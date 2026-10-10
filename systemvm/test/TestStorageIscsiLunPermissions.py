# Preparation-only regression using the actual candidate apply heredoc.
import ast,contextlib,io,json,os,fcntl,shutil,subprocess,sys,tempfile,unittest
from pathlib import Path
from unittest import mock
CLI=Path(__file__).resolve().parents[1]/"debian/usr/local/bin/ablestack-storagectl"
IQN="iqn.2026-10.local.storage:permissions"
C1="iqn.2026-10.example.client:one"
C2="iqn.2026-10.example.client:two"
def target(lun,acls):
 return {"uuid":"fixture-"+str(lun),"targetName":IQN,"lunOrNamespace":str(lun),"volumeUuid":"fixture-serial","volumeName":"fixture-serial","volumeSizeBytes":65536,"acls":acls}
def grant(principal,permission="READ_ONLY",state="Ready"):
 return {"principal":principal,"permission":permission,"state":state,"config":{}}
class Fixture:
 def run(self,targets,wrong=False,replace_node=False,replace_link=False,extra_lun=False):
  with tempfile.TemporaryDirectory() as td:
   root=Path(td);state=root/"state";state.mkdir(mode=0o700);fs=root/"configfs/iscsi";fs.mkdir(parents=True)
   raw=root/"raw";raw.write_bytes(b"\0"*65536)
   packet=root/"payload.json";packet.write_text(json.dumps({"enabled":True,"listeners":[{"listenIp":"127.0.0.1","port":3260}],"targets":targets}))
   text=CLI.read_text();begin=text.index("apply_iscsi_targets() {");body=text[begin:text.index("apply_nvmeof_subsystems() {",begin)]
   code=body[body.index("<<'PY'\n")+len("<<'PY'\n"):body.rindex("\nPY")]
   tree=ast.parse(code)
   class Paths(ast.NodeTransformer):
    def visit_Constant(self,node):
     if isinstance(node.value,str):
      if node.value=="/etc/ablestack-storage":return ast.copy_location(ast.Constant(str(state)),node)
      if node.value.startswith("/sys/kernel/config/target/"):
       return ast.copy_location(ast.Constant(str(root/"configfs"/node.value.removeprefix("/sys/kernel/config/target/"))),node)
     return node
    def visit_FunctionDef(self,node):
     if node.name in ("command_exists","has_elf_header","has_text_prefix"):node.body=[ast.Return(value=ast.Constant(True))]
     return self.generic_visit(node)
   tree=Paths().visit(tree);ast.fix_missing_locations(tree)
   calls=[];mappings={};rollback=[];replaced=[False]
   tpg=fs/IQN/"tpgt_1"
   def run(command,**kwargs):
    calls.append(command)
    if command[0]=="targetcli":
     path=command[1];op=command[2] if len(command)>2 else ""
     if path=="/iscsi" and op=="create":
      for x in ["lun","acls","np"]:(tpg/x).mkdir(parents=True,exist_ok=True)
     elif path=="/iscsi" and op=="delete":
      if (fs/IQN).exists():rollback.append(True);shutil.rmtree(fs/IQN)
     elif path.endswith("/luns") and op=="create":
      assert "add_mapped_luns=false" in command
      (tpg/"lun"/("lun_"+str(int(command[4])))).mkdir(exist_ok=True)
     elif path.endswith("/acls") and op=="create":
      assert "add_mapped_luns=false" in command
      acl=tpg/"acls"/command[3];(acl/"auth").mkdir(parents=True,exist_ok=True)
      for name in ["userid","password","userid_mutual","password_mutual"]:(acl/"auth"/name).write_text("NULL")
     elif "/acls/" in path and op=="create":
      principal=path.split("/acls/",1)[1];lun=command[3]
      flag=int(command[5].split("=",1)[1]);node=tpg/"acls"/principal/("lun_"+lun);node.mkdir()
      os.symlink(tpg/"lun"/("lun_"+command[4]),node/"alias")
      (node/"write_protect").write_text(str(0 if wrong else flag))
      mappings[(principal,lun)]=flag
     return subprocess.CompletedProcess(command,0,"","")
    if command[0]=="lsblk":
     if "-J" in command:
      return subprocess.CompletedProcess(command,0,json.dumps({"blockdevices":[{"name":"fixture","path":str(raw),"type":"disk","serial":"fixture-serial","size":65536,"mountpoint":None,"children":[]}]}),"")
     return subprocess.CompletedProcess(command,0,"65536\n","")
    if command[0]=="findmnt":return subprocess.CompletedProcess(command,1,"","")
    if command[0]=="ss":return subprocess.CompletedProcess(command,0,"LISTEN 0 1 127.0.0.1:3260 *:*\n","")
    return subprocess.CompletedProcess(command,0,"","")
   fd=os.open(root/"writer.lock",os.O_RDWR|os.O_CREAT,0o600)
   try:saved=os.dup(9)
   except OSError:saved=None
   os.dup2(fd,9);fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
   output=io.StringIO();failure=None
   original_read=os.read
   def read(descriptor,size):
    value=original_read(descriptor,size)
    if (replace_node or replace_link or extra_lun) and not replaced[0]:
     path=Path(os.readlink("/proc/self/fd/"+str(descriptor)))
     if path.name=="write_protect":
      node=path.parent
      if replace_node:node.rename(node.with_name(node.name+"-old"));node.mkdir()
      elif extra_lun:(node.parent/"lun_2").mkdir()
      else:(node/"alias").unlink();os.symlink(root,node/"alias")
      replaced[0]=True
    return value
   try:
    with mock.patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9","ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(root/"writer.lock")}),mock.patch.object(sys,"argv",["fixture",str(packet)]),mock.patch.object(subprocess,"run",side_effect=run),mock.patch("time.sleep",lambda _:None),mock.patch("os.read",side_effect=read),contextlib.redirect_stdout(output):
     try:exec(compile(tree,"<actual-per-lun-apply>","exec"),{})
     except BaseException as e:failure=e
   finally:
    if saved is None:os.close(9)
    else:os.dup2(saved,9);os.close(saved)
    if fd!=9:os.close(fd)
   return {"stdout":output.getvalue(),"failure":failure,"calls":calls,"mappings":mappings,"rollback":bool(rollback),"statePublished":(state/"iscsi-targets.json").exists()}
