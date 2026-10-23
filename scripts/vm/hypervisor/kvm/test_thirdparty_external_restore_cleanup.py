#!/usr/bin/env python3
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

"""Rejected metadata cleanup ownership, retries and replay safety."""
import copy
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import thirdparty_external_restore_cleanup as helper


class RejectedMetadataCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()
        self.receipts = self.base / 'receipts'
        self.root = self.base / 'ablestack-netbackup/i-2-318-VM/2026.10.23.10.25.03.498'
        self.plan = dict(version=1, id='external-restore-12345678-1234-1234-1234-123456789012', provider='ablestack-netbackup',
                         jobId='121', externalId='ablecube4_1792718956', backupUuid='backup-uuid', vmName='i-2-318-VM', vmUuid='vm-uuid',
                         proof=dict(hostName='ablecube4', startedAt=int((time.time()-10)*1000), endedAt=int((time.time()+1)*1000)),
                         destinations=[dict(path=str(self.root), configuration=dict(rootPath=str(self.base)))])
        self.plan['manifest'] = dict(version=1, provider=self.plan['provider'], backupUuid=self.plan['backupUuid'],
                                     vmName=self.plan['vmName'], timestamp=self.root.name, complete=True, backupType='FULL', volumes=[])
        mounts = patch.object(helper, 'validate_job_directory')
        self.validate = mounts.start()
        self.addCleanup(mounts.stop)
        self.write_metadata()

    def write_metadata(self):
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / 'checkpoints').mkdir(exist_ok=True)
        (self.root / 'checkpoints/disk.xml').write_text('<domaincheckpoint/>')
        (self.root / 'backup-manifest.json').write_text(json.dumps(self.plan['manifest']))
        (self.root / 'domain-config.xml').write_text('<domain><name>i-2-318-VM</name><uuid>vm-uuid</uuid></domain>')
        (self.root / '.staging.complete').write_text('volume_pipeline=true\nbackup_uuid=backup-uuid\n')
        (self.root / 'dominfo.xml').write_text('domain information')

    def test_removes_owned_metadata_for_both_providers_without_touching_backup_storage(self):
        for provider in ('ablestack-netbackup', 'ablestack-veeam'):
            with self.subTest(provider=provider):
                plan = copy.deepcopy(self.plan)
                plan['provider'] = provider
                plan['manifest']['provider'] = provider
                root = self.base / provider / self.plan['vmName'] / self.root.name
                plan['destinations'][0]['path'] = str(root)
                root.mkdir(parents=True, exist_ok=True)
                (root / 'backup-manifest.json').write_text(json.dumps(plan['manifest']))
                (root / 'domain-config.xml').write_text('<domain><name>i-2-318-VM</name><uuid>vm-uuid</uuid></domain>')
                (root / '.staging.complete').write_text('volume_pipeline=true\nbackup_uuid=backup-uuid\n')
                helper.cleanup(plan, self.receipts / provider)
                self.assertFalse(root.exists())
                self.validate.assert_called()

    def test_completed_replay_preserves_a_later_restore_to_the_same_directory(self):
        helper.cleanup(self.plan, self.receipts)
        self.write_metadata()
        helper.cleanup(self.plan, self.receipts)
        self.assertTrue((self.root / 'backup-manifest.json').exists())

    def test_never_deletes_volume_images_unknown_files_symlinks_or_hardlinks(self):
        for name in ('root.disk.raw', 'root.disk.qcow2', 'unknown.txt'):
            with self.subTest(name=name):
                extra = self.root / name
                extra.write_text('must remain')
                with self.assertRaisesRegex(RuntimeError, 'unknown content'):
                    helper.cleanup(self.plan, self.receipts)
                self.assertTrue((self.root / 'backup-manifest.json').exists())
                extra.unlink()
        target = self.base / 'important.xml'
        target.write_text('must remain')
        link = self.root / 'domblklist.xml'
        link.symlink_to(target)
        with self.assertRaises(RuntimeError):
            helper.cleanup(self.plan, self.receipts)
        link.unlink()
        os.link(target, link)
        with self.assertRaisesRegex(RuntimeError, 'individually owned'):
            helper.cleanup(self.plan, self.receipts)
        self.assertTrue(target.exists())

    def test_stale_metadata_unrelated_vm_and_path_mismatches_are_preserved(self):
        for change in ('time', 'vm', 'path', 'backup'):
            with self.subTest(change=change):
                plan = copy.deepcopy(self.plan)
                if change == 'time':
                    plan['proof']['startedAt'] -= 3600000
                    plan['proof']['endedAt'] -= 3600000
                elif change == 'vm':
                    plan['vmUuid'] = 'different-vm'
                elif change == 'path':
                    plan['destinations'][0]['configuration']['rootPath'] = '/different-root'
                else:
                    plan['manifest']['backupUuid'] = 'different-backup'
                with self.assertRaises(RuntimeError):
                    helper.cleanup(plan, self.receipts)
                self.assertTrue((self.root / 'backup-manifest.json').exists())

    def test_partial_unlink_retries_only_the_original_inventory(self):
        original = helper.os.unlink
        counter = [0]
        def interrupted(path, *args, **kwargs):
            counter[0] += 1
            if counter[0] == 2:
                raise OSError('temporary filesystem error')
            return original(path, *args, **kwargs)
        with patch.object(helper.os, 'unlink', side_effect=interrupted):
            with self.assertRaises(OSError):
                helper.cleanup(self.plan, self.receipts)
        helper.cleanup(self.plan, self.receipts)
        self.assertFalse(self.root.exists())

    def test_partial_cleanup_does_not_adopt_changed_metadata_on_retry(self):
        with patch.object(helper.os, 'unlink', side_effect=OSError('temporary failure')):
            with self.assertRaises(OSError):
                helper.cleanup(self.plan, self.receipts)
        (self.root / 'domain-config.xml').write_text('new restore operation')
        with self.assertRaisesRegex(RuntimeError, 'later operation changed'):
            helper.cleanup(self.plan, self.receipts)
        self.assertTrue((self.root / 'backup-manifest.json').exists())

    def test_changed_receipt_identity_is_rejected(self):
        helper.cleanup(self.plan, self.receipts)
        changed = copy.deepcopy(self.plan)
        changed['jobId'] = '122'
        with self.assertRaisesRegex(RuntimeError, 'another external restore'):
            helper.cleanup(changed, self.receipts)

    def test_absent_destination_receipt_does_not_adopt_later_files(self):
        self.root.rename(self.root.with_name('earlier-metadata'))
        helper.cleanup(self.plan, self.receipts)
        # An absent destination cannot authorize deleting files created later.
        self.write_metadata()
        with patch.object(helper, 'inventory', side_effect=AssertionError('must not recapture')):
            helper.cleanup(self.plan, self.receipts)


if __name__ == '__main__':
    unittest.main()
