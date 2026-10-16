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

"""Regression coverage for sparse copies, incremental zero overrides and failed NBD initialization."""
import contextlib
import ctypes
import errno
import importlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import thirdparty_qcow2_nbd as staging
import thirdparty_volume_backup as backup

SECTOR = 512
DIRTY = "qemu:dirty-bitmap:dirty-vda"


class MemoryExport:
    """A small NBD peer with deliberately partial status replies and a 1 KiB IO limit."""
    def __init__(self, data, statuses=(), tuples=False, metadata=True):
        self.data = bytearray(data)
        self.statuses = statuses
        self.metadata = metadata
        self.contexts = []
        self.reads = []
        self.writes = []
        self.closed = False
        self.flushed = False
        self.readonly = False
        self.flush_supported = True
        self.block_status_64 = self.status if tuples else None
        self.block_status = None if tuples else self.status
        self.tuples = tuples
        self.reply = None

    def set_export_name(self, name):
        pass

    def add_meta_context(self, name):
        self.contexts.append(name)

    def can_meta_context(self, name):
        return self.metadata

    def connect_unix(self, socket):
        pass

    def get_size(self):
        return len(self.data)

    def get_block_size(self, kind):
        return SECTOR if kind == 0 else 2 * SECTOR

    def is_read_only(self):
        return self.readonly

    def can_flush(self):
        return self.flush_supported

    def status(self, count, offset, callback, flags):
        if self.reply is not None:
            self.reply(count, offset, callback)
            return
        for start, end, bits in self.statuses:
            if start <= offset < end:
                length = min(count, end - offset)
                entries = [(length, bits)] if self.tuples else [length, bits]
                callback(self.contexts[0], offset, entries, ctypes.c_int(0))
                return

    def pread(self, count, offset):
        self.reads.append((offset, count))
        return self.data[offset:offset + count]

    def pwrite(self, data, offset):
        self.writes.append((offset, bytes(data)))
        self.data[offset:offset + len(data)] = data

    def flush(self):
        self.flushed = True

    def shutdown(self):
        pass

    def close(self):
        self.closed = True


def module_for(*handles):
    return SimpleNamespace(NBD=Mock(side_effect=handles), CMD_FLAG_REQ_ONE=8)


def copy(source, output, incremental=False):
    module = module_for(source, output)
    with staging.Connection(module, "source.sock", len(source.data),
                            dirty_context=DIRTY if incremental else None) as reader:
        with staging.Connection(module, "output.sock", len(output.data), writable=True) as writer:
            staging.copy_volume(reader, writer, incremental, lambda: None, lambda count, started: None,
                                lambda fraction: None, time.monotonic() + 10)


