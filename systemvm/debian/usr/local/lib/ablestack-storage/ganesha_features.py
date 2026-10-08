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

"""Fresh protected byte attestation for the compiled VFS POSIX ACL runtime."""
import hashlib
import json
import os
from pathlib import Path
import stat
import time
import re
import subprocess

GANESHA_ACL_SOURCE = "2a57b6d53295426247b200cd100ba0741b12aff9"
GANESHA_ACL_PREFIX = "/opt/ablestack-ganesha/5.5.3"


def ganesha_protected_read(path, limit=64*1024):
    path=Path(path);info=path.lstat();parent=path.parent.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid!=0 or info.st_mode&0o022 or info.st_size>limit
            or not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=0 or parent.st_mode&0o022):
        raise ValueError("Ganesha build attestation is not protected")
    descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        opened=os.fstat(descriptor);fields=("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns","st_ctime_ns")
        if any(getattr(info,key)!=getattr(opened,key) for key in fields):raise ValueError("Ganesha attestation changed during open")
        with os.fdopen(descriptor,"rb",closefd=False) as handle:content=handle.read(limit+1)
        after=os.fstat(descriptor)
        if len(content)>limit or any(getattr(opened,key)!=getattr(after,key) for key in fields):raise ValueError("Ganesha attestation changed during read")
        return content,opened
    finally:os.close(descriptor)


MANAGED_GANESHA_UNIT = '[Unit]\nDescription=ABLESTACK Storage Service NFS Ganesha endpoint %i\nAfter=local-fs.target network-online.target rpcbind.service\nWants=network-online.target rpcbind.service\n\n[Service]\nExecCondition=/usr/local/bin/ablestack-storagectl operation generation render-boot-gate %n\nType=simple\nExecStartPre=/bin/mkdir -p /run/ablestack-storage/ganesha /etc/ganesha/ablestack-storage /run/ganesha\nExecStartPre=/usr/bin/find /run/ganesha -maxdepth 1 -name ganesha.pid -type f -delete\nExecStart=/usr/bin/ganesha.nfsd -F -p /run/ablestack-storage/ganesha/%i.pid -L /run/ablestack-storage/ganesha/%i.log -f /etc/ganesha/ablestack-storage/%i.conf\nRestart=on-failure\nRestartSec=2\nKillMode=process\nTimeoutStartSec=30\n\n[Install]\nWantedBy=multi-user.target\n'

GANESHA_SERVICE_BINDINGS = {
 "/etc/systemd/system/ablestack-storage-ganesha@.service.d/ablestack-storage-vfs-acl.conf":
 "[Service]\nExecStart=\nExecStart="+GANESHA_ACL_PREFIX+"/bin/ganesha.nfsd -F -p /run/ablestack-storage/ganesha/%i.pid -L /run/ablestack-storage/ganesha/%i.log -f /etc/ganesha/ablestack-storage/%i.conf\nExecReload=\nExecReload=/bin/kill -HUP $MAINPID\n",
 "/etc/systemd/system/nfs-ganesha.service.d/ablestack-storage-vfs-acl.conf":
 "[Service]\nExecStart=\nExecStart="+GANESHA_ACL_PREFIX+"/bin/ganesha.nfsd -C\nExecReload=\nExecReload=/bin/kill -HUP $MAINPID\n"}


