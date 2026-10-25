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
from pathlib import Path
import sys
import zipfile

cluster = sys.argv[1]
assert cluster in ['31', '32']
web = Path('/usr/share/cloudstack-management/webapp')
stage = Path('/home/ablecloud/snapshot-actions-1479-stage/final')
backend = json.loads(Path('/root/snapshot-actions-1479-backend-owner-20261023/backend-result.json').read_text())
ui_backup = '/root/snapshot-actions-1479-ui-final-routes-20261023'
ui = json.loads((Path(ui_backup)/'ui-result.json').read_text())
assert (web/'WEB-INF').is_dir()
for path, sha in {**ui['config'], **backend['preservedConfig']}.items():
    assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == sha, path
jar = Path(backend['jar'])
assert hashlib.sha256(jar.read_bytes()).hexdigest() == backend['after']
with zipfile.ZipFile(jar) as archive:
    for item in backend['classes']:
        assert hashlib.sha256(archive.read(item['path'])).hexdigest() == item['sha256']
baseline = json.loads(Path('/root/snapshot-actions-1479-backend-20261023/backend-result.json').read_text())
with zipfile.ZipFile(baseline['backup']) as old, zipfile.ZipFile(jar) as current:
    changed = {item['path'] for item in backend['classes']}
    preserved = [name for name in old.namelist() if name not in changed]
    for name in preserved:
        assert old.read(name) == current.read(name), name
    assert set(current.namelist()) - set(old.namelist()) <= changed
manifest = json.loads((stage/'ui-manifest.json').read_text())
for item in manifest:
    assert hashlib.sha256((web/item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
js = b''.join((web/item['path']).read_bytes() for item in manifest if item['path'].endswith('.js'))
markers = ['blockingLoadingState', 'fetchSyncProgress', 'extractJobId', 'snapshot-deletion-recovery', 'storage-snapshot-operations', 'customjobid']
for marker in markers:
    assert marker.encode() in js, marker
print(json.dumps({'cluster': cluster, 'backendClasses': len(backend['classes']), 'uiFiles': len(manifest), 'jarSha256': backend['after'], 'WEB-INF': True, 'configurationPreserved': True, 'markers': markers, 'unmodifiedJarEntries': len(preserved), 'verified': True}))
