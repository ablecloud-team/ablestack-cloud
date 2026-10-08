#!/usr/bin/env python3

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

import ast
from pathlib import Path
import socket
from types import SimpleNamespace
import time
import unittest

SOURCE=Path(__file__).resolve().parents[2]/'systemvm/debian/usr/local/bin/ablestack-storagectl'
class StorageSmbFreshHealthTest(unittest.TestCase):
    def setUp(self):
        self.listener=socket.socket();self.listener.bind(('127.0.0.1',0));self.listener.listen(8);self.addCleanup(self.listener.close)
        self.port=self.listener.getsockname()[1]
        self.desired={'enabled':True,'listeners':[{'listenIp':'127.0.0.1','port':self.port}]}
        self.observed=SimpleNamespace(returncode=0,stdout=f'LISTEN 0 128 127.0.0.1:{self.port} 0.0.0.0:* users:(("smbd",pid=42,fd=3))')
        self.ns=dict(load_json_state=lambda name:self.desired,run=lambda args:self.observed,time=time,socket=socket)
        source=SOURCE.read_text();start=source.index('def collect_smb_listener_runtime():');end=source.index('smb_runtime = collect_smb_listener_runtime()',start)
        exec(compile(ast.parse(source[start:end]),str(SOURCE),'exec'),self.ns)
    def test_fresh_specific_socket_and_tcp_connection_are_both_required(self):
        result=self.ns['collect_smb_listener_runtime']();endpoint=result['runtimeEndpoints'][0]
        self.assertTrue(result['available']);self.assertTrue(result['listening'])
        self.assertTrue(endpoint['listenerOwned']);self.assertTrue(endpoint['tcpReady'])
    def test_no_socket_is_distinct_from_unavailable_observation(self):
        self.observed.stdout=''
        result=self.ns['collect_smb_listener_runtime']()
        self.assertTrue(result['available']);self.assertFalse(result['listening'])
        self.observed.returncode=1
        result=self.ns['collect_smb_listener_runtime']()
        self.assertFalse(result['available'])
        self.assertEqual('SMB_SOCKET_OBSERVATION_UNAVAILABLE',result['errorCode'])
    def test_other_process_socket_is_not_reported_as_samba(self):
        self.observed.stdout=self.observed.stdout.replace('smbd','python3')
        result=self.ns['collect_smb_listener_runtime']()
        self.assertFalse(result['runtimeEndpoints'][0]['listenerOwned'])
        self.assertFalse(result['listening'])
if __name__=='__main__':unittest.main()