class NbdCopyTest(unittest.TestCase):
    def test_full_skips_explicit_zero_but_reads_hole_without_zero(self):
        data = bytes(SECTOR) + b"A" * SECTOR + b"B" * SECTOR
        source = MemoryExport(data, [(0, SECTOR, 3), (SECTOR, 2 * SECTOR, 1), (2 * SECTOR, len(data), 0)])
        output = MemoryExport(bytes(len(data)))
        copy(source, output)
        self.assertEqual(data, bytes(output.data))
        self.assertEqual([(SECTOR, SECTOR), (2 * SECTOR, SECTOR)], source.reads)
        self.assertTrue(output.flushed)
        self.assertTrue(source.closed and output.closed)

    def test_incremental_zero_overrides_parent_with_both_callback_formats(self):
        parent = b"A" * (3 * SECTOR)
        current = b"B" * SECTOR + bytes(SECTOR) + parent[2 * SECTOR:]
        for tuples in (False, True):
            with self.subTest(tuples=tuples):
                source = MemoryExport(current, [(0, SECTOR, 1), (SECTOR, 2 * SECTOR, 1),
                                                (2 * SECTOR, len(parent), 0)], tuples=tuples)
                output = MemoryExport(bytes(len(parent)))
                copy(source, output, True)
                restored = bytearray(parent)
                for offset, data in output.writes:
                    restored[offset:offset + len(data)] = data
                self.assertEqual(current, bytes(restored))
                self.assertIn((SECTOR, bytes(SECTOR)), output.writes)
                self.assertFalse(any(offset >= 2 * SECTOR for offset, _ in source.reads))

    def test_unknown_allocation_reads_all_data(self):
        source = MemoryExport(b"A" * (4 * SECTOR), metadata=False)
        output = MemoryExport(bytes(len(source.data)))
        copy(source, output)
        self.assertEqual(source.data, output.data)
        self.assertEqual(4 * SECTOR, sum(count for _, count in source.reads))

    def test_missing_dirty_bitmap_fails_before_writing(self):
        source = MemoryExport(b"A" * SECTOR, metadata=False)
        output = MemoryExport(bytes(SECTOR))
        with self.assertRaisesRegex(RuntimeError, "incremental dirty bitmap"):
            copy(source, output, True)
        self.assertEqual([], output.writes)
        self.assertTrue(source.closed)

    def test_invalid_or_missing_dirty_replies_are_never_treated_as_clean(self):
        replies = ([], [0, 1], [SECTOR], [(SECTOR, 1, 0)], [SECTOR, 2])
        for entries in replies:
            with self.subTest(entries=entries):
                source = MemoryExport(b"A" * SECTOR)
                source.reply = lambda count, offset, callback: callback(DIRTY, offset, entries, ctypes.c_int(0))
                output = MemoryExport(bytes(SECTOR))
                with self.assertRaisesRegex(RuntimeError, "confirm the incremental dirty bitmap"):
                    copy(source, output, True)
                self.assertFalse(output.flushed)
                self.assertEqual([], output.writes)
                self.assertTrue(source.closed and output.closed)

    def test_missing_optional_status_callback_falls_back_to_reads(self):
        source = MemoryExport(b"A" * SECTOR)  # No response from a negotiated allocation context.
        output = MemoryExport(bytes(SECTOR))
        copy(source, output)
        self.assertEqual(source.data, output.data)

    def test_signal_interruption_is_not_swallowed_by_optional_allocation_fallback(self):
        source = MemoryExport(b"A" * SECTOR)
        source.reply = Mock(side_effect=InterruptedError("interrupted"))
        output = MemoryExport(bytes(SECTOR))
        with self.assertRaisesRegex(InterruptedError, "interrupted"):
            copy(source, output)
        self.assertEqual([], source.reads)
        self.assertFalse(output.flushed)
        self.assertTrue(source.closed and output.closed)

    def test_short_read_fails_without_flushing_incomplete_backup(self):
        source = MemoryExport(b"A" * SECTOR, metadata=False)
        source.pread = lambda count, offset: b"A"
        output = MemoryExport(bytes(SECTOR))
        with self.assertRaisesRegex(RuntimeError, "incomplete data"):
            copy(source, output)
        self.assertEqual([], output.writes)
        self.assertFalse(output.flushed)

    def test_flush_failure_is_not_reported_as_a_completed_copy(self):
        source = MemoryExport(b"A" * SECTOR, metadata=False)
        output = MemoryExport(bytes(SECTOR))
        output.flush = Mock(side_effect=RuntimeError("flush failed"))
        with self.assertRaisesRegex(RuntimeError, "flush failed"):
            copy(source, output)
        self.assertTrue(source.closed and output.closed)

    def test_bad_size_or_output_capabilities_close_failed_connection(self):
        for fault in ("size", "readonly", "flush"):
            with self.subTest(fault=fault):
                handle = MemoryExport(bytes(SECTOR))
                handle.readonly = fault == "readonly"
                handle.flush_supported = fault != "flush"
                with self.assertRaises(RuntimeError):
                    with staging.Connection(module_for(handle), "output.sock",
                                            2 * SECTOR if fault == "size" else SECTOR, writable=True):
                        self.fail("Invalid output was accepted")
                self.assertTrue(handle.closed)

    def test_failed_connect_and_failed_image_creation_release_source(self):
        handle = MemoryExport(bytes(SECTOR))
        handle.connect_unix = Mock(side_effect=RuntimeError("handshake failed"))
        with self.assertRaisesRegex(RuntimeError, "handshake failed"):
            with staging.Connection(module_for(handle), "source.sock", SECTOR):
                self.fail("Failed connection was accepted")
        self.assertTrue(handle.closed)
        with tempfile.TemporaryDirectory() as directory:
            source = MemoryExport(bytes(SECTOR))
            worker = backup.Backup.__new__(backup.Backup)
            worker.nbd = module_for(source)
            worker.plan = {}
            worker.labels = ["vda"]
            worker.manifest = {"volumes": [{"uuid": "volume-1", "provisionedBytes": SECTOR}]}
            worker.count, worker.timeout = 1, 300
            worker.job, worker.socket = Path(directory), Path(directory) / "source.sock"
            with patch.object(backup, "qcow2_command", side_effect=RuntimeError("create failed")):
                with self.assertRaisesRegex(RuntimeError, "OUTPUT_CREATE"):
                    worker.export_qcow2(0, Path(directory) / "image.qcow2")
            self.assertTrue(source.closed)

    def test_failed_preflight_does_not_reserve_capacity_or_touch_source(self):
        worker = backup.Backup.__new__(backup.Backup)
        with tempfile.TemporaryDirectory() as directory:
            worker.job = Path(directory) / "backup-id"
            worker.job.mkdir()
            worker.manifest = {"backupUuid": worker.job.name}
            worker.plan = {"rbd": False}
            worker.source_start = worker.job / "volume-source-start.json"
            original = {"version": 1, "backupUuid": worker.job.name, "state": "NOT_STARTED"}
            backup.atomic(worker.source_start, original)
            worker.engine = worker.job / "volume-engine.json"
            worker.preflight_qcow2 = Mock(side_effect=RuntimeError("missing dependency"))
            worker.reserve = Mock()
            with patch.object(backup, "admission_closed_reason", return_value=None):
                with self.assertRaisesRegex(RuntimeError, "missing dependency"):
                    worker.execute()
            worker.reserve.assert_not_called()
            self.assertEqual(original, backup.json.loads(worker.source_start.read_text()))
            self.assertFalse(worker.engine.exists())


