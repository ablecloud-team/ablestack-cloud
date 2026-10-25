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
import sys,os,json,subprocess
from remote import root,sql
c=sys.argv[1];results=[]
for mode in ['unsupported','corrupt','missing','unreadable']:
 try:
  subprocess.run(['python3',str(root/'scripts/inject.py'),c,mode],check=True)
  subprocess.run(['node',str(root/'scripts/extract.mjs'),c,'negative','negative-'+mode],check=True)
  r=json.loads((root/(c+'-extract-negative-'+mode+'.json')).read_text());assert r['job']['jobstatus']==2,r
  db=sql(c,"SELECT JSON_OBJECT('url',r.download_url,'state',r.state,'status',s.status) FROM snapshots s JOIN snapshot_store_ref r ON r.snapshot_id=s.id WHERE s.uuid='"+r['snapshot']+"' AND r.store_role='Image' AND r.state='Ready';")[0];assert db['url'] is None and db['status']=='BackedUp'
  results.append({'mode':mode,'jobid':r['accepted']['jobid'],'jobstatus':2,'db':db,'noSuccessfulUrl':True})
 finally:subprocess.run(['python3',str(root/'scripts/inject.py'),c,'restore'],check=True)
(root/(c+'-negative-downloads.json')).write_text(json.dumps(results,indent=2));print(c,'negative paths PASS',len(results))
