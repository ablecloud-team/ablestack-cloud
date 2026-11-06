from pathlib import Path
import json,hashlib
root=Path('/srv/ablestack-storage/volumes/17f958c0-896d-442d-a933-643da72a205f/export/e1451')
profiles=[]
for phase,mode in [('r4-dual-name','NAME_DOMAIN'),('r4-dual-pre-vm','NUMERIC'),('r4-dual-post-vm','NUMERIC')]:
 for client in ['c1','c2']:
  for version in [3,4]:
   port=2049
   file=root/'evidence'/(client+'-'+phase)/f'epic1451-{client}-{mode.lower()}-root-rw-{version}-{port}-10.10.13.192.json'
   data=json.loads(file.read_text())
   assert data['identity']==mode and data['client']==client and data['port']==port and data['server']=='10.10.13.192'
   assert len(data['cases'])==4 and all(c['read'] and c['write'] for c in data['cases'])
   if mode=='NUMERIC' or version==3:assert all(c['clientUid']==c['expectedServerUid']  for c in data['cases'])
   profiles.append({'phase':phase,'file':str(file.relative_to(root)),'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'result':data})
print(json.dumps({'profileCount':len(profiles),'uidCases':sum(len(p['result']['cases']) for p in profiles),'profiles':profiles}))
