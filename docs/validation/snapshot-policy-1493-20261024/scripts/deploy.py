import json,hashlib,shutil,sys,tarfile,subprocess,os
from pathlib import Path
from remote import root,remote
c=sys.argv[1];repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots');stage=root/('server-'+c);stage.mkdir(exist_ok=True);manifest=[]
paths=[('api','com/cloud/storage/Snapshot.class'),('api','org/apache/cloudstack/api/command/user/snapshot/CreateSnapshotCmd.class'),('api','org/apache/cloudstack/api/command/user/snapshot/CreateSnapshotBackupCmd.class')]
paths += [('server',str(x.relative_to(repo/'server/target/classes'))) for x in (repo/'server/target/classes/com/cloud/storage/snapshot').glob('SnapshotManagerImpl*.class')]
for module,path in paths:
 dest=stage/'management'/path;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(repo/module/'target/classes'/path,dest);manifest.append({'group':'management','path':path,'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
(stage/'manifest.json').write_text(json.dumps(manifest));shutil.copy2(root/'scripts/patch_runtime.py',stage/'patch_runtime.py');shutil.copy2(repo/'scripts/util/repair-snapshot-policy-types.py',stage/'repair-snapshot-policy-types.py')
shutil.copy2(root/(c+'-archive-policy-evidence.json'),stage/'archive-policy-evidence.json')
archive=root/(c+'-server.tgz')
with tarfile.open(archive,'w:gz') as tar:
 for f in stage.rglob('*'):
  if f.is_file():tar.add(f,arcname=str(f.relative_to(stage)))
subprocess.run(['sshpass','-e','scp','-P','22','-o','StrictHostKeyChecking=yes',str(archive),'ablecloud@10.10.'+c+'.10:/tmp/snapshot1493-server.tgz'],env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},check=True)
remote(c,['sudo','-n','mkdir','-p','/root/snapshot1493-stage']);remote(c,['sudo','-n','tar','xzf','/tmp/snapshot1493-server.tgz','-C','/root/snapshot1493-stage'])
staticcode="import pathlib,hashlib,json\nw=pathlib.Path('/usr/share/cloudstack-management/webapp');assert (w/'WEB-INF').is_dir();result={}\nfor p in w.rglob('*'):\n if not p.is_file():continue\n st=p.stat()\n if st.st_size>64*1024*1024:\n  result[str(p.relative_to(w))]={'size':st.st_size,'mtime_ns':st.st_mtime_ns,'inode':st.st_ino}\n else:\n  h=hashlib.sha256()\n  with p.open('rb') as f:\n   for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)\n  result[str(p.relative_to(w))]=h.hexdigest()\nprint(json.dumps(result))\n"
before=json.loads(remote(c,['sudo','-n','python3','-c',staticcode]));(root/(c+'-webapp-before.json')).write_text(json.dumps(before))
out=remote(c,['sudo','-n','python3','/root/snapshot1493-stage/patch_runtime.py','/root/snapshot1493-stage','management','/root/snapshot-policy-1493-20261024-management']);(root/(c+'-management-deploy.json')).write_text(out)
remote(c,['sudo','-n','systemctl','restart','mold'])
check="""import time,urllib.request,subprocess,json
for i in range(180):
 try:
  status=urllib.request.urlopen('http://127.0.0.1:8080/client/',timeout=2).status
  if status==200:break
 except Exception:pass
 time.sleep(1)
else:raise Exception('Mold did not become ready')
assert subprocess.check_output(['systemctl','is-active','mold'],text=True).strip()=='active'
print(json.dumps({'mold':'active','clientHttp':status}))"""
runtime=json.loads(remote(c,['sudo','-n','python3','-c',check]));after=json.loads(remote(c,['sudo','-n','python3','-c',staticcode]));assert before==after
runtime.update({'webappFilesUnchanged':len(after),'classes':manifest,'otherJarEntriesUnchanged':True})
(root/(c+'-runtime.json')).write_text(json.dumps(runtime,indent=2));print(c,'deployed',len(manifest),'classes, unchanged webapp',len(after),runtime['mold'],runtime['clientHttp'])
