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


"""Exercise actual SMB source-policy helpers without privileged service changes."""
import ast
import ipaddress
import re
import unittest
from pathlib import Path
SOURCE = Path(__file__).resolve().parents[2] / 'systemvm/debian/usr/local/bin/ablestack-storagectl'
BLOCK = next(block for block in re.findall("<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S) if 'def smb_network_policy(' in block)
TREE = ast.parse(BLOCK)
NS = {'ipaddress': ipaddress}
NODES = [node for node in TREE.body if isinstance(node, ast.FunctionDef) and node.name in ('smb_network_policy', 'smb_hosts_allow_lines')]
exec(compile(ast.Module(body=NODES, type_ignores=[]), str(SOURCE), 'exec'), NS)
class SmbNetworkAclTest(unittest.TestCase):
    def policy(self, value): return NS['smb_network_policy'](value)
    def rule(self, source, kind='CIDR'): return {'principalType': kind, 'principal': source, 'permission': 'CONNECT', 'state': 'Ready'}
    def test_absent_rules_keep_existing_any_source_behavior(self):
        policy = self.policy([])
        self.assertEqual('ANY_SOURCE', policy['networkAccessMode'])
        self.assertEqual([], NS['smb_hosts_allow_lines'](policy))
    def test_restrictions_are_normalized_per_share_with_loopback(self):
        a = self.policy([self.rule('10.1.1.9/24'), self.rule('10.1.1.0/24')])
        b = self.policy([self.rule('10.1.1.9', 'IP_ADDRESS')])
        self.assertEqual(['10.1.1.0/24'], a['allowedSources'])
        self.assertEqual(1, a['effectiveNetworkAclCount'])
        self.assertEqual(['   hosts allow = 10.1.1.9 127.0.0.1 ::1'], NS['smb_hosts_allow_lines'](b))
    def test_account_tokens_and_samba_injection_cannot_become_sources(self):
        for source in ('*', 'ALL', 'host.example', '10.1.1.9\nhosts allow = ALL'):
            with self.subTest(source=source):
                with self.assertRaises(ValueError): self.policy([self.rule(source, 'IP_ADDRESS')])
        invalid = self.rule('10.1.1.9', 'IP_ADDRESS'); invalid['permission'] = 'ADMIN'
        with self.assertRaises(ValueError): self.policy([invalid])
    def test_disabled_rules_do_not_apply(self):
        rule = self.rule('10.1.1.9', 'IP_ADDRESS'); rule['state'] = 'Disabled'
        self.assertEqual('ANY_SOURCE', self.policy([rule])['networkAccessMode'])
if __name__ == '__main__': unittest.main()
