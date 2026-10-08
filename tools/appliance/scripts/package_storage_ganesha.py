#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Package a private Ganesha runtime; never overwrite the distribution package."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import os

def package_runtime(installed, output, lock):
    prefix=Path(lock["prefix"])
    if lock["schemaVersion"]!=1 or str(prefix)!="/opt/ablestack-ganesha/"+lock["version"] or lock["architecture"]!="amd64":
        raise ValueError("Ganesha package lock has an unsupported layout")
    output.mkdir(parents=True,exist_ok=True)
    private=output/str(prefix).lstrip("/")
    (private/"bin").mkdir(parents=True)
    (private/"lib/x86_64-linux-gnu/ganesha").mkdir(parents=True)
    source=installed/str(prefix).lstrip("/")
    names=["bin/ganesha.nfsd","lib/x86_64-linux-gnu/libganesha_nfsd.so","lib/x86_64-linux-gnu/libganesha_nfsd.so."+lock["version"],
           "lib/x86_64-linux-gnu/libntirpc.so","lib/x86_64-linux-gnu/libntirpc.so.5.0","lib/x86_64-linux-gnu/ganesha/libfsalvfs.so"]
    for name in names:
        item=source/name
        if item.is_symlink():
            target=os.readlink(item)
            if Path(target).is_absolute() or not (item.parent/target).resolve().is_relative_to(source.resolve()):
                raise ValueError("Ganesha package symlink escapes its private runtime")
            (private/name).symlink_to(target)
        elif item.is_file():shutil.copy2(item,private/name)
        else:raise ValueError("Ganesha package runtime file is missing")
    executable=output/"usr/local/bin/ganesha.nfsd";executable.parent.mkdir(parents=True)
    executable.symlink_to(prefix/"bin/ganesha.nfsd")
    bindings={
      "/etc/systemd/system/ablestack-storage-ganesha@.service.d/ablestack-storage-vfs-acl.conf":
        "[Service]\nExecStart=\nExecStart="+str(prefix)+"/bin/ganesha.nfsd -F -p /run/ablestack-storage/ganesha/%i.pid -L /run/ablestack-storage/ganesha/%i.log -f /etc/ganesha/ablestack-storage/%i.conf\nExecReload=\nExecReload=/bin/kill -HUP $MAINPID\n",
      "/etc/systemd/system/nfs-ganesha.service.d/ablestack-storage-vfs-acl.conf":
        "[Service]\nExecStart=\nExecStart="+str(prefix)+"/bin/ganesha.nfsd -C\nExecReload=\nExecReload=/bin/kill -HUP $MAINPID\n"}
    for path,content in bindings.items():
        destination=output/path.lstrip("/");destination.parent.mkdir(parents=True,exist_ok=True);destination.write_text(content)
    control=output/"DEBIAN";control.mkdir()
    (control/"control").write_text("Package: "+lock["packageName"]+"\nVersion: "+lock["version"]+"-1+ablestack1\nArchitecture: amd64\n"
        "Maintainer: ABLESTACK <support@ablecloud.io>\n"
        "Depends: libacl1, libblkid1, libc6, libcap2, libdbus-1-3, libgssapi-krb5-2, libkrb5-3, libnfsidmap1, liburcu8, libuuid1, libjemalloc2, libssl3\n"
        "Description: Pinned Ganesha VFS POSIX ACL runtime for ABLESTACK SystemVM\n")
    (control/"postinst").write_text("#!/bin/sh\nset -e\nif [ -d /run/systemd/system ]; then systemctl daemon-reload; fi\n")
    for item in output.rglob("*"):
        if not item.is_symlink():item.chmod(0o755 if item.is_dir() or item.name in ("ganesha.nfsd","postinst") else 0o644)
    return {"files":{name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names if not (source/name).is_symlink()},
            "serviceBindings":{path:hashlib.sha256(content.encode()).hexdigest() for path,content in bindings.items()}}

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--installed-root",type=Path,required=True);parser.add_argument("--package-root",type=Path,required=True);parser.add_argument("--lock",type=Path,required=True)
    args=parser.parse_args()
    print(json.dumps(package_runtime(args.installed_root,args.package_root,json.loads(args.lock.read_text())),sort_keys=True))
