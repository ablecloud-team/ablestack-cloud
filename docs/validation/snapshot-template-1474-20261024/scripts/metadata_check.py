import json
from remote import root
matrix=[]
for c in ['31','32']:
 db=json.loads((root/(c+'-all-combinations-db.json')).read_text())['templates'];physical=json.loads((root/(c+'-all-combinations-physical.json')).read_text());apis=json.loads((root/(c+'-templates-api.json')).read_text())['template'];checks=[]
 for t in db:
  if t['name'].endswith('-before'):continue
  p=next(x for x in physical if x['uuid']==t['uuid']);a=next(x for x in apis if x['id']==t['uuid']);props={}
  for line in p['properties'].splitlines():
   if '=' in line and not line.startswith('#'):
    key,value=line.split('=',1);props[key]=value
  ext=t['format'].lower();size=107374182400 if '-root-' in t['name'] else 1073741824
  assert t['format']==a['format']==p['qemu']['format'].upper()
  assert size==t['size']==t['refsize']==a['size']==p['qemu']['virtual-size']==int(props['virtualsize'])==int(props[ext+'.virtualsize'])
  assert t['physicalsize']==p['fileSize']==int(props['size'])==int(props[ext+'.size'])
  assert p['path'].endswith('.'+ext) and props['filename']==props[ext+'.filename']==p['path'].rsplit('/',1)[1]
  assert a['isready'] and t['downloadstate']=='DOWNLOADED' and not t['destroyed']
  checks.append({'uuid':t['uuid'],'name':t['name'],'format':t['format'],'type':'ROOT' if '-root-' in t['name'] else 'DATADISK','virtualSize':size,'fileLength':p['fileSize'],'allocatedBytes':p['qemu'].get('actual-size'),'headerApiDbPropertiesExtensionAgree':True})
 for kind in ['ROOT','DATADISK']:
  for fmt in ['RAW','QCOW2']:
   assert any(x['type']==kind and x['format']==fmt for x in checks);matrix.append({'cluster':c,'storage':'GFS2' if c=='31' else 'RBD','type':kind,'backupFormat':fmt,'status':'PASS'})
 (root/(c+'-metadata-verified.json')).write_text(json.dumps(checks,indent=2));print(c,'API/UI counterpart/DB/header/properties matched',len(checks))
(root/'matrix.json').write_text(json.dumps(matrix,indent=2))
