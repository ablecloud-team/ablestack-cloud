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

# Install Ablestack Veeam backup hooks on KVM host (mold-agent post-install).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
LEGACY_ETC="/etc/mold/backup/veeam"
SHARE_DIR="${MOLD_BACKUP_SHARE_DIR:-/usr/share/mold/backup/veeam}"
LOG_DIR="/var/log/mold"
ABLESTACK_SECRET_KEY_FILE="${ABLESTACK_SECRET_KEY_FILE:-/root/.ssh/ablestack.key}"

install -d -m 0755 "${SHARE_DIR}" "${ETC_DIR}" "${ETC_DIR}/secrets" "${ETC_DIR}/state" "${ETC_DIR}/hooks" "${ETC_DIR}/registry" "${LOG_DIR}"

ABLESTACK_SECRET_KEY_FILE="${ABLESTACK_SECRET_KEY_FILE}" bash "${SCRIPT_DIR}/install-ablestack-secret-key.sh"
if [[ -f "${SCRIPT_DIR}/ablestack.key.default" ]]; then
  install -m 0644 "${SCRIPT_DIR}/ablestack.key.default" "${SHARE_DIR}/ablestack.key.default"
  install -m 0644 "${SCRIPT_DIR}/ablestack.key.default" "${ETC_DIR}/ablestack.key.default"
fi

for f in install.sh install-ablestack-secret-key.sh mold-backup.lib.sh mold-backup-secret.sh mold-backup.sh veeam_config.sh \
  ablestack_veeam_pre_notify.sh ablestack_veeam_post_notify.sh ablestack_veeam_restore_notify.sh ablestack_veeam_restore_event.sh \
  mold-veeam-trigger-hook.sh; do
  [[ -f "${SCRIPT_DIR}/${f}" ]] || continue
  install -m 0755 "${SCRIPT_DIR}/${f}" "${ETC_DIR}/${f}"
  install -m 0755 "${SCRIPT_DIR}/${f}" "${SHARE_DIR}/${f}" 2>/dev/null || true
done

CVT_SRC=""
for _cvt_candidate in \
  "${ABLESTACK_CVT_BACKUP_SRC:-}" \
  "${SCRIPT_DIR}/ablestack_cvtbackup.sh" \
  "${SCRIPT_DIR}/../ablestack_cvtbackup.sh" \
  "/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/ablestack_cvtbackup.sh"; do
  [[ -n "${_cvt_candidate}" && -f "${_cvt_candidate}" ]] || continue
  CVT_SRC="${_cvt_candidate}"
  break
done
if [[ -n "${CVT_SRC}" ]]; then
  install -m 0755 "${CVT_SRC}" "${ETC_DIR}/ablestack_cvtbackup.sh"
  install -m 0755 "${CVT_SRC}" "${SHARE_DIR}/ablestack_cvtbackup.sh"
  CS_CVT_DIR="/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm"
  if [[ -d "${CS_CVT_DIR}" ]]; then
    install -m 0755 "${CVT_SRC}" "${CS_CVT_DIR}/ablestack_cvtbackup.sh"
  fi
  echo "Installed host export script: ${ETC_DIR}/ablestack_cvtbackup.sh (from ${CVT_SRC})"
else
  echo "ERROR: ablestack_cvtbackup.sh not found." >&2
  echo "  Copy scripts/vm/hypervisor/kvm/veeam/ (includes ablestack_cvtbackup.sh) or set ABLESTACK_CVT_BACKUP_SRC=/path/to/ablestack_cvtbackup.sh" >&2
  exit 1
fi

NAS_SRC=""
for _nas_candidate in \
  "${ABLESTACK_NAS_BACKUP_SRC:-}" \
  "${SCRIPT_DIR}/ablestack_nasbackup.sh" \
  "${SCRIPT_DIR}/../ablestack_nasbackup.sh" \
  "/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm/ablestack_nasbackup.sh"; do
  [[ -n "${_nas_candidate}" && -f "${_nas_candidate}" ]] || continue
  NAS_SRC="${_nas_candidate}"
  break
