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
"""Actual packaged health/cache producer contract; requires private OS directories.

Run safely with the sibling run-preparation-health-wire-isolated.py launcher.
The generated fixture is consumed by Java/API and UI readiness regression tests.
No health success or BOOT receipt is invented: health, generation, writer-idle and
maintenance are collected from the packaged CLI; the journal comes from the real
BootCoordinator and is projected by the exact monitor cache writer function.
The monitor network-observation prerequisite is explicitly seeded; this test is
not evidence that network connectivity was validated. Prior-BOOT/cache/journal
faults are controlled inputs, while their resulting producer bytes are captured.
"""
import copy
import hashlib
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
CLI = ROOT / "systemvm/debian/usr/local/bin/ablestack-storagectl"
MONITOR = ROOT / "systemvm/debian/usr/local/bin/ablestack-storage-monitor"
LIBRARY = ROOT / "systemvm/debian/usr/local/lib/ablestack-storage"


class StoragePreparationHealthWireTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        private = set()
        for line in Path("/proc/self/mountinfo").read_text().splitlines():
            if " - tmpfs " in line:
                private.add(line.split()[4])
        if not {"/etc", "/var", "/run", "/tmp"} <= private:
            raise unittest.SkipTest("Native health requires bwrap private /etc,/var,/run,/tmp")
        # Root/repository must remain read-only even inside the private OS scope.
        if not all(os.statvfs(str(path)).f_flag & os.ST_RDONLY for path in ("/", ROOT)):
            raise AssertionError("Native health fixture repository/root are not read-only")
        sys.path.insert(0, str(LIBRARY))
        from boot_reconcile import BootCoordinator
        cls.temporary = tempfile.TemporaryDirectory(prefix="preparation-health-wire-")
        cls.work = Path(cls.temporary.name)
        cls.cache = cls.work / "cache"
        cls.cache.mkdir(mode=0o700)
        cls.journal = cls.work / "progress/status.json"
        cls.boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        cls.previous_boot = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        cls.environment = dict(os.environ,
                               ABLESTACK_STORAGE_MONITOR_CACHE_DIR=str(cls.cache),
                               ABLESTACK_STORAGE_BOOT_PROGRESS_JOURNAL=str(cls.journal))
        cls.samples = {}

        # The real CLI performs its ordinary cache-miss live collection.
        cold = cls.native("health")
        cls.collect("LIVE_COLD", cold, "WAITING_BOOT", "HEALTH_BOOT_METADATA_PENDING")
        cls.write_cache(cold)
        cls.collect("CURRENT_BOOT_NO_RECEIPT", cls.native("health"),
                    "WAITING_BOOT", "BOOT_RECONCILE_STATUS_PENDING")

        coordinator = BootCoordinator(
            "a" * 64, {"probeTasks": 0, "endpointModeGroups": 0,
                       "configuredProtocolCount": 0, "concurrency": 1},
            boot_id=cls.boot, journal=cls.journal, checkpoint_dir=cls.work / "receipts",
            configured_protocols=())
        cls.write_cache(cold)
        cls.collect("CURRENT_BOOT_RECONCILING", cls.native("health"),
                    "WAITING_BOOT", "BOOT_RECONCILING")
        coordinator.finish(skipped=True)
        cls.write_cache(cold)
        ready = cls.native("health")
        cls.collect("CURRENT_BOOT_READY", ready, "READY", "CURRENT_BOOT_READY")

        cls.write_cache(cold, network_error=True)
        cls.collect("CURRENT_BOOT_READY_NETWORK_ERROR", cls.native("health"),
                    "OBSERVATION_UNAVAILABLE", "HEALTH_OBSERVATION_FAILED",
                    faultInput="Only monitor network observation reports ADDRESS_MISMATCH; BOOT READY is real")
        cls.write_cache(cold)

        # The coordinator also publishes minimal progress-only health before the monitor.
        # Its real producer may retain a READY receipt while explicit network failure
        # changes outer success/status, which the API must not authorize as READY.
        cls.write_network(error=False)
        with patch.dict(os.environ, {"ABLESTACK_STORAGE_MONITOR_CACHE_DIR": str(cls.cache)}):
            publisher = BootCoordinator(
                "b" * 64, {"probeTasks": 0, "endpointModeGroups": 0,
                           "configuredProtocolCount": 0, "concurrency": 1},
                boot_id=cls.boot, journal=cls.work / "publisher/status.json",
                checkpoint_dir=cls.work / "publisher-receipts",
                configured_protocols=(), publish_cache=True)
            cls.collect("COORDINATOR_PROGRESS_ONLY_RECONCILING", cls.native("health"),
                        "WAITING_BOOT", "BOOT_RECONCILING")
            publisher.finish(skipped=True)
            cls.collect("COORDINATOR_PROGRESS_ONLY_READY", cls.native("health"),
                        "READY", "CURRENT_BOOT_READY")
            cls.write_network(error=True)
            publisher.heartbeat()
            cls.collect("COORDINATOR_READY_NETWORK_ERROR", cls.native("health"),
                        "OBSERVATION_UNAVAILABLE", "HEALTH_OBSERVATION_FAILED",
                        faultInput="Only coordinator network observation reports ADDRESS_MISMATCH; BOOT READY is real")
        cls.write_cache(cold)

        cls.collect("GENERATION_BOOT_CHANGE", ready, "WAITING_BOOT",
                    "BOOT_CHANGED_DURING_OBSERVATION",
                    faultInput="Only the earlier generation BOOT is seeded as the previous BOOT")
        cls.samples["GENERATION_BOOT_CHANGE"]["before"]["bootId"] = cls.previous_boot

        previous = copy.deepcopy(ready)
        previous.pop("cached", None)
        previous["bootId"] = cls.previous_boot
        previous["bootReconcile"]["bootId"] = cls.previous_boot
        cls.cache.joinpath("health.json").write_text(json.dumps(previous))
        cls.collect("PREVIOUS_BOOT_CACHE", cls.native("health"),
                    "WAITING_BOOT", "HEALTH_CACHE_PREVIOUS_BOOT",
                    faultInput="Only a valid previous BOOT identity is seeded in the existing cache")

        expired = copy.deepcopy(ready)
        expired.pop("cached", None)
        expired["generatedEpoch"] = time.time() - 61
        cls.cache.joinpath("health.json").write_text(json.dumps(expired))
        cls.collect("STALE_CACHE_LIVE_FALLBACK", cls.native("health"),
                    "WAITING_BOOT", "HEALTH_BOOT_METADATA_PENDING",
                    faultInput="Only the cache generatedEpoch is expired; CLI chooses live fallback")

        cls.cache.joinpath("health.json").write_text("{")
        cls.collect("MALFORMED_CACHE_LIVE_FALLBACK", cls.native("health"),
                    "WAITING_BOOT", "HEALTH_BOOT_METADATA_PENDING",
                    faultInput="Only cache JSON is broken; CLI chooses live fallback")

        cls.journal.write_text("{")
        cls.journal.chmod(0o600)
        cls.write_cache(cold)
        cls.collect("CURRENT_BOOT_RECEIPT_READ_ERROR", cls.native("health"),
                    "OBSERVATION_UNAVAILABLE", "BOOT_OBSERVATION_INVALID",
                    faultInput="Only protected BOOT journal JSON is broken; monitor records read error")
        cls.collect("EXPIRED_READY_OBSERVATION", ready,
                    "OBSERVATION_UNAVAILABLE", "HEALTH_OBSERVATION_STALE",
                    evaluatedAtEpoch=ready["generatedEpoch"] + 31)

    @classmethod
    def tearDownClass(cls):
        destination = os.environ.get("PREPARATION_HEALTH_WIRE_FIXTURE_OUTPUT")
        if destination:
            fixture = {"schemaVersion": 1,
                       "producer": {"cli": str(CLI.relative_to(ROOT)),
                                    "cliSha256": hashlib.sha256(CLI.read_bytes()).hexdigest(),
                                    "monitor": str(MONITOR.relative_to(ROOT)),
                                    "monitorSha256": hashlib.sha256(MONITOR.read_bytes()).hexdigest(),
                                    "method": "packaged CLI, exact monitor cache writer and real BootCoordinator publish_cache",
                                    "noProtocolMutation": True,
                                    "controlledInputs": ["previous BOOT cache identity", "cache timestamp/JSON",
                                                         "BOOT journal JSON"]},
                       "samples": list(cls.samples.values())}
            source = CLI.read_text()
            health_body = source[source.index("runtime_health() {"):source.index("\nruntime_inventory() {")]
            output_statement = health_body[health_body.rindex("print(json.dumps({"):].strip()
            fixture["producer"]["bootstrapCompatibility"] = {
                "bootstrapCliSha256": "8e14f84ea70b81ffd54dced38a48e31ff1a7202537557c414dabf28f0c2a5197",
                "liveOutputStatementExactEqual": hashlib.sha256(output_statement.encode()).hexdigest()
                    == "3502af746bd42602fdee4156ad0e34f963b55e002dcbbd0f7582709450000187",
                "liveOutputStatementSha256": hashlib.sha256(output_statement.encode()).hexdigest(),
                "scope": "Live output statement matches installed bootstrap source capture; full bootstrap CLI not executed"}
            fixture["producer"]["controlledInputs"].append(
                "Network observation is seeded only to exercise monitor health cache projection")
            Path(destination).write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n")
        cls.temporary.cleanup()

    @classmethod
    def native(cls, *arguments):
        result = subprocess.run([str(CLI), *arguments], env=cls.environment,
                                capture_output=True, text=True, timeout=20)
        if result.returncode != 0:
            raise AssertionError("Native public contract command failed: " +
                                 " ".join(arguments) + ": " + result.stdout + result.stderr)
        return json.loads(result.stdout)

    @classmethod
    def collect(cls, name, health, status, reason, **details):
        cls.samples[name] = {
            "name": name, "health": health,
            "before": cls.native("operation", "generation", "status"),
            "after": cls.native("operation", "generation", "status"),
            "idle": cls.native("operation", "writer-idle"),
            "maintenance": cls.native("operation", "maintenance", "status"),
            "evaluatedAtEpoch": details.pop("evaluatedAtEpoch", time.time()),
            "expected": {"ready": status == "READY", "status": status, "reason": reason},
            **details}

    @classmethod
    def write_network(cls, error=False):
        network = {"schemaVersion": 1, "kind": "SHAREDFS_NETWORK_READINESS",
                   "success": not error, "status": "ERROR" if error else "OK", "mode": "DHCP",
                   "observedEpoch": time.time(), "bootId": cls.boot,
                   "expected": {"mode": "DHCP", "dns": []}, "actual": {},
                   "mismatches": ["ADDRESS_MISMATCH"] if error else []}
        path = cls.cache / "network.json"
        path.write_text(json.dumps(network))
        path.chmod(0o600)

    @classmethod
    def write_cache(cls, live, network_error=False):
        cls.write_network(error=network_error)
        # Independent producer scenario: no preceding corrupt output may block this writer.
        cls.cache.joinpath("health.json").unlink(missing_ok=True)
        input_path = cls.work / "health-input.json"
        input_path.write_text(json.dumps(live))
        source = MONITOR.read_text()
        start = source.index("boot_monitor_json() {\n")
        end = source.index("\nwrite_json() {\n", start)
        # Execute the producer function verbatim, not a copied Python projection.
        invocation = source[start:end] + '\nboot_monitor_json write health 30 "$1" "$2" "$3"\n'
        result = subprocess.run(["/bin/bash", "-euo", "pipefail", "-c", invocation,
                                 "health-wire-cache-writer", str(cls.cache), str(input_path),
                                 str(cls.cache / "network.json")],
                                env=cls.environment, capture_output=True, text=True, timeout=20)
        if result.returncode != 0:
            raise AssertionError(result.stdout + result.stderr)

    def test_live_and_expired_fallback_omit_boot_metadata(self):
        for name in ("LIVE_COLD", "STALE_CACHE_LIVE_FALLBACK", "MALFORMED_CACHE_LIVE_FALLBACK"):
            with self.subTest(name=name):
                health = self.samples[name]["health"]
                self.assertIs(health["success"], True)
                self.assertIn(health["status"], ("ok", "degraded"))
                self.assertIsInstance(health["generatedEpoch"], (int, float))
                self.assertIsInstance(health["observedEpoch"], (int, float))
                for key in ("bootId", "bootReconcile", "cached"):
                    self.assertNotIn(key, health)

    def test_monitor_attaches_current_boot_before_journal_exists(self):
        health = self.samples["CURRENT_BOOT_NO_RECEIPT"]["health"]
        self.assertEqual(self.boot, health["bootId"])
        self.assertIs(health["cached"], True)
        self.assertNotIn("bootReconcile", health)
        self.assertNotIn("bootReconcileReadError", health)

    def test_real_boot_progress_moves_from_reconciling_to_ready_skipped(self):
        progress = self.samples["CURRENT_BOOT_RECONCILING"]["health"]
        ready = self.samples["CURRENT_BOOT_READY"]["health"]
        self.assertEqual("RECONCILING", progress["bootReconcile"]["status"])
        self.assertEqual("reconciling", progress["status"])
        self.assertEqual("READY", ready["bootReconcile"]["status"])
        self.assertEqual("SKIPPED", ready["bootReconcile"]["phase"])
        self.assertIs(ready["bootReconcile"]["runtimeReady"], True)
        self.assertEqual(self.boot, ready["bootReconcile"]["bootId"])
        self.assertTrue(all(not item["configured"] for item in
                            ready["bootReconcile"]["protocols"].values()))

    def test_cli_does_not_rebind_previous_boot_cache(self):
        health = self.samples["PREVIOUS_BOOT_CACHE"]["health"]
        self.assertIs(health["cached"], True)
        self.assertEqual(self.previous_boot, health["bootId"])
        self.assertNotEqual(self.boot, health["bootId"])
        self.assertEqual(self.previous_boot, health["bootReconcile"]["bootId"])

    def test_monitor_marks_invalid_journal_without_inventing_receipt(self):
        health = self.samples["CURRENT_BOOT_RECEIPT_READ_ERROR"]["health"]
        self.assertEqual(self.boot, health["bootId"])
        self.assertIs(health["bootReconcileReadError"], True)
        self.assertNotIn("bootReconcile", health)

    def test_real_coordinator_publishes_supported_progress_only_wire_shapes(self):
        for name, expected_status in (("COORDINATOR_PROGRESS_ONLY_RECONCILING", "RECONCILING"),
                                      ("COORDINATOR_PROGRESS_ONLY_READY", "READY")):
            with self.subTest(name=name):
                health = self.samples[name]["health"]
                self.assertEqual("boot-reconcile", health["collector"])
                self.assertEqual(expected_status, health["bootReconcile"]["status"])
                self.assertEqual(self.boot, health["bootId"])
                self.assertIs(health["success"], True)
                self.assertIs(health["cached"], True)
                self.assertNotIn("observedEpoch", health)
                self.assertNotIn("services", health)

    def test_explicit_network_error_keeps_real_ready_receipt_but_outer_failure(self):
        for name in ("CURRENT_BOOT_READY_NETWORK_ERROR", "COORDINATOR_READY_NETWORK_ERROR"):
            with self.subTest(name=name):
                health = self.samples[name]["health"]
                self.assertEqual("READY", health["bootReconcile"]["status"])
                self.assertIs(health["bootReconcile"]["runtimeReady"], True)
                self.assertIs(health["success"], False)
                self.assertEqual("error", health["status"])
                self.assertIs(health["networkReadiness"]["success"], False)
                self.assertEqual("ERROR", health["networkReadiness"]["status"])
                self.assertEqual(["ADDRESS_MISMATCH"], health["networkReadiness"]["mismatches"])

    def test_live_output_statement_remains_compatible_with_installed_bootstrap(self):
        source = CLI.read_text()
        health_body = source[source.index("runtime_health() {"):source.index("\nruntime_inventory() {")]
        statement = health_body[health_body.rindex("print(json.dumps({"):].strip()
        # Captured from installed 8e14 bootstrap; no imaginary BOOT field on live health.
        self.assertEqual("3502af746bd42602fdee4156ad0e34f963b55e002dcbbd0f7582709450000187",
                         hashlib.sha256(statement.encode()).hexdigest())

    def test_actual_auxiliary_producers_preserve_unconfigured_state(self):
        for sample in self.samples.values():
            with self.subTest(name=sample["name"]):
                for key in ("before", "after"):
                    self.assertIs(sample[key]["success"], True)
                    self.assertIs(sample[key]["generationSupported"], True)
                    expected_boot = self.previous_boot if sample["name"] == "GENERATION_BOOT_CHANGE" and key == "before" else self.boot
                    self.assertEqual(expected_boot, sample[key]["bootId"])
                    self.assertIsNone(sample[key]["pendingOperationUuid"])
                self.assertIs(sample["idle"]["writerIdle"], True)
                self.assertIs(sample["maintenance"]["bootHeld"], False)


if __name__ == "__main__":
    unittest.main()

