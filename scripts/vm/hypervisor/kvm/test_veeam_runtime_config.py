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

"""Host credentials are refreshed from Mold and never written to configuration."""

import base64
import json
import os
from pathlib import Path
import subprocess
import unittest

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from veeam_runtime_config import CONTEXT, fetch_runtime


ROOT = Path(__file__).resolve().parent


class FakeApi:
    def __init__(self):
        self.settings = dict(url="https://vbr.test:9398/api/", username="vbr-account", password="synthetic-password",
                             hostname="host-test", zoneid="zone-test")
        self.calls = []
        self.tamper = False

    def call(self, command, **params):
        self.calls.append((command, params))
        public = serialization.load_der_public_key(base64.b64decode(params["publickey"]))
        key, nonce = os.urandom(32), os.urandom(12)
        ciphertext = AESGCM(key).encrypt(nonce, json.dumps(self.settings).encode(), CONTEXT)
        if self.tamper:
            ciphertext = ciphertext[:-1] + bytes([ciphertext[-1] ^ 1])
        return dict(version=1, nonce=base64.b64encode(nonce).decode(), ciphertext=base64.b64encode(ciphertext).decode(),
                    wrappedkey=base64.b64encode(public.encrypt(key,
                        padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))).decode())


class VeeamRuntimeConfigTest(unittest.TestCase):
    def test_each_fetch_uses_a_new_key_and_current_mold_values(self):
        api = FakeApi()
        first = fetch_runtime(api, "host-test")
        api.settings["password"] = "synthetic-rotated-password"
        second = fetch_runtime(api, "host-test")
        self.assertEqual("synthetic-password", first["password"])
        self.assertEqual("synthetic-rotated-password", second["password"])
        self.assertEqual("vbr.test", second["sshhost"])
        self.assertEqual(["getVeeamRuntimeConfig"] * 2, [name for name, _ in api.calls])
        self.assertNotEqual(api.calls[0][1]["publickey"], api.calls[1][1]["publickey"])
        self.assertNotIn("password", api.calls[0][1])

    def test_tampered_or_wrong_host_responses_do_not_expose_credentials(self):
        for tamper in (False, True):
            with self.subTest(tamper=tamper):
                api = FakeApi()
                api.tamper = tamper
                if not tamper:
                    api.settings["hostname"] = "another-host"
                with self.assertRaises(ValueError) as failure:
                    fetch_runtime(api, "host-test")
                self.assertNotIn(api.settings["password"], str(failure.exception))

    def test_invalid_connection_values_are_rejected_without_including_them_in_errors(self):
        for field, value in (("url", "https://user:secret@vbr.test/"), ("username", ""), ("password", "")):
            with self.subTest(field=field):
                api = FakeApi()
                api.settings[field] = value
                with self.assertRaises(ValueError):
                    fetch_runtime(api, "host-test")

    def test_host_loader_updates_only_memory_and_keeps_password_as_data(self):
        settings = dict(url="https://vbr.test:9398/api/", username="vbr-account", password="' $(must-not-run); \"value\"",
                        hostname="host-test", zoneid="zone-test", sshhost="vbr.test")
        script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
MOLD_API_URL=http://mold.test MOLD_API_KEY=synthetic-key MOLD_API_SECRET=synthetic-secret
VEEAM_PASSWORD=stale VEEAM_SSH_HOST=stale
python3() {
  if [[ "$1" == *veeam_runtime_config.py ]]; then
    printf '%s' "$TEST_SETTINGS"
  else
    command python3 "$@"
  fi
}
mold_backup_load_veeam_connection
[[ "$VEEAM_PASSWORD" == "$TEST_PASSWORD" ]]
[[ "$VEEAM_SSH_PASSWORD" == "$TEST_PASSWORD" ]]
[[ "$VEEAM_API_PASSWORD" == "$TEST_PASSWORD" ]]
[[ "$VEEAM_SSH_HOST" == vbr.test && "$VEEAM_SSH_USER" == vbr-account && "$ZONE_ID" == zone-test ]]
'''
        result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                    env=dict(os.environ, TEST_LIBRARY=str(ROOT / "veeam/mold-backup.lib.sh"),
                             TEST_SETTINGS=json.dumps(settings), TEST_PASSWORD=settings["password"]))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout)
        self.assertNotIn(settings["password"], result.stderr)

    def test_failed_lookup_cannot_use_legacy_or_previous_job_password(self):
        script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
MOLD_API_URL=http://mold.test MOLD_API_KEY=synthetic-key MOLD_API_SECRET=synthetic-secret
VEEAM_PASSWORD=stale VEEAM_SSH_PASSWORD=stale VEEAM_SSH_HOST=stale
python3() { return 1; }
if mold_backup_load_veeam_connection; then exit 2; fi
[[ -z "${VEEAM_PASSWORD:-}" && -z "${VEEAM_SSH_PASSWORD:-}" && -z "${VEEAM_SSH_HOST:-}" ]]
'''
        result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                                env=dict(os.environ, TEST_LIBRARY=str(ROOT / "veeam/mold-backup.lib.sh")))
        self.assertEqual(0, result.returncode, result.stderr)

    def test_ssh_and_scp_askpass_files_do_not_contain_the_password(self):
        script = r'''
set -euo pipefail
source "$TEST_LIBRARY"
VEEAM_SSH_HOST=vbr.test VEEAM_SSH_USER=vbr-account VEEAM_SSH_PASSWORD=synthetic-password VEEAM_SSH_KEY=""
command() {
  if [[ "$1" == -v && "$2" == sshpass ]]; then return 1; fi
  builtin command "$@"
}
setsid() {
  [[ -f "$SSH_ASKPASS" ]]
  if grep -qF synthetic-password "$SSH_ASKPASS"; then return 2; fi
  [[ "$(sh "$SSH_ASKPASS")" == synthetic-password ]]
}
mold_backup_veeam_ssh_cmd 'read-only-test'
mold_backup_veeam_scp "$TEST_LIBRARY" /unused-target
'''
        result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10,
                                env=dict(os.environ, TEST_LIBRARY=str(ROOT / "veeam/mold-backup.lib.sh")))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout)


if __name__ == "__main__":
    unittest.main()
