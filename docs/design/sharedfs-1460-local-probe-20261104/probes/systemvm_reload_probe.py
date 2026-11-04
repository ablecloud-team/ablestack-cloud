#!/usr/bin/env python3
"""#1460 isolated design proof. Never edits active runtime or real ACL/config."""
import ast, copy, hashlib, ipaddress, json, os, shutil, signal, stat, subprocess, tempfile, time, traceback
from pathlib import Path
VOL=Path('/srv/ablestack-storage/volumes/ef9a2ff3-31fd-44fe-a97a-2e0669455127')
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='8470a7eb-b5b2-3b45-9922-ba2d3e3aeed0'
assert subprocess.check_output(['findmnt','-n','-M',str(VOL),'-o','FSTYPE,UUID'],text=True).split()==['xfs','08b42571-dbc5-44bd-965b-c5da35d4eb7f']
ROOT=Path(tempfile.mkdtemp(prefix='epic1460-design-',dir='/var/tmp'))
DATA=VOL/('.'+ROOT.name);DATA.mkdir(mode=0o755)
NS=[ROOT.name+'s',ROOT.name+'a',ROOT.name+'b'];children=[];mounted=[];results=[];workers={}
def run(a,ok=True,timeout=20,**kw):
 p=subprocess.run(a,capture_output=True,text=True,timeout=timeout,**kw)
 if ok and p.returncode:raise RuntimeError(str(a[:4])+': '+p.stderr[-1500:])
 return p
WORKER="""import json,subprocess,sys,traceback
for line in sys.stdin:
 try:
  x=json.loads(line);p=subprocess.run(x['a'],capture_output=True,text=True,timeout=x.get('timeout',20))
  print(json.dumps({'returncode':p.returncode,'stdout':p.stdout,'stderr':p.stderr}),flush=True)
 except Exception:print(json.dumps({'returncode':99,'stdout':'','stderr':traceback.format_exc()}),flush=True)
"""
def net(n,a,**kw):
 if n not in workers:return run(['ip','netns','exec',NS[n]]+a,**kw)
 p=workers[n];p.stdin.write(json.dumps({'a':a,'timeout':kw.get('timeout',20)})+'\n');p.stdin.flush()
 v=json.loads(p.stdout.readline());result=subprocess.CompletedProcess(a,v['returncode'],v['stdout'],v['stderr'])
 if kw.get('ok',True) and result.returncode:raise RuntimeError(str(a[:4])+': '+result.stderr[-1500:])
 return result
def identity(pid):return (Path('/proc')/str(pid)/'stat').read_text().split()[21]
def protected():
 paths=[Path('/etc/fstab')]+list(Path('/etc/ganesha/ablestack-storage').glob('*.conf'))+list(Path('/etc/ablestack-storage/desired-state').glob('*.json'))
 active=Path('/opt/ablestack/storage-runtime/current').resolve()
 paths += [active/'ablestack-storagectl',active/'lib/ablestack-storage/boot_reconcile.py',active/'lib/ablestack-storage/shared_directory_policy.py',VOL/'export/boot001/shared-seed']
 return {'hashes':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.is_file()},
  'pids':{p.name:p.read_text() for p in Path('/run/ablestack-storage/ganesha').glob('*.pid')},
  'seed':[(p.stat().st_uid,p.stat().st_gid,p.stat().st_mode,p.stat().st_ino) for p in [VOL/'export/boot001/shared-seed']]}
before=protected()
def record(name,**v):results.append({'name':name,'passed':True,**v})
def writeatomic(path,s):
 temp=path.with_suffix('.new');temp.write_text(s);os.replace(temp,path)
