from pathlib import Path
import json,os,subprocess,hashlib
assert Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()=="7229bd05-0ea0-3b1f-8f38-cd714a240553"
ctl=Path("/usr/local/bin/ablestack-storagectl");real=ctl.resolve()
release=real.parent/"lib/ablestack-storage"
code="import sys,json;sys.path.insert(0,sys.argv[1]);import shared_directory_policy as h;from pathlib import Path;print(json.dumps({'helper':h.__file__,'siblingUpdater':str(Path(h.__file__).with_name('runtime_updater.py')),'siblingUpdaterExists':Path(h.__file__).with_name('runtime_updater.py').exists(),'protection':h.inheritance_protection_version(),'explicitBaselineProtection':h.inheritance_protection_version('/usr/local/lib/ablestack-storage/runtime_updater.py')}))"
q=subprocess.run(["python3","-c",code,str(release)],capture_output=True,text=True,timeout=30)
p=Path("/export/common1453r2");s=p.stat();seed=p/"seed-before.txt"
print(json.dumps({"success":True,"ctlLink":str(ctl),"ctlResolved":str(real),"releaseHelperRoot":str(release),"activeResult":json.loads(q.stdout),"parent":{"uid":s.st_uid,"gid":s.st_gid,"mode":oct(s.st_mode&0o7777),"inode":s.st_ino},"seed":{"sha256":hashlib.sha256(seed.read_bytes()).hexdigest(),"inode":seed.stat().st_ino},"writesPerformed":False}))
