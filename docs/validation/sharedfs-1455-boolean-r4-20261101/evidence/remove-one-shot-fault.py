from pathlib import Path
import subprocess,json
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
unit='ablestack-storage-ganesha@0.0.0.0_2049.service'
marker=Path('/run/epic1455-r4-fail-once');assert not marker.exists(),'UI apply has not consumed one-shot failure'
folder=Path('/run/systemd/system')/(unit+'.d');dropin=folder/'90-epic1455-r4.conf'
assert dropin.exists();dropin.unlink()
if not list(folder.iterdir()):folder.rmdir()
subprocess.run(['systemctl','daemon-reload'],check=True)
active=subprocess.check_output(['systemctl','is-active',unit],text=True).strip()
assert active=='active',active
journal=subprocess.check_output(['journalctl','-u',unit,'--since','2026-11-01 04:08:00 UTC','--no-pager','-n','100'],text=True)
print(json.dumps({'removed':True,'markerConsumed':True,'unitActiveAfterAutomaticRollback':active,'restartExecutedByHarness':False,'expectedJournal':[s for s in journal.splitlines()if 'EPIC1455_EXPECTED' in s or 'status=1/FAILURE' in s or 'Started' in s][-12:]}))
