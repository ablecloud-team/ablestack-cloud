import json,hashlib,re
from pathlib import Path
d=Path(__file__).resolve().parents[1];repo=d.parents[2];checks=[]
def read(p):return json.loads((d/p).read_text())
def check(name,condition):
 assert condition,name
 checks.append(name)
for x in read('manifest.json'):check('artifact:'+x['path'],hashlib.sha256((d/x['path']).read_bytes()).hexdigest()==x['sha256'])
for x in read('source-sha256.json'):check('source:'+x['path'],hashlib.sha256((repo/x['path']).read_bytes()).hexdigest()==x['sha256'])
check('api module build','BUILD SUCCESS'in(d/'api-build.log').read_text());java=(d/'server-tests.log').read_text();check('server Checkstyle and 67 tests','Starting audit'in java and 'Audit done.'in java and 'Tests run: 67, Failures: 0, Errors: 0, Skipped: 0'in java and 'BUILD SUCCESS'in java)
check('93 UI tests',bool(re.search(r'Tests:\s+93 passed, 93 total',(d/'ui-tests.log').read_text())))
check('UI lint','No lint errors found'in(d/'ui-lint.log').read_text());check('UI build','Build complete'in(d/'ui-build.log').read_text())
for c in ['31','32']:
 x=read(c+'-validation.json');final=next(s['id']for s in x['fixtures']['snapshots']if s['scenario']=='final')
 for phase,count,usage in [('finalBefore',0,2490368),('finalPartial',1,4980736),('finalComplete',2,7471104),('finalReplay',2,7471104)]:
  p=x[phase];check(c+':'+phase,p['pass']and len(p['zoneReferences'])==count and len(p['usageEvents'])==count and p['secondaryUsage']==usage and p['expectedPhysicalBytes']==usage)
 copies=[p for p in x['physical']if p.get('snapshot')==final and p.get('role')=='Image'];check(c+':physical images',len(copies)==3 and len({p['sha256']for p in copies})==1 and all(p['verified']and p['info']['virtual-size']==1073741824 for p in copies))
 check(c+':archive',x['archive']['secondaryUnchanged']and(c!='32'or x['archive']['primaryRbdSnapshotRemoved']['absent']))
 check(c+':no fixture database residual',not any(x['finalState']['database'].values()));check(c+':no physical residual',not x['finalState']['primary']['remaining']and not x['finalState']['secondary']['remaining'])
 check(c+':operational projection',x['preservation']['status']=='PASS'and all(p['unchanged']for p in x['preservation']['preserved']))
 check(c+':quota',x['quota']['secondaryMatches']and x['quota']['snapshotMatches']);check(c+':runtime',len(x['runtime']['classes'])==8 and x['runtime']['staticFiles']==921 and x['runtime']['staticVerified']and x['runtime']['webInf']and x['runtime']['configPreserved']and x['runtime']['http']==200)
 check(c+':original agents',len(x['housekeeping']['originalHosts'])==5 and all(p['state']=='Up'for p in x['housekeeping']['originalHosts']))
 for phase,expected in [('partial',2),('retry',1)]:
  net=read('browser/'+c+'-final-'+phase+'-network.json')['entries'];mut=[p for p in net if p['command']=='copySnapshot'];check(c+':'+phase+' single POST',len(mut)==1 and mut[0]['params']['id']==final);job=mut[0]['response']['copysnapshotresponse']['jobid'];check(c+':'+phase+' job status',any(p.get('response',{}).get('queryasyncjobresultresponse',{}).get('jobstatus')==expected for p in net if p['command']=='queryAsyncJobResult'and p['params']['jobid']==job))
 check(c+':empty target UI',not read('browser/'+c+'-copy-empty.json')['enabled'])
for f in ['protected-windows-final.json','protected-wsl-final.json']:check(f,read(f)['unchanged'])
for f in ['browser/31-copy-parameter-keys.json','browser/32-final-copy-parameter-keys.json']:check(f,all(not any(k.lower()in ['usestorageReplication'.lower(),'storageids']for k in p['keys'])for p in read(f)))
secret=re.compile(rb'(?i)(ablecloud(?:2026|1)!|-----BEGIN [A-Z ]*PRIVATE KEY)')
for p in d.rglob('*'):
 if p.is_file()and p.suffix!='.jpg':check('credential scan:'+str(p.relative_to(d)),not secret.search(p.read_bytes()))
out={'status':'PASS','checks':len(checks),'scope':'offline evidence, source/artifact hashes, final live results, test/build logs and credential scan'};(d/'verification.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps(out))
