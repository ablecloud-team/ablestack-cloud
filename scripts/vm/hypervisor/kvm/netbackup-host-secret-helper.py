#!/usr/bin/env python3

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

import argparse
import os
import sys
from pathlib import Path

from ablestack_backup_secret import MAX_SECRET_BYTES, decrypt_secret as decrypt_host_secret, write_secret
from ablestack_backup_secret_key import SecretKeyError


SECRET_KEY_FILE = Path(os.environ.get("SECRET_KEY_FILE", os.environ.get("ABLESTACK_SECRET_KEY_FILE", "/root/.ssh/ablestack.key")))


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def encrypt_secret(output_file: Path, plaintext: bytes) -> None:
    write_secret(output_file, plaintext, SECRET_KEY_FILE)


def decrypt_secret(secret_file: Path) -> bytes:
    return decrypt_host_secret(secret_file, SECRET_KEY_FILE, "netbackup")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Encrypt/decrypt NetBackup secret files")
    subparsers = parser.add_subparsers(dest="action", required=True)

    encrypt_parser = subparsers.add_parser("encrypt")
    encrypt_parser.add_argument("secret_file")

    decrypt_parser = subparsers.add_parser("decrypt")
    decrypt_parser.add_argument("secret_file")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    secret_file = Path(args.secret_file)
    if args.action == "encrypt":
        plaintext = sys.stdin.buffer.read(MAX_SECRET_BYTES + 1)
        if not plaintext:
            fail("No secret data provided on stdin for encryption")
        encrypt_secret(secret_file, plaintext)
        return

    if not secret_file.is_file():
        fail(f"Secret file not found: {secret_file}")
    sys.stdout.buffer.write(decrypt_secret(secret_file))


if __name__ == "__main__":
    try:
        main()
    except (SecretKeyError, OSError) as exc:
        fail(str(exc))
