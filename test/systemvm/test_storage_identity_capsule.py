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


"""Exercise encrypted local-identity transport without reading real credential files."""
import importlib.util
from pathlib import Path
import unittest
import subprocess
import json
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

SOURCE = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/lib/ablestack-storage/identity_capsule.py"
spec = importlib.util.spec_from_file_location("identity_capsule_test", SOURCE)
capsules = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capsules)


class IdentityCapsuleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.private = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption()).decode()
        cls.public = key.public_key().public_bytes(serialization.Encoding.PEM,
                                                   serialization.PublicFormat.SubjectPublicKeyInfo).decode()

    def test_encrypted_round_trip_keeps_plain_credential_material_outside_envelope(self):
        payload = {"schemaVersion": 1, "files": {}, "accounts": {"/etc/passwd": ["synthetic:x:1002:1002::/nonexistent:/usr/sbin/nologin"]}}
        result = capsules.encrypt(payload, self.public, "instance:operation")
        self.assertNotIn("synthetic", str(result))
        self.assertEqual(payload, capsules.decrypt(result, self.private, "instance:operation"))

    def test_wrong_scope_or_changed_ciphertext_cannot_be_restored(self):
        result = capsules.encrypt({"schemaVersion": 1, "files": {}, "accounts": {}}, self.public, "correct")
        with self.assertRaises(ValueError):
            capsules.decrypt(result, self.private, "other")
        result["sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            capsules.decrypt(result, self.private, "correct")

    def test_public_backup_and_user_data_paths_are_never_capsule_inputs(self):
        for path in ["/srv/ablestack-storage/volumes/data/file", "/etc/krb5.keytab", "/tmp/imported.sh"]:
            with self.assertRaises(ValueError):
                capsules.validate_payload({"schemaVersion": 1, "files": {path: {}}, "accounts": {}})

    def test_scoped_block_restore_never_touches_samba_database_or_operating_system_accounts(self):
        iscsi='/etc/ablestack-storage/secrets/iscsi-acl-secrets.json'
        payload={'schemaVersion':1,'files':{iscsi:{'absent':True},'/var/lib/samba/private/passdb.tdb':{'absent':True}},
                 'accounts':{'/etc/passwd':['synthetic:x:1002:1002::/nonexistent:/usr/sbin/nologin']}}
        selected=capsules.select_restore_domains(payload,['ISCSI'])
        self.assertEqual({iscsi:{'absent':True}},selected['files']);self.assertEqual({},selected['accounts'])
        from unittest.mock import Mock,patch
        observer=Mock(side_effect=AssertionError('unaffected Samba inspection'))
        capsules.require_identity_database_quiescence(selected['files'],observer)
        observer.assert_not_called()
        for scope in ([],['UNKNOWN'],['SMB','SMB']):
            with self.assertRaises(ValueError):capsules.select_restore_domains(payload,scope)
        self.assertEqual(payload['accounts'],capsules.select_restore_domains(payload,['SMB'])['accounts'])

    def test_weak_or_wrong_wrapping_key_is_rejected(self):
        key = rsa.generate_private_key(public_exponent=65537, key_size=1024)
        public = key.public_key().public_bytes(serialization.Encoding.PEM,
                                               serialization.PublicFormat.SubjectPublicKeyInfo).decode()
        with self.assertRaises(ValueError):
            capsules.encrypt({}, public, "scope")

    def test_account_record_cannot_inject_new_lines(self):
        with self.assertRaises(ValueError):
            capsules.validate_payload({"schemaVersion": 1, "files": {}, "accounts": {"/etc/passwd": ["user:x:1:1\nroot:x:0:0"]}})

    def test_nonlogin_accounts_preserve_other_os_users_and_same_numeric_identity(self):
        current = "root:x:0:0::/root:/bin/bash\ncloud:x:1001:1001::/home/cloud:/bin/bash\n"
        record = "synthetic:x:1002:1002::/home/synthetic:/usr/sbin/nologin"
        merged = capsules.account_merge(current, [record], "passwd")
        self.assertTrue(merged.startswith(current))
        self.assertIn(record, merged)
        self.assertEqual(merged, capsules.account_merge(merged, [record], "passwd"))

    def test_foreign_uid_or_changed_home_is_rejected_before_any_account_write(self):
        current = "foreign:x:1002:1002::/home/foreign:/usr/sbin/nologin\n"
        record = "synthetic:x:1002:1002::/nonexistent:/usr/sbin/nologin"
        with self.assertRaises(ValueError):
            capsules.account_merge(current, [record], "passwd")
        current = "synthetic:x:1002:1002::/root:/bin/bash\n"
        with self.assertRaises(ValueError):
            capsules.account_merge(current, [record], "passwd")

    def test_protected_names_uid_and_foreign_gid_cannot_be_imported(self):
        for record in ["root:x:0:0::/root:/bin/bash", "nobody:x:65534:65534::/nonexistent:/usr/sbin/nologin"]:
            with self.assertRaises(ValueError):
                capsules.account_merge("root:x:0:0::/root:/bin/bash\n", [record], "passwd")
        with self.assertRaises(ValueError):
            capsules.account_merge("foreign:x:1001001:\n", ["sf_g_test:x:1001001:"], "group")

    def test_runtime_entrypoint_embeds_the_same_reviewed_crypto_implementation(self):
        runtime = SOURCE.parents[2] / "bin/ablestack-storagectl"
        text = runtime.read_text()
        self.assertIn(SOURCE.read_text(), text)
        self.assertIn('3<&0', text)
        self.assertIn('os.fdopen(3).read()', text)

    def test_capsule_capabilities_reads_pipe_without_writing_payload_file(self):
        runtime = (SOURCE.parents[2] / "bin/ablestack-storagectl").read_text()
        start = runtime.index("identity_capsule_command() {")
        end = runtime.index('\nPYIDENTITY\n}', start) + len('\nPYIDENTITY\n}')
        function = runtime[start:end]
        request = {"instanceUuid": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                   "operationUuid": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}
        result = subprocess.run(["bash", "-c", function + "\nidentity_capsule_command capabilities /dev/stdin"],
                                input=json.dumps(request), text=True, capture_output=True, check=True)
        response = json.loads(result.stdout)
        self.assertTrue(response["success"])
        self.assertFalse(response["adIdentity"])

    def test_live_tdb_replacement_is_rejected_before_any_restore_write(self):
        from unittest.mock import patch
        payload={"schemaVersion":1,"files":{"/var/lib/samba/private/passdb.tdb":{"absent":True}},"accounts":{}}
        with patch.object(capsules,'require_identity_database_quiescence',side_effect=ValueError('SMB_IDENTITY_QUIESCE_REQUIRED')) as guard, patch.object(capsules,'regular_file') as read, patch.object(capsules.os,'replace') as replace:
            with self.assertRaisesRegex(ValueError,'SMB_IDENTITY_QUIESCE_REQUIRED'):capsules.restore(payload)
            guard.assert_called_once_with(payload['files']);read.assert_not_called();replace.assert_not_called()

    def test_deleted_database_handle_blocks_restore_as_well_as_current_inode_handle(self):
        for deleted in (False,True):
            with self.assertRaisesRegex(ValueError,'QUIESCE_REQUIRED'):
                capsules.require_identity_database_quiescence({'/var/lib/samba/private/passdb.tdb':{}},lambda paths:[{'pid':123,'deleted':deleted}])
        capsules.require_identity_database_quiescence({'/var/lib/samba/private/passdb.tdb':{}},lambda paths:[])

    def test_descriptor_observation_reads_metadata_only_and_handles_process_disappearance(self):
        import tempfile,os
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);process=root/'123';(process/'fd').mkdir(parents=True);(process/'comm').write_text('smbd')
            database=root/'passdb.tdb';database.write_bytes(b'SYNTHETIC_METADATA_FIXTURE')
            descriptor=process/'fd/27';descriptor.symlink_to(database)
            from unittest.mock import patch
            real_link=os.readlink
            with patch.object(capsules.os,'readlink',side_effect=lambda path:'/var/lib/samba/private/passdb.tdb (deleted)' if str(path).endswith('/27') else real_link(path)):
                holders=capsules.live_identity_database_holders(capsules.LIVE_TDB_FILES,root)
            self.assertEqual(1,len(holders));self.assertTrue(holders[0]['deleted']);self.assertEqual(database.stat().st_ino,holders[0]['inode'])
            self.assertNotIn('SYNTHETIC',str(holders))
            (process/'comm').unlink();self.assertEqual([],capsules.live_identity_database_holders(capsules.LIVE_TDB_FILES,root))

    def test_shadow_requires_matching_scoped_nonlogin_account(self):
        with self.assertRaises(ValueError):
            capsules.validate_payload({"schemaVersion": 1, "files": {}, "accounts": {"/etc/shadow": ["synthetic:!:20000:0:99999:7:::"]}})

    def test_absent_file_marker_cannot_smuggle_credential_data(self):
        path = "/etc/ablestack-storage/secrets/iscsi-acl-secrets.json"
        with self.assertRaises(ValueError):
            capsules.validate_payload({"schemaVersion": 1, "files": {path: {"absent": True, "data": "ignored"}}, "accounts": {}})
        self.assertEqual({"absent": True}, capsules.validate_payload(
            {"schemaVersion": 1, "files": {path: {"absent": True}}, "accounts": {}})["files"][path])

    def test_rollback_removes_only_exact_post_snapshot_owned_accounts(self):
        created = "synthetic:x:1002:1002::/nonexistent:/usr/sbin/nologin"
        foreign = "foreign:x:1003:1003::/nonexistent:/usr/sbin/nologin"
        current = "root:x:0:0::/root:/bin/bash\n" + created + "\n" + foreign + "\n"
        merged = capsules.rollback_account_merge(current, [], "passwd", {"synthetic": created})
        self.assertNotIn("synthetic", merged)
        self.assertIn(foreign, merged)
        self.assertIn("root:x:0:0", merged)
        changed = current.replace("1002:1002", "1004:1004")
        with self.assertRaises(ValueError):
            capsules.rollback_account_merge(changed, [], "passwd", {"synthetic": created})

    def test_provenance_rejects_hash_files_and_keeps_snapshot_accounts(self):
        record = "synthetic:x:1002:1002::/nonexistent:/usr/sbin/nologin"
        self.assertIn(record, capsules.rollback_account_merge(record, [record], "passwd", {"synthetic": record}))
        import base64
        data = {"schemaVersion": 1, "accounts": {"/etc/shadow": ["synthetic:hash:20000:0:99999:7:::"]}}
        # Provenance is intentionally limited to non-secret public account records.
        with self.assertRaises(ValueError):
            capsules.owned_account_records({"/etc/ablestack-storage/smb-local-account-provenance.json":
                                           {"data": base64.b64encode(json.dumps(data).encode()).decode()}})

    def test_native_file_commit_failure_restores_every_prior_file(self):
        import base64, tempfile, os, stat
        from unittest.mock import patch
        from types import SimpleNamespace
        payload = {"schemaVersion": 1, "files": {}, "accounts": {}}
        for path in sorted(capsules.FILES):
            payload["files"][path] = {"data": base64.b64encode(b"new").decode(), "mode": 0o600, "uid": 0, "gid": 0}
        with tempfile.TemporaryDirectory() as folder:
            mapped = {path: Path(folder) / str(i) for i, path in enumerate(sorted(capsules.FILES | capsules.ACCOUNT_FILES))}
            before = {}
            for path, local in mapped.items():
                value = b"old"
                if path.endswith("smb-local-account-provenance.json"):
                    value = b'{"schemaVersion":1,"accounts":{}}'
                elif path.endswith("smb-managed-identities.json"):
                    value = b'{}'
                elif path in capsules.ACCOUNT_FILES:
                    value = b""
                local.write_bytes(value);os.chmod(local, 0o600);before[path] = value
            def regular(path, maximum=capsules.MAX_CAPSULE_BYTES):
                local = mapped[path];actual = local.stat()
                return local.read_bytes(), SimpleNamespace(st_mode=actual.st_mode, st_gid=0)
            real_replace = os.replace
            commits = []
            def replace(source, target):
                if target in mapped:
                    commits.append(target)
                    if len(commits) == 2:
                        raise OSError("injected second file commit failure")
                    target = mapped[target]
                return real_replace(source, target)
            real_exists = os.path.exists
            def exists(path):
                return real_exists(mapped[path]) if path in mapped else real_exists(path)
            def lexists(path):
                return path in mapped and real_exists(mapped[path])
            with patch.object(capsules, "regular_file", regular), patch.object(capsules.os.path, "exists", exists), \
                 patch.object(capsules.os.path, "lexists", lexists), patch.object(capsules.os.path, "realpath", lambda value: value), \
                 patch.object(capsules.os, "makedirs"), patch.object(capsules.os, "fchown"), \
                 patch.object(capsules.os, "replace", replace), \
                 patch.object(capsules.tempfile if hasattr(capsules, "tempfile") else tempfile, "mkstemp", wraps=tempfile.mkstemp) as temp:
                # Stage into a disposable directory while preserving the production path allowlist.
                original = temp._mock_wraps
                temp.side_effect = lambda **kwargs: original(prefix=kwargs.get("prefix", "test"), dir=folder)
                with self.assertRaises(OSError):
                    capsules.restore(payload)
            self.assertTrue(all(local.read_bytes() == before[path] for path, local in mapped.items()))

    def test_payload_rejects_unknown_metadata_login_accounts_and_unbounded_modes(self):
        with self.assertRaises(ValueError):
            capsules.validate_payload({"schemaVersion": 1, "files": {}, "accounts": {}, "script": "ignored"})
        with self.assertRaises(ValueError):
            capsules.validate_payload({"schemaVersion": 1, "files": {}, "accounts": {"/etc/passwd": ["synthetic:x:1002:1002::/home/synthetic:/bin/bash"]}})
        import base64
        path = "/var/lib/samba/private/passdb.tdb"
        for mode in (0o4600, -1):
            with self.assertRaises(ValueError):
                capsules.validate_payload({"schemaVersion": 1, "files": {path: {"data": base64.b64encode(b"synthetic").decode(), "mode": mode, "uid": 0, "gid": 0}}, "accounts": {}})

    def test_forced_account_cleanup_requires_controller_creation_provenance(self):
        import base64
        uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        suffix = uuid.replace("-", "")[:20]
        identity = {"managedUser": "sf_u_"+suffix, "managedGroup": "sf_g_"+suffix, "ownerUid": 1001001, "ownerGid": 1001001}
        path = "/etc/ablestack-storage/smb-managed-identities.json"
        envelope = lambda: {path: {"data": base64.b64encode(json.dumps({uuid:identity}).encode()).decode()}}
        self.assertEqual({}, capsules.owned_account_records(envelope())["/etc/passwd"])
        identity["createdByControllerUser"] = True
        identity["createdByControllerGroup"] = True
        records = capsules.owned_account_records(envelope())
        self.assertIn("sf_u_"+suffix, records["/etc/passwd"])
        self.assertIn("sf_g_"+suffix, records["/etc/group"])
        identity["managedUser"] = "foreign"
        with self.assertRaises(ValueError):
            capsules.owned_account_records(envelope())

    def test_nvme_auth_capture_only_accepts_literal_bounded_host_nqns(self):
        for value in ("/etc/shadow", "nqn.test/../../secret", "nqn.test\nkey", "iqn.test", "nqn."+"a"*224):
            with self.assertRaises(ValueError):
                capsules.validate_host_nqn(value)
        self.assertEqual("nqn.2014-08.org.nvmexpress:uuid:example", capsules.validate_host_nqn("nqn.2014-08.org.nvmexpress:uuid:example"))
        with self.assertRaises(ValueError):
            capsules.collect_nvme_hosts(["nqn.test:host"]*2)
        with self.assertRaises(ValueError):
            capsules.collect_nvme_hosts(["nqn.test:host"]*513)

    def test_capsule_nvme_auth_fields_are_allowlisted_and_bounded(self):
        payload = {"schemaVersion": 1, "files": {}, "accounts": {}, "nvmeHosts": {"nqn.test:host": {"dhchap_key": "DHHC-1:synthetic", "dhchap_ctrl_key": None}}}
        capsules.validate_payload(payload)
        for fields in ({"other": "key"}, {"dhchap_key": "wrong", "dhchap_ctrl_key": None}, {"dhchap_key": "DHHC-1:"+"x"*4096, "dhchap_ctrl_key": None}):
            payload["nvmeHosts"]["nqn.test:host"] = fields
            with self.assertRaises(ValueError):
                capsules.validate_payload(payload)

    def test_protected_nvme_replay_does_not_add_acl_hosts_or_mutate_original_payload(self):
        desired = {"subsystems": [{"hosts": [{"principal": "nqn.test:allowed", "config": {"dhChapEnabled": True}}]}]}
        credentials = {"nqn.test:allowed": {"dhchap_key": "DHHC-1:synthetic"}, "nqn.test:unlisted": {"dhchap_key": "DHHC-1:extra"}}
        merged = capsules.merge_nvme_identity_payload(desired, credentials)
        self.assertNotIn("secrets", desired["subsystems"][0]["hosts"][0])
        self.assertEqual(1, len(merged["subsystems"][0]["hosts"]))
        self.assertEqual("DHHC-1:synthetic", merged["subsystems"][0]["hosts"][0]["secrets"]["dhChapKey"])
        with self.assertRaises(ValueError):
            capsules.merge_nvme_identity_payload(desired, {})

    def test_nvme_replay_uses_stdin_and_suppresses_child_secret_diagnostics(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        desired = {"subsystems": [{"hosts": [{"principal": "nqn.test:allowed", "config": {"dhChapEnabled": True}}]}]}
        credentials = {"nqn.test:allowed": {"dhchap_key": "DHHC-1:synthetic"}}
        with patch.object(capsules.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout='{"success":true}')) as run:
            self.assertTrue(capsules.replay_nvme_identity_payload(desired, credentials)["nvmeRestored"])
            self.assertEqual("/dev/stdin", run.call_args.args[0][-1])
            self.assertNotIn("synthetic", str(run.call_args.args))
            self.assertEqual(subprocess.DEVNULL, run.call_args.kwargs["stderr"])
            self.assertIn("DHHC-1:synthetic", run.call_args.kwargs["input"])

    def test_missing_protected_nvme_binding_is_blocked_before_identity_files(self):
        from unittest.mock import patch
        payload = {"schemaVersion": 1, "files": {}, "accounts": {}, "nvmeHosts": {"nqn.test:allowed": {"dhchap_key": "DHHC-1:synthetic", "dhchap_ctrl_key": None}}}
        with patch.object(capsules, "restore") as restore:
            with self.assertRaises(ValueError):
                capsules.restore_protected(payload)
            restore.assert_not_called()
    def test_protected_replay_restores_accounts_then_only_the_reviewed_protocol_payload(self):
        from unittest.mock import patch
        payload = {"schemaVersion": 1, "files": {}, "accounts": {}, "nvmeHosts": {"nqn.test:allowed": {"dhchap_key": "DHHC-1:synthetic", "dhchap_ctrl_key": None}}}
        desired = {"subsystems": [{"hosts": [{"principal": "nqn.test:allowed", "config": {"dhChapEnabled": True}}]}]}
        sequence = []
        with patch.object(capsules, "restore", side_effect=lambda value: sequence.append("identity") or {"success": True}), \
             patch.object(capsules, "replay_nvme_identity_payload", side_effect=lambda value, hosts: sequence.append("protocol") or {"success": True}):
            self.assertTrue(capsules.restore_protected(payload, desired)["nvmeRestored"])
        self.assertEqual(["identity", "protocol"], sequence)


if __name__ == "__main__":
    unittest.main()
