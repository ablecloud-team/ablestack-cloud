# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
from pathlib import Path
import subprocess,time,uuid,json,os,hashlib
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='2e65bb57-9f05-39ef-bd89-08daadd33364'
unit='epic1459-design-'+uuid.uuid4().hex[:8]
root=Path('/var/tmp')/unit;root.mkdir(mode=0o700);journal=root/'status.json'
before=hashlib.sha256(Path('/etc/systemd/system/ablestack-storage-reconcile.service').read_bytes()).hexdigest()
worker='from pathlib import Path;import time,json;p=Path('+repr(str(journal))+');p.write_text(json.dumps({"state":"RUNNING"}));time.sleep(2);p.write_text(json.dumps({"state":"READY"}))'
started=time.monotonic()
r=subprocess.run(['systemd-run','--collect','--unit='+unit,'--property=Type=exec','--property=RuntimeMaxSec=6','--property=KillMode=control-group','/usr/bin/python3','-c',worker],capture_output=True,text=True,timeout=5)
elapsed=time.monotonic()-started
try:
 assert r.returncode==0,r.stderr
 for _ in range(30):
  if journal.exists():break
  time.sleep(.05)
 first=json.loads(journal.read_text());assert first['state']=='RUNNING' and elapsed<2
 props=subprocess.run(['systemctl','show',unit,'-p','Type','-p','RuntimeMaxUSec','-p','KillMode'],capture_output=True,text=True,check=True).stdout
 for _ in range(60):
  if json.loads(journal.read_text())['state']=='READY':break
  time.sleep(.05)
 assert json.loads(journal.read_text())['state']=='READY'
 assert before==hashlib.sha256(Path('/etc/systemd/system/ablestack-storage-reconcile.service').read_bytes()).hexdigest()
 print(json.dumps({'success':True,'kind':'transient worker admission feasibility, not boot implementation','launcherElapsedSeconds':round(elapsed,3),'workerStillRunningAfterLauncher':first,'workerFinal':json.loads(journal.read_text()),'properties':props,'existingUnitUnchanged':True}))
finally:
 subprocess.run(['systemctl','stop',unit],capture_output=True,timeout=10)
 subprocess.run(['systemctl','reset-failed',unit],capture_output=True,timeout=5)
 journal.unlink(missing_ok=True);root.rmdir()
