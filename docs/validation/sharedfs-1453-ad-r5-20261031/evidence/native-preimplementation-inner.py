from pathlib import Path
import os,json,sys,subprocess,uuid,hashlib,importlib.util,secrets
root=Path(sys.argv[1])
assert os.readlink('/proc/self/ns/mnt')!=os.readlink('/proc/1/ns/mnt')
subprocess.run(['mount','--bind',str(root/'etc'),'/etc'],check=True)
sys.path.insert(0,'/usr/local/lib/ablestack-storage')
spec=importlib.util.spec_from_file_location('candidate_transaction',root/'candidate.py');tx=importlib.util.module_from_spec(spec);spec.loader.exec_module(tx)
instance='4cc8fd3c-19c5-4f05-960c-82d40abdc802'
desired=Path('/etc/ablestack-storage/desired-state/smb-share-apply.json')
before=json.loads(desired.read_text())
original_resolv=Path('/etc/resolv.conf').read_bytes()
krb=Path('/etc/krb5.conf')
assert not krb.exists() and not krb.is_symlink()
os.environ['KRB5CCNAME']='FILE:'+str(root/'krb5cc')
results=[]
for mode in ['missing','existing']:
 if mode=='existing':krb.write_bytes(b'[libdefaults]\n default_realm = ORIGINAL.INVALID\n');krb.chmod(0o600)
 old=krb.read_bytes() if krb.exists() else None;inode=krb.stat().st_ino if krb.exists() else None
 operation=str(uuid.uuid4())
 join=tx.AdDomainJoin(root/'operations',configuration_root=root/'locks',desired=desired)
 real_member=join.member;observed={}
 def member(value):
  try:return real_member(value)
  finally:observed.update(created=krb.is_file(),generatedRealm='ABLESTACK.LOCAL' in krb.read_text())
 join.member=member
 value={'instanceUuid':instance,'operationid':operation,'domainName':'ablestack.local','username':'epic1453-precheck-no-user',
        'password':secrets.token_urlsafe(24),'dnsServers':'10.10.13.247','netbiosName':'STORR5PRECHECK',
        'smbDesiredState':before,'smbBeforeState':before}
 outcome=join.run(value)
 assert outcome['state']=='MEMBERSHIP_FAILED' and outcome['rollback']=='SUCCEEDED',outcome
 assert outcome['failureCode']=='AD_MEMBERSHIP_KINIT_FAILED',outcome
 assert observed=={'created':True,'generatedRealm':True},observed
 snap=json.loads((root/'operations'/instance/operation/'client-before.json').read_text())
 assert snap['/etc/krb5.conf']['exists']==(mode=='existing')
 assert Path('/etc/resolv.conf').read_bytes()==original_resolv
 assert (krb.read_bytes()==old and krb.stat().st_mode&0o777==0o600 and krb.stat().st_ino==inode) if old else not krb.exists()
 results.append({'initial':mode,'actualMemberCliCreatedKerberos':True,'failureCode':outcome['failureCode'],'rollback':outcome['rollback'],'originalStateRestored':True})
 if krb.exists():krb.unlink()
victim=root/'untouched';victim.write_text('unchanged');krb.symlink_to(victim)
try:
 tx.AdDomainJoin(root/'operations',configuration_root=root/'locks',desired=desired).run({**value,'operationid':str(uuid.uuid4())})
except tx.Rejected as error:assert error.code=='AD_CLIENT_CONFIGURATION_UNSAFE'
else:raise AssertionError('symlink was accepted')
assert victim.read_text()=='unchanged'
print(json.dumps({'success':True,'actualInitialKerberosAbsent':True,'cases':results,'symlinkRejected':True,'realCliAndKdcFailurePath':True,'configurationApplied':False}))
