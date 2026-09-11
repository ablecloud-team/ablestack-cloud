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

import urllib.request, urllib.parse, urllib.error, json, http.cookiejar, time, socket, uuid
from pathlib import Path
root = Path('/tmp/epic998')
class Client:
    def __init__(self,user='admin',password='password'):
        self.op=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.session=None
        self.session=self.api('login',username=user,password=password,domain='/')['sessionkey']
    def api(self,command,**kw):
        if self.session:kw['sessionkey']=self.session
        try:
            r=json.load(self.op.open('http://127.0.0.1:18998/client/api',data=urllib.parse.urlencode(dict(command=command,response='json',**kw)).encode(),timeout=30))
            result=next(iter(r.values()))
            if result.get('errorcode'):raise RuntimeError(str(result))
            return result
        except urllib.error.HTTPError as e:
            try:info=json.load(e)
            except Exception:info={'error':str(e)}
            raise RuntimeError(command+': '+str(info))
    def wait(self,result):
        if 'jobid' not in result:return result
        for _ in range(120):
            result=self.api('queryAsyncJobResult',jobid=result['jobid'])
            if result.get('jobstatus')==1:return result.get('jobresult',{})
            if result.get('jobstatus')==2:raise RuntimeError(str(result.get('jobresult')))
            time.sleep(.5)
        raise RuntimeError('Job timeout')
    def deny(self,command,**kw):
        try:self.wait(self.api(command,**kw))
        except RuntimeError as e:
            assert any(w in str(e).lower() for w in ['permission','access','conflict','not allowed','not authorized','illegal','unsupported','invalid','unable','not available']),str(e)
            return
        raise AssertionError('Unexpected success '+command)
for i in range(120):
    try:admin=Client();break
    except Exception:
        if i==119:raise
        time.sleep(1)
suffix=uuid.uuid4().hex[:7]
# S7 fixture uses a real management API and disposable DB, never the development service.
theme=admin.api('createGuiTheme',name='S7-'+suffix,commonnames='s7.example.test',loginbasedomain='tenant')['guiThemes']
assert theme['loginbasedomain']=='tenant',theme
tid=theme['id']
listed=admin.api('listGuiThemes',commonname='s7.example.test')['guiThemes'][0]
assert listed['id']==tid and listed['loginbasedomain']=='tenant'
updated=admin.api('updateGuiTheme',id=tid,loginbasedomain='tenant/child')['guiThemes']
assert updated['loginbasedomain']=='tenant/child'
updated=admin.api('updateGuiTheme',id=tid,description='S7 preserves omitted domain')['guiThemes']
assert updated['loginbasedomain']=='tenant/child'
# Clearing is permitted with another meaningful theme customization.
updated=admin.api('updateGuiTheme',id=tid,css='body {color: blue}',loginbasedomain='')['guiThemes']
assert updated.get('loginbasedomain','')==''
try:admin.api('createGuiTheme',name='Invalid S7',loginbasedomain='tenant')
except RuntimeError as e:assert 'commonNames' in str(e)
else:raise AssertionError('Missing common name accepted')
rootdomain=admin.api('listDomains')['domain'][0]['id']
username='s7-user-'+suffix
account=admin.api('createAccount',username=username,password='S7!test2026',firstname='S7',lastname='Fixture',email='s7@example.invalid',accounttype=0,domainid=rootdomain,enable='false')['account']
user=Client(username,'S7!test2026')
for command,args in [('createGuiTheme',dict(name='forbidden',css='body {}')),('updateGuiTheme',dict(id=tid,css='body {}')),('removeGuiTheme',dict(id=tid))]:user.deny(command,**args)
admin=Client()
admin.api('removeGuiTheme',id=tid)
assert not admin.api('listGuiThemes',commonname='s7.example.test').get('guiThemes')
(root/'browser-account.json').write_text(json.dumps(dict(username=username,password='S7!test2026',accountid=account['id'])))
checks=['theme-create-domain-only','theme-public-commonname-list','theme-update-domain','omitted-domain-retained','explicit-empty-domain','missing-commonname-rejected','regular-user-three-mutations-denied','theme-remove']
(root/'api-theme-result.json').write_text(json.dumps(dict(status='PASS',checks=checks),indent=2)+'\n')
print('PASS',len(checks),'actual Management GUI theme API contracts')
