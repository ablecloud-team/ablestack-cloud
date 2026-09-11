#!/usr/bin/bash
# Shared helpers for Mold host/datadisk Veeam onboarding (sourced by setup-datadisk-veeam-backup.sh).
# shellcheck shell=bash

mold_guest_common_die() { echo "ERROR: $*" >&2; exit 1; }

# Build Mold API URL from agent.properties host= value (or an explicit URL).
# - bare host/IP        → http://HOST:8080/client/api
# - http://HOST         → http://HOST:8080/client/api  (inject :8080 when port missing)
# - https://HOST        → https://HOST/client/api      (do NOT inject :8080; use 443)
# - URL with port/path  → keep scheme/port; ensure .../client/api
mold_guest_normalize_mold_api_url() {
  local raw="${1:-}"
  raw="${raw%%@*}"
  raw="$(printf '%s' "$raw" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//;s|/*$||')"
  [[ -n "$raw" ]] || return 1

  local scheme rest hostport path
  if [[ "$raw" =~ ^https?:// ]]; then
    scheme="${raw%%://*}://"
    rest="${raw#*://}"
    hostport="${rest%%/*}"
    if [[ "$rest" == *"/"* ]]; then
      path="/${rest#*/}"
    else
      path=""
    fi
    path="${path%/}"
    case "$path" in
      ""|"/") path="/client/api" ;;
      */client/api) ;;
      */client) path="${path}/api" ;;
      *) path="${path}/client/api" ;;
    esac
    if [[ "$scheme" == "http://" && "$hostport" != *:* ]]; then
      hostport="${hostport}:8080"
    fi
    printf '%s\n' "${scheme}${hostport}${path}"
    return 0
  fi

  printf '%s\n' "http://${raw}:8080/client/api"
}

mold_guest_discover_mold_api_url_from_agent() {
  local props host url
  for props in /etc/cloudstack/agent/agent.properties /etc/cloudstack/agent/agent.properties.override; do
    [[ -f "$props" ]] || continue
    host="$(grep -E '^[[:space:]]*host[[:space:]]*=' "$props" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d ' \r' || true)"
    [[ -n "$host" ]] || continue
    url="$(mold_guest_normalize_mold_api_url "$host")" || continue
    printf '%s\n' "$url"
    return 0
  done
  return 1
}

mold_guest_resolve_kvm_ip_from_env() {
  local env_file="${1:-}"
  if [[ -n "$env_file" && -f "$env_file" ]]; then
    # shellcheck source=/dev/null
    set -a && source "$env_file" && set +a
  fi
  if [[ -n "${KVM_IP:-}" ]]; then
    echo "$KVM_IP"
    return 0
  fi
  local host="${KVM_HOST:-}"
  if [[ "$host" =~ @(.+)$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  if [[ -n "$host" ]]; then
    echo "$host"
    return 0
  fi
  hostname -I 2>/dev/null | awk '{print $1}'
}

mold_guest_sync_api_from_env() {
  local env_file="$1" conf_file="$2" key val
  [[ -f "$env_file" && -f "$conf_file" ]] || return 0
  for key in MOLD_API_URL MOLD_API_KEY MOLD_API_SECRET ZONE_ID BACKUP_REPO_ADDRESS BACKUP_OFFERING_NAME \
    VEEAM_SSH_HOST MOLD_DATADISK_PATH BACKUP_STORAGE_MODE KVM_HOSTNAME \
    KVM_HOST KVM_SSH_USER VEEAM_JOB_NAME; do
    val=$(grep -E "^${key}=" "$env_file" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"') || true
    [[ -n "$val" ]] || continue
    if grep -qE "^${key}=" "$conf_file" 2>/dev/null; then
      sed -i "s#^${key}=.*#${key}=\"${val}\"#" "$conf_file"
    else
      echo "${key}=\"${val}\"" >> "$conf_file"
    fi
  done
}

# KVM hypervisor — Mold_Host_Backup.conf (host/datadisk mode).
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

# Ensure KVM pre/post notify scripts exist and Agent script timeout is set.
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
  echo "NOTE: Register Pre/Post in Veeam UI Job → Guest Processing (no PowerShell automation)."
}
