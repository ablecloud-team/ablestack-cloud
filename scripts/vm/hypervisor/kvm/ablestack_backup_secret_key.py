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

"""Shared Host key management; legacy decoding is confined to compatibility reads."""

import argparse
import base64
import binascii
import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import stat
import sys
import tempfile

DEFAULT_KEY_FILE = "/root/.ssh/ablestack.key"
KEY_FORMAT = "ablestack-backup-host-key"
KEY_VERSION = 1
MAX_KEY_BYTES = 4096


class SecretKeyError(ValueError):
    pass


def _check_owner(info, path):
    # Both integrations run as root on Cube Hosts. Tests may use a private directory as another user.
    if info.st_uid != os.geteuid():
        raise SecretKeyError(f"Secret key must be owned by the executing user: {path}")


def _open_regular(path, flags):
    fd = os.open(str(path), flags | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, 0o600)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            raise SecretKeyError(f"Secret key must be a regular file: {path}")
        _check_owner(info, path)
        if stat.S_IMODE(info.st_mode) != 0o600:
            raise SecretKeyError(f"Secret key file must have permission 600: {path}")
        return fd
    except BaseException:
        os.close(fd)
        raise


def _read_key(path):
    try:
        fd = _open_regular(path, os.O_RDONLY)
        with os.fdopen(fd, "rb") as source:
            raw = source.read(MAX_KEY_BYTES + 1)
    except OSError as exc:
        raise SecretKeyError(f"Unable to read secret key file: {path} ({exc.strerror})") from None
    if not raw.strip() or len(raw) > MAX_KEY_BYTES:
        raise SecretKeyError(f"Secret key is empty or exceeds the allowed size: {path}")
    return raw


