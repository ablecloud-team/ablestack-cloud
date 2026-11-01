# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
from pathlib import Path
import json,sys,tempfile,unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"debian/usr/local/lib/ablestack-storage"))
import smb_file_identity as f
import runtime_updater as u

class FixedIdentityContract(unittest.TestCase):
    def test_legacy_and_typed_policy(self):
        self.assertEqual("AUTHENTICATED",f.policy({})["mode"])
        self.assertEqual(7001,f.policy({"fileIdentity":{"version":1,"mode":"FIXED","uid":7001,"gid":7002}})["uid"])
        for value in [True,{},{"version":True,"mode":"AUTHENTICATED"},{"version":1,"mode":"INVALID"},
                      {"version":1,"mode":"FIXED","uid":0,"gid":7002},{"version":1,"mode":"FIXED","uid":7001,"gid":0},
                      {"version":1,"mode":"FIXED","uid":"7001","gid":7002},{"version":1,"mode":"FIXED","uid":7001,"gid":True},
                      {"version":1,"mode":"FIXED","uid":65534,"gid":7002},{"version":1,"mode":"FIXED","uid":61200,"gid":7002}]:
            with self.subTest(value=value),self.assertRaises(f.PolicyError):f.policy({"fileIdentity":value})
    def test_reserved_names_cannot_enroll_as_auth_accounts(self):
        for value in ["asfs_u_c1eca707_7001","ABLESTACK\\ASFS_g_c1eca707_7002"]:
            with self.assertRaises(f.PolicyError):f.reject_auth_identity(value)
        f.reject_auth_identity("normal-user")
    def test_ordinary_mode_never_observes_audit_or_accounts(self):
        with patch.object(f,"audit_dependency",side_effect=AssertionError("ordinary code changed")):
            self.assertEqual("AUTHENTICATED",f.FixedIdentities().preview({"config":{}},"unused")["mode"])
    def test_conflicting_policy_rejects_before_dependency_or_account_mutation(self):
        config={"fileIdentity":{"version":1,"mode":"FIXED","uid":7001,"gid":7002}}
        for flag in ["guestOk","inheritOwner"]:
            with self.subTest(flag=flag),patch.object(f,"audit_dependency",side_effect=AssertionError("too late")),self.assertRaises(f.PolicyError):
                f.FixedIdentities().preview({"config":{**config,flag:True}},"unused")
    def test_registry_rejects_symlinks_and_preserves_atomic_owned_state(self):
        with tempfile.TemporaryDirectory()as td:
            path=Path(td)/"owned.json";engine=f.FixedIdentities(path)
            self.assertEqual({},engine.read()["identities"])
            engine.write({"version":1,"identities":{"test":{"uid":7001,"gid":7002}}})
            self.assertEqual(0o600,path.stat().st_mode&0o777)
            self.assertEqual(7001,engine.read()["identities"]["test"]["uid"])
            path.rename(Path(td)/"real");path.symlink_to(Path(td)/"real")
            with self.assertRaises(OSError):engine.read()
    def test_common_posix_change_cannot_enable_sgid_or_close_forced_access(self):
        row={"state":"Ready","acls":[{"principal":"allowed"}],"config":{"backingPath":"/managed/volume/team",
            "volumeMountPath":"/managed/volume","fileIdentity":{"version":1,"mode":"FIXED","uid":7001,"gid":7002}}}
        value={"policy":{"canonicalpath":"team","owneruid":7001,"ownergid":7002,"mode":"2770"},"smbShares":[row]}
        with self.assertRaises(f.PolicyError):f.validate_directory_change(value)
        value["policy"]["mode"]="0000"
        with patch.object(f,"checked",return_value="user::rwx\ngroup::rwx\nother::---"):
            with self.assertRaises(f.PolicyError):f.validate_directory_change(value)
        value["policy"]["mode"]="0770"
        with patch.object(f,"checked",return_value="user::rwx\ngroup::rwx\nother::---"):f.validate_directory_change(value)
    def test_retired_identity_blocks_older_signed_runtime_capability(self):
        with tempfile.TemporaryDirectory()as td:
            root=Path(td);registry=root/"owned.json";desired=root/"desired.json"
            registry.write_text(json.dumps({"identities":{"owned":{"state":"RETIRED"}}}));desired.write_text('{"shares":[]}')
            with self.assertRaises(u.RuntimeUpgradeError):u.require_smb_file_identity_compatibility(root,desired,registry)
            helper=root/"lib/ablestack-storage";helper.mkdir(parents=True)
            (helper/"smb_file_identity.py").write_text("SMB_FILE_IDENTITY_VERSION = 1\n")
            u.require_smb_file_identity_compatibility(root,desired,registry)
    def test_audit_and_admin_render_contract(self):
        result=f.render({"user":"asfs_u_7001","group":"asfs_g_7002"})
        self.assertIn("   admin users =",result)
        self.assertIn("   full_audit:failure = none",result)
        self.assertIn("   vfs objects = full_audit",result)
        self.assertTrue(any("%U|%u" in s for s in result))
    def test_options_recovery_observes_file_identity_drift_too(self):
        import configuration_transaction as tx
        with tempfile.TemporaryDirectory() as td:
            private=Path(td);candidate={"smb share apply":{"shares":[]}}
            (private/"request.json").write_text(json.dumps({"candidate":candidate,"onlineSmbOptions":True}))
            (private/"candidate-completed.json").write_text(json.dumps({"requestHash":tx.public_hash(candidate)}))
            adapter=tx.RuntimeAdapter(private)
            with patch.object(adapter,"smb_options_only",return_value=True),patch.object(adapter,"verify",side_effect=f.PolicyError("SMB_FIXED_IDENTITY_RUNTIME_DRIFT")):
                self.assertEqual("FAILED",adapter.observe_completed())
            with patch.object(adapter,"smb_options_only",return_value=True),patch.object(adapter,"verify",return_value=None) as verify:
                self.assertEqual("VERIFIED",adapter.observe_completed())
                verify.assert_called_once_with(candidate)
    def test_disabled_protocol_does_not_require_published_sections(self):
        row={"config":{"fileIdentity":{"version":1,"mode":"FIXED","uid":7001,"gid":7002}},"acls":[{"principal":"allowed"}]}
        engine=f.FixedIdentities()
        with patch.object(engine,"inspect_identity",return_value={"exists":True,"owned":True}),patch.object(engine,"preview",side_effect=AssertionError("disabled listener was observed")):
            engine.verify({"instanceUuid":"instance","enabled":False,"shares":[row]})
if __name__=="__main__":unittest.main()
