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

import ast
import copy
import contextlib
import io
import re
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import service_identity_source as module
from rendered_generation import DESIRED_PATHS,DOMAINS,rendered_json
from template_maintenance import Maintenance
from service_maintenance import ServiceMaintenance
from service_identity_cipher import ServiceIdentityCipher


class StorageServiceIdentitySourceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.config=self.root/"configuration";self.config.mkdir(mode=0o700)
        self.maintenance_root=self.root/"maintenance";self.maintenance_root.mkdir(mode=0o700)
        self.operation=str(uuid.uuid4());self.instance=str(uuid.uuid4())
        self.scope={"instanceUuid":self.instance,"maintenanceUuid":self.operation,"operationUuid":self.operation,"revision":8}
        self.generation={"instanceUuid":self.instance,"operationUuid":str(uuid.uuid4()),"revision":7,"configurationSha256":"a"*64}
        self.desired={name:None for name in DESIRED_PATHS}
        self.actual={"generation":self.generation,"configurationSha256":"a"*64,"generationStatus":"IN_SYNC","pendingOperationUuid":None,"configurationDesiredState":self.desired}
        self.pointer=self.root/"rendered";self.pointer.mkdir(mode=0o700);rendered_json(self.pointer/"desired-state.json",self.desired)
        self.rendered={"scope":{key:self.generation[key] for key in ("instanceUuid","operationUuid","revision")},"configurationSha256":"a"*64,"manifestSha256":"b"*64}
        self.active=[{"unit":"ablestack-storage-smb@"+"1"*24+".service","pid":100,"startTicks":"23","configurationPath":"/etc/samba/smb.conf","listenerEndpoints":[]}]
        self.local_sid="S-1-5-21-1-2-3";self.holders=[];self.listener_fault=False;self.calls=[];self.probe=None;self.writer_calls=0
        self.maintenance=Maintenance(self.maintenance_root,lambda:self.actual)
        self.runtime=type("Runtime",(),{})();self.runtime.cli="fixture";self.runtime.command=self.command
        self.store=type("Store",(),{"pointer":lambda ignored:self.pointer,"status":lambda ignored:{"bootHeld":False,"current":self.rendered,"activation":None}})()
        self.driver=type("Driver",(),{"store":self.store,"runtime":self.runtime,"generation":lambda ignored:self.actual,"verify":lambda ignored,path:{name:True for name in DOMAINS}})()
        self.public=module.ServicePublicIdentity(self.runtime,self.config,run=self.run_command,sid_reader=lambda name:self.local_sid)
        self.public.owners=lambda:copy.deepcopy(self.active);self.public.holders=lambda:copy.deepcopy(self.holders);self.public.listeners_clear=self.listeners_clear
        self.source=module.ServiceIdentitySource(self.driver,self.maintenance_root,self.public,self.writer)
        self.env={"ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(self.config),"ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR":str(self.maintenance_root),"ABLESTACK_STORAGE_VOLUME_OPERATIONS":str(self.root/"volume-journals")}
        self.env_patch=patch.dict(os.environ,self.env);self.env_patch.start();self.addCleanup(self.env_patch.stop)
    def writer(self):self.writer_calls+=1
    def run_command(self,args,**kwargs):
        self.calls.append(args)
        if args==["testparm","-s","--parameter-name=netbios name"]:return subprocess.CompletedProcess(args,0,"SERVER\n","")
        raise AssertionError(args)
    def command(self,args,payload=None):
        if args==("operation","maintenance","status"):return self.maintenance.status()
        if args==("identity","domain","inspect"):return copy.deepcopy(self.probe)
        if args==("operation","generation","render-status"):return {"success":True,"bootHeld":False,"current":self.rendered,"activation":None}
        if args==("operation","generation","render-service-source-quiesce-guard"):return self.source.guard(payload)
        if args==("operation","generation","render-service-source-stopped"):return self.source.stopped(payload)
        raise AssertionError(args)
    def listeners_clear(self,owners):
        if self.listener_fault:raise ValueError("listener remains exposed")
    def journal(self):
        self.maintenance.enter(self.scope,"SERVICE")
        value={"scope":self.scope,"phase":"HELD","owners":copy.deepcopy(self.active),"stoppedUnits":[row["unit"] for row in self.active],
               "sourceGeneration":self.generation,"sourceRendered":self.rendered,"sourceActivation":None}
        self.maintenance.write(self.maintenance_root/"service-maintenance.json",value);self.active.clear();return value
    def joined(self):
        machine="[global]\n workgroup = ABLESTACK\n";path=self.config/"ad-machine.conf";path.write_text(machine);path.chmod(0o600)
        mapping={"default":{"backend":"tdb","range":[10000,60000]},"domain":{"backend":"rid","range":[1000000,1999999],"baseRid":0}}
        self.probe={"scope":{key:self.scope[key] for key in ("instanceUuid","operationUuid","revision")},"domain":"ablestack.local","realm":"ABLESTACK.LOCAL","workgroup":"ABLESTACK","netbiosName":"SERVER",
                    "machineSid":self.local_sid,"domainSid":"S-1-5-21-4-5-6","machineAccountSid":"S-1-5-21-4-5-6-1001","servicePrincipals":["cifs/server.ablestack.local","host/server.ablestack.local"],
                    "trustVerified":True,"identityVerified":True,"adSpnsVerified":True,"dnsAliasesVerified":True,
                    "dnsAliases":[{"hostname":"server.ablestack.local","addresses":["10.10.13.240"]}],"idmapPolicy":mapping,
                    "bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),"generatedEpoch":time.time()}
        state={"state":"JOINED","machineConfigurationSha256":hashlib.sha256(machine.encode()).hexdigest()}
        rendered_json(self.config/"smb-domain.json",state)
        self.active.append({"unit":"ablestack-storage-winbind.service","pid":101,"startTicks":"24","configurationPath":"/etc/ablestack-storage/ad-machine.conf","listenerEndpoints":[]})
    def test_prestop_capture_contains_only_public_sam_ad_and_existing_policy_metadata(self):
        self.joined();paths=[];original=self.public.public_file
        def observe(path,*args,**kwargs):
            paths.append(str(path));self.assertNotIn(".tdb",str(path));self.assertNotIn("keytab",str(path));return original(path,*args,**kwargs)
        self.public.public_file=observe
        result=self.source.capture(self.scope);saved=json.loads(self.source.path(self.scope).read_text())
        self.assertTrue(result["publicAdPreStopCaptured"]);self.assertEqual(self.local_sid,result["publicLocalMachineSid"]);self.assertEqual(Path("/proc/sys/kernel/random/boot_id").read_text().strip(),result["bootId"])
        self.assertEqual("CAPTURED",saved["phase"]);self.assertNotIn("rootSourceConfiguration",saved["sourcePublicIdentity"]);self.assertEqual(0o600,self.source.path(self.scope).stat().st_mode&0o777)
        self.assertTrue(any("ad-machine.conf" in path for path in paths));self.assertFalse(any(args[0]=="systemctl" for args in self.calls))
    def test_mixed_root_uuid_invalid_types_or_foreign_generation_are_rejected_before_record(self):
        for request in ({**self.scope,"templateUpgradeUuid":str(uuid.uuid4())},{**self.scope,"maintenanceUuid":str(uuid.uuid4())},{**self.scope,"revision":True},{**self.scope,"captured":True}):
            with self.assertRaises(ValueError):self.source.capture(request)
        self.actual["generation"]={**self.generation,"instanceUuid":str(uuid.uuid4())}
        with self.assertRaises(ValueError):self.source.capture(self.scope)
        self.assertFalse(self.source.path(self.scope).exists());self.assertEqual([],self.calls)
    def test_foreign_root_or_service_hold_cannot_borrow_public_capture_or_guard(self):
        self.source.capture(self.scope);record=self.source.path(self.scope).read_bytes()
        original=self.runtime.command
        for marker in ({"bootHeld":True,"maintenanceKind":"ROOT","scope":{"instanceUuid":self.instance,"templateUpgradeUuid":str(uuid.uuid4()),"operationUuid":self.operation,"revision":8}},
                       {"bootHeld":True,"maintenanceKind":"SERVICE","scope":{**self.scope,"operationUuid":str(uuid.uuid4())}}):
            self.runtime.command=lambda args,payload=None:marker if args==("operation","maintenance","status") else original(args,payload)
            with self.assertRaises(ValueError):self.source.capture(self.scope)
            with self.assertRaises(ValueError):self.source.guard(self.scope)
            self.assertEqual(record,self.source.path(self.scope).read_bytes())
        self.runtime.command=original

    def test_all_four_source_readback_or_unowned_joined_winbind_blocks_before_record(self):
        self.driver.verify=lambda path:{"NFS":True,"SMB":False,"ISCSI":True,"NVMEOF":True}
        with self.assertRaises(ValueError):self.source.capture(self.scope)
        self.assertFalse(self.source.path(self.scope).exists())
        self.driver.verify=lambda path:{name:True for name in DOMAINS};self.joined();self.active=[self.active[0]]
        with self.assertRaises(ValueError):self.source.capture(self.scope)
        self.assertFalse(self.source.path(self.scope).exists())
    def test_afterstop_export_requires_exact_service_marker_and_durable_owned_stop_journal(self):
        self.source.capture(self.scope)
        with self.assertRaises(ValueError):self.source.export_source(self.scope)
        journal=self.journal();journal["stoppedUnits"]=[];self.maintenance.write(self.maintenance_root/"service-maintenance.json",journal)
        with self.assertRaises(ValueError):self.source.stopped(self.scope)
        self.assertEqual("CAPTURED",json.loads(self.source.path(self.scope).read_text())["phase"])
    def test_same_boot_owned_stop_replay_returns_identical_receipt_without_journal_rewrite(self):
        self.joined();capture=self.source.capture(self.scope);self.journal()
        stopped=self.source.stopped(self.scope);before=self.source.path(self.scope).stat();raw=self.source.path(self.scope).read_bytes()
        result=self.source.export_source(self.scope);again=self.source.export_source(self.scope)
        self.assertTrue(stopped["serviceSourceStoppedVerified"]);self.assertEqual(result,again);self.assertEqual(capture["publicAdPreStopSha256"],result["publicAdPreStopSha256"])
        self.assertEqual(raw,self.source.path(self.scope).read_bytes());self.assertEqual(before,self.source.path(self.scope).stat())
    def test_changed_source_bytes_sid_boot_public_machine_or_owner_prevent_export(self):
        self.source.capture(self.scope);self.journal();self.source.stopped(self.scope)
        self.local_sid="S-1-5-21-9-9-9"
        with self.assertRaises(ValueError):self.source.export_source(self.scope)
        self.local_sid="S-1-5-21-1-2-3"
        rendered_json(self.config/"iscsi-targets.json",{"enabled":False})
        with self.assertRaises(ValueError):self.source.export_source(self.scope)
        (self.config/"iscsi-targets.json").unlink()
        saved=json.loads(self.source.path(self.scope).read_text());saved["bootId"]=str(uuid.uuid4());rendered_json(self.source.path(self.scope),saved)
        with self.assertRaises(ValueError):self.source.export_source(self.scope)
    def test_known_private_tdb_holder_or_listener_blocks_stopped_authority(self):
        self.source.capture(self.scope);self.journal();self.holders=[{"pid":42,"fd":3,"path":"/var/lib/samba/private/secrets.tdb"}]
        with self.assertRaises(ValueError):self.source.stopped(self.scope)
        self.holders=[];self.listener_fault=True
        with self.assertRaises(ValueError):self.source.stopped(self.scope)
        self.assertEqual("CAPTURED",json.loads(self.source.path(self.scope).read_text())["phase"])
    def test_ad_string_boolean_stale_probe_or_wrong_sam_fails_before_capture(self):
        self.joined();original=copy.deepcopy(self.probe)
        for changed in ({"trustVerified":"true"},{"identityVerified":"true"},{"bootId":str(uuid.uuid4())},{"generatedEpoch":time.time()-120},{"machineSid":"S-1-5-21-9-9-9"}):
            self.probe={**original,**changed}
            with self.assertRaises(ValueError):self.source.capture(self.scope)
            self.assertFalse(self.source.path(self.scope).exists())
    def test_service_enter_stops_file_acceptors_before_winbind_and_returns_producer_bound_receipt(self):
        self.joined();self.source.capture(self.scope);calls=[]
        def stop(args,**kwargs):
            calls.append(args)
            if args[:2]==["systemctl","stop"]:self.active=[row for row in self.active if row["unit"]!=args[2]]
            return ""
        service=ServiceMaintenance(self.maintenance,"fixture",stop,self.command,lambda:copy.deepcopy(self.active))
        entered=service.enter(self.scope)
        self.assertEqual(["ablestack-storage-smb@"+"1"*24+".service","ablestack-storage-winbind.service"],[row[2] for row in calls])
        self.assertTrue(entered["serviceSourceStoppedVerified"]);self.assertEqual(self.scope,entered["scope"]);self.assertEqual("SERVICE",entered["maintenanceKind"])
        self.assertEqual(self.source.export_source(self.scope)["stoppedReceiptSha256"],entered["stoppedReceiptSha256"])
        again=service.enter(self.scope);self.assertEqual(entered,again);self.assertEqual(2,len(calls))
    def test_ad_service_stop_without_prestop_capture_is_rejected_before_marker_or_signal(self):
        self.joined();calls=[]
        service=ServiceMaintenance(self.maintenance,"fixture",lambda *args,**kwargs:calls.append(args),self.command,lambda:copy.deepcopy(self.active))
        with self.assertRaises(OSError):service.enter(self.scope)
        self.assertEqual([],calls);self.assertFalse(self.maintenance.marker.exists())
    def test_actual_cli_capsule_export_uses_service_afterstop_authority_and_real_ram_aead(self):
        from identity_capsule import encrypt,decrypt,validate_payload,validate_ad_identity
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        self.source.capture(self.scope);self.journal();self.source.stopped(self.scope)
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        private=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        request={**self.scope,"publicKey":public,"sourceConfigurationSha256":"a"*64,"names":[]}
        cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        block=cli.read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        tree=ast.parse(block);start=block[:block.index('scope = str(request.get("instanceUuid")')].count("\n")+1
        nodes=[node for node in tree.body if node.lineno>=start];calls=[];collections=[]
        def command(args,payload=None):
            calls.append(args)
            self.assertEqual(("operation","generation","render-service-identity-export-source","/dev/stdin"),args)
            return self.source.export_source(payload)
        namespace={"request":request,"action":"export","re":re,"Path":Path,"json":json,"identity_command":command,"validate_ad_identity":validate_ad_identity,
                   "validate_payload":validate_payload,"encrypt":encrypt,
                   "collect":lambda *args,**kwargs:collections.append(kwargs) or {"schemaVersion":1,"files":{},"accounts":{}},"ServiceIdentityCipher":ServiceIdentityCipher}
        output=io.StringIO()
        with contextlib.redirect_stdout(output):exec(compile(ast.Module(body=nodes,type_ignores=[]),str(cli),"exec"),namespace)
        capsule=json.loads(output.getvalue())["capsule"]
        plain=decrypt(capsule,private,self.instance+":"+self.operation);validate_payload(plain)
        self.assertEqual("a"*64,plain["sourceConfigurationSha256"]);self.assertEqual(2,len(calls));self.assertEqual(1,len(collections))
        self.assertNotIn(private,output.getvalue());self.assertIsNone(json.loads(output.getvalue())["rootIdentityReference"])

    def test_actual_cli_capsule_export_before_stop_or_mixed_scope_rejects_before_raw_collection(self):
        from identity_capsule import validate_payload
        self.source.capture(self.scope);cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        block=cli.read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        tree=ast.parse(block);start=block[:block.index('scope = str(request.get("instanceUuid")')].count("\n")+1
        nodes=[node for node in tree.body if node.lineno>=start];collections=[];encryptions=[]
        for request in ({**self.scope,"publicKey":"irrelevant"},{**self.scope,"publicKey":"irrelevant","templateUpgradeUuid":str(uuid.uuid4())}):
            namespace={"request":request,"action":"export","re":re,"Path":Path,"json":json,
                       "identity_command":lambda args,payload=None:self.source.export_source(payload),"validate_payload":validate_payload,
                       "collect":lambda *args,**kwargs:collections.append(kwargs),
                       "encrypt":lambda *args,**kwargs:encryptions.append(args)}
            with self.assertRaises(ValueError):exec(compile(ast.Module(body=nodes,type_ignores=[]),str(cli),"exec"),namespace)
        self.assertEqual([],collections);self.assertEqual([],encryptions)

    def test_source_pending_must_be_same_operation_and_prestop_capture_precedes_native_begin(self):
        self.actual["pendingOperationUuid"]=self.operation;self.actual["generationStatus"]="PENDING"
        with self.assertRaises(ValueError):self.source.capture(self.scope)
        self.actual["pendingOperationUuid"]=None;self.actual["generationStatus"]="IN_SYNC";self.source.capture(self.scope)
        self.actual["pendingOperationUuid"]=self.operation;self.actual["generationStatus"]="PENDING"
        self.assertTrue(self.source.guard(self.scope)["quiesceAuthorized"])
        self.actual["pendingOperationUuid"]=str(uuid.uuid4())
        with self.assertRaises(ValueError):self.source.guard(self.scope)


if __name__=="__main__":unittest.main()
