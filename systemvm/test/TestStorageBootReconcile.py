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

import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

LIB = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0, str(LIB))
import boot_reconcile as module


class StorageBootReconcileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.clock = 100.0
        self.epoch = 1700000000.0
        self.boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        self.work = {"probeTasks": 2, "endpointModeGroups": 1,
                     "configuredProtocolCount": 2, "concurrency": 1}
        patcher = patch.dict(os.environ, {"ABLESTACK_STORAGE_MONITOR_CACHE_DIR": str(self.root / "cache")})
        patcher.start()
        self.addCleanup(patcher.stop)

    def coordinator(self, **kwargs):
        return module.BootCoordinator(
            kwargs.pop("desired_hash", "a" * 64), kwargs.pop("workload", self.work),
            kwargs.pop("instance_uuid", None), journal=self.root / "progress/status.json",
            checkpoint_dir=self.root / "receipts", clock=lambda: self.clock,
            epoch=lambda: self.epoch, boot_id=kwargs.pop("boot_id", self.boot),
            configured_protocols=kwargs.pop("configured_protocols", ("NFS", "SMB")), **kwargs)

    def report(self, pending=False, success=True, domain="NFS"):
        return {"success": success, "runtimeReady": True, "detailVerified": not pending,
                "probePending": 1 if pending else 0, "completedProbeCount": 0 if pending else 1,
                "totalProbeCount": 1, "lastFailure": None, "skippedAclCount": 0,
                "detailVerificationKind": "UNVERIFIED" if pending else "MOUNT_PROBES_COMPLETE" if domain == "NFS" else "EXACT_RUNTIME_READBACK"}

    def test_successful_smb_not_reapplied_during_nfs_retry_actual_checkpoint_and_callbacks(self):
        c = self.coordinator()
        calls = {"NFS": 0, "SMB": 0}
        def apply(domain):
            calls[domain] += 1
            return self.report(pending=domain == "NFS" and calls[domain] == 1, domain=domain)
        c.begin_attempt(1)
        self.assertFalse(c.run_protocol("NFS", "b" * 64, lambda: apply("NFS"), lambda: self.report(True)))
        self.assertTrue(c.run_protocol("SMB", "c" * 64, lambda: apply("SMB"), lambda: self.report(domain="SMB")))
        c.begin_attempt(2)
        self.assertTrue(c.run_protocol("NFS", "b" * 64, lambda: apply("NFS"), self.report))
        self.assertTrue(c.run_protocol("SMB", "c" * 64, lambda: apply("SMB"), lambda: self.report(domain="SMB")))
        self.assertEqual({"NFS": 2, "SMB": 1}, calls)
        self.assertEqual("READY", c.finish()["status"])
        self.assertTrue(json.loads((self.root / "receipts/smb.json").read_text())["detailVerified"])

    def test_listener_ready_probe_pending_is_not_verified_or_terminal_ready(self):
        c = self.coordinator()
        c.begin_attempt(1)
        for domain in ("NFS", "SMB"):
            c.run_protocol(domain, "b" * 64, lambda: self.report(True), lambda: self.report(True))
        value = c.finish()
        self.assertTrue(value["runtimeReady"])
        self.assertFalse(value["detailedVerified"])
        self.assertEqual("DEGRADED", value["status"])
        self.assertFalse(value["protocols"]["NFS"]["verified"])
        self.assertEqual("FAILED", json.loads((self.root / "receipts/nfs.json").read_text())["phase"])

    def test_claimed_detail_true_but_incomplete_count_is_rejected(self):
        c = self.coordinator()
        result = dict(self.report(), completedProbeCount=0)
        self.assertFalse(c.run_protocol("NFS", "b" * 64, lambda: result, lambda: result))
        self.assertFalse(c.record["protocols"]["NFS"]["verified"])

    def test_successful_apply_ack_stays_pending_until_failed_fresh_verify(self):
        c = self.coordinator()
        ack = dict(self.report(True), runtimeReady=False, probePending=0,
                   completedProbeCount=0, totalProbeCount=0)
        failure = c.failure("PROBE_PERMISSION_DENIED", "NFS", cleanup_complete=True)
        denied = dict(self.report(), success=False, detailVerified=False,
                      completedProbeCount=99, totalProbeCount=100,
                      detailVerificationKind="UNVERIFIED", lastFailure=failure)
        def verify():
            row = c.heartbeat()["protocols"]["NFS"]
            self.assertEqual("PENDING", row["probeState"])
            self.assertFalse(row["verified"])
            self.assertEqual("UNVERIFIED", row["detailVerificationKind"])
            return denied
        self.assertFalse(c.run_protocol("NFS", "b" * 64, lambda: ack, verify))
        row = c.heartbeat()["protocols"]["NFS"]
        self.assertEqual("FAILED", row["probeState"])
        self.assertEqual(99, row["completedProbeCount"])
        self.assertEqual(failure, c.record["lastFailure"])

    def test_explicit_progress_cannot_hide_structured_or_cleanup_failure(self):
        c = self.coordinator()
        failure = c.failure("PROBE_PERMISSION_DENIED", "NFS", cleanup_complete=True)
        result = dict(self.report(True), success=False, completedProbeCount=99,
                      totalProbeCount=100, lastFailure=failure)
        self.assertFalse(c.report_protocol("NFS", "PROBE_EXPORTS", result, in_progress=True))
        self.assertEqual("FAILED", c.heartbeat()["protocols"]["NFS"]["probeState"])
        self.assertEqual(failure, c.record["lastFailure"])
        failure = c.failure("CLEANUP_PENDING", "NFS")
        result["lastFailure"] = failure
        c.report_protocol("NFS", "PROBE_EXPORTS", result, in_progress=True)
        self.assertEqual("FAILED", c.heartbeat()["protocols"]["NFS"]["probeState"])
        held = c.hold("NFS_PROBE_CLEANUP_PENDING", failure)
        self.assertEqual("HELD", held["status"])
        self.assertEqual(failure, held["lastFailure"])
        self.assertFalse(held["detailedVerified"])

    def test_progress_flag_and_failure_schema_are_strict_before_journal_update(self):
        c = self.coordinator()
        before = c.journal.read_bytes()
        for flag in (1, "true", None):
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                c.report_protocol("NFS", "PROBE_EXPORTS", self.report(True), in_progress=flag)
            self.assertEqual(before, c.journal.read_bytes())
        for failure in ({}, {"code": "PROBE_PERMISSION_DENIED"}, "FAILED", False):
            with self.subTest(failure=failure), self.assertRaises(ValueError):
                c.report_protocol("NFS", "PROBE_EXPORTS",
                                  dict(self.report(True), lastFailure=failure), in_progress=True)
            self.assertEqual(before, c.journal.read_bytes())

    def test_disabled_apply_ack_waits_for_fresh_verified_absence(self):
        c = self.coordinator(workload=dict(self.work, configuredProtocolCount=0,
                                          probeTasks=0, endpointModeGroups=0),
                             configured_protocols=())
        ack = dict(self.report(True), runtimeReady=False, probePending=0,
                   completedProbeCount=0, totalProbeCount=0)
        absent = dict(ack, runtimeReady=True, detailVerified=True,
                      detailVerificationKind="NOT_REQUIRED")
        def verify():
            row = c.heartbeat()["protocols"]["NFS"]
            self.assertEqual("PENDING", row["probeState"])
            self.assertFalse(row["verified"])
            return absent
        self.assertTrue(c.run_protocol("NFS", "b" * 64, lambda: ack, verify, configured=False))
        row = c.heartbeat()["protocols"]["NFS"]
        self.assertEqual("NOT_REQUIRED", row["probeState"])
        self.assertFalse(row["listenerReady"])

    def test_terminal_failure_without_progress_flag_is_never_silent_pending(self):
        c = self.coordinator()
        result = dict(self.report(), success=False, detailVerified=False,
                      detailVerificationKind="UNVERIFIED")
        self.assertFalse(c.report_protocol("NFS", "VERIFY_ALL", result))
        self.assertEqual("FAILED", c.heartbeat()["protocols"]["NFS"]["probeState"])
        self.assertFalse(c.record["protocols"]["NFS"]["verified"])

    def test_boot_and_desired_and_payload_change_invalidate_reuse(self):
        c = self.coordinator()
        c.run_protocol("SMB", "b" * 64, lambda: self.report(domain="SMB"), lambda: self.report(domain="SMB"))
        calls = []
        c.run_protocol("SMB", "c" * 64, lambda: calls.append("payload") or self.report(domain="SMB"), lambda: self.report(domain="SMB"))
        other = self.coordinator(desired_hash="d" * 64)
        other.run_protocol("SMB", "c" * 64, lambda: calls.append("desired") or self.report(domain="SMB"), lambda: self.report(domain="SMB"))
        other = self.coordinator(desired_hash="d" * 64, boot_id="00000000-0000-0000-0000-000000000001")
        other.run_protocol("SMB", "c" * 64, lambda: calls.append("boot") or self.report(domain="SMB"), lambda: self.report(domain="SMB"))
        self.assertEqual(["payload", "desired", "boot"], calls)

    def test_old_verified_receipt_still_requires_fresh_detail_observation(self):
        c = self.coordinator()
        module.boot_write_json(self.root / "receipts/nfs.json",
                               {"protocol": "NFS", "desiredSha256": "b" * 64,
                                "bootId": self.boot, "phase": "VERIFIED"})
        self.assertFalse(c.run_protocol("NFS", "b" * 64,
                                        lambda: self.report(True), lambda: self.report(True)))
        self.assertFalse(c.record["protocols"]["NFS"]["verified"])

    def test_workloads_1_10_100_modes_and_groups_and_sequential_budget_cap(self):
        for exports in (1, 10, 100):
            for modes in (1, 2):
                for groups in (1, 4):
                    plan = dict(self.work, probeTasks=exports * modes * groups,
                                endpointModeGroups=modes * groups)
                    seconds = module.boot_workload_deadline(plan)
                    self.assertEqual(min(1500, max(600, 90 + 2 * (15 * plan["probeTasks"]
                                                                 + 5 * plan["endpointModeGroups"]) + 40)), seconds)
                    self.assertLess(seconds, 1800)
        self.assertEqual(1500, module.boot_workload_deadline(dict(self.work, probeTasks=800)))
        for field, value in (("probeTasks", True), ("probeTasks", -1), ("probeTasks", "100"),
                             ("probeTasks", float("nan")), ("concurrency", 4)):
            with self.assertRaises(ValueError):
                module.boot_workload_deadline(dict(self.work, **{field: value}))

    def test_cap_deadline_starts_no_new_effect_and_old_verified_not_relabelled_ready(self):
        c = self.coordinator()
        c.run_protocol("NFS", "b" * 64, self.report, self.report)
        self.clock += 1501
        calls = []
        self.assertFalse(c.run_protocol("NFS", "b" * 64, lambda: calls.append(1), self.report))
        self.assertEqual([], calls)
        self.assertNotEqual("READY", c.finish()["status"])

    def test_heartbeat_persisted_and_same_transaction_cannot_extend_deadline(self):
        c = self.coordinator()
        original = c.deadline
        self.clock += 10
        self.epoch += 10
        public = c.heartbeat()
        self.assertEqual(10, public["elapsedSeconds"])
        self.assertEqual(self.epoch, public["updatedEpoch"])
        other = self.coordinator()
        self.assertEqual(original, other.deadline)
        self.assertEqual(100, other.started)

    def test_readonly_projection_rejects_foreign_boot_and_stale_active_not_old_terminal(self):
        c = self.coordinator()
        before = c.journal.read_bytes()
        self.assertEqual(self.boot, module.read_boot_progress(c.journal, self.boot, self.epoch)["bootId"])
        self.assertEqual(before, c.journal.read_bytes())
        with self.assertRaises(ValueError):
            module.read_boot_progress(c.journal, "00000000-0000-0000-0000-000000000001", self.epoch)
        with self.assertRaises(ValueError):
            module.read_boot_progress(c.journal, self.boot, self.epoch + 16)
        c.hold("APPROVED_MAINTENANCE_HELD")
        self.assertEqual("HELD", module.read_boot_progress(c.journal, self.boot, self.epoch + 3600)["status"])

    def test_no_configuration_is_explicit_skipped_not_all_protocols_listening(self):
        work = dict(self.work, configuredProtocolCount=0, probeTasks=0, endpointModeGroups=0)
        c = self.coordinator(workload=work, configured_protocols=())
        value = c.finish(skipped=True)
        self.assertEqual(("READY", "SKIPPED"), (value["status"], value["phase"]))
        self.assertTrue(value["detailedVerified"])
        self.assertTrue(all(not row["configured"] and not row["listenerReady"]
                            and row["applyState"] == "NOT_REQUIRED" for row in value["protocols"].values()))

    def test_minimal_cache_visible_with_old_monitor_unit_without_claiming_unobserved_ports(self):
        c = self.coordinator(publish_cache=True)
        c.begin_attempt(1)
        c.report_protocol("NFS", "PROBE_EXPORTS", self.report(True))
        cache = json.loads((self.root / "cache/health.json").read_text())
        self.assertEqual("RECONCILING", cache["bootReconcile"]["status"])
        self.assertFalse(cache["bootReconcile"]["detailedVerified"])
        self.assertNotIn("listenPorts", cache)
        self.assertNotIn("services", cache)
        self.assertEqual(self.boot, cache["bootId"])

    def test_boot_cache_never_hides_actual_same_boot_network_error(self):
        module.boot_write_json(self.root / "cache/network.json",
                               {"schemaVersion": 1, "kind": "SHAREDFS_NETWORK_READINESS", "bootId": self.boot,
                                "success": False, "status": "ERROR", "mode": "STATIC", "observedEpoch": self.epoch,
                                "expected": {}, "actual": {}, "mismatches": ["ADDRESS_MISMATCH"]}, private=False)
        c = self.coordinator(publish_cache=True)
        cache = json.loads((self.root / "cache/health.json").read_text())
        self.assertFalse(cache["success"])
        self.assertEqual("error", cache["status"])
        self.assertEqual(["ADDRESS_MISMATCH"], cache["networkReadiness"]["mismatches"])
        self.assertEqual("RECONCILING", cache["bootReconcile"]["status"])

    def test_unsafe_symlink_and_world_writable_progress_are_rejected(self):
        c = self.coordinator()
        c.journal.chmod(0o666)
        with self.assertRaises(ValueError):
            c.heartbeat()
        c.journal.chmod(0o600)
        content = c.journal.read_bytes()
        c.journal.unlink()
        c.journal.symlink_to(self.root / "unrelated")
        with self.assertRaises(ValueError):
            c.heartbeat()
        self.assertFalse((self.root / "unrelated").exists())
        self.assertTrue(content)

    def test_attach_existing_is_opt_in_exact_boot_desired_scope_no_deadline_extension(self):
        c = self.coordinator()
        with patch.dict(os.environ, {"ABLESTACK_STORAGE_BOOT_PROGRESS_JOURNAL": str(c.journal),
                                     "ABLESTACK_STORAGE_BOOT_DESIRED_HASH": "a" * 64,
                                     "ABLESTACK_STORAGE_BOOT_COORDINATOR": "1"}):
            attached = module.BootCoordinator.attach_existing()
            self.assertLessEqual(attached.deadline, c.deadline)
            self.assertEqual(self.boot, attached.boot_id)
            with patch.dict(os.environ, {"ABLESTACK_STORAGE_BOOT_DESIRED_HASH": "f" * 64}):
                with self.assertRaises(ValueError):
                    module.BootCoordinator.attach_existing()
        with patch.dict(os.environ, {"ABLESTACK_STORAGE_BOOT_COORDINATOR": "0"}):
            self.assertIsNone(module.BootCoordinator.attach_existing())


    def test_public_uuid_types_fail_closed_before_monitor_active_decision(self):
        c = self.coordinator()
        record = dict(c.heartbeat())
        for field in ("bootId", "instanceUuid"):
            for invalid in (True, 1, [], {}):
                damaged = dict(record, **{field: invalid})
                with self.assertRaises(ValueError):
                    module.boot_validate_public(damaged)

    def test_actual_writer_fd9_inheritance_external_deny_release_and_child_retention(self):
        import subprocess
        code = """
import fcntl,json,os,subprocess,sys,time
from pathlib import Path
sys.path.insert(0,sys.argv[1]);from boot_reconcile import boot_writer_lock
probe='import fcntl,os,sys;f=os.open(sys.argv[1],os.O_RDWR);fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)'
path=os.environ['ABLESTACK_STORAGE_WRITER_LOCK_FILE'];child=None
try:
 with boot_writer_lock():
  child=subprocess.Popen([sys.executable,'-c','import os,time;os.fstat(9);print("READY",flush=True);time.sleep(.5)'],pass_fds=(9,),stdout=subprocess.PIPE,text=True)
  assert child.stdout.readline().strip()=='READY'
  held=subprocess.run([sys.executable,'-c',probe,path],capture_output=True).returncode!=0
 retained=subprocess.run([sys.executable,'-c',probe,path],capture_output=True).returncode!=0
 child.wait(timeout=2)
 released=subprocess.run([sys.executable,'-c',probe,path],capture_output=True).returncode==0
 print(json.dumps({'held':held,'retainedByChildAfterParentClose':retained,'releasedAfterChild':released}))
finally:
 if child and child.poll()is None:child.terminate();child.wait(timeout=2)
"""
        environment = dict(os.environ, ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root / "writer/desired.lock"))
        result = subprocess.run([sys.executable, "-c", code, str(LIB)], env=environment,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(all(json.loads(result.stdout).values()))

    def test_writer_lock_invalid_mode_and_symlink_never_enter_effect_scope(self):
        import subprocess
        code = """
import os,sys
sys.path.insert(0,sys.argv[1]);from boot_reconcile import boot_writer_lock
try:
 with boot_writer_lock():raise AssertionError('EFFECT_SCOPE_ENTERED')
except ValueError:print('DENIED')
"""
        directory = self.root / "writer"
        directory.mkdir()
        path = directory / "desired.lock"
        path.write_text("")
        path.chmod(0o666)
        environment = dict(os.environ, ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(path))
        for symlink in (False, True):
            if symlink:
                path.unlink()
                path.symlink_to(self.root / "foreign")
                (self.root / "foreign").write_text("UNRELATED")
            result = subprocess.run([sys.executable, "-c", code, str(LIB)], env=environment,
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("DENIED", result.stdout.strip())
        self.assertEqual("UNRELATED", (self.root / "foreign").read_text())


    def test_absence_only_disabled_protocol_is_replayed_and_verified_without_false_mount_claim(self):
        work = dict(self.work, configuredProtocolCount=0, probeTasks=0, endpointModeGroups=0)
        c = self.coordinator(workload=work, configured_protocols=())
        good = {"success": True, "runtimeReady": True, "detailVerified": True, "probePending": 0,
                "completedProbeCount": 0, "totalProbeCount": 0, "skippedAclCount": 0,
                "detailVerificationKind": "NOT_REQUIRED", "lastFailure": None}
        calls = []
        self.assertTrue(c.run_protocol("NFS", "b" * 64, lambda: calls.append("apply") or good, lambda: good,
                                       configured=False))
        self.assertEqual(["apply"], calls)
        self.assertEqual("NOT_REQUIRED", c.finish()["protocols"]["NFS"]["detailVerificationKind"])
        bad = dict(good, success=False, runtimeReady=False, detailVerified=False, detailVerificationKind="UNVERIFIED")
        self.assertFalse(c.run_protocol("NFS", "c" * 64, lambda: bad, lambda: bad, configured=False))
        row = c.heartbeat()["protocols"]["NFS"]
        self.assertFalse(row["verified"])
        self.assertEqual("FAILED", row["applyState"])

    def test_all_acl_skipped_retains_inventory_basis_and_skip_count_in_public_projection(self):
        c = self.coordinator()
        value = {"success": True, "runtimeReady": True, "detailVerified": True, "probePending": 0,
                 "completedProbeCount": 0, "totalProbeCount": 0, "skippedAclCount": 2,
                 "detailVerificationKind": "EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS", "lastFailure": None}
        self.assertTrue(c.run_protocol("NFS", "b" * 64, lambda: value, lambda: value))
        row = c.heartbeat()["protocols"]["NFS"]
        self.assertEqual(2, row["skippedAclCount"])
        self.assertEqual(0, row["totalProbeCount"])
        self.assertEqual("EXACT_INVENTORY_AND_ELIGIBLE_MOUNTS", row["detailVerificationKind"])
        for missing in ("skippedAclCount", "detailVerificationKind"):
            damaged = dict(value)
            damaged.pop(missing)
            with self.assertRaises(ValueError):
                module.boot_report(damaged)

    def test_minimal_cache_does_not_refresh_stale_future_or_nonfinite_network_observation(self):
        for observed in (self.epoch - 31, self.epoch + 6, float("nan"), True, "1700000000"):
            module.boot_write_json(self.root / "cache/network.json",
                                   {"schemaVersion": 1, "kind": "SHAREDFS_NETWORK_READINESS", "bootId": self.boot,
                                    "success": False, "status": "ERROR", "mode": "STATIC", "observedEpoch": observed,
                                    "expected": {}, "actual": {}, "mismatches": ["ADDRESS_MISMATCH"]}, private=False) if observed == observed else None
            if observed != observed:
                # Corrupt NaN is injected as raw JSON to exercise the protected reader, not the normal writer.
                target = self.root / "cache/network.json"
                target.write_text(target.read_text().replace('1700000000', 'NaN')) if target.exists() else None
                record = json.loads(target.read_text())
                record["observedEpoch"] = float("nan")
                target.write_text(json.dumps(record))
            c = self.coordinator(publish_cache=True)
            cache = json.loads((self.root / "cache/health.json").read_text())
            self.assertNotIn("networkReadiness", cache)
            self.assertEqual("RECONCILING", cache["bootReconcile"]["status"])



if __name__ == "__main__":
    unittest.main()
