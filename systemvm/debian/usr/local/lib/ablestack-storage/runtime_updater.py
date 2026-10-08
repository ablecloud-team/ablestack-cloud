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
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import time


RUNTIME_ABI_VERSION = "1"
DESIRED_STATE_SCHEMA_VERSION = "1"
SUPPORTED_IMPACTS = {"NONE"}
ENTRYPOINTS = (
    "ablestack-storagectl",
    "ablestack-storage-boot-reconcile",
    "ablestack-storage-monitor",
)
IDENTIFIER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
MAX_BUNDLE_BYTES = 64 * 1024 * 1024
MAX_EXPANDED_BYTES = 128 * 1024 * 1024
MAX_FILES = 256


class RuntimeUpgradeError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fsync_directory(path):
    fd = os.open(str(path), os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_json(path, value, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(value, handle, sort_keys=True, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def atomic_symlink(target, link):
    link.parent.mkdir(parents=True, exist_ok=True)
    temporary = link.parent / f".{link.name}.next"
    try:
        temporary.unlink(missing_ok=True)
        os.symlink(str(target), str(temporary))
        os.replace(str(temporary), str(link))
        fsync_directory(link.parent)
    finally:
        temporary.unlink(missing_ok=True)


def require_identifier(value, field):
    value = str(value or "")
    if not IDENTIFIER_RE.fullmatch(value):
        raise RuntimeUpgradeError("INVALID_REQUEST", f"invalid {field}")
    return value


def require_sha256(value, field):
    value = str(value or "").lower()
    if not SHA256_RE.fullmatch(value):
        raise RuntimeUpgradeError("INVALID_REQUEST", f"invalid {field}")
    return value


class RuntimeUpdater:
    def __init__(self):
        self.runtime_root = Path(os.environ.get(
            "ABLESTACK_STORAGE_RUNTIME_ROOT", "/opt/ablestack/storage-runtime"))
        self.state_root = Path(os.environ.get(
            "ABLESTACK_STORAGE_RUNTIME_STATE_ROOT", "/var/lib/ablestack-storage/runtime-updates"))
        self.trusted_keys = Path(os.environ.get(
            "ABLESTACK_STORAGE_RUNTIME_TRUSTED_KEYS", str(self.runtime_root / "trusted-keys")))
        self.entrypoint_root = Path(os.environ.get(
            "ABLESTACK_STORAGE_RUNTIME_ENTRYPOINT_ROOT", "/usr/local/bin"))
        self.lock_file = Path(os.environ.get(
            "ABLESTACK_STORAGE_RUNTIME_LOCK", "/run/lock/ablestack-storage-runtime-upgrade.lock"))
        self.template_manifest = Path(os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MANIFEST", "/etc/ablestack-storage/template-manifest.json"))
        self.releases = self.runtime_root / "releases"
        self.current = self.runtime_root / "current"
        self.previous = self.runtime_root / "previous"

    def transaction_dir(self, request):
        transaction_id = require_identifier(request.get("transactionId"), "transactionId")
        return self.state_root / transaction_id

    def state_path(self, request):
        return self.transaction_dir(request) / "state.json"

    def read_state(self, request, required=True):
        path = self.state_path(request)
        if not path.exists():
            if required:
                raise RuntimeUpgradeError("TRANSACTION_NOT_FOUND", "runtime upgrade transaction does not exist")
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise RuntimeUpgradeError("STATE_INVALID", f"unable to read transaction state: {error}") from error

    def write_state(self, request, state, phase, **updates):
        state = dict(state or {})
        state.update(updates)
        state["transactionId"] = require_identifier(request.get("transactionId"), "transactionId")
        state["phase"] = phase
        state["updatedAt"] = int(time.time())
        atomic_json(self.state_path(request), state)
        return state

    def current_version(self, link=None):
        link = link or self.current
        if not link.is_symlink():
            return None
        try:
            resolved = link.resolve(strict=True)
            if resolved.parent != self.releases.resolve():
                return None
            return resolved.name
        except OSError:
            return None

    def platform_attestation(self):
        unknown = {"platformVersionKnown": False, "platformVersion": None, "productVersion": None,
                   "templateManifestSha256": None, "platformVersionDiagnostic": "PLATFORM_VERSION_UNKNOWN"}
        try:
            path = self.template_manifest
            info = path.lstat(); parent = path.parent.lstat()
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022
                    or info.st_size > 2 * 1024 * 1024 or not stat.S_ISDIR(parent.st_mode)
                    or parent.st_uid != os.geteuid() or parent.st_mode & 0o022):
                return unknown
            directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                opened_parent = os.fstat(directory)
                fields = ("st_dev", "st_ino", "st_uid", "st_mode")
                if tuple(getattr(opened_parent, key) for key in fields) != tuple(getattr(parent, key) for key in fields):
                    return unknown
                descriptor = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | getattr(os, "O_NOATIME", 0), dir_fd=directory)
            finally:
                os.close(directory)
            try:
                opened = os.fstat(descriptor)
                fields = ("st_dev", "st_ino", "st_uid", "st_gid", "st_mode", "st_size", "st_mtime_ns", "st_ctime_ns")
                if tuple(getattr(opened, key) for key in fields) != tuple(getattr(info, key) for key in fields):
                    return unknown
                with os.fdopen(descriptor, "rb", closefd=False) as handle:
                    content = handle.read(2 * 1024 * 1024 + 1)
                after = os.fstat(descriptor)
                if len(content) > 2 * 1024 * 1024 or tuple(getattr(after, key) for key in fields) != tuple(getattr(opened, key) for key in fields):
                    return unknown
            finally:
                os.close(descriptor)
            manifest = json.loads(content)
            version = manifest.get("platformVersion")
            proof = manifest.get("platformVersionSource") or {}
            declared = proof.get("declaredVersion")
            matched = re.fullmatch(r"([0-9]+(?:\.[0-9]+){2,3})(?:-[A-Za-z][A-Za-z0-9_.-]*)?", declared) if isinstance(declared, str) else None
            normalized = matched.group(1) if matched else None
            if normalized and normalized.count(".") == 2:
                normalized += ".0"
            if (normalized != version or not isinstance(version, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+){3}", version)
                    or manifest.get("productVersion") != version
                    or (manifest.get("registrationDetails") or {}).get("storage.service.platform.version") != version
                    or proof.get("path") != "pom.xml" or not SHA256_RE.fullmatch(str(proof.get("sha256") or ""))
                    or (manifest.get("sourceFiles") or {}).get("pom.xml") != proof.get("sha256")):
                return unknown
            return {"platformVersionKnown": True, "platformVersion": version, "productVersion": version,
                    "templateManifestSha256": hashlib.sha256(content).hexdigest(), "platformVersionSource": proof}
        except (OSError, ValueError, TypeError, AttributeError):
            return unknown

    def capabilities(self, _request):
        return {
            "success": True,
            **self.platform_attestation(),
            "runtimeAbiVersion": RUNTIME_ABI_VERSION,
            "desiredStateSchemaVersion": DESIRED_STATE_SCHEMA_VERSION,
            "signedRuntimeReadback": True,
            "updaterSha256": sha256_file(Path(__file__)),
            "supportedServiceImpacts": sorted(SUPPORTED_IMPACTS),
            "currentVersion": self.current_version(),
            "previousVersion": self.current_version(self.previous),
            "entrypointsManaged": all(
                (self.entrypoint_root / name).is_symlink() for name in ENTRYPOINTS
            ),
            "commands": [
                "capabilities", "bootstrap", "begin", "status", "finalize", "verify",
                "preflight", "activate", "rollback", "cleanup", "readback",
            ],
        }

    def bootstrap(self, request):
        if self.current_version():
            return {"success": True, "phase": "BOOTSTRAPPED", "currentVersion": self.current_version()}
        sources = []
        for name in ENTRYPOINTS:
            source = self.entrypoint_root / name
            if not source.is_file() or source.is_symlink():
                raise RuntimeUpgradeError("BOOTSTRAP_UNAVAILABLE", f"legacy entrypoint is unavailable: {source}")
            sources.append(source)
        digest = hashlib.sha256()
        for source in sources:
            digest.update(source.name.encode("utf-8"))
            digest.update(bytes.fromhex(sha256_file(source)))
        version = request.get("bundleVersion") or f"bootstrap-{digest.hexdigest()[:16]}"
        version = require_identifier(version, "bundleVersion")
        release = self.releases / version
        release.mkdir(parents=True, exist_ok=False)
        for source in sources:
            destination = release / source.name
            shutil.copy2(source, destination)
            os.chmod(destination, stat.S_IMODE(source.stat().st_mode))
        atomic_json(release / "bootstrap.json", {
            "bundleVersion": version,
            "runtimeAbiVersion": RUNTIME_ABI_VERSION,
            "desiredStateSchemaVersion": DESIRED_STATE_SCHEMA_VERSION,
            "files": [{"path": p.name, "sha256": sha256_file(p)} for p in sources],
        }, mode=0o644)
        atomic_symlink(release, self.current)
        for name in ENTRYPOINTS:
            atomic_symlink(self.current / name, self.entrypoint_root / name)
        return {"success": True, "phase": "BOOTSTRAPPED", "currentVersion": version}

    def begin(self, request):
        bundle_version = require_identifier(request.get("bundleVersion"), "bundleVersion")
        archive_sha = require_sha256(request.get("archiveSha256"), "archiveSha256")
        manifest_sha = require_sha256(request.get("manifestSha256"), "manifestSha256")
        total_size = int(request.get("totalSize") or 0)
        manifest_size = int(request.get("manifestSize") or 0)
        signature_size = int(request.get("signatureSize") or 0)
        if total_size <= 0 or total_size > MAX_BUNDLE_BYTES:
            raise RuntimeUpgradeError("INVALID_REQUEST", "bundle size is outside the allowed range")
        if manifest_size <= 0 or manifest_size > 1024 * 1024 or signature_size <= 0 or signature_size > 16384:
            raise RuntimeUpgradeError("INVALID_REQUEST", "manifest or signature size is outside the allowed range")
        existing = self.read_state(request, required=False)
        expected = {
            "bundleVersion": bundle_version,
            "archiveSha256": archive_sha,
            "manifestSha256": manifest_sha,
            "totalSize": total_size,
            "manifestSize": manifest_size,
            "signatureSize": signature_size,
        }
        if existing:
            if any(existing.get(key) != value for key, value in expected.items()):
                raise RuntimeUpgradeError("TRANSACTION_CONFLICT", "transaction already exists with different bundle metadata")
            return {"success": True, **existing}
        transaction = self.transaction_dir(request)
        transaction.mkdir(parents=True, exist_ok=False)
        state = self.write_state(request, expected, "RECEIVING", receivedBytes=0)
        return {"success": True, **state}

    def status(self, request):
        state = self.read_state(request)
        transaction = self.transaction_dir(request)
        for field, filename in (("receivedBytes", "bundle.tar.gz"),
                                ("receivedManifestBytes", "manifest.json"),
                                ("receivedSignatureBytes", "manifest.sig")):
            path = transaction / filename
            state[field] = path.stat().st_size if path.exists() else 0
        return {"success": True, **state}

    def finalize(self, request):
        state = self.read_state(request)
        if state.get("phase") not in {"RECEIVING", "RECEIVED"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "transaction is not receiving files")
        transaction = self.transaction_dir(request)
        files = {
            "bundle.tar.gz": state["totalSize"],
            "manifest.json": state["manifestSize"],
            "manifest.sig": state["signatureSize"],
        }
        for name, expected_size in files.items():
            path = transaction / name
            if not path.is_file() or path.stat().st_size != expected_size:
                raise RuntimeUpgradeError("TRANSFER_INCOMPLETE", f"unexpected size for {name}")
        if sha256_file(transaction / "bundle.tar.gz") != state["archiveSha256"]:
            raise RuntimeUpgradeError("ARCHIVE_HASH_MISMATCH", "runtime bundle SHA-256 does not match")
        if sha256_file(transaction / "manifest.json") != state["manifestSha256"]:
            raise RuntimeUpgradeError("MANIFEST_HASH_MISMATCH", "runtime manifest SHA-256 does not match")
        state = self.write_state(request, state, "RECEIVED", receivedBytes=state["totalSize"])
        return {"success": True, **state}

    def load_manifest(self, transaction):
        try:
            manifest = json.loads((transaction / "manifest.json").read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise RuntimeUpgradeError("MANIFEST_INVALID", f"unable to parse runtime manifest: {error}") from error
        required = {
            "bundleVersion", "runtimeAbiVersion", "desiredStateSchemaVersion",
            "serviceImpact", "keyId", "files",
        }
        missing = required - set(manifest)
        if missing:
            raise RuntimeUpgradeError("MANIFEST_INVALID", f"manifest fields are missing: {sorted(missing)}")
        require_identifier(manifest["bundleVersion"], "bundleVersion")
        require_identifier(manifest["keyId"], "keyId")
        if not isinstance(manifest["files"], list) or not manifest["files"] or len(manifest["files"]) > MAX_FILES:
            raise RuntimeUpgradeError("MANIFEST_INVALID", "manifest file list is invalid")
        return manifest

    def verify_signature(self, transaction, manifest):
        key_path = self.trusted_keys / f"{manifest['keyId']}.pem"
        if not key_path.is_file():
            raise RuntimeUpgradeError("SIGNING_KEY_UNKNOWN", "runtime signing key is not trusted")
        command = [
            "openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(key_path),
            "-sigfile", str(transaction / "manifest.sig"), "-rawin", "-in",
            str(transaction / "manifest.json"),
        ]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if result.returncode != 0:
            raise RuntimeUpgradeError("SIGNATURE_INVALID", "runtime manifest signature verification failed")

    def validate_manifest_files(self, manifest):
        result = {}
        for entry in manifest["files"]:
            if not isinstance(entry, dict):
                raise RuntimeUpgradeError("MANIFEST_INVALID", "manifest file entry is invalid")
            raw_path = str(entry.get("path") or "")
            path = PurePosixPath(raw_path)
            if path.is_absolute() or ".." in path.parts or len(path.parts) != 1:
                raise RuntimeUpgradeError("MANIFEST_PATH_UNSAFE", f"unsafe runtime file path: {raw_path}")
            if raw_path not in ENTRYPOINTS or raw_path in result:
                raise RuntimeUpgradeError("MANIFEST_PATH_UNSAFE", f"runtime file is not allowed: {raw_path}")
            mode = int(str(entry.get("mode") or "0"), 8)
            if mode & ~0o755 or not mode & 0o100:
                raise RuntimeUpgradeError("MANIFEST_MODE_UNSAFE", f"runtime file mode is invalid: {raw_path}")
            result[raw_path] = {
                "sha256": require_sha256(entry.get("sha256"), f"files[{raw_path}].sha256"),
                "mode": mode,
            }
        if set(result) != set(ENTRYPOINTS):
            raise RuntimeUpgradeError("MANIFEST_INVALID", "runtime bundle must contain all managed entrypoints")
        return result

    def extract_release(self, transaction, manifest, declared_files):
        version = manifest["bundleVersion"]
        release = self.releases / version
        if release.exists():
            self.verify_release(manifest, release)
            return release
        self.releases.mkdir(parents=True, exist_ok=True)
        temporary = Path(tempfile.mkdtemp(prefix=f".{version}.", dir=str(self.releases)))
        seen = set()
        expanded = 0
        try:
            with tarfile.open(transaction / "bundle.tar.gz", "r:gz") as archive:
                members = archive.getmembers()
                if len(members) > MAX_FILES + 16:
                    raise RuntimeUpgradeError("ARCHIVE_UNSAFE", "runtime archive contains too many entries")
                for member in members:
                    path = PurePosixPath(member.name)
                    if path.is_absolute() or ".." in path.parts:
                        raise RuntimeUpgradeError("ARCHIVE_UNSAFE", f"unsafe archive path: {member.name}")
                    if member.isdir():
                        continue
                    if not member.isfile() or len(path.parts) != 1 or member.name not in declared_files:
                        raise RuntimeUpgradeError("ARCHIVE_UNSAFE", f"unexpected archive entry: {member.name}")
                    if member.name in seen:
                        raise RuntimeUpgradeError("ARCHIVE_UNSAFE", f"duplicate archive entry: {member.name}")
                    expanded += member.size
                    if expanded > MAX_EXPANDED_BYTES:
                        raise RuntimeUpgradeError("ARCHIVE_UNSAFE", "runtime archive expands beyond the allowed size")
                    source = archive.extractfile(member)
                    if source is None:
                        raise RuntimeUpgradeError("ARCHIVE_INVALID", f"unable to read archive entry: {member.name}")
                    destination = temporary / member.name
                    with source, open(destination, "wb") as target:
                        shutil.copyfileobj(source, target)
                    os.chmod(destination, declared_files[member.name]["mode"])
                    seen.add(member.name)
            if seen != set(declared_files):
                raise RuntimeUpgradeError("ARCHIVE_INVALID", "runtime archive does not match manifest files")
            atomic_json(temporary / "manifest.json", manifest, mode=0o644)
            self.verify_release(manifest, temporary)
            os.replace(str(temporary), str(release))
            fsync_directory(self.releases)
            return release
        finally:
            if temporary.exists():
                shutil.rmtree(temporary, ignore_errors=True)

    def verify_release(self, manifest, release):
        declared = self.validate_manifest_files(manifest)
        for name, metadata in declared.items():
            path = release / name
            if not path.is_file() or path.is_symlink():
                raise RuntimeUpgradeError("RELEASE_INVALID", f"runtime release file is missing: {name}")
            if sha256_file(path) != metadata["sha256"]:
                raise RuntimeUpgradeError("RELEASE_HASH_MISMATCH", f"runtime release file hash differs: {name}")
            if stat.S_IMODE(path.stat().st_mode) != metadata["mode"]:
                raise RuntimeUpgradeError("RELEASE_MODE_MISMATCH", f"runtime release file mode differs: {name}")
            first_line = path.read_bytes().splitlines()[:1]
            if first_line and first_line[0].startswith(b"#!"):
                interpreter = first_line[0][2:].decode("utf-8", errors="strict").split()[0]
                if interpreter.endswith("bash") or interpreter.endswith("sh"):
                    result = subprocess.run([interpreter, "-n", str(path)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    if result.returncode != 0:
                        raise RuntimeUpgradeError("RELEASE_SELF_TEST_FAILED", f"shell syntax check failed: {name}")
                elif interpreter.endswith("python3"):
                    try:
                        compile(path.read_bytes(), str(path), "exec")
                    except (SyntaxError, ValueError) as invalid:
                        raise RuntimeUpgradeError("RELEASE_SELF_TEST_FAILED", f"python syntax check failed: {name}") from invalid

    def verify(self, request):
        state = self.read_state(request)
        if state.get("phase") not in {"RECEIVED", "VERIFIED", "PREFLIGHT_OK"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "runtime bundle has not been received")
        transaction = self.transaction_dir(request)
        manifest = self.load_manifest(transaction)
        if manifest["bundleVersion"] != state["bundleVersion"]:
            raise RuntimeUpgradeError("MANIFEST_INVALID", "manifest bundle version differs from transaction")
        self.verify_signature(transaction, manifest)
        declared = self.validate_manifest_files(manifest)
        release = self.extract_release(transaction, manifest, declared)
        state = self.write_state(request, state, "VERIFIED", releasePath=str(release), keyId=manifest["keyId"])
        return {"success": True, **state}

    def preflight(self, request):
        state = self.read_state(request)
        if state.get("phase") not in {"VERIFIED", "PREFLIGHT_OK"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "runtime bundle has not been verified")
        manifest = self.load_manifest(self.transaction_dir(request))
        if str(manifest["runtimeAbiVersion"]) != RUNTIME_ABI_VERSION:
            raise RuntimeUpgradeError("RUNTIME_ABI_INCOMPATIBLE", "runtime ABI is not supported")
        if str(manifest["desiredStateSchemaVersion"]) != DESIRED_STATE_SCHEMA_VERSION:
            raise RuntimeUpgradeError("DESIRED_STATE_SCHEMA_INCOMPATIBLE", "desired-state schema is not supported")
        if manifest["serviceImpact"] not in SUPPORTED_IMPACTS:
            raise RuntimeUpgradeError("SERVICE_IMPACT_UNSUPPORTED", "runtime bundle requires template maintenance")
        release = self.releases / manifest["bundleVersion"]
        self.verify_release(manifest, release)
        available = shutil.disk_usage(self.runtime_root).free
        required = int(state["totalSize"]) * 3
        if available < required:
            raise RuntimeUpgradeError("INSUFFICIENT_SPACE", "insufficient free space for runtime activation and rollback")
        if not self.current_version():
            raise RuntimeUpgradeError("BOOTSTRAP_REQUIRED", "runtime entrypoints have not been bootstrapped")
        state = self.write_state(request, state, "PREFLIGHT_OK", previousVersion=self.current_version(),
                                 availableBytes=available, requiredBytes=required)
        return {"success": True, **state}

    def activate(self, request):
        state = self.read_state(request)
        phase = state.get("phase")
        if phase not in {"PREFLIGHT_OK", "ACTIVATING", "COMPLETE"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "runtime bundle did not pass preflight")
        target_version = state["bundleVersion"]
        target = self.releases / target_version
        manifest = self.pinned_manifest(state)
        observed = self.current_version()
        if phase == "COMPLETE":
            self.signed_runtime_readback(state, state)
            return {"success": True, **state}
        if phase == "ACTIVATING":
            previous_version = require_identifier(state.get("previousVersion"), "previousVersion")
            if observed not in {target_version, previous_version}:
                raise RuntimeUpgradeError("ACTIVATION_RESUME_STATE_MISMATCH", "active runtime changed during activation")
            if observed == target_version:
                self.signed_runtime_readback(state, state)
                if self.current_version(self.previous) != previous_version:
                    raise RuntimeUpgradeError("ACTIVATION_RESUME_STATE_MISMATCH", "previous runtime changed during activation")
                state = self.write_state(request, state, "COMPLETE", previousVersion=previous_version,
                                         currentVersion=target_version, activatedAt=int(time.time()))
                return {"success": True, **state}
        else:
            previous_version = observed
        if not previous_version:
            raise RuntimeUpgradeError("BOOTSTRAP_REQUIRED", "runtime entrypoints have not been bootstrapped")
        previous_target = self.releases / previous_version
        if not previous_target.is_dir():
            raise RuntimeUpgradeError("ACTIVATION_RESUME_STATE_MISMATCH", "previous runtime release is unavailable")
        self.write_state(request, state, "ACTIVATING", previousVersion=previous_version)
        try:
            self.verify_release(manifest, target)
            atomic_symlink(previous_target, self.previous)
            atomic_symlink(target, self.current)
            self.signed_runtime_readback(state, state)
        except Exception as error:
            atomic_symlink(previous_target, self.current)
            rolled_back = self.write_state(
                request, state, "ROLLED_BACK", previousVersion=previous_version,
                errorCode=getattr(error, "code", "ACTIVATION_FAILED"), errorMessage=str(error),
            )
            raise RuntimeUpgradeError("ACTIVATION_FAILED_ROLLED_BACK", json.dumps(rolled_back, sort_keys=True)) from error
        state = self.write_state(request, state, "COMPLETE", previousVersion=previous_version,
                                 currentVersion=target_version, activatedAt=int(time.time()))
        return {"success": True, **state}

    def pinned_manifest(self, state):
        transaction = self.transaction_dir(state)
        if sha256_file(transaction / "manifest.json") != state["manifestSha256"]:
            raise RuntimeUpgradeError("MANIFEST_HASH_MISMATCH", "staged manifest hash differs from its pinned source")
        if sha256_file(transaction / "bundle.tar.gz") != state["archiveSha256"]:
            raise RuntimeUpgradeError("ARCHIVE_HASH_MISMATCH", "staged bundle hash differs from its pinned source")
        manifest = self.load_manifest(transaction)
        if manifest["bundleVersion"] != state["bundleVersion"]:
            raise RuntimeUpgradeError("MANIFEST_INVALID", "signed runtime manifest version differs from its pinned source")
        self.verify_signature(transaction, manifest)
        return manifest

    def readback(self, request):
        state = self.read_state(request)
        if state.get("phase") not in {"VERIFIED", "PREFLIGHT_OK", "COMPLETE"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "signed runtime readback requires a verified transaction")
        return self.signed_runtime_readback(request, state)

    def signed_runtime_readback(self, request, state):
        expected = {
            "bundleVersion": require_identifier(request.get("bundleVersion"), "bundleVersion"),
            "archiveSha256": require_sha256(request.get("archiveSha256"), "archiveSha256"),
            "manifestSha256": require_sha256(request.get("manifestSha256"), "manifestSha256"),
        }
        if any(state.get(key) != value for key, value in expected.items()):
            raise RuntimeUpgradeError("RUNTIME_READBACK_SCOPE_MISMATCH", "signed runtime readback transaction scope changed")
        manifest = self.pinned_manifest(state)
        if self.current_version() != expected["bundleVersion"]:
            raise RuntimeUpgradeError("SIGNED_RUNTIME_NOT_INSTALLED", "current runtime is not the pinned signed release")
        release = self.releases / expected["bundleVersion"]
        self.verify_release(manifest, release)
        for name in ENTRYPOINTS:
            entrypoint = self.entrypoint_root / name
            expected_link = self.current / name
            try:
                literal = Path(os.path.abspath(entrypoint.parent / os.readlink(entrypoint)))
                bound = entrypoint.resolve(strict=True)
                required = (release / name).resolve(strict=True)
            except (OSError, RuntimeError) as failure:
                raise RuntimeUpgradeError("RUNTIME_ENTRYPOINT_BINDING_INVALID", "a managed runtime entrypoint is not bound") from failure
            if not entrypoint.is_symlink() or literal != expected_link or bound != required:
                raise RuntimeUpgradeError("RUNTIME_ENTRYPOINT_BINDING_INVALID", "a managed runtime entrypoint differs from the active release")
        return {"success": True, "signedRuntimeVerified": True, "installedFilesVerified": True,
                "entrypointsVerified": True, "currentVersion": expected["bundleVersion"],
                "archiveSha256": expected["archiveSha256"], "manifestSha256": expected["manifestSha256"], "updaterSha256": sha256_file(Path(__file__))}

    def rollback(self, request):
        state = self.read_state(request)
        previous_version = state.get("previousVersion") or self.current_version(self.previous)
        previous_version = require_identifier(previous_version, "previousVersion")
        previous_target = self.releases / previous_version
        if not previous_target.is_dir():
            raise RuntimeUpgradeError("ROLLBACK_UNAVAILABLE", "previous runtime release is unavailable")
        current_version = self.current_version()
        atomic_symlink(previous_target, self.current)
        if current_version and (self.releases / current_version).is_dir():
            atomic_symlink(self.releases / current_version, self.previous)
        state = self.write_state(request, state, "ROLLED_BACK", currentVersion=previous_version,
                                 rolledBackAt=int(time.time()))
        return {"success": True, **state}

    def cleanup(self, request):
        state = self.read_state(request)
        if state.get("phase") not in {"COMPLETE", "ROLLED_BACK", "FAILED"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "active runtime transaction cannot be cleaned")
        transaction = self.transaction_dir(request)
        for name in ("bundle.tar.gz", "manifest.sig"):
            (transaction / name).unlink(missing_ok=True)
        return {"success": True, "transactionId": state["transactionId"], "phase": state["phase"]}

    def execute(self, operation, request):
        handlers = {
            "capabilities": self.capabilities,
            "bootstrap": self.bootstrap,
            "begin": self.begin,
            "status": self.status,
            "finalize": self.finalize,
            "verify": self.verify,
            "preflight": self.preflight,
            "activate": self.activate,
            "rollback": self.rollback,
            "readback": self.readback,
            "cleanup": self.cleanup,
        }
        if operation not in handlers:
            raise RuntimeUpgradeError("OPERATION_UNSUPPORTED", "unsupported runtime updater operation")
        self.lock_file.parent.mkdir(parents=True, exist_ok=True)
        with open(self.lock_file, "a+", encoding="utf-8") as lock:
            try:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise RuntimeUpgradeError("UPGRADE_LOCKED", "another runtime upgrade operation is active") from error
            return handlers[operation](request)


def load_request(path):
    if path:
        data = Path(path).read_text(encoding="utf-8")
    elif not sys.stdin.isatty():
        data = sys.stdin.read()
    else:
        data = "{}"
    if not data.strip():
        return {}
    value = json.loads(data)
    if not isinstance(value, dict):
        raise RuntimeUpgradeError("INVALID_REQUEST", "request must be a JSON object")
    return value


def main():
    parser = argparse.ArgumentParser(description="ABLESTACK Storage Service runtime updater")
    parser.add_argument("operation")
    parser.add_argument("--request", help="path to a JSON request file")
    args = parser.parse_args()
    try:
        result = RuntimeUpdater().execute(args.operation, load_request(args.request))
        print(json.dumps(result, sort_keys=True))
        return 0
    except RuntimeUpgradeError as error:
        print(json.dumps({"success": False, "errorCode": error.code, "message": str(error)}, sort_keys=True))
        return 1
    except Exception as error:
        print(json.dumps({"success": False, "errorCode": "INTERNAL_ERROR", "message": str(error)}, sort_keys=True))
        return 1


if __name__ == "__main__":
    sys.exit(main())
