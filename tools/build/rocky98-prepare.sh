#!/usr/bin/env bash

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

# Run installation only in a disposable Rocky 9.8 amd64 build container.
# --check validates an already prepared environment without modifying it.
set -euo pipefail
source /etc/os-release
if [[ "$ID" != rocky || "$VERSION_ID" != 9.8 || "$(uname -m)" != x86_64 ]]; then
    echo "Rocky Linux 9.8 x86_64 is required" >&2
    exit 1
fi
check_tools() {
    [[ "$(javac -version 2>&1)" == javac\ 17.* ]]
    mvn -v | head -n 1 | grep -F 'Apache Maven 3.9.10'
    [[ "$(node -v)" == v14.21.3 ]]
    [[ "$(npm -v)" == 6.14.18 ]]
    [[ "$(python3 --version)" == Python\ 3.10.* ]]
    echo "PASS: Rocky 9.8/x86_64 JDK17 Maven3.9.10 Node14.21.3 npm6.14.18 Python3.10"
}
if [[ "${1:-}" == --check ]]; then
    check_tools
    exit 0
fi
if [[ $# != 0 || $EUID != 0 ]]; then
    echo "Usage: $0 [--check]; installation requires root in a disposable container" >&2
    exit 1
fi
# Never rewrite the image's repositories to the Rocky 9.7 vault.
dnf --releasever=9.8 -y install dnf-plugins-core
dnf --releasever=9.8 config-manager --set-enabled crb
dnf --releasever=9.8 -y install epel-release
dnf --releasever=9.8 -y install \
    bash bzip2 bzip2-devel ca-certificates cpio curl-minimal findutils gcc gcc-c++ \
    genisoimage git glibc-devel gzip java-11-openjdk-devel java-17-openjdk-devel \
    jq libffi-devel make nodejs openssl-devel python3-devel python3-pip \
    python3-setuptools readline-devel rpm-build shadow-utils sqlite-devel \
    systemd-rpm-macros tar unzip wget which xz xz-devel zlib-devel
mkdir -p /opt/epic990-toolchain
cd /opt/epic990-toolchain
curl -fsSLo maven.tar.gz https://archive.apache.org/dist/maven/maven-3/3.9.10/binaries/apache-maven-3.9.10-bin.tar.gz
echo '4ef617e421695192a3e9a53b3530d803baf31f4269b26f9ab6863452d833da5530a4d04ed08c36490ad0f141b55304bceed58dbf44821153d94ae9abf34d0e1b  maven.tar.gz' | sha512sum -c -
tar -xzf maven.tar.gz -C /opt
curl -fsSLo node.tar.xz https://nodejs.org/dist/v14.21.3/node-v14.21.3-linux-x64.tar.xz
echo '05c08a107c50572ab39ce9e8663a2a2d696b5d262d5bd6f98d84b997ce932d9a  node.tar.xz' | sha256sum -c -
tar -xJf node.tar.xz -C /opt
if [[ ! -x /opt/python-3.10/bin/python3.10 ]]; then
    curl -fsSLo python.tar.xz https://www.python.org/ftp/python/3.10.21/Python-3.10.21.tar.xz
    echo 'a0da1e72132e950154eca0f6f47d5db828454700de20e5113667940d81e0db04  python.tar.xz' | sha256sum -c -
    tar -xJf python.tar.xz
    (
        cd Python-3.10.21
        ./configure --prefix=/opt/python-3.10 --with-ensurepip=install
        make -j2
        make install
    )
fi
# Do not replace system Python, which is used by dnf.
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH="/opt/apache-maven-3.9.10/bin:/opt/node-v14.21.3-linux-x64/bin:/opt/python-3.10/bin:$JAVA_HOME/bin:$PATH"
check_tools
