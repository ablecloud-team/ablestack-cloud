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


def iscsi_auth_name(value,target=False):
    if (not isinstance(value,str) or len(value)>223 or not re.fullmatch(r"iqn\.[0-9]{4}-[0-9]{2}\.[A-Za-z0-9.-]+:[A-Za-z0-9_.:-]+",value)
            or (target and ".local.storage:" not in value)):
        raise ValueError("iSCSI authentication has a foreign target or initiator")
    return value


def iscsi_auth_values(config,values):
    if not isinstance(config,dict) or not isinstance(values,dict):
        raise ValueError("iSCSI authentication inputs are not structured")
    result={}
    for flag,user,password,secret in (("chapEnabled","userid","password","chapSecret"),("mutualChapEnabled","userid_mutual","password_mutual","mutualChapSecret")):
        enabled=config.get(flag,False)
        if type(enabled) is not bool:raise ValueError("iSCSI authentication flags are invalid")
        username=config.get("chapUsername" if flag=="chapEnabled" else "mutualChapUsername")
        for field,value in ((user,username),(password,values.get(secret))):
            if enabled:
                if (not isinstance(value,str) or not value or not value.strip() or value.startswith("NULL")
                        or len(value.encode())>255 or any(char in value for char in ("\0","\r","\n"))):
                    raise ValueError("iSCSI authentication value is invalid for the fixed kernel attribute")
                result[field]=value.encode()
            else:result[field]=b"NULL"
    return result


# rtslib-fb 2.1.75 MappedLUN.MAX_LUN, not a claim of the kernel limit.
ISCSI_MAPPED_LUN_MAX = 255

class ConfigfsIscsiAuth:
    def __init__(self,root=None,writer=None,write=None):
        self.root=Path(root or "/sys/kernel/config/target/iscsi")
        self.writer=writer or iscsi_auth_writer
        self.write=write or os.write

    @staticmethod
    def identity(info):
        return info.st_dev,info.st_ino,info.st_mode,info.st_uid,info.st_gid

    def auth_directory(self,target,initiator):
        iscsi_auth_name(target,True);iscsi_auth_name(initiator)
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
        iscsi_auth_name(target,True);iscsi_auth_name(initiator);self.writer()
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



def iscsi_private_vault(path):
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
        iscsi_auth_name(parts[0],True);iscsi_auth_name(parts[1])
        if (not isinstance(secrets,dict) or not secrets or not set(secrets)<={"chapSecret","mutualChapSecret"}
                or any(not isinstance(item,str) or not item or len(item.encode())>255 or any(char in item for char in ("\0","\r","\n")) for item in secrets.values())):
            raise ValueError("iSCSI private vault credential binding is invalid")
    return value
