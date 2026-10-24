#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance
# with the License. You may obtain a copy at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
"""Durable, per-instance configuration transaction boundary. Never formats DATA."""
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import selectors
sys.path.insert(0, str(Path(__file__).resolve().parent))
import tempfile
import time
import uuid

VERSION = 1
ROOT = Path("/var/lib/ablestack-storage/configuration-operations")
OPERATIONS = {"nfs export apply", "smb share apply", "iscsi target apply",
              "nvmeof subsystem apply", "network endpoint apply"}
TERMINAL = {"COMMITTED", "ROLLED_BACK"}


class Rejected(Exception):
    """A safe public error code, not command output or a secret."""
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def atomic_json(path, value):
    path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    os.chmod(path.parent, 0o700)
    fd, temporary = tempfile.mkstemp(prefix=".configuration-", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load(path, default=None):
    try:
        with path.open(encoding="utf-8") as stream:
            return json.load(stream)
    except FileNotFoundError:
        return default


def token(value):
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError, AttributeError):
        raise Rejected("INVALID_IDENTITY")


def redact(item):
    if isinstance(item, dict):
        return {key: redact(v) for key, v in item.items()
                if not any(part in key.lower() for part in ("secret", "password", "chapkey", "chapctrlkey"))}
    if isinstance(item, list):
        return [redact(v) for v in item]
    return item


def public_hash(value):
    return hashlib.sha256(json.dumps(redact(value), sort_keys=True,
                                    separators=(",", ":")).encode()).hexdigest()


def kernel_link_target(path, raw_target):
    """Interpret a kernel readlink value relative to its link, independent of cwd."""
    raw = Path(raw_target)
    return Path(os.path.normpath(str(raw if raw.is_absolute() else path.parent / raw)))


def validated_kernel_links(trees, base=Path("/sys/kernel/config/nvmet")):
    """Validate the complete owned before-image before any kernel mutation."""
    links = {}
    for name, tree in trees.items():
        root = Path(name)
        if root.parent not in (base / "hosts", base / "subsystems", base / "ports"):
            raise Rejected("KERNEL_ROOT_OUTSIDE_SCOPE")
        if tree is None:
            continue
        for relative, raw_target in tree["links"].items():
            parts = Path(relative).parts
            if len(parts) != 2 or not parts[1].startswith("nqn."):
                raise Rejected("KERNEL_LINK_OUTSIDE_SCOPE")
            if root.parent == base / "subsystems" and parts[0] == "allowed_hosts":
                expected = base / "hosts" / parts[1]
            elif root.parent == base / "ports" and parts[0] == "subsystems":
                expected = base / "subsystems" / parts[1]
            else:
                raise Rejected("KERNEL_LINK_OUTSIDE_SCOPE")
            path = root / relative
            target = kernel_link_target(path, raw_target)
            if target != expected or str(target) not in trees or trees[str(target)] is None:
                raise Rejected("KERNEL_LINK_TARGET_OUTSIDE_SCOPE")
            if target.is_symlink():
                raise Rejected("KERNEL_LINK_TARGET_IDENTITY_CHANGED")
            if path.is_symlink():
                if kernel_link_target(path, os.readlink(path)) != target:
                    raise Rejected("KERNEL_LINK_IDENTITY_CHANGED")
            elif path.exists():
                raise Rejected("KERNEL_LINK_COLLISION")
            links[(name, relative)] = target
    return links


