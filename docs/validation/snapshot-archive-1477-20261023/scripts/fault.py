# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# Only disposable SNAP1477 fixtures may be altered by this helper.
import json,os,shlex,subprocess,sys,uuid
from pathlib import Path,PurePosixPath
cluster,scenario,kind=sys.argv[1:4]
assert cluster in ['31','32']
root=Path('/home/ablecloud/work/snapshot-archive-1477-20261023')
reg=json.loads((root/(cluster+'-fixtures.json')).read_text())
snap=next(x for x in reg['snapshots'] if x['scenario']==scenario)
assert snap['name'].startswith('SNAP1477-'+cluster+'-')
uid=str(uuid.UUID(snap['id']))
def remote(args,host=False):
    cmd=['sshpass','-e','ssh','-n','-p','22','-o','StrictHostKeyChecking=yes']
    if host and cluster=='32':cmd+=['-o','UserKnownHostsFile='+str(root/'trusted-hosts32')]
    return subprocess.check_output(cmd+[(('root@10.10.'+cluster+'.1') if host else ('ablecloud@10.10.'+cluster+'.10')),shlex.join(args)],env={**os.environ,'SSHPASS':os.environ['HOST_SECRET' if host else 'SSH_SECRET']},text=True).strip()
def sql(query):return remote(['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',query])
rows=[json.loads(x) for x in sql("SELECT JSON_OBJECT('snapshotid',s.id,'state',s.status,'referenceid',r.id,'role',r.store_role,'refstate',r.state,'path',r.install_path,'volumeid',s.volume_id) FROM snapshots s LEFT JOIN snapshot_store_ref r ON r.snapshot_id=s.id WHERE s.uuid='"+uid+"' AND s.name LIKE 'SNAP1477-"+cluster+"-%' ORDER BY r.id;").splitlines()]
assert rows and rows[0]['snapshotid']
sid=int(rows[0]['snapshotid'])
output=root/(cluster+'-'+scenario+'-'+kind+'.json')
assert not output.exists(), 'Fault step has already run; inspect its evidence before repeating'
record={'snapshot':uid,'scenario':scenario,'kind':kind,'before':rows,'fixtureOnly':True}
def image_path(row):
    rel=PurePosixPath(row['path']);assert row['role']=='Image' and len(rel.parts)==4 and rel.parts[-2]==str(row['volumeid']) and '..' not in rel.parts
    uuid.UUID(rel.name)
    return '/nfs/secondary/'+str(rel)
def native(row):
    pool,image,name=row['path'].split('/');assert pool=='rbd' and image==reg['volumes'][0]['id'];uuid.UUID(name)
    return pool+'/'+image,name
if kind in ['hide-image','primary-only']:
    image=next(x for x in rows if x['role']=='Image' and x['refstate']=='Ready')
    original=image_path(image);backup='/home/ablecloud/snapshot-1477-faults/'+uid+'.image'
    remote(['sudo','-n','test','-f',original]);record['sha256']=remote(['sudo','-n','sha256sum',original]).split()[0]
    remote(['mkdir','-p','/home/ablecloud/snapshot-1477-faults']);remote(['sudo','-n','test','!','-e',backup]);remote(['sudo','-n','mv','--',original,backup])
    record.update({'path':original,'backup':backup,'referenceid':image['referenceid']})
    if kind=='primary-only':
        assert cluster=='32' and any(x['role']=='Primary' and x['refstate']=='Ready' for x in rows)
        sql("UPDATE snapshot_store_ref SET state='Destroyed',display=0 WHERE id="+str(image['referenceid'])+' AND snapshot_id='+str(sid)+';')
elif kind=='restore-image':
    old=json.loads((root/(cluster+'-'+scenario+'-hide-image.json')).read_text())
    remote(['sudo','-n','test','!','-e',old['path']]);remote(['sudo','-n','mv','--',old['backup'],old['path']])
    assert remote(['sudo','-n','sha256sum',old['path']]).split()[0]==old['sha256']
elif kind in ['backup-block','release-backup']:
    assert cluster=='32'
    old=json.loads((root/(cluster+'-'+scenario+'-primary-only.json')).read_text());directory=str(PurePosixPath(old['path']).parent)
    assert directory.startswith('/nfs/secondary/snapshots/') and directory.endswith('/'+str(rows[0]['volumeid']))
    remote(['sudo','-n','chattr','+i' if kind=='backup-block' else '-i','--',directory]);record['directory']=directory
