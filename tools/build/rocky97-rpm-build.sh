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

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd -P)
PACK=${PACK:-oss}
SIMULATOR=${SIMULATOR:-default}
DISTRO=${DISTRO:-rocky9}
RELEASE=${RELEASE:-}
TEMPLATES=${TEMPLATES:-}
NODE_VERSION=${NODE_VERSION:-14.21.3}
BRAND=${BRAND:-}
PACKAGE_VERSION=${PACKAGE_VERSION:-}
TIMESTAMP_VALUE=${TIMESTAMP_VALUE:-}
BUILD_SRPM=${BUILD_SRPM:-false}
USE_TIMESTAMP=${USE_TIMESTAMP:-false}
LOCAL_FAST=${LOCAL_FAST:-false}

if ! command -v dnf >/dev/null 2>&1; then
    echo "This helper must run inside a Rocky/RHEL-compatible environment with dnf."
    exit 1
fi

if [ "$DISTRO" != "rocky9" ] && [ "$DISTRO" != "centos8" ]; then
    echo "Unsupported DISTRO=$DISTRO. Expected rocky9 or centos8."
    exit 1
fi

echo "== Rocky 9.7 RPM build helper =="
echo "ROOT_DIR=$ROOT_DIR"
echo "DISTRO=$DISTRO"
echo "PACK=$PACK"
echo "SIMULATOR=$SIMULATOR"
echo "RELEASE=${RELEASE:-<default>}"
echo "TEMPLATES=${TEMPLATES:-<none>}"
echo "NODE_VERSION=$NODE_VERSION"
echo "BRAND=${BRAND:-<default>}"
echo "PACKAGE_VERSION=${PACKAGE_VERSION:-<maven>}"
echo "TIMESTAMP_VALUE=${TIMESTAMP_VALUE:-<generated>}"
echo "BUILD_SRPM=$BUILD_SRPM"
echo "USE_TIMESTAMP=$USE_TIMESTAMP"
echo "LOCAL_FAST=$LOCAL_FAST"

configure_rocky_vault_repositories() {
    local repo

    for repo in /etc/yum.repos.d/rocky*.repo; do
        [ -f "$repo" ] || continue
        sed -E -i \
            -e 's|^mirrorlist=|#mirrorlist=|' \
            -e 's|^#?baseurl=https?://dl\.rockylinux\.org/\$contentdir|baseurl=https://dl.rockylinux.org/vault/rocky|' \
            "$repo"
    done
}

# Rocky 9.7 is archived, so both the container image and its package
# repositories must use the Rocky vault.
configure_rocky_vault_repositories

DNF=(dnf --releasever=9.7 -y)

"${DNF[@]}" install dnf-plugins-core
dnf --releasever=9.7 config-manager --set-enabled crb || true
"${DNF[@]}" install epel-release || true
"${DNF[@]}" install \
    bash \
    bzip2 \
    ca-certificates \
    findutils \
    gcc \
    genisoimage \
    git \
    glibc-devel \
    gzip \
    java-11-openjdk-devel \
    java-17-openjdk-devel \
    jq \
    maven \
    nodejs \
    openssl-devel \
    python3-devel \
    python3-pip \
    python3-setuptools \
    rpm-build \
    systemd-rpm-macros \
    shadow-utils \
    tar \
    unzip \
    wget \
    which \
    xz

if [ "$PACK" = "noredist" ] || [ "$PACK" = "NOREDIST" ]; then
    echo "Installing non-redistributable VMware build dependencies"
    tmp_nonoss_dir=$(mktemp -d /tmp/cloudstack-nonoss-XXXXXX)
    git clone --depth 1 https://github.com/shapeblue/cloudstack-nonoss.git "$tmp_nonoss_dir"
    (
        cd "$tmp_nonoss_dir"
        bash -x install-non-oss.sh
    )
    rm -rf "$tmp_nonoss_dir"
fi

if [ ! -x "/opt/node-v${NODE_VERSION}-linux-x64/bin/node" ]; then
    curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
        | tar -xJf - -C /opt
fi
NODE_BIN_DIR="/opt/node-v${NODE_VERSION}-linux-x64/bin"

PROJECT_JDK_VERSION=$(sed -n \
    's:.*<cs\.jdk\.version>\([^<]*\)</cs\.jdk\.version>.*:\1:p' \
    "$ROOT_DIR/pom.xml" | head -n 1)
