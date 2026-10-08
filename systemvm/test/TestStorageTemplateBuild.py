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

import json
import errno
import fcntl
import importlib.util
import stat
from contextlib import contextmanager
from unittest import mock
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
WRITE = ROOT / 'tools/appliance/scripts/write_storage_template_manifest.py'
VALIDATE = ROOT / 'tools/appliance/scripts/validate_storage_template.py'
SIGN = ROOT / 'tools/build/with-storage-runtime-signing-key.py'

class StorageTemplateBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'image'
        shutil.copytree(ROOT / 'systemvm/debian/usr/local', self.root / 'usr/local', ignore=shutil.ignore_patterns('__pycache__'))
        self.lock = json.loads((ROOT / 'tools/appliance/systemvmtemplate/storage-kernel-amd64.json').read_text())
        self.boot = self.root / 'boot'
        self.boot.mkdir()
        version = self.lock['kernelVersion']
        (self.boot / ('config-' + version)).write_text('\n'.join(k + '=' + v for k, v in self.lock['requiredConfig'].items()))
        for kind in ('vmlinuz-', 'initrd.img-'):
            (self.boot / (kind + version)).touch()
        self.modules = self.root / 'lib/modules' / version
        self.modules.mkdir(parents=True)
        for name in ('nvmet', 'nvmet-tcp', 'nvme-auth'):
            (self.modules / (name + '.ko')).touch()
        subprocess.run(['python3', str(WRITE), '--image-root', str(self.root), '--source-root', str(ROOT),
                        '--version', 'test-template', '--runtime-version', 'test-runtime'], check=True)

    def validate(self):
        return subprocess.run(['python3', str(VALIDATE), str(self.root)], capture_output=True, text=True)

    def test_template_contains_explicit_capability_and_source_hashes(self):
        self.assertEqual(0, self.validate().returncode)
        manifest = json.loads((self.root / 'etc/ablestack-storage/template-manifest.json').read_text())
        self.assertEqual('test-template', manifest['templateVersion'])
        self.assertEqual('test-runtime', manifest['runtimeBundleVersion'])
        self.assertEqual('4.23.0.0', manifest['platformVersion'])
        self.assertEqual(manifest['platformVersion'], manifest['productVersion'])
        self.assertEqual(manifest['sourceFiles']['pom.xml'], manifest['platformVersionSource']['sha256'])
        self.assertEqual(64, len(manifest['sourceTreeSha256']))
        self.assertEqual('true', manifest['registrationDetails']['storage.service.configuration.generation.adopt'])

    def test_build_filename_version_cannot_replace_the_platform_attestation(self):
        path = self.root / 'etc/ablestack-storage/template-manifest.json'
        manifest = json.loads(path.read_text()); manifest['platformVersion'] = '4.23.0.0.88'
        path.write_text(json.dumps(manifest))
        self.assertNotEqual(0, self.validate().returncode)

    def test_kernel_without_auth_is_rejected(self):
        (self.boot / ('config-' + self.lock['kernelVersion'])).write_text('# CONFIG_NVME_TARGET_AUTH is not set')
        self.assertNotEqual(0, self.validate().returncode)

    def test_runtime_source_tamper_is_rejected(self):
        with (self.root / 'usr/local/bin/ablestack-storagectl').open('a') as f:
            f.write('\n# tamper\n')
        self.assertNotEqual(0, self.validate().returncode)

    def test_missing_nvme_auth_module_is_rejected(self):
        (self.modules / 'nvme-auth.ko').unlink()
        self.assertNotEqual(0, self.validate().returncode)

    def test_new_kvm_builder_and_export_require_sparse_metadata_allocation(self):
        recipe = json.loads((ROOT / 'tools/appliance/systemvmtemplate/template-base_x86_64-target_x86_64.json').read_text())
        builder = next(value for value in recipe['builders'] if value['type'] == 'qemu')
        self.assertEqual(['-o', 'preallocation=metadata'], builder['qemu_img_args']['create'])
        self.assertEqual(['-o', 'preallocation=metadata'], builder['qemu_img_args']['convert'])
        export = (ROOT / 'tools/appliance/build.sh').read_text()
        self.assertIn('qemu-img convert -o compat=0.10,preallocation=metadata -f qcow2 -O qcow2', export)
        self.assertNotIn('qemu-img convert -o compat=0.10 -f qcow2 -c -O qcow2', export)

    def test_release_signing_secret_absence_blocks_publication(self):
        env = dict(os.environ)
        env.pop('STORAGE_RUNTIME_SIGNING_PRIVATE_KEY', None)
        public = Path(self.temp.name) / 'keys'
        result = subprocess.run(['python3', str(SIGN), '--key-id', 'test-key', '--trusted-key-dir', str(public),
                                 '--require-stable', 'true', '--', 'true'], env=env, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('require the stable', result.stderr)
        self.assertFalse(public.exists())

    def test_ephemeral_key_remains_a_descriptor_and_only_public_key_is_saved(self):
        env = dict(os.environ)
        env.pop('STORAGE_RUNTIME_SIGNING_PRIVATE_KEY', None)
        public = Path(self.temp.name) / 'keys'
        result = subprocess.run(['python3', str(SIGN), '--key-id', 'test-key', '--trusted-key-dir', str(public),
                                 '--', 'bash', '-c', 'test -r "$STORAGE_RUNTIME_SIGNING_PRIVATE_KEY_FILE" && openssl pkey -in "$STORAGE_RUNTIME_SIGNING_PRIVATE_KEY_FILE" -check -noout'],
                                env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['test-key.pem'], [p.name for p in public.iterdir()])
        self.assertIn('PUBLIC KEY', (public / 'test-key.pem').read_text())
        self.assertNotIn('PRIVATE KEY', result.stdout + result.stderr)


class StorageSigningKeySealTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('storage_signing_wrapper', SIGN)
        cls.sign = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.sign)

    def assert_closed(self, fd):
        with self.assertRaises(OSError) as failure:
            os.fstat(fd)
        self.assertEqual(errno.EBADF, failure.exception.errno)

    def test_real_seals_reject_write_resize_and_new_seals(self):
        private = bytearray(os.urandom(32))
        with self.sign.sealed_signing_key(private) as fd:
            self.assertEqual(bytearray(32), private)
            info = os.fstat(fd)
            self.assertEqual(0o600, stat.S_IMODE(info.st_mode))
            self.assertEqual(os.geteuid(), info.st_uid)
            self.assertTrue(fcntl.fcntl(fd, fcntl.F_GETFD) & fcntl.FD_CLOEXEC)
            self.assertEqual(self.sign.REQUIRED_SEALS,
                             fcntl.fcntl(fd, fcntl.F_GET_SEALS) & self.sign.REQUIRED_SEALS)
            for change in (lambda: os.write(fd, b'x'), lambda: os.ftruncate(fd, 0),
                           lambda: os.ftruncate(fd, 64),
                           lambda: fcntl.fcntl(fd, fcntl.F_ADD_SEALS, fcntl.F_SEAL_SEAL)):
                with self.assertRaises(OSError) as failure:
                    change()
                self.assertEqual(errno.EPERM, failure.exception.errno)
        self.assert_closed(fd)

    def test_context_failure_closes_descriptor_and_erases_input(self):
        private = bytearray(os.urandom(32))
        with self.assertRaisesRegex(RuntimeError, 'synthetic builder failure'):
            with self.sign.sealed_signing_key(private) as fd:
                raise RuntimeError('synthetic builder failure')
        self.assertEqual(bytearray(32), private)
        self.assert_closed(fd)

    def test_partial_writes_complete_before_sealing(self):
        expected = b'noncredential test input'
        private = bytearray(expected)
        real_write = os.write
        with mock.patch.object(self.sign.os, 'write', side_effect=lambda fd, data: real_write(fd, data[:3])):
            with self.sign.sealed_signing_key(private) as fd:
                self.assertEqual(expected, os.read(fd, 128))
        self.assertEqual(bytearray(len(expected)), private)
        self.assert_closed(fd)

    def test_descriptor_write_failure_closes_and_erases_before_yield(self):
        private = bytearray(os.urandom(32))
        created = []
        real_create = os.memfd_create
        def remember(*args):
            fd = real_create(*args)
            created.append(fd)
            return fd
        with mock.patch.object(self.sign.os, 'memfd_create', side_effect=remember), \
                mock.patch.object(self.sign.os, 'write', side_effect=OSError(errno.EIO, 'synthetic write failure')):
            with self.assertRaises(OSError):
                with self.sign.sealed_signing_key(private):
                    self.fail('An incomplete key must not reach a builder')
        self.assertEqual(bytearray(32), private)
        self.assertEqual(1, len(created))
        self.assert_closed(created[0])

    def test_unverified_seals_block_builder_and_close_descriptor(self):
        private = bytearray(os.urandom(32))
        created = []
        real_fcntl = fcntl.fcntl
        def intercept(fd, operation, *args):
            if operation == fcntl.F_GET_SEALS:
                created.append(fd)
                return 0
            return real_fcntl(fd, operation, *args)
        with mock.patch.object(self.sign.fcntl, 'fcntl', side_effect=intercept):
            with self.assertRaisesRegex(RuntimeError, 'protection could not be verified'):
                with self.sign.sealed_signing_key(private):
                    self.fail('Unverified protection must block the builder')
        self.assertEqual(bytearray(32), private)
        self.assert_closed(created[0])

    def test_actual_builder_inherits_only_sealed_fd_and_saves_public_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            public = Path(temporary) / 'keys'
            env = dict(os.environ)
            env.pop('STORAGE_RUNTIME_SIGNING_PRIVATE_KEY', None)
            unrelated = os.memfd_create('unrelated-noncredential')
            leaked = fcntl.fcntl(unrelated, fcntl.F_DUPFD, 180)
            os.set_inheritable(leaked, True)
            code = """import os,stat,fcntl,errno,json
path=os.environ['STORAGE_RUNTIME_SIGNING_PRIVATE_KEY_FILE'];fd=os.open(path,os.O_RDWR);info=os.fstat(fd)
required=fcntl.F_SEAL_WRITE|fcntl.F_SEAL_GROW|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_SEAL
try:os.write(fd,b'forbidden replacement');writeDenied=False
except OSError as failure:writeDenied=failure.errno==errno.EPERM
try:os.fstat(180);unrelatedInherited=True
except OSError:unrelatedInherited=False
out={'sealed':fcntl.fcntl(fd,fcntl.F_GET_SEALS)&required==required,'mode600':stat.S_IMODE(info.st_mode)==0o600,'ownerMatches':info.st_uid==os.geteuid(),'writeDenied':writeDenied,'rawKeyEnvironmentAbsent':'STORAGE_RUNTIME_SIGNING_PRIVATE_KEY' not in os.environ,'unrelatedInherited':unrelatedInherited}
os.close(fd);print(json.dumps(out));raise SystemExit(0 if all(out[k] for k in out if k!='unrelatedInherited') and not unrelatedInherited else 2)
"""
            try:
                result = subprocess.run(['python3', str(SIGN), '--key-id', 'test-key',
                                         '--trusted-key-dir', str(public), '--', 'python3', '-c', code],
                                        env=env, pass_fds=(leaked,), capture_output=True, text=True)
            finally:
                os.close(leaked)
                os.close(unrelated)
            self.assertEqual(0, result.returncode, result.stderr)
            observation = json.loads(result.stdout.splitlines()[-1])
            self.assertTrue(observation['sealed'])
            self.assertTrue(observation['writeDenied'])
            self.assertFalse(observation['unrelatedInherited'])
            self.assertEqual(['test-key.pem'], [path.name for path in public.iterdir()])
            self.assertIn('PUBLIC KEY', (public / 'test-key.pem').read_text())
            self.assertNotIn('PRIVATE KEY', result.stdout + result.stderr)

    def test_builder_launch_failure_closes_descriptor(self):
        captured = []
        original = self.sign.sealed_signing_key
        original_run = subprocess.run
        def launch(*args, **kwargs):
            if args[0][0] == 'missing-test-command':
                raise FileNotFoundError('synthetic command unavailable')
            return original_run(*args, **kwargs)
        @contextmanager
        def observe(private):
            with original(private) as fd:
                captured.append((fd, private))
                yield fd
        with tempfile.TemporaryDirectory() as temporary, \
                mock.patch.object(self.sign, 'sealed_signing_key', side_effect=observe), \
                mock.patch.object(self.sign.subprocess, 'run', side_effect=launch), \
                mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop('STORAGE_RUNTIME_SIGNING_PRIVATE_KEY', None)
            with self.assertRaises(FileNotFoundError):
                self.sign.main(['--key-id', 'test-key', '--trusted-key-dir', temporary, '--', 'missing-test-command'])
        self.assertEqual(1, len(captured))
        self.assertFalse(any(captured[0][1]))
        self.assert_closed(captured[0][0])

if __name__ == '__main__':
    unittest.main()