class Qcow2BackupControlTest(unittest.TestCase):
    def worker(self, directory, provider="ablestack-commvault", limit=8):
        root = Path(directory)
        job, staging_root = root / "job", root / "staging"
        job.mkdir()
        staging_root.mkdir()
        plan = {"manifest": {"backupUuid": "backup-id", "provider": provider, "volumes": [{}]},
                "vmName": "source-vm", "backupPath": str(staging_root), "timeout": 300,
                "diskPaths": [str(root / "source.qcow2")], "checkpointName": "checkpoint",
                "bandwidthLimitMbps": limit}
        file = job / "volume-plan.json"
        backup.atomic(file, plan)
        return backup.Backup(file)

    def test_all_providers_support_running_and_stopped_qcow2_controls(self):
        for provider in ("ablestack-commvault", "ablestack-netbackup", "ablestack-veeam"):
            for state in ("running", "shut off"):
                with self.subTest(provider=provider, state=state), tempfile.TemporaryDirectory() as directory:
                    worker = self.worker(directory, provider, 64)
                    source = worker.plan["diskPaths"][0]
                    xml = ('<domain><devices><disk device="disk"><source file="%s"/>'
                           '<target dev="vda"/></disk></devices></domain>') % source
                    backup.atomic(worker.job / "backup-primary-capacity.json", {})

                    def virsh(args):
                        return {"dumpxml": xml, "domstate": state,
                                "checkpoint-dumpxml": "<domaincheckpoint/>"}.get(args[3], "")

                    with patch("thirdparty_primary_capacity.backup_capacity", return_value={"primaryClaims": []}), \
                            patch.object(backup, "run", side_effect=virsh) as run, \
                            patch.object(backup, "qcow2_command", return_value='{"format":"qcow2"}'), \
                            patch.object(worker, "thaw_guest"):
                        worker.begin_qcow2()
                    self.assertTrue(worker.live_bandwidth)
                    self.assertTrue(worker.pull)
                    if state == "shut off":
                        self.assertTrue(worker.dummy)
                        self.assertEqual("DUMMY-VOLUME-backup-id", worker.domain)
                        run.assert_any_call(["virsh", "-c", "qemu:///system", "create",
                                             str(worker.job / "dummy.xml"), "--paused"])
                    else:
                        self.assertFalse(worker.dummy)
                        self.assertEqual("source-vm", worker.domain)
                    worker.progress("QCOW2_BACKUP", 0)
                    progress = (worker.job / "volume-progress.properties").read_text()
                    self.assertIn("liveBandwidthSupported=true", progress)
                    self.assertIn("bandwidthLimitMbps=64", progress)

    def test_throttle_applies_live_changes_before_finishing_a_block(self):
        for limit, elapsed in ((16, 0.5), (0, 0.25)):
            with self.subTest(limit=limit), tempfile.TemporaryDirectory() as directory:
                worker = self.worker(directory)
                clock = [0.0]

                def sleep(delay):
                    clock[0] += delay
                    backup.atomic(worker.bandwidth_file, str(limit))

                with patch.object(backup.time, "monotonic", side_effect=lambda: clock[0]), \
                        patch.object(backup.time, "sleep", side_effect=sleep):
                    worker.throttle(1000000, 0.0)
                self.assertAlmostEqual(elapsed, clock[0])

    def test_cancel_during_throttle_stops_copy_before_the_next_block(self):
        for marker in ("volume-cancel", "staging-admission-cancel"):
            with self.subTest(marker=marker), tempfile.TemporaryDirectory() as directory:
                worker = self.worker(directory)
                source = MemoryExport(bytes([0xa5]) * (3 * SECTOR), metadata=False)
                output = MemoryExport(bytes(3 * SECTOR))
                clock = [0.0]

                def sleep(delay):
                    clock[0] += delay
                    backup.atomic(worker.job / marker, "cancelled")

                with staging.Connection(module_for(source), "source.sock", 3 * SECTOR) as reader, \
                        staging.Connection(module_for(output), "output.sock", 3 * SECTOR, writable=True) as writer, \
                        patch.object(backup.time, "monotonic", side_effect=lambda: clock[0]), \
                        patch.object(backup.time, "sleep", side_effect=sleep):
                    with self.assertRaisesRegex(RuntimeError, "cancelled"):
                        staging.copy_volume(reader, writer, False, worker.check_cancel, worker.throttle,
                                            lambda fraction: None, 10)
                self.assertEqual(1, len(source.reads))
                self.assertEqual(1, len(output.writes))
                self.assertFalse(output.flushed)

    def test_stopped_vm_cancellation_aborts_and_destroys_its_owned_domain(self):
        with tempfile.TemporaryDirectory() as directory:
            worker = self.worker(directory)
            worker.domain, worker.dummy, worker.pull = "DUMMY-VOLUME-backup-id", True, True
            with patch.object(worker, "thaw_guest"), \
                    patch.object(backup, "run", return_value="Job type: None") as run:
                worker.close()
            run.assert_any_call(["virsh", "-c", "qemu:///system", "domjobabort", worker.domain], 30)
            run.assert_any_call(["virsh", "-c", "qemu:///system", "destroy", worker.domain], 30)
            self.assertFalse(worker.pull)


