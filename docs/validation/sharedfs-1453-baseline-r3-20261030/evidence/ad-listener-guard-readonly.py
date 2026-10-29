from pathlib import Path
import os,sys,json,hashlib,subprocess
from unittest.mock import patch
assert Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()=="587719e2-0e9f-3a00-a88b-c7180b06ec0f"
ctl=Path("/usr/local/bin/ablestack-storagectl").resolve()
sys.path.insert(0,str(ctl.parent/"lib/ablestack-storage"))
import configuration_transaction as tx
engine=tx.SmbListenerProfiles()
state=json.loads(engine.manifest.read_text());row=next(iter(state["profiles"].values()))
files=[engine.manifest,Path("/etc/samba/smb.conf"),Path("/etc/ablestack-storage/smb-domain.json")]
before={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
original_checked=tx.checked
def readonly_checked(args,*a,**kw):
 if args[:2] not in (["systemctl","show"],["systemctl","is-active"]):
  raise AssertionError("Diagnostic blocked any possible mutation: "+str(args[:3]))
 return original_checked(args,*a,**kw)
result={"managedReady":engine.ready(row,state["profiles"]),"managedPid":engine.pid(row),"rawPid":engine.raw_pid(row),"row":row,"actualSockets":sorted(engine.sockets())}
with patch.object(engine,"configuration",side_effect=AssertionError("Configuration mutation blocked")),patch.object(tx,"checked",side_effect=readonly_checked):
 try:engine.start(row,state["profiles"])
 except tx.Rejected as error:result["rejectedCode"]=error.code
 else:raise AssertionError("Expected port-ownership rejection was absent")
assert before=={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
result.update(writesPerformed=False,installedConfigurationUnchanged=True)
print(json.dumps(result))
