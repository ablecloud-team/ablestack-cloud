#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
"""Fail-closed bwrap launcher for the Native preparation health wire tests.

Run from any directory:
  python3 systemvm/test/run-preparation-health-wire-isolated.py
  python3 systemvm/test/run-preparation-health-wire-isolated.py --suite iscsi-auth

Requires Linux, /usr/bin/bwrap with --bind-fd support, and permission to read
/etc/{passwd,group,shadow,gshadow} for host integrity verification. The launcher
executes Native only after independent namespace/mount/capability postconditions
pass. Native never executes on the host. Output defaults to a new private /tmp/
storage-preparation-health-wire-* directory. --output-dir may select a new path
with that exact parent/prefix; an existing directory is always rejected.

The fixture is exported only to the output directory. After reviewing it,
explicitly copy it to server/src/test/resources/storage-config/ if an update is
intended. The repository is always mounted read-only inside the sandbox.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile

REPOSITORY = Path(__file__).resolve().parents[2]
BWRAP = Path("/usr/bin/bwrap")
PYTHON = Path("/usr/bin/python3")
PREFIX = "storage-preparation-health-wire-"
ACCOUNT_FILES = ("/etc/passwd", "/etc/group", "/etc/shadow", "/etc/gshadow")
NAMESPACES = ("mnt", "pid", "net", "user", "ipc", "uts")
SUITES = {
    "health": ("TestStoragePreparationHealthWire.py",),
    "native-all": ("TestStorage*.py",),
    "harness-readiness": ("TestStorageIdentitySourceFence.py", "TestStorageAdIdentity.py",
                          "TestStorageSmbCurrentRetention.py", "TestStorageLocalSamBootstrap.py"),
    "iscsi-auth": ("TestStorageIscsiAuth.py", "TestStorageIscsiLifecycle.py",
                   "TestStorageIscsiLunPermissions.py", "TestStorageInlineSources.py"),
    "iscsi-guard-consumer": ("TestStorageIscsiLifecycle.py",),
    "iscsi-manager-payload": ("TestStorageIscsiLunPermissions.py",),
    "iscsi-manager-listener": ("TestStorageIscsiLunPermissions.py",),
}


class Rejected(Exception):
    pass


def require(value, code):
    if not value:
        raise Rejected(code)


def real_directory(path, code):
    require(path.is_absolute() and str(path) == os.path.normpath(str(path)), code)
    require(path.resolve(strict=True) == path, code)
    current = Path("/")
    for part in path.parts[1:]:
        current /= part
        info = current.lstat()
        require(stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode), code)
    return path.stat()


def protected_executable(path, code):
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid in (0, os.geteuid())
            and not info.st_mode & 0o022 and os.access(path, os.X_OK), code)


def account_fingerprints():
    values = {}
    for name in ACCOUNT_FILES:
        path = Path(name)
        info = path.lstat()
        require(stat.S_ISREG(info.st_mode) and not stat.S_ISLNK(info.st_mode),
                "HOST_ACCOUNT_FILE_NOT_REGULAR")
        values[name] = (info.st_dev, info.st_ino, info.st_mode, info.st_uid,
                        info.st_gid, info.st_size, info.st_mtime_ns, info.st_ctime_ns,
                        hashlib.sha256(path.read_bytes()).hexdigest())
    return values


INSIDE_GUARD = r"""
import ctypes,json,os,stat,subprocess,sys
from pathlib import Path
spec=json.loads(sys.argv[1]);passed=[]
def need(value,code):
    if not value:raise AssertionError(code)
    passed.append(code)
root=Path(spec["output"]);info=root.lstat()
need(root.resolve()==root and stat.S_ISDIR(info.st_mode)
     and stat.S_IMODE(info.st_mode)==0o700 and info.st_uid==os.geteuid()==0
     and info.st_gid==os.getegid()==0
     and [info.st_dev,info.st_ino]==spec["rootIdentity"],"PRIVATE_PINNED_OUTPUT_IDENTITY")
need(not any(root.iterdir()),"EMPTY_OUTPUT_DIRECTORY")
for name in ("/",spec["repository"],"/usr","/proc","/sys"):
    need(bool(os.statvfs(name).f_flag & os.ST_RDONLY),"READONLY_"+name)
mounts={}
for line in Path("/proc/self/mountinfo").read_text().splitlines():
    left,right=line.split(" - ",1)
    mounts[left.split()[4]]=right.split()[0]
for name in ("/etc","/var","/run","/tmp"):
    need(mounts.get(name)=="tmpfs","PRIVATE_"+name)
