#!/usr/bin/env python3
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



"""UI setup regression tests with isolated files and mocked external services."""
import contextlib
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import uuid

import netbackup_host_configuration as ui


class NetBackupHostConfigurationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        for name, value in {"ROOT": self.root, "STATE": self.root / "state.json", "LOCK": self.root / "configuration.lock"}.items():
            self.stack.enter_context(patch.object(ui, name, value))
        for name, value in {"CONFIG_OUTPUT_DIR": self.root / "etc", "HOOK_OUTPUT_DIR": self.root / "bin",
                            "NETBACKUP_BP_CONF_PATH": self.root / "bp.conf", "BACKUP_STAGING_ROOT": self.root / "staging"}.items():
            self.stack.enter_context(patch.object(ui.setup, name, value))
        ui.setup.HOOK_OUTPUT_DIR.mkdir()
        ui.setup.CONFIG_OUTPUT_DIR.mkdir()
        ui.setup.NETBACKUP_BP_CONF_PATH.touch()
        (ui.setup.HOOK_OUTPUT_DIR / "bpbackup").touch()
        self.request = {"action": "CHECK", "operationid": "operation-1", "policyname": "seocho-gfs+01",
                        "hostid": "host-1", "zoneid": "zone-1", "clusterid": "cluster-1",
                        "moldurl": "http://mold.test/client/api", "adminapikey": "admin-api-key-private",
                        "adminsecretkey": "admin-secret-key-private", "netbackupurl": "https://netbackup.test/netbackup",
                        "netbackupapikey": "netbackup-api-key-private", "vmschedulebackup": True, "incrementalbackup": False}
        self.policy = {"policyAttributes": {"active": False}, "backupSelections": {"selections": []}}
        self.stack.enter_context(patch.object(ui.setup, "read_netbackup_policy", side_effect=lambda args: self.policy))
        self.stack.enter_context(patch.object(ui.setup, "resolve_host_context", side_effect=self.identity))
        self.stack.enter_context(patch.object(ui.setup, "check_netbackup_policy"))
        self.staging = self.stack.enter_context(patch.object(ui.setup, "load_common_staging"))
        self.stack.enter_context(patch.object(ui.setup, "get_configuration_value", side_effect=self.setting))
        self.key = self.stack.enter_context(patch.object(ui.setup, "prepare_secret_key"))
        self.offering = self.stack.enter_context(patch.object(ui.setup, "ensure_netbackup_offering"))
        self.files = self.stack.enter_context(patch.object(ui.setup, "generate_host_outputs"))
        self.idle = self.stack.enter_context(patch.object(ui, "no_external_jobs"))
        self.stack.enter_context(patch.object(ui, "timer_active", return_value=True))
        self.update = self.stack.enter_context(patch.object(ui.setup, "update_configuration_value"))

    def test_status_reports_all_policies_without_selecting_only_the_latest_receipt(self):
        for name in ("policy-a", "policy-b"):
            (ui.setup.CONFIG_OUTPUT_DIR / ("netbackup-host-" + name + ".conf")).write_text("NETBACKUP_POLICY_NAME=" + name + "\n")
        value = ui.status()
        self.assertEqual(["policy-a", "policy-b"], value["configuredpolicies"])
        self.assertTrue(value["policyinventorycomplete"])

    def test_status_marks_incomplete_policy_identity_instead_of_reporting_an_empty_inventory(self):
        (ui.setup.CONFIG_OUTPUT_DIR / "netbackup-host-legacy.conf").write_text("KVM_HOSTNAME=host-test\n")
        value = ui.status()
        self.assertFalse(value["policyinventorycomplete"])

    def identity(self, args):
        args.kvm_host_id = "host-1"
        args.kvm_hostname = "host-test"
        args.kvm_ip = "192.0.2.1"
        args.netbackup_client = "host-client"
        return "zone-1", "cluster-1"

    def setting(self, name, *args, **kwargs):
        return {ui.setup.NETBACKUP_VM_SCHEDULE_CONFIG_NAME: str(self.request["vmschedulebackup"]).lower(),
                ui.setup.NETBACKUP_HOOK_TIMEOUT_CONFIG_NAME: "86400",
                "kvm.incremental.backup": str(self.request["incrementalbackup"]).lower(),
                "backup.plugin.netbackup.url": self.request["netbackupurl"],
                "backup.plugin.netbackup.apikey": self.request["netbackupapikey"]}[name]

    def run_worker(self, **changes):
        request = dict(self.request, **changes)
        path = self.root / "request.json"
        path.write_text(json.dumps(request)); path.chmod(0o600)
        ui.atomic_state({"state": "RUNNING", "action": request["action"], "operationid": request["operationid"],
                         "policyname": request["policyname"], "check": []})
        with ui.LOCK.open("a") as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            ui.run_worker(path, os.dup(lock.fileno()))
        self.assertFalse(path.exists())
        text = ui.STATE.read_text()
        for key in ("adminapikey", "adminsecretkey", "netbackupapikey"):
            self.assertNotIn(request[key], text)
        return json.loads(text)

    def test_check_is_read_only_and_does_not_require_an_offering_or_vm_schedule(self):
        result = self.run_worker()
        self.assertEqual("CHECKED", result["state"])
        self.staging.assert_called_once()
        self.assertTrue(self.staging.call_args.kwargs["read_only"])
        for method in (self.key, self.offering, self.files, self.update, self.idle):
            method.assert_not_called()
        self.assertFalse(result["policypathmatched"])

    def test_apply_prepares_offering_and_host_files_without_updating_shared_settings(self):
        expected = str(ui.setup.BACKUP_STAGING_ROOT / "bootstrap" / self.request["policyname"])
        Path(expected).mkdir(parents=True)
        self.policy["backupSelections"]["selections"] = [expected]
        result = self.run_worker(action="APPLY")
        self.assertEqual("COMPLETED", result["state"])
        self.assertTrue(result["policypathmatched"])
        self.assertTrue(result["bootstrappresent"])
        self.offering.assert_called_once()
        self.files.assert_called_once()
        self.update.assert_not_called()

    def test_apply_with_wrong_policy_path_is_not_reported_as_ready(self):
        self.policy["backupSelections"]["selections"] = ["/other/path"]
        result = self.run_worker(action="APPLY")
        self.assertEqual("POLICY_PATH_MISMATCH", result["state"])
        self.assertEqual(["/other/path"], result["policyselections"])
        self.assertFalse(result["policypathmatched"])

    def test_exact_single_selection_is_required_and_live_recheck_can_confirm_it(self):
        expected = str(ui.setup.BACKUP_STAGING_ROOT / "bootstrap" / self.request["policyname"])
        Path(expected).mkdir(parents=True)
        for paths, matched in (([], False), ([expected, "/extra"], False), ([expected + "/"], False), ([expected], True)):
            with self.subTest(paths=paths):
                self.policy["backupSelections"]["selections"] = paths
                result = self.run_worker()
                self.assertEqual(matched, result["policypathmatched"])

    def test_wrong_host_blocks_all_writes(self):
        result = self.run_worker(hostid="other-host")
        self.assertEqual("FAILED", result["state"])
        self.assertIn("selected Mold Host", result["message"])
        self.offering.assert_not_called(); self.files.assert_not_called(); self.key.assert_not_called()

    def test_shared_setting_drift_blocks_apply(self):
        result = self.run_worker(action="APPLY", incrementalbackup=True)
        self.assertEqual("FAILED", result["state"])
        self.assertIn("Shared backup settings changed", result["message"])
        self.files.assert_not_called()

    def test_active_policy_or_client_jobs_block_apply_before_any_writes(self):
        self.policy["policyAttributes"]["active"] = True
        result = self.run_worker(action="APPLY")
        self.assertEqual("FAILED", result["state"])
        self.offering.assert_not_called(); self.files.assert_not_called()
        self.policy["policyAttributes"]["active"] = False
        self.idle.side_effect = ValueError("Finish active jobs")
        result = self.run_worker(action="APPLY")
        self.assertEqual("FAILED", result["state"])
        self.files.assert_not_called()

    def test_failed_helpers_redact_keys_from_receipt(self):
        self.files.side_effect = ValueError("bad " + self.request["adminsecretkey"])
        result = self.run_worker(action="APPLY")
        self.assertEqual("FAILED", result["state"])
        self.assertIn("<hidden>", result["message"])

    def test_request_file_permissions_are_enforced(self):
        path = self.root / "request.json"
        path.write_text(json.dumps(self.request)); path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "private regular file"):
            ui.read_request(path)

    def test_status_distinguishes_missing_receipt_from_worker_outcome_loss(self):
        self.assertEqual("NOT_CONFIGURED", ui.status()["state"])
        ui.atomic_state({"state": "RUNNING", "operationid": "lost-worker"})
        self.assertEqual("UNCONFIRMED", ui.status()["state"])

    def test_start_serializes_operations_and_keeps_credentials_out_of_status(self):
        path = self.root / "request.json"
        path.write_text(json.dumps(self.request)); path.chmod(0o600)
        with ui.LOCK.open("a") as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            with self.assertRaises(BlockingIOError):
                ui.start(path)
        with patch.object(ui.subprocess, "Popen") as launch:
            result = ui.start(path)
        self.assertEqual("RUNNING", result["state"])
        self.assertTrue(launch.call_args.kwargs["start_new_session"])
        self.assertIn("pass_fds", launch.call_args.kwargs)
        for secret in (self.request["adminapikey"], self.request["adminsecretkey"], self.request["netbackupapikey"]):
            self.assertNotIn(secret, ui.STATE.read_text())


class NetBackupHookTimeoutResponseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.conf = self.root / "bp.conf"
        self.original = (b"SERVER = primary\nCLIENT_NAME = host\nBPSTART_TIMEOUT = 86400\n"
                         b"BPEND_TIMEOUT = 86400\nCLIENT_READ_TIMEOUT = 86400\n")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("systemctl", "service"):
            command = self.bin / name
            command.write_text("#!/bin/sh\n"
                               "printf '%s\\n' 'restart stdout: synthetic-private-key'\n"
                               "printf '%s\\n' 'restart stderr: synthetic-private-key' >&2\n"
                               'exit "$TEST_RESTART_EXIT"\n')
            command.chmod(0o700)

    def reset(self):
        self.conf.write_bytes(self.original)
        self.conf.chmod(0o640)
        self.request = dict(operationid=str(uuid.uuid4()), previous=86400, timeout=3600,
                            hostname="host", hostip="192.0.2.1", netbackupurl="https://primary/netbackup",
                            netbackupapikey="synthetic-private-key")

    def invoke(self, action, service="systemctl", restart_exit=0):
        request = self.root / "request.json"
        request.write_text(json.dumps(dict(self.request, action="HOOK_TIMEOUT_" + action)))
        request.chmod(0o600)
        code = textwrap.dedent("""\
            import os
            from pathlib import Path
            import sys
            import netbackup_host_configuration as ui

            root, service = Path(sys.argv[1]), sys.argv[2]
            ui.ROOT = root
            ui.LOCK = root / "configuration.lock"
            ui.setup.NETBACKUP_BP_CONF_PATH = root / "bp.conf"
            ui.setup.shutil.which = lambda name: str(root / "bin" / name) if name == service else None
            ui.status = lambda: dict(state="COMPLETED", configured=True, policyinventorycomplete=True)
            def idle(args):
                print("preflight stdout: synthetic-private-key")
                print("preflight stderr: synthetic-private-key", file=sys.stderr)
            ui.no_external_jobs = idle
            sys.argv = [ui.__file__, "--request", str(root / "request.json")]
            ui.main()
            """)
        # Match Script.executeIgnoreExitValue: Agent parses both streams as one JSON document.
        result = subprocess.run([sys.executable, "-c", code, str(self.root), service],
                                cwd=Path(ui.__file__).parent, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=15,
                                env=dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ.get("PATH", ""),
                                         TEST_RESTART_EXIT=str(restart_exit)))
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertFalse(request.exists())
        value = json.loads(result.stdout)
        self.assertNotIn("synthetic-private-key", result.stdout)
        if action == "CHECK":
            self.request["fingerprint"] = value["fingerprint"]
        return value

    def test_apply_rollback_and_commit_each_return_a_single_json_receipt(self):
        for service in ("systemctl", "service"):
            with self.subTest(service=service):
                self.reset()
                self.assertEqual("CHECKED", self.invoke("CHECK", service)["state"])
                self.assertEqual("APPLIED", self.invoke("APPLY", service)["state"])
                for key in ("BPSTART_TIMEOUT", "BPEND_TIMEOUT", "CLIENT_READ_TIMEOUT"):
                    self.assertIn(key + " = 3600\n", self.conf.read_text())
                self.assertEqual(0o640, self.conf.stat().st_mode & 0o777)
                self.assertEqual("ROLLED_BACK", self.invoke("ROLLBACK", service)["state"])
                self.assertEqual(self.original, self.conf.read_bytes())
                self.assertEqual("CHECKED", self.invoke("CHECK", service)["state"])
                self.assertEqual("APPLIED", self.invoke("APPLY", service)["state"])
                self.assertEqual("COMMITTED", self.invoke("COMMIT", service)["state"])
                self.assertFalse(list((self.root / "hook-timeouts").glob("*.pending.json")))

    def test_failed_apply_or_rollback_keeps_a_json_receipt_and_can_be_recovered(self):
        for service in ("systemctl", "service"):
            for action in ("APPLY", "ROLLBACK"):
                with self.subTest(service=service, action=action):
                    self.reset()
                    self.invoke("CHECK", service)
                    if action == "ROLLBACK":
                        self.invoke("APPLY", service)
                    result = self.invoke(action, service, restart_exit=7)
                    self.assertEqual("FAILED", result["state"])
                    self.assertIn("exit status 7", result["message"])
                    self.assertTrue(list((self.root / "hook-timeouts").glob("*.pending.json")))
                    self.assertEqual("RECOVERED", self.invoke("RECOVER", service)["state"])
                    self.assertEqual(self.original, self.conf.read_bytes())
                    self.assertFalse(list((self.root / "hook-timeouts").glob("*.pending.json")))


if __name__ == "__main__":
    unittest.main()
