#!/usr/bin/env python3
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

"""Mandatory fail-closed OS sandbox for the Gate C test Python transports."""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

BWRAP = Path("/usr/bin/bwrap")
PYTHON = "/usr/bin/python3"
PREFIX = "storage-config-baseline-flow-"
TRANSPORTS = {
    "baseline": "native-generation-with-witness-portable.py",
    "controller": "native-controller-transport.py",
    "safety-probe": "native-test-sandbox-probe.py",
    "filesystem-safety": "native-filesystem-safety.py",
}
NAMESPACES = ("mnt", "pid", "net", "user", "ipc", "uts")

class SandboxRejected(Exception):
    pass

def require(value, code):
    if not value:
        raise SandboxRejected(code)

def real_directory(path, code):
    require(path.is_absolute() and str(path) == os.path.normpath(str(path)), code)
    require(path.resolve(strict=True) == path, code)
    current = Path("/")
    for part in path.parts[1:]:
        current /= part
        info = current.lstat()
        require(stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode), code)
    return path.lstat()

def protected_regular(path, code):
    require(path.is_absolute() and path.resolve(strict=True) == path, code)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and not stat.S_ISLNK(info.st_mode)
            and info.st_uid in (0, os.geteuid()) and not info.st_mode & 0o022, code)
    return info

def validate_owned_tree(root, uid):
    root=os.fspath(root)
    root_device=os.lstat(root).st_dev
    def reject(code):
        raise SandboxRejected(code)
    # Detect bind mounts even when they use the same backing filesystem device.
    for line in open('/proc/self/mountinfo',encoding='utf-8'):
        point=re.sub(r'\\([0-7]{3})',lambda m:chr(int(m.group(1),8)),line.split(' - ',1)[0].split()[4])
        if point.startswith(root+'/'):
            reject('OWNED_TREE_NESTED_MOUNT_PROHIBITED')
    for parent,dirs,files in os.walk(root,topdown=True,followlinks=False):
        for name in dirs+files:
            path=os.path.join(parent,name)
            info=os.lstat(path)
            if info.st_uid!=uid:
                reject('OWNED_TREE_FOREIGN_OWNER_PROHIBITED')
            if stat.S_ISLNK(info.st_mode):
                if info.st_nlink!=1:
                    reject('OWNED_TREE_HARDLINK_PROHIBITED')
                target=os.path.realpath(path)
                if not os.path.exists(target) or os.path.commonpath((root,target))!=root:
                    reject('OWNED_TREE_OUTSIDE_SYMLINK_PROHIBITED')
            elif stat.S_ISREG(info.st_mode):
                if info.st_nlink!=1:
                    reject('OWNED_TREE_HARDLINK_PROHIBITED')
                if info.st_dev!=root_device:
                    reject('OWNED_TREE_FOREIGN_FILESYSTEM_PROHIBITED')
            elif stat.S_ISDIR(info.st_mode):
                if info.st_dev!=root_device:
                    reject('OWNED_TREE_FOREIGN_FILESYSTEM_PROHIBITED')
            else:
                reject('OWNED_TREE_SPECIAL_FILE_PROHIBITED')

