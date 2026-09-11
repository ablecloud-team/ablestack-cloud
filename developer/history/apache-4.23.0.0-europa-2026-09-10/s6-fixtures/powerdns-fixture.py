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

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlsplit
from pathlib import Path
zones = {}
class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        with open('/tmp/epic997/provider-http.log','a') as f: f.write(fmt % args + '\n')
    def handle_api(self):
        if self.headers.get('X-API-Key') != 's6-provider-dummy': return self.reply(401, {'error':'unauthorized'})
        path = unquote(urlsplit(self.path).path).rstrip('/')
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))) or '{}')
        server = {'id':'localhost','daemon_type':'authoritative'}
        if path == '/api/v1/servers': return self.reply(200,[server])
        if path == '/api/v1/servers/localhost': return self.reply(200,server)
        prefix='/api/v1/servers/localhost/zones'
        if path == prefix and self.command == 'POST':
            name=body['name']
            if name in zones: return self.reply(409,{'error':'exists'})
            zones[name] = dict(body,id=name,rrsets=[])
            return self.reply(201,zones[name])
        if path.startswith(prefix+'/'):
            name=path[len(prefix)+1:]
            if name not in zones: return self.reply(404,{'error':'not found'})
            if self.command=='GET': return self.reply(200,zones[name])
            if self.command=='DELETE': del zones[name]
            if self.command=='PUT': zones[name].update(body)
            if self.command=='PATCH':
                for rr in body['rrsets']:
                    zones[name]['rrsets']=[x for x in zones[name]['rrsets'] if (x['name'],x['type'])!=(rr['name'],rr['type'])]
                    if rr['changetype']!='DELETE':zones[name]['rrsets'].append(rr)
            return self.reply(204,{})
        return self.reply(404,{'error':'path'})
    def reply(self,status,body):
        data=json.dumps(body).encode() if status!=204 else b''
        self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
    do_GET=do_POST=do_PUT=do_PATCH=do_DELETE=handle_api
ThreadingHTTPServer(('0.0.0.0',18953),Handler).serve_forever()