case "$PROJECT_JDK_VERSION" in
    11|17) ;;
    *)
        echo "Unsupported or missing cs.jdk.version in pom.xml: ${PROJECT_JDK_VERSION:-<empty>}"
        exit 1
        ;;
esac

PROJECT_JAVA_HOME="/usr/lib/jvm/java-${PROJECT_JDK_VERSION}-openjdk"
if [ ! -x "$PROJECT_JAVA_HOME/bin/javac" ]; then
    project_javac=$(find /usr/lib/jvm -maxdepth 4 -type f \
        -path "*java-${PROJECT_JDK_VERSION}*/bin/javac" -print -quit)
    if [ -z "$project_javac" ]; then
        echo "Unable to find JDK ${PROJECT_JDK_VERSION} under /usr/lib/jvm"
        exit 1
    fi
    PROJECT_JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$project_javac")")")
fi

export JAVA_HOME="$PROJECT_JAVA_HOME"
export PATH="$NODE_BIN_DIR:$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dcom.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize=true --add-opens=java.base/java.lang=ALL-UNNAMED"
export RPM_NODE_BIN_DIR="$NODE_BIN_DIR"

git config --global --add safe.directory "$ROOT_DIR"

mkdir -p "$ROOT_DIR/dist/rocky97-build"
{
    echo "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "os_release=$(tr '\n' ' ' </etc/os-release)"
    echo "java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | tr '\n' ' ' )"
    echo "maven_version=$(mvn -v | head -n 1)"
    echo "maven_opts=$MAVEN_OPTS"
    echo "node_version=$(node -v)"
    echo "npm_version=$(npm -v)"
    echo "pack=$PACK"
    echo "simulator=$SIMULATOR"
    echo "distro=$DISTRO"
    echo "release=${RELEASE:-<default>}"
    echo "templates=${TEMPLATES:-<none>}"
    echo "brand=${BRAND:-<default>}"
    echo "package_version=${PACKAGE_VERSION:-<maven>}"
    echo "timestamp_value=${TIMESTAMP_VALUE:-<generated>}"
    echo "build_srpm=$BUILD_SRPM"
    echo "use_timestamp=$USE_TIMESTAMP"
    echo "local_fast=$LOCAL_FAST"
} >"$ROOT_DIR/dist/rocky97-build/environment.txt"

build_args=(
    --distribution "$DISTRO"
    --pack "$PACK"
)

if [ "$SIMULATOR" != "default" ]; then
    build_args+=(--simulator "$SIMULATOR")
fi

if [ -n "$RELEASE" ]; then
    build_args+=(--release "$RELEASE")
fi

if [ -n "$BRAND" ]; then
    build_args+=(--brand "$BRAND")
fi

if [ -n "$PACKAGE_VERSION" ]; then
    build_args+=(--package-version "$PACKAGE_VERSION")
fi

if [ -n "$TIMESTAMP_VALUE" ]; then
    build_args+=(--timestamp-value "$TIMESTAMP_VALUE")
fi

if [ -n "$TEMPLATES" ]; then
    build_args+=(--templates "$TEMPLATES")
fi

if [ "$USE_TIMESTAMP" == "true" ] || [ -n "$TIMESTAMP_VALUE" ]; then
    build_args+=(--use-timestamp)
fi

if [ "$BUILD_SRPM" == "true" ]; then
    build_args+=(--build-srpm)
fi

if [ "$LOCAL_FAST" == "true" ]; then
    build_args+=(--local-fast)
fi

cd "$ROOT_DIR/packaging"
package_status=0
./package.sh "${build_args[@]}" || package_status=$?

if [ "$package_status" -ne 0 ]; then
    echo "RPM packaging failed once; cleaning incomplete Maven downloads and retrying"
    find /root/.m2/repository -type f -name '*.part' -delete 2>/dev/null || true
    sleep 5
    package_status=0
    ./package.sh "${build_args[@]}" || package_status=$?
fi

if [ "$package_status" -ne 0 ]; then
    echo "RPM packaging failed after retry" >&2
    exit "$package_status"
fi

cd "$ROOT_DIR"
find dist/rpmbuild -type f \( -name '*.rpm' -o -name '*.src.rpm' \) | sort \
    > dist/rocky97-build/artifacts.txt
