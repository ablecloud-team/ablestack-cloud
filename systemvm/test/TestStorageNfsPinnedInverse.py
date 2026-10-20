import contextlib
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


from pathlib import Path
import ast
import hashlib
import importlib.util
import json
import os
import re
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
LIB = ROOT / "systemvm/debian/usr/local/lib/ablestack-storage"
spec = importlib.util.spec_from_file_location("pinned_policy", LIB / "ganesha_dbus.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
CLI = ROOT / "systemvm/debian/usr/local/bin/ablestack-storagectl"
REAL_PATH = type(Path())


def manifest(scope, files, previous=None):
    value = {"scope": scope, "files": {key: hashlib.sha256(raw).hexdigest() for key, raw in files.items()},
             "previousRenderedSha256": previous}
    value["manifestSha256"] = hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return value


class StorageNfsPinnedInverseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.store = self.root / "rendered"
        self.generations = self.store / "generations"
        self.previous = self.generations / "11111111-1111-4111-8111-111111111111"
        self.target = self.generations / "22222222-2222-4222-8222-222222222222"
        self.configs = self.root / "configs"
        for directory in (self.store, self.generations, self.previous, self.target, self.configs):
            directory.mkdir(mode=0o700, exist_ok=True)
        self.scope = {"instanceUuid": "33333333-3333-4333-8333-333333333333",
                      "operationUuid": self.target.name, "revision": 2}
        self.relative = "nfs/ganesha/" + "a" * 24 + ".conf"
        self.configuration = "NFS_Core_Param {\n NFS_Port = 2049;\n Bind_addr = 10.10.13.227;\n}\n"
        self.nfs = {"endpoints": [{"legacyUnitKey": "10.10.13.227_2049", "configurationPath": self.relative}]}
        self.old_manifest = manifest({**self.scope, "operationUuid": self.previous.name, "revision": 1}, {})
        self.new_manifest = manifest(self.scope, {self.relative: self.configuration.encode(),
            "nfs/manifest.json": json.dumps(self.nfs).encode()}, self.old_manifest["manifestSha256"])
        self.write(self.previous / "generation.json", self.old_manifest)
        self.write(self.target / "generation.json", self.new_manifest)
        self.write(self.target / "nfs/manifest.json", self.nfs)
        self.write(self.target / self.relative, self.configuration)
        self.journal = {"scope": self.scope, "phase": "ROLLING_BACK",
                        "previousOperationUuid": self.previous.name,
                        "previousSha256": self.old_manifest["manifestSha256"],
                        "targetSha256": self.new_manifest["manifestSha256"]}
        self.write(self.store / "activation.json", self.journal)
        (self.store / "current").symlink_to("generations/" + self.previous.name)
        self.named = self.configs / "10.10.13.227_2049.conf"
        self.named.symlink_to(self.store / "current" / self.relative)
        self.policy = module.GaneshaDbusPolicy(config_root=self.configs, rendered_source=self.target)

    def write(self, path, value):
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        path.write_text(value if isinstance(value, str) else json.dumps(value))
        path.chmod(0o600)

    def mapped(self, value):
        text = os.fspath(value)
        prefix = "/var/lib/ablestack-storage/rendered-generations"
        return REAL_PATH(str(self.store) + text[len(prefix):] if text.startswith(prefix) else text)

    def source_names(self):
        with patch("pathlib.Path", self.mapped):
            return self.policy.source_names()

    def test_actual_dangling_current_link_uses_only_pinned_target_during_inverse(self):
        self.assertTrue(self.named.is_symlink())
        self.assertFalse(self.named.exists())
        names, observed = self.source_names()
        self.assertEqual(1, len(names))
        self.assertEqual(self.target / self.relative, observed[0][2])
        self.assertFalse(self.named.exists())

    def test_default_policy_never_adopts_a_dangling_link(self):
        self.policy.rendered_source = None
        with self.assertRaises(FileNotFoundError):
            self.source_names()

    def test_foreign_phase_scope_source_manifest_or_configuration_are_rejected(self):
        for key, value in [("phase", "ACTIVATING"), ("targetSha256", "0" * 64),
                           ("scope", {**self.scope, "operationUuid": self.previous.name}),
                           ("previousSha256", "0" * 64)]:
            with self.subTest(field=key):
                self.write(self.store / "activation.json", {**self.journal, key: value})
                with self.assertRaises(ValueError):
                    self.source_names()
        self.write(self.store / "activation.json", self.journal)
        self.write(self.target / self.relative, self.configuration + "# tampered\n")
        with self.assertRaises(ValueError):
            self.source_names()

    def test_foreign_current_or_configuration_link_is_rejected(self):
        (self.store / "current").unlink()
        (self.store / "current").symlink_to("generations/" + self.target.name)
        with self.assertRaises(ValueError):
            self.source_names()
        (self.store / "current").unlink()
        (self.store / "current").symlink_to("generations/" + self.previous.name)
        self.named.unlink()
        self.named.symlink_to(self.target / self.relative)
        with self.assertRaises(ValueError):
            self.source_names()


class StorageNfsAliasInverseTest(unittest.TestCase):
    def setUp(self):
        tree = ast.parse(CLI.read_text().split("apply_nfs_exports() {", 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0])
        self.tree = tree
        definitions = [node for node in tree.body if isinstance(node, ast.FunctionDef)
                       and node.name == "cleanup_stale_export_aliases"]
        self.error = type("NfsReadinessError", (RuntimeError,), {})
        self.events = []
        self.mounts = {"/export/owned"}
        identity = types.SimpleNamespace(st_dev=100, st_ino=200)
        self.namespace = {"alias_state": {"owned": {"aliasPath": "/export/owned", "backingPath": "/backing"}},
                          "next_alias_state": {}, "rendered_from_context": None,
                          "NfsReadinessError": self.error, "is_mountpoint": lambda path: path in self.mounts,
                          "run": self.unmount, "update_fstab": lambda tag: self.events.append("fstab"),
                          "save_alias_state": lambda value: self.events.append(("save", value)),
                          "os": types.SimpleNamespace(stat=lambda path: identity, path=types.SimpleNamespace(islink=lambda path: False))}
        exec(compile(ast.Module(body=definitions, type_ignores=[]), str(CLI), "exec"), self.namespace)

    def unmount(self, command):
        self.events.append("umount")
        self.mounts.remove(command[1])
        return types.SimpleNamespace(returncode=0)

    def test_actual_cli_unmount_success_is_proved_before_clearing_ownership(self):
        self.namespace["cleanup_stale_export_aliases"]()
        self.assertEqual(["umount", "fstab", ("save", {})], self.events)

    def test_actual_cli_failed_or_unverified_unmount_retains_receipts(self):
        for code in [1, 0]:
            with self.subTest(returncode=code):
                self.events.clear()
                self.namespace["run"] = lambda command: types.SimpleNamespace(returncode=code)
                with self.assertRaisesRegex(self.error, "NFS_ALIAS_UNMOUNT_FAILED" if code else "NFS_ALIAS_UNMOUNT_UNVERIFIED"):
                    self.namespace["cleanup_stale_export_aliases"]()
                self.assertEqual([], self.events)

    def test_actual_cli_foreign_bind_is_never_unmounted(self):
        self.namespace["os"].stat = lambda path: types.SimpleNamespace(st_dev=100, st_ino=200 if path == "/backing" else 201)
        with self.assertRaises(ValueError):
            self.namespace["cleanup_stale_export_aliases"]()
        self.assertEqual([], self.events)

    def test_actual_cli_owned_absence_retry_requires_inactive_unit_and_pinned_source(self):
        definition=next(node for node in self.tree.body if isinstance(node,ast.FunctionDef)
                        and node.name=="stop_owned_rendered_ganesha")
        with tempfile.TemporaryDirectory() as directory:
            source=Path(directory)/"source"
            (source/"nfs").mkdir(parents=True)
            (source/"nfs/manifest.json").write_text(json.dumps({"endpoints":[
                {"legacyUnitKey":"owned","configurationPath":"nfs/ganesha/"+("a"*24)+".conf"}]}))
            config=str(Path(directory)/"owned.conf")
            def commands(state,pid="0"):
                calls=[]
                def run(args,**kwargs):
                    calls.append(args)
                    return types.SimpleNamespace(stdout=pid if "MainPID"in args else state,returncode=0)
                return calls,run
            calls,run=commands("inactive")
            ns={"re":re,"Path":Path,"json":json,"os":os,"rendered_from_context":(source,{}),
                "ganesha_remaining":lambda maximum:maximum,
                "subprocess":types.SimpleNamespace(run=run)}
            exec(compile(ast.Module(body=[definition],type_ignores=[]),str(CLI),"exec"),ns)
            ns["stop_owned_rendered_ganesha"]("owned",config,remove_configuration=True)
            self.assertTrue(all(call[1]=="show" for call in calls))
            for state,pid in [("activating","0"),("inactive","5")]:
                calls,run=commands(state,pid)
                ns["subprocess"]=types.SimpleNamespace(run=run)
                with self.assertRaises(ValueError):
                    ns["stop_owned_rendered_ganesha"]("owned",config,remove_configuration=True)
                self.assertTrue(all(call[1]=="show" for call in calls))
            calls,run=commands("inactive")
            ns["subprocess"]=types.SimpleNamespace(run=run)
            with self.assertRaises(ValueError):
                ns["stop_owned_rendered_ganesha"]("foreign",config,remove_configuration=True)
            Path(config).write_text("foreign regular file")
            with self.assertRaises(ValueError):
                ns["stop_owned_rendered_ganesha"]("owned",config,remove_configuration=True)

    def test_actual_cli_orders_daemon_inverse_before_alias_cleanup(self):
        attempt = next(node for node in self.tree.body if isinstance(node, ast.Try)
                       and any(isinstance(item, ast.Call) and isinstance(item.func, ast.Name)
                               and item.func.id == "start_ganesha_endpoints" for item in ast.walk(node)))
        calls = [item.func.id for statement in attempt.body for item in ast.walk(statement)
                 if isinstance(item, ast.Call) and isinstance(item.func, ast.Name)]
        self.assertLess(calls.index("start_ganesha_endpoints"), calls.index("cleanup_stale_export_aliases"))
        self.assertLess(calls.index("cleanup_stale_export_aliases"), calls.index("probe_nfs_export_visibility"))


if __name__ == "__main__":
    unittest.main()
