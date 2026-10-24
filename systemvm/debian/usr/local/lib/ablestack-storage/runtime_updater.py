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
import ast
import contextlib
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
UPDATER_VERSION = 2
MANAGED_FILES = {'ablestack-storagectl': 493, 'ablestack-storage-boot-reconcile': 493, 'ablestack-storage-monitor': 493, 'lib/ablestack-storage/volume_identity.py': 420, 'lib/ablestack-storage/volume_preparation.py': 420, 'lib/ablestack-storage/session_auth.py': 420, 'lib/ablestack-storage/configuration_transaction.py': 420, 'lib/ablestack-storage/configuration_preflight.py': 420, 'lib/ablestack-storage/nvmeof_identity.py': 420}
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
        self.helper_root = Path(os.environ.get("ABLESTACK_STORAGE_RUNTIME_HELPER_ROOT", "/usr/local/lib/ablestack-storage"))
        self.confirmed = self.runtime_root / "confirmed.json"
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
        state["workerPid"] = os.getpid()
        state["workerStartTicks"] = Path("/proc/self/stat").read_text().rsplit(")", 1)[1].split()[19]
        state["workerBootId"] = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
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

    def capabilities(self, _request):
        return {
            "success": True,
            "updaterVersion": UPDATER_VERSION,
            "updaterSha256": sha256_file(Path(__file__)),
            "payloadLayout": 2,
            "activationConfirmation": True,
            "integrity": self.observe_integrity(),
            "runtimeAbiVersion": RUNTIME_ABI_VERSION,
            "desiredStateSchemaVersion": DESIRED_STATE_SCHEMA_VERSION,
            "supportedServiceImpacts": sorted(SUPPORTED_IMPACTS),
            "currentVersion": self.current_version(),
            "previousVersion": self.current_version(self.previous),
            "entrypointsManaged": all(
                (self.entrypoint_root / name).is_symlink() for name in ENTRYPOINTS
            ),
            "commands": [
                "capabilities", "bootstrap", "begin", "status", "finalize", "verify",
                "preflight", "activate", "rollback", "cleanup",
            ],
        }

    def bootstrap(self, request):
        if self.current_version():
            # Only boot recovery may roll back an unconfirmed native switch automatically.
            if not request.get("transactionId") and self.state_root.exists():
                for status in sorted(self.state_root.glob("*/state.json")):
                    state = json.loads(status.read_text())
                    if state.get("phase") == "ROLLING_BACK":
                        self.rollback({"transactionId":state["transactionId"]})
                    if state.get("phase") == "ACTIVATING" and self.current_version() == state.get("previousVersion"):
                        self.write_state({"transactionId":state["transactionId"]},state,"ROLLED_BACK",currentVersion=self.current_version())
                    if state.get("phase") in {"ACTIVATING", "ACTIVATED"} and self.current_version() == state.get("bundleVersion"):
                        confirmed = json.loads(self.confirmed.read_text()) if self.confirmed.exists() else {}
                        if confirmed.get("transactionId") == state["transactionId"] and confirmed.get("version") == state["bundleVersion"]:
                            self.write_state({"transactionId": state["transactionId"]}, state, "COMPLETE")
                        else:
                            self.rollback({"transactionId": state["transactionId"], "restoreConfiguration": True})
            release = self.releases / self.current_version()
            if not (release / "integrity.json").exists() and request.get("baselineProfile"):
                expected = request["baselineProfile"]["files"]
                if {x["path"] for x in expected} != set(MANAGED_FILES):
                    raise RuntimeUpgradeError("BASELINE_UNVERIFIED", "profile is incomplete")
                for item in expected:
                    name = item["path"]
                    path = release / name if name in ENTRYPOINTS else self.helper_root / Path(name).name
                    if not path.is_file() or path.is_symlink() or sha256_file(path) != item["sha256"] or stat.S_IMODE(path.stat().st_mode) != item["mode"]:
                        raise RuntimeUpgradeError("BASELINE_UNVERIFIED", "profile differs: " + name)
                self.record_integrity(release, legacy=True)
                atomic_json(self.confirmed, {"version":self.current_version(), "origin":"verified-baseline"})
            if (release / "integrity.json").exists():
                self.verify_baseline(self.current_version())
            return {"success": True, "phase": "BOOTSTRAPPED", "currentVersion": self.current_version(),
                    "integrity": self.observe_integrity()}
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
        default = Path(os.environ.get("ABLESTACK_STORAGE_RUNTIME_DEFAULT", "/etc/ablestack-storage/runtime-default"))
        template_manifest = None
        if (default / "manifest.json").is_file():
            template_manifest = self.load_manifest(default)
            original_trust = self.trusted_keys
            try:
                self.trusted_keys = Path(os.environ.get("ABLESTACK_STORAGE_RUNTIME_DEFAULT_TRUST", "/etc/ablestack-storage/runtime-trusted-keys"))
                self.verify_signature(default, template_manifest)
            finally:
                self.trusted_keys = original_trust
            if str(template_manifest["runtimeAbiVersion"])!=RUNTIME_ABI_VERSION or str(template_manifest["desiredStateSchemaVersion"])!=DESIRED_STATE_SCHEMA_VERSION:
                raise RuntimeUpgradeError("TEMPLATE_RUNTIME_INCOMPATIBLE", "template runtime ABI/schema differs")
        version = request.get("bundleVersion") or (template_manifest or {}).get("bundleVersion") or f"bootstrap-{digest.hexdigest()[:16]}"
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
        for name in MANAGED_FILES:
            if name in ENTRYPOINTS:
                continue
            source = self.helper_root / Path(name).name
            if not source.is_file() or source.is_symlink():
                raise RuntimeUpgradeError("BOOTSTRAP_UNAVAILABLE", "runtime helper is unavailable")
            destination = release / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            os.chmod(destination, MANAGED_FILES[name])
        if template_manifest:
            self.verify_release(template_manifest, release)
            atomic_json(release / "manifest.json", template_manifest, mode=0o644)
        self.record_integrity(release, origin="template-baseline")
        atomic_symlink(release, self.current)
        atomic_json(self.confirmed, {"version":version,"origin":"template-baseline"})
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
        state["currentVersion"] = self.current_version()
        proof=self.releases / (self.current_version() or "missing") / "integrity.json"
        state["currentOrigin"] = json.loads(proof.read_text()).get("origin") if proof.exists() else None
        try:
            observed = Path("/proc/%d/stat" % int(state.get("workerPid",0))).read_text().rsplit(")",1)[1].split()[19]
            state["workerActive"] = observed==state.get("workerStartTicks") and Path("/proc/sys/kernel/random/boot_id").read_text().strip()==state.get("workerBootId")
        except (OSError,ValueError,IndexError):
            state["workerActive"] = False
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
        if manifest.get("payloadLayout") != 2 or manifest.get("manifestFormat") != 2:
            raise RuntimeUpgradeError("PAYLOAD_LAYOUT_UNSUPPORTED", "runtime payload requires layout 2")
        result = {}
        for entry in manifest["files"]:
            if not isinstance(entry, dict):
                raise RuntimeUpgradeError("MANIFEST_INVALID", "manifest file entry is invalid")
            name = str(entry.get("path") or "")
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or str(path) != name or name not in MANAGED_FILES or name in result:
                raise RuntimeUpgradeError("MANIFEST_PATH_UNSAFE", "runtime file is not allowed: " + name)
            mode = int(str(entry.get("mode") or "0"), 8)
            if mode != MANAGED_FILES[name] or entry.get("owner") != "root" or entry.get("group") != "root":
                raise RuntimeUpgradeError("MANIFEST_MODE_UNSAFE", "runtime owner or mode differs: " + name)
            size = entry.get("bytes")
            if not isinstance(size, int) or isinstance(size, bool) or size <= 0 or size > MAX_EXPANDED_BYTES:
                raise RuntimeUpgradeError("MANIFEST_INVALID", "invalid runtime file size")
            result[name] = {"sha256": require_sha256(entry.get("sha256"), "file.sha256"), "mode": mode, "bytes": size}
        if set(result) != set(MANAGED_FILES) or sum(x["bytes"] for x in result.values()) > MAX_EXPANDED_BYTES:
            raise RuntimeUpgradeError("MANIFEST_INVALID", "runtime bundle must contain the complete managed payload")
        return result

    def checked_manifest(self, request):
        state = self.read_state(request)
        transaction = self.transaction_dir(request)
        if sha256_file(transaction / "manifest.json") != state["manifestSha256"]:
            raise RuntimeUpgradeError("MANIFEST_HASH_MISMATCH", "manifest changed after transfer")
        if sha256_file(transaction / "bundle.tar.gz") != state["archiveSha256"]:
            raise RuntimeUpgradeError("ARCHIVE_HASH_MISMATCH", "archive changed after transfer")
        manifest = self.load_manifest(transaction)
        if manifest["bundleVersion"] != state["bundleVersion"]:
            raise RuntimeUpgradeError("MANIFEST_INVALID", "bundle version differs")
        self.verify_signature(transaction, manifest)
        self.validate_manifest_files(manifest)
        for field, expected, code in (
                ("runtimeAbiVersion", RUNTIME_ABI_VERSION, "RUNTIME_ABI_INCOMPATIBLE"),
                ("desiredStateSchemaVersion", DESIRED_STATE_SCHEMA_VERSION, "DESIRED_STATE_SCHEMA_INCOMPATIBLE")):
            if str(manifest[field]) != expected:
                raise RuntimeUpgradeError(code, "unsupported " + field)
        if manifest["serviceImpact"] not in SUPPORTED_IMPACTS:
            raise RuntimeUpgradeError("SERVICE_IMPACT_UNSUPPORTED", "template maintenance is required")
        for field in ("keyId", "runtimeAbiVersion", "desiredStateSchemaVersion", "serviceImpact"):
            if request.get(field) is not None and str(request[field]) != str(manifest[field]):
                raise RuntimeUpgradeError("REGISTERED_METADATA_MISMATCH", "registered " + field + " differs")
        return manifest

    def observe_integrity(self):
        version = self.current_version()
        if not version:
            return {"verified": False, "reason": "BOOTSTRAP_REQUIRED", "files": []}
        try:
            files = self.verify_baseline(version)
            return {"verified": True, "files": files}
        except (RuntimeUpgradeError, OSError, ValueError) as error:
            return {"verified": False, "reason": getattr(error, "code", "BASELINE_UNVERIFIED"), "files": []}

    def verify_baseline(self, version):
        release = self.releases / require_identifier(version, "version")
        proof = release / "integrity.json"
        if not proof.is_file():
            raise RuntimeUpgradeError("BASELINE_UNVERIFIED", "known runtime baseline must be verified")
        metadata = json.loads(proof.read_text())
        files = []
        for item in metadata["files"]:
            name = item["path"]
            if name not in MANAGED_FILES:
                raise RuntimeUpgradeError("BASELINE_INVALID", "unknown baseline file")
            path = (self.helper_root / Path(name).name) if item.get("external") else release / name
            if path.is_symlink() or not path.is_file() or sha256_file(path) != item["sha256"] or stat.S_IMODE(path.stat().st_mode) != item["mode"]:
                raise RuntimeUpgradeError("PREVIOUS_RUNTIME_INTEGRITY_MISMATCH", "runtime file differs: " + name)
            if path.stat().st_uid != 0 or path.stat().st_gid != 0:
                raise RuntimeUpgradeError("RELEASE_OWNER_MISMATCH", "runtime owner differs")
            files.append({"path":name,"sha256":item["sha256"],"mode":format(item["mode"], "04o")})
        if {x["path"] for x in files} != set(MANAGED_FILES):
            raise RuntimeUpgradeError("BASELINE_INVALID", "incomplete baseline")
        return files

    def record_integrity(self, release, legacy=False, origin="signed-bundle"):
        files = []
        for name, mode in MANAGED_FILES.items():
            external = legacy and name not in ENTRYPOINTS
            path = self.helper_root / Path(name).name if external else release / name
            files.append({"path": name, "sha256": sha256_file(path), "mode": mode, "external":external})
        atomic_json(release / "integrity.json", {"origin": "verified-baseline" if legacy else origin, "files": files})

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
                        if member.name.rstrip("/") not in {"lib", "lib/ablestack-storage"}:
                            raise RuntimeUpgradeError("ARCHIVE_UNSAFE", "unexpected archive directory")
                        continue
                    if not member.isfile() or member.name not in declared_files:
                        raise RuntimeUpgradeError("ARCHIVE_UNSAFE", f"unexpected archive entry: {member.name}")
                    if member.name in seen:
                        raise RuntimeUpgradeError("ARCHIVE_UNSAFE", f"duplicate archive entry: {member.name}")
                    if member.size != declared_files[member.name]["bytes"] or member.uid != 0 or member.gid != 0 or (member.mode & 0o7777) != declared_files[member.name]["mode"]:
                        raise RuntimeUpgradeError("ARCHIVE_UNSAFE", "archive metadata differs from manifest")
                    expanded += member.size
                    if expanded > MAX_EXPANDED_BYTES:
                        raise RuntimeUpgradeError("ARCHIVE_UNSAFE", "runtime archive expands beyond the allowed size")
                    source = archive.extractfile(member)
                    if source is None:
                        raise RuntimeUpgradeError("ARCHIVE_INVALID", f"unable to read archive entry: {member.name}")
                    destination = temporary / member.name
                    destination.parent.mkdir(parents=True, exist_ok=True)
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
            if any((release / Path(*Path(name).parts[:i])).is_symlink() for i in range(1, len(Path(name).parts)+1)) or not path.is_file():
                raise RuntimeUpgradeError("RELEASE_INVALID", f"runtime release file is missing: {name}")
            if sha256_file(path) != metadata["sha256"]:
                raise RuntimeUpgradeError("RELEASE_HASH_MISMATCH", f"runtime release file hash differs: {name}")
            if path.stat().st_uid != 0 or path.stat().st_gid != 0:
                raise RuntimeUpgradeError("RELEASE_OWNER_MISMATCH", "runtime owner differs: " + name)
            if path.stat().st_size != metadata["bytes"]:
                raise RuntimeUpgradeError("RELEASE_SIZE_MISMATCH", "runtime size differs: " + name)
            if stat.S_IMODE(path.stat().st_mode) != metadata["mode"]:
                raise RuntimeUpgradeError("RELEASE_MODE_MISMATCH", f"runtime release file mode differs: {name}")
            if name.endswith(".py"):
                try:
                    compile(path.read_bytes(), str(path), "exec")
                except SyntaxError as error:
                    raise RuntimeUpgradeError("RELEASE_SELF_TEST_FAILED", "python syntax failed: " + name) from error
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
                    except SyntaxError as error:
                        raise RuntimeUpgradeError("RELEASE_SELF_TEST_FAILED", "python syntax check failed: " + name) from error

    def verify(self, request):
        state = self.read_state(request)
        if state.get("phase") not in {"RECEIVED", "VERIFIED", "PREFLIGHT_OK"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "runtime bundle has not been received")
        transaction = self.transaction_dir(request)
        manifest = self.checked_manifest(request)
        declared = self.validate_manifest_files(manifest)
        self.check_space(state, declared)
        release = self.extract_release(transaction, manifest, declared)
        state = self.write_state(request, state, "VERIFIED", releasePath=str(release), keyId=manifest["keyId"])
        return {"success": True, **state}

    def check_space(self, state, declared):
        required = int(state["totalSize"]) + sum(x["bytes"] for x in declared.values()) * 2 + 16 * 1024 * 1024
        available = shutil.disk_usage(self.runtime_root).free
        if available < required:
            raise RuntimeUpgradeError("INSUFFICIENT_SPACE", "insufficient space for payload, rollback and diagnostics")
        return available, required

    def preflight(self, request):
        state = self.read_state(request)
        if state.get("phase") not in {"VERIFIED", "PREFLIGHT_OK"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "bundle has not been verified")
        manifest = self.checked_manifest(request)
        if manifest["bundleVersion"] == self.current_version():
            raise RuntimeUpgradeError("RUNTIME_ALREADY_CURRENT", "runtime version is already current; previous pointer is preserved")
        self.verify_release(manifest, self.releases / manifest["bundleVersion"])
        self.verify_baseline(self.current_version())
        available, required = self.check_space(state, self.validate_manifest_files(manifest))
        state = self.write_state(request, state, "PREFLIGHT_OK", previousVersion=self.current_version(),
                                 availableBytes=available, requiredBytes=required, verifiedFiles=manifest["files"])
        return {"success": True, **state}

    def configuration_snapshot(self, request):
        if self.runtime_root != Path("/opt/ablestack/storage-runtime"):
            return
        sys.path.insert(0, str(self.helper_root))
        from configuration_transaction import RuntimeAdapter
        commands = {}
        for name, path in {
                "nfs export apply": "/etc/ablestack-storage/desired-state/nfs-export-apply.json",
                "smb share apply": "/etc/ablestack-storage/desired-state/smb-share-apply.json",
                "iscsi target apply": "/etc/ablestack-storage/iscsi-targets.json",
                "nvmeof subsystem apply": "/etc/ablestack-storage/nvmeof-subsystems.json"}.items():
            if Path(path).is_file():
                commands[name] = json.loads(Path(path).read_text())
        private = self.transaction_dir(request) / "configuration-before"
        private.mkdir(mode=0o700, exist_ok=False)
        payload = {"instanceUuid": require_identifier(request.get("instanceUuid"), "instanceUuid"),
                   "candidate": commands, "before": commands}
        adapter = RuntimeAdapter(private)
        adapter.preflight(payload)
        adapter.snapshot(payload)
        # A fresh health observation; cached health cannot confirm a new runtime.
        result = subprocess.run([str(self.current / "ablestack-storagectl"), "health"],
            env={**os.environ,"ABLESTACK_STORAGECTL_CACHE":"0"}, capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise RuntimeUpgradeError("BASELINE_HEALTH_UNAVAILABLE", "baseline health could not be observed")
        health = json.loads(result.stdout)
        atomic_json(private / "health.json", {"listenPorts":health.get("listenPorts",{})})

    def verify_configuration(self, request):
        if self.runtime_root != Path("/opt/ablestack/storage-runtime"):
            return
        import base64
        private = self.transaction_dir(request) / "configuration-before"
        before = json.loads((private / "before.json").read_text())
        for name, metadata in before["files"].items():
            path = Path(name)
            if not path.is_file() or path.is_symlink() or path.read_bytes()!=base64.b64decode(metadata["content"]):
                raise RuntimeUpgradeError("CONFIGURATION_CHANGED", "owned configuration differs after activation")
        result = subprocess.run([str(self.current / "ablestack-storagectl"),"health"],
            env={**os.environ,"ABLESTACK_STORAGECTL_CACHE":"0"}, capture_output=True,text=True,timeout=60)
        if result.returncode:
            raise RuntimeUpgradeError("RUNTIME_HEALTH_FAILED","fresh runtime health failed")
        expected = json.loads((private / "health.json").read_text())["listenPorts"]
        actual = json.loads(result.stdout).get("listenPorts",{})
        if expected != actual:
            raise RuntimeUpgradeError("PROTOCOL_HEALTH_CHANGED","protocol listener health differs from baseline")

    def refresh_monitor(self):
        # Only the runtime-owned collector is refreshed; storage protocol daemons stay running.
        if self.runtime_root != Path("/opt/ablestack/storage-runtime"):
            return
        active = subprocess.run(["systemctl","is-active","--quiet","ablestack-storage-monitor.service"],capture_output=True)
        if active.returncode == 0:
            result = subprocess.run(["systemctl","restart","ablestack-storage-monitor.service"],capture_output=True,timeout=30)
            if result.returncode:
                raise RuntimeUpgradeError("MONITOR_RESTART_FAILED","runtime collector could not load the selected release")

    def activate(self, request):
        state = self.read_state(request)
        if request.get("confirmActivation"):
            if state.get("phase") not in {"ACTIVATED", "COMPLETE"} or self.current_version() != state["bundleVersion"]:
                raise RuntimeUpgradeError("CONFIRMATION_CONFLICT", "activation is not the current transaction")
            self.verify_release(self.checked_manifest(request), self.releases / state["bundleVersion"])
            self.verify_baseline(state["bundleVersion"])
            self.verify_configuration(request)
            atomic_json(self.confirmed, {"transactionId": state["transactionId"], "version": state["bundleVersion"]})
            return {"success": True, **self.write_state(request, state, "COMPLETE", confirmedAt=int(time.time()))}
        if state.get("phase") == "ACTIVATED" and self.current_version() == state["bundleVersion"]:
            return {"success": True, **state}
        if state.get("phase") not in {"PREFLIGHT_OK", "COMPLETE"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "runtime bundle did not pass preflight")
        target_version = state["bundleVersion"]
        if self.current_version() == target_version and state.get("phase") == "COMPLETE":
            return {"success": True, **state}
        manifest = self.checked_manifest(request)
        target = self.releases / target_version
        self.verify_release(manifest, target)
        self.verify_baseline(state["previousVersion"])
        if self.current_version() != state["previousVersion"]:
            raise RuntimeUpgradeError("BASELINE_CHANGED", "current runtime differs from preflight baseline")
        self.record_integrity(target)
        previous_version = self.current_version()
        if not previous_version:
            raise RuntimeUpgradeError("BOOTSTRAP_REQUIRED", "runtime entrypoints have not been bootstrapped")
        previous_target = self.releases / previous_version
        self.write_state(request, state, "ACTIVATING", previousVersion=previous_version)
        try:
            self.configuration_snapshot(request)
        except Exception as error:
            self.write_state(request,state,"REJECTED",previousVersion=previous_version,currentVersion=previous_version,
                             errorCode=getattr(error,"code","BASELINE_SNAPSHOT_FAILED"))
            raise
        atomic_symlink(previous_target, self.previous)
        atomic_symlink(target, self.current)
        try:
            self.verify_release(manifest, self.current.resolve(strict=True))
            for name in ENTRYPOINTS:
                managed = self.entrypoint_root / name
                if not managed.is_symlink() or managed.resolve(strict=True) != (target / name).resolve(strict=True):
                    raise RuntimeUpgradeError("ENTRYPOINT_NOT_MANAGED", f"runtime entrypoint is not managed: {name}")
            self.refresh_monitor()
        except Exception as error:
            atomic_symlink(previous_target, self.current)
            self.refresh_monitor()
            rolled_back = self.write_state(
                request, state, "ROLLED_BACK", previousVersion=previous_version,
                errorCode=getattr(error, "code", "ACTIVATION_FAILED"), errorMessage=str(error),
            )
            raise RuntimeUpgradeError("ACTIVATION_FAILED_ROLLED_BACK", json.dumps(rolled_back, sort_keys=True)) from error
        state = self.write_state(request, state, "ACTIVATED", previousVersion=previous_version,
                                 currentVersion=target_version, activatedAt=int(time.time()))
        return {"success": True, **state}

    def rollback(self, request):
        state = self.read_state(request)
        if state.get("phase") == "ROLLED_BACK" and self.current_version() == state.get("currentVersion"):
            return {"success": True, **state}
        resuming = state.get("phase") == "ROLLING_BACK" and self.current_version() == state.get("previousVersion")
        if not resuming and (self.current_version() != state["bundleVersion"] or state.get("phase") not in {"COMPLETE", "ACTIVATED", "ACTIVATING", "ROLLING_BACK"}):
            raise RuntimeUpgradeError("ROLLBACK_STALE_OPERATION", "transaction does not own the current runtime")
        previous_version = state.get("previousVersion")
        previous_version = require_identifier(previous_version, "previousVersion")
        previous_target = self.releases / previous_version
        if not previous_target.is_dir():
            raise RuntimeUpgradeError("ROLLBACK_UNAVAILABLE", "previous runtime release is unavailable")
        self.verify_baseline(previous_version)
        current_version = self.current_version()
        restore = bool(request.get("restoreConfiguration") or state.get("restoreConfiguration"))
        state = self.write_state(request,state,"ROLLING_BACK",restoreConfiguration=restore)
        atomic_symlink(previous_target, self.current)
        if current_version != previous_version and current_version and (self.releases / current_version).is_dir():
            atomic_symlink(self.releases / current_version, self.previous)
        self.refresh_monitor()
        if restore and self.runtime_root == Path("/opt/ablestack/storage-runtime"):
            sys.path.insert(0,str(self.helper_root))
            from configuration_transaction import RuntimeAdapter
            RuntimeAdapter(self.transaction_dir(request) / "configuration-before").restore()
            self.verify_configuration(request)
        atomic_json(self.confirmed, {"transactionId": state["transactionId"], "version": previous_version})
        state = self.write_state(request, state, "ROLLED_BACK", currentVersion=previous_version,
                                 currentOrigin=json.loads((previous_target / "integrity.json").read_text()).get("origin"),
                                 rolledBackAt=int(time.time()))
        return {"success": True, **state}

    def cleanup(self, request):
        state = self.read_state(request)
        if state.get("phase") not in {"COMPLETE", "ROLLED_BACK", "FAILED"}:
            raise RuntimeUpgradeError("INVALID_PHASE", "active runtime transaction cannot be cleaned")
        transaction = self.transaction_dir(request)
        for name in ("receive.part",):
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
            "cleanup": self.cleanup,
        }
        if operation not in handlers:
            raise RuntimeUpgradeError("OPERATION_UNSUPPORTED", "unsupported runtime updater operation")
        if operation in {"status", "capabilities"}:
            return handlers[operation](request)
        self.lock_file.parent.mkdir(parents=True, exist_ok=True)
        with open(self.lock_file, "a+", encoding="utf-8") as lock:
            try:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise RuntimeUpgradeError("UPGRADE_LOCKED", "another runtime upgrade operation is active") from error
            with contextlib.ExitStack() as locks:
                if operation in {"activate", "rollback"} and self.runtime_root == Path("/opt/ablestack/storage-runtime"):
                    paths = [Path("/run/lock/ablestack-storage-boot-reconcile.lock")]
                    if request.get("instanceUuid"):
                        instance = require_identifier(request["instanceUuid"], "instanceUuid")
                        paths.append(Path("/var/lib/ablestack-storage/configuration-operations") / instance / "lock")
                    paths.extend(Path("/var/lib/ablestack-storage").glob("**/*.job.lock"))
                    for path in paths:
                        path.parent.mkdir(parents=True, exist_ok=True)
                        handle = locks.enter_context(path.open("a"))
                        try:
                            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        except BlockingIOError as error:
                            raise RuntimeUpgradeError("CONFIGURATION_WRITER_ACTIVE", "configuration or preparation writer is active") from error
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
