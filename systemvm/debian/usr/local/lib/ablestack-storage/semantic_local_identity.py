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

"""RID-backed tdbsam4 LOCAL import; original SAM and AD material are excluded."""
import base64
import ctypes
import fcntl
import os
from pathlib import Path
import re
import struct
import time
from ad_identity import bounded_ad_run
from identity_capsule import owned_account_records,account_merge,regular_file,restore,MAX_CAPSULE_BYTES
from samba_public_sid import samba_public_sid,PublicSidTdbData
from semantic_identity_alias import semantic_managed_aliases

LOCAL_PASSDB="/var/lib/samba/private/passdb.tdb"
LOCAL_SEMANTIC_FILES={LOCAL_PASSDB,"/etc/ablestack-storage/smb-managed-identities.json","/etc/ablestack-storage/smb-local-account-provenance.json"}


class SemanticRamTdb:
    def __init__(self,data):
        temporary=os.memfd_create("semantic-passdb",os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING)
        try:self.fd=fcntl.fcntl(temporary,fcntl.F_DUPFD_CLOEXEC,16)
        finally:os.close(temporary)
        os.fchmod(self.fd,0o600);os.write(self.fd,data)
        self.lib=ctypes.CDLL("libtdb.so.1")
        self.lib.tdb_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_uint];self.lib.tdb_open.restype=ctypes.c_void_p
        self.lib.tdb_fetch.argtypes=[ctypes.c_void_p,PublicSidTdbData];self.lib.tdb_fetch.restype=PublicSidTdbData
        self.lib.tdb_store.argtypes=[ctypes.c_void_p,PublicSidTdbData,PublicSidTdbData,ctypes.c_int];self.lib.tdb_store.restype=ctypes.c_int
        self.lib.tdb_firstkey.argtypes=[ctypes.c_void_p];self.lib.tdb_firstkey.restype=PublicSidTdbData
        self.lib.tdb_nextkey.argtypes=[ctypes.c_void_p,PublicSidTdbData];self.lib.tdb_nextkey.restype=PublicSidTdbData
        self.lib.tdb_close.argtypes=[ctypes.c_void_p]
        self.free=ctypes.CDLL(None).free;self.free.argtypes=[ctypes.c_void_p]
        self.db=self.lib.tdb_open(("/proc/self/fd/"+str(self.fd)).encode(),0,0,os.O_RDWR,0)
        if not self.db:
            os.close(self.fd);raise ValueError("Authenticated semantic passdb cannot open in RAM")
    def close(self):
        if self.db:self.lib.tdb_close(self.db);self.db=None
        os.close(self.fd)
    def fetch(self,key):
        buffer=ctypes.create_string_buffer(key);value=self.lib.tdb_fetch(self.db,PublicSidTdbData(ctypes.cast(buffer,ctypes.c_void_p),len(key)))
        try:
            if not value.dptr:return None
            if value.dsize>MAX_CAPSULE_BYTES:raise ValueError("Semantic record exceeds its bound")
            return ctypes.string_at(value.dptr,value.dsize)
        finally:
            if value.dptr:self.free(value.dptr)
    def keys(self):
        result=[];value=self.lib.tdb_firstkey(self.db)
        while value.dptr:
            try:
                if value.dsize>512 or len(result)>=4096:raise ValueError("Semantic passdb key inventory exceeds its bound")
                result.append(ctypes.string_at(value.dptr,value.dsize))
                following=self.lib.tdb_nextkey(self.db,value)
            finally:self.free(value.dptr)
            value=following
        return result
    def store(self,key,value):
        k=ctypes.create_string_buffer(key);v=ctypes.create_string_buffer(value)
        if self.lib.tdb_store(self.db,PublicSidTdbData(ctypes.cast(k,ctypes.c_void_p),len(key)),PublicSidTdbData(ctypes.cast(v,ctypes.c_void_p),len(value)),3):
            raise ValueError("Semantic public-domain update in RAM failed")
    def bytes(self):
        if not 0<os.fstat(self.fd).st_size<=MAX_CAPSULE_BYTES:raise ValueError("Semantic passdb size is invalid")
        os.lseek(self.fd,0,os.SEEK_SET);return os.read(self.fd,MAX_CAPSULE_BYTES+1)


