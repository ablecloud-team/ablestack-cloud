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
import json,os,subprocess,shlex,sys
from pathlib import Path
root=Path('/home/ablecloud/work/snapshot-download-1475-20261024')
def remote(c,args,host=None):
 user='root' if host else 'ablecloud'; addr='10.10.'+c+'.'+str(host or 10)
 options=['-o','StrictHostKeyChecking=yes']
 if c=='32' and host:options+=['-o','UserKnownHostsFile=/home/ablecloud/work/snapshot-archive-1477-20261023/trusted-hosts32']
 env={**os.environ,'SSHPASS':os.environ['HOST_SECRET' if host else 'SSH_SECRET']}
 try:return subprocess.check_output(['sshpass','-e','ssh','-n','-p','22']+options+[user+'@'+addr,shlex.join(args)],env=env,text=True).strip()
 except subprocess.CalledProcessError as e:raise RuntimeError('Remote command failed: '+addr+' exit '+str(e.returncode)) from None
def sql(c,q):return [json.loads(x) for x in remote(c,['sudo','-n','env','MYSQL_PWD='+os.environ['DB_SECRET'],'mysql','-uroot','-N','-B','--raw','cloud','-e',q]).splitlines()]
if __name__=='__main__':
 for c in ['31','32']:
  inv=json.loads((root/(c+'-existing-before.json')).read_text())
  summary={k:[{x:v for x,v in r.items() if x in ['id','name','displayname','state','type','ipaddress','url','size','ostypeid','isready','zoneid','path','hostid']} for r in data.get({'listTemplates':'template','listVolumes':'volume','listHosts':'host','listStoragePools':'storagepool','listServiceOfferings':'serviceoffering','listNetworks':'network'}.get(k,'_'),[])] for k,data in inv.items() if k in ['listTemplates','listVolumes','listHosts','listStoragePools','listServiceOfferings','listNetworks']}
  (root/(c+'-summary.json')).write_text(json.dumps(summary,indent=2))
  rows=sql(c,"SELECT JSON_OBJECT('id',id,'name',name,'type',type,'ip',private_ip_address,'status',status,'resource',resource) FROM host WHERE removed IS NULL;")
  (root/(c+'-host-db.json')).write_text(json.dumps(rows,indent=2));print(c,json.dumps(rows))
  probe=remote(c,['sudo','-n','sh','-c', 'find /usr/share/cloudstack-management /usr/share/cloudstack-agent -maxdepth 3 -name "*secondary*jar" -o -name "cloudstack*.jar" -o -name "cloud-core*.jar"; command -v qemu-img; pgrep -af "SecondaryStorage|AgentShell|cloudstack" | head -8'])
  (root/(c+'-runtime-before.txt')).write_text(probe);print(probe[:1200])
