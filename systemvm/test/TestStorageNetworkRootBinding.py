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
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import unittest
from types import SimpleNamespace

SOURCE=Path(__file__).resolve().parents[2]/'systemvm/debian/usr/local/bin/ablestack-storagectl'

class StorageNetworkRootBindingTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.cache=self.root/'network-endpoints.json'
        self.cache.write_text(json.dumps({'endpoints':[{'listenIp':'10.1.1.11','primaryIp':'10.1.1.10','prefixlen':16}]}))
        self.original=self.cache.read_bytes()
        self.receipt=self.root/'state/network-bindings.json'
        self.links=[{'ifname':'eth0','address':'52:54:00:00:00:01'}]
        self.addresses=[{'ifname':'eth0','addr_info':[{'local':'10.1.1.10','prefixlen':16}]}]
        self.calls=[]
        def run(args):
            self.calls.append(args)
            if args[:3]==['ip','addr','add']:
                ip,prefix=args[3].split('/')
                for link in self.addresses:
                    if link['ifname']==args[5]:link['addr_info'].append({'local':ip,'prefixlen':int(prefix),'secondary':True})
            return SimpleNamespace(returncode=0,stderr='')
        self.ns=dict(ipaddress=ipaddress,json=json,os=os,re=re,hashlib=hashlib,stat=stat,tempfile=tempfile,
                     STATE_PATH=str(self.cache),BINDING_RECEIPT_PATH=str(self.receipt),strict_bindings=None,
                     interface_links=lambda:self.links,interface_addresses=lambda:self.addresses,run=run)
        source=SOURCE.read_text();block=source[source.index('def root_binding_targets('):source.index('payload = load_json(payload_path, {})',source.index('def root_binding_targets('))]
        functions=[node for node in ast.parse(block).body if isinstance(node,ast.FunctionDef)]
        # apply_one's ordinary fallback functions are not used in strict mode.
        prefix=source[source.index('def find_existing_iface('):source.index('def find_target_iface(')]
        exec(compile(ast.parse(prefix),str(SOURCE),'exec'),self.ns)
        exec(compile(ast.Module(body=functions,type_ignores=[]),str(SOURCE),'exec'),self.ns)
        self.expected=[{'listenIp':'10.1.1.11','macAddress':'52:54:00:00:00:01','primaryIp':'10.1.1.10','prefixlen':16}]
        self.requested=[{'listenIp':'10.1.1.11','primaryIp':'10.1.1.10','prefixlen':16}]

    def bind(self):
        self.ns['strict_bindings']=self.ns['root_binding_targets'](self.expected,self.requested)
        return self.ns['apply_one'](self.requested[0])

    def test_exact_mac_replay_and_separate_receipt_preserve_desired_bytes(self):
        proof=self.bind()
        self.assertEqual('52:54:00:00:00:01',proof['macAddress'])
        self.ns['write_binding_receipt'](self.expected,[proof])
        self.assertEqual(self.original,self.cache.read_bytes())
        self.assertEqual(hashlib.sha256(self.original).hexdigest(),json.loads(self.receipt.read_text())['desiredStateSha256'])
        self.assertEqual(0o600,self.receipt.stat().st_mode&0o777)

    def test_absent_desired_empty_bindings_receipt_never_creates_desired_file(self):
        self.cache.unlink()
        self.ns['write_binding_receipt']([],[])
        receipt=self.ns['read_binding_receipt']()
        self.assertFalse(receipt['desiredPresent'])
        self.assertIsNone(receipt['desiredStateSha256'])
        self.assertEqual([],receipt['expectedBindings'])
        self.assertFalse(self.cache.exists())

    def test_absent_desired_nonempty_bindings_fail_closed(self):
        self.cache.unlink()
        with self.assertRaisesRegex(ValueError,'desired cache'):
            self.ns['write_binding_receipt'](self.expected,[])
        self.assertFalse(self.cache.exists())
        self.assertFalse(self.receipt.exists())

    def test_renamed_interface_reboot_uses_receipt_mac_and_same_primary(self):
        proof=self.bind();self.ns['write_binding_receipt'](self.expected,[proof])
        saved=self.ns['read_binding_receipt']()
        self.links[0]['ifname']='ens7'
        self.addresses=[{'ifname':'ens7','addr_info':[{'local':'10.1.1.10','prefixlen':16}]}]
        self.ns['strict_bindings']=self.ns['root_binding_targets'](saved['expectedBindings'],self.requested)
        proof=self.ns['apply_one'](self.requested[0])
        self.assertEqual('ens7',proof['interface'])
        self.assertEqual(self.original,self.cache.read_bytes())

    def test_foreign_mac_address_is_rejected_before_any_interface_mutation(self):
        self.links.append({'ifname':'eth1','address':'52:54:00:00:00:02'})
        self.addresses.append({'ifname':'eth1','addr_info':[{'local':'10.1.1.11','prefixlen':16}]})
        with self.assertRaisesRegex(ValueError,'foreign'):
            self.bind()
        self.assertFalse(self.calls)

    def test_missing_or_ambiguous_mac_and_missing_primary_are_rejected(self):
        original=list(self.links)
        for links in ([],original+original):
            self.links=links
            with self.assertRaises(ValueError):self.bind()
        self.links=original;self.addresses=[]
        with self.assertRaises(ValueError):self.bind()
        self.assertFalse(self.calls)

    def test_unknown_cached_ip_has_no_fallback_interface(self):
        self.requested.append({'listenIp':'10.1.1.12'})
        with self.assertRaisesRegex(ValueError,'pinned'):
            self.bind()
        self.assertFalse(self.calls)

    def test_post_apply_mac_change_is_rejected(self):
        self.bind()
        target=self.ns['strict_bindings']['10.1.1.11']
        self.links[0]['address']='52:54:00:00:00:02'
        with self.assertRaisesRegex(ValueError,'MAC changed'):
            self.ns['verify_root_binding']('10.1.1.11',target)

if __name__=='__main__':unittest.main()
