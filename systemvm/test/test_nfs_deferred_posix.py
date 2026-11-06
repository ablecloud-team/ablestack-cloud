#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch

source = Path(os.environ.get("NFS_DEFERRED_TEST_MODULE", str(Path(__file__).resolve().parents[1] / "debian/usr/local/lib/ablestack-storage/configuration_transaction.py")))
spec = importlib.util.spec_from_file_location("deferred_posix_transaction", source)
txn = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = txn
spec.loader.exec_module(txn)

class NoPublisher(txn.RuntimeAdapter):
    def run(self, *args):
        raise AssertionError("metadata initialization must not invoke a protocol renderer")

@unittest.skipUnless(os.geteuid() == 0, "real POSIX ownership regression requires root")
class DeferredNfsPosixTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.directory = self.base / "root"
        self.directory.mkdir(mode=0o755)
        self.child = self.directory / "preserve.txt"
        self.child.write_bytes(b"existing-child-data")
        os.chown(self.child, 1234, 2345); os.chmod(self.child, 0o640)
        self.operation, self.instance, self.export = [str(uuid.uuid4()) for _ in range(3)]
        self.config = {"backingPath":str(self.directory), "volumeMountPath":str(self.base),
            "ownerUid":65534, "ownerGid":65534, "mode":"0775",
            "lastInspection":{"directoryCreated":True, "configurationOperationId":self.operation}}
        self.row = {"uuid":self.export, "path":"/export/deferred", "config":self.config}
        self.value = {"posixOnly":True, "configurationOperationId":self.operation, "exports":[self.row],
            "posixIntent":{"version":1, "create":True, "targetUuid":self.export,
                           "fields":{"ownerUid":65534, "ownerGid":65534, "mode":"0775"}}}
        self.payload = {"instanceUuid":self.instance, "operationid":self.operation, "baseRevision":0,
            "targetRevision":1, "candidate":{"nfs export apply":self.value}, "before":{}}
        self.transaction = txn.Transaction(self.base / "transactions", NoPublisher)
        def observe(row):
            s = Path(row["config"]["backingPath"]).lstat()
            return {"status":"OBSERVED", "fingerprint":str((s.st_uid,s.st_gid,s.st_mode,s.st_ino))}
        self.observe = patch.object(txn, "nfs_posix_observe", side_effect=observe)
        self.observe.start()
    def tearDown(self):
        self.observe.stop()
        self.tmp.cleanup()
    def metadata(self, path):
        s=path.stat()
        return s.st_uid,s.st_gid,s.st_mode&0o7777
    def test_deferred_creation_verifies_before_commit_and_later_acl_preserves(self):
        child=self.metadata(self.child)
        state=self.transaction.execute("apply",self.payload)
        self.assertEqual("VERIFIED",state["state"])
        self.assertEqual((65534,65534,0o775),self.metadata(self.directory))
        self.assertEqual(child,self.metadata(self.child)); self.assertEqual(b"existing-child-data",self.child.read_bytes())
        acl={"configurationOperationId":str(uuid.uuid4()),"posixIntent":{"version":1,"create":False,"fields":{},"targetUuid":str(uuid.uuid4())}}
        self.assertEqual({},txn.nfs_posix_apply_config(self.row,acl))
        self.transaction.execute("commit",self.payload)
        os.chmod(self.directory,0o750)
        self.assertEqual("COMMITTED",self.transaction.execute("apply",self.payload)["state"])
        self.assertEqual(0o750,self.metadata(self.directory)[2])
    def test_resume_observes_metadata_without_runner_result_or_second_apply(self):
        self.assertEqual("VERIFIED",self.transaction.execute("apply",self.payload)["state"])
        self.assertEqual("VERIFIED",self.transaction.execute("resume",self.payload)["state"])
    def test_verification_failure_restores_metadata_and_child_without_publication(self):
        class Fail(NoPublisher):
            def verify(self, commands):
                super().verify(commands)
                if self.nfs_posix_only({"candidate":commands}): raise txn.Rejected("SIMULATED_VERIFY_FAILURE")
        before=self.metadata(self.directory),self.metadata(self.child)
        state=txn.Transaction(self.base/"failure",Fail).execute("apply",self.payload)
        self.assertEqual("ROLLED_BACK",state["state"]);self.assertEqual("SUCCEEDED",state["rollback"]["state"])
        self.assertEqual(before,(self.metadata(self.directory),self.metadata(self.child)))
    def test_imported_initial_path_keeps_permissions(self):
        self.config.update(posixPolicy="PRESERVE_EXISTING_INITIAL",lastInspection={"directoryCreated":False})
        self.value["posixIntent"]["fields"]={}
        os.chown(self.directory,1234,2345);os.chmod(self.directory,0o750)
        self.assertEqual("VERIFIED",self.transaction.execute("apply",self.payload)["state"])
        self.assertEqual((1234,2345,0o750),self.metadata(self.directory))
    def test_stale_observation_or_foreign_create_receipt_never_changes_metadata(self):
        self.config["lastInspection"]={"directoryCreated":False}
        self.value["posixIntent"]["expectedObservation"]=txn.nfs_posix_observe(self.row)["fingerprint"]
        os.chmod(self.directory,0o750)
        state=self.transaction.execute("apply",self.payload)
        self.assertEqual("POSIX_OBSERVATION_CHANGED",state["originalfailure"]["code"])
        self.assertEqual((0,0,0o750),self.metadata(self.directory))
    def test_wrong_target_and_mixed_commands_are_rejected_before_apply(self):
        self.value["posixIntent"]["targetUuid"]=str(uuid.uuid4())
        state=self.transaction.execute("apply",self.payload)
        self.assertEqual("INVALID_DEFERRED_POSIX_INTENT",state["originalfailure"]["code"])
        self.assertEqual((0,0,0o755),self.metadata(self.directory))
    def test_mixed_protocol_payload_is_rejected_before_any_renderer(self):
        self.payload["candidate"]["smb share apply"]={"enabled":False}
        state=self.transaction.execute("apply",self.payload)
        self.assertEqual("INVALID_DEFERRED_POSIX_INTENT",state["originalfailure"]["code"])
        self.assertEqual((0,0,0o755),self.metadata(self.directory))
    def test_recursive_metadata_intent_cannot_escape_the_root_only_stage(self):
        self.value["posixIntent"]["fields"]["recursivePermission"]=True
        state=self.transaction.execute("apply",self.payload)
        self.assertEqual("INVALID_DEFERRED_POSIX_INTENT",state["originalfailure"]["code"])
        self.assertEqual((1234,2345,0o640),self.metadata(self.child))
    def test_replaced_directory_on_interrupted_writer_requires_recovery(self):
        self.transaction.execute("apply",self.payload)
        _, _, status = self.transaction.paths(self.payload)
        running=txn.load(status);running.update(state="RUNNING",phase="APPLYING");txn.atomic_json(status,running)
        renamed=self.base/"replaced"
        self.directory.rename(renamed);self.directory.mkdir()
        state=self.transaction.execute("resume",self.payload)
        self.assertEqual("RECOVERY_REQUIRED",state["state"])
        self.assertEqual((0,0,0o755),self.metadata(self.directory))

if __name__ == "__main__":
    unittest.main()
