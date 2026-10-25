import sys,json
from pathlib import Path
from remote import root,remote
c=sys.argv[1];r=json.loads((root/(c+'-fixtures.json')).read_text());before=json.loads((root/(c+'-db-before.json')).read_text());after=json.loads((root/(c+'-db-ui-deleted.json')).read_text());scenarios=['owner-next','other','vmdata','deleted'];ids=[next(s['id']for s in r['snapshots']if s['scenario']==x)for x in scenarios]
rows=[]
for sid in ids:
 s=next(x for x in after['snapshots']if x['id']==sid);refs=[x for x in after['references']if x['snapshot']==sid and x['state']!='Destroyed'];assert s['state']=='Destroyed'and not refs
 paths=['/nfs/secondary/'+x['path']for x in before['references']if x['snapshot']==sid and x['role']=='Image'];assert len(paths)==1
 for p in paths:remote(c,['sudo','-n','test','!','-e',p])
 rows.append({'snapshot':sid,'state':s['state'],'activeReferences':len(refs),'removedBackupFiles':paths})
primary=[]
if c=='32':
 live=json.loads(remote(c,['rbd','ls','--format','json','rbd'],1));archived=next(x['id']for x in r['snapshots']if x['scenario']=='ui-source');refs=[x for x in before['references']if x['snapshot']in ids and x['role']=='Primary']+[x for x in json.loads((root/(c+'-db-created.json')).read_text())['references']if x['snapshot']==archived and x['role']=='Primary']
 for ref in refs:
  parts=ref['path'].split('/');assert len(parts)==3 and parts[0]=='rbd'
  image=parts[1];snap=parts[2]
  snaps=json.loads(remote(c,['rbd','snap','ls','rbd/'+image,'--format','json'],1))if image in live else[]
  assert all(x['name']!=snap for x in snaps)
  primary.append({'snapshot':ref['snapshot'],'image':image,'removedSnapshot':snap,'imageStillPresent':image in live})
out={'deleted':rows,'primaryRemoved':primary,'status':'PASS'};(root/(c+'-ui-delete-oracle.json')).write_text(json.dumps(out,indent=2));print(c,'four UI deletions: DB terminal, backup files absent;',len(primary),'RBD snapshots absent')
