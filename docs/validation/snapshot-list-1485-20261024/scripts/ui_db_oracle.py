import json,sys,re
from remote import root,sql
c=sys.argv[1];r=json.loads((root/(c+'-fixtures.json')).read_text());prefix=r['prefix'];delta=json.loads((root/(c+'-ui-delta.json')).read_text())
assert len(r['snapshots'])==27
rows=sql(c,"SELECT JSON_OBJECT('id',s.id,'uuid',s.uuid,'name',s.name,'size',s.size,'status',s.status,'removed',s.removed,'volume',v.uuid,'type',s.snapshot_type,'description',s.type_description) FROM snapshots s JOIN volumes v ON v.id=s.volume_id WHERE s.name LIKE '"+prefix+"%' ORDER BY s.id; SELECT JSON_OBJECT('snapshot',s.uuid,'role',r.store_role,'path',r.install_path,'state',r.state,'physical',r.physical_size) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.name LIKE '"+prefix+"%';")
snaps=[x for x in rows if'uuid'in x];refs=[x for x in rows if'snapshot'in x];assert len(snaps)==27
byid={x['uuid']:x for x in snaps};assert byid[delta['uiCreated']['id']]['removed']is None and byid[delta['uiCreated']['id']]['status']=='BackedUp'
assert all(byid[x]['status']=='Destroyed'for x in delta['uiDeleted']);assert len([x for x in snaps if x['removed']is None and x['status']!='Destroyed'])==25
ids=set()
for f in (root/'browser').glob(c+'-*.json'):
 try:d=json.loads(f.read_text())
 except:continue
 if not isinstance(d,dict):continue
 for x in d.get('requests',[]):
  if x['command']=='queryAsyncJobResult'and x['args'].get('jobid'):ids.add(x['args']['jobid'])
  for v in (x.get('response')or{}).values():
   if isinstance(v,dict)and v.get('jobid'):ids.add(v['jobid'])
assert ids and all(re.fullmatch('[a-f0-9-]{36}',x)for x in ids)
jobs=sql(c,"SELECT JSON_OBJECT('id',id,'uuid',uuid,'command',job_cmd,'status',job_status,'resultCode',job_result_code) FROM async_job WHERE uuid IN ("+','.join("'"+x+"'"for x in sorted(ids))+");")
assert len(jobs)==len(ids)and all(x['status']==1 for x in jobs)
assert any(x['command'].endswith('CreateSnapshotCmd')for x in jobs)and len([x for x in jobs if x['command'].endswith('DeleteSnapshotCmd')])>=2
out={'snapshots':snaps,'references':refs,'jobs':jobs,'activeAfterUi':25,'created':delta['uiCreated']['id'],'deleted':delta['uiDeleted']}
(root/(c+'-ui-db-oracle.json')).write_text(json.dumps(out,indent=2));before=json.loads((root/(c+'-db-before.json')).read_text());paths={'references':before['references']+refs,'volumes':before['volumes']};(root/(c+'-cleanup-paths.json')).write_text(json.dumps(paths,indent=2));print(c,'UI create/delete jobs and 27 DB rows agree; 25 active')
