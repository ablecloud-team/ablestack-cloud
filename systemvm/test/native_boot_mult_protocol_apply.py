# Licensed under the Apache License, Version 2.0. See NOTICE.
# Explicit opt-in integration regression; run only on the disposable SharedFS VM.
from pathlib import Path
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--vm-uuid',required=True)
parser.add_argument('--instance-uuid',required=True)
parser.add_argument('--helper',type=Path,required=True)
parser.add_argument('--seed-root',type=Path,required=True)
parser.add_argument('--prior-observed-path',default='/dev/epic1459-previous-observation')
parser.add_argument('--expected-probes',type=int,required=True)
args=parser.parse_args()
import base64,hashlib,json,os,tempfile,shutil,subprocess,sys,importlib.util,copy,fcntl
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()==args.vm_uuid.lower()
release=Path('/opt/ablestack/storage-runtime/current').resolve()
sys.path.insert(0,str(release/'lib/ablestack-storage'))
import boot_reconcile as baseline
root=Path(tempfile.mkdtemp(prefix='epic1459-path-observation-',dir='/var/tmp'));root.chmod(0o700)
candidate=args.helper.resolve();assert candidate.is_file()
spec=importlib.util.spec_from_file_location('candidate_boot',candidate);fixed=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixed)
source=args.seed_root.resolve();assert str(source).startswith('/srv/ablestack-storage/volumes/')
def metadata():
 result={}
 for p in [source,source/'nfs-seed',source/'team',source/'team/shared-seed',source/'team/smb-seed']:
  s=p.stat();result[str(p)]={'uid':s.st_uid,'gid':s.st_gid,'mode':s.st_mode&0o7777,'inode':s.st_ino}
  if p.is_file():result[str(p)]['sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
 return result
watched=[release/'ablestack-storagectl',release/'lib/ablestack-storage/boot_reconcile.py',Path('/etc/systemd/system/ablestack-storage-reconcile.service')]
watched=[p for p in watched if p.is_file()]
before_code={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in watched};before_data=metadata()
native=baseline.Runtime();snapshot=baseline.desired_snapshot()
assert snapshot['instanceUuid']==args.instance_uuid.lower()
native.eligibility(snapshot['instanceUuid'])
connections=subprocess.check_output(['ss','-Htn','state','established'],text=True).splitlines()
assert not any(any(line.split()[2].endswith(':'+str(port)) for port in (2049,2050,445,3260,4420)) for line in connections),'Disconnect test clients before native apply regression'
paths={p:root/(p+'.json') for p in baseline.DESIRED}
for p,path in paths.items():
 value=copy.deepcopy(snapshot['states'][p])
 if p=='NVMEOF':
  value['subsystems'][0]['namespaces'][0]['runtime']['backingPath']=args.prior_observed_path
 path.write_text(json.dumps(value));path.chmod(0o600)
def legacy_hash():return baseline.digest({p:{k:v for k,v in baseline.read_json(path,{}).items() if k!='lastApply'} for p,path in paths.items()})
old_before=legacy_hash();new_before=fixed.desired_snapshot(paths)['desiredHash']
class ActualPrepare(fixed.Runtime):
 def __init__(self):self.applies=[];self.probes=0
 def launch(self,directory,unit):return {'returncode':0}
 def prepare(self,protocol,path,seconds):
  self.applies.append(protocol)
  result=super().prepare(protocol,path,seconds)
  # Read the real CLI's saved result back into this isolated worker snapshot.
  paths[protocol].write_bytes(baseline.DESIRED[protocol].read_bytes())
  return result
 def probe(self,task,private,seconds):
  self.probes+=1
  return super().probe(task,private,seconds)
runtime=ActualPrepare();engine=fixed.BootReconciler(root/'journal',paths,runtime)
leases=[]
try:
 for p in [Path('/run/lock/ablestack-storage-runtime-upgrade.lock'),Path('/run/lock/ablestack-storage-boot-reconcile.lock'),Path('/var/lib/ablestack-storage/configuration-operations')/snapshot['instanceUuid']/'lock']:
  f=p.open('a');fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB);leases.append(f)
 engine.start();result=engine.work()
 after=fixed.desired_snapshot(paths)
 assert runtime.applies==['NFS','SMB','ISCSI','NVMEOF'],runtime.applies
 assert result['state']=='READY',result
 assert result['protocols']['NFS']['completed']==args.expected_probes,result
 assert legacy_hash()!=old_before
 assert after['desiredHash']==new_before
 negatives=[]
 for label,protocol,mutate in [
 ('volume_uuid','NVMEOF',lambda v:v['subsystems'][0]['namespaces'][0].update(volumeUuid='00000000-0000-0000-0000-000000000001')),
 ('namespace_uuid','NVMEOF',lambda v:v['subsystems'][0]['namespaces'][0].update(uuid='00000000-0000-0000-0000-000000000002')),
 ('namespace_size','NVMEOF',lambda v:v['subsystems'][0]['namespaces'][0].update(volumeSizeBytes=2147483648)),
 ('configured_path','NVMEOF',lambda v:v['subsystems'][0]['namespaces'][0].setdefault('config',{}).update(backingPath='/dev/forbidden')),
 ('acl','NVMEOF',lambda v:v['subsystems'][0]['hosts'][0].update(principal='nqn.2026-11.local.client:other')),
 ('backing_type','NVMEOF',lambda v:v['subsystems'][0]['namespaces'][0]['runtime'].update(backingType='file')),
 ('nfs_acl','NFS',lambda v:v['exports'][0].setdefault('acls',[]).append({'source':'127.0.0.1/32','readOnly':True}))]:
  original=paths[protocol].read_bytes();value=json.loads(original);mutate(value);paths[protocol].write_text(json.dumps(value))
  assert fixed.desired_snapshot(paths)['desiredHash']!=new_before,label
  paths[protocol].write_bytes(original);negatives.append(label)
 assert before_data==metadata()
 assert before_code=={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in watched}
 assert not list(root.glob('**/probe-*'))
 print(json.dumps({'success':True,'mode':'actual SystemVM worker -> signed CLI NFS/SMB/iSCSI/NVMe apply -> saved desired reload -> actual NFS probe','applies':runtime.applies,'probes':runtime.probes,'result':result,'oldHashChanged':True,'desiredProjectionStable':True,'negativeProtectionChecks':negatives,'dataPreserved':True,'activeCodePreserved':True}))
finally:
 for f in reversed(leases):f.close()
 if not any(str(root) in line.split()[1] for line in Path('/proc/self/mounts').read_text().splitlines() if len(line.split())>1):shutil.rmtree(root)
