import json,sys
from remote import root,sql
for c in ['31','32']:
 reg=json.loads((root/(c+'-fixtures.json')).read_text());owner=next(x for x in reg['accounts']if x['scenario']=='owner');vols=[x for x in reg['volumes']if not x.get('sourceDeleted')and x.get('account')==owner['name']and not x.get('projectid')];ids=','.join("'"+x['id']+"'"for x in vols)
 original=sql(c,"SELECT JSON_OBJECT('uuid',uuid,'created',created) FROM volumes WHERE uuid IN ("+ids+") ORDER BY id;");assert len(original)==24
 sql(c,"UPDATE volumes SET created='2026-10-24 10:00:00' WHERE uuid IN ("+ids+");SELECT JSON_OBJECT('updated',ROW_COUNT());")
 rows=sql(c,"SELECT JSON_OBJECT('uuid',uuid,'created',created) FROM volumes WHERE uuid IN ("+ids+") ORDER BY created DESC,id DESC;")
 (root/(c+'-candidate-ties.json')).write_text(json.dumps({'original':original,'conditionedCreated':'2026-10-24 10:00:00','onlyTaskOwned':True,'expectedPages':[[x['uuid']for x in rows[:20]],[x['uuid']for x in rows[20:]]]},indent=2))
 print(c,'conditioned24 owner volume timestamps')
