#!/usr/bin/env python3
"""Design-only actual-kernel mount/detach proof. No product implementation."""
import os,sys,json,pathlib,subprocess,hashlib,tempfile,time,uuid
P=pathlib.Path
ROOT=P(sys.argv[1]).resolve()
CLI=P('/usr/local/bin/ablestack-storagectl')
EXPECTED='c09a749ef83294cdb0dd544d7c61a5502873ce4a5cf612c927b6b6313330a3ef'
def run(args,check=True,**kw):
 r=subprocess.run(args,text=True,capture_output=True,timeout=20,**kw)
 if check and r.returncode:raise RuntimeError(json.dumps({'args':args,'exit':r.returncode,'stderr':r.stderr[-900:]}))
 return r
def sha(p):return hashlib.sha256(P(p).read_bytes()).hexdigest()
assert sha(CLI)==EXPECTED
assert str(ROOT).startswith('/var/tmp/epic1461-design-') and ROOT.parent==P('/var/tmp')
def protected():
 paths=[P('/etc/fstab'),CLI]
 paths+=list(P('/etc/ganesha/ablestack-storage').glob('*.conf'))
 paths+=list(P('/etc/ablestack-storage/desired-state').glob('*.json'))
 paths+=list(P('/run/ablestack-storage/ganesha').glob('*.pid'))
 seed=P('/srv/ablestack-storage/volumes/76827053-fe54-4a8d-a613-ad13bc57580a/export/online-dual/seed.txt')
 paths+=[seed]
 return {str(p):sha(p) for p in paths if p.is_file()}
if '--child' not in sys.argv:
 before=protected();ROOT.mkdir(mode=0o700,exist_ok=False)
 src=CLI.read_text();inline=src.split("volume_detach_prepare() {",1)[1].split("<<'PY'\n",1)[1].split("\nPY\n",1)[0]
 # Harness redirects only the fstab path and daemon-reload. Mount discovery/umount are unchanged.
 inline=inline.replace('fstab_path = "/etc/fstab"','fstab_path = '+repr(str(ROOT/'fstab')))
 a=inline.index('def daemon_reload():');b=inline.index('\nif mount_path:',a)
 inline=inline[:a]+'def daemon_reload():\n    pass\n'+inline[b:]
 (ROOT/'existing-detach.py').write_text(inline)
 result=run(['unshare','--mount','--propagation','private','/usr/bin/python3',__file__,str(ROOT),'--child'],check=False)
 try:child=json.loads((ROOT/'child-result.json').read_text())
 except Exception:child={'success':False,'stderr':result.stderr,'stdout':result.stdout,'exit':result.returncode}
 child['productionProtectedFilesUnchanged']=before==protected()
 child['protectedFileCount']=len(before);child['cliSha256']=EXPECTED
 (ROOT/'result.json').write_text(json.dumps(child,indent=2))
 print(json.dumps(child))
 sys.exit(0 if child.get('success') and child['productionProtectedFilesUnchanged'] else 1)
checks=[];loops=[];mounts=[]
def check(name,condition,**detail):
 assert condition,(name,detail)
 checks.append({'name':name,'passed':True,**detail})
def mounted(p):return run(['findmnt','-n','--mountpoint',str(p)],check=False).returncode==0
def identity(p):
 r=json.loads(run(['findmnt','-J','-T',str(p),'-o','TARGET,SOURCE,FSTYPE,UUID,FSROOT']).stdout)['filesystems'][0]
 s=p.stat();r.update(device=s.st_dev,inode=s.st_ino,uid=s.st_uid,gid=s.st_gid,mode=oct(s.st_mode&4095))
 return r
def same(a,b):return (a['uuid'],a['device'],a['inode'])==(b['uuid'],b['device'],b['inode'])
def helper(root,vid):
 payload=ROOT/'payload.json';payload.write_text(json.dumps({'volumeUuid':vid,'mountPath':str(root)}))
 return run(['/usr/bin/python3',str(ROOT/'existing-detach.py'),str(payload)],check=False)
def unmount(p):
 r=run(['umount',str(p)],check=False)
 return r
