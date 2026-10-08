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

"""One existing public local SAM SID key through libtdb opened O_RDONLY."""
import ctypes
import os
from pathlib import Path
import re
import stat
import struct


class PublicSidTdbData(ctypes.Structure):
    _fields_=[("dptr",ctypes.c_void_p),("dsize",ctypes.c_size_t)]


def samba_sid_bytes(value):
    # Samba 4.17 stores struct dom_sid (revision, count, authority, 15 LE u32).
    if not isinstance(value,bytes) or len(value)!=68 or value[0]!=1 or value[1]!=4 or value[2:8]!=bytes([0,0,0,0,0,5]):
        raise ValueError("Existing public SAM SID has an unsupported binary shape")
    subauthorities=struct.unpack("<4I",value[8:24])
    if subauthorities[0]!=21:raise ValueError("Existing public SID is not a local domain SAM identity")
    return "S-1-5-"+ "-".join(str(number) for number in subauthorities)


def samba_public_sid(netbios,path=None,library=None):
    if not isinstance(netbios,str) or not re.fullmatch(r"[A-Z0-9][A-Z0-9_-]{0,14}",netbios):
        raise ValueError("Public SAM SID requires its exact validated NetBIOS machine name")
    path=Path(path or "/var/lib/samba/private/secrets.tdb")
    parent=path.parent.lstat();info=path.lstat();fields=("st_dev","st_ino","st_uid","st_gid","st_mode","st_size","st_mtime_ns","st_ctime_ns")
    if (not stat.S_ISDIR(parent.st_mode) or parent.st_uid!=os.geteuid() or parent.st_mode&0o022
            or not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or stat.S_IMODE(info.st_mode)!=0o600
            or info.st_nlink!=1 or not 0<info.st_size<=64*1024*1024):
        raise ValueError("Existing SAM SID database is not protected")
    directory=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW);descriptor=None;database=None;data=None
    lib=library
    try:
        actual_parent=os.fstat(directory)
        if (actual_parent.st_dev,actual_parent.st_ino)!=(parent.st_dev,parent.st_ino):raise ValueError("SAM SID database parent changed")
        descriptor=os.open(path.name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=directory);opened=os.fstat(descriptor)
        if any(getattr(opened,key)!=getattr(info,key) for key in fields):raise ValueError("SAM SID database changed during open")
        if lib is None:lib=ctypes.CDLL("libtdb.so.1",use_errno=True)
        lib.tdb_open.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_uint];lib.tdb_open.restype=ctypes.c_void_p
        lib.tdb_fetch.argtypes=[ctypes.c_void_p,PublicSidTdbData];lib.tdb_fetch.restype=PublicSidTdbData
        lib.tdb_close.argtypes=[ctypes.c_void_p];lib.tdb_close.restype=ctypes.c_int
        # O_RDONLY and no TDB_CLEAR_IF_FIRST/CREATE flags. The descriptor-bound
        # proc path prevents a named replacement from selecting another file.
        database=lib.tdb_open(("/proc/self/fd/"+str(descriptor)).encode(),0,0,os.O_RDONLY,0)
        if not database:raise ValueError("Existing public SAM SID database cannot be opened read-only")
        key=("SECRETS/SID/"+netbios).encode();buffer=ctypes.create_string_buffer(key)
        data=lib.tdb_fetch(database,PublicSidTdbData(ctypes.cast(buffer,ctypes.c_void_p),len(key)))
        if not data.dptr or data.dsize!=68:raise ValueError("Existing exact machine SAM SID key is absent or malformed")
        result=samba_sid_bytes(ctypes.string_at(data.dptr,data.dsize))
        after=os.fstat(descriptor);named=os.stat(path.name,dir_fd=directory,follow_symlinks=False);named_parent=path.parent.lstat()
        if (any(getattr(opened,key)!=getattr(after,key) or getattr(opened,key)!=getattr(named,key) for key in fields)
                or (named_parent.st_dev,named_parent.st_ino)!=(parent.st_dev,parent.st_ino)):
            raise ValueError("SAM SID database or named parent changed during the public key read")
        return result
    finally:
        if data is not None and data.dptr:
            allocator=ctypes.CDLL(None);allocator.free.argtypes=[ctypes.c_void_p];allocator.free.restype=None;allocator.free(data.dptr)
        if database:lib.tdb_close(database)
        if descriptor is not None:os.close(descriptor)
        os.close(directory)
