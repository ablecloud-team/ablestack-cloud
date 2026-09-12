# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Execute production NAS functions with a disposable command boundary fixture."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path("scripts/vm/hypervisor/kvm/nasbackup.sh").resolve()
STUB = r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(os.environ['NAS_FIXTURE']);case=os.environ.get('NAS_CASE','')
name=Path(sys.argv[0]).name;a=sys.argv[1:]
with (root/'calls.jsonl').open('a') as f:f.write(json.dumps([name]+a)+'\n')
if name=='udevadm':
 if case!='unresolved':print('drbd/by-res/cs-legacy-data/0')
elif name=='virsh':
 if 'domblklist' in a:print('file disk vda /images/root-file\nblock disk vdb /dev/drbd1098')
 elif 'qemu-monitor-command' in a:
  if 'query-block' in a[-1]:print(json.dumps({'return':[{'inserted':{'file':p,'node-name':str(i),'dirty-bitmaps':[] if case=='missing-bitmap' else [{'name':'parent'}]}} for i,p in enumerate(['/images/root-file','/dev/drbd1098'])]}))
 elif 'checkpoint-list' in a:print('parent')
 elif 'backup-begin' in a:
  (root/'backup.xml').write_text((root/'mount/new/backup.xml').read_text())
  if case=='begin-failure':sys.exit(1)
  for t in ET.parse(root/'backup.xml').findall('.//target'):Path(t.attrib['file']).write_bytes(b'backup')
 elif 'domjobinfo' in a:print('Job type: Completed')
 elif 'domstate' in a:print('paused')
elif name=='qemu-img':
 if a[0]=='rebase' and case=='rebase-failure':sys.exit(1)
 if a[0]=='convert':Path(a[-1]).write_bytes(b'converted')
'''

class NasFixture(unittest.TestCase):
    def run_backup(self, mode='incremental', case='', uuids='root-id,data-id', stopped=False):
        temp = tempfile.TemporaryDirectory(prefix='epic994-nas-')
        self.addCleanup(temp.cleanup)
        root=Path(temp.name)
        (root/'bin').mkdir()
        stub=root/'bin/stub';stub.write_text(STUB);stub.chmod(0o755)
        for name in ('virsh','qemu-img','udevadm'):(root/'bin'/name).symlink_to(stub)
        (root/'mount/old').mkdir(parents=True)
        (root/'mount/old/root.qcow2').touch()
        if case!='missing-parent':(root/'mount/old/data.qcow2').touch()
        source=SCRIPT.read_text().split('while [[ $# -gt 0 ]]; do',1)[0]
        env=dict(os.environ,NAS_FIXTURE=str(root),NAS_CASE=case,PATH=str(root/'bin')+':'+os.environ['PATH'])
        driver=source+r'''
logFile="$NAS_FIXTURE/agent.log"
verb=0
VM=fixture-vm
MODE='''+mode+r'''
BITMAP_NEW=new
BITMAP_PARENT=parent
PARENT_PATHS=old/root.qcow2,old/data.qcow2
VOLUME_UUIDS='''+uuids+r'''
DISK_PATHS=/images/root-file,/dev/drbd1098
QUIESCE=true
mount_operation() { mount_point="$NAS_FIXTURE/mount"; dest="$mount_point/new"; }
umount() { return 0; }
rmdir() { return 0; }
timeout() { return 0; }
sync() { return 0; }
'''+('backup_stopped_vm' if stopped else 'backup_running_vm')
        result=subprocess.run(['bash','-c',driver],env=env,capture_output=True,text=True,timeout=15)
        calls=[json.loads(l) for l in (root/'calls.jsonl').read_text().splitlines()]
        return result,calls,root

    def test_incremental_uses_each_volume_parent(self):
        r,c,p=self.run_backup();self.assertEqual(r.returncode,0,r.stderr+r.stdout)
        rebases=[a for a in c if a[:2]==['qemu-img','rebase']]
        self.assertEqual(len(rebases),2)
        self.assertIn('../old/root.qcow2',rebases[0]);self.assertTrue(rebases[0][-1].endswith('/root.root-id.qcow2'))
        self.assertIn('../old/data.qcow2',rebases[1]);self.assertTrue(rebases[1][-1].endswith('/datadisk.data-id.qcow2'))

    def test_missing_parent_fails_and_resumes(self):
        r,c,p=self.run_backup(case='missing-parent');self.assertNotEqual(r.returncode,0)
        self.assertTrue(any('resume' in a for a in c));self.assertFalse((p/'mount/new').exists())

    def test_rebase_failure_fails_and_resumes(self):
        r,c,p=self.run_backup(case='rebase-failure');self.assertNotEqual(r.returncode,0)
        self.assertTrue(any('resume' in a for a in c))

    def test_full_linstor_preserves_data_disk_name_after_skipped_file_root(self):
        r,c,p=self.run_backup(mode='full');self.assertEqual(r.returncode,0,r.stderr+r.stdout)
        convert=[a for a in c if a[:2]==['qemu-img','convert']];self.assertEqual(len(convert),1)
        self.assertTrue(convert[0][-2].endswith('/datadisk.data-id.qcow2'))

    def test_legacy_linstor_uuid_resolved_for_xml_and_convert(self):
        r,c,p=self.run_backup(mode='full',uuids='');self.assertEqual(r.returncode,0,r.stderr+r.stdout)
        self.assertIn('datadisk.legacy-data.qcow2',(p/'backup.xml').read_text())
        self.assertTrue(any(a[0]=='qemu-img' and a[-2].endswith('/datadisk.legacy-data.qcow2') for a in c))

    def test_unresolved_linstor_fails_without_starting_backup(self):
        r,c,p=self.run_backup(mode='full',case='unresolved',uuids='');self.assertNotEqual(r.returncode,0)
        self.assertFalse(any('backup-begin' in a for a in c))

    def test_stopped_raw_drbd_does_not_receive_qcow_bitmap(self):
        r,c,p=self.run_backup(mode='full',stopped=True);self.assertEqual(r.returncode,0,r.stderr+r.stdout)
        bitmap=[a for a in c if a[:2]==['qemu-img','bitmap']];self.assertEqual(len(bitmap),1)
        self.assertIn('/images/root-file',bitmap[0]);self.assertNotIn('/dev/drbd1098',bitmap[0])

    def test_begin_failure_thaws_and_resumes(self):
        r,c,p=self.run_backup(case='begin-failure');self.assertNotEqual(r.returncode,0)
        self.assertTrue(any('guest-fsfreeze-thaw' in a[-1] for a in c));self.assertTrue(any('resume' in a for a in c))

    def test_partial_bitmap_falls_back_to_full(self):
        r,c,p=self.run_backup(case='missing-bitmap');self.assertEqual(r.returncode,0,r.stderr+r.stdout)
        self.assertIn('INCREMENTAL_FALLBACK=true',r.stdout)
        self.assertNotIn('<incremental>',(p/'backup.xml').read_text())
        self.assertFalse(any(a[:2]==['qemu-img','rebase'] for a in c))

if __name__=='__main__':unittest.main()
