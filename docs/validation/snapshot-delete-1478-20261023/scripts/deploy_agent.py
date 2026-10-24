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

import hashlib,json,os,shutil,subprocess,sys,zipfile
from pathlib import Path
stage=Path(sys.argv[1]);backup=Path('/root/snapshot-delete-1478-kvm-20261023')
assert not backup.exists();backup.mkdir(mode=0o700)
jars=list(Path('/usr/share/cloudstack-agent/lib').glob('cloud-plugin-hypervisor-kvm*.jar'));assert len(jars)==1
jar=jars[0];shutil.copy2(jar,backup/jar.name)
programs=Path('/usr/local/lib/ablestack-qemu-exec-tools/ftctl')
def program_hashes(): return {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in programs.rglob('*') if p.is_file() and p.suffix in ['.py','.sh']}
before=program_hashes();vms=subprocess.check_output(['virsh','list','--all']).decode()
manifest=json.loads((stage/'manifest.json').read_text());updates={x['path']:(stage/x['path']).read_bytes() for x in manifest}
for x in manifest:assert hashlib.sha256(updates[x['path']]).hexdigest()==x['sha256']
tmp=jar.with_suffix('.1478-tmp')
with zipfile.ZipFile(jar) as original,zipfile.ZipFile(tmp,'w') as patched:
 assert set(updates).issubset(original.namelist())
 for info in original.infolist():patched.writestr(info,updates.get(info.filename,original.read(info)))
shutil.copystat(jar,tmp);os.replace(tmp,jar)
try:
 subprocess.run(['systemctl','restart','mold-agent'],check=True)
 subprocess.run(['systemctl','is-active','--quiet','mold-agent'],check=True)
except Exception:
 shutil.copy2(backup/jar.name,jar);subprocess.run(['systemctl','restart','mold-agent'],check=False);raise
assert before==program_hashes()
after_vms=subprocess.check_output(['virsh','list','--all']).decode()
assert vms==after_vms,'VM inventory changed; inspect before continuing'
with zipfile.ZipFile(jar) as check:
 for x in manifest:assert hashlib.sha256(check.read(x['path'])).hexdigest()==x['sha256']
result={'jar':str(jar),'backup':str(backup/jar.name),'classes':manifest,'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'vmsUnchanged':True,'ftctlProgramsUnchanged':True,'ftctlProgramCount':len(before)}
(backup/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
