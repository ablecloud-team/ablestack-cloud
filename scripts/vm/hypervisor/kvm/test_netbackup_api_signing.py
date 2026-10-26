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

"""Validate real shell API requests against ApiServer.verifyRequest's canonical encoding."""

import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from urllib.parse import parse_qsl, quote_plus, urlsplit


DIRECTORY = Path(__file__).resolve().parent
REASON = "The original VM must be Stopped before restoring metadata in the external UI"
PROOF = json.dumps(dict(hostName="ablecube4", startedAt=1792977750000, endedAt=1792977780000))


class NetBackupApiSigningTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.notify = self.base / "notify-functions.sh"
        # Load the production functions without executing their CLI entry point.
        source = (DIRECTORY / "netbackup-host-restore-notify").read_text()
        self.assertTrue(source.rstrip().endswith('main "$@"'))
        self.notify.write_text(source.rsplit('\nmain "$@"', 1)[0])
        self.env = dict(os.environ, ADMIN_APIKEY="synthetic-key", ADMIN_SECRETKEY="synthetic-secret",
                        MOLD_API_URL="https://mold.test/client/api", MOLD_API_KEY="synthetic-key",
                        MOLD_API_SECRET="synthetic-secret", MOLD_RESTORE_API_URL="https://mold.test/client/api",
                        TEST_NOTIFY=str(self.notify), TEST_COMMON=str(DIRECTORY / "netbackup-host-common.sh"),
                        TEST_VEEAM=str(DIRECTORY / "veeam/mold-backup.lib.sh"), TEST_REASON=REASON, TEST_PROOF=PROOF,
                        LOG_FILE=str(self.base / "notify.log"), MOLD_EXTERNAL_RESTORE_PROOF=PROOF, MOLD_RESTORE_API_METHOD="POST")

    def shell(self, script):
        return subprocess.run(["bash", "-c", script], env=self.env, text=True, capture_output=True, timeout=20)

    def verify_signature(self, url, reason=REASON, proof=PROOF):
        params = dict(parse_qsl(urlsplit(url.strip()).query, keep_blank_values=True))
        if reason is not None:
            self.assertEqual(reason, params["reason"])
        if proof is not None:
            self.assertEqual(proof, params["proof"])
        signature = params.pop("signature")
        # Java URLEncoder leaves '*' literal, encodes '~', and the server replaces '+' with '%20'.
        def server_encode(value):
            return quote_plus(value, safe="*").replace("~", "%7E").replace("+", "%20")
        canonical = "&".join(key + "=" + server_encode(params[key]) for key in sorted(params)).lower()
        expected = base64.b64encode(hmac.new(b"synthetic-secret", canonical.encode(), hashlib.sha256).digest()).decode()
        self.assertEqual(expected, signature, "Request signature differs from ApiServer.verifyRequest")

    def signed_requests(self, reason=REASON):
        self.env["TEST_REASON"] = reason
        for signer in ("notify", "common"):
            with self.subTest(signer=signer):
                script = r'''
source "$TEST_NOTIFY"
params="$(build_api_params rejectBackupArtifactRestore "provider=ablestack-netbackup&reason=$(urlencode_plus "$TEST_REASON")&proof=$(urlencode_plus "$TEST_PROOF")")"
build_signed_url "$params"
''' if signer == "notify" else r'''
source "$TEST_COMMON"
params="$(build_mold_api_params rejectBackupArtifactRestore provider ablestack-netbackup reason "$TEST_REASON" proof "$TEST_PROOF")"
build_mold_signed_url https://mold.test/client/api "$params"
'''
                result = self.shell(script)
                self.assertEqual(0, result.returncode, result.stderr)
                self.verify_signature(result.stdout, reason)

    def test_rejected_restore_reason_and_completion_json_match_server_signature(self):
        self.signed_requests()

    def test_literal_plus_percent_and_ampersand_are_preserved(self):
        self.signed_requests("Restore A+B & C%20D")

    def test_unicode_reason_round_trips_before_server_verification(self):
        self.signed_requests("가상머신 정지 필요 / VM must be Stopped")

    def test_java_urlencoder_special_characters_match(self):
        self.signed_requests("Restore ~snapshot* / path")

    def test_veeam_fallback_already_signs_the_same_rejection_payload(self):
        self.env["TEST_URL"] = str(self.base / "veeam-url")
        result = self.shell(r'''
source "$TEST_VEEAM"
mold_backup_log() { :; }
curl() {
  local output="" url="${@: -1}"
  printf '%s' "$url" > "$TEST_URL"
  while [[ $# -gt 0 ]]; do
    if [[ "$1" == -o ]]; then output="$2"; shift; fi
    shift
  done
  printf '%s' '{"rejectbackupartifactrestoreresponse":{"success":true}}' > "$output"
  printf 200
}
mold_backup_cloudstack_api_call rejectBackupArtifactRestore provider=ablestack-veeam "reason=$TEST_REASON" "proof=$TEST_PROOF"
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.verify_signature((self.base / "veeam-url").read_text())

    def test_rejection_api_failure_is_reported_to_the_watcher(self):
        result = self.shell(r'''
source "$TEST_NOTIFY"
curl() {
  printf '%s\n401' '{"rejectbackupartifactrestoreresponse":{"errorcode":401}}'
}
invoke_mold_api POST rejectBackupArtifactRestore externalid ablecube4_1792958584 354 "provider=ablestack-netbackup&reason=$(urlencode_plus "$TEST_REASON")&proof=$(urlencode_plus "$TEST_PROOF")"
''')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("command=rejectBackupArtifactRestore", result.stderr)
        self.assertNotIn("SKIPPED", result.stdout)

    def test_accepted_restore_forwards_completion_proof_with_a_valid_signature(self):
        self.env["TEST_URL"] = str(self.base / "accepted-url")
        for has_proof in (True, False):
            with self.subTest(has_proof=has_proof):
                self.env["MOLD_EXTERNAL_RESTORE_PROOF"] = PROOF if has_proof else ""
                result = self.shell(r'''
source "$TEST_NOTIFY"
curl() {
  printf '%s' "${@: -1}" > "$TEST_URL"
  printf '%s\n200' '{"restorebackupartifactresponse":{"jobid":"async-test"}}'
}
submit_volume_restore ablecube4_1792958584 359
''')
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("async-test", result.stdout)
                url = (self.base / "accepted-url").read_text()
                self.verify_signature(url, reason=None, proof=PROOF if has_proof else None)
                params = dict(parse_qsl(urlsplit(url).query))
                self.assertEqual("restoreBackupArtifact", params["command"])
                self.assertEqual("359", params["jobid"])
                self.assertEqual("ablecube4_1792958584", params["externalid"])
                self.assertEqual(has_proof, "proof" in params)


if __name__ == "__main__":
    unittest.main()
