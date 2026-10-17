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

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
from local_sam_bootstrap import LocalSamBootstrap
from samba_public_sid import SambaPublicSidMissing


class StorageLocalSamBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.config=self.root/"config";self.config.mkdir(mode=0o700)
        self.scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":3}
        self.current={"instanceUuid":self.scope["instanceUuid"],"operationUuid":str(uuid.uuid4()),"revision":2,"configurationSha256":"a"*64}
        self.boot=str(uuid.uuid4());self.state={"generation":self.current,"generationStatus":"IN_SYNC","pendingOperationUuid":None,"configurationSha256":"a"*64,"bootId":self.boot}
        self.request={**self.scope,"netbiosName":"SERVER","initializationApproved":True,"expectedGeneration":self.current,"expectedConfigurationSha256":"a"*64,"expectedBootId":self.boot}
        self.sid=None;self.calls=[];self.marker={"bootHeld":False};self.foreign=False
        def read(name):
            if self.sid is None:raise SambaPublicSidMissing("missing")
            return self.sid
        self.bootstrap=LocalSamBootstrap("fixture",self.config,self.root,self.run_command,read,lambda:None,lambda:None,private_root=self.root/"private")
    def run_command(self,args,**kwargs):
        self.calls.append(args)
        if args[1:]==["operation","maintenance","status"]:out=json.dumps(self.marker)
        elif args[1:]==["operation","generation","status"]:out=json.dumps(self.state)
        elif args[:2]==["testparm","-s"]:out="SERVER"
        elif args[:2]==["net","setlocalsid"]:self.sid=args[2];out=""
        else:raise AssertionError(args)
        return subprocess.CompletedProcess(args,0,out,"")
    def test_missing_target_sam_is_created_only_by_explicit_approved_bootstrap_then_preserved(self):
        result=self.bootstrap.initialize(self.request)
        self.assertTrue(result["localSamInitialized"]);self.assertTrue(result["sideEffects"]);self.assertFalse(result["canonicalDesiredStateChanged"]);self.assertEqual(self.sid,result["localMachineSid"])
        self.calls.clear();again=self.bootstrap.initialize(self.request)
        self.assertFalse(again["localSamInitialized"]);self.assertTrue(again["identityPreserved"]);self.assertEqual(result["localMachineSid"],again["localMachineSid"])
        self.assertFalse(any(args[:2]==["net","setlocalsid"] for args in self.calls))
    def test_readonly_missing_probe_and_unapproved_foreign_hold_stale_source_have_no_sid_creation(self):
        for change in ({"initializationApproved":"true"},{"expectedBootId":str(uuid.uuid4())},{"expectedConfigurationSha256":"b"*64},{"expectedGeneration":{**self.current,"revision":1}}):
            with self.assertRaises(ValueError):self.bootstrap.initialize({**self.request,**change})
            self.assertIsNone(self.sid);self.assertFalse(any(args[:2]==["net","setlocalsid"] for args in self.calls))
        self.marker={"bootHeld":True,"maintenanceKind":"ROOT","scope":{}}
        with self.assertRaises(ValueError):self.bootstrap.initialize(self.request)
        self.assertIsNone(self.sid)
    def test_response_lost_bootstrap_replay_proves_planned_sid_and_rejects_foreign_replacement_without_write(self):
        result=self.bootstrap.initialize(self.request);path=self.bootstrap.root/(self.scope["operationUuid"]+".json")
        record=json.loads(path.read_text());record["phase"]="PREPARED";path.write_text(json.dumps(record));path.chmod(0o600)
        self.calls.clear();again=self.bootstrap.initialize(self.request);self.assertEqual(result["localMachineSid"],again["localMachineSid"])
        self.assertFalse(any(args[:2]==["net","setlocalsid"] for args in self.calls))
        self.sid="S-1-5-21-9-9-9";self.calls.clear()
        with self.assertRaises(ValueError):self.bootstrap.initialize(self.request)
        self.assertFalse(any(args[:2]==["net","setlocalsid"] for args in self.calls))
    def test_actual_cli_bootstrap_router_rejects_unapproved_request_before_any_named_state_or_sid_write(self):
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        payload=self.root/"payload.json";payload.write_text(json.dumps({**self.request,"initializationApproved":False}))
        before={str(path):path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        result=subprocess.run(["bash",str(cli),"identity","local-sam","bootstrap",str(payload)],capture_output=True,text=True,timeout=15,
                              env=dict(os.environ,ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root/"writer"),ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.config),
                                       ABLESTACK_STORAGE_LOG_FILE="/dev/null",ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(self.root/"state")))
        self.assertNotEqual(0,result.returncode);self.assertIn("AD_LIFECYCLE_REJECTED",result.stdout)
        self.assertEqual(before,{str(path):path.read_bytes() for path in self.root.rglob("*") if path.is_file() and path.name!="writer"})
    def test_actual_cli_in_private_mount_namespace_explicitly_creates_synthetic_sid_then_preserves_it(self):
        # Normal CLI routing, actual FD9 flock and real libtdb. Only the net
        # mutation binary is a synthetic fixture; no host Samba DB is changed.
        import textwrap
        root=self.root;varlib=root/"varlib";(varlib/"samba/private").mkdir(parents=True,mode=0o700)
        bins=root/"bin";bins.mkdir();testparm=bins/"testparm";testparm.write_text("#!/bin/sh\nprintf 'SERVER\\n'\n");testparm.chmod(0o755)
        net=bins/"net"
        net.write_text(textwrap.dedent("""\
            #!/usr/bin/python3
            import ctypes,os,struct,sys
            class D(ctypes.Structure):_fields_=[("dptr",ctypes.c_void_p),("dsize",ctypes.c_size_t)]
            lib=ctypes.CDLL("libtdb.so.1")
            lib.tdb_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_uint];lib.tdb_open.restype=ctypes.c_void_p
            lib.tdb_store.argtypes=[ctypes.c_void_p,D,D,ctypes.c_int];lib.tdb_store.restype=ctypes.c_int
            lib.tdb_close.argtypes=[ctypes.c_void_p]
            assert sys.argv[1]=="setlocalsid"
            nums=[int(n) for n in sys.argv[2].split("-")[3:]]
            value=bytes([1,4,0,0,0,0,0,5])+struct.pack("<15I",*nums,*([0]*11))
            key=b"SECRETS/SID/SERVER";k=ctypes.create_string_buffer(key);v=ctypes.create_string_buffer(value)
            db=lib.tdb_open(b"/var/lib/samba/private/secrets.tdb",0,0,os.O_RDWR|os.O_CREAT,0o600)
            assert db
            assert lib.tdb_store(db,D(ctypes.cast(k,ctypes.c_void_p),len(key)),D(ctypes.cast(v,ctypes.c_void_p),len(value)),1)==0
            lib.tdb_close(db)
            """));net.chmod(0o755)
        worker=root/"worker.py";cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        worker.write_text(textwrap.dedent("""\
            import json,os,subprocess,sys,uuid
            from pathlib import Path
            root=Path(sys.argv[1]);cli=sys.argv[2];lib=sys.argv[3]
            subprocess.run(["mount","--make-rprivate","/"],check=True)
            subprocess.run(["mount","--bind",str(root/"varlib"),"/var/lib"],check=True)
            sys.path.insert(0,lib)
            from config_generation import Generation
            config=root/"config";generation=root/"generation";generation.mkdir(mode=0o700)
            actual=Generation(generation,config);digest=actual.digest()
            current={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":2,"configurationSha256":digest}
            (generation/"current.json").write_text(json.dumps(current));(generation/"current.json").chmod(0o600)
            boot=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
            request={"instanceUuid":current["instanceUuid"],"operationUuid":str(uuid.uuid4()),"revision":3,"netbiosName":"SERVER","initializationApproved":True,
                     "expectedGeneration":current,"expectedConfigurationSha256":digest,"expectedBootId":boot}
            payload=root/"actual-request.json";payload.write_text(json.dumps(request))
            env=dict(os.environ,PATH=str(root/"bin")+":"+os.environ["PATH"],ABLESTACK_STORAGE_GENERATION_DIR=str(generation),ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(config),
                     ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(root/"state"),ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(root/"writer"),ABLESTACK_STORAGE_LOG_FILE="/dev/null")
            values=[]
            for _ in range(2):
                result=subprocess.run(["bash",cli,"identity","local-sam","bootstrap",str(payload)],env=env,capture_output=True,text=True,timeout=20)
                assert result.returncode==0,result.stdout+result.stderr
                values.append(json.loads(result.stdout))
            assert values[0]["localSamInitialized"] is True and values[0]["sideEffects"] is True and values[1]["identityPreserved"] is True and values[1]["sideEffects"] is False
            assert values[0]["localMachineSid"]==values[1]["localMachineSid"]
            assert values[0]["generation"]==current and values[1]["configurationSha256"]==digest
            assert json.loads((generation/"current.json").read_text())==current
            print(json.dumps({"created":True,"preserved":True,"canonicalUnchanged":True}))
            """))
        result=subprocess.run(["unshare","--mount","--",sys.executable,str(worker),str(root),str(cli),str(LIB)],capture_output=True,text=True,timeout=45)
        self.assertEqual(0,result.returncode,result.stdout+result.stderr);self.assertEqual({"created":True,"preserved":True,"canonicalUnchanged":True},json.loads(result.stdout))

    def test_joined_existing_target_is_preserve_only_and_missing_or_replaced_sid_is_never_created(self):
        self.sid="S-1-5-21-10-11-12";state={"instanceUuid":self.scope["instanceUuid"],"joinState":"JOINED","netbiosName":"SERVER","identityReceipt":{"machineSid":self.sid}}
        path=self.config/"smb-domain.json";path.write_text(json.dumps(state));path.chmod(0o600)
        result=self.bootstrap.initialize(self.request);self.assertTrue(result["identityPreserved"]);self.assertFalse(result["localSamInitialized"])
        for sid in (None,"S-1-5-21-99-99-99"):
            self.sid=sid;self.calls.clear()
            with self.assertRaises(ValueError):self.bootstrap.initialize(self.request)
            self.assertFalse(any(args[:2]==["net","setlocalsid"] for args in self.calls))

    def test_existing_template_sam_seed_is_not_deleted_or_renamed_to_initialize_a_different_target(self):
        import ctypes,struct
        from samba_public_sid import PublicSidTdbData
        root=self.bootstrap.private_root;(root/"private").mkdir(parents=True,mode=0o700)
        path=root/"private/secrets.tdb";lib=ctypes.CDLL("libtdb.so.1")
        lib.tdb_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_uint];lib.tdb_open.restype=ctypes.c_void_p
        lib.tdb_store.argtypes=[ctypes.c_void_p,PublicSidTdbData,PublicSidTdbData,ctypes.c_int];lib.tdb_close.argtypes=[ctypes.c_void_p]
        database=lib.tdb_open(os.fsencode(path),0,0,os.O_RDWR|os.O_CREAT,0o600);self.assertTrue(database)
        key=b"SECRETS/SID/SYSTEMVM";value=bytes([1,4,0,0,0,0,0,5])+struct.pack("<15I",21,1,2,3,*([0]*11))
        k=ctypes.create_string_buffer(key);v=ctypes.create_string_buffer(value)
        try:self.assertEqual(0,lib.tdb_store(database,PublicSidTdbData(ctypes.cast(k,ctypes.c_void_p),len(key)),PublicSidTdbData(ctypes.cast(v,ctypes.c_void_p),len(value)),1))
        finally:lib.tdb_close(database)
        before=path.read_bytes();metadata=path.stat()
        with self.assertRaisesRegex(ValueError,"nonempty foreign"):self.bootstrap.initialize(self.request)
        self.assertEqual(before,path.read_bytes());self.assertEqual(metadata.st_mtime_ns,path.stat().st_mtime_ns)
        self.assertFalse(any(args[:2]==["net","setlocalsid"] for args in self.calls))

    def test_malformed_existing_identity_is_not_treated_as_missing_or_regenerated(self):
        self.bootstrap.sid_reader=lambda name:(_ for _ in ()).throw(ValueError("malformed"))
        with self.assertRaises(ValueError):self.bootstrap.initialize(self.request)
        self.assertIsNone(self.sid);self.assertFalse(any(args[:2]==["net","setlocalsid"] for args in self.calls))


if __name__=="__main__":unittest.main()
