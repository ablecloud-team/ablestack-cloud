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

import copy,json,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"debian/usr/local/lib/ablestack-storage"))
from smb_source_checkpoint import SmbSourceCheckpoint,IDENTITY_DATABASES

class StorageSmbSourceNotStartedTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.scope={"instanceUuid":"11111111-1111-4111-8111-111111111111","operationUuid":"22222222-2222-4222-8222-222222222222","revision":2,"localCheckpointUuid":"33333333-3333-4333-8333-333333333333"}
        self.value={**self.scope,"sourceGeneration":{"revision":1},"sourceConfigurationSha256":"a"*64,"expectedBootId":"44444444-4444-4444-8444-444444444444"}
        self.native=SmbSourceCheckpoint.__new__(SmbSourceCheckpoint)
        self.native.root=Path(self.temp.name);self.native.request=lambda *args:self.scope
        self.native.read=lambda path:json.loads(path.read_text())if path.exists()else None
        self.native.boot=lambda:self.value["expectedBootId"]
        self.actual={"configurationDesiredState":{"desired-state/smb-share-apply.json":None}}
        self.state_calls=[]
        def state(value,**kwargs):
            self.state_calls.append(kwargs);return self.actual
        self.native.state=state
        self.view={"units":[],"ownedEndpoints":[],"sessionsVerifiedEmpty":True,
            "databases":{name:{"path":path,"present":False}for name,path in IDENTITY_DATABASES.items()}}
        self.native.observe=lambda *args,**kwargs:self.view

    def test_only_original_empty_source_with_no_journal_or_cipher_has_not_started_proof(self):
        with patch.object(self.native,"write")as write:
            result=self.native.status(self.value);write.assert_not_called()
        self.assertEqual("NOT_STARTED",result["phase"])
        for key in ("sourceStopNotStarted","checkpointCipherAbsent","sourceRuntimeVerified","currentSessionsVerifiedEmpty"):
            self.assertIs(result[key],True)
        self.assertIs(result["journalPresent"],False)
        self.assertEqual(self.scope,result["scope"])
        self.assertFalse(result["canonicalDesiredStateChanged"])
        self.assertTrue(any(row.get("source_only")is True for row in self.state_calls))

    def test_journal_absence_alone_never_proves_safe_cancellation(self):
        for key,value in (("units",[{"unit":"smbd.service"}]),("ownedEndpoints",[{"port":445}]),("sessionsVerifiedEmpty",False),("databases",{})):
            original=copy.deepcopy(self.view);self.view[key]=value
            self.assertNotIn("sourceStopNotStarted",self.native.status(self.value));self.view=original
        name=next(iter(self.view["databases"]));self.view["databases"][name]["present"]=True
        self.assertNotIn("sourceStopNotStarted",self.native.status(self.value))

    def test_existing_cipher_or_configured_smb_never_uses_empty_source_proof(self):
        cipher=self.native.path(self.scope,"cipher");cipher.write_text("{}")
        self.assertNotIn("sourceStopNotStarted",self.native.status(self.value))
        cipher.unlink();self.actual["configurationDesiredState"]["desired-state/smb-share-apply.json"]={}
        self.assertNotIn("sourceStopNotStarted",self.native.status(self.value))

    def test_changed_source_or_observation_error_remains_failure(self):
        def reject(*args,**kwargs):raise ValueError("changed original source")
        self.native.state=reject
        with self.assertRaises(ValueError):self.native.status(self.value)
        self.native.state=lambda *args,**kwargs:self.actual
        self.native.observe=reject
        with self.assertRaises(ValueError):self.native.status(self.value)

if __name__=="__main__":unittest.main()
