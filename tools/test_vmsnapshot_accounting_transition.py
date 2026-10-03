# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("transition", pathlib.Path(__file__).with_name("vmsnapshot-accounting-transition.py"))
transition = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transition)


class TransitionTest(unittest.TestCase):
    def setUp(self):
        self.row = dict(id=1, uuid="volume-a", pool_id=1, vm_id=2, size=100,
                        chain_bytes=300, version="", audit_id="", snapshot_fingerprint="stable")
        self.audit = transition.plan([self.row])

    def test_dry_run_preserves_exact_nominal_counter_and_groups_pools(self):
        self.assertEqual(self.audit["excluded_nominal_bytes_by_pool"], {"1": 300})
        self.assertEqual(self.audit["volumes"][0]["chain_bytes"], 300)
        self.assertEqual(self.row["chain_bytes"], 300)

    def test_apply_and_rollback_are_idempotent_only_for_the_saved_audit(self):
        changed = transition.verify_rows(self.audit, [self.row], "apply")
        self.assertEqual(len(changed), 1)
        applied = dict(self.row, chain_bytes=0, version=transition.VERSION, audit_id=self.audit["audit_id"])
        self.assertEqual(transition.verify_rows(self.audit, [applied], "apply"), [])
        self.assertEqual(len(transition.verify_rows(self.audit, [applied], "rollback")), 1)
        self.assertEqual(transition.verify_rows(self.audit, [self.row], "rollback"), [])
        with self.assertRaises(ValueError):
            transition.verify_rows(self.audit, [dict(applied, audit_id="another-audit")], "rollback")

    def test_resize_migration_snapshot_changes_or_removed_volume_prevent_overwrite(self):
        for field, value in [("size", 200), ("pool_id", 9), ("snapshot_fingerprint", "changed"), ("uuid", "other")]:
            with self.assertRaises(ValueError):
                transition.verify_rows(self.audit, [dict(self.row, **{field: value})], "apply")
        with self.assertRaises(ValueError):
            transition.verify_rows(self.audit, [], "apply")

    def test_mutations_are_transactional_and_conditional_on_counter_and_audit_identity(self):
        sql = transition.mutation_sql(self.audit, [self.row], "apply")
        self.assertTrue(sql.startswith("START TRANSACTION;"))
        self.assertTrue(sql.endswith("COMMIT;"))
        self.assertIn("FOR UPDATE", sql)
        self.assertIn("v.vm_snapshot_chain_size=300", sql)
        self.assertIn("NOT EXISTS", sql)
        rollback = transition.mutation_sql(self.audit, [self.row], "rollback")
        self.assertIn(transition.sql_text(self.audit["audit_id"]), rollback)
        self.assertIn("JOIN volume_details a", rollback)


if __name__ == "__main__":
    unittest.main()
