#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""ABLESTACK offline file guards. POSIX locks live on GFS2 and survive controller restarts."""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

DIRECTORY = ".ablestack-offline-raw"


def command(args, timeout=30):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False,
                            env=dict(os.environ, LC_ALL="C"))
    if result.returncode:
        raise RuntimeError("SHARED_RAW_UNCONFIRMED: %s inspection failed" % args[0])
    return result.stdout


def guard_files(path):
    path = Path(path)
    if not path.is_absolute() or path.is_symlink() or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", path.name):
        raise RuntimeError("SHARED_RAW_UNKNOWN: non-canonical source file")
    parent = path.parent.resolve(strict=True)
    fs = command(["findmnt", "--noheadings", "--output", "FSTYPE,OPTIONS", "--target", str(parent)]).strip().split()
    if len(fs) != 2 or fs[0] != "gfs2" or any(option.split("=", 1)[0] == "localflocks" for option in fs[1].split(",")):
        raise RuntimeError("SHARED_RAW_UNKNOWN: mounted GFS2 with cluster-wide locking is required")
    directory = parent / DIRECTORY
    if directory.is_symlink():
        raise RuntimeError("SHARED_RAW_UNKNOWN: guard directory is a symlink")
    directory.mkdir(mode=0o700, exist_ok=True)
    digest = hashlib.sha256(path.name.encode()).hexdigest()
    return directory / (digest + ".lock"), directory / (digest + ".json")


@contextlib.contextmanager
def locked(paths, job=None, require_owner=False):
    with contextlib.ExitStack() as stack:
        entries = []
        for path in sorted(set(map(str, paths))):
            lock, marker = guard_files(path)
            fd = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            stream = stack.enter_context(os.fdopen(fd, "r+"))
            try:
                fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError("SHARED_RAW_BUSY: file operation is still running") from None
            if marker.is_symlink():
                raise RuntimeError("SHARED_RAW_UNKNOWN: guard marker is a symlink")
            owner = json.loads(marker.read_text()) if marker.exists() else None
            if require_owner and (not owner or owner.get("jobId") != job or owner.get("version") != 1):
                raise RuntimeError("SHARED_RAW_UNCONFIRMED: offline ownership is missing or belongs to another job")
            entries.append((path, marker, owner))
        yield entries


def runtime_free(paths):
    """Inspect every local active domain, including paused guests and unknown consumers."""
    names = command(["virsh", "-c", "qemu:///system", "list", "--name"]).splitlines()
    sources = [Path(path).resolve() for path in paths]
    for name in filter(str.strip, names):
        domain = ET.fromstring(command(["virsh", "-c", "qemu:///system", "dumpxml", name.strip()]))
        for source in domain.findall("./devices/disk/source"):
            file = source.get("file")
            if file:
                candidate = Path(file).resolve()
                for path in sources:
                    if candidate == path or candidate.name == path.name or (path.exists() and candidate.exists() and os.path.samefile(path, candidate)):
                        raise RuntimeError("SHARED_RAW_IN_USE: an active domain still uses the source file")


def no_readers(paths):
    names = {str(path) for path in paths}
    for process in Path("/proc").iterdir():
        if not process.name.isdigit() or int(process.name) == os.getpid():
            continue
        try:
            args = process.joinpath("cmdline").read_bytes().decode(errors="replace").split("\0")
        except (FileNotFoundError, ProcessLookupError):
            continue
        if args and Path(args[0]).name in {"qemu-img", "qemu-nbd"} and names.intersection(args):
            raise RuntimeError("SHARED_RAW_BUSY: an export reader is still running")


