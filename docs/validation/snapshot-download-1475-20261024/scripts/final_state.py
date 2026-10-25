# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with this
# work for additional information regarding copyright ownership. The ASF
# licenses this file to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance with the License.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations.
import json,sys,re
from pathlib import Path
from remote import root,remote,sql
for c in ['31','32']:
 reg=json.loads((root/(c+'-fixtures.json')).read_text());prefix=reg['prefix'];before=json.loads((root/(c+'-before-cleanup-db.json')).read_text());initial=json.loads((root/(c+'-before-download-db.json')).read_text())
 ids=[x['id'] for x in reg['snapshots']];assert all(re.fullmatch('[a-f0-9-]{36}',x) for x in ids);selector='s.uuid IN ('+','.join("'"+x+"'" for x in ids)+')'
 r={}
 r['snapshots']=sql(c,"SELECT JSON_OBJECT('active',COUNT(*)) FROM snapshots s WHERE "+selector+" AND s.status<>'Destroyed' AND s.removed IS NULL;")[0]['active']
 for table,name,extra in [('volumes','name',"AND state<>'Expunged'"),('vm_instance','display_name','')]:r[table]=sql(c,"SELECT JSON_OBJECT('active',COUNT(*)) FROM "+table+" WHERE "+name+" LIKE '"+prefix+"%' AND removed IS NULL "+extra+";")[0]['active']
 r['snapshotReferences']=sql(c,"SELECT JSON_OBJECT('active',COUNT(*)) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE "+selector+" AND r.state NOT IN ('Destroyed','Failed');")[0]['active']
 r['downloadReferences']=sql(c,"SELECT JSON_OBJECT('active',COUNT(*)) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE "+selector+" AND r.download_url IS NOT NULL;")[0]['active']
 r['counts']=sql(c,"SELECT JSON_OBJECT('type',type,'count',count) FROM resource_count WHERE account_id=2 AND type IN ('snapshot','secondary_storage');")
 r['usage']=sql(c,"SELECT JSON_OBJECT('name',s.name,'uuid',s.uuid,'count',COUNT(*)) FROM usage_event e JOIN snapshots s ON s.id=e.resource_id WHERE e.type='SNAPSHOT.DELETE' AND "+selector+" GROUP BY s.id;")
 assert all(r[x]==0 for x in ['snapshots','volumes','vm_instance','snapshotReferences','downloadReferences']),r
 paths=sorted(set('/nfs/secondary/snapshots/'+x['path'].split('snapshots/',1)[1] for x in before['snapshots'] if x.get('role')=='Image' and x.get('path')))
 code="import json,sys;from pathlib import Path;print(json.dumps([{'path':s,'exists':Path(s).exists()} for s in sys.argv[1:]]))"
 r['secondary']=json.loads(remote(c,['sudo','-n','python3','-c',code]+paths));assert not any(x['exists'] for x in r['secondary'])
 primary=set()
 for v in before['volumes']+initial['volumes']:
  primary.add(v['uuid'])
  if v['path']:primary.add(v['path'].rsplit('/',1)[-1])
 if c=='32':
  images=json.loads(remote(c,['rbd','ls','rbd','--format','json'],'1'));r['primaryObjects']=[x for x in images if x in primary or x.startswith(prefix)]
  trash=json.loads(remote(c,['rbd','trash','ls','rbd','--format','json'],'1'));r['trash']=[x for x in trash if x.get('name') in primary or x.get('name','').startswith(prefix)]
 else:
  names=[]
  for p in sorted(primary):
   if names:names.append('-o')
   names.extend(['-name',p])
  r['primaryObjects']=remote(c,['find','/mnt/glue-gfs','-maxdepth','4','(']+names+[')','-print'],'1').splitlines();r['trash']=[]
 r['domains']={}
 for host in ['1','2','3']:
  domains=remote(c,['virsh','list','--all','--name'],host).splitlines();instances=[x['instance'] for x in before['vms']];r['domains'][host]=[x for x in domains if x in instances]
 assert not r['primaryObjects'] and not r['trash'] and not any(r['domains'].values()),r
 assert len(r['usage'])==len(ids) and all(x['count']==1 for x in r['usage']),r['usage']
 urls=set()
 for file in ['ui-initial-observations.json','ui-raw-observations.json']:
  u=json.loads((root/file).read_text())[c].get('url')
  if u:urls.add(u)
 for p in list(root.glob(c+'-extract-*.json'))+[root/(c+'-baseline-extract.json'),root/(c+'-korean-name.json')]:
  value=json.loads(p.read_text());body=value.get('job',{}).get('jobresult',value);body=body.get('extractsnapshotresponse',body);url=body.get('snapshot',{}).get('url')
  if url:urls.add(url)
 code="import urllib.request,urllib.error,json,sys;out=[]\nfor url in sys.argv[1:]:\n try:r=urllib.request.urlopen(urllib.request.Request(url,method='HEAD'));status=r.status\n except urllib.error.HTTPError as e:status=e.code\n out.append({'url':url,'status':status})\nprint(json.dumps(out))"
 r['urls']=json.loads(remote(c,['python3','-c',code]+sorted(urls)));assert all(x['status']==404 for x in r['urls']),r['urls']
 ip='10.10.'+c+('.8' if c=='31' else '.7')
 code="import json,sys;from pathlib import Path;urls=json.loads(sys.argv[1]);out=[]\nfor u in urls:\n p=Path('/var/www/html/userdata')/u.split('/userdata/',1)[1].split('/')[0];out.append({'path':str(p),'exists':p.exists()})\nprint(json.dumps(out))"
 import shlex
 r['exportDirs']=json.loads(remote(c,['sudo','-n','ssh','-p','3922','-i','/var/cloudstack/management/.ssh/id_rsa','-o','UserKnownHostsFile=/root/snapshot1474-ssvm-known-hosts','-o','StrictHostKeyChecking=yes','root@'+ip,shlex.join(['python3','-c',code,json.dumps(sorted(urls))])]))
 assert not any(x['exists'] for x in r['exportDirs'])
 (root/(c+'-final-state.json')).write_text(json.dumps(r,indent=2));print(c,'API/DB/files/images/trash/domains/exports zero; all URLs404; DELETE events once')
