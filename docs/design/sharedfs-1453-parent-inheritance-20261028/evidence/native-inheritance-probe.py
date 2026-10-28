#!/usr/bin/env python3
"""#1453 design probe only. Never installs or patches production runtime.
Reuses pinned native fixture scaffolding from #1452 with private #1453 paths.
"""
import os,sys,json,stat,hashlib,subprocess,signal,time,uuid,base64,zlib,ast,copy,secrets
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
BASE_SOURCE = "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"
base={"__name__":"_native_fixture"}
exec(compile(zlib.decompress(base64.b64decode(BASE_SOURCE)).decode().replace("epic1452","epic1453"),"<pinned-native-fixture>","exec"),base)
command=base["command"]
Probe=base["Probe"]

def info(path):
 s=path.stat()
 return {"uid":s.st_uid,"gid":s.st_gid,"mode":format(stat.S_IMODE(s.st_mode),"04o"),"inode":s.st_ino,"device":s.st_dev,
         **({"sha256":hashlib.sha256(path.read_bytes()).hexdigest()} if path.is_file() else {})}

def guard(config, mode):
 enabled=config.get("inheritOwner",False)
 if not isinstance(enabled,bool): raise ValueError("SMB_INHERIT_OWNER_INVALID")
 if (enabled or mode&0o2000) and any(str(config.get(k) or "").strip() for k in ("forceUser","forceGroup")):
  raise ValueError("SMB_PARENT_IDENTITY_CONFLICT")

