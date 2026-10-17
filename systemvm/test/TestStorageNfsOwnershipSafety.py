#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import ast
import os
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
CLI = ROOT / "systemvm/debian/usr/local/bin/ablestack-storagectl"

class StorageNfsOwnershipSafetyTest(unittest.TestCase):
    def setUp(self):
        source = CLI.read_text()
        start = source.index('def apply_posix_permissions(')
        end = source.index('def load_alias_state():', start)
        self.ns = {"os": os, "verify_common_posix_policy": lambda p, c: False,
                   "int_config": lambda c, k: c.get(k), "mode_config": lambda c: int(c['mode'], 8),
                   "truth": lambda c, k, d: c.get(k, d)}
        exec(compile(ast.parse(source[start:end]), str(CLI), 'exec'), self.ns)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'share'
        self.path.mkdir(mode=0o750)
        self.child = self.path / 'existing'
        self.child.write_text('untouched')
        self.child.chmod(0o640)
        self.config = {'ownerUid': os.geteuid(), 'ownerGid': os.getegid(), 'mode': '0777'}

    def creation_identity(self):
        value = self.path.stat()
        return {"device": value.st_dev, "inode": value.st_ino}

    def test_existing_share_owner_mode_and_child_data_remain_unchanged(self):
        before = self.path.stat()
        child = self.child.stat()
        self.ns['apply_posix_permissions'](str(self.path), self.config)
        self.assertEqual(before, self.path.stat())
        self.assertEqual(child, self.child.stat())
        self.assertEqual('untouched', self.child.read_text())

    def test_new_directory_initialization_changes_only_the_new_directory(self):
        child = self.child.stat()
        self.ns['apply_posix_permissions'](str(self.path), self.config, self.creation_identity())
        self.assertEqual(0o777, self.path.stat().st_mode & 0o777)
        self.assertEqual(child, self.child.stat())

    def test_new_directory_recursive_request_is_rejected_without_mutation(self):
        before = self.path.stat()
        with self.assertRaises(ValueError):
            self.ns['apply_posix_permissions'](str(self.path), dict(self.config, recursivePermission=True), self.creation_identity())
        self.assertEqual(before, self.path.stat())

    def test_replaced_created_directory_is_rejected_before_owner_or_mode_changes(self):
        expected = self.creation_identity()
        moved = self.path.with_name('original')
        self.path.rename(moved)
        self.path.mkdir(mode=0o700)
        replacement = self.path.stat()
        with self.assertRaises(RuntimeError):
            self.ns['apply_posix_permissions'](str(self.path), self.config, expected)
        self.assertEqual(replacement, self.path.stat())
        self.assertEqual('untouched', (moved / 'existing').read_text())

    def test_symlink_new_directory_initialization_is_rejected(self):
        link = Path(self.temp.name) / 'alias'
        link.symlink_to(self.path, target_is_directory=True)
        before = self.path.stat()
        with self.assertRaises(OSError):
            self.ns['apply_posix_permissions'](str(link), self.config, self.creation_identity())
        self.assertEqual(before, self.path.stat())

class StorageNfsFilesystemRootTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "volume"
        self.root.mkdir(mode=0o750)
        source = CLI.read_text()
        start = source.index("def verify_share_mount_boundary(path, root):")
        end = source.index("def ensure_export_alias(export):", start)
        self.ns = {"os": os, "run": lambda args: SimpleNamespace(returncode=0, stdout=str(self.root) + "\n")}
        exec(compile(ast.parse(source[start:end]), str(CLI), "exec"), self.ns)

    def test_existing_filesystem_root_is_accepted_without_permission_initialization(self):
        before = self.root.stat()
        self.ns["verify_share_mount_boundary"](str(self.root), str(self.root))
        self.assertIsNone(self.ns["ensure_directory_below_volume"](str(self.root), str(self.root), True))
        self.assertEqual(before, self.root.stat())

    def test_only_a_new_leaf_returns_an_initialization_inode(self):
        leaf = self.root / "new"
        value = self.ns["ensure_directory_below_volume"](str(self.root), str(leaf), True)
        self.assertEqual({"device": leaf.stat().st_dev, "inode": leaf.stat().st_ino}, value)
        self.assertIsNone(self.ns["ensure_directory_below_volume"](str(self.root), str(leaf), True))

    def test_symlink_root_and_outside_path_are_rejected(self):
        alias = Path(self.temp.name) / "alias"
        alias.symlink_to(self.root, target_is_directory=True)
        for helper in ("verify_share_mount_boundary", "ensure_directory_below_volume"):
            with self.assertRaises((ValueError, RuntimeError)):
                if helper == "ensure_directory_below_volume":
                    self.ns[helper](str(alias), str(alias), True)
                else:
                    self.ns[helper](str(alias), str(alias))
        with self.assertRaises(RuntimeError):
            self.ns["verify_share_mount_boundary"](self.temp.name, str(self.root))

    def test_filesystem_root_requires_an_exact_mount(self):
        self.ns["run"] = lambda args: SimpleNamespace(returncode=0, stdout=self.temp.name + "\n")
        before = self.root.stat()
        with self.assertRaises(RuntimeError):
            self.ns["verify_share_mount_boundary"](str(self.root), str(self.root))
        with self.assertRaises(RuntimeError):
            self.ns["ensure_directory_below_volume"](str(self.root), str(self.root), True)
        self.assertEqual(before, self.root.stat())

if __name__ == '__main__':
    unittest.main()
