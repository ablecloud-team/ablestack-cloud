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
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
CONF = ROOT / "src/main/resources/conf"


class IsoContainerdReadinessTest(unittest.TestCase):
    def run_guard(self, name, failures):
        text = (CONF / name).read_text()
        guard = text.split("        systemctl enable --now containerd\n", 1)[1].split("        # Preserve archive digests;", 1)[0]
        guard = "systemctl enable --now containerd\n" + "\n".join(x[8:] if x.startswith("        ") else x for x in guard.splitlines())
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            scripts = {
                "systemctl": "echo SERVICE >> \"$TRACE\"\n",
                "sleep": "echo WAIT >> \"$TRACE\"\n",
                "ctr": "n=$(cat \"$COUNT\" 2>/dev/null || echo 0); n=$((n+1)); echo $n > \"$COUNT\"; echo CRI_$n >> \"$TRACE\"; [ $n -gt \"$FAILURES\" ]\n",
            }
            for command, script in scripts.items():
                target = path / command
                target.write_text("#!/bin/bash\n" + script)
                target.chmod(0o755)
            env = dict(os.environ, PATH=str(path) + ":" + os.environ["PATH"], TRACE=str(path / "trace"), COUNT=str(path / "count"), FAILURES=str(failures))
            result = subprocess.run(["bash", "-ec", guard + "\necho IMPORT >> \"$TRACE\""], env=env, capture_output=True, text=True)
            return result, (path / "trace").read_text().splitlines()

    def test_delayed_socket_blocks_import_until_ready_for_every_node_role(self):
        for name in ("k8s-node.yml", "k8s-control-node.yml", "k8s-control-node-add.yml"):
            with self.subTest(name=name):
                result, trace = self.run_guard(name, 2)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["SERVICE", "CRI_1", "WAIT", "CRI_2", "WAIT", "CRI_3", "IMPORT"], trace)

    def test_socket_timeout_prevents_import_for_every_node_role(self):
        for name in ("k8s-node.yml", "k8s-control-node.yml", "k8s-control-node-add.yml"):
            with self.subTest(name=name):
                result, trace = self.run_guard(name, 100)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("IMPORT", trace)
                self.assertEqual(30, trace.count("WAIT"))
                self.assertIn("did not become ready", result.stderr)

    def test_failed_setup_stops_cloud_init_before_deployment(self):
        for name in ("k8s-node.yml", "k8s-control-node.yml", "k8s-control-node-add.yml"):
            text = (CONF / name).read_text()
            setup = next(x[4:] for x in text.splitlines() if x.startswith("  - /opt/bin/setup-kube-system"))
            result = subprocess.run(["bash", "-c", setup.replace("/opt/bin/setup-kube-system", "false") + "\necho DEPLOY"], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("DEPLOY", result.stdout)


if __name__ == "__main__":
    unittest.main()
