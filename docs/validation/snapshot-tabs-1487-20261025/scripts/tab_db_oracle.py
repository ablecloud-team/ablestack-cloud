import json, sys, re
from remote import root, sql
c=sys.argv[1];after=len(sys.argv)>2 and sys.argv[2]=='after';r=json.loads((root/(c+'-fixtures.json')).read_text())
ids=[x['id']for x in r['snapshots']]+[x['id']for x in r['vms']]
assert all(re.fullmatch('[a-f0-9-]{36}',x)for x in ids)
quoted=','.join("'"+x+"'"for x in ids)
notes=sql(c,"SELECT JSON_OBJECT('id',uuid,'entityid',entity_uuid,'entitytype',entity_type,'text',annotation,'userid',user_uuid,'adminsonly',admins_only,'removed',removed) FROM annotations WHERE entity_uuid IN ("+quoted+") ORDER BY id DESC")
events=sql(c,"SELECT JSON_OBJECT('id',e.uuid,'type',e.type,'state',e.state,'resourceid',s.uuid,'resourcetype',e.resource_type,'description',e.description,'level',e.level,'created',e.created,'accountId',e.account_id,'resourceAccountId',s.account_id,'account',a.account_name,'domainid',d.uuid) FROM event e JOIN snapshots s ON s.id=e.resource_id JOIN account a ON a.id=e.account_id JOIN domain d ON d.id=e.domain_id WHERE e.resource_type='Snapshot' AND s.uuid IN ("+quoted+") ORDER BY e.id DESC")
out={'notes':notes,'events':events}
if not after:assert len([x for x in notes if x['removed']is None])==33
owner=next(x for x in r['snapshots']if x['scenario']=='owner')
if not after:assert len([x for x in events if x['resourceid']==owner['id']])==30
(root/(c+('-tab-db-after-ui.json'if after else '-tab-db-before.json'))).write_text(json.dumps(out,indent=2));print(c,'Independent tab DB inventory',len(notes),'notes;',len(events),'snapshot events')
