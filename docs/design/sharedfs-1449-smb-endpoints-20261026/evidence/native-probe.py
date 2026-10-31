#!/usr/bin/env python3
"""#1449 design feasibility only; isolated net/pid/mount namespaces."""
import os,sys,json,time,signal,socket,subprocess,hashlib,secrets,shutil
from pathlib import Path
root=Path(__file__).resolve().parent
assert os.readlink('/proc/self/ns/net')!=os.environ['EPIC1449_PARENT_NETNS']
assert os.readlink('/proc/self/ns/pid')!=os.environ['EPIC1449_PARENT_PIDNS']
result={'isolated':True,'version':subprocess.check_output(['smbd','--version'],text=True).strip()}
processes=[];handles=[]
def run(args,**kw):
 return subprocess.run(args,capture_output=True,text=True,timeout=12,**kw)
def waitfor(test,seconds=4):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if test():return True
  time.sleep(.1)
 return False
def openport(ip,port):
 try:
  with socket.create_connection((ip,port),.2):return True
 except OSError:return False
def owned_listener(pid,ip,port):
 try:
  inodes={os.readlink(p) for p in Path('/proc/'+str(pid)+'/fd').iterdir()}
  for line in Path('/proc/net/tcp').read_text().splitlines()[1:]:
   fields=line.split()
   host_hex,port_hex=fields[1].split(':')
   host=socket.inet_ntoa(bytes.fromhex(host_hex)[::-1])
   if fields[3]=='0A' and host==ip and int(port_hex,16)==port and 'socket:['+fields[9]+']' in inodes:return True
 except (FileNotFoundError,ProcessLookupError):pass
 return False
def profile(name,pairs,exact=False):
 home=root/name;home.mkdir(exist_ok=True)
 for d in ['lock','state','cache','private','rpc']:
  (root/'common'/d).mkdir(parents=True,exist_ok=True)
 (home/'pid').mkdir(exist_ok=True)
 env={'payload':{'listeners':[{'listenIp':ip,'port':port} for ip,port in pairs]},'netbios_name':'EPIC1449'}
 exec(fragment,env)
 lines=env['lines']
 if exact:
  lines=[s for s in lines if 'interfaces =' not in s]
  lines.append(' interfaces = '+pairs[0][0])
 lines.extend([' security = user',' workgroup = WORKGROUP',
  ' private dir = '+str(root/'common/private'),
  ' passdb backend = tdbsam:'+str(root/'common/private/passdb.tdb'),
  ' lock directory = '+str(root/'common/lock'),
  ' state directory = '+str(root/'common/state'),
  ' cache directory = '+str(root/'common/cache'),
  ' ncalrpc dir = '+str(root/'common/rpc'),
  ' pid directory = '+str(home/'pid'),
  ' log file = '+str(home/'log.%m'),' max log size = 256',
  '[probe]',' path = '+str(root/'data'),' read only = yes',' guest ok = no',' valid users = nobody'])
 cfg=home/'smb.conf'
 if exact:
  base=root/'common/shared-base.conf'
  if not base.exists():base.write_text('\n'.join(lines)+'\n')
  cfg.write_text('[global]\n include = '+str(base)+'\n[global]\n smb ports = '+str(pairs[0][1])+'\n interfaces = '+pairs[0][0]+'\n bind interfaces only = yes\n pid directory = '+str(home/'pid')+'\n log file = '+str(home/'log.%m')+'\n')
 else:cfg.write_text('\n'.join(lines)+'\n')
 p=run(['testparm','-s',str(cfg)]);assert p.returncode==0,p.stderr
 return cfg
def start(cfg):
 log=(cfg.parent/'daemon.log').open('w');handles.append(log)
 p=subprocess.Popen(['smbd','-F','--no-process-group','-s',str(cfg)],stdout=log,stderr=log,start_new_session=True)
 processes.append(p);return p
def readfile(ip,port,cfg,dest):
 p=run(['smbclient','//'+ip+'/probe','-p',str(port),'-U','nobody','-s',str(cfg),'-c','get marker.txt '+str(dest)],env={**os.environ,'PASSWD':password})
 assert p.returncode==0 and dest.read_bytes()==marker,(p.returncode,p.stdout,p.stderr)
def interactive(ip,port,cfg):
 log=(cfg.parent/'client.log').open('w');handles.append(log)
 p=subprocess.Popen(['smbclient','//'+ip+'/probe','-p',str(port),'-U','nobody','-s',str(cfg)],stdin=subprocess.PIPE,stdout=log,stderr=log,text=True,env={**os.environ,'PASSWD':password})
 processes.append(p);return p
def get_existing(client,dest):
 client.stdin.write('get marker.txt '+str(dest)+'\n');client.stdin.flush()
 assert waitfor(lambda:dest.exists()),'persistent SMB read did not complete'
 assert dest.read_bytes()==marker
