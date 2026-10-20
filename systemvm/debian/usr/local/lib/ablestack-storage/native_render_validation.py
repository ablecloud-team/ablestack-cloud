# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Fixed read-only validators with isolated, attested Ganesha startup."""
import json
from pathlib import Path
import subprocess

NFS_VALIDATION_ERROR_STAGES={
 "NFS_VALIDATOR_RUNTIME_UNAVAILABLE":"RUNTIME_SELECTION",
 "NFS_VALIDATOR_RUNTIME_UNTRUSTED":"RUNTIME_SELECTION",
 "NFS_VALIDATOR_NAMESPACE_FAILED":"NAMESPACE_SETUP",
 "NFS_VALIDATOR_CHROOT_FAILED":"CHROOT_SETUP",
 "NFS_VALIDATOR_PRIVATE_SERVICES_FAILED":"PRIVATE_SERVICES",
 "NFS_VALIDATOR_PARSER_REJECTED":"PARSER_STARTUP",
 "NFS_VALIDATOR_PARSER_TIMEOUT":"PARSER_STARTUP",
 "NFS_VALIDATOR_WORKER_FAILED":"WORKER_TRANSPORT",
 "NFS_VALIDATOR_WORKER_TIMEOUT":"WORKER_TRANSPORT"}

def native_validation_diagnostic(error):
    value=getattr(error,"diagnostic",None)
    if (type(value)is dict and set(value)=={"protocol","stage","runtimeValidationCode"}
            and value.get("protocol")=="NFS" and type(value.get("runtimeValidationCode"))is str
            and type(value.get("stage"))is str and NFS_VALIDATION_ERROR_STAGES.get(value["runtimeValidationCode"])==value["stage"]):
        return dict(value)
    return None

class NativeRenderValidationError(ValueError):
    def __init__(self,code):
        super().__init__("NFS_VALIDATION_FAILED")
        self.diagnostic={"protocol":"NFS","stage":NFS_VALIDATION_ERROR_STAGES[code],"runtimeValidationCode":code}

