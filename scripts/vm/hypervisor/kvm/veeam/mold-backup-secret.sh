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

# Encrypt/decrypt Mold API secret for mold-backup hooks (AES-256-CBC + PBKDF2).
# Default host key: /root/.ssh/ablestack.key (base64-encoded passphrase, chmod 600).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPENSSL_CIPHER="aes-256-cbc"
DEFAULT_KEY_FILE="${ABLESTACK_SECRET_KEY_FILE:-/root/.ssh/ablestack.key}"

usage() {
  cat <<EOF
Usage:
  mold-backup-secret.sh encrypt --secret <plain> --key-file <path> [--out <enc-file>]
  mold-backup-secret.sh decrypt --enc-file <path> --key-file <path>
  mold-backup-secret.sh gen-key --key-file <path>

Files:
  --key-file   Passphrase file (chmod 600). Default on hosts: ${DEFAULT_KEY_FILE}
               Ablestack installs a base64-encoded passphrase in this file; the script
               decodes base64 when valid, otherwise uses the file contents as-is.
  --enc-file   Encrypted secret blob (base64 OpenSSL output).
  --out        Output path for encrypt (default: same dir as key-file, api-secret.enc)
EOF
}

die() { echo "ERROR: $*" >&2; exit 1; }

require_openssl() {
  command -v openssl >/dev/null 2>&1 || die "openssl is required"
}

# Read passphrase from key file (base64 one-liner or raw text).
mold_secret_read_passphrase() {
  local key_file="$1"
  local raw decoded
  raw="$(tr -d '\n\r' < "$key_file")"
  [[ -n "$raw" ]] || die "Key file is empty: $key_file"
  if decoded="$(printf '%s' "$raw" | base64 -d 2>/dev/null)" && [[ -n "$decoded" ]]; then
    printf '%s' "$decoded"
    return 0
  fi
  printf '%s' "$raw"
}

mold_secret_openssl_passfile() {
  local pass="$1"
  local tmp
  tmp="$(mktemp)"
  chmod 600 "$tmp"
  printf '%s' "$pass" > "$tmp"
  echo "$tmp"
}

mold_secret_encrypt() {
  local secret="" key_file="" out_file=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --secret) secret="$2"; shift 2 ;;
      --key-file) key_file="$2"; shift 2 ;;
      --out) out_file="$2"; shift 2 ;;
      *) die "Unknown option: $1" ;;
    esac
  done
  [[ -n "$secret" ]] || die "--secret is required"
  [[ -n "$key_file" ]] || die "--key-file is required"
  [[ -f "$key_file" ]] || die "Key file not found: $key_file"
  if [[ -z "$out_file" ]]; then
    out_file="$(dirname "$key_file")/api-secret.enc"
  fi
  require_openssl
  install -d -m 0700 "$(dirname "$out_file")"
  local pass pass_file
  pass="$(mold_secret_read_passphrase "$key_file")"
  pass_file="$(mold_secret_openssl_passfile "$pass")"
  echo -n "$secret" | openssl enc -"${OPENSSL_CIPHER}" -salt -pbkdf2 -pass "file:${pass_file}" -base64 -out "$out_file"
  rm -f "$pass_file"
  chmod 0600 "$out_file"
  echo "$out_file"
}

mold_secret_decrypt() {
  local enc_file="" key_file=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --enc-file) enc_file="$2"; shift 2 ;;
      --key-file) key_file="$2"; shift 2 ;;
      *) die "Unknown option: $1" ;;
    esac
  done
  [[ -f "$enc_file" ]] || die "Encrypted file not found: $enc_file"
  [[ -f "$key_file" ]] || die "Key file not found: $key_file"
  require_openssl
  local pass pass_file
  pass="$(mold_secret_read_passphrase "$key_file")"
  pass_file="$(mold_secret_openssl_passfile "$pass")"
  local plain
  plain="$(openssl enc -"${OPENSSL_CIPHER}" -d -salt -pbkdf2 -pass "file:${pass_file}" -base64 -in "$enc_file" 2>/dev/null)" \
    || die "Decrypt failed (wrong key file or corrupt enc file): $enc_file"
  rm -f "$pass_file"
  # Strip trailing newline — OpenSSL may add one; breaks CloudStack API HMAC.
  plain="${plain//$'\r'/}"
  plain="${plain%"${plain##*[![:space:]]}"}"
  printf '%s' "$plain"
}

mold_secret_gen_key() {
  local key_file=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --key-file) key_file="$2"; shift 2 ;;
      *) die "Unknown option: $1" ;;
    esac
  done
  [[ -n "$key_file" ]] || die "--key-file is required"
  require_openssl
  install -d -m 0700 "$(dirname "$key_file")"
  if [[ -f "$key_file" ]]; then
    die "Key file already exists: $key_file (refuse to overwrite)"
  fi
  openssl rand -base64 32 > "$key_file"
  chmod 0600 "$key_file"
  echo "Created key file: $key_file"
}

cmd="${1:-}"
shift || true
case "$cmd" in
  encrypt) mold_secret_encrypt "$@" ;;
  decrypt) mold_secret_decrypt "$@" ;;
  gen-key) mold_secret_gen_key "$@" ;;
  -h|--help|help|"") usage ;;
  *) die "Unknown command: $cmd (use --help)" ;;
esac
