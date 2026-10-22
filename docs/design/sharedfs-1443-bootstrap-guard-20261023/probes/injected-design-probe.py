import os,pathlib,subprocess,json,time,hashlib,threading,http.server,traceback,shlex
ROOT=pathlib.Path('/run/epic1443-guard-design-probe')
PREFIX='epic1443-guard-design-'
SOURCE_GUARD='sharedfs_boot_state=$(systemctl show cloud-early-config.service -p ActiveState -p SubState -p Result); if ! printf \'%s\\n\' "$sharedfs_boot_state" | grep -qx \'ActiveState=active\' || ! printf \'%s\\n\' "$sharedfs_boot_state" | grep -qx \'SubState=exited\' || ! printf \'%s\\n\' "$sharedfs_boot_state" | grep -qx \'Result=success\'; then echo \'SHAREDFS_BOOTSTRAP_PENDING: waiting for guest network initialization\' >&2; exit 75; fi; '
PATCH_FUNC='patch_sshd_config() {\n  if `! ssh -Q PubkeyAcceptedAlgorithms >/dev/null 2>&1` && `grep ^PubkeyAcceptedAlgorithms /etc/ssh/sshd_config >/dev/null`; then\n      # "PubkeyAcceptedAlgorithms=+ssh-rsa" is added to /etc/ssh/sshd_config in 4.20.0 systemvm template\n      # However, it is not supported in old systemvm templates\n      # If the system vm is created from an old systemvm template, remove it from /etc/ssh/sshd_config\n      # No need to restart ssh if it is running well\n      log_it "Removing PubkeyAcceptedAlgorithms=+ssh-rsa from /etc/ssh/sshd_config as it is not supported"\n      sed -i "/PubkeyAcceptedAlgorithms=+ssh-rsa/d" /etc/ssh/sshd_config\n      if ! systemctl is-active ssh > /dev/null; then\n        systemctl restart ssh\n      fi\n  elif `ssh -Q PubkeyAcceptedAlgorithms >/dev/null 2>&1` && `! grep ^PubkeyAcceptedAlgorithms /etc/ssh/sshd_config >/dev/null`; then\n      log_it "Adding PubkeyAcceptedAlgorithms=+ssh-rsa to sshd_config"\n      sed -i "/PubkeyAuthentication yes/aPubkeyAcceptedAlgorithms=+ssh-rsa" /etc/ssh/sshd_config\n      systemctl restart ssh\n  fi\n}\n'
CLOUD_BLOCK='    log_it "Starting cloud-init services"\n    if [ -f /home/cloud/success ]; then\n      systemctl stop cloud-init cloud-config cloud-final\n      systemctl disable cloud-init cloud-config cloud-final\n    else\n      systemctl start --no-block cloud-init\n      systemctl start --no-block cloud-config\n      systemctl start --no-block cloud-final\n    fi'
REAL_UNITS=['cloud-preinit.service','cloud-early-config.service','ssh.service','cloud-init.service','cloud-init-local.service','cloud-config.service','cloud-final.service','ablestack-sharedfs-network.service']
FILES=['/etc/passwd','/etc/group','/etc/shadow','/etc/gshadow','/etc/ssh/sshd_config','/etc/fstab','/etc/resolv.conf','/opt/cloud/bin/setup/sharedfsvm.sh','/opt/cloud/bin/setup/patch.sh','/usr/local/sbin/ablestack-sharedfs-network','/etc/ablestack-storage/sharedfs-network.json']
def run(a,timeout=30,check=True,env=None):
 p=subprocess.run(a,text=True,capture_output=True,timeout=timeout,env=env)
 if check and p.returncode:raise RuntimeError(str(a[:3])+': '+p.stderr[-1500:])
 return p
def props(name):
 p=run(['systemctl','show',name,'-p','Id','-p','LoadState','-p','ActiveState','-p','SubState','-p','Result','-p','MainPID','-p','ExecMainStartTimestampMonotonic','-p','ExecMainExitTimestampMonotonic'],check=False)
 return dict(l.split('=',1) for l in p.stdout.splitlines() if '=' in l)
def snapshot():
 files={p:hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() if pathlib.Path(p).exists() else None for p in FILES}
 return {'files':files,'units':{u:props(u) for u in REAL_UNITS},'addr':json.loads(run(['ip','-j','-4','addr']).stdout),'route':json.loads(run(['ip','-j','-4','route']).stdout),'mounts':run(['findmnt','--json','-o','SOURCE,TARGET,FSTYPE,UUID']).stdout}