class DesignProbe(Probe):
 def __init__(self,args):
  super().__init__(args)
  self.result["kind"]="#1453 design only: real installed Samba and common POSIX engine in private namespaces"
  self.result["scaffoldSourceSha256"]=hashlib.sha256(zlib.decompress(base64.b64decode(BASE_SOURCE))).hexdigest()
  self.parents={}
  self.secret=secrets.token_urlsafe(30)
  self.other_secret=secrets.token_urlsafe(30)
 def protected(self):
  value=super().protected()
  value["accounts"]={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in map(Path,["/etc/passwd","/etc/group","/etc/shadow","/etc/gshadow"])}
  value["desired"]={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in Path("/etc/ablestack-storage/desired-state").rglob("*") if p.is_file()}
  return value
 def stop_smb(self):
  p=self.smb_process
  if p.poll() is None:
   os.killpg(p.pid,signal.SIGTERM)
   try:p.wait(timeout=8)
   except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
 def setup(self):
  conf,other=super().setup()
  self.smb_process=self.children[-1]
  self.stop_smb()
  self.smbconf=self.control/"smb.conf"
  self.global_conf=self.smbconf.read_text().split("[common]",1)[0].replace("map to guest=Bad User","map to guest=Never")
  src=Path("/usr/local/bin/ablestack-storagectl").read_text()
  body=src.split("apply_smb_shares() {",1)[1].split("<<'PY'\n",1)[1].split("\nPY\n",1)[0]
  tree=ast.parse(body)
  self.truth=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=="truth")
  self.render=next(n for n in ast.walk(tree) if isinstance(n,ast.Expr) and isinstance(n.value,ast.Call)
   and isinstance(n.value.func,ast.Attribute) and n.value.func.attr=="extend"
   and "create mask = 0660" in ast.get_source_segment(body,n))
  self.smbconf.write_text(self.global_conf+"\n[authsetup]\npath="+str(self.shared)+"\nguest ok=no\nread only=yes\n")
  for user,password in (("epic1452user",self.secret),("daemon",self.other_secret)):
   q=subprocess.run(["smbpasswd","-c",str(self.smbconf),"-a","-s",user],input=password+"\n"+password+"\n",capture_output=True,text=True,timeout=15)
   assert q.returncode==0,q.stderr
  command(["setfacl","-m","u:1001:rwx",str(self.shared)])
  command(["setfacl","-d","-m","u:1001:rwx",str(self.shared)])
  self.source=self.control/"input.txt";self.source.write_bytes(b"epic1453-inherit-io")
  self.restart_smb([{"name":"common","path":self.shared,"inheritOwner":False}])
  return conf,other
 def emit(self,row):
  config={"readOnly":row.get("readOnly",False),"browseable":True}
  scope={"lines":[],"name":row["name"],"path":str(row["path"]),"config":config,"guest_ok":False}
  exec(compile(ast.fix_missing_locations(ast.Module(body=[copy.deepcopy(self.truth),copy.deepcopy(self.render)],type_ignores=[])),"<installed-smb-renderer-extract>","exec"),scope)
  mode=stat.S_IMODE(row["path"].stat().st_mode)
  guard(row,mode)
  if "inheritOwner" in row:
   scope["lines"].append("   inherit owner = "+("yes" if row["inheritOwner"] else "no"))
  scope["lines"].append('   valid users = "epic1452user"')
  if not row.get("readOnly"):scope["lines"].append('   write list = "epic1452user"')
  return scope["lines"]
 def restart_smb(self,rows):
  self.stop_smb()
  lines=[]
  for row in rows:lines+=self.emit(row)
  self.smbconf.write_text(self.global_conf+"\n"+"\n".join(lines)+"\n")
  command(["testparm","-s",str(self.smbconf)])
  out=(self.control/"smb-private-output.log").open("a");self.logs.append(out)
  self.smb_process=subprocess.Popen(["ip","netns","exec",self.ns,"smbd","--foreground","--no-process-group","--configfile",str(self.smbconf)],stdout=out,stderr=subprocess.STDOUT,start_new_session=True)
  self.children.append(self.smb_process);self.wait(23045)
 def smb(self,share,commands,user="epic1452user",denied=False):
  q=subprocess.run(["ip","netns","exec",self.ns,"smbclient","//127.0.0.1/"+share,"-p","23045","-U",user,"-m","SMB3","-c",commands],
    env={**os.environ,"PASSWD":self.secret if user=="epic1452user" else self.other_secret},capture_output=True,text=True,timeout=25)
  if denied:
   assert q.returncode!=0 and any(s in q.stdout+q.stderr for s in ["NT_STATUS_ACCESS_DENIED","NT_STATUS_MEDIA_WRITE_PROTECTED"]),q.stdout+q.stderr
   return {"rejected":True,"status":"ACCESS_DENIED"}
  assert q.returncode==0,commands+": "+q.stdout+q.stderr
  return q.stdout
 def parent(self,name,uid,gid,setgid):
  p=self.shared/name;p.mkdir();os.chown(p,uid,gid)
  command(["setfacl","-m","u:1001:rwx",str(p)]);command(["setfacl","-d","-m","u:1001:rwx",str(p)])
  p.chmod(0o2770 if setgid else 0o770)
  seed=p/"seed.txt";seed.write_bytes(b"epic1453-seed");os.chown(seed,1001,1001);seed.chmod(0o640)
  return p
 def exercise(self,conf,other):
  matrix=[]
  for label,uid,gid in (("a",0,65534),("b",1,1)):
   for inherit in (False,True):
    for setgid in (False,True):
     name=label+("_owner" if inherit else "_creator")+("_sgid" if setgid else "_plain")
     p=self.parent(name,uid,gid,setgid);self.parents[name]=p
     before=info(p);seed=info(p/"seed.txt")
     self.restart_smb([{"name":"matrix","path":p,"inheritOwner":inherit}])
     self.smb("matrix","put "+str(self.source)+" made.txt; mkdir child; cd child; put "+str(self.source)+" nested.txt; mkdir grand; cd grand; put "+str(self.source)+" deep.txt")
     observed={s:info(p/s) for s in ("made.txt","child","child/nested.txt","child/grand","child/grand/deep.txt")}
     expected_uid=uid if inherit else 1001
     expected_gid=gid if setgid else 1001
     assert all(v["uid"]==expected_uid and v["gid"]==expected_gid for v in observed.values()),{"case":name,"observed":observed,"expectedUid":expected_uid,"expectedGid":expected_gid}
     assert all((int(v["mode"],8)&0o2000)==(0o2000 if setgid else 0) for s,v in observed.items() if "sha256" not in v),observed
     assert info(p)==before and info(p/"seed.txt")==seed
     matrix.append({"case":name,"parent":before,"inheritOwner":inherit,"setgid":setgid,"newObjects":observed,"existingSeedUnchanged":True})
  self.record("installed Samba four inheritance combinations on two distinct parents",matrix=matrix)
  a=self.parents["a_owner_sgid"];b=self.parents["b_owner_sgid"]
  self.restart_smb([{"name":"common","path":self.shared,"inheritOwner":False},{"name":"cross","path":a,"inheritOwner":True},{"name":"other","path":b,"inheritOwner":True},{"name":"readonly","path":a,"inheritOwner":True,"readOnly":True}])
  q=self.smb("readonly","put "+str(self.source)+" forbidden-ro.txt",denied=True)
  u=self.smb("cross","put "+str(self.source)+" forbidden-user.txt",user="daemon",denied=True)
  assert not (a/"forbidden-ro.txt").exists() and not (a/"forbidden-user.txt").exists()
  self.record("READ_ONLY and authenticated but unauthorized principal cannot write",readOnly=q,unauthorized=u)
  child=a/"child";old_files={str(p.relative_to(child)):info(p) for p in child.rglob("*") if p.is_file()}
  os.chown(child,1,1);child.chmod(0o2770)
  self.smb("cross","cd child; put "+str(self.source)+" immediate-parent.txt")
  assert (child/"immediate-parent.txt").stat().st_uid==1 and (child/"immediate-parent.txt").stat().st_gid==1
  assert all(info(child/name)==v for name,v in old_files.items())
  self.record("inheritance uses the immediate parent, not share-root identity",created=info(child/"immediate-parent.txt"),otherParent=info(b))
  before=info(a)
  for cfg in ({"inheritOwner":True,"forceUser":"daemon"},{"inheritOwner":True,"forceGroup":"daemon"},{"inheritOwner":False,"forceGroup":"daemon"}):
   try:guard(cfg,0o2770);raise AssertionError("conflict accepted")
   except ValueError as err:assert str(err)=="SMB_PARENT_IDENTITY_CONFLICT"
  assert info(a)==before
  test_conf=self.control/"conflict.conf"
  test_conf.write_text(self.smbconf.read_text()+"\n[conflict]\npath="+str(a)+"\ninherit owner=yes\nforce user=daemon\n")
  command(["testparm","-s",str(test_conf)])
  self.record("Samba accepts contradictory force identity; prototype rejects before metadata/config changes",sambaParserAccepts=True,applicationGuardRequired=True,parentUnchanged=True)
  command(["smbcontrol","-s",str(self.smbconf),str(self.smb_process.pid),"reload-config"])
  self.smb("cross","put "+str(self.source)+" reload.txt")
  self.restart_smb([{"name":"common","path":self.shared,"inheritOwner":False},{"name":"cross","path":a,"inheritOwner":True}])
  self.smb("cross","put "+str(self.source)+" restart.txt")
  assert (a/"reload.txt").stat().st_uid==0 and (a/"restart.txt").stat().st_uid==0
  self.record("private Samba reload and restart retain explicit parent owner setting",reload=info(a/"reload.txt"),restart=info(a/"restart.txt"))
  self.refresh([{'port':23049},{'port':23050}])  # Fixture ACLs were created after initial Ganesha startup.
  self.nfs_io(a)
  self.common_policy(conf)
 def nfs_io(self,a):
  code=r'''
import os,subprocess,sys,json
from pathlib import Path
mount=Path(sys.argv[1]);port=sys.argv[2];relative=sys.argv[3]
def run(args,**kw):
 r=subprocess.run(args,capture_output=True,text=True,timeout=30,**kw)
 if r.returncode:raise RuntimeError(str(args[:3])+": "+r.stderr)
 return r.stdout
def identity():os.setgroups([1001,65534]);os.setgid(1001);os.setuid(1001)
run(["mount","-t","nfs4","-o","vers=4.1,sec=sys,port="+port+",soft,timeo=10,retrans=1,nosharecache,actimeo=0,lookupcache=none","127.0.0.1:/shared",str(mount)])
try:
 target=mount/relative/("nfs-"+port+".txt")
 run(["python3","-c","from pathlib import Path;import sys;Path(sys.argv[1]).write_text('epic1453-nfs')",str(target)],preexec_fn=identity)
 run(["python3","-c","from pathlib import Path;import sys;assert Path(sys.argv[1]).read_bytes()==b'epic1453-inherit-io'",str(mount/relative/"made.txt")],preexec_fn=identity)
 observed=json.loads(run(["python3","-c","import os,json,sys;s=os.stat(sys.argv[1]);print(json.dumps(dict(uid=s.st_uid,gid=s.st_gid)))",str(target)],preexec_fn=identity));observed.update(port=int(port),smbFileReadableViaNfs=True,clientUid=1001,clientPrimaryGid=1001,clientSupplementaryGids=[1001,65534]);print(json.dumps(observed))
finally:run(["umount",str(mount)])
'''
  observed=[]
  for port in (23049,23050):
   m=self.control/("nfs-client-"+str(port));m.mkdir()
   out=command(["ip","netns","exec",self.ns,"unshare","--mount","--propagation","private","python3","-c",code,str(m),str(port),a.name],timeout=70)
   v=json.loads(out);assert v["uid"]==1001 and v["gid"]==65534
   target=self.control/("nfs-copy-"+str(port))
   self.smb("cross","get nfs-"+str(port)+".txt "+str(target))
   assert target.read_bytes()==b"epic1453-nfs"
   observed.append(v)
  self.record("NFS two-port cross-access preserved; UID remains NFS creator while setgid inherits GID",clients=observed)
 def common_policy(self,conf):
  sys.path.insert(0,self.args.candidate)
  import shared_directory_policy as h
  import configuration_transaction as txm
  engine=h.DirectoryPolicies(registry=self.control/"common-policy.json",configs=conf,desired=self.control/"desired",refresh=self.refresh,protocol_check=self.check_protocols)
  row={"uuid":"probe-root","name":"probe-root","protocol":"NFS","config":{"volumeMountPath":str(self.volume),"backingPath":str(self.shared)}}
  o=engine.observe(row);policy={k:o[k] for k in ("volumeuuid","canonicalpath","filesystemuuid","inode","owneruid","ownergid","mode")}
  policy.update(uuid=str(uuid.uuid4()),revision=1)
  smb=[{"state":"Ready","config":row["config"],"acls":[{"principalType":"LOCAL_USER","principal":"epic1452user","permission":"READ_WRITE","state":"Ready"}]}]
  def value(rev,mode=None,fail=False):
   change={} if mode is None else {"mode":mode}
   return {"action":"ADOPT" if rev==1 else "UPDATE","policy":{**policy,"revision":rev,**({"mode":mode} if mode else {})},"changes":change,"expectedObservation":engine.observe(row)["fingerprint"],"expectedPolicyRevision":rev-1,"units":engine.units(str(self.shared)),"smbShares":smb,"testFail":fail}
  instance=str(uuid.uuid4());calls=[]
  class Adapter(txm.RuntimeAdapter):
   def apply(me,c):calls.append(1);super().apply(c)
   def verify(me,c):
    super().verify(c)
    if c["directory policy apply"].get("testFail"):raise txm.Rejected("DESIGN_INJECTED_PEER_FAILURE")
  def payload(v,base):return {"instanceUuid":instance,"operationid":str(uuid.uuid4()),"baseRevision":base,"targetRevision":base+1,"candidate":{"directory policy apply":v},"before":{}}
  descendants={str(p.relative_to(self.shared)):info(p) for p in self.shared.rglob("*")}
  initial=info(self.shared)
  with patch.object(h,"DirectoryPolicies",return_value=engine):
   tx=txm.Transaction(self.control/"journal",Adapter)
   adopt=payload(value(1),0);assert tx.execute("apply",adopt)["state"]=="VERIFIED";tx.execute("commit",adopt)
   on=payload(value(2,"2770"),1);assert tx.execute("apply",on)["state"]=="VERIFIED";tx.execute("commit",on)
   active=info(self.shared);assert (active["uid"],active["gid"],active["inode"],active["device"])==(initial["uid"],initial["gid"],initial["inode"],initial["device"]) and active["mode"]=="2770"
   count=len(calls);reopen=txm.Transaction(self.control/"journal",Adapter);assert reopen.execute("apply",on)["state"]=="COMMITTED" and len(calls)==count
   policy.update(revision=2,mode="2770")
   off=payload(value(3,"0770"),2);assert tx.execute("apply",off)["state"]=="VERIFIED";tx.execute("commit",off);assert info(self.shared)==initial
   policy.update(revision=3,mode="0770")
   failed=payload(value(4,"2770",True),3);rolled=tx.execute("apply",failed);assert rolled["state"]=="ROLLED_BACK" and rolled["rollback"]["state"]=="SUCCEEDED"
   assert info(self.shared)==initial and {str(p.relative_to(self.shared)):info(p) for p in self.shared.rglob("*")}==descendants
  self.record("installed common transaction supports nonrecursive setgid on/off, identity preservation, duplicate protection and rollback",initial=initial,enabled=active,restored=info(self.shared),rollback=rolled["state"],childrenUnchanged=True)

def main():
 assert os.readlink('/proc/self/ns/mnt')!=os.readlink('/proc/1/ns/mnt'), 'Run with unshare --mount --propagation private'
 assert not any('shared:' in line.split(' - ',1)[0] for line in Path('/proc/self/mountinfo').read_text().splitlines()), 'Private mount propagation required'
 args=SimpleNamespace(volume="/srv/ablestack-storage/volumes/b02bd1d2-ed62-44e8-8a95-8798b604f43e",provisioning="SPARSE",candidate="/usr/local/lib/ablestack-storage",live_export="/srv/ablestack-storage/volumes/b02bd1d2-ed62-44e8-8a95-8798b604f43e/export/common1452")
 result=DesignProbe(args).run();print(json.dumps(result));return 0 if result["success"] else 1

if __name__=="__main__":sys.exit(main())
