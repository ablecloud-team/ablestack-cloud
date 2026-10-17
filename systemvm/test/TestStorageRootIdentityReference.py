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

import copy,json,os,sys,tempfile,unittest,uuid,fcntl,subprocess
from pathlib import Path
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from root_identity_reference import RootIdentityReference
from identity_capsule import encrypt
from posix_receipt_transfer import posix_row_sha256
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

class StorageRootIdentityReferenceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.base=Path(self.temp.name)
        operation=str(uuid.uuid4());self.original={"instanceUuid":str(uuid.uuid4()),"templateUpgradeUuid":str(uuid.uuid4()),"operationUuid":operation,"revision":39}
        self.current={**self.original,"operationUuid":str(uuid.uuid4()),"revision":40};self.sha="f"*64
        self.reference=RootIdentityReference(self.base/"references")
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self.public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        self.private=key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()).decode()
        self.capsule=encrypt({"schemaVersion":1,"files":{},"accounts":{}},self.public,self.original["instanceUuid"]+":"+operation)
        self.marker={"maintenanceKind":"ROOT","bootHeld":True,"scope":self.current}
    def test_different_operation_requires_exact_retained_ciphertext_and_original_root_row(self):
        request={**self.current,"originalSourceScope":self.original,"sourceConfigurationSha256":self.sha}
        with self.assertRaises(ValueError):self.reference.authorize(request,self.capsule,self.marker)
        self.reference.retain(self.original,self.capsule,self.sha)
        expected=self.original["instanceUuid"]+":"+self.original["operationUuid"]
        self.assertEqual(expected,self.reference.authorize(request,self.capsule,self.marker))
        self.assertEqual(0o600,self.reference.path(self.original,self.capsule).stat().st_mode&0o777)
        for changed in ({"originalSourceScope":{**self.original,"templateUpgradeUuid":str(uuid.uuid4())}},{"sourceConfigurationSha256":"a"*64},{"originalSourceScope":{**self.original,"revision":41}}):
            with self.assertRaises(ValueError):self.reference.authorize({**request,**changed},self.capsule,self.marker)
        changed={**self.capsule,"wrappedKey":self.capsule["wrappedKey"][:-4]+"AAAA"}
        with self.assertRaises(ValueError):self.reference.authorize(request,changed,self.marker)
    def test_clone_foreign_marker_or_unprotected_reference_cannot_adopt_original_capsule(self):
        self.reference.retain(self.original,self.capsule,self.sha)
        request={**self.current,"originalSourceScope":self.original,"sourceConfigurationSha256":self.sha}
        for marker in ({**self.marker,"bootHeld":False},{**self.marker,"maintenanceKind":"SERVICE"},{**self.marker,"scope":self.original}):
            with self.assertRaises(ValueError):self.reference.authorize(request,self.capsule,marker)
        self.reference.path(self.original,self.capsule).chmod(0o644)
        with self.assertRaises(ValueError):self.reference.authorize(request,self.capsule,self.marker)
    def test_actual_embedded_crypto_rpc_import_then_posix_attest_preserves_canonical_data_and_allows_only_retained_rollback(self):
        policy=str(uuid.uuid4());volume=str(uuid.uuid4());filesystem=str(uuid.uuid4())
        directory=self.base/"data";directory.mkdir();directory.chmod(0o770);child=directory/"sentinel";child.write_bytes(b"preserved DATA sentinel")
        inode=directory.stat();identity={"filesystemUuid":filesystem,"device":inode.st_dev,"inode":inode.st_ino,"effectiveUid":inode.st_uid,"effectiveGid":inode.st_gid,"effectiveMode":"0770","aclSha256":"a"*64}
        canonical_identity={**identity,"device":identity["device"]+1}
        request={"uuid":policy,"instanceUuid":self.original["instanceUuid"],"volumeUuid":volume,"volumeMountPath":"/srv/ablestack-storage/volumes/"+volume,"relativePath":"leaf","revision":2,"config":{"directoryMode":"0770"}}
        row={"request":request,"config":request["config"],"effective":{"directoryIdentity":canonical_identity}}
        receipt={"schemaVersion":1,"phase":"COMPLETE","scope":{"instanceUuid":request["instanceUuid"],"policyUuid":policy,"volumeUuid":volume,"revision":2,"volumeMountPath":request["volumeMountPath"],"relativePath":"leaf"},"requestSha256":posix_row_sha256(request),"directoryIdentity":canonical_identity}
        records={policy:{"canonicalRow":row,"rowSha256":posix_row_sha256(row),"postReceipt":receipt}}
        payload={"schemaVersion":1,"files":{},"accounts":{},"posixPolicies":records,"sourceConfigurationSha256":self.sha}
        capsule=encrypt(payload,self.public,self.original["instanceUuid"]+":"+self.original["operationUuid"])
        bindings=[{"volumeUuid":volume,"sizeBytes":1024,"filesystemUuid":filesystem}]
        metadata=self.base/"fixture.json"
        observation={"volumeUuid":volume,"mappingStatus":"EXACT","matchedBy":"VOLUME_SERIAL","serial":volume.replace("-","")[:20],"sizeBytes":1024,"filesystemUuid":filesystem,"mounts":[{"target":request["volumeMountPath"]}]}
        helper=self.base/"native-fixture";helper.write_text("#!/usr/bin/python3\nimport json,os,sys\nfrom pathlib import Path\nv=json.loads(Path(os.environ['ROOT_RPC_FIXTURE']).read_text())\nargs=sys.argv[1:4]\nresult=v['maintenance'] if args==['operation','maintenance','status'] else {'success':True,'directoryIdentity':v['directoryIdentity']} if args==['posix','directory','inspect'] else {'success':True,'volumes':[v['volume']]}\nprint(json.dumps(result))\n");helper.chmod(0o755)
        cli=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        body=cli.read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        before_directory=directory.stat();before_child=child.stat();canonical=json.dumps(row,sort_keys=True)
        env=dict(os.environ,ABLESTACK_STORAGE_ROOT_IDENTITY_REFERENCES=str(self.base/"rpc-references"),ABLESTACK_STORAGE_POSIX_RECEIPTS=str(self.base/"receipts"),ROOT_RPC_FIXTURE=str(metadata))
        def invoke(action,root_scope,original=None,success=True):
            metadata.write_text(json.dumps({"maintenance":{"success":True,"maintenanceKind":"ROOT","bootHeld":True,"scope":root_scope},"directoryIdentity":identity,"volume":observation}))
            value={**root_scope,"capsule":capsule,"credentialPrivateKey":self.private,"sourceConfigurationSha256":self.sha,"fileVolumeBindings":bindings,"deferNvmeReplay":True}
            if original is not None:value["originalSourceScope"]=original
            descriptor=os.memfd_create("synthetic-root-rpc",os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING)
            try:
                os.fchmod(descriptor,0o600);os.write(descriptor,json.dumps(value).encode());os.lseek(descriptor,0,os.SEEK_SET)
                fcntl.fcntl(descriptor,fcntl.F_ADD_SEALS,fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL)
                result=subprocess.run([sys.executable,"-",action,"/proc/self/fd/"+str(descriptor),str(helper)],input=body,env=env,pass_fds=(descriptor,),capture_output=True,text=True,timeout=15)
            finally:os.close(descriptor)
            self.assertEqual(success,result.returncode==0,result.stderr+result.stdout)
            self.assertNotIn(self.private,result.stdout+result.stderr)
            return json.loads(result.stdout) if success else None
        imported=invoke("import",self.original);self.assertTrue(imported["posixReceiptRestoreDeferred"])
        attested=invoke("posix-attest",self.original);self.assertEqual(1,attested["posixReceiptsRestored"]);self.assertFalse(attested["dataPermissionsChanged"]);self.assertFalse(attested["canonicalDesiredStateChanged"])
        invoke("posix-attest",self.current,self.original)
        foreign={**self.current,"templateUpgradeUuid":str(uuid.uuid4())};invoke("posix-attest",foreign,self.original,False)
        self.assertEqual(before_directory,directory.stat());self.assertEqual(before_child,child.stat());self.assertEqual(b"preserved DATA sentinel",child.read_bytes());self.assertEqual(canonical,json.dumps(row,sort_keys=True))
        result=json.loads((self.base/"receipts"/(policy+".json")).read_text());self.assertEqual(identity,result["directoryIdentity"]);self.assertEqual(canonical_identity,result["canonicalDirectoryIdentity"])
        self.assertEqual(0o600,(self.base/"receipts"/(policy+".json")).stat().st_mode&0o777)

if __name__=="__main__":unittest.main()
