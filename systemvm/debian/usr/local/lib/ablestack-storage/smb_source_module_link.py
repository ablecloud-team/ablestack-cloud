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

"""Narrow compatibility for the signed LOCAL recovery module-link repair."""
import hashlib,json,os,re,stat
from pathlib import Path

MODULE_LINK_ORIGINAL_CLI_SHA256="5d6314b5f74538e94e61d134ac2869fbb31d2c165ee74b0926fa0c77c8bff01b"
MODULE_LINK_BEGIN=("# BEGIN"+" EMBEDDED SIGNED LOCAL RECOVERY MODULES").encode()
MODULE_LINK_END=("# END"+" EMBEDDED SIGNED LOCAL RECOVERY MODULES").encode()

def normalize_module_link_cli(current,original,block_sha):
    """Remove only the exact signed module closure and its one guarded call."""
    if not re.fullmatch("[0-9a-f]{64}",block_sha):raise ValueError("LOCAL module block pin is invalid")
    if current.count(MODULE_LINK_BEGIN)!=2 or current.count(MODULE_LINK_END)!=2:
        raise ValueError("LOCAL module closure occurrence differs")
    for unused in range(2):
        start=current.index(MODULE_LINK_BEGIN);end=current.index(MODULE_LINK_END,start)+len(MODULE_LINK_END)
        if hashlib.sha256(current[start:end]).hexdigest()!=block_sha or current[end:end+1]!=b"\n":
            raise ValueError("LOCAL module closure bytes changed")
        current=current[:start]+current[end+1:]
    declaration=('LOCAL_SOURCE_MODULE_LINK_SHA256="'+block_sha+'"\n').encode()
    if current.count(declaration)!=2:raise ValueError("LOCAL module closure declaration differs")
    current=current.replace(declaration,b"")
    changed=(b"            if not source_module_link_compatibility(self,value,journal,LOCAL_SOURCE_MODULE_LINK_SHA256,regular_file,SmbIdentity):\n"
        b"                if not self.unconfigured_stop_bridge(value,journal):self.code_compatibility(value,journal)")
    previous=b"            if not self.unconfigured_stop_bridge(value,journal):self.code_compatibility(value,journal)"
    if current.count(changed)!=2:raise ValueError("LOCAL module compatibility call differs")
    current=current.replace(changed,previous)
    if current!=original:raise ValueError("LOCAL module repair changed other signed code")
    return current

