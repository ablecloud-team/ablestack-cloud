# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "debian/usr/local/lib/ablestack-storage"))
import smb_client_access as h
import configuration_transaction as tx
import configuration_preflight as pf
import runtime_updater as updater


class ClientAccessContract(unittest.TestCase):
    def payload(self):
        return {"enabled": True, "listeners": [{"listenIp": "10.10.13.220", "port": 445}],
                "shares": [{"uuid": "share", "name": "team", "state": "Ready",
                            "config": {"keep": "보존", "fileIdentity": {"version": 1, "mode": "FIXED", "uid": 7001, "gid": 7002}},
                            "acls": [{"principal": "user", "permission": "READ_WRITE", "config": {"passwordSupplied": True}}]}]}

    def test_literal_normalization_and_invalid_sources(self):
        self.assertEqual(["10.10.13.0/24", "10.10.254.224/32"], h.normalize(["10.10.254.224", "10.10.13.22/24"]))
        for value in [None, "", {}, [None], [True], ["host.local"], ["*"], ["ALL"], ["10.01.2.3"],
                      ["10.0.0.1/0"], ["10.0.0.1/33"], ["1.2.3.256"], ["::1"], ["224.0.0.1"],
                      ["0.0.0.0"], ["255.255.255.255"], ["10.0.0.1\nhosts allow=ALL"],
                      ["10.0.0.1", "10.0.0.1/32"], ["10.0.0.1/24", "10.0.0.0/24"], ["1.2.3."+str(i) for i in range(33)]]:
            with self.subTest(value=value), self.assertRaises(h.PolicyError):
                h.normalize(value)

    def test_missing_empty_and_bad_stored_policy_are_distinct(self):
        self.assertEqual([], h.policy({}))
        self.assertEqual([], h.policy({"clientAccess": {"version": 1, "cidrs": []}}))
        for value in [None, {}, {"version": True, "cidrs": []}, {"version": 1, "cidrs": None},
                      {"version": 1, "cidrs": [], "hosts allow": "ALL"}]:
            with self.subTest(value=value), self.assertRaises(h.PolicyError):
                h.policy({"clientAccess": value})

    def test_strict_single_share_diff_preserves_real_acl_and_posix(self):
        before=self.payload();after=copy.deepcopy(before)
        after["shares"][0]["config"]["clientAccess"]={"version": 1, "cidrs": ["10.10.254.224"]}
        after["shares"][0]["state"]="Updating"
        after["shares"][0]["acls"][0]["config"]["passwordSupplied"]=False
        self.assertEqual(["10.10.254.224/32"], h.difference(before,after)["after"])
        self.assertNotIn("clientAccess",before["shares"][0]["config"])
        for field in ["path", "name", "acls", "fileIdentity", "listeners"]:
            bad=copy.deepcopy(after)
            if field=="listeners":bad["listeners"][0]["port"]=1445
            elif field=="acls":bad["shares"][0]["acls"][0]["permission"]="ADMIN"
            elif field=="fileIdentity":bad["shares"][0]["config"]["fileIdentity"]["uid"]=0
            else:bad["shares"][0][field]="changed"
            with self.subTest(field=field),self.assertRaises(h.PolicyError):h.difference(before,bad)

    def test_renderer_never_applies_restricted_policy_globally(self):
        self.assertEqual([],h.render({}))
        self.assertEqual(["   hosts allow = 10.10.254.224/32 127.0.0.1/32","   hosts deny = ALL"],
                         h.render({"clientAccess":{"version":1,"cidrs":["10.10.254.224"]}}))

    def test_ipv6_wildcard_and_missing_protection_fail_before_apply(self):
        value=self.payload();value["shares"][0]["config"]["clientAccess"]={"version":1,"cidrs":["10.0.0.1"]}
        with patch.object(h,"protected",return_value=False),self.assertRaises(h.PolicyError):h.validate_payload(value)
        with patch.object(h,"protected",return_value=True):
            h.validate_payload(value)
            for ip in ["0.0.0.0","::","host.local","fd00::1"]:
                value["listeners"][0]["listenIp"]=ip
                with self.subTest(ip=ip),self.assertRaises(h.PolicyError):h.validate_payload(value)
        value["enabled"]=False
        h.validate_payload(value)  # Disabling a protocol never expands access.

    def test_last_rule_preview_needs_confirmation(self):
        row=self.payload()["shares"][0]
        row["config"]["clientAccess"]={"version":1,"cidrs":["10.0.0.1"]}
        with patch.object(h,"protected",return_value=True):
            with self.assertRaises(h.PolicyError) as error:
                h.SmbClientAccessPolicies().preview(row,{"cidrs":[]})
            self.assertEqual("SMB_CLIENT_ACCESS_CONFIRM_ANY_REQUIRED",error.exception.code)

    def test_network_only_route_cannot_masquerade_as_multicommand_apply(self):
        self.assertTrue(tx.RuntimeAdapter.smb_options_only({"onlineSmbClientAccess":True,"candidate":{"smb share apply":{}}}))
        self.assertFalse(tx.RuntimeAdapter.smb_options_only({"onlineSmbClientAccess":True,"candidate":{"smb share apply":{},"nfs export apply":{}}}))

    def test_first_admission_selects_network_engine_before_request_file_exists(self):
        before=self.payload();candidate=copy.deepcopy(before)
        candidate["shares"][0]["config"]["clientAccess"]={"version":1,"cidrs":["10.0.0.1"]}
        value={"instanceUuid":"instance","onlineSmbClientAccess":True,"before":{"smb share apply":before},"candidate":{"smb share apply":candidate}}
        with tempfile.TemporaryDirectory() as directory:
            adapter=tx.RuntimeAdapter(Path(directory))
            self.assertFalse(adapter.payload_path.exists())
            with patch.object(h,"protected",return_value=True),patch.object(h,"SmbClientAccessPolicies") as engine:
                adapter.preflight(value)
                engine.return_value.snapshot.assert_called_once_with(value)
            self.assertTrue(adapter.payload_path.exists())
            self.assertTrue(json.loads(adapter.payload_path.read_text())["onlineSmbClientAccess"])

    def test_fingerprint_includes_source_policy_and_actual_observation(self):
        a={"smbclientaccesstarget":{"uuid":"share"},"smbclientaccess":{"cidrs":["10.0.0.1/32"]},"clientaccessfingerprint":"a"}
        b=copy.deepcopy(a);b["smbclientaccess"]["cidrs"]=["10.0.0.2/32"]
        self.assertNotEqual(pf.fingerprint(a),pf.fingerprint(b))

    def test_restricted_policy_prevents_downgrade_without_helper(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);desired=root/"smb.json"
            desired.write_text(json.dumps(self.payload()))
            updater.require_smb_client_access_compatibility(root,desired)
            value=self.payload();value["shares"][0]["config"]["clientAccess"]={"version":1,"cidrs":["10.0.0.1"]}
            desired.write_text(json.dumps(value))
            with self.assertRaises(updater.RuntimeUpgradeError):updater.require_smb_client_access_compatibility(root,desired)
            helper=root/"lib/ablestack-storage/smb_client_access.py";helper.parent.mkdir(parents=True);helper.write_text("VERSION = 1")
            updater.require_smb_client_access_compatibility(root,desired)

if __name__=="__main__":unittest.main()
