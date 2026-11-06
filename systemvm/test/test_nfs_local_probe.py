# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Run the actual CLI function, not a second implementation of the selection rules."""
import ast
import copy
import ipaddress
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"debian/usr/local/lib/ablestack-storage"))
import configuration_transaction as tx

def actual_probe(source=None):
    source=source or (ROOT/"debian/usr/local/bin/ablestack-storagectl").read_text()
    body=source.split("apply_nfs_exports() {",1)[-1]
    lines=body.splitlines()
    start=next(i for i,line in enumerate(lines) if line.startswith("def probe_nfs_export_visibility("))
    end=next(i for i in range(start+1,len(lines)) if lines[i] and not lines[i][0].isspace())
    return compile(ast.parse("\n".join(lines[start:end])),"actual CLI visibility probe","exec")

class LocalProbe(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.calls=[];self.mount_failure=False;self.ip_failure=False
        self.env={"subprocess":subprocess,"ipaddress":ipaddress,"tempfile":tempfile,"shutil":shutil,
                  "endpoint_key":lambda ip,port:str(port),"nfs_probe_root":self.tmp.name}
        exec(actual_probe(),self.env)
    def tearDown(self):self.tmp.cleanup()
    def run_command(self,args,**kwargs):
        self.calls.append(args)
        if args[:2]==["ip","-4"]:
            return subprocess.CompletedProcess(args,1 if self.ip_failure else 0,
                "2: eth0 inet 10.10.13.220/16 scope global eth0\n","")
        if args[0]=="mount":
            return subprocess.CompletedProcess(args,32 if self.mount_failure else 0,"","access denied" if self.mount_failure else "")
        if args[0]=="umount":return subprocess.CompletedProcess(args,0,"","")
        raise AssertionError(args)
    def export(self,clients,**extra):
        return {"uuid":"share","name":"team","path":"/export/team","pseudo":"/team","protocolMode":"V4_ONLY",
                "clients":[{"clients":v}for v in clients],**extra}
    def check(self,rows):
        runtime=[{"listenIp":"0.0.0.0","port":2049}]
        original=copy.deepcopy(rows)
        with patch.object(subprocess,"run",side_effect=self.run_command):
            failures=self.env["probe_nfs_export_visibility"](runtime,{("0.0.0.0",2049):rows})
        self.assertEqual(original,rows)
        return runtime[0],failures
    def test_two_remote_clients_do_not_add_server_acl_or_mount(self):
        runtime,failures=self.check([self.export(["10.10.254.224/32","10.10.254.223/32"])])
        self.assertFalse(failures);self.assertTrue(runtime["probeSuccess"]);self.assertFalse(runtime["probeComplete"])
        self.assertIsNone(runtime["exportsVisible"]);self.assertIsNone(runtime["probeResults"][0]["success"])
        self.assertEqual("EXTERNAL_CLIENT_REQUIRED",runtime["probeResults"][0]["status"])
        self.assertEqual(["ip"],[a[0]for a in self.calls])
    def test_local_allowed_retains_real_mount_and_unmount_verification(self):
        for clients in [["10.10.0.0/16"],["*"]]:
            with self.subTest(clients=clients):
                self.calls=[];runtime,failures=self.check([self.export(clients)])
                self.assertFalse(failures);self.assertTrue(runtime["probeComplete"]);self.assertTrue(runtime["exportsVisible"])
                self.assertEqual("MOUNT_VERIFIED",runtime["probeResults"][0]["status"])
                self.assertEqual(["ip","mount","umount"],[a[0]for a in self.calls])
    def test_real_mount_failure_still_fails(self):
        self.mount_failure=True
        runtime,failures=self.check([self.export(["10.10.0.0/16"])])
        self.assertEqual([runtime],failures);self.assertFalse(runtime["probeSuccess"])
        self.assertEqual("FAILED",runtime["probeResults"][0]["status"])
    def test_unknown_host_policy_and_ip_observation_failure_do_not_bypass_checks(self):
        runtime,failures=self.check([self.export(["unknown.example.test"])])
        self.assertEqual([runtime],failures)
        self.ip_failure=True
        with self.assertRaisesRegex(RuntimeError,"NFS_LOCAL_ADDRESS_OBSERVATION_UNAVAILABLE"):
            self.check([self.export(["10.10.254.224/32"])])
    def test_skipped_export_cannot_mask_another_export_failure(self):
        self.mount_failure=True
        runtime,failures=self.check([self.export(["10.10.254.224/32"]),self.export(["10.10.0.0/16"],uuid="second")])
        self.assertEqual([runtime],failures)
        self.assertEqual(["EXTERNAL_CLIENT_REQUIRED","FAILED"],[r["status"]for r in runtime["probeResults"]])
    def test_failed_runner_result_survives_later_rollback_runner(self):
        private=Path(self.tmp.name)/"op";private.mkdir();adapter=tx.RuntimeAdapter(private)
        results=[{"returncode":1,"result":{"code":"NFS_EXPORT_VISIBILITY_FAILED","failures":[{"port":2049}]}},
                 {"returncode":0,"result":{"success":True}}]
        class Process:
            pid=os.getpid()
            def __init__(self,*args,**kwargs):tx.atomic_json(private/"runner-result.json",results.pop(0))
            def poll(self):return 0
        with patch.object(subprocess,"Popen",Process):
            with self.assertRaisesRegex(tx.Rejected,"PROTOCOL_APPLY_FAILED"):adapter.run("nfs export apply",{})
            original=tx.load(private/"original-runner-result.json")
            adapter.run("nfs export apply",{})
        self.assertEqual(original,tx.load(private/"original-runner-result.json"))
        self.assertEqual(1,original["returncode"]);self.assertEqual(0,tx.load(private/"runner-result.json")["returncode"])
    def test_transaction_preserves_safe_first_failure_detail(self):
        class Adapter:
            def __init__(self,private):self.private=private
            def preflight(self,payload):pass
            def snapshot(self,payload):pass
            def apply(self,commands):
                tx.atomic_json(self.private/"original-runner-result.json",{"result":{"code":"NFS_EXPORT_VISIBILITY_FAILED","failures":[{"port":2049}]}})
                raise tx.Rejected("PROTOCOL_APPLY_FAILED")
            def restore(self):pass
            def verify_restored(self):pass
        payload={"instanceUuid":str(uuid.uuid4()),"operationid":str(uuid.uuid4()),"baseRevision":0,"targetRevision":1,"candidate":{"nfs export apply":{}}}
        result=tx.Transaction(Path(self.tmp.name)/"tx",Adapter).execute("apply",payload)
        self.assertEqual("ROLLED_BACK",result["state"])
        self.assertEqual("NFS_EXPORT_VISIBILITY_FAILED",result["originalfailure"]["detail"]["code"])
        self.assertEqual("SUCCEEDED",result["rollback"]["state"])
if __name__=="__main__":unittest.main()
