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
        end = runtime.index('\ncommand="', start)
        function = runtime[start:end]
        request = {"instanceUuid": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                   "operationUuid": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}
        result = subprocess.run(["bash", "-c", function + "\nidentity_capsule_command capabilities /dev/stdin"],
                                input=json.dumps(request), text=True, capture_output=True, check=True)
        response = json.loads(result.stdout)
        self.assertTrue(response["success"])
        self.assertFalse(response["adIdentity"])

    def test_shadow_requires_matching_scoped_nonlogin_account(self):
        with self.assertRaises(ValueError):
            capsules.validate_payload({"schemaVersion": 1, "files": {}, "accounts": {"/etc/shadow": ["synthetic:!:20000:0:99999:7:::"]}})

    def test_absent_file_marker_cannot_smuggle_credential_data(self):
        path = "/etc/ablestack-storage/secrets/iscsi-acl-secrets.json"
        with self.assertRaises(ValueError):
            capsules.validate_payload({"schemaVersion": 1, "files": {path: {"absent": True, "data": "ignored"}}, "accounts": {}})
        self.assertEqual({"absent": True}, capsules.validate_payload(
            {"schemaVersion": 1, "files": {path: {"absent": True}}, "accounts": {}})["files"][path])


if __name__ == "__main__":
    unittest.main()
