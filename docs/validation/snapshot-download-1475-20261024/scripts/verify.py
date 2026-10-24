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
import json,hashlib,re,sys
from pathlib import Path
base=Path(__file__).resolve().parents[1];repo=base.parents[2];ev=base/'evidence';checks=[]
def read(name):return json.loads((ev/name).read_text(encoding='utf-8'))
def check(name,value):
 if not value:raise AssertionError(name)
 checks.append({'check':name,'passed':True})
scope=read('scope.json');source=read('source-sha256.json')
check('24 changed source/test files and two unchanged common inspectors',len(scope['changedSourceAndTests'])==24 and len(source)==26 and len(scope['reusedInspectors'])==2)
check('source hashes match this checkout',all(hashlib.sha256((repo/p).read_bytes()).hexdigest()==sha for p,sha in source.items()))
total=0
for name,n in [('core-tests.log',2),('server-final-tests.log',50),('secondary-final-tests.log',60)]:
 log=(ev/name).read_text(encoding='utf-8');rows=re.findall(r'Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0',log)
 check(name+' focused tests/install',bool(rows) and int(rows[-1])==n and 'BUILD SUCCESS' in log);total+=n
check('Java focused total 112',total==112)
for name in ['api-build.log','driver-final-build.log']:check(name+' changed module install','BUILD SUCCESS' in (ev/name).read_text(encoding='utf-8'))
ui=(ev/'ui-verified-tests.log').read_text(encoding='utf-8');check('UI 193 tests in 14 suites','14 passed, 14 total' in ui and '193 passed, 193 total' in ui)
check('no-fix lint','No lint errors found' in (ev/'ui-final-lint.log').read_text(encoding='utf-8'))
check('production UI build completed','Build complete' in (ev/'ui-verified-build.log').read_text(encoding='utf-8') and 'postbuild' in (ev/'ui-verified-build.log').read_text(encoding='utf-8'))
check('no full Cloud/Actions/qemu build claimed',not scope['fullCloudBuild'] and not scope['actionsDispatch'] and not scope['qemuBuild'])
for name in ['protected-windows.json','protected-wsl.json']:check(name+' preserved',read(name)['matchPrior'])
matrix=[]
for c in ['31','32']:
 d=read(c+'-validation.json');prefix='SNAPD1475-'+c
 check(c+' original bug baseline',d['baseline']['http']['http']==206 and d['baseline']['http']['magic'].startswith('514649fb') and d['baseline']['api']['snapshot']['url'].endswith('.qcow2' if c=='31' else '.raw'))
 check(c+' four API/UI file proofs',set(d['downloads'])=={'native-after','raw','native-ui','raw-ui'})
 for tag,item in d['downloads'].items():
  api=item['api'];db=item['db'];qemu=item['qemu'];kind='raw' if tag.startswith('raw') else 'qcow2'
  check(c+' '+tag+' response/DB/header/capacity/filename',item['verified'] and qemu['format']==kind and qemu['virtual-size']==api['size']==db['size']==1073741824 and item['fileSize']==api['physicalsize'] and api['filename'].endswith('.'+kind) and api['url'].endswith('/'+api['filename']) and db['url']==api['url'])
  check(c+' '+tag+' HTTP full/partial and unchanged data',' 200 ' in item['fullHeaders'] and ' 206 ' in item['partialHeaders'] and item['sourceSha256']==item['downloadSha256'] and item['sourceUnchanged'] and '1072693248' in item['dataPattern'] and bool(db['sourceRemoved']))
  check(c+' '+tag+' async preparation complete',d['extracts'][tag]['job']['jobstatus']==1 and api['state']=='DOWNLOAD_URL_CREATED')
  matrix.append({'cluster':c,'entry':tag,'sourceFormat':db['sourceformat'],'format':api['format'],'virtualSize':api['size'],'fileSize':api['physicalsize'],'passed':True})
 check(c+' source format differs from actual backup',d['downloads']['native-after' if c=='32' else 'raw']['db']['sourceformat']!=d['downloads']['native-after' if c=='32' else 'raw']['api']['format'])
 check(c+' four failure paths without URL',len(d['negative'])==4 and all(x['jobstatus']==2 and x['noSuccessfulUrl'] and x['db']['url'] is None and x['db']['status']=='BackedUp' for x in d['negative']))
 check(c+' anonymous API rejected',bool(next(iter(d['anonymous']['response'].values())).get('errorcode')))
 check(c+' HTTP403 handled',d['lifecycle']['deny']['after']=='403')
 check(c+' controlled expiry and regeneration',d['lifecycle']['expire']['answer']['result'] and d['lifecycle']['expire']['after']=='404' and d['lifecycle']['expire']['timerWaited'] is False and d['afterExpiry']['job']['jobstatus']==1)
 check(c+' Korean name safe fallback and deletion',d['korean']['snapshot']['name']=='다운로드 검증' and bool(re.fullmatch('[a-f0-9-]{36}\\.qcow2',d['korean']['snapshot']['filename'])) and d['korean']['http']==206 and d['korean']['afterDeleteHttp']==404)
 check(c+' double click and F5 never duplicate POST',d['doubleclick']['verified'] and d['doubleclick']['uiDoubleclickAddedJobs']==1 and d['doubleclick']['noResubmitAfterReload'] and len(d['doubleclick']['jobs'])==len(d['doubleclick']['afterReloadAndThemeJobs'])==2)
 state=d['state'];check(c+' active DB and references zero',all(state[k]==0 for k in ['snapshots','volumes','vm_instance','snapshotReferences','downloadReferences']))
 check(c+' physical files/trash/domains/exports zero',not state['primaryObjects'] and not state['trash'] and not any(state['domains'].values()) and not any(x['exists'] for x in state['secondary']+state['exportDirs']))
 check(c+' all issued links revoked',len(state['urls'])>=8 and all(x['status']==404 for x in state['urls']))
 check(c+' exactly one DELETE event per snapshot',len(state['usage'])==4 and all(x['count']==1 for x in state['usage']))
 check(c+' operational projections preserved/API zero',d['preservation']['status']=='PASS' and all(x['unchanged'] for x in d['preservation']['preserved']) and all(x['count']==0 for x in d['preservation']['fixtureActive']))
 check(c+' counters and test file cleanup',d['counters']['match'] and d['counters']['actual']==d['counters']['calculated'] and not d['backupsCleanup']['directoryExists'] and not d['filesCleanup']['directoryExists'])
 check(c+' deployed class/static hashes and server files',d['runtime']['management']['staticFiles']==921 and d['runtime']['management']['configPreserved'] and d['runtime']['management']['WEB-INF'] and d['runtime']['ssvm']['existingSnapshotTemplateMethodsPreserved'] and d['runtime']['mold']=='active' and d['runtime']['clientHttp']==200)
 check(c+' five agents up',len(d['ready']['hosts'])==5 and all(x['state']=='Up' for x in d['ready']['hosts']))
