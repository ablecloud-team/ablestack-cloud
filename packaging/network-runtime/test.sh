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
package=${1:?Pass the built runtime RPM}
requires=$(rpm -qp --requires "$package")
for dependency in iproute iptables iputils dnsmasq haproxy httpd radvd python3 util-linux openssh-server; do
    grep -qx "$dependency" <<< "$requires"
done
rpm -qpl "$package" | grep -qx /etc/cloudstack/extensions/network-namespace/network-namespace-wrapper.sh
rpm -qpl "$package" | grep -qx /usr/sbin/cloudstack-network-runtime-check
test -z "$(rpm -qp --scripts "$package")"
for distro in centos7 centos8; do
    parsed=$(rpmspec -P --define '_ver 4.23.0.0' --define '_fullver 4.23.0.0' --define '_rel 1' "$root/packaging/$distro/cloud.spec")
    agent=$(sed -n '/^%package agent$/,/^%description agent$/p' <<< "$parsed")
    grep -qx 'Requires: cloudstack-network-runtime >= 1.0.0' <<< "$agent"
    management=$(sed -n '/^%files management$/,/^%files agent$/p' <<< "$parsed")
    grep -qx '%exclude /etc/cloudstack/extensions/network-namespace/network-namespace-wrapper.sh' <<< "$management"
    echo "PASS $distro Agent dependency and management file ownership"
done
bash -n "$root/packaging/package.sh"
echo 'PASS runtime files, dependencies and absence of service-start scriptlets'
