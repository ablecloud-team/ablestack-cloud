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

# Local software-browser fixture. No real Cloud credentials or services.
import json, time, urllib.parse, mimetypes
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
ROOT = Path(__file__).resolve().parents[2] / 'dist'
LOG = Path('/tmp/issue1042-browser-requests.jsonl')
BASE = ['listApis', 'listUsers', 'listZones', 'listCapabilities', 'listVirtualMachines', 'listVolumes', 'listNetworks', 'listVPCs', 'listProjects', 'listEvents', 'listAccounts', 'listTemplates', 'listIsos', 'listServiceOfferings', 'listDiskOfferings', 'listResourceLimits', 'listResourceCounts', 'listSSHKeyPairs', 'listAsyncJobs', 'listProjectInvitations']
EXTRA = ['listConfigurations', 'listNetworkServiceProviders', 'listWallAlertRules', 'listHostsMetrics', 'listHosts', 'listClusters', 'listPods', 'listStoragePools', 'listDomains', 'listLdapConfigurations', 'cloudianIsEnabled', 'listHSMProfiles']

def user(role):
    return dict(id=role, userid=role, username=role, account=role, accountid=role, domainid='domain1', domain='ROOT', firstname=role.title(), lastname='Tester', type={'user': '0', 'domain': '2', 'admin': '1'}.get(role, '0'), roletype={'user': 'User', 'domain': 'DomainAdmin', 'admin': 'Admin'}.get(role, 'User'), rolename={'user': 'User', 'domain': 'Domain Admin', 'admin': 'Admin'}.get(role, 'User'), state='enabled', timezone='Asia/Seoul', timezoneoffset='9.0', sessionkey=role, firstlogin=False, is2faenabled='false')

class Handler(BaseHTTPRequestHandler):

    def log_message(self, *args):
        pass

    def do_POST(self):
        self.handle_request()

    def do_GET(self):
        self.handle_request()

    def send(self, data, status=200, ctype='application/json'):
        self.send_response(status)
        self.send_header('Content-Type', ctype)
        self.end_headers()
        self.wfile.write(data if isinstance(data, bytes) else json.dumps(data).encode())

    def handle_request(self):
        url = urllib.parse.urlsplit(self.path)
        q = urllib.parse.parse_qs(url.query)
        if self.command == 'POST':
            raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            if url.path == '/__error':
                with LOG.open('a') as f:
                    f.write(json.dumps({'browserError': raw.decode(), 'time': time.time()}) + '\n')
                return self.send({})
            q.update(urllib.parse.parse_qs(raw.decode()))
        if url.path == '/__evidence':
            return self.send(('<html><body><h1>Issue 1042 browser evidence</h1><pre>' + (LOG.read_text() if LOG.exists() else '') + '</pre></body></html>').encode(), ctype='text/html')
        if url.path.startswith('/client/api'):
            cmd = q.get('command', [''])[0]
            role = q.get('sessionkey', q.get('username', ['user']))[0]
            if role not in ['user', 'domain', 'admin']:
                role = 'user'
            entry = {'time': time.time(), 'role': role, 'command': cmd}
            status = 432 if cmd in ['listNetworkServiceProviders', 'listWallAlertRules', 'cloudianIsEnabled', 'listLdapConfigurations'] else 200
            entry['status'] = status
            with LOG.open('a') as f:
                f.write(json.dumps(entry) + '\n')
            if status != 200:
                return self.send({'errorresponse': {'errorcode': 432, 'errortext': 'Fixture: optional discovery denied'}}, 432)
            if cmd == 'login':
                return self.send({'loginresponse': user(role)})
            if cmd == 'logout':
                return self.send({'logoutresponse': {'success': True}})
            if cmd == 'listApis':
                return self.send({'listapisresponse': {'api': [{'name': n, 'params': [], 'isasync': False} for n in BASE + (EXTRA if role == 'admin' else ['listDomains'] if role == 'domain' else [])]}})
            if cmd == 'listUsers':
                return self.send({'listusersresponse': {'count': 1, 'user': [user(role)]}})
            if cmd == 'listCapabilities':
                return self.send({'listcapabilitiesresponse': {'capability': {'securitygroupsenabled': False, 'customhypervisordisplayname': 'Custom', 'defaultuipagesize': 20, 'version': '4.20.0.0', 'userpublictemplateenabled': True}}})
            if cmd == 'listAccounts':
                return self.send({'listaccountsresponse': {'count': 1, 'account': [dict(user(role), name=role)]}})
            if cmd == 'listConfigurations':
                return self.send({'listconfigurationsresponse': {'count': 0, 'configuration': []}})
            return self.send({cmd.lower() + 'response': {'count': 0}})
        rel = urllib.parse.unquote(url.path).removeprefix('/client/').lstrip('/')
        path = (ROOT / (rel or 'index.html')).resolve()
        if ROOT not in path.parents:
            return self.send({}, 404)
        if not path.is_file():
            return self.send({}, 404)
        data = path.read_bytes()
        if path.name == 'index.html':
            script = b"<script>for(const e of ['error','unhandledrejection'])window.addEventListener(e,x=>{fetch('/__error',{method:'POST',body:JSON.stringify({type:e,message:String(x.reason||x.message)})})});</script>"
            data = data.replace(b'</head>', script + b'</head>')
        self.send(data, ctype=mimetypes.guess_type(str(path))[0] or 'application/octet-stream')
ThreadingHTTPServer(('127.0.0.1', 8872), Handler).serve_forever()
