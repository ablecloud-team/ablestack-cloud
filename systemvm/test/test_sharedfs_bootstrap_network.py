# SPDX-License-Identifier: Apache-2.0
"""Isolated STATIC/DHCP bootstrap regressions; never configures the host network."""
import importlib.util,json,os
from pathlib import Path
import subprocess,tempfile,unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("bootnet", ROOT/"debian/usr/local/lib/ablestack-storage/sharedfs_bootstrap_network.py")
bn=importlib.util.module_from_spec(spec);spec.loader.exec_module(bn)

class BootstrapNetworkTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.state=self.root/"state.json";self.links=self.root/"net";self.links.mkdir()
        (self.links/"eth0").mkdir();(self.links/"eth0/address").write_text("02:0d:00:cc:00:48")
        self.helper=self.root/"helper";self.helper.write_text("#!/bin/sh\nexit 0\n");self.helper.chmod(0o755)
        self.payload=dict(macAddress="02:0d:00:cc:00:48",ipAddress="10.10.13.250",cidr="10.10.0.0/16",gateway="10.10.13.1",dns1="8.8.8.8")
    def tearDown(self):self.temp.cleanup()
    def write(self):
        self.state.write_text(json.dumps(self.payload));self.state.chmod(0o600)
    def test_first_boot_and_dhcp_mode_do_not_invoke_static_helper(self):
        with patch.object(bn.subprocess,"run") as run:
            self.assertEqual("DHCP",bn.restore(self.state,self.links,self.helper));run.assert_not_called()
    def test_valid_static_uses_existing_helper_once_and_preserves_state(self):
        self.write();before=self.state.read_bytes()
        with patch.object(bn.subprocess,"run",return_value=subprocess.CompletedProcess([],0,"{}","")) as run:
            self.assertEqual("STATIC",bn.restore(self.state,self.links,self.helper))
            self.assertEqual([str(self.helper),str(self.state)],run.call_args[0][0]);self.assertEqual(1,run.call_count)
        self.assertEqual(before,self.state.read_bytes())
    def test_invalid_static_never_falls_back_to_dhcp(self):
        for key,value in [("ipAddress","10.11.13.250"),("ipAddress","10.10.0.0"),("ipAddress","10.10.255.255"),("ipAddress","::1"),("gateway","10.11.1.1"),("macAddress","02:00:00:00:00:01"),("macAddress","../../unsafe"),("dns1","::1")]:
            with self.subTest(key=key,value=value):
                self.payload[key]=value;self.write()
                with patch.object(bn.subprocess,"run") as run:
                    with self.assertRaises((ValueError,KeyError)):bn.restore(self.state,self.links,self.helper)
                    run.assert_not_called()
                self.payload=dict(macAddress="02:0d:00:cc:00:48",ipAddress="10.10.13.250",cidr="10.10.0.0/16",gateway="10.10.13.1",dns1="8.8.8.8")
    def test_corrupt_state_blocks(self):
        self.write();self.state.write_text("{")
        with self.assertRaises(ValueError):bn.load_static_state(self.state,self.links)
    def test_nonprivate_state_blocks(self):
        self.write();self.state.chmod(0o644)
        with self.assertRaises(ValueError):bn.load_static_state(self.state,self.links)
    def test_symlink_state_blocks(self):
        self.write();self.state.rename(self.root/"real");self.state.symlink_to(self.root/"real")
        with self.assertRaises(OSError):bn.load_static_state(self.state,self.links)
    def test_ambiguous_matching_nics_block(self):
        self.write();(self.links/"eth1").mkdir();(self.links/"eth1/address").write_text(self.payload["macAddress"])
        with self.assertRaises(ValueError):bn.load_static_state(self.state,self.links)
    def test_untrusted_helper_blocks(self):
        self.write();self.helper.chmod(0o777)
        with self.assertRaises(ValueError):bn.restore(self.state,self.links,self.helper)
    def test_helper_failure_does_not_become_dhcp(self):
        self.write()
        with patch.object(bn.subprocess,"run",return_value=subprocess.CompletedProcess([],1,"","fixture")):
            with self.assertRaises(RuntimeError):bn.restore(self.state,self.links,self.helper)
    def test_actual_shell_branch_keeps_dhcp_arguments_and_skips_them_for_static(self):
        source=(ROOT/"debian/opt/cloud/bin/setup/sharedfsvm.sh").read_text().replace('/usr/local/sbin/ablestack-sharedfs-network --interface "${NIC_MACS:-}"', 'printf eth0')
        first=source[source.index("    local sharedfs_boot_network_mode"):source.index("    update-alternatives")]
        start=source.index('    if [ "$sharedfs_boot_network_mode" = "STATIC" ]; then')
        branch=source[start:source.index("\n    fi",start)+len("\n    fi")]
        for mode,expected in [("STATIC",0),("DHCP",1)]:
            script="python3(){ printf '"+mode+"'; }; dhclient(){ printf 'DHCP_CALLED:%s ' \"$@\"; }; log_it(){ :; }; boot(){\n"+first+branch+"\n}; boot"
            r=subprocess.run(["bash","-c",script],capture_output=True,text=True,check=True)
            self.assertEqual(expected,r.stdout.count("DHCP_CALLED:-4"))
            if expected:self.assertIn("DHCP_CALLED:/var/lib/dhcp/dhclient.eth0.leases",r.stdout)
        script="python3(){ return 1; }; dhclient(){ printf BAD; }; boot(){\n"+first+branch+"\n}; boot"
        r=subprocess.run(["bash","-c",script],capture_output=True,text=True)
        self.assertNotEqual(0,r.returncode);self.assertNotIn("BAD",r.stdout)
        self.assertIn("sharedfs_bootstrap_network.py) || exit 1",source)

if __name__=="__main__":unittest.main()
