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
import contextlib
import io
import os
from pathlib import Path
import re
import sys
import unittest
from unittest.mock import patch
from xml.dom.minidom import parse

HOOK = Path(__file__).parents[4] / 'agent/bindir/libvirtqemuhook.in'
module = ast.parse(HOOK.read_text())
namespace = dict(os=os, re=re, sys=sys, parse=parse)
exec(compile(ast.Module(body=[n for n in module.body if isinstance(n, ast.FunctionDef)], type_ignores=[]), str(HOOK), 'exec'), namespace)

originalPreparedParent = namespace['preparedVlanParent']

class HookVlanTest(unittest.TestCase):
    def transform(self, bridge, parent, prepared=None):
        namespace['networkConfig'] = lambda: type('Network', (), {'isNetworkDev': lambda _, p: p == parent})()
        namespace['getGuestNetworkDevice'] = lambda: parent
        namespace['preparedVlanParent'] = lambda b, v: prepared
        output = io.StringIO()
        with patch('sys.stdin', io.StringIO('<domain><devices><interface type="bridge"><source bridge="'+bridge+'"/></interface></devices></domain>')), contextlib.redirect_stdout(output):
            namespace['handleMigrateBegin']()
        return parse(io.StringIO(output.getvalue())).getElementsByTagName('source')[0].getAttribute('bridge')

    def test_legacy_valid_name_remains(self):
        self.assertEqual('brbond0-4094', self.transform('brbond0-4094', 'bond0'))
        self.assertEqual('brbond-x-1000', self.transform('brbond-x-1000', 'bond-x'))

    def test_old_global_name_and_long_destination(self):
        self.assertEqual('brlhfblgjc-3832', self.transform('cloudVirBr3832', 'ens1f0np0'))
        self.assertEqual('brlhfblgjc-3832', self.transform('breth0-3832', 'ens1f0np0'))

    def test_compact_migration_to_same_or_different_parent(self):
        self.assertEqual('brlhfblgjc-3832', self.transform('brlhfblgjc-3832', 'ens1f0np0'))
        self.assertEqual('brbond0-3832', self.transform('brlhfblgjc-3832', 'bond0'))

    def test_prepared_non_guest_parent_is_preserved(self):
        expected = namespace['vlanBridgeName']('bond12345678901', '4094')
        self.assertEqual(expected, self.transform(expected, 'eth0', prepared='bond12345678901'))

    def test_vxlan_and_native_are_unchanged(self):
        self.assertEqual('brvx-100000', self.transform('brvx-100000', 'ens1f0np0'))
        self.assertEqual('cloudbr0', self.transform('cloudbr0', 'ens1f0np0'))

    def test_prepared_marker_must_match_vid_and_parent_hash(self):
        from unittest.mock import mock_open
        for owner, expected in [('cloudstack:vlan:ens1f0np0:3832', 'ens1f0np0'), ('cloudstack:vlan:ens1f0np0:3831', None), ('cloudstack:vlan:bond0:3832', None)]:
            with patch('builtins.open', mock_open(read_data=owner)):
                self.assertEqual(expected, originalPreparedParent('brlhfblgjc-3832', '3832'))

    def test_every_vlan_has_same_length_contract(self):
        names=set()
        for parent in ['eth0', 'bond0', 'ens1f0np0', 'bond12345678901', 'ens123456789012']:
            for vid in range(1,4095):
                br=namespace['vlanBridgeName'](parent,str(vid))
                self.assertLessEqual(len(br),15)
                self.assertNotIn(br,names)
                names.add(br)

if __name__ == '__main__':unittest.main()
