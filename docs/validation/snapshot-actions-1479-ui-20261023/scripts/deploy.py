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


from pathlib import Path
import json,hashlib,os,subprocess,shlex,tarfile,sys
phase=os.environ.get('DEPLOY_PHASE','final');assert phase in ['initial','final'];r=Path('/home/ablecloud/work/snapshot-actions-1479-ui-20261023');repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots');dist=repo/'ui/dist';stage=r/('stage-'+phase);stage.mkdir(exist_ok=True)
manifest=[]
for p in sorted(dist.rglob('*')):
 if p.is_file() and p.relative_to(dist).parts[0] not in ['WEB-INF','META-INF','config.json','config.js']:
  manifest.append({'path':p.relative_to(dist).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
(stage/'ui-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
assert any(x['path']=='index.html' for x in manifest)
localMarkers={}
for marker in ['snapshot-job-details','snapshot-notice-target','blockingLoadingState','fetchSyncProgress','extractJobId']:
 files=[x for x in manifest if x['path'].endswith('.js') and marker in (dist/x['path']).read_text()]
 assert files,marker;localMarkers[marker]=files
(r/('active-bundle-markers-'+phase+'.json')).write_text(json.dumps({'indexSha256':next(x['sha256'] for x in manifest if x['path']=='index.html'),'markers':localMarkers},indent=2)+'\n')
with tarfile.open(r/('ui-'+phase+'.tgz'),'w:gz') as tar:
 for x in manifest:tar.add(dist/x['path'],arcname='ui/'+x['path'])
 tar.add(stage/'ui-manifest.json',arcname='ui-manifest.json');tar.add(r/'scripts/deploy_ui.py',arcname='deploy_ui.py')
for c in sys.argv[1:]:
 assert c in ['31','32'];dest=f'ablecloud@10.10.{c}.10';remote='/home/ablecloud/snapshot-actions-1479-ui-notices-20261023-'+phase;backup='/root/snapshot-actions-1479-ui-notices-20261023-'+phase
 env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']}
 def ssh(args):return subprocess.check_output(['sshpass','-e','ssh','-n','-o','StrictHostKeyChecking=yes',dest,shlex.join(args)],env=env,text=True).strip()
 before=ssh(['sudo','-n','sha256sum',f'/usr/share/cloudstack-management/lib/cloudstack-4.23.0.0'+('-ABLESTACK.Mold' if c=='31' else '')+'.jar'])
 ssh(['mkdir','-p',remote])
 subprocess.run(['sshpass','-e','scp','-o','StrictHostKeyChecking=yes',str(r/('ui-'+phase+'.tgz')),dest+':'+remote+'/ui.tgz'],env=env,check=True)
 ssh(['tar','-xzf',remote+'/ui.tgz','-C',remote])
 result=json.loads(ssh(['sudo','-n','python3',remote+'/deploy_ui.py',remote,backup]))
 script="test -d /usr/share/cloudstack-management/webapp/WEB-INF && curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/client/ && systemctl is-active mold && sha256sum /usr/share/cloudstack-management/lib/cloudstack-4.23.0.0"+('-ABLESTACK.Mold' if c=='31' else '')+".jar"
 status=ssh(['bash','-c',script]);assert '200active' in status and before in status,status
 markers={}
 for marker in ['snapshot-job-details','snapshot-notice-target','blockingLoadingState','fetchSyncProgress','extractJobId']:
  found=ssh(['sudo','-n','grep','-rl','--include=*.js',marker,'/usr/share/cloudstack-management/webapp/js']);assert found,marker;markers[marker]=True
 result.update({'cluster':c,'clientHttp':200,'moldActive':True,'backendJarUnchanged':True,'backendJarSha256':before.split()[0],'markers':markers})
 (r/f'{c}-ui-deploy-{phase}.json').write_text(json.dumps(result,indent=2)+'\n');print(c,'deployed',result['files'],'static files, webapp/config/JAR preserved')
