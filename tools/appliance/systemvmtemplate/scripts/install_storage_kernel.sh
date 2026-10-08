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

# Packer uploads this lock alongside the provisioner. Installing a checked
# package rather than a floating meta-package also makes rebuilds reviewable.
lock_file="${SYSTEMVM_STORAGE_KERNEL_LOCK:-/tmp/storage-kernel-amd64.json}"
if [[ "$(dpkg --print-architecture)" != "amd64" ]]; then
  echo "The pinned Storage Service kernel currently supports amd64 only" >&2
  exit 1
fi
DEBIAN_FRONTEND=noninteractive apt-get -q -y --no-install-recommends install python3 curl ca-certificates
readarray -t kernel < <(python3 - "$lock_file" <<'PYKERNEL'
import json, sys
lock = json.load(open(sys.argv[1], encoding="utf-8"))
for key in ("url", "sha256", "packageName", "packageVersion", "kernelVersion"):
    print(lock[key])
PYKERNEL
)
[[ ${#kernel[@]} == 5 ]]
package=$(mktemp /tmp/storage-kernel.XXXXXX.deb)
trap 'rm -f "$package"' EXIT
curl -fsSL --retry 5 --retry-all-errors "${kernel[0]}" -o "$package"
printf '%s  %s\n' "${kernel[1]}" "$package" | sha256sum -c -
[[ "$(dpkg-deb -f "$package" Package)" == "${kernel[2]}" ]]
[[ "$(dpkg-deb -f "$package" Version)" == "${kernel[3]}" ]]
DEBIAN_FRONTEND=noninteractive apt-get -q -y --no-install-recommends install "$package"
apt-mark manual "${kernel[2]}"
apt-mark hold "${kernel[2]}"
python3 - "$lock_file" <<'PYCONFIG'
import json, pathlib, sys
lock = json.load(open(sys.argv[1], encoding="utf-8"))
config = pathlib.Path("/boot/config-" + lock["kernelVersion"]).read_text()
for key, value in lock["requiredConfig"].items():
    if key + "=" + value not in config.splitlines():
        raise SystemExit("Required Storage Service kernel option missing: " + key)
PYCONFIG
update-initramfs -u -k "${kernel[4]}"
update-grub
