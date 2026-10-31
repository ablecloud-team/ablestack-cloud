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
import base64
import contextlib
import fcntl
import re
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
OPERATIONS = {"directory policy apply", "nfs export apply", "smb share apply", "iscsi target apply",
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



class NfsIdentityMapping:
    """Process-bound, volatile evidence. A configuration read never starts a service."""
    VERSION = 1
    MODE = "nfs4IdentityMappingMode"
    REVISION = "nfs4IdentityPolicyRevision"
    def __init__(self, config=Path("/etc/ganesha/ablestack-storage"),
                 run=Path("/run/ablestack-storage/ganesha"),
                 desired=Path("/etc/ablestack-storage/desired-state/nfs-export-apply.json")):
        self.config, self.run, self.desired = Path(config), Path(run), Path(desired)

    @staticmethod
    def policy(value):
        mode = value.get(NfsIdentityMapping.MODE, "NAME_DOMAIN")
        revision = value.get(NfsIdentityMapping.REVISION, 0)
        if mode not in ("NAME_DOMAIN", "NUMERIC") or type(revision) is not int or revision < 0:
            raise Rejected("NFS_IDENTITY_POLICY_INVALID")
        return mode, revision

    @staticmethod
    def lines(value):
        mode, _ = NfsIdentityMapping.policy(value)
        return ["    Allow_Numeric_Owners = true;",
                "    Only_Numeric_Owners = %s;" % ("true" if mode == "NUMERIC" else "false")]

    @staticmethod
    def semantics(text):
        import re
        clean = re.sub(r"(?m)#.*$", "", text)
        block = re.search(r"\bNFSV4\s*\{([^{}]*)\}", clean, re.I)
        body = block.group(1) if block else ""
        def setting(key, default):
            values = re.findall(r"\b" + key + r'\s*=\s*([^;]+);', body, re.I)
            if len(values) > 1:
                raise Rejected("NFS_IDENTITY_CONFIG_AMBIGUOUS")
            return values[0].strip().strip('"') if values else default
        allow, only = setting("Allow_Numeric_Owners", "true").lower(), setting("Only_Numeric_Owners", "false").lower()
        if allow not in ("true", "false") or only not in ("true", "false"):
            raise Rejected("NFS_IDENTITY_CONFIG_INVALID")
        domain = setting("DomainName", "")
        source = "NFSV4.DomainName" if domain else "nfsidmap"
        idmap = setting("IdmapConf", "")
        if not domain and idmap:
            import configparser
            parser = configparser.ConfigParser()
            parser.read(idmap)
            domain = parser.get("General", "Domain", fallback="").strip()
            source = "IdmapConf"
        if not domain:
            try:
                result = subprocess.run(["nfsidmap", "-d"], capture_output=True, text=True, timeout=3, check=False)
                domain = result.stdout.strip() if result.returncode == 0 else ""
            except (OSError, subprocess.TimeoutExpired):
                domain = ""
        # No domain is provisioned or silently invented on read.
        security = sorted({flavor.strip().lower() for line in re.findall(r"\bSecType\s*=\s*([^;]+);", clean, re.I)
                           for flavor in line.split(",")})
        return {"allow": allow == "true", "only": only == "true", "domain": domain, "source": source, "security": security}

    @staticmethod
    def process(pid):
        proc = Path("/proc") / str(int(pid))
        exe = (proc / "exe").resolve(strict=True)
        if exe.name != "ganesha.nfsd":
            raise Rejected("NFS_IDENTITY_PROCESS_INVALID")
        stat = (proc / "stat").read_text().rsplit(")", 1)[1].split()
        return {"pid": int(pid), "boot": Path("/proc/sys/kernel/random/boot_id").read_text().strip(), "start": stat[19]}

    def publish(self, key, path, pid, value):
        mode, revision = self.policy(value)
        semantic = self.semantics(Path(path).read_text())
        if not semantic["allow"] or semantic["only"] != (mode == "NUMERIC"):
            raise Rejected("NFS_IDENTITY_RENDER_MISMATCH")
        if mode == "NUMERIC" and semantic["security"] != ["sys"]:
            raise Rejected("NFS_NUMERIC_AUTH_SYS_REQUIRED")
        receipt = {"mode": mode, "revision": revision, "process": self.process(pid),
                   "signature": public_hash(semantic), "semantic": semantic, "observedat": str(time.time())}
        atomic_json(self.run / (key + ".identity.json"), receipt)

    def complete(self, keys, value):
        mode, revision = self.policy(value)
        atomic_json(self.run / "identity-endpoints.json", {"keys": sorted(keys), "mode": mode, "revision": revision})

    def observe(self, desired=None):
        desired = load(self.desired, {}) if desired is None else desired
        mode, revision = self.policy(desired)
        rows = []
        try:
            manifest = load(self.run / "identity-endpoints.json", {})
            keys = set(manifest.get("keys", [])) | {p.stem for p in self.config.glob("*.conf")}
        except (ValueError, OSError, TypeError):
            return {"observationstatus": "UNKNOWN", "endpoints": [], "observeddomain": "", "observedat": str(time.time())}
        for key in sorted(keys):
            import re
            path = self.config / (key + ".conf")
            if not path.is_file():
                rows.append({"endpoint": key, "state": "DRIFT", "configuredmode": mode, "configuredrevision": revision})
                continue
            body = path.read_text()
            port = re.search(r"\bNFS_Port\s*=\s*(\d+)\s*;", body, re.I)
            row = {"port": int(port.group(1)) if port else 2049, "endpoint": path.stem,
                   "configuredmode": mode, "configuredrevision": revision}
            try:
                receipt = load(self.run / (path.stem + ".identity.json"))
                pid = int((self.run / (path.stem + ".pid")).read_text().strip())
                process = self.process(pid)
                semantic = self.semantics(body)
                if receipt is None:
                    row["state"] = "LEGACY_UNCONFIRMED"
                elif receipt.get("process") != process:
                    row["state"] = "UNKNOWN"
                elif (receipt.get("mode"), receipt.get("revision")) != (mode, revision) or receipt.get("signature") != public_hash(semantic):
                    row["state"] = "DRIFT"
                elif not semantic["allow"] or semantic["only"] != (mode == "NUMERIC"):
                    row["state"] = "DRIFT"
                else:
                    row.update(state="APPLIED", effectivemode=mode, observedrevision=revision,
                               observeddomain=semantic["domain"], observedat=receipt["observedat"])
            except (OSError, ValueError, Rejected):
                row["state"] = "UNKNOWN"
            rows.append(row)
        states = {row["state"] for row in rows}
        status = ("NOT_RUNNING" if not rows else "APPLIED" if states == {"APPLIED"} else
                  "DRIFT" if "DRIFT" in states else "UNKNOWN" if "UNKNOWN" in states else "LEGACY_UNCONFIRMED")
        result = {"observationstatus": status, "endpoints": rows, "observedat": str(time.time()),
                  "observeddomain": self.semantics("")["domain"]}
        if status == "APPLIED":
            domains = {row.get("observeddomain", "") for row in rows}
            result["observeddomain"] = next(iter(domains)) if len(domains) == 1 else ""
            result.update(effectivemode=mode, policyrevision=revision)
        return result

    def inspect(self):
        # Optional NFS observation must not disable unrelated transaction capabilities.
        try:
            return self.observe()
        except (OSError, ValueError, TypeError, KeyError, Rejected):
            return {"observationstatus": "UNKNOWN", "endpoints": [], "observeddomain": "",
                    "observedat": str(time.time()), "code": "NFS_IDENTITY_OBSERVATION_UNAVAILABLE"}

    def verify(self, desired):
        observation = self.observe(desired)
        if observation["observationstatus"] not in ("APPLIED", "NOT_RUNNING"):
            raise Rejected("NFS_IDENTITY_RUNTIME_MISMATCH")
        return observation


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

    @staticmethod
    def bindings_status(value, private):
        receipt = load(private / "pathbindings.json", {})
        if receipt:
            value["pathbindings"] = {k: receipt[k] for k in ("operationid", "instanceUuid")}
            value["pathbindings"]["bindings"] = list(receipt.get("bindings", {}).values())
            if receipt.get("retained"): value["pathbindings"]["retained"] = receipt["retained"]
        return value

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
        if value.get("state") != "NOT_FOUND":
            progress = load(status.parent / "smb-options-progress.json")
            if progress: value["smbOptions"] = progress
        return self.bindings_status(value, status.parent)

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
                if current.get("smbListenerOnly"):
                    self.adapter_factory(private).finalize_smb_listeners(payload)
                if (private / "pathbindings.json").exists(): self.adapter_factory(private).finalize_paths()
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
                    self.bindings_status(current, private)
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
            if payload.get("candidate", {}).get("smb share apply", {}).get("listenerOnly"):
                current["smbListenerOnly"] = True
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
                self.bindings_status(current, private)
                current.update(state="VERIFIED", phase="COMMITTING")
                atomic_json(status_path, current)
                return current
            except BaseException as error:
                progress = load(private / "smb-options-progress.json")
                if progress: current["smbOptions"] = progress
                if getattr(error, "code", None) in ("APPLY_STILL_RUNNING", "RESULT_OBSERVATION_UNAVAILABLE"):
                    current.update(state="RECOVERY_REQUIRED", phase="OBSERVING", rollback={"state": "UNKNOWN"},
                                   observationCode=getattr(error, "code", "OBSERVATION_UNAVAILABLE"))
                    atomic_json(status_path, current)
                    return current
                # First failure is durable BEFORE compensation. Never expose raw subprocess output.
                current.setdefault("originalfailure", {"code": getattr(error, "code", "CONFIGURATION_APPLY_FAILED"),
                                   "phase": current["phase"], "type": type(error).__name__})
                original = load(private / "original-runner-result.json", {})
                detail = original.get("result") or {}
                if detail.get("code") in ("NFS_EXPORT_VISIBILITY_FAILED", "NFS_LOCAL_ADDRESS_OBSERVATION_UNAVAILABLE"):
                    current["originalfailure"]["detail"] = {k: detail[k] for k in ("code", "failures") if k in detail}
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


class SmbListenerProfiles:
    """SMB binding overlays. Shared auth/locking; individually owned process groups."""
    VERSION = 1

    def __init__(self, root=Path("/etc/ablestack-storage/smb-listeners"),
                 base=Path("/etc/samba/smb.conf"), namespace=None):
        self.root, self.base, self.namespace = Path(root), Path(base), namespace
        self.manifest = self.root.parent / (self.root.name + ".json")
        self.desired = self.root.parent / "desired-state/smb-share-apply.json"

    @contextlib.contextmanager
    def locked(self):
        if self.root.is_symlink() or self.manifest.is_symlink():
            raise Rejected("SMB_PROFILE_IDENTITY_CHANGED")
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.root, 0o700)
        with (self.root / ".lock").open("a") as stream:
            os.chmod(self.root / ".lock", 0o600)
            fcntl.flock(stream, fcntl.LOCK_EX)
            yield

    @staticmethod
    def normalized(value):
        import ipaddress
        rows, seen, identities = [], set(), set()
        for item in value.get("listeners", []):
            if item.get("state") in ("Disabled", "Destroyed", "Error"):
                continue
            identity = token(item.get("id"))
            try:
                ip = ipaddress.IPv4Address(str(item.get("listenIp") or "0.0.0.0"))
                port = int(item.get("port") or 445)
                if ip.is_multicast or ip.is_loopback or not 1 <= port <= 65535:
                    raise ValueError()
            except (ValueError, TypeError):
                raise Rejected("INVALID_SMB_ENDPOINT")
            pair = (str(ip), port)
            if pair in seen or identity in identities:
                raise Rejected("DUPLICATE_SMB_ENDPOINT")
            seen.add(pair); identities.add(identity)
            rows.append({"id": identity, "listenIp": str(ip), "port": port})
        return sorted(rows, key=lambda row: (row["listenIp"], row["port"], row["id"]))

    @staticmethod
    def key(row):
        return token(row["id"]) + "-" + public_hash([row["listenIp"], row["port"]])[:12]

    @staticmethod
    def sockets(pid=None, namespace_pid=None):
        import socket
        owned = None
        if pid:
            try:
                owned = {os.readlink(p) for p in Path("/proc/%s/fd" % pid).iterdir()}
            except OSError:
                return set()
        result = set()
        for line in Path("/proc/%s/net/tcp" % (pid or namespace_pid)).read_text().splitlines()[1:] if pid or namespace_pid else Path("/proc/net/tcp").read_text().splitlines()[1:]:
            fields = line.split()
            if fields[3] != "0A" or owned is not None and "socket:[" + fields[9] + "]" not in owned:
                continue
            raw, port = fields[1].split(":")
            result.add((socket.inet_ntoa(bytes.fromhex(raw)[::-1]), int(port, 16)))
        return result

    def ctl(self, *args):
        return checked(["systemctl"] + list(args)).decode().strip()

    def validated_unit(self, row):
        self.normalized({"listeners": [row]})
        expected = "smbd.service" if row.get("kind") == "legacy" else "ablestack-smb-" + self.key(row) + ".service"
        if row.get("unit") != expected or row.get("key") != (row["id"] + "-legacy" if row.get("kind") == "legacy" else self.key(row)):
            raise Rejected("SMB_PROFILE_IDENTITY_CHANGED")
        return expected

    def raw_pid(self, row):
        self.validated_unit(row)
        result = subprocess.run(["systemctl", "show", row["unit"], "-p", "MainPID", "-p", "LoadState"],
                                capture_output=True, text=True, timeout=10, env={**os.environ, "LC_ALL": "C"})
        values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
        if values.get("LoadState") == "not-found" or result.returncode and "could not be found" in result.stderr:
            return 0
        if result.returncode:
            raise Rejected("SMB_UNIT_OBSERVATION_UNAVAILABLE")
        return int(values.get("MainPID") or 0)

    def pid(self, row):
        try:
            pid = self.raw_pid(row)
            if pid <= 1 or Path("/proc/%d/exe" % pid).resolve() != Path("/usr/sbin/smbd").resolve():
                return None
            args = Path("/proc/%d/cmdline" % pid).read_bytes().split(b"\0")
            if row.get("kind") != "legacy":
                config = self.root / (row["key"] + ".conf")
                if row["unit"] != "ablestack-smb-" + row["key"] + ".service":
                    return None
                if str(config).encode() not in args and ("--configfile=" + str(config)).encode() not in args:
                    return None
                if config.is_symlink() or hashlib.sha256(config.read_bytes()).hexdigest() != row.get("configHash"):
                    return None
                if os.getpgid(pid) != pid:
                    return None
                # systemd already creates the isolated process group.  Keep its
                # cgroup ownership proof when Samba skips its own setsid().
                group = self.ctl("show", row["unit"], "-p", "ControlGroup", "--value")
                memberships = {line.split(":", 2)[2] for line in
                               Path("/proc/%d/cgroup" % pid).read_text().splitlines()}
                if not group or group not in memberships:
                    return None
            return pid
        except (OSError, ValueError, Rejected):
            return None

    def ready(self, row, profiles):
        if row.get("kind") == "covered":
            owner = profiles.get(row.get("coveredBy"))
            return bool(owner and owner.get("kind") != "covered" and self.ready(owner, profiles))
        pid = self.pid(row)
        return bool(pid and (row["listenIp"], row["port"]) in self.sockets(pid))

    def record(self, row):
        key = self.key(row)
        return {**row, "key": key, "kind": "profile", "unit": "ablestack-smb-" + key + ".service"}

    def configuration(self, row):
        key = row["key"]
        if key != self.key(row):
            raise Rejected("INVALID_SMB_PROFILE_KEY")
        pid_dir = self.root / key
        pid_dir.mkdir(mode=0o700, exist_ok=True)
        binding = (" interfaces = " + row["listenIp"] + "\n bind interfaces only = yes\n"
                   if row["listenIp"] != "0.0.0.0" else " bind interfaces only = no\n interfaces =\n")
        text = ("[global]\n include = " + str(self.base) + "\n[global]\n"
                " smb ports = " + str(row["port"]) + "\n" + binding +
                " pid directory = " + str(pid_dir) + "\n")
        path = self.root / (key + ".conf")
        if path.is_symlink():
            raise Rejected("SMB_PROFILE_IDENTITY_CHANGED")
        if not path.exists() or path.read_text() != text:
            fd, temporary = tempfile.mkstemp(prefix=".smb-", dir=self.root)
            try:
                os.fchmod(fd, 0o600)
                with os.fdopen(fd, "w") as stream:
                    stream.write(text); stream.flush(); os.fsync(stream.fileno())
                checked(["testparm", "-s", temporary])
                os.replace(temporary, path)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        row["configHash"] = hashlib.sha256(path.read_bytes()).hexdigest()
        return path

    def start(self, row, profiles):
        if self.ready(row, profiles):
            return
        if row.get("kind") == "covered":
            if not self.ready(row, profiles):
                raise Rejected("SMB_COVERING_LISTENER_UNAVAILABLE")
            return
        if row.get("kind") == "legacy":
            if self.namespace:
                raise Rejected("SMB_LEGACY_OUTSIDE_SCOPE")
            checked(["systemctl", "start", "smbd"])
        else:
            pair = (row["listenIp"], row["port"])
            if any(port == pair[1] and (ip == pair[0] or ip == "0.0.0.0" or pair[0] == "0.0.0.0")
                   for ip, port in self.sockets(namespace_pid=int(self.namespace.parent.parent.name) if self.namespace else None)):
                raise Rejected("SMB_ENDPOINT_IN_USE")
            config = self.configuration(row)
            args = ["systemd-run", "--quiet", "--collect", "--unit=" + row["unit"],
                    "--property=Type=simple", "--property=Restart=no",
                    "--property=KillMode=control-group"]
            if self.namespace:
                args.append("--property=NetworkNamespacePath=" + str(self.namespace))
            args += ["/usr/sbin/smbd", "-F", "--no-process-group", "--configfile=" + str(config)]
            checked(args)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if self.ready(row, profiles):
                return
            time.sleep(.1)
        # Stop only the unit whose executable/config proves ownership.
        if self.pid(row):
            self.stop(row)
        raise Rejected("SMB_ENDPOINT_NOT_OWNED_OR_READY")

    def stop(self, row):
        if row.get("kind") == "covered":
            return
        raw_pid = self.raw_pid(row)
        if raw_pid and not self.pid(row):
            raise Rejected("SMB_PROFILE_OWNERSHIP_CHANGED")
        if raw_pid:
            checked(["systemctl", "stop", row["unit"]])
        if row.get("kind") == "legacy":
            checked(["systemctl", "disable", "smbd"])
        elif row["key"] != self.key(row):
            raise Rejected("INVALID_SMB_PROFILE_KEY")
        else:
            path = self.root / (row["key"] + ".conf")
            if path.exists():
                if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != row.get("configHash"):
                    raise Rejected("SMB_PROFILE_OWNERSHIP_CHANGED")
                path.unlink()
            with contextlib.suppress(FileNotFoundError):
                (self.root / row["key"]).rmdir()

    def bootstrap(self, value, before):
        state = load(self.manifest)
        if state:
            if state.get("instanceUuid") != token(value.get("instanceUuid")):
                raise Rejected("SMB_INSTANCE_IDENTITY_CHANGED")
            return state
        profiles = {}
        previous = self.normalized(before)
        legacy_pid = 0 if self.namespace else int(self.ctl("show", "smbd", "-p", "MainPID", "--value") or 0)
        if legacy_pid:
            desired = load(self.desired, {})
            if len(previous) != 1 or desired.get("instanceUuid") != value.get("instanceUuid"):
                raise Rejected("SMB_LEGACY_IDENTITY_UNCONFIRMED")
            row = {**previous[0], "key": previous[0]["id"] + "-legacy", "kind": "legacy", "unit": "smbd.service"}
            observed = {(ip, port) for ip, port in self.sockets(legacy_pid) if not ip.startswith("127.")}
            if observed != {(row["listenIp"], row["port"])} or not self.pid(row):
                raise Rejected("SMB_LEGACY_BINDING_UNCONFIRMED")
            profiles[row["key"]] = row
        elif not self.namespace and self.base == Path("/etc/samba/smb.conf") and not load(self.desired):
            if value.get("shares") or (value.get("identityDomain") or {}).get("domainName"):
                raise Rejected("SMB_BASE_CONFIGURATION_UNCONFIRMED")
            self.base.write_text("[global]\n security = user\n workgroup = WORKGROUP\n"
                                 " passdb backend = tdbsam\n load printers = no\n"
                                 " printing = bsd\n disable spoolss = yes\n")
            os.chmod(self.base, 0o644)
        for old in previous:
            if not any(row["id"] == old["id"] for row in profiles.values()):
                row = self.record(old); profiles[row["key"]] = row
                self.configuration(row); self.start(row, profiles)
        return {"schemaVersion": self.VERSION, "instanceUuid": token(value["instanceUuid"]), "profiles": profiles}

    def remember_added_aliases(self, state, desired, original):
        old = {address.get("local") for row in original for address in row.get("addr_info", [])}
        current = json.loads(checked(["ip", "-j", "addr", "show"]))
        network = load(self.root.parent / "network-endpoints.json", {}).get("endpoints", [])
        wanted = {row["listenIp"] for row in desired}
        owned = state.setdefault("ownedAliases", {})
        for link in current:
            for address in link.get("addr_info", []):
                ip = address.get("local")
                if ip not in wanted or ip in old or not address.get("secondary"):
                    continue
                entry = next((e for e in network if e.get("listenIp") == ip), None)
                if not entry or str(entry.get("macAddress", "")).lower() != str(link.get("address", "")).lower():
                    continue
                if int(entry.get("prefixlen", -1)) != int(address["prefixlen"]):
                    continue
                owned[ip] = {"listenIp": ip, "interface": link["ifname"], "macAddress": link["address"].lower(),
                             "prefixlen": address["prefixlen"]}

    def release_owned_aliases(self, state, value):
        requested = value.get("releaseAddresses", [])
        if not requested:
            return
        owned = state.setdefault("ownedAliases", {})
        desired = self.normalized(value)
        path = self.root.parent / "network-endpoints.json"
        for ip in requested:
            alias = owned.get(ip)
            # A server-side reference check is required too. The guest additionally
            # rejects arbitrary/manual/primary addresses and active SMB bindings.
            if not alias or any(row["listenIp"] in (ip, "0.0.0.0") for row in desired):
                raise Rejected("SMB_ALIAS_RELEASE_NOT_OWNED")
            network = load(path, {})
            entries = network.get("endpoints", [])
            entry = next((e for e in entries if e.get("listenIp") == ip), None)
            if entry and (str(entry.get("macAddress", "")).lower() != alias["macAddress"] or
                          int(entry.get("prefixlen", -1)) != alias["prefixlen"]):
                raise Rejected("SMB_ALIAS_IDENTITY_CHANGED")
            current = json.loads(checked(["ip", "-j", "addr", "show"]))
            link = next((row for row in current if row.get("ifname") == alias["interface"] and
                         str(row.get("address", "")).lower() == alias["macAddress"]), None)
            if link is None:
                raise Rejected("SMB_ALIAS_IDENTITY_CHANGED")
            address = next((a for a in link.get("addr_info", []) if a.get("local") == ip), None)
            if address:
                if not entry or not address.get("secondary") or address.get("prefixlen") != alias["prefixlen"]:
                    raise Rejected("SMB_ALIAS_RELEASE_NOT_SECONDARY")
                checked(["ip", "addr", "del", ip + "/" + str(alias["prefixlen"]), "dev", alias["interface"]])
            if entry:
                network["endpoints"] = [e for e in entries if e.get("listenIp") != ip]
                atomic_json(path, network)
            owned.pop(ip)
            atomic_json(self.manifest, state)

    def stage(self, value, before, operation, original_network=None):
        operation = token(operation)
        desired = self.normalized(value)
        with self.locked():
            state = self.bootstrap(value, before)
            pending = state.get("pending")
            if pending and (pending["operationid"] != operation or pending["hash"] != public_hash(value)):
                raise Rejected("SMB_LISTENER_OPERATION_PENDING")
            if not pending:
                pending = {"operationid": operation, "hash": public_hash(value),
                           "before": json.loads(json.dumps(state["profiles"])), "value": redact(value)}
                state["pending"] = pending
            profiles = state["profiles"]
            pending.setdefault("beforeOwnedAliases", json.loads(json.dumps(state.get("ownedAliases", {}))))
            if original_network is not None:
                self.remember_added_aliases(state, desired, original_network)
            wanted = []
            wildcards = {row["port"]: row for row in desired if row["listenIp"] == "0.0.0.0"}
            for item in desired:
                existing = next((row for row in profiles.values()
                                 if row["id"] == item["id"] and row["listenIp"] == item["listenIp"] and row["port"] == item["port"]), None)
                row = existing or self.record(item)
                owner = wildcards.get(item["port"])
                if owner and item["listenIp"] != "0.0.0.0":
                    cover = next((p for p in profiles.values() if p["id"] == owner["id"] and p["listenIp"] == "0.0.0.0"), None)
                    if not cover:
                        raise Rejected("SMB_WILDCARD_TRANSITION_REQUIRES_REVIEW")
                    row = {**row, "kind": "covered", "coveredBy": cover["key"]}
                elif item["listenIp"] == "0.0.0.0" and not existing:
                    if any(p["port"] == item["port"] and p["listenIp"] != "0.0.0.0" for p in profiles.values()):
                        raise Rejected("SMB_WILDCARD_TRANSITION_REQUIRES_REVIEW")
                if row.get("kind") == "profile":
                    self.configuration(row)
                profiles[row["key"]] = row; wanted.append(row["key"])
                pending["wanted"] = wanted
                atomic_json(self.manifest, state)
                self.start(row, profiles)
                atomic_json(self.manifest, state)
            pending["wanted"] = wanted
            atomic_json(self.manifest, state)
            self.verify(value, state)
            return {"success": True, "runtimeReady": True, "listeners": len(wanted)}

    def verify_legacy_restored(self, value, snapshot):
        # A failed first migration may never have created a profile manifest.
        # Prove the original configuration and actual legacy listener read-only.
        import base64
        rows = self.normalized(value)
        if str(self.manifest) in snapshot["files"]:
            raise Rejected("SMB_PROFILE_BEFORE_IMAGE_MISSING")
        for path in (self.base, self.desired):
            image = snapshot["files"].get(str(path))
            if rows and not image:
                raise Rejected("SMB_LEGACY_BEFORE_IMAGE_UNAVAILABLE")
            if image:
                info = path.stat()
                if (path.is_symlink() or path.read_bytes() != base64.b64decode(image["content"]) or
                        (info.st_uid, info.st_gid, info.st_mode & 0o777) !=
                        (image["uid"], image["gid"], image["mode"])):
                    raise Rejected("SMB_LEGACY_BEFORE_IMAGE_CHANGED")
        if not rows:
            if int(self.ctl("show", "smbd", "-p", "MainPID", "--value") or 0):
                raise Rejected("SMB_LEGACY_BINDING_UNCONFIRMED")
            return
        persisted = load(self.desired, {})
        if len(rows) != 1 or persisted.get("instanceUuid") != value.get("instanceUuid") or self.normalized(persisted) != rows:
            raise Rejected("SMB_LEGACY_IDENTITY_UNCONFIRMED")
        row = {**rows[0], "key": rows[0]["id"] + "-legacy", "kind": "legacy", "unit": "smbd.service"}
        pid = self.pid(row)
        if not pid or {(ip, port) for ip, port in self.sockets(pid) if not ip.startswith("127.")} != {(row["listenIp"], row["port"])}:
            raise Rejected("SMB_LEGACY_BINDING_UNCONFIRMED")
        args = Path("/proc/%s/cmdline" % pid).read_bytes().split(b"\0")
        config = None
        for index, arg in enumerate(args):
            if arg in (b"-s", b"--configfile") and index + 1 < len(args):
                config = os.fsdecode(args[index + 1])
            elif arg.startswith(b"--configfile="):
                config = os.fsdecode(arg.split(b"=", 1)[1])
        if (Path(config).resolve() if config else Path("/etc/samba/smb.conf")) != self.base.resolve():
            raise Rejected("SMB_LEGACY_CONFIGURATION_UNCONFIRMED")
        for observed in snapshot.get("smbListeners", {}).get("listeners", []):
            if observed.get("id") == row["id"] and observed.get("pid") and observed["pid"] != pid:
                raise Rejected("SMB_LEGACY_PROCESS_CHANGED")

    def verify(self, value, state=None):
        state = state or load(self.manifest, {})
        profiles = state.get("profiles", {})
        for desired in self.normalized(value):
            row = next((p for p in profiles.values() if p["id"] == desired["id"] and
                        p["listenIp"] == desired["listenIp"] and p["port"] == desired["port"]), None)
            if not row or not self.ready(row, profiles):
                raise Rejected("SMB_LISTENER_NOT_READY")

    def commit(self, operation):
        with self.locked():
            state = load(self.manifest, {})
            pending = state.get("pending")
            if not pending:
                return
            if pending["operationid"] != token(operation):
                raise Rejected("SMB_LISTENER_OPERATION_IDENTITY_CHANGED")
            wanted = set(pending.get("wanted", []))
            for key, row in list(state["profiles"].items()):
                if key not in wanted:
                    if any(p.get("coveredBy") == key for k, p in state["profiles"].items() if k in wanted):
                        raise Rejected("SMB_WILDCARD_STILL_REFERENCED")
                    self.stop(row); state["profiles"].pop(key)
                    atomic_json(self.manifest, state)
            self.verify(pending["value"], state)
            self.release_owned_aliases(state, pending["value"])
            value = pending["value"]
            atomic_json(self.desired, {k: v for k, v in value.items()
                                       if k not in ("configurationOperationId", "releaseAddresses")})
            state.pop("pending")
            atomic_json(self.manifest, state)

    def rollback(self, operation):
        with self.locked():
            state = load(self.manifest, {})
            pending = state.get("pending")
            if not pending:
                return
            if pending["operationid"] != token(operation):
                raise Rejected("SMB_LISTENER_OPERATION_IDENTITY_CHANGED")
            previous = pending["before"]
            for key, row in state["profiles"].items():
                if key not in previous:
                    self.stop(row)
            state["profiles"] = previous
            state["ownedAliases"] = pending.get("beforeOwnedAliases", state.get("ownedAliases", {}))
            state.pop("pending")
            atomic_json(self.manifest, state)

    def boot(self, value):
        state = load(self.manifest, {})
        if state.get("pending"):
            with self.locked():
                for row in sorted(state["profiles"].values(), key=lambda row: row.get("kind") == "covered"):
                    self.start(row, state["profiles"])
                atomic_json(self.manifest, state)
            return
        operation = str(uuid.UUID(int=0))
        self.stage(value, value, operation)
        self.commit(operation)

    def stop_all(self):
        with self.locked():
            state = load(self.manifest, {})
            for row in state.get("profiles", {}).values():
                self.stop(row)

    def refresh(self, value):
        # Full share/auth changes retain their existing all-SMB restart semantics.
        with self.locked():
            old = load(self.manifest, {})
            if old.get("pending"):
                for row in sorted(old["profiles"].values(), key=lambda row: row.get("kind") == "covered"):
                    self.start(row, old["profiles"])
                return
            for row in old.get("profiles", {}).values():
                self.stop(row)
            state = {"schemaVersion": 1, "instanceUuid": token(value["instanceUuid"]), "profiles": {},
                     "ownedAliases": old.get("ownedAliases", {})}
            atomic_json(self.manifest, state)
            for item in self.normalized(value):
                row = self.record(item)
                wildcard = next((r for r in state["profiles"].values() if r["listenIp"] == "0.0.0.0" and r["port"] == row["port"]), None)
                if wildcard and row["listenIp"] != "0.0.0.0":
                    row.update(kind="covered", coveredBy=wildcard["key"])
                self.configuration(row) if row["kind"] != "covered" else None
                state["profiles"][row["key"]] = row
                atomic_json(self.manifest, state)
                self.start(row, state["profiles"])
                atomic_json(self.manifest, state)
            self.verify(value, state)

    def show(self):
        state = load(self.manifest, {})
        profiles = state.get("profiles", {})
        if not state and not self.namespace:
            desired = load(self.desired, {})
            rows = self.normalized(desired)
            if len(rows) == 1:
                row = {**rows[0], "key": rows[0]["id"] + "-legacy", "kind": "legacy", "unit": "smbd.service"}
                profiles[row["key"]] = row
        result = []
        for row in profiles.values():
            ready = self.ready(row, profiles)
            result.append({"id": row["id"], "listenIp": row["listenIp"], "port": row["port"],
                           "state": ("COVERED" if row.get("kind") == "covered" else "READY") if ready else "UNKNOWN",
                           "coveredBy": profiles.get(row.get("coveredBy"), {}).get("id"), "pid": self.pid(row) if row.get("kind") != "covered" else None})
        return {"version": self.VERSION, "configured": bool(state or result), "listeners": result,
                "ownedAliases": list(state.get("ownedAliases", {}).values()), "pending": bool(state.get("pending")),
                "observedEpoch": time.time()}