INSIDE_GUARD = r"""
import ctypes,json,os,re,stat,sys

passed=[]
class PostconditionRejected(Exception):
    def __init__(self,code):
        self.code=code

def need(value,label):
    if not value:
        raise PostconditionRejected(label)
    passed.append(label)

def validate_owned_tree(root, uid):
    root=os.fspath(root)
    root_device=os.lstat(root).st_dev
    def reject(code):
        raise PostconditionRejected(code)
    # Detect bind mounts even when they use the same backing filesystem device.
    for line in open('/proc/self/mountinfo',encoding='utf-8'):
        point=re.sub(r'\\([0-7]{3})',lambda m:chr(int(m.group(1),8)),line.split(' - ',1)[0].split()[4])
        if point.startswith(root+'/'):
            reject('OWNED_TREE_NESTED_MOUNT_PROHIBITED')
    for parent,dirs,files in os.walk(root,topdown=True,followlinks=False):
        for name in dirs+files:
            path=os.path.join(parent,name)
            info=os.lstat(path)
            if info.st_uid!=uid:
                reject('OWNED_TREE_FOREIGN_OWNER_PROHIBITED')
            if stat.S_ISLNK(info.st_mode):
                if info.st_nlink!=1:
                    reject('OWNED_TREE_HARDLINK_PROHIBITED')
                target=os.path.realpath(path)
                if not os.path.exists(target) or os.path.commonpath((root,target))!=root:
                    reject('OWNED_TREE_OUTSIDE_SYMLINK_PROHIBITED')
            elif stat.S_ISREG(info.st_mode):
                if info.st_nlink!=1:
                    reject('OWNED_TREE_HARDLINK_PROHIBITED')
                if info.st_dev!=root_device:
                    reject('OWNED_TREE_FOREIGN_FILESYSTEM_PROHIBITED')
            elif stat.S_ISDIR(info.st_mode):
                if info.st_dev!=root_device:
                    reject('OWNED_TREE_FOREIGN_FILESYSTEM_PROHIBITED')
            else:
                reject('OWNED_TREE_SPECIAL_FILE_PROHIBITED')

try:
    spec=json.loads(sys.argv[1])
    root=spec['root'];repo=spec['repository']
    need(os.path.realpath(root)==root and os.getcwd()==root,'ROOT_REALPATH_AND_CWD')
    info=os.lstat(root)
    need(stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode)==0o700
         and info.st_uid==spec['insideExpectedUid'] and info.st_gid==spec['insideExpectedGid']
         and os.geteuid()==spec['insideExpectedUid'] and os.getegid()==spec['insideExpectedGid'] and [info.st_dev,info.st_ino]==spec['rootIdentity'],
         'ROOT_PRIVATE_DIRECTORY_IDENTITY')
    need(not os.statvfs(root).f_flag & os.ST_RDONLY,'OWNED_ROOT_READWRITE')
    validate_owned_tree(root,spec['insideExpectedUid'])
    passed.append('OWNED_TREE_ISOLATED_SINGLE_LINK')
    for name,label in (('/','HOST_ROOT_READONLY'),('/etc','HOST_ETC_READONLY'),('/usr','HOST_USR_READONLY'),
                       ('/var','HOST_VAR_READONLY'),(repo,'REPOSITORY_READONLY')):
        need(bool(os.statvfs(name).f_flag & os.ST_RDONLY),label)
    for name,before in spec['parentNamespaces'].items():
        need(os.readlink('/proc/self/ns/'+name)!=before,'NAMESPACE_'+name.upper())
    mount_types={}
    for line in open('/proc/self/mountinfo',encoding='utf-8'):
        left,right=line.rstrip().split(' - ',1)
        point=re.sub(r'\\([0-7]{3})',lambda m:chr(int(m.group(1),8)),left.split()[4])
        mount_types[point]=right.split()[0]
    need(mount_types.get('/tmp')=='tmpfs' and mount_types.get('/run')=='tmpfs','MASKED_TMP_AND_RUN')
    need(mount_types.get('/dev') in ('tmpfs','devtmpfs') and mount_types.get('/proc')=='proc',
         'FRESH_DEV_AND_PROC_MOUNTS')
    need(bool(os.statvfs('/proc').f_flag & os.ST_RDONLY),'PROC_READONLY')
    need(os.stat('/dev').st_dev!=spec['hostDevDevice'],'FRESH_DEV_FILESYSTEM')
    status=dict(line.split(':',1) for line in open('/proc/self/status',encoding='ascii') if ':' in line)
    for name,label in (('CapEff','CAP_EFFECTIVE_EMPTY'),('CapPrm','CAP_PERMITTED_EMPTY'),('CapBnd','CAP_BOUNDING_EMPTY')):
        need(int(status[name].strip(),16)==0,label)
    need(ctypes.CDLL(None,use_errno=True).prctl(38,1,0,0,0)==0,'NO_NEW_PRIVILEGES')
    need(set(os.environ)=={'PATH','HOME','TMPDIR','PWD','LANG','LC_ALL','PYTHONDONTWRITEBYTECODE'},
         'EXACT_CHILD_ENVIRONMENT')
    need(all(os.environ[name]==root for name in ('HOME','TMPDIR','PWD')),'CHILD_SCOPE_ENVIRONMENT')
    command=list(spec['transportCommand'])
    if spec['transportKind'] in ('safety-probe','filesystem-safety'):
        command+=['--checked-postconditions',json.dumps(passed,separators=(',',':'))]
    os.execv('/usr/bin/python3',['/usr/bin/python3','-I','-B',*command])
except BaseException as failure:
    print(json.dumps({'success':False,'errorCode':'NATIVE_TEST_SANDBOX_POSTCONDITION_REJECTED',
                      'failedPostcondition':failure.code if isinstance(failure,PostconditionRejected) else 'GUARD_INTERNAL_ERROR',
                      'passedPostconditions':passed,'exceptionClass':type(failure).__name__}),flush=True)
    sys.exit(125)
"""

