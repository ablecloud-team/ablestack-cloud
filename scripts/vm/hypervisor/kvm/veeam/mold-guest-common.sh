#!/usr/bin/bash
# Shared helpers for Mold guest VM + Veeam onboarding (sourced by other scripts).
# shellcheck shell=bash

mold_guest_common_die() { echo "ERROR: $*" >&2; exit 1; }

mold_guest_resolve_script() {
  local name="$1"
  local base="${MOLD_GUEST_SCRIPT_DIR:-}"
  local etc="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
  local candidate
  for candidate in \
    "${base}/${name}" \
    "${etc}/${name}" \
    "/usr/share/mold/backup/veeam/${name}" \
    "/tmp/veeam-install/${name}"; do
    if [[ -f "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

mold_guest_sync_api_from_env() {
  local env_file="$1" conf_file="$2" key val
  [[ -f "$env_file" && -f "$conf_file" ]] || return 0
  for key in MOLD_API_URL MOLD_API_KEY MOLD_API_SECRET ZONE_ID BACKUP_REPO_ADDRESS BACKUP_OFFERING_NAME \
    VEEAM_SSH_HOST VM_TARGETS VEEAM_PASSWORD VEEAM_USERNAME VEEAM_TRIGGER_METHOD \
    MOLD_DATADISK_PATH VEEAM_HOST_REPO_ROOT VEEAM_REPO_NAME BACKUP_STORAGE_MODE KVM_HOSTNAME \
    KVM_HOST KVM_SSH_USER KVM_SSH_PASSWORD VEEAM_JOB_NAME VEEAM_PROTECTION_GROUP_NAME; do
    val=$(grep -E "^${key}=" "$env_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"') || true
    [[ -n "$val" ]] || continue
    if grep -qE "^${key}=" "$conf_file" 2>/dev/null; then
      sed -i "s#^${key}=.*#${key}=\"${val}\"#" "$conf_file"
    else
      echo "${key}=\"${val}\"" >> "$conf_file"
    fi
  done
}

# Push mold-backup.windows.conf (with KVM_SSH_PASSWORD) to Veeam B&R for setup-veeam-mold-job.ps1.
mold_guest_sync_windows_conf_to_veeam() {
  local env_file="$1"
  local etc="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
  local win_local="${etc}/mold-backup.windows.conf"
  local veeam_host veeam_user install_dir
  local -a ssh_opts=() scp_opts=()

  # shellcheck source=/dev/null
  set -a && source "$env_file" && set +a
  veeam_host="${VEEAM_SSH_HOST:-}"
  veeam_user="${VEEAM_SSH_USER:-administrator}"
  install_dir="${VEEAM_INSTALL_DIR:-C:/ProgramData/Mold/backup/veeam}"
  [[ -n "$veeam_host" ]] || return 0
  [[ -f "$win_local" ]] || return 0
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_opts=(-i "${VEEAM_SSH_KEY}")

  mold_guest_sync_api_from_env "$env_file" "$win_local"
  echo "=== Sync windows conf → ${veeam_user}@${veeam_host}:${install_dir}/mold-backup.windows.conf ==="
  scp "${scp_opts[@]}" "${ssh_opts[@]}" "$win_local" \
    "${veeam_user}@${veeam_host}:${install_dir}/mold-backup.windows.conf"
}

mold_guest_ensure_conf() {
  local etc="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
  local guest_conf="${etc}/Mold_Guest_Backup.conf"
  local main_conf="" candidate
  for candidate in "${etc}/mold-backup.conf" "${etc}/Mold_Guest_Backup.conf" "${etc}/"*.conf; do
    [[ -f "$candidate" ]] || continue
    main_conf="$candidate"
    break
  done
  [[ -n "$main_conf" ]] || mold_guest_common_die "Missing KVM backup conf under ${etc} — run veeam_config.sh first"
  if [[ ! -f "$guest_conf" ]]; then
    cp -a "$main_conf" "$guest_conf"
    chmod 0600 "$guest_conf"
    echo "Created ${guest_conf} from $(basename "$main_conf")"
  fi
  local env_file="${etc}/mold-backup.env"
  [[ -f "$env_file" ]] && mold_guest_sync_api_from_env "$env_file" "$guest_conf"
  [[ -f "$env_file" ]] && mold_guest_sync_api_from_env "$env_file" "$main_conf"
  sed -i 's/^BACKUP_MODE=.*/BACKUP_MODE="guest"/' "$guest_conf"
  for key_val in \
    'VEEAM_TRIGGER_ENABLED="true"' \
    'VEEAM_TRIGGER_METHOD="ssh"'; do
    key="${key_val%%=*}"
    if grep -qE "^${key}=" "$guest_conf" 2>/dev/null; then
      sed -i "s#^${key}=.*#${key_val}#" "$guest_conf"
    else
      echo "$key_val" >> "$guest_conf"
    fi
  done
}

# Passwordless SSH guest VM -> KVM (required for Veeam pre/post hooks).
mold_guest_run_ssh_setup() {
  local env_file="$1"
  local vm_targets="$2"
  local setup_script rc=0

  [[ -n "$vm_targets" ]] || mold_guest_common_die "VM_TARGETS empty for guest SSH setup"
  [[ -f "$env_file" ]] || mold_guest_common_die "Missing env file: $env_file"

  # shellcheck source=/dev/null
  set -a && source "$env_file" && set +a
  if [[ -z "${GUEST_VM_SSH_PASSWORD:-}" ]]; then
    mold_guest_common_die "GUEST_VM_SSH_PASSWORD is not set in ${env_file} (required for automatic guest->KVM SSH)"
  fi

  setup_script="$(mold_guest_resolve_script setup-guest-kvm-ssh.sh)" \
    || mold_guest_common_die "setup-guest-kvm-ssh.sh not found"

  echo "=== Auto: guest -> KVM SSH (${vm_targets}) ==="
  bash "$setup_script" --env-file "$env_file" --vm-targets "$vm_targets" || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    mold_guest_common_die "guest->KVM SSH setup failed (check GUEST_VM_SSH_PASSWORD and guest reachability)"
  fi
}

# conf + SSH + optional Veeam .sh upload (called from deploy / push / veeam_config).
mold_guest_prepare_onboarding() {
  local env_file="$1"
  local vm_targets="$2"
  local skip_ssh="${3:-false}"

  mold_guest_ensure_conf
  if [[ "$skip_ssh" != "true" ]]; then
    mold_guest_run_ssh_setup "$env_file" "$vm_targets"
  fi
}

mold_guest_resolve_kvm_ip_from_env() {
  local env_file="$1"
  # shellcheck source=/dev/null
  set -a && source "$env_file" && set +a
  if [[ -n "${KVM_IP:-}" ]]; then
    echo "$KVM_IP"
    return 0
  fi
  local host="${KVM_HOST:-}"
  if [[ "$host" =~ @(.+)$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  [[ -n "$host" ]] && echo "$host" && return 0
  mold_guest_common_die "Set KVM_IP or KVM_HOST in ${env_file}"
}

# E:\opt1\veeam\<kvm-host> — one Veeam repository per hypervisor (not per VM).
mold_guest_setup_veeam_host_repo() {
  local env_file="$1" kvm_hostname="${2:-}"
  local veeam_host veeam_user install_dir host_root repo_name
  local -a ssh_opts=() scp_opts=()

  # shellcheck source=/dev/null
  set -a && source "$env_file" && set +a
  veeam_host="${VEEAM_SSH_HOST:-}"
  veeam_user="${VEEAM_SSH_USER:-administrator}"
  [[ -n "$veeam_host" ]] || mold_guest_common_die "VEEAM_SSH_HOST not set"
  [[ -n "$kvm_hostname" ]] || kvm_hostname="${KVM_HOSTNAME:-$(hostname -s)}"
  install_dir="${VEEAM_INSTALL_DIR:-C:/ProgramData/Mold/backup/veeam}"
  host_root="${VEEAM_HOST_REPO_ROOT:-E:/opt1/veeam}"
  repo_name="${VEEAM_REPO_NAME:-Mold ${kvm_hostname}}"
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_opts=(-i "${VEEAM_SSH_KEY}")

  mold_guest_sync_ps1_to_veeam "$env_file"
  local ps1_local
  ps1_local="$(mold_guest_resolve_script setup-veeam-host-repo.ps1 2>/dev/null || true)"
  if [[ -n "$ps1_local" ]]; then
    scp "${scp_opts[@]}" "${ssh_opts[@]}" "$ps1_local" \
      "${veeam_user}@${veeam_host}:${install_dir}/setup-veeam-host-repo.ps1"
  fi

  echo "=== Veeam host repo: ${host_root}/${kvm_hostname} (name: ${repo_name}) ==="
  local remote_cmd
  remote_cmd=$(printf '%s' \
    "pwsh -NoProfile -ExecutionPolicy Bypass -File \"${install_dir}/setup-veeam-host-repo.ps1\" " \
    "-KvmHostname \"${kvm_hostname}\" " \
    "-HostRepoRoot \"${host_root}\" " \
    "-RepositoryName \"${repo_name}\"")
  ssh "${ssh_opts[@]}" -o BatchMode=yes -o ConnectTimeout=120 -o StrictHostKeyChecking=accept-new \
    "${veeam_user}@${veeam_host}" "$remote_cmd" \
    || mold_guest_common_die "setup-veeam-host-repo.ps1 failed — create E:\\opt1\\veeam\\${kvm_hostname} in Veeam UI"
}

# Copy required PS1 from KVM share to Veeam (not installed under /etc/ablestack/veeam).
mold_guest_sync_ps1_to_veeam() {
  local env_file="$1"
  local veeam_host veeam_user install_dir ps1_dir f local_path
  local -a ssh_opts=() scp_opts=()

  # shellcheck source=/dev/null
  set -a && source "$env_file" && set +a
  veeam_host="${VEEAM_SSH_HOST:-}"
  veeam_user="${VEEAM_SSH_USER:-administrator}"
  install_dir="${VEEAM_INSTALL_DIR:-C:/ProgramData/Mold/backup/veeam}"
  [[ -n "$veeam_host" ]] || return 0
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_opts=(-i "${VEEAM_SSH_KEY}")

  ps1_dir=""
  for f in install-veeam-job.ps1 create-veeam-agent-job.ps1 setup-veeam-mold-job.ps1 \
    setup-veeam-host-repo.ps1; do
    local_path="$(mold_guest_resolve_script "$f" 2>/dev/null || true)"
    [[ -n "$local_path" ]] && ps1_dir="$(dirname "$local_path")" && break
  done
  [[ -n "$ps1_dir" ]] || {
    echo "WARN: PS1 not found on KVM — run: bash /tmp/veeam-install/install.sh"
    return 0
  }

  echo "=== Sync PS1 → ${veeam_user}@${veeam_host}:${install_dir} (from ${ps1_dir}) ==="
  for f in install-veeam-job.ps1 create-veeam-agent-job.ps1 setup-veeam-mold-job.ps1 \
    setup-veeam-host-repo.ps1 veeam-job-pre-backup.ps1 veeam-job-post-backup.ps1 \
    veeam-job-post-restore.ps1; do
    [[ -f "${ps1_dir}/${f}" ]] || continue
    scp "${scp_opts[@]}" "${ssh_opts[@]}" "${ps1_dir}/${f}" \
      "${veeam_user}@${veeam_host}:${install_dir}/${f}"
  done
}

# KVM hypervisor (10.10.31.2) — one Veeam Agent job + Mold_Host_Backup.conf (not per-guest VM).
mold_host_ensure_conf() {
  local etc="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
  local host_conf="${etc}/Mold_Host_Backup.conf"
  local main_conf="" candidate
  for candidate in "${etc}/mold-backup.conf" "${etc}/Mold_Host_Backup.conf" "${etc}/"*.conf; do
    [[ -f "$candidate" ]] || continue
    main_conf="$candidate"
    break
  done
  [[ -n "$main_conf" ]] || mold_guest_common_die "Missing KVM backup conf under ${etc} — run veeam_config.sh first"
  if [[ ! -f "$host_conf" ]]; then
    cp -a "$main_conf" "$host_conf"
    chmod 0600 "$host_conf"
    echo "Created ${host_conf} from $(basename "$main_conf")"
  fi
  local env_file="${etc}/mold-backup.env"
  [[ -f "$env_file" ]] && mold_guest_sync_api_from_env "$env_file" "$host_conf"
  [[ -f "$env_file" ]] && mold_guest_sync_api_from_env "$env_file" "$main_conf"
  sed -i 's/^BACKUP_MODE=.*/BACKUP_MODE="host"/' "$host_conf"
  local pre="${etc}/ablestack_veeam_pre_notify.sh"
  local post="${etc}/ablestack_veeam_post_notify.sh"
  for key_val in \
    "KVM_PRE_NOTIFY_SCRIPT=\"${pre}\"" \
    "KVM_POST_NOTIFY_SCRIPT=\"${post}\"" \
    'VEEAM_TRIGGER_ENABLED="false"' \
    'VEEAM_TRIGGER_METHOD="auto"'; do
    key="${key_val%%=*}"
    if grep -qE "^${key}=" "$host_conf" 2>/dev/null; then
      sed -i "s#^${key}=.*#${key_val}#" "$host_conf"
    else
      echo "$key_val" >> "$host_conf"
    fi
  done
}

# Veeam Agent job on KVM (e.g. 10.10.31.2) → E:\opt1\veeam\<hostname>\ repository.
mold_host_ensure_kvm_prepost_scripts() {
  local etc="${ABLESTACK_VEEAM_ETC_DIR:-/etc/ablestack/veeam}"
  local pre="${KVM_PRE_NOTIFY_SCRIPT:-${etc}/ablestack_veeam_pre_notify.sh}"
  local post="${KVM_POST_NOTIFY_SCRIPT:-${etc}/ablestack_veeam_post_notify.sh}"
  local staging="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
  local ini="/etc/veeam/veeam.ini"
  local timeout="${VEEAM_SCRIPT_TIMEOUT:-1800}"

  [[ -x "$pre" ]] || mold_guest_common_die "Missing pre-notify on KVM: ${pre} — run install.sh"
  [[ -x "$post" ]] || mold_guest_common_die "Missing post-notify on KVM: ${post} — run install.sh"
  mkdir -p "$staging"
  chmod 0755 "$staging" 2>/dev/null || true

  if [[ -f "$ini" ]]; then
    if grep -q '^\[scripts\]' "$ini" 2>/dev/null; then
      if grep -q '^timeoutPrePost' "$ini" 2>/dev/null; then
        sed -i "s/^timeoutPrePost.*/timeoutPrePost = ${timeout}/" "$ini"
      else
        sed -i "/^\[scripts\]/a timeoutPrePost = ${timeout}" "$ini"
      fi
    else
      printf '\n[scripts]\ntimeoutPrePost = %s\n' "$timeout" >>"$ini"
    fi
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active veeamservice >/dev/null 2>&1; then
      systemctl restart veeamservice 2>/dev/null || true
      echo "Veeam Agent: ${ini} timeoutPrePost=${timeout}s (veeamservice restarted)"
    else
      echo "Veeam Agent: set ${ini} [scripts] timeoutPrePost = ${timeout}"
    fi
  else
    echo "WARN: ${ini} not found — install Veeam Agent for Linux on this KVM host"
  fi
  echo "KVM pre/post ready: ${pre} | ${post} | staging=${staging}"
}

# Register Guest Processing pre-freeze/post-thaw on the KVM Linux Agent job (runs ON 10.10.31.2).
mold_host_register_veeam_scripts() {
  local env_file="$1" kvm_hostname="${2:-}" kvm_ip="${3:-}"
  local veeam_host veeam_user install_dir job_name pg_name pre post
  local -a ssh_opts=()

  # shellcheck source=/dev/null
  set -a && source "$env_file" && set +a
  veeam_host="${VEEAM_SSH_HOST:-}"
  veeam_user="${VEEAM_SSH_USER:-administrator}"
  [[ -n "$veeam_host" ]] || mold_guest_common_die "VEEAM_SSH_HOST not set in ${env_file}"
  [[ -n "$kvm_hostname" ]] || kvm_hostname="${KVM_HOSTNAME:-$(hostname -s)}"
  [[ -n "$kvm_ip" ]] || kvm_ip="$(mold_guest_resolve_kvm_ip_from_env "$env_file")"
  install_dir="${VEEAM_INSTALL_DIR:-C:/ProgramData/Mold/backup/veeam}"
  job_name="${VEEAM_JOB_NAME:-Mold ${kvm_hostname}}"
  pg_name="${VEEAM_PROTECTION_GROUP_NAME:-Mold KVM Agents}"
  pre="${KVM_PRE_NOTIFY_SCRIPT:-/etc/ablestack/veeam/ablestack_veeam_pre_notify.sh}"
  post="${KVM_POST_NOTIFY_SCRIPT:-/etc/ablestack/veeam/ablestack_veeam_post_notify.sh}"
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_opts=(-i "${VEEAM_SSH_KEY}")

  mold_guest_sync_ps1_to_veeam "$env_file"

  if ! mold_guest_veeam_job_exists "$env_file" "$job_name"; then
    echo "WARN: Veeam job '${job_name}' not found — run mold_host_setup_veeam_job first"
    return 1
  fi

  echo "=== Register pre/post on Veeam host job '${job_name}' (Linux Agent ${kvm_ip}) ==="
  local remote_ps1="${install_dir}/install-veeam-job.ps1"
  local remote_cmd
  remote_cmd=$(printf '%s' \
    "pwsh -NoProfile -ExecutionPolicy Bypass -File \"${remote_ps1}\" " \
    "-JobName \"${job_name}\" " \
    "-LinuxAgent " \
    "-KvmHost \"${kvm_ip}\" " \
    "-ProtectionGroupName \"${pg_name}\" " \
    "-AgentPreNotifyScript \"${pre}\" " \
    "-AgentPostNotifyScript \"${post}\" " \
    "-InstallDir \"${install_dir}\" " \
    "-SourceDir \"${install_dir}\"")
  ssh "${ssh_opts[@]}" -o BatchMode=yes -o ConnectTimeout=120 -o StrictHostKeyChecking=accept-new \
    "${veeam_user}@${veeam_host}" "$remote_cmd" \
    || mold_guest_common_die "install-veeam-job.ps1 -LinuxAgent failed for '${job_name}'"

  echo "Registered Guest Processing scripts on '${job_name}':"
  echo "  Pre-freeze  → ${pre} (Mold export → ${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam})"
  echo "  Post-thaw   → ${post} (registry + staging cleanup)"
}

mold_host_setup_veeam_job() {
  local env_file="$1" kvm_hostname="${2:-}" kvm_ip="${3:-}"
  local veeam_host veeam_user install_dir job_name repo_name backup_path pg_name
  local -a ssh_opts=()

  # shellcheck source=/dev/null
  set -a && source "$env_file" && set +a
  veeam_host="${VEEAM_SSH_HOST:-}"
  veeam_user="${VEEAM_SSH_USER:-administrator}"
  [[ -n "$veeam_host" ]] || mold_guest_common_die "VEEAM_SSH_HOST not set in ${env_file}"
  [[ -n "$kvm_hostname" ]] || kvm_hostname="${KVM_HOSTNAME:-$(hostname -s)}"
  [[ -n "$kvm_ip" ]] || kvm_ip="$(mold_guest_resolve_kvm_ip_from_env "$env_file")"
  install_dir="${VEEAM_INSTALL_DIR:-C:/ProgramData/Mold/backup/veeam}"
  job_name="${VEEAM_JOB_NAME:-Mold ${kvm_hostname}}"
  repo_name="${VEEAM_REPO_NAME:-Mold ${kvm_hostname}}"
  backup_path="${VEEAM_HOST_BACKUP_PATH:-/tmp/mold/veeam}"
  pg_name="${VEEAM_PROTECTION_GROUP_NAME:-Mold KVM Agents}"
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_opts=(-i "${VEEAM_SSH_KEY}")

  [[ -n "${KVM_SSH_PASSWORD:-}" ]] || mold_guest_common_die "KVM_SSH_PASSWORD required in ${env_file} (Veeam: Cannot find credentials for agent)"

  mold_guest_sync_ps1_to_veeam "$env_file"
  mold_guest_sync_windows_conf_to_veeam "$env_file"
  mold_guest_setup_veeam_host_repo "$env_file" "$kvm_hostname" 2>/dev/null \
    || echo "WARN: Veeam host repo setup skipped"

  echo "=== Veeam KVM host job: ${job_name} (agent ${kvm_ip} / ${kvm_hostname}) ==="
  local remote_cmd win_conf="${install_dir}/mold-backup.windows.conf"
  remote_cmd=$(printf '%s' \
    "pwsh -NoProfile -ExecutionPolicy Bypass -File \"${install_dir}/setup-veeam-mold-job.ps1\" " \
    "-ConfPath \"${win_conf}\" " \
    "-JobName \"${job_name}\" " \
    "-KvmHost \"${kvm_ip}\" " \
    "-AgentHostName \"${kvm_hostname}\" " \
    "-BackupPath \"${backup_path}\" " \
    "-RepositoryName \"${repo_name}\" " \
    "-ProtectionGroupName \"${pg_name}\"")
  ssh "${ssh_opts[@]}" -o BatchMode=yes -o ConnectTimeout=180 -o StrictHostKeyChecking=accept-new \
    "${veeam_user}@${veeam_host}" "$remote_cmd" \
    || mold_guest_common_die "setup-veeam-mold-job.ps1 failed for KVM host ${kvm_ip}"

  mold_host_register_veeam_scripts "$env_file" "$kvm_hostname" "$kvm_ip" \
    || echo "WARN: pre/post registration skipped — re-run: mold_host_register_veeam_scripts"
}

mold_guest_veeam_job_exists() {
  local env_file="$1" job_name="$2"
  local veeam_host veeam_user install_dir remote_cmd out
  local -a ssh_opts=()

  # shellcheck source=/dev/null
  set -a && source "$env_file" && set +a
  veeam_host="${VEEAM_SSH_HOST:-}"
  veeam_user="${VEEAM_SSH_USER:-administrator}"
  [[ -n "$veeam_host" && -n "$job_name" ]] || return 1
  [[ -n "${VEEAM_SSH_KEY:-}" && -f "${VEEAM_SSH_KEY}" ]] && ssh_opts=(-i "${VEEAM_SSH_KEY}")

  remote_cmd=$(printf '%s' \
    "pwsh -NoProfile -Command \"Import-Module Veeam.Backup.PowerShell -ErrorAction SilentlyContinue; " \
    "if (Get-VBRComputerBackupJob -Name '${job_name//\'/\'\'\'}' -ErrorAction SilentlyContinue) { Write-Output 'true' } else { Write-Output 'false' }\"")

  out="$(ssh "${ssh_opts[@]}" -o BatchMode=yes -o ConnectTimeout=60 -o StrictHostKeyChecking=accept-new \
    "${veeam_user}@${veeam_host}" "$remote_cmd" 2>/dev/null | tr -d '[:space:]')"
  [[ "$out" == "true" ]]
}

# Create one guest Agent job on Veeam (+ register Guest Processing via install-veeam-job.ps1).
mold_guest_create_veeam_job_for_vm() {
  mold_guest_common_die "Guest-mode Veeam jobs were removed. Use mold_host_setup_veeam_job / setup-datadisk-veeam-backup.sh."
}

# Register Pre-job/Post-job on an EXISTING Veeam Agent job (UI-created job).
mold_guest_register_veeam_job_scripts() {
  mold_guest_common_die "Guest-mode Veeam jobs were removed. Use mold_host_register_veeam_scripts / setup-datadisk-veeam-backup.sh."
}
