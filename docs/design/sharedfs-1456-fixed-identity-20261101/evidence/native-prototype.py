# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
from pathlib import Path
import os,sys,json,tempfile,subprocess,hashlib,uuid,time,signal,secrets,pwd,grp,argparse,shutil
VOLUME=Path('/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277')
def run(args,**kw):return subprocess.run(args,capture_output=True,text=True,timeout=20,**kw)
def checked(args,**kw):
 q=run(args,**kw)
 if q.returncode:raise RuntimeError(str(args[:2])+': '+q.stderr[:350]+q.stdout[:350])
 return q.stdout
def account_hashes():return {str(p):hashlib.sha256(p.read_bytes()).hexdigest()for p in map(Path,['/etc/passwd','/etc/group','/etc/shadow','/etc/gshadow'])}
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--module',required=True);parser.add_argument('--inner',action='store_true');a=parser.parse_args()
 assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
 if not a.inner:
  before=account_hashes()
  q=subprocess.run(['unshare','--mount','--net','--propagation','private',sys.executable,__file__,'--module',a.module,'--inner'],capture_output=True,text=True,timeout=180)
  assert before==account_hashes(),'real SystemVM account files changed'
  print(q.stdout);print(q.stderr,file=sys.stderr)
  if q.returncode:return q.returncode
  print(json.dumps({'globalSystemVmAccountsUnchanged':True,'namespaceIsolation':'mount+network, RAM overlay for /etc and passdb','productionMutation':False}))
  return 0
 fs=checked(['findmnt','-n','-M',str(VOLUME),'-o','UUID,FSTYPE']).strip();assert fs.endswith(' xfs')
 checked(['ip','link','set','lo','up'])
 control=Path(tempfile.mkdtemp(prefix='epic1456-control-',dir='/var/tmp'));data=VOLUME/('.epic1456-design-'+uuid.uuid4().hex);data.mkdir(mode=0o755)
 processes=[];logs=[];checks=[];anchors=[]
 anchor=Path(tempfile.mkdtemp(prefix='epic1456-anchor-',dir='/run'));anchor.chmod(0o755)
 def passed(name,**details):checks.append({'name':name,'passed':True,**details})
 try:
  checked(['mount','-t','tmpfs','-o','size=32m,mode=0700,nosuid,nodev','tmpfs',str(control)])
  for name in ['lower','upper','work']: (control/name).mkdir()
  checked(['mount','--bind','/etc',str(control/'lower')]);checked(['mount','-o','remount,bind,ro',str(control/'lower')])
  checked(['mount','-t','overlay','overlay','-o','lowerdir='+str(control/'lower')+',upperdir='+str(control/'upper')+',workdir='+str(control/'work'),'/etc'])
  # All user/group writes and Samba passdb below live only in private RAM mounts.
  for base in range(7000,9900,10):
   free=True
   for n in range(base+1,base+7):
    if run(['getent','passwd',str(n)]).returncode==0 or run(['getent','group',str(n)]).returncode==0:free=False;break
   if free:break
  else:raise RuntimeError('no isolated free identity block')
  uid,gid,auid,agid,duid,dgid=range(base+1,base+7)
  force='e1456force';auth='e1456auth';denied='e1456deny'
  for name,u,g in [(force,uid,gid),(auth,auid,agid),(denied,duid,dgid)]:
   checked(['groupadd','-g',str(g),name])
   checked(['useradd','-r','-u',str(u),'-g',str(g),'-M','-N','-l','-d','/nonexistent','-s','/usr/sbin/nologin',name])
  p=pwd.getpwnam(force);assert p.pw_uid==uid and p.pw_gid==gid and p.pw_shell=='/usr/sbin/nologin' and not Path(p.pw_dir).exists()
  import spwd
  assert spwd.getspnam(force).sp_pwdp.startswith(('!','*'))
  passed('managed identity is non-login, locked and no home',uid=uid,gid=gid)
  before=account_hashes();duplicate=run(['useradd','-r','-u',str(uid),'-g',str(gid),'-M','-N','-l','e1456collision'])
  assert duplicate.returncode!=0 and before==account_hashes()
  passed('real duplicate UID creation refuses without account edits')
  def validate(u,g,inherit=False,guest=False,mode='0770'):
   if not isinstance(u,int)or not isinstance(g,int)or not (1000<=u<=65533 and 1000<=g<=65533):raise ValueError('PROTECTED_ID')
   if 10000<=u<=999999 or 10000<=g<=999999:raise ValueError('AD_IDMAP_RANGE')
   if inherit or int(mode,8)&0o2000:raise ValueError('INHERIT_CONFLICT')
   if guest:raise ValueError('GUEST_FORBIDDEN')
   if pwd.getpwuid(u).pw_name!=force or grp.getgrgid(g).gr_name!=force:raise ValueError('UID_GID_COLLISION')
  for label,values in [('root',(0,gid)),('protected',(65534,gid)),('AD overlap',(10001,gid)),('foreign UID',(auid,gid)),('foreign GID',(uid,agid)),('inherit owner',(uid,gid,True)),('guest',(uid,gid,False,True)),('setgid',(uid,gid,False,False,'2770'))]:
   try:validate(*values)
   except ValueError as e:passed('prototype guard '+label,code=str(e))
   else:raise AssertionError(label+' accepted')
  validate(uid,gid);passed('verified owned identity can be observed repeatedly')
  for name,u,g,mode in [('rw',uid,gid,0o770),('denied',0,0,0o700),('legacy',auid,agid,0o770),('sgid',uid,agid,0o2770)]:
   path=data/name;path.mkdir();os.chown(path,u,g);os.chmod(path,mode)
  for name in ['rw','denied','legacy','sgid']:
   target=anchor/name;target.mkdir();checked(['mount','--bind',str(data/name),str(target)]);anchors.append(target)
  old=data/'rw'/'existing.txt';old.write_text('existing-data-preserved\n');os.chown(old,auid,agid);old.chmod(0o644)
  old_meta=(old.stat().st_uid,old.stat().st_gid,old.stat().st_ino,old.stat().st_mode,hashlib.sha256(old.read_bytes()).hexdigest())
  src=control/'source';src.write_text('epic1456-fixed-native\n')
  libroot=Path('/usr/lib/x86_64-linux-gnu/samba')
  assert libroot.is_dir()
  for folder in ['lib-lower','lib-upper','lib-work']:(control/folder).mkdir(mode=0o700)
  checked(['mount','--bind',str(libroot),str(control/'lib-lower')]);checked(['mount','-o','remount,bind,ro',str(control/'lib-lower')])
  checked(['mount','-t','overlay','overlay','-o','lowerdir='+str(control/'lib-lower')+',upperdir='+str(control/'lib-upper')+',workdir='+str(control/'lib-work'),str(libroot)])
  module=libroot/'vfs'/'full_audit.so';module.parent.mkdir(exist_ok=True);module.write_bytes(Path(a.module).read_bytes());module.chmod(0o644)
  passwords={auth:secrets.token_urlsafe(24),denied:secrets.token_urlsafe(24)}
  port=23156;base_dir=control/'samba';base_dir.mkdir()
  lines=['[global]','server role=standalone server','security=user','map to guest=Never','smb ports='+str(port),'interfaces=127.0.0.1','bind interfaces only=yes','netbios name=EPICFIX','load printers=no','printing=bsd','disable spoolss=yes','log level=3 vfs:5']
  for key,folder in [('private dir','private'),('state directory','state'),('lock directory','lock'),('cache directory','cache'),('pid directory','pid'),('ncalrpc dir','rpc')]:
   path=base_dir/folder;path.mkdir();lines.append(key+'='+str(path))
  lines+=['passdb backend=tdbsam:'+str(base_dir/'private/passdb.tdb'),'log file='+str(base_dir/'smb.log')]
  for share,folder,fixed,admin in [('fixed','rw',True,False),('blocked','denied',True,False),('legacy','legacy',False,False),('sgid-raw','sgid',True,False),('admin-baseline','denied',False,True)]:
   lines+=['['+share+']','path='+str(anchor/folder),'read only=no','guest ok=no','valid users='+auth,'write list='+auth,'create mask=0660','directory mask=0770','inherit owner=no']
   if fixed:
    lines+=['force user='+force,'force group='+force,'admin users=','vfs objects=full_audit','full_audit:prefix=EPIC1456|%U|%u|%g|%S|%I','full_audit:success=connect disconnect openat mkdirat pwrite renameat unlinkat','full_audit:failure=none','full_audit:syslog=false']
   if admin:lines+=['admin users='+auth]
  config=base_dir/'smb.conf';config.write_text('\n'.join(lines)+'\n')
  checked(['testparm','-s',str(config)])
  for name,password in passwords.items():checked(['smbpasswd','-c',str(config),'-s','-a',name],input=password+'\n'+password+'\n')
  users=checked(['pdbedit','-s',str(config),'-L']);assert force+':' not in users
  passed('forced identity is absent from isolated Samba passdb')
  def start():
   stream=(base_dir/('daemon-'+str(len(processes))+'.log')).open('w');logs.append(stream)
   process=subprocess.Popen(['smbd','--foreground','--no-process-group','--configfile',str(config)],stdout=stream,stderr=subprocess.STDOUT,start_new_session=True);processes.append(process)
   for _ in range(100):
    if run(['ss','-Hlnt','sport','=',str(port)]).stdout.strip():return process
    if process.poll()is not None:raise RuntimeError('private smbd failed')
    time.sleep(.05)
   raise RuntimeError('private smbd readiness missing')
  def smb(share,user,command,anonymous=False):
   env=os.environ.copy();env['PASSWD']=passwords.get(user,secrets.token_urlsafe(20))
   args=['smbclient','-s',str(config),'//127.0.0.1/'+share,'-p',str(port),'-U',user,'-c',command]
   if anonymous:args+=['-N']
   return run(args,env=env)
  process=start()
  legacy_probe=smb('legacy',auth,'put '+str(src)+' baseline.txt')
  print(json.dumps({'legacyClientDiagnostic':{'rc':legacy_probe.returncode,'stdout':legacy_probe.stdout[-600:],'stderr':legacy_probe.stderr[-600:]}}))
  for label,share,user,cmd in [('fixed file','fixed',auth,'put '+str(src)+' fixed.txt; mkdir created-dir'),('unlisted user','fixed',denied,'put '+str(src)+' denied.txt'),('POSIX denied','blocked',auth,'put '+str(src)+' denied.txt'),('guest denied','fixed','nobody','ls'),('forced identity login denied','fixed',force,'ls')]:
   q=smb(share,user,cmd,anonymous=label=='guest denied')
   if label=='fixed file':
    path=data/'rw'/'fixed.txt';directory=data/'rw'/'created-dir'
    assert q.returncode==0 and path.exists() and directory.exists(),q.stdout+q.stderr
    assert (path.stat().st_uid,path.stat().st_gid)==(uid,gid) and (directory.stat().st_uid,directory.stat().st_gid)==(uid,gid)
    passed(label,uid=path.stat().st_uid,gid=path.stat().st_gid,directoryUid=directory.stat().st_uid,directoryGid=directory.stat().st_gid)
   else:
    assert q.returncode!=0 or 'NT_STATUS_' in q.stdout+q.stderr,(label,q.stdout,q.stderr)
    assert not (data/('denied'if share=='blocked'else'rw')/'denied.txt').exists()
    passed(label,status=[x for x in (q.stdout+q.stderr).splitlines()if 'NT_STATUS_'in x][-1:])
  q=smb('legacy',auth,'put '+str(src)+' legacy.txt');assert (data/'legacy'/'legacy.txt').stat().st_uid==auid,q.stdout+q.stderr
  passed('ordinary authenticated mode keeps creator UID',uid=auid)
  q=smb('admin-baseline',auth,'put '+str(src)+' admin.txt')
  admin_path=data/'denied'/'admin.txt';passed('legacy ADMIN root bypass is isolated evidence',rootOwned=admin_path.exists() and admin_path.stat().st_uid==0)
  q=smb('sgid-raw',auth,'put '+str(src)+' sgid.txt')
  sgid_path=data/'sgid'/'sgid.txt';assert sgid_path.exists(),q.stdout+q.stderr
  passed('raw SGID result recorded; existing policy guard must stay',actualGid=sgid_path.stat().st_gid,requestedGid=gid,parentGid=agid)
  checked(['smbcontrol','-s',str(config),str(process.pid),'reload-config'])
  q=smb('fixed',auth,'put '+str(src)+' after-reload.txt');assert (data/'rw'/'after-reload.txt').stat().st_uid==uid,q.stdout+q.stderr
  passed('private configuration reload keeps fixed UID/GID')
  os.killpg(process.pid,signal.SIGTERM);process.wait(timeout=15);process=start()
  q=smb('fixed',auth,'put '+str(src)+' after-restart.txt');path=data/'rw'/'after-restart.txt'
  assert (path.stat().st_uid,path.stat().st_gid)==(uid,gid),q.stdout+q.stderr
  passed('private daemon restart keeps fixed UID/GID')
  time.sleep(.2)
  audit=[line.strip()for file in base_dir.glob('*.log')for line in file.read_text(errors='replace').splitlines()if 'EPIC1456|'in line]
  assert any('EPIC1456|'+auth+'|'+force+'|'+force+'|fixed|'in s for s in audit),audit[-5:]
  passed('audit retains authenticated user separately from effective user/group',sample=[x for x in audit if 'fixed.txt'in x][:2])
  assert old_meta==(old.stat().st_uid,old.stat().st_gid,old.stat().st_ino,old.stat().st_mode,hashlib.sha256(old.read_bytes()).hexdigest())
  passed('existing file data/owner/group/mode/inode preserved')
  print(json.dumps({'success':True,'provisioning':'SPARSE','filesystem':fs,'samba':checked(['smbd','-V']).strip(),'checks':checks,'scope':'private RAM account overlay and Samba listener, no production API/config apply','modulePathWasTemporary':True}))
 except Exception:
  import re
  diagnostics=[]
  for file in control.glob('samba/*.log'):
   diagnostics += [line for line in file.read_text(errors='replace').splitlines() if re.search('(?i)error|failed|denied|cannot|NT_STATUS|REVISION|EPIC1456.*(fail|create_file|mkdir)',line)][-35:]
  print(json.dumps({'privatePrototypeDiagnostics':diagnostics[-55:]}))
  raise
 finally:
  for process in processes:
   if process.poll()is None:
    os.killpg(process.pid,signal.SIGTERM)
    try:process.wait(timeout=15)
    except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
  for stream in logs:stream.close()
  for target in reversed(anchors):
   subprocess.run(['umount',str(target)],capture_output=True);target.rmdir()
  anchor.rmdir()
  assert data.parent==VOLUME and data.name.startswith('.epic1456-design-') and data.resolve().parent==VOLUME.resolve()
  shutil.rmtree(data)
  # Namespace teardown discards RAM overlay/account hashes; no account files are copied back.
if __name__=='__main__':raise SystemExit(main())
