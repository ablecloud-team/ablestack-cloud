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
import os
import subprocess
import tempfile
from types import SimpleNamespace
import re
import unittest
from pathlib import Path
SOURCE = Path(__file__).resolve().parents[2] / 'systemvm/debian/usr/local/bin/ablestack-storagectl'
BLOCK = next(block for block in re.findall("<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S) if 'def smb_network_policy(' in block)
TREE = ast.parse(BLOCK)
NS = {'ipaddress': ipaddress, 're': re}
NODES = [node for node in TREE.body if isinstance(node, ast.FunctionDef) and node.name in ('smb_network_policy', 'smb_hosts_allow_lines', 'verify_share_mount_boundary', 'truth', 'smb_creation_policy', 'smb_creation_lines', 'install_validated_smb_config')]
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
    def test_nested_mount_validation_captures_findmnt_stdout(self):
        with tempfile.TemporaryDirectory() as root:
            child = Path(root) / 'smb' / 'share'; child.mkdir(parents=True)
            def run(command, **kwargs):
                captured = kwargs.get('stdout') == subprocess.PIPE and kwargs.get('text') is True
                return SimpleNamespace(returncode=0, stdout=root + '\n' if captured else None)
            NS.update({'os': os, 'subprocess': subprocess, 'run': run})
            NS['verify_share_mount_boundary'](str(child), root)
            outside = Path(root) / 'smb' / 'link'; outside.symlink_to('/tmp')
            with self.assertRaises(RuntimeError): NS['verify_share_mount_boundary'](str(outside), root)

    def test_failed_testparm_keeps_live_configuration_and_cleans_candidate(self):
        with tempfile.TemporaryDirectory() as folder:
            config = Path(folder) / 'smb.conf'; config.write_text('known-good')
            def rejected(command, **kwargs):
                self.assertNotEqual(str(config), command[-1])
                raise subprocess.CalledProcessError(1, command)
            NS.update({'os': os, 'subprocess': subprocess, 'tempfile': tempfile, 'run': rejected})
            with self.assertRaises(subprocess.CalledProcessError):
                NS['install_validated_smb_config'](['invalid candidate'], str(config))
            self.assertEqual('known-good', config.read_text())
            self.assertEqual(['smb.conf'], os.listdir(folder))

    def test_creation_defaults_and_exact_forced_modes(self):
        policy = NS['smb_creation_policy']({})
        self.assertEqual('0660', policy['createMask'])
        self.assertEqual('0770', policy['directoryMask'])
        self.assertIn('   force create mode = 0000', NS['smb_creation_lines'](policy))
        exact = NS['smb_creation_policy']({key: '0775' for key in ('createMask', 'forceCreateMode', 'directoryMask', 'forceDirectoryMode')})
        self.assertIn('   force directory mode = 0775', NS['smb_creation_lines'](exact))
    def test_creation_rejects_conflicts_and_injection(self):
        for invalid in ({'createMask': '0600', 'forceCreateMode': '0060'}, {'createMask': '1777'},
                        {'forceCreateMode': '0600', 'inheritPermissions': True}, {'directoryMask': '0770\nforce user=root'}):
            with self.assertRaises(ValueError): NS['smb_creation_policy'](invalid)

    def test_disabled_rules_do_not_apply(self):
        rule = self.rule('10.1.1.9', 'IP_ADDRESS'); rule['state'] = 'Disabled'
        self.assertEqual('ANY_SOURCE', self.policy([rule])['networkAccessMode'])
if __name__ == '__main__': unittest.main()
