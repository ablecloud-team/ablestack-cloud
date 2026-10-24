import json,os,sys
from remote import root,sql,remote
for c in ['31','32']:
 reg=json.loads((root/(c+'-fixtures.json')).read_text());ids=','.join("'"+s['id']+"'"for s in reg['snapshots']+reg.get('retiredSnapshots',[]));vid=','.join("'"+s['id']+"'"for s in reg['volumes'])
 refs=sql(c,"SELECT JSON_OBJECT('uuid',s.uuid,'snapshot',s.id,'volume',s.volume_id,'store',r.store_id,'role',r.store_role,'state',r.state,'path',r.install_path) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.uuid IN ("+ids+");")
 vols=sql(c,"SELECT JSON_OBJECT('id',id,'uuid',uuid,'path',path,'state',state,'removed',removed,'pool',pool_id) FROM volumes WHERE uuid IN ("+vid+");")
 snaps=sql(c,"SELECT JSON_OBJECT('id',id,'uuid',uuid,'name',name,'type',snapshot_type,'state',status,'volume',volume_id,'removed',removed) FROM snapshots WHERE uuid IN ("+ids+");")
 policies=sql(c,"SELECT JSON_OBJECT('count',COUNT(*)) FROM snapshot_policy p JOIN volumes v ON v.id=p.volume_id WHERE v.uuid IN ("+vid+");")[0]['count'];assert policies==0
 (root/(c+'-cleanup-paths.json')).write_text(json.dumps({'references':refs,'volumes':vols},indent=2));(root/(c+'-db-fixtures.json')).write_text(json.dumps({'snapshots':snaps,'volumes':vols,'references':refs,'activePolicies':policies},indent=2))
 hosts=sql(c,"SELECT JSON_OBJECT('id',id,'uuid',uuid,'guid',guid,'status',status,'ip',private_ip_address,'type',type) FROM host WHERE removed IS NULL ORDER BY id;");(root/(c+'-hosts-before.json')).write_text(json.dumps(hosts))
 brief={'domain':reg['domain']['id'],'path':reg['domain']['path'],'owner':next(x for x in reg['accounts']if x['scenario']=='owner')['name'],'project':reg['project']['id'],'volume':next(x for x in reg['volumes']if x['scenario']=='owner')['id'],'retained':next(x for x in reg['volumes']if x['scenario']=='retained')['id'],'zone':reg['volumes'][0]['zoneid']};print(c,brief)
 print(c,len(snaps),'snapshots including retired;',len(refs),'refs;',len(vols),'volumes;',policies,'policies')
