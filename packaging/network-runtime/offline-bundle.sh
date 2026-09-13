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
# Run on the target EL major version with the intended installation repositories.
set -euo pipefail
rpm_path=$(realpath "${1:?Usage: offline-bundle.sh runtime.rpm output-directory}")
output=${2:?Specify a new output directory}
shift 2
if [[ -e "$output" ]]; then
    echo 'Output directory must not already exist.' >&2
    exit 1
fi
mkdir -p "$output"
cp "$rpm_path" "$output/"
mapfile -t dependencies < <(rpm -qp --requires "$rpm_path" | grep -v '^rpmlib(')
dnf download --resolve --alldeps --arch="$(uname -m),noarch" --destdir "$output" "$@" "${dependencies[@]}"
createrepo_c "$output"
(cd "$output" && sha256sum ./*.rpm > SHA256SUMS)
echo "Bundle: $output (install using the package manager with only this directory)"
