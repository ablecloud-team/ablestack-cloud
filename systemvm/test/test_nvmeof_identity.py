# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Persistent NVMe identifiers; native opt-in test exercises actual configfs."""
import ast
import importlib.util
import json
import os
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[1]
MODULE = Path(os.environ.get("NVMEOF_IDENTITY_MODULE", ROOT / "debian/usr/local/lib/ablestack-storage/nvmeof_identity.py"))
spec = importlib.util.spec_from_file_location("nvme_identity_test", MODULE)
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)


class IdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.ns = self.root / "namespace"
        self.ns.mkdir()
        self.nqn = "nqn.2026-10.local.storage:identity-test"
        self.value = {"uuid": str(uuid.uuid4()), "volumeUuid": str(uuid.uuid4()), "lunOrNamespace": 1}
        self.store = identity.NamespaceIdentityStore(self.root / "identities.json")
        self.original = {"device_uuid": uuid.uuid4().hex, "device_nguid": uuid.uuid4().hex}
        self.make_namespace(self.original)

    def make_namespace(self, attributes, enabled="0"):
        for name, value in attributes.items():
            (self.ns / name).write_text(value)
        (self.ns / "enable").write_text(enabled)
        (self.ns / "device_path").write_text("/dev/owned-data")

    def tearDown(self):
        self.temp.cleanup()

    def prepare(self):
        self.store.prepare(self.ns, self.nqn, self.value, "/dev/owned-data")

    def test_new_identity_is_durable_before_enable_and_file_is_private(self):
        self.prepare()
        self.assertEqual("0", (self.ns / "enable").read_text())
        self.assertEqual(self.original, self.store.load()["entries"][self.store.key(self.nqn, self.value)])
        self.assertEqual(0o600, self.store.path.stat().st_mode & 0o777)

    def test_cold_kernel_recreation_restores_supported_identifiers(self):
        self.prepare()
        self.make_namespace({"device_uuid": uuid.uuid4().hex, "device_nguid": "0" * 32})
        self.prepare()
        self.assertEqual(self.original, self.store.observe(self.ns))

    def test_legacy_active_identity_is_adopted_without_native_writes(self):
        (self.ns / "enable").write_text("1")
        with patch.object(Path, "write_text", side_effect=AssertionError("live kernel write")):
            self.prepare()
        self.assertEqual(self.original, self.store.observe(self.ns))

    def test_live_identity_mismatch_is_rejected_without_changing_kernel(self):
        self.prepare()
        changed = {"device_uuid": uuid.uuid4().hex, "device_nguid": self.original["device_nguid"]}
        self.make_namespace(changed, "1")
        with patch.object(Path, "write_text", side_effect=AssertionError("live kernel write")):
            with self.assertRaisesRegex(ValueError, "ACTIVE_MISMATCH"):
                self.prepare()
        self.assertEqual(changed, self.store.observe(self.ns))

    def test_active_backing_mismatch_cannot_rebind_the_identity(self):
        (self.ns / "enable").write_text("1")
        (self.ns / "device_path").write_text("/dev/other-data")
        with self.assertRaisesRegex(ValueError, "BACKING_CHANGED"):
            self.prepare()
        self.assertFalse(self.store.path.exists())

    def test_new_resource_and_volume_have_distinct_bindings(self):
        self.prepare()
        self.value = {**self.value, "uuid": str(uuid.uuid4()), "volumeUuid": str(uuid.uuid4())}
        changed = {"device_uuid": uuid.uuid4().hex, "device_nguid": "0" * 32}
        self.make_namespace(changed)
        self.prepare()
        self.assertEqual(changed, self.store.observe(self.ns))
        self.assertEqual(2, len(self.store.load()["entries"]))

    def test_corrupt_store_fails_before_native_write(self):
        self.store.path.write_text("{")
        with patch.object(Path, "write_text", side_effect=AssertionError("native write")):
            with self.assertRaises(ValueError):
                self.prepare()
        self.assertEqual(self.original, self.store.observe(self.ns))

    def test_missing_required_identifier_and_zero_uuid_are_rejected(self):
        (self.ns / "device_uuid").unlink()
        with self.assertRaisesRegex(ValueError, "UNSUPPORTED"):
            self.prepare()
        (self.ns / "device_uuid").write_text("0" * 32)
        with self.assertRaisesRegex(ValueError, "INVALID"):
            self.prepare()

    def test_readonly_optional_identifier_is_verified_not_forced(self):
        (self.ns / "device_eui64").write_text("0" * 16)
        (self.ns / "device_eui64").chmod(0o444)
        self.prepare()
        self.make_namespace({"device_uuid": uuid.uuid4().hex, "device_nguid": "0" * 32})
        self.prepare()
        self.assertEqual("0" * 16, (self.ns / "device_eui64").read_text())
        (self.ns / "device_eui64").chmod(0o644)

    def test_failed_checkpoint_cannot_mutate_or_enable_namespace(self):
        with patch.object(self.store, "save", side_effect=OSError("checkpoint unavailable")):
            with self.assertRaisesRegex(OSError, "checkpoint unavailable"):
                self.prepare()
        self.assertEqual(self.original, self.store.observe(self.ns))
        self.assertEqual("0", (self.ns / "enable").read_text())

    def test_actual_storagectl_orders_prepare_before_enable_and_verify_after(self):
        storagectl = Path(os.environ.get("NVMEOF_STORAGECTL", ROOT / "debian/usr/local/bin/ablestack-storagectl"))
        body = storagectl.read_text().split("apply_nvmeof_subsystems() {", 1)[1]
        code = re.search(r"<<'PY'\n(.*?)\nPY", body, re.S).group(1)
        ast.parse(code)
        prepare = code.index("identity_store.prepare(ns_dir, nqn, namespace, backing_path)")
        enable = code.index('write_if_changed(os.path.join(ns_dir, "enable"), "1")', prepare)
        verify = code.index("identity_store.verify(ns_dir, nqn, namespace)", enable)
        self.assertLess(prepare, enable)
        self.assertLess(enable, verify)