# NFS POSIX application is an operation intent, never a side effect of rendering.
NFS_POSIX_VERSION = 1
NFS_POSIX_KEYS = ("ownerUid", "ownerGid", "mode", "recursivePermission")


def nfs_posix_observe(export, managed_root=Path("/srv/ablestack-storage/volumes")):
    import stat
    config = export.get("config") or {}
    result = {"resourceuuid": export.get("uuid"), "status": "UNAVAILABLE", "observedat": time.time()}
    try:
        mount = Path(config["volumeMountPath"])
        backing = Path(config["backingPath"])
        root = Path(managed_root).resolve()
        # Neither a root filesystem path nor an arbitrary caller-provided path.
        if mount.parent.resolve() != root or mount.is_symlink() or not mount.is_dir():
            return result
        if backing == mount or backing.is_relative_to(mount):
            if backing.is_symlink() or not backing.resolve().is_relative_to(mount.resolve()):
                return result
        else:
            return result
        fs = subprocess.run(["findmnt", "-n", "-o", "UUID", "--mountpoint", str(mount)],
                            capture_output=True, text=True, timeout=5)
        if fs.returncode or not fs.stdout.strip():
            return result
        filesystem = fs.stdout.strip()
        if config.get("filesystemUuid") and filesystem != config["filesystemUuid"]:
            return result
        identity = {"path": str(backing.resolve()), "volume": mount.name, "filesystem": filesystem}
        if not backing.exists():
            result["status"] = "PATH_MISSING"
        else:
            info = backing.lstat()
            if not stat.S_ISDIR(info.st_mode):
                return result
            acl = subprocess.run(["getfacl", "-cpn", str(backing)], capture_output=True, timeout=5)
            if acl.returncode:
                return result
            result.update(status="OBSERVED", owneruid=info.st_uid, ownergid=info.st_gid,
                          mode=format(stat.S_IMODE(info.st_mode), "04o"))
            identity.update(uid=info.st_uid, gid=info.st_gid, mode=result["mode"],
                            device=info.st_dev, inode=info.st_ino, acl=hashlib.sha256(acl.stdout).hexdigest())
        result["fingerprint"] = public_hash(identity)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        pass
    return result


