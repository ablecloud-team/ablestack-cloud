# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http: #www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.


"""Fresh verification probes must not accept the monitor cache as new apply evidence."""
import re,subprocess,unittest
from pathlib import Path
SOURCE=Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"
class FreshObservationTest(unittest.TestCase):
    def test_operation_observe_disables_inventory_cache_in_the_actual_command_branch(self):
        source=SOURCE.read_text()
        branch=re.search(r'elif \[\[ "\$subcommand" == "observe" \]\]; then\n(.*?)\n    else',source,re.S).group(1)
        result=subprocess.run(["bash","-c","runtime_inventory() { printf '%s' \"$ABLESTACK_STORAGECTL_CACHE\"; };\n"+branch],capture_output=True,text=True,check=True)
        self.assertEqual("0",result.stdout)
    def test_operation_verify_remains_cache_free_for_health(self):
        source=SOURCE.read_text()
        branch=re.search(r'elif \[\[ "\$subcommand" == "verify" \]\]; then\n(.*?)\n    elif',source,re.S).group(1)
        result=subprocess.run(["bash","-c","runtime_health() { printf '%s' \"$ABLESTACK_STORAGECTL_CACHE\"; };\n"+branch],capture_output=True,text=True,check=True)
        self.assertEqual("0",result.stdout)
    def test_empty_iscsi_state_file_does_not_require_a_listener_without_targets(self):
        source=SOURCE.read_text()
        branch=re.search(r'if iscsi_targets_state.get\("targets"\) and not listen_ports\["iscsi"\]:\n(.*?)\nif desired_state',source,re.S).group(0).split("\nif desired_state")[0]
        scope={"iscsi_targets_state":{"targets":[],"listeners":[{"port":3260}]},"listen_ports":{"iscsi":False},"status":"ok"}
        exec(branch,scope);self.assertEqual("ok",scope["status"])
        scope["iscsi_targets_state"]["targets"]=[{"targetName":"required"}]
        exec(branch,scope);self.assertEqual("degraded",scope["status"])
    def test_local_machine_identity_change_restarts_but_policy_reload_keeps_sessions(self):
        source=SOURCE.read_text()
        branch=re.search(r'    if previous_netbios_name and previous_netbios_name != netbios_name:\n(.*?)\nfd, managed_temporary',source,re.S).group(0).split("\nfd, managed_temporary")[0]
        import textwrap
        branch=textwrap.dedent(branch)
        calls=[];scope={"previous_netbios_name":"OLD","netbios_name":"NEW","run":lambda command,**kwargs:calls.append(command),"subprocess":subprocess}
        exec(branch,scope);self.assertEqual([["systemctl","restart","smbd","nmbd"]],calls)
        calls.clear();scope["previous_netbios_name"]="NEW";exec(branch,scope);self.assertEqual([["smbcontrol","all","reload-config"]],calls)
if __name__=="__main__":unittest.main()
