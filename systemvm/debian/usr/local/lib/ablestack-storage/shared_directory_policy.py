# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""One POSIX owner per canonical managed directory. Never creates or formats DATA."""
import contextlib
import hashlib
import json
import os
from pathlib import Path
import pwd
import grp
import re
import stat
import subprocess
import time

VERSION = 1
SMB_PARENT_INHERITANCE_VERSION = 1
REGISTRY = Path("/etc/ablestack-storage/desired-state/directory-policy.json")
MANAGED = Path("/srv/ablestack-storage/volumes")
LIMIT = 1024 * 1024


class PolicyError(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def checked(args, **kwargs):
    result = subprocess.run(args, capture_output=True, timeout=kwargs.pop("timeout", 30), **kwargs)
    if result.returncode:
        raise PolicyError("DIRECTORY_RUNTIME_COMMAND_FAILED")
    return result.stdout


def prepare_smb_ad_resolution(payload, netbios_name, config=Path("/etc/samba/smb.conf")):
    """Prepare NSS before applying AD ACLs, without starting the default SMB listener."""
    inactive = ("Disabled", "Destroyed", "Error")
    principals = {(acl.get("principalType"), acl.get("principal"))
                  for row in payload.get("shares", []) if row.get("state") not in inactive
                  for acl in row.get("acls", []) if acl.get("state") not in inactive
                  and acl.get("principalType") in ("AD_USER", "AD_GROUP")}
    if not payload.get("enabled", True) or not principals:
        return
    identity = payload.get("identityDomain") or {}
    domain = str(identity.get("domainName") or "")
    if identity.get("joinState") != "JOINED" or not domain:
        raise PolicyError("SMB_AD_RESOLVER_CONFIG_MISMATCH")

    def command(args, code, timeout=10):
        try:
            result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        except (OSError, subprocess.SubprocessError):
            raise PolicyError(code)
        if result.returncode:
            raise PolicyError(code)
        return result.stdout.strip()

    try:
        info = config.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022 or info.st_size > LIMIT:
            raise PolicyError("SMB_AD_RESOLVER_CONFIG_UNSAFE")
    except OSError:
        raise PolicyError("SMB_AD_RESOLVER_CONFIG_UNSAFE")
    expected = {"security": "ADS", "realm": domain, "netbios name": netbios_name}
    for parameter, value in expected.items():
        observed = command(["testparm", "-s", "--parameter-name=" + parameter, str(config)],
                           "SMB_AD_RESOLVER_CONFIG_MISMATCH")
        if observed.upper() != value.upper():
            raise PolicyError("SMB_AD_RESOLVER_CONFIG_MISMATCH")

    try:
        active = subprocess.run(["systemctl", "is-active", "--quiet", "winbind.service"],
                                capture_output=True, timeout=5).returncode == 0
    except (OSError, subprocess.SubprocessError):
        raise PolicyError("SMB_AD_RESOLVER_UNAVAILABLE")
    if not active:
        command(["systemctl", "start", "winbind.service"], "SMB_AD_RESOLVER_UNAVAILABLE", timeout=20)
    command(["wbinfo", "-t"], "SMB_AD_TRUST_UNAVAILABLE")
    deadline = time.monotonic() + 8
    pending = set(principals)
    while pending:
        for kind, name in list(pending):
            if not isinstance(name, str) or not name:
                raise PolicyError("DIRECTORY_PRINCIPAL_UNAVAILABLE")
            try:
                result = subprocess.run(["getent", "--", "passwd" if kind == "AD_USER" else "group", name],
                                        capture_output=True, text=True, timeout=min(2, max(.1, deadline - time.monotonic())))
            except (OSError, subprocess.SubprocessError):
                continue
            if result.returncode == 0 and result.stdout.strip():
                pending.remove((kind, name))
        if pending:
            if time.monotonic() >= deadline:
                raise PolicyError("DIRECTORY_PRINCIPAL_UNAVAILABLE")
            time.sleep(.2)


def canonical_hash(path):
    return hashlib.sha256(path.encode("utf-8")).hexdigest()


def fields(value):
    if not isinstance(value, dict) or set(value) - {"ownerUid", "ownerGid", "mode", "recursivePermission"}:
        raise PolicyError("DIRECTORY_POLICY_INVALID")
    result = {}
    for key, item in value.items():
        if key == "mode":
            if not re.fullmatch(r"0?[0-7]{3,4}", str(item)):
                raise PolicyError("DIRECTORY_POLICY_INVALID")
            result[key] = format(int(str(item), 8), "04o")
        elif key == "recursivePermission":
            if not isinstance(item, bool):
                raise PolicyError("DIRECTORY_POLICY_INVALID")
            result[key] = item
        else:
            if isinstance(item, bool) or not isinstance(item, int) or not 0 <= item <= 65535:
                raise PolicyError("DIRECTORY_POLICY_INVALID")
            result[key] = item
    return result


class DirectoryPolicies:
    def __init__(self, registry=REGISTRY, managed=MANAGED,
                 desired=Path("/etc/ablestack-storage/desired-state"),
                 configs=Path("/etc/ganesha/ablestack-storage"), refresh=None, protocol_check=None):
        self.registry, self.managed, self.desired, self.configs = map(Path, (registry, managed, desired, configs))
        self.refresh_hook = refresh
        self.protocol_check = protocol_check

    def read(self):
        if not self.registry.exists():
            return {}
        if self.registry.is_symlink() or self.registry.stat().st_size > LIMIT:
            raise PolicyError("DIRECTORY_REGISTRY_INVALID")
        value = json.loads(self.registry.read_text())
        if value.get("version") != VERSION or not isinstance(value.get("policies"), dict):
            raise PolicyError("DIRECTORY_REGISTRY_INVALID")
        return value["policies"]

    def write(self, policies):
        from configuration_transaction import atomic_json
        value = {"version": VERSION, "policies": policies}
        if len(json.dumps(value).encode()) > LIMIT:
            raise PolicyError("DIRECTORY_SNAPSHOT_LIMIT")
        atomic_json(self.registry, value)

    def observe(self, row):
        from configuration_transaction import nfs_posix_observe
        value = nfs_posix_observe(row, self.managed)
        if value["status"] == "OBSERVED":
            config = row["config"]
            mount = Path(config["volumeMountPath"]).resolve()
            path = Path(config["backingPath"]).resolve()
            info = path.stat()
            fs=checked(["findmnt","-n","-o","UUID,FSTYPE","--mountpoint",str(mount)]).decode().split()
            if len(fs)!=2: raise PolicyError("DIRECTORY_OBSERVATION_UNAVAILABLE")
            value.update(canonicalpath=str(path.relative_to(mount)), path=str(path),
                         volumeuuid=mount.name, filesystemuuid=fs[0],filesystem=fs[1],
                         inode=info.st_ino, acl=checked(["getfacl", "-cpn", str(path)]).decode())
        return value

    def inspect(self, rows):
        groups = {}
        for row in rows:
            observed = self.observe(row)
            if observed["status"] != "OBSERVED":
                key = "unavailable:" + str(row.get("uuid"))
            else:
                key = observed["volumeuuid"] + ":" + observed["canonicalpath"]
            if observed["status"]!="OBSERVED":
                config=row.get("config") or {}
                observed["path"]=config.get("backingPath") or row.get("path")
                if config.get("volumeMountPath"): observed["volumeuuid"]=Path(config["volumeMountPath"]).name
            entry = groups.setdefault(key, {**observed, "references": []})
            if observed.get("status") == "OBSERVED": entry["units"] = self.units(observed["path"])
            entry["references"].append({k: row.get(k) for k in ("uuid", "protocol", "name")})
        registry = self.read()
        for item in groups.values():
            matches = [p for p in registry.values() if p["volumeuuid"] == item.get("volumeuuid")
                       and p["canonicalpath"] == item.get("canonicalpath")]
            if matches:
                item["policy"] = matches[0]
        return list(groups.values())

    def policy_for(self, path):
        path = str(Path(path).resolve())
        matches = [p for p in self.read().values()
                   if str((self.managed / p["volumeuuid"] / p["canonicalpath"]).resolve()) == path]
        if not matches:
            return None
        p = matches[0]
        row = {"config": {"volumeMountPath": str(self.managed / p["volumeuuid"]), "backingPath": path,
                          "filesystemUuid": p["filesystemuuid"]}}
        observed = self.observe(row)
        if observed.get("inode") != p["inode"] or observed.get("filesystemuuid") != p["filesystemuuid"]:
            raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
        if (observed["owneruid"], observed["ownergid"], observed["mode"]) != (p["owneruid"], p["ownergid"], p["mode"]):
            raise PolicyError("DIRECTORY_POLICY_DRIFT")
        return p

    @staticmethod
    def mount_info(path, exact=False):
        result=subprocess.run(["findmnt","-J","-M" if exact else "-T",str(path),
                               "-o","TARGET,FSROOT,UUID"],capture_output=True,text=True,timeout=5)
        if result.returncode:
            if exact: return None
            raise PolicyError("DIRECTORY_MOUNT_OBSERVATION_UNAVAILABLE")
        try:
            rows=json.loads(result.stdout)["filesystems"]
            if len(rows)!=1: raise ValueError()
            row=rows[0]
            if not row.get("uuid") or not Path(row["fsroot"]).is_absolute() or ".." in Path(row["fsroot"]).parts:
                raise ValueError()
            row["target"]=str(Path(row["target"]).resolve())
            if exact and Path(row["target"])!=Path(path).resolve(): raise ValueError()
            return row
        except (ValueError,KeyError,TypeError):
            raise PolicyError("DIRECTORY_MOUNT_OBSERVATION_UNAVAILABLE")

    def export_mapping(self, path, mounts, cache):
        """Map a VFS bind alias to a verified managed volume, never by string alone."""
        path=Path(path)
        if path.is_symlink() or not path.is_dir(): return None
        export=path.resolve()
        if export not in cache: cache[export]=self.mount_info(export)
        observed=cache[export]
        alias_stat=export.stat()
        for volume,info,volume_stat in mounts:
            if observed["uuid"]!=info["uuid"] or alias_stat.st_dev!=volume_stat.st_dev:
                continue
            try:
                fs_relative=Path(observed["fsroot"]).relative_to(Path(info["fsroot"]))
                suffix=export.relative_to(Path(observed["target"]))
            except ValueError:
                continue
            physical=(volume/fs_relative/suffix).resolve()
            if not physical.is_relative_to(volume): continue
            try: actual=physical.stat()
            except FileNotFoundError: continue
            if (actual.st_dev,actual.st_ino)!=(alias_stat.st_dev,alias_stat.st_ino):
                continue
            return {"exportpath":str(export),"physicalpath":str(physical),
                    "volumeuuid":volume.name,"filesystemuuid":info["uuid"],
                    "mounttarget":observed["target"],"fsroot":observed["fsroot"],
                    "volumemount":str(volume),"volumefsroot":info["fsroot"],
                    "device":actual.st_dev,"inode":actual.st_ino}
        return None

    def units(self, path):
        canonical=Path(path).resolve()
        files=sorted(self.configs.glob("*.conf"))
        if not files: return []
        root=self.managed.resolve()
        try: relative=canonical.relative_to(root)
        except ValueError: raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
        if not relative.parts: raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
        selected=root/relative.parts[0]
        mounts=[]
        if root.is_dir():
            for volume in sorted(root.iterdir()):
                if volume.is_symlink() or not volume.is_dir(): continue
                info=self.mount_info(volume,exact=True)
                if info: mounts.append((volume.resolve(),info,volume.stat()))
        if not any(volume==selected for volume,_,_ in mounts):
            raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
        result,cache=[],{}
        for file in files:
            if file.is_symlink() or not re.fullmatch(r"[A-Za-z0-9_.-]+",file.stem):
                raise PolicyError("DIRECTORY_NFS_IDENTITY_CHANGED")
            text=file.read_text()
            raw_paths=re.findall(r'(?m)^\s*Path\s*=\s*("(?:[^"\\]|\\.)*")\s*;',text)
            mappings=[self.export_mapping(json.loads(raw),mounts,cache) for raw in raw_paths]
            mappings=[entry for entry in mappings if entry]
            if any(entry["volumemount"]==str(selected) and canonical.is_relative_to(Path(entry["physicalpath"]))
                   for entry in mappings):
                result.append({"unit":"ablestack-storage-ganesha@"+file.stem+".service",
                               "config":str(file),"sha256":canonical_hash(text),
                               "paths":[entry["physicalpath"] for entry in mappings],
                               "aliases":mappings,
                               "port":(re.findall(r"NFS_Port\s*=\s*(\d+)",text) or ["2049"])[0]})
        return result

    def unit_ready(self,item):
        if subprocess.run(["systemctl","is-active","--quiet",item["unit"]],capture_output=True,timeout=10).returncode:
            return False
        raw=checked(["systemctl","show","-p","MainPID","--value",item["unit"]],timeout=10).decode().strip()
        if not raw.isdecimal() or int(raw)<1: return False
        try: command=Path("/proc/"+raw+"/cmdline").read_bytes()
        except FileNotFoundError: return False
        if b"ganesha.nfsd" not in command or item["config"].encode() not in command:
            raise PolicyError("DIRECTORY_NFS_IDENTITY_CHANGED")
        rows=checked(["ss","-H","-lntp"],timeout=10).decode().splitlines()
        return any(len(row.split())>3 and row.split()[3].endswith(":"+str(item["port"]))
                   and ("pid="+raw+",") in row for row in rows)

    def restart_units(self,units):
        for item in units:
            checked(["systemctl","restart",item["unit"]],timeout=45)
            deadline=time.monotonic()+20
            while time.monotonic()<deadline:
                if self.unit_ready(item): break
                time.sleep(.2)
            else: raise PolicyError("DIRECTORY_NFS_NOT_READY")
            raw=checked(["systemctl","show","-p","MainPID","--value",item["unit"]],timeout=10).decode().strip()
            receipt=Path("/run/ablestack-storage/ganesha")/(Path(item["config"]).stem+".pid")
            if receipt.is_symlink(): raise PolicyError("DIRECTORY_NFS_IDENTITY_CHANGED")
            receipt.write_text(raw)

    def refresh(self, path):
        units = self.units(path)
        if self.refresh_hook:
            self.refresh_hook(units)
        else:
            self.restart_units(units)

    def requirements(self, rows, path):
        """Resolve existing SMB identities; no authentication/account mutation here."""
        result = {}
        for row in rows:
            config = row.get("config") or {}
            backing = config.get("backingPath") or row.get("path")
            if not backing or str(Path(backing).resolve()) != path or row.get("state") in ("Error", "Disabled", "Destroyed"):
                continue
            for acl in row.get("acls", []):
                if acl.get("state") in ("Error", "Disabled", "Destroyed"):
                    continue
                kind, name = acl.get("principalType"), acl.get("principal")
                try:
                    if kind in ("LOCAL_USER", "AD_USER"):
                        key = ("u", pwd.getpwnam(name).pw_uid)
                    elif kind in ("LOCAL_GROUP", "AD_GROUP"):
                        key = ("g", grp.getgrnam(name).gr_gid)
                    else:
                        raise PolicyError("DIRECTORY_PRINCIPAL_UNAVAILABLE")
                except KeyError:
                    raise PolicyError("DIRECTORY_PRINCIPAL_UNAVAILABLE")
                bits = 5 if acl.get("permission") == "READ_ONLY" else 7
                result[key] = result.get(key, 0) | bits
        return result

    @staticmethod
    def compatible(required, uid, mode):
        for (kind, identity), bits in required.items():
            mask = (mode >> 3) & 7
            if bits & ~mask:
                raise PolicyError("DIRECTORY_ACL_MASK_CONFLICT")

    @contextlib.contextmanager
    def opened(self, mount, relative, expected_inode=None):
        descriptor = os.open(mount, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            root_device = os.fstat(descriptor).st_dev
            for part in Path(relative).parts:
                if part in ("", ".", "..") or "/" in part:
                    raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
                child = os.open(part, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=descriptor)
                os.close(descriptor)
                descriptor = child
                if os.fstat(descriptor).st_dev != root_device:
                    raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
            info = os.fstat(descriptor)
            if expected_inode is not None and info.st_ino != expected_inode:
                raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
            yield descriptor
        finally:
            os.close(descriptor)

    def prepare(self, value):
        policy = dict(value["policy"])
        change = fields(value.get("changes") or {})
        row = {"config": {"volumeMountPath": str(self.managed / policy["volumeuuid"]),
                          "backingPath": str(self.managed / policy["volumeuuid"] / policy["canonicalpath"]),
                          "filesystemUuid": policy["filesystemuuid"]}}
        observed = self.observe(row)
        if observed["status"] != "OBSERVED" or observed.get("fingerprint") != value.get("expectedObservation"):
            raise PolicyError("DIRECTORY_POLICY_STALE")
        if observed["inode"] != policy["inode"]:
            raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
        previous = self.read()
        same = [p for p in previous.values() if p["volumeuuid"] == policy["volumeuuid"] and p["canonicalpath"] == policy["canonicalpath"]]
        if value["action"] == "ADOPT":
            if same:
                raise PolicyError("DIRECTORY_POLICY_CONFLICT")
            if change or (policy["owneruid"], policy["ownergid"], policy["mode"]) != (
                    observed["owneruid"], observed["ownergid"], observed["mode"]):
                raise PolicyError("DIRECTORY_POLICY_INVALID")
        elif value["action"] == "UPDATE":
            existing = previous.get(policy["uuid"])
            if not existing or existing["revision"] != value.get("expectedPolicyRevision") or policy["revision"] != existing["revision"] + 1:
                raise PolicyError("DIRECTORY_POLICY_STALE")
            if any(existing.get(k)!=policy.get(k) for k in ("volumeuuid","canonicalpath","filesystemuuid","inode")):
                raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
            if int(policy["mode"],8) & 0o2000 and not int(observed["mode"],8) & 0o2000:
                for row in value.get("smbShares",[]):
                    config=row.get("config") or {}
                    if row.get("state") in ("Disabled", "Destroyed", "Error"):
                        continue
                    if str(Path(config.get("backingPath") or row.get("path") or "/").resolve()) == observed["path"]:
                        inheritance_conflict(config,policy["mode"])
                        for actual in smb_runtime_settings(row["name"]):
                            inheritance_conflict(actual,policy["mode"])
            self.compatible(self.requirements(value.get("smbShares", []), observed["path"]),
                            policy["owneruid"], int(policy["mode"], 8))
        else:
            raise PolicyError("DIRECTORY_POLICY_INVALID")
        expected=(change.get("ownerUid",observed["owneruid"]), change.get("ownerGid",observed["ownergid"]),
                  change.get("mode",observed["mode"]))
        if (policy["owneruid"],policy["ownergid"],policy["mode"]) != expected:
            raise PolicyError("DIRECTORY_POLICY_INVALID")
        units = self.units(observed["path"])
        if value.get("units") != units:
            raise PolicyError("DIRECTORY_POLICY_STALE")
        if value["action"]=="UPDATE":
            self.verify_protocols(value)
        return policy, observed, change

    def snapshot(self, value):
        original=value
        policy, observed, change = self.prepare(value)
        root = Path(observed["path"])
        paths = [root]
        if change.get("recursivePermission"):
            # Caller admission also requires a drained endpoint set.
            for parent, directories, files in os.walk(root, followlinks=False):
                for name in directories + files:
                    p = Path(parent) / name
                    if p.is_symlink() or p.stat().st_dev != root.stat().st_dev:
                        raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
                    paths.append(p)
                if len(paths) > 2048:
                    raise PolicyError("DIRECTORY_SNAPSHOT_LIMIT")
        entries, seen = [], set()
        mount = self.managed / policy["volumeuuid"]
        for path in sorted(paths,key=lambda p:str(p.relative_to(mount))):
            info = path.lstat()
            if (info.st_dev, info.st_ino) in seen:
                continue
            seen.add((info.st_dev, info.st_ino))
            relative = str(path.relative_to(mount))
            with self.opened(mount, relative, info.st_ino) as fd:
                acl = checked(["getfacl", "-cpn", "/proc/self/fd/" + str(fd)], pass_fds=(fd,)).decode()
                entries.append({"relative": relative, "inode": info.st_ino, "uid": info.st_uid, "gid": info.st_gid,
                                "mode": stat.S_IMODE(info.st_mode), "acl": acl})
            if len(json.dumps(entries).encode()) > LIMIT:
                raise PolicyError("DIRECTORY_SNAPSHOT_LIMIT")
        value = {"policy": policy, "entries": entries, "registry": self.read(),
                 "registryExisted": self.registry.exists(), "action": value["action"]}
        if len(json.dumps(value).encode()) > LIMIT:
            raise PolicyError("DIRECTORY_SNAPSHOT_LIMIT")
        value["treefingerprint"]=canonical_hash(json.dumps(entries,sort_keys=True))
        if value.get("action")=="UPDATE" and value.get("policy") and value["entries"]:
            expected=original.get("expectedTreeFingerprint") if original else None
            if expected and expected!=value["treefingerprint"]:
                raise PolicyError("DIRECTORY_POLICY_STALE")
        if len(json.dumps(value).encode())>LIMIT:
            raise PolicyError("DIRECTORY_SNAPSHOT_LIMIT")
        return value

    @staticmethod
    def acl_impact(value,before):
        changes=value.get("changes",{})
        rows=[]
        if "mode" not in changes and not changes.get("recursivePermission"):
            return {"changes":[],"count":0,"truncated":False}
        target=int(value["policy"]["mode"],8)
        def perms(bits):
            return "".join(c if bits & n else "-" for c,n in zip("rwx",(4,2,1)))
        for entry in before["entries"]:
            old_mask=(entry["mode"]>>3)&7;new_mask=(target>>3)&7
            for line in entry["acl"].splitlines():
                raw=line.split("#",1)[0].strip().split(":")
                if len(raw)!=3 or raw[0] not in ("user","group") or not raw[1]:
                    continue
                bits=sum(n for c,n in zip(raw[2],(4,2,1)) if c!="-")
                if bits&old_mask != bits&new_mask:
                    rows.append({"path":entry["relative"],"principal":raw[0]+":"+raw[1],
                                 "before":perms(bits&old_mask),"after":perms(bits&new_mask)})
        return {"changes":rows[:40],"count":len(rows),"truncated":len(rows)>40}

    def apply(self, value, before, checkpoint=lambda count: None):
        policy, observed, change = self.prepare(value)
        if change.get("recursivePermission"):
            change={**change,"ownerUid":policy["owneruid"],"ownerGid":policy["ownergid"],"mode":policy["mode"]}
        mount = self.managed / policy["volumeuuid"]
        if value["action"] == "UPDATE":
            for index, entry in enumerate(before["entries"]):
                with self.opened(mount, entry["relative"], entry["inode"]) as fd:
                    current=os.fstat(fd)
                    acl=checked(["getfacl","-cpn","/proc/self/fd/"+str(fd)],pass_fds=(fd,)).decode()
                    if (current.st_uid,current.st_gid,stat.S_IMODE(current.st_mode),acl) != (entry["uid"],entry["gid"],entry["mode"],entry["acl"]):
                        raise PolicyError("DIRECTORY_POLICY_STALE")
                    target_uid=policy["owneruid"] if "ownerUid" in change else entry["uid"]
                    target_gid=policy["ownergid"] if "ownerGid" in change else entry["gid"]
                    target_mode=int(policy["mode"],8) if "mode" in change else entry["mode"]
                    if (target_uid,target_gid,target_mode)==(current.st_uid,current.st_gid,stat.S_IMODE(current.st_mode)):
                        continue
                    checkpoint(index+1)  # durable intent precedes each owned mutation
                    if (target_uid,target_gid)!=(current.st_uid,current.st_gid):
                        os.fchown(fd,target_uid,target_gid)
                    os.fchmod(fd,target_mode)
        policies = self.read()
        policies[policy["uuid"]] = {**policies.get(policy["uuid"],{}),**policy}
        self.write(policies)
        if value["action"] == "UPDATE":
            self.refresh(observed["path"])
        self.verify(value,before)

    def verify_protocols(self,value):
        if self.protocol_check:
            self.protocol_check(value)
            return
        for unit in value.get("units",[]):
            if not self.unit_ready(unit): raise PolicyError("DIRECTORY_NFS_NOT_READY")
        policy=value["policy"]
        path=str(self.managed/policy["volumeuuid"]/policy["canonicalpath"])
        try: nfs=json.loads((self.desired/"nfs-export-apply.json").read_text())
        except FileNotFoundError: nfs={}
        nfs_expected=nfs.get("enabled",True) and any(
            str(Path((r.get("config") or {}).get("backingPath") or r.get("path") or "/").resolve())==path
            and r.get("state") not in ("Error","Disabled","Destroyed") and r.get("acls")
            for r in nfs.get("exports",[]))
        if nfs_expected and not value.get("units"):
            raise PolicyError("DIRECTORY_NFS_NOT_READY")
        policy=value["policy"]
        path=str(self.managed/policy["volumeuuid"]/policy["canonicalpath"])
        smb=any(str(Path((r.get("config") or {}).get("backingPath") or r.get("path") or "/").resolve())==path
                and r.get("state") not in ("Error","Disabled","Destroyed") for r in value.get("smbShares",[]))
        try: smb_desired=json.loads((self.desired/"smb-share-apply.json").read_text())
        except FileNotFoundError: smb_desired={}
        if smb and smb_desired.get("enabled",True):
            from configuration_transaction import SmbListenerProfiles
            listeners=SmbListenerProfiles().show().get("listeners",[])
            if not listeners or any(v.get("state") not in ("READY","COVERED") for v in listeners):
                raise PolicyError("DIRECTORY_SMB_NOT_READY")

    def verify(self, value, before=None):
        self.verify_protocols(value)
        policy = value["policy"]
        saved = self.read().get(policy["uuid"])
        if not saved or any(saved.get(k)!=v for k,v in policy.items()):
            raise PolicyError("DIRECTORY_POLICY_VERIFY_FAILED")
        observed = self.observe({"config": {"volumeMountPath": str(self.managed / policy["volumeuuid"]),
                                 "backingPath": str(self.managed / policy["volumeuuid"] / policy["canonicalpath"]),
                                 "filesystemUuid": policy["filesystemuuid"]}})
        if (observed.get("inode"), observed.get("owneruid"), observed.get("ownergid"), observed.get("mode")) != (
                policy["inode"], policy["owneruid"], policy["ownergid"], policy["mode"]):
            raise PolicyError("DIRECTORY_POLICY_VERIFY_FAILED")
        if before is not None:
            change=value.get("changes",{})
            if change.get("recursivePermission"):
                change={**change,"ownerUid":policy["owneruid"],"ownerGid":policy["ownergid"],"mode":policy["mode"]}
            for item in before["entries"]:
                with self.opened(self.managed/policy["volumeuuid"],item["relative"],item["inode"]) as fd:
                    info=os.fstat(fd)
                    expected=(policy["owneruid"] if "ownerUid" in change else item["uid"],
                              policy["ownergid"] if "ownerGid" in change else item["gid"],
                              int(policy["mode"],8) if "mode" in change else item["mode"])
                    if (info.st_uid,info.st_gid,stat.S_IMODE(info.st_mode))!=expected:
                        raise PolicyError("DIRECTORY_POLICY_VERIFY_FAILED")

            if (value.get("changes") or {}).get("recursivePermission"):
                actual=set()
                root=self.managed/policy["volumeuuid"]/policy["canonicalpath"]
                for parent,dirs,files in os.walk(root,followlinks=False):
                    for item in [Path(parent)]+[Path(parent)/n for n in dirs+files]:
                        if item.is_symlink(): raise PolicyError("DIRECTORY_IDENTITY_CHANGED")
                        actual.add(item.stat().st_ino)
                if actual!={entry["inode"] for entry in before["entries"]}:
                    raise PolicyError("DIRECTORY_TREE_CHANGED")

    def restore(self, before, changed_count=0):
        policy = before["policy"]
        mount = self.managed / policy["volumeuuid"]
        for entry in reversed(before["entries"][:changed_count]):
            with self.opened(mount, entry["relative"], entry["inode"]) as fd:
                os.fchown(fd, entry["uid"], entry["gid"])
                checked(["setfacl", "-k", "/proc/self/fd/" + str(fd)],pass_fds=(fd,))
                checked(["setfacl", "--set-file=-", "/proc/self/fd/" + str(fd)],
                        input=entry["acl"].encode(), pass_fds=(fd,))
                os.fchmod(fd, entry["mode"])
        if before.get("registryExisted",True):
            self.write(before["registry"])
        else:
            with contextlib.suppress(FileNotFoundError):
                self.registry.unlink()
        if changed_count:
            self.refresh(str(mount / policy["canonicalpath"]))
        self.verify_restored(before)

    def verify_restored(self, before):
        if self.read() != before["registry"]:
            raise PolicyError("DIRECTORY_POLICY_RESTORE_FAILED")
        mount = self.managed / before["policy"]["volumeuuid"]
        for entry in before["entries"]:
            with self.opened(mount, entry["relative"], entry["inode"]) as fd:
                info = os.fstat(fd)
                acl = checked(["getfacl", "-cpn", "/proc/self/fd/" + str(fd)], pass_fds=(fd,)).decode()
                if (info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode), acl) != (
                        entry["uid"], entry["gid"], entry["mode"], entry["acl"]):
                    raise PolicyError("DIRECTORY_POLICY_RESTORE_FAILED")



    def protected(self, path, smb=None):
        policy=self.policy_for(path)
        if policy: return policy
        try:
            nfs=json.loads((self.desired/"nfs-export-apply.json").read_text())
        except FileNotFoundError:
            return None
        def paths(rows):
            return {str(Path((r.get("config") or {}).get("backingPath") or r.get("path")).resolve())
                    for r in rows if (r.get("config") or {}).get("backingPath") or r.get("path")}
        if str(Path(path).resolve()) not in paths(nfs.get("exports",[])):
            return None
        if smb is None:
            try: smb=json.loads((self.desired/"smb-share-apply.json").read_text())
            except FileNotFoundError: smb={}
        return {"legacy":True} if str(Path(path).resolve()) in paths(smb.get("shares",[])) else None

    def refresh_many(self, paths):
        if self.refresh_hook:
            units={u["unit"]:u for path in paths for u in self.units(path)}
            self.refresh_hook(list(units.values()))
            return
        units={u["unit"]:u for path in paths for u in self.units(path)}
        self.restart_units(list(units.values()))

    def apply_acl_batch(self, rows, paths):
        plans=[]
        for path in sorted(set(paths)):
            policy=self.policy_for(path)
            if not policy: continue
            required=self.requirements(rows,path)
            self.compatible(required,policy["owneruid"],int(policy["mode"],8))
            acl=checked(["getfacl","-cpn",path]).decode()
            current,defaults={},{}
            for line in acl.splitlines():
                if line.startswith(("user:","group:","default:user:","default:group:")):
                    raw=line.split("\t",1)[0].split("#",1)[0].strip()
                    parts=raw.split(":")
                    if len(parts)==3 and parts[1]:current[parts[0][0]+":"+parts[1]]=parts[2]
                    if len(parts)==4 and parts[2]:defaults[parts[1][0]+":"+parts[2]]=parts[3]
            old=policy.get("ownedacl",{})
            for key,bits in old.items():
                if key not in current or current[key]!=bits or defaults.get(key)!=bits:
                    raise PolicyError("DIRECTORY_ACL_DRIFT")
            mask=(int(policy["mode"],8)>>3)&7
            default_mask=next((line.split(":")[-1] for line in acl.splitlines() if line.startswith("default:mask:")),None)
            def bits(text): return sum(v for c,v in zip(text,(4,2,1)) if c!="-")
            if default_mask is not None and any(n & ~bits(default_mask) for n in required.values()):
                raise PolicyError("DIRECTORY_ACL_MASK_CONFLICT")
            plans.append((path,policy,required,old,default_mask,mask))
        updated=self.read()
        changed=[]
        for path,policy,required,old,default_mask,mask in plans:
            mount=self.managed/policy["volumeuuid"]
            with self.opened(mount,policy["canonicalpath"],policy["inode"]) as fd:
                target="/proc/self/fd/"+str(fd)
                desired={kind+":"+str(identity):("r" if n&4 else "-")+("w" if n&2 else "-")+("x" if n&1 else "-") for (kind,identity),n in required.items()}
                if old==desired:
                    continue  # render/boot with the same ACL does not mutate POSIX
                for key in old.keys()-desired.keys():
                    checked(["setfacl","-n","-x",key,target],pass_fds=(fd,))
                    checked(["setfacl","-n","-d","-x",key,target],pass_fds=(fd,))
                for key,value in desired.items():
                    checked(["setfacl","-n","-m",key+":"+value,target],pass_fds=(fd,))
                    opts=key+":"+value
                    if default_mask is None:
                        perms=("r" if mask&4 else "-")+("w" if mask&2 else "-")+("x" if mask&1 else "-")
                        opts+=",m::"+perms
                    checked(["setfacl","-n","-d","-m",opts,target],pass_fds=(fd,))
                if stat.S_IMODE(os.fstat(fd).st_mode)!=int(policy["mode"],8):
                    raise PolicyError("DIRECTORY_POLICY_DRIFT")
            updated[policy["uuid"]]={**updated[policy["uuid"]],"ownedacl":desired}
            if old!=desired: changed.append(path)
        if changed: self.write(updated)
        self.refresh_many(changed)
        return len(plans)

def policy_for(path):
    return DirectoryPolicies().policy_for(path)


def main():
    import sys
    if len(sys.argv)!=3 or sys.argv[1] not in ("inspect","preview"):
        raise PolicyError("DIRECTORY_COMMAND_INVALID")
    source=Path(sys.argv[2])
    if source.stat().st_size>256*1024:
        raise PolicyError("PAYLOAD_LIMIT")
    value=json.loads(source.read_text())
    engine=DirectoryPolicies()
    if sys.argv[1]=="inspect":
        result={"directories":engine.inspect(value.get("shares",[])),"sharedDirectoryPolicyVersion":VERSION}
    else:
        before=engine.snapshot(value)
        result={"success":True,"entrycount":len(before["entries"]),"snapshotbytes":len(json.dumps(before).encode()),
                "units":value.get("units",[]),"references":value.get("references",[]),
                "treefingerprint":before["treefingerprint"],"aclimpact":engine.acl_impact(value,before),
                "before":{"owneruid":before["entries"][0]["uid"],"ownergid":before["entries"][0]["gid"],
                          "mode":format(before["entries"][0]["mode"],"04o")},"after":value["policy"]}
    print(json.dumps(result))


def inheritance_enabled(config):
    value = config.get("inheritOwner", False)
    if not isinstance(value, bool):
        raise PolicyError("SMB_INHERIT_OWNER_INVALID")
    return value


def inheritance_conflict(config, mode):
    if (inheritance_enabled(config) or int(mode, 8) & 0o2000) and any(
            str(config.get(key) or "").strip() for key in ("forceUser", "forceGroup")):
        raise PolicyError("SMB_PARENT_IDENTITY_CONFLICT")


def inheritance_protection_version(updater=None, minimum=5):
    """Inspect the trusted baseline, not executable candidate code."""
    import ast
    # The updater belongs to the protected SystemVM baseline, outside signed releases.
    path = Path(updater) if updater else Path("/usr/local/lib/ablestack-storage/runtime_updater.py")
    try:
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022 or info.st_size > LIMIT:
            return 0
        tree = ast.parse(path.read_text())
        value = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign)
                     and any(isinstance(t, ast.Name) and t.id == "UPDATER_VERSION" for t in n.targets))
        return 1 if isinstance(value, int) and value >= minimum else 0
    except (OSError, ValueError, SyntaxError, StopIteration):
        return 0


