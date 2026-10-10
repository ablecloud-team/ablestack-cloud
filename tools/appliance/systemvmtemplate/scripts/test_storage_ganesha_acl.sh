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
if (( $# == 0 )); then set -- --outer; fi
if [[ "$1" != "--namespace" ]]; then
  proof=$(mktemp -d /var/tmp/ablestack-ganesha-acl.XXXXXX)
  chmod 0700 "$proof"
  timeout --kill-after=2 60 unshare --mount --net --pid --fork --kill-child=SIGKILL --mount-proc bash "$0" --namespace "$proof"
  cat "$proof/result.json"
  exit 0
fi
proof="$2"
mount --make-rprivate /
ip link set lo up
ip link add test0 type dummy
ip addr add 192.0.2.1/24 dev test0
ip link set test0 up
mkdir -p "$proof"/data/child "$proof"/mnt /var/run/ganesha /var/lib/nfs/ganesha
chown 1002:1002 "$proof"/data
chmod 0770 "$proof"/data
chown 0:0 "$proof"/data/child
chmod 0770 "$proof"/data/child
setfacl -m u:1002:rwx "$proof"/data/child
setfacl -d -m u:1002:rwx "$proof"/data/child
printf '%s\n' 'named POSIX ACL preserves root:root 0770 child' > "$proof"/data/child/proof.txt
chown 1002:1002 "$proof"/data/child/proof.txt
chmod 0660 "$proof"/data/child/proof.txt
getfacl -cpn "$proof"/data/child > "$proof"/source-acl.txt
readelf --dyn-syms -W /opt/ablestack-ganesha/5.5.3/lib/x86_64-linux-gnu/ganesha/libfsalvfs.so > "$proof"/candidate-symbols.txt
ganesha.nfsd -v > "$proof"/version.txt 2>&1
cat > "$proof"/ganesha.conf <<EOF
NFS_Core_Param { NFS_Port = 20492; Bind_Addr = 127.0.0.1; Protocols = 4; Enable_NLM = false; Enable_RQUOTA = false; }
NFSv4 { Only_Numeric_Owners = true; Graceless = true; }
EXPORT {
 Export_Id = 898;
 Path = $proof/data;
 Pseudo = /acl;
 Access_Type = RW;
 Squash = All_Squash;
 Anonymous_uid = 1002;
 Anonymous_gid = 1002;
 Protocols = 4;
 Transports = TCP;
 SecType = sys;
 FSAL { Name = VFS; }
 CLIENT { Clients = 127.0.0.1; Access_Type = RW; Squash = All_Squash; }
}
EOF
ganesha.nfsd -F -f "$proof"/ganesha.conf -L "$proof"/ganesha.log &
daemon=$!
trap 'timeout --kill-after=1 5 umount "$proof"/mnt 2>/dev/null || true; kill "$daemon" 2>/dev/null || true' EXIT
for iteration in $(seq 1 50); do
 if ss -lnt | grep -q '127.0.0.1:20492'; then break; fi
 sleep .1
done
timeout --kill-after=2 20 mount -t nfs -o vers=4.1,port=20492,proto=tcp,noac 127.0.0.1:/acl "$proof"/mnt
cat "$proof"/mnt/child/proof.txt > "$proof"/client-read.txt
cmp "$proof"/client-read.txt "$proof"/data/child/proof.txt
printf '%s\n' 'cross NFS named ACL write' > "$proof"/mnt/child/nfs-created.txt
stat -c '%u:%g:%a' "$proof"/data/child "$proof"/data/child/nfs-created.txt > "$proof"/post-stat.txt
cat "$proof"/data/child/nfs-created.txt
printf '%s\n' 'ACL_SELFTEST_PASS'

getfacl -cpn "$proof"/data/child/nfs-created.txt > "$proof"/created-acl.txt
grep -q '^user:1002:rwx' "$proof"/created-acl.txt
printf '{"namedAclReadWrite":true,"defaultAclInheritance":true}\n' > "$proof"/result.json
