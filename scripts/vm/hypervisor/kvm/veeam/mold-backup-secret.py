#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Veeam CLI for shared AES-256-GCM credentials and read-only legacy compatibility."""

import argparse
import os
from pathlib import Path
import sys

try:
    from ablestack_backup_secret import MAX_SECRET_BYTES, decrypt_secret, write_secret
    from ablestack_backup_secret_key import SecretKeyError, ensure_key
except ModuleNotFoundError:
    # Repository/RPM layout. install.sh places the same module beside this helper for standalone installs.
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    from ablestack_backup_secret import MAX_SECRET_BYTES, decrypt_secret, write_secret
    from ablestack_backup_secret_key import SecretKeyError, ensure_key


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    default_key = os.environ.get("ABLESTACK_SECRET_KEY_FILE", "/root/.ssh/ablestack.key")
    encrypt = commands.add_parser("encrypt")
    encrypt.add_argument("--key-file", default=default_key)
    source = encrypt.add_mutually_exclusive_group(required=True)
    source.add_argument("--secret")  # Existing callers remain supported; configuration uses stdin.
    source.add_argument("--secret-stdin", action="store_true")
    encrypt.add_argument("--out")
    decrypt = commands.add_parser("decrypt")
    decrypt.add_argument("--key-file", default=default_key)
    decrypt.add_argument("--enc-file", required=True)
    for name in ("ensure-key", "gen-key"):
        key = commands.add_parser(name)
        key.add_argument("--key-file", default=default_key)
        key.add_argument("--existing-secret", action="append", default=[])
        key.add_argument("--secret-dir", action="append", default=[])
        key.add_argument("--upgrade-unused-legacy", action="store_true")
    args = parser.parse_args()
    try:
        if args.action in ("ensure-key", "gen-key"):
            created = ensure_key(args.key_file, args.existing_secret, args.secret_dir,
                                 args.action == "gen-key", args.upgrade_unused_legacy)
            print(f"{'Prepared random' if created else 'Keeping existing'} Host key: {args.key_file}")
        elif args.action == "encrypt":
            plaintext = sys.stdin.buffer.read(MAX_SECRET_BYTES + 1) if args.secret_stdin else args.secret.encode("utf-8")
            if not plaintext:
                raise SecretKeyError("API secret is empty")
            output = Path(args.out) if args.out else Path(args.key_file).parent / "api-secret.enc"
            write_secret(output, plaintext, args.key_file)
            print(output)
        else:
            sys.stdout.buffer.write(decrypt_secret(args.enc_file, args.key_file, "veeam"))
    except (SecretKeyError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
