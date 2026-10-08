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
import os
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


if __name__=="__main__":unittest.main()