GANESHA_NAMESPACE_WORKER = r'''
import hashlib,ipaddress,json,os,re,socket,stat,subprocess,sys,tempfile,time
from pathlib import Path
ERROR_STAGES={
 'NFS_VALIDATOR_RUNTIME_UNAVAILABLE':'RUNTIME_SELECTION',
 'NFS_VALIDATOR_RUNTIME_UNTRUSTED':'RUNTIME_SELECTION',
 'NFS_VALIDATOR_NAMESPACE_FAILED':'NAMESPACE_SETUP',
 'NFS_VALIDATOR_CHROOT_FAILED':'CHROOT_SETUP',
 'NFS_VALIDATOR_PRIVATE_SERVICES_FAILED':'PRIVATE_SERVICES',
 'NFS_VALIDATOR_PARSER_REJECTED':'PARSER_STARTUP',
 'NFS_VALIDATOR_PARSER_TIMEOUT':'PARSER_STARTUP',
 'NFS_VALIDATOR_WORKER_FAILED':'WORKER_TRANSPORT',
 'NFS_VALIDATOR_WORKER_TIMEOUT':'WORKER_TRANSPORT'}
RUNTIME_PREFIX='/opt/ablestack-ganesha/5.5.3'
RUNTIME_FILES=('bin/ganesha.nfsd','lib/x86_64-linux-gnu/libganesha_nfsd.so.5.5.3',
 'lib/x86_64-linux-gnu/libntirpc.so.5.0','lib/x86_64-linux-gnu/ganesha/libfsalvfs.so')
class WorkerFailure(ValueError):
 def __init__(self,code):self.code=code
def require(value,code):
 if not value:raise WorkerFailure(code)
def protected_file(path,limit):
 path=Path(path);before=path.lstat();parent=path.parent.lstat()
 require(stat.S_ISREG(before.st_mode)and before.st_uid==0 and not before.st_mode&0o022
  and before.st_size<=limit and stat.S_ISDIR(parent.st_mode)and parent.st_uid==0 and not parent.st_mode&0o022,'NFS_VALIDATOR_RUNTIME_UNTRUSTED')
 fields=('st_dev','st_ino','st_uid','st_gid','st_mode','st_size','st_mtime_ns','st_ctime_ns')
 signature=lambda info:tuple(getattr(info,k)for k in fields)
 fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
 try:
  require(signature(before)==signature(os.fstat(fd)),'NFS_VALIDATOR_RUNTIME_UNTRUSTED')
  with os.fdopen(fd,'rb',closefd=False)as handle:raw=handle.read(limit+1)
  require(len(raw)<=limit and signature(before)==signature(os.fstat(fd))==signature(path.lstat()),'NFS_VALIDATOR_RUNTIME_UNTRUSTED')
  return raw
 finally:os.close(fd)
def selected_runtime():
 manifest=Path('/etc/ablestack-storage/ganesha-build-manifest.json')
 if manifest.exists()or manifest.is_symlink():
  value=json.loads(protected_file(manifest,64*1024))
  require(type(value.get('schemaVersion'))is int and value['schemaVersion']==1 and value.get('version')=='5.5.3'
   and value.get('prefix')==RUNTIME_PREFIX and set(value.get('files',{}))==set(RUNTIME_FILES),'NFS_VALIDATOR_RUNTIME_UNTRUSTED')
  prefix=Path(RUNTIME_PREFIX)
  for directory in(prefix.parent.parent,prefix.parent,prefix):
   info=directory.lstat()
   require(stat.S_ISDIR(info.st_mode)and info.st_uid==0 and not info.st_mode&0o022,'NFS_VALIDATOR_RUNTIME_UNTRUSTED')
  for name in RUNTIME_FILES:
   digest=value['files'][name]
   require(type(digest)is str and re.fullmatch('[0-9a-f]{64}',digest)
    and hashlib.sha256(protected_file(prefix/name,32*1024*1024)).hexdigest()==digest,'NFS_VALIDATOR_RUNTIME_UNTRUSTED')
  return str(prefix/'bin/ganesha.nfsd'),RUNTIME_PREFIX
 for name in('/usr/bin/ganesha.nfsd','/usr/sbin/ganesha.nfsd'):
  path=Path(name)
  if path.exists()and not path.is_symlink():
   protected_file(path,32*1024*1024);return name,None
 raise WorkerFailure('NFS_VALIDATOR_RUNTIME_UNAVAILABLE')
def run(root,budget):
 end=time.monotonic()+budget;work=end-min(8,budget/4);children=[];stage='RUNTIME_SELECTION'
 defaults={'RUNTIME_SELECTION':'NFS_VALIDATOR_RUNTIME_UNTRUSTED','NAMESPACE_SETUP':'NFS_VALIDATOR_NAMESPACE_FAILED',
  'CHROOT_SETUP':'NFS_VALIDATOR_CHROOT_FAILED','PRIVATE_SERVICES':'NFS_VALIDATOR_PRIVATE_SERVICES_FAILED',
  'PARSER_STARTUP':'NFS_VALIDATOR_PARSER_REJECTED'}
 def remaining(limit):
  value=min(limit,work-time.monotonic())
  if value<=0:raise WorkerFailure('NFS_VALIDATOR_WORKER_TIMEOUT')
  return value
 def command(args):return subprocess.run(args,check=True,capture_output=True,text=True,timeout=remaining(15))
 def stop(child):
  if child.poll()is None:
   child.terminate()
   try:child.wait(timeout=max(.01,min(2,end-time.monotonic())))
   except subprocess.TimeoutExpired:
    child.kill();child.wait(timeout=max(.01,min(1,end-time.monotonic())))
 def start(args):
  child=subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);children.append(child);return child
 outcome={'success':False,'nfsParserVerified':False}
 try:
  try:binary,prefix=selected_runtime()
  except FileNotFoundError:raise WorkerFailure('NFS_VALIDATOR_RUNTIME_UNAVAILABLE')from None
  manifest=json.loads((root/'nfs/manifest.json').read_text())
  stage='NAMESPACE_SETUP'
  command(['mount','--make-rprivate','/'])
  command(['mount','-t','tmpfs','-o','mode=0755','tmpfs','/run'])
  command(['ip','link','set','lo','up']);command(['ip','link','add','parserdummy','type','dummy'])
  command(['ip','addr','add','192.0.2.1/32','dev','parserdummy'])
  for address in sorted({str(ipaddress.IPv4Address(endpoint['listenIp']))for endpoint in manifest.get('endpoints',[])}-{'0.0.0.0','192.0.2.1'}):
   command(['ip','addr','add',address+'/32','dev','parserdummy'])
  command(['ip','link','set','parserdummy','up'])
  stage='CHROOT_SETUP';sandbox=Path('/run/ganesha-parser-root');sandbox.mkdir()
  for name in('usr','etc','dev','proc','tmp','run','run/ganesha','run/dbus','run/rpcbind','var','var/lib',
   'var/lib/nfs','var/lib/nfs/ganesha','var/lib/nfs/ganesha/v4recov','var/lib/nfs/ganesha/v4old',
   'var/lib/nfs/statd','var/lib/nfs/statd/sm','var/lib/nfs/statd/sm.bak','var/lib/rpcbind','var/lib/dbus','candidate','export'):
   (sandbox/name).mkdir(exist_ok=True)
  (sandbox/'var/run').symlink_to('/run')
  for name in('usr','etc','dev'):
   command(['mount','--bind','/'+name,str(sandbox/name)])
   command(['mount','-o','remount,bind,ro',str(sandbox/name)])
  for name in('lib','lib64','bin','sbin'):
   source=Path('/'+name)
   if source.is_symlink():(sandbox/name).symlink_to(os.readlink(source))
   elif source.is_dir():
    (sandbox/name).mkdir();command(['mount','--bind',str(source),str(sandbox/name)])
    command(['mount','-o','remount,bind,ro',str(sandbox/name)])
  if prefix is not None:
   target=sandbox/prefix.lstrip('/');target.mkdir(parents=True)
   command(['mount','--bind',prefix,str(target)])
   command(['mount','-o','remount,bind,ro',str(target)])
  command(['mount','--bind','/proc',str(sandbox/'proc')])
  command(['mount','--bind',str(root),str(sandbox/'candidate')])
  command(['mount','-o','remount,bind,ro',str(sandbox/'candidate')])
  for value in manifest.get('aliases',{}).values():
   alias=value['aliasPath'];backing=value['backingPath']
   require(alias.startswith('/export/')and len(Path(alias).parts)==3,'NFS_VALIDATOR_CHROOT_FAILED')
   target=sandbox/alias.lstrip('/');target.mkdir()
   command(['mount','--bind',backing,str(target)])
   command(['mount','-o','remount,bind,ro',str(target)])
  os.chroot(sandbox);os.chdir('/');root=Path('/candidate')
  stage='PRIVATE_SERVICES'
  os.environ['DBUS_SYSTEM_BUS_ADDRESS']='unix:path=/run/dbus/system_bus_socket'
  dbus_config=Path('/run/dbus/validator.conf')
  dbus_config.write_text('<busconfig><type>system</type><listen>unix:path=/run/dbus/system_bus_socket</listen>'
   '<auth>EXTERNAL</auth><policy user="root"><allow own="*"/><allow send_destination="*"/>'
   '<allow receive_sender="*"/></policy></busconfig>')
  dbus_config.chmod(0o600)
  bus=start(['/usr/bin/dbus-daemon','--nofork','--nopidfile','--config-file='+str(dbus_config)])
  rpc=start(['/usr/sbin/rpcbind','-f'])
  ready=False
  while time.monotonic()<min(work,end-2):
   require(bus.poll()is None and rpc.poll()is None,'NFS_VALIDATOR_PRIVATE_SERVICES_FAILED')
   sock=Path('/run/dbus/system_bus_socket')
   try:
    if sock.exists()and stat.S_ISSOCK(sock.stat().st_mode):
     with socket.create_connection(('127.0.0.1',111),timeout=remaining(.2)):ready=True
   except OSError:pass
   if ready:break
   remaining(.1);time.sleep(.05)
  require(ready,'NFS_VALIDATOR_PRIVATE_SERVICES_FAILED')
  stage='PARSER_STARTUP'
  for endpoint in manifest.get('endpoints',[]):
   path=root/endpoint['configurationPath']
   with tempfile.TemporaryDirectory(prefix='ganesha-validator-',dir='/run')as directory:
    directory=Path(directory);log=directory/'parser.log'
    server=start([binary,'-F','-f',str(path),'-p',str(directory/'pid'),'-L',str(log),'-N','NIV_EVENT'])
    try:
     deadline=min(work,time.monotonic()+8);ready=False
     while time.monotonic()<deadline:
      if server.poll()is not None:break
      require(not log.exists()or log.stat().st_size<=4*1024*1024,'NFS_VALIDATOR_PARSER_REJECTED')
      text=log.read_text(errors='replace')if log.exists()else''
      if any(word in text for word in('Parse failed','Could not parse','Configuration file not found','Error while parsing','Unknown parameter','CONFIG :CRIT','CONFIG :FATAL','NFS STARTUP :CRIT','NFS STARTUP :FATAL')):break
      if 'NFS SERVER INITIALIZED'in text:ready=True;break
      time.sleep(.1)
     if not ready:
      raise WorkerFailure('NFS_VALIDATOR_PARSER_TIMEOUT'if server.poll()is None and time.monotonic()>=deadline else'NFS_VALIDATOR_PARSER_REJECTED')
    finally:stop(server);children.remove(server)
  outcome={'success':True,'nfsParserVerified':True}
 except Exception as error:
  code=error.code if isinstance(error,WorkerFailure)and error.code in ERROR_STAGES else defaults[stage]
  outcome['diagnostic']={'protocol':'NFS','stage':ERROR_STAGES[code],'runtimeValidationCode':code}
 finally:
  for child in reversed(children):
   try:stop(child)
   except Exception:
    if outcome['success']:
     code='NFS_VALIDATOR_PRIVATE_SERVICES_FAILED'
     outcome={'success':False,'nfsParserVerified':False,'diagnostic':{'protocol':'NFS','stage':ERROR_STAGES[code],'runtimeValidationCode':code}}
 return outcome
if __name__=='__main__':
 result=run(Path(sys.argv[1]),float(sys.argv[2])if len(sys.argv)>2 else 118)
 print(json.dumps(result),flush=True)
 raise SystemExit(0 if result['success']else 1)
'''