class Transaction:
    def __init__(self, root=ROOT, adapter_factory=None):
        self.root = Path(root)
        self.adapter_factory = adapter_factory or RuntimeAdapter

    def paths(self, payload):
        instance = token(payload.get("instanceUuid"))
        operation = token(payload.get("operationid"))
        directory = self.root / instance
        return directory, directory / operation, directory / operation / "status.json"

    def show(self, payload):
        # No flock, service command, device signature query or mutation on this path.
        _, _, status = self.paths(payload)
        value = load(status, {"operationid": token(payload.get("operationid")), "state": "NOT_FOUND"})
        if value.get("state") not in TERMINAL:
            # Process identity observation only; never acquires a write lock or applies configuration.
            value["workerActive"] = self.adapter_factory(status.parent).is_running()
            progress = load(status.parent / "runner-progress.json", {})
            if progress:
                value["execution"] = progress
                value["execution"]["overdue"] = time.time() > progress.get("softDeadlineAt", float("inf"))
            value["cancellationSupported"] = False
        return value

    def execute(self, action, payload):
        directory, private, status_path = self.paths(payload)
        directory.mkdir(parents=True, mode=0o700, exist_ok=True)
        os.chmod(directory, 0o700)
        with (directory / "lock").open("a") as lock:
            os.chmod(directory / "lock", 0o600)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            current = load(status_path)
            revision = load(directory / "revision.json", {"revision": 0})
            if action == "commit":
                if not current or current.get("state") not in ("VERIFIED", "COMMITTED"):
                    raise Rejected("COMMIT_REQUIRES_VERIFIED_OPERATION")
                if current["targetRevision"] != int(payload["targetRevision"]):
                    raise Rejected("REVISION_CONFLICT")
                atomic_json(directory / "revision.json", {"revision": current["targetRevision"],
                            "operationid": current["operationid"]})
                current.update(state="COMMITTED", phase="COMPLETED")
                atomic_json(status_path, current)
                return current
            if action == "resume":
                if not current:
                    raise Rejected("UNKNOWN_OPERATION")
                # Observe only known private material. Never replay a candidate with lost credentials.
                if current.get("state") in TERMINAL or current.get("state") == "VERIFIED":
                    return current
                adapter = self.adapter_factory(private)
                if adapter.is_running():
                    current.update(state="RECOVERY_REQUIRED", phase="OBSERVING")
                    atomic_json(status_path, current)
                    return current
                if current.get("originalfailure"):
                    # A confirmed failure/rollback intent cannot become a candidate commit
                    # merely because the last successful child result is still on disk.
                    return self.rollback(current, status_path, adapter)
                outcome = adapter.observe_completed()
                if outcome == "VERIFIED":
                    current.update(state="VERIFIED", phase="COMMITTING")
                    atomic_json(status_path, current)
                    return current
                if outcome != "FAILED":
                    current.update(state="RECOVERY_REQUIRED", phase="OBSERVING")
                    atomic_json(status_path, current)
                    return current
                current.setdefault("originalfailure", {"code": "PROTOCOL_APPLY_FAILED", "phase": current["phase"]})
                atomic_json(status_path, current)
                return self.rollback(current, status_path, adapter)
            if action != "apply":
                raise Rejected("UNSUPPORTED_ACTION")
            if current:
                if current.get("requestHash") != public_hash(payload.get("candidate")):
                    raise Rejected("REQUEST_CONFLICT")
                return current  # one logical key never starts a second apply
            if revision["revision"] != int(payload["baseRevision"]):
                raise Rejected("REVISION_CONFLICT")
            for item in directory.glob("*/status.json"):
                other = load(item)
                if other and other.get("state") not in TERMINAL:
                    raise Rejected("RECOVERY_REQUIRED")
            if int(payload["targetRevision"]) != int(payload["baseRevision"]) + 1:
                raise Rejected("REVISION_CONFLICT")
            if any(key not in OPERATIONS for key in (payload.get("candidate") or {})):
                raise Rejected("UNSUPPORTED_OPERATION")
            current = {"operationid": token(payload["operationid"]),
                       "instanceUuid": token(payload["instanceUuid"]),
                       "baseRevision": int(payload["baseRevision"]),
                       "targetRevision": int(payload["targetRevision"]),
                       "requestHash": public_hash(payload["candidate"]),
                       "state": "RUNNING", "phase": "VALIDATING",
                       "rollback": {"state": "NOT_NEEDED"}, "updated": time.time()}
            atomic_json(status_path, current)
            adapter = self.adapter_factory(private)
            try:
                adapter.preflight(payload)
                adapter.snapshot(payload)
                current.update(phase="APPLYING")
                atomic_json(status_path, current)
                adapter.apply(payload["candidate"])
                current.update(phase="VERIFYING")
                atomic_json(status_path, current)
                adapter.verify(payload["candidate"])
                current.update(state="VERIFIED", phase="COMMITTING")
                atomic_json(status_path, current)
                return current
            except BaseException as error:
                if getattr(error, "code", None) in ("APPLY_STILL_RUNNING", "RESULT_OBSERVATION_UNAVAILABLE"):
                    current.update(state="RECOVERY_REQUIRED", phase="OBSERVING", rollback={"state": "UNKNOWN"},
                                   observationCode=getattr(error, "code", "OBSERVATION_UNAVAILABLE"))
                    atomic_json(status_path, current)
                    return current
                # First failure is durable BEFORE compensation. Never expose raw subprocess output.
                current.setdefault("originalfailure", {"code": getattr(error, "code", "CONFIGURATION_APPLY_FAILED"),
                                   "phase": current["phase"], "type": type(error).__name__})
                if current["phase"] == "VALIDATING":
                    current.update(state="ROLLED_BACK", phase="COMPLETED", rollback={"state": "NOT_NEEDED"})
                    atomic_json(status_path, current)
                    return current
                atomic_json(status_path, current)
                return self.rollback(current, status_path, adapter)

    def rollback(self, current, status_path, adapter):
        current.update(phase="ROLLING_BACK", rollback={"state": "RUNNING"})
        atomic_json(status_path, current)
        try:
            adapter.restore()
            current.update(phase="VERIFYING_ROLLBACK")
            atomic_json(status_path, current)
            adapter.verify_restored()
            current.update(state="ROLLED_BACK", phase="COMPLETED", rollback={"state": "SUCCEEDED"})
        except BaseException as error:
            current.update(state="RECOVERY_REQUIRED", phase="OBSERVING",
                           rollback={"state": "FAILED", "code": getattr(error, "code", "ROLLBACK_FAILED"),
                                     "type": type(error).__name__})
        atomic_json(status_path, current)
        return current


def checked(args, **kwargs):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=kwargs.pop("timeout", 30), **kwargs)
    if result.returncode:
        raise Rejected("RUNTIME_COMMAND_FAILED")
    return result.stdout


