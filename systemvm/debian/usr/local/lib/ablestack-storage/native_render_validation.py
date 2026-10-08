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

"""Fixed read-only protocol validators; Ganesha runs in private mount/net/PID namespaces."""
import json
from pathlib import Path
import subprocess
import time

GANESHA_NAMESPACE_WORKER = r'''
import ipaddress,json,os,subprocess,sys,tempfile,time
from pathlib import Path
root=Path(sys.argv[1]);manifest=json.loads((root/'nfs/manifest.json').read_text())
def command(args):return subprocess.run(args,check=True,capture_output=True,text=True,timeout=15)
command(['mount','--make-rprivate','/'])
command(['mount','-t','tmpfs','-o','mode=0755','tmpfs','/run'])
command(['ip','link','set','lo','up'])
command(['ip','link','add','parserdummy','type','dummy'])
command(['ip','addr','add','192.0.2.1/32','dev','parserdummy'])
for address in sorted({str(ipaddress.IPv4Address(endpoint['listenIp'])) for endpoint in manifest.get('endpoints',[])}-{'0.0.0.0','192.0.2.1'}):
 command(['ip','addr','add',address+'/32','dev','parserdummy'])
command(['ip','link','set','parserdummy','up'])
sandbox=Path('/run/ganesha-parser-root');sandbox.mkdir()
# A private chroot gives the parser writable runtime state and alias directories
# without creating anything under the live /export or DATA filesystem.
for name in ('usr','etc','dev','proc','tmp','run','var','var/lib','var/lib/nfs','var/lib/nfs/ganesha','candidate','export'):
 (sandbox/name).mkdir(exist_ok=True)
for name in ('usr','etc','dev'):
 command(['mount','--bind','/'+name,str(sandbox/name)])
 command(['mount','-o','remount,bind,ro',str(sandbox/name)])
for name in ('lib','lib64'):
 source=Path('/'+name)
 if source.is_symlink():(sandbox/name).symlink_to(os.readlink(source))
 elif source.is_dir():
  (sandbox/name).mkdir();command(['mount','--bind',str(source),str(sandbox/name)])
  command(['mount','-o','remount,bind,ro',str(sandbox/name)])
command(['mount','--bind','/proc',str(sandbox/'proc')])
command(['mount','--bind',str(root),str(sandbox/'candidate')])
command(['mount','-o','remount,bind,ro',str(sandbox/'candidate')])
for value in manifest.get('aliases',{}).values():
 alias=value['aliasPath'];backing=value['backingPath']
 if not alias.startswith('/export/') or len(Path(alias).parts)!=3:raise ValueError('candidate alias is outside its fixed private root')
 target=sandbox/alias.lstrip('/');target.mkdir()
 command(['mount','--bind',backing,str(target)])
 command(['mount','-o','remount,bind,ro',str(target)])
os.chroot(sandbox);os.chdir('/')
root=Path('/candidate');Path('/run/ganesha').mkdir()
rpc=subprocess.Popen(['rpcbind','-f'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
 for endpoint in manifest.get('endpoints',[]):
  path=root/endpoint['configurationPath']
  with tempfile.TemporaryDirectory(prefix='ganesha-validator-',dir='/run') as directory:
   directory=Path(directory);log=directory/'parser.log'
   server=subprocess.Popen(['ganesha.nfsd','-F','-f',str(path),'-p',str(directory/'pid'),'-L',str(log),'-N','NIV_EVENT'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
   try:
    deadline=time.monotonic()+8
    ready=False
    while time.monotonic()<deadline:
     if server.poll() is not None:break
     text=log.read_text(errors='replace') if log.exists() else ''
     if any(word in text for word in ('Parse failed','Could not parse','Configuration file not found','Error while parsing','Unknown parameter','CONFIG :CRIT','CONFIG :FATAL','NFS STARTUP :CRIT','NFS STARTUP :FATAL')):break
     if 'NFS SERVER INITIALIZED' in text or 'NFS STARTUP' in text:
      ready=True;break
     time.sleep(.1)
    if not ready:
     tail=log.read_text(errors='replace').splitlines()[-10:] if log.exists() else []
     print(json.dumps({'success':False,'nfsParserVerified':False,'diagnostic':tail}),flush=True)
     raise ValueError('isolated Ganesha parser/startup did not verify the candidate')
   finally:
    if server.poll() is None:
     server.terminate()
     try:server.wait(timeout=5)
     except subprocess.TimeoutExpired:server.kill();server.wait(timeout=2)
finally:
 if rpc.poll() is None:
  rpc.terminate()
  try:rpc.wait(timeout=2)
  except subprocess.TimeoutExpired:rpc.kill();rpc.wait(timeout=2)
print(json.dumps({'success':True,'nfsParserVerified':True}))
'''


def validate_smb_candidate(root, timeout=20):
    result=subprocess.run(['testparm','-s',str(Path(root)/'smb/smb.conf')],capture_output=True,text=True,timeout=timeout)
    if result.returncode:raise ValueError('Samba testparm rejected the staged configuration')
    return True


def validate_nfs_candidate(root, timeout=120):
    root=Path(root);manifest=json.loads((root/'nfs/manifest.json').read_text())
    if not manifest.get('endpoints'):return True
    result=subprocess.run(['unshare','--mount','--net','--pid','--fork','--kill-child=SIGKILL','--mount-proc','python3','-c',GANESHA_NAMESPACE_WORKER,str(root)],capture_output=True,text=True,timeout=timeout)
    if result.returncode:raise ValueError('Private Ganesha parser validation failed')
    outcome=json.loads(result.stdout)
    if outcome.get('success') is not True or outcome.get('nfsParserVerified') is not True:raise ValueError('Ganesha parser attestation is missing')
    return True