def smb_runtime_settings(name, sources=None):
    """Read only owned daemon configurations; optional sources support private native fixtures."""
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", str(name)):
        raise PolicyError("SMB_INHERITANCE_NAME_INVALID")
    if sources is None:
        from configuration_transaction import SmbListenerProfiles
        profiles = SmbListenerProfiles()
        if profiles.manifest.exists():
            state = json.loads(profiles.manifest.read_text())
            sources = []
            for row in state.get("profiles", {}).values():
                if row.get("kind") == "covered":
                    continue
                pid = profiles.pid(row)
                if not pid:
                    raise PolicyError("SMB_INHERITANCE_OBSERVATION_UNAVAILABLE")
                sources.append((profiles.base if row.get("kind") == "legacy" else profiles.root / (row["key"] + ".conf"), pid))
        else:
            q = subprocess.run(["systemctl", "show", "smbd.service", "-p", "MainPID", "--value"],
                               capture_output=True, text=True, timeout=10)
            pid = int(q.stdout.strip() or 0)
            sources = [(profiles.base, pid)] if pid else []
    result = []
    for source, pid in sources:
        source = Path(source)
        if source.is_symlink() or not source.is_file() or pid <= 1:
            raise PolicyError("SMB_INHERITANCE_OBSERVATION_UNAVAILABLE")
        try:
            if Path("/proc/%d/exe" % pid).resolve() != Path("/usr/sbin/smbd").resolve():
                raise ValueError()
            args = Path("/proc/%d/cmdline" % pid).read_bytes().split(b"\0")
            if source != Path("/etc/samba/smb.conf") and str(source).encode() not in args and (
                    "--configfile=" + str(source)).encode() not in args:
                raise ValueError()
        except (OSError, ValueError):
            raise PolicyError("SMB_INHERITANCE_OBSERVATION_UNAVAILABLE")
        values = {}
        for parameter, key in (("inherit owner", "inheritowner"), ("force user", "forceUser"), ("force group", "forceGroup")):
            output = checked(["testparm", "-s", "--section-name=" + name, "--parameter-name=" + parameter, str(source)]).decode().strip()
            values[key] = output[:256]
        values.update(pid=pid, config=str(source))
        result.append(values)
    return result


