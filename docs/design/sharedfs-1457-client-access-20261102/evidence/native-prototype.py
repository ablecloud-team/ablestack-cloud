# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
# Design-only private native experiment. Not imported by the product.
from pathlib import Path
import os,sys,json,subprocess,tempfile,time,secrets,signal,shutil,hashlib,re,ipaddress
VOLUME=Path('/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277')
DMI='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
def run(a,**kw):return subprocess.run(a,capture_output=True,text=True,timeout=20,**kw)
def checked(a,**kw):
 r=run(a,**kw)
 if r.returncode:raise RuntimeError(str(a[:3])+': '+r.stderr[-700:]+r.stdout[-700:])
 return r.stdout
def hashes():
 paths=[Path('/etc/passwd'),Path('/etc/group'),Path('/etc/shadow'),Path('/etc/gshadow'),Path('/etc/samba/smb.conf'),Path('/etc/ablestack-storage/desired-state/smb-share-apply.json'),*Path('/etc/ablestack-storage/smb-listeners').glob('*.conf')]
 return {str(p):hashlib.sha256(p.read_bytes()).hexdigest()for p in paths}
def normalize(values):
 if not isinstance(values,list)or len(values)>32:raise ValueError('LIST')
 out=[]
 for value in values:
  if not isinstance(value,str)or '%'in value or not re.fullmatch(r'[0-9A-Fa-f:./]+',value.strip()):raise ValueError('LITERAL')
  raw=value.strip()
  if '/'in raw and not re.fullmatch(r'[0-9]+',raw.split('/')[1]):raise ValueError('PREFIX')
  net=ipaddress.ip_network(raw,strict=False)
  if net.prefixlen==0 or net.network_address.is_multicast or net.network_address.is_unspecified:raise ValueError('ANY_OR_MULTICAST')
  item=str(net)
  if item in out:raise ValueError('DUPLICATE')
  out.append(item)
 return sorted(out,key=lambda n:(ipaddress.ip_network(n).version,int(ipaddress.ip_network(n).network_address),ipaddress.ip_network(n).prefixlen))
