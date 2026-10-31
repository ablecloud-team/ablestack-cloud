# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "debian/usr/local/lib/ablestack-storage"))
import shared_directory_policy as h
import configuration_transaction as tx
import configuration_preflight as pf
import runtime_updater as updater

class Paths(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.managed = self.base / "volumes"; self.managed.mkdir()
        self.volume = self.managed / str(uuid.uuid4()); self.volume.mkdir()
        self.engine = h.FileSharePathBindings(self.managed, self.base / "bindings", self.base / "op" / "paths.json")
        original = self.engine.volume
        def volume(row):
            c=row["config"]; rel=h.relative_file_share_path(c["relativeSharePath"])
            root=Path(c["volumeMountPath"])
            if root != self.volume or c["backingPath"] != str(root / rel): raise h.PolicyError("DIRECTORY_POLICY_PATH_MISMATCH")
            fd=os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            identity=c.get("pathIdentity") or {}
            if identity and (identity["filesystemUuid"] != "fs" or identity["volumeRootInode"] != os.fstat(fd).st_ino):
                os.close(fd); raise h.PolicyError("DIRECTORY_PATH_IDENTITY_CHANGED")
            return root,rel,fd,"fs"
        self.engine.volume = volume
        self.engine.pin = lambda *args: None
        self.row = {"uuid": str(uuid.uuid4()), "state": "Ready", "config": {"pathBindingVersion":1, "relativeSharePath":"projects/team",
                    "volumeMountPath":str(self.volume), "backingPath":str(self.volume / "projects/team"), "createDirectory":True}}
        self.payload={"instanceUuid":str(uuid.uuid4()),"operationid":str(uuid.uuid4()),"candidate":{"nfs export apply":{"exports":[self.row]}}}
    def tearDown(self): self.tmp.cleanup()
    def inspect(self, row=None):
        with patch.object(h, "checked", return_value=b"user::rwx\ngroup::r-x\nother::r-x\n"):
            return self.engine.inspect(row or self.row)
    def begin(self):
        with patch.object(h, "checked", return_value=b""):
            self.engine.begin(self.payload)
    def test_normalization_rejects_only_invalid_paths(self):
        self.assertEqual("projects/team-a",h.relative_file_share_path("projects/team-a"))
        for path in ("", "/root", ".", "..", "a/../b", "a//b", "a\\b", "a\x00b", "x"*256, "a/"):
            with self.subTest(path=path), self.assertRaises(h.PolicyError): h.relative_file_share_path(path)
    def test_explicit_creation_and_durable_receipt(self):
        self.begin(); result=self.engine.ensure(self.row,"NFS")
        self.assertTrue(result["created"]); self.assertTrue((self.volume/"projects/team").is_dir())
        receipt=self.engine.journal_value()
        self.assertEqual(2,len(receipt["created"]))
        self.assertEqual(self.row["config"]["pathIdentity"],receipt["bindings"][self.row["uuid"]]["pathIdentity"])
        self.assertEqual({"filesystemUuid","volumeRootInode","inode"},set(self.row["config"]["pathIdentity"]))
        self.assertEqual(0o755,self.engine.root.stat().st_mode&0o777)
        self.assertEqual(0o600,self.engine.registry.stat().st_mode&0o777)
        self.engine.ensure(self.row,"NFS")
        self.assertEqual(2,len(self.engine.journal_value()["created"]))
    def test_creation_is_never_implicit_or_adopted_after_interruption(self):
        self.row["config"]["createDirectory"]=False; self.begin()
        with self.assertRaisesRegex(h.PolicyError,"DIRECTORY_CREATION_REQUIRED"): self.engine.ensure(self.row,"NFS")
        self.row["config"]["createDirectory"]=True
        (self.volume/"projects").mkdir()
        with self.assertRaisesRegex(h.PolicyError,"DIRECTORY_CREATION_UNCONFIRMED"): self.engine.ensure(self.row,"NFS")
    def test_existing_directory_permissions_and_identity_are_not_reset(self):
        target=self.volume/"projects/team";target.mkdir(parents=True);target.chmod(0o750)
        self.begin();before=target.stat()
        result=self.engine.ensure(self.row,"SMB")
        self.assertFalse(result["created"]);self.assertEqual(before.st_mode,target.stat().st_mode)
        self.engine.enrich_desired({"shares":[self.row]})
        target.rename(target.with_name("old"));target.mkdir()
        with self.assertRaisesRegex(h.PolicyError,"DIRECTORY_PATH_IDENTITY_CHANGED"):self.inspect()
    def test_symlink_and_same_device_mount_boundary_rejected(self):
        (self.volume/"projects").symlink_to(self.base)
        with self.assertRaisesRegex(h.PolicyError,"DIRECTORY_PATH_SYMLINK"):self.inspect()
        (self.volume/"projects").unlink();(self.volume/"projects/team").mkdir(parents=True)
        real=self.engine.mnt_id
        with patch.object(self.engine,"mnt_id",side_effect=[100,101]):
            with self.assertRaisesRegex(h.PolicyError,"DIRECTORY_MOUNT_BOUNDARY"):self.inspect()
    def test_rollback_empty_only_and_retains_client_data(self):
        self.begin();self.engine.ensure(self.row,"NFS")
        self.engine.remove_anchor=lambda entry: None
        self.engine.rollback()
        self.assertFalse((self.volume/"projects").exists())
        self.row["config"].pop("pathIdentity");self.begin();self.engine.ensure(self.row,"NFS")
        target=self.volume/"projects/team";(target/"keep").write_text("data")
        retained=self.engine.rollback()
        self.assertIn(str(target),retained);self.assertEqual("data",(target/"keep").read_text())
    def test_parent_inode_change_after_admission_is_rejected(self):
        parent=self.volume/"projects";parent.mkdir()
        self.begin();parent.rename(parent.with_name("original"));parent.mkdir()
        with self.assertRaisesRegex(h.PolicyError,"DIRECTORY_PATH_IDENTITY_CHANGED"):self.engine.ensure(self.row,"NFS")
        self.assertFalse((parent/"team").exists())

    def test_downgrade_protection_reads_both_protocols(self):
        desired=self.base/"desired";desired.mkdir();registry=desired/"directory-policy.json"
        (desired/"nfs-export-apply.json").write_text(json.dumps({"exports":[self.row]}))
        release=self.base/"release";(release/"lib/ablestack-storage").mkdir(parents=True)
        helper=release/updater.DIRECTORY_HELPER;helper.write_text("SMB_CREATION_POLICY_VERSION=1")
        with self.assertRaisesRegex(updater.RuntimeUpgradeError,"active nested"):updater.require_nested_paths_compatibility(release,registry)
        helper.write_text("NESTED_FILE_SHARE_PATH_VERSION=1")
        updater.require_nested_paths_compatibility(release,registry)
    def test_fingerprint_covers_path_observation_and_relation_changes(self):
        intent={"protocol":"NFS","revision":1,"pathtarget":self.row,"pathfingerprint":"before"}
        baseline=pf.fingerprint(intent)
        intent["pathfingerprint"]="after";self.assertNotEqual(baseline,pf.fingerprint(intent))
        self.assertNotIn("pathbindings",tx.Transaction.bindings_status({},self.base))
if __name__=="__main__":unittest.main()
