#!/usr/bin/env python3
"""Initial witness tests: real generation engine; only kernel/tool observations are fixtures."""
import copy
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import types
import unittest
import uuid
from unittest import mock

LIB = Path(__file__).resolve().parents[1] / "debian/usr/local/lib/ablestack-storage"
sys.path.insert(0, str(LIB))
from config_generation import Generation
from clone_initial_witness import CloneInitialWitness

ROOT_UUID = "11111111-1111-4111-8111-111111111111"
VM_UUID = "22222222-2222-4222-8222-222222222222"
INSTANCE = "33333333-3333-4333-8333-333333333333"
OPERATION = "44444444-4444-4444-8444-444444444444"
BOOT = Path("/proc/sys/kernel/random/boot_id").read_text().strip()

class InitialFixture:
    def __init__(self, data_count=4):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.commands = []
        self.hook = None
        self.fail_tool = None
        self.missing_tool = None
        self.udp = True
        self.bad_owner = False
        self.exited = set()
        self.put("/proc/sys/kernel/random/boot_id", BOOT + "\n")
        self.put("/sys/class/dmi/id/product_uuid", VM_UUID + "\n")
        self.put("/proc/modules", "target_core_mod 8192 1 - Live 0x0\niscsi_target_mod 8192 0 - Live 0x0\n")
        for name in ("target_core_mod", "iscsi_target_mod"):
            self.directory("/sys/module/" + name)
        self.put("/sys/kernel/config/target/dbroot", "/etc/rtslib-fb-target\n")
        uname = os.uname()
        self.put("/sys/kernel/config/target/version", "Target Engine Core ConfigFS Infrastructure v5.0 on "
                 + uname.sysname + "/" + uname.machine + " on " + uname.release + "\n")
        self.directory("/sys/kernel/config/target/iscsi/discovery_auth")
        self.put("/sys/kernel/config/target/iscsi/cpus_allowed_list", "0-3\n")
        self.put("/sys/kernel/config/target/iscsi/lio_version", "4.1\n")
        self.directory("/sys/kernel/config/target/core/alua/lu_gps/default_lu_gp")
        self.put("/etc/exports", "# Kernel export sample only\n")
        self.put("/etc/ganesha/ganesha.conf", "#NFS_CORE_PARAM {\n#Protocols=3,4;\n#}\n#EXPORT { Path=/example; }\n#LOG { }\n")
        vendor = "[global]\nworkgroup = WORKGROUP\n[homes]\n[printers]\npath=/var/tmp\n[print$]\npath=/var/lib/samba/printers\n"
        self.put("/etc/samba/smb.conf", vendor)
        self.put("/usr/share/samba/smb.conf", vendor)
        self.put("/etc/rsyslog.conf", "$ModLoad imudp\n$UDPServerRun 3914\n")
        self.owner(55, "sshd", "/usr/sbin/sshd")
        self.owner(66, "ntpd", "/usr/sbin/ntpd")
        self.owner(77, "rsyslogd", "/usr/sbin/rsyslogd")
        self.generation = Generation(state=self.path("/var/lib/ablestack-storage/config-generations"),
                                     configuration=self.path("/etc/ablestack-storage"))
        self.put("/etc/ablestack-storage/sharedfs-network.json", json.dumps({"schemaVersion": 1, "address": "192.0.2.10/24"}))
        rootfs = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        self.devices = [{"name": "/dev/sda", "path": "/dev/sda", "type": "disk", "size": 5242880000,
            "serial": ROOT_UUID.replace("-", "")[:20], "fstype": None, "uuid": None, "mountpoints": [],
            "children": [{"name": "/dev/sda1", "path": "/dev/sda1", "type": "part", "size": 5000000000,
                         "serial": None, "fstype": "ext4", "uuid": rootfs, "mountpoints": ["/"]}]}]
        self.mounts = [{"source": "/dev/sda1", "target": "/", "fstype": "ext4", "options": "rw", "uuid": rootfs}]
        for index in range(data_count):
            volume = str(uuid.uuid5(uuid.UUID(INSTANCE), "data-" + str(index)))
            path = "/dev/sd" + chr(ord("b") + index)
            filesystem = "ext4" if index < 2 else None
            fsuuid = str(uuid.uuid5(uuid.UUID(volume), "filesystem")) if filesystem else None
            target = "/srv/ablestack-storage/volumes/" + volume
            self.devices.append({"name": path, "path": path, "type": "disk", "size": 2147483648,
                "serial": volume.replace("-", "")[:20], "fstype": filesystem, "uuid": fsuuid,
                "mountpoints": [target] if filesystem else []})
            if filesystem:
                self.mounts.append({"source": path, "target": target, "fstype": filesystem, "options": "rw", "uuid": fsuuid})
        self.scope = {"instanceUuid": INSTANCE, "operationUuid": OPERATION, "revision": 1}

    def path(self, value):
        return self.root / value.lstrip("/")

    def directory(self, value):
        p = self.path(value);p.mkdir(parents=True, exist_ok=True, mode=0o700);return p

    def put(self, value, text):
        p = self.path(value);p.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        p.write_text(text);p.chmod(0o600);return p

    def owner(self, pid, name, executable):
        self.put("/proc/" + str(pid) + "/comm", name + "\n")
        self.put("/proc/" + str(pid) + "/stat", str(pid) + " (" + name + ") " + " ".join(["S"] + ["0"] * 18 + ["1234"]) + "\n")
        self.put(executable, "# fixture binary\n").chmod(0o755)
        os.symlink(executable, self.path("/proc/" + str(pid) + "/exe"))

    def request(self):
        return {**self.scope, "expectedBootId": BOOT, "expectedConfigurationSha256": self.generation.digest(),
            "expectedCurrentGeneration": self.generation.status()["generation"],
            "expectedPendingOperationUuid": self.generation.status()["pendingOperationUuid"],
            "expectedVmUuid": VM_UUID, "rootVolumeUuid": ROOT_UUID, "rootSizeBytes": 5242880000}

    def flatten(self):
        return [row for disk in self.devices for row in [disk] + disk.get("children", [])]

    def run(self, args, **kwargs):
        self.commands.append(args)
        if self.hook:
            self.hook(args)
        if args[0] == self.fail_tool:
            return types.SimpleNamespace(returncode=1, stdout="", stderr="fixture command failed")
        result = ""
        code = 0
        if args[0] == "lsblk":
            result = json.dumps({"blockdevices": copy.deepcopy(self.devices)})
        elif args[0] == "findmnt":
            result = json.dumps({"filesystems": [{"target": "/sys/kernel/config", "fstype": "configfs"}]
                                if "--types" in args else copy.deepcopy(self.mounts)})
        elif args[0] == "blkid":
            row = next(x for x in self.flatten() if x["path"] == args[-1])
            if row.get("fstype"):
                result = "TYPE=" + row["fstype"] + "\nUUID=" + row["uuid"] + "\n"
            else:
                code = 2
        elif args[0] == "testparm":
            result = "[global]\n[homes]\n[printers]\n[print$]\n"
        elif args[0] == "systemctl":
            active = {"ssh.service": 55, "ntpsec.service": 66, "rsyslog.service": 77}
            pid = active.get(args[2], 0)
            if self.bad_owner and pid:
                pid = 999
            result = ("LoadState=loaded\nActiveState=active\nSubState=running\nMainPID=" + str(pid) + "\n") if pid else "LoadState=not-found\nActiveState=inactive\nSubState=dead\nMainPID=0\n"
        elif args[0] == "ss":
            if args[-1] == "-ltnp":
                result = 'LISTEN 0 128 0.0.0.0:22 0.0.0.0:* users:(("sshd",pid=55,fd=3))\n'
            elif self.udp:
                result = 'UNCONN 0 0 0.0.0.0:123 0.0.0.0:* users:(("ntpd",pid=66,fd=3))\nUNCONN 0 0 0.0.0.0:3914 0.0.0.0:* users:(("rsyslogd",pid=77,fd=4))\n'
        elif args[0] != "exportfs":
            raise AssertionError("Unexpected fixture command: " + str(args))
        return types.SimpleNamespace(returncode=code, stdout=result, stderr="")

    def witness(self, **kwargs):
        return CloneInitialWitness(self.generation, self.root, self.run,
            lambda name: None if name == self.missing_tool else "/fixture/bin/" + name, **kwargs)

    def snapshot(self):
        return {str(p.relative_to(self.root)): (p.lstat().st_mode, os.readlink(p) if p.is_symlink() else p.read_bytes())
                for p in self.root.rglob("*") if p.is_file() or p.is_symlink()}

    def close(self):
        self.temporary.cleanup()