def command(repository, root, transport):
    require(transport in TRANSPORTS, "FIXED_TRANSPORT_REQUIRED")
    real_directory(repository, "REAL_REPOSITORY_REQUIRED")
    require((repository / "pom.xml").is_file()
            and (repository / "systemvm/debian/usr/local/lib/ablestack-storage/config_generation.py").is_file(),
            "REPOSITORY_ANCHORS_REQUIRED")
    require(root.parent == Path("/tmp") and re.fullmatch(PREFIX + r"[A-Za-z0-9_-]+", root.name),
            "EXACT_OWNED_TEST_ROOT_REQUIRED")
    info = real_directory(root, "REAL_OWNED_TEST_ROOT_REQUIRED")
    require(info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 0o700,
            "PRIVATE_OWNED_TEST_ROOT_REQUIRED")
    native = root / "native"
    child = real_directory(native, "REAL_NATIVE_CASE_REQUIRED")
    require(child.st_uid == os.geteuid() and stat.S_IMODE(child.st_mode) == 0o700,
            "PRIVATE_NATIVE_CASE_REQUIRED")
    validate_owned_tree(root,os.geteuid())
    protected_regular(BWRAP, "MANDATORY_BUBBLEWRAP_UNAVAILABLE")
    require(os.access(BWRAP, os.X_OK), "MANDATORY_BUBBLEWRAP_UNAVAILABLE")
    trusted_python = Path(PYTHON).resolve(strict=True)
    require(trusted_python.parent == Path("/usr/bin") and re.fullmatch(r"python3(?:\.\d+)*", trusted_python.name), "TRUSTED_PYTHON_REQUIRED")
    protected_regular(trusted_python, "TRUSTED_PYTHON_REQUIRED")
    script = repository / "server/src/test/resources/storage-config" / TRANSPORTS[transport]
    protected_regular(script, "READONLY_FIXED_TRANSPORT_REQUIRED")
    if transport in ("safety-probe", "filesystem-safety"):
        transport_command = [str(script), "--owned-test-root", str(root), "--repository-root", str(repository)]
    else:
        transport_command = [str(script), "--repository-root", str(repository), "--case-dir", str(native)]
    # Bind only this pinned owned directory through bubblewrap's supported FD option.
    # The child never resolves a parent /proc/PID/fd path or a replaceable host path.
    # The in-sandbox original dev/inode/owner/mode gate remains mandatory before exec.
    root_fd = os.open(root, os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        pinned = os.fstat(root_fd)
        require((pinned.st_dev, pinned.st_ino) == (info.st_dev, info.st_ino), "OWNED_TEST_ROOT_CHANGED")
        spec = {"root":str(root),"repository":str(repository),"hostUid":os.geteuid(),"hostGid":os.getegid(),
                "insideExpectedUid":0,"insideExpectedGid":0,
                "rootIdentity":[info.st_dev,info.st_ino],"hostDevDevice":os.stat('/dev').st_dev,
                "parentNamespaces":{name:os.readlink('/proc/self/ns/'+name) for name in NAMESPACES},
                "transportCommand":transport_command,"transportKind":transport}
        args = [str(BWRAP), "--die-with-parent", "--new-session", "--unshare-all", "--unshare-user", "--uid", "0", "--gid", "0",
                "--cap-drop", "ALL", "--clearenv", "--ro-bind", "/", "/",
                "--perms", "1777", "--tmpfs", "/tmp",
                "--bind-fd", str(root_fd), str(root),
                "--tmpfs", "/run", "--dev", "/dev", "--proc", "/proc", "--remount-ro", "/proc",
                "--chdir", str(root), "--setenv", "PATH", "/usr/bin:/bin",
                "--setenv", "HOME", str(root), "--setenv", "TMPDIR", str(root),
                "--setenv", "LANG", "C.UTF-8", "--setenv", "LC_ALL", "C.UTF-8",
                "--setenv", "PYTHONDONTWRITEBYTECODE", "1", PYTHON, "-I", "-B", "-c", INSIDE_GUARD,
                json.dumps(spec,separators=(',',':'))]
        return args, root_fd
    except BaseException:
        os.close(root_fd)
        raise

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--repository-root',type=Path,required=True)
    parser.add_argument('--owned-test-root',type=Path,required=True)
    parser.add_argument('--transport',choices=sorted(TRANSPORTS),required=True)
    args=parser.parse_args()
    fd=None
    try:
        argv,fd=command(args.repository_root,args.owned_test_root,args.transport)
        # stdin is inherited unchanged: credentials and capsule keys never become argv or environment.
        return subprocess.run(argv,pass_fds=(fd,),close_fds=True,env={"PATH":"/usr/bin:/bin"},
                              timeout=180,check=False).returncode
    except BaseException as failure:
        code=str(failure) if isinstance(failure,SandboxRejected) else 'SANDBOX_LAUNCH_FAILED'
        print(json.dumps({'success':False,'errorCode':code,'exceptionClass':type(failure).__name__}),flush=True)
        return 125
    finally:
        if fd is not None:
            os.close(fd)

if __name__=='__main__':
    sys.exit(main())
