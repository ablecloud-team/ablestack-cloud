import json,sys
from remote import root,remote,sql
for c in ['31','32']:
 expected={}
 for mode in ['raw','root-raw','restore']:
  x=json.loads((root/(c+'-injection-'+mode+'.json')).read_text());expected[x['path'].rsplit('/',1)[1]]=x['originalSha256']
 script='''import json,sys,hashlib
from pathlib import Path
p=Path('/root/snapshot-template1474-fixture-backups');expected=json.loads(sys.argv[1]);out=[]
assert p.resolve()==Path('/root/snapshot-template1474-fixture-backups')
if p.exists():
 for b in p.iterdir():
  assert b.is_file() and not b.is_symlink() and b.name in expected
  digest=hashlib.sha256(b.read_bytes()).hexdigest();assert digest==expected[b.name];out.append({'name':b.name,'sha256':digest,'bytes':b.stat().st_size});b.unlink()
 p.rmdir()
print(json.dumps({'removedFixtureBackups':out,'directoryExists':p.exists(),'deploymentRollbackBackupsRetained':True}))'''
 out=remote(c,['sudo','-n','python3','-c',script,json.dumps(expected)]);(root/(c+'-fixture-backups-cleanup.json')).write_text(out)
 q="SELECT JSON_OBJECT('snapshot',(SELECT COUNT(*) FROM snapshots WHERE account_id=2 AND removed IS NULL AND status NOT IN ('Error','Destroyed') AND snapshot_type<>7),'secondary_storage',((SELECT COALESCE(SUM(size),0) FROM volumes WHERE account_id=2 AND path IS NULL AND state<>'Allocated' AND removed IS NULL)+(SELECT COALESCE(SUM(r.physical_size),0) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.account_id=2 AND s.removed IS NULL AND r.state='Ready' AND r.store_role='Image')+(SELECT COALESCE(SUM(r.size),0) FROM template_store_ref r JOIN vm_template t ON t.id=r.template_id WHERE t.account_id=2 AND t.removed IS NULL AND r.download_state IN ('DOWNLOADED','DOWNLOAD_IN_PROGRESS') AND r.destroyed=0)));"
 ex=sql(c,q)[0];actual={x['type']:x['count'] for x in json.loads((root/(c+'-final-state.json')).read_text())['counts']};assert ex==actual,(ex,actual)
 (root/(c+'-counters-verified.json')).write_text(json.dumps({'actual':actual,'calculated':ex,'match':True},indent=2));print(c,'fixture backups removed; counters consistent')
