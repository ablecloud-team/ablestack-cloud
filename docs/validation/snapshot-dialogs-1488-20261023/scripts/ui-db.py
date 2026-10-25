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


import json,os,shlex,subprocess,sys,uuid
from pathlib import Path
c=sys.argv[1];assert c in ['31','32'];r=Path('/home/ablecloud/work/snapshot-dialogs-1488-20261023')
rows=[json.loads(x) for x in (r/f'{c}-proxy.jsonl').read_text().splitlines()]
reg=json.loads((r/f'{c}-fixtures.json').read_text());checks=[]
def sql(q):
 cmd=['sshpass','-e','ssh','-n','-o','StrictHostKeyChecking=yes',f'ablecloud@10.10.{c}.10',shlex.join(['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',q])]
 return json.loads(subprocess.check_output(cmd,env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},text=True))
for scenario in ['normal-ui-volume','lost-ui-volume','keyboard-ui-volume']:
 name=f'SNAPDIALOG-{c}-{scenario}'
 posts=[x for x in rows if x.get('mutable') and x.get('cmd')=='createVolume' and x.get('args',{}).get('name')==name]
 original=next(x['id'] for x in reg['snapshots'] if x['scenario']==('orphan-source' if scenario.startswith('lost') else 'source'))
 assert posts and all(x['args']['snapshotid']==original for x in posts)
 ids=set(x['args']['customjobid'] for x in posts);assert len(ids)==1
 jobid=next(iter(ids));uuid.UUID(jobid)
 q=f"SELECT JSON_OBJECT('jobid','{jobid}','transportPosts',{len(posts)},'jobRows',(SELECT COUNT(*) FROM async_job WHERE uuid='{jobid}'),'jobStatus',(SELECT job_status FROM async_job WHERE uuid='{jobid}'),'volumeRows',(SELECT COUNT(*) FROM volumes WHERE name='{name}'),'volume',(SELECT uuid FROM volumes WHERE name='{name}'),'vm',(SELECT m.uuid FROM volumes v LEFT JOIN vm_instance m ON m.id=v.instance_id WHERE v.name='{name}'),'device',(SELECT device_id FROM volumes WHERE name='{name}'));"
 out=sql(q);assert out['jobRows']==1 and out['jobStatus']==1 and out['volumeRows']==1,out
 if (c=='32' and scenario=='normal-ui-volume') or (c=='31' and scenario=='keyboard-ui-volume'):assert out['vm']==reg['vm']['id'] and out['device']==4,out
 checks.append({'scenario':scenario,'snapshot':original,**out})
posts=[x for x in rows if x.get('mutable') and x.get('cmd')=='revertSnapshot'];assert len(posts)==2
assert len(set(p['args']['customjobid'] for p in posts))==2
original=next(x['id'] for x in reg['snapshots'] if x['scenario']=='source')
for p in posts:
 jobid=p['args']['customjobid'];uuid.UUID(jobid);assert p['args']['id']==original
 out=sql(f"SELECT JSON_OBJECT('jobRows',COUNT(*),'jobStatus',MAX(job_status)) FROM async_job WHERE uuid='{jobid}';");assert out=={'jobRows':1,'jobStatus':1},out
 checks.append({'scenario':'revert','snapshot':original,'jobid':jobid,'transportPosts':1,**out})
(r/f'{c}-ui-db-verified.json').write_text(json.dumps({'status':'PASS','checks':checks},indent=2)+'\n')
print(c,'UI operation UUID/DB/attachment PASS',len(checks))
