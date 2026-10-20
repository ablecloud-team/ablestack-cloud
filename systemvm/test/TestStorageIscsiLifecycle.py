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
"""Exercise the signed apply function around direct LIO configuration."""
import ast
import contextlib
import copy
import fcntl
import hashlib
import importlib.util
import io
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
import uuid
from unittest import mock

CLI=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"


class StorageIscsiLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.bin=self.root/"bin";self.bin.mkdir()
        self.foreign=self.root/"foreign-target";self.foreign.write_text("FOREIGN_CONFIGFS")
        self.listener=self.root/"owned-listener";self.calls=self.root/"service-calls"
        source=CLI.read_text();begin=source.index("apply_iscsi_targets() {")
        self.function=source[begin:source.index("apply_nvmeof_subsystems() {",begin)]
        # This stub deliberately models the installed Debian oneshot unit:
        # start/enable --now restores a global saved config; restart clears LIO.
        self.executable("systemctl",f"""#!{sys.executable}
import os,sys
from pathlib import Path
root=Path(os.environ["LIFECYCLE_ROOT"])
with (root/"service-calls").open("a") as stream:stream.write(" ".join(sys.argv[1:])+"\\n")
(root/"owned-listener").unlink(missing_ok=True)
(root/"foreign-target").write_text("GLOBAL_RESTORED")
""")
        # Execute the exact signed Python readiness block after a simulated
        # successful targetcli portal creation. Other Python apply effects are
        # covered by the configfs/auth tests and the real kernel acceptance.
        self.executable("python3",f"""#!{sys.executable}
import ast,os,subprocess,sys
from pathlib import Path
from types import SimpleNamespace
source=sys.stdin.read();tree=ast.parse(source)
root=Path(os.environ["LIFECYCLE_ROOT"])
listener=root/"owned-listener"
if os.environ.get("LIFECYCLE_LISTENER_MISSING")!="1":listener.write_text("LIVE_PORTAL")
namespace={{"os":os,"enabled":True,"applied":1,"required_ports":{{3260}},
           "command_exists":lambda name:True,
           "run":lambda args,timeout=20:subprocess.run(args,capture_output=True,text=True,timeout=timeout),
           "best_effort_firewall":lambda port:None,
           "port_listening":lambda port:listener.exists(),
           "time":SimpleNamespace(sleep=lambda seconds:None)}}
names={{"service_action","iscsi_guard","emit_iscsi_failure","verify_iscsi_readiness"}}
constants={{"first_iscsi_failure","ISCSI_GUARD_STAGES","ISCSI_GUARD_CATEGORIES"}}
helpers=[item for item in tree.body if isinstance(item,ast.FunctionDef) and item.name in names
         or isinstance(item,ast.Assign) and any(isinstance(x,ast.Name) and x.id in constants for x in item.targets)]
namespace.update({{"subprocess":subprocess,"json":__import__("json"),"IscsiTargetcliFailure":type("IscsiTargetcliFailure",(SystemExit,),{{}})}})
exec(compile(ast.Module(body=helpers,type_ignores=[]),"<signed-iscsi-readiness>","exec"),namespace)
namespace["iscsi_guard"]("READINESS",namespace["verify_iscsi_readiness"])
""")
        self.env={**os.environ,"PATH":str(self.bin)+":"+os.environ["PATH"],"LIFECYCLE_ROOT":str(self.root)}
        self.env.pop("ABLESTACK_STORAGE_RENDERED_REPLAY",None)

    def executable(self,name,body):
        path=self.bin/name;path.write_text(textwrap.dedent(body));path.chmod(0o755)

    def apply(self,replay=False):
        env=dict(self.env)
        if replay:env["ABLESTACK_STORAGE_RENDERED_REPLAY"]="1"
        return subprocess.run(["bash","-e","-c",self.function+'\napply_iscsi_targets /unused-payload\n'],
                              env=env,capture_output=True,text=True,timeout=5)

    def test_normal_apply_keeps_new_live_portal_and_foreign_configfs_without_global_service_effects(self):
        result=self.apply()
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual("LIVE_PORTAL",self.listener.read_text())
        self.assertEqual("FOREIGN_CONFIGFS",self.foreign.read_text())
        self.assertFalse(self.calls.exists())

    def test_rendered_replay_uses_the_same_live_portal_readback(self):
        result=self.apply(replay=True)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual("LIVE_PORTAL",self.listener.read_text())
        self.assertEqual("FOREIGN_CONFIGFS",self.foreign.read_text())
        self.assertFalse(self.calls.exists())

    def test_missing_actual_listener_fails_without_start_restore_or_restart_clear(self):
        self.env["LIFECYCLE_LISTENER_MISSING"]="1"
        result=self.apply()
        self.assertNotEqual(0,result.returncode)
        self.assertEqual("READINESS", json.loads(result.stdout)["stage"])
        self.assertEqual("SYSTEM_EXIT", json.loads(result.stdout)["category"])
        self.assertNotIn("3260", result.stderr)
        self.assertEqual("FOREIGN_CONFIGFS",self.foreign.read_text())
        self.assertFalse(self.calls.exists())

    def diagnostic_namespace(self):
        begin = self.function.index("<<'PY'\n") + len("<<'PY'\n")
        source = self.function[begin:self.function.rindex("\nPY")]
        names = {"run", "run_targetcli", "ok_or_exists", "require_targetcli",
                 "targetcli_error_category", "rollback_created", "emit_iscsi_failure", "iscsi_guard", "load_iscsi_payload"}
        constants = {"ISCSI_DIAGNOSTIC_STAGES", "ISCSI_DIAGNOSTIC_CATEGORIES", "first_iscsi_failure", "ISCSI_GUARD_STAGES", "ISCSI_GUARD_CATEGORIES"}
        nodes = []
        for node in ast.parse(source).body:
            if isinstance(node, ast.FunctionDef) and node.name in names:
                nodes.append(node)
            elif isinstance(node, ast.ClassDef) and node.name == "IscsiTargetcliFailure":
                nodes.append(node)
            elif isinstance(node, ast.Assign) and any(isinstance(item, ast.Name) and item.id in constants for item in node.targets):
                nodes.append(node)
        space = {"subprocess": subprocess, "json": json, "re": re}
        exec(compile(ast.Module(body=nodes, type_ignores=[]), "<actual-signed-iscsi-diagnostics>", "exec"), space)
        return space

    def test_default_subprocess_failure_and_cleanup_spawn_failure_preserve_first_public_diagnostic(self):
        self.executable("targetcli", f"""#!{sys.executable}
import sys
print("SYNTHETIC_PRIVATE_CREDENTIAL in stdout")
print("TypeError: SYNTHETIC_PRIVATE_CREDENTIAL in stderr", file=sys.stderr)
sys.exit(1)
""")
        previous = os.environ.get("PATH")
        os.environ["PATH"] = self.env["PATH"]
        self.addCleanup(lambda: os.environ.__setitem__("PATH", previous))
        space = self.diagnostic_namespace()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            with self.assertRaises(space["IscsiTargetcliFailure"]) as failed:
                space["require_targetcli"]("/iscsi", "create", "iqn.public", stage="TARGET_CREATE")
            self.assertNotIn("SYNTHETIC_PRIVATE_CREDENTIAL", str(failed.exception))
            (self.bin / "targetcli").unlink()
            with self.assertRaises(space["IscsiTargetcliFailure"]):
                space["rollback_created"](["iqn.public"], [])
        self.assertEqual(1, len(output.getvalue().splitlines()))
        value = json.loads(output.getvalue())
        self.assertEqual({"success": False, "kind": "ISCSI_TARGETCLI_COMMAND_FAILED",
                          "stage": "TARGET_CREATE", "returnCode": 1, "category": "TYPE_ERROR"}, value)
        self.assertNotIn("SYNTHETIC_PRIVATE_CREDENTIAL", output.getvalue())

    def test_best_effort_nonzero_cleanup_stays_best_effort_without_failure_publication(self):
        self.executable("targetcli", f"""#!{sys.executable}
import sys
print("SYNTHETIC_PRIVATE_CREDENTIAL", file=sys.stderr)
sys.exit(1)
""")
        previous = os.environ.get("PATH")
        os.environ["PATH"] = self.env["PATH"]
        self.addCleanup(lambda: os.environ.__setitem__("PATH", previous))
        space = self.diagnostic_namespace()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            space["rollback_created"](["iqn.public"], ["ablestack-public"])
        self.assertEqual("", output.getvalue())
        self.assertIsNone(space["first_iscsi_failure"])

    def test_ambiguous_private_error_buffer_is_unclassified_and_timeout_has_no_raw_command(self):
        space = self.diagnostic_namespace()
        result = subprocess.CompletedProcess(["private-command"], 1,
                                             "TypeError: SYNTHETIC_PRIVATE_CREDENTIAL",
                                             "PermissionError: SYNTHETIC_PRIVATE_CREDENTIAL")
        self.assertEqual("UNCLASSIFIED", space["targetcli_error_category"](result))
        for category in ("TIMEOUT", "SPAWN_FAILURE"):
            with self.assertRaises(ValueError):
                space["IscsiTargetcliFailure"]("TARGET_CREATE", 1, category)
        def timed_out(args, timeout=20):
            raise subprocess.TimeoutExpired(["SYNTHETIC_PRIVATE_CREDENTIAL"], timeout,
                                            output="SYNTHETIC_PRIVATE_CREDENTIAL")
        space["run"] = timed_out
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            with self.assertRaises(space["IscsiTargetcliFailure"]) as failed:
                space["run_targetcli"]("/iscsi", "create", "iqn.public", stage="TARGET_CREATE")
        self.assertNotIn("SYNTHETIC_PRIVATE_CREDENTIAL", str(failed.exception))
        self.assertEqual({"success": False, "kind": "ISCSI_TARGETCLI_COMMAND_FAILED",
                          "stage": "TARGET_CREATE", "returnCode": None, "category": "TIMEOUT"},
                         json.loads(output.getvalue()))



