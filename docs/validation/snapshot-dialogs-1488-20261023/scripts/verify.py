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
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parent.parent
e=root/'evidence'
def read(name): return json.loads((e/name).read_text(encoding='utf-8'))
matrices=[]
for c in ['31','32']:
 for action in ['create','restore']:
  for theme in ['light','dark']:
   for width in [320,390,768,1280]:
    name=f'{c}-{action}-{theme}-{width}-final'
    m=read('ui/'+name+'.json')
    assert m['modal'] and m['dark']==(theme=='dark'),name
    assert m['viewport']['width']==width and m['rect']['width']==(width-24 if width<600 else 720),name
    assert not m['overlaps'] and not m['nestedForms'] and not m['summaryTables'],name
    assert m['bodyOverflow']['scrollWidth']<=m['bodyOverflow']['clientWidth'],name
    assert m['documentOverflow']['scrollWidth']<=m['documentOverflow']['clientWidth'],name
    minimum=min(m['minimumContrast'],m['leafText']['minimumContrast'])
    assert minimum>=4.5,(name,minimum)
    assert all(g==24 for g in m['fieldGaps']+m['stackGaps']),(name,m['fieldGaps'],m['stackGaps'])
    assert (e/('ui/'+name+'.png')).is_file(),name
    matrices.append({'name':name,'width':m['rect']['width'],'minimumContrast':minimum,'overlap':False})
  focus=read(f'ui/{c}-{action}-escape-focus-final.json')
  assert focus['tag']=='BUTTON' and '작업' in focus['text'],focus
 for scenario in ['source','normal-ui-volume','lost-ui-volume','keyboard-ui-volume']:
  assert all(x['verifiedPattern']=='0x5a' for x in read(f'{c}-pattern-verify-{scenario}.json'))
 assert all(x['verifiedPattern']=='0xa5' for x in read(f'{c}-pattern-verify-changed-source.json'))
 assert read(f'{c}-ui-db-verified.json')['status']=='PASS'
 assert all(x==0 for x in read(f'{c}-final-counters.json').values())
 assert read(f'{c}-preservation.json')['status']=='PASS'
 assert read(f'{c}-physical-cleanup-verification.json')['status']=='PASS'
 assert read(f'{c}-ui-deploy-verified.json')['managementPidUnchanged']
 assert read(f'{c}-ui-deploy-verified.json')['files']==921
 assert len(read(f'{c}-runtime-smoke.json'))==8
for name in ['protected-windows-verification.json','protected-wsl-verification.json']:
 m=read(name)
 assert all(v is True for k,v in m.items() if k.endswith('Unchanged')),m
repo=root.parents[2]
for file,expected in read('final-source-sha256.json').items():
 assert hashlib.sha256((repo/file).read_bytes()).hexdigest()==expected,file
assert read('resource-counters-verification.json')['status']=='PASS'
assert '__FINAL' not in (root/'report.ko.md').read_text(encoding='utf-8')
for file,expected in read('vm-components-before.json').items():
 assert hashlib.sha256((repo/file).read_bytes()).hexdigest()==expected,file
result={'status':'PASS','matrixCombinations':len(matrices),'minimumContrast':min(x['minimumContrast'] for x in matrices),'matrices':matrices,'scope':'volume creation and snapshot revert dialogs only','fullCloudBuild':False,'commonVmSourcesUnchanged':True,'cleanupAndPreservation':'PASS'}
(e/'verification.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
files={str(p.relative_to(root)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.rglob('*')) if p.is_file() and p.name!='files-sha256.json'}
(e/'files-sha256.json').write_text(json.dumps(files,indent=2)+'\n',encoding='utf-8')
print('PASS',len(matrices),'UI combinations, physical data, cleanup, deployment, preservation')
