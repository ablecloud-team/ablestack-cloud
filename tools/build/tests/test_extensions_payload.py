#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

import importlib.util
import os
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "extensions_payload", ROOT / "tools/build/verify_extensions_payload.py"
)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


@unittest.skipUnless(os.name == "posix", "RPM permissions require a POSIX filesystem")
class ExtensionsPayloadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.payload = Path(self.temp.name)
        self.directory = self.payload / "etc/cloudstack/extensions"
        self.directory.mkdir(parents=True)
        self.directory.chmod(0o755)
        for relative in CHECKER.REQUIRED:
            target = self.directory / relative
            target.parent.mkdir(exist_ok=True)
            target.parent.chmod(0o755)
            shutil.copyfile(ROOT / "extensions" / relative, target)
            target.chmod(0o755)
        self.link = self.payload / "usr/share/cloudstack-management/extensions"
        self.link.parent.mkdir(parents=True)
        self.link.symlink_to("/etc/cloudstack/extensions")
        self.properties = self.payload / "etc/cloudstack/management/server.properties"
        self.properties.parent.mkdir()
        self.properties.write_text("extensions.deployment.mode=production\n")

    def findings(self):
        return CHECKER.verify_payload(ROOT, self.payload)

    def test_complete_payload(self):
        self.assertEqual([], self.findings())

    def test_missing_each_extension(self):
        for relative in CHECKER.REQUIRED:
            with self.subTest(extension=relative):
                target = self.directory / relative
                original = target.read_bytes()
                target.unlink()
                self.assertIn("Missing extension file: " + relative, self.findings())
                target.write_bytes(original)
                target.chmod(0o755)

    def test_missing_link(self):
        self.link.unlink()
        self.assertTrue(any("symlink" in x for x in self.findings()))

    def test_wrong_link(self):
        self.link.unlink()
        self.link.symlink_to("/tmp/extensions")
        self.assertTrue(any("symlink" in x for x in self.findings()))

    def test_modified_script(self):
        (self.directory / CHECKER.REQUIRED[0]).write_text("modified")
        self.assertTrue(any("differs from source" in x for x in self.findings()))

    def test_script_without_execute_permission(self):
        (self.directory / CHECKER.REQUIRED[0]).chmod(0o644)
        self.assertTrue(any("mode 0755" in x for x in self.findings()))

    def test_untraversable_directory(self):
        (self.directory / "HyperV").chmod(0o700)
        self.assertTrue(any("directory must have mode" in x for x in self.findings()))

    def test_nonproduction_modes(self):
        for mode in ("developer", "@EXTENSIONSDEPLOYMENTMODE@", ""):
            with self.subTest(mode=mode):
                self.properties.write_text("extensions.deployment.mode=" + mode + "\n")
                self.assertTrue(any("must be production" in x for x in self.findings()))

    def test_rocky_spec_installs_and_owns_extensions(self):
        spec = (ROOT / "packaging/centos8/cloud.spec").read_text()
        install = spec.split("%install", 1)[1].split("%clean", 1)[0]
        management = spec.split("%files management", 1)[1].split("%files agent", 1)[0]
        self.assertIn("cp -r extensions/*", install)
        self.assertIn("ln -sf %{_sysconfdir}/%{name}/extensions", install)
        self.assertIn("%{_datadir}/%{name}-management/extensions", management)
        self.assertIn("%dir %attr(0755,cloud,cloud) %{_sysconfdir}/%{name}/extensions", management)
        self.assertIn("%attr(0755,cloud,cloud) %{_sysconfdir}/%{name}/extensions/*", management)

    def test_both_rocky_builds_check_payload(self):
        for release in ("97", "98"):
            script = (ROOT / ("tools/build/rocky" + release + "-rpm-build.sh")).read_text()
            self.assertIn('verify_extensions_payload.py" "$ROOT_DIR" "$extract_dir"', script)


if __name__ == "__main__":
    unittest.main()