class RuntimeAdapter:
    """Existing storagectl render/apply engines with bounded private before-images."""
    def __init__(self, private):
        self.private = Path(private)
        self.snapshot_path = self.private / "before.json"
        self.payload_path = self.private / "request.json"
        self.child_path = self.private / "runner.json"

    def preflight(self, payload):
        if payload.get("vmUuid"):
            observed = Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()
            if observed != token(payload["vmUuid"]):
                raise Rejected("VM_IDENTITY_MISMATCH")
        for operation, value in payload["candidate"].items():
            if operation not in OPERATIONS or not isinstance(value, dict):
                raise Rejected("UNSUPPORTED_OPERATION")
            if value.get("instanceUuid") not in (None, payload["instanceUuid"]):
                raise Rejected("INSTANCE_IDENTITY_MISMATCH")
            if operation.startswith("nvmeof"):
                for sub in value.get("subsystems", []):
                    for host in sub.get("hosts", []):
                        for key, attribute in (("dhChapKey", "dhchap_key"), ("dhChapCtrlKey", "dhchap_ctrl_key")):
                            if (host.get("secrets") or {}).get(key):
                                path = Path("/sys/kernel/config/nvmet/hosts") / host["principal"] / attribute
                                if path.exists() and not path.read_text().strip():
                                    raise Rejected("AUTH_BEFORE_IMAGE_UNAVAILABLE")
        if payload.get("preflight"):
            from configuration_preflight import inspect
            report = inspect(payload["preflight"])
            if report["status"] != "READY":
                blocked = next((c["code"] for c in report["checks"] if c["blocking"]), "RESOURCE_OBSERVATION_UNAVAILABLE")
                raise Rejected(blocked)
        self.payload = payload
        atomic_json(self.payload_path, redact(payload))

    def file_paths(self, payload):
        paths = {Path("/etc/ablestack-storage/network-endpoints.json")}
        for operation in payload["candidate"]:
            if operation.startswith("nfs"):
                paths.update(Path("/etc/ganesha/ablestack-storage").glob("*.conf"))
                paths.update(Path("/etc/exports.d").glob("*ablestack*"))
                paths.update(Path("/etc/ablestack-storage").glob("nfs*.json"))
                paths.add(Path("/etc/ablestack-storage/desired-state/nfs-export-apply.json"))
                paths.add(Path("/etc/fstab"))
            if operation.startswith("smb"):
                paths.update(Path("/etc/ablestack-storage").glob("smb*.json"))
                paths.add(Path("/etc/samba/smb.conf"))
                paths.add(Path("/etc/ablestack-storage/desired-state/smb-share-apply.json"))
            if operation.startswith("iscsi"):
                paths.add(Path("/etc/ablestack-storage/iscsi-targets.json"))
                paths.add(Path("/etc/ablestack-storage/secrets/iscsi-acl-secrets.json"))
                paths.add(Path("/etc/rtslib-fb-target/saveconfig.json"))
            if operation.startswith("nvmeof"):
                paths.add(Path("/etc/ablestack-storage/nvmeof-subsystems.json"))
        return paths

    def directories(self, payload):
        paths = set()
        users, groups = set(), set()
        for commands in (payload.get("before", {}), payload["candidate"]):
            for value in commands.values():
                for row in value.get("exports", []) + value.get("shares", []):
                    for key in ("path", "mountPath", "backingPath"):
                        value_path = row.get(key) or (row.get("config") or {}).get(key)
                        if value_path and str(value_path).startswith(("/export/", "/srv/ablestack-storage/volumes/")):
                            paths.add(str(Path(value_path).resolve()))
                    for acl in row.get("acls", []):
                        if acl.get("principalType") == "LOCAL_USER":
                            users.add(acl["principal"])
                        if acl.get("principalType") == "LOCAL_GROUP":
                            groups.add(acl["principal"])
        return paths, users, groups

    def snapshot(self, payload):
        import base64
        import grp
        import pwd
        paths, users, groups = self.directories(payload)
        snapshot = {"files": {}, "metadata": {}, "users": {}, "groups": {}, "network": []}
        for path in self.file_paths(payload):
            if path.is_file() and not path.is_symlink():
                stat = path.stat()
                if stat.st_size > 4 * 1024 * 1024:
                    raise Rejected("BEFORE_IMAGE_LIMIT")
                snapshot["files"][str(path)] = {"content": base64.b64encode(path.read_bytes()).decode(),
                    "mode": stat.st_mode & 0o777, "uid": stat.st_uid, "gid": stat.st_gid}
        for path in paths:
            item = Path(path)
            if item.is_dir():
                stat = item.stat()
                snapshot["metadata"][path] = {"uid": stat.st_uid, "gid": stat.st_gid,
                    "mode": stat.st_mode & 0o7777,
                    "acl": checked(["getfacl", "-p", path]).decode()}
            else:
                snapshot["metadata"][path] = None
        for user in users:
            try:
                entry = pwd.getpwnam(user)
                dump = subprocess.run(["pdbedit", "-Lw", "-u", user], capture_output=True, timeout=20)
                snapshot["users"][user] = {"uid": entry.pw_uid,
                    "groups": [g.gr_name for g in grp.getgrall() if user in g.gr_mem],
                    "samba": dump.stdout.decode() if dump.returncode == 0 else None}
            except KeyError:
                snapshot["users"][user] = None
        for group in groups | users:
            try:
                entry = grp.getgrnam(group)
                snapshot["groups"][group] = {"gid": entry.gr_gid, "members": entry.gr_mem}
            except KeyError:
                snapshot["groups"][group] = None
        snapshot["network"] = json.loads(checked(["ip", "-j", "-4", "addr", "show"]))
        snapshot["mounts"] = json.loads(checked(["findmnt", "-J", "-o", "TARGET,SOURCE,FSTYPE,OPTIONS"]))
        # The kernel snapshot contains attributes/links, never ordinary file copies into configfs.
        snapshot["kernel"] = self.kernel_snapshot(payload)
        ports = Path("/sys/kernel/config/nvmet/ports")
        snapshot["nvmePortIds"] = [v.name for v in ports.iterdir()] if ports.is_dir() else []
        snapshot["lio"] = self.lio_snapshot(payload)
        if len(json.dumps(snapshot).encode()) > 8 * 1024 * 1024:
            raise Rejected("BEFORE_IMAGE_LIMIT")
        atomic_json(self.snapshot_path, snapshot)

    def lio_snapshot(self, payload):
        if "iscsi target apply" not in payload["candidate"]:
            return None
        from rtslib_fb import RTSRoot
        dump = RTSRoot().dump()
        names = {t.get("targetName") for commands in (payload.get("before", {}), payload["candidate"])
                 for t in commands.get("iscsi target apply", {}).get("targets", [])}
        owned = [t for t in dump.get("targets", []) if t.get("wwn") in names]
        stores = {f"ablestack-{t['uuid']}" for commands in (payload.get("before", {}), payload["candidate"])
                  for t in commands.get("iscsi target apply", {}).get("targets", [])}
        return {"targets": owned, "storage_objects": [v for v in dump.get("storage_objects", []) if v.get("name") in stores]}

    def kernel_snapshot(self, payload):
        trees = {}
        commands = list(payload.get("before", {}).items()) + list(payload["candidate"].items())
        roots = set()
        for operation, value in commands:
            if operation.startswith("nvmeof"):
                for sub in value.get("subsystems", []):
                    name = sub.get("targetName", "")
                    if "/" in name or not name.startswith("nqn."):
                        raise Rejected("INVALID_TARGET_IDENTITY")
                    roots.add(Path("/sys/kernel/config/nvmet/subsystems") / name)
                    for host in sub.get("hosts", []):
                        name = host.get("principal", "")
                        if "/" in name or not name.startswith("nqn."):
                            raise Rejected("INVALID_HOST_IDENTITY")
                        roots.add(Path("/sys/kernel/config/nvmet/hosts") / name)
        owned_names = {root.name for root in roots if root.parent.name == "subsystems"}
        endpoint_keys = set()
        for operation, value in commands:
            if operation.startswith("nvmeof"):
                for listener in value.get("listeners") or [{"listenIp": value.get("listenIp") or "0.0.0.0", "port": value.get("port") or 4420}]:
                    endpoint_keys.add((str(listener.get("listenIp") or "0.0.0.0"), str(listener.get("port") or 4420)))
        port_root = Path("/sys/kernel/config/nvmet/ports")
        if port_root.is_dir():
            for port in port_root.iterdir():
                key = ((port / "addr_traddr").read_text().strip(), (port / "addr_trsvcid").read_text().strip())
                names = {link.name for link in (port / "subsystems").iterdir()} if (port / "subsystems").is_dir() else set()
                if key in endpoint_keys or names & owned_names:
                    roots.add(port)
        for root in roots:
            if not root.exists():
                trees[str(root)] = None
                continue
            tree = {"directories": [""], "attributes": {}, "links": {}}
            for directory, dirs, files in os.walk(root, followlinks=False):
                for name in list(dirs):
                    path = Path(directory) / name
                    relative = str(path.relative_to(root))
                    if path.is_symlink():
                        tree["links"][relative] = os.readlink(path); dirs.remove(name)
                    else:
                        tree["directories"].append(relative)
                for name in files:
                    if name.startswith(("attr_", "addr_")) or name in ("device_path", "device_uuid", "device_nguid", "device_eui64", "ana_grpid", "buffered_io", "enable", "dhchap_key", "dhchap_ctrl_key"):
                        path = Path(directory) / name
                        tree["attributes"][str(path.relative_to(root))] = path.read_text().strip()
            trees[str(root)] = tree
        return trees

    def run(self, operation, value):
        # Secrets live in an anonymous RAM descriptor, never a durable payload file.
        descriptor = os.memfd_create("storage-configuration", 0)
        os.write(descriptor, json.dumps(value).encode())
        os.lseek(descriptor, 0, os.SEEK_SET)
        with contextlib.suppress(FileNotFoundError):
            (self.private / "runner-result.json").unlink()
        process = subprocess.Popen([sys.executable, __file__, "--run", str(self.private), operation, str(descriptor)],
                                   pass_fds=(descriptor,), start_new_session=True,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.close(descriptor)
        atomic_json(self.child_path, {"pid": process.pid, "boot": Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                                    "start": Path("/proc/%s/stat" % process.pid).read_text().split()[21]})
        # Soft deadline is observation metadata, never permission to kill/replay a writer.
        while process.poll() is None:
            time.sleep(.25)
        result = load(self.private / "runner-result.json")
        if not result or result.get("observationUnknown"):
            raise Rejected("RESULT_OBSERVATION_UNAVAILABLE")
        if result["returncode"] != 0:
            raise Rejected("PROTOCOL_APPLY_FAILED")
        return result

    def is_running(self):
        child = load(self.child_path)
        def alive(record):
            if not record:
                return False
            path = Path("/proc/%s/stat" % record["pid"])
            try:
                return (record["boot"] == Path("/proc/sys/kernel/random/boot_id").read_text().strip()
                        and path.read_text().split()[21] == record["start"])
            except (OSError, IndexError):
                return False
        progress = load(self.private / "runner-progress.json", {})
        return alive(child) or alive(progress.get("child"))

    def observe_completed(self):
        result = load(self.private / "runner-result.json")
        if not result or result.get("observationUnknown"):
            return "UNKNOWN"
        payload = load(self.payload_path)
        # A durable completion marker is published only after every candidate engine ran.
        if load(self.private / "candidate-completed.json") == {"requestHash": public_hash(payload["candidate"])}:
            try:
                self.verify(payload["candidate"])
                return "VERIFIED"
            except Rejected:
                return "FAILED"
        if result["returncode"] != 0:
            return "FAILED"
        operations = list(payload["candidate"])
        if operations and result.get("operation") == operations[-1]:
            try:
                self.verify(payload["candidate"])
                return "VERIFIED"
            except Rejected:
                return "FAILED"
        # The last child finished, but the interrupted parent did not start the remaining steps.
        return "FAILED" if result.get("operation") in operations else "UNKNOWN"

    def apply(self, commands):
        for operation, value in commands.items():
            self.run(operation, value)
        payload = load(self.payload_path)
        if commands == payload["candidate"]:
            atomic_json(self.private / "candidate-completed.json", {"requestHash": public_hash(commands)})

    def verify(self, commands):
        if not commands:
            return
        result = load(self.private / "runner-result.json")
        if not result or result["returncode"] != 0:
            raise Rejected("RUNTIME_VERIFICATION_FAILED")
        output = result.get("result", {})
        if output.get("success") is False or output.get("runtimeReady") is False or output.get("listening") is False:
            raise Rejected("RUNTIME_VERIFICATION_FAILED")
        for operation, value in commands.items():
            if not value.get("enabled", True):
                continue
            if operation.startswith("smb") and value.get("shares"):
                checked(["smbcontrol", "smbd", "ping"])
                rows = checked(["ss", "-H", "-lnt"]).decode().splitlines()
                listeners = value.get("listeners") or [{"port": value.get("port") or 445}]
                for item in listeners:
                    if not any(len(row.split()) > 3 and row.split()[3].endswith(":" + str(item.get("port") or 445)) for row in rows):
                        raise Rejected("SMB_LISTENER_NOT_READY")
            if operation.startswith("iscsi"):
                from rtslib_fb import RTSRoot
                actual = {target.wwn: target for target in RTSRoot().targets}
                for target in value.get("targets", []):
                    iqn = target.get("targetName")
                    if not iqn or target.get("state") in ("Disabled", "Destroyed", "Error"):
                        continue
                    if iqn not in actual:
                        raise Rejected("ISCSI_TARGET_NOT_READY")
                    tpgs = list(actual[iqn].tpgs)
                    if not tpgs or int(target.get("lunOrNamespace") or "0") not in {lun.lun for lun in tpgs[0].luns}:
                        raise Rejected("ISCSI_LUN_NOT_READY")
                    expected = {acl["principal"] for acl in target.get("acls", []) if acl.get("state") not in ("Disabled", "Destroyed", "Error")}
                    if not expected.issubset({acl.node_wwn for acl in tpgs[0].node_acls}):
                        raise Rejected("ISCSI_ACL_NOT_READY")
            if operation.startswith("nvmeof"):
                for sub in value.get("subsystems", []):
                    if sub.get("state") in ("Disabled", "Destroyed", "Error"):
                        continue
                    root = Path("/sys/kernel/config/nvmet/subsystems") / sub["targetName"]
                    if not root.is_dir():
                        raise Rejected("NVME_SUBSYSTEM_NOT_READY")
                    allow_any = bool((sub.get("config") or {}).get("allowAnyHost"))
                    if (root / "attr_allow_any_host").read_text().strip() != ("1" if allow_any else "0"):
                        raise Rejected("NVME_HOST_POLICY_NOT_READY")
                    expected_hosts = set() if allow_any else {host["principal"] for host in sub.get("hosts", [])
                        if host.get("state", "Ready") not in ("Disabled", "Destroyed", "Error")}
                    host_links = list((root / "allowed_hosts").iterdir())
                    if {path.name for path in host_links} != expected_hosts:
                        raise Rejected("NVME_HOST_ACL_NOT_READY")
                    for path in host_links:
                        expected = Path("/sys/kernel/config/nvmet/hosts") / path.name
                        if not path.is_symlink() or kernel_link_target(path, os.readlink(path)) != expected:
                            raise Rejected("NVME_HOST_LINK_IDENTITY_CHANGED")
                    for namespace in sub.get("namespaces", []):
                        if namespace.get("state") in ("Disabled", "Destroyed", "Error"):
                            continue
                        path = root / "namespaces" / str(namespace.get("lunOrNamespace") or "1") / "enable"
                        if not path.is_file() or path.read_text().strip() != "1":
                            raise Rejected("NVME_NAMESPACE_NOT_READY")

    def restore_files(self, snapshot):
        import base64
        payload = load(self.payload_path)
        current = self.file_paths(payload)
        for path in current:
            if str(path) not in snapshot["files"] and path.is_file() and not path.is_symlink():
                path.unlink()
        for name, value in snapshot["files"].items():
            path = Path(name); path.parent.mkdir(parents=True, exist_ok=True)
            fd, temporary = tempfile.mkstemp(prefix=".configuration-", dir=path.parent)
            try:
                with os.fdopen(fd, "wb") as stream:
                    stream.write(base64.b64decode(value["content"])); stream.flush(); os.fsync(stream.fileno())
                os.chmod(temporary, value["mode"]); os.chown(temporary, value["uid"], value["gid"])
                os.replace(temporary, path)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)

    def restore(self):
        if self.is_running():
            raise Rejected("APPLY_STILL_RUNNING")
        snapshot = load(self.snapshot_path)
        payload = load(self.payload_path)
        if not snapshot or not payload:
            raise Rejected("BEFORE_IMAGE_UNAVAILABLE")
        self.restore_files(snapshot)
        self.restore_accounts(snapshot)
        self.capture_new_ports_for_restore(snapshot, payload)
        self.restore_kernel(snapshot["kernel"])
        self.restore_lio_credentials(snapshot, payload)
        self.apply(payload["before"])
        self.cleanup_new_backstores(snapshot, payload)
        self.restore_alias_mounts(snapshot, payload)
        self.restore_network(snapshot, payload)
        self.restore_files(snapshot)
        for name, value in snapshot["metadata"].items():
            if value is None:
                with contextlib.suppress(FileNotFoundError, OSError):
                    Path(name).rmdir()  # only empty operation-created directories
                continue
            if not Path(name).is_dir():
                raise Rejected("DIRECTORY_IDENTITY_CHANGED")
            os.chown(name, value["uid"], value["gid"]); os.chmod(name, value["mode"])
            checked(["setfacl", "--restore=-"], input=value["acl"].encode())

    def cleanup_new_backstores(self, snapshot, payload):
        if snapshot.get("lio") is None:
            return
        from rtslib_fb import RTSRoot
        original = {item["name"] for item in snapshot["lio"]["storage_objects"]}
        for target in payload["candidate"].get("iscsi target apply", {}).get("targets", []):
            name = "ablestack-" + token(target["uuid"])
            if name in original:
                continue
            storage = next((item for item in RTSRoot().storage_objects
                            if item.plugin == "block" and item.name == name), None)
            if storage is None:
                continue
            if list(storage.attached_luns):
                raise Rejected("BACKSTORE_STILL_REFERENCED")
            storage.delete()

    def restore_alias_mounts(self, snapshot, payload):
        def flatten(items):
            for item in items:
                yield item
                yield from flatten(item.get("children") or [])
        before = {row["target"] for row in flatten(snapshot["mounts"].get("filesystems", []))}
        desired = {row.get("path") for value in payload["candidate"].values() for row in value.get("exports", [])}
        current = json.loads(checked(["findmnt", "-J", "-o", "TARGET,SOURCE,FSTYPE,OPTIONS"]))
        for row in flatten(current.get("filesystems", [])):
            target = row["target"]
            if target not in before and target in desired and target.startswith("/export/"):
                checked(["umount", "--", target])

    def restore_network(self, snapshot, payload):
        original = {(row["ifname"], item["local"], item["prefixlen"]) for row in snapshot["network"]
                    for item in row.get("addr_info", []) if item.get("family") == "inet"}
        primary = {(row["ifname"], next((item["local"] for item in row.get("addr_info", [])
                   if item.get("family") == "inet"), None)) for row in snapshot["network"]}
        candidates, previous = set(), set()
        for commands, addresses in ((payload["candidate"], candidates), (payload["before"], previous)):
            for value in commands.values():
                if value.get("listenIp"):
                    addresses.add(value["listenIp"])
                addresses.update(v.get("listenIp") for v in value.get("listeners", []))
        current = json.loads(checked(["ip", "-j", "-4", "addr", "show"]))
        current_primary = {(row["ifname"], next((item["local"] for item in row.get("addr_info", [])
                           if item.get("family") == "inet"), None)) for row in current}
        if primary != current_primary:
            # In particular, do not force a previous address over a new DHCP lease.
            raise Rejected("PRIMARY_NETWORK_CHANGED")
        present = {(row["ifname"], item["local"], item["prefixlen"]) for row in current
                   for item in row.get("addr_info", []) if item.get("family") == "inet"}
        for row in current:
            for item in row.get("addr_info", []):
                key = (row["ifname"], item["local"], item["prefixlen"])
                if item["local"] in candidates and key not in original and (row["ifname"], item["local"]) not in primary:
                    checked(["ip", "addr", "del", f"{item['local']}/{item['prefixlen']}", "dev", row["ifname"]])
        for interface, address, prefix in original - present:
            if address in previous and (interface, address) not in primary:
                checked(["ip", "addr", "add", f"{address}/{prefix}", "dev", interface])

    def restore_lio_credentials(self, snapshot, payload):
        if snapshot.get("lio") is None:
            return
        stores = {row["name"]: row for row in snapshot["lio"]["storage_objects"]}
        native = {}
        for target in snapshot["lio"]["targets"]:
            for tpg in target.get("tpgs", []):
                for acl in tpg.get("node_acls", []):
                    native[(target["wwn"], acl["node_wwn"])] = acl
        for target in payload["before"].get("iscsi target apply", {}).get("targets", []):
            old_store = stores.get("ablestack-" + str(target.get("uuid")))
            if old_store:
                target["configurationRestoreBackstore"] = old_store
            for acl in target.get("acls", []):
                old = native.get((target.get("targetName"), acl.get("principal")), {})
                secrets = acl.setdefault("secrets", {})
                if old.get("chap_password"):
                    secrets["chapSecret"] = old["chap_password"]
                if old.get("chap_mutual_password"):
                    secrets["mutualChapSecret"] = old["chap_mutual_password"]

    def restore_accounts(self, snapshot):
        import pwd
        for user, value in snapshot["users"].items():
            if value is None:
                try:
                    entry = pwd.getpwnam(user)
                except KeyError:
                    continue
                if entry.pw_shell != "/usr/sbin/nologin":
                    raise Rejected("ACCOUNT_IDENTITY_CHANGED")
                subprocess.run(["smbpasswd", "-x", user], capture_output=True, timeout=20)
                checked(["userdel", user])
            else:
                if pwd.getpwnam(user).pw_uid != value["uid"]:
                    raise Rejected("ACCOUNT_IDENTITY_CHANGED")
                checked(["usermod", "-G", ",".join(value["groups"]), user])
                if value["samba"]:
                    source = self.private / "account-before.smbpasswd"
                    source.write_text(value["samba"]); os.chmod(source, 0o600)
                    try:
                        checked(["pdbedit", "-i", "smbpasswd:" + str(source)])
                    finally:
                        source.unlink()
        import grp
        for group, value in snapshot["groups"].items():
            try:
                current = grp.getgrnam(group)
            except KeyError:
                if value is not None:
                    raise Rejected("GROUP_IDENTITY_CHANGED")
                continue
            if value is None:
                if current.gr_mem or any(entry.pw_gid == current.gr_gid for entry in pwd.getpwall()):
                    raise Rejected("GROUP_STILL_REFERENCED")
                checked(["groupdel", group])
            elif current.gr_gid != value["gid"]:
                raise Rejected("GROUP_IDENTITY_CHANGED")
            elif sorted(current.gr_mem) != sorted(value["members"]):
                checked(["gpasswd", "-M", ",".join(value["members"]), group])
        # Never write /etc/passwd, /etc/group, /etc/shadow or the global Samba TDB.

    def capture_new_ports_for_restore(self, snapshot, payload):
        if "nvmeof subsystem apply" not in payload["candidate"]:
            return
        owned = {sub["targetName"] for commands in (payload["candidate"], payload["before"])
                 for sub in commands.get("nvmeof subsystem apply", {}).get("subsystems", [])}
        root = Path("/sys/kernel/config/nvmet/ports")
        if root.is_dir():
            for port in root.iterdir():
                if port.name in snapshot.get("nvmePortIds", []):
                    continue
                names = {link.name for link in (port / "subsystems").iterdir()} if (port / "subsystems").is_dir() else set()
                if names & owned:
                    if names - owned:
                        raise Rejected("KERNEL_PORT_OWNERSHIP_CHANGED")
                    snapshot["kernel"][str(port)] = None

    def restore_kernel(self, trees):
        links = validated_kernel_links(trees)
        # Detach added links across all affected roots before removing a new host/subsystem.
        for name, tree in trees.items():
            root = Path(name)
            if not root.exists():
                continue
            expected = tree["links"] if tree else {}
            for directory, dirs, files in os.walk(root, followlinks=False):
                for item in files + list(dirs):
                    path = Path(directory) / item
                    if path.is_symlink():
                        relative = str(path.relative_to(root))
                        if relative not in expected:
                            path.unlink()
                        if item in dirs:
                            dirs.remove(item)
        rank = lambda name: 0 if "/hosts/" in name else 1 if "/subsystems/" in name else 2
        for name in sorted(trees, key=rank):
            tree = trees[name]; root = Path(name)
            if tree is None:
                continue
            root.mkdir(exist_ok=True)
            desired_dirs = set(tree["directories"])
            for directory, dirs, files in os.walk(root, topdown=False, followlinks=False):
                relative = str(Path(directory).relative_to(root))
                if relative != "." and relative not in desired_dirs and not Path(directory).is_symlink():
                    self.remove_kernel_directory(Path(directory))
            for relative in sorted(tree["directories"], key=lambda x: x.count("/")):
                (root / relative).mkdir(exist_ok=True)
            for relative, value in sorted(tree["attributes"].items(), key=lambda item: item[0].endswith("/enable") or item[0] == "enable"):
                path = root / relative
                if not path.exists():
                    raise Rejected("KERNEL_ATTRIBUTE_UNAVAILABLE")
                if path.read_text().strip() != value:
                    enable = path.parent / "enable"
                    if path.name.startswith("device_") and enable.exists() and enable.read_text().strip() == "1":
                        enable.write_text("0")
                    path.write_text(value)
        for name in sorted(trees, key=rank):
            tree = trees[name]; root = Path(name)
            if tree is None:
                if root.exists():
                    self.remove_kernel_directory(root)
                continue
            for relative in tree["links"]:
                path = root / relative
                target = links[(name, relative)]
                if not target.is_dir() or target.is_symlink() or Path(os.path.realpath(target)) != target:
                    raise Rejected("KERNEL_LINK_TARGET_UNAVAILABLE")
                if path.is_symlink():
                    if kernel_link_target(path, os.readlink(path)) != target:
                        raise Rejected("KERNEL_LINK_IDENTITY_CHANGED")
                    continue
                if path.exists():
                    raise Rejected("KERNEL_LINK_COLLISION")
                # configfs resolves the supplied target at syscall time, relative to cwd.
                path.symlink_to(str(target))

    def remove_kernel_directory(self, root):
        for directory, dirs, files in os.walk(root, topdown=False, followlinks=False):
            for item in files + dirs:
                path = Path(directory) / item
                if path.is_symlink():
                    path.unlink()
                elif path.name == "enable" and path.is_file():
                    path.write_text("0")
            Path(directory).rmdir()

    def verify_restored(self):
        self.verify(load(self.payload_path)["before"])
        snapshot = load(self.snapshot_path)
        for name, value in snapshot["metadata"].items():
            if value:
                stat = Path(name).stat()
                if (stat.st_uid, stat.st_gid, stat.st_mode & 0o7777) != (value["uid"], value["gid"], value["mode"]):
                    raise Rejected("METADATA_RESTORE_MISMATCH")


