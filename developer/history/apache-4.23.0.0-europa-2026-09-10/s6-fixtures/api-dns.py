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
root = Path('/tmp/epic997')
class Client:
    def __init__(self,user='admin',password='password'):
        self.op=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.session=None
        self.session=self.api('login',username=user,password=password,domain='/')['sessionkey']
    def api(self,command,**kw):
        if self.session:kw['sessionkey']=self.session
        try:
            r=json.load(self.op.open('http://127.0.0.1:18997/client/api',data=urllib.parse.urlencode(dict(command=command,response='json',**kw)).encode(),timeout=30))
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
ip=socket.gethostbyname(socket.gethostname())
serverparams=dict(provider='PowerDNS',dnsapikey='s6-provider-dummy',nameservers='ns.example.test',url='http://'+ip,port=18953)
assert admin.api('listDnsProviders').get('count',0)>0
for server in admin.api('listDnsServers').get('dnsserver',[]):
    if server['name'].startswith('S6-public-'):
        admin.wait(admin.api('deleteDnsServer',id=server['id']))
public=admin.api('addDnsServer',name='S6-public-'+suffix,ispublic='true',publicdomainsuffix='example.test',**serverparams)['dnsserver']
assert 's6-provider-dummy' not in json.dumps(public)
admin.api('updateDnsServer',id=public['id'],state='Enabled')
rootdomain=admin.api('listDomains')['domain'][0]['id']
users=[]
for n in ['a','b']:
    name='s6-'+n+'-'+suffix
    account=admin.api('createAccount',username=name,password='S6!test2026',firstname='S6',lastname='Fixture',email='s6@example.invalid',accounttype=0,domainid=rootdomain,enable='false')['account']
    users.append(name)
b=Client(users[1],'S6!test2026')
b.deny('addDnsServer',name='forbidden',**serverparams)
b.deny('updateDnsServer',id=public['id'],name='forbidden')
b.deny('deleteDnsServer',id=public['id'])
admin=Client()
for url in ['http://127.0.0.1:18953','ftp://'+ip,'http://169.254.169.254']:
    admin.deny('addDnsServer',name='invalid-'+suffix,**dict(serverparams,url=url))
a=Client(users[0],'S6!test2026')
zone=a.wait(a.api('createDnsZone',dnsserverid=public['id'],name='tenant-'+suffix+'.example.test'))['dnszone']
assert zone['name']=='tenant-'+suffix+'.example.test',zone
b=Client(users[1],'S6!test2026')
for name in ['tenant-'+suffix+'.example.test','sub.tenant-'+suffix+'.example.test']:b.deny('createDnsZone',dnsserverid=public['id'],name=name)
assert not b.api('listDnsZones',id=zone['id']).get('dnszone')
b.deny('deleteDnsZone',id=zone['id'])
b.deny('createDnsRecord',dnszoneid=zone['id'],name='intruder',type='A',contents='192.0.2.9')
a=Client(users[0],'S6!test2026')
created=a.wait(a.api('createDnsRecord',dnszoneid=zone['id'],name='host',type='A',contents='192.0.2.10',ttl=120))
records=a.api('listDnsRecords',dnszoneid=zone['id'])
assert '192.0.2.10' in json.dumps(records),records
assert 's6-provider-dummy' not in json.dumps(a.api('listDnsServers'))
a.wait(a.api('deleteDnsRecord',dnszoneid=zone['id'],name='host',type='A'))
assert '192.0.2.10' not in json.dumps(a.api('listDnsRecords',dnszoneid=zone['id']))
a.wait(a.api('deleteDnsZone',id=zone['id']))
admin=Client()
admin.wait(admin.api('deleteDnsServer',id=public['id']))
(root/'api-dns-result.json').write_text(json.dumps(dict(status='PASS',provider='PowerDNS HTTP contract fixture; not a physical DNS server',zone=zone['name'],checks=['provider-registration','admin-only-server-mutations','unsafe-URL-denial','public-suffix','cross-tenant-zone-conflict','cross-tenant-ACL','record-create-list-delete','zone-delete','secret-omission']),indent=2))
print('PASS actual Management PowerDNS API lifecycle, URL denial, account ACL, zone conflict, records and secret omission',flush=True)