def save():
 tmp=ROOT/'result.tmp';tmp.write_text(json.dumps(out,indent=2));tmp.replace(ROOT/'result.json')
def new_guard(early,meta):
 # Design prototype only: no production module or real unit is changed.
 e=props(early);m=props(meta)
 base=(e.get('ActiveState'),e.get('SubState'),e.get('Result'))==('active','exited','success')
 netdone=m.get('LoadState')=='not-found' or (m.get('ActiveState') not in ('activating','deactivating') and m.get('SubState') not in ('running','start','start-pre','start-post'))
 if e.get('ActiveState')=='failed':return 'FAILED'
 return 'READY' if base and netdone else 'PENDING'
def install_case(case,max_wait,has_alg=False):
 names={x:PREFIX+case+'-'+x+'.service' for x in ['early','meta','ssh','config','final']}
 cfg=ROOT/(case+'-sshd.conf');cfg.write_text('PubkeyAuthentication yes\n'+('PubkeyAcceptedAlgorithms=+ssh-rsa\n' if has_alg else ''))
 func=PATCH_FUNC.replace('/etc/ssh/sshd_config',str(cfg)).replace('systemctl is-active ssh','systemctl is-active '+names['ssh']).replace('systemctl restart ssh','systemctl restart '+names['ssh'])
 block=CLOUD_BLOCK.replace('/home/cloud/success',str(ROOT/(case+'-absent-success')))
 for a,b in [('cloud-init',names['meta']),('cloud-config',names['config']),('cloud-final',names['final'])]:block=block.replace(a,b)
 early=ROOT/(case+'-early.sh')
 early.write_text('#!/bin/bash\nset -x\nlog_it(){ printf "%s\\n" "$*"; };\n'+func+'\n'+block+'\npatch_sshd_config\n')
 meta=ROOT/(case+'-metadata.py')
 meta.write_text("""import http.server,threading,time,json,pathlib,sys
from cloudinit import url_helper
class Handler(http.server.BaseHTTPRequestHandler):
 def do_GET(self):
  self.send_response(404);self.end_headers();self.wfile.write(b'not metadata')
 def log_message(self,*args):pass
server=http.server.HTTPServer(('127.0.0.1',0),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
t=time.monotonic();messages=[]
url='http://127.0.0.1:'+str(server.server_port)+'/latest/meta-data/instance-id'
answer=url_helper.wait_for_url([url],max_wait=MAX_WAIT,timeout=1,status_cb=messages.append)
pathlib.Path(RESULT_PATH).write_text(json.dumps({'elapsed':time.monotonic()-t,'httpStatus':404,'result':answer,'messages':messages}))
sys.exit(1 if answer==(False,None) else 0)
""".replace('MAX_WAIT',repr(max_wait)).replace('RESULT_PATH',repr(str(ROOT/(case+'-metadata-result.json')))))
 texts={}
 texts['meta']='[Unit]\nDescription=Isolated actual cloud-init URL wait\nBefore='+names['ssh']+'\nWants='+names['ssh']+'\n[Service]\nType=oneshot\nPrivateNetwork=yes\nRemainAfterExit=yes\nTimeoutStartSec=160\nExecStart=/usr/bin/python3 '+str(meta)+'\n'
 texts['ssh']='[Unit]\nDescription=Isolated SSH ordering witness; no real SSH\nAfter='+names['meta']+'\n[Service]\nType=oneshot\nRemainAfterExit=yes\nExecStart=/bin/true\n'
 texts['early']='[Unit]\nDescription=Isolated SharedFS tail; no real bootstrap\n[Service]\nType=oneshot\nRemainAfterExit=yes\nTimeoutStartSec=180\nExecStart=/bin/bash '+str(early)+'\n'
 for k in ['config','final']:texts[k]='[Unit]\nDescription=Isolated no-op cloud stage\n[Service]\nType=oneshot\nRemainAfterExit=yes\nExecStart=/bin/true\n'
 for k,n in names.items():
  p=pathlib.Path('/run/systemd/system')/n
  assert not p.exists(),str(p);p.write_text(texts[k]);created.append(n)
 run(['systemctl','daemon-reload'])
 run(['systemctl','start',names['ssh']])
 run(['systemctl','start','--no-block',names['early']])
 return names
