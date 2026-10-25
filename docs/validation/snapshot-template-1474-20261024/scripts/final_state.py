import json,sys
from remote import root,remote,sql
for c in ['31','32']:
 prefix='SNAPT1474-'+c;before=json.loads((root/(c+'-all-combinations-db.json')).read_text());jobs=json.loads((root/(c+'-jobs-db.json')).read_text());tids=sorted(set([x['id'] for x in before['templates']]+[x['instanceid'] for x in jobs if x['instancetype']=='Template' and x['instanceid']]))
 r={}
 for table,name,extra in [('snapshots','name',"AND status<>'Destroyed'"),('volumes','name',"AND state<>'Expunged'"),('vm_instance','display_name',''),('vm_template','name','')]:
  r[table]=sql(c,"SELECT JSON_OBJECT('active',COUNT(*)) FROM "+table+" WHERE "+name+" LIKE '"+prefix+"%' AND removed IS NULL "+extra+";")[0]['active']
 r['snapshotReferences']=sql(c,"SELECT JSON_OBJECT('active',COUNT(*)) FROM snapshot_store_ref r JOIN snapshots s ON s.id=r.snapshot_id WHERE s.name LIKE '"+prefix+"%' AND r.state NOT IN ('Destroyed','Failed');")[0]['active']
 r['primaryTemplateCache']=sql(c,"SELECT JSON_OBJECT('id',id,'template',template_id,'pool',pool_id,'path',install_path,'local',local_path,'state',state,'download',download_state,'gc',marked_for_gc) FROM template_spool_ref WHERE template_id IN ("+','.join(map(str,tids))+");")
 r['usage']=sql(c,"SELECT JSON_OBJECT('name',s.name,'count',COUNT(*)) FROM usage_event e JOIN snapshots s ON s.id=e.resource_id WHERE e.type='SNAPSHOT.DELETE' AND s.name LIKE '"+prefix+"%' GROUP BY s.id;")
 r['counts']=sql(c,"SELECT JSON_OBJECT('type',type,'count',count) FROM resource_count WHERE account_id=2 AND type IN ('snapshot','secondary_storage');")
 assert all(r[x]==0 for x in ['snapshots','volumes','vm_instance','vm_template','snapshotReferences']),r
 paths=[]
 for x in before['snapshots']:
  if x['role']=='Image' and x['path']:paths.append('/nfs/secondary/snapshots/'+x['path'].split('snapshots/',1)[1])
 dirs=['/nfs/secondary/template/tmpl/2/'+str(x) for x in tids]
 code="import json,sys;from pathlib import Path;out=[]\nfor s in sys.argv[1:]:\n p=Path(s);o={'path':s,'exists':p.exists(),'directory':p.is_dir()};o['children']=[{'name':x.name,'file':x.is_file(),'size':x.stat().st_size} for x in p.iterdir()] if p.is_dir() else [];out.append(o)\nprint(json.dumps(out))"
 r['secondary']=json.loads(remote(c,['sudo','-n','python3','-c',code]+sorted(set(paths+dirs))))
 primary=[]
 for v in before['volumes']:
  primary.append(v['uuid'])
  if v['path']:primary.append(v['path'].rsplit('/',1)[-1])
 for t in before['templates']:primary.append(t['uuid'])
 if c=='32':
  images=json.loads(remote(c,['rbd','ls','rbd','--format','json'],'1'));r['primaryObjects']=[x for x in images if x in primary or x.startswith(prefix)]
  trash=json.loads(remote(c,['rbd','trash','ls','rbd','--format','json'],'1'));r['trash']=[x for x in trash if x.get('name') in primary or x.get('name','').startswith(prefix)]
 else:
  names=[]
  for p in sorted(set(primary)):
   if names:names.append('-o')
   names.extend(['-name',p])
  r['primaryObjects']=remote(c,['find','/mnt/glue-gfs','-maxdepth','4','(']+names+[')','-print'],'1').splitlines()
 r['domains']={}
 for host in ['1','2','3']:
  domains=remote(c,['virsh','list','--all','--name'],host).splitlines();instances=[x['instance'] for x in before['vms']];r['domains'][host]=[x for x in domains if x in instances]
 (root/(c+'-final-state.json')).write_text(json.dumps(r,indent=2));print(c,'active DB 0, secondary leftovers',sum(x['exists'] for x in r['secondary']),'primary',r['primaryObjects'],'caches',r['primaryTemplateCache'])