def run_child(private, operation, descriptor):
    private = Path(private)
    if operation not in OPERATIONS:
        raise Rejected("UNSUPPORTED_OPERATION")
    descriptor = int(descriptor)
    argv = ["/usr/local/bin/ablestack-storagectl"] + operation.split() + [f"/proc/self/fd/{descriptor}"]
    try:
        process = subprocess.Popen(argv, pass_fds=(descriptor,), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   env={**os.environ, "ABLESTACK_STORAGE_CONFIGURATION_TRANSACTION": "1"})
        payload = load(private / "request.json", {})
        budget = min(1800, max(300, int((payload.get("executionProfile") or {}).get("softdeadlineseconds", 300))))
        began = time.time()
        child_identity = {"pid": process.pid, "boot": Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                          "start": Path("/proc/%s/stat" % process.pid).read_text().split()[21]}
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ, "stdout")
        selector.register(process.stderr, selectors.EVENT_READ, "stderr")
        tails = {"stdout": b"", "stderr": b""}
        totals = {"stdout": 0, "stderr": 0}
        limits = {"stdout": 8 * 1024 * 1024, "stderr": 64 * 1024}
        heartbeat = 0
        while selector.get_map():
            if time.monotonic() >= heartbeat:
                atomic_json(private / "runner-progress.json",
                            {"child": child_identity, "heartbeatAt": time.time(), "startedAt": began,
                             "softDeadlineAt": began + budget, "operation": operation,
                             "cancellationSupported": False})
                heartbeat = time.monotonic() + 5
            for key, _ in selector.select(.25):
                chunk = os.read(key.fileobj.fileno(), 65536)
                if not chunk:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                name = key.data
                totals[name] += len(chunk)
                tails[name] = (tails[name] + chunk)[-limits[name]:]
        process.wait()
        selector.close()
        parsed = {}
        for line in reversed(tails["stdout"].decode(errors="replace").splitlines()):
            try:
                value = json.loads(line)
                if isinstance(value, dict):
                    parsed = value
                    break
            except ValueError:
                pass
        atomic_json(private / "runner-result.json",
                    {"returncode": process.returncode, "result": redact(parsed), "operation": operation,
                     "outputTruncated": totals["stdout"] > limits["stdout"] or totals["stderr"] > limits["stderr"],
                     "observationUnknown": process.returncode == 0 and not parsed})

    except BaseException as error:
        atomic_json(private / "runner-result.json", {"returncode": -1, "type": type(error).__name__})
        raise


