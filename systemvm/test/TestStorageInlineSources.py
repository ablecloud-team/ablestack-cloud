# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

from pathlib import Path
import ast,re,unittest
ROOT=Path(__file__).resolve().parents[2]
LIB=ROOT/"systemvm/debian/usr/local/lib/ablestack-storage"
CLI=ROOT/"systemvm/debian/usr/local/bin/ablestack-storagectl"


class StorageInlineSourcesTest(unittest.TestCase):
    def test_signed_rendered_entrypoint_matches_all_fixed_reviewed_library_bodies_exactly(self):
        modules=['rendered_generation','ganesha_dbus','nvme_credentials','native_renderers','native_render_validation','native_render_runtime','rendered_network','rendered_prerequisites','rendered_driver']
        expected=['import sys']
        for name in modules:
            value=(LIB/(name+'.py')).read_text()
            expected.append('\n'.join(line for line in value.splitlines() if not any(line.startswith('from '+item+' import ') for item in modules)))
        actual=CLI.read_text().split("<<'PYRENDEREDGENERATION'\n",1)[1].split("\nPYRENDEREDGENERATION",1)[0]
        actual=actual[:actual.index('\ntry:\n    action = sys.argv[1]')]
        self.assertEqual('\n'.join(expected),actual)
        ast.parse(actual)

    def test_signed_nfs_dbus_and_nvme_store_closures_match_reviewed_bodies(self):
        source=CLI.read_text()
        for name,marker,start in (('ganesha_dbus','GANESHA DBUS','EXPORT_PATH='),('nvme_credentials','NVME CREDENTIALS','"""Protected per-instance')):
            expected=(LIB/(name+'.py')).read_text();expected=expected[expected.index(start):]
            actual=source.split('# BEGIN EMBEDDED '+marker+'\n',1)[1].split('# END EMBEDDED '+marker,1)[0]
            self.assertEqual(expected.strip(),actual.strip())

if __name__=='__main__':unittest.main()
