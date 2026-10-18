# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Fresh initial-service facts only. This observer never establishes a native generation."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import time
import uuid

CI_FEATURE = "STORAGE_CLONE_INITIAL_BASELINE_V1"
CI_REQUEST_FIELDS = frozenset(("instanceUuid", "operationUuid", "revision", "expectedBootId",
    "expectedConfigurationSha256", "expectedCurrentGeneration", "expectedPendingOperationUuid",
    "expectedVmUuid", "rootVolumeUuid", "rootSizeBytes"))
CI_PROTOCOL_PROCESSES = frozenset(("ganesha.nfsd", "smbd", "nmbd", "winbindd", "nfsd",
    "rpc.nfsd", "rpc.mountd", "mountd", "tgtd", "istgt", "spdk_tgt", "tcmu-runner",
    "samba", "samba-dcerpcd", "rpc.statd"))

def ci_need(value, code):
    if not value:
        raise ValueError(code)

def ci_uuid(value):
    ci_need(type(value) is str and str(uuid.UUID(value)) == value, "INITIAL_UUID_INVALID")
    return value

def ci_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

class CloneInitialWitness:
    def __init__(self, generation, system_root="/", runner=subprocess.run, which=shutil.which,
                 epoch=time.time, monotonic=time.monotonic, seconds=45):
        self.generation = generation
        self.system = Path(system_root)
        self.runner, self.which, self.epoch, self.monotonic = runner, which, epoch, monotonic
        self.deadline = monotonic() + seconds
        self.exited_pids = set()

    def path(self, value):
        return self.system / str(value).lstrip("/")

    def remaining(self):
        value = self.deadline - self.monotonic()
        ci_need(value > 0, "INITIAL_OBSERVATION_DEADLINE")
        return min(value, 5)

    def command(self, argv, allowed=(0,)):
        self.remaining()
        ci_need(self.which(argv[0]) is not None, "INITIAL_REQUIRED_TOOL_MISSING")
        result = self.runner(argv, capture_output=True, text=True, timeout=self.remaining())
        ci_need(result.returncode in allowed and type(result.stdout) is str
                and len(result.stdout.encode()) <= 8 * 1024 * 1024, "INITIAL_COMMAND_UNAVAILABLE")
        return result

    def parents(self, path):
        for parent in (path, *path.parents):
            if not os.path.lexists(parent):
                continue
            info = parent.lstat()
            ci_need(stat.S_ISDIR(info.st_mode) and not parent.is_symlink()
                    and info.st_uid == os.geteuid() and not info.st_mode & 0o022,
                    "INITIAL_UNTRUSTED_DIRECTORY")
            if parent == self.system:
                break

    def file(self, path, optional=False, virtual=False):
        self.parents(path.parent)
        if not os.path.lexists(path):
            ci_need(optional, "INITIAL_REQUIRED_FILE_MISSING")
            return None
        before = path.lstat()
        ci_need(stat.S_ISREG(before.st_mode) and before.st_uid == os.geteuid()
                and not before.st_mode & 0o022 and before.st_size <= 8 * 1024 * 1024,
                "INITIAL_UNTRUSTED_FILE")
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | getattr(os, "O_NOATIME", 0))
        try:
            signature = lambda s: (s.st_dev, s.st_ino, s.st_mode, s.st_uid, s.st_gid,
                                   s.st_size, s.st_mtime_ns, s.st_ctime_ns)
            opened = os.fstat(fd)
            kernel_metadata = virtual and path in (
                self.path("/sys/kernel/config/target/dbroot"),
                self.path("/sys/kernel/config/target/version"))
            if kernel_metadata:
                # These configfs attributes may allocate their dentry inode at open.
                # Pin the opened fd; only this pre-open comparison excludes inode.
                prior, pinned = signature(before), signature(opened)
                ci_need(prior[:1] + prior[2:] == pinned[:1] + pinned[2:], "INITIAL_FILE_CHANGED")
            else:
                ci_need(signature(before) == signature(opened), "INITIAL_FILE_CHANGED")
            if virtual:
                chunks, total = [], 0
                while total <= 8 * 1024 * 1024:
                    part = os.read(fd, min(4096, 8 * 1024 * 1024 + 1 - total))
                    if not part:
                        break
                    chunks.append(part);total += len(part)
                raw = b"".join(chunks)
            else:
                raw = os.read(fd, 8 * 1024 * 1024 + 1)
            ci_need(len(raw) <= 8 * 1024 * 1024 and (virtual or len(raw) == before.st_size)
                    and signature(opened) == signature(os.fstat(fd)) == signature(path.lstat()),
                    "INITIAL_FILE_CHANGED")
            return raw
        finally:
            os.close(fd)

    def entries(self, path, optional=True):
        self.parents(path)
        if not os.path.lexists(path):
            ci_need(optional, "INITIAL_REQUIRED_DIRECTORY_MISSING")
            return []
        self.parents(path)
        rows = sorted(path.iterdir())
        ci_need(len(rows) <= 4096, "INITIAL_INVENTORY_BOUND")
        return rows

    def boot(self):
        raw = self.file(self.path("/proc/sys/kernel/random/boot_id"), virtual=True)
        return ci_uuid(raw.decode().strip())

    def dmi_uuid(self):
        alias = self.path("/sys/class/dmi/id")
        if alias.is_symlink():
            self.parents(alias.parent)
            before = alias.lstat()
            ci_need(before.st_uid == os.geteuid()
                    and os.readlink(alias) == "../../devices/virtual/dmi/id",
                    "INITIAL_DMI_ALIAS_INVALID")
            target = self.path("/sys/devices/virtual/dmi/id/product_uuid")
            raw = self.file(target, virtual=True)
            after = alias.lstat()
            ci_need((before.st_dev, before.st_ino, before.st_uid, before.st_ctime_ns)
                    == (after.st_dev, after.st_ino, after.st_uid, after.st_ctime_ns)
                    and os.readlink(alias) == "../../devices/virtual/dmi/id",
                    "INITIAL_DMI_ALIAS_CHANGED")
        else:
            raw = self.file(alias / "product_uuid", virtual=True)
        return ci_uuid(raw.decode().strip().lower())

    def state(self, request):
        self.parents(self.generation.config)
        self.parents(self.generation.root)
        value = self.generation.status()
        desired = value.get("configurationDesiredState")
        ci_need(type(desired) is dict and set(desired) == set(self.generation.paths)
                and ci_digest(desired) == value.get("configurationSha256")
                == request["expectedConfigurationSha256"], "INITIAL_CANONICAL_CHANGED")
        # Only a genuinely unconfigured protocol/policy source may be initialized.
        for name in set(self.generation.paths) - {"sharedfs-network.json"}:
            ci_need(desired[name] is None or desired[name] == {}, "INITIAL_PROTOCOL_CANONICAL_NOT_EMPTY")
        ci_need(type(desired["sharedfs-network.json"]) is dict and bool(desired["sharedfs-network.json"]),
                "INITIAL_DECLARED_NETWORK_MISSING")
        current = value.get("generation")
        ci_need(type(current) is dict and current == request["expectedCurrentGeneration"],
                "INITIAL_CURRENT_CHANGED")
        pending = value.get("pendingOperationUuid")
        ci_need(pending == request["expectedPendingOperationUuid"], "INITIAL_PENDING_CHANGED")
        scope = {k: request[k] for k in ("instanceUuid", "operationUuid", "revision")}
        if not current:
            ci_need(value.get("runtimeRevision") == 0, "INITIAL_REVISION_NOT_ZERO")
            if pending is None:
                ci_need(value.get("generationStatus") == "UNVERIFIED", "INITIAL_STATUS_INVALID")
                boundary = "INITIAL_IDLE"
            else:
                ci_need(pending == scope["operationUuid"] and value.get("generationStatus") == "PENDING",
                        "INITIAL_FOREIGN_PENDING")
                raw = self.file(self.generation.pending)
                record = json.loads(raw)
                ci_need(type(record) is dict and all(record.get(k) == v for k, v in scope.items())
                        and record.get("previous") == {} and record.get("beforeSha256") == value["configurationSha256"]
                        and record.get("phase") in ("PREPARED", "VERIFIED"), "INITIAL_PENDING_SCOPE_OR_PHASE")
                if record["phase"] == "VERIFIED":
                    frozen = self.generation.frozen(scope)
                    ci_need(frozen["configurationDesiredState"] == desired
                            and frozen["configurationSha256"] == value["configurationSha256"],
                            "INITIAL_PENDING_FROZEN_CHANGED")
                boundary = "OWN_PENDING"
        else:
            ci_need(pending is None and value.get("generationStatus") == "IN_SYNC"
                    and set(current) == {"instanceUuid", "operationUuid", "revision", "configurationSha256", "verifiedAt"}
                    and current["instanceUuid"] == request["instanceUuid"]
                    and type(current["revision"]) is int and current["revision"] > 0
                    and type(current["verifiedAt"]) in (float, int) and math.isfinite(current["verifiedAt"])
                    and current["verifiedAt"] > 0 and current["configurationSha256"] == value["configurationSha256"],
                    "INITIAL_COMMITTED_BASELINE_INVALID")
            ci_uuid(current["operationUuid"])
            frozen = self.generation.frozen(current)
            ci_need(frozen["generation"] == current and frozen["configurationDesiredState"] == desired,
                    "INITIAL_COMMITTED_FROZEN_CHANGED")
            boundary = "COMMITTED_BASELINE"
        return value, boundary

    def disks(self, request):
        data = json.loads(self.command(["lsblk", "--json", "--bytes", "--paths", "--output",
            "NAME,PATH,TYPE,SIZE,SERIAL,FSTYPE,UUID,MOUNTPOINTS"]).stdout)
        mounts = json.loads(self.command(["findmnt", "--json", "--output", "SOURCE,TARGET,FSTYPE,OPTIONS,UUID"]).stdout)
        ci_need(type(data) is dict and type(data.get("blockdevices")) is list
                and type(mounts) is dict and type(mounts.get("filesystems")) is list, "INITIAL_DISK_SCHEMA")
        all_mounts = []
        def walk_mount(rows):
            for row in rows:
                ci_need(type(row) is dict and type(row.get("target")) is str, "INITIAL_MOUNT_SCHEMA")
                all_mounts.append({k: row.get(k) for k in ("source", "target", "fstype", "options", "uuid")})
                if "children" in row:
                    ci_need(type(row["children"]) is list, "INITIAL_MOUNT_SCHEMA")
                    walk_mount(row["children"])
        walk_mount(mounts["filesystems"])
        roots = []
        inventory = []
        paths, serials = set(), set()
        for disk in data["blockdevices"]:
            ci_need(type(disk) is dict and type(disk.get("type")) is str, "INITIAL_DISK_SCHEMA")
            if disk["type"] != "disk":
                ci_need(disk["type"] == "rom" and not any(disk.get("mountpoints") or []),
                        "INITIAL_UNKNOWN_BLOCK_DEVICE")
                continue
            children = []
            def flatten(row):
                ci_need(type(row) is dict and re.fullmatch(r"/dev/[A-Za-z0-9_./-]+", row.get("path") or ""),
                        "INITIAL_DEVICE_PATH_INVALID")
                children.append(row)
                for child in row.get("children") or []:
                    flatten(child)
            flatten(disk)
            serial = re.sub(r"[-\s]", "", str(disk.get("serial") or "")).lower()
            size = disk.get("size")
            ci_need(re.fullmatch(r"(?:[0-9a-f]{20}|[0-9a-f]{32})", serial) and serial not in serials
                    and type(size) is int and size > 0 and disk["path"] not in paths,
                    "INITIAL_DISK_IDENTITY_UNKNOWN")
            serials.add(serial);paths.add(disk["path"])
            related = [m for m in all_mounts if m["source"] in {x["path"] for x in children}]
            is_root = any(m["target"] == "/" for m in related)
            filesystems = [x for x in children if x.get("fstype") or x.get("uuid")]
            if is_root:
                root_sources = {m["source"] for m in related if m["target"] == "/"}
                filesystems = [x for x in filesystems if x["path"] in root_sources]
            ci_need(len(filesystems) <= 1, "INITIAL_FILESYSTEM_AMBIGUOUS")
            fs = filesystems[0] if filesystems else disk
            probe = self.command(["blkid", "--probe", "--cache-file", "/dev/null", "--output", "export", fs["path"]], (0, 2))
            fields = dict(line.split("=", 1) for line in probe.stdout.splitlines() if "=" in line)
            if probe.returncode == 0:
                ci_need(fields.get("TYPE") == fs.get("fstype") and fields.get("UUID") == fs.get("uuid")
                        and bool(fields.get("TYPE")) and bool(fields.get("UUID")), "INITIAL_FILESYSTEM_CHANGED")
                ci_uuid(fields["UUID"])
            else:
                ci_need(not probe.stdout and not fs.get("fstype") and not fs.get("uuid"), "INITIAL_UNKNOWN_SIGNATURE")
            row = {"volumeUuid": str(uuid.UUID(serial)) if len(serial) == 32 else None,
                "serial": disk["serial"], "sizeBytes": size, "devicePath": disk["path"],
                "filesystem": fs.get("fstype"), "filesystemUuid": fs.get("uuid"),
                "mounts": sorted(related, key=lambda m: (m["target"], m["source"])), "isRoot": is_root,
                "partitioned": bool(disk.get("children"))}
            inventory.append(row)
            if is_root:
                exact = request["rootVolumeUuid"].replace("-", "")
                ci_need(serial in (exact, exact[:20]) and size == request["rootSizeBytes"],
                        "INITIAL_ROOT_IDENTITY_CHANGED")
                row["volumeUuid"] = request["rootVolumeUuid"]
                roots.append({"volumeUuid": request["rootVolumeUuid"], "serial": disk["serial"],
                    "sizeBytes": size, "devicePath": disk["path"], "mappingStatus": "EXACT",
                    "matchedBy": "VOLUME_SERIAL", "vmUuid": request["expectedVmUuid"]})
        ci_need(len(roots) == 1 and 1 < len(inventory) <= 513, "INITIAL_ROOT_OR_DATA_SET_UNKNOWN")
        return roots[0], sorted(inventory, key=lambda row: row["devicePath"])

    def comments_only(self, path, allowed=()):
        raw = self.file(path, optional=True)
        if raw is None:
            return None
        text = raw.decode()
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        meaningful = [line.split("#", 1)[0].strip() for line in text.splitlines()]
        ci_need(all(not line or line in allowed for line in meaningful), "INITIAL_FOREIGN_STATIC_CONFIGURATION")
        return hashlib.sha256(raw).hexdigest()

    def saved_empty(self, path, keys):
        raw = self.file(path, optional=True)
        if raw is None:
            return None
        value = json.loads(raw)
        ci_need(type(value) is dict and set(value) <= set(keys)
                and all(type(v) is list and not v for v in value.values()), "INITIAL_SAVED_TARGET_NOT_EMPTY")
        return hashlib.sha256(raw).hexdigest()

    def kernel(self):
        module_text = self.file(self.path("/proc/modules"), virtual=True).decode()
        modules = set()
        for line in module_text.splitlines():
            fields = line.split()
            ci_need(len(fields) >= 6 and fields[1].isdigit() and fields[2].isdigit(),
                    "INITIAL_MODULE_INVENTORY_UNKNOWN")
            modules.add(fields[0])
        relevant = ("target_core_mod", "iscsi_target_mod", "nvmet", "nvmet_tcp", "nvmet_rdma", "nvmet_fc")
        loaded = {}
        for name in relevant:
            path = self.path("/sys/module/" + name)
            if os.path.lexists(path):
                self.parents(path)
            loaded[name] = name in modules or os.path.lexists(path)
        found = json.loads(self.command(["findmnt", "--json", "--types", "configfs", "--output", "TARGET,FSTYPE"]).stdout)
        ci_need(type(found) is dict and found.get("filesystems") == [{"target": "/sys/kernel/config", "fstype": "configfs"}],
                "INITIAL_CONFIGFS_UNOBSERVED")
        target = self.path("/sys/kernel/config/target")
        ci_need(os.path.lexists(target) or not any(loaded[n] for n in ("target_core_mod", "iscsi_target_mod")),
                "INITIAL_LOADED_TARGET_NAMESPACE_UNOBSERVED")
        counts = {"iscsiTargets": 0, "backstores": 0, "nvmeSubsystems": 0, "nvmePorts": 0, "nvmeHosts": 0}
        metadata = {}
        for entry in self.entries(target):
            if entry.name in ("dbroot", "version"):
                raw = self.file(entry, virtual=True)
                value = raw.decode().strip()
                if entry.name == "dbroot":
                    ci_need(value == "/etc/rtslib-fb-target", "INITIAL_TARGET_DBROOT_UNKNOWN")
                else:
                    uname = os.uname()
                    suffix = " on " + uname.sysname + "/" + uname.machine + " on " + uname.release
                    ci_need(re.fullmatch(r"Target Engine Core ConfigFS Infrastructure v[0-9]+[.][0-9]+"
                                        + re.escape(suffix), value), "INITIAL_TARGET_VERSION_UNKNOWN")
                metadata[entry.name] = value
                continue
            ci_need(entry.name in ("core", "iscsi"), "INITIAL_UNKNOWN_TARGET_FABRIC")
        for entry in self.entries(target / "iscsi"):
            if entry.name in ("cpus_allowed_list", "lio_version"):
                ci_need(entry.is_file() and not entry.is_symlink(), "INITIAL_UNKNOWN_ISCSI_METADATA")
                continue
            ci_need(entry.name == "discovery_auth" and entry.is_dir() and not entry.is_symlink(),
                    "INITIAL_FOREIGN_ISCSI_TARGET")
            self.parents(entry)
        for hba in self.entries(target / "core"):
            ci_need(hba.is_dir() and not hba.is_symlink(), "INITIAL_UNKNOWN_BACKSTORE_ENTRY")
            if hba.name == "alua":
                ci_need(all(child.name == "lu_gps" for child in self.entries(hba)),
                        "INITIAL_UNKNOWN_ALUA_NAMESPACE")
                for group in self.entries(hba / "lu_gps", optional=False):
                    ci_need(group.name == "default_lu_gp" and group.is_dir() and not group.is_symlink(),
                            "INITIAL_FOREIGN_ALUA_GROUP")
                continue
            ci_need(re.fullmatch(r"(iblock|fileio|rd_mcp|pscsi|user)_[0-9]+", hba.name), "INITIAL_UNKNOWN_BACKSTORE_HBA")
            for child in self.entries(hba):
                ci_need(child.is_file() and not child.is_symlink(), "INITIAL_ORPHAN_BACKSTORE")
        nvme = self.path("/sys/kernel/config/nvmet")
        ci_need(os.path.lexists(nvme) or not any(loaded[n] for n in relevant if n.startswith("nvmet")),
                "INITIAL_LOADED_NVME_NAMESPACE_UNOBSERVED")
        for entry in self.entries(nvme):
            ci_need(entry.name in ("subsystems", "ports", "hosts"), "INITIAL_UNKNOWN_NVME_ENTRY")
        for name in ("subsystems", "ports", "hosts"):
            ci_need(not self.entries(nvme / name, optional=not os.path.lexists(nvme)),
                    "INITIAL_FOREIGN_OR_ORPHAN_NVME")
        return {"resourceCounts": counts, "relevantModulesLoaded": loaded,
                "configfsObserved": True, "absentNamespacesRequireModulesAbsent": True,
                "targetKernelMetadata": metadata,
                "targetDbrootSavedConfigurationPath": "/etc/rtslib-fb-target/saveconfig.json"
                    if "dbroot" in metadata else None}

    def control_process(self, proc):
        # /proc/PID ownership may be the trusted service's dropped uid, not root.
        self.parents(proc.parent)
        directory = proc.lstat()
        ci_need(stat.S_ISDIR(directory.st_mode) and not proc.is_symlink(), "INITIAL_OWNER_PROCESS_UNKNOWN")
        path = proc / "stat"
        before = path.lstat()
        ci_need(stat.S_ISREG(before.st_mode) and before.st_size <= 1048576, "INITIAL_OWNER_PROCESS_UNKNOWN")
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        try:
            opened = os.fstat(fd)
            ci_need((opened.st_dev, opened.st_ino, opened.st_uid) == (before.st_dev, before.st_ino, before.st_uid),
                    "INITIAL_OWNER_PROCESS_CHANGED")
            fields = os.read(fd, 1048577).decode().rpartition(")")[2].split()
            ci_need(len(fields) > 19 and fields[19].isdigit(), "INITIAL_OWNER_IDENTITY_UNKNOWN")
            after = proc.lstat()
            ci_need((directory.st_dev, directory.st_ino, directory.st_uid)
                    == (after.st_dev, after.st_ino, after.st_uid), "INITIAL_OWNER_PROCESS_CHANGED")
            return {"startTicks": int(fields[19]), "processUid": directory.st_uid,
                    "processDirectoryInode": directory.st_ino}
        finally:
            os.close(fd)

    def control_owner(self, name, pid, port, transport, processes):
        known = {"sshd": ("TCP", 22, "/usr/sbin/sshd", ("ssh.service", "sshd.service")),
                 "ntpd": ("UDP", 123, "/usr/sbin/ntpd", ("ntpsec.service", "ntp.service", "ntpd.service")),
                 "rsyslogd": ("UDP", 3914, "/usr/sbin/rsyslogd", ("rsyslog.service",))}
        ci_need(name in known and known[name][:2] == (transport, port)
                and processes.get(pid) == name, "INITIAL_UNKNOWN_LISTENER_OWNER")
        _, _, binary, units = known[name]
        proc = self.path("/proc") / str(pid)
        before = self.control_process(proc)
        link = proc / "exe"
        ci_need(link.is_symlink() and os.readlink(link) == binary, "INITIAL_OWNER_EXECUTABLE_CHANGED")
        executable = self.path(binary)
        self.parents(executable.parent)
        info = executable.lstat()
        ci_need(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and not info.st_mode & 0o022,
                "INITIAL_OWNER_EXECUTABLE_UNTRUSTED")
        matched = []
        for unit in units:
            text = self.command(["systemctl", "show", unit, "--property=LoadState", "--property=ActiveState",
                                 "--property=SubState", "--property=MainPID"]).stdout
            value = dict(line.split("=", 1) for line in text.splitlines() if "=" in line)
            ci_need(set(value) == {"LoadState", "ActiveState", "SubState", "MainPID"},
                    "INITIAL_OWNER_UNIT_UNKNOWN")
            if value["LoadState"] == "loaded" and value["ActiveState"] == "active" and value["SubState"] == "running" and value["MainPID"] == str(pid):
                matched.append(unit)
        ci_need(matched and os.readlink(link) == binary
                and self.control_process(proc) == before,
                "INITIAL_OWNER_UNIT_OR_PID_CHANGED")
        if name == "rsyslogd":
            text = self.file(self.path("/etc/rsyslog.conf")).decode()
            directives = [line.strip() for line in text.splitlines() if line.strip() and not line.lstrip().startswith("#")]
            ci_need(directives.count("$UDPServerRun 3914") == 1 and "$ModLoad imudp" in directives,
                    "INITIAL_SYSLOG_UDP_BINDING_UNKNOWN")
        return {"name": name, "pid": pid, **before, "executable": binary,
                "units": sorted(matched), "transport": transport, "port": port}

    def runtime(self):
        files = {}
        files["exports"] = self.comments_only(self.path("/etc/exports"))
        for entry in self.entries(self.path("/etc/exports.d")):
            ci_need(entry.is_file() and not entry.is_symlink(), "INITIAL_UNKNOWN_EXPORTS_ENTRY")
            files["exports.d/" + entry.name] = self.comments_only(entry)
        for value in ("/proc/fs/nfsd/exports", "/proc/fs/nfs/exports"):
            raw = self.file(self.path(value), optional=True, virtual=True)
            if raw is not None:
                ci_need(all(not line.strip() or line.lstrip().startswith("#") for line in raw.decode().splitlines()),
                        "INITIAL_KERNEL_NFS_EXPORT")
        threads = self.file(self.path("/proc/fs/nfsd/threads"), optional=True, virtual=True)
        ci_need(threads is None or threads.decode().strip() == "0", "INITIAL_KERNEL_NFS_THREADS")
        ci_need(not self.command(["exportfs", "-v"]).stdout.strip(), "INITIAL_ACTIVE_NFS_EXPORT")
        files["ganesha"] = self.comments_only(self.path("/etc/ganesha/ganesha.conf"),
            ('%include "/etc/ganesha/ablestack-storage/*.conf"', '%include "/etc/ganesha/ablestack-storage/*.conf";'))
        for entry in self.entries(self.path("/etc/ganesha/ablestack-storage")):
            ci_need(entry.is_file() and not entry.is_symlink(), "INITIAL_UNKNOWN_GANESHA_CONFIGURATION")
            files["ganesha/" + entry.name] = self.comments_only(entry)
        smb = self.file(self.path("/etc/samba/smb.conf"), optional=True)
        vendor = self.file(self.path("/usr/share/samba/smb.conf"), optional=True)
        smb_kind = "ABSENT"
        if smb is not None:
            ci_need(vendor is not None and smb == vendor, "INITIAL_FOREIGN_SAMBA_CONFIGURATION")
            parsed = self.command(["testparm", "-s", "/etc/samba/smb.conf"]).stdout
            names = re.findall(r"^\[([^\]]+)\]\s*$", parsed, re.M)
            ci_need(names and set(names) <= {"global", "homes", "printers", "print$"}
                    and len(names) == len(set(names)), "INITIAL_UNKNOWN_VENDOR_SAMBA")
            smb_kind = "INACTIVE_EXACT_VENDOR_DEFAULT"
        else:
            ci_need(self.which("testparm") is not None, "INITIAL_REQUIRED_TOOL_MISSING")
        units = {}
        for unit in ("smb.service", "smbd.service", "nmb.service", "nmbd.service", "winbind.service",
                     "nfs-ganesha.service", "ganesha.service", "nfs-server.service", "nfs-kernel-server.service"):
            text = self.command(["systemctl", "show", unit, "--property=LoadState", "--property=ActiveState",
                                 "--property=SubState", "--property=MainPID"]).stdout
            value = dict(line.split("=", 1) for line in text.splitlines() if "=" in line)
            ci_need(set(value) == {"LoadState", "ActiveState", "SubState", "MainPID"}
                    and value["LoadState"] in ("loaded", "not-found", "masked")
                    and value["ActiveState"] in ("inactive", "failed") and value["MainPID"] == "0",
                    "INITIAL_SAMBA_DAEMON_NOT_INACTIVE")
            units[unit] = value
        processes = {}
        for proc in self.entries(self.path("/proc"), optional=False):
            if not proc.name.isdigit():
                continue
            try:
                comm = (proc / "comm").read_text().strip()
            except FileNotFoundError:
                if not os.path.lexists(proc):
                    self.exited_pids.add(int(proc.name))
                    continue
                raise ValueError("INITIAL_PROCESS_OBSERVATION_UNKNOWN") from None
            except OSError:
                raise ValueError("INITIAL_PROCESS_OBSERVATION_UNKNOWN") from None
            ci_need(comm not in CI_PROTOCOL_PROCESSES, "INITIAL_PROTOCOL_PROCESS_ACTIVE")
            processes[int(proc.name)] = comm
        sockets = []
        owner_bindings = {}
        for flag in ("-ltnp", "-lunp"):
            for line in self.command(["ss", "-H", flag]).stdout.splitlines():
                fields = line.split()
                ci_need(len(fields) >= 6 and fields[3].rpartition(":")[2].isdigit(),
                        "INITIAL_SOCKET_SCHEMA_UNKNOWN")
                port = int(fields[3].rpartition(":")[2])
                owners = re.findall(r'\("([^"]+)",pid=([0-9]+),fd=[0-9]+\)', " ".join(fields[5:]))
                ci_need(bool(owners), "INITIAL_SOCKET_OWNER_UNOBSERVED")
                transport = "TCP" if flag == "-ltnp" else "UDP"
                for name, pid in owners:
                    key = (name, int(pid), port, transport)
                    if key not in owner_bindings:
                        owner_bindings[key] = self.control_owner(name, int(pid), port, transport, processes)
                sockets.append({"transport": "TCP" if flag == "-ltnp" else "UDP", "local": fields[3],
                                "owners": sorted([[name, int(pid)] for name, pid in owners])})
        for value in ("/etc/rtslib-fb-target/saveconfig.json", "/etc/target/saveconfig.json"):
            files[value] = self.saved_empty(self.path(value), ("storage_objects", "targets", "fabric_modules", "alua_tpgs"))
        for value in ("/etc/nvmet/config.json", "/etc/nvmetcli/config.json"):
            files[value] = self.saved_empty(self.path(value), ("hosts", "ports", "subsystems"))
        files["tgt"] = self.comments_only(self.path("/etc/tgt/targets.conf"), ("include /etc/tgt/conf.d/*.conf",))
        for entry in self.entries(self.path("/etc/tgt/conf.d")):
            files["tgt/" + entry.name] = self.comments_only(entry)
        kernel = self.kernel()
        return {"kernelResourceCounts": kernel, "activeNfsExports": 0, "protocolProcesses": [],
                "nonProtocolControlPlaneListeners": sorted(sockets, key=lambda row: (row["transport"], row["local"])),
                "staticConfigurationSha256": files, "sambaConfigurationKind": smb_kind, "sambaUnits": units,
                "controlPlaneOwnerBindings": sorted(owner_bindings.values(), key=lambda row: (row["name"], row["pid"])),
                "sambaHomesDataIndependenceClaimed": False, "commandFailuresIgnored": False}

    def inspect(self, request):
        ci_need(type(request) is dict and set(request) == CI_REQUEST_FIELDS, "INITIAL_REQUEST_SCHEMA")
        for key in ("instanceUuid", "operationUuid", "expectedBootId", "expectedVmUuid", "rootVolumeUuid"):
            ci_uuid(request[key])
        ci_need(type(request["revision"]) is int and request["revision"] > 0
                and type(request["rootSizeBytes"]) is int and request["rootSizeBytes"] > 0
                and type(request["expectedConfigurationSha256"]) is str
                and re.fullmatch("[a-f0-9]{64}", request["expectedConfigurationSha256"])
                and type(request["expectedCurrentGeneration"]) is dict, "INITIAL_REQUEST_SCOPE")
        ci_need(request["expectedPendingOperationUuid"] in (None, request["operationUuid"]), "INITIAL_REQUEST_PENDING")
        boot = self.boot()
        dmi = self.dmi_uuid()
        ci_need(boot == request["expectedBootId"] and dmi == request["expectedVmUuid"], "INITIAL_BOOT_OR_VM_CHANGED")
        before, boundary = self.state(request)
        root, disks = self.disks(request)
        facts = self.runtime()
        ci_need(self.runtime() == facts and self.disks(request) == (root, disks)
                and self.state(request) == (before, boundary) and self.boot() == boot
                and self.dmi_uuid() == dmi,
                "INITIAL_OBSERVATION_CHANGED")
        self.remaining()
        ci_need(all(not os.path.lexists(self.path("/proc") / str(pid)) for pid in self.exited_pids),
                "INITIAL_EXITED_PROCESS_REAPPEARED")
        generated = self.epoch()
        ci_need(type(generated) in (int, float) and math.isfinite(generated) and generated > 0,
                "INITIAL_OBSERVATION_TIME_UNKNOWN")
        return {"success": True, "readOnly": True, "generationSupported": True,
            "cloneInitialBaselineV1Supported": True, "schemaVersion": 1,
            "scope": {k: request[k] for k in ("instanceUuid", "operationUuid", "revision")},
            "bootId": boot, "configurationSha256": before["configurationSha256"],
            "configurationDesiredState": before["configurationDesiredState"], "generation": before["generation"],
            "runtimeRevision": before["runtimeRevision"], "generationStatus": before["generationStatus"],
            "pendingOperationUuid": before["pendingOperationUuid"], "witnessBoundary": boundary,
            "globalRuntimeEmpty": True, "observationComplete": True, "initialSourceVerified": False,
            "rootIdentity": root, "diskInventory": disks, "runtimeFacts": facts, "generatedEpoch": generated,
            "processObservation": {"matchingCompleteSnapshots": 2,
                "exitedPidObservations": sorted(self.exited_pids), "exitedPidsStillAbsent": True}}
