import pathlib,subprocess,hashlib,bz2,shutil,json,os,stat,tarfile,io,time
p=pathlib.Path('/root/work/epic1438-preparation/20261027/issue1451-implementation')
src=pathlib.Path('/root/work/ablestack-cloud/systemvm/debian/usr/local/bin/ablestack-storagectl')
base=pathlib.Path('/root/work/epic1438-preparation/20261027/issue1450-implementation/r4-deferred-posix/systemvm-epic1450-deferred-posix-20261027-r4.qcow2.bz2')
def sha(f):
 h=hashlib.sha256()
 with f.open('rb') as r:
  for b in iter(lambda:r.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
protected=[pathlib.Path('/etc/passwd'),pathlib.Path('/etc/group'),pathlib.Path('/etc/shadow'),pathlib.Path('/etc/gshadow')]
host_before=[f.read_bytes() for f in protected]
assert sha(base)=='4d91e34ab34fe10120f3568ddf62eb9a6063647620d2471c5b9c900aed4b5c74'
raw=p/'verified-1450-r4-base.qcow2'
if not raw.exists():
 with bz2.open(base,'rb') as r,raw.open('wb') as w:shutil.copyfileobj(r,w,1024*1024)
image=p/'systemvm-epic1451-nfs-idmapping-20261027-final.qcow2'
assert not image.exists()
subprocess.run(['qemu-img','convert','-f','qcow2','-O','qcow2',str(raw),str(image)],check=True)
subprocess.run(['modprobe','nbd','max_part=8'],check=True)
device=None
for n in range(6,16):
 d=pathlib.Path('/dev/nbd'+str(n));pid=pathlib.Path('/sys/block/nbd'+str(n)+'/pid')
 if d.exists() and not pid.exists():device=d;break
assert device,'No unoccupied NBD device'
mount=p/'image-root';mount.mkdir(exist_ok=False)
pid_file=p/'qemu-nbd.pid'
assert not pid_file.exists()
subprocess.run(['qemu-nbd','--format=qcow2','--pid-file='+str(pid_file),'--connect='+str(device),str(image)],check=True)
root=None;mounted=False
def snapshot():
 result={}
 for folder,dirs,files in os.walk(mount,followlinks=False):
  for name in dirs+files:
   f=pathlib.Path(folder)/name;st=f.lstat();rel=f.relative_to(mount).as_posix()
   meta=[stat.S_IFMT(st.st_mode),stat.S_IMODE(st.st_mode),st.st_uid,st.st_gid]
   if stat.S_ISREG(st.st_mode):meta.append(sha(f))
   elif stat.S_ISLNK(st.st_mode):meta.append(os.readlink(f))
   result[rel]=meta
 return result
try:
 for i in range(30):
  if any(pathlib.Path(str(device)+'p'+str(n)).exists() for n in [6,1,5]):break
  time.sleep(.2)
 for n in [6,1,5]:
  part=str(device)+'p'+str(n)
  if not pathlib.Path(part).exists():continue
  r=subprocess.run(['mount','-o','rw',part,str(mount)],capture_output=True)
  if r.returncode:continue
  mounted=True
  if (mount/'usr/local/bin/ablestack-storagectl').is_file():root=part;break
  subprocess.run(['umount',str(mount)],check=True);mounted=False
 assert root,'No verified guest root'
 assert os.path.ismount(mount) and subprocess.check_output(['findmnt','-n','-o','SOURCE','--mountpoint',str(mount)],text=True).strip()==root
 before=snapshot()
 assert not (mount/'etc/ablestack-storage/sharedfs-network.json').exists()
 changes={'usr/local/bin/ablestack-storagectl':'systemvm/debian/usr/local/bin/ablestack-storagectl','usr/local/lib/ablestack-storage/configuration_transaction.py':'systemvm/debian/usr/local/lib/ablestack-storage/configuration_transaction.py','usr/local/lib/ablestack-storage/runtime-baseline-profile.json':'systemvm/debian/usr/local/lib/ablestack-storage/runtime-baseline-profile.json'}
 assert sha(mount/'usr/local/lib/ablestack-storage/volume_preparation.py')=='7f22d9aab691c7367e22b7cf053b2ca285e7a9bb604981699c7f02fa34c66f7e'
 installed_hashes={}
 for rel,source in changes.items():
  target=mount/rel;source=pathlib.Path('/root/work/ablestack-cloud')/source
  assert target.resolve().is_relative_to(mount.resolve()) and not target.is_symlink()
  mode=stat.S_IMODE(target.stat().st_mode) if target.exists() else 0o644
  shutil.copyfile(source,target);os.chown(target,0,0);os.chmod(target,mode)
  assert sha(target)==sha(source);installed_hashes[rel]=sha(target)
 profile=json.loads((mount/'usr/local/lib/ablestack-storage/runtime-baseline-profile.json').read_text())
 for row in profile['files']:
  rel=pathlib.Path(row['path'])
  candidate=mount/'usr/local'/(pathlib.Path('bin')/rel if len(rel.parts)==1 else rel)
  assert sha(candidate)==row['sha256'], 'Baseline payload mismatch: '+str(rel)
 after=snapshot()
 changed=[k for k in set(before)|set(after) if before.get(k)!=after.get(k)]
 assert set(changed)==set(changes),changed
 assert sha(mount/'usr/local/bin/ablestack-storagectl')=='112b889b9244e95fca385e943e65553274e9bab388020c093da02bd9c3deaf61'
 assert sha(mount/'usr/local/lib/ablestack-storage/volume_preparation.py')=='7f22d9aab691c7367e22b7cf053b2ca285e7a9bb604981699c7f02fa34c66f7e'
 subprocess.run(['sync'],check=True)
 subprocess.run(['umount',str(mount)],check=True);mounted=False
finally:
 if mounted:subprocess.run(['umount',str(mount)],check=True)
 pid=pathlib.Path('/sys/block/'+device.name+'/pid')
 if pid.exists():
  cmd=pathlib.Path('/proc/'+pid_file.read_text().strip()+'/cmdline').read_bytes()
  assert str(image).encode() in cmd,'NBD attachment changed ownership'
  subprocess.run(['qemu-nbd','--disconnect',str(device)],check=True,capture_output=True)
 mount.rmdir()
 if pid_file.exists():pid_file.unlink()
 assert [f.read_bytes() for f in protected]==host_before,'Host account files changed'
check=subprocess.run(['qemu-img','check',str(image)],capture_output=True,text=True,check=True)
compressed=image.with_suffix('.qcow2.bz2')
with image.open('rb') as r,bz2.open(compressed,'wb',compresslevel=1) as w:shutil.copyfileobj(r,w,1024*1024)
subprocess.run(['bzip2','-t',str(compressed)],check=True)
result={'baseCompressedSha256':sha(base),'installedModuleHashes':installed_hashes,'changedGuestPaths':sorted(changed),'unchangedGuestPathsVerified':len(before)-len(changed),'hostAccountFilesUnchanged':True,'image':str(image),'compressed':str(compressed),'compressedSha256':sha(compressed),'compressedBytes':compressed.stat().st_size,'qemuImageCheck':check.stdout.strip(),'buildKind':'scoped NFSv4 identity policy modules and matching runtime baseline; other image paths verified unchanged'}
(p/'guest-module-build.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
