from pathlib import Path
import json,sys,hashlib,subprocess
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
release=Path('/opt/ablestack/storage-runtime/current').resolve()
sys.path.insert(0,str(release/'lib/ablestack-storage'))
import shared_directory_policy as h
settings=h.smb_runtime_settings('s1455-child')
assert len(settings)==2 and all(s['inheritowner']=='no' and not s['forceUser'] and not s['forceGroup']for s in settings)
root=Path('/var/lib/ablestack-storage/configuration-operations/c1eca707-a554-4c42-85cd-1e9e9744d804')
operations={}
for op in ['18dc6b50-841f-4980-8eab-2c1a6409fa58','be1417ba-c346-479f-b5e6-f838ad8d28a4']:
 value=json.loads((root/op/'status.json').read_text())
 operations[op]={k:value.get(k)for k in ['state','phase','baseRevision','targetRevision','rollback','originalfailure']}
print(json.dumps({'strictSmbSettings':settings,'release':release.name,'activeHelperSha256':hashlib.sha256((release/'lib/ablestack-storage/shared_directory_policy.py').read_bytes()).hexdigest(),'activeCliSha256':hashlib.sha256((release/'ablestack-storagectl').read_bytes()).hexdigest(),'operations':operations,'defaultSmbdState':subprocess.run(['systemctl','is-active','smbd'],capture_output=True,text=True).stdout.strip()}))
