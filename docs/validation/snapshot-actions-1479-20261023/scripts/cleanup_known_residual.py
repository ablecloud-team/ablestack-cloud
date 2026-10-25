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
# One immutable, identified test-only file. This is not a product cleanup success.
import json,os,shlex,subprocess
from pathlib import Path
r=Path('/home/ablecloud/work/snapshot-actions-1479-20261023');d=json.loads((r/'31-final-db.json').read_text());p=json.loads((r/'31-final-physical.json').read_text());s=next(x for x in d['snapshots'] if x['uuid']=='b33928f0-11af-43af-95f6-811d77eba840');assert s['name']=='SNAP1479-31-running-snapshot' and s['state']=='Destroyed' and s['refstate']=='Destroyed';v=next(x for x in d['volumes'] if x['uuid']=='0e7bbc27-b61b-4e8f-8571-047a0536f1ca');assert v['removed'] and v['state']=='Expunged';f=next(x for x in p['images'] if x['exists']);assert f['path']=='/nfs/secondary/snapshots/2/978/95d25630-0098-43ed-b478-e5d41e346e73'
evidence={'snapshot':s,'volume':v,'file':f,'productCleanupSucceeded':False,'followupIssue':1489,'manualTestFixtureCleanup':True};(r/'31-existing-delete-residual.json').write_text(json.dumps(evidence,indent=2)+'\n')
program="""import sys,pathlib,hashlib,json,subprocess,os
p=pathlib.Path(sys.argv[1]);expected=sys.argv[2]
assert str(p.resolve())==sys.argv[1] and str(p).startswith('/nfs/secondary/snapshots/2/978/') and not p.is_symlink()
env={**os.environ,'MYSQL_PWD':sys.argv[3]}
q=\"SELECT s.name,s.status,r.state,v.state,v.removed FROM snapshots s JOIN snapshot_store_ref r ON r.snapshot_id=s.id JOIN volumes v ON v.id=s.volume_id WHERE s.uuid='b33928f0-11af-43af-95f6-811d77eba840';\"
row=subprocess.check_output(['mysql','-uroot','-N','-B','cloud','-e',q],env=env,text=True).strip().split('\\t');assert row[:4]==['SNAP1479-31-running-snapshot','Destroyed','Destroyed','Expunged'] and row[4]!='NULL',row
assert hashlib.sha256(p.read_bytes()).hexdigest()==expected
p.unlink();assert not p.exists();print(json.dumps({'path':str(p),'sha256Before':expected,'dbReadOnlyGuard':True,'removed':True,'productCleanupSucceeded':False}))
"""
args=['sudo','-n','python3','-c',program,f['path'],f['sha256'],os.environ['DB_SECRET']];out=subprocess.check_output(['sshpass','-e','ssh','-n','-o','StrictHostKeyChecking=yes','ablecloud@10.10.31.10',shlex.join(args)],env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},text=True);evidence['manualResult']=json.loads(out);(r/'31-existing-delete-residual.json').write_text(json.dumps(evidence,indent=2)+'\n');print('31 identified residual fixture file removed after read-only DB/hash guards')
