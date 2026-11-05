#!/usr/bin/env python3
# Licensed under the Apache License, Version 2.0. See NOTICE.
"""Actual Samba/systemd in an isolated network; production files and units stay intact."""
import os, sys, json, time, subprocess, tempfile, pathlib, uuid, secrets, pwd, hashlib, importlib.util
P = pathlib.Path
stage = P(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("configuration_transaction", stage / "configuration_transaction.py")
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
fixture = P(tempfile.mkdtemp(prefix="epic1461-legacy-")); fixture.chmod(0o700)
home = P(tempfile.mkdtemp(prefix="epic1461-state-")); home.chmod(0o755)
data = P(tempfile.mkdtemp(prefix="epic1461-data-")); data.chmod(0o770)
nobody = pwd.getpwnam("nobody"); os.chown(data, nobody.pw_uid, nobody.pw_gid)
marker = data / "sentinel"; marker.write_bytes(b"epic1461-implicit-legacy-native\n")
os.chown(marker, nobody.pw_uid, nobody.pw_gid); marker.chmod(0o640)
protected = [P(p) for p in ["/etc/passwd", "/etc/group", "/etc/shadow", "/etc/gshadow",
 "/etc/samba/smb.conf", "/etc/ablestack-storage/desired-state/smb-share-apply.json",
 "/usr/local/lib/ablestack-storage/configuration_transaction.py", "/export/nfs1461/sentinel"]]
def hashes(): return {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in protected if p.exists()}
def livepid(): return subprocess.check_output(["systemctl","show","smbd","-p","MainPID","--value"],text=True).strip()
before, prodpid = hashes(), livepid()
processes=[]; units=[]; holder=None; controller=None
result={"ok":False,"checks":[]}
def run(args, **kw): return subprocess.run(args, check=True, capture_output=True, text=True, timeout=20, **kw)
def wait(test, seconds=10):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if test(): return
  time.sleep(.1)
 raise AssertionError("condition timeout")
def check(name): result["checks"].append(name)
class FixtureProfiles(m.SmbListenerProfiles):
 def __init__(self, namespace, base):
  super().__init__(fixture/"profiles", base, namespace); self.legacy_unit=None; self.generation=0
 def ctl(self,*args):
  if args[0] in ("disable","enable"): return ""
  if args[0]=="start" and args[1] in ("smbd","smbd.service"):
   self.generation+=1; self.legacy_unit="epic1461-probe-legacy-"+str(uuid.uuid4())+".service";units.append(self.legacy_unit)
   run(["systemd-run","--quiet","--collect","--unit="+self.legacy_unit,
        "--property=Type=simple","--property=Restart=no","--property=KillMode=control-group",
        "--property=NetworkNamespacePath="+str(self.namespace),"/usr/sbin/smbd","-F","--no-process-group","--configfile="+str(self.base)])
   wait(lambda:self.legacy_process() is not None)
   return ""
  values=[self.legacy_unit if x in ("smbd","smbd.service") else x for x in args]
  answer=subprocess.run(["systemctl",*values],capture_output=True,text=True,timeout=10)
  if answer.returncode and args[0]=="show":
   return "0" if "--value" in args else "MainPID=0\nControlGroup=\nUnitFileState=transient"
  if answer.returncode: raise RuntimeError(answer.stderr)
  return answer.stdout.strip()
 def raw_pid(self,row):
  if row.get("kind")=="legacy":
   self.validated_unit(row);return int(self.ctl("show","smbd","-p","MainPID","--value") or 0)
  return super().raw_pid(row)
 def implicit_legacy(self,*args,**kw):
  ns=self.namespace;self.namespace=None
  try:return super().implicit_legacy(*args,**kw)
  finally:self.namespace=ns
 def bootstrap(self,*args,**kw):
  ns=self.namespace;self.namespace=None
  try:return super().bootstrap(*args,**kw)
  finally:self.namespace=ns
 def start(self,row,profiles):
  if row.get("kind")=="legacy":
   self.ctl("start","smbd");return
  return super().start(row,profiles)

try:
 holder=subprocess.Popen(["unshare","--net","--","sleep","300"]); processes.append(holder)
 wait(lambda:os.readlink("/proc/%s/ns/net"%holder.pid)!=os.readlink("/proc/self/ns/net"))
 ns=P("/proc/%s/ns/net"%holder.pid)
 def net(args):return ["nsenter","--net="+str(ns),"--",*args]
 for args in [["ip","link","set","lo","up"],["ip","link","add","ens8","type","dummy"],["ip","link","set","ens8","up"],
              ["ip","addr","add","10.144.61.2/24","dev","ens8"]]:run(net(args))
 for name in ["private","lock","state","cache","rpc","pids"]:(home/name).mkdir(mode=0o700 if name=="private" else 0o755)
 base=fixture/"base.conf"
 base.write_text("\n".join(["[global]","security = user","workgroup = WORKGROUP","smb ports = 24461",
  "netbios name = EPIC1461","server min protocol = SMB2","load printers = no","printing = bsd",
  "disable spoolss = yes","log file = "+str(fixture/"log.%m"),"max log size = 128",
  "pid directory = "+str(home/"pids"),"private dir = "+str(home/"private"),
  "passdb backend = tdbsam:"+str(home/"private/passdb.tdb"),"lock directory = "+str(home/"lock"),
  "state directory = "+str(home/"state"),"cache directory = "+str(home/"cache"),
  "ncalrpc dir = "+str(home/"rpc"),"[probe]","path = "+str(data),"read only = no",
  "guest ok = no","valid users = nobody"])+"\n");base.chmod(0o600)
 password=secrets.token_urlsafe(24);run(["smbpasswd","-s","-a","nobody","-c",str(base)],input=password+"\n"+password+"\n")
 controller=FixtureProfiles(ns,base);instance=str(uuid.uuid4())
 old={"instanceUuid":instance,"enabled":True,"listenerOnly":True,"shares":[],"listeners":[]}
 m.atomic_json(controller.desired,old);controller.ctl("start","smbd")
 wait(lambda:controller.legacy_process() and controller.legacy_sockets(controller.legacy_process()["pid"]))
 evidence=controller.implicit_legacy(old)
 assert evidence and evidence["sockets"] and evidence["port"]==24461
 check("real_implicit_default_identity_ipv4_ipv6_and_protected_configuration")
 bad={**old,"instanceUuid":str(uuid.uuid4())}
 try:controller.implicit_legacy(bad);raise AssertionError("foreign instance accepted")
 except m.Rejected as e:assert e.code=="SMB_LEGACY_IDENTITY_UNCONFIRMED"
 check("foreign_instance_rejected_before_any_service_effect")
 env={**os.environ,"PASSWD":password}
 log=(fixture/"held.log").open("w")
 client=subprocess.Popen(net(["smbclient","//10.144.61.2/probe","-p","24461","-U","nobody","-s",str(base)]),
  stdin=subprocess.PIPE,stdout=log,stderr=log,text=True,env=env);processes.append(client)
 wait(lambda:controller.implicit_connections(evidence)>0)
 endpoint={"id":str(uuid.uuid4()),"listenIp":"10.144.61.2","port":24461}
 new={**old,"listeners":[endpoint],"listenerImplicitBefore":evidence};op=str(uuid.uuid4());pid=evidence["process"]["pid"]
 try:controller.stage(new,old,op);raise AssertionError("active SMB session bypassed drain")
 except m.Rejected as e:assert e.code=="SMB_LEGACY_DRAIN_REQUIRED",e.code
 assert controller.legacy_process()["pid"]==pid and not controller.manifest.exists()
 check("real_authenticated_session_blocks_handoff_without_stopping_or_replaying")
 client.stdin.write("quit\n");client.stdin.flush();client.wait(timeout=10);log.close()
 wait(lambda:controller.implicit_connections(evidence)==0)
 snapshot={"files":{},"smbLegacy":{**evidence,"stopAllowed":True},"smbListeners":{"observedEpoch":time.time()}}
 for p in [base,controller.desired]:
  import base64
  st=p.stat();snapshot["files"][str(p)]={"content":base64.b64encode(p.read_bytes()).decode(),"uid":st.st_uid,"gid":st.st_gid,"mode":st.st_mode&0o777}
 real_stop=controller.stop
 def interrupted_stop(row):
  controller.stop=real_stop
  raise RuntimeError("controlled interruption after durable stop intent")
 controller.stop=interrupted_stop
 try:controller.stage(new,old,op);raise AssertionError("interruption not injected")
 except RuntimeError:pass
 assert controller.legacy_process()["pid"]==pid
 controller.stage(new,old,op)
 check("interruption_after_stop_intent_reconciles_same_operation")
 assert controller.legacy_process() is None
 assert controller.show()["listeners"][-1]["state"] in ("READY","UNKNOWN")
 def access(label):
  target=fixture/label
  run(net(["smbclient","//10.144.61.2/probe","-p","24461","-U","nobody","-s",str(base),"-c","get sentinel "+str(target)]),env=env)
  assert target.read_bytes()==marker.read_bytes()
 access("staged")
 check("same_port_legacy_to_managed_handoff_and_authenticated_read")
 controller.rollback(op)
 controller.verify_legacy_restored(old,snapshot)
 assert not controller.manifest.exists();access("restored")
 check("rollback_restores_owned_legacy_binding_and_manifest_absence")
 new={**new,"listenerImplicitBefore":controller.implicit_legacy(old)}
 controller.stage(new,old,str(uuid.uuid4()));pending=m.load(controller.manifest)["pending"]["operationid"]
 controller.commit(pending);access("committed")
 assert controller.legacy_process() is None
 check("commit_keeps_explicit_managed_listener_and_data_permissions")
 controller.stop_all();controller.boot(new);access("booted")
 check("managed_boot_reconciliation_without_duplicate_default_listener")
 assert marker.stat().st_uid==nobody.pw_uid and marker.stat().st_mode&0o777==0o640
 assert hashes()==before and livepid()==prodpid
 check("production_configuration_process_and_nfs_data_unchanged")
 result["ok"]=True
finally:
 if controller:
  try:controller.stop_all()
  except Exception as error:result["cleanupError"]=type(error).__name__
 for unit in units:subprocess.run(["systemctl","stop",unit],capture_output=True,timeout=10)
 for process in processes:
  if process.poll() is None:
   process.terminate()
   try:process.wait(timeout=5)
   except subprocess.TimeoutExpired:process.kill();process.wait()
 result["protectedUnchanged"]=hashes()==before and livepid()==prodpid
 (stage/"legacy-native-result.json").write_text(json.dumps(result,indent=2))
 print(json.dumps(result))
