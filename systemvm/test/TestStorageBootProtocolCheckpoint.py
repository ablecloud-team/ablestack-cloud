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
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BOOT = ROOT / "systemvm/debian/usr/local/bin/ablestack-storage-boot-reconcile"


class StorageBootProtocolCheckpointTest(unittest.TestCase):
    def fixture(self, root):
        config = root / "config"
        desired = config / "desired-state"
        desired.mkdir(parents=True)
        for name, rows in (("nfs-export-apply", "exports"), ("smb-share-apply", "shares")):
            path = desired / (name + ".json")
            path.write_text(json.dumps({"enabled": True, rows: [{"uuid": "public-fixture", "state": "Ready"}]}))
            path.chmod(0o600)
        stub = root / "storagectl"
        stub.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:];call=" ".join(args[:3]);root=Path(os.environ["CASE_ROOT"])
with(root/"calls").open("a")as out:out.write(call+"\\n")
if os.environ.get("CHECK_BOOT_FD9")=="1":
 import fcntl
 opened=os.fstat(9);named=Path(os.environ["ABLESTACK_STORAGE_WRITER_LOCK_FILE"]).stat()
 assert(opened.st_dev,opened.st_ino)==(named.st_dev,named.st_ino)
 fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
def probe(pending=False):
 return {"success":True,"runtimeReady":True,"detailVerified":not pending,"probePending":int(pending),
         "completedProbeCount":0 if pending else 1,"totalProbeCount":1,"skippedAclCount":0,
         "detailVerificationKind":"UNVERIFIED"if pending else"MOUNT_PROBES_COMPLETE",
         "lastFailure":{"protocol":"NFS","phase":"PROBE_EXPORTS","endpointIp":None,"endpointPort":2049,
         "exportUuid":None,"exportName":"test","protocolMode":"NFS4","code":"NFS_PROBE_TIMEOUT",
         "timedOut":True,"retryable":True,"cleanup":{"complete":True,"childActive":False,"mountPresent":False}}if pending else None}
if args[:3]==["nfs","export","apply"]:
 barrier=os.environ.get("NFS_WAIT_MARKER")
 if barrier:
  import time
  Path(barrier+".ready").write_text("READY")
  until=time.monotonic()+5
  while Path(barrier).exists()and time.monotonic()<until:time.sleep(.01)
 count=sum(x=="nfs export apply"for x in(root/"calls").read_text().splitlines())
 result=probe(count==1 and os.environ.get("FIRST_NFS_PENDING","1")=="1")
 if os.environ.get("CLEANUP_UNCERTAIN")=="1":
  result=probe(True);result["success"]=False
  result["lastFailure"]["cleanup"]={"complete":False,"childActive":False,"mountPresent":False}
elif args[:3]==["nfs","export","boot-verify"]:
 count=sum(x=="nfs export apply"for x in(root/"calls").read_text().splitlines())
 result=probe(count<2 and os.environ.get("FIRST_NFS_PENDING","1")=="1")
elif args[:2]==["operation","verify"]:
 result={"success":True,"nfsGanesha":{"status":"ok","endpoints":[{"processRunning":True,"listening":True}]},
         "smbRuntime":{"available":True,"runtimeEndpoints":[{"listenerOwned":os.environ.get("SMB_OWNED","1")=="1",
                                                          "tcpReady":os.environ.get("SMB_TCP","1")=="1"}]},
         "listenPorts":{"nfs":True,"smb":True}}
elif args[:3]==["operation","generation","render-status"]:
 result={"success":True,"bootHeld":False,"current":{"exactFixture":True}if os.environ.get("RENDERED_FIXTURE")=="1"else None}
elif args[:3]==["operation","generation","render-boot"]:
 import importlib.util
 spec=importlib.util.spec_from_file_location("fixture_coordinator",os.environ["BOOT_COORDINATOR_MODULE"])
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 c=module.BootCoordinator.attach_existing()
 assert c is not None and c.record["status"]=="RECONCILING"
 for domain,row in c.record["protocols"].items():
  if row["configured"]:
   report={"success":True,"runtimeReady":True,"detailVerified":True,"probePending":0,
           "completedProbeCount":1 if domain=="NFS"else 0,"totalProbeCount":1 if domain=="NFS"else 0,
           "skippedAclCount":0,"detailVerificationKind":"MOUNT_PROBES_COMPLETE"if domain=="NFS"else"EXACT_RUNTIME_READBACK","lastFailure":None}
   c.report_protocol(domain,"VERIFY_ALL",report)
 if os.environ.get("FAIL_FINAL_RENDERED")=="1":
  print(json.dumps({"success":False}));raise SystemExit(1)
 c.finish();result={"success":True}
