# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with this
# work for additional information regarding copyright ownership. The ASF
# licenses this file to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations.
import json,os,shlex,subprocess,sys,uuid
from pathlib import Path
c=sys.argv[1];assert c in ('31','32');r=Path('/home/ablecloud/work/snapshot-actions-1479-20261023');checks=[]
for x in json.loads((r/f'{c}-dedupe-api.json').read_text()):
 table={'createSnapshot':'snapshots','createVolume':'volumes','createTemplate':'vm_template'}[x['command']];resource=x['resources'][0]['id'];checks.append((x['customjobid'],table,resource,x['command']))
p=json.loads((r/f'{c}-reload-protection-proof.json').read_text());jobid=p['transportRequests'][0]['args']['customjobid'];reg=json.loads((r/f'{c}-fixtures.json').read_text());resource=next(x['id'] for x in reg['snapshots'] if x['name']==f'SNAP1479-{c}-lost-reload-ui');checks.append((jobid,'snapshots',resource,'UI socket drop/F5'))
queries=[]
for jobid,table,resource,kind in checks:
 uuid.UUID(jobid);uuid.UUID(resource)
 queries.append(f"SELECT JSON_OBJECT('kind','{kind}','jobid','{jobid}','jobRows',(SELECT COUNT(*) FROM async_job WHERE uuid='{jobid}'),'jobStatus',(SELECT job_status FROM async_job WHERE uuid='{jobid}'),'resource','{resource}','resourceRows',(SELECT COUNT(*) FROM {table} WHERE uuid='{resource}'));")
cmd=['sshpass','-e','ssh','-n','-o','StrictHostKeyChecking=yes',f'ablecloud@10.10.{c}.10',shlex.join(['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e','\n'.join(queries)])]
rows=[json.loads(x) for x in subprocess.check_output(cmd,env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},text=True).splitlines()];assert all(x['jobRows']==1 and x['resourceRows']==1 and x['jobStatus']==1 for x in rows),rows;(r/f'{c}-dedupe-db.json').write_text(json.dumps(rows,indent=2)+'\n');print(c,'exact job/resource DB rows=1',len(rows),'cases')
