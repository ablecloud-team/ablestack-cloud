import json,sys,re
from remote import root,sql,remote
c,phase=sys.argv[1:3];r=json.loads((root/(c+'-fixtures.json')).read_text());assert c in ['31','32']
owner=next(x for x in r['snapshots']if x['scenario']=='owner')
if phase in ['created','visibility','deleted']:
 if phase=='created':
  rows=sql(c,"SELECT JSON_OBJECT('id',uuid,'entityid',entity_uuid,'text',annotation,'adminsonly',admins_only,'removed',removed) FROM annotations WHERE entity_uuid='"+owner['id']+"' AND annotation LIKE '"+r['prefix']+"-ui-admin%' AND removed IS NULL")
  assert len(rows)==1 and not rows[0]['adminsonly']
 else:
  before=json.loads((root/(c+'-ui-created-db.json')).read_text());id=before[0]['id'];assert re.fullmatch('[a-f0-9-]{36}',id)
  rows=sql(c,"SELECT JSON_OBJECT('id',uuid,'entityid',entity_uuid,'text',annotation,'adminsonly',admins_only,'removed',removed) FROM annotations WHERE uuid='"+id+"'")
  assert len(rows)==1
  if phase=='visibility':assert rows[0]['adminsonly']==1 and rows[0]['removed']is None
  else:assert rows[0]['removed']is not None
 (root/(c+'-ui-'+phase+'-db.json')).write_text(json.dumps(rows,ensure_ascii=False,indent=2));print(c,phase,'independent comment DB PASS')
elif phase=='zone-deleted':
 target=next(x for x in r['snapshots']if x['scenario']=='owner-next')
 rows=sql(c,"SELECT JSON_OBJECT('snapshot',s.uuid,'status',s.status,'activeRefs',(SELECT COUNT(*) FROM snapshot_store_ref WHERE snapshot_id=s.id AND state<>'Destroyed'),'sourceVolumeActive',(SELECT COUNT(*) FROM volumes WHERE id=s.volume_id AND removed IS NULL)) FROM snapshots s WHERE s.uuid IN ('"+target['id']+"','"+owner['id']+"')")
 result=next(x for x in rows if x['snapshot']==target['id']);kept=next(x for x in rows if x['snapshot']==owner['id'])
 assert result['status']=='Destroyed'and result['activeRefs']==0 and result['sourceVolumeActive']==1
 assert kept['status']=='BackedUp'and kept['activeRefs']==(1 if c=='31'else 2)
 before=json.loads((root/(c+'-db-before.json')).read_text());paths=['/nfs/secondary/'+x['path']for x in before['references']if x['snapshot']==target['id']and x['role']=='Image'];assert len(paths)==1
 left=json.loads(remote(c,['sudo','-n','python3','-c','import json,pathlib;print(json.dumps([p for p in '+repr(paths)+' if pathlib.Path(p).exists()]))']));assert not left
 (root/(c+'-ui-zone-deleted-db.json')).write_text(json.dumps({'snapshots':rows,'removedSecondaryPaths':paths,'remainingSecondary':left},indent=2));print(c,'UI Zone deletion DB/backup terminal, other snapshot/source volume preserved')
else:raise ValueError('Unknown explicit verification phase')
