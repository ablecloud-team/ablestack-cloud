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

"""Execute consumer shell snippets against containerd 1.x/2.x fixtures."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

RESOURCES = Path(__file__).resolve().parents[2] / 'main/resources'
CONSUMERS = [RESOURCES / 'conf' / name for name in (
    'k8s-control-node.yml', 'k8s-control-node-add.yml', 'k8s-node.yml'
)] + [RESOURCES / 'script/upgrade-kubernetes.sh']
DIGEST = 'a' * 64
IMAGE = 'registry.k8s.io/pause@sha256:' + DIGEST
LEGACY = '''version = 2
[plugins."io.containerd.grpc.v1.cri"]
  sandbox_image = "registry.k8s.io/pause:3.9"
  enable_selinux = false
'''
CURRENT = '''version = 3
[plugins.'io.containerd.cri.v1.images'.pinned_images]
  sandbox = 'registry.k8s.io/pause:3.10.2'
[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runc]
  sandboxer = 'podsandbox'
  SystemdCgroup = true
'''


def pause_snippet(source):
    start = source.index('PAUSE_IMAGE=""')
    end = source.index('echo "Configured ISO pause image: $PAUSE_IMAGE"', start)
    return textwrap.dedent(source[start:end] + source[end:].splitlines()[0])


def import_snippet(source):
    start = source.index('CTR_IMPORT_OPTIONS=()')
    if 'setup_complete=true' in source[start:]:
        success = source.index('setup_complete=true', start)
        end = source.index('fi', success) + 2
    else:
        end = source.index('if [ -e "${BINARIES_DIR}/provider.yaml" ]', start)
    return textwrap.dedent(source[start:end])


class IsoConsumerRuntimeTest(unittest.TestCase):
    def run_pause(self, resource, config, imported=IMAGE, mapping=True):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'docker').mkdir()
            if mapping:
                (root / 'docker/images.list').write_text(DIGEST + '.tar registry.k8s.io/pause\n')
            (root / 'config.toml').write_text(config)
            (root / 'ctr').write_text('#!/bin/sh\nprintf "%s\\n" "$IMPORTED"\n')
            (root / 'ctr').chmod(0o755)
            code = pause_snippet(resource.read_text()).replace('/etc/containerd/config.toml', str(root / 'config.toml'))
            result = subprocess.run(['bash', '-e', '-c', code], env={**os.environ,
                'PATH': str(root) + ':' + os.environ['PATH'], 'BINARIES_DIR': str(root),
                'IMPORTED': imported}, capture_output=True, text=True)
            return result, (root / 'config.toml').read_text(), list(root.glob('*.tmp'))

    def test_supported_configs_use_iso_digest_and_preserve_runtime(self):
        for resource in CONSUMERS:
            for config in (LEGACY, CURRENT):
                with self.subTest(resource=resource.name, config=config.splitlines()[0]):
                    result, updated, leftovers = self.run_pause(resource, config)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn(IMAGE, updated)
                    self.assertNotIn('pause:3.', updated)
                    for line in config.splitlines():
                        if not ('sandbox_image =' in line or 'sandbox =' in line):
                            self.assertIn(line, updated)
                    self.assertFalse(leftovers)

    def test_missing_import_fails_without_changing_config(self):
        for resource in CONSUMERS:
            with self.subTest(resource=resource.name):
                result, updated, _ = self.run_pause(resource, CURRENT, imported='registry.k8s.io/pause:3.10.2')
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(updated, CURRENT)

    def test_unknown_config_fails_and_removes_temporary_file(self):
        for resource in CONSUMERS:
            with self.subTest(resource=resource.name):
                config = 'version = 3\n[unknown]\nsandbox = "unrelated"\n'
                result, updated, leftovers = self.run_pause(resource, config)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(updated, config)
                self.assertFalse(leftovers)

    def test_legacy_iso_without_mapping_still_uses_local_pause(self):
        for resource in CONSUMERS:
            with self.subTest(resource=resource.name):
                result, updated, _ = self.run_pause(resource, LEGACY, imported='registry.k8s.io/pause:3.10.1', mapping=False)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('registry.k8s.io/pause:3.10.1', updated)

    def test_import_preserves_digests_with_version_supported_options(self):
        for resource in CONSUMERS:
            for local_supported in (False, True):
                with self.subTest(resource=resource.name, local_supported=local_supported), \
                        tempfile.TemporaryDirectory(prefix='iso consumer ') as directory:
                    root = Path(directory)
                    (root / 'docker').mkdir()
                    archive = root / 'docker' / (DIGEST + '.tar')
                    archive.write_bytes(b'archive fixture')
                    (root / 'docker/images.list').write_text(DIGEST + '.tar registry.k8s.io/pause\n')
                    (root / 'docker/README').write_text('not an archive')
                    (root / 'ctr').write_text(textwrap.dedent(r"""
                        #!/usr/bin/env python3
                        import json, os, sys
                        args = sys.argv[1:]
                        supported = os.environ['LOCAL_SUPPORTED'] == 'true'
                        if '--help' in args:
                            print('--digests --base-name' + (' --local' if supported else ''))
                            sys.exit(0)
                        with open(os.environ['IMPORT_ARGS'], 'a') as output:
                            output.write(json.dumps(args) + '\n')
                        sys.exit(0 if ('--local' in args) == supported else 1)
                        """).lstrip())
                    (root / 'ctr').chmod(0o755)
                    code = import_snippet(resource.read_text()) + '\necho IMPORT_COMPLETE\n'
                    result = subprocess.run(['bash', '-e', '-c', code], env={**os.environ,
                        'PATH': str(root) + ':' + os.environ['PATH'], 'BINARIES_DIR': str(root),
                        'MAX_SETUP_CRUCIAL_CMD_ATTEMPTS': '3',
                        'LOCAL_SUPPORTED': str(local_supported).lower(),
                        'IMPORT_ARGS': str(root / 'imports')}, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn('IMPORT_COMPLETE', result.stdout)
                    imports = (root / 'imports').read_text().splitlines()
                    self.assertEqual(len(imports), 1)
                    expected = ['-n', 'k8s.io', 'image', 'import']
                    if local_supported:
                        expected.append('--local')
                    expected += ['--digests', '--base-name', 'registry.k8s.io/pause', str(archive)]
                    self.assertEqual(json.loads(imports[0]), expected)

    def test_repeated_image_import_failure_aborts_install(self):
        for resource in CONSUMERS[:3]:
            with self.subTest(resource=resource.name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / 'docker').mkdir()
                (root / 'docker' / (DIGEST + '.tar')).write_bytes(b'broken archive')
                (root / 'docker/images.list').write_text(DIGEST + '.tar registry.k8s.io/pause\n')
                (root / 'ctr').write_text('#!/bin/sh\ncase " $* " in *" --help "*) echo "--digests --base-name"; exit 0;; esac\necho attempt >> "$ATTEMPTS"\nexit 1\n')
                (root / 'ctr').chmod(0o755)
                source = resource.read_text()
                start = source.index('CTR_IMPORT_OPTIONS=()')
                success = source.index('setup_complete=true', start)
                end = source.index('fi', success) + 2
                code = textwrap.dedent(source[start:end]) + '\necho SHOULD_NOT_SUCCEED\n'
                result = subprocess.run(['bash', '-e', '-c', code], env={**os.environ,
                    'PATH': str(root) + ':' + os.environ['PATH'], 'BINARIES_DIR': str(root),
                    'MAX_SETUP_CRUCIAL_CMD_ATTEMPTS': '3', 'ATTEMPTS': str(root / 'attempts')},
                    capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('SHOULD_NOT_SUCCEED', result.stdout)
                self.assertEqual(len((root / 'attempts').read_text().splitlines()), 3)


if __name__ == '__main__':
    unittest.main()
