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
import pathlib
import shlex
import subprocess
import tempfile
import time
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[2] / "main/resources/script/upgrade-kubernetes.sh"

class UpgradeApiRecoveryTest(unittest.TestCase):
    def run_gate(self, mode, budget):
        source = SOURCE.read_text()
        start = source.index("wait_for_upgrade_api() {")
        end = source.index("\n}\n", start) + 3
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            kubectl = root / "kubectl"
            kubectl.write_text("#!/bin/bash\nn=0; [ ! -e \"$CASE_DIR/count\" ] || n=$(cat \"$CASE_DIR/count\"); n=$((n+1)); echo $n > \"$CASE_DIR/count\"\nif [ \"$CASE_MODE\" = never ] || [ $n -le 2 ]; then exit 1; fi\necho ok\n")
            kubectl.chmod(0o700)
            function = source[start:end].replace("/opt/bin/kubectl", shlex.quote(str(kubectl)))
            begin = time.monotonic()
            result = subprocess.run(["bash", "-c", function + "\nwait_for_upgrade_api " + str(budget) + " && echo APPLY_REACHED"],
                                    env={**os.environ, "CASE_DIR": tmp, "CASE_MODE": mode}, capture_output=True, text=True, timeout=10)
            count = int((root / "count").read_text()) if (root / "count").exists() else 0
            return result, count, time.monotonic() - begin

    def test_transient_api_failure_requires_three_consecutive_successes(self):
        result, count, _ = self.run_gate("transient", 7)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(count, 5)
        self.assertIn("MOLD_UPGRADE_API_READY", result.stdout)
        self.assertIn("APPLY_REACHED", result.stdout)

    def test_unready_api_has_bounded_deadline_and_never_applies_payload(self):
        result, count, elapsed = self.run_gate("never", 2)
        self.assertEqual(result.returncode, 1)
        self.assertGreater(count, 0)
        self.assertLess(elapsed, 4)
        self.assertNotIn("APPLY_REACHED", result.stdout)
        self.assertIn("readiness deadline", result.stderr)

    def test_invalid_timeout_cannot_extend_deadline(self):
        for budget in (0, 121):
            result, count, _ = self.run_gate("transient", budget)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(count, 0)

if __name__ == "__main__":
    unittest.main()
