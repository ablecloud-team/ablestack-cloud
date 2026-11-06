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

"""NetBackup restore entry creation and compatibility with the actual backup pipeline."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import thirdparty_restore_metadata as metadata
import thirdparty_volume_backup as pipeline


class RestoreEntryTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()

    def test_online_pipeline_writes_entry_before_final_metadata_transfer_only_for_netbackup(self):
        for provider in ('ablestack-netbackup', 'ablestack-veeam', 'ablestack-commvault'):
            with self.subTest(provider=provider):
                base = self.root / provider
                job = base / 'backup-id'
                stage = base / 'stage'
                job.mkdir(parents=True)
                stage.mkdir()
                worker = pipeline.Backup.__new__(pipeline.Backup)
                worker.root = stage
                worker.job = job
                worker.source_start = job / 'source-start.json'
                worker.source_start.write_text(json.dumps(dict(version=1, backupUuid='backup-id', state='NOT_STARTED')))
                worker.engine = job / 'engine'
                worker.offline = None
                worker.vm = 'i-2-25-VM'
                worker.pull = False
                worker.count = 1
                worker.timeout = 30
                worker.plan = dict(rbd=True, sourceHost='host-1', providerStep='TRANSFER')
                worker.manifest = dict(version=1, provider=provider, backupUuid='backup-id', vmName=worker.vm, backupType='FULL',
                                       volumes=[dict(uuid='disk-id', chain=[dict(backupUuid='backup-id', path=str(stage / 'root.raw'))])])
                worker.check_cancel = Mock()
                worker.reserve = Mock()
                worker.progress = Mock()
                worker.begin_rbd = Mock()
                worker.export_rbd = lambda index, path: path.write_bytes(b'payload')
                (job / 'volume-start').touch()
                transferred = []
                def transfer(index, artifact, is_metadata=False):
                    if is_metadata:
                        self.assertEqual(provider == 'ablestack-netbackup', (stage / metadata.NETBACKUP_ENTRY).exists())
                        if provider == 'ablestack-netbackup':
                            self.assertEqual(metadata.NETBACKUP_ENTRY, artifact['restoreEntry'])
                            texts = metadata.parse_netbackup_entry(metadata.read_metadata_file(stage / metadata.NETBACKUP_ENTRY))
                            self.assertTrue(json.loads(texts['backup-manifest.json'])['complete'])
                            self.assertTrue(json.loads(texts['backup-manifest.json'])['volumes'][0]['chain'][0]['completed'])
                            for name in metadata.REQUIRED_METADATA:
                                self.assertEqual((stage / name).read_text(), texts[name])
                    else:
                        artifact.update(completed=True, externalId='host-1_123', jobId='123')
                    transferred.append(is_metadata)
                worker.transfer = transfer
                with patch.object(pipeline, 'run', return_value='<domain><name>i-2-25-VM</name><uuid>vm-id</uuid></domain>'):
                    worker.execute()
                self.assertEqual([False, True], transferred)

    def test_entry_rejects_foreign_types_and_extra_embedded_files(self):
        valid = dict(version=1, kind='MOLD_VM_RESTORE', provider='ablestack-netbackup', files=dict.fromkeys(metadata.REQUIRED_METADATA, 'text'))
        self.assertEqual(valid['files'], metadata.parse_netbackup_entry(json.dumps(valid)))
        for field, value in (('provider', 'ablestack-veeam'), ('kind', 'other'), ('version', 2), ('files', {'root.raw': 'payload'})):
            with self.subTest(field=field), self.assertRaises(ValueError):
                metadata.parse_netbackup_entry(json.dumps(dict(valid, **{field: value})))

    def test_metadata_reader_rejects_links_without_modifying_files(self):
        file = self.root / 'metadata'
        file.write_text('original')
        link = self.root / 'link'
        link.symlink_to(file)
        with self.assertRaises(OSError):
            metadata.read_metadata_file(link)
        link.unlink()
        os.link(file, link)
        with self.assertRaises(ValueError):
            metadata.read_metadata_file(file)
        self.assertEqual('original', file.read_text())


if __name__ == '__main__':
    unittest.main()
