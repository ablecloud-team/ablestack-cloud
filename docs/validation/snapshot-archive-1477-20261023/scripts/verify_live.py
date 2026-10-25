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
stage = Path('/home/ablecloud/snapshot-archive-1477-stage')
backend = json.loads(Path('/root/snapshot-archive-1477-backend-permissions-20261023/backend-result.json').read_text())
ui_backup = '/root/snapshot-archive-1477-ui-final-20261023'
ui = json.loads((Path(ui_backup)/'ui-result.json').read_text())
assert (web/'WEB-INF').is_dir()
for path, sha in {**ui['config'], **backend['preservedConfig']}.items():
    assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == sha, path
jar = Path(backend['jar'])
assert hashlib.sha256(jar.read_bytes()).hexdigest() == backend['after']
with zipfile.ZipFile(jar) as archive:
    for item in backend['classes']:
        assert hashlib.sha256(archive.read(item['path'])).hexdigest() == item['sha256']
manifest = json.loads((stage/'ui-manifest.json').read_text())
for item in manifest:
    assert hashlib.sha256((web/item['path']).read_bytes()).hexdigest() == item['sha256'], item['path']
js = b''.join((web/item['path']).read_bytes() for item in manifest if item['path'].endswith('.js'))
markers = ['blockingLoadingState', 'fetchSyncProgress', 'extractJobId', 'snapshot-deletion-recovery', 'force-cleanup-plan', 'force-cleanup-results', 'label.snapshot.force.execute']
for marker in markers:
    assert marker.encode() in js, marker
original = next(Path('/root/snapshot-archive-1477-backend-20261023').glob('cloudstack-*.jar'))
with zipfile.ZipFile(original) as old, zipfile.ZipFile(jar) as current:
    changed = {x['path'] for x in backend['classes']}
    preserved = [x for x in old.infolist() if x.filename not in changed]
    for x in preserved:
        newer = current.getinfo(x.filename)
        assert (x.CRC, x.file_size) == (newer.CRC, newer.file_size), x.filename
for early in ['/root/snapshot-archive-1477-backend-20261023/backend-result.json', '/root/snapshot-archive-1477-ui-20261023/ui-result.json']:
    data = json.loads(Path(early).read_text())
    for path, sha in data.get('preservedConfig', data.get('config', {})).items():
        assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == sha
print(json.dumps({'cluster': cluster, 'backendClasses': len(backend['classes']), 'uiFiles': len(manifest), 'jarSha256': backend['after'], 'WEB-INF': True, 'unmodifiedJarEntriesPreserved': len(preserved), 'configurationPreserved': True, 'markers': markers, 'verified': True}))
