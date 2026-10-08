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

"""Scope cleanup to protected prior canonical targets; preserve shared/foreign refs."""
import json
import os
from pathlib import Path
import re
import stat
import hashlib
import tempfile
import uuid
import ipaddress

def nvme_cleanup_writer():
    if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") != "9":
        raise ValueError("NVMe authentication requires its protected native writer")
    try:
        named=Path(os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FILE","/run/ablestack-storage/desired-writer.lock"))
        opened=os.fstat(9);expected=named.lstat()
        if (not stat.S_ISREG(opened.st_mode) or opened.st_uid!=os.geteuid() or stat.S_IMODE(opened.st_mode)!=0o600
                or not stat.S_ISREG(expected.st_mode) or (opened.st_dev,opened.st_ino)!=(expected.st_dev,expected.st_ino)
                or Path("/proc/self/fd/9").resolve(strict=True)!=named.resolve(strict=True)):
            raise ValueError("NVMe authentication writer identity changed")
        locks=Path("/proc/self/fdinfo/9").read_text()
        pattern=r"(?m)^lock:\s+[0-9]+:\s+FLOCK\s+ADVISORY\s+WRITE\s+[0-9]+\s+[0-9a-f]+:[0-9a-f]+:"+str(opened.st_ino)+r"\s+0\s+EOF\s*$"
        if not re.search(pattern,locks):
            raise ValueError("NVMe authentication writer does not hold its own exclusive lock")
    except OSError as invalid:
        raise ValueError("NVMe authentication writer is unavailable") from invalid


def nvme_cleanup_name(value,target=False):
    if not isinstance(value,str) or not re.fullmatch(r"nqn\.[A-Za-z0-9_.:-]{1,219}",value) or (target and ".local.storage:" not in value):
        raise ValueError("NVMe cleanup name is outside its managed canonical scope")
    return value


class NvmeManagedCleanup:
    def __init__(self,base,state,payload,sessions,writer=None,rmdir=None):
        self.base=Path(base);self.state=Path(state);self.sessions=sessions
        self.writer=writer or nvme_cleanup_writer;self.rmdir=rmdir or os.rmdir
        self.previous,self.source_signature=self.read(self.state,optional=True)
        self.previous=self.previous or {}
        for value in (self.previous,payload):
            if "enabled" in value and type(value["enabled"]) is not bool:raise ValueError("NVMe cleanup enabled flag is not typed")
        self.instance=payload.get("instanceUuid")
        if not isinstance(self.instance,str) or str(uuid.UUID(self.instance))!=self.instance:
            raise ValueError("NVMe cleanup requires its exact instance UUID before effects")
        old_instance=self.previous.get("instanceUuid")
        if old_instance is not None and self.instance!=old_instance:
            raise ValueError("NVMe cleanup instance differs from its protected prior canonical record")
        self.owned=self.rows(self.previous,include_disabled=True)
        self.desired=self.rows(payload)
        self.book_path=self.state.parent/"nvme-runtime-ownership.json"
        book,_=self.read(self.book_path,optional=True,private=True)
        boot=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
        if book is not None and (set(book)!={"schemaVersion","instanceUuid","bootId","ports","hosts"} or type(book["schemaVersion"]) is not int or book["schemaVersion"]!=1
                or book["instanceUuid"]!=self.instance or not isinstance(book["ports"],dict) or not isinstance(book["hosts"],dict)):
            raise ValueError("NVMe runtime ownership receipt is foreign or invalid")
        self.book=book if book is not None and book["bootId"]==boot else {"schemaVersion":1,"instanceUuid":self.instance,"bootId":boot,"ports":{},"hosts":{}}
        self.guard()
        if self.base.exists():
            self.directory(self.base)
            for name in ("subsystems","ports","hosts"):
                if (self.base/name).exists():self.directory(self.base/name)
        for port,record in self.book["ports"].items():
            if (not isinstance(port,str) or not re.fullmatch(r"[1-9][0-9]{0,3}",port) or int(port)>4095 or not isinstance(record,dict)
                    or set(record)!={"device","inode","listenIp","port"} or any(type(record.get(key)) is not int or record[key]<0 for key in ("device","inode"))
                    or type(record.get("port")) is not int or not 1<=record["port"]<=65535):
                raise ValueError("NVMe owned port receipt is invalid")
            ipaddress.IPv4Address(record["listenIp"])
            path=self.base/"ports"/port
            if path.exists() and (self.directory(path)!={key:record[key] for key in ("device","inode")} or self.port_tuple(path)!=(record["listenIp"],record["port"])):
                raise ValueError("NVMe owned port identity/listener changed before effects")
        for host,record in self.book["hosts"].items():
            nvme_cleanup_name(host)
            if not isinstance(record,dict) or set(record)!={"device","inode"} or any(type(record[key]) is not int or record[key]<0 for key in record):
                raise ValueError("NVMe owned host receipt is invalid")
            path=self.base/"hosts"/host
            if path.exists() and self.directory(path)!=record:raise ValueError("NVMe owned host identity changed before effects")
        for nqn,row in self.desired.items():
            path=self.base/"subsystems"/nqn
            if path.exists() and nqn not in self.owned:
                raise ValueError("NVMe apply refuses an existing target outside prior canonical ownership")
        for nqn,row in self.owned.items():
            path=self.base/"subsystems"/nqn
            if not path.exists():continue
            self.directory(path);namespaces=path/"namespaces"
            allowed={str(item.get("lunOrNamespace") or 1) for item in row.get("namespaces") or []}
            wanted={str(item.get("lunOrNamespace") or 1) for item in self.desired.get(nqn,{}).get("namespaces") or []}
            if namespaces.exists():self.directory(namespaces)
            if namespaces.exists() and any(item.name not in allowed|wanted for item in namespaces.iterdir()):
                raise ValueError("NVMe cleanup refuses an unrecorded live namespace before effects")
            known_hosts={host["principal"] for host in row.get("hosts") or []}|{host["principal"] for host in self.desired.get(nqn,{}).get("hosts") or []}
            if any(link.name not in known_hosts for link in self.links(path/"allowed_hosts")):
                raise ValueError("NVMe cleanup refuses an unrecorded live host reference before effects")

    def read(self,path,optional=False,private=False):
        path=Path(path);parent=path.parent.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022:
            raise ValueError("NVMe canonical/ownership parent is not protected")
        try:before=path.lstat()
        except FileNotFoundError:
            if optional:return None,None
            raise
        if (not stat.S_ISREG(before.st_mode) or before.st_uid!=os.geteuid() or before.st_mode&0o022 or before.st_size>8*1024*1024
                or (private and stat.S_IMODE(before.st_mode)!=0o600)):
            raise ValueError("NVMe canonical/ownership record is not protected")
        descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
        fields=("st_dev","st_ino","st_mode","st_uid","st_gid","st_size","st_mtime_ns","st_ctime_ns")
        try:
            opened=os.fstat(descriptor);data=os.read(descriptor,8*1024*1024+1);after=os.fstat(descriptor);named=path.lstat()
            if len(data)>8*1024*1024 or any(getattr(before,key)!=getattr(opened,key) or getattr(after,key)!=getattr(opened,key) or getattr(named,key)!=getattr(opened,key) for key in fields):
                raise ValueError("NVMe protected source record changed while reading")
            def unique(pairs):
                result={}
                for key,value in pairs:
                    if key in result:raise ValueError("NVMe protected record has duplicate fields")
                    result[key]=value
                return result
            value=json.loads(data,object_pairs_hook=unique)
            if not isinstance(value,dict):raise ValueError("NVMe protected source record is invalid")
            return value,tuple(getattr(opened,key) for key in fields)+(hashlib.sha256(data).hexdigest(),)
        finally:os.close(descriptor)

    def rows(self,payload,include_disabled=False):
        rows=payload.get("subsystems") or []
        if not isinstance(rows,list):raise ValueError("NVMe canonical subsystem list is invalid")
        result={}
        for row in rows:
            if not isinstance(row,dict):raise ValueError("NVMe canonical subsystem row is invalid")
            nqn=nvme_cleanup_name(row.get("targetName"),True)
            if nqn in result:raise ValueError("NVMe canonical cleanup target is duplicated")
            if not include_disabled and (payload.get("enabled") is False or row.get("state","Ready") in ("Disabled","Destroyed","Error")):continue
            for namespace in row.get("namespaces") or []:
                number=namespace.get("lunOrNamespace")
                if number is None:number=1
                if type(number) is bool or not str(number).isdigit() or not 1<=int(number)<=65535:
                    raise ValueError("NVMe canonical cleanup namespace number is invalid")
            for host in row.get("hosts") or []:nvme_cleanup_name(host.get("principal"))
            result[nqn]=row
        return result

    def guard(self,drained=False):
        self.writer()
        _,signature=self.read(self.state,optional=True)
        if signature!=self.source_signature:raise ValueError("NVMe prior canonical record changed before cleanup")
        if drained:
            observed=self.sessions()
            if (observed.get("status")!="ok" or type(observed.get("sessionSchemaVersion")) is not int or observed["sessionSchemaVersion"]!=2
                    or type(observed.get("observedNvmeofTcpCount")) is not int or observed["observedNvmeofTcpCount"]!=0
                    or observed.get("warnings") or any(row.get("protocol")=="NVME_OF" for row in observed.get("sessions") or [])):
                raise ValueError("NVMe owned cleanup requires known idle native sessions")

    def directory(self,path):
        info=Path(path).lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:
            raise ValueError("NVMe configfs cleanup object is not an owned direct directory")
        return {"device":info.st_dev,"inode":info.st_ino}

    def port_tuple(self,path):
        values={}
        for name in ("addr_trtype","addr_adrfam","addr_traddr","addr_trsvcid"):
            attribute=path/name;fd=os.open(attribute,os.O_RDONLY|os.O_NOFOLLOW)
            try:
                opened=os.fstat(fd);named=attribute.lstat()
                if (not stat.S_ISREG(opened.st_mode) or opened.st_uid!=os.geteuid() or opened.st_mode&0o022
                        or (opened.st_dev,opened.st_ino)!=(named.st_dev,named.st_ino)):
                    raise ValueError("NVMe port public attribute is foreign")
                values[name]=os.read(fd,256).decode().strip()
                if (opened.st_dev,opened.st_ino)!=(attribute.lstat().st_dev,attribute.lstat().st_ino):
                    raise ValueError("NVMe port public attribute changed")
            finally:os.close(fd)
        if values["addr_trtype"]!="tcp" or values["addr_adrfam"]!="ipv4" or not values["addr_trsvcid"].isdigit():
            raise ValueError("NVMe owned port transport/listener is invalid")
        return str(ipaddress.IPv4Address(values["addr_traddr"])),int(values["addr_trsvcid"])

    def publish(self):
        self.guard()
        if not isinstance(self.instance,str) or str(uuid.UUID(self.instance))!=self.instance:
            raise ValueError("NVMe runtime ownership needs its exact instance UUID")
        fd,tmp=tempfile.mkstemp(prefix=".nvme-runtime-ownership-",dir=self.state.parent)
        try:
            os.fchmod(fd,0o600)
            with os.fdopen(fd,"w") as stream:json.dump(self.book,stream,sort_keys=True);stream.flush();os.fsync(stream.fileno())
            os.replace(tmp,self.book_path)
        finally:
            if os.path.exists(tmp):os.unlink(tmp)

    def created_port(self,port,key):
        path=self.base/"ports"/str(port);value=self.directory(path)
        if self.port_tuple(path)!=(str(key[0]),int(key[1])):raise ValueError("NVMe new owned port listener readback differs")
        value.update({"listenIp":str(key[0]),"port":int(key[1])})
        self.book["ports"][str(port)]=value;self.publish()

    def created_host(self,host):
        nvme_cleanup_name(host);self.book["hosts"][host]=self.directory(self.base/"hosts"/host);self.publish()

    def links(self,parent):
        parent=Path(parent)
        if not parent.exists():return []
        self.directory(parent);result=[]
        for path in parent.iterdir():
            if path.is_symlink():result.append(path)
            else:raise ValueError("NVMe cleanup refuses a non-symlink reference")
        return result

    def remove_link(self,path,target):
        if not path.is_symlink() or path.resolve()!=target.resolve():raise ValueError("NVMe owned cleanup link differs")
        self.guard(True);os.unlink(path)

    def disable_namespace(self,path):
        self.directory(path);attribute=path/"enable"
        fd=os.open(attribute,os.O_RDWR|os.O_NOFOLLOW)
        try:
            opened=os.fstat(fd);named=attribute.lstat()
            if (not stat.S_ISREG(opened.st_mode) or opened.st_uid!=os.geteuid() or opened.st_mode&0o022
                    or (opened.st_dev,opened.st_ino)!=(named.st_dev,named.st_ino)):
                raise ValueError("NVMe namespace enable attribute is foreign")
            self.guard(True);os.write(fd,b"0");os.lseek(fd,0,os.SEEK_SET)
            if os.read(fd,32).strip()!=b"0":raise ValueError("NVMe namespace disable readback differs")
            if (opened.st_dev,opened.st_ino)!=(attribute.lstat().st_dev,attribute.lstat().st_ino):raise ValueError("NVMe namespace enable attribute changed")
        finally:os.close(fd)
        self.guard(True);self.rmdir(path)

    def finish(self,desired_ports=None):
        ports=self.base/"ports";subsystems=self.base/"subsystems";host_candidates=set(self.book["hosts"])
        if not self.base.exists():return set()
        for row in self.owned.values():host_candidates.update(host["principal"] for host in row.get("hosts") or [])
        for nqn,old in self.owned.items():
            path=subsystems/nqn
            if not path.exists():continue
            self.directory(path);wanted=self.desired.get(nqn);wanted_hosts={row["principal"] for row in (wanted or {}).get("hosts") or []}
            if wanted is not None and (wanted.get("config") or {}).get("allowAnyHost"):wanted_hosts=set()
            for port in ports.iterdir() if ports.exists() else []:
                if wanted is not None and (desired_ports is None or port.name in desired_ports.get(nqn,set())):continue
                link=port/"subsystems"/nqn
                if link.exists() or link.is_symlink():self.remove_link(link,path)
            for host in old.get("hosts") or []:
                name=host["principal"];link=path/"allowed_hosts"/name
                if name not in wanted_hosts and (link.exists() or link.is_symlink()):self.remove_link(link,self.base/"hosts"/name)
            wanted_ns={str(row.get("lunOrNamespace") or 1) for row in (wanted or {}).get("namespaces") or [] if row.get("state","Ready") not in ("Disabled","Destroyed","Error")}
            for namespace in path.joinpath("namespaces").iterdir():
                if namespace.name not in wanted_ns:self.disable_namespace(namespace)
            if wanted is None:
                if self.links(path/"allowed_hosts"):raise ValueError("NVMe target retains an unrecorded host reference")
                self.guard(True);self.rmdir(path)
        # Delete only current-boot controller-created empty objects. A shared
        # host/port or an older/unregistered object is preserved byte-for-byte.
        for port,record in list(self.book["ports"].items()):
            path=ports/port
            if not path.exists():self.book["ports"].pop(port);continue
            if self.directory(path)!={key:record[key] for key in ("device","inode")}:raise ValueError("NVMe owned port identity changed")
            if not self.links(path/"subsystems"):
                self.guard(True);self.rmdir(path);self.book["ports"].pop(port)
        referenced={link.name for subsystem in subsystems.iterdir() if subsystem.is_dir() for link in self.links(subsystem/"allowed_hosts")} if subsystems.exists() else set()
        for host in host_candidates:
            path=self.base/"hosts"/host
            if host in referenced:continue
            record=self.book["hosts"].get(host)
            if record is not None and path.exists():
                if self.directory(path)!=record:raise ValueError("NVMe owned host identity changed")
                self.guard(True);self.rmdir(path)
            if record is not None:self.book["hosts"].pop(host)
        if self.book_path.exists() or self.book["ports"] or self.book["hosts"]:self.publish()
        return referenced & host_candidates
