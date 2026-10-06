# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http: #www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.


"""Exercise the actual embedded NFS readiness probe without privileged mutations."""
import ast
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace

SOURCE = Path(__file__).resolve().parents[2] / 'systemvm/debian/usr/local/bin/ablestack-storagectl'
CODE = SOURCE.read_text().split("<<'PYRESOURCES'\n", 1)[1].split('\nPYRESOURCES', 1)[0]

class ResourceReadinessTest(unittest.TestCase):
    def run_probe(self, mode, possible='0-15', request=''):
        files = {
            '/proc/meminfo': 'MemTotal: 4000000 kB\nMemAvailable: 3000000 kB\n',
            '/sys/devices/system/cpu/online': '0-1',
            '/sys/devices/system/cpu/possible': possible,
            '/sys/devices/system/memory/auto_online_blocks': 'offline',
            '/run/request.json': request,
        }
        class FakePath:
            def __init__(self, path): self.path = path
            def is_file(self): return self.path in files
            def read_text(self): return files[self.path]
            def write_text(self, value): files[self.path] = value
        tree = ast.parse(CODE)
        tree.body = [node for node in tree.body if not isinstance(node, (ast.Import, ast.ImportFrom))]
        stdout = io.StringIO()
        code = None
        with redirect_stdout(stdout):
            try:
                exec(compile(tree, str(SOURCE), 'exec'), {'json': json, 'Path': FakePath, 'sys': SimpleNamespace(argv=['probe', '/run/request.json', mode])})
            except SystemExit as exit:
                code = exit.code
        return json.loads(stdout.getvalue()), files, code

    def test_resource_observation_is_read_only_and_accepts_empty_payload(self):
        result, files, code = self.run_probe('resources')
        self.assertEqual(0, code)
        self.assertEqual(2, result['onlineCpuCount'])
        self.assertEqual(16, result['possibleCpuCount'])
        self.assertEqual(4096000000, result['memoryTotalBytes'])
        self.assertEqual('offline', files['/sys/devices/system/memory/auto_online_blocks'])

    def test_scale_preflight_enables_supported_memory_hotplug(self):
        result, files, code = self.run_probe('prepare-scale', request='{"targetCpuCount":4}')
        self.assertEqual(0, code)
        self.assertTrue(result['success'])
        self.assertEqual('online', files['/sys/devices/system/memory/auto_online_blocks'])

    def test_incompatible_cpu_topology_is_blocked_without_memory_changes(self):
        result, files, code = self.run_probe('prepare-scale', possible='0-1', request='{"targetCpuCount":4}')
        self.assertEqual(1, code)
        self.assertFalse(result['success'])
        self.assertEqual('offline', files['/sys/devices/system/memory/auto_online_blocks'])

if __name__ == '__main__':
    unittest.main()
