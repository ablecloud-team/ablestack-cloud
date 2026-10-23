#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Durable, single-volume preparation for newly allocated SharedFS DATA only."""

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
import pathlib
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import uuid

from volume_identity import (VolumeIdentityError, compact, flatten_devices,
                             root_disk_names, resolve_volume_device, token_matches)

STATE_ROOT = pathlib.Path("/var/lib/ablestack-storage/volume-preparation")
HELPER = "/usr/local/bin/ablestack-storagectl"
MODULE = "/usr/local/lib/ablestack-storage/volume_preparation.py"
CAPABILITY = 1
RPC_DEADLINE = None
MAX_STATE = 32768
MAX_DIAGNOSTIC = 2048
SUPPORTED = {"xfs", "ext4"}


class PreparationError(RuntimeError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def canonical(value):
    try:
        parsed = str(uuid.UUID(str(value)))
        if parsed != str(value).lower():
            raise ValueError()
        return parsed
    except (ValueError, TypeError, AttributeError):
        raise PreparationError("INVALID_IDENTITY", "A canonical UUID is required")


def boot_id():
    return pathlib.Path("/proc/sys/kernel/random/boot_id").read_text().strip()


def ticks(pid):
    try:
        return pathlib.Path("/proc/%d/stat" % int(pid)).read_text().rsplit(")", 1)[1].split()[19]
    except (OSError, ValueError, IndexError):
        return None


def run(args, timeout=20):
    """Keep the old probe budget; formatter deadlines are explicitly separate."""
    if RPC_DEADLINE is not None:
        timeout = min(timeout, max(0.01, RPC_DEADLINE - time.monotonic()))
    return subprocess.run(args, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=timeout, check=False)


def normalize(payload):
    p = {key: payload.get(key) for key in
         ("operationId", "instanceUuid", "vmUuid", "rootVolumeUuid", "volumeUuid",
          "volumeName", "volumeSizeBytes", "filesystem", "formatTimeoutSeconds")}
    for key in ("operationId", "instanceUuid", "vmUuid", "rootVolumeUuid", "volumeUuid"):
        p[key] = canonical(p[key])
    p["filesystem"] = str(p["filesystem"] or "").lower()
    if p["filesystem"] not in SUPPORTED:
        raise PreparationError("UNSUPPORTED_FILESYSTEM", "Only XFS and EXT4 preparation is supported")
    p["volumeSizeBytes"] = int(p["volumeSizeBytes"] or 0)
    p["formatTimeoutSeconds"] = int(p["formatTimeoutSeconds"] or 0)
    if p["volumeSizeBytes"] <= 0 or not 30 <= p["formatTimeoutSeconds"] <= 7200:
        raise PreparationError("INVALID_BUDGET", "Invalid initial volume size or format budget")
    p["volumeName"] = str(p["volumeName"] or "")[:255]
    return p


class Store:
    def __init__(self, volume, root=STATE_ROOT):
        self.volume = canonical(volume)
        self.root = pathlib.Path(root)
        self.path = self.root / (self.volume + ".json")

    def ensure(self):
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        st = self.root.lstat()
        if not stat.S_ISDIR(st.st_mode) or st.st_uid != os.geteuid() or st.st_mode & 0o022:
            raise PreparationError("JOURNAL_UNSAFE", "Preparation journal directory is not private")

    def read(self):
        if not self.path.exists() and not self.path.is_symlink():
            return None
        fd = os.open(str(self.path), os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, "r") as f:
            st = os.fstat(f.fileno())
            if st.st_uid != os.geteuid() or not stat.S_ISREG(st.st_mode) or st.st_mode & 0o077 or st.st_size > MAX_STATE:
                raise PreparationError("JOURNAL_UNSAFE", "Preparation journal is unsafe")
            try:
                s = json.load(f)
            except (ValueError, TypeError):
                raise PreparationError("JOURNAL_INVALID", "Preparation journal is invalid")
        if s.get("version") != 1 or s.get("volumeUuid") != self.volume:
            raise PreparationError("JOURNAL_INVALID", "Preparation journal identity is invalid")
        canonical(s.get("operationId"))
        return s

    @contextlib.contextmanager
    def lock(self, kind, blocking=True):
        self.ensure()
        f = os.open(str(self.root / (self.volume + "." + kind + ".lock")),
                    os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(f, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
            yield
        finally:
            os.close(f)

    def update(self, patch, initial=None):
        with self.lock("state"):
            old = self.read()
            if old is None:
                if initial is None:
                    raise PreparationError("JOURNAL_MISSING", "Preparation journal is missing")
                old = dict(initial)
            old.update(patch)
            raw = json.dumps(old, sort_keys=True).encode()
            if len(raw) > MAX_STATE:
                raise PreparationError("JOURNAL_TOO_LARGE", "Preparation journal exceeded its bound")
            fd, tmp = tempfile.mkstemp(prefix="." + self.volume, dir=str(self.root))
            try:
                with os.fdopen(fd, "wb") as f:
                    os.fchmod(f.fileno(), 0o600)
                    f.write(raw)
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp, self.path)
                d = os.open(str(self.root), os.O_DIRECTORY)
                try:
                    os.fsync(d)
                finally:
                    os.close(d)
            finally:
                if os.path.exists(tmp):
                    os.unlink(tmp)
            return old


def validate_record(s, p):
    for k in ("operationId", "instanceUuid", "vmUuid", "rootVolumeUuid",
              "volumeUuid", "volumeSizeBytes", "filesystem", "formatTimeoutSeconds"):
        if s.get("payload", {}).get(k) != p.get(k):
            raise PreparationError("IDENTITY_CHANGED", "Preparation intent does not match the original volume")


def inspect(p, probe_filesystem=True):
    start = time.monotonic()
    def probe(args):
        remaining = 18 - (time.monotonic() - start)
        if remaining <= 0:
            raise PreparationError("OBSERVATION_TIMEOUT", "Volume observation exceeded its deadline")
        return run(args, min(8, remaining))
    # Filesystem columns can themselves read a device that mkfs is modifying.
    columns = "NAME,PATH,TYPE,SERIAL,SIZE,MOUNTPOINT"
    if probe_filesystem:
        columns += ",FSTYPE,UUID"
    r = probe(["lsblk", "-b", "-J", "-o", columns])
    if r.returncode:
        raise PreparationError("OBSERVATION_FAILED", "Unable to observe attached disks")
    devices = json.loads(r.stdout).get("blockdevices", [])
    flattened = flatten_devices(devices)
    roots = root_disk_names(flattened)
    root_disks = [d for d in flattened if d.get("type") == "disk" and d.get("name") in roots]
    if len(root_disks) != 1 or not token_matches(root_disks[0].get("serial"), [compact(p["rootVolumeUuid"])]):
        raise PreparationError("ROOT_IDENTITY_CHANGED", "SharedFS ROOT disk identity changed")
    mount = "/srv/ablestack-storage/volumes/" + p["volumeUuid"]
    m = probe(["findmnt", "-n", "-o", "SOURCE", "--mountpoint", mount])
    source = m.stdout.strip().splitlines()[0] if m.returncode == 0 and m.stdout.strip() else None
    resolved = resolve_volume_device(devices, volume_uuid=p["volumeUuid"], expected_size=p["volumeSizeBytes"],
                                     mount_source=source, allow_blank_size_fallback=False)
    d = next(x for x in flattened if x.get("path") == resolved["devicePath"])
    if resolved["matchedBy"] != "VOLUME_SERIAL" or d.get("type") != "disk" or int(d.get("size") or 0) != p["volumeSizeBytes"]:
        raise PreparationError("IDENTITY_CHANGED", "Exact DATA serial, whole disk and size are required")
    if d.get("children"):
        raise PreparationError("EXISTING_SIGNATURE", "A new DATA disk cannot contain partitions")
    if d.get("mountpoint") and os.path.realpath(str(d["mountpoint"])) != mount:
        raise PreparationError("FOREIGN_MOUNT", "DATA is mounted at a different path")
    if source and os.path.realpath(source) != os.path.realpath(resolved["devicePath"]):
        raise PreparationError("FOREIGN_MOUNT", "Canonical mount belongs to another disk")
    if not probe_filesystem:
        return dict(resolved, mountPath=mount, mounted=bool(source), observedAt=time.time())
    b = probe(["blkid", "-p", "-o", "export", resolved["devicePath"]])
    fields = dict(line.split("=", 1) for line in b.stdout.splitlines() if "=" in line)
    if b.returncode not in (0, 2):
        raise PreparationError("UNKNOWN_FILESYSTEM", "DATA signature could not be read unambiguously")
    fs = fields.get("TYPE") or d.get("fstype") or ""
    fsid = fields.get("UUID") or d.get("uuid") or ""
    if not fs:
        w = probe(["wipefs", "--no-act", "--json", resolved["devicePath"]])
        if w.returncode or json.loads(w.stdout).get("signatures"):
            raise PreparationError("EXISTING_SIGNATURE", "Unexpected or partial DATA signature")
    return dict(resolved, filesystem=fs, filesystemUuid=fsid, mountPath=mount,
                mounted=bool(source), observedAt=time.time())


def alive(s):
    if s.get("bootId") != boot_id():
        return False
    r = run(["systemctl", "is-active", s["unit"]], 3)
    if r.stdout.strip() not in ("active", "activating"):
        return False
    pid = s.get("workerPid")
    return not pid or ticks(pid) == s.get("workerStartTicks")


def observation_state(s):
    out = {k: v for k, v in s.items() if k not in ("payload", "workerPid", "workerStartTicks")}
    out.update(capabilityVersion=CAPABILITY, observationAvailable=True, lastObservedAt=time.time(),
               canResume=False, progresspercent=None, currentBootId=boot_id())
    return out


def snapshot(p, store=None):
    store = store or Store(p["volumeUuid"])
    s = store.read()
    if s is None:
        return {"capabilityVersion": CAPABILITY, "operationId": p["operationId"],
                "volumeUuid": p["volumeUuid"], "status": "OBSERVATION_PENDING", "phase": "NOT_OBSERVED",
                "canResume": False, "errorCode": "JOURNAL_MISSING", "observationAvailable": True}
    validate_record(s, p)
    try:
        if alive(s):
            # Verify ROOT/DATA identity without reading signatures during mkfs/discard.
            inspect(p, probe_filesystem=False)
            s = store.read()
            validate_record(s, p)
            if alive(s):
                out = observation_state(s)
                out.update(status="RUNNING", blockedReason="WORKER_RUNNING")
                return out
        # An ended/rebooted worker still requires the original complete FS checks.
        i = inspect(p)
        s = store.read()
        validate_record(s, p)
        out = observation_state(s)
        out["observedFilesystem"] = i["filesystem"]
        out["observedFilesystemUuid"] = i["filesystemUuid"]
        if alive(s):
            out.update(status="RUNNING", blockedReason="WORKER_RUNNING")
            return out
    except (subprocess.TimeoutExpired, PreparationError) as e:
        if not isinstance(e, subprocess.TimeoutExpired) and e.code != "OBSERVATION_TIMEOUT":
            raise
        # A read timeout is not evidence of a failed formatter or a blank disk.
        s = store.read()
        validate_record(s, p)
        out = observation_state(s)
        out.update(status="OBSERVATION_PENDING", observationAvailable=False, canResume=False,
                   blockedReason="OBSERVATION_UNAVAILABLE", errorCode="OBSERVATION_TIMEOUT",
                   reason=str(e)[-MAX_DIAGNOSTIC:])
        return out
    if s.get("filesystemUuid") and i["filesystemUuid"] != s["filesystemUuid"]:
        out.update(status="RECOVERY_REQUIRED", errorCode="FILESYSTEM_IDENTITY_CHANGED")
        return out
    if s.get("phase") == "PREPARED" and i["mounted"] and i["filesystem"] == p["filesystem"] and i["filesystemUuid"]:
        out.update(status="PREPARED", phase="PREPARED", canResume=True, blockedReason=None)
    elif s.get("errorCode") in ("FILESYSTEM_INVALID", "IDENTITY_CHANGED", "ROOT_IDENTITY_CHANGED"):
        out.update(status="RECOVERY_REQUIRED")
    elif s.get("formatEverStarted"):
        if i["filesystem"] == p["filesystem"] and i["filesystemUuid"]:
            out.update(status="OBSERVATION_PENDING", canResume=True, blockedReason=None)
        else:
            out.update(status="RECOVERY_REQUIRED", errorCode="FORMAT_RESULT_UNKNOWN",
                       reason="A previous format started; an empty or partial disk cannot be formatted again")
    elif not i["filesystem"] and not i["mounted"]:
        out.update(status="OBSERVATION_PENDING", canResume=True, blockedReason=None)
    else:
        out.update(status="RECOVERY_REQUIRED", errorCode="EXISTING_SIGNATURE")
    return out


def launch(p, resume=False, store=None):
    store = store or Store(p["volumeUuid"])
    try:
        with store.lock("job", blocking=False):
            s = store.read()
            if s is None:
                if resume:
                    return snapshot(p, store)
                now = time.time()
                unit = "ablestack-sharedfs-prepare-" + p["operationId"] + ".service"
                initial = dict(version=1, operationId=p["operationId"], volumeUuid=p["volumeUuid"], payload=p,
                               status="RUNNING", phase="QUEUED", unit=unit, bootId=boot_id(),
                               formatEverStarted=False, formatCount=0, createdAt=now)
                s = store.update({}, initial)
            validate_record(s, p)
            if alive(s):
                return snapshot(p, store)
            if s.get("formatEverStarted") and not resume:
                return snapshot(p, store)
            current = snapshot(p, store)
            if current.get("status") == "PREPARED" or not current.get("canResume"):
                return current
            store.update({"status": "RUNNING", "phase": "QUEUED", "bootId": boot_id(),
                          "workerPid": None, "workerStartTicks": None, "errorCode": None, "reason": None})
            r = run(["systemd-run", "--quiet", "--collect", "--service-type=exec", "--unit=" + s["unit"],
                     "/usr/bin/python3", MODULE, "worker", p["volumeUuid"], p["operationId"]], 10)
            if r.returncode:
                store.update({"status": "RECOVERY_REQUIRED", "errorCode": "LAUNCH_FAILED",
                              "reason": r.stderr[-MAX_DIAGNOSTIC:]})
            # No job lock is taken by readonly status; the worker starts after this lock is released.
            out = store.read()
            return {k: v for k, v in dict(out, capabilityVersion=CAPABILITY, observationAvailable=True).items()
                    if k not in ("payload", "workerPid", "workerStartTicks")}
    except BlockingIOError:
        return snapshot(p, store)


class PreparationContext:
    def __init__(self, payload, store=None):
        self.store = store or Store(payload.get("volumeUuid"))
        self.state = self.store.read()
        if not self.state or self.state["operationId"] != canonical(payload.get("preparationOperationId")):
            raise PreparationError("JOURNAL_MISMATCH", "Missing original preparation context")
        self.payload = self.state["payload"]
        if not alive(self.state):
            raise PreparationError("WORKER_NOT_ACTIVE", "Preparation worker is not active")

    def stage(self, phase):
        now = time.time()
        budget = min(600, self.payload["formatTimeoutSeconds"]) if phase == "VERIFYING_FILESYSTEM" else 120
        self.store.update({"phase": phase, "heartbeatAt": now, "phaseStartedAt": now,
                           "phaseTimeoutSeconds": budget, "deadlineAt": now + budget})

    def before_format(self, device, fs):
        s = self.store.read()
        i = inspect(self.payload)
        if s.get("formatEverStarted") or i["filesystem"] or i["mounted"] or fs != self.payload["filesystem"] or os.path.realpath(device) != os.path.realpath(i["devicePath"]):
            raise PreparationError("REFORMAT_FORBIDDEN", "Initial DATA may be formatted only once")
        now = time.time()
        self.store.update({"formatEverStarted": True, "formatCount": int(s.get("formatCount") or 0) + 1,
                          "formatStartedAt": now, "formatDeadlineAt": now + self.payload["formatTimeoutSeconds"],
                          "phase": "FORMATTING", "heartbeatAt": now, "phaseStartedAt": now,
                          "phaseTimeoutSeconds": self.payload["formatTimeoutSeconds"],
                          "deadlineAt": now + self.payload["formatTimeoutSeconds"]})

    def run(self, args, timeout=20):
        s = self.store.read()
        if pathlib.Path(args[0]).name.startswith("mkfs."):
            timeout = min(self.payload["formatTimeoutSeconds"], max(0.01, s["formatDeadlineAt"] - time.time()))
        elif args[0] == "mount":
            timeout = 120
        process = subprocess.Popen(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        try:
            out, err = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                out, err = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                out, err = process.communicate()
            raise PreparationError("COMMAND_DEADLINE", "Deadline exceeded in " + str(args[0]))
        return subprocess.CompletedProcess(args, process.returncode, out, err)

    def verify_formatted(self, device, fs):
        self.stage("VERIFYING_FILESYSTEM")
        i = inspect(self.payload)
        if i["filesystem"] != fs or not i["filesystemUuid"]:
            raise PreparationError("FILESYSTEM_INVALID", "Formatter result has no expected filesystem UUID")
        self.store.update({"filesystemUuid": i["filesystemUuid"], "observedFilesystem": fs})
        self.health(device, fs)

    def health(self, device, fs):
        args = ["xfs_repair", "-n", device] if fs == "xfs" else ["e2fsck", "-f", "-n", device]
        r = self.run(args, timeout=min(600, self.payload["formatTimeoutSeconds"]))
        if r.returncode:
            raise PreparationError("FILESYSTEM_INVALID", "Read-only filesystem verification failed: " + r.stderr[-MAX_DIAGNOSTIC:])


def worker(volume, operation):
    store = Store(volume)
    stop = threading.Event()
    def heartbeat():
        while not stop.wait(5):
            store.update({"heartbeatAt": time.time()})
    with store.lock("job"):
        s = store.read()
        if not s or s["operationId"] != canonical(operation):
            raise PreparationError("JOURNAL_MISMATCH", "Worker operation does not match")
        p = s["payload"]
        store.update({"workerPid": os.getpid(), "workerStartTicks": ticks(os.getpid()), "bootId": boot_id(),
                      "status": "RUNNING", "phase": "VERIFYING_IDENTITY", "heartbeatAt": time.time(),
                      "startedAt": s.get("createdAt", time.time()), "deadlineAt": time.time() + 20,
                      "phaseTimeoutSeconds": 20})
        thread = threading.Thread(target=heartbeat, daemon=True)
        thread.start()
        try:
            i = inspect(p)
            payload = dict(p, preparationOperationId=operation, importMode="FORMAT_EMPTY",
                           config={"volumeMountPath": i["mountPath"], "relativeSharePath": "", "createDirectory": False})
            if s.get("formatEverStarted"):
                if i["filesystem"] != p["filesystem"] or not i["filesystemUuid"]:
                    raise PreparationError("FORMAT_RESULT_UNKNOWN", "Previous format cannot be repeated")
                if s.get("filesystemUuid") and s["filesystemUuid"] != i["filesystemUuid"]:
                    raise PreparationError("IDENTITY_CHANGED", "Filesystem UUID changed")
                store.update({"filesystemUuid": i["filesystemUuid"], "observedFilesystem": i["filesystem"]})
                ctx = PreparationContext(payload, store)
                if not i["mounted"]:
                    ctx.stage("VERIFYING_FILESYSTEM")
                    ctx.health(i["devicePath"], i["filesystem"])
                payload["importMode"] = "MOUNT_EXISTING"
            elif i["filesystem"] or i["mounted"]:
                raise PreparationError("EXISTING_SIGNATURE", "New DATA already has a filesystem")
            fd, name = tempfile.mkstemp(prefix="." + store.volume, suffix=".input", dir=str(store.root))
            try:
                with os.fdopen(fd, "w") as f:
                    json.dump(payload, f)
                r = run([HELPER, "volume", "attach", "inspect", name],
                        p["formatTimeoutSeconds"] + min(600, p["formatTimeoutSeconds"]) + 600)
            finally:
                os.unlink(name)
            if r.returncode:
                raise PreparationError("PREPARATION_FAILED", r.stderr[-MAX_DIAGNOSTIC:])
            result = json.loads(r.stdout)
            last = store.read()
            if (result.get("volumeUuid") != p["volumeUuid"] or not result.get("persistentMount")
                    or result.get("filesystemUuid") != last.get("filesystemUuid")
                    or result.get("filesystem") != p["filesystem"]):
                raise PreparationError("IDENTITY_CHANGED", "Prepared filesystem/mount identity is not verified")
            store.update({"phase": "PREPARED", "status": "PREPARED", "finishedAt": time.time(),
                          "mountPath": result["volumeMountPath"], "heartbeatAt": time.time(), "errorCode": None, "reason": None, "deadlineAt": None})
        except (Exception, SystemExit) as e:
            store.update({"status": "RECOVERY_REQUIRED", "errorCode": getattr(e, "code", "PREPARATION_FAILED"),
                          "reason": str(e)[-MAX_DIAGNOSTIC:], "heartbeatAt": time.time()})
        finally:
            stop.set()
            thread.join(timeout=2)


def main():
    if os.geteuid() != 0:
        raise PreparationError("PERMISSION_DENIED", "Initial preparation requires root")
    if len(sys.argv) == 4 and sys.argv[1] == "worker":
        worker(sys.argv[2], sys.argv[3])
        return
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("start", "status", "resume"))
    parser.add_argument("payload")
    args = parser.parse_args()
    global RPC_DEADLINE
    RPC_DEADLINE = time.monotonic() + 18
    with open(args.payload) as f:
        p = normalize(json.load(f))
    try:
        result = snapshot(p) if args.action == "status" else launch(p, args.action == "resume")
    except (PreparationError, VolumeIdentityError, subprocess.TimeoutExpired, ValueError, OSError) as e:
        result = {"capabilityVersion": CAPABILITY, "operationId": p["operationId"], "volumeUuid": p["volumeUuid"],
                  "status": "RECOVERY_REQUIRED", "phase": "VERIFYING_IDENTITY", "canResume": False,
                  "observationAvailable": not isinstance(e, subprocess.TimeoutExpired),
                  "errorCode": getattr(e, "code", "OBSERVATION_FAILED"), "reason": str(e)[-MAX_DIAGNOSTIC:]}
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
