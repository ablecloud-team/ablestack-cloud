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
        modules=['ad_authority','rendered_generation','ganesha_dbus','nvme_credentials','native_renderers','native_render_validation','native_render_runtime','rendered_network','rendered_prerequisites','rendered_credentials','posix_root_initialization','root_identity_reference','root_configuration_capsule','root_source_identity_checkpoint','root_source_recovery','service_identity_source','root_retained_authorization','rendered_driver']
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

    def test_signed_iscsi_auth_matches_reviewed_ram_to_configfs_body(self):
        source=CLI.read_text()
        actual=source.split("# BEGIN EMBEDDED ISCSI AUTH\n",1)[1].split("# END EMBEDDED ISCSI AUTH",1)[0]
        self.assertEqual((LIB/"iscsi_auth.py").read_text().rstrip(),actual.rstrip())
        block=source[source.index("apply_iscsi_targets() {"):source.index("apply_nvmeof_subsystems() {")]
        self.assertNotIn('f"password={password}"',block);self.assertNotIn('f"mutual_password={mutual_password}"',block)
        self.assertNotIn('run_targetcli("saveconfig")',block)
        self.assertIn('ConfigfsIscsiAuth().apply(iqn,initiator,acl_config,secrets)',block)
        ast.parse(actual)

    def test_signed_private_source_collector_is_quiescent_no_backup_and_byte_bound(self):
        source=CLI.read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        actual=ast.parse(source);expected=ast.parse((LIB/"identity_capsule.py").read_text())
        for name in ("regular_file","collect"):
            got=next(item for item in actual.body if isinstance(item,ast.FunctionDef) and item.name==name)
            wanted=next(item for item in expected.body if isinstance(item,ast.FunctionDef) and item.name==name)
            self.assertEqual(ast.dump(wanted),ast.dump(got))
        collect=next(item for item in actual.body if isinstance(item,ast.FunctionDef) and item.name=="collect")
        self.assertNotIn("tdbbackup",ast.unparse(collect));self.assertIn("live_identity_database_holders",ast.unparse(collect))

if __name__=='__main__':unittest.main()
