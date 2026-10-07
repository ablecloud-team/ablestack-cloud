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

import copy
import importlib.util
import pathlib
import unittest

source = pathlib.Path(__file__).resolve().parents[2] / "main/resources/script/upgrade-workload-gate.py"
spec = importlib.util.spec_from_file_location("upgrade_gate", source)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def controller(name="web", ready=2):
    return {"kind": "Deployment", "metadata": {"namespace": "app", "name": name, "uid": name + "-uid", "generation": 2},
            "spec": {"replicas": 2}, "status": {"observedGeneration": 2, "readyReplicas": ready,
                                                 "updatedReplicas": 2, "availableReplicas": ready}}


def pod(uid, node="worker1", ready=True):
    return {"kind": "Pod", "metadata": {"namespace": "app", "name": uid, "uid": uid, "labels": {"app": "web"}},
            "spec": {"nodeName": node}, "status": {"conditions": [{"type": "Ready", "status": "True" if ready else "False"}]}}


def service():
    return {"kind": "Service", "metadata": {"namespace": "app", "name": "web", "uid": "service-uid"},
            "spec": {"selector": {"app": "web"}, "type": "LoadBalancer"},
            "status": {"loadBalancer": {"ingress": [{"ip": "192.0.2.1"}]}}}


def endpoints(uids=("pod1", "pod2"), owner="service-uid"):
    return {"kind": "EndpointSlice", "metadata": {"namespace": "app", "name": "web-slice", "uid": "slice-uid",
                                                   "ownerReferences": [{"kind": "Service", "uid": owner}]},
            "endpoints": [{"targetRef": {"kind": "Pod", "uid": u}, "conditions": {"ready": True}} for u in uids]}


class UpgradeGateTest(unittest.TestCase):
    def setUp(self):
        self.items = [controller(), service(), pod("pod1"), pod("pod2", "worker2"), endpoints()]
        self.baseline = gate.capture(self.items, ["worker1", "worker2"])

    def test_waits_for_replacement_workloads_and_service_endpoints(self):
        current = [controller(ready=1), service(), pod("new1"), pod("new2", "worker2", False), endpoints(("new1", "new2"))]
        self.assertFalse(gate.recovered(self.baseline, current))
        current[0] = controller()
        current[3] = pod("new2", "worker2")
        self.assertTrue(gate.recovered(self.baseline, current))

    def test_existing_pending_deployment_is_not_an_upgrade_failure(self):
        old = controller("already-pending", 0)
        baseline = gate.capture(self.items + [old], ["worker1"])
        self.assertEqual(1, len(baseline["controllers"]))
        self.assertTrue(gate.recovered(baseline, self.items + [old]))

    def test_ready_endpoint_cannot_reference_a_stale_pod_or_foreign_service(self):
        self.assertEqual(0, gate.endpoint_count(service(), [pod("pod1"), endpoints(owner="foreign-service")]))
        self.assertEqual(0, gate.endpoint_count(service(), [pod("replacement"), endpoints(("pod1",))]))

    def test_pdb_blocks_before_any_node_changes(self):
        budget = {"kind": "PodDisruptionBudget", "metadata": {"namespace": "app", "name": "web-protect", "uid": "pdb"},
                  "spec": {"selector": {"matchLabels": {"app": "web"}}}, "status": {"disruptionsAllowed": 0}}
        with self.assertRaisesRegex(gate.GateError, "PDB blocks upgrade"):
            gate.capture(self.items + [budget], ["worker1"])
        budget["status"]["disruptionsAllowed"] = 1
        self.assertEqual(1, len(gate.capture(self.items + [budget], ["worker1"])["controllers"]))

    def test_pdb_does_not_block_unrelated_nodes_or_daemonsets(self):
        daemon = pod("agent")
        daemon["metadata"]["ownerReferences"] = [{"kind": "DaemonSet", "uid": "ds"}]
        budget = {"kind": "PodDisruptionBudget", "metadata": {"namespace": "app", "name": "budget", "uid": "pdb"},
                  "spec": {"selector": {}}, "status": {"disruptionsAllowed": 0}}
        gate.capture([daemon, budget], ["worker1"])
        gate.capture(self.items + [budget], ["other-node"])

    def test_replacement_uid_and_concurrent_scale_fail_closed(self):
        current = copy.deepcopy(self.items)
        current[0]["metadata"]["uid"] = "new-owner"
        with self.assertRaises(gate.GateError):
            gate.recovered(self.baseline, current)
        current = copy.deepcopy(self.items)
        current[0]["spec"]["replicas"] = 3
        with self.assertRaises(gate.GateError):
            gate.recovered(self.baseline, current)

    def test_lb_ingress_loss_is_not_recovery(self):
        current = copy.deepcopy(self.items)
        current[1]["status"] = {}
        self.assertFalse(gate.recovered(self.baseline, current))

    def test_pdb_expression_semantics(self):
        self.assertTrue(gate.selected({"matchExpressions": [{"key": "other", "operator": "NotIn", "values": ["x"]}]}, {}))
        self.assertFalse(gate.selected({"matchExpressions": [{"key": "app", "operator": "In", "values": ["other"]}]}, {"app": "web"}))
        with self.assertRaises(gate.GateError):
            gate.selected({"matchExpressions": [{"key": "app", "operator": "Unknown"}]}, {})

    def test_receipt_contains_no_pod_env_or_credential_values(self):
        self.items[2]["spec"]["containers"] = [{"env": [{"name": "SECRET", "value": "do-not-store"}]}]
        self.assertNotIn("do-not-store", str(gate.capture(self.items, ["worker1"])))


if __name__ == "__main__":
    unittest.main()
