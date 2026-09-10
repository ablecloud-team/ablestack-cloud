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
NONOSS_SHA=not-used
SIMULATOR=${SIMULATOR:-default}
DISTRO=${DISTRO:-rocky9}
RELEASE=${RELEASE:-}
TEMPLATES=${TEMPLATES:-}
NODE_VERSION=${NODE_VERSION:-14.21.3}
if [[ "$NODE_VERSION" != 14.21.3 ]]; then
    echo "Europa requires Node.js 14.21.3" >&2
    exit 1
fi
BRAND=${BRAND:-}
PACKAGE_VERSION=${PACKAGE_VERSION:-}
TIMESTAMP_VALUE=${TIMESTAMP_VALUE:-}
BUILD_SRPM=${BUILD_SRPM:-false}
USE_TIMESTAMP=${USE_TIMESTAMP:-false}
LOCAL_FAST=${LOCAL_FAST:-false}
ABLESTACK_UI_BUILD_VERSION=${ABLESTACK_UI_BUILD_VERSION:-}

if ! command -v dnf >/dev/null 2>&1; then
    echo "This helper must run inside a Rocky/RHEL-compatible environment with dnf."
    exit 1
fi

if [ "$DISTRO" != "rocky9" ] && [ "$DISTRO" != "centos8" ]; then
    echo "Unsupported DISTRO=$DISTRO. Expected rocky9 or centos8."
    exit 1
fi

echo "== Rocky 9.8 RPM build helper =="
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
echo "ABLESTACK_UI_BUILD_VERSION=${ABLESTACK_UI_BUILD_VERSION:-<source-config>}"

"$ROOT_DIR/tools/build/rocky98-prepare.sh"
export PATH="/opt/apache-maven-3.9.10/bin:/opt/python-3.10/bin:$PATH"

if [ "$PACK" = "noredist" ] || [ "$PACK" = "NOREDIST" ]; then
    echo "Installing non-redistributable VMware build dependencies"
    NONOSS_SHA=f94b5cfcd12e7ce50f9e732ff0e12affa9030640
    tmp_nonoss_dir=$(mktemp -d /tmp/cloudstack-nonoss-XXXXXX)
    git clone --depth 1 https://github.com/shapeblue/cloudstack-nonoss.git "$tmp_nonoss_dir"
    git -C "$tmp_nonoss_dir" fetch --depth 1 origin f94b5cfcd12e7ce50f9e732ff0e12affa9030640
    git -C "$tmp_nonoss_dir" checkout --detach f94b5cfcd12e7ce50f9e732ff0e12affa9030640
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

JAVA17_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
if [ -d /usr/lib/jvm/java-17-openjdk ]; then
    JAVA17_HOME=/usr/lib/jvm/java-17-openjdk
fi

export JAVA_HOME="$JAVA17_HOME"
export PATH="$NODE_BIN_DIR:$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dcom.sun.xml.bind.v2.bytecode.ClassTailor.noOptimize=true --add-opens=java.base/java.lang=ALL-UNNAMED"
export RPM_NODE_BIN_DIR="$NODE_BIN_DIR"

git config --global --add safe.directory "$ROOT_DIR"

mkdir -p "$ROOT_DIR/dist/rocky98-build"
{
    echo "source_sha=$(git -C "$ROOT_DIR" rev-parse HEAD)"
    echo "architecture=$(uname -m)"
    echo "python_version=$(python3 --version)"
    echo "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "os_release=$(tr '\n' ' ' </etc/os-release)"
    echo "java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | tr '\n' ' ' )"
    echo "maven_version=$(mvn -v | head -n 1)"
    echo "maven_opts=$MAVEN_OPTS"
    echo "node_version=$(node -v)"
    echo "npm_version=$(npm -v)"
    echo "nonoss_sha=$NONOSS_SHA"
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
    echo "ui_build_version=${ABLESTACK_UI_BUILD_VERSION:-<source-config>}"
} >"$ROOT_DIR/dist/rocky98-build/environment.txt"
rpm -qa --qf '%{NAME}-%{VERSION}-%{RELEASE}.%{ARCH}\n' | sort > "$ROOT_DIR/dist/rocky98-build/rpm-dependencies.txt"
"$ROOT_DIR/tools/build/rocky98-prepare.sh" --check
git -C "$ROOT_DIR" ls-files -z '*pom.xml' ui/package-lock.json | \
    (cd "$ROOT_DIR" && xargs -0 sha256sum) > "$ROOT_DIR/dist/rocky98-build/dependency-inputs.sha256"

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

