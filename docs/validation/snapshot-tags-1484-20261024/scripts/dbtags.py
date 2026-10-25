import json,sys,re
from remote import root,sql
c=sys.argv[1];r=json.loads((root/(c+'-fixtures.json')).read_text());ids=[t['id'] for t in r['targets']];assert all(re.fullmatch('[a-f0-9-]{36}',x)for x in ids);keys=','.join("'"+x+"'"for x in ids)
rows=sql(c,"SELECT JSON_OBJECT('id',id,'resourceid',resource_uuid,'resourcetype',resource_type,'key',`key`,'value',value,'account',account_id,'domain',domain_id) FROM resource_tags WHERE resource_uuid IN ("+keys+") ORDER BY resource_uuid,`key`,value; SELECT JSON_OBJECT('owner',uuid,'account',account_id,'domain',domain_id) FROM snapshots WHERE uuid IN ("+keys+"); SELECT JSON_OBJECT('owner',uuid,'account',account_id,'domain',domain_id) FROM vm_instance WHERE uuid IN ("+keys+");")
owner={x['owner']:x for x in rows if'owner'in x};tags=[x for x in rows if'key'in x]
for x in tags:assert x['account']==owner[x['resourceid']]['account']and x['domain']==owner[x['resourceid']]['domain']
print(json.dumps({'tags':tags,'owners':list(owner.values()),'ownershipConsistent':True}))
