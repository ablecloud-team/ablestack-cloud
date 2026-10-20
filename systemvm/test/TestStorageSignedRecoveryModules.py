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

import ast,hashlib,json,subprocess,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
CLI=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from smb_source_module_link import normalize_module_link_cli

class SignedRecoveryModulesTest(unittest.TestCase):
    def test_isolated_actual_cli_module_imports_and_same_writer_pre_and_post_activation(self):
        # -I prevents a checkout PYTHONPATH from hiding missing deployment modules.
        program=r'''
import sys,ast,os,json,tempfile,uuid,hashlib
from pathlib import Path
from types import SimpleNamespace
raw=Path(sys.argv[1]).read_text()
begin="# BEGIN"+" EMBEDDED SIGNED LOCAL RECOVERY MODULES"
end="# END"+" EMBEDDED SIGNED LOCAL RECOVERY MODULES"
blocks=[];pos=0
while begin in raw[pos:]:
 a=raw.index(begin,pos);b=raw.index(end,a)+len(end);blocks.append(raw[a:b]);pos=b
assert len(blocks)==2 and blocks[0]==blocks[1]
for block in blocks:
 for name in ("template_maintenance","service_maintenance","rendered_generation"):sys.modules.pop(name,None)
 exec(compile(block,"<signed-entry-module-block>","exec"),{})
 for name in ("template_maintenance","service_maintenance","rendered_generation"):
  assert sys.modules[name].__file__=="ablestack-storagectl:"+name
from template_maintenance import Maintenance
from service_maintenance import ServiceMaintenance
from rendered_generation import RenderedGeneration,REQUIRED,DOMAINS,DESIRED_PATHS,rendered_json
body=raw.split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
klass=next(n for n in ast.walk(ast.parse(body))if isinstance(n,ast.ClassDef)and n.name=="SmbSourceCheckpoint")
method=next(n for n in klass.body if isinstance(n,ast.FunctionDef)and n.name=="same_service_writer")
scopekeys={"instanceUuid","operationUuid","revision","localCheckpointUuid"}
commonkeys=scopekeys|{"sourceGeneration","sourceConfigurationSha256","expectedBootId"}
ns={"Path":Path,"os":os,"re":__import__("re"),"LOCAL_SOURCE_SCOPE":scopekeys,"LOCAL_SOURCE_COMMON":commonkeys}
exec(compile(ast.Module(body=[method],type_ignores=[]),"<actual-entry-same-service-writer>","exec"),ns)
with tempfile.TemporaryDirectory()as temporary:
 root=Path(temporary)
 os.environ["ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR"]=str(root/"maintenance")
 os.environ["ABLESTACK_STORAGE_RENDERED_GENERATIONS"]=str(root/"rendered")
 store=RenderedGeneration()
 instance=str(uuid.uuid4());sourceop=str(uuid.uuid4());op=str(uuid.uuid4())
 source_scope={"instanceUuid":instance,"operationUuid":sourceop,"revision":1}
 scope={"instanceUuid":instance,"operationUuid":op,"revision":2}
 canonical={name:None for name in DESIRED_PATHS}
 source_sha=hashlib.sha256(json.dumps(canonical,sort_keys=True,separators=(",",":")).encode()).hexdigest()
 source_generation={**source_scope,"configurationSha256":source_sha}
 files={name:"{}"for name in REQUIRED};files["desired-state.json"]=json.dumps(canonical)
 request={**source_scope,"configurationSha256":source_sha}
 source=store.stage(request,files,lambda *args:{d:True for d in DOMAINS})
 store.publish_pointer(store.generations/sourceop)
 rendered_json(store.journal,{"scope":source_scope,"phase":"COMPLETE","targetSha256":source["manifestSha256"],"previousSha256":None})
 keeper=Maintenance()
 held_scope={**scope,"maintenanceUuid":op}
 keeper.write(keeper.marker,{"kind":"SERVICE","scope":held_scope,"bootHeld":True})
 service=ServiceMaintenance(keeper,CLI if "CLI"in globals()else sys.argv[1],command=lambda *a:None)
 checkpoint={"scope":held_scope,"phase":"HELD","sourceGeneration":source_generation,"sourceRendered":source,"sourceActivation":store.status()["activation"]}
 keeper.write(service.journal,checkpoint)
 value={**scope,"localCheckpointUuid":str(uuid.uuid4()),"sourceGeneration":source_generation,"sourceConfigurationSha256":source_sha,"expectedBootId":str(uuid.uuid4())}
 local_scope={k:value[k]for k in scopekeys};cipher={"scope":local_scope,"common":value,"sourceDesiredState":{}}
 journal={"scope":local_scope,"common":value,"sourceDesiredState":{}}
 localroot=root/"local";localroot.mkdir()
 for kind,data in [("cipher",cipher),("journal",journal)]: (localroot/(kind+".json")).write_text(json.dumps(data))
 owner=SimpleNamespace(cli=Path(sys.argv[1]),handler=SimpleNamespace(generations=root/"generations"),
  command=lambda *args:None,common=lambda v:{k:v[k]for k in commonkeys},
  path=lambda scope,kind:localroot/(kind+".json"),read=lambda p:json.loads(p.read_text()),
  validate_record=lambda *args:None,reference=lambda record:{"checkpoint":"fixed-original"},
  request=lambda *args:scope)
 fn=ns["same_service_writer"]
 assert fn(owner,value,keeper.status(),store.status(),observation=True)is True
 forged=keeper.status();forged["scope"]={**held_scope,"revision":3}
 assert fn(owner,value,forged,store.status(),observation=True)is False
 target_canonical={**canonical,"network-endpoints.json":{"schemaVersion":1,"endpoints":[]}}
 target_sha=hashlib.sha256(json.dumps(target_canonical,sort_keys=True,separators=(",",":")).encode()).hexdigest()
 targetfiles={**files,"smb/smb.conf":"[global]\n#target","desired-state.json":json.dumps(target_canonical)}
 target=store.stage({**scope,"expectedCurrentRenderedSha256":source["manifestSha256"],"configurationSha256":target_sha},
  targetfiles,lambda *args:{d:True for d in DOMAINS})
 store.activate(scope,lambda *args:None,lambda *args:{d:True for d in DOMAINS})
 imported={**value,"restoreDomains":["SMB"],"renderedManifestSha256":target["manifestSha256"],"localSourceCheckpointReference":owner.reference(cipher)}
 try:fn(owner,imported,keeper.status(),store.status())
 except ValueError:pass
 else:raise AssertionError("Forward target must not authorize import")
 verified=[]
 def inverse(*args):
  verified.append(fn(owner,imported,keeper.status(),store.status()))
 store.rollback(scope,inverse,lambda *args:{d:True for d in DOMAINS})
 assert verified and all(verified)
 assert fn(owner,value,keeper.status(),store.status(),observation=True)is True
print(json.dumps({"isolatedImports":3,"actualEntryBodies":2,"preActivationStatus":True,"forwardImportRejected":True,"inverseVerified":True,"hostAccountWrites":0}))
'''
        p=subprocess.run([sys.executable,"-I","-B","-c",program,str(CLI)],capture_output=True,text=True,timeout=30)
        self.assertEqual(0,p.returncode,p.stderr[-1800:])
        self.assertTrue(json.loads(p.stdout)["inverseVerified"])

    def test_exact_delta_normalization_rejects_any_other_entry_or_module_change(self):
        block=b"# BEGIN EMBEDDED SIGNED LOCAL RECOVERY MODULES\npass\n# END EMBEDDED SIGNED LOCAL RECOVERY MODULES"
        digest=hashlib.sha256(block).hexdigest()
        old=b"            if not self.unconfigured_stop_bridge(value,journal):self.code_compatibility(value,journal)"
        changed=(b"            if not source_module_link_compatibility(self,value,journal,LOCAL_SOURCE_MODULE_LINK_SHA256,regular_file,SmbIdentity):\n    "+old)
        original=b"identity\n"+old+b"\nrendered\n"+old+b"\n"
        declaration=('LOCAL_SOURCE_MODULE_LINK_SHA256="'+digest+'"\n').encode()
        current=b"identity\n"+block+b"\n"+declaration+changed+b"\nrendered\n"+block+b"\n"+declaration+changed+b"\n"
        self.assertEqual(original,normalize_module_link_cli(current,original,digest))
        for bad in [current+b"foreign\n",current.replace(b"pass\n",b"evil\n",1),current.replace(b"identity\n",b"changed\n",1),current.replace(declaration,b"",1)]:
            with self.assertRaises(ValueError):normalize_module_link_cli(bad,original,digest)

if __name__=="__main__":unittest.main()