@contextlib.contextmanager
def isolated_iscsi_writer(root):
    lock=root/"writer.lock";fd=os.open(lock,os.O_RDWR|os.O_CREAT,0o600)
    try:saved=os.dup(9)
    except OSError:saved=None
    if fd!=9:os.dup2(fd,9);os.close(fd)
    fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
    try:
        with mock.patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":"9",
                "ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(lock)}):
            yield
    finally:
        if saved is None:os.close(9)
        else:os.dup2(saved,9);os.close(saved)


class IscsiGuardEntryFixture:
    """Run the actual apply heredoc with disposable files/external adapters."""
    SECRET = "SYNTHETIC_PRIVATE_GUARD_TOKEN"

    def run(self, scenario):
        from unittest import mock
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary);state = root / "state";state.mkdir(mode=0o700)
            raw = root / "raw";raw.write_bytes(b"\0" * 65536)
            payload = {"instanceUuid":"44444444-4444-4444-8444-444444444444","enabled": True, "listeners": [{"listenIp": "127.0.0.1", "port": 3260}],
                       "targets": [{"uuid": "11111111-1111-4111-8111-111111111111", "targetName": "iqn.2026-05.local.storage:fixture",
                                    "volumeName": "abcdef22222242228222", "volumeUuid": "abcdef22-2222-4222-8222-222222222222",
                                    "lunOrNamespace": "0", "volumeSizeBytes": 65536, "acls": []}]}
            if scenario == "disabled-null":
                payload["enabled"]=False;payload["targets"][0]["state"]="Disabled";payload["targets"][0]["acls"]=None
            if scenario == "auth":payload["targets"][0]["acls"]=[{"principal":"iqn.2026-05.example.client:fixture","permission":"READ_WRITE","config":{},"state":"Ready"}]
            if scenario in ("target-iqn-invalid","initiator-iqn-invalid","ownership-invalid","ownership-mismatch","auth-value-invalid"):
                payload["targets"][0]["acls"]=[{"principal":"iqn.2026-05.example.client:fixture","permission":"READ_WRITE","config":{}}]
                if scenario=="target-iqn-invalid":payload["targets"][0]["targetName"]="malformed"
                elif scenario=="initiator-iqn-invalid":payload["targets"][0]["acls"][0]["principal"]="../foreign"
                elif scenario=="ownership-invalid":payload["targets"][0]["volumeUuid"]="invalid"
                elif scenario=="auth-value-invalid":payload["targets"][0]["acls"][0]["config"]["chapEnabled"]="true"
                else:
                    other=copy.deepcopy(payload["targets"][0])
                    other["uuid"]="55555555-5555-4555-8555-555555555555";other["lunOrNamespace"]="1"
                    other["acls"][0]["config"]={"chapEnabled":True,"chapUsername":"fixture-user"}
                    payload["targets"].append(other)
            path = root / "payload.json";path.write_text(json.dumps(payload))
            if scenario == "early-input": path.write_text('{"' + self.SECRET)
            if scenario == "early-vault":
                secret_dir=state / "secrets";secret_dir.mkdir(mode=0o700)
                vault=secret_dir / "iscsi-acl-secrets.json";vault.write_text(self.SECRET);vault.chmod(0o644)
            source=CLI.read_text();start=source.index("apply_iscsi_targets() {")
            function=source[start:source.index("apply_nvmeof_subsystems() {",start)]
            code=function[function.index("<<'PY'\n")+len("<<'PY'\n"):function.rindex("\nPY")]
            tree=ast.parse(code)
            class FixturePaths(ast.NodeTransformer):
                def visit_Constant(self,node):
                    if isinstance(node.value,str):
                        if node.value=="/etc/ablestack-storage":return ast.copy_location(ast.Constant(str(state)),node)
                        if node.value=="/dev/":return ast.copy_location(ast.Constant(str(root)+"/"),node)
                        if node.value.startswith("/sys/kernel/config/target/"):
                            return ast.copy_location(ast.Constant(str(root / "configfs" / node.value.removeprefix("/sys/kernel/config/target/"))),node)
                    return node
            tree=FixturePaths().visit(tree);ast.fix_missing_locations(tree)
            calls=[];failed=[False]
            def run(command,**kwargs):
                calls.append(list(command))
                if command[0]=="targetcli":
                    if command[1]=="/backstores/block" and command[2]=="create":
                        obj=root/"configfs/core/iblock_0"/command[3];(obj/"wwn").mkdir(parents=True,exist_ok=True)
                        serial=next((x.split("=",1)[1] for x in command if x.startswith("wwn=")),"33333333-3333-4333-8333-333333333333")
                        (obj/"wwn/vpd_unit_serial").write_text("T10 VPD Unit Serial Number: "+serial+"\n")
                        (obj/"udev_path").write_text(str(raw))
                    if scenario=="targetcli-first" and "luns" in command[1] and "create" in command:
                        failed[0]=True
                        return subprocess.CompletedProcess(command,1,self.SECRET,"TypeError: "+self.SECRET)
                    if scenario in ("mid-device-cleanup","targetcli-first","unknown-mid") and failed[0] and "delete" in command:
                        raise subprocess.TimeoutExpired(command,20,output=self.SECRET,stderr=self.SECRET)
                    return subprocess.CompletedProcess(command,0,"","")
                if command[0]=="python3":
                    return subprocess.CompletedProcess(command,1 if scenario=="dependency" else 0,"",self.SECRET if scenario=="dependency" else "")
                if command[0]=="lsblk":
                    if scenario=="unknown-mid":failed[0]=True;raise Exception(self.SECRET)
                    if "-J" in command:
                        mounted="/"+self.SECRET if scenario in ("mid-device","mid-device-cleanup") else None
                        if mounted:failed[0]=True
                        data={"blockdevices":[{"name":"fixture","path":str(raw),"type":"disk","serial":"abcdef22222242228222","size":65536,"mountpoint":mounted,"children":[]}]}
                        return subprocess.CompletedProcess(command,0,json.dumps(data),"")
                    return subprocess.CompletedProcess(command,0,"65536\n","")
                if command[0]=="findmnt":return subprocess.CompletedProcess(command,1,"","")
                if command[0]=="ss":
                    return subprocess.CompletedProcess(command,0,"" if scenario=="readiness" else "LISTEN 0 1 127.0.0.1:3260 *:*\n","")
                return subprocess.CompletedProcess(command,0,"","")
            # Only external inspection adapters are replaced; actual guarded
            # calls, vault/device/readiness functions and rollback body execute.
            class FixtureAdapters(ast.NodeTransformer):
                def visit_FunctionDef(self,node):
                    if node.name in ("command_exists","has_elf_header","has_text_prefix"):
                        if scenario in ("lookup", "attribute", "assertion", "unknown") and node.name=="has_elf_header":
                            exception={"lookup":"KeyError","attribute":"AttributeError","assertion":"AssertionError","unknown":"Exception"}[scenario]
                            node.body=[ast.Raise(exc=ast.Call(func=ast.Name(id=exception,ctx=ast.Load()),args=[ast.Constant(IscsiGuardEntryFixture.SECRET)],keywords=[]),cause=None)]
                        else:node.body=[ast.Return(value=ast.Constant(True))]
                    return self.generic_visit(node)
            tree=FixtureAdapters().visit(tree);ast.fix_missing_locations(tree)
            output=io.StringIO();error=None
            with isolated_iscsi_writer(root),mock.patch.object(sys,"argv",["fixture",str(path)]),mock.patch.object(subprocess,"run",side_effect=run),mock.patch("time.sleep",lambda seconds:None),contextlib.redirect_stdout(output):
                try:exec(compile(tree,"<actual-iscsi-guard-entry>","exec"),{})
                except BaseException as failure:error=failure
            return {"stdout":output.getvalue(),"failure":error,"calls":calls}


