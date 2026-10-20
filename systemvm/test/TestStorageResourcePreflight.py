# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.


import ast
import copy
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
LIB = REPO / "systemvm/debian/usr/local/lib/ablestack-storage"
CLI = REPO / "systemvm/debian/usr/local/bin/ablestack-storagectl"
sys.path.insert(0, str(LIB))
from resource_reservation import ResourceReservation
from resource_preflight import ResourcePreflight, ReadinessProbe, preflight_json, preflight_read


class Probe:
    def __init__(self):
        self.calls = 0
        self.sessions = 0
        self.health = None
        self.change = None
        self.error = None
        self.volume_change = None

    def observe(self, scope, readiness):
        self.calls += 1
        if self.error:
            raise self.error
        if self.change:
            self.change()
        volumes = [{**row, "mappingStatus": "EXACT", "mounted": row["kind"] == "FILE_DATA",
                    "readOnly": True} for row in readiness["volumes"]]
        if self.volume_change and volumes:
            volumes[0].update(self.volume_change)
        return {"activeSessions": self.sessions, "protocolHealth": self.health or {p: "READY" for p in readiness["protocols"]},
                "backingVolumes": volumes, "sessionObservationKind": "TCP_CONNECTIONS_ONLY"}


class StorageResourcePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.gen = self.root / "generation"
        self.gen.mkdir(mode=0o700)
        self.instance, self.operation, self.volume, self.fs = (str(uuid.uuid4()) for _ in range(4))
        self.current = self.gen / "current.json"
        self.write(self.current, {"instanceUuid": self.instance, "revision": 9})
        self.observed = {"memoryAvailableBytes": 8 << 30, "stagingFreeBytes": 8 << 30,
                         "loadPerCpu": .2, "logicalReservationOnly": True}
        self.store = ResourceReservation(self.root / "lease", self.gen, lambda: self.observed, lambda: 1000, "boot-test")
        self.requirements = {"minimumMemoryAvailableBytes": 256 << 20, "stagingRequiredBytes": 100 << 20,
                             "maxLoadPerCpu": 2, "requireSessionDrain": False}
        self.request = {"instanceUuid": self.instance, "operationUuid": self.operation, "revision": 10,
                        "requirements": self.requirements,
                        "readiness": {"protocols": ["NFS"], "volumes": []}}
        self.probe = Probe()
        self.preflight = ResourcePreflight(self.store, self.probe)

    def write(self, path, value):
        path.write_text(json.dumps(value))
        path.chmod(0o600)

    def add_volume(self, kind="UNUSED"):
        self.request["readiness"]["volumes"] = [{"volumeUuid": self.volume, "sizeBytes": 8 << 30, "kind": kind,
                                                "filesystemUuid": self.fs if kind == "FILE_DATA" else None}]

    def test_typed_positive_fresh_exact_scope_is_readonly_and_never_allocates(self):
        before = self.current.read_bytes()
        self.add_volume()
        result = self.preflight.execute(self.request)
        self.assertTrue(result["success"])
        self.assertEqual("TYPED_READINESS_V1", result["mode"])
        self.assertEqual({k: self.request[k] for k in ("instanceUuid", "operationUuid", "revision")}, result["scope"])
        self.assertEqual(self.requirements, result["requirements"])
        self.assertEqual("TCP_CONNECTIONS_ONLY", result["observed"]["sessionObservationKind"])
        self.assertFalse(result["drainSupported"])
        self.assertEqual(before, self.current.read_bytes())
        self.assertFalse(self.store.root.exists())

    def test_real_formal_16gib_floor_blocks_without_allocating_or_domain_probe(self):
        self.request["requirements"] = {**self.requirements, "minimumMemoryAvailableBytes": 16 << 30}
        result = self.preflight.execute(self.request)
        self.assertEqual(["MEMORY_HEADROOM"], result["blockers"])
        self.assertFalse(result["compatible"])
        self.assertIsNone(result["observed"]["activeSessions"])
        self.assertEqual(0, self.probe.calls)
        self.assertFalse(self.store.root.exists())

    def test_staging_and_load_pressure_use_exact_requested_floors(self):
        for field, value, blocker in (("stagingFreeBytes", 1, "STAGING_HEADROOM"),
                                      ("loadPerCpu", 3, "LOAD_HEADROOM")):
            with self.subTest(field=field):
                prior = self.observed.copy()
                self.observed[field] = value
                self.assertIn(blocker, self.preflight.execute(self.request)["blockers"])
                self.observed = prior
        self.assertEqual(0, self.probe.calls)

    def test_missing_bool_nonfinite_and_negative_observations_fail_closed(self):
        for key in ("memoryAvailableBytes", "stagingFreeBytes", "loadPerCpu"):
            for value in (None, True, -1, float("nan"), float("inf")):
                with self.subTest(key=key, value=value):
                    prior = self.observed.copy()
                    self.observed[key] = value
                    result = self.preflight.execute(self.request)
                    self.assertFalse(result["success"])
                    self.assertTrue(any(code.endswith("_UNAVAILABLE") for code in result["blockers"]))
                    self.observed = prior
        self.assertEqual(0, self.probe.calls)

    def test_foreign_instance_pending_stale_and_absent_source_never_gain_authority(self):
        for change in ({"instanceUuid": str(uuid.uuid4())}, {"revision": 8}, {"revision": True}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.preflight.execute({**self.request, **change})
        self.write(self.gen / "pending.json", {**self.request, "operationUuid": str(uuid.uuid4())})
        with self.assertRaises(ValueError):
            self.preflight.execute(self.request)
        (self.gen / "pending.json").unlink()
        self.current.unlink()
        with self.assertRaises(ValueError):
            self.preflight.execute(self.request)
        self.assertFalse(self.store.root.exists())

    def test_exact_schema_no_caller_supplied_observation_or_private_body(self):
        for mutate in (lambda r: r.update(observed={}), lambda r: r.pop("requirements"),
                       lambda r: r["requirements"].update(secret="do-not-echo"),
                       lambda r: r["readiness"].update(observed={}),
                       lambda r: r["readiness"].update(protocols=[]),
                       lambda r: r["readiness"].update(protocols=["NFS", "NFS"]),
                       lambda r: r["readiness"].update(protocols=["FOREIGN"])):
            request = copy.deepcopy(self.request); mutate(request)
            with self.assertRaises((ValueError, KeyError, TypeError)):
                self.preflight.execute(request)

    def test_volume_schema_rejects_missing_filesystem_ambiguous_or_wrong_geometry(self):
        self.add_volume("FILE_DATA")
        for mutate in (lambda row: row.update(filesystemUuid=None), lambda row: row.update(sizeBytes=True),
                       lambda row: row.update(sizeBytes=0), lambda row: row.update(kind="UNKNOWN"),
                       lambda row: row.update(volumeUuid="not-uuid"),
                       lambda row: row.update(privateBody="secret")):
            request = copy.deepcopy(self.request); mutate(request["readiness"]["volumes"][0])
            with self.assertRaises((ValueError, TypeError)):
                self.preflight.execute(request)
        self.request["readiness"]["volumes"] *= 2
        with self.assertRaises(ValueError): self.preflight.execute(self.request)

    def test_active_sessions_fail_closed_with_maintenance_alternative_not_false_drain(self):
        self.probe.sessions = 1
        result = self.preflight.execute(self.request)
        self.assertIn("ACTIVE_SESSIONS_REQUIRE_MAINTENANCE", result["blockers"])
        self.assertFalse(result["drainSupported"])
        self.assertEqual("APPROVED_MAINTENANCE_PLAN_REQUIRED", result["maintenanceAlternative"])
        self.request["requirements"] = {**self.requirements, "requireSessionDrain": True}
        self.probe.sessions = 0
        self.assertIn("DRAIN_NOT_IMPLEMENTED", self.preflight.execute(self.request)["blockers"])

    def test_unknown_sessions_health_and_probe_failure_cannot_pass(self):
        for sessions in (None, -1, True):
            self.probe.sessions = sessions
            self.assertIn("SESSION_OBSERVATION_UNAVAILABLE", self.preflight.execute(self.request)["blockers"])
        self.probe.sessions = 0
        for health in ({"NFS": "UNAVAILABLE"}, {"SMB": "READY"}, {"NFS": "STOPPED_AS_APPROVED"}):
            self.probe.health = health
            self.assertIn("PROTOCOL_HEALTH_UNAVAILABLE", self.preflight.execute(self.request)["blockers"])
        self.probe.error = PermissionError()
        self.assertIn("READINESS_OBSERVATION_UNAVAILABLE", self.preflight.execute(self.request)["blockers"])

    def test_wrong_backing_identity_and_no_observation_cannot_pass(self):
        self.add_volume("FILE_DATA")
        for change in ({"volumeUuid": str(uuid.uuid4())}, {"sizeBytes": 1}, {"filesystemUuid": str(uuid.uuid4())},
                       {"mappingStatus": "UNAVAILABLE"}, {"readOnly": False}):
            self.probe.volume_change = change
            self.assertIn("BACKING_VOLUME_UNAVAILABLE", self.preflight.execute(self.request)["blockers"])

    def test_concurrent_generation_change_rejects_even_if_observation_was_good(self):
        self.probe.change = lambda: self.write(self.current, {"instanceUuid": self.instance, "revision": 10})
        with self.assertRaises(ValueError): self.preflight.execute(self.request)
        self.assertFalse(self.store.root.exists())

    def test_legacy_empty_payload_explicitly_reports_old_floors_without_source_authority(self):
        self.current.unlink()
        result = self.store.execute("preflight")
        self.assertTrue(result["compatible"])
        self.assertEqual("LEGACY_FIXED_FLOORS", result["mode"])
        self.assertIsNone(result["instanceUuid"])
        self.assertFalse(self.store.root.exists())
        self.observed["memoryAvailableBytes"] = (256 << 20) - 1
        self.assertFalse(self.store.execute("preflight")["compatible"])

    def test_real_cli_typed_pressure_while_writer_held_never_reacquires_or_creates_lease(self):
        self.request["requirements"] = {**self.requirements, "minimumMemoryAvailableBytes": 1 << 50}
        payload = self.root / "request.json"; payload.write_text(json.dumps(self.request))
        lock = self.root / "writer.lock"; lock.touch(mode=0o600)
        fd = os.open(lock, os.O_RDWR); self.addCleanup(os.close, fd)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        env = dict(os.environ, ABLESTACK_STORAGE_GENERATION_DIR=str(self.gen),
                   ABLESTACK_STORAGE_RESERVATION_DIR=str(self.store.root), ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(lock))
        result = subprocess.run([str(CLI), "operation", "preflight", str(payload)], env=env,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        reply = json.loads(result.stdout)
        self.assertEqual("TYPED_READINESS_V1", reply["mode"])
        self.assertIn("MEMORY_HEADROOM", reply["blockers"])
        self.assertFalse(self.store.root.exists())

    def test_json_duplicates_nonfinite_and_protected_source_replacement_fail_closed(self):
        for raw in ('{"a":1,"a":2}', '{"a":NaN}'):
            with self.assertRaises(ValueError): preflight_json(raw)
        value = self.root / "config.json"; self.write(value, {"enabled": True})
        self.assertTrue(preflight_read(value)["enabled"])
        value.chmod(0o666)
        with self.assertRaises(ValueError): preflight_read(value)
        value.unlink(); value.symlink_to(self.current)
        with self.assertRaises(ValueError): preflight_read(value)


class StorageReadinessObservationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.probe = ReadinessProbe(self.root)
        self.volume, self.fs = str(uuid.uuid4()), str(uuid.uuid4())
        self.scope = {"instanceUuid": str(uuid.uuid4()), "operationUuid": str(uuid.uuid4()), "revision": 2}

    def configure(self, protocol, collection, rows):
        from resource_preflight import PREFLIGHT_CONFIGS
        path = self.root / PREFLIGHT_CONFIGS[protocol][0]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({collection: rows})); path.chmod(0o600)
        return path

    def test_live_tcp_custom_port_and_approved_service_health_are_observed_not_drained(self):
        self.configure("NFS", "exports", [{"state": "Ready", "config": {"listenerGroupPorts": [22049]}}])
        outputs = iter(("ESTAB 0 0 10.0.0.1:22049 10.0.0.2:45678\nESTAB 0 0 10.0.0.1:22 10.0.0.2:1234\n",
                        "LISTEN 0 128 0.0.0.0:22049 0.0.0.0:*\n",
                        "ablestack-storage-ganesha@group.service loaded active running service\n"))
        self.probe.run = lambda args: next(outputs)
        sessions, health = self.probe.protocols(["NFS"])
        self.assertEqual(1, sessions); self.assertEqual({"NFS": "READY"}, health)

    def test_unknown_listener_output_and_malformed_canonical_never_claim_ready(self):
        path = self.configure("NFS", "exports", [{"state": "Ready"}])
        outputs = iter(("", "UNKNOWN 0 1 0.0.0.0:2049 *:*", ""))
        self.probe.run = lambda args: next(outputs)
        with self.assertRaises(ValueError): self.probe.protocols(["NFS"])
        path.write_text(json.dumps({"exports": [None]}))
        with self.assertRaises(ValueError): self.probe.ports("NFS")

    def test_listener_alone_or_service_alone_cannot_claim_healthy(self):
        self.configure("SMB", "shares", [{"state": "Ready"}])
        for listen, units in (("LISTEN 0 1 0.0.0.0:445 *:*\n", ""), ("", "smbd.service loaded active running SMB\n")):
            outputs = iter(("", listen, units)); self.probe.run = lambda args: next(outputs)
            self.assertEqual({"SMB": "UNAVAILABLE"}, self.probe.protocols(["SMB"])[1])

    def test_absent_protocol_is_not_configured_and_nvme_namespace_custom_port_is_included(self):
        outputs = iter(("", "", "")); self.probe.run = lambda args: next(outputs)
        self.assertEqual((0, {"ISCSI": "NOT_CONFIGURED"}), self.probe.protocols(["ISCSI"]))
        self.configure("NVME_OF", "subsystems", [{"namespaces": [{"listenerPorts": "4421"}]}])
        self.assertEqual(({4421}, True), self.probe.ports("NVME_OF"))

    def test_canonical_scope_and_top_level_custom_listener_bindings_are_checked(self):
        path = self.configure("ISCSI", "targets", [{"state": "Ready"}])
        path.write_text(json.dumps({"instanceUuid": self.scope["instanceUuid"], "targets": [{"state": "Ready"}],
                                    "port": 3261, "listeners": [{"port": 3262}]}))
        self.assertEqual(({3261, 3262}, True), self.probe.ports("ISCSI", self.scope))
        foreign = {**self.scope, "instanceUuid": str(uuid.uuid4())}
        with self.assertRaises(ValueError): self.probe.ports("ISCSI", foreign)
        value = json.loads(path.read_text()); value["listeners"][0]["port"] = True
        path.write_text(json.dumps(value))
        with self.assertRaises(ValueError): self.probe.ports("ISCSI", self.scope)

    def disk_fixture(self, kind):
        disk = {"name": "vdb", "path": "/dev/vdb", "type": "disk", "serial": self.volume.replace("-", "")[:20],
                "size": 8 << 30, "fstype": "xfs" if kind == "FILE_DATA" else None,
                "uuid": self.fs if kind == "FILE_DATA" else None}
        mounts = [{"source": "/dev/vdb", "target": "/srv/ablestack-storage/volumes/" + self.volume,
                   "fstype": "xfs", "options": "rw", "uuid": self.fs}] if kind == "FILE_DATA" else []
        request = [{"volumeUuid": self.volume, "sizeBytes": 8 << 30, "kind": kind,
                    "filesystemUuid": self.fs if kind == "FILE_DATA" else None}]
        return disk, mounts, request

    def run_disk(self, disk, mounts, request, signatures=None, blkid=None):
        def run(args):
            if args[0] == "lsblk": return json.dumps({"blockdevices": [disk]})
            if args[0] == "findmnt": return json.dumps({"filesystems": mounts})
            if args[0] == "wipefs": return json.dumps({"signatures": signatures or []})
            if args[0] == "blkid": return "UUID=" + (blkid or self.fs) + "\nTYPE=xfs\n"
            raise AssertionError("non-readonly observation")
        self.probe.run = run
        with patch("volume_identity.glob.glob", return_value=[]):
            return self.probe.volumes(self.scope, request)

    def test_file_data_exact_live_uuid_and_managed_mount_required(self):
        disk, mounts, request = self.disk_fixture("FILE_DATA")
        self.assertEqual("EXACT", self.run_disk(disk, mounts, request)[0]["mappingStatus"])
        self.assertEqual("UNAVAILABLE", self.run_disk(disk, mounts, request, blkid=str(uuid.uuid4()))[0]["mappingStatus"])
        self.assertEqual("UNAVAILABLE", self.run_disk(disk, [], request)[0]["mappingStatus"])

    def test_raw_client_filesystem_is_allowed_but_any_host_child_mount_is_blocked(self):
        disk, mounts, request = self.disk_fixture("BLOCK_RAW")
        disk["children"] = [{"name": "vdb1", "path": "/dev/vdb1", "type": "part", "size": 4 << 30,
                             "fstype": "ntfs", "uuid": self.fs}]
        self.assertEqual("EXACT", self.run_disk(disk, mounts, request, [{"type": "gpt"}])[0]["mappingStatus"])
        mounts = [{"source": "UUID=" + self.fs, "uuid": self.fs, "target": "/foreign"}]
        self.assertEqual("UNAVAILABLE", self.run_disk(disk, mounts, request, [{"type": "gpt"}])[0]["mappingStatus"])

    def test_unused_nonblank_size_drift_root_and_foreign_serial_fail_closed(self):
        disk, mounts, request = self.disk_fixture("UNUSED")
        self.assertEqual("EXACT", self.run_disk(disk, mounts, request)[0]["mappingStatus"])
        self.assertEqual("UNAVAILABLE", self.run_disk(disk, mounts, request, [{"type": "xfs"}])[0]["mappingStatus"])
        for mutate in (lambda d: d.update(size=1), lambda d: d.update(mountpoint="/"),
                       lambda d: d.update(serial=str(uuid.uuid4()))):
            changed = copy.deepcopy(disk); mutate(changed)
            with self.assertRaises((RuntimeError, ValueError)): self.run_disk(changed, mounts, request)

    def test_readonly_child_deadline_and_output_bound(self):
        with self.assertRaises(ValueError):
            self.probe.run([sys.executable, "-c", "print('x'*100)"], limit=5)
        self.probe.deadline = self.probe.clock() + .05
        with self.assertRaises(TimeoutError):
            self.probe.run([sys.executable, "-c", "import time;time.sleep(3)"])

    def test_all_canonical_inline_definitions_are_embedded_without_external_imports(self):
        text = CLI.read_text()
        start = text.index("resource_reservation_command()")
        body = text[start:text.index("\nPYRESOURCERESERVATION", start) + len("\nPYRESOURCERESERVATION")]
        embedded = body.split("<<'PYRESOURCERESERVATION'\n", 1)[1].rsplit("\nPYRESOURCERESERVATION", 1)[0]
        tree = ast.parse(embedded)
        inline = {node.name: ast.dump(node, include_attributes=False) for node in tree.body
                  if isinstance(node, (ast.FunctionDef, ast.ClassDef))}
        for name in ("volume_identity.py", "root_data_inspection.py", "resource_preflight.py", "resource_reservation.py"):
            canonical = ast.parse((LIB / name).read_text())
            for node in canonical.body:
                if isinstance(node, (ast.FunctionDef, ast.ClassDef)):
                    self.assertEqual(ast.dump(node, include_attributes=False), inline[node.name], node.name)
        self.assertNotIn("from resource_preflight import", embedded)
        self.assertNotIn("from volume_identity import", embedded)
        self.assertNotIn("from root_data_inspection import", embedded)


if __name__ == "__main__":
    unittest.main()
