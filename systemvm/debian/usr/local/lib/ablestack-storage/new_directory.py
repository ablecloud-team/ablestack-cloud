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

"""Initialize a private new inode before an atomic no-replace directory publish."""
import ctypes
import errno
import os
import stat
import uuid


def publish_new_directory(parent_fd, stage_fd, name, owner_uid=None, owner_gid=None, mode=0o750):
    if not isinstance(name,str) or not name or name in ('.','..') or '/' in name or '\\' in name or any(ord(ch)<32 for ch in name):
        raise ValueError('New directory name is not a bounded single component')
    for value in (owner_uid,owner_gid):
        if value is not None and (isinstance(value,bool) or not isinstance(value,int) or not 0<=value<=2147483647):
            raise ValueError('New directory owner must be an explicit numeric UID/GID')
    if isinstance(mode,bool) or not isinstance(mode,int) or not 0<=mode<=0o7777:raise ValueError('New directory mode is invalid')
    stage=os.fstat(stage_fd);parent=os.fstat(parent_fd)
    if (not stat.S_ISDIR(stage.st_mode) or stage.st_uid!=os.geteuid() or stat.S_IMODE(stage.st_mode)!=0o700
            or stage.st_dev!=parent.st_dev):raise ValueError('New directory staging parent is not a protected directory on the same filesystem')
    try:
        existing=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent_fd)
    except FileNotFoundError:
        existing=None
    if existing is not None:
        os.close(existing);return None
    private='.new-directory-'+str(uuid.uuid4())
    os.mkdir(private,mode=0o700,dir_fd=stage_fd)
    descriptor=os.open(private,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=stage_fd)
    created=None
    try:
        created=os.fstat(descriptor)
        if created.st_uid!=os.geteuid() or created.st_dev!=parent.st_dev:raise ValueError('Private new directory identity changed')
        if owner_uid is not None or owner_gid is not None:
            os.fchown(descriptor,owner_uid if owner_uid is not None else -1,owner_gid if owner_gid is not None else -1)
        os.fchmod(descriptor,mode);os.fsync(descriptor)
        library=ctypes.CDLL(None,use_errno=True)
        rename=getattr(library,'renameat2',None)
        if rename is None:raise ValueError('Atomic no-replace directory publication is unavailable')
        rename.argtypes=[ctypes.c_int,ctypes.c_char_p,ctypes.c_int,ctypes.c_char_p,ctypes.c_uint]
        rename.restype=ctypes.c_int
        if rename(stage_fd,os.fsencode(private),parent_fd,os.fsencode(name),1):
            number=ctypes.get_errno()
            if number==errno.EEXIST:return None
            raise OSError(number,os.strerror(number))
        os.fsync(stage_fd);os.fsync(parent_fd)
        named=os.stat(name,dir_fd=parent_fd,follow_symlinks=False);opened=os.fstat(descriptor)
        if (named.st_dev,named.st_ino)!=(created.st_dev,created.st_ino):
            raise ValueError('Published directory was replaced; replacement metadata remains untouched')
        return {'created':True,'device':opened.st_dev,'inode':opened.st_ino,'effectiveUid':opened.st_uid,
                'effectiveGid':opened.st_gid,'effectiveMode':format(stat.S_IMODE(opened.st_mode),'04o')}
    finally:
        os.close(descriptor)
        try:
            leftover=os.stat(private,dir_fd=stage_fd,follow_symlinks=False)
            if created is not None and (leftover.st_dev,leftover.st_ino)==(created.st_dev,created.st_ino):os.rmdir(private,dir_fd=stage_fd)
        except FileNotFoundError:pass