def semantic_samu_public(value,name,source_identity,target_name):
    # Samba 4.17 SAMU_BUFFER_V3/V4: seven uint32 times, twelve B
    # strings, user/group RID, then private fields. Parse no hash/history bytes.
    if not isinstance(value,bytes) or len(value)<28:raise ValueError("Semantic tdbsam4 record is truncated")
    offset=28;strings=[];encoded=[]
    for _ in range(12):
        if offset+4>len(value):raise ValueError("Semantic tdbsam4 public string header is truncated")
        size=struct.unpack_from("<I",value,offset)[0];offset+=4
        if size>65536 or offset+size>len(value):raise ValueError("Semantic tdbsam4 public string is unbounded")
        part=value[offset:offset+size];offset+=size;strings.append(part);encoded.append(struct.pack("<I",size)+part)
    if strings[0]!=name.encode()+bytes([0]) or offset+8>len(value):raise ValueError("Semantic passdb key/username differs")
    rid,group_rid=struct.unpack_from("<II",value,offset)
    if not 1000<=rid<=4294967295 or not 0<group_rid<=4294967295:raise ValueError("Semantic passdb RID is reserved or invalid")
    domain=strings[1].rstrip(bytes([0])).decode("ascii")
    if domain not in (source_identity["netbiosName"],source_identity["workgroup"],target_name):
        raise ValueError("Semantic local account domain is foreign")
    if domain==source_identity["netbiosName"]:
        new=target_name.encode()+bytes([0]);encoded[1]=struct.pack("<I",len(new))+new
    result=value[:28]+b"".join(encoded)+value[offset:]
    # The entire RID/private tail is preserved exactly.
    return {"rid":rid,"groupRid":group_rid},result


def semantic_passdb_namespace(database,owned,source_identity,target_sid,target_name):
    if database.fetch(b"INFO/version\0")!=struct.pack("<I",4) or database.fetch(b"INFO/minor_version\0")!=struct.pack("<I",0):
        raise ValueError("Semantic LOCAL requires exact tdbsam4; implicit conversion is forbidden")
    keys=database.keys();allowed={b"INFO/version\0",b"INFO/minor_version\0",b"NEXT_RID\0"};rows=[];used=set()
    for key in keys:
        if key in allowed:continue
        if key.startswith(b"USER_") and key.endswith(bytes([0])):
            name=key[5:-1].decode("ascii")
            if name not in owned:raise ValueError("Semantic passdb contains an unowned account")
            metadata,rebased=semantic_samu_public(database.fetch(key),name,source_identity,target_name)
            rid=metadata["rid"]
            if rid in used:raise ValueError("Semantic passdb user RID collision")
            used.add(rid)
            if database.fetch(("RID_%08x"%rid).encode()+bytes([0]))!=name.encode()+bytes([0]):
                raise ValueError("Semantic passdb RID reverse authority differs")
            unix=owned[name].split(":");rows.append({"name":name,"uid":int(unix[2]),"gid":int(unix[3]),"rid":rid,"userSid":target_sid+"-"+str(rid)})
            if rebased!=database.fetch(key):database.store(key,rebased)
        elif not re.fullmatch(b"RID_[0-9a-f]{8}\0",key):
            raise ValueError("Semantic passdb has unsupported privilege or foreign keys")
    expected={("RID_%08x"%row["rid"]).encode()+bytes([0]) for row in rows}
    if {key for key in keys if key.startswith(b"RID_")}!=expected:raise ValueError("Semantic passdb forward/reverse RID union differs")
    next_rid=database.fetch(b"NEXT_RID\0")
    if next_rid is None or len(next_rid)!=4 or (used and struct.unpack("<I",next_rid)[0]<=max(used)):
        raise ValueError("Semantic passdb next RID counter is unavailable or collides")
    return sorted(rows,key=lambda row:row["name"])


