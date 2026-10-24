import json,re,shutil
from remote import root,remote,sql
for c in ['31','32']:
 state=json.loads((root/(c+'-final-state.json')).read_text());jobs=json.loads((root/(c+'-jobs-db.json')).read_text());before=json.loads((root/(c+'-all-combinations-db.json')).read_text())
 shutil.copy2(root/(c+'-final-state.json'),root/(c+'-pre-legacy-cleanup-state.json'))
 residual=[x for x in state['secondary'] if x['exists']]
 failed={x['instanceid']:x for x in jobs if x['instancetype']=='Template' and x['status']==2 and (x.get('name')or'').startswith('SNAPT1474-'+c)}
 for x in residual:assert int(x['path'].rsplit('/',1)[1]) in failed
 allowed=set(['template.properties'])
 for x in before['snapshots']:
  if x['role']=='Image':allowed.update([x['path'].rsplit('/',1)[1]+'.qcow2',x['path'].rsplit('/',1)[1]+'.raw'])
 ids=[int(x['path'].rsplit('/',1)[1]) for x in residual]
 if ids:
  refs=sql(c,"SELECT JSON_OBJECT('active',COUNT(*)) FROM template_store_ref WHERE template_id IN ("+','.join(map(str,ids))+") AND destroyed=0 AND state NOT IN ('Destroyed','Failed');")[0]['active'];assert refs==0
 script='''import sys,json,hashlib,subprocess
from pathlib import Path
paths=json.loads(sys.argv[1]);allowed=set(json.loads(sys.argv[2]));out=[]
for path in paths:
 p=Path(path);assert p.resolve().parent==Path('/nfs/secondary/template/tmpl/2');files=[]
 for f in p.iterdir():
  assert f.is_file() and not f.is_symlink() and f.name in allowed
  item={'name':f.name,'size':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
  if f.name.endswith('.qcow2'):
   item['patternCheck']=subprocess.check_output(['qemu-io','-r','-f','qcow2','-c','read -P 0x5a 0 1M','-c','read -P 0xa5 1023M 1M',str(f)],text=True)
  files.append(item)
 for f in p.iterdir():f.unlink()
 p.rmdir();out.append({'path':path,'files':files,'deleted':True,'reason':'Legacy test deployment/failure artifact; not final product automatic cleanup'})
print(json.dumps(out))'''
 out=remote(c,['sudo','-n','python3','-c',script,json.dumps([x['path'] for x in residual]),json.dumps(sorted(allowed))]);(root/(c+'-legacy-artifact-cleanup.json')).write_text(out);print(c,'removed legacy artifacts',len(residual))
