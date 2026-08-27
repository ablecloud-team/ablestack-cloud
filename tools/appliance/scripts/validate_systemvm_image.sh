#!/bin/bash
#
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
#
# Validate a SystemVM qcow2 image before it is published as a template.
# This catches corrupted compressed template artifacts before Cloud registers
# them and creates broken SystemVMs.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <systemvm-template.qcow2>" >&2
  exit 2
fi

IMAGE="$1"
if [[ ! -f "$IMAGE" ]]; then
  echo "SystemVM image does not exist: $IMAGE" >&2
  exit 2
fi

if ! command -v qemu-img >/dev/null 2>&1; then
  echo "qemu-img is required to validate SystemVM images" >&2
  exit 2
fi
if ! command -v qemu-nbd >/dev/null 2>&1; then
  echo "qemu-nbd is required to validate SystemVM images" >&2
  exit 2
fi

qemu-img check "$IMAGE" >/dev/null

NBD_DEVICE="${SYSTEMVM_VALIDATE_NBD_DEVICE:-/dev/nbd7}"
MOUNT_DIR="$(mktemp -d /tmp/systemvm-image-check.XXXXXX)"

cleanup() {
  set +e
  mountpoint -q "$MOUNT_DIR" && umount "$MOUNT_DIR"
  qemu-nbd --disconnect "$NBD_DEVICE" >/dev/null 2>&1
  rmdir "$MOUNT_DIR" >/dev/null 2>&1
}
trap cleanup EXIT

modprobe nbd max_part=8 >/dev/null 2>&1 || true
qemu-nbd --disconnect "$NBD_DEVICE" >/dev/null 2>&1 || true
qemu-nbd --connect="$NBD_DEVICE" "$IMAGE"
sleep 2

ROOT_PARTITION=""
for candidate in "${NBD_DEVICE}p6" "${NBD_DEVICE}p1" "${NBD_DEVICE}p5"; do
  if [[ -b "$candidate" ]] && mount -o ro "$candidate" "$MOUNT_DIR" >/dev/null 2>&1; then
    if [[ -d "$MOUNT_DIR/usr" && -d "$MOUNT_DIR/etc" ]]; then
      ROOT_PARTITION="$candidate"
      break
    fi
    umount "$MOUNT_DIR"
  fi
done

if [[ -z "$ROOT_PARTITION" ]]; then
  echo "Unable to locate SystemVM root partition in $IMAGE" >&2
  exit 1
fi

assert_elf() {
  local path="$1"
  if [[ ! -e "$MOUNT_DIR$path" ]]; then
    echo "Missing expected ELF file in SystemVM image: $path" >&2
    exit 1
  fi
  local magic
  magic="$(od -An -tx1 -N4 "$MOUNT_DIR$path" | tr -d ' \n')"
  if [[ "$magic" != "7f454c46" ]]; then
    echo "Invalid ELF header in SystemVM image: $path" >&2
    exit 1
  fi
}

assert_text_prefix() {
  local path="$1"
  local prefix="$2"
  if [[ ! -e "$MOUNT_DIR$path" ]]; then
    echo "Missing expected text file in SystemVM image: $path" >&2
    exit 1
  fi
  if ! head -c "${#prefix}" "$MOUNT_DIR$path" | grep -q "^${prefix}$"; then
    echo "Invalid text header in SystemVM image: $path" >&2
    exit 1
  fi
}

resolve_link_target() {
  local path="$1"
  if [[ -L "$MOUNT_DIR$path" ]]; then
    local target
    target="$(readlink "$MOUNT_DIR$path")"
    if [[ "$target" = /* ]]; then
      printf '%s' "$target"
    else
      printf '%s/%s' "$(dirname "$path")" "$target"
    fi
  else
    printf '%s' "$path"
  fi
}

assert_elf "$(resolve_link_target /usr/bin/python3)"
assert_elf "/usr/lib/python3/dist-packages/gi/_gi.cpython-311-x86_64-linux-gnu.so"
assert_elf "$(resolve_link_target /lib/x86_64-linux-gnu/libmagic.so.1)"
assert_text_prefix "/var/lib/dpkg/status" "Package:"

if [[ ! -x "$MOUNT_DIR/bin/targetcli" && ! -x "$MOUNT_DIR/usr/bin/targetcli" ]]; then
  echo "targetcli is missing from SystemVM image" >&2
  exit 1
fi

echo "SystemVM image validation passed: $IMAGE"
