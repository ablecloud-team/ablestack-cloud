import sys,json,hashlib,zipfile,shutil,os,subprocess
from pathlib import Path
stage=Path(sys.argv[1]);backup=Path('/root/snapshot-template-1474-agent-20261024');assert not backup.exists();backup.mkdir(mode=0o700)
lib=Path('/usr/share/cloudstack-agent/lib');manifest=json.loads((stage/'manifest.json').read_text())
def vms():return subprocess.check_output(['virsh','list','--all'],text=True)
def ft():
 base=Path('/usr/local/lib/ablestack-qemu-exec-tools/ftctl');return {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in base.rglob('*') if p.is_file() and p.suffix in ('.py','.sh')}
beforevms=vms();beforeft=ft();rows=[]
for group in ['kvm','core','secondary']:
 pattern={'kvm':'cloud-plugin-hypervisor-kvm*.jar','core':'cloud-core*.jar','secondary':'cloud-secondary-storage*.jar'}[group];jars=list(lib.glob(pattern));assert len(jars)==1,jars;jar=jars[0];shutil.copy2(jar,backup/jar.name)
 updates={x['path']:(stage/group/x['path']).read_bytes() for x in manifest if x['group']==group}
 for x in manifest:
  if x['group']==group:assert hashlib.sha256(updates[x['path']]).hexdigest()==x['sha256']
 tmp=jar.with_suffix('.1474tmp');preserved=hashlib.sha256()
 with zipfile.ZipFile(jar) as old,zipfile.ZipFile(tmp,'w') as new:
  keys=set(old.namelist())
  for info in old.infolist():
   data=old.read(info);new.writestr(info,updates.get(info.filename,data))
   if info.filename not in updates:preserved.update(info.filename.encode()+data)
  for k,v in updates.items():
   if k not in keys:new.writestr(k,v)
 check=hashlib.sha256()
 with zipfile.ZipFile(tmp) as new:
  for info in new.infolist():
   data=new.read(info)
   if info.filename not in updates:check.update(info.filename.encode()+data)
  for k,v in updates.items():assert new.read(k)==v
 assert check.hexdigest()==preserved.hexdigest();shutil.copystat(jar,tmp);os.replace(tmp,jar)
 rows.append({'jar':str(jar),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'classes':list(updates),'otherEntriesUnchanged':True})
subprocess.run(['systemctl','restart','mold-agent'],check=True);subprocess.run(['systemctl','is-active','--quiet','mold-agent'],check=True)
assert beforeft==ft();aftervms=vms();proof={'jars':rows,'ftctlUnchanged':True,'ftctlFileCount':len(beforeft),'vmsUnchanged':beforevms==aftervms,'vmsBefore':beforevms,'vmsAfter':aftervms}
(backup/'result.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