done
if [[ -n "${NAS_SRC}" ]]; then
  install -m 0755 "${NAS_SRC}" "${ETC_DIR}/ablestack_nasbackup.sh"
  install -m 0755 "${NAS_SRC}" "${SHARE_DIR}/ablestack_nasbackup.sh"
  CS_KVM_DIR="/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm"
  install -d -m 0755 "${CS_KVM_DIR}"
  install -m 0755 "${NAS_SRC}" "${CS_KVM_DIR}/ablestack_nasbackup.sh"
  echo "Installed NAS backup script: ${CS_KVM_DIR}/ablestack_nasbackup.sh (from ${NAS_SRC})"
else
  echo "WARN: ablestack_nasbackup.sh not found — import-veeam-seed requires agent + this script" >&2
fi

install -m 0644 "${SCRIPT_DIR}/mold-ms-backup-schema-fix.sql" "${SHARE_DIR}/mold-ms-backup-schema-fix.sql" 2>/dev/null || true
install -m 0644 "${SCRIPT_DIR}/mold-ms-backup-schema-fix.sql" "${ETC_DIR}/mold-ms-backup-schema-fix.sql" 2>/dev/null || true

install -m 0644 "${SCRIPT_DIR}/mold-backup.conf.default" "${SHARE_DIR}/mold-backup.conf.default"
install -m 0644 "${SCRIPT_DIR}/mold-backup.env.example" "${SHARE_DIR}/mold-backup.env.example"
if [[ ! -f "${ETC_DIR}/mold-backup.env" ]]; then
  install -m 0600 "${SCRIPT_DIR}/mold-backup.env.example" "${ETC_DIR}/mold-backup.env"
  echo "Created ${ETC_DIR}/mold-backup.env — set MOLD_API_KEY / MOLD_API_SECRET, then veeam_config.sh --job-name ..."
else
  echo "Keeping existing ${ETC_DIR}/mold-backup.env"
fi
install -m 0644 "${SCRIPT_DIR}/mold-backup.windows.conf.default" "${SHARE_DIR}/mold-backup.windows.conf.default"
install -m 0644 "${SCRIPT_DIR}/veeam-job-pre-backup.ps1" "${SHARE_DIR}/veeam-job-pre-backup.ps1"
install -m 0644 "${SCRIPT_DIR}/veeam-job-post-backup.ps1" "${SHARE_DIR}/veeam-job-post-backup.ps1"
install -m 0644 "${SCRIPT_DIR}/veeam-job-post-restore.ps1" "${SHARE_DIR}/veeam-job-post-restore.ps1"
install -m 0644 "${SCRIPT_DIR}/install-veeam-job.ps1" "${SHARE_DIR}/install-veeam-job.ps1"
install -m 0644 "${SCRIPT_DIR}/create-veeam-agent-job.ps1" "${SHARE_DIR}/create-veeam-agent-job.ps1" 2>/dev/null || true
install -m 0644 "${SCRIPT_DIR}/setup-veeam-mold-job.ps1" "${SHARE_DIR}/setup-veeam-mold-job.ps1" 2>/dev/null || true
install -m 0755 "${SCRIPT_DIR}/setup-datadisk-veeam-backup.sh" "${ETC_DIR}/setup-datadisk-veeam-backup.sh" 2>/dev/null || true
install -m 0644 "${SCRIPT_DIR}/setup-veeam-host-repo.ps1" "${SHARE_DIR}/setup-veeam-host-repo.ps1" 2>/dev/null || true
install -m 0755 "${SCRIPT_DIR}/mold-guest-common.sh" "${ETC_DIR}/mold-guest-common.sh"
install -m 0755 "${SCRIPT_DIR}/push-to-veeam.sh" "${SHARE_DIR}/push-to-veeam.sh" 2>/dev/null || true
install -m 0755 "${SCRIPT_DIR}/push-to-veeam.sh" "${ETC_DIR}/push-to-veeam.sh" 2>/dev/null || true

