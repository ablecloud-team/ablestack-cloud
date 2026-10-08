# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Immutable rendered configuration publication and verified ordered replay.

Validators and runtime callbacks are fixed native adapters, never RPC-provided
code. Filesystem publication is atomic; kernel/configfs changes are ordered and
reversible and cannot be represented as an atomic kernel transaction.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import time
import uuid

DOMAINS = ("NFS", "SMB", "ISCSI", "NVMEOF")
REQUIRED = {"nfs/manifest.json", "smb/smb.conf", "smb/manifest.json", "block/iscsi-plan.json", "block/nvmeof-plan.json", "desired-state.json", "network-bindings.json", "posix-plan.json", "prerequisites.json", "file-volumes.json"}
DYNAMIC = re.compile(r"(?:nfs/ganesha/[0-9a-f]{24}\.conf|nfs/exports/[0-9a-f-]{36}\.exports|smb/listeners/[0-9a-f]{24}\.json)")
DESIRED_PATHS = {"desired-state/nfs-export-apply.json", "desired-state/smb-share-apply.json", "iscsi-targets.json", "nvmeof-subsystems.json", "posix-directory-policies.json", "network-endpoints.json", "sharedfs-network.json"}
MAX_FILE = 8 * 1024 * 1024
MAX_TOTAL = 32 * 1024 * 1024


def rendered_directory(path, create=False):
    if create:
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise ValueError("Rendered generation directory is not protected")
    return info


def rendered_read(path):
    info = path.lstat(); rendered_directory(path.parent)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600 or info.st_size > MAX_FILE:
        raise ValueError("Rendered generation file is not protected")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(descriptor)
        fields = ("st_dev", "st_ino", "st_uid", "st_gid", "st_mode", "st_size", "st_mtime_ns", "st_ctime_ns")
        if tuple(getattr(opened, key) for key in fields) != tuple(getattr(info, key) for key in fields):
            raise ValueError("Rendered generation file changed while opening")
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            value = handle.read(MAX_FILE + 1)
        after = os.fstat(descriptor)
        if len(value) > MAX_FILE or tuple(getattr(after, key) for key in fields) != tuple(getattr(opened, key) for key in fields):
            raise ValueError("Rendered generation file changed while reading")
        return value
    finally:
        os.close(descriptor)


