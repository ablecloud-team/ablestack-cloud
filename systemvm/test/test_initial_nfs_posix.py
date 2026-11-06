#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file for details.
# The ASF licenses this file under the Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
# Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS.
"""Execute the actual guest POSIX functions against isolated real directories."""
import ast
import hashlib
import os
from pathlib import Path
import re
import stat
import tempfile
import unittest

source = Path(os.environ.get("STORAGECTL_UNDER_TEST", Path(__file__).resolve().parents[1] / "debian/usr/local/bin/ablestack-storagectl")).read_text()
block = next(p.split("\nPY\n", 1)[0] for p in source.split("<<'PY'\n")[1:] if "def effective_nfs_export_config(" in p.split("\nPY\n", 1)[0])
names = {"truth", "int_config", "mode_config", "effective_nfs_export_config", "apply_posix_permissions"}
tree = ast.parse(block)
functions = [x for x in tree.body if isinstance(x, ast.FunctionDef) and x.name in names]
assert {x.name for x in functions} == names
namespace = {"os": os, "re": re}
exec(compile(ast.Module(body=functions, type_ignores=[]), "actual-guest-nfs-functions", "exec"), namespace)

class InitialNfsPosixTest(unittest.TestCase):
    def setUp(self):
        if os.geteuid() != 0:
            self.skipTest("Real chown regression requires an isolated root test runner")
        self.temp = tempfile.TemporaryDirectory(prefix="sharedfs1442-posix-")
        self.directory = Path(self.temp.name) / "export"
        self.directory.mkdir()
        self.file = self.directory / "preserve.txt"
        self.file.write_bytes(b"original-permission-fixture\n")
        for p in (self.directory, self.file):
            os.chown(p, 1234, 2345)
        self.directory.chmod(0o750)
        self.file.chmod(0o640)
        self.before = hashlib.sha256(self.file.read_bytes()).hexdigest()
    def tearDown(self):
        if hasattr(self, "temp"):
            self.temp.cleanup()
    def metadata(self, p):
        s = p.stat()
        return s.st_uid, s.st_gid, stat.S_IMODE(s.st_mode)
    def apply(self, config):
        effective = namespace["effective_nfs_export_config"](config)
        namespace["apply_posix_permissions"](str(self.directory), effective)
        self.assertEqual(self.before, hashlib.sha256(self.file.read_bytes()).hexdigest())
        return effective
    def test_initial_import_preserves_real_directory_and_file(self):
        c = self.apply({"readOnly": False, "rootSquash": True, "posixPolicy": "PRESERVE_EXISTING_INITIAL"})
        self.assertEqual((1234, 2345, 0o750), self.metadata(self.directory))
        self.assertEqual((1234, 2345, 0o640), self.metadata(self.file))
        self.assertEqual(65534, c["anonUid"])
        self.assertNotIn("ownerUid", c)
        self.assertNotIn("mode", c)
    def test_new_or_later_share_keeps_legacy_initialization(self):
        self.apply({"readOnly": False, "rootSquash": True})
        self.assertEqual((65534, 65534, 0o775), self.metadata(self.directory))
        self.assertEqual((1234, 2345, 0o640), self.metadata(self.file))
    def test_explicit_values_are_not_silently_ignored(self):
        self.apply({"rootSquash": True, "posixPolicy": "PRESERVE_EXISTING_INITIAL", "ownerUid": 1666, "ownerGid": 2777, "mode": "0770"})
        self.assertEqual((1666, 2777, 0o770), self.metadata(self.directory))
    def test_read_only_legacy_does_not_change_permissions(self):
        self.apply({"readOnly": True, "rootSquash": True})
        self.assertEqual((1234, 2345, 0o750), self.metadata(self.directory))

if __name__ == "__main__":
    unittest.main(verbosity=2)
