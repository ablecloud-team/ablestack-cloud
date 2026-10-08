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

import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import uuid

SOURCE = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/lib/ablestack-storage/posix_root_initialization.py"
spec = importlib.util.spec_from_file_location("root_initializer", SOURCE)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class StoragePosixRootInitializationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name); self.data = self.base / "data"; self.data.mkdir(mode=0o755)
        self.descriptor = os.open(self.data, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        self.addCleanup(os.close, self.descriptor)
        value = self.data.stat()
        self.identity = {"filesystemUuid": str(uuid.uuid4()), "device": value.st_dev, "inode": value.st_ino,
                         "effectiveUid": value.st_uid, "effectiveGid": value.st_gid,
                         "effectiveMode": format(stat.S_IMODE(value.st_mode), "04o"), "aclSha256": "0" * 64}
        self.before = {**self.identity, "directoryIdentity": self.identity, "filesystemRoot": True}
        self.request = {"instanceUuid": str(uuid.uuid4()), "uuid": str(uuid.uuid4()), "revision": 1,
                        "volumeUuid": str(uuid.uuid4()), "volumeMountPath": "/srv/ablestack-storage/volumes/data",
                        "relativePath": "", "allowFilesystemRoot": True, "expectedDirectoryIdentity": self.identity,
                        "config": {"directoryMode": "0775", "applyOwner": True, "ownerUid": 65534, "ownerGid": 65534},
                        "rootInitialization": {"formatReceiptUuid": str(uuid.uuid4()), "operationUuid": str(uuid.uuid4())}}
        self.initializer = module.RootInitialization(self.base / "operations", self.base / "receipts")
        self.journal = {"phase": "COMPLETE", "formatStarted": True, "mountPath": self.request["volumeMountPath"],
                        "formatReceipt": {"schemaVersion": 1, "receiptUuid": self.request["rootInitialization"]["formatReceiptUuid"],
                                          "volumeUuid": self.request["volumeUuid"], "filesystemUuid": self.identity["filesystemUuid"],
                                          "matchedBy": "VOLUME_SERIAL", "serial": self.request["volumeUuid"].replace("-", "")[:20],
                                          "directoryIdentity": self.identity}}
        self.journal_path = self.initializer.operations / (self.request["volumeUuid"] + ".json")
        module.root_receipt_write(self.journal_path, self.journal)

    def test_exact_new_format_is_claimed_once_and_verified_replay_is_idempotent(self):
        receipt, replayed = self.initializer.prepare(self.request, self.before, self.descriptor)
        self.assertFalse(replayed); self.assertEqual("APPLYING", receipt["phase"])
        self.data.chmod(0o775)
        after = {**self.before, "directoryIdentity": {**self.identity, "effectiveMode": "0775"}}
        self.initializer.verified(self.request, receipt, after); self.initializer.complete(self.request, receipt)
        reused, replayed = self.initializer.prepare(self.request, after, self.descriptor)
        self.assertTrue(replayed); self.assertEqual("COMPLETE", reused["phase"])
        self.assertEqual(0o600, stat.S_IMODE(self.initializer.path(self.request).stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(self.initializer.receipts.stat().st_mode))

    def test_missing_or_foreign_format_proof_never_claims_an_initializer(self):
        self.journal_path.unlink()
        with self.assertRaises(ValueError): self.initializer.prepare(self.request, self.before, self.descriptor)
        self.assertFalse(self.initializer.receipts.exists())
        for field, value in (("matchedBy", "UNIQUE_BLANK_SIZE"), ("filesystemUuid", str(uuid.uuid4())), ("volumeUuid", str(uuid.uuid4()))):
            journal = {**self.journal, "formatReceipt": {**self.journal["formatReceipt"], field: value}}
            module.root_receipt_write(self.journal_path, journal)
            with self.assertRaises(ValueError): self.initializer.prepare(self.request, self.before, self.descriptor)
        self.assertFalse(self.initializer.receipts.exists())

    def test_an_existing_user_file_or_recovery_directory_data_prevents_new_initialization(self):
        child = self.data / "existing"; child.write_text("preserve")
        before = child.stat()
        with self.assertRaises(ValueError): self.initializer.prepare(self.request, self.before, self.descriptor)
        self.assertEqual(before, child.stat()); self.assertEqual("preserve", child.read_text())
        child.unlink(); lost = self.data / "lost+found"; lost.mkdir(mode=0o700); (lost / "recovered").write_text("preserve")
        with self.assertRaises(ValueError): self.initializer.prepare(self.request, self.before, self.descriptor)
        self.assertFalse(self.initializer.receipts.exists())

    def test_empty_ext4_recovery_directory_is_preserved(self):
        lost = self.data / "lost+found"; lost.mkdir(mode=0o700); before = lost.stat()
        self.initializer.prepare(self.request, self.before, self.descriptor)
        self.assertEqual(before, lost.stat())

    def test_foreign_operation_or_changed_policy_cannot_reuse_the_receipt(self):
        self.initializer.prepare(self.request, self.before, self.descriptor)
        for request in ({**self.request, "rootInitialization": {**self.request["rootInitialization"], "operationUuid": str(uuid.uuid4())}},
                        {**self.request, "config": {"directoryMode": "0777"}}):
            with self.assertRaises(ValueError): self.initializer.prepare(request, self.before, self.descriptor)

    def test_partial_metadata_change_requires_recovery_and_cannot_be_marked_complete(self):
        receipt, _ = self.initializer.prepare(self.request, self.before, self.descriptor)
        changed = {**self.before, "directoryIdentity": {**self.identity, "effectiveMode": "0700"}}
        with self.assertRaises(ValueError): self.initializer.prepare(self.request, changed, self.descriptor)
        with self.assertRaises(ValueError): self.initializer.complete(self.request, receipt)
        self.initializer.failed(self.request, receipt, changed)
        self.assertEqual("RECOVERY_REQUIRED", module.root_receipt_read(self.initializer.path(self.request))["phase"])

    def test_symlink_journal_and_fresh_preview_are_readonly_fail_closed(self):
        self.assertTrue(self.initializer.preview(self.request, self.before, self.descriptor)["available"])
        self.assertFalse(self.initializer.receipts.exists())
        real = self.journal_path.with_suffix(".real"); self.journal_path.rename(real); self.journal_path.symlink_to(real)
        with self.assertRaises(ValueError): self.initializer.preview(self.request, self.before, self.descriptor)

    def test_cli_embeds_the_complete_helper_without_a_new_guest_import(self):
        cli = SOURCE.parents[2] / "bin/ablestack-storagectl"
        embedded = SOURCE.read_text().split('"""Explicit one-inode', 1)[1].strip()
        self.assertIn(embedded, cli.read_text())
        self.assertNotIn("from posix_root_initialization import", cli.read_text())

if __name__ == "__main__": unittest.main()
