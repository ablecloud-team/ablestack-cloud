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


def pending_nfs_readonly_body():
    import io,tokenize
    source=(LIB/"config_generation.py").read_text();tree=ast.parse(source)
    functions=[ast.get_source_segment(source,node) for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in ("read_json","redact")]
    klass=next(node for node in tree.body if isinstance(node,ast.ClassDef) and node.name=="Generation")
    members=[]
    for node in klass.body:
        if isinstance(node,ast.Assign) or isinstance(node,ast.FunctionDef) and node.name in ("__init__","files","digest","status","request","scoped"):
            members.append("    "+ast.get_source_segment(source,node))
    limit=next(node for node in tree.body if isinstance(node,ast.Assign) and any(isinstance(item,ast.Name) and item.id=="MAX_BYTES" for item in node.targets))
    value=ast.get_source_segment(source,limit)+"\n"+"\n\n".join(functions)+"\n\nclass Generation:\n"+"\n\n".join(members)
    aliases={"MAX_BYTES":"PENDING_NFS_MAX_BYTES","read_json":"pending_nfs_read","redact":"pending_nfs_redact","Generation":"PendingNfsGeneration","uuid":"pending_nfs_uuid"}
    tokens=tokenize.generate_tokens(io.StringIO(value).readline)
    return "import uuid as pending_nfs_uuid\n"+tokenize.untokenize([token._replace(string=aliases.get(token.string,token.string)) if token.type==tokenize.NAME else token for token in tokens]).rstrip()

def current_smb_reviewed_bodies():
    import io,tokenize
    def body(name):
        value=(LIB/(name+".py")).read_text()
        return value[value.index(chr(34)*3):].rstrip()
    def subset(name,klass,methods,alias):
        value=(LIB/(name+".py")).read_text();tree=ast.parse(value)
        chosen=next(node for node in tree.body if isinstance(node,ast.ClassDef) and node.name==klass)
        return "class "+alias+":\n"+"\n\n".join("    "+ast.get_source_segment(value,node) for node in chosen.body if isinstance(node,ast.FunctionDef) and node.name in methods)
    def function(name,fn):
        value=(LIB/(name+".py")).read_text()
        return ast.get_source_segment(value,next(node for node in ast.parse(value).body if isinstance(node,ast.FunctionDef) and node.name==fn))
    def aliases(value,mapping):
        tokens=tokenize.generate_tokens(io.StringIO(value).readline)
        return tokenize.untokenize([token._replace(string=mapping.get(token.string,token.string)) if token.type==tokenize.NAME else token for token in tokens]).rstrip()
    lock=subset("pending_nfs_authorization","PendingNfsAuthorization",{"lock"},"CurrentSmbWriterLock")
    current=body("smb_current_retention")
    dependencies=("smb_identity","samba_public_sid","pending_nfs_authorization","identity_capsule","service_identity_cipher","samba_current_public_sid")
    current="\n".join(line for line in current.splitlines() if not any(line.startswith("from "+name+" import ") for name in dependencies))
    current=aliases(current,{"PendingNfsAuthorization":"CurrentSmbWriterLock"})
    smb=body("smb_identity").replace("            from smb_current_retention import SmbCurrentRetention\n","")
    raw_sid=body("samba_current_public_sid").replace("from samba_public_sid import PublicSidTdbData, SambaPublicSidMissing, samba_sid_bytes\n","")
    codec=lock+"\n\n"+smb+"\n\n"+raw_sid+"\n\n"+current
    files=subset("service_identity_cipher","ServiceIdentityCipher",{"__init__","read","write"},"CurrentSmbCipherFiles")
    plain="\n\n".join((smb,body("samba_public_sid"),raw_sid,"MAX_CAPSULE_BYTES = 8*1024*1024\n"+function("identity_capsule","regular_file"),
        function("service_identity_cipher","service_cipher_json"),files,lock,aliases(current,{"ServiceIdentityCipher":"CurrentSmbCipherFiles"})))
    return codec,plain

