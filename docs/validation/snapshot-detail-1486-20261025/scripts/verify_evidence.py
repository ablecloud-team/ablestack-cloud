import json, hashlib, re
from pathlib import Path
here=Path(__file__).resolve().parents[1];repo=here.parents[2]
def read(p):return json.loads((here/p).read_text())
s=read('evidence-summary.json');assert s['tests']==162 and s['suites']==10 and s['sourceFiles']==14
assert '162 passed' in (here/'logs/ui-tests-final.log').read_text()
assert 'No lint errors found' in (here/'logs/ui-lint.log').read_text()
assert 'Build complete.' in (here/'logs/ui-build.log').read_text()
for f in read('source-manifest.json'):assert hashlib.sha256((repo/f['path']).read_bytes()).hexdigest()==f['sha256'],f['path']
for f in read('evidence-manifest.json'):assert hashlib.sha256((here/f['path']).read_bytes()).hexdigest()==f['sha256'],f['path']
for c in ['31','32']:
 a=read('evidence/'+c+'-detail-api-oracle.json');assert len(a['cases'])==44 and all(x['pass']for x in a['cases'])
 a=read('evidence/'+c+'-readonly-api.json');assert len(a)==5 and all(x['pass']for x in a)
 a=read('evidence/'+c+'-ui-db-oracle.json');assert len(a['cases'])==(39 if c=='31'else 40)and all(x['pass']for x in a['cases']);assert len(a['jobs'])==2 and all(x['status']==1 for x in a['jobs'])
 assert 'OS bytes not verified' in a['clipboardLimitation']
 b=read('evidence/'+c+'-db-before.json');assert len(b['snapshots'])==7 and len(b['volumes'])==6 and len(b['references'])==(7 if c=='31'else 13)
 a=read('evidence/'+c+'-physical-before.json');assert len(a)==7 and all(x['physicalMatchesApiDb'] and x['fileSize']==x['dbPhysical'] for x in a)
 a=read('evidence/'+c+'-ui-deploy.json');assert a['files']==924 and a['verified'] and a['webInf'] and a['clientHttp']==200 and a['mold']=='active' and all(a['markers'].values())
 a=read('evidence/'+c+'-final-state.json');assert not any(a['database'].values());assert a['chainSetting']=='false';assert a['physical']['secondaryFilesChecked']==7 and a['physical']['primaryVolumesChecked']==6 and not a['physical']['remaining'] and not a['physical']['rbdTrash'];assert all(x['fixtureDomainAbsent']for x in a['libvirt'])
 assert read('evidence/'+c+'-preservation.json')['status']=='PASS'
 for mode in ['light','dark']:
  for w in [390,768,1280]:
   a=read('browser/'+c+'-'+mode+'-'+str(w)+'.json');assert a['layout']['width']==w and a['layout']['noOverlap'] and a['layout']['pageOverflowContained'];assert all(x['weight']=='700'and x['margin']=='5px'for x in a['layout']['items'])
   png=(here/'browser'/(c+'-'+mode+'-'+str(w)+'.png')).read_bytes();assert int.from_bytes(png[16:20],'big')==w
 assert '전체 0 개 항목' in (here/'browser'/(c+'-cleanup-ui.txt')).read_text()
a=read('evidence/protection-verified.json');assert a['windows']['preserved'] and a['wsl']['preserved']
for f in here.rglob('*'):
 if f.is_file()and f.suffix in ['.json','.jsonl','.md','.mjs','.py','.log','.txt']:
  assert not re.search(r'Ablecloud[0-9]+!',f.read_text()),'Credential leak '+str(f)
print('PASS: source/evidence hashes, 162 tests, 44+5 API and 39/40 UI-DB cases, 12 screens, deployed assets and zero residuals')
