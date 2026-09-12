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

# NetBackup restore_notify equivalent for Veeam + Mold.
# Set BACKUP_ID (and optional VM_NAME / VM_UUID) in job conf or environment.

set -euo pipefail

CLIENT="${1:-$(hostname -s)}"
JOB="${2:-${VEEAM_JOB_NAME:-}}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=mold-backup.lib.sh
source "${SCRIPT_DIR}/mold-backup.lib.sh"

export VEEAM_JOB_NAME="$JOB"
mold_backup_restore_notify "$CLIENT" "$JOB"
