# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Exercise the executable template script, including masked command failures."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/storage/qcow2/createtmplt.sh"
QEMU = """#!/usr/bin/env python3
import os, pathlib, shutil, sys
args = sys.argv[1:]
if args[0] == 'info':
    print('file format: ' + os.getenv('FORMAT', 'qcow2'))
    if os.getenv('BACKING'): print('backing file: base.qcow2')
elif args[0] == 'convert':
    pathlib.Path(args[-1]).write_bytes(b'partial')
    if os.getenv('FAIL') == 'convert': sys.exit(1)
    shutil.copyfile(args[-2], args[-1])
elif args[0] == 'compare':
    sys.exit(pathlib.Path(args[-2]).read_bytes() != pathlib.Path(args[-1]).read_bytes())
"""
COPY = """#!/usr/bin/env python3
import os, pathlib, shutil, sys
if os.getenv('FAIL') in ('copy', 'corrupt'):
    pathlib.Path(sys.argv[-1]).write_bytes(b'partial')
    sys.exit(1 if os.getenv('FAIL') == 'copy' else 0)
shutil.copyfile(sys.argv[-2], sys.argv[-1])
"""


class CreateTemplateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="template-script-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.source = self.root / "source image"
        self.source.write_bytes(b"head" + bytes(4096) + b"tail")
        self.output = self.root / "output directory"
        self.output.mkdir()
        self.target = self.output / "template.qcow2"
        self.properties = self.output / "template.properties"
        for name, text in (("cloud-qemu-img", QEMU), ("cp", COPY)):
            path = self.bin / name
            path.write_text(text)
            path.chmod(0o755)

    def run_script(self, fail=None, image_format="qcow2", backing=False,
                   cleanup=False, snapshot=False):
        env = dict(os.environ, PATH=str(self.bin) + ":" + os.environ["PATH"],
                   FORMAT=image_format, FAIL=fail or "", BACKING="1" if backing else "")
        args = ["bash", str(SCRIPT), "-f", str(self.source), "-t", str(self.output),
                "-n", self.target.name]
        if cleanup:
            args.append("-u")
        if snapshot:
            args += ["-s", "-c", "snapshot"]
        return subprocess.run(args, env=env, cwd=self.root, capture_output=True)

    def assert_failure(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(self.source.exists(), "Failure must preserve the source")
        self.assertFalse(self.target.exists(), "Partial image must be removed")
        self.assertFalse(self.properties.is_file(), "No success metadata on failure")

    def test_copy_failure_is_not_masked_by_chmod_and_preserves_cleanup_source(self):
        self.assert_failure(self.run_script(fail="copy", cleanup=True))

    def test_raw_conversion_failure_removes_partial_image(self):
        self.assert_failure(self.run_script(fail="convert", image_format="raw", cleanup=True))

    def test_backing_conversion_failure_removes_partial_image(self):
        self.assert_failure(self.run_script(fail="convert", backing=True))

    def test_snapshot_conversion_failure_removes_partial_image(self):
        self.assert_failure(self.run_script(fail="convert", snapshot=True))

    def test_copy_success_with_wrong_content_is_rejected(self):
        self.assert_failure(self.run_script(fail="corrupt", cleanup=True))

    def test_properties_write_failure_preserves_source(self):
        self.properties.mkdir()
        self.assert_failure(self.run_script(cleanup=True))

    def test_chmod_failure_preserves_source(self):
        path = self.bin / "chmod"
        path.write_text("#!/bin/sh\nexit 1\n")
        path.chmod(0o755)
        self.assert_failure(self.run_script(cleanup=True))

    def test_qcow2_copy_preserves_bytes_and_publishes_properties(self):
        self.assertEqual(self.run_script().returncode, 0)
        self.assertEqual(self.source.read_bytes(), self.target.read_bytes())
        self.assertIn("filename=template.qcow2", self.properties.read_text())

    def test_raw_regular_file_is_converted(self):
        self.assertEqual(self.run_script(image_format="raw").returncode, 0)
        self.assertEqual(self.source.read_bytes(), self.target.read_bytes())

    def test_backing_conversion_succeeds(self):
        self.assertEqual(self.run_script(backing=True).returncode, 0)

    def test_snapshot_conversion_succeeds(self):
        self.assertEqual(self.run_script(snapshot=True).returncode, 0)

    def test_cleanup_only_removes_source_after_complete_success(self):
        self.assertEqual(self.run_script(cleanup=True).returncode, 0)
        self.assertFalse(self.source.exists())
        self.assertTrue(self.target.exists())
        self.assertTrue(self.properties.is_file())


if __name__ == "__main__":
    unittest.main()