def nfs_posix_observations(desired):
    return [nfs_posix_observe(row) for row in (desired or {}).get("exports", [])]


def nfs_posix_apply_config(export, payload, directory_created=False):
    """Return only the permitted chown/chmod intent; normal render/boot preserves roots."""
    config = export.get("config") or {}
    from shared_directory_policy import DirectoryPolicies
    if config.get("backingPath") and DirectoryPolicies().protected(config["backingPath"]):
        return {}
    intent = payload.get("posixIntent") or {}
    selected = intent.get("targetUuid") == export.get("uuid")
    fields = (intent.get("fields") or {}) if selected else {}
    initialization = directory_created
    receipt = config.get("lastInspection") or {}
    initialization = initialization or (
        selected and intent.get("create") is True and receipt.get("directoryCreated") is True
        and receipt.get("configurationOperationId")
        and receipt.get("configurationOperationId") == payload.get("configurationOperationId"))
    if config.get("posixPolicy") == "PRESERVE_EXISTING_INITIAL" and not fields:
        return {}
    if initialization:
        # A genuinely new managed directory keeps the established initial behavior.
        result = {k: config[k] for k in NFS_POSIX_KEYS if config.get(k) not in (None, "")}
        mapped = not config.get("readOnly", False) and (config.get("allSquash", False) or config.get("rootSquash", True))
        result.setdefault("ownerUid", config.get("anonUid", 65534) if mapped else 0)
        result.setdefault("ownerGid", config.get("anonGid", 65534) if mapped else 0)
        result.setdefault("mode", "0775" if mapped else "0755")
        result["recursivePermission"] = False
        if not fields:
            return result
    if not fields:
        return {}
    if any(k not in NFS_POSIX_KEYS for k in fields):
        raise Rejected("INVALID_NFS_POSIX_INTENT")
    if not initialization:
        observed = nfs_posix_observe(export)
        if observed["status"] != "OBSERVED":
            raise Rejected("POSIX_OBSERVATION_UNAVAILABLE")
        if not intent.get("expectedObservation") or observed.get("fingerprint") != intent["expectedObservation"]:
            raise Rejected("POSIX_OBSERVATION_CHANGED")
    # Recursive execution is never inherited from the stored export config.
    return {**(result if initialization else {}),
            **({k: config[k] for k in NFS_POSIX_KEYS if k in config} if fields.get("recursivePermission") is True else {}),
            **{k: v for k, v in fields.items() if v is not None},
            "recursivePermission": fields.get("recursivePermission") is True}


