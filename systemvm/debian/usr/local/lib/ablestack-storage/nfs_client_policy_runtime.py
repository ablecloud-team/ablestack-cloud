# Licensed under the Apache License, Version 2.0. See NOTICE.
# http://www.apache.org/licenses/LICENSE-2.0
"""Process-bound NFS CLIENT-only updates. No daemon start/stop or DATA mutation."""
import base64
import copy
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import tempfile
import time
from nfs_ganesha_render import payload_exports, render_configurations, endpoint_key

OP = "nfs export apply"
CLIENT = {"readOnly", "rootSquash", "allSquash", "anonUid", "anonGid"}
ACL_CONFIG = CLIENT | {"sync", "secure", "endpointMode", "listenerGroupPorts",
                       "protocolMode", "inheritPolicy", "policyMode"}
META = {"configurationOperationId", "posixIntent", "lastApply"}


class PolicyError(Exception):
    def __init__(self, code, port=None):
        super().__init__(code)
        self.code = code
        self.detail = {"code": code, "stage": "NFS_CLIENT_POLICY"}
        if port is not None:
            self.detail["port"] = int(port)


def canonical(value, policy=False):
    """Comparison projection only: export and CLIENT list order is semantic."""
    value = copy.deepcopy(value)
    for key in META:
        value.pop(key, None)
    for row in value.get("exports", []):
        if row.get("state") == "Updating":
            row["state"] = "Ready"
        if policy:
            row.pop("acls", None)
            for key in CLIENT:
                row.get("config", {}).pop(key, None)
        else:
            for rule in row.get("acls", []):
                if rule.get("state") == "Updating":
                    rule["state"] = "Ready"
    return value


def stored_desired(value):
    """Match legacy persistence: remove transport fields, keep ordered policy data."""
    value = copy.deepcopy(value)
    value.pop("configurationOperationId", None)
    value.pop("posixIntent", None)
    return value


def policy_only(payload):
    before, candidate = payload.get("before", {}), payload.get("candidate", {})
    if set(before) != {OP} or set(candidate) != {OP}:
        return False
    old, new = before[OP], candidate[OP]
    if not old.get("enabled", True) or not new.get("enabled", True) or not old.get("exports"):
        return False
    intent = new.get("posixIntent", {})
    if new.get("posixOnly") or intent.get("create") or intent.get("fields"):
        return False
    for value in (old, new):
        for row in value.get("exports", []):
            for rule in row.get("acls", []):
                if (rule.get("principalType") not in ("IP", "CIDR") or
                        set(rule.get("config") or {}) - ACL_CONFIG):
                    return False
    try:
        return canonical(old, True) == canonical(new, True)
    except (KeyError, TypeError, ValueError):
        return False


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def read_file(path, optional=False):
    path = Path(path)
    if path.resolve() != path.absolute():
        raise PolicyError("NFS_CLIENT_FILE_IDENTITY_CHANGED")
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        if optional:
            return None
        raise PolicyError("NFS_CLIENT_BEFORE_IMAGE_UNAVAILABLE")
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022 or info.st_size > 4 * 1024**2:
            raise PolicyError("NFS_CLIENT_FILE_IDENTITY_CHANGED")
        raw = b""
        while len(raw) <= 4 * 1024**2:
            part = os.read(fd, 65536)
            if not part:
                break
            raw += part
        if len(raw) != info.st_size:
            raise PolicyError("NFS_CLIENT_FILE_IDENTITY_CHANGED")
        return {"content": base64.b64encode(raw).decode(), "hash": digest(raw),
                "mode": stat.S_IMODE(info.st_mode)}
    finally:
        os.close(fd)


def contents(image):
    return base64.b64decode(image["content"])


