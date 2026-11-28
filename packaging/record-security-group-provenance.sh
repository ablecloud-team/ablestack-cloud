#!/usr/bin/env bash
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

source_root="$1"
stage_root="$2"
relative_path=vm/network/security_group.py
revision=$(git -C "$source_root" rev-parse --verify HEAD)
digest=$(git -C "$source_root" show "$revision:scripts/$relative_path" | sha256sum)

# Fail before building an RPM if the archive would misidentify the script.
manifest=$(printf '%s  %s\n' "${digest%% *}" "$relative_path")
(cd "$stage_root/scripts" && sha256sum -c - <<< "$manifest")

printf '%s\n' "$revision" > "$stage_root/build/gitrev.txt"
printf '%s\n' "$manifest" > "$stage_root/build/security-group.sha256"
