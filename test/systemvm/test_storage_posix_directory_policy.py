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


"""Use the actual embedded POSIX helpers with real inode/mode/ACL operations on temporary data."""
import ast
import grp
import hashlib
import json
import os
import pwd
import re
import shutil
import stat
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
SOURCE = Path(__file__).resolve().parents[2] / 'systemvm/debian/usr/local/bin/ablestack-storagectl'
BLOCK = re.search("<<'PYPOSIX'\n(.*?)\nPYPOSIX", SOURCE.read_text(), re.S).group(1)
NODES = [node for node in ast.parse(BLOCK).body if isinstance(node, ast.FunctionDef)]
INVENTORY = next(block for block in re.findall("<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S) if 'def posix_directory_observations()' in block)
INVENTORY_NODES = [node for node in ast.parse(INVENTORY).body if isinstance(node, ast.FunctionDef) and node.name == 'posix_directory_observations']

@unittest.skipUnless(shutil.which('getfacl') and shutil.which('setfacl'), 'POSIX ACL tools are required')
class PosixDirectoryPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'volume'; self.root.mkdir()
        self.directory = self.root / 'shared'; self.directory.mkdir()
        self.child = self.directory / 'existing.txt'; self.child.write_text('Existing child data remains unchanged'); self.child.chmod(0o640)
        self.volume = 'ca9bcef3-8881-4b3b-84be-a989e654fd6a'
        self.logical = '/srv/ablestack-storage/volumes/' + self.volume
        def mapped(value):
            return str(self.root) + value[len(self.logical):] if isinstance(value, str) and value.startswith(self.logical) else value
        path = SimpleNamespace(**{name: getattr(os.path, name) for name in ('join',)})
        path.realpath = lambda value: value
        path.isdir = lambda value: os.path.isdir(mapped(value)); path.islink = lambda value: os.path.islink(mapped(value))
        proxy = SimpleNamespace(**{name: getattr(os, name) for name in ('O_RDONLY', 'O_DIRECTORY', 'O_NOFOLLOW', 'fstat', 'close', 'chown', 'chmod')})
        proxy.path = path; proxy.lstat = lambda value: os.lstat(mapped(value))
        proxy.open = lambda value, *args, **kwargs: os.open(mapped(value), *args, **kwargs)
        self.ns = {'os': proxy, 'subprocess': subprocess, 'tempfile': tempfile, 're': re, 'uuid': uuid, 'json': json, 'hashlib': hashlib, 'stat': stat, 'pwd': pwd, 'grp': grp, 'desired': {}}
        exec(compile(ast.Module(body=NODES, type_ignores=[]), str(SOURCE), 'exec'), self.ns)
        original_run = self.ns['run']
        def run(argv, **kwargs):
            if argv[0] == 'findmnt':
                return SimpleNamespace(stdout=self.logical + (' fake-filesystem-uuid' if argv[3] == 'TARGET,UUID' else '') + '\n')
            return original_run(argv, **kwargs)
        self.ns['run'] = run
        self.request = {'uuid': 'd2ab6184-8a26-4313-9bea-f227e67e8394', 'instanceUuid': '00b331db-f1ba-4a40-aa19-931fdd393a0e',
                        'volumeUuid': self.volume, 'volumeMountPath': self.logical, 'relativePath': 'shared', 'revision': 1,
                        'config': {'directoryMode': '2775', 'applyOwner': False, 'recursive': False,
                                   'accessEntries': [{'principalType': 'NUMERIC_GID', 'principal': '10006', 'permission': 'READ_WRITE'}],
                                   'defaultEntries': [{'principalType': 'NUMERIC_GID', 'principal': '10006', 'permission': 'READ_WRITE'}]}}
        path, fs, fd = self.ns['path_identity'](self.request)
        self.addCleanup(os.close, fd)
        self.ns.update(request=self.request, canonical_path=path, filesystem_uuid=fs, directory_fd=fd)
    def snapshot(self): return self.ns['observe'](self.request)
    def test_applies_only_current_directory_and_preserves_existing_child(self):
        before = self.snapshot(); child = self.child.stat()
        after = self.ns['apply_policy_atomic'](self.request, before, {})
        self.assertEqual('2775', after['effectiveMode']); self.assertEqual('CONSISTENT', after['driftStatus'])
        self.assertEqual(before['inode'], after['inode']); self.assertEqual(before['effectiveUid'], after['effectiveUid'])
        self.assertEqual(child.st_ino, self.child.stat().st_ino); self.assertEqual(child.st_mode, self.child.stat().st_mode)
        self.assertEqual('Existing child data remains unchanged', self.child.read_text())
        self.assertIn('group:10006:rwx', after['acl']); self.assertIn('default:group:10006:rwx', after['acl'])
    def test_mid_apply_acl_failure_restores_exact_stat_and_acl(self):
        before = self.snapshot(); before['previousPolicy'] = None; original = self.ns['run']
        def fail(argv, **kwargs):
            if argv[0] == 'setfacl' and '-m' in argv: raise subprocess.CalledProcessError(1, argv)
            return original(argv, **kwargs)
        self.ns['run'] = fail
        with self.assertRaises(subprocess.CalledProcessError): self.ns['apply_policy_atomic'](self.request, before, {})
        after = self.snapshot()
        for key in ('effectiveUid', 'effectiveGid', 'effectiveMode', 'inode', 'acl'): self.assertEqual(before[key], after[key], key)
        self.assertEqual('Existing child data remains unchanged', self.child.read_text())
    def test_replaced_directory_cannot_redirect_metadata_changes(self):
        before = self.snapshot(); outside = Path(self.temp.name) / 'outside'; outside.mkdir(); outside.chmod(0o755)
        self.directory.rename(self.root / 'original'); self.directory.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError): self.ns['apply_policy_atomic'](self.request, before, {})
        self.assertEqual(0o755, stat.S_IMODE(outside.stat().st_mode))
        self.assertEqual(before['effectiveMode'], format(stat.S_IMODE((self.root / 'original').stat().st_mode), '04o'))
    def test_rejects_symlink_and_parent_components_at_open(self):
        self.child.unlink(); self.directory.rmdir(); self.directory.symlink_to('/tmp', target_is_directory=True)
        with self.assertRaises(OSError): self.ns['path_identity'](self.request)
        for value in ('../etc', '/etc', 'shared//child'):
            request = dict(self.request, relativePath=value)
            with self.assertRaises(ValueError): self.ns['path_identity'](request)

    def select_filesystem_root(self):
        self.request = dict(self.request, relativePath="", allowFilesystemRoot=True,
                            config={"directoryMode": "0750", "applyOwner": False, "recursive": False})
        path, filesystem, descriptor = self.ns["path_identity"](self.request)
        self.addCleanup(os.close, descriptor)
        self.ns.update(request=self.request, canonical_path=path, filesystem_uuid=filesystem, directory_fd=descriptor)

    def test_filesystem_root_requires_explicit_scope_and_exact_preview_before_metadata_changes(self):
        with self.assertRaises(ValueError): self.ns["path_identity"](dict(self.request, relativePath=""))
        self.select_filesystem_root()
        before = self.snapshot(); child = self.child.stat()
        with self.assertRaises(ValueError): self.ns["apply_policy_atomic"](self.request, before, {})
        self.assertEqual(before["effectiveMode"], self.snapshot()["effectiveMode"])
        self.request["expectedDirectoryIdentity"] = before["directoryIdentity"]
        after = self.ns["apply_policy_atomic"](self.request, before, {})
        self.assertTrue(after["filesystemRoot"]); self.assertEqual("0750", after["effectiveMode"])
        self.assertEqual(child, self.child.stat()); self.assertEqual(before["effectiveUid"], after["effectiveUid"])

    def test_filesystem_root_changed_mode_or_filesystem_uuid_invalidates_preview_without_restoring_it(self):
        self.select_filesystem_root(); before = self.snapshot()
        self.request["expectedDirectoryIdentity"] = before["directoryIdentity"]
        self.root.chmod(0o700)
        changed = self.snapshot()
        with self.assertRaises(ValueError): self.ns["apply_policy_atomic"](self.request, changed, {})
        self.assertEqual("0700", self.snapshot()["effectiveMode"])
        self.request["expectedDirectoryIdentity"] = {**changed["directoryIdentity"], "filesystemUuid": "foreign"}
        with self.assertRaises(ValueError): self.ns["apply_policy_atomic"](self.request, changed, {})
        self.assertEqual("0700", self.snapshot()["effectiveMode"])

    def test_filesystem_root_acl_change_invalidates_even_an_unchanged_inode_preview(self):
        self.select_filesystem_root(); before = self.snapshot()
        self.request["expectedDirectoryIdentity"] = before["directoryIdentity"]
        subprocess.run(["setfacl", "-m", "u:10008:r-x", str(self.root)], check=True)
        changed = self.snapshot()
        self.assertEqual(before["inode"], changed["inode"])
        with self.assertRaises(ValueError): self.ns["apply_policy_atomic"](self.request, changed, {})
        self.assertEqual(changed["directoryIdentity"], self.snapshot()["directoryIdentity"])

class PosixDirectoryObservationTest(unittest.TestCase):
    def test_mode_drift_and_read_errors_are_not_reported_as_consistent(self):
        import time
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'dir'; path.mkdir(); path.chmod(0o755)
            logical = '/srv/ablestack-storage/volumes/volume/dir'
            request = {'volumeMountPath': '/srv/ablestack-storage/volumes/volume', 'relativePath': 'dir', 'config': {'directoryMode': '2775', 'applyOwner': False}}
            data = {'policy': {'request': request, 'effective': {'effectiveMode': '2775', 'acl': ['user::rwx']}}}
            proxy = SimpleNamespace(path=SimpleNamespace(realpath=lambda value: value), stat=lambda value: os.stat(path))
            runner = SimpleNamespace(PIPE=subprocess.PIPE, run=lambda *args, **kw: SimpleNamespace(returncode=0, stdout='user::rwx\n'))
            ns = {'time': time, 'os': proxy, 'subprocess': runner, 'load_json': lambda *args: data}
            exec(compile(ast.Module(body=INVENTORY_NODES, type_ignores=[]), str(SOURCE), 'exec'), ns)
            self.assertEqual('DRIFT', ns['posix_directory_observations']()['policy']['driftStatus'])
            runner.run = lambda *args, **kw: SimpleNamespace(returncode=1, stdout='')
            self.assertEqual('UNOBSERVED', ns['posix_directory_observations']()['policy']['driftStatus'])

if __name__ == '__main__': unittest.main()
