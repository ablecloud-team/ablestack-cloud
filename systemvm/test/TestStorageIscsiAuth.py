# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
from pathlib import Path
import copy,json,os,sys,tempfile,types,unittest
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from iscsi_auth import ConfigfsIscsiAuth,IscsiAuthOwnership,IscsiAuthError,iscsi_auth_name,iscsi_private_vault

class StorageIscsiAuthTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.target="iqn.2026-10.local.storage:owned";self.principal="iqn.2026-10.example:client"
        self.auth=self.root/self.target/"tpgt_1/acls"/self.principal/"auth";self.auth.mkdir(parents=True)
        self.fields=("userid","password","userid_mutual","password_mutual")
        for field in self.fields:(self.auth/field).touch(mode=0o600)
        self.config={"chapEnabled":True,"chapUsername":"synthetic-user","mutualChapEnabled":True,"mutualChapUsername":"synthetic-server"}
        self.secrets={"chapSecret":"SYNTHETIC_CHAP_ONLY","mutualChapSecret":"SYNTHETIC_MUTUAL_ONLY"};self.written=[]
        def kernel_write(descriptor,value):
            self.written.append(value);os.ftruncate(descriptor,0);return os.write(descriptor,value)
        self.ownership=self.owner()
        self.writer=ConfigfsIscsiAuth(self.root,writer=lambda:None,write=kernel_write,ownership=self.ownership)
    def payload(self,target=None,principal=None,config=None):
        return {"instanceUuid":"33333333-3333-4333-8333-333333333333","targets":[{
            "uuid":"11111111-1111-4111-8111-111111111111",
            "targetName":target or self.target,"volumeUuid":"22222222-2222-4222-8222-222222222222",
            "volumeSizeBytes":65536,"lunOrNamespace":"0","acls":[{
                "principal":principal or self.principal,"permission":"READ_WRITE",
                "config":self.config if config is None else config}]}]}
    def owner(self,target=None,principal=None,config=None,previous=None):
        return IscsiAuthOwnership(self.payload(target,principal,config),previous,writer=lambda:None)

    def test_auth_values_use_fixed_configfs_attributes_with_no_subprocess_and_public_only_receipt(self):
        with patch("subprocess.run",side_effect=AssertionError("credential subprocess attempted")):
            result=self.writer.apply(self.target,self.principal,self.config,self.secrets)
        self.assertTrue(result["credentialReadbackVerified"]);self.assertFalse(result["credentialSubprocessArguments"])
        self.assertEqual(self.secrets["chapSecret"],(self.auth/"password").read_text())
        self.assertEqual(self.secrets["mutualChapSecret"],(self.auth/"password_mutual").read_text())
        for secret in self.secrets.values():self.assertNotIn(secret,str(result))
    def test_all_secret_types_and_lengths_are_validated_before_first_write(self):
        for invalid in ("",None,"bad\ninput","NULL_hidden","x"*256,True):
            with self.subTest(kind=type(invalid).__name__):
                with self.assertRaises(ValueError):self.writer.apply(self.target,self.principal,self.config,{**self.secrets,"mutualChapSecret":invalid})
                self.assertEqual([],self.written);self.assertTrue(all((self.auth/field).read_bytes()==b"" for field in self.fields))
    def test_disabled_auth_writes_kernel_null_sentinel_and_does_not_replay_saved_secrets(self):
        self.writer.ownership=self.owner(config={})
        self.writer.apply(self.target,self.principal,{},self.secrets)
        self.assertEqual([b"NULL"]*4,self.written)
        self.assertTrue(all((self.auth/field).read_text()=="NULL" for field in self.fields))
    def test_symlink_attribute_blocks_before_any_userid_or_password_effect(self):
        outside=self.root/"outside";outside.write_text("must remain")
        (self.auth/"password").unlink();(self.auth/"password").symlink_to(outside)
        with self.assertRaises(ValueError):self.writer.apply(self.target,self.principal,self.config,self.secrets)
        self.assertEqual([],self.written);self.assertEqual("must remain",outside.read_text())
    def test_acl_directory_replacement_never_writes_a_winning_replacement_inode(self):
        previous=self.auth.with_name("old");replacement=[]
        def replace(descriptor,value):
            os.ftruncate(descriptor,0);count=os.write(descriptor,value)
            self.auth.rename(previous);self.auth.mkdir()
            for field in self.fields:
                path=self.auth/field;path.write_text("winner");path.chmod(0o600);replacement.append(path)
            return count
        self.writer.write=replace
        with self.assertRaisesRegex(ValueError,"ACL was replaced"):self.writer.apply(self.target,self.principal,self.config,self.secrets)
        self.assertTrue(all(path.read_text()=="winner" for path in replacement))
    def test_missing_writer_and_foreign_target_refuse_before_configfs_effects(self):
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":""}):
            writer=ConfigfsIscsiAuth(self.root,write=self.writer.write,ownership=self.ownership)
            with self.assertRaises(ValueError):writer.apply(self.target,self.principal,self.config,self.secrets)
        for foreign in ("iqn.2026-10.foreign:target","../../escape","iqn.2026-10.local.storage:x/escape"):
            with self.assertRaises(ValueError):self.writer.apply(foreign,self.principal,self.config,self.secrets)
        self.assertEqual([],self.written)
    def test_short_write_or_wrong_kernel_readback_never_echoes_private_values(self):
        for mode in ("short","wrong"):
            def injected(descriptor,value):
                if mode=="short":return len(value)-1
                os.ftruncate(descriptor,0);os.write(descriptor,b"unexpected");return len(value)
            self.writer.write=injected
            with self.assertRaises(ValueError) as rejected:self.writer.apply(self.target,self.principal,self.config,self.secrets)
            for value in self.secrets.values():self.assertNotIn(value,str(rejected.exception))

    def test_private_vault_requires_owner_only_metadata_and_exact_existing_resource_keys(self):
        import json
        secrets=self.root/"secrets";secrets.mkdir(mode=0o700);vault=secrets/"iscsi-acl-secrets.json"
        self.assertEqual({},iscsi_private_vault(vault,self.ownership))
        value={self.target+"|"+self.principal:dict(self.secrets)};vault.write_text(json.dumps(value));vault.chmod(0o600)
        self.assertEqual(value,iscsi_private_vault(vault,self.ownership))
        vault.chmod(0o644)
        with self.assertRaises(ValueError):iscsi_private_vault(vault,self.ownership)
        vault.chmod(0o600);vault.write_text(json.dumps({"iqn.2026-10.foreign:target|"+self.principal:dict(self.secrets)}))
        with self.assertRaises(ValueError):iscsi_private_vault(vault,self.ownership)
        self.assertEqual([],self.written)

    def test_leading_trailing_spaces_allowed_by_api_and_kernel_remain_exact(self):
        secrets={key:"  "+value+"  " for key,value in self.secrets.items()}
        result=self.writer.apply(self.target,self.principal,self.config,secrets)
        self.assertTrue(result["credentialReadbackVerified"])
        self.assertEqual(secrets["chapSecret"],(self.auth/"password").read_text())
        self.assertEqual(secrets["mutualChapSecret"],(self.auth/"password_mutual").read_text())

    def test_simple_open_and_fake_fd9_when_other_process_holds_lock_refuse_before_write(self):
        import fcntl,subprocess
        lock=self.root/"writer.lock";lock.touch(mode=0o600)
        try:previous=os.dup(9)
        except OSError:previous=None
        descriptor=os.open(lock,os.O_RDWR)
        if descriptor!=9:os.dup2(descriptor,9);os.close(descriptor)
        holder=None
        try:
            writer=ConfigfsIscsiAuth(self.root,write=self.writer.write,ownership=self.ownership)
            with patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9","ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(lock)}):
                with self.assertRaisesRegex(ValueError,"own exclusive lock"):writer.apply(self.target,self.principal,self.config,self.secrets)
                holder=subprocess.Popen([sys.executable,"-c","import fcntl,sys; f=open(sys.argv[1],'r+'); fcntl.flock(f,fcntl.LOCK_EX); print('READY',flush=True); sys.stdin.read(1)",str(lock)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
                self.assertEqual("READY",holder.stdout.readline().strip())
                with self.assertRaisesRegex(ValueError,"own exclusive lock"):writer.apply(self.target,self.principal,self.config,self.secrets)
                self.assertEqual([],self.written)
                holder.communicate(input="x",timeout=3);holder=None
                fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
                self.assertTrue(writer.apply(self.target,self.principal,self.config,self.secrets)["credentialReadbackVerified"])
        finally:
            if holder is not None:holder.kill();holder.communicate(timeout=3)
            if previous is not None:os.dup2(previous,9);os.close(previous)
            else:os.close(9)

    def test_configfs_ephemeral_preopen_inode_is_not_a_persistent_file_identity(self):
        import types
        real_open=os.open;real_stat=os.stat;held=set();order=[]
        def opened(path,flags,*args,**kwargs):
            descriptor=real_open(path,flags,*args,**kwargs)
            if path in self.fields and kwargs.get("dir_fd") is not None:held.add(path);order.append(("open",path))
            return descriptor
        def named(path,*args,**kwargs):
            value=real_stat(path,*args,**kwargs)
            if path in self.fields and kwargs.get("dir_fd") is not None:
                order.append(("stat",path))
                if path not in held:
                    value=types.SimpleNamespace(**{field:getattr(value,field) for field in ("st_dev","st_ino","st_mode","st_uid","st_gid","st_nlink")})
                    value.st_ino+=1
            return value
        with patch("iscsi_auth.os.open",side_effect=opened),patch("iscsi_auth.os.stat",side_effect=named):
            result=self.writer.apply(self.target,self.principal,self.config,self.secrets)
        self.assertTrue(result["credentialReadbackVerified"])
        for field in self.fields:self.assertLess(order.index(("open",field)),order.index(("stat",field)))

    def test_named_attribute_replaced_after_open_is_rejected_before_any_auth_write(self):
        real_open=os.open;replacement=self.auth/"replacement"
        def opened(path,flags,*args,**kwargs):
            descriptor=real_open(path,flags,*args,**kwargs)
            if path=="password" and kwargs.get("dir_fd") is not None:
                replacement.write_text("winner");replacement.chmod(0o600);os.replace(replacement,self.auth/"password")
            return descriptor
        with patch("iscsi_auth.os.open",side_effect=opened):
            with self.assertRaises(ValueError):self.writer.apply(self.target,self.principal,self.config,self.secrets)
        self.assertEqual([],self.written);self.assertEqual("winner",(self.auth/"password").read_text())

    def test_opened_untrusted_owner_is_rejected_before_any_attribute_write(self):
        password=self.auth/"password";actual=password.stat();identity=(actual.st_dev,actual.st_ino)
        real_fstat=os.fstat;observed=[]
        def foreign_fstat(descriptor):
            info=real_fstat(descriptor)
            if (info.st_dev,info.st_ino)==identity:
                observed.append(descriptor)
                fields={name:getattr(info,name)for name in dir(info)if name.startswith("st_")}
                fields["st_uid"]=os.geteuid()+1
                return types.SimpleNamespace(**fields)
            return info
        with patch("iscsi_auth.os.fstat",side_effect=foreign_fstat):
            with self.assertRaisesRegex(ValueError,"attribute is foreign"):self.writer.apply(self.target,self.principal,self.config,self.secrets)
        self.assertTrue(observed);self.assertEqual([],self.written)
        self.assertEqual(actual.st_uid,password.stat().st_uid)

    def test_generic_custom_iqn_supports_no_chap_chap_mutual_and_private_vault(self):
        custom="iqn.2026-10.local.ablestack:issue909-prepared-iscsi"
        self.auth.rename(self.root/"old-auth")
        auth=self.root/custom/"tpgt_1/acls"/self.principal/"auth"
        auth.mkdir(parents=True)
        for field in self.fields:(auth/field).touch(mode=0o600)
        for config,secrets in (({},{}),({"chapEnabled":True,"chapUsername":"fixture-user"},
                {"chapSecret":"SYNTHETIC_CHAP_ONLY"}),(self.config,self.secrets)):
            with self.subTest(mode=config):
                ownership=self.owner(target=custom,config=config)
                writer=ConfigfsIscsiAuth(self.root,writer=lambda:None,write=self.writer.write,ownership=ownership)
                self.assertTrue(writer.apply(custom,self.principal,config,secrets)["credentialReadbackVerified"])
                if config:
                    secret_dir=self.root/"custom-secrets";secret_dir.mkdir(mode=0o700,exist_ok=True)
                    vault=secret_dir/"vault.json";value={custom+"|"+self.principal:secrets}
                    vault.write_text(json.dumps(value));vault.chmod(0o600)
                    self.assertEqual(value,iscsi_private_vault(vault,ownership))

    def test_syntax_and_root_owned_paths_never_authorize_foreign_target_or_acl(self):
        for target,principal in (("iqn.2026-10.local.storage:foreign",self.principal),
                                 (self.target,"iqn.2026-10.example:other")):
            path=self.root/target/"tpgt_1/acls"/principal/"auth";path.mkdir(parents=True,exist_ok=True)
            for field in self.fields:(path/field).touch(mode=0o600)
            iscsi_auth_name(target,True);iscsi_auth_name(principal)
            with self.assertRaises(IscsiAuthError) as failure:
                self.writer.apply(target,principal,self.config,self.secrets)
            self.assertEqual("OWNERSHIP_MISMATCH",failure.exception.iscsi_auth_code)
            self.assertEqual([],self.written)
        with self.assertRaises(IscsiAuthError) as failure:
            ConfigfsIscsiAuth(self.root,writer=lambda:None,write=self.writer.write).apply(
                self.target,self.principal,self.config,self.secrets)
        self.assertEqual("OWNERSHIP_INVALID",failure.exception.iscsi_auth_code)

    def test_canonical_identity_and_instance_binding_reject_before_any_auth_write(self):
        for field,value in (("uuid","not-uuid"),("volumeUuid","not-uuid"),
                            ("volumeSizeBytes",True),("volumeSizeBytes",0),("backstoreType","FILE")):
            packet=self.payload();packet["targets"][0][field]=value
            with self.subTest(field=field):
                with self.assertRaises(IscsiAuthError):IscsiAuthOwnership(packet,writer=lambda:None)
        previous=self.payload();previous["instanceUuid"]="55555555-5555-4555-8555-555555555555"
        with self.assertRaises(IscsiAuthError):self.owner(previous=previous)
        packet=self.payload();packet["targets"].append(copy.deepcopy(packet["targets"][0]))
        with self.assertRaises(IscsiAuthError):IscsiAuthOwnership(packet,writer=lambda:None)
        self.assertEqual([],self.written)

    def test_owned_context_is_frozen_against_caller_payload_and_grant_mutation(self):
        packet=self.payload();owner=IscsiAuthOwnership(packet,writer=lambda:None)
        packet["targets"][0]["targetName"]="iqn.2026-10.example:foreign"
        packet["targets"][0]["acls"][0]["principal"]="iqn.2026-10.example:other"
        packet["targets"][0]["acls"][0]["permission"]="READ_ONLY"
        self.assertTrue(owner.authorize(self.target,self.principal,config=self.config,expected={"0":0}))
        with self.assertRaises(IscsiAuthError):owner.authorize(packet["targets"][0]["targetName"],self.principal)
        with self.assertRaises(IscsiAuthError):owner.authorize(self.target,self.principal,expected={"0":1})
        with self.assertRaises(TypeError):owner._grants[(self.target,self.principal)]["0"]=1
        with self.assertRaises(AttributeError):owner._vault=frozenset()

    def test_legitimate_target_update_retains_only_exact_source_vault_read_authority(self):
        previous=self.payload()
        custom="iqn.2026-10.local.ablestack:renamed"
        packet=self.payload(target=custom)
        packet["targets"][0]["volumeUuid"]="66666666-6666-4666-8666-666666666666"
        packet["targets"][0]["lunOrNamespace"]="7"
        owner=IscsiAuthOwnership(packet,previous,writer=lambda:None)
        self.assertTrue(owner.authorize(custom,self.principal,config=self.config,expected={"7":0}))
        self.assertTrue(owner.authorize(self.target,self.principal,vault=True))
        with self.assertRaises(IscsiAuthError):owner.authorize(self.target,self.principal)
        with self.assertRaises(IscsiAuthError):owner.authorize(custom,"iqn.2026-10.example:foreign",vault=True)
        directory=self.root/"update-secrets";directory.mkdir(mode=0o700);vault=directory/"vault.json"
        saved={self.target+"|"+self.principal:self.secrets}
        vault.write_text(json.dumps(saved));vault.chmod(0o600)
        self.assertEqual(saved,iscsi_private_vault(vault,owner))

    def test_target_and_initiator_diagnostics_are_fixed_and_secret_free(self):
        for target,initiator,code in (("iqn.invalid",self.principal,"TARGET_IQN_INVALID"),
                                      (self.target,"../../escape","INITIATOR_IQN_INVALID")):
            with self.assertRaises(IscsiAuthError) as failure:
                self.writer.apply(target,initiator,self.config,self.secrets)
            self.assertEqual(code,failure.exception.iscsi_auth_code)
            self.assertNotIn(target,str(failure.exception));self.assertNotIn(initiator,str(failure.exception))
            for secret in self.secrets.values():self.assertNotIn(secret,str(failure.exception))
        self.assertEqual([],self.written)

if __name__=="__main__":unittest.main()