@unittest.skipUnless(all(shutil.which(tool) for tool in ("qemu-img", "qemu-nbd", "qemu-io")),
                     "Real QCOW2 regression requires qemu-img, qemu-nbd and qemu-io")
class RealQcow2ZeroOverrideTest(unittest.TestCase):
    def test_incremental_zero_survives_unsafe_parent_rebase(self):
        try:
            module = importlib.import_module("nbd")
        except ImportError:
            self.skipTest("Real QCOW2 regression requires python3-libnbd")
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            root = Path(directory)
            parent, current, output = [root / (name + ".qcow2") for name in ("parent", "current", "output")]
            size = 1024 * 1024

            def run(*args):
                subprocess.run(args, capture_output=True, check=True, timeout=30)

            run("qemu-img", "create", "-f", "qcow2", str(parent), str(size))
            run("qemu-io", "-f", "qcow2", "-c", "write -P 0xa5 0 1M", str(parent))
            run("qemu-img", "convert", "-O", "qcow2", str(parent), str(current))
            run("qemu-img", "bitmap", "--add", str(current), "bm0")
            run("qemu-io", "-f", "qcow2", "-c", "write -P 0 0 64k", str(current))
            run("qemu-img", "create", "-f", "qcow2", str(output), str(size))
            servers = []
            for name, image, options in (("source", current, ["--read-only", "--bitmap=bm0"]), ("output", output, [])):
                socket = root / (name + ".sock")
                process = subprocess.Popen(["qemu-nbd", "--persistent", "--format=qcow2", "--socket", str(socket),
                                            *options, str(image)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                servers.append((process, socket))
                stack.callback(backup.stop_qcow2_output, process, socket)
            deadline = time.monotonic() + 10

            def connect(socket, **options):
                while time.monotonic() < deadline:
                    if any(process.poll() is not None for process, _ in servers):
                        self.fail("Test qemu-nbd exited before connecting")
                    if socket.exists():
                        try:
                            return stack.enter_context(staging.Connection(module, socket, size, **options))
                        except module.Error as exc:
                            if exc.errnum not in (errno.ENOENT, errno.ECONNREFUSED):
                                raise
                    time.sleep(0.05)
                self.fail("Test qemu-nbd did not start")

            source = connect(servers[0][1], dirty_context="qemu:dirty-bitmap:bm0")
            destination = connect(servers[1][1], writable=True)
            staging.copy_volume(source, destination, True, lambda: None, lambda count, started: None,
                                lambda fraction: None, time.monotonic() + 10)
            stack.close()  # qemu-img must acquire the output image after both servers exit.
            run("qemu-img", "rebase", "-u", "-f", "qcow2", "-F", "qcow2", "-b", str(parent), str(output))
            raw = root / "restored.raw"
            run("qemu-img", "convert", "-O", "raw", str(output), str(raw))
            self.assertEqual(bytes(65536) + bytes([0xa5]) * (size - 65536), raw.read_bytes())


if __name__ == "__main__":
    unittest.main()
