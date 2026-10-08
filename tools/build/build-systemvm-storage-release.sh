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
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
: "${SYSTEMVM_VERSION:?}" "${SYSTEMVM_BUILD_NUMBER:?}" "${STORAGE_RUNTIME_SIGNING_KEY_ID:?}" "${STORAGE_RUNTIME_SIGNING_PRIVATE_KEY_FILE:?}"
export ABLESTACK_STORAGE_RUNTIME_BUILD_COMMIT="$(git rev-parse HEAD)"
export ABLESTACK_STORAGE_RUNTIME_BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export SYSTEMVM_STORAGE_RUNTIME_VERSION="${SYSTEMVM_VERSION}-${SYSTEMVM_BUILD_NUMBER}"
mkdir -p dist/systemvm-kvm
set -o pipefail
(
  cd tools/appliance
  PACKER_LOG="${PACKER_LOG:-1}" bash ./build.sh systemvmtemplate "$SYSTEMVM_VERSION" x86_64 "$SYSTEMVM_BUILD_NUMBER"
) 2>&1 | tee dist/systemvm-kvm/systemvm-kvm-build.log
runtime_version="$SYSTEMVM_STORAGE_RUNTIME_VERSION"
runtime_dir=dist/systemvm-kvm/release/storage-runtime
tools/build/build-storage-runtime-bundle.sh \
  --version "$runtime_version" --private-key "$STORAGE_RUNTIME_SIGNING_PRIVATE_KEY_FILE" \
  --key-id "$STORAGE_RUNTIME_SIGNING_KEY_ID" --output-dir "$runtime_dir"
(cd "$runtime_dir" && sha256sum -c SHA256SUMS)
public_key="systemvm/debian/etc/ablestack-storage/runtime-trusted-keys/${STORAGE_RUNTIME_SIGNING_KEY_ID}.pem"
openssl pkeyutl -verify -pubin -inkey "$public_key" \
  -sigfile "$runtime_dir/manifest.sig" -rawin -in "$runtime_dir/manifest.json"
runtime_prefix="ablestack-storage-runtime-${runtime_version}"
mv "$runtime_dir/manifest.json" "$runtime_dir/${runtime_prefix}.manifest.json"
mv "$runtime_dir/manifest.sig" "$runtime_dir/${runtime_prefix}.manifest.sig"
cp "$public_key" "$runtime_dir/${STORAGE_RUNTIME_SIGNING_KEY_ID}.pem"
(
  cd "$runtime_dir"
  rm -f SHA256SUMS
  sha256sum "${runtime_prefix}.tar.gz" "${runtime_prefix}.manifest.json" \
    "${runtime_prefix}.manifest.sig" "${STORAGE_RUNTIME_SIGNING_KEY_ID}.pem" \
    > "${runtime_prefix}.SHA256SUMS"
)
