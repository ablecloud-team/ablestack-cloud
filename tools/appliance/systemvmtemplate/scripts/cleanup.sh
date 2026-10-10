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

set -e

function cleanup_apt() {
  export DEBIAN_FRONTEND=noninteractive
  apt-get -y remove --purge dictionaries-common busybox \
    task-english task-ssh-server tasksel tasksel-data laptop-detect wamerican sharutils \
    nano util-linux-locales krb5-locales

  apt-get -y autoremove --purge
  apt-get autoclean
  apt-get clean
}

# Removing leftover leases and persistent rules
function cleanup_dhcp() {
  rm -f /var/lib/dhcp/*
}

# Make sure Udev doesn't block our network
function cleanup_dev() {
  echo "cleaning up udev rules"
  rm -f /etc/udev/rules.d/70-persistent-net.rules
  rm -rf /dev/.udev/
  rm -f /lib/udev/rules.d/75-persistent-net-generator.rules
}

function cleanup_misc() {
  # Scripts
  rm -fr /home/cloud/cloud_scripts*
  rm -f /usr/share/cloud/cloud-scripts.tar
  rm -f /root/.rnd
  rm -f /var/www/html/index.html
  # Logs
  rm -f /var/log/*.log
  rm -f /var/log/apache2/*
  rm -f /var/log/messages
  rm -f /var/log/syslog
  rm -f /var/log/messages
  rm -fr /var/log/apt
  rm -fr /var/log/installer
  # Docs and data files
  rm -fr /var/lib/apt/*
  rm -fr /var/cache/apt/*
  rm -fr /var/cache/debconf/*old
  rm -fr /usr/share/doc
  rm -fr /usr/share/man
  rm -fr /usr/share/info
  rm -fr /usr/share/lintian
  rm -fr /usr/share/apache2/icons
  find /usr/share/locale -type f | grep -v en_US | xargs rm -fr
  find /usr/share/zoneinfo -type f | grep -v UTC | xargs rm -fr
  rm -fr /tmp/*
}

# Reset only the image being finalized. Never run this on an active VM.
# An empty machine-id lets systemd persist a per-VM ID during the next boot.
# Remove the D-Bus fallback too, otherwise systemd may import the baked ID again.
function cleanup_machine_id() {
  local image_root="${1:-/}"
  test -d "$image_root/etc"
  rm -f "$image_root/etc/machine-id"
  install -m 0444 /dev/null "$image_root/etc/machine-id"
  if test -d "$image_root/var/lib/dbus"; then
    rm -f "$image_root/var/lib/dbus/machine-id"
    ln -s /etc/machine-id "$image_root/var/lib/dbus/machine-id"
  fi
}

# Packer finalization of a new image only. Existing images and running VMs
# retain their identities; bootstrap later creates one per-instance local SID.
function cleanup_storage_identity_seed() {
  local image_root="${1:-/}" proc_root="${2:-/proc}"
  if test "$image_root" != "/"; then image_root="${image_root%/}"; fi
  if test "$image_root" = "/"; then test "$proc_root" = "/proc"; fi
  test ! -L "$image_root"
  test -d "$image_root/etc"
  if test "$image_root" = "/"; then
    systemctl stop smbd.service nmbd.service winbind.service 2>/dev/null || true
    local unit
    for unit in smbd.service nmbd.service winbind.service; do
      if systemctl is-active --quiet "$unit"; then
        echo "Identity daemon remains active during new-image finalization" >&2
        return 1
      fi
    done
  fi
  # Match the native ROOT_IDENTITY_COMMS observer scope. This is evidence
  # about known identity processes, not a claim to observe every process.
  python3 - "$proc_root" <<'PY_IDENTITY_SEED_PROCESS_CHECK'
import sys
from pathlib import Path
known=("smbd","nmbd","winbindd","samba","samba-dcerpcd","samba-bgqd")
for process in Path(sys.argv[1]).iterdir():
    if not process.name.isdigit():continue
    try:name=(process/"comm").read_text().strip()
    except FileNotFoundError:continue
    if name in known:raise SystemExit("Known identity process remains active during new-image finalization")
PY_IDENTITY_SEED_PROCESS_CHECK
  local relative parent
  for relative in var/lib/samba/private/secrets.tdb var/lib/samba/private/passdb.tdb     var/lib/samba/winbindd_idmap.tdb var/lib/samba/private/winbindd_idmap.tdb     var/lib/samba/winbindd_cache.tdb etc/krb5.keytab     etc/ablestack-storage/ad-machine.conf etc/ablestack-storage/smb-domain.json     etc/ablestack-storage/smb-semantic-identity-aliases.json; do
    parent="$(dirname "$image_root/$relative")"
    while test "$parent" != "$image_root" && test "$parent" != "/"; do
      test ! -L "$parent"
      parent="$(dirname "$parent")"
    done
    test ! -L "$image_root/$relative"
    test ! -d "$image_root/$relative"
    rm -f -- "$image_root/$relative"
    test ! -e "$image_root/$relative" && test ! -L "$image_root/$relative"
  done
}

function cleanup() {
  cleanup_apt
  cleanup_dhcp
  cleanup_dev
  cleanup_misc
  cleanup_machine_id
  cleanup_storage_identity_seed
}

return 2>/dev/null || cleanup
