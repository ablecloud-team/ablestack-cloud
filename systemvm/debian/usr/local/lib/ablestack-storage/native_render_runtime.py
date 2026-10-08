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

"""Fixed, bounded adapters for immutable rendered generations.

Only the signed storagectl entrypoint executes protocol effects. A caller cannot
supply commands or config paths. Configfs changes require an observed drained
block domain; they are ordered replay, not atomic kernel publication.
"""
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time

from rendered_generation import rendered_read
from ganesha_dbus import GaneshaDbus
from nvme_credentials import NvmeCredentialStore, protected_credential_json

RENDER_COMMANDS = {"NFS": ("nfs", "export", "apply"), "SMB": ("smb", "share", "apply"),
                   "ISCSI": ("iscsi", "target", "apply"), "NVMEOF": ("nvmeof", "subsystem", "apply")}
PROTOCOL_FILES = {"NFS": "desired-state/nfs-export-apply.json", "SMB": "desired-state/smb-share-apply.json",
                  "ISCSI": "iscsi-targets.json", "NVMEOF": "nvmeof-subsystems.json"}


class NativeRenderedRuntime:
    def __init__(self, cli, store, deadline=None, credentials=None):
        self.cli = str(cli)
        self.store = store
        self.deadline = deadline or time.monotonic() + 240
        if os.environ.get("ABLESTACK_STORAGE_BOOT_DEADLINE_MONOTONIC"):
            self.deadline=min(self.deadline,float(os.environ["ABLESTACK_STORAGE_BOOT_DEADLINE_MONOTONIC"]))
        self.credentials = credentials or {}
        self.iscsi_root = Path("/sys/kernel/config/target/iscsi")
        self.nvme_root = Path("/sys/kernel/config/nvmet")
        self.nfs_config_root=Path("/etc/ganesha/ablestack-storage")
        self.nvme_credentials=NvmeCredentialStore()
        self.iscsi_credentials_path=Path("/etc/ablestack-storage/secrets/iscsi-acl-secrets.json")

    def remaining(self, limit=60):
        value = min(limit, self.deadline - time.monotonic())
        if value <= 0:
            raise TimeoutError("Rendered runtime operation exhausted its total deadline")
        return value

    def command(self, arguments, payload=None, replay=None, replay_from=None):
        # Secrets use sealed memfd input, never argv, logs or disk files. FD9
        # remains inherited so a stuck kernel child continues to hold the writer.
        descriptor = None
        inherited = (9,) if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") == "9" else ()
        environment = dict(os.environ, ABLESTACK_STORAGECTL_CACHE="0")
        environment.pop("ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY",None)
        if replay:
            if getattr(self,"root_source_replay",False):environment["ABLESTACK_STORAGE_ROOT_SOURCE_REPLAY"]="1"
            environment["ABLESTACK_STORAGE_RENDERED_REPLAY"] = str(replay)
            environment["ABLESTACK_STORAGE_RENDERED_FROM"] = str(replay_from or replay)
            environment["ABLESTACK_STORAGE_RENDERED_BUDGET"] = str(self.remaining(180))
        try:
            if payload is not None:
                descriptor = os.memfd_create("storage-render-replay", os.MFD_CLOEXEC | os.MFD_ALLOW_SEALING)
                os.fchmod(descriptor, 0o600)
                encoded = json.dumps(payload, allow_nan=False).encode()
                if len(encoded) > 8 * 1024 * 1024:
                    raise ValueError("Rendered runtime input exceeds its limit")
                os.write(descriptor, encoded); os.lseek(descriptor, 0, os.SEEK_SET)
                fcntl.fcntl(descriptor, fcntl.F_ADD_SEALS, fcntl.F_SEAL_WRITE | fcntl.F_SEAL_GROW | fcntl.F_SEAL_SHRINK | fcntl.F_SEAL_SEAL)
                inherited = (*inherited, descriptor)
                arguments = (*arguments, "/proc/self/fd/" + str(descriptor))
            timeout = self.remaining(180)
            child = subprocess.Popen([self.cli, *arguments], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     text=True, env=environment, pass_fds=inherited, start_new_session=True)
            try:
                output, _ = child.communicate(timeout=timeout)
            except subprocess.TimeoutExpired:
                # The core persists RECOVERY_REQUIRED before the next writer can
                # run. Never wait indefinitely for an uninterruptible child.
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.communicate(timeout=2)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    try: child.communicate(timeout=2)
                    except subprocess.TimeoutExpired: pass
                raise TimeoutError("Rendered native replay timed out; reconciliation is required")
            if child.returncode or len(output) > 16 * 1024 * 1024:
                raise ValueError("Fixed native rendered operation failed")
            result = json.loads(output)
            if result.get("success") is not True:
                raise ValueError("Fixed native rendered operation did not verify success")
            return result
        finally:
            if descriptor is not None: os.close(descriptor)

    def reobserve_block(self,path,plan):
        manifest=self.store.inspect(path);wanted={}
        for target in plan["targets"]:
            for row in target["backstores"]:
                value={"volumeUuid":row["volumeUuid"],"sizeBytes":row["sizeBytes"],"kind":"BLOCK_RAW"}
                if row["volumeUuid"] in wanted and wanted[row["volumeUuid"]]!=value:raise ValueError("Rendered raw DATA volume mapping is ambiguous")
                wanted[row["volumeUuid"]]=value
        if not wanted:return {}
        scope=manifest["scope"]
        result=self.command(("operation","root-data","inspect"),{**scope,"templateUpgradeUuid":scope["operationUuid"],"volumes":list(wanted.values())})
        observed={row["volumeUuid"]:row for row in result["volumes"]}
        if set(observed)!=set(wanted):raise ValueError("Rendered raw DATA observation set differs")
        resolved={}
        for target in plan["targets"]:
            for row in target["backstores"]:
                actual=observed[row["volumeUuid"]]
                if (actual.get("mappingStatus")!="EXACT" or actual.get("matchedBy")!="VOLUME_SERIAL" or actual.get("serial")!=row["serial"]
                        or actual.get("sizeBytes")!=row["sizeBytes"] or actual.get("mounts") or not actual.get("observedDevicePath")):
                    raise ValueError("Rendered raw DATA serial/size/unmounted binding changed")
                resolved[row["volumeUuid"]]=actual["observedDevicePath"]
        return resolved

    def payload(self, path, domain, rollback=False):
        desired = json.loads(rendered_read(path / "desired-state.json"))
        value = desired[PROTOCOL_FILES[domain]]
        if value is None:
            value = {"enabled": False, {"NFS":"exports", "SMB":"shares", "ISCSI":"targets", "NVMEOF":"subsystems"}[domain]: []}
        value = json.loads(json.dumps(value))
        # Credential input is indexed by the immutable resource ACL identity.
        # Reject unused/foreign entries rather than expanding desired access.
        supplied = (self.credentials.get("previous" if rollback else "target") or {}).get(domain) or {}
        used = set()
        for resource in value.get("shares" if domain == "SMB" else "targets" if domain == "ISCSI" else "subsystems", []):
            for acl in resource.get("hosts" if domain == "NVMEOF" else "acls", []):
                key = str(acl.get("uuid") or "")
                if key in supplied:
                    allowed = {"password"} if domain == "SMB" else {"chapSecret", "mutualChapSecret"} if domain == "ISCSI" else {"dhChapKey", "dhChapCtrlKey"}
                    secret = supplied[key]
                    if not isinstance(secret, dict) or not set(secret) <= allowed or not secret or any(not isinstance(item, str) or not item for item in secret.values()):
                        raise ValueError("Transient rendered credential binding is invalid")
                    if domain == "SMB": acl["password"] = secret["password"]
                    else: acl["secrets"] = secret
                    used.add(key)
        if used != set(supplied):
            raise ValueError("Transient rendered credentials contain a foreign ACL")
        if domain in ("ISCSI","NVMEOF"):
            plan=json.loads(rendered_read(path/("block/iscsi-plan.json" if domain=="ISCSI" else "block/nvmeof-plan.json")))
            resolved=self.reobserve_block(path,plan)
            for resource in value.get("targets" if domain=="ISCSI" else "subsystems",[]):
                items=[resource] if domain=="ISCSI" else resource.get("namespaces",[])
                for item in items:
                    if item.get("state","Ready") in ("Disabled","Destroyed","Error") or value.get("enabled") is False:continue
                    volume=item.get("volumeUuid")
                    if volume not in resolved:raise ValueError("Rendered raw desired resource has no exact immutable DATA plan")
                    item["config"]={**(item.get("config") or {}),"backingPath":resolved[volume],"backstoreType":"block"}
        return value

    def require_drained(self, domains):
        block = set(domains) & {"ISCSI", "NVMEOF"}
        if not block: return
        observed = self.command(("sessions",))
        mapping = {"ISCSI":"ISCSI", "NVMEOF":"NVME_OF"}
        # The session collector must explicitly report available native sources.
        for domain in block:
            protocol = mapping[domain]
            if any(item.get("protocol") == protocol for item in observed.get("sessions", [])):
                raise ValueError("Changed block domain requires its active sessions to drain")
            field = "observedIscsiTcpCount" if domain == "ISCSI" else "observedNvmeofTcpCount"
            if (observed.get("status") != "ok" or observed.get("sessionSchemaVersion") != 2
                    or observed.get(field) != 0 or observed.get("warnings")):
                raise ValueError("Block session observation is unavailable or busy; replay is held")

    def replay(self, path, domain, rollback=False):
        self.store.inspect(path)
        if domain not in RENDER_COMMANDS:
            raise ValueError("Unknown fixed rendered runtime adapter")
        if domain in ("ISCSI", "NVMEOF"):
            self.require_drained((domain,))
        journal=self.store.read_journal()
        source=path
        if journal and journal.get("phase") not in ("COMPLETE","ROLLED_BACK"):
            source=self.store.generations/(journal["scope"]["operationUuid"] if rollback else journal["previousOperationUuid"])
            self.store.inspect(source)
        return self.command(RENDER_COMMANDS[domain], self.payload(path, domain, rollback), replay=path, replay_from=source)

    def tcp(self, ip, port):
        ip = "127.0.0.1" if ip == "0.0.0.0" else "::1" if ip == "::" else ip
        with socket.create_connection((ip, int(port)), timeout=self.remaining(1)):
            return True

    def verify(self, path):
        manifest = self.store.inspect(path)
        health = self.command(("operation", "verify"))
        result = {domain: False for domain in RENDER_COMMANDS}
        journal=self.store.read_journal() or {}
        credential_side="previous" if manifest["manifestSha256"]==journal.get("previousSha256") else "target"
        nfs = json.loads(rendered_read(path / "nfs/manifest.json"))
        nfs_health=health.get("nfsGanesha") or {}
        observed_nfs={row.get("file"):row for row in nfs_health.get("endpoints",[])}
        for endpoint in nfs.get("endpoints", []):
            configuration = self.nfs_config_root / (endpoint["legacyUnitKey"] + ".conf")
            if hashlib.sha256(configuration.read_bytes()).hexdigest() != manifest["files"][endpoint["configurationPath"]]:
                raise ValueError("Live Ganesha configuration differs from its immutable generation")
            row=observed_nfs.get(configuration.name) or {}
            pid=int(row.get("pid") or 0)
            if not pid or row.get("processRunning") is not True:raise ValueError("Rendered NFS owned process is unavailable")
            process=Path("/proc/"+str(pid))
            ticks=(process/"stat").read_text().rpartition(")")[2].split()[19]
            arguments=(process/"cmdline").read_bytes().split(bytes([0]))
            if (not arguments or not arguments[0].endswith(b"ganesha.nfsd") or arguments.count(b"-f")!=1
                    or arguments[arguments.index(b"-f")+1].decode()!=str(configuration)):
                raise ValueError("Rendered NFS process uses a foreign configuration")
            adapter=GaneshaDbus(deadline=self.deadline)
            name,manager=adapter.owned_endpoint_manager(endpoint["listenIp"],endpoint["port"],pid)
            adapter.verify(manager,adapter.configuration(configuration.read_text())["exports"])
            self.tcp(endpoint["listenIp"], endpoint["port"])
            if (process/"stat").read_text().rpartition(")")[2].split()[19]!=ticks:raise ValueError("Rendered NFS process changed during readback")
        result["NFS"] = (not nfs.get("endpoints") and nfs_health.get("active", 0) == 0 and not (health.get("listenPorts") or {}).get("nfs")) or bool(nfs_health.get("listening") and nfs_health.get("active", 0) >= len(nfs["endpoints"]))
        smb = json.loads(rendered_read(path / "smb/manifest.json"))
        actual_smb = Path("/etc/samba/smb.conf")
        if smb.get("listeners"):
            if hashlib.sha256(actual_smb.read_bytes()).hexdigest() != manifest["files"]["smb/smb.conf"]:
                raise ValueError("Live Samba configuration differs from its immutable generation")
            endpoints = {(str(item.get("listenIp")), int(item.get("port") or 0)): item for item in (health.get("smbRuntime") or {}).get("runtimeEndpoints", [])}
            for endpoint in smb["listeners"]:
                actual = endpoints.get((endpoint["listenIp"], endpoint["port"])) or {}
                if actual.get("listenerOwned") is not True or actual.get("tcpReady") is not True:
                    raise ValueError("Rendered Samba endpoint ownership/TCP readback failed")
                self.tcp(endpoint["listenIp"], endpoint["port"])
        if not smb.get("listeners") and any(item.get("listenerOwned") and item.get("tcpReady") for item in (health.get("smbRuntime") or {}).get("runtimeEndpoints", [])):
            raise ValueError("Disabled rendered Samba still exposes an owned endpoint")
        result["SMB"] = True
        for domain, name in (("ISCSI", "iscsi"), ("NVMEOF", "nvmeof")):
            plan = json.loads(rendered_read(path / ("block/" + name + "-plan.json")))
            resolved=self.reobserve_block(path,plan)
            result[domain] = self.verify_block(plan,credential_side,manifest["scope"]["instanceUuid"],resolved)
        return result

    def verify_block(self, plan,credential_side="target",instance_uuid=None,resolved=None):
        durable_nvme=None;durable_iscsi=None
        if plan["protocol"]=="ISCSI" and any((acl.get("config") or {}).get("chapEnabled") or (acl.get("config") or {}).get("mutualChapEnabled") for target in plan["targets"] for acl in target["acls"]):
            durable_iscsi=protected_credential_json(self.iscsi_credentials_path)
            if not isinstance(durable_iscsi,dict):raise ValueError("Rendered iSCSI authentication lacks its protected durable store")
        if plan["protocol"]=="NVMEOF" and any((host.get("config") or {}).get("dhChapEnabled") or (host.get("config") or {}).get("dhChapCtrlEnabled") for target in plan["targets"] for host in target["acls"]):
            durable_nvme=self.nvme_credentials.read()
            if not durable_nvme or (instance_uuid is not None and durable_nvme["instanceUuid"]!=instance_uuid):
                raise ValueError("Rendered NVMe authentication lacks its protected durable instance store")
        collection = self.iscsi_root if plan["protocol"] == "ISCSI" else self.nvme_root / "subsystems"
        actual = {item.name for item in collection.iterdir() if ".local.storage:" in item.name} if collection.is_dir() else set()
        if actual != {item["targetName"] for item in plan["targets"]}:
            raise ValueError("Rendered managed block resource set differs from the live kernel")
        for target in plan["targets"]:
            name = target["targetName"]
            if plan["protocol"] == "ISCSI":
                root = self.iscsi_root / name / "tpgt_1"
                if not root.is_dir(): raise ValueError("Rendered iSCSI target is absent")
                actual_acls = {item.name for item in (root / "acls").iterdir()}
                if actual_acls != {item["principal"] for item in target["acls"]}:
                    raise ValueError("Rendered iSCSI access bindings differ")
                portals = {item.name for item in (root / "np").iterdir()}
                if portals != {item["listenIp"] + ":" + str(item["port"]) for item in target["listeners"]}:
                    raise ValueError("Rendered iSCSI portal bindings differ")
                if {item.name for item in (root / "lun").iterdir()} != {"lun_" + str(item["number"]) for item in target["backstores"]}:
                    raise ValueError("Rendered iSCSI LUN set differs")
                required = any((item.get("config") or {}).get("chapEnabled") or (item.get("config") or {}).get("mutualChapEnabled") for item in target["acls"])
                if (root / "attrib/authentication").read_text().strip() != ("1" if required else "0"):
                    raise ValueError("Rendered iSCSI authentication policy differs")
                for acl in target["acls"]:
                    config = acl.get("config") or {}; auth = root / "acls" / acl["principal"] / "auth"
                    for flag, username, field in (("chapEnabled", "chapUsername", "userid"), ("mutualChapEnabled", "mutualChapUsername", "userid_mutual")):
                        observed_identity=(auth/field).read_text().rstrip("\n")
                        if observed_identity=="NULL":observed_identity=""
                        if observed_identity != (str(config.get(username) or "") if config.get(flag) else ""):
                            raise ValueError("Rendered CHAP identity differs")
                    for flag, field, secret in (("chapEnabled", "password", "chapSecret"), ("mutualChapEnabled", "password_mutual", "mutualChapSecret")):
                        observed = (auth / field).read_text().rstrip("\n")
                        if observed=="NULL":observed=""
                        if bool(observed) != bool(config.get(flag)):
                            raise ValueError("Rendered CHAP credential presence differs")
                        expected = ((self.credentials.get(credential_side) or {}).get("ISCSI") or {}).get(acl.get("uuid"), {}).get(secret)
                        if config.get(flag):
                            durable=(durable_iscsi.get(name+"|"+acl["principal"]) or {}).get(secret)
                            if not durable or durable!=observed:raise ValueError("Rendered CHAP differs from its protected durable credential")
                        if expected is not None and observed != expected:
                            raise ValueError("Rendered CHAP credential verification failed")
                for backing in target["backstores"]:
                    links = list((root / "lun" / ("lun_" + str(backing["number"]))).glob("*"))
                    links = [item for item in links if item.is_symlink()]
                    if len(links) != 1: raise ValueError("Rendered iSCSI LUN is not exactly mapped")
                    control = (links[0].resolve() / "udev_path").read_text().strip()
                    if Path(control).resolve() != Path((resolved or {}).get(backing["volumeUuid"],backing["devicePath"])).resolve():
                        raise ValueError("Rendered iSCSI LUN changed its DATA binding")
            else:
                root = self.nvme_root / "subsystems" / name
                if not root.is_dir() or (root / "attr_allow_any_host").read_text().strip() != ("1" if target["allowAnyHost"] else "0"):
                    raise ValueError("Rendered NVMe subsystem access differs")
                if {item.name for item in (root / "allowed_hosts").iterdir()} != {item["principal"] for item in target["acls"]}:
                    raise ValueError("Rendered NVMe host bindings differ")
                if {item.name for item in (root / "namespaces").iterdir()} != {str(item["number"]) for item in target["backstores"]}:
                    raise ValueError("Rendered NVMe namespace set differs")
                for host in target["acls"]:
                    config = host.get("config") or {}; auth = self.nvme_root / "hosts" / host["principal"]
                    for flag, field, secret in (("dhChapEnabled", "dhchap_key", "dhChapKey"), ("dhChapCtrlEnabled", "dhchap_ctrl_key", "dhChapCtrlKey")):
                        observed = (auth / field).read_text().strip() if (auth / field).exists() else ""
                        if bool(observed) != bool(config.get(flag)):
                            raise ValueError("Rendered DHCHAP credential presence differs")
                        expected = ((self.credentials.get(credential_side) or {}).get("NVMEOF") or {}).get(host.get("uuid"), {}).get(secret)
                        if config.get(flag):
                            durable=(durable_nvme["hosts"].get(host["principal"]) or {}).get(secret)
                            if not durable or observed!=durable:raise ValueError("Rendered DHCHAP differs from its protected durable credential")
                        if expected is not None and observed != expected:
                            raise ValueError("Rendered DHCHAP credential verification failed")
                for backing in target["backstores"]:
                    namespace = root / "namespaces" / str(backing["number"])
                    if (namespace / "enable").read_text().strip() != "1" or Path((namespace / "device_path").read_text().strip()).resolve() != Path((resolved or {}).get(backing["volumeUuid"],backing["devicePath"])).resolve():
                        raise ValueError("Rendered NVMe namespace changed its DATA binding")
                actual = set()
                for port in (self.nvme_root / "ports").iterdir():
                    if (port / "subsystems" / name).is_symlink():
                        actual.add(((port / "addr_traddr").read_text().strip(), int((port / "addr_trsvcid").read_text().strip())))
                if actual != {(item["listenIp"], item["port"]) for item in target["listeners"]}:
                    raise ValueError("Rendered NVMe endpoint links differ")
            for endpoint in target["listeners"]: self.tcp(endpoint["listenIp"], endpoint["port"])
        return True
