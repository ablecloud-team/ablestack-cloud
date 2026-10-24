import json,sys,shlex
from remote import root,sql,remote
c,mode=sys.argv[1:3];reg=json.loads((root/(c+'-fixtures.json')).read_text());scenario='root' if mode=='root-raw' else ('raw' if mode=='raw' else 'negative')
s=next(x for x in reg['snapshots'] if x['scenario']==scenario)
r=sql(c,"SELECT JSON_OBJECT('uuid',s.uuid,'name',s.name,'state',s.status,'size',s.size,'path',r.install_path) FROM snapshots s JOIN snapshot_store_ref r ON r.snapshot_id=s.id WHERE s.uuid='"+s['id']+"' AND r.store_role='Image' AND r.state='Ready';")[0]
assert r['name']==reg['prefix']+'-'+scenario and r['state']=='BackedUp';p=r['path'];p='/nfs/secondary/snapshots/'+p.split('snapshots/',1)[1]
script='''import os,subprocess,json,sys,shutil,hashlib
from pathlib import Path
p=Path(sys.argv[1]);mode=sys.argv[2];size=int(sys.argv[3]);backup=Path('/root/snapshot-template1474-fixture-backups');backup.mkdir(exist_ok=True)
b=backup/p.name
if not b.exists():shutil.copy2(p,b)
original=hashlib.sha256(b.read_bytes()).hexdigest()
if mode=='raw':
 temp=p.with_suffix('.1474-raw-tmp');subprocess.run(['qemu-img','convert','-f','qcow2','-O','raw',str(b),str(temp)],check=True);shutil.copystat(p,temp);os.replace(temp,p)
elif mode=='unsupported':
 temp=p.with_suffix('.1474-vmdk-tmp');subprocess.run(['qemu-img','convert','-f','qcow2','-O','vmdk',str(b),str(temp)],check=True);shutil.copystat(p,temp);os.replace(temp,p)
elif mode=='missing':p.unlink()
elif mode=='corrupt':
 with p.open('wb') as f:f.write(bytes.fromhex('514649fb00000003'))
elif mode=='restore':\n if p.is_dir():\n  children=list(p.iterdir())\n  if children:\n   assert len(children)==1 and children[0].name==p.name and hashlib.sha256(children[0].read_bytes()).hexdigest()==original\n   children[0].unlink()\n  p.rmdir()\n shutil.copy2(b,p);assert hashlib.sha256(p.read_bytes()).hexdigest()==original
else:raise Exception('unknown mode')
r={'path':str(p),'mode':mode,'originalSha256':original,'exists':p.is_file(),'bytes':p.stat().st_size if p.exists() else 0}
if p.is_file():
 q=subprocess.run(['qemu-img','info','--output=json',str(p)],capture_output=True,text=True);r['qemuExit']=q.returncode;r['qemu']=json.loads(q.stdout) if q.returncode==0 else q.stderr
print(json.dumps(r))'''
out=remote(c,['sudo','-n','python3','-c',script,p,'raw' if mode=='root-raw' else mode,str(r['size'])]);(root/(c+'-injection-'+mode+'.json')).write_text(out);print(c,mode,'injected and backed up')
