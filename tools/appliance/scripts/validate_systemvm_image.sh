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
[[ "$NBD_DEVICE" =~ ^/dev/nbd[0-9]+$ ]] || { echo "Invalid validation NBD device" >&2; exit 2; }
NBD_NAME="${NBD_DEVICE##*/}"
NBD_SYSFS="/sys/block/$NBD_NAME"
NBD_PROCFS="/proc"
NBD_QEMU="$(readlink -f "$(command -v qemu-nbd)")"
IMAGE="$(readlink -f "$IMAGE")"
NBD_LOCK_DIR="/run/lock/ablestack-systemvm-image"
mkdir -p -m 0700 "$NBD_LOCK_DIR"
[[ ! -L "$NBD_LOCK_DIR" && -d "$NBD_LOCK_DIR" && "$(stat -c %u "$NBD_LOCK_DIR")" == "$EUID" && "$(stat -c %a "$NBD_LOCK_DIR")" == 700 ]] || {
  echo "Validation NBD lock directory is not protected" >&2; exit 2;
}
NBD_LOCK="$NBD_LOCK_DIR/$NBD_NAME.lock"
if [[ -e "$NBD_LOCK" || -L "$NBD_LOCK" ]]; then
  [[ ! -L "$NBD_LOCK" && -f "$NBD_LOCK" && "$(stat -c %u "$NBD_LOCK")" == "$EUID" && "$(stat -c %a "$NBD_LOCK")" == 600 ]] || {
    echo "Validation NBD lock file is not protected" >&2; exit 2;
  }
fi
umask 077
exec {NBD_LOCK_FD}<>"$NBD_LOCK"
flock -n "$NBD_LOCK_FD" || { echo "Validation NBD is reserved" >&2; exit 1; }
MOUNT_DIR="$(mktemp -d /tmp/systemvm-image-check.XXXXXX)"
NBD_PID_FILE="$MOUNT_DIR/nbd-owner.pid"
NBD_CONNECTED=0
NBD_OWNER=""
ROOT_MOUNTED=0
BOOT_MOUNTED=0

# BEGIN VALIDATION NBD OWNERSHIP
nbd_is_idle() {
  local size pid
  [[ -r "$NBD_SYSFS/size" ]] || return 1
  size="$(cat "$NBD_SYSFS/size")" || return 1
  [[ "$size" == 0 ]] || return 1
  if [[ -e "$NBD_SYSFS/pid" ]]; then
    pid="$(cat "$NBD_SYSFS/pid")" || return 1
    [[ -z "$pid" || "$pid" == 0 ]] || return 1
  fi
}

nbd_owner_identity() {
  python3 - "$NBD_SYSFS" "$NBD_PROCFS" "$NBD_PID_FILE" "$NBD_QEMU" "$NBD_DEVICE" "$IMAGE" <<'PYNBDOWNER'
import json,os,re,stat,sys
from pathlib import Path
try:
    kernel,proc,pidfile,qemu,device,image=map(str,sys.argv[1:])
    root=Path(kernel);owner_file=Path(pidfile);owner_info=owner_file.lstat()
    if not stat.S_ISREG(owner_info.st_mode) or owner_info.st_uid!=os.geteuid() or owner_info.st_mode&0o077:raise ValueError()
    server=owner_file.read_text().strip();token=(root/"pid").read_text().strip()
    if not re.fullmatch(r"[1-9][0-9]*",server) or not re.fullmatch(r"[1-9][0-9]*",token):raise ValueError()
    if int((root/"size").read_text().strip())<=0:raise ValueError()
    path=Path(proc)/server
    if path.stat().st_uid!=os.geteuid() or os.path.realpath(path/"exe")!=qemu:raise ValueError()
    args=[os.fsdecode(v) for v in (path/"cmdline").read_bytes().split(b"\0") if v]
    if "--read-only" not in args or "--connect="+device not in args or image not in args:raise ValueError()
    process_start=(path/"stat").read_text().rsplit(")",1)[1].split()[19]
    image_info=Path(image).lstat();device_info=Path(device).lstat()
    if not stat.S_ISREG(image_info.st_mode) or image_info.st_uid!=os.geteuid() or image_info.st_mode&0o022 or not stat.S_ISBLK(device_info.st_mode) or device_info.st_uid!=os.geteuid():raise ValueError()
    device_fds=[];image_fds=[]
    for fd in (path/"fd").iterdir():
        info=fd.stat()
        if stat.S_ISBLK(info.st_mode) and info.st_rdev==device_info.st_rdev and os.readlink(fd)==device:
            device_fds.append([fd.name,info.st_dev,info.st_ino,info.st_rdev])
        if stat.S_ISREG(info.st_mode) and (info.st_dev,info.st_ino)==(image_info.st_dev,image_info.st_ino) and os.readlink(fd)==image:
            flags=re.search(r"(?m)^flags:\s*([0-7]+)$",(path/"fdinfo"/fd.name).read_text())
            if flags is None or int(flags.group(1),8)&3:raise ValueError()
            image_fds.append([fd.name,info.st_dev,info.st_ino])
    if not device_fds or not image_fds:raise ValueError()
    if (path/"stat").read_text().rsplit(")",1)[1].split()[19]!=process_start or (root/"pid").read_text().strip()!=token:raise ValueError()
    print(json.dumps([token,server,process_start,sorted(device_fds),sorted(image_fds)],separators=(",",":")))
except (OSError,ValueError,IndexError):raise SystemExit(1)
PYNBDOWNER
}

