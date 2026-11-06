#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Isolated design feasibility: real Ganesha, SPARSE DATA dirs and namespace mounts."""
from pathlib import Path
import os,sys,json,subprocess,tempfile,uuid,time,signal,shutil,hashlib,ast,ipaddress,concurrent.futures,socket
def cmd(args,timeout=30,check=True,**kw):
 r=subprocess.run(args,capture_output=True,text=True,timeout=timeout,**kw)
 if check and r.returncode:raise RuntimeError(str(args[:3])+': '+r.stderr[-1600:])
 return r
if os.environ.get('EPIC1459_PRIVATE_NS')!='1':
 body=Path('/var/tmp/epic1459-native-'+uuid.uuid4().hex[:8]+'.py');body.write_text(Path(__file__).read_text());body.chmod(0o700)
 try:
  r=cmd(['unshare','--mount','--propagation','private',sys.executable,str(body)],timeout=180,check=False,env={**os.environ,'EPIC1459_PRIVATE_NS':'1'})
  print(r.stdout);print(r.stderr,file=sys.stderr)
 finally:body.unlink(missing_ok=True)
 raise SystemExit(r.returncode)
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='2e65bb57-9f05-39ef-bd89-08daadd33364'
volume=Path('/srv/ablestack-storage/volumes/f79e348b-a3db-45c1-bd68-77302df8041f')
assert cmd(['findmnt','-n','-M',str(volume),'-o','FSTYPE']).stdout.strip()=='xfs'
nonce=uuid.uuid4().hex[:8];control=Path('/var/tmp/epic1459-native-'+nonce);data=volume/('.epic1459-design-'+nonce);ns='epic1459-'+nonce
children=[];logs=[];made_ns=False;checks=[]
paths=[Path('/usr/local/bin/ablestack-storagectl'),Path('/usr/local/bin/ablestack-storage-boot-reconcile'),Path('/etc/fstab'),*Path('/etc/ganesha/ablestack-storage').glob('*.conf'),*Path('/etc/ablestack-storage/desired-state').glob('*.json')]
def protected():
 return {'files':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.exists()},'pids':{p.name:p.read_text() for p in Path('/run/ablestack-storage/ganesha').glob('*.pid')},'net':cmd(['ip','-j','addr','show','dev','eth0']).stdout,'seed':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in volume.glob('export/cap1458-root/team/*') if p.is_file()}}
before=protected();result={'success':False,'kind':'private SystemVM design feasibility; not product acceptance','operatorProvisioning':'SPARSE','checks':checks}
def execute_bounded(args,deadline=3):
 p=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,start_new_session=True)
 try:
  out,err=p.communicate(timeout=deadline)
  return {'rc':p.returncode,'stdout':out,'stderr':err,'timeout':False}
 except subprocess.TimeoutExpired:
  os.killpg(p.pid,signal.SIGTERM)
  try:out,err=p.communicate(timeout=.5)
  except subprocess.TimeoutExpired:
   os.killpg(p.pid,signal.SIGKILL);out,err=p.communicate(timeout=2)
  return {'rc':p.returncode,'stdout':out,'stderr':err,'timeout':True}
def start(port,count):
 conf=control/('nfs-'+str(port)+'.conf');recovery=control/('recover-'+str(port));recovery.mkdir(exist_ok=True)
 text='NFS_Core_Param { NFS_Port='+str(port)+'; Bind_addr=0.0.0.0; Protocols=4; Enable_NLM=false; Enable_RQUOTA=false; Enable_UDP=false; }\n'
 text+='NFSV4 { Grace_Period=0; Graceless=true; RecoveryRoot='+json.dumps(str(recovery))+'; Allow_Numeric_Owners=true; Only_Numeric_Owners=true; }\n'
 text+='EXPORT_DEFAULTS { Access_Type=None; Protocols=4; Transports=TCP; SecType=sys; }\n'
 for i in range(count):
  text+='EXPORT { Export_Id='+str(14590+i)+'; Path='+json.dumps(str(data/('e'+str(i))))+'; Pseudo="/e'+str(i)+'"; Protocols=4; FSAL { Name=VFS; } CLIENT { Clients=192.0.2.59; Access_Type=RO; Squash=Root_Squash; } }\n'
 conf.write_text(text);log=(control/('out-'+str(port))).open('a');logs.append(log)
 p=subprocess.Popen(['ip','netns','exec',ns,'ganesha.nfsd','-F','-f',str(conf),'-p',str(control/(str(port)+'.pid')),'-L',str(control/(str(port)+'.log'))],stdout=log,stderr=log,start_new_session=True);children.append(p)
 for _ in range(60):
  if p.poll() is not None:raise RuntimeError('private ganesha exited '+(control/(str(port)+'.log')).read_text(errors='replace')[-1200:])
  x=cmd(['ip','netns','exec',ns,'python3','-c','import socket;s=socket.create_connection(("192.0.2.59",'+str(port)+'),.1);s.close()'],timeout=3,check=False)
  if not x.returncode:return p
  time.sleep(.1)
 raise RuntimeError('private listener not ready')
