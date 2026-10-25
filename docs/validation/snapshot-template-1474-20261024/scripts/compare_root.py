import json,sys
from remote import root,remote,sql
for c in ['31','32']:
 rows=sql(c,"SELECT JSON_OBJECT('name',t.name,'format',t.format,'uuid',t.uuid,'path',r.install_path) FROM vm_template t JOIN template_store_ref r ON r.template_id=t.id WHERE t.name LIKE 'SNAPT1474-"+c+"-root%' AND t.removed IS NULL AND r.destroyed=0;")
 snap=sql(c,"SELECT JSON_OBJECT('path',r.install_path) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.name='SNAPT1474-"+c+"-root' AND r.store_role='Image' AND r.state='Ready';")[0]['path'];snap='/nfs/secondary/snapshots/'+snap.split('snapshots/',1)[1]
 results=[]
 for t in rows:
  path='/nfs/secondary/'+t['path'];out=remote(c,['sudo','-n','timeout','180','qemu-img','compare','-f',t['format'].lower(),'-F','raw',path,snap])
  assert 'Images are identical' in out
  results.append({'template':t['uuid'],'format':t['format'],'sourceSnapshot':snap,'path':path,'result':out,'identical':True})
 (root/(c+'-root-full-compare.json')).write_text(json.dumps(results,indent=2));print(c,'ROOT full logical image compare PASS',len(results),flush=True)