need(mounts.get("/proc")=="proc","FRESH_PROC")
need(os.stat("/dev").st_dev!=spec["hostDevDevice"],"FRESH_DEV")
for name,before in spec["parentNamespaces"].items():
    need(os.readlink("/proc/self/ns/"+name)!=before,"NAMESPACE_"+name)
status=dict(line.split(":",1)for line in Path("/proc/self/status").read_text().splitlines()if":"in line)
for key in ("CapEff","CapPrm","CapBnd"):
    need(int(status[key].strip(),16)==0,key+"_EMPTY")
need(ctypes.CDLL(None).prctl(38,1,0,0,0)==0,"NO_NEW_PRIVILEGES")
need(not os.statvfs(root).f_flag & os.ST_RDONLY,"OWNED_OUTPUT_WRITABLE")
need(set(os.environ)=={"PWD","PATH","HOME","LANG","LC_ALL","PYTHONDONTWRITEBYTECODE",
                       "PREPARATION_HEALTH_WIRE_FIXTURE_OUTPUT"},"EXACT_CHILD_ENVIRONMENT")
need(os.environ["PWD"]==str(root)and os.getcwd()==str(root),"OUTPUT_CWD_BINDING")
need(os.environ["PREPARATION_HEALTH_WIRE_FIXTURE_OUTPUT"]==
     str(root/"preparation-health-wire-contract.json"),"FIXED_FIXTURE_OUTPUT")
# Private synthetic account baseline only. Never copy/read host account contents.
for name,content,mode in (
        ("passwd","root:x:0:0:Synthetic sandbox root:/root:/bin/bash\n",0o644),
        ("group","root:x:0:\n",0o644),
        ("shadow","root:!:1::::::\n",0o600),
        ("gshadow","root:!::\n",0o600)):
    path=Path("/etc")/name
    need(not path.exists()and not path.is_symlink(),"EMPTY_PRIVATE_ACCOUNT_"+name)
    with path.open("x") as handle:handle.write(content)
    path.chmod(mode)
passed.append("SYNTHETIC_PRIVATE_ACCOUNT_BASELINE")
(root/"isolation-proof.json").write_text(json.dumps(
    {"success":True,"passedPostconditions":passed,"noHostNativeExecution":True,
     "parentNamespaces":spec["parentNamespaces"],
     "sandboxNamespaces":{name:os.readlink("/proc/self/ns/"+name)for name in spec["parentNamespaces"]},
     "outputIdentity":spec["rootIdentity"]},indent=2)+"\n")
tests=Path(spec["repository"],"systemvm/test")
if spec["suite"] in ("iscsi-auth","native-all","harness-readiness"):
    program="import unittest,sys; sys.path.insert(0,"+repr(spec["repository"])+"); loader=unittest.TestLoader(); suite=unittest.TestSuite(); "+\
            "; ".join("suite.addTests(loader.discover("+repr(str(tests))+",pattern="+repr(name)+"))"
                      for name in spec["tests"])+\
            "; result=unittest.TextTestRunner(verbosity=1).run(suite); sys.exit(0 if result.wasSuccessful() else 1)"
    command=["/usr/bin/python3","-B","-c",program]
elif spec["suite"] in ("iscsi-guard-consumer","iscsi-manager-payload","iscsi-manager-listener"):
    flag={"iscsi-guard-consumer":"--guard-consumer-fixtures",
          "iscsi-manager-payload":"--manager-payload","iscsi-manager-listener":"--manager-listener"}[spec["suite"]]
    command=["/usr/bin/python3","-B",str(tests/spec["tests"][0]),flag]
else:
    command=["/usr/bin/python3","-B",str(tests/spec["tests"][0])]
result=subprocess.run(command,check=False)
for entry in root.iterdir():
    value=entry.lstat()
    need(stat.S_ISREG(value.st_mode)and not stat.S_ISLNK(value.st_mode)
         and value.st_nlink==1 and value.st_uid==os.geteuid(),"REGULAR_SINGLE_LINK_OUTPUT")
