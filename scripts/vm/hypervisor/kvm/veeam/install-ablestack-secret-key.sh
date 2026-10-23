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

# Prepare the shared NetBackup/Veeam Host key without replacing existing material.
# Uses the common helper from the package-owned KVM script directory.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEY_FILE="${ABLESTACK_SECRET_KEY_FILE:-/root/.ssh/ablestack.key}"

helper="${SCRIPT_DIR}/../ablestack_backup_secret_key.py"
if [[ -f "$helper" ]]; then
  exec python3 "$helper" ensure --key-file "$KEY_FILE" "$@"
fi
echo "ERROR: common Host key helper not found; update the NetBackup/Veeam scripts together" >&2
exit 1
