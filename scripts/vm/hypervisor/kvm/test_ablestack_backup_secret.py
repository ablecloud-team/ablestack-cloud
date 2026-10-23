#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Credential compatibility, authenticated decryption, and concurrent provisioning regressions."""

import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import hmac
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import ablestack_backup_secret as crypto
import ablestack_backup_secret_key as keys


class BackupCredentialTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.key = self.root / "ablestack.key"
        self.secret = self.root / "secret.enc"
        self.environment = patch.dict(os.environ, {}, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.roots = patch.object(keys, "_secret_roots", return_value=set())
        self.roots.start()
        self.addCleanup(self.roots.stop)
        self.plaintext = b"synthetic-api-secret-for-regression-only"

    def write_private(self, path, data):
        path.write_bytes(data)
        path.chmod(0o600)

    def test_both_cli_entrypoints_read_the_common_ciphertext(self):
        keys.ensure_key(self.key)
        scripts = Path(__file__).resolve().parent
        environment = dict(os.environ, SECRET_KEY_FILE=str(self.key))
        subprocess.run([sys.executable, str(scripts / "netbackup-host-secret-helper.py"), "encrypt", str(self.secret)],
                       input=self.plaintext, check=True, capture_output=True, env=environment)
        result = subprocess.run(["bash", str(scripts / "veeam/mold-backup-secret.sh"), "decrypt",
                                 "--enc-file", str(self.secret), "--key-file", str(self.key)], check=True, capture_output=True)
        self.assertEqual(self.plaintext, result.stdout)
        subprocess.run(["bash", str(scripts / "veeam/mold-backup-secret.sh"), "encrypt", "--secret-stdin",
                        "--out", str(self.secret), "--key-file", str(self.key)], input=self.plaintext, check=True, capture_output=True)
        result = subprocess.run([sys.executable, str(scripts / "netbackup-host-secret-helper.py"), "decrypt", str(self.secret)],
                                check=True, capture_output=True, env=environment)
        self.assertEqual(self.plaintext, result.stdout)
        payload = json.loads(self.secret.read_bytes())
        self.assertEqual("AES-256-GCM", payload["cipher"])
        self.assertEqual(2, payload["version"])
        self.assertEqual(0o600, stat.S_IMODE(self.secret.stat().st_mode))

    def test_tampered_ciphertext_and_metadata_never_fall_back(self):
        keys.ensure_key(self.key)
        original = json.loads(crypto.encrypt_secret(self.plaintext, self.key))
        mutations = {
            "cipher": "AES-256-CBC", "purpose": "other-secret", "version": 1,
            "nonce": base64.b64encode(b"\0" * 12).decode(), "keyId": "0" * 64,
            "ciphertext": base64.b64encode(b"\0" * 64).decode(),
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                payload = dict(original, **{field: value})
                self.write_private(self.secret, json.dumps(payload).encode())
                with patch.object(crypto, "_decrypt_legacy_netbackup") as legacy:
                    with self.assertRaises(keys.SecretKeyError):
                        crypto.decrypt_secret(self.secret, self.key, "netbackup")
                    legacy.assert_not_called()

    def test_wrong_key_and_duplicate_json_fields_are_rejected(self):
        keys.ensure_key(self.key)
        wrong = self.root / "wrong.key"
        keys.ensure_key(wrong)
        crypto.write_secret(self.secret, self.plaintext, self.key)
        with self.assertRaises(keys.SecretKeyError):
            crypto.decrypt_secret(self.secret, wrong, "veeam")
        payload = self.secret.read_text()
        self.write_private(self.secret, payload.replace('"version": 2', '"version": 2, "version": 1').encode())
        with self.assertRaises(keys.SecretKeyError):
            crypto.decrypt_secret(self.secret, self.key, "netbackup")

    def test_concurrent_key_creation_is_idempotent_and_host_keys_differ(self):
        with ThreadPoolExecutor(max_workers=8) as workers:
            results = list(workers.map(lambda _: keys.ensure_key(self.key), range(16)))
        self.assertEqual(1, sum(results))
        original = self.key.read_bytes()
        self.assertFalse(keys.ensure_key(self.key))
        self.assertEqual(original, self.key.read_bytes())
        other = self.root / "other.key"
        keys.ensure_key(other)
        self.assertNotEqual(keys.read_aes_key(self.key), keys.read_aes_key(other))
        self.assertEqual(32, len(keys.read_aes_key(self.key)))

    def test_existing_secret_in_either_integration_prevents_key_creation_or_upgrade(self):
        external = self.root / "veeam" / "secrets"
        external.mkdir(parents=True)
        self.write_private(external / "secret.enc.bak.old", b"legacy encrypted credential")
        with patch.object(keys, "_secret_roots", return_value={external.parent}):
            with self.assertRaises(keys.SecretKeyError):
                keys.ensure_key(self.key)
            self.assertFalse(self.key.exists())
            self.write_private(self.key, b"legacy-key")
            self.assertFalse(keys.ensure_key(self.key))
            with self.assertRaises(keys.SecretKeyError):
                keys.ensure_key(self.key, upgrade_unused_legacy=True)
            self.assertEqual(b"legacy-key", self.key.read_bytes())

    def test_unused_legacy_key_upgrades_only_during_explicit_configuration(self):
        self.write_private(self.key, b"legacy-key")
        with self.assertRaises(keys.SecretKeyError):
            crypto.encrypt_secret(self.plaintext, self.key)
        self.assertEqual(b"legacy-key", self.key.read_bytes())
        self.assertTrue(keys.ensure_key(self.key, upgrade_unused_legacy=True))
        crypto.write_secret(self.secret, self.plaintext, self.key)
        self.assertEqual(self.plaintext, crypto.decrypt_secret(self.secret, self.key, "veeam"))

    def test_key_symlinks_permissions_and_malformed_versioned_keys_are_rejected(self):
        keys.ensure_key(self.key)
        alias = self.root / "alias.key"
        alias.symlink_to(self.key)
        with self.assertRaises(keys.SecretKeyError):
            keys.read_aes_key(alias)
        self.key.chmod(0o644)
        with self.assertRaises(keys.SecretKeyError):
            keys.read_aes_key(self.key)
        for content in (b"", b'{"version": 99}', b'{"format":'):
            self.write_private(self.key, content)
            with self.assertRaises(keys.SecretKeyError):
                keys.ensure_key(self.key, upgrade_unused_legacy=True)
            self.assertEqual(content, self.key.read_bytes())

    def test_failed_encryption_preserves_the_previous_ciphertext(self):
        keys.ensure_key(self.key)
        crypto.write_secret(self.secret, self.plaintext, self.key)
        original = self.secret.read_bytes()
        with patch.object(crypto, "_aes_gcm", side_effect=keys.SecretKeyError("backend unavailable")):
            with self.assertRaises(keys.SecretKeyError):
                crypto.write_secret(self.secret, b"replacement", self.key)
        self.assertEqual(original, self.secret.read_bytes())
        self.assertEqual([], list(self.root.glob(".secret.enc-*")))

    def test_legacy_netbackup_ciphertext_uses_original_text_key(self):
        # The old writer used the encoded text itself, while Veeam decoded it.
        legacy_key = base64.b64encode(b"synthetic-legacy-key")
        self.write_private(self.key, legacy_key + b"\n")
        salt, nonce = b"test-salt16bytes", b"test-nonce16byte"
        derived = hashlib.pbkdf2_hmac("sha256", legacy_key, salt, 200000, dklen=64)
        stream = b"".join(hmac.new(derived[:32], nonce + index.to_bytes(8, "big"), hashlib.sha256).digest()
                          for index in range((len(self.plaintext) + 31) // 32))
        ciphertext = bytes(left ^ right for left, right in zip(self.plaintext, stream))
        payload = {"version": 1, "iterations": 200000,
                   "salt": base64.b64encode(salt).decode(), "nonce": base64.b64encode(nonce).decode(),
                   "ciphertext": base64.b64encode(ciphertext).decode(),
                   "mac": base64.b64encode(hmac.new(derived[32:], nonce + ciphertext, hashlib.sha256).digest()).decode()}
        self.write_private(self.secret, json.dumps(payload).encode())
        self.assertEqual(self.plaintext, crypto.decrypt_secret(self.secret, self.key, "netbackup"))

    def test_legacy_veeam_ciphertext_uses_original_decoded_passphrase(self):
        legacy_key = b"synthetic-legacy-key"
        self.write_private(self.key, base64.b64encode(legacy_key) + b"\n")
        pass_file = self.root / "legacy-passphrase"
        self.write_private(pass_file, legacy_key)
        result = subprocess.run(["openssl", "enc", "-aes-256-cbc", "-salt", "-pbkdf2", "-base64", "-pass", "file:" + str(pass_file)],
                                input=self.plaintext, check=True, capture_output=True)
        self.write_private(self.secret, result.stdout)
        self.assertEqual(self.plaintext, crypto.decrypt_secret(self.secret, self.key, "veeam"))


if __name__ == "__main__":
    unittest.main()
