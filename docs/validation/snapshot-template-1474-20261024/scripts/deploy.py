import hashlib,json,shutil,tarfile,subprocess,os,shlex,sys
from pathlib import Path
from remote import remote,root
repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots')
stage=root/'stage';stage.mkdir(exist_ok=True)
manifest=[]
patterns={
 'core':('core/target/classes',['org/apache/cloudstack/storage/to/SnapshotObjectTO.class','org/apache/cloudstack/storage/command/BackupDeleteAnswer.class']),
 'secondary':('services/secondary-storage/server/target/classes',['org/apache/cloudstack/storage/resource/NfsSecondaryStorageResource*.class','org/apache/cloudstack/storage/formatinspector/SnapshotImageMetadata.class','org/apache/cloudstack/storage/formatinspector/Qcow2*.class'])}
for group,(base,globs) in patterns.items():
 base=repo/base
 for pattern in globs:
  for p in base.glob(pattern):
   rel=p.relative_to(base);dst=stage/group/rel;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dst)
   manifest.append({'group':group,'path':str(rel),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
assert len(manifest)>=5
(stage/'manifest.json').write_text(json.dumps(manifest,indent=2));shutil.copy2(root/'scripts/patch_runtime.py',stage/'patch_runtime.py')
archive=root/'runtime.tgz'
with tarfile.open(archive,'w:gz') as tar:
 for p in stage.rglob('*'):
  if p.is_file():tar.add(p,arcname=str(p.relative_to(stage)))
c=sys.argv[1]
cmd=['sshpass','-e','scp','-P','22','-o','StrictHostKeyChecking=yes',str(archive),'ablecloud@10.10.'+c+'.10:/tmp/snapshot1474-runtime.tgz']
subprocess.run(cmd,env={**os.environ,'SSHPASS':os.environ['SSH_SECRET']},check=True)
remote(c,['sudo','-n','mkdir','-p','/root/snapshot1474-stage'])
remote(c,['sudo','-n','tar','xzf','/tmp/snapshot1474-runtime.tgz','-C','/root/snapshot1474-stage'])
# Patch management sender first, then the receiving SSVM, before restarting either service.
out=remote(c,['sudo','-n','python3','/root/snapshot1474-stage/patch_runtime.py','/root/snapshot1474-stage','management'])
(root/(c+'-management-deploy.json')).write_text(out)
ip='10.10.'+c+('.8' if c=='31' else '.7')
opts=['-p','3922','-i','/var/cloudstack/management/.ssh/id_rsa','-o','UserKnownHostsFile=/root/snapshot1474-ssvm-known-hosts','-o','StrictHostKeyChecking=yes']
remote(c,['sudo','-n','scp','-P','3922','-i','/var/cloudstack/management/.ssh/id_rsa','-o','UserKnownHostsFile=/root/snapshot1474-ssvm-known-hosts','-o','StrictHostKeyChecking=yes','/tmp/snapshot1474-runtime.tgz','root@'+ip+':/root/snapshot1474-runtime.tgz'])
remote(c,['sudo','-n','ssh']+opts+['root@'+ip,'mkdir -p /root/snapshot1474-stage && tar xzf /root/snapshot1474-runtime.tgz -C /root/snapshot1474-stage'])
out=remote(c,['sudo','-n','ssh']+opts+['root@'+ip,'python3 /root/snapshot1474-stage/patch_runtime.py /root/snapshot1474-stage ssvm'])
(root/(c+'-ssvm-deploy.json')).write_text(out)
remote(c,['sudo','-n','ssh']+opts+['root@'+ip,'systemctl restart cloud.service && systemctl is-active cloud.service'])
remote(c,['sudo','-n','systemctl','restart','mold'])
print(c,'deployed',len(manifest),'classes; backups retained')