def config(port,mode,acl='both',perm='RW',squash='Root_Squash'):
 clients='CLIENT { Clients=192.0.2.11; Access_Type=RW; Squash=Root_Squash; }\n'
 if acl=='both':clients+='CLIENT { Clients=192.0.2.12; Access_Type='+perm+'; Squash='+squash+'; }\n'
 proto='3,4' if mode=='dual' else '4'
 return ('NFS_Core_Param { NFS_Port='+str(port)+'; MNT_Port='+str(port-1)+'; Bind_addr=192.0.2.1; Protocols='+proto+'; Transports=TCP; Enable_NLM=false; Enable_RQUOTA=false; mount_path_pseudo=true; }\n'
  +'NFSV4 { Grace_Period=0; Graceless=true; RecoveryRoot='+json.dumps(str(ROOT/('recovery-'+str(port))))+'; Allow_Numeric_Owners=true; Only_Numeric_Owners=true; }\n'
  +'LOG { Default_Log_Level=EVENT; }\n'
  +'EXPORT_DEFAULTS { Access_Type=None; Protocols='+proto+'; Transports=TCP; SecType=sys; }\n'
  +'EXPORT { Export_Id=1460; Path='+json.dumps(str(DATA/'shared'))+'; Pseudo="/shared"; Protocols='+proto+'; Access_Type=None; SecType=sys; FSAL { Name=VFS; } '+clients+' }\n'
  +'EXPORT { Export_Id=1461; Path='+json.dumps(str(DATA/'other'))+'; Pseudo="/other"; Protocols='+proto+'; Access_Type=None; SecType=sys; FSAL { Name=VFS; } CLIENT { Clients=192.0.2.11,192.0.2.12; Access_Type=RW; Squash=Root_Squash; } }\n')
def alive(p):return p.poll() is None
def ready(port):
 for _ in range(100):
  p=net(0,['python3','-c','import socket;s=socket.create_connection(("192.0.2.1",'+str(port)+'),.2);s.close()'],ok=False,timeout=3)
  if not p.returncode:return
  time.sleep(.05)
 raise RuntimeError('private listener unavailable')
def start(port,mode):
 (ROOT/('recovery-'+str(port))).mkdir(exist_ok=True)
 conf=ROOT/(str(port)+'.conf');writeatomic(conf,config(port,mode))
 log=ROOT/(str(port)+'.log')
 p=subprocess.Popen(['ip','netns','exec',NS[0],'ganesha.nfsd','-F','-f',str(conf),'-p',str(ROOT/(str(port)+'.pid')),'-L',str(log)],stdout=(ROOT/(str(port)+'.out')).open('a'),stderr=subprocess.STDOUT,start_new_session=True)
 children.append(p);ready(port)
 return {'proc':p,'conf':conf,'log':log,'start':identity(p.pid),'port':port,'mode':mode}
def reload(x,content,invalid=False):
 p=x['proc'];assert alive(p) and identity(p.pid)==x['start']
 pos=x['log'].stat().st_size
 writeatomic(x['conf'],content);fd=os.pidfd_open(p.pid)
 try:signal.pidfd_send_signal(fd,signal.SIGHUP)
 finally:os.close(fd)
 for _ in range(100):
  s=x['log'].read_text(errors='replace')[pos:]
  if ('Reread exports complete' in s) or ('Error while parsing new configuration' in s) or ('Error while parsing EXPORT entries' in s):break
  if not alive(p):raise RuntimeError('private reload process exited: '+s[-3000:])
  time.sleep(.05)
 else:raise RuntimeError('private reload acknowledgement timeout: '+s[-2000:])
 assert alive(p) and identity(p.pid)==x['start']
 if invalid:assert 'Error while parsing' in s,s[-2000:]
 else:assert 'Reread exports complete' in s and 'Error while parsing' not in s,s[-2000:]
 return s[-3000:]
def mount(n,version,port,name='shared'):
 d=ROOT/('mnt-'+str(n)+'-'+str(version)+'-'+str(port)+'-'+name);d.mkdir(exist_ok=True)
 typ='nfs' if version==3 else 'nfs4'
 opts='vers='+('3' if version==3 else '4.1')+',proto=tcp,port='+str(port)+',soft,timeo=10,retrans=1,nosharecache,actimeo=0,lookupcache=none'
 if version==3:opts+=',nolock,mountproto=tcp,mountport='+str(port-1)
 net(n,['mount','-t',typ,'-o',opts,'192.0.2.1:/'+name,str(d)],timeout=25)
 mounted.append((n,d));return d
