from pathlib import Path
import json,subprocess,time,traceback
ROOT=Path('/run/epic1443-guard-design-probe')
ns={};s=(ROOT/'worker.py').read_text();exec(s[:s.index("ROOT.mkdir(mode")],ns)
run=ns['run'];snapshot=ns['snapshot'];oldguard=ns['SOURCE_GUARD']
PREFIX='epic1443-guard-design-extra-'
def state(u):
 p=run(['systemctl','show',u,'-p','LoadState','-p','ActiveState','-p','SubState','-p','Result','-p','Job'],check=False)
 return dict(l.split('=',1) for l in p.stdout.splitlines() if '=' in l)
def gate(early,stages):
 e=state(early);m=[state(u) for u in stages]
 if e.get('LoadState')!='loaded' or e.get('ActiveState')=='failed':return 'FAILED',e,m
 if (e.get('ActiveState'),e.get('SubState'),e.get('Result'))!=('active','exited','success'):return 'PENDING',e,m
 for x in m:
  if x.get('LoadState')=='not-found':continue
  if x.get('LoadState')!='loaded':return 'FAILED',e,m
  if x.get('Job') not in ('0','') or x.get('ActiveState') in ('activating','deactivating') or x.get('SubState') in ('running','start','start-pre','start-post'):return 'PENDING',e,m
 return 'READY',e,m
out={'status':'RUNNING','before':snapshot(),'cases':{}};created=[]
try:
 names={k:PREFIX+k+'.service' for k in ['early','local','meta','failed-meta']}
 text={
 'early':'[Service]\nType=oneshot\nRemainAfterExit=yes\nExecStart=/bin/true\n',
 'local':'[Service]\nType=oneshot\nRemainAfterExit=yes\nExecStart=/bin/sleep 4\n',
 'meta':'[Unit]\nAfter='+names['local']+'\nRequires='+names['local']+'\n[Service]\nType=oneshot\nRemainAfterExit=yes\nExecStart=/bin/true\n',
 'failed-meta':'[Service]\nType=oneshot\nExecStart=/bin/false\n'}
 for k,n in names.items():
  p=Path('/run/systemd/system')/n;assert not p.exists();p.write_text(text[k]);created.append(n)
 run(['systemctl','daemon-reload'])
 run(['systemctl','start','--no-block',names['local']]);run(['systemctl','start','--no-block',names['meta']]);run(['systemctl','start',names['early']])
 old=run(['bash','-c',oldguard.replace('cloud-early-config.service',names['early'])],check=False).returncode
 first=gate(names['early'],[names['local'],names['meta']])
 assert first[0]=='PENDING'
 assert first[2][1].get('Job') not in ('0','')
 assert first[2][1].get('ActiveState')=='inactive'
 t=time.monotonic()
 while time.monotonic()-t<10:
  last=gate(names['early'],[names['local'],names['meta']])
  if last[0]!='PENDING':break
  time.sleep(.1)
 assert last[0]=='READY'
 out['cases']['queuedNetworkStage']={'oldRc':old,'first':first,'last':last}
 run(['systemctl','start',names['failed-meta']],check=False)
 done=gate(names['early'],[names['failed-meta']])
 assert done[0]=='READY'
 out['cases']['finishedNoDatasourceIsNotStaticFailure']=done
 missing=gate(PREFIX+'absent-required.service',[names['meta']])
 assert missing[0]=='FAILED'
 out['cases']['missingRequiredEarlyUnit']=missing
 optional=gate(names['early'],[PREFIX+'absent-optional.service'])
 assert optional[0]=='READY'
 out['cases']['noInstalledCloudInitIsNotNewDhcpRequirement']=optional
 # Isolated monotonic retry design contract. No product dispatcher, database or VM lifecycle is called.
 def wait_policy(mode,responder,budget=360):
  c={'now':0.0,'calls':[]}
  if mode!='STATIC':return {'outcome':'SKIPPED','calls':[],'elapsed':0}
  while c['now']<budget:
   left=budget-c['now'];rpc=max(1,min(60,int(left)))
   c['calls'].append(rpc)
   verdict,duration=responder(c['now'],rpc)
   c['now']+=duration
   if verdict in ['READY','FAILED']:return {'outcome':verdict,'calls':c['calls'],'elapsed':c['now']}
   c['now']+=min(2,max(0,budget-c['now']))
  return {'outcome':'PENDING_TIMEOUT','calls':c['calls'],'elapsed':c['now']}
 checks={}
 checks['dhcpNoDispatch']=wait_policy('DHCP',lambda *_:(_ for _ in ()).throw(Exception('DHCP must not dispatch')))
 checks['normal126Seconds']=wait_policy('STATIC',lambda t,r:('READY' if t>=126 else 'PENDING',1))
 checks['perpetualPending']=wait_policy('STATIC',lambda t,r:('PENDING',min(r,60)))
 checks['terminalFailure']=wait_policy('STATIC',lambda t,r:('FAILED',1))
 checks['lastRpcBoundedByRemaining']=wait_policy('STATIC',lambda t,r:('PENDING',r),budget=65)
 assert checks['dhcpNoDispatch']['calls']==[]
 assert checks['normal126Seconds']['outcome']=='READY'
 assert checks['perpetualPending']['outcome']=='PENDING_TIMEOUT' and checks['perpetualPending']['elapsed']==360
 assert checks['terminalFailure']['calls']==[60]
 assert checks['lastRpcBoundedByRemaining']['calls']==[60,3] and checks['lastRpcBoundedByRemaining']['elapsed']==65
 out['cases']['deadlinePolicyPrototype']=checks
 out['status']='PASS'
except Exception:
 out['status']='PROBE_ERROR';out['error']=traceback.format_exc()
finally:
 for n in reversed(created):run(['systemctl','stop',n],check=False)
 for n in created:
  p=Path('/run/systemd/system')/n
  if p.exists():p.unlink()
 if created:run(['systemctl','daemon-reload']);run(['systemctl','reset-failed']+created,check=False)
 out['after']=snapshot();out['unchanged']={k:out['before'][k]==out['after'][k] for k in out['before']}
 out['testUnitsRemoved']=all(not (Path('/run/systemd/system')/n).exists() for n in created)
 (ROOT/'extra-result.json').write_text(json.dumps(out,indent=2))
 print(json.dumps({k:v for k,v in out.items() if k not in ['before','after']}))
