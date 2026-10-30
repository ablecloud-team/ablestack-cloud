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
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[6] / "scripts/vm/hypervisor/kvm"))
import thirdparty_volume_backup as backup
import thirdparty_primary_capacity as capacity


class VolumeQuiesceTest(unittest.TestCase):
    def exercise(self, provider, rbd, quiesce=True, agent_error=False, source_error=False):
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
            backup.atomic(job / "volume-engine.json", {})
            # execute() must create the engine receipt only after its initial ownership check.
            (job / "volume-engine.json").unlink()
            backup.atomic(job / "backup-primary-capacity.json", {})
            (job / "volume-start").touch()
            engine = backup.Backup(job / "volume-plan.json")
            events, frozen = [], [False]

            def reserve():
                events.append("admitted")
                backup.atomic(engine.engine, {})
                (root / ".staging.inprogress").touch()

            domain = '<domain><devices>' + ''.join(
                '<disk device="disk"><source file="%s"/><target dev="vd%s"/></disk>' % (path, chr(97 + i))
                for i, path in enumerate(paths)) + '</devices></domain>'

            def run(command, *args):
                if "qemu-agent-command" in command:
                    action = json.loads(command[-1])["execute"]
                    if agent_error:
                        raise RuntimeError("QEMU Guest Agent unavailable")
                    if action == "guest-fsfreeze-status":
                        return json.dumps({"return": "frozen" if frozen[0] else "thawed"})
                    frozen[0] = action == "guest-fsfreeze-freeze"
                    events.append("freeze" if frozen[0] else "thaw")
                    return '{"return": 2}'
                if command[0] == "rbd" and command[1:3] == ["snap", "create"] or "backup-begin" in command:
                    events.append("recovery")
                    self.assertEqual(quiesce and not agent_error, frozen[0])
                    if source_error:
                        raise RuntimeError("source recovery point failed")
                if "dumpxml" in command:
                    return domain
                if "domstate" in command:
                    return "running"
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
                if source_error:
                    with self.assertRaisesRegex(RuntimeError, "source recovery point failed"):
                        engine.execute()
                else:
                    engine.execute()
            self.assertFalse(frozen[0])
            return events

    def test_rbd_and_qcow2_thaw_after_all_recovery_points_before_any_volume_transfer(self):
        for provider in ("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam"):
            for rbd in (True, False):
                with self.subTest(provider=provider, rbd=rbd):
                    events = self.exercise(provider, rbd)
                    self.assertEqual(["admitted", "freeze"] + ["recovery"] * (2 if rbd else 1)
                                     + ["thaw", "export-0", "transfer-0", "export-1", "transfer-1", "metadata"], events)

    def test_disabled_quiesce_and_missing_agent_continue_without_freezing(self):
        for rbd in (True, False):
            for quiesce, agent_error in ((False, False), (True, True)):
                with self.subTest(rbd=rbd, quiesce=quiesce, agent_error=agent_error):
                    events = self.exercise("ablestack-netbackup", rbd, quiesce, agent_error)
                    self.assertNotIn("freeze", events)
                    self.assertEqual("metadata", events[-1])

    def test_source_failure_thaws_before_aborting_without_transferring(self):
        for rbd in (True, False):
            with self.subTest(rbd=rbd):
                events = self.exercise("ablestack-veeam", rbd, source_error=True)
                self.assertEqual(["admitted", "freeze", "recovery", "thaw"], events)


if __name__ == "__main__":
    unittest.main()