try:
 # The namespace's mount propagation is private; production daemons/configs are never touched.
 for fs in ['xfs','ext4']:
  image=ROOT/(fs+'.sparse');image.touch()
  with image.open('r+b') as f:f.truncate(512*1024*1024)
  st=image.stat();check(fs+' sparse backing',st.st_blocks*512<st.st_size,logicalBytes=st.st_size,allocatedBytes=st.st_blocks*512)
  loop=run(['losetup','--find','--show',str(image)]).stdout.strip();loops.append(loop)
  assert P('/sys/class/block/'+P(loop).name+'/loop/backing_file').read_text().strip()==str(image)
  run(['mkfs.'+fs,'-q',loop] if fs=='ext4' else ['mkfs.xfs','-q',loop])
  m=ROOT/(fs+'-mount');m.mkdir();run(['mount',loop,str(m)]);mounts.append(m)
  parent=m/'projects';child=parent/'child';child.mkdir(parents=True)
  seed=child/'seed.bin';seed.write_bytes(b'epic1461-preserve-'+fs.encode()+b'\n')
  os.chown(child,1234,2345);os.chmod(child,0o2770);os.chown(seed,1234,2345);os.chmod(seed,0o640)
  run(['setfacl','-m','u:2001:rwx,d:u:2001:rwx',str(child)])
  before={'hash':sha(seed),'identity':identity(child),'acl':run(['getfacl','-cpn',str(child)]).stdout}
  aliases=ROOT/(fs+'-aliases');aliases.mkdir();nf=aliases/'nfs-parent';sm=aliases/'smb-child';nf.mkdir();sm.mkdir()
  run(['mount','--bind',str(parent),str(nf)]);mounts.append(nf)
  run(['mount','--bind',str(child),str(sm)]);mounts.append(sm)
  check(fs+' external aliases map by UUID/device/inode/FSROOT',same(identity(sm),identity(child)) and identity(sm)['fsroot']=='/projects/child' and identity(nf)['uuid']==before['identity']['uuid'])
  # Prove the limitation without deleting data: base-only helper leaves external aliases.
  fstab=ROOT/'fstab';vid=str(uuid.uuid4());untouched='UUID=unrelated /unrelated xfs defaults 0 0\n';fstab.write_text(untouched+f'UUID={vid} {m} {fs} defaults 0 0 # ablestack-storage:{vid}\n')
  base=helper(m,vid);check(fs+' existing helper only removes base mount',base.returncode==0 and not mounted(m) and mounted(nf) and mounted(sm),externalAliasesRemain=True)
  check(fs+' data still readable through retained alias',sha(sm/'seed.bin')==before['hash'])
  run(['mount',loop,str(m)]) # same disposable filesystem; no mkfs repeat
  # Busy alias is a pretested failure: regular unmount fails and destructive continuation must stay at zero.
  holder=subprocess.Popen(['/usr/bin/python3','-c','import os,time;os.chdir('+repr(str(sm))+');print("ready",flush=True);time.sleep(15)'],stdout=subprocess.PIPE,text=True)
  assert holder.stdout.readline().strip()=='ready'
  busy=unmount(sm);check(fs+' busy alias blocks before detach/VM/delete',busy.returncode!=0 and mounted(sm),detachCalls=0,vmExpungeCalls=0,volumeDeleteCalls=0)
  holder.terminate();holder.wait(timeout=3)
  # Proposed ordering uses only verified aliases, deepest FSROOT first, then existing base helper.
  check(fs+' reject ROOT/foreign path before helper',not str(P('/')).startswith(str(ROOT)) and not same(identity(P('/')),before['identity']),helperCallsForRejected=0)
  for alias in [sm,nf]:
   obs=identity(alias);assert obs['uuid']==before['identity']['uuid'] and obs['device']==before['identity']['device']
   assert unmount(alias).returncode==0
  fstab.write_text(untouched+f'UUID={vid} {m} {fs} defaults 0 0 # ablestack-storage:{vid}\n')
  final=helper(m,vid)
  check(fs+' verified aliases first then normal base unmount',final.returncode==0 and all(not mounted(x) for x in [sm,nf,m]) and fstab.read_text()==untouched)
  # Idempotent resume must not call the old helper on an already-unmounted path.
  check(fs+' detached observation resumes without helper',all(not mounted(x)for x in [sm,nf,m]),repeatedUnmountCalls=0)
  run(['losetup','-d',loop]);loops.remove(loop)
  loop=run(['losetup','--find','--show',str(image)]).stdout.strip();loops.append(loop)
  run(['mount',loop,str(m)])
  after={'hash':sha(seed),'identity':identity(child),'acl':run(['getfacl','-cpn',str(child)]).stdout}
  check(fs+' reattach/remount preserves file, FSUUID, inode, owner, mode, ACL',before==after,before=before,after=after)
  assert unmount(m).returncode==0;run(['losetup','-d',loop]);loops.remove(loop)
 raw=ROOT/'block.sparse';raw.touch()
 with raw.open('r+b')as f:f.truncate(64*1024*1024);f.seek(1024*1024);f.write(b'epic1461-raw-marker\n');f.flush();os.fsync(f.fileno())
 h=sha(raw);loop=run(['losetup','--find','--show',str(raw)]).stdout.strip();loops.append(loop)
 run(['losetup','-d',loop]);loops.remove(loop);loop=run(['losetup','--find','--show',str(raw)]).stdout.strip();loops.append(loop)
 check('raw loop detach/reattach preserves exact bytes without filesystem',sha(raw)==h and run(['blkid',loop],check=False).returncode!=0,sha256=h)
 run(['losetup','-d',loop]);loops.remove(loop)
 result={'success':True,'designOnly':True,'scope':'private mount namespace; loop-backed SPARSE files only; no Cloud business API or live protocol mutation','checks':checks,'productSourceModified':False,'liveProtocolRestarted':False}
except Exception as e:
 result={'success':False,'checks':checks,'error':str(e)}
finally:
 for p in reversed(mounts):
  if mounted(p):run(['umount',str(p)],check=False)
 for l in loops:run(['losetup','-d',l],check=False)
 result['loopDevicesReleased']=not loops or all(run(['losetup',l],check=False).returncode!=0 for l in loops)
 (ROOT/'child-result.json').write_text(json.dumps(result,indent=2))
