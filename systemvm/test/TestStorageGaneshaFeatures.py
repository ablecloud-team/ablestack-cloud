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

import hashlib,json,os,sys,tempfile,unittest,subprocess
from pathlib import Path
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from ganesha_features import ganesha_acl_capabilities,GANESHA_ACL_PREFIX,GANESHA_ACL_SOURCE,GANESHA_SERVICE_BINDINGS,MANAGED_GANESHA_UNIT

@unittest.skipUnless(os.geteuid()==0,"Runtime attestation requires root-owned files")
class StorageGaneshaFeaturesTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.manifest=self.root/"etc/ablestack-storage/ganesha-build-manifest.json";self.manifest.parent.mkdir(parents=True)
        names=["bin/ganesha.nfsd","lib/x86_64-linux-gnu/libganesha_nfsd.so.5.5.3","lib/x86_64-linux-gnu/libntirpc.so.5.0","lib/x86_64-linux-gnu/ganesha/libfsalvfs.so"]
        self.files={}
        for name in names:
            path=self.root/GANESHA_ACL_PREFIX.lstrip("/")/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(("synthetic ELF fixture:"+name).encode());path.chmod(0o644)
            self.files[name]=hashlib.sha256(path.read_bytes()).hexdigest()
        self.proc=self.root/"proc";self.proc.mkdir()
        unit=self.root/"etc/systemd/system/ablestack-storage-ganesha@.service";unit.parent.mkdir(parents=True);unit.write_text(MANAGED_GANESHA_UNIT)
        for path,content in GANESHA_SERVICE_BINDINGS.items():
            target=self.root/path.lstrip("/");target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content);target.chmod(0o644)
        self.value={"serviceBindings":{path:hashlib.sha256(content.encode()).hexdigest() for path,content in GANESHA_SERVICE_BINDINGS.items()},"schemaVersion":1,"version":"5.5.3","sourceCommit":GANESHA_ACL_SOURCE,"prefix":GANESHA_ACL_PREFIX,
                    "requiredCmakeFlags":{"ENABLE_VFS_POSIX_ACL":"ON","ENABLE_VFS_DEBUG_ACL":"OFF","USE_FSAL_VFS":"ON","USE_DBUS":"ON"},
                    "files":self.files,"selfTest":{"namedAclReadWrite":True,"defaultAclInheritance":True}}
        self.write()
    def write(self):self.manifest.write_text(json.dumps(self.value));self.manifest.chmod(0o644)
    def probe(self):return ganesha_acl_capabilities(self.manifest,self.root,str(self.proc))
    def test_exact_root_protected_manifest_hashes_and_real_test_attestation_are_required(self):
        before=self.manifest.stat();content=self.manifest.read_bytes()
        result=self.probe();self.assertTrue(result["nfsVfsPosixAclSupported"]);self.assertIn("NFS_VFS_POSIX_ACL",result["supportedFeatures"])
        self.assertFalse(result["activeRuntimeVfsVerified"]);self.assertTrue(result["activeRuntimeNotApplicable"])
        self.assertEqual(content,self.manifest.read_bytes());self.assertEqual(before,self.manifest.stat())
        self.manifest.unlink();self.assertFalse(self.probe()["nfsVfsPosixAclSupported"]);self.assertFalse(self.manifest.exists())
    def test_flag_version_selftest_tamper_or_runtime_bytes_fail_closed(self):
        for change in ({"schemaVersion":True},{"version":"4.3"},{"sourceCommit":"0"*40},{"selfTest":{"namedAclReadWrite":True,"defaultAclInheritance":False}},
                       {"requiredCmakeFlags":{**self.value["requiredCmakeFlags"],"ENABLE_VFS_DEBUG_ACL":"ON"}}):
            original=dict(self.value);self.value.update(change);self.write()
            self.assertFalse(self.probe()["nfsVfsPosixAclSupported"])
            self.value=original
        self.write();path=self.root/GANESHA_ACL_PREFIX.lstrip("/")/"lib/x86_64-linux-gnu/ganesha/libfsalvfs.so"
        path.write_bytes(b"replacement");self.assertFalse(self.probe()["nfsVfsPosixAclSupported"])
    def test_inactive_selected_legacy_binary_or_unknown_override_never_attests_support(self):
        path=self.root/"etc/systemd/system/ablestack-storage-ganesha@.service.d/ablestack-storage-vfs-acl.conf"
        path.write_text(path.read_text().replace(GANESHA_ACL_PREFIX+"/bin/ganesha.nfsd","/usr/bin/ganesha.nfsd"))
        self.assertFalse(self.probe()["nfsVfsPosixAclSupported"])
        path.write_text(GANESHA_SERVICE_BINDINGS["/"+str(path.relative_to(self.root))])
        override=path.parent/"zz-legacy.conf";override.write_text("[Service]\nExecStart=\nExecStart=/usr/bin/ganesha.nfsd\n")
        self.assertFalse(self.probe()["nfsVfsPosixAclSupported"])

    def fake_process(self,binary,library):
        process=self.proc/"123";process.mkdir();(process/"exe").symlink_to(binary)
        (process/"stat").write_text("123 (ganesha.nfsd) "+" ".join(["S"]+["0"]*18+["100"]+["0"]*8))
        value=library.stat()
        (process/"maps").write_text("1-2 r-xp 0 %02x:%02x %d %s\n"%(os.major(value.st_dev),os.minor(value.st_dev),value.st_ino,library))
        return process

    def test_active_pid_and_vfs_inode_must_bind_to_actual_private_runtime(self):
        binary=self.root/GANESHA_ACL_PREFIX.lstrip("/")/"bin/ganesha.nfsd"
        library=self.root/GANESHA_ACL_PREFIX.lstrip("/")/"lib/x86_64-linux-gnu/ganesha/libfsalvfs.so"
        process=self.fake_process(binary,library)
        result=self.probe();self.assertTrue(result["activeRuntimeVfsVerified"]);self.assertEqual(123,result["activeGaneshaProcesses"][0]["pid"])
        legacy=self.root/"usr/bin/ganesha.nfsd";legacy.parent.mkdir(parents=True);legacy.write_bytes(b"legacy")
        (process/"exe").unlink();(process/"exe").symlink_to(legacy)
        self.assertFalse(self.probe()["nfsVfsPosixAclSupported"])
        (process/"exe").unlink();(process/"exe").symlink_to(binary)
        (process/"maps").write_text("1-2 r-xp 0 00:00 1 /usr/lib/ganesha/libfsalvfs.so\n")
        self.assertFalse(self.probe()["nfsVfsPosixAclSupported"])

    def test_installed_fixed_readonly_capability_uses_no_native_writer_or_new_payload(self):
        cli=LIB.parents[1]/"bin/ablestack-storagectl"
        writer=self.root/"never-created"/"writer.lock"
        result=subprocess.run([str(cli),"nfs","capabilities"],env={**os.environ,"ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(writer)},capture_output=True,text=True,timeout=10)
        self.assertEqual(0,result.returncode,result.stderr);self.assertEqual(1,len(result.stdout.splitlines()))
        self.assertFalse(json.loads(result.stdout)["nfsVfsPosixAclSupported"]);self.assertFalse(writer.parent.exists())
        expected=(LIB/"ganesha_features.py").read_text().strip()
        self.assertTrue(expected in cli.read_text(),"Capability must be fully included in the signed CLI")

    def test_symlink_or_group_writable_proof_cannot_attest_support(self):
        self.manifest.chmod(0o664);self.assertFalse(self.probe()["nfsVfsPosixAclSupported"])
        self.write();saved=self.manifest.with_suffix(".saved");self.manifest.rename(saved);self.manifest.symlink_to(saved)
        self.assertFalse(self.probe()["nfsVfsPosixAclSupported"])

if __name__=="__main__":unittest.main()
