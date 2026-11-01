from pathlib import Path
import subprocess,json,hashlib
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
unit='ablestack-storage-ganesha@0.0.0.0_2049.service'
assert subprocess.check_output(['systemctl','is-active',unit],text=True).strip()=='active'
marker=Path('/run/epic1455-r4-fail-once');assert not marker.exists()
folder=Path('/run/systemd/system')/(unit+'.d');folder.mkdir(exist_ok=True)
dropin=folder/'90-epic1455-r4.conf';assert not dropin.exists()
dropin.write_text("[Service]\nExecStartPre=/bin/sh -c 'if test -e /run/epic1455-r4-fail-once; then /bin/rm -f /run/epic1455-r4-fail-once; echo EPIC1455_EXPECTED_ONE_SHOT_START_FAILURE >&2; exit 1; fi'\n")
marker.write_text('one shot test-only, fail one candidate restart; rollback next start allowed\n')
subprocess.run(['systemctl','daemon-reload'],check=True)
print(json.dumps({'installed':True,'target':unit,'restartExecutedByHarness':False,'oneShotMarker':str(marker),'dropin':str(dropin),'activeBefore':True,'payloadUnchanged':True}))