class StorageIscsiGuardDiagnosticTest(unittest.TestCase):
    def test_early_and_mid_and_late_actual_call_sites_produce_one_safe_hint(self):
        for scenario,stage,category in (("target-iqn-invalid","AUTH","TARGET_IQN_INVALID"),("initiator-iqn-invalid","AUTH","INITIATOR_IQN_INVALID"),("ownership-invalid","AUTH","OWNERSHIP_INVALID"),("ownership-mismatch","AUTH","OWNERSHIP_MISMATCH"),("auth-value-invalid","AUTH","AUTH_VALUE_INVALID"),("early-input","INPUT","VALUE_ERROR"),("early-vault","VAULT_STATE","VALUE_ERROR"),
                ("dependency","DEPENDENCY","SYSTEM_EXIT"),("mid-device","DEVICE","SYSTEM_EXIT"),("readiness","READINESS","SYSTEM_EXIT"),("auth","AUTH","VALUE_ERROR"),("lookup","DEPENDENCY","LOOKUP_ERROR"),("attribute","DEPENDENCY","ATTRIBUTE_ERROR"),("assertion","DEPENDENCY","ASSERTION_ERROR")):
            with self.subTest(scenario=scenario):
                result=IscsiGuardEntryFixture().run(scenario)
                self.assertIsInstance(result["failure"],SystemExit)
                records=result["stdout"].splitlines();self.assertEqual(1,len(records))
                value=json.loads(records[0])
                self.assertEqual({"success":False,"kind":"ISCSI_APPLY_GUARD_FAILED","stage":stage,"returnCode":None,"category":category},value)
                self.assertNotIn(IscsiGuardEntryFixture.SECRET,result["stdout"])
                self.assertNotIn(IscsiGuardEntryFixture.SECRET,str(result["failure"]))
                targets=[c for c in result["calls"] if c[0]=="targetcli"]
                if scenario.startswith("early"):self.assertEqual([],targets)
                if scenario=="mid-device":self.assertTrue(any("create" in c and c[1]=="/iscsi" for c in targets))

    def test_guard_first_hint_survives_actual_cleanup_timeout(self):
        result=IscsiGuardEntryFixture().run("mid-device-cleanup")
        self.assertIsInstance(result["failure"],SystemExit)
        self.assertEqual(1,len(result["stdout"].splitlines()))
        self.assertEqual("DEVICE",json.loads(result["stdout"])["stage"])

    def test_targetcli_first_hint_is_not_reclassified_or_overwritten(self):
        result=IscsiGuardEntryFixture().run("targetcli-first")
        self.assertIsInstance(result["failure"],SystemExit)
        self.assertEqual(1,len(result["stdout"].splitlines()))
        self.assertEqual({"success":False,"kind":"ISCSI_TARGETCLI_COMMAND_FAILED","stage":"LUN_CREATE","returnCode":1,"category":"TYPE_ERROR"},json.loads(result["stdout"]))

    def test_unknown_exception_redacts_without_fabricating_phase(self):
        for scenario in ("unknown", "unknown-mid"):
            result=IscsiGuardEntryFixture().run(scenario)
            self.assertIsInstance(result["failure"],SystemExit)
            self.assertEqual("",result["stdout"])
            self.assertNotIn(IscsiGuardEntryFixture.SECRET,str(result["failure"]))

    def test_disabled_target_null_acls_retains_baseline_success(self):
        result=IscsiGuardEntryFixture().run("disabled-null")
        self.assertIsNone(result["failure"])
        self.assertIs(json.loads(result["stdout"])["success"],True)

    def test_valid_actual_entry_remains_successful(self):
        result=IscsiGuardEntryFixture().run("positive")
        self.assertIsNone(result["failure"])
        self.assertIs(json.loads(result["stdout"])["success"],True)

    def test_listener_and_guard_category_closure_with_actual_guard(self):
        fixture=StorageIscsiLifecycleTest();fixture.setUp()
        try:
            space=fixture.diagnostic_namespace()
            for exception,category in ((ValueError("x"),"VALUE_ERROR"),(TypeError("x"),"TYPE_ERROR"),(RuntimeError("x"),"RUNTIME_ERROR"),(OSError("x"),"OS_ERROR"),(subprocess.TimeoutExpired("x",1),"TIMEOUT"),(SystemExit("x"),"SYSTEM_EXIT"),(KeyError("x"),"LOOKUP_ERROR"),(IndexError("x"),"LOOKUP_ERROR"),(AttributeError("x"),"ATTRIBUTE_ERROR"),(AssertionError("x"),"ASSERTION_ERROR")):
                space["first_iscsi_failure"]=None;output=io.StringIO()
                def failed():raise exception
                with contextlib.redirect_stdout(output),self.assertRaises(SystemExit):space["iscsi_guard"]("LISTENER",failed)
                self.assertEqual(category,json.loads(output.getvalue())["category"])
            output=io.StringIO()
            with contextlib.redirect_stdout(output),self.assertRaises(SystemExit) as success:
                space["iscsi_guard"]("INPUT",lambda:(_ for _ in ()).throw(SystemExit(0)))
            self.assertEqual(0,success.exception.code);self.assertEqual("",output.getvalue())
        finally:fixture.doCleanups()



