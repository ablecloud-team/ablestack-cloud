# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "debian/usr/local/lib/ablestack-storage"))
import shared_directory_policy as h
import configuration_transaction as tx
import configuration_preflight as pf
import runtime_updater as updater


class SmbCreationContract(unittest.TestCase):
    def payload(self):
        return {"instanceUuid":"instance","enabled":True,"identityDomain":{"joinState":"JOINED","healthState":"UNKNOWN","config":{"adJoin":{"state":"RUNNING"}}},
                "shares":[{"uuid":"share","name":"team","state":"Ready","path":"/managed/team","config":{"keep":"original","directoryMode":"2770"},"acls":[]}]}

    def test_strict_octal_defaults_and_force_subset(self):
        self.assertEqual(h.SMB_MODE_DEFAULTS,h.smb_creation_modes({}))
        self.assertEqual("0640",h.smb_creation_modes({"createMask":"640"})["createMask"])
        for config in [{"createMask":660},{"createMask":True},{"createMask":"0888"},{"createMask":"1777"},{"createMask":"00660"},{"createMask":""},{"createMask":"0600","forceCreateMode":"0040"},{"directoryMask":"0700","forceDirectoryMode":"0001"}]:
            with self.subTest(config=config),self.assertRaises(h.PolicyError):
                h.smb_creation_modes(config)

    def test_only_target_options_change_and_observation_fields_do_not_change_policy(self):
        before=self.payload();after=copy.deepcopy(before)
        after["shares"][0]["config"]["createMask"]="0640";after["shares"][0]["state"]="Updating"
        after["identityDomain"]["healthState"]="OK";after["identityDomain"]["config"]["adJoin"]={"state":"COMMITTED"}
        value=h.smb_options_difference(before,after)
        self.assertEqual("0640",value["after"]["createMask"])
        self.assertEqual("0660",value["before"]["createMask"])
        self.assertNotIn("createMask",before["shares"][0]["config"])

    def test_auth_path_identity_acl_or_foreign_option_changes_cannot_use_reload(self):
        before=self.payload()
        for case in ["path","name","guestOk","directoryMode","acls","domain","listeners"]:
            after=copy.deepcopy(before);after["shares"][0]["config"]["createMask"]="0640"
            if case in ("path","name"):after["shares"][0][case]="changed"
            elif case in ("guestOk","directoryMode"):after["shares"][0]["config"][case]="changed"
            elif case=="acls":after["shares"][0]["acls"]=[{"principal":"other"}]
            elif case=="domain":after["identityDomain"]["joinState"]="NOT_JOINED"
            else:after["listeners"]=[{"port":1445}]
            with self.subTest(case=case),self.assertRaises(h.PolicyError):
                h.smb_options_difference(before,after)

    def test_committed_listener_transport_metadata_is_not_a_service_policy_change(self):
        before=self.payload();before.update(listenerOnly=True,listenerBefore={"old":"transport"},listenerOriginalNetwork=[])
        before["listeners"]=[{"id":"endpoint","port":445,"state":"Updating"}]
        after=copy.deepcopy(before)
        for key in ("listenerOnly","listenerBefore","listenerOriginalNetwork"):after.pop(key)
        after["listeners"][0]["state"]="Ready";after["shares"][0]["config"]["createMask"]="0640"
        self.assertEqual("0640",h.smb_options_difference(before,after)["after"]["createMask"])
        after["listeners"][0]["state"]="Disabled"
        with self.assertRaises(h.PolicyError):h.smb_options_difference(before,after)

    def test_same_effective_policy_is_noop_and_multiple_share_updates_are_rejected(self):
        before=self.payload();after=copy.deepcopy(before);after["shares"][0]["config"].update(h.SMB_MODE_DEFAULTS)
        self.assertIsNone(h.smb_options_difference(before,after))
        before["shares"].append({**copy.deepcopy(before["shares"][0]),"uuid":"second"})
        after=copy.deepcopy(before)
        for row in after["shares"]:row["config"]["createMask"]="0640"
        with self.assertRaises(h.PolicyError):h.smb_options_difference(before,after)

    def test_forged_internal_marker_cannot_enter_a_different_engine(self):
        with tempfile.TemporaryDirectory() as root:
            adapter=tx.RuntimeAdapter(root)
            with self.assertRaises(tx.Rejected) as caught:adapter.preflight({"onlineSmbOptions":True,"candidate":{"nfs export apply":{}}})
            self.assertEqual("INVALID_SMB_OPTIONS_INTENT",caught.exception.code)
            self.assertFalse((Path(root)/"request.json").exists())

    def test_execution_rechecks_the_confirmed_preflight_before_any_file_write(self):
        with tempfile.TemporaryDirectory() as root:
            adapter=tx.RuntimeAdapter(root)
            payload={"onlineSmbOptions":True,"instanceUuid":"instance","candidate":{"smb share apply":{}},
                     "preflight":{"expectedFingerprint":"confirmed","protocol":"SMB"}}
            with patch.object(pf,"inspect",return_value={"status":"READY","fingerprint":"changed"}):
                with self.assertRaises(tx.Rejected) as caught:adapter.preflight(payload)
            self.assertEqual("SMB_OPTIONS_STALE_OBSERVATION",caught.exception.code)
            self.assertFalse((Path(root)/"request.json").exists())

    def test_config_symlinks_writable_and_multiple_links_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/"owned.conf";path.write_text("[global]");path.chmod(0o644)
            link=Path(root)/"link";link.symlink_to(path)
            with self.assertRaises(h.PolicyError):h.SmbCreationPolicies.read_file(link)
            path.chmod(0o666)
            with self.assertRaises(h.PolicyError):h.SmbCreationPolicies.read_file(path)
            path.chmod(0o644)
            import os
            os.link(path,Path(root)/"hardlink")
            with self.assertRaises(h.PolicyError):h.SmbCreationPolicies.read_file(path)

    def test_creation_input_and_process_parent_observation_are_bound_to_fingerprint(self):
        a={"protocol":"SMB","revision":1,"smbcreation":{"createMask":"0640"},"creationfingerprint":"process-and-parent-A"}
        b=copy.deepcopy(a);b["smbcreation"]["createMask"]="0660"
        self.assertNotEqual(pf.fingerprint(a),pf.fingerprint(b))
        b=copy.deepcopy(a);b["creationfingerprint"]="process-and-parent-B"
        self.assertNotEqual(pf.fingerprint(a),pf.fingerprint(b))
        b=copy.deepcopy(a);b["connections"]=999
        self.assertEqual(pf.fingerprint(a),pf.fingerprint(b))

    def test_protected_guard_checks_candidate_ast_without_executing_it(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);desired=root/"desired.json";target=root/"candidate";file=target/updater.DIRECTORY_HELPER
            file.parent.mkdir(parents=True);file.write_text("raise RuntimeError('must not execute')\nSMB_CREATION_POLICY_VERSION=1\n")
            desired.write_text(json.dumps({"shares":[{"state":"Ready","config":{"createMask":"0640"}}]}))
            updater.require_smb_creation_compatibility(target,desired)
            file.write_text("VERSION=1\n")
            with self.assertRaises(updater.RuntimeUpgradeError) as e:updater.require_smb_creation_compatibility(target,desired)
            self.assertEqual("SMB_CREATION_POLICY_CAPABILITY_REQUIRED",e.exception.code)
            desired.write_text(json.dumps({"shares":[{"state":"Ready","config":dict(h.SMB_MODE_DEFAULTS)}]}))
            updater.require_smb_creation_compatibility(target,desired)
            desired.write_text("{damaged")
            with self.assertRaises(updater.RuntimeUpgradeError):updater.require_smb_creation_compatibility(target,desired)

    def stored_api_state(self, root, before):
        """Use the real listener stage/commit persistence that originally lost the flag."""
        import uuid
        controller = tx.SmbListenerProfiles(root / "profiles", root / "smb.conf")
        controller.base.write_text("[global]\n[common1453r5]\n create mask = 0660\n directory mask = 0770\n")
        operation = str(uuid.uuid4())
        with patch.object(controller, "bootstrap", return_value={"instanceUuid": before["instanceUuid"], "profiles": {}}), \
             patch.object(controller, "configuration", return_value=controller.base), \
             patch.object(controller, "start"), patch.object(controller, "ready", return_value=True):
            controller.stage(before, before, operation)
            self.assertTrue(tx.load(controller.manifest)["pending"])
            controller.commit(operation)
        self.assertNotIn("pending", tx.load(controller.manifest))
        return controller

    def test_actual_api_payload_to_listener_saved_state_to_creation_snapshot(self):
        fixture = json.loads((ROOT / "test/fixtures/smb-creation-api-operation-1454.json").read_text())
        for flag in ("actual", None, False, True):
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as directory:
                payload = copy.deepcopy(fixture)
                if flag != "actual":
                    for section in ("before", "candidate"):
                        for share in payload[section]["smb share apply"]["shares"]:
                            for acl in share["acls"]:
                                if flag is None:
                                    acl["config"].pop("passwordSupplied", None)
                                else:
                                    acl["config"]["passwordSupplied"] = flag
                untouched = copy.deepcopy(payload)
                controller = self.stored_api_state(Path(directory), payload["before"]["smb share apply"])
                saved = tx.load(controller.desired)
                self.assertNotIn("passwordSupplied", json.dumps(saved))
                engine = h.SmbCreationPolicies(controller.base, controller.desired)
                with patch.object(engine, "preview", return_value={}), patch.object(engine, "profiles", return_value=[]):
                    snapshot = engine.snapshot(payload)
                self.assertEqual("SMB_OPTIONS", snapshot["kind"])
                self.assertEqual("0640", snapshot["after"]["createMask"])
                self.assertEqual(untouched, payload)
                self.assertNotIn("passwordSupplied", json.dumps(tx.load(controller.desired)))
                self.assertEqual("READ_WRITE", saved["shares"][0]["acls"][0]["permission"])

    def test_actual_api_acl_grants_and_unrecognized_fields_remain_protected(self):
        fixture = json.loads((ROOT / "test/fixtures/smb-creation-api-operation-1454.json").read_text())
        for field, value in (("principal", "unlisted"), ("principalType", "LOCAL_GROUP"),
                             ("permission", "READ_ONLY"), ("state", "Disabled"),
                             ("config.futureSecurityPolicy", "changed"), ("config.passwordPolicy", "changed")):
            payload = copy.deepcopy(fixture)
            acl = payload["candidate"]["smb share apply"]["shares"][0]["acls"][0]
            if field.startswith("config."):
                acl["config"][field.split(".", 1)[1]] = value
            else:
                acl[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(h.PolicyError, "INVALID_SMB_OPTIONS_INTENT"):
                h.smb_options_difference(payload["before"]["smb share apply"], payload["candidate"]["smb share apply"])
        with tempfile.TemporaryDirectory() as directory:
            controller = self.stored_api_state(Path(directory), fixture["before"]["smb share apply"])
            saved = tx.load(controller.desired)
            saved["shares"][0]["acls"][0]["permission"] = "READ_ONLY"
            tx.atomic_json(controller.desired, saved)
            engine = h.SmbCreationPolicies(controller.base, controller.desired)
            with self.assertRaisesRegex(h.PolicyError, "SMB_OPTIONS_BASELINE_CHANGED"):
                engine.snapshot(fixture)


if __name__ == "__main__":
    unittest.main()