def validate_smb_candidate(root, timeout=20):
    result=subprocess.run(['testparm','-s',str(Path(root)/'smb/smb.conf')],capture_output=True,text=True,timeout=timeout)
    if result.returncode:raise ValueError('Samba testparm rejected the staged configuration')
    return True


def validate_nfs_candidate(root, timeout=120):
    root=Path(root);manifest=json.loads((root/'nfs/manifest.json').read_text())
    if not manifest.get('endpoints'):return True
    try:
        result=subprocess.run(['unshare','--mount','--net','--pid','--fork','--kill-child=SIGKILL','--mount-proc','python3','-c',GANESHA_NAMESPACE_WORKER,str(root),str(max(.1,float(timeout)-2))],capture_output=True,text=True,timeout=timeout)
    except subprocess.TimeoutExpired:
        raise NativeRenderValidationError("NFS_VALIDATOR_WORKER_TIMEOUT")from None
    try:
        if len(result.stdout)>64*1024:raise ValueError("bounded reply required")
        outcome=json.loads(result.stdout)
        if result.returncode==0 and outcome=={"success":True,"nfsParserVerified":True}:return True
        diagnostic=native_validation_diagnostic(type("WorkerResult",(),{"diagnostic":outcome.get("diagnostic")})())
        if diagnostic is None or outcome.get("success")is not False or outcome.get("nfsParserVerified")is not False:
            raise ValueError("closed worker failure required")
    except (ValueError,TypeError,AttributeError):
        raise NativeRenderValidationError("NFS_VALIDATOR_WORKER_FAILED")from None
    raise NativeRenderValidationError(diagnostic["runtimeValidationCode"])
