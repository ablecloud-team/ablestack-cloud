#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Design-only execution of deployed functions with injected command boundaries."""
from pathlib import Path
import ast,subprocess,tempfile,shutil,os,ipaddress,json,re,copy
from types import SimpleNamespace
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='2e65bb57-9f05-39ef-bd89-08daadd33364'
runtime=Path('/opt/ablestack/storage-runtime/current').resolve()
source=(runtime/'ablestack-storagectl').read_text().splitlines()
a=next(i for i,x in enumerate(source) if x.startswith('def probe_nfs_export_visibility('))
b=next(i for i in range(a+1,len(source)) if source[i] and not source[i][0].isspace())
compiled=compile(ast.parse('\n'.join(source[a:b])),'deployed NFS probe','exec')
checks=[]
with tempfile.TemporaryDirectory(prefix='epic1459-contract-',dir='/var/tmp') as tmp:
 root=Path(tmp);probe=root/'mounts';probe.mkdir()
 calls=[]
 def fake(args,**kw):
  calls.append(args)
  if args[0]=='ip':return subprocess.CompletedProcess(args,0,'2: probe0 inet 192.0.2.59/32 scope global probe0\n','')
  if args[0]=='mount' and behavior=='timeout':raise subprocess.TimeoutExpired(args,kw.get('timeout',15))
  return subprocess.CompletedProcess(args,0,'','')
 scope={'subprocess':SimpleNamespace(run=fake,PIPE=subprocess.PIPE),'ipaddress':ipaddress,'tempfile':tempfile,'shutil':shutil,'nfs_probe_root':str(probe),'endpoint_key':lambda ip,p:str(p)}
 exec(compiled,scope)
 matrix=[]
 for count in (1,10,100):
  for mode,ports in [('V4_ONLY',[23049,23050]),('V3V4_DUAL',[2049])]:
   calls.clear();behavior='success'
   rows=[{'uuid':str(i),'name':'design'+str(i),'path':'/private/'+str(i),'pseudo':'/e'+str(i),'protocolMode':mode,'clients':[{'clients':'192.0.2.59/32'}]} for i in range(count)]
   endpoints=[{'listenIp':'0.0.0.0','port':p} for p in ports]
   failed=scope['probe_nfs_export_visibility'](endpoints,{('0.0.0.0',p):copy.deepcopy(rows) for p in ports})
   mounts=[x for x in calls if x[0]=='mount'];unmounts=[x for x in calls if x[0]=='umount']
   expected=count*(2 if mode=='V3V4_DUAL' else len(ports))
   assert not failed and len(mounts)==len(unmounts)==expected
   matrix.append({'exports':count,'mode':mode,'listeners':len(ports),'mountCalls':len(mounts),'unmountCalls':len(unmounts),'fixedMountTimeoutSeconds':15,'sequentialWorstMountSeconds':len(mounts)*15,'under120SecondUnitIfEachMountTakes1Second':len(mounts)<120})
 checks.append({'name':'deployed probe sequential cost for 1/10/100 actual function (injected successful subprocess responses)','matrix':matrix,'passed':True})
 calls.clear();behavior='timeout'
 endpoint={'listenIp':'0.0.0.0','port':23049}
 error=None
 try:scope['probe_nfs_export_visibility']([endpoint],{('0.0.0.0',23049):rows[:1]})
 except subprocess.TimeoutExpired as e:error=type(e).__name__
 assert error=='TimeoutExpired' and not any(c[0]=='umount' for c in calls) and not endpoint['probeResults']
 checks.append({'name':'deployed timeout escapes before a diagnostic row and skips unmount attempt','passed':True,'exception':error,'unmountCalls':0,'diagnosticRows':0})
 boot=(runtime/'ablestack-storage-boot-reconcile').read_text()
 boot=re.sub(r'ensure_ganesha_runtime_dirs\(\) \{.*?\n\}', 'ensure_ganesha_runtime_dirs() { :; }',boot,flags=re.S)
 boot=re.sub(r'ensure_nvmeof_runtime\(\) \{.*?\n\}', 'ensure_nvmeof_runtime() { :; }',boot,flags=re.S)
 boot=boot.replace('/run/lock/ablestack-storage-boot-reconcile.lock',str(root/'boot.lock')).replace('NETWORK_HELPER=/usr/local/sbin/ablestack-sharedfs-network','NETWORK_HELPER='+str(root/'network'))
 path=root/'bin';path.mkdir()
 for name in ['mount','systemctl']:
  f=path/name;f.write_text('#!/bin/sh\nexit 0\n');f.chmod(0o755)
 network=root/'network';network.write_text('#!/bin/sh\nexit 0\n');network.chmod(0o755)
 ctl=root/'ctl';ctl.write_text('#!/usr/bin/python3\nfrom pathlib import Path\nimport sys,json\nr=Path('+repr(str(root))+')\nc=r/"calls.json"\na=json.loads(c.read_text()) if c.exists() else []\na.append(" ".join(sys.argv[1:3]));c.write_text(json.dumps(a))\nif sys.argv[1:3]==["nfs","export"] and a.count("nfs export")==1:sys.exit(1)\nprint("{}")\n');ctl.chmod(0o755)
 trial=root/'boot';trial.write_text(boot)
 desired=root/'desired';desired.mkdir()
 for name in ['nfs-export-apply.json','smb-share-apply.json','iscsi.json','nvme.json']:(desired/name).write_text('{}')
 env={**os.environ,'PATH':str(path)+':'+os.environ['PATH'],'ABLESTACK_STORAGECTL':str(ctl),'ABLESTACK_STORAGE_MONITOR':'/does/not/exist','ABLESTACK_STORAGE_DESIRED_STATE_DIR':str(desired),'ABLESTACK_STORAGE_ISCSI_DESIRED_STATE_FILE':str(desired/'iscsi.json'),'ABLESTACK_STORAGE_NVMEOF_DESIRED_STATE_FILE':str(desired/'nvme.json'),'ABLESTACK_STORAGE_BOOT_RECONCILE_LOG':str(root/'boot.log'),'ABLESTACK_STORAGE_BOOT_RECONCILE_ATTEMPTS':'2','ABLESTACK_STORAGE_BOOT_RECONCILE_SLEEP':'0'}
 result=subprocess.run(['bash',str(trial)],env=env,capture_output=True,text=True,timeout=20)
 calls=json.loads((root/'calls.json').read_text());counts={p:calls.count(p) for p in ['nfs export','smb share','iscsi target','nvmeof subsystem']}
 assert result.returncode==0 and all(x==2 for x in counts.values()),(result.stderr,counts)
 checks.append({'name':'deployed orchestrator repeats successful protocols after one NFS failure','passed':True,'calls':counts,'scope':'copy only; all command side effects replaced'})
print(json.dumps({'success':True,'runtime':runtime.name,'checks':checks,'productionWrites':0,'kind':'design-only fault injection; not product acceptance'}))