def inheritance_preview(target, policy, requested, sources=None):
    engine = DirectoryPolicies()
    observed = engine.observe(target)
    if observed.get("status") != "OBSERVED":
        raise PolicyError("SMB_PARENT_OBSERVATION_UNAVAILABLE")
    stored = engine.policy_for(observed["path"])
    if not stored or stored["uuid"] != policy.get("uuid"):
        raise PolicyError("DIRECTORY_POLICY_REQUIRED")
    if any(observed.get(k) != policy.get(k) for k in ("owneruid", "ownergid", "mode", "inode", "filesystemuuid")):
        raise PolicyError("DIRECTORY_POLICY_STALE")
    config = dict(target.get("config") or {})
    enabled = requested["inheritOwner"]
    config["inheritOwner"] = enabled
    inheritance_conflict(config, observed["mode"])
    if enabled and inheritance_protection_version() != 1:
        raise PolicyError("SMB_PARENT_INHERITANCE_PROTECTION_REQUIRED")
    actual = [] if target.get("uuid") == "NEW" else smb_runtime_settings(target["name"], sources)
    for entry in actual:
        inheritance_conflict({**entry, "inheritOwner": enabled}, observed["mode"])
    parent = {k: observed[k] for k in ("owneruid", "ownergid", "mode", "inode", "filesystemuuid", "volumeuuid", "canonicalpath", "fingerprint")}
    return {"inheritowner": enabled, "parent": parent,
            "futureowner": "PARENT" if enabled else "CREATOR",
            "futuregroup": "PARENT" if int(observed["mode"], 8) & 0o2000 else "CREATOR",
            "setgid": bool(int(observed["mode"], 8) & 0o2000),
            "createmask": smb_creation_modes(config)["createMask"], "directorymask": smb_creation_modes(config)["directoryMask"], "existingobjectschange": False, "runtime": actual}


