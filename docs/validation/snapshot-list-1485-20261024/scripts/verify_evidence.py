import json,hashlib,re
from pathlib import Path
here=Path(__file__).resolve().parents[1];repo=here.parents[2]
def read(p):return json.loads((here/p).read_text(encoding='utf-8'))
s=read('evidence-summary.json');assert s['tests']==120 and s['suites']==7
assert '120 passed'in(here/'logs/ui-tests-final.log').read_text(encoding='utf-8')
assert 'No lint errors found'in(here/'logs/ui-lint.log').read_text(encoding='utf-8')
assert 'Build complete.'in(here/'logs/ui-build-column-fix.log').read_text(encoding='utf-8')
for f in read('source-manifest.json'):assert hashlib.sha256((repo/f['path']).read_bytes()).hexdigest()==f['sha256'],f['path']
ui=read('browser/ui-validation.json')
for c in ['31','32']:
 api=read('evidence/'+c+'-api-db-validation.json');assert len(api['cases'])==30 and all(x['pass']for x in api['cases'])
 acl=read('evidence/'+c+'-acl.json');assert len(acl)==5 and all(x['deniedOrEmpty']for x in acl)
 before=read('evidence/'+c+'-db-before.json');assert len(before['snapshots'])==26 and len(before['references'])==(26 if c=='31'else 52)
 d=read('evidence/'+c+'-ui-deploy-columns.json');assert d['verified']and d['clientHttp']==200 and d['webInf']and d['mold']=='active'
 final=read('evidence/'+c+'-final-state.json');assert not any(final['database'].values())and not final['physical']['remaining']and not final['physical']['rbdTrash']
 assert read('evidence/'+c+'-preservation.json')['status']=='PASS'
 db=read('evidence/'+c+'-ui-db-oracle.json');assert db['activeAfterUi']==25 and len(db['snapshots'])==27 and all(x['status']==1 for x in db['jobs'])
 for phase in ['main','page2','page-size50','advanced-daily','columns-persist','list503-retained','read403-clears','first-failure','late-query-ignored','menu-recovery-stays-closed','size-boundary','copies','owner-scope','owner-project','other-scope','create-entry','bulk-selected','ui-bulk-delete','cleanup-ui']:
  assert any(x['name']==c+'-'+phase for x in ui),(c,phase)
 for mode in ['light','dark']:
  for w in [390,768,1280]:
   e=next(x for x in ui if x['name']==c+'-'+mode+'-'+str(w));p=e['projection'];assert p['viewport']['width']==w and p['noOverlap']and p['pageOverflowContained'],e['name']
   assert p['cellStyle']['fontSize']=='14px'and p['cellStyle']['padding']=='12px 8px'
   assert(here/'browser'/(c+'-'+mode+'-'+str(w)+'.png')).stat().st_size>10000
 for name,command,count in [('ui-create','createSnapshot',1),('bulk-delete','deleteSnapshot',2)]:
  n=read('browser/'+c+'-'+name+'.json')['requests'];real=[x for x in n if x['command']==command and x['method']=='POST'and x['args'].get('dryrun')!='true'];assert len(real)==count,(c,name,len(real))
  if command=='deleteSnapshot':assert all(x['args'].get('expectedscopehash')for x in real)
 assert any(x['name']==c+'-bulk-selected'and x['dom'].count('checkbox [checked]')==2 for x in ui)
 assert next(x for x in ui if x['name']==c+'-first-failure')['projection']['pagination']is None
 assert next(x for x in ui if x['name']==c+'-read403-clears')['projection']['rows']==[]
 assert next(x for x in ui if x['name']==c+'-menu-recovery-stays-closed')['projection']['menuCount']==0
 for phase in ['pending-selection','list503-retained']:
  e=next(x for x in ui if x['name']==c+'-'+phase);rows=e['projection']['rows'];assert len(rows)==20 and sum(x['selected']for x in rows)==2 and all(x['disabled']for x in rows)
 boundary=next(x for x in ui if x['name']==c+'-size-boundary')['projection']['rows']
 assert all(value in boundary[i]['cells']for i,value in enumerate(['0 B','미확인','1.31 MiB']))
 late=next(x for x in ui if x['name']==c+'-late-query-ignored')['projection']['rows'];assert len(late)==1 and any('other-00'in v for v in late[0]['cells'])
 other=next(x for x in ui if x['name']==c+'-other-scope')['projection']['rows'];assert len(other)==1 and any('other-00'in v for v in other[0]['cells'])
 focus=next(x for x in ui if x['name']==c+'-keyboard-focus')['focus'];assert focus['tag']=='TR'and focus['tabIndex']==0

assert read('evidence/protection-verified.json')['windows']['preserved']and read('evidence/protection-verified.json')['wsl']['preserved']
for f in here.rglob('*'):
 if f.is_file()and f.suffix in['.json','.jsonl','.md','.mjs','.py','.log','.txt']:
  assert not re.search(r'Ablecloud[0-9]+!',f.read_text(encoding='utf-8')),'Credential leak '+str(f)
print('PASS: 120 tests, 30 API/DB + 5 ACL per cluster, deployed UI/layout/job evidence and zero residuals')
