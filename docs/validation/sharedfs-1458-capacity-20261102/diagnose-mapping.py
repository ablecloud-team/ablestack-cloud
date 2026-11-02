from pathlib import Path
import subprocess,json,sys,copy,hashlib
assert Path('/sys/class/dmi/id/product_uuid').read_text().strip().lower()=='d9c423e9-ce89-3ba9-9cd8-f48cdb791da2'
sys.path.insert(0,'/opt/ablestack/storage-runtime/current/lib/ablestack-storage')
from volume_identity import resolve_volume_device,VolumeIdentityError,flatten_devices,token_matches,compact
uuid='e070a0aa-dbf5-48c0-83e0-09f874446277'
devices=json.loads(subprocess.check_output(['lsblk','-b','-J','-o','NAME,PATH,TYPE,SIZE,SERIAL,WWN,FSTYPE,UUID,MAJ:MIN,PKNAME,MOUNTPOINT'],text=True))['blockdevices']
matches=[v for v in flatten_devices(devices)if token_matches(v.get('serial'),[compact(uuid)])and v.get('type')=='disk']
assert len(matches)==1
dev=matches[0]
def observe(rows,identity,source=None,size=10737418240):
 try:return dict(result='RESOLVED',device=resolve_volume_device(rows,volume_uuid=identity,mount_source=source,expected_size=size,allow_blank_size_fallback=False))
 except VolumeIdentityError as e:return dict(result='REJECTED',error=str(e))
baseline=observe(devices,uuid)
mounted=observe(devices,uuid,dev['path'])
wrong=observe(devices,'00000000-1111-2222-3333-444444444444',dev['path'])
missing=copy.deepcopy(devices)
def strip(rows):
 for row in rows:
  row.pop('mountpoint',None);strip(row.get('children',[]))
strip(missing)
previous=observe(missing,uuid)
root=next(row for row in devices if row.get('name')=='sda')
root_result=observe(devices,root['serial'],'/dev/sda6',root['size'])
assert baseline['result']=='REJECTED'and mounted['result']=='RESOLVED'and wrong['result']=='REJECTED'and previous['result']=='RESOLVED'and root_result['result']=='REJECTED'
paths=['/opt/ablestack/storage-runtime/current/ablestack-storagectl','/opt/ablestack/storage-runtime/current/ablestack-storage-monitor']
print(json.dumps(dict(success=True,diagnosisOnly=True,device={k:dev.get(k)for k in ['path','serial','fstype','uuid','mountpoint','size']},withMountpointAndNoSource=baseline,withActualSource=mounted,wrongIdentity=wrong,withoutMountpointField=previous,rootBlocked=root_result,activeHashes={p:hashlib.sha256(Path(p).read_bytes()).hexdigest()for p in paths})))
