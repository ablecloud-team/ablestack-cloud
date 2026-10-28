from pathlib import Path
import os,sys,json,stat,hashlib,subprocess,shutil,time,uuid,ast,multiprocessing,traceback,signal
sys.path.insert(0,'/usr/local/lib/ablestack-storage')
import configuration_transaction as installed
VOLUME='78396639-cab6-46a8-9999-89f991abbd01'
MOUNT=Path('/srv/ablestack-storage/volumes')/VOLUME
LIVE=MOUNT/'export/e1451'
CONTROL=Path('/var/tmp/epic1452-design-proof')
DATA=MOUNT/'.epic1452-design-proof'
NS='epic1452-design'
result={'kind':'isolated design validation; not product implementation or acceptance','checks':[]}
children=[];opened=[];control_created=False;data_created=False;namespace_created=False
def checked(argv,**kwargs):
 r=subprocess.run(argv,text=True,capture_output=True,timeout=kwargs.pop('timeout',25),**kwargs)
 if r.returncode:raise RuntimeError(str(argv[:3])+': '+r.stderr[-1600:]+' '+r.stdout[-1200:])
 return r.stdout.strip()
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def meta(p):
 s=p.stat();v={'uid':s.st_uid,'gid':s.st_gid,'mode':format(stat.S_IMODE(s.st_mode),'04o')}
 if p.is_file():v['sha256']=sha(p)
 return v
def acl(p,fd=None):return checked(['getfacl','-cpn',str(p)],**({'pass_fds':(fd,)} if fd is not None else {}))
def live_snapshot():
 files=[Path('/usr/local/bin/ablestack-storagectl'),Path('/usr/local/lib/ablestack-storage/configuration_transaction.py'),Path('/etc/samba/smb.conf'),Path('/etc/fstab')]
 files+=list(Path('/etc/ganesha/ablestack-storage').glob('*.conf'))
 manifest=json.loads(Path('/etc/ablestack-storage/runtime-default/manifest.json').read_text())
 for row in manifest['files']:
  name=row['path'];files.append(Path('/usr/local')/('bin/'+name if '/' not in name else name))
 network=json.loads(subprocess.check_output(['ip','-j','addr','show','dev','eth0'],text=True))[0]
 return {'network':{'mac':network['address'],'addresses':[x['local'] for x in network['addr_info']]},'sourcesAndConfigs':{str(p):sha(p) for p in files if p.is_file()},'pids':{p.name:p.read_text() for p in Path('/run/ablestack-storage/ganesha').glob('*.pid')},'data':{str(p.relative_to(LIVE)):meta(p) for p in LIVE.rglob('*') if p.is_file()},'root':meta(LIVE)}
def record(name,**data):result['checks'].append({'name':name,'passed':True,**data})
def observe(path):return installed.nfs_posix_observe({'uuid':'design','config':{'volumeMountPath':str(MOUNT),'backingPath':str(path)}})
def key(path):
 if observe(path)['status']!='OBSERVED':raise ValueError('PATH_OBSERVATION_UNAVAILABLE')
 return hashlib.sha256(('design-instance|'+VOLUME+'|'+str(path.resolve().relative_to(MOUNT))).encode()).hexdigest()
def open_directory(path):
 canonical=path.resolve()
 if not canonical.is_relative_to(MOUNT.resolve()):raise ValueError('PATH_OUTSIDE_VOLUME')
 fd=os.open(MOUNT,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
 try:
  for part in canonical.relative_to(MOUNT).parts:
   nextfd=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd);os.close(fd);fd=nextfd
  return fd
 except BaseException:os.close(fd);raise
def snapshot_root(path):
 fd=open_directory(path)
 try:return {'meta':meta(path),'acl':acl('/proc/self/fd/'+str(fd),fd)}
 finally:os.close(fd)
def set_root(path,uid,gid,mode):
 fd=open_directory(path)
 try:os.fchown(fd,uid,gid);os.fchmod(fd,int(mode,8))
 finally:os.close(fd)
def restore_root(path,before):
 fd=open_directory(path);proc='/proc/self/fd/'+str(fd)
 try:
  os.fchown(fd,before['meta']['uid'],before['meta']['gid'])
  checked(['setfacl','-b',proc],pass_fds=(fd,));checked(['setfacl','-k',proc],pass_fds=(fd,))
  checked(['setfacl','-n','--set-file=-',proc],input=before['acl']+'\n',pass_fds=(fd,))
  os.fchmod(fd,int(before['meta']['mode'],8))
 finally:os.close(fd)
 assert snapshot_root(path)==before
