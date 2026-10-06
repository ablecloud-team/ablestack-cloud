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

# Use only the disposable independent restore fixture, after importing its RDB.
namespace=${1:-}
expected_records=${2:-100}
[[ "$namespace" =~ ^rt1230-[a-z0-9-]+-restore$ ]] || { echo "ERROR: independent restore namespace required" >&2; exit 1; }
[[ "$expected_records" =~ ^[1-9][0-9]*$ ]] || { echo "ERROR: positive expected record count required" >&2; exit 1; }
kubectl_binary=${KUBECTL:-kubectl}
k() { timeout 210 "$kubectl_binary" -n "$namespace" "$@"; }
redis() { timeout 20 "$kubectl_binary" --request-timeout=10s -n "$namespace" exec redis-0 -- redis-cli "$@" | tr -d '\r'; }

# Reject empty AOF-first startup rather than finalizing an empty database.
[[ "$(redis CONFIG GET appendonly | tail -1)" == no ]] || { echo "ERROR: start the restored RDB with appendonly no first" >&2; exit 1; }
[[ "$(redis DBSIZE)" == "$expected_records" ]] || { echo "ERROR: restored record count differs" >&2; exit 1; }
before_uid=$(k get pod redis-0 -o jsonpath='{.metadata.uid}')
[[ "$(redis CONFIG SET appendonly yes)" == OK ]] || { echo "ERROR: cannot enable AOF" >&2; exit 1; }
ready=false
aof_deadline=$((SECONDS + 120))
for ((attempt=0; attempt<120; attempt++)); do
    (( SECONDS < aof_deadline )) || break
    info=$(redis INFO persistence)
    if grep -qx 'aof_enabled:1' <<< "$info" && grep -qx 'aof_rewrite_in_progress:0' <<< "$info" \
            && grep -qx 'aof_last_bgrewrite_status:ok' <<< "$info" \
            && grep -Eq '^aof_current_size:[1-9][0-9]*$' <<< "$info"; then
        ready=true
        break
    fi
    sleep 1
done
[[ "$ready" == true ]] || { echo "ERROR: AOF rewrite timed out or failed" >&2; exit 1; }
k patch statefulset redis --type strategic --patch '{"spec":{"template":{"spec":{"containers":[{"name":"redis","command":["redis-server","--appendonly","yes","--dir","/data"]}]}}}}'
k rollout status statefulset/redis --timeout=180s
after_uid=$(k get pod redis-0 -o jsonpath='{.metadata.uid}')
[[ "$before_uid" != "$after_uid" ]] || { echo "ERROR: restored Redis was not recreated" >&2; exit 1; }
[[ "$(redis CONFIG GET appendonly | tail -1)" == yes && "$(redis DBSIZE)" == "$expected_records" ]] || { echo "ERROR: records or AOF lost after restart" >&2; exit 1; }
echo "RDB_IMPORT_AOF_REWRITE_AND_RESTART_RECORD_COUNT_PASS $expected_records"
# The caller must also compare every data value and the separate files checksum.