ui=read('ui-final-observations.json');check('10 final browser theme/format/preparation observations',len(ui)==10)
for key,value in ui.items():
 check(key+' VM shell/overlap/theme',value['width']==720 and value['noOverlap'] and value['background']==('rgb(34, 40, 47)' if key.endswith('-dark') else 'rgb(255, 255, 255)'))
 if '-prepare-' in key:check(key+' download guidance', '스냅샷 백업을 다운로드할 수 있습니다' in value['text'] and '새 볼륨을 생성' not in value['text'])
 else:check(key+' localized safe result','다운로드 준비가 완료' in value['text'] and '생성된 리소스 보기' not in value['text'] and 'Please click' not in value['text'] and value['rel']=='noopener noreferrer')
 check(key+' native screenshot exists',(base/'images'/(key+'.jpg')).is_file())
check('UI failed jobs do not provide a download link',all(not x['hasDownloadLink'] and '서버 작업 실패' in x['text'] and 'Truncated snapshot' in x['text'] for x in read('ui-failure-observations.json').values()))
check('UI cleanup lists show no fixture',all(x['noData'] and x['rows']==0 for x in read('ui-cleanup.json').values()))
manifest=read('file-manifest.json');check('report/evidence/script/image file manifest',all((base/p).is_file() and hashlib.sha256((base/p).read_bytes()).hexdigest()==sha for p,sha in manifest.items()))
result={'status':'PASS','checks':len(checks),'javaTests':112,'uiTests':193,'uiSuites':14,'downloadProofs':len(matrix),'matrix':matrix,'results':checks}
(ev/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('PASS',len(checks),'evidence checks; Java112/UI193; downloads8; active fixtures0')
