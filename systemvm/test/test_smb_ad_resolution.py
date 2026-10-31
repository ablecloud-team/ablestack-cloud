# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Cold AD ACL replay must establish Winbind before NSS/account/directory mutation."""
import ast
import builtins
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "debian/usr/local/lib/ablestack-storage"
sys.path.insert(0, str(HELPER))
import shared_directory_policy as policy


def payload():
    return {"enabled": True, "netbiosName": "STORTEST", "identityDomain": {
        "domainName": "ablestack.local", "joinState": "JOINED"},
        "shares": [{"state": "Ready", "acls": [
            {"principalType": "AD_USER", "principal": "ABLESTACK\\alice"},
            {"principalType": "AD_GROUP", "principal": "ABLESTACK\\writers"}]}]}


class AdResolution(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.active = False
        self.failure = None
        self.info = types.SimpleNamespace(st_mode=stat.S_IFREG | 0o644, st_uid=0, st_size=400)

    def command(self, args, **kwargs):
        self.calls.append(args)
        self.assertIn("timeout", kwargs)
        self.assertNotIn("shell", kwargs)
        rc, out = 0, ""
        if args[0] == "testparm":
            out = {"security": "ADS", "realm": "ABLESTACK.LOCAL", "netbios name": "STORTEST"}[args[2].split("=", 1)[1]]
        elif args[:2] == ["systemctl", "is-active"]:
            rc = 0 if self.active else 3
        elif args[:2] == ["systemctl", "start"]:
            self.active = True
        elif args[0] == "getent":
            self.assertTrue(self.active, "NSS reached before Winbind")
            out = "identity:x:10000:10003"
        if self.failure and self.failure(args):
            rc = 1
        return subprocess.CompletedProcess(args, rc, out, "")

    def execute(self, value=None):
        with patch.object(Path, "lstat", return_value=self.info), \
                patch.object(policy.subprocess, "run", side_effect=self.command):
            policy.prepare_smb_ad_resolution(value or payload(), "STORTEST")

    def assert_code(self, code):
        with self.assertRaises(policy.PolicyError) as raised:
            self.execute()
        self.assertEqual(code, raised.exception.code)

    def test_cold_start_trust_then_exact_user_and_group_nss(self):
        self.execute()
        start = self.calls.index(["systemctl", "start", "winbind.service"])
        trust = self.calls.index(["wbinfo", "-t"])
        first = next(i for i, c in enumerate(self.calls) if c[0] == "getent")
        self.assertLess(start, trust)
        self.assertLess(trust, first)
        self.assertIn(["getent", "--", "passwd", "ABLESTACK\\alice"], self.calls)
        self.assertIn(["getent", "--", "group", "ABLESTACK\\writers"], self.calls)
        self.assertFalse(any("smbd" in a or "enable" in a for c in self.calls for a in c))

    def test_warm_resolver_not_restarted(self):
        self.active = True
        self.execute()
        self.assertFalse(any(c[:2] == ["systemctl", "start"] for c in self.calls))

    def test_local_disabled_or_inactive_ad_acls_skip_all_commands(self):
        for field in ("local", "service", "share", "acl"):
            value = payload()
            if field == "local":
                for a in value["shares"][0]["acls"]:
                    a["principalType"] = "LOCAL_USER"
            elif field == "service":
                value["enabled"] = False
            elif field == "share":
                value["shares"][0]["state"] = "Disabled"
            else:
                for a in value["shares"][0]["acls"]:
                    a["state"] = "Disabled"
            with self.subTest(field=field):
                self.calls.clear()
                self.execute(value)
                self.assertEqual([], self.calls)

    def test_wrong_realm_or_unjoined_fails_before_service_change(self):
        for kind in ("realm", "join"):
            value = payload()
            value["identityDomain"]["domainName" if kind == "realm" else "joinState"] = "other"
            with self.assertRaises(policy.PolicyError) as raised:
                self.execute(value)
            self.assertEqual("SMB_AD_RESOLVER_CONFIG_MISMATCH", raised.exception.code)
        self.assertFalse(any(c[0] == "systemctl" for c in self.calls))

    def test_symlink_or_writable_config_rejected(self):
        for mode in (stat.S_IFLNK | 0o777, stat.S_IFREG | 0o666):
            self.info.st_mode = mode
            self.assert_code("SMB_AD_RESOLVER_CONFIG_UNSAFE")
        self.assertEqual([], self.calls)

    def test_start_failure_does_not_reach_nss(self):
        self.failure = lambda c: c[:2] == ["systemctl", "start"]
        self.assert_code("SMB_AD_RESOLVER_UNAVAILABLE")
        self.assertFalse(any(c[0] == "getent" for c in self.calls))

    def test_trust_failure_does_not_reach_nss(self):
        self.failure = lambda c: c[0] == "wbinfo"
        self.assert_code("SMB_AD_TRUST_UNAVAILABLE")
        self.assertFalse(any(c[0] == "getent" for c in self.calls))

    def test_nss_waits_for_readiness_then_uses_existing_exact_identity(self):
        attempts = {}
        original = self.command
        def delayed(args, **kwargs):
            result = original(args, **kwargs)
            if args[0] == "getent":
                attempts[args[-1]] = attempts.get(args[-1], 0) + 1
                if attempts[args[-1]] == 1:
                    result.returncode = 2
                    result.stdout = ""
            return result
        with patch.object(Path, "lstat", return_value=self.info), \
                patch.object(policy.subprocess, "run", side_effect=delayed), \
                patch.object(policy.time, "sleep"):
            policy.prepare_smb_ad_resolution(payload(), "STORTEST")
        self.assertEqual([2, 2], sorted(attempts.values()))

    def test_unknown_principal_fails_closed_with_existing_code(self):
        self.failure = lambda c: c[0] == "getent"
        with patch.object(policy.time, "monotonic", side_effect=[0, 0, 0, 9]):
            self.assert_code("DIRECTORY_PRINCIPAL_UNAVAILABLE")

    def test_timeout_has_safe_error(self):
        with patch.object(Path, "lstat", return_value=self.info), \
                patch.object(policy.subprocess, "run", side_effect=subprocess.TimeoutExpired("testparm", 10)):
            with self.assertRaises(policy.PolicyError) as raised:
                policy.prepare_smb_ad_resolution(payload(), "STORTEST")
        self.assertEqual("SMB_AD_RESOLVER_CONFIG_MISMATCH", raised.exception.code)

    def test_actual_cli_calls_guard_before_acl_and_mutable_share_loop(self):
        source = (ROOT / "debian/usr/local/bin/ablestack-storagectl").read_text()
        body = source.split("apply_smb_shares() {", 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        tree = ast.parse(body)
        guard = next(i for i, n in enumerate(tree.body) if isinstance(n, ast.Expr)
                     and isinstance(n.value, ast.Call) and isinstance(n.value.func, ast.Attribute)
                     and n.value.func.attr == "prepare_smb_ad_resolution")
        mutation = next(i for i, n in enumerate(tree.body) if isinstance(n, ast.For)
                        and isinstance(n.target, ast.Name) and n.target.id == "share")
        acl = next(i for i, n in enumerate(tree.body) if isinstance(n, ast.Expr)
                   and isinstance(n.value, ast.Call) and isinstance(n.value.func, ast.Attribute)
                   and n.value.func.attr == "apply_acl_batch")
        self.assertLess(guard, mutation)
        self.assertLess(mutation, acl)
        code = compile(ast.fix_missing_locations(ast.Module(body=tree.body[:guard + 1],
                       type_ignores=[])), "<actual-smb-entrypoint>", "exec")
        sentinel = "/__cold_ad_payload__"
        real_open = builtins.open
        def open_payload(path, mode="r", *args, **kwargs):
            if str(path) == sentinel:
                return io.StringIO(json.dumps(payload()))
            return real_open(path, mode, *args, **kwargs)
        for failure in (False, True):
            namespace = {}
            with patch.object(sys, "argv", ["contract", sentinel]), \
                    patch.dict(os.environ, {"ABLESTACK_STORAGE_HELPER_ROOT": str(HELPER)}), \
                    patch.object(builtins, "open", side_effect=open_payload), \
                    patch.object(os, "makedirs"), \
                    patch.object(policy, "prepare_smb_ad_resolution",
                                 side_effect=policy.PolicyError("SMB_AD_RESOLVER_UNAVAILABLE") if failure else None) as prepare:
                if failure:
                    with self.assertRaises(policy.PolicyError):
                        exec(code, namespace)
                else:
                    exec(code, namespace)
                prepare.assert_called_once_with(payload(), "STORTEST")


if __name__ == "__main__":
    unittest.main()