class CloneInitialWitnessTests(unittest.TestCase):
    def setUp(self):
        self.f = InitialFixture()
    def tearDown(self):
        self.f.close()

    def test_initial_real_generation_and_exact_defaults_have_no_effects(self):
        before = self.f.snapshot();r = self.f.witness().inspect(self.f.request())
        self.assertEqual(r["witnessBoundary"], "INITIAL_IDLE")
        self.assertEqual(r["generation"], {});self.assertEqual(r["runtimeRevision"], 0)
        self.assertFalse(r["initialSourceVerified"]);self.assertTrue(r["readOnly"])
        self.assertEqual(len(r["diskInventory"]), 5)
        self.assertEqual(r["rootIdentity"]["volumeUuid"], ROOT_UUID)
        self.assertTrue(all(x["volumeUuid"] is None for x in r["diskInventory"] if not x["isRoot"]))
        self.assertEqual(r["runtimeFacts"]["sambaConfigurationKind"], "INACTIVE_EXACT_VENDOR_DEFAULT")
        self.assertFalse(r["runtimeFacts"]["sambaHomesDataIndependenceClaimed"])
        self.assertEqual({x["name"] for x in r["runtimeFacts"]["controlPlaneOwnerBindings"]}, {"sshd", "ntpd", "rsyslogd"})
        self.assertEqual(before, self.f.snapshot());self.assertFalse(self.f.generation.root.exists())

    def test_own_pending_uses_real_begin_and_verify(self):
        self.f.generation.execute("begin", self.f.scope)
        for phase in ("PREPARED", "VERIFIED"):
            if phase == "VERIFIED":self.f.generation.execute("verify", self.f.scope)
            before = self.f.snapshot();r = self.f.witness().inspect(self.f.request())
            self.assertEqual(r["witnessBoundary"], "OWN_PENDING");self.assertEqual(r["generationStatus"], "PENDING")
            self.assertEqual(before, self.f.snapshot())

    def test_committed_reuse_requires_real_frozen_generation(self):
        for action in ("begin", "verify", "commit", "finish"):self.f.generation.execute(action, self.f.scope)
        before = self.f.snapshot();r = self.f.witness().inspect(self.f.request())
        self.assertEqual(r["witnessBoundary"], "COMMITTED_BASELINE");self.assertEqual(r["generationStatus"], "IN_SYNC")
        self.assertEqual(r["generation"], self.f.generation.frozen(self.f.scope)["generation"])
        self.assertEqual(before, self.f.snapshot());self.assertFalse(r["initialSourceVerified"])

    def test_data_cardinality_is_actual_not_hardcoded_five(self):
        self.f.close();self.f = InitialFixture(1)
        self.assertEqual(len(self.f.witness().inspect(self.f.request())["diskInventory"]), 2)

    def test_foreign_unmanaged_iscsi_target_rejected(self):
        self.f.directory("/sys/kernel/config/target/iscsi/iqn.2026-10.foreign:orphan")
        with self.assertRaisesRegex(ValueError, "FOREIGN_ISCSI"):self.f.witness().inspect(self.f.request())

    def test_orphan_lio_backstore_rejected(self):
        self.f.directory("/sys/kernel/config/target/core/iblock_0/unlinked-foreign")
        with self.assertRaisesRegex(ValueError, "ORPHAN_BACKSTORE"):self.f.witness().inspect(self.f.request())

    def test_orphan_unmanaged_nvme_host_rejected(self):
        self.f.directory("/sys/kernel/config/nvmet/hosts/foreign")
        self.f.directory("/sys/kernel/config/nvmet/ports");self.f.directory("/sys/kernel/config/nvmet/subsystems")
        with self.assertRaisesRegex(ValueError, "FOREIGN_OR_ORPHAN_NVME"):self.f.witness().inspect(self.f.request())

    def test_loaded_nvme_missing_namespace_is_not_empty(self):
        self.f.directory("/sys/module/nvmet")
        with self.assertRaisesRegex(ValueError, "LOADED_NVME_NAMESPACE_UNOBSERVED"):self.f.witness().inspect(self.f.request())

    def test_saved_foreign_target_rejected_even_when_kernel_empty(self):
        self.f.put("/etc/target/saveconfig.json", json.dumps({"targets": [{"wwn": "iqn.foreign"}], "storage_objects": []}))
        with self.assertRaisesRegex(ValueError, "SAVED_TARGET_NOT_EMPTY"):self.f.witness().inspect(self.f.request())

    def test_static_nfs_export_rejected(self):
        self.f.put("/etc/exports", "/srv/data *(rw)\n")
        with self.assertRaisesRegex(ValueError, "FOREIGN_STATIC_CONFIGURATION"):self.f.witness().inspect(self.f.request())

    def test_ganesha_export_and_unknown_include_rejected(self):
        for value in ("EXPORT { Path=/foreign; }\n", '%include "/foreign/*.conf"\n'):
            self.f.put("/etc/ganesha/ganesha.conf", value)
            with self.assertRaisesRegex(ValueError, "FOREIGN_STATIC_CONFIGURATION"):self.f.witness().inspect(self.f.request())

    def test_vendor_same_share_names_with_changed_config_rejected(self):
        with self.f.path("/etc/samba/smb.conf").open("a") as f:f.write("unknown = enabled\n")
        with self.assertRaisesRegex(ValueError, "FOREIGN_SAMBA_CONFIGURATION"):self.f.witness().inspect(self.f.request())

    def test_missing_tool_and_failed_ss_are_unknown(self):
        self.f.missing_tool = "ss"
        with self.assertRaisesRegex(ValueError, "REQUIRED_TOOL_MISSING"):self.f.witness().inspect(self.f.request())
        self.f.missing_tool = None;self.f.fail_tool = "ss"
        with self.assertRaisesRegex(ValueError, "COMMAND_UNAVAILABLE"):self.f.witness().inspect(self.f.request())

    def test_known_port_without_exact_unit_owner_rejected(self):
        self.f.bad_owner = True
        with self.assertRaisesRegex(ValueError, "OWNER_UNIT_OR_PID_CHANGED"):self.f.witness().inspect(self.f.request())

    def test_wrong_boot_root_and_ambiguous_serial_rejected(self):
        request = self.f.request();request["expectedBootId"] = str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError, "BOOT_OR_VM_CHANGED"):self.f.witness().inspect(request)
        self.f.devices[0]["serial"] = "f" * 20
        with self.assertRaisesRegex(ValueError, "ROOT_IDENTITY_CHANGED"):self.f.witness().inspect(self.f.request())
        self.f.devices[0]["serial"] = self.f.devices[1]["serial"]
        with self.assertRaises(ValueError):self.f.witness().inspect(self.f.request())

    def test_nonempty_posix_and_endpoint_canonical_are_not_initial(self):
        for name in ("posix-directory-policies.json", "network-endpoints.json"):
            p = self.f.put("/etc/ablestack-storage/" + name, '{"foreign":true}')
            with self.assertRaisesRegex(ValueError, "PROTOCOL_CANONICAL_NOT_EMPTY"):self.f.witness().inspect(self.f.request())
            p.unlink()

    def test_pending_foreign_or_changed_current_rejected(self):
        self.f.generation.execute("begin", self.f.scope)
        request = self.f.request();request["operationUuid"] = str(uuid.uuid4())
        with self.assertRaisesRegex(ValueError, "REQUEST_PENDING"):self.f.witness().inspect(request)

    def test_boot_and_disk_drift_during_observation_rejected(self):
        called = []
        def changed(args):
            if args[0] == "ss" and not called:
                called.append(True);self.f.put("/proc/sys/kernel/random/boot_id", str(uuid.uuid4()))
        self.f.hook = changed
        with self.assertRaisesRegex(ValueError, "OBSERVATION_CHANGED"):self.f.witness().inspect(self.f.request())

    def test_protocol_process_cannot_hide_without_listener(self):
        self.f.put("/proc/88/comm", "ganesha.nfsd\n")
        with self.assertRaisesRegex(ValueError, "PROTOCOL_PROCESS_ACTIVE"):self.f.witness().inspect(self.f.request())

    def test_process_permission_error_not_empty(self):
        path = self.f.put("/proc/88/comm", "unrelated\n");original = Path.read_text
        def read(p, *a, **k):
            if p == path:raise PermissionError("fixture")
            return original(p, *a, **k)
        with mock.patch.object(Path, "read_text", read):
            with self.assertRaisesRegex(ValueError, "PROCESS_OBSERVATION_UNKNOWN"):self.f.witness().inspect(self.f.request())

    def test_exited_pid_requires_absence_in_later_snapshot(self):
        path = self.f.put("/proc/88/comm", "unrelated\n");original = Path.read_text
        def read(p, *a, **k):
            if p == path:
                path.unlink();path.parent.rmdir();raise FileNotFoundError("fixture")
            return original(p, *a, **k)
        with mock.patch.object(Path, "read_text", read):
            r = self.f.witness().inspect(self.f.request())
        self.assertEqual(r["processObservation"]["exitedPidObservations"], [88])

    def test_data_filesystem_drift_is_rejected_without_writing_generation(self):
        done = []
        def changed(args):
            if args[0] == "ss" and not done:
                done.append(True);self.f.devices[1]["uuid"] = str(uuid.uuid4())
                self.f.mounts[1]["uuid"] = self.f.devices[1]["uuid"]
        self.f.hook = changed
        with self.assertRaisesRegex(ValueError, "OBSERVATION_CHANGED"):
            self.f.witness().inspect(self.f.request())
        self.assertFalse(self.f.generation.root.exists())

    def test_canonical_drift_during_observation_rejected(self):
        done = []
        def changed(args):
            if args[0] == "ss" and not done:
                done.append(True);self.f.put("/etc/ablestack-storage/sharedfs-network.json", '{"foreign":true}')
        self.f.hook = changed
        with self.assertRaisesRegex(ValueError, "CANONICAL_CHANGED"):
            self.f.witness().inspect(self.f.request())

    def test_exited_process_reappearance_cannot_be_ignored(self):
        path = self.f.put("/proc/88/comm", "unrelated\\n");original = Path.read_text;gone = []
        def read(p, *a, **k):
            if p == path and not gone:
                gone.append(True);path.unlink();path.parent.rmdir();raise FileNotFoundError("fixture")
            return original(p, *a, **k)
        def reappear(args):
            if args[0] == "ss" and gone and not path.exists():
                self.f.put("/proc/88/comm", "unrelated\\n")
        self.f.hook = reappear
        with mock.patch.object(Path, "read_text", read):
            with self.assertRaisesRegex(ValueError, "EXITED_PROCESS_REAPPEARED"):
                self.f.witness().inspect(self.f.request())

    def test_known_daemon_on_storage_or_unknown_port_is_rejected(self):
        original = self.f.run
        def different(args, **kwargs):
            result = original(args, **kwargs)
            if args[0] == "ss" and args[-1] == "-lunp":
                result.stdout = result.stdout.replace(":123 ", ":4420 ")
            return result
        with self.assertRaisesRegex(ValueError, "UNKNOWN_LISTENER_OWNER"):
            CloneInitialWitness(self.f.generation, self.f.root, different, lambda name: "/fixture/" + name).inspect(self.f.request())

    def test_frozen_artifact_drift_cannot_fake_committed_baseline(self):
        for action in ("begin", "verify", "commit", "finish"):self.f.generation.execute(action, self.f.scope)
        path = self.f.generation.root / (OPERATION + ".json")
        value = json.loads(path.read_text());value["desired"]["posix-directory-policies.json"] = {"foreign": True}
        path.write_text(json.dumps(value));path.chmod(0o600)
        with self.assertRaises(ValueError):self.f.witness().inspect(self.f.request())

    def test_control_service_dropped_uid_is_bound_by_unit_pid_and_exe(self):
        proc = self.f.path("/proc/66")
        real_lstat, real_stat, real_fstat = Path.lstat, Path.stat, os.fstat
        identities = {(info.st_dev, info.st_ino) for info in (real_lstat(proc), real_lstat(proc / "stat"))}
        observed = set()
        def dropped_uid(info, method):
            if (info.st_dev, info.st_ino) in identities:
                observed.add(method)
                fields = {name: getattr(info, name) for name in dir(info) if name.startswith("st_")}
                fields.update(st_uid=1234, st_gid=1234)
                return types.SimpleNamespace(**fields)
            return info
        def named_lstat(path, *args, **kwargs):
            return dropped_uid(real_lstat(path, *args, **kwargs), "lstat")
        def named_stat(path, *args, **kwargs):
            return dropped_uid(real_stat(path, *args, **kwargs), "stat")
        def opened_stat(descriptor):
            return dropped_uid(real_fstat(descriptor), "fstat")
        with mock.patch.object(Path, "lstat", named_lstat), mock.patch.object(Path, "stat", named_stat), mock.patch.object(os, "fstat", opened_stat):
            r = self.f.witness().inspect(self.f.request())
        ntp = next(x for x in r["runtimeFacts"]["controlPlaneOwnerBindings"] if x["name"] == "ntpd")
        self.assertEqual(ntp["processUid"], 1234)
        self.assertTrue({"lstat", "fstat"}.issubset(observed))
        self.assertEqual(os.geteuid(), real_lstat(proc).st_uid)

    def inode_changed(self, info):
        fields = {name: getattr(info, name) for name in ("st_dev", "st_ino", "st_mode",
                  "st_uid", "st_gid", "st_size", "st_mtime_ns", "st_ctime_ns")}
        fields["st_ino"] -= 1
        return types.SimpleNamespace(**fields)

    def test_exact_configfs_metadata_pins_the_opened_dentry(self):
        paths = {self.f.path("/sys/kernel/config/target/" + name) for name in ("version", "dbroot")}
        original = Path.lstat;calls = {}
        def before_open(path):
            info = original(path)
            if path in paths:
                calls[path] = calls.get(path, 0) + 1
                if calls[path] % 2 == 1:
                    return self.inode_changed(info)
            return info
        before = self.f.snapshot()
        with mock.patch.object(Path, "lstat", before_open):
            reply = self.f.witness().inspect(self.f.request())
        self.assertTrue(reply["globalRuntimeEmpty"])
        self.assertEqual(before, self.f.snapshot())

    def test_configfs_metadata_path_replaced_after_open_is_rejected(self):
        path = self.f.path("/sys/kernel/config/target/version")
        original = os.read;changed = []
        def replace_after_open(fd, amount):
            raw = original(fd, amount)
            if os.readlink("/proc/self/fd/" + str(fd)) == str(path) and not changed:
                changed.append(True)
                path.unlink()
                self.f.put("/sys/kernel/config/target/version", raw.decode())
            return raw
        with mock.patch.object(os, "read", replace_after_open):
            with self.assertRaisesRegex(ValueError, "FILE_CHANGED"):
                self.f.witness().inspect(self.f.request())
        self.assertFalse(self.f.generation.root.exists())

    def test_regular_file_keeps_preopen_inode_guard(self):
        path = self.f.path("/etc/exports");original = Path.lstat
        def before_open(value):
            info = original(value)
            return self.inode_changed(info) if value == path else info
        with mock.patch.object(Path, "lstat", before_open):
            with self.assertRaisesRegex(ValueError, "FILE_CHANGED"):
                self.f.witness().inspect(self.f.request())

    def test_target_metadata_dbroot_cannot_select_an_unobserved_configuration(self):
        self.f.put("/sys/kernel/config/target/dbroot", "/etc/foreign-target")
        self.f.put("/etc/foreign-target/saveconfig.json", '{"targets":[{"target":"foreign"}]}')
        with self.assertRaisesRegex(ValueError, "TARGET_DBROOT_UNKNOWN"):
            self.f.witness().inspect(self.f.request())

    def test_target_metadata_version_must_match_actual_kernel(self):
        self.f.put("/sys/kernel/config/target/version",
                   "Target Engine Core ConfigFS Infrastructure v5.0 on Linux/x86_64 on foreign-kernel")
        with self.assertRaisesRegex(ValueError, "TARGET_VERSION_UNKNOWN"):
            self.f.witness().inspect(self.f.request())

    def test_target_metadata_cannot_be_a_directory_or_alias(self):
        path = self.f.path("/sys/kernel/config/target/version")
        path.unlink();path.mkdir(mode=0o700)
        with self.assertRaisesRegex(ValueError, "UNTRUSTED_FILE"):
            self.f.witness().inspect(self.f.request())

    def test_virtual_proc_small_reads_avoid_kernel_enomem(self):
        original = os.read
        target = self.f.path("/proc/sys/kernel/random/boot_id")
        reads = []
        def bounded(fd, amount):
            if os.readlink("/proc/self/fd/" + str(fd)) == str(target):
                reads.append(amount)
                if amount > 4096:
                    raise OSError(12, "fixture virtual sysctl allocation")
            return original(fd, amount)
        before = self.f.snapshot()
        with mock.patch.object(os, "read", bounded):
            reply = self.f.witness().inspect(self.f.request())
        self.assertEqual(reply["bootId"], BOOT)
        self.assertTrue(reads)
        self.assertTrue(all(amount <= 4096 for amount in reads))
        self.assertEqual(before, self.f.snapshot())
        self.assertFalse(self.f.generation.root.exists())

    def dmi_alias(self, target="../../devices/virtual/dmi/id"):
        logical = self.f.path("/sys/class/dmi/id")
        (logical / "product_uuid").unlink()
        logical.rmdir()
        self.f.put("/sys/devices/virtual/dmi/id/product_uuid", VM_UUID + "\n")
        os.symlink(target, logical)
        return logical

    def test_exact_standard_dmi_alias_is_readonly(self):
        self.dmi_alias()
        before = self.f.snapshot()
        r = self.f.witness().inspect(self.f.request())
        self.assertEqual(r["rootIdentity"]["vmUuid"], VM_UUID)
        self.assertEqual(before, self.f.snapshot())

    def test_foreign_dmi_alias_is_rejected(self):
        self.dmi_alias("../../devices/foreign/dmi/id")
        with self.assertRaisesRegex(ValueError, "DMI_ALIAS_INVALID"):
            self.f.witness().inspect(self.f.request())

    def test_dmi_alias_target_symlink_is_rejected(self):
        self.dmi_alias()
        target = self.f.path("/sys/devices/virtual/dmi/id")
        (target / "product_uuid").unlink()
        target.rmdir()
        self.f.put("/sys/devices/virtual/foreign-id/product_uuid", VM_UUID + "\n")
        os.symlink("../foreign-id", target)
        with self.assertRaisesRegex(ValueError, "UNTRUSTED_DIRECTORY"):
            self.f.witness().inspect(self.f.request())

    def test_dmi_alias_replacement_during_read_is_rejected(self):
        alias = self.dmi_alias()
        witness = self.f.witness()
        original = witness.file
        def changed(path, **kwargs):
            raw = original(path, **kwargs)
            if path == self.f.path("/sys/devices/virtual/dmi/id/product_uuid"):
                alias.unlink()
                os.symlink("../../devices/foreign/dmi/id", alias)
            return raw
        witness.file = changed
        with self.assertRaisesRegex(ValueError, "DMI_ALIAS_CHANGED"):
            witness.inspect(self.f.request())

    def test_inline_capability_and_normal_feature_match_source(self):
        repo = LIB.parents[5]
        cli = (LIB.parents[0] / "../bin/ablestack-storagectl").resolve().read_text()
        start = cli.index("# BEGIN EMBEDDED CLONE_INITIAL_WITNESS_V1\n") + len("# BEGIN EMBEDDED CLONE_INITIAL_WITNESS_V1\n")
        end = cli.index("# END EMBEDDED CLONE_INITIAL_WITNESS_V1", start)
        source = (LIB / "clone_initial_witness.py").read_text()
        self.assertEqual(cli[start:end].strip(), source[source.index('"""Fresh initial-service'):].strip())
        self.assertIn("operation:generation:clone-initial-inspect|", cli)
        self.assertIn('"cloneInitialBaselineV1Supported":true', cli)
        builder = Path(__file__).resolve().parents[2] / "tools/build/build-storage-runtime-bundle.sh"
        self.assertEqual(builder.read_text().count('"STORAGE_CLONE_INITIAL_BASELINE_V1"'), 1)

if __name__ == "__main__":
    unittest.main()
