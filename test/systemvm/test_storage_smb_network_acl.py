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
NS = {'ipaddress': ipaddress, 're': re, 'os': os, 'subprocess': subprocess, 'tempfile': tempfile}
NODES = [node for node in TREE.body if isinstance(node, ast.FunctionDef) and node.name in ('smb_network_policy', 'smb_hosts_allow_lines', 'verify_share_mount_boundary', 'truth', 'smb_creation_policy', 'smb_creation_lines', 'install_validated_smb_config', 'apply_directory_policy', 'remove_stale_managed_smb_acls', 'smb_creation_acl_preflight', 'verify_common_posix_policy', 'smb_inheritance_policy', 'smb_inheritance_lines', 'smb_forced_identity', 'smb_forced_identity_lines', 'rollback_uncommitted_managed_identities', 'smb_effective_read_only')]
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

    def test_creation_policy_update_preserves_existing_root_mode_and_acl(self):
        calls = []
        NS['os'] = SimpleNamespace(chmod=lambda *args: calls.append(args))
        previous = {'path': '/volume/share', 'directoryMode': '0o770'}
        self.assertEqual(0o770, NS['apply_directory_policy']('/volume/share', {'directoryMode': '0770'}, False, previous))
        self.assertEqual([], calls)
        NS['apply_directory_policy']('/volume/share', {'directoryMode': '2775'}, False, previous)
        self.assertEqual([('/volume/share', 0o2775)], calls)
        calls.clear(); NS['apply_directory_policy']('/volume/share', {'directoryMode': '2775'}, True, previous)
        self.assertEqual([], calls)
    def test_deleting_account_acl_removes_only_previously_managed_principal(self):
        calls = []
        NS.update({'run': lambda argv: calls.append(argv), 'resolve_account_id': lambda kind, name: ('u', '1001')})
        previous = {'aclSummary': [{'principalType': 'LOCAL_USER', 'principal': 'managed'}]}
        NS['remove_stale_managed_smb_acls']('/volume/share', [], previous)
        self.assertEqual([['setfacl', '-x', 'u:1001', '/volume/share'], ['setfacl', '-d', '-x', 'u:1001', '/volume/share']], calls)
        calls.clear(); NS['remove_stale_managed_smb_acls']('/volume/share', previous['aclSummary'], previous)
        self.assertEqual([], calls)

    def test_existing_default_acl_conflict_is_blocked_without_permission_changes(self):
        policy = NS['smb_creation_policy']({key: '0775' for key in ('createMask', 'forceCreateMode', 'directoryMask', 'forceDirectoryMode')})
        NS['os'] = os
        with tempfile.TemporaryDirectory() as root:
            acl = 'default:user::rwx\ndefault:group::rwx\ndefault:mask::rwx\ndefault:other::---\n'
            NS['run'] = lambda *args, **kwargs: SimpleNamespace(stdout=acl)
            with self.assertRaisesRegex(RuntimeError, 'default ACL permits 0770'):
                NS['smb_creation_acl_preflight'](root, policy)
            acl = acl.replace('other::---', 'other::r-x')
            self.assertEqual('0775', NS['smb_creation_acl_preflight'](root, policy)['defaultAclMode'])
            self.assertEqual('COMPATIBLE', NS['smb_creation_acl_preflight'](root, NS['smb_creation_policy']({}))['state'])

    def test_readonly_account_acl_cannot_gain_write_from_share_or_forced_identity(self):
        self.assertTrue(NS['smb_effective_read_only']({'readOnly': False, 'posixOwnershipMode': 'FORCED_UID_GID'}, 1, False))
        self.assertTrue(NS['smb_effective_read_only']({'readOnly': True}, 1, False))
        self.assertFalse(NS['smb_effective_read_only']({'readOnly': False}, 0, True))

    def test_failed_reload_restores_previous_config_bytes_and_modes(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'smb.conf'; target.write_bytes(b'new-config'); target.chmod(0o600)
            calls = []
            NS.update({'os': os, 'tempfile': tempfile, 'subprocess': subprocess, 'identity_apply_verified': False,
                       'created_identity_users': ['sf_u_new'], 'created_identity_groups': ['sf_g_new'],
                       'smb_previous_files': {str(target): b'known-good-config'}, 'smb_previous_modes': {str(target): 0o640},
                       'shutil': SimpleNamespace(which=lambda value: 'smbcontrol'), 'run_optional': lambda argv, **kwargs: calls.append(argv)})
            NS['rollback_uncommitted_managed_identities']()
            self.assertEqual(b'known-good-config', target.read_bytes()); self.assertEqual(0o640, target.stat().st_mode & 0o777)
            self.assertEqual(['smbcontrol', 'all', 'reload-config'], calls[0])
            self.assertEqual([['userdel', 'sf_u_new'], ['groupdel', 'sf_g_new']], calls[1:])
            self.assertEqual(['smb.conf'], os.listdir(folder))

    def test_failed_render_cleans_only_new_managed_identities(self):
        calls = []
        NS.update({'identity_apply_verified': False, 'created_identity_users': ['sf_u_new'], 'created_identity_groups': ['sf_g_new'],
                   'run_optional': lambda argv, **kwargs: calls.append(argv), 'smb_previous_files': {}, 'shutil': SimpleNamespace(which=lambda value: None)})
        NS['rollback_uncommitted_managed_identities']()
        self.assertEqual([['userdel', 'sf_u_new'], ['groupdel', 'sf_g_new']], calls)
        calls.clear(); NS['identity_apply_verified'] = True; NS['rollback_uncommitted_managed_identities']()
        self.assertEqual([], calls)

    def test_forced_identity_preflight_rejects_existing_and_protected_ids_without_creating_accounts(self):
        class Missing:
            def getgrnam(self, value): raise KeyError(value)
            def getgrgid(self, value): raise KeyError(value)
            def getpwnam(self, value): raise KeyError(value)
            def getpwuid(self, value): raise KeyError(value)
        NS.update({'grp': Missing(), 'pwd': Missing(), 'run': lambda *args: self.fail('preflight must not create native accounts')})
        config = {'posixOwnershipMode': 'FORCED_UID_GID', 'ownerUid': 1001001, 'ownerGid': 1001001}
        policy = NS['smb_forced_identity'](config, 'a39c4d3e-aef8-4bcc-9858-7b86f628c38a', [], False)
        self.assertEqual(1001001, policy['ownerUid']); self.assertTrue(policy['managedUser'].startswith('sf_u_'))
        self.assertEqual(2, len(NS['smb_forced_identity_lines'](policy)))
        for uid in (0, 1002, 65534, 2147483648):
            with self.assertRaises(ValueError): NS['smb_forced_identity'](dict(config, ownerUid=uid), 'a39c4d3e-aef8-4bcc-9858-7b86f628c38a', [], False)
        NS['pwd'] = SimpleNamespace(getpwnam=lambda value: (_ for _ in ()).throw(KeyError(value)), getpwuid=lambda value: SimpleNamespace(pw_name='foreign'))
        with self.assertRaises(ValueError): NS['smb_forced_identity'](config, 'a39c4d3e-aef8-4bcc-9858-7b86f628c38a', [], False)

    def test_parent_owner_inheritance_does_not_bypass_account_authentication(self):
        policy = NS['smb_inheritance_policy']({'ownershipInheritance': 'INHERIT_PARENT_OWNER', 'inheritGroup': True}, [])
        self.assertEqual(['   inherit owner = yes'], NS['smb_inheritance_lines'](policy))
        self.assertEqual([], NS['smb_inheritance_lines'](NS['smb_inheritance_policy']({}, [])))
        for config, acls in [({'inheritGroup': True}, []), ({'ownershipInheritance': 'INHERIT_PARENT_OWNER', 'guestOk': True}, []),
                             ({'ownershipInheritance': 'INHERIT_PARENT_OWNER', 'posixOwnershipMode': 'FORCED_UID_GID'}, []),
                             ({'ownershipInheritance': 'INHERIT_PARENT_OWNER'}, [{'permission': 'ADMIN'}])]:
            with self.assertRaises(ValueError): NS['smb_inheritance_policy'](config, acls)

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
