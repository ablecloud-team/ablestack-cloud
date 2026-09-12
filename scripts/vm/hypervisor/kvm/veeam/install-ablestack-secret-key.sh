#!/usr/bin/bash
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

# Deploy /root/.ssh/ablestack.key for mold-backup-secret.sh (idempotent).
# Safe to run from /etc/ablestack/veeam when full install.sh is not present.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEY_FILE="${ABLESTACK_SECRET_KEY_FILE:-/root/.ssh/ablestack.key}"

src=""
for candidate in \
  "${ABLESTACK_KEY_DEFAULT_SRC:-}" \
  "${SCRIPT_DIR}/ablestack.key.default" \
  "/usr/share/mold/backup/veeam/ablestack.key.default" \
  "/tmp/veeam-install/ablestack.key.default"; do
  [[ -n "$candidate" && -f "$candidate" ]] || continue
  src="$candidate"
  break
done

install -d -m 0700 "$(dirname "$KEY_FILE")"
if [[ -f "$KEY_FILE" ]]; then
  chmod 0600 "$KEY_FILE"
  echo "Keeping existing ${KEY_FILE} ($(wc -c < "$KEY_FILE") bytes)"
  exit 0
fi
if [[ -z "$src" ]]; then
  echo "WARN: ablestack.key.default not found — create ${KEY_FILE} manually" >&2
  echo "  printf 'QWJsZWNsb3VkMSE=' > ${KEY_FILE} && chmod 600 ${KEY_FILE}" >&2
  exit 1
fi
install -m 0600 "$src" "$KEY_FILE"
echo "Installed ${KEY_FILE} from ${src} ($(wc -c < "$KEY_FILE") bytes)"
