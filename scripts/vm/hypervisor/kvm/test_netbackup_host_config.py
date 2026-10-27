#!/usr/bin/env python3
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

"""NetBackup provisioning mode, configuration scope, and input validation regressions."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("netbackup_host_config", Path(__file__).with_name("netbackup-host-config.py"))
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


class NetBackupConfigurationTest(unittest.TestCase):
    def test_policy_config_records_encrypted_secret_and_key_references(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            args = self.arguments("--vm-schedule-backup", "false")
            args.kvm_hostname = "host-test"
            with patch.object(config, "SECRET_OUTPUT_DIR", root / "secrets with spaces"), \
                    patch.object(config, "SECRET_KEY_FILE", root / "host.key"):
                path = root / "policy.conf"
                config.write_config_file(path, args)
            values = dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line and not line.startswith("#"))
            self.assertEqual([str(root / "secrets with spaces/secret.enc")], shlex.split(values["MOLD_SECRET_FILE"]))
            self.assertEqual([str(root / "host.key")], shlex.split(values["SECRET_KEY_FILE"]))
            self.assertNotIn("ADMIN_SECRETKEY=", path.read_text())

    def test_policy_secret_decryption_receives_its_configured_key_without_export(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            secret = root / "custom secret.enc"
            secret.touch()
            helper = root / "helper"
            helper.write_text('#!/bin/sh\nprintf "%s\\n%s" "$SECRET_KEY_FILE" "$2" > "$TEST_ROOT/used"\nprintf synthetic-secret\n')
            helper.chmod(0o700)
            script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
SECRET_HELPER="$TEST_ROOT/helper"
MOLD_SECRET_FILE="$TEST_ROOT/custom secret.enc"
SECRET_KEY_FILE="$TEST_ROOT/custom host.key"
ADMIN_SECRETKEY=""
load_admin_secretkey
[[ "$ADMIN_SECRETKEY" == synthetic-secret ]]
'''
            import os
            result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                      env=dict(os.environ, TEST_ROOT=str(root), TEST_LIBRARY=str(Path(config.__file__).with_name("netbackup-host-common.sh"))))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([str(root / "custom host.key"), str(secret)], (root / "used").read_text().splitlines())
            self.assertEqual("", result.stdout)

    def policy(self):
        return {"policyType": "Standard", "policyAttributes": {"jobLimit": 1, "active": False},
                "clients": [{"hostName": "host-test"}], "schedules": [
                    {"scheduleName": "schedule", "backupType": "Full Backup"},
                    {"scheduleName": "mold-transfer", "backupType": "User Backup",
                     "startWindow": [{"dayOfWeek": 1, "startSeconds": 0, "durationSeconds": 604800}]}]}

    def test_policy_check_uses_raw_api_auth_and_accepts_serial_limits_during_configuration(self):
        args = self.arguments("--vm-schedule-backup", "true")
        response = {"data": {"attributes": {"policy": self.policy()}}}
        with patch.object(config, "urlopen", return_value=io.BytesIO(json.dumps(response).encode())) as request, \
                patch.object(config, "get_configuration_value", return_value="1"), contextlib.redirect_stderr(io.StringIO()):
            config.check_netbackup_policy(args)
        self.assertEqual("synthetic-netbackup-key", request.call_args.args[0].get_header("Authorization"))

    def test_policy_check_rejects_wrong_schedule_type_client_or_empty_transfer_window(self):
        for invalid in ("type", "client", "window"):
            policy = self.policy()
            if invalid == "type":
                policy["schedules"][1]["backupType"] = "Full Backup"
            elif invalid == "client":
                policy["clients"][0]["hostName"] = "another-host"
            else:
                policy["schedules"][1]["startWindow"] = []
            response = {"data": {"attributes": {"policy": policy}}}
            with self.subTest(invalid=invalid), patch.object(config, "urlopen", return_value=io.BytesIO(json.dumps(response).encode())), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                config.check_netbackup_policy(self.arguments())

    def test_schedule_specific_transfer_hooks_do_not_enter_parent_hooks(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(config, "HOOK_OUTPUT_DIR", Path(directory)):
            config.write_transfer_hooks("prod-host")
            for name in ("bpstart_notify", "bpend_notify"):
                file = Path(directory) / (name + ".prod-host.mold-transfer")
                result = subprocess.run([str(file), "client", "prod-host", "mold-transfer", "UBAK", "0"],
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode)
                self.assertEqual("", result.stdout)

    def arguments(self, *options):
        argv = ["netbackup-host-config.py", "--policy-name", "host-test", "--mold-url", "http://mold.test/client/api",
                "--admin-apikey", "synthetic-key", "--admin-secretkey", "synthetic-secret",
                "--netbackup-url", "https://netbackup.test/netbackup", "--netbackup-apikey", "synthetic-netbackup-key"]
        with patch.object(sys, "argv", argv + list(options)), patch.object(config.socket, "gethostname", return_value="host-test"):
            return config.parse_args()

    def test_host_context_uses_the_local_host_independently_of_the_policy_name(self):
        args = self.arguments("--policy-name", "prod-weekly")
        hosts = [{"name": "prod-weekly", "zoneid": "wrong-zone", "clusterid": "wrong-cluster"},
                 {"name": "host-test", "zoneid": "zone", "clusterid": "cluster"}]
        with patch.object(config, "invoke_mold_api", return_value={"listhostsresponse": {"host": hosts}}) as api, \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(("zone", "cluster"), config.resolve_host_context(args))
        self.assertEqual("host-test", api.call_args.args[2]["name"])
        self.assertEqual("prod-weekly", args.policy_name)

    def test_host_ip_override_saves_the_exact_mold_host_name(self):
        args = self.arguments("--policy-name", "prod-weekly", "--kvm-ip", "192.0.2.2")
        hosts = [{"name": "host-test", "ipaddress": "192.0.2.1", "zoneid": "wrong-zone", "clusterid": "wrong-cluster"},
                 {"name": "Mold-Host-2", "ipaddress": "192.0.2.2", "zoneid": "zone", "clusterid": "cluster"}]
        with patch.object(config, "invoke_mold_api", return_value={"listhostsresponse": {"host": hosts}}), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(("zone", "cluster"), config.resolve_host_context(args))
        self.assertEqual("Mold-Host-2", args.kvm_hostname)

    def test_explicit_host_name_is_used_instead_of_the_local_name(self):
        args = self.arguments("--policy-name", "prod-weekly", "--kvm-hostname", "mold-host")
        host = {"name": "mold-host", "zoneid": "zone", "clusterid": "cluster"}
        with patch.object(config, "invoke_mold_api", return_value={"listhostsresponse": {"host": host}}), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(("zone", "cluster"), config.resolve_host_context(args))

    def test_unmatched_or_ambiguous_hosts_never_fall_back_to_the_first_result(self):
        args = self.arguments("--policy-name", "prod-weekly")
        for names in (("prod-weekly",), ("host-test", "host-test")):
            hosts = [{"name": name, "zoneid": "zone", "clusterid": "cluster"} for name in names]
            with self.subTest(names=names), \
                    patch.object(config, "invoke_mold_api", return_value={"listhostsresponse": {"host": hosts}}), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                config.resolve_host_context(args)

    def test_runtime_selects_the_configured_mold_host_and_keeps_the_policy_name(self):
        script = r'''
set -euo pipefail
source "$1"
MOLD_VM_CACHE_FILE="$2/cache"
KVM_HOSTNAME="$3"
CLIENT_NAME=netbackup-client
POLICY_NAME=prod-weekly
VM_SCHEDULE_BACKUP=true
log() { :; }
invoke_mold_api() {
  printf '%s\n' "$@" > "${MOLD_VM_CACHE_FILE}.request"
  printf '%s\n' '{"listexternalbackupcandidatesresponse":{"externalbackupcandidates":{"selectionmode":"SCHEDULE","candidate":[]}}}'
}
cache_mold_virtual_machines
cat "${MOLD_VM_CACHE_FILE}.request"
'''
        for host, expected in (("host-test", "host-test"), ("", "netbackup-client")):
            with self.subTest(host=host), tempfile.TemporaryDirectory() as directory:
                result = subprocess.run(["bash", "-c", script, "test", str(Path(__file__).with_name("netbackup-host-common.sh")),
                                         directory, host], capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
                params = result.stdout.splitlines()
                self.assertEqual(expected, params[params.index("hostname") + 1])
                self.assertEqual("prod-weekly", params[params.index("policyid") + 1])

    def configure(self, args, current_incremental="true", chain="8", global_incremental=None, global_plugins="ablestack-netbackup", zone_plugins="ablestack-netbackup"):
        if global_incremental is None:
            global_incremental = current_incremental
        values = {"backup.framework.enabled": "true", "kvm.incremental.backup": current_incremental,
                  "kvm.backup.chain.size": chain, "backup.framework.provider.plugin": "ablestack-netbackup",
                  "backup.plugin.netbackup.url": args.netbackup_url, "backup.plugin.netbackup.apikey": args.netbackup_apikey}
        def read_value(name, *args, **kwargs):
            if name == "backup.framework.provider.plugin":
                return zone_plugins if len(args) > 3 and args[3] else global_plugins
            if name == "kvm.incremental.backup" and not kwargs.get("cluster_id"):
                return global_incremental
            return values[name]

        with patch.object(config, "get_configuration_value", side_effect=read_value) as read, \
                patch.object(config, "update_configuration_value") as update, \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            restart = config.ensure_backup_framework_configuration("zone-id", "cluster-id", args)
        return restart, read, update

    def test_provider_registration_preserves_each_scope_and_adds_ablestack_name(self):
        _, _, update = self.configure(self.arguments(), global_plugins="ablestack-nas", zone_plugins="ablestack-veeam")
        calls = [call.args for call in update.call_args_list if call.args[0] == "backup.framework.provider.plugin"]
        self.assertEqual(2, len(calls))
        self.assertEqual(("backup.framework.provider.plugin", "ablestack-nas,ablestack-netbackup"), calls[0][:2])
        self.assertIsNone(calls[0][-1])
        self.assertEqual(("backup.framework.provider.plugin", "ablestack-veeam,ablestack-netbackup"), calls[1][:2])
        self.assertEqual("zone-id", calls[1][-1])

    def test_existing_invocations_default_to_incremental_without_resetting_chain_size(self):
        args = self.arguments()
        self.assertTrue(args.incremental_backup)
        self.assertIsNone(args.backup_chain_size)
        restart, read, update = self.configure(args, chain="25")
        self.assertFalse(restart)
        update.assert_not_called()
        self.assertNotIn("kvm.backup.chain.size", [call.args[0] for call in read.call_args_list])

    def test_interactive_selection_asks_mode_before_host_filters(self):
        args = self.arguments()
        with patch.object(sys.stdin, "isatty", return_value=True), patch("builtins.input", side_effect=["false", "i-2-1-VM", "i-2-2-VM"]) as read:
            config.configure_vm_selection(args)
        self.assertFalse(args.vm_schedule_backup)
        self.assertEqual("i-2-1-VM", args.vm_include)
        self.assertEqual("i-2-2-VM", args.vm_exclude)
        self.assertIn("per-VM", read.call_args_list[0].args[0])
        self.assertIn("VM_INCLUDE", read.call_args_list[1].args[0])
        self.assertIn("VM_EXCLUDE", read.call_args_list[2].args[0])

    def test_schedule_mode_skips_both_filter_prompts(self):
        args = self.arguments()
        with patch.object(sys.stdin, "isatty", return_value=True), patch("builtins.input", return_value="true") as read:
            config.configure_vm_selection(args)
        self.assertTrue(args.vm_schedule_backup)
        self.assertEqual(("*", "*"), (args.vm_include, args.vm_exclude))
        self.assertEqual(1, read.call_count)

    def test_noninteractive_selection_defaults_to_all_host_vms(self):
        args = self.arguments()
        with patch.object(sys.stdin, "isatty", return_value=False), patch("builtins.input") as read:
            config.configure_vm_selection(args)
        self.assertFalse(args.vm_schedule_backup)
        self.assertEqual(("*", ""), (args.vm_include, args.vm_exclude))
        read.assert_not_called()

    def test_explicit_selection_and_filters_support_unattended_configuration(self):
        args = self.arguments("--vm-schedule-backup", "false", "--vm-include", "i-2-1-VM", "--vm-exclude", "i-2-2-VM")
        with patch.object(sys.stdin, "isatty", return_value=True), patch("builtins.input") as read:
            config.configure_vm_selection(args)
        self.assertEqual(("i-2-1-VM", "i-2-2-VM"), (args.vm_include, args.vm_exclude))
        read.assert_not_called()

    def test_schedule_flag_and_sentinels_are_saved_together(self):
        args = self.arguments("--vm-schedule-backup", "true", "--policy-name", "prod-weekly")
        with patch.object(sys.stdin, "isatty", return_value=False):
            config.configure_vm_selection(args)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "policy.conf"
            config.write_config_file(path, args)
            text = path.read_text()
        self.assertIn("VM_SCHEDULE_BACKUP=true", text)
        self.assertIn('VM_INCLUDE="*"', text)
        self.assertIn('VM_EXCLUDE="*"', text)
        self.assertIn("KVM_HOSTNAME=host-test", text)
        self.assertIn("/bootstrap/prod-weekly", text)

    def test_schedule_mode_rejects_manual_filters(self):
        args = self.arguments("--vm-schedule-backup", "true", "--vm-include", "*")
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            config.configure_vm_selection(args)

    def test_full_mode_updates_global_and_cluster_flags_and_requires_restart(self):
        args = self.arguments("--incremental-backup", "false")
        restart, read, update = self.configure(args)
        self.assertTrue(restart)
        self.assertEqual(2, update.call_count)
        self.assertEqual([("kvm.incremental.backup", "false"), ("kvm.incremental.backup", "false")],
                         [call.args[:2] for call in update.call_args_list])
        self.assertEqual({}, update.call_args_list[0].kwargs)
        self.assertEqual({"cluster_id": "cluster-id"}, update.call_args_list[1].kwargs)
        self.assertNotIn("kvm.backup.chain.size", [call.args[0] for call in read.call_args_list])

    def test_global_flag_is_updated_even_when_cluster_already_matches(self):
        args = self.arguments("--incremental-backup", "false")
        restart, _, update = self.configure(args, current_incremental="false", global_incremental="true")
        self.assertTrue(restart)
        update.assert_called_once_with("kvm.incremental.backup", "false", args.mold_url, args.admin_apikey,
                                       args.admin_secretkey)

    def test_cluster_override_is_updated_even_when_global_already_matches(self):
        args = self.arguments("--incremental-backup", "false")
        restart, _, update = self.configure(args, current_incremental="true", global_incremental="false")
        self.assertTrue(restart)
        update.assert_called_once_with("kvm.incremental.backup", "false", args.mold_url, args.admin_apikey,
                                       args.admin_secretkey, cluster_id="cluster-id")

    def test_rerunning_full_mode_is_idempotent(self):
        restart, _, update = self.configure(self.arguments("--incremental-backup", "false"), current_incremental="false")
        self.assertFalse(restart)
        update.assert_not_called()

    def test_reenabling_incremental_applies_the_requested_chain_size(self):
        args = self.arguments("--incremental-backup", "true", "--backup-chain-size", "4")
        restart, _, update = self.configure(args, current_incremental="false")
        self.assertTrue(restart)
        self.assertEqual([("kvm.incremental.backup", "true"), ("kvm.incremental.backup", "true"), ("kvm.backup.chain.size", "4")],
                         [call.args[:2] for call in update.call_args_list])
        self.assertNotIn("cluster_id", update.call_args_list[0].kwargs)
        self.assertEqual("cluster-id", update.call_args_list[1].kwargs["cluster_id"])
        self.assertNotIn("cluster_id", update.call_args_list[2].kwargs)

    def test_chain_change_alone_does_not_require_restart(self):
        restart, _, update = self.configure(self.arguments("--backup-chain-size", "6"))
        self.assertFalse(restart)
        self.assertEqual(("kvm.backup.chain.size", "6"), update.call_args.args[:2])

    def test_boolean_input_is_case_insensitive(self):
        self.assertFalse(self.arguments("--incremental-backup", "FALSE").incremental_backup)
        self.assertTrue(self.arguments("--incremental-backup", "True").incremental_backup)

    def test_invalid_boolean_is_rejected_during_parsing(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.arguments("--incremental-backup", "invalid")

    def test_full_mode_rejects_chain_option_before_key_or_api_changes(self):
        args = self.arguments("--incremental-backup", "false", "--backup-chain-size", "8")
        with patch.object(config, "ensure_key") as key, patch.object(config, "invoke_mold_api") as api, \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            config.validate_args(args)
        key.assert_not_called()
        api.assert_not_called()

    def test_nonpositive_chain_size_is_rejected(self):
        for value in ("0", "-1"):
            with self.subTest(value=value), patch.object(config, "ensure_key") as key, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                config.validate_args(self.arguments("--backup-chain-size", value))
            key.assert_not_called()

    def test_full_configuration_never_updates_chain_even_if_validation_is_bypassed(self):
        args = self.arguments("--incremental-backup", "false", "--backup-chain-size", "7")
        _, read, update = self.configure(args, current_incremental="false")
        update.assert_not_called()
        self.assertNotIn("kvm.backup.chain.size", [call.args[0] for call in read.call_args_list])

    def provision_with_staging(self, overrides=None, query_failure=False, mount_result=0, mount_error=None):
        args = self.arguments("--vm-schedule-backup", "false")
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            values = {"backup.thirdparty.staging.enable": "true",
                      "backup.thirdparty.staging.mount.path": str(mount),
                      "backup.thirdparty.staging.root.path": str(mount / "staging"),
                      "backup.framework.enabled": "true", "kvm.incremental.backup": "true",
                      "backup.framework.provider.plugin": "ablestack-netbackup",
                      "backup.plugin.netbackup.url": args.netbackup_url,
                      "backup.plugin.netbackup.apikey": args.netbackup_apikey}
            values.update(overrides or {})
            events = []

            def read_value(name, *unused_args, **unused_kwargs):
                if query_failure:
                    config.fail("Synthetic staging query failure")
                return values[name]

            def check_mount(*unused_args, **unused_kwargs):
                events.append("mount")
                if mount_error:
                    raise mount_error
                return subprocess.CompletedProcess("findmnt", mount_result)

            with patch.object(config, "parse_args", return_value=args), \
                    patch.object(sys.stdin, "isatty", return_value=False), \
                    patch.object(config, "BACKUP_STAGING_ROOT", Path(directory) / "bootstrap"), \
                    patch.object(config, "STAGING_MOUNT_PATH", None), \
                    patch.object(config, "get_configuration_value", side_effect=read_value), \
                    patch.object(config.subprocess, "run", side_effect=check_mount) as mounted, \
                    patch.object(config, "resolve_host_context", return_value=("zone-id", "cluster-id")), \
                    patch.object(config, "check_netbackup_policy"), \
                    patch.object(config, "ensure_key", side_effect=lambda *a, **kw: events.append("key")) as key, \
                    patch.object(config, "update_configuration_value") as update, \
                    patch.object(config, "ensure_netbackup_offering") as offering, \
                    patch.object(config, "generate_host_outputs") as output, \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as errors:
                result = 0
                try:
                    config.main()
                except SystemExit as exc:
                    result = exc.code
                self.assertEqual([], list(Path(directory).iterdir()))
            return result, errors.getvalue(), key, update, offering, output, mounted, events

    def assert_staging_preflight_rejected(self, **options):
        result, errors, key, update, offering, output, mounted, events = self.provision_with_staging(**options)
        self.assertNotEqual(0, result)
        key.assert_not_called()
        update.assert_not_called()
        offering.assert_not_called()
        output.assert_not_called()
        return errors, mounted

    def test_disabled_staging_prevents_credentials_mold_updates_and_host_files(self):
        errors, mounted = self.assert_staging_preflight_rejected(
            overrides={"backup.thirdparty.staging.enable": "false"})
        self.assertIn("enable backup.thirdparty.staging.enable", errors)
        mounted.assert_not_called()

    def test_staging_query_failure_prevents_configuration_changes(self):
        errors, mounted = self.assert_staging_preflight_rejected(query_failure=True)
        self.assertIn("staging query failure", errors)
        mounted.assert_not_called()

    def test_invalid_staging_paths_prevent_configuration_changes(self):
        for root in ("", "relative/path", "/outside-staging-mount"):
            with self.subTest(root=root):
                _, mounted = self.assert_staging_preflight_rejected(
                    overrides={"backup.thirdparty.staging.root.path": root})
                mounted.assert_not_called()

    def test_missing_staging_mount_prevents_configuration_changes(self):
        errors, mounted = self.assert_staging_preflight_rejected(mount_result=1)
        self.assertIn("not mounted on this host", errors)
        mounted.assert_called_once()

    def test_unavailable_mount_check_prevents_configuration_changes(self):
        for error in (FileNotFoundError("findmnt"), subprocess.TimeoutExpired("findmnt", 30)):
            with self.subTest(error=type(error).__name__):
                errors, _ = self.assert_staging_preflight_rejected(mount_error=error)
                self.assertIn("Unable to verify the common staging mount", errors)

    def test_valid_staging_allows_normal_provisioning_after_mount_check(self):
        result, errors, key, update, offering, output, mounted, events = self.provision_with_staging()
        self.assertEqual(0, result, errors)
        self.assertLess(events.index("mount"), events.index("key"))
        key.assert_called_once()
        mounted.assert_called_once()
        update.assert_not_called()
        offering.assert_called_once()
        output.assert_called_once()

    def test_host_reconfiguration_migrates_the_daemon_to_a_timer_and_is_retryable(self):
        args = self.arguments("--vm-schedule-backup", "false")
        with patch.object(sys.stdin, "isatty", return_value=False):
            config.configure_vm_selection(args)
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            base = Path(directory)
            service = base / "systemd/netbackup-host-restore-watcher.service"
            timer = service.with_suffix(".timer")
            service.parent.mkdir()
            old_service = "[Service]\nType=simple\nRestart=always\n"
            service.write_text(old_service)
            locations = {
                "HOOK_OUTPUT_DIR": base / "hooks", "CONFIG_OUTPUT_DIR": base / "config",
                "SECRET_OUTPUT_DIR": base / "config/secrets", "BACKUP_STAGING_ROOT": base / "staging",
                "WATCHER_SERVICE_PATH": service, "WATCHER_TIMER_PATH": timer,
                "WATCHER_OUTPUT_PATH": base / "bin/watcher", "RESTORE_NOTIFY_OUTPUT_PATH": base / "bin/notify",
                "WATCHER_CONFIG_PATH": base / "config/watcher.conf", "RESTORE_CONFIG_PATH": base / "config/restore.conf",
                "LOGROTATE_CONFIG_PATH": base / "logrotate/netbackup",
            }
            stack.enter_context(patch.multiple(config, **locations))
            stack.enter_context(patch.object(config.shutil, "which", return_value="/usr/bin/systemctl"))
            stack.enter_context(patch.object(config, "write_encrypted_secret_file"))
            stack.enter_context(patch.object(config, "apply_permissions"))
            stack.enter_context(patch.object(config, "ensure_backup_staging_root"))
            stack.enter_context(patch.object(config, "apply_netbackup_bp_conf", return_value=False))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
            calls = []

            def systemctl(command, **unused):
                calls.append(command)
                if command[1] == "show":
                    kind = "oneshot" if "Type=oneshot" in service.read_text() else "simple"
                    return subprocess.CompletedProcess(command, 0, f"Type={kind}\nActiveState=inactive\n", "")
                if command[1:3] == ["enable", "--now"]:
                    self.assertEqual(timer.name, command[3])
                    self.assertIn("Type=oneshot", service.read_text())
                    self.assertIn("TimeoutStartSec=300", service.read_text())
                    self.assertNotIn("Restart=always", service.read_text())
                    self.assertIn("OnCalendar=minutely", timer.read_text())
                    self.assertIn(f"Unit={service.name}", timer.read_text())
                    self.assertNotIn("POLL_INTERVAL_SECONDS", locations["WATCHER_CONFIG_PATH"].read_text())
                return subprocess.CompletedProcess(command, 0)

            stack.enter_context(patch.object(config.subprocess, "run", side_effect=systemctl))
            config.generate_host_outputs("zone-id", args)
            self.assertEqual(["systemctl", "disable", "--now", service.name], calls[2])
            self.assertEqual(["systemctl", "enable", "--now", timer.name], calls[-1])
            self.assertEqual(old_service, next(service.parent.glob(service.name + ".bak.*")).read_text())
            self.assertIn(f"ExecStart={locations['WATCHER_OUTPUT_PATH']} --once", service.read_text())
            calls.clear()
            config.generate_host_outputs("zone-id", args)
            self.assertEqual(["systemctl", "stop", timer.name], calls[1])
            self.assertEqual(["systemctl", "disable", "--now", service.name], calls[3])
            self.assertEqual(["systemctl", "enable", "--now", timer.name], calls[-1])
            self.assertIn(old_service, [path.read_text() for path in service.parent.glob(service.name + ".bak.*")])

    def test_reconfiguration_waits_for_an_active_oneshot_before_replacing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            service = Path(directory) / "watcher.service"
            timer = service.with_suffix(".timer")
            service.write_text("[Service]\nType=oneshot\n")
            timer.touch()
            result = subprocess.CompletedProcess("systemctl", 0, "Type=oneshot\nActiveState=activating\n", "")
            with patch.object(config, "WATCHER_SERVICE_PATH", service), patch.object(config, "WATCHER_TIMER_PATH", timer), \
                    patch.object(config.shutil, "which", return_value="/usr/bin/systemctl"), \
                    patch.object(config.subprocess, "run", return_value=result) as command, \
                    contextlib.redirect_stderr(io.StringIO()) as errors, self.assertRaises(SystemExit):
                config.pause_watcher_for_configuration()
            self.assertIn("Wait for it to finish", errors.getvalue())
            self.assertIn(["systemctl", "stop", timer.name], [call.args[0] for call in command.call_args_list])
            self.assertFalse(any(call.args[0][1] == "disable" for call in command.call_args_list))
            self.assertEqual("[Service]\nType=oneshot\n", service.read_text())

    def test_activation_failure_can_be_retried_without_enabling_the_direct_service(self):
        with patch.object(config.shutil, "which", return_value="/usr/bin/systemctl"), \
                patch.object(config.subprocess, "run") as command:
            command.side_effect = [subprocess.CompletedProcess("reload", 0),
                                   subprocess.CalledProcessError(1, "enable"),
                                   subprocess.CompletedProcess("reload", 0), subprocess.CompletedProcess("enable", 0)]
            with self.assertRaises(subprocess.CalledProcessError):
                config.enable_watcher_timer()
            with contextlib.redirect_stdout(io.StringIO()):
                config.enable_watcher_timer()
        enable_commands = [call.args[0] for call in command.call_args_list if call.args[0][1] == "enable"]
        self.assertEqual([["systemctl", "enable", "--now", config.WATCHER_TIMER_PATH.name]] * 2, enable_commands)

    def test_timer_migration_retains_custom_history_and_lock_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            state = base / "old history.json"
            lock = base / "old watcher.lock"
            state.write_text('{"initialJobBaselineCompleted": true, "processed": {"123": {}}}')
            lock.touch()
            inode = lock.stat().st_ino
            path = base / "watcher.conf"
            path.write_text(f"STATE_FILE={shlex.quote(str(state))}\nLOCK_FILE={shlex.quote(str(lock))}\nPOLL_INTERVAL_SECONDS=30\n")
            with contextlib.redirect_stdout(io.StringIO()):
                config.write_watcher_config(path, self.arguments())
            values = {line.split("=", 1)[0]: shlex.split(line.split("=", 1)[1])[0]
                      for line in path.read_text().splitlines() if "=" in line}
            self.assertEqual("host-test", values["MOLD_HOST_NAME"])
            self.assertEqual(str(state), values["STATE_FILE"])
            self.assertEqual(str(lock), values["LOCK_FILE"])
            self.assertEqual(inode, lock.stat().st_ino)
            self.assertIn('"123"', state.read_text())
            self.assertNotIn("POLL_INTERVAL_SECONDS", values)

    def test_invalid_existing_history_location_prevents_watcher_config_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "watcher.conf"
            original = 'STATE_FILE="relative/state.json"\n'
            path.write_text(original)
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                config.write_watcher_config(path, self.arguments())
            self.assertEqual(original, path.read_text())
            self.assertEqual([], list(path.parent.glob("*.bak.*")))


if __name__ == "__main__":
    unittest.main()
