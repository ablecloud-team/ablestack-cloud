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

"""Shared AES-256-GCM credential envelope; old algorithms are available for decryption only."""

import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import subprocess

from ablestack_backup_secret_key import SecretKeyError, _unique_fields, atomic_write_private, read_aes_key, read_key_material

SECRET_FORMAT = "ablestack-backup-secret"
SECRET_VERSION = 2
MAX_SECRET_BYTES = 16384
MAX_ENVELOPE_BYTES = 65536
HEADER = {"format": SECRET_FORMAT, "version": SECRET_VERSION, "cipher": "AES-256-GCM", "purpose": "mold-api-secret"}


def _aes_gcm(key):
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        raise SecretKeyError("AES-256-GCM requires python3-cryptography; install the Agent package dependencies") from None
    return AESGCM(key)


def _aad(header):
    return json.dumps(header, sort_keys=True, separators=(",", ":")).encode("ascii")


def _base64(value):
    if not isinstance(value, str):
        raise ValueError("Invalid encoded value")
    return base64.b64decode(value, validate=True)


def encrypt_secret(plaintext, key_file):
    if not plaintext or len(plaintext) > MAX_SECRET_BYTES:
        raise SecretKeyError("API secret is empty or exceeds the allowed size")
    key = read_aes_key(key_file)
    header = dict(HEADER, keyId=hashlib.sha256(key).hexdigest())
    # 96-bit random nonce, 128-bit authentication tag (AESGCM does not truncate tags).
    nonce = secrets.token_bytes(12)
    ciphertext = _aes_gcm(key).encrypt(nonce, plaintext, _aad(header))
    payload = dict(header, nonce=base64.b64encode(nonce).decode("ascii"),
                   ciphertext=base64.b64encode(ciphertext).decode("ascii"))
    return (json.dumps(payload, sort_keys=True) + "\n").encode("ascii")


def write_secret(path, plaintext, key_file):
    if Path(path).resolve() == Path(key_file).resolve():
        raise SecretKeyError("Encrypted secret output must not replace the Host key")
    atomic_write_private(path, encrypt_secret(plaintext, key_file))


def _decrypt_common(payload, key_file):
    try:
        fields = set(HEADER) | {"keyId", "nonce", "ciphertext"}
        if (set(payload) != fields or type(payload.get("version")) is not int
                or any(payload.get(name) != value for name, value in HEADER.items())):
            raise ValueError("Unsupported envelope")
        key = read_aes_key(key_file)
        key_id = hashlib.sha256(key).hexdigest()
        if payload["keyId"] != key_id:
            raise ValueError("Wrong key")
        nonce = _base64(payload["nonce"])
        ciphertext = _base64(payload["ciphertext"])
        if len(nonce) != 12 or not 16 < len(ciphertext) <= MAX_SECRET_BYTES + 16:
            raise ValueError("Invalid nonce or ciphertext size")
        header = {name: payload[name] for name in set(HEADER) | {"keyId"}}
        from cryptography.exceptions import InvalidTag
        try:
            return _aes_gcm(key).decrypt(nonce, ciphertext, _aad(header))
        except InvalidTag:
            raise ValueError("Authentication failed") from None
    except ImportError:
        raise SecretKeyError("AES-256-GCM requires python3-cryptography; install the Agent package dependencies") from None
    except SecretKeyError:
        raise
    except (ValueError, TypeError, KeyError):
        # Never return partially decrypted data, and never fall back to a legacy cipher after authentication fails.
        raise SecretKeyError("Encrypted API secret is invalid, tampered with, or uses a different Host key") from None


def _decrypt_legacy_netbackup(payload, key_file):
    try:
        if type(payload.get("version")) is not int or payload["version"] != 1:
            raise ValueError("Unsupported legacy envelope")
        iterations = payload.get("iterations", 200000)
        if type(iterations) is not int or not 1 <= iterations <= 2000000:
            raise ValueError("Invalid iteration count")
        salt, nonce = _base64(payload["salt"]), _base64(payload["nonce"])
        ciphertext, mac = _base64(payload["ciphertext"]), _base64(payload["mac"])
        if len(salt) != 16 or len(nonce) != 16 or len(mac) != 32 or not 0 < len(ciphertext) <= MAX_SECRET_BYTES:
            raise ValueError("Invalid legacy envelope size")
        material = read_key_material(key_file, "netbackup")
        derived = hashlib.pbkdf2_hmac("sha256", material, salt, iterations, dklen=64)
        enc_key, mac_key = derived[:32], derived[32:]
        if not hmac.compare_digest(mac, hmac.new(mac_key, nonce + ciphertext, hashlib.sha256).digest()):
            raise ValueError("Legacy authentication failed")
        stream = b"".join(hmac.new(enc_key, nonce + counter.to_bytes(8, "big"), hashlib.sha256).digest()
                          for counter in range((len(ciphertext) + 31) // 32))
        return bytes(left ^ right for left, right in zip(ciphertext, stream))
    except (ValueError, TypeError, KeyError):
        raise SecretKeyError("Legacy NetBackup API secret is invalid or uses a different Host key") from None


def _decrypt_legacy_veeam(data, key_file):
    # Compatibility only: new files are never written with CBC or an inferred Base64 passphrase.
    material = read_key_material(key_file, "veeam")
    read_fd, write_fd = os.pipe()
    try:
        try:
            os.write(write_fd, material)
        finally:
            os.close(write_fd)
        result = subprocess.run(["openssl", "enc", "-aes-256-cbc", "-d", "-salt", "-pbkdf2", "-base64", "-pass", f"fd:{read_fd}"],
                                input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, pass_fds=(read_fd,))
        if result.returncode:
            raise SecretKeyError("Legacy Veeam API secret decryption failed (wrong key or corrupt file)")
        return result.stdout.replace(b"\r", b"").rstrip()
    finally:
        os.close(read_fd)


def decrypt_secret(path, key_file, legacy_provider):
    with Path(path).open("rb") as source:
        data = source.read(MAX_ENVELOPE_BYTES + 1)
    if not data or len(data) > MAX_ENVELOPE_BYTES:
        raise SecretKeyError("Encrypted API secret is empty or exceeds the allowed size")
    if data.lstrip().startswith((b"{", b"[")):
        try:
            payload = json.loads(data, object_pairs_hook=_unique_fields)
            if not isinstance(payload, dict):
                raise ValueError("Invalid envelope")
        except (ValueError, UnicodeError):
            raise SecretKeyError("Invalid encrypted API secret format") from None
        if payload.get("format") == SECRET_FORMAT or payload.get("version") == SECRET_VERSION:
            return _decrypt_common(payload, key_file)
        if legacy_provider == "netbackup":
            return _decrypt_legacy_netbackup(payload, key_file)
        raise SecretKeyError("Unsupported encrypted API secret format")
    if legacy_provider == "veeam":
        return _decrypt_legacy_veeam(data, key_file)
    raise SecretKeyError("Unsupported encrypted API secret format")