def validate_inheritance_share(row, payload=None):
    config = row.get("config") or {}
    if not inheritance_enabled(config):
        return
    if inheritance_protection_version() != 1:
        raise PolicyError("SMB_PARENT_INHERITANCE_PROTECTION_REQUIRED")
    path = config.get("backingPath") or row.get("path")
    policy = DirectoryPolicies().policy_for(path)
    if not policy or config.get("directoryPolicyUuid") != policy["uuid"]:
        raise PolicyError("DIRECTORY_POLICY_REQUIRED")
    inheritance_conflict(config, policy["mode"])


def verify_smb_inheritance(payload, sources=None):
    for row in payload.get("shares", []):
        config = row.get("config") or {}
        if "inheritOwner" not in config or row.get("state") in ("Disabled", "Destroyed", "Error"):
            continue
        validate_inheritance_share(row, payload)
        expected = inheritance_enabled(config)
        values = smb_runtime_settings(row["name"], sources)
        if not values or any((v["inheritowner"].lower() in ("yes", "windows and unix")) != expected for v in values):
            raise PolicyError("SMB_INHERITANCE_VERIFY_FAILED")


def inheritance_observations(payload):
    result = []
    for row in payload.get("shares", []):
        config = row.get("config") or {}
        if "inheritOwner" not in config or row.get("state") in ("Disabled", "Destroyed", "Error"):
            continue
        value = {"uuid": row.get("uuid")}
        try:
            value["inheritowner"] = inheritance_enabled(config)
            path = config.get("backingPath") or row.get("path")
            observed = DirectoryPolicies().observe(row)
            value["parent"] = {k: observed.get(k) for k in ("owneruid", "ownergid", "mode", "inode", "status")}
            value["runtime"] = smb_runtime_settings(row["name"])
            value["status"] = "APPLIED" if value["runtime"] and all(
                (v["inheritowner"].lower() in ("yes", "windows and unix")) == value["inheritowner"] for v in value["runtime"]) else "UNKNOWN"
        except (PolicyError, OSError, ValueError) as error:
            value.update(status="UNKNOWN", code=getattr(error, "code", "SMB_PARENT_OBSERVATION_UNAVAILABLE"))
        result.append(value)
    return result


