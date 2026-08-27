#!/usr/bin/env python3

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

import importlib.util
import os
import unittest


MODULE_PATH = os.path.abspath(os.path.join(
    os.path.dirname(__file__),
    "..", "debian", "usr", "local", "lib", "ablestack-storage", "session_auth.py"))
SPEC = importlib.util.spec_from_file_location("session_auth", MODULE_PATH)
SESSION_AUTH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SESSION_AUTH)


class TestStorageSessionAuth(unittest.TestCase):
    def test_targetcli_not_authenticated_is_only_an_observation(self):
        observation = SESSION_AUTH.parse_targetcli_auth_observation(
            "name: iqn.test (NOT AUTHENTICATED)")
        verification = SESSION_AUTH.classify_iscsi_auth_session(
            "LOGGED_IN", True, True)

        self.assertEqual("NOT_AUTHENTICATED", observation)
        self.assertEqual("VERIFIED", verification)
        self.assertTrue(SESSION_AUTH.compatibility_authenticated(verification))

    def test_no_auth_session_is_not_required(self):
        verification = SESSION_AUTH.classify_iscsi_auth_session(
            "LOGGED_IN", False, False)

        self.assertEqual("NOT_REQUIRED", verification)
        self.assertIsNone(SESSION_AUTH.compatibility_authenticated(verification))

    def test_missing_policy_evidence_is_unknown(self):
        verification = SESSION_AUTH.classify_iscsi_auth_session(
            "LOGGED_IN", None, None)

        self.assertEqual("UNKNOWN", verification)
        self.assertIsNone(SESSION_AUTH.compatibility_authenticated(verification))

    def test_explicit_failure_is_failed(self):
        verification = SESSION_AUTH.classify_iscsi_auth_session(
            "FAILED", True, True, explicit_failure=True)

        self.assertEqual("FAILED", verification)
        self.assertFalse(SESSION_AUTH.compatibility_authenticated(verification))

    def test_helper_has_no_secret_contract(self):
        exported_names = set(vars(SESSION_AUTH))
        self.assertNotIn("chapSecret", exported_names)
        self.assertNotIn("password", exported_names)


if __name__ == "__main__":
    unittest.main()