def source_module_link_compatibility(owner,value,journal,block_sha,regular_reader=None,identity_type=None):
    """Status is observational; only the authenticated original import records approval."""
    old_sha=journal.get("cliSha256")
    if old_sha!=MODULE_LINK_ORIGINAL_CLI_SHA256:return False
    renderer_reader=not hasattr(owner,"handler")
    if renderer_reader:
        # for_renderer deliberately skips the producer constructor. Supply only
        # observation dependencies and require an import-authenticated receipt.
        if regular_reader is None or identity_type is None:
            raise ValueError("LOCAL module repair reader dependencies are absent")
        from copy import copy
        owner=copy(owner)
        owner.reader=regular_reader
        owner.runtime_root=getattr(owner,"runtime_root",Path("/opt/ablestack/storage-runtime"))
        owner.installation_root=getattr(owner,"installation_root",Path("/var/lib/ablestack-storage/runtime-updates"))
        owner.handler=identity_type(owner.cli)
        owner.boot=getattr(owner,"boot",lambda:Path("/proc/sys/kernel/random/boot_id").read_text().strip())
        owner.command=owner.native_command
    current=owner.cli.resolve(strict=True)
    if current!=(owner.runtime_root/"current/ablestack-storagectl").resolve(strict=True):
        raise ValueError("LOCAL module repair ENTRY is not installed")
    raw=owner.cli.read_bytes();new_sha=hashlib.sha256(raw).hexdigest()
    def signed(path,checksum):
        data=owner.compat_entry(path,checksum)
        manifest=json.loads(owner.reader(str(path.parent/"manifest.json"),64*1024)[0])
        authority=owner.installed_release_authority(path.parent,manifest)
        if not os.path.lexists(owner.installation_root/authority["transactionId"]/"manifest.sig"):
            raise ValueError("LOCAL module repair requires signed installation receipts")
        return data
    signed(current,new_sha)
    paths=list((owner.runtime_root/"releases").iterdir())
    if len(paths)>128:raise ValueError("LOCAL module repair history exceeds bound")
    originals=[]
    for folder in paths:
        metadata=folder.lstat()
        if not stat.S_ISDIR(metadata.st_mode):continue
        candidate=folder/"ablestack-storagectl"
        if not os.path.lexists(candidate):continue
        data=owner.reader(str(candidate))[0]
        if hashlib.sha256(data).hexdigest()==old_sha:originals.append(signed(candidate,old_sha))
    if len(originals)!=1:raise ValueError("LOCAL module repair original signed ENTRY is ambiguous")
    normalize_module_link_cli(raw,originals[0],block_sha)
    scope={key:value[key]for key in ("instanceUuid","operationUuid","revision","localCheckpointUuid")}
    record=owner.read(owner.path(scope,"cipher"))
    if not record or owner.read(owner.path(scope,"journal"))!=journal:
        raise ValueError("LOCAL module repair original checkpoint is absent")
    owner.validate_record(value,record)
    if (journal.get("scope")!=scope or journal.get("common")!=owner.common(value)
            or record.get("common")!=owner.common(value)or journal.get("publicKey")!=record.get("publicKey")
            or journal.get("sourceDesiredState")!=record.get("sourceDesiredState")
            or owner.boot()!=value["expectedBootId"]):
        raise ValueError("LOCAL module repair source or boot changed")
    stable={"schemaVersion":1,"kind":"LOCAL_SOURCE_MODULE_LINK_CODE_COMPATIBILITY","scope":scope,
        "common":owner.common(value),"originalCliSha256":old_sha,"currentCliSha256":new_sha,
        "moduleClosureSha256":block_sha,"checkpointReference":owner.reference(record)}
    path=owner.path(scope,"module-link-code-compatibility");saved=owner.read(path)
    if saved is not None:
        if saved!=stable:raise ValueError("LOCAL immutable module compatibility receipt changed")
        return True
    if renderer_reader:
        raise ValueError("LOCAL rendered reader requires the original authenticated module compatibility receipt")
    if journal.get("phase")!="RESUMED"or journal.get("imported")is True:
        raise ValueError("LOCAL module repair requires the original resumed pre-activation SOURCE")
    pending=owner.read(owner.handler.generations/"pending.json")
    current_generation=owner.read(owner.handler.generations/"current.json")
    if (not pending or pending.get("phase")!="PREPARED"or pending.get("previous")!=value["sourceGeneration"]
            or pending.get("beforeSha256")!=value["sourceConfigurationSha256"]
            or current_generation!=value["sourceGeneration"]
            or any(pending.get(key)!=scope[key]for key in ("instanceUuid","operationUuid","revision"))):
        raise ValueError("LOCAL module repair original pending writer changed")
    observed=owner.command(("operation","generation","status"))
    if (observed.get("generation")!=value["sourceGeneration"]
            or observed.get("configurationSha256")!=value["sourceConfigurationSha256"]
            or observed.get("configurationDesiredState")!=journal["sourceDesiredState"]
            or observed.get("pendingOperationUuid")!=scope["operationUuid"]
            or observed.get("bootId")!=value["expectedBootId"]):
        raise ValueError("LOCAL module repair canonical SOURCE changed")
    from template_maintenance import Maintenance
    from service_maintenance import ServiceMaintenance
    from rendered_generation import RenderedGeneration
    keeper=Maintenance();service=ServiceMaintenance(keeper,owner.cli,command=owner.command)
    held=service.status({**{key:scope[key]for key in ("instanceUuid","operationUuid","revision")},
        "maintenanceUuid":scope["operationUuid"]})
    checkpoint=keeper.read(service.journal)
    if (held.get("bootHeld")is not True or held.get("maintenanceKind")!="SERVICE"
            or held.get("phase")not in ("HELD","RECOVERY_REQUIRED")or not checkpoint
            or checkpoint.get("sourceGeneration")!=value["sourceGeneration"]):
        raise ValueError("LOCAL module repair lacks its original SERVICE hold")
    store=RenderedGeneration()
    rendered=store.status()
    source=checkpoint.get("sourceRendered")
    if (not isinstance(source,dict)or source.get("configurationSha256")!=value["sourceConfigurationSha256"]
            or source.get("scope")!={key:value["sourceGeneration"][key]for key in ("instanceUuid","operationUuid","revision")}
            or rendered.get("current")!=source or rendered.get("activation")!=checkpoint.get("sourceActivation")
            or rendered.get("bootHeld")is not False or os.path.lexists(store.generations/scope["operationUuid"])):
        raise ValueError("LOCAL module repair is not the original pre-activation SOURCE")
    if owner.stopped_observation()!=journal.get("stopped"):
        raise ValueError("LOCAL module repair stopped SOURCE changed")
    owner.match_private(record)
    if set(value)==set(owner.common(value)):return True
    owner.request(value,"import-local-source")
    owner.decode(value,record) # Authenticate the original cipher before recording compatibility.
    owner.write(scope,"module-link-code-compatibility",stable,immutable=True)
    return True