verify_management_schema_resources() {
    local management_rpm
    local extract_dir
    local packaged_jar
    local resource
    local source_resource
    local extracted_resource
    local -a packaged_jars

    management_rpm=$(find dist/rpmbuild/RPMS -type f -name 'cloudstack-management-*.rpm' | sort | tail -1)
    if [ -z "$management_rpm" ]; then
        echo "cloudstack-management RPM was not generated" >&2
        return 1
    fi

    extract_dir=$(mktemp -d /tmp/cloudstack-management-rpm-XXXXXX)
    (
        cd "$extract_dir"
        rpm2cpio "$ROOT_DIR/$management_rpm" | cpio -idm --quiet
    )

    mapfile -t packaged_jars < <(find "$extract_dir/usr/share/cloudstack-management/lib" -maxdepth 1 -type f -name 'cloudstack-*.jar' | sort)
    if [ "${#packaged_jars[@]}" -ne 1 ]; then
        echo "Expected exactly one packaged cloudstack application jar, found ${#packaged_jars[@]}" >&2
        rm -rf "$extract_dir"
        return 1
    fi
    packaged_jar=${packaged_jars[0]}

    if find "$extract_dir/usr/share/cloudstack-management/lib" -maxdepth 1 -type f -name 'cloud-engine-schema-*.jar' | grep -q .; then
        echo "Standalone cloud-engine-schema jar must not be packaged beside the application jar" >&2
        rm -rf "$extract_dir"
        return 1
    fi

    for dependency in \
        bcprov-jdk18on-1.83.jar \
        bcpkix-jdk18on-1.83.jar \
        bctls-jdk18on-1.83.jar \
        mysql-connector-j-8.4.0.jar; do
        if [[ ! -f "$extract_dir/usr/share/cloudstack-management/lib/$dependency" ]]; then
            echo "Missing required runtime dependency: $dependency" >&2
            rm -rf "$extract_dir"
            return 1
        fi
    done
    if find "$extract_dir/usr/share/cloudstack-management/lib" -maxdepth 1 \
        \( -name '*-jdk15on-*.jar' -o -name 'mysql-connector-j-8.0.*.jar' \) | grep -q .; then
        echo "Obsolete BC/MySQL runtime dependency in management RPM" >&2
        rm -rf "$extract_dir"
        return 1
    fi

    mkdir -p "$extract_dir/runtime-smoke"
    "$JAVA_HOME/bin/javac" \
        -cp "$packaged_jar:$extract_dir/usr/share/cloudstack-management/lib/*" \
        -d "$extract_dir/runtime-smoke" "$ROOT_DIR/tools/build/ManagementRuntimeSmoke.java"
    timeout 60s "$JAVA_HOME/bin/java" \
        -cp "$extract_dir/runtime-smoke:$packaged_jar:$extract_dir/usr/share/cloudstack-management/lib/*" \
        ManagementRuntimeSmoke | tee "$ROOT_DIR/dist/rocky98-build/runtime-smoke.txt"

    for resource in \
        META-INF/db/schema-Europa-After.sql \
        META-INF/db/views/cloud.shared_filesystem_view.sql; do
        source_resource="$ROOT_DIR/engine/schema/src/main/resources/$resource"
        extracted_resource=$(mktemp /tmp/cloudstack-schema-resource-XXXXXX)
        if ! unzip -p "$packaged_jar" "$resource" >"$extracted_resource"; then
            echo "Missing schema resource in packaged application jar: $resource" >&2
            rm -f "$extracted_resource"
            rm -rf "$extract_dir"
            return 1
        fi
        if ! cmp -s "$source_resource" "$extracted_resource"; then
            echo "Packaged schema resource differs from source: $resource" >&2
            rm -f "$extracted_resource"
            rm -rf "$extract_dir"
            return 1
        fi
        rm -f "$extracted_resource"
    done

    rm -rf "$extract_dir"
    echo "Verified management RPM schema resources: $management_rpm"
}

verify_management_schema_resources

verify_ui_build_version() {
    local ui_rpm
    local extract_dir
    local packaged_version

    if [ -z "$ABLESTACK_UI_BUILD_VERSION" ]; then
        echo "Skipping UI build version verification because no release version was supplied"
        return
    fi

    ui_rpm=$(find dist/rpmbuild/RPMS -type f -name 'cloudstack-ui-*.rpm' | sort | tail -1)
    if [ -z "$ui_rpm" ]; then
        echo "cloudstack-ui RPM was not generated" >&2
        return 1
    fi

    extract_dir=$(mktemp -d /tmp/cloudstack-ui-rpm-XXXXXX)
    (
        cd "$extract_dir"
        rpm2cpio "$ROOT_DIR/$ui_rpm" | cpio -idm --quiet
    )
    packaged_version=$(jq -r '.buildVersion // empty' \
        "$extract_dir/etc/cloudstack/ui/config.json")
    rm -rf "$extract_dir"

    if [ "$packaged_version" != "$ABLESTACK_UI_BUILD_VERSION" ]; then
        echo "Packaged UI buildVersion mismatch: expected $ABLESTACK_UI_BUILD_VERSION, got $packaged_version" >&2
        return 1
    fi

    echo "Verified UI buildVersion in $ui_rpm: $packaged_version"
}

verify_ui_build_version

find dist/rpmbuild -type f \( -name '*.rpm' -o -name '*.src.rpm' \) | sort \
    > dist/rocky98-build/artifacts.txt

while IFS= read -r artifact; do
    sha256sum "$artifact"
done < dist/rocky98-build/artifacts.txt > dist/rocky98-build/SHA256SUMS
