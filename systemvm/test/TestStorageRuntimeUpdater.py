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
        })
        for name in ENTRYPOINTS:
            self.write_script(self.entrypoint_root / name, "bootstrap")
            self.write_script(self.source / name, "v2")
        self.private_key_fd = os.memfd_create("storage-runtime-test-key", os.MFD_CLOEXEC)
        private = subprocess.check_output(["openssl", "genpkey", "-algorithm", "ED25519"], stderr=subprocess.DEVNULL)
        os.write(self.private_key_fd, private)
        os.lseek(self.private_key_fd, 0, os.SEEK_SET)
        self.private_key = Path(f"/proc/self/fd/{self.private_key_fd}")
        self.public_key = self.trusted_keys / "test-key.pem"
        subprocess.run(["openssl", "pkey", "-in", str(self.private_key), "-pubout", "-out", str(self.public_key)], check=True, pass_fds=(self.private_key_fd,))

    def tearDown(self):
        os.close(self.private_key_fd)
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
        ], check=True, env=env, capture_output=True, text=True, pass_fds=(self.private_key_fd,))
        return self.output / f"ablestack-storage-runtime-{version}.tar.gz"

    def stage_transaction(self, transaction="tx-1", version="v2"):
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
        self.run_updater("preflight", request)
        return request

    def test_signed_builder_pins_all_three_platform_compatibility_ranges(self):
        self.build_bundle()
        compatibility = json.loads((self.output / 'manifest.json').read_text())['compatibility']
        self.assertEqual(1, compatibility['schemaVersion'])
        for domain in ('manager', 'agent', 'template'):
            self.assertEqual({'minimumVersion': '4.23.0.0', 'maximumVersionExclusive': '4.24.0.0'}, compatibility[domain])

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

    def activate_signed(self):
        self.run_updater("bootstrap")
        request = self.stage_transaction()
        self.run_updater("activate", request)
        return request

    def make_activating(self, switched=False):
        bootstrap = self.run_updater("bootstrap")["currentVersion"]
        request = self.stage_transaction()
        state = self.run_updater("status", request)
        state.update(phase="ACTIVATING", previousVersion=bootstrap)
        (self.state_root / request['transactionId'] / 'state.json').write_text(json.dumps(state))
        if switched:
            previous = self.runtime_root / 'previous'
            previous.symlink_to(self.runtime_root / 'releases' / bootstrap)
            current = self.runtime_root / 'current'
            current.unlink()
            current.symlink_to(self.runtime_root / 'releases/v2')
        return request, bootstrap

    def test_activation_resume_after_pointer_switch_keeps_the_original_previous_release(self):
        request, previous = self.make_activating(switched=True)
        result = self.run_updater('activate', request)
        self.assertEqual('COMPLETE', result['phase'])
        self.assertEqual(previous, result['previousVersion'])
        self.assertEqual(previous, self.run_updater('capabilities')['previousVersion'])
        self.assertTrue(self.run_updater('readback', request)['signedRuntimeVerified'])

    def test_activation_resume_before_pointer_switch_replays_the_pinned_release(self):
        request, previous = self.make_activating()
        result = self.run_updater('activate', request)
        self.assertEqual('COMPLETE', result['phase'])
        self.assertEqual(previous, result['previousVersion'])
        self.assertEqual('v2', result['currentVersion'])

    def test_activation_resume_rejects_a_third_current_release_without_writing_state(self):
        request, previous = self.make_activating()
        third = self.runtime_root / 'releases/third'
        third.mkdir()
        current = self.runtime_root / 'current'
        current.unlink()
        current.symlink_to(third)
        state_path = self.state_root / request['transactionId'] / 'state.json'
        before = state_path.read_bytes()
        result = self.run_updater('activate', request, success=False)
        self.assertEqual('ACTIVATION_RESUME_STATE_MISMATCH', result['errorCode'])
        self.assertEqual(before, state_path.read_bytes())
        self.assertEqual('third', self.run_updater('capabilities')['currentVersion'])

    def test_signed_readback_verifies_exact_release_and_has_no_state_or_target_writes(self):
        request = self.activate_signed()
        before = {str(path): (path.read_bytes(), path.stat().st_mtime_ns) for path in self.temp.rglob('*') if path.is_file() and not path.is_symlink()}
        result = self.run_updater("readback", request)
        self.assertTrue(result["signedRuntimeVerified"])
        self.assertTrue(result["installedFilesVerified"])
        self.assertTrue(result["entrypointsVerified"])
        after = {str(path): (path.read_bytes(), path.stat().st_mtime_ns) for path in self.temp.rglob('*') if path.is_file() and not path.is_symlink()}
        self.assertEqual(before, after)

    def test_signed_readback_rejects_different_scope_installed_bytes_and_entrypoint_binding(self):
        request = self.activate_signed()
        different = self.run_updater("readback", dict(request, archiveSha256="0" * 64), success=False)
        self.assertEqual("RUNTIME_READBACK_SCOPE_MISMATCH", different["errorCode"])
        entrypoint = self.entrypoint_root / ENTRYPOINTS[0]
        entrypoint.unlink()
        entrypoint.symlink_to(self.runtime_root / 'releases/v2' / ENTRYPOINTS[0])
        wrong_binding = self.run_updater("readback", request, success=False)
        self.assertEqual("RUNTIME_ENTRYPOINT_BINDING_INVALID", wrong_binding["errorCode"])
        entrypoint.unlink()
        entrypoint.symlink_to(self.runtime_root / 'current' / ENTRYPOINTS[0])
        target = self.runtime_root / 'releases/v2' / ENTRYPOINTS[0]
        target.write_text('#!/bin/bash\necho changed\n')
        changed = self.run_updater("readback", request, success=False)
        self.assertEqual("RELEASE_HASH_MISMATCH", changed["errorCode"])

    def test_unsigned_bootstrap_cannot_pass_signed_readback(self):
        self.run_updater("bootstrap")
        request = self.stage_transaction()
        result = self.run_updater("readback", request, success=False)
        self.assertEqual("SIGNED_RUNTIME_NOT_INSTALLED", result["errorCode"])

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
        self.assertEqual("ACTIVATION_FAILED_ROLLED_BACK", result["errorCode"])
        capabilities = self.run_updater("capabilities")
        self.assertEqual(bootstrap["currentVersion"], capabilities["currentVersion"])
        state = self.run_updater("status", request)
        self.assertEqual("ROLLED_BACK", state["phase"])

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
