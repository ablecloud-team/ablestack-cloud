#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Boot-only, bounded protocol recovery. Never formats DATA or edits desired policy."""
import concurrent.futures
import contextlib
import fcntl
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid

VERSION = 1
ROOT = Path("/var/lib/ablestack-storage/boot-reconcile")
DESIRED = {
    "NFS": Path("/etc/ablestack-storage/desired-state/nfs-export-apply.json"),
    "SMB": Path("/etc/ablestack-storage/desired-state/smb-share-apply.json"),
    "ISCSI": Path("/etc/ablestack-storage/iscsi-targets.json"),
    "NVMEOF": Path("/etc/ablestack-storage/nvmeof-subsystems.json"),
}
ACTIVE = {"STARTING", "RUNNING"}
RETRYABLE = {"PAUSED", "INTERRUPTED"}
TERMINAL_PROTOCOL = {"READY", "DISABLED", "EXTERNAL_CLIENT_REQUIRED"}
CLI = "/usr/local/bin/ablestack-storagectl"

class RecoveryError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)

def identifier(value):
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError, AttributeError):
        raise RecoveryError("BOOT_IDENTITY_INVALID")

def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

def safe_directory(path):
    path = Path(path)
    # Every ancestor is checked; do not follow a redirected journal root.
    for item in reversed([path, *path.parents]):
        if item.is_symlink():
            raise RecoveryError("BOOT_JOURNAL_UNSAFE")
        if item.exists():
            info = item.stat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
                # /tmp and /var/tmp may be used only by isolated constructor tests.
                if item not in (Path("/tmp"), Path("/var/tmp"), Path("/run/lock")) or not info.st_mode & stat.S_ISVTX:
                    raise RecoveryError("BOOT_JOURNAL_UNSAFE")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)

def read_json(path, fallback=None):
    path = Path(path)
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return fallback
    with os.fdopen(fd) as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022 or info.st_size > 8 * 1024 * 1024:
            raise RecoveryError("BOOT_STATE_UNSAFE")
        try:
            return json.load(stream)
        except (ValueError, TypeError):
            raise RecoveryError("BOOT_STATE_INVALID")

