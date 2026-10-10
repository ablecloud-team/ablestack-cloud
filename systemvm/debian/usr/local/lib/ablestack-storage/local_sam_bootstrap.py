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

"""Explicit pre-capture target SAM creation; readonly probes never initialize it."""
import ctypes
import json
import os
from pathlib import Path
import re
import stat
import time
import uuid
from ad_identity import bounded_ad_run,ad_protected_json
from samba_public_sid import samba_public_sid,SambaPublicSidMissing,PublicSidTdbData
from posix_root_initialization import root_receipt_read,root_receipt_write


class LocalSamBootstrap:
    def __init__(self,cli,configuration=None,root=None,run=None,sid_reader=None,writer=None,quiescence=None,private_root=None):
        self.private_root=Path(private_root or "/var/lib/samba")
        self.cli=str(cli);self.configuration=Path(configuration or os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT","/etc/ablestack-storage"))
        self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))/"local-sam-bootstrap"
        self.run=run or bounded_ad_run;self.sid_reader=sid_reader or samba_public_sid
        self.writer=writer or self.require_writer;self.quiescence=quiescence or self.require_quiescence;self.deadline=time.monotonic()+60

    def command(self,args):
        remaining=self.deadline-time.monotonic()
        if remaining<=0:raise TimeoutError("Explicit SAM bootstrap deadline expired")
        value=self.run(args,capture_output=True,text=True,timeout=min(10,remaining))
        if value.returncode:raise ValueError("Explicit SAM bootstrap observation/command failed")
        return value.stdout.strip()

    def require_writer(self):
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD")!="9":raise ValueError("SAM bootstrap requires its native writer")
        info=os.fstat(9);named=Path(os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FILE","/run/ablestack-storage/desired-writer.lock")).lstat()
        pattern=r"(?m)^lock:\s+[0-9]+:\s+FLOCK\s+ADVISORY\s+WRITE\s+[0-9]+\s+[0-9a-f]+:[0-9a-f]+:"+str(info.st_ino)+r"\s+0\s+EOF\s*$"
        if (not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600
                or (info.st_dev,info.st_ino)!=(named.st_dev,named.st_ino) or not re.search(pattern,Path("/proc/self/fdinfo/9").read_text())):
            raise ValueError("SAM bootstrap writer does not hold the exact named exclusive descriptor")

    def require_quiescence(self):
        for process in Path("/proc").iterdir():
            if not process.name.isdigit():continue
            try:name=(process/"comm").read_text().strip()
            except FileNotFoundError:continue
            if name in ("smbd","nmbd","winbindd","samba","samba-dcerpcd","samba-bgqd"):raise ValueError("Missing SAM initialization requires quiescent identity daemons")

    def require_empty_private(self):
        for relative in ("private/secrets.tdb","private/passdb.tdb","winbindd_idmap.tdb"):
            path=self.private_root/relative
            if not path.exists() and not path.is_symlink():continue
            info=path.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600:
                raise ValueError("Fresh SAM bootstrap private database is foreign")
            descriptor=os.open(path,os.O_RDONLY|os.O_NOFOLLOW);database=None;key=None
            try:
                lib=ctypes.CDLL("libtdb.so.1");lib.tdb_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_uint];lib.tdb_open.restype=ctypes.c_void_p
                lib.tdb_firstkey.argtypes=[ctypes.c_void_p];lib.tdb_firstkey.restype=PublicSidTdbData
                lib.tdb_close.argtypes=[ctypes.c_void_p];lib.tdb_close.restype=ctypes.c_int
                opened=os.fstat(descriptor)
                if (opened.st_dev,opened.st_ino)!=(info.st_dev,info.st_ino):raise ValueError("SAM bootstrap database changed")
                database=lib.tdb_open(("/proc/self/fd/"+str(descriptor)).encode(),0,0,os.O_RDONLY,0)
                if not database:raise ValueError("Fresh SAM bootstrap cannot prove an empty database")
                key=lib.tdb_firstkey(database)
                if key.dptr:raise ValueError("Fresh SAM bootstrap refuses nonempty foreign SAM/AD records")
                after=path.lstat()
                if (after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns)!=(opened.st_dev,opened.st_ino,opened.st_size,opened.st_mtime_ns):
                    raise ValueError("SAM bootstrap database changed during empty observation")
            finally:
                if key is not None and key.dptr:
                    allocator=ctypes.CDLL(None);allocator.free.argtypes=[ctypes.c_void_p];allocator.free(key.dptr)
                if database:lib.tdb_close(database)
                os.close(descriptor)

    def initialize(self,request):
        keys={"instanceUuid","operationUuid","revision","netbiosName","initializationApproved","expectedGeneration","expectedConfigurationSha256","expectedBootId"}
        if not isinstance(request,dict) or set(request)!=keys or request["initializationApproved"] is not True:
            raise ValueError("Local SAM initialization requires an explicit closed approved request")
        scope={key:request[key] for key in ("instanceUuid","operationUuid","revision")}
        for key in ("instanceUuid","operationUuid"):
            if not isinstance(scope[key],str) or str(uuid.UUID(scope[key]))!=scope[key]:raise ValueError("SAM bootstrap UUID is invalid")
        if type(scope["revision"]) is not int or scope["revision"]<1:raise ValueError("SAM bootstrap revision is invalid")
        name=request["netbiosName"]
        if not isinstance(name,str) or not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,14}",name):raise ValueError("SAM bootstrap machine name is invalid")
        self.writer()
        marker=json.loads(self.command([self.cli,"operation","maintenance","status"]))
        generation=json.loads(self.command([self.cli,"operation","generation","status"]));current=generation.get("generation") or {}
        if (marker.get("bootHeld") is not False or generation.get("generationStatus")!="IN_SYNC" or generation.get("pendingOperationUuid")
                or current.get("instanceUuid")!=scope["instanceUuid"] or type(current.get("revision")) is not int or current["revision"]>scope["revision"]):
            raise ValueError("SAM bootstrap requires its settled target before any SERVICE/ROOT hold")
        if (current!=request["expectedGeneration"] or generation.get("configurationSha256")!=request["expectedConfigurationSha256"]
                or generation.get("bootId")!=request["expectedBootId"]):raise ValueError("SAM bootstrap source generation/SHA/boot is stale")
        actual_name=self.command(["testparm","-s","--parameter-name=netbios name"]).upper()
        if actual_name!=name:raise ValueError("SAM bootstrap name differs from the target's actual configured name")
        state=ad_protected_json(self.configuration/"smb-domain.json",True)
        joined=state is not None and str(state.get("state") or state.get("joinState") or "").upper()=="JOINED"
        if state is not None and state.get("instanceUuid")!=scope["instanceUuid"]:raise ValueError("SAM bootstrap state belongs to another instance")
        if joined and state.get("netbiosName")!=name:raise ValueError("SAM bootstrap cannot rename a joined AD identity")
        path=self.root/(scope["operationUuid"]+".json");record=root_receipt_read(path)
        if record is not None:
            if (record.get("scope")!=scope or record.get("netbiosName")!=name or record.get("bootId")!=generation["bootId"]
                    or record.get("generation")!=current or record.get("configurationSha256")!=generation["configurationSha256"]):
                raise ValueError("SAM bootstrap journal belongs to another target/source/boot")
        try:
            existing=self.sid_reader(name)
        except (FileNotFoundError,SambaPublicSidMissing):existing=None
        if joined and (existing is None or existing!=(state.get("identityReceipt") or {}).get("machineSid")):
            raise ValueError("Joined AD target SAM is missing or differs; bootstrap cannot replace it")
        if record is not None and existing is not None:
            if existing!=record["localMachineSid"]:raise ValueError("SAM bootstrap planned SID was replaced before replay")
            if record["phase"]!="COMPLETE":
                record["phase"]="COMPLETE";root_receipt_write(path,record)
        if existing is not None:return {"success":True,"scope":scope,"localSamInitialized":False,"localMachineSid":existing,"netbiosName":name,"identityPreserved":True,"sideEffects":False,"bootId":generation["bootId"],"generation":current,"configurationSha256":generation["configurationSha256"],"canonicalDesiredStateChanged":False}
        self.quiescence();self.require_empty_private()
        if record is not None:
            if record.get("scope")!=scope or record.get("netbiosName")!=name or record.get("bootId")!=generation["bootId"]:
                raise ValueError("SAM bootstrap journal belongs to another target/boot")
            planned=record["localMachineSid"]
        else:
            planned="S-1-5-21-"+"-".join(str(int.from_bytes(os.urandom(4),"little")) for _ in range(3))
            record={"schemaVersion":1,"scope":scope,"phase":"PREPARED","netbiosName":name,"localMachineSid":planned,"bootId":generation["bootId"],"generation":current,"configurationSha256":generation["configurationSha256"]}
            root_receipt_write(path,record)
        self.command(["net","setlocalsid",planned])
        if self.sid_reader(name)!=planned:raise ValueError("Explicit target SAM initialization readback differs")
        after=json.loads(self.command([self.cli,"operation","generation","status"]))
        if after.get("generation")!=current or after.get("configurationSha256")!=generation.get("configurationSha256") or after.get("bootId")!=generation["bootId"]:
            raise ValueError("SAM bootstrap changed a canonical generation or boot")
        record["phase"]="COMPLETE";root_receipt_write(path,record)
        return {"success":True,"scope":scope,"localSamInitialized":True,"localMachineSid":planned,"netbiosName":name,"identityPreserved":False,"sideEffects":True,
                "canonicalDesiredStateChanged":False,"bootId":generation["bootId"],"generation":current,"configurationSha256":generation["configurationSha256"]}
