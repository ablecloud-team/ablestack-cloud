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
import ipaddress
import json
from pathlib import Path
import socket
from types import SimpleNamespace
import time
import unittest

SOURCE = Path(__file__).resolve().parents[2] / 'systemvm/debian/usr/local/bin/ablestack-storagectl'

class StorageSmbEndpointReadinessTest(unittest.TestCase):
    def setUp(self):
        source = SOURCE.read_text()
        start = source.index('def smb_owned_listening_sockets(')
        end = source.index('def install_validated_smb_config(', start)
        self.listeners = []
        self.sockets = ''
        self.assigned = ['127.0.0.1', '127.0.0.2']
        def run(args, **kwargs):
            if args[0] == 'ss':
                return SimpleNamespace(stdout=self.sockets)
            if args[0] == 'ip':
                return SimpleNamespace(stdout=json.dumps([{'addr_info': [{'local': value} for value in self.assigned]}]))
            raise AssertionError(args)
        self.ns = dict(run=run, subprocess=SimpleNamespace(PIPE=-1), socket=socket,
                       ipaddress=ipaddress, json=json, time=time)
        exec(compile(ast.parse(source[start:end]), str(SOURCE), 'exec'), self.ns)
        self.addCleanup(lambda: [listener.close() for listener in self.listeners])

    def bind(self, ip, owner='smbd'):
        listener = socket.socket()
        listener.bind((ip, 0))
        listener.listen(8)
        self.listeners.append(listener)
        port = listener.getsockname()[1]
        self.sockets += f'LISTEN 0 128 {ip}:{port} 0.0.0.0:* users:(("{owner}",pid=42,fd=4))\n'
        return {'listenIp': ip, 'port': port}

    def verify(self, rows):
        return self.ns['verify_smb_endpoint_listeners'](rows, timeout=0.05)

    def test_each_specific_endpoint_needs_its_own_smbd_owned_reachable_socket(self):
        a = self.bind('127.0.0.1')
        b = self.bind('127.0.0.2')
        result = self.verify([a, b])
        self.assertEqual(2, len(result))
        self.assertTrue(all(item['success'] and item['listenerOwned'] for item in result))

    def test_existing_a_cannot_mask_missing_b_listener(self):
        a = self.bind('127.0.0.1')
        with self.assertRaises(RuntimeError):
            self.verify([a, {'listenIp': '127.0.0.2', 'port': a['port']}])

    def test_other_process_tcp_listener_cannot_be_reported_as_samba(self):
        endpoint = self.bind('127.0.0.1', owner='python3')
        with self.assertRaises(RuntimeError):
            self.verify([endpoint])

    def test_wildcard_socket_cannot_satisfy_specific_binding_contract(self):
        endpoint = self.bind('0.0.0.0')
        with self.assertRaises(RuntimeError):
            self.verify([dict(endpoint, listenIp='127.0.0.1')])

    def test_one_hundred_endpoint_probes_share_one_total_deadline(self):
        clock = [0.0]
        timeouts = []
        self.ns['time'] = SimpleNamespace(monotonic=lambda: clock[0], sleep=lambda value: clock.__setitem__(0, clock[0] + value))
        def run(args, **kwargs):
            timeouts.append(kwargs['timeout'])
            clock[0] += 0.02
            if args[0] == 'ip':
                return SimpleNamespace(stdout=json.dumps([{'addr_info': [{'local': '127.0.0.1'}]}]))
            return SimpleNamespace(stdout='\n'.join(f'LISTEN 0 128 127.0.0.1:{port} 0.0.0.0:* users:(("smbd",pid=42,fd=4))' for port in range(20000,20100)))
        def connect(endpoint, timeout):
            timeouts.append(timeout)
            clock[0] += min(0.01, timeout)
            raise TimeoutError()
        self.ns['run'] = run
        self.ns['socket'] = SimpleNamespace(create_connection=connect)
        rows = [{'listenIp': '127.0.0.1', 'port': port} for port in range(20000,20100)]
        with self.assertRaisesRegex(RuntimeError, 'deadline'):
            self.ns['verify_smb_endpoint_listeners'](rows, timeout=0.1)
        self.assertLessEqual(clock[0], 0.100001)
        self.assertLess(len(timeouts), 20)
        self.assertTrue(all(0 < value <= 0.1 for value in timeouts))

    def test_unassigned_ip_is_rejected_even_if_an_unrelated_socket_is_reachable(self):
        endpoint = self.bind('127.0.0.2')
        self.assigned = ['127.0.0.1']
        with self.assertRaises(RuntimeError):
            self.verify([endpoint])

if __name__ == '__main__':
    unittest.main()
