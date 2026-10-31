#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

path = Path(os.environ.get("NFS_POSIX_TEST_MODULE", str(Path(__file__).resolve().parents[1] / "debian/usr/local/lib/ablestack-storage/configuration_transaction.py")))
spec = importlib.util.spec_from_file_location("nfs_posix_transaction", path)
txn = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = txn
spec.loader.exec_module(txn)

class NfsPosixIntentTest(unittest.TestCase):
    def row(self, config=None):
        return {"uuid":"export-1","config":config or {"ownerUid":1234,"ownerGid":2345,"mode":"0750","recursivePermission":True}}
    def payload(self, **values):
        return {"configurationOperationId":"attempt", "posixIntent":{"targetUuid":"export-1", "fields":{}, **values}}

    def test_policy_only_and_boot_do_not_reapply_stored_posix(self):
        for value in ({},self.payload()):
            with patch.object(txn,"nfs_posix_observe") as observe:
                self.assertEqual({},txn.nfs_posix_apply_config(self.row(),value))
                observe.assert_not_called()

    def test_initialization_matches_six_recommendations(self):
        for read_only in (False,True):
            for policy in ("ROOT","NONE","ALL"):
                config={"readOnly":read_only,"rootSquash":policy=="ROOT","allSquash":policy=="ALL","anonUid":1666,"anonGid":2777}
                actual=txn.nfs_posix_apply_config(self.row(config),{},True)
                mapped=not read_only and policy!="NONE"
                self.assertEqual(1666 if mapped else 0,actual["ownerUid"])
                self.assertEqual("0775" if mapped else "0755",actual["mode"])
                self.assertFalse(actual["recursivePermission"])

    def test_imported_initial_never_initializes_implicitly(self):
        config={"posixPolicy":"PRESERVE_EXISTING_INITIAL","rootSquash":True}
        self.assertEqual({},txn.nfs_posix_apply_config(self.row(config),{},True))

    def test_fresh_inspection_receipt_is_bound_to_target_and_attempt(self):
        config={"lastInspection":{"directoryCreated":True,"configurationOperationId":"attempt"}}
        self.assertIn("ownerUid",txn.nfs_posix_apply_config(self.row(config),self.payload(create=True)))
        for value in (self.payload(create=False),{"configurationOperationId":"different", "posixIntent":self.payload(create=True)["posixIntent"]}):
            self.assertEqual({},txn.nfs_posix_apply_config(self.row(config),value))

    def test_explicit_zero_and_partial_fields_do_not_repeat_recursion(self):
        with patch.object(txn,"nfs_posix_observe",return_value={"status":"OBSERVED","fingerprint":"fresh"}):
            value=txn.nfs_posix_apply_config(self.row(),self.payload(fields={"ownerUid":0},expectedObservation="fresh"))
        self.assertEqual({"ownerUid":0,"recursivePermission":False},value)

    def test_stale_or_unavailable_observation_rejected_before_chown(self):
        for value in ({"status":"UNAVAILABLE"},{"status":"OBSERVED","fingerprint":"changed"}):
            with patch.object(txn,"nfs_posix_observe",return_value=value):
                with self.assertRaises(txn.Rejected):
                    txn.nfs_posix_apply_config(self.row(),self.payload(fields={"mode":"0770"},expectedObservation="old"))

    def test_other_export_and_unknown_keys_are_not_selected(self):
        self.assertEqual({},txn.nfs_posix_apply_config({"uuid":"other","config":{}},self.payload(fields={"mode":"0770"})))
        with self.assertRaisesRegex(txn.Rejected,"INVALID_NFS_POSIX_INTENT"):
            txn.nfs_posix_apply_config(self.row(),self.payload(fields={"shell":"unsafe"}))

    def test_real_stat_zero_fingerprint_and_path_missing(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);mount=root/"volume";mount.mkdir();directory=mount/"export";directory.mkdir()
            row={"uuid":"export-1","config":{"volumeMountPath":str(mount),"backingPath":str(directory)}}
            def run(args,**kw):
                return subprocess.CompletedProcess(args,0,stdout="fs-test\n" if args[0]=="findmnt" else b"user::rwx\n",stderr="")
            with patch.object(txn.subprocess,"run",side_effect=run):
                first=txn.nfs_posix_observe(row,root)
                self.assertEqual("OBSERVED",first["status"]);self.assertEqual(os.getuid(),first["owneruid"])
                os.chmod(directory,0o750)
                second=txn.nfs_posix_observe(row,root)
                self.assertNotEqual(first["fingerprint"],second["fingerprint"])
                row["config"]["backingPath"]=str(mount/"missing")
                self.assertEqual("PATH_MISSING",txn.nfs_posix_observe(row,root)["status"])

    def test_escaped_and_symlink_paths_never_observe(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);mount=root/"volume";mount.mkdir();link=mount/"link";link.symlink_to(root)
            for target in (link,root/"outside"):
                row={"config":{"volumeMountPath":str(mount),"backingPath":str(target)}}
                self.assertEqual("UNAVAILABLE",txn.nfs_posix_observe(row,root)["status"])

    def test_canonical_persist_strips_transient_intent(self):
        text=Path(os.environ.get("NFS_POSIX_TEST_CTL", str(Path(__file__).resolve().parents[1]/"debian/usr/local/bin/ablestack-storagectl"))).read_text()
        block=text.split("persist_nfs_desired_state() {",1)[1].split("\n}",1)[0]
        self.assertIn('value.pop("posixIntent", None)',block)
        self.assertIn('value.pop("configurationOperationId", None)',block)

if __name__=="__main__":
    unittest.main()
