# SPDX-License-Identifier: Apache-2.0
"""Actual policy/command contracts; host networking is never changed by this suite."""
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.machinery.SourceFileLoader("sharedfs_net", str(ROOT / "debian/usr/local/sbin/ablestack-sharedfs-network"))
spec = importlib.util.spec_from_loader(loader.name, loader)
net = importlib.util.module_from_spec(spec)
loader.exec_module(net)

class NetworkPolicyTest(unittest.TestCase):
    def setUp(self):
        self.raw = dict(macAddress="02:00:00:00:00:52", ipAddress="192.0.2.10",
                        cidr="192.0.2.0/24", gateway="192.0.2.1", dns1="192.0.2.53")
        self.links = [{"ifname":"ens8", "addr_info":[{"family":"inet","local":"192.0.2.10","prefixlen":24},
                                                      {"family":"inet","local":"192.0.2.20","prefixlen":24,"secondary":True}]}]
        self.route = {"dst":"default","gateway":"192.0.2.1","dev":"ens8","metric":100}
        self.other = {"dst":"default","gateway":"198.51.100.1","dev":"ens9","metric":200}
        self.resolver = "nameserver 192.0.2.53\nsearch example.test\noptions timeout:1\n"
    def test_same_static_policy_is_address_route_noop_and_keeps_alias(self):
        p=net.plan(net.validate(self.raw),"ens8",self.links,[self.route,self.other],None,[],self.resolver)
        self.assertFalse(p["addAddress"]); self.assertFalse(p["addDefault"])
        self.assertEqual([],p["removeAddresses"]);self.assertEqual([],p["removeRoutes"])
        self.assertEqual(self.resolver,p["resolver"])
    def test_dhcp_lifetime_is_not_adopted_as_permanent_static(self):
        self.links[0]["addr_info"][0].update(dynamic=True, valid_life_time=30, preferred_life_time=20)
        route={**self.route,"protocol":"dhcp","expires":30}
        p=net.plan(net.validate(self.raw),"ens8",self.links,[route,self.other],None,[],self.resolver)
        self.assertTrue(p["renewPrimary"]);self.assertFalse(p["addAddress"])
        self.assertEqual([route],p["removeRoutes"]);self.assertTrue(p["addDefault"])
        self.assertEqual([],p["removeAddresses"])
    def test_no_gateway_removes_only_previously_owned_via_default(self):
        self.raw.pop("gateway")
        previous={"macAddress":self.raw["macAddress"],"defaultRoute":net.route_key(self.route)}
        p=net.plan(net.validate(self.raw),"ens8",self.links,[self.route,self.other,{"dst":"192.0.2.0/24","dev":"ens8","protocol":"kernel"}],previous,[],self.resolver)
        self.assertEqual([self.route],p["removeRoutes"]);self.assertFalse(p["addDefault"])
        self.assertNotIn("flush",repr(p))
    def test_unknown_default_owner_blocks_before_commands(self):
        self.raw["gateway"]="192.0.2.2"
        with patch.object(net,"run") as run:
            with self.assertRaisesRegex(ValueError,"OWNER_CONFLICT"):
                net.plan(net.validate(self.raw),"ens8",self.links,[self.route],None,[],self.resolver)
            run.assert_not_called()
    def test_invalid_gateway_and_prefix_are_rejected_before_mutation(self):
        for gateway in ["192.0.2.10","192.0.2.0","192.0.2.255","198.51.100.1","::1","224.0.0.1","0.0.0.0"]:
            with self.subTest(gateway=gateway),patch.object(net,"run") as run:
                with self.assertRaises(ValueError):net.validate({**self.raw,"gateway":gateway})
                run.assert_not_called()
        with self.assertRaises(ValueError):net.validate({**self.raw,"cidr":"192.0.2.0/33"})
    def test_prefix31_peer_and_prefix32_no_gateway_match_server_contract(self):
        self.assertEqual(31,net.validate({**self.raw,"cidr":"192.0.2.10/31","gateway":"192.0.2.11"})["prefixlen"])
        self.assertIsNone(net.validate({**self.raw,"cidr":"192.0.2.10/32","gateway":None})["gateway"])
    def test_dns_replacement_preserves_unmanaged_search_options_and_nameserver(self):
        text="nameserver 192.0.2.53\nnameserver 203.0.113.53\nsearch example.test\noptions rotate\n"
        new=net.dns_plan(text,["192.0.2.54"],["192.0.2.53"])
        self.assertNotIn("192.0.2.53",new)
        for value in ["192.0.2.54","203.0.113.53","search example.test","options rotate"]:self.assertIn(value,new)
        self.assertEqual(text,net.dns_plan(text,[],["192.0.2.53"]))
    def test_single_mac_resolves_noneth0_and_ambiguous_nics_block(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/"ens8").mkdir();(root/"ens8/address").write_text(self.raw["macAddress"])
            self.assertEqual("ens8",net.interface_for_mac(self.raw["macAddress"],root))
            (root/"ens9").mkdir();(root/"ens9/address").write_text(self.raw["macAddress"])
            with self.assertRaisesRegex(ValueError,"NIC_OWNER_UNKNOWN"):net.interface_for_mac(self.raw["macAddress"],root)
    def test_private_state_and_symlink_protection(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"state";net.atomic_json(p,self.raw);self.assertEqual(self.raw,net.trusted_json(p))
            p.chmod(0o644)
            with self.assertRaises(ValueError):net.trusted_json(p)
            p.unlink();p.symlink_to(Path(d)/"missing")
            with self.assertRaises(OSError):net.trusted_json(p)
    def test_owned_address_change_never_removes_additional_address(self):
        previous={"macAddress":self.raw["macAddress"],"primary":{"address":"192.0.2.10","prefixlen":24}}
        self.raw["ipAddress"]="192.0.2.11"
        p=net.plan(net.validate(self.raw),"ens8",self.links,[self.route],previous,[],self.resolver)
        self.assertTrue(p["addAddress"]);self.assertEqual([("192.0.2.10",24)],p["removeAddresses"])
    def test_manual_stanza_preserves_other_nic(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"interfaces";other="iface ens9 inet dhcp\n    metric 200\n"
            p.write_text("auto lo ens8 ens9\niface lo inet loopback\niface ens8 inet dhcp\n    metric 100\n"+other)
            net.set_manual_interface("ens8",p)
            self.assertIn(other,p.read_text());self.assertNotIn("iface ens8 inet dhcp",p.read_text())
            self.assertEqual(1,p.read_text().count("iface ens8 inet manual"))
            old=p.read_text();net.set_manual_interface("ens8",p);self.assertEqual(old,p.read_text())
    def test_dhcp_guard_only_blocks_matching_static_nic(self):
        with patch.object(net,"trusted_json",return_value=self.raw),patch.object(net,"interface_for_mac",return_value="ens8"):
            self.assertTrue(net.dhcp_guard("ens8"));self.assertFalse(net.dhcp_guard("ens9"))
        with patch.object(net,"trusted_json",return_value=None):
            self.assertFalse(net.dhcp_guard("ens8"))
    def test_packaged_hook_exits_before_all_static_ipv4_reasons_and_passes_dhcp(self):
        import subprocess
        source=(ROOT/"debian/etc/dhcp/dhclient-enter-hooks.d/ablestack-sharedfs").read_text()
        with tempfile.TemporaryDirectory() as d:
            helper=Path(d)/"helper";helper.write_text("#!/bin/sh\nexit 0\n");helper.chmod(0o755)
            source=source.replace("/usr/local/sbin/ablestack-sharedfs-network",str(helper))
            for reason in ["PREINIT","BOUND","RENEW","REBIND","REBOOT","EXPIRE","FAIL","STOP"]:
                result=subprocess.run(["sh","-c",source+"\nprintf MUTATED"],env={"reason":reason,"interface":"ens8"},text=True,capture_output=True)
                self.assertEqual("",result.stdout,reason)
            helper.write_text("#!/bin/sh\nexit 1\n")
            result=subprocess.run(["sh","-c",source+"\nprintf ORIGINAL_DHCP_HANDLER"],env={"reason":"EXPIRE","interface":"ens8"},text=True,capture_output=True)
            self.assertEqual("ORIGINAL_DHCP_HANDLER",result.stdout)
    def test_boot_network_failure_skips_all_protocol_apply(self):
        import subprocess
        source=(ROOT/"debian/usr/local/bin/ablestack-storage-boot-reconcile").read_text()
        a=source.index('  success=1\n');b=source.index('  if [[ -s "$NFS_PAYLOAD"',a)
        gate=source[a:b]
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);helper=root/"helper";ctl=root/"ctl"
            helper.write_text("#!/bin/sh\nexit 0\n");helper.chmod(0o755)
            ctl.write_text("#!/bin/sh\nexit 1\n");ctl.chmod(0o755)
            gate=gate.replace("NETWORK_HELPER=/usr/local/sbin/ablestack-sharedfs-network","NETWORK_HELPER="+str(helper))
            script='log(){ :; }; sleep(){ :; }; STORAGECTL='+str(ctl)+'; LOG_FILE='+str(root/"log")+'; SLEEP_SECONDS=0; attempt=1; while ((attempt<=1)); do\n'+gate+'\nprintf PROTOCOL_APPLY; break; done'
            result=subprocess.run(["bash","-c",script],text=True,capture_output=True)
            self.assertEqual(0,result.returncode,result.stderr);self.assertNotIn("PROTOCOL_APPLY",result.stdout)
    def test_endpoint_corrupt_state_and_mac_contract_use_actual_runtime_source(self):
        source=(ROOT/"debian/usr/local/bin/ablestack-storagectl").read_text()
        code=source.split("network_endpoint_apply() {",1)[1].split("<<'PY'\n",1)[1].split("\nPY\n",1)[0]
        ns={"__name__":"contract"};defs=code[:code.index("payload = load_json(payload_path")]
        with patch("sys.argv",["fixture","unused"]):exec(defs,ns)
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"state";p.write_text("{")
            with self.assertRaisesRegex(ValueError,"INVALID_ENDPOINT_STATE"):ns["load_json"](str(p),{})
        for value in [-1,33,"oops"]:
            with self.assertRaises((ValueError,TypeError)):ns["requested_prefix"]({"prefixlen":value})
        ns["interface_links"]=lambda:[{"ifname":"ens8","address":self.raw["macAddress"],"link_type":"ether"}]
        self.assertEqual(("ens8",24),ns["find_target_iface"]({"macAddress":self.raw["macAddress"],"prefixlen":24},[]))
        ns["interface_links"]=lambda:[{"ifname":"ens8","address":self.raw["macAddress"]},{"ifname":"ens9","address":self.raw["macAddress"]}]
        with self.assertRaisesRegex(RuntimeError,"NIC_OWNER_UNKNOWN"):ns["find_target_iface"]({"macAddress":self.raw["macAddress"]},[])

if __name__ == "__main__":
    unittest.main()
