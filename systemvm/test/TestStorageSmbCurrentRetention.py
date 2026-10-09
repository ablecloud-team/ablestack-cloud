# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
import base64
import copy
import ctypes
import fcntl
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

LIB = Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/lib/ablestack-storage"
sys.path.insert(0, str(LIB))
import config_generation as generation
import smb_identity
import smb_current_retention as module
import identity_capsule as capsule
import samba_public_sid as sid_module

CLI = Path(__file__).resolve().parents[2]/"systemvm/debian/usr/local/bin/ablestack-storagectl"


class StorageSmbCurrentRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.root.chmod(0o700)
        self.config = self.root/"config"; self.config.mkdir()
        self.gen = generation.Generation(self.root/"generations", self.config); self.gen.root.mkdir()
        self.scope = {"instanceUuid": str(uuid.uuid4()), "operationUuid": str(uuid.uuid4()), "revision": 4}
        self.desired = {name: None for name in self.gen.paths}
        self.desired["desired-state/nfs-export-apply.json"] = {"enabled": True, "exports": []}
        self.gen.replace_desired(self.desired)
        self.current = {"instanceUuid": self.scope["instanceUuid"], "operationUuid": str(uuid.uuid4()), "revision": 3,
                        "configurationSha256": self.gen.digest(), "verifiedAt": 1234}
        generation.atomic_json(self.gen.current, self.current)
        generation.atomic_json(self.gen.root/(self.current["operationUuid"]+".json"), {**self.current, "phase": "VERIFIED", "desired": self.desired})
        generation.atomic_json(self.gen.pending, {**self.scope, "phase": "PREPARED", "previous": self.current,
                                                "beforeSha256": self.current["configurationSha256"]})
        self.handler = smb_identity.SmbIdentity(CLI)
        self.handler.generations = self.gen.root; self.handler.configuration = self.config
        self.handler.process_root = self.root/"process"; self.handler.process_root.mkdir()
        self.native = module.SmbCurrentRetention(CLI, command=self.command, handler=self.handler)
        self.native.root_binding = lambda expected: None
        self.native.runtime_binding = lambda request: None
        self.boot = str(uuid.uuid4()); self.native.boot = lambda: self.boot
        self.request = {**self.scope, "sourceGeneration": self.current, "sourceConfigurationSha256": self.current["configurationSha256"],
                        "rootVmBinding": {"vmUuid": str(uuid.uuid4()), "rootVolumeUuid": str(uuid.uuid4())},
                        "runtimePin": {"bundleVersion": "fixture", "archiveSha256": "a"*64, "manifestSha256": "b"*64, "updaterSha256": "c"*64}}
        self.active = True; self.units_value = [{"unit": "smbd.service", "pid": 111, "startTicks": "101"},
                                               {"unit": "nmbd.service", "pid": 222, "startTicks": "102"}]
        for row in self.units_value:
            name = row["unit"][:-8]; executable = "/usr/sbin/"+name
            row.update({"executable": {"path": executable, "device": 1, "inode": row["pid"], "sha256": "d"*64},
                        "vendorUnit": {"path": "/lib/systemd/system/"+row["unit"], "device": 1, "inode": row["pid"]+1, "sha256": "e"*64},
                        "argv": [executable, "--foreground", "--no-process-group"],
                        "cgroup": "0::/system.slice/"+row["unit"], "configurationPath": "/etc/samba/smb.conf"})
        self.calls = []; self.counter = 0
        self.private = {name: self.root/Path(path).name for name, path in module.IDENTITY_DATABASES.items()}
        self.private["PASSDB"].write_bytes(b"PUBLIC_ONLY_SYNTHETIC_PASSDB"); self.private["PASSDB"].chmod(0o600)
        self.create_sid(self.private["SECRETS"])
        self.native.reader = self.read
        self.native.sid_reader = lambda name: sid_module.samba_public_sid(name, self.private["SECRETS"])
        self.handler.database_identity = self.databases
        self.handler.identity_holders = lambda: []
        self.handler.sessions = lambda *args: {"available": True, "establishedTcpCount": 0, "synRecvTcpCount": 0,
            "smbSessionCount": 0, "treeConnectionCount": 0, "openFileCount": 0, "byteLockOpenFileCount": 0,
            "lockingDatabasesAligned": True, "safeToRebind": True}
        self.handler.run = self.run_command
        self.native.units = self.units
        self.native.collector = self.collect
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.newkey = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.original = capsule.encrypt(self.empty_payload(), self.public(self.key), self.scope["instanceUuid"]+":"+self.scope["operationUuid"])
        self.raw_before = {name: path.read_bytes() for name, path in self.private.items()}
        self.lock_path = self.root/"writer.lock"; self.lock_path.touch(); self.lock_path.chmod(0o600)
        self.descriptor = os.open(self.lock_path, os.O_RDWR)
        try:
            self.saved9 = fcntl.fcntl(9, fcntl.F_DUPFD_CLOEXEC, 16)
        except OSError:
            self.saved9 = None
        os.dup2(self.descriptor, 9)
        fcntl.flock(9, fcntl.LOCK_EX|fcntl.LOCK_NB)
        self.environment = patch.dict(os.environ, {"ABLESTACK_STORAGE_WRITER_LOCK_FD": "9",
            "ABLESTACK_STORAGE_WRITER_LOCK_FILE": str(self.lock_path), "ABLESTACK_STORAGE_VOLUME_OPERATIONS": str(self.root/"volumeops")})
        self.environment.start(); self.addCleanup(self.environment.stop); self.addCleanup(self.close_lock)

    def close_lock(self):
        os.close(9); os.close(self.descriptor)
        if self.saved9 is not None:
            os.dup2(self.saved9, 9); os.close(self.saved9)

    def create_sid(self, path):
        lib = ctypes.CDLL("libtdb.so.1")
        lib.tdb_open.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint]; lib.tdb_open.restype = ctypes.c_void_p
        lib.tdb_store.argtypes = [ctypes.c_void_p, sid_module.PublicSidTdbData, sid_module.PublicSidTdbData, ctypes.c_int]
        lib.tdb_close.argtypes = [ctypes.c_void_p]
        db = lib.tdb_open(os.fsencode(path), 0, 0, os.O_RDWR|os.O_CREAT, 0o600)
        name = ("SECRETS/SID/STOR"+self.scope["instanceUuid"].replace("-", "")[:10].upper()).encode()
        raw = bytes([1,4,0,0,0,0,0,5])+struct.pack("<15I", 21, 11, 22, 33, *([0]*11))
        key = ctypes.create_string_buffer(name); value = ctypes.create_string_buffer(raw)
        self.assertEqual(0, lib.tdb_store(db, sid_module.PublicSidTdbData(ctypes.cast(key, ctypes.c_void_p), len(name)),
                                       sid_module.PublicSidTdbData(ctypes.cast(value, ctypes.c_void_p), len(raw)), 1))
        lib.tdb_close(db); path.chmod(0o600)

    def public(self, key):
        return key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()

    def pem(self, key):
        return key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()

    def empty_payload(self):
        return {"schemaVersion": 1, "files": {path: {"absent": True} for path in capsule.FILES},
                "accounts": {path: [] for path in capsule.ACCOUNT_FILES}, "nvmeHosts": {}}

    def collect(self, names):
        self.counter += 1
        value = self.empty_payload()
        for label, path in self.private.items():
            raw = path.read_bytes()
            value["files"][module.IDENTITY_DATABASES[label]] = {"data": base64.b64encode(raw).decode(),
                "sha256": hashlib.sha256(raw).hexdigest(), "mode": 0o600, "uid": 0, "gid": 0}
        return value

    def read(self, path):
        mapped = next((self.private[label] for label, actual in module.IDENTITY_DATABASES.items() if actual == path), None)
        if mapped:
            return capsule.regular_file(mapped)
        if path == "/etc/samba/smb.conf":
            return b"PUBLIC FIXTURE CONFIG", self.private["PASSDB"].stat()
        return capsule.regular_file(path)

    def databases(self):
        return {label: {"path": module.IDENTITY_DATABASES[label], "present": True, "device": path.stat().st_dev,
            "inode": path.stat().st_ino, "uid": 0, "gid": 0, "mode": "0600"} for label, path in self.private.items()}

    def command(self, args, payload=None):
        if args == ("operation", "generation", "status"):
            return self.gen.status()
        if args == ("operation", "maintenance", "status"):
            return {"success": True, "bootHeld": False}
        if args == ("operation", "generation", "render-status"):
            return {"success": True, "current": None, "activation": None}
        raise AssertionError(args)

    def units(self, stopped=False, require_master=True):
        if stopped:
            if self.active:
                raise ValueError("still active fixture")
            return []
        return copy.deepcopy(self.units_value) if self.active else []

    def run_command(self, args):
        self.calls.append(args)
        if args[:2] == ["testparm", "-s"]:
            return "SERVER"
        if args[:3] == ["ss", "-H", "-ltnp"]:
            return 'LISTEN 0 0 0.0.0.0:445 0.0.0.0:* users:(("smbd",pid=111,fd=30))' if self.active else ""
        if args[:3] == ["ss", "-H", "-ntp"]:
            return ""
        if args[:2] == ["systemctl", "stop"]:
            if args[2] == "smbd.service":
                self.active = False
            return ""
        raise AssertionError(args)

    def approval(self):
        return {**self.request, "expectedReview": self.native.review(self.request), "maintenanceApproved": True,
                "instanceName": "fixture-instance", "confirmation": "fixture-instance",
                "originalCapsule": self.original, "credentialPrivateKey": self.pem(self.key)}

    def stop(self):
        request = self.approval()
        with patch.object(self.handler, "open_master_handles", side_effect=lambda masters: {row["pid"]: os.open("/dev/null", os.O_RDONLY) for row in masters}), \
             patch.object(module.signal, "pidfd_send_signal"):
            result = self.native.quiesce(request)
        self.assertTrue(result["currentStoppedVerified"])
        return request

    def exported(self):
        approval = self.stop()
        request = {**self.request, "currentReviewHash": approval["expectedReview"]["currentReviewHash"], "publicKey": self.public(self.newkey)}
        return approval, request, self.native.export(request)

    def test_review_real_readonly_sid_current_source_scope_hash_and_no_effects(self):
        review = self.native.review(self.request)
        self.assertEqual(1, review["schemaVersion"]); self.assertFalse(review["currentFacts"]["loadedDaemonSidVerified"])
        self.assertEqual(2, len(review["currentFacts"]["databases"]))
        self.assertEqual(1, len(review["currentFacts"]["publicNamespaceSids"]))
        second = self.native.review(self.request); self.assertEqual(review["currentReviewHash"], second["currentReviewHash"])
        self.assertEqual(self.raw_before, {name: path.read_bytes() for name, path in self.private.items()})
        self.assertFalse(self.native.root.exists()); self.assertFalse(any("stop" in row for row in self.calls))

    def test_foreign_scope_previous_nonnull_smb_and_active_maintenance_reject_before_effects(self):
        for mutate in ("scope", "previous", "smb", "maintenance"):
            with self.subTest(mutate=mutate):
                pending = generation.read_json(self.gen.pending)
                original = copy.deepcopy(pending)
                if mutate == "scope": pending["operationUuid"] = str(uuid.uuid4())
                if mutate == "previous": pending["previous"] = {}
                if mutate in ("scope", "previous"): generation.atomic_json(self.gen.pending, pending)
                if mutate == "smb": generation.atomic_json(self.config/"desired-state/smb-share-apply.json", {})
                saved = self.native.command
                if mutate == "maintenance":
                    self.native.command = lambda args, payload=None: {"bootHeld": True} if args[1:]==("maintenance","status") else saved(args,payload)
                with self.assertRaises(ValueError): self.native.review(self.request)
                generation.atomic_json(self.gen.pending, original)
                (self.config/"desired-state/smb-share-apply.json").unlink(missing_ok=True); self.native.command = saved
        self.assertFalse(any("stop" in row for row in self.calls))

    def test_original_cipher_scope_key_or_nonempty_source_reject_before_stop(self):
        for bad in ("key", "scope", "database"):
            request = self.approval()
            if bad == "key": request["credentialPrivateKey"] = self.pem(self.newkey)
            if bad == "scope": request["originalCapsule"] = capsule.encrypt(self.empty_payload(), self.public(self.key), str(uuid.uuid4())+":"+self.scope["operationUuid"])
            if bad == "database":
                payload = self.collect([]); request["originalCapsule"] = capsule.encrypt(payload, self.public(self.key), self.scope["instanceUuid"]+":"+self.scope["operationUuid"])
            with patch.object(module.signal, "pidfd_send_signal") as send:
                with self.assertRaises(Exception): self.native.quiesce(request)
                send.assert_not_called()
        self.assertFalse(self.native.root.exists()); self.assertFalse(self.native.journal(self.scope))

    def test_unheld_named_fd9_cannot_gain_writer_authority(self):
        request = self.approval(); fcntl.flock(9, fcntl.LOCK_UN)
        with patch.object(module.signal, "pidfd_send_signal") as send:
            with self.assertRaises(ValueError): self.native.quiesce(request)
            send.assert_not_called()
        fcntl.flock(9, fcntl.LOCK_EX|fcntl.LOCK_NB); self.assertFalse(self.native.journal(self.scope))

    def test_quiesce_journal_precedes_exact_stop_and_no_default_restart(self):
        request = self.approval()
        original_run = self.handler.run
        def observed(args):
            if args[:2] == ["systemctl", "stop"]:
                self.assertEqual("QUIESCING", self.native.journal(self.scope)["phase"])
            return original_run(args)
        self.handler.run = observed
        with patch.object(self.handler, "open_master_handles", return_value={111: os.open("/dev/null", os.O_RDONLY), 222: os.open("/dev/null", os.O_RDONLY)}), \
             patch.object(module.signal, "pidfd_send_signal"):
            result = self.native.quiesce(request)
        self.assertTrue(result["currentStoppedVerified"]); self.assertFalse(result["originalIdentityRestored"])
        self.assertEqual(["nmbd.service", "smbd.service"], [row[2] for row in self.calls if row[:2]==["systemctl","stop"]])
        self.assertFalse(any("start" in row or "restart" in row for row in self.calls))

    def test_current_real_crypto_distinct_key_purpose_cache_and_original_bytes_preserved(self):
        original = copy.deepcopy(self.original); approval, request, exported = self.exported()
        aad = self.scope["instanceUuid"]+":"+self.scope["operationUuid"]+":CURRENT_LOCAL_AFTERSTOP"
        decoded = capsule.decrypt(exported["capsule"], self.pem(self.newkey), aad)
        self.assertEqual(module.CURRENT_SMB_KIND, decoded["kind"]); self.assertEqual(self.original, original)
        with self.assertRaises(Exception): capsule.decrypt(exported["capsule"], self.pem(self.key), aad)
        with self.assertRaises(Exception): capsule.decrypt(exported["capsule"], self.pem(self.newkey), self.scope["instanceUuid"]+":"+self.scope["operationUuid"])
        self.assertEqual(exported, self.native.export(request)); self.assertEqual(1, self.counter)
        with self.assertRaises(ValueError): self.native.export({**request, "publicKey": self.public(self.key)})
        public = json.dumps(exported); self.assertNotIn("privateFingerprints", public)
        self.assertEqual(self.raw_before, {name: path.read_bytes() for name, path in self.private.items()})

    def test_changed_private_bytes_or_foreign_reference_deny_retain(self):
        approval, request, exported = self.exported()
        retain = {**self.request, "currentReviewHash": request["currentReviewHash"], "currentIdentityReference": exported["currentIdentityReference"]}
        with self.assertRaises(ValueError): self.native.retain({**retain, "currentIdentityReference": {"operationUuid": self.scope["operationUuid"], "sha256": "f"*64}})
        self.private["PASSDB"].write_bytes(b"REPLACEMENT_IN_SAME_INODE")
        with self.assertRaises(ValueError): self.native.retain(retain)
        self.assertEqual("EXPORTED", self.native.journal(self.scope)["phase"])

    def test_retained_terminal_and_new_writer_baseline_preserve_current_and_empty_source(self):
        approval, request, exported = self.exported()
        retain = {**self.request, "currentReviewHash": request["currentReviewHash"], "currentIdentityReference": exported["currentIdentityReference"]}
        self.assertTrue(self.native.retain(retain)["currentIdentityRetained"])
        self.gen.execute("rollback", self.scope)
        result = self.native.retain(retain, terminal=True)
        self.assertFalse(result["originalIdentityRestored"]); self.assertTrue(result["originalConfigurationSourceRestored"])
        self.assertEqual(result, self.native.retain(retain, terminal=True))
        self.assertFalse(self.gen.pending.exists())
        inspected = self.native.inspect_retained({**self.scope, "revision": 3}, self.current)
        self.assertEqual("LOCAL_IDENTITY_RETAINED_WITHOUT_SMB_CONFIG", inspected["identityBaselineKind"])
        new_scope = {**self.scope, "operationUuid": str(uuid.uuid4())}
        self.gen.execute("begin", new_scope)
        self.assertEqual(new_scope, self.native.inspect_retained(new_scope, self.current)["scope"])
        foreign = {**new_scope, "operationUuid": str(uuid.uuid4())}
        with self.assertRaises(ValueError): self.native.inspect_retained(foreign, self.current)
        self.assertEqual(self.raw_before, {name: path.read_bytes() for name, path in self.private.items()})

    def test_lost_stop_response_or_journal_publication_resumes_only_same_process_authority(self):
        request = self.stop(); previous_calls = len([row for row in self.calls if row[:2]==["systemctl","stop"]])
        self.native.quiesce(request)
        self.assertEqual(previous_calls, len([row for row in self.calls if row[:2]==["systemctl","stop"]]))
        journal = self.native.journal(self.scope); journal["phase"] = "QUIESCING"; self.native.journal(self.scope, journal)
        with patch.object(module.signal, "pidfd_send_signal") as send:
            self.assertTrue(self.native.quiesce(request)["currentStoppedVerified"]); send.assert_not_called()
        journal["phase"] = "RECOVERY_REQUIRED"; self.native.journal(self.scope, journal)
        self.active = True; self.units_value[0]["startTicks"] = "FOREIGN"
        with patch.object(module.signal, "pidfd_send_signal") as send:
            with self.assertRaises(ValueError): self.native.quiesce(request)
            send.assert_not_called()

    def test_stale_review_boolean_approval_or_boot_change_rejects_without_stop(self):
        request = self.approval()
        for field, value in (("maintenanceApproved", "true"), ("confirmation", "foreign")):
            with self.assertRaises(ValueError): self.native.quiesce({**request, field: value})
        request["expectedReview"]["generatedEpoch"] -= 61
        with self.assertRaises(ValueError): self.native.quiesce(request)
        self.assertFalse(any("stop" in row for row in self.calls))

    def test_actual_cli_dispatch_scope_rejection_uses_sealed_stdin_without_external_effects(self):
        for action in ("current-review", "current-quiesce", "current-export", "current-retain", "current-verify"):
            result = subprocess.run(["bash", str(CLI), "smb", "identity", action, "/dev/stdin"],
                                    input=json.dumps(self.scope), capture_output=True, text=True, timeout=20, pass_fds=(9,))
            self.assertEqual(1, result.returncode, (action, result.stdout, result.stderr))
            self.assertEqual("SMB_CURRENT_RETENTION_REJECTED", json.loads(result.stdout)["errorCode"])
            self.assertNotIn("Traceback", result.stderr)

    def test_package_hash_and_actual_unit_proc_config_owner_inode_are_not_caller_authority(self):
        package = self.root/"package"; package.mkdir()
        data = {}
        for unit, pid in (("smbd.service", 111), ("nmbd.service", 222)):
            exe = "/usr/sbin/"+unit[:-8]; vendor = "/lib/systemd/system/"+unit
            for name in (exe, vendor):
                path = package/Path(name).name; path.write_bytes(("PUBLIC PACKAGE "+name).encode()); path.chmod(0o600); data[name] = path
            gate = "/etc/systemd/system/"+unit+".d/ablestack-storage-gate.conf"
            path = package/(unit+".gate"); path.write_bytes(b"[Service]\nExecCondition=/usr/local/bin/ablestack-storagectl operation generation render-boot-gate %n\n"); path.chmod(0o600); data[gate] = path
            proc = self.handler.process_root/str(pid); proc.mkdir(); (proc/"exe").symlink_to(data[exe])
            (proc/"comm").write_text(unit[:-8]); (proc/"cgroup").write_text("0::/system.slice/"+unit)
            (proc/"cmdline").write_bytes((exe+"\0--foreground\0--no-process-group\0").encode())
            (proc/"stat").write_text(str(pid)+" ("+unit[:-8]+") "+" ".join(["S"]+["0"]*18+["START"]))
        manifest = package/"md5sums"
        manifest.write_text("\n".join(hashlib.md5(path.read_bytes()).hexdigest()+"  "+name.lstrip("/") for name,path in data.items() if ".d/" not in name))
        manifest.chmod(0o600); data["/var/lib/dpkg/info/samba.md5sums"] = manifest
        self.native.reader = lambda path: capsule.regular_file(data[path])
        def observation(args):
            unit = args[2]
            if "--property=MainPID" in args: return "111" if unit=="smbd.service" else "222"
            if "--property=FragmentPath" in args: return "/lib/systemd/system/"+unit
            if "--property=DropInPaths" in args: return "/etc/systemd/system/"+unit+".d/ablestack-storage-gate.conf"
            raise AssertionError(args)
        self.handler.run = observation
        actual_realpath = os.path.realpath
        def process_link(path):
            for pid,name in ((111,"smbd"),(222,"nmbd")):
                if str(path)==str(self.handler.process_root/str(pid)/"exe"): return "/usr/sbin/"+name
            return actual_realpath(path)
        with patch.object(module.os.path,"realpath",side_effect=process_link):
            rows = module.SmbCurrentRetention.units(self.native)
            self.assertEqual(2,len(rows)); self.assertEqual("START",rows[0]["startTicks"])
            (self.handler.process_root/"111"/"cmdline").write_bytes(b"/usr/sbin/smbd\0--configfile=/tmp/foreign\0")
            with self.assertRaises(ValueError): module.SmbCurrentRetention.units(self.native)
            (self.handler.process_root/"111"/"cmdline").write_bytes(b"/usr/sbin/smbd\0--foreground\0--no-process-group\0")
            data["/usr/sbin/smbd"].write_bytes(b"REPLACED PACKAGE")
            with self.assertRaises(ValueError): module.SmbCurrentRetention.units(self.native)

    def test_session_race_keeps_recovery_and_never_restarts_or_exports_plaintext(self):
        request = self.approval()
        positive = request["expectedReview"]["currentFacts"]["sessions"]
        observations = iter([positive, positive, {"safeToRebind": False}])
        self.handler.sessions = lambda *args: next(observations)
        with patch.object(self.handler,"open_master_handles",return_value={111:os.open("/dev/null",os.O_RDONLY),222:os.open("/dev/null",os.O_RDONLY)}),patch.object(module.signal,"pidfd_send_signal") as send:
            with self.assertRaises(ValueError): self.native.quiesce(request)
        self.assertEqual("RECOVERY_REQUIRED",self.native.journal(self.scope)["phase"])
        self.assertFalse(any(row[:2]==["systemctl","stop"] for row in self.calls))
        self.assertFalse(any(call.args[1]==module.signal.SIGTERM for call in send.call_args_list))
        self.assertFalse(self.native.path(self.scope,"cipher").exists())

    def test_active_formatter_and_foreign_private_fd_are_rejected_without_identity_changes(self):
        operations = self.root/"volumeops";operations.mkdir()
        generation.atomic_json(operations/"format.json",{"formatterActive":True})
        with self.assertRaises(ValueError): self.native.review(self.request)
        (operations/"format.json").unlink()
        process = self.handler.process_root/"777";process.mkdir();(process/"comm").write_text("foreign");(process/"cgroup").write_text("0::/foreign")
        (process/"fd").mkdir();(process/"fd/4").symlink_to("/var/lib/samba/private/secrets.tdb")
        # A real protected open FD symlink is observed; pathname mapping here
        # represents an unknown process in the scoped synthetic proc fixture.
        original_stat = Path.stat
        def observed_stat(path,*args,**kwargs):
            if str(path)==str(process/"fd/4"):return self.private["SECRETS"].stat()
            return original_stat(path,*args,**kwargs)
        with patch.object(module.Path,"stat",new=observed_stat):
            with self.assertRaises(ValueError): self.native.review(self.request)
        self.assertFalse(any("stop" in row for row in self.calls));self.assertEqual(self.raw_before,{name:path.read_bytes() for name,path in self.private.items()})

    def test_export_response_loss_before_phase_publication_reuses_cipher_without_recollect(self):
        approval, request, exported = self.exported()
        journal = self.native.journal(self.scope); journal["phase"]="STOPPED";self.native.journal(self.scope,journal)
        self.assertEqual(exported,self.native.export(request));self.assertEqual(1,self.counter)
        self.boot=str(uuid.uuid4())
        with self.assertRaises(ValueError):self.native.export(request)


    def test_actual_embedded_codec_dispatcher_positive_review_stop_crypto_retain_and_terminal(self):
        import ast,io
        from contextlib import redirect_stdout
        program=CLI.read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        tree=ast.parse(program);definitions=[]
        for node in tree.body:
            if isinstance(node,(ast.Import,ast.ImportFrom,ast.FunctionDef,ast.ClassDef)):
                definitions.append(node)
            elif isinstance(node,ast.Assign) and not any(isinstance(target,ast.Name) and target.id in ("request","action","scope") for target in node.targets):
                definitions.append(node)
        namespace={"__name__":"current_codec_fixture"}
        exec(compile(ast.Module(body=definitions,type_ignores=[]),"<actual-PYIDENTITY-definitions>","exec"),namespace)
        cls=namespace["SmbCurrentRetention"];embedded=cls(CLI,command=self.command,handler=self.handler)
        embedded.__dict__.update(self.native.__dict__)
        embedded.sid_reader=lambda name: namespace["samba_public_sid"](name,self.private["SECRETS"])
        embedded.encryptor=namespace["encrypt"];embedded.decryptor=namespace["decrypt"];embedded.validator=namespace["validate_payload"]
        namespace["SmbCurrentRetention"]=lambda *args,**kwargs:embedded
        dispatch=next(node for node in tree.body if isinstance(node,ast.If) and isinstance(node.test,ast.Compare)
                      and isinstance(node.test.left,ast.Name) and node.test.left.id=="action")
        def invoke(action,request):
            namespace.update({"action":action,"request":request,"scope":self.scope["instanceUuid"]+":"+self.scope["operationUuid"]})
            output=io.StringIO()
            with patch.object(sys,"argv",["actual-script",action,"/dev/stdin",str(CLI)]),redirect_stdout(output):
                exec(compile(ast.Module(body=[dispatch],type_ignores=[]),"<actual-PYIDENTITY-dispatch>","exec"),namespace)
            return json.loads(output.getvalue())
        review=invoke("current-review",self.request)
        stop_request={**self.approval(),"expectedReview":review}
        with patch.object(self.handler,"open_master_handles",return_value={111:os.open("/dev/null",os.O_RDONLY),222:os.open("/dev/null",os.O_RDONLY)}),patch.object(module.signal,"pidfd_send_signal"):
            stopped=invoke("current-quiesce",stop_request)
        self.assertTrue(stopped["currentStoppedVerified"])
        exported=invoke("current-export",{**self.request,"currentReviewHash":review["currentReviewHash"],"publicKey":self.public(self.newkey)})
        aad=self.scope["instanceUuid"]+":"+self.scope["operationUuid"]+":CURRENT_LOCAL_AFTERSTOP"
        self.assertEqual(module.CURRENT_SMB_KIND,namespace["decrypt"](exported["capsule"],self.pem(self.newkey),aad)["kind"])
        request={**self.request,"currentReviewHash":review["currentReviewHash"],"currentIdentityReference":exported["currentIdentityReference"]}
        self.assertTrue(invoke("current-retain",request)["currentIdentityRetained"])
        self.gen.execute("rollback",self.scope)
        self.assertTrue(invoke("current-verify",request)["originalConfigurationSourceRestored"])
        self.assertEqual(self.raw_before,{name:path.read_bytes() for name,path in self.private.items()})


    def test_master_reassignment_after_pidfd_pinning_rejects_before_first_signal(self):
        request=self.approval()
        def handles(masters):
            self.units_value[0]["startTicks"]="REASSIGNED"
            return {row["pid"]:os.open("/dev/null",os.O_RDONLY) for row in masters}
        with patch.object(self.handler,"open_master_handles",side_effect=handles),patch.object(module.signal,"pidfd_send_signal") as send:
            with self.assertRaises(ValueError):self.native.quiesce(request)
            send.assert_not_called()
        self.assertFalse(any(row[:2]==["systemctl","stop"] for row in self.calls))


    def test_afterstop_public_config_change_blocks_export_retain_and_terminal_baseline(self):
        approval=self.stop();stable_reader=self.native.reader
        request={**self.request,"currentReviewHash":approval["expectedReview"]["currentReviewHash"],"publicKey":self.public(self.newkey)}
        def changed(path):
            return (b"FOREIGN PUBLIC CONFIG SAME NAMESPACE",self.private["PASSDB"].stat()) if path=="/etc/samba/smb.conf" else stable_reader(path)
        self.native.reader=changed
        with self.assertRaises(ValueError):self.native.export(request)
        self.assertFalse(self.native.path(self.scope,"cipher").exists());self.assertEqual(0,self.counter)
        self.native.reader=stable_reader;exported=self.native.export(request)
        retain={**self.request,"currentReviewHash":request["currentReviewHash"],"currentIdentityReference":exported["currentIdentityReference"]}
        self.native.reader=changed
        with self.assertRaises(ValueError):self.native.retain(retain)
        self.native.reader=stable_reader;self.native.retain(retain);self.gen.execute("rollback",self.scope)
        self.native.reader=changed
        with self.assertRaises(ValueError):self.native.retain(retain,terminal=True)
        with self.assertRaises(ValueError):self.native.inspect_retained({**self.scope,"revision":3},self.current)
        self.assertEqual(self.raw_before,{name:path.read_bytes() for name,path in self.private.items()})

