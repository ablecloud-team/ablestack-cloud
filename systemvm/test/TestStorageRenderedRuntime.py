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

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from native_render_runtime import NativeRenderedRuntime


class StorageRenderedRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.cli=Path(self.temp.name)/"fixed-cli"
        self.cli.write_text("#!/usr/bin/python3\nimport fcntl,json,os,sys,time\nif sys.argv[1]=='sleep': time.sleep(5)\nvalue=json.load(open(sys.argv[-1])) if sys.argv[-1].startswith('/proc/self/fd/') else {}\nprint(json.dumps({'success':True,'args':sys.argv[1:],'payloadPresent':bool(value),'rootSourceMode':os.environ.get('ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY'),'sealed':bool(fcntl.fcntl(int(sys.argv[-1].rsplit('/',1)[1]),fcntl.F_GET_SEALS)) if value else False}))\n")
        self.cli.chmod(0o700)
        self.adapter=NativeRenderedRuntime(self.cli,None)

    def test_credentials_use_sealed_inherited_memfd_and_never_appear_in_arguments_or_output(self):
        result=self.adapter.command(('fixed',),{'password':'SYNTHETIC_PASSWORD_NOT_PERSISTED'})
        self.assertTrue(result['payloadPresent']);self.assertTrue(result['sealed'])
        self.assertEqual('fixed',result['args'][0]);self.assertTrue(result['args'][-1].startswith('/proc/self/fd/'))
        self.assertNotIn('SYNTHETIC',json.dumps(result))
        self.assertEqual(['fixed-cli'],[item.name for item in Path(self.temp.name).iterdir()])

    def test_total_deadline_is_enforced_and_child_is_boundedly_terminated(self):
        self.adapter.deadline=time.monotonic()+.05
        began=time.monotonic()
        with self.assertRaises(TimeoutError):self.adapter.command(('sleep',))
        self.assertLess(time.monotonic()-began,1)

    def test_active_unknown_or_degraded_block_sessions_never_enter_a_runtime_rebuild(self):
        good={'success':True,'status':'ok','sessionSchemaVersion':2,'sessions':[], 'observedIscsiTcpCount':0,'observedNvmeofTcpCount':0,'warnings':[]}
        self.adapter.command=lambda *args,**kwargs:good
        self.adapter.require_drained(('ISCSI','NVMEOF'))
        for bad in ({**good,'sessions':[{'protocol':'ISCSI'}]}, {**good,'observedNvmeofTcpCount':1},
                    {**good,'warnings':['unavailable']},{**good,'status':'degraded'},{'success':True}):
            self.adapter.command=lambda *args,**kwargs:bad
            with self.assertRaises(ValueError):self.adapter.require_drained(('ISCSI','NVMEOF'))

    def test_untouched_file_domain_never_calls_block_session_collector(self):
        self.adapter.command=lambda *args,**kwargs: (_ for _ in ()).throw(AssertionError('block collector was touched'))
        self.adapter.require_drained(('NFS','SMB'))

    def test_unknown_adapter_and_foreign_credential_acl_are_rejected_before_execution(self):
        class Store:
            def inspect(self,path):return {}
        self.adapter.store=Store()
        self.adapter.command=lambda *args,**kwargs: (_ for _ in ()).throw(AssertionError('unexpected execution'))
        with self.assertRaises(ValueError):self.adapter.replay(Path('/unused'),'EXECUTABLE')
        self.adapter.credentials={'target':{'SMB':{'foreign':{'password':'SYNTHETIC'}}}}
        desired={name:None for name in ('desired-state/nfs-export-apply.json','desired-state/smb-share-apply.json','iscsi-targets.json','nvmeof-subsystems.json')}
        with patch('native_render_runtime.rendered_read',return_value=json.dumps(desired).encode()):
            with self.assertRaises(ValueError):self.adapter.payload(Path('/unused'),'SMB')

    def test_cold_nvme_readback_requires_durable_instance_and_exact_host_controller_keys(self):
        from nvme_credentials import NvmeCredentialStore
        instance=str(uuid.uuid4());host='nqn.2026-10.test.example:client';name='nqn.2026-10.local.storage:target';root=Path(self.temp.name)/'nvmet';self.adapter.nvme_root=root
        subsystem=root/'subsystems'/name;(subsystem/'allowed_hosts').mkdir(parents=True);(subsystem/'allowed_hosts'/host).touch();(subsystem/'namespaces').mkdir();(subsystem/'attr_allow_any_host').write_text('0');(root/'ports').mkdir()
        auth=root/'hosts'/host;auth.mkdir(parents=True);(auth/'dhchap_key').write_text('DHHC-1:SYNTHETIC_HOST');(auth/'dhchap_ctrl_key').write_text('DHHC-1:SYNTHETIC_CONTROLLER')
        self.adapter.nvme_credentials=NvmeCredentialStore(Path(self.temp.name)/'secrets'/'nvmeof-acl-secrets.json')
        record={'schemaVersion':1,'instanceUuid':instance,'hosts':{host:{'dhChapKey':'DHHC-1:SYNTHETIC_HOST','dhChapCtrlKey':'DHHC-1:SYNTHETIC_CONTROLLER'}}};self.adapter.nvme_credentials.persist(record)
        plan={'protocol':'NVMEOF','targets':[{'targetName':name,'allowAnyHost':False,'acls':[{'uuid':str(uuid.uuid4()),'principal':host,'config':{'dhChapEnabled':True,'dhChapCtrlEnabled':True}}],'backstores':[],'listeners':[]}]}
        self.assertTrue(self.adapter.verify_block(plan,instance_uuid=instance))
        with self.assertRaises(ValueError):self.adapter.verify_block(plan,instance_uuid=str(uuid.uuid4()))
        (auth/'dhchap_key').write_text('DHHC-1:DIFFERENT')
        with self.assertRaisesRegex(ValueError,'protected durable'):self.adapter.verify_block(plan,instance_uuid=instance)

    def test_rendered_boot_inherits_outer_total_deadline_instead_of_resetting_240_seconds(self):
        deadline=time.monotonic()+.05
        with patch.dict(os.environ,{'ABLESTACK_STORAGE_BOOT_DEADLINE_MONOTONIC':str(deadline)}):
            adapter=NativeRenderedRuntime(self.cli,None)
        self.assertEqual(deadline,adapter.deadline)
        with self.assertRaises(TimeoutError):adapter.command(('sleep',))

    def test_block_replay_reobserves_exact_serial_and_allows_device_rename_without_binding_other_data(self):
        scope={'instanceUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'revision':3};volume=str(uuid.uuid4())
        self.adapter.store=type('Store',(),{'inspect':lambda self,path:{'scope':scope}})()
        plan={'targets':[{'backstores':[{'volumeUuid':volume,'sizeBytes':1024,'serial':'exact-volume-serial','devicePath':'/dev/old'}]}]}
        good={'volumeUuid':volume,'mappingStatus':'EXACT','matchedBy':'VOLUME_SERIAL','serial':'exact-volume-serial','sizeBytes':1024,'mounts':[],'observedDevicePath':'/dev/new'}
        self.adapter.command=lambda *args,**kwargs:{'volumes':[good]}
        self.assertEqual({volume:'/dev/new'},self.adapter.reobserve_block(Path('/unused'),plan))
        for change in ({'matchedBy':'FILESYSTEM_UUID'},{'serial':'foreign'},{'sizeBytes':2048},{'mounts':[{'target':'/export'}]}):
            self.adapter.command=lambda *args,**kwargs:{'volumes':[{**good,**change}]}
            with self.assertRaises(ValueError):self.adapter.reobserve_block(Path('/unused'),plan)

    def test_cold_iscsi_readback_checks_durable_keys_and_kernel_null_unset_values(self):
        instance=str(uuid.uuid4());target="iqn.2026-10.local.storage:target";principal="iqn.2026-10.example:client"
        root=Path(self.temp.name)/"iscsi";self.adapter.iscsi_root=root;group=root/target/"tpgt_1"
        auth=group/"acls"/principal/"auth";auth.mkdir(parents=True);(group/"np").mkdir();(group/"lun").mkdir();(group/"attrib").mkdir()
        (group/"attrib/authentication").write_text("1")
        for field,value in (("userid","synthetic-user"),("password","  SYNTHETIC_CHAP_ONLY  "),("userid_mutual","NULL"),("password_mutual","NULL")):(auth/field).write_text(value)
        secret_dir=Path(self.temp.name)/"iscsi-secrets";secret_dir.mkdir(mode=0o700);vault=secret_dir/"iscsi-acl-secrets.json"
        vault.write_text(json.dumps({target+"|"+principal:{"chapSecret":"  SYNTHETIC_CHAP_ONLY  "}}));vault.chmod(0o600);self.adapter.iscsi_credentials_path=vault
        plan={"protocol":"ISCSI","targets":[{"targetName":target,"acls":[{"uuid":str(uuid.uuid4()),"principal":principal,"config":{"chapEnabled":True,"chapUsername":"synthetic-user","mutualChapEnabled":False}}],"listeners":[],"backstores":[]}]}
        self.assertTrue(self.adapter.verify_block(plan,instance_uuid=instance))
        (auth/"password").write_text("SYNTHETIC_DIFFERENT")
        with self.assertRaisesRegex(ValueError,"protected durable"):self.adapter.verify_block(plan,instance_uuid=instance)
        plan["targets"][0]["acls"][0]["config"]={};(group/"attrib/authentication").write_text("0")
        for field in ("userid","password","userid_mutual","password_mutual"):(auth/field).write_text("NULL")
        self.assertTrue(self.adapter.verify_block(plan,instance_uuid=instance))

    def test_root_source_replay_mode_is_coordinator_owned_and_removed_from_ordinary_commands(self):
        with patch.dict(os.environ,{"ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY":"1"}):
            self.assertIsNone(self.adapter.command(("fixed",))["rootSourceMode"])
            self.assertIsNone(self.adapter.command(("fixed",),replay=Path("/synthetic/source"))["rootSourceMode"])
            self.adapter.root_source_replay=True
            self.assertEqual("1",self.adapter.command(("fixed",),replay=Path("/synthetic/source"))["rootSourceMode"])
            self.assertIsNone(self.adapter.command(("fixed",))["rootSourceMode"])

if __name__=='__main__':unittest.main()
