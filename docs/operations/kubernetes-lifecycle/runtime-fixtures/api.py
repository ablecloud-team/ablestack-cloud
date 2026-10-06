# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

import signal
import threading
HEALTH_FAILED=False
READINESS_FAILED=False
def controlled_health_failure(signum,frame):
    global HEALTH_FAILED,READINESS_FAILED
    HEALTH_FAILED=True
    READINESS_FAILED=True
signal.signal(signal.SIGUSR1,controlled_health_failure)
def controlled_readiness_failure(signum,frame):
    global READINESS_FAILED
    READINESS_FAILED=not READINESS_FAILED
signal.signal(signal.SIGUSR2,controlled_readiness_failure)

import os,socket,json
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer

def redis(*args):
    with socket.create_connection(('redis',6379),timeout=4) as s:
        parts=[str(x).encode() for x in args]
        s.sendall(b'*'+str(len(parts)).encode()+b'\r\n'+b''.join(b'$'+str(len(x)).encode()+b'\r\n'+x+b'\r\n' for x in parts))
        f=s.makefile('rb'); head=f.readline()
        if head[:1] in (b'+',b':'):return head[1:-2].decode()
        if head[:1]==b'$':
            n=int(head[1:]); return None if n<0 else f.read(n+2)[:-2].decode()
        raise RuntimeError('redis command failed')
class H(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def send(self,code,obj):
        b=json.dumps(obj).encode();self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
    def do_GET(self):
        try:
            if self.path=='/health':return self.send(503 if HEALTH_FAILED else 200,{'ok':not HEALTH_FAILED})
            if self.path=='/ready':
                if READINESS_FAILED:return self.send(503,{'ok':False})
                return self.send(200,{'redis':redis('PING')})
            if self.path=='/config':return self.send(200,{'revision':os.getenv('REVISION')})
            if self.headers.get('Authorization')!='Bearer '+os.getenv('APP_TOKEN'):return self.send(401,{'error':'unauthorized'})
            if self.path.startswith('/kv/'):return self.send(200,{'value':redis('GET',self.path[4:])})
            self.send(404,{})
        except Exception:self.send(503,{'error':'backend unavailable'})
    def do_POST(self):
        try:
            if self.headers.get('Authorization')!='Bearer '+os.getenv('APP_TOKEN'):return self.send(401,{'error':'unauthorized'})
            if not self.path.startswith('/kv/'):return self.send(404,{})
            data=json.loads(self.rfile.read(int(self.headers.get('Content-Length',0))));self.send(200,{'result':redis('SET',self.path[4:],data['value'])})
        except Exception:self.send(503,{'error':'backend unavailable'})
server=ThreadingHTTPServer(('0.0.0.0',8080),H)
def graceful_termination(signum,frame):
    global READINESS_FAILED
    READINESS_FAILED=True
    threading.Thread(target=server.shutdown,daemon=True).start()
signal.signal(signal.SIGTERM,graceful_termination)
try:
    server.serve_forever()
finally:
    server.server_close()
