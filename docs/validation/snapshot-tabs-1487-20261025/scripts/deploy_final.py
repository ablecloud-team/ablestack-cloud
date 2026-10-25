import json,sys,os,shutil,hashlib,tarfile,subprocess
from pathlib import Path
from remote import root,remote
repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots');mode=sys.argv[1]
if mode=='pack':
 log=(root/'ui-build-final.log').read_text();assert 'Build complete.'in log and 'Build failed'not in log
 stage=root/'ui-stage-final';assert not stage.exists();stage.mkdir()
 # Locale JSON is fetched at runtime, independently of the compiled JS. Synchronize final wording.
 for lang in ['en','ko_KR']:
  source=repo/'ui/public/locales'/(lang+'.json');json.loads(source.read_text());shutil.copy2(source,repo/'ui/dist/locales'/source.name)
 files=[f for f in (repo/'ui/dist').rglob('*')if f.is_file()];assert all(f.stat().st_size<64*1024*1024 for f in files)
 shutil.copytree(repo/'ui/dist',stage/'ui')
 manifest=[{'path':str(f.relative_to(stage/'ui')),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}for f in(stage/'ui').rglob('*')if f.is_file()and f.relative_to(stage/'ui').parts[0]not in['WEB-INF','META-INF','config.json','config.js']]
 assert all((stage/'ui'/x['path']).stat().st_size<64*1024*1024 for x in manifest)
 (stage/'ui-manifest.json').write_text(json.dumps(manifest));shutil.copy2(root/'scripts/deploy_ui_final.py',stage/'deploy_ui.py')
 with tarfile.open(root/'ui-final.tgz','w:gz')as tar:
  for f in stage.rglob('*'):
   if f.is_file():tar.add(f,arcname=str(f.relative_to(stage)))
 print('UI packed',len(manifest),'files',sum((stage/'ui'/x['path']).stat().st_size for x in manifest),'bytes')
else:
 c=mode;assert c in['31','32'];subprocess.run(['sshpass','-e','scp','-P','22','-o','StrictHostKeyChecking=yes',str(root/'ui-final.tgz'),'ablecloud@10.10.'+c+'.10:/tmp/snapshot-tabs1487-ui-final.tgz'],env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},check=True)
 stage='/nfs/snapshot-tabs-1487-20261025-ui-stage-final';remote(c,['sudo','-n','mkdir','-m','700',stage]);remote(c,['sudo','-n','tar','xzf','/tmp/snapshot-tabs1487-ui-final.tgz','-C',stage])
 out=json.loads(remote(c,['sudo','-n','python3',stage+'/deploy_ui.py',stage,'/nfs/snapshot-tabs-1487-20261025-ui-final-backup']));(root/(c+'-ui-deploy-final.json')).write_text(json.dumps(out,indent=2));print(c,'UI deploy verified',out['files'],'files,Mold active,HTTP200,WEB-INF/config/JAR preserved')