def rendered_fsync(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def rendered_write(path, value):
    rendered_directory(path.parent, True)
    descriptor, temporary = tempfile.mkstemp(prefix=".rendered-", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(value); handle.flush(); os.fsync(handle.fileno())
        os.replace(temporary, path); rendered_fsync(path.parent)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def rendered_json(path, value):
    rendered_write(path, json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode())


def no_rendered_secrets(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if any(token in key.lower() for token in ("password", "secret", "dhchapkey", "dhchapctrlkey", "privatekey", "keytab", "capsule")):
                raise ValueError("Rendered generation accepts credential references, not credential material")
            no_rendered_secrets(item)
    elif isinstance(value, list):
        for item in value: no_rendered_secrets(item)


def rendered_domain(name):
    if name.startswith("nfs/"): return "NFS"
    if name.startswith("smb/"): return "SMB"
    if name == "block/iscsi-plan.json": return "ISCSI"
    if name == "block/nvmeof-plan.json": return "NVMEOF"
    return None


class RenderedGeneration:
    def __init__(self, root=None):
        self.root = Path(root or os.environ.get("ABLESTACK_STORAGE_RENDERED_GENERATIONS", "/var/lib/ablestack-storage/rendered-generations"))
        self.generations = self.root / "generations"
        self.current = self.root / "current"
        self.journal = self.root / "activation.json"

    def scope(self, request):
        scope = {key: str(uuid.UUID(request[key])) for key in ("instanceUuid", "operationUuid")}
        revision = request["revision"]
        if isinstance(revision, bool) or not isinstance(revision, int) or revision < 0:
            raise ValueError("Rendered generation revision is invalid")
        return {**scope, "revision": revision}

    def pointer(self):
        if not self.current.exists() and not self.current.is_symlink(): return None
        if not self.current.is_symlink(): raise ValueError("Rendered current pointer is not a managed symlink")
        target = os.readlink(self.current)
        if not re.fullmatch(r"generations/[0-9a-f-]{36}", target):
            raise ValueError("Rendered current pointer escapes its generation root")
        path = self.root / target
        rendered_directory(path)
        return path

    def inspect(self, path):
        if path is None: return None
        manifest = json.loads(rendered_read(path / "generation.json"))
        names = set(manifest["files"])
        if not REQUIRED <= names or any(name not in REQUIRED and not DYNAMIC.fullmatch(name) for name in names):
            raise ValueError("Rendered generation contains an unexpected path")
        self.scope(manifest["scope"])
        actual_names = set()
        device = rendered_directory(path).st_dev
        for directory, children, files in os.walk(path, followlinks=False):
            directory = Path(directory)
            if rendered_directory(directory).st_dev != device:
                raise ValueError("Rendered generation crosses a mount boundary")
            for child in children:
                if (directory / child).is_symlink(): raise ValueError("Rendered generation contains a symlink directory")
            actual_names.update((directory / name).relative_to(path).as_posix() for name in files)
        if actual_names != names | {"generation.json"}:
            raise ValueError("Rendered generation contains an undeclared file")
        for name, expected in manifest["files"].items():
            if hashlib.sha256(rendered_read(path / name)).hexdigest() != expected:
                raise ValueError("Rendered generation file hash changed")
        checksum = hashlib.sha256(json.dumps({key: value for key, value in manifest.items() if key != "manifestSha256"}, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        if manifest.get("manifestSha256") != checksum:
            raise ValueError("Rendered generation manifest hash changed")
        return manifest

    def read_journal(self):
        if not self.journal.exists(): return None
        return json.loads(rendered_read(self.journal))

    def status(self):
        manifest = self.inspect(self.pointer())
        journal = self.read_journal()
        return {"success": True, "renderedGenerationSupported": True, "renderedGenerationSchemaVersion": 1,
                "fullFourProtocolActivationSupported": False, "supportedFeatures": ["RENDERED_CONFIG_GENERATION_HANDLER"],
                "current": manifest, "activation": journal,
                "bootHeld": bool(journal and journal["phase"] not in ("COMPLETE", "ROLLED_BACK"))}

    def stage(self, request, files, validator):
        scope = self.scope(request)
        rendered_directory(self.root, True); rendered_directory(self.generations, True)
        current = self.inspect(self.pointer())
        journal = self.read_journal()
        if journal and journal["phase"] not in ("COMPLETE", "ROLLED_BACK") and journal["scope"] != scope:
            raise ValueError("An interrupted rendered writer must be recovered before staging")
        expected = request.get("expectedCurrentRenderedSha256")
        final = self.generations / scope["operationUuid"]
        replayed = final.exists()
        if not replayed and expected != (current or {}).get("manifestSha256"):
            raise ValueError("Rendered source pointer changed before stage")
        if not replayed and current and (current["scope"]["instanceUuid"] != scope["instanceUuid"] or current["scope"]["revision"] >= scope["revision"]):
            raise ValueError("Rendered source revision is stale or foreign")
        names = set(files)
        if not REQUIRED <= names or len(names) > 4096 or any(name not in REQUIRED and not DYNAMIC.fullmatch(name) for name in names):
            raise ValueError("Rendered stage contains an unexpected path")
        normalized = {}
        for name, value in files.items():
            if not isinstance(value, (str, bytes)): raise ValueError("Rendered file content must be bytes or UTF-8 text")
            value = value.encode() if isinstance(value, str) else value
            if len(value) > MAX_FILE: raise ValueError("Rendered stage file exceeds its size limit")
            if name.endswith(".json"): no_rendered_secrets(json.loads(value))
            elif re.search(rb"(?i)(?:password|secret|dhchap[_ ]?(?:ctrl[_ ]?)?key|private[_ ]?key)\s*=", value):
                raise ValueError("Rendered daemon config contains credential material")
            normalized[name] = value
        if sum(len(value) for value in normalized.values()) > MAX_TOTAL:
            raise ValueError("Rendered stage exceeds its size limit")
        desired = json.loads(normalized["desired-state.json"])
        if not isinstance(desired, dict) or set(desired) != DESIRED_PATHS or any(value is not None and not isinstance(value, dict) for value in desired.values()):
            raise ValueError("Rendered desired state must have the exact seven canonical source files")
        configuration_sha = hashlib.sha256(json.dumps(desired, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()
        manifest = {"schemaVersion": 1, "scope": scope, "previousRenderedSha256": expected, "configurationSha256": configuration_sha,
                    "files": {name: hashlib.sha256(value).hexdigest() for name, value in sorted(normalized.items())}}
        manifest["domainSha256"] = {domain: hashlib.sha256(json.dumps({name: checksum for name, checksum in manifest["files"].items() if rendered_domain(name) == domain}, sort_keys=True).encode()).hexdigest() for domain in DOMAINS}
        manifest["manifestSha256"] = hashlib.sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        final = self.generations / scope["operationUuid"]
        if final.exists():
            if self.inspect(final) != manifest: raise ValueError("Rendered operation was replayed with another payload")
            return manifest
        temporary = Path(tempfile.mkdtemp(prefix=".stage-", dir=self.root))
        try:
            temporary.chmod(0o700)
            for name, value in normalized.items(): rendered_write(temporary / name, value)
            # The fixed native validator must validate all four protocols. It
            # runs before any public pointer or runtime side effect changes.
            validation = validator(temporary, manifest)
            if not isinstance(validation, dict) or set(validation) != set(DOMAINS) or any(validation[domain] is not True for domain in DOMAINS):
                raise ValueError("All four rendered protocols were not validated")
            rendered_json(temporary / "generation.json", manifest)
            if temporary.stat().st_dev != self.generations.stat().st_dev:
                raise ValueError("Rendered generation publication crosses filesystems")
            os.rename(temporary, final); rendered_fsync(self.generations); rendered_fsync(self.root)
            return manifest
        finally:
            if temporary.exists(): shutil.rmtree(temporary)

    def publish_pointer(self, target):
        temporary = self.root / (".current-" + str(uuid.uuid4()))
        try:
            os.symlink("generations/" + target.name, temporary)
            os.replace(temporary, self.current); rendered_fsync(self.root)
        finally:
            temporary.unlink(missing_ok=True)

    def scoped_activation(self, request):
        journal = self.read_journal()
        if not journal or journal["scope"] != self.scope(request):
            raise ValueError("Rendered activation scope changed")
        return journal

    def activate(self, request, replay, verify, persist_desired=None, prerequisites=None):
        scope = self.scope(request); target = self.generations / scope["operationUuid"]
        manifest = self.inspect(target); journal = self.read_journal()
        if request.get("renderedManifestSha256", manifest["manifestSha256"]) != manifest["manifestSha256"]:
            raise ValueError("Rendered target manifest pin changed")
        current_path = self.pointer(); current = self.inspect(current_path)
        if journal and journal["scope"] == scope and journal["phase"] in ("VERIFIED", "COMPLETE"):
            if current != manifest or journal["targetSha256"] != manifest["manifestSha256"]:
                raise ValueError("Verified rendered pointer changed")
            observed = verify(target)
            if set(observed) != set(DOMAINS) or not all(observed[domain] is True for domain in DOMAINS):
                raise ValueError("Verified rendered replay lost runtime agreement")
            return self.status()
        if journal and journal["phase"] not in ("COMPLETE", "ROLLED_BACK"):
            journal = self.scoped_activation(request)
            if journal["targetSha256"] != manifest["manifestSha256"] or (current or {}).get("manifestSha256") not in (journal["targetSha256"], journal["previousSha256"]):
                raise ValueError("Interrupted rendered activation pointer differs from its pinned scope")
        else:
            if manifest["previousRenderedSha256"] != (current or {}).get("manifestSha256"):
                raise ValueError("Rendered pointer changed before activation")
            changed = [domain for domain in DOMAINS if manifest["domainSha256"][domain] != (current or {}).get("domainSha256", {}).get(domain)]
            journal = {"scope": scope, "phase": "ACTIVATING", "targetSha256": manifest["manifestSha256"],
                       "previousSha256": (current or {}).get("manifestSha256"), "previousOperationUuid": (current or {}).get("scope", {}).get("operationUuid"),
                       "changedDomains": changed, "startedDomains": [], "appliedDomains": [], "startedEpoch": time.time()}
            rendered_json(self.journal, journal)
        try:
            self.publish_pointer(target)
            if prerequisites is not None:
                journal["prerequisitesStarted"] = True;rendered_json(self.journal, journal)
                prerequisites(target, False)
                journal["prerequisitesVerified"] = True;rendered_json(self.journal, journal)
            for domain in journal["changedDomains"]:
                # This durable receipt precedes each daemon/configfs effect. A
                # process death requires replay of exactly the started domains.
                if domain not in journal.setdefault("startedDomains",[]):
                    journal["startedDomains"].append(domain)
                journal["activeDomain"]=domain;rendered_json(self.journal,journal)
                replay(target, domain, False)
                if domain not in journal.setdefault("appliedDomains",[]):journal["appliedDomains"].append(domain)
                rendered_json(self.journal,journal)
            verified = verify(target)
            if not isinstance(verified, dict) or set(verified) != set(DOMAINS) or any(verified[domain] is not True for domain in DOMAINS):
                raise ValueError("Rendered runtime did not pass all four protocol checks")
            if persist_desired is not None: persist_desired(target)
            journal.update(phase="VERIFIED", verifiedEpoch=time.time()); rendered_json(self.journal, journal)
            return self.status()
        except Exception:
            try: self.rollback(request, replay, verify, persist_desired, prerequisites)
            except Exception:
                journal.update(phase="RECOVERY_REQUIRED"); rendered_json(self.journal, journal)
            raise

    def finalize(self, request, generation, verify):
        journal = self.scoped_activation(request)
        path = self.pointer(); current = self.inspect(path)
        actual = generation()
        verified = verify(path)
        expected = {**current["scope"], "configurationSha256": current["configurationSha256"]}
        if (actual.get("pendingOperationUuid") or actual.get("generationStatus") != "IN_SYNC"
                or any((actual.get("generation") or {}).get(key) != value for key, value in expected.items())
                or actual.get("configurationSha256") != current["configurationSha256"]
                or not isinstance(verified, dict) or set(verified) != set(DOMAINS) or any(verified[domain] is not True for domain in DOMAINS)):
            raise ValueError("Rendered finalization requires the exact committed native generation and fresh runtime verification")
        if journal["phase"] not in ("VERIFIED", "COMPLETE") or current["manifestSha256"] != journal["targetSha256"]:
            raise ValueError("Rendered runtime was not durably verified")
        journal.update(phase="COMPLETE", completedEpoch=time.time()); rendered_json(self.journal, journal)
        return self.status()

    def rollback(self, request, replay, verify, persist_desired=None, prerequisites=None):
        journal = self.scoped_activation(request)
        previous_uuid = journal.get("previousOperationUuid")
        if not previous_uuid: raise ValueError("Rendered baseline is missing; recovery requires a verified source")
        previous = self.generations / str(uuid.UUID(previous_uuid)); manifest = self.inspect(previous)
        if manifest["manifestSha256"] != journal["previousSha256"]:
            raise ValueError("Previous rendered generation changed")
        current = self.inspect(self.pointer())
        if current["manifestSha256"] not in (journal["targetSha256"], journal["previousSha256"]):
            raise ValueError("Rendered rollback pointer has a foreign generation")
        journal.update(phase="ROLLING_BACK"); rendered_json(self.journal, journal)
        try:
            self.publish_pointer(previous)
            if prerequisites is not None and journal.get("prerequisitesStarted"):prerequisites(previous, True)
            started=journal.get("startedDomains",journal["changedDomains"])
            if any(domain not in journal["changedDomains"] for domain in started):raise ValueError("Rendered started-domain receipt is foreign")
            for domain in reversed(started):
                journal["activeDomain"]=domain;rendered_json(self.journal,journal)
                replay(previous,domain,True)
            verified = verify(previous)
            if not isinstance(verified, dict) or set(verified) != set(DOMAINS) or any(verified[domain] is not True for domain in DOMAINS):
                raise ValueError("Previous rendered runtime was not fully restored")
            if persist_desired is not None: persist_desired(previous)
            journal.update(phase="ROLLED_BACK", rolledBackEpoch=time.time()); rendered_json(self.journal, journal)
            return self.status()
        except Exception:
            journal.update(phase="RECOVERY_REQUIRED"); rendered_json(self.journal, journal)
            raise
