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
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2];LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage";sys.path.insert(0,str(LIB))
from semantic_identity_alias import semantic_forced_alias,semantic_managed_aliases
from native_renderers import render_smb_candidate


class StorageSemanticIdentityAliasTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);self.root.chmod(0o700)
        self.instance=str(uuid.uuid4());self.source=str(uuid.uuid4());self.target=str(uuid.uuid4());self.op=str(uuid.uuid4())
        self.scope={"instanceUuid":self.instance,"maintenanceUuid":self.op,"operationUuid":self.op,"revision":3}
        token=self.source.replace("-","")[:20]
        self.row={"sourceShareUuid":self.source,"targetShareUuid":self.target,"identityNamespaceShareUuid":self.source,
                  "managedUser":"sf_u_"+token,"managedGroup":"sf_g_"+token,"ownerUid":12000,"ownerGid":12001,"sourceIdentityVerified":True}
        self.record={"schemaVersion":1,"instanceUuid":self.instance,"scope":self.scope,"bootId":str(uuid.uuid4()),"sourceCheckpointRecordSha256":"a"*64,
                     "originalSourceAuthority":{"kind":"STORAGE_AD_SEMANTIC_SOURCE"},"mappings":[self.row]}
        self.state={"instanceUuid":self.instance,"semanticManagedIdentityAliasBinding":{key:self.record[key] for key in ("scope","bootId","sourceCheckpointRecordSha256","originalSourceAuthority")}}
        self.write()
        self.cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        self.config={"posixOwnershipMode":"FORCED_UID_GID","ownerUid":12000,"ownerGid":12001,"posixPolicyUuid":str(uuid.uuid4())}
        self.payload={"instanceUuid":self.instance,"shares":[{"uuid":self.target,"name":"restored","path":"/srv/ablestack-storage/volumes/"+str(uuid.uuid4())+"/share","config":self.config,"acls":[]}]}
    def write(self):
        alias=self.root/"smb-semantic-identity-aliases.json";alias.write_text(json.dumps(self.record,sort_keys=True));alias.chmod(0o600)
        self.state["semanticManagedIdentityAliasSha256"]=hashlib.sha256(alias.read_bytes()).hexdigest()
        state=self.root/"smb-domain.json";state.write_text(json.dumps(self.state,sort_keys=True));state.chmod(0o600)
    def physical(self):
        source=self.cli.read_text();block=next(value for value in __import__("re").findall(r"<<'PY'\n(.*?)\nPY",source,__import__("re").S) if "def smb_forced_identity(" in value)
        node=next(n for n in ast.parse(block).body if isinstance(n,ast.FunctionDef) and n.name=="smb_forced_identity")
        calls=[]
        namespace={"re":__import__("re"),"semantic_forced_alias":semantic_forced_alias,"payload":self.payload,"state_dir":str(self.root),
                   "truth":lambda c,k,d=False:c.get(k,d),"run":lambda args:calls.append(args),
                   "pwd":SimpleNamespace(getpwnam=lambda name:SimpleNamespace(pw_uid=12000,pw_gid=12001,pw_shell="/usr/sbin/nologin",pw_dir="/nonexistent")),
                   "grp":SimpleNamespace(getgrnam=lambda name:SimpleNamespace(gr_gid=12001)),
                   "managed_identities":{},"active_managed_identities":{},"created_identity_users":[],"created_identity_groups":[]}
        exec(compile(ast.Module(body=[node],type_ignores=[]),"signed-physical-helper","exec"),namespace)
        result=namespace["smb_forced_identity"](self.config,self.target,[],False)
        return result,calls
    def test_pure_and_actual_physical_helper_use_identical_original_names_without_local_account_recreation(self):
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(self.root)}):
            files=render_smb_candidate(self.payload,self.cli)
        physical,calls=self.physical()
        self.assertIn("force user = "+self.row["managedUser"],files["smb/smb.conf"]);self.assertIn("force group = "+self.row["managedGroup"],files["smb/smb.conf"])
        self.assertEqual(self.row["managedUser"],physical["managedUser"]);self.assertEqual(self.source,physical["sourceIdentityShareUuid"]);self.assertEqual([],calls)
    def test_changed_target_instance_scope_boot_cipher_hash_numeric_or_row_reject_before_physical_effects(self):
        old=copy.deepcopy(self.record);state=copy.deepcopy(self.state)
        for change in ({"instanceUuid":str(uuid.uuid4())},{"scope":{**self.scope,"revision":"3"}},{"bootId":"wrong"},{"sourceCheckpointRecordSha256":"bad"},
                       {"mappings":[{**self.row,"ownerUid":True}]},{"mappings":[{**self.row,"managedGroup":"foreign"}]}):
            self.record={**old,**change};self.write()
            with self.subTest(fields=tuple(change)),self.assertRaises(ValueError):self.physical()
            with patch.dict(os.environ,{"ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(self.root)}),self.assertRaises(ValueError):render_smb_candidate(self.payload,self.cli)
        self.record=old;self.state=state;self.write()
        with self.assertRaises(ValueError):semantic_forced_alias(self.instance,self.target,12002,12001,self.root)
    def test_chain_clone_preserves_original_namespace_and_union_mapping_is_reviewed(self):
        original_user={"posixOwnershipMode":"FORCED_UID_GID",**{key:self.row[key] for key in ("managedUser","managedGroup","ownerUid","ownerGid")},"sourceIdentityShareUuid":self.source}
        original={"payload":{"files":{"/etc/ablestack-storage/smb-managed-identities.json":{"data":base64.b64encode(json.dumps({self.target:original_user}).encode()).decode()}}}}
        next_target=str(uuid.uuid4());rows=semantic_managed_aliases(original,[{"sourceShareUuid":self.target,"targetShareUuid":next_target}])
        self.assertEqual(self.source,rows[0]["identityNamespaceShareUuid"]);self.assertEqual(self.row["managedUser"],rows[0]["managedUser"])
        with self.assertRaises(ValueError):semantic_managed_aliases(original,[])
        with self.assertRaises(ValueError):semantic_managed_aliases(original,[{"sourceShareUuid":self.target,"targetShareUuid":next_target}]*2)

if __name__=="__main__":unittest.main()
