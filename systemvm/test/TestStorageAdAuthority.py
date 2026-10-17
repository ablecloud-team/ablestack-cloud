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
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
import ad_authority as module
import ad_identity
import native_renderers
CLI=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"


class StorageAdAuthorityTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.config=self.root/"config";self.config.mkdir(mode=0o700)
        self.gen=self.root/"generation";self.gen.mkdir(mode=0o700)
        self.scope={"instanceUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":7}
        self.sids={"machineSid":"S-1-5-21-1-2-3","domainSid":"S-1-5-21-4-5-6","machineAccountSid":"S-1-5-21-4-5-6-1001"}
        self.policy={"default":{"backend":"tdb","range":[10000,60000]},"domain":{"backend":"rid","range":[1000000,1999999],"baseRid":0}}
        machine=ad_identity.ad_configuration("ablestack.local","ABLESTACK","SERVER","[global]\n[existing]\n path = /srv/data\n",["10.10.13.100"],self.policy)["smbConfiguration"]
        self.write(self.config/"ad-machine.conf",machine)
        self.state={"state":"JOINED","joinState":"JOINED","instanceUuid":self.scope["instanceUuid"],"domainName":"ablestack.local","realm":"ABLESTACK.LOCAL","workgroup":"ABLESTACK","netbiosName":"SERVER",
                    "identityReceipt":self.sids,"idmapPolicy":self.policy,"machineConfigurationSha256":hashlib.sha256(machine.encode()).hexdigest(),
                    "servicePrincipals":["cifs/server.ablestack.local","host/server.ablestack.local"],
                    "dnsAliases":[{"hostname":"server.ablestack.local","addresses":["10.10.13.240"]}]}
        self.write(self.config/"smb-domain.json",self.state);self.write(self.gen/"current.json",self.scope)
        receipt={"domain":"ablestack.local","realm":"ABLESTACK.LOCAL","workgroup":"ABLESTACK","netbiosName":"SERVER",**self.sids,"trustVerified":True,"identityVerified":True,
                 "servicePrincipals":self.state["servicePrincipals"],"dnsAliases":self.state["dnsAliases"],"idmapPolicy":self.policy}
        self.identity={"domainName":"ablestack.local","joinState":"JOINED","config":{"workgroup":"ABLESTACK","netbiosName":"SERVER","identityReceipt":receipt}}
        self.env={"ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(self.config),"ABLESTACK_STORAGE_GENERATION_DIR":str(self.gen)}
        self.calls=[];self.modification={}
    def write(self,path,value):
        path.write_text(value if isinstance(value,str) else json.dumps(value,sort_keys=True));path.chmod(0o600)
    def run_mapping(self,args,**kwargs):
        self.calls.append(args);request=json.loads(kwargs["input"])
        self.assertEqual(["identity","principal","resolve","/dev/stdin"],args[1:])
        self.assertEqual("ABLESTACK.LOCAL",request["expectedRealm"]);self.assertEqual(self.sids["domainSid"],request["expectedDomainSid"])
        user=request["principalType"]=="AD_USER"
        value={"success":True,"scope":self.scope,"sideEffects":False,"principalType":request["principalType"],"realm":"ABLESTACK.LOCAL","domainSid":self.sids["domainSid"],
               "workgroup":"ABLESTACK","qualifiedName":module.ad_qualified_name(request["principal"],"ablestack.local","ABLESTACK"),"sid":self.sids["domainSid"]+"-2001","sidType":1 if user else 2,"sidTypeName":"SID_USER" if user else "SID_DOM_GRP",
               "numericId":1002001,"kind":"u" if user else "g","mappingVerified":True,"reverseVerified":True,
               "bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),"generatedEpoch":time.time()}
        value.update(self.modification);return subprocess.CompletedProcess(args,0,json.dumps(value),"")
    def mapping(self,kind="AD_USER"):
        return module.ad_mapped_entry({"principalType":kind,"principal":"Example"},{"instanceUuid":self.scope["instanceUuid"],"revision":2},
                                      self.run_mapping,configuration=self.config,generation=self.gen)
    def test_exact_private_policy_supplies_both_smb_renderers_without_widening_ranges_or_data_effects(self):
        backing="/srv/ablestack-storage/volumes/"+str(uuid.uuid4())+"/shared"
        payload={"instanceUuid":self.scope["instanceUuid"],"netbiosName":"SERVER","identityDomain":self.identity,"shares":[{"uuid":str(uuid.uuid4()),"name":"shared","path":backing,"acls":[{"principalType":"AD_USER","principal":"Example","permission":"READ_WRITE"}]}]}
        before={str(path):path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        with patch.dict(os.environ,self.env):
            files=native_renderers.render_smb_candidate(payload,CLI)
        self.assertIn("idmap config * : range = 10000-60000",files["smb/smb.conf"])
        self.assertIn("idmap config ABLESTACK : range = 1000000-1999999",files["smb/smb.conf"])
        self.assertIn("idmap config ABLESTACK : base_rid = 0",files["smb/smb.conf"])
        self.assertNotIn("10000-999999",files["smb/smb.conf"]);self.assertIn('"ABLESTACK'+chr(92)+'Example"',files["smb/smb.conf"])
        self.assertEqual(before,{str(path):path.read_bytes() for path in self.root.rglob("*") if path.is_file()})
        source=CLI.read_text();physical=next(block for block in re.findall(r"<<\x27PY\x27\n(.*?)\nPY",source,re.S) if "def resolve_account_id(" in block)
        self.assertIn('] + ad_policy["idmapLines"]',physical);self.assertNotIn("10000-999999",physical)
    def test_missing_receipt_foreign_instance_or_desired_sid_reject_before_renderer_files(self):
        for changed in ({},{"identityReceipt":{}},{"identityReceipt":{**self.identity["config"]["identityReceipt"],"domainSid":"S-1-5-21-9-9-9"}}):
            identity=copy.deepcopy(self.identity);identity["config"]=changed
            with self.assertRaises(ValueError):module.protected_ad_policy(self.scope["instanceUuid"],identity,"SERVER",self.config)
        for instance,netbios in ((str(uuid.uuid4()),"SERVER"),(self.scope["instanceUuid"],"OTHER")):
            with self.assertRaises(ValueError):module.protected_ad_policy(instance,self.identity,netbios,self.config)
        self.assertEqual([],self.calls)
    def test_changed_machine_hash_and_duplicate_or_wrong_parameters_reject_without_subprocess(self):
        original=(self.config/"ad-machine.conf").read_text()
        for content in (original+"# source changed\n",original.replace("range = 1000000-1999999","range = 1000001-1999999"),original.replace("[existing]","realm = FOREIGN.LOCAL\n[existing]")):
            self.write(self.config/"ad-machine.conf",content)
            if content!=original+"# source changed\n":
                state={**self.state,"machineConfigurationSha256":hashlib.sha256(content.encode()).hexdigest()};self.write(self.config/"smb-domain.json",state)
            with self.assertRaises(ValueError):module.protected_ad_policy(self.scope["instanceUuid"],self.identity,"SERVER",self.config)
        self.assertEqual([],self.calls)
    def test_unprotected_or_symlink_machine_and_domain_files_are_never_authority(self):
        for name in ("ad-machine.conf","smb-domain.json"):
            path=self.config/name;raw=path.read_bytes();path.chmod(0o666)
            with self.assertRaises(ValueError):module.protected_ad_policy(self.scope["instanceUuid"],self.identity,"SERVER",self.config)
            path.chmod(0o600);target=self.root/(name+".target");target.write_bytes(raw);target.chmod(0o600);path.unlink();path.symlink_to(target)
            with self.assertRaises(ValueError):module.protected_ad_policy(self.scope["instanceUuid"],self.identity,"SERVER",self.config)
            path.unlink();path.write_bytes(raw);path.chmod(0o600)
    def test_user_and_domain_group_mapping_accept_only_the_same_fresh_reverse_verified_sid(self):
        for kind in ("AD_USER","AD_GROUP"):
            value=self.mapping(kind);self.assertEqual(1002001,value["numericId"]);self.assertEqual(self.sids["domainSid"]+"-2001",value["sid"])
        self.modification={"sidType":4,"sidTypeName":"SID_ALIAS"}
        self.assertEqual(4,self.mapping("AD_GROUP")["sidType"])
        self.assertEqual(3,len(self.calls))
    def test_upn_and_domain_qualified_smb_principals_normalize_without_foreign_domain_fallback(self):
        for name in ("Example","Example@ablestack.local","ABLESTACK"+chr(92)+"Example"):
            self.assertEqual("ABLESTACK"+chr(92)+"Example",module.ad_qualified_name(name,"ablestack.local","ABLESTACK"))
        for name in ("Example@foreign.local","FOREIGN"+chr(92)+"Example","Example\nroot"):
            with self.assertRaises(ValueError):module.ad_qualified_name(name,"ablestack.local","ABLESTACK")
        backing="/srv/ablestack-storage/volumes/"+str(uuid.uuid4())+"/shared"
        payload={"instanceUuid":self.scope["instanceUuid"],"netbiosName":"SERVER","identityDomain":self.identity,"shares":[{"uuid":str(uuid.uuid4()),"name":"shared","path":backing,"acls":[{"principalType":"AD_USER","principal":"Example@ablestack.local","permission":"READ_WRITE"}]}]}
        with patch.dict(os.environ,self.env):
            files=native_renderers.render_smb_candidate(payload,CLI)
            self.assertIn('valid users = "ABLESTACK'+chr(92)+'Example"',files["smb/smb.conf"])
            payload["shares"][0]["acls"][0]["principal"]="Example@foreign.local"
            with self.assertRaises(ValueError):native_renderers.render_smb_candidate(payload,CLI)

    def test_physical_posix_ad_rejection_precedes_chown_chmod_and_every_acl_command(self):
        block=CLI.read_text().split("<<'PYPOSIX'\n",1)[1].split("\nPYPOSIX",1)[0]
        nodes=[node for node in ast.parse(block).body if isinstance(node,ast.FunctionDef) and node.name in ("resolved_entry","apply_policy","bits")]
        calls=[];entry={"principalType":"AD_USER","principal":"Example","permission":"READ_WRITE"}
        request={"instanceUuid":self.scope["instanceUuid"],"config":{"accessEntries":[entry],"applyOwner":True,"ownerUid":1002001,"ownerGid":1002001}}
        namespace={"request":request,"re":re,"pwd":None,"grp":None,"os":os,"directory_fd":123,"verify_expected_directory_identity":lambda *args:None,
                   "observe":lambda *args:{"effectiveUid":0,"effectiveGid":0,"effectiveMode":"0770"},
                   "ad_mapped_entry":lambda item,req,cli=None:self.mapping(),"sys":type("Args",(),{"argv":["ctl","apply","payload",str(CLI)]}),
                   "run":lambda *args,**kwargs:calls.append(args)}
        exec(compile(ast.Module(body=nodes,type_ignores=[]),str(CLI),"exec"),namespace)
        self.modification={"reverseVerified":False}
        with patch.object(os,"chown") as chown,patch.object(os,"chmod") as chmod:
            with self.assertRaises(ValueError):namespace["apply_policy"](request,{"effectiveUid":0,"effectiveGid":0},{})
            chown.assert_not_called();chmod.assert_not_called();self.assertEqual([],calls)
        self.modification={}
        key,numeric,bits=namespace["resolved_entry"](entry)
        self.assertEqual(("u",1002001,"rwx"),(key,numeric,bits))

    def test_foreign_sid_wrong_reverse_numeric_reserved_boot_or_string_boolean_is_rejected(self):
        changes=({"sid":"S-1-5-21-9-9-9-2001"},{"realm":"FOREIGN.LOCAL"},{"reverseVerified":False},{"reverseVerified":"true"},
                 {"mappingVerified":"true"},{"success":"true"},{"sidType":True},{"qualifiedName":"FOREIGN"+chr(92)+"Example"},{"workgroup":"FOREIGN"},{"numericId":65534},{"numericId":True},{"numericId":10001},
                 {"sidType":2,"sidTypeName":"SID_DOM_GRP"},{"bootId":str(uuid.uuid4())},{"generatedEpoch":time.time()-120},{"generatedEpoch":"now"},
                 {"scope":{**self.scope,"operationUuid":str(uuid.uuid4())}})
        for changed in changes:
            with self.subTest(changed=tuple(changed)):
                self.modification=changed
                with self.assertRaises(ValueError):self.mapping()
    def test_foreign_pending_and_unowned_pending_cannot_lend_a_mapping_scope(self):
        self.write(self.gen/"pending.json",{**self.scope,"instanceUuid":str(uuid.uuid4())})
        with self.assertRaises(ValueError):self.mapping()
        self.write(self.gen/"pending.json",self.scope)
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":""}):
            with self.assertRaises(ValueError):self.mapping()
        self.assertEqual([],self.calls)
    def test_actual_signed_smb_adapter_rejects_stale_private_ad_policy_before_effects(self):
        payload=self.root/"payload.json";payload.write_text(json.dumps({"instanceUuid":self.scope["instanceUuid"],"netbiosName":"SERVER","identityDomain":self.identity,"shares":[]}))
        self.write(self.config/"ad-machine.conf","tampered")
        before={str(path):path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        result=subprocess.run(["bash",str(CLI),"smb","share","preflight",str(payload)],capture_output=True,text=True,timeout=20,
                              env=dict(os.environ,**self.env,ABLESTACK_STORAGE_LOG_FILE="/dev/null",ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root/"lock")))
        self.assertNotEqual(0,result.returncode)
        after={str(path):path.read_bytes() for path in self.root.rglob("*") if path.is_file() and path.name!="lock"}
        self.assertEqual(before,after)
    def test_signed_posix_and_smb_helpers_match_reviewed_authority_body_exactly(self):
        expected=(LIB/"ad_authority.py").read_text().split('"""Protected joined AD policy',1)[1]
        expected='"""Protected joined AD policy'+expected
        source=CLI.read_text()
        blocks=re.findall("# BEGIN EMBEDDED AD AUTHORITY\n(.*?)\n# END EMBEDDED AD AUTHORITY",source,re.S)
        self.assertEqual(2,len(blocks))
        for block in blocks:self.assertEqual(expected,block)
        smb=next(block for block in re.findall(r"<<'PY'\n(.*?)\nPY",source,re.S) if "def resolve_account_id(" in block)
        function=next(node for node in ast.parse(smb).body if isinstance(node,ast.FunctionDef) and node.name=="resolve_account_id")
        self.assertNotIn("wbinfo",ast.unparse(function));self.assertNotIn("identity_candidates",ast.unparse(function))
        self.assertIn("ad_mapped_entry",ast.unparse(function))
    def test_local_user_and_group_collision_authority_is_local_file_not_nss(self):
        accounts=self.root/"accounts";accounts.mkdir(mode=0o700)
        self.write(accounts/"passwd","localmanaged:x:1002001:1002001::/nonexistent:/usr/sbin/nologin\n")
        self.write(accounts/"group","localmanaged:x:1002001:\n")
        for kind in ("AD_USER","AD_GROUP"):
            with self.assertRaises(ValueError):ad_identity.reject_local_ad_collision(kind,1002001,accounts)
            ad_identity.reject_local_ad_collision(kind,1002002,accounts)
    def test_pending_readonly_scope_requires_the_actual_owned_exclusive_writer_descriptor(self):
        self.write(self.gen/"pending.json",self.scope);path=self.root/"writer";descriptor=os.open(path,os.O_RDWR|os.O_CREAT,0o600)
        saved=None
        try:
            try:saved=os.dup(9)
            except OSError:pass
            os.dup2(descriptor,9);fcntl.flock(9,fcntl.LOCK_EX)
            with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9","ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(path)}):
                self.assertEqual(1002001,self.mapping()["numericId"])
                fcntl.flock(9,fcntl.LOCK_UN)
                with self.assertRaises(ValueError):self.mapping()
        finally:
            if saved is not None:os.dup2(saved,9);os.close(saved)
            else:os.close(9)
            if descriptor!=9:os.close(descriptor)


if __name__=="__main__":unittest.main()
