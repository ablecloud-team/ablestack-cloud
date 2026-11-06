from pathlib import Path
import os,sys,json,hashlib,subprocess,shutil,tempfile,importlib.util,contextlib
sys.dont_write_bytecode=True
stage=Path('/var/tmp/epic1446-design-20261024')
assert stage.resolve()==stage and stage.is_dir()
sentinel=stage/'probe-ran'
assert not sentinel.exists(),'Do not rerun a design probe over an existing result'
sentinel.write_text('one design execution')
spec=importlib.util.spec_from_file_location('installed_runtime', '/usr/local/lib/ablestack-storage/runtime_updater.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
builder=stage/'build-storage-runtime-bundle.sh'
observations=[]
@contextlib.contextmanager
def sandbox(label):
 with tempfile.TemporaryDirectory(prefix=label+'-',dir=stage) as temporary:
  root=Path(temporary);root.chmod(0o700)
  folders={k:root/n for k,n in [('runtime','runtime'),('state','state'),('bin','bin'),('source','source'),('out','output')]}
  for p in folders.values():p.mkdir(mode=0o700)
  trust=folders['runtime']/'trusted-keys';trust.mkdir(mode=0o700)
  env={'ABLESTACK_STORAGE_RUNTIME_ROOT':str(folders['runtime']),'ABLESTACK_STORAGE_RUNTIME_STATE_ROOT':str(folders['state']),'ABLESTACK_STORAGE_RUNTIME_TRUSTED_KEYS':str(trust),'ABLESTACK_STORAGE_RUNTIME_ENTRYPOINT_ROOT':str(folders['bin']),'ABLESTACK_STORAGE_RUNTIME_LOCK':str(root/'lock')}
  old={k:os.environ.get(k) for k in env};os.environ.update(env)
  private=root/'fixture-private.pem'
  subprocess.run(['openssl','genpkey','-algorithm','ED25519','-out',str(private)],check=True,capture_output=True);private.chmod(0o600)
  subprocess.run(['openssl','pkey','-in',str(private),'-pubout','-out',str(trust/'probe-test.pem')],check=True,capture_output=True)
  def scripts(directory,value):
   for name in m.ENTRYPOINTS:
    p=directory/name;p.write_text('#!/bin/bash\nset -euo pipefail\necho '+value+'\n');p.chmod(0o755)
  scripts(folders['bin'],'baseline')
  u=m.RuntimeUpdater();u.execute('bootstrap',{})
  def prepare(version):
   scripts(folders['source'],version)
   subprocess.run([str(builder),'--version',version,'--private-key',str(private),'--key-id','probe-test','--source-root',str(folders['source']),'--output-dir',str(folders['out'])],env={**os.environ,'SOURCE_DATE_EPOCH':'0'},check=True,capture_output=True)
   archive=folders['out']/('ablestack-storage-runtime-'+version+'.tar.gz');manifest=folders['out']/'manifest.json';signature=folders['out']/'manifest.sig'
   request={'transactionId':'probe-'+version,'bundleVersion':version,'archiveSha256':m.sha256_file(archive),'manifestSha256':m.sha256_file(manifest),'totalSize':archive.stat().st_size,'manifestSize':manifest.stat().st_size,'signatureSize':signature.stat().st_size}
   u.execute('begin',request);target=u.transaction_dir(request)
   for a,b in [(archive,'bundle.tar.gz'),(manifest,'manifest.json'),(signature,'manifest.sig')]:shutil.copyfile(a,target/b)
   u.execute('finalize',request);u.execute('verify',request);u.execute('preflight',request)
   return request
  try:yield u,prepare,root
  finally:
   private.unlink(missing_ok=True)
   for k,v in old.items():
    if v is None:os.environ.pop(k,None)
    else:os.environ[k]=v

with sandbox('normal') as (u,prepare,root):
 req=prepare('v2');activation=u.execute('activate',req);rollback=u.execute('rollback',req)
 observations.append({'case':'existing signed activation and rollback','keyScope':'isolated fixture trust only','activated':activation['currentVersion'],'rolledBack':rollback['currentVersion'],'normalMechanismWorks':activation['currentVersion']=='v2' and rollback['currentVersion'].startswith('bootstrap-')})

with sandbox('signature') as (u,prepare,root):
 req=prepare('v2');manifest=u.transaction_dir(req)/'manifest.json';v=json.loads(manifest.read_text());v['buildCommit']='tampered';manifest.write_text(json.dumps(v))
 try:u.verify_signature(u.transaction_dir(req),v);code='ACCEPTED'
 except m.RuntimeUpgradeError as e:code=e.code
 observations.append({'case':'signature tamper direct verification','result':code,'expectedRejection':code=='SIGNATURE_INVALID'})

with sandbox('activation-recheck') as (u,prepare,root):
 req=prepare('v2');path=u.transaction_dir(req)/'manifest.json';v=json.loads(path.read_text());v['runtimeAbiVersion']='incompatible';v['serviceImpact']='MAINTENANCE';path.write_text(json.dumps(v))
 state=u.read_state(req)
 proposed='MANIFEST_CHANGED_AFTER_PREFLIGHT' if m.sha256_file(path)!=state['manifestSha256'] else 'UNCHANGED'
 actual=u.execute('activate',req)
 observations.append({'case':'manifest changed after preflight','existingResult':actual['phase'],'existingCurrent':u.current_version(),'signatureAndAbiNotRechecked':actual['phase']=='COMPLETE','designGateSketch':proposed,'sketchIsProductImplementation':False})

with sandbox('stale-rollback') as (u,prepare,root):
 older=prepare('v2');u.execute('activate',older);newer=prepare('v3');u.execute('activate',newer)
 before=u.current_version();oldState=u.read_state(older);sketch='ROLLBACK_STALE_OPERATION' if before!=oldState['bundleVersion'] else 'ALLOW'
 result=u.execute('rollback',older)
 observations.append({'case':'rollback an older transaction after a newer activation','before':before,'olderTarget':'v2','existingAfter':u.current_version(),'existingAcceptedStaleRollback':before=='v3' and u.current_version().startswith('bootstrap-'),'designGateSketch':sketch})

with sandbox('previous-integrity') as (u,prepare,root):
 req=prepare('v2');u.execute('activate',req);state=u.read_state(req);previous=u.releases/state['previousVersion']
 (previous/'ablestack-storagectl').write_text('#!/bin/bash\necho changed-baseline\n')
 captured=json.loads((previous/'bootstrap.json').read_text());bad=[f['path'] for f in captured['files'] if m.sha256_file(previous/f['path'])!=f['sha256']]
 result=u.execute('rollback',req)
 observations.append({'case':'previous runtime bytes changed before rollback','changedFiles':bad,'existingRollbackAccepted':result['phase']=='ROLLED_BACK','designGateSketch':'PREVIOUS_RUNTIME_INTEGRITY_MISMATCH' if bad else 'ALLOW'})

with sandbox('activation-failure') as (u,prepare,root):
 req=prepare('v2');before=u.current_version();real=u.verify_release
 def injected(manifest,path):
  if u.current_version()=='v2':raise m.RuntimeUpgradeError('PROBE_ACTIVATION_FAILURE','test fault')
  return real(manifest,path)
 u.verify_release=injected
 try:u.execute('activate',req);code='ACCEPTED'
 except m.RuntimeUpgradeError as e:code=e.code
 observations.append({'case':'existing activation validation failure','result':code,'currentRestored':u.current_version()==before,'phase':u.read_state(req)['phase']})

with tempfile.TemporaryDirectory(prefix='closure-',dir=stage) as temporary:
 root=Path(temporary);helpers=root/'helpers';helpers.mkdir();script=root/'ctl'
 source=Path('/usr/local/bin/ablestack-storagectl').read_text()
 script.write_text(source.replace('/usr/local/lib/ablestack-storage',str(helpers)).replace('/var/log/ablestack-storagectl.log',str(root/'ctl.log')));script.chmod(0o755)
 missing=subprocess.run([str(script),'configuration','capability','show'],capture_output=True,text=True)
 names=['volume_identity.py','volume_preparation.py','session_auth.py','configuration_transaction.py','configuration_preflight.py','nvmeof_identity.py']
 for name in names:shutil.copyfile(Path('/usr/local/lib/ablestack-storage')/name,helpers/name)
 complete=subprocess.run([str(script),'configuration','capability','show'],capture_output=True,text=True,env={**os.environ,'PYTHONDONTWRITEBYTECODE':'1'})
 observations.append({'case':'actual installed CLI helper dependency closure','probeChanges':'private CLI path and log location only','withoutHelpersExit':missing.returncode,'withSixHelpersExit':complete.returncode,'configurationVersion':json.loads(complete.stdout).get('configurationTransactionVersion') if complete.returncode==0 else None,'noProtocolApplyInvoked':True})

result={'kind':'design-only isolated execution of installed RuntimeUpdater and copied CLI','liveActivation':False,'liveProtocolApply':False,'fixtureKeysNotLiveTrusted':True,'fixturePrivateKeysRetained':len(list(stage.rglob('fixture-private.pem'))),'observations':observations}
assert result['fixturePrivateKeysRetained']==0
(stage/'result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
