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

"""Exercise PRESTOP public authority, owned stop, stopped export and signed routing."""
from pathlib import Path
import ast
import copy
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from TestStorageRootSourceRecovery import StorageRootSourceRecoveryTest
from root_source_identity_checkpoint import RootSourceIdentityCheckpoint,root_public_sha
from root_configuration_capsule import root_configuration_authorize
from identity_capsule import encrypt,decrypt,validate_payload,validate_ad_identity
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


class StorageRootSourceIdentityTest(StorageRootSourceRecoveryTest):
    # Reuse the immutable generation/canonical-byte fixture without inheriting
    # unrelated historical tests into this test unit.
    def setUp(self):
        super().setUp()
        self.unit="ablestack-storage-winbind.service";self.smbunit="ablestack-storage-smb@"+"a"*24+".service"
        self.active={self.unit:121,self.smbunit:122};self.stops=[];self.fail_stop=None;self.ad_probes=0
        self.proc=self.root/"proc";self.proc.mkdir(mode=0o700)
        for unit,pid in self.active.items():
            process=self.proc/str(pid);process.mkdir();(process/"fd").mkdir()
            exe="/usr/sbin/winbindd" if unit==self.unit else "/usr/sbin/smbd"
            config="/etc/ablestack-storage/ad-machine.conf" if unit==self.unit else "/etc/samba/smb.conf"
            (process/"exe").symlink_to(exe)
            (process/"cmdline").write_bytes((exe+"\0--configfile="+config+"\0").encode())
            (process/"cgroup").write_text("0::/system.slice/"+unit+"\n")
            (process/"comm").write_text("winbindd\n" if unit==self.unit else "smbd\n")
            (process/"stat").write_text(str(pid)+" (native-test) "+" ".join(["S"]+["0"]*18+["201"])+"\n")
        endpoints=self.config/"smb-endpoint-listeners";endpoints.mkdir(mode=0o700)
        (endpoints/("a"*24+".json")).write_text(json.dumps({"listenIp":"192.0.2.20","port":445}))
        machine=self.config/"ad-machine.conf";machine.write_text("[global]\nrealm = EXAMPLE.TEST\n");machine.chmod(0o600)
        self.machine_sha=hashlib.sha256(machine.read_bytes()).hexdigest()
        self.ad={"success":True,"scope":{key:self.request[key] for key in ("instanceUuid","operationUuid","revision")},
                 "identityVerified":True,"trustVerified":True,"adSpnsVerified":True,"dnsAliasesVerified":True,
                 "domain":"example.test","realm":"EXAMPLE.TEST","workgroup":"EXAMPLE","netbiosName":"ASTSOURCE",
                 "machineSid":"S-1-5-21-1-2-3","domainSid":"S-1-5-21-4-5-6","machineAccountSid":"S-1-5-21-4-5-6-1002",
                 "servicePrincipals":["host/astsource.example.test","cifs/astsource.example.test"],
                 "idmapPolicy":{"default":{"backend":"tdb","range":[1000,9999]},"domain":{"backend":"rid","range":[100000,999999],"baseRid":0}},
                 "dnsAliases":[{"hostname":"astsource.example.test","addresses":["192.0.2.20"]}]}
        state={"state":"JOINED","machineConfigurationSha256":self.machine_sha}
        domain=self.config/"smb-domain.json";domain.write_text(json.dumps(state));domain.chmod(0o600)
        native=self.driver.runtime.command
        def command(args,payload=None):
            if args==("identity","domain","inspect"):
                self.ad_probes+=1;self.events.append(("ad-prestop",None));return copy.deepcopy(self.ad)
            return native(args)
        self.driver.runtime.command=command
        def run(args,**kwargs):
            if args[0]=="ss":return SimpleNamespace(returncode=0,stdout="")
            if args[1]=="list-units":
                return SimpleNamespace(returncode=0,stdout="\n".join(unit+" loaded active running" for unit in sorted(self.active)))
            if args[1]=="show":
                pid=self.active.get(args[2],0)
                return SimpleNamespace(returncode=0,stdout=f"MainPID={pid}\nActiveState={'active' if pid else 'inactive'}\n")
            if args[1]=="stop":
                self.stops.append(args[2])
                if args[2]==self.fail_stop:return SimpleNamespace(returncode=1,stdout="")
                self.active.pop(args[2],None);return SimpleNamespace(returncode=0,stdout="")
            raise AssertionError("unexpected global lifecycle effect "+repr(args))
        self.recovery.identity=RootSourceIdentityCheckpoint(self.driver.runtime,self.config,run,self.proc)

    def test_prestop_ad_snapshot_is_public_fresh_and_retry_preserves_the_complete_contract(self):
        capture=self.recovery.capture(self.request);retry=self.recovery.capture(self.request)
        self.assertTrue(capture["publicAdPreStopCaptured"]);self.assertEqual(1,self.ad_probes);self.assertEqual([],self.stops)
        self.assertEqual({**capture,"sideEffects":False},retry)
        saved=json.loads(self.recovery.path(self.marker["scope"]).read_text())
        self.assertEqual(self.machine_sha,saved["sourcePublicIdentity"]["publicAdIdentity"]["machineConfigurationSha256"])
        self.assertEqual(self.ad["machineAccountSid"],saved["sourcePublicIdentity"]["publicAdIdentity"]["machineAccountSid"])
        self.assertNotIn("files",saved["sourcePublicIdentity"]);self.assertEqual(self.actual["configurationDesiredState"],saved["sourcePublicIdentity"]["rootSourceConfiguration"]["configurationDesiredState"])

    def test_raw_source_requires_owned_stopped_receipt_and_reuses_only_public_prestop_ad(self):
        capture=self.recovery.capture(self.request)
        with self.assertRaisesRegex(ValueError,"stopped source receipt"):self.recovery.identity_export_source(self.request)
        stop=self.recovery.quiesce_source(self.request);source=self.recovery.identity_export_source(self.request)
        self.assertEqual([self.smbunit,self.unit],self.stops);self.assertTrue(stop["rootSourceStoppedVerified"])
        self.assertEqual(stop["stoppedReceiptSha256"],source["stoppedReceiptSha256"])
        self.assertEqual(capture["publicAdPreStopSha256"],source["publicAdPreStopSha256"]);self.assertEqual(1,self.ad_probes)
        self.assertEqual(self.marker["scope"],source["scope"]);self.assertFalse(source["canonicalDesiredStateChanged"])
        validate_ad_identity(source["adIdentity"])
        foreign={**self.request,"operationUuid":"00000000-0000-0000-0000-000000000001"}
        with self.assertRaises(ValueError):self.recovery.identity_export_source(foreign)

    def test_unknown_owner_or_changed_exact_pid_config_refuses_before_any_service_stop(self):
        for mode in ("legacy","cgroup","argv"):
            with self.subTest(mode=mode):
                self.recovery.capture(self.request)
                if mode=="legacy":self.active["winbind.service"]=130
                elif mode=="cgroup":(self.proc/"121/cgroup").write_text("0::/system.slice/foreign.service\n")
                else:(self.proc/"121/cmdline").write_bytes(b"winbindd\0--configfile=/foreign.conf\0")
                with self.assertRaises(ValueError):self.recovery.quiesce_source(self.request)
                self.assertEqual([],self.stops)
                if mode=="legacy":self.active.pop("winbind.service")
                elif mode=="cgroup":(self.proc/"121/cgroup").write_text("0::/system.slice/"+self.unit+"\n")
                else:(self.proc/"121/cmdline").write_bytes(b"winbindd\0--configfile=/etc/ablestack-storage/ad-machine.conf\0")

    def test_public_binding_replacement_or_missing_trust_blocks_before_stop(self):
        self.recovery.capture(self.request)
        path=self.config/"ad-machine.conf";previous=path.read_bytes();path.unlink();path.write_bytes(previous);path.chmod(0o600)
        with self.assertRaisesRegex(ValueError,"configuration changed"):self.recovery.quiesce_source(self.request)
        self.assertEqual([],self.stops)
        self.recovery.path(self.marker["scope"]).unlink();self.ad["identityVerified"]=False
        with self.assertRaisesRegex(ValueError,"incomplete or unverified"):self.recovery.capture(self.request)
        self.assertFalse(self.recovery.path(self.marker["scope"]).exists())
        self.ad["identityVerified"]=True
        for change in ({"domainSid":"foreign"},{"machineAccountSid":"S-1-5-21-9-9-9-1002"},{"idmapPolicy":{"default":{"backend":"tdb","range":[1000,9999]},"domain":{"backend":"rid","range":[9990,19999],"baseRid":0}}}):
            with self.subTest(change=tuple(change)):
                previous=copy.deepcopy(self.ad);self.ad.update(change)
                with self.assertRaises(ValueError):self.recovery.capture(self.request)
                self.assertEqual([],self.stops);self.assertFalse(self.recovery.path(self.marker["scope"]).exists())
                self.ad=previous

    def test_partial_owned_stop_stays_held_and_retry_uses_the_original_owner_plan(self):
        self.recovery.capture(self.request);self.fail_stop=self.unit
        with self.assertRaises(ValueError):self.recovery.quiesce_source(self.request)
        saved=json.loads(self.recovery.path(self.marker["scope"]).read_text())
        self.assertEqual("RECOVERY_REQUIRED",saved["phase"]);self.assertTrue(self.marker["bootHeld"])
        self.assertEqual({self.unit,self.smbunit},{row["unit"] for row in saved["sourceStopOwners"]})
        self.assertEqual({self.unit:121},self.active);self.fail_stop=None
        stop=self.recovery.quiesce_source(self.request)
        self.assertTrue(stop["rootSourceStoppedVerified"]);self.assertEqual(set(stop["stoppedUnits"]),{self.unit,self.smbunit})
        self.assertEqual({},self.active)

    def test_poststop_restart_known_holder_or_other_boot_cannot_authorize_any_raw_source(self):
        self.recovery.capture(self.request);self.recovery.quiesce_source(self.request)
        self.active[self.unit]=121
        with self.assertRaisesRegex(ValueError,"restarted"):self.recovery.identity_export_source(self.request)
        self.active.clear()
        with patch.object(self.recovery.identity,"holders",return_value=[{"pid":1}]):
            with self.assertRaisesRegex(ValueError,"holder appeared"):self.recovery.identity_export_source(self.request)
        path=self.recovery.path(self.marker["scope"]);saved=json.loads(path.read_text());saved["sourceStoppedReceipt"]["bootId"]="oldboot"
        from rendered_generation import rendered_json
        rendered_json(path,saved)
        with self.assertRaisesRegex(ValueError,"same-boot"):self.recovery.identity_export_source(self.request)

    def test_exact_signed_export_branch_authenticates_latest_seven_in_real_aead_after_stop(self):
        self.recovery.capture(self.request);self.recovery.quiesce_source(self.request)
        cli=LIB.parent.parent/"bin/ablestack-storagectl"
        body=cli.read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        parsed=ast.parse(body)
        branch=next(node for node in ast.walk(parsed) if isinstance(node,ast.If) and ast.unparse(node.test)=="action == 'export'")
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        private=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        request={**self.marker["scope"],"publicKey":public,"includePosixPolicyReceipts":True}
        collected=[];printed=[]
        def command(args,payload=None):
            self.assertEqual(("operation","generation","render-root-identity-export-source","/dev/stdin"),args)
            return self.recovery.identity_export_source(payload)
        def collect(*args,**kwargs):
            self.assertEqual({},self.active);collected.append(kwargs)
            return {"schemaVersion":1,"files":{},"accounts":{},"nvmeHosts":{},"adIdentity":kwargs["ad_identity"],"posixPolicies":kwargs["posix_policies"]}
        class Reference:
            def retain(inner,*args):return {"sourceConfigurationSha256":args[2]}
        namespace={"request":request,"identity_command":command,"collect":collect,"encrypt":encrypt,"validate_payload":validate_payload,
                   "validate_ad_identity":validate_ad_identity,"RootIdentityReference":Reference,"Path":Path,"json":json,
                   "scope":request["instanceUuid"]+":"+request["operationUuid"],"print":lambda value:printed.append(json.loads(value))}
        exec(compile(ast.Module(body=branch.body,type_ignores=[]),str(cli),"exec"),namespace)
        self.assertEqual(1,len(collected));self.assertEqual(1,self.ad_probes)
        result=printed[0];restored=decrypt(result["capsule"],private,namespace["scope"]);validate_payload(restored)
        self.assertEqual(self.actual["configurationDesiredState"],root_configuration_authorize(restored,self.marker["scope"],self.actual["configurationSha256"]))
        self.assertNotIn(private,str(result));self.assertNotIn("ASTSOURCE",str(result["capsule"]))
        self.assertEqual(self.ad["machineAccountSid"],restored["adIdentity"]["machineAccountSid"])

    def test_actual_signed_status_and_root_fixed_actions_are_readonly_and_correctly_dispatched(self):
        cli=LIB.parent.parent/"bin/ablestack-storagectl";empty=self.root/"empty"
        env={**os.environ,"ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR":str(empty/"maintenance"),
             "ABLESTACK_STORAGE_RENDERED_GENERATIONS":str(empty/"render"),"ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(empty/"writer.lock")}
        status=subprocess.run([str(cli),"operation","generation","render-status"],env=env,capture_output=True,text=True,timeout=5)
        observed=json.loads(status.stdout);self.assertTrue(observed["rootSourceIdentityCheckpointSupported"])
        self.assertFalse(observed["fullFourProtocolActivationSupported"]);self.assertFalse(empty.exists())
        for action in ("render-root-capture-source","render-root-resume-source","render-root-quiesce-source","render-root-identity-export-source"):
            result=subprocess.run([str(cli),"operation","generation",action,"/dev/stdin"],input=json.dumps(self.request),
                                  env=env,capture_output=True,text=True,timeout=5)
            response=json.loads(result.stdout);self.assertEqual("RENDERED_GENERATION_REJECTED",response["errorCode"])
            self.assertNotIn("CONFIG_GENERATION",response["errorCode"])


# Keep this focused unit limited to its own behaviors.
for name in tuple(vars(StorageRootSourceRecoveryTest)):
    if name.startswith("test_") and name not in vars(StorageRootSourceIdentityTest):
        setattr(StorageRootSourceIdentityTest,name,None)
del StorageRootSourceRecoveryTest

if __name__=="__main__":unittest.main()