class StorageIscsiOwnedCleanupTest(unittest.TestCase):
    """Exercise deletion against exact canonical ownership and disposable configfs."""
    CUSTOM = "iqn.2026-10.local.epic898:owned"
    OTHER = "iqn.2026-10.local.epic898:current"
    FOREIGN = "iqn.2026-05.local.storage:foreign"
    OWNED_ID = "1e24abb8-ad7a-4128-a1b9-d97292b77890"
    CURRENT_ID = "550c8e51-1c93-49d4-aea8-7684ab0ed60b"

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.iscsi = self.root / "iscsi";self.iscsi.mkdir()
        self.core = self.root / "core";self.core.mkdir()
        self.calls = []
        source = CLI.read_text();start = source.index("apply_iscsi_targets() {")
        function = source[start:source.index("apply_nvmeof_subsystems() {", start)]
        code = function[function.index("<<'PY'\n")+len("<<'PY'\n"):function.rindex("\nPY")]
        names = {"managed_iqn", "existing_iscsi_targets", "desired_iqns_from_payload",
                 "block_backstores_from_payload", "existing_managed_block_backstores",
                 "cleanup_stale_managed_targets", "clear_managed_iscsi_runtime",
                 "iscsi_payload_ownership", "iscsi_payload_luns", "iscsi_cleanup_lun", "iscsi_cleanup_ownership", "owned_iscsi_runtime"}
        nodes = [n for n in ast.parse(code).body if isinstance(n, ast.FunctionDef) and n.name in names]
        paths = {"/sys/kernel/config/target/iscsi": str(self.iscsi),
                 "/sys/kernel/config/target/core": str(self.core)}
        class Paths(ast.NodeTransformer):
            def visit_Constant(self, node):
                if isinstance(node.value, str) and node.value in paths:
                    return ast.copy_location(ast.Constant(paths[node.value]), node)
                return node
        tree = Paths().visit(ast.Module(body=nodes, type_ignores=[]));ast.fix_missing_locations(tree)
        self.space = {"Path": Path, "re": re, "payload": {"targets": []},
                      "previous_payload": {"targets": []},
                      "run_targetcli": self.command, "require_targetcli": self.command,
                      "iscsi_additional_cleanup_ownership": lambda: {}}
        exec(compile(tree, "<actual-owned-iscsi-cleanup>", "exec"), self.space)

    def command(self, *args, **kwargs):
        self.calls.append(args)
        return subprocess.CompletedProcess(args, 0, "", "")

    def row(self, iqn=None, uuid=None, state="Ready"):
        return {"uuid": uuid or self.OWNED_ID, "targetName": iqn or self.CUSTOM, "state": state}

    def live(self, iqn, uuid, number=0):
        obj = self.core / "iblock_0" / ("ablestack-" + uuid);obj.mkdir(parents=True)
        lun = self.iscsi / iqn / ("tpgt_1/lun/lun_" + str(number));lun.mkdir(parents=True)
        (lun / "owned-link").symlink_to(obj)
        return obj, lun

    def test_deleted_custom_iqn_and_exact_backstore_are_removed(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        self.live(self.CUSTOM, self.OWNED_ID)
        self.live(self.FOREIGN, "foreign-storage")
        self.space["cleanup_stale_managed_targets"](set())
        self.assertEqual([("/iscsi", "delete", self.CUSTOM),
                          ("/backstores/block", "delete", "ablestack-" + self.OWNED_ID)], self.calls)

    def test_disabled_and_error_canonical_rows_keep_cleanup_ownership(self):
        for state in ("Disabled", "Error", "Destroyed"):
            with self.subTest(state=state):
                self.calls.clear();self.space["previous_payload"]["targets"] = [self.row(state=state)]
                if not (self.iscsi / self.CUSTOM).exists():self.live(self.CUSTOM, self.OWNED_ID)
                self.space["cleanup_stale_managed_targets"](set())
                self.assertIn(("/iscsi", "delete", self.CUSTOM), self.calls)

    def test_authoritative_clear_uses_current_and_previous_canonical_only(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        self.space["payload"]["targets"] = [self.row(self.OTHER, self.CURRENT_ID)]
        self.live(self.CUSTOM, self.OWNED_ID);self.live(self.OTHER, self.CURRENT_ID)
        self.live(self.FOREIGN, "foreign-storage")
        self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual({("/iscsi", "delete", self.CUSTOM), ("/iscsi", "delete", self.OTHER),
                          ("/backstores/block", "delete", "ablestack-" + self.OWNED_ID),
                          ("/backstores/block", "delete", "ablestack-" + self.CURRENT_ID)}, set(self.calls))

    def test_empty_canonical_does_not_clear_foreign_prefix_or_backstore(self):
        self.live(self.FOREIGN, "foreign-storage")
        self.space["cleanup_stale_managed_targets"](set())
        self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([], self.calls)

    def test_foreign_lun_in_owned_target_rejects_before_any_delete(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        obj, lun = self.live(self.CUSTOM, self.OWNED_ID)
        foreign = self.core / "iblock_1/foreign";foreign.mkdir(parents=True)
        (lun / "foreign-link").symlink_to(foreign)
        with self.assertRaises(ValueError):self.space["cleanup_stale_managed_targets"](set())
        self.assertEqual([], self.calls)

    def test_named_owned_link_outside_configfs_core_rejects_before_any_delete(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        obj, lun = self.live(self.CUSTOM, self.OWNED_ID)
        (lun / "owned-link").unlink()
        foreign = self.root / "outside" / obj.name;foreign.mkdir(parents=True)
        (lun / "owned-link").symlink_to(foreign)
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([], self.calls)

    def test_cleanup_failure_is_not_ignored(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        self.live(self.CUSTOM, self.OWNED_ID)
        def failed(*args, **kwargs):raise RuntimeError("SYNTHETIC_FIXED_CLEANUP_FAILURE")
        self.space["require_targetcli"] = failed
        with self.assertRaises(RuntimeError):self.space["cleanup_stale_managed_targets"](set())
        self.assertEqual([], self.calls)

    def test_foreign_target_reference_to_owned_backstore_rejects_before_delete(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        obj, _ = self.live(self.CUSTOM, self.OWNED_ID)
        foreign = self.iscsi / self.FOREIGN / "tpgt_1/lun/lun_0"
        foreign.mkdir(parents=True);(foreign / "shared-owned-object").symlink_to(obj)
        for action in (lambda: self.space["cleanup_stale_managed_targets"](set()),
                       self.space["clear_managed_iscsi_runtime"]):
            with self.subTest(action=action), self.assertRaises(ValueError):action()
        self.assertEqual([], self.calls)

    def test_foreign_fabric_reference_to_owned_backstore_rejects_before_delete(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        obj, _ = self.live(self.CUSTOM, self.OWNED_ID)
        foreign = self.root / "loopback/naa.foreign/tpgt_1/lun/lun_0"
        foreign.mkdir(parents=True);(foreign / "shared-owned-object").symlink_to(obj)
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([], self.calls)

    def test_nonstandard_foreign_fabric_link_to_owned_backstore_rejects_before_delete(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        obj, _ = self.live(self.CUSTOM, self.OWNED_ID)
        foreign = self.root / "external_fabric/other_shape";foreign.mkdir(parents=True)
        (foreign / "live_lun").symlink_to(obj)
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([], self.calls)

    def test_extra_lun_in_owned_target_is_not_authorized_by_backstore_name(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        obj, _ = self.live(self.CUSTOM, self.OWNED_ID)
        extra = self.iscsi / self.CUSTOM / "tpgt_1/lun/lun_99";extra.mkdir()
        (extra / "same-owned-object").symlink_to(obj)
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([],self.calls)

    def test_crossed_lun_backstore_bindings_reject_before_delete(self):
        first = {**self.row(),"lunOrNamespace":"0"}
        second = {**self.row(uuid=self.CURRENT_ID),"lunOrNamespace":"1"}
        self.space["previous_payload"]["targets"] = [first,second]
        obj0,lun0 = self.live(self.CUSTOM,self.OWNED_ID,0)
        obj1,lun1 = self.live(self.CUSTOM,self.CURRENT_ID,1)
        (lun0/"owned-link").unlink();(lun1/"owned-link").unlink()
        (lun0/"wrong-object").symlink_to(obj1);(lun1/"wrong-object").symlink_to(obj0)
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([],self.calls)

    def test_recorded_stale_backstore_without_target_is_removed(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        obj = self.core / "iblock_0" / ("ablestack-" + self.OWNED_ID)
        obj.mkdir(parents=True)
        self.space["cleanup_stale_managed_targets"](set())
        self.assertEqual([("/backstores/block", "delete", "ablestack-" + self.OWNED_ID)], self.calls)

    def test_unrecorded_extra_portal_group_rejects_before_any_delete(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        self.live(self.CUSTOM, self.OWNED_ID)
        (self.iscsi / self.CUSTOM / "tpgt_2").mkdir()
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([], self.calls)

    def test_retained_custom_target_and_backstore_are_not_stale(self):
        self.space["previous_payload"]["targets"] = [self.row()]
        self.space["payload"]["targets"] = [self.row()]
        self.live(self.CUSTOM, self.OWNED_ID)
        self.space["cleanup_stale_managed_targets"]({self.CUSTOM})
        self.assertEqual([], self.calls)


class StorageIscsiStrictCreateTest(unittest.TestCase):
    def test_nonzero_already_or_exists_error_is_a_safe_backstore_failure(self):
        fixture = StorageIscsiLifecycleTest();fixture.setUp()
        try:
            for message in ("device already in use", "unrelated existing object exists"):
                with self.subTest(message=message):
                    space = fixture.diagnostic_namespace()
                    space["run_targetcli"] = lambda *args, **kw: subprocess.CompletedProcess(args, 1,
                        "SYNTHETIC_PRIVATE_CREDENTIAL " + message, "")
                    output = io.StringIO()
                    with contextlib.redirect_stdout(output):
                        with self.assertRaises(space["IscsiTargetcliFailure"]):
                            space["require_targetcli"]("/backstores/block", "create", "owned", "/dev/owned",
                                                       stage="BACKSTORE_CREATE")
                    public = json.loads(output.getvalue())
                    self.assertEqual("BACKSTORE_CREATE", public["stage"])
                    self.assertEqual(1, public["returnCode"])
                    self.assertNotIn("SYNTHETIC_PRIVATE_CREDENTIAL", output.getvalue())
        finally:fixture.doCleanups()

    def test_success_exit_code_remains_successful(self):
        fixture = StorageIscsiLifecycleTest();fixture.setUp()
        try:
            space = fixture.diagnostic_namespace()
            result = subprocess.CompletedProcess([], 0, "", "")
            space["run_targetcli"] = lambda *args, **kw: result
            self.assertIs(result, space["require_targetcli"]("/backstores/block", "create", "owned",
                                                            "/dev/owned", stage="BACKSTORE_CREATE"))
        finally:fixture.doCleanups()


class StorageIscsiRollbackAuthorityTest(unittest.TestCase):
    def setUp(self):
        self.fixture = StorageIscsiOwnedCleanupTest();self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root;self.space = self.fixture.space
        source = CLI.read_text();a = source.index("apply_iscsi_targets() {")
        body = source[a:source.index("apply_nvmeof_subsystems() {",a)]
        body = body[body.index("<<'PY'\n")+len("<<'PY'\n"):body.rindex("\nPY")]
        names = {"pending_nfs_read","pending_nfs_redact","iscsi_auth_writer",
                 "iscsi_cleanup_lun","iscsi_cleanup_bindings","iscsi_cleanup_receipt","rendered_runtime_context",
                 "iscsi_additional_cleanup_ownership","protected_directory","atomic_json",
                 "iscsi_backstore_public_identity","iscsi_source_identity_receipt","iscsi_checkpoint_source_authority","checkpoint_iscsi_source_identity",
                 "walk_devices","normalize_token","mounted","resolve_backstore"}
        nodes = [n for n in ast.parse(body).body if isinstance(n,(ast.Import,ast.ImportFrom))
                 or isinstance(n,ast.FunctionDef) and n.name in names
                 or isinstance(n,ast.ClassDef) and n.name=="PendingNfsGeneration"
                 or isinstance(n,ast.Assign) and any(isinstance(x,ast.Name) and x.id=="PENDING_NFS_MAX_BYTES" for x in n.targets)]
        paths={"/sys/kernel/config/target/core":str(self.fixture.core),"/sys/kernel/config/target/iscsi":str(self.fixture.iscsi)}
        class Paths(ast.NodeTransformer):
            def visit_Constant(self,node):
                if isinstance(node.value,str) and node.value in paths:return ast.copy_location(ast.Constant(paths[node.value]),node)
                return node
        tree=Paths().visit(ast.Module(body=nodes,type_ignores=[]));ast.fix_missing_locations(tree)
        exec(compile(tree,"<actual-iscsi-rollback-authority>","exec"),self.space)
        path = CLI.parent.parent/"lib/ablestack-storage/config_generation.py"
        spec = importlib.util.spec_from_file_location("iscsi_cleanup_generation_fixture",path)
        self.module = importlib.util.module_from_spec(spec);spec.loader.exec_module(self.module)
        self.config = self.root/"configuration";self.config.mkdir(mode=0o700)
        (self.config/"desired-state").mkdir(mode=0o700)
        self.generation = self.module.Generation(self.root/"generations",self.config)
        self.instance = str(uuid.uuid4())
        self.scope = {"instanceUuid":self.instance,"operationUuid":str(uuid.uuid4()),"revision":1}
        self.source = {"instanceUuid":self.instance,"targets":[{**self.fixture.row(self.fixture.OTHER,self.fixture.CURRENT_ID),
            "volumeUuid":"db3de2b7-5fcb-4cb5-a0aa-7ac41a161ffa","volumeSizeBytes":65536}]}
        self.module.atomic_json(self.config/"iscsi-targets.json",self.source)
        for action in ("begin","verify","commit","finish"):self.generation.execute(action,self.scope)
        frozen = self.generation.execute("frozen",self.scope)
        self.request = {**self.scope,"operationUuid":str(uuid.uuid4()),"revision":2,
                        "previousGeneration":frozen["generation"],"configurationDesiredState":frozen["configurationDesiredState"]}
        self.generation.execute("begin",self.request)
        self.target = {**self.source,"targets":self.source["targets"]+[self.fixture.row()]}
        self.module.atomic_json(self.config/"iscsi-targets.json",self.target)
        self.generation.execute("restore",self.request)
        self.original_pending = self.generation.pending.read_bytes()
        self.space["payload"] = self.source;self.space["previous_payload"] = self.source
        self.fixture.live(self.fixture.OTHER,self.fixture.CURRENT_ID)
        self.fixture.live(self.fixture.CUSTOM,self.fixture.OWNED_ID)
        lock = self.root/"writer.lock";descriptor = os.open(lock,os.O_RDWR|os.O_CREAT,0o600)
        try:saved = os.dup(9)
        except OSError:saved = None
        os.dup2(descriptor,9)
        if descriptor != 9:os.close(descriptor)
        fcntl.flock(9,fcntl.LOCK_EX|fcntl.LOCK_NB)
        def close_lock():
            os.close(9)
            if saved is not None:os.dup2(saved,9);os.close(saved)
        self.addCleanup(close_lock)
        self.env = mock.patch.dict(os.environ,{"ABLESTACK_STORAGE_GENERATION_DIR":str(self.generation.root),
            "ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(self.config),"ABLESTACK_STORAGE_WRITER_LOCK_FILE":str(lock),
            "ABLESTACK_STORAGE_WRITER_LOCK_FD":"9"})
        self.env.start();self.addCleanup(self.env.stop)
        for name in ("ABLESTACK_STORAGE_RENDERED_REPLAY","ABLESTACK_STORAGE_RENDERED_FROM","ABLESTACK_STORAGE_RENDERED_GENERATIONS"):
            os.environ.pop(name,None)

    def test_source_restore_retains_exact_target_cleanup_through_real_protected_pending(self):
        self.assertEqual(self.source,self.module.read_json(self.config/"iscsi-targets.json"))
        self.space["cleanup_stale_managed_targets"]({self.fixture.OTHER})
        self.assertEqual([("/iscsi","delete",self.fixture.CUSTOM),
                          ("/backstores/block","delete","ablestack-"+self.fixture.OWNED_ID)],self.fixture.calls)

    def test_verified_noncommitted_scope_can_consume_original_target_cleanup(self):
        pending=json.loads(self.original_pending);pending["phase"]="VERIFIED"
        self.module.atomic_json(self.generation.pending,pending)
        self.space["cleanup_stale_managed_targets"]({self.fixture.OTHER})
        self.assertIn(("/iscsi","delete",self.fixture.CUSTOM),self.fixture.calls)
        self.assertNotIn(("/iscsi","delete",self.fixture.OTHER),self.fixture.calls)

    def test_lun_update_rollback_authorizes_source_zero_and_target_one(self):
        pending=json.loads(self.original_pending);pending.pop("iscsiCleanupOwnership",None)
        self.module.atomic_json(self.generation.pending,pending)
        changed=copy.deepcopy(self.source);changed["targets"][0]["lunOrNamespace"]="1"
        self.module.atomic_json(self.config/"iscsi-targets.json",changed)
        self.generation.execute("restore",self.request)
        receipt=self.module.read_json(self.generation.pending)["iscsiCleanupOwnership"]
        self.assertEqual("1",receipt["targets"][0]["lunOrNamespace"])
        lun=self.fixture.iscsi/self.fixture.OTHER/"tpgt_1/lun/lun_0"
        lun.rename(lun.with_name("lun_1"))
        self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([("/iscsi","delete",self.fixture.OTHER),
                          ("/backstores/block","delete","ablestack-"+self.fixture.CURRENT_ID)],self.fixture.calls)

    def test_pending_authority_rejects_scope_boot_digest_phase_and_missing_writer(self):
        original = json.loads(self.original_pending)
        changes = [("scope",{"operationUuid":str(uuid.uuid4())}),("bootId",str(uuid.uuid4())),
                   ("bindingsSha256","0"*64),("beforeSha256","0"*64)]
        for key,value in changes:
            with self.subTest(key=key):
                pending = copy.deepcopy(original)
                if key=="scope":pending["iscsiCleanupOwnership"]["scope"].update(value)
                else:pending["iscsiCleanupOwnership"][key]=value
                self.module.atomic_json(self.generation.pending,pending)
                with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
                self.assertEqual([],self.fixture.calls)
        self.generation.pending.write_bytes(self.original_pending)
        self.module.atomic_json(self.generation.pending,{**original,"phase":"COMMITTED"})
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.generation.pending.write_bytes(self.original_pending)
        with mock.patch.dict(os.environ,{"ABLESTACK_STORAGE_WRITER_LOCK_FD":""}):
            with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([],self.fixture.calls)

    def test_pending_authority_rejects_changed_source_or_current_pointer(self):
        self.module.atomic_json(self.config/"iscsi-targets.json",self.target)
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.module.atomic_json(self.config/"iscsi-targets.json",self.source)
        self.module.atomic_json(self.generation.current,{})
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([],self.fixture.calls)

    def test_local_checkpoint_boot_or_source_hash_cannot_be_borrowed(self):
        identity = str(uuid.UUID(bytes=hashlib.md5(("local-source-checkpoint:"+self.request["operationUuid"]).encode()).digest(),version=3))
        path = self.generation.root.parent/"smb-local-source-checkpoints"/(identity+"-journal.json")
        local = {"scope":{**{k:self.request[k]for k in ("instanceUuid","operationUuid","revision")},"localCheckpointUuid":identity},
                 "common":{"expectedBootId":str(uuid.uuid4()),"sourceConfigurationSha256":self.request["previousGeneration"]["configurationSha256"]}}
        self.module.atomic_json(path,local)
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([],self.fixture.calls)

    def rendered(self):
        root = self.root/"rendered";(root/"generations").mkdir(parents=True,mode=0o700);root.chmod(0o700)
        result = []
        for revision,desired in ((1,self.source),(2,self.target)):
            scope = {**self.scope,"operationUuid":str(uuid.uuid4()),"revision":revision}
            path = root/"generations"/scope["operationUuid"];path.mkdir(mode=0o700)
            raw = json.dumps({"iscsi-targets.json":desired},sort_keys=True).encode()
            (path/"desired-state.json").write_bytes(raw);(path/"desired-state.json").chmod(0o600)
            manifest = {"scope":scope,"files":{"desired-state.json":hashlib.sha256(raw).hexdigest()}}
            manifest["manifestSha256"] = hashlib.sha256(json.dumps(manifest,sort_keys=True,separators=(",",":")).encode()).hexdigest()
            self.module.atomic_json(path/"generation.json",manifest);result.append((path,manifest))
        (root/"current").symlink_to("generations/"+result[0][0].name)
        self.module.atomic_json(root/"activation.json",{"targetSha256":result[1][1]["manifestSha256"],"previousSha256":result[0][1]["manifestSha256"]})
        os.environ.update({"ABLESTACK_STORAGE_RENDERED_GENERATIONS":str(root),"ABLESTACK_STORAGE_RENDERED_REPLAY":str(result[0][0]),
                           "ABLESTACK_STORAGE_RENDERED_FROM":str(result[1][0])})
        return root,result

    def test_rendered_source_replay_uses_only_the_pinned_target_manifest(self):
        self.rendered()
        self.generation.pending.unlink()
        self.space["cleanup_stale_managed_targets"]({self.fixture.OTHER})
        self.assertIn(("/iscsi","delete",self.fixture.CUSTOM),self.fixture.calls)

    def test_rendered_update_rollback_preserves_exact_source_and_target_lun_authority(self):
        self.target=copy.deepcopy(self.source);self.target["targets"][0]["lunOrNamespace"]="1"
        self.rendered();self.generation.pending.unlink()
        lun=self.fixture.iscsi/self.fixture.OTHER/"tpgt_1/lun/lun_0"
        lun.rename(lun.with_name("lun_1"))
        self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([("/iscsi","delete",self.fixture.OTHER),
                          ("/backstores/block","delete","ablestack-"+self.fixture.CURRENT_ID)],self.fixture.calls)

    def test_rendered_context_rejects_changed_file_pointer_or_journal(self):
        root,result = self.rendered()
        desired = result[1][0]/"desired-state.json";raw=desired.read_bytes()
        desired.write_bytes(b"{}")
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        desired.write_bytes(raw)
        self.module.atomic_json(root/"activation.json",{"targetSha256":"0"*64,"previousSha256":result[0][1]["manifestSha256"]})
        with self.assertRaises(ValueError):self.space["clear_managed_iscsi_runtime"]()
        self.assertEqual([],self.fixture.calls)


class StorageIscsiSourceWwnTest(unittest.TestCase):
    WWN="44444444-4444-4444-8444-444444444444"
    def setUp(self):
        self.base=StorageIscsiRollbackAuthorityTest();self.base.setUp()
        self.addCleanup(self.base.doCleanups)
        self.space=self.base.space;self.target=self.base.source["targets"][0]
        self.obj=self.base.fixture.core/"iblock_0"/("ablestack-"+self.target["uuid"])
        (self.obj/"wwn").mkdir();(self.obj/"wwn/vpd_unit_serial").write_text("T10 VPD Unit Serial Number: "+self.WWN+"\n")
        (self.obj/"udev_path").write_text("/dev/source")
        self.disk={"path":"/dev/source","serial":self.target["volumeUuid"].replace("-","")[:20],
                   "size":65536,"type":"disk","mountpoint":None}
        self.space["lsblk_devices"]=lambda:[self.disk]
        self.space["find_backing_path"]=lambda target:"/dev/source"
        self.space["normalize_backstore_type"]=lambda target:"BLOCK"
        self.space["mountpoint_for_device"]=lambda path:None
        self.space["validate_block_backstore_device"]=lambda path:None
        self.calls=[];self.wrong=False
        def command(*args,**kwargs):
            import shutil
            self.calls.append(args)
            if args[0]=="/backstores/block" and args[1]=="create":
                self.obj.mkdir(parents=True,exist_ok=True);(self.obj/"wwn").mkdir(exist_ok=True)
                serial=next((x.split("=",1)[1]for x in args if x.startswith("wwn=")),"55555555-5555-4555-8555-555555555555")
                if self.wrong:serial="66666666-6666-4666-8666-666666666666"
                (self.obj/"wwn/vpd_unit_serial").write_text("T10 VPD Unit Serial Number: "+serial+"\n")
                (self.obj/"udev_path").write_text("/dev/source")
            if args[0]=="/backstores/block" and args[1]=="delete" and self.obj.exists():shutil.rmtree(self.obj)
            return subprocess.CompletedProcess(args,0,"","")
        self.space["require_targetcli"]=command;self.space["run_targetcli"]=command

    def checkpoint(self):
        result=self.space["checkpoint_iscsi_source_identity"]()
        self.space["iscsi_runtime_identity"]=result
        return result

    def test_failed_delete_restores_original_wwn_after_live_object_is_gone(self):
        import shutil
        self.checkpoint()
        first=self.base.generation.pending.read_bytes()
        self.assertEqual(self.WWN,json.loads(first)["iscsiSourceRuntimeIdentity"]["identities"][0]["wwn"])
        shutil.rmtree(self.obj)
        shutil.rmtree(self.base.fixture.iscsi/self.base.fixture.OTHER)
        self.checkpoint()
        self.assertEqual(first,self.base.generation.pending.read_bytes())
        self.space["resolve_backstore"](self.target,"ablestack-"+self.target["uuid"])
        self.assertIn("wwn="+self.WWN,self.calls[0])
        self.assertIn(self.WWN,(self.obj/"wwn/vpd_unit_serial").read_text())

    def test_normal_lun_update_reuses_original_unit_identity_and_ignores_payload_wwn(self):
        import shutil
        self.checkpoint();shutil.rmtree(self.obj)
        updated={**self.target,"lunOrNamespace":"1","wwn":"77777777-7777-4777-8777-777777777777"}
        self.space["resolve_backstore"](updated,"ablestack-"+self.target["uuid"])
        self.assertIn("wwn="+self.WWN,self.calls[0])
        self.assertNotIn("wwn="+updated["wwn"],self.calls[0])

    def test_source_checkpoint_fsync_failure_precedes_any_delete(self):
        self.space["atomic_json"]=lambda *a,**k:(_ for _ in ()).throw(OSError("synthetic fsync failure"))
        with self.assertRaises(OSError):self.checkpoint()
        self.assertEqual([],self.calls)
        self.assertTrue(self.obj.exists())

    def test_changed_source_serial_or_data_size_rejects_before_delete(self):
        for change in ({"serial":"foreign"},{"size":65537},{"mountpoint":"/foreign"}):
            with self.subTest(change=change):
                original=dict(self.disk);self.disk.update(change)
                with self.assertRaises(ValueError):self.checkpoint()
                self.disk.clear();self.disk.update(original)
                self.assertEqual([],self.calls)

    def test_recreated_unit_serial_mismatch_is_failure_and_only_new_object_is_cleaned(self):
        import shutil
        self.checkpoint();shutil.rmtree(self.obj);self.wrong=True
        with self.assertRaises(ValueError):self.space["resolve_backstore"](self.target,"ablestack-"+self.target["uuid"])
        self.assertEqual("delete",self.calls[-1][1]);self.assertFalse(self.obj.exists())

    def test_existing_receipt_cannot_change_boot_scope_or_unit_volume_binding(self):
        self.checkpoint()
        original=self.base.module.read_json(self.base.generation.pending)
        for change in ({"bootId":str(uuid.uuid4())},{"beforeSha256":"0"*64},{"identitiesSha256":"0"*64},
                       {"scope":{**original["iscsiSourceRuntimeIdentity"]["scope"],"operationUuid":str(uuid.uuid4())}}):
            with self.subTest(change=change):
                pending=copy.deepcopy(original);pending["iscsiSourceRuntimeIdentity"].update(change)
                self.base.module.atomic_json(self.base.generation.pending,pending)
                with self.assertRaises(ValueError):self.checkpoint()
                self.assertEqual([],self.calls)

    def test_multi_protocol_partial_target_uses_verified_frozen_iscsi_source(self):
        pending=self.base.module.read_json(self.base.generation.pending);pending.pop("iscsiCleanupOwnership",None)
        self.base.module.atomic_json(self.base.generation.pending,pending)
        self.base.module.atomic_json(self.base.config/"desired-state/nfs-export-apply.json",{"exports":[{"name":"partial-target"}]})
        self.assertNotEqual(pending["beforeSha256"],self.base.generation.digest())
        result=self.checkpoint()
        self.assertEqual(self.WWN,result[self.target["uuid"]]["wwn"])
        receipt=self.base.module.read_json(self.base.generation.pending)["iscsiSourceRuntimeIdentity"]
        self.assertEqual(pending["beforeSha256"],receipt["beforeSha256"])

    def test_partial_target_cannot_replace_exact_frozen_iscsi_source_binding(self):
        pending=self.base.module.read_json(self.base.generation.pending);pending.pop("iscsiCleanupOwnership",None)
        self.base.module.atomic_json(self.base.generation.pending,pending)
        changed=copy.deepcopy(self.base.source);changed["targets"][0]["lunOrNamespace"]="1"
        self.base.module.atomic_json(self.base.config/"iscsi-targets.json",changed)
        with self.assertRaises(ValueError):self.checkpoint()
        self.assertEqual([],self.calls)

    def test_unit_receipt_publication_preserves_other_protocol_pending_fields(self):
        validate=self.space["iscsi_source_identity_receipt"];injected=[False]
        def add_other(value,pending,boot):
            if not injected[0]:
                injected[0]=True
                latest=self.base.module.read_json(self.base.generation.pending)
                self.base.module.atomic_json(self.base.generation.pending,{**latest,"nvmeRuntimeIdentity":{"publicFixture":True}})
            return validate(value,pending,boot)
        self.space["iscsi_source_identity_receipt"]=add_other
        self.checkpoint()
        pending=self.base.module.read_json(self.base.generation.pending)
        self.assertEqual({"publicFixture":True},pending["nvmeRuntimeIdentity"])
        self.assertIn("iscsiSourceRuntimeIdentity",pending)

    def test_empty_initial_iscsi_source_does_not_block_partial_multi_protocol_apply(self):
        generation=self.base.module.Generation(self.base.root/"initial-generation",self.base.root/"initial-config")
        generation.config.mkdir(mode=0o700);(generation.config/"desired-state").mkdir(mode=0o700)
        request={"instanceUuid":self.base.instance,"operationUuid":str(uuid.uuid4()),"revision":1}
        generation.execute("begin",request)
        self.base.module.atomic_json(generation.config/"desired-state/nfs-export-apply.json",{"exports":[{"name":"first-target"}]})
        with mock.patch.dict(os.environ,{"ABLESTACK_STORAGE_GENERATION_DIR":str(generation.root),
                                        "ABLESTACK_STORAGE_CONFIGURATION_ROOT":str(generation.config)}):
            self.space["previous_payload"]={};self.space["payload"]={"instanceUuid":self.base.instance,"targets":[]}
            result=self.checkpoint()
        self.assertEqual({},result)
        self.assertNotIn("iscsiSourceRuntimeIdentity",self.base.module.read_json(generation.pending))
        self.assertEqual([],self.calls)

    def test_verified_wwn_recovery_consumes_only_immutable_prepared_source_receipt(self):
        self.checkpoint()
        pending=self.base.module.read_json(self.base.generation.pending)
        original=copy.deepcopy(pending["iscsiSourceRuntimeIdentity"]);pending["phase"]="VERIFIED"
        self.base.module.atomic_json(self.base.generation.pending,pending)
        self.assertEqual(self.WWN,self.checkpoint()[self.target["uuid"]]["wwn"])
        self.assertEqual(original,self.base.module.read_json(self.base.generation.pending)["iscsiSourceRuntimeIdentity"])
        pending.pop("iscsiSourceRuntimeIdentity")
        self.base.module.atomic_json(self.base.generation.pending,pending)
        with self.assertRaises(ValueError):self.checkpoint()
        self.assertNotIn("iscsiSourceRuntimeIdentity",self.base.module.read_json(self.base.generation.pending))
        self.assertEqual([],self.calls)

    def test_no_pending_reuses_only_exact_live_public_unit_identity(self):
        self.base.generation.pending.unlink()
        result=self.checkpoint();self.assertEqual(self.WWN,result[self.target["uuid"]]["wwn"])
        self.assertFalse(self.base.generation.pending.exists())

def compiled_consumer_fixtures():
    rows=[]
    for scenario in ("target-iqn-invalid","initiator-iqn-invalid","ownership-invalid","ownership-mismatch","auth-value-invalid","early-input","early-vault","mid-device-cleanup","readiness","auth","lookup","attribute","assertion","targetcli-first","unknown","unknown-mid"):
        result=IscsiGuardEntryFixture().run(scenario)
        if result["failure"] is None:raise AssertionError("Fixture did not fail")
        rows.append({"scenario":scenario,"stdout":result["stdout"],"exitCode":1})
    return rows


if __name__ == "__main__":
    if "--guard-consumer-fixtures" in sys.argv:
        print(json.dumps(compiled_consumer_fixtures(),separators=(",",":")))
    else:
        unittest.main()
