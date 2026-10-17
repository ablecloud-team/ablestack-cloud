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

import ast,ctypes,errno,importlib.util,os,re,stat,tempfile,unittest,uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SOURCE=Path(__file__).resolve().parents[2]/'systemvm/debian/usr/local/lib/ablestack-storage/new_directory.py'
spec=importlib.util.spec_from_file_location('new_directory',SOURCE);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class StorageNewDirectoryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.parent=self.root/'parent';self.parent.mkdir();self.stage=self.root/'stage';self.stage.mkdir(mode=0o700)
        self.parentfd=os.open(self.parent,os.O_RDONLY|os.O_DIRECTORY);self.stagefd=os.open(self.stage,os.O_RDONLY|os.O_DIRECTORY)
        self.addCleanup(os.close,self.parentfd);self.addCleanup(os.close,self.stagefd)

    def publish(self):return m.publish_new_directory(self.parentfd,self.stagefd,'new',os.geteuid(),os.getegid(),0o775)

    def test_only_new_inode_is_initialized_before_publication(self):
        result=self.publish();self.assertTrue(result['created']);self.assertEqual((self.parent/'new').stat().st_ino,result['inode'])
        self.assertEqual('0775',result['effectiveMode']);self.assertEqual([],list(self.stage.iterdir()))

    def test_existing_directory_owner_mode_and_children_are_never_changed(self):
        target=self.parent/'new';target.mkdir(mode=0o750);child=target/'existing';child.write_text('unchanged');child.chmod(0o640)
        before=target.stat();data=child.stat();self.assertIsNone(self.publish())
        self.assertEqual(before,target.stat());self.assertEqual(data,child.stat());self.assertEqual('unchanged',child.read_text())

    def test_concurrent_creation_winner_is_preserved_without_replacement_or_chown(self):
        winner={}
        def conflict(sourcefd,source,targetfd,name,flags):
            target=self.parent/'new';target.mkdir(mode=0o700);winner['stat']=target.stat();ctypes.set_errno(errno.EEXIST);return -1
        with patch.object(m.ctypes,'CDLL',return_value=SimpleNamespace(renameat2=conflict)):
            self.assertIsNone(self.publish())
        self.assertEqual(winner['stat'],(self.parent/'new').stat());self.assertEqual([],list(self.stage.iterdir()))

    def test_postpublication_replacement_is_rejected_without_touching_its_metadata(self):
        real=ctypes.CDLL(None,use_errno=True).renameat2;replacement={}
        def swap(*args):
            result=real(*args)
            target=self.parent/'new';target.rename(self.parent/'published-original');target.mkdir(mode=0o700);replacement['stat']=target.stat();return result
        with patch.object(m.ctypes,'CDLL',return_value=SimpleNamespace(renameat2=swap)):
            with self.assertRaises(ValueError):self.publish()
        self.assertEqual(replacement['stat'],(self.parent/'new').stat())
        self.assertEqual(0o775,stat.S_IMODE((self.parent/'published-original').stat().st_mode))

    def test_unprotected_stage_and_symlink_existing_target_fail_closed(self):
        self.stage.chmod(0o777)
        with self.assertRaises(ValueError):self.publish()
        self.stage.chmod(0o700);(self.parent/'new').symlink_to(self.stage,target_is_directory=True)
        with self.assertRaises(OSError):self.publish()

class StorageVolumeNewLeafInitializationTest(unittest.TestCase):
    def test_actual_volume_preparation_initializes_only_a_new_leaf_and_persists_inode_provenance(self):
        cli=SOURCE.parents[2]/'bin/ablestack-storagectl'
        block=next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY",cli.read_text(),re.S) if 'def resolve_backing_path(' in value and 'created_directory_receipts' in value)
        function=next(node for node in ast.parse(block).body if isinstance(node,ast.FunctionDef) and node.name=='resolve_backing_path')
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);config={'newDirectoryPermissions':{'ownerUid':os.geteuid(),'ownerGid':os.getegid(),'mode':'0775'}}
            namespace={'os':os,'re':re,'config':config,'fs_uuid':str(uuid.uuid4()),'volume_key':str(uuid.uuid4()),
                       'created_directory_receipts':[],'publish_new_directory':m.publish_new_directory,
                       'run':lambda args:SimpleNamespace(returncode=0,stdout=str(root))}
            exec(compile(ast.Module(body=[function],type_ignores=[]),str(cli),'exec'),namespace)
            path=namespace['resolve_backing_path'](str(root),'nfs/new',True)
            self.assertEqual(0o755,stat.S_IMODE((root/'nfs').stat().st_mode))
            self.assertEqual(0o775,stat.S_IMODE(Path(path).stat().st_mode))
            self.assertEqual(Path(path).stat().st_ino,namespace['created_directory_receipts'][-1]['inode'])
            existing=Path(path)/'user-data';existing.write_text('unchanged');existing.chmod(0o640)
            before=Path(path).stat();child=existing.stat();count=len(namespace['created_directory_receipts'])
            namespace['config']['newDirectoryPermissions']['mode']='0777'
            namespace['resolve_backing_path'](str(root),'nfs/new',True)
            self.assertEqual(before,Path(path).stat());self.assertEqual(child,existing.stat());self.assertEqual(count,len(namespace['created_directory_receipts']))

if __name__=='__main__':unittest.main()
