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

"""One source snapshot/checkpoint, one image in staging, confirmed transfer before unlink."""
import argparse
import contextlib
import errno
import fcntl
import hashlib
import json
import os
import re
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import time
import traceback
import uuid
import xml.etree.ElementTree as ET

from thirdparty_staging_cleanup import validate_job_directory
from thirdparty_qcow2_nbd import Connection, copy_volume, preflight


def diagnostic_tail(path):
    with Path(path).open("rb") as stream:
        stream.seek(0, os.SEEK_END)
        stream.seek(max(0, stream.tell() - 4096))
        lines = stream.read().decode("utf-8", errors="replace").splitlines()
    return next((line.strip()[:500] for line in reversed(lines) if line.strip()), "no diagnostic output")


def qcow2_command(args, diagnostics, timeout=300):
    """Capture local QCOW2 tool errors without logging Ceph/controller command arguments."""
    with Path(diagnostics).open("a") as stream:
        os.chmod(diagnostics, 0o600)
        stream.write("QCOW2 tool: %s %s\n" % (args[0], args[1]))
        stream.flush()
        try:
            result = subprocess.run(args, stdout=subprocess.PIPE, stderr=stream, text=True,
                                    timeout=timeout, check=False, env=dict(os.environ, LC_ALL="C"))
        except subprocess.TimeoutExpired:
            raise RuntimeError("%s %s timed out; diagnostics=%s" %
                               (args[0], args[1], diagnostics)) from None
        if result.returncode:
            raise RuntimeError("%s %s failed (exit %d): %s; diagnostics=%s" %
                               (args[0], args[1], result.returncode, diagnostic_tail(diagnostics), diagnostics))
        return result.stdout


def stop_qcow2_output(process, socket):
    if process.poll() is None:
        with contextlib.suppress(ProcessLookupError):
            process.terminate()
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        with contextlib.suppress(ProcessLookupError):
            process.kill()
        process.wait(timeout=30)
    # Never remove a socket while its writer may still be running.
    Path(socket).unlink(missing_ok=True)


def atomic(path, value):
    path = Path(path)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w") as stream:
        if isinstance(value, str):
            stream.write(value)
        else:
            json.dump(value, stream, ensure_ascii=False)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)
    fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def run(args, timeout=300):
    if args[0] == "virsh":
        timeout = min(timeout, 30)  # Control response timeout, independent of data transfer duration.
    # Never include Ceph keys or controller credentials in errors or the job log.
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, timeout=timeout, check=False, env=dict(os.environ, LC_ALL="C"))
    if result.returncode:
        raise RuntimeError("%s failed (exit %d)" % (args[0], result.returncode))
    return result.stdout


def admission_closed_path(job):
    job = Path(job)
    if job.name in ("", ".", "..") or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-" for c in job.name):
        raise RuntimeError("Invalid staging admission job ID")
    return job.parent.parent / "staging-admission-closed" / (job.name + ".closed")


def admission_closed_reason(job):
    # The stable receipt survives removal of the Host job directory.
    for path in (admission_closed_path(job), Path(job) / "staging-admission-closed"):
        if path.exists():
            return path.read_text()
    return None