def protocol_io(phase):
 mount=CONTROL/'client-mount';mount.mkdir(exist_ok=True)
 client_code="""from pathlib import Path
import os,subprocess,json
mnt=Path(MOUNT_VALUE);root=Path(CONTROL_VALUE);phase=PHASE_VALUE;payload='epic1452-'+phase
def run(argv,**kw):
 r=subprocess.run(argv,text=True,capture_output=True,timeout=25,**kw)
 assert r.returncode==0,(argv,r.stdout,r.stderr)
 return r.stdout
def uid():
 os.setgroups([65534]);os.setgid(65534);os.setuid(65534)
run(['mount','-t','nfs4','-o','vers=4.1,sec=sys,proto=tcp,port=23049,soft,timeo=10,retrans=1,nosharecache,actimeo=0,lookupcache=none','127.0.0.1:/shared',str(mnt)])
try:
 s=mnt.stat()
 (root/'input.txt').write_text(payload)
 write="from pathlib import Path;import sys;Path(sys.argv[1]).write_text(sys.argv[2])"
 run(['python3','-c',write,str(mnt/('from-nfs-'+phase+'.txt')),payload],preexec_fn=uid)
 commands='put '+str(root/'input.txt')+' from-smb-'+phase+'.txt; get from-nfs-'+phase+'.txt '+str(root/'from-nfs.txt')+'; get seed.txt '+str(root/'seed-copy.txt')
 run(['smbclient','//127.0.0.1/common','-p','23045','-U','epic1452guest%','-N','-c',commands])
 assert (root/'from-nfs.txt').read_text()==payload
 assert (root/'seed-copy.txt').read_bytes()==b'epic1452-seed-preserved'
 read="from pathlib import Path;import sys;assert Path(sys.argv[1]).read_text()==sys.argv[2]"
 run(['python3','-c',read,str(mnt/('from-smb-'+phase+'.txt')),payload],preexec_fn=uid)
 print(json.dumps({'phase':phase,'nfsMountUid':s.st_uid,'nfsMountGid':s.st_gid,'nfsMountMode':oct(s.st_mode&0o7777),'smbPutNfsRead':True,'nfsPutSmbRead':True,'seedPreserved':True}))
finally:run(['umount',str(mnt)])
""".replace('MOUNT_VALUE',repr(str(mount))).replace('CONTROL_VALUE',repr(str(CONTROL))).replace('PHASE_VALUE',repr(phase))
 return json.loads(checked(['ip','netns','exec',NS,'unshare','--mount','--propagation','private','python3','-c',client_code],timeout=75))
