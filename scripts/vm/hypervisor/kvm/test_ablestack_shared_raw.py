#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import ablestack_shared_raw as raw
import thirdparty_volume_backup as pipeline


class SharedRawTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.source = self.root / "disk.raw"
        self.source.write_bytes(b"source")
        self.plan = {"version": 1, "jobId": "backup", "operation": "BACKUP", "vmName": "vm", "vmUuid": "vm-uuid", "disks": [
            {"path": "disk.raw", "format": "raw", "bytes": 4096}]}
        self.lock = self.root / "guard.lock"; self.marker = self.root / "guard.json"
        self.files = patch.object(raw, "guard_files", return_value=(self.lock, self.marker))
        self.files.start(); self.addCleanup(self.files.stop)

    def prepare(self):
        with patch.object(raw, "runtime_free"), patch.object(raw, "command", return_value='{"format":"raw","virtual-size":4096}'):
            raw.control("PREPARE", self.plan, [str(self.source)])

    def test_other_operation_cannot_replace_durable_ownership(self):
        self.prepare(); self.assertEqual(self.plan, json.loads(self.marker.read_text()))
        with patch.object(raw, "runtime_free"), patch.object(raw, "command", return_value='{"format":"raw","virtual-size":4096}'):
            with self.assertRaisesRegex(RuntimeError, "SHARED_RAW_PROTECTED"):
                raw.control("PREPARE", dict(self.plan, jobId="another"), [str(self.source)])
        self.assertEqual(self.plan, json.loads(self.marker.read_text()))

    def test_release_is_blocked_by_real_posix_reader_lock(self):
        self.prepare()
        code = "import fcntl,sys; f=open(sys.argv[1],'r+'); fcntl.lockf(f,fcntl.LOCK_EX); print('locked',flush=True); sys.stdin.read()"
        child = subprocess.Popen([sys.executable, "-c", code, str(self.lock)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual("locked", child.stdout.readline().strip())
            with self.assertRaisesRegex(RuntimeError, "SHARED_RAW_BUSY"):
                raw.control("RELEASE", self.plan, [str(self.source)])
            self.assertTrue(self.marker.exists())
        finally:
            child.communicate(input="", timeout=5)
        with patch.object(raw, "no_readers"):
            raw.control("RELEASE", self.plan, [str(self.source)])
        self.assertFalse(self.marker.exists())

    def test_late_worker_fails_after_release(self):
        self.prepare()
        with patch.object(raw, "no_readers"):
            raw.control("RELEASE", self.plan, [str(self.source)])
        with self.assertRaisesRegex(RuntimeError, "ownership is missing"):
            with raw.offline_sources([str(self.source)], "backup"):
                self.fail("retired job cannot read source")

    def test_orphan_reader_keeps_source_protected(self):
        self.prepare()
        with patch.object(raw, "no_readers", side_effect=RuntimeError("reader active")):
            with self.assertRaisesRegex(RuntimeError, "reader active"):
                raw.control("RELEASE", self.plan, [str(self.source)])
        self.assertTrue(self.marker.exists())

    def test_source_modification_is_detected_before_metadata_success(self):
        self.prepare()
        with patch.object(raw, "runtime_free"):
            with self.assertRaisesRegex(RuntimeError, "SOURCE_CHANGED"):
                with raw.offline_sources([str(self.source)], "backup") as validate:
                    self.source.write_bytes(b"different"); validate()

    def test_unknown_runtime_or_running_shared_writer_blocks_prepare(self):
        for error in ("runtime unknown", "shared writer active"):
            with patch.object(raw, "runtime_free", side_effect=RuntimeError(error)):
                with self.assertRaisesRegex(RuntimeError, error):
                    raw.control("PREPARE", self.plan, [str(self.source)])
            self.assertFalse(self.marker.exists())

    def test_real_format_is_checked_instead_of_file_extension(self):
        with patch.object(raw, "runtime_free"), patch.object(raw, "command", return_value='{"format":"qcow2","virtual-size":4096}'):
            with self.assertRaisesRegex(RuntimeError, "format or virtual size"):
                raw.control("PREPARE", self.plan, [str(self.source)])
        self.assertFalse(self.marker.exists())

    def test_active_domain_with_path_alias_blocks_copy(self):
        alias = self.root / "alias.raw"; os.link(self.source, alias)
        xml = '<domain><devices><disk><source file="%s"/></disk></devices></domain>' % alias
        with patch.object(raw, "command", side_effect=["running-vm\n", xml]):
            with self.assertRaisesRegex(RuntimeError, "SHARED_RAW_IN_USE"):
                raw.runtime_free([str(self.source)])

    def test_cluster_local_locking_mount_is_rejected(self):
        self.files.stop()
        for options in ("rw,localflocks", "rw,localflocks=1"):
            with patch.object(raw, "command", return_value="gfs2 " + options):
                with self.assertRaisesRegex(RuntimeError, "cluster-wide locking"):
                    raw.guard_files(str(self.source))

    def test_conversion_creates_independent_raw_and_compares_original_raw(self):
        target = self.root / "copy.raw"; process = Mock(returncode=0); process.poll.return_value = 0
        def launch(args, **kwargs):
            target.write_bytes(b"qcow2")
            self.assertEqual(["qemu-img", "convert", "-p", "-f", "raw", "-O", "raw", "-S", "4k"], args[:9])
            self.assertNotIn("-B", args); return process
        with patch.object(raw.subprocess, "Popen", side_effect=launch), patch.object(raw, "command", side_effect=['{"format":"raw","virtual-size":4096}', ""]) as commands:
            raw.convert(str(self.source), target, "raw", 4096, 30)
        self.assertEqual(["qemu-img", "compare", "-f", "raw", "-F", "raw", str(self.source), str(target)], commands.call_args_list[1].args[0])

    def test_backing_file_in_full_result_is_rejected(self):
        process = Mock(returncode=0); process.poll.return_value = 0
        with patch.object(raw.subprocess, "Popen", return_value=process), patch.object(raw, "command", return_value='{"format":"qcow2","virtual-size":4096,"backing-filename":"parent"}'):
            with self.assertRaisesRegex(RuntimeError, "independent image"):
                raw.convert(str(self.source), self.root / "copy.raw", "raw", 4096, 30)

    def test_cancel_terminates_conversion_before_returning(self):
        process = Mock(); process.poll.return_value = None
        with patch.object(raw.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(RuntimeError, "canceled"):
                raw.convert(str(self.source), self.root / "copy.raw", "raw", 4096, 30,
                            cancel=Mock(side_effect=RuntimeError("canceled")))
        process.terminate.assert_called_once(); process.wait.assert_called_once()

    def test_nas_exports_under_one_source_lease(self):
        destination = self.root / "nas"; destination.mkdir(); self.prepare()
        payload = {"plan": self.plan, "paths": [str(self.source)], "files": ["root.disk.raw"], "destination": str(destination), "timeout": 30}
        def convert(source, target, *args, **kwargs):
            self.assertTrue(self.marker.exists()); target.write_bytes(b"image")
        with patch.object(raw, "runtime_free"), patch.object(raw, "command", return_value=""), patch.object(raw, "convert", side_effect=convert), patch("builtins.print") as output:
            raw.export_nas(payload)
        output.assert_called_once_with(5, flush=True)

    def test_stopped_vm_without_libvirt_definition_uses_mold_identity_descriptor(self):
        with patch.object(raw, "command", return_value=""):
            raw.capture_configuration(self.plan, [str(self.source)], self.root)
        domain = raw.ET.fromstring((self.root / "domain-config.xml").read_text())
        self.assertEqual("vm-uuid", domain.findtext("uuid"))
        self.assertEqual("false", domain.find("metadata/ablestack-offline-full").get("bootable"))

    def test_existing_libvirt_definition_must_match_mold_identity(self):
        with patch.object(raw, "command", side_effect=["vm\n", "<domain><name>vm</name><uuid>another</uuid></domain>"]):
            with self.assertRaisesRegex(RuntimeError, "another VM"):
                raw.capture_configuration(self.plan, [str(self.source)], self.root)

    def test_nas_refuses_path_escape_before_copy(self):
        payload = {"plan": self.plan, "paths": [str(self.source)], "files": ["../escape.qcow2"], "destination": str(self.root), "timeout": 30}
        with patch.object(raw, "convert") as convert:
            with self.assertRaisesRegex(RuntimeError, "Invalid offline NAS"):
                raw.export_nas(payload)
        convert.assert_not_called()

    def test_pipeline_sends_volume_then_final_metadata_without_checkpoints(self):
        self.prepare(); stage = self.root / "stage"; stage.mkdir()
        (stage / "domain-config.xml").write_text("<domain/>")
        worker = pipeline.Backup.__new__(pipeline.Backup)
        worker.root = stage; worker.count = 1; worker.timeout = 30; worker.offline = self.plan
        worker.plan = {"diskPaths": [str(self.source)], "sourceHost": "host"}
        artifact = {"path": str(stage / "root.raw"), "backupUuid": "backup"}
        worker.manifest = {"backupUuid": "backup", "backupType": "FULL", "volumes": [{"chain": [artifact], "provisionedBytes": 4096}]}
        worker.bandwidth_file = self.root / "bandwidth"; worker.bandwidth_file.write_text("0")
        worker.check_cancel = Mock(); worker.progress = Mock(); order = []
        def transfer(index, current, metadata=False):
            if metadata:
                self.assertTrue(worker.manifest["complete"])
                self.assertTrue((stage / "backup-manifest.json").is_file())
                self.assertTrue((stage / ".staging.complete").is_file()); order.append("metadata")
            else:
                self.assertTrue(Path(current["path"]).is_file()); order.append("volume")
        worker.transfer = transfer
        with patch.object(raw, "runtime_free"), patch.object(raw, "command", return_value=""), patch.object(raw, "convert", side_effect=lambda source, target, *args: Path(target).write_bytes(b"copy")):
            worker.execute_offline()
        self.assertEqual(["volume", "metadata"], order); self.assertFalse((stage / "checkpoints").exists())


if __name__ == "__main__":
    unittest.main()