class RuntimeAdapter:
    """Existing storagectl render/apply engines with bounded private before-images."""
    def __init__(self, private):
        self.private = Path(private)
        self.snapshot_path = self.private / "before.json"
        self.payload_path = self.private / "request.json"
        self.child_path = self.private / "runner.json"

    def preflight(self, payload):
        if payload.get("onlineSmbOptions") is True and not self.smb_options_only(payload):
            raise Rejected("INVALID_SMB_OPTIONS_INTENT")
        if payload.get("vmUuid"):
            observed = Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()
            if observed != token(payload["vmUuid"]):
                raise Rejected("VM_IDENTITY_MISMATCH")
        for operation, value in payload["candidate"].items():
            if operation not in OPERATIONS or not isinstance(value, dict):
                raise Rejected("UNSUPPORTED_OPERATION")
            if value.get("instanceUuid") not in (None, payload["instanceUuid"]):
                raise Rejected("INSTANCE_IDENTITY_MISMATCH")
            if value.get("posixOnly") and not self.nfs_posix_only(payload):
                raise Rejected("INVALID_DEFERRED_POSIX_INTENT")
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
            if (self.smb_options_only(payload) or payload["preflight"].get("pathtarget")) and report.get("fingerprint") != payload["preflight"].get("expectedFingerprint"):
                raise Rejected("DIRECTORY_PATH_OBSERVATION_CHANGED" if payload["preflight"].get("pathtarget") else "SMB_OPTIONS_STALE_OBSERVATION")
            if report["status"] != "READY":
                blocked = next((c["code"] for c in report["checks"] if c["blocking"]), "RESOURCE_OBSERVATION_UNAVAILABLE")
                raise Rejected(blocked)
        if self.smb_options_only(payload):
            self.smb_options_engine().snapshot(payload)
        if self.directory_only(payload):
            from shared_directory_policy import DirectoryPolicies
            DirectoryPolicies().prepare(payload["candidate"]["directory policy apply"])
        if self.nfs_posix_only(payload):
            self.posix_only_plan(payload["candidate"]["nfs export apply"])
        if not self.smb_options_only(payload):
            from shared_directory_policy import FileSharePathBindings, PolicyError
            engine = self.path_bindings()
            for row in engine.rows(payload["candidate"]):
                preview = engine.inspect(row)
                if not preview["exists"] and not row["config"].get("createDirectory"):
                    raise PolicyError("DIRECTORY_CREATION_REQUIRED")
        self.payload = payload
        atomic_json(self.payload_path, redact(payload))


    def path_bindings(self, restoring=False):
        from shared_directory_policy import FileSharePathBindings
        return FileSharePathBindings(journal=self.private / "pathbindings.json", restoring=restoring)

    def finalize_paths(self):
        payload = load(self.payload_path, {})
        if (self.private / "pathbindings.json").exists():
            self.path_bindings().finalize(payload.get("candidate", {}))

    @staticmethod
    def smb_options_only(payload):

        return (payload or {}).get("onlineSmbOptions") is True and set((payload or {}).get("candidate", {})) == {"smb share apply"}

    def smb_options_engine(self):
        from shared_directory_policy import SmbCreationPolicies
        engine = SmbCreationPolicies()
        engine.progress = lambda value: atomic_json(self.private / "smb-options-progress.json", value)
        return engine

    def smb_options_snapshot(self):
        return load(self.snapshot_path)["smbOptions"]

    @staticmethod
    def nfs_posix_only(payload):
        commands = (payload or {}).get("candidate", {})
        return set(commands) == {"nfs export apply"} and commands["nfs export apply"].get("posixOnly") is True

    @staticmethod
    def posix_only_row(value):
        rows, intent = value.get("exports", []), value.get("posixIntent") or {}
        if len(rows) != 1 or intent.get("version") != 1 or intent.get("create") is not True:
            raise Rejected("INVALID_DEFERRED_POSIX_INTENT")
        row = rows[0]
        if intent.get("targetUuid") != row.get("uuid") or (intent.get("fields") or {}).get("recursivePermission") is True:
            raise Rejected("INVALID_DEFERRED_POSIX_INTENT")
        from shared_directory_policy import FileSharePathBindings
        if not FileSharePathBindings.bound(row) and nfs_posix_observe(row)["status"] != "OBSERVED":
            raise Rejected("POSIX_OBSERVATION_UNAVAILABLE")
        return row

    def posix_only_plan(self, value):
        row = self.posix_only_row(value)
        path = Path(row["config"]["backingPath"])
        from shared_directory_policy import FileSharePathBindings
        if FileSharePathBindings.bound(row):
            preview = self.path_bindings().inspect(row)
            if not preview["exists"]: return
            receipt = self.path_bindings().journal_value()
            created = any(v["relative"] == row["config"]["relativeSharePath"] and v["volume"] == row["config"]["volumeMountPath"] for v in receipt.get("created", []))
            allowed = nfs_posix_apply_config(row, value, created) if created else {}
        else:
            allowed = nfs_posix_apply_config(row, value)
        info = path.stat()
        expected = {"path": str(path), "device": info.st_dev, "inode": info.st_ino,
                    "uid": int(allowed.get("ownerUid", info.st_uid)),
                    "gid": int(allowed.get("ownerGid", info.st_gid)),
                    "mode": int(str(allowed["mode"]), 8) if "mode" in allowed else info.st_mode & 0o7777}
        atomic_json(self.private / "posix-expected.json", expected)

    def posix_only_fd(self, value):
        row = self.posix_only_row(value)
        expected = load(self.private / "posix-expected.json")
        path = row["config"]["backingPath"]
        if not expected or expected["path"] != path:
            raise Rejected("DIRECTORY_IDENTITY_CHANGED")
        from shared_directory_policy import FileSharePathBindings
        if FileSharePathBindings.bound(row):
            binding = self.path_bindings().ensure(row, "NFS")
            path = binding["anchor"]
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        info = os.fstat(fd)
        if (info.st_dev, info.st_ino) != (expected["device"], expected["inode"]):
            os.close(fd)
            raise Rejected("DIRECTORY_IDENTITY_CHANGED")
        return fd, expected

    def apply_posix_only(self, value):
        row = self.posix_only_row(value)
        from shared_directory_policy import FileSharePathBindings
        if FileSharePathBindings.bound(row):
            binding = self.path_bindings().ensure(row, "NFS")
            self.posix_only_plan(value)
            allowed = nfs_posix_apply_config(row, value, binding["created"]) if binding["created"] else {}
        else:
            allowed = nfs_posix_apply_config(row, value)
        fd, expected = self.posix_only_fd(value)
        try:
            if "ownerUid" in allowed or "ownerGid" in allowed:
                os.fchown(fd, int(allowed.get("ownerUid", -1)), int(allowed.get("ownerGid", -1)))
            if "mode" in allowed:
                os.fchmod(fd, int(str(allowed["mode"]), 8))
            os.fsync(fd)
        finally:
            os.close(fd)

    def verify_posix_only(self, value):
        fd, expected = self.posix_only_fd(value)
        try:
            info = os.fstat(fd)
            if (info.st_uid, info.st_gid, info.st_mode & 0o7777) != (expected["uid"], expected["gid"], expected["mode"]):
                raise Rejected("NFS_POSIX_VERIFICATION_FAILED")
        finally:
            os.close(fd)

    def restore_posix_only(self, payload, snapshot):
        value = payload["candidate"]["nfs export apply"]
        fd, expected = self.posix_only_fd(value)
        try:
            before = snapshot["metadata"].get(expected["path"])
            if before is None:
                raise Rejected("BEFORE_IMAGE_UNAVAILABLE")
            os.fchown(fd, before["uid"], before["gid"])
            os.fchmod(fd, before["mode"])
            checked(["setfacl", "--restore=-"], input=before["acl"].encode())
        finally:
            os.close(fd)

    @staticmethod
    def listener_only(payload):
        value = payload.get("candidate", {}).get("smb share apply", {})
        return value.get("listenerOnly") is True

    def finalize_smb_listeners(self, payload):
        saved = load(self.payload_path, {})
        if self.listener_only(saved):
            SmbListenerProfiles().commit(payload["operationid"])

    def file_paths(self, payload):
        if self.nfs_posix_only(payload):
            return set()
        paths = {Path("/etc/ablestack-storage/network-endpoints.json")}
        for operation in payload["candidate"]:
            if operation.startswith("nfs"):
                paths.update(Path("/etc/ganesha/ablestack-storage").glob("*.conf"))
                paths.update(Path("/etc/exports.d").glob("*ablestack*"))
                paths.update(Path("/etc/ablestack-storage").glob("nfs*.json"))
                paths.add(Path("/etc/ablestack-storage/nfs-local-probes.json"))
                paths.add(Path("/etc/ablestack-storage/desired-state/nfs-export-apply.json"))
                paths.add(Path("/etc/fstab"))
            if operation.startswith("smb"):
                paths.add(Path("/etc/ablestack-storage/desired-state/directory-policy.json"))
                paths.update(Path("/etc/ablestack-storage").glob("smb*.json"))
                paths.update(Path("/etc/ablestack-storage/smb-listeners").glob("*.conf"))
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
        if self.nfs_posix_only(payload):
            row = self.posix_only_row(payload["candidate"]["nfs export apply"])
            return {row["config"]["backingPath"]}, set(), set()
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

    @staticmethod
    def directory_only(payload):
        return set((payload or {}).get("candidate",{})) == {"directory policy apply"}

    def snapshot(self, payload):
        if self.smb_options_only(payload):
            atomic_json(self.snapshot_path, {"smbOptions": self.smb_options_engine().snapshot(payload)})
            return
        if self.directory_only(payload):
            from shared_directory_policy import DirectoryPolicies
            before=DirectoryPolicies().snapshot(payload["candidate"]["directory policy apply"])
            atomic_json(self.snapshot_path,{"directory":before,"metadata":{},"files":{}})
            return
        import base64
        import grp
        import pwd
        paths, users, groups = (set(), set(), set()) if self.listener_only(payload) else self.directories(payload)
        snapshot = {"files": {}, "metadata": {}, "users": {}, "groups": {}, "network": []}
        for path in self.file_paths(payload):
            if path.is_file() and not path.is_symlink():
                stat = path.stat()
                if stat.st_size > 4 * 1024 * 1024:
                    raise Rejected("BEFORE_IMAGE_LIMIT")
                snapshot["files"][str(path)] = {"content": base64.b64encode(path.read_bytes()).decode(),
                    "mode": stat.st_mode & 0o777, "uid": stat.st_uid, "gid": stat.st_gid}
        from shared_directory_policy import FileSharePathBindings
        nested = {}
        for commands in (payload.get("before", {}), payload["candidate"]):
            for row in FileSharePathBindings.rows(commands):
                observed = self.path_bindings().inspect(row)
                if observed["exists"]:
                    nested[observed["physicalpath"]] = {"volumeuuid": observed["volumeuuid"],
                        "canonicalpath": observed["relativepath"], "filesystemuuid": observed["filesystemuuid"],
                        "inode": observed["inode"]}
        snapshot["directoryIdentities"] = nested
        for path in paths:
            item = Path(path)
            if item.is_dir():
                stat = item.stat()
                snapshot["metadata"][path] = {"uid": stat.st_uid, "gid": stat.st_gid,
                    "mode": stat.st_mode & 0o7777,
                    "acl": checked(["getfacl", "-p", path]).decode()}
            else:
                snapshot["metadata"][path] = None
        if "smb share apply" in payload.get("candidate",{}):
            from shared_directory_policy import DirectoryPolicies
            engine=DirectoryPolicies()
            for path in paths:
                policy=engine.policy_for(path)
                if policy:
                    snapshot["directoryIdentities"][path]={k:policy[k] for k in ("volumeuuid","canonicalpath","filesystemuuid","inode")}
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
        if self.listener_only(payload):
            snapshot["smbListeners"] = SmbListenerProfiles().show()
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
        file_commands = any(k in payload["candidate"] for k in ("nfs export apply", "smb share apply"))
        if file_commands and (FileSharePathBindings.rows(payload["candidate"]) or FileSharePathBindings.rows(payload.get("before", {}))):
            self.path_bindings().begin(payload)

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
            # Rollback uses the same runner path. Keep the first structured,
            # already-redacted failure immutable before compensation can replace it.
            original = self.private / "original-runner-result.json"
            if not original.exists(): atomic_json(original, result)
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
        saved = load(self.payload_path, {})
        if self.smb_options_only(saved):
            if load(self.private / "candidate-completed.json") != {"requestHash": public_hash(saved["candidate"])}:
                return "FAILED"
            try:
                self.smb_options_engine().verify(self.smb_options_snapshot())
                return "VERIFIED"
            except Exception:
                return "FAILED"
        payload = load(self.payload_path)
        result = load(self.private / "runner-result.json")
        if self.directory_only(payload):
            if load(self.private/"candidate-completed.json") != {"requestHash":public_hash(payload["candidate"])}:
                return "FAILED"
            try:
                self.verify(payload["candidate"])
                return "VERIFIED"
            except Exception:
                return "FAILED"
        if not self.nfs_posix_only(payload) and (not result or result.get("observationUnknown")):
            return "UNKNOWN"
        if self.nfs_posix_only(payload):
            try:
                self.verify(payload["candidate"])
                return "VERIFIED"
            except Rejected:
                return "FAILED"
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
        if self.smb_options_only(load(self.payload_path, {})):
            self.smb_options_engine().apply(self.smb_options_snapshot(), commands["smb share apply"])
            atomic_json(self.private / "candidate-completed.json", {"requestHash": public_hash(commands)})
            return
        if self.directory_only({"candidate":commands}):
            from shared_directory_policy import DirectoryPolicies
            before=load(self.snapshot_path)["directory"]
            DirectoryPolicies().apply(commands["directory policy apply"],before,
                lambda count:atomic_json(self.private/"directory-progress.json",{"count":count}))
            atomic_json(self.private/"candidate-completed.json",{"requestHash":public_hash(commands)})
            return
        if self.nfs_posix_only({"candidate": commands}):
            self.apply_posix_only(commands["nfs export apply"])
            atomic_json(self.private / "candidate-completed.json", {"requestHash": public_hash(commands)})
            return
        for operation, value in commands.items():
            if operation == "smb share apply" and value.get("listenerOnly"):
                value = dict(value)
                saved = load(self.payload_path)
                value["configurationOperationId"] = saved["operationid"]
                value["listenerBefore"] = saved.get("before", {}).get(operation, {})
                value["listenerOriginalNetwork"] = load(self.snapshot_path, {}).get("network", [])
            self.run(operation, value)
        payload = load(self.payload_path)
        if commands == payload["candidate"]:
            atomic_json(self.private / "candidate-completed.json", {"requestHash": public_hash(commands)})

    def verify(self, commands):
        if self.smb_options_only(load(self.payload_path, {})):
            self.smb_options_engine().verify(self.smb_options_snapshot())
            return
        if self.directory_only({"candidate":commands}):
            from shared_directory_policy import DirectoryPolicies
            DirectoryPolicies().verify(commands["directory policy apply"],load(self.snapshot_path)["directory"])
            return
        if self.nfs_posix_only({"candidate": commands}):
            self.path_bindings().verify(commands)
            self.verify_posix_only(commands["nfs export apply"])
            return
        if not commands:
            return
        self.path_bindings(restoring=(self.private / "path-restore.json").exists()).verify(commands)
        smb = commands.get("smb share apply", {})
        if smb.get("listenerOnly"):
            SmbListenerProfiles().verify(smb)
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
            if operation == "nfs export apply" and NfsIdentityMapping.MODE in value:
                NfsIdentityMapping().verify(value)
            if operation.startswith("smb") and SmbListenerProfiles().manifest.exists():
                SmbListenerProfiles().verify(value)
                if any("inheritOwner" in (row.get("config") or {}) for row in value.get("shares",[])):
                    from shared_directory_policy import verify_smb_inheritance
                    verify_smb_inheritance(value)
                continue
            if operation.startswith("smb") and value.get("shares"):
                checked(["smbcontrol", "smbd", "ping"])
                rows = checked(["ss", "-H", "-lnt"]).decode().splitlines()
                listeners = value.get("listeners") or [{"port": value.get("port") or 445}]
                for item in listeners:
                    if not any(len(row.split()) > 3 and row.split()[3].endswith(":" + str(item.get("port") or 445)) for row in rows):
                        raise Rejected("SMB_LISTENER_NOT_READY")
            if operation.startswith("smb") and any("inheritOwner" in (row.get("config") or {}) for row in value.get("shares",[])):
                from shared_directory_policy import verify_smb_inheritance
                verify_smb_inheritance(value)
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
        if self.smb_options_only(load(self.payload_path, {})):
            self.smb_options_engine().restore(self.smb_options_snapshot())
            return
        if self.is_running():
            raise Rejected("APPLY_STILL_RUNNING")
        snapshot = load(self.snapshot_path)
        payload = load(self.payload_path)
        if not snapshot or not payload:
            raise Rejected("BEFORE_IMAGE_UNAVAILABLE")
        if self.directory_only(payload):
            from shared_directory_policy import DirectoryPolicies
            DirectoryPolicies().restore(snapshot["directory"],load(self.private/"directory-progress.json",{}).get("count",0))
            return
        if self.nfs_posix_only(payload):
            if (self.private / "pathbindings.json").exists():
                atomic_json(self.private / "path-restore.json", {"restoring": True})
                self.path_bindings(restoring=True).rollback()
                if not any(v for v in snapshot["metadata"].values()): return
            self.restore_posix_only(payload, snapshot)
            return
        if self.listener_only(payload):
            SmbListenerProfiles().rollback(payload["operationid"])
            self.restore_files(snapshot)
            self.restore_network(snapshot, payload)
            return
        if "smb share apply" in payload.get("candidate", {}) and SmbListenerProfiles().manifest.exists():
            SmbListenerProfiles().stop_all()
        if (self.private / "pathbindings.json").exists():
            atomic_json(self.private / "path-restore.json", {"restoring": True})
            self.path_bindings(restoring=True).rollback()
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
                nested_roots = [Path((row.get("config") or {})["backingPath"])
                    for row in self.path_bindings().rows(payload["candidate"])]
                if any(Path(name) == root or Path(name).is_relative_to(root) for root in nested_roots):
                    continue  # owned inode-checked journal performs empty-only cleanup
                with contextlib.suppress(FileNotFoundError, OSError):
                    Path(name).rmdir()  # only empty operation-created directories
                continue
            if not Path(name).is_dir():
                raise Rejected("DIRECTORY_IDENTITY_CHANGED")
            identity=snapshot.get("directoryIdentities",{}).get(name)
            if identity:
                from shared_directory_policy import DirectoryPolicies
                engine=DirectoryPolicies()
                mount=engine.managed/identity["volumeuuid"]
                observed=engine.observe({"config":{"volumeMountPath":str(mount),"backingPath":str(mount/identity["canonicalpath"]),
                                                  "filesystemUuid":identity["filesystemuuid"]}})
                if observed.get("inode")!=identity["inode"]:
                    raise Rejected("DIRECTORY_IDENTITY_CHANGED")
                with engine.opened(mount,identity["canonicalpath"],identity["inode"]) as fd:
                    target="/proc/self/fd/"+str(fd)
                    os.fchown(fd,value["uid"],value["gid"])
                    checked(["setfacl","-k",target],pass_fds=(fd,))
                    text="\n".join(line for line in value["acl"].splitlines() if not line.startswith("#"))
                    checked(["setfacl","--set-file=-",target],input=text.encode(),pass_fds=(fd,))
                    os.fchmod(fd,value["mode"])
            else:
                os.chown(name, value["uid"], value["gid"]); os.chmod(name, value["mode"])
                checked(["setfacl", "--restore=-"], input=value["acl"].encode())
        if "smb share apply" in payload.get("candidate",{}):
            from shared_directory_policy import DirectoryPolicies
            engine=DirectoryPolicies()
            engine.refresh_many([p for p in snapshot["metadata"] if engine.policy_for(p)])


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
        if self.smb_options_only(load(self.payload_path, {})):
            self.smb_options_engine().verify(self.smb_options_snapshot(), restored=True)
            return
        payload = load(self.payload_path)
        snapshot = load(self.snapshot_path)
        if self.nfs_posix_only(payload) and (self.private / "pathbindings.json").exists() and not any(v for v in snapshot["metadata"].values()):
            return
        if self.directory_only(payload):
            from shared_directory_policy import DirectoryPolicies
            DirectoryPolicies().verify_restored(snapshot["directory"])
            return
        profiles = SmbListenerProfiles() if self.listener_only(payload) else None
        if profiles is not None and not profiles.manifest.exists():
            profiles.verify_legacy_restored(payload["before"].get("smb share apply", {}), snapshot)
        else:
            self.verify(payload["before"])
        for name, value in snapshot["metadata"].items():
            if value:
                stat = Path(name).stat()
                if (stat.st_uid, stat.st_gid, stat.st_mode & 0o7777) != (value["uid"], value["gid"], value["mode"]):
                    raise Rejected("METADATA_RESTORE_MISMATCH")


