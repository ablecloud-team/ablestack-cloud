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


# A reversible metadata-only ownership mismatch on a detached disposable volume.
import json,os,subprocess,shlex,sys
from pathlib import Path
cluster,mode=sys.argv[1:3];assert cluster in ('31','32') and mode in ('start','clear')
root=Path('/home/ablecloud/work/snapshot-actions-1479-20261023');reg=json.loads((root/(cluster+'-fixtures.json')).read_text())
source=next(v for v in reg['volumes'] if v['scenario']=='tenant');admin=next(v for v in reg['volumes'] if v['scenario']=='source');assert source['name'].startswith(reg['prefix'])
file=root/(cluster+'-owner-before.json')
def sql(q):
 args=['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',q]
 return [json.loads(x) for x in subprocess.check_output(['sshpass','-e','ssh','-n','-p','22','-o','StrictHostKeyChecking=yes','ablecloud@10.10.'+cluster+'.10',shlex.join(args)],env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},text=True).splitlines()]
where="uuid='"+source['id']+"' AND name='"+source['name']+"' AND state='Ready' AND removed IS NULL AND instance_id IS NULL"
read="SELECT JSON_OBJECT('id',id,'account',account_id,'domain',domain_id,'instance',instance_id,'path',path) FROM volumes WHERE "+where+';'
if mode=='start':
 before=sql(read);assert len(before)==1;assert not file.exists(),'One ownership fault per fixture';file.write_text(json.dumps(before[0],indent=2))
 sql("UPDATE volumes v JOIN volumes a ON a.uuid='"+admin['id']+"' SET v.account_id=a.account_id,v.domain_id=a.domain_id WHERE v.uuid='"+source['id']+"' AND v.name='"+source['name']+"' AND v.instance_id IS NULL AND v.state='Ready';")
 after=sql(read)[0];assert after['account']!=before[0]['account']
else:
 before=json.loads(file.read_text());sql('UPDATE volumes SET account_id='+str(before['account'])+',domain_id='+str(before['domain'])+' WHERE '+where+';');after=sql(read)[0];assert after==before
(root/(cluster+'-owner-'+mode+'.json')).write_text(json.dumps(after,indent=2)+'\n');print(cluster,mode,'PASS')
