import hashlib,json,os,subprocess,sys
from pathlib import Path
primary=Path('/mnt/e1448s')
alias=Path('/mnt/e1448a')
expected=json.loads(Path('/tmp/epic1448-client-evidence/e1448s.json').read_text())
if not os.path.ismount(alias):
 alias.mkdir(exist_ok=True)
 subprocess.run(['mount','-t','nfs4','-o','ro,vers=4.1,proto=tcp','10.10.13.235:/epic1438-1448-static-20261026-nfs',str(alias)],check=True,timeout=30)
os.sync()
Path('/proc/sys/vm/drop_caches').write_text('3')
for name,root in [('primary',primary),('alias-read-only',alias)]:
 path=root/'epic1448-preserve.bin'
 stat=path.stat()
 actual={'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'uid':stat.st_uid,'gid':stat.st_gid,'mode':stat.st_mode&0o777}
 assert all(actual[k]==expected[k] for k in actual),(name,actual,expected)
 print('PRESERVED',name)
 print('SHA256',actual['sha256'])
 print('UID_GID_MODE',actual['uid'],actual['gid'],oct(actual['mode']))
print('PRIMARY_ALIAS_DATA_PERMISSION_PRESERVED')
