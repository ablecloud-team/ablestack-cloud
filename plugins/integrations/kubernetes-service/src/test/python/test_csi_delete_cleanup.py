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
import importlib.machinery
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock

source = pathlib.Path(__file__).resolve().parents[2] / "main/resources/script/delete-pv-reclaimpolicy-delete"
loader = importlib.machinery.SourceFileLoader("csi_cleanup", str(source))
spec = importlib.util.spec_from_loader(loader.name, loader)
cleanup = importlib.util.module_from_spec(spec)
loader.exec_module(cleanup)


def pv(name="data", policy="Delete", driver=cleanup.DRIVER, dynamic=True):
    return {"metadata": {"name": name, "uid": name + "-pv-uid",
                         "annotations": {"pv.kubernetes.io/provisioned-by": driver} if dynamic else {}},
            "spec": {"persistentVolumeReclaimPolicy": policy, "csi": {"driver": driver, "volumeHandle": name + "-handle"},
                     "claimRef": {"namespace": "app", "name": name, "uid": name + "-claim-uid"}}}


def pvc(name="data"):
    return {"metadata": {"namespace": "app", "name": name, "uid": name + "-claim-uid"}, "spec": {"volumeName": name}}


def workload(resource="deployments", claim="data"):
    spec = {"volumes": [{"persistentVolumeClaim": {"claimName": claim}}]}
    if resource == "cronjobs":
        spec = {"jobTemplate": {"spec": {"template": {"spec": spec}}}}
    elif resource != "pods":
        spec = {"template": {"spec": spec}}
    return {"metadata": {"namespace": "app", "name": resource + "-user", "uid": resource + "-uid"}, "spec": spec}


class FakeAPI:
    def __init__(self):
        self.objects = {"persistentvolumes": [pv()], "persistentvolumeclaims": [pvc()], "pods": [workload("pods")]}
        self.deletions = []
        self.block = False

    def list(self, version, resource):
        return copy.deepcopy(self.objects.get(resource, []))

    def delete(self, version, resource, obj):
        self.deletions.append((resource, obj["metadata"]["uid"]))
        if not self.block:
            self.objects[resource] = [o for o in self.objects.get(resource, []) if o["metadata"]["uid"] != obj["metadata"]["uid"]]

    def wait_absent(self, version, resource, obj):
        if self.block:
            raise cleanup.CleanupError("CSI/finalizer timeout")


class CsiCleanupTest(unittest.TestCase):
    def test_exact_claim_matching_for_all_workload_kinds(self):
        workloads = [(v, r, [workload(r), workload(r, "database")]) for v, r in cleanup.WORKLOADS]
        volumes, selected = cleanup.deletion_plan([pv()], [pvc()], workloads)
        self.assertEqual(1, len(volumes))
        self.assertEqual(7, len(selected))
        self.assertTrue(all(cleanup.claim_names(r, obj) == {"data"} for v, r, obj in selected))

    def test_retain_external_and_manual_static_volumes_are_preserved(self):
        volumes, workloads = cleanup.deletion_plan([pv(policy="Retain"), pv(driver="nfs.csi.k8s.io"), pv(dynamic=False)], [pvc()], [])
        self.assertEqual([], volumes)
        self.assertEqual([], workloads)

    def test_reused_claim_uid_blocks_before_mutation(self):
        claim = pvc()
        claim["metadata"]["uid"] = "replacement-uid"
        with self.assertRaisesRegex(cleanup.CleanupError, "identity changed"):
            cleanup.deletion_plan([pv()], [claim], [])

    def test_unbound_delete_volume_is_included_for_retry(self):
        volume = pv()
        del volume["spec"]["claimRef"]
        volumes, _ = cleanup.deletion_plan([volume], [], [])
        self.assertIsNone(volumes[0]["claim"])

    def test_uid_precondition_is_sent_and_never_force_removes_finalizers(self):
        api = cleanup.API("unused", 1)
        api.request = mock.Mock(return_value={})
        obj = pvc()
        api.delete("v1", "persistentvolumeclaims", obj)
        body = api.request.call_args.args[2]
        self.assertEqual({"uid": "data-claim-uid"}, body["preconditions"])
        self.assertNotIn("finalizers", json.dumps(body))
        self.assertEqual("Foreground", body["propagationPolicy"])

    def test_success_and_repeat_keep_volume_handles_in_durable_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            journal = pathlib.Path(tmp) / "receipt.json"
            api = FakeAPI()
            with mock.patch("builtins.print"):
                cleanup.cleanup(api, journal)
                cleanup.cleanup(api, journal)
            receipt = json.loads(journal.read_text())
            self.assertTrue(receipt["completed"])
            self.assertEqual("data-handle", receipt["volumes"][0]["handle"])
            self.assertEqual(["pods", "persistentvolumeclaims", "persistentvolumes"], [r for r, uid in api.deletions])
            self.assertEqual(0o600, journal.stat().st_mode & 0o777)

    def test_timeout_leaves_incomplete_receipt_and_no_false_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            journal = pathlib.Path(tmp) / "receipt.json"
            api = FakeAPI()
            api.block = True
            with self.assertRaisesRegex(cleanup.CleanupError, "timeout"):
                cleanup.cleanup(api, journal)
            self.assertFalse(json.loads(journal.read_text())["completed"])
            self.assertEqual([("pods", "pods-uid")], api.deletions)

    def test_name_reuse_during_wait_fails_closed(self):
        api = cleanup.API("unused", 1)
        replacement = pvc()
        replacement["metadata"]["uid"] = "replacement-uid"
        api.request = mock.Mock(return_value=replacement)
        with self.assertRaisesRegex(cleanup.CleanupError, "name reused"):
            api.wait_absent("v1", "persistentvolumeclaims", pvc())


if __name__ == "__main__":
    unittest.main()
