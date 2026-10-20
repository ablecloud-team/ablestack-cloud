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

"""Exact configfs CHAP writes; credentials never become subprocess arguments."""
import os
import json
from pathlib import Path
import re
import stat
import uuid
from types import MappingProxyType


def iscsi_auth_writer():
    if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") != "9":
        raise ValueError("iSCSI authentication requires its protected native writer")
    try:
        named=Path(os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FILE","/run/ablestack-storage/desired-writer.lock"))
        opened=os.fstat(9);expected=named.lstat()
        if (not stat.S_ISREG(opened.st_mode) or opened.st_uid!=os.geteuid() or stat.S_IMODE(opened.st_mode)!=0o600
                or not stat.S_ISREG(expected.st_mode) or (opened.st_dev,opened.st_ino)!=(expected.st_dev,expected.st_ino)
                or Path("/proc/self/fd/9").resolve(strict=True)!=named.resolve(strict=True)):
            raise ValueError("iSCSI authentication writer identity changed")
        locks=Path("/proc/self/fdinfo/9").read_text()
        pattern=r"(?m)^lock:\s+[0-9]+:\s+FLOCK\s+ADVISORY\s+WRITE\s+[0-9]+\s+[0-9a-f]+:[0-9a-f]+:"+str(opened.st_ino)+r"\s+0\s+EOF\s*$"
        if not re.search(pattern,locks):
            raise ValueError("iSCSI authentication writer does not hold its own exclusive lock")
    except OSError as invalid:
        raise ValueError("iSCSI authentication writer is unavailable") from invalid


class IscsiAuthError(ValueError):
    """Fixed public category only; neither identity nor credentials enter messages."""
    CODES = frozenset(("TARGET_IQN_INVALID", "INITIATOR_IQN_INVALID", "OWNERSHIP_INVALID",
                       "OWNERSHIP_MISMATCH", "AUTH_VALUE_INVALID"))
    def __init__(self, code):
        if code not in self.CODES:
            raise ValueError("Invalid iSCSI authentication diagnostic category")
        self.iscsi_auth_code = code
        super().__init__("iSCSI authentication validation failed: " + code)


def iscsi_auth_name(value, target=False):
    # IQN grammar is shared with the API. A domain suffix is never ownership.
    if (not isinstance(value, str) or len(value)>223 or not re.fullmatch(
            r"iqn\.[0-9]{4}-[0-9]{2}\.[A-Za-z0-9.-]+:[A-Za-z0-9_.:-]+", value)):
        raise IscsiAuthError("TARGET_IQN_INVALID" if target else "INITIATOR_IQN_INVALID")
    return value


def iscsi_auth_uuid(value):
    try:
        return type(value) is str and str(uuid.UUID(value)) == value
    except (ValueError, AttributeError):
        return False


class IscsiAuthOwnership:
    """Immutable per-command canonical TARGET/SOURCE bindings, never name ownership.

    The caller's writer-protected canonical payload already passed management/
    generation authority. TARGET authorizes exact active ACL effects; SOURCE
    additionally authorizes reading old vault keys so removals/edits can discard
    them safely. A resource may be renamed, remapped or moved to another DATA
    volume by the existing update API: neither snapshot is rewritten or merged
    into permission to write an ACL absent from the exact TARGET snapshot.
    """
    __slots__ = ("_writer", "_bindings", "_grants", "_configs", "_vault")

    def __setattr__(self, name, value):
        raise AttributeError("iSCSI authentication ownership is immutable")

    def __init__(self, payload, previous=None, writer=None):
        check_writer = writer or iscsi_auth_writer
        check_writer()
        if not isinstance(payload, dict) or not iscsi_auth_uuid(payload.get("instanceUuid")):
            raise IscsiAuthError("OWNERSHIP_INVALID")
        instance = payload["instanceUuid"]
        if previous is None:
            previous = {}
        grants, configs, vault, bindings = {}, {}, set(), {}
        for source, value in ((False, payload), (True, previous)):
            if (not isinstance(value, dict) or not isinstance(value.get("targets", []), list)
                    or value.get("targets") and value.get("instanceUuid") != instance):
                raise IscsiAuthError("OWNERSHIP_INVALID")
            seen = set()
            for row in value.get("targets", []):
                if not isinstance(row, dict):
                    raise IscsiAuthError("OWNERSHIP_INVALID")
                identity, volume = row.get("uuid"), row.get("volumeUuid")
                if (not iscsi_auth_uuid(identity) or identity in seen or not iscsi_auth_uuid(volume)
                        or type(row.get("volumeSizeBytes")) is not int or row["volumeSizeBytes"] < 1
                        or not isinstance(row.get("config", {}), dict)
                        or str(row.get("backstoreType") or (row.get("config") or {}).get("backstoreType") or "BLOCK").upper() != "BLOCK"):
                    raise IscsiAuthError("OWNERSHIP_INVALID")
                seen.add(identity)
                iqn = iscsi_auth_name(row.get("targetName"), True)
                raw_lun = row.get("lunOrNamespace", "0")
                if type(raw_lun) not in (int, str) or not re.fullmatch(r"[0-9]+", str(raw_lun)):
                    raise IscsiAuthError("OWNERSHIP_INVALID")
                lun = str(raw_lun).lstrip("0") or "0"
                if len(lun)>5 or int(lun)>65535:
                    raise IscsiAuthError("OWNERSHIP_INVALID")
                binding_key = (source, iqn, lun)
                binding = (identity, volume, row["volumeSizeBytes"])
                if binding_key in bindings and bindings[binding_key] != binding:
                    raise IscsiAuthError("OWNERSHIP_MISMATCH")
                bindings[binding_key] = binding
                acls = row.get("acls") or []
                if not isinstance(acls, list):
                    raise IscsiAuthError("OWNERSHIP_INVALID")
                if row.get("state", "Ready") in ("Disabled", "Destroyed", "Error"):
                    continue
                for acl in acls:
                    if not isinstance(acl, dict):
                        raise IscsiAuthError("OWNERSHIP_INVALID")
                    if acl.get("state", "Ready") in ("Disabled", "Destroyed", "Error"):
                        continue
                    initiator = iscsi_auth_name(acl.get("principal"))
                    key = (iqn, initiator)
                    vault.add(key)
                    if source:
                        continue
                    config = acl.get("config") or {}
                    if not isinstance(config, dict) or acl.get("permission") not in ("READ_ONLY", "READ_WRITE"):
                        raise IscsiAuthError("OWNERSHIP_INVALID")
                    if int(lun)>ISCSI_MAPPED_LUN_MAX:
                        raise IscsiAuthError("OWNERSHIP_INVALID")
                    signature = self.signature(config)
                    if key in configs and configs[key] != signature:
                        raise IscsiAuthError("OWNERSHIP_MISMATCH")
                    configs[key] = signature
                    protect = 1 if acl["permission"] == "READ_ONLY" else 0
                    if lun in grants.setdefault(key, {}) and grants[key][lun] != protect:
                        raise IscsiAuthError("OWNERSHIP_MISMATCH")
                    grants[key][lun] = protect
        object.__setattr__(self, "_writer", check_writer)
        object.__setattr__(self, "_bindings", MappingProxyType(bindings))
        object.__setattr__(self, "_grants", MappingProxyType(
            {key: MappingProxyType(dict(value)) for key, value in grants.items()}))
        object.__setattr__(self, "_configs", MappingProxyType(configs))
        object.__setattr__(self, "_vault", frozenset(vault))

    @staticmethod
    def signature(config):
        if not isinstance(config, dict):
            raise IscsiAuthError("AUTH_VALUE_INVALID")
        flags = (config.get("chapEnabled", False), config.get("mutualChapEnabled", False))
        if any(type(flag) is not bool for flag in flags):
            raise IscsiAuthError("AUTH_VALUE_INVALID")
        for enabled, key in zip(flags, ("chapUsername", "mutualChapUsername")):
            value = config.get(key)
            if enabled and (type(value) is not str or not value.strip() or value.startswith("NULL")
                            or len(value.encode())>255 or any(char in value for char in ("\0", "\r", "\n"))):
                raise IscsiAuthError("AUTH_VALUE_INVALID")
        return (flags[0], config.get("chapUsername") if flags[0] else None,
                flags[1], config.get("mutualChapUsername") if flags[1] else None)

    def authorize(self, target, initiator, config=None, expected=None, vault=False):
        self._writer()
        key = (iscsi_auth_name(target, True), iscsi_auth_name(initiator))
        if key not in (self._vault if vault else self._grants):
            raise IscsiAuthError("OWNERSHIP_MISMATCH")
        if config is not None and self.signature(config) != self._configs.get(key):
            raise IscsiAuthError("OWNERSHIP_MISMATCH")
        if expected is not None and (not isinstance(expected,dict) or dict(expected) != dict(self._grants[key])):
            raise IscsiAuthError("OWNERSHIP_MISMATCH")
        return True


def iscsi_auth_authorize(ownership, target, initiator, **kwargs):
    if not isinstance(ownership, IscsiAuthOwnership):
        raise IscsiAuthError("OWNERSHIP_INVALID")
    return ownership.authorize(target, initiator, **kwargs)


def iscsi_auth_values(config,values):
    if not isinstance(config,dict) or not isinstance(values,dict):
        raise IscsiAuthError("AUTH_VALUE_INVALID")
    result={}
    for flag,user,password,secret in (("chapEnabled","userid","password","chapSecret"),("mutualChapEnabled","userid_mutual","password_mutual","mutualChapSecret")):
        enabled=config.get(flag,False)
        if type(enabled) is not bool:raise IscsiAuthError("AUTH_VALUE_INVALID")
        username=config.get("chapUsername" if flag=="chapEnabled" else "mutualChapUsername")
        for field,value in ((user,username),(password,values.get(secret))):
            if enabled:
                if (not isinstance(value,str) or not value or not value.strip() or value.startswith("NULL")
                        or len(value.encode())>255 or any(char in value for char in ("\0","\r","\n"))):
                    raise IscsiAuthError("AUTH_VALUE_INVALID")
                result[field]=value.encode()
            else:result[field]=b"NULL"
    return result


# rtslib-fb 2.1.75 MappedLUN.MAX_LUN, not a claim of the kernel limit.
ISCSI_MAPPED_LUN_MAX = 255

class ConfigfsIscsiAuth:
    def __init__(self,root=None,writer=None,write=None,ownership=None):
        self.root=Path(root or "/sys/kernel/config/target/iscsi")
        self.writer=writer or iscsi_auth_writer
        self.write=write or os.write
        self.ownership=ownership

    @staticmethod
    def identity(info):
        return info.st_dev,info.st_ino,info.st_mode,info.st_uid,info.st_gid

    def auth_directory(self,target,initiator):
        iscsi_auth_authorize(self.ownership,target,initiator)
        path=self.root;descriptor=None
        try:
            for component in (None,target,"tpgt_1","acls",initiator,"auth"):
                if component is None:
                    named=path.lstat();opened=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
                else:
                    named=os.stat(component,dir_fd=descriptor,follow_symlinks=False)
                    opened=os.open(component,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=descriptor);path=path/component
                info=os.fstat(opened)
                if (not stat.S_ISDIR(named.st_mode) or named.st_uid!=os.geteuid() or named.st_mode&0o022
                        or self.identity(named)!=self.identity(info)):
                    os.close(opened);raise ValueError("iSCSI authentication directory is foreign or changed")
                if descriptor is not None:os.close(descriptor)
                descriptor=opened
            return descriptor,path
        except (OSError,ValueError):
            if descriptor is not None:os.close(descriptor)
            raise ValueError("iSCSI authentication directory is unavailable or replaced") from None

    def attribute(self,directory,field,write=False):
        if field not in ("userid","password","userid_mutual","password_mutual"):
            raise ValueError("iSCSI authentication attribute is outside its fixed allowlist")
        # configfs drops unreferenced attribute dentries and allocates a new
        # inode on lookup. Pin the opened dentry first, then compare its name.
        try:descriptor=os.open(field,(os.O_RDWR if write else os.O_RDONLY)|os.O_NOFOLLOW,dir_fd=directory)
        except OSError as invalid:raise ValueError("iSCSI authentication attribute is unavailable or not a direct file") from invalid
        try:
            opened=os.fstat(descriptor)
            if not stat.S_ISREG(opened.st_mode) or opened.st_uid!=os.geteuid() or opened.st_mode&0o022 or opened.st_nlink!=1:
                raise ValueError("iSCSI authentication attribute is foreign")
            named=os.stat(field,dir_fd=directory,follow_symlinks=False)
            if self.identity(named)!=self.identity(opened):
                raise ValueError("iSCSI authentication attribute changed after its dentry was pinned")
            return descriptor,opened
        except BaseException:
            os.close(descriptor);raise

    def apply(self,target,initiator,config,secrets):
        iscsi_auth_authorize(self.ownership,target,initiator,config=config)
        values=iscsi_auth_values(config,secrets);self.writer()
        directory,path=self.auth_directory(target,initiator);attributes={}
        try:
            for field in values:attributes[field]=self.attribute(directory,field,True)
            for field,value in values.items():
                if self.identity(path.lstat())!=self.identity(os.fstat(directory)):
                    raise ValueError("iSCSI authentication ACL was replaced before a write")
                descriptor,before=attributes[field]
                if self.identity(before)!=self.identity(os.stat(field,dir_fd=directory,follow_symlinks=False)):
                    raise ValueError("iSCSI authentication attribute was replaced before a write")
                os.lseek(descriptor,0,os.SEEK_SET)
                if self.write(descriptor,value)!=len(value):raise ValueError("iSCSI authentication attribute write was incomplete")
                os.lseek(descriptor,0,os.SEEK_SET);observed=os.read(descriptor,4096).rstrip(b"\n")
                if observed!=value:raise ValueError("iSCSI authentication attribute readback differed")
                if self.identity(before)!=self.identity(os.stat(field,dir_fd=directory,follow_symlinks=False)):
                    raise ValueError("iSCSI authentication attribute was replaced during readback")
            return {"success":True,"credentialReadbackVerified":True,"chapEnabled":config.get("chapEnabled",False),"mutualChapEnabled":config.get("mutualChapEnabled",False),"credentialSubprocessArguments":False}
        finally:
            for descriptor,info in attributes.values():os.close(descriptor)
            os.close(directory)


    def verify_lun_access(self,target,initiator,expected):
        """Read only the exact NodeACL mapped-LUN links and public protection bits."""
        iscsi_auth_authorize(self.ownership,target,initiator,expected=expected);self.writer()
        if not isinstance(expected,dict) or any(not re.fullmatch(r"[0-9]+",str(lun))
                or not 0<=int(lun)<=ISCSI_MAPPED_LUN_MAX or type(value)is not int or value not in (0,1) for lun,value in expected.items()):
            raise ValueError("iSCSI LUN permission plan is invalid")
        descriptors=[]
        def directory(parts):
            path=self.root;parent=None
            for part in (None,*parts):
                if part is None:
                    opened=os.open(path,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
                    named=path.lstat()
                else:
                    opened=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent)
                    named=os.stat(part,dir_fd=parent,follow_symlinks=False);path=path/part
                descriptors.append(opened);info=os.fstat(opened)
                if (not stat.S_ISDIR(named.st_mode) or named.st_uid!=os.geteuid() or named.st_mode&0o022
                        or self.identity(named)!=self.identity(info)):
                    raise ValueError("iSCSI LUN permission directory is foreign or replaced")
                parent=opened
            return parent,path
        try:
            acl,path=directory((target,"tpgt_1","acls",initiator))
            mapped={name for name in os.listdir(acl) if re.fullmatch(r"lun_[0-9]+",name)}
            if mapped!={"lun_"+str(lun) for lun in expected}:
                raise ValueError("iSCSI mapped LUN grant set differs")
            for lun,protect in expected.items():
                iteration_start=len(descriptors)
                node,nodepath=directory((target,"tpgt_1","acls",initiator,"lun_"+str(lun)))
                tpg,tpgpath=directory((target,"tpgt_1","lun","lun_"+str(lun)))
                links=[name for name in os.listdir(node) if stat.S_ISLNK(os.stat(name,dir_fd=node,follow_symlinks=False).st_mode)]
                if len(links)!=1 or (nodepath/os.readlink(links[0],dir_fd=node)).resolve(strict=True)!=tpgpath:
                    raise ValueError("iSCSI mapped LUN points outside its exact target")
                before=os.fstat(tpg);before_acl=os.fstat(acl);before_node=os.fstat(node)
                if self.identity(before_acl)!=self.identity(path.lstat()) or self.identity(before_node)!=self.identity(nodepath.lstat()):
                    raise ValueError("iSCSI mapped ACL directory changed before readback")
                descriptor=os.open("write_protect",os.O_RDONLY|os.O_NOFOLLOW,dir_fd=node);descriptors.append(descriptor)
                info=os.fstat(descriptor)
                if (not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022 or info.st_nlink!=1
                        or self.identity(info)!=self.identity(os.stat("write_protect",dir_fd=node,follow_symlinks=False))):
                    raise ValueError("iSCSI mapped LUN protection attribute is foreign or replaced")
                observed=os.read(descriptor,16).strip()
                if observed!=str(protect).encode() or self.identity(before)!=self.identity(tpgpath.lstat()):
                    raise ValueError("iSCSI mapped LUN protection readback differs")
                if (self.identity(info)!=self.identity(os.stat("write_protect",dir_fd=node,follow_symlinks=False))
                        or self.identity(before_acl)!=self.identity(path.lstat()) or self.identity(before_node)!=self.identity(nodepath.lstat())
                        or [name for name in os.listdir(node) if stat.S_ISLNK(os.stat(name,dir_fd=node,follow_symlinks=False).st_mode)]!=links
                        or (nodepath/os.readlink(links[0],dir_fd=node)).resolve(strict=True)!=tpgpath):
                    raise ValueError("iSCSI mapped LUN protection or ACL changed during readback")
                for opened in reversed(descriptors[iteration_start:]):os.close(opened)
                del descriptors[iteration_start:]
            if (self.identity(os.fstat(acl))!=self.identity(path.lstat())
                    or {name for name in os.listdir(acl) if re.fullmatch(r"lun_[0-9]+",name)}!={"lun_"+str(lun) for lun in expected}):
                raise ValueError("iSCSI mapped LUN grant set changed during readback")
            return {"success":True,"mappedLunPermissionsVerified":True,"mappedLunCount":len(expected)}
        except OSError:
            raise ValueError("iSCSI mapped LUN permissions are unavailable") from None
        finally:
            for descriptor in reversed(descriptors):os.close(descriptor)



def iscsi_private_vault(path,ownership=None):
    path=Path(path)
    try:info=path.lstat()
    except FileNotFoundError:return {}
    parent=path.parent.lstat()
    if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o077
            or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600 or info.st_size>8*1024*1024):
        raise ValueError("iSCSI private vault is not protected")
    descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        opened=os.fstat(descriptor)
        fields=("st_dev","st_ino","st_mode","st_uid","st_gid","st_size","st_mtime_ns","st_ctime_ns")
        if any(getattr(opened,key)!=getattr(info,key) for key in fields):raise ValueError("iSCSI private vault changed while opening")
        data=os.read(descriptor,8*1024*1024+1);after=os.fstat(descriptor)
        if len(data)>8*1024*1024 or any(getattr(after,key)!=getattr(opened,key) for key in fields):raise ValueError("iSCSI private vault changed while reading")
        value=json.loads(data)
    except (ValueError,TypeError) as invalid:
        raise ValueError("iSCSI private vault is invalid") from invalid
    finally:os.close(descriptor)
    if not isinstance(value,dict):raise ValueError("iSCSI private vault is invalid")
    for binding,secrets in value.items():
        parts=binding.split("|")
        if len(parts)!=2:raise ValueError("iSCSI private vault resource binding is invalid")
        iscsi_auth_authorize(ownership,parts[0],parts[1],vault=True)
        if (not isinstance(secrets,dict) or not secrets or not set(secrets)<={"chapSecret","mutualChapSecret"}
                or any(not isinstance(item,str) or not item or len(item.encode())>255 or any(char in item for char in ("\0","\r","\n")) for item in secrets.values())):
            raise ValueError("iSCSI private vault credential binding is invalid")
    return value