ROOT.mkdir(mode=0o700,exist_ok=True);assert not (ROOT/'result.json').exists()
out={'status':'RUNNING','designOnly':True,'vmUuid':'a14c8a94-8c5c-3675-b036-652cbb92d15a','before':snapshot(),'cases':{}}
created=[]
save()
try:
 names=install_case('firstboot',120,False)
 t=time.monotonic();old=[];guard=SOURCE_GUARD.replace('cloud-early-config.service',names['early'])
 for i in range(30):
  p=run(['bash','-c',guard],check=False)
  old.append({'attempt':i+1,'elapsed':round(time.monotonic()-t,3),'rc':p.returncode,'early':props(names['early']),'metadata':props(names['meta'])})
  # Match the observed host QGA one-second status polling plus existing two-second retry interval.
  time.sleep(1)
  if p.returncode==0:break
  if i<29:time.sleep(2)
 out['cases']['firstboot']={'old30AttemptLoop':old,'oldSucceeded':old[-1]['rc']==0,'elapsedOld':time.monotonic()-t}
 save()
 newobs=[]
 while time.monotonic()-t<360:
  verdict=new_guard(names['early'],names['meta'])
  newobs.append({'elapsed':round(time.monotonic()-t,3),'verdict':verdict,'early':props(names['early']),'metadata':props(names['meta'])})
  if verdict!='PENDING':break
  time.sleep(2)
 out['cases']['firstboot'].update(prototypeObservations=newobs,prototypeSucceeded=newobs[-1]['verdict']=='READY',elapsedPrototype=time.monotonic()-t)
 out['cases']['firstboot']['metadataResult']=json.loads((ROOT/'firstboot-metadata-result.json').read_text())
 out['cases']['firstboot']['originalGuardAfterWait']=run(['bash','-c',guard],check=False).returncode
 out['cases']['firstboot']['journal']=run(['journalctl','-u',names['early'],'-u',names['meta'],'-u',names['ssh'],'--no-pager','-o','short-monotonic','-n','180']).stdout
 save()
 names=install_case('configured-reboot',6,True)
 t=time.monotonic();guard=SOURCE_GUARD.replace('cloud-early-config.service',names['early'])
 # Wait until the isolated early script has launched the metadata stage.
 for i in range(150):
  if props(names['early']).get('SubState')=='exited':break
  time.sleep(.02)
 p=run(['bash','-c',guard],check=False)
 oldverdict={'rc':p.returncode,'early':props(names['early']),'metadata':props(names['meta']),'elapsed':time.monotonic()-t}
 newobs=[]
 while time.monotonic()-t<20:
  verdict=new_guard(names['early'],names['meta']);newobs.append({'elapsed':time.monotonic()-t,'verdict':verdict,'metadata':props(names['meta'])})
  if verdict!='PENDING':break
  time.sleep(.2)
 out['cases']['configuredReboot']={'oldGuard':oldverdict,'prototype':newobs,'metadataResult':json.loads((ROOT/'configured-reboot-metadata-result.json').read_text()),'prototypeSucceeded':newobs[-1]['verdict']=='READY'}
 # Terminal unit failure is not treated as perpetual pending in the prototype.
 failed=PREFIX+'terminal.service';pp=pathlib.Path('/run/systemd/system')/failed
 assert not pp.exists();pp.write_text('[Service]\nType=oneshot\nExecStart=/bin/false\n');created.append(failed)
 run(['systemctl','daemon-reload']);run(['systemctl','start',failed],check=False)
 out['cases']['terminalFailure']={'oldRc':run(['bash','-c',SOURCE_GUARD.replace('cloud-early-config.service',failed)],check=False).returncode,'newVerdict':new_guard(failed,names['meta'])}
 out['status']='PASS'
 assert out['cases']['firstboot']['oldSucceeded'] is False
 assert out['cases']['firstboot']['prototypeSucceeded'] is True
 assert out['cases']['configuredReboot']['oldGuard']['rc']==0
 assert out['cases']['configuredReboot']['oldGuard']['metadata']['ActiveState']=='activating'
 assert out['cases']['terminalFailure']['newVerdict']=='FAILED'
except Exception:
 out['status']='PROBE_ERROR';out['error']=traceback.format_exc()
finally:
 for n in reversed(created):run(['systemctl','stop',n],check=False)
 for n in created:
  p=pathlib.Path('/run/systemd/system')/n
  if p.exists():p.unlink()
 if created:run(['systemctl','daemon-reload']);run(['systemctl','reset-failed']+created,check=False)
 out['after']=snapshot()
 out['unchanged']={k:out['before'][k]==out['after'][k] for k in ['files','units','addr','route','mounts']}
 out['testUnitsRemoved']=all(not (pathlib.Path('/run/systemd/system')/n).exists() for n in created)
 save()
