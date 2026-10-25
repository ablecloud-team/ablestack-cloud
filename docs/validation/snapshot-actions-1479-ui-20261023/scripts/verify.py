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


import hashlib,json,re,struct
from pathlib import Path
root=Path(__file__).resolve().parents[1];repo=root.parents[2];e=root/'evidence';checks=[]
def read(path):return json.loads((e/path).read_text(encoding='utf-8-sig'))
def check(name,condition):
 assert condition,name
 checks.append({'name':name,'status':'PASS'})
sources=read('source-sha256.json')
for f,sha in sources.items():
 p=repo/f;check('source '+f,(not p.exists()) if sha is None else hashlib.sha256(p.read_bytes()).hexdigest()==sha)
for entry in read('vm-standard-components.json'):check('VM standard '+entry['path'],hashlib.sha256((repo/entry['path']).read_bytes()).hexdigest()==entry['sha256'])
check('144 UI tests / 11 suites',bool(re.search(r'Tests:\s+144 passed, 144 total',(e/'focused-tests.log').read_text())) and bool(re.search(r'Test Suites:\s+11 passed, 11 total',(e/'focused-tests.log').read_text())))
check('full UI no-fix lint','No lint errors found!' in (e/'full-ui-lint.log').read_text())
check('final changed files no-fix lint','No lint errors found!' in (e/'final-files-lint.log').read_text())
check('production UI build','Build complete' in (e/'production-build.log').read_text() and 'Failed to compile' not in (e/'production-build.log').read_text())
for c in ['31','32']:
 dep=read(f'{c}-ui-deploy-final.json');check(c+' deployment/webapp/config/backend preservation',dep['files']==920 and dep['verified'] and dep['clientHttp']==200 and dep['moldActive'] and dep['backendJarUnchanged'] and all(dep['markers'].values()))
 dup=read(f'{c}-ui-dedupe-db.json');check(c+' duplicate transport → one job/resource',dup['transportPosts']==2 and dup['jobRows']==1 and dup['volumeRows']==1 and dup['jobStatus']==1 and dup['uiRevertPosts']==0)
 check(c+' forced cleanup DB and physical evidence',read(f'{c}-force-verified.json')['status']=='PASS')
 for pattern in ['source','other','lost-ui-volume']:
  check(c+' physical pattern '+pattern,all(x['verifiedPattern']=='0x5a' for x in read(f'{c}-pattern-verify-{pattern}.json')))
 check(c+' fixture DB counters zero',all(v==0 for v in read(f'{c}-final-counters.json').values()))
 check(c+' known physical objects/domain absent',read(f'{c}-physical-cleanup-verification.json')['status']=='PASS')
 check(c+' operational inventory preserved',read(f'{c}-preservation.json')['status']=='PASS')
 check(c+' resource counters consistent',read(f'{c}-resource-counts-consistent.json')['status']=='PASS')
 check(c+' runtime API/Agents healthy',all(x['status']=='PASS' for x in read(f'{c}-runtime-smoke.json')))
 for kind in ['snapshots','volumes']:
  txt=(e/'ui'/f'{c}-cleanup-{kind}-empty.txt').read_text();check(c+' UI empty '+kind,'데이터 없음' in txt and '전체 0 개 항목 중 0-0 표시' in txt)
 for state in ['job-unknown-final','job-complete-final','f5-result-link-restored-final','job-force-complete-final']:
  m=read(f'ui/{c}-{state}.json');check(c+' common md dialog '+state,len(m['dialogs'])==1 and m['dialogs'][0]['width']==720 and 'mold-dialog-standard' in m['dialogs'][0]['class'])
  txt=(e/'ui'/f'{c}-{state}.txt').read_text();check(c+' advanced IDs collapsed '+state,'button (collapsed)' in txt and '고급 · 작업 식별 및 복구' in txt and '요청 UUID' not in txt)
 for state in ['job-complete-final','f5-result-link-restored-final']:
  txt=(e/'ui'/f'{c}-{state}.txt').read_text();check(c+' frozen target and result link '+state,f'SNAP1479UI-{c}-orphan-source-snapshot' in txt and '생성된 리소스 보기' in txt)
 txt=(e/'ui'/f'{c}-job-unknown-final.txt').read_text();check(c+' final unknown guidance','기존 작업 결과 조회로 다시 확인하세요.' in txt and '재제출 차단' in txt)
 txt=(e/'ui'/f'{c}-final-f5-clear-still-blocked.txt').read_text();check(c+' final F5/clear lease blocks confirmation','button (disabled) 확인' in txt)
 txt=(e/'ui'/f'{c}-project-job-hidden.txt').read_text();check(c+' foreign project job hidden',f'SNAP1479UI-{c}-orphan-source-snapshot' not in txt)
 txt=(e/'ui'/f'{c}-zone-preserved-final.txt').read_text();check(c+' Zone tab preserved',bool(re.search(r'tab \(selected[^\n]*\) Zone',txt)))
 check(c+' ESC focus restored',read(f'ui/{c}-zone-preserved-final.json')['focus']=='알림')
 reference=read(f'ui/{c}-vm-reference.json');current=read(f'ui/{c}-vm-standard-final.json');check(c+' VM header/tab layout matches reference',reference['tabs']==current['tabs'] and reference['width']==current['width'] and reference['height']==current['height'])
for p in (e/'ui').glob('*.json'):
 if p.name=='activity-link-final.json':continue
 m=json.loads(p.read_text());check('no body panel/overflow '+p.stem,m['oldPanels']==0 and not m['overflow'] and all(not x['bodyOverflow'] for x in m['dialogs']))
for c in ['31','32']:
 for frame in ['mobile','tablet']:
  name=f'{c}-job-{frame}-final';m=read('ui/'+name+'.json');png=(e/'ui'/(name+'.png')).read_bytes();dims=struct.unpack('>II',png[16:24]);check('responsive raster '+name,dims==(m['width'],m['height']))
activity=read('ui/activity-link-final.json');check('existing activity panel link',all(all(v.values()) for v in activity.values()))
check('protected Windows FTCTL unchanged',read('protected-windows-preserved.json')['status']=='PASS')
check('protected WSL FTCTL unchanged',read('protected-wsl-preserved.json')['status']=='PASS')
check('temporary proxies stopped',read('proxy-cleanup.json')['stopped']==2)
summary={'status':'PASS','uiTests':144,'uiSuites':11,'newNotificationTests':15,'clusters':['31 GFS2','32 RBD'],'checks':checks,'fullCloudBuildRun':False,'actionsDispatched':False,'javaChangedOrRebuiltThisRound':False,'prMerged':False}
(e/'verification.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
manifest=[{'path':p.relative_to(root).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(root.rglob('*')) if p.is_file() and p.name!='files-sha256.json' and '__pycache__' not in p.parts]
(e/'files-sha256.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print('PASS',len(checks),'evidence/source assertions;',len(manifest),'files hashed')