def close_admission(job, reason):
    """Persist closure outside removable job records, under capacity.lock."""
    path = admission_closed_path(job)
    path.parent.mkdir(mode=0o700, exist_ok=True)
    fd = os.open(path.parent.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
    atomic(path, reason)


def remove_reservation(path):
    path.unlink(missing_ok=True)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def staging_identity(directory):
    # Called under capacity.lock. This identity survives Host reboot and NFS remount.
    file = directory / "filesystem.id"
    if not file.exists():
        atomic(file, str(uuid.uuid4()))
    return str(uuid.UUID(file.read_text().strip()))


def parent_checkpoint_xml(plan):
    parent = plan["parentCheckpointName"]
    checkpoints = [ET.fromstring(xml) for xml in (plan.get("parentCheckpointXmlChain") or {}).values()]
    matches = [checkpoint for checkpoint in checkpoints if checkpoint.findtext("name") == parent]
    if len(matches) != 1:
        raise RuntimeError("The exact parent checkpoint XML is unconfirmed")
    return matches[0]


def redefine_parent_checkpoint(plan, domain, job):
    # Archived ancestors describe the backup chain, not the remaining source bitmaps.
    # The immediate baseline is sufficient; never resurrect a retired ancestor.
    checkpoint = parent_checkpoint_xml(plan)
    parent = checkpoint.find("parent")
    if parent is not None:
        checkpoint.remove(parent)
    names = run(["virsh", "-c", "qemu:///system", "checkpoint-list", domain, "--name"]).splitlines()
    if plan["parentCheckpointName"] not in names:
        file = job / "parent-checkpoint.xml"
        atomic(file, ET.tostring(checkpoint, encoding="unicode"))
        run(["virsh", "-c", "qemu:///system", "checkpoint-create", domain, "--xmlfile", str(file), "--redefine", "--redefine-validate"])


class Backup:
    def __init__(self, plan_file):
        self.plan = json.loads(Path(plan_file).read_text())
        self.manifest = self.plan["manifest"]
        self.root = Path(self.plan["backupPath"])
        self.job = Path(plan_file).parent
        self.timeout = max(300, self.plan["timeout"])
        self.vm = self.plan["vmName"]
        self.domain = self.vm
        self.dummy = False
        self.pull = False
        self.success = False
        self.socket = Path("/var/lib/libvirt/qemu") / ("backup-" + self.manifest["backupUuid"] + ".sock")
        self.reservation = None
        self.engine = self.job / "volume-engine.json"
        self.source_start = self.job / "volume-source-start.json"
        self.count = len(self.manifest["volumes"])
        self.live_bandwidth = False
        self.bandwidth_file = self.job / "volume-bandwidth-mbps"
        atomic(self.bandwidth_file, str(max(0, self.plan.get("bandwidthLimitMbps", 0))))

    def progress(self, step, index, fraction=0):
        # Each volume receives equal export/transfer shares; metadata owns the final 5%.
        if index < self.count:
            completed = 2 * index + (1 if step.endswith("_TRANSFER") else 0) + fraction
            progress = int(95 * completed / (2 * self.count))
        else:
            progress = 95 + int(4 * fraction)
        atomic(self.job / "volume-progress.properties",
               "step=%s\nvolumeIndex=%d\nvolumeCount=%d\nprogress=%d\n" %
               (step, min(index + 1, self.count), self.count, min(progress, 99)) +
               "liveBandwidthSupported=%s\nbandwidthLimitMbps=%s\n" %
               (str(self.live_bandwidth).lower(), self.bandwidth_file.read_text().strip()))

    def throttle(self, byte_count, started_at):
        while True:
            self.check_cancel()
            limit = int(self.bandwidth_file.read_text().strip())
            if limit <= 0:
                return
            remaining = byte_count / (limit * 1000000 / 8) - (time.monotonic() - started_at)
            if remaining <= 0:
                return
            time.sleep(min(0.25, remaining))

    def check_cancel(self):
        queue_cancel = self.job / "staging-admission-cancel"
        if queue_cancel.exists():
            raise RuntimeError(queue_cancel.read_text())
        if (self.job / "volume-cancel").exists():
            raise RuntimeError("Volume pipeline was cancelled")

    def reserve(self):
        from thirdparty_primary_capacity import filesystem_capacity, prepare_backup_capacity, backup_capacity
        stage_root = Path(self.plan["stageRoot"]).resolve(strict=True)
        if self.engine.exists():
            raise RuntimeError("Backup engine was already initialized; use job reconciliation instead of rerunning it")
        atomic(self.engine, {"stageDevice": stage_root.stat().st_dev})
        if not self.root.is_absolute() or not self.root.resolve().is_relative_to(stage_root):
            raise RuntimeError("Backup path is outside configured third-party staging")
        directory = stage_root / ".volume-reservations"
        directory.mkdir(mode=0o700, exist_ok=True)
        required = max(v["provisionedBytes"] for v in self.manifest["volumes"])
        required += (required * self.plan["bufferPercent"] + 99) // 100
        scratch = 0
        staging_key = filesystem_capacity(stage_root, True)["storageKey"]
        if not self.plan["rbd"]:
            primary = prepare_backup_capacity(self.manifest["backupUuid"], stage_root, self.plan["diskPaths"],
                                              [v["provisionedBytes"] for v in self.manifest["volumes"]])
            atomic(self.job / "backup-primary-capacity.json", primary)
            scratch = sum(claim["requiredBytes"] for claim in backup_capacity(primary)["primaryClaims"]
                          if claim["storageKey"] == staging_key)
        with (directory / "capacity.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            if admission_closed_reason(self.job) is not None:
                raise RuntimeError("Backup staging admission is closed")
            identity = staging_identity(directory)
            atomic(self.engine, {"stageDevice": stage_root.stat().st_dev, "stageFilesystemId": identity})
            self.reservation = directory / (self.manifest["backupUuid"] + ".json")
            if self.reservation.exists():
                raise RuntimeError("Staging reservation already exists for this backup")
            atomic(self.job / "staging-admission-plan.json", {
                "jobId": self.manifest["backupUuid"], "stageRoot": str(stage_root), "stageFilesystemId": identity,
                "admissionProtocolVersion": 1,
                "reservation": {"jobId": self.manifest["backupUuid"], "bytes": required,
                                "primaryScratchBytes": scratch, "host": self.plan["sourceHost"], "operation": "BACKUP"}})
        atomic(self.job / "staging-admission-request.json", {
            "admission": True, "jobId": self.manifest["backupUuid"], "operation": "BACKUP",
            "requiredBytes": required + scratch, "stagingStorageKey": staging_key,
            "bufferPercent": self.plan["bufferPercent"],
            "capacityVersion": 0 if self.plan["rbd"] else 1})
        self.progress("WAITING", 0)
        queue_deadline = time.monotonic() + max(1, self.plan.get("queueTimeout", 3600))
        while not (self.job / "staging-admission-granted").is_file():
            self.check_cancel()
            if time.monotonic() >= queue_deadline:
                from thirdparty_staging_admission import control
                result = control(self.job / "staging-admission-plan.json", "cancel", "Staging queue timeout expired")
                if result["state"] == "CANCELED":
                    self.check_cancel()
            time.sleep(1)
        self.check_cancel()
        (self.job / "staging-admission-request.json").unlink(missing_ok=True)

    @staticmethod
    def rbd_command(uri):
        if not uri.startswith("rbd:"):
            raise RuntimeError("Invalid RBD source")
        payload = uri[4:]
        image = payload.split(":")[0]
        args = ["rbd"]
        for field, option in (("mon_host", "-m"), ("id", "--id"), ("key", "--key")):
            marker = ":" + field + "="
            if marker not in payload:
                continue
            value = payload.split(marker, 1)[1]
            if field == "mon_host":
                value = re.split(r"(?<!\\):(?:auth_[^=]*|id|key)=", value, maxsplit=1)[0]
                value = value.replace("\\;", ",").replace("\\:", ":")
            else:
                value = value.split(":", 1)[0]
            args += [option, value]
        return args, image

    def freeze_guest(self):
        # Record ownership before freezing; a process crash must not leave the source VM frozen.
        try:
            state = json.loads(run(["virsh", "-c", "qemu:///system", "qemu-agent-command", self.vm,
                                    '{"execute":"guest-fsfreeze-status"}'], 30))
            if state.get("return") != "thawed":
                return
            engine = json.loads(self.engine.read_text())
            engine["freezePending"] = True
            atomic(self.engine, engine)
            run(["virsh", "-c", "qemu:///system", "qemu-agent-command", self.vm,
                 '{"execute":"guest-fsfreeze-freeze"}'], 30)
        except RuntimeError:
            pass

    def thaw_guest(self):
        thaw_owned_guest(self.engine, self.vm)

    def begin_rbd(self):
        checkpoint = self.plan["checkpointName"]
        parent = self.plan.get("parentCheckpointName")
        # Validate every source before creating snapshots or any large staging artifact.
        for uri in self.plan["diskPaths"]:
            args, image = self.rbd_command(uri)
            run(args + ["info", image])
            if parent:
                snapshots = json.loads(run(args + ["snap", "ls", "--format", "json", image]))
                if not any(s["name"] == parent for s in snapshots):
                    raise RuntimeError("Parent RBD snapshot is missing")
        try:
            if self.plan.get("quiesce"):
                self.freeze_guest()
            for uri in self.plan["diskPaths"]:
                args, image = self.rbd_command(uri)
                run(args + ["snap", "create", image + "@" + checkpoint])
        finally:
            self.thaw_guest()
        metadata = ("vm_name=%s\nbackup_engine=RBD_DIFF\nbackup_type=%s\ncheckpoint_name=%s\n"
                    "parent_checkpoint_name=%s\ndisk_paths=%s\nbackup_files=%s\nbackup_dir=%s\n" %
                    (self.vm, self.manifest["backupType"], checkpoint, parent or "",
                     ",".join(self.rbd_command(uri)[1] for uri in self.plan["diskPaths"]),
                     ",".join(Path(volume["chain"][-1]["path"]).name for volume in self.manifest["volumes"]), self.root))
        # Match the existing rbd-backup.meta shape; never serialize controller credentials.
        atomic(self.root / "rbd-backup.meta", metadata)
        (self.root / "checkpoints").mkdir()
        atomic(self.root / "checkpoints" / (checkpoint + ".meta"), metadata)

    def begin_qcow2(self):
        # Pull-mode scratch belongs to primary storage, never to Host OS or staging.
        from thirdparty_primary_capacity import backup_capacity
        current = backup_capacity(json.loads((self.job / "backup-primary-capacity.json").read_text()))
        for claim in current["primaryClaims"]:
            if claim["storageKey"] == current["stagingStorageKey"]:
                # Source scratch was reserved atomically with the staging admission.
                directory = self.reservation.parent
                with (directory / "capacity.lock").open("a") as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX)
                    record = json.loads(self.reservation.read_text())
                    if record.get("primaryScratchBytes", 0) < claim["requiredBytes"]:
                        raise RuntimeError("Source scratch exceeds the admitted staging reservation")
            elif claim["availableBytes"] < claim["requiredBytes"]:
                raise RuntimeError("Insufficient primary capacity for a consistent QCOW2 pull backup")
        try:
            domain_xml = ET.fromstring(run(["virsh", "-c", "qemu:///system", "dumpxml", self.vm]))
            active = run(["virsh", "-c", "qemu:///system", "domstate", self.vm]).strip() == "running"
        except RuntimeError:
            domain_xml = ET.Element("domain", type="kvm")
            active = False
        # Both Running and Stopped VM volumes use the same throttled NBD copy loop.
        self.live_bandwidth = self.manifest["provider"] in (
            "ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")
        if not active:
            self.domain = "DUMMY-VOLUME-" + self.manifest["backupUuid"]
            dummy = ET.Element("domain", type="kvm")
            ET.SubElement(dummy, "name").text = self.domain
            ET.SubElement(dummy, "memory", unit="MiB").text = "1024"
            ET.SubElement(dummy, "vcpu").text = "1"
            operating = ET.SubElement(dummy, "os")
            ET.SubElement(operating, "type", arch="x86_64").text = "hvm"
            devices = ET.SubElement(dummy, "devices")
            for i, path in enumerate(self.plan["diskPaths"]):
                disk = ET.SubElement(devices, "disk", type="file", device="disk")
                info = json.loads(qcow2_command(["qemu-img", "info", "--output=json", path],
                                               self.job / "qcow2-preflight.log"))
                ET.SubElement(disk, "driver", name="qemu", type=info["format"])
                ET.SubElement(disk, "source", file=path)
                ET.SubElement(disk, "target", dev="vd" + chr(ord("a") + i), bus="virtio")
            dummy_file = self.job / "dummy.xml"
            atomic(dummy_file, ET.tostring(dummy, encoding="unicode"))
            run(["virsh", "-c", "qemu:///system", "create", str(dummy_file), "--paused"])
            self.dummy = True
            domain_xml = ET.fromstring(run(["virsh", "-c", "qemu:///system", "dumpxml", self.domain]))
        by_path = {}
        for disk in domain_xml.findall("./devices/disk"):
            source, target = disk.find("source"), disk.find("target")
            if disk.get("device") == "disk" and source is not None and target is not None and source.get("file"):
                by_path[str(Path(source.get("file")).resolve())] = target.get("dev")
        backup_xml = ET.Element("domainbackup", mode="pull")
        parent_checkpoint = self.plan.get("parentCheckpointName")
        if parent_checkpoint:
            ET.SubElement(backup_xml, "incremental").text = parent_checkpoint
            redefine_parent_checkpoint(self.plan, self.domain, self.job)
        ET.SubElement(backup_xml, "server", transport="unix", socket=str(self.socket))
        disks = ET.SubElement(backup_xml, "disks")
        checkpoint_xml = ET.Element("domaincheckpoint")
        ET.SubElement(checkpoint_xml, "name").text = self.plan["checkpointName"]
        checkpoint_disks = ET.SubElement(checkpoint_xml, "disks")
        self.labels = []
        for i, path in enumerate(self.plan["diskPaths"]):
            label = by_path.get(str(Path(path).resolve()))
            if not label:
                raise RuntimeError("Cannot identify source disk in libvirt domain")
            self.labels.append(label)
            disk = ET.SubElement(disks, "disk", name=label, backup="yes", type="file",
                                 exportname=label, exportbitmap="dirty-" + label)
            ET.SubElement(disk, "driver", type="qcow2")
            scratch = str(Path(path).parent / (".backup-" + self.manifest["backupUuid"] + "-" + label + ".qcow2"))
            ET.SubElement(disk, "scratch", file=scratch)
            ET.SubElement(checkpoint_disks, "disk", name=label, checkpoint="bitmap")
        backup_path = self.job / "pull.xml"
        checkpoint_path = self.job / "checkpoint.xml"
        atomic(backup_path, ET.tostring(backup_xml, encoding="unicode"))
        atomic(checkpoint_path, ET.tostring(checkpoint_xml, encoding="unicode"))
        try:
            if self.plan.get("quiesce") and active:
                self.freeze_guest()
            run(["virsh", "-c", "qemu:///system", "backup-begin", self.domain,
                 "--backupxml", str(backup_path), "--checkpointxml", str(checkpoint_path)])
            self.pull = True
        finally:
            self.thaw_guest()
        (self.root / "checkpoints").mkdir()
        atomic(self.root / "checkpoints" / (self.plan["checkpointName"] + ".xml"),
               run(["virsh", "-c", "qemu:///system", "checkpoint-dumpxml", self.domain,
                    self.plan["checkpointName"], "--no-domain"]))

    def export_rbd(self, index, target):
        args, image = self.rbd_command(self.plan["diskPaths"][index])
        parent = self.plan.get("parentCheckpointName")
        command = args + (["export-diff", "--from-snap", parent] if parent else ["export"])
        command += [image + "@" + self.plan["checkpointName"], str(target)]
        step = "RBD_EXPORT_DIFF" if parent else "RBD_EXPORT"
        with subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) as proc:
            deadline = time.monotonic() + self.timeout
            try:
                while proc.poll() is None:
                    self.check_cancel()
                    if time.monotonic() >= deadline:
                        raise RuntimeError("RBD volume export timed out")
                    size = target.stat().st_size if target.exists() else 0
                    total = self.manifest["volumes"][index]["provisionedBytes"]
                    self.progress(step, index, min(1, size / total))
                    time.sleep(1)
                if proc.returncode:
                    raise RuntimeError("RBD volume export failed")
            except BaseException:
                proc.terminate()
                try:
                    proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
                raise

    def export_qcow2(self, index, target):
        incremental = bool(self.plan.get("parentCheckpointName"))
        context = "qemu:dirty-bitmap:dirty-" + self.labels[index] if incremental else None
        volume = self.manifest["volumes"][index]
        size = volume["provisionedBytes"]
        destination_socket = self.job / ("target-" + str(index) + ".sock")
        diagnostics = self.job / ("qcow2-volume-" + str(index + 1) + ".log")
        deadline = time.monotonic() + self.timeout
        phase = "SOURCE_CONNECT"
        try:
            # All initialization is inside the cleanup scope, including failed handshakes and image creation.
            with contextlib.ExitStack() as stack:
                log = stack.enter_context(diagnostics.open("a"))
                os.chmod(diagnostics, 0o600)
                log.write("QCOW2 volume: index=%d uuid=%s export=%s provisionedBytes=%d incremental=%s\n" %
                          (index + 1, volume["uuid"], self.labels[index], size, incremental))
                log.flush()
                source = stack.enter_context(Connection(self.nbd, self.socket, size,
                                                        export=self.labels[index], dirty_context=context))
                if destination_socket.exists() or destination_socket.is_symlink():
                    raise RuntimeError("QCOW2 output socket already exists")
                phase = "OUTPUT_CREATE"
                qcow2_command(["qemu-img", "create", "-f", "qcow2", str(target), str(size)], diagnostics)
                log.write("QCOW2 tool: qemu-nbd output server\n")
                log.flush()
                process = subprocess.Popen(["qemu-nbd", "--socket", str(destination_socket),
                                            "--persistent", "--format=qcow2", str(target)],
                                           stdout=subprocess.DEVNULL, stderr=log,
                                           env=dict(os.environ, LC_ALL="C"))
                stack.callback(stop_qcow2_output, process, destination_socket)
                phase = "OUTPUT_CONNECT"
                while True:
                    self.check_cancel()
                    if process.poll() is not None:
                        raise RuntimeError("QCOW2 output server exited (exit %d): %s" %
                                           (process.returncode, diagnostic_tail(diagnostics)))
                    if time.monotonic() >= deadline:
                        raise RuntimeError("QCOW2 output connection timed out")
                    if destination_socket.exists():
                        try:
                            destination = stack.enter_context(Connection(self.nbd, destination_socket, size, writable=True))
                            break
                        except self.nbd.Error as exc:
                            # Socket creation can precede listen(). Retry only before the first write.
                            if getattr(exc, "errnum", None) not in (errno.ENOENT, errno.ECONNREFUSED):
                                raise
                    time.sleep(0.1)
                phase = "DATA_COPY"
                copy_volume(source, destination, incremental, self.check_cancel, self.throttle,
                            lambda fraction: self.progress("QCOW2_BACKUP", index, fraction), deadline)
                if process.poll() is not None:
                    raise RuntimeError("QCOW2 output server exited before completion (exit %d): %s" %
                                       (process.returncode, diagnostic_tail(diagnostics)))
                phase = "OUTPUT_CLOSE"
            if incremental:
                phase = "PARENT_LINK"
                chain = volume["chain"]
                qcow2_command(["qemu-img", "rebase", "-u", "-f", "qcow2", "-F", "qcow2",
                               "-b", chain[-2]["path"], str(target)], diagnostics, self.timeout)
        except Exception as exc:
            with contextlib.suppress(OSError):
                with diagnostics.open("a") as log:
                    os.chmod(diagnostics, 0o600)
                    traceback.print_exception(type(exc), exc, exc.__traceback__, file=log)
            raise RuntimeError("QCOW2 volume %d/%d (%s) failed at %s: %s; diagnostics=%s" %
                               (index + 1, self.count, volume["uuid"], phase, exc, diagnostics)) from exc

    def preflight_qcow2(self):
        self.nbd = preflight(bool(self.plan.get("parentCheckpointName")))
        if self.plan.get("parentCheckpointName"):
            for volume in self.manifest["volumes"]:
                if len(volume["chain"]) < 2:
                    raise RuntimeError("QCOW2 incremental volume is missing its parent artifact")
        diagnostics = self.job / "qcow2-preflight.log"
        for tool in ("virsh", "qemu-img", "qemu-nbd"):
            version = qcow2_command([tool, "--version"], diagnostics, 30).splitlines()
            print("QCOW2 tool preflight: %s %s" % (shutil.which(tool), version[0] if version else "unknown"), flush=True)

    def transfer(self, index, artifact, metadata=False):
        self.progress("METADATA_TRANSFER" if metadata else self.plan["providerStep"], index)
        atomic(self.job / "volume-request.json", {"index": index, "metadata": metadata,
                                                 "artifact": artifact, "backupUuid": self.manifest["backupUuid"]})
        deadline = time.monotonic() + self.timeout
        ack = self.job / ("volume-ack-" + str(index) + ".json")
        while not ack.is_file():
            self.check_cancel()
            if time.monotonic() >= deadline:
                raise RuntimeError("External artifact transfer confirmation timed out")
            time.sleep(1)
        updated = json.loads(ack.read_text())
        if updated.get("backupUuid") != self.manifest["backupUuid"]:
            raise RuntimeError("External confirmation belongs to another backup")
        confirmed = updated.get("metadata") if metadata else updated["volumes"][index]["chain"][-1]
        if not confirmed or not confirmed.get("completed") or not confirmed.get("externalId"):
            raise RuntimeError("External artifact transfer did not complete")
        if (any(confirmed.get(key) != artifact.get(key) for key in ("path", "backupUuid"))
                or (not metadata and confirmed.get("size") != artifact.get("size"))):
            raise RuntimeError("External confirmation differs from the requested artifact")
        self.manifest = updated
        if metadata:
            validate_completed_transfers(self.plan, updated)
            # The external backup is already complete. A later local IO failure
            # must never cause the current source checkpoint to be discarded.
            self.success = True
            record_completed_transfers(self.plan, self.job, updated)
        (self.job / "volume-request.json").unlink(missing_ok=True)
        if not metadata:
            Path(artifact["path"]).unlink()
        self.progress("METADATA_TRANSFER" if metadata else self.plan["providerStep"], index, 1)

    def execute(self):
        if self.job.name != self.manifest["backupUuid"]:
            raise RuntimeError("Source preparation belongs to another backup")
        start = json.loads(self.source_start.read_text())
        if (start != {"version": 1, "backupUuid": self.manifest["backupUuid"], "state": "NOT_STARTED"}
                or self.engine.exists() or admission_closed_reason(self.job) is not None):
            raise RuntimeError("Backup engine was already initialized; its source start record is protected")
        if not self.plan["rbd"]:
            self.preflight_qcow2()
        self.check_cancel()
        self.reserve()
        self.root.mkdir(parents=True, exist_ok=False)
        atomic(self.root / ".staging.inprogress", "volume_pipeline=true\n")
        # The parent external UI job must leave its pre script and finish before child transfers start.
        atomic(self.root / ".volume-bootstrap", self.manifest["backupUuid"] + "\n")
        atomic(self.job / "volume-request.json", {"gate": True, "backupUuid": self.manifest["backupUuid"]})
        self.progress("PREPARING", 0)
        deadline = time.monotonic() + self.timeout
        while not (self.job / "volume-start").exists():
            self.check_cancel()
            if time.monotonic() >= deadline:
                raise RuntimeError("External parent job did not release the source preparation gate")
            time.sleep(1)
        self.check_cancel()
        # Persist the intent before any libvirt/Ceph operation. Missing or ambiguous
        # records cannot be treated as proof that the source was never touched.
        atomic(self.source_start, {"version": 1, "backupUuid": self.manifest["backupUuid"], "state": "STARTED"})
        (self.job / "volume-request.json").unlink(missing_ok=True)
        try:
            xml = run(["virsh", "-c", "qemu:///system", "dumpxml", self.vm])
            atomic(self.root / "domain-config.xml", xml)
        except RuntimeError:
            pass
        for command in ("dominfo", "domiflist", "domblklist"):
            with contextlib.suppress(RuntimeError):
                atomic(self.root / (command + ".xml"), run(["virsh", "-c", "qemu:///system", command, self.vm]))
        if self.plan["rbd"]:
            self.begin_rbd()
        else:
            self.begin_qcow2()
        for index, volume in enumerate(self.manifest["volumes"]):
            artifact = volume["chain"][-1]
            target = Path(artifact["path"])
            if target.parent != self.root or target.exists():
                raise RuntimeError("Invalid or existing artifact destination")
            self.check_cancel()
            if self.plan["rbd"]:
                self.export_rbd(index, target)
            else:
                self.export_qcow2(index, target)
            artifact["size"] = target.stat().st_size
            # A catalog acknowledgment is mandatory; no latest-backup/time proximity inference.
            self.transfer(index, artifact)
        if self.pull:
            run(["virsh", "-c", "qemu:///system", "domjobabort", self.domain])
            self.pull = False
        self.manifest["complete"] = True
        atomic(self.root / "backup-manifest.json", self.manifest)
        # The final external metadata job must include the complete marker as well as XML/manifest.
        # The Host job remains RUNNING until this metadata transfer is acknowledged.
        atomic(self.root / ".staging.complete", "volume_pipeline=true\nbackup_uuid=%s\n" % self.manifest["backupUuid"])
        (self.root / ".staging.inprogress").unlink(missing_ok=True)
        metadata = {"backupUuid": self.manifest["backupUuid"], "path": str(self.root),
                    "sourceHost": self.plan["sourceHost"], "completed": False}
        self.transfer(self.count, metadata, True)
        self.progress("FINALIZING", self.count, 1)

    def close(self):
        thaw_error = None
        try:
            self.thaw_guest()
        except Exception as exc:
            # Keep the durable freeze ownership proof and still attempt to stop readers.
            thaw_error = exc
        if self.success:
            if thaw_error:
                raise RuntimeError("Guest thaw is unconfirmed; finalization remains pending") from thaw_error
            finalize_host(self.plan, self.job, self.manifest)
            self.pull = False
            return
        if self.pull:
            try:
                run(["virsh", "-c", "qemu:///system", "domjobabort", self.domain], 30)
                info = run(["virsh", "-c", "qemu:///system", "domjobinfo", self.domain], 30)
                if not re.search(r"Job type:\s+None(?:\s|$)", info):
                    raise RuntimeError("Abort accepted but source job termination is unconfirmed")
                self.pull = False
            except Exception:
                print("QCOW2 backup cleanup is pending; capacity reservation is retained", flush=True)
        if self.dummy:
            try:
                run(["virsh", "-c", "qemu:///system", "destroy", self.domain], 30)
                self.pull = False
            except Exception:
                print("Stopped VM backup domain cleanup is pending", flush=True)
                self.pull = True
        # The final metadata acknowledgment may be delayed beyond engine exit.
        # Only controller reconciliation may decide whether to remove checkpoints.
        if thaw_error:
            raise RuntimeError("Guest thaw remains pending; source cleanup evidence is retained") from thaw_error


