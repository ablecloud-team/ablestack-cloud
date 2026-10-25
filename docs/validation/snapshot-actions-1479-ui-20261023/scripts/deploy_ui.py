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

import hashlib,json,shutil,sys
from pathlib import Path
stage=Path(sys.argv[1]);backup=Path(sys.argv[2]);web=Path('/usr/share/cloudstack-management/webapp')
assert (web/'WEB-INF').is_dir()
assert not backup.exists()
backup.mkdir(mode=0o700)
config={}
for p in [web/'config.json',web/'config.js',web/'WEB-INF/web.xml']:
 if p.exists(): config[str(p)]=hashlib.sha256(p.read_bytes()).hexdigest()
manifest=json.loads((stage/'ui-manifest.json').read_text())
for item in sorted(manifest,key=lambda x:x['path']=='index.html'):
 rel=Path(item['path']);assert not rel.is_absolute() and '..' not in rel.parts and rel.parts[0] not in ['WEB-INF','META-INF','config.json','config.js']
 source=stage/'ui'/rel;target=web/rel
 assert hashlib.sha256(source.read_bytes()).hexdigest()==item['sha256']
 if target.is_file():
  copy=backup/rel;copy.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(target,copy)
 target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
for item in manifest: assert hashlib.sha256((web/item['path']).read_bytes()).hexdigest()==item['sha256']
for path,sha in config.items(): assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==sha
assert (web/'WEB-INF').is_dir()
(backup/'ui-result.json').write_text(json.dumps({'files':len(manifest),'config':config,'verified':True},indent=2)+'\n')
print(json.dumps({'files':len(manifest),'verified':True,'backup':str(backup)}))
