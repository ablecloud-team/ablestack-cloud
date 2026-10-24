# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Opt-in contract regression on a disposable SystemVM's existing owned backstore."""
import ast
import importlib.util
import os
from pathlib import Path
import re
import sys
import types
import unittest
from unittest.mock import patch

NATIVE = os.environ.get("CONFIGURATION_NATIVE_BACKSTORE")
ROOT = Path(__file__).resolve().parents[1]
MODULE = Path(os.environ.get("CONFIGURATION_MODULE", ROOT / "debian/usr/local/lib/ablestack-storage/configuration_transaction.py"))
STORAGECTL = Path(os.environ.get("CONFIGURATION_STORAGECTL", ROOT / "debian/usr/local/bin/ablestack-storagectl"))


@unittest.skipUnless(NATIVE, "Requires an explicitly selected disposable SystemVM backstore")
class NativeContractTests(unittest.TestCase):
    def setUp(self):
        import rtslib_fb
        self.native = rtslib_fb.BlockStorageObject(NATIVE)
        self.before = (self.native.udev_path, self.native.wwn)
        body = STORAGECTL.read_text().split("apply_iscsi_targets() {", 1)[1]
        code = re.search(r"<<'PY'\n(.*?)\nPY", body, re.S).group(1)
        tree = ast.parse(code)
        function = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "resolve_backstore")
        namespace = {"os": os, "find_backing_path": lambda target: self.before[0],
                     "normalize_backstore_type": lambda target: "block", "mountpoint_for_device": lambda path: None,
                     "validate_block_backstore_device": lambda path: None,
                     "require_targetcli": self.reject_mutation}
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(STORAGECTL), "exec"), namespace)
        self.resolve = namespace["resolve_backstore"]
        spec = importlib.util.spec_from_file_location("configuration_transaction_native_test", MODULE)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def reject_mutation(self, *args):
        raise AssertionError("An existing lookup must not invoke targetcli mutation")

    def tearDown(self):
        import rtslib_fb
        native = rtslib_fb.BlockStorageObject(NATIVE)
        self.assertEqual(self.before, (native.udev_path, native.wwn))

    def test_real_existing_lookup_reuses_device_and_wwn_without_create(self):
        result = self.resolve({"uuid": NATIVE.removeprefix("ablestack-")}, NATIVE)
        self.assertEqual(self.before[0], result[1])
        self.assertFalse(result[3])

    def test_real_native_before_image_uses_supported_lookup_constructor(self):
        target = {"uuid": NATIVE.removeprefix("ablestack-"), "configurationRestoreBackstore": self.native.dump()}
        with patch.dict(os.environ, {"ABLESTACK_STORAGE_CONFIGURATION_TRANSACTION": "1"}):
            result = self.resolve(target, NATIVE)
        self.assertFalse(result[3])

    def test_programming_error_cannot_be_reclassified_as_absence(self):
        import rtslib_fb
        def broken_root():
            raise TypeError("native-contract-sentinel")
        with patch.object(rtslib_fb, "RTSRoot", broken_root):
            with self.assertRaisesRegex(TypeError, "native-contract-sentinel"):
                self.resolve({"uuid": NATIVE.removeprefix("ablestack-")}, NATIVE)

    def test_cleanup_absent_object_is_noop_but_programming_error_propagates(self):
        import rtslib_fb
        adapter = self.module.RuntimeAdapter(Path("/tmp/unused-configuration-native-test"))
        payload = {"candidate": {"iscsi target apply": {"targets": [{"uuid": "2395a27b-c693-4325-9680-4eadf45b92d9"}]}}}
        snapshot = {"lio": {"storage_objects": []}}
        adapter.cleanup_new_backstores(snapshot, payload)
        def broken_root():
            raise TypeError("cleanup-contract-sentinel")
        with patch.object(rtslib_fb, "RTSRoot", broken_root):
            with self.assertRaisesRegex(TypeError, "cleanup-contract-sentinel"):
                adapter.cleanup_new_backstores(snapshot, payload)


if __name__ == "__main__":
    unittest.main(verbosity=2)