SMB_CREATION_POLICY_VERSION = 1
SMB_MODE_DEFAULTS = {"createMask": "0660", "directoryMask": "0770",
                     "forceCreateMode": "0000", "forceDirectoryMode": "0000"}
SMB_OPTION_DEFAULTS = {"readOnly": False, "browseable": True, "inheritOwner": False}
SMB_OPTION_NAMES = {"createMask": "create mask", "directoryMask": "directory mask",
                    "forceCreateMode": "force create mode", "forceDirectoryMode": "force directory mode",
                    "readOnly": "read only", "browseable": "browseable", "inheritOwner": "inherit owner"}


def smb_creation_modes(config):
    result = {}
    for key, default in SMB_MODE_DEFAULTS.items():
        value = config.get(key, default)
        if not isinstance(value, str) or not re.fullmatch(r"0?[0-7]{3}", value):
            raise PolicyError("SMB_CREATION_MODE_INVALID")
        result[key] = format(int(value, 8), "04o")
    for force, mask in (("forceCreateMode", "createMask"), ("forceDirectoryMode", "directoryMask")):
        if int(result[force], 8) & ~int(result[mask], 8):
            raise PolicyError("SMB_FORCE_MODE_OUTSIDE_MASK")
    return result


def smb_creation_custom(config):
    return smb_creation_modes(config) != SMB_MODE_DEFAULTS