def atomic(path, value):
    temporary = path.with_name(path.name + ".tmp-" + str(os.getpid()))
    fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump(value, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def control(action, plan, paths):
    if plan.get("version") != 1 or not re.fullmatch(r"[A-Za-z0-9_.-]+", plan.get("jobId", "")) or not paths:
        raise RuntimeError("SHARED_RAW_UNKNOWN: invalid offline plan")
    with locked(paths) as entries:
        if action == "PREPARE":
            runtime_free(paths)
            if plan.get("operation") == "BACKUP":
                if len(paths) != len(plan.get("disks", [])):
                    raise RuntimeError("Offline plan source count differs")
                for path, disk in zip(paths, plan["disks"]):
                    info = json.loads(command(["qemu-img", "info", "--output=json", path]))
                    if info.get("format") != disk.get("format") or info.get("virtual-size") != disk.get("bytes"):
                        raise RuntimeError("Offline source format or virtual size differs from its plan")
            for _, _, owner in entries:
                if owner is not None and owner != plan:
                    raise RuntimeError("SHARED_RAW_PROTECTED: a different operation owns this file")
            for _, marker, _ in entries:
                atomic(marker, plan)
        elif action in {"RELEASE", "ABORT_PREPARE"}:
            no_readers(paths)
            for _, _, owner in entries:
                if owner is not None and owner != plan:
                    raise RuntimeError("SHARED_RAW_PROTECTED: another operation replaced this owner")
            for _, marker, _ in entries:
                marker.unlink(missing_ok=True)
                fd = os.open(marker.parent, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(fd)
                finally:
                    os.close(fd)
        else:
            raise RuntimeError("Unknown shared RAW control operation")


@contextlib.contextmanager
def offline_sources(paths, job):
    with locked(paths, job, True) as entries:
        if any(owner.get("operation") != "BACKUP" for _, _, owner in entries):
            raise RuntimeError("SHARED_RAW_UNCONFIRMED: source is not owned by a backup")
        runtime_free(paths)
        before = [os.stat(path, follow_symlinks=False) for path in paths]
        def validate():
            runtime_free(paths)
            after = [os.stat(path, follow_symlinks=False) for path in paths]
            for first, last in zip(before, after):
                if (first.st_dev, first.st_ino, first.st_size, first.st_mtime_ns, first.st_ctime_ns) != (last.st_dev, last.st_ino, last.st_size, last.st_mtime_ns, last.st_ctime_ns):
                    raise RuntimeError("SHARED_RAW_SOURCE_CHANGED: offline source was modified during backup")
        yield validate
        validate()


def convert(source, destination, source_format, virtual_bytes, timeout, progress=None, cancel=None, bandwidth=0):
    """Create an independent Full in the source format; -S only preserves sparse zero regions."""
    target = Path(destination)
    if target.exists() or target.is_symlink() or source_format not in {"raw", "qcow2"} or target.suffix != "." + source_format:
        raise RuntimeError("Invalid offline Full conversion destination or format")
    args = ["qemu-img", "convert", "-p", "-f", source_format, "-O", source_format, "-S", "4k"]
    if bandwidth > 0:
        args += ["-r", str(bandwidth * 1000000 // 8)]
    log = target.with_name(target.name + ".convert.log")
    try:
        with log.open("w+") as output:
            process = subprocess.Popen(args + [str(source), str(target)], stdout=output, stderr=output,
                                       env=dict(os.environ, LC_ALL="C"))
            try:
                deadline = time.monotonic() + timeout
                while process.poll() is None:
                    if cancel:
                        cancel()
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Offline Full conversion timed out")
                    if progress:
                        output.flush()
                        with log.open() as reader:
                            status = re.findall(r"([0-9]+(?:\.[0-9]+)?)/100%", reader.read()[-4096:])
                        if status:
                            progress(float(status[-1]) / 100)
                    time.sleep(0.25)
                if process.returncode:
                    output.flush()
                    detail = log.read_text(errors="replace")[-2000:]
                    raise RuntimeError("Offline Full conversion failed: " + detail)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=30)
        info = json.loads(command(["qemu-img", "info", "--output=json", str(target)]))
        if info.get("format") != source_format or info.get("virtual-size") != virtual_bytes or any(k in info for k in ("backing-filename", "full-backing-filename", "data-file")):
            raise RuntimeError("Offline Full result is not an independent image of the expected size")
        with target.open("rb") as stream:
            os.fsync(stream.fileno())
        if progress:
            progress(1)
    finally:
        log.unlink(missing_ok=True)


def capture_configuration(plan, paths, destination):
    """Stopped Mold VMs may have no persistent libvirt definition; never boot a dummy."""
    name, uuid = plan.get("vmName"), plan.get("vmUuid")
    if not name or not uuid or len(paths) != len(plan.get("disks", [])):
        raise RuntimeError("Offline Full VM identity is missing")
    domains = command(["virsh", "-c", "qemu:///system", "list", "--all", "--name"]).splitlines()
    if name in [value.strip() for value in domains]:
        xml = command(["virsh", "-c", "qemu:///system", "dumpxml", name])
        domain = ET.fromstring(xml)
        if domain.tag != "domain" or domain.findtext("name") != name or domain.findtext("uuid") != uuid:
            raise RuntimeError("Offline VM configuration belongs to another VM")
    else:
        # This is explicitly an inventory/identity descriptor, not a bootable
        # libvirt definition. Mold reconstructs hardware/network from its stored backup details.
        domain = ET.Element("domain", type="kvm")
        ET.SubElement(domain, "name").text = name
        ET.SubElement(domain, "uuid").text = uuid
        metadata = ET.SubElement(domain, "metadata")
        record = ET.SubElement(metadata, "ablestack-offline-full", version="1", configuration="mold", bootable="false")
        record.text = json.dumps(plan.get("vmConfiguration", {}), ensure_ascii=False)
        devices = ET.SubElement(domain, "devices")
        for index, (path, source) in enumerate(zip(paths, plan["disks"])):
            disk = ET.SubElement(devices, "disk", type="file", device="disk")
            ET.SubElement(disk, "driver", name="qemu", type=source["format"])
            ET.SubElement(disk, "source", file=path)
            ET.SubElement(disk, "target", dev="vd" + chr(ord("a") + index), bus="virtio")
        xml = ET.tostring(domain, encoding="unicode")
    file = Path(destination) / "domain-config.xml"
    with file.open("w") as stream:
        stream.write(xml)
        stream.flush()
        os.fsync(stream.fileno())


def export_nas(payload):
    plan, paths, files = payload["plan"], payload["paths"], payload["files"]
    destination = Path(payload["destination"]).resolve(strict=True)
    if (plan.get("version") != 1 or plan.get("operation") != "BACKUP"
            or len(paths) != len(plan.get("disks", [])) or len(paths) != len(files)
            or not files or len(set(files)) != len(files)):
        raise RuntimeError("Invalid offline NAS Full plan")
    for path, disk, name in zip(paths, plan["disks"], files):
        if (Path(path).name != disk["path"] or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.(?:raw|qcow2)", name) or Path(name).suffix != "." + disk["format"]
                or disk["format"] not in {"raw", "qcow2"} or disk["bytes"] <= 0):
            raise RuntimeError("Invalid offline NAS source or destination")
    job_directory = os.environ.get("ABLESTACK_BACKUP_JOB_DIR")
    total_bytes = sum(disk["bytes"] for disk in plan["disks"])
    copied_bytes = 0
    previous = None

    def progress(step, index, fraction=0):
        nonlocal previous
        percentage = 5
        if step == "FINALIZING":
            percentage = 99
        elif step in {"RAW_COPY", "QCOW2_COPY"}:
            percentage += int(90 * (copied_bytes + plan["disks"][index]["bytes"] * max(0, min(1, fraction))) / total_bytes)
        if previous is not None:
            percentage = max(previous[1], percentage)
        current = (step, percentage, index + 1)
        if not job_directory or current == previous:
            return
        details = {"PREPARING": "Preparing offline Full backup", "RAW_COPY": "Copying RAW backup data",
                   "QCOW2_COPY": "Copying QCOW2 backup data", "FINALIZING": "Finalizing offline Full backup"}[step]
        path = Path(job_directory) / "nas-progress.properties"
        temporary = path.with_name(path.name + ".tmp-" + str(os.getpid()))
        try:
            temporary.write_text("step=%s\nprogress=%s\nvolumeIndex=%s\nvolumeCount=%s\ndetails=%s\n" %
                                 (*current, len(files), details), encoding="ascii")
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)
        previous = current

    progress("PREPARING", 0)
    with offline_sources(paths, plan["jobId"]) as validate:
        capture_configuration(plan, paths, destination)
        total = 0
        for index, (path, disk, name) in enumerate(zip(paths, plan["disks"], files)):
            target = destination / name
            step = "RAW_COPY" if disk["format"] == "raw" else "QCOW2_COPY"
            progress(step, index)
            convert(path, target, disk["format"], disk["bytes"], payload["timeout"],
                    progress=lambda fraction: progress(step, index, fraction), bandwidth=payload.get("bandwidth", 0))
            copied_bytes += disk["bytes"]
            total += target.stat().st_size
        progress("FINALIZING", len(files) - 1)
        validate()
    print(total, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--action", required=True)
    parser.add_argument("--plan-file", required=True)
    args = parser.parse_args()
    payload = json.loads(Path(args.plan_file).read_text())
    import signal
    def canceled(signum, frame):
        raise RuntimeError("Offline Full export was terminated")
    signal.signal(signal.SIGTERM, canceled)
    if args.action == "EXPORT_NAS":
        export_nas(payload)
    else:
        control(args.action, payload["plan"], payload["paths"])


if __name__ == "__main__":
    main()
