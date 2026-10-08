#!/bin/bash
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

set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
lock=/tmp/storage-ganesha-vfs-amd64.json
[[ "$(dpkg --print-architecture)" == amd64 ]]
[[ ! -e /var/lib/ablestack-storage/config-generations/current.json ]]
work=$(mktemp -d /var/tmp/ablestack-ganesha-build.XXXXXX)
apt-mark showmanual > "$work/manual-before.txt"
apt-get update
apt-get install -y --no-install-recommends build-essential cmake bison flex pkg-config libdbus-1-dev libkrb5-dev libnfsidmap-dev libblkid-dev libacl1-dev libcap-dev liburcu-dev libjemalloc-dev libssl-dev libtirpc-dev uuid-dev python3-distutils python3-setuptools
prefix=/opt/ablestack-ganesha/5.5.3
python3 - "$lock" "$work" <<'PYFETCH'
import hashlib,json,tarfile,urllib.request,sys
from pathlib import Path
lock=json.loads(Path(sys.argv[1]).read_text());root=Path(sys.argv[2])
if lock["schemaVersion"]!=1 or lock["version"]!="5.5.3" or lock["prefix"]!="/opt/ablestack-ganesha/5.5.3":raise ValueError("Unsupported Ganesha lock")
for name,destination in (("source",root/"source"),("ntirpc",root/"source/src/libntirpc")):
 item=lock[name]
 if not item["url"].startswith("https://codeload.github.com/nfs-ganesha/"):raise ValueError("Ganesha source authority differs")
 content=urllib.request.urlopen(item["url"],timeout=120).read()
 if hashlib.sha256(content).hexdigest()!=item["sha256"]:raise ValueError("Ganesha source archive checksum differs")
 archive=root/(name+".tar.gz");archive.write_bytes(content);destination.mkdir(parents=True,exist_ok=True)
 with tarfile.open(archive) as handle:
  members=[]
  for entry in handle.getmembers():
   parts=Path(entry.name).parts
   if not parts or Path(entry.name).is_absolute() or ".." in parts or entry.isdev() or entry.islnk():raise ValueError("Unsafe Ganesha archive entry")
   if len(parts)==1:continue
   entry.name=str(Path(*parts[1:]))
   if not (destination/entry.name).resolve().is_relative_to(destination.resolve()):raise ValueError("Ganesha archive escapes its fixed tree")
   if entry.issym() and (Path(entry.linkname).is_absolute() or not (destination/entry.name).parent.joinpath(entry.linkname).resolve().is_relative_to(destination.resolve())):raise ValueError("Ganesha source link escapes its fixed tree")
   members.append(entry)
  handle.extractall(destination,members=members)
PYFETCH
cmake -S "$work/source/src" -B "$work/build" \
 -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_INSTALL_PREFIX="$prefix" -DSYSCONFDIR=/etc -DSYSSTATEDIR=/var -DRUNTIMEDIR=/run/ganesha \
 -DLIB_INSTALL_DIR="$prefix/lib/x86_64-linux-gnu" -DFSAL_DESTINATION="$prefix/lib/x86_64-linux-gnu/ganesha" \
 -DCMAKE_INSTALL_RPATH="$prefix/lib/x86_64-linux-gnu" \
 -DUSE_FSAL_VFS=ON -DENABLE_VFS_POSIX_ACL=ON -DENABLE_VFS_DEBUG_ACL=OFF \
 -DUSE_FSAL_PROXY_V3=OFF -DUSE_FSAL_PROXY_V4=OFF -DUSE_FSAL_CEPH=OFF -DUSE_FSAL_GLUSTER=OFF \
 -DUSE_FSAL_RGW=OFF -DUSE_FSAL_GPFS=OFF -DUSE_FSAL_LUSTRE=OFF -DUSE_FSAL_XFS=OFF \
 -DUSE_FSAL_ZFS=OFF -DUSE_FSAL_LIZARDFS=OFF -DUSE_FSAL_KVSFS=OFF \
 -DUSE_DBUS=ON -DUSE_GSS=ON -DUSE_NFSIDMAP=ON -DUSE_JEMALLOC=ON -DBUILD_CONFIG=debian
