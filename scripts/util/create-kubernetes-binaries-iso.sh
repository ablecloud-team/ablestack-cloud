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
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if [ "$#" -eq 0 ] || [[ "$1" != --* ]]; then
    echo "Usage: $0 --recipe RECIPE.json --output OUTPUT_DIRECTORY --revision r1 [--development]" >&2
    echo "Legacy positional builds used mutable sources and are no longer release eligible. Select a checked-in recipe." >&2
    exit 2
fi
exec python3 "$SCRIPT_DIR/kubernetes-iso/build.py" "$@"
