import json,os,sys,hashlib
from pathlib import Path
from remote import root,remote,sql

c=sys.argv[1];manifest=json.loads((root/('server-final-'+c)/'manifest.json').read_text());ui=json.loads((root/'ui-final-stage/ui-manifest.json').read_text())
code="""import json,zipfile,hashlib,pathlib,urllib.request,subprocess
manifest=MANIFEST
ui=UI
jars=list(pathlib.Path('/usr/share/cloudstack-management/lib').glob('cloudstack-*.jar'));assert len(jars)==1
with zipfile.ZipFile(jars[0]) as z:
 for x in manifest:assert hashlib.sha256(z.read(x['path'])).hexdigest()==x['sha256'],x['path']
assert hashlib.sha256(pathlib.Path('/root/snapshot1483-ui-final2-stage/ui-manifest.json').read_bytes()).hexdigest()=='EVIDENCE_DIGEST'
web=pathlib.Path('/usr/share/cloudstack-management/webapp');assert (web/'WEB-INF').is_dir()
for x in ui:assert hashlib.sha256((web/x['path']).read_bytes()).hexdigest()==x['sha256'],x['path']
assert subprocess.check_output(['systemctl','is-active','mold'],text=True).strip()=='active'
status=urllib.request.urlopen('http://127.0.0.1:8080/client/').status;assert status==200
print(json.dumps({'managementClasses':manifest,'staticFiles':len(ui),'staticVerified':True,'webInfPreserved':True,'mold':'active','clientHttp':status}))
""".replace('MANIFEST',repr(manifest)).replace('UI', "json.loads(pathlib.Path('/root/snapshot1483-ui-final2-stage/ui-manifest.json').read_text())")
code=code.replace('EVIDENCE_DIGEST',hashlib.sha256((root/'ui-final-stage/ui-manifest.json').read_bytes()).hexdigest())
out=json.loads(remote(c,['sudo','-n','python3','-c',code]));before=json.loads((root/(c+'-hosts-before.json')).read_text());after=sql(c,"SELECT JSON_OBJECT('id',id,'uuid',uuid,'guid',guid,'status',status,'ip',private_ip_address,'type',type) FROM host WHERE removed IS NULL ORDER BY id;");assert before==after,(before,after);out['originalHostsPreserved']=True;out['hostsUp']=sum(x['status']=='Up'for x in after)
(root/(c+'-runtime-final.json')).write_text(json.dumps(out,indent=2));print(c,'runtime PASS',len(manifest),'classes',len(ui),'UI files',out['hostsUp'],'original hosts Up')
