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

import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[6] / "scripts/vm/hypervisor/kvm"))
import thirdparty_staging_cleanup as cleanup
import thirdparty_volume_backup as backup


class CompletedStagingCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.stage = self.base / "stage"
        self.root = self.stage / "ablestack-commvault/i-2-220-VM/2026.10.12.16.55.52.320"
        self.root.mkdir(parents=True)
        self.job = self.base / "control/jobs/backup-1"
        self.job.mkdir(parents=True)
        self.reservations = self.stage / ".volume-reservations"
        self.reservations.mkdir()
        (self.reservations / "filesystem.id").write_text("fs-1")
        (self.reservations / "backup-1.json").write_text("{}")
        (self.root / ".volume-bootstrap").write_text("backup-1")
        (self.root / "root.qcow2").write_bytes(b"volume")
        (self.root / "backup-manifest.json").write_text("{}")
        (self.root / "domain-config.xml").write_text("<domain/>")
        (self.root / ".staging.complete").write_text("complete")
        (self.root / "checkpoints").mkdir()
        (self.root / "checkpoints/current.xml").write_text("<domaincheckpoint/>")
        self.source = self.base / "primary/current-bitmap"
        self.source.parent.mkdir()
        self.source.write_text("incremental baseline")
        manifest = {
            "version": 1, "backupUuid": "backup-1", "provider": "ablestack-commvault",
            "vmName": "i-2-220-VM", "timestamp": self.root.name, "backupType": "FULL",
            "volumes": [{"uuid": "volume-1", "deviceId": 0, "provisionedBytes": 100,
                         "engine": "QCOW2", "chain": [{"backupUuid": "backup-1", "path": str(self.root / "root.qcow2")}]}]
        }
        self.plan = {"manifest": manifest, "stageRoot": str(self.stage), "backupPath": str(self.root)}
        self.manifest = copy.deepcopy(manifest)
        self.manifest["complete"] = True
        artifact = self.manifest["volumes"][0]["chain"][0]
        artifact.update(completed=True, jobId="1127", externalId="1127", sourceHost="ablecube4")
        self.manifest["metadata"] = {"backupUuid": "backup-1", "path": str(self.root), "completed": True,
                                     "jobId": "1129", "externalId": "1129", "sourceHost": "ablecube4"}
        backup.atomic(self.job / "volume-engine.json", {"stageFilesystemId": "fs-1"})
        self.mountinfo = "1 0 0:1 / / rw - ext4 /dev/test rw\n"
        # Exercise the real path/symlink checks with a portable /proc mountinfo fixture.
        def cleanup_path(value):
            if str(value) == "/proc/self/mountinfo":
                return mock.Mock(read_text=lambda: self.mountinfo)
            return Path(value)
        patch = mock.patch.object(cleanup, "Path", side_effect=cleanup_path)
        patch.start()
        self.addCleanup(patch.stop)
        patch = mock.patch.object(backup, "cleanup_source")
        self.source_cleanup = patch.start()
        self.addCleanup(patch.stop)

    def finalize(self, purge=True):
        return backup.finalize_host(self.plan, self.job, self.manifest, purge_staging=purge)

    def test_engine_keeps_metadata_until_controller_authorizes_purge(self):
        self.finalize(purge=False)
        self.assertFalse((self.root / "root.qcow2").exists())
        self.assertTrue((self.root / "backup-manifest.json").exists())
        self.assertTrue((self.root / "checkpoints/current.xml").exists())
        self.source_cleanup.assert_called_once_with(self.plan, self.job, self.job / "volume-engine.json", keep_checkpoint=True)

    def test_controller_removes_every_staged_copy_and_preserves_source_and_other_backup(self):
        other = self.root.parent / "2026.10.12.16.50.00.000"
        other.mkdir()
        (other / "keep.xml").write_text("other backup")
        result = self.finalize()
        self.assertEqual("COMPLETED", result["stagingCleanupState"])
        self.assertFalse(self.root.exists())
        self.assertTrue((other / "keep.xml").exists())
        self.assertEqual("incremental baseline", self.source.read_text())
        self.assertFalse((self.reservations / "backup-1.json").exists())
        self.finalize()  # Lost response/restart does not require deleted metadata.
        self.source_cleanup.assert_called_once()

    def test_legacy_finalization_receipt_does_not_skip_metadata_cleanup(self):
        backup.atomic(self.job / "volume-finalization.json", {"version": 1, "backupUuid": "backup-1", "state": "COMPLETED"})
        self.finalize()
        self.assertFalse(self.root.exists())
        self.assertFalse(self.root.parent.exists())
        self.source_cleanup.assert_not_called()

    def test_partial_directory_deletion_is_retryable_after_ownership_marker_was_removed(self):
        def interrupted_remove(root):
            (root / ".volume-bootstrap").unlink()
            (root / "domain-config.xml").unlink()
            raise OSError("temporary storage failure")
        with mock.patch.object(backup.shutil, "rmtree", side_effect=interrupted_remove):
            with self.assertRaises(OSError):
                self.finalize()
        result = json.loads((self.job / "volume-finalization.json").read_text())
        self.assertEqual("WAITING", result["stagingCleanupState"])
        self.finalize()
        self.assertFalse(self.root.exists())
        self.assertEqual("COMPLETED", json.loads((self.job / "volume-finalization.json").read_text())["stagingCleanupState"])

    def test_unconfirmed_external_metadata_is_never_removed(self):
        self.manifest["metadata"]["completed"] = False
        with self.assertRaisesRegex(RuntimeError, "metadata transfer is not confirmed"):
            self.finalize()
        self.assertTrue((self.root / "root.qcow2").exists())
        self.source_cleanup.assert_not_called()

    def test_wrong_directory_owner_and_changed_filesystem_are_protected(self):
        (self.root / ".volume-bootstrap").write_text("another-backup")
        with self.assertRaisesRegex(RuntimeError, "ownership is unconfirmed"):
            self.finalize()
        (self.root / ".volume-bootstrap").write_text("backup-1")
        (self.reservations / "filesystem.id").write_text("replacement-filesystem")
        with self.assertRaisesRegex(RuntimeError, "filesystem changed"):
            self.finalize()
        self.assertTrue((self.root / "backup-manifest.json").exists())

    def test_symlink_and_nested_mount_are_protected(self):
        self.mountinfo += "2 1 0:2 / %s rw - ext4 /dev/other rw\n" % (self.root / "checkpoints")
        with self.assertRaisesRegex(RuntimeError, "mount point"):
            self.finalize()
        self.mountinfo = self.mountinfo.splitlines()[0] + "\n"
        original = self.root.with_name("protected-data")
        self.root.rename(original)
        self.root.symlink_to(original, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, "symbolic link"):
            self.finalize()
        self.assertTrue((original / "backup-manifest.json").exists())


class ParentCheckpointCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.job = self.root / "backup-2"
        self.job.mkdir()
        self.paths = [str(self.root / "root.qcow2"), str(self.root / "data.qcow2")]
        self.parent_xml = self.xml("parent", "ancestor")
        self.current_xml = self.xml("current", "parent")
        (self.job / "checkpoint.xml").write_text(self.current_xml)
        (self.job / "pull.xml").write_text('<domainbackup><disks><disk name="vda"/><disk name="vdb"/></disks></domainbackup>')
        self.plan = {
            "rbd": False, "vmName": "vm", "checkpointName": "current", "parentCheckpointName": "parent",
            "diskPaths": self.paths,
            "parentCheckpointXmlChain": {"/archived/parent.xml": self.parent_xml, "/archived/ancestor.xml": self.xml("ancestor")},
            "manifest": {"backupUuid": self.job.name, "volumes": [
                {"chain": [{"checkpointName": "current", "parentCheckpointName": "parent"}]} for _ in self.paths]}
        }
        self.bitmaps = [{name: {"name": name, "recording": True, "persistent": True, "flags": ["auto"]}
                         for name in ["ancestor", "parent", "current", "unrelated"]} for _ in self.paths]
        self.metadata = {"ancestor", "parent", "current", "unrelated"}
        self.offline = False
        self.active_job = False
        self.fail_disk = None
        self.monitor_error = False
        self.reject_removal = False
        self.backing_parent = False
        self.expected_registration = "parent"
        self.calls = []
        patcher = mock.patch.object(backup, "run", side_effect=self.run_command)
        patcher.start()
        self.addCleanup(patcher.stop)

    @staticmethod
    def xml(name, parent=None):
        return ('<domaincheckpoint><name>%s</name>%s<disks>'
                '<disk name="vda" checkpoint="bitmap" bitmap="%s"/>'
                '<disk name="vdb" checkpoint="bitmap" bitmap="%s"/>'
                '</disks></domaincheckpoint>') % (name, '<parent><name>%s</name></parent>' % parent if parent else "", name, name)

    def run_command(self, args, timeout=300):
        self.calls.append(args)
        if args[0] == "qemu-img":
            if args[1] == "info":
                index = self.paths.index(args[-1])
                images = [{"format": "qcow2", "format-specific": {"data": {"bitmaps": list(self.bitmaps[index].values())}}}]
                if self.backing_parent:
                    images.append({"format-specific": {"data": {"bitmaps": [{"name": "parent"}]}}})
                return json.dumps(images)
            self.assertEqual(["qemu-img", "bitmap", "--remove"], args[:3])
            self.remove(self.paths.index(args[3]), args[4])
            return ""
        command = args[3]
        if command == "domstate":
            return "shut off" if self.offline else "running"
        if command == "list":
            return "vm\n"
        if command == "domjobinfo":
            return "Job type: Unbounded" if self.active_job else "Job type: None"
        if command == "checkpoint-list":
            return "\n".join(self.metadata)
        if command == "checkpoint-dumpxml":
            return self.parent_xml
        if command == "checkpoint-delete":
            self.assertIn(args[5:], [["parent"], ["parent", "--metadata"]])
            if args[5:] == ["parent"]:
                for i, bitmaps in enumerate(self.bitmaps):
                    if "parent" in bitmaps:
                        self.remove(i, "parent")
            self.metadata.remove("parent")
            return ""
        if command == "checkpoint-create":
            xml = ET.fromstring(Path(args[6]).read_text())
            self.assertEqual(self.expected_registration, xml.findtext("name"))
            self.assertIsNone(xml.find("parent"))
            self.assertIn("--redefine-validate", args)
            self.metadata.add(self.expected_registration)
            return ""
        if command == "qemu-monitor-command":
            request = json.loads(args[-1])
            if self.monitor_error:
                return json.dumps({"error": {"desc": "monitor rejected command"}})
            if request["execute"] == "query-block":
                devices = [{"inserted": {"node-name": "node-%d" % i, "image": {"filename": path},
                                         "dirty-bitmaps": list(self.bitmaps[i].values())}} for i, path in enumerate(self.paths)]
                if self.backing_parent:
                    devices[0]["inserted"]["image"]["backing-filename"] = str(self.root / "backing.qcow2")
                return json.dumps({"return": devices})
            if request["execute"] == "query-named-block-nodes":
                return json.dumps({"return": [{"drv": "qcow2", "image": {"filename": str(self.root / "backing.qcow2")},
                                               "dirty-bitmaps": [{"name": "parent"}]}]})
            self.assertEqual("block-dirty-bitmap-remove", request["execute"])
            if self.reject_removal:
                return json.dumps({"error": {"desc": "bitmap is busy"}})
            self.remove(int(request["arguments"]["node"].split("-")[1]), request["arguments"]["name"])
            return json.dumps({"return": {}})
        raise AssertionError("Unexpected command: %s" % args)

    def remove(self, index, name):
        self.assertEqual("parent", name)
        if index == self.fail_disk:
            raise RuntimeError("disk is busy")
        self.bitmaps[index].pop(name)

    def clean(self):
        return backup.retire_parent_snapshots(self.plan, self.job)

    def test_live_and_offline_cleanup_remove_only_the_recorded_parent(self):
        for self.offline in [False, True]:
            with self.subTest(offline=self.offline):
                self.assertTrue(self.clean())
                self.assertEqual({"ancestor", "parent", "current", "unrelated"}, self.metadata)
                self.assertTrue(all(set(bitmaps) == {"ancestor", "current", "unrelated"} for bitmaps in self.bitmaps))
                receipt = json.loads((self.job / "source-cleanup.json").read_text())
                self.assertEqual(2, receipt["version"])
                self.assertEqual("COMPLETED", receipt["state"])
                self.assertEqual(self.paths, receipt["diskPaths"])
                self.assertTrue(self.clean())  # Repeated confirmation is harmless.
                self.assertEqual(self.parent_xml, self.plan["parentCheckpointXmlChain"]["/archived/parent.xml"])
                for bitmaps in self.bitmaps:
                    bitmaps["parent"] = {"name": "parent"}

    def test_current_equals_parent_is_rejected_before_any_source_operation(self):
        self.plan["checkpointName"] = "parent"
        self.assertFalse(self.clean())
        self.assertEqual([], self.calls)

    def test_partial_failure_is_waiting_and_retries_only_the_remaining_parent(self):
        self.fail_disk = 1
        self.assertFalse(self.clean())
        self.assertIn("parent", self.metadata)
        receipt = json.loads((self.job / "source-cleanup.json").read_text())
        self.assertEqual("WAITING", receipt["state"])
        self.assertTrue(receipt["errors"])
        self.fail_disk = None
        self.assertTrue(self.clean())
        self.assertTrue(all("current" in bitmaps for bitmaps in self.bitmaps))

    def test_missing_current_bitmap_on_one_disk_blocks_every_deletion(self):
        del self.bitmaps[1]["current"]
        self.assertFalse(self.clean())
        self.assertTrue(all("parent" in bitmaps for bitmaps in self.bitmaps))

    def test_active_job_or_monitor_query_failure_never_means_absence(self):
        for active_job, monitor_error in [(True, False), (False, True)]:
            self.active_job, self.monitor_error = active_job, monitor_error
            self.assertFalse(self.clean())
            self.assertTrue(all("parent" in bitmaps for bitmaps in self.bitmaps))

    def test_saved_parent_reference_must_match_every_volume(self):
        self.plan["manifest"]["volumes"][1]["chain"][-1]["parentCheckpointName"] = "other"
        self.assertFalse(self.clean())
        self.assertEqual([], self.calls)

    def test_conflicting_live_parent_metadata_is_preserved(self):
        self.parent_xml = self.xml("parent").replace('bitmap="parent"', 'bitmap="different"')
        self.assertFalse(self.clean())
        self.assertIn("parent", self.metadata)
        self.assertTrue(all("parent" in bitmaps for bitmaps in self.bitmaps))

    def test_next_incremental_redefines_only_the_immediate_baseline_when_metadata_is_lost(self):
        self.assertTrue(self.clean())
        self.metadata = set()
        next_plan = copy.deepcopy(self.plan)
        next_plan["parentCheckpointName"] = "current"
        next_plan["parentCheckpointXmlChain"]["/archived/current.xml"] = self.current_xml
        self.expected_registration = "current"
        backup.redefine_parent_checkpoint(next_plan, "vm", self.job)
        self.assertEqual({"current"}, self.metadata)
        self.assertEqual(self.parent_xml, self.plan["parentCheckpointXmlChain"]["/archived/parent.xml"])
        self.assertEqual({"ancestor", "current", "unrelated"}, set(self.bitmaps[0]))

    def test_shared_backing_parent_is_retained_for_live_and_offline_sources(self):
        self.backing_parent = True
        for self.offline in [False, True]:
            self.assertFalse(self.clean())
            self.assertTrue(all("parent" in bitmaps for bitmaps in self.bitmaps))

    def test_qmp_error_with_successful_virsh_exit_is_not_completion(self):
        self.reject_removal = True
        self.assertFalse(self.clean())
        self.assertTrue(all("parent" in bitmaps for bitmaps in self.bitmaps))

    def test_rbd_retirement_preserves_current_and_unrelated_snapshots(self):
        self.plan["rbd"] = True
        self.plan["diskPaths"] = ["image-0", "image-1"]
        snapshots = {image: {"parent", "current", "unrelated"} for image in self.plan["diskPaths"]}

        def rbd_run(args, timeout=300):
            if args[1:3] == ["snap", "ls"]:
                return json.dumps([{"name": name} for name in snapshots[args[-1]]])
            self.assertEqual(["rbd", "snap", "rm"], args[:3])
            image, name = args[-1].split("@")
            self.assertEqual("parent", name)
            snapshots[image].remove(name)
            return ""

        with mock.patch.object(backup.Backup, "rbd_command", side_effect=lambda image: (["rbd"], image)), \
                mock.patch.object(backup, "run", side_effect=rbd_run):
            self.assertTrue(self.clean())
            self.assertTrue(self.clean())
        self.assertTrue(all(names == {"current", "unrelated"} for names in snapshots.values()))


if __name__ == "__main__":
    unittest.main()
