"""#1447 design-only boundary probes. No production implementation or live activation."""
import os,sys,json,hashlib,tarfile,tempfile,shutil,importlib.util,subprocess,zipfile
from pathlib import Path,PurePosixPath
sys.dont_write_bytecode=True
VERSION='epic1438-1446-7f473dc06768'
URL='http://10.10.13.10:8080/client/storage-runtime/'+VERSION+'/'
def digest(data):return hashlib.sha256(data).hexdigest()
def load_module(path):
 spec=importlib.util.spec_from_file_location('installed_verifier',path)
 m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def inputs(root):
 import urllib.request
 root.mkdir(mode=0o700,exist_ok=True)
 for name in ['manifest.json','manifest.sig','ablestack-storage-runtime-'+VERSION+'.tar.gz']:
  with urllib.request.urlopen(URL+name,timeout=15) as response:data=response.read(64*1024*1024+1)
  assert len(data)<=64*1024*1024
  (root/('bundle.tar.gz' if name.endswith('.tar.gz') else name)).write_bytes(data)
def catalog_gate(state,verification,compatibility,action,referenced=False,active=False):
 # A policy sketch only, deliberately separate from the installed product.
 if action=='delete':
  return 'ALLOW' if state in {'DRAFT','RETIRED'} and not referenced and not active else 'DENY'
 if state in {'REVOKING','REVOKED','DELETED'}:return 'DENY'
 if verification!='VERIFIED' or compatibility!='COMPATIBLE':return 'DENY'
 if action=='apply':return 'ALLOW' if state=='PUBLISHED' else 'DENY'
 if action=='rollback':return 'ALLOW' if state in {'PUBLISHED','SUSPENDED','RETIRED'} else 'DENY'
 return 'DENY'
def policy_matrix():
 cases=[
 ('unverified','DRAFT','UNVERIFIED','COMPATIBLE','apply',False,False,'DENY'),
 ('verified-not-published','DRAFT','VERIFIED','COMPATIBLE','apply',False,False,'DENY'),
 ('published','PUBLISHED','VERIFIED','COMPATIBLE','apply',False,False,'ALLOW'),
 ('unknown-target','PUBLISHED','VERIFIED','UNKNOWN','apply',False,False,'DENY'),
 ('failed-validation','PUBLISHED','FAILED','COMPATIBLE','apply',False,False,'DENY'),
 ('suspended-apply','SUSPENDED','VERIFIED','COMPATIBLE','apply',False,False,'DENY'),
 ('suspended-rollback','SUSPENDED','VERIFIED','COMPATIBLE','rollback',True,False,'ALLOW'),
 ('retired-rollback','RETIRED','VERIFIED','COMPATIBLE','rollback',True,False,'ALLOW'),
 ('revoking-apply','REVOKING','VERIFIED','COMPATIBLE','apply',True,True,'DENY'),
 ('revoked-rollback','REVOKED','VERIFIED','COMPATIBLE','rollback',True,False,'DENY'),
 ('referenced-delete','RETIRED','VERIFIED','COMPATIBLE','delete',True,False,'DENY'),
 ('active-delete','DRAFT','UNVERIFIED','UNKNOWN','delete',False,True,'DENY'),
 ('unused-retired-delete','RETIRED','VERIFIED','COMPATIBLE','delete',False,False,'ALLOW'),
 ('deleted-cannot-apply','DELETED','VERIFIED','COMPATIBLE','apply',False,False,'DENY')]
 result=[]
 for name,state,verified,compat,action,refs,active,expected in cases:
  actual=catalog_gate(state,verified,compat,action,refs,active)
  assert actual==expected,(name,actual,expected)
  result.append({'case':name,'result':actual})
 return result
def stream_check(m,u,tx):
 manifest=u.load_manifest(tx);u.verify_signature(tx,manifest)
 declared=u.validate_manifest_files(manifest)
 seen=set();expanded=0
 with tarfile.open(tx/'bundle.tar.gz','r:gz') as archive:
  for member in archive.getmembers():
   path=PurePosixPath(member.name)
   if member.isdir():
    assert member.name.rstrip('/') in {'lib','lib/ablestack-storage'}
    continue
   assert not path.is_absolute() and '..' not in path.parts
   assert member.isfile() and member.name in declared and member.name not in seen
   entry=declared[member.name]
   assert member.uid==0 and member.gid==0 and member.mode==entry['mode'] and member.size==entry['bytes']
   expanded+=member.size;assert expanded<=m.MAX_EXPANDED_BYTES
   with archive.extractfile(member) as handle:blob=handle.read()
   assert digest(blob)==entry['sha256'];seen.add(member.name)
 assert seen==set(declared)
 return {'fileCount':len(seen),'expandedBytes':expanded,'signatureVerified':True,'archiveFilesExtracted':False,'payloadExecuted':False}
