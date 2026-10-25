import json,sys,re
from remote import root,sql
c=sys.argv[1];r=json.loads((root/(c+'-fixtures.json')).read_text());q=lambda xs:','.join("'"+x+"'"for x in xs)
sids=q([s['id']for s in r['snapshots']]);vids=q([v['id']for v in r['volumes']]);vm=q([v['id']for v in r['vms']]);targets=q([v['id']for v in r['targets']])
refs=sql(c,"SELECT JSON_OBJECT('snapshot',s.uuid,'role',r.store_role,'path',r.install_path,'state',r.state) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.uuid IN ("+sids+") AND r.install_path IS NOT NULL;")
vols=sql(c,"SELECT JSON_OBJECT('uuid',v.uuid,'path',v.path,'name',v.name,'state',v.state,'type',v.volume_type) FROM volumes v LEFT JOIN vm_instance vm ON vm.id=v.instance_id WHERE v.uuid IN ("+vids+") OR vm.uuid IN ("+vm+");")
rows=sql(c,"SELECT JSON_OBJECT('id',id,'resourceid',resource_uuid,'type',resource_type,'key',`key`,'value',value,'account',account_id,'domain',domain_id) FROM resource_tags WHERE resource_uuid IN ("+targets+") ORDER BY resource_uuid,`key`,value;")
assert not any(x['key']=='ui-no-receipt'for x in rows)
assert len([x for x in rows if x['key']=='baseline'])==4
assert len([x for x in rows if x['key']=='ui-late-held'])==1
assert not any(x['key']in ['ui-vm','ui-owner','ui-project','ui-create']for x in rows)
ids=set()
for f in (root/'browser').glob(c+'-*.json'):
 try:d=json.loads(f.read_text())
 except:continue
 if not isinstance(d,dict):continue
 for x in d.get('requests',[]):
  if x['command']=='queryAsyncJobResult':ids.add(x['args']['jobid'])
  for v in (x.get('response')or{}).values():
   if isinstance(v,dict)and v.get('jobid'):ids.add(v['jobid'])
assert all(re.fullmatch('[a-f0-9-]{36}',x)for x in ids)
jobs=sql(c,"SELECT JSON_OBJECT('id',id,'uuid',uuid,'command',job_cmd,'status',job_status,'resultCode',job_result_code) FROM async_job WHERE uuid IN ("+q(sorted(ids))+") ORDER BY id;")
assert len(jobs)==len(ids)and all(x['status']in[1,2]for x in jobs)
assert any(x['status']==2 and x['command'].endswith('CreateTagsCmd')for x in jobs)
out={'tags':rows,'jobs':jobs,'jobCount':len(jobs),'baselineCount':4,'noReceiptHasNoTag':True,'snapshotRefs':refs,'volumes':vols}
(root/(c+'-ui-db-oracle.json')).write_text(json.dumps(out,indent=2));(root/(c+'-cleanup-paths.json')).write_text(json.dumps({'references':refs,'volumes':vols},indent=2));print(c,'UI DB jobs',len(jobs),'all terminal, tag rows',len(rows))
