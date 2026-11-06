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
output_dir="${output_dir:-${repo_root}/dist/storage-runtime}"
mkdir -p "$output_dir"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
stage="${workdir}/stage"
mkdir -p "$stage"
python3 - "$repo_root" "$source_root" "$stage" <<'PYBUILD'
import ast, pathlib, shutil, sys
repo, source, stage = map(pathlib.Path, sys.argv[1:])
module = repo / "systemvm/debian/usr/local/lib/ablestack-storage/runtime_updater.py"
tree = ast.parse(module.read_text())
contract = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t,ast.Name) and t.id=="MANAGED_FILES" for t in n.targets))
for name, mode in contract.items():
    original = source / name if "/" not in name else repo / "systemvm/debian/usr/local" / name
    if not original.is_file() or original.is_symlink():
        raise SystemExit("Missing or unsafe runtime source: " + str(original))
    target = stage / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(original, target)
    target.chmod(mode)
PYBUILD

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
for path in sorted((x for x in stage.rglob("*") if x.is_file()), key=lambda item: str(item.relative_to(stage))):
    files.append({
        "path": str(path.relative_to(stage)),
        "bytes": path.stat().st_size,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "mode": format(path.stat().st_mode & 0o777, "04o"),
        "owner": "root",
        "group": "root",
    })
manifest = {
    "manifestFormat": 2,
    "payloadLayout": 2,
    "bundleVersion": version,
    "runtimeAbiVersion": runtime_abi,
    "desiredStateSchemaVersion": desired_schema,
    "serviceImpact": impact,
    "keyId": key_id,
    "buildCommit": commit,
    "buildTime": build_time,
    "files": files,
}
Path(output).write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
PY

source_date_epoch="${SOURCE_DATE_EPOCH:-0}"
tar --sort=name --mtime="@${source_date_epoch}" --owner=0 --group=0 --numeric-owner \
  -C "$stage" -czf "$archive" \
  ablestack-storagectl ablestack-storage-boot-reconcile ablestack-storage-monitor lib
openssl pkeyutl -sign -inkey "$private_key" -rawin -in "$manifest" -out "$signature"
(
  cd "$output_dir"
  sha256sum "$(basename "$archive")" manifest.json manifest.sig > SHA256SUMS
)

printf '%s\n' "$archive"
