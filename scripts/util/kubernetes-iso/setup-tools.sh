#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
set -euo pipefail
DEST=${1:?Usage: setup-tools.sh TOOL_DIRECTORY}
mkdir -p "$DEST"
curl --fail --location --retry 3 --silent --show-error https://github.com/sigstore/cosign/releases/download/v2.4.3/cosign-linux-amd64 -o "$DEST/cosign"
echo "caaad125acef1cb81d58dcdc454a1e429d09a750d1e9e2b3ed1aed8964454708  $DEST/cosign" | sha256sum -c -
chmod +x "$DEST/cosign"
curl --fail --location --retry 3 --silent --show-error https://github.com/containerd/containerd/releases/download/v1.7.28/containerd-1.7.28-linux-amd64.tar.gz -o "$DEST/containerd.tar.gz"
echo "7a8c262deb63becc877e82d23749e4f99f4a17e8e660f9b8c257ca87a5c056b6  $DEST/containerd.tar.gz" | sha256sum -c -
tar -xzf "$DEST/containerd.tar.gz" -C "$DEST"
