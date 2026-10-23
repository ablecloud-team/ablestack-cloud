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

"""Reject shared RAW before source mutation; retain the RBD and QCOW2 paths."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import thirdparty_backup_source_support as support
import thirdparty_volume_backup as backup


class SourceSupportTest(unittest.TestCase):
    def test_actual_raw_format_is_blocked_even_with_qcow2_extension(self):
        with patch.object(support, "inspect", side_effect=["gfs2\n", '{"format":"raw"}']):
            with self.assertRaisesRegex(RuntimeError, "GFS2_RAW_OFFLINE_FULL_REQUIRED"):
                support.require_supported_sources(["/gfs/disk.qcow2"])

    def test_gfs2_qcow2_is_accepted_even_with_raw_extension(self):
        with patch.object(support, "inspect", side_effect=["gfs2\n", '{"format":"qcow2"}']):
            support.require_supported_sources(["/gfs/disk.raw"])

    def test_rbd_is_not_inspected_including_when_staging_is_gfs2(self):
        with patch.object(support, "inspect") as inspect:
            support.require_supported_sources(["rbd:pool/image"])
        inspect.assert_not_called()

    def test_nfs_source_is_outside_the_gfs2_restriction(self):
        with patch.object(support, "inspect", return_value="nfs4\n") as inspect:
            support.require_supported_sources(["/nfs/disk.raw"])
        self.assertEqual(1, inspect.call_count)

    def test_raw_second_disk_rejects_the_complete_request(self):
        with patch.object(support, "inspect", side_effect=["gfs2", '{"format":"qcow2"}',
                                                          "gfs2", '{"format":"raw"}']):
            with self.assertRaisesRegex(RuntimeError, "online/checkpoint path is rejected"):
                support.require_supported_sources(["/gfs/root", "/gfs/shared"])

    def test_unknown_image_format_does_not_fall_back_to_qcow2(self):
        for data in ("{}", "not-json", "[]", '{"format":"vmdk"}'):
            with self.subTest(data=data), patch.object(support, "inspect", side_effect=["gfs2", data]):
                with self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_UNKNOWN"):
                    support.require_supported_sources(["/gfs/disk"])

    def test_missing_or_ambiguous_filesystem_is_rejected(self):
        for filesystem in ("", "gfs2\nxfs"):
            with self.subTest(filesystem=filesystem), patch.object(support, "inspect", return_value=filesystem):
                with self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_UNKNOWN"):
                    support.require_supported_sources(["/gfs/disk"])

    def test_tool_failure_is_not_treated_as_supported(self):
        with patch.object(support.subprocess, "run", return_value=Mock(returncode=1)):
            with self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_UNKNOWN"):
                support.require_supported_sources(["/gfs/disk"])

    def test_shared_image_probe_uses_read_only_force_share(self):
        with patch.object(support, "inspect", side_effect=["gfs2", '{"format":"qcow2"}']) as inspect:
            support.require_supported_sources(["/gfs/disk"])
        inspect.assert_any_call(["qemu-img", "info", "--force-share", "--output=json", "/gfs/disk"])

    def test_external_providers_stop_before_nbd_preflight(self):
        for provider in ("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam"):
            for backup_type in ("FULL", "INCREMENTAL"):
                with self.subTest(provider=provider, backup_type=backup_type):
                    worker = backup.Backup.__new__(backup.Backup)
                    worker.plan = {"diskPaths": ["/gfs/shared.raw"]}
                    worker.manifest = {"provider": provider, "backupType": backup_type}
                    with patch.object(support, "inspect", side_effect=["gfs2", '{"format":"raw"}']), \
                            patch.object(backup, "preflight") as nbd:
                        with self.assertRaisesRegex(RuntimeError, "GFS2_RAW_OFFLINE_FULL_REQUIRED"):
                            worker.preflight_qcow2()
                    nbd.assert_not_called()

    def test_nas_rejects_before_mount_freeze_or_checkpoint(self):
        source = Path(__file__).with_name("ablestack_nasbackup.sh").read_text()
        functions = source.split("\nwhile [[ $# -gt 0 ]]; do", 1)[0].replace(
            'source "$(dirname "${BASH_SOURCE[0]}")/ablestack_backup_safety.sh"', ":")
        for mode in ("FULL", "INCREMENTAL"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                unexpected = Path(directory) / "unexpected"
                script = functions + "\n" + "\n".join([
                    "trap - ERR", "trap - EXIT", "logFile=/dev/null",
                    "BACKUP_TYPE=" + mode,
                    "check_backup_sources() { echo '" + support.UNSUPPORTED + "' >&2; return 1; }",
                    'mount_operation() { touch "' + str(unexpected) + '"; }',
                    'ablestack_freeze_guest() { touch "' + str(unexpected) + '"; }',
                    "backup_running_vm",
                ])
                result = subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=10)
                self.assertEqual(1, result.returncode)
                self.assertIn("GFS2_RAW_OFFLINE_FULL_REQUIRED", result.stderr)
                self.assertFalse(unexpected.exists())


if __name__ == "__main__":
    unittest.main()
