from pathlib import Path
import json,tempfile,subprocess,hashlib,shutil,time,os
assert Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()=="d9c423e9-ce89-3ba9-9cd8-f48cdb791da2"
root=Path(tempfile.mkdtemp(prefix="epic1458-contract-",dir="/run"))
fixture={'lsblk': [{'name': 'sda', 'path': '/dev/sda', 'type': 'disk', 'size': 5242880000, 'serial': '42dcf2e2c14f448aa886', 'wwn': None, 'fstype': None, 'uuid': None, 'maj:min': '8:0', 'pkname': None, 'children': [{'name': 'sda1', 'path': '/dev/sda1', 'type': 'part', 'size': 398458880, 'serial': None, 'wwn': None, 'fstype': 'ext2', 'uuid': '02a28a52-ae70-46b5-8f12-3636640c9a99', 'maj:min': '8:1', 'pkname': 'sda'}, {'name': 'sda2', 'path': '/dev/sda2', 'type': 'part', 'size': 1024, 'serial': None, 'wwn': None, 'fstype': None, 'uuid': None, 'maj:min': '8:2', 'pkname': 'sda'}, {'name': 'sda5', 'path': '/dev/sda5', 'type': 'part', 'size': 510656512, 'serial': None, 'wwn': None, 'fstype': 'swap', 'uuid': 'c30f6d3c-fe8f-41df-99b0-52cb823c014c', 'maj:min': '8:5', 'pkname': 'sda'}, {'name': 'sda6', 'path': '/dev/sda6', 'type': 'part', 'size': 4329570304, 'serial': None, 'wwn': None, 'fstype': 'ext4', 'uuid': '91b8366d-a5de-42ad-a420-2e26aa755c3d', 'maj:min': '8:6', 'pkname': 'sda'}]}, {'name': 'sdb', 'path': '/dev/sdb', 'type': 'disk', 'size': 10737418240, 'serial': 'e070a0aadbf548c083e0', 'wwn': None, 'fstype': 'xfs', 'uuid': '9c8ddfe9-3e23-46f0-9476-5c794b004841', 'maj:min': '8:16', 'pkname': None}, {'name': 'sr0', 'path': '/dev/sr0', 'type': 'rom', 'size': 1073741312, 'serial': 'QM00003', 'wwn': None, 'fstype': None, 'uuid': None, 'maj:min': '11:0', 'pkname': None}, {'name': 'sr1', 'path': '/dev/sr1', 'type': 'rom', 'size': 1073741312, 'serial': 'QM00004', 'wwn': None, 'fstype': None, 'uuid': None, 'maj:min': '11:1', 'pkname': None}], 'statvfs': {'totalBytes': 10670309376, 'usedBytes': 108703744, 'availableBytes': 10561605632, 'device': 2064, 'inode': 128}, 'device': {'devicePath': '/dev/sdb', 'diskPath': '/dev/sdb', 'diskName': 'sdb', 'filesystem': 'xfs', 'filesystemUuid': '9c8ddfe9-3e23-46f0-9476-5c794b004841', 'serial': 'e070a0aadbf548c083e0', 'matchedBy': 'VOLUME_SERIAL'}, 'boot': 'e92456e6-cce5-4cb8-bab7-d8ffa2961c2d'}
handler='\ncapacity_design_only() {\npython3 - "$1" "$MONITOR_CACHE_DIR" <<\'CAPACITY_PROBE\'\nimport json,sys,time,os\nsys.path.insert(0,os.environ["ABLESTACK_STORAGE_HELPER_ROOT"])\nfrom volume_identity import resolve_volume_device,VolumeIdentityError\nrequest=json.load(open(sys.argv[1]));cache=json.load(open(sys.argv[2]+"/capacity.json"))\nif request.get("capacityOnly") is not True or not isinstance(request.get("volumes"),list) or len(request["volumes"])>1024:raise ValueError("INVALID_CAPACITY_REQUEST")\nif cache.get("capacitySnapshotVersion")!=1:\n print(json.dumps({"success":True,"status":"UNSUPPORTED","capacitySnapshotVersion":1}));raise SystemExit()\nstatus="STALE"if time.time()-cache["generatedEpoch"]>cache["staleAfterSeconds"]else"CURRENT"\nobservations=[]\nfor volume in request["volumes"]:\n try:\n  device=resolve_volume_device(cache["blockDevices"],volume_uuid=volume["uuid"],expected_size=volume["size"],allow_blank_size_fallback=False)\n  filesystem=next((x for x in cache["fileSystems"] if x["uuid"]==device.get("filesystemUuid")and x["devicePath"]==device["devicePath"]),None)\n  observations.append({"volumeUuid":volume["uuid"],"mappingStatus":"EXACT","filesystem":filesystem})\n except VolumeIdentityError:\n  observations.append({"volumeUuid":volume["uuid"],"mappingStatus":"UNMAPPED","filesystem":None})\nprint(json.dumps({"success":True,"status":status,"capacitySnapshotVersion":1,"observedAt":cache["generatedAt"],"bootId":cache["bootId"],"observations":observations}))\nCAPACITY_PROBE\n}\n'
checks=[]
def passed(name):checks.append({'name':name,'passed':True})
try:
 original=Path('/opt/ablestack/storage-runtime/current/ablestack-storagectl').read_text()
 source_hash=hashlib.sha256(original.encode()).hexdigest()
 base=original.replace('LOG_FILE="/var/log/ablestack-storagectl.log"','LOG_FILE="'+str(root/'probe.log')+'"')
 baseline=root/'baseline';baseline.write_text(base);baseline.chmod(0o700)
 patched=base.replace('runtime_inventory() {',handler+'\nruntime_inventory() {',1)
 old='  inventory)\n    log_command "$command" ""\n    runtime_inventory\n    ;;'
 new='  inventory)\n    payload="${2:-}"\n    if [[ -n "$payload" && "$(python3 -c \'import json,sys;print(json.load(open(sys.argv[1])).get("capacityOnly") is True)\' "$payload")" == "True" ]]; then\n      capacity_design_only "$payload"\n    else\n      log_command "$command" ""\n      runtime_inventory\n    fi\n    ;;'
 assert old in patched;patched=patched.replace(old,new,1)
 cli=root/'candidate';cli.write_text(patched);cli.chmod(0o700)
 cache=root/'cache';cache.mkdir()
 now=time.time();ordinary={'success':True,'status':'ok','generatedEpoch':now,'staleAfterSeconds':100,'designOrdinaryMarker':True}
 (cache/'inventory.json').write_text(json.dumps(ordinary))
 value={'schemaVersion':1,'capacitySnapshotVersion':1,'generatedAt':'design-observation','generatedEpoch':now,'staleAfterSeconds':15,'bootId':fixture['boot'],'blockDevices':fixture['lsblk'],'fileSystems':[{'uuid':fixture['device']['filesystemUuid'],'devicePath':fixture['device']['devicePath'],'usedBytes':fixture['statvfs']['usedBytes'],'totalBytes':fixture['statvfs']['totalBytes']}]}
 (cache/'capacity.json').write_text(json.dumps(value))
 request=root/'request.json';request.write_text(json.dumps({'capacityOnly':True,'volumes':[{'uuid':'e070a0aa-dbf5-48c0-83e0-09f874446277','size':10737418240}]}))
 empty=root/'empty.json';empty.write_text('{}')
 env=dict(os.environ,ABLESTACK_STORAGE_MONITOR_CACHE_DIR=str(cache))
 def call(path,payload):
  r=subprocess.run([str(path),'inventory',str(payload)],capture_output=True,text=True,env=env,timeout=15)
  assert r.returncode==0,r.stderr;return json.loads(r.stdout)
 ordinary_out=call(cli,empty);assert ordinary_out['designOrdinaryMarker'];passed('existing empty inventory payload stays on original cached code path')
 result=call(cli,request);assert result['status']=='CURRENT'and result['capacitySnapshotVersion']==1;assert result['observations'][0]['filesystem']['usedBytes']==fixture['statvfs']['usedBytes'];passed('optional capacityOnly payload reaches proposed read-only branch through existing inventory argv')
 assert call(baseline,request)['designOrdinaryMarker'];passed('old runtime safely ignores optional payload and can be detected as unsupported')
 value['generatedEpoch']=now-1000;(cache/'capacity.json').write_text(json.dumps(value));assert call(cli,request)['status']=='STALE';passed('capacity-only read retains stale status without live inventory fallback')
 value['generatedEpoch']=time.time();value['fileSystems'][0]['usedBytes']=0;(cache/'capacity.json').write_text(json.dumps(value));zero=call(cli,request);assert zero['observations'][0]['filesystem']['usedBytes']==0;passed('measured zero stays numeric zero instead of becoming missing or physical usage')
 assert source_hash==hashlib.sha256(Path('/opt/ablestack/storage-runtime/current/ablestack-storagectl').read_bytes()).hexdigest()
 print(json.dumps({'success':True,'count':len(checks),'checks':checks,'wire':result,'productCodeChanged':False,'copiesOnly':True}))
finally:
 assert root.parent==Path('/run')and root.name.startswith('epic1458-contract-');shutil.rmtree(root)