raise SystemExit(result.returncode)
"""


def launch(output,suite="health"):
    real_directory(REPOSITORY, "REAL_REPOSITORY_REQUIRED")
    require((REPOSITORY / "pom.xml").is_file(), "REPOSITORY_ANCHORS_REQUIRED")
    require(suite in SUITES,"FIXED_SUITE_REQUIRED")
    for name in SUITES[suite]:
        test = REPOSITORY / "systemvm/test" / ("TestStorageIscsiAuth.py" if suite=="native-all" else name)
        require(test.is_file() and not test.is_symlink(), "FIXED_TEST_REQUIRED")
    protected_executable(BWRAP, "TRUSTED_BWRAP_REQUIRED")
    protected_executable(PYTHON.resolve(strict=True), "TRUSTED_PYTHON_REQUIRED")
    require(output.parent == Path("/tmp") and re.fullmatch(PREFIX + r"[A-Za-z0-9_-]+", output.name),
            "EXACT_PRIVATE_OUTPUT_PATH_REQUIRED")
    info = real_directory(output, "REAL_PRIVATE_OUTPUT_REQUIRED")
    require(info.st_uid == os.geteuid() and info.st_gid == os.getegid()
            and stat.S_IMODE(info.st_mode) == 0o700 and not any(output.iterdir()),
            "PRIVATE_EMPTY_OWNED_OUTPUT_REQUIRED")
    before = account_fingerprints()
    descriptor = os.open(output, os.O_PATH | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        pinned = os.fstat(descriptor)
        require((pinned.st_dev, pinned.st_ino) == (info.st_dev, info.st_ino),
                "OUTPUT_DIRECTORY_CHANGED")
        spec = {"repository": str(REPOSITORY), "output": str(output), "suite":suite,"tests":SUITES[suite],
                "rootIdentity": [info.st_dev, info.st_ino], "hostDevDevice": os.stat("/dev").st_dev,
                "parentNamespaces": {name: os.readlink("/proc/self/ns/" + name) for name in NAMESPACES}}
        argv = [str(BWRAP), "--die-with-parent", "--new-session", "--unshare-all", "--unshare-user",
                "--uid", "0", "--gid", "0", "--cap-drop", "ALL", "--clearenv", "--ro-bind", "/", "/",
                "--tmpfs", "/etc", "--tmpfs", "/var", "--tmpfs", "/run", "--tmpfs", "/tmp",
                "--tmpfs", "/sys", "--remount-ro", "/sys", "--dev", "/dev",
                "--proc", "/proc", "--remount-ro", "/proc", "--bind-fd", str(descriptor), str(output),
                "--hostname", "health-wire-contract", "--chdir", str(output),
                "--setenv", "PWD", str(output), "--setenv", "PATH", "/usr/bin:/bin", "--setenv", "HOME", "/tmp",
                "--setenv", "LANG", "C.UTF-8", "--setenv", "LC_ALL", "C.UTF-8",
                "--setenv", "PYTHONDONTWRITEBYTECODE", "1",
                "--setenv", "PREPARATION_HEALTH_WIRE_FIXTURE_OUTPUT",
                str(output / "preparation-health-wire-contract.json"),
                str(PYTHON), "-I", "-B", "-c", INSIDE_GUARD, json.dumps(spec, separators=(",", ":"))]
        try:
            result = subprocess.run(argv, pass_fds=(descriptor,), close_fds=True,
                                    capture_output=True, text=True, timeout=600 if suite=="native-all" else 180,
                                    env={"PATH": "/usr/bin:/bin"})
            if result.stdout:
                print(result.stdout, end="")
            if result.stderr:
                print(result.stderr, end="", file=sys.stderr)
            code = result.returncode
        finally:
            unchanged = before == account_fingerprints()
            (output / "host-account-integrity.json").write_text(json.dumps(
                {"unchanged": unchanged, "paths": list(ACCOUNT_FILES)}, indent=2) + "\n")
            require(unchanged, "HOST_ACCOUNT_FILES_CHANGED")
        if suite not in ("iscsi-guard-consumer","iscsi-manager-payload","iscsi-manager-listener"):
            print(json.dumps({"exitCode": code, "hostAccountFilesUnchanged": True,
                              "output": str(output), "repositoryModified": False}))
        return code
    finally:
        os.close(descriptor)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path,
                        help="New private /tmp/storage-preparation-health-wire-* path; must not exist")
    parser.add_argument("--suite",choices=sorted(SUITES),default="health")
    args = parser.parse_args()
    try:
        if args.output_dir is None:
            output = Path(tempfile.mkdtemp(prefix=PREFIX, dir="/tmp"))
        else:
            output = args.output_dir
            require(output.is_absolute() and output.parent == Path("/tmp")
                    and re.fullmatch(PREFIX + r"[A-Za-z0-9_-]+", output.name),
                    "EXACT_PRIVATE_OUTPUT_PATH_REQUIRED")
            output.mkdir(mode=0o700, exist_ok=False)
        return launch(output,args.suite)
    except BaseException as failure:
        code = str(failure) if isinstance(failure, Rejected) else "HEALTH_WIRE_SANDBOX_LAUNCH_FAILED"
        print(json.dumps({"success": False, "errorCode": code,
                          "exceptionClass": type(failure).__name__}), file=sys.stderr)
        return 125


if __name__ == "__main__":
    raise SystemExit(main())

