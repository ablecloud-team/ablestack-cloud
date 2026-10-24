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
import sys,json,subprocess,os,shlex
from remote import root,remote,sql
c,tag,mode=sys.argv[1:4];r=json.loads((root/(c+'-extract-'+tag+'.json')).read_text());d=r['job']['jobresult'];d=d.get('extractsnapshotresponse',d)['snapshot']
row=sql(c,"SELECT JSON_OBJECT('id',r.id,'path',r.install_path,'parent',i.parent,'url',r.download_url) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id JOIN image_store i ON i.id=r.store_id WHERE s.uuid='"+r['snapshot']+"' AND r.store_role='Image' AND r.state='Ready';")[0]
assert row['url']==d['url'];ip='10.10.'+c+('.8' if c=='31' else '.7');opts=['-p','3922','-i','/var/cloudstack/management/.ssh/id_rsa','-o','UserKnownHostsFile=/root/snapshot1474-ssvm-known-hosts','-o','StrictHostKeyChecking=yes']
def ssvm(args):return remote(c,['sudo','-n','ssh']+opts+['root@'+ip,shlex.join(args)])
def status():return remote(c,['curl','-sS','-o','/dev/null','-w','%{http_code}',d['url']])
before=status();assert before=='200',before
if mode=='deny':
 token=d['url'].split('/userdata/',1)[1].split('/')[0];ssvm(['chmod','700','/var/www/html/userdata/'+token]);after=status();assert after=='403',after
 result={'mode':'access-denied','before':before,'after':after,'url':d['url']}
elif mode=='expire':
 expiry=int(sql(c,"SELECT JSON_OBJECT('value',value) FROM configuration WHERE name='extract.url.expiration.interval';")[0]['value'] or 14400)+1
 sql(c,"UPDATE snapshot_store_ref SET download_url_created=DATE_SUB(NOW(),INTERVAL "+str(expiry)+" SECOND) WHERE id="+str(row['id'])+" AND download_url='"+d['url']+"';")
 env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']}
 subprocess.run(['sshpass','-e','scp','-P','22','-o','StrictHostKeyChecking=yes',str(root/'ExportLifecycleProbe.class'),'ablecloud@10.10.'+c+'.10:/tmp/ExportLifecycleProbe.class'],env=env,check=True)
 remote(c,['sudo','-n','scp','-P','3922']+opts[2:]+['/tmp/ExportLifecycleProbe.class','root@'+ip+':/root/snapshot1475-stage/ExportLifecycleProbe.class'])
 output=ssvm(['java','-cp','/root/snapshot1475-stage:/usr/local/cloud/systemvm/*','ExportLifecycleProbe',row['parent'],row['path'],d['url']]);answer=json.loads(output.split('PROBE_RESULT=',1)[1]);assert answer['result'],answer
 after=status();assert after=='404',after
 sql(c,"UPDATE snapshot_store_ref SET download_url=NULL,download_url_created=NULL WHERE id="+str(row['id'])+" AND download_url='"+d['url']+"';")
 result={'mode':'controlled-expiry','before':before,'after':after,'url':d['url'],'answer':answer,'timerWaited':False,'method':'Age only fixture DB timestamp, invoke installed SSVM revocation handler, clear only matching fixture URL as collector does'}
else:raise ValueError(mode)
(root/(c+'-lifecycle-'+mode+'.json')).write_text(json.dumps(result,indent=2));print(c,mode,before,'->',after)
