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

import json
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[2]
LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from rendered_driver import RenderedDriver
from rendered_generation import RenderedGeneration


class StorageRenderedDriverTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.cli=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"
        self.environment=dict(os.environ,ABLESTACK_STORAGE_RENDERED_GENERATIONS=str(self.root/"render"),
                              ABLESTACK_STORAGE_GENERATION_DIR=str(self.root/"generation"),
                              ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(self.root/"config"),
                              ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR=str(self.root/"maintenance"),
                              ABLESTACK_STORAGE_WRITER_LOCK_FILE=str(self.root/"lock"/"writer.lock"))

    def test_embedded_status_is_readonly_and_runs_without_new_external_helpers_on_legacy_template(self):
        result=subprocess.run([self.cli,"operation","generation","render-status"],capture_output=True,text=True,env=self.environment,timeout=10)
        self.assertEqual(0,result.returncode,result.stderr)
        value=json.loads(result.stdout);self.assertTrue(value['success']);self.assertFalse(value['bootHeld']);self.assertIsNone(value['current'])
        self.assertEqual([],list(self.root.iterdir()))

    def test_embedded_boot_gate_is_readonly_with_no_existing_generation_or_maintenance_marker(self):
        result=subprocess.run([self.cli,"operation","generation","render-boot-gate","smbd.service"],capture_output=True,text=True,env=self.environment,timeout=10)
        self.assertEqual(0,result.returncode,result.stderr);self.assertTrue(json.loads(result.stdout)['success'])
        self.assertEqual([],list(self.root.iterdir()))

    def test_pending_native_writer_blocks_boot_before_any_protocol_or_mount_action(self):
        store=RenderedGeneration(self.root/"render");driver=RenderedDriver(self.cli,store)
        store.status=lambda:{"success":True,"bootHeld":False,"current":{"scope":{}},"activation":None}
        driver.runtime.command=lambda args:({'success':True,'bootHeld':False,'scope':None} if args[1]=='maintenance' else {'pendingOperationUuid':'pending'})
        with self.assertRaisesRegex(ValueError,'pending native'):driver.execute('render-boot-gate',unit='smbd.service')
        self.assertEqual([],list(self.root.iterdir()))

    def test_rpc_does_not_accept_rendered_bytes_arbitrary_paths_or_executable_callbacks(self):
        request=self.root/'request.json';request.write_text(json.dumps({'instanceUuid':'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','operationUuid':'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb','revision':1,'callback':'/tmp/foreign-program'}))
        result=subprocess.run([self.cli,'operation','generation','render-stage',str(request)],capture_output=True,text=True,env=self.environment,timeout=10)
        self.assertNotEqual(0,result.returncode);self.assertIn('unknown input field',json.loads(result.stdout)['reason'])
        self.assertFalse((self.root/'render').exists())

    def test_nfs_only_rollback_preserves_all_unaffected_identity_material_without_import(self):
        store=RenderedGeneration(self.root/'render');driver=RenderedDriver(self.cli,store)
        store.scoped_activation=lambda request:{'changedDomains':['NFS','SMB'],'startedDomains':['NFS']}
        driver.runtime.command=lambda *args,**kwargs:(_ for _ in ()).throw(AssertionError('identity import/read was attempted'))
        driver.recovery_key=lambda request:(_ for _ in ()).throw(AssertionError('unaffected identity decrypt was attempted'))
        result=driver.restore_identity({})
        self.assertTrue(result['unaffectedIdentityPreserved']);self.assertFalse(result['identityRestored'])

    def test_legacy_nfs_import_retains_exact_source_bytes_and_rejects_policy_or_path_difference(self):
        driver=RenderedDriver(self.cli,RenderedGeneration(self.root/'render'))
        driver.nfs_config_root=self.root/'configs';driver.nfs_config_root.mkdir()
        config='NFS_Core_Param {\n NFS_Port = 2049;\n Protocols = 4;\n}\nEXPORT {\n Export_Id = 1001;\n Path = "/export/share";\n Pseudo = "/share";\n Access_Type = RO;\n FSAL {\n Name = VFS;\n }\n}\n'
        actual=driver.nfs_config_root/'known.conf';actual.write_text(config);actual.chmod(0o600)
        generated=config.replace(' Protocols',' Dbus_Name_Prefix = "org.ablestack.storage.ganesha.e'+'a'*24+'";\n Protocols')
        files={'nfs/manifest.json':json.dumps({'endpoints':[{'legacyUnitKey':'known','configurationPath':'nfs/ganesha/generated.conf'}]}),'nfs/ganesha/generated.conf':generated}
        self.assertEqual(config,driver.retain_legacy_nfs_baseline(dict(files))['nfs/ganesha/generated.conf'])
        for foreign in (config.replace('RO','RW'),config.replace('/export/share','/export/foreign')):
            actual.write_text(foreign)
            with self.assertRaisesRegex(ValueError,'source bytes differ'):driver.retain_legacy_nfs_baseline(dict(files))

    def test_native_pending_writer_blocks_legacy_boot_even_before_first_rendered_baseline(self):
        store=RenderedGeneration(self.root/'render');driver=RenderedDriver(self.cli,store)
        store.status=lambda:{'success':True,'bootHeld':False,'current':None,'activation':None}
        driver.runtime.command=lambda args:({'success':True,'bootHeld':False,'scope':None} if args[1]=='maintenance' else {'pendingOperationUuid':'legacy-pending'})
        with self.assertRaisesRegex(ValueError,'pending native'):driver.execute('render-boot-gate',unit='smbd.service')
        self.assertEqual([],list(self.root.iterdir()))

    def test_file_stage_requires_fresh_exact_serial_size_filesystem_and_mount_with_stable_device_independence(self):
        driver=RenderedDriver(self.cli,RenderedGeneration(self.root/'render'))
        volume=str(uuid.uuid4());filesystem=str(uuid.uuid4());mount='/srv/ablestack-storage/volumes/'+volume
        request={'instanceUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'revision':2,'fileVolumeBindings':[{'volumeUuid':volume,'sizeBytes':1024,'filesystemUuid':filesystem}]}
        files={'nfs/manifest.json':json.dumps({'aliases':{'export':{'volumeMountPath':mount,'backingPath':mount+'/share'}}}),
               'smb/manifest.json':json.dumps({'shares':[{'path':mount+'/share'}]})}
        good={'mappingStatus':'EXACT','matchedBy':'VOLUME_SERIAL','sizeBytes':1024,'filesystemUuid':filesystem,'observedDevicePath':'/dev/sdb','mounts':[{'target':mount}]}
        driver.runtime.command=lambda *args,**kwargs:{'volumes':[good]}
        verified=driver.file_bindings(request,files)
        self.assertNotIn('observedDevicePath',json.dumps(verified));self.assertEqual(mount,verified['volumes'][0]['mountPath'])
        for change in ({'matchedBy':'FILESYSTEM_UUID'},{'filesystemUuid':str(uuid.uuid4())},{'sizeBytes':2048},{'mounts':[]}):
            driver.runtime.command=lambda *args,**kwargs:{'volumes':[{**good,**change}]}
            with self.assertRaises(ValueError):driver.file_bindings(request,files)
        with self.assertRaises(ValueError):driver.file_bindings({**request,'fileVolumeBindings':[]},files)

    def test_quarantined_new_root_import_keeps_proven_bootstrap_network_without_fabricating_native_generation(self):
        from rendered_generation import DESIRED_PATHS,REQUIRED,DOMAINS
        store=RenderedGeneration(self.root/'render');driver=RenderedDriver(self.cli,store)
        scope={'instanceUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'revision':10}
        desired={name:None for name in DESIRED_PATHS};desired['sharedfs-network.json']={'mode':'STATIC','primaryIp':'10.10.13.240'}
        checksum=hashlib.sha256(json.dumps(desired,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        source={'generation':None,'pendingOperationUuid':None,'generationStatus':'UNVERIFIED','configurationDesiredState':desired,'configurationSha256':checksum}
        request={**scope,'initialRootBaseline':True,'configurationDesiredState':desired}
        files={name:'{}' for name in REQUIRED};files['desired-state.json']=json.dumps(desired)
        driver.render=lambda request:dict(files);driver.validate=lambda *args:{name:True for name in DOMAINS};driver.verify=lambda path:{name:True for name in DOMAINS};driver.install_boot_guards=lambda:None;driver.generation=lambda:source
        driver.runtime.command=lambda args:{'success':True,'bootHeld':True,'scope':{**scope,'templateUpgradeUuid':str(uuid.uuid4())}}
        result=driver.execute('render-import',request)
        self.assertEqual(0,result['current']['scope']['revision']);self.assertFalse(result['bootHeld']);self.assertEqual(checksum,result['current']['configurationSha256'])
        self.assertIsNone(source['generation']);self.assertEqual(desired,json.loads((store.pointer()/'desired-state.json').read_text()))
        self.assertIn('initialRootScope',result['activation'])

if __name__=='__main__':unittest.main()
