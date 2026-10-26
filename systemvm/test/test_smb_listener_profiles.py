# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file distributed
# with this work for additional information regarding copyright ownership.
# The ASF licenses this file under the Apache License, Version 2.0
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS.
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

MODULE = Path(os.environ.get("CONFIGURATION_MODULE", Path(__file__).resolve().parents[1] /
              "debian/usr/local/lib/ablestack-storage/configuration_transaction.py"))
spec = importlib.util.spec_from_file_location("configuration_transaction", MODULE)
configuration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configuration)


class SmbListenerLaunchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.controller = configuration.SmbListenerProfiles(self.root / "profiles", self.root / "base.conf")
        self.endpoint = {"id": str(uuid.uuid4()), "listenIp": "192.0.2.10", "port": 445}
        self.row = self.controller.record(self.endpoint)

    def tearDown(self):
        self.temp.cleanup()

    @unittest.skipUnless(hasattr(os, "setsid"), "Linux/systemd execution contract")
    def test_systemd_session_leader_does_not_run_samba_setsid_again(self):
        """The old smbd -F invocation fails EPERM before socket creation.

        Exercise the real kernel constraint, then emulate Samba's documented
        no-process-group decision at the systemd-run boundary.  The SystemVM
        integration test additionally executes the installed smbd binary.
        """
        failure = subprocess.run([sys.executable, "-c", "import os;os.setsid()"],
                                 start_new_session=True, capture_output=True, text=True)
        self.assertNotEqual(0, failure.returncode)
        self.assertIn("Operation not permitted", failure.stderr)
        calls = []
        def launcher(args, *unused, **kw):
            calls.append(args)
            if args[0] == "systemd-run":
                code = "import os;" + ("pass" if "--no-process-group" in args else "os.setsid()")
                subprocess.run([sys.executable, "-c", code], start_new_session=True, check=True,
                               capture_output=True)
            return b""
        with patch.object(self.controller, "ready", side_effect=[False, True]), \
             patch.object(self.controller, "sockets", return_value=set()), \
             patch.object(self.controller, "configuration", return_value=self.root / "binding.conf"), \
             patch.object(configuration, "checked", side_effect=launcher):
            self.controller.start(self.row, {self.row["key"]: self.row})
        start = calls[0]
        self.assertEqual("systemd-run", start[0])
        self.assertIn("--property=KillMode=control-group", start)
        self.assertIn("--property=Restart=no", start)
        self.assertIn("--unit=" + self.row["unit"], start)
        self.assertIn("--no-process-group", start)

    def test_live_owned_tuple_is_not_started_twice(self):
        with patch.object(self.controller, "ready", return_value=True), \
             patch.object(configuration, "checked") as run:
            self.controller.start(self.row, {self.row["key"]: self.row})
            run.assert_not_called()

    def test_conflicting_socket_is_rejected_before_any_process_start(self):
        with patch.object(self.controller, "ready", return_value=False), \
             patch.object(self.controller, "sockets", return_value={("0.0.0.0", 445)}), \
             patch.object(configuration, "checked") as run:
            with self.assertRaisesRegex(configuration.Rejected, "SMB_ENDPOINT_IN_USE"):
                self.controller.start(self.row, {self.row["key"]: self.row})
            run.assert_not_called()

    def test_corrupt_unit_identity_cannot_select_another_service(self):
        with self.assertRaisesRegex(configuration.Rejected, "SMB_PROFILE_IDENTITY_CHANGED"):
            self.controller.raw_pid(dict(self.row, unit="ssh.service"))

    def test_null_defaults_are_a_single_tuple_but_different_ports_are_distinct(self):
        a = dict(self.endpoint, listenIp=None, port=None)
        b = dict(a, id=str(uuid.uuid4()), listenIp="0.0.0.0", port=445)
        with self.assertRaisesRegex(configuration.Rejected, "DUPLICATE_SMB_ENDPOINT"):
            self.controller.normalized({"listeners": [a, b]})
        b["port"] = 1445
        self.assertEqual(2, len(self.controller.normalized({"listeners": [a, b]})))

    def test_only_new_secondary_with_matching_persisted_nic_identity_is_owned(self):
        original = [{"ifname": "eth0", "address": "02:00:00:00:00:01",
                     "addr_info": [{"local": "192.0.2.1", "prefixlen": 24}]}]
        current = [{**original[0], "addr_info": original[0]["addr_info"] + [
            {"local": "192.0.2.10", "prefixlen": 24, "secondary": True}]}]
        entry = {"listenIp": "192.0.2.10", "macAddress": "02:00:00:00:00:01", "prefixlen": 24}
        configuration.atomic_json(self.root / "network-endpoints.json", {"endpoints": [entry]})
        state = {}
        with patch.object(configuration, "checked", return_value=json.dumps(current).encode()):
            self.controller.remember_added_aliases(state, [self.endpoint], original)
        self.assertEqual({"192.0.2.10"}, set(state["ownedAliases"]))
        state = {}
        with patch.object(configuration, "checked", return_value=json.dumps(current).encode()):
            self.controller.remember_added_aliases(state, [self.endpoint], current)
        self.assertEqual({}, state["ownedAliases"])

    def test_release_requires_secondary_and_does_not_replay_after_boot(self):
        ip = "192.0.2.10"
        alias = {"listenIp": ip, "interface": "eth0", "macAddress": "02:00:00:00:00:01", "prefixlen": 24}
        state = {"instanceUuid": str(uuid.uuid4()), "profiles": {}, "ownedAliases": {ip: alias}}
        path = self.root / "network-endpoints.json"
        configuration.atomic_json(path, {"endpoints": [alias]})
        current = [{"ifname": "eth0", "address": alias["macAddress"], "addr_info": [
            {"local": "192.0.2.1", "prefixlen": 24}, {"local": ip, "prefixlen": 24, "secondary": True}]}]
        with patch.object(configuration, "checked", side_effect=[json.dumps(current).encode(), b""]) as commands:
            self.controller.release_owned_aliases(state, {"listeners": [], "releaseAddresses": [ip]})
            self.assertEqual(["ip", "addr", "del", ip + "/24", "dev", "eth0"], commands.call_args_list[1].args[0])
        self.assertEqual([], configuration.load(path)["endpoints"])
        self.assertEqual({}, state["ownedAliases"])
        value = {"instanceUuid": state["instanceUuid"], "listeners": [], "releaseAddresses": [ip]}
        operation = str(uuid.uuid4())
        configuration.atomic_json(self.controller.manifest, {**state, "pending": {
            "operationid": operation, "wanted": [], "value": value}})
        with patch.object(self.controller, "release_owned_aliases"):
            self.controller.commit(operation)
        self.assertNotIn("releaseAddresses", configuration.load(self.controller.desired))

    def test_primary_manual_or_still_bound_alias_cannot_be_released(self):
        ip = self.endpoint["listenIp"]
        with self.assertRaisesRegex(configuration.Rejected, "SMB_ALIAS_RELEASE_NOT_OWNED"):
            self.controller.release_owned_aliases({"ownedAliases": {}}, {"releaseAddresses": [ip]})
        alias = {"listenIp": ip, "interface": "eth0", "macAddress": "02:00:00:00:00:01", "prefixlen": 24}
        state = {"profiles": {}, "ownedAliases": {ip: alias}}
        value = {"listeners": [self.endpoint], "releaseAddresses": [ip]}
        with self.assertRaisesRegex(configuration.Rejected, "SMB_ALIAS_RELEASE_NOT_OWNED"):
            self.controller.release_owned_aliases(state, value)
        configuration.atomic_json(self.root / "network-endpoints.json", {"endpoints": [alias]})
        primary = [{"ifname": "eth0", "address": alias["macAddress"],
                    "addr_info": [{"local": ip, "prefixlen": 24}]}]
        with patch.object(configuration, "checked", return_value=json.dumps(primary).encode()) as commands:
            with self.assertRaisesRegex(configuration.Rejected, "SMB_ALIAS_RELEASE_NOT_SECONDARY"):
                self.controller.release_owned_aliases(state, {"listeners": [], "releaseAddresses": [ip]})
            self.assertEqual(1, commands.call_count)


if __name__ == "__main__":
    unittest.main()