cmake --build "$work/build" -j2
DESTDIR="$work/installed" cmake --install "$work/build"
python3 /tmp/package_storage_ganesha.py --installed-root "$work/installed" --package-root "$work/package" --lock "$lock" > "$work/package-files.json"
dpkg-deb --build --root-owner-group "$work/package" "$work/runtime.deb"
dpkg -i "$work/runtime.deb"
if [[ -d /run/systemd/system ]]; then
  systemctl disable --now nfs-ganesha.service
  systemctl mask nfs-ganesha.service
  systemctl daemon-reload
fi
ldd /usr/local/bin/ganesha.nfsd > "$work/ldd.txt"
! grep -q "not found" "$work/ldd.txt"
bash /tmp/test_storage_ganesha_acl.sh > "$work/selftest.log"
python3 - "$lock" "$work" <<'PYATTEST'
import hashlib,json,os,sys
from pathlib import Path
lock=json.loads(Path(sys.argv[1]).read_text());work=Path(sys.argv[2])
result=next(json.loads(line) for line in (work/"selftest.log").read_text().splitlines() if line.startswith("{"))
if result!={"namedAclReadWrite":True,"defaultAclInheritance":True}:raise ValueError("Real Ganesha ACL self-test did not pass")
package=json.loads((work/"package-files.json").read_text());files=package["files"]
manifest={"schemaVersion":1,"version":lock["version"],"sourceCommit":lock["source"]["commit"],"sourceArchiveSha256":lock["source"]["sha256"],
 "ntirpcCommit":lock["ntirpc"]["commit"],"ntirpcArchiveSha256":lock["ntirpc"]["sha256"],"prefix":lock["prefix"],
  "requiredCmakeFlags":lock["requiredCmakeFlags"],"files":files,"serviceBindings":package["serviceBindings"],"selfTest":result,"packageSha256":hashlib.sha256((work/"runtime.deb").read_bytes()).hexdigest()}
output=Path("/etc/ablestack-storage/ganesha-build-manifest.json");output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps(manifest,sort_keys=True,indent=2)+"\n");output.chmod(0o644)
PYATTEST

python3 - "$work" <<'PYCLEAN'
import json,re,shutil,subprocess,sys
from pathlib import Path
work=Path(sys.argv[1]).resolve()
if work.parent!=Path("/var/tmp") or not work.name.startswith("ablestack-ganesha-build."):raise ValueError("Foreign Ganesha build workspace")
old=set((work/"manual-before.txt").read_text().splitlines())
new=set(subprocess.check_output(["apt-mark","showmanual"],text=True).splitlines())-old-{"ablestack-nfs-ganesha-vfs-acl"}
if any(not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*(?::[a-z0-9]+)?",name) for name in new):raise ValueError("Invalid build dependency package")
if new:subprocess.run(["apt-mark","auto",*sorted(new)],check=True)
subprocess.run(["apt-get","-y","autoremove","--purge"],check=True)
subprocess.run(["/usr/local/bin/ganesha.nfsd","-v"],check=True,timeout=10)
if "not found" in subprocess.check_output(["ldd","/usr/local/bin/ganesha.nfsd"],text=True,timeout=10):raise ValueError("Ganesha runtime dependency disappeared")
artifact=Path("/var/lib/ablestack-storage/template-build");artifact.mkdir(parents=True,exist_ok=True,mode=0o700)
shutil.copyfile(work/"runtime.deb",artifact/"ablestack-nfs-ganesha-vfs-acl_5.5.3-1+ablestack1_amd64.deb")
shutil.rmtree(work)
PYCLEAN
