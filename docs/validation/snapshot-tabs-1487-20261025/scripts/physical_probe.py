import json,sys
from remote import root,remote
c=sys.argv[1];d=json.loads((root/(c+'-db-before.json')).read_text())
refs=[x for x in d['references']if x['role']=='Image'];paths=['/nfs/secondary/'+x['path']for x in refs]
assert len(paths)==7 and all('..'not in x.split('/')for x in paths)
code="import json,pathlib,subprocess;paths="+repr(paths)+";out=[]\nfor p in paths:\n f=pathlib.Path(p);i=json.loads(subprocess.check_output(['qemu-img','info','--output=json',p],text=True));out.append({'path':p,'exists':f.is_file(),'fileSize':f.stat().st_size,'virtualSize':i['virtual-size'],'allocatedSize':i['actual-size'],'format':i['format']})\nprint(json.dumps(out))"
rows=json.loads(remote(c,['sudo','-n','python3','-c',code]))
for row,ref in zip(rows,refs):assert row['exists']and row['fileSize']==ref['physical']and row['virtualSize']==ref['size']
(root/(c+'-physical-before.json')).write_text(json.dumps(rows,indent=2));print(c,'7 backup images agree with DB virtual and physical bytes')
