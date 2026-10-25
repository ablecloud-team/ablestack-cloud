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
import json,hashlib,zipfile,sys
from pathlib import Path
from remote import root,remote
repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots')
for c in ['31','32']:
 expected={}
 for file in [root/('stage-'+c)/'manifest.json',root/('server-final-'+c)/'manifest.json']:
  for x in json.loads(file.read_text()):
   if x['group']=='management':expected[x['path']]=x['sha256']
 code="""import sys,json,zipfile,hashlib
from pathlib import Path
expected=json.loads(sys.argv[1]);jars=list(Path('/usr/share/cloudstack-management/lib').glob('cloudstack-*.jar'));assert len(jars)==1
with zipfile.ZipFile(jars[0]) as z: actual={k:hashlib.sha256(z.read(k)).hexdigest() for k in expected}
assert actual==expected
web=Path('/usr/share/cloudstack-management/webapp');assert (web/'WEB-INF').is_dir()
stage=Path('/root/snapshot1475-ui-verified-stage');manifest=json.loads((stage/'ui-manifest.json').read_text());assert len(manifest)==921
assert all(hashlib.sha256((web/x['path']).read_bytes()).hexdigest()==x['sha256'] for x in manifest)
backup=json.loads(Path('/root/snapshot-download-1475-20261024-ui-verified-backup/ui-result.json').read_text());assert all(hashlib.sha256(Path(k).read_bytes()).hexdigest()==v for k,v in backup['config'].items())
print(json.dumps({'classes':actual,'WEB-INF':True,'staticFiles':len(manifest),'configPreserved':True,'protectedServerFiles':backup['protectedServerFiles']}))
"""
 management=json.loads(remote(c,['sudo','-n','python3','-c',code,json.dumps(expected)]))
 ssvmExpected={'core':{},'secondary':{}}
 for x in json.loads((root/('stage-'+c)/'manifest.json').read_text()):
  if x['group'] in ssvmExpected:ssvmExpected[x['group']][x['path']]=x['sha256']
 if c=='32':
  for x in json.loads((root/'compat32/manifest.json').read_text()):ssvmExpected[x['group']][x['path']]=x['sha256']
 with zipfile.ZipFile(root/(c+'-original-secondary.jar')) as old:
  for path in ['org/apache/cloudstack/storage/resource/NfsSecondaryStorageResource.class','org/apache/cloudstack/storage/formatinspector/SnapshotImageMetadata.class']:
   ssvmExpected['secondary'][path]=hashlib.sha256(old.read(path)).hexdigest()
 code="""import json,sys,zipfile,hashlib
from pathlib import Path
expected=json.loads(sys.argv[1]);actual={}
for group,entries in expected.items():
 jars=list(Path('/usr/local/cloud/systemvm').glob('cloud-'+('core' if group=='core' else 'secondary-storage')+'-*.jar'));assert len(jars)==1
 with zipfile.ZipFile(jars[0]) as z:actual[group]={k:hashlib.sha256(z.read(k)).hexdigest() for k in entries}
assert actual==expected
print(json.dumps({'classes':actual,'existingSnapshotTemplateMethodsPreserved':True}))
"""
 import shlex
 ip='10.10.'+c+('.8' if c=='31' else '.7')
 opts=['sudo','-n','ssh','-p','3922','-i','/var/cloudstack/management/.ssh/id_rsa','-o','UserKnownHostsFile=/root/snapshot1474-ssvm-known-hosts','-o','StrictHostKeyChecking=yes','root@'+ip]
 ssvm=json.loads(remote(c,opts+[shlex.join(['python3','-c',code,json.dumps(ssvmExpected)])]))
 active=remote(c,['sudo','-n','systemctl','is-active','mold']);http=remote(c,['curl','-sS','-o','/dev/null','-w','%{http_code}','http://10.10.'+c+'.10:8080/client/']);assert active=='active' and http=='200'
 (root/(c+'-runtime-verified.json')).write_text(json.dumps({'management':management,'ssvm':ssvm,'mold':active,'clientHttp':int(http)},indent=2));print(c,'management/SSVM classes, 921 static files, preserved config, active/client200 PASS')
