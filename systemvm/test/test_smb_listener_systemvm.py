# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file distributed
# with this work for additional information regarding copyright ownership.
# The ASF licenses this file under the Apache License, Version 2.0
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS.
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.environ.get("RUN_SYSTEMVM_SMB_LISTENER_TESTS") == "1",
                     "explicit root/SystemVM native integration opt-in")
class SmbListenerSystemVMTests(unittest.TestCase):
    def test_native_systemd_sessions_locking_rollback_and_boot(self):
        self.assertEqual(0, os.geteuid())
        module = Path(os.environ.get("CONFIGURATION_MODULE", Path(__file__).resolve().parents[1] /
                      "debian/usr/local/lib/ablestack-storage/configuration_transaction.py"))
        for binary in ("systemd-run", "smbd", "smbclient", "smbpasswd", "nsenter", "unshare"):
            self.assertIsNotNone(shutil.which(binary), binary)
        with tempfile.TemporaryDirectory(prefix="smb-native-driver-") as name:
            root = Path(name)
            shutil.copyfile(module, root / "configuration_transaction.py")
            fixtures = Path(__file__).parent / "fixtures"
            for file in ("smb_listener_native_probe.py", "smb_listener_lock_probe.py"):
                shutil.copyfile(fixtures / file, root / file)
            run = subprocess.run(["python3", str(root / "smb_listener_native_probe.py")],
                                 capture_output=True, text=True, timeout=180)
            self.assertEqual(0, run.returncode, run.stderr)
            result = json.loads((root / "native-controller-result.json").read_text())
            self.assertTrue(result.get("ok"), result)
            self.assertTrue(result["locking"]["ok"])
            self.assertTrue(result["protectedStateAfterCleanupUnchanged"])
            self.assertEqual(10, len(result["checks"]))


if __name__ == "__main__":
    unittest.main()
