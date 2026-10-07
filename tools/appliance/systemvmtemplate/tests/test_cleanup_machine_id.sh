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
base=$(cd "$(dirname "$0")/.." && pwd)
source "$base/scripts/cleanup.sh"
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT

mkdir -p "$fixture/etc" "$fixture/var/lib/dbus"
printf '%s\n' 0123456789abcdef0123456789abcdef > "$fixture/etc/machine-id"
printf '%s\n' fedcba9876543210fedcba9876543210 > "$fixture/var/lib/dbus/machine-id"
printf 'keep\n' > "$fixture/etc/unrelated"
cleanup_machine_id "$fixture"
test -f "$fixture/etc/machine-id"
test ! -s "$fixture/etc/machine-id"
test "$(stat -c %a "$fixture/etc/machine-id")" = 444
test "$(readlink "$fixture/var/lib/dbus/machine-id")" = /etc/machine-id
test "$(cat "$fixture/etc/unrelated")" = keep
echo 'PASS: baked systemd and D-Bus IDs cleared; unrelated image data preserved'

# Finalizing twice must not follow the absolute D-Bus symlink into the build host.
host_id_before=$(sha256sum /etc/machine-id)
cleanup_machine_id "$fixture"
test ! -s "$fixture/etc/machine-id"
test "$(readlink "$fixture/var/lib/dbus/machine-id")" = /etc/machine-id
test "$host_id_before" = "$(sha256sum /etc/machine-id)"
echo 'PASS: repeat finalization does not alter the build host machine-id'

rm -f "$fixture/var/lib/dbus/machine-id"
rmdir "$fixture/var/lib/dbus"
rm -f "$fixture/etc/machine-id"
cleanup_machine_id "$fixture"
test -f "$fixture/etc/machine-id"
test ! -s "$fixture/etc/machine-id"
test ! -e "$fixture/var/lib/dbus"
echo 'PASS: absent machine-id and D-Bus directory are supported'
