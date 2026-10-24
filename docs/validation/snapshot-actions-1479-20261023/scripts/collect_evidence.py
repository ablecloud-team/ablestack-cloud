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
import hashlib,json,re,shutil,subprocess
from pathlib import Path
r=Path('/home/ablecloud/work/snapshot-actions-1479-20261023');out=Path('docs/validation/snapshot-actions-1479-20261023/evidence');out.mkdir(exist_ok=True)
def clean(x):
 if isinstance(x,dict):return {k:'[REDACTED]' if re.search('password|passphrase|session|secret|api.?key|cookie|token|userdata',k,re.I) else clean(v) for k,v in x.items()}
 if isinstance(x,list):return [clean(v) for v in x]
 return x
for p in r.glob('*.json'):
 if any(x in p.name for x in ['existing-before','proxy-control','source-sha256','issue-before','owner-before','ftctl-before','linkage-before']):continue
 data=clean(json.loads(p.read_text()));(out/p.name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
for p in (r/'windows-ui-evidence/evidence').iterdir():
 if p.name=='windows-ftctl-before.json':continue
 shutil.copy2(p,out/p.name)
for name in ['server-tests','ui-regression-final','ui-lint-final','ui-build-final','schema-compat-build']:
 p=Path('/home/ablecloud/work/snapshot-1479-'+name+'.log');assert p.is_file();shutil.copy2(p,out/p.name)
# Fixture allowlist only: exclude operational inventory and authentication responses.
for c in ('31','32'):
 reg=json.loads((r/f'{c}-fixtures.json').read_text());ids={x['id'] for bucket in ['snapshots','volumes','templates'] for x in reg[bucket]}|{reg['vm']['id'],reg['role']['id'],reg['tenant']['id'],reg['project']['id']}
 entries=[json.loads(line) for line in (r/f'{c}-api.jsonl').read_text().splitlines()];jobs=set();selected=[]
 for x in entries:
  if x['command'] in ('login','logout','listApis','listHosts'):continue
  args=x.get('args',{});fixture=any(str(v) in ids for k,v in args.items() if k in ['id','snapshotid','volumeid','virtualmachineid','roleid','accountid']) or str(args.get('name','')).startswith(f'SNAP1479-{c}-') or args.get('keyword')==f'SNAP1479-{c}' or args.get('jobid') in jobs
  if fixture:
   if x.get('data',{}).get('jobid'):jobs.add(x['data']['jobid'])
   selected.append(clean(x))
 (out/f'{c}-api-fixtures.jsonl').write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in selected))
 logs=[json.loads(line) for line in (r/f'{c}-proxy.jsonl').read_text().splitlines()];(out/f'{c}-proxy-fixtures.jsonl').write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in logs if x.get('fixture')))
for p in r.glob('*deploy*.txt'):shutil.copy2(p,out/p.name)
for p in r.glob('*deploy*.log'):shutil.copy2(p,out/p.name)
# Preserve only the relevant error from the hotplug diagnostic, not arbitrary management logs.
p=r/'32-hotattach-diagnostic.log'
if p.exists():(out/'32-hotattach-diagnostic.txt').write_text('\n'.join(l for l in p.read_text().splitlines() if re.search('SNAP1479|ClassCastException|AttachAnswer|11423292',l))+'\n')
paths=subprocess.check_output(['git','diff','--name-only']).decode().splitlines()+subprocess.check_output(['git','ls-files','--others','--exclude-standard','api','server','ui']).decode().splitlines()
hashes={p:hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in sorted(set(paths)) if Path(p).is_file() and p.startswith(('api/','server/','ui/'))};(out/'source-sha256.json').write_text(json.dumps(hashes,indent=2)+'\n')
print('Collected',len(list(out.iterdir())),'sanitized evidence files; source files',len(hashes))
