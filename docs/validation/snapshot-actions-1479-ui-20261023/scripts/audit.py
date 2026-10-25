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


import hashlib,json,os,shlex,subprocess,sys
from pathlib import Path
cluster,phase=sys.argv[1:3]
assert cluster in ['31','32']
root=Path('/home/ablecloud/work/snapshot-actions-1479-ui-20261023')
reg=json.loads((root/(cluster+'-fixtures.json')).read_text())
def remote(args,host=False):
    address=('root@10.10.'+cluster+'.1') if host else ('ablecloud@10.10.'+cluster+'.10')
    cmd=['sshpass','-e','ssh','-n','-p','22','-o','StrictHostKeyChecking=yes']
    if host and cluster=='32':cmd+=['-o','UserKnownHostsFile='+'/home/ablecloud/work/snapshot-archive-1477-20261023/trusted-hosts32']
    return subprocess.check_output(cmd+[address,shlex.join(args)],env={**os.environ,'SSHPASS':os.environ['HOST_SECRET' if host else 'SSH_SECRET']},text=True).strip()
def sql(query):
    text=remote(['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',query])
    return [json.loads(x) for x in text.splitlines()]
prefix='SNAP1479UI-'+cluster+'-%'
rows=sql("SELECT JSON_OBJECT('id',s.id,'uuid',s.uuid,'name',s.name,'state',s.status,'removed',s.removed,'location',s.location_type,'referenceid',r.id,'role',r.store_role,'storeid',r.store_id,'refstate',r.state,'path',r.install_path,'physical',r.physical_size,'display',CAST(r.display AS UNSIGNED)) FROM snapshots s LEFT JOIN snapshot_store_ref r ON r.snapshot_id=s.id WHERE s.name LIKE '"+prefix+"' ORDER BY s.id,r.id;")
volumes=sql("SELECT JSON_OBJECT('id',id,'uuid',uuid,'name',name,'state',state,'removed',removed,'path',path,'poolid',pool_id,'instanceid',instance_id) FROM volumes WHERE name LIKE '"+prefix+"' ORDER BY id;")
counts=sql("SELECT JSON_OBJECT('owner',a.account_name,'type',r.type,'count',r.count) FROM resource_count r JOIN account a ON a.id=r.account_id WHERE (a.id=2 OR a.account_name LIKE 'snap1479ui-"+cluster+"-%') AND r.type IN ('snapshot','secondary_storage');")
details=sql("SELECT JSON_OBJECT('snapshot',s.uuid,'key',d.name,'value',d.value,'display',d.display) FROM snapshot_details d JOIN snapshots s ON s.id=d.snapshot_id WHERE s.name LIKE '"+prefix+"' AND d.name LIKE 'snapshot.force-delete.%';")
events=sql("SELECT JSON_OBJECT('snapshot',s.uuid,'name',s.name,'type',e.type,'count',COUNT(*)) FROM usage_event e JOIN snapshots s ON s.id=e.resource_id WHERE s.name LIKE '"+prefix+"' AND e.type IN ('SNAPSHOT.CREATE','SNAPSHOT.ON_PRIMARY','SNAPSHOT.DELETE') GROUP BY s.uuid,s.name,e.type ORDER BY s.name,e.type;")
expected=sql("SELECT JSON_OBJECT('owner',a.account_name,'snapshot',(SELECT COUNT(*) FROM snapshots s WHERE s.account_id=a.id AND s.removed IS NULL AND s.status NOT IN ('Error','Destroyed') AND s.snapshot_type<>7),'secondary_storage',((SELECT COALESCE(SUM(v.size),0) FROM volumes v WHERE v.account_id=a.id AND v.path IS NULL AND v.state<>'Allocated' AND v.removed IS NULL)+(SELECT COALESCE(SUM(r.physical_size),0) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.account_id=a.id AND s.removed IS NULL AND r.state='Ready' AND r.store_role='Image')+(SELECT COALESCE(SUM(r.size),0) FROM template_store_ref r JOIN vm_template t ON t.id=r.template_id WHERE t.account_id=a.id AND t.removed IS NULL AND r.download_state IN ('DOWNLOADED','DOWNLOAD_IN_PROGRESS') AND r.destroyed=0))) FROM account a WHERE a.id=2 OR a.account_name LIKE 'snap1479ui-"+cluster+"-%';")
(root/(cluster+'-'+phase+'-db.json')).write_text(json.dumps({'snapshots':rows,'volumes':volumes,'counts':counts,'forcedCleanupAudit':details,'usageEvents':events,'expectedCounts':expected},indent=2)+'\n')
vm=sql("SELECT JSON_OBJECT('id',id,'uuid',uuid,'name',display_name,'instance',instance_name,'state',state,'hostid',host_id,'removed',removed) FROM vm_instance WHERE display_name LIKE '"+prefix+"';")
(root/(cluster+'-'+phase+'-vm-db.json')).write_text(json.dumps(vm,indent=2)+'\n')
templates=sql("SELECT JSON_OBJECT('id',t.id,'uuid',t.uuid,'name',t.name,'removed',t.removed,'path',r.install_path,'state',r.download_state,'destroyed',r.destroyed) FROM vm_template t LEFT JOIN template_store_ref r ON r.template_id=t.id WHERE t.name LIKE '"+prefix+"';")
(root/(cluster+'-'+phase+'-templates-db.json')).write_text(json.dumps(templates,indent=2)+'\n')
extra=sql("SELECT JSON_OBJECT('id',v.id,'uuid',v.uuid,'name',v.name,'state',v.state,'removed',v.removed,'path',v.path,'poolid',v.pool_id,'instanceid',v.instance_id) FROM volumes v JOIN vm_instance m ON m.id=v.instance_id WHERE m.display_name LIKE '"+prefix+"';")
for v in extra:
 if not any(x['id']==v['uuid'] for x in reg['volumes']):reg['volumes'].append({'id':v['uuid'],'name':v['name'],'scenario':'vm-root','virtualmachineid':reg['vm']['id']})
