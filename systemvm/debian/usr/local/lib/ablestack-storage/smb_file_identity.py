# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Owned, non-authenticating SMB file identities. No automatic data permission changes."""
import contextlib
import fcntl
import grp
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import tempfile
import time
from shared_directory_policy import PolicyError, FileSharePathBindings, inheritance_protection_version

SMB_FILE_IDENTITY_VERSION = 1
LIMIT = 1024 * 1024
REGISTRY = Path("/var/lib/ablestack-storage/smb-fixed-identities.json")
PREFIX = "asfs_"
INACTIVE = ("Disabled", "Destroyed", "Error")
AUDIT_SUCCESS = "connect disconnect openat mkdirat pwrite renameat unlinkat"


def reject_auth_identity(name):
    base = re.split(r"[\\/@]", str(name))[-1].lower()
    if re.fullmatch(r"asfs_[ug]_[0-9a-f]{8}_[0-9]+", base):
        raise PolicyError("SMB_FIXED_IDENTITY_AUTH_FORBIDDEN")


def policy(config):
    value = (config or {}).get("fileIdentity")
    if value is None:
        return {"version": 1, "mode": "AUTHENTICATED"}
    if not isinstance(value, dict) or set(value) - {"version", "mode", "uid", "gid"} or type(value.get("version")) is not int or value.get("version") != 1:
        raise PolicyError("SMB_FIXED_IDENTITY_INVALID")
    if value.get("mode") == "AUTHENTICATED" and not (set(value) & {"uid", "gid"}):
        return dict(value)
    if value.get("mode") != "FIXED":
        raise PolicyError("SMB_FIXED_IDENTITY_INVALID")
    for key in ("uid", "gid"):
        n = value.get(key)
        if type(n) is not int or not 1000 <= n <= 65533 or 61184 <= n <= 65519:
            raise PolicyError("SMB_FIXED_IDENTITY_PROTECTED_ID")
    return dict(value)


def published(row):
    return row.get("state") not in INACTIVE and bool((row.get("config") or {}).get("guestOk") or
        any(a.get("principal") and a.get("state") not in INACTIVE for a in row.get("acls", [])))


def checked(args, **kwargs):
    p = subprocess.run(args, capture_output=True, text=True, timeout=20, **kwargs)
    if p.returncode:
        raise PolicyError("SMB_FIXED_IDENTITY_COMMAND_FAILED")
    return p.stdout.strip()


def read_safe(path, absent=None):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return absent
    try:
        s = os.fstat(fd)
        if not stat.S_ISREG(s.st_mode) or s.st_uid != 0 or s.st_nlink != 1 or s.st_mode & 0o022 or s.st_size > LIMIT:
            raise PolicyError("SMB_FIXED_IDENTITY_REGISTRY_UNSAFE")
        return json.loads(os.read(fd, LIMIT + 1))
    finally:
        os.close(fd)


def protected():
    return inheritance_protection_version(minimum=9) == 1


def audit_dependency():
    if not protected():
        raise PolicyError("SMB_FIXED_IDENTITY_PROTECTION_REQUIRED")
    profile = read_safe(Path("/usr/local/lib/ablestack-storage/runtime-baseline-profile.json"), {})
    item = (profile.get("nativeDependencies") or {}).get("smbFullAudit") or {}
    path = Path(item.get("path", "/nonexistent"))
    if str(path) != "/usr/lib/x86_64-linux-gnu/samba/vfs/full_audit.so":
        raise PolicyError("SMB_FIXED_IDENTITY_AUDIT_REQUIRED")
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, "rb") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or info.st_size > LIMIT:
                raise ValueError()
            digest = hashlib.sha256(stream.read()).hexdigest()
        version = checked(["dpkg-query", "-W", "-f=${Version}", "samba-libs"])
        if digest != item["sha256"] or version != item["packageVersion"]:
            raise ValueError()
    except (OSError, ValueError, KeyError):
        raise PolicyError("SMB_FIXED_IDENTITY_AUDIT_REQUIRED")
    return {"ready": True, "packageVersion": version, "sha256": digest}


