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
import time
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
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
        for name in ("geteuid", "fchmod", "fdopen", "fsync", "replace", "unlink"):
            setattr(proxy, name, getattr(os, name))
        path.dirname = os.path.dirname;path.exists = os.path.exists
        proxy.environ = os.environ
        self.ns = {'Path': Path, 'time': time, 'IDENTITY_FIELDS': ('filesystemUuid', 'device', 'inode', 'effectiveUid', 'effectiveGid', 'effectiveMode', 'aclSha256'), 'os': proxy, 'subprocess': subprocess, 'tempfile': tempfile, 're': re, 'uuid': uuid, 'json': json, 'hashlib': hashlib, 'stat': stat, 'pwd': pwd, 'grp': grp, 'desired': {}}
        definitions = [node for node in ast.parse(BLOCK).body if isinstance(node, (ast.FunctionDef, ast.ClassDef))]
        exec(compile(ast.Module(body=definitions, type_ignores=[]), str(SOURCE), 'exec'), self.ns)
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

    def dispatch(self, operation):
        state = Path(self.temp.name) / "policies.json"
        self.ns.update(operation=operation, state_path=str(state))
        tail = BLOCK[BLOCK.index("initializer = RootInitialization()"):BLOCK.index("os.close(directory_fd)\nprint(json.dumps(result")]
        with patch.dict(os.environ, {"ABLESTACK_STORAGE_POSIX_RECEIPTS": str(Path(self.temp.name) / "receipts")}):
            exec(compile(tail, str(SOURCE), "exec"), self.ns)
        return self.ns["result"]

    def test_boot_replay_after_explicit_owner_mode_acl_change_is_nonmutating_and_byte_identical(self):
        before = self.snapshot()
        self.request["config"].update(applyOwner=True, ownerUid=65534, ownerGid=65534)
        self.request["expectedDirectoryIdentity"] = before["directoryIdentity"]
        child = self.child.stat()
        after = self.dispatch("apply")
        self.assertEqual(65534, after["effectiveUid"]);self.assertTrue(after["postApplyReceiptVerified"])
        state = Path(self.ns["state_path"]); state_info = state.stat(); state_bytes = state.read_bytes()
        receipt = Path(self.temp.name) / "receipts" / (self.request["uuid"] + ".json")
        receipt_info = receipt.stat(); receipt_bytes = receipt.read_bytes()
        # Simulate a new boot process loading the protected committed desired bytes.
        self.ns["desired"] = json.loads(state_bytes)
        original_run = self.ns["run"]
        def readonly(argv, **kwargs):
            self.assertNotEqual("setfacl", argv[0]);return original_run(argv, **kwargs)
        self.ns["run"] = readonly
        self.ns["os"].chown = lambda *args: self.fail("boot performed chown")
        self.ns["os"].chmod = lambda *args: self.fail("boot performed chmod")
        second = self.dispatch("apply")
        self.assertTrue(second["alreadyEffective"]);self.assertEqual(after["directoryIdentity"], second["directoryIdentity"])
        self.assertEqual(state_bytes, state.read_bytes());self.assertEqual(state_info, state.stat())
        self.assertEqual(receipt_bytes, receipt.read_bytes());self.assertEqual(receipt_info, receipt.stat())
        self.assertEqual(child, self.child.stat())

    def test_boot_replay_rejects_changed_acl_and_replaced_inode_without_metadata_effects(self):
        self.request["expectedDirectoryIdentity"] = self.snapshot()["directoryIdentity"]
        self.dispatch("apply")
        subprocess.run(["setfacl", "-m", "u:10008:r-x", str(self.directory)], check=True)
        changed = self.snapshot()["directoryIdentity"]
        with self.assertRaises(ValueError):self.dispatch("apply")
        self.assertEqual(changed, self.snapshot()["directoryIdentity"])
        # Even a replacement with equal permissions cannot adopt the committed receipt.
        self.directory.rename(self.root / "original")
        self.directory.mkdir();self.directory.chmod(0o2775)
        new_info = self.directory.stat()
        with self.assertRaises(ValueError):self.dispatch("apply")
        self.assertEqual(new_info, self.directory.stat())

    def test_boot_missing_legacy_receipt_does_not_approve_stale_preview(self):
        self.request["expectedDirectoryIdentity"] = self.snapshot()["directoryIdentity"]
        self.dispatch("apply")
        receipt = Path(self.temp.name) / "receipts" / (self.request["uuid"] + ".json")
        receipt.unlink()
        observed = self.snapshot()["directoryIdentity"]
        with self.assertRaises(ValueError):self.dispatch("apply")
        self.assertEqual(observed, self.snapshot()["directoryIdentity"])

    def test_foreign_or_unprotected_post_receipt_cannot_attest_an_existing_policy(self):
        self.request["expectedDirectoryIdentity"] = self.snapshot()["directoryIdentity"]
        self.dispatch("apply")
        receipt = Path(self.temp.name) / "receipts" / (self.request["uuid"] + ".json")
        original = receipt.read_text(); observed = self.snapshot()["directoryIdentity"]
        foreign = json.loads(original);foreign["scope"]["revision"] += 1
        receipt.write_text(json.dumps(foreign))
        with self.assertRaises(ValueError):self.dispatch("apply")
        self.assertEqual(observed, self.snapshot()["directoryIdentity"])
        receipt.write_text(original);receipt.chmod(0o644)
        with self.assertRaises(ValueError):self.dispatch("apply")
        self.assertEqual(observed, self.snapshot()["directoryIdentity"])
        receipt.chmod(0o600);saved=receipt.with_suffix(".saved");receipt.rename(saved);receipt.symlink_to(saved)
        with self.assertRaises(ValueError):self.dispatch("apply")
        self.assertEqual(observed, self.snapshot()["directoryIdentity"])

    def test_restore_rejects_a_foreign_receipt_before_metadata_inverse(self):
        self.request["expectedDirectoryIdentity"] = self.snapshot()["directoryIdentity"]
        self.dispatch("apply")
        snapshot = self.dispatch("inspect")
        self.request = {**self.request, "revision": 2, "expectedDirectoryIdentity": snapshot["directoryIdentity"],
                        "config": {**self.request["config"], "directoryMode": "0775"}}
        self.ns["request"] = self.request
        self.dispatch("apply"); observed = self.snapshot()["directoryIdentity"]
        snapshot["previousPostApplyReceipt"]["requestSha256"] = "0" * 64
        self.request = snapshot;self.ns["request"] = snapshot
        with self.assertRaises(ValueError):self.dispatch("restore")
        self.assertEqual(observed, self.snapshot()["directoryIdentity"])

    def test_restore_reinstates_exact_previous_policy_receipt_then_boot_is_noop(self):
        self.request["expectedDirectoryIdentity"] = self.snapshot()["directoryIdentity"]
        self.dispatch("apply")
        old = dict(self.request);old["config"] = dict(old["config"])
        snapshot = self.dispatch("inspect")
        self.request = {**old, "revision": 2, "expectedDirectoryIdentity": snapshot["directoryIdentity"],
                        "config": {**old["config"], "directoryMode": "0775"}}
        self.ns["request"] = self.request
        self.dispatch("apply")
        self.request = snapshot;self.ns["request"] = self.request
        self.dispatch("restore")
        self.request = old;self.ns["request"] = old
        result = self.dispatch("apply")
        self.assertTrue(result["alreadyEffective"]);self.assertEqual(snapshot["directoryIdentity"], result["directoryIdentity"])

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

    def select_legacy_root(self):
        self.logical = '/export'
        self.request = dict(self.request, relativePath='', volumeMountPath=self.logical, allowFilesystemRoot=True,
                            allowLegacyMountRoot=True, expectedFilesystemUuid='fake-filesystem-uuid', expectedVolumeSerial=self.volume.replace('-','')[:20],
                            expectedSizeBytes=20*(1<<30), config={'directoryMode':'0750','applyOwner':False})
        previous = self.ns['run']
        self.legacy_device = {'path':'/dev/data','type':'disk','name':'data','size':20*(1<<30),
                              'serial':self.request['expectedVolumeSerial'],'uuid':'fake-filesystem-uuid','mountpoint':'/export'}
        def observed(argv, **kwargs):
            if argv[0]=='lsblk': return SimpleNamespace(stdout=json.dumps({'blockdevices':[self.legacy_device]}))
            if argv[0]=='findmnt' and argv[3]=='SOURCE':return SimpleNamespace(stdout='/dev/data\n')
            return previous(argv, **kwargs)
        self.ns['run']=observed

    def test_explicit_legacy_data_root_binding_preserves_data_and_requires_preview_cas(self):
        self.select_legacy_root()
        path, filesystem, descriptor = self.ns['path_identity'](self.request);self.addCleanup(os.close,descriptor)
        self.ns.update(request=self.request,canonical_path=path,filesystem_uuid=filesystem,directory_fd=descriptor)
        before=self.snapshot();child=self.child.stat()
        self.assertTrue(before['allowLegacyMountRoot']);self.assertEqual('fake-filesystem-uuid',before['expectedFilesystemUuid'])
        with self.assertRaises(ValueError):self.ns['apply_policy_atomic'](self.request,before,{})
        self.request['expectedDirectoryIdentity']=before['directoryIdentity']
        self.ns['apply_policy_atomic'](self.request,before,{})
        self.assertEqual(child,self.child.stat());self.assertEqual('Existing child data remains unchanged',self.child.read_text())

    def test_legacy_mount_foreign_serial_root_device_or_filesystem_uuid_is_rejected(self):
        self.select_legacy_root()
        for changes in ({'serial':'foreign'},{'mountpoint':'/'},{'uuid':'foreign'}):
            original=dict(self.legacy_device);self.legacy_device.update(changes)
            with self.assertRaises(ValueError):self.ns['path_identity'](self.request)
            self.legacy_device.clear();self.legacy_device.update(original)
        with self.assertRaises(ValueError):self.ns['path_identity']({**self.request,'volumeMountPath':'/export/../outside'})

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
