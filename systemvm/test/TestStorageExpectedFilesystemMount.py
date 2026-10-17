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

import ast
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "systemvm/debian/usr/local/bin/ablestack-storagectl"
sys.path.insert(0, str(ROOT / "systemvm/debian/usr/local/lib/ablestack-storage"))
from volume_identity import VolumeIdentityError, resolve_volume_device


class StorageExpectedFilesystemMountTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.block = next(block for block in re.findall(r"<<'PY'\n(.*?)\nPY", CLI.read_text(), re.S)
                         if "def format_empty_device():" in block)
        cls.tree = ast.parse(cls.block)
        cls.definition = next(node for node in cls.tree.body if isinstance(node, ast.FunctionDef)
                              and node.name == "require_expected_mount_filesystem")

    def setUp(self):
        self.volume = "22222222-2222-4222-8222-222222222222"
        self.fs = "33333333-3333-4333-8333-333333333333"
        self.request = {"volumeUuid": self.volume, "expectedFilesystemUuid": self.fs,
                        "filesystem": "EXT4", "volumeSizeBytes": 20 << 30, "importMode": "MOUNT_EXISTING"}
        self.devices = [{"path": "/dev/sdb", "name": "sdb", "type": "disk", "size": 20 << 30,
                         "serial": self.volume.replace("-", "")[:20], "fstype": "ext4", "uuid": self.fs}]
        self.rows = Mock(side_effect=lambda: copy.deepcopy(self.devices))
        self.probe = Mock(side_effect=lambda device, field: self.fs if field == "UUID" else "ext4")
        self.env = {"uuid": uuid, "walk": lambda rows: rows, "lsblk": self.rows, "blkid_value": self.probe,
                    "resolve_volume_device": resolve_volume_device}
        identity = next(node for node in self.tree.body if isinstance(node, ast.FunctionDef)
                        and node.name == "assert_mutating_volume_identity")
        self.env.update(compact=lambda value: "".join(char for char in str(value or "").lower() if char.isalnum()),
                        parse_size=lambda value: int(value or 0))
        exec(compile(ast.Module(body=[identity, self.definition], type_ignores=[]), str(CLI), "exec"), self.env)

    def call(self, request=None):
        return self.env["require_expected_mount_filesystem"](self.request if request is None else request, "/dev/sdb")

    def test_exact_current_uuid_type_serial_and_size_accept_without_mutating_request(self):
        before = copy.deepcopy(self.request)
        self.call()
        self.assertEqual(before, self.request)
        self.assertEqual(1, self.rows.call_count)
        self.assertEqual([("/dev/sdb", "UUID"), ("/dev/sdb", "TYPE")],
                         [call.args for call in self.probe.call_args_list])

    def test_omitted_expected_uuid_keeps_legacy_path_and_does_not_probe(self):
        self.call({"volumeUuid": "historical", "filesystem": "legacy", "importMode": "MOUNT_EXISTING"})
        self.rows.assert_not_called()
        self.probe.assert_not_called()

    def test_present_null_blank_noncanonical_and_nonstring_ids_are_rejected(self):
        for key in ("expectedFilesystemUuid", "volumeUuid"):
            for value in (None, "", False, {}, 5, self.fs.upper(), self.fs.replace("-", "")):
                # UUIDs containing only numeric hex characters have no uppercase distinction.
                if value == self.fs.upper():
                    value = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
                with self.subTest(key=key, value=value), self.assertRaises((ValueError, TypeError, AttributeError)):
                    self.call({**self.request, key: value})
        self.probe.assert_not_called()

    def test_expected_uuid_never_authorizes_blank_type_unknown_size_or_any_format(self):
        for change in ({"filesystem": None}, {"filesystem": ""}, {"filesystem": "btrfs"},
                       {"filesystem": {}}, {"volumeSizeBytes": True}, {"volumeSizeBytes": "21474836480"},
                       {"volumeSizeBytes": 0}, {"importMode": "FORMAT_EMPTY"},
                       {"importMode": "FORMAT_IF_EMPTY"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.call({**self.request, **change})
        self.probe.assert_not_called()

    def test_fresh_device_rebinding_and_geometry_change_are_rejected_before_blkid(self):
        for change in ({"serial": "foreign-volume"}, {"path": "/dev/sdc"}, {"size": 19 << 30}):
            self.devices = [{**self.devices[0], **change}]
            with self.subTest(change=change), self.assertRaises((ValueError, VolumeIdentityError)):
                self.call()
            self.devices[0].update(path="/dev/sdb", serial=self.volume.replace("-", "")[:20], size=20 << 30)
        self.probe.assert_not_called()

    def test_matching_filesystem_without_requested_volume_serial_cannot_authorize_mount(self):
        self.devices[0]["serial"] = ""
        with self.assertRaises((ValueError, VolumeIdentityError)):
            self.call()
        self.probe.assert_not_called()

    def test_current_blkid_uuid_or_type_drift_is_rejected_despite_matching_lsblk(self):
        for actual_uuid, actual_type in ((str(uuid.uuid4()), "ext4"), (self.fs, "xfs"), ("", "ext4"), (self.fs, "")):
            self.probe.side_effect = lambda device, field: actual_uuid if field == "UUID" else actual_type
            with self.subTest(uuid=actual_uuid, fs=actual_type), self.assertRaises(ValueError):
                self.call()

    def test_xfs_and_resume_use_same_expected_uuid_guard_without_new_format_authority(self):
        self.request.update(filesystem="XFS", resumeOnly=True)
        self.devices[0].update(fstype="xfs")
        self.probe.side_effect = lambda device, field: self.fs if field == "UUID" else "xfs"
        self.call()

    def test_actual_mutating_mount_branch_calls_guard_before_formatter_or_any_mount_effect(self):
        branch = next(node for node in self.tree.body if isinstance(node, ast.If)
                      and node.body and isinstance(node.body[0], ast.Expr)
                      and isinstance(node.body[0].value, ast.Call)
                      and isinstance(node.body[0].value.func, ast.Name)
                      and node.body[0].value.func.id == "require_expected_mount_filesystem")
        self.assertEqual("import_mode != 'INSPECT_ONLY'", ast.unparse(branch.test))
        effects = [node for node in ast.walk(branch) if isinstance(node, ast.Call)
                   and ((isinstance(node.func, ast.Name) and node.func.id in ("format_empty_device", "phase", "persist_backing_mount"))
                        or isinstance(node.func, ast.Attribute) and node.func.attr == "makedirs")]
        self.assertTrue(effects)
        self.assertTrue(all(node.lineno > branch.body[0].lineno for node in effects))
        self.env["payload"] = {**self.request, "expectedFilesystemUuid": ""}
        self.env["device"] = "/dev/sdb"
        effect = Mock(side_effect=AssertionError("No mount/format/fstab effect may follow rejection"))
        self.env["effect"] = effect
        wire = ast.Module(body=[branch.body[0], ast.Expr(value=ast.Call(func=ast.Name(id="effect", ctx=ast.Load()), args=[], keywords=[]))],
                          type_ignores=[])
        ast.fix_missing_locations(wire)
        with self.assertRaises(ValueError):
            exec(compile(wire, str(CLI), "exec"), self.env)
        effect.assert_not_called()

    def test_real_readonly_capability_is_literal_and_supported_token_is_normal_producer(self):
        with tempfile.TemporaryDirectory() as folder:
            lock = Path(folder) / "absent" / "writer.lock"
            result = subprocess.run([str(CLI), "volume", "operation", "capabilities"],
                                    env=dict(os.environ, ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(lock)),
                                    text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stderr)
            capability = json.loads(result.stdout)
            self.assertIs(capability["expectedFilesystemUuidGuardSupported"], True)
            self.assertFalse(lock.exists())
        producer = (ROOT / "tools/build/build-storage-runtime-bundle.sh").read_text()
        self.assertEqual(1, producer.count('"STORAGE_VOLUME_EXPECTED_FILESYSTEM_UUID_GUARD"'))


if __name__ == "__main__":
    unittest.main()
