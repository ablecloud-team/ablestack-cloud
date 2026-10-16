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

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "systemvm/debian/usr/local/lib/ablestack-storage"))
import boot_reconcile as module


class StorageBootMonitorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        cli = self.root / "storagectl"
        cli.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
with Path(os.environ["CASE_CALLS"]).open("a")as out:out.write(" ".join(sys.argv[1:])+"\\n")
print(json.dumps({"success":True,"status":"ok","bootHeld":False,"password":"discard-public-fixture",
                  "listenPorts":{"nfs":True},"services":{"nfs":"active"}}))
''')
        cli.chmod(0o700)
        network = self.root / "network-helper"
        network.write_text('''#!/usr/bin/env python3
import json
from pathlib import Path
print(json.dumps({"schemaVersion":1,"kind":"SHAREDFS_NETWORK_READINESS","success":True,"status":"OK",
"mode":"DHCP","observedEpoch":1,"bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
"expected":{"mode":"DHCP","dns":[]},"actual":{},"mismatches":[]}))
''')
        network.chmod(0o700)
        self.environment = dict(os.environ, ABLESTACK_STORAGECTL=str(cli),
                                ABLESTACK_SHAREDFS_NETWORK_HELPER=str(network),
                                ABLESTACK_STORAGE_MONITOR_CACHE_DIR=str(self.root / "cache"),
                                ABLESTACK_STORAGE_BOOT_PROGRESS_JOURNAL=str(self.root / "progress/status.json"),
                                CASE_CALLS=str(self.root / "calls"))
        self.c = module.BootCoordinator(
            "a" * 64, {"probeTasks": 1, "endpointModeGroups": 1, "configuredProtocolCount": 1, "concurrency": 1},
            boot_id=self.boot, journal=self.root / "progress/status.json",
            checkpoint_dir=self.root / "receipts", configured_protocols=("NFS",))

    def collect(self, readonly=False):
        before = self.c.journal.read_bytes()
        args = ["bash", str(ROOT / "systemvm/debian/usr/local/bin/ablestack-storage-monitor")]
        if readonly:
            args.append("--boot-readonly")
        result = subprocess.run(args + ["--once"], env=self.environment,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(before, self.c.journal.read_bytes())
        return json.loads((self.root / "cache/health.json").read_text())

    def test_active_boot_progress_and_listener_ready_are_visible_without_monitor_writer(self):
        self.c.begin_attempt(1)
        self.c.report_protocol("NFS", "PROBE_EXPORTS",
                               {"success": True, "runtimeReady": True, "detailVerified": False,
                                "probePending": 1, "completedProbeCount": 0, "totalProbeCount": 1,
                                "skippedAclCount": 0, "detailVerificationKind": "UNVERIFIED"})
        value = self.collect()
        self.assertEqual("RECONCILING", value["bootReconcile"]["status"])
        self.assertTrue(value["bootReconcile"]["runtimeReady"])
        self.assertFalse(value["bootReconcile"]["detailedVerified"])
        self.assertEqual("reconciling", value["status"])
        self.assertEqual(self.boot, value["bootId"])
        self.assertNotIn("password", value)
        self.assertNotIn("network endpoints reconcile", (self.root / "calls").read_text())

    def test_terminal_old_phase_time_is_preserved_but_health_cache_is_fresh(self):
        self.c.hold("APPROVED_MAINTENANCE_HELD")
        record = json.loads(self.c.journal.read_text())
        record["updatedEpoch"] = 1.0
        module.boot_write_json(self.c.journal, record)
        value = self.collect(readonly=True)
        self.assertEqual("HELD", value["bootReconcile"]["status"])
        self.assertEqual(1.0, value["bootReconcile"]["updatedEpoch"])
        self.assertGreater(time.time() - value["generatedEpoch"], -1)
        self.assertLess(time.time() - value["generatedEpoch"], 5)
        self.assertNotIn("network endpoints reconcile", (self.root / "calls").read_text())

    def test_stale_or_foreign_active_progress_is_unknown_and_never_starts_endpoint_writer(self):
        record = json.loads(self.c.journal.read_text())
        record["updatedEpoch"] = 1.0
        module.boot_write_json(self.c.journal, record)
        value = self.collect()
        self.assertNotIn("bootReconcile", value)
        self.assertTrue(value["bootReconcileReadError"])
        self.assertNotIn("network endpoints reconcile", (self.root / "calls").read_text())

    def test_unit_order_preserves_network_dependency_but_monitor_not_delayed_by_reconcile(self):
        units = ROOT / "systemvm/debian/etc/systemd/system"
        reconcile = (units / "ablestack-storage-reconcile.service").read_text()
        monitor = (units / "ablestack-storage-monitor.service").read_text()
        self.assertIn("TimeoutStartSec=1800", reconcile)
        self.assertNotIn("Before=ablestack-storage-monitor.service", reconcile)
        self.assertNotIn("ablestack-storage-reconcile.service", monitor)
        self.assertIn("ablestack-sharedfs-network.service", reconcile)
        self.assertIn("ablestack-sharedfs-network.service", monitor)


if __name__ == "__main__":
    unittest.main()
