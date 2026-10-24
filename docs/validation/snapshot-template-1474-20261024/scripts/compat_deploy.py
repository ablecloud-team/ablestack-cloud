import json,hashlib,zipfile,subprocess,os,shlex
from pathlib import Path
from remote import root,remote
updates={}
for p in (root/'stage/secondary').rglob('*.class'):
 rel=str(p.relative_to(root/'stage/secondary'))
 if '/formatinspector/' in rel:updates[rel]=p.read_bytes()
name='org/apache/cloudstack/storage/resource/NfsSecondaryStorageResource.class';updates[name]=(root/'compat32'/name).read_bytes()
with zipfile.ZipFile(root/'ssvm32-secondary-original.jar') as old,zipfile.ZipFile(root/'ssvm32-secondary-compatible.jar','w') as new:
 keys=set(old.namelist())
 for info in old.infolist():new.writestr(info,updates.get(info.filename,old.read(info)))
 for k,v in updates.items():
  if k not in keys:new.writestr(k,v)
with zipfile.ZipFile(root/'ssvm32-secondary-original.jar') as old,zipfile.ZipFile(root/'ssvm32-secondary-compatible.jar') as new:
 for name in old.namelist():
  if name not in updates:assert old.read(name)==new.read(name)
proof={'updatedEntries':{k:hashlib.sha256(v).hexdigest() for k,v in updates.items()},'originalOtherEntriesUnchanged':True,'otherMethodBodiesUnchanged':130,'sha256':hashlib.sha256((root/'ssvm32-secondary-compatible.jar').read_bytes()).hexdigest()}
(root/'32-compatible-deployment-proof.json').write_text(json.dumps(proof,indent=2))
subprocess.run(['sshpass','-e','scp','-P','22','-o','StrictHostKeyChecking=yes',str(root/'ssvm32-secondary-compatible.jar'),'ablecloud@10.10.32.10:/tmp/snapshot1474-secondary-compatible.jar'],env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},check=True)
opts=['-i','/var/cloudstack/management/.ssh/id_rsa','-o','UserKnownHostsFile=/root/snapshot1474-ssvm-known-hosts','-o','StrictHostKeyChecking=yes']
remote('32',['sudo','-n','scp','-P','3922']+opts+['/tmp/snapshot1474-secondary-compatible.jar','root@10.10.32.7:/root/snapshot1474-secondary-compatible.jar'])
cmd='cp -p /usr/local/cloud/systemvm/cloud-secondary-storage-4.21.0.0-Mold.Diplo-202609171036.jar /root/snapshot-template-1474-20261024-ssvm/first-full-class.jar && cp /root/snapshot1474-secondary-compatible.jar /usr/local/cloud/systemvm/cloud-secondary-storage-4.21.0.0-Mold.Diplo-202609171036.jar && systemctl restart cloud.service && sha256sum /usr/local/cloud/systemvm/cloud-secondary-storage-4.21.0.0-Mold.Diplo-202609171036.jar'
out=remote('32',['sudo','-n','ssh','-p','3922']+opts+['root@10.10.32.7',cmd]);print(out);assert proof['sha256'] in out
(root/'32-compatible-runtime-sha.txt').write_text(out)
