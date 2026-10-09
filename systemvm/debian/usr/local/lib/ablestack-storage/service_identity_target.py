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

"""Fresh TARGET-only LKG capture under a held SERVICE; SOURCE records are immutable."""
import base64
import hashlib
import json
import os
from pathlib import Path
import time
from service_identity_source import ServiceIdentitySource,service_identity_scope
from service_identity_cipher import ServiceIdentityCipher,service_cipher_digest
from rendered_credentials import credential_json
from rendered_generation import DOMAINS,rendered_read,rendered_json
from root_source_identity_checkpoint import root_public_sha,ROOT_IDENTITY_HOLDER_SCOPE


class ServiceIdentityTarget:
    capture_kind="SERVICE_IDENTITY_TARGET"
    stop_kind="SERVICE_TARGET_STOP_JOURNAL"
    stopped_kind="SERVICE_TARGET_STOPPED"
    cipher_kind="SERVICE_TARGET_IDENTITY_CHECKPOINT"
    prefix="service-identity-target"
    stopped_flag="serviceTargetStoppedVerified"
    maintenance_kind="SERVICE"
    def __init__(self,driver,controller,root=None,identity=None,writer=None,winbind=None):
        self.source=ServiceIdentitySource(driver,root,identity,writer)
        self.driver=driver;self.store=driver.store;self.runtime=driver.runtime;self.root=self.source.root
        self.identity=self.source.identity;self.controller=controller;self.writer=self.source.writer
        self.winbind=winbind
    checkpoint_field="serviceIdentityCheckpoint"
    def local_sid(self,saved):return saved["targetPublicIdentity"]["publicLocalMachineSid"]
    def scope(self,request):
        if not isinstance(request,dict) or set(request)!={"instanceUuid","maintenanceUuid","operationUuid","revision","targetConfigurationSha256"}:
            raise ValueError("TARGET capture request is mixed or incomplete")
        scope=service_identity_scope({key:request[key] for key in ("instanceUuid","maintenanceUuid","operationUuid","revision")})
        digest=request["targetConfigurationSha256"]
        if not isinstance(digest,str) or len(digest)!=64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError("TARGET configuration checksum is invalid")
        return scope
    def path(self,scope):return self.root/(self.prefix+"-"+scope["operationUuid"]+".json")
    def stop_path(self,scope):return self.root/(self.prefix+"-stop-"+scope["operationUuid"]+".json")
    def cipher_path(self,scope):return self.root/(self.prefix+"-cipher-"+scope["operationUuid"]+".json")
    def observation(self,request):
        scope=self.scope(request);self.writer();self.source.held(scope)
        actual,status=self.source.observation(scope)
        if (any(actual["generation"].get(key)!=scope[key] for key in ("instanceUuid","operationUuid","revision"))
                or actual.get("pendingOperationUuid") is not None or actual["configurationSha256"]!=request["targetConfigurationSha256"]):
            raise ValueError("LKG TARGET is not its exact committed current generation")
        return scope,actual,status
    def capture(self,request):
        scope,actual,status=self.observation(request);path=self.path(scope)
        if path.exists() or path.is_symlink():
            saved=self.validate(request)[1];return self.receipt(saved)
        verified=self.driver.verify(self.store.pointer())
        if set(verified)!=set(DOMAINS) or any(verified[name] is not True for name in DOMAINS):
            raise ValueError("LKG TARGET all-four readback failed before capture")
        frozen=self.identity.freeze(scope,actual);owners=self.identity.owners()
        if (frozen["publicAdIdentity"] is not None)!=any(row["unit"]=="ablestack-storage-winbind.service" for row in owners):
            raise ValueError("LKG TARGET AD differs from its owned winbind")
        saved={"schemaVersion":1,"kind":self.capture_kind,"scope":scope,"phase":"CAPTURED","targetGeneration":actual["generation"],
               "targetRendered":status["current"],"targetActivation":status.get("activation"),"targetConfigurationSha256":actual["configurationSha256"],
               "targetPublicIdentity":frozen,"targetOwners":owners,"canonicalBytes":self.source.canonical_bytes(),
               "bootId":Path("/proc/sys/kernel/random/boot_id").read_text().strip(),"capturedEpoch":time.time()}
        rendered_json(path,saved);return self.receipt(saved)
    def receipt(self,saved):
        value={"success":True,"scope":saved["scope"],"targetCaptured":True,"targetGeneration":saved["targetGeneration"],
               "targetConfigurationSha256":saved["targetConfigurationSha256"],"targetRenderedManifestSha256":saved["targetRendered"]["manifestSha256"],
               "bootId":saved["bootId"],"publicLocalMachineSid":self.local_sid(saved),"canonicalDesiredStateChanged":False}
        if saved.get("targetStoppedReceipt") is not None:
            value.update({self.stopped_flag:True,"stoppedReceiptSha256":root_public_sha(saved["targetStoppedReceipt"])})
        return value
    def validate(self,request):
        scope,actual,status=self.observation(request);saved=credential_json(rendered_read(self.path(scope)))
        if (type(saved.get("schemaVersion")) is not int or saved["schemaVersion"]!=1 or saved.get("kind")!=self.capture_kind or saved.get("scope")!=scope
                or saved.get("targetGeneration")!=actual["generation"] or saved.get("targetRendered")!=status["current"]
                or saved.get("targetActivation")!=status.get("activation") or saved.get("targetConfigurationSha256")!=actual["configurationSha256"]
                or saved.get("canonicalBytes")!=self.source.canonical_bytes() or saved.get("bootId")!=Path("/proc/sys/kernel/random/boot_id").read_text().strip()):
            raise ValueError("LKG TARGET captured generation/seven/boot differs")
        self.identity.unchanged(scope,actual,saved["targetPublicIdentity"])
        return scope,saved,actual
    def quiesce(self,request):
        scope,saved,actual=self.validate(request);owners=saved["targetOwners"];journal_path=self.stop_path(scope)
        if saved.get("phase")=="STOPPED":return self.stopped(request)
        if self.identity.owners()!=owners:raise ValueError("LKG TARGET owners changed before stop")
        if not time.time()-180<=saved["capturedEpoch"]<=time.time()+5:raise ValueError("LKG TARGET PRESTOP capture expired")
        journal={"kind":self.stop_kind,"scope":scope,"phase":"STOPPING","targetGeneration":saved["targetGeneration"],
                 "targetConfigurationSha256":saved["targetConfigurationSha256"],"owners":owners,"stoppedUnits":[],"bootId":saved["bootId"]}
        rendered_json(journal_path,journal)
        try:
            for owner in sorted(owners,key=lambda row:row["unit"]=="ablestack-storage-winbind.service"):
                fresh=self.controller.prove_unit(owner["unit"],False)
                if fresh is None or fresh!=owner:raise ValueError("LKG TARGET unit PID/start/config changed before owned stop")
                self.controller.run(["systemctl","stop",owner["unit"]],timeout=self.controller.remaining())
                journal["stoppedUnits"].append(owner["unit"]);rendered_json(journal_path,journal)
            journal["phase"]="HELD";rendered_json(journal_path,journal)
            return self.stopped(request)
        except Exception:
            journal["phase"]="RECOVERY_REQUIRED";rendered_json(journal_path,journal);raise
    def stopped(self,request):
        scope,saved,actual=self.validate(request);journal=credential_json(rendered_read(self.stop_path(scope)));owners=saved["targetOwners"]
        if (journal.get("kind")!=self.stop_kind or journal.get("scope")!=scope or journal.get("phase") not in ("HELD","RECOVERY_REQUIRED")
                or journal.get("targetGeneration")!=saved["targetGeneration"] or journal.get("targetConfigurationSha256")!=saved["targetConfigurationSha256"]
                or journal.get("owners")!=owners or len(journal.get("stoppedUnits",[]))!=len(owners)
                or set(journal["stoppedUnits"])!={row["unit"] for row in owners} or self.identity.owners()):
            raise ValueError("LKG TARGET lacks its exact independent owned-stop receipt")
        self.identity.listeners_clear(owners)
        if self.identity.holders():raise ValueError("LKG TARGET private TDB holders remain")
        receipt={"schemaVersion":1,"kind":self.stopped_kind,"scope":scope,"targetGeneration":saved["targetGeneration"],
                 "targetConfigurationSha256":saved["targetConfigurationSha256"],"owners":owners,"knownIdentityDatabaseHolders":0,
                 "holderObservationScope":ROOT_IDENTITY_HOLDER_SCOPE,"bootId":saved["bootId"],"publicLocalMachineSid":self.local_sid(saved)}
        if saved.get("targetStoppedReceipt") not in (None,receipt):raise ValueError("LKG TARGET stopped receipt changed")
        if saved.get("targetStoppedReceipt") is None:
            saved["targetStoppedReceipt"]=receipt;saved["phase"]="STOPPED";rendered_json(self.path(scope),saved)
        return {**self.receipt(saved),"bootHeld":True,"maintenanceKind":self.maintenance_kind,"sideEffects":False}
    def export_target(self,request):
        self.stopped(request);scope,saved,actual=self.validate(request)
        if saved["phase"]!="STOPPED":raise ValueError("LKG RAW TARGET export requires its own AFTERSTOP receipt")
        return {**self.receipt(saved),"adIdentity":saved["targetPublicIdentity"]["publicAdIdentity"],"posixPolicies":saved["targetPublicIdentity"]["sourcePosixPolicies"],"sideEffects":False}
    def wrapping_key(self,request):
        source=ServiceIdentityCipher(self.root);scope=self.scope({key:value for key,value in request.items() if key not in ("publicKey","identityCheckpointRef")})
        if source.source_path(scope).exists() or source.source_path(scope).is_symlink():
            native=source.source(scope);record=source.read(source.path(scope))
            if (record.get("kind")!="SERVICE_SOURCE_IDENTITY_CHECKPOINT" or record.get("serviceScope")!=scope
                    or record.get("sourceRecordSha256")!=service_cipher_digest(native)):
                raise ValueError("LKG TARGET lacks its original held SOURCE wrapping-key authority")
            checkpoint=record["identityCheckpoint"]
        else:
            common={key:scope[key] for key in ("instanceUuid","operationUuid","revision")}
            checkpoint=credential_json(rendered_read(self.driver.checkpoints/(scope["operationUuid"]+".json")))
            previous=self.driver.credential_source(common);reference=request.get("identityCheckpointRef")
            if (checkpoint.get("scope")!=common or checkpoint.get("sourceConfigurationSha256")!=previous.get("configurationSha256")
                    or not isinstance(reference,dict) or set(reference)!={"operationUuid","sha256"}
                    or reference.get("operationUuid")!=scope["operationUuid"] or reference.get("sha256")!=checkpoint.get("capsule",{}).get("sha256")):
                raise ValueError("LKG TARGET stage original checkpoint/ref/previous generation differs")
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        public=serialization.load_pem_public_key(str(request.get("publicKey") or "").encode())
        expected=serialization.load_pem_public_key(checkpoint["publicKey"].encode())
        if not isinstance(public,rsa.RSAPublicKey) or public.key_size<2048 or public.public_numbers()!=expected.public_numbers():
            raise ValueError("LKG TARGET wrapping key differs from its original RenderedBatch key")
        old=checkpoint["capsule"];raw=base64.b64decode(old["ciphertext"],validate=True)
        if old.get("scope")!=scope["instanceUuid"]+":"+scope["operationUuid"] or hashlib.sha256(raw).hexdigest()!=old.get("sha256"):
            raise ValueError("LKG TARGET original checkpoint ciphertext scope/digest differs")
        return old

    def retain_cipher(self,request,capsule):
        scope,saved,actual=self.validate({key:value for key,value in request.items() if key not in ("publicKey","identityCheckpointRef")})
        self.stopped({key:value for key,value in request.items() if key not in ("publicKey","identityCheckpointRef")})
        old=self.wrapping_key(request)
        if capsule==old:raise ValueError("Before-JOIN SOURCE ciphertext cannot become LKG TARGET")
        cipher=ServiceIdentityCipher(self.root);path=self.cipher_path(scope)
        raw=base64.b64decode(capsule["ciphertext"],validate=True)
        if capsule.get("scope")!=scope["instanceUuid"]+":"+scope["operationUuid"] or hashlib.sha256(raw).hexdigest()!=capsule.get("sha256"):
            raise ValueError("LKG TARGET ciphertext differs from its exact scope/digest")
        record={"schemaVersion":1,"kind":self.cipher_kind,"scope":scope,"targetConfigurationSha256":saved["targetConfigurationSha256"],
                "targetRecordSha256":service_cipher_digest(saved),"stoppedReceiptSha256":root_public_sha(saved["targetStoppedReceipt"]),
                "publicKey":request["publicKey"],"capsule":capsule,"bootId":saved["bootId"]}
        if path.exists() or path.is_symlink():
            if cipher.read(path)!=record:raise ValueError("LKG TARGET cannot replace its original independent ciphertext")
        else:cipher.write(path,record)
        return {"kind":self.cipher_kind,"scope":scope,"capsuleSha256":capsule["sha256"],
                "targetConfigurationSha256":saved["targetConfigurationSha256"],"checkpointRecordSha256":service_cipher_digest(record)}
    def cached_cipher(self,request):
        scope=self.scope({key:value for key,value in request.items() if key not in ("publicKey","identityCheckpointRef")})
        path=self.cipher_path(scope)
        if not path.exists() and not path.is_symlink():return None
        self.wrapping_key(request);plain={key:value for key,value in request.items() if key not in ("publicKey","identityCheckpointRef")}
        _,saved,actual=self.validate(plain);journal=credential_json(rendered_read(self.stop_path(scope)))
        if journal.get("phase") in ("RESUMED","RESUMING"):self.verify_resumed(scope,saved,actual)
        else:self.stopped(plain)
        record=ServiceIdentityCipher(self.root).read(path)
        if (record.get("kind")!=self.cipher_kind or record.get("scope")!=scope
                or record.get("targetRecordSha256")!=service_cipher_digest(saved) or record.get("bootId")!=saved["bootId"]
                or record.get("stoppedReceiptSha256")!=root_public_sha(saved["targetStoppedReceipt"])
                or record.get("targetConfigurationSha256")!=saved["targetConfigurationSha256"]):
            raise ValueError("LKG TARGET cached independent ciphertext authority differs")
        capsule=record["capsule"];raw=base64.b64decode(capsule["ciphertext"],validate=True)
        if capsule["scope"]!=scope["instanceUuid"]+":"+scope["operationUuid"] or hashlib.sha256(raw).hexdigest()!=capsule["sha256"]:
            raise ValueError("LKG TARGET cached ciphertext scope/digest differs")
        return {"capsule":capsule,self.checkpoint_field:{"kind":self.cipher_kind,"scope":scope,"capsuleSha256":capsule["sha256"],
                "targetConfigurationSha256":saved["targetConfigurationSha256"],"checkpointRecordSha256":service_cipher_digest(record)}}

    def verify_resumed(self,scope,saved,actual):
        owners=self.identity.owners()
        if {row["unit"] for row in owners}!={row["unit"] for row in saved["targetOwners"]}:
            raise ValueError("LKG TARGET resumed owner set differs")
        for owner in saved["targetOwners"]:self.controller.prove_unit(owner["unit"])
        verified=self.driver.verify(self.store.pointer())
        if set(verified)!=set(DOMAINS) or any(verified[name] is not True for name in DOMAINS):
            raise ValueError("LKG TARGET fresh resumed all-four readback failed")
        if self.identity.freeze(scope,actual)!=saved["targetPublicIdentity"]:
            raise ValueError("LKG TARGET resumed public SAM/domain/alias/ownership evidence differs")
        return {**self.receipt(saved),"targetRuntimeVerified":True,"canonicalDesiredStateChanged":False}

    def resume(self,request):
        scope,saved,actual=self.validate(request)
        journal=credential_json(rendered_read(self.stop_path(scope)))
        if journal.get("phase") in ("RESUMED","RESUMING"):
            result=self.verify_resumed(scope,saved,actual)
            if journal["phase"]!="RESUMED":journal["phase"]="RESUMED";rendered_json(self.stop_path(scope),journal)
            return result
        self.stopped(request)
        journal["phase"]="RESUMING";rendered_json(self.stop_path(scope),journal)
        try:
            for owner in sorted(saved["targetOwners"],key=lambda row:row["unit"]!="ablestack-storage-winbind.service"):
                if owner["unit"]=="ablestack-storage-winbind.service":
                    if self.winbind is None:raise ValueError("LKG TARGET winbind resume producer is unavailable")
                    self.winbind.start({key:scope[key] for key in ("instanceUuid","operationUuid","revision")})
                else:self.controller.run(["systemctl","start",owner["unit"]],timeout=self.controller.remaining())
                self.controller.prove_unit(owner["unit"])
            verified=self.driver.verify(self.store.pointer())
            if set(verified)!=set(DOMAINS) or any(verified[name] is not True for name in DOMAINS):raise ValueError("LKG TARGET fresh resume all-four readback failed")
            self.identity.unchanged(scope,actual,saved["targetPublicIdentity"])
            journal=credential_json(rendered_read(self.stop_path(scope)));journal["phase"]="RESUMED";rendered_json(self.stop_path(scope),journal)
            return {**self.receipt(saved),"targetRuntimeVerified":True,"canonicalDesiredStateChanged":False}
        except Exception:
            journal=credential_json(rendered_read(self.stop_path(scope)));journal["phase"]="RECOVERY_REQUIRED";rendered_json(self.stop_path(scope),journal);raise
