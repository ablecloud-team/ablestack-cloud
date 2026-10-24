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
import hashlib,json,re
from pathlib import Path
from datetime import datetime,timezone
root=Path(__file__).resolve().parents[1];e=root/'evidence'
def read(name):return json.loads((e/name).read_text(encoding='utf-8-sig'))
assert 'Tests run: 93, Failures: 0, Errors: 0, Skipped: 0' in (e/'snapshot-1479-server-tests.log').read_text()
assert re.search(r'Tests:\s+129 passed, 129 total',(e/'snapshot-1479-ui-regression-final.log').read_text())
assert 'No lint errors found!' in (e/'snapshot-1479-ui-lint-final.log').read_text()
assert 'Build complete' in (e/'snapshot-1479-ui-build-final.log').read_text()
assert 'BUILD SUCCESS' in (e/'snapshot-1479-schema-compat-build.log').read_text()
for path,sha in read('source-sha256.json').items():assert hashlib.sha256(Path(path).read_bytes()).hexdigest()==sha,path
clusters={}
for c in ('31','32'):
 counters=read(f'{c}-final-counters.json');assert all(v==0 for v in counters.values())
 physical=read(f'{c}-physical-cleanup-verification.json');assert physical['status']=='PASS'
 preservation=read(f'{c}-preservation.json');assert preservation['status']=='PASS' and all(x['unchanged'] for x in preservation['preserved'])
 runtime=read(f'{c}-runtime-final.json');assert runtime['verified'] and runtime['backendClasses']==20 and runtime['uiFiles']==920 and runtime['configurationPreserved'] and runtime['unmodifiedJarEntries']>200000
 assert read(f'{c}-linkage-final.json')=={'checked':20,'missing':[]}
 assert len(read(f'{c}-runtime-smoke.json'))==8
 assert read(f'{c}-reload-protection-proof.json')['status']=='PASS'
 assert all(x['jobRows']==x['resourceRows']==1 and x['jobStatus']==1 for x in read(f'{c}-dedupe-db.json'))
 assert all(x['status']=='PASS' and len(x['resources'])==1 for x in read(f'{c}-dedupe-api.json'))
 bulk=read(f'{c}-bulk-transport.json');assert bulk['secondMutationCount']==0 and bulk['secondPreserved'] and len({x['jobid'] for x in bulk['posts']})==1
 images=read(f'{c}-bulk-physical.json')['images'];row=next(x for x in bulk['secondDbRows'] if x['role']=='Image');assert any(x['path']=='/nfs/secondary/'+row['path'] and x['exists'] for x in images)
 assert read(f'{c}-project-query-proof.json')['status']=='PASS'
 assert read(f'{c}-late-route-proof.json')['status']=='PASS'
 ax=(e/f'{c}-late-route-final.txt').read_text();assert 'container 볼륨 생성 도움말' not in ax and '실행 전 검증 중단' in ax
 ax=(e/f'{c}-permission-final.txt').read_text();assert '현재 계정에 이 작업 권한이 없습니다' in ax
 assert read(f'{c}-conflict-clear.json')['backupConflicts']==read(f'{c}-conflict-clear.json')['vmSnapshotConflicts']==0
 db=read(f'{c}-final-db.json');assert all(x['state']=='Destroyed' or x['removed'] for x in db['snapshots']);assert all(x['refstate'] in ('Destroyed',None) for x in db['snapshots'])
 expected={x['owner']:x for x in db['expectedCounts']}
 for x in db['counts']:assert x['count']==expected[x['owner']][x['type']],x
 reg=read(f'{c}-fixtures.json');clusters[c]={'status':'PASS','snapshots':len(reg['snapshots']),'volumesIncludingVmRoot':len(reg['volumes']),'templates':len(reg['templates']),'activeFixtureCounters':counters,'manualResidualFileCleanup':1 if c=='31' else 0,'knownAccountCleanupUsageIncrements':len(read(f'{c}-known-account-cleanup-events.json')),'jarSha256':runtime['jarSha256']}
residual=read('31-existing-delete-residual.json');assert residual['productCleanupSucceeded'] is False and residual['manualResult']['removed']
assert read('windows-ftctl-preserved.json')['preserved'] and read('wsl-ftctl-preserved.json')['preserved']
files=[p for p in sorted(e.iterdir()) if p.is_file() and p.name!='verification.json']
result={'issue':1479,'epic':1473,'status':'PASS within SS-06 scope','javaTests':93,'uiTests':129,'uiSuites':10,'fullCloudBuildRun':False,'pullRequestMerged':False,'clusters':clusters,'followup':{'issue':1489,'absoluteInstallPathDeleteResidual':'UNRESOLVED: one 31 test file manually removed after DB/hash guards','accountCleanupUsageDuplication':'UNRESOLVED','hotAttachCastFailure':'UNRESOLVED diagnostic'},'verifiedAt':datetime.now(timezone.utc).isoformat(),'evidenceSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}}
(e/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='evidenceSha256'},ensure_ascii=False,indent=2))
