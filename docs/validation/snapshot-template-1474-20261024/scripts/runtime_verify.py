import json,hashlib,sys
from pathlib import Path
from remote import root,remote
repo=Path('/home/ablecloud/work/dhslove/cloud-epic-1473-snapshots')
expected=hashlib.sha256((repo/'core/target/classes/org/apache/cloudstack/storage/to/SnapshotObjectTO.class').read_bytes()).hexdigest()
meta=hashlib.sha256((repo/'services/secondary-storage/server/target/classes/org/apache/cloudstack/storage/formatinspector/SnapshotImageMetadata.class').read_bytes()).hexdigest()
for c in ['31','32']:
 code="import json,zipfile,hashlib;from pathlib import Path;p=next(Path('/usr/share/cloudstack-management/lib').glob('cloudstack-*.jar'));z=zipfile.ZipFile(p);w=Path('/usr/share/cloudstack-management/webapp');print(json.dumps({'snapshotClassSha256':hashlib.sha256(z.read('org/apache/cloudstack/storage/to/SnapshotObjectTO.class')).hexdigest(),'jarSha256':hashlib.sha256(p.read_bytes()).hexdigest(),'WEB-INF':(w/'WEB-INF').is_dir(),'configSha256':hashlib.sha256((w/'config.json').read_bytes()).hexdigest(),'indexSha256':hashlib.sha256((w/'index.html').read_bytes()).hexdigest()}))"
 proof=json.loads(remote(c,['sudo','-n','python3','-c',code]));assert proof['snapshotClassSha256']==expected and proof['WEB-INF']
 proof['mold']=remote(c,['sudo','-n','systemctl','is-active','mold']);proof['http']=remote(c,['curl','-s','-o','/dev/null','-w','%{http_code}','http://10.10.'+c+'.10:8080/client/']);assert proof['mold']=='active' and proof['http']=='200'
 proof['agents']=[]
 for h in ['1','2','3']:
  code="import json,zipfile,hashlib;from pathlib import Path;p=next(Path('/usr/share/cloudstack-agent/lib').glob('cloud-secondary-storage*.jar'));z=zipfile.ZipFile(p);print(json.dumps({'metadataSha256':hashlib.sha256(z.read('org/apache/cloudstack/storage/formatinspector/SnapshotImageMetadata.class')).hexdigest()}))"
  a=json.loads(remote(c,['python3','-c',code],h));assert a['metadataSha256']==meta;a['host']=h;proof['agents'].append(a)
 (root/(c+'-runtime-verified.json')).write_text(json.dumps(proof,indent=2));print(c,'runtime class hashes, WEB-INF, Mold/client verified',flush=True)
