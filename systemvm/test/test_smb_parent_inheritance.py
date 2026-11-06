# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "debian/usr/local/lib/ablestack-storage"))
import shared_directory_policy as h
import runtime_updater as updater
import configuration_preflight as preflight


class ParentInheritanceTest(unittest.TestCase):
    def test_optional_boolean_and_fixed_identity_are_strict(self):
        self.assertFalse(h.inheritance_enabled({}))
        self.assertFalse(h.inheritance_enabled({"inheritOwner": False}))
        self.assertTrue(h.inheritance_enabled({"inheritOwner": True}))
        for bad in ("yes", "false", 0, 1, None, []):
            with self.assertRaises(h.PolicyError):
                h.inheritance_enabled({"inheritOwner": bad})
        for config, mode in (({"inheritOwner": True, "forceUser": "user"}, "0770"),
                             ({"inheritOwner": True, "forceGroup": "group"}, "0770"),
                             ({"forceGroup": "group"}, "2770")):
            with self.assertRaises(h.PolicyError) as e:
                h.inheritance_conflict(config, mode)
            self.assertEqual("SMB_PARENT_IDENTITY_CONFLICT", e.exception.code)
        h.inheritance_conflict({}, "2770")
        h.inheritance_conflict({"forceUser": "user"}, "0770")

    def test_baseline_inspection_never_executes_candidate_code(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw); path = root / "updater.py"
            for version, expected in ((4, 0), (5, 1)):
                path.write_text("UPDATER_VERSION = %d\n" % version)
                path.chmod(0o644)
                self.assertEqual(expected, h.inheritance_protection_version(path))
            marker = root / "executed"
            path.write_text("open(%r,'w').write('bad')\nUPDATER_VERSION=5\n" % str(marker))
            self.assertEqual(1, h.inheritance_protection_version(path))
            self.assertFalse(marker.exists())
            path.chmod(0o666)
            self.assertEqual(0, h.inheritance_protection_version(path))
            path.chmod(0o644)
            alias = root / "symlink"; alias.symlink_to(path)
            self.assertEqual(0, h.inheritance_protection_version(alias))

    def test_ad_baseline_requires_six_without_disabling_parent_inheritance_on_five(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "updater.py"
            for version, ad in ((5, 0), (6, 1)):
                path.write_text("UPDATER_VERSION = %d\n" % version)
                path.chmod(0o644)
                self.assertEqual(1, h.inheritance_protection_version(path))
                self.assertEqual(ad, h.inheritance_protection_version(path, minimum=6))

    def test_parent_observation_and_explicit_disable_preserve_identity(self):
        parent = {"status": "OBSERVED", "owneruid": 100001, "ownergid": 200001, "mode": "2770",
                  "inode": 42, "filesystemuuid": "fs", "volumeuuid": "volume",
                  "canonicalpath": "export/team", "fingerprint": "stat", "path": "/managed/team"}
        policy = {**parent, "uuid": "policy"}
        engine = Mock()
        engine.observe.return_value = parent
        engine.policy_for.return_value = policy
        with patch.object(h, "DirectoryPolicies", return_value=engine), patch.object(h, "inheritance_protection_version", return_value=1), patch.object(h, "smb_runtime_settings", return_value=[]):
            target = {"uuid": "NEW", "config": {"directoryPolicyUuid": "policy"}}
            result = h.inheritance_preview(target, policy, {"inheritOwner": True})
            self.assertEqual("PARENT", result["futureowner"])
            self.assertEqual("PARENT", result["futuregroup"])
            self.assertEqual(100001, result["parent"]["owneruid"])
            self.assertFalse(result["existingobjectschange"])
            engine.policy_for.assert_called_with("/managed/team")
            drift = {**policy, "inode": 43}
            with self.assertRaises(h.PolicyError):
                h.inheritance_preview(target, drift, {"inheritOwner": True})
            with patch.object(h, "inheritance_protection_version", return_value=0):
                disabled = h.inheritance_preview(target, policy, {"inheritOwner": False})
                self.assertEqual("CREATOR", disabled["futureowner"])
                with self.assertRaises(h.PolicyError) as e:
                    h.inheritance_preview(target, policy, {"inheritOwner": True})
                self.assertEqual("SMB_PARENT_INHERITANCE_PROTECTION_REQUIRED", e.exception.code)

    def test_invalid_inventory_row_is_diagnostic_not_a_list_failure(self):
        result = h.inheritance_observations({"shares": [{"uuid": "bad", "config": {"inheritOwner": "yes"}}]})
        self.assertEqual("UNKNOWN", result[0]["status"])
        self.assertEqual("SMB_INHERIT_OWNER_INVALID", result[0]["code"])

    def test_signed_target_guard_legacy_active_false_and_nonexecuting_ast(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw); desired = root / "smb-share-apply.json"
            helper = root / updater.DIRECTORY_HELPER
            helper.parent.mkdir(parents=True)
            for shares in ([], [{"config": {}}], [{"config": {"inheritOwner": False}}],
                           [{"state": "Disabled", "config": {"inheritOwner": True}}]):
                desired.write_text(json.dumps({"shares": shares}))
                updater.require_smb_inheritance_compatibility(root, desired)
            desired.write_text(json.dumps({"shares": [{"config": {"inheritOwner": True}}]}))
            for source in (None, "# legacy helper", "SMB_PARENT_INHERITANCE_VERSION = True", "SMB_PARENT_INHERITANCE_VERSION = 0"):
                if source is not None: helper.write_text(source)
                with self.assertRaises(updater.RuntimeUpgradeError) as e:
                    updater.require_smb_inheritance_compatibility(root, desired)
                self.assertEqual("SMB_INHERITANCE_CAPABILITY_REQUIRED", e.exception.code)
            marker = root / "executed"
            helper.write_text("open(%r,'w').write('bad')\nSMB_PARENT_INHERITANCE_VERSION=1\n" % str(marker))
            updater.require_smb_inheritance_compatibility(root, desired)
            self.assertFalse(marker.exists())
            for broken in ('{', '{"shares":{}}', '{"shares":[null]}', '{"shares":[{"config":{"inheritOwner":"yes"}}]}'):
                desired.write_text(broken)
                with self.assertRaises(updater.RuntimeUpgradeError) as e:
                    updater.require_smb_inheritance_compatibility(root, desired)
                self.assertEqual("SMB_INHERITANCE_PROTECTED", e.exception.code)

    def test_preview_fingerprint_binds_policy_and_observed_parent(self):
        intent = {"protocol": "SMB", "revision": 3, "smbinheritance": {"inheritOwner": True, "directoryPolicyId": "policy"}, "inheritancefingerprint": "first"}
        original = preflight.fingerprint(intent)
        self.assertNotEqual(original, preflight.fingerprint({**intent, "inheritancefingerprint": "second"}))
        self.assertNotEqual(original, preflight.fingerprint({**intent, "smbinheritance": {"inheritOwner": False, "directoryPolicyId": "policy"}}))


if __name__ == "__main__":
    unittest.main()
