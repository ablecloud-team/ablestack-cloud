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
c=sys.argv[1];assert c in ['31','32'];r=Path('/home/ablecloud/work/snapshot-actions-1479-ui-20261023')
rows=[json.loads(x) for x in (r/f'{c}-proxy.jsonl').read_text().splitlines()]
posts=[x for x in rows if x.get('mutable') and x.get('cmd')=='createVolume' and x.get('args',{}).get('name')==f'SNAP1479UI-{c}-lost-ui-volume']
reg=json.loads((r/f'{c}-fixtures.json').read_text())
original=next(x['id'] for x in reg['snapshots'] if x['scenario']=='orphan-source')
assert all(x['args']['snapshotid']==original for x in posts)
assert not any(x.get('mutable') and x.get('cmd')=='revertSnapshot' for x in rows)
ids=set(x['args']['customjobid'] for x in posts);assert len(ids)==1 and len(posts)>=1
jobid=next(iter(ids));uuid.UUID(jobid)
q=f"SELECT JSON_OBJECT('jobid','{jobid}','transportPosts',{len(posts)},'jobRows',(SELECT COUNT(*) FROM async_job WHERE uuid='{jobid}'),'jobStatus',(SELECT job_status FROM async_job WHERE uuid='{jobid}'),'volumeRows',(SELECT COUNT(*) FROM volumes WHERE name='SNAP1479UI-{c}-lost-ui-volume'),'volume',(SELECT uuid FROM volumes WHERE name='SNAP1479UI-{c}-lost-ui-volume'),'uiRevertPosts',0);"
cmd=['sshpass','-e','ssh','-n','-o','StrictHostKeyChecking=yes',f'ablecloud@10.10.{c}.10',shlex.join(['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',q])]
o=json.loads(subprocess.check_output(cmd,env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},text=True));assert o['jobRows']==1 and o['jobStatus']==1 and o['volumeRows']==1,o
(r/f'{c}-ui-dedupe-db.json').write_text(json.dumps(o,indent=2)+'\n');print(c,'UI dedupe DB PASS',o)
