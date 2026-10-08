#!/usr/bin/env python3

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
import json
from pathlib import Path
import tempfile
import unittest
import uuid

SOURCE=Path(__file__).resolve().parents[2]/'systemvm/debian/usr/local/lib/ablestack-storage/template_maintenance.py'
spec=importlib.util.spec_from_file_location('maintenance',SOURCE)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class StorageTemplateMaintenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.scope={'instanceUuid':str(uuid.uuid4()),'templateUpgradeUuid':str(uuid.uuid4()),'operationUuid':str(uuid.uuid4()),'revision':10}
        self.verified={'instanceUuid':self.scope['instanceUuid'],'operationUuid':str(uuid.uuid4()),'revision':9,'configurationSha256':'a'*64}
        self.observed={'generation':self.verified,'configurationSha256':'a'*64,'generationStatus':'IN_SYNC','pendingOperationUuid':None}
        self.maintenance=module.Maintenance(Path(self.temp.name)/'state',lambda:self.observed)

    def test_same_scope_enter_and_durable_restart_status_are_idempotent(self):
        self.maintenance.enter(self.scope)
        self.maintenance.enter(self.scope)
        reopened=module.Maintenance(self.maintenance.root,lambda:self.observed)
        self.assertEqual(self.scope,reopened.status()['scope'])
        self.assertTrue(reopened.status()['bootHeld'])
        self.assertEqual(0o600,self.maintenance.marker.stat().st_mode&0o777)

    def test_known_previous_same_row_scope_can_adopt_forward_for_manual_rollback(self):
        self.maintenance.enter(self.scope)
        next_scope=dict(self.scope,operationUuid=str(uuid.uuid4()),revision=11)
        result=self.maintenance.enter(dict(next_scope,expectedPreviousScope=self.scope))
        self.assertEqual(next_scope,result['scope'])

    def test_foreign_or_absent_previous_marker_cannot_be_overwritten(self):
        with self.assertRaises(ValueError):
            self.maintenance.enter(dict(self.scope,expectedPreviousScope=self.scope))
        self.maintenance.enter(self.scope)
        foreign=dict(self.scope,templateUpgradeUuid=str(uuid.uuid4()),operationUuid=str(uuid.uuid4()),revision=11)
        with self.assertRaises(ValueError):
            self.maintenance.enter(dict(foreign,expectedPreviousScope=self.scope))
        self.assertEqual(self.scope,self.maintenance.status()['scope'])

    def test_source_generation_revision_nine_can_release_root_job_ten_and_replay_receipt(self):
        self.maintenance.enter(self.scope)
        request=dict(self.scope,verifiedGeneration=self.verified)
        self.assertTrue(self.maintenance.release(request)['released'])
        self.assertFalse(self.maintenance.marker.exists())
        self.assertTrue(self.maintenance.release(request)['released'])
        self.assertFalse(self.maintenance.status()['bootHeld'])

    def test_foreign_absent_marker_release_without_receipt_is_rejected(self):
        with self.assertRaises(ValueError):
            self.maintenance.release(dict(self.scope,verifiedGeneration=self.verified))

    def test_pending_or_drifted_generation_cannot_release(self):
        self.maintenance.enter(self.scope)
        request=dict(self.scope,verifiedGeneration=self.verified)
        self.observed['pendingOperationUuid']=str(uuid.uuid4())
        with self.assertRaises(ValueError):self.maintenance.release(request)
        self.observed['pendingOperationUuid']=None;self.observed['configurationSha256']='b'*64
        with self.assertRaises(ValueError):self.maintenance.release(request)
        self.assertTrue(self.maintenance.marker.exists())

    def test_status_missing_directory_is_readonly_and_open_replacement_is_rejected(self):
        self.assertFalse(self.maintenance.status()['bootHeld'])
        self.assertFalse(self.maintenance.root.exists())
        self.maintenance.enter(self.scope)
        original=module.os.fstat
        from types import SimpleNamespace
        def changed(descriptor):
            observed=original(descriptor)
            return SimpleNamespace(**{key:getattr(observed,key)+(1 if key=='st_ino' else 0) for key in ('st_dev','st_ino','st_uid','st_gid','st_mode','st_size')})
        module.os.fstat=changed
        try:
            with self.assertRaisesRegex(ValueError,'changed while opening'):
                self.maintenance.status()
        finally:
            module.os.fstat=original

    def test_unprotected_or_symlink_marker_is_rejected(self):
        self.maintenance.enter(self.scope)
        self.maintenance.marker.chmod(0o644)
        with self.assertRaises(ValueError):self.maintenance.status()
        self.maintenance.marker.unlink()
        foreign=Path(self.temp.name)/'foreign';foreign.write_text('{}')
        self.maintenance.marker.symlink_to(foreign)
        with self.assertRaises(ValueError):self.maintenance.status()

if __name__=='__main__':unittest.main()
