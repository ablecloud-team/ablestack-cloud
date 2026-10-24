import hashlib,json,shutil,tarfile,subprocess,os
from pathlib import Path
from remote import root,remote
repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots')
stage=root/'stage';manifest=json.loads((stage/'manifest.json').read_text())
for x in manifest:
 src=repo/('core/target/classes' if x['group']=='core' else 'services/secondary-storage/server/target/classes')/x['path']
 shutil.copy2(src,stage/x['group']/x['path']);x['sha256']=hashlib.sha256(src.read_bytes()).hexdigest()
(stage/'manifest.json').write_text(json.dumps(manifest,indent=2))
with tarfile.open(root/'runtime-final.tgz','w:gz') as tar:
 for p in stage.rglob('*'):
  if p.is_file():tar.add(p,arcname=str(p.relative_to(stage)))
subprocess.run(['sshpass','-e','scp','-P','22','-o','StrictHostKeyChecking=yes',str(root/'runtime-final.tgz'),'ablecloud@10.10.31.10:/tmp/snapshot1474-runtime-final.tgz'],env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},check=True)
opts=['-i','/var/cloudstack/management/.ssh/id_rsa','-o','UserKnownHostsFile=/root/snapshot1474-ssvm-known-hosts','-o','StrictHostKeyChecking=yes']
remote('31',['sudo','-n','scp','-P','3922']+opts+['/tmp/snapshot1474-runtime-final.tgz','root@10.10.31.8:/root/snapshot1474-runtime-final.tgz'])
out=remote('31',['sudo','-n','ssh','-p','3922']+opts+['root@10.10.31.8','mkdir -p /root/snapshot1474-final; tar xzf /root/snapshot1474-runtime-final.tgz -C /root/snapshot1474-final; python3 /root/snapshot1474-final/patch_runtime.py /root/snapshot1474-final ssvm-verified; systemctl restart cloud.service'])
(root/'31-ssvm-verified-deploy.json').write_text(out);print('31 SSVM final classes deployed')
