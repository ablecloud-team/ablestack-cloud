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
import json,sys,shlex
from remote import root,remote,sql
c,tag=sys.argv[1:3];r=json.loads((root/(c+'-extract-'+tag+'.json')).read_text());assert r['job']['jobstatus']==1
d=r['job']['jobresult'];d=d.get('extractsnapshotresponse',d)['snapshot']
rows=sql(c,"SELECT JSON_OBJECT('uuid',s.uuid,'size',s.size,'status',s.status,'sourceformat',v.format,'sourceRemoved',v.removed,'refid',r.id,'path',r.install_path,'url',r.download_url,'urlCreated',r.download_url_created,'state',r.state) FROM snapshots s JOIN snapshot_store_ref r ON r.snapshot_id=s.id LEFT JOIN volumes v ON v.id=s.volume_id WHERE s.uuid='"+r['snapshot']+"' AND r.store_role='Image' AND r.state='Ready';")
assert len(rows)==1 and rows[0]['url']==d['url'] and rows[0]['size']==d['size'];db=rows[0]
source='/nfs/secondary/snapshots/'+db['path'].split('snapshots/',1)[1]
code='''import json,sys,subprocess,hashlib,os
from pathlib import Path
url,filename,source,expected_format,expected_size,expected_file=sys.argv[1:]
root=Path('/root/snapshot-download1475-files');root.mkdir(exist_ok=True);target=root/filename
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for chunk in iter(lambda:f.read(1048576),b''):h.update(chunk)
 return h.hexdigest()
before=sha(Path(source))
def req(extra,target):
 r=subprocess.run(['curl','--silent','--show-error','--max-time','180','--dump-header','-','--output',str(target)]+extra+[url],capture_output=True,text=True,check=True)
 return r.stdout
full=req([],target);info=json.loads(subprocess.check_output(['qemu-img','info','--output=json',str(target)],text=True))
assert ' 200 ' in full and info['format']==expected_format.lower() and info['virtual-size']==int(expected_size) and target.stat().st_size==int(expected_file)
download=sha(target);assert download==before
after=sha(Path(source));assert after==before
front=root/(filename+'.front');partial=req(['--range','0-1048575'],front);assert ' 206 ' in partial and front.stat().st_size==1048576
pattern=subprocess.check_output(['qemu-io','-f',info['format'],'-c','read -P 0x5a 0 1M','-c','read -P 0xa5 1023M 1M',str(target)],text=True)
print(json.dumps({'url':url,'filename':filename,'fullHeaders':full,'partialHeaders':partial,'qemu':info,'fileSize':target.stat().st_size,'magic':target.open('rb').read(8).hex(),'sourceSha256':before,'downloadSha256':download,'sourceUnchanged':before==after,'dataPattern':pattern,'verified':True}))
'''
out=json.loads(remote(c,['sudo','-n','python3','-c',code,d['url'],d['filename'],source,d['format'],str(d['size']),str(d['physicalsize'])]));out['db']=db;out['api']=d
(root/(c+'-download-'+tag+'-verified.json')).write_text(json.dumps(out,indent=2));print(c,tag,'API/DB/file/header/pattern match',out['qemu']['format'],out['qemu']['virtual-size'])
