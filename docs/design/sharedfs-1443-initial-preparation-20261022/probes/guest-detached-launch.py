# SPDX-License-Identifier: Apache-2.0
import subprocess,json,uuid,shutil,time
unit='issue1443-design-'+uuid.uuid4().hex+'.service'
assert shutil.which('systemd-run') and shutil.which('systemctl')
begin=time.monotonic()
r=subprocess.run(['systemd-run','--quiet','--collect','--service-type=exec','--unit='+unit,'/bin/sleep','8'],capture_output=True,text=True,timeout=10)
assert r.returncode==0,r.stderr
print(json.dumps({'unit':unit,'launcherElapsedSeconds':round(time.monotonic()-begin,3),'payload':'/bin/sleep 8','persistentUnitFileCreated':False,'actualFormatterExecuted':False}))
