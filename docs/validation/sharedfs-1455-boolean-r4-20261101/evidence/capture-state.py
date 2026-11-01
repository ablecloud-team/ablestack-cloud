from pathlib import Path
import os,json,stat,hashlib,subprocess
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
volume=Path('/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277')
def item(p):
 s=p.lstat();v={'uid':s.st_uid,'gid':s.st_gid,'mode':format(s.st_mode&0o7777,'04o'),'inode':s.st_ino}
 if stat.S_ISLNK(s.st_mode):v['link']=os.readlink(p)
 else:
  v['acl']=subprocess.check_output(['getfacl','-cpn',str(p)],text=True).strip()
  if stat.S_ISREG(s.st_mode):
   assert s.st_size<65536;v['sha256']=hashlib.sha256(p.read_bytes()).hexdigest()
 return v
out={'runtimeVersion':Path('/opt/ablestack/storage-runtime/current').resolve().name,'objects':{},'originalObjects':{},'configHashes':{}}
root=volume/'epic1455/projects'
for parent,dirs,files in os.walk(root,followlinks=False):
 p=Path(parent);out['objects'][str(p.relative_to(volume))]=item(p)
 for name in dirs+files:
  child=p/name;out['objects'][str(child.relative_to(volume))]=item(child)
for parent in [Path('/export/common1453r5'),Path('/export/ad1453r5')]:
 for p in [parent]+sorted(parent.rglob('*')):out['originalObjects'][str(p)]=item(p)
for p in [Path('/etc/ablestack-storage/desired-state/nfs-export-apply.json'),Path('/etc/ablestack-storage/desired-state/smb-share-apply.json'),Path('/etc/samba/smb.conf'),*Path('/etc/ganesha/ablestack-storage').glob('*.conf'),*Path('/etc/ablestack-storage/smb-listeners').glob('*.conf')]:
 if p.is_file():out['configHashes'][str(p)]=hashlib.sha256(p.read_bytes()).hexdigest()
out['filesystem']=subprocess.check_output(['findmnt','-n','-M',str(volume),'-o','UUID,FSTYPE'],text=True).strip()
out['mac']=Path('/sys/class/net/eth0/address').read_text().strip()
out['bootId']=Path('/proc/sys/kernel/random/boot_id').read_text().strip()
out['network']=json.loads(subprocess.check_output(['ip','-j','-4','addr','show','dev','eth0'],text=True))
out['faultHookAbsent']=not Path('/run/epic1455-r4-fail-once').exists() and not Path('/run/systemd/system/ablestack-storage-ganesha@0.0.0.0_2049.service.d/90-epic1455-r4.conf').exists()
out['operations']={}
for op in ['18dc6b50-841f-4980-8eab-2c1a6409fa58','be1417ba-c346-479f-b5e6-f838ad8d28a4']:
 status=Path('/var/lib/ablestack-storage/configuration-operations/c1eca707-a554-4c42-85cd-1e9e9744d804')/op/'status.json'
 if status.exists():
  value=json.loads(status.read_text());out['operations'][op]={k:value.get(k)for k in ['state','phase','baseRevision','targetRevision','rollback']}
print(json.dumps(out))