def main(argv):
    if argv[:2] == ["capability", "show"]:
        print(json.dumps({"configurationTransactionVersion": VERSION, "configurationPreflightVersion": 1,
                          "configurationObservationVersion": 1}))
        return 0
    if len(argv) == 3 and argv[:2] == ["preflight", "show"]:
        from configuration_preflight import inspect
        source = Path(argv[2])
        if source.stat().st_size > 256 * 1024:
            raise Rejected("PAYLOAD_LIMIT")
        print(json.dumps(inspect(load(source))))
        return 0
    if len(argv) != 3 or argv[0] != "operation":
        raise Rejected("INVALID_COMMAND")
    source = Path(argv[2])
    if source.stat().st_size > 4 * 1024 * 1024:
        raise Rejected("PAYLOAD_LIMIT")
    payload = load(source)
    transaction = Transaction()
    result = transaction.show(payload) if argv[1] == "show" else transaction.execute(argv[1], payload)
    print(json.dumps(result, sort_keys=True))
    return 0 if argv[1] == "show" or result.get("state") in ("VERIFIED", "COMMITTED", "ROLLED_BACK") else 1


if __name__ == "__main__":
    try:
        if len(sys.argv) == 5 and sys.argv[1] == "--run":
            run_child(sys.argv[2], sys.argv[3], sys.argv[4])
        else:
            sys.exit(main(sys.argv[1:]))
    except (Rejected, BlockingIOError) as error:
        print(json.dumps({"code": getattr(error, "code", "CONFIGURATION_BUSY")}))
        sys.exit(1)
