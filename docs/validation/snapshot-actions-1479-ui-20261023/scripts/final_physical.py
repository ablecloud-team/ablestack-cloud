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
import json,os,shlex,subprocess,sys
from pathlib import Path
c=sys.argv[1];assert c in ('31','32');r=Path('/home/ablecloud/work/snapshot-actions-1479-ui-20261023');reg=json.loads((r/f'{c}-fixtures.json').read_text());db=json.loads((r/f'{c}-final-db.json').read_text());p=json.loads((r/f'{c}-final-physical.json').read_text())
assert all(not x['exists'] for x in p['images']),p['images']
if c=='31':assert not p['sourceVolumePaths']
else:assert all(not x['exists'] for x in p['sourceVolumes']) and not p['fixtureClones']
def remote(args,host=None):
 cmd=['sshpass','-e','ssh','-n','-o','StrictHostKeyChecking=yes']
 if host and c=='32':cmd+=['-o','UserKnownHostsFile=/home/ablecloud/work/snapshot-archive-1477-20261023/trusted-hosts32']
 cmd+=[f'root@10.10.{c}.{host}' if host else f'ablecloud@10.10.{c}.10',shlex.join(args)]
 return subprocess.check_output(cmd,env={**os.environ,'SSHPASS':os.environ['HOST_SECRET' if host else 'SSH_SECRET']},text=True).strip()
templates=json.loads((r/f'{c}-pre-cleanup-templates-db.json').read_text());paths=sorted(set('/nfs/secondary/'+t['path'] for t in templates if t['path']))
program="import sys,json,pathlib;print(json.dumps([{'path':x,'exists':pathlib.Path(x).exists()} for x in sys.argv[1:]]))"
templateFiles=json.loads(remote(['sudo','-n','python3','-c',program]+paths));assert all(not x['exists'] for x in templateFiles),templateFiles
hosts=[]
for h in ('1','2','3'):
 domains=remote(['virsh','list','--all','--name'],h).splitlines();assert reg['vm']['instancename'] not in domains
 hosts.append({'host':f'10.10.{c}.{h}','fixtureDomainAbsent':True})
result={'cluster':c,'secondarySnapshotFilesAbsent':len(p['images']),'knownPrimaryVolumesAbsent':len(reg['volumes']),'templateFiles':templateFiles,'hosts':hosts,'status':'PASS'}
(r/f'{c}-physical-cleanup-verification.json').write_text(json.dumps(result,indent=2)+'\n');print(c,'NFS/primary/domain known fixture physical objects absent')
