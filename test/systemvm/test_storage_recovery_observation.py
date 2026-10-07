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


"""Read actual rendered access policies without privileged changes or secret disclosure."""
import ast
from pathlib import Path
import re
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "systemvm/debian/usr/local/bin/ablestack-storagectl"

def observation_namespace():
    block = next(value for value in re.findall(r"<<'PY'\n(.*?)\nPY", SOURCE.read_text(), re.S)
                 if "def nfs_rendered_clients(" in value)
    nodes = [node for node in ast.parse(block).body if isinstance(node, ast.FunctionDef)
             and node.name in {"config_value", "nfs_rendered_clients"}]
    namespace = {"re": re}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(SOURCE), "exec"), namespace)
    return namespace

class RecoveryObservationTest(unittest.TestCase):
    def test_reads_actual_client_policy_and_export_anonymous_defaults(self):
        observed = observation_namespace()["nfs_rendered_clients"](
            'Access_Type = None; Squash = Root_Squash; Anonymous_uid = 1234;'
            'CLIENT { Clients = 10.1.1.9/32; Access_Type = RW; Squash = All_Squash; Anonymous_gid = 5678; }')
        self.assertEqual([{"clients": "10.1.1.9/32", "access": "RW", "squash": "All_Squash",
                           "anonUid": 1234, "anonGid": 5678}], observed)
    def test_does_not_invent_a_client_when_rendered_export_has_none(self):
        self.assertEqual([], observation_namespace()["nfs_rendered_clients"]("Access_Type = None;"))
    def test_client_overrides_do_not_bleed_between_clients(self):
        observed = observation_namespace()["nfs_rendered_clients"](
            'Squash = Root_Squash; CLIENT { Clients = 10.1.1.9/32; Access_Type = RO; Anonymous_uid = 1002; }'
            'CLIENT { Clients = 10.1.1.165/32; Access_Type = RW; }')
        self.assertEqual(1002, observed[0]["anonUid"])
        self.assertEqual(65534, observed[1]["anonUid"])
        self.assertEqual("Root_Squash", observed[1]["squash"])

if __name__ == "__main__":
    unittest.main()
