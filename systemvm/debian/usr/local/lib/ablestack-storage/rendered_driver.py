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

"""Scope-bound native RPC coordinator. Credential checkpoints contain ciphertext only."""
import hashlib
import ast
import base64
import json
import os
from pathlib import Path
import re
import time
import tempfile
import stat
import subprocess
import shutil
import uuid

from rendered_generation import DOMAINS, DESIRED_PATHS, RenderedGeneration, no_rendered_secrets, rendered_read, rendered_json, rendered_directory, rendered_write, rendered_fsync
from native_renderers import render_nfs_candidate, render_smb_candidate, render_block_candidate
from native_render_validation import validate_nfs_candidate, validate_smb_candidate
from native_render_runtime import NativeRenderedRuntime, PROTOCOL_FILES
from rendered_prerequisites import RenderedPrerequisites
from ganesha_dbus import GaneshaDbus


class RenderedDriver:
    def __init__(self, cli, store=None):
        self.store = store or RenderedGeneration()
        self.runtime = NativeRenderedRuntime(cli, self.store)
        self.checkpoints = self.store.root / "identity-checkpoints"
        self.prerequisites = RenderedPrerequisites(self.runtime,self.store)
        self.runtime.persist_one = self.persist_one
        self.nfs_config_root=Path("/etc/ganesha/ablestack-storage")

    def verify(self,path):
        manifest=self.store.inspect(path);bindings=json.loads(rendered_read(path/"file-volumes.json"))
        for row in bindings["volumes"]:
            scope=manifest["scope"]
            actual=self.runtime.command(("operation","root-data","inspect"),{**scope,"templateUpgradeUuid":scope["operationUuid"],"volumes":[{"volumeUuid":row["volumeUuid"],"sizeBytes":row["sizeBytes"],"kind":"FILE_DATA"}]})["volumes"][0]
            if (actual.get("mappingStatus")!="EXACT" or actual.get("matchedBy")!="VOLUME_SERIAL" or actual.get("sizeBytes")!=row["sizeBytes"] or actual.get("filesystemUuid")!=row["filesystemUuid"]
                    or not any(mount.get("target")==row["mountPath"] for mount in actual.get("mounts",[]))):raise ValueError("Rendered FILE_DATA binding changed during activation/readback")
        result=self.runtime.verify(path)
        self.prerequisites.verify(path)
        return result

    def generation(self):
        return self.runtime.command(("operation", "generation", "status"))

    def desired(self, request):
        desired = request["configurationDesiredState"]
        no_rendered_secrets(desired)
        if not isinstance(desired, dict) or set(desired) != DESIRED_PATHS or any(item is not None and not isinstance(item, dict) for item in desired.values()):
            raise ValueError("Rendered desired input requires the exact seven canonical files")
        protocols = request.get("protocolDesiredState")
        if not isinstance(protocols, dict) or set(protocols) != set(DOMAINS):
            raise ValueError("Rendered protocol input requires exactly four fixed domains")
        if any(protocols[domain] != desired[PROTOCOL_FILES[domain]] for domain in DOMAINS):
            raise ValueError("Rendered protocol payload differs from its canonical desired file")
        return desired

    def resolve_block(self, item):
        scope = self.active_scope
        volume = str(uuid.UUID(item["volumeUuid"]))
        result = self.runtime.command(("operation", "root-data", "inspect"),
                                      {**scope, "templateUpgradeUuid": scope["operationUuid"], "volumes": [{"volumeUuid": volume, "sizeBytes": item["volumeSizeBytes"], "kind": "BLOCK_RAW"}]})
        observed = result["volumes"][0]
        return {"devicePath": observed["observedDevicePath"], "serial": observed["serial"],
                "matchedBy": observed["matchedBy"], "sizeBytes": observed["sizeBytes"], "mounted": bool(observed.get("mounts")), "rootDevice": False}

    def file_bindings(self,request,files):
        nfs=json.loads(files["nfs/manifest.json"]);smb=json.loads(files["smb/manifest.json"])
        mounted_roots={}
        for row in nfs.get("aliases",{}).values():mounted_roots[row["volumeMountPath"]]=row["backingPath"]
        for row in smb.get("shares",[]):
            match=re.match(r"^(/srv/ablestack-storage/volumes/([0-9a-f-]{36}))(?:/|$)",row["path"])
            if not match:raise ValueError("Rendered SMB DATA path has no exact managed volume root")
            mounted_roots[match[1]]=row["path"]
        supplied=request.get("fileVolumeBindings") or []
        if not isinstance(supplied,list):raise ValueError("Rendered FILE_DATA bindings must be structured")
        wanted={}
        for row in supplied:
            if not isinstance(row,dict) or set(row)!={"volumeUuid","sizeBytes","filesystemUuid"}:raise ValueError("Rendered FILE_DATA binding fields differ")
            volume=str(uuid.UUID(row["volumeUuid"]));filesystem=str(uuid.UUID(row["filesystemUuid"]));size=row["sizeBytes"]
            if isinstance(size,bool) or not isinstance(size,int) or size<=0 or volume in wanted:raise ValueError("Rendered FILE_DATA binding is ambiguous")
            wanted[volume]={"volumeUuid":volume,"sizeBytes":size,"filesystemUuid":filesystem}
        required={str(uuid.UUID(Path(root).name)) for root in mounted_roots}
        if set(wanted)!=required:raise ValueError("Rendered FILE_DATA requires the exact declared volume/size/filesystem manifest")
        scope=self.store.scope(request);verified=[]
        for root in sorted(mounted_roots):
            if not re.fullmatch(r"/srv/ablestack-storage/volumes/[0-9a-f-]{36}",root):raise ValueError("Rendered FILE_DATA root is outside its managed namespace")
            row=wanted[str(uuid.UUID(Path(root).name))]
            actual=self.runtime.command(("operation","root-data","inspect"),{**scope,"templateUpgradeUuid":scope["operationUuid"],"volumes":[{**{key:row[key] for key in ("volumeUuid","sizeBytes")},"kind":"FILE_DATA"}]})["volumes"][0]
            if (actual.get("mappingStatus")!="EXACT" or actual.get("matchedBy")!="VOLUME_SERIAL" or actual.get("sizeBytes")!=row["sizeBytes"]
                    or actual.get("filesystemUuid")!=row["filesystemUuid"] or not any(mount.get("target")==root for mount in actual.get("mounts",[]))):
                raise ValueError("Rendered FILE_DATA mounted filesystem/serial/size differs from its frozen manifest")
            verified.append({**row,"mountPath":root})
        return {"schemaVersion":1,"volumes":verified}

    def render(self, request):
        desired = self.desired(request)
        self.active_scope = self.store.scope(request)
        refs = request.get("credentialRefs") or {}
        no_rendered_secrets(refs)
        if not isinstance(refs, dict) or not set(refs) <= set(DOMAINS):
            raise ValueError("Credential references contain an unknown domain")
        files = {}
        for domain in DOMAINS:
            value = desired[PROTOCOL_FILES[domain]] or {"enabled": False}
            if domain == "NFS": files.update(render_nfs_candidate(value, Path(self.runtime.cli)))
            elif domain == "SMB": files.update(render_smb_candidate(value, Path(self.runtime.cli), refs.get(domain)))
            else: files.update(render_block_candidate(value, domain, self.resolve_block, refs.get(domain)))
        files["file-volumes.json"]=json.dumps(self.file_bindings(request,files),sort_keys=True)
        files["desired-state.json"] = json.dumps(desired, sort_keys=True)
        files["network-bindings.json"] = json.dumps({"endpoints": desired["network-endpoints.json"], "primary": desired["sharedfs-network.json"]}, sort_keys=True)
        files["posix-plan.json"] = json.dumps(desired["posix-directory-policies.json"], sort_keys=True)
        current = self.store.pointer()
        source = json.loads(rendered_read(current / "desired-state.json")) if current is not None else desired
        # Endpoints carry a protected persistent MAC receipt; the caller can
        # supply fresh DB topology only through this structured transient field.
        expected = request.get("expectedBindings")
        if expected is None:
            path = Path("/var/lib/ablestack-storage/network-endpoint-bindings.json")
            if path.exists():
                info = path.lstat()
                if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077:
                    raise ValueError("Rendered network binding receipt is not protected")
                expected = json.loads(path.read_text())["expectedBindings"]
            else: expected = []
        files["prerequisites.json"] = json.dumps(self.prerequisites.snapshot(source, desired, expected), sort_keys=True)
        return files

    def retain_legacy_nfs_baseline(self,files):
        manifest=json.loads(files["nfs/manifest.json"])
        def without_managed_prefix(content):
            GaneshaDbus.configuration(content)
            return re.sub(r'(?m)^[ \t]*Dbus_Name_Prefix[ \t]*=[ \t]*"org\.ablestack\.storage\.ganesha\.e[0-9a-f]{24}"[ \t]*;[ \t]*\n?', '',content)
        for endpoint in manifest["endpoints"]:
            key=endpoint["legacyUnitKey"]
            if not re.fullmatch(r'[A-Za-z0-9_.-]+',key):raise ValueError("Legacy NFS config key is invalid")
            actual=self.nfs_config_root/(key+".conf")
            info=actual.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:
                raise ValueError("Legacy NFS source config is not protected")
            descriptor=os.open(actual,os.O_RDONLY|os.O_NOFOLLOW)
            try:
                opened=os.fstat(descriptor)
                fields=("st_dev","st_ino","st_mode","st_size","st_mtime_ns","st_ctime_ns")
                if tuple(getattr(opened,key) for key in fields)!=tuple(getattr(info,key) for key in fields):raise ValueError("Legacy NFS config changed while opening")
                content=os.read(descriptor,8*1024*1024+1).decode()
                after=os.fstat(descriptor)
                if len(content.encode())>8*1024*1024 or tuple(getattr(after,key) for key in fields)!=tuple(getattr(opened,key) for key in fields):raise ValueError("Legacy NFS config changed while reading")
            finally:os.close(descriptor)
            if without_managed_prefix(content)!=without_managed_prefix(files[endpoint["configurationPath"]]):
                raise ValueError("Legacy NFS source bytes differ from the declared export/global policy")
            files[endpoint["configurationPath"]]=content
        return files

    def validate(self, path, manifest):
        validate_nfs_candidate(path, timeout=self.runtime.remaining(120))
        validate_smb_candidate(path, timeout=self.runtime.remaining(20))
        kernel_file = Path("/boot/config-" + os.uname().release)
        kernel = kernel_file.read_text() if kernel_file.is_file() else ""
        for domain, filename in (("ISCSI", "block/iscsi-plan.json"), ("NVMEOF", "block/nvmeof-plan.json")):
            plan = json.loads(rendered_read(path / filename))
            if plan.get("protocol") != domain or plan.get("schemaVersion") != 1:
                raise ValueError("Block candidate schema is invalid")
            if plan["targets"]:
                flags = ("CONFIG_TARGET_CORE", "CONFIG_ISCSI_TARGET") if domain == "ISCSI" else ("CONFIG_NVME_TARGET", "CONFIG_NVME_TARGET_TCP")
                if any(not re.search(r"^" + flag + r"=(?:y|m)$", kernel, re.M) for flag in flags):
                    raise ValueError("Running template kernel cannot implement the staged block protocol")
                if domain == "ISCSI" and not shutil.which("targetcli"):
                    raise ValueError("iSCSI staged plan requires the fixed targetcli runtime")
            for target in plan["targets"]:
                for acl in target["acls"]:
                    config = acl["config"]
                    if domain == "NVMEOF" and (config.get("dhChapEnabled") or config.get("dhChapCtrlEnabled")) and not re.search(r"^CONFIG_NVME_TARGET_AUTH=y$", kernel, re.M):
                        raise ValueError("Running template kernel lacks NVMe target authentication")
        return {domain: True for domain in DOMAINS}

    def checkpoint(self, request, source):
        rendered_directory(self.checkpoints, True)
        scope = self.store.scope(request)
        path = self.checkpoints / (scope["operationUuid"] + ".json")
        if path.exists():
            saved = json.loads(rendered_read(path))
            if saved["scope"] != scope or saved["sourceConfigurationSha256"] != source["configurationSha256"]:
                raise ValueError("Rendered identity checkpoint scope changed")
            return saved
        key = request.get("checkpointPublicKey")
        if not isinstance(key, str) or len(key) > 16384:
            raise ValueError("Rendered stage requires a wrapping public key for durable identity recovery")
        desired = source["configurationDesiredState"]
        smb = desired[PROTOCOL_FILES["SMB"]] or {}
        names = set()
        for share in smb.get("shares", []):
            for acl in share.get("acls", []):
                if acl.get("principalType") in ("LOCAL_USER", "LOCAL_GROUP"):
                    names.add(acl["principal"])
        nvme = desired[PROTOCOL_FILES["NVMEOF"]] or {}
        hosts = sorted({host["principal"] for subsystem in nvme.get("subsystems", []) for host in subsystem.get("hosts", [])
                        if (host.get("config") or {}).get("dhChapEnabled") or (host.get("config") or {}).get("dhChapCtrlEnabled")})
        exported = self.runtime.command(("identity", "capsule", "export"), {**scope, "publicKey": key, "names": sorted(names), "nvmeHosts": hosts})
        saved = {"schemaVersion": 1, "scope": scope, "sourceConfigurationSha256": source["configurationSha256"], "publicKey": key, "capsule": exported["capsule"]}
        rendered_json(path, saved)
        return saved

    def recovery_key(self, request):
        from cryptography.hazmat.primitives import serialization
        saved = json.loads(rendered_read(self.checkpoints / (self.store.scope(request)["operationUuid"] + ".json")))
        private = request.get("checkpointPrivateKey")
        if not isinstance(private, str):
            raise ValueError("Protected checkpoint key is required before rendered activation/recovery")
        key = serialization.load_pem_private_key(private.encode(), password=None)
        expected = serialization.load_pem_public_key(saved["publicKey"].encode())
        encoding, form = serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        if key.public_key().public_bytes(encoding, form) != expected.public_bytes(encoding, form):
            raise ValueError("Protected recovery key does not match the durable identity checkpoint")
        if saved["scope"] != self.store.scope(request):
            raise ValueError("Durable identity checkpoint belongs to another operation")
        return saved

    def restore_identity(self, request):
        journal=self.store.scoped_activation(request)
        domains=[domain for domain in journal.get("startedDomains",journal["changedDomains"]) if domain in ("SMB","ISCSI","NVMEOF")]
        if not domains:return {"success":True,"identityRestored":False,"unaffectedIdentityPreserved":True}
        saved = self.recovery_key(request)
        previous = self.store.pointer()
        previous_manifest = self.store.inspect(previous)
        if previous_manifest["configurationSha256"] != saved["sourceConfigurationSha256"]:
            raise ValueError("Identity recovery differs from the pinned previous rendered configuration")
        # Decrypt the authenticated, scope-bound capsule inside this heap. Only
        # fixed reviewed codec definitions from signed CLI source are loaded.
        source = Path(self.runtime.cli).read_text().split("<<'PYIDENTITY'\n", 1)[1].split("\nPYIDENTITY", 1)[0]
        allowed = {"decrypt", "validate_payload", "account_merge", "validate_host_nqn", "merge_nvme_identity_payload"}
        tree = ast.parse(source)
        definitions = [item for item in tree.body if isinstance(item, ast.FunctionDef) and item.name in allowed]
        if {item.name for item in definitions} != allowed: raise ValueError("Signed identity codec differs from the fixed contract")
        namespace = {"base64": base64, "hashlib": hashlib, "os": os, "re": re}
        for item in tree.body:
            if isinstance(item, ast.Assign) and len(item.targets) == 1 and isinstance(item.targets[0], ast.Name) and item.targets[0].id in ("MAX_CAPSULE_BYTES", "FILES", "ACCOUNT_FILES"):
                namespace[item.targets[0].id] = ast.literal_eval(item.value)
        exec(compile(ast.Module(body=definitions, type_ignores=[]), self.runtime.cli, "exec"), namespace)
        capsule_scope = saved["scope"]["instanceUuid"] + ":" + saved["scope"]["operationUuid"]
        identity = namespace["decrypt"](saved["capsule"], request["checkpointPrivateKey"], capsule_scope)
        namespace["validate_payload"](identity)
        if "SMB" in domains:
            # LIVE TDB replacement remains prohibited. Only an already-approved
            # exact Root maintenance operation can stop its owned identity
            # daemons before restoring the ciphertext checkpoint. Other
            # protocols continue serving their unchanged generations.
            maintenance=self.prerequisites.network.authorize(self.store.scope(request))
            quiesced=self.runtime.command(("operation","quiesce"),{**maintenance,"domains":["SMB"]})
            if quiesced.get("quiesced") is not True or quiesced.get("scope")!=maintenance or quiesced.get("domainsQuiesced")!=["SMB"]:
                raise ValueError("Root SMB identity rollback lacks exact scoped quiescence")
        desired = json.loads(rendered_read(previous / "desired-state.json"))[PROTOCOL_FILES["NVMEOF"]]
        if "NVMEOF" in domains and identity.get("nvmeHosts"):
            merged = namespace["merge_nvme_identity_payload"](desired, identity["nvmeHosts"])
            credentials = self.runtime.credentials.setdefault("previous", {}).setdefault("NVMEOF", {})
            for subsystem in merged["subsystems"]:
                for host in subsystem.get("hosts", []):
                    if host.get("secrets"): credentials[host["uuid"]] = host["secrets"]
        # File/account restoration is separate from kernel replay. The ordered
        # adapter restores NVMe only if that domain was affected, preserving
        # unrelated live block sessions during an SMB-only rollback.
        self.runtime.command(("identity", "capsule", "import"), {**saved["scope"], "capsule": saved["capsule"],
                             "credentialPrivateKey": request["checkpointPrivateKey"], "deferNvmeReplay": True,"restoreDomains":domains})

    def persist_one(self, name, value):
        if name not in DESIRED_PATHS: raise ValueError("Canonical prerequisite path is outside its fixed allowlist")
        root = Path(os.environ.get("ABLESTACK_STORAGE_CONFIGURATION_ROOT", "/etc/ablestack-storage"));target = root / name
        if target.exists() and not target.is_symlink():
            info=target.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:raise ValueError("Canonical desired file is not protected")
            if value is not None and json.loads(target.read_bytes())==value:return
        if value is None and not target.exists() and not target.is_symlink():return
        target.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        info=target.parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:
            raise ValueError("Canonical desired parent is not protected")
        if value is None: target.unlink(missing_ok=True);rendered_fsync(target.parent);return
        descriptor,temporary=tempfile.mkstemp(prefix=".rendered-desired-",dir=target.parent)
        try:
            os.fchmod(descriptor,0o600)
            with os.fdopen(descriptor,"w") as handle:json.dump(value,handle,sort_keys=True);handle.flush();os.fsync(handle.fileno())
            os.replace(temporary,target);rendered_fsync(target.parent)
        finally:
            if os.path.exists(temporary):os.unlink(temporary)

    def persist_desired(self, path):
        desired=json.loads(rendered_read(path / "desired-state.json"))
        for name,value in desired.items():self.persist_one(name,value)
        receipt=json.loads(rendered_read(path/"prerequisites.json"))
        proof=self.runtime.command(("network","endpoints","reconcile"),{"expectedBindings":receipt["expectedBindings"]})
        if proof.get("bindingReceiptVerified") is not True or proof.get("bindingReceiptDesiredStateSha256")!=proof.get("desiredStateSha256"):
            raise ValueError("Canonical rendered network receipt did not verify after desired normalization")

    def boot_authorized(self, unit, maintenance):
        if not isinstance(unit, str): return False
        root = Path("/run/ablestack-storage/rendered-authorization")
        try:
            proof = json.loads(rendered_read(root / "writer.json"))
            status = self.store.status(); journal = status["activation"]
            if (maintenance.get("scope") != proof.get("maintenanceScope") or not journal or journal["scope"] != proof["scope"] or journal["phase"] not in ("ACTIVATING", "ROLLING_BACK", "VERIFIED")
                    or proof["bootId"] != Path("/proc/sys/kernel/random/boot_id").read_text().strip() or unit not in proof["units"]):
                return False
            pid = str(int(proof["pid"]))
            ticks = Path("/proc/" + pid + "/stat").read_text().rpartition(")")[2].split()[19]
            if ticks != proof["startTicks"]: return False
            info = Path("/proc/" + pid + "/fd/9").stat()
            return (info.st_dev, info.st_ino, info.st_uid, stat.S_IMODE(info.st_mode)) == (proof["lockDevice"], proof["lockInode"], os.geteuid(), 0o600)
        except (OSError, KeyError, ValueError, IndexError): return False

    def authorize_units(self, request):
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") != "9":
            raise ValueError("Rendered activation requires its inherited native writer lock")
        lock = os.fstat(9)
        if not stat.S_ISREG(lock.st_mode) or lock.st_uid != os.geteuid() or stat.S_IMODE(lock.st_mode) != 0o600:
            raise ValueError("Rendered activation lock is not protected")
        units = set()
        paths = [self.store.pointer(), self.store.generations / self.store.scope(request)["operationUuid"]]
        for path in paths:
            if path is None: continue
            self.store.inspect(path)
            nfs = json.loads(rendered_read(path / "nfs/manifest.json"))
            for endpoint in nfs.get("endpoints", []): units.add("ablestack-storage-ganesha@" + endpoint["legacyUnitKey"] + ".service")
            smb = json.loads(rendered_read(path / "smb/manifest.json"))
            for endpoint in smb.get("listeners", []):
                key = hashlib.sha256((endpoint["listenIp"] + ":" + str(endpoint["port"])).encode()).hexdigest()[:24]
                units.add("ablestack-storage-smb@" + key + ".service")
            if smb.get("listeners"): units.update(("smbd.service", "nmbd.service", "winbind.service"))
        root = Path("/run/ablestack-storage/rendered-authorization");rendered_directory(root, True)
        maintenance = self.runtime.command(("operation", "maintenance", "status"))
        if maintenance.get("bootHeld") and any((maintenance.get("scope") or {}).get(key) != value for key, value in self.store.scope(request).items()):
            raise ValueError("Rendered activation ROOT maintenance scope is foreign")
        proof = {"scope": self.store.scope(request), "maintenanceScope": maintenance.get("scope"), "units": sorted(units), "pid": os.getpid(),
                 "startTicks": Path("/proc/self/stat").read_text().rpartition(")")[2].split()[19],
                 "bootId": Path("/proc/sys/kernel/random/boot_id").read_text().strip(), "lockDevice": lock.st_dev, "lockInode": lock.st_ino}
        rendered_json(root / "writer.json", proof)
        return root / "writer.json"

    def install_boot_guards(self):
        content = "[Service]\nExecCondition=/usr/local/bin/ablestack-storagectl operation generation render-boot-gate %n\n"
        root = Path("/etc/systemd/system")
        for unit in ("smbd.service", "nmbd.service", "winbind.service", "nfs-server.service", "nfs-kernel-server.service", "nfs-ganesha.service", "target.service", "rtslib-fb-targetctl.service"):
            directory = root / (unit + ".d")
            directory.mkdir(mode=0o755, exist_ok=True)
            info = directory.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022:
                raise ValueError("Protocol boot guard directory is not protected")
            path = directory / "ablestack-storage-gate.conf"
            if path.exists():
                info = path.lstat()
                if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o022 or path.read_text() != content:
                    raise ValueError("Existing protocol boot guard is foreign")
            else:
                fd, temporary = tempfile.mkstemp(prefix=".storage-boot-gate-", dir=directory)
                try:
                    os.fchmod(fd, 0o644)
                    with os.fdopen(fd, "w") as handle:
                        handle.write(content);handle.flush();os.fsync(handle.fileno())
                    os.replace(temporary, path);rendered_fsync(directory)
                finally:
                    if os.path.exists(temporary):os.unlink(temporary)
        result = subprocess.run(["systemctl", "daemon-reload"], capture_output=True, timeout=self.runtime.remaining(15))
        if result.returncode:raise ValueError("Protocol boot guards could not be loaded")

    def execute(self, action, request=None, unit=None):
        if action == "render-status": return self.store.status()
        if action in ("render-boot-gate", "render-boot"):
            status = self.store.status()
            maintenance = self.runtime.command(("operation", "maintenance", "status"))
            if action == "render-boot-gate" and self.boot_authorized(unit, maintenance):
                return {"success": True, "bootHeld": False, "activationUnitAuthorized": unit}
            if status["bootHeld"] or maintenance.get("bootHeld") is not False:
                raise ValueError("Automatic protocol exposure is held by an interrupted configuration/ROOT operation")
            actual = self.generation()
            if actual.get("pendingOperationUuid"):
                raise ValueError("Automatic protocol exposure is held by a pending native configuration writer")
            if action == "render-boot-gate" or status["current"] is None: return status
            current = status["current"]
            if actual.get("pendingOperationUuid") or actual.get("generationStatus") != "IN_SYNC" or actual["configurationSha256"] != current["configurationSha256"] or any(actual["generation"].get(key) != value for key, value in current["scope"].items()):
                raise ValueError("Rendered boot current differs from the committed native generation")
            path = self.store.pointer()
            # The current immutable generation remains pinned for the whole
            # replay. Missing authentication material raises before readiness.
            for domain in DOMAINS: self.runtime.replay(path, domain)
            verified = self.verify(path)
            if not all(verified[domain] is True for domain in DOMAINS):
                raise ValueError("Rendered boot did not verify all four protocols")
            self.persist_desired(path)
            return {**status, "bootRuntimeVerified": verified}
        scope = self.store.scope(request)
        source = self.generation()
        if action in ("render-import", "render-stage"):
            files = self.render(request)
            if action == "render-import":
                if request.get("initialRootBaseline") is True:
                    maintenance=self.runtime.command(("operation","maintenance","status"))
                    if (source.get("generation") or source.get("pendingOperationUuid") or any(source["configurationDesiredState"][name] is not None for name in (*PROTOCOL_FILES.values(),"posix-directory-policies.json"))
                            or request["configurationDesiredState"]!=source["configurationDesiredState"] or self.store.pointer() is not None
                            or maintenance.get("bootHeld") is not True or any((maintenance.get("scope") or {}).get(key)!=value for key,value in scope.items())):
                        raise ValueError("Fresh ROOT rendered baseline lacks its exact protected empty/quarantined scope")
                    # A new target has no native LKG yet. Publish an immutable
                    # disabled baseline at revision0 without fabricating a
                    # native generation. Root adoption remains after real replay.
                    initial={**request,"operationUuid":str(uuid.uuid5(uuid.UUID(scope["operationUuid"]),"empty-rendered-root-baseline")),"revision":0,"expectedCurrentRenderedSha256":None}
                    manifest=self.store.stage(initial,files,self.validate)
                    target=self.store.generations/manifest["scope"]["operationUuid"]
                    verified=self.verify(target)
                    if not all(verified[domain] is True for domain in DOMAINS):raise ValueError("Quarantined ROOT still exposes an unconfigured protocol")
                    self.install_boot_guards();self.store.publish_pointer(target)
                    rendered_json(self.store.journal,{"scope":manifest["scope"],"phase":"COMPLETE","targetSha256":manifest["manifestSha256"],"previousSha256":None,"changedDomains":[],"baselineImported":True,"initialRootScope":maintenance["scope"]})
                    return self.store.status()
                if source.get("pendingOperationUuid") or source.get("generationStatus") != "IN_SYNC" or source.get("generation") != request.get("previousGeneration") or source["configurationDesiredState"] != request["configurationDesiredState"]:
                    raise ValueError("Legacy rendered import requires its exact verified native generation")
                if any(source["generation"].get(key) != value for key, value in scope.items()):
                    raise ValueError("Legacy rendered import scope differs from native LKG")
                if self.store.pointer() is not None:
                    current = self.store.inspect(self.store.pointer())
                    if current["scope"] != scope: raise ValueError("Rendered baseline already exists with another scope")
                    return self.store.status()
                files=self.retain_legacy_nfs_baseline(files)
                manifest = self.store.stage({**request, "expectedCurrentRenderedSha256": None}, files, self.validate)
                target = self.store.generations / scope["operationUuid"]
                verified = self.verify(target)
                if not all(verified[domain] is True for domain in DOMAINS): raise ValueError("Existing native runtime did not verify the rendered source baseline")
                self.install_boot_guards()
                self.store.publish_pointer(target)
                rendered_json(self.store.journal, {"scope": scope, "phase": "COMPLETE", "targetSha256": manifest["manifestSha256"], "previousSha256": None, "changedDomains": [], "baselineImported": True})
                return self.store.status()
            initial=self.store.read_journal() or {}
            root_initial=initial.get("initialRootScope")
            if root_initial:
                maintenance=self.runtime.command(("operation","maintenance","status"))
                if (maintenance.get("scope")!=root_initial or maintenance.get("bootHeld") is not True or any(root_initial.get(key)!=value for key,value in scope.items())
                        or source.get("generation") or source.get("pendingOperationUuid")
                        or source["configurationDesiredState"]!=json.loads(rendered_read(self.store.pointer()/"desired-state.json"))):
                    raise ValueError("Fresh ROOT staging escaped its protected empty baseline")
                pending={**scope,"previous":{},"beforeSha256":source["configurationSha256"]}
            else:
                if source.get("pendingOperationUuid") != scope["operationUuid"]:
                    raise ValueError("Rendered stage has no matching native writer")
                pending = json.loads(rendered_read(Path(os.environ.get("ABLESTACK_STORAGE_GENERATION_DIR", "/var/lib/ablestack-storage/config-generations")) / "pending.json"))
            if any(pending.get(key) != value for key, value in scope.items()) or pending.get("previous") != request.get("previousGeneration"):
                raise ValueError("Rendered pending source/scope changed")
            if self.store.pointer() is None: raise ValueError("Verified rendered source baseline must be imported before staging")
            current = self.store.inspect(self.store.pointer())
            if (current["configurationSha256"] != pending.get("beforeSha256") or source["configurationSha256"] != pending.get("beforeSha256")):
                raise ValueError("Source changed before pure rendered staging/identity capture")
            # Network and common permissions are prerequisite transactions. Until
            # their adapters supply verified rollback receipts, reject their
            # changes rather than silently claiming whole-state activation.
            previous_desired = json.loads(rendered_read(self.store.pointer() / "desired-state.json"))
            if request["configurationDesiredState"]["sharedfs-network.json"]!=previous_desired["sharedfs-network.json"]:
                raise ValueError("Rendered primary network change requires its separate verified transition")
            if request["configurationDesiredState"]["network-endpoints.json"]!=previous_desired["network-endpoints.json"]:
                self.prerequisites.network.authorize(scope,require_writer=False)
            healthy = self.verify(self.store.pointer())
            if not all(healthy[domain] is True for domain in DOMAINS):
                raise ValueError("Source runtime lost agreement before durable rendered staging")
            checkpoint = self.checkpoint(request, source)
            manifest = self.store.stage(request, files, self.validate)
            return {"success": True, "phase": "STAGED", "scope": scope, "renderedManifestSha256": manifest["manifestSha256"], "configurationSha256": manifest["configurationSha256"],
                    "identityCheckpointRef": {"operationUuid": scope["operationUuid"], "sha256": checkpoint["capsule"]["sha256"]}, "validators": {domain: True for domain in DOMAINS}}
        if action in ("render-finalize", "render-activate", "render-rollback"):
            pin = request.get("renderedManifestSha256")
            target = self.store.inspect(self.store.generations / scope["operationUuid"])
            if not re.fullmatch(r"[0-9a-f]{64}", str(pin or "")) or target["manifestSha256"] != pin:
                raise ValueError("Rendered mutation requires its exact staged manifest pin")
        if action == "render-finalize": return self.store.finalize(request, self.generation, self.verify)
        if action not in ("render-activate", "render-rollback"):
            raise ValueError("Unknown fixed rendered generation action")
        self.recovery_key(request)
        self.runtime.credentials = request.get("transientCredentials") or {}
        previous_restored = False
        def replay(path, domain, rollback):
            nonlocal previous_restored
            if rollback and not previous_restored:
                self.restore_identity(request); previous_restored = True
            return self.runtime.replay(path, domain, rollback)
        proof = self.authorize_units(request)
        target_path = self.store.generations / scope["operationUuid"]
        target_manifest = self.store.inspect(target_path)
        previous_path = self.store.root / "generations" / (self.store.read_journal().get("previousOperationUuid") if self.store.read_journal() and self.store.read_journal()["scope"]==scope else self.store.inspect(self.store.pointer())["scope"]["operationUuid"])
        def prerequisites(path, rollback):
            return self.prerequisites.apply(target_path, previous_path, rollback)
        try:
            if action == "render-rollback":
                return self.store.rollback(request, replay, self.verify, self.persist_desired, prerequisites)
            target = self.store.inspect(self.store.generations / scope["operationUuid"])
            current = self.store.inspect(self.store.pointer())
            changed = [domain for domain in DOMAINS if target["domainSha256"][domain] != current["domainSha256"][domain]]
            self.runtime.require_drained(changed)
            return self.store.activate(request, replay, self.verify, self.persist_desired, prerequisites)
        finally:
            proof.unlink(missing_ok=True);rendered_fsync(proof.parent)
