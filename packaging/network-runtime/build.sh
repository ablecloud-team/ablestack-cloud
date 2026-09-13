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
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
topdir=${1:-$root/dist/network-runtime-rpmbuild}
mkdir -p "$topdir"
topdir=$(cd "$topdir" && pwd)
case "$root:$topdir" in
    /mnt/*|*:/mnt/*) echo 'Build from a Linux filesystem, not /mnt.' >&2; exit 1 ;;
esac
mkdir -p "$topdir"/{BUILD,BUILDROOT,RPMS,SOURCES,SPECS,SRPMS}
install -m 0644 "$root/extensions/network-namespace/network-namespace-wrapper.sh" "$topdir/SOURCES/"
install -m 0644 "$root/packaging/network-runtime/cloudstack-network-runtime-check" "$topdir/SOURCES/"
rpmbuild -bb --define "_topdir $topdir" "$root/packaging/network-runtime/cloudstack-network-runtime.spec"