mode=sys.argv[1]
with tempfile.TemporaryDirectory(prefix='epic1447-design-') as tmp:
 root=Path(tmp);root.chmod(0o700);tx=root/'received';inputs(tx)
 if mode=='management':
  with zipfile.ZipFile('/usr/share/cloudstack-management/lib/cloudstack-4.23.0.0.jar') as jar:
   module=root/'runtime_updater.py';module.write_bytes(jar.read('storage-runtime/bootstrap/runtime_updater.py'))
  # Public key is supplied as public input; no private key is accessed by this probe.
  public=Path(sys.argv[2]).read_bytes()
 else:
  module=Path('/usr/local/lib/ablestack-storage/runtime_updater.py')
  public=Path('/opt/ablestack/storage-runtime/trusted-keys/ablestack-runtime-cluster13.pem').read_bytes()
 m=load_module(module);u=m.RuntimeUpdater();trust=root/'trust';trust.mkdir(mode=0o700)
 (trust/'ablestack-runtime-cluster13.pem').write_bytes(public);u.trusted_keys=trust
 result={'kind':'design-only probes','mode':mode,'uid':os.geteuid(),'python':sys.version.split()[0],'moduleSha256':digest(module.read_bytes()),'policySketchIsProduct':False,'policyMatrix':policy_matrix()}
 result['streamVerification']=stream_check(m,u,tx)
 original=(tx/'manifest.sig').read_bytes();broken=bytearray(original);broken[0]^=1;(tx/'manifest.sig').write_bytes(broken)
 try:u.verify_signature(tx,u.load_manifest(tx));raise AssertionError('invalid signature was accepted')
 except m.RuntimeUpgradeError as error:result['tamperedSignatureRejected']=error.code=='SIGNATURE_INVALID'
 finally:(tx/'manifest.sig').write_bytes(original)
 assert result['tamperedSignatureRejected']
 if mode=='native':
  folders={k:root/k for k in ['runtime','state','entrypoints']}
  for p in folders.values():p.mkdir(mode=0o700)
  env={'ABLESTACK_STORAGE_RUNTIME_ROOT':str(folders['runtime']),'ABLESTACK_STORAGE_RUNTIME_STATE_ROOT':str(folders['state']),'ABLESTACK_STORAGE_RUNTIME_ENTRYPOINT_ROOT':str(folders['entrypoints']),'ABLESTACK_STORAGE_RUNTIME_TRUSTED_KEYS':str(trust),'ABLESTACK_STORAGE_RUNTIME_LOCK':str(root/'runtime.lock'),'ABLESTACK_STORAGE_RUNTIME_DEFAULT':str(root/'no-default')}
  os.environ.update(env)
  def request(name):
   return {'transactionId':name,'bundleVersion':VERSION,'archiveSha256':digest((tx/'bundle.tar.gz').read_bytes()),'manifestSha256':digest((tx/'manifest.json').read_bytes()),'totalSize':(tx/'bundle.tar.gz').stat().st_size,'manifestSize':(tx/'manifest.json').stat().st_size,'signatureSize':(tx/'manifest.sig').stat().st_size}
  verifier=m.RuntimeUpdater();req=request('catalog-verify')
  verifier.execute('begin',req)
  for name in ['bundle.tar.gz','manifest.json','manifest.sig']:shutil.copyfile(tx/name,verifier.transaction_dir(req)/name)
  verifier.execute('finalize',req);verified=verifier.execute('verify',req)
  result['existingVerifyWithoutBootstrap']={'phase':verified['phase'],'privateCurrentIsUnset':verifier.current_version() is None}
  assert verifier.current_version() is None and verified['phase']=='VERIFIED'
  for name in m.ENTRYPOINTS:
   p=folders['entrypoints']/name;p.write_text('#!/bin/bash\necho design-baseline\n');p.chmod(0o755)
  baseline=verifier.execute('bootstrap',{'transactionId':'design-bootstrap'})['currentVersion']
  verifier.execute('preflight',req)
  if catalog_gate('DRAFT','VERIFIED','COMPATIBLE','apply')=='DENY':
   assert verifier.current_version()==baseline
  verifier.execute('activate',req);verifier.execute('activate',dict(req,confirmActivation=True))
  # Demonstrates the missing product boundary without touching a live root.
  accepted=verifier.execute('rollback',dict(req,catalogState='REVOKED',catalogRevision=3))
  result['currentNativeIgnoresCatalogFields']={'rollbackAccepted':accepted['phase']=='ROLLED_BACK','onlyPrivatePointersChanged':True}
  # Persisted fence sketch, not an installed updater patch.
  policy=root/'policy.json';m.atomic_json(policy,{'bundle':VERSION,'revision':3,'state':'REVOKED'})
  def fence(action,incoming):
   saved=json.loads(policy.read_text())
   if incoming<saved['revision']:return 'STALE_POLICY_REJECTED'
   return catalog_gate(saved['state'],'VERIFIED','COMPATIBLE',action)
  result['persistedFenceSketch']={'applyAfterReload':fence('apply',3),'rollbackAfterReload':fence('rollback',3),'olderPolicy':fence('apply',2)}
  assert result['persistedFenceSketch']=={'applyAfterReload':'DENY','rollbackAfterReload':'DENY','olderPolicy':'STALE_POLICY_REJECTED'}
 result['liveActivation']=False;result['productionCodePatched']=False;result['privateRootsRemovedOnExit']=True
 print(json.dumps(result))
