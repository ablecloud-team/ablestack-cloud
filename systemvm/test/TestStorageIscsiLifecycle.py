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
helpers=[item for item in tree.body if isinstance(item,ast.FunctionDef) and item.name=="service_action"]
readiness=[item for item in tree.body if isinstance(item,ast.If) and ast.unparse(item.test)=="enabled and applied"]
assert len(readiness)==1
exec(compile(ast.Module(body=helpers+readiness,type_ignores=[]),"<signed-iscsi-readiness>","exec"),namespace)
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
        self.assertIn("did not listen on TCP port(s): 3260",result.stderr)
        self.assertEqual("FOREIGN_CONFIGFS",self.foreign.read_text())
        self.assertFalse(self.calls.exists())

    def diagnostic_namespace(self):
        begin = self.function.index("<<'PY'\n") + len("<<'PY'\n")
        source = self.function[begin:self.function.rindex("\nPY")]
        names = {"run", "run_targetcli", "ok_or_exists", "require_targetcli",
                 "targetcli_error_category", "rollback_created"}
        constants = {"ISCSI_DIAGNOSTIC_STAGES", "ISCSI_DIAGNOSTIC_CATEGORIES", "first_targetcli_failure"}
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
        self.assertIsNone(space["first_targetcli_failure"])

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


if __name__=="__main__":unittest.main()
