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

"""Progress stalls, unknown metrics and independent worker supervision."""
import os
import contextlib
import io
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock

import ablestack_backup_progress as progress


class ProgressTest(unittest.TestCase):
    def test_repeated_and_decreasing_measurements_do_not_extend_the_deadline(self):
        now = [0]
        guard = progress.ProgressDeadline("copy", 10, clock=lambda: now[0])
        guard.observe(100)
        now[0] = 9
        guard.observe(100)
        guard.observe(50)
        guard.check()
        now[0] = 10
        with self.assertRaisesRegex(progress.NoProgressError, "NO_PROGRESS"):
            guard.check()

    def test_increasing_bytes_keep_slow_large_copies_running(self):
        now = [0]
        guard = progress.ProgressDeadline("copy", 10, clock=lambda: now[0])
        for timestamp in range(0, 100, 5):
            now[0] = timestamp
            guard.observe(timestamp)
            guard.check()

    def test_unknown_metrics_warn_once_without_canceling(self):
        now = [0]
        warning = Mock()
        guard = progress.ProgressDeadline("external transfer", 10, clock=lambda: now[0], warning=warning)
        guard.observe(float("nan"))
        now[0] = 100
        guard.check()
        guard.check()
        warning.assert_called_once()
        guard.observe(0)
        now[0] = 109
        guard.check()
        now[0] = 110
        with self.assertRaises(progress.NoProgressError):
            guard.check()

    def test_parsers_require_native_progress_not_log_or_file_activity(self):
        self.assertEqual(12.34, progress.percent("qemu", "(12.34/100%)\r(12.34/100%)"))
        self.assertEqual(5, progress.percent("rbd", "Exporting image: 5% complete..."))
        self.assertIsNone(progress.percent("rbd", "processedBytes=1000000; heartbeat=200"))
        self.assertIsNone(progress.percent("qemu", "(123/100%)"))

    def test_native_qemu_stall_is_distinct_from_the_absolute_timeout(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            result = progress.run_command([sys.executable, "-c",
                    "import os,time; os.write(2,b'(1.00/100%)\\r'); time.sleep(10)"], "qemu", idle=0.2, poll=0.01)
        self.assertEqual(125, result)
        self.assertIn("NO_PROGRESS", errors.getvalue())

    def test_native_zero_during_preallocation_does_not_authorize_cancellation(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            result = progress.run_command([sys.executable, "-c",
                    "import os,time; os.write(2,b'(0.00/100%)\\r'); time.sleep(0.35)"], "qemu", idle=0.15, poll=0.01)
        self.assertEqual(0, result)
        self.assertIn("PROGRESS_UNAVAILABLE", errors.getvalue())

    def test_native_completion_waits_for_final_flush_instead_of_canceling(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            result = progress.run_command([sys.executable, "-c",
                    "import os,time; os.write(2,b'(100.00/100%)\\r'); time.sleep(0.35)"], "qemu", idle=0.15, poll=0.01)
        self.assertEqual(0, result)
        self.assertIn("PROGRESS_UNAVAILABLE", errors.getvalue())

    def test_native_worker_without_progress_metrics_warns_and_finishes(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            result = progress.run_command([sys.executable, "-c",
                    "import sys,time; print('alive',file=sys.stderr,flush=True); time.sleep(0.35)"], "rbd", idle=0.15, poll=0.01)
        self.assertEqual(0, result)
        self.assertIn("PROGRESS_UNAVAILABLE", errors.getvalue())

    def supervise(self, initial, program):
        with tempfile.TemporaryDirectory() as directory:
            job = Path(directory)
            (job / "volume-progress.properties").write_text(initial)
            # These artifacts must never be removed by the watchdog itself.
            (job / "reservation.json").write_text("owned")
            (job / "payload.qcow2").write_text("partial")
            result = progress.supervise([sys.executable, "-c", program, str(job)], job,
                                        idle=0.15, poll=0.01, grace=0.5)
            self.assertEqual("owned", (job / "reservation.json").read_text())
            self.assertEqual("partial", (job / "payload.qcow2").read_text())
            warning = (job / "data-progress-warning.properties").read_text() if (job / "data-progress-warning.properties").exists() else ""
            return result, warning, (job / "stopped").exists()

    def test_blocked_worker_is_stopped_independently_and_cleanup_evidence_is_retained(self):
        code = """import signal, sys, time
from pathlib import Path
job = Path(sys.argv[1])
def stopped(signum, frame):
    (job / 'stopped').write_text('termination observed')
    sys.exit(0)
signal.signal(signal.SIGTERM, stopped)
time.sleep(10)
"""
        result, warning, stopped = self.supervise("step=QCOW2_BACKUP\nvolumeIndex=1\nprocessedBytes=0\n", code)
        self.assertEqual(124, result)
        self.assertIn("NO_PROGRESS", warning)
        self.assertTrue(stopped)

    def test_unknown_external_progress_warns_without_stopping_the_worker(self):
        result, warning, _ = self.supervise("step=NETBACKUP_TRANSFER\nvolumeIndex=1\n", "import time; time.sleep(0.35)")
        self.assertEqual(0, result)
        self.assertIn("PROGRESS_UNAVAILABLE", warning)

    def test_worker_ignoring_graceful_cancellation_is_killed_without_releasing_its_artifacts(self):
        result, warning, _ = self.supervise("step=QCOW2_BACKUP\nvolumeIndex=1\nprocessedBytes=0\n",
                "import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(10)")
        self.assertEqual(124, result)
        self.assertIn("NO_PROGRESS", warning)

    def test_queue_and_cleanup_do_not_inherit_data_stall_deadlines(self):
        for step in ("WAITING", "CLEANUP_SOURCE", "FINALIZING"):
            with self.subTest(step=step):
                result, warning, _ = self.supervise("step=%s\nvolumeIndex=1\nprocessedBytes=0\n" % step,
                                                    "import time; time.sleep(0.3)")
                self.assertEqual(0, result)
                self.assertEqual("", warning)

    def test_advancing_worker_outlives_the_idle_budget(self):
        code = """import os, sys, time
from pathlib import Path
job = Path(sys.argv[1])
for value in range(8):
    target = job / 'next.tmp'
    target.write_text('step=QCOW2_BACKUP\\nvolumeIndex=1\\nprocessedBytes=%s\\n' % value)
    os.replace(target, job / 'volume-progress.properties')
    time.sleep(0.05)
"""
        result, warning, _ = self.supervise("step=QCOW2_BACKUP\nvolumeIndex=1\nprocessedBytes=0\n", code)
        self.assertEqual(0, result)
        self.assertEqual("", warning)


if __name__ == "__main__":
    unittest.main()