if [[ -f "${SCRIPT_DIR}/README.ko.md" ]]; then
  install -m 0644 "${SCRIPT_DIR}/README.ko.md" "${SHARE_DIR}/README.ko.md"
fi

if [[ ! -f "${ETC_DIR}/mold-backup.conf" ]]; then
  install -m 0600 "${SCRIPT_DIR}/mold-backup.conf.default" "${ETC_DIR}/mold-backup.conf"
  echo "Created ${ETC_DIR}/mold-backup.conf — run veeam_config.sh for job-based setup."
else
  echo "Keeping existing ${ETC_DIR}/mold-backup.conf"
fi

# Backward-compatible symlink for older paths
if [[ ! -e "${LEGACY_ETC}" ]]; then
  ln -sfn "${ETC_DIR}" "${LEGACY_ETC}" 2>/dev/null || true
fi

mkdir -p "${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}" 2>/dev/null || true

install_restore_agent_units() {
  local unit_dir="/etc/systemd/system"
  [[ -f "${SCRIPT_DIR}/mold-veeam-restore-agent.service" ]] || return 0
  for f in mold-veeam-restore-agent.sh enable-veeam-mold-restore.sh; do
    [[ -f "${SCRIPT_DIR}/${f}" ]] || continue
    install -m 0755 "${SCRIPT_DIR}/${f}" "${ETC_DIR}/${f}"
    install -m 0755 "${SCRIPT_DIR}/${f}" "${SHARE_DIR}/${f}" 2>/dev/null || true
  done
  for f in mold-veeam-restore-agent.service mold-veeam-restore-agent.timer; do
    [[ -f "${SCRIPT_DIR}/${f}" ]] || continue
    install -m 0644 "${SCRIPT_DIR}/${f}" "${ETC_DIR}/${f}"
    install -m 0644 "${SCRIPT_DIR}/${f}" "${SHARE_DIR}/${f}" 2>/dev/null || true
  done
  install -m 0644 "${SCRIPT_DIR}/mold-veeam-restore-agent.service" "${unit_dir}/mold-veeam-restore-agent.service"
  install -m 0644 "${SCRIPT_DIR}/mold-veeam-restore-agent.timer" "${unit_dir}/mold-veeam-restore-agent.timer"
  if command -v systemctl >/dev/null 2>&1; then
    systemctl daemon-reload 2>/dev/null || true
    systemctl disable mold-veeam-restore-watch.timer 2>/dev/null || true
    systemctl stop mold-veeam-restore-watch.timer 2>/dev/null || true
    systemctl enable mold-veeam-restore-agent.timer 2>/dev/null || true
    systemctl start mold-veeam-restore-agent.timer 2>/dev/null || true
    echo "Enabled mold-veeam-restore-agent.timer (3min poll → Mold restoreBackup on Veeam FLR)"
    echo "  Run: ${ETC_DIR}/enable-veeam-mold-restore.sh --vm-include 'i-2-XX-VM'"
  fi
}
install_restore_agent_units

echo "Installed Ablestack Veeam backup hooks (host/datadisk mode):"
echo "  Active: ${ETC_DIR}/"
echo "  Reference: ${SHARE_DIR}/"
echo "  Secret key: ${ABLESTACK_SECRET_KEY_FILE}"
echo "  Host backup path: /tmp/mold/veeam"
echo "  Configure: veeam_config.sh --job-name ... --install"
echo "  Datadisk setup: ${ETC_DIR}/setup-datadisk-veeam-backup.sh"
echo "  FLR→Mold: ${ETC_DIR}/enable-veeam-mold-restore.sh"
echo "  Veeam PS1/Job: ${ETC_DIR}/push-to-veeam.sh"