def atomic_json(path, value):
    safe_directory(path.parent)
    if path.is_symlink():
        raise RecoveryError("BOOT_JOURNAL_UNSAFE")
    fd, name = tempfile.mkstemp(prefix=".boot-", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w") as stream:
            json.dump(value, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
        parent = os.open(path.parent, os.O_DIRECTORY | os.O_RDONLY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        if os.path.exists(name):
            os.unlink(name)

def process_identity(pid):
    try:
        fields = Path("/proc/" + str(int(pid)) + "/stat").read_text().rsplit(")", 1)[1].split()
        return str(pid) + ":" + fields[19] if fields[0] not in ("Z", "X") else None
    except (OSError, ValueError, IndexError):
        return None

def bounded_command(args, seconds):
    process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
    expired = False
    try:
        out, err = process.communicate(timeout=max(.05, seconds))
    except subprocess.TimeoutExpired:
        expired = True
        os.killpg(process.pid, signal.SIGTERM)
        try:
            out, err = process.communicate(timeout=.5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            out, err = process.communicate(timeout=2)
    if len(out) > 8 * 1024 * 1024:
        raise RecoveryError("BOOT_RESPONSE_TOO_LARGE")
    return {"returncode": process.returncode, "stdout": out, "stderr": err[-2048:], "timedOut": expired}

# Each mount is owned by a private mount namespace; no daemon or normal DATA mount
# is a child of this probe. A group kill releases the namespace after descendants exit.
PROBE = r"""
import json,os,subprocess,sys,tempfile
from pathlib import Path
request=json.loads(sys.argv[1])
directory=Path(tempfile.mkdtemp(prefix="probe-",dir=request["root"]))
result={"state":"FAILED","code":"NFS_PROBE_FAILED","cleanup":"COMPLETE"}
try:
 command=["mount","-t",request["mountType"],"-o",request["options"],request["target"],str(directory)]
 try:
  p=subprocess.run(command,capture_output=True,text=True,timeout=request["timeout"])
  if p.returncode==0:result.update(state="READY",code=None)
  else:
   error=p.stderr.lower()
   code="NFS_PROBE_ACCESS_DENIED" if "access denied" in error or "permission denied" in error else "NFS_PROBE_PATH_INVALID" if "no such file" in error else "NFS_PROBE_PENDING"
   result.update(state="BLOCKED" if code in ("NFS_PROBE_ACCESS_DENIED","NFS_PROBE_PATH_INVALID") else "WAITING",code=code)
 except subprocess.TimeoutExpired:
  result.update(state="WAITING",code="NFS_PROBE_TIMEOUT")
finally:
 if os.path.ismount(directory):
  try:
   p=subprocess.run(["umount",str(directory)],capture_output=True,timeout=1)
   if p.returncode:result.update(state="BLOCKED",code="BOOT_CLEANUP_REQUIRED",cleanup="REQUIRED")
  except subprocess.TimeoutExpired:result.update(state="BLOCKED",code="BOOT_CLEANUP_REQUIRED",cleanup="REQUIRED")
 if not os.path.ismount(directory):directory.rmdir()
 print(json.dumps(result))
"""

def workload_budget(probes, listeners):
    return min(900, max(90, 45 + 20 * listeners + 3 * math.ceil(probes / 4)))

def active_rows(value, key):
    if not value.get("enabled", True):
        return []
    return [row for row in value.get(key, []) if row.get("state", "Ready") not in ("Disabled", "Destroyed", "Error")]

def desired_snapshot(paths=DESIRED):
    states = {name: read_json(path, {}) for name, path in paths.items()}
    ids = {identifier(v["instanceUuid"]) for v in states.values() if v.get("instanceUuid")}
    if not any(states.values()):
        return None
    if len(ids) != 1:
        raise RecoveryError("BOOT_INSTANCE_IDENTITY_MISMATCH")
    def canonical(value):
        # Only an existing runtime observation, never an ACL or identity field.
        return {key: item for key, item in value.items() if key != "lastApply"}
    return {"instanceUuid": ids.pop(), "desiredHash": digest({p: canonical(v) for p, v in states.items()}), "states": states}

def rendered_probe_plan(endpoints, exports):
    """Use the same local-IP/ACL contract as the ordinary visibility probe."""
    command = bounded_command(["ip", "-4", "-o", "addr", "show", "scope", "global"], 5)
    if command["returncode"]:
        raise RecoveryError("NETWORK_PRIMARY_PENDING")
    addresses = []
    for line in command["stdout"].splitlines():
        fields = line.split()
        if "inet" in fields:
            addresses.append(str(ipaddress.ip_interface(fields[fields.index("inet") + 1]).ip))
    if not addresses:
        raise RecoveryError("NETWORK_PRIMARY_PENDING")
    result = []
    for endpoint in endpoints:
        ip, port = str(endpoint["listenIp"]), int(endpoint["port"])
        rows = []
        for export in exports.get((ip, port), []):
            tokens = [v.strip() for c in export.get("clients", []) for v in str(c.get("clients", "")).split(",") if v.strip()]
            def allowed(address):
                for token in tokens:
                    if token in ("*", "0.0.0.0/0", "::/0"):
                        return True
                    try:
                        if ipaddress.ip_address(address) in ipaddress.ip_network(token, strict=False):
                            return True
                    except ValueError:
                        pass
                return False
            candidates = [ip] if ip not in ("0.0.0.0", "::", "") else addresses
            host = next((a for a in candidates if allowed(a)), None)
            if not host and "*" in tokens:
                host = "127.0.0.1"
            if not host:
                try:
                    for token in tokens: ipaddress.ip_network(token, strict=False)
                    if not tokens: raise ValueError()
                except ValueError:
                    raise RecoveryError("NFS_LOCAL_ADDRESS_OBSERVATION_UNAVAILABLE")
            rows.append({"uuid": export["uuid"], "name": export.get("name"), "pseudo": export["pseudo"],
                         "protocolMode": export["protocolMode"], "backingPath": export["path"], "probeHost": host})
        result.append({"host": ip, "port": port, "exports": rows})
    return result

def probe_plan(prepare):
    tasks, external = [], []
    for endpoint in prepare.get("bootProbePlan", []):
        for export in endpoint["exports"]:
            port, host = int(endpoint["port"]), endpoint["host"]
            base = {"exportUuid": identifier(export["uuid"]), "exportName": str(export.get("name", ""))[:80],
                    "port": port, "protocolMode": export.get("protocolMode", "V4_ONLY")}
            if not export.get("probeHost"):
                external.append({**base, "state": "EXTERNAL_CLIENT_REQUIRED", "code": "LOCAL_ADDRESS_EXCLUDED_BY_ACL"})
                continue
            host = str(ipaddress.IPv4Address(export["probeHost"]))
            versions = (4, 3) if base["protocolMode"] == "V3V4_DUAL" else (4,)
            pseudo = export["pseudo"]
            if not pseudo.startswith("/") or any(c in pseudo for c in ("\x00", "\n", "\r")):
                raise RecoveryError("BOOT_EXPORT_PATH_INVALID")
            for version in versions:
                task = {**base, "version": version, "target": host + ":" + pseudo,
                        "mountType": "nfs4" if version == 4 else "nfs",
                        "options": "vers=" + ("4.1" if version == 4 else "3") + ",proto=tcp,port=" + str(port) +
                                   (",nolock" if version == 3 else "") + ",ro,soft,timeo=1,retrans=1,nosharecache"}
                task["key"] = digest({k: task[k] for k in ("exportUuid", "port", "version", "target", "options")})
                tasks.append(task)
    return tasks, external

class Runtime:
    def run(self, args, seconds=30):
        return bounded_command(args, seconds)

    def network(self):
        helper = "/usr/local/sbin/ablestack-sharedfs-network"
        for args, phase in (([helper, "--primary-ready"], "NETWORK_PRIMARY"),
                            ([CLI, "network", "endpoints", "reconcile"], "NETWORK_ALIASES"),
                            ([helper, "--ready"], "NETWORK_ALIASES")):
            result = self.run(args, 10)
            if result["returncode"]:
                raise RecoveryError(phase + "_PENDING")

    def data(self, snapshot):
        # Reuse the existing non-formatting mount and binding identity guards.
        result = self.run(["mount", "-a"], 20)
        if result["returncode"]:
            raise RecoveryError("DATA_MOUNTS_PENDING")
        from shared_directory_policy import FileSharePathBindings
        engine = FileSharePathBindings()
        rows = active_rows(snapshot["states"]["NFS"], "exports") + active_rows(snapshot["states"]["SMB"], "shares")
        checked_volumes = set()
        for row in rows:
            config = row.get("config") or {}
            backing = config.get("backingPath")
            if backing and not Path(backing).is_dir():
                raise RecoveryError("DATA_MOUNTS_PENDING")
            mount = config.get("volumeMountPath")
            volume = config.get("volumeUuid")
            if mount and mount not in checked_volumes:
                from volume_identity import resolve_volume_device
                blocks = json.loads(self.run(["lsblk", "-b", "-J", "-o", "NAME,PATH,TYPE,SIZE,SERIAL,FSTYPE,UUID,MOUNTPOINT"], 8)["stdout"])
                if not volume:
                    volume = Path(mount).name
                resolve_volume_device(blocks.get("blockdevices", []), volume_uuid=volume,
                    expected_size=int(config["volumeSizeGb"]) * 1024**3 if config.get("volumeSizeGb") else None,
                    mount_source=self.run(["findmnt", "-n", "-M", mount, "-o", "SOURCE"], 5)["stdout"].strip(),
                    allow_blank_size_fallback=False)
                checked_volumes.add(mount)
            # Binding anchors are recreated by the existing apply path, so inspect
            # physical identity here, not the volatile /run anchor's existence.
            if engine.bound(row):
                current = engine.inspect(row)
                identity = config.get("pathIdentity") or {}
                if not current.get("exists") or current.get("inode") != identity.get("inode") or current.get("filesystemuuid") != identity.get("filesystemUuid"):
                    raise RecoveryError("DIRECTORY_PATH_IDENTITY_CHANGED")

    def prepare(self, protocol, path, seconds):
        operation = {"NFS": ["nfs", "export", "boot-prepare"], "SMB": ["smb", "share", "apply"],
                     "ISCSI": ["iscsi", "target", "apply"], "NVMEOF": ["nvmeof", "subsystem", "apply"]}[protocol]
        if protocol == "NVMEOF":
            self.run(["modprobe", "nvmet-tcp"], 5)
            self.run(["mount", "-t", "configfs", "configfs", "/sys/kernel/config"], 5)
        value = self.run([CLI, *operation, str(path)], seconds)
        if value["timedOut"]:
            raise RecoveryError(protocol + "_PREPARATION_PENDING")
        if value["returncode"]:
            for line in value["stdout"].splitlines():
                try:
                    code = json.loads(line).get("code", "")
                    if re.fullmatch(r"[A-Z][A-Z0-9_]{1,95}", code): raise RecoveryError(code)
                except (ValueError, AttributeError): pass
            raise RecoveryError(protocol + "_PREPARATION_FAILED")
        # Some existing SMB apply paths emit two JSON lines.
        try:
            objects = [json.loads(line) for line in value["stdout"].splitlines() if line.startswith("{")]
            if not objects or not all(v.get("success") is True for v in objects):
                raise ValueError()
            return next((v for v in objects if "bootProbePlan" in v), objects[-1])
        except (ValueError, TypeError):
            raise RecoveryError(protocol + "_PREPARATION_INVALID")

    def identity(self, plan):
        identity = []
        for row in plan.get("runtimeEndpoints", []):
            pid = int(row.get("pid") or 0)
            process = process_identity(pid)
            path = Path(row.get("configPath") or "/etc/ganesha/ablestack-storage/" + row["key"] + ".conf")
            if not process or path.is_symlink() or not path.is_file():
                raise RecoveryError("NFS_RUNTIME_IDENTITY_CHANGED")
            identity.append([row["key"], process, hashlib.sha256(path.read_bytes()).hexdigest()])
        paths = sorted({row["backingPath"] for group in plan.get("bootProbePlan", []) for row in group["exports"] if row.get("backingPath")})
        if paths:
            mounts = json.loads(self.run(["findmnt", "-J", "-l", "-o", "TARGET,UUID,FSROOT,MAJ:MIN"], 5)["stdout"])["filesystems"]
            for path in paths:
                info = os.stat(path, follow_symlinks=False)
                candidates = [m for m in mounts if path == m["target"] or path.startswith(m["target"].rstrip("/") + "/")]
                found = max(candidates, key=lambda m: len(m["target"]))
                if not found.get("uuid") or not stat.S_ISDIR(info.st_mode):
                    raise RecoveryError("NFS_FILESYSTEM_IDENTITY_CHANGED")
                identity.append([path, info.st_dev, info.st_ino, found["uuid"], found.get("fsroot"), found.get("maj:min")])
        return digest(identity)

    def probe(self, task, private, seconds):
        root = private / "probes" / digest({k: task[k] for k in ("mountType", "target", "options")})
        safe_directory(root)
        request = {**task, "root": str(root), "timeout": min(3, seconds)}
        result = self.run(["unshare", "--mount", "--propagation", "private", sys.executable, "-c", PROBE, json.dumps(request)], min(5, seconds + 1))
        if result["timedOut"]:
            for directory in root.glob("probe-*"):
                if directory.is_symlink() or os.path.ismount(directory):
                    return {"state":"BLOCKED", "code":"BOOT_CLEANUP_REQUIRED", "cleanup":"REQUIRED"}
                try: directory.rmdir()
                except OSError: return {"state":"BLOCKED", "code":"BOOT_CLEANUP_REQUIRED", "cleanup":"REQUIRED"}
            root.rmdir()
            return {"state": "WAITING", "code": "NFS_PROBE_TIMEOUT", "cleanup": "COMPLETE"}
        try:
            value = json.loads(result["stdout"].splitlines()[-1])
            if value["state"] not in ("READY", "WAITING", "BLOCKED") or value["cleanup"] not in ("COMPLETE", "REQUIRED"):
                raise ValueError()
            if value["cleanup"] == "COMPLETE": root.rmdir()
            return value
        except (ValueError, KeyError, IndexError, OSError):
            return {"state": "BLOCKED", "code": "BOOT_PROBE_RESULT_INVALID", "cleanup": "REQUIRED"}

    def launch(self, directory, unit):
        # Only the signed release containing this module is an executable target.
        return self.run(["systemd-run", "--collect", "--unit=" + unit, "--property=Type=exec",
                         "--property=RuntimeMaxSec=960", "--property=KillMode=control-group",
                         sys.executable, str(Path(__file__).resolve()), "worker", str(directory)], 5)

class BootReconciler:
    def __init__(self, root=ROOT, paths=DESIRED, runtime=None, boot=None):
        self.root, self.paths, self.runtime = Path(root), paths, runtime or Runtime()
        self.boot = identifier(boot or Path("/proc/sys/kernel/random/boot_id").read_text().strip())

    def snapshot(self):
        return desired_snapshot(self.paths)

    def locate(self, snapshot):
        return self.root / snapshot["instanceUuid"] / self.boot

    def write(self, directory, state):
        state["observedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        state["heartbeatEpoch"] = time.time()
        atomic_json(directory / "status.json", state)

    def matching_runtime(self, state):
        try:
            return state.get("runtimeIdentity") == self.runtime.identity(state["plan"])
        except (RecoveryError, OSError):
            return False

    def status(self, instance=None):
        snapshot = self.snapshot()
        if not snapshot:
            return {"schemaVersion": VERSION, "state": "NOT_CONFIGURED", "canResume": False}
        if instance and identifier(instance) != snapshot["instanceUuid"]:
            raise RecoveryError("BOOT_INSTANCE_IDENTITY_MISMATCH")
        directory = self.locate(snapshot)
        value = read_json(directory / "status.json")
        if not value:
            return {"schemaVersion": VERSION, "bootId": self.boot, "desiredHash": snapshot["desiredHash"],
                    "state": "NOT_OBSERVED", "canResume": False}
        result = {k: v for k, v in value.items() if k not in ("plan", "results", "workerIdentity", "heartbeatEpoch", "unit")}
        result["updatedAt"] = value.get("observedAt")
        result["observedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        if value["desiredHash"] != snapshot["desiredHash"]:
            result.update(state="BLOCKED", code="BOOT_CONFIG_CHANGED", canResume=False)
        elif value["state"] == "READY" and value.get("plan") and not self.matching_runtime(value):
            result.update(state="INTERRUPTED", code="NFS_RUNTIME_IDENTITY_CHANGED", canResume=True)
        elif value["state"] in ACTIVE and time.time() - value.get("heartbeatEpoch", 0) > 10 and process_identity(value.get("workerPid", 0)) != value.get("workerIdentity"):
            result.update(state="INTERRUPTED", code="BOOT_WORKER_INTERRUPTED", canResume=True)
        else:
            result["canResume"] = value["state"] in RETRYABLE and value.get("cleanup", {}).get("state") == "COMPLETE"
        results = value.get("results", {})
        result["exports"] = [{k: row.get(k) for k in ("exportUuid", "exportName", "port", "version", "state", "code", "observedAt")} for row in results.values()][:1000]
        result["omittedExports"] = max(0, len(results) - 1000)
        return result

    @contextlib.contextmanager
    def locks(self, directory, worker=False):
        safe_directory(directory)
        with contextlib.ExitStack() as stack:
            paths = []
            if worker and self.root == ROOT:
                paths += [Path("/run/lock/ablestack-storage-runtime-upgrade.lock"),
                          Path("/run/lock/ablestack-storage-boot-reconcile.lock"),
                          Path("/var/lib/ablestack-storage/configuration-operations") / directory.parent.name / "lock"]
            paths.append(directory / ("lock" if worker else "admission.lock"))
            for path in paths:
                safe_directory(path.parent)
                descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
                stream = stack.enter_context(os.fdopen(descriptor, "a"))
                try:
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise RecoveryError("BOOT_WRITER_ACTIVE")
            yield

    def start(self, request=None):
        request = request or {}
        snapshot = self.snapshot()
        if not snapshot:
            return self.status()
        directory = self.locate(snapshot)
        with self.locks(directory):
            old = read_json(directory / "status.json")
            if request:
                if set(request) != {"instanceUuid", "bootId", "expectedDesiredHash", "protocol"} or request["protocol"] != "NFS":
                    raise RecoveryError("BOOT_REQUEST_INVALID")
                if identifier(request["instanceUuid"]) != snapshot["instanceUuid"] or identifier(request["bootId"]) != self.boot or request["expectedDesiredHash"] != snapshot["desiredHash"]:
                    raise RecoveryError("BOOT_OBSERVATION_CHANGED")
                current = self.status(snapshot["instanceUuid"])
                if current["state"] in ACTIVE:
                    return current  # Lost reply or duplicate admission never starts another worker.
                if not current.get("canResume"):
                    raise RecoveryError("BOOT_RECOVERY_NOT_RESUMABLE")
            elif old:
                if old["desiredHash"] != snapshot["desiredHash"]:
                    raise RecoveryError("BOOT_CONFIG_CHANGED")
                if old["state"] in ACTIVE | {"READY", "BLOCKED"}:
                    return self.status()
            state = old or {"schemaVersion": VERSION, "bootId": self.boot, "instanceUuid": snapshot["instanceUuid"],
                            "desiredHash": snapshot["desiredHash"], "protocols": {}, "results": {}, "cleanup": {"state": "COMPLETE"},
                            "firstFailure": None, "attempt": 0, "phase": "NETWORK_PRIMARY"}
            state.update(state="STARTING", attempt=state["attempt"] + 1, workerPid=0)
            state["unit"] = "ablestack-storage-boot-" + snapshot["instanceUuid"].replace("-", "")[:16] + "-" + self.boot.replace("-", "")[:12]
            self.write(directory, state)
            launch = self.runtime.launch(directory, state["unit"])
            if launch["returncode"]:
                state.update(state="PAUSED", code="BOOT_WORKER_START_PENDING")
                self.write(directory, state)
            return self.status()

    def failure(self, state, code, protocol=None, task=None):
        failure = {"code": code, "phase": state["phase"], "protocol": protocol,
                   "observedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        if task:
            failure.update({k: task.get(k) for k in ("exportUuid", "port", "version")})
        if not state.get("firstFailure"):
            state["firstFailure"] = failure
        state["lastFailure"] = failure

    def work(self, directory=None):
        snapshot = self.snapshot()
        if not snapshot:
            return self.status()
        expected = self.locate(snapshot)
        if directory and Path(directory) != expected:
            raise RecoveryError("BOOT_JOURNAL_IDENTITY_CHANGED")
        with self.locks(expected, worker=True):
            state = read_json(expected / "status.json")
            if not state or state["desiredHash"] != snapshot["desiredHash"]:
                raise RecoveryError("BOOT_CONFIG_CHANGED")
            if state["state"] == "READY":
                return self.status()
            state.update(state="RUNNING", workerPid=os.getpid(), workerIdentity=process_identity(os.getpid()))
            begin = time.monotonic()
            try:
                nfs = snapshot["states"]["NFS"]
                listeners = max(1, len(nfs.get("listeners") or []))
                probes = len(active_rows(nfs, "exports")) * listeners * (2 if nfs.get("protocolMode") == "V3V4_DUAL" else 1)
                state["budgetSeconds"] = workload_budget(probes, listeners)
                for phase, action in (("NETWORK_PRIMARY", self.runtime.network), ("DATA_MOUNTS", lambda: self.runtime.data(snapshot))):
                    state["phase"] = phase; self.write(expected, state)
                    ready_by = min(begin + state["budgetSeconds"], time.monotonic() + 30)
                    while True:
                        try:
                            action(); break
                        except RecoveryError as error:
                            self.failure(state, error.code)
                            self.write(expected, state)
                            if not error.code.endswith("_PENDING") or time.monotonic() >= ready_by: raise
                            time.sleep(min(1, max(0, ready_by - time.monotonic())))
                # Prepare every protocol once before any long NFS visibility wait.
                for protocol in ("NFS", "SMB", "ISCSI", "NVMEOF"):
                    if protocol == "NFS" and state.get("plan") and not self.matching_runtime(state):
                        state["protocols"][protocol] = {"state":"VERIFYING"}
                        state["results"] = {}
                    if state["protocols"].get(protocol, {}).get("state") in TERMINAL_PROTOCOL:
                        continue
                    key = {"NFS": "exports", "SMB": "shares", "ISCSI": "targets", "NVMEOF": "subsystems"}[protocol]
                    wanted = snapshot["states"][protocol]
                    if not active_rows(wanted, key):
                        state["protocols"][protocol] = {"state": "DISABLED", "phase": "COMPLETED"}
                        continue
                    if protocol == "NFS" and state.get("plan"):
                        if self.matching_runtime(state):
                            continue
                        # A changed listener requires re-observation, not old completed probes.
                        state["results"] = {}
                    state["phase"] = "CONFIGURATION"; self.write(expected, state)
                    try:
                        remaining = state["budgetSeconds"] - (time.monotonic() - begin)
                        if remaining <= 0: raise RecoveryError(protocol + "_PREPARATION_PENDING")
                        plan = self.runtime.prepare(protocol, self.paths[protocol], min(60, remaining))
                        if protocol == "NFS":
                            tasks, external = probe_plan(plan)
                            state.update(plan=plan, runtimeIdentity=self.runtime.identity(plan),
                                         budgetSeconds=workload_budget(len(tasks), len(plan.get("runtimeEndpoints", []))))
                            for item in external:
                                state["results"][digest(item)] = item
                            state["protocols"][protocol] = {"state": "VERIFYING", "total": len(tasks), "completed": 0,
                                                           "externalClientRequired": len(external)}
                        else:
                            state["protocols"][protocol] = {"state": "READY", "phase": "COMPLETED"}
                    except RecoveryError as error:
                        self.failure(state, error.code, protocol)
                        state["protocols"][protocol] = {"state": "WAITING" if error.code.endswith("_PENDING") else "BLOCKED"}
                    self.write(expected, state)
                state["phase"] = "EXPORT_VISIBILITY"
                if state.get("plan"):
                    tasks, _ = probe_plan(state["plan"])
                    deadline = begin + state["budgetSeconds"]
                    for attempt in range(3):
                        if self.snapshot()["desiredHash"] != state["desiredHash"] or self.runtime.identity(state["plan"]) != state["runtimeIdentity"]:
                            raise RecoveryError("BOOT_OBSERVATION_CHANGED")
                        pending = [v for v in tasks if state["results"].get(v["key"], {}).get("state") not in ("READY", "BLOCKED")]
                        if not pending or time.monotonic() >= deadline:
                            break
                        def observe(task):
                            value = self.runtime.probe(task, expected, max(.1, min(3, deadline - time.monotonic())))
                            return task, value
                        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                            for offset in range(0, len(pending), 4):
                                if time.monotonic() >= deadline: break
                                for task, value in pool.map(observe, pending[offset:offset + 4]):
                                    row = {k: task[k] for k in ("exportUuid", "exportName", "port", "version")}
                                    row.update(value, observedAt=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
                                    state["results"][task["key"]] = row
                                    if value["state"] != "READY":
                                        self.failure(state, value.get("code", "NFS_PROBE_FAILED"), "NFS", task)
                                    if value.get("cleanup") == "REQUIRED":
                                        state["cleanup"] = {"state": "REQUIRED"}
                                    completed = sum(state["results"].get(v["key"], {}).get("state") == "READY" for v in tasks)
                                    state["protocols"]["NFS"].update(completed=completed, pendingProbes=len(tasks) - completed)
                                    state["elapsedSeconds"] = round(time.monotonic() - begin, 2)
                                    self.write(expected, state)
                        if attempt < 2 and any(state["results"].get(v["key"], {}).get("state") == "WAITING" for v in tasks):
                            time.sleep(min(1, max(0, deadline - time.monotonic())))
                    values = [state["results"].get(v["key"], {}).get("state", "WAITING") for v in tasks]
                    blocked = "BLOCKED" in values or state["cleanup"]["state"] != "COMPLETE"
                    status = "BLOCKED" if blocked else "READY" if all(v == "READY" for v in values) else "WAITING"
                    state["protocols"]["NFS"].update(state=status, phase="COMPLETED" if status == "READY" else "EXPORT_VISIBILITY")
                states = [v["state"] for v in state["protocols"].values()]
                state["state"] = "BLOCKED" if "BLOCKED" in states else "READY" if all(v in TERMINAL_PROTOCOL for v in states) else "PAUSED"
                state["phase"] = "COMPLETED" if state["state"] == "READY" else "EXPORT_VISIBILITY"
            except RecoveryError as error:
                self.failure(state, error.code)
                state["state"] = "PAUSED" if error.code.endswith("_PENDING") else "BLOCKED"
                state["code"] = error.code
            except Exception as error:
                safe_code = getattr(error, "code", "BOOT_OBSERVATION_UNAVAILABLE")
                if not isinstance(safe_code, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{1,95}", safe_code):
                    safe_code = "BOOT_OBSERVATION_UNAVAILABLE"
                self.failure(state, safe_code)
                state.update(state="PAUSED" if safe_code == "BOOT_OBSERVATION_UNAVAILABLE" else "BLOCKED", code=safe_code)
            state["elapsedSeconds"] = round(time.monotonic() - begin, 2)
            self.write(expected, state)
        return self.status()

def protection_version():
    try:
        from shared_directory_policy import inheritance_protection_version
        return int(inheritance_protection_version(minimum=11) == 1)
    except (ImportError, TypeError, OSError):
        return 0

def read_status():
    try:
        return BootReconciler().status()
    except Exception:
        return {"schemaVersion": VERSION, "state": "UNKNOWN", "code": "BOOT_OBSERVATION_UNAVAILABLE", "canResume": False}

def main(argv):
    request = read_json(Path(argv[1]), {}) if len(argv) > 1 and argv[1] and argv[0] in ("status", "resume") else {}
    engine = BootReconciler()
    if argv[0] in ("start", "resume", "worker") and not protection_version():
        raise RecoveryError("BOOT_BASELINE_REQUIRED")
    if argv[0] == "status":
        result = engine.status(request.get("instanceUuid"))
    elif argv[0] == "start":
        result = engine.start()
    elif argv[0] == "resume":
        result = engine.start(request)
    elif argv[0] == "worker":
        result = engine.work(argv[1])
    else:
        raise RecoveryError("BOOT_REQUEST_INVALID")
    print(json.dumps({"success": True, "bootReconcile": result}))
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except RecoveryError as error:
        print(json.dumps({"success": False, "code": error.code}))
        sys.exit(1)
