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



class IscsiGuardEntryFixture:
    """Run the actual apply heredoc with disposable files/external adapters."""
    SECRET = "SYNTHETIC_PRIVATE_GUARD_TOKEN"

    def run(self, scenario):
        from unittest import mock
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary);state = root / "state";state.mkdir(mode=0o700)
            raw = root / "raw";raw.write_bytes(b"\0" * 65536)
            payload = {"enabled": True, "listeners": [{"listenIp": "127.0.0.1", "port": 3260}],
                       "targets": [{"uuid": "fixture-id", "targetName": "iqn.2026-05.local.storage:fixture",
                                    "volumeName": "fixture-serial", "volumeUuid": "fixture-serial",
                                    "lunOrNamespace": "0", "volumeSizeBytes": 65536, "acls": []}]}
            if scenario == "disabled-null":
                payload["enabled"]=False;payload["targets"][0]["state"]="Disabled";payload["targets"][0]["acls"]=None
            if scenario == "auth":payload["targets"][0]["acls"]=[{"principal":"iqn.2026-05.example.client:fixture","config":{},"state":"Ready"}]
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
                        if node.value.startswith("/sys/kernel/config/target/"):
                            return ast.copy_location(ast.Constant(str(root / "configfs" / node.value.removeprefix("/sys/kernel/config/target/"))),node)
                    return node
            tree=FixturePaths().visit(tree);ast.fix_missing_locations(tree)
            calls=[];failed=[False]
            def run(command,**kwargs):
                calls.append(list(command))
                if command[0]=="targetcli":
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
                        data={"blockdevices":[{"name":"fixture","path":str(raw),"type":"disk","serial":"fixture-serial","size":65536,"mountpoint":mounted,"children":[]}]}
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
            with mock.patch.object(sys,"argv",["fixture",str(path)]),mock.patch.object(subprocess,"run",side_effect=run),mock.patch("time.sleep",lambda seconds:None),contextlib.redirect_stdout(output):
                try:exec(compile(tree,"<actual-iscsi-guard-entry>","exec"),{})
                except BaseException as failure:error=failure
            return {"stdout":output.getvalue(),"failure":error,"calls":calls}


class StorageIscsiGuardDiagnosticTest(unittest.TestCase):
    def test_early_and_mid_and_late_actual_call_sites_produce_one_safe_hint(self):
        for scenario,stage,category in (("early-input","INPUT","VALUE_ERROR"),("early-vault","VAULT_STATE","VALUE_ERROR"),
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


def compiled_consumer_fixtures():
    rows=[]
    for scenario in ("early-input","early-vault","mid-device-cleanup","readiness","auth","lookup","attribute","assertion","targetcli-first","unknown","unknown-mid"):
        result=IscsiGuardEntryFixture().run(scenario)
        if result["failure"] is None:raise AssertionError("Fixture did not fail")
        rows.append({"scenario":scenario,"stdout":result["stdout"],"exitCode":1})
    return rows


if __name__ == "__main__":
    if "--guard-consumer-fixtures" in sys.argv:
        print(json.dumps(compiled_consumer_fixtures(),separators=(",",":")))
    else:
        unittest.main()