elif args[:3]==["operation","generation","render-boot-gate"]:
 result={"success":True,"bootHeld":False}
elif args[:3]==["operation","maintenance","status"]:
 result={"success":True,"bootHeld":False}
else:result={"success":True}
print(json.dumps(result))
''')
        stub.chmod(0o700)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        for command in ("mount", "systemctl", "modprobe"):
            path = bin_dir / command
            path.write_text("#!/bin/sh\nexit 0\n")
            path.chmod(0o700)
        environment = dict(os.environ, CASE_ROOT=str(root), CHECK_BOOT_FD9="1",
                           BOOT_COORDINATOR_MODULE=str(ROOT/"systemvm/debian/usr/local/lib/ablestack-storage/boot_reconcile.py"),
                           PATH=str(bin_dir) + ":" + os.environ["PATH"],
                           ABLESTACK_STORAGECTL=str(stub), ABLESTACK_STORAGE_MONITOR="/nonexistent",
                           ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(config),
                           ABLESTACK_STORAGE_DESIRED_STATE_DIR=str(desired),
                           ABLESTACK_STORAGE_BOOT_PROGRESS_JOURNAL=str(root / "progress/status.json"),
                           ABLESTACK_STORAGE_BOOT_CHILD_JOURNAL=str(root / "child/current.json"),
                           ABLESTACK_STORAGE_BOOT_CHECKPOINT_DIR=str(root / "receipts"),
                           ABLESTACK_STORAGE_MONITOR_CACHE_DIR=str(root / "cache"),
                           ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(root / "writer/desired.lock"),
                           ABLESTACK_STORAGE_BOOT_RECONCILE_ATTEMPTS="3", ABLESTACK_STORAGE_BOOT_RECONCILE_SLEEP="0")
        return environment

    def test_actual_standalone_entry_retries_pending_detail_and_keeps_successful_smb_once(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            environment = self.fixture(root)
            result = subprocess.run(["bash", str(BOOT)], env=environment, capture_output=True, text=True, timeout=20)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            calls = (root / "calls").read_text().splitlines()
            self.assertEqual(2, calls.count("nfs export apply"))
            self.assertEqual(1, calls.count("smb share apply"))
            self.assertGreaterEqual(calls.count("nfs export boot-verify"), 2)
            receipt = json.loads((root / "receipts/smb.json").read_text())
            self.assertEqual("VERIFIED", receipt["phase"])
            self.assertTrue(receipt["detailVerified"])
            state = json.loads((root / "progress/status.json").read_text())
            self.assertEqual("READY", state["status"])
            self.assertTrue(state["detailedVerified"])
            self.assertGreaterEqual(state["deadlineSeconds"], 600)
            # Even if the old monitor unit cannot start, its API health cache observes boot.
            cache = json.loads((root / "cache/health.json").read_text())
            self.assertEqual("READY", cache["bootReconcile"]["status"])
            self.assertNotIn("listenPorts", cache)

    def test_actual_standalone_smb_ack_requires_owned_listener_and_tcp(self):
        for owned, tcp, expected in (("0", "1", 1), ("1", "0", 1), ("1", "1", 0)):
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                environment = self.fixture(root)
                environment.update(SMB_OWNED=owned, SMB_TCP=tcp, FIRST_NFS_PENDING="0",
                                   ABLESTACK_STORAGE_BOOT_RECONCILE_ATTEMPTS="1")
                result = subprocess.run(["bash", str(BOOT)], env=environment, capture_output=True, text=True, timeout=20)
                self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
                state = json.loads((root / "progress/status.json").read_text())
                self.assertEqual(expected == 0, state["protocols"]["SMB"]["verified"])


    def test_same_boot_rendered_terminal_restart_and_expired_transaction_get_fresh_budget(self):
        for expired in (False, True):
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                environment = self.fixture(root)
                environment.update(RENDERED_FIXTURE="1", FIRST_NFS_PENDING="0")
                for run in range(2):
                    result = subprocess.run(["bash", str(BOOT)], env=environment, capture_output=True, text=True, timeout=20)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    state = json.loads((root / "progress/status.json").read_text())
                    self.assertEqual("READY", state["status"])
                    if run == 0 and expired:
                        state["_internal"]["deadlineMonotonic"] = 1.0
                        state["_internal"]["startedMonotonic"] = .5
                        state["remainingSeconds"] = 0.0
                        (root / "progress/status.json").write_text(json.dumps(state))
                self.assertGreater(state["remainingSeconds"], 500)
                calls = (root / "calls").read_text().splitlines()
                self.assertEqual(2, calls.count("operation generation render-boot"))

    def test_rendered_final_verification_failure_never_publishes_ready(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            environment = self.fixture(root)
            environment.update(RENDERED_FIXTURE="1", FAIL_FINAL_RENDERED="1")
            result = subprocess.run(["bash", str(BOOT)], env=environment, capture_output=True, text=True, timeout=20)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            state = json.loads((root / "progress/status.json").read_text())
            self.assertNotEqual("READY", state["status"])
            self.assertEqual("BOOT_RENDERED_FINALIZATION_UNVERIFIED", state["lastFailure"]["code"])

    def test_unknown_probe_cleanup_stops_before_smb_or_another_nfs_attempt(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            environment = self.fixture(root)
            environment["CLEANUP_UNCERTAIN"] = "1"
            result = subprocess.run(["bash", str(BOOT)], env=environment, capture_output=True, text=True, timeout=20)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            calls = (root / "calls").read_text().splitlines()
            self.assertEqual(1, calls.count("nfs export apply"))
            self.assertNotIn("smb share apply", calls)
            state = json.loads((root / "progress/status.json").read_text())
            self.assertEqual("HELD", state["status"])
            self.assertEqual("NFS_PROBE_TIMEOUT", state["lastFailure"]["code"])
            self.assertFalse(state["lastFailure"]["cleanup"]["complete"])

    def test_concurrent_second_root_invocation_does_not_mutate_active_owner_progress(self):
        import time
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            environment = self.fixture(root)
            barrier = root / "pause"
            barrier.write_text("OWNED_LOCAL_BARRIER")
            environment.update(NFS_WAIT_MARKER=str(barrier), FIRST_NFS_PENDING="0")
            first = subprocess.Popen(["bash", str(BOOT)], env=environment, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True)
            try:
                until = time.monotonic() + 5
                while not Path(str(barrier) + ".ready").exists() and time.monotonic() < until:
                    time.sleep(.01)
                self.assertTrue(Path(str(barrier) + ".ready").exists())
                before = json.loads((root / "progress/status.json").read_text())
                call_count = len((root / "calls").read_text().splitlines())
                second = subprocess.run(["bash", str(BOOT)], env=environment,
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(1, second.returncode)
                self.assertEqual("BOOT_WRITER_BUSY", json.loads(second.stdout)["code"])
                after = json.loads((root / "progress/status.json").read_text())
                self.assertEqual("RECONCILING", after["status"])
                self.assertEqual(before["_internal"], after["_internal"])
                self.assertEqual(before["desiredHash"], after["desiredHash"])
                self.assertEqual(call_count, len((root / "calls").read_text().splitlines()))
                barrier.unlink()
                output, error = first.communicate(timeout=15)
                self.assertEqual(0, first.returncode, output + error)
            finally:
                if first.poll() is None:
                    first.terminate()
                    first.communicate(timeout=5)


    def test_production_entry_and_monitor_embed_the_exact_same_canonical_coordinator(self):
        module = (ROOT / "systemvm/debian/usr/local/lib/ablestack-storage/boot_reconcile.py").read_text()
        module = module[module.index('"""'):].rstrip()
        for path in (BOOT, ROOT / "systemvm/debian/usr/local/bin/ablestack-storage-monitor"):
            source = path.read_text()
            embedded = source.split("# BEGIN EMBEDDED STORAGE_BOOT_COORDINATOR\n", 1)[1].split(
                "\n# END EMBEDDED STORAGE_BOOT_COORDINATOR", 1)[0]
            self.assertEqual(module, embedded)


if __name__ == "__main__":
    unittest.main()
