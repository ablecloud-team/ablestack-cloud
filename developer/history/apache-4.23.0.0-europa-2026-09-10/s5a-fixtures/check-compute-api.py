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

import urllib.request,urllib.error,urllib.parse,http.cookiejar,json,time,pymysql,sys
from pathlib import Path
base=Path('/tmp/epic993')
jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
url='http://127.0.0.1:18993/client/api'
for i in range(30):
 try:
  with opener.open(url+'?command=listCapabilities&response=json',timeout=3) as r:r.read();break
 except urllib.error.HTTPError as e:
  if e.code in [401,431]:break
 except Exception:pass
 time.sleep(2)
else:raise RuntimeError('Management API did not become ready')
params=dict(command='login',response='json',username='s5a-runtime-admin',password='S5A-fixture-password',domain='/')
with opener.open(url,urllib.parse.urlencode(params).encode(),timeout=15) as r:login=json.load(r)
assert 'loginresponse' in login,list(login)
session=login['loginresponse']['sessionkey']
def api(command,**params):
 params.update(command=command,response='json',sessionkey=session)
 try:
  with opener.open(url,urllib.parse.urlencode(params).encode(),timeout=30) as r:return next(iter(json.load(r).values()))
 except urllib.error.HTTPError as e:
  error=json.loads(e.read());raise RuntimeError(command+': '+str(error))
c=pymysql.connect(host='epic993-mysql-upgrade',user='root',password='epic993-fixture-dummy')
q=c.cursor();q.execute('SELECT uuid FROM cloud.vm_instance WHERE id=993001');vm=q.fetchone()[0]
legacy=api('listVMSchedule',virtualmachineid=vm)
generic=api('listResourceSchedule',resourcetype='VirtualMachine',resourceid=vm)
assert legacy.get('count')==5,legacy
assert generic.get('count')==5,generic
created=api('createResourceSchedule',resourcetype='VirtualMachine',resourceid=vm,schedule='0 12 * * *',timezone='Asia/Seoul',action='STOP',enabled='false',startdate='2027-01-01 12:00:00',description='S5A runtime CRUD fixture')
row=created.get('resourceschedule',created)
assert 'id' in row, list(created)
sid=row['id']
updated=api('updateVMSchedule',id=sid,description='S5A updated through legacy API',enabled='false')
updatedRow=updated.get('vmschedule',updated)
assert updatedRow.get('description')=='S5A updated through legacy API',updatedRow
removed=api('deleteResourceSchedule',resourcetype='VirtualMachine',resourceid=vm,id=sid)
assert removed.get('success') is True,removed
remaining=api('listVMSchedule',virtualmachineid=vm)
assert remaining.get('count')==5,remaining
result={'login':'PASS','legacy_schedules':legacy['count'],'generic_schedules':generic['count'],'generic_create_legacy_update_generic_delete':'PASS'}
(base/('compute-api-'+sys.argv[1]+'.json')).write_text(json.dumps(result))
print(result)
