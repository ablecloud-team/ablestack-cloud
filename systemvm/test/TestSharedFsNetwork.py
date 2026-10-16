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


"""Exercise the canonical production helper with isolated fake network/process observations."""
import copy
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import signal
import shutil
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "systemvm/debian/usr/local/sbin/ablestack-sharedfs-network"
loader = importlib.machinery.SourceFileLoader("sharedfs_network_test", str(SOURCE))
spec = importlib.util.spec_from_loader(loader.name, loader)
m = importlib.util.module_from_spec(spec)
loader.exec_module(m)
STATIC = {"macAddress": "02:01:00:00:09:13", "ipAddress": "192.0.2.10", "cidr": "192.0.2.0/24",
          "gateway": "192.0.2.1", "dns1": "203.0.113.53"}


class Fake:
    def __init__(self):
        self.links = [{"ifname": "ens913", "address": STATIC["macAddress"], "flags": ["UP"], "link_type": "ether"}]
        self.addresses = [{"family": "inet", "scope": "global", "local": STATIC["ipAddress"], "prefixlen": 24},
                          {"family": "inet", "scope": "global", "local": "192.0.2.20", "prefixlen": 24, "label": "ens913:1"}]
        self.defaults = [{"dst": "default", "dev": "ens913", "gateway": STATIC["gateway"]}]
        self.connected = [{"dst": STATIC["cidr"], "dev": "ens913", "scope": "link"}]
        self.active, self.masked, self.client_count = False, True, 0
        self.effects = []
        self.on_start = None

    def __call__(self, args, **kwargs):
        if args[:4] == ["ip", "-j", "link", "show"]:
            value = self.links
        elif args[:5] == ["ip", "-j", "-4", "addr", "show"]:
            value = [{"addr_info": self.addresses}]
        elif args[:6] == ["ip", "-j", "-4", "route", "show", "default"]:
            value = self.defaults
        elif args[:6] == ["ip", "-j", "-4", "route", "show", "dev"]:
            value = self.connected
        elif args[:2] == ["systemctl", "show"]:
            return subprocess.CompletedProcess(args, 0, "ActiveState=" + ("active" if self.active else "inactive") + "\nUnitFileState=" + ("masked" if self.masked else "enabled") + "\n", "")
        else:
            self.effects.append(args)
            if args[:2] == ["systemctl", "stop"]:
                self.active, self.client_count = False, 0
            elif args[:2] == ["systemctl", "mask"]:
                self.masked = True
            elif args[:2] == ["systemctl", "unmask"]:
                self.masked = False
            elif args[:2] == ["systemctl", "start"]:
                self.active, self.client_count = True, 1
                if self.on_start:
                    self.on_start()
            elif args[:4] == ["ip", "-4", "addr", "replace"]:
                ip, prefix = args[4].split("/")
                self.addresses = [r for r in self.addresses if r["local"] != ip]
                self.addresses.append({"family": "inet", "scope": "global", "local": ip, "prefixlen": int(prefix)})
            elif args[:4] == ["ip", "-4", "addr", "del"]:
                ip, _ = args[4].split("/")
                self.addresses = [r for r in self.addresses if r["local"] != ip]
            elif args[:4] == ["ip", "-4", "route", "del"]:
                self.defaults = [r for r in self.defaults if r.get("dev") != args[-1]]
            elif args[:4] == ["ip", "-4", "route", "replace"]:
                if args[4] == "default":
                    self.defaults = [{"dst": "default", "dev": args[-1], "gateway": args[6]}]
                else:
                    self.connected = [{"dst": args[4], "dev": "ens913", "scope": "link"}]
            else:
                raise AssertionError(args)
            return subprocess.CompletedProcess(args, 0, "", "")
        return subprocess.CompletedProcess(args, 0, json.dumps(value), "")


class NetworkTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.state, self.domain, self.resolver = [self.base / n for n in ("network.json", "domain.json", "resolv.conf")]
        self.state.write_text(json.dumps(STATIC));self.state.chmod(0o600)
        self.resolver.write_text("nameserver 203.0.113.53\n")
        self.fake = Fake()
        self.net = m.Network(self.state, self.fake, self.resolver, self.domain, leases=self.base, aliases=self.base / "endpoints.json")
        self.net.clients = lambda name: [{"argv": [b"dhclient", name.encode()]}] * self.fake.client_count
        self.net.stop_clients = lambda name: setattr(self.fake, "client_count", 0)
        self.net.cloud_config = self.base / "cloud.cfg.d" / m.CLOUD_CONFIG.name
        self.net.cloud_config.parent.mkdir()
        self.net.role = self.base / "cmdline";self.net.role.write_text("type=sharedfsvm name=isolated-test\n")
    def tearDown(self):
        self.tmp.cleanup()


    def test_prepare_installs_network_exclusion_before_static_or_dhcp_effects(self):
        self.fake.masked = False
        original = self.net.run
        seen = []
        def run(args, **kwargs):
            if args[0] == "systemctl" and args[1] in ("mask", "stop", "start", "unmask"):
                seen.append(self.net.cloud_config.read_text())
            return original(args, **kwargs)
        self.net.run = run
        self.assertTrue(self.net.prepare()["success"])
        expected = ("# SharedFS primary networking is owned by ablestack-sharedfs-network.\n"
                    "network:\n  config: disabled\n"
                    "disable_network_activation: true\n"
                    "datasource_list: [NoCloud, ConfigDrive]\n")
        self.assertEqual("zz-ablestack-sharedfs-network.cfg", m.CLOUD_CONFIG.name)
        self.assertGreater(m.CLOUD_CONFIG.name, "cloudstack.cfg")
        self.assertEqual(expected, self.net.cloud_config.read_text())
        self.assertTrue(seen);self.assertTrue(all(text == expected for text in seen))
        before = self.net.cloud_config.stat()
        self.net.prepare()
        self.assertEqual(before, self.net.cloud_config.stat())
        self.state.unlink();self.fake.active = True;self.fake.masked = False;self.fake.client_count = 1
        self.assertTrue(self.net.prepare()["success"])
        self.assertEqual(before, self.net.cloud_config.stat())
        self.assertEqual(0o644, stat.S_IMODE(before.st_mode))

    def test_initial_dhcp_excludes_cloud_init_before_starting_owned_client(self):
        self.state.unlink();self.fake.masked = False;self.fake.active = False
        self.fake.on_start = lambda: self.assertTrue(self.net.cloud_config.is_file())
        self.assertTrue(self.net.prepare()["success"])
        self.assertIn("datasource_list: [NoCloud, ConfigDrive]", self.net.cloud_config.read_text())
        self.assertEqual(1, self.fake.client_count)

    def test_qga_public_0666_role_selector_does_not_relax_persistent_network_state(self):
        self.net.role.chmod(0o666)
        self.assertTrue(self.net.prepare()["success"])
        self.assertEqual(0o666, stat.S_IMODE(self.net.role.stat().st_mode))
        self.state.chmod(0o666)
        with self.assertRaises(ValueError):self.net.prepare()
        self.assertEqual("ERROR", self.net.inspect()["status"])
        self.assertEqual([], self.fake.effects)

    def test_qga_public_role_does_not_relax_resolver_protection(self):
        self.net.role.chmod(0o666);self.resolver.chmod(0o666)
        with self.assertRaises(ValueError):self.net.prepare()
        self.assertEqual("UNKNOWN", self.net.inspect()["status"])
        self.assertEqual([], self.fake.effects)

    def test_public_role_requires_regular_owner_stable_identity_and_bounded_read(self):
        role = self.net.role
        role.unlink();role.symlink_to(self.state)
        with self.assertRaises((ValueError, OSError)):m.sharedfs_role(role)
        role.unlink();role.write_text("type=sharedfsvm");role.chmod(0o666)
        with patch.object(m.os, "geteuid", return_value=os.geteuid() + 1):
            with self.assertRaises(ValueError):m.sharedfs_role(role)
        read = os.read
        def changed(fd, size):
            raw = read(fd, size);role.write_text("type=router");return raw
        with patch.object(m.os, "read", side_effect=changed):
            with self.assertRaises(ValueError):m.sharedfs_role(role)
        role.write_bytes(b"x" * (1024 * 1024 + 1))
        with self.assertRaises(ValueError):m.sharedfs_role(role)

    def test_inspect_never_creates_cloud_init_configuration(self):
        self.assertTrue(self.net.inspect()["success"])
        self.assertFalse(self.net.cloud_config.exists())
        self.assertEqual([], self.fake.effects)

    def test_cloud_init_exclusion_rejects_foreign_role_or_conflicting_owned_file(self):
        for role in ("type=router", "type=sharedfsvm type=router", "type=sharedfsvm-extra", ""):
            self.net.role.write_text(role)
            with self.assertRaises(ValueError):self.net.prepare()
            self.assertFalse(self.net.cloud_config.exists())
        self.net.role.write_text("type=sharedfsvm")
        self.net.cloud_config.write_text("network:\n  config: enabled\n")
        before = self.net.cloud_config.read_bytes()
        with self.assertRaises(ValueError):self.net.prepare()
        self.assertEqual(before, self.net.cloud_config.read_bytes())
        self.assertEqual([], self.fake.effects)

    def test_cloud_init_exclusion_rejects_symlink_or_writable_directory(self):
        foreign = self.base / "foreign";foreign.write_text("untouched")
        self.net.cloud_config.symlink_to(foreign)
        with self.assertRaises((ValueError, OSError)):self.net.prepare()
        self.assertEqual("untouched", foreign.read_text())
        self.net.cloud_config.unlink()
        self.net.cloud_config.parent.chmod(0o777)
        with self.assertRaises(ValueError):self.net.prepare()
        self.assertFalse(self.net.cloud_config.exists())
        self.assertEqual([], self.fake.effects)

    def test_cloud_init_atomic_publication_does_not_clobber_concurrent_file(self):
        original = os.link
        def concurrent(source, target, **kwargs):
            self.net.cloud_config.write_text("CONCURRENT_CONFIGURATION")
            return original(source, target, **kwargs)
        with patch.object(m.os, "link", side_effect=concurrent):
            with self.assertRaises(FileExistsError):self.net.prepare()
        self.assertEqual("CONCURRENT_CONFIGURATION", self.net.cloud_config.read_text())
        self.assertEqual([self.net.cloud_config], list(self.net.cloud_config.parent.iterdir()))
        self.assertEqual([], self.fake.effects)

    def test_invalid_static_never_installs_cloud_init_config_or_starts_dhcp(self):
        self.state.write_text("{}")
        with self.assertRaises(ValueError):self.net.prepare()
        self.assertFalse(self.net.cloud_config.exists());self.assertEqual([], self.fake.effects)

    def test_postinit_userdata_seed_and_modules_remain_available(self):
        postinit = (ROOT / "systemvm/debian/opt/cloud/bin/setup/postinit.sh").read_text()
        self.assertIn("datasource_list: ['NoCloud']", postinit)
        self.assertIn("/var/lib/cloud/seed/nocloud/user-data", postinit)
        self.assertIn("cloud-init init --local &&", postinit)
        self.assertIn("cloud-init modules --mode=config &&", postinit)
        self.assertIn("cloud-init modules --mode=final", postinit)
        self.assertIn("  - scripts-user", postinit)

    def test_valid_static_matching_is_idempotent_and_keeps_aliases(self):
        before = copy.deepcopy(self.fake.addresses);meta = self.resolver.stat()
        self.assertTrue(self.net.prepare()["success"])
        self.assertEqual([], self.fake.effects)
        self.assertEqual(before, self.fake.addresses);self.assertEqual(meta, self.resolver.stat())

    def test_gateway_special_values_rejected_before_mutation_and_31_32_without_gateway_allowed(self):
        for gateway in ("0.0.0.0", "192.0.2.10", "192.0.2.0", "192.0.2.255", "127.0.0.1", "169.254.0.1", "224.0.0.1", "240.0.0.1", "198.51.100.1"):
            with self.assertRaises(ValueError):
                m.declaration({**STATIC, "gateway": gateway})
        for prefix in (31, 32):
            self.assertEqual("STATIC", m.declaration({**STATIC, "cidr": "192.0.2.10/" + str(prefix), "gateway": None})["mode"])
        self.assertEqual([], self.fake.effects)

    def test_invalid_or_empty_static_state_does_not_fall_back_to_dhcp(self):
        for text in ("{}", "", '{"ipAddress":"192.0.2.10","ipAddress":"192.0.2.11"}', '{"mode":"OTHER"}'):
            self.state.write_text(text)
            self.assertEqual("ERROR", self.net.inspect()["status"])
            with self.assertRaises((ValueError, KeyError)):
                self.net.prepare()
        self.assertEqual([], self.fake.effects)

    def test_semantic_boolean_and_numeric_static_dns_gateway_fields_are_not_optional_null(self):
        for change in ({"gateway": False}, {"gateway": 0}, {"dns1": False}, {"dns2": 123}, {"ipAddress": True}, {"cidr": 24}):
            self.state.write_text(json.dumps({**STATIC, **change}))
            self.assertEqual("ERROR", self.net.inspect()["status"])
            with self.assertRaises(ValueError):
                self.net.prepare()
        with self.assertRaises(ValueError):
            m.address(True)
        self.assertEqual([], self.fake.effects)

    def test_no_gateway_removes_only_selected_nic_default(self):
        self.state.write_text(json.dumps({**STATIC, "gateway": None}))
        other = {"dst": "default", "dev": "eth9", "gateway": "198.51.100.1"}
        self.fake.defaults.append(other)
        self.assertTrue(self.net.prepare()["success"])
        self.assertIn(other, self.fake.defaults)
        self.assertIn(["ip", "-4", "route", "del", "default", "dev", "ens913"], self.fake.effects)
        self.assertFalse(any("flush" in row for row in self.fake.effects))
        self.assertEqual("192.0.2.20", self.fake.addresses[1]["local"])

    def test_route_mismatch_inspect_is_error_without_repair(self):
        self.fake.defaults[0]["gateway"] = "192.0.2.254"
        observed = self.net.inspect()
        self.assertEqual("ERROR", observed["status"]);self.assertFalse(observed["success"])
        self.assertIn("GATEWAY_MISMATCH", observed["mismatches"]);self.assertEqual([], self.fake.effects)

    def test_joined_dns_takes_priority_over_static_and_reapplication_is_read_only_when_matching(self):
        self.domain.write_text(json.dumps({"joinState": "JOINED", "domainName": "ablestack.local", "dnsServers": ["192.0.2.53"]}))
        self.domain.chmod(0o600)
        self.assertTrue(self.net.prepare()["success"])
        self.assertEqual("search ablestack.local\nnameserver 192.0.2.53\n", self.resolver.read_text())
        meta = self.resolver.stat();self.net.prepare();self.assertEqual(meta, self.resolver.stat())
        self.assertEqual(["192.0.2.53"], self.net.inspect()["expected"]["dns"])

    def test_missing_state_dhcp_uses_discovered_non_eth0_and_unmasks_only_it(self):
        self.state.unlink();self.fake.masked = True
        observed = self.net.prepare()
        self.assertTrue(observed["success"]);self.assertEqual("DHCP", observed["mode"])
        self.assertIn(["systemctl", "unmask", "cloud-dhclient@ens913.service"], self.fake.effects)
        self.assertTrue(all("eth0" not in " ".join(row) for row in self.fake.effects))

    def test_initial_dhcp_converges_when_qga_atomically_injects_static(self):
        self.state.unlink();self.fake.defaults = [];self.fake.addresses = []
        self.fake.on_start = lambda: self.state.write_text(json.dumps(STATIC))
        result = self.net.prepare()
        self.assertTrue(result["success"]);self.assertEqual("STATIC", result["mode"])
        self.assertTrue(self.fake.masked);self.assertFalse(self.fake.active)

    def test_proven_live_dhcp_lease_removed_but_explicit_alias_kept(self):
        self.fake.active, self.fake.masked, self.fake.client_count = True, False, 1
        self.fake.addresses[0]["local"] = "192.0.2.100"
        lease = self.base / "dhclient.ens913.leases"
        lease.write_text('lease {\n interface "ens913";\n fixed-address 192.0.2.100;\n option subnet-mask 255.255.255.0;\n}\n');lease.chmod(0o600)
        self.net.clients = lambda name: [{"argv": [b"dhclient", b"-lf", os.fsencode(lease), b"ens913"]}] if self.fake.client_count else []
        self.assertTrue(self.net.prepare()["success"])
        self.assertNotIn("192.0.2.100", {r["local"] for r in self.fake.addresses})
        self.assertIn("192.0.2.20", {r["local"] for r in self.fake.addresses})
        self.assertIn("192.0.2.10", {r["local"] for r in self.fake.addresses})
        self.assertFalse(any("flush" in row for row in self.fake.effects))

    def test_ambiguous_live_dhcp_address_is_fail_closed_before_stop(self):
        self.fake.active, self.fake.masked, self.fake.client_count = True, False, 1
        self.fake.addresses[0]["local"] = "192.0.2.100"
        self.net.clients = lambda name: [{"argv": [b"dhclient", b"ens913"]}]
        with self.assertRaises(ValueError):
            self.net.prepare()
        self.assertEqual([], self.fake.effects)

    def test_dhcp_argument_parser_never_treats_option_values_as_nic_or_accepts_unknown_options(self):
        self.assertEqual(["ens913"], m.dhcp_interfaces([b"dhclient", b"-e", b"ens913", b"-pf", b"/run/owned", b"ens913"]))
        self.assertEqual(["ens913", "eth9"], m.dhcp_interfaces([b"dhclient", b"ens913", b"eth9"]))
        with self.assertRaises(ValueError):
            m.dhcp_interfaces([b"dhclient", b"--unknown", b"ens913"])

    def test_same_named_dhcp_in_foreign_netns_is_not_an_owned_client(self):
        proc = self.base / "proc";proc.mkdir();(proc / "self/ns").mkdir(parents=True)
        (proc / "self/ns/net").symlink_to("/proc/self/ns/net")
        binary = self.base / "dhclient";binary.write_text("fixture");binary.chmod(0o755)
        row = proc / "123";(row / "ns").mkdir(parents=True)
        (row / "comm").write_bytes(b"dhclient\n");(row / "cmdline").write_bytes(b"/sbin/dhclient\0-4\0ens913\0")
        (row / "stat").write_bytes(b"123 (dhclient) S 1 " + b"0 " * 17 + b"321\n")
        (row / "exe").symlink_to(binary)
        foreign = self.base / "other-netns";foreign.write_text("fixture")
        (row / "ns/net").symlink_to(foreign)
        real = m.Network(self.state, self.fake, self.resolver, self.domain, proc=proc)
        self.assertEqual([], real.clients("ens913"))
        (row / "ns/net").unlink();(row / "ns/net").symlink_to("/proc/self/ns/net")
        self.assertEqual(123, real.clients("ens913")[0]["pid"])
        (row / "cmdline").write_bytes(b"/sbin/dhclient\0ens913\0eth9\0")
        with self.assertRaises(ValueError):
            real.clients("ens913")

    def test_pid_reuse_and_process_permission_failure_never_signal(self):
        record = {"pid": 123, "uid": 0, "start": 1, "argv": [b"dhclient", b"ens913"], "exe": "/sbin/dhclient", "binary": (1, 2, 0, 0o100755), "netns": (1, 3)}
        real = m.Network(self.state, self.fake, self.resolver, self.domain)
        real.clients = lambda name: [record]
        with patch.object(m.os, "pidfd_open", return_value=99), patch.object(m.os, "close"), patch.object(m, "process", return_value={**record, "start": 2}), patch.object(m.signal, "pidfd_send_signal") as send:
            with self.assertRaises(ValueError):real.stop_clients("ens913")
            send.assert_not_called()
        with patch.object(m, "process", side_effect=PermissionError()), patch.object(m.os, "pidfd_open", return_value=99), patch.object(m.os, "close"), patch.object(m.signal, "pidfd_send_signal") as send:
            with self.assertRaises(PermissionError):real.stop_clients("ens913")
            send.assert_not_called()

    def test_unknown_observation_is_never_ok(self):
        with patch.object(self.net, "clients", side_effect=PermissionError()):
            value = self.net.inspect()
        self.assertEqual("UNKNOWN", value["status"]);self.assertFalse(value["success"])
    def test_legacy_two_literal_newline_nameservers_correct_with_zero_ip_route_pid_effect(self):
        content = r"nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
        self.state.write_text(json.dumps({**STATIC, "dns1": "8.8.8.8", "dns2": "8.8.4.4"}))
        self.resolver.write_text(content)
        before = (copy.deepcopy(self.fake.addresses), copy.deepcopy(self.fake.defaults), copy.deepcopy(self.fake.connected))
        observed = self.net.inspect()
        self.assertEqual("ERROR", observed["status"]);self.assertEqual(["DNS_MISMATCH"], observed["mismatches"])
        self.assertEqual([], observed["actual"]["dns"]);self.assertEqual(content, self.resolver.read_text())
        self.assertTrue(self.net.prepare()["success"])
        self.assertEqual("nameserver 8.8.8.8\nnameserver 8.8.4.4\n", self.resolver.read_text())
        self.assertEqual(before, (self.fake.addresses, self.fake.defaults, self.fake.connected));self.assertEqual([], self.fake.effects)

    def test_single_literal_newline_is_typed_content_error_and_explicit_prepare_corrects(self):
        self.resolver.write_text(r"nameserver 203.0.113.53\n")
        observed = self.net.inspect()
        self.assertEqual("ERROR", observed["status"]);self.assertEqual(["DNS_MISMATCH"], observed["mismatches"])
        self.assertEqual([], observed["actual"]["dns"])
        self.assertTrue(self.net.prepare()["success"]);self.assertEqual([], self.fake.effects)

    def test_malformed_content_without_expected_dns_stays_blocked_with_no_guessed_dns(self):
        content = r"nameserver 8.8.8.8\n"
        self.state.write_text(json.dumps({**STATIC, "dns1": None}));self.resolver.write_text(content)
        observed = self.net.inspect()
        self.assertEqual("ERROR", observed["status"]);self.assertEqual(["DNS_MISMATCH"], observed["mismatches"])
        with self.assertRaises(ValueError):self.net.prepare()
        self.assertEqual(content, self.resolver.read_text());self.assertEqual([], self.fake.effects)

    def test_resolver_unsafe_structure_and_mode_remain_unknown_and_are_not_rewritten(self):
        original = self.base / "other-resolver";original.write_text("nameserver 203.0.113.53\n")
        self.resolver.unlink();self.resolver.symlink_to(original)
        observed = self.net.inspect();self.assertEqual("UNKNOWN", observed["status"])
        with self.assertRaises(ValueError):self.net.prepare()
        self.assertTrue(self.resolver.is_symlink());self.assertEqual("nameserver 203.0.113.53\n", original.read_text())
        self.resolver.unlink();self.resolver.write_text("nameserver 203.0.113.53\n");self.resolver.chmod(0o666)
        self.assertEqual("UNKNOWN", self.net.inspect()["status"])
        with self.assertRaises(ValueError):self.net.prepare()
        self.assertEqual([], self.fake.effects)

    def test_resolver_permission_eio_and_nonascii_remain_unknown(self):
        original = m.protected_text
        for error in (PermissionError(), OSError(5, "SYNTHETIC_ONLY")):
            def read(path):
                if path == self.resolver:
                    raise error
                return original(path)
            with patch.object(m, "protected_text", side_effect=read):
                self.assertEqual("UNKNOWN", self.net.inspect()["status"])
                with self.assertRaises(ValueError):self.net.prepare()
        self.resolver.write_bytes(b"nameserver \xff\n")
        self.assertEqual("UNKNOWN", self.net.inspect()["status"])
        with self.assertRaises(ValueError):self.net.prepare()
        self.assertEqual([], self.fake.effects)

    def test_malformed_content_uses_validated_joined_dns_without_static_dns_or_network_churn(self):
        self.domain.write_text(json.dumps({"joinState": "JOINED", "domainName": "ablestack.local", "dnsServers": ["192.0.2.53"]}));self.domain.chmod(0o600)
        self.resolver.write_text(r"nameserver 8.8.8.8\n")
        self.assertEqual("ERROR", self.net.inspect()["status"]);self.assertTrue(self.net.prepare()["success"])
        self.assertEqual("search ablestack.local\nnameserver 192.0.2.53\n", self.resolver.read_text())
        self.assertEqual([], self.fake.effects)

    def test_valid_commented_nameserver_retains_content_and_has_no_mismatch(self):
        content = "# harmless\nnameserver 203.0.113.53 # declared\n"
        self.resolver.write_text(content)
        self.assertEqual("OK", self.net.inspect()["status"]);self.assertEqual(content, self.resolver.read_text())
        self.assertEqual([], self.fake.effects)


