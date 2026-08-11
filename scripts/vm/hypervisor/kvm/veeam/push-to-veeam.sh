#!/usr/bin/bash
# Deploy Mold Veeam scripts to B&R server and create Agent backup job (idempotent).
#
#   cp mold-backup.env.example mold-backup.env   # edit VEEAM_SSH_HOST, KVM_IP, JOB_NAME
#   bash push-to-veeam.sh --env-file mold-backup.env
#
# Requires: SSH from this host to Veeam Windows server (OpenSSH on Windows).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHARE_DIR="${MOLD_BACKUP_SHARE_DIR:-/usr/share/mold/backup/veeam}"
ETC_DIR="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"

mold_push_resolve_ps1_dir() {
  local candidate
  for candidate in "$SCRIPT_DIR" "$SHARE_DIR" "$ETC_DIR"; do
    [[ -f "${candidate}/setup-veeam-mold-job.ps1" ]] && { echo "$candidate"; return 0; }
  done
  echo "$SCRIPT_DIR"
}

mold_push_sync_windows_conf() {
  local env_file="${1:-${ETC_DIR}/mold-backup.env}"
  local win_conf="${ETC_DIR}/mold-backup.windows.conf"
  [[ -f "$env_file" ]] || return 0
  [[ -f "$win_conf" ]] || return 0

  local pw user targets kvm_host kvm_pw kvm_user
  pw="$(grep -E '^GUEST_VM_SSH_PASSWORD=' "$env_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"')" || true
  user="$(grep -E '^GUEST_VM_SSH_USER=' "$env_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"')" || true
  kvm_pw="$(grep -E '^KVM_SSH_PASSWORD=' "$env_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"')" || true
  kvm_user="$(grep -E '^KVM_SSH_USER=' "$env_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"')" || true
  targets="$(grep -E '^VM_TARGETS=' "$env_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"')" || true
  kvm_host="$(grep -E '^KVM_HOST=' "$env_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"')" || true
  [[ -z "$kvm_host" ]] && kvm_host="$(grep -E '^KVM_IP=' "$env_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"')" || true

  if [[ -n "$pw" ]]; then
    if grep -qE '^GUEST_VM_SSH_PASSWORD=' "$win_conf" 2>/dev/null; then
      sed -i "s#^GUEST_VM_SSH_PASSWORD=.*#GUEST_VM_SSH_PASSWORD=\"${pw}\"#" "$win_conf"
    else
      echo "GUEST_VM_SSH_PASSWORD=\"${pw}\"" >>"$win_conf"
    fi
  fi
  if [[ -n "$user" ]]; then
    if grep -qE '^GUEST_VM_SSH_USER=' "$win_conf" 2>/dev/null; then
      sed -i "s#^GUEST_VM_SSH_USER=.*#GUEST_VM_SSH_USER=\"${user}\"#" "$win_conf"
    else
      echo "GUEST_VM_SSH_USER=\"${user}\"" >>"$win_conf"
    fi
  fi
  if [[ -n "$targets" ]]; then
    if grep -qE '^VM_TARGETS=' "$win_conf" 2>/dev/null; then
      sed -i "s#^VM_TARGETS=.*#VM_TARGETS=\"${targets}\"#" "$win_conf"
    else
      echo "VM_TARGETS=\"${targets}\"" >>"$win_conf"
    fi
  fi
  if [[ -n "$kvm_host" ]]; then
    if grep -qE '^KVM_HOST=' "$win_conf" 2>/dev/null; then
      sed -i "s#^KVM_HOST=.*#KVM_HOST=\"${kvm_host}\"#" "$win_conf"
    else
      echo "KVM_HOST=\"${kvm_host}\"" >>"$win_conf"
    fi
  fi
  if [[ -n "$kvm_pw" ]]; then
    if grep -qE '^KVM_SSH_PASSWORD=' "$win_conf" 2>/dev/null; then
      sed -i "s#^KVM_SSH_PASSWORD=.*#KVM_SSH_PASSWORD=\"${kvm_pw}\"#" "$win_conf"
    else
      echo "KVM_SSH_PASSWORD=\"${kvm_pw}\"" >>"$win_conf"
    fi
  fi
  if [[ -n "$kvm_user" ]]; then
    if grep -qE '^KVM_SSH_USER=' "$win_conf" 2>/dev/null; then
      sed -i "s#^KVM_SSH_USER=.*#KVM_SSH_USER=\"${kvm_user}\"#" "$win_conf"
    else
      echo "KVM_SSH_USER=\"${kvm_user}\"" >>"$win_conf"
    fi
  fi
}