def read(n,d,allowed):
 p=net(n,['dd','if='+str(d/'seed'),'iflag=direct','bs=4096','count=1','status=none'],ok=False)
 if allowed:assert not p.returncode and p.stdout=='x'*4096,p.stderr
 else:assert p.returncode and ('Permission denied' in p.stderr or 'Operation not permitted' in p.stderr),p.stderr
 return {'rc':p.returncode,'stderr':p.stderr.strip()[:200]}
def unmountall():
 while mounted:
  n,d=mounted.pop();net(n,['umount',str(d)],ok=False,timeout=10)
def exercise(mode,ports):
 endpoints=[start(port,mode) for port in ports]
 ds={}
 for x in endpoints:
  for ver in ([4,3] if mode=='dual' else [4]):
   for n in [1,2]:
    d=mount(n,ver,x['port']);read(n,d,True);ds[(n,ver,x['port'])]=d
 other=mount(2,4,ports[0],'other');read(2,other,True)
 cont=ROOT/('continuous-'+mode+'.json')
 src='import os,time,json,sys; p=sys.argv[1];fd=os.open(p,os.O_CREAT|os.O_RDWR,0o644);n=0;begin=time.monotonic();errors=[]\nwhile time.monotonic()-begin<18:\n try:os.pwrite(fd,bytes([n%251])*4096,0);os.fdatasync(fd);n+=1\n except OSError as e:errors.append(str(e))\n time.sleep(.02)\nos.close(fd);open(sys.argv[2],"w").write(json.dumps({"requests":n,"errors":errors}))'
 writerCode='import subprocess;subprocess.Popen('+repr(['python3','-c',src,str(ds[(1,4,ports[0])]/'continuous'),str(cont)])+',stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)'
 net(1,['python3','-c',writerCode])
 writer=None

 for x in endpoints:reload(x,config(x['port'],mode,'one'))
 denied=[]
 for (n,v,port),d in ds.items():denied.append({'client':n,'version':v,'port':port,**read(n,d,n==1)})
 read(2,other,True)
 record(mode+' revoke client2; next direct READ denied; client1 and sibling remain allowed',checks=denied,pids=[x['proc'].pid for x in endpoints])
 for x in endpoints:reload(x,config(x['port'],mode))
 for (n,v,port),d in ds.items():read(n,d,True)
 record(mode+' regrant restores existing mounts without reconnect')
 for x in endpoints:reload(x,config(x['port'],mode,'both','RO'))
 for x in endpoints:
  d=ds[(2,4,x['port'])]
  p=net(2,['sh','-c','printf denied > "$1"', 'sh', str(d/'should-not-exist')],ok=False)
  assert p.returncode and not (DATA/'shared/should-not-exist').exists(),p.stderr
 record(mode+' RW to RO prevents next write; no daemon restart')
 for x in endpoints:reload(x,config(x['port'],mode,'both','RW','No_Root_Squash'))
 for x in endpoints:
  d=ds[(2,4,x['port'])];f='root-'+mode+'-'+str(x['port'])
  net(2,['sh','-c','printf root > "$1"','sh',str(d/f)])
  assert (DATA/'shared'/f).stat().st_uid==0
 record(mode+' root squash policy dynamically changes numeric owner')
 x=endpoints[0];old=config(x['port'],mode,'both','RW','No_Root_Squash')
 reload(x,'NFS_Core_Param { invalid !!!',invalid=True)
 read(1,ds[(1,4,x['port'])],True)
 reload(x,old)
 record(mode+' invalid config rejected with live export preserved; previous valid config reloaded')
 # Execute the actual installed local probe on this real remote-only ACL.
 cli=Path('/opt/ablestack/storage-runtime/current/ablestack-storagectl').read_text()
 segment=cli.split('def probe_nfs_export_visibility(',1)[1].split('\n    return failures',1)[0]
 func='def probe_nfs_export_visibility('+segment+'\n    return failures\n'
 code=compile(ast.parse(func),'installed storagectl probe','exec')
 probeFile=ROOT/('probe-'+mode+'.py')
 probeFile.write_text('import subprocess,ipaddress,tempfile,shutil,json\nnfs_probe_root='+repr(str(ROOT))+'\ndef endpoint_key(ip,p):return str(p)\n'+func+
  '\nruntime=[{"listenIp":"0.0.0.0","port":'+str(ports[0])+'}]\nrows={("0.0.0.0",'+str(ports[0])+'):[{"uuid":"isolated1460","name":"shared","path":'+repr(str(DATA/'shared'))+',"pseudo":"/shared","protocolMode":'+repr('V3V4_DUAL' if mode=='dual' else 'V4_ONLY')+',"clients":[{"clients":"192.0.2.11/32"},{"clients":"192.0.2.12/32"}]}]}\nf=probe_nfs_export_visibility(runtime,rows)\nassert not f and runtime[0]["probeComplete"] is False and runtime[0]["probeResults"][0]["status"]=="EXTERNAL_CLIENT_REQUIRED"\nprint(json.dumps(runtime))')
 observation=json.loads(net(0,['python3',str(probeFile)]).stdout)
 record(mode+' actual installed local probe distinguishes remote-only ACL',result=observation)
 for _ in range(220):
  if cont.exists():break
  time.sleep(.1)
 else:raise RuntimeError('continuous writer did not finish')
 v=json.loads(cont.read_text());assert v['requests']>100 and not v['errors'],v
 record(mode+' continuously allowed mounted client I/O',**v)
 for x in endpoints:assert alive(x['proc']) and identity(x['proc'].pid)==x['start']
 unmountall()
 for x in endpoints:x['proc'].terminate();x['proc'].wait(timeout=8)