before=live_snapshot()
try:
 assert not CONTROL.exists() and not DATA.exists()
 assert NS not in [line.split()[0] for line in checked(['ip','netns','list']).splitlines()]
 CONTROL.mkdir(mode=0o755);control_created=True
 DATA.mkdir(mode=0o755);data_created=True
 source=Path('/usr/local/bin/ablestack-storagectl').read_text()
 start=source.index('def apply_posix_acl(');snippet=source[start:source.index('\nidentity =',start)]
 functions=[n for n in ast.parse(snippet).body if isinstance(n,ast.FunctionDef) and n.name in {'apply_posix_acl','reset_directory_acl','apply_directory_policy'}]
 import pwd,grp
 def resolve(t,p):return ('u',pwd.getpwnam(p).pw_uid) if t=='LOCAL_USER' else ('g',grp.getgrnam(p).gr_gid)
 env={'os':os,'shutil':shutil,'resolve_account_id':resolve,'run_optional':lambda argv:subprocess.run(argv,capture_output=True,check=True)}
 exec(compile(ast.Module(body=functions,type_ignores=[]),'<installed SMB functions>','exec'),env)
 legacy=DATA/'legacy';legacy.mkdir();os.chown(legacy,1001,1001);os.chmod(legacy,0o750)
 m0=meta(legacy);env['apply_directory_policy'](str(legacy),{'directoryMode':'0770'},True);m1=meta(legacy);assert m0==m1
 env['apply_posix_acl'](str(legacy),'LOCAL_USER','nobody','READ_WRITE');m2=meta(legacy)
 assert m2['mode']=='0770' and m1['mode']=='0750'
 record('installed crossProtocol skips chmod but ACL mask changes effective mode',before=m1,after=m2)
 env['apply_directory_policy'](str(legacy),{'directoryMode':'0770'},False)
 assert 'user:65534:' not in acl(legacy)
 record('standalone legacy SMB chmod/reset behavior reproduced')
 actual=DATA/'actual';actual.mkdir();shared=actual/'shared';shared.mkdir()
 (DATA/'parent-alias').symlink_to(actual,target_is_directory=True);set_root(shared,65534,65534,'0770')
 seed=shared/'seed.txt';seed.write_bytes(b'epic1452-seed-preserved');os.chown(seed,65534,65534);os.chmod(seed,0o640)
 alias=DATA/'parent-alias/shared'
 assert key(shared)==key(alias) and observe(shared)['fingerprint']==observe(alias)['fingerprint']
 record('canonical in-volume parent alias groups NFS/SMB references once',policyKey=key(shared),canonical=str(shared))
 outside=CONTROL/'outside';outside.mkdir();(outside/'sentinel').write_text('untouched')
 (DATA/'outside-parent').symlink_to(outside,target_is_directory=True)
 assert observe(DATA/'outside-parent/sentinel')['status']=='UNAVAILABLE'
 (DATA/'leaf').symlink_to(shared,target_is_directory=True);assert observe(DATA/'leaf')['status']=='UNAVAILABLE'
 record('outside-volume and leaf symlink rejected')
 acltest=DATA/'acl-mask';acltest.mkdir();os.chmod(acltest,0o750);old_acl=snapshot_root(acltest)
 assert ((0o750>>3)&7)!=7
 record('common mode mask 5 cannot grant requested RW ACL mask 7')
 fd=open_directory(acltest)
 try:checked(['setfacl','-n','-m','u:65534:r-x','/proc/self/fd/'+str(fd)],pass_fds=(fd,))
 finally:os.close(fd)
 assert meta(acltest)['mode']=='0750';restore_root(acltest,old_acl)
 record('no-mask ACL and FD rollback preserve exact mode and ACL')
 # This operation registration exists only in this probe process; no product file changes.
 installed.OPERATIONS.add('directory policy apply')
 txnpath=DATA/'transaction';txnpath.mkdir();set_root(txnpath,65534,65534,'0770')
 (txnpath/'child.txt').write_bytes(b'epic1452-child-preserved');calls=[]
 class Adapter:
  def __init__(self,private):self.private=Path(private);self.private.mkdir(parents=True,exist_ok=True)
  def preflight(self,p):
   (self.private/'payload.json').write_text(json.dumps(p))
   if p['candidate']['directory policy apply']['expectedObservation']!=observe(txnpath)['fingerprint']:raise installed.Rejected('POSIX_OBSERVATION_CHANGED')
  def snapshot(self,p):(self.private/'before.json').write_text(json.dumps(snapshot_root(txnpath)))
  def apply(self,c):
   calls.append('apply');v=c['directory policy apply'];set_root(txnpath,v['uid'],v['gid'],v['mode'])
  def verify(self,c):
   v=c['directory policy apply']
   if v.get('failPeer'):raise installed.Rejected('DESIGN_PEER_VERIFY_FAILURE')
   assert meta(txnpath)=={'uid':v['uid'],'gid':v['gid'],'mode':v['mode']}
  def restore(self):restore_root(txnpath,json.loads((self.private/'before.json').read_text()));calls.append('restore')
  def verify_restored(self):assert snapshot_root(txnpath)==json.loads((self.private/'before.json').read_text())
  def is_running(self):return False
  def observe_completed(self):return 'VERIFIED'
 instance=str(uuid.uuid4())
 payload={'instanceUuid':instance,'operationid':str(uuid.uuid4()),'baseRevision':0,'targetRevision':1,'before':{},'candidate':{'directory policy apply':{'uid':1001,'gid':1001,'mode':'0777','expectedObservation':observe(txnpath)['fingerprint'],'references':['NFS','SMB']}}}
 transaction=installed.Transaction(CONTROL/'journal',Adapter);applied=transaction.execute('apply',payload);assert applied['state']=='VERIFIED'
 transaction.execute('commit',payload);reloaded=installed.Transaction(CONTROL/'journal',Adapter)
 assert reloaded.execute('resume',payload)['state']=='COMMITTED'
 assert reloaded.execute('apply',payload)['state']=='COMMITTED' and calls.count('apply')==1
 record('installed transaction one apply for two references, commit, reopen journal and no duplicate apply',applyCount=1)
 stale=dict(payload,operationid=str(uuid.uuid4()))
 try:reloaded.execute('apply',stale);raise AssertionError('stale revision accepted')
 except installed.Rejected as e:assert e.code=='REVISION_CONFLICT'
 record('installed revision fence rejects stale policy transaction')
 old=snapshot_root(txnpath);child_before=meta(txnpath/'child.txt')
 failed={'instanceUuid':instance,'operationid':str(uuid.uuid4()),'baseRevision':1,'targetRevision':2,'before':{},'candidate':{'directory policy apply':{'uid':65534,'gid':65534,'mode':'0770','expectedObservation':observe(txnpath)['fingerprint'],'references':['NFS','SMB'],'failPeer':True}}}
 rolled=reloaded.execute('apply',failed)
 assert rolled['state']=='ROLLED_BACK' and rolled['rollback']['state']=='SUCCEEDED' and rolled['originalfailure']['code']=='DESIGN_PEER_VERIFY_FAILURE'
 assert snapshot_root(txnpath)==old and meta(txnpath/'child.txt')==child_before
 record('injected peer failure restores ACL/owner/mode and preserves child data',operation=rolled)
 ready=multiprocessing.Event();done=multiprocessing.Queue()
 class SlowAdapter(Adapter):
  def apply(self,c):ready.set();time.sleep(.6);super().apply(c)
 def worker():
  p=dict(failed,operationid=str(uuid.uuid4()),baseRevision=0,targetRevision=1)
  p['candidate']={'directory policy apply':dict(failed['candidate']['directory policy apply'],failPeer=False,expectedObservation=observe(txnpath)['fingerprint'])}
  done.put(installed.Transaction(CONTROL/'concurrent-journal',SlowAdapter).execute('apply',p)['state'])
 process=multiprocessing.Process(target=worker);process.start();assert ready.wait(10)
 try:installed.Transaction(CONTROL/'concurrent-journal',Adapter).execute('apply',dict(payload,operationid=str(uuid.uuid4())));raise AssertionError('second writer accepted')
 except BlockingIOError:record('installed cross-process instance lock rejects concurrent second writer')
 process.join(10);assert not process.is_alive() and done.get(timeout=2)=='VERIFIED'
 checked(['ip','netns','add',NS]);namespace_created=True;checked(['ip','-n',NS,'link','set','lo','up'])
 checked(['ip','-n',NS,'link','add','probe0','type','dummy'])
 checked(['ip','-n',NS,'addr','add','192.0.2.52/32','dev','probe0'])
 checked(['ip','-n',NS,'link','set','probe0','up'])
 for folder in ['recovery','smb-private','smb-state','smb-lock','smb-cache','smb-pid','smb-rpc']:(CONTROL/folder).mkdir(mode=0o700)
 ganesha=CONTROL/'ganesha.conf'
 ganesha.write_text('''NFS_Core_Param { NFS_Port=23049; Bind_addr=127.0.0.1; Protocols=4; Enable_NLM=false; Enable_RQUOTA=false; Enable_UDP=false; Dbus_Name_Prefix="epic1452design"; }
NFSV4 { Grace_Period=0; Graceless=true; RecoveryRoot="'''+str(CONTROL/'recovery')+'''"; Allow_Numeric_Owners=true; Only_Numeric_Owners=true; }
EXPORT_DEFAULTS { Access_Type=None; Protocols=4; Transports=TCP; SecType=sys; }
EXPORT { Export_Id=1452; Path="'''+str(shared)+'''"; Pseudo="/shared"; Protocols=4; SecType=sys; FSAL { Name=VFS; } CLIENT { Clients=127.0.0.1; Access_Type=RW; Squash=Root_Squash; } }
''')
 smb=CONTROL/'smb.conf'
 smb.write_text('''[global]
 server role=standalone server
 security=user
 map to guest=Bad User
 guest account=nobody
 smb ports=23045
 interfaces=127.0.0.1
 bind interfaces only=yes
 netbios name=EPIC1452DESIGN
 workgroup=WORKGROUP
 load printers=no
 printing=bsd
 disable spoolss=yes
 passdb backend=tdbsam:'''+str(CONTROL/'smb-private/passdb.tdb')+'''
 private dir='''+str(CONTROL/'smb-private')+'''
 state directory='''+str(CONTROL/'smb-state')+'''
 lock directory='''+str(CONTROL/'smb-lock')+'''
 cache directory='''+str(CONTROL/'smb-cache')+'''
 pid directory='''+str(CONTROL/'smb-pid')+'''
 ncalrpc dir='''+str(CONTROL/'smb-rpc')+'''
 log file='''+str(CONTROL/'smb.log')+'''
[common]
 path='''+str(shared)+'''
 read only=no
 guest ok=yes
 create mask=0660
 directory mask=0770
''')
 checked(['testparm','-s',str(smb)])
 for argv,log in [(['ganesha.nfsd','-F','-f',str(ganesha),'-p',str(CONTROL/'ganesha.pid'),'-L',str(CONTROL/'ganesha.log')],'ganesha-output.log'),(['smbd','--foreground','--no-process-group','--configfile',str(smb)],'smb-output.log')]:
  output=(CONTROL/log).open('w');opened.append(output)
  children.append(subprocess.Popen(['ip','netns','exec',NS]+argv,stdout=output,stderr=subprocess.STDOUT,start_new_session=True))
 for n in range(50):
  if all(subprocess.run(['ip','netns','exec',NS,'python3','-c','import socket;s=socket.create_connection(("127.0.0.1",'+str(port)+'),.3);s.close()'],capture_output=True).returncode==0 for port in [23049,23045]):break
  if any(p.poll() is not None for p in children):raise RuntimeError('private protocol server exited')
  time.sleep(.1)
 first=protocol_io('before')
 assert (first['nfsMountUid'],first['nfsMountGid'],first['nfsMountMode'])==(65534,65534,'0o770')
 root_seed=meta(seed);set_root(shared,1001,1001,'0777')
 old_ganesha=children[0];os.killpg(old_ganesha.pid,signal.SIGTERM);old_ganesha.wait(timeout=8)
 output=(CONTROL/'ganesha-output.log').open('a');opened.append(output)
 new_ganesha=subprocess.Popen(['ip','netns','exec',NS,'ganesha.nfsd','-F','-f',str(ganesha),'-p',str(CONTROL/'ganesha.pid'),'-L',str(CONTROL/'ganesha.log')],stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
 children.append(new_ganesha)
 for n in range(50):
  if subprocess.run(['ip','netns','exec',NS,'python3','-c','import socket;s=socket.create_connection(("127.0.0.1",23049),.3);s.close()'],capture_output=True).returncode==0:break
  time.sleep(.1)
 second=protocol_io('after')
 record('controlled NFS process refresh invalidates external POSIX attribute cache',oldPid=old_ganesha.pid,newPid=new_ganesha.pid,smbPidUnchanged=children[1].poll() is None)
 result['nativeProtocolIO']={'before':first,'after':second,'actualRoot':meta(shared),'seedBefore':root_seed,'seedAfter':meta(seed)}
 assert (second['nfsMountUid'],second['nfsMountGid'],second['nfsMountMode'])==(1001,1001,'0o777') and meta(seed)==root_seed
 record('actual isolated NFSv4 and SMB cross-read/write before/after one common POSIX root change',before=first,after=second,seedMetadataPreserved=True)
 result['success']=True
except BaseException as e:
 result['success']=False;result['harnessError']={'type':type(e).__name__,'message':str(e),'trace':traceback.format_exc()}
 result['privateLogs']={p.name:p.read_text(errors='replace')[-4500:] for p in CONTROL.glob('*.log')} if control_created else {}
finally:
 for p in reversed(children):
  try:os.killpg(p.pid,signal.SIGTERM)
  except ProcessLookupError:pass
  try:p.wait(timeout=8)
  except subprocess.TimeoutExpired:
   os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
 for output in opened:output.close()
 if namespace_created:subprocess.run(['ip','netns','del',NS],capture_output=True)
 if data_created:
  assert DATA.resolve().parent==MOUNT.resolve() and DATA.name=='.epic1452-design-proof' and not DATA.is_symlink()
  shutil.rmtree(DATA)
 if control_created:
  assert CONTROL.resolve()==Path('/var/tmp/epic1452-design-proof') and not CONTROL.is_symlink()
  shutil.rmtree(CONTROL)
 after=live_snapshot();result['productionUnchanged']=before==after;result['isolatedPathsRemoved']=not DATA.exists() and not CONTROL.exists()
 result['productionFileCount']=len(after['data'])
 if before!=after:result['success']=False
 print(json.dumps(result))