def validate_completed_transfers(plan, manifest):
    """Accept exact catalog confirmations for every artifact in the saved plan."""
    expected = plan["manifest"]
    if (manifest.get("version") != 1 or manifest.get("complete") is not True
            or any(manifest.get(key) != expected.get(key)
                   for key in ("backupUuid", "provider", "vmName", "timestamp", "backupType", "parentBackupUuid"))):
        raise RuntimeError("Transfer completion differs from the saved backup plan")
    volumes = manifest.get("volumes", [])
    if len(volumes) != len(expected["volumes"]):
        raise RuntimeError("Transfer completion has a different volume count")
    owned = []
    for volume, source in zip(volumes, expected["volumes"]):
        if (any(volume.get(key) != source.get(key) for key in ("uuid", "deviceId", "provisionedBytes", "engine"))
                or len(volume.get("chain", [])) != len(source["chain"])):
            raise RuntimeError("Transfer completion has a different source volume")
        for artifact, original in zip(volume["chain"], source["chain"]):
            if any(artifact.get(key) != original.get(key)
                   for key in ("path", "backupUuid", "checkpointName", "parentCheckpointName")):
                raise RuntimeError("Transfer completion has a different artifact chain")
            if (artifact.get("completed") is not True or artifact.get("submissionPending")
                    or not all(artifact.get(key) for key in ("jobId", "externalId", "sourceHost"))):
                raise RuntimeError("A volume transfer is not confirmed")
        owned.append(volume["chain"][-1])
    metadata = manifest.get("metadata")
    if (not metadata or metadata.get("completed") is not True or metadata.get("submissionPending")
            or metadata.get("path") != plan["backupPath"]
            or not all(metadata.get(key) for key in ("jobId", "externalId", "sourceHost"))):
        raise RuntimeError("The final metadata transfer is not confirmed")
    for artifact in owned + [metadata]:
        # Catalog clients may use the Host's IP instead of its Mold name.
        # Host ownership comes from the immutable local plan and job directory.
        if artifact.get("backupUuid") != expected["backupUuid"]:
            raise RuntimeError("Transfer completion belongs to another backup")


