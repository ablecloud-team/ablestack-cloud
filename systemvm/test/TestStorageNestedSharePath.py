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

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / "debian/usr/local/bin/ablestack-storagectl"
text = SOURCE.read_text()
start = text.index("def resolve_backing_path(")
end = text.index("\nbacking_path = resolve_backing_path", start)
namespace = {"os": os, "run": lambda args: subprocess.run(args, capture_output=True, text=True)}
exec(compile(text[start:end], str(SOURCE), "exec"), namespace)
resolve = namespace["resolve_backing_path"]

class NestedPathTest(unittest.TestCase):
    def test_nested_creation_and_existing_directory(self):
        with tempfile.TemporaryDirectory() as root:
            target = resolve(root, "parent/child", True)
            self.assertTrue(Path(target).is_dir())
            self.assertEqual(target, resolve(root, "parent/child", False))
            Path(target, "keep.txt").write_text("preserved")
            resolve(root, "parent/child", False)
            self.assertEqual("preserved", Path(target, "keep.txt").read_text())

    def test_absolute_traversal_symlink_and_file_are_rejected(self):
        with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as outside:
            Path(root, "link").symlink_to(outside)
            Path(root, "file").write_text("data")
            for path in ("/absolute", "../outside", "parent/../other", "parent/./child", "link/child", "file/child", "missing"):
                with self.subTest(path=path), self.assertRaises(SystemExit):
                    resolve(root, path, False)

    def test_mount_boundary_is_verified_before_directory_creation(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "parent").mkdir()
            original = namespace["run"]
            namespace["run"] = lambda args: subprocess.CompletedProcess(args, 0, "/different" if args[-1].endswith("parent") else "/root", "")
            try:
                with self.assertRaises(SystemExit):
                    resolve(root, "parent/child", True)
                self.assertFalse(Path(root, "parent/child").exists())
            finally:
                namespace["run"] = original

if __name__ == "__main__":
    unittest.main()
