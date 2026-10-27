# Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
# See NOTICE. Apache License, Version 2.0. http://www.apache.org/licenses/LICENSE-2.0
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
source=Path(os.environ.get("NFS_IDENTITY_TEST_MODULE",str(Path(__file__).resolve().parents[1]/"debian/usr/local/lib/ablestack-storage/configuration_transaction.py")))
spec=importlib.util.spec_from_file_location("idmapping",source)
txn=importlib.util.module_from_spec(spec);spec.loader.exec_module(txn)
class IdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name);self.conf=self.root/"conf";self.run=self.root/"run"
        self.conf.mkdir();self.run.mkdir()
        self.mapper=txn.NfsIdentityMapping(self.conf,self.run,self.root/"desired.json")
        self.identity={"pid":22,"boot":"boot-a","start":"123"}
        self.proc=patch.object(txn.NfsIdentityMapping,"process",return_value=self.identity);self.proc.start()
        self.domain=patch.object(txn.subprocess,"run")
        result=self.domain.start().return_value;result.returncode=0;result.stdout="localdomain"
        self.value={txn.NfsIdentityMapping.MODE:"NUMERIC",txn.NfsIdentityMapping.REVISION:13}
    def tearDown(self):
        self.proc.stop();self.domain.stop();self.temp.cleanup()
    def endpoint(self,key,port,mode):
        path=self.conf/(key+".conf")
        path.write_text("EXPORT_DEFAULTS { SecType=sys; } NFS_Core_Param { NFS_Port = %s; }\nNFSV4 {\n%s\n}\n" % (port,"\n".join(self.mapper.lines(mode))))
        (self.run/(key+".pid")).write_text("22")
        return path
    def test_all_ports_have_same_policy_and_observed_revision(self):
        for port in (2049,2050):
            key="0.0.0.0_"+str(port);path=self.endpoint(key,port,self.value)
            self.mapper.publish(key,path,22,self.value)
        result=self.mapper.verify(self.value)
        self.assertEqual("APPLIED",result["observationstatus"])
        self.assertEqual([13,13],[r["observedrevision"] for r in result["endpoints"]])
    def test_file_edit_alone_cannot_claim_new_loaded_mode(self):
        old={};path=self.endpoint("a",2049,old);self.mapper.publish("a",path,22,old)
        self.endpoint("a",2049,self.value)
        self.assertEqual("DRIFT",self.mapper.observe(self.value)["observationstatus"])
    def test_new_pid_or_boot_is_unknown_not_applied(self):
        path=self.endpoint("a",2049,self.value);self.mapper.publish("a",path,22,self.value)
        self.identity["start"]="999"
        self.assertEqual("UNKNOWN",self.mapper.observe(self.value)["observationstatus"])
    def test_missing_receipt_is_legacy_and_read_does_not_write(self):
        self.endpoint("a",2049,{})
        self.assertEqual("LEGACY_UNCONFIRMED",self.mapper.observe({})["observationstatus"])
        self.assertEqual([],list(self.run.glob("*.identity.json")))
    def test_rollback_legacy_implicit_defaults_match_new_process_receipt(self):
        path=self.endpoint("a",2049,{});self.mapper.publish("a",path,22,{})
        path.write_text("EXPORT_DEFAULTS { SecType=sys; } NFS_Core_Param { NFS_Port = 2049; }\nNFSV4 { Grace_Period=5; }")
        self.assertEqual("APPLIED",self.mapper.verify({})["observationstatus"])
    def test_second_port_failure_prevents_verified(self):
        path=self.endpoint("a",2049,self.value);self.mapper.publish("a",path,22,self.value)
        self.endpoint("b",2050,self.value)
        with self.assertRaisesRegex(txn.Rejected,"NFS_IDENTITY_RUNTIME_MISMATCH"):
            self.mapper.verify(self.value)
    def test_removed_second_port_config_is_drift(self):
        for port in (2049,2050):
            key="0.0.0.0_"+str(port);path=self.endpoint(key,port,self.value)
            self.mapper.publish(key,path,22,self.value)
        self.mapper.complete(["0.0.0.0_2049","0.0.0.0_2050"],self.value)
        (self.conf/"0.0.0.0_2050.conf").unlink()
        with self.assertRaises(txn.Rejected):self.mapper.verify(self.value)
    def test_corrupt_receipt_is_unknown_without_breaking_other_capabilities(self):
        self.endpoint("a",2049,self.value)
        (self.run/"a.identity.json").write_text("{broken")
        self.assertEqual("UNKNOWN",self.mapper.observe(self.value)["observationstatus"])
    def test_numeric_rejects_other_security_flavors(self):
        path=self.endpoint("a",2049,self.value)
        path.write_text(path.read_text().replace("SecType=sys", "SecType=krb5p"))
        with self.assertRaisesRegex(txn.Rejected,"NFS_NUMERIC_AUTH_SYS_REQUIRED"):
            self.mapper.publish("a",path,22,self.value)
    def test_invalid_mode_and_revision_fail_without_files(self):
        for v in ({txn.NfsIdentityMapping.MODE:"invalid"},{txn.NfsIdentityMapping.REVISION:-1}):
            with self.assertRaises(txn.Rejected):self.mapper.lines(v)
        self.assertEqual([],list(self.conf.iterdir()))
    def test_adapter_verification_really_invokes_the_policy_gate(self):
        adapter=txn.RuntimeAdapter(self.root/"operation")
        adapter.private.mkdir(exist_ok=True)
        txn.atomic_json(adapter.private/"runner-result.json", {"returncode":0,"result":{"success":True}})
        with patch.object(txn.NfsIdentityMapping,"verify",side_effect=txn.Rejected("EXPECTED_POLICY_GATE")):
            with self.assertRaisesRegex(txn.Rejected,"EXPECTED_POLICY_GATE"):
                adapter.verify({"nfs export apply": self.value})
        self.assertNotIn(self.run/"a.identity.json",adapter.file_paths({"candidate":{"nfs export apply":self.value}}))
    def test_no_exports_is_not_running(self):
        self.assertEqual("NOT_RUNNING",self.mapper.verify(self.value)["observationstatus"])
    def test_observation_leaves_data_owner_and_mode_unchanged(self):
        data=self.root/"data";data.write_bytes(b"preserve");os.chmod(data,0o640)
        before=data.stat()
        path=self.endpoint("a",2049,self.value);self.mapper.publish("a",path,22,self.value);self.mapper.verify(self.value)
        after=data.stat()
        self.assertEqual((before.st_uid,before.st_gid,before.st_mode),(after.st_uid,after.st_gid,after.st_mode))
        self.assertEqual(b"preserve",data.read_bytes())
