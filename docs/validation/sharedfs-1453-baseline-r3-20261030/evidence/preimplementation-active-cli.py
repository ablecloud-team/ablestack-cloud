from pathlib import Path
import os,json,subprocess,sys,hashlib,stat
root=Path(sys.argv[1]);candidate=root/"candidate.py"
assert os.readlink("/proc/self/ns/mnt")!=os.readlink("/proc/1/ns/mnt")
assert not any("shared:" in x.split(" - ",1)[0] for x in Path("/proc/self/mountinfo").read_text().splitlines())
assert Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()=="7229bd05-0ea0-3b1f-8f38-cd714a240553"
ctl=Path("/usr/local/bin/ablestack-storagectl");active=ctl.resolve().parent/"lib/ablestack-storage"
baseline=Path("/usr/local/lib/ablestack-storage/runtime_updater.py")
assert active!=baseline.parent and not (active/"runtime_updater.py").exists()
env={**os.environ,"PYTHONDONTWRITEBYTECODE":"1"}
def run(args):
 q=subprocess.run(args,capture_output=True,text=True,env=env,timeout=30)
 assert q.returncode==0,(q.returncode,q.stderr[-1500:])
 return json.loads(q.stdout)
def capability():return run([str(ctl),"configuration","capability","show"])
original=capability();assert original["smbParentInheritanceProtectionVersion"]==0
subprocess.run(["mount","--bind",str(candidate),str(active/"shared_directory_policy.py")],check=True)
try:
 current=capability();assert current["smbParentInheritanceProtectionVersion"]==1
 sys.path.insert(0,str(active));import shared_directory_policy as h
 desired=json.loads(Path("/etc/ablestack-storage/desired-state/smb-share-apply.json").read_text())
 share=next(x for x in desired["shares"] if x["name"]=="common1453r2")
 policy=h.DirectoryPolicies().read()[share["config"]["directoryPolicyUuid"]]
 query={"protocol":"SMB","revision":8,"action":"UPDATE","ports":[445],"resourceids":[share["uuid"]],"resourcecount":1,"ackrequired":True,"drainrequired":False,"smbinheritance":{"inheritOwner":True,"directoryPolicyId":policy["uuid"]},"inheritancetarget":share,"inheritancepolicy":policy}
 f=root/"preflight.json";f.write_text(json.dumps(query))
 accepted=run([str(ctl),"configuration","preflight","show",str(f)])
 assert accepted["status"]=="READY",accepted
 assert accepted["inheritancepreview"]["inheritowner"] is True
 failures=[]
 for label,version,mode in [("legacy",4,0o644),("writable",5,0o666)]:
  stub=root/(label+".py");stub.write_text("UPDATER_VERSION = "+str(version)+"\n");stub.chmod(mode)
  subprocess.run(["mount","--bind",str(stub),str(baseline)],check=True)
  try:
   caps=capability();assert caps["smbParentInheritanceProtectionVersion"]==0,(label,caps)
   denied=run([str(ctl),"configuration","preflight","show",str(f)])
   assert denied["status"]=="BLOCKED" and denied["checks"][0]["code"]=="SMB_PARENT_INHERITANCE_PROTECTION_REQUIRED",denied
   failures.append(label)
  finally:subprocess.run(["umount",str(baseline)],check=True)
 empty=root/"empty-baseline";empty.mkdir()
 subprocess.run(["mount","--bind",str(empty),str(baseline.parent)],check=True)
 try:assert capability()["smbParentInheritanceProtectionVersion"]==0;failures.append("missing")
 finally:subprocess.run(["umount",str(baseline.parent)],check=True)
 assert capability()["smbParentInheritanceProtectionVersion"]==1
 print(json.dumps({"success":True,"actualEntrypoint":str(ctl.resolve()),"releaseHasUpdater":False,"originalCapabilityProtection":0,"candidateCapabilityProtection":1,"truePreflight":accepted["status"],"parent":accepted["inheritancepreview"]["parent"],"negativeGuards":failures,"configurationApplied":False}))
finally:subprocess.run(["umount",str(active/"shared_directory_policy.py")],check=True)
