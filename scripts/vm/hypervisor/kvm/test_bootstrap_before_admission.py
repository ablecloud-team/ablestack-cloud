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

"""Queued VMs publish bootstrap ownership before waiting for the shared Host slot."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import thirdparty_bootstrap as bootstrap
import thirdparty_volume_backup as backup


class BootstrapBeforeAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.stage = self.base / "stage"
        self.stage.mkdir()

    def worker(self, name, granted=False):
        worker = backup.Backup.__new__(backup.Backup)
        worker.job = self.base / "jobs" / name
        worker.job.mkdir(parents=True)
        worker.root = self.stage / name / "point"
        worker.engine = worker.job / "volume-engine.json"
        worker.plan = {"stageRoot": str(self.stage), "rbd": True, "bufferPercent": 20,
                       "sourceHost": "ablecube4", "queueTimeout": 3600}
        worker.manifest = {"backupUuid": name, "volumes": [{"provisionedBytes": 1024}]}
        worker.progress = Mock()
        worker.check_cancel = Mock()
        if granted:
            (worker.job / "staging-admission-granted").touch()
        return worker

    def test_parent_can_collect_both_markers_while_second_vm_waits_for_the_slot(self):
        first = self.worker("first", granted=True)
        queued = self.worker("second")

        class QueueObserved(Exception):
            pass

        def queue_wait(_):
            self.assertTrue((first.root / ".volume-bootstrap").is_file())
            self.assertEqual("second", (queued.root / ".volume-bootstrap").read_text().strip())
            marker = bootstrap.publish(self.stage / "bootstrap/policy", queued.root / ".volume-bootstrap", "second")
            self.assertTrue(Path(marker).is_file())
            self.assertTrue((queued.job / "staging-admission-request.json").is_file())
            self.assertFalse((queued.job / "staging-admission-granted").exists())
            self.assertFalse((queued.job / "volume-start").exists())
            self.assertFalse(list(queued.root.glob("*.raw")))
            raise QueueObserved()

        with patch("thirdparty_primary_capacity.filesystem_capacity", return_value={"storageKey": "stage"}), \
                patch.object(backup, "staging_identity", return_value="filesystem-test"), \
                patch.object(backup, "admission_closed_reason", return_value=None):
            first.reserve()
            with patch.object(backup.time, "sleep", side_effect=queue_wait), self.assertRaises(QueueObserved):
                queued.reserve()
        queued.progress.assert_called_with("WAITING", 0)

    def test_existing_directory_is_never_reused_or_overwritten(self):
        worker = self.worker("existing", granted=True)
        worker.root.mkdir(parents=True)
        image = worker.root / "disk.raw"
        image.write_text("previous source")
        with patch("thirdparty_primary_capacity.filesystem_capacity", return_value={"storageKey": "stage"}), \
                patch.object(backup, "staging_identity", return_value="filesystem-test"), \
                patch.object(backup, "admission_closed_reason", return_value=None), self.assertRaises(FileExistsError):
            worker.reserve()
        self.assertEqual("previous source", image.read_text())
        self.assertFalse((worker.root / ".volume-bootstrap").exists())


if __name__ == "__main__":
    unittest.main()
