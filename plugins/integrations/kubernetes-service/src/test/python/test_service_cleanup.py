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

source = pathlib.Path(__file__).resolve().parents[2] / "main/resources/script/cleanup-owned-services"
loader = importlib.machinery.SourceFileLoader("service_cleanup", str(source))
spec = importlib.util.spec_from_loader(loader.name, loader)
cleanup = importlib.util.module_from_spec(spec)
loader.exec_module(cleanup)


def service(uid="service-uid", deleting=True):
    meta = {"name": "web", "namespace": "app", "uid": uid, "resourceVersion": "42",
            "finalizers": ["service.kubernetes.io/load-balancer-cleanup", "example.org/retain"]}
    if deleting:
        meta["deletionTimestamp"] = "2026-10-06T19:00:00Z"
    return {"metadata": meta, "spec": {"type": "LoadBalancer"}}


class ServiceCleanupTest(unittest.TestCase):
    def journal(self, tmp):
        path = pathlib.Path(tmp) / "receipt.json"
        cleanup.save_receipt(path, {"services": cleanup.services_receipt([service()])})
        return path

    def test_deletion_records_the_original_uid_before_every_mutation(self):
        api = mock.Mock()
        api.list.return_value = [service(deleting=False)]
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "receipt.json"
            def delete(version, resource, obj):
                receipt = json.loads(path.read_text())
                self.assertEqual(obj["metadata"]["uid"], receipt["services"][0]["uid"])
            api.delete.side_effect = delete
            with mock.patch("builtins.print"):
                cleanup.request_deletion(api, path)
            api.delete.assert_called_once()

    def test_finalize_uses_uid_and_resource_version_cas_and_preserves_foreign_finalizers(self):
        api = mock.Mock()
        api.request.return_value = service()
        api.list.return_value = []
        with tempfile.TemporaryDirectory() as tmp, mock.patch("builtins.print"):
            cleanup.finish_deletion(api, self.journal(tmp), True)
        body = api.request.call_args.args[2]
        self.assertEqual({"op": "test", "path": "/metadata/uid", "value": "service-uid"}, body[0])
        self.assertEqual({"op": "test", "path": "/metadata/resourceVersion", "value": "42"}, body[1])
        self.assertEqual(["example.org/retain"], body[2]["value"])
        api.wait_absent.assert_called_once()

    def test_legacy_wait_does_not_remove_any_finalizer(self):
        api = mock.Mock()
        api.request.return_value = service()
        api.list.return_value = []
        with tempfile.TemporaryDirectory() as tmp, mock.patch("builtins.print"):
            cleanup.finish_deletion(api, self.journal(tmp), False)
        self.assertEqual(1, api.request.call_count)
        self.assertEqual("GET", api.request.call_args.args[0])

    def test_replacement_uid_is_preserved(self):
        api = mock.Mock()
        api.request.return_value = service(uid="replacement")
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(cleanup.CleanupError, "UID changed"):
                cleanup.finish_deletion(api, self.journal(tmp), True)
        self.assertEqual(1, api.request.call_count)
        api.wait_absent.assert_not_called()

    def test_live_service_finalizer_is_never_removed(self):
        api = mock.Mock()
        api.request.return_value = service(deleting=False)
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(cleanup.CleanupError, "not requested"):
                cleanup.finish_deletion(api, self.journal(tmp), True)
        self.assertEqual(1, api.request.call_count)

    def test_new_service_blocks_node_removal(self):
        api = mock.Mock()
        api.request.return_value = None
        api.list.return_value = [service(uid="new")]
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(cleanup.CleanupError, "New LoadBalancer"):
                cleanup.finish_deletion(api, self.journal(tmp), True)

    def test_receipts_exclude_service_env_and_annotations(self):
        obj = service()
        obj["metadata"]["annotations"] = {"credential": "private"}
        self.assertNotIn("private", json.dumps(cleanup.services_receipt([obj])))


if __name__ == "__main__":
    unittest.main()