class StorageIscsiLunPermissionsTest(unittest.TestCase):
 def test_same_initiator_ro_and_rw_are_per_lun(self):
  r=Fixture().run([target(0,[grant(C1)]),target(1,[grant(C1,"READ_WRITE")])])
  self.assertIsNone(r["failure"]);self.assertEqual({(C1,"0"):1,(C1,"1"):0},r["mappings"])
  self.assertIs(json.loads(r["stdout"])["success"],True)
 def test_different_initiators_and_absent_grant_no_mapping(self):
  r=Fixture().run([target(0,[grant(C1,"READ_WRITE"),grant(C2)]),target(1,[])])
  self.assertIsNone(r["failure"]);self.assertEqual({(C1,"0"):0,(C2,"0"):1},r["mappings"])
 def test_disabled_acl_does_not_create_access(self):
  r=Fixture().run([target(0,[grant(C1,state="Disabled")])])
  self.assertIsNone(r["failure"]);self.assertEqual({},r["mappings"])
 def test_unknown_missing_and_duplicate_conflict_fail_before_vault_or_delete(self):
  missing=grant(C1);missing.pop("permission")
  for targets in [[target(0,[grant(C1,"ADMIN")])],[target(0,[missing])],[target(0,[grant(C1)]),target(0,[grant(C1,"READ_WRITE")])]]:
   r=Fixture().run(targets);self.assertIsInstance(r["failure"],SystemExit)
   self.assertEqual([],r["calls"]);self.assertFalse(r["statePublished"])
   self.assertEqual("AUTH",json.loads(r["stdout"])["stage"])
 def test_wrong_readback_fails_and_rolls_back(self):
  r=Fixture().run([target(0,[grant(C1)])],wrong=True)
  self.assertIsInstance(r["failure"],SystemExit);self.assertTrue(r["rollback"])
  self.assertEqual("AUTH",json.loads(r["stdout"])["stage"])
 def test_replaced_mapped_directory_fails_and_rolls_back(self):
  r=Fixture().run([target(0,[grant(C1)])],replace_node=True)
  self.assertIsInstance(r["failure"],SystemExit);self.assertTrue(r["rollback"])
 def test_changed_link_after_open_fails_and_rolls_back(self):
  r=Fixture().run([target(0,[grant(C1)])],replace_link=True)
  self.assertIsInstance(r["failure"],SystemExit);self.assertTrue(r["rollback"])
 def test_new_ungranted_child_during_readback_is_rejected(self):
  r=Fixture().run([target(0,[grant(C1)])],extra_lun=True)
  self.assertIsInstance(r["failure"],SystemExit);self.assertTrue(r["rollback"])
 def test_ungranted_tpg_range_is_not_silently_restricted(self):
  r=Fixture().run([target(65535,[])])
  self.assertIsNone(r["failure"]);self.assertEqual({},r["mappings"])
 def test_vendor_limit_and_leading_zero(self):
  r=Fixture().run([target("000255",[grant(C1)])])
  # targetcli normalizes the numeric TPG LUN in the real vendor; fixture does too.
  self.assertIsNone(r["failure"])
  r=Fixture().run([target(256,[grant(C1)])])
  self.assertIsInstance(r["failure"],SystemExit);self.assertEqual([],r["calls"]);self.assertFalse(r["statePublished"])
if __name__=="__main__":
 if "--manager-payload" in sys.argv:
  packet=json.load(sys.stdin);result=Fixture().run(packet["targets"])
  print(json.dumps({"success":result["failure"] is None,"mappings":[{"principal":p,"lun":l,"writeProtect":v} for (p,l),v in sorted(result["mappings"].items())],"rollback":result["rollback"]}))
 else:unittest.main()
