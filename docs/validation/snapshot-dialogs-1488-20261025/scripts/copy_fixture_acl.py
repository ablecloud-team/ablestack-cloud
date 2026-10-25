import json,sys,os,re,shlex,hashlib
from pathlib import Path
from remote import root,remote,sql
c,mode=sys.argv[1:3];assert mode in ['allow','restore']
r=json.loads((root/(c+'-fixtures.json')).read_text());s=next(x for x in r['snapshots']if x['scenario']=='ui-source');assert s['name'].startswith(r['prefix'])
d=json.loads((root/(c+'-db-created.json')).read_text());ref=next(x for x in d['references']if x['snapshot']==s['id']and x['role']=='Image');p=next(x['path']for x in d['physical']if x['snapshot']==s['id']);source=Path(p);assert source.is_absolute()and str(source).startswith('/nfs/secondary/snapshots/')and '..'not in source.parts
acl=source.parent/'.htaccess';client='10.10.'+c+'.6';body='<Files "'+source.name+'">\nOrder Deny,Allow\nDeny from all\nAllow from '+client+'\n</Files>\n'
parent=sql(c,"SELECT JSON_OBJECT('parent',parent) FROM image_store WHERE uuid='"+ref['store']+"';")[0]['parent'];assert re.fullmatch('[a-f0-9-]{36}',parent)
url='http://'+client+'/copy/SecStorage/'+parent+'/'+ref['path']
b=json.loads((root/(c+'-ssvm-baseline.json')).read_text());ssh=['sudo','-n','ssh','-p','3922','-i','/var/cloudstack/management/.ssh/id_rsa','-o','UserKnownHostsFile=/root/snapshot1474-ssvm-known-hosts','-o','StrictHostKeyChecking=yes','root@'+b['host']['ipaddress']]
if mode=='allow':
 code="from pathlib import Path;p=Path("+repr(str(acl))+");assert not p.exists();p.write_text("+repr(body)+");p.chmod(0o644)";remote(c,['sudo','-n','python3','-c',code])
else:
 code="from pathlib import Path;p=Path("+repr(str(acl))+");assert p.read_text()=="+repr(body)+";p.unlink()";remote(c,['sudo','-n','python3','-c',code])
code="import urllib.request,urllib.error,hashlib,json;from pathlib import Path;u="+repr(url)+";o={'copyParentSha256':hashlib.sha256(Path('/var/www/html/copy/.htaccess').read_bytes()).hexdigest()};\ntry:\n r=urllib.request.urlopen(urllib.request.Request(u,headers={'Range':'bytes=0-3'}),timeout=15);o.update(status=r.status,magic=r.read(4).hex())\nexcept urllib.error.HTTPError as e:o['status']=e.code\nprint(json.dumps(o))"
code=code.replace(chr(92)+'n',chr(10))
out=json.loads(remote(c,ssh+[shlex.join(['python3','-c',code])]))
assert out['status']==(206 if mode=='allow'else 403),out
if mode=='allow':assert out['magic']=='514649fb'
else:assert out['copyParentSha256']==json.loads((root/(c+'-copy-fixture-acl-allow.json')).read_text())['copyParentSha256']
out.update(mode=mode,path=str(acl),file=source.name,onlyClient=client,fixtureOnly=True,originalAclUnchanged=True)
(root/(c+'-copy-fixture-acl-'+mode+'.json')).write_text(json.dumps(out,indent=2));print(c,mode,'fixture file HTTP',out['status'],'original copy ACL unchanged')
