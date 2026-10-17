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

"""Real protected pending/FD9 with disposable configfs metadata; no device I/O."""
import copy
import fcntl
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

LIB = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0, str(LIB))
import config_generation as generation
import nvme_runtime_identity as module
from nvme_cleanup import NvmeManagedCleanup


class StorageNvmeRuntimeIdentityTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = self.root / "configuration"; self.config.mkdir(mode=0o700)
        self.base = self.root / "nvmet"; self.base.mkdir(mode=0o700)
        for name in ("subsystems", "ports", "hosts"):
            (self.base / name).mkdir(mode=0o700)
        self.instance, self.parent_id, self.namespace_id, self.volume = [str(uuid.uuid4()) for _ in range(4)]
        self.nqn = "nqn.2026-10.local.storage:identity-fixture"
        self.boot = str(uuid.uuid4()); self.size = 21474836480
        self.namespace = {"uuid": self.namespace_id, "lunOrNamespace": "1",
                          "volumeUuid": self.volume, "volumeSizeBytes": self.size, "state": "Ready"}
        self.subsystem = {"uuid": self.parent_id, "targetName": self.nqn, "state": "Ready",
                          "config": {"allowAnyHost": False}, "namespaces": [self.namespace], "hosts": []}
        self.source = {"instanceUuid": self.instance, "enabled": True, "subsystems": [self.subsystem]}
        self.empty = {"instanceUuid": self.instance, "enabled": True, "subsystems": []}
        self.state = self.config / "nvmeof-subsystems.json"
        self.gen = generation.Generation(self.root / "generations", self.config)
        desired = {name: None for name in self.gen.paths}; desired["nvmeof-subsystems.json"] = self.source
        self.gen.replace_desired(desired)
        self.original = {"instanceUuid": self.instance, "operationUuid": str(uuid.uuid4()), "revision": 1}
        for action in ("begin", "verify", "commit", "finish"):
            self.gen.execute(action, self.original)
        frozen = self.gen.execute("frozen", self.original)
        self.request = {"instanceUuid": self.instance, "operationUuid": str(uuid.uuid4()), "revision": 2,
                        "previousGeneration": frozen["generation"], "configurationDesiredState": frozen["configurationDesiredState"]}
        self.gen.execute("begin", self.request)
        self.original_serial = "SOURCE0123456789"
        self.original_model = "Owned"
        self.original_uuid = str(uuid.uuid4())
        self.original_nguid = str(uuid.uuid4())
        self.raw = self.root / "DATA-sentinel"; self.raw.write_bytes(b"RAW DATA PRESERVED")
        self.original_data = self.raw.read_bytes()
        self.make_runtime(default=False, enabled=True)
        lock = self.root / "writer.lock"; descriptor = os.open(lock, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            saved = os.dup(9)
        except OSError:
            saved = None
        os.dup2(descriptor, 9)
        if descriptor != 9:
            os.close(descriptor)
        fcntl.flock(9, fcntl.LOCK_EX | fcntl.LOCK_NB)
        def release():
            os.close(9)
            if saved is not None:
                os.dup2(saved, 9); os.close(saved)
        self.addCleanup(release)
        environment = patch.dict(os.environ, {"ABLESTACK_STORAGE_WRITER_LOCK_FD": "9",
            "ABLESTACK_STORAGE_WRITER_LOCK_FILE": str(lock),
            "ABLESTACK_STORAGE_GENERATION_DIR": str(self.gen.root)})
        environment.start(); self.addCleanup(environment.stop)
        self.writes = []
        self.probes = []
        self.devices = {"/dev/fixture-data": (self.volume, self.size)}

    @property
    def subsystem_path(self):
        return self.base / "subsystems" / self.nqn

    def namespace_path(self, number="1"):
        return self.subsystem_path / "namespaces" / str(number)

    def make_runtime(self, default=False, enabled=False, number="1", eui=True):
        path = self.subsystem_path
        path.mkdir(mode=0o700, exist_ok=True)
        for name in ("namespaces", "allowed_hosts"):
            (path / name).mkdir(mode=0o700, exist_ok=True)
        attributes = {"attr_serial": "DEFAULT123456789" if default else self.original_serial,
                      "attr_model": "Linux" if default else self.original_model, "attr_allow_any_host": "0"}
        for name, value in attributes.items():
            (path / name).write_text(value)
        ns = self.namespace_path(number); ns.mkdir(mode=0o700, exist_ok=True)
        attributes = {"enable": "1" if enabled else "0", "device_path": "/dev/fixture-data",
                      "device_uuid": str(uuid.uuid4()) if default else self.original_uuid,
                      "device_nguid": str(uuid.uuid4()) if default else self.original_nguid}
        if eui:
            attributes["device_eui64"] = "ffffffffffffffff" if default else "0123456789abcdef"
        for name, value in attributes.items():
            (ns / name).write_text(value)

    def cleanup(self, payload, ownership=None):
        idle = lambda: {"status": "ok", "sessionSchemaVersion": 2, "observedNvmeofTcpCount": 0,
                        "warnings": [], "sessions": []}
        return NvmeManagedCleanup(self.base, self.state, payload, idle, ownership=ownership)

    def probe(self, binding, path):
        self.probes.append((binding["volumeUuid"], path))
        volume, size = self.devices.get(path, ("foreign", 1))
        return {"devicePath": path, "serial": volume.replace("-", "")[:20], "sizeBytes": size}

    def write_attribute(self, fd, raw):
        self.writes.append(Path(os.readlink("/proc/self/fd/" + str(fd))).name)
        result = os.write(fd, raw)
        # Ordinary fixture files model configfs scalar replacement semantics.
        os.ftruncate(fd, len(raw))
        return result

    def identity(self, payload=None, **extra):
        return module.NvmeRuntimeIdentity(self.cleanup(payload or self.source), payload or self.source,
            generation_root=self.gen.root, probe=extra.pop("probe", self.probe),
            boot=extra.pop("boot", lambda: self.boot), write=extra.pop("write", self.write_attribute), **extra)

    def receipt(self):
        return generation.read_json(self.gen.pending)[module.NVME_IDENTITY_FIELD]

    def replace_receipt(self, value):
        value["checkpointSha256"] = module.nvme_identity_digest(
            {key: item for key, item in value.items() if key != "checkpointSha256"})
        pending = generation.read_json(self.gen.pending)
        generation.atomic_json(self.gen.pending, {**pending, module.NVME_IDENTITY_FIELD: value})

    def recreate_after_failed_delete(self):
        first = self.identity(self.empty)
        self.assertEqual([], self.writes)
        self.assertEqual(self.original_data, self.raw.read_bytes())
        generation.atomic_json(self.state, self.empty)
        shutil.rmtree(self.subsystem_path)
        self.gen.execute("restore", self.request)
        recovered = self.identity(self.source)
        self.make_runtime(default=True, enabled=False)
        return first, recovered

    def test_failed_delete_restores_original_controller_and_namespace_before_enable_or_expose(self):
        first, recovered = self.recreate_after_failed_delete()
        self.assertNotEqual(self.original_serial, (self.subsystem_path / "attr_serial").read_text())
        recovered.restore_subsystem(self.subsystem)
        recovered.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertEqual(self.original_serial, (self.subsystem_path / "attr_serial").read_text())
        self.assertEqual(self.original_model, (self.subsystem_path / "attr_model").read_text())
        self.assertEqual(self.original_uuid, (self.namespace_path() / "device_uuid").read_text())
        self.assertEqual(self.original_nguid, (self.namespace_path() / "device_nguid").read_text())
        self.assertEqual("0123456789abcdef", (self.namespace_path() / "device_eui64").read_text())
        self.assertEqual("0", (self.namespace_path() / "enable").read_text())
        self.assertTrue(set(self.writes) <= {"attr_serial", "attr_model", "device_uuid", "device_nguid", "device_eui64"})
        self.assertEqual(first.checkpoint, recovered.checkpoint)
        self.assertEqual(self.original_data, self.raw.read_bytes())

    def test_existing_enabled_namespace_with_exact_identity_never_writes(self):
        identity = self.identity()
        identity.restore_subsystem(self.subsystem)
        identity.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertEqual([], self.writes)

    def test_enabled_namespace_identity_drift_rejects_without_writing(self):
        identity = self.identity()
        (self.namespace_path() / "device_uuid").write_text(str(uuid.uuid4()))
        with self.assertRaisesRegex(ValueError, "enabled namespace"):
            identity.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertEqual([], self.writes)

    def test_source_checkpoint_is_private_public_only_and_immutable_across_recovery(self):
        first = self.identity(self.empty)
        record = self.receipt(); raw = self.gen.pending.read_bytes()
        self.assertEqual(0o600, self.gen.pending.stat().st_mode & 0o777)
        self.assertEqual(self.request["operationUuid"], record["scope"]["operationUuid"])
        self.assertEqual(self.original["operationUuid"], record["sourceGeneration"]["operationUuid"])
        self.assertEqual(first.checkpoint["sourceConfigurationSha256"], self.gen.digest())
        self.assertFalse(any(name in json.dumps(record).lower() for name in ("password", "dhchap", "secret", "keytab")))
        self.identity(self.empty)
        self.assertEqual(raw, self.gen.pending.read_bytes())

    def test_unknown_payload_identity_is_not_an_authority(self):
        forged = copy.deepcopy(self.source)
        forged["subsystems"][0].update(attr_serial="FORGED-SERIAL", attr_model="FORGED-MODEL")
        forged["subsystems"][0]["namespaces"][0].update(device_uuid=str(uuid.uuid4()), device_nguid=str(uuid.uuid4()))
        identity = self.identity(forged)
        self.assertEqual(self.original_serial, identity.checkpoint["identities"][0]["attributes"]["attr_serial"])
        self.assertEqual(self.original_uuid, identity.checkpoint["identities"][0]["namespaces"][0]["attributes"]["device_uuid"])
        self.assertNotIn("FORGED", json.dumps(identity.checkpoint))

    def test_namespace_number_update_reuses_identity_only_for_the_exact_original_resource(self):
        target = copy.deepcopy(self.source); target["subsystems"][0]["namespaces"][0]["lunOrNamespace"] = "2"
        identity = self.identity(target)
        ns = self.namespace_path("2"); ns.mkdir(mode=0o700)
        for name, value in {"enable": "0", "device_path": "/dev/fixture-data",
                            "device_uuid": str(uuid.uuid4()), "device_nguid": str(uuid.uuid4()),
                            "device_eui64": "ffffffffffffffff"}.items():
            (ns / name).write_text(value)
        identity.restore_namespace(target["subsystems"][0], target["subsystems"][0]["namespaces"][0], "/dev/fixture-data")
        self.assertEqual(self.original_uuid, (ns / "device_uuid").read_text())
        self.assertEqual(self.original_nguid, (ns / "device_nguid").read_text())

    def test_unapproved_resource_nqn_nsid_or_volume_is_rejected(self):
        identity = self.identity()
        for changes in ({"uuid": str(uuid.uuid4())}, {"lunOrNamespace": "2"}, {"lunOrNamespace": True}, {"volumeUuid": str(uuid.uuid4())}):
            with self.subTest(changes=changes):
                row = {**self.namespace, **changes}
                with self.assertRaises(ValueError):
                    identity.restore_namespace(self.subsystem, row, "/dev/fixture-data")
        parent = {**self.subsystem, "targetName": self.nqn + "-foreign"}
        with self.assertRaises(ValueError):
            identity.restore_subsystem(parent)
        self.assertEqual([], self.writes)

    def test_different_volume_serial_or_size_rejects_before_checkpoint_or_write(self):
        for field, wrong in (("serial", "foreign"), ("sizeBytes", self.size + 1)):
            def probe(binding, path):
                return {**self.probe(binding, path), field: wrong}
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.identity(probe=probe)
            self.assertNotIn(module.NVME_IDENTITY_FIELD, generation.read_json(self.gen.pending))
        self.assertEqual([], self.writes)

    def test_receipt_scope_boot_fingerprint_or_unknown_field_cannot_be_borrowed(self):
        self.identity()
        original = self.receipt()
        mutations = [
            lambda v: v["scope"].update(operationUuid=str(uuid.uuid4())),
            lambda v: v.update(bootId=str(uuid.uuid4())),
            lambda v: v.update(sourceConfigurationSha256="0" * 64),
            lambda v: v.update(foreignField="SYNTHETIC_PUBLIC"),
            lambda v: v["identities"][0]["namespaces"][0]["binding"].update(volumeUuid=str(uuid.uuid4()))]
        for change in mutations:
            with self.subTest(change=change):
                value = copy.deepcopy(original); change(value); self.replace_receipt(value)
                with self.assertRaises(ValueError):
                    self.identity()
                self.assertEqual([], self.writes)
        self.replace_receipt(original)

    def test_current_pending_phase_or_canonical_source_change_rejects_before_effects(self):
        pending = self.gen.pending.read_bytes(); current = self.gen.current.read_bytes(); source = self.state.read_bytes()
        value = generation.read_json(self.gen.pending); value["phase"] = "COMMITTED"
        generation.atomic_json(self.gen.pending, value)
        with self.assertRaises(ValueError): self.identity()
        self.gen.pending.write_bytes(pending)
        generation.atomic_json(self.gen.current, {})
        with self.assertRaises(ValueError): self.identity()
        self.gen.current.write_bytes(current)
        generation.atomic_json(self.state, self.empty)
        with self.assertRaises(ValueError): self.identity()
        self.state.write_bytes(source)
        self.assertEqual([], self.writes)

    def test_missing_or_unlocked_fd9_rejects_without_checkpoint(self):
        fcntl.flock(9, fcntl.LOCK_UN)
        with self.assertRaises(ValueError): self.identity()
        self.assertNotIn(module.NVME_IDENTITY_FIELD, generation.read_json(self.gen.pending))

    def test_changed_target_intent_under_same_pending_is_rejected(self):
        self.identity(self.empty)
        target = copy.deepcopy(self.source); target["subsystems"][0]["namespaces"][0]["lunOrNamespace"] = "2"
        with self.assertRaisesRegex(ValueError, "original canonical target"):
            self.identity(target)
        self.assertEqual([], self.writes)

    def test_receipt_fsync_publication_failure_prevents_kernel_mutation(self):
        original = self.gen.pending.read_bytes()
        with patch.object(module.os, "replace", side_effect=OSError("SYNTHETIC_ATOMIC_FAILURE")):
            with self.assertRaises(OSError): self.identity(self.empty)
        self.assertEqual(original, self.gen.pending.read_bytes())
        self.assertEqual(self.original_serial, (self.subsystem_path / "attr_serial").read_text())
        self.assertEqual([], self.writes)
        self.assertFalse(list(self.gen.root.glob(".nvme-runtime-identity-*")))

    def test_checkpoint_or_boot_change_between_capture_and_restore_rejects(self):
        identity = self.identity()
        changed = copy.deepcopy(self.receipt()); changed["identities"][0]["attributes"]["attr_serial"] = "FOREIGN123456789"
        self.replace_receipt(changed)
        with self.assertRaisesRegex(ValueError, "immutable"):
            identity.restore_subsystem(self.subsystem)
        self.replace_receipt(identity.checkpoint)
        identity.boot = lambda: str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError, "boot changed"):
            identity.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertEqual([], self.writes)

    def test_live_enabled_check_occurs_before_each_namespace_write(self):
        _, recovered = self.recreate_after_failed_delete()
        recovered.restore_subsystem(self.subsystem)
        def race(fd, raw):
            result = self.write_attribute(fd, raw)
            (self.namespace_path() / "enable").write_text("1")
            return result
        recovered.write = race
        before = len(self.writes)
        with self.assertRaisesRegex(ValueError, "became enabled"):
            recovered.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertEqual(1, len(self.writes) - before)
        self.assertIn(self.writes[-1], ("device_uuid", "device_nguid", "device_eui64"))

    def test_metadata_write_incomplete_or_wrong_readback_fails_closed(self):
        _, recovered = self.recreate_after_failed_delete()
        for write in (lambda fd, raw: len(raw) - 1,
                      lambda fd, raw: self.write_attribute(fd, b"X" * len(raw))):
            recovered.write = write
            with self.assertRaises(ValueError):
                recovered.restore_subsystem(self.subsystem)
        self.assertEqual(self.original_data, self.raw.read_bytes())

    def test_symlink_or_oversized_identity_attribute_rejects_before_checkpoint(self):
        path = self.subsystem_path / "attr_serial"
        value = path.read_text(); path.unlink(); path.symlink_to(self.raw)
        with self.assertRaises((OSError, ValueError)): self.identity()
        path.unlink(); path.write_text(value * 20)
        with self.assertRaises(ValueError): self.identity()
        self.assertNotIn(module.NVME_IDENTITY_FIELD, generation.read_json(self.gen.pending))

    def test_absent_optional_eui64_is_preserved_without_inventing_an_attribute(self):
        (self.namespace_path() / "device_eui64").unlink()
        _, recovered = self.recreate_after_failed_delete()
        (self.namespace_path() / "device_eui64").unlink()
        recovered.restore_subsystem(self.subsystem)
        recovered.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertFalse((self.namespace_path() / "device_eui64").exists())
        self.assertNotIn("device_eui64", self.writes)

    def test_no_pending_startup_uses_live_identity_only_without_persisting_history(self):
        self.gen.pending.unlink()
        identity = self.identity()
        identity.restore_subsystem(self.subsystem)
        identity.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertIsNone(identity.scope)
        self.assertFalse(self.gen.pending.exists())
        self.assertEqual([], self.writes)

    def test_new_resource_does_not_adopt_identity_from_another_source_uuid(self):
        target = copy.deepcopy(self.source); target["subsystems"][0]["uuid"] = str(uuid.uuid4())
        target["subsystems"][0]["targetName"] += "-new"
        target["subsystems"][0]["namespaces"] = []
        identity = self.identity(target)
        nqn = target["subsystems"][0]["targetName"]
        path = self.base / "subsystems" / nqn; path.mkdir(mode=0o700)
        (path / "namespaces").mkdir(mode=0o700)
        (path / "attr_serial").write_text("NEWRESOURCE12345")
        (path / "attr_model").write_text("Linux")
        identity.restore_subsystem(target["subsystems"][0])
        self.assertEqual("NEWRESOURCE12345", (path / "attr_serial").read_text())
        self.assertEqual([], self.writes)

    def test_wrong_optional_attribute_presence_rejects_before_any_namespace_write(self):
        (self.namespace_path() / "device_eui64").unlink()
        _, recovered = self.recreate_after_failed_delete()
        recovered.restore_subsystem(self.subsystem)
        before = len(self.writes)
        with self.assertRaisesRegex(ValueError, "presence changed"):
            recovered.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertEqual(before, len(self.writes))

    def test_source_controller_identity_mismatch_never_rewrites_an_existing_object(self):
        identity = self.identity()
        (self.subsystem_path / "attr_serial").write_text("FOREIGN123456789")
        with self.assertRaisesRegex(ValueError, "existing controller"):
            identity.restore_subsystem(self.subsystem)
        self.assertEqual([], self.writes)

    def test_recreated_controller_identity_cannot_change_after_port_exposure(self):
        _, recovered = self.recreate_after_failed_delete()
        port = self.base / "ports/1/subsystems"; port.mkdir(parents=True)
        (port / self.nqn).symlink_to(self.subsystem_path, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "port exposure"):
            recovered.restore_subsystem(self.subsystem)
        self.assertEqual([], self.writes)

    def test_prior_protocol_target_files_do_not_replace_the_frozen_source_authority(self):
        generation.atomic_json(self.config / "iscsi-targets.json",
                               {"instanceUuid": self.instance, "targets": []})
        self.assertNotEqual(self.gen.current.read_text(), self.gen.pending.read_text())
        identity = self.identity()
        self.assertEqual(self.request["previousGeneration"]["configurationSha256"],
                         identity.checkpoint["sourceConfigurationSha256"])
        self.assertEqual([], self.writes)

    def test_frozen_source_artifact_hash_scope_or_phase_drift_is_rejected(self):
        path = self.gen.root / (self.original["operationUuid"] + ".json")
        original = path.read_bytes()
        for field, wrong in (("phase", "PREPARED"), ("revision", 99),
                             ("configurationSha256", "0" * 64)):
            value = json.loads(original); value[field] = wrong
            generation.atomic_json(path, value)
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.identity()
            path.write_bytes(original)
        self.assertEqual([], self.writes)

    def inactive_namespace_absence(self, state):
        inactive = copy.deepcopy(self.source)
        inactive["subsystems"][0]["namespaces"][0]["state"] = state
        self.gen.pending.unlink()
        generation.atomic_json(self.state, inactive)
        # Build a real verified inactive SOURCE generation.
        active = {**self.original, "operationUuid": str(uuid.uuid4()), "revision": 2}
        generation.atomic_json(self.gen.current, {})
        for action in ("begin", "verify", "commit", "finish"):
            self.gen.execute(action, active)
        self.request = {**active, "operationUuid": str(uuid.uuid4()), "revision": 3}
        self.gen.execute("begin", self.request)
        shutil.rmtree(self.namespace_path())
        identity = self.identity(inactive)
        self.assertEqual([], identity.checkpoint["identities"][0]["namespaces"])
        self.assertEqual([], self.writes)

    def test_disabled_source_namespace_can_be_absent_without_inventing_its_identity(self):
        self.inactive_namespace_absence("Disabled")

    def test_error_source_namespace_can_be_absent_without_inventing_its_identity(self):
        self.inactive_namespace_absence("Error")

    def test_destroyed_source_namespace_can_be_absent_without_inventing_its_identity(self):
        self.inactive_namespace_absence("Destroyed")

    def test_production_topology_probe_uses_only_fixed_public_lsblk_and_exact_raw_disk(self):
        binding = module.nvme_identity_bindings(self.source, self.instance)["namespaces"][0]
        result = type("PublicResult", (), {"returncode": 0, "stdout": json.dumps({"blockdevices": [{
            "path": "/dev/fixture-data", "type": "disk", "serial": self.volume.replace("-", "")[:20],
            "size": self.size, "fstype": None, "mountpoints": [None]}]})})()
        import stat
        with patch.object(module.subprocess, "run", return_value=result) as run, \
                patch.object(module.os, "stat", return_value=type("PublicStat", (), {"st_mode": stat.S_IFBLK})()):
            proof = module.NvmeRuntimeIdentity.probe_volume(binding, "/dev/fixture-data")
        self.assertEqual(self.volume.replace("-", "")[:20], proof["serial"])
        self.assertEqual(self.size, proof["sizeBytes"])
        self.assertEqual(["lsblk", "-J", "-b", "-o", "PATH,TYPE,SERIAL,SIZE,FSTYPE,MOUNTPOINTS"],
                         run.call_args.args[0])

    def ownership(self, cleanup, payload):
        return module.NvmeRuntimeIdentity.cleanup_authority(cleanup, payload,
            generation_root=self.gen.root, probe=self.probe, boot=lambda: self.boot)

    def namespace_only(self, number, path="/dev/fixture-data", enabled=True):
        ns = self.namespace_path(number); ns.mkdir(mode=0o700)
        for name, value in {"enable": "1" if enabled else "0", "device_path": path,
                            "device_uuid": str(uuid.uuid4()), "device_nguid": str(uuid.uuid4()),
                            "device_eui64": "ffffffffffffffff"}.items():
            (ns / name).write_text(value)

    def configfs_rmdir(self, path):
        # Metadata-only disposable configfs model, never a DATA path.
        path = Path(path)
        for child in list(path.iterdir()):
            if child.is_symlink():
                raise AssertionError("foreign/shared link reached identity fixture rmdir")
            if child.is_dir():
                child.rmdir()
            elif child.name in ("enable", "device_path", "device_uuid", "device_nguid",
                               "device_eui64", "attr_serial", "attr_model", "attr_allow_any_host", "dhchap_key"):
                child.unlink()
            else:
                raise AssertionError("unknown attribute reached identity fixture rmdir")
        path.rmdir()

    def checkpoint_namespace_create_target(self):
        target = copy.deepcopy(self.source)
        extra = {"uuid": str(uuid.uuid4()), "lunOrNamespace": "2",
                 "volumeUuid": str(uuid.uuid4()), "volumeSizeBytes": self.size, "state": "Ready"}
        target["subsystems"][0]["namespaces"].append(extra)
        self.devices["/dev/fixture-extra"] = (extra["volumeUuid"], self.size)
        self.identity(target)
        self.namespace_only("2", "/dev/fixture-extra")
        generation.atomic_json(self.state, target)
        self.gen.execute("restore", self.request)
        return target

    def test_failed_namespace_create_cleanup_uses_only_protected_target_triplet_after_source_restore(self):
        self.checkpoint_namespace_create_target()
        with self.assertRaises(ValueError):
            self.cleanup(self.source)  # Default/no-hook behavior is unchanged.
        before = self.gen.pending.read_bytes()
        cleanup = self.cleanup(self.source, self.ownership)
        self.assertEqual(self.source, cleanup.previous)
        self.assertEqual({"1", "2"}, {str(row["lunOrNamespace"])
                                     for row in cleanup.owned[self.nqn]["namespaces"]})
        cleanup.rmdir = self.configfs_rmdir
        cleanup.finish({})
        self.assertFalse(self.namespace_path("2").exists())
        self.assertTrue(self.namespace_path("1").exists())
        self.assertEqual(before, self.gen.pending.read_bytes())
        self.assertEqual(self.original_data, self.raw.read_bytes())

    def test_failed_new_subsystem_create_cleanup_preserves_source_and_unrelated_foreign_target(self):
        target = copy.deepcopy(self.source)
        new = {"uuid": str(uuid.uuid4()), "targetName": self.nqn + "-target", "namespaces": [], "hosts": []}
        target["subsystems"].append(new)
        self.identity(target)
        path = self.base / "subsystems" / new["targetName"]; path.mkdir(mode=0o700)
        for name in ("namespaces", "allowed_hosts"): (path / name).mkdir(mode=0o700)
        for name, value in {"attr_serial": "TARGET0123456789", "attr_model": "Linux"}.items():
            (path / name).write_text(value)
        foreign = self.base / "subsystems" / (self.nqn + "-foreign"); foreign.mkdir(mode=0o700)
        (foreign / "opaque-public").write_text("FOREIGN PRESERVED")
        generation.atomic_json(self.state, target); self.gen.execute("restore", self.request)
        cleanup = self.cleanup(self.source, self.ownership); cleanup.rmdir = self.configfs_rmdir
        cleanup.finish({})
        self.assertFalse(path.exists())
        self.assertEqual("FOREIGN PRESERVED", (foreign / "opaque-public").read_text())
        self.assertEqual(self.original_serial, (self.subsystem_path / "attr_serial").read_text())
        self.assertEqual(self.original_data, self.raw.read_bytes())

    def test_failed_namespace_id_update_allows_exact_target_two_and_restores_source_one(self):
        target = copy.deepcopy(self.source); target["subsystems"][0]["namespaces"][0]["lunOrNamespace"] = "2"
        self.identity(target)
        shutil.rmtree(self.namespace_path("1"))
        self.namespace_only("2")
        generation.atomic_json(self.state, target); self.gen.execute("restore", self.request)
        cleanup = self.cleanup(self.source, self.ownership)
        identity = module.NvmeRuntimeIdentity(cleanup, self.source, generation_root=self.gen.root,
            probe=self.probe, boot=lambda: self.boot, write=self.write_attribute)
        self.namespace_only("1", enabled=False)
        identity.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        (self.namespace_path("1") / "enable").write_text("1")
        cleanup.rmdir = self.configfs_rmdir; cleanup.finish({})
        self.assertFalse(self.namespace_path("2").exists())
        self.assertEqual(self.original_uuid, (self.namespace_path("1") / "device_uuid").read_text())

    def test_recorded_target_nsid_with_foreign_disk_refuses_before_cleanup_or_identity_writes(self):
        self.checkpoint_namespace_create_target()
        (self.namespace_path("2") / "device_path").write_text("/dev/foreign")
        with self.assertRaisesRegex(ValueError, "DATA volume is foreign"):
            self.cleanup(self.source, self.ownership)
        self.assertEqual("1", (self.namespace_path("2") / "enable").read_text())
        self.assertEqual([], self.writes)
        self.assertEqual(self.original_data, self.raw.read_bytes())

    def test_unrecorded_namespace_even_with_an_owned_disk_is_not_cleanup_authority(self):
        self.checkpoint_namespace_create_target(); self.namespace_only("99")
        with self.assertRaisesRegex(ValueError, "unrecorded namespace"):
            self.cleanup(self.source, self.ownership)
        self.assertTrue(self.namespace_path("99").exists())
        self.assertEqual([], self.writes)

    def test_recorded_target_host_cleanup_keeps_auth_body_out_of_identity_checkpoint(self):
        target = copy.deepcopy(self.source)
        host = "nqn.2026-10.example:owned-new-host"
        target["subsystems"][0]["hosts"] = [{"uuid": str(uuid.uuid4()), "principal": host,
                                            "secrets": {"dhChapKey": "SYNTHETIC_PRIVATE"}}]
        self.identity(target)
        directory = self.base / "hosts" / host; directory.mkdir(mode=0o700)
        (directory / "dhchap_key").write_text("SYNTHETIC_PRIVATE")
        (self.subsystem_path / "allowed_hosts" / host).symlink_to(directory, target_is_directory=True)
        forward = self.cleanup(target); forward.created_host(host)
        generation.atomic_json(self.state, target); self.gen.execute("restore", self.request)
        self.assertNotIn("SYNTHETIC_PRIVATE", self.gen.pending.read_text())
        cleanup = self.cleanup(self.source, self.ownership); cleanup.rmdir = self.configfs_rmdir
        cleanup.finish({})
        self.assertFalse((self.subsystem_path / "allowed_hosts" / host).exists())
        self.assertFalse(directory.exists())
        self.assertEqual(self.original_data, self.raw.read_bytes())

    def test_unrecorded_host_is_preserved_and_refused_before_cleanup(self):
        self.checkpoint_namespace_create_target()
        host = "nqn.2026-10.example:foreign-host"
        directory = self.base / "hosts" / host; directory.mkdir(mode=0o700)
        (directory / "dhchap_key").write_text("SYNTHETIC_PRIVATE")
        link = self.subsystem_path / "allowed_hosts" / host
        link.symlink_to(directory, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "unrecorded host"):
            self.cleanup(self.source, self.ownership)
        self.assertTrue(link.is_symlink())
        self.assertEqual("SYNTHETIC_PRIVATE", (directory / "dhchap_key").read_text())
        self.assertEqual([], self.writes)

    def test_cleanup_callback_never_mints_missing_receipt_or_accepts_changed_scope(self):
        cleanup = self.cleanup(self.source)
        self.assertEqual({}, self.ownership(cleanup, self.source))
        self.assertNotIn(module.NVME_IDENTITY_FIELD, generation.read_json(self.gen.pending))
        self.identity()
        value = self.receipt(); value["scope"]["operationUuid"] = str(uuid.uuid4()); self.replace_receipt(value)
        with self.assertRaises(ValueError):
            self.cleanup(self.source, self.ownership)
        self.assertEqual([], self.writes)

    def test_namespace_volume_switch_after_first_proof_refuses_before_identity_write(self):
        _, recovered = self.recreate_after_failed_delete()
        recovered.restore_subsystem(self.subsystem)
        calls = []
        def switched(binding, path):
            calls.append(path)
            proof = self.probe(binding, path)
            return proof if len(calls) == 1 else {**proof, "serial": "foreign"}
        recovered.probe = switched
        before = len(self.writes)
        with self.assertRaises(ValueError):
            recovered.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertEqual(before, len(self.writes))
        self.assertEqual(self.original_data, self.raw.read_bytes())

    def test_first_nvme_on_an_exact_absent_source_uses_empty_identity_without_adopting_defaults(self):
        self.gen.pending.unlink()
        self.state.unlink()
        shutil.rmtree(self.subsystem_path)
        generation.atomic_json(self.gen.current, {})
        initial = {"instanceUuid": self.instance, "operationUuid": str(uuid.uuid4()), "revision": 1}
        for action in ("begin", "verify", "commit", "finish"):
            self.gen.execute(action, initial)
        self.gen.execute("begin", {**initial, "operationUuid": str(uuid.uuid4()), "revision": 2})
        identity = self.identity(self.source)
        self.assertEqual([], identity.checkpoint["identities"])
        self.assertEqual({"subsystems": [], "namespaces": [], "hosts": []},
                         identity.checkpoint["sourceBindings"])
        self.assertEqual([], self.writes)

    def test_verified_noncommitted_recovery_restores_identity_under_the_exact_source_current(self):
        self.identity(self.empty)
        generation.atomic_json(self.state, self.empty)
        shutil.rmtree(self.subsystem_path)
        self.gen.execute("verify", self.request)
        self.assertEqual(self.request["previousGeneration"], generation.read_json(self.gen.current))
        self.gen.execute("restore", self.request)
        cleanup = self.cleanup(self.source, self.ownership)
        recovered = module.NvmeRuntimeIdentity(cleanup, self.source, generation_root=self.gen.root,
            probe=self.probe, boot=lambda: self.boot, write=self.write_attribute)
        self.make_runtime(default=True, enabled=False)
        recovered.restore_subsystem(self.subsystem)
        recovered.restore_namespace(self.subsystem, self.namespace, "/dev/fixture-data")
        self.assertEqual(self.original_serial, (self.subsystem_path / "attr_serial").read_text())
        self.assertEqual(self.original_uuid, (self.namespace_path() / "device_uuid").read_text())
        self.assertEqual("VERIFIED", generation.read_json(self.gen.pending)["phase"])
        self.assertEqual(self.request["previousGeneration"], generation.read_json(self.gen.current))

    def test_committed_current_target_never_borrows_original_source_identity(self):
        self.identity(self.empty)
        generation.atomic_json(self.state, self.empty)
        self.gen.execute("verify", self.request)
        self.gen.execute("commit", self.request)
        with self.assertRaises(ValueError):
            self.cleanup(self.source, self.ownership)
        with self.assertRaises(ValueError):
            self.identity(self.source)
        self.assertEqual([], self.writes)

    def test_verified_noncommitted_but_foreign_source_fingerprint_is_rejected(self):
        self.identity(self.empty)
        generation.atomic_json(self.state, self.empty)
        self.gen.execute("verify", self.request)
        with self.assertRaisesRegex(ValueError, "SOURCE fingerprint"):
            self.cleanup(self.source, self.ownership)
        self.assertEqual([], self.writes)

    def test_verified_writer_without_original_checkpoint_cannot_adopt_a_late_source_identity(self):
        self.gen.execute("verify", self.request)
        with self.assertRaisesRegex(ValueError, "original pre-mutation"):
            self.identity(self.source)
        self.assertNotIn(module.NVME_IDENTITY_FIELD, generation.read_json(self.gen.pending))
        self.assertEqual([], self.writes)

    def test_configuration_hash_matches_the_existing_generation_redaction_contract(self):
        canonical = generation.read_json(self.state)
        canonical["lastApply"] = {"generatedEpoch": 123}
        canonical["subsystems"][0]["hosts"] = [{"principal": "nqn.2026-10.example:client",
                                                "secrets": {"dhChapKey": "SYNTHETIC_PRIVATE"}}]
        generation.atomic_json(self.state, canonical)
        helper = object.__new__(module.NvmeRuntimeIdentity)
        helper.cleanup = self.cleanup(self.source)
        self.assertEqual(self.gen.digest(), module.nvme_identity_digest(helper._canonical()))


if __name__ == "__main__":
    unittest.main()

