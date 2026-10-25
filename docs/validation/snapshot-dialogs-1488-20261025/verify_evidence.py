import json,hashlib,struct,re
from pathlib import Path
root=Path(__file__).resolve().parent
b=root/'browser';e=root/'evidence'
def image_size(raw):
 if raw[:8]==bytes([137,80,78,71,13,10,26,10]):return struct.unpack('>II',raw[16:24])
 assert raw[:2]==bytes([255,216])
 i=2
 while i<len(raw):
  if raw[i]!=255:i+=1;continue
  while raw[i]==255:i+=1
  marker=raw[i];i+=1
  if marker in [216,217]:continue
  n=struct.unpack('>H',raw[i:i+2])[0]
  if marker in [192,193,194,195,197,198,199,201,202,203,205,206,207]:
   h,w=struct.unpack('>HH',raw[i+3:i+7]);return w,h
  i+=n
 raise ValueError('No JPEG frame')

checks=[];layouts=[];focus=[]
def check(name,condition):
 assert condition,name
 checks.append(name)
kinds=['create','volume','template','restore','download','archive','delete','copy','zoneDelete','bulk','recovery','job']
for c in ['31','32']:
 for theme in ['light','dark']:
  for width in [390,768,1280]:
   rows=json.loads((b/(c+'-repaired-focus-'+theme+'-'+str(width)+'.json')).read_text());check(c+' '+theme+' '+str(width)+' 12 final focus cases',len(rows)==12)
   for row in rows:
    check(c+' '+theme+' '+str(width)+' '+row['kind']+' focus and Escape return',row['entered']['inDialog']and row['returned']['tag']=='BUTTON'and row['returned']['connected'])
    if width==390:check(c+' '+theme+' '+row['kind']+' Tab trap',row['backward']['inDialog']and row['forward']['inDialog'])
   focus.extend(rows)
   for kind in kinds:
    old=c+'-final-'+theme+'-'+str(width)+'-'+kind
    current=c+'-repaired-layout-'+theme+'-'+str(width)+'-'+kind
    name=current
    p=b/(name+'.json');d=json.loads(p.read_text());pic=b/(name+'.png');pic=pic if pic.exists() else pic.with_suffix('.jpg');w,h=image_size(pic.read_bytes());check(name+' image and actual viewport',(w,h)==(width,720)and(d['width'],d['height'])==(width,720))
    check(name+' width and fixed header footer',0<d['dialog']['width']<=width and d['footer']['bottom']<=721 and d['header']['bottom']<=d['body']['y']+1 and d['body']['bottom']<=d['footer']['y']+1 and d['documentWidth']<=width)
    overlaps=[]
    for g in d['groups']:
     a,z=g['labelRect'],g['controlRect']
     if a and z and min(a['right'],z['right'])-max(a['x'],z['x'])>1 and min(a['bottom'],z['bottom'])-max(a['y'],z['y'])>1:overlaps.append(g['label'])
    check(name+' two dimensional form gaps',not overlaps)
    if theme=='dark':check(name+' no solid-black dark-mode text',not any(x.get('text','').strip() and x.get('fill',x.get('color')) in ['rgb(0, 0, 0)','rgba(0, 0, 0, 1)'] for x in d['colors']))
    check(name+' no StorPool option',not re.search('StorPool|프라이머리 스토리지 복제 사용|프라이버리 스토리지 복제 사용',d['text'],re.I))
    if kind in ['delete','zoneDelete','bulk']:check(name+' VM identity column', '가상머신' in d['text'])
    if kind in ['delete','bulk']:check(name+' linked VM name and state','SNAPDIALOG1488-'+c+'-vm' in d['text'] and '정지' in d['text'])
    if kind=='bulk':check(name+' five targets and internal scrolling','5개' in d['text']and any(x['scrollWidth']>x['width']for x in d['tables'])and any(x['scrollHeight']>x['height']for x in d['tables']))
    layouts.append({'cluster':c,'theme':theme,'width':width,'kind':kind,'record':p.name,'status':'PASS'})
 for suffix in ['ui-job-oracle','ui-delete-oracle','preservation']:
  d=json.loads((e/(c+'-'+suffix+'.json')).read_text());check(c+' '+suffix,d['status']=='PASS')
 d=json.loads((e/(c+'-final-state.json')).read_text());check(c+' active DB and physical zero',not any(d['database'].values())and not d['physical']['remaining']and not d['physical']['rbdTrash']and d['runtime']=={'mold':'active','http':200})
 for suffix in ['copy','zone-deleted','derived']:
  d=json.loads((e/(c+'-db-'+suffix+'.json')).read_text());check(c+' '+suffix+' independent physical data',len(d['physical'])==(1 if suffix=='zone-deleted'else 2))
 d=json.loads((e/(c+'-copy-fixture-acl-restore.json')).read_text());check(c+' fixture-only HTTP ACL restored',d['status']==403 and d['originalAclUnchanged'])
 d=json.loads((e/(c+'-ui-deploy-repair.json')).read_text());check(c+' final UI deployment',d['files']==922 and d['verified']and d['webInf']and d['clientHttp']==200 and d['mold']=='active')
 d=json.loads((e/(c+'-download-http.json')).read_text());check(c+' download completion and actual QCOW2',d['status']==206 and d['magic']=='514649fb'and d['virtualSize']==1073741824)
 d=json.loads((e/(c+'-download-revoked.json')).read_text());check(c+' source Zone deletion revokes download',d['status']==404)
for platform in ['windows','wsl']:
 d=json.loads((e/('protected-'+platform+'-verification.json')).read_text());check('protected '+platform+' FT work unchanged',d['unchanged'])
check('201 focused tests','Tests:       201 passed, 201 total' in(root/'logs/ui-tests-final.log').read_text())
check('final UI build','Build complete.' in(root/'logs/ui-build-repair.log').read_text())
check('final changed-source lint','No lint errors found' in(root/'logs/ui-repair-lint.log').read_text())
check('144 effective layouts and final focus',len(layouts)==144 and len(focus)==144)
manifest=json.loads((root/'source-manifest.json').read_text())
repo=root.parents[2]
for item in manifest:check('source hash '+item['path'],hashlib.sha256((repo/item['path']).read_bytes()).hexdigest()==item['sha256'])
result={'status':'PASS','checks':len(checks),'layouts':len(layouts),'focusCases':len(focus),'mobileTabCases':sum(x['width']==390 for x in focus),'sources':len(manifest),'boundaries':['focused tests and live clusters; not full Cloud CI','dedicated TLS agent processes in existing SSVM; independent NFS subpaths','fixture-only mount mapping and exact-file self-IP ACL; no snapshot/result SQL edits']}
(root/'evidence-verification.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
(b/'final-layout-matrix.json').write_text(json.dumps(layouts,indent=2,ensure_ascii=False)+'\n');print(json.dumps(result,ensure_ascii=False))
