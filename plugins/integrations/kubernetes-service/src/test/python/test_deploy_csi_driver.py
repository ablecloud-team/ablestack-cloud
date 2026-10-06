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

import hashlib
import json
import os
import pathlib
import subprocess
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[2] / "main/resources/script/deploy-csi-driver"


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.image = "ghcr.io/example/ablestack-kubernetes-csi@sha256:" + "a" * 64
        manifest = ""
        for kind, name in [("Deployment", "cloudstack-csi-controller"), ("DaemonSet", "cloudstack-csi-node")]:
            manifest += "---\nkind: " + kind + "\nmetadata:\n  name: " + name + "\n  namespace: kube-system\nspec:\n  template:\n    spec:\n      containers:\n      - name: " + name + "\n        image: " + self.image + "\n"
        (self.root / "manifest.yaml").write_text(manifest)
        (self.root / "snapshot-crds.yaml").write_text("kind: CustomResourceDefinition\n")
        self.profile()
        self.mock = self.root / "kubectl"
        self.mock.write_text("#!/bin/bash\necho \"$*\" >> \"$CALLS\"\ncase \"$*\" in *\"${FAIL_STAGE:-NEVER_MATCH}\"*) exit 17;; esac\nexit 0\n")
        self.mock.chmod(0o700)
        self.env = {**os.environ, "MOLD_CSI_DIR": str(self.root), "MOLD_KUBECTL": str(self.mock), "CALLS": str(self.root / "calls")}

    def tearDown(self):
        self.temp.cleanup()

    def profile(self):
        (self.root / "profile.json").write_text(json.dumps({"schemaVersion": 1, "apiSignature": "HmacSHA256", "driverImage": self.image,
            "files": {name: hashlib.sha256((self.root / name).read_bytes()).hexdigest() for name in ["manifest.yaml", "snapshot-crds.yaml"]}}))

    def run_installer(self, stage=None):
        env = dict(self.env)
        if stage:
            env["FAIL_STAGE"] = stage
        return subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)

    def test_cached_bundle_applies_both_files_and_waits_for_real_workloads(self):
        result = self.run_installer()
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = (self.root / "calls").read_text()
        self.assertIn("snapshot-crds.yaml", calls)
        self.assertIn("manifest.yaml", calls)
        self.assertEqual(calls.count("--for=condition=Established"), 3)
        self.assertIn("rollout status deployment/cloudstack-csi-controller", calls)
        self.assertIn("rollout status daemonset/cloudstack-csi-node", calls)
        self.assertIn("MOLD_CSI_DRIVER_READY", result.stdout)

    def test_partial_or_changed_bundle_fails_before_any_api_mutation(self):
        for name in ["profile.json", "manifest.yaml", "snapshot-crds.yaml"]:
            with self.subTest(file=name):
                path = self.root / name
                data = path.read_bytes()
                path.unlink()
                self.assertNotEqual(self.run_installer().returncode, 0)
                self.assertFalse((self.root / "calls").exists())
                path.write_bytes(data)
        (self.root / "manifest.yaml").write_text("changed")
        self.assertNotEqual(self.run_installer().returncode, 0)
        self.assertFalse((self.root / "calls").exists())

    def test_stock_driver_is_rejected_even_with_matching_file_checksums(self):
        path = self.root / "manifest.yaml"
        path.write_text(path.read_text().replace(self.image, "ghcr.io/cloudstack/cloudstack-csi-driver:main"))
        self.profile()
        self.assertNotEqual(self.run_installer().returncode, 0)
        self.assertFalse((self.root / "calls").exists())

    def test_api_apply_crd_and_rollout_failures_never_report_success(self):
        for stage in ["snapshot-crds.yaml", "--for=condition=Established", "manifest.yaml", "rollout status deployment", "rollout status daemonset"]:
            with self.subTest(stage=stage):
                result = self.run_installer(stage)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("MOLD_CSI_DRIVER_READY", result.stdout)


if __name__ == "__main__":
    unittest.main()