@unittest.skipUnless(os.environ.get("NVMEOF_IDENTITY_NATIVE_DEVICE"), "Requires an owned disposable SPARSE block volume")
class NativeIdentityTests(unittest.TestCase):
    def setUp(self):
        import stat
        self.device = os.environ["NVMEOF_IDENTITY_NATIVE_DEVICE"]
        self.volume = str(uuid.UUID(os.environ["NVMEOF_IDENTITY_NATIVE_VOLUME"]))
        self.assertTrue(stat.S_ISBLK(os.stat(self.device).st_mode))
        self.temporary = tempfile.TemporaryDirectory(prefix="nvme-identity-native-")
        self.nqn = "nqn.2026-10.local.storage:identity-test-" + str(uuid.uuid4())
        self.subsystem = Path("/sys/kernel/config/nvmet/subsystems") / self.nqn
        self.subsystem.mkdir()
        self.ns = self.subsystem / "namespaces/1"
        self.ns.mkdir()
        (self.ns / "device_path").write_text(self.device)
        self.value = {"uuid": str(uuid.uuid4()), "volumeUuid": self.volume, "lunOrNamespace": 1}
        self.store = identity.NamespaceIdentityStore(Path(self.temporary.name) / "identities.json")
        self.before_data = self.sample()

    def sample(self):
        import hashlib
        with open(self.device, "rb", buffering=0) as stream:
            stream.seek(1048576)
            return hashlib.sha256(stream.read(4096)).hexdigest()

    def tearDown(self):
        try:
            (self.ns / "enable").write_text("0")
            self.ns.rmdir()
            self.subsystem.rmdir()
            self.assertEqual(self.before_data, self.sample())
        finally:
            self.temporary.cleanup()

    def prepare(self):
        self.store.prepare(self.ns, self.nqn, self.value, self.device)

    def test_actual_recreated_kernel_namespace_restores_identifiers_before_enable(self):
        (self.ns / "device_nguid").write_text(str(uuid.uuid4()))
        original = self.store.observe(self.ns)
        self.prepare()
        self.assertEqual("0", (self.ns / "enable").read_text().strip())
        (self.ns / "enable").write_text("1")
        (self.ns / "enable").write_text("0")
        self.ns.rmdir()
        self.ns.mkdir()
        (self.ns / "device_path").write_text(self.device)
        self.assertNotEqual(original["device_uuid"], self.store.observe(self.ns)["device_uuid"])
        self.prepare()
        self.assertEqual(original, self.store.observe(self.ns))
        (self.ns / "enable").write_text("1")
        self.store.verify(self.ns, self.nqn, self.value)

    def test_actual_active_namespace_adoption_does_not_write_identifiers(self):
        (self.ns / "enable").write_text("1")
        original = self.store.observe(self.ns)
        with patch.object(Path, "write_text", side_effect=AssertionError("live configfs write")):
            self.prepare()
        self.assertEqual(original, self.store.observe(self.ns))

    def test_actual_active_mismatch_does_not_rewrite_kernel(self):
        self.prepare()
        original = self.store.observe(self.ns)
        value = self.store.load()
        value["entries"][self.store.key(self.nqn, self.value)]["device_uuid"] = uuid.uuid4().hex
        self.store.save(value)
        (self.ns / "enable").write_text("1")
        with self.assertRaisesRegex(ValueError, "ACTIVE_MISMATCH"):
            self.prepare()
        self.assertEqual(original, self.store.observe(self.ns))


if __name__ == "__main__":
    unittest.main(verbosity=2)
