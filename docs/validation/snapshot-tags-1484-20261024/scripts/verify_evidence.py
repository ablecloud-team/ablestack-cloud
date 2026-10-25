import json,hashlib,sys,re
from pathlib import Path
here=Path(__file__).resolve().parents[1];repo=here.parents[2]
def read(p):return json.loads((here/p).read_text(encoding='utf-8'))
summary=read('evidence-summary.json');assert summary['tests']==80 and summary['suites']==6
assert '80 passed' in (here/'logs/ui-tests-final.log').read_text(encoding='utf-8')
assert 'No lint errors found' in (here/'logs/ui-lint-final.log').read_text(encoding='utf-8')
assert 'Build complete.' in (here/'logs/ui-build-final.log').read_text(encoding='utf-8')
for x in read('source-manifest.json'):assert hashlib.sha256((repo/x['path']).read_bytes()).hexdigest()==x['sha256'],x['path']
ui=read('browser/ui-validation.json')
for c in ['31','32']:
 assert len(read('evidence/'+c+'-api-db-validation.json')['cases'])==49
 assert not any(read('evidence/'+c+'-final-state.json')['database'].values())
 assert read('evidence/'+c+'-preservation.json')['status']=='PASS'
 dep=read('evidence/'+c+'-ui-deploy-final.json');assert dep['verified']and dep['clientHttp']==200 and dep['mold']=='active'and dep['webInf']
 db=read('evidence/'+c+'-ui-db-oracle.json');assert db['jobCount']>=13 and all(x['status']in [1,2]for x in db['jobs'])
 for name in ['job-lookup-retry','late-post-receipt']:
  n=read('browser/'+c+'-'+name+'.json')['requests'];assert len([x for x in n if x['command']=='createTags'])==1,(c,name)
  ids={x['args']['jobid']for x in n if x['command']=='queryAsyncJobResult'};assert len(ids)==1,(c,name,ids)
 n=read('browser/'+c+'-no-job-no-resubmit.json')['requests'];assert n and all(x['command']=='listTags'and x['method']=='GET'for x in n)
 assert read('evidence/'+c+'-submission-db-control.json')['joblessRequestsReachedServer']==0
 assert not any(x['command']=='queryAsyncJobResult'for x in n)
 n=read('browser/'+c+'-project-owner-create-delete.json')['requests'];assert len([x for x in n if x['method']=='POST'])==2
 assert all(x['args'].get('projectid') not in [None,'0','-1']for x in n if x['command']!='queryAsyncJobResult')
 for kind in ['dark','light']:
  f=here/'browser'/(c+'-'+kind+'-final.png');assert f.stat().st_size>10000
  entries=[x for x in ui if x['name']==c+'-'+kind+'-final'];assert len(entries)==1
  layout=entries[0]['layout'];assert layout['noOverlap']and layout['inputFullWidth']and layout['closeInheritsColor'],(c,kind,layout)
 assert any(x['name']==c+'-domainadmin-readonly'and x['createButtonCount']==0 and x['tagCloseCount']==0 for x in ui)
 assert any(x['name']==c+'-project-switch-completed'and x['tagRegionCount']==0 for x in ui)
assert read('evidence/protection-verified.json')['windows']['preserved']and read('evidence/protection-verified.json')['wsl']['preserved']
for f in here.rglob('*'):
 if f.is_file()and f.suffix in ['.json','.jsonl','.md','.mjs','.py','.log','.txt']:
  text=f.read_text(encoding='utf-8');assert not re.search(r'Ablecloud[0-9]+!',text),'Credential leak: '+str(f)
print('PASS: 80 tests, 49 cases per cluster, real UI job/owner traces, final assets/theme/layout, cleanup and preservation')
