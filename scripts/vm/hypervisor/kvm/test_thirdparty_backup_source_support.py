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
import errno
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import thirdparty_backup_source_support as support
import thirdparty_volume_backup as backup


class SourceSupportTest(unittest.TestCase):
    def test_source_health_cli_preserves_paths_with_commas_and_the_legacy_rbd_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "disk with,comma.raw"
            source.write_bytes(b"existing source")
            findmnt = root / "findmnt"
            findmnt.write_text("#!/bin/sh\nprintf 'gfs2\\n'\n")
            findmnt.chmod(0o755)
            environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"])
            for arguments in (["--health-paths", str(source)], ["--disk-paths", "rbd:pool/image"]):
                result = subprocess.run([sys.executable, support.__file__, *arguments],
                                        env=environment, text=True, capture_output=True, timeout=10)
                self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b"existing source", source.read_bytes())

    def test_admission_probe_reads_raw_without_changing_it_or_requesting_a_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source disk,raw"
            source.write_bytes(b"raw-source-data")
            with patch.object(support, "inspect", return_value="gfs2\n") as inspect:
                support.require_accessible_sources([str(source)])
            self.assertEqual(b"raw-source-data", source.read_bytes())
            inspect.assert_called_once_with(["findmnt", "--noheadings", "--output", "FSTYPE", "--target", str(source)])

    def test_admission_probe_rejects_withdrawn_source_metadata(self):
        with patch.object(support, "inspect", return_value="gfs2\n"), \
                patch.object(support.os, "stat", side_effect=OSError(errno.EIO, "Input/output error")):
            with self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_UNAVAILABLE.*Input/output error"):
                support.require_accessible_sources(["/gfs/source"])

    def test_admission_probe_rejects_missing_empty_and_directory_sources(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(support, "inspect", return_value="nfs4\n"):
            empty = Path(directory) / "empty"
            empty.touch()
            for path in (Path(directory) / "missing", empty, Path(directory)):
                with self.subTest(path=path), self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_UNAVAILABLE"):
                    support.require_accessible_sources([str(path)])

    def test_admission_probe_does_not_ignore_an_unknown_mount(self):
        with patch.object(support, "inspect", side_effect=RuntimeError("findmnt source inspection failed")):
            with self.assertRaisesRegex(RuntimeError, "findmnt source inspection failed"):
                support.require_accessible_sources(["/gfs/source"])

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
        with patch.object(support.subprocess, "run", return_value=Mock(returncode=1, stderr="")):
            with self.assertRaisesRegex(RuntimeError, "BACKUP_SOURCE_UNKNOWN"):
                support.require_supported_sources(["/gfs/disk"])

    def test_mount_failure_reports_exit_code_and_diagnostic_without_continuing(self):
        result = subprocess.CompletedProcess([], 1, "", "findmnt: /gfs/disk: No such file or directory\n")
        with patch.object(support.subprocess, "run", return_value=result) as run:
            with self.assertRaisesRegex(RuntimeError, "findmnt source inspection failed \\(exit=1\\): findmnt: /gfs/disk: No such file"):
                support.require_supported_sources(["/gfs/disk"])
            self.assertEqual(1, run.call_count)

    def test_source_error_diagnostics_are_bounded_and_single_line(self):
        result = subprocess.CompletedProcess([], 2, "", "line one\n" + "x" * 1000)
        with patch.object(support.subprocess, "run", return_value=result):
            with self.assertRaises(RuntimeError) as failure:
                support.require_supported_sources(["/gfs/disk"])
        message = str(failure.exception)
        self.assertNotIn("\n", message)
        self.assertIn("exit=2", message)
        self.assertLess(len(message), 600)

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
