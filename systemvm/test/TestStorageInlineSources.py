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
        modules=['rendered_generation','ganesha_dbus','nvme_credentials','native_renderers','native_render_validation','native_render_runtime','rendered_network','rendered_prerequisites','rendered_credentials','rendered_driver']
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

    def test_signed_service_and_ad_readonly_bodies_match_reviewed_modules_exactly(self):
        source=CLI.read_text()
        maintenance=source.split("<<'PYMAINTENANCE'\n",1)[1].split("\nimport subprocess\nimport sys\n\ndef native_generation",1)[0]
        modules=[]
        for name,start in (("template_maintenance",'"""Persistent'),("service_maintenance",'"""Approved')):
            value=(LIB/(name+".py")).read_text();value=value[value.index(start):].replace("from template_maintenance import Maintenance,scope,marker_kind\n","")
            modules.append(value.rstrip())
        self.assertEqual("\n".join(modules),maintenance)
        ad=source.split("<<'PYADIDENTITY'\n",1)[1].split("\nimport sys\ntry:",1)[0]
        self.assertEqual((LIB/"ad_identity.py").read_text().rstrip(),ad.rstrip())

    def test_signed_root_network_and_handler_availability_are_exact_and_production_false(self):
        source=CLI.read_text();actual=source.split("<<'PYROOTNETWORK'\n",1)[1].split("\nimport sys\ntry:",1)[0]
        expected=(LIB/"root_network_bootstrap.py").read_text();expected=expected[expected.index('"""Fresh ROOT'):]
        self.assertEqual(expected.rstrip(),actual.rstrip())
        import sys
        sys.path.insert(0,str(LIB))
        from rendered_generation import RenderedGeneration
        import tempfile
        with tempfile.TemporaryDirectory() as scratch:
            result=RenderedGeneration(Path(scratch)/"absent").status()
            self.assertEqual(["RENDERED_CONFIG_GENERATION_HANDLER"],result["supportedFeatures"]);self.assertFalse(result["fullFourProtocolActivationSupported"])
            self.assertFalse((Path(scratch)/"absent").exists())

if __name__=='__main__':unittest.main()