class StorageInlineSourcesTest(unittest.TestCase):
    def test_signed_rendered_entrypoint_matches_all_fixed_reviewed_library_bodies_exactly(self):
        modules=['ad_authority','semantic_identity_alias','rendered_generation','ganesha_dbus','nvme_credentials','native_renderers','native_render_validation','native_render_runtime','rendered_network','rendered_prerequisites','rendered_credentials','posix_root_initialization','root_identity_reference','root_configuration_capsule','root_source_identity_checkpoint','root_source_recovery','samba_public_sid','service_identity_source','service_identity_cipher','template_maintenance','service_maintenance','service_identity_target','root_identity_target','root_retained_authorization','root_ad_imported_authorization','root_ad_identity_authority','pending_nfs_authorization','rendered_driver']
        expected=['import sys']
        for name in modules:
            value=(LIB/(name+'.py')).read_text()
            if name=="root_ad_imported_authorization":value="import subprocess\n"+value[value.index("def root_ad_runtime_readback("):value.index("class RootAdImportedAuthorization:")].rstrip()
            if name=="pending_nfs_authorization":
                expected.append(pending_nfs_readonly_body())
                value=value.replace("from config_generation import Generation as PendingNfsGeneration, read_json as pending_nfs_read\n","")
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
        modules=("ad_authority","root_ad_imported_authorization","root_ad_identity_authority","samba_public_sid","ad_identity")
        parts=[]
        for name in modules:
            value=(LIB/(name+".py")).read_text()
            if name=="root_ad_imported_authorization":value="import subprocess\n"+value[value.index("def root_ad_runtime_readback("):value.index("class RootAdImportedAuthorization:")].rstrip()
            parts.append("\n".join(line for line in value.splitlines() if not any(line.startswith("from "+item+" import ") for item in modules)))
        expected="\n".join(parts)
        self.assertEqual(expected,ad.rstrip())

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

    def test_signed_ad_lifecycle_closure_matches_all_fixed_reviewed_modules(self):
        source=CLI.read_text();actual=source.split("<<'PYADLIFECYCLE'\n",1)[1].split("\nimport sys\ntry:",1)[0]
        modules=["posix_root_initialization","ad_authority","service_identity_cipher","samba_public_sid","root_ad_imported_authorization","root_ad_identity_authority","ad_identity","local_sam_bootstrap","semantic_ad_source","ad_winbind","ad_lifecycle"];parts=[]
        for name in modules:
            value=(LIB/(name+".py")).read_text()
            if name=="semantic_ad_source":value=value[value.index("def semantic_new_target("):]
            if name=="root_ad_imported_authorization":value="import subprocess\n"+value[value.index("def root_ad_runtime_readback("):value.index("class RootAdImportedAuthorization:")].rstrip()
            parts.append("\n".join(line for line in value.splitlines() if not any(line.startswith("from "+item+" import ") for item in modules)))
        self.assertEqual("\n".join(parts),actual);ast.parse(actual)
        oldjoin=source[source.index("smb_domain_join() {"):source.index("apply_iscsi_targets() {")]
        self.assertNotIn("%{password}",oldjoin);self.assertNotIn("kinit",oldjoin)

    def test_signed_forward_root_imported_authority_matches_closed_reviewed_crypto_runtime_body(self):
        source=CLI.read_text();actual=source.split("# BEGIN EMBEDDED ROOT AD IMPORTED AUTHORITY\n",1)[1].split("\n# END EMBEDDED ROOT AD IMPORTED AUTHORITY",1)[0]
        dependencies=("ad_authority","samba_public_sid","root_identity_reference","root_configuration_capsule","identity_capsule","posix_root_initialization")
        expected="\n".join(line for line in (LIB/"root_ad_imported_authorization.py").read_text().splitlines() if not any(line.startswith("from "+name+" import ") for name in dependencies))
        self.assertEqual(expected,actual);ast.parse(actual)
        self.assertEqual(1,source.count("# BEGIN EMBEDDED ROOT AD IMPORTED AUTHORITY\n"))
        self.assertIn('elif action=="ad-authorize-imported":',source)

    def test_signed_semantic_original_source_parser_matches_reviewed_crypto_consumer(self):
        source=CLI.read_text();actual=source.split("# BEGIN EMBEDDED AD SEMANTIC SOURCE\n",1)[1].split("\n# END EMBEDDED AD SEMANTIC SOURCE",1)[0]
        expected="\n".join(line for line in (LIB/"semantic_ad_source.py").read_text().splitlines() if not line.startswith("from identity_capsule import "))
        self.assertEqual(expected,actual);ast.parse(actual)

    def test_signed_root_target_cipher_matches_fixed_typed_publisher_and_dispatchers(self):
        source=CLI.read_text();actual=source.split("# BEGIN EMBEDDED ROOT TARGET CIPHER\n",1)[1].split("\n# END EMBEDDED ROOT TARGET CIPHER",1)[0]
        expected="\n".join(line for line in (LIB/"root_target_cipher.py").read_text().splitlines() if not line.startswith(("from service_target_cipher import ","from root_identity_reference import ")))
        self.assertEqual(expected,actual);ast.parse(actual)
        self.assertIn('elif action=="export-root-target":',source)
        self.assertIn('|render-ad-join-inverse-guard) rendered_generation_command',source)

    def test_signed_target_cipher_matches_codec_only_publisher_and_exposes_no_public_retain_rpc(self):
        source=CLI.read_text();actual=source.split("# BEGIN EMBEDDED SERVICE TARGET CIPHER\n",1)[1].split("\n# END EMBEDDED SERVICE TARGET CIPHER",1)[0]
        expected="\n".join(line for line in (LIB/"service_target_cipher.py").read_text().splitlines() if not line.startswith("from service_identity_cipher import "))
        self.assertEqual(expected,actual);ast.parse(actual)
        self.assertNotIn("render-service-target-retain",source)
        self.assertIn('elif action=="export-target":',source)

    def test_signed_iscsi_auth_matches_reviewed_ram_to_configfs_body(self):
        source=CLI.read_text()
        actual=source.split("# BEGIN EMBEDDED ISCSI AUTH\n",1)[1].split("# END EMBEDDED ISCSI AUTH",1)[0]
        self.assertEqual((LIB/"iscsi_auth.py").read_text().rstrip(),actual.rstrip())
        block=source[source.index("apply_iscsi_targets() {"):source.index("apply_nvmeof_subsystems() {")]
        self.assertNotIn('f"password={password}"',block);self.assertNotIn('f"mutual_password={mutual_password}"',block)
        self.assertNotIn('run_targetcli("saveconfig")',block)
        self.assertIn('ConfigfsIscsiAuth().apply(iqn,initiator,acl_config,secrets)',block)
        ast.parse(actual)

    def test_signed_service_cipher_producer_body_is_exact_without_caller_publication_rpc(self):
        source=CLI.read_text()
        actual=source.split("# BEGIN EMBEDDED SERVICE CIPHER\n",1)[1].split("\n# END EMBEDDED SERVICE CIPHER",1)[0]
        expected=(LIB/"service_identity_cipher.py").read_text()
        expected=expected[expected.index('"""Native-only AFTERSTOP'):]
        self.assertEqual(expected,actual)
        self.assertNotIn("render-service-retain-identity",source)
    def test_signed_private_source_collector_is_quiescent_no_backup_and_byte_bound(self):
        source=CLI.read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        actual=ast.parse(source);expected=ast.parse((LIB/"identity_capsule.py").read_text())
        for name in ("regular_file","collect"):
            got=next(item for item in actual.body if isinstance(item,ast.FunctionDef) and item.name==name)
            wanted=next(item for item in expected.body if isinstance(item,ast.FunctionDef) and item.name==name)
            self.assertEqual(ast.dump(wanted),ast.dump(got))
        collect=next(item for item in actual.body if isinstance(item,ast.FunctionDef) and item.name=="collect")
        self.assertNotIn("tdbbackup",ast.unparse(collect));self.assertIn("live_identity_database_holders",ast.unparse(collect))
    def test_signed_pending_nfs_and_initial_source_closures_are_complete(self):
        source=CLI.read_text()
        helper=(LIB/"pending_nfs_authorization.py").read_text().replace("from config_generation import Generation as PendingNfsGeneration, read_json as pending_nfs_read\n","").rstrip()
        embedded=source.split("# BEGIN EMBEDDED PENDING NFS AUTHORIZATION\n",1)[1].split("\n# END EMBEDDED PENDING NFS AUTHORIZATION",1)[0]
        self.assertEqual(helper,embedded);ast.parse(embedded)
        readers=source.split("# BEGIN EMBEDDED PENDING NFS READERS\n",1)[1].split("\n# END EMBEDDED PENDING NFS READERS",1)[0]
        self.assertEqual(pending_nfs_readonly_body(),readers);ast.parse(readers)
        generation=(LIB/"config_generation.py").read_text()
        generation=generation[generation.index('"""Durable desired-state'):].rstrip()
        embedded=source.split("<<'PYGENERATION'\n",1)[1].split("\nimport sys\nrequest =",1)[0]
        self.assertEqual(generation,embedded);ast.parse(embedded)

    def test_signed_smb_pending_and_identity_closures_match_reviewed_bodies(self):
        source=CLI.read_text()
        helper=(LIB/"pending_nfs_authorization.py").read_text().replace("from config_generation import Generation as PendingNfsGeneration, read_json as pending_nfs_read\n","").rstrip()
        actual=source.split("# BEGIN EMBEDDED PENDING SMB AUTHORIZATION\n",1)[1].split("\n# END EMBEDDED PENDING SMB AUTHORIZATION",1)[0]
        self.assertEqual(helper,actual);ast.parse(actual)
        readers=source.split("# BEGIN EMBEDDED PENDING SMB READERS\n",1)[1].split("\n# END EMBEDDED PENDING SMB READERS",1)[0]
        self.assertEqual(pending_nfs_readonly_body(),readers);ast.parse(readers)
        _,expected=current_smb_reviewed_bodies()
        actual=source.split("<<'PYSMBIDENTITY'\n",1)[1].split("\nimport sys\ntry:",1)[0]
        self.assertEqual(expected,actual);ast.parse(actual)

    def test_signed_current_smb_retention_codec_body_and_readonly_dependency_subsets_are_exact(self):
        source=CLI.read_text();expected,_=current_smb_reviewed_bodies()
        actual=source.split("# BEGIN EMBEDDED SMB CURRENT RETENTION\n",1)[1].split("\n# END EMBEDDED SMB CURRENT RETENTION",1)[0]
        self.assertEqual(expected,actual);ast.parse(actual)
        codec=source.split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        self.assertEqual(1,len([node for node in ast.parse(codec).body if isinstance(node,ast.FunctionDef) and node.name=="encrypt"]))
        self.assertEqual(1,len([node for node in ast.parse(codec).body if isinstance(node,ast.FunctionDef) and node.name=="decrypt"]))
        for action in ("current-review","current-quiesce","current-export","current-retain","current-verify"):
            self.assertIn('"'+action+'"',source)
        self.assertIn(":CURRENT_LOCAL_AFTERSTOP",actual)

if __name__=='__main__':unittest.main()
