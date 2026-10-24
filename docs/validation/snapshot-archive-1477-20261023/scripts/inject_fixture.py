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

import datetime,json,os,shlex,subprocess,sys,uuid
from pathlib import Path,PurePosixPath
cluster,scenario,kind=sys.argv[1:4]
assert cluster in ['31','32'] and kind in ['duplicate','partial','recoverystate','missing']
root=Path('/home/ablecloud/work/snapshot-archive-1477-20261023')
output=root/(cluster+'-'+scenario+'-'+kind+'-injection.json')
assert not output.exists(), 'Fixture has already been injected'
reg=json.loads((root/(cluster+'-fixtures.json')).read_text());snap=next(s for s in reg['snapshots'] if s['scenario']==scenario)
def remote(args):
 env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']}
 result=subprocess.run(['sshpass','-e','ssh','-n','-p','22','-o','StrictHostKeyChecking=yes','ablecloud@10.10.'+cluster+'.10',shlex.join(args)],env=env,capture_output=True,text=True)
 if result.returncode: raise RuntimeError('Remote fixture operation failed: '+result.stderr.strip())
 return result.stdout.strip()
def sql(query): return remote(['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','cloud','-e',query])
uid=snap['id'];assert str(uuid.UUID(uid))==uid
rows=sql("SELECT s.id,r.id,r.install_path,s.volume_id FROM snapshots s JOIN snapshot_store_ref r ON r.snapshot_id=s.id WHERE s.uuid='"+uid+"' AND s.name LIKE 'SNAP1477-"+cluster+"-%' AND r.store_role='Image' AND r.state<>'Destroyed' ORDER BY r.id LIMIT 1;").split('\t')
snapshot_id,reference_id,path,volume_id=rows
assert snapshot_id.isdigit() and reference_id.isdigit() and not PurePosixPath(path).is_absolute() and '..' not in PurePosixPath(path).parts
assert PurePosixPath(path).parent.name==volume_id
if kind=='missing':
 counts=sql("SELECT type,count FROM resource_count WHERE account_id=(SELECT account_id FROM snapshots WHERE id="+snapshot_id+") AND type IN ('snapshot','secondary_storage');")
 size=int(sql('SELECT physical_size FROM snapshot_store_ref WHERE id='+reference_id+';'))
 record={'cluster':cluster,'scenario':scenario,'kind':kind,'snapshot':uid,'sourcePath':path,'imagePhysicalSize':size,'countsBefore':dict((k,int(v)) for k,v in (line.split('\t') for line in counts.splitlines()))}
 if cluster=='31':
  remote(['sudo','-n','test','-f','/nfs/secondary/'+path])
  record['sha256Before']=remote(['sudo','-n','sha256sum','/nfs/secondary/'+path]).split()[0]
  remote(['sudo','-n','rm','--','/nfs/secondary/'+path])
  remote(['sudo','-n','test','!','-e','/nfs/secondary/'+path])
  record['fileAbsentAfterInjection']=True
 else:
  native=sql("SELECT install_path FROM snapshot_store_ref WHERE snapshot_id="+snapshot_id+" AND store_role='Primary' AND state<>'Destroyed' LIMIT 1;")
  pool,image,snapname=native.split('/')
  assert pool=='rbd' and image==reg['volumes'][0]['id']
  def host(args):
   env={**os.environ,'SSHPASS':os.environ['HOST_SECRET']}
   return subprocess.check_output(['sshpass','-e','ssh','-n','-p','22','-o','UserKnownHostsFile='+str(root/'trusted-hosts32'),'-o','StrictHostKeyChecking=yes','root@10.10.32.1',shlex.join(args)],env=env,text=True).strip()
  before=json.loads(host(['rbd','snap','ls',pool+'/'+image,'--format','json']))
  assert any(x['name']==snapname and x['protected']=='false' for x in before)
  host(['rbd','snap','rm',pool+'/'+image+'@'+snapname])
  after=json.loads(host(['rbd','snap','ls',pool+'/'+image,'--format','json']))
  assert [x for x in before if x['name']!=snapname]==after
  record.update({'nativePath':native,'nativeAbsentAfterInjection':True,'otherNativeSnapshotsUnchanged':True})
 output.write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record));sys.exit(0)
if kind=='recoverystate':
 sql("UPDATE snapshot_store_ref SET state='Destroying',display=0 WHERE snapshot_id="+snapshot_id+" AND state<>'Destroyed'; UPDATE snapshots SET status='Destroyed' WHERE id="+snapshot_id+" AND uuid='"+uid+"';")
 record={'cluster':cluster,'scenario':scenario,'kind':kind,'snapshot':uid,'fixtureOnly':True,'productRecoveryRequired':True}
 output.write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record));sys.exit(0)
newpath=path
if kind=='partial':
 newpath=str(PurePosixPath(path).parent/str(uuid.uuid4()))
 remote(['sudo','-n','cp','-a','--','/nfs/secondary/'+path,'/nfs/secondary/'+newpath])
columns=sql("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='cloud' AND TABLE_NAME='snapshot_store_ref' AND COLUMN_NAME<>'id' ORDER BY ORDINAL_POSITION;").splitlines()
assert all(c.replace('_','').isalnum() for c in columns)
cols=','.join('`'+c+'`' for c in columns)
newid=sql('INSERT INTO snapshot_store_ref ('+cols+') SELECT '+cols+' FROM snapshot_store_ref WHERE id='+reference_id+' AND snapshot_id='+snapshot_id+'; SELECT LAST_INSERT_ID();').splitlines()[-1]
assert newid.isdigit()
sql("UPDATE snapshot_store_ref SET state='Ready',display=1,install_path='"+newpath+"' WHERE id="+newid+' AND snapshot_id='+snapshot_id+';')
if kind=='partial': remote(['sudo','-n','chattr','+i','--','/nfs/secondary/'+newpath])
record={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'cluster':cluster,'scenario':scenario,'kind':kind,'snapshot':uid,'sourceReference':int(reference_id),'injectedReference':int(newid),'sourcePath':path,'injectedPath':newpath,'immutable':kind=='partial'}
output.write_text(json.dumps(record,indent=2)+'\n');print(json.dumps(record))