def main():
 assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()==DMI
 if '--inner'not in sys.argv:
  before=hashes()
  r=subprocess.run(['unshare','--mount','--net','--propagation','private',sys.executable,__file__,'--inner'],capture_output=True,text=True,timeout=160)
  print(r.stdout);print(r.stderr,file=sys.stderr)
  assert before==hashes(),'production file mutation'
  print(json.dumps({'productionFilesUnchanged':True,'namespaceIsolation':'private mount+network, RAM /etc and Samba state','productionApply':False}))
  return r.returncode
 checked(['ip','link','set','lo','up'])
 control=Path(tempfile.mkdtemp(prefix='epic1457-native-',dir='/var/tmp'))
 data=VOLUME/('.epic1457-design-'+secrets.token_hex(8));data.mkdir(mode=0o755)
 processes=[];streams=[];clients={};checks=[]
 def passed(name,**detail):checks.append({'name':name,'passed':True,**detail})
 try:
  checked(['mount','-t','tmpfs','-o','size=48m,mode=0700,nosuid,nodev','tmpfs',str(control)])
  for n in ['lower','upper','work']:(control/n).mkdir()
  checked(['mount','--bind','/etc',str(control/'lower')]);checked(['mount','-o','remount,bind,ro',str(control/'lower')])
  checked(['mount','-t','overlay','overlay','-o','lowerdir='+str(control/'lower')+',upperdir='+str(control/'upper')+',workdir='+str(control/'work'),'/etc'])
  sys.path.insert(0,'/opt/ablestack/storage-runtime/current/lib/ablestack-storage')
  from shared_directory_policy import SmbCreationPolicies
  checked(['ip','link','add','b1457','type','bridge']);checked(['ip','link','set','b1457','up'])
  checked(['ip','addr','add','10.145.7.1/24','dev','b1457'])
  checked(['ip','-6','addr','add','fd00:1457::1/64','dev','b1457','nodad'])
  for n in [1,2]:
   stream=(control/('net'+str(n)+'.log')).open('w');streams.append(stream)
   child=subprocess.Popen(['unshare','--net','sleep','150'],stdout=stream,stderr=stream,start_new_session=True);processes.append(child)
   for _ in range(50):
    if Path('/proc/%d/ns/net'%child.pid).readlink()!=Path('/proc/self/ns/net').readlink():break
    time.sleep(.03)
   clients[n]=child.pid
   checked(['ip','link','add','s'+str(n),'type','veth','peer','name','c'+str(n)])
   checked(['ip','link','set','s'+str(n),'master','b1457']);checked(['ip','link','set','s'+str(n),'up'])
   checked(['ip','link','set','c'+str(n),'netns',str(child.pid)])
   prefix=['nsenter','-t',str(child.pid),'--net','--']
   for a in [['ip','link','set','lo','up'],['ip','link','set','c'+str(n),'up'],['ip','addr','add','10.145.7.'+str(10+n)+'/24','dev','c'+str(n)],['ip','-6','addr','add','fd00:1457::'+str(10+n)+'/64','dev','c'+str(n),'nodad']]:checked(prefix+a)
  import pwd
  uid=next(i for i in range(8100,9000)if run(['getent','passwd',str(i)]).returncode and run(['getent','group',str(i)]).returncode)
  auth='e1457auth';denied='e1457deny'
  for name,i in [(auth,uid),(denied,uid+1)]:
   checked(['groupadd','-g',str(i),name]);checked(['useradd','-r','-u',str(i),'-g',str(i),'-M','-N','-l','-s','/usr/sbin/nologin',name])
  fixed=pwd.getpwnam('asfs_u_c1eca707_7001');assert (fixed.pw_uid,fixed.pw_gid)==(7001,7002)
  for name,u,g,mode in [('target',7001,7002,0o770),('other',uid,uid,0o770),('posix',0,0,0o700)]:
   path=data/name;path.mkdir();os.chown(path,u,g);path.chmod(mode)
  old=data/'target/preserved.txt';old.write_text('preserved-native-1457\n');os.chown(old,7001,7002);old.chmod(0o660)
  def metadata(p):s=p.stat();return (s.st_uid,s.st_gid,s.st_ino,s.st_mode,hashlib.sha256(p.read_bytes()).hexdigest())
  old_meta=metadata(old)
  src=control/'source';src.write_text('private-native-1457\n')
  base=control/'smb.conf'
  folders={}
  for key,name in [('private dir','private'),('state directory','state'),('lock directory','lock'),('cache directory','cache'),('ncalrpc dir','rpc')]:
   folders[key]=control/name;folders[key].mkdir()
  global_lines=['[global]','server role=standalone server','security=user','map to guest=Never','netbios name=EPICNET','load printers=no','printing=bsd','disable spoolss=yes','log level=1','passdb backend=tdbsam:'+str(control/'private/passdb.tdb'),'log file='+str(control/'smb-%I.log')]
  global_lines += [k+'='+str(v)for k,v in folders.items()]
  def config(cidrs=None,ipv6=False):
   lines=list(global_lines)
   for name in ['target','other','posix']:
    lines+=['['+name+']','path='+str(data/name),'read only=no','guest ok=no','valid users='+auth,'write list='+auth,'create mask=0660','directory mask=0770','admin users=']
    if name in ['target','posix']:lines+=['force user='+fixed.pw_name,'force group=asfs_g_c1eca707_7002']
    if name=='target' and cidrs:
     lines+=['hosts allow='+' '.join(cidrs+['127.0.0.1/32','::1/128']),'hosts deny=ALL']
   return '\n'.join(lines)+'\n'
  base.write_text(config());base.chmod(0o600)
  passwords={auth:secrets.token_urlsafe(24),denied:secrets.token_urlsafe(24)}
  for user,pw in passwords.items():checked(['smbpasswd','-c',str(base),'-s','-a',user],input=pw+'\n'+pw+'\n')
  profiles=[];daemons=[]
  for port in [445,1445]:
   pid_dir=control/('pid'+str(port));pid_dir.mkdir()
   path=control/('port'+str(port)+'.conf')
   path.write_text('[global]\ninclude='+str(base)+'\n[global]\nsmb ports='+str(port)+'\ninterfaces=lo b1457\nbind interfaces only=yes\npid directory='+str(pid_dir)+'\n');path.chmod(0o600)
   profiles.append(path)
  def start():
   rows=[]
   for path in profiles:
    f=(control/(path.stem+'-daemon.log')).open('a');streams.append(f)
    child=subprocess.Popen(['smbd','-F','--no-process-group','--configfile='+str(path)],stdout=f,stderr=f,start_new_session=True);processes.append(child);daemons.append(child);rows.append((path,child.pid))
   for _ in range(80):
    if all(str(p)in checked(['ss','-Hlnt'])for p in [445,1445]):break
    if any(x.poll()is not None for x in daemons):raise RuntimeError('private daemon startup')
    time.sleep(.05)
   return SmbCreationPolicies(base=base,desired=control/'desired.json',profiles=rows)
  engine=start()
  def smb(client,share,command,user=auth,port=445,v6=False):
   env=os.environ.copy();env['PASSWD']=passwords[user]
   prefix=['nsenter','-t',str(clients[client]),'--net','--'] if client else []
   address='fd00:1457::1'if v6 else '10.145.7.1'if client else '127.0.0.1'
   return run(prefix+['smbclient','//EPICNET/'+share,'-I',address,'-p',str(port),'-U',user,'-c',command],env=env)
  def allowed(r):return r.returncode==0 and 'NT_STATUS_'not in r.stdout+r.stderr
  def must_allow(client,share,label,**kw):
   r=smb(client,share,'put '+str(src)+' '+label+'.txt; get preserved.txt '+str(control/('read-'+label)) if share=='target'else'put '+str(src)+' '+label+'.txt',**kw)
   assert allowed(r),(label,r.returncode,r.stdout,r.stderr);passed(label)
  def must_deny(client,share,label,**kw):
   r=smb(client,share,'put '+str(src)+' should-not-exist-'+label,**kw)
   assert not allowed(r),(label,r.stdout,r.stderr);assert not (data/share/('should-not-exist-'+label)).exists()
   passed(label,ntstatus=[l for l in (r.stdout+r.stderr).splitlines()if 'NT_STATUS_'in l][-1:])
  for port in [445,1445]:
   for client in [1,2]:must_allow(client,'target','any-source-'+str(client)+'-'+str(port),port=port)
  normalized=normalize(['10.145.7.11','10.145.7.128/25']);assert normalized==['10.145.7.11/32','10.145.7.128/25']
  for invalid in [['host.local'],['*.local'],['ALL'],['10.145.7.0/255.255.255.0'],['10.145.7.11','10.145.7.11/32'],['0.0.0.0/0'],['fe80::1%eth0'],['10.1.1.1\nhosts allow=ALL']]:
   try:normalize(invalid)
   except ValueError:continue
   raise AssertionError('invalid literal accepted')
  passed('literal CIDR normalization and duplicate/hostname/wildcard/injection/zero-prefix rejection')
  base.write_text(config(['10.145.7.11/32']));engine.reload({'profiles':engine.profiles()})
  other_section=base.read_text().split('[other]')[1].split('[posix]')[0]
  for port in [445,1445]:
   must_allow(1,'target','allow-c1-'+str(port),port=port);must_deny(2,'target','deny-c2-'+str(port),port=port)
   must_allow(2,'other','other-unchanged-'+str(port),port=port)
   must_deny(1,'target','account-acl-'+str(port),user=denied,port=port)
   must_deny(1,'posix','posix-'+str(port),port=port)
  must_allow(0,'target','necessary-loopback')
  base.write_text(config(['10.145.7.0/24']));engine.reload({'profiles':engine.profiles()})
  must_allow(1,'target','subnet-c1');must_allow(2,'target','subnet-c2')
  base.write_text(config(['10.145.7.11/32']));engine.reload({'profiles':engine.profiles()})
  env=os.environ.copy();env['PASSWD']=passwords[auth]
  held_log=control/'held.log';f=held_log.open('w');streams.append(f)
  held=subprocess.Popen(['nsenter','-t',str(clients[1]),'--net','--','smbclient','//EPICNET/target','-I','10.145.7.1','-p','445','-U',auth,'-c','get preserved.txt '+str(control/'held-before')+'; ! sleep 4; put '+str(src)+' held-after-reload.txt'],stdout=f,stderr=f,env=env,start_new_session=True);processes.append(held)
  for _ in range(50):
   if (control/'held-before').exists():break
   time.sleep(.05)
  assert (control/'held-before').exists(),'held client not connected'
  base.write_text(config(['10.145.7.12/32']));engine.reload({'profiles':engine.profiles()})
  held.wait(timeout=12);assert held.returncode==0 and (data/'target/held-after-reload.txt').exists(),held_log.read_text()
  passed('existing tree connection remains after policy reload; explicit UI scope required')
  must_deny(1,'target','revoked-new-tree');must_allow(2,'target','newly-allowed-c2')
  assert other_section==base.read_text().split('[other]')[1].split('[posix]')[0]
  passed('target-only section edit preserves other share bytes and both endpoint processes')
  saved=base.read_bytes();snapshot={'profiles':engine.profiles()}
  try:
   base.write_text(config(['10.145.7.11/32']))
   checked(['smbcontrol','-s',snapshot['profiles'][0]['config'],str(snapshot['profiles'][0]['pid']),'reload-config'])
   raise RuntimeError('controlled second-listener reload failure')
  except RuntimeError:
   SmbCreationPolicies.write_file(base,saved,{'mode':0o600,'uid':0,'gid':0});engine.reload(snapshot)
  assert base.read_bytes()==saved
  must_deny(1,'target','rollback-c1-denied');must_allow(2,'target','rollback-c2-allowed')
  passed('controlled partial reload rollback restores original bytes and behavior')
  base.write_text(config([]));engine.reload({'profiles':engine.profiles()})
  must_allow(1,'target','explicit-empty-any-source');must_allow(2,'target','explicit-empty-c2')
  ipv6_previous_mtime=base.stat().st_mtime_ns
  time.sleep(1.1)
  base.write_text(config(['fd00:1457::11/128']));engine.reload({'profiles':engine.profiles()})
  ipv6observations=[]
  for n in [1,2]:
   r=smb(n,'target','put '+str(src)+' ipv6-probe-'+str(n)+'.txt',v6=True)
   route=checked(['nsenter','-t',str(clients[n]),'--net','--','ip','-6','route','get','fd00:1457::1'])
   ipv6observations.append({'client':n,'allowed':allowed(r),'route':route.strip(),'effectiveAllow':checked(['testparm','-s','--section-name=target','--parameter-name=hosts allow',str(profiles[0])]).strip(),'previousMtimeNs':ipv6_previous_mtime,'candidateMtimeNs':base.stat().st_mtime_ns,'effectiveDeny':checked(['testparm','-s','--section-name=target','--parameter-name=hosts deny',str(profiles[0])]).strip()})
  passed('IPv6 capability measured only; product design rejects IPv6 until endpoint and enforcement are proven',observations=ipv6observations)

  base.write_text(config(['10.145.7.11/32']));engine.reload({'profiles':engine.profiles()})
  for child in daemons:
   os.killpg(child.pid,signal.SIGTERM);child.wait(timeout=8)
  daemons.clear();engine=start()
  must_allow(1,'target','private-restart-allowed');must_deny(2,'target','private-restart-denied')
  assert metadata(old)==old_meta;passed('existing SPARSE XFS file data owner mode inode preserved')
  print(json.dumps({'success':True,'checks':checks,'count':len(checks),'samba':checked(['smbd','-V']).strip(),'filesystem':checked(['findmnt','-n','-M',str(VOLUME),'-o','UUID,FSTYPE']).strip(),'nativeIpv6':{'observed':ipv6observations,'productSupport':False},'existingTreeApplyScope':'NEW_CONNECTIONS','productionApply':False}))
 finally:
  for child in reversed(processes):
   if child.poll()is None:
    try:os.killpg(child.pid,signal.SIGTERM);child.wait(timeout=5)
    except Exception:
     try:os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=5)
     except ProcessLookupError:pass
  for stream in streams:stream.close()
  assert data.parent==VOLUME and data.name.startswith('.epic1457-design-') and data.resolve().parent==VOLUME.resolve()
  shutil.rmtree(data)
  checked(["umount","/etc"]);checked(["umount",str(control/"lower")]);checked(["umount",str(control)]);control.rmdir()
if __name__=='__main__':raise SystemExit(main())