class MonitorAndBootTest(unittest.TestCase):
    def monitor(self, known=True, gateway_ok=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            native = root / "storagectl"
            native.write_text("#!/usr/bin/env python3\nimport json,sys\nargs=sys.argv[1:]\nprint(json.dumps({'success':True,'status':'ok','bootHeld':True,'password':'SYNTHETIC_ONLY'}))\n")
            native.chmod(0o755)
            state = root / "state.json";state.write_text(json.dumps(STATIC));state.chmod(0o600)
            resolver = root / "resolv.conf";resolver.write_text("nameserver 203.0.113.53\n")
            fake = Fake()
            if not gateway_ok:
                fake.defaults[0]["gateway"] = "192.0.2.254"
            helper = m.Network(state, fake, resolver, root / "domain.json")
            helper.clients = lambda name: []
            observed = helper.inspect()
            response = root / "network.json";response.write_text(json.dumps(observed))
            executable = root / "network-helper"
            if known:
                executable.write_text("#!/bin/sh\ncat " + str(response) + "\nexit " + ("0" if observed["success"] else "1") + "\n")
                executable.chmod(0o755)
            environment = {**os.environ, "ABLESTACK_STORAGECTL": str(native), "ABLESTACK_SHAREDFS_NETWORK_HELPER": str(executable),
                           "ABLESTACK_STORAGE_MONITOR_CACHE_DIR": str(root / "cache")}
            run = subprocess.run(["bash", str(ROOT / "systemvm/debian/usr/local/bin/ablestack-storage-monitor"), "--once"],
                                 env=environment, capture_output=True, timeout=20)
            self.assertEqual(0, run.returncode, run.stderr.decode())
            raw = (root / "cache/health.json").read_text()
            self.assertNotIn("SYNTHETIC_ONLY", raw)
            return json.loads(raw)

    def test_monitor_real_cache_retains_ok_producer_shape(self):
        health = self.monitor()
        self.assertTrue(health["success"]);self.assertEqual("ok", health["status"])
        self.assertEqual("OK", health["networkReadiness"]["status"])
        self.assertTrue(health["networkReadiness"]["success"])
        self.assertEqual("ens913", health["networkReadiness"]["actual"]["interfaceName"])

    def test_monitor_route_mismatch_is_api_cache_error(self):
        health = self.monitor(gateway_ok=False)
        self.assertFalse(health["success"]);self.assertEqual("error", health["status"])
        self.assertEqual("ERROR", health["networkReadiness"]["status"])
        self.assertIn("GATEWAY_MISMATCH", health["networkReadiness"]["mismatches"])
        self.assertIn({"collector": "network", "code": "GATEWAY_MISMATCH"}, health["errors"])

    def test_monitor_missing_helper_is_typed_unknown_and_never_green(self):
        health = self.monitor(known=False)
        self.assertFalse(health["success"]);self.assertEqual("error", health["status"])
        self.assertEqual("UNKNOWN", health["networkReadiness"]["status"])
        self.assertIsNone(health["networkReadiness"]["actual"]["dhcpProcessCount"])

    def test_boot_and_initialization_share_ready_path_without_monitor_cycle(self):
        base = ROOT / "systemvm/debian"
        boot = (base / "usr/local/bin/ablestack-storage-boot-reconcile").read_text()
        setup = (base / "opt/cloud/bin/setup/sharedfsvm.sh").read_text()
        init = (base / "opt/cloud/bin/setup/init.sh").read_text()
        reconcile = (base / "etc/systemd/system/ablestack-storage-reconcile.service").read_text()
        self.assertLess(boot.index("ablestack-sharedfs-network --prepare"), boot.index('rendered="$(boot_bounded_exec'))
        self.assertNotIn("dhclient -4", boot);self.assertNotIn("dhclient -4", setup)
        self.assertNotIn("enable --now ablestack-storage-monitor", setup)
        self.assertIn("systemctl enable ablestack-storage-monitor.service >/dev/null 2>&1 || true", setup)
        self.assertIn('if [ ! -e /etc/ablestack-storage/sharedfs-network.json ]', init)
        self.assertIn("Requires=ablestack-storage-runtime-bootstrap.service ablestack-sharedfs-network.service", reconcile)
        self.assertNotIn("cloud-dhclient@", reconcile);self.assertNotIn("network-online", reconcile)

    @unittest.skipUnless(shutil.which("systemd-analyze"), "systemd verification tool unavailable")
    def test_actual_systemd_ordering_has_no_cycle(self):
        base = ROOT / "systemvm/debian"
        names = ["ablestack-sharedfs-network.service", "cloud-dhclient@.service", "ablestack-storage-reconcile.service",
                 "ablestack-storage-monitor.service", "ablestack-storage-runtime-bootstrap.service",
                 "cloud-preinit.service", "cloud-early-config.service"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory);units = root / "etc/systemd/system";units.mkdir(parents=True)
            for name in names:
                shutil.copyfile(base / "etc/systemd/system" / name, units / name)
            for target in ("local-fs", "sysinit", "basic", "shutdown", "multi-user", "network", "network-pre", "network-online", "sockets", "paths", "timers"):
                (units / (target + ".target")).write_text("[Unit]\nDescription=Isolated verification target\nDefaultDependencies=no\n")
            for name in ("qemu-guest-agent", "rtslib-fb-targetctl"):
                (units / (name + ".service")).write_text("[Unit]\nDescription=External dependency\nDefaultDependencies=no\nAfter=local-fs.target\n[Service]\nType=oneshot\nExecStart=/bin/true\nRemainAfterExit=yes\n")
            for name in ("bin/sh", "bin/true", "sbin/dhclient", "usr/local/sbin/ablestack-sharedfs-network", "usr/local/bin/ablestack-storage-boot-reconcile",
                         "usr/local/bin/ablestack-storage-monitor", "usr/local/bin/ablestack-storage-runtime-updater",
                         "opt/cloud/bin/setup/init.sh", "opt/cloud/bin/setup/cloud-early-config"):
                path = root / name;path.parent.mkdir(parents=True, exist_ok=True);path.write_text("#!/bin/sh\nexit 0\n");path.chmod(0o755)
            run = subprocess.run(["systemd-analyze", "--root=" + str(root), "verify", "--man=no", "--generators=no", *names],
                                 capture_output=True, text=True, timeout=20)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertEqual("", run.stderr)

if __name__ == "__main__":
    unittest.main(verbosity=2)
