#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Exercise NFS capacity identity queries with actual POSIX record-lock semantics."""
import fcntl
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import thirdparty_primary_capacity as capacity


class NfsPrimaryCapacityTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.stage = self.root / "stage"
        self.primary = self.root / "primary"
        self.stage.mkdir()
        self.primary.mkdir()
        self.primary_uuid = "3d154c38-a00e-4225-bb81-44905a2bcdb2"
        findmnt = patch.object(capacity.subprocess, "run", side_effect=self.find_mount)
        findmnt.start()
        self.addCleanup(findmnt.stop)
        # Linux NFS implements flock with POSIX record locks. Using the real
        # lockf syscall reproduces EBADF for an exclusive lock on a read-only FD.
        locks = patch.object(capacity.fcntl, "flock", side_effect=fcntl.lockf)
        locks.start()
        self.addCleanup(locks.stop)

    def find_mount(self, args, **kwargs):
        target = Path(args[args.index("--target") + 1])
        if target.is_relative_to(self.primary):
            mount = {"target": str(self.primary), "fstype": "gfs2", "uuid": self.primary_uuid, "fsroot": "/"}
        else:
            mount = {"target": str(self.stage), "fstype": "nfs4", "uuid": None, "fsroot": "/"}
        return SimpleNamespace(stdout=json.dumps({"filesystems": [mount]}))

    def test_existing_nfs_identity_can_be_queried_without_writing(self):
        prepared = capacity.filesystem_capacity(self.stage, True)
        directory = self.stage / ".ablestack-capacity"
        before = {file.name: file.read_bytes() for file in directory.iterdir()}

        current = capacity.filesystem_capacity(self.stage)

        self.assertEqual(prepared["storageKey"], current["storageKey"])
        self.assertGreaterEqual(current["availableBytes"], 0)
        self.assertEqual(before, {file.name: file.read_bytes() for file in directory.iterdir()})

    def test_qcow2_primary_capacity_can_be_rechecked_with_nfs_staging(self):
        paths = [self.primary / "root.qcow2", self.primary / "data.qcow2"]
        for file in paths:
            file.touch()
        volume_bytes = 100 * 1024**3
        saved = capacity.prepare_backup_capacity("backup-1", self.stage, paths, [volume_bytes, volume_bytes])

        current = capacity.backup_capacity(saved)

        self.assertEqual(saved["stagingStorageKey"], current["stagingStorageKey"])
        self.assertEqual(1, len(current["primaryClaims"]))
        claim = current["primaryClaims"][0]
        self.assertEqual("fs:" + self.primary_uuid, claim["storageKey"])
        self.assertEqual(2 * volume_bytes, claim["volumeBytes"])
        self.assertEqual(240 * 1024**3, claim["requiredBytes"])

    def test_query_does_not_create_a_missing_nfs_identity(self):
        directory = self.stage / ".ablestack-capacity"
        directory.mkdir()
        (directory / "identity.lock").touch()

        with self.assertRaisesRegex(RuntimeError, "Saved NFS capacity identity is unavailable"):
            capacity.filesystem_capacity(self.stage)

        self.assertEqual(["identity.lock"], [file.name for file in directory.iterdir()])

    def test_query_does_not_create_a_missing_nfs_namespace(self):
        with self.assertRaises(FileNotFoundError):
            capacity.filesystem_capacity(self.stage)

        self.assertFalse((self.stage / ".ablestack-capacity").exists())


if __name__ == "__main__":
    unittest.main()