AD_DOMAIN_ROOT = Path("/var/lib/ablestack-storage/ad-domain-operations")
AD_DOMAIN_TERMINAL = {"COMMITTED", "ROLLED_BACK", "MEMBERSHIP_FAILED"}


def ad_domain_status(instance, root=AD_DOMAIN_ROOT):
    path = Path(root) / token(instance) / "status.json"
    if not path.exists():
        return {}
    info = path.lstat()
    if path.is_symlink() or info.st_uid != os.geteuid() or info.st_mode & 0o077 or info.st_size > 16384:
        raise Rejected("AD_DOMAIN_STATUS_UNSAFE")
    try:
        value = load(path)
    except (OSError, ValueError) as error:
        raise Rejected("AD_DOMAIN_STATUS_UNSAFE") from error
    if not isinstance(value, dict) or value.get("state") not in AD_DOMAIN_TERMINAL | {"RUNNING", "RECOVERY_REQUIRED"}:
        raise Rejected("AD_DOMAIN_STATUS_UNSAFE")
    token(value.get("operationid"))
    return {key: value[key] for key in ("operationid", "jobid", "phase", "state", "membership", "serviceApply",
            "rollback", "failureCode", "updatedAt") if key in value}


class AdClientConfiguration:
    """Bounded before-images, including absence, inside the AD operation's lease."""
    def __init__(self, paths, optional=(Path("/etc/krb5.conf"),)):
        self.paths = tuple(map(Path, paths))
        self.optional = set(map(Path, optional))

    @staticmethod
    def directory(path):
        try:
            descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        except OSError as error:
            raise Rejected("AD_CLIENT_CONFIGURATION_UNSAFE") from error
        info = os.fstat(descriptor)
        if info.st_uid != os.geteuid() or info.st_mode & 0o022:
            os.close(descriptor)
            raise Rejected("AD_CLIENT_CONFIGURATION_UNSAFE")
        return descriptor

    @staticmethod
    def validate(descriptor):
        import stat
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                or info.st_mode & 0o7022 or info.st_nlink != 1 or info.st_size > 1048576):
            raise Rejected("AD_CLIENT_CONFIGURATION_UNSAFE")
        return info

    @staticmethod
    def identity(parent, path, info):
        current = os.stat(path.name, dir_fd=parent, follow_symlinks=False)
        if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
            raise Rejected("AD_CLIENT_CONFIGURATION_CHANGED")

    def snapshot(self, destination):
        images = {}
        for path in self.paths:
            parent = self.directory(path)
            try:
                try:
                    descriptor = os.open(path.name, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=parent)
                except FileNotFoundError:
                    if path not in self.optional:
                        raise Rejected("AD_CLIENT_CONFIGURATION_UNSAFE")
                    images[str(path)] = {"exists": False}
                    continue
                except OSError as error:
                    raise Rejected("AD_CLIENT_CONFIGURATION_UNSAFE") from error
                with os.fdopen(descriptor, "rb") as stream:
                    info = self.validate(stream.fileno())
                    content = stream.read(1048577)
                    self.identity(parent, path, info)
                    if len(content) > 1048576:
                        raise Rejected("AD_CLIENT_CONFIGURATION_UNSAFE")
                    images[str(path)] = {"exists": True, "content": base64.b64encode(content).decode(),
                                         "mode": info.st_mode & 0o777, "uid": info.st_uid, "gid": info.st_gid}
            finally:
                os.close(parent)
        atomic_json(destination, images)

    def restore(self, source):
        images = load(source)
        if not isinstance(images, dict) or set(images) != set(map(str, self.paths)):
            raise Rejected("AD_CLIENT_SNAPSHOT_INVALID")
        for path in self.paths:
            image = images[str(path)]
            if not isinstance(image, dict) or type(image.get("exists")) is not bool:
                raise Rejected("AD_CLIENT_SNAPSHOT_INVALID")
            parent = self.directory(path)
            try:
                if not image["exists"]:
                    if path not in self.optional:
                        raise Rejected("AD_CLIENT_SNAPSHOT_INVALID")
                    try:
                        descriptor = os.open(path.name, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=parent)
                    except FileNotFoundError:
                        continue
                    with os.fdopen(descriptor, "rb") as stream:
                        info = self.validate(stream.fileno())
                        self.identity(parent, path, info)
                        os.unlink(path.name, dir_fd=parent)
                else:
                    content = base64.b64decode(image["content"], validate=True)
                    if (len(content) > 1048576 or type(image.get("mode")) is not int
                            or image["mode"] & ~0o755 or image.get("uid") != os.geteuid()
                            or type(image.get("gid")) is not int):
                        raise Rejected("AD_CLIENT_SNAPSHOT_INVALID")
                    flags = os.O_RDWR | os.O_NONBLOCK | os.O_NOFOLLOW
                    try:
                        descriptor = os.open(path.name, flags, dir_fd=parent)
                    except FileNotFoundError:
                        descriptor = os.open(path.name, flags | os.O_CREAT | os.O_EXCL, 0o600, dir_fd=parent)
                    with os.fdopen(descriptor, "r+b") as stream:
                        info = self.validate(stream.fileno())
                        self.identity(parent, path, info)
                        if stream.read() != content:
                            stream.seek(0); stream.write(content); stream.truncate()
                        os.fchown(stream.fileno(), image["uid"], image["gid"])
                        os.fchmod(stream.fileno(), image["mode"])
                        stream.flush(); os.fsync(stream.fileno())
                os.fsync(parent)
            finally:
                os.close(parent)