for v in volumes:
 if not any(x['id']==v['uuid'] for x in reg['volumes']):reg['volumes'].append({'id':v['uuid'],'name':v['name'],'scenario':'discovered-db'})
(root/(cluster+'-fixtures.json')).write_text(json.dumps(reg,indent=2)+'\n')
(root/(cluster+'-'+phase+'-vm-volumes-db.json')).write_text(json.dumps(extra,indent=2)+'\n')
known_file=root/(cluster+'-known-copies.json')
known=json.loads(known_file.read_text()) if known_file.exists() else []
identity={(x['uuid'],x['role'],x['path']) for x in known}
for row in rows:
    if row['path'] and (row['uuid'],row['role'],row['path']) not in identity:
        known.append({k:row[k] for k in ['uuid','name','role','path']})
known_file.write_text(json.dumps(known,indent=2)+'\n')
paths=sorted(set('/nfs/secondary/snapshots/'+x['path'].split('/snapshots/',1)[-1] if '/snapshots/' in x['path'] else '/nfs/secondary/'+x['path'] for x in known if x['role']=='Image' and len(x['path'].split('/'))>=4))
program="import sys,json,hashlib,pathlib; out=[]\nfor x in sys.argv[1:]:\n p=pathlib.Path(x);out.append({'path':x,'exists':p.is_file(),'bytes':p.stat().st_size if p.is_file() else None,'sha256':hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else None})\nprint(json.dumps(out))"
physical={'images':json.loads(remote(['sudo','-n','python3','-c',program]+paths))}
physical['templates']=[]
for t in templates:
 if t['removed'] is None and t['path'] and t['path'].startswith('template/'):
  path='/nfs/secondary/'+t['path']
  out=remote(['sudo','-n','timeout','30','qemu-io','-r','-f','qcow2','-c','read -P 0x5a 0 1M',path])
  physical['templates'].append({'uuid':t['uuid'],'path':path,'pattern':'0x5a','verified':True,'output':out})
primary_file=root/(cluster+'-known-primary-objects.json')
primary=json.loads(primary_file.read_text()) if primary_file.exists() else []
for v in volumes+extra:
 if v['path'] and not any(x['uuid']==v['uuid'] and x['path']==v['path'] for x in primary):primary.append({'uuid':v['uuid'],'name':v['name'],'path':v['path']})
for v in reg['volumes']:
 if v.get('path') and not any(x['uuid']==v['id'] and x['path']==v['path'] for x in primary):primary.append({'uuid':v['id'],'name':v['name'],'path':v['path']})
# Include pre-expunge root paths whose DB association/path can disappear after removal.
oldroots=root/(cluster+'-pre-cleanup-vm-volumes-db.json')
if oldroots.exists():
 for v in json.loads(oldroots.read_text()):
  if v['path'] and not any(x['uuid']==v['uuid'] and x['path']==v['path'] for x in primary):primary.append({'uuid':v['uuid'],'name':v['name'],'path':v['path']})
primary_file.write_text(json.dumps(primary,indent=2)+'\n')
object_names=sorted(set([v['id'] for v in reg['volumes']]+[Path(v['path']).name for v in primary]))
if cluster=='32':
    images=json.loads(remote(['rbd','ls','rbd','--format','json'],True));physical['sourceVolumes']=[]
    for name in object_names:
        item={'image':name,'exists':name in images}
        item['snapshots']=json.loads(remote(['rbd','snap','ls','rbd/'+name,'--format','json'],True)) if item['exists'] else []
        physical['sourceVolumes'].append(item)
    physical['fixtureClones']=[x for x in images if x.startswith('SNAP1479UI-')]
else:
    names=[]
    for name in object_names:
        if names:names.append('-o')
        names.extend(['-name',name])
    physical['sourceVolumePaths']=remote(['find','/mnt/glue-gfs','-maxdepth','3','(']+names+[')','-print'],True).splitlines()
(root/(cluster+'-'+phase+'-physical.json')).write_text(json.dumps(physical,indent=2)+'\n')
print(cluster,phase,'snapshots/refs',len(rows),'known files',len(paths),'forced audit',len(details))
