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


import json,os,subprocess,sys,shlex
from pathlib import Path
cluster,phase=sys.argv[1:3]
root=Path('/home/ablecloud/work/snapshot-dialogs-1488-20261025')
reg=json.loads((root/(cluster+'-fixtures.json')).read_text())
assert cluster in ('31','32')
def run(args,management=False):
 cmd=['sshpass','-e','ssh','-n','-p','22','-o','StrictHostKeyChecking=yes']
 if cluster=='32' and not management: cmd+=['-o','UserKnownHostsFile=/home/ablecloud/work/snapshot-archive-1477-20261023/trusted-hosts32']
 cmd += [('ablecloud@10.10.'+cluster+'.10') if management else ('root@10.10.'+cluster+'.1'),shlex.join(args)]
 return subprocess.check_output(cmd,env={**os.environ,'SSHPASS':os.environ['SSH_SECRET' if management else 'HOST_SECRET']},text=True).strip()
def sql(q):
 return [json.loads(x) for x in run(['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',q],True).splitlines()]
results=[]
for v in reg['volumes']:
 if phase=='seed' and v['scenario'] not in ('owner',):continue
 if phase!='seed' and len(sys.argv)>3 and v['scenario']!=sys.argv[3]:continue
 row=sql("SELECT JSON_OBJECT('name',name,'state',state,'removed',removed,'path',path,'instanceid',instance_id) FROM volumes WHERE uuid='"+v['id']+"';")[0]
 assert row['name'].startswith(reg['prefix']) and row['state']=='Ready' and row['removed'] is None
 if phase in ('seed','change'):assert row['instanceid'] is None,'Never write a connected disk'
 image='/mnt/glue-gfs/'+row['path'] if cluster=='31' else 'rbd:rbd/'+row['path']
 args=['timeout','30','qemu-io','-f','qcow2' if cluster=='31' else 'raw']
 pattern='0xa5' if phase in ('change','verify-changed') else '0x5a'
 if phase in ('seed','change'):out=run(args+['-c','write -P '+pattern+' 0 1M',image])
 else:out=run(args+['-c','read -P '+pattern+' 0 1M',image])
 results.append({'volume':v['id'],'scenario':v['scenario'],'image':image,'phase':phase,'output':out,'verifiedPattern':pattern})
(root/(cluster+'-pattern-'+phase+(('-'+sys.argv[3]) if len(sys.argv)>3 else '')+'.json')).write_text(json.dumps(results,indent=2)+'\n')
print(cluster,phase,'PASS',len(results))
