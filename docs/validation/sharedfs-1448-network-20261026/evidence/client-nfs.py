#!/usr/bin/env python3
"""#1448 client-side test helper. Execute explicitly from the Mold UI console."""
import hashlib,json,os,socket,subprocess,sys
from pathlib import Path
mode=sys.argv[1]
state_root=Path('/tmp/epic1448-client-evidence')
state_root.mkdir(mode=0o700,exist_ok=True)
def run(args):
 subprocess.run(args,check=True,timeout=90)
def no_cache():
 os.sync()
 Path('/proc/sys/vm/drop_caches').write_text('3\n')
if mode=='mount':
 server,export,destination=sys.argv[2:5]
 assert export.startswith('/') and destination.startswith('/mnt/e1448')
 dest=Path(destination);dest.mkdir(parents=True,exist_ok=True)
 if not subprocess.run(['mountpoint','-q',destination]).returncode:raise RuntimeError('client mountpoint already mounted; inspect rather than duplicate')
 run(['mount','-t','nfs4','-o','vers=4.1,proto=tcp',server+':'+export,destination])
 marker=dest/'epic1448-preserve.bin';data=bytes(range(256))*32
 marker.write_bytes(data);os.sync();info=marker.stat()
 baseline={'server':server,'export':export,'destination':destination,'path':str(marker),'sha256':hashlib.sha256(data).hexdigest(),'uid':info.st_uid,'gid':info.st_gid,'mode':info.st_mode&0o7777}
 file=state_root/(dest.name+'.json');file.write_text(json.dumps(baseline));file.chmod(0o600)
 no_cache()
 assert hashlib.sha256(marker.read_bytes()).hexdigest()==baseline['sha256']
 print('NFS_MOUNT_WRITE_READ_OK',json.dumps(baseline,sort_keys=True))
elif mode=='verify':
 dest=Path(sys.argv[2]);baseline=json.loads((state_root/(dest.name+'.json')).read_text())
 no_cache();marker=Path(baseline['path']);info=marker.stat()
 observed={'sha256':hashlib.sha256(marker.read_bytes()).hexdigest(),'uid':info.st_uid,'gid':info.st_gid,'mode':info.st_mode&0o7777}
 assert observed=={k:baseline[k] for k in observed},(baseline,observed)
 print('NFS_PRESERVATION_OK',json.dumps(observed,sort_keys=True))
elif mode=='probe':
 for port in map(int,sys.argv[3:]):
  with socket.create_connection((sys.argv[2],port),timeout=5):pass
  print('TCP_OK',sys.argv[2],port)
else:raise ValueError('unsupported test mode')
