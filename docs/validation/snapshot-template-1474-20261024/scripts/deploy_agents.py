import json,hashlib,shutil,subprocess,tarfile,os,sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from remote import root,remote
repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots');asm=':'.join(str(Path('/home/ablecloud/.m2/repository/org/ow2/asm')/n/'9.7'/(n+'-9.7.jar')) for n in ['asm','asm-tree'])
def deploy(pair):
 c,h=pair;label=c+'-'+h;stage=root/('agent-'+label);stage.mkdir(exist_ok=True)
 jars=json.loads(remote(c,['python3','-c',"import json;from pathlib import Path;print(json.dumps([str(p) for p in Path('/usr/share/cloudstack-agent/lib').glob('cloud-plugin-hypervisor-kvm*.jar')]))"],h));assert len(jars)==1
 opts=['-o','StrictHostKeyChecking=yes']
 if c=='32':opts+=['-o','UserKnownHostsFile=/home/ablecloud/work/snapshot-archive-1477-20261023/trusted-hosts32']
 old=stage/'original-kvm.jar'
 env={**os.environ,'SSHPASS':os.environ['HOST_SECRET']}
 subprocess.run(['sshpass','-e','scp','-P','22']+opts+['root@10.10.'+c+'.'+h+':'+jars[0],str(old)],env=env,check=True)
 out=subprocess.check_output(['java','-cp',str(root)+':'+asm,'SnapshotMethodPatchAsm',str(old),str(repo/'plugins/hypervisors/kvm/target/classes'),str(stage/'kvm'),'com.cloud.hypervisor.kvm.storage.KVMStorageProcessor','createVolumeFromSnapshotOnNFS'],text=True);(stage/'methods.log').write_text(out)
 items=[]
 for group,base,patterns in [('kvm',stage/'kvm',['**/*.class']),('core',repo/'core/target/classes',['org/apache/cloudstack/storage/to/SnapshotObjectTO.class']),('secondary',repo/'services/secondary-storage/server/target/classes',['org/apache/cloudstack/storage/formatinspector/*.class'])]:
  for pat in patterns:
   for p in base.glob(pat):
    rel=p.relative_to(base);dst=stage/group/rel
    if p!=dst:dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dst)
    items.append({'group':group,'path':str(rel),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
 (stage/'manifest.json').write_text(json.dumps(items,indent=2));shutil.copy2(root/'scripts/patch_agent.py',stage/'patch_agent.py')
 archive=root/('agent-'+label+'.tgz')
 with tarfile.open(archive,'w:gz') as tar:
  for p in stage.rglob('*'):
   if p.is_file() and p.name!='original-kvm.jar':tar.add(p,arcname=str(p.relative_to(stage)))
 subprocess.run(['sshpass','-e','scp','-P','22']+opts+[str(archive),'root@10.10.'+c+'.'+h+':/root/snapshot1474-agent.tgz'],env=env,check=True)
 remote(c,['mkdir','-p','/root/snapshot1474-stage'],h);remote(c,['tar','xzf','/root/snapshot1474-agent.tgz','-C','/root/snapshot1474-stage'],h)
 out=remote(c,['python3','/root/snapshot1474-stage/patch_agent.py','/root/snapshot1474-stage'],h);(root/(label+'-agent-deploy.json')).write_text(out)
 proof=json.loads(out);print(label,'PASS','VMs preserved',proof['vmsUnchanged'],flush=True);assert proof['vmsUnchanged'];return label
with ThreadPoolExecutor(max_workers=3) as pool:
 for result in pool.map(deploy,[(c,h) for c in ['31','32'] for h in ['1','2','3']]):pass
