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

"""Typed ROOT TARGET LKG; independent CURRENT target and authenticated SOURCE key."""
from pathlib import Path
import os
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from service_identity_target import ServiceIdentityTarget
from service_identity_source import service_identity_writer
from root_source_recovery import RootSourceRecovery,root_recovery_scope
from root_source_identity_checkpoint import RootSourceIdentityCheckpoint
from root_ad_identity_authority import root_ad_retained_authority
from rendered_credentials import credential_json
from rendered_generation import rendered_read


class RootIdentityTarget(ServiceIdentityTarget):
    capture_kind="ROOT_IDENTITY_TARGET"
    stop_kind="ROOT_TARGET_STOP_JOURNAL"
    stopped_kind="ROOT_TARGET_STOPPED"
    cipher_kind="ROOT_TARGET_IDENTITY_CHECKPOINT"
    prefix="root-identity-target"
    stopped_flag="rootTargetStoppedVerified"
    maintenance_kind="ROOT"
    checkpoint_field="rootIdentityCheckpoint"
    def __init__(self,driver,controller,root=None,identity=None,writer=None,winbind=None):
        original=RootSourceRecovery(driver,root)
        self.source=original;self.driver=driver;self.store=driver.store;self.runtime=driver.runtime
        self.root=Path(root or os.environ.get("ABLESTACK_STORAGE_TEMPLATE_MAINTENANCE_DIR","/var/lib/ablestack-storage"))
        self.identity=identity or RootSourceIdentityCheckpoint(self.runtime)
        self.controller=controller;self.writer=writer or service_identity_writer;self.winbind=winbind
        # Existing public observation/canonical reader is reused without writing
        # its ROOT SOURCE namespace.
        self.source.held=self.source.marker
    def scope(self,request):
        base={"instanceUuid","templateUpgradeUuid","operationUuid","revision","targetConfigurationSha256"}
        extras=set(request)-base if isinstance(request,dict) else set()
        if extras not in ({"importedRootAuthorization"},{"retainedRootAuthorization"}) or set(request)!=base|extras:
            raise ValueError("ROOT TARGET requires exact ROOT4/target SHA and one opaque SOURCE role")
        scope=root_recovery_scope({key:request[key] for key in base-{"targetConfigurationSha256"}})
        digest=request["targetConfigurationSha256"]
        if not isinstance(digest,str) or len(digest)!=64 or any(c not in "0123456789abcdef" for c in digest):
            raise ValueError("ROOT TARGET checksum is invalid")
        self.authority(request)
        return scope
    def observation(self,request):
        scope=self.scope(request);self.writer();self.source.held(scope)
        actual=self.driver.generation();status=self.store.status();current=status.get("current");activation=status.get("activation")
        common={key:scope[key] for key in ("instanceUuid","operationUuid","revision")};generation=actual.get("generation") or {}
        if (actual.get("generationStatus")!="IN_SYNC" or actual.get("pendingOperationUuid") is not None
                or any(generation.get(key)!=value for key,value in common.items()) or generation.get("configurationSha256")!=request["targetConfigurationSha256"]
                or actual.get("configurationSha256")!=request["targetConfigurationSha256"] or not isinstance(current,dict) or current.get("scope")!=common
                or current.get("configurationSha256")!=request["targetConfigurationSha256"]
                or not isinstance(activation,dict) or activation.get("scope")!=common or activation.get("phase") not in ("VERIFIED","COMPLETE")
                or activation.get("targetSha256")!=current["manifestSha256"] or type(status.get("bootHeld")) is not bool
                or status["bootHeld"]!=(activation["phase"]=="VERIFIED")
                or actual.get("configurationDesiredState")!=credential_json(rendered_read(self.store.pointer()/"desired-state.json"))):
            raise ValueError("ROOT TARGET must be its actual committed current target before/after rendered finalize")
        return scope,actual,status
    def authority(self,request):
        role="importedRootAuthorization" if "importedRootAuthorization" in request else "retainedRootAuthorization"
        common={key:request[key] for key in ("instanceUuid","operationUuid","revision")}
        marker=self.runtime.command(("operation","maintenance","status"))
        value=root_ad_retained_authority(common,marker,reference=request[role])
        expected="ROOT_AD_IMPORTED_AUTHORIZATION" if role=="importedRootAuthorization" else "ROOT_RETAINED_AUTHORIZATION"
        if value["kind"]!=expected:raise ValueError("ROOT TARGET source opaque role differs")
        return value
    def local_sid(self,saved):return saved["targetPublicIdentity"]["publicAdIdentity"]["machineSid"]
    def export_target(self,request):
        result=super().export_target(request)
        _,saved,_=self.validate(request)
        return {**result,"rootSourceConfiguration":saved["targetPublicIdentity"]["rootSourceConfiguration"],"maintenanceKind":"ROOT","bootHeld":True,"sideEffects":False}
    def wrapping_key(self,request):
        controls={key:value for key,value in request.items() if key not in ("publicKey","identityCheckpointRef")}
        self.scope(controls);authority=self.authority(controls);checkpoint=authority["identityCheckpoint"]
        actual=serialization.load_pem_public_key(str(request.get("publicKey") or "").encode())
        expected=serialization.load_pem_public_key(checkpoint["publicKey"].encode())
        if not isinstance(actual,rsa.RSAPublicKey) or actual.key_size<2048 or actual.public_numbers()!=expected.public_numbers():
            raise ValueError("ROOT TARGET wrapping key is not authenticated original SOURCE key")
        return checkpoint["capsule"]