def stop(p):
 if p.poll() is None:
  os.killpg(p.pid,signal.SIGTERM)
  try:p.wait(timeout=5)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
WORKER='import os,sys,json,subprocess\nfrom pathlib import Path\nm=Path(sys.argv[1]);port=sys.argv[2];i=sys.argv[3];target=sys.argv[4]\nr=subprocess.run(["mount","-t","nfs4","-o","vers=4.1,proto=tcp,port="+port+",ro,soft,timeo=1,retrans=1,nosharecache",target,str(m)],capture_output=True,text=True,timeout=3)\ntry:\n if r.returncode==0:assert (m/"seed").read_text()=="1459-design-"+i\n print(json.dumps({"success":r.returncode==0,"timeout":False,"mounted":os.path.ismount(m),"error":r.stderr[-300:]}))\nfinally:\n if os.path.ismount(m):subprocess.run(["umount",str(m)],capture_output=True,timeout=3,check=True)\n'
def one(task,broken=False):
 port,i=task;mount=control/("mount-"+str(port)+"-"+str(i));mount.mkdir(exist_ok=True)
 target="192.0.2.59:/"+("absent" if broken else "e"+str(i))
 r=execute_bounded(["ip","netns","exec",ns,"python3","-c",WORKER,str(mount),str(port),str(i),target],deadline=6)
 try:
  if r["timeout"]:return {"success":False,"timeout":True,"error":r["stderr"][-300:]}
  if r["rc"]:raise RuntimeError(r["stderr"][-1600:])
  return json.loads(r["stdout"])
 finally:
  if not os.path.ismount(mount):mount.rmdir()

try:
 control.mkdir(mode=0o755);data.mkdir(mode=0o755)
 for i in range(100):
  d=data/('e'+str(i));d.mkdir(mode=0o755);(d/'seed').write_text('1459-design-'+str(i))
 cmd(['ip','netns','add',ns]);made_ns=True
 cmd(['ip','-n',ns,'link','set','lo','up']);cmd(['ip','-n',ns,'link','add','probe0','type','dummy']);cmd(['ip','-n',ns,'addr','add','192.0.2.59/32','dev','probe0']);cmd(['ip','-n',ns,'link','set','probe0','up'])
 for count in (1,10,100):
  processes=[start(p,count) for p in (23049,23050)]
  begin=time.monotonic();tasks=[(p,i) for p in (23049,23050) for i in range(count)]
  with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:observed=list(pool.map(one,tasks))
  assert all(r['success'] for r in observed),[r for r in observed if not r['success']][:2]
  checks.append({'name':'real V4 Ganesha exports on two private listeners','exports':count,'probes':len(tasks),'concurrency':4,'elapsedSeconds':round(time.monotonic()-begin,3),'passed':True})
  if count==100:
   pids=[p.pid for p in processes]
   bad=one((23049,99),broken=True);assert not bad['success'];assert all(p.poll() is None for p in processes)
   good=one((23049,99));assert good['success'];assert pids==[p.pid for p in processes]
   checks.append({'name':'one failed private export probe then observe-only recovery; listener PIDs unchanged','passed':True,'firstFailure':bad})
  for p in processes:stop(p)
 # Real child-process timeout: start a sleeping descendant and prove its group dies.
 marker=control/'descendant.pid'
 r=execute_bounded(['python3','-c','import subprocess,time;from pathlib import Path;p=subprocess.Popen(["sleep","30"]);Path('+repr(str(marker))+').write_text(str(p.pid));time.sleep(30)'],deadline=.2)
 assert r['timeout'];child=int(marker.read_text())
 state=Path('/proc')/str(child)/'stat';child_running=state.exists() and state.read_text().split()[2] not in ('Z','X')
 assert not child_running
 checks.append({'name':'real timeout terminates process group including descendant and reaps parent','passed':True,'descendantRunning':False})
 result['success']=True
except BaseException as e:
 import traceback
 result['error']={'type':type(e).__name__,'message':str(e),'trace':traceback.format_exc()}
 result['logs']={p.name:p.read_text(errors='replace')[-1500:] for p in control.glob('*.log')}
finally:
 for p in children:stop(p)
 for log in logs:log.close()
 for p in control.glob('mount-*'):
  if os.path.ismount(p):cmd(['umount',str(p)],check=False)
 if made_ns:cmd(['ip','netns','del',ns],check=False)
 assert data.parent==volume and data.name.startswith('.epic1459-design-')
 if data.exists():shutil.rmtree(data)
 if control.exists():shutil.rmtree(control)
 result['protectedUnchanged']=before==protected();result['temporaryResourcesRemoved']=not data.exists() and not control.exists()
 result['success']=result['success'] and result['protectedUnchanged'] and result['temporaryResourcesRemoved']
print(json.dumps(result))
raise SystemExit(0 if result['success'] else 1)
