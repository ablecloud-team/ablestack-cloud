# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Opt-in real kernel configfs tests; no port, namespace or DATA is created."""
import copy
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

MODULE = Path(os.environ.get("CONFIGURATION_MODULE", Path(__file__).resolve().parents[1] /
              "debian/usr/local/lib/ablestack-storage/configuration_transaction.py"))
spec = importlib.util.spec_from_file_location("configfs_transaction_test", MODULE)
configuration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configuration)


@unittest.skipUnless(os.environ.get("CONFIGURATION_CONFIGFS_NATIVE") == "1", "Requires a disposable SystemVM")
class ConfigfsLinkTests(unittest.TestCase):
    def setUp(self):
        self.base = Path("/sys/kernel/config/nvmet")
        self.name = "nqn.2026-10.local.storage:cfg1444-test-" + str(uuid.uuid4())
        self.host = self.base / "hosts" / self.name
        self.subsystem = self.base / "subsystems" / self.name
        self.host.mkdir()
        self.subsystem.mkdir()
        self.link = self.subsystem / "allowed_hosts" / self.name
        self.link.symlink_to(str(self.host))
        self.temporary = tempfile.TemporaryDirectory(prefix="cfg1444-native-")
        self.adapter = configuration.RuntimeAdapter(Path(self.temporary.name))
        value = {"subsystems": [{"targetName": self.name, "hosts": [{"principal": self.name}]}]}
        payload = {"before": {"nvmeof subsystem apply": value}, "candidate": {"nvmeof subsystem apply": value}}
        self.trees = self.adapter.kernel_snapshot(payload)
        self.cwd = os.getcwd()

    def tearDown(self):
        os.chdir(self.cwd)
        assert self.name.startswith("nqn.2026-10.local.storage:cfg1444-test-")
        if self.link.is_symlink():
            self.link.unlink()
        if self.subsystem.exists():
            self.subsystem.rmdir()
        if self.host.exists():
            self.host.rmdir()
        self.temporary.cleanup()

    def test_actual_configfs_relative_before_image_restores_from_root_and_tmp(self):
        saved = self.trees[str(self.subsystem)]["links"]["allowed_hosts/" + self.name]
        self.assertFalse(Path(saved).is_absolute())
        for cwd in ("/", "/tmp"):
            with self.subTest(cwd=cwd):
                self.link.unlink()
                os.chdir(cwd)
                self.adapter.restore_kernel(self.trees)
                self.assertEqual(str(self.host), os.path.realpath(self.link))

    def test_matching_link_is_not_removed_or_recreated(self):
        before = self.link.lstat().st_ino
        with patch.object(Path, "symlink_to", side_effect=AssertionError("unchanged link must not be recreated")):
            self.adapter.restore_kernel(self.trees)
        self.assertEqual(before, self.link.lstat().st_ino)

    def test_target_escape_is_rejected_before_kernel_mutation(self):
        malformed = copy.deepcopy(self.trees)
        malformed[str(self.subsystem)]["links"]["allowed_hosts/" + self.name] = "/etc"
        before = self.link.lstat().st_ino
        with self.assertRaisesRegex(configuration.Rejected, "KERNEL_LINK_TARGET_OUTSIDE_SCOPE"):
            self.adapter.restore_kernel(malformed)
        self.assertEqual(before, self.link.lstat().st_ino)

    def test_missing_owned_target_is_recreated_before_legacy_link(self):
        self.link.unlink()
        self.host.rmdir()
        os.chdir("/")
        self.adapter.restore_kernel(self.trees)
        self.assertEqual(str(self.host), os.path.realpath(self.link))


if __name__ == "__main__":
    unittest.main(verbosity=2)
