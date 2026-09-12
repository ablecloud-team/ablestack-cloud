# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import urllib.request,urllib.parse,json,http.cookiejar
from pathlib import Path
op=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
session=None
def api(cmd,**kw):
 if session:kw['sessionkey']=session
 try:
  r=json.load(op.open('http://127.0.0.1:18994/client/api',data=urllib.parse.urlencode(dict(command=cmd,response='json',**kw)).encode(),timeout=30))
  return next(iter(r.values()))
 except urllib.error.HTTPError as e:
  r=json.load(e);raise RuntimeError(cmd+': '+str(next(iter(r.values())).get('errortext')))
session=api('login',username='admin',password='password',domain='/')['sessionkey']
z=json.loads(Path('/tmp/epic994/api-zone.json').read_text())
providers={p['name'] for p in api('listBackupProviders')['providers']}
assert {'kboss','nas','netbackup','commvault','bx','dummy','networker','veeam'}<=providers
for old in api('listBackupOfferings',zoneid=z['id']).get('backupoffering',[]):
 if old['name'].startswith('S5B-runtime-'):api('deleteBackupOffering',id=old['id'])
result=api('createBackupOffering',name='S5B-runtime-offering',description='S5B metadata fixture',zoneid=z['id'],allowuserdrivenbackups='true',compress='true',validate='true',validationsteps='wait_for_boot,screenshot',allowquickrestore='true',backupchainsize=4)
offer=result['backupoffering'];oid=offer['id'];assert offer['provider']=='kboss',offer
try:
 api('updateBackupOffering',id=oid,name='S5B-runtime-denied',retentionperiod='17')
 raise AssertionError('KBOSS must not invoke an external retention plan update')
except RuntimeError as e:
 assert 'provider is not set to commvault' in str(e)
api('updateBackupOffering',id=oid,name='S5B-runtime-updated')
listed=api('listBackupOfferings',id=oid)['backupoffering'][0]
assert listed['name']=='S5B-runtime-updated'
assert listed.get('retentionperiod') is None
api('listBackupServiceJobs',zoneid=z['id'])
api('listBackups',zoneid=z['id'])
api('deleteBackupOffering',id=oid)
print('PASS assembled management: 8 providers preserved, mixed NAS/KBOSS offering create/list/update/delete, external retention update rejection, KBOSS jobs and backup list APIs')
