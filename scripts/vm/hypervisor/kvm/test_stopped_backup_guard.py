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
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import thirdparty_stopped_backup_guard as guard
import thirdparty_volume_backup as volume


class StoppedBackupGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.patch = patch.object(guard, "ROOT", self.root / "guards")
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.manifest = {"vmName": "vm", "backupUuid": "backup-1", "backupType": "FULL",
                         "volumes": [{"engine": "RBD_DIFF", "chain": [{"path": str(self.root / name)}]}
                                     for name in ("root.raw", "data.raw")]}
        self.plan = {"version": 1, "jobId": "backup-1", "protectStoppedSource": True, "manifest": self.manifest}
        self.source = {"vmName": "vm", "manifest": self.manifest, "backupStartPlan": self.plan,
                       "rbd": True, "checkpointName": "checkpoint-1", "diskPaths": ["pool/root", "pool/data"]}
        directory = guard.ROOT / "vm"
        directory.mkdir(parents=True)
        self.file = directory / "backup-1.json"
        self.file.write_text(json.dumps({"version": 1, "plan": self.plan, "phase": "PROTECTED", "released": False}))

    def proof(self):
        return json.loads(self.file.read_text())

    def rbd_worker(self):
        worker = volume.Backup.__new__(volume.Backup)
        worker.plan, worker.vm, worker.manifest = self.source, "vm", self.manifest
        worker.root = self.root
        worker.rbd_command = lambda uri: (["rbd"], uri)
        worker.thaw_guest = Mock()
        return worker

    def test_rbd_keeps_all_disks_protected_until_last_snapshot_completes(self):
        worker = self.rbd_worker()
        created = []

        def run(args):
            self.assertFalse(self.proof()["released"])
            if args[1:3] == ["snap", "create"]:
                created.append(args[-1])
            return ""

        with patch.object(volume, "run", side_effect=run):
            with guard.prepare(self.source, lambda: True):
                worker.begin_rbd()
        self.assertEqual(["pool/root@checkpoint-1", "pool/data@checkpoint-1"], created)
        self.assertEqual("SNAPSHOTS_COMPLETE", self.proof()["phase"])
        self.assertTrue(self.proof()["released"])

    def test_second_disk_snapshot_failure_retains_protection(self):
        worker = self.rbd_worker()

        def run(args):
            if args[1:3] == ["snap", "create"] and args[-1].startswith("pool/data@"):
                raise RuntimeError("snapshot failed")
            return ""

        with patch.object(volume, "run", side_effect=run):
            with self.assertRaisesRegex(RuntimeError, "snapshot failed"):
                with guard.prepare(self.source, lambda: True):
                    worker.begin_rbd()
        self.assertFalse(self.proof()["released"])

    def test_qcow2_remains_protected_after_dummy_preparation(self):
        self.source["rbd"] = False
        for disk in self.manifest["volumes"]:
            disk["engine"] = "QCOW2"
        self.file.write_text(json.dumps({"version": 1, "plan": self.plan, "phase": "PROTECTED", "released": False}))
        with guard.prepare(self.source, lambda: True):
            pass
        self.assertFalse(self.proof()["released"])
        guard.cleaned(self.source)
        self.assertEqual("SOURCE_CLEANED", self.proof()["phase"])

    def test_qcow2_dummy_destroy_requires_confirmed_termination(self):
        self.source["rbd"] = False
        for disk in self.manifest["volumes"]:
            disk["engine"] = "QCOW2"
        self.file.write_text(json.dumps({"version": 1, "plan": self.plan, "phase": "PROTECTED", "released": False}))
        job = self.root / "job"
        job.mkdir()
        (job / "dummy.xml").write_text("dummy ownership")
        state = subprocess.CompletedProcess([], 0, "paused", "")
        with patch.object(Path, "iterdir", return_value=iter([])), \
                patch.object(volume.subprocess, "run", return_value=state), \
                patch.object(volume, "run", return_value="Job type: None"), \
                patch.object(volume, "guest_is_off_or_absent", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "termination is unconfirmed"):
                volume.cleanup_source(self.source, job, job / "engine.json")
        self.assertFalse(self.proof()["released"])
        with patch.object(Path, "iterdir", return_value=iter([])), \
                patch.object(volume.subprocess, "run", return_value=state), \
                patch.object(volume, "run", return_value="Job type: None"), \
                patch.object(volume, "guest_is_off_or_absent", return_value=True):
            volume.cleanup_source(self.source, job, job / "engine.json")
        self.assertTrue(self.proof()["released"])

    def test_active_vm_is_rejected_before_source_operations(self):
        with self.assertRaisesRegex(RuntimeError, "source is active"):
            with guard.prepare(self.source, lambda: False):
                self.fail("Source preparation must not run")
        self.assertFalse(self.proof()["released"])

    def test_orphan_qemu_prevents_dummy_release_after_libvirt_destroy(self):
        self.source["rbd"] = False
        job = self.root / "job"
        job.mkdir()
        (job / "dummy.xml").write_text("dummy ownership")
        process = Mock()
        process.name = "1234"
        process.joinpath.return_value.read_bytes.return_value = b"qemu-kvm\0-name\0guest=DUMMY-VOLUME-backup-1,debug-threads=on\0"
        with patch.object(Path, "iterdir", side_effect=lambda: iter([process])), \
                patch.object(volume.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "paused", "")), \
                patch.object(volume, "run", return_value="Job type: None"), \
                patch.object(volume, "guest_is_off_or_absent", return_value=True):
            with self.assertRaisesRegex(RuntimeError, "QEMU still owns source disks"):
                volume.cleanup_source(self.source, job, job / "engine.json")
        self.assertFalse(self.proof()["released"])

    def test_qcow2_runtime_change_cannot_enter_an_unprotected_dummy_path(self):
        self.source["rbd"] = False
        worker = volume.Backup.__new__(volume.Backup)
        worker.job, worker.vm, worker.plan, worker.manifest = self.root, "vm", self.source, self.manifest
        (self.root / "backup-primary-capacity.json").write_text("{}")
        for protected, runtime in ((False, "shut off"), (True, "running")):
            with self.subTest(protected=protected, runtime=runtime):
                self.plan["protectStoppedSource"] = protected
                with patch("thirdparty_primary_capacity.backup_capacity", return_value={"primaryClaims": []}), \
                        patch.object(volume, "run", side_effect=["<domain/>", runtime]) as run:
                    with self.assertRaisesRegex(RuntimeError, "protection|became active"):
                        worker.begin_qcow2()
                    self.assertEqual(2, run.call_count)
                    self.assertFalse((self.root / "dummy.xml").exists())

    def test_released_or_mismatched_job_cannot_restart_source(self):
        guard.cleaned(self.source)
        with self.assertRaisesRegex(RuntimeError, "already released"):
            with guard.prepare(self.source, lambda: True):
                self.fail("A released source must not run again")
        self.plan["hostId"] = 99
        with self.assertRaisesRegex(RuntimeError, "ownership is unconfirmed"):
            guard.cleaned(self.source)

    def test_missing_ownership_and_cleanup_failure_remain_blocked(self):
        self.file.unlink()
        with self.assertRaises(FileNotFoundError):
            with guard.prepare(self.source, lambda: True):
                self.fail("Missing ownership must not permit backup IO")

    def test_orphaned_snapshot_command_prevents_cleanup_release(self):
        self.source["diskPaths"] = ["rbd:pool/root", "rbd:pool/data"]
        process = Mock()
        process.name = "1234"
        process.joinpath.return_value.read_bytes.return_value = b"rbd\0snap\0create\0pool/root@checkpoint-1\0"
        with patch.object(Path, "iterdir", return_value=iter([process])):
            with self.assertRaisesRegex(RuntimeError, "control process is still active"):
                volume.cleanup_source(self.source, self.root, self.root / "engine.json")
        self.assertFalse(self.proof()["released"])

    def test_orphaned_dummy_creation_prevents_cleanup_release(self):
        self.source["rbd"] = False
        process = Mock()
        process.name = "1234"
        process.joinpath.return_value.read_bytes.return_value = ("virsh\0create\0%s\0--paused\0" %
                                                               (self.root / "dummy.xml")).encode()
        with patch.object(Path, "iterdir", return_value=iter([process])):
            with self.assertRaisesRegex(RuntimeError, "control process is still active"):
                volume.cleanup_source(self.source, self.root, self.root / "engine.json")
        self.assertFalse(self.proof()["released"])

    def test_running_and_legacy_backups_do_not_acquire_stopped_guard(self):
        self.plan["protectStoppedSource"] = False
        with guard.prepare(self.source, lambda: self.fail("Must not inspect a running backup")):
            pass

    def test_transfer_progress_does_not_invalidate_cleanup_ownership(self):
        self.source.update(backupPath=str(self.root / "staging"), timeout=300)
        file = self.root / "volume-plan.json"
        file.write_text(json.dumps(self.source))
        worker = volume.Backup(file)
        worker.manifest["complete"] = True
        worker.manifest["volumes"][0]["chain"][-1]["size"] = 1234
        self.assertNotIn("complete", worker.plan["manifest"])
        self.assertNotIn("size", worker.plan["manifest"]["volumes"][0]["chain"][-1])
        guard.cleaned(worker.plan)
        self.assertEqual("SOURCE_CLEANED", self.proof()["phase"])


if __name__ == "__main__":
    unittest.main()