class AdDomainJoin:
    """One guest lease; membership credentials never enter the durable snapshot."""
    def __init__(self, root=AD_DOMAIN_ROOT, adapter_factory=None, configuration_root=ROOT,
                 desired=Path("/etc/ablestack-storage/desired-state/smb-share-apply.json"),
                 client_paths=(Path("/etc/resolv.conf"), Path("/etc/krb5.conf")),
                 optional_client_paths=(Path("/etc/krb5.conf"),)):
        self.root = Path(root)
        self.configuration_root, self.desired = Path(configuration_root), Path(desired)
        self.client_files = AdClientConfiguration(client_paths, optional_client_paths)
        self.adapter_factory = adapter_factory or RuntimeAdapter

    def member(self, value):
        descriptor = os.memfd_create("storage-ad-membership", 0)
        try:
            os.write(descriptor, json.dumps(value).encode())
            os.lseek(descriptor, 0, os.SEEK_SET)
            result = subprocess.run(["/usr/local/bin/ablestack-storagectl", "smb", "domain", "join",
                "/proc/self/fd/" + str(descriptor)], pass_fds=(descriptor,), capture_output=True,
                env={**os.environ, "ABLESTACK_STORAGE_AD_MEMBERSHIP_ONLY": "1"})
            if result.returncode:
                phase = load(Path("/etc/ablestack-storage/smb-domain-error.json"), {}).get("phase", "")
                if phase in {"validate", "testparm", "kinit", "join", "testjoin", "wbinfo"}:
                    raise Rejected("AD_MEMBERSHIP_" + phase.upper() + "_FAILED")
                raise Rejected("AD_MEMBERSHIP_FAILED")
            try:
                return json.loads(result.stdout)
            except (ValueError, TypeError):
                return {}
        finally:
            os.close(descriptor)

    def run(self, value):
        from shared_directory_policy import inheritance_protection_version
        if inheritance_protection_version(minimum=6) != 1:
            raise Rejected("AD_DOMAIN_BASELINE_REQUIRED")
        instance = token(value.get("instanceUuid"))
        operation = token(value.get("operationid"))
        candidate = value.get("smbDesiredState")
        if not isinstance(candidate, dict) or candidate.get("instanceUuid") != instance:
            raise Rejected("INSTANCE_IDENTITY_MISMATCH")
        lock_dir = self.configuration_root / instance
        lock_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        with (lock_dir / "lock").open("a") as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            previous = ad_domain_status(instance, self.root)
            if previous and previous.get("state") not in AD_DOMAIN_TERMINAL:
                raise Rejected("AD_DOMAIN_RECOVERY_REQUIRED")
            profiles = SmbListenerProfiles()
            if profiles.manifest.exists():
                topology = load(profiles.manifest)
                if topology.get("instanceUuid") != instance or topology.get("pending"):
                    raise Rejected("SMB_PROFILE_RECOVERY_REQUIRED")
                for row in topology.get("profiles", {}).values():
                    if not profiles.ready(row, topology["profiles"]):
                        overlap = any(port == row["port"] and (ip == row["listenIp"] or ip == "0.0.0.0" or row["listenIp"] == "0.0.0.0")
                                      for ip, port in profiles.sockets())
                        raise Rejected("SMB_ENDPOINT_IN_USE" if overlap else "SMB_PROFILE_RECOVERY_REQUIRED")
            directory = self.root / instance
            private = directory / operation
            if private.exists():
                raise Rejected("AD_DOMAIN_DUPLICATE_ATTEMPT")
            private.mkdir(parents=True, mode=0o700)
            status_path = directory / "status.json"
            status = {"operationid": operation, "phase": "JOINING", "state": "RUNNING",
                      "membership": "JOINING", "serviceApply": "WAITING", "rollback": "NOT_NEEDED"}
            if value.get("jobid"): status["jobid"] = token(value["jobid"])
            def record(**patch):
                status.update(patch)
                status["updatedAt"] = time.time()
                atomic_json(status_path, status)
            before = load(self.desired, value.get("smbBeforeState"))
            if not isinstance(before, dict) or before.get("instanceUuid") != instance:
                raise Rejected("AD_SMB_BEFORE_IMAGE_UNAVAILABLE")
            from configuration_preflight import inspect
            report = inspect({"instanceUuid": instance, "protocol": "SMB", "revision": 0,
                              "ports": [v["port"] for v in candidate.get("listeners", [])],
                              "drainrequired": True, "resourcecount": len(candidate.get("shares", []))})
            if report["status"] != "READY":
                raise Rejected(next((v["code"] for v in report["checks"] if v["blocking"]), "AD_ADMISSION_UNAVAILABLE"))
            client_snapshot = private / "client-before.json"
            self.client_files.snapshot(client_snapshot)
            record()
            joined = False
            adapter = self.adapter_factory(private)
            try:
                member = self.member(value)
                joined = True
                record(phase="APPLYING_SMB", membership="JOINED", serviceApply="APPLYING", reusedMembership=bool(member and member.get("alreadyJoined")))
                payload = {"instanceUuid": instance, "operationid": operation,
                           "before": {"smb share apply": before},
                           "candidate": {"smb share apply": candidate}}
                adapter.preflight(payload)
                adapter.snapshot(payload)
                adapter.apply(payload["candidate"])
                adapter.verify(payload["candidate"])
                checked(["net", "ads", "testjoin"])
                checked(["wbinfo", "-t"])
                record(phase="COMPLETED", state="COMMITTED", serviceApply="VERIFIED")
                return dict(status, success=True)
            except Exception as error:
                code = getattr(error, "code", "AD_SMB_APPLY_FAILED")
                if joined:
                    result = load(private / "runner-result.json", {}).get("result", {})
                    detail = result.get("code", "") if isinstance(result, dict) else ""
                    if isinstance(detail, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,79}", detail):
                        code = detail
                    record(phase="ROLLING_BACK_SMB", serviceApply="FAILED", failureCode=code)
                    try:
                        if adapter.snapshot_path.exists():
                            adapter.restore()
                            adapter.verify_restored()
                            rollback = "SUCCEEDED"
                        else:
                            rollback = "NOT_NEEDED"
                        record(phase="COMPLETED", state="ROLLED_BACK", rollback=rollback)
                    except Exception:
                        record(phase="RECOVERY_REQUIRED", state="RECOVERY_REQUIRED", rollback="FAILED")
                else:
                    if code in {"AD_MEMBERSHIP_JOIN_FAILED", "AD_MEMBERSHIP_TESTJOIN_FAILED", "AD_MEMBERSHIP_WBINFO_FAILED"}:
                        record(phase="RECOVERY_REQUIRED", state="RECOVERY_REQUIRED", membership="UNKNOWN",
                               serviceApply="NOT_APPLIED", failureCode=code)
                    else:
                        try:
                            self.client_files.restore(client_snapshot)
                            record(phase="COMPLETED", state="MEMBERSHIP_FAILED", membership="FAILED",
                                   serviceApply="NOT_APPLIED", rollback="SUCCEEDED", failureCode=code)
                        except Exception:
                            record(phase="RECOVERY_REQUIRED", state="RECOVERY_REQUIRED", membership="FAILED",
                                   serviceApply="NOT_APPLIED", rollback="FAILED", failureCode=code)
                return dict(status, success=False)


