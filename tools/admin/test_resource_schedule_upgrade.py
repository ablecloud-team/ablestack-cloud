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
import importlib.util
import itertools
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('upgrade', Path(__file__).with_name('resource-schedule-upgrade.py'))
upgrade = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upgrade)


class ScheduleUpgradeTest(unittest.TestCase):
    def test_only_complete_schemas_are_accepted(self):
        tables = sorted(upgrade.OLD | upgrade.NEW)
        for flags in itertools.product((False, True), repeat=len(tables)):
            present = {name for name, flag in zip(tables, flags) if flag}
            with self.subTest(tables=present):
                if present in (upgrade.OLD, upgrade.NEW):
                    self.assertEqual(upgrade.classify(present | {'unrelated'}),
                                     'legacy' if present == upgrade.OLD else 'resource')
                else:
                    with self.assertRaises(ValueError):
                        upgrade.classify(present)

    def test_read_only_is_default(self):
        with patch('sys.argv', ['upgrade']), patch.object(upgrade, 'sql', return_value='vm_schedule\nvm_scheduled_job') as sql:
            upgrade.main()
        sql.assert_called_once_with('cloud', 'SHOW TABLES')

    def test_rehearsal_cannot_target_cloud(self):
        with patch('sys.argv', ['upgrade', '--rehearsal']), patch.object(upgrade, 'sql') as sql:
            with self.assertRaises(SystemExit):
                upgrade.main()
        sql.assert_not_called()

    def test_active_management_refuses_apply(self):
        with patch('sys.argv', ['upgrade', '--apply', '--backup-dir', '/unused']), \
                patch.object(upgrade, 'sql', return_value='vm_schedule\nvm_scheduled_job') as sql, \
                patch.object(upgrade.subprocess, 'check_output', return_value='active\n'):
            with self.assertRaisesRegex(ValueError, 'must be stopped'):
                upgrade.main()
        sql.assert_called_once()

    def test_migrated_schema_is_validated_without_writes(self):
        with patch('sys.argv', ['upgrade', '--apply']), \
                patch.object(upgrade, 'sql', return_value='\n'.join(sorted(upgrade.NEW))) as sql, \
                patch.object(upgrade, 'validate_resource') as validate:
            upgrade.main()
        validate.assert_called_once_with('cloud')
        sql.assert_called_once()


if __name__ == '__main__':
    unittest.main()
