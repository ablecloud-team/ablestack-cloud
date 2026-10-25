import json,sys,shlex
from remote import root,remote,sql
c=sys.argv[1];reg=json.loads((root/(c+'-fixtures.json')).read_text());agents=json.loads((root/(c+'-dedicated-agents.json')).read_text());baseline=json.loads((root/(c+'-ssvm-baseline.json')).read_text());ip=baseline['host']['ipaddress'];originalPid=baseline['process']['processes'][0]['pid']
for z in reg['zones']:
 rows=sql(c,"SELECT JSON_OBJECT('active',COUNT(*)) FROM snapshot_store_ref r JOIN image_store i ON i.id=r.store_id WHERE r.store_role='Image' AND r.state<>'Destroyed' AND i.uuid='"+z['store']['id']+"';");assert rows[0]['active']==0,rows
ssh=['sudo','-n','ssh','-p','3922','-i','/var/cloudstack/management/.ssh/id_rsa','-o','UserKnownHostsFile=/root/snapshot1474-ssvm-known-hosts','-o','StrictHostKeyChecking=yes','root@'+ip]
code=r"""import pathlib,json,os,signal,time,hashlib,subprocess,shutil
agents=AGENTS;originalPid=ORIGINAL
out=[]
assert pathlib.Path('/proc/'+originalPid).exists()
for a in agents:
 b=pathlib.Path(a['base']);assert b.parent==pathlib.Path('/root')and b.name.startswith('SNAPDIALOG1488-')and not b.is_symlink()
 hashes=json.loads((b/'preserved.json').read_text());changed=[p for p,h in hashes.items()if hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()!=h]
 assert all(p=='/usr/local/cloud/systemvm/conf/agent.properties'for p in changed),changed
 originalArgs=(pathlib.Path('/proc')/originalPid/'cmdline').read_bytes().decode().split(chr(0));expectedGuid=next(x.split('=',1)[1]for x in originalArgs if x.startswith('guid='))
 current=dict(x.split('=',1)for x in pathlib.Path('/usr/local/cloud/systemvm/conf/agent.properties').read_text().splitlines()if'='in x and not x.startswith('#'));assert current.get('guid')==expectedGuid
 assert all(current.get(k)==next(x.split('=',1)[1]for x in originalArgs if x.startswith(k+'='))for k in ['zone','pod'])
 assert current.get('host','').split('@')[0]==next(x.split('=',1)[1]for x in originalArgs if x.startswith('host=')).split('@')[0]
 pid=int((b/'pid').read_text());p=pathlib.Path('/proc')/str(pid)
 if p.exists():
  args=(p/'cmdline').read_bytes().decode().split(chr(0));assert 'guid='+b.name in args;assert str(pid)!=originalPid;os.kill(pid,signal.SIGTERM)
  for i in range(50):
   if not p.exists():break
   time.sleep(0.1)
  if p.exists():
   args=(p/'cmdline').read_bytes().decode().split(chr(0));assert 'guid='+b.name in args;os.kill(pid,signal.SIGKILL)
 lower=pathlib.Path(a['mount']);bind=pathlib.Path('/mnt/SecStorage')/b.name
 files=[str(p)for p in lower.rglob('*')if p.is_file()];assert not files,files
 for path in [bind,lower]:
  assert path.name==b.name and not path.is_symlink()
  if path.is_mount():subprocess.run(['umount',str(path)],check=True)
  if path.exists():path.rmdir()
 out.append({'name':b.name,'pid':pid,'stopped':True,'mountsRemoved':True,'physicalFilesBeforeUnmount':len(files),'originalIdentityNetworkAndCertificatesPreserved':True,'generatedPropertiesHashChangedOnReconnect':bool(changed)})
 shutil.rmtree(b)
assert pathlib.Path('/proc/'+originalPid).exists()
print(json.dumps({'agents':out,'originalAgentPid':originalPid,'originalAgentAlive':True,'remainingNfs':json.loads(subprocess.check_output(['findmnt','-J','-t','nfs,nfs4'],text=True))}))
""".replace('AGENTS',repr(agents)).replace('ORIGINAL',repr(originalPid))
out=json.loads(remote(c,ssh+[shlex.join(['python3','-c',code])]))
(root/(c+'-agent-cleanup.json')).write_text(json.dumps(out,indent=2));print(c,'dedicated agents/mounts/configs removed; original agent and configuration preserved')
