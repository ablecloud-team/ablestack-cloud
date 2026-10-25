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

import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import zipfile

staging = Path(sys.argv[1]).resolve()
backup = Path(sys.argv[2]).resolve()
webapp = Path('/usr/share/cloudstack-management/webapp')
assert (webapp / 'WEB-INF').is_dir()
jars = list(Path('/usr/share/cloudstack-management/lib').glob('cloudstack-*.jar'))
assert len(jars) == 1, jars
jar = jars[0]
assert not backup.exists(), 'A fresh backup directory is required'
backup.mkdir(mode=0o700)
manifest = json.loads((staging / 'manifest.json').read_text())
updates = {item['path']: (staging / item['path']).read_bytes() for item in manifest}
for item in manifest:
    assert hashlib.sha256(updates[item['path']]).hexdigest() == item['sha256']
shutil.copy2(jar, backup / jar.name)
config_hashes = {}
for p in [webapp/'WEB-INF/web.xml', webapp/'config.json']:
    if p.exists(): config_hashes[str(p)] = hashlib.sha256(p.read_bytes()).hexdigest()
result = {'jar': str(jar), 'backup': str(backup/jar.name), 'before': hashlib.sha256(jar.read_bytes()).hexdigest(), 'classes': manifest, 'preservedConfig': config_hashes}
temporary = jar.with_suffix('.1478-tmp')
with zipfile.ZipFile(jar) as original, zipfile.ZipFile(temporary, 'w') as patched:
    assert not any(info.filename.startswith('META-INF/') and info.filename.endswith(('.SF', '.RSA', '.DSA')) for info in original.infolist())
    present = set()
    for info in original.infolist():
        if info.filename in updates:
            patched.writestr(info, updates[info.filename]); present.add(info.filename)
        else:
            patched.writestr(info, original.read(info))
    for name, content in updates.items():
        if name not in present: patched.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)
shutil.copystat(jar, temporary)
with zipfile.ZipFile(temporary) as check:
    for item in manifest: assert hashlib.sha256(check.read(item['path'])).hexdigest() == item['sha256']
os.replace(temporary, jar)
result['after'] = hashlib.sha256(jar.read_bytes()).hexdigest()
for path, sha in config_hashes.items(): assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == sha
(backup/'backend-result.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps({'jar': str(jar), 'backup': str(backup), 'classes': len(manifest), 'sha256': result['after']}))
