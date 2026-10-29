# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""The full native entrypoint regression lives next to this bounded binding contract."""
import ast
import builtins
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "debian/usr/local/lib/ablestack-storage"
sys.path.insert(0, str(HELPER))
import shared_directory_policy as policy


class SmbEntrypointBinding(unittest.TestCase):
    def test_nonempty_payload_keeps_the_real_directory_policy_instance(self):
        source = (ROOT / "debian/usr/local/bin/ablestack-storagectl").read_text()
        body = source.split("apply_smb_shares() {", 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        tree = ast.parse(body)
        stop = next(i for i, node in enumerate(tree.body)
                    if isinstance(node, ast.For) and isinstance(node.target, ast.Name)
                    and node.target.id == "candidate_share")
        # Run every original import/definition/binding up to the new guard. Do not
        # substitute a module for directory_policies; that hid the original bug.
        code = compile(ast.fix_missing_locations(ast.Module(body=tree.body[:stop + 1],
                       type_ignores=[])), "<actual-smb-entrypoint-prefix>", "exec")
        for flag in (None, False, True):
            with self.subTest(flag=flag):
                config = {"directoryPolicyUuid": "registered", "backingPath": "/managed/team"}
                if flag is not None:
                    config["inheritOwner"] = flag
                payload = {"instanceUuid": "00000000-0000-0000-0000-000000000001",
                           "shares": [{"state": "Ready", "config": config}]}
                sentinel = "/__binding_payload__"
                real_open = builtins.open
                def read_payload(path, mode="r", *args, **kwargs):
                    if str(path) == sentinel:
                        return io.StringIO(json.dumps(payload))
                    if any(token in mode for token in "wax+"):
                        raise AssertionError("Binding regression must not write")
                    return real_open(path, mode, *args, **kwargs)
                namespace = {}
                with patch.object(sys, "argv", ["contract", sentinel]), \
                        patch.dict(os.environ, {"ABLESTACK_STORAGE_HELPER_ROOT": str(HELPER)}), \
                        patch.object(builtins, "open", side_effect=read_payload), \
                        patch.object(policy, "inheritance_protection_version", return_value=1), \
                        patch.object(policy.DirectoryPolicies, "policy_for", return_value={"uuid": "registered", "mode": "0775"}):
                    exec(code, namespace)
                self.assertIsInstance(namespace["directory_policies"], policy.DirectoryPolicies)
                self.assertFalse(hasattr(namespace["directory_policies"], "validate_inheritance_share"))


if __name__ == "__main__":
    unittest.main()
