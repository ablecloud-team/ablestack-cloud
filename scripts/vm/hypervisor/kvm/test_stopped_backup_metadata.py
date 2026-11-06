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
import contextlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

import thirdparty_restore_metadata as metadata
import thirdparty_volume_backup as pipeline


class StoppedBackupMetadataTest(unittest.TestCase):
    def worker(self, directory, provider, engine, incremental=False):
        root = Path(directory)
        job = root / "jobs" / "backup-1"
        job.mkdir(parents=True)
        stage = root / "stage" / provider / "i-2-336-VM" / "point"
        stage.mkdir(parents=True)
        suffix = ".rbdiff" if engine == "RBD_DIFF" and incremental else ".raw" if engine == "RBD_DIFF" else ".qcow2"
        volumes = []
        for index, name in enumerate(("root", "data")):
            artifact = {"backupUuid": "backup-1", "path": str(stage / (name + suffix)), "checkpointName": "point",
                        "sourceHost": "host-1", "completed": False}
            parent = {"backupUuid": "parent", "path": str(stage.parent / "parent" / (name + (".raw" if engine == "RBD_DIFF" else ".qcow2"))),
                      "checkpointName": "parent", "sourceHost": "host-1", "completed": True, "externalId": "parent-image", "jobId": "parent-job"}
            volumes.append({"uuid": name, "deviceId": index, "provisionedBytes": 1024, "engine": engine,
                            "chain": [parent, artifact] if incremental else [artifact]})
        manifest = {"version": 1, "provider": provider, "backupUuid": "backup-1", "vmName": "i-2-336-VM", "timestamp": "point",
                    "backupType": "INCREMENTAL" if incremental else "FULL", "volumes": volumes,
                    "sourceConfiguration": {"vmUuid": "original-vm-uuid", "details": {"serviceofferingid": "original-offering", "nics": "original-network"}}}
        plan = {"manifest": manifest, "vmName": manifest["vmName"], "backupPath": str(stage), "rbd": engine == "RBD_DIFF",
                "sourceHost": "host-1", "providerStep": "TRANSFER", "timeout": 300, "backupStartPlan": {"protectStoppedSource": True}}
        file = job / "volume-plan.json"
        file.write_text(json.dumps(plan))
        worker = pipeline.Backup(file)
        worker.check_cancel = Mock()
        worker.reserve = Mock()
        worker.preflight_qcow2 = Mock()
        worker.progress = Mock()
        worker.begin_qcow2 = Mock()
        worker.begin_rbd = Mock()
        worker.export_qcow2 = Mock(side_effect=lambda index, target: target.write_bytes(b"data"))
        worker.export_rbd = Mock(side_effect=lambda index, target: target.write_bytes(b"data"))
        pipeline.atomic(worker.source_start, {"version": 1, "backupUuid": "backup-1", "state": "NOT_STARTED"})
        (job / "volume-start").touch()
        return worker

    def no_domain(self, args, *extra):
        if args[3] == "list":
            return ""
        raise RuntimeError("Original VM is undefined")

    def test_all_providers_and_qcow2_rbd_chains_capture_original_identity_before_transfer(self):
        for provider in ("ablestack-netbackup", "ablestack-commvault", "ablestack-veeam"):
            for engine in ("QCOW2", "RBD_DIFF"):
                for incremental in (False, True):
                    with self.subTest(provider=provider, engine=engine, incremental=incremental), tempfile.TemporaryDirectory() as directory:
                        worker = self.worker(directory, provider, engine, incremental)
                        transferred = []

                        def transfer(index, artifact, is_metadata=False):
                            domain = ET.fromstring((worker.root / "domain-config.xml").read_text())
                            self.assertEqual(worker.vm, domain.findtext("name"))
                            self.assertEqual("original-vm-uuid", domain.findtext("uuid"))
                            self.assertIsNone(domain.find("memory"))
                            self.assertIsNone(domain.find("vcpu"))
                            descriptor = domain.find("metadata/ablestack-source-configuration")
                            self.assertEqual("false", descriptor.get("bootable"))
                            self.assertEqual("original-offering", json.loads(descriptor.text)["serviceofferingid"])
                            if is_metadata:
                                if provider == "ablestack-netbackup":
                                    texts = metadata.parse_netbackup_entry((worker.root / metadata.NETBACKUP_ENTRY).read_text())
                                    self.assertEqual((worker.root / "domain-config.xml").read_text(), texts["domain-config.xml"])
                                    self.assertEqual("original-vm-uuid", json.loads(texts["backup-manifest.json"])["sourceConfiguration"]["vmUuid"])
                                else:
                                    self.assertFalse((worker.root / metadata.NETBACKUP_ENTRY).exists())
                            else:
                                artifact.update(completed=True, externalId="image-" + str(index), jobId="job-" + str(index))
                                expected = ".rbdiff" if engine == "RBD_DIFF" and incremental else ".raw" if engine == "RBD_DIFF" else ".qcow2"
                                self.assertTrue(artifact["path"].endswith(expected))
                            transferred.append(is_metadata)

                        worker.transfer = transfer
                        with patch.object(pipeline, "run", side_effect=self.no_domain), \
                                patch.object(pipeline.stopped_source, "prepare", return_value=contextlib.nullcontext()):
                            worker.execute()
                        self.assertEqual([False, False, True], transferred)
                        self.assertNotIn("DUMMY", (worker.root / "domain-config.xml").read_text())

    def test_missing_mold_identity_fails_before_snapshot_dummy_or_data_export(self):
        with tempfile.TemporaryDirectory() as directory:
            worker = self.worker(directory, "ablestack-netbackup", "QCOW2")
            worker.manifest.pop("sourceConfiguration")
            worker.transfer = Mock()
            with patch.object(pipeline, "run", side_effect=self.no_domain):
                with self.assertRaisesRegex(RuntimeError, "original Mold VM identity"):
                    worker.execute()
            worker.begin_qcow2.assert_not_called()
            worker.export_qcow2.assert_not_called()
            worker.transfer.assert_not_called()

    def test_foreign_or_dummy_xml_is_rejected_before_backup_io(self):
        for name, uuid in (("DUMMY-VOLUME-backup-1", "original-vm-uuid"), ("i-2-336-VM", "dummy-vm-uuid")):
            with self.subTest(name=name, uuid=uuid), tempfile.TemporaryDirectory() as directory:
                worker = self.worker(directory, "ablestack-netbackup", "RBD_DIFF")
                xml = "<domain><name>%s</name><uuid>%s</uuid></domain>" % (name, uuid)
                with patch.object(pipeline, "run", return_value=xml):
                    with self.assertRaisesRegex(RuntimeError, "original VM"):
                        worker.execute()
                worker.begin_rbd.assert_not_called()
                worker.export_rbd.assert_not_called()

    def test_an_existing_unreadable_definition_is_not_replaced_with_inventory_xml(self):
        with tempfile.TemporaryDirectory() as directory:
            worker = self.worker(directory, "ablestack-netbackup", "RBD_DIFF")
            def run(args):
                if args[3] == "list":
                    return worker.vm + "\n"
                raise RuntimeError("Cannot query libvirt")
            with patch.object(pipeline, "run", side_effect=run):
                with self.assertRaisesRegex(RuntimeError, "could not be read"):
                    worker.capture_domain_configuration()

    def test_parent_bitmap_bindings_follow_current_dummy_or_real_disk_labels(self):
        for names in (("vda", "vdb"), ("sda", "sdd")):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                paths = [str(root / "root.qcow2"), str(root / "data.qcow2")]
                plan = {"parentCheckpointName": "parent", "diskPaths": paths, "parentCheckpointXmlChain": {"parent":
                        '<domaincheckpoint><name>parent</name><disks><disk name="old-root" checkpoint="bitmap" bitmap="parent-root"/>'
                        '<disk name="old-data" checkpoint="bitmap" bitmap="parent-data"/></disks></domaincheckpoint>'}}
                labels = dict(zip((str(Path(path).resolve()) for path in paths), names))
                with patch.object(pipeline, "run", return_value=""):
                    pipeline.redefine_parent_checkpoint(plan, "backup-or-real-vm", root, labels)
                disks = ET.fromstring((root / "parent-checkpoint.xml").read_text()).findall("./disks/disk")
                self.assertEqual(list(names), [disk.get("name") for disk in disks])
                self.assertEqual(["parent-root", "parent-data"], [disk.get("bitmap") for disk in disks])

    def test_parent_disk_count_mismatch_never_redefines_a_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = [str(root / "root"), str(root / "data")]
            plan = {"parentCheckpointName": "parent", "diskPaths": paths, "parentCheckpointXmlChain": {"parent":
                    '<domaincheckpoint><name>parent</name><disks><disk name="old" checkpoint="bitmap"/></disks></domaincheckpoint>'}}
            with patch.object(pipeline, "run", return_value="") as run:
                with self.assertRaisesRegex(RuntimeError, "exact current source disks"):
                    pipeline.redefine_parent_checkpoint(plan, "dummy", root,
                            dict(zip((str(Path(path).resolve()) for path in paths), ("vda", "vdb"))))
            self.assertEqual(1, run.call_count)

    def test_parent_retirement_preserves_current_bitmaps_after_device_labels_change(self):
        for offline in (True, False):
            with self.subTest(offline=offline), tempfile.TemporaryDirectory() as directory:
                job = Path(directory) / "backup-1"
                job.mkdir()
                paths = [str(job / "root.qcow2"), str(job / "data.qcow2")]
                labels = ("sda", "sdd")
                parent_xml = ('<domaincheckpoint><name>parent</name><disks>'
                              '<disk name="vda" checkpoint="bitmap"/>'
                              '<disk name="vdb" checkpoint="bitmap"/></disks></domaincheckpoint>')
                plan = {"parentCheckpointName": "parent", "checkpointName": "current", "vmName": "original",
                        "diskPaths": paths, "parentCheckpointXmlChain": {"parent": parent_xml},
                        "manifest": {"backupUuid": job.name, "volumes": [{"chain": [
                            {"parentCheckpointName": "parent", "checkpointName": "current"}]} for _ in paths]}}
                (job / "checkpoint.xml").write_text('<domaincheckpoint><name>current</name><disks>' + ''.join(
                    '<disk name="%s" checkpoint="bitmap"/>' % label for label in labels) + '</disks></domaincheckpoint>')
                (job / "pull.xml").write_text('<domainbackup><disks>' + ''.join(
                    '<disk name="%s"/>' % label for label in labels) + '</disks></domainbackup>')
                bitmaps = {path: [{"name": name, "flags": ["auto"], "recording": True, "persistent": True}
                                 for name in ("parent", "current")] for path in paths}
                rebound = ET.fromstring(parent_xml)
                for disk, label in zip(rebound.findall("./disks/disk"), labels):
                    disk.set("name", label)

                def run(args, *unused):
                    if args[0] == "qemu-img":
                        self.assertNotIn("--force-share", args)
                        if args[1] == "info":
                            return json.dumps([{"format": "qcow2", "format-specific": {
                                "data": {"bitmaps": bitmaps[args[-1]]}}}])
                        self.assertEqual(["bitmap", "--remove"], args[1:3])
                        bitmaps[args[3]] = [bitmap for bitmap in bitmaps[args[3]] if bitmap["name"] != args[4]]
                        return ""
                    if "list" in args:
                        return "" if offline else "original\n"
                    if "checkpoint-list" in args:
                        return "parent\n"
                    if "checkpoint-dumpxml" in args:
                        return ET.tostring(rebound, encoding="unicode")
                    if "domjobinfo" in args:
                        return "Job type: None\n"
                    request = json.loads(args[-1])
                    if request["execute"] == "query-block":
                        return json.dumps({"return": [{"inserted": {"node-name": str(index),
                            "image": {"filename": path}, "dirty-bitmaps": bitmaps[path]}}
                            for index, path in enumerate(paths)]})
                    self.assertEqual("block-dirty-bitmap-remove", request["execute"])
                    arguments = request["arguments"]
                    path = paths[int(arguments["node"])]
                    bitmaps[path] = [bitmap for bitmap in bitmaps[path] if bitmap["name"] != arguments["name"]]
                    return '{"return":{}}'

                with patch.object(pipeline, "guest_is_off_or_absent", return_value=offline), \
                        patch.object(pipeline, "run", side_effect=run):
                    pipeline.retire_parent_qcow2(plan, job)
                self.assertEqual([["current"], ["current"]], [[item["name"] for item in bitmaps[path]] for path in paths])
                self.assertEqual(parent_xml, plan["parentCheckpointXmlChain"]["parent"])


if __name__ == "__main__":
    unittest.main()