def smb_creation_protected():
    return inheritance_protection_version(minimum=7) == 1


def smb_share_options(config):
    result = smb_creation_modes(config)
    for key, default in SMB_OPTION_DEFAULTS.items():
        value = config.get(key, default)
        if not isinstance(value, bool):
            raise PolicyError("SMB_SHARE_OPTION_INVALID")
        result[key] = value
    return result


def smb_acl_policy_without_observation(row):
    """The credential-supplied indicator is UI metadata, never an ACL grant."""
    for acl in row.get("acls", []):
        (acl.get("config") or {}).pop("passwordSupplied", None)


def smb_options_difference(before, candidate):
    """A strict semantic diff, independent of a caller's options-only assertion."""
    import copy
    def canonical(value):
        value = copy.deepcopy(value)
        for key in ("listenerOnly", "configurationOperationId", "releaseAddresses", "listenerBefore", "listenerOriginalNetwork"):
            value.pop(key, None)
        for listener in value.get("listeners", []):
            if listener.get("state") in ("Ready", "Updating", "Creating", "Allocated"):
                listener["state"] = "Ready"
        # These two fields are observations/job display data, never service policy.
        identity = value.get("identityDomain") or {}
        identity.pop("healthState", None)
        (identity.get("config") or {}).pop("adJoin", None)
        rows = value.get("shares", [])
        if not isinstance(rows, list):
            raise PolicyError("INVALID_SMB_OPTIONS_INTENT")
        keyed = {}
        for row in rows:
            key = row.get("uuid")
            if not key or key in keyed:
                raise PolicyError("INVALID_SMB_OPTIONS_INTENT")
            if row.get("state") in ("Ready", "Updating"):
                row["state"] = "Ready"
            smb_acl_policy_without_observation(row)
            keyed[key] = row
        value["shares"] = keyed
        return value
    left, right = canonical(before), canonical(candidate)
    changed = []
    if set(left["shares"]) != set(right["shares"]):
        raise PolicyError("INVALID_SMB_OPTIONS_INTENT")
    for key in left["shares"]:
        a, b = left["shares"][key], right["shares"][key]
        old, new = smb_share_options(a.get("config") or {}), smb_share_options(b.get("config") or {})
        for row in (a, b):
            row.setdefault("config", {})
            for field in SMB_OPTION_NAMES:
                row["config"].pop(field, None)
        if old != new:
            if a.get("state") != "Ready" or b.get("state") != "Ready":
                raise PolicyError("INVALID_SMB_OPTIONS_INTENT")
            changed.append({"uuid": key, "name": a.get("name"), "before": old, "after": new,
                            "row": before["shares"][next(i for i, x in enumerate(before["shares"]) if x["uuid"] == key)]})
    if left != right or len(changed) > 1:
        raise PolicyError("INVALID_SMB_OPTIONS_INTENT")
    return changed[0] if changed else None


