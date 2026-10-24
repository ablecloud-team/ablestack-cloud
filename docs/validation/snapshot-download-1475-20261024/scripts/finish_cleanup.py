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
import json,sys
from remote import root,remote,sql
for c in ['31','32']:
 expected={}
 for mode in ['raw','restore']:
  x=json.loads((root/(c+'-injection-'+mode+'.json')).read_text());expected[x['path'].rsplit('/',1)[1]]=x['originalSha256']
 script='''import json,sys,hashlib
from pathlib import Path
p=Path('/root/snapshot-download1475-fixture-backups');expected=json.loads(sys.argv[1]);out=[]
assert p.resolve()==Path('/root/snapshot-download1475-fixture-backups')
if p.exists():
 for b in p.iterdir():
  assert b.is_file() and not b.is_symlink() and b.name in expected
  digest=hashlib.sha256(b.read_bytes()).hexdigest();assert digest==expected[b.name];out.append({'name':b.name,'sha256':digest,'bytes':b.stat().st_size});b.unlink()
 p.rmdir()
print(json.dumps({'removedFixtureBackups':out,'directoryExists':p.exists(),'deploymentRollbackBackupsRetained':True}))'''
 out=remote(c,['sudo','-n','python3','-c',script,json.dumps(expected)]);(root/(c+'-fixture-backups-cleanup.json')).write_text(out)
 script="import json,sys;from pathlib import Path;p=Path('/root/snapshot-download1475-files');prefix=sys.argv[1];out=[]\nassert p.resolve()==Path('/root/snapshot-download1475-files')\nif p.exists():\n for b in p.iterdir():\n  assert b.is_file() and not b.is_symlink() and b.name.startswith(prefix)\n  out.append({'name':b.name,'bytes':b.stat().st_size});b.unlink()\n p.rmdir()\nprint(json.dumps({'removed':out,'directoryExists':p.exists()}))"
 out=remote(c,['sudo','-n','python3','-c',script,'SNAPD1475-'+c]);(root/(c+'-download-files-cleanup.json')).write_text(out)
 q="SELECT JSON_OBJECT('snapshot',(SELECT COUNT(*) FROM snapshots WHERE account_id=2 AND removed IS NULL AND status NOT IN ('Error','Destroyed') AND snapshot_type<>7),'secondary_storage',((SELECT COALESCE(SUM(size),0) FROM volumes WHERE account_id=2 AND path IS NULL AND state<>'Allocated' AND removed IS NULL)+(SELECT COALESCE(SUM(r.physical_size),0) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.account_id=2 AND s.removed IS NULL AND r.state='Ready' AND r.store_role='Image')+(SELECT COALESCE(SUM(r.size),0) FROM template_store_ref r JOIN vm_template t ON t.id=r.template_id WHERE t.account_id=2 AND t.removed IS NULL AND r.download_state IN ('DOWNLOADED','DOWNLOAD_IN_PROGRESS') AND r.destroyed=0)));"
 ex=sql(c,q)[0];actual={x['type']:x['count'] for x in json.loads((root/(c+'-final-state.json')).read_text())['counts']};assert ex==actual,(ex,actual)
 (root/(c+'-counters-verified.json')).write_text(json.dumps({'actual':actual,'calculated':ex,'match':True},indent=2));print(c,'fixture backups removed; counters consistent')
