import json,sys
from remote import root,remote,sql
for c in ['31','32']:
 reg=json.loads((root/(c+'-fixtures.json')).read_text());results=[]
 allvols=sql(c,"SELECT JSON_OBJECT('id',uuid,'scenario',name) FROM volumes WHERE name LIKE '"+reg['prefix']+"%' AND name LIKE '%restor%' AND removed IS NULL AND state='Ready';")
 for v in allvols:
  if 'restor' not in v['scenario']:continue
  row=sql(c,"SELECT JSON_OBJECT('name',name,'path',path,'state',state,'removed',removed,'instance',instance_id) FROM volumes WHERE uuid='"+v['id']+"';")[0]
  assert row['name'].startswith(reg['prefix']) and row['state']=='Ready' and not row['removed'] and not row['instance']
  image='/mnt/glue-gfs/'+row['path'] if c=='31' else 'rbd:rbd/'+row['path']
  out=remote(c,['timeout','30','qemu-io','-r','-f','qcow2' if c=='31' else 'raw','-c','read -P 0x5a 0 1M','-c','read -P 0xa5 1023M 1M',image],1)
  results.append({'id':v['id'],'scenario':v['scenario'],'image':image,'patterns':['0x5a@0/1MiB','0xa5@1023MiB/1MiB'],'verified':True,'output':out})
 ts=sql(c,"SELECT JSON_OBJECT('uuid',t.uuid,'name',t.name,'format',t.format,'path',r.install_path,'size',r.size) FROM vm_template t JOIN template_store_ref r ON r.template_id=t.id WHERE t.name LIKE '"+reg['prefix']+"%' AND t.removed IS NULL AND r.destroyed=0 AND r.download_state='DOWNLOADED';")
 for t in ts:
  if '-root-' in t['name'] or '-before' in t['name']:continue
  image='/nfs/secondary/'+t['path'];out=remote(c,['sudo','-n','timeout','30','qemu-io','-r','-f',t['format'].lower(),'-c','read -P 0x5a 0 1M','-c','read -P 0xa5 1023M 1M',image])
  results.append({'id':t['uuid'],'name':t['name'],'template':True,'image':image,'verified':True,'output':out})
 (root/(c+'-data-verification.json')).write_text(json.dumps(results,indent=2));print(c,'Verified restored volumes and templates:',len(results))