def idmap_ranges():
    output = checked(["testparm", "-s", "/etc/samba/smb.conf"])
    ranges = []
    for low, high in re.findall(r"(?im)^\s*idmap config [^\n:]+\s*:\s*range\s*=\s*(\d+)\s*-\s*(\d+)\s*$", output):
        ranges.append((int(low), int(high)))
    return ranges


class FixedIdentities:
    def __init__(self, registry=REGISTRY, sources=None):
        self.registry = Path(registry)
        self.sources = sources

    def read(self):
        value = read_safe(self.registry, {"version": 1, "identities": {}})
        if not isinstance(value, dict) or value.get("version") != 1 or not isinstance(value.get("identities"), dict):
            raise PolicyError("SMB_FIXED_IDENTITY_REGISTRY_INVALID")
        return value

    def write(self, value):
        raw = json.dumps(value, sort_keys=True).encode()
        if len(raw) > LIMIT:
            raise PolicyError("SMB_FIXED_IDENTITY_LIMIT")
        self.registry.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        parent = self.registry.parent.stat()
        if parent.st_uid != 0 or parent.st_mode & 0o022 or self.registry.parent.is_symlink() or self.registry.is_symlink():
            raise PolicyError("SMB_FIXED_IDENTITY_REGISTRY_UNSAFE")
        fd, temporary = tempfile.mkstemp(prefix=".smb-identity-", dir=self.registry.parent)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(raw); stream.flush(); os.fsync(stream.fileno())
            os.replace(temporary, self.registry)
            fd = os.open(self.registry.parent, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(fd)
            finally: os.close(fd)
        finally:
            if os.path.exists(temporary): os.unlink(temporary)

    @contextlib.contextmanager
    def locked(self):
        self.registry.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        lock = self.registry.with_suffix(".lock")
        fd = os.open(lock, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            s = os.fstat(fd)
            if s.st_uid != 0 or not stat.S_ISREG(s.st_mode) or s.st_nlink != 1:
                raise PolicyError("SMB_FIXED_IDENTITY_REGISTRY_UNSAFE")
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally: os.close(fd)

    @staticmethod
    def key(instance, value):
        if not re.fullmatch(r"[0-9a-fA-F-]{36}", str(instance)):
            raise PolicyError("SMB_FIXED_IDENTITY_INSTANCE_REQUIRED")
        return str(instance).lower() + ":" + str(value["uid"]) + ":" + str(value["gid"])

    @staticmethod
    def names(instance, value):
        prefix = str(instance).replace("-", "")[:8].lower()
        return "asfs_u_" + prefix + "_" + str(value["uid"]), "asfs_g_" + prefix + "_" + str(value["gid"])

    def inspect_identity(self, instance, value, state=None):
        key = self.key(instance, value)
        user, group = self.names(instance, value)
        registry = self.read() if state is None else state
        owned = registry["identities"].get(key)
        for entry_key, item in registry["identities"].items():
            if item["uid"] == value["uid"] and entry_key != key:
                raise PolicyError("SMB_FIXED_IDENTITY_UID_COLLISION")
        for number in (value["uid"], value["gid"]):
            if any(low <= number <= high for low, high in idmap_ranges()):
                raise PolicyError("SMB_FIXED_IDENTITY_IDMAP_OVERLAP")
        try: u = pwd.getpwuid(value["uid"])
        except KeyError: u = None
        try: g = grp.getgrgid(value["gid"])
        except KeyError: g = None
        try: named_u = pwd.getpwnam(user)
        except KeyError: named_u = None
        try: named_g = grp.getgrnam(group)
        except KeyError: named_g = None
        if u and (not owned or u.pw_name != user or u.pw_gid != value["gid"]):
            raise PolicyError("SMB_FIXED_IDENTITY_UID_COLLISION")
        # A group may be reused only with an owned registry receipt for the same instance.
        owned_group = any(x.get("instance") == instance and x["gid"] == value["gid"] and x["group"] == group for x in registry["identities"].values())
        if g and (not owned_group or g.gr_name != group):
            raise PolicyError("SMB_FIXED_IDENTITY_GID_COLLISION")
        if named_u and named_u.pw_uid != value["uid"] or named_g and named_g.gr_gid != value["gid"]:
            raise PolicyError("SMB_FIXED_IDENTITY_NAME_COLLISION")
        if u:
            import spwd
            if u.pw_shell != "/usr/sbin/nologin" or u.pw_dir != "/nonexistent" or not spwd.getspnam(user).sp_pwdp.startswith(("!", "*")):
                raise PolicyError("SMB_FIXED_IDENTITY_ACCOUNT_DRIFT")
            if set(os.getgrouplist(user, value["gid"])) != {value["gid"]}:
                raise PolicyError("SMB_FIXED_IDENTITY_GROUP_DRIFT")
            q = subprocess.run(["pdbedit", "-L"], capture_output=True, text=True, timeout=15)
            if q.returncode != 0 or len(q.stdout.encode()) > LIMIT:
                raise PolicyError("SMB_FIXED_IDENTITY_OBSERVATION_UNAVAILABLE")
            if any(line.split(":",1)[0] == user for line in q.stdout.splitlines()):
                raise PolicyError("SMB_FIXED_IDENTITY_PASSDB_DRIFT")
        return {"key": key, "user": user, "group": group, "uid": value["uid"], "gid": value["gid"],
                "exists": u is not None and g is not None, "owned": bool(owned)}

    @staticmethod
    def permission(path, value, writable):
        script = ("import os,sys;os.setgroups([]);os.setgid(int(sys.argv[2]));os.setuid(int(sys.argv[1]));"
                  "raise SystemExit(0 if os.access('.',int(sys.argv[4]),dir_fd=int(sys.argv[3]),effective_ids=True) else 1)")
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            need = os.R_OK | os.X_OK | (os.W_OK if writable else 0)
            q = subprocess.run(["python3", "-c", script, str(value["uid"]), str(value["gid"]), str(fd), str(need)],
                               pass_fds=(fd,), capture_output=True, timeout=10)
            return q.returncode == 0
        finally: os.close(fd)

    @staticmethod
    def tree(path):
        start = time.monotonic(); count = 0; signatures = []
        root = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        dev = os.fstat(root).st_dev
        mount_id = FileSharePathBindings.mnt_id(root)
        def visit(fd):
            nonlocal count
            count += 1
            if count > 10000 or time.monotonic() - start > 10:
                raise PolicyError("SMB_FIXED_IDENTITY_TREE_LIMIT")
            s = os.fstat(fd)
            if s.st_dev != dev or FileSharePathBindings.mnt_id(fd) != mount_id: raise PolicyError("DIRECTORY_MOUNT_BOUNDARY")
            if s.st_mode & stat.S_ISGID: raise PolicyError("SMB_PARENT_IDENTITY_CONFLICT")
            signatures.append([s.st_ino, s.st_uid, s.st_gid, stat.S_IMODE(s.st_mode)])
            for name in sorted(os.listdir(fd)):
                info = os.stat(name, dir_fd=fd, follow_symlinks=False)
                if stat.S_ISLNK(info.st_mode):
                    continue  # never follow data symlinks; the managed root itself is FD-validated
                if stat.S_ISDIR(info.st_mode):
                    child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                    try: visit(child)
                    finally: os.close(child)
        try: visit(root)
        finally: os.close(root)
        return hashlib.sha256(json.dumps(signatures).encode()).hexdigest()

    def preview(self, row, instance, required=None, verify_current=False):
        config = row.get("config") or {}
        value = policy(config)
        if value["mode"] != "FIXED": return {"mode": "AUTHENTICATED", "existingobjectschange": False}
        if config.get("guestOk") is True: raise PolicyError("SMB_FIXED_IDENTITY_GUEST_FORBIDDEN")
        if config.get("inheritOwner") is True: raise PolicyError("SMB_PARENT_IDENTITY_CONFLICT")
        dependency = audit_dependency()
        identity = self.inspect_identity(instance, value)
        required = published(row) if required is None else required
        if not config.get("relativeSharePath") and config.get("backingPath") and config.get("volumeMountPath"):
            row = {**row, "config": {**config, "relativeSharePath": str(Path(config["backingPath"]).relative_to(Path(config["volumeMountPath"])))}}
        if required and not config.get("directoryPolicyUuid"):
            raise PolicyError("DIRECTORY_POLICY_REQUIRED")
        observed = FileSharePathBindings().inspect(row)
        path = observed.get("physicalpath")
        result = {"mode": "FIXED", "uid": value["uid"], "gid": value["gid"], "identity": identity,
                  "audit": dependency, "existingobjectschange": False, "publicationrequired": required,
                  "status": "POSIX_PREPARATION_REQUIRED", "path": observed}
        if observed["exists"]:
            info = os.stat(path)
            result["directory"] = {"uid": info.st_uid, "gid": info.st_gid, "mode": format(stat.S_IMODE(info.st_mode), "04o"), "inode": info.st_ino}
            if required:
                result["treefingerprint"] = self.tree(path)
                if not self.permission(path, value, not config.get("readOnly", False)):
                    raise PolicyError("SMB_FIXED_IDENTITY_PERMISSION_DENIED")
                result["status"] = "READY"
        elif required: raise PolicyError("SMB_FIXED_IDENTITY_PERMISSION_DENIED")
        if verify_current:
            from shared_directory_policy import smb_runtime_settings
            result["currentRuntime"] = smb_runtime_settings(row["name"], self.sources)
            current = policy(row.get("currentConfig", config))
            expected = self.names(instance, current) if current["mode"] == "FIXED" else ("", "")
            for source in result["currentRuntime"]:
                if (source["forceUser"], source["forceGroup"]) != expected:
                    raise PolicyError("SMB_FIXED_IDENTITY_RUNTIME_DRIFT")
        return result

    def ensure(self, payload):
        rows = payload.get("shares", [])
        requested = [(row, policy(row.get("config") or {})) for row in rows if row.get("state") not in INACTIVE]
        if not any(v["mode"] == "FIXED" for _, v in requested) and not self.registry.exists(): return {}
        result = {}
        with self.locked():
            state = self.read()
            for item in state["identities"].values(): item["references"] = []; item["state"] = "RETIRED"
            for row, value in requested:
                if value["mode"] != "FIXED": continue
                self.preview(row, payload["instanceUuid"], required=payload.get("enabled", True) and published(row))
                identity = self.inspect_identity(payload["instanceUuid"], value, state)
                key = identity["key"]; previous = state["identities"].get(key, {})
                item = {**previous, **identity, "instance": payload["instanceUuid"], "state": "RESERVED",
                        "references": previous.get("references", []) + [row["uuid"]]}
                item.pop("exists", None); item.pop("owned", None)
                state["identities"][key] = item
                self.write(state)  # ownership reservation precedes account writes
                try: grp.getgrnam(identity["group"])
                except KeyError: checked(["groupadd", "-g", str(value["gid"]), identity["group"]])
                try: pwd.getpwnam(identity["user"])
                except KeyError:
                    checked(["useradd", "-r", "-u", str(value["uid"]), "-g", str(value["gid"]), "-M", "-N", "-l",
                             "-d", "/nonexistent", "-s", "/usr/sbin/nologin", identity["user"]])
                self.inspect_identity(payload["instanceUuid"], value, state)
                item["state"] = "ACTIVE" if payload.get("enabled", True) and published(row) else "CREATED"
                self.write(state)
                result[row["uuid"]] = identity
            self.write(state)
        return result

    def verify(self, payload):
        for row in payload.get("shares", []):
            if row.get("state") in INACTIVE or policy(row.get("config") or {})["mode"] != "FIXED": continue
            identity = self.inspect_identity(payload["instanceUuid"], policy(row["config"]))
            if not identity["exists"] or not identity["owned"]: raise PolicyError("SMB_FIXED_IDENTITY_ACCOUNT_DRIFT")
            if not payload.get("enabled", True) or not published(row): continue
            self.preview(row, payload["instanceUuid"], required=payload.get("enabled", True) and published(row))
            from shared_directory_policy import smb_runtime_settings
            for source in smb_runtime_settings(row["name"], self.sources):
                for parameter, expected in [("force user", identity["user"]), ("force group", identity["group"]),
                                            ("admin users", ""), ("guest ok", "No"), ("inherit owner", "No")]:
                    actual = checked(["testparm", "-s", "--section-name=" + row["name"], "--parameter-name=" + parameter, source["config"]])
                    if actual.lower() != expected.lower(): raise PolicyError("SMB_FIXED_IDENTITY_RUNTIME_DRIFT")
                if checked(["testparm", "-s", "--section-name=" + row["name"], "--parameter-name=vfs objects", source["config"]]) != "full_audit":
                    raise PolicyError("SMB_FIXED_IDENTITY_AUDIT_REQUIRED")

    def observations(self, payload):
        values = []
        for row in payload.get("shares", []):
            if policy(row.get("config") or {})["mode"] != "FIXED": continue
            try:
                value = self.preview(row, payload["instanceUuid"], required=payload.get("enabled", True) and published(row))
                identity = value["identity"]
                value.update(shareuuid=row["uuid"], name=row.get("name"), observeduid=identity["uid"] if identity["exists"] and payload.get("enabled", True) and published(row) else None,
                             observedgid=identity["gid"] if identity["exists"] and payload.get("enabled", True) and published(row) else None)
                if payload.get("enabled", True) and published(row):
                    self.verify({**payload, "shares": [row]})
                values.append(value)
            except (PolicyError, OSError, ValueError) as e:
                values.append({"shareuuid": row["uuid"], "status": "UNKNOWN", "code": getattr(e, "code", "SMB_FIXED_IDENTITY_OBSERVATION_UNAVAILABLE")})
        return values


def render(identity):
    return ["   force user = " + identity["user"], "   force group = " + identity["group"], "   admin users =",
            "   vfs objects = full_audit",
            "   full_audit:prefix = %D|%U|%u|%g|%S|%I|%d",
            "   full_audit:success = " + AUDIT_SUCCESS,
            "   full_audit:failure = none", "   full_audit:syslog = false"]

def validate_directory_change(value):
    """Recheck only FIXED references before an explicit common POSIX mutation."""
    wanted = value["policy"]
    for row in value.get("smbShares", []):
        config = row.get("config") or {}
        ident = policy(config)
        if ident["mode"] != "FIXED" or not published(row):
            continue
        root = str(Path(config.get("volumeMountPath", "")) / wanted["canonicalpath"])
        if config.get("backingPath") != root:
            continue
        mode = int(wanted["mode"], 8)
        if mode & 0o2000:
            raise PolicyError("SMB_PARENT_IDENTITY_CONFLICT")
        acl = checked(["getfacl", "-cpn", root])
        entries = [line.split("#", 1)[0].strip().split(":") for line in acl.splitlines() if not line.startswith("default:")]
        def bits(s): return sum(n for c,n in zip(s, (4,2,1)) if c != "-")
        named = {(p[0],p[1]):bits(p[2]) for p in entries if len(p)==3 and p[1] and p[0] in ("user","group")}
        mask = (mode >> 3) & 7
        uid, gid = ident["uid"], ident["gid"]
        if uid == wanted["owneruid"]:
            granted = (mode >> 6) & 7
        elif ("user",str(uid)) in named:
            granted = named[("user",str(uid))] & mask
        else:
            group_bits = named.get(("group",str(gid)), 0)
            in_group = ("group",str(gid)) in named
            if gid == wanted["ownergid"]:
                group_bits |= next((bits(p[2]) for p in entries if p[:2]==["group",""]),mask) if named else mask
                in_group = True
            granted = group_bits & mask if in_group else mode & 7
        need = 5 if config.get("readOnly") else 7
        if granted & need != need:
            raise PolicyError("SMB_FIXED_IDENTITY_PERMISSION_DENIED")