def ganesha_service_binding(base,declared):
    expected={path:hashlib.sha256(content.encode()).hexdigest() for path,content in GANESHA_SERVICE_BINDINGS.items()}
    if declared!=expected:raise ValueError("Ganesha service executable binding attestation differs")
    for path,content in GANESHA_SERVICE_BINDINGS.items():
        actual,_=ganesha_protected_read(base/path.lstrip("/"))
        if actual.decode()!=content:raise ValueError("Ganesha selected service uses another executable")
    path=base/"etc/systemd/system/ablestack-storage-ganesha@.service"
    content,_=ganesha_protected_read(path)
    if content.decode()!=MANAGED_GANESHA_UNIT:raise ValueError("Ganesha managed base unit is foreign")
    # Reject unknown late overrides rather than treating our own drop-in as the
    # effective service selection. Gate-only source drop-ins may coexist.
    for prefix in ("etc/systemd/system","run/systemd/system","usr/lib/systemd/system","lib/systemd/system"):
        owner=base/prefix
        if not owner.exists():continue
        for unit in owner.glob("ablestack-storage-ganesha@*.service"):
            if unit.name!="ablestack-storage-ganesha@.service":
                raise ValueError("Ganesha endpoint has an unverified instance unit override")
        directories=list(owner.glob("ablestack-storage-ganesha@*.service.d"))
        directories.append(owner/"nfs-ganesha.service.d")
        for directory in directories:
            if not directory.exists():continue
            for item in directory.glob("*.conf"):
                relative="/"+str(item.relative_to(base))
                if relative in expected:continue
                content,_=ganesha_protected_read(item)
                if content.decode()!="[Service]\nExecCondition=/usr/local/bin/ablestack-storagectl operation generation render-boot-gate %n\n":
                    raise ValueError("Ganesha service has an unverified override")
    return expected


def ganesha_systemd_effective_binding(base):
    if base!=Path("/") or not Path("/run/systemd/system").is_dir():
        return {"systemdObserved":False,"bindingObservation":"PROTECTED_UNIT_FILES"}
    result={}
    for unit in ("ablestack-storage-ganesha@capability.service","nfs-ganesha.service"):
        observed=subprocess.run(["systemctl","show",unit,"-p","LoadState","-p","ExecStart"],capture_output=True,text=True,timeout=3)
        if observed.returncode:raise ValueError("Ganesha effective systemd binding is unavailable")
        values=dict(line.split("=",1) for line in observed.stdout.splitlines() if "=" in line)
        if values.get("LoadState")=="masked" and unit=="nfs-ganesha.service":
            result[unit]={"masked":True};continue
        selected=re.findall(r"\bpath=([^ ;}]+)",values.get("ExecStart",""))
        if selected!=[GANESHA_ACL_PREFIX+"/bin/ganesha.nfsd"]:
            raise ValueError("Effective systemd Ganesha executable is legacy or foreign")
        result[unit]={"masked":False,"execStartPath":selected[0]}
    return {"systemdObserved":True,"bindingObservation":"SYSTEMD_EFFECTIVE_EXECSTART","effectiveUnitBindings":result}


def ganesha_active_library_binding(base,process_root="/proc"):
    binary=base/GANESHA_ACL_PREFIX.lstrip("/")/"bin/ganesha.nfsd"
    library=base/GANESHA_ACL_PREFIX.lstrip("/")/"lib/x86_64-linux-gnu/ganesha/libfsalvfs.so"
    binary_info=binary.stat();library_info=library.stat();observed=[];deadline=time.monotonic()+3
    for process in Path(process_root).iterdir():
        if time.monotonic()>=deadline:raise ValueError("Ganesha active runtime observation deadline exhausted")
        if not process.name.isdigit():continue
        try:
            executable=os.readlink(process/"exe")
            canonical=executable[:-10] if executable.endswith(" (deleted)") else executable
            if not canonical.endswith("/ganesha.nfsd"):continue
            before=(process/"stat").read_text().rpartition(")")[2].split()[19]
            opened=(process/"exe").stat()
            if (opened.st_dev,opened.st_ino)!=(binary_info.st_dev,binary_info.st_ino) or executable.endswith(" (deleted)"):
                raise ValueError("Active Ganesha is bound to a legacy or replaced executable")
            matched=False
            for line in (process/"maps").read_text().splitlines():
                parts=line.split(None,5)
                if len(parts)!=6 or "libfsalvfs.so" not in parts[5]:continue
                if parts[5]!=str(library) and parts[5]!=GANESHA_ACL_PREFIX+"/lib/x86_64-linux-gnu/ganesha/libfsalvfs.so":
                    raise ValueError("Active Ganesha VFS library is legacy or foreign")
                major,minor=(int(value,16) for value in parts[3].split(":"))
                if (os.makedev(major,minor),int(parts[4]))!=(library_info.st_dev,library_info.st_ino):
                    raise ValueError("Active Ganesha VFS library inode differs from its manifest")
                matched=True
            if not matched:raise ValueError("Active Ganesha VFS mapping is unobservable")
            after=(process/"stat").read_text().rpartition(")")[2].split()[19]
            if before!=after:raise ValueError("Active Ganesha PID identity changed while inspecting")
            observed.append({"pid":int(process.name),"startTicks":before,"executable":GANESHA_ACL_PREFIX+"/bin/ganesha.nfsd",
                             "vfsDevice":library_info.st_dev,"vfsInode":library_info.st_ino})
        except FileNotFoundError:continue
    return observed

