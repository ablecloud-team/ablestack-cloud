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

"""Protected joined AD policy and fixed fresh principal mapping for physical ACLs."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time
import uuid


def ad_authority_read(path, exact_mode=None, max_bytes=8*1024*1024):
    if type(max_bytes) is not int or not 0<max_bytes<=12*1024*1024:raise ValueError("AD authority bound is invalid")
    path=Path(path);parent=path.parent.lstat();info=path.lstat()
    if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022
            or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022
            or info.st_size>max_bytes or exact_mode is not None and stat.S_IMODE(info.st_mode)!=exact_mode):
        raise ValueError("AD policy authority is not protected")
    directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    descriptor=None
    fields=("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns","st_ctime_ns")
    try:
        opened_parent=os.fstat(directory)
        if (opened_parent.st_dev,opened_parent.st_ino)!=(parent.st_dev,parent.st_ino):raise ValueError("AD policy directory changed")
        descriptor=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=directory);opened=os.fstat(descriptor)
        if any(getattr(opened,key)!=getattr(info,key) for key in fields):raise ValueError("AD policy changed during open")
        with os.fdopen(descriptor,"rb",closefd=False) as handle:raw=handle.read(max_bytes+1)
        after=os.fstat(descriptor);named=os.stat(path.name,dir_fd=directory,follow_symlinks=False)
        if len(raw)>max_bytes or any(getattr(opened,key)!=getattr(after,key) or getattr(opened,key)!=getattr(named,key) for key in fields):
            raise ValueError("AD policy changed during read")
        return raw
    finally:
        if descriptor is not None:os.close(descriptor)
        os.close(directory)


def ad_qualified_name(value,domain,workgroup):
    if not isinstance(value,str) or not value or len(value)>256 or any(ord(char)<32 for char in value):raise ValueError("AD principal is invalid")
    if chr(92) in value:
        pieces=value.split(chr(92))
        if len(pieces)!=2 or pieces[0].upper()!=workgroup:raise ValueError("AD principal belongs to another domain")
        name=pieces[1]
    elif "@" in value:
        pieces=value.split("@")
        if len(pieces)!=2 or pieces[1].lower()!=domain:raise ValueError("AD principal belongs to another realm")
        name=pieces[0]
    else:name=value
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_. -]{0,127}",name) or name!=name.strip():raise ValueError("AD principal name is invalid")
    return workgroup+chr(92)+name


def ad_policy_lines(workgroup,policy):
    if not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,14}",workgroup or ""):raise ValueError("AD workgroup authority is invalid")
    if not isinstance(policy,dict) or set(policy)!={"default","domain"}:raise ValueError("AD policy requires exact idmap scopes")
    lines=[];ranges=[]
    for key,name,backend,fields in (("default","*","tdb",{"backend","range"}),("domain",workgroup,"rid",{"backend","range","baseRid"})):
        row=policy[key];limits=row.get("range") if isinstance(row,dict) else None
        if (not isinstance(row,dict) or set(row)!=fields or row.get("backend")!=backend or not isinstance(limits,list) or len(limits)!=2
                or any(type(number) is not int for number in limits) or not 10000<=limits[0]<limits[1]<=2147483647 or limits[0]<=65534<=limits[1]):
            raise ValueError("AD idmap authority has an unsafe or unsupported range")
        if backend=="rid" and (type(row["baseRid"]) is not int or not 0<=row["baseRid"]<=2147483647):raise ValueError("AD idmap RID authority is invalid")
        ranges.append(limits);lines.extend(["   idmap config "+name+" : backend = "+backend,"   idmap config "+name+" : range = "+str(limits[0])+"-"+str(limits[1])])
        if backend=="rid":lines.append("   idmap config "+name+" : base_rid = "+str(row["baseRid"]))
    if max(row[0] for row in ranges)<=min(row[1] for row in ranges):raise ValueError("AD idmap authority ranges overlap")
    return lines


def protected_ad_policy(instance_uuid, identity=None, netbios_name=None, configuration=None):
    directory=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
    raw=ad_authority_read(directory/"smb-domain.json");state=json.loads(raw)
    if (not isinstance(state,dict) or state.get("state")!="JOINED" or state.get("joinState")!="JOINED"
            or state.get("instanceUuid")!=str(uuid.UUID(instance_uuid))):
        raise ValueError("AD joined policy belongs to another native instance")
    domain=state.get("domainName");realm=state.get("realm");workgroup=state.get("workgroup");netbios=state.get("netbiosName")
    if (not isinstance(domain,str) or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?",domain) or "." not in domain
            or ".." in domain or realm!=domain.upper() or not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,14}",netbios or "")):
        raise ValueError("AD joined realm/machine authority is invalid")
    receipt=state.get("identityReceipt")
    if not isinstance(receipt,dict) or set(receipt)!={"machineSid","domainSid","machineAccountSid"}:
        raise ValueError("AD joined policy lacks its protected SID receipt")
    for key in ("machineSid","domainSid"):
        if not re.fullmatch(r"S-1-5-21(?:-[0-9]{1,10}){3}",str(receipt[key])):raise ValueError("AD joined SID authority is invalid")
    if not re.fullmatch(re.escape(receipt["domainSid"])+r"-[0-9]{1,10}",str(receipt["machineAccountSid"])):
        raise ValueError("AD joined computer SID authority is foreign")
    mapping=state.get("idmapPolicy");lines=ad_policy_lines(workgroup,mapping)
    machine=ad_authority_read(directory/"ad-machine.conf",0o600);checksum=hashlib.sha256(machine).hexdigest()
    if state.get("machineConfigurationSha256")!=checksum:raise ValueError("AD machine policy differs from its joined receipt")
    expected={"workgroup":workgroup,"realm":realm,"netbios name":netbios,"security":"ADS"}
    for line in lines:
        name,value=line.strip().split("=",1);expected[name.strip().lower()]=value.strip()
    observed={};global_section=False
    for line in machine.decode().splitlines():
        stripped=line.strip()
        if stripped.startswith("["):global_section=stripped.lower()=="[global]";continue
        if global_section and "=" in stripped and not stripped.startswith(("#",";")):
            name,value=stripped.split("=",1);name=name.strip().lower()
            if name in expected:
                if name in observed:raise ValueError("AD machine policy contains duplicate authority parameters")
                observed[name]=value.strip()
    if observed!=expected:raise ValueError("AD machine parameters differ from their exact joined authority")
    if identity is not None:
        config=identity.get("config") or {};wanted=config.get("identityReceipt")
        stable={"domain":domain,"realm":realm,"workgroup":workgroup,"netbiosName":netbios,**receipt,
                "idmapPolicy":mapping,"servicePrincipals":state.get("servicePrincipals"),"dnsAliases":state.get("dnsAliases")}
        if (identity.get("joinState")!="JOINED" or str(identity.get("domainName") or "").lower()!=domain
                or config.get("workgroup") not in (None,workgroup) or config.get("netbiosName") not in (None,netbios)
                or not isinstance(wanted,dict) or any(wanted.get(key)!=value for key,value in stable.items())
                or wanted.get("trustVerified") is not True or wanted.get("identityVerified") is not True):
            raise ValueError("Desired AD identity differs from its protected joined policy")
    if netbios_name is not None and netbios_name!=netbios:raise ValueError("SMB netbios name differs from its protected AD machine")
    if ad_authority_read(directory/"smb-domain.json")!=raw:raise ValueError("AD joined policy changed during machine verification")
    return {"domain":domain,"realm":realm,"workgroup":workgroup,"netbiosName":netbios,"domainSid":receipt["domainSid"],
            "idmapPolicy":mapping,"idmapLines":lines,"machineConfigurationSha256":checksum}


def ad_mapped_entry(entry,request,run=None,cli=None,configuration=None,generation=None,clock=None):
    policy=protected_ad_policy(request["instanceUuid"],configuration=configuration)
    root=Path(generation or os.environ.get("ABLESTACK_STORAGE_GENERATION_DIR","/var/lib/ablestack-storage/config-generations"))
    path=root/"pending.json"
    if not path.exists() and not path.is_symlink():path=root/"current.json"
    scope=json.loads(ad_authority_read(path))
    native={key:scope[key] for key in ("instanceUuid","operationUuid","revision")}
    if native["instanceUuid"]!=request["instanceUuid"] or type(native["revision"]) is not int or native["revision"]<1:
        raise ValueError("AD mapping scope belongs to another native instance")
    if request.get("operationUuid") is not None:
        if request.get("operationUuid")!=native["operationUuid"] or request.get("revision")!=native["revision"]:
            raise ValueError("AD mapping requested operation/revision differs")
    elif path.name=="pending.json":
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")!="9":raise ValueError("AD pending mapping lacks its owned writer")
        descriptor=os.fstat(9);named=Path(os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FILE","/run/ablestack-storage/desired-writer.lock")).lstat()
        if (not stat.S_ISREG(descriptor.st_mode) or descriptor.st_uid!=os.geteuid() or stat.S_IMODE(descriptor.st_mode)!=0o600
                or (descriptor.st_dev,descriptor.st_ino)!=(named.st_dev,named.st_ino)
                or not re.search(r"lock:.*FLOCK.*WRITE",Path("/proc/self/fdinfo/9").read_text())):
            raise ValueError("AD pending mapping writer descriptor differs")
    kind=entry.get("principalType")
    if kind not in ("AD_USER","AD_GROUP"):raise ValueError("AD mapping principal type is unsupported")
    qualified=ad_qualified_name(entry.get("principal"),policy["domain"],policy["workgroup"])
    payload={**native,"principalType":kind,"principal":entry.get("principal"),"expectedRealm":policy["realm"],"expectedDomainSid":policy["domainSid"]}
    result=(run or subprocess.run)([str(cli or os.environ.get("ABLESTACK_STORAGECTL_SELF","/usr/local/bin/ablestack-storagectl")),"identity","principal","resolve","/dev/stdin"],
                                 input=json.dumps(payload),capture_output=True,text=True,timeout=45)
    if result.returncode:raise ValueError("Fresh AD principal mapping was rejected")
    value=json.loads(result.stdout);number=value.get("numericId");sid_value=value.get("sid");now=(clock or time.time)()
    expected_kind="u" if kind=="AD_USER" else "g";allowed={(1,"SID_USER")} if kind=="AD_USER" else {(2,"SID_DOM_GRP"),(4,"SID_ALIAS")}
    limits=policy["idmapPolicy"]["domain"]["range"]
    if (value.get("success") is not True or value.get("scope")!=native or value.get("principalType")!=kind
            or value.get("mappingVerified") is not True or value.get("reverseVerified") is not True or value.get("sideEffects") is not False
            or value.get("realm")!=policy["realm"] or value.get("workgroup")!=policy["workgroup"] or value.get("qualifiedName")!=qualified
            or value.get("domainSid")!=policy["domainSid"] or value.get("kind")!=expected_kind
            or type(value.get("sidType")) is not int or (value.get("sidType"),value.get("sidTypeName")) not in allowed
            or not re.fullmatch(re.escape(policy["domainSid"])+r"-[0-9]{1,10}",str(sid_value))
            or type(number) is not int or not limits[0]<=number<=limits[1] or number==65534
            or value.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
            or type(value.get("generatedEpoch")) not in (int,float) or not now-60<=value["generatedEpoch"]<=now+5):
        raise ValueError("AD principal mapping differs from its protected domain/type/reverse authority")
    return value
