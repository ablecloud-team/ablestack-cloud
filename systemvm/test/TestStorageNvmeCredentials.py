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

from pathlib import Path
import copy,json,os,stat,sys,tempfile,unittest,uuid
from unittest.mock import patch
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from nvme_credentials import NvmeCredentialStore


class StorageNvmeCredentialsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.store=NvmeCredentialStore(self.root/'secret'/'nvmeof-acl-secrets.json')
        self.instance=str(uuid.uuid4());self.host='nqn.2026-10.test.example:client'
        self.payload={'instanceUuid':self.instance,'subsystems':[{'targetName':'nqn.2026-10.local.storage:target','hosts':[
            {'uuid':str(uuid.uuid4()),'principal':self.host,'config':{'dhChapEnabled':True,'dhChapCtrlEnabled':True},
             'secrets':{'dhChapKey':'DHHC-1:SYNTHETIC_HOST_NOT_REAL','dhChapCtrlKey':'DHHC-1:SYNTHETIC_CONTROLLER_NOT_REAL'}}]}]}

    def test_cold_boot_hydrates_same_host_and_controller_credentials_from_protected_instance_store(self):
        original=copy.deepcopy(self.payload);merged,record=self.store.hydrate(self.payload)
        self.assertEqual(original,self.payload);self.assertFalse(self.store.path.exists())
        self.store.persist(record)
        self.assertEqual(0o600,stat.S_IMODE(self.store.path.stat().st_mode));self.assertEqual(0o700,stat.S_IMODE(self.store.path.parent.stat().st_mode))
        cold=copy.deepcopy(self.payload);cold['subsystems'][0]['hosts'][0].pop('secrets')
        observed,_=NvmeCredentialStore(self.store.path).hydrate(cold)
        self.assertEqual(merged['subsystems'][0]['hosts'][0]['secrets'],observed['subsystems'][0]['hosts'][0]['secrets'])

    def test_missing_secret_foreign_instance_and_unprotected_file_fail_before_any_persist(self):
        missing=copy.deepcopy(self.payload);missing['subsystems'][0]['hosts'][0].pop('secrets')
        with self.assertRaises(ValueError):self.store.hydrate(missing)
        self.assertFalse(self.store.path.parent.exists())
        _,record=self.store.hydrate(self.payload);self.store.persist(record)
        with self.assertRaisesRegex(ValueError,'another instance'):self.store.hydrate({**self.payload,'instanceUuid':str(uuid.uuid4())})
        self.store.path.chmod(0o644)
        with self.assertRaises(ValueError):self.store.hydrate(self.payload)

    def test_shared_host_conflicting_policy_or_key_rejects_instead_of_changing_other_subsystem_auth(self):
        for field,value in (('secrets',{'dhChapKey':'DHHC-1:DIFFERENT','dhChapCtrlKey':'DHHC-1:OTHER'}),('config',{'dhChapEnabled':False,'dhChapCtrlEnabled':False})):
            payload=copy.deepcopy(self.payload);other=copy.deepcopy(payload['subsystems'][0]);other['targetName']+='-other';other['hosts'][0][field]=value;payload['subsystems'].append(other)
            with self.assertRaisesRegex(ValueError,'conflicting'):self.store.hydrate(payload)
            self.assertFalse(self.store.path.exists())

    def test_no_auth_or_disabled_state_never_creates_plain_credential_file(self):
        for payload in ({'subsystems':[]},{**self.payload,'enabled':False}):
            merged,record=self.store.hydrate(payload);self.store.persist(record)
            self.assertFalse(self.store.path.parent.exists())

    def test_symlink_and_file_read_swap_are_rejected(self):
        _,record=self.store.hydrate(self.payload);self.store.persist(record)
        original=self.store.path.read_bytes();alternate=self.store.path.parent/'other';alternate.write_bytes(original);alternate.chmod(0o600)
        old_lstat=Path.lstat
        def swap(path,*args,**kwargs):
            info=old_lstat(path,*args,**kwargs)
            if path==self.store.path:os.replace(alternate,path)
            return info
        with patch.object(Path,'lstat',swap):
            with self.assertRaisesRegex(ValueError,'changed while opening'):self.store.read()
        self.store.path.unlink();self.store.path.symlink_to(alternate)
        with self.assertRaises(ValueError):self.store.read()

if __name__=='__main__':unittest.main()
