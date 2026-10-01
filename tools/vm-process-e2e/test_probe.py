# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import probe

VM = "11111111-1111-4111-8111-111111111111"
SNAPSHOT = "22222222-2222-4222-8222-222222222222"


class ProbeTest(unittest.TestCase):
    def test_refuses_an_unlisted_production_vm(self):
        client = Mock()
        client.call.return_value = {"virtualmachine": [{"name": "production"}]}
        with self.assertRaises(probe.ProbeError):
            probe.test_vm(client, VM)

    def test_expired_page_is_not_mixed_with_a_snapshot(self):
        client = Mock()
        client.job.return_value = {"processsnapshot": {"processstate": {
            "kind": "snapshot", "status": "OK", "snapshotId": SNAPSHOT}}}
        client.call.side_effect = [{"jobid": "job"}, {"processsnapshot": {
            "stale": True, "processstate": {"kind": "failure"}}}]
        with self.assertRaises(probe.ProbeError):
            probe.snapshot(client, VM)

    def test_duplicate_identity_across_pages_fails(self):
        client = Mock()
        client.job.return_value = {"processsnapshot": {"processstate": {
            "kind": "snapshot", "status": "OK", "snapshotId": SNAPSHOT}}}
        page = {"processsnapshot": {"stale": False, "count": 2, "processstate": {
            "snapshotId": SNAPSHOT, "processes": [{"identity": {"pid": 3}}]}}}
        client.call.side_effect = [{"jobid": "job"}, page, page]
        with self.assertRaises(probe.ProbeError):
            probe.snapshot(client, VM)

    def test_partial_collection_cannot_be_reported_complete(self):
        client = Mock()
        client.call.return_value = {"jobid": "job"}
        client.job.return_value = {"processsnapshot": {"processstate": {
            "kind": "snapshot", "status": "PARTIAL", "snapshotId": SNAPSHOT}}}
        with self.assertRaises(probe.ProbeError):
            probe.snapshot(client, VM)

    def test_non_fixture_service_is_rejected_before_mutation(self):
        client = Mock()
        client.call.return_value = {"virtualmachine": [{"name": "TestProcess"}]}
        with self.assertRaises(probe.ProbeError):
            probe.fixed_action(client, VM, {"action": "service.restart", "pid": 3,
                                           "serviceName": "critical-service"})
        self.assertEqual(client.call.call_count, 1)

    def test_lost_mutation_response_only_queries_the_saved_request(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "receipt.json"
            calls = []
            def call(command, **kwargs):
                calls.append((command, kwargs))
                if command == "listVirtualMachines":
                    return {"virtualmachine": [{"name": "TestProcess"}]}
                if command == "killVirtualMachineProcess":
                    self.assertEqual(json.loads(path.read_text())["requestId"], kwargs["requestid"])
                    raise probe.ProbeError("response lost")
                return {"processoperation": {"processstate": {
                    "state": "SUCCEEDED", "operationId": SNAPSHOT, "effect": "VERIFIED",
                    "postcondition": "TARGET_EXITED"}}}
            client = Mock()
            client.call.side_effect = call
            result = probe.fixed_action(client, VM, {"action": "process.kill", "pid": 3,
                          "snapshotId": SNAPSHOT, "receipt": str(path)})
            self.assertEqual(result["state"], "SUCCEEDED")
            self.assertEqual([c[0] for c in calls].count("killVirtualMachineProcess"), 1)
            self.assertEqual(calls[1][1]["requestid"], calls[2][1]["requestid"])

    def test_boolean_success_without_postcondition_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            client = Mock()
            client.call.side_effect = [
                {"virtualmachine": [{"name": "TestProcess"}]}, {},
                {"processoperation": {"processstate": {"state": "SUCCEEDED",
                  "effect": "VERIFIED", "postcondition": "NOT_CHECKED"}}}]
            with self.assertRaises(probe.ProbeError):
                probe.fixed_action(client, VM, {"action": "process.kill", "pid": 3,
                       "snapshotId": SNAPSHOT, "receipt": str(Path(folder) / "receipt.json")})


if __name__ == "__main__":
    unittest.main()
