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

"""Portable test-only OS fixture. It never fabricates Native generation responses."""
from pathlib import Path
import copy,importlib.util,json,os,types,uuid
from unittest.mock import patch
ROOT_UUID="11111111-1111-4111-8111-111111111111"
VM_UUID="22222222-2222-4222-8222-222222222222"
INSTANCE="33333333-3333-4333-8333-333333333333"
OPERATION="44444444-4444-4444-8444-444444444444"
BOOT=Path("/proc/sys/kernel/random/boot_id").read_text().strip()
FIXTURE_ROOT=None
Generation=CloneInitialWitness=None

class InitialFixture:
    def __init__(self, data_count=4):
        self.root = FIXTURE_ROOT
        self.commands = []
        self.hook = None
        self.fail_tool = None
        self.missing_tool = None
        self.udp = True
        self.bad_owner = False
        self.exited = set()
        self.executable_links = {}
        self.put("/proc/sys/kernel/random/boot_id", BOOT + "\n")
        self.put("/sys/class/dmi/id/product_uuid", VM_UUID + "\n")
        self.put("/proc/modules", "target_core_mod 8192 1 - Live 0x0\niscsi_target_mod 8192 0 - Live 0x0\n")
        for name in ("target_core_mod", "iscsi_target_mod"):
            self.directory("/sys/module/" + name)
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
        target = self.put(executable, "# fixture binary\n");target.chmod(0o755)
        link = self.path("/proc/" + str(pid) + "/exe")
        if not link.is_symlink():
            os.symlink(str(target), link)
        elif os.readlink(link) != str(target):
            raise ValueError("OS_FIXTURE_EXECUTABLE_CHANGED")
        self.executable_links[link] = executable

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

    def inspect(self, request):
        real_readlink = os.readlink
        def logical_readlink(path, *args, **kwargs):
            physical = real_readlink(path, *args, **kwargs)
            logical = self.executable_links.get(Path(os.fsdecode(path))) if kwargs.get("dir_fd") is None else None
            if logical is not None:
                expected = os.fsencode(str(self.path(logical))) if isinstance(physical, bytes) else str(self.path(logical))
                if physical != expected:
                    raise ValueError("OS_FIXTURE_EXECUTABLE_CHANGED")
                return os.fsencode(logical) if isinstance(physical, bytes) else logical
            return physical
        # Normalize only the declared OS readlink observation; physical links stay inside the case.
        with patch.object(os, "readlink", side_effect=logical_readlink):
            return self.witness().inspect(request)

    def snapshot(self):
        return {str(p.relative_to(self.root)): (p.lstat().st_mode, os.readlink(p) if p.is_symlink() else p.read_bytes())
                for p in self.root.rglob("*") if p.is_file() or p.is_symlink()}

    def close(self):
        pass  # The Java-owned temporary case is retained for its ledger assertions.

def inspect_fixture(generation, request, fixture_path, repository):
    global FIXTURE_ROOT, Generation, CloneInitialWitness
    FIXTURE_ROOT=Path(fixture_path).parent
    Generation=type(generation)
    path=Path(repository)/"systemvm/debian/usr/local/lib/ablestack-storage/clone_initial_witness.py"
    loader=importlib.util.spec_from_file_location("gate_c_real_initial_witness",path)
    actual=importlib.util.module_from_spec(loader);loader.loader.exec_module(actual)
    CloneInitialWitness=actual.CloneInitialWitness
    spec = json.loads(Path(fixture_path).read_text())
    fields = {"vmUuid", "bootId", "rootVolumeUuid", "rootSizeBytes", "dataVolumes"}
    if type(spec) is not dict or set(spec) - (fields | {"rootFilesystemUuid", "foreignIscsiTarget"}) or not fields <= set(spec):
        raise ValueError("EXPLICIT_OS_FIXTURE_SCHEMA_REQUIRED")
    for key in ("vmUuid", "bootId", "rootVolumeUuid"):
        if str(uuid.UUID(spec[key])) != spec[key]:
            raise ValueError("OS_FIXTURE_UUID_INVALID")
    volumes = spec["dataVolumes"]
    if type(volumes) is not list or not 0 < len(volumes) <= 512:
        raise ValueError("OS_FIXTURE_DATA_SET_REQUIRED")
    fixture = InitialFixture(len(volumes))
    try:
        # Generation remains the caller's real persistent temporary case. Never seed its files.
        fixture.generation = generation
        fixture.put("/proc/sys/kernel/random/boot_id", spec["bootId"] + "\n")
        fixture.put("/sys/class/dmi/id/product_uuid", spec["vmUuid"] + "\n")
        rootfs = spec.get("rootFilesystemUuid", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        fixture.devices[0]["serial"] = spec["rootVolumeUuid"].replace("-", "")[:20]
        fixture.devices[0]["size"] = spec["rootSizeBytes"]
        fixture.devices[0]["children"][0]["uuid"] = rootfs
        fixture.mounts[0]["uuid"] = rootfs
        for index, volume in enumerate(volumes):
            if type(volume) is not dict or set(volume) != {"volumeUuid", "sizeBytes", "filesystem", "filesystemUuid", "mounts"}:
                raise ValueError("OS_FIXTURE_DATA_ROW_SCHEMA_REQUIRED")
            row = fixture.devices[index + 1]
            row["serial"] = volume["volumeUuid"].replace("-", "")[:20]
            row["size"] = volume["sizeBytes"];row["fstype"] = volume["filesystem"];row["uuid"] = volume["filesystemUuid"]
            row["mountpoints"] = [item["target"] for item in volume["mounts"]]
        fixture.mounts = fixture.mounts[:1]
        for index, volume in enumerate(volumes):
            for mount in volume["mounts"]:
                fixture.mounts.append({**mount, "source": fixture.devices[index + 1]["path"]})
        if spec.get("foreignIscsiTarget"):
            fixture.directory("/sys/kernel/config/target/iscsi/" + spec["foreignIscsiTarget"])
        return fixture.inspect(request)
    finally:
        fixture.close()
