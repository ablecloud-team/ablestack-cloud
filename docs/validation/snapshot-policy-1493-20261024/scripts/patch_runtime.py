import json,hashlib,shutil,sys,zipfile,os
from pathlib import Path
stage=Path(sys.argv[1]);kind=sys.argv[2]
backup=Path(sys.argv[3]) if len(sys.argv)>3 else Path('/root/snapshot-policy-1493-20261024-'+kind)
assert not backup.exists();backup.mkdir(mode=0o700)
manifest=json.loads((stage/'manifest.json').read_text());results=[]
groups=['management'] if kind=='management' else [g for g in ['core','secondary'] if any(x['group']==g for x in manifest)]
for group in groups:
 jars=list(Path('/usr/share/cloudstack-management/lib').glob('cloudstack-*.jar')) if group=='management' else list(Path('/usr/local/cloud/systemvm').glob('cloud-'+('core' if group=='core' else 'secondary-storage')+'-*.jar'))
 assert len(jars)==1,jars
 jar=jars[0];shutil.copy2(jar,backup/jar.name)
 updates={x['path']:(stage/group/x['path']).read_bytes() for x in manifest if x['group']==group}
 for x in manifest:
  if x['group']==group:assert hashlib.sha256(updates[x['path']]).hexdigest()==x['sha256']
 original_hash=hashlib.sha256(jar.read_bytes()).hexdigest();tmp=jar.with_suffix('.1493-tmp');preserved=hashlib.sha256()
 with zipfile.ZipFile(jar) as old,zipfile.ZipFile(tmp,'w') as new:
  present=set()
  for info in old.infolist():
   data=old.read(info)
   if info.filename not in updates:preserved.update(info.filename.encode()+data)
   new.writestr(info,updates.get(info.filename,data));present.add(info.filename)
  for k,v in updates.items():
   if k not in present:new.writestr(k,v)
 checked=hashlib.sha256()
 with zipfile.ZipFile(tmp) as check:
  for info in check.infolist():
   if info.filename not in updates:checked.update(info.filename.encode()+check.read(info))
  for k,v in updates.items():assert check.read(k)==v
 assert preserved.hexdigest()==checked.hexdigest()
 shutil.copystat(jar,tmp);os.replace(tmp,jar)
 results.append({'jar':str(jar),'before':original_hash,'after':hashlib.sha256(jar.read_bytes()).hexdigest(),'updatedClasses':list(updates),'otherEntriesUnchanged':True})
if kind=='management':assert Path('/usr/share/cloudstack-management/webapp/WEB-INF').is_dir()
(backup/'result.json').write_text(json.dumps(results,indent=2));print(json.dumps(results))