nbd_disconnect_owned() {
  local observed
  [[ "$NBD_CONNECTED" == 1 && -n "$NBD_OWNER" ]] || return 0
  observed="$(nbd_owner_identity)" || { echo "Preserving unknown validation NBD owner" >&2; return 1; }
  [[ "$observed" == "$NBD_OWNER" ]] || { echo "Preserving replaced validation NBD owner" >&2; return 1; }
  qemu-nbd --disconnect "$NBD_DEVICE" >/dev/null 2>&1 || return 1
  NBD_CONNECTED=0
}

cleanup() {
  local rc="$?"
  trap - EXIT
  set +e
  if [[ "$BOOT_MOUNTED" == 1 ]]; then
    if umount "$MOUNT_DIR/boot"; then BOOT_MOUNTED=0; else rc=1; fi
  fi
  if [[ "$ROOT_MOUNTED" == 1 && "$BOOT_MOUNTED" == 0 ]]; then
    if umount "$MOUNT_DIR"; then ROOT_MOUNTED=0; else rc=1; fi
  fi
  if [[ "$ROOT_MOUNTED" == 0 && "$BOOT_MOUNTED" == 0 ]]; then
    if ! nbd_disconnect_owned; then rc=1; fi
  else
    echo "Preserving validation NBD with an unremoved owned mount" >&2
  fi
  if nbd_is_idle; then rm -f "$NBD_PID_FILE";rmdir "$MOUNT_DIR" >/dev/null 2>&1; fi
  exit "$rc"
}

nbd_connect_owned() {
  nbd_is_idle || { echo "Validation NBD is occupied or unobservable; preserving it" >&2; return 1; }
  # No initial disconnect: an occupied device has not been authorized as ours.
  qemu-nbd --read-only --pid-file="$NBD_PID_FILE" --connect="$NBD_DEVICE" "$IMAGE" || return 1
  local attempt observed
  for attempt in {1..30}; do
    if observed="$(nbd_owner_identity)"; then
      NBD_OWNER="$observed";NBD_CONNECTED=1;return 0
    fi
    sleep 0.1
  done
  echo "Validation NBD connection owner could not be proven; preserving it" >&2
  return 1
}
# END VALIDATION NBD OWNERSHIP
trap cleanup EXIT
modprobe nbd max_part=8 >/dev/null 2>&1 || true
nbd_connect_owned
sleep 2

ROOT_PARTITION=""
for candidate in "${NBD_DEVICE}p6" "${NBD_DEVICE}p1" "${NBD_DEVICE}p5"; do
  if [[ -b "$candidate" ]] && mount -t ext4 -o ro,noload "$candidate" "$MOUNT_DIR" >/dev/null 2>&1; then
    ROOT_MOUNTED=1
    if [[ -d "$MOUNT_DIR/usr" && -d "$MOUNT_DIR/etc" ]]; then
      ROOT_PARTITION="$candidate"
      ROOT_MOUNTED=1
      break
    fi
    umount "$MOUNT_DIR"
    ROOT_MOUNTED=0
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

# The Debian appliance recipe keeps /boot on a separate partition. Mount it
# read-only before checking the selected kernel; checking root/boot alone would
# incorrectly reject a healthy split-boot image.
if [[ ! -f "$MOUNT_DIR/boot/grub/grub.cfg" ]]; then
  for candidate in "${NBD_DEVICE}p1" "${NBD_DEVICE}p2" "${NBD_DEVICE}p3" "${NBD_DEVICE}p5"; do
    if [[ "$candidate" == "$ROOT_PARTITION" || ! -b "$candidate" ]]; then
      continue
    fi
    if mount -t ext4 -o ro,noload "$candidate" "$MOUNT_DIR/boot" >/dev/null 2>&1; then
      BOOT_MOUNTED=1
      if [[ -f "$MOUNT_DIR/boot/grub/grub.cfg" ]]; then
        BOOT_MOUNTED=1
        break
      fi
      umount "$MOUNT_DIR/boot"
      BOOT_MOUNTED=0
    fi
  done
fi
if [[ ! -f "$MOUNT_DIR/boot/grub/grub.cfg" ]]; then
  echo "Unable to locate SystemVM boot partition in $IMAGE" >&2
  exit 1
fi

assert_elf "$(resolve_link_target /usr/bin/python3)"
assert_elf "/usr/lib/python3/dist-packages/gi/_gi.cpython-311-x86_64-linux-gnu.so"
assert_elf "$(resolve_link_target /lib/x86_64-linux-gnu/libmagic.so.1)"
assert_text_prefix "/var/lib/dpkg/status" "Package:"

if [[ ! -x "$MOUNT_DIR/bin/targetcli" && ! -x "$MOUNT_DIR/usr/bin/targetcli" ]]; then
  echo "targetcli is missing from SystemVM image" >&2
  exit 1
fi

validator_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
validation_args=("$MOUNT_DIR")
if [[ -n "${SYSTEMVM_STORAGE_TEMPLATE_MANIFEST_OUTPUT:-}" ]]; then
  validation_args+=(--output "$SYSTEMVM_STORAGE_TEMPLATE_MANIFEST_OUTPUT")
fi
python3 "$validator_dir/validate_storage_template.py" "${validation_args[@]}"
echo "SystemVM image validation passed: $IMAGE"