def ganesha_acl_capabilities(manifest=None,root=None,process_root="/proc"):
    path=Path(manifest or "/etc/ablestack-storage/ganesha-build-manifest.json");base=Path(root or "/")
    result={"success":True,"generatedEpoch":time.time(),"nfsVfsPosixAclSupported":False,
            "ganeshaVersion":None,"ganeshaBuildManifestSha256":None,"vfsLibrarySha256":None,
             "posixAclBuildEnabled":False,"posixAclSelfTestVerified":False,"configuredExecutableBindingVerified":False,
            "activeRuntimeVfsVerified":False,"activeGaneshaProcesses":[],"supportedFeatures":[]}
    try:
        data,_=ganesha_protected_read(path);value=json.loads(data)
        expected_flags={"ENABLE_VFS_POSIX_ACL":"ON","ENABLE_VFS_DEBUG_ACL":"OFF","USE_FSAL_VFS":"ON","USE_DBUS":"ON"}
        if (type(value.get("schemaVersion")) is not int or value.get("schemaVersion")!=1 or value.get("sourceCommit")!=GANESHA_ACL_SOURCE or value.get("version")!="5.5.3"
                or value.get("requiredCmakeFlags")!=expected_flags or value.get("prefix")!=GANESHA_ACL_PREFIX
                or value.get("selfTest",{}).get("namedAclReadWrite") is not True or value.get("selfTest",{}).get("defaultAclInheritance") is not True):
            raise ValueError("Ganesha build or real ACL self-test attestation is incomplete")
        files=value.get("files")
        required={"bin/ganesha.nfsd","lib/x86_64-linux-gnu/libganesha_nfsd.so.5.5.3","lib/x86_64-linux-gnu/libntirpc.so.5.0","lib/x86_64-linux-gnu/ganesha/libfsalvfs.so"}
        if not isinstance(files,dict) or set(files)!=required:raise ValueError("Ganesha fixed runtime file attestation differs")
        for name,expected in files.items():
            if not isinstance(expected,str) or len(expected)!=64:raise ValueError("Ganesha file digest is invalid")
            content,_=ganesha_protected_read(base/GANESHA_ACL_PREFIX.lstrip("/")/name,32*1024*1024)
            if hashlib.sha256(content).hexdigest()!=expected:raise ValueError("Ganesha installed runtime bytes changed")
        bindings=ganesha_service_binding(base,value.get("serviceBindings"))
        selected=ganesha_systemd_effective_binding(base)
        processes=ganesha_active_library_binding(base,process_root)
        result.update(**selected,configuredExecutableBindingVerified=True,activeRuntimeVfsVerified=bool(processes),
                      activeRuntimeNotApplicable=not bool(processes),activeGaneshaProcesses=processes,
                      serviceBindings=bindings,nfsVfsPosixAclSupported=True,ganeshaVersion=value["version"],
                      ganeshaBuildManifestSha256=hashlib.sha256(data).hexdigest(),
                      vfsLibrarySha256=files["lib/x86_64-linux-gnu/ganesha/libfsalvfs.so"],
                      posixAclBuildEnabled=True,posixAclSelfTestVerified=True,supportedFeatures=["NFS_VFS_POSIX_ACL"])
    except (OSError,ValueError,TypeError,KeyError,IndexError,subprocess.TimeoutExpired) as error:
        result["diagnostic"]=str(error)
    return result
