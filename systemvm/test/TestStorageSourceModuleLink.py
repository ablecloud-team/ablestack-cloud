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

import copy,hashlib,json,sys,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock,patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"debian/usr/local/lib/ablestack-storage"))
import smb_source_module_link as module

class ModuleLinkCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);root=Path(self.temp.name)
        block=b"# BEGIN EMBEDDED SIGNED LOCAL RECOVERY MODULES\npass\n# END EMBEDDED SIGNED LOCAL RECOVERY MODULES"
        self.digest=hashlib.sha256(block).hexdigest()
        old=b"            if not self.unconfigured_stop_bridge(value,journal):self.code_compatibility(value,journal)"
        changed=b"            if not source_module_link_compatibility(self,value,journal,LOCAL_SOURCE_MODULE_LINK_SHA256,regular_file,SmbIdentity):\n    "+old
        original=b"identity\n"+old+b"\nrendered\n"+old+b"\n"
        declaration=('LOCAL_SOURCE_MODULE_LINK_SHA256="'+self.digest+'"\n').encode()
        current=b"identity\n"+block+b"\n"+declaration+changed+b"\nrendered\n"+block+b"\n"+declaration+changed+b"\n"
        runtime=root/"runtime";releases=runtime/"releases";releases.mkdir(parents=True)
        installs=root/"installs";installs.mkdir()
        for version,raw in [("old",original),("new",current)]:
            directory=releases/version;directory.mkdir();(directory/"ablestack-storagectl").write_bytes(raw)
            (directory/"manifest.json").write_text(json.dumps({"bundleVersion":version}))
            transaction=installs/("test-"+version);transaction.mkdir();(transaction/"manifest.sig").write_bytes(b"synthetic-test-signature")
        (runtime/"current").symlink_to(releases/"new",target_is_directory=True)
        self.old_sha=hashlib.sha256(original).hexdigest()
        p=patch.object(module,"MODULE_LINK_ORIGINAL_CLI_SHA256",self.old_sha);p.start();self.addCleanup(p.stop)
        self.value={"instanceUuid":"11111111-1111-4111-8111-111111111111","operationUuid":"22222222-2222-4222-8222-222222222222","localCheckpointUuid":"33333333-3333-4333-8333-333333333333","revision":2,"sourceGeneration":{"instanceUuid":"11111111-1111-4111-8111-111111111111","operationUuid":"44444444-4444-4444-8444-444444444444","revision":1,"configurationSha256":"a"*64},"sourceConfigurationSha256":"a"*64,"expectedBootId":"55555555-5555-4555-8555-555555555555"}
        scope={k:self.value[k]for k in ["instanceUuid","operationUuid","revision","localCheckpointUuid"]}
        self.record={"scope":scope,"common":copy.deepcopy(self.value),"sourceDesiredState":{},"publicKey":"synthetic-public-key"}
        self.journal={**copy.deepcopy(self.record),"cliSha256":self.old_sha,"phase":"RESUMED","stopped":{"databases":"original-empty"}}
        generation=root/"generations";generation.mkdir()
        local=root/"local";local.mkdir()
        self.rows={local/"journal.json":self.journal,local/"cipher.json":self.record,
            generation/"current.json":self.value["sourceGeneration"],generation/"pending.json":{**{k:scope[k]for k in ["instanceUuid","operationUuid","revision"]},"phase":"PREPARED","previous":self.value["sourceGeneration"],"beforeSha256":"a"*64}}
        self.observed={"generation":self.value["sourceGeneration"],"configurationSha256":"a"*64,"configurationDesiredState":{},"pendingOperationUuid":scope["operationUuid"],"bootId":self.value["expectedBootId"]}
        self.owner=SimpleNamespace(cli=runtime/"current/ablestack-storagectl",runtime_root=runtime,installation_root=installs,handler=SimpleNamespace(generations=generation),
            reader=lambda name,*args:(Path(name).read_bytes(),Path(name).stat()),
            installed_release_authority=lambda directory,manifest:{"transactionId":"test-"+manifest["bundleVersion"]},
            read=lambda path:self.rows.get(path),path=lambda scope,kind:local/(kind+".json"),
            common=lambda value:{k:value[k]for k in self.value},boot=lambda:self.value["expectedBootId"],
            validate_record=Mock(),reference=lambda record:{"localCheckpointUuid":scope["localCheckpointUuid"],"sha256":"c"*64},
            command=lambda *args:self.observed,stopped_observation=lambda:{"databases":"original-empty"},match_private=Mock(),request=Mock(),decode=Mock())
        self.owner.compat_entry=Mock(side_effect=lambda path,digest:Path(path).read_bytes())
        self.writes=[]
        def write(scope,kind,value,immutable=False):
            self.assertTrue(immutable);self.writes.append(kind);self.rows[self.owner.path(scope,kind)]=copy.deepcopy(value)
        self.owner.write=write
        source={"scope":{k:self.value["sourceGeneration"][k]for k in ["instanceUuid","operationUuid","revision"]},"configurationSha256":"a"*64,"manifestSha256":"b"*64}
        activation={"phase":"COMPLETE"}
        self.checkpoint={"sourceGeneration":self.value["sourceGeneration"],"sourceRendered":source,"sourceActivation":activation}
        self.held={"bootHeld":True,"maintenanceKind":"SERVICE","phase":"HELD"}
        self.rendered={"current":source,"activation":activation,"bootHeld":False}
        keeper=SimpleNamespace(read=lambda path:self.checkpoint)
        service=SimpleNamespace(journal=root/"service.json",status=lambda *args:self.held)
        self.store=SimpleNamespace(status=lambda:self.rendered,generations=root/"rendered-generations")
        for name,replacement in [("template_maintenance.Maintenance",lambda:keeper),("service_maintenance.ServiceMaintenance",lambda *args,**kwargs:service),("rendered_generation.RenderedGeneration",lambda:self.store)]:
            p=patch(name,replacement);p.start();self.addCleanup(p.stop)

    def call(self,value=None):
        return module.source_module_link_compatibility(self.owner,value or self.value,self.journal,self.digest)

    def test_status_accepts_exact_signed_link_delta_without_writes(self):
        before=copy.deepcopy(self.rows)
        self.assertTrue(self.call());self.assertEqual([],self.writes);self.assertEqual(before,self.rows)
        self.assertGreaterEqual(self.owner.compat_entry.call_count,2)
        self.owner.request.assert_not_called();self.owner.decode.assert_not_called()

    def test_only_authenticated_original_import_publishes_immutable_receipt(self):
        value={**self.value,"restoreDomains":["SMB"],"capsule":"synthetic-cipher"}
        self.assertTrue(self.call(value));self.assertEqual(["module-link-code-compatibility"],self.writes)
        self.owner.request.assert_called_once_with(value,"import-local-source")
        self.owner.decode.assert_called_once_with(value,self.record)
        self.assertTrue(self.call());self.assertEqual(1,len(self.writes))

    def readonly_renderer(self):
        reader=copy.copy(self.owner);del reader.handler
        reader.native_command=self.owner.command
        return reader

    def test_renderer_accepts_only_the_original_import_authenticated_receipt(self):
        self.assertTrue(self.call({**self.value,"restoreDomains":["SMB"]}))
        before=copy.deepcopy(self.rows);reader=self.readonly_renderer()
        self.assertTrue(module.source_module_link_compatibility(reader,self.value,self.journal,self.digest,
            self.owner.reader,lambda cli:self.owner.handler))
        self.assertFalse(hasattr(reader,"handler"));self.assertEqual(before,self.rows)
        self.assertEqual(["module-link-code-compatibility"],self.writes)

    def test_renderer_never_approves_compatibility_or_constructs_the_producer(self):
        reader=self.readonly_renderer()
        with self.assertRaisesRegex(ValueError,"original authenticated"):
            module.source_module_link_compatibility(reader,self.value,self.journal,self.digest,
                self.owner.reader,lambda cli:self.owner.handler)
        self.assertEqual([],self.writes);self.assertFalse(hasattr(reader,"handler"))
        self.owner.request.assert_not_called();self.owner.decode.assert_not_called()

    def test_failed_authentication_never_publishes_receipt(self):
        self.owner.decode.side_effect=ValueError("synthetic-rejected-authentication")
        with self.assertRaises(ValueError):self.call({**self.value,"restoreDomains":["SMB"]})
        self.assertEqual([],self.writes)

    def test_foreign_boot_pending_scope_or_source_never_mints_authority(self):
        self.owner.boot=lambda:"foreign"
        with self.assertRaises(ValueError):self.call()
        self.owner.boot=lambda:self.value["expectedBootId"]
        path=self.owner.handler.generations/"pending.json"
        self.rows[path]["operationUuid"]="foreign"
        with self.assertRaises(ValueError):self.call()
        self.rows[path]["operationUuid"]=self.value["operationUuid"];self.observed["configurationSha256"]="d"*64
        with self.assertRaises(ValueError):self.call()
        self.assertEqual([],self.writes)

    def test_changed_cipher_journal_private_source_or_hold_is_rejected(self):
        self.record["common"]={};self.owner.validate_record.side_effect=ValueError("record-changed")
        with self.assertRaises(ValueError):self.call()
        self.record["common"]=copy.deepcopy(self.value);self.owner.validate_record.side_effect=None
        self.held["bootHeld"]=False
        with self.assertRaises(ValueError):self.call()
        self.held["bootHeld"]=True;self.owner.match_private.side_effect=ValueError("private-source-changed")
        with self.assertRaises(ValueError):self.call()
        self.assertEqual([],self.writes)

    def test_target_activation_or_imported_journal_cannot_use_initial_approval(self):
        self.rendered["bootHeld"]=True
        with self.assertRaises(ValueError):self.call()
        self.rendered["bootHeld"]=False;self.journal["imported"]=True
        with self.assertRaises(ValueError):self.call()
        self.assertEqual([],self.writes)

    def test_other_original_code_or_missing_signature_is_not_eligible(self):
        self.journal["cliSha256"]="f"*64;self.assertFalse(self.call())
        self.journal["cliSha256"]=self.old_sha
        (self.owner.installation_root/"test-old/manifest.sig").unlink()
        with self.assertRaises(ValueError):self.call()
        self.assertEqual([],self.writes)

    def test_changed_cached_receipt_cannot_borrow_an_earlier_approval(self):
        self.assertTrue(self.call({**self.value,"restoreDomains":["SMB"]}))
        path=next(path for path in self.rows if path.name=="module-link-code-compatibility.json")
        self.rows[path]["currentCliSha256"]="f"*64
        with self.assertRaises(ValueError):self.call()

if __name__=="__main__":unittest.main()
