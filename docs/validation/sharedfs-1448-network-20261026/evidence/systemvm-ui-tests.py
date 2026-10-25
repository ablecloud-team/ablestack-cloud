#!/usr/bin/env python3
"""#1448 controlled SystemVM tests; run only from the Mold UI console."""
import hashlib,json,os,re,subprocess,sys
from pathlib import Path
STATE=Path('/etc/ablestack-storage/sharedfs-network.json')
def run(args,**kwargs):
 return subprocess.run(args,text=True,capture_output=True,timeout=20,**kwargs)
def facts():
 return {'addresses':json.loads(subprocess.check_output(['ip','-j','-4','addr'],text=True)),
         'routes':json.loads(subprocess.check_output(['ip','-j','-4','route'],text=True)),
         'dns':Path('/etc/resolv.conf').read_text()}
def listeners():
 return sorted(line.split()[3] for line in subprocess.check_output(['ss','-H','-lnt'],text=True).splitlines())
if sys.argv[1]=='static-events':
 state=json.loads(STATE.read_text());mac=state['macAddress'].lower()
 choices=[p.name for p in Path('/sys/class/net').iterdir() if p.name!='lo' and (p/'address').read_text().strip().lower()==mac]
 assert len(choices)==1
 interface=choices[0];before=facts()
 for reason in ['PREINIT','BOUND','RENEW','REBIND','REBOOT','EXPIRE','FAIL','STOP']:
  env={**os.environ,'reason':reason,'interface':interface,
       'old_ip_address':state['ipAddress'],'old_subnet_mask':'255.255.0.0',
       'new_ip_address':state['ipAddress'],'new_subnet_mask':'255.255.255.0',
       'new_routers':state['gateway'],'new_domain_name_servers':'192.0.2.53'}
  p=run(['/sbin/dhclient-script'],env=env)
  assert p.returncode==0,(reason,p.stderr)
  assert facts()==before,(reason,'network changed')
  print('STATIC_DHCP_EVENT_PRESERVED',reason,flush=True)
 print('PRIMARY_ALIAS_ROUTE_DNS_PRESERVED',flush=True)
elif sys.argv[1]=='fault':
 root=Path('/tmp/e1448-gate-test');root.mkdir(exist_ok=False)
 source=Path('/usr/local/bin/ablestack-storage-boot-reconcile').read_text()
 calls=root/'calls'
 shim=root/'storagectl'
 shim.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> '+str(calls)+'\ncase "$1:$2" in network:endpoints) echo EPIC1448_CONTROLLED_ENDPOINT_FAILURE; exit 1;; *) exit 99;; esac\n')
 shim.chmod(0o700)
 copy,count=re.subn(r'^STORAGECTL=.*$', 'STORAGECTL='+str(shim),source,flags=re.M)
 assert count==1
 private=root/'boot-reconcile';private.write_text(copy);private.chmod(0o700)
 config=[p for base in ['/etc/ganesha','/etc/samba','/etc/ablestack-storage'] for p in Path(base).rglob('*') if p.is_file() and p.suffix in ['.json','.conf']]
 before={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in config}
 ports=listeners()
 p=run([str(private)],env={**os.environ,'ABLESTACK_STORAGE_BOOT_RECONCILE_ATTEMPTS':'1','ABLESTACK_STORAGE_BOOT_RECONCILE_SLEEP':'0','ABLESTACK_STORAGE_BOOT_RECONCILE_LOG':str(root/'boot.log')})
 assert p.returncode==1,(p.returncode,p.stdout,p.stderr)
 assert calls.read_text().splitlines()==['network endpoints reconcile'],calls.read_text()
 assert before=={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in config}
 assert ports==listeners()
 status=json.loads(Path('/run/ablestack-storage/network-status.json').read_text())
 assert status['phase']=='NETWORK_ALIASES' and status['status']=='FAILED'
 print('EXPECTED_ALIAS_FAILURE_RECORDED',status['phase'],flush=True)
 print('PROTOCOL_APPLY_NOT_CALLED_CONFIG_AND_LISTENERS_PRESERVED',flush=True)
else:raise ValueError('unknown controlled test')
