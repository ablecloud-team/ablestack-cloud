#!/usr/bin/env python3
"""Disposable SystemVM regression: real CLI, real bindings, standard before-image rollback."""
from pathlib import Path
import argparse,contextlib,copy,fcntl,hashlib,json,os,shutil,stat,subprocess,sys,tempfile,time,traceback,uuid

def command(argv,**kwargs):
 q=subprocess.run(argv,capture_output=True,text=True,timeout=kwargs.pop("timeout",60),**kwargs)
 if q.returncode:raise RuntimeError(str(argv[:3])+": "+(q.stdout+q.stderr)[-3000:])
 return q.stdout
def digest(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def metadata(p):
 s=Path(p).stat()
 return dict(uid=s.st_uid,gid=s.st_gid,mode=format(stat.S_IMODE(s.st_mode),"04o"),inode=s.st_ino)
def protected(volume):
 paths=[Path("/etc")/n for n in ["passwd","group","shadow","gshadow"]]
 paths += [Path("/etc/samba/smb.conf"),Path("/etc/ablestack-storage/desired-state/directory-policy.json"),Path("/etc/ablestack-storage/desired-state/nfs-export-apply.json"),Path("/etc/ablestack-storage/desired-state/smb-share-apply.json")]
 paths += list(Path("/etc/ganesha/ablestack-storage").glob("*.conf"))
 common=volume/"export/common1453"
 return {"files":{str(p):digest(p) for p in paths if p.is_file()},"parent":metadata(common),
         "children":{str(p.relative_to(common)):{"meta":metadata(p),"sha256":digest(p)} for p in common.rglob("*") if p.is_file()},
         "network":json.loads(command(["ip","-j","-4","addr","show","dev","eth0"]))}
def main():
 ap=argparse.ArgumentParser();ap.add_argument("--candidate",type=Path,required=True);ap.add_argument("--control",type=Path,required=True)
 ap.add_argument("--instance-uuid",required=True);ap.add_argument("--source-operation",required=True);ap.add_argument("--confirm-disposable-vm",required=True);ap.add_argument("--volume-uuid",required=True);ap.add_argument("--provisioning",choices=["SPARSE","FAT"],required=True)
 args=ap.parse_args()
 assert os.readlink("/proc/self/ns/mnt")!=os.readlink("/proc/1/ns/mnt"),"Private mount namespace required"
 assert not any("shared:" in l.split(" - ",1)[0] for l in Path("/proc/self/mountinfo").read_text().splitlines()),"Private propagation required"
 assert Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()==str(uuid.UUID(args.confirm_disposable_vm))
 volume=Path("/srv/ablestack-storage/volumes")/str(uuid.UUID(args.volume_uuid))
 assert command(["findmnt","-n","-M",str(volume),"-o","FSTYPE"]).strip() in ["xfs","ext4"]
 candidate=args.candidate.resolve();control=args.control.resolve();assert control.parent==Path("/var/tmp") and control.name.startswith("epic1453-r2-")
 instance=str(uuid.UUID(args.instance_uuid))
 original_request=json.loads((Path("/var/lib/ablestack-storage/configuration-operations")/instance/str(uuid.UUID(args.source_operation))/"request.json").read_text())
 before_command=original_request["before"]["smb share apply"]
 current_desired=Path("/etc/ablestack-storage/desired-state/smb-share-apply.json")
 if current_desired.exists():assert not json.loads(current_desired.read_text()).get("shares"),"Only an empty disposable SMB baseline is allowed"
 assert not command(["ss","-H","-tn","state","established","( sport = :2049 or sport = :445 )"]).strip(),"Drain disposable test sessions before native test"
 nonce=uuid.uuid4().hex[:10];data=volume/(".epic1453-native-r2-"+nonce);data.mkdir();os.chown(data,100001,200001);data.chmod(0o775)
 seed=data/"seed.bin";seed.write_bytes(b"epic1453-whole-entrypoint-preserve");os.chown(seed,1001,1001);seed.chmod(0o640);seed_before={"metadata":metadata(seed),"sha256":digest(seed)}
 registry=Path("/etc/ablestack-storage/desired-state/directory-policy.json")
 originals={}
 for root in [Path("/etc/samba"),Path("/etc/ablestack-storage")]:
  for p in root.rglob("*"):
   if p.is_file() and not p.is_symlink():originals[str(p)]=(p.read_bytes(),stat.S_IMODE(p.stat().st_mode),p.stat().st_uid,p.stat().st_gid)
 original_paths=set(originals)
 original_services={}
 for unit in ["smbd.service","nmbd.service","winbind.service"]:
  original_services[unit]={"active":subprocess.run(["systemctl","is-active",unit],capture_output=True,text=True).stdout.strip(),"enabled":subprocess.run(["systemctl","is-enabled",unit],capture_output=True,text=True).stdout.strip()}
 firewall=command(["iptables-save"])
 baseline=protected(volume);ctl_target=Path("/usr/local/bin/ablestack-storagectl").resolve();ctl_before=digest(ctl_target)
 wrapper=control/"dispatcher";stderr_log=control/"whole-cli-stderr.log"
 wrapper.write_text("#!/bin/bash\nexec "+str(candidate/"ablestack-storagectl")+" \"$@\" 2> >(tee -a "+str(stderr_log)+" >&2)\n");wrapper.chmod(0o700)
 sys.path.insert(0,str(candidate/"lib/ablestack-storage"))
 import shared_directory_policy as h
 import configuration_transaction as txm
 assert h.inheritance_protection_version()==1
 engine=h.DirectoryPolicies();share=copy.deepcopy(original_request["candidate"]["smb share apply"]["shares"][0])
 share.update(name="native1453_"+nonce,uuid=str(uuid.uuid4()),state="Ready",path="/export/native1453_"+nonce,acls=[])
 share["config"].update(backingPath=str(data),directoryPolicyUuid=str(uuid.uuid4()),volumeMountPath=str(volume),createDirectory=False,crossProtocol=True,guestOk=False,readOnly=False,directoryMode="0775")
 share["config"].pop("inheritOwner",None)
 observed=engine.observe(share);assert observed["status"]=="OBSERVED"
 policy={k:observed[k] for k in ["volumeuuid","canonicalpath","filesystemuuid","inode","owneruid","ownergid","mode"]}
 policy.update(uuid=share["config"]["directoryPolicyUuid"],revision=1)
 result={"success":False,"vmUuid":args.confirm_disposable_vm,"volumeUuid":args.volume_uuid,"provisioning":args.provisioning,"kind":"unmodified full product CLI/native bindings with candidate executable dispatch","cases":[]}
 mounted=False;lock=None;active_adapter=None;failure=None
 class Adapter(txm.RuntimeAdapter):
  def snapshot(me,payload):
   super().snapshot(payload)
   policies=engine.read();policies[policy["uuid"]]=policy;engine.write(policies)
  def verify(me,commands):
   super().verify(commands)
   if getattr(me,"inject",False) and commands.get("smb share apply",{}).get("shares"):
    raise txm.Rejected("NATIVE_TEST_POST_APPLY_FAILURE")
 try:
  lock=(Path("/var/lib/ablestack-storage/configuration-operations")/instance/"lock").open("a")
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  command(["mount","--bind",str(wrapper),str(ctl_target)]);mounted=True
  for label,flag,inject in [("omitted",None,False),("false",False,False),("true",True,False),("injected-peer-failure",True,True)]:
   row=copy.deepcopy(share)
   if flag is not None:row["config"]["inheritOwner"]=flag
   smb=copy.deepcopy(original_request["candidate"]["smb share apply"]);smb["shares"]=[row];smb["enabled"]=True;smb["listeners"]=[{"id":str(uuid.uuid4()),"listenIp":"0.0.0.0","port":445}]
   payload={"instanceUuid":instance,"vmUuid":args.confirm_disposable_vm,"operationid":str(uuid.uuid4()),"baseRevision":0,"targetRevision":1,"candidate":{"smb share apply":smb},"before":{"smb share apply":before_command}}
   factory=lambda private:Adapter(private)
   transaction=txm.Transaction(control/("journal-"+label),factory)
   directory,private,status_path=transaction.paths(payload)
   if inject:
    class FailingAdapter(Adapter):
     def __init__(me,private):super().__init__(private);me.inject=True
    transaction=txm.Transaction(control/("journal-"+label),FailingAdapter)
   applied=transaction.execute("apply",payload)
   active_adapter=transaction.adapter_factory(private)
   if inject:
    assert applied["state"]=="ROLLED_BACK" and applied["originalfailure"]["code"]=="NATIVE_TEST_POST_APPLY_FAILURE" and applied["rollback"]["state"]=="SUCCEEDED",applied
    result["cases"].append({"flag":label,"state":applied["state"],"rollback":applied["rollback"]["state"]});active_adapter=None;continue
   assert applied["state"]=="VERIFIED",applied
   runtime=h.smb_runtime_settings(row["name"]);assert runtime and all((v["inheritowner"].lower() in ["yes","windows and unix"])==(flag is True) for v in runtime),runtime
   stamp=(private/"runner-result.json").stat().st_mtime_ns
   assert transaction.execute("apply",payload)["state"]=="VERIFIED" and (private/"runner-result.json").stat().st_mtime_ns==stamp
   preview_value={"action":"UPDATE","policy":{**policy,"revision":2,"mode":"2775"},"changes":{"mode":"2775"},"expectedObservation":engine.observe(row)["fingerprint"],"expectedPolicyRevision":1,"units":engine.units(str(data)),"smbShares":[row]}
   preview_file=control/("preview-"+label+".json");preview_file.write_text(json.dumps(preview_value))
   preview=json.loads(command(["python3",str(candidate/"lib/ablestack-storage/shared_directory_policy.py"),"preview",str(preview_file)]))
   assert preview["success"] and preview["after"]["mode"]=="2775" and metadata(data)["mode"]=="0775"
   result["cases"].append({"flag":label,"state":applied["state"],"inheritOwnerValues":[v["inheritowner"] for v in runtime],"directCliPreview":True,"parent":metadata(data),"duplicateDidNotApply":True})
   cleaned=transaction.rollback(applied,status_path,active_adapter)
   assert cleaned["state"]=="ROLLED_BACK" and cleaned["rollback"]["state"]=="SUCCEEDED",cleaned
   active_adapter=None
   assert {"metadata":metadata(seed),"sha256":digest(seed)}==seed_before
  result["success"]=True
 except BaseException as error:
  failure=error;result["errorType"]=type(error).__name__;result["error"]=str(error)[:3000];result["trace"]=traceback.format_exc()[-4000:]
 finally:
  if active_adapter is not None:
   try:active_adapter.restore();active_adapter.verify_restored()
   except BaseException as error:result["cleanupError"]=str(error);result["success"]=False
  try:
   txm.SmbListenerProfiles().stop_all()
   for p in list(Path("/etc/ablestack-storage").rglob("*"))+list(Path("/etc/samba").rglob("*")):
    if p.is_file() and not p.is_symlink() and str(p) not in original_paths:
     assert str(p).startswith(("/etc/ablestack-storage/smb","/etc/samba/"))
     p.unlink()
   for name,(content,mode,uid,gid) in originals.items():
    p=Path(name)
    if p.exists() and p.read_bytes()==content:
     p.chmod(mode);os.chown(p,uid,gid);continue
    p.parent.mkdir(parents=True,exist_ok=True);fd,temp=tempfile.mkstemp(prefix=".native-restore-",dir=str(p.parent))
    with os.fdopen(fd,"wb") as f:f.write(content);f.flush();os.fsync(f.fileno())
    os.chmod(temp,mode);os.chown(temp,uid,gid);os.replace(temp,p)
   for unit,value in original_services.items():
    command(["systemctl","start" if value["active"]=="active" else "stop",unit])
    if value["enabled"] in ["enabled","disabled"]:command(["systemctl","enable" if value["enabled"]=="enabled" else "disable",unit])
   command(["iptables-restore"],input=firewall)
   assert baseline==protected(volume),"Protected metadata/accounts/config changed"
   assert {"metadata":metadata(seed),"sha256":digest(seed)}==seed_before,"Seed changed"
   assert {p.name for p in data.iterdir()}=={"seed.bin"}
   assert data.parent==volume and data.name.startswith(".epic1453-native-r2-");seed.unlink();data.rmdir()
   result["protectedStatePreserved"]=True;result["temporaryDataRemoved"]=True
  except BaseException as error:
   result["cleanupError"]=str(error);result["success"]=False
  if mounted:command(["umount",str(ctl_target)])
  if lock is not None:lock.close()
  result["installedStoragectlUnchanged"]=digest(ctl_target)==ctl_before
  if not result["installedStoragectlUnchanged"]:result["success"]=False
  if stderr_log.exists():result["nativeStderrTail"]=stderr_log.read_text(errors="replace")[-5000:] if not result["success"] else "captured; no unexpected command failure"
 print(json.dumps(result))
 return 0 if result["success"] else 1
if __name__=="__main__":sys.exit(main())
