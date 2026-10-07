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
import importlib.util
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

spec = importlib.util.spec_from_file_location("preflight", Path(__file__).with_name("preflight-cks-config.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class TemplatePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.jar = self.root / "candidate.jar"
        self.conf = self.root / "conf"
        self.conf.mkdir()
        with zipfile.ZipFile(self.jar, "w") as archive:
            for name in module.REQUIRED:
                data = (name + "\n").encode()
                archive.writestr("conf/" + name, data)
                (self.conf / name).write_bytes(data)

    def test_same_packaged_templates_pass(self):
        self.assertEqual("PASS", module.inspect(self.jar, self.conf)["status"])

    def test_old_control_template_fails_without_writing_it(self):
        path = self.conf / "k8s-control-node.yml"
        path.write_bytes(b"old template")
        report = module.inspect(self.jar, self.conf)
        self.assertEqual("FAIL", report["status"])
        self.assertEqual("DRIFT", report["files"][0]["status"])
        self.assertEqual(b"old template", path.read_bytes())

    def test_missing_worker_template_fails(self):
        (self.conf / "k8s-node.yml").unlink()
        report = module.inspect(self.jar, self.conf)
        self.assertEqual("MISSING", report["files"][2]["status"])

    def test_incomplete_candidate_rejected(self):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("conf/k8s-control-node.yml", b"one")
        with self.assertRaises(ValueError):
            module.inspect(self.jar, self.conf)

    def test_ambiguous_candidate_rejected(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.jar, "a") as archive:
                archive.writestr("conf/k8s-control-node.yml", b"replacement")
        with self.assertRaises(ValueError):
            module.inspect(self.jar, self.conf)


if __name__ == "__main__":
    unittest.main()
