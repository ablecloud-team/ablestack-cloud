import json,hashlib,re
from pathlib import Path
base=Path(__file__).resolve().parents[1];repo=base.parents[2];ev=base/'evidence';checks=[]
def check(name,value):
 assert value,name
 checks.append({'check':name,'passed':True})
def read(name):return json.loads((ev/name).read_text())
source=read('source-sha256.json');check('eight source/test files match',len(source)==8 and all(hashlib.sha256((repo/p).read_bytes()).hexdigest()==v for p,v in source.items()))
matrix=read('matrix.json');check('8 format/storage/type combinations',len(matrix)==8 and all(x['status']=='PASS' for x in matrix))
tests=[]
for log,n in [('core-tests.log',2),('secondary-complete-tests.log',45),('kvm-complete-tests.log',69)]:
 s=(ev/log).read_text();rows=re.findall(r'Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0',s);check(log+' success',bool(rows) and int(rows[-1])==n and 'BUILD SUCCESS' in s);tests.append(n)
ui=read('ui-observations.json');check('UI metadata 4 observations',len(ui['templates'])==4 and all(x['ready'] for x in ui['templates']));check('new VM UI starts 4',len(ui['bootedVMs'])==4 and all(x['state']=='Running' for x in ui['bootedVMs']))
check('preserved Windows/WSL FT trees',all(read('protected-'+x+'.json')['matchPrior'] for x in ['windows','wsl']))
metadata_count=0;data_count=0;root_comparisons=0
for c in ['31','32']:
 meta=read(c+'-metadata-verified.json');metadata_count+=len(meta);check(c+' header/API/DB/properties/extension',len(meta)==6 and all(x['headerApiDbPropertiesExtensionAgree'] for x in meta))
 data=read(c+'-data-verification.json');data_count+=len(data);check(c+' data patterns',len(data)>0 and all(x['verified'] for x in data))
 roots=read(c+'-root-full-compare.json');root_comparisons+=len(roots);check(c+' full root compare',len(roots)==2 and all(x['identical'] for x in roots))
 baseline=read(c+'-root-source-seed-guest.json')
 for scenario in ['root-boot','root-raw-boot']:
  guest=read(c+'-'+scenario+'-verify-guest.json');check(c+' '+scenario+' real guest boot',guest['vmid']!=baseline['vmid'] and guest['state']=='Running' and guest['guestAgent'] and bool(guest['guest-get-osinfo']) and bool(guest['guest-get-fsinfo']))
 for mode in ['unsupported','corrupt','missing']:
  neg=read(c+'-negative-'+mode+'.json');check(c+' '+mode+' not Ready',neg['noReady'] and neg['job']['jobstatus']==2)
 state=read(c+'-final-state.json');check(c+' active DB zero',all(state[k]==0 for k in ['snapshots','volumes','vm_instance','vm_template','snapshotReferences']))
 check(c+' physical/cache/domain zero',not state['primaryObjects'] and not state.get('trash',[]) and not state['primaryTemplateCache'] and not any(x['exists'] for x in state['secondary']) and not any(state['domains'].values()))
 check(c+' counters',read(c+'-counters-verified.json')['match'] and all(x['count']==1 for x in state['usage']))
 keep=read(c+'-preservation.json');check(c+' operations preserved/API zero',keep['status']=='PASS' and all(x['unchanged'] for x in keep['preserved']) and all(x['count']==0 for x in keep['fixtureActive']))
 check(c+' fixture backups removed',read(c+'-fixture-backups-cleanup.json')['directoryExists']==False)
 runtime=read(c+'-runtime-verified.json');check(c+' active runtime',runtime['WEB-INF'] and runtime['mold']=='active' and runtime['http']=='200' and len(runtime['agents'])==3)
 check(c+' five agents Up',len(read(c+'-agents-after-deploy.json'))==5 and all(x['state']=='Up' for x in read(c+'-agents-after-deploy.json')))
 for h in ['1','2','3']:
  deploy=read(c+'-'+h+'-agent-deploy.json');check(c+'-'+h+' deployment preservation',deploy['vmsUnchanged'] and deploy['ftctlUnchanged'] and all(x['otherEntriesUnchanged'] for x in deploy['jars']))
 prior=read(c+'-pre-legacy-cleanup-state.json');late=['407','408'] if c=='31' else ['236']
 check(c+' final failure auto cleanup',all(not x['exists'] for x in prior['secondary'] if x['path'].rsplit('/',1)[-1] in late))
check('UI failed job/API agreement',read('32-ui-negative-job.json')['jobstatus']==2 and ui['failure']['jobid']==read('32-ui-negative-job.json')['jobid'])
check('116 module tests',sum(tests)==116)
result={'status':'PASS','issue':1474,'epic':1473,'tests':sum(tests),'testClasses':8,'formatCombinations':len(matrix),'metadataCases':metadata_count,'patternCases':data_count,'fullRootComparisons':root_comparisons,'newRootVMsBooted':4,'manualLegacyRemediation':{c:read(c+'-legacy-artifact-cleanup.json') for c in ['31','32']},'fullCloudBuild':'not requested, not run','uiSourcesChanged':False,'checks':checks}
(ev/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
manifest={str(p.relative_to(base)):hashlib.sha256(p.read_bytes()).hexdigest() for p in base.rglob('*') if p.is_file() and p.name!='files-sha256.json'}
(ev/'files-sha256.json').write_text(json.dumps(manifest,indent=2)+'\n');print('PASS',len(checks),'checks;',sum(tests),'tests;',len(matrix),'format combinations')