def record_completed_transfers(plan, job, manifest):
    validate_completed_transfers(plan, manifest)
    if job.name != manifest["backupUuid"]:
        raise RuntimeError("Transfer completion belongs to another Host job")
    receipt = job / "volume-transfer-complete.json"
    if receipt.exists():
        recorded = json.loads(receipt.read_text())
        if recorded.get("version") != 1 or recorded.get("backupUuid") != job.name or recorded.get("state") != "COMPLETED":
            raise RuntimeError("Existing transfer completion ownership is unconfirmed")
        previous = recorded["manifest"]
        validate_completed_transfers(plan, previous)
        old = [v["chain"][-1] for v in previous["volumes"]] + [previous["metadata"]]
        new = [v["chain"][-1] for v in manifest["volumes"]] + [manifest["metadata"]]
        if any(any(a.get(key) != b.get(key) for key in ("jobId", "externalId", "path", "size", "sourceHost"))
               for a, b in zip(old, new)):
            raise RuntimeError("Confirmed external artifact references cannot be replaced")
        return
    atomic(receipt, {"version": 1, "backupUuid": job.name, "state": "COMPLETED", "manifest": manifest})


def finalize_host(plan, job, manifest, purge_staging=False):
    """Retry local cleanup without transferring data or deleting the current checkpoint."""
    record_completed_transfers(plan, job, manifest)
    finalization = job / "volume-finalization.json"
    recorded = {}
    if finalization.exists():
        recorded = json.loads(finalization.read_text())
        if recorded.get("version") != 1 or recorded.get("backupUuid") != job.name:
            raise RuntimeError("Existing Host finalization ownership is unconfirmed")
    # The engine finishes first. Only the controller can authorize metadata removal,
    # after its checkpoint XML has been durably saved outside staging.
    cleanup_started = (recorded.get("state") == "COMPLETED"
                       and recorded.get("stagingCleanupState") in ("WAITING", "COMPLETED")
                       and recorded.get("stagingPath") == plan["backupPath"])
    stage, root, engine = backup_cleanup_paths(plan, job, completed_cleanup=cleanup_started)
    if not engine.is_file():
        raise RuntimeError("Source engine and original staging filesystem identity are unconfirmed")
    if recorded.get("state") != "COMPLETED":
        atomic(finalization, {"version": 1, "backupUuid": job.name, "state": "WAITING"})
        cleanup_source(plan, job, engine, keep_checkpoint=True)
        for volume in manifest["volumes"]:
            artifact = Path(volume["chain"][-1]["path"])
            if artifact.parent != root or artifact.is_symlink():
                raise RuntimeError("Invalid completed artifact cleanup destination")
            artifact.unlink(missing_ok=True)
    reservations = stage / ".volume-reservations"
    with (reservations / "capacity.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        close_admission(job, "Backup staging completed\n")
        remove_reservation(reservations / (job.name + ".json"))
    result = {"version": 1, "backupUuid": job.name, "state": "COMPLETED"}
    if purge_staging or cleanup_started:
        # This receipt allows a partial rmtree to be retried even if it already
        # removed the in-directory ownership marker. Plan/mount checks still apply.
        result.update(stagingCleanupState="WAITING", stagingPath=plan["backupPath"])
        atomic(finalization, result)
        if root.exists():
            validate_job_directory(manifest["provider"], root, stage / manifest["provider"])
            shutil.rmtree(root)
        try:
            root.parent.rmdir()
        except FileNotFoundError:
            pass
        except OSError as exc:
            if exc.errno not in (errno.ENOTEMPTY, errno.EEXIST):
                raise
        parent = root.parent if root.parent.exists() else root.parent.parent
        fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        result["stagingCleanupState"] = "COMPLETED"
    atomic(finalization, result)
    return result


def retire_parent_snapshots(plan, job):
    """Retain the current checkpoint and durably retry only the recorded parent."""
    receipt = job / "source-cleanup.json"
    result = {"version": 1 if plan["rbd"] else 2, "state": "WAITING", "backupUuid": plan["manifest"]["backupUuid"],
              "checkpointName": plan["checkpointName"], "parentCheckpointName": plan.get("parentCheckpointName")}
    if not plan["rbd"]:
        result.update(engine="QCOW2", diskPaths=plan["diskPaths"])
    atomic(receipt, result)
    errors = []
    if plan["rbd"] and plan.get("parentCheckpointName"):
        if plan["parentCheckpointName"] == plan["checkpointName"]:
            raise RuntimeError("Refusing to retire the current RBD checkpoint")
        for uri in plan["diskPaths"]:
            args, image = Backup.rbd_command(uri)
            try:
                snapshots = json.loads(run(args + ["snap", "ls", "--format", "json", image], 30))
                if any(item["name"] == plan["parentCheckpointName"] for item in snapshots):
                    run(args + ["snap", "rm", image + "@" + plan["parentCheckpointName"]], 30)
            except Exception as exc:
                errors.append("%s@%s: %s" % (image, plan["parentCheckpointName"], exc))
    elif not plan["rbd"] and plan.get("parentCheckpointName"):
        try:
            retire_parent_qcow2(plan, job)
        except Exception as exc:
            errors.append(str(exc))
    result.update(state="WAITING" if errors else "COMPLETED", errors=errors)
    atomic(receipt, result)
    return not errors


def checkpoint_bitmaps(checkpoint, name):
    if checkpoint.tag != "domaincheckpoint" or checkpoint.findtext("name") != name:
        raise RuntimeError("Source checkpoint identity is unconfirmed")
    result = {}
    for disk in checkpoint.findall("./disks/disk"):
        if disk.get("checkpoint") == "bitmap":
            label = disk.get("name")
            if not label or label in result:
                raise RuntimeError("Source checkpoint disk identity is unconfirmed")
            result[label] = disk.get("bitmap") or name
    if not result:
        raise RuntimeError("Source checkpoint has no confirmed bitmap bindings")
    return result


def retire_parent_qcow2(plan, job):
    """Retire only the immediate parent's bitmaps; retain checkpoint metadata/XML."""
    parent, current = plan["parentCheckpointName"], plan["checkpointName"]
    if parent == current:
        raise RuntimeError("Refusing to retire the current QCOW2 checkpoint")
    volumes = plan["manifest"]["volumes"]
    if (job.name != plan["manifest"]["backupUuid"] or len(volumes) != len(plan["diskPaths"])
            or any(v["chain"][-1].get("parentCheckpointName") != parent
                   or v["chain"][-1].get("checkpointName") != current for v in volumes)):
        raise RuntimeError("Parent checkpoint does not match the completed backup plan")
    previous = checkpoint_bitmaps(parent_checkpoint_xml(plan), parent)
    baseline = checkpoint_bitmaps(ET.fromstring((job / "checkpoint.xml").read_text()), current)
    labels = [disk.get("name") for disk in ET.fromstring((job / "pull.xml").read_text()).findall("./disks/disk")]
    if (len(labels) != len(volumes) or len(set(labels)) != len(labels)
            or set(labels) != set(previous) or set(labels) != set(baseline)
            or set(previous.values()).intersection(baseline.values())):
        raise RuntimeError("Parent and current source bitmap bindings are unconfirmed")
    domain = plan["vmName"]
    offline = guest_is_off_or_absent(domain)
    domains = run(["virsh", "-c", "qemu:///system", "list", "--all", "--name"]).splitlines()
    present = domain in domains
    names = run(["virsh", "-c", "qemu:///system", "checkpoint-list", domain, "--name"]).splitlines() if present else []
    if parent in names:
        actual = ET.fromstring(run(["virsh", "-c", "qemu:///system", "checkpoint-dumpxml", domain, parent, "--no-domain"]))
        if checkpoint_bitmaps(actual, parent) != previous:
            raise RuntimeError("Parent checkpoint metadata differs from the saved source plan")
    paths = dict(zip(labels, plan["diskPaths"]))
    if len({str(Path(path).resolve()) for path in paths.values()}) != len(paths):
        raise RuntimeError("Source disk paths are not unique")

    def monitor(command, arguments=None):
        request = {"execute": command}
        if arguments is not None:
            request["arguments"] = arguments
        response = json.loads(run(["virsh", "-c", "qemu:///system", "qemu-monitor-command", domain, json.dumps(request)]))
        if "return" not in response or "error" in response:
            raise RuntimeError("QCOW2 bitmap monitor operation is unconfirmed: " + command)
        return response["return"]

    def inventory():
        if offline:
            result = {}
            for label, path in paths.items():
                # Default image locking must succeed. Never use --force-share/-U.
                chain = json.loads(run(["qemu-img", "info", "--backing-chain", "--output=json", path]))
                if not isinstance(chain, list) or not chain:
                    raise RuntimeError("Source image chain is unconfirmed")
                info = chain[0]
                if info.get("format") != "qcow2":
                    raise RuntimeError("Source disk is no longer QCOW2")
                if any(bitmap.get("name") == previous[label] for image in chain[1:]
                       for bitmap in image.get("format-specific", {}).get("data", {}).get("bitmaps", [])):
                    raise RuntimeError("Parent bitmap remains in a backing layer; cleanup requires the live source VM")
                bitmaps = info.get("format-specific", {}).get("data", {}).get("bitmaps", [])
                result[label] = (None, {bitmap["name"]: bitmap for bitmap in bitmaps})
            return result
        info = run(["virsh", "-c", "qemu:///system", "domjobinfo", domain])
        if not re.search(r"Job type:\s+None", info, re.IGNORECASE):
            raise RuntimeError("Source VM has an active job; parent cleanup is deferred")
        result = {}
        backing_nodes = None
        for device in monitor("query-block"):
            inserted = device.get("inserted", {})
            source = inserted.get("image", {}).get("filename") or inserted.get("file")
            for label, path in paths.items():
                if source and Path(source).resolve() == Path(path).resolve():
                    if label in result or not inserted.get("node-name"):
                        raise RuntimeError("Live source disk identity is ambiguous")
                    backing = inserted.get("image", {}).get("backing-filename")
                    visited = {str(Path(path).resolve())}
                    while backing:
                        backing_path = str((Path(source).parent / backing).resolve())
                        if backing_path in visited:
                            raise RuntimeError("Source backing chain is cyclic")
                        visited.add(backing_path)
                        if backing_nodes is None:
                            backing_nodes = monitor("query-named-block-nodes")
                        matches = [node for node in backing_nodes if node.get("drv") != "file"
                                   and node.get("image", {}).get("filename")
                                   and str(Path(node["image"]["filename"]).resolve()) == backing_path]
                        if len(matches) != 1:
                            raise RuntimeError("Source backing bitmap ownership is unconfirmed")
                        if any(bitmap.get("name") == previous[label] for bitmap in matches[0].get("dirty-bitmaps", [])):
                            raise RuntimeError("Parent bitmap remains in a backing layer; shared backing data is protected")
                        source = backing_path
                        backing = matches[0].get("image", {}).get("backing-filename")
                    result[label] = (inserted["node-name"], {bitmap["name"]: bitmap for bitmap in inserted.get("dirty-bitmaps", [])})
        if set(result) != set(paths):
            raise RuntimeError("Live source disks do not match the saved backup plan")
        return result

    def valid_baseline(bitmap):
        return (bitmap and not bitmap.get("inconsistent") and "inconsistent" not in bitmap.get("flags", [])
                and ("auto" in bitmap.get("flags", []) if offline else bitmap.get("recording") and bitmap.get("persistent")))

    before = inventory()
    if all(previous[label] not in bitmaps for label, (_, bitmaps) in before.items()):
        return
    # Validate every disk before touching the first one. An absent parent is idempotent.
    for label, (_, bitmaps) in before.items():
        bitmap = bitmaps.get(baseline[label])
        if not valid_baseline(bitmap):
            raise RuntimeError("Current incremental bitmap is unconfirmed on disk " + label)
        if previous[label] in bitmaps and bitmaps[previous[label]].get("busy"):
            raise RuntimeError("Parent bitmap is busy on disk " + label)
    for label, (node, bitmaps) in before.items():
        if previous[label] not in bitmaps:
            continue
        if offline:
            run(["qemu-img", "bitmap", "--remove", paths[label], previous[label]])
        else:
            monitor("block-dirty-bitmap-remove", {"node": node, "name": previous[label]})
    after = inventory()
    if any(previous[label] in bitmaps or not valid_baseline(bitmaps.get(baseline[label])) for label, (_, bitmaps) in after.items()):
        raise RuntimeError("Parent bitmap removal or current baseline preservation is unconfirmed")


def guest_is_off_or_absent(vm):
    try:
        return run(["virsh", "-c", "qemu:///system", "domstate", vm], 30).strip() == "shut off"
    except (RuntimeError, subprocess.TimeoutExpired):
        # A failed lookup alone must never authorize cleanup.
        return vm not in run(["virsh", "-c", "qemu:///system", "list", "--all", "--name"], 30).splitlines()


def thaw_owned_guest(receipt, vm):
    if not receipt.exists():
        return
    engine = json.loads(receipt.read_text())
    if engine.get("freezePending"):
        try:
            state = json.loads(run(["virsh", "-c", "qemu:///system", "qemu-agent-command", vm,
                                    '{"execute":"guest-fsfreeze-status"}'], 30))
            if state.get("return") != "thawed":
                run(["virsh", "-c", "qemu:///system", "qemu-agent-command", vm,
                     '{"execute":"guest-fsfreeze-thaw"}'], 30)
        except (RuntimeError, subprocess.TimeoutExpired):
            if not guest_is_off_or_absent(vm):
                raise
        engine["freezePending"] = False
        atomic(receipt, engine)


def cleanup_source(plan, job, receipt, keep_checkpoint=False):
    """Stop owned readers; preserve confirmed backups' current checkpoint."""
    manifest = plan["manifest"]
    job_id = manifest["backupUuid"]
    thaw_owned_guest(receipt, plan["vmName"])
    # No owner process may remain, including children orphaned by an engine crash.
    payloads = {volume["chain"][-1]["path"] for volume in manifest["volumes"]}
    for process in Path("/proc").iterdir():
        if not process.name.isdigit():
            continue
        try:
            args = process.joinpath("cmdline").read_bytes().decode().split("\0")
        except (FileNotFoundError, ProcessLookupError):
            continue
        if args and Path(args[0]).name == "qemu-nbd" and "--socket" in args:
            socket = Path(args[args.index("--socket") + 1])
            if socket.parent == job and socket.name.startswith("target-"):
                os.kill(int(process.name), signal.SIGTERM)
                raise RuntimeError("Stopped an orphaned job NBD helper; waiting for it to exit")
        if args and Path(args[0]).name in {"rbd", "qemu-img"} and payloads.intersection(args):
            raise RuntimeError("A source export process still owns this backup payload")
    if plan["rbd"]:
        if not keep_checkpoint:
            for uri in plan["diskPaths"]:
                args, image = Backup.rbd_command(uri)
                snapshots = json.loads(run(args + ["snap", "ls", "--format", "json", image]))
                if any(item["name"] == plan["checkpointName"] for item in snapshots):
                    run(args + ["snap", "rm", image + "@" + plan["checkpointName"]])
        return
    domain = "DUMMY-VOLUME-" + job_id if (job / "dummy.xml").exists() else plan["vmName"]
    state = subprocess.run(["virsh", "-c", "qemu:///system", "domstate", domain], capture_output=True, text=True,
                           timeout=30, env=dict(os.environ, LC_ALL="C"))
    if not plan["rbd"] and state.returncode == 0:
        active = state.stdout.strip().lower() != "shut off"
        if active:
            info = run(["virsh", "-c", "qemu:///system", "domjobinfo", domain])
            if not re.search(r"Job type:\s+None", info, re.IGNORECASE):
                xml = ET.fromstring(run(["virsh", "-c", "qemu:///system", "backup-dumpxml", domain]))
                server = xml.find("server")
                if server is None or server.get("socket") != "/var/lib/libvirt/qemu/backup-%s.sock" % job_id:
                    raise RuntimeError("A different libvirt job is active; original VM is protected")
                run(["virsh", "-c", "qemu:///system", "domjobabort", domain])
                if not re.search(r"Job type:\s+None", run(["virsh", "-c", "qemu:///system", "domjobinfo", domain]), re.IGNORECASE):
                    raise RuntimeError("Source pull backup termination is unconfirmed")
        if domain.startswith("DUMMY-VOLUME-"):
            if active:
                run(["virsh", "-c", "qemu:///system", "destroy", domain])
        elif not keep_checkpoint:
            names = run(["virsh", "-c", "qemu:///system", "checkpoint-list", domain, "--name"]).splitlines()
            if plan["checkpointName"] in names:
                run(["virsh", "-c", "qemu:///system", "checkpoint-delete", domain, plan["checkpointName"], "--metadata"])
    elif not plan["rbd"]:
        # A failed lookup is not proof that a domain vanished.
        domains = run(["virsh", "-c", "qemu:///system", "list", "--all", "--name"]).splitlines()
        if domain in domains:
            raise RuntimeError("Source domain state could not be confirmed")
    if (job / "pull.xml").exists():
        xml = ET.fromstring(job.joinpath("pull.xml").read_text())
        parents = {Path(path).resolve().parent for path in plan["diskPaths"]}
        for scratch in xml.findall("./disks/disk/scratch"):
            path = Path(scratch.get("file", ""))
            if path.parent.resolve() not in parents or not path.name.startswith(".backup-" + job_id + "-"):
                raise RuntimeError("Scratch file ownership does not match this backup")
            path.unlink(missing_ok=True)


def backup_cleanup_paths(plan, job, completed_cleanup=False):
    manifest = plan["manifest"]
    job_id = manifest["backupUuid"]
    stage = Path(plan["stageRoot"]).resolve(strict=True)
    root = Path(plan["backupPath"])
    expected = stage / manifest["provider"] / manifest["vmName"] / manifest["timestamp"]
    validate_job_directory(manifest["provider"], root, stage / manifest["provider"])
    if root.resolve() != expected.resolve() or job.name != job_id or root.is_symlink():
        raise RuntimeError("Invalid backup cleanup destination")
    if root.exists():
        marker = root / ".volume-bootstrap"
        if (marker.is_symlink() or (marker.exists() and marker.read_text().strip() != job_id)
                or (not marker.is_file() and not completed_cleanup)):
            raise RuntimeError("Backup directory ownership is unconfirmed; existing data is protected")
    receipt = job / "volume-engine.json"
    if receipt.exists():
        engine = json.loads(receipt.read_text())
        identity = stage / ".volume-reservations" / "filesystem.id"
        if engine.get("stageFilesystemId"):
            if not identity.is_file() or identity.read_text().strip() != engine["stageFilesystemId"]:
                raise RuntimeError("Staging filesystem changed; mount the original filesystem before cleanup")
        elif engine["stageDevice"] != stage.stat().st_dev:
            raise RuntimeError("Staging filesystem changed; mount the original filesystem before cleanup")
    return stage, root, receipt


def cleanup_failed(file, source_not_started=False):
    """Clean after writer termination, or durable proof that source IO never began."""
    plan = json.loads(file.read_text())
    manifest = plan["manifest"]
    job_id = manifest["backupUuid"]
    job = file.parent
    if (job / "volume-transfer-complete.json").exists():
        raise RuntimeError("Confirmed transfers require finalization instead of failed backup cleanup")
    stage, root, receipt = backup_cleanup_paths(plan, job)
    if source_not_started:
        start_file = job / "volume-source-start.json"
        start = json.loads(start_file.read_text())
        if start != {"version": 1, "backupUuid": job_id, "state": "NOT_STARTED"}:
            raise RuntimeError("Source preparation was started or its receipt is unconfirmed")
        if ((job / "pull.xml").exists() or (job / "dummy.xml").exists()
                or (receipt.exists() and json.loads(receipt.read_text()).get("freezePending"))
                or any(Path(volume["chain"][-1]["path"]).exists() for volume in manifest["volumes"])):
            raise RuntimeError("Source resources conflict with the NOT_STARTED receipt")
    else:
        cleanup_source(plan, job, receipt)
    if root.exists():
        validate_job_directory(manifest["provider"], root, stage / manifest["provider"])
        shutil.rmtree(root)
    reservations = stage / ".volume-reservations"
    reservations.mkdir(mode=0o700, exist_ok=True)
    with (reservations / "capacity.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        # The receipt also survives subsequent Host job record cleanup.
        close_admission(job, "Backup staging cleanup completed\n")
        remove_reservation(reservations / (job_id + ".json"))
    atomic(job / "volume-cleanup.json", {"state": "COMPLETED"})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan-file", required=True)
    parser.add_argument("--action", choices=["backup", "cleanup", "cleanup-unstarted", "cleanup-completed", "finalize"], default="backup")
    parser.add_argument("--completed-manifest-file")
    parser.add_argument("--result-file")
    args = parser.parse_args()
    file = Path(args.plan_file)
    with (file.parent / "volume-engine.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action in ("cleanup", "cleanup-unstarted"):
            cleanup_failed(file, args.action == "cleanup-unstarted")
            return 0
        if args.action == "cleanup-completed":
            plan = json.loads(file.read_text())
            if file.parent.name != plan["manifest"]["backupUuid"]:
                raise RuntimeError("Completed source cleanup belongs to another backup")
            return 0 if retire_parent_snapshots(plan, file.parent) else 1
        if args.action == "finalize":
            plan = json.loads(file.read_text())
            manifest = json.loads(Path(args.completed_manifest_file).read_text())
            result = finalize_host(plan, file.parent, manifest, purge_staging=True)
            if args.result_file:
                atomic(Path(args.result_file), result)
            return 0
        return run_backup(args.plan_file)


def claim_backup_start(plan_file):
    """Use the Agent's POSIX record lock before any source or staging IO."""
    file = Path(plan_file)
    source = json.loads(file.read_text())
    plan = source.get("backupStartPlan")
    if plan is None:
        return  # Existing jobs keep their original engine protocol.
    job_id = file.parent.name
    if (plan.get("version") != 1 or plan.get("jobId") != job_id
            or plan.get("manifest") != source.get("manifest") or plan.get("backupPath") != source.get("backupPath")):
        raise RuntimeError("Backup start plan differs from the source plan")
    directory = Path("/var/lib/ablestack/backup/backup-start")
    with (directory / (job_id + ".lock")).open("a") as lock:
        fcntl.lockf(lock, fcntl.LOCK_EX)
        receipt_file = directory / (job_id + ".json")
        receipt = json.loads(receipt_file.read_text())
        if receipt.get("version") != 1 or receipt.get("plan") != plan or receipt.get("state") != "DISPATCHED":
            raise RuntimeError("Backup start is unprepared, already initialized or permanently blocked")
        receipt["state"] = "STARTED"
        receipt["checkedAt"] = int(time.time() * 1000)
        atomic(receipt_file, receipt)


def run_backup(plan_file):
    claim_backup_start(plan_file)
    file = Path(plan_file)
    source = json.loads(file.read_text())
    job = file.parent
    if (job.name != source["manifest"]["backupUuid"] or (job / "volume-source-start.json").exists()
            or (job / "volume-engine.json").exists()):
        raise RuntimeError("Backup engine was already initialized; its source start record is protected")
    # This proof also covers constructor/configuration failures before execute().
    # Never reset it on a repeated or delayed engine invocation.
    atomic(job / "volume-source-start.json", {"version": 1, "backupUuid": job.name, "state": "NOT_STARTED"})
    def interrupt(signum, frame):
        raise InterruptedError("Volume pipeline interrupted")
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    worker = None
    failure = None
    cleanup_failure = None
    finalized = False
    try:
        worker = Backup(plan_file)
        worker.execute()
    except Exception as exc:
        failure = exc
        if not source["rbd"]:
            # QCOW2 commands contain local file paths, never RBD/controller credentials.
            traceback.print_exception(type(exc), exc, exc.__traceback__, file=sys.stdout)
            sys.stdout.flush()
    finally:
        if worker is not None:
            try:
                worker.close()
                finalized = worker.success
            except Exception as exc:
                cleanup_failure = exc
    # The Agent uses the last non-trace log line as the UI failure reason.
    # Emit a compact summary after diagnostics and cleanup, preserving the initial failure.
    if not finalized:
        message = " ".join(str(failure or cleanup_failure or "Backup did not finalize").split())
        label = "Volume backup finalization is pending" if worker is not None and worker.success else "Volume backup failed"
        if cleanup_failure and failure:
            message += "; host cleanup is pending: " + " ".join(str(cleanup_failure).split())
        elif worker is not None and worker.pull:
            message += "; source cleanup is pending and capacity reservation is retained"
        print("%s: %s" % (label, message), flush=True)
    elif failure:
        print("Host backup finalization completed", flush=True)
    return 0 if finalized else 1


if __name__ == "__main__":
    raise SystemExit(main())
