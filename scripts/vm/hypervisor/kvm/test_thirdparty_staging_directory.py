#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""LOCAL directory validation and the production shell's explicit mode selection."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import thirdparty_staging_directory as directory

UUID = '9e6dab28-cb5b-45fb-9a47-fc5668848174'
GIB = 1024**3


class LocalDirectoryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.stage = self.base / 'staging'
        self.operation = self.stage / 'ablestack-netbackup/i-2-25-VM/2026.11.02.10.00.00.000'
        self.mount = {'target': '/', 'fstype': 'xfs', 'uuid': UUID}
        mocked = patch.object(directory.subprocess, 'run', side_effect=lambda *a, **k:
                              subprocess.CompletedProcess(a[0], 0, json.dumps({'filesystems': [self.mount]})))
        self.findmnt = mocked.start()
        self.addCleanup(mocked.stop)

    def test_directory_uses_existing_local_filesystem_and_removes_write_probe(self):
        available = directory.prepare_directory(self.stage, self.operation)
        self.assertGreater(available, 0)
        self.assertTrue(self.operation.is_dir())
        self.assertEqual([], list(self.operation.iterdir()))
        for call in self.findmnt.call_args_list:
            self.assertEqual('findmnt', call.args[0][0])
            self.assertNotIn('--mountpoint', call.args[0])

    def test_small_job_fits_but_large_job_still_fails_on_same_local_directory(self):
        with patch.object(directory.os, 'statvfs', return_value=SimpleNamespace(f_bavail=250*GIB, f_frsize=1)):
            self.assertEqual(250*GIB, directory.prepare_directory(self.stage, self.operation, 120*GIB))
            with self.assertRaisesRegex(ValueError, 'Insufficient capacity'):
                directory.prepare_directory(self.stage, self.operation, 1200*GIB)
        self.assertEqual([], list(self.operation.iterdir()))

    def test_nfs_gfs2_unknown_uuid_and_nonlocal_filesystems_are_rejected(self):
        for kind, filesystem_uuid in (('nfs4', None), ('gfs2', UUID), ('tmpfs', None), ('xfs', None)):
            with self.subTest(kind=kind, uuid=filesystem_uuid):
                self.mount.update(fstype=kind, uuid=filesystem_uuid)
                with self.assertRaises(ValueError):
                    directory.prepare_directory(self.stage, self.operation)
                self.assertFalse(self.stage.exists())

    def test_root_relative_escape_and_symlink_paths_are_rejected_before_creation(self):
        outside = self.base / 'outside'
        outside.mkdir()
        link = self.base / 'link'
        link.symlink_to(outside, target_is_directory=True)
        for root, operation in (('/', '/provider'), ('relative', 'relative/provider'),
                                (str(self.stage / '..' / 'escape'), str(self.stage / '..' / 'escape/provider')),
                                (str(link), str(link / 'provider')), (str(self.stage), str(outside / 'provider'))):
            with self.subTest(root=root), self.assertRaises(ValueError):
                directory.prepare_directory(root, operation)
        self.findmnt.assert_not_called()
        self.assertFalse(self.stage.exists())
        self.assertFalse((outside / 'provider').exists())

    def test_nested_mount_or_changed_filesystem_is_rejected(self):
        for changed_index in (2, 3):
            calls = 0
            def inspect(path):
                nonlocal calls
                calls += 1
                return ('/', UUID) if calls != changed_index else ('/other', UUID)
            with self.subTest(index=changed_index), patch.object(directory, 'local_filesystem', side_effect=inspect):
                with self.assertRaisesRegex(ValueError, 'another filesystem'):
                    directory.prepare_directory(self.stage, self.operation)

    def test_failed_write_probe_prevents_admission(self):
        with patch.object(directory.tempfile, 'TemporaryDirectory', side_effect=PermissionError('not writable')):
            with self.assertRaises(PermissionError):
                directory.prepare_directory(self.stage, self.operation)


class StagingShellModeTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.stage = self.base / 'stage'
        self.stage.mkdir()
        self.scripts = self.base / 'host scripts'
        self.scripts.mkdir()
        source = Path(directory.__file__).parent
        # Only relocate the privileged lock directory; run all validation branches unchanged.
        self.script = self.scripts / 'thirdparty_staging.sh'
        self.script.write_text((source / self.script.name).read_text().replace('/run/lock', str(self.base / 'locks')))
        shutil.copyfile(source / 'thirdparty_staging_directory.py', self.scripts / 'thirdparty_staging_directory.py')
        binary = self.base / 'bin'
        binary.mkdir()
        programs = {
            'findmnt': '#!' + sys.executable + '\n' + r'''
import json, os, sys
args = sys.argv[1:]
if '--json' in args:
    print(json.dumps({'filesystems': [{'target': '/', 'fstype': os.getenv('TEST_FSTYPE','xfs'), 'uuid': '9e6dab28-cb5b-45fb-9a47-fc5668848174'}]}))
elif '-o' in args:
    field = args[args.index('-o')+1]
    print({'FSTYPE':os.getenv('TEST_FSTYPE','xfs'),'SOURCE':'nas:/backup','TARGET':os.environ['TEST_STAGE']}[field])
elif '--mountpoint' in args:
    sys.exit(0 if os.getenv('TEST_MOUNTED') == '1' and '--fstab' not in args else 1)
''',
            'realpath': '#!' + sys.executable + '\nfrom pathlib import Path\nimport sys\nprint(Path(sys.argv[-1]).resolve())\n',
            'flock': '#!/bin/sh\nexit 0\n',
            'sync': '#!/bin/sh\nexit 0\n',
            'mount': '#!/bin/sh\nexit 99\n',
            'df': '#!/bin/sh\nprintf "Filesystem 1-blocks Used Available Capacity Mounted\nfake 999999 1 999998 1%% fake\n"\n',
        }
        for name, text in programs.items():
            file = binary / name
            file.write_text(text)
            file.chmod(0o755)
        self.env = dict(os.environ, PATH=str(binary)+os.pathsep+os.environ['PATH'], TEST_STAGE=str(self.stage))

    def run_validator(self, kind='LOCAL', mode=None, required='0', **settings):
        args = [kind, str(self.stage), str(self.stage), 'nas:/backup', '', str(self.stage / 'provider'), '10', required]
        if mode is not None:
            args.append(mode)
        return subprocess.run(['bash', str(self.script), *args], env=dict(self.env, **settings), capture_output=True, text=True, timeout=10)

    def test_explicit_directory_mode_accepts_existing_local_folder_without_mounting(self):
        result = self.run_validator(mode='DIRECTORY')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(result.stdout.strip().isdigit())
        self.assertTrue((self.stage / 'provider').is_dir())

    def test_legacy_and_explicit_mount_mode_never_fall_back_to_directory(self):
        for mode in (None, 'MOUNT'):
            with self.subTest(mode=mode):
                result = self.run_validator(mode=mode)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('fstab', result.stderr)
                self.assertFalse((self.stage / 'provider').exists())

    def test_directory_does_not_allow_nfs_or_gfs2_and_unknown_modes(self):
        for kind, mode in (('NFS','DIRECTORY'), ('GFS2','DIRECTORY'), ('LOCAL','AUTO')):
            with self.subTest(kind=kind, mode=mode):
                result = self.run_validator(kind=kind, mode=mode)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.stage / 'provider').exists())

    def test_legacy_local_nfs_gfs2_mount_modes_remain_supported(self):
        for kind, filesystem in (('LOCAL','xfs'), ('NFS','nfs4'), ('GFS2','gfs2')):
            with self.subTest(kind=kind):
                result = self.run_validator(kind=kind, TEST_MOUNTED='1', TEST_FSTYPE=filesystem)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('999998', result.stdout.strip())

    def test_directory_mode_keeps_runtime_capacity_checks(self):
        result = self.run_validator(mode='DIRECTORY', required=str(2**62))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Insufficient capacity', result.stderr)


if __name__ == '__main__':
    unittest.main()