die() { echo "ERROR: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      [[ -f "$2" ]] || die "not found: $2"
      ENV_FILE="$2"
      # shellcheck source=/dev/null
      set -a && source "$2" && set +a
      shift 2
      ;;
    --vm-targets)
      die "Guest-mode VM_TARGETS removed. Use host/datadisk mode only."
      ;;
    --skip-create) SKIP_CREATE=true; shift ;;
    --no-start) SKIP_START=true; shift ;;
    --skip-guest-onboard) shift ;; # ignored (compat)
    -h|--help)
      echo "Usage: push-to-veeam.sh [--env-file mold-backup.env] [--skip-create] [--no-start]"
      echo "  Deploys host-mode PS1 to Veeam and runs setup-veeam-mold-job.ps1"
      exit 0
      ;;
    *) die "Unknown: $1" ;;
  esac
done

SKIP_CREATE="${SKIP_CREATE:-false}"
SKIP_START="${SKIP_START:-false}"
VEEAM_SSH_HOST="${VEEAM_SSH_HOST:-}"
VEEAM_SSH_USER="${VEEAM_SSH_USER:-administrator}"
VEEAM_SSH_KEY="${VEEAM_SSH_KEY:-}"
VEEAM_INSTALL_DIR="${VEEAM_INSTALL_DIR:-C:/ProgramData/Mold/backup/veeam}"
JOB_NAME="${JOB_NAME:-${VEEAM_JOB_NAME:-Mold KVM Backup}}"
KVM_IP="${KVM_IP:-10.10.31.2}"
KVM_HOSTNAME="${KVM_HOSTNAME:-}"
BACKUP_PATH="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
VEEAM_REPO_NAME="${VEEAM_REPO_NAME:-}"

[[ -n "$VEEAM_SSH_HOST" ]] || die "Set VEEAM_SSH_HOST=10.10.254.246 in mold-backup.env"

VEEAM_SSH="${VEEAM_SSH_USER}@${VEEAM_SSH_HOST}"
SSH_OPTS=()
[[ -n "$VEEAM_SSH_KEY" && -f "$VEEAM_SSH_KEY" ]] && SSH_OPTS=(-i "$VEEAM_SSH_KEY")

