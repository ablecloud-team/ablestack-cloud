import hashlib,json,os,shutil,sys,urllib.request,subprocess,zipfile
from pathlib import Path
stage=Path(sys.argv[1]);backup=Path(sys.argv[2]);web=Path('/usr/share/cloudstack-management/webapp')
def sha(path):
 h=hashlib.sha256()
 with path.open('rb')as f:
  for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
 return h.hexdigest()
assert (web/'WEB-INF').is_dir() and urllib.request.urlopen('http://127.0.0.1:8080/client/').status==200
assert backup.parent==Path('/nfs') and backup.name=='snapshot-detail-1486-20261025-ui-backup' and not backup.exists()
backup.mkdir(mode=0o700)
protected={str(f.relative_to(web)):sha(f)for name in ['WEB-INF','META-INF'] for f in(web/name).rglob('*')if f.is_file()}
meta=(web/'META-INF').is_dir();config={str(f.relative_to(web)):sha(f)for f in[web/'config.json',web/'config.js']if f.is_file()}
large={str(f.relative_to(web)):{'size':f.stat().st_size,'inode':f.stat().st_ino,'mtime_ns':f.stat().st_mtime_ns}for f in web.iterdir()if f.is_file()and f.stat().st_size>64*1024*1024}
jars=list(Path('/usr/share/cloudstack-management/lib').glob('cloudstack-*.jar'));assert len(jars)==1;jarsha=sha(jars[0])
manifest=json.loads((stage/'ui-manifest.json').read_text());created=[]
for item in sorted(manifest,key=lambda x:x['path']=='index.html'):
 rel=Path(item['path']);assert not rel.is_absolute()and '..'not in rel.parts and rel.parts[0]not in['WEB-INF','META-INF','config.json','config.js'];source=stage/'ui'/rel;target=web/rel
 assert source.stat().st_size<64*1024*1024 and sha(source)==item['sha256']
 assert web.resolve() in target.resolve().parents and not target.is_symlink()
 if target.is_file():
  copy=backup/rel;copy.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(target,copy)
 else:created.append(str(rel))
 target.parent.mkdir(parents=True,exist_ok=True);temp=target.with_name(target.name+'.snapshot-detail1486.tmp');shutil.copy2(source,temp);os.replace(temp,target)
for item in manifest:assert sha(web/item['path'])==item['sha256']
assert all(sha(web/f)==h for f,h in protected.items())and all(sha(web/f)==h for f,h in config.items())
assert (web/'WEB-INF').is_dir()and (web/'META-INF').is_dir()==meta and sha(jars[0])==jarsha
assert all((web/f).stat().st_size==m['size']and(web/f).stat().st_ino==m['inode']and(web/f).stat().st_mtime_ns==m['mtime_ns']for f,m in large.items())
assert subprocess.check_output(['systemctl','is-active','mold'],text=True).strip()=='active'
assert urllib.request.urlopen('http://127.0.0.1:8080/client/').status==200
markers=['snapshot-detail-summary','snapshot-detail-properties','message.snapshot.detail.incomplete','resource.tags.v1:'];found={m:False for m in markers}
for item in manifest:
 if item['path'].endswith('.js'):
  text=(web/item['path']).read_text();found={m:ok or m in text for m,ok in found.items()}
assert all(found.values())
result={'files':len(manifest),'verified':True,'backup':str(backup),'protectedServerFiles':len(protected),'webInf':True,'metaInfPreserved':True,'configHashes':config,'managementJarUnchanged':jarsha,'largeFileMetadataUnchanged':large,'mold':'active','clientHttp':200,'markers':found,'newStaticFiles':created,'disk':subprocess.check_output(['df','-h','/','/nfs'],text=True)}
(backup/'ui-result.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
