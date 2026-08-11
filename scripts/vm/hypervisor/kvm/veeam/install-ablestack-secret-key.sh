#!/usr/bin/bash
# Deploy /root/.ssh/ablestack.key for mold-backup-secret.sh (idempotent).
# Safe to run from /etc/ablestack/veeam when full install.sh is not present.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEY_FILE="${ABLESTACK_SECRET_KEY_FILE:-/root/.ssh/ablestack.key}"

src=""
for candidate in \
  "${ABLESTACK_KEY_DEFAULT_SRC:-}" \
  "${SCRIPT_DIR}/ablestack.key.default" \
  "/usr/share/mold/backup/veeam/ablestack.key.default" \
  "/tmp/veeam-install/ablestack.key.default"; do
  [[ -n "$candidate" && -f "$candidate" ]] || continue
  src="$candidate"
  break
done

install -d -m 0700 "$(dirname "$KEY_FILE")"
if [[ -f "$KEY_FILE" ]]; then
  chmod 0600 "$KEY_FILE"
  echo "Keeping existing ${KEY_FILE} ($(wc -c < "$KEY_FILE") bytes)"
  exit 0
fi
if [[ -z "$src" ]]; then
  echo "WARN: ablestack.key.default not found — create ${KEY_FILE} manually" >&2
  echo "  printf 'QWJsZWNsb3VkMSE=' > ${KEY_FILE} && chmod 600 ${KEY_FILE}" >&2
  exit 1
fi
install -m 0600 "$src" "$KEY_FILE"
echo "Installed ${KEY_FILE} from ${src} ($(wc -c < "$KEY_FILE") bytes)"
