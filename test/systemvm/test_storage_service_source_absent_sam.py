# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information.
# The ASF licenses this file under the Apache License, Version 2.0.
# You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for permissions and limitations under the License.

"""Read-only absent-SAM witness and unchanged-source guards; no service or network mutation."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock

LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
try:
    from service_identity_source import ServicePublicIdentity
    from native_render_runtime import PROTOCOL_FILES
finally:sys.path.remove(str(LIB))


class ServiceSourceAbsentSamTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.root.chmod(0o700)
        self.configuration=self.root/"configuration";self.configuration.mkdir(mode=0o700)
        self.sam=self.root/"samba";self.sam.mkdir(mode=0o700);(self.sam/"private").mkdir(mode=0o700)
        self.reader=Mock(side_effect=AssertionError("A missing SAM must not cause a SID read or initialization"))
        self.identity=ServicePublicIdentity(Mock(),configuration=self.configuration,sam_root=self.sam,sid_reader=self.reader)
        self.identity.command=Mock(return_value="FIXTURE");self.identity.owners=Mock(return_value=[])
        self.scope={"instanceUuid":"11111111-1111-4111-8111-111111111111"}
        self.actual={"configurationDesiredState":{PROTOCOL_FILES["SMB"]:None,"posix-directory-policies.json":None,
            PROTOCOL_FILES["NFS"]:{"instanceUuid":self.scope["instanceUuid"],"exports":[]}}}

    def tearDown(self):self.temp.cleanup()

    def test_nfs_only_absence_is_frozen_and_checked_without_creating_or_reading_sam(self):
        frozen=self.identity.freeze(self.scope,self.actual)
        self.assertIsNone(frozen["publicLocalMachineSid"]);self.assertIn("absentLocalSam",frozen)
        self.assertEqual(3,len(frozen["absentLocalSam"]["databases"]))
        self.identity.unchanged(self.scope,self.actual,frozen)
        self.reader.assert_not_called();self.identity.command.assert_not_called()
        self.assertEqual([],list((self.sam/"private").iterdir()))

    def test_absent_parent_chain_is_allowed_but_directory_creation_invalidates_frozen_witness(self):
        (self.sam/"private").rmdir();self.sam.rmdir()
        frozen=self.identity.freeze(self.scope,self.actual);self.identity.unchanged(self.scope,self.actual,frozen)
        self.sam.mkdir(mode=0o700)
        with self.assertRaises(ValueError):self.identity.unchanged(self.scope,self.actual,frozen)

    def test_any_sam_database_created_after_capture_rejects_the_absence_witness(self):
        for relative in ("private/secrets.tdb","private/passdb.tdb","winbindd_idmap.tdb"):
            frozen=self.identity.freeze(self.scope,self.actual)
            path=self.sam/relative;path.write_bytes(b"SYNTHETIC_PRESENT_DATABASE");path.chmod(0o600)
            with self.assertRaises(ValueError):self.identity.unchanged(self.scope,self.actual,frozen)
            path.unlink()

    def test_existing_sam_keeps_the_strict_public_sid_reader(self):
        path=self.sam/"private/secrets.tdb";path.write_bytes(b"SYNTHETIC_PRESENT_DATABASE");path.chmod(0o600)
        self.reader.side_effect=None;self.reader.return_value="S-1-5-21-1-2-3"
        frozen=self.identity.freeze(self.scope,self.actual)
        self.assertEqual("S-1-5-21-1-2-3",frozen["publicLocalMachineSid"]);self.assertNotIn("absentLocalSam",frozen)
        self.identity.unchanged(self.scope,self.actual,frozen);self.assertEqual(2,self.reader.call_count)

    def test_configured_smb_cannot_claim_absent_sam(self):
        self.actual["configurationDesiredState"][PROTOCOL_FILES["SMB"]]={"shares":[]}
        with self.assertRaises(AssertionError):self.identity.freeze(self.scope,self.actual)
        self.reader.assert_called_once()

    def test_owned_smb_process_cannot_claim_absent_sam_even_without_canonical_smb(self):
        self.identity.owners.return_value=[{"unit":"ablestack-storage-smb@"+"1"*24+".service"}]
        with self.assertRaises(AssertionError):self.identity.freeze(self.scope,self.actual)
        self.reader.assert_called_once()

    def test_smb_added_after_absence_capture_is_rejected(self):
        frozen=self.identity.freeze(self.scope,self.actual)
        self.actual["configurationDesiredState"][PROTOCOL_FILES["SMB"]]={"shares":[]}
        with self.assertRaises(ValueError):self.identity.unchanged(self.scope,self.actual,frozen)

    def test_unprotected_or_symlink_parent_cannot_establish_absence(self):
        private=self.sam/"private";private.chmod(0o777)
        with self.assertRaises(ValueError):self.identity.absent_local_sam()
        private.chmod(0o700);private.rmdir();private.symlink_to(self.configuration,target_is_directory=True)
        with self.assertRaises(ValueError):self.identity.absent_local_sam()


if __name__=="__main__":unittest.main()
