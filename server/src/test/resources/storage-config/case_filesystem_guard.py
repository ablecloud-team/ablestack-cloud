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

"""Test-only final-path checks; the mandatory OS sandbox is authoritative."""
import errno
import os
import stat
import threading

_REALPATH=os.path.realpath
_READLINK=os.readlink
_LSTAT=os.lstat
_STAT=os.stat
_FSTAT=os.fstat
_GETCWD=os.getcwd

class CasePathDenied(PermissionError):
    def __init__(self,code):
        super().__init__(errno.EPERM,code)
        self.code=code

def denied(code):
    raise CasePathDenied(code)

def _physical_realpath(path,*,strict=False):
    # Fixture transports are single-threaded. Never mix logical daemon aliases
    # into a security decision about actual filesystem containment.
    if threading.active_count()!=1 or threading.current_thread()is not threading.main_thread():
        denied("CASE_RAW_RESOLVER_REQUIRES_SINGLE_THREAD")
    previous=os.readlink
    os.readlink=_READLINK
    try:return _REALPATH(path,strict=strict)
    finally:os.readlink=previous  # Nested calls restore their immediate caller.

class CasePathGuard:
    def __init__(self,root):
        self.root=os.path.abspath(os.fspath(root))
        if _physical_realpath(self.root,strict=True)!=self.root:denied("CASE_ROOT_ALIAS")
        info=_LSTAT(self.root)
        if not stat.S_ISDIR(info.st_mode)or info.st_uid!=os.geteuid()or stat.S_IMODE(info.st_mode)!=0o700:
            denied("CASE_ROOT_NOT_PRIVATE_OWNED")
        self.identity=(info.st_dev,info.st_ino,info.st_uid,info.st_gid,stat.S_IMODE(info.st_mode))
        # No host invocation or weak fallback is permitted. Namespace comparison
        # is additionally enforced by the mandatory launcher before this module.
        if not os.statvfs("/").f_flag&os.ST_RDONLY or os.statvfs(self.root).f_flag&os.ST_RDONLY:
            denied("CASE_OS_SANDBOX_REQUIRED")
        with open("/proc/self/status",encoding="ascii")as stream:
            caps=next((line.split()[1]for line in stream if line.startswith("CapEff:")),None)
        if caps is None or int(caps,16)!=0:denied("CASE_CAPABILITIES_NOT_DROPPED")

    def check_root(self):
        info=_LSTAT(self.root)
        if (info.st_dev,info.st_ino,info.st_uid,info.st_gid,stat.S_IMODE(info.st_mode))!=self.identity:
            denied("CASE_ROOT_CHANGED")

    def inside(self,path):
        return os.path.commonpath((self.root,path))==self.root

    def fd(self,fd,directory=False,stdio=False):
        self.check_root()
        info=_FSTAT(fd)
        if stdio and fd in(1,2)and(stat.S_ISFIFO(info.st_mode)or stat.S_ISSOCK(info.st_mode)):return
        name=_READLINK("/proc/self/fd/"+str(fd))
        if not name.startswith("/")or name.endswith(" (deleted)"):denied("CASE_UNNAMED_FD")
        actual=_physical_realpath(name,strict=True)
        if not self.inside(actual):denied("CASE_FD_OUTSIDE")
        named=_STAT(actual)
        if (info.st_dev,info.st_ino)!=(named.st_dev,named.st_ino):denied("CASE_FD_CHANGED")
        if directory and not stat.S_ISDIR(info.st_mode):denied("CASE_FD_NOT_DIRECTORY")
        if stat.S_ISREG(info.st_mode)and info.st_nlink!=1:denied("CASE_HARDLINK")
        return actual

    def path(self,path,dir_fd=None,root_ok=False):
        self.check_root()
        if type(path)is int:return self.fd(path)
        value=os.fsdecode(path)
        if not os.path.isabs(value):
            base=self.fd(dir_fd,directory=True)if dir_fd not in(None,-1)else _GETCWD()
            value=os.path.join(base,value)
        actual=_physical_realpath(os.path.abspath(value),strict=False)
        if not self.inside(actual):denied("CASE_PATH_OUTSIDE")
        if actual==self.root and not root_ok:denied("CASE_ROOT_MUTATION")
        try:info=_STAT(actual)
        except FileNotFoundError:info=None
        if info is not None and stat.S_ISREG(info.st_mode)and info.st_nlink!=1:denied("CASE_HARDLINK")
        return actual

    def symlink(self,target,destination,dir_fd=None):
        final=self.path(destination,dir_fd)
        value=os.fsdecode(target)
        actual=_physical_realpath(value if os.path.isabs(value)else os.path.join(os.path.dirname(final),value),strict=False)
        if not self.inside(actual):denied("CASE_SYMLINK_OUTSIDE")

def is_write_mode(mode):
    return isinstance(mode,str)and any(char in mode for char in"wax+")

def is_write_flags(flags):
    return bool(flags&(os.O_WRONLY|os.O_RDWR|os.O_CREAT|os.O_TRUNC|os.O_APPEND|os.O_EXCL))