class SmbCreationPolicies:
    """Only owned config bytes and targeted reloads. Never mutates accounts or DATA."""
    def __init__(self, base=Path("/etc/samba/smb.conf"),
                 desired=Path("/etc/ablestack-storage/desired-state/smb-share-apply.json"), profiles=None):
        self.base, self.desired, self.private_profiles = Path(base), Path(desired), profiles

    @staticmethod
    def read_file(path):
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        except OSError:
            raise PolicyError("SMB_OPTIONS_FILE_UNSAFE")
        try:
            info = os.fstat(descriptor)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1 or info.st_mode & 0o022 or info.st_size > LIMIT:
                raise PolicyError("SMB_OPTIONS_FILE_UNSAFE")
            data = os.read(descriptor, LIMIT + 1)
            if len(data) > LIMIT:
                raise PolicyError("SMB_OPTIONS_FILE_UNSAFE")
            return data, {"uid": info.st_uid, "gid": info.st_gid, "mode": stat.S_IMODE(info.st_mode)}
        finally:
            os.close(descriptor)

    def profiles(self):
        if self.private_profiles is not None:
            sources = self.private_profiles
        else:
            from configuration_transaction import SmbListenerProfiles
            engine = SmbListenerProfiles()
            if engine.manifest.exists():
                manifest = json.loads(self.read_file(engine.manifest)[0])
                if manifest.get("pending"):
                    raise PolicyError("SMB_OPTIONS_TOPOLOGY_PENDING")
                sources = []
                for row in manifest.get("profiles", {}).values():
                    if row.get("kind") == "covered":
                        continue
                    if not engine.ready(row, manifest["profiles"]):
                        raise PolicyError("SMB_OPTIONS_LISTENER_UNAVAILABLE")
                    sources.append((engine.base if row.get("kind") == "legacy" else engine.root / (row["key"] + ".conf"), engine.pid(row)))
            else:
                value = checked(["systemctl", "show", "smbd.service", "-p", "MainPID", "--value"])
                sources = [(self.base, int(value.strip() or 0))]
        result = []
        for config, pid in sources:
            config, pid = Path(config), int(pid or 0)
            raw, _ = self.read_file(config)
            if pid <= 1 or Path("/proc/%d/exe" % pid).resolve() != Path("/usr/sbin/smbd").resolve():
                raise PolicyError("SMB_OPTIONS_PROCESS_CHANGED")
            args = Path("/proc/%d/cmdline" % pid).read_bytes().split(b"\0")
            if config != Path("/etc/samba/smb.conf") and str(config).encode() not in args and ("--configfile=" + str(config)).encode() not in args:
                raise PolicyError("SMB_OPTIONS_PROCESS_CHANGED")
            start = Path("/proc/%d/stat" % pid).read_text().rsplit(")", 1)[1].split()[19]
            ports = checked(["testparm", "-s", "--parameter-name=smb ports", str(config)]).decode().strip()
            result.append({"ports": ports, "config": str(config), "pid": pid, "starttime": start,
                           "overlayhash": hashlib.sha256(raw).hexdigest() if config != self.base else None})
        if not result:
            raise PolicyError("SMB_OPTIONS_LISTENER_UNAVAILABLE")
        return sorted(result, key=lambda v: v["config"])

    def settings(self, name, profiles=None):
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", str(name)):
            raise PolicyError("SMB_CREATION_NAME_INVALID")
        values = []
        for profile in profiles or self.profiles():
            options = {}
            for key, parameter in list(SMB_OPTION_NAMES.items()) + [("inheritPermissions", "inherit permissions"), ("forceUser", "force user"), ("forceGroup", "force group")]:
                raw = checked(["testparm", "-s", "--section-name=" + name, "--parameter-name=" + parameter,
                               profile["config"]]).decode().strip()
                options[key] = (raw.lower() in ("yes", "true", "windows and unix")) if key in SMB_OPTION_DEFAULTS or key == "inheritPermissions" else raw if key in ("forceUser", "forceGroup") else format(int(raw, 8), "04o")
            values.append({**profile, **options})
        return values

    def preview(self, row, changes):
        current = smb_share_options(row.get("config") or {})
        wanted = dict(current)
        wanted.update(changes)
        wanted = smb_share_options(wanted)
        if (set(changes) & set(SMB_MODE_DEFAULTS) or smb_creation_custom(wanted)) and not smb_creation_protected():
            raise PolicyError("SMB_CREATION_POLICY_PROTECTION_REQUIRED")
        base, _ = self.read_file(self.base)
        if re.search(r"(?im)^[ \t]*(include|copy)[ \t]*=", base.decode()):
            raise PolicyError("SMB_OPTIONS_SECTION_AMBIGUOUS")
        observed = self.settings(row["name"])
        for entry in observed:
            if any(entry[k] != current[k] for k in SMB_OPTION_NAMES):
                raise PolicyError("SMB_CREATION_POLICY_DRIFT")
            if entry["inheritPermissions"]:
                raise PolicyError("SMB_CREATION_POLICY_INHERIT_CONFLICT")
        parent = DirectoryPolicies().observe(row)
        if parent.get("status") != "OBSERVED":
            raise PolicyError("DIRECTORY_OBSERVATION_UNAVAILABLE")
        for entry in observed:
            inheritance_conflict({**entry, "inheritOwner": wanted["inheritOwner"]}, parent["mode"])
        target = {**row, "config": {**(row.get("config") or {}), **wanted}}
        validate_inheritance_share(target)
        return {"before": current, "after": wanted, "runtime": observed, "configurationhash":hashlib.sha256(base).hexdigest(),
                "parent": {k: parent.get(k) for k in ("owneruid", "ownergid", "mode", "inode", "filesystemuuid", "volumeuuid", "fingerprint")},
                "existingobjectschange": False, "applymode": "ONLINE_RELOAD",
                "executablebits": bool(int(wanted["createMask"], 8) & 0o111)}

    def snapshot(self, payload):
        import base64
        before = payload.get("before", {}).get("smb share apply")
        candidate = payload.get("candidate", {}).get("smb share apply")
        if set(payload.get("candidate", {})) != {"smb share apply"} or not isinstance(before, dict):
            raise PolicyError("INVALID_SMB_OPTIONS_INTENT")
        change = smb_options_difference(before, candidate)
        if change is None:
            import base64
            raw, metadata = self.read_file(self.base)
            return {"kind":"SMB_OPTIONS_NOOP", "base":{"content":base64.b64encode(raw).decode(), **metadata}, "profiles":self.profiles()}
        saved, desired_meta = self.read_file(self.desired)
        # Reuse the strict diff canonicalization to allow only job observation drift.
        old_saved = json.loads(saved)
        matched = json.loads(json.dumps(old_saved))
        wanted = json.loads(json.dumps(before))
        # Compare whole structures after removing the same observational fields and normalizing states/options.
        def normalized(value):
            value = json.loads(json.dumps(value))
            for key in ("listenerOnly", "configurationOperationId", "releaseAddresses", "listenerBefore", "listenerOriginalNetwork"):
                value.pop(key, None)
            for listener in value.get("listeners", []):
                if listener.get("state") in ("Ready", "Updating", "Creating", "Allocated"):
                    listener["state"] = "Ready"
            identity = value.get("identityDomain") or {}
            identity.pop("healthState", None)
            (identity.get("config") or {}).pop("adJoin", None)
            for row in value.get("shares", []):
                if row.get("state") in ("Ready", "Updating"):
                    row["state"] = "Ready"
                row.setdefault("config", {}).update(smb_share_options(row.get("config") or {}))
                smb_acl_policy_without_observation(row)
            value["shares"] = sorted(value.get("shares", []), key=lambda r: r["uuid"])
            return value
        if normalized(matched) != normalized(wanted):
            raise PolicyError("SMB_OPTIONS_BASELINE_CHANGED")
        preview = self.preview(change["row"], change["after"])
        raw, metadata = self.read_file(self.base)
        text = raw.decode()
        headers = list(re.finditer(r"(?m)^\s*\[([^]\r\n]+)\]\s*$", text))
        selected = [(i, h) for i, h in enumerate(headers) if h.group(1) == change["name"]]
        if len(selected) != 1:
            raise PolicyError("SMB_OPTIONS_SECTION_AMBIGUOUS")
        i, header = selected[0]
        start, end = header.end(), headers[i + 1].start() if i + 1 < len(headers) else len(text)
        section = text[start:end]
        if re.search(r"(?im)^\s*(include|copy)\s*=", section):
            raise PolicyError("SMB_OPTIONS_SECTION_AMBIGUOUS")
        changes = {key: value for key, value in change["after"].items() if change["before"][key] != value}
        for key, value in changes.items():
            parameter = SMB_OPTION_NAMES[key]
            pattern = r"(?im)^[ \t]*" + re.escape(parameter) + r"[ \t]*=.*$"
            matches = list(re.finditer(pattern, section))
            if len(matches) > 1:
                raise PolicyError("SMB_OPTIONS_SECTION_AMBIGUOUS")
            line = "   " + parameter + " = " + ("yes" if value is True else "no" if value is False else value)
            section = re.sub(pattern, lambda _: line, section) if matches else section.rstrip() + "\n" + line + "\n"
        candidate_text = text[:start] + section + text[end:]
        return {"kind": "SMB_OPTIONS", "target": change["name"], "before": change["before"], "after": change["after"],
                "base": {"content": base64.b64encode(raw).decode(), **metadata},
                "desired": {"content": base64.b64encode(saved).decode(), **desired_meta},
                "candidate": candidate_text, "candidateDesired": json.dumps(candidate, sort_keys=True, indent=2) + "\n",
                "profiles": self.profiles(), "preview": preview}

    @staticmethod
    def write_file(path, data, metadata):
        import tempfile
        descriptor, temp = tempfile.mkstemp(prefix=".smb-options-", dir=str(path.parent))
        try:
            os.fchmod(descriptor, metadata["mode"])
            os.fchown(descriptor, metadata["uid"], metadata["gid"])
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(data); stream.flush(); os.fsync(stream.fileno())
            os.replace(temp, path)
            descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(descriptor)
            finally: os.close(descriptor)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)

    def require_profiles(self, snapshot):
        if self.profiles() != snapshot["profiles"]:
            raise PolicyError("SMB_OPTIONS_PROCESS_CHANGED")

    def reload(self, snapshot):
        self.require_profiles(snapshot)
        for profile in snapshot["profiles"]:
            getattr(self, "progress", lambda value: None)({"phase":"RELOADING", "ports":profile["ports"], "pid":profile["pid"], "config":profile["config"]})
            try:
                checked(["smbcontrol", "-s", profile["config"], "-t", "10", str(profile["pid"]), "reload-config"], timeout=15)
                checked(["smbcontrol", "-s", profile["config"], "-t", "10", str(profile["pid"]), "ping"], timeout=15)
            except (PolicyError, OSError, subprocess.SubprocessError):
                raise PolicyError("SMB_OPTIONS_RELOAD_FAILED")
            getattr(self, "progress", lambda value: None)({"phase":"RELOADED", "ports":profile["ports"], "pid":profile["pid"]})
        self.require_profiles(snapshot)

    def apply(self, snapshot, candidate):
        if snapshot["kind"] == "SMB_OPTIONS_NOOP":
            self.verify(snapshot)
            return
        import tempfile, base64
        self.require_profiles(snapshot)
        raw, _ = self.read_file(self.base)
        if raw not in (base64.b64decode(snapshot["base"]["content"]), snapshot["candidate"].encode()):
            raise PolicyError("SMB_OPTIONS_BASELINE_CHANGED")
        descriptor, name = tempfile.mkstemp(prefix=".smb-options-check-", dir=str(self.base.parent))
        try:
            with os.fdopen(descriptor, "w") as stream:
                stream.write(snapshot["candidate"])
            checked(["testparm", "-s", name])
        finally:
            os.unlink(name)
        self.write_file(self.base, snapshot["candidate"].encode(), snapshot["base"])
        self.reload(snapshot)
        self.write_file(self.desired, (json.dumps(candidate, sort_keys=True, indent=2) + "\n").encode(), snapshot["desired"])
        self.verify(snapshot)

    def verify(self, snapshot, restored=False):
        import base64
        self.require_profiles(snapshot)
        if snapshot["kind"] == "SMB_OPTIONS_NOOP":
            if self.read_file(self.base)[0] != base64.b64decode(snapshot["base"]["content"]):
                raise PolicyError("SMB_OPTIONS_CONFIG_MISMATCH")
            return True
        expected = snapshot["before" if restored else "after"]
        raw, _ = self.read_file(self.base)
        target = base64.b64decode(snapshot["base"]["content"]) if restored else snapshot["candidate"].encode()
        if raw != target:
            raise PolicyError("SMB_OPTIONS_CONFIG_MISMATCH")
        desired, _ = self.read_file(self.desired)
        wanted = base64.b64decode(snapshot["desired"]["content"]) if restored else snapshot["candidateDesired"].encode()
        if desired != wanted:
            raise PolicyError("SMB_OPTIONS_DESIRED_MISMATCH")
        for row in self.settings(snapshot["target"], snapshot["profiles"]):
            if any(row[key] != expected[key] for key in SMB_OPTION_NAMES):
                raise PolicyError("SMB_OPTIONS_VERIFY_FAILED")
        return True

    def restore(self, snapshot):
        if snapshot["kind"] == "SMB_OPTIONS_NOOP":
            self.verify(snapshot)
            return
        import base64
        self.require_profiles(snapshot)
        self.write_file(self.base, base64.b64decode(snapshot["base"]["content"]), snapshot["base"])
        self.write_file(self.desired, base64.b64decode(snapshot["desired"]["content"]), snapshot["desired"])
        self.reload(snapshot)
        self.verify(snapshot, restored=True)


if __name__=="__main__":
    import sys
    try: main()
    except (PolicyError,OSError,ValueError,KeyError,subprocess.SubprocessError) as error:
        print(json.dumps({"code":getattr(error,"code","DIRECTORY_OBSERVATION_UNAVAILABLE")}))
        sys.exit(1)
