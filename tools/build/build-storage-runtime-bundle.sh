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

usage() {
  echo "Usage: $0 --version VERSION --private-key KEY --key-id KEY_ID [--output-dir DIR] [--source-root DIR]" >&2
  exit 2
}

version=""
private_key=""
key_id=""
output_dir=""
source_root=""
runtime_abi="${ABLESTACK_STORAGE_RUNTIME_ABI_VERSION:-1}"
desired_schema="${ABLESTACK_STORAGE_DESIRED_STATE_SCHEMA_VERSION:-1}"
service_impact="${ABLESTACK_STORAGE_RUNTIME_SERVICE_IMPACT:-NONE}"
build_commit="${ABLESTACK_STORAGE_RUNTIME_BUILD_COMMIT:-}"
build_time="${ABLESTACK_STORAGE_RUNTIME_BUILD_TIME:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) version="${2:-}"; shift 2 ;;
    --private-key) private_key="${2:-}"; shift 2 ;;
    --key-id) key_id="${2:-}"; shift 2 ;;
    --output-dir) output_dir="${2:-}"; shift 2 ;;
    --source-root) source_root="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ "$version" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || usage
[[ "$key_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || usage
[[ -r "$private_key" ]] || usage
[[ "$service_impact" == "NONE" ]] || { echo "Only serviceImpact=NONE is supported" >&2; exit 1; }

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_root="${source_root:-${repo_root}/systemvm/debian/usr/local/bin}"
build_commit="${build_commit:-$(git -C "$repo_root" rev-parse HEAD)}"
build_time="${build_time:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
output_dir="${output_dir:-${repo_root}/dist/storage-runtime}"
mkdir -p "$output_dir"

for name in ablestack-storagectl ablestack-storage-boot-reconcile ablestack-storage-monitor; do
  [[ -r "${source_root}/${name}" ]] || { echo "Missing runtime source: ${source_root}/${name}" >&2; exit 1; }
done

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
stage="${workdir}/stage"
mkdir -p "$stage"
for name in ablestack-storagectl ablestack-storage-boot-reconcile ablestack-storage-monitor; do
  install -m 0755 "${source_root}/${name}" "${stage}/${name}"
done

manifest="${output_dir}/manifest.json"
signature="${output_dir}/manifest.sig"
archive="${output_dir}/ablestack-storage-runtime-${version}.tar.gz"

python3 - "$stage" "$manifest" "$version" "$runtime_abi" "$desired_schema" "$service_impact" "$key_id" "$build_commit" "$build_time" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

stage, output, version, runtime_abi, desired_schema, impact, key_id, commit, build_time = sys.argv[1:]
stage = Path(stage)
files = []
for path in sorted(stage.iterdir(), key=lambda item: item.name):
    files.append({
        "path": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "mode": "0755",
        "owner": "root",
        "group": "root",
    })
manifest = {
    "bundleVersion": version,
    "runtimeAbiVersion": runtime_abi,
    "desiredStateSchemaVersion": desired_schema,
    "serviceImpact": impact,
    "keyId": key_id,
    "buildCommit": commit,
    "buildTime": build_time,
    "files": files,
    "compatibility": {
        "schemaVersion": 1,
        "manager": {"minimumVersion": "4.23.0.0", "maximumVersionExclusive": "4.24.0.0"},
        "agent": {"minimumVersion": "4.23.0.0", "maximumVersionExclusive": "4.24.0.0"},
        "template": {"minimumVersion": "4.23.0.0", "maximumVersionExclusive": "4.24.0.0"},
    },
    "configurationGenerationSchemaVersion": "1",
    "identityCapsuleSchemaVersion": "1",
    "configurationGenerationRootTransfer": ["ADOPT", "ALIGN"],
    "supportedFeatures": ["NESTED_FILE_SHARE", "NFS_NUMERIC_IDENTITY", "POSIX_DIRECTORY_POLICY", "SMB_NETWORK_ACL", "SMB_CREATION_MODE", "SMB_PARENT_OWNER", "SMB_FORCED_IDENTITY", "LOGICAL_RESOURCE_RESERVATION", "SERVICE_MAINTENANCE", "RENDERED_CONFIG_GENERATION_HANDLER", "SMB_AD_IDENTITY_HANDLER"],
}
Path(output).write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
PY

source_date_epoch="${SOURCE_DATE_EPOCH:-0}"
tar --sort=name --mtime="@${source_date_epoch}" --owner=0 --group=0 --numeric-owner \
  -C "$stage" -czf "$archive" \
  ablestack-storagectl ablestack-storage-boot-reconcile ablestack-storage-monitor
openssl pkeyutl -sign -inkey "$private_key" -rawin -in "$manifest" -out "$signature"
(
  cd "$output_dir"
  sha256sum "$(basename "$archive")" manifest.json manifest.sig > SHA256SUMS
)

printf '%s\n' "$archive"