def write_file(path, raw, mode=0o600):
    path = Path(path)
    if path.parent.resolve() != path.parent.absolute():
        raise PolicyError("NFS_CLIENT_FILE_IDENTITY_CHANGED")
    info = path.parent.stat()
    if info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise PolicyError("NFS_CLIENT_FILE_IDENTITY_CHANGED")
    fd, temporary = tempfile.mkstemp(prefix=".nfs-client-", dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class ClientPolicies:
    def __init__(self, private=None, config=Path("/etc/ganesha/ablestack-storage"),
                 run=Path("/run/ablestack-storage/ganesha"), settings=Path("/etc/ablestack-storage")):
        self.private = Path(private) if private is not None else None
        self.config, self.run, self.settings = Path(config), Path(run), Path(settings)
        self.desired = self.settings / "desired-state/nfs-export-apply.json"
        self.probes = self.settings / "nfs-local-probes.json"
        self.journal = self.private / "nfs-client-policy.json" if self.private else None

    def save(self, value):
        from configuration_transaction import atomic_json
        atomic_json(self.journal, value)
        stat_fields = Path("/proc/self/stat").read_text().rsplit(")", 1)[1].split()
        from configuration_transaction import load
        prior = load(self.private / "runner-progress.json", {})
        started = prior.get("startedAt", time.time()) if prior.get("child", {}).get("pid") == os.getpid() else time.time()
        atomic_json(self.private / "runner-progress.json", {"operation": OP, "phase": value["phase"],
            "heartbeatAt": time.time(), "startedAt": started, "softDeadlineAt": started + 300,
            "child": {"pid": os.getpid(), "start": stat_fields[19],
                      "boot": Path("/proc/sys/kernel/random/boot_id").read_text().strip()},
            "cancellationSupported": False})

    def state(self):
        from configuration_transaction import load
        return load(self.journal, {})

    def rendered(self, value):
        from configuration_transaction import NfsIdentityMapping
        return render_configurations(payload_exports(value), NfsIdentityMapping.lines(value))

    def directories(self, value):
        from shared_directory_policy import DirectoryPolicies, FileSharePathBindings
        engine = DirectoryPolicies()
        bindings = FileSharePathBindings()
        result = {}
        cache = {}
        for row in value.get("exports", []):
            if row.get("state") in ("Disabled", "Destroyed", "Error"):
                continue
            config = row.get("config") or {}
            key = (config.get("volumeMountPath"), config.get("backingPath"), config.get("filesystemUuid"))
            if key not in cache:
                cache[key] = engine.observe(row)
            observed = cache[key]
            if observed.get("status") != "OBSERVED":
                raise PolicyError("NFS_CLIENT_DIRECTORY_UNCONFIRMED")
            if bindings.bound(row):
                bound = bindings.inspect(row)
                if not bound.get("exists") or bound.get("inode") != observed["inode"]:
                    raise PolicyError("NFS_CLIENT_DIRECTORY_IDENTITY_CHANGED")
            path = Path(row["path"])
            if path.is_symlink() or not os.path.samefile(path, observed["path"]):
                raise PolicyError("NFS_CLIENT_DIRECTORY_IDENTITY_CHANGED")
            result[row["uuid"]] = {k: observed[k] for k in
                ("fingerprint", "inode", "filesystemuuid", "volumeuuid", "canonicalpath", "acl")}
        return result

    def process(self, key, path, port):
        from configuration_transaction import NfsIdentityMapping, Rejected
        image = read_file(self.run / (key + ".pid"))
        try:
            pid = int(contents(image))
            result = NfsIdentityMapping.process(pid)
            args = (Path("/proc") / str(pid) / "cmdline").read_bytes().decode().split("\0")
            if Path(args[args.index("-f") + 1]).resolve() != path:
                raise ValueError()
            fds = set()
            for fd in (Path("/proc") / str(pid) / "fd").iterdir():
                try:
                    value = os.readlink(fd)
                    if value.startswith("socket:["):
                        fds.add(value[8:-1])
                except OSError:
                    continue
            listening = False
            for table in ("tcp", "tcp6"):
                for line in (Path("/proc") / str(pid) / "net" / table).read_text().splitlines()[1:]:
                    fields = line.split()
                    if fields[3] == "0A" and int(fields[1].split(":")[1], 16) == int(port) and fields[9] in fds:
                        listening = True
            if not listening:
                raise ValueError()
            return result
        except (OSError, ValueError, IndexError, KeyError, Rejected):
            raise PolicyError("NFS_CLIENT_RUNTIME_IDENTITY_CHANGED", port)

    def require_process(self, row):
        if self.process(row["key"], Path(row["config"]), row["port"]) != row["process"]:
            raise PolicyError("NFS_CLIENT_RUNTIME_IDENTITY_CHANGED", row["port"])

    def snapshot(self, payload):
        if not policy_only(payload):
            raise PolicyError("NFS_CLIENT_POLICY_NOT_ONLY")
        from configuration_transaction import token
        from shared_directory_policy import FileSharePathBindings
        old, new = copy.deepcopy(payload["before"][OP]), copy.deepcopy(payload["candidate"][OP])
        FileSharePathBindings().enrich_desired(old)
        FileSharePathBindings().enrich_desired(new)
        current = json.loads(contents(read_file(self.desired)))
        if canonical(current) != canonical(old):
            raise PolicyError("NFS_CLIENT_DESIRED_CHANGED")
        before, after = self.rendered(old), self.rendered(new)
        if set(before) != set(after) or not before:
            raise PolicyError("NFS_CLIENT_POLICY_STRUCTURE_CHANGED")
        snapshot = {"operationid": token(payload["operationid"]), "instanceUuid": token(payload["instanceUuid"]),
                    "rows": [], "files": {}, "directories": self.directories(old), "value": new}
        for (ip, port), text in before.items():
            key = endpoint_key(ip, port)
            path = self.config / (key + ".conf")
            image = read_file(path)
            if contents(image) != text.encode():
                raise PolicyError("NFS_CLIENT_CONFIG_CHANGED", port)
            snapshot["rows"].append({"key": key, "port": port, "config": str(path),
                "process": self.process(key, path, port), "before": image,
                "after": after[(ip, port)], "changed": text != after[(ip, port)]})
        for path in (self.desired, self.probes):
            snapshot["files"][str(path)] = read_file(path, optional=True)
        if len(json.dumps(snapshot).encode()) > 8 * 1024**2:
            raise PolicyError("NFS_CLIENT_BEFORE_IMAGE_LIMIT")
        return snapshot

    def reload(self, row, text, progress):
        self.require_process(row)
        path = Path(row["config"])
        log = self.run / (row["key"] + ".log")
        info = log.stat()
        if log.is_symlink() or not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid():
            raise PolicyError("NFS_CLIENT_LOG_UNCONFIRMED", row["port"])
        progress.update(phase="WRITING", configHash=digest(text.encode()))
        self.save(progress)
        write_file(path, text.encode(), row["before"]["mode"])
        self.require_process(row)
        progress.update(phase="SIGNALLING", logInode=info.st_ino, logOffset=info.st_size)
        self.save(progress)  # Durable intent before the signal; never infer "not started".
        try:
            fd = os.pidfd_open(row["process"]["pid"])
            try:
                self.require_process(row)
                signal.pidfd_send_signal(fd, signal.SIGHUP)
            finally:
                os.close(fd)
        except OSError:
            raise PolicyError("NFS_CLIENT_RUNTIME_IDENTITY_CHANGED", row["port"])
        deadline, changed_at, size = time.monotonic() + 8, time.monotonic(), info.st_size
        while time.monotonic() < deadline:
            self.require_process(row)
            now = log.stat()
            if now.st_ino != info.st_ino or now.st_size < info.st_size or now.st_size - info.st_size > 256 * 1024:
                raise PolicyError("NFS_CLIENT_RELOAD_UNCONFIRMED", row["port"])
            if now.st_size != size:
                size, changed_at = now.st_size, time.monotonic()
            with log.open("rb") as stream:
                stream.seek(info.st_size)
                lines = stream.read(256 * 1024).decode(errors="replace").splitlines()
            rejected = any(("Error while parsing" in line or
                (":CONFIG :" in line and any(level in line for level in (":CRIT :", ":FATAL :", ":MAJ :", ":WARN :"))
                 and "A protocol is specified for a CLIENT block that is not enabled" not in line)) for line in lines)
            if rejected:
                raise PolicyError("NFS_CLIENT_RELOAD_REJECTED", row["port"])
            if any("Reread exports complete" in line for line in lines) and time.monotonic() - changed_at >= .15:
                if read_file(path)["hash"] != progress["configHash"]:
                    raise PolicyError("NFS_CLIENT_CONFIG_CHANGED", row["port"])
                return
            time.sleep(.05)
        raise PolicyError("NFS_CLIENT_RELOAD_UNCONFIRMED", row["port"])

    def observe(self, snapshot):
        from boot_reconcile import Runtime, rendered_probe_plan, probe_plan
        exports = payload_exports(snapshot["value"])
        runtime = [{"key": r["key"], "listenIp": ip, "port": port, "pid": r["process"]["pid"]}
                   for (ip, port), r in zip(sorted(exports), snapshot["rows"])]
        plan = {"bootProbePlan": rendered_probe_plan(runtime, exports)}
        tasks, external = probe_plan(plan)
        result = {}
        for row in external:
            result[(row["exportUuid"], row["port"])] = {"status": "EXTERNAL_CLIENT_REQUIRED",
                "reason": "LOCAL_ADDRESS_EXCLUDED_BY_ACL"}
        def check(task):
            return task, Runtime().probe(task, self.private, 3)
        # Same bounded fan-out as the existing boot observer; join every probe
        # before reporting a failure so no sibling mount is left running.
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            checked = list(pool.map(check, tasks))
        for task, value in checked:
            if value.get("state") != "READY" or value.get("cleanup") != "COMPLETE":
                raise PolicyError("NFS_CLIENT_VISIBILITY_FAILED", task["port"])
            result[(task["exportUuid"], task["port"])] = {"status": "MOUNT_VERIFIED"}
        by_port = {r["port"]: r for r in snapshot["rows"]}
        observations = {}
        for (share, port), value in result.items():
            row = by_port[port]
            observations.setdefault(share, []).append({**value, "port": port, "observedAt": time.time(),
                "configurationState": "APPLIED", "applyMethod": "RELOAD",
                "operationid": snapshot["operationid"], "process": row["process"],
                "bootId": row["process"]["boot"], "runtimeConfigHash": digest(row["after"].encode())})
        return observations

    def apply(self, snapshot):
        progress = self.state()
        if progress.get("phase") == "APPLIED":
            self.verify(snapshot)
            return
        if progress:
            raise PolicyError("NFS_CLIENT_RELOAD_UNCONFIRMED")
        if self.directories(snapshot["value"]) != snapshot["directories"]:
            raise PolicyError("NFS_CLIENT_DIRECTORY_IDENTITY_CHANGED")
        progress = {"operationid": snapshot["operationid"], "phase": "PREPARED", "touched": []}
        self.save(progress)
        for row in snapshot["rows"]:
            self.require_process(row)
            if read_file(row["config"])["hash"] != row["before"]["hash"]:
                raise PolicyError("NFS_CLIENT_CONFIG_CHANGED", row["port"])
            if row["changed"]:
                progress["touched"].append(row["key"])
                self.reload(row, row["after"], progress)
        progress.update(phase="VERIFYING")
        self.save(progress)
        observations = self.observe(snapshot)
        desired = stored_desired(snapshot["value"])
        write_file(self.desired, json.dumps(desired, sort_keys=True).encode())
        write_file(self.probes, json.dumps(observations, sort_keys=True).encode())
        progress.update(phase="APPLIED", endpoints=[{"port": r["port"], "process": r["process"],
            "configHash": digest(r["after"].encode()), "changed": r["changed"]} for r in snapshot["rows"]])
        self.save(progress)

    def verify(self, snapshot, restored=False):
        state = self.state()
        expected = "RESTORED" if restored else "APPLIED"
        if state.get("operationid") != snapshot["operationid"] or state.get("phase") != expected:
            raise PolicyError("NFS_CLIENT_RECEIPT_UNCONFIRMED")
        for row in snapshot["rows"]:
            self.require_process(row)
            wanted = row["before"]["hash"] if restored else digest(row["after"].encode())
            if read_file(row["config"])["hash"] != wanted:
                raise PolicyError("NFS_CLIENT_CONFIG_CHANGED", row["port"])
        if self.directories(snapshot["value"]) != snapshot["directories"]:
            raise PolicyError("NFS_CLIENT_DIRECTORY_IDENTITY_CHANGED")
        if not restored:
            desired = json.loads(contents(read_file(self.desired)))
            if desired != stored_desired(snapshot["value"]):
                raise PolicyError("NFS_CLIENT_DESIRED_CHANGED")

    def restore(self, snapshot):
        progress = self.state() or {"operationid": snapshot["operationid"], "touched": []}
        if progress.get("operationid") != snapshot["operationid"]:
            raise PolicyError("NFS_CLIENT_RECEIPT_UNCONFIRMED")
        if progress.get("phase") == "RESTORED":
            self.verify(snapshot, True)
            return
        touched = set(progress.get("touched", []))
        for row in snapshot["rows"]:
            if row["key"] not in touched:
                self.require_process(row)
                continue
            self.require_process(row)
            current = read_file(row["config"])["hash"]
            if current not in (row["before"]["hash"], digest(row["after"].encode())):
                raise PolicyError("NFS_CLIENT_CONFIG_CHANGED", row["port"])
            self.reload(row, contents(row["before"]).decode(), progress)
        for path, image in snapshot["files"].items():
            if image is None:
                Path(path).unlink(missing_ok=True)
            else:
                write_file(path, contents(image), image["mode"])
        progress.update(phase="RESTORED")
        self.save(progress)
        self.verify(snapshot, True)


def probe_record(endpoint, result, operation=None):
    """Attach same-boot/process/config identity after the existing normal CLI probe."""
    from configuration_transaction import NfsIdentityMapping
    key = endpoint["key"]
    path = Path("/etc/ganesha/ablestack-storage") / (key + ".conf")
    process = NfsIdentityMapping.process(endpoint["pid"])
    return {"port": endpoint["port"], "status": result.get("status", "FAILED"),
            "reason": result.get("reason"), "observedAt": time.time(), "process": process,
            "bootId": process["boot"], "runtimeConfigHash": read_file(path)["hash"],
            "configurationState": "APPLIED", "applyMethod": "APPLY", "operationid": operation}


def observations(boot_status, settings=Path("/etc/ablestack-storage"),
                 config=Path("/etc/ganesha/ablestack-storage"), run=Path("/run/ablestack-storage/ganesha")):
    """Read-only. Old observations are not fresh success; reuse the existing boot report."""
    from configuration_transaction import load
    settings, config, run = Path(settings), Path(config), Path(run)
    try:
        image = read_file(settings / "nfs-local-probes.json", optional=True)
        saved = json.loads(contents(image)) if image else {}
        if not isinstance(saved, dict): saved = {}
    except (OSError, ValueError, PolicyError):
        saved = {}
    result, identities = {}, {}
    engine = ClientPolicies(config=config, run=run, settings=settings)
    for share, rows in saved.items():
        if not isinstance(rows, list): continue
        for row in rows:
            if not isinstance(row, dict) or not isinstance(row.get("port"), int): continue
            port = row["port"]
            try:
                if port not in identities:
                    key = endpoint_key("0.0.0.0", port)
                    path = config / (key + ".conf")
                    identities[port] = (engine.process(key, path, port), read_file(path)["hash"])
                process, hashed = identities[port]
                if process != row.get("process") or hashed != row.get("runtimeConfigHash"):
                    raise ValueError()
                value = row
            except (OSError, ValueError, PolicyError):
                value = {**row, "status": "UNKNOWN", "reason": "NFS_CLIENT_OBSERVATION_STALE",
                         "previousStatus": row.get("status"), "configurationState": "UNKNOWN"}
            result.setdefault(share, []).append(value)
    try:
        desired = load(settings / "desired-state/nfs-export-apply.json", {})
        for (_, port), shares in payload_exports(desired).items():
            for share in shares:
                rows = result.setdefault(share["uuid"], [])
                if not any(r["port"] == port for r in rows):
                    rows.append({"port": port, "status": "UNKNOWN", "configurationState": "UNKNOWN",
                                 "reason": "NFS_CLIENT_OBSERVATION_UNAVAILABLE"})
    except (OSError, ValueError, KeyError, TypeError):
        pass
    if boot_status.get("state") == "READY":
        grouped = {}
        for row in boot_status.get("exports", []):
            grouped.setdefault((row["exportUuid"], row["port"]), []).append(row)
        for (share, port), rows in grouped.items():
            if any(r.get("state") not in ("READY", "EXTERNAL_CLIENT_REQUIRED") for r in rows):
                continue
            current = next((r for r in result.get(share, []) if r["port"] == port), None)
            if current and current.get("status") != "UNKNOWN":
                continue
            external = any(r.get("state") == "EXTERNAL_CLIENT_REQUIRED" for r in rows)
            value = {"port": port, "status": "EXTERNAL_CLIENT_REQUIRED" if external else "MOUNT_VERIFIED",
                     "reason": "LOCAL_ADDRESS_EXCLUDED_BY_ACL" if external else None,
                     "observedAt": boot_status.get("updatedAt"), "bootId": boot_status["bootId"],
                     "configurationState": "APPLIED", "applyMethod": "BOOT_OBSERVATION"}
            result[share] = [r for r in result.get(share, []) if r["port"] != port] + [value]
    return result