def _unique_fields(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError("Duplicate key field")
        result[name] = value
    return result


def _versioned_material(raw, path):
    if raw.lstrip().startswith((b"{", b"[")):
        try:
            payload = json.loads(raw, object_pairs_hook=_unique_fields)
            if (not isinstance(payload, dict) or set(payload) != {"format", "version", "encoding", "key"}
                    or payload["format"] != KEY_FORMAT or type(payload["version"]) is not int
                    or payload["version"] != KEY_VERSION or payload["encoding"] != "hex"
                    or not isinstance(payload["key"], str)
                    or re.fullmatch(r"[0-9a-f]{64}", payload["key"]) is None):
                raise ValueError("Unsupported key format")
            # Explicit HEX format. New AES-GCM operations decode it in Python; Shell never handles binary keys.
            return payload["key"].encode("ascii")
        except (ValueError, TypeError, UnicodeError):
            raise SecretKeyError(f"Invalid or unsupported Host key format: {path}") from None
    return None


def read_aes_key(path):
    material = _versioned_material(_read_key(Path(path)), path)
    if material is None:
        raise SecretKeyError("New encryption requires a random versioned Host key. "
                             "Migrate/re-register both integrations before replacing a legacy key")
    return bytes.fromhex(material.decode("ascii"))


def read_key_material(path, legacy_provider):
    raw = _read_key(Path(path))
    material = _versioned_material(raw, path)
    if material is not None:
        return material

    if legacy_provider == "netbackup":
        return raw.strip()
    if legacy_provider != "veeam":
        raise SecretKeyError("A provider is required for an unversioned legacy key")

    # Preserve the old Veeam Bash/OpenSSL passphrase exactly, including command substitution semantics.
    legacy = raw.replace(b"\r", b"").replace(b"\n", b"").replace(b"\0", b"")
    try:
        decoded = base64.b64decode(legacy, validate=True).replace(b"\0", b"").rstrip(b"\n")
        if decoded:
            return decoded
    except (ValueError, binascii.Error):
        pass
    if not legacy:
        raise SecretKeyError(f"Legacy secret key is empty: {path}")
    return legacy


def _private_directory(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode):
        raise SecretKeyError(f"Secret directory must be a real directory: {path}")
    _check_owner(info, path)
    if stat.S_IMODE(info.st_mode) != 0o700:
        raise SecretKeyError(f"Secret directory must have permission 700: {path}")


def atomic_write_private(path, data, overwrite=True):
    """Publish complete owner-only files without truncating a previously usable credential."""
    path = Path(path)
    _private_directory(path.parent)
    fd, temporary = tempfile.mkstemp(prefix="." + path.name + "-", dir=str(path.parent))
    try:
        with os.fdopen(fd, "wb") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        if overwrite:
            os.replace(temporary, path)
        else:
            # link publishes atomically and refuses to replace an existing key, even outside our lock.
            os.link(temporary, path)
        directory_fd = os.open(str(path.parent), os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if os.path.lexists(temporary):
            os.unlink(temporary)


def _secret_roots():
    roots = {Path("/etc/ablestack/netbackup"), Path("/etc/ablestack/veeam"),
             Path("/etc/mold/backup/netbackup"), Path("/etc/mold/backup/veeam")}
    for name in ("CONFIG_OUTPUT_DIR", "ABLESTACK_VEEAM_ETC_DIR", "MOLD_BACKUP_ETC_DIR", "MOLD_BACKUP_SHARE_DIR"):
        if os.environ.get(name):
            roots.add(Path(os.environ[name]))
    return roots


def _existing_secret(secret_files=(), secret_directories=()):
    candidates = [Path(path) for path in secret_files]
    directories = [Path(path) for path in secret_directories]
    for name in ("MOLD_API_SECRET_ENC_FILE", "MOLD_SECRET_FILE"):
        if os.environ.get(name):
            candidates.append(Path(os.environ[name]))
    if os.environ.get("SECRET_OUTPUT_DIR"):
        directories.append(Path(os.environ["SECRET_OUTPUT_DIR"]))
    for root in _secret_roots():
        directories.append(root / "secrets")
        candidates.extend(root.glob("*.enc*"))
        # Read literal file references only. Never source Shell configuration during key generation.
        for config in list(root.glob("*.conf")) + list(root.glob("*.env")):
            for line in config.read_text(encoding="utf-8").splitlines():
                match = re.match(r"\s*(?:export\s+)?(?:MOLD_API_SECRET_ENC_FILE|MOLD_SECRET_FILE)\s*=\s*(.*)", line)
                if not match:
                    continue
                try:
                    values = shlex.split(match.group(1), comments=True)
                except ValueError:
                    raise SecretKeyError(f"Invalid encrypted secret reference in {config}; restore the original key") from None
                if len(values) > 1:
                    raise SecretKeyError(f"Ambiguous encrypted secret reference in {config}; restore the original key")
                value = values[0] if values else ""
                if not value:
                    continue
                if "$" in value or "`" in value or not Path(value).is_absolute():
                    raise SecretKeyError(f"Unable to verify encrypted secret reference in {config}; restore the original key")
                candidates.append(Path(value))
    for directory in directories:
        if directory.exists():
            candidates.extend(directory.rglob("*.enc*"))
    return next((path for path in candidates if os.path.lexists(path)), None)


def ensure_key(path, secret_files=(), secret_directories=(), require_new=False, upgrade_unused_legacy=False):
    """Use only during install/configuration. Runtime decrypt must never generate or replace keys."""
    path = Path(path)
    if not path.is_absolute():
        raise SecretKeyError("Secret key file path must be absolute")
    _private_directory(path.parent)
    lock_path = path.with_name(path.name + ".lock")
    lock_fd = _open_regular(lock_path, os.O_RDWR | os.O_CREAT)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
        replacing_unused_legacy = False
        if os.path.lexists(path):
            if require_new:
                raise SecretKeyError(f"Secret key already exists; refusing to replace it: {path}")
            raw = _read_key(path)
            material = _versioned_material(raw, path)
            if material is not None or not upgrade_unused_legacy:
                return False
            replacing_unused_legacy = True
        existing = _existing_secret(secret_files, tuple(secret_directories) + (path.parent,))
        if existing is not None:
            raise SecretKeyError(f"Cannot generate or replace Host key while an encrypted secret exists: {existing}. "
                                 "Restore the original key or explicitly re-register credentials for both integrations")
        payload = {"format": KEY_FORMAT, "version": KEY_VERSION, "encoding": "hex", "key": secrets.token_hex(32)}
        atomic_write_private(path, (json.dumps(payload) + "\n").encode("ascii"), overwrite=replacing_unused_legacy)
        return True
    finally:
        os.close(lock_fd)


def main():
    parser = argparse.ArgumentParser(description="Manage the shared NetBackup/Veeam Host key")
    parser.add_argument("action", choices=("ensure", "gen-key", "validate"))
    parser.add_argument("--key-file", default=os.environ.get("ABLESTACK_SECRET_KEY_FILE", DEFAULT_KEY_FILE))
    parser.add_argument("--existing-secret", action="append", default=[])
    parser.add_argument("--secret-dir", action="append", default=[])
    parser.add_argument("--upgrade-unused-legacy", action="store_true", help="Replace an unused unversioned key only if neither integration has encrypted credentials")
    args = parser.parse_args()
    try:
        if args.action == "validate":
            read_key_material(args.key_file, "netbackup")
            print(f"Validated Host key: {args.key_file}")
        else:
            created = ensure_key(args.key_file, args.existing_secret, args.secret_dir, args.action == "gen-key", args.upgrade_unused_legacy)
            print(f"{'Prepared random' if created else 'Keeping existing'} Host key: {args.key_file}")
    except (SecretKeyError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
