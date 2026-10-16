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

"""Protected on-disk generation and FD9; block-device metadata is a disposable fixture."""
import ast
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

LIB = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/lib/ablestack-storage"
CLI = LIB.parents[1] / "bin/ablestack-storagectl"
sys.path.insert(0, str(LIB))
import config_generation as generation
import nvme_boot_configuration as module


class StorageNvmeBootConfigurationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = self.root / "config"; self.config.mkdir(mode=0o700)
        (self.config / "desired-state").mkdir(mode=0o700)
        self.generations = self.root / "generations"; self.generations.mkdir(mode=0o700)
        self.reservation = self.root / "lease.json"
        self.base = self.root / "nvmet"; self.base.mkdir(mode=0o700)
        self.scope = {"instanceUuid": str(uuid.uuid4()), "operationUuid": str(uuid.uuid4()), "revision": 14}
        self.boot, self.parent, self.ns, self.volume = [str(uuid.uuid4()) for _ in range(4)]
        self.nqn = "nqn.2026-10.local.storage:boot-preservation"
        self.size = 1073741824
        self.source = {"enabled": True, "instanceUuid": self.scope["instanceUuid"],
            "subsystems": [{"uuid": self.parent, "targetName": self.nqn, "state": "Ready",
                "namespaces": [{"uuid": self.ns, "volumeUuid": self.volume, "volumeSizeBytes": self.size,
                    "lunOrNamespace": "1", "state": "Ready", "runtime": {"backingPath": "/dev/sdy", "backingType": "block"}}]}]}
        self.source["lastApply"] = {"success": True, "applied": 1, "skipped": 0}
        self.source["privateCapsule"] = {"reference": "PRESERVE PRIVATE REFERENCE"}
        self.state = self.config / "nvmeof-subsystems.json"
        self.write(self.state, self.source)
        self.write(self.config / "desired-state/nfs-export-apply.json", {"exports": [{"name": "KEEP", "readOnly": True, "count": 1}]})
        self.desired = generation.Generation(self.generations, self.config).files()
        self.digest = module.NvmeBootRecovery.digest(self.desired)
        current = {**self.scope, "configurationSha256": self.digest, "verifiedAt": 1}
        self.current = self.generations / "current.json"; self.write(self.current, current)
        self.artifact = self.generations / (self.scope["operationUuid"] + ".json")
        self.write(self.artifact, {**current, "phase": "VERIFIED", "desired": self.desired})
        changed = copy.deepcopy(self.source)
        changed["subsystems"][0]["namespaces"][0]["runtime"]["backingPath"] = "/dev/sdz"
        self.write(self.state, changed)
        self.changed = changed
        self.devices = [{"name": "vda", "path": "/dev/vda", "type": "disk", "size": 5000, "serial": "ROOT",
                         "fstype": "ext4", "mountpoint": "/"},
                        {"name": "sdz", "path": "/dev/sdz", "type": "disk", "size": self.size,
                         "serial": self.volume.replace("-", "")[:20], "fstype": None, "mountpoint": None}]
        namespace_path = self.base / "subsystems" / self.nqn / "namespaces/1"
        namespace_path.mkdir(mode=0o700, parents=True)
        self.enable = namespace_path / "enable"; self.enable.write_text("1")
        self.device = namespace_path / "device_path"; self.device.write_text("/dev/sdz")
        self.canary = self.root / "DATA"; self.canary.write_bytes(b"NEVER WRITE DATA")
        self.cipher = self.root / "cipher"; self.cipher.write_bytes(b"NEVER REENCRYPT")
        self.lock = self.root / "writer.lock"
        descriptor = os.open(self.lock, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
        try: saved = os.dup(9)
        except OSError: saved = None
        os.dup2(descriptor, 9)
        if descriptor != 9: os.close(descriptor)
        fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)
        def close():
            os.close(9)
            if saved is not None: os.dup2(saved, 9); os.close(saved)
        self.addCleanup(close)
        env = patch.dict(os.environ, {"ABLESTACK_STORAGE_WRITER_LOCK_FD": "9",
                                     "ABLESTACK_STORAGE_WRITER_LOCK_FILE": str(self.lock)})
        env.start(); self.addCleanup(env.stop)
        original = Path.lstat
        block = os.stat_result((stat.S_IFBLK | 0o600, 913, 1, 1, os.geteuid(), os.getegid(), 0, 0, 0, 0))
        patcher = patch.object(Path, "lstat", lambda path: block if str(path) == "/dev/sdz" else original(path))
        patcher.start(); self.addCleanup(patcher.stop)
        globber = patch("volume_identity.glob.glob", return_value=[])
        globber.start(); self.addCleanup(globber.stop)
        self.recovery = module.NvmeBootRecovery(self.config, self.generations, self.reservation,
            self.base, boot=lambda: self.boot, devices=lambda: copy.deepcopy(self.devices))
        self.request = {**self.scope, "expectedBootId": self.boot, "namespaceUuid": self.ns,
            "expectedCurrentSha256": self.sha(self.current), "expectedArtifactSha256": self.sha(self.artifact),
            "expectedConfigurationSha256": self.digest,
            "expectedObservedConfigurationSha256": generation.Generation(self.generations, self.config).digest()}

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value, sort_keys=True, indent=2)); path.chmod(0o600)

    @staticmethod
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def preserved(self):
        return {str(path): path.read_bytes() for path in (self.current, self.artifact, self.canary, self.cipher)}

    def rejected(self, request=None):
        before = self.preserved(); state = self.state.read_bytes()
        with self.assertRaises((ValueError, OSError, KeyError, TypeError, RuntimeError)):
            self.recovery.recover(request or self.request)
        self.assertEqual(before, self.preserved()); self.assertEqual(state, self.state.read_bytes())

    def test_real_protected_recovery_restores_only_one_field_and_is_idempotent(self):
        preserved = self.preserved()
        result = self.recovery.recover(self.request)
        self.assertTrue(result["canonicalChanged"])
        self.assertEqual(self.source, json.loads(self.state.read_bytes()))
        self.assertEqual(json.dumps(self.source, sort_keys=True, indent=2).encode(), self.state.read_bytes())
        self.assertEqual(self.digest, generation.Generation(self.generations, self.config).digest())
        self.assertEqual(preserved, self.preserved())
        signature = module.nvme_boot_read(self.state)
        self.assertFalse(self.recovery.recover(self.request)["canonicalChanged"])
        self.assertEqual(signature, module.nvme_boot_read(self.state))

    def test_requests_are_closed_exact_scope_and_hash_bound(self):
        for key, value in (("instanceUuid", str(uuid.uuid4())), ("operationUuid", str(uuid.uuid4())),
                           ("revision", True), ("revision", 15), ("expectedBootId", str(uuid.uuid4())),
                           ("expectedCurrentSha256", "0" * 64), ("expectedArtifactSha256", "0" * 64),
                           ("expectedConfigurationSha256", "0" * 64), ("expectedObservedConfigurationSha256", "0" * 64),
                           ("namespaceUuid", str(uuid.uuid4())), ("configurationRoot", "/tmp")):
            with self.subTest(key=key, value=value):
                self.rejected({**self.request, key: value})

    def test_pending_or_any_active_or_expired_lease_blocks_repair(self):
        for path in (self.generations / "pending.json", self.reservation):
            for value in ({}, {"expired": True}):
                self.write(path, value); self.rejected(); path.unlink()

    def test_nonverified_artifact_and_changed_current_are_rejected(self):
        value = json.loads(self.artifact.read_bytes()); value["phase"] = "COMMITTED"
        self.write(self.artifact, value)
        self.request["expectedArtifactSha256"] = self.sha(self.artifact)
        self.rejected()

    def test_other_declaration_difference_and_extra_namespace_field_are_not_adopted(self):
        nfs = self.config / "desired-state/nfs-export-apply.json"; original = nfs.read_bytes()
        self.write(nfs, {"exports": [{"name": "FOREIGN"}]}); self.rejected(); nfs.write_bytes(original)
        value = copy.deepcopy(self.changed); value["subsystems"][0]["namespaces"][0]["volumeSizeBytes"] += 1
        self.write(self.state, value); self.request["expectedObservedConfigurationSha256"] = generation.Generation(self.generations, self.config).digest()
        self.rejected()

    def test_other_declaration_boolean_integer_and_integer_float_drift_is_never_adopted(self):
        nfs = self.config / "desired-state/nfs-export-apply.json"
        original = copy.deepcopy(self.desired["desired-state/nfs-export-apply.json"])
        for key, replacement in (("readOnly", 1), ("count", 1.0)):
            with self.subTest(key=key):
                self.write(self.state, self.changed)
                value = copy.deepcopy(original)
                value["exports"][0][key] = replacement
                self.write(nfs, value)
                # The caller can attest the real changed digest, but cannot
                # authorize a second semantic/type difference from VERIFIED.
                self.request["expectedObservedConfigurationSha256"] = generation.Generation(self.generations, self.config).digest()
                self.rejected()

    def test_root_or_ambiguous_serial_size_formatted_and_mounted_disks_fail_closed(self):
        original = copy.deepcopy(self.devices)
        for field, value in (("serial", "foreign"), ("size", self.size + 1), ("fstype", "xfs"),
                             ("mountpoint", "/"), ("type", "part")):
            self.devices = copy.deepcopy(original); self.devices[1][field] = value
            with self.subTest(field=field): self.rejected()
        self.devices = copy.deepcopy(original)
        duplicate = copy.deepcopy(original[1]); duplicate.update(name="sdy", path="/dev/sdy")
        self.devices.append(duplicate); self.rejected()

    def test_disabled_wrong_device_or_unsafe_configfs_namespace_blocks_repair(self):
        self.enable.write_text("0"); self.rejected(); self.enable.write_text("1")
        self.device.write_text("/dev/sdy"); self.rejected(); self.device.write_text("/dev/sdz")
        self.device.chmod(0o666); self.rejected()

    def test_lost_fd9_and_writable_canonical_input_are_rejected(self):
        with patch.dict(os.environ, {"ABLESTACK_STORAGE_WRITER_LOCK_FD": ""}): self.rejected()
        self.state.chmod(0o666); self.rejected()

    def test_live_and_generation_changes_before_publication_do_not_write(self):
        original = self.recovery.live
        calls = []
        def changed(*args):
            calls.append(1)
            result = original(*args)
            if len(calls) == 2:
                self.boot = str(uuid.uuid4())
            return result
        with patch.object(self.recovery, "live", changed): self.rejected()

    def test_boot_saved_source_does_not_persist_fresh_device_observation(self):
        saved = module.NvmeSavedBootSource(str(self.state), copy.deepcopy(self.changed), self.state)
        self.assertTrue(saved.preserve())
        value = copy.deepcopy(self.changed); value["subsystems"][0]["namespaces"][0]["runtime"]["backingPath"] = "/dev/sdx"
        body = CLI.read_text().split("apply_nvmeof_subsystems() {", 1)[1].split("\nPY\n}", 1)[0].split("<<'PY'\n", 1)[1]
        node = next(node for node in ast.parse(body).body if isinstance(node, ast.If)
                    and any(isinstance(item, ast.With) and "state_fp" in ast.unparse(item) for item in node.body))
        before = self.state.read_bytes()
        environment = {"os": os, "json": json, "state_dir": str(self.config),
                       "state_payload": value, "saved_boot_source": saved}
        with patch.dict(os.environ, {"ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY": ""}):
            exec(compile(ast.Module(body=[node], type_ignores=[]), "<signed-persist>", "exec"), environment)
        self.assertEqual(before, self.state.read_bytes())

    def test_normal_sealed_memfd_payload_still_persists(self):
        fd = os.memfd_create("normal-api")
        self.addCleanup(lambda: os.close(fd))
        os.write(fd, json.dumps(self.changed).encode())
        source = module.NvmeSavedBootSource("/proc/self/fd/" + str(fd), self.changed, self.state)
        self.assertFalse(source.preserve())

    def test_optional_unconfigured_protocol_directory_is_absent_without_creating_it(self):
        path = self.config / "desired-state/nfs-export-apply.json"
        path.unlink(); path.parent.rmdir()
        self.assertIsNone(self.recovery.snapshot()["desired-state/nfs-export-apply.json"])
        self.assertFalse(path.parent.exists())
        path.parent.symlink_to(self.root / "missing")
        with self.assertRaises(ValueError): self.recovery.snapshot()

    def test_signed_recovery_program_executes_with_its_actual_closed_dependencies(self):
        body = CLI.read_text().split("<<'PYNVMEBOOTRECOVERY'\n", 1)[1].split("\nPYNVMEBOOTRECOVERY", 1)[0]
        nodes = [node for node in ast.parse(body).body if not isinstance(node, ast.Try)]
        namespace = {}
        exec(compile(ast.Module(body=nodes, type_ignores=[]), "<signed-recovery>", "exec"), namespace)
        recovery = namespace["NvmeBootRecovery"](self.config, self.generations, self.reservation,
            self.base, boot=lambda: self.boot, devices=lambda: copy.deepcopy(self.devices))
        result = recovery.recover(self.request)
        self.assertTrue(result["canonicalChanged"])
        self.assertEqual(self.source, json.loads(self.state.read_bytes()))
        self.assertEqual(self.sha(self.state), result["nvmeConfigurationSha256"])

    def test_normal_memfd_signed_persist_block_keeps_existing_behavior(self):
        fd = os.memfd_create("normal-api"); self.addCleanup(lambda: os.close(fd))
        os.write(fd, json.dumps(self.changed).encode())
        saved = module.NvmeSavedBootSource("/proc/self/fd/" + str(fd), self.changed, self.state)
        body = CLI.read_text().split("apply_nvmeof_subsystems() {", 1)[1].split("\nPY\n}", 1)[0].split("<<'PY'\n", 1)[1]
        node = next(node for node in ast.parse(body).body if isinstance(node, ast.If)
                    and any(isinstance(item, ast.With) and "state_fp" in ast.unparse(item) for item in node.body))
        payload = {**self.changed, "normalNewApiField": True}
        with patch.dict(os.environ, {"ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY": ""}):
            exec(compile(ast.Module(body=[node], type_ignores=[]), "<signed-persist>", "exec"),
                 {"os": os, "json": json, "state_dir": str(self.config),
                  "state_payload": payload, "saved_boot_source": saved})
        self.assertEqual(payload, json.loads(self.state.read_bytes()))

    def test_saved_canonical_alias_and_stale_snapshot_are_rejected(self):
        alias = self.config / "alias"; alias.symlink_to(self.state)
        with self.assertRaises(ValueError): module.NvmeSavedBootSource(alias, self.changed, self.state)
        source = module.NvmeSavedBootSource(self.state, self.changed, self.state)
        self.write(self.state, {**self.changed, "enabled": False})
        with self.assertRaises(ValueError): source.preserve()

    def test_duplicate_keys_nonfinite_and_unsafe_symlink_are_rejected(self):
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}'):
            with self.assertRaises(ValueError): module.nvme_boot_json(raw)
        linked = self.config / "linked"; linked.symlink_to(self.state)
        with self.assertRaises(ValueError): module.nvme_boot_read(linked)

    def test_signed_boot_source_and_recovery_closures_match_canonical_helpers(self):
        source = CLI.read_text()
        expected = (LIB / "nvme_boot_configuration.py").read_text()
        expected = "\n".join(line for line in expected.splitlines() if not line.startswith(
            ("from config_generation import ", "from nvme_cleanup import ", "from volume_identity import "))).rstrip()
        begin, end = "# BEGIN EMBEDDED NVME BOOT CONFIGURATION\n", "\n# END EMBEDDED NVME BOOT CONFIGURATION"
        blocks = source.split(begin)[1:]
        self.assertEqual(2, len(blocks))
        for block in blocks: self.assertEqual(expected, block.split(end, 1)[0])
        recovery = source.split("<<'PYNVMEBOOTRECOVERY'\n", 1)[1].split("\nPYNVMEBOOTRECOVERY", 1)[0]
        tree = ast.parse(recovery)
        def function_ast(text, name):
            return ast.dump(next(node for node in ast.parse(text).body if isinstance(node, ast.FunctionDef) and node.name == name))
        for filename, names in (("nvme_cleanup.py", ("nvme_cleanup_writer",)),
                                ("config_generation.py", ("redact",)),
                                ("volume_identity.py", ("resolve_volume_device", "flatten_devices", "safe_candidate",
                                 "root_disk_names", "by_id_candidates", "unique_candidate", "resolution", "token_matches",
                                 "compact", "size_matches", "device_for_path", "has_mounted_or_formatted_child", "flatten_mounts"))):
            for name in names:
                self.assertEqual(function_ast((LIB / filename).read_text(), name), function_ast(recovery, name))
        generation_class = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "Generation")
        self.assertEqual(generation.Generation.paths, ast.literal_eval(generation_class.body[0].value))
        self.assertIn("recover-nvme-observation", source)
        self.assertNotIn("operation:generation:recover-nvme-observation|", source)
        self.assertIn("saved_boot_source=NvmeSavedBootSource(payload_path,payload)", source)
        self.assertIn("and not saved_boot_source.preserve():", source)


if __name__ == "__main__":
    unittest.main()
