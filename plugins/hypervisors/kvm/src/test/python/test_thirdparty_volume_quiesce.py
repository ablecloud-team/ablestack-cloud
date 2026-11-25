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

"""All volumes acquire their recovery points before thaw and sequential transfer."""

import json
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[6] / "scripts/vm/hypervisor/kvm"))
import thirdparty_volume_backup as backup
import thirdparty_primary_capacity as capacity


class VolumeQuiesceTest(unittest.TestCase):
    def test_real_transfer_ack_keeps_host_quiesce_evidence_for_final_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            job = Path(directory)
            payload = job / "disk.raw"
            payload.write_bytes(b"payload")
            artifact = {"backupUuid": "backup-1", "path": str(payload), "size": 7}
            quiesce = {"requested": True, "status": "VERIFIED"}
            manifest = {"backupUuid": "backup-1", "guestQuiesce": quiesce,
                        "volumes": [{"chain": [artifact]}]}
            plan = {"manifest": manifest, "backupPath": directory, "vmName": "i-2-1-VM", "timeout": 300,
                    "providerStep": "NETBACKUP_TRANSFER"}
            backup.atomic(job / "volume-plan.json", plan)
            controller = json.loads(json.dumps(manifest))
            controller.pop("guestQuiesce")  # The previous controller acknowledged only artifact/catalog fields.
            controller["volumes"][0]["chain"][0].update(completed=True, externalId="host_123")
            backup.atomic(job / "volume-ack-0.json", controller)
            engine = backup.Backup(job / "volume-plan.json")
            requests = []
            original = backup.atomic

            def capture(path, value):
                if Path(path).name == "volume-request.json":
                    requests.append(json.loads(json.dumps(value)))
                return original(path, value)

            with mock.patch.object(engine, "progress"), mock.patch.object(engine, "check_cancel"), \
                    mock.patch.object(backup, "atomic", side_effect=capture):
                engine.transfer(0, artifact)
            self.assertEqual(quiesce, requests[0]["guestQuiesce"])
            self.assertEqual(quiesce, engine.manifest["guestQuiesce"])
            self.assertFalse(payload.exists())

    def test_conflicting_ack_keeps_the_original_payload_and_quiesce_result(self):
        with tempfile.TemporaryDirectory() as directory:
            job = Path(directory)
            payload = job / "disk.raw"
            payload.write_bytes(b"payload")
            artifact = {"backupUuid": "backup-1", "path": str(payload), "size": 7}
            quiesce = {"requested": True, "status": "VERIFIED"}
            manifest = {"backupUuid": "backup-1", "guestQuiesce": quiesce,
                        "volumes": [{"chain": [artifact]}]}
            backup.atomic(job / "volume-plan.json", {"manifest": manifest, "backupPath": directory,
                          "vmName": "i-2-1-VM", "timeout": 300, "providerStep": "NETBACKUP_TRANSFER"})
            controller = json.loads(json.dumps(manifest))
            controller["guestQuiesce"] = {"requested": False, "status": "NOT_REQUESTED"}
            controller["volumes"][0]["chain"][0].update(completed=True, externalId="host_123")
            backup.atomic(job / "volume-ack-0.json", controller)
            engine = backup.Backup(job / "volume-plan.json")
            with mock.patch.object(engine, "progress"), mock.patch.object(engine, "check_cancel"), \
                    self.assertRaisesRegex(RuntimeError, "changed the guest quiesce result"):
                engine.transfer(0, artifact)
            self.assertEqual(quiesce, engine.manifest["guestQuiesce"])
            self.assertEqual(b"payload", payload.read_bytes())

    def exercise(self, provider="ablestack-netbackup", rbd=True, quiesce=True,
                 agent_error=False, source_error=False, stopped=False, fault=None, error=None):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            job, root = base / "backup-1", base / "stage"
            job.mkdir()
            root.mkdir()
            paths = ["rbd:pool/root", "rbd:pool/data"] if rbd else [str(base / name) for name in ("root.qcow2", "data.qcow2")]
            volumes = [{"chain": [{"path": str(root / name)}]} for name in ("root.raw", "data.raw")]
            plan = {"manifest": {"backupUuid": "backup-1", "provider": provider, "backupType": "FULL", "volumes": volumes},
                    "backupPath": str(root), "vmName": "i-2-1-VM", "sourceHost": "host-1", "timeout": 300,
                    "diskPaths": paths, "rbd": rbd, "quiesce": quiesce, "checkpointName": "point-1"}
            backup.atomic(job / "volume-plan.json", plan)
            backup.atomic(job / "volume-source-start.json", {"version": 1, "backupUuid": "backup-1", "state": "NOT_STARTED"})
            backup.atomic(job / "backup-primary-capacity.json", {})
            (job / "volume-start").touch()
            engine = backup.Backup(job / "volume-plan.json")
            events, frozen = [], [fault == "foreign-freeze"]

            def reserve():
                events.append("admitted")
                backup.atomic(engine.engine, {})
                (root / ".staging.inprogress").touch()

            domain = '<domain><name>i-2-1-VM</name><uuid>vm-uuid</uuid><devices>' + ''.join(
                '<disk device="disk"><source file="%s"/><target dev="vd%s"/></disk>' % (path, chr(97 + i))
                for i, path in enumerate(paths)) + '</devices></domain>'

            def run(command, *args):
                if "qemu-agent-command" in command:
                    action = json.loads(command[-1])["execute"]
                    if agent_error:
                        raise RuntimeError("QEMU Guest Agent unavailable")
                    if action == "guest-fsfreeze-status":
                        return json.dumps({"return": "frozen" if frozen[0] else "thawed"})
                    self.assertTrue(json.loads(engine.engine.read_text())["freezePending"],
                                    "ownership must be durable before any freeze/thaw request")
                    if action == "guest-fsfreeze-freeze":
                        events.append("freeze")
                        frozen[0] = fault != "freeze-zero"
                        if fault == "freeze-timeout":
                            raise subprocess.TimeoutExpired(command, 10)
                        if fault == "freeze-json-error":
                            return '{"error":{"class":"GenericError"}}'
                        return json.dumps({"return": 0 if fault == "freeze-zero" else 2})
                    events.append("thaw")
                    if fault == "thaw-stays-frozen":
                        return '{"return":2}'
                    frozen[0] = False
                    if fault == "thaw-response-lost":
                        raise subprocess.TimeoutExpired(command, 10)
                    return '{"return":2}'
                if command[0] == "rbd" and command[1:3] == ["snap", "create"] or "backup-begin" in command:
                    events.append("recovery")
                    self.assertEqual(quiesce and not agent_error and not stopped, frozen[0])
                    if source_error:
                        raise RuntimeError("source recovery point failed")
                    if fault == "cancel":
                        raise InterruptedError("backup interrupted")
                    if fault == "auto-thaw" and events.count("recovery") == (2 if rbd else 1):
                        frozen[0] = False
                    return "{}"
                if "dumpxml" in command:
                    return domain
                if "domstate" in command:
                    return "shut off" if stopped else "running"
                if "checkpoint-dumpxml" in command:
                    return "<domaincheckpoint/>"
                return "{}"

            def export(index, target):
                self.assertFalse(frozen[0], "guest must be thawed before exporting any volume")
                self.assertEqual(2 if rbd else 1, events.count("recovery"))
                events.append("export-%s" % index)
                target.write_bytes(b"payload")

            def transfer(index, artifact, metadata=False):
                self.assertFalse(frozen[0], "guest must remain thawed throughout external transfer")
                events.append("metadata" if metadata else "transfer-%s" % index)

            with mock.patch.object(engine, "reserve", side_effect=reserve), \
                    mock.patch.object(engine, "preflight_qcow2"), \
                    mock.patch.object(engine, "export_rbd", side_effect=export), \
                    mock.patch.object(engine, "export_qcow2", side_effect=export), \
                    mock.patch.object(engine, "transfer", side_effect=transfer), \
                    mock.patch.object(backup, "run", side_effect=run), \
                    mock.patch.object(capacity, "backup_capacity", return_value={"primaryClaims": []}):
                if error:
                    with self.assertRaisesRegex(Exception, error):
                        engine.execute()
                else:
                    engine.execute()
            receipt = json.loads(engine.engine.read_text())
            return events, receipt, engine.manifest

    def test_all_providers_thaw_after_entire_recovery_point_before_any_transfer(self):
        for provider in ("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam"):
            for rbd in (True, False):
                with self.subTest(provider=provider, rbd=rbd):
                    events, receipt, manifest = self.exercise(provider, rbd)
                    self.assertEqual(["admitted", "freeze"] + ["recovery"] * (2 if rbd else 1)
                                     + ["thaw", "export-0", "transfer-0", "export-1", "transfer-1", "metadata"], events)
                    self.assertFalse(receipt["freezePending"])
                    self.assertEqual({"requested": True, "status": "VERIFIED"}, manifest["guestQuiesce"])

    def test_disabled_quiesce_preserves_backup_without_guest_agent_for_both_storage_types(self):
        for rbd in (True, False):
            with self.subTest(rbd=rbd):
                events, receipt, manifest = self.exercise(rbd=rbd, quiesce=False, agent_error=True)
                self.assertNotIn("freeze", events)
                self.assertNotIn("thaw", events)
                self.assertEqual("metadata", events[-1])
                self.assertEqual({"requested": False, "status": "NOT_REQUESTED"}, manifest["guestQuiesce"])

    def test_requested_freeze_without_agent_fails_before_snapshot_or_transfer(self):
        for rbd in (True, False):
            with self.subTest(rbd=rbd):
                events, receipt, _ = self.exercise(rbd=rbd, agent_error=True, error="GUEST_FREEZE_UNCONFIRMED")
                self.assertEqual(["admitted"], events)
                self.assertFalse(receipt.get("freezePending"))

    def test_stopped_rbd_vm_does_not_need_guest_agent_even_when_requested(self):
        events, receipt, manifest = self.exercise(stopped=True, agent_error=True)
        self.assertNotIn("freeze", events)
        self.assertEqual("metadata", events[-1])
        self.assertEqual("NOT_APPLICABLE", manifest["guestQuiesce"]["status"])

    def test_source_error_or_cancel_thaws_without_exporting(self):
        for rbd in (True, False):
            for cancel in (False, True):
                with self.subTest(rbd=rbd, cancel=cancel):
                    events, receipt, _ = self.exercise(rbd=rbd, source_error=not cancel,
                        fault="cancel" if cancel else None, error="interrupted" if cancel else "source recovery point failed")
                    self.assertEqual(["admitted", "freeze", "recovery", "thaw"], events)
                    self.assertFalse(receipt["freezePending"])

    def test_uncertain_or_invalid_freeze_response_never_creates_recovery_point(self):
        for fault in ("freeze-timeout", "freeze-json-error", "freeze-zero"):
            with self.subTest(fault=fault):
                events, receipt, _ = self.exercise(fault=fault, error="GUEST_FREEZE_UNCONFIRMED")
                self.assertNotIn("recovery", events)
                self.assertFalse(receipt["freezePending"])
                if fault != "freeze-zero":
                    self.assertEqual("thaw", events[-1])

    def test_foreign_freeze_is_never_thawed(self):
        events, receipt, _ = self.exercise(fault="foreign-freeze", error="GUEST_FREEZE_UNCONFIRMED")
        self.assertEqual(["admitted"], events)
        self.assertFalse(receipt.get("freezePending"))

    def test_expired_vss_freeze_window_rejects_recovery_points_before_export(self):
        for rbd in (True, False):
            with self.subTest(rbd=rbd):
                events, receipt, _ = self.exercise(rbd=rbd, fault="auto-thaw", error="GUEST_FREEZE_LOST")
                self.assertNotIn("export-0", events)
                self.assertFalse(receipt["freezePending"])

    def test_successful_thaw_response_without_resumed_io_retains_ownership(self):
        events, receipt, _ = self.exercise(fault="thaw-stays-frozen", error="thaw remains pending")
        self.assertEqual(3, events.count("thaw"))
        self.assertNotIn("export-0", events)
        self.assertTrue(receipt["freezePending"])

    def test_lost_thaw_response_is_accepted_only_after_thawed_status(self):
        events, receipt, _ = self.exercise(fault="thaw-response-lost")
        self.assertEqual(1, events.count("thaw"))
        self.assertEqual("metadata", events[-1])
        self.assertFalse(receipt["freezePending"])


if __name__ == "__main__":
    unittest.main()
