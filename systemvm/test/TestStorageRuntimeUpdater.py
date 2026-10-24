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

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
UPDATER = ROOT / "debian/usr/local/lib/ablestack-storage/runtime_updater.py"
BUNDLE_BUILDER = ROOT.parent / "tools/build/build-storage-runtime-bundle.sh"
ENTRYPOINTS = (
    "ablestack-storagectl",
    "ablestack-storage-boot-reconcile",
    "ablestack-storage-monitor",
)


class StorageRuntimeUpdaterTest(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp(prefix="storage-runtime-test-"))
        self.runtime_root = self.temp / "runtime"
        self.state_root = self.temp / "state"
        self.entrypoint_root = self.temp / "bin"
        self.trusted_keys = self.runtime_root / "trusted-keys"
        self.lock_file = self.temp / "runtime.lock"
        self.source = self.temp / "source"
        self.output = self.temp / "output"
        for path in (self.entrypoint_root, self.trusted_keys, self.source, self.output):
            path.mkdir(parents=True, exist_ok=True)
        self.env = os.environ.copy()
        self.env.update({
            "ABLESTACK_STORAGE_RUNTIME_ROOT": str(self.runtime_root),
            "ABLESTACK_STORAGE_RUNTIME_STATE_ROOT": str(self.state_root),
            "ABLESTACK_STORAGE_RUNTIME_TRUSTED_KEYS": str(self.trusted_keys),
            "ABLESTACK_STORAGE_RUNTIME_ENTRYPOINT_ROOT": str(self.entrypoint_root),
            "ABLESTACK_STORAGE_RUNTIME_LOCK": str(self.lock_file),
            "ABLESTACK_STORAGE_RUNTIME_HELPER_ROOT": str(ROOT / "debian/usr/local/lib/ablestack-storage"),
        })
        for name in ENTRYPOINTS:
            self.write_script(self.entrypoint_root / name, "bootstrap")
            self.write_script(self.source / name, "v2")
        self.private_key = self.temp / "private.pem"
        self.public_key = self.trusted_keys / "test-key.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(self.private_key)], check=True)
        subprocess.run(["openssl", "pkey", "-in", str(self.private_key), "-pubout", "-out", str(self.public_key)], check=True)

    def tearDown(self):
        shutil.rmtree(self.temp, ignore_errors=True)

    def write_script(self, path, value):
        path.write_text(f"#!/bin/bash\nset -euo pipefail\necho {value}\n", encoding="utf-8")
        path.chmod(0o755)

    def run_updater(self, operation, request=None, success=True):
        command = ["python3", str(UPDATER), operation]
        stdin = json.dumps(request or {})
        result = subprocess.run(command, input=stdin, text=True, capture_output=True, env=self.env)
        payload = json.loads(result.stdout)
        if success:
            self.assertEqual(0, result.returncode, payload)
            self.assertTrue(payload["success"])
        else:
            self.assertNotEqual(0, result.returncode, payload)
            self.assertFalse(payload["success"])
        return payload

    def build_bundle(self, version="v2"):
        env = self.env.copy()
        env.update({
            "SOURCE_DATE_EPOCH": "0",
            "ABLESTACK_STORAGE_RUNTIME_BUILD_COMMIT": "test-commit",
            "ABLESTACK_STORAGE_RUNTIME_BUILD_TIME": "1970-01-01T00:00:00Z",
        })
        subprocess.run([
            str(BUNDLE_BUILDER), "--version", version, "--private-key", str(self.private_key),
            "--key-id", "test-key", "--output-dir", str(self.output), "--source-root", str(self.source),
        ], check=True, env=env, capture_output=True, text=True)
        return self.output / f"ablestack-storage-runtime-{version}.tar.gz"

    def stage_transaction(self, transaction="tx-1", version="v2", preflight_success=True):
        archive = self.build_bundle(version)
        manifest = self.output / "manifest.json"
        signature = self.output / "manifest.sig"
        request = {
            "transactionId": transaction,
            "bundleVersion": version,
            "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "manifestSha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
            "totalSize": archive.stat().st_size,
            "manifestSize": manifest.stat().st_size,
            "signatureSize": signature.stat().st_size,
        }
        self.run_updater("begin", request)
        self.run_updater("begin", request)
        transaction_dir = self.state_root / transaction
        shutil.copy2(archive, transaction_dir / "bundle.tar.gz")
        shutil.copy2(manifest, transaction_dir / "manifest.json")
        shutil.copy2(signature, transaction_dir / "manifest.sig")
        self.run_updater("finalize", request)
        self.run_updater("verify", request)
        self.run_updater("preflight", request, success=preflight_success)
        return request

    def test_signed_bundle_activation_and_rollback(self):
        bootstrap = self.run_updater("bootstrap")
        self.assertEqual("BOOTSTRAPPED", bootstrap["phase"])
        request = self.stage_transaction()
        activated = self.run_updater("activate", request)
        self.assertEqual("v2", activated["currentVersion"])
        result = subprocess.run([str(self.entrypoint_root / "ablestack-storagectl")], text=True, capture_output=True, check=True)
        self.assertEqual("v2", result.stdout.strip())
        rolled_back = self.run_updater("rollback", request)
        self.assertTrue(rolled_back["currentVersion"].startswith("bootstrap-"))
        result = subprocess.run([str(self.entrypoint_root / "ablestack-storagectl")], text=True, capture_output=True, check=True)
        self.assertEqual("bootstrap", result.stdout.strip())

    def confirm(self, request):
        return self.run_updater("activate", dict(request, confirmActivation=True))

    def test_template_default_and_bundle_share_version_and_nine_file_hashes(self):
        self.build_bundle("template-v2")
        default = self.temp / "default"
        default.mkdir()
        for name in ("manifest.json", "manifest.sig"):
            shutil.copy2(self.output / name, default / name)
        self.env["ABLESTACK_STORAGE_RUNTIME_DEFAULT"] = str(default)
        self.env["ABLESTACK_STORAGE_RUNTIME_DEFAULT_TRUST"] = str(self.trusted_keys)
        for name in ENTRYPOINTS:
            shutil.copy2(self.source / name, self.entrypoint_root / name)
        result = self.run_updater("bootstrap")
        self.assertEqual("template-v2", result["currentVersion"])
        manifest = json.loads((default / "manifest.json").read_text())
        release = self.runtime_root / "releases/template-v2"
        for item in manifest["files"]:
            self.assertEqual(item["sha256"],hashlib.sha256((release / item["path"]).read_bytes()).hexdigest())

    def test_confirmation_and_boot_persistence(self):
        self.run_updater("bootstrap")
        request = self.stage_transaction()
        activated = self.run_updater("activate", request)
        self.assertEqual("ACTIVATED", activated["phase"])
        self.assertEqual("COMPLETE", self.confirm(request)["phase"])
        self.assertEqual("v2", self.run_updater("bootstrap")["currentVersion"])
        self.assertTrue(self.run_updater("capabilities")["integrity"]["verified"])

    def test_unconfirmed_activation_is_recovered_before_boot_reconcile(self):
        baseline = self.run_updater("bootstrap")["currentVersion"]
        request = self.stage_transaction()
        self.run_updater("activate", request)
        self.assertEqual(baseline, self.run_updater("bootstrap")["currentVersion"])
        self.assertEqual("ROLLED_BACK", self.run_updater("status", request)["phase"])

    def test_manifest_changed_after_preflight_cannot_activate(self):
        baseline = self.run_updater("bootstrap")["currentVersion"]
        request = self.stage_transaction()
        path = self.state_root / request["transactionId"] / "manifest.json"
        value = json.loads(path.read_text())
        value["runtimeAbiVersion"] = "incompatible"
        path.write_text(json.dumps(value))
        self.assertEqual("MANIFEST_HASH_MISMATCH", self.run_updater("activate", request, success=False)["errorCode"])
        self.assertEqual(baseline, self.run_updater("capabilities")["currentVersion"])

    def test_stale_rollback_and_duplicate_rollback(self):
        self.run_updater("bootstrap")
        one = self.stage_transaction()
        self.run_updater("activate", one)
        self.confirm(one)
        two = self.stage_transaction("tx-2", "v3")
        self.run_updater("activate", two)
        self.confirm(two)
        self.assertEqual("ROLLBACK_STALE_OPERATION", self.run_updater("rollback", one, success=False)["errorCode"])
        self.assertEqual("v2", self.run_updater("rollback", two)["currentVersion"])
        self.assertEqual("v2", self.run_updater("rollback", two)["currentVersion"])

    def test_rollback_resumes_after_pointer_switch_and_lost_confirmation_write(self):
        baseline = self.run_updater("bootstrap")["currentVersion"]
        request = self.stage_transaction()
        self.run_updater("activate", request)
        self.confirm(request)
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location("runtime_candidate", UPDATER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        original = module.atomic_json
        def interrupted(path, *args, **kwargs):
            if Path(path).name == "confirmed.json":
                raise OSError("injected lost confirmation write")
            return original(path, *args, **kwargs)
        with patch.dict(os.environ, self.env), patch.object(module, "atomic_json", side_effect=interrupted):
            with self.assertRaisesRegex(OSError, "injected"):
                module.RuntimeUpdater().execute("rollback", request)
        state = self.run_updater("status", request)
        self.assertEqual("ROLLING_BACK", state["phase"])
        self.assertEqual(baseline, state["currentVersion"])
        self.assertEqual(baseline, self.run_updater("rollback", request)["currentVersion"])

    def test_already_current_version_cannot_replace_its_previous_pointer(self):
        self.run_updater("bootstrap")
        original = self.stage_transaction()
        self.run_updater("activate", original)
        self.confirm(original)
        previous = self.run_updater("capabilities")["previousVersion"]
        duplicate = self.stage_transaction("tx-duplicate", "v2", preflight_success=False)
        failure = self.run_updater("preflight", duplicate, success=False)
        self.assertEqual("RUNTIME_ALREADY_CURRENT", failure["errorCode"])
        self.assertEqual(previous, self.run_updater("capabilities")["previousVersion"])
        self.assertEqual(previous, self.run_updater("rollback", original)["currentVersion"])

    def test_changed_previous_code_blocks_rollback(self):
        baseline = self.run_updater("bootstrap")["currentVersion"]
        request = self.stage_transaction()
        self.run_updater("activate", request)
        self.confirm(request)
        (self.runtime_root / "releases" / baseline / ENTRYPOINTS[0]).write_text("changed")
        self.assertEqual("PREVIOUS_RUNTIME_INTEGRITY_MISMATCH", self.run_updater("rollback", request, success=False)["errorCode"])
        self.assertEqual("v2", self.run_updater("capabilities")["currentVersion"])

    def test_payload_contains_six_helpers_and_exact_modes(self):
        self.build_bundle()
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual(2, manifest["payloadLayout"])
        self.assertEqual(9, len(manifest["files"]))
        self.assertEqual(6, sum(x["mode"]=="0644" for x in manifest["files"]))
        with tarfile.open(self.output / "ablestack-storage-runtime-v2.tar.gz") as archive:
            for item in manifest["files"]:
                member = archive.getmember(item["path"])
                self.assertEqual(member.size, item["bytes"])
                self.assertEqual(member.uid, 0)

    def test_manifest_signature_tamper_is_rejected(self):
        self.run_updater("bootstrap")
        archive = self.build_bundle("tampered")
        manifest = self.output / "manifest.json"
        signature = self.output / "manifest.sig"
        request = {
            "transactionId": "tx-tampered",
            "bundleVersion": "tampered",
            "archiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "manifestSha256": "0" * 64,
            "totalSize": archive.stat().st_size,
            "manifestSize": manifest.stat().st_size,
            "signatureSize": signature.stat().st_size,
        }
        manifest.write_text(manifest.read_text(encoding="utf-8").replace("test-commit", "modified-commit"), encoding="utf-8")
        request["manifestSha256"] = hashlib.sha256(manifest.read_bytes()).hexdigest()
        request["manifestSize"] = manifest.stat().st_size
        self.run_updater("begin", request)
        target = self.state_root / "tx-tampered"
        shutil.copy2(archive, target / "bundle.tar.gz")
        shutil.copy2(manifest, target / "manifest.json")
        shutil.copy2(signature, target / "manifest.sig")
        self.run_updater("finalize", request)
        result = self.run_updater("verify", request, success=False)
        self.assertEqual("SIGNATURE_INVALID", result["errorCode"])

    def test_activation_failure_restores_previous_runtime(self):
        bootstrap = self.run_updater("bootstrap")
        request = self.stage_transaction(transaction="tx-rollback", version="rollback-target")
        target = self.runtime_root / "releases/rollback-target/ablestack-storagectl"
        target.write_text("#!/bin/bash\nexit 1\n", encoding="utf-8")
        target.chmod(0o755)
        result = self.run_updater("activate", request, success=False)
        self.assertEqual("RELEASE_HASH_MISMATCH", result["errorCode"])
        capabilities = self.run_updater("capabilities")
        self.assertEqual(bootstrap["currentVersion"], capabilities["currentVersion"])
        state = self.run_updater("status", request)
        self.assertEqual("PREFLIGHT_OK", state["phase"])

    def test_archive_path_traversal_is_rejected(self):
        spec = importlib.util.spec_from_file_location("runtime_updater", UPDATER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        updater = module.RuntimeUpdater()
        updater.runtime_root = self.runtime_root
        updater.releases = self.runtime_root / "releases"
        transaction = self.temp / "unsafe"
        transaction.mkdir()
        archive_path = transaction / "bundle.tar.gz"
        payload = self.temp / "payload"
        payload.write_text("unsafe", encoding="utf-8")
        with tarfile.open(archive_path, "w:gz") as archive:
            archive.add(payload, arcname="../escape")
        manifest = {
            "bundleVersion": "unsafe",
            "files": [{"path": name, "sha256": "0" * 64, "mode": "0755"} for name in ENTRYPOINTS],
        }
        declared = {name: {"sha256": "0" * 64, "mode": 0o755} for name in ENTRYPOINTS}
        with self.assertRaisesRegex(module.RuntimeUpgradeError, "unsafe archive path"):
            updater.extract_release(transaction, manifest, declared)


if __name__ == "__main__":
    unittest.main()