veeam_ssh() {
  if ((${#SSH_OPTS[@]} > 0)); then
    ssh "${SSH_OPTS[@]}" "$@"
  else
    ssh "$@"
  fi
}

veeam_scp() {
  if ((${#SSH_OPTS[@]} > 0)); then
    scp "${SSH_OPTS[@]}" "$@"
  else
    scp "$@"
  fi
}

if [[ "${VEEAM_BACKUP_TARGET:-}" == "guest" || -n "${VM_TARGETS:-}" ]]; then
  die "Guest-mode Veeam jobs were removed. Use host/datadisk mode (VEEAM_BACKUP_TARGET=host, unset VM_TARGETS)."
fi

VEEAM_FILES=(
  setup-veeam-mold-job.ps1
  create-veeam-agent-job.ps1
  install-veeam-job.ps1
  veeam-job-pre-backup.ps1
  veeam-job-post-backup.ps1
  veeam-job-post-restore.ps1
  setup-veeam-host-repo.ps1
  mold-backup.windows.conf.default
)

VEEAM_FILES_REQUIRED=(
  setup-veeam-mold-job.ps1
  create-veeam-agent-job.ps1
  install-veeam-job.ps1
  mold-backup.windows.conf.default
)

PS1_DIR="$(mold_push_resolve_ps1_dir)"
if ! grep -q 'SelectedFiles' "${PS1_DIR}/install-veeam-job.ps1" 2>/dev/null; then
  die "Outdated PS1 in ${PS1_DIR} (missing SelectedFiles support). Run: bash install.sh from updated veeam/ package, or MOLD_BACKUP_SHARE_DIR=/path/to/repo/veeam bash push-to-veeam.sh ..."
fi
mold_push_sync_windows_conf "${ENV_FILE:-${ETC_DIR}/mold-backup.env}"

echo "=== Prepare Veeam install dir: ${VEEAM_INSTALL_DIR} ==="
veeam_ssh "$VEEAM_SSH" "powershell -Command \"New-Item -ItemType Directory -Force -Path '${VEEAM_INSTALL_DIR}' | Out-Null\"" \
  || die "Cannot SSH to Veeam server ${VEEAM_SSH}"

echo "=== SCP scripts → ${VEEAM_SSH}:${VEEAM_INSTALL_DIR}/ (from ${PS1_DIR}) ==="
for f in "${VEEAM_FILES_REQUIRED[@]}"; do
  [[ -f "${PS1_DIR}/${f}" ]] || die "Missing required ${PS1_DIR}/${f} — run install.sh or set MOLD_BACKUP_SHARE_DIR"
done
for f in "${VEEAM_FILES[@]}"; do
  [[ -f "${PS1_DIR}/${f}" ]] || continue
  veeam_scp "${PS1_DIR}/${f}" "${VEEAM_SSH}:${VEEAM_INSTALL_DIR}/${f}"
done

# Push KVM-generated windows conf (local file or remote KVM_HOST)
if [[ -f "${ETC_DIR}/mold-backup.windows.conf" ]]; then
  echo "=== Copy mold-backup.windows.conf from ${ETC_DIR} ==="
  veeam_scp "${ETC_DIR}/mold-backup.windows.conf" \
    "${VEEAM_SSH}:${VEEAM_INSTALL_DIR}/mold-backup.windows.conf"
else
  KVM_SSH="${KVM_HOST:-}"
  if [[ -n "$KVM_SSH" && "$KVM_SSH" != *@* ]]; then
    KVM_SSH="root@${KVM_SSH}"
  fi
  if [[ -n "$KVM_SSH" ]]; then
    echo "=== Try copy mold-backup.windows.conf from KVM ==="
    veeam_scp "${KVM_SSH}:/etc/ablestack/veeam/mold-backup.windows.conf" \
      "${VEEAM_SSH}:${VEEAM_INSTALL_DIR}/mold-backup.windows.conf" 2>/dev/null \
      || echo "NOTE: mold-backup.windows.conf not copied from KVM (run veeam_config.sh on KVM first)"
  fi
fi

if [[ "$SKIP_CREATE" == "true" ]]; then
  echo "=== Skip job create (--skip-create) ==="
  exit 0
fi

mold_push_ps_escape() {
  # PowerShell single-quoted string: ' -> ''
  printf '%s' "$1" | sed "s/'/''/g"
}

mold_push_write_remote_runner() {
  local setup_script="$1"
  shift
  local runner_local
  runner_local="$(mktemp "${TMPDIR:-/tmp}/mold-push-remote-run.XXXXXX.ps1")"
  {
    printf '%s\n' '$ErrorActionPreference = "Stop"'
    printf '& %s' "'$(mold_push_ps_escape "$setup_script")'"
    while [[ $# -gt 0 ]]; do
      local flag="$1" val="${2:-}"
      if [[ "$flag" == -* && -n "$val" && "$val" != -* ]]; then
        printf ' %s %s' "$flag" "'$(mold_push_ps_escape "$val")'"
        shift 2
      else
        printf ' %s' "$flag"
        shift
      fi
    done
    printf '\n'
  } >"$runner_local"
  veeam_scp "$runner_local" "${VEEAM_SSH}:${VEEAM_INSTALL_DIR}/mold-push-remote-run.ps1"
  rm -f "$runner_local"
}

mold_push_run_remote_setup() {
  local setup_script="${VEEAM_INSTALL_DIR}/setup-veeam-mold-job.ps1"
  local setup_name="setup-veeam-mold-job.ps1"
  local runner_path="${VEEAM_INSTALL_DIR}/mold-push-remote-run.ps1"
  local -a ps_args=(
    -InstallDir "${VEEAM_INSTALL_DIR}"
    -ConfPath "${VEEAM_INSTALL_DIR}/mold-backup.windows.conf"
  )

  echo "=== setup-veeam-mold-job.ps1 (file-level /tmp/mold/veeam + Pre/Post) ==="
  [[ -n "$KVM_HOSTNAME" ]] && ps_args+=(-AgentHostName "$KVM_HOSTNAME")
  [[ -n "$VEEAM_REPO_NAME" ]] && ps_args+=(-RepositoryName "$VEEAM_REPO_NAME")
  if [[ "${VEEAM_START_JOBS:-true}" != "false" && "$SKIP_START" != "true" ]]; then
    ps_args+=(-StartJob)
  fi

  echo "=== Upload remote runner for ${setup_name} ==="
  mold_push_write_remote_runner "$setup_script" "${ps_args[@]}"

  # No quotes around -File path: OpenSSH on Windows breaks ''path'' with nested quoting.
  echo "=== Remote: ${setup_name} on ${VEEAM_SSH} ==="
  if veeam_ssh "$VEEAM_SSH" pwsh -NoProfile -ExecutionPolicy Bypass -File "${runner_path}"; then
    return 0
  fi
  if veeam_ssh "$VEEAM_SSH" powershell -NoProfile -ExecutionPolicy Bypass -File "${runner_path}"; then
    return 0
  fi
  die "Veeam job setup failed — check Agent on KVM and mold-backup.windows.conf"
}

mold_push_run_remote_setup

echo ""
echo "=== Done ==="
echo "Veeam UI: jobs started automatically (set VEEAM_START_JOBS=false or --no-start to skip)"
echo "FLR→Mold: on KVM run enable-veeam-mold-restore.sh (mold-veeam-restore-agent.timer)"
echo "KVM log:  ssh ${KVM_SSH:-root@${KVM_IP}} tail -f /var/log/mold/veeam-hook.log"
