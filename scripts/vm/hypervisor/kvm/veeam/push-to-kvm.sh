#!/usr/bin/bash
# Copy veeam scripts to KVM and run install.sh (+ optional veeam_config).
# Run from Git repo on Mac or any host with SSH to KVM.
#
#   cp mold-backup.env.example mold-backup.env   # edit API keys
#   bash scripts/vm/hypervisor/kvm/veeam/push-to-kvm.sh --env-file mold-backup.env
#
# Or:
#   KVM_HOST=root@10.10.31.2 bash scripts/vm/hypervisor/kvm/veeam/push-to-kvm.sh
#   bash scripts/vm/hypervisor/kvm/veeam/push-to-kvm.sh --host root@10.10.31.2 --no-configure

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../../.." && pwd)"
RUN_CONFIGURE="${RUN_CONFIGURE:-true}"

die() { echo "ERROR: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      [[ -f "$2" ]] || die "not found: $2"
      # shellcheck source=/dev/null
      set -a && source "$2" && set +a
      shift 2
      ;;
    --host|--kvm-host)
      [[ -n "${2:-}" ]] || die "$1 requires a value (e.g. root@10.10.31.2)"
      KVM_HOST="$2"
      [[ "$KVM_HOST" == *@* ]] || KVM_HOST="root@${KVM_HOST}"
      shift 2
      ;;
    --jump|--jump-host)
      [[ -n "${2:-}" ]] || die "$1 requires a value (e.g. root@10.10.31.20)"
      JUMP_HOST="$2"
      shift 2
      ;;
    --no-configure) RUN_CONFIGURE=false; shift ;;
    -h|--help)
      echo "Usage: push-to-kvm.sh [--host root@KVM] [--jump root@JUMP] [--env-file mold-backup.env] [--no-configure]"
      echo ""
      echo "Examples:"
      echo "  bash push-to-kvm.sh --host root@10.10.31.2 --no-configure"
      echo "  KVM_HOST=root@10.10.31.2 bash push-to-kvm.sh"
      echo "  bash push-to-kvm.sh --env-file mold-backup.env"
      exit 0
      ;;
    *) die "Unknown: $1 (try --help)" ;;
  esac
done

KVM_HOST="${KVM_HOST:-}"
[[ -n "$KVM_HOST" ]] || die "Set KVM_HOST=root@10.10.31.2 (or use --env-file)"

# SSH via jump host (e.g. Mac → ccvm → KVM): JUMP_HOST=root@10.10.31.20
SSH_OPTS=()
SCP_OPTS=()
if [[ -n "${JUMP_HOST:-}" ]]; then
  SSH_OPTS=(-o "ProxyJump=${JUMP_HOST}")
  SCP_OPTS=(-o "ProxyJump=${JUMP_HOST}")
  echo "Using jump host: ${JUMP_HOST}"
fi

kvm_ssh() {
  if ((${#SSH_OPTS[@]} > 0)); then
    ssh "${SSH_OPTS[@]}" "$@"
  else
    ssh "$@"
  fi
}

kvm_scp() {
  if ((${#SCP_OPTS[@]} > 0)); then
    scp "${SCP_OPTS[@]}" "$@"
  else
    scp "$@"
  fi
}

echo "=== SCP veeam scripts → ${KVM_HOST}:/tmp/veeam-install/ ==="
kvm_ssh "$KVM_HOST" "mkdir -p /tmp/veeam-install"
# Include parent ablestack_nasbackup.sh (agent restore path)
NAS_PARENT="${SCRIPT_DIR}/../ablestack_nasbackup.sh"
kvm_scp -r "${SCRIPT_DIR}/"* "${KVM_HOST}:/tmp/veeam-install/"
[[ -f "$NAS_PARENT" ]] && kvm_scp "$NAS_PARENT" "${KVM_HOST}:/tmp/veeam-install/ablestack_nasbackup.sh"

echo "=== install.sh on KVM ==="
kvm_ssh "$KVM_HOST" "bash /tmp/veeam-install/install.sh"

if [[ "$RUN_CONFIGURE" == "true" ]]; then
  JOB_NAME="${JOB_NAME:-Mold KVM Backup}"
  CONFIG_ARGS=(--job-name "${JOB_NAME}" --install)
  [[ -n "${KVM_IP:-}" ]] && CONFIG_ARGS+=(--kvm-host "${KVM_IP}")
  if [[ -n "${MOLD_API_URL:-}" && -n "${MOLD_API_KEY:-}" && -n "${MOLD_API_SECRET:-}" ]]; then
    CONFIG_ARGS+=(
      --mold-url "${MOLD_API_URL}"
      --api-key "${MOLD_API_KEY}"
      --api-secret "${MOLD_API_SECRET}"
    )
    [[ -n "${ZONE_ID:-}" ]] && CONFIG_ARGS+=(--zone-id "${ZONE_ID}")
    [[ -n "${VM_INCLUDE:-}" ]] && CONFIG_ARGS+=(--vm-include "${VM_INCLUDE}")
    [[ -n "${VM_NAME:-}" ]] && CONFIG_ARGS+=(--vm-name "${VM_NAME}")
    [[ -n "${VM_UUID:-}" ]] && CONFIG_ARGS+=(--vm-uuid "${VM_UUID}")
    [[ -n "${BACKUP_REPO_ADDRESS:-}" ]] && CONFIG_ARGS+=(--nas-repo "${BACKUP_REPO_ADDRESS}")
    [[ -n "${VEEAM_URL:-}" ]] && CONFIG_ARGS+=(--veeam-url "${VEEAM_URL}")
  else
    echo "=== veeam_config.sh on KVM (reads /etc/ablestack/veeam/mold-backup.env) ==="
  fi
  echo "=== veeam_config.sh on KVM ==="
  kvm_ssh "$KVM_HOST" "cd /etc/ablestack/veeam && ./veeam_config.sh $(printf '%q ' "${CONFIG_ARGS[@]}")"
fi

echo ""
echo "=== Done. On KVM run: ==="
echo "  /etc/ablestack/veeam/mold-backup.sh backup-full --job \"${JOB_NAME:-Mold KVM Backup}\""
echo "  /etc/ablestack/veeam/mold-backup.sh status --job \"${JOB_NAME:-Mold KVM Backup}\""
