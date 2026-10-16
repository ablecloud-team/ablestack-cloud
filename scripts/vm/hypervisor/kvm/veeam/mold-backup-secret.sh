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

# Shared AES-256-GCM encryption; legacy Veeam ciphertext is supported for decryption only.
# Keys and binary data are handled by Python, never by Shell command substitution.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_HELPER="${SCRIPT_DIR}/mold-backup-secret.py"
[[ -f "$PYTHON_HELPER" ]] || { echo "ERROR: common credential helper not found; update the Veeam scripts" >&2; exit 1; }
exec python3 "$PYTHON_HELPER" "$@"
