# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import importlib.util
import pathlib
import unittest

path = pathlib.Path(__file__).parents[2] / "main/resources/script/upgrade-control-plane-gate.py"
spec = importlib.util.spec_from_file_location("control_gate", path)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def pod(image="apiserver:v1.36.5", status="True"):
    return {"metadata": {"name": "kube-apiserver-control-a"}, "spec": {"containers": [{"image": image}]},
            "status": {"conditions": [{"type": "Ready", "status": status}]}}


class GateTest(unittest.TestCase):
    def test_old_ready_image_is_not_upgrade_completion(self):
        self.assertFalse(gate.verify_local_pods([pod("apiserver:v1.35.9")], "control-a", {"kube-apiserver": "apiserver:v1.36.5"}))

    def test_current_image_must_be_ready(self):
        self.assertFalse(gate.verify_local_pods([pod(status="False")], "control-a", {"kube-apiserver": "apiserver:v1.36.5"}))

    def test_ready_target_image_accepted(self):
        self.assertTrue(gate.verify_local_pods([pod()], "control-a", {"kube-apiserver": "apiserver:v1.36.5"}))

    def test_terminating_target_rejected(self):
        p = pod()
        p["metadata"]["deletionTimestamp"] = "2026-10-06T00:00:00Z"
        self.assertFalse(gate.verify_local_pods([p], "control-a", {"kube-apiserver": "apiserver:v1.36.5"}))

    def test_quorum_only_does_not_allow_next_node(self):
        h = [{"endpoint": "a", "health": True}, {"endpoint": "b", "health": True}]
        self.assertFalse(gate.verify_etcd_health(h, 3))

    def test_duplicate_healthy_endpoint_is_not_three_members(self):
        h = [{"endpoint": "a", "health": True}] * 3
        self.assertFalse(gate.verify_etcd_health(h, 3))

    def test_unhealthy_member_rejected(self):
        h = [{"endpoint": str(i), "health": i != 1} for i in range(3)]
        self.assertFalse(gate.verify_etcd_health(h, 3))

    def test_all_members_healthy_accepted(self):
        h = [{"endpoint": str(i), "health": True} for i in range(3)]
        self.assertTrue(gate.verify_etcd_health(h, 3))


if __name__ == "__main__":
    unittest.main()
