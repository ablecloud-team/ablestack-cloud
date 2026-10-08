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

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/appliance/scripts"))
from storage_identity_seed import SEED_PATHS, identity_seed_absence

CLEANUP = ROOT / "tools/appliance/systemvmtemplate/scripts/cleanup.sh"


class StorageTemplateIdentitySeedTest(unittest.TestCase):
    def root(self, directory):
        root = Path(directory)
        (root / "etc").mkdir(mode=0o700)
        return root

    def cleanup(self, root, proc=None):
        if proc is None:
            proc=root/"proc";proc.mkdir(exist_ok=True)
        return subprocess.run(["bash", "-c", 'source "$1"; cleanup_storage_identity_seed "$2" "$3"',
                               "new-image-test", str(CLEANUP), str(root),str(proc)],
                              capture_output=True, text=True, timeout=10)

    def test_absence_is_readonly_and_never_claims_an_already_unique_sid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.root(directory)
            sentinel = root / "etc/sentinel"; sentinel.write_bytes(b"preserved")
            before = sentinel.read_bytes()
            result = identity_seed_absence(root)
            self.assertFalse(result["uniqueMachineSidAlreadyClaimed"])
            self.assertEqual(SEED_PATHS, tuple(row["path"] for row in result["identitySeeds"]))
            self.assertTrue(all(row["absent"] is True for row in result["identitySeeds"]))
            self.assertEqual(before, sentinel.read_bytes())

    def test_existing_seed_rejects_without_reading_or_modifying_bytes(self):
        for relative in SEED_PATHS:
            with self.subTest(path=relative), tempfile.TemporaryDirectory() as directory:
                root = self.root(directory); path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"synthetic-original-private-seed"); path.chmod(0o600)
                before = path.read_bytes()
                with self.assertRaises(ValueError): identity_seed_absence(root)
                self.assertEqual(before, path.read_bytes())

    def test_dangling_seed_link_and_linked_parent_reject_without_following(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.root(directory); path = root / SEED_PATHS[0]
            path.parent.mkdir(parents=True); path.symlink_to(root / "absent")
            with self.assertRaises(ValueError): identity_seed_absence(root)
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as other:
            root = self.root(directory); (root / "var").symlink_to(other, target_is_directory=True)
            with self.assertRaises(ValueError): identity_seed_absence(root)

    def test_actual_finalizer_cleans_new_image_seed_only_and_keeps_other_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = self.root(directory)
            sentinel = root / "etc/sentinel"; sentinel.write_bytes(b"preserved")
            for relative in SEED_PATHS:
                path = root / relative; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"synthetic-new-build-seed"); path.chmod(0o600)
            result = self.cleanup(root)
            self.assertEqual(0, result.returncode, result.stderr)
            identity_seed_absence(root)
            self.assertEqual(b"preserved", sentinel.read_bytes())

    def test_actual_finalizer_rejects_linked_parent_without_deleting_external_seed(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as other:
            root = self.root(directory); outside = Path(other)
            external = outside / "lib/samba/private/secrets.tdb"; external.parent.mkdir(parents=True)
            external.write_bytes(b"outside-preserved"); (root / "var").symlink_to(outside, target_is_directory=True)
            result = self.cleanup(root)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(b"outside-preserved", external.read_bytes())

    def test_actual_finalizer_rejects_leaf_link_without_deleting_it_or_its_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root=self.root(directory);target=root/"outside-preserved";target.write_bytes(b"preserved")
            seed=root/SEED_PATHS[0];seed.parent.mkdir(parents=True);seed.symlink_to(target)
            result=self.cleanup(root)
            self.assertNotEqual(0,result.returncode);self.assertTrue(seed.is_symlink());self.assertEqual(b"preserved",target.read_bytes())

    def test_known_orphan_identity_processes_block_before_seed_delete(self):
        for name in ("smbd","nmbd","winbindd","samba","samba-dcerpcd","samba-bgqd"):
            with self.subTest(process=name),tempfile.TemporaryDirectory() as directory:
                root=self.root(directory);seed=root/SEED_PATHS[0];seed.parent.mkdir(parents=True);seed.write_bytes(b"new-build-preserved")
                proc=root/"proc";(proc/"99").mkdir(parents=True);(proc/"99/comm").write_text(name)
                result=self.cleanup(root,proc)
                self.assertNotEqual(0,result.returncode);self.assertEqual(b"new-build-preserved",seed.read_bytes())

    def test_finalizer_and_attestation_path_sets_match(self):
        source = CLEANUP.read_text()
        for relative in SEED_PATHS: self.assertIn(relative, source)


if __name__ == "__main__": unittest.main()