try:
 for n in NS:run(['ip','netns','add',n]);run(['ip','-n',n,'link','set','lo','up'])
 run(['ip','-n',NS[0],'link','add','br0','type','bridge']);run(['ip','-n',NS[0],'addr','add','192.0.2.1/24','dev','br0']);run(['ip','-n',NS[0],'link','set','br0','up'])
 for n in [1,2]:
  a='s'+str(n);b='c'+str(n)
  run(['ip','link','add',a,'type','veth','peer','name',b])
  run(['ip','link','set',a,'netns',NS[0]]);run(['ip','link','set',b,'netns',NS[n]])
  run(['ip','-n',NS[0],'link','set',a,'master','br0']);run(['ip','-n',NS[0],'link','set',a,'up'])
  run(['ip','-n',NS[n],'addr','add','192.0.2.'+str(10+n)+'/24','dev',b]);run(['ip','-n',NS[n],'link','set',b,'up'])
 for n in [1,2]:
  worker=subprocess.Popen(['ip','netns','exec',NS[n],'python3','-u','-c',WORKER],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=(ROOT/('worker-'+str(n)+'.stderr')).open('w'),text=True,bufsize=1);workers[n]=worker;children.append(worker)
 for name in ['shared','other']:
  d=DATA/name;d.mkdir(mode=0o777);d.chmod(0o777);(d/'seed').write_text('x'*4096);(d/'seed').chmod(0o644)
 exercise('v4',[23049,23050])
 exercise('dual',[23049])
 record('real DATA remains on verified SPARSE XFS; no format performed')
 output={'success':True,'checks':results}
except Exception:
 output={'success':False,'checks':results,'error':traceback.format_exc(),'logs':{p.name:p.read_text(errors='replace')[-7000:] for p in ROOT.glob('*.log')}}
finally:
 try:unmountall()
 except Exception:pass
 for p in children:
  if p.poll() is None:
   p.terminate()
   try:p.wait(timeout=5)
   except subprocess.TimeoutExpired:p.kill();p.wait(timeout=5)
 try:unmountall()
 except Exception:pass
 for n in reversed(NS):run(['ip','netns','del',n],ok=False)
 output['productionUnchanged']=before==protected()
 shutil.rmtree(DATA);shutil.rmtree(ROOT)
 output['temporaryResourcesRemoved']=not DATA.exists() and all(not Path('/var/run/netns',n).exists() for n in NS)
 print(json.dumps(output))
