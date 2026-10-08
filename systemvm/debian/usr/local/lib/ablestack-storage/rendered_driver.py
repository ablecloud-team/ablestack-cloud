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
from rendered_credentials import credential_json, credential_bindings, credential_recovery_key, credential_target_inputs
from root_source_recovery import RootSourceRecovery
from service_identity_source import ServiceIdentitySource
from root_retained_authorization import RootRetainedAuthorization
from root_configuration_capsule import root_configuration_sha256
from nvme_credentials import protected_credential_json


class RenderedDriver:
    def __init__(self, cli, store=None):
        self.store = store or RenderedGeneration()
        self.runtime = NativeRenderedRuntime(cli, self.store)
        self.checkpoints = self.store.root / "identity-checkpoints"
        self.prerequisites = RenderedPrerequisites(self.runtime,self.store)
        self.runtime.persist_one = self.persist_one
        self.nfs_config_root=Path("/etc/ganesha/ablestack-storage")
        self.root_source=RootSourceRecovery(self)
        self.service_identity=ServiceIdentitySource(self)
        self.root_retained=RootRetainedAuthorization(self)

    def retained_authority(self,request,for_stage=False):
        reference=request.get("retainedRootAuthorization")
        if reference is None:return None
        record=self.root_retained.load(request,reference,for_stage)
        desired=request.get("configurationDesiredState")
        if desired is not None and (root_configuration_sha256(desired)!=record["latestConfigurationSha256"] or desired!=record["configurationDesiredState"]):
            raise ValueError("Retained ROOT target seven differ from its authenticated latest source")
        return record

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

    def baseline_credential_refs(self,request,source):
        scope=self.store.scope(request)
        if (source.get("pendingOperationUuid") or source.get("generationStatus")!="IN_SYNC"
                or source.get("generation")!=request.get("previousGeneration")
                or source.get("configurationDesiredState")!=request.get("configurationDesiredState")
                or any((source.get("generation") or {}).get(key)!=value for key,value in scope.items())
                or request.get("credentialRefs") not in (None,{})):
            raise ValueError("Baseline private references require only the exact native current declaration")
        refs={"SMB":{},"ISCSI":{},"NVMEOF":{}};desired=source["configurationDesiredState"];inactive={"Disabled","Destroyed","Error"}
        smb=desired[PROTOCOL_FILES["SMB"]] or {};users=set()
        for share in smb.get("shares") or []:
            if smb.get("enabled") is False or share.get("state","Ready") in inactive:continue
            refs["SMB"][share["uuid"]]={"kind":"CURRENT_PRIVATE_VAULT",**{key:scope[key] for key in ("instanceUuid","operationUuid")},"sourceConfigurationSha256":source["configurationSha256"],"vault":"SAMBA_PASSDB"}
            for acl in share.get("acls") or []:
                if acl.get("state","Ready") not in inactive and acl.get("principalType")=="LOCAL_USER":users.add(acl["principal"])
        if users:
            identity=self.runtime.command(("smb","identity","inspect"),scope)
            if (identity.get("success") is not True or identity.get("scope")!=scope or identity.get("ownershipVerified") is not True
                    or identity.get("identityDatabaseAligned") is not True):
                raise ValueError("Baseline Samba private databases are not aligned with the owned live source")
            for name in sorted(users):
                if not isinstance(name,str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]{0,63}",name):raise ValueError("Baseline managed Samba account name is invalid")
                observed=subprocess.run(["pdbedit","-L","-u",name],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,timeout=self.runtime.remaining(10))
                rows=observed.stdout.splitlines()
                if observed.returncode or len(rows)!=1 or rows[0].split(":",1)[0]!=name:
                    raise ValueError("Baseline managed Samba account is absent from its protected current passdb")
        iscsi=protected_credential_json(self.runtime.iscsi_credentials_path) or {}
        nvme=self.runtime.nvme_credentials.read()
        for domain,collection,acls,flags,vault in (("ISCSI","targets","acls",(("chapEnabled","chapSecret"),("mutualChapEnabled","mutualChapSecret")),"ISCSI_ACL_AUTH"),
                                                 ("NVMEOF","subsystems","hosts",(("dhChapEnabled","dhChapKey"),("dhChapCtrlEnabled","dhChapCtrlKey")),"NVME_HOST_AUTH")):
            payload=desired[PROTOCOL_FILES[domain]] or {}
            for resource in payload.get(collection) or []:
                if payload.get("enabled") is False or resource.get("state","Ready") in inactive:continue
                for acl in resource.get(acls) or []:
                    if acl.get("state","Ready") in inactive:continue
                    key=resource["targetName"]+"|"+acl["principal"];config=acl.get("config") or {}
                    private=iscsi.get(key) if domain=="ISCSI" else (nvme or {}).get("hosts",{}).get(acl["principal"])
                    for flag,field in flags:
                        if config.get(flag):
                            if domain=="NVMEOF" and (nvme or {}).get("instanceUuid")!=scope["instanceUuid"]:raise ValueError("Baseline NVMe private vault belongs to another source instance")
                            if not isinstance(private,dict) or not isinstance(private.get(field),str) or not private[field]:
                                raise ValueError("Baseline authentication lacks its exact protected current private vault slot")
                    refs[domain][key]={"kind":"CURRENT_PRIVATE_VAULT",**{key:scope[key] for key in ("instanceUuid","operationUuid")},"sourceConfigurationSha256":source["configurationSha256"],"vault":vault}
        return refs

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
        root_transfer=None;initial=self.store.read_journal() or {};root_scope=initial.get("initialRootScope")
        retained=self.retained_authority(request)
        if retained is not None:
            root_transfer={"rootScope":retained["scope"],"sourceConfigurationSha256":retained["latestConfigurationSha256"],
                           "retainedRootAuthorization":request["retainedRootAuthorization"]}
        if root_scope is not None and source["posix-directory-policies.json"]!=desired["posix-directory-policies.json"]:
            maintenance=self.runtime.command(("operation","maintenance","status"))
            if (maintenance.get("bootHeld") is not True or maintenance.get("scope")!=root_scope
                    or any(root_scope.get(key)!=value for key,value in self.active_scope.items())):
                raise ValueError("ROOT POSIX staging lacks its original protected empty baseline scope")
            root_transfer={"rootScope":root_scope,"sourceConfigurationSha256":hashlib.sha256(json.dumps(desired,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()}
        files["prerequisites.json"] = json.dumps(self.prerequisites.snapshot(source, desired, expected,root_transfer), sort_keys=True)
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
        retained=self.retained_authority(request)
        if retained is not None:
            saved=self.root_retained.identity_checkpoint(retained)
            from cryptography.hazmat.primitives import serialization
            try:
                actual=serialization.load_pem_public_key(str(request.get("checkpointPublicKey") or "").encode())
                expected=serialization.load_pem_public_key(retained["checkpointPublicKey"].encode())
                if actual.public_numbers()!=expected.public_numbers():raise ValueError("retained wrapping key differs")
            except Exception as invalid:raise ValueError("Retained ROOT checkpoint wrapping key differs from its authenticated latest source") from invalid
            if path.exists() or path.is_symlink():
                if credential_json(rendered_read(path))!=saved:raise ValueError("Retained ROOT encrypted checkpoint changed")
            else:rendered_json(path,saved)
            return saved
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

    def credential_source(self, request):
        retained=self.retained_authority(request)
        if retained is not None:return {"configurationSha256":retained["latestConfigurationSha256"],"scope":retained["latestSourceGeneration"],"retainedLatestIdentitySource":True}
        scope=self.store.scope(request)
        target=self.store.inspect(self.store.generations/scope["operationUuid"])
        current=self.store.pointer();manifest=self.store.inspect(current)
        if manifest["manifestSha256"]!=target["previousRenderedSha256"]:
            journal=self.store.scoped_activation(request)
            if journal.get("targetSha256")!=target["manifestSha256"] or journal.get("previousSha256")!=target["previousRenderedSha256"]:
                raise ValueError("Credential checkpoint source differs from the staged immutable pointer")
            current=self.store.generations/str(uuid.UUID(journal["previousOperationUuid"]))
            manifest=self.store.inspect(current)
        if manifest["manifestSha256"]!=target["previousRenderedSha256"] or manifest["scope"]["instanceUuid"]!=scope["instanceUuid"]:
            raise ValueError("Credential checkpoint source is foreign")
        return manifest

    def recovery_key(self, request):
        scope=self.store.scope(request);source=self.credential_source(request)
        saved=credential_json(rendered_read(self.checkpoints/(scope["operationUuid"]+".json")))
        credential_recovery_key(saved,request.get("checkpointPrivateKey"),scope,source["configurationSha256"],request.get("identityCheckpointRef"))
        return saved

    def staged_credential_refs(self, path):
        self.store.inspect(path)
        refs={"SMB":{},"ISCSI":{},"NVMEOF":{}}
        for share in credential_json(rendered_read(path/"smb/manifest.json")).get("shares",[]):
            key=share["uuid"]
            if key in refs["SMB"]:raise ValueError("Staged SMB credential share is ambiguous")
            refs["SMB"][key]=share["credentialRefs"]
        for domain,filename in (("ISCSI","block/iscsi-plan.json"),("NVMEOF","block/nvmeof-plan.json")):
            for target in credential_json(rendered_read(path/filename))["targets"]:
                for acl in target["acls"]:
                    ref=acl.get("credentialRef")
                    if ref is None:continue
                    key=target["targetName"]+"|"+acl["principal"]
                    if key in refs[domain] and refs[domain][key]!=ref:raise ValueError("Staged block credential binding is ambiguous")
                    refs[domain][key]=ref
        return refs

    def protected_target_credentials(self, request, source_only=False):
        path=self.store.generations/self.store.scope(request)["operationUuid"]
        desired=credential_json(rendered_read(path/"desired-state.json"))
        refs=self.staged_credential_refs(path);saved=self.recovery_key(request)
        return credential_target_inputs(request,desired,refs,saved,self.credential_source(request)["configurationSha256"],source_only)

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
        allowed = {"decrypt", "validate_payload", "account_merge", "validate_host_nqn", "merge_nvme_identity_payload","validate_ad_identity","validate_posix_transfers","posix_row_sha256","capsule_validate_posix","capsule_validate_root_configuration"}
        tree = ast.parse(source)
        definitions = [item for item in tree.body if isinstance(item, ast.FunctionDef) and item.name in allowed]
        if {item.name for item in definitions} != allowed: raise ValueError("Signed identity codec differs from the fixed contract")
        namespace = {"base64": base64, "hashlib": hashlib, "os": os, "re": re, "json":json, "uuid":uuid}
        for item in tree.body:
            if isinstance(item, ast.Assign) and len(item.targets) == 1 and isinstance(item.targets[0], ast.Name) and item.targets[0].id in ("MAX_CAPSULE_BYTES", "FILES", "AD_FILES", "PUBLIC_IDENTITY_FILES", "ACCOUNT_FILES", "POSIX_TRANSFER_KEYS"):
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

    def service_source(self,request):
        expected={key:request[key] for key in ("instanceUuid","maintenanceUuid","operationUuid","revision")}
        if type(expected["revision"]) is not int or expected["revision"]<1:raise ValueError("SERVICE source revision is invalid")
        for key in ("instanceUuid","maintenanceUuid","operationUuid"):expected[key]=str(uuid.UUID(expected[key]))
        maintenance=self.runtime.command(("operation","maintenance","status"))
        if (expected["maintenanceUuid"]!=expected["operationUuid"] or maintenance.get("maintenanceKind")!="SERVICE"
                or maintenance.get("bootHeld") is not True or maintenance.get("scope")!=expected):raise ValueError("SERVICE source recovery lacks its exact held scope")
        checkpoint_path=Path(os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))/"service-maintenance.json"
        checkpoint=json.loads(rendered_read(checkpoint_path));status=self.store.status();actual=self.generation()
        if (checkpoint.get("scope")!=expected or checkpoint.get("phase") not in ("HELD","RECOVERY_REQUIRED")
                or not checkpoint.get("sourceRendered") or status.get("current")!=checkpoint["sourceRendered"]
                or actual.get("generation")!=checkpoint.get("sourceGeneration") or actual.get("pendingOperationUuid")
                or actual.get("generationStatus")!="IN_SYNC" or actual.get("configurationSha256")!=checkpoint["sourceGeneration"].get("configurationSha256")):
            raise ValueError("SERVICE source recovery differs from its protected pre-activation snapshot")
        return expected,checkpoint_path,checkpoint,status

    def boot_authorized(self, unit, maintenance):
        if not isinstance(unit, str): return False
        root = Path("/run/ablestack-storage/rendered-authorization")
        try:
            proof = json.loads(rendered_read(root / "writer.json"))
            status = self.store.status(); journal = status["activation"]
            if proof.get("mode")=="ROOT_SOURCE_RESTORE":
                expected,path,checkpoint=self.root_source.validate(proof["maintenanceScope"])
                if (checkpoint.get("phase") not in ("REPLAYING","VERIFIED")
                        or proof.get("sourceManifestSha256")!=checkpoint["sourceRendered"]["manifestSha256"]
                        or any(expected.get(key)!=value for key,value in proof["scope"].items())):return False
            elif proof.get("mode")=="SERVICE_SOURCE_RESTORE":
                expected,path,checkpoint,source_status=self.service_source(proof["maintenanceScope"])
                if (checkpoint.get("sourceResumePhase") not in ("REPLAYING","VERIFIED") or source_status.get("activation")!=checkpoint.get("sourceActivation")
                        or proof.get("sourceManifestSha256")!=checkpoint["sourceRendered"].get("manifestSha256")
                        or any(expected.get(key)!=value for key,value in proof["scope"].items())):return False
            elif not journal or journal["scope"] != proof["scope"] or journal["phase"] not in ("ACTIVATING", "ROLLING_BACK", "VERIFIED"):
                return False
            if (maintenance.get("scope") != proof.get("maintenanceScope")
                    or proof["bootId"] != Path("/proc/sys/kernel/random/boot_id").read_text().strip() or unit not in proof["units"]):return False
            pid = str(int(proof["pid"]))
            ticks = Path("/proc/" + pid + "/stat").read_text().rpartition(")")[2].split()[19]
            if ticks != proof["startTicks"]: return False
            info = Path("/proc/" + pid + "/fd/9").stat()
            return (info.st_dev, info.st_ino, info.st_uid, stat.S_IMODE(info.st_mode)) == (proof["lockDevice"], proof["lockInode"], os.geteuid(), 0o600)
        except (OSError, KeyError, ValueError, IndexError): return False

    def authorize_units(self, request,source_only=False):
        if os.environ.get("ABLESTACK_STORAGE_WRITER_LOCK_FD") != "9":
            raise ValueError("Rendered activation requires its inherited native writer lock")
        lock = os.fstat(9)
        if not stat.S_ISREG(lock.st_mode) or lock.st_uid != os.geteuid() or stat.S_IMODE(lock.st_mode) != 0o600:
            raise ValueError("Rendered activation lock is not protected")
        units = set()
        paths = [self.store.pointer()] if source_only else [self.store.pointer(), self.store.generations / self.store.scope(request)["operationUuid"]]
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
        if source_only:
            if source_only=="ROOT":
                expected,path,checkpoint=self.root_source.validate(request)
                if maintenance.get("maintenanceKind")!="ROOT":raise ValueError("ROOT source cannot borrow a SERVICE authorization")
                proof["mode"]="ROOT_SOURCE_RESTORE"
            else:proof["mode"]="SERVICE_SOURCE_RESTORE"
            proof["sourceManifestSha256"]=self.store.inspect(self.store.pointer())["manifestSha256"]
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
        if action == "render-status": return {**self.store.status(),"rootSourceIdentityCheckpointSupported":True,"retainedRootRestoreSupported":True,"serviceIdentityCheckpointSupported":True}
        if action=="render-service-capture-source":return self.service_identity.capture(request)
        if action=="render-service-source-quiesce-guard":return self.service_identity.guard(request)
        if action=="render-service-source-stopped":return self.service_identity.stopped(request)
        if action=="render-service-identity-export-source":return self.service_identity.export_source(request)
        if action=="render-root-capture-source":return self.root_source.capture(request)
        if action=="render-root-capture-retained":return self.root_retained.capture(request)
        if action=="render-root-authorize-retained":return self.root_retained.authorize(request)
        if action=="render-root-quiesce-source":return self.root_source.quiesce_source(request)
        if action=="render-root-identity-export-source":return self.root_source.identity_export_source(request)
        if action=="render-root-resume-source":return self.root_source.resume(request)
        if action=="render-root-replay-guard":return self.root_source.guard()
        if action=="render-root-source-quiesce-guard":
            scope,path,checkpoint=self.root_source.validate(request)
            return {"success":True,"scope":scope,"sourceCaptured":True,"quiesceAuthorized":True,"sideEffects":False}
        if action=="render-maintenance-resume-source":
            expected,checkpoint_path,checkpoint,status=self.service_source(request)
            if status.get("activation")!=checkpoint.get("sourceActivation"):raise ValueError("SERVICE pre-activation resume cannot adopt a rendered activation")
            units=checkpoint.get("stoppedUnits")
            if not isinstance(units,list) or any(not isinstance(unit,str) or not re.fullmatch(r"ablestack-storage-(?:ganesha@[A-Za-z0-9_.-]+|smb@[0-9a-f]{24})\.service",unit) for unit in units):
                raise ValueError("SERVICE source resume contains an unknown stopped acceptor")
            domains=[domain for domain,prefix in (("NFS","ablestack-storage-ganesha@"),("SMB","ablestack-storage-smb@")) if any(unit.startswith(prefix) for unit in units)]
            checkpoint["sourceResumePhase"]="REPLAYING";checkpoint["sourceResumeDomains"]=domains;rendered_json(checkpoint_path,checkpoint)
            proof=self.authorize_units(request,source_only=True);self.runtime.credentials=request.get("transientCredentials") or {}
            try:
                for domain in domains:self.runtime.replay(self.store.pointer(),domain)
                result=self.verify(self.store.pointer())
                if not all(result.get(domain) is True for domain in DOMAINS):raise ValueError("SERVICE resumed source runtime failed all-four readback")
                checkpoint["sourceResumePhase"]="VERIFIED";rendered_json(checkpoint_path,checkpoint)
                return {"success":True,"scope":expected,"sourceUnchangedVerified":True,"runtimeVerified":True,"resumedDomains":domains,"blockTargetsPreserved":True,"generationAdvanced":False}
            except Exception:
                checkpoint["sourceResumePhase"]="RECOVERY_REQUIRED";rendered_json(checkpoint_path,checkpoint);raise
            finally:proof.unlink(missing_ok=True);rendered_fsync(proof.parent)
        if action == "render-maintenance-verify":
            maintenance=self.runtime.command(("operation","maintenance","status"))
            expected={key:request[key] for key in ("instanceUuid","maintenanceUuid","operationUuid","revision")}
            if type(expected["revision"]) is not int or expected["revision"]<1:raise ValueError("SERVICE verification revision is invalid")
            for key in ("instanceUuid","maintenanceUuid","operationUuid"):expected[key]=str(uuid.UUID(expected[key]))
            if (expected["maintenanceUuid"]!=expected["operationUuid"] or maintenance.get("maintenanceKind")!="SERVICE"
                    or maintenance.get("bootHeld") is not True or maintenance.get("scope")!=expected):
                raise ValueError("SERVICE runtime verification lacks its exact held scope")
            actual=self.generation();verified=request.get("verifiedGeneration")
            if (not isinstance(verified,dict) or actual.get("generation")!=verified or actual.get("pendingOperationUuid")
                    or actual.get("generationStatus")!="IN_SYNC" or actual.get("configurationSha256")!=verified.get("configurationSha256")):
                raise ValueError("SERVICE committed generation was not exactly verified")
            status=self.store.status();current=status["current"];activation=status.get("activation")
            if status["bootHeld"] or current is None or current["configurationSha256"]!=actual["configurationSha256"]:
                raise ValueError("SERVICE immutable current was not finalized")
            forward=all(verified.get(key)==expected[key] and current["scope"].get(key)==expected[key] for key in ("instanceUuid","operationUuid","revision"))
            source_unchanged=False
            if not forward:
                expected,checkpoint_path,checkpoint,source_status=self.service_source(request)
                rollback=(activation and activation.get("phase")=="ROLLED_BACK" and all(activation.get("scope",{}).get(key)==expected[key] for key in ("instanceUuid","operationUuid","revision")))
                source_unchanged=(source_status.get("activation")==checkpoint.get("sourceActivation") and checkpoint.get("sourceResumePhase")=="VERIFIED")
                if not rollback and not source_unchanged:raise ValueError("SERVICE source was neither rolled back nor verified unchanged before activation")
            result=self.verify(self.store.pointer())
            if not all(result.get(domain) is True for domain in DOMAINS):raise ValueError("SERVICE all-four runtime verification failed")
            return {"success":True,"scope":expected,"runtimeVerified":True,"protocols":result,"rollbackVerified":not forward and not source_unchanged,"sourceUnchangedVerified":source_unchanged,"activationOccurred":not source_unchanged,"sideEffects":False}

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
        retained=self.retained_authority(request,for_stage=action=="render-stage")
        if retained is not None and action=="render-rollback":raise ValueError("Retained ROOT historical rollback is prohibited; restore its captured latest ROOT")
        source = self.generation()
        if action in ("render-import", "render-stage"):
            if retained is not None and action=="render-import":raise ValueError("Retained ROOT cannot borrow fresh/legacy rendered import")
            if action=="render-stage":credential_bindings(self.desired(request),request.get("credentialRefs") or {},scope,retained["latestConfigurationSha256"] if retained is not None else source["configurationSha256"])
            if action=="render-import" and request.get("initialRootBaseline") is not True:
                request={**request,"credentialRefs":self.baseline_credential_refs(request,source)}
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
            if retained is None:
                healthy = self.verify(self.store.pointer())
                if not all(healthy[domain] is True for domain in DOMAINS):raise ValueError("Source runtime lost agreement before durable rendered staging")
            elif (request.get("previousGeneration")!=retained["retainedGeneration"] or request.get("expectedCurrentRenderedSha256")!=retained["retainedRenderedSha256"] or pending.get("previous")!=retained["retainedGeneration"]):
                raise ValueError("Retained ROOT pending.previous/old pointer differs from its protected baseline")
            checkpoint = self.checkpoint(request, source)
            manifest = self.store.stage(request, files, self.validate)
            return {"success": True, "phase": "STAGED", "scope": scope, "renderedManifestSha256": manifest["manifestSha256"], "configurationSha256": manifest["configurationSha256"],
                    "sourceConfigurationSha256":source["configurationSha256"],"identityCheckpointSourceConfigurationSha256":checkpoint["sourceConfigurationSha256"],
                    "identityCheckpointRef": {"operationUuid": scope["operationUuid"], "sha256": checkpoint["capsule"]["sha256"]}, "validators": {domain: True for domain in DOMAINS},
                    **({"retainedRootAuthorization":request["retainedRootAuthorization"],"historicalSourceRuntimeVerified":False} if retained is not None else {})}
        if action in ("render-finalize", "render-activate", "render-rollback"):
            pin = request.get("renderedManifestSha256")
            target = self.store.inspect(self.store.generations / scope["operationUuid"])
            if not re.fullmatch(r"[0-9a-f]{64}", str(pin or "")) or target["manifestSha256"] != pin:
                raise ValueError("Rendered mutation requires its exact staged manifest pin")
        if action == "render-finalize": return self.store.finalize(request, self.generation, self.verify)
        if action not in ("render-activate", "render-rollback"):
            raise ValueError("Unknown fixed rendered generation action")
        # Authenticate immutable target/source bindings before the first runtime
        # authorization, pointer publication, identity import or daemon effect.
        target_credentials=self.protected_target_credentials(request,source_only=action=="render-rollback")
        self.runtime.credentials={"target":target_credentials,"previous":{}}
        previous_restored = False
        def replay(path, domain, rollback):
            nonlocal previous_restored
            if retained is not None and rollback:raise ValueError("Retained ROOT cannot replay historical protocols/identity/permissions; latest ROOT compensation is required")
            if rollback and not previous_restored:
                self.restore_identity(request); previous_restored = True
            return self.runtime.replay(path, domain, rollback)
        proof=None
        try:
            proof = self.authorize_units(request)
            target_path = self.store.generations / scope["operationUuid"]
            previous_path = self.store.generations/self.credential_source(request)["scope"]["operationUuid"]
            def prerequisites(path, rollback):
                return self.prerequisites.apply(target_path, previous_path, rollback)
            if action == "render-rollback":
                return self.store.rollback(request, replay, self.verify, self.persist_desired, prerequisites)
            target = self.store.inspect(self.store.generations / scope["operationUuid"])
            current = self.store.inspect(self.store.pointer())
            changed = [domain for domain in DOMAINS if target["domainSha256"][domain] != current["domainSha256"][domain]]
            self.runtime.require_drained(changed)
            return self.store.activate(request, replay, self.verify, self.persist_desired, prerequisites)
        finally:
            if proof is not None:proof.unlink(missing_ok=True);rendered_fsync(proof.parent)
            self.runtime.credentials.clear()
