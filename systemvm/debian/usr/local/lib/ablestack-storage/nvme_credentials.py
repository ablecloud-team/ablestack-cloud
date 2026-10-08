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

"""Protected per-instance NVMe credentials; public desired state remains redacted."""
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import uuid


def protected_credential_json(path):
    path=Path(path)
    try:info=path.lstat()
    except FileNotFoundError:return None
    parent=path.parent.lstat()
    if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o077
            or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600 or info.st_size>8*1024*1024):
        raise ValueError("Protected NVMe credential store permissions are invalid")
    descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        fields=("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns","st_ctime_ns")
        opened=os.fstat(descriptor)
        if tuple(getattr(opened,key) for key in fields)!=tuple(getattr(info,key) for key in fields):raise ValueError("NVMe credential store changed while opening")
        value=os.read(descriptor,8*1024*1024+1)
        after=os.fstat(descriptor)
        if len(value)>8*1024*1024 or tuple(getattr(after,key) for key in fields)!=tuple(getattr(opened,key) for key in fields):raise ValueError("NVMe credential store changed while reading")
        record=json.loads(value)
    finally:os.close(descriptor)
    return record


class NvmeCredentialStore:
    def __init__(self,path=None):
        self.path=Path(path or "/etc/ablestack-storage/secrets/nvmeof-acl-secrets.json")

    def read(self):
        record=protected_credential_json(self.path)
        if record is None:return None
        if not isinstance(record,dict) or set(record)!={"schemaVersion","instanceUuid","hosts"} or record["schemaVersion"]!=1 or not isinstance(record["hosts"],dict):
            raise ValueError("NVMe credential store schema is invalid")
        if str(uuid.UUID(record["instanceUuid"]))!=record["instanceUuid"]:raise ValueError("NVMe credential store instance binding is invalid")
        return record

    def hydrate(self,payload):
        result=json.loads(json.dumps(payload));previous=self.read();instance=result.get("instanceUuid")
        if previous and previous["instanceUuid"]!=instance:raise ValueError("NVMe credential store belongs to another instance")
        hosts={};policies={}
        for subsystem in result.get("subsystems") or []:
            if result.get("enabled") is False or subsystem.get("state","Ready") in ("Disabled","Destroyed","Error") or (subsystem.get("config") or {}).get("allowAnyHost"):continue
            for host in subsystem.get("hosts") or []:
                if host.get("state","Ready") in ("Disabled","Destroyed","Error"):continue
                principal=host.get("principal");config=host.get("config") or {};supplied=host.get("secrets") or {}
                if not isinstance(principal,str) or not re.fullmatch(r"nqn\.[A-Za-z0-9_.:-]{1,219}",principal):raise ValueError("NVMe credential host binding is invalid")
                policy=(bool(config.get("dhChapEnabled")),bool(config.get("dhChapCtrlEnabled")))
                if principal in policies and policies[principal]!=policy:raise ValueError("Shared NVMe host has conflicting authentication policy")
                policies[principal]=policy;secrets={}
                for flag,field in (("dhChapEnabled","dhChapKey"),("dhChapCtrlEnabled","dhChapCtrlKey")):
                    if not config.get(flag):continue
                    value=supplied.get(field) or ((previous or {}).get("hosts",{}).get(principal) or {}).get(field)
                    if not isinstance(value,str) or not value.startswith("DHHC-1:") or len(value)>4096 or "\n" in value or "\r" in value:raise ValueError("Required protected NVMe credential is missing or invalid")
                    secrets[field]=value
                if principal in hosts and hosts[principal]!=secrets:raise ValueError("Shared NVMe host has conflicting credential input")
                if secrets:hosts[principal]=secrets;host["secrets"]=secrets
                else:host.pop("secrets",None)
        if hosts and (not isinstance(instance,str) or str(uuid.UUID(instance))!=instance):raise ValueError("Protected NVMe credentials require an exact instance UUID")
        return result,{"schemaVersion":1,"instanceUuid":instance,"hosts":hosts}

    def persist(self,record):
        if not record["hosts"] and not self.path.exists():return
        if str(uuid.UUID(record["instanceUuid"]))!=record["instanceUuid"]:raise ValueError("NVMe credential store instance is invalid")
        self.path.parent.mkdir(parents=True,mode=0o700,exist_ok=True);parent=self.path.parent.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o077:raise ValueError("NVMe credential directory is not protected")
        if self.path.exists():self.read()
        descriptor,temporary=tempfile.mkstemp(prefix=".nvme-credentials-",dir=self.path.parent)
        try:
            os.fchmod(descriptor,0o600)
            with os.fdopen(descriptor,"w") as handle:json.dump(record,handle,sort_keys=True);handle.flush();os.fsync(handle.fileno())
            os.replace(temporary,self.path)
            directory=os.open(self.path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(directory)
            finally:os.close(directory)
        finally:
            if os.path.exists(temporary):os.unlink(temporary)
