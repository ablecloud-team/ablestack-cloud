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

# Keep the validated mount available for external backup-solution UI restores.
set -euo pipefail

fail() {
    printf 'Third-party staging validation failed: %s\n' "$*" >&2
    exit 1
}

[[ "$#" -eq 8 ]] || fail "Expected eight staging arguments."
storage_type=$1
root_path=$2
mount_path=$3
nfs_source=$4
mount_options=$5
operation_path=$6
timeout_seconds=$7
required_bytes=$8

[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || fail "Invalid mount timeout."
[[ "$required_bytes" =~ ^[0-9]+$ ]] || fail "Invalid required capacity."
[[ "$mount_path" == /* && "$mount_path" != / ]] || fail "A separate absolute mount point is required."
[[ "$root_path" == "$mount_path" || "$root_path" == "$mount_path/"* ]] || fail "Staging root must be within the mount point."
[[ "$operation_path" == "$root_path" || "$operation_path" == "$root_path/"* ]] || fail "Operation path must be within the staging root."
[[ "$(realpath -m -- "$mount_path")" == "$mount_path" ]] || fail "Mount path must be canonical and must not traverse symlinks."
[[ "$(realpath -m -- "$root_path")" == "$root_path" ]] || fail "Staging root must be canonical and must not traverse symlinks."
[[ "$(realpath -m -- "$operation_path")" == "$operation_path" ]] || fail "Operation path must be canonical and must not traverse symlinks."

# Serialize mount preparation across concurrent agent requests on this host.
mkdir -p /run/lock
exec 9>/run/lock/ablestack-thirdparty-staging.lock
flock -w "$timeout_seconds" 9 || fail "Another staging mount preparation is in progress."

if ! findmnt -rn --mountpoint "$mount_path" >/dev/null; then
    mkdir -p -- "$mount_path"
    mount_args=()
    [[ -z "$mount_options" ]] || mount_args=(-o "$mount_options")
    case "$storage_type" in
        NFS)
            [[ "$nfs_source" == *:/* && "$nfs_source" != -* ]] || fail "NFS source must use server:/export format."
            mount -t nfs "${mount_args[@]}" -- "$nfs_source" "$mount_path" || fail "Unable to mount the configured NFS export."
            ;;
        LOCAL|GFS2)
            findmnt -rn --fstab --mountpoint "$mount_path" >/dev/null || fail "Prepare the staging mount in /etc/fstab on this host first."
            mount "${mount_args[@]}" -- "$mount_path" || fail "Unable to mount the host-prepared staging filesystem."
            ;;
        *)
            fail "Unsupported staging storage type."
            ;;
    esac
fi

actual_type=$(findmnt -rn --mountpoint "$mount_path" -o FSTYPE)
actual_source=$(findmnt -rn --mountpoint "$mount_path" -o SOURCE)
case "$storage_type" in
    NFS)
        [[ "$actual_type" == nfs || "$actual_type" == nfs4 ]] || fail "Configured NFS mount has filesystem type [$actual_type]."
        [[ "${actual_source%/}" == "${nfs_source%/}" ]] || fail "Mounted NFS source [$actual_source] differs from configured source [$nfs_source]."
        ;;
    GFS2)
        [[ "$actual_type" == gfs2 ]] || fail "Configured GFS2 mount has filesystem type [$actual_type]."
        ;;
    LOCAL)
        [[ "$actual_type" == xfs || "$actual_type" == ext4 || "$actual_type" == btrfs ]] || fail "Configured LOCAL mount must use a local XFS, EXT4, or Btrfs filesystem."
        ;;
    *)
        fail "Unsupported staging storage type."
        ;;
esac

# Create job roots only after the actual mount is confirmed.
mkdir -p -- "$operation_path"
[[ "$(realpath -e -- "$operation_path")" == "$operation_path" ]] || fail "Operation path must not traverse symlinks."
[[ "$(findmnt -rn --target "$operation_path" -o TARGET)" == "$mount_path" ]] || fail "Operation path resolves to another filesystem."

probe_dir=""
cleanup_probe() {
    if [[ -n "$probe_dir" ]]; then
        rm -f -- "$probe_dir/probe"
        rmdir -- "$probe_dir"
    fi
}
trap cleanup_probe EXIT
probe_dir=$(mktemp -d "$operation_path/.ablestack-staging-check.XXXXXX") || fail "Staging path is not writable."
printf 'ABLESTACK staging write check\n' > "$probe_dir/probe" || fail "Unable to write to staging storage."
sync -f "$probe_dir/probe" || fail "Unable to sync the staging write probe."
cleanup_probe
probe_dir=""

available_bytes=$(LC_ALL=C df -PB1 -- "$operation_path" | awk 'NR == 2 {print $4}')
[[ "$available_bytes" =~ ^[0-9]+$ ]] || fail "Unable to read staging filesystem capacity."
(( available_bytes >= required_bytes )) || fail "Insufficient capacity: required [$required_bytes] bytes, available [$available_bytes] bytes."
printf '%s\n' "$available_bytes"