def run_child(private, operation, descriptor):
    private = Path(private)
    if operation not in OPERATIONS:
        raise Rejected("UNSUPPORTED_OPERATION")
    descriptor = int(descriptor)
    argv = ["/usr/local/bin/ablestack-storagectl"] + operation.split() + [f"/proc/self/fd/{descriptor}"]
    try:
        process = subprocess.Popen(argv, pass_fds=(descriptor,), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   env={**os.environ, "ABLESTACK_STORAGE_CONFIGURATION_TRANSACTION": "1",
                                        "ABLESTACK_STORAGE_PATH_JOURNAL": str(private / "pathbindings.json"),
                                        "ABLESTACK_STORAGE_PATH_RESTORE": "1" if (private / "path-restore.json").exists() else "0"})
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
    if argv == ["smb-listeners", "show"]:
        print(json.dumps(SmbListenerProfiles().show()))
        return 0
    if len(argv) == 3 and argv[0] == "smb-listeners" and argv[1] in ("apply", "full"):
        source = Path(argv[2])
        if source.stat().st_size > 4 * 1024 * 1024:
            raise Rejected("PAYLOAD_LIMIT")
        value = load(source)
        profiles = SmbListenerProfiles()
        if argv[1] == "full":
            profiles.refresh(value)
        elif os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_TRANSACTION") == "1":
            before = value.pop("listenerBefore", {})
            operation = value.pop("configurationOperationId", None)
            original = value.pop("listenerOriginalNetwork", None)
            profiles.stage(value, before, operation, original)
        else:
            profiles.boot(value)
        print(json.dumps({"success": True, "runtimeReady": True}))
        return 0
    if len(argv) == 3 and argv[:2] == ["ad-domain", "show"]:
        request = load(Path(argv[2]))
        print(json.dumps(ad_domain_status(request.get("instanceUuid"))))
        return 0
    if len(argv) == 3 and argv[:2] == ["ad-domain", "join"]:
        try:
            result = AdDomainJoin().run(load(Path(argv[2])))
        except (Rejected, BlockingIOError) as error:
            result = {"success": False, "membership": "UNCHANGED", "serviceApply": "NOT_APPLIED",
                      "phase": "VALIDATING", "state": "REJECTED", "rollback": "NOT_NEEDED",
                      "failureCode": getattr(error, "code", "CONFIGURATION_BUSY")}
        print(json.dumps(result))
        if not result["success"]:
            print(result.get("failureCode", "AD_SMB_APPLY_FAILED"), file=sys.stderr)
        return 0 if result["success"] else 1
    if argv[:2] == ["capability", "show"]:
        try:
            from shared_directory_policy import SMB_PARENT_INHERITANCE_VERSION, inheritance_protection_version
            inheritance_version=SMB_PARENT_INHERITANCE_VERSION
            inheritance_protection=inheritance_protection_version()
            ad_domain_protection=inheritance_protection_version(minimum=6)
        except ImportError:
            inheritance_version=inheritance_protection=ad_domain_protection=0
        except TypeError:
            ad_domain_protection=0
        print(json.dumps({"configurationTransactionVersion": VERSION, "configurationPreflightVersion": 1,
                          "configurationObservationVersion": 1, "smbListenerProfilesVersion": SmbListenerProfiles.VERSION, "nfsPosixIntentVersion": NFS_POSIX_VERSION, "nfsDeferredPosixVersion": 1,
                          "nestedFileSharePathVersion": 1, "nestedFileSharePathProtectionVersion": int(__import__("shared_directory_policy").nested_path_protected()), "smbCreationPolicyVersion": 1, "smbCreationPolicyProtectionVersion": int(__import__("shared_directory_policy").smb_creation_protected()), "sharedDirectoryPolicyVersion": 1, "adDomainTransactionVersion": ad_domain_protection, "smbParentInheritanceVersion": inheritance_version, "smbParentInheritanceProtectionVersion": inheritance_protection, "nfsIdentityMappingVersion": 1, "nfsidentitymapping": NfsIdentityMapping().inspect()}))
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
