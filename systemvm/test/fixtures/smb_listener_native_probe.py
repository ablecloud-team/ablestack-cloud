# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file distributed
# with this work for additional information regarding copyright ownership.
# The ASF licenses this file under the Apache License, Version 2.0
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS.
#!/usr/bin/env python3
"""Temporary controller injection: real Samba/systemd, isolated network, no production config changes."""
import os,sys,json,time,subprocess,tempfile,pathlib,uuid,secrets,pwd,hashlib,shutil,traceback,importlib.util
P=pathlib.Path
stage=P(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("txn",stage/"configuration_transaction.py")
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
fixture=P(tempfile.mkdtemp(prefix="epic1449-controller-"));os.chmod(fixture,0o700)
statehome=P(tempfile.mkdtemp(prefix="epic1449-state-"));os.chmod(statehome,0o755)
data=P(tempfile.mkdtemp(prefix="epic1449-public-"));owner=pwd.getpwnam("nobody")
os.chown(data,owner.pw_uid,owner.pw_gid);os.chmod(data,0o770)
marker=b"epic1449-controller-auth-session-data\n";f=data/"marker.txt";f.write_bytes(marker);os.chown(f,owner.pw_uid,owner.pw_gid);os.chmod(f,0o660)
processes=[];handles=[];holder=None;c=None;result={"ok":False,"checks":[]}
protected=[P(x) for x in ["/etc/passwd","/etc/group","/etc/shadow","/etc/gshadow","/etc/samba/smb.conf","/var/lib/samba/private/passdb.tdb","/usr/local/bin/ablestack-storagectl","/usr/local/lib/ablestack-storage/configuration_transaction.py","/export/epic1438-1448-static-20261026-nfs/epic1448-preserve.bin"]]
def hashes():return {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in protected if p.exists()}
def prodpid():return subprocess.check_output(["systemctl","show","smbd","-p","MainPID","--value"],text=True).strip()
before=hashes();beforepid=prodpid()
def run(args,**kw):return subprocess.run(args,check=True,capture_output=True,text=True,timeout=15,**kw)
def nsargs(args):return ["nsenter","--net="+str(c.namespace),"--"]+args
def wait(test,seconds=5):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if test():return
  time.sleep(.1)
 raise AssertionError("condition timeout")
def check(label):result["checks"].append(label)
def payload(rows):return {"instanceUuid":instance,"listenerOnly":True,"shares":[],"listeners":rows}
def stagecommit(rows):
 op=str(uuid.uuid4());v=payload(rows);c.stage(v,{},op);c.commit(op);return v
def heldread(client,label):
 dest=fixture/label;client.stdin.write("get marker.txt "+str(dest)+"\n");client.stdin.flush()
 wait(lambda:dest.exists());assert dest.read_bytes()==marker and client.poll() is None
def connect(row):
 log=(fixture/(row["id"]+".client.log")).open("w");handles.append(log)
 client=subprocess.Popen(nsargs(["smbclient","//"+row["listenIp"]+"/probe","-p",str(row["port"]),"-U","nobody","-s",str(base)]),stdin=subprocess.PIPE,stdout=log,stderr=log,text=True,env={**os.environ,"PASSWD":password});processes.append(client);return client
try:
 holder=subprocess.Popen(["unshare","--net","--","sleep","240"]);processes.append(holder)
 wait(lambda:os.readlink("/proc/%d/ns/net"%holder.pid)!=os.readlink("/proc/self/ns/net"))
 c=m.SmbListenerProfiles(fixture/"profiles",fixture/"base.conf",P("/proc/%d/ns/net"%holder.pid))
 for cmd in [["ip","link","set","lo","up"],["ip","link","add","ens8","type","dummy"],["ip","link","set","ens8","up"]]+[["ip","addr","add",ip+"/24","dev","ens8"] for ip in ["10.144.9.2","10.144.9.3","10.144.9.4"]]:run(nsargs(cmd))
 for d in ["private","lock","state","cache","rpc"]:(statehome/d).mkdir(mode=0o700 if d=="private" else 0o755)
 base=fixture/"base.conf"
 lines=["[global]","security = user","workgroup = WORKGROUP","netbios name = EPIC1449","server min protocol = SMB2","load printers = no","printing = bsd","disable spoolss = yes","log file = "+str(fixture/"log.%m"),"max log size = 128",
 "private dir = "+str(statehome/"private"),"passdb backend = tdbsam:"+str(statehome/"private/passdb.tdb"),"lock directory = "+str(statehome/"lock"),"state directory = "+str(statehome/"state"),"cache directory = "+str(statehome/"cache"),"ncalrpc dir = "+str(statehome/"rpc"),
 "[probe]","path = "+str(data),"read only = no","guest ok = no","valid users = nobody"]
 base.write_text("\n".join(lines)+"\n");os.chmod(base,0o600)
 password=secrets.token_urlsafe(24)
 run(["smbpasswd","-s","-a","nobody","-c",str(base)],input=password+"\n"+password+"\n")
 instance=str(uuid.uuid4())
 a={"id":str(uuid.uuid4()),"listenIp":"10.144.9.2","port":24445}
 b={"id":str(uuid.uuid4()),"listenIp":"10.144.9.3","port":24445}
 v=stagecommit([a]);pa=c.pid(m.load(c.manifest)["profiles"][c.key(a)])
 assert pa;held=connect(a);heldread(held,"before");check("real_systemd_owned_pid_and_local_auth")
 v2=stagecommit([a,b]);heldread(held,"after-add");bc=connect(b);heldread(bc,"second-auth");check("add_exact_tuple_preserves_authenticated_session")
 stagecommit([a,b]);assert c.pid(m.load(c.manifest)["profiles"][c.key(a)])==pa;check("duplicate_reapply_same_pid")
 blocker=subprocess.Popen(nsargs(["python3","-c","import socket,time;s=socket.socket();s.bind(('10.144.9.4',24447));s.listen(1);time.sleep(60)"]));processes.append(blocker)
 wait(lambda:("10.144.9.4",24447) in c.sockets(namespace_pid=holder.pid))
 bad={"id":str(uuid.uuid4()),"listenIp":"10.144.9.4","port":24447};op=str(uuid.uuid4())
 try:c.stage(payload([a,b,bad]),v2,op);raise AssertionError("conflict not rejected")
 except m.Rejected as e:assert str(e)=="SMB_ENDPOINT_IN_USE",str(e)
 c.rollback(op);heldread(held,"after-failure");heldread(bc,"second-after-failure");check("bind_failure_rollback_preserves_both_authenticated_sessions")
 blocker.terminate();blocker.wait(timeout=3)
 changed={**b,"listenIp":"10.144.9.4","port":24446};op=str(uuid.uuid4());c.stage(payload([a,changed]),v2,op)
 heldread(bc,"old-generation-before-commit");c.commit(op);heldread(held,"after-edit")
 sockets=c.sockets(namespace_pid=holder.pid)
 assert ("10.144.9.3",24445) not in sockets and ("10.144.9.4",24446) in sockets
 assert ("10.144.9.2",24446) not in sockets and ("10.144.9.4",24445) not in sockets
 check("stable_uuid_edit_and_no_cartesian_bindings")
 env={**os.environ,"PASSWD":password,"SMB_A":a["listenIp"],"SMB_B":changed["listenIp"],"SMB_PORT_A":str(a["port"]),"SMB_PORT_B":str(changed["port"])}
 lock=run(nsargs(["python3",str(stage/"smb_listener_lock_probe.py")]),env=env);result["locking"]=json.loads(lock.stdout);assert result["locking"]["ok"];check("SMB2_3_cross_profile_share_mode_lock")
 op=str(uuid.uuid4());c.stage(payload([a]),payload([a,changed]),op)
 assert ("10.144.9.4",24446) in c.sockets(namespace_pid=holder.pid)
 c.commit(op);heldread(held,"after-delete");assert ("10.144.9.4",24446) not in c.sockets(namespace_pid=holder.pid);check("delete_finalizes_only_target_after_commit")
 # Alias ownership/release uses only this namespace and a private network manifest.
 original=json.loads(run(nsargs(["ip","-j","addr","show"])).stdout)
 link=next(row for row in original if row["ifname"]=="ens8")
 alias={"id":str(uuid.uuid4()),"listenIp":"10.144.9.5","port":24448}
 run(nsargs(["ip","addr","add","10.144.9.5/24","dev","ens8"]))
 m.atomic_json(fixture/"network-endpoints.json",{"endpoints":[{"listenIp":"10.144.9.5","macAddress":link["address"],"prefixlen":24}]})
 original_checked=m.checked
 def checked_in_namespace(args,*args2,**kw):
  return original_checked(nsargs(args) if args[0]=="ip" else args,*args2,**kw)
 m.checked=checked_in_namespace
 op=str(uuid.uuid4());c.stage(payload([a,alias]),payload([a]),op,original);c.commit(op)
 assert "10.144.9.5" in m.load(c.manifest)["ownedAliases"];check("new_secondary_ownership_proved_by_before_image")
 op=str(uuid.uuid4());remove=payload([a]);remove["releaseAddresses"]=["10.144.9.5"];c.stage(remove,payload([a,alias]),op);c.commit(op)
 assert not any(addr["local"]=="10.144.9.5" for row in json.loads(run(nsargs(["ip","-j","-4","addr","show"])).stdout) for addr in row.get("addr_info",[]))
 assert not m.load(fixture/"network-endpoints.json")["endpoints"]
 assert "releaseAddresses" not in m.load(c.desired);heldread(held,"after-alias-release");check("only_owned_unreferenced_alias_released_and_not_replayed")
 m.checked=original_checked
 c.stop_all();c.boot(payload([a]));reconnected=connect(a);heldread(reconnected,"after-boot");check("canonical_manifest_boot_recreates_listener")
 assert hashes()==before and prodpid()==beforepid
 result.update(ok=True,productionHashesUnchanged=True,productionPidUnchanged=True,dataSha256=hashlib.sha256(f.read_bytes()).hexdigest(),mode=oct(f.stat().st_mode&0o777),uid=f.stat().st_uid)

except Exception as error:
 result["error"]=traceback.format_exc()
 if isinstance(error,subprocess.CalledProcessError):
  result["childStdout"]=error.stdout
  result["childStderr"]=error.stderr
 if c:
  result["observation"]=c.show()
  result["units"]=[subprocess.run(["systemctl","status",row["unit"],"--no-pager"],capture_output=True,text=True).stdout[-3500:] for row in m.load(c.manifest,{}).get("profiles",{}).values() if row.get("kind")=="profile"]
finally:
 for client in reversed(processes):
  if client is not holder and client.poll() is None:client.terminate()
 # Cleanup only units whose ExecStart proves this exact private fixture.
 if c:
  for row in m.load(c.manifest,{}).get("profiles",{}).values():
   unit=row.get("unit","")
   if row.get("kind")!="profile" or unit!="ablestack-smb-"+c.key(row)+".service":continue
   show=subprocess.run(["systemctl","show",unit,"-p","ExecStart"],capture_output=True,text=True)
   if str(fixture)+"/profiles/" in show.stdout and "/usr/sbin/smbd" in show.stdout:
    subprocess.run(["systemctl","stop",unit],capture_output=True,timeout=10)
 if holder and holder.poll() is None:holder.terminate()
 for p in processes:
  try:p.wait(timeout=3)
  except subprocess.TimeoutExpired:p.kill();p.wait()
 for h in handles:h.close()
 result["protectedStateAfterCleanupUnchanged"]=hashes()==before and prodpid()==beforepid
 (stage/"native-controller-result.json").write_text(json.dumps(result,indent=2))
 shutil.rmtree(fixture);shutil.rmtree(data);shutil.rmtree(statehome)
 print(json.dumps(result),flush=True)
