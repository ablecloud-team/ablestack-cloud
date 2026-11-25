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
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("netbackup_host_config", Path(__file__).with_name("netbackup_host_setup.py"))
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)


class NetBackupConfigurationTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        patcher = patch.object(config, "CONFIG_OUTPUT_DIR", Path(directory.name))
        patcher.start()
        self.addCleanup(patcher.stop)
        patcher = patch.object(config, "get_configuration_value", side_effect=lambda key, *args, **kwargs:
                              "86400" if key == config.NETBACKUP_HOOK_TIMEOUT_CONFIG_NAME else "true")
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_policy_config_records_encrypted_secret_and_key_references(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            args = self.arguments("--vm-schedule-backup", "false")
            args.kvm_hostname = "host-test"
            args.kvm_host_id = "host-uuid"
            with patch.object(config, "SECRET_OUTPUT_DIR", root / "secrets with spaces"), \
                    patch.object(config, "SECRET_KEY_FILE", root / "host.key"):
                path = root / "policy.conf"
                config.write_config_file(path, args)
            values = dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line and not line.startswith("#"))
            self.assertEqual([str(root / "secrets with spaces/secret.enc")], shlex.split(values["MOLD_SECRET_FILE"]))
            self.assertEqual([str(root / "host.key")], shlex.split(values["SECRET_KEY_FILE"]))
            self.assertEqual([args.policy_name], shlex.split(values["NETBACKUP_POLICY_NAME"]))
            self.assertEqual([args.kvm_host_id], shlex.split(values["KVM_HOST_ID"]))
            self.assertNotIn("ADMIN_SECRETKEY=", path.read_text())

    def test_client_notify_and_read_limits_use_global_hook_timeout_and_preserve_other_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'bp.conf'
            path.write_text('SERVER = primary\nBPSTART_TIMEOUT = 300\nBPSTART_TIMEOUT = 600\nCLIENT_READ_TIMEOUT = 300\n')
            with patch.object(config, 'NETBACKUP_BP_CONF_PATH', path):
                self.assertTrue(config.apply_netbackup_bp_conf(172800))
            values = path.read_text()
            self.assertIn('SERVER = primary\n', values)
            for key in ('BPSTART_TIMEOUT', 'BPEND_TIMEOUT', 'CLIENT_READ_TIMEOUT'):
                self.assertEqual(1, values.count(key + ' = '))
                self.assertIn(key + ' = 172800\n', values)
            for key in ('CLIENT_CONNECT_TIMEOUT', 'SERVER_CONNECT_TIMEOUT'):
                self.assertIn(key + ' = 1800\n', values)

    def test_host_setup_reads_global_hook_timeout_and_rejects_invalid_values_before_writes(self):
        args = self.arguments()
        for value in ('86400', '172800', '1'):
            with patch.object(config, 'get_configuration_value', return_value=value) as read:
                self.assertEqual(int(value), config.read_hook_timeout(args))
                read.assert_called_once_with(config.NETBACKUP_HOOK_TIMEOUT_CONFIG_NAME, args.mold_url, args.admin_apikey, args.admin_secretkey)
        for value in (None, '', '0', '-1', 'bad', '1.5', 1.5, True, '2147483648'):
            with self.subTest(value=value), patch.object(config, 'get_configuration_value', return_value=value), \
                    patch.object(config, 'apply_netbackup_bp_conf') as apply, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    config.generate_host_outputs('zone-1', args)
                apply.assert_not_called()

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
        args.kvm_hostname = "host-test"
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
        with patch.object(sys, "argv", argv + list(options)):
            return config.parse_args()

    def test_private_credentials_file_can_replace_command_line_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "credentials.json"
            values = dict(moldurl="http://mold.test/client/api", adminapikey="synthetic-key",
                          adminsecretkey="synthetic-secret", netbackupurl="https://netbackup.test/netbackup",
                          netbackupapikey="synthetic-netbackup-key")
            path.write_text(json.dumps(values)); path.chmod(0o600)
            argv = ["netbackup-host-config.py", "--host-only", "--check-only", "--policy-name", "host-test",
                    "--credentials-file", str(path)]
            with patch.object(sys, "argv", argv):
                args = config.parse_args()
            self.assertEqual(values["adminsecretkey"], args.admin_secretkey)
            self.assertEqual(values["netbackupurl"], args.netbackup_url)
            self.assertTrue(args.check_only)
            self.assertIsNone(args.incremental_backup)
            for mode in (0o644, 0o640, 0o400):
                path.chmod(mode)
                with patch.object(sys, "argv", argv), contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit):
                        config.parse_args()
            path.chmod(0o600)
            link = Path(directory) / "credentials-link.json"
            link.symlink_to(path)
            with patch.object(sys, "argv", argv[:-1] + [str(link)]), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    config.parse_args()

    def test_host_only_uses_ui_worker_receipt_and_never_updates_shared_settings(self):
        import netbackup_host_configuration as configuration
        for check_only, action in ((True, "CHECK"), (False, "APPLY")):
            args = self.arguments("--host-only", *( ["--check-only"] if check_only else []))
            args.kvm_host_id = "host-uuid"
            with tempfile.TemporaryDirectory() as directory, patch.object(configuration, "ROOT", Path(directory)), \
                    patch.object(config, "resolve_host_context", return_value=("zone", "cluster")), \
                    patch.object(config, "update_configuration_value") as update, \
                    patch.object(config, "ensure_backup_framework_configuration") as shared, \
                    patch.object(configuration, "start") as start, contextlib.redirect_stdout(io.StringIO()) as output:
                def submit(path):
                    request = configuration.read_request(path)
                    self.assertEqual(action, request["action"])
                    self.assertEqual("host-uuid", request["hostid"])
                    self.assertTrue(request["vmschedulebackup"])
                    self.assertTrue(request["incrementalbackup"])
                    self.assertEqual("synthetic-secret", request["adminsecretkey"])
                    Path(path).unlink()
                    return dict(state="RUNNING", action=action, operationid=request["operationid"])
                start.side_effect = submit
                config.configure_host_only(args)
                start.assert_called_once()
                update.assert_not_called(); shared.assert_not_called()
                self.assertIn('"state": "RUNNING"', output.getvalue())
                self.assertNotIn("synthetic-secret", output.getvalue())
                self.assertNotIn("synthetic-netbackup-key", output.getvalue())

    def test_host_only_rejects_requested_shared_setting_changes(self):
        import netbackup_host_configuration as configuration
        for options in (("--backup-chain-size", "4"), ("--vm-schedule-backup", "false"),
                        ("--incremental-backup", "false")):
            args = self.arguments("--host-only", *options)
            with patch.object(config, "resolve_host_context", return_value=("zone", "cluster")), \
                    patch.object(configuration, "start") as start, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    config.configure_host_only(args)
                start.assert_not_called()

    def test_host_only_launch_failure_removes_private_request_and_redacts_secrets(self):
        import netbackup_host_configuration as configuration
        args = self.arguments("--host-only")
        args.kvm_host_id = "host-uuid"
        with tempfile.TemporaryDirectory() as directory, patch.object(configuration, "ROOT", Path(directory)), \
                patch.object(config, "resolve_host_context", return_value=("zone", "cluster")), \
                patch.object(configuration, "start", side_effect=ValueError("failed: " + args.admin_secretkey)), \
                contextlib.redirect_stderr(io.StringIO()) as error:
            with self.assertRaises(SystemExit):
                config.configure_host_only(args)
            self.assertEqual([], list(Path(directory).iterdir()))
            self.assertNotIn(args.admin_secretkey, error.getvalue())
            self.assertIn("<hidden>", error.getvalue())

    def host(self, **values):
        return dict(dict(id="host-uuid", name="host-test", ipaddress="192.0.2.1", hypervisor="KVM",
                         zoneid="zone", clusterid="cluster"), **values)

    def resolve(self, args, hosts=None, client="host-test", addresses=None):
        policy = self.policy()
        policy["clients"] = [{"hostName": client}]
        def lookup(name):
            return {"192.0.2.1"} if name in (client, "192.0.2.1") else set()
        with patch.object(config, "read_netbackup_policy", return_value=policy), \
                patch.object(config, "local_host_addresses", return_value=addresses or {"192.0.2.1"}), \
                patch.object(config, "resolve_addresses", side_effect=lookup), \
                patch.object(config, "invoke_mold_api", return_value={"listhostsresponse": {"host": hosts if hosts is not None else [self.host()]}}), \
                contextlib.redirect_stderr(io.StringIO()):
            return config.resolve_host_context(args)

    def test_existing_and_custom_policy_names_resolve_the_same_local_mold_host(self):
        for policy in ("host-test", "HOST-TEST", "custom-policy", "PoC.GFS_01+full", "ends-"):
            with self.subTest(policy=policy):
                args = self.arguments("--policy-name", policy)
                self.assertIsNone(args.kvm_hostname)
                self.assertEqual(("zone", "cluster"), self.resolve(args))
                self.assertEqual("host-test", args.kvm_hostname)
                self.assertEqual("host-uuid", args.kvm_host_id)
                self.assertEqual("192.0.2.1", args.kvm_ip)
                self.assertEqual(policy, args.policy_name)
                self.assertEqual("host-test", args.netbackup_client)

    def test_policy_client_fqdn_alias_and_ip_can_identify_a_differently_named_mold_host(self):
        for client in ("host-test.example.test", "192.0.2.1", "backup-alias.example.test"):
            with self.subTest(client=client):
                args = self.arguments("--policy-name", "independent-policy")
                self.assertEqual(("zone", "cluster"), self.resolve(args, client=client))
                self.assertEqual("host-test", args.kvm_hostname)

    def test_same_host_name_in_another_cluster_does_not_change_verified_uuid(self):
        args = self.arguments()
        hosts = [self.host(), self.host(id="other-host-uuid", ipaddress="192.0.2.2", clusterid="other-cluster")]
        self.assertEqual(("zone", "cluster"), self.resolve(args, hosts=hosts))
        self.assertEqual("host-uuid", args.kvm_host_id)

    def test_policy_client_on_another_machine_cannot_be_overridden_with_kvm_arguments(self):
        args = self.arguments("--kvm-hostname", "host-test", "--kvm-ip", "192.0.2.1")
        with self.assertRaises(SystemExit):
            self.resolve(args, addresses={"192.0.2.99"})

    def test_optional_legacy_arguments_are_assertions_of_the_verified_host(self):
        self.resolve(self.arguments("--kvm-hostname", "host-test", "--kvm-ip", "192.0.2.1"))
        for options in (("--kvm-hostname", "other-host"), ("--kvm-ip", "192.0.2.2")):
            with self.subTest(options=options), self.assertRaises(SystemExit):
                self.resolve(self.arguments(*options))

    def test_missing_ambiguous_non_kvm_or_incomplete_mold_hosts_are_rejected(self):
        other = dict(self.host(), ipaddress="192.0.2.2")
        for hosts in ([], [other], [self.host(), self.host()], [dict(self.host(), hypervisor="VMware")],
                      [dict(self.host(), clusterid="")], [dict(self.host(), id="")]):
            with self.subTest(hosts=hosts), self.assertRaises(SystemExit):
                self.resolve(self.arguments(), hosts=hosts)

    def test_dns_client_matching_multiple_mold_hosts_is_ambiguous_even_when_one_is_local(self):
        policy = self.policy()
        hosts = [self.host(), dict(self.host(), name="host-other", ipaddress="192.0.2.2")]
        with patch.object(config, "read_netbackup_policy", return_value=policy), \
                patch.object(config, "local_host_addresses", return_value={"192.0.2.1"}), \
                patch.object(config, "resolve_addresses", side_effect=lambda name: {"192.0.2.1", "192.0.2.2"} if name == "host-test" else {name}), \
                patch.object(config, "invoke_mold_api", return_value={"listhostsresponse": {"host": hosts}}), \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            config.resolve_host_context(self.arguments())

    def test_hosts_on_later_inventory_pages_are_checked_before_accepting_uniqueness(self):
        rows = [dict(self.host(), ipaddress="192.0.2.99")] * 500
        policy = self.policy()
        args = self.arguments("--policy-name", "custom-policy")
        with patch.object(config, "read_netbackup_policy", return_value=policy), \
                patch.object(config, "local_host_addresses", return_value={"192.0.2.1"}), \
                patch.object(config, "resolve_addresses", side_effect=lambda name: {"192.0.2.1"} if name == "host-test" else {name}), \
                patch.object(config, "invoke_mold_api", side_effect=[{"listhostsresponse": {"host": rows}},
                    {"listhostsresponse": {"host": [self.host()]}}]) as api, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(("zone", "cluster"), config.resolve_host_context(args))
        self.assertEqual(["1", "2"], [call.args[2]["page"] for call in api.call_args_list])
        self.assertNotIn("name", api.call_args.args[2])

    def test_policy_read_checks_registration_one_client_and_exact_encoded_name(self):
        args = self.arguments("--policy-name", "PoC.GFS_01+full")
        response = {"data": {"id": args.policy_name, "attributes": {"policy": self.policy()}}}
        with patch.object(config, "urlopen", return_value=io.BytesIO(json.dumps(response).encode())) as request:
            config.read_netbackup_policy(args)
        self.assertTrue(request.call_args.args[0].full_url.endswith("/PoC.GFS_01%2Bfull"))
        for clients in ([], [{"hostName": "host-test"}, {"hostName": "other"}], [{}]):
            args = self.arguments()
            policy = self.policy()
            policy["clients"] = clients
            with self.subTest(clients=clients), patch.object(config, "urlopen", return_value=io.BytesIO(
                    json.dumps({"data": {"attributes": {"policy": policy}}}).encode())), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                config.read_netbackup_policy(args)

    def test_invalid_policy_names_fail_before_any_configuration_or_file_changes(self):
        for name in ("", "with space", "with\ttab", "-start", ".start", "end.", "a/b", "a;cmd", "a$(cmd)", "a'quote", "한글"):
            with self.subTest(name=name), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                config.validate_args(self.arguments("--policy-name=" + name))
        self.assertNotEqual(config.sanitize_name("a+b"), config.sanitize_name("a_b"))

    def test_netbackup_policy_length_boundary_is_checked_before_configuration(self):
        config.validate_policy_name("p" * 128)
        with patch.object(config, "configure_vm_selection") as selection, \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            config.validate_args(self.arguments("--policy-name", "p" * 129))
        selection.assert_not_called()

    def test_policy_client_verification_stops_before_settings_secrets_or_hooks_change(self):
        args = self.arguments("--policy-name", "custom-policy")
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(config, "parse_args", return_value=args))
            stack.enter_context(patch.object(config, "validate_args"))
            stack.enter_context(patch.object(config, "load_common_staging"))
            stack.enter_context(patch.object(config, "read_netbackup_policy", side_effect=SystemExit(1)))
            downstream = [stack.enter_context(patch.object(config, name)) for name in (
                "prepare_secret_key", "ensure_backup_framework_configuration", "ensure_netbackup_offering", "generate_host_outputs")]
            stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
            with self.assertRaises(SystemExit):
                config.main()
        for operation in downstream:
            operation.assert_not_called()

    def test_local_interface_detection_excludes_loopback_and_does_not_use_dns_as_local_proof(self):
        result = subprocess.CompletedProcess([], 0, json.dumps([{"addr_info": [
            {"local": "127.0.0.1"}, {"local": "192.0.2.1"}, {"local": "::1"}, {"local": "2001:db8::1"}]}]))
        with patch.object(config.subprocess, "run", return_value=result):
            self.assertEqual({"192.0.2.1", "2001:db8::1"}, config.local_host_addresses())

    def test_plus_is_preserved_in_shell_config_and_transfer_hook_names(self):
        args = self.arguments("--policy-name", "poc+gfs_01")
        self.resolve(args)
        with tempfile.TemporaryDirectory() as directory, patch.object(config, "HOOK_OUTPUT_DIR", Path(directory)):
            config.write_transfer_hooks(config.sanitize_name(args.policy_name))
            self.assertTrue((Path(directory) / "bpstart_notify.poc+gfs_01.mold-transfer").is_file())
            path = Path(directory) / "config"
            config.write_config_file(path, args)
            shell = subprocess.run(["bash", "-c", 'source "$1"; printf "%s\n%s" "$NETBACKUP_POLICY_NAME" "$KVM_HOSTNAME"', "test", str(path)],
                                   capture_output=True, text=True, check=True)
            self.assertEqual("poc+gfs_01\nhost-test", shell.stdout)

    def test_policy_runtime_keeps_plus_and_underscore_contexts_separate(self):
        script = r'''
set -euo pipefail
source "$1"
POLICY_NAME="$3"
resolve_context
[[ "$POLICY_SAFE" == "$3" ]]
CONFIG_ROOT="$2"
log() { :; }
load_admin_secretkey() { :; }
load_backup_staging_root_from_mold() { :; }
load_staging_timeout_from_data_operation_config() { :; }
load_policy_schedule_config
[[ "$KVM_HOSTNAME" == mold-host-2 ]]
'''
        for requested in ("poc+gfs", "poc_gfs"):
            for configured in (requested, "wrong-policy"):
                with self.subTest(requested=requested, configured=configured), tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / ("netbackup-host-" + requested + ".conf")
                    path.write_text("KVM_HOSTNAME=mold-host-2\nNETBACKUP_POLICY_NAME=" + configured
                                    + "\nMOLD_URL=http://mold.test/client/api\nADMIN_APIKEY=synthetic-key\n")
                    result = subprocess.run(["bash", "-c", script, "test", str(Path(config.__file__).with_name("netbackup-host-common.sh")),
                                             directory, requested], capture_output=True, text=True, timeout=10)
                    self.assertEqual(0 if requested == configured else 1, result.returncode, result.stderr)

    def candidate_lookup(self, host_id="host-uuid", host_name="host-test", inventories=None, candidates=None,
                         client="192.0.2.1", local_addresses=("192.0.2.1",)):
        script = r'''
set -euo pipefail
source "$1"
MOLD_VM_CACHE_FILE="$2/cache"
TEST_ROOT="$2"
KVM_HOST_ID="$3"
KVM_HOSTNAME="$4"
CLIENT_NAME="$5"
POLICY_NAME=prod-weekly
MOLD_LIST_VMS_API_URL=http://mold.test/client/api
log() { :; }
invoke_mold_api() {
  printf '%s\n' "$@" >> "${MOLD_VM_CACHE_FILE}.request"
  case "$3" in
    listHosts) cat "$TEST_ROOT/hosts-${@: -1}.json" ;;
    listExternalBackupCandidates) cat "$TEST_ROOT/candidates.json" ;;
    *) return 1 ;;
  esac
}
cache_mold_virtual_machines
printf '%s\n%s' "$KVM_HOST_ID" "$KVM_HOSTNAME"
'''
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ip = root / "ip"
            interfaces = json.dumps([{"addr_info": [{"local": address} for address in local_addresses]}])
            ip.write_text("#!/bin/sh\nprintf '%s\\n' " + shlex.quote(interfaces) + "\n")
            ip.chmod(0o755)
            for page, inventory in enumerate([self.host()] if inventories is None else inventories, start=1):
                (root / f"hosts-{page}.json").write_text(json.dumps({"listhostsresponse": {"host": inventory}}))
            (root / "candidates.json").write_text(json.dumps({"listexternalbackupcandidatesresponse": {
                "externalbackupcandidates": {"selectionmode": "CONFIG", "candidate": candidates or []}}}))
            result = subprocess.run(["bash", "-c", script, "test", str(Path(__file__).with_name("netbackup-host-common.sh")),
                                     directory, host_id, host_name, client], capture_output=True, text=True, timeout=10,
                                    env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"]))
            request = root / "cache.request"
            return result, request.read_text().splitlines() if request.exists() else []

    def test_runtime_verifies_current_uuid_for_identically_named_hosts_and_keeps_policy_name(self):
        for host_id in ("cluster-a-host-uuid", "cluster-b-host-uuid"):
            with self.subTest(host_id=host_id):
                inventory = [self.host(id=host_id), self.host(id="other-host-uuid", ipaddress="192.0.2.2", clusterid="other-cluster")]
                result, params = self.candidate_lookup(host_id=host_id, inventories=[inventory])
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(host_id, params[params.index("hostid") + 1])
                self.assertEqual("prod-weekly", params[params.index("policyid") + 1])
                self.assertNotIn("hostname", params)
                self.assertIn("listHosts", params)
                self.assertNotIn("name", params)

    def test_runtime_refreshes_renamed_reregistered_and_legacy_host_identity(self):
        for saved_id, saved_name in (("host-uuid", "old-name"), ("deleted-host-uuid", "host-test"), ("", "")):
            with self.subTest(saved_id=saved_id, saved_name=saved_name):
                inventory = [self.host(id="current-uuid", name="current-host"),
                             self.host(id=saved_id or "other-uuid", name=saved_name or "other-host", ipaddress="192.0.2.2")]
                result, params = self.candidate_lookup(host_id=saved_id, host_name=saved_name, inventories=[inventory])
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("current-uuid", params[params.index("hostid") + 1])
                self.assertEqual(["current-uuid", "current-host"], result.stdout.splitlines())
                self.assertNotIn("hostname", params)

    def test_runtime_rejects_missing_ambiguous_or_incomplete_hosts_before_selection(self):
        for inventory in ([], [self.host(), self.host(id="other-uuid", clusterid="other-cluster")],
                          [self.host(id="")], [self.host(hypervisor="VMware")], "invalid"):
            with self.subTest(inventory=inventory):
                result, params = self.candidate_lookup(host_id="", inventories=[inventory])
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("listExternalBackupCandidates", params)

    def test_runtime_checks_later_pages_before_accepting_host_identity(self):
        first = [self.host()] + [self.host(name="other-host", ipaddress="192.0.2.99")] * 499
        for second, accepted in (([], True), ([self.host(id="second-host-uuid")], False)):
            with self.subTest(accepted=accepted):
                result, params = self.candidate_lookup(host_id="", inventories=[first, second])
                self.assertEqual(accepted, result.returncode == 0, result.stderr)
                self.assertEqual(2, params.count("listHosts"))
                self.assertEqual(accepted, "listExternalBackupCandidates" in params)

    def test_runtime_cannot_accept_a_saved_uuid_when_client_is_not_local(self):
        for addresses in ((), ("192.0.2.2",), ("127.0.0.1",)):
            with self.subTest(addresses=addresses):
                result, params = self.candidate_lookup(local_addresses=addresses)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("listExternalBackupCandidates", params)
                self.assertNotIn("listHosts", params)

    def test_runtime_inventory_failure_cannot_fall_back_to_the_saved_uuid(self):
        result, params = self.candidate_lookup(inventories=[])
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("listExternalBackupCandidates", params)

    def test_candidate_response_cannot_redirect_execution_to_another_host_uuid(self):
        candidate = dict(virtualmachineid="vm-uuid", instancename="i-2-1-VM", hostid="host-uuid", backupofferingid="offering-uuid")
        for host_id in ("host-uuid", "other-host-uuid"):
            with self.subTest(host_id=host_id):
                result, _ = self.candidate_lookup(host_id=host_id, inventories=[[self.host(id=host_id)]], candidates=[candidate])
                self.assertEqual(host_id == "host-uuid", result.returncode == 0)

    def assert_cluster_incremental_update(self, update, args, value):
        update.assert_called_once_with("kvm.incremental.backup", value, args.mold_url, args.admin_apikey,
                                       args.admin_secretkey, cluster_id="cluster-id")

    def configure(self, args, current_incremental="true", chain="8", global_incremental=None, global_plugins="ablestack-netbackup", zone_plugins="ablestack-netbackup", global_schedule="false"):
        if global_incremental is None:
            global_incremental = current_incremental
        values = {"backup.framework.enabled": "true", "kvm.incremental.backup": current_incremental,
                  "kvm.backup.chain.size": chain, "backup.framework.provider.plugin": "ablestack-netbackup",
                  "backup.plugin.netbackup.url": args.netbackup_url, "backup.plugin.netbackup.apikey": args.netbackup_apikey,
                  config.NETBACKUP_VM_SCHEDULE_CONFIG_NAME: global_schedule}
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
        self.assert_cluster_incremental_update(update, args, "true")
        self.assertNotIn("kvm.backup.chain.size", [call.args[0] for call in read.call_args_list])


    def test_mode_prompt_does_not_ask_for_vm_filters(self):
        for mode in ("true", "false"):
            args = self.arguments()
            with patch.object(sys.stdin, "isatty", return_value=True), patch("builtins.input", return_value=mode) as read:
                config.configure_vm_selection(args)
            self.assertEqual(mode == "true", args.vm_schedule_backup)
            self.assertEqual(1, read.call_count)
            self.assertFalse(hasattr(args, "vm_include"))
            self.assertFalse(hasattr(args, "vm_exclude"))

    def test_removed_netbackup_filter_arguments_are_rejected(self):
        for argument in ("--vm-include", "--vm-exclude"):
            with self.subTest(argument=argument), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                self.arguments(argument, "*")

    def test_generated_policy_has_no_vm_filters_in_either_mode(self):
        for mode in ("true", "false"):
            args = self.arguments("--vm-schedule-backup", mode)
            self.resolve(args)
            path = config.CONFIG_OUTPUT_DIR / "new-policy.conf"
            config.write_config_file(path, args)
            text = path.read_text()
            for key in ("VM_SCHEDULE_BACKUP=", "VM_INCLUDE=", "VM_EXCLUDE="):
                self.assertNotIn(key, text)
            self.assertIn("NETBACKUP_POLICY_NAME=host-test", text)
            self.assertIn("/bootstrap/host-test", text)

    def test_custom_template_cannot_reintroduce_removed_filter_keys(self):
        template = config.CONFIG_OUTPUT_DIR / "template.conf"
        template.write_text('VM_SCHEDULE_BACKUP=true\nVM_INCLUDE="*"\nVM_EXCLUDE="*"\n')
        args = self.arguments("--vm-schedule-backup", "false")
        self.resolve(args)
        path = config.CONFIG_OUTPUT_DIR / "new-policy.conf"
        with patch.object(config, "POLICY_TEMPLATE_PATH", template):
            config.write_config_file(path, args)
        for key in ("VM_SCHEDULE_BACKUP=", "VM_INCLUDE=", "VM_EXCLUDE="):
            self.assertNotIn(key, path.read_text())

    def test_dynamic_global_schedule_update_does_not_require_restart(self):
        args = self.arguments("--vm-schedule-backup", "true")
        restart, read, update = self.configure(args)
        self.assertFalse(restart)
        self.assertEqual([config.NETBACKUP_VM_SCHEDULE_CONFIG_NAME, "kvm.incremental.backup"],
                         [call.args[0] for call in update.call_args_list])
        self.assertEqual("true", update.call_args_list[0].args[1])

    def test_omitted_noninteractive_mode_retains_current_mold_value(self):
        for current in ("true", "false"):
            with self.subTest(current=current):
                args = self.arguments()
                with patch.object(config, "get_configuration_value", return_value=current), \
                        patch.object(sys.stdin, "isatty", return_value=False):
                    config.configure_vm_selection(args)
                self.assertEqual(current == "true", args.vm_schedule_backup)
                restart, read, update = self.configure(args, global_schedule=current)
                self.assertFalse(restart)
                self.assert_cluster_incremental_update(update, args, "true")

    def test_interactive_default_comes_from_mold_and_mentions_global_scope(self):
        args = self.arguments()
        with patch.object(config, "get_configuration_value", return_value="true"), \
                patch.object(sys.stdin, "isatty", return_value=True), patch("builtins.input", return_value="") as read:
            config.configure_vm_selection(args)
        self.assertTrue(args.vm_schedule_backup)
        self.assertIn("ALL NetBackup host policies", read.call_args.args[0])
        self.assertIn("current: true", read.call_args.args[0])
        self.assertEqual(1, read.call_count)

    def test_missing_or_invalid_mold_mode_stops_configuration(self):
        for current in ("", "invalid"):
            with self.subTest(current=current), patch.object(config, "get_configuration_value", return_value=current), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                config.configure_vm_selection(self.arguments())


    def test_full_mode_updates_only_cluster_flag_and_requires_restart(self):
        args = self.arguments("--incremental-backup", "false")
        restart, read, update = self.configure(args)
        self.assertTrue(restart)
        self.assert_cluster_incremental_update(update, args, "false")
        self.assertNotIn("kvm.backup.chain.size", [call.args[0] for call in read.call_args_list])

    def test_global_flag_is_preserved_when_cluster_already_matches(self):
        args = self.arguments("--incremental-backup", "false")
        restart, _, update = self.configure(args, current_incremental="false", global_incremental="true")
        self.assertFalse(restart)
        self.assert_cluster_incremental_update(update, args, "false")

    def test_configuring_multiple_clusters_pins_each_choice_without_changing_global(self):
        for initial_global in ("false", "true"):
            with self.subTest(initial_global=initial_global):
                args = self.arguments("--vm-schedule-backup", "true")
                values = {"backup.framework.enabled": "true", "backup.framework.provider.plugin": "ablestack-netbackup",
                          "kvm.incremental.backup": initial_global, "backup.plugin.netbackup.url": args.netbackup_url,
                          "backup.plugin.netbackup.apikey": args.netbackup_apikey,
                          config.NETBACKUP_VM_SCHEDULE_CONFIG_NAME: "true"}
                overrides = {}

                def read_value(name, *unused, cluster_id=None):
                    return overrides.get(cluster_id, values[name]) if cluster_id else values[name]

                def write_value(name, value, *unused, cluster_id=None):
                    self.assertEqual("kvm.incremental.backup", name)
                    self.assertIsNotNone(cluster_id)
                    overrides[cluster_id] = value

                with patch.object(config, "get_configuration_value", side_effect=read_value), \
                        patch.object(config, "update_configuration_value", side_effect=write_value), \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    restart_a = config.ensure_backup_framework_configuration("zone", "cluster-a", args)
                    args.incremental_backup = False
                    restart_b = config.ensure_backup_framework_configuration("zone", "cluster-b", args)
                    self.assertFalse(config.ensure_backup_framework_configuration("zone", "cluster-b", args))

                self.assertEqual(initial_global == "false", restart_a)
                self.assertEqual(initial_global == "true", restart_b)
                self.assertEqual(initial_global, values["kvm.incremental.backup"])
                self.assertEqual({"cluster-a": "true", "cluster-b": "false"}, overrides)
                # Simulate a later administrator change to the Global default.
                values["kvm.incremental.backup"] = "true" if initial_global == "false" else "false"
                self.assertEqual("true", read_value("kvm.incremental.backup", cluster_id="cluster-a"))
                self.assertEqual("false", read_value("kvm.incremental.backup", cluster_id="cluster-b"))
                self.assertEqual(values["kvm.incremental.backup"], read_value("kvm.incremental.backup", cluster_id="unconfigured-cluster"))

    def test_cluster_override_is_updated_even_when_global_already_matches(self):
        args = self.arguments("--incremental-backup", "false")
        restart, _, update = self.configure(args, current_incremental="true", global_incremental="false")
        self.assertTrue(restart)
        update.assert_called_once_with("kvm.incremental.backup", "false", args.mold_url, args.admin_apikey,
                                       args.admin_secretkey, cluster_id="cluster-id")

    def test_rerunning_full_mode_is_idempotent(self):
        args = self.arguments("--incremental-backup", "false")
        restart, _, update = self.configure(args, current_incremental="false")
        self.assertFalse(restart)
        self.assert_cluster_incremental_update(update, args, "false")

    def test_reenabling_incremental_applies_the_requested_chain_size(self):
        args = self.arguments("--incremental-backup", "true", "--backup-chain-size", "4")
        restart, _, update = self.configure(args, current_incremental="false")
        self.assertTrue(restart)
        self.assertEqual([("kvm.incremental.backup", "true"), ("kvm.backup.chain.size", "4")],
                         [call.args[:2] for call in update.call_args_list])
        self.assertEqual("cluster-id", update.call_args_list[0].kwargs["cluster_id"])
        self.assertNotIn("cluster_id", update.call_args_list[1].kwargs)

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
        self.assert_cluster_incremental_update(update, args, "false")
        self.assertNotIn("kvm.backup.chain.size", [call.args[0] for call in read.call_args_list])

    def test_local_directory_preflight_and_creation_use_the_same_filesystem_validator(self):
        from thirdparty_staging_directory import prepare_directory
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / "staging-local"
            values = {"backup.thirdparty.staging.enable": "true", "backup.thirdparty.staging.storage.type": "LOCAL",
                      "backup.thirdparty.staging.local.mode": "DIRECTORY", "backup.thirdparty.staging.mount.path": "",
                      "backup.thirdparty.staging.root.path": str(root)}
            with patch.multiple(config, BACKUP_STAGING_ROOT=root, STAGING_MOUNT_PATH=None, STAGING_LOCAL_MODE="MOUNT"), \
                    patch.object(config, "get_configuration_value", side_effect=lambda name, *a, **k: values[name]), \
                    patch("thirdparty_staging_directory.local_filesystem", return_value=("/", "filesystem-id")), \
                    patch("thirdparty_staging_directory.prepare_directory", wraps=prepare_directory) as validate, \
                    contextlib.redirect_stderr(io.StringIO()):
                config.load_common_staging(self.arguments())
                self.assertEqual("DIRECTORY", config.STAGING_LOCAL_MODE)
                config.ensure_backup_staging_root()
                self.assertEqual(2, validate.call_count)
                self.assertTrue((root / "ablestack-netbackup").is_dir())

    def test_invalid_local_mode_prevents_configuration_changes(self):
        errors, mounted = self.assert_staging_preflight_rejected(overrides={
            "backup.thirdparty.staging.storage.type": "LOCAL", "backup.thirdparty.staging.local.mode": "AUTO"})
        self.assertIn("local.mode", errors)
        mounted.assert_not_called()

    def provision_with_staging(self, overrides=None, query_failure=False, mount_result=0, mount_error=None):
        args = self.arguments("--vm-schedule-backup", "false")
        with tempfile.TemporaryDirectory() as directory:
            mount = Path(directory) / "mount"
            values = {"backup.thirdparty.staging.enable": "true",
                      "backup.thirdparty.staging.storage.type": "NFS",
                      "backup.thirdparty.staging.mount.path": str(mount),
                      "backup.thirdparty.staging.root.path": str(mount / "staging"),
                      "backup.framework.enabled": "true", "kvm.incremental.backup": "true",
                      "backup.framework.provider.plugin": "ablestack-netbackup",
                      "backup.plugin.netbackup.url": args.netbackup_url,
                      "backup.plugin.netbackup.apikey": args.netbackup_apikey,
                      config.NETBACKUP_VM_SCHEDULE_CONFIG_NAME: "false"}
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
                    patch.object(config, "read_netbackup_policy"), \
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
        self.assert_cluster_incremental_update(update, self.arguments(), "true")
        offering.assert_called_once()
        output.assert_called_once()

    def test_host_reconfiguration_migrates_the_daemon_to_a_timer_and_is_retryable(self):
        args = self.arguments("--vm-schedule-backup", "false")
        self.resolve(args)
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
            deployed_helper = locations["WATCHER_OUTPUT_PATH"].parent / "thirdparty_restore_metadata.py"
            self.assertTrue(deployed_helper.is_file())
            self.assertEqual(0o644, deployed_helper.stat().st_mode & 0o777)
            self.assertEqual(["systemctl", "disable", "--now", service.name], calls[2])
            self.assertEqual(["systemctl", "enable", "--now", timer.name], calls[-1])
            self.assertEqual(old_service, next(service.parent.glob(service.name + ".bak.*")).read_text())
            self.assertIn(f"ExecStart={locations['WATCHER_OUTPUT_PATH']} --once", service.read_text())
            calls.clear()
            config.generate_host_outputs("zone-id", args)
            deployed_helper = locations["WATCHER_OUTPUT_PATH"].parent / "thirdparty_restore_metadata.py"
            self.assertTrue(deployed_helper.is_file())
            self.assertEqual(0o644, deployed_helper.stat().st_mode & 0o777)
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
            args = self.arguments()
            self.resolve(args)
            with contextlib.redirect_stdout(io.StringIO()):
                config.write_watcher_config(path, args)
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
