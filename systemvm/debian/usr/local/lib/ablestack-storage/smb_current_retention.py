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

"""Explicit retention of CURRENT local Samba identity; never restoration of empty SOURCE."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import time
import uuid
from smb_identity import SmbIdentity, identity_json, IDENTITY_DATABASES
from samba_public_sid import samba_public_sid, SambaPublicSidMissing
from pending_nfs_authorization import PendingNfsAuthorization
from identity_capsule import collect, encrypt, decrypt, validate_payload, regular_file
from service_identity_cipher import ServiceIdentityCipher

CURRENT_SMB_COMMON = {"instanceUuid", "operationUuid", "revision", "sourceGeneration",
                      "sourceConfigurationSha256", "rootVmBinding", "runtimePin"}
CURRENT_SMB_APPROVAL = {"expectedReview", "maintenanceApproved", "instanceName", "confirmation"}
CURRENT_SMB_KIND = "CURRENT_LOCAL_SMB_AFTERSTOP_IDENTITY_CHECKPOINT"


def current_smb_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


class SmbCurrentRetention:
    def __init__(self, cli=None, command=None, handler=None):
        self.cli = Path(cli or "/usr/local/bin/ablestack-storagectl")
        self.handler = handler or SmbIdentity(self.cli)
        self.root = self.handler.generations.parent / "smb-current-retention"
        self.command = command or self.native_command
        self.sid_reader = samba_public_sid
        self.reader = lambda *args: regular_file(*args)
        self.encryptor = lambda *args: encrypt(*args)
        self.decryptor = lambda *args: decrypt(*args)
        self.collector = lambda *args: collect(*args)
        self.validator = lambda *args: validate_payload(*args)
        self.lock = lambda: PendingNfsAuthorization.lock(None, os.getpid())
        self.boot = lambda: Path("/proc/sys/kernel/random/boot_id").read_text().strip()

    def native_command(self, arguments, payload=None):
        inherited = (9,) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") == "9" else ()
        result = subprocess.run([str(self.cli), *arguments], input=json.dumps(payload) if payload is not None else None,
                                capture_output=True, text=True, timeout=self.handler.remaining(15), pass_fds=inherited)
        if result.returncode or len(result.stdout) > 8*1024*1024:
            raise ValueError("CURRENT identity native observation failed")
        value = json.loads(result.stdout)
        if value.get("success") is not True:
            raise ValueError("CURRENT identity native observation did not verify")
        return value

    def request(self, request, action):
        extras = {"current-review": set(), "current-quiesce": CURRENT_SMB_APPROVAL | {"originalCapsule", "credentialPrivateKey"},
                  "current-export": {"currentReviewHash", "publicKey"},
                  "current-retain": {"currentReviewHash", "currentIdentityReference"},
                  "current-verify": {"currentReviewHash", "currentIdentityReference"}}
        if action not in extras or not isinstance(request, dict) or set(request) != CURRENT_SMB_COMMON | extras[action]:
            raise ValueError("CURRENT identity request is not closed")
        scope = self.handler.scope(request)
        source = request["sourceGeneration"]
        if (not isinstance(source, dict) or source.get("instanceUuid") != scope["instanceUuid"]
                or type(source.get("revision")) is not int or source["revision"] != scope["revision"]-1
                or source.get("configurationSha256") != request["sourceConfigurationSha256"]
                or not re.fullmatch("[0-9a-f]{64}", str(request["sourceConfigurationSha256"]))):
            raise ValueError("CURRENT identity SOURCE generation is foreign")
        binding = request["rootVmBinding"]
        if not isinstance(binding, dict) or set(binding) != {"vmUuid", "rootVolumeUuid"}:
            raise ValueError("CURRENT identity ROOT binding is not exact")
        for value in binding.values():
            if not isinstance(value, str) or str(uuid.UUID(value)) != value:
                raise ValueError("CURRENT identity ROOT binding is invalid")
        pin = request["runtimePin"]
        if (not isinstance(pin, dict) or set(pin) != {"bundleVersion", "archiveSha256", "manifestSha256", "updaterSha256"}
                or not re.fullmatch("[A-Za-z0-9][A-Za-z0-9._-]{0,127}", str(pin.get("bundleVersion")))
                or any(not re.fullmatch("[0-9a-f]{64}", str(pin.get(key))) for key in pin if key != "bundleVersion")):
            raise ValueError("CURRENT identity signed runtime pin is invalid")
        return scope

    def source(self, request, terminal=False):
        scope = self.handler.scope(request)
        if os.path.lexists(self.handler.configuration / "desired-state/smb-share-apply.json"):
            raise ValueError("CURRENT local retention requires the actual absent SMB source file")
        current = identity_json(self.handler.generations / "current.json")
        pending_path = self.handler.generations / "pending.json"
        pending = identity_json(pending_path) if os.path.lexists(pending_path) else None
        if current != request["sourceGeneration"]:
            raise ValueError("CURRENT identity current generation changed")
        artifact = identity_json(self.handler.generations / (current["operationUuid"]+".json"))
        names = {"desired-state/nfs-export-apply.json", "desired-state/smb-share-apply.json", "iscsi-targets.json",
                 "nvmeof-subsystems.json", "posix-directory-policies.json", "network-endpoints.json", "sharedfs-network.json"}
        desired = artifact.get("desired")
        if (artifact.get("phase") != "VERIFIED" or any(artifact.get(key) != current.get(key)
                for key in ("instanceUuid", "operationUuid", "revision", "configurationSha256", "verifiedAt"))
                or not isinstance(desired, dict) or set(desired) != names or desired["desired-state/smb-share-apply.json"] is not None
                or current_smb_digest(desired) != request["sourceConfigurationSha256"]):
            raise ValueError("CURRENT identity lacks verified SMB-null SOURCE seven")
        if pending is None:
            if not terminal:
                raise ValueError("CURRENT identity failed writer is absent")
            completed = identity_json(self.handler.generations / (scope["operationUuid"]+".json"))
            if (completed.get("phase") != "ROLLED_BACK" or completed.get("previous") != current
                    or completed.get("beforeSha256") != request["sourceConfigurationSha256"]
                    or any(completed.get(key) != value for key, value in scope.items())):
                raise ValueError("CURRENT retained terminal rollback is not its original writer")
        elif (pending.get("phase") != "PREPARED" or pending.get("previous") != current
              or pending.get("beforeSha256") != request["sourceConfigurationSha256"]
              or any(pending.get(key) != value for key, value in scope.items())):
            raise ValueError("CURRENT identity pending writer changed")
        status = self.command(("operation", "generation", "status"))
        if (status.get("generation") != current or status.get("configurationSha256") != request["sourceConfigurationSha256"]
                or status.get("configurationDesiredState") != desired
                or status.get("pendingOperationUuid") != (scope["operationUuid"] if pending is not None else None)):
            raise ValueError("CURRENT identity canonical SOURCE seven changed")
        maintenance = self.command(("operation", "maintenance", "status"))
        rendered = self.command(("operation", "generation", "render-status"))
        if (maintenance.get("bootHeld") is not False or rendered.get("current") is not None
                or rendered.get("activation") is not None):
            raise ValueError("CURRENT identity cannot borrow ROOT/SERVICE/rendered authority")
        domain = self.handler.configuration / "smb-domain.json"
        if os.path.lexists(domain):
            state = identity_json(domain)
            if str(state.get("joinState") or state.get("state") or "").upper() not in ("", "NOT_JOINED", "LEFT"):
                raise ValueError("CURRENT local identity rejects AD authority")
        if any(os.path.lexists(name) for name in ("/etc/krb5.keytab", "/etc/ablestack-storage/ad-machine.conf", "/var/lib/samba/winbindd_idmap.tdb")):
            raise ValueError("CURRENT local identity contains AD private artifacts")
        operations = Path(os.environ.get("ABLESTACK_STORAGE_VOLUME_OPERATIONS", "/var/lib/ablestack-storage/volume-operations"))
        if operations.exists():
            for path in operations.glob("*.json"):
                operation = identity_json(path)
                if operation.get("formatterActive") is True or operation.get("terminationPending") is True:
                    raise ValueError("CURRENT identity cannot proceed with an unresolved formatter")
        self.root_binding(request["rootVmBinding"])
        self.runtime_binding(request)
        return scope, current

    def root_binding(self, expected):
        actual = Path("/sys/class/dmi/id/product_uuid").read_text().strip().lower()
        if actual != expected["vmUuid"]:
            raise ValueError("CURRENT identity guest UUID changed")
        rows = json.loads(self.handler.run(["lsblk", "-J", "-b", "-o", "PATH,TYPE,SERIAL,MOUNTPOINTS"]))["blockdevices"]
        roots = []
        def descend(node, parent=None):
            disk = node if node.get("type") == "disk" else parent
            if "/" in (node.get("mountpoints") or []) and disk is not None:
                roots.append(disk)
            for child in node.get("children") or []:
                descend(child, disk)
        for row in rows:
            descend(row)
        token = expected["rootVolumeUuid"].replace("-", "")
        if len(roots) != 1 or str(roots[0].get("serial") or "").strip() not in (token, token[:20]):
            raise ValueError("CURRENT identity ROOT serial ownership changed")

    def runtime_binding(self, request):
        pin = request["runtimePin"]
        result = subprocess.run(["/usr/local/bin/ablestack-storage-runtime-updater", "readback", "/dev/stdin"],
                                input=json.dumps({"transactionId": "smb-current-"+request["operationUuid"],
                                                  **{key: pin[key] for key in ("bundleVersion", "archiveSha256", "manifestSha256")}}),
                                capture_output=True, text=True, timeout=self.handler.remaining(15))
        observed = json.loads(result.stdout) if not result.returncode else {}
        if (any(observed.get(key) is not True for key in ("success", "signedRuntimeVerified", "installedFilesVerified", "entrypointsVerified"))
                or observed.get("currentVersion") != pin["bundleVersion"]
                or any(observed.get(key) != pin[key] for key in ("archiveSha256", "manifestSha256", "updaterSha256"))):
            raise ValueError("CURRENT identity installed signed runtime changed")

    def namespaces(self, request):
        configured = self.handler.run(["testparm", "-s", "--parameter-name=netbios name", "/etc/samba/smb.conf"]).strip().upper()
        target = "STOR"+request["instanceUuid"].replace("-", "")[:10].upper()
        if not re.fullmatch("[A-Z0-9][A-Z0-9_-]{0,14}", configured):
            raise ValueError("CURRENT identity configured namespace is invalid")
        result = []
        for name in sorted({configured, target}):
            try:
                sid = self.sid_reader(name)
            except SambaPublicSidMissing:
                continue
            result.append({"netbiosName": name, "machineSid": sid})
        if not result:
            raise ValueError("CURRENT identity has no existing allowed public SAM namespace")
        return result

    def package_file(self, path):
        # Existing image/package authority, not a caller-supplied executable hash.
        name = str(Path(path))
        data, info = self.reader(name)
        relative = name.lstrip("/")
        digest_path = Path("/var/lib/dpkg/info/samba.md5sums")
        digest_data, _ = self.reader(str(digest_path))
        rows = [line.split() for line in digest_data.decode().splitlines()]
        matches = [row[0] for row in rows if len(row) == 2 and row[1] == relative]
        if len(matches) != 1 or hashlib.md5(data).hexdigest() != matches[0]:
            raise ValueError("CURRENT identity fixed Samba package file changed")
        return {"path": name, "device": info.st_dev, "inode": info.st_ino, "sha256": hashlib.sha256(data).hexdigest()}

    def units(self, stopped=False, require_master=True):
        result = []
        for unit, executable in (("smbd.service", "/usr/sbin/smbd"), ("nmbd.service", "/usr/sbin/nmbd")):
            pid = int(self.handler.run(["systemctl", "show", unit, "--property=MainPID", "--value"]).strip())
            fragment = self.handler.run(["systemctl", "show", unit, "--property=FragmentPath", "--value"]).strip()
            if fragment not in ("/lib/systemd/system/"+unit, "/usr/lib/systemd/system/"+unit):
                raise ValueError("CURRENT identity fixed default unit is foreign")
            vendor = self.package_file(fragment.replace("/usr/lib/", "/lib/"))
            dropins = self.handler.run(["systemctl", "show", unit, "--property=DropInPaths", "--value"]).strip().split()
            if dropins != ["/etc/systemd/system/"+unit+".d/ablestack-storage-gate.conf"]:
                raise ValueError("CURRENT identity fixed unit lacks its signed boot gate")
            for path in dropins:
                if path != "/etc/systemd/system/"+unit+".d/ablestack-storage-gate.conf":
                    raise ValueError("CURRENT identity unit has foreign drop-ins")
                raw, _ = self.reader(path)
                if raw != b"[Service]\nExecCondition=/usr/local/bin/ablestack-storagectl operation generation render-boot-gate %n\n":
                    raise ValueError("CURRENT identity unit boot guard changed")
            binary = self.package_file(executable)
            if stopped:
                if pid != 0:
                    raise ValueError("CURRENT identity default unit remains active")
                continue
            if pid == 0:
                continue
            root = self.handler.process_root / str(pid)
            loaded = (root / "exe").stat()
            if (loaded.st_dev, loaded.st_ino) != (binary["device"], binary["inode"]):
                raise ValueError("CURRENT identity loaded executable inode changed")
            argv = [part.decode() for part in (root/"cmdline").read_bytes().split(bytes([0])) if part]
            if (os.path.realpath(root/"exe") != executable or (root/"comm").read_text().strip() != unit[:-8]
                    or any(arg not in (executable, "--foreground", "--no-process-group", "-F") for arg in argv)
                    or not argv or argv[0] != executable
                    or (root/"cgroup").read_text().strip() != "0::/system.slice/"+unit):
                raise ValueError("CURRENT identity fixed master executable/cgroup/argv changed")
            ticks = (root/"stat").read_text().rpartition(")")[2].split()[19]
            result.append({"unit": unit, "pid": pid, "startTicks": ticks, "executable": binary,
                           "vendorUnit": vendor, "argv": argv, "cgroup": "0::/system.slice/"+unit,
                           "configurationPath": "/etc/samba/smb.conf"})
        if not stopped and require_master and not any(row["unit"] == "smbd.service" for row in result):
            raise ValueError("CURRENT identity default smbd ownership is unavailable")
        return result

    def facts(self, request, stopped=False):
        databases = self.handler.database_identity()
        if set(databases) != set(IDENTITY_DATABASES) or any(item.get("present") is not True for item in databases.values()):
            raise ValueError("CURRENT retention requires both existing protected identity databases")
        public = self.namespaces(request)
        units = self.units(stopped)
        sockets = [row for row in self.handler.socket_rows(self.handler.run(["ss", "-H", "-ltnp"])) if row["port"] in (139, 445)]
        allowed = {row["pid"] for row in units if row["unit"] == "smbd.service"}
        if any(row["port"] != 445 or row["ip"] not in ("0.0.0.0", "::") or set(row["pids"]) != allowed for row in sockets):
            raise ValueError("CURRENT identity listener ownership is foreign")
        if stopped and (sockets or self.handler.identity_holders()):
            raise ValueError("CURRENT identity still has a listener/private database holder")
        if not stopped and not sockets:
            raise ValueError("CURRENT identity default acceptor observation is absent")
        # Unknown identity daemons are never stopped as a side effect of adoption.
        allowed_units = {row["unit"] for row in units}
        for process in self.handler.process_root.iterdir():
            self.handler.remaining()
            if not process.name.isdigit():
                continue
            try:
                name = (process/"comm").read_text().strip()
                if name in ("smbd", "nmbd", "winbindd", "samba", "samba-dcerpcd", "samba-bgqd"):
                    group = (process/"cgroup").read_text().strip()
                    if stopped or name not in ("smbd", "nmbd") or group not in {"0::/system.slice/"+unit for unit in allowed_units}:
                        raise ValueError("CURRENT identity has an unapproved identity process")
            except FileNotFoundError:
                continue
        if stopped:
            connections = self.handler.socket_rows(self.handler.run(["ss", "-H", "-ntp"]))
            if any(row["port"] in (139, 445) and row["state"] in ("ESTAB", "SYN-RECV", "SYN_RECV") for row in connections):
                raise ValueError("CURRENT stopped identity retains an established SMB connection")
            sessions = {"available": True, "establishedTcpCount": 0, "synRecvTcpCount": 0, "smbSessionCount": 0,
                        "treeConnectionCount": 0, "openFileCount": 0, "byteLockOpenFileCount": 0,
                        "lockingDatabasesAligned": True, "safeToRebind": True, "source": "CURRENT_AFTERSTOP_ABSENCE"}
        else:
            sessions = self.handler.sessions([{"listenIp": "0.0.0.0", "port": 445}, {"listenIp": "::", "port": 445}],
                                            [{"lockingDatabasesAligned": True}])
        if sessions.get("safeToRebind") is not True:
            raise ValueError("CURRENT identity sessions/byte locks have not drained")
        # FD inode agreement is checked for every live holder, not inferred from
        # the PID/name. Stopped capture requires zero holders instead.
        holders = self.handler.identity_holders()
        for process in self.handler.process_root.iterdir():
            self.handler.remaining()
            if not process.name.isdigit():
                continue
            try:
                for fd in (process / "fd").iterdir():
                    try:
                        target = os.readlink(fd)
                        canonical = target[:-10] if target.endswith(" (deleted)") else target
                        if canonical not in IDENTITY_DATABASES.values():
                            continue
                        info = fd.stat()
                        if stopped:
                            raise ValueError("CURRENT identity has a foreign private FD holder")
                        group = (process / "cgroup").read_text().strip()
                        if group not in {"0::/system.slice/"+unit for unit in allowed_units}:
                            raise ValueError("CURRENT identity private FD is held by an unapproved process")
                        item = next(value for value in databases.values() if value["path"] == canonical)
                        if target.endswith(" (deleted)") or (info.st_dev, info.st_ino) != (item["device"], item["inode"]):
                            raise ValueError("CURRENT identity private FD points at a replaced database")
                    except FileNotFoundError:
                        continue
            except FileNotFoundError:
                continue
        for row in holders:
            item = next((value for value in databases.values() if value["path"] == row["path"]), None)
            root = self.handler.process_root / str(row["pid"])
            if (not item or row["deleted"] or (row["device"], row["inode"]) != (item["device"], item["inode"])
                    or (root/"cgroup").read_text().strip() not in {"0::/system.slice/"+unit for unit in allowed_units}):
                raise ValueError("CURRENT identity private database descriptor is foreign/stale")
        return {"bootId": self.boot(), "publicNamespaceSids": public, "databases": databases, "units": units,
                "listeners": sockets, "identityHolders": holders,
                "configurationSha256": hashlib.sha256(self.reader("/etc/samba/smb.conf")[0]).hexdigest(),
                "sessions": sessions, "loadedDaemonSidVerified": False}

    def review(self, request):
        self.request(request, "current-review")
        self.source(request)
        facts = self.facts(request)
        stable = {"scope": self.handler.scope(request), "sourceGeneration": request["sourceGeneration"],
                  "sourceConfigurationSha256": request["sourceConfigurationSha256"], "rootVmBinding": request["rootVmBinding"],
                  "runtimePin": request["runtimePin"], "currentFacts": facts}
        return {"success": True, "schemaVersion": 1, "sideEffects": False, "kind": "CURRENT_LOCAL_SMB_IDENTITY_RECOVERY_REVIEW",
                **stable, "bootId": facts["bootId"], "currentReviewHash": current_smb_digest(stable), "generatedEpoch": time.time()}

    def path(self, scope, suffix):
        return self.root / (scope["operationUuid"]+"-"+suffix+".json")

    def read(self, path):
        return ServiceIdentityCipher().read(path)

    def save(self, path, value, immutable=True):
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = self.root.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise ValueError("CURRENT identity retention directory is not protected")
        if immutable:
            if os.path.lexists(path):
                if self.read(path) != value:
                    raise ValueError("CURRENT identity immutable checkpoint changed")
            else:
                ServiceIdentityCipher().write(path, value)

    def journal(self, scope, value=None):
        # Journal writer and reader use the same existing protected namespace.
        return self.handler.repair_journal({"operationUuid": scope["operationUuid"]+"-current"}, value)

    def stopped(self, request, journal, terminal=False):
        self.source(request, terminal)
        if (journal.get("scope") != self.handler.scope(request) or journal.get("common") != {key: request[key] for key in CURRENT_SMB_COMMON}
                or journal.get("bootId") != self.boot() or journal.get("phase") not in ("STOPPED", "EXPORTED", "RETAINED")):
            raise ValueError("CURRENT identity stopped authority changed")
        facts = self.facts(request, stopped=True)
        if (facts["publicNamespaceSids"] != journal["review"]["currentFacts"]["publicNamespaceSids"]
                or facts["databases"] != journal["review"]["currentFacts"]["databases"]
                or facts["configurationSha256"] != journal["review"]["currentFacts"]["configurationSha256"]):
            raise ValueError("CURRENT identity namespace/database inode changed after stop")
        return facts

    def quiesce(self, request):
        scope = self.request(request, "current-quiesce")
        if (request["maintenanceApproved"] is not True or not isinstance(request["instanceName"], str)
                or not request["instanceName"] or request["confirmation"] != request["instanceName"]):
            raise ValueError("CURRENT identity requires explicit exact-name maintenance approval")
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") != "9":
            raise ValueError("CURRENT identity requires its held writer FD9")
        self.lock()
        old = self.journal(scope)
        if old is not None:
            if (old["common"] != {key: request[key] for key in CURRENT_SMB_COMMON}
                    or old["review"] != request["expectedReview"] or old["confirmation"] != request["confirmation"]
                    or old["originalCapsuleSha256"] != current_smb_digest(request["originalCapsule"])):
                raise ValueError("CURRENT identity interrupted approval changed")
            if old["phase"] in ("STOPPED", "EXPORTED", "RETAINED"):
                self.stopped(request, old)
                return self.stop_result(old)
            if old["bootId"] != self.boot() or old["phase"] not in ("QUIESCING", "RECOVERY_REQUIRED"):
                raise ValueError("CURRENT identity interrupted STOP is foreign")
            self.source(request)
            databases = self.handler.database_identity()
            if (databases != old["review"]["currentFacts"]["databases"]
                    or self.namespaces(request) != old["review"]["currentFacts"]["publicNamespaceSids"]):
                raise ValueError("CURRENT identity interrupted private namespace/inodes changed")
            remaining = self.units(require_master=False)
            if remaining:
                observed = self.facts(request)
                masters = observed["units"]
            else:
                masters = []
            previous = {row["unit"]: row for row in old["review"]["currentFacts"]["units"]}
            if any(previous.get(row["unit"]) != row for row in masters):
                raise ValueError("CURRENT identity interrupted master was replaced")
            if not masters:
                old["phase"] = "STOPPED"
                self.stopped(request, old)
                self.journal(scope, old)
                return self.stop_result(old)
            return self.stop_owned(request, old, masters)
        if not isinstance(request["originalCapsule"], dict) or set(request["originalCapsule"]) != {"schemaVersion", "scope", "wrappedKey", "nonce", "ciphertext", "sha256"}:
            raise ValueError("CURRENT identity original cipher shape is not exact")
        original = self.decryptor(request["originalCapsule"], request["credentialPrivateKey"],
                                  scope["instanceUuid"]+":"+scope["operationUuid"])
        self.validator(original)
        if (original.get("adIdentity") is not None or original.get("sourceConfigurationSha256") not in (None, request["sourceConfigurationSha256"])
                or any(original["files"].get(path) != {"absent": True} for path in IDENTITY_DATABASES.values())):
            raise ValueError("CURRENT identity original capsule is not its authenticated empty local SOURCE")
        common = {key: request[key] for key in CURRENT_SMB_COMMON}
        fresh = self.review(common)
        expected = request["expectedReview"]
        if (not isinstance(expected, dict) or set(expected) != set(fresh)
                or type(expected.get("generatedEpoch")) not in (int, float)
                or not 0 <= time.time()-expected["generatedEpoch"] <= 60
                or any(expected[key] != fresh[key] for key in fresh if key != "generatedEpoch")):
            raise ValueError("CURRENT identity reviewed approval is stale/changed")
        value = {"scope": scope, "common": common, "review": expected, "bootId": self.boot(),
                 "confirmation": request["confirmation"], "originalCapsuleSha256": current_smb_digest(request["originalCapsule"]),
                 "phase": "QUIESCING"}
        return self.stop_owned(request, value, fresh["currentFacts"]["units"])

    def stop_owned(self, request, value, masters):
        scope = self.handler.scope(request)
        handles = self.handler.open_master_handles(masters)
        paused = []
        try:
            # Pinning a PID does not pin systemd's current ownership. Reobserve
            # the complete approval after opening handles, before the first
            # signal; a restarted/reassigned master cannot inherit the intent.
            self.source(request)
            pinned = self.facts(request)
            frozen = value["review"]["currentFacts"]
            if (pinned["units"] != masters or any(pinned[key] != frozen[key] for key in
                    ("bootId", "publicNamespaceSids", "databases", "configurationSha256"))):
                raise ValueError("CURRENT identity ownership changed after pidfd pinning")
            self.journal(scope, value)
            for row in masters:
                signal.pidfd_send_signal(handles[row["pid"]], signal.SIGSTOP, None, 0); paused.append(row["pid"])
            if self.handler.sessions([{"listenIp": "0.0.0.0", "port": 445}, {"listenIp": "::", "port": 445}],
                                     [{"lockingDatabasesAligned": True}]).get("safeToRebind") is not True:
                raise ValueError("CURRENT identity new session raced approved quiesce")
            for row in sorted(masters, key=lambda row: row["unit"] == "smbd.service"):
                signal.pidfd_send_signal(handles[row["pid"]], signal.SIGTERM, None, 0)
                signal.pidfd_send_signal(handles[row["pid"]], signal.SIGCONT, None, 0); paused.remove(row["pid"])
                self.handler.run(["systemctl", "stop", row["unit"]])
            value["phase"] = "STOPPED"
            self.stopped(request, value)
            self.journal(scope, value)
            return self.stop_result(value)
        except Exception:
            value["phase"] = "RECOVERY_REQUIRED"; self.journal(scope, value)
            raise
        finally:
            for pid in paused:
                try:
                    signal.pidfd_send_signal(handles[pid], signal.SIGCONT, None, 0)
                except ProcessLookupError:
                    pass
            for descriptor in handles.values():
                os.close(descriptor)

    def stop_result(self, value):
        return {"success": True, "schemaVersion": 1, "scope": value["scope"], "kind": "CURRENT_LOCAL_SMB_IDENTITY_STOPPED",
                "currentReviewHash": value["review"]["currentReviewHash"], "currentStoppedVerified": True,
                "bootId": value["bootId"], "originalIdentityRestored": False, "automaticSmbExposure": False}

    def protected(self, request, action, terminal=False):
        scope = self.request(request, action)
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") != "9" and action != "current-verify":
            raise ValueError("CURRENT identity mutation requires held FD9")
        if action != "current-verify":
            self.lock()
        journal = self.journal(scope)
        if not journal or journal["review"]["currentReviewHash"] != request["currentReviewHash"]:
            raise ValueError("CURRENT identity retained review authority changed")
        self.stopped(request, journal, terminal)
        return scope, journal

    def export(self, request):
        scope, journal = self.protected(request, "current-export")
        path = self.path(scope, "cipher")
        if os.path.lexists(path):
            record = self.read(path)
            if (record["publicKey"] != request["publicKey"] or record["currentReviewHash"] != request["currentReviewHash"]
                    or record["common"] != journal["common"]):
                raise ValueError("CURRENT identity cached key/review changed")
            self.match_private(record)
            return self.export_result(record)
        payload = self.collector([])
        self.validator(payload)
        if payload.get("adIdentity") is not None:
            raise ValueError("CURRENT local export cannot include AD authority")
        after = self.stopped(request, journal)
        wrapper = {"schemaVersion": 1, "kind": CURRENT_SMB_KIND, "scope": scope, "common": journal["common"],
                   "currentReviewHash": request["currentReviewHash"], "bootId": self.boot(),
                   "publicNamespaceSids": after["publicNamespaceSids"], "identity": payload}
        cipher = self.encryptor(wrapper, request["publicKey"], scope["instanceUuid"]+":"+scope["operationUuid"]+":CURRENT_LOCAL_AFTERSTOP")
        # Private byte fingerprints remain only in the native protected record;
        # public results expose ciphertext hashes, never plaintext TDB hashes.
        fingerprints = {path: file["sha256"] for path, file in payload["files"].items() if file.get("absent") is not True}
        record = {"schemaVersion": 1, "kind": CURRENT_SMB_KIND, "scope": scope, "common": journal["common"],
                  "currentReviewHash": request["currentReviewHash"], "bootId": self.boot(), "publicKey": request["publicKey"],
                  "publicNamespaceSids": after["publicNamespaceSids"], "privateFingerprints": fingerprints,
                  "accountNames": {path: [line.split(":", 1)[0] for line in lines] for path, lines in payload["accounts"].items()},
                  "accountFingerprint": current_smb_digest(payload["accounts"]), "capsule": cipher}
        self.match_private(record)
        self.save(path, record, immutable=True)
        journal["phase"] = "EXPORTED"; self.journal(scope, journal)
        return self.export_result(record)

    def export_result(self, record):
        reference = {"operationUuid": record["scope"]["operationUuid"], "sha256": current_smb_digest(record)}
        receipt = {"kind": CURRENT_SMB_KIND, "scope": record["scope"], "capsuleSha256": record["capsule"]["sha256"],
                   "sourceConfigurationSha256": record["common"]["sourceConfigurationSha256"], "checkpointRecordSha256": reference["sha256"]}
        return {"success": True, "schemaVersion": 1, "scope": record["scope"], "capsule": record["capsule"],
                "currentIdentityCheckpoint": receipt, "currentIdentityReference": reference}

    def match_private(self, record):
        if self.handler.identity_holders():
            raise ValueError("CURRENT retained identity has a live private holder")
        for path, checksum in record["privateFingerprints"].items():
            if hashlib.sha256(self.reader(path)[0]).hexdigest() != checksum:
                raise ValueError("CURRENT stopped private database bytes changed")

        accounts = {}
        for path, names in record["accountNames"].items():
            accounts[path] = [] if not names else [line for line in self.reader(path)[0].decode().splitlines() if line.split(":", 1)[0] in names]
        if current_smb_digest(accounts) != record["accountFingerprint"]:
            raise ValueError("CURRENT retained managed Unix credential records changed")

    def retain(self, request, terminal=False):
        scope, journal = self.protected(request, "current-verify" if terminal else "current-retain", terminal)
        record = self.read(self.path(scope, "cipher"))
        if (request["currentIdentityReference"] != {"operationUuid": scope["operationUuid"], "sha256": current_smb_digest(record)}
                or record["common"] != journal["common"] or record["currentReviewHash"] != request["currentReviewHash"]
                or record["bootId"] != self.boot() or record["publicNamespaceSids"] != self.namespaces(request)):
            raise ValueError("CURRENT retained checkpoint binding changed")
        self.match_private(record)
        if terminal:
            if journal["phase"] != "RETAINED":
                raise ValueError("CURRENT retained final verification is not published")
        else:
            baseline = {"schemaVersion": 1, "kind": "LOCAL_IDENTITY_RETAINED_WITHOUT_SMB_CONFIG", "scope": scope,
                        "sourceGeneration": request["sourceGeneration"], "sourceConfigurationSha256": request["sourceConfigurationSha256"],
                        "bootId": self.boot(), "currentIdentityReference": request["currentIdentityReference"]}
            self.save(self.path(scope, "baseline"), baseline, immutable=True)
            journal["phase"] = "RETAINED"; self.journal(scope, journal)
        return {"success": True, "scope": scope, "kind": "LOCAL_IDENTITY_RETAINED_WITHOUT_SMB_CONFIG",
                "currentIdentityRetained": True, "originalIdentityRestored": False,
                "originalConfigurationSourceRestored": True, "automaticSmbExposure": False,
                "bootId": self.boot(), "generation": request["sourceGeneration"],
                "sourceConfigurationSha256": request["sourceConfigurationSha256"],
                "currentIdentityReference": request["currentIdentityReference"], "sideEffects": False}

    def inspect_retained(self, scope, current):
        if not self.root.exists():
            return None
        parent = self.root.lstat()
        if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.geteuid() or stat.S_IMODE(parent.st_mode) != 0o700:
            raise ValueError("CURRENT retained baseline parent is foreign")
        candidates = []
        for path in self.root.glob("*-baseline.json"):
            value = self.read(path)
            if value.get("sourceGeneration") == current:
                candidates.append(value)
        if not candidates:
            return None
        if len(candidates) != 1:
            raise ValueError("CURRENT retained baseline is ambiguous")
        baseline = candidates[0]
        original_scope = baseline["scope"]
        journal = self.journal(original_scope)
        if (baseline.get("kind") != "LOCAL_IDENTITY_RETAINED_WITHOUT_SMB_CONFIG" or baseline.get("bootId") != self.boot()
                or not journal or journal.get("phase") != "RETAINED" or baseline.get("scope") != journal.get("scope")
                or baseline.get("sourceConfigurationSha256") != current.get("configurationSha256")):
            raise ValueError("CURRENT retained baseline has no terminal authority")
        record = self.read(self.path(original_scope, "cipher"))
        expected_ref = {"operationUuid": original_scope["operationUuid"], "sha256": current_smb_digest(record)}
        if baseline.get("currentIdentityReference") != expected_ref or record.get("common") != journal.get("common"):
            raise ValueError("CURRENT retained baseline ciphertext reference changed")
        request = journal["common"]
        old_artifact = identity_json(self.handler.generations / (original_scope["operationUuid"]+".json"))
        if (old_artifact.get("phase") != "ROLLED_BACK" or old_artifact.get("previous") != current
                or old_artifact.get("beforeSha256") != current["configurationSha256"]
                or any(old_artifact.get(key) != value for key, value in original_scope.items())):
            raise ValueError("CURRENT retained baseline original writer never rolled back")
        # A new legitimate pending writer is observed as itself. It never
        # borrows the previous failed operation UUID/revision.
        pending_path = self.handler.generations / "pending.json"
        pending = identity_json(pending_path) if os.path.lexists(pending_path) else None
        if pending is not None:
            if (pending.get("phase") != "PREPARED" or pending.get("previous") != current
                    or pending.get("beforeSha256") != current["configurationSha256"]
                    or scope["revision"] != current["revision"]+1
                    or any(pending.get(key) != value for key, value in scope.items())):
                raise ValueError("CURRENT retained baseline new writer is foreign")
        elif scope["revision"] != current["revision"]:
            raise ValueError("CURRENT retained baseline observation is stale")
        if scope["instanceUuid"] != current["instanceUuid"]:
            raise ValueError("CURRENT retained baseline instance changed")
        self.root_binding(request["rootVmBinding"])
        self.runtime_binding(request)
        maintenance = self.command(("operation", "maintenance", "status"))
        rendered = self.command(("operation", "generation", "render-status"))
        status = self.command(("operation", "generation", "status"))
        if (maintenance.get("bootHeld") is not False or rendered.get("current") is not None or rendered.get("activation") is not None
                or status.get("generation") != current or status.get("configurationSha256") != current["configurationSha256"]):
            raise ValueError("CURRENT retained baseline canonical/maintenance changed")
        facts = self.facts(request, stopped=True)
        if (facts["publicNamespaceSids"] != record["publicNamespaceSids"]
                or facts["configurationSha256"] != journal["review"]["currentFacts"]["configurationSha256"]):
            raise ValueError("CURRENT retained public namespace changed")
        self.match_private(record)
        return {"success": True, "smbIdentitySupported": True, "scope": scope, "bootId": self.boot(), "generation": current,
                "configurationSha256": facts["configurationSha256"], "identityBaselineKind": baseline["kind"],
                "databases": facts["databases"], "masters": [], "ownedEndpoints": [], "ownershipVerified": True,
                "endpointTcpReady": True, "identityDatabaseAligned": True, "requiresQuiesce": False,
                "identityRestoreSafe": True, "identityHolders": [], "sessions": facts["sessions"],
                "generatedEpoch": time.time(), "currentIdentityRetained": True, "originalIdentityRestored": False,
                "currentIdentityReference": expected_ref, "publicNamespaceSids": facts["publicNamespaceSids"]}

    def execute(self, action, request):
        if action == "current-review":
            return self.review(request)
        if action == "current-quiesce":
            return self.quiesce(request)
        if action == "current-export":
            return self.export(request)
        if action in ("current-retain", "current-verify"):
            return self.retain(request, terminal=action == "current-verify")
        raise ValueError("Unknown CURRENT local identity action")
