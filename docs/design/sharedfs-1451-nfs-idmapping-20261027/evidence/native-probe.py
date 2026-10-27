
from pathlib import Path
import subprocess,os,json,time,hashlib,signal,sys
base=Path('/var/tmp/epic1451-idmapping-probe');base.mkdir(mode=0o700,exist_ok=True)
data=Path('/srv/ablestack-storage/volumes/e6468b3c-785b-4808-b746-57bf514f8137/export/epic1451-idmapping-probe')
assert not data.exists();data.mkdir(mode=0o755)
f=data/'owner.txt';f.write_bytes(b'epic1451-identity-preserved');os.chown(f,1,1);os.chmod(f,0o640)
live=Path('/etc/ganesha/ablestack-storage/0.0.0.0_2049.conf')
def before():
 return {'configHash':hashlib.sha256(live.read_bytes()).hexdigest(),'pid':Path('/run/ablestack-storage/ganesha/0.0.0.0_2049.pid').read_text().strip(),'ctlHash':hashlib.sha256(Path('/usr/local/bin/ablestack-storagectl').read_bytes()).hexdigest(),'originalSha':hashlib.sha256((data.parent/'e1450-r4/probe.txt').read_bytes()).hexdigest()}
baseline=before();ns='epic1451-idmap-probe';children=[];result={}
try:
 subprocess.run(['ip','netns','add',ns],check=True,capture_output=True);subprocess.run(['ip','-n',ns,'link','set','lo','up'],check=True)
 subprocess.run(['ip','-n',ns,'link','add','probe0','type','dummy'],check=True)
 subprocess.run(['ip','-n',ns,'addr','add','192.0.2.1/32','dev','probe0'],check=True)
 subprocess.run(['ip','-n',ns,'link','set','probe0','up'],check=True)
 for mode in ['NAME_DOMAIN','NUMERIC','NAME_DOMAIN_RESTORED']:
  d=base/mode;d.mkdir(mode=0o700,exist_ok=True);(d/'recovery').mkdir(exist_ok=True)
  (d/'idmap.conf').write_text('[General]\nDomain = design.test\n[Mapping]\nNobody-User = nobody\nNobody-Group = nogroup\n')
  text=f'''NFS_Core_Param {{
 NFS_Port=23049; Bind_Addr=127.0.0.1; Protocols=4;
 Enable_NLM=false; Enable_RQUOTA=false; Enable_UDP=false; Dbus_Name_Prefix="epic1451{mode.lower().replace('_','')}";
}}
NFSV4 {{
 Grace_Period=0; Graceless=true; RecoveryRoot="{d}/recovery";
 IdmapConf="{d}/idmap.conf";
 Allow_Numeric_Owners=true; Only_Numeric_Owners={'true' if mode=='NUMERIC' else 'false'};
}}
EXPORT_DEFAULTS {{Access_Type=None; Protocols=4; Transports=TCP; SecType=sys;}}
EXPORT {{
 Export_Id=51; Path="{data}"; Pseudo="/probe"; Protocols=4; SecType=sys;
 Access_Type=RW; Squash=No_Root_Squash;
 FSAL {{Name=VFS;}}
 CLIENT {{Clients=127.0.0.1;Access_Type=RW;Squash=No_Root_Squash;}}
}}
'''
  conf=d/'ganesha.conf';conf.write_text(text)
  output=(d/'stdout.log').open('wb')
  proc=subprocess.Popen(['ip','netns','exec',ns,'ganesha.nfsd','-F','-f',str(conf),'-p',str(d/'pid'),'-L',str(d/'ganesha.log')],stdout=output,stderr=subprocess.STDOUT,start_new_session=True);children.append(proc)
  passed=None
  for attempt in range(30):
   if proc.poll() is not None:raise RuntimeError('private ganesha exited '+str(proc.returncode)+' '+(d/'ganesha.log').read_text()[-2500:])
   probe=subprocess.run(['ip','netns','exec',ns,'python3',str(base/'rpc_probe.py'),'23049'],capture_output=True,text=True)
   if probe.returncode==0:passed=json.loads(probe.stdout);break
   time.sleep(.2)
  if passed is None:raise RuntimeError('probe not ready '+probe.stderr[-1800:]+' '+(d/'ganesha.log').read_text()[-1800:])
  result[mode]=passed
  if mode=='NAME_DOMAIN':
   conf.write_text(text.replace('Only_Numeric_Owners=false','Only_Numeric_Owners=true'))
   proc.send_signal(signal.SIGHUP);time.sleep(.3)
   hup=subprocess.run(['ip','netns','exec',ns,'python3',str(base/'rpc_probe.py'),'23049'],capture_output=True,text=True,check=True)
   result['SIGHUP_AFTER_NUMERIC_EDIT']=json.loads(hup.stdout)
  proc.terminate();proc.wait(timeout=8);output.close()
  if mode=='NUMERIC':
   bad=base/'rejected';bad.mkdir(mode=0o700,exist_ok=True)
   badconf=bad/'ganesha.conf';badconf.write_text(text.replace('Only_Numeric_Owners=true','Only_Numeric_Owners=not_a_boolean'))
   badproc=subprocess.Popen(['ip','netns','exec',ns,'ganesha.nfsd','-F','-f',str(badconf),'-p',str(bad/'pid'),'-L',str(bad/'ganesha.log')],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True);children.append(badproc)
   badproc.wait(timeout=8);assert badproc.returncode!=0
   result['invalidCandidateRejected']={'exitcode':badproc.returncode,'restoredAfterward':'NAME_DOMAIN_RESTORED'}
 assert before()==baseline
 result['productionUnchanged']=True
 result['filePreserved']={'uid':f.stat().st_uid,'gid':f.stat().st_gid,'mode':oct(f.stat().st_mode&4095),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
 print(json.dumps(result))
finally:
 for proc in children:
  if proc.poll() is None:
   proc.terminate()
   try:proc.wait(timeout=8)
   except subprocess.TimeoutExpired:proc.kill();proc.wait()
 subprocess.run(['ip','netns','del',ns],capture_output=True)
 if f.exists():f.unlink()
 if data.exists():data.rmdir()
 assert before()==baseline
