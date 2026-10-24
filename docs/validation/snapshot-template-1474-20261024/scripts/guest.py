import json,sys,time,base64
from remote import root,sql,remote
c,scenario,phase=sys.argv[1:4];reg=json.loads((root/(c+'-fixtures.json')).read_text());vm=next(v for v in reg['vms'] if v['scenario']==scenario)
r=sql(c,"SELECT JSON_OBJECT('name',display_name,'instance',instance_name,'host',h.private_ip_address,'state',v.state) FROM vm_instance v JOIN host h ON h.id=v.host_id WHERE v.uuid='"+vm['id']+"';")[0]
assert r['name'].startswith(reg['prefix']) and r['state']=='Running';host=r['host'].split('.')[-1]
def qga(args):return json.loads(remote(c,['timeout','15','virsh','qemu-agent-command',r['instance'],json.dumps(args)],host))
qga({'execute':'guest-ping'})
info=qga({'execute':'guest-info'})['return']
enabled=[x['name'] for x in info['supported_commands'] if x['enabled']]
proof={**r,'vmid':vm['id'],'phase':phase,'scenario':scenario,'guestAgent':True,'enabledCommands':enabled}
for command in ['guest-get-osinfo','guest-get-host-name','guest-get-fsinfo']:
 if command in enabled:proof[command]=qga({'execute':command})['return']
if 'guest-file-open' in enabled and 'guest-file-write' in enabled and 'guest-file-read' in enabled:
 marker='snapshot1474-'+c+'-persisted\n'
 handle=qga({'execute':'guest-file-open','arguments':{'path':'/root/snapshot1474-marker','mode':'w' if phase=='seed' else 'r'}})['return']
 try:
  if phase=='seed':qga({'execute':'guest-file-write','arguments':{'handle':handle,'buf-b64':base64.b64encode(marker.encode()).decode()}})
  else:
   result=qga({'execute':'guest-file-read','arguments':{'handle':handle}})['return']
   value=base64.b64decode(result['buf-b64']).decode();assert value==marker;proof['restoredMarker']=value
 finally:qga({'execute':'guest-file-close','arguments':{'handle':handle}})
else:proof['marker']='Guest file operations disabled; use disk pattern and boot OS/fs evidence separately'
assert proof.get('guest-get-osinfo') and proof.get('guest-get-fsinfo')
(root/(c+'-'+scenario+'-'+phase+'-guest.json')).write_text(json.dumps(proof,indent=2));print(c,scenario,phase,'Guest OS/root filesystem responded',proof.get('guest-get-osinfo'),proof.get('restoredMarker',''))
