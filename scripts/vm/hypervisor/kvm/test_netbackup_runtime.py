#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
import fcntl
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import netbackup_runtime as runtime


class NetBackupRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.source = self.root / "package"
        self.source.mkdir()
        self.target = self.root / "runtime"
        self.target.mkdir()
        self.config = self.root / "watcher.conf"
        self.lock = self.root / "watcher.lock"
        self.config.write_text('LOCK_FILE="%s"\nNETBACKUP_APIKEY="keep-existing-credentials"\n' % self.lock)
        self.service = self.root / "watcher.service"
        self.service.write_text("[Service]\nExecStart=/original/watcher --once\n")
        self.watcher = self.target / "watcher"
        self.watcher.write_text("old version")
        for name in ["netbackup-host-restore-watcher.py", "thirdparty_restore_metadata.py"]:
            (self.source / name).write_text("version = 'updated'\n")
        (self.source / "netbackup-host-restore-notify").write_text("#!/bin/sh\nexit 0\n")
        for key, value in dict(SOURCE=self.source, CONFIG=self.config, SERVICE=self.service,
                               WATCHER=self.watcher, NOTIFY=self.target / "notify").items():
            p = patch.object(runtime, key, value)
            p.start()
            self.addCleanup(p.stop)

    def test_configured_host_refreshes_executables_without_changing_credentials(self):
        before = self.config.read_bytes()
        with patch.object(runtime.subprocess, "run") as run:
            self.assertTrue(runtime.install())
        run.assert_called_once_with(["systemctl", "daemon-reload"], check=True, timeout=30)
        self.assertEqual(before, self.config.read_bytes())
        self.assertEqual((self.source / "netbackup-host-restore-watcher.py").read_bytes(), self.watcher.read_bytes())
        dropin = self.service.with_name(self.service.name + ".d") / "20-mold-runtime-refresh.conf"
        self.assertIn("ExecStartPre=/usr/bin/python3", dropin.read_text())
        self.assertIn("--refresh", dropin.read_text())
        timestamp = self.watcher.stat().st_mtime_ns
        self.assertFalse(runtime.refresh())
        self.assertEqual(timestamp, self.watcher.stat().st_mtime_ns)

    def test_fresh_package_install_does_not_enable_or_configure_watcher(self):
        self.config.unlink()
        with patch.object(runtime.subprocess, "run") as run:
            self.assertFalse(runtime.install())
            self.assertFalse(runtime.refresh())
        run.assert_not_called()
        self.assertEqual("old version", self.watcher.read_text())

    def test_busy_watcher_is_not_interrupted_and_timer_can_retry(self):
        with self.lock.open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                runtime.refresh()
        self.assertEqual("old version", self.watcher.read_text())
        self.assertTrue(runtime.refresh())

    def test_missing_packaged_dependency_cannot_partially_replace_runtime(self):
        (self.source / "netbackup-host-restore-notify").unlink()
        with self.assertRaisesRegex(RuntimeError, "incomplete"):
            runtime.refresh()
        self.assertEqual("old version", self.watcher.read_text())

    def test_redirected_runtime_is_not_overwritten(self):
        self.watcher.unlink()
        protected = self.root / "operator-file"
        protected.write_text("keep")
        self.watcher.symlink_to(protected)
        with self.assertRaisesRegex(RuntimeError, "redirected"):
            runtime.refresh()
        self.assertEqual("keep", protected.read_text())


if __name__ == "__main__":
    unittest.main()