elif kind in ['missing-path','restore-path']:
    if kind=='missing-path':
        target=next(x for x in rows if x['role']==('Image' if cluster=='31' else 'Primary') and x['refstate']=='Ready')
        assert target['path'];record.update({'referenceid':target['referenceid'],'path':target['path']})
        sql('UPDATE snapshot_store_ref SET install_path=NULL WHERE id='+str(target['referenceid'])+' AND snapshot_id='+str(sid)+';')
    else:
        old=json.loads((root/(cluster+'-'+scenario+'-missing-path.json')).read_text());path=old['path'];assert "'" not in path and '..' not in PurePosixPath(path).parts
        sql("UPDATE snapshot_store_ref SET install_path='"+path+"' WHERE id="+str(old['referenceid'])+' AND snapshot_id='+str(sid)+';')
        record['restoredAddressOnly']=True # Never restore lifecycle/global states after product cleanup.
elif kind in ['busy','release-busy']:
    if kind=='busy':
        if cluster=='31':
            row=next(x for x in rows if x['role']=='Image' and x['refstate']=='Ready');path=image_path(row)
            remote(['sudo','-n','chattr','+i','--',path]);record['path']=path
        else:
            row=next(x for x in rows if x['role']=='Primary' and x['refstate']=='Ready');image,name=native(row);child='rbd/SNAP1477-32-'+scenario+'-child'
            listing=json.loads(remote(['rbd','snap','ls',image,'--format','json'],True));assert any(x['name']==name and x['protected']=='false' for x in listing)
            remote(['rbd','snap','protect',image+'@'+name],True);remote(['rbd','clone',image+'@'+name,child],True)
            record.update({'native':image+'@'+name,'child':child})
    else:
        old=json.loads((root/(cluster+'-'+scenario+'-busy.json')).read_text())
        if cluster=='31':remote(['sudo','-n','chattr','-i','--',old['path']])
        else:
            assert old['child'].startswith('rbd/SNAP1477-32-');remote(['rbd','rm','--no-progress',old['child']],True)
elif kind in ['secondary-missing','secondary-unmapped']:
    assert cluster=='32'
    primary=next(x for x in rows if x['role']=='Primary' and x['refstate']=='Ready');image,name=native(primary)
    before=json.loads(remote(['rbd','snap','ls',image,'--format','json'],True));assert any(x['name']==name for x in before)
    secondary=next(x for x in rows if x['role']=='Image' and x['refstate']=='Ready');path=image_path(secondary)
    remote(['sudo','-n','test','-f',path]);remote(['sudo','-n','rm','--',path]);remote(['sudo','-n','test','!','-e',path])
    if kind=='secondary-unmapped':sql('DELETE FROM snapshot_store_ref WHERE id='+str(secondary['referenceid'])+' AND snapshot_id='+str(sid)+" AND store_role='Image';")
    after=json.loads(remote(['rbd','snap','ls',image,'--format','json'],True));assert before==after
    record.update({'secondaryAbsent':True,'primaryPhysicalPresent':True,'native':image+'@'+name,'nativeSnapshotsUnchanged':True})
elif kind in ['all-physical-missing','unmapped']:
    for row in rows:
        if row['refstate']!='Ready':continue
        if row['role']=='Image':
            path=image_path(row);remote(['sudo','-n','test','-f',path]);remote(['sudo','-n','rm','--',path]);remote(['sudo','-n','test','!','-e',path])
        elif row['role']=='Primary':
            assert cluster=='32';image,name=native(row)
            before=json.loads(remote(['rbd','snap','ls',image,'--format','json'],True));assert any(x['name']==name and x['protected']=='false' for x in before)
            remote(['rbd','snap','rm',image+'@'+name],True)
            after=json.loads(remote(['rbd','snap','ls',image,'--format','json'],True));assert [x for x in before if x['name']!=name]==after
    if kind=='unmapped':sql('DELETE FROM snapshot_store_ref WHERE snapshot_id='+str(sid)+';')
    assert sql('SELECT status FROM snapshots WHERE id='+str(sid)+';')==rows[0]['state']
elif kind=='cleanup-backup':
    old=json.loads((root/(cluster+'-'+scenario+'-primary-only.json')).read_text())
    assert old['backup']=='/home/ablecloud/snapshot-1477-faults/'+uid+'.image'
    assert sql('SELECT status FROM snapshots WHERE id='+str(sid)+';')=='Destroyed'
    remote(['sudo','-n','test','!','-e',old['path']]);remote(['sudo','-n','rm','--',old['backup']])
else:raise ValueError(kind)
output.write_text(json.dumps(record,indent=2)+'\n');print(cluster,scenario,kind,'fixture step complete')
