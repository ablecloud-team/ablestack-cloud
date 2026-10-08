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

if __name__ == '__main__':
    unittest.main()