class SemanticLocalIdentity:
    def __init__(self,run=None,read=None,apply=None,sid_reader=None):
        self.run=run or bounded_ad_run;self.read=read or regular_file;self.apply=apply or restore
        self.sid_reader=sid_reader or samba_public_sid;self.deadline=time.monotonic()+120
    def restore_local(self,original,target_sid,target_name,resource_mappings=None):
        payload=original["payload"];owned=owned_account_records(payload["files"]);accounts=payload["accounts"]
        aliases=semantic_managed_aliases(original,resource_mappings if resource_mappings is not None else [])
        if self.sid_reader(target_name)!=target_sid:raise ValueError("Semantic target SAM differs before LOCAL import")
        for path in ("/etc/passwd","/etc/group"):
            incoming=accounts.get(path,[])
            if {line.split(":",1)[0]:line for line in incoming}!=owned[path]:raise ValueError("Semantic Unix identities lack exact source provenance")
        for path,lines in accounts.items():
            names={line.split(":",1)[0] for line in lines};public="/etc/passwd" if path in ("/etc/passwd","/etc/shadow") else "/etc/group"
            if not names<=set(owned[public]):raise ValueError("Semantic secret account names lack public ownership")
            current,_=self.read(path);account_merge(current.decode(),lines,Path(path).name)
        local={path:dict(item) for path,item in payload["files"].items() if path in LOCAL_SEMANTIC_FILES};item=local.get(LOCAL_PASSDB);mappings=[]
        if item is not None and item.get("absent") is not True:
            if item.get("uid")!=0 or item.get("mode")!=0o600:raise ValueError("Semantic passdb is unprotected")
            data=base64.b64decode(item["data"],validate=True)
            database=SemanticRamTdb(data)
            try:
                mappings=semantic_passdb_namespace(database,owned["/etc/passwd"],original["identity"],target_sid,target_name)
                local[LOCAL_PASSDB]={"data":base64.b64encode(database.bytes()).decode(),"uid":0,"gid":item["gid"],"mode":0o600}
            finally:database.close()
        elif any(not name.startswith("sf_u_") for name in owned["/etc/passwd"]):raise ValueError("Authenticated semantic LOCAL accounts have no source passdb")
        # Existing target Unix and passdb unions must be owned by the same
        # source names/RIDs, even when restoring an earlier password snapshot.
        try:target_data,_=self.read(LOCAL_PASSDB)
        except FileNotFoundError:target_data=None
        if target_data:
            database=SemanticRamTdb(target_data)
            try:
                observed=semantic_passdb_namespace(database,owned["/etc/passwd"],{**original["identity"],"netbiosName":target_name},target_sid,target_name)
                incoming={row["name"]:row["rid"] for row in mappings}
                if any(incoming.get(row["name"])!=row["rid"] for row in observed):raise ValueError("Semantic target passdb has a foreign RID union")
            finally:database.close()
        self.apply({"schemaVersion":1,"files":local,"accounts":accounts})
        if self.sid_reader(target_name)!=target_sid:raise ValueError("Semantic LOCAL import changed target SAM")
        for path in ("/etc/passwd","/etc/group"):
            actual,_=self.read(path);rows={line.split(":",1)[0]:line for line in actual.decode().splitlines()}
            if any(rows.get(name)!=value for name,value in owned[path].items()):raise ValueError("Semantic Unix UID/GID readback differs")
        result=self.run(["pdbedit","--list","--verbose"],capture_output=True,text=True,timeout=min(15,self.deadline-time.monotonic()))
        if result.returncode:raise ValueError("Semantic Samba public namespace readback failed")
        blocks=re.split(r"(?m)^Unix username:\s*",result.stdout);observed={}
        if blocks[0].replace("-","").strip():raise ValueError("Semantic public passdb readback prefix is invalid")
        for block in blocks[1:]:
            name=block.splitlines()[0].strip();matches=re.findall(r"(?m)^User SID:\s*(S-[0-9-]+)\s*$",block)
            if name in observed or len(matches)!=1:raise ValueError("Semantic public passdb readback is repeated or incomplete")
            observed[name]=matches[0]
        if observed!={row["name"]:row["userSid"] for row in mappings}:raise ValueError("Semantic user RID does not resolve in target SAM namespace")
        return {"localAccountsRestored":True,"localPassdbSidRebased":True,"targetSamPreserved":True,"publicMappings":mappings,"publicGroupMappings":[{"name":name,"gid":int(value.split(":")[2])} for name,value in sorted(owned["/etc/group"].items())],"managedIdentityMappings":aliases}
