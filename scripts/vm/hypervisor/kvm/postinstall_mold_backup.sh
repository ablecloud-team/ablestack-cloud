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

# Entry point for mold-agent or cloudstack-common RPM %post.
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -x "${DIR}/veeam/install.sh" ]]; then
  "${DIR}/veeam/install.sh"
fi
# veeam/install.sh deploys /root/.ssh/ablestack.key from ablestack.key.default when missing (chmod 600).
# mold-backup hooks decrypt MOLD_API_SECRET via mold-backup-secret.sh + MOLD_SECRET_KEY_FILE.
