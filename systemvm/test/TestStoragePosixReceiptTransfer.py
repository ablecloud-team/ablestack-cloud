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

import copy,json,os,sys,tempfile,unittest,uuid
from pathlib import Path
LIB=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0,str(LIB))
from posix_policy_receipt import PosixPolicyReceipt
from posix_receipt_transfer import PosixReceiptTransfer,posix_row_sha256

class StoragePosixReceiptTransferTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.base=Path(self.temp.name)
        self.instance=str(uuid.uuid4());self.policy=str(uuid.uuid4());self.volume=str(uuid.uuid4());self.filesystem=str(uuid.uuid4())
        self.identity={"filesystemUuid":self.filesystem,"device":2064,"inode":12345,"effectiveUid":0,"effectiveGid":0,"effectiveMode":"0770","aclSha256":"a"*64}
        self.request={"uuid":self.policy,"instanceUuid":self.instance,"volumeUuid":self.volume,"volumeMountPath":"/srv/ablestack-storage/volumes/"+self.volume,
                      "relativePath":"leaf","revision":2,"expectedDirectoryIdentity":{**self.identity,"effectiveUid":65534},"config":{"directoryMode":"0770","applyOwner":True,"ownerUid":0,"ownerGid":0}}
        self.row={"request":self.request,"config":self.request["config"],"effective":{"directoryIdentity":self.identity}}
        self.source=PosixPolicyReceipt(self.base/"source");self.source.write(self.request,self.row["effective"])
        self.transfer=PosixReceiptTransfer(self.source)
        self.records=self.transfer.collect({self.policy:self.row},self.instance,lambda request:self.row["effective"])
        self.target=PosixPolicyReceipt(self.base/"target");self.transfer=PosixReceiptTransfer(self.target)
        self.observed={"directoryIdentity":{**self.identity,"device":2080}}
        self.scope={"instanceUuid":self.instance,"templateUpgradeUuid":str(uuid.uuid4()),"operationUuid":str(uuid.uuid4()),"revision":39}
        self.bindings=[{"volumeUuid":self.volume,"sizeBytes":20*(1<<30),"filesystemUuid":self.filesystem}]
        self.maintenance={"bootHeld":True,"scope":self.scope}
        self.volume_proof={"mappingStatus":"EXACT","matchedBy":"VOLUME_SERIAL","serial":self.volume.replace("-","")[:20],"sizeBytes":20*(1<<30),
                           "filesystemUuid":self.filesystem,"mounts":[{"target":self.request["volumeMountPath"]}]}
    def restore(self,records=None):
        return self.transfer.restore(records or self.records,self.scope,"f"*64,self.bindings,self.maintenance,lambda request:self.observed,lambda binding:self.volume_proof)
    def test_device_only_transfer_preserves_canonical_bytes_and_boot_replay_is_noop(self):
        source=json.dumps(self.row,sort_keys=True);result=self.restore()
        self.assertFalse(result["dataPermissionsChanged"]);self.assertFalse(result["canonicalDesiredStateChanged"])
        receipt=self.target.read(self.request);self.assertEqual(2080,receipt["directoryIdentity"]["device"]);self.assertEqual(2064,receipt["canonicalDirectoryIdentity"]["device"])
        self.assertTrue(self.target.replay(self.request,self.row,self.observed));self.assertEqual(source,json.dumps(self.row,sort_keys=True))
        # A previously remapped ROOT can become the source of another SAMEVM transfer.
        reexport=PosixReceiptTransfer(self.target).collect({self.policy:self.row},self.instance,lambda request:self.observed)
        self.assertEqual(posix_row_sha256(self.row),reexport[self.policy]["rowSha256"])
    def test_inode_uid_mode_acl_or_foreign_filesystem_cannot_be_remapped(self):
        for key,value in (("inode",99),("effectiveUid",65534),("effectiveMode","0777"),("aclSha256","b"*64),("filesystemUuid",str(uuid.uuid4()))):
            self.observed={"directoryIdentity":{**self.identity,"device":2080,key:value}}
            with self.assertRaises(ValueError):self.restore()
            self.assertFalse(self.target.receipts.exists())
    def test_marker_scope_or_volume_serial_size_fs_mount_mismatch_fails_before_receipt_write(self):
        self.maintenance={"bootHeld":False,"scope":self.scope}
        with self.assertRaises(ValueError):self.restore()
        self.maintenance={"bootHeld":True,"scope":{**self.scope,"operationUuid":str(uuid.uuid4())}}
        with self.assertRaises(ValueError):self.restore()
        self.maintenance={"bootHeld":True,"scope":self.scope}
        for key,value in (("matchedBy","FILESYSTEM_UUID"),("serial",self.volume.replace("-","")[:8]),("sizeBytes",1),("filesystemUuid",str(uuid.uuid4())),("mounts",[])):
            original=self.volume_proof;self.volume_proof={**original,key:value}
            with self.assertRaises(ValueError):self.restore()
            self.volume_proof=original
        self.assertFalse(self.target.receipts.exists())
    def test_source_without_receipt_and_tampered_canonical_row_are_rejected(self):
        self.source.path(self.request).unlink()
        with self.assertRaises(ValueError):PosixReceiptTransfer(self.source).collect({self.policy:self.row},self.instance,lambda request:self.row["effective"])
        records=copy.deepcopy(self.records);records[self.policy]["canonicalRow"]["config"]["directoryMode"]="0777"
        with self.assertRaises(ValueError):self.restore(records)
        self.assertFalse(self.target.receipts.exists())
    def test_transferred_plan_verifies_protected_source_row_without_seeding_target_canonical_state(self):
        self.restore();before=self.target.path(self.request).read_bytes()
        plan=self.target.transferred_plan(self.row,self.observed,self.scope,"f"*64,self.maintenance)
        self.assertTrue(plan["transferOnly"]);self.assertFalse(plan["sideEffects"]);self.assertFalse(plan["canonicalDesiredStateChanged"])
        self.assertEqual(self.row,plan["configurationDesiredRow"]);self.assertEqual(self.observed["directoryIdentity"],plan["predictedDirectoryIdentity"])
        self.assertEqual(before,self.target.path(self.request).read_bytes())
        for changed in ({"source_sha":"a"*64},{"root_scope":{**self.scope,"operationUuid":str(uuid.uuid4())}},{"observed":{"directoryIdentity":{**self.observed["directoryIdentity"],"inode":99}}}):
            arguments={"row":self.row,"observed":self.observed,"root_scope":self.scope,"source_sha":"f"*64,"maintenance":self.maintenance,**changed}
            with self.assertRaises(ValueError):self.target.transferred_plan(**arguments)
        self.assertEqual(before,self.target.path(self.request).read_bytes())

    def test_clone_new_instance_cannot_reuse_same_machine_policy_receipts(self):
        self.scope={**self.scope,"instanceUuid":str(uuid.uuid4())};self.maintenance={"bootHeld":True,"scope":self.scope}
        with self.assertRaises(ValueError):self.restore()
        self.assertFalse(self.target.receipts.exists())

if __name__=="__main__":unittest.main()
