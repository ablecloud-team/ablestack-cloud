# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Read-only admission observations. Never starts, resumes or stops a service."""
import hashlib
from itertools import chain
import json
import os
from pathlib import Path
import stat
import subprocess
import time

MIB = 1024 * 1024
DEFAULT_PORTS = {"NFS": 2049, "SMB": 445, "ISCSI": 3260, "NVME_OF": 4420}

def fingerprint(intent):
    material = {k: intent.get(k) for k in ("instanceUuid", "revision", "protocol", "action", "resourceids", "resourcesdigest", "resourcecount", "drainrequired", "recursive")}
    if intent.get("protocol") == "NFS" and intent.get("posixchanges"):
        material.update(posixchanges=intent["posixchanges"], posixfingerprint=intent.get("posixfingerprint"))
    if intent.get("smbinheritance"):
        material.update(smbinheritance=intent["smbinheritance"], inheritancefingerprint=intent.get("inheritancefingerprint"))
    return hashlib.sha256(json.dumps(material, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

def _read_json(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_size > MIB:
        raise ValueError("UNSAFE_OBSERVATION")
    return json.loads(path.read_text())

def available_memory(meminfo=Path("/proc/meminfo"), groups=Path("/proc/self/cgroup"),
                     v2=Path("/sys/fs/cgroup"), v1=Path("/sys/fs/cgroup/memory")):
    mem = dict(line.split(":", 1) for line in meminfo.read_text().splitlines())
    available = int(mem["MemAvailable"].split()[0]) * 1024
    locations = [(v2, "memory.max", "memory.current"), (v1, "memory.limit_in_bytes", "memory.usage_in_bytes")]
    for line in groups.read_text().splitlines():
        _, controllers, raw = line.split(":", 2)
        if ".." in Path(raw).parts:
            raise ValueError("INVALID_CGROUP")
        if controllers == "":
            base, names = v2, ("memory.max", "memory.current")
        elif "memory" in controllers.split(","):
            base, names = v1, ("memory.limit_in_bytes", "memory.usage_in_bytes")
        else:
            continue
        current = base / raw.lstrip("/")
        while current != base and current.is_relative_to(base):
            locations.append((current, *names))
            current = current.parent
    for base, limit_name, usage_name in locations:
        try:
            raw = (base / limit_name).read_text().strip()
            if raw != "max":
                limit = int(raw)
                if 0 < limit < (1 << 60):
                    available = min(available, max(0, limit - int((base / usage_name).read_text())))
        except FileNotFoundError:
            pass
    return available

def sample(intent):
    available = available_memory()
    filesystems = []
    for name in ("/var/lib/ablestack-storage", "/etc/ablestack-storage", "/tmp"):
        path = Path(name)
        while not path.exists():
            path = path.parent
        device, fs = path.stat().st_dev, os.statvfs(path)
        if any(v["device"] == device for v in filesystems):
            continue
        filesystems.append({"device": device, "freebytes": fs.f_bavail * fs.f_frsize,
                            "freeinodes": fs.f_favail, "readonly": bool(fs.f_flag & os.ST_RDONLY)})
    ports = {int(v) for v in intent.get("ports", [])} or {DEFAULT_PORTS[intent["protocol"]]}
    if any(v < 1 or v > 65535 for v in ports):
        raise ValueError("INVALID_PORT")
    result = subprocess.run(["ss", "-H", "-tn", "state", "established"], capture_output=True, text=True, timeout=5)
    if result.returncode:
        raise ValueError("SESSION_OBSERVATION_UNAVAILABLE")
    connections = sum(1 for line in result.stdout.splitlines()
                      if len(line.split()) > 3 and line.split()[2].rsplit(":", 1)[-1].isdigit()
                      and int(line.split()[2].rsplit(":", 1)[-1]) in ports)
    busy = []
    selected = set(intent.get("volumeuuids", []))
    for path in Path("/var/lib/ablestack-storage/volume-preparation").glob("*.json"):
        value = _read_json(path)
        if value.get("status") == "RUNNING" or (
            value.get("volumeUuid") in selected and value.get("status") in ("RECOVERY_REQUIRED", "OBSERVATION_PENDING")
        ):
            # A queued or interrupted worker is not proof of completed preparation.
            busy.append(value.get("volumeUuid"))
    runtime_busy = False
    if intent.get("instanceUuid"):
        from configuration_transaction import ad_domain_status, AD_DOMAIN_TERMINAL
        ad = ad_domain_status(intent["instanceUuid"])
        runtime_busy = bool(ad and ad.get("state") not in AD_DOMAIN_TERMINAL)
    for path in Path("/var/lib/ablestack-storage/runtime-updates").glob("*/state.json"):
        state = _read_json(path)
        if state.get("phase") in ("ACTIVATING", "ROLLING_BACK"):
            runtime_busy = True
    before_bytes = 0
    for path in chain(Path("/etc/ablestack-storage").glob("*.json"), Path("/etc/ganesha/ablestack-storage").glob("*.conf"), [Path("/etc/samba/smb.conf")]):
        if path.is_file():
            size = path.stat().st_size
            if size > 4 * MIB:
                raise ValueError("BEFORE_IMAGE_LIMIT")
            before_bytes += size
            if before_bytes > 8 * MIB:
                raise ValueError("BEFORE_IMAGE_LIMIT")
    return {"memoryavailablebytes": available, "filesystems": filesystems, "load1": os.getloadavg()[0],
            "cpucount": os.cpu_count() or 1, "connections": connections, "busyvolumes": busy,
            "runtimebusy": runtime_busy, "snapshotbytes": min(8 * MIB, 2 * before_bytes), "observedat": time.time()}

def evaluate(intent, measured):
    now = measured.get("observedat", time.time())
    request_bytes = min(4 * MIB, int(intent.get("requestbytes", 4096)))
    snapshot = measured["snapshotbytes"]
    memory_need = 208 * MIB + 8 * (request_bytes + snapshot)
    disk_need = 80 * MIB + 2 * (request_bytes + snapshot)
    checks = []
    def check(code, blocked, **values):
        checks.append({"code": code, "blocking": bool(blocked), "severity": "ERROR" if blocked else "INFO", **values})
    check("MEMORY_LOW" if measured["memoryavailablebytes"] < memory_need else "MEMORY_AVAILABLE", measured["memoryavailablebytes"] < memory_need,
          availablebytes=measured["memoryavailablebytes"], requiredbytes=memory_need)
    for fs in measured["filesystems"]:
        check("FILESYSTEM_READONLY" if fs["readonly"] else "SPACE_LOW" if fs["freebytes"] < disk_need or fs["freeinodes"] < 64 else "FILESYSTEM_SPACE", fs["readonly"] or fs["freebytes"] < disk_need or fs["freeinodes"] < 64,
              availablebytes=fs["freebytes"], requiredbytes=disk_need, readonly=fs["readonly"], freeinodes=fs["freeinodes"])
    check("VOLUME_PREPARATION_ACTIVE", bool(measured["busyvolumes"]), count=len(measured["busyvolumes"]))
    check("RUNTIME_WRITER_ACTIVE", measured.get("runtimebusy", False))
    check("DRAIN_REQUIRED", intent.get("drainrequired", False) and measured["connections"] > 0,
          connections=measured["connections"])
    if measured["load1"] / measured["cpucount"] > 2:
        checks.append({"code": "LOAD_HIGH", "severity": "WARNING", "blocking": False})
    recursive = bool(intent.get("recursive"))
    return {"status": "BLOCKED" if any(c["blocking"] for c in checks) else "READY",
            "revision": intent["revision"], "observedat": now, "expiresat": now + 30,
            "fingerprint": fingerprint(intent), "checks": checks,
            "impact": {"class": "DRAIN_REQUIRED" if intent.get("drainrequired") else
                       "TEMPORARY_INTERRUPTION" if intent.get("ackrequired") else "ONLINE",
                       "affectedcount": intent.get("resourcecount", len(intent.get("resourceids", []))), "connections": measured["connections"],
                       "confidence": "ENDPOINT_TCP_CONNECTIONS", "ackrequired": bool(intent.get("ackrequired")),
                       "drainrequired": bool(intent.get("drainrequired"))},
            "profile": {"name": "RECURSIVE_POSIX" if recursive else "CONFIGURATION",
                        "softdeadlineseconds": 1800 if recursive else 300, "pollintervalseconds": 2,
                        "observationseconds": 2100 if recursive else 600},
            "cancellationsupported": False}

def inspect(intent):
    if not isinstance(intent, dict) or intent.get("protocol") not in DEFAULT_PORTS:
        raise ValueError("INVALID_PREFLIGHT_INTENT")
    try:
        intent = dict(intent)
        observed = None
        if intent.get("protocol") == "NFS" and intent.get("posixtarget"):
            from configuration_transaction import nfs_posix_observe
            observed = nfs_posix_observe(intent["posixtarget"])
            intent["posixfingerprint"] = observed.get("fingerprint")
        if intent.get("directorytarget"):
            from shared_directory_policy import DirectoryPolicies
            DirectoryPolicies().prepare(intent["directorytarget"])
        inheritance = None
        if intent.get("inheritancetarget"):
            from shared_directory_policy import inheritance_preview
            inheritance = inheritance_preview(intent["inheritancetarget"],intent["inheritancepolicy"],intent["smbinheritance"])
            intent["inheritancefingerprint"] = inheritance["parent"]["fingerprint"]
        report = evaluate(intent, sample(intent))
        if inheritance is not None:
            report["inheritancepreview"] = inheritance

        if observed is not None:
            report["posixobservation"] = observed
            report["posixchanges"] = intent.get("posixchanges") or {}
            if intent.get("posixchanges") and intent.get("action") != "CREATE" and observed["status"] != "OBSERVED":
                report["status"] = "BLOCKED"
                report["checks"].append({"code":"POSIX_OBSERVATION_UNAVAILABLE","blocking":True,"severity":"ERROR"})
        return report
    except Exception as error:
        code=getattr(error,"code","RESOURCE_OBSERVATION_UNAVAILABLE")
        status="BLOCKED" if code.startswith(("SMB_","DIRECTORY_")) and "UNAVAILABLE" not in code else "UNKNOWN"
        return {"status": status, "revision": intent.get("revision"), "fingerprint": fingerprint(intent),
                "observedat": time.time(), "expiresat": time.time(),
                "checks": [{"code": getattr(error,"code","RESOURCE_OBSERVATION_UNAVAILABLE"), "blocking": True, "severity": "ERROR"}],
                "cancellationsupported": False}
