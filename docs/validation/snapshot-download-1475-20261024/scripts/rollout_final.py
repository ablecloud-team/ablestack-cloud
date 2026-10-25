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
import json,hashlib,shutil,sys,tarfile,subprocess,os
from pathlib import Path
from remote import root,remote
repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots');mode=sys.argv[1];c=sys.argv[2] if len(sys.argv)>2 else None
def upload(archive,destination):
 subprocess.run(['sshpass','-e','scp','-P','22','-o','StrictHostKeyChecking=yes',str(archive),'ablecloud@10.10.'+c+'.10:'+destination],env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},check=True)
if mode=='server':
 stage=root/('server-final-'+c);stage.mkdir(exist_ok=True);manifest=[]
 for module,path in [('server','com/cloud/storage/snapshot/SnapshotManagerImpl.class'),('plugins/storage/image/default','org/apache/cloudstack/storage/datastore/driver/CloudStackImageStoreDriverImpl.class')]:
  dest=stage/'management'/path;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(repo/module/'target/classes'/path,dest)
  manifest.append({'group':'management','path':path,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
 (stage/'manifest.json').write_text(json.dumps(manifest));shutil.copy2(root/'scripts/patch_runtime.py',stage/'patch_runtime.py')
 archive=root/(c+'-server-final.tgz')
 with tarfile.open(archive,'w:gz') as tar:
  for p in stage.rglob('*'):
   if p.is_file():tar.add(p,arcname=str(p.relative_to(stage)))
 upload(archive,'/tmp/snapshot1475-server-final.tgz');remote(c,['sudo','-n','mkdir','-p','/root/snapshot1475-server-final']);remote(c,['sudo','-n','tar','xzf','/tmp/snapshot1475-server-final.tgz','-C','/root/snapshot1475-server-final'])
 out=remote(c,['sudo','-n','python3','/root/snapshot1475-server-final/patch_runtime.py','/root/snapshot1475-server-final','management','/root/snapshot-download-1475-20261024-management-final']);(root/(c+'-management-final-deploy.json')).write_text(out)
 remote(c,['sudo','-n','systemctl','restart','mold']);print(c,'final server deployed')
elif mode=='pack-ui':
 stage=root/'ui-verified-stage';stage.mkdir(exist_ok=True);shutil.copytree(repo/'ui/dist',stage/'ui',dirs_exist_ok=True)
 manifest=[{'path':str(p.relative_to(stage/'ui')),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (stage/'ui').rglob('*') if p.is_file() and p.relative_to(stage/'ui').parts[0] not in ['WEB-INF','META-INF','config.json','config.js']]
 (stage/'ui-manifest.json').write_text(json.dumps(manifest));shutil.copy2('/home/ablecloud/work/snapshot-dialogs-1488-20261023/scripts/deploy_ui.py',stage/'deploy_ui.py')
 with tarfile.open(root/'ui-verified.tgz','w:gz') as tar:
  for p in stage.rglob('*'):
   if p.is_file():tar.add(p,arcname=str(p.relative_to(stage)))
 print('Packaged',len(manifest),'static files')
elif mode=='ui':
 upload(root/'ui-verified.tgz','/tmp/snapshot1475-ui-verified.tgz');remote(c,['sudo','-n','mkdir','-p','/root/snapshot1475-ui-verified-stage']);remote(c,['sudo','-n','tar','xzf','/tmp/snapshot1475-ui-verified.tgz','-C','/root/snapshot1475-ui-verified-stage'])
 out=remote(c,['sudo','-n','python3','/root/snapshot1475-ui-verified-stage/deploy_ui.py','/root/snapshot1475-ui-verified-stage','/root/snapshot-download-1475-20261024-ui-verified-backup']);(root/(c+'-ui-deploy.json')).write_text(out);print(c,out)
else:raise ValueError(mode)
