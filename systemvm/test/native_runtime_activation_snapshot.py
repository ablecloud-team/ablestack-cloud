# Licensed under the Apache License, Version 2.0. See NOTICE.
"""Opt-in SystemVM activation/snapshot regression; runtime pointers are private."""
from pathlib import Path
import argparse,base64,hashlib,json,os,shutil,subprocess,sys,tempfile
p=argparse.ArgumentParser()
p.add_argument('--vm-uuid',required=True)
p.add_argument('--candidate',type=Path,required=True)
p.add_argument('--instance-uuid',required=True)
p.add_argument('--transaction',required=True)
p.add_argument('--seed-root',type=Path,required=True)
args=p.parse_args()
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()==args.vm_uuid.lower()
live=Path('/opt/ablestack/storage-runtime')
state_source=Path('/var/lib/ablestack-storage/runtime-updates')/args.transaction
assert state_source.resolve().parent==Path('/var/lib/ablestack-storage/runtime-updates')
original=json.loads((state_source/'state.json').read_text())
assert original['phase'] in ('PREFLIGHT_OK','REJECTED')
root=Path(tempfile.mkdtemp(prefix='epic1459-activation-pretest-',dir='/var/tmp'));root.chmod(0o700)
def observations():
 result={'current':str((live/'current').resolve()),'previous':os.readlink(live/'previous') if (live/'previous').is_symlink() else None,'files':{}}
 for x in [args.seed_root,args.seed_root/'nfs-seed',args.seed_root/'team',args.seed_root/'team/shared-seed',args.seed_root/'team/smb-seed']:
  s=x.stat();v=[s.st_uid,s.st_gid,s.st_mode&0o7777,s.st_ino]
  if x.is_file():v.append(hashlib.sha256(x.read_bytes()).hexdigest())
  result['files'][str(x)]=v
 for x in [Path('/usr/local/lib/ablestack-storage/runtime_updater.py'),live/'current/lib/ablestack-storage/boot_reconcile.py']:
  result['files'][str(x)]=hashlib.sha256(x.read_bytes()).hexdigest()
 return result
before=observations()
driver=r"""
from pathlib import Path
import importlib.util,json,os,subprocess,sys,uuid
root=Path(sys.argv[1]);mode=sys.argv[2]
subprocess.run(['mount','--bind',str(root/'runtime'),'/opt/ablestack/storage-runtime'],check=True,capture_output=True)
sys.path.insert(0,'/usr/local/lib/ablestack-storage')
spec=importlib.util.spec_from_file_location('candidate_updater',root/'candidate.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class ActualActivation(m.RuntimeUpdater):
 def refresh_monitor(self):
  # systemd runs outside the private mount namespace; only this host side effect is excluded.
  self.collector_refreshes+=1
 def configuration_snapshot(self,request):
  super().configuration_snapshot(request)
  if mode=='failure':raise RuntimeError('injected snapshot failure')
engine=ActualActivation();engine.state_root=root/'state';engine.collector_refreshes=0
request=json.loads((root/'request.json').read_text())
old=engine.current_version();old_previous=engine.current_version(engine.previous)
if mode=='success':
 result=engine.activate(request)
 assert result['phase']=='ACTIVATED' and engine.current_version()==request['bundleVersion']
 journal=json.loads((engine.transaction_dir(request)/'configuration-before/pathbindings.json').read_text())
 operation=journal['operationid']
 assert str(uuid.UUID(operation))==operation
 assert operation==engine.snapshot_operation_id(request)==engine.snapshot_operation_id(dict(request))
 # Real health and owned configuration equality checks run in confirmation.
 confirmed=engine.activate(dict(request,confirmActivation=True))
 assert confirmed['phase']=='COMPLETE'
 assert engine.collector_refreshes==1
 print(json.dumps({'mode':mode,'phase':confirmed['phase'],'pathBindingOperationId':operation,'journalPlanCount':len(journal['plans']),'snapshotReal':True,'pointerPrivate':True,'monitorRestartExcluded':True}))
else:
 try:engine.activate(request)
 except m.RuntimeUpgradeError as error:
  assert error.code=='BASELINE_SNAPSHOT_FAILED',error.code
 else:raise AssertionError('injected failure not rejected')
 assert engine.current_version()==old and engine.current_version(engine.previous)==old_previous
 saved=engine.read_state(request);assert saved['phase']=='REJECTED'
 assert saved['errorCode']=='BASELINE_SNAPSHOT_FAILED'
 assert engine.collector_refreshes==0
 print(json.dumps({'mode':mode,'phase':saved['phase'],'errorCode':saved['errorCode'],'pointersUnchanged':True,'snapshotReal':True}))
"""
results=[]
try:
 assert (live/'releases'/original['bundleVersion']).is_dir()
 for mode in ('success','failure'):
  case=root/mode;case.mkdir(mode=0o700)
  shutil.copytree(live,case/'runtime',symlinks=True)
  shutil.copy2(args.candidate,case/'candidate.py')
  state=case/'state'/args.transaction;state.mkdir(parents=True,mode=0o700)
  for name in ('manifest.json','manifest.sig','bundle.tar.gz'):
   shutil.copy2(state_source/name,state/name)
  saved=dict(original,phase='PREFLIGHT_OK')
  (state/'state.json').write_text(json.dumps(saved));(state/'state.json').chmod(0o600)
  request={'transactionId':args.transaction,'instanceUuid':args.instance_uuid,'bundleVersion':original['bundleVersion']}
  (case/'request.json').write_text(json.dumps(request))
  (case/'driver.py').write_text(driver)
  completed=subprocess.run(['unshare','--mount','--propagation','private',sys.executable,str(case/'driver.py'),str(case),mode],capture_output=True,text=True,timeout=150)
  if completed.returncode:
   print(completed.stderr[-5000:],file=sys.stderr);raise SystemExit(completed.returncode)
  results.append(json.loads(completed.stdout))
 assert before==observations(),'live pointers/code or DATA changed'
 print(json.dumps({'success':True,'results':results,'livePointersCodeAndDataUnchanged':True,'scope':'actual activation -> actual RuntimeAdapter -> actual bound path journal -> fresh health; only collector restart excluded'}))
finally:
 assert root.parent==Path('/var/tmp') and root.name.startswith('epic1459-activation-pretest-')
 shutil.rmtree(root)
