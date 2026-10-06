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
import fcntl
import hashlib
import json
import os
import re
from pathlib import Path
import signal
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

BLOCK = 4 * 1024 * 1024


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
    # Never include Ceph keys or controller credentials in errors or the job log.
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError("%s failed (exit %d)" % (args[0], result.returncode))
    return result.stdout


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
        self.created_snapshots = []
        self.success = False
        self.socket = Path("/var/lib/libvirt/qemu") / ("backup-" + self.manifest["backupUuid"] + ".sock")
        self.reservation = None
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
        if (self.job / "volume-cancel").exists():
            raise RuntimeError("Volume pipeline was cancelled")

    def reserve(self):
        stage_root = Path(self.plan["stageRoot"]).resolve(strict=True)
        if not self.root.is_absolute() or not self.root.resolve().is_relative_to(stage_root):
            raise RuntimeError("Backup path is outside configured third-party staging")
        directory = stage_root / ".volume-reservations"
        directory.mkdir(mode=0o700, exist_ok=True)
        required = max(v["provisionedBytes"] for v in self.manifest["volumes"])
        required += (required * self.plan["bufferPercent"] + 99) // 100
        with (directory / "capacity.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            reservations = [json.loads(p.read_text()) for p in directory.glob("*.json")]
            reserved = sum(record["bytes"] + record.get("primaryScratchBytes", 0) for record in reservations)
            stat = os.statvfs(stage_root)
            if required > stat.f_bavail * stat.f_frsize - reserved:
                raise RuntimeError("Insufficient effective staging capacity including active reservations")
            self.reservation = directory / (self.manifest["backupUuid"] + ".json")
            if self.reservation.exists():
                raise RuntimeError("Staging reservation already exists for this backup")
            atomic(self.reservation, {"jobId": self.manifest["backupUuid"], "bytes": required,
                                      "host": self.plan["sourceHost"], "operation": "BACKUP"})

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
        frozen = False
        try:
            if self.plan.get("quiesce"):
                try:
                    run(["virsh", "-c", "qemu:///system", "qemu-agent-command", self.vm,
                         '{"execute":"guest-fsfreeze-freeze"}'], 30)
                    frozen = True
                except RuntimeError:
                    pass
            for uri in self.plan["diskPaths"]:
                args, image = self.rbd_command(uri)
                run(args + ["snap", "create", image + "@" + checkpoint])
                self.created_snapshots.append((args, image, checkpoint))
        finally:
            if frozen:
                run(["virsh", "-c", "qemu:///system", "qemu-agent-command", self.vm,
                     '{"execute":"guest-fsfreeze-thaw"}'], 30)
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
        try:
            import nbd
        except ImportError as exc:
            raise RuntimeError("Volume QCOW2 staging requires the host python3-libnbd package") from exc
        self.nbd = nbd
        # Pull-mode scratch belongs to primary storage, never to Host OS or staging.
        required = {}
        for path, volume in zip(self.plan["diskPaths"], self.manifest["volumes"]):
            parent = Path(path).resolve(strict=True).parent
            device = parent.stat().st_dev
            entry = required.setdefault(device, [parent, 0])
            entry[1] += volume["provisionedBytes"]
        for parent, amount in required.values():
            stat = os.statvfs(parent)
            overhead = max(10 * 1024**3, amount // 5)
            if parent.stat().st_dev == self.root.stat().st_dev:
                # When staging uses primary GFS2, source scratch and staged images compete
                # for the same free space. Reserve both under the shared admission lock.
                directory = self.reservation.parent
                with (directory / "capacity.lock").open("a") as lock:
                    fcntl.flock(lock, fcntl.LOCK_EX)
                    reservations = [json.loads(p.read_text()) for p in directory.glob("*.json")]
                    reserved = sum(record["bytes"] + record.get("primaryScratchBytes", 0) for record in reservations)
                    stat = os.statvfs(parent)
                    if amount + overhead > stat.f_bavail * stat.f_frsize - reserved:
                        raise RuntimeError("Insufficient shared primary/staging capacity including source scratch reservations")
                    record = json.loads(self.reservation.read_text())
                    record["primaryScratchBytes"] = amount + overhead
                    atomic(self.reservation, record)
            elif stat.f_bavail * stat.f_frsize < amount + overhead:
                raise RuntimeError("Insufficient primary capacity for a consistent QCOW2 pull backup")
        try:
            domain_xml = ET.fromstring(run(["virsh", "-c", "qemu:///system", "dumpxml", self.vm]))
            active = run(["virsh", "-c", "qemu:///system", "domstate", self.vm]).strip() == "running"
        except RuntimeError:
            domain_xml = ET.Element("domain", type="kvm")
            active = False
        self.live_bandwidth = active and self.manifest["provider"] == "ablestack-commvault"
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
                info = json.loads(run(["qemu-img", "info", "--output=json", path]))
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
            for name, xml in (self.plan.get("parentCheckpointXmlChain") or {}).items():
                path = self.job / ("checkpoint-" + str(uuid.uuid4()) + ".xml")
                atomic(path, xml)
                try:
                    run(["virsh", "-c", "qemu:///system", "checkpoint-info", self.domain, name])
                except RuntimeError:
                    run(["virsh", "-c", "qemu:///system", "checkpoint-create", self.domain,
                         "--xmlfile", str(path), "--redefine"])
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
        frozen = False
        try:
            if self.plan.get("quiesce") and active:
                try:
                    run(["virsh", "-c", "qemu:///system", "qemu-agent-command", self.vm,
                         '{"execute":"guest-fsfreeze-freeze"}'], 30)
                    frozen = True
                except RuntimeError:
                    pass
            run(["virsh", "-c", "qemu:///system", "backup-begin", self.domain,
                 "--backupxml", str(backup_path), "--checkpointxml", str(checkpoint_path)])
            self.pull = True
        finally:
            if frozen:
                run(["virsh", "-c", "qemu:///system", "qemu-agent-command", self.vm,
                     '{"execute":"guest-fsfreeze-thaw"}'], 30)
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
        source = self.nbd.NBD()
        source.set_export_name(self.labels[index])
        context = "qemu:dirty-bitmap:dirty-" + self.labels[index]
        incremental = bool(self.plan.get("parentCheckpointName"))
        if incremental:
            source.add_meta_context(context)
        source.connect_unix(str(self.socket))
        size = source.get_size()
        destination_socket = self.job / ("target-" + str(index) + ".sock")
        run(["qemu-img", "create", "-f", "qcow2", str(target), str(size)])
        destination = None
        process = subprocess.Popen(["qemu-nbd", "--socket", str(destination_socket),
                                    "--persistent", "--format=qcow2", str(target)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            deadline = time.monotonic() + self.timeout
            while not destination_socket.exists():
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Unable to open QCOW2 output image")
                time.sleep(0.1)
            destination = self.nbd.NBD()
            destination.connect_unix(str(destination_socket))
            if incremental and not source.can_meta_context(context):
                raise RuntimeError("Source does not expose the requested incremental dirty bitmap")
            offset = 0
            while offset < size:
                self.check_cancel()
                if time.monotonic() > deadline:
                    raise RuntimeError("QCOW2 volume export timed out")
                length = min(BLOCK, size - offset)
                dirty = True
                if incremental:
                    extents = []
                    def extent_callback(name, start, entries, error):
                        if name == context and start == offset:
                            extents.extend(entries)
                        return 0
                    source.block_status(length, offset, extent_callback, flags=self.nbd.CMD_FLAG_REQ_ONE)
                    if len(extents) < 2 or extents[0] <= 0:
                        raise RuntimeError("Invalid incremental dirty bitmap response")
                    length = min(length, extents[0])
                    dirty = bool(extents[1] & 1)
                if dirty:
                    block_started = time.monotonic()
                    data = source.pread(length, offset)
                    # Changed zero blocks must be allocated in an incremental overlay.
                    if incremental or any(data):
                        destination.pwrite(data, offset)
                    self.throttle(length, block_started)
                offset += length
                self.progress("QCOW2_BACKUP", index, offset / size)
            destination.flush()
        finally:
            if destination is not None:
                with contextlib.suppress(Exception):
                    destination.shutdown()
            with contextlib.suppress(Exception):
                source.shutdown()
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            destination_socket.unlink(missing_ok=True)
        if incremental:
            chain = self.manifest["volumes"][index]["chain"]
            if len(chain) < 2:
                raise RuntimeError("QCOW2 incremental volume is missing its parent artifact")
            run(["qemu-img", "rebase", "-u", "-f", "qcow2", "-F", "qcow2",
                 "-b", chain[-2]["path"], str(target)], self.timeout)

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
        self.manifest = updated
        (self.job / "volume-request.json").unlink(missing_ok=True)
        if not metadata:
            Path(artifact["path"]).unlink()
        self.progress("METADATA_TRANSFER" if metadata else self.plan["providerStep"], index, 1)

    def execute(self):
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
        self.success = True

    def close(self):
        if self.pull:
            try:
                run(["virsh", "-c", "qemu:///system", "domjobabort", self.domain], 30)
                self.pull = False
            except Exception:
                print("QCOW2 backup cleanup is pending; capacity reservation is retained", flush=True)
        if self.dummy:
            try:
                run(["virsh", "-c", "qemu:///system", "destroy", self.domain], 30)
                self.pull = False
            except Exception:
                print("Stopped VM backup domain cleanup is pending", flush=True)
        if not self.success:
            for args, image, checkpoint in reversed(self.created_snapshots):
                with contextlib.suppress(Exception):
                    run(args + ["snap", "rm", image + "@" + checkpoint], 30)
        elif self.plan["rbd"] and self.plan.get("parentCheckpointName"):
            # Keep the current checkpoint for the next export-diff; retire the previous snapshot
            # only after all image jobs and the final metadata job have been confirmed.
            for uri in self.plan["diskPaths"]:
                args, image = self.rbd_command(uri)
                try:
                    run(args + ["snap", "rm", image + "@" + self.plan["parentCheckpointName"]], 30)
                except Exception:
                    print("Previous RBD backup snapshot cleanup is pending", flush=True)
        if self.reservation and not self.pull:
            with (self.reservation.parent / "capacity.lock").open("a") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX)
                self.reservation.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan-file", required=True)
    args = parser.parse_args()
    worker = Backup(args.plan_file)
    def interrupt(signum, frame):
        raise RuntimeError("Volume pipeline interrupted")
    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    try:
        worker.execute()
    except Exception as exc:
        print("Volume backup failed: %s" % exc, flush=True)
        return 1
    finally:
        worker.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
