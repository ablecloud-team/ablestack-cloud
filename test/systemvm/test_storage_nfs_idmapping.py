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


"""Exercise the actual embedded NFS readiness probe without privileged mutations."""
import hashlib
import ast
import os
import re
import tempfile
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2] / 'systemvm/debian/usr/local/bin/ablestack-storagectl'
TEXT = SOURCE.read_text()
BLOCK = next(value for value in re.findall("<<'PY'" + chr(10) + "(.*?)" + chr(10) + "PY", TEXT, re.S) if "def write_ganesha_configs(" in value)
TREE = ast.parse(BLOCK)

class NfsIdMappingTest(unittest.TestCase):
    def render(self, mode, root):
        node = next(item for item in TREE.body if isinstance(item, ast.FunctionDef) and item.name == 'write_ganesha_configs')
        namespace = {'hashlib':hashlib,
            'os': os, 'ganesha_conf_dir': root, 'id_mapping_mode': mode,
            'endpoint_key': lambda ip, port: ip.replace('.', '_') + '_' + str(port),
            'ganesha_bind_addr': lambda ip: ip,
            'nfs_protocols_for_mode': lambda value: '3,4' if value == 'V3V4_DUAL' else '4',
            'assign_ganesha_export_ids': lambda exports: exports,
            'render_ganesha_export': lambda export: 'EXPORT { Export_Id = 1; }',
        }
        exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE), 'exec'), namespace)
        return namespace['write_ganesha_configs']({
            ('10.1.1.10', 2049): [{'protocolMode': 'V4_ONLY'}],
            ('10.1.1.11', 2050): [{'protocolMode': 'V3V4_DUAL'}],
        }, 2049)

    def test_numeric_policy_is_identical_on_every_endpoint_and_keeps_v3_dual_mode(self):
        with tempfile.TemporaryDirectory() as root:
            configs = self.render('NUMERIC', root)
            self.assertEqual(2, len(configs))
            for item in configs:
                text = Path(item[1]).read_text()
                self.assertIn('Only_Numeric_Owners = true;', text)
                self.assertIn('Allow_Numeric_Owners = true;', text)
            self.assertIn('Protocols = 3,4;', Path(configs[1][1]).read_text())

    def test_compatible_name_domain_policy_is_explicit(self):
        with tempfile.TemporaryDirectory() as root:
            for item in self.render('NAME_DOMAIN', root):
                self.assertIn('Only_Numeric_Owners = false;', Path(item[1]).read_text())

if __name__ == '__main__':
    unittest.main()
