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

"""Retained ROOT baseline and latest AEAD authority; no canonical/DATA effects."""
import ast
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import uuid
import time
from rendered_generation import rendered_read,rendered_json,DOMAINS,no_rendered_secrets
from rendered_credentials import credential_json,credential_envelope
from root_configuration_capsule import root_capsule_scope,root_configuration_authorize
from root_source_identity_checkpoint import root_public_sha
from root_identity_reference import RootIdentityReference
from posix_root_initialization import root_receipt_read


class RootRetainedAuthorization:
    def __init__(self,driver,root=None):
        self.driver=driver;self.store=driver.store;self.runtime=driver.runtime
        self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))/"root-retained-authorizations"

    def scope(self,request):
        if not isinstance(request,dict) or "maintenanceUuid" in request:raise ValueError("Retained ROOT cannot borrow a SERVICE scope")
        value=root_capsule_scope({key:request[key] for key in ("instanceUuid","templateUpgradeUuid","operationUuid","revision")})
        marker=self.runtime.command(("operation","maintenance","status"))
        if marker.get("maintenanceKind")!="ROOT" or marker.get("bootHeld") is not True or marker.get("scope")!=value:
            raise ValueError("Retained ROOT lacks its exact protected held marker")
        return value

    def ref(self,value):
        if not isinstance(value,dict) or set(value)!={"authorizationUuid","sha256"}:raise ValueError("Retained ROOT opaque reference fields are invalid")
        if not isinstance(value["authorizationUuid"],str) or str(uuid.UUID(value["authorizationUuid"]))!=value["authorizationUuid"]:
            raise ValueError("Retained ROOT opaque UUID is invalid")
        if not isinstance(value["sha256"],str) or not re.fullmatch("[0-9a-f]{64}",value["sha256"]):raise ValueError("Retained ROOT opaque digest is invalid")
        return value

    def identifier(self,scope,kind):
        return str(uuid.uuid5(uuid.UUID(scope["operationUuid"]),"ablestack-root-retained-"+kind))

    def path(self,identifier,kind):
        return self.root/(identifier+"-"+kind+".json")

    def baseline(self,scope,ref,allow_pending=False):
        ref=self.ref(ref);identifier=self.identifier(scope,"baseline")
        if ref["authorizationUuid"]!=identifier:raise ValueError("Retained ROOT baseline belongs to another operation")
        record=credential_json(rendered_read(self.path(identifier,"baseline")))
        if root_public_sha(record)!=ref["sha256"] or record.get("scope")!=scope:raise ValueError("Retained ROOT protected baseline reference differs")
        self.unchanged(scope,record,allow_pending)
        return record

    def observe(self,scope,allow_pending=False):
        actual=self.driver.generation();status=self.store.status();generation=actual.get("generation")
        current=status.get("current")
        pending=actual.get("pendingOperationUuid")
        if pending:
            if not allow_pending or pending!=scope["operationUuid"] or actual.get("generationStatus")!="PENDING":
                raise ValueError("Retained ROOT baseline has a foreign or unapproved native pending writer")
            record=credential_json(rendered_read(Path(os.environ.get("ABLESTACK_STORAGE_GENERATION_DIR","/var/lib/ablestack-storage/config-generations"))/"pending.json"))
            if (any(record.get(key)!=scope[key] for key in ("instanceUuid","operationUuid","revision"))
                    or record.get("previous")!=generation or record.get("beforeSha256")!=actual.get("configurationSha256") or record.get("phase")!="PREPARED"):
                raise ValueError("Retained ROOT pending.previous is not its exact captured old generation")
        if (actual.get("generationStatus") not in (("IN_SYNC","PENDING") if allow_pending else ("IN_SYNC",)) or not isinstance(generation,dict)
                or generation.get("instanceUuid")!=scope["instanceUuid"] or type(generation.get("revision")) is not int or generation["revision"]>=scope["revision"]
                or generation.get("configurationSha256")!=actual.get("configurationSha256")
                or status.get("bootHeld") is not False or not isinstance(current,dict)
                or current.get("configurationSha256")!=actual["configurationSha256"]
                or any(current.get("scope",{}).get(key)!=generation.get(key) for key in ("instanceUuid","operationUuid","revision"))):
            raise ValueError("Retained ROOT native generation/pointer is not an exact inactive baseline")
        desired=credential_json(rendered_read(self.store.pointer()/"desired-state.json"))
        if desired!=actual.get("configurationDesiredState"):raise ValueError("Retained ROOT seven differ from its immutable old pointer")
        return actual,status

    def quarantine(self):
        health=self.runtime.command(("operation","verify"))
        nfs=health.get("nfsGanesha");smb=health.get("smbRuntime");ports=health.get("listenPorts")
        if (not isinstance(nfs,dict) or type(nfs.get("active")) is not int or nfs["active"]!=0
                or not isinstance(smb,dict) or not isinstance(smb.get("runtimeEndpoints"),list) or not isinstance(ports,dict)
                or any(type(ports.get(key)) is not bool for key in ("nfs","smb"))
                or ports["nfs"] or ports["smb"]
                or any(row.get("listenerOwned") or row.get("tcpReady") for row in smb["runtimeEndpoints"])):
            raise ValueError("Retained ROOT file/block listener quarantine is not freshly verified")
        for root in (self.runtime.iscsi_root,self.runtime.nvme_root/"subsystems"):
            if root.exists() or root.is_symlink():
                info=root.lstat()
                if not stat.S_ISDIR(info.st_mode) or info.st_uid!=os.geteuid() or info.st_mode&0o022:
                    raise ValueError("Retained ROOT kernel inventory is not trusted")
                for path in root.iterdir():
                    if root==self.runtime.iscsi_root and path.name=="discovery_auth":
                        default=path.lstat()
                        if not stat.S_ISDIR(default.st_mode) or default.st_uid!=os.geteuid() or default.st_mode&0o022:
                            raise ValueError("Retained ROOT static discovery authentication namespace is untrusted")
                        continue # Kernel-created metadata only; never read auth values.
                    if path.is_dir() and not path.is_symlink():
                        raise ValueError("Retained ROOT still exposes a kernel block target")
                    raise ValueError("Retained ROOT kernel namespace has an unknown entry")
        self.listeners_clear()
        return {domain:True for domain in DOMAINS}

    def listeners_clear(self):
        desired=self.driver.generation().get("configurationDesiredState") or {};ports={2049,445,139,3260,4420}
        for name in ("desired-state/nfs-export-apply.json","desired-state/smb-share-apply.json","iscsi-targets.json","nvmeof-subsystems.json"):
            payload=desired.get(name) or {}
            if payload.get("port") is not None:ports.add(int(payload["port"]))
            for row in payload.get("listeners") or []:
                if row.get("port") is not None:ports.add(int(row["port"]))
        observed=subprocess.run(["ss","-H","-ltnp"],capture_output=True,text=True,timeout=self.runtime.remaining(15))
        if observed.returncode or len(observed.stdout)>1024*1024:raise ValueError("Retained ROOT TCP listener inventory is unavailable")
        for line in observed.stdout.splitlines():
            fields=line.split()
            if len(fields)<5 or not fields[3].rpartition(":")[2].isdigit():raise ValueError("Retained ROOT TCP listener inventory is malformed")
            if int(fields[3].rpartition(":")[2]) in ports:raise ValueError("Retained ROOT still exposes a declared/default protocol TCP listener")

    def unchanged(self,scope,saved,allow_pending=False):
        actual,status=self.observe(scope,allow_pending)
        if (saved.get("schemaVersion")!=1 or type(saved.get("schemaVersion")) is not int or saved.get("kind")!="ROOT_RETAINED_BASELINE"
                or saved.get("scope")!=scope or saved.get("retainedGeneration")!=actual["generation"]
                or saved.get("retainedRenderedSha256")!=status["current"]["manifestSha256"]
                or saved.get("retainedCanonicalBytes")!=self.driver.root_source.canonical_bytes()):
            raise ValueError("Retained ROOT old baseline changed after protected capture")
        return actual,status

    def capture(self,request):
        allowed={"instanceUuid","templateUpgradeUuid","operationUuid","revision","expectedRetainedGeneration","expectedRetainedRenderedSha256"}
        if not isinstance(request,dict) or set(request)!=allowed:raise ValueError("Retained ROOT capture accepts only typed scope and exact old baseline pins")
        scope=self.scope(request);actual,status=self.observe(scope)
        if request["expectedRetainedGeneration"]!=actual["generation"] or request["expectedRetainedRenderedSha256"]!=status["current"]["manifestSha256"]:
            raise ValueError("Retained ROOT caller baseline pin differs before capture")
        protocols=self.quarantine();identifier=self.identifier(scope,"baseline");path=self.path(identifier,"baseline")
        if path.exists() or path.is_symlink():
            saved=credential_json(rendered_read(path));self.unchanged(scope,saved)
        else:
            saved={"schemaVersion":1,"kind":"ROOT_RETAINED_BASELINE","scope":scope,"authorizationUuid":identifier,
                   "retainedGeneration":actual["generation"],"retainedRenderedSha256":status["current"]["manifestSha256"],
                   "retainedCanonicalBytes":self.driver.root_source.canonical_bytes(),"protocolQuarantine":protocols,"capturedEpoch":time.time()}
            self.unchanged(scope,saved);rendered_json(path,saved)
        return {"success":True,"scope":scope,"retainedBaselineCaptured":True,"baselineRef":{"authorizationUuid":identifier,"sha256":root_public_sha(saved)},
                "retainedGeneration":saved["retainedGeneration"],"retainedRenderedSha256":saved["retainedRenderedSha256"],
                "canonicalDesiredStateChanged":False,"dataPermissionsChanged":False}

    def codec(self):
        # Load only the fixed decrypt definition from the signed CLI already
        # selected by the caller's normal runtime attestation.
        source=Path(self.runtime.cli).read_text().split("<<'PYIDENTITY'\n",1)[1].split("\nPYIDENTITY",1)[0]
        tree=ast.parse(source);definitions=[item for item in tree.body if isinstance(item,ast.FunctionDef) and item.name in ("decrypt","encrypt")]
        if {item.name for item in definitions}!={"encrypt","decrypt"}:raise ValueError("Retained ROOT signed encrypted codec is not exact")
        namespace={"base64":base64,"hashlib":hashlib,"json":json,"os":os}
        assignments=[item for item in tree.body if isinstance(item,ast.Assign) and any(isinstance(key,ast.Name) and key.id=="MAX_CAPSULE_BYTES" for key in item.targets)]
        if len(assignments)!=1:raise ValueError("Retained ROOT signed decrypt size bound is missing")
        value=assignments[0].value
        if ast.dump(value)!=ast.dump(ast.parse("8 * 1024 * 1024",mode="eval").body):
            raise ValueError("Retained ROOT signed decrypt size bound differs")
        namespace["MAX_CAPSULE_BYTES"]=8*1024*1024
        exec(compile(ast.Module(body=definitions,type_ignores=[]),self.runtime.cli,"exec"),namespace)
        return namespace

    def bindings(self,scope,values):
        if not isinstance(values,list) or len(values)>512:raise ValueError("Retained ROOT FILE binding set is invalid")
        result=[];seen=set()
        for row in values:
            if not isinstance(row,dict) or set(row)!={"volumeUuid","sizeBytes","filesystemUuid"}:raise ValueError("Retained ROOT FILE binding fields are not exact")
            volume=row["volumeUuid"];filesystem=row["filesystemUuid"]
            if (not isinstance(volume,str) or str(uuid.UUID(volume))!=volume or volume in seen or type(row["sizeBytes"]) is not int or row["sizeBytes"]<=0
                    or not isinstance(filesystem,str) or str(uuid.UUID(filesystem))!=filesystem):
                raise ValueError("Retained ROOT FILE binding is foreign or invalid")
            seen.add(volume)
            observed=self.runtime.command(("operation","root-data","inspect"),{**scope,"volumes":[{"volumeUuid":volume,"sizeBytes":row["sizeBytes"],"kind":"FILE_DATA"}]})
            if not isinstance(observed.get("volumes"),list) or len(observed["volumes"])!=1:raise ValueError("Retained ROOT FILE DATA readback is incomplete")
            actual=observed["volumes"][0];serial="".join(char for char in str(actual.get("serial") or "").lower() if char.isalnum());token=volume.replace("-","")
            if (actual.get("volumeUuid")!=volume or actual.get("matchedBy")!="VOLUME_SERIAL" or actual.get("mappingStatus")!="EXACT"
                    or serial not in (token,token[:20]) or actual.get("sizeBytes")!=row["sizeBytes"] or actual.get("filesystemUuid")!=filesystem):
                raise ValueError("Retained ROOT FILE serial/filesystem/size differs from its frozen source")
            result.append(dict(row))
        return sorted(result,key=lambda row:row["volumeUuid"])

    def authorize(self,request):
        allowed={"instanceUuid","templateUpgradeUuid","operationUuid","revision","baselineRef","capsule","credentialPrivateKey","originalSourceScope","sourceConfigurationSha256","fileVolumeBindings"}
        if not isinstance(request,dict) or set(request)!=allowed:raise ValueError("Retained ROOT authorization accepts no plaintext latest seven or caller snapshot")
        scope=self.scope(request);saved=self.baseline(scope,request["baselineRef"]);self.quarantine()
        source_scope=root_capsule_scope(request["originalSourceScope"])
        reference=RootIdentityReference()
        capsule_scope=reference.authorize(request,request["capsule"],self.runtime.command(("operation","maintenance","status")))
        retained=root_receipt_read(reference.path(source_scope,request["capsule"]))
        expected={"schemaVersion":1,"sourceRootScope":source_scope,"capsuleSha256":reference.digest(request["capsule"]),"sourceConfigurationSha256":request["sourceConfigurationSha256"]}
        if retained!=expected:raise ValueError("Retained ROOT latest capsule lacks its same-op authenticated import reference")
        if not isinstance(request["credentialPrivateKey"],str) or len(request["credentialPrivateKey"])>16384:raise ValueError("Retained ROOT protected key transport is invalid")
        codec=self.codec()
        try:payload=codec["decrypt"](request["capsule"],request["credentialPrivateKey"],capsule_scope)
        except Exception as invalid:raise ValueError("Retained ROOT latest identity authentication failed") from invalid
        try:
            desired=root_configuration_authorize(payload,source_scope,request["sourceConfigurationSha256"])
            bindings=self.bindings(scope,request["fileVolumeBindings"])
            required={row["request"]["volumeUuid"] for row in (desired["posix-directory-policies.json"] or {}).values()}
            if not required<={row["volumeUuid"] for row in bindings}:raise ValueError("Retained ROOT latest POSIX policies lack frozen FILE DATA bindings")
            from cryptography.hazmat.primitives import serialization
            private=serialization.load_pem_private_key(request["credentialPrivateKey"].encode(),password=None)
            from cryptography.hazmat.primitives.asymmetric import rsa
            if not isinstance(private,rsa.RSAPrivateKey) or private.key_size<2048:raise ValueError("Retained ROOT wrapping key algorithm/size is unsupported")
            public=private.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo).decode()
            record={"schemaVersion":1,"kind":"ROOT_RETAINED_AUTHORIZATION","scope":scope,"baselineRef":request["baselineRef"],
                    "retainedGeneration":saved["retainedGeneration"],"retainedRenderedSha256":saved["retainedRenderedSha256"],
                    "latestSourceRootScope":source_scope,"latestSourceGeneration":payload["rootSourceConfiguration"]["sourceGeneration"],
                    "latestConfigurationSha256":request["sourceConfigurationSha256"],"configurationDesiredState":desired,
                    "latestIdentityArtifactSha256":reference.digest(request["capsule"]),"checkpointPublicKey":public,"fileVolumeBindings":bindings}
            # A prior AAD may be rebound only after its protected reference and
            # AEAD latest-seven header authenticate. Only ciphertext persists.
            target_aad=scope["instanceUuid"]+":"+scope["operationUuid"]
            cipher=request["capsule"] if capsule_scope==target_aad else codec["encrypt"](payload,public,target_aad)
            credential_envelope(cipher,target_aad)
            identity={"schemaVersion":1,"scope":{key:scope[key] for key in ("instanceUuid","operationUuid","revision")},
                      "sourceConfigurationSha256":record["latestConfigurationSha256"],"publicKey":public,"capsule":cipher}
            record["identityCheckpointSha256"]=cipher["sha256"]
            no_rendered_secrets(record)
            self.unchanged(scope,saved);self.quarantine();identifier=self.identifier(scope,"authorization");path=self.path(identifier,"authorization")
            if path.exists() or path.is_symlink():
                previous=credential_json(rendered_read(path))
                if previous!=record:raise ValueError("Retained ROOT authorization changed after immutable publication")
            else:
                identity_path=self.path(identifier,"identity")
                if identity_path.exists() or identity_path.is_symlink():
                    if credential_json(rendered_read(identity_path))!=identity:raise ValueError("Retained ROOT latest encrypted checkpoint changed")
                else:rendered_json(identity_path,identity)
                rendered_json(path,record)
            opaque={"authorizationUuid":identifier,"sha256":root_public_sha(record)}
            return {"success":True,"scope":scope,"retainedRootAuthorized":True,**opaque,"retainedRootAuthorization":opaque,
                    "latestConfigurationSha256":record["latestConfigurationSha256"],"retainedGeneration":record["retainedGeneration"],
                    "retainedRenderedSha256":record["retainedRenderedSha256"],"canonicalDesiredStateChanged":False,"dataPermissionsChanged":False}
        finally:payload.clear()

    def load(self,request,reference,for_stage=False):
        if request.get("maintenanceUuid") is not None:raise ValueError("Retained ROOT stage cannot borrow SERVICE scope")
        marker=self.runtime.command(("operation","maintenance","status"));scope=self.scope(marker.get("scope"))
        if any(request.get(key)!=scope[key] for key in ("instanceUuid","operationUuid","revision")):
            raise ValueError("Retained ROOT stage operation/revision differs from its protected Root4")
        opaque=self.ref(reference);identifier=self.identifier(scope,"authorization")
        if opaque["authorizationUuid"]!=identifier:raise ValueError("Retained ROOT stage authorization belongs to another operation")
        record=credential_json(rendered_read(self.path(identifier,"authorization")))
        if record.get("scope")!=scope or root_public_sha(record)!=opaque["sha256"]:raise ValueError("Retained ROOT authorization digest/scope differs")
        if for_stage:self.baseline(scope,record["baselineRef"],True);self.quarantine()
        return record

    def identity_checkpoint(self,record):
        identifier=self.identifier(record["scope"],"authorization")
        saved=credential_json(rendered_read(self.path(identifier,"identity")))
        if (saved.get("scope")!={key:record["scope"][key] for key in ("instanceUuid","operationUuid","revision")}
                or saved.get("sourceConfigurationSha256")!=record["latestConfigurationSha256"]
                or saved.get("publicKey")!=record["checkpointPublicKey"] or saved.get("capsule",{}).get("sha256")!=record["identityCheckpointSha256"]):
            raise ValueError("Retained ROOT latest encrypted checkpoint differs from its authorization")
        return saved
