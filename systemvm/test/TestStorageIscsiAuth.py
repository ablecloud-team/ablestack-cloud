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
import os,sys,tempfile,unittest
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from iscsi_auth import ConfigfsIscsiAuth,iscsi_private_vault

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
        self.writer=ConfigfsIscsiAuth(self.root,writer=lambda:None,write=kernel_write)
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
            writer=ConfigfsIscsiAuth(self.root,write=self.writer.write)
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
        self.assertEqual({},iscsi_private_vault(vault))
        value={self.target+"|"+self.principal:dict(self.secrets)};vault.write_text(json.dumps(value));vault.chmod(0o600)
        self.assertEqual(value,iscsi_private_vault(vault))
        vault.chmod(0o644)
        with self.assertRaises(ValueError):iscsi_private_vault(vault)
        vault.chmod(0o600);vault.write_text(json.dumps({"iqn.2026-10.foreign:target|"+self.principal:dict(self.secrets)}))
        with self.assertRaises(ValueError):iscsi_private_vault(vault)
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
            writer=ConfigfsIscsiAuth(self.root,write=self.writer.write)
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
        password=self.auth/"password";os.chown(password,65534,password.stat().st_gid)
        try:
            with self.assertRaisesRegex(ValueError,"attribute is foreign"):self.writer.apply(self.target,self.principal,self.config,self.secrets)
            self.assertEqual([],self.written)
        finally:os.chown(password,os.geteuid(),password.stat().st_gid)

if __name__=="__main__":unittest.main()
