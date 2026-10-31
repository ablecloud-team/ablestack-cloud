from pathlib import Path
import os,json,ast,stat,hashlib,sys,subprocess
assert Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()=="7229bd05-0ea0-3b1f-8f38-cd714a240553"
base=Path("/usr/local/lib/ablestack-storage");sys.path.insert(0,str(base))
import shared_directory_policy as h
up=base/"runtime_updater.py";s=up.lstat()
t=ast.parse(up.read_text())
version=[ast.literal_eval(n.value) for n in t.body if isinstance(n,ast.Assign) and any(isinstance(x,ast.Name) and x.id=="UPDATER_VERSION" for x in n.targets)]
print(json.dumps({"success":True,"effectiveUid":os.geteuid(),"helperFile":h.__file__,"limit":h.LIMIT,"updater":{"regular":stat.S_ISREG(s.st_mode),"symlink":up.is_symlink(),"uid":s.st_uid,"gid":s.st_gid,"mode":oct(s.st_mode&0o7777),"size":s.st_size,"sha256":hashlib.sha256(up.read_bytes()).hexdigest(),"version":version},"protection":h.inheritance_protection_version(),"capability":subprocess.run(["/usr/local/bin/ablestack-storagectl","configuration","capability","show"],capture_output=True,text=True,timeout=30).stdout}))
