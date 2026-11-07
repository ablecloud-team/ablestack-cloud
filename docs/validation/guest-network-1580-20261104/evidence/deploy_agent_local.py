import base64,json,os,pathlib,shlex,subprocess,sys,zipfile
root=pathlib.Path('/home/ablecloud/work/guest-network-1580-validation')
repo=pathlib.Path('/home/ablecloud/work/dhslove/cloud-epic-1554')
prefix='com/cloud/hypervisor/kvm/resource/'
classes={prefix+n+'.class':base64.b64encode((repo/'plugins/hypervisors/kvm/target/classes'/prefix/(n+'.class')).read_bytes()).decode()for n in ['BridgeVifDriver','IvsVifDriver','VlanBridgeName']}
payload=json.dumps({'classes':classes,'script':base64.b64encode((repo/'scripts/vm/network/vnet/modifyvlan.sh').read_bytes()).decode(),'hook':base64.b64encode((repo/'agent/bindir/libvirtqemuhook.in').read_bytes()).decode(),'source':sys.argv[1]}).encode()
code=(root/'deploy_agent_remote.py').read_text()
def ssh(c,h,command,data=None):
 env=os.environ.copy()
 if c==31:env['SSHPASS']=env['HOST_PASSWORD'];endpoint=f'root@10.10.{c}.{h}'
 else:
  env['SSHPASS']=env['MGMT_PASSWORD'];endpoint='ablecloud@10.10.32.10'
  command='sudo -n ssh -p22 -oBatchMode=yes root@10.10.32.'+str(h)+' '+shlex.quote(command)
 return subprocess.check_output(['sshpass','-e','ssh','-p22',endpoint,command],input=data,env=env,timeout=100)
for c in (31,32):
 for h in (1,2,3):
  if (root/f'{c}-{h}-agent-final-deployment.json').exists():continue
  basejar=next((root/'backups'/f'{c}-{h}').glob('*.jar'))
  derived=root/'derived'/f'{c}-{h}';derived.mkdir(parents=True,exist_ok=True)
  with zipfile.ZipFile(basejar)as z:(derived/'base.class').write_bytes(z.read(prefix+'LibvirtComputingResource.class'))
  subprocess.run(['java','--add-exports','java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED','--add-exports','java.base/jdk.internal.org.objectweb.asm.tree=ALL-UNNAMED','-cp',str(root),'PreserveResourceMethods',str(derived/'base.class'),str(repo/'plugins/hypervisors/kvm/target/classes'/prefix/'LibvirtComputingResource.class'),str(derived/'resource.class')],check=True)
  request=json.loads(payload);request['classes'][prefix+'LibvirtComputingResource.class']=base64.b64encode((derived/'resource.class').read_bytes()).decode()
  result=json.loads(ssh(c,h,'python3 -c '+shlex.quote(code),json.dumps(request).encode()))
  (root/f'{c}-{h}-agent-final-deployment.json').write_text(json.dumps(result,indent=2)+'\n')
  folder=root/'backups-final'/f'{c}-{h}';folder.mkdir(parents=True,exist_ok=True)
  for name in [pathlib.Path(result['jar']).name,'modifyvlan.sh','qemu','receipt.json']:
   (folder/name).write_bytes(ssh(c,h,'cat '+shlex.quote(result['backup']+'/'+name)))
  print(c,h,result['service'].strip(),result['after_script_sha256'],flush=True)
