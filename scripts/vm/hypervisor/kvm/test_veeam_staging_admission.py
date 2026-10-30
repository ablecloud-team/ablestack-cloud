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

"""Veeam parents validate staging before requests, including empty due lists."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

DIRECTORY = Path(__file__).resolve().parent


class VeeamStagingAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        library_dir = self.root / "veeam"
        library_dir.mkdir()
        self.library = library_dir / "mold-backup.lib.sh"
        shutil.copyfile(DIRECTORY / "veeam/mold-backup.lib.sh", self.library)
        (self.root / "thirdparty_staging.sh").write_text(r'''#!/bin/bash
python3 - "$@" <<'PYVALIDATE'
import json, os, pathlib, sys
root = pathlib.Path(os.environ['TEST_ROOT'])
(root / 'validation.json').write_text(json.dumps(sys.argv[1:]))
if os.environ.get('TEST_UNAVAILABLE') == 'true':
    print('Unable to mount the configured NFS export.', file=sys.stderr)
    sys.exit(1)
pathlib.Path(sys.argv[6]).mkdir(parents=True, exist_ok=True)
print(123456)
PYVALIDATE
''')
        bindir = self.root / "bin"
        bindir.mkdir()
        timeout = bindir / "timeout"
        timeout.write_text('#!/bin/bash\nshift 3\nexec "$@"\n')
        timeout.chmod(0o755)
        self.payload = self.root / "stage/ablestack-veeam/bootstrap/host-test/parent-policy"
        self.env = dict(os.environ, TEST_ROOT=str(self.root), TEST_LIBRARY=str(self.library),
                        VEEAM_HOST_BACKUP_PATH=str(self.root / "stage/ablestack-veeam"),
                        VEEAM_AGENT_PAYLOAD_PATH=str(self.payload), VEEAM_AGENT_PAYLOAD_MODE="COMMON",
                        ABLESTACK_VEEAM_STATE_DIR=str(self.root / "state"),
                        PATH=str(bindir) + os.pathsep + os.environ['PATH'])

    def run_hook(self, **settings):
        script = r'''set -euo pipefail
source "$TEST_LIBRARY"
mold_backup_load_config() { :; }
mold_backup_notify_log() { echo "$*" >> "$TEST_ROOT/events"; }
mold_backup_api_list_config_value() {
  case "$1" in
    *.storage.type) echo nfs;;
    *.mount.path) echo "$TEST_ROOT/stage";;
    *.nfs.source) echo '192.168.1.161:/backup';;
    *.mount.options) echo '';;
    *.mount.timeout) echo "${TEST_MOUNT_TIMEOUT:-300}";;
  esac
}
mold_backup_api_external_candidates() {
  echo CANDIDATES >> "$TEST_ROOT/events"
  echo '{"selectionmode":"SCHEDULE","candidate":[]}'
}
KVM_HOSTNAME=host-test
mold_backup_pre_notify host-test "${TEST_JOB:-parent-policy}" default
'''
        return subprocess.run(["bash", "-c", script], env=dict(self.env, **settings),
                              text=True, capture_output=True, timeout=10)

    def test_storage_outage_rejects_before_creating_bootstrap_or_querying_vms(self):
        for _ in range(3):
            result = self.run_hook(TEST_UNAVAILABLE="true")
            self.assertNotEqual(0, result.returncode, result.stderr)
        self.assertFalse(self.payload.exists())
        self.assertNotIn("CANDIDATES", (self.root / "events").read_text())
        self.assertIn("BACKUP_START_DEFERRED", (self.root / "events").read_text())

    def test_storage_recovery_prepares_empty_parent_directory_even_with_no_due_vm(self):
        self.assertNotEqual(0, self.run_hook(TEST_UNAVAILABLE="true").returncode)
        result = self.run_hook()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(self.payload.is_dir())
        self.assertEqual([], list(self.payload.iterdir()))
        self.assertIn("CANDIDATES", (self.root / "events").read_text())
        arguments = json.loads((self.root / "validation.json").read_text())
        self.assertEqual(["NFS", str(self.root / "stage"), str(self.root / "stage"),
                          "192.168.1.161:/backup", "", str(self.payload), "15", "0"], arguments)

    def test_custom_parent_marker_path_still_requires_healthy_volume_staging(self):
        custom = self.root / "custom-markers"
        result = self.run_hook(VEEAM_AGENT_PAYLOAD_MODE="CUSTOM", VEEAM_AGENT_PAYLOAD_PATH=str(custom), TEST_UNAVAILABLE="true")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(custom.exists())
        result = self.run_hook(VEEAM_AGENT_PAYLOAD_MODE="CUSTOM", VEEAM_AGENT_PAYLOAD_PATH=str(custom))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(custom.is_dir())
        self.assertEqual(str(self.root / "stage/ablestack-veeam"), json.loads((self.root / "validation.json").read_text())[5])

    def test_transfer_jobs_never_recursively_validate_or_request_vm_backups(self):
        result = self.run_hook(TEST_JOB="ABLESTACK-H-0123456789abcdef0123456789abcdef", TEST_UNAVAILABLE="true")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.root / "validation.json").exists())
        self.assertFalse((self.root / "events").exists())

    def test_shorter_configured_mount_timeout_is_retained(self):
        result = self.run_hook(TEST_MOUNT_TIMEOUT="5")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("5", json.loads((self.root / "validation.json").read_text())[6])


if __name__ == "__main__":
    unittest.main()