try:
 for cmd in [['ip','link','set','lo','up'],['ip','link','add','ens8','type','dummy'],['ip','addr','add','10.144.9.2/24','dev','ens8'],['ip','addr','add','10.144.9.3/24','dev','ens8'],['ip','link','set','ens8','up']]:
  subprocess.run(cmd,check=True,capture_output=True)
 os.chmod(root,0o755);(root/'data').mkdir(exist_ok=True);os.chmod(root/'data',0o755)
 marker=b'epic1449-isolated-design-data\n';(root/'data/marker.txt').write_bytes(marker);os.chmod(root/'data/marker.txt',0o644)
 src=Path('/usr/local/bin/ablestack-storagectl').read_text()
 startidx=src.index('listener_rows = [item for item in (payload.get("listeners")')
 fragment=src[startidx:src.index('if ad_joined:',startidx)]
 result['deployedRendererSha256']=hashlib.sha256(fragment.encode()).hexdigest()
 cfg=profile('reload',[('10.144.9.2',14445)])
 password=secrets.token_urlsafe(24)
 auth=run(['smbpasswd','-s','-a','nobody','-c',str(cfg)],input=password+'\n'+password+'\n')
 assert auth.returncode==0,auth.stderr
 daemon=start(cfg);assert waitfor(lambda:openport('10.144.9.2',14445)),'baseline daemon'
 readfile('10.144.9.2',14445,cfg,root/'before')
 client=interactive('10.144.9.2',14445,cfg);get_existing(client,root/'held-before')
 profile('reload',[('10.144.9.2',14445),('10.144.9.3',14445)])
 os.kill(daemon.pid,signal.SIGHUP)
 result['reloadNewIpListens']=waitfor(lambda:openport('10.144.9.3',14445),2)
 get_existing(client,root/'held-after-reload')
 result['reloadOldAuthenticatedSessionPreserved']=client.poll() is None and daemon.poll() is None
 cfgcross=profile('cross',[('10.144.9.2',14446),('10.144.9.3',14447)])
 cross=start(cfgcross);assert waitfor(lambda:openport('10.144.9.2',14446))
 result['currentRendererCartesianBindings']={ip+':'+str(port):openport(ip,port) for ip in ['10.144.9.2','10.144.9.3'] for port in [14446,14447]}
 cfg1=profile('one',[('10.144.9.2',14448)],exact=True);p1=start(cfg1);assert waitfor(lambda:openport('10.144.9.2',14448))
 held=interactive('10.144.9.2',14448,cfg1);get_existing(held,root/'held-one-before')
 cfg2=profile('two',[('10.144.9.3',14448)],exact=True);p2=start(cfg2);assert waitfor(lambda:openport('10.144.9.3',14448))
 readfile('10.144.9.3',14448,cfg2,root/'two-read');get_existing(held,root/'held-one-after-add')
 result['sharedLockStatePrivateDbProfilesAuthenticated']=True
 result['profileIncludesSharedExistingFormatBase']=True
 result['oldSessionPreservedAfterAddingProfile']=held.poll() is None and p1.poll() is None
 result['profileOneOwnsExactSocket']=owned_listener(p1.pid,'10.144.9.2',14448)
 result['profileTwoOwnsExactSocket']=owned_listener(p2.pid,'10.144.9.3',14448)
 assert result['profileOneOwnsExactSocket'] and result['profileTwoOwnsExactSocket']
 holder=socket.socket();holder.bind(('10.144.9.2',14449));holder.listen(3)
 cfgbad=profile('fail',[('10.144.9.2',14449)],exact=True);bad=start(cfgbad)
 available=waitfor(lambda:owned_listener(bad.pid,'10.144.9.2',14449),2)
 assert not available,'candidate unexpectedly owns reserved endpoint'
 result['bindFailureDetectedBySocketOwnership']=True
 result['failedCandidateMayRemainRunning']=bad.poll() is None
 bad.terminate();bad.wait(timeout=3);holder.close()
 get_existing(held,root/'held-one-after-failure');readfile('10.144.9.3',14448,cfg2,root/'two-after-failure')
 result['healthyProfilesAndAuthenticatedSessionPreservedOnFailure']=True
 p2.terminate();p2.wait(timeout=4)
 get_existing(held,root/'held-one-after-delete')
 result['removeOneProfilePreservesOtherAuthenticatedSession']=True
 result['nonRequestedEndpointNotBound']=not openport('10.144.9.2',14449)
 result['ok']=True
except Exception as e:
 result.update(ok=False,error=repr(e))
finally:
 for p in reversed(processes):
  if p.poll() is None:p.terminate()
 for p in reversed(processes):
  try:p.wait(timeout=3)
  except subprocess.TimeoutExpired:p.kill()
 for h in handles:h.close()
 # No operator password or passdb file leaves the disposable fixture.
 shutil.rmtree(root/'common',ignore_errors=True)
 password=None
 (root/'result.json').write_text(json.dumps(result,indent=2))
 print(json.dumps(result),flush=True)
