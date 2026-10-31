from pathlib import Path
import tarfile,hashlib,json,shutil,os
stage=Path('/var/tmp/epic1451-ui-r3-20261028');root=Path('/usr/share/cloudstack-management/webapp')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(root/'index.html')=='761f2dc709965eaf70a89088a39f6de9b31488aa71e95a0db8510a50c5ac8f36'
management=sha(Path('/usr/share/cloudstack-management/lib/cloudstack-4.23.0.0.jar'))
assert management=='07c64a803b1702ebd22b652bcea2468351e38a89f81eff36e8ba47600972e9a4'
config=sha(root/'config.json')
systemvm={p.name:(p.stat().st_size,p.stat().st_mtime_ns) for p in (root/'systemvm').iterdir() if p.is_file()}
backup=stage/'before-ui-r3';backup.mkdir(mode=0o700,exist_ok=False)
changed=[];pending=[]
with tarfile.open(stage/'ui-module-r3.tar.gz') as source:
 for member in source:
  rel=Path(member.name)
  assert member.isfile() and not rel.is_absolute() and '..' not in rel.parts
  assert rel.parts[0] not in {'config.json','systemvm','WEB-INF','storage-runtime'}
  target=root/rel
  assert target.resolve().is_relative_to(root.resolve()) and not target.is_symlink()
  data=source.extractfile(member).read()
  if target.exists() and target.read_bytes()==data:continue
  before=sha(target) if target.exists() else None
  if target.exists():
   save=backup/rel;save.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(target,save)
  pending.append((rel,data));changed.append({'path':str(rel),'before':before,'after':hashlib.sha256(data).hexdigest()})
for rel,data in sorted(pending,key=lambda item:item[0].name=='index.html'):
 target=root/rel;target.parent.mkdir(parents=True,exist_ok=True)
 temp=target.with_name('.epic1451-'+target.name);temp.write_bytes(data);temp.chmod(0o644);os.replace(temp,target)
assert sha(Path('/usr/share/cloudstack-management/lib/cloudstack-4.23.0.0.jar'))==management
assert sha(root/'config.json')==config
assert systemvm=={p.name:(p.stat().st_size,p.stat().st_mtime_ns) for p in (root/'systemvm').iterdir() if p.is_file()}
receipt={'success':True,'indexSha256':sha(root/'index.html'),'changedFiles':len(changed),'files':changed,'configPreserved':True,'systemvmPreserved':True}
(stage/'ui-deployment-r3.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({k:v for k,v in receipt.items() if k!='files'}))
