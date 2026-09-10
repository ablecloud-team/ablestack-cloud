<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Cross Hypervisor DR VMware VDDK Connect Contract Design

Date: 2026-07-08

## 1. Purpose

This document defines the structural fix for the VMware to ABLESTACK DR sync
failure observed with plan `71182935-11c6-4ed3-aeec-ebde1486bdfa` and run
`ec474612-9bc4-4477-b4cd-0f71e31d6a1a`.

The previous raw-over-NBD source graph fix is already active. The current run
reached the VMware mover, created the target RBD image, and then failed while
VDDK tried to connect to the VMware source disk:

```text
nbdkit: vddk[1]: error: VixDiskLib_ConnectEx: One of the parameters was invalid
qemu-img: server reported: VixDiskLib_ConnectEx: One of the parameters was invalid
ERROR: DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID: qemu-img cannot open VDDK NBD source for disk0
```

The problem has moved from a QEMU graph syntax issue to a VMware source
connection contract issue. Cloud currently builds the guided mapping with
top-level `sourceExternalRef`, but it does not build a canonical `mapping.source`
object. FTCTL canonicalization expects `profile.source` fields such as
`vmId`, `externalRef`, `vcenterRef`, and source endpoint identity, so the runtime
manifest can be missing required VMware source identity while the plan still
passes readiness and target preparation.

## 2. Live Evidence

The live failed run showed the following state:

| Item | Value |
| --- | --- |
| Plan | `71182935-11c6-4ed3-aeec-ebde1486bdfa` |
| Run | `ec474612-9bc4-4477-b4cd-0f71e31d6a1a` |
| Direction | `VMWARE_TO_KVM` |
| Source site | VMware Direct, endpoint `10.10.21.10`, health `CONNECTED` |
| Target site | ABLESTACK KVM, endpoint `http://10.10.32.10:8080/client/api`, health `CONNECTED` |
| Run state | `FAILED` |
| Failed step | `runtime-projection` |
| Runtime error | `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` |
| Runtime details | `VixDiskLib_ConnectEx: One of the parameters was invalid` |
| Target side | RBD image was prepared, target VM was not materialized |

Runtime files confirmed that `vmware-disks.json` had `sourceVmRef=vm-4486`,
but `vcenterRef` was empty and `datastoreRef` was populated from the ABLESTACK
target storage reference rather than from VMware source identity. This is enough
to prove that the Cloud-to-FTCTL source contract is under-specified.

## 3. Design Goals

- The UI must never show a plan as ready when the VMware source connect contract
  is incomplete.
- The API/backend must validate fast contract requirements before dispatching a
  long-running async sync command.
- The backend must build a canonical `mapping.source` object for VMware source
  plans instead of relying on top-level compatibility fields only.
- FTCTL must fail before target conversion when required VDDK connect parameters
  are missing.
- FTCTL must distinguish QEMU source graph errors from VDDK connect parameter
  errors.
- No new DB schema is required for this change.

## 4. Affected Layers

| Layer | Change required | Reason |
| --- | --- | --- |
| UI | Surface source-contract readiness and specific VDDK connect errors | Operator must see why sync cannot start or why it failed |
| API | Enrich preview/readiness/start responses with source-contract state | UI must not infer readiness from generic plan state |
| Backend | Build `mapping.source`, validate it before dispatch, map new error codes | The missing contract originates in Cloud mapping and readiness |
| Agent | No new command; preserve FTCTL JSON and stderr | Agent should relay the more specific FTCTL failure |
| FTCTL | Validate VDDK connect parameters and emit specific exit/error codes | The engine owns final source connection semantics |
| DB | No schema change | Existing plan/run/status JSON fields can store contract and error data |

## 5. Cloud Backend Design

### 5.1 Guided mapping builder

File:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrPlanGuidedSpecBuilder.java
```

Current behavior:

```java
mapping.addProperty("sourceExternalRef", plan.getSourceExternalRef());
```

Target behavior:

```java
private JsonObject buildSource(DrPlanVO plan) {
    JsonObject source = new JsonObject();
    if (plan == null || !StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_")) {
        return source;
    }
    addString(source, "provider", "VMWARE");
    addString(source, "driver", "VMWARE_CBT");
    addLong(source, "siteId", plan.getSourceSiteId());
    addLong(source, "workerHostId", plan.getSourceWorkerHostId());
    addString(source, "vmId", plan.getSourceExternalRef());
    addString(source, "externalRef", plan.getSourceExternalRef());
    addString(source, "vcenterRef", sourceSiteUuid(plan.getSourceSiteId()));
    addString(source, "endpointRef", sourceSiteEndpoint(plan.getSourceSiteId()));
    addString(source, "datacenterRef", sourceSiteVmwareDatacenterRef(plan.getSourceSiteId()));
    return source;
}
```

`buildMapping()` must call `buildSource()` before `buildTarget()`:

```java
JsonObject source = buildSource(plan);
if (!source.entrySet().isEmpty()) {
    mapping.add("source", source);
}
```

The source object must not contain credentials or secrets. Secrets remain in the
credential path resolved during execution.

Canonical mapping shape:

```json
{
  "schemaVersion": "DR_PLAN_GUIDED_SPEC_V1",
  "direction": "VMWARE_TO_KVM",
  "sourceSiteId": 2,
  "targetSiteId": 3,
  "sourceExternalRef": "vm-4486",
  "source": {
    "provider": "VMWARE",
    "driver": "VMWARE_CBT",
    "siteId": 2,
    "vmId": "vm-4486",
    "externalRef": "vm-4486",
    "vcenterRef": "<source-site-uuid>",
    "endpointRef": "10.10.21.10",
    "datacenterRef": "<optional-vmware-datacenter-ref>"
  },
  "target": {
    "hypervisor": "KVM",
    "siteId": 3,
    "storageRef": "<ablestack-storage-ref>",
    "serviceOfferingId": "<service-offering-id>"
  },
  "disks": [
    {
      "sourceVmdkPath": "[datastore] vm/vm.vmdk",
      "sourceVmRef": "vm-4486",
      "sizeBytes": 107374182400,
      "targetDiskRef": "Rokcy10-1-dr-disk-0",
      "targetStorageRef": "<ablestack-storage-ref>",
      "targetDiskOfferingId": "<disk-offering-id>",
      "targetFormat": "raw"
    }
  ]
}
```

### 5.2 Readiness validator

File:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrPlanReadinessValidator.java
```

Add source-contract reason constants:

```java
public static final String REASON_VMWARE_SOURCE_SITE_REQUIRED = "VMWARE_SOURCE_SITE_REQUIRED";
public static final String REASON_VMWARE_SOURCE_CREDENTIAL_REQUIRED = "VMWARE_SOURCE_CREDENTIAL_REQUIRED";
public static final String REASON_VMWARE_SOURCE_ENDPOINT_REQUIRED = "VMWARE_SOURCE_ENDPOINT_REQUIRED";
public static final String REASON_VMWARE_SOURCE_VM_REF_REQUIRED = "VMWARE_SOURCE_VM_REF_REQUIRED";
public static final String REASON_VMWARE_SOURCE_VMDK_PATH_REQUIRED = "VMWARE_SOURCE_VMDK_PATH_REQUIRED";
public static final String REASON_VMWARE_SOURCE_CONTRACT_INCOMPLETE = "VMWARE_SOURCE_CONTRACT_INCOMPLETE";
```

Inject site credential lookup:

```java
@Inject
private DrSiteCredentialService drSiteCredentialService;
```

Add validation:

```java
private void validateVmwareSourceContract(DrPlanVO plan, JsonObject mapping, DrPlanReadiness readiness) {
    if (plan == null || !StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_")) {
        return;
    }

    DrSiteVO sourceSite = drSiteDao != null ? drSiteDao.findById(plan.getSourceSiteId()) : null;
    JsonObject source = objectAt(mapping, "source");
    String sourceVmRef = firstNonBlank(
            plan.getSourceExternalRef(),
            firstString(source, "vmId", "vmRef", "externalRef", "uuid"),
            firstString(mapping, "sourceExternalRef"));
    String endpoint = firstNonBlank(
            firstString(source, "endpointRef", "endpoint"),
            sourceSite != null ? sourceSite.getEndpoint() : null);

    if (sourceSite == null) {
        readiness.addBlockingReason(REASON_VMWARE_SOURCE_SITE_REQUIRED);
    }
    if (StringUtils.isBlank(endpoint)) {
        readiness.addBlockingReason(REASON_VMWARE_SOURCE_ENDPOINT_REQUIRED);
    }
    if (StringUtils.isBlank(sourceVmRef)) {
        readiness.addBlockingReason(REASON_VMWARE_SOURCE_VM_REF_REQUIRED);
    }
    if (!hasVmwareSourceCredentials(sourceSite)) {
        readiness.addBlockingReason(REASON_VMWARE_SOURCE_CREDENTIAL_REQUIRED);
    }
    validateVmwareDiskSourcePaths(mapping, readiness);
    if (containsVmwareSourceBlocker(readiness)) {
        readiness.setReasonCode(REASON_VMWARE_SOURCE_CONTRACT_INCOMPLETE);
        readiness.setMessage("VMware source connection information is incomplete; update the DR plan or source site before starting sync");
    }
}
```

Call order in `validateForExecution()`:

```java
validateWorkers(plan, readiness);
validateVmwareDataPlane(plan, readiness);
validateVmwareSourceContract(plan, mapping, readiness);
```

This must run before target materialization or agent dispatch.

### 5.3 Sync dispatch guard

File:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/orchestrator/DrProtectionOrchestratorImpl.java
```

Before materializing target replica disks or dispatching the agent:

```java
DrPlanReadiness readiness = drPlanReadinessValidator.validateForExecution(latestPlan);
if (!readiness.isExecutionReady()) {
    markRunFailed(run, readiness.getReasonCode(), readiness.getMessage(), readiness.toJson());
    throw new CloudRuntimeException(readiness.getMessage());
}
```

If the sync API has already created a `dr_run`, the run should be closed as
`FAILED` in the same transaction with `engineAccepted=false`. If the failure is
detected during preview or create, the API may return validation details without
creating a runtime run.

### 5.4 Error code mapping

File:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrConstants.java
```

Add:

```java
public static final String ERROR_VMWARE_VDDK_CONNECT_INVALID = "DR_VMWARE_VDDK_CONNECT_INVALID";
public static final String ERROR_VMWARE_VDDK_EXPORT_UNAVAILABLE = "DR_VMWARE_VDDK_EXPORT_UNAVAILABLE";
```

Projection adapters must preserve these codes from FTCTL runtime JSON:

```java
case "DR_VMWARE_VDDK_CONNECT_INVALID":
case "DR_VMWARE_VDDK_EXPORT_UNAVAILABLE":
case "DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID":
    markTerminalFailed(run, errorCode, sanitizedMessage);
    break;
```

## 6. API Contract Design

No new API command is required.

The existing preview/readiness responses should include a nested source contract
object:

```json
{
  "sourceContract": {
    "provider": "VMWARE",
    "ready": false,
    "vmRef": "vm-4486",
    "vcenterRef": "",
    "endpointPresent": true,
    "credentialPresent": true,
    "diskPathCount": 1,
    "missingFields": ["source.vcenterRef"]
  }
}
```

The start-sync command must remain asynchronous for long-running work. Fast
contract validation may complete immediately, but it must not block on VDDK
I/O, target storage creation, qemu-img conversion, or remote host activity.

Recommended start behavior:

| Situation | API behavior |
| --- | --- |
| Missing contract found before run creation | Return validation error with `sourceContract` |
| Missing contract found after run creation | Return run response with `state=FAILED`, `engineAccepted=false` |
| Contract valid | Return run/job response immediately after dispatch |

## 7. UI Design

The UI must consume the readiness/source-contract result instead of deriving
readiness from plan state alone.

Affected UI areas:

- DR Plan list status badge
- DR Plan detail readiness panel
- Sync start/retry action gating
- Latest run error panel

UI rules:

- Disable `Start Sync` when `sourceContract.ready=false`.
- Show the first blocking field as an actionable message:
  - `source.vcenterRef`: "VMware source site identity is missing. Re-save the source site or plan."
  - `source credential`: "VMware source credentials are missing or not configured."
  - `source disk path`: "One or more source disk paths are missing."
- For `DR_VMWARE_VDDK_CONNECT_INVALID`, show:
  - "VMware VDDK rejected the source connection parameters. Check vCenter endpoint, VM MoRef, disk path, and credentials."
- Do not display secret values.

## 8. FTCTL Contract Design

The FTCTL design companion is:

```text
ablestack-qemu-exec-tools/docs/ftctl/433-ftctl-dr-vmware-vddk-connect-contract-design-20260708.md
```

Cloud must provide enough non-secret source identity for that FTCTL contract:

- `profile.source.provider=VMWARE`
- `profile.source.driver=VMWARE_CBT`
- `profile.source.vmId` or `profile.source.externalRef`
- `profile.source.vcenterRef` or `profile.source.endpointRef`
- disk-level `sourceVmdkPath`
- source credentials in runtime credentials JSON

## 9. DB Design

No migration is required.

Use existing fields:

| Table | Field usage |
| --- | --- |
| `dr_plan` | `state`, `last_error_code`, `last_error_message`, `last_status_json` |
| `dr_run` | `state`, `error_code`, `error_message`, `last_status_json`, `engine_accepted` |
| `dr_run_step` | step-level failure and message |
| `dr_replica` | target materialization state |
| `dr_replica_disk` | disk-level state and optional `details_json.cleanupRequired` |
| `dr_event` | operator-visible source-contract and runtime failures |

If target storage was prepared before a VDDK connect failure, the backend should
record `cleanupRequired=true` in status/details JSON and expose cleanup as the
next recovery action. Future sync attempts must not silently reuse ambiguous
partial target artifacts unless the operator explicitly retries after cleanup or
the runtime proves the artifact belongs to the same run.

## 10. Test Plan

### Unit tests

| Test | Expected result |
| --- | --- |
| Guided VMware-to-KVM spec creates `mapping.source` | Source object contains provider, driver, site id, VM ref, vCenter/source endpoint refs |
| Readiness with missing source VM ref | `executionReady=false`, reason `VMWARE_SOURCE_VM_REF_REQUIRED` |
| Readiness with missing source credential | `executionReady=false`, reason `VMWARE_SOURCE_CREDENTIAL_REQUIRED` |
| Readiness with missing source disk path | `executionReady=false`, reason `VMWARE_SOURCE_VMDK_PATH_REQUIRED:<index>` |
| Start sync with incomplete source contract | Run is not dispatched to agent, or run is immediately failed with `engineAccepted=false` |
| Projection receives FTCTL exit 73 | Plan/run show `DR_VMWARE_VDDK_CONNECT_INVALID` |
| Projection receives FTCTL exit 74 | Plan/run show `DR_VMWARE_VDDK_EXPORT_UNAVAILABLE` |

### Integration smoke

1. Create VMware-to-ABLESTACK plan.
2. Preview/readiness must include `sourceContract.ready=true`.
3. Start sync.
4. Confirm agent dispatch returns immediately.
5. Confirm FTCTL status either progresses to restore point creation or fails
   with a specific VDDK connect/export error.
6. Confirm UI list/detail show the same run state and error code as DB.

## 11. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Source mapping | Only top-level `sourceExternalRef` is guaranteed | Canonical `mapping.source` is generated for VMware source plans |
| Readiness | Checks VDDK libdir, workers, target placement, disks | Also checks VMware source endpoint, credential, VM ref, and disk paths |
| Dispatch | Can prepare target storage before source contract is proven | Blocks or fails run before target prep when source contract is incomplete |
| FTCTL error | VDDK ConnectEx can appear as source graph invalid | ConnectEx maps to `DR_VMWARE_VDDK_CONNECT_INVALID` |
| Operator UI | Generic failure after sync starts | Actionable readiness and runtime messages before/after sync |
| DB | Existing fields store generic error | Existing fields store specific source-contract and VDDK errors |

## 12. Live Manual Preflight Refinement

After this design was drafted, the intended source-open path was manually
validated on the actual target worker `10.10.32.1` against the real source VM
`vm-4486` and source disk.

Validated source inventory:

| Field | Value |
| --- | --- |
| vCenter endpoint | `10.10.21.10` |
| Source VM MoRef | `vm-4486` |
| Source VM name | `Rokcy10-1` |
| Source VM inventory path | `/Datacenter/vm/Rokcy10-1` |
| Source ESXi host | `10.10.21.3` |
| Source disk key | `2000` |
| Source disk path | `[3-host-local-disk] test1/test1.vmdk` |
| Source disk size | `107374182400` bytes |
| Data-plane worker | `10.10.32.1` |
| VDDK libdir | `/usr/share/ablestack/v2k/compat/vsphere80/vddk` |

The test used the same non-destructive boundary the implementation should use:
`nbdkit` exposes the VDDK source read-only, and `qemu-img info --image-opts`
checks whether QEMU can open the source graph. It does not convert data or write
to the target disk.

Manual results:

| Test | Result | Design conclusion |
| --- | --- | --- |
| `vm` parameter omitted | nbdkit rejects startup: `missing parameter: vm` | VM reference is mandatory |
| `vm=moref=vm-4486`, no snapshot | VDDK fails with `DiskLib error 16392: Failed to lock the file` | Powered-on VM sync requires a run snapshot |
| `vm=moref=vm-4486`, snapshot MoRef, base VMDK path read from vCenter | `qemu-img info` succeeds and reports 100 GiB raw source | This is the required base-transfer source-open contract |
| snapshot MoRef plus current delta VMDK path | VDDK fails with access-rights error | The mover must use the base backing VMDK path, not the post-snapshot delta path |
| VMDK path passed through a lossy shell/local encoding path | datastore name can appear as `????` and open fails | Cloud/FTCTL must preserve UTF-8 paths from JSON/govc and avoid lossy shell re-encoding |

Successful source-open shape:

```bash
LD_LIBRARY_PATH="${vddk_libdir}" \
nbdkit --exit-with-parent --foreground --unix "${socket}" -r vddk \
  "libdir=${vddk_libdir}" \
  "server=${vcenter_server}" \
  "user=${vcenter_user}" \
  "password=+${password_file}" \
  "thumbprint=${vcenter_thumbprint}" \
  "vm=moref=${source_vm_moref}" \
  "snapshot=${run_snapshot_moref}" \
  "transports=nbd:nbdssl" \
  "file=${base_vmdk_path}"

qemu-img info --force-share --image-opts \
  "driver=raw,file.driver=nbd,file.server.type=unix,file.server.path=${socket}"
```

This changes the target design from "validate that a source VM ref exists" to
"create/resolve a run snapshot, preserve the base VMDK path, and verify VDDK can
open that snapshot view before target conversion."

## 13. Snapshot-Aware Cloud/Backend Contract

The UI/API must not ask the user for a snapshot reference. Snapshot lifecycle is
runtime-owned because every sync run needs a consistent source point-in-time.

Backend responsibilities:

- Keep `mapping.source.vmId` / `externalRef` as the vCenter VM MoRef.
- Keep disk-level base VMDK path from vCenter inventory as
  `disks[].source.vmdkPath` and `disks[].sourceVmdkPath`.
- Preserve UTF-8 JSON end to end; do not rebuild datastore paths through a
  non-UTF-8 shell path.
- Do not require `snapshotRef` during plan create/update readiness.
- During sync dispatch, pass enough source credential and source VM/disk
  identity for FTCTL to create a run snapshot asynchronously.

Recommended profile addition before agent dispatch:

```json
{
  "source": {
    "provider": "VMWARE",
    "driver": "VMWARE_CBT",
    "vmId": "vm-4486",
    "externalRef": "vm-4486",
    "endpointRef": "10.10.21.10",
    "snapshotPolicy": {
      "createForSync": true,
      "memory": false,
      "quiesce": false,
      "namePrefix": "ftctl-dr"
    }
  }
}
```

Readiness refinement:

| Check | Blocking before dispatch? | Reason |
| --- | --- | --- |
| source site/credential/endpoint present | yes | cannot create snapshot or open VDDK |
| source VM MoRef present | yes | `vm` parameter is mandatory |
| disk base VMDK path present and UTF-8 preserved | yes | VDDK `file` parameter must be exact |
| run snapshot already present | no | FTCTL creates it in the async worker |
| source open preflight succeeds | async runtime step | requires actual VDDK/NFC access and snapshot |

New runtime step sequence:

```text
prepare
dispatch-agent
agent-accept
source-snapshot-create
source-open-preflight
target-prepare
base-transfer
restore-point-create
source-snapshot-cleanup
projection-complete
```

If `source-open-preflight` fails, FTCTL must remove the run-created snapshot
before returning terminal failure when cleanup is safe.

## 14. Additional Error Mapping

The manual validation exposed two failure families that should not be collapsed
into the old source graph error.

Add constants:

```java
public static final String ERROR_VMWARE_VDDK_SOURCE_LOCKED = "DR_VMWARE_VDDK_SOURCE_LOCKED";
public static final String ERROR_VMWARE_VDDK_OPEN_DENIED = "DR_VMWARE_VDDK_OPEN_DENIED";
```

Projection mapping:

| FTCTL exit | Cloud error code | Evidence |
| --- | --- | --- |
| 72 | `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` | QEMU raw-over-NBD graph is invalid |
| 73 | `DR_VMWARE_VDDK_CONNECT_INVALID` | `VixDiskLib_ConnectEx` rejects connection parameters |
| 74 | `DR_VMWARE_VDDK_EXPORT_UNAVAILABLE` | VDDK export cannot be opened but reason is not classified |
| 75 | `DR_VMWARE_VDDK_SOURCE_LOCKED` | `DiskLib error 16392: Failed to lock the file` |
| 76 | `DR_VMWARE_VDDK_OPEN_DENIED` | `You do not have access rights to this file` |

Operator guidance:

- `DR_VMWARE_VDDK_SOURCE_LOCKED`: retry after FTCTL creates a run snapshot, or
  investigate why snapshot creation was skipped.
- `DR_VMWARE_VDDK_OPEN_DENIED`: check that the mover uses the base VMDK path
  from inventory and not the current delta VMDK path.
- `DR_VMWARE_VDDK_CONNECT_INVALID`: check endpoint, VM MoRef, snapshot MoRef,
  thumbprint, and credential.

## 15. Live CBT And RPO Incremental Validation

The RPO cycle was also manually validated on the same VM after the source-open
preflight. This closes the gap between "base source can be opened" and
"periodic incremental sync can be driven by CBT."

Observed state before the test:

| Check | Result |
| --- | --- |
| `config.changeTrackingEnabled` | `true` |

> 2026-08-10 clarification: this property is retained as a diagnostic signal,
> not a standalone hard gate. Authoritative readiness requires selected-disk
> ExtraConfig, a non-empty snapshot change ID, and successful CBT query evidence
> as specified by
> `600-cross-hypervisor-dr-vmware-cbt-activation-convergence-design-20260810.md`.
| active VMware snapshot | none |
| active backing path | `[3-host-local-disk] test1/test1.vmdk` |

Validation sequence:

1. Create baseline snapshot `S1` with `memory=false`.
2. Call `QueryChangedDiskAreas` helper with empty change-id.
3. Persist returned `new_change_id`.
4. Remove `S1`.
5. Create RPO snapshot `S2`.
6. Call `QueryChangedDiskAreas(snapshot=S2, changeId=S1.new_change_id)`.
7. Remove `S2`.
8. Confirm there is no remaining snapshot and the backing path returned to the
   base VMDK.

Observed result:

| Item | Value |
| --- | --- |
| baseline `new_change_id` | `.../636` |
| incremental `new_change_id` | `.../640` |
| changed areas | 2 |
| changed bytes | 131072 |
| cleanup result | snapshot tree returned to `null` |

This proves that the DR engine can keep snapshot depth low. A previous snapshot
does not need to remain present after the previous durable checkpoint if its
per-disk CBT `changeId` is persisted.

## 16. RPO Cycle Design

Add a CBT-aware checkpoint cycle to the async FTCTL worker:

```text
base sync:
  ensure-cbt-enabled
  create snapshot S0
  open source with VDDK snapshot S0
  transfer full base image
  read/persist per-disk new_change_id
  mark restore point durable
  remove S0

each RPO cycle:
  create snapshot Sn
  QueryChangedDiskAreas(snapshot=Sn, changeId=last_durable_change_id)
  transfer only changed extents from snapshot Sn
  persist new_change_id after target patch is durable
  remove Sn
```

Cloud DB persistence should use existing JSON fields:

| DB location | JSON content |
| --- | --- |
| `dr_replica_disk.details_json` | latest durable per-disk `cbt.lastChangeId`, `cbt.lastSnapshotName`, `cbt.lastSnapshotRef`, `cbt.lastRpoAt` |
| `dr_restore_point_artifact.details_json` | checkpoint disk artifact, changed-area count, bytes, old/new changeId |
| `dr_run.last_status_json` | active snapshot, current changed-area progress, cleanupRequired |
| `dr_event.details_json` | operator-visible checkpoint and cleanup evidence |

Example disk details:

```json
{
  "cbt": {
    "enabled": true,
    "diskId": "scsi0:0",
    "lastChangeId": "52.../640",
    "lastChangedBytes": 131072,
    "lastChangedAreas": 2,
    "lastRpoAt": "2026-07-08T00:51:20+09:00"
  }
}
```

Readiness and runtime gates:

| Gate | Behavior |
| --- | --- |
| CBT disabled and auto-enable disabled | fail readiness or run step with `DR_VMWARE_CBT_DISABLED`; do not silently fall back to full scan |
| CBT disabled and auto-enable enabled | run `ensure-cbt-enabled`, verify VM/disk CBT flags, then continue |
| empty changeId on initial base | allowed; base sync persists first durable changeId |
| empty changeId on incremental | block incremental and require a new base checkpoint |
| snapshot cleanup failure | mark `cleanupRequired=true` and keep UI warning until cleanup succeeds |
| changed-area query failure | fail current RPO cycle before target patching |

Additional error constants:

```java
public static final String ERROR_VMWARE_CBT_DISABLED = "DR_VMWARE_CBT_DISABLED";
public static final String ERROR_VMWARE_CBT_ENABLE_FAILED = "DR_VMWARE_CBT_ENABLE_FAILED";
public static final String ERROR_VMWARE_CBT_VERIFY_FAILED = "DR_VMWARE_CBT_VERIFY_FAILED";
public static final String ERROR_VMWARE_CBT_DISK_ID_UNRESOLVED = "DR_VMWARE_CBT_DISK_ID_UNRESOLVED";
public static final String ERROR_VMWARE_CBT_CHANGE_ID_MISSING = "DR_VMWARE_CBT_CHANGE_ID_MISSING";
public static final String ERROR_VMWARE_CBT_QUERY_FAILED = "DR_VMWARE_CBT_QUERY_FAILED";
public static final String ERROR_VMWARE_SNAPSHOT_CLEANUP_REQUIRED = "DR_VMWARE_SNAPSHOT_CLEANUP_REQUIRED";
```

The UI should show RPO checkpoint state separately from plan state:

- latest durable checkpoint time;
- last changed bytes;
- last changed area count;
- snapshot cleanup warning if any run-created snapshot remains.

## 17. CBT Check And Auto-Enable Design

The live VM already had CBT enabled, so the previous validation proved the
enabled path. The implementation must also handle the disabled path explicitly.

Policy:

```json
{
  "source": {
    "driver": "VMWARE_CBT",
    "cbtPolicy": {
      "required": true,
      "autoEnable": true,
      "failIfPreExistingSnapshots": false
    }
  }
}
```

Cloud UI/API behavior:

- Default `cbtPolicy.required=true` for VMware-to-ABLESTACK DR.
- Default `cbtPolicy.autoEnable=true` unless an operator explicitly disables it
  in advanced policy.
- If auto-enable is disabled and source CBT is off, block sync before dispatch
  with `DR_VMWARE_CBT_DISABLED`.
- If auto-enable is enabled, dispatch remains asynchronous; the ftctl worker
  performs the enable operation and reports status through run/status JSON.

Runtime step expansion:

```text
ensure-cbt-enabled:
  discover VM CBT state
  discover selected disk CBT state
  resolve disk ids such as scsi0:0 from source disk key/backing
  if disabled and autoEnable=true:
    enable VM-level CBT
    enable disk-level CBT for each selected disk
    re-read VM/disk CBT state
  create baseline snapshot
  verify baseline changeId can be produced
```

The selected disk inventory must therefore include enough information to resolve
the vSphere CBT disk id:

```json
{
  "source": {
    "diskRef": "2000",
    "diskKey": 2000,
    "controllerKey": 1000,
    "controllerBusNumber": 0,
    "unitNumber": 0,
    "cbtDiskId": "scsi0:0",
    "vmdkPath": "[datastore] vm/vm.vmdk"
  }
}
```

If Cloud inventory cannot provide `cbtDiskId`, FTCTL may resolve it through the
vSphere hardware device graph. If neither layer can resolve it, fail with
`DR_VMWARE_CBT_DISK_ID_UNRESOLVED`.

CBT state JSON:

```json
{
  "cbt": {
    "required": true,
    "autoEnable": true,
    "vmEnabledBefore": false,
    "vmEnabledAfter": true,
    "enabledByFtctl": true,
    "disks": [
      {
        "diskRef": "2000",
        "cbtDiskId": "scsi0:0",
        "enabledBefore": false,
        "enabledAfter": true
      }
    ]
  }
}
```

Pre-existing snapshots:

- If `failIfPreExistingSnapshots=true` and non-FTCTL snapshots exist before CBT
  enable, block with `DR_VMWARE_CBT_SNAPSHOT_CONFLICT`.
- The current guided-plan default is `false` to keep first sync asynchronous and
  avoid blocking a valid baseline solely because an operator snapshot exists.
- FTCTL must never delete operator-created snapshots.
- The operator can retry after manually resolving those snapshots, or a later
  product policy can allow a new full baseline with explicit acknowledgement.

Additional error constant:

```java
public static final String ERROR_VMWARE_CBT_SNAPSHOT_CONFLICT = "DR_VMWARE_CBT_SNAPSHOT_CONFLICT";
```

## 18. Implementation Update - 2026-07-08

Implemented in Cloud/UI:

- VMware source inventory now carries `controllerBusNumber`, `unitNumber`,
  `deviceKey`, and inferred `cbtDiskId=scsiX:Y` in disk option details.
- DR Plan UI preserves those fields in `diskmappingsjson` instead of only
  sending the vCenter disk key such as `2000`.
- Guided spec sanitization and target placement resolution preserve the same
  fields and infer `scsiX:Y` when bus/unit are available.
- FTCTL profile generation adds source endpoint `cbtPolicy` and the guided
  policy adds `required=true`, `autoEnable=true`,
  `failIfPreExistingSnapshots=false`.
- Cloud constants and ko/en locale messages now include the explicit CBT
  failure codes returned by FTCTL.

## 19. Live Preflight Regression - 2026-07-08

A live VMware-to-ABLESTACK plan exposed a contract mismatch between Cloud's
runtime file model and qemu FTCTL's CBT preflight reader.

Observed plan:

| Item | Value |
| --- | --- |
| Plan UUID | `bb4a6719-13c7-49de-a8ce-f5e04ff640a7` |
| Direction | `VMWARE_TO_KVM` |
| Source site | `21 VMware ESXi Cluster` |
| Target site | `32 ABLESTACK Cluster` |
| Source VM ref | `vm-4486` |
| Worker host | `10.10.32.1` |
| Plan state | `ERROR` |
| Run state | `FAILED` |
| Failed step | `runtime-projection` |
| Error code | `DR_VMWARE_CBT_QUERY_FAILED` |
| FTCTL step | `vmware-cbt-preflight` |

The qemu runtime directory contained the expected split runtime files:

- `profile.json`: non-secret DR topology, source external ref, policy, and disk
  mapping.
- `credentials.json`: vCenter endpoint, account, password, TLS policy, VDDK
  location, and compatibility version.
- `vmware-cbt.json`: redacted CBT preflight status.

The CBT status reported a missing endpoint/username/password/source VM
reference. A direct preflight using the runtime `credentials.json` on the same
worker host proved the underlying environment is healthy:

| Check | Result |
| --- | --- |
| vCenter API | reachable through compatibility `govc` |
| vCenter version | `8.0.1` build `21560480` |
| `govc vm.info -json vm-4486` | succeeded |
| VM CBT | enabled |
| Selected disk key | `2000` |
| Resolved CBT disk id | `scsi0:0` |
| Disk CBT | enabled |

The active worker hosts do not expose `govc` on PATH, but the compatibility
bundle contains a valid binary:

```text
/usr/share/ablestack/v2k/compat/vsphere80/bin/govc
```

Cloud conclusion:

- Cloud's runtime split between `profile.json` and `credentials.json` is the
  right security model and must be preserved.
- Cloud must guarantee that the agent/FTCTL invocation receives the credential
  file path, normally through `FTCTL_DR_CREDENTIALS_FILE`.
- Cloud should keep enriching the disk mapping with `sourceDiskKey`,
  `controllerKey`, `unitNumber`, `controllerBusNumber`, `sourceDiskRef`, and
  inferred `cbtDiskId` when available.
- FTCTL must still be resilient when Cloud can only provide disk key plus
  backing path, because vCenter responses vary by source inventory path.
- UI should not show a generic failed state when `vmware-cbt.json` contains a
  more precise redacted failure message.

## 20. Remediation Design - Cloud/API/UI Contract Updates

This design keeps the current asynchronous flow:

```text
UI action
  -> Cloud API creates run and returns job/run identity
  -> backend persists run/step state
  -> backend dispatches agent command with profile + credentials file contract
  -> agent executes FTCTL asynchronously
  -> FTCTL writes redacted runtime status
  -> backend polls/collects runtime status
  -> UI renders run progress and next available actions
```

No API call should synchronously wait for VMware CBT enablement, snapshot
creation, or VDDK data transfer.

### 20.1 API/backend command contract

The backend command builder that dispatches `dr-sync-start`, `dr-sync-resume`,
and related VMware sync actions must provide both runtime files explicitly.

Expected command context:

```java
public final class DrAgentCommandContext {
    private String planUuid;
    private String runUuid;
    private Path runtimeDir;
    private Path profileFile;
    private Path credentialsFile;
    private Map<String, String> environment;

    public void prepareEnvironment() {
        environment.put("FTCTL_DR_PROFILE_FILE", profileFile.toString());
        environment.put("FTCTL_DR_CREDENTIALS_FILE", credentialsFile.toString());
        environment.put("FTCTL_DR_RUNTIME_DIR", runtimeDir.toString());
    }
}
```

Rules:

- `profile.json` must not contain plaintext password, API secret, session token,
  or any equivalent secret.
- `credentials.json` must be written with owner-only permissions on the worker
  host.
- Cloud logs must print only the file path and redacted credential summary.
- If `credentials.json` is missing, backend should fail the run before agent
  dispatch with `DR_CREDENTIALS_FILE_MISSING` and a clear operator message.
- If the agent accepted the command, subsequent VMware-specific failures should
  be stored on the run step as FTCTL-originated details.

### 20.2 Source credential projection

Cloud should continue to store site credentials in the secure site credential
model, then project them only at runtime:

```java
public DrRuntimeCredentials buildRuntimeCredentials(DrPlanVO plan) {
    DrSiteVO sourceSite = siteDao.findById(plan.getSourceSiteId());
    DrSiteCredential credential = credentialService.resolveActive(sourceSite.getId());

    return DrRuntimeCredentials.source()
        .endpoint(normalizeVmwareEndpoint(sourceSite.getEndpoint()))
        .principal(credential.getPrincipal())
        .password(credentialSecretService.decryptPassword(credential))
        .tlsVerify(credential.isTlsVerify())
        .vddkLibdir(resolveVddkLibdir(sourceSite, credential))
        .vddkVersion(resolveVddkVersion(sourceSite, credential))
        .build();
}
```

The runtime writer serializes this as:

```json
{
  "credentials": {
    "source": {
      "endpoint": "10.10.21.10",
      "principal": "administrator@ablecloud.local",
      "auth": {
        "type": "password",
        "password": "<runtime-only secret>"
      },
      "tlsVerify": false,
      "vddkLibdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk",
      "vddkVersion": "8"
    }
  }
}
```

Only the runtime host file may contain the secret. API responses and persisted
run details must redact it.

### 20.3 Disk identity enrichment

The VMware inventory adapter should produce a disk option that is useful both
for UI selection and qemu-side CBT normalization:

```java
public final class DrVmwareDiskOption {
    private String diskKey;            // "2000"
    private String controllerKey;      // "1000"
    private Integer controllerBusNumber; // 0
    private Integer unitNumber;        // 0
    private String cbtDiskId;          // "scsi0:0"
    private String backingPath;        // "[datastore] vm/vm.vmdk"
    private Long capacityBytes;
}
```

Inventory parsing should resolve `cbtDiskId` from the full VM hardware graph:

```java
Map<String, Integer> scsiBusByControllerKey = new HashMap<>();
for (VirtualDevice device : vmHardware.getDevice()) {
    if (device instanceof VirtualSCSIController) {
        scsiBusByControllerKey.put(String.valueOf(device.getKey()),
            ((VirtualSCSIController) device).getBusNumber());
    }
}

for (VirtualDevice device : vmHardware.getDevice()) {
    if (device instanceof VirtualDisk) {
        VirtualDisk disk = (VirtualDisk) device;
        Integer bus = scsiBusByControllerKey.get(String.valueOf(disk.getControllerKey()));
        Integer unit = disk.getUnitNumber();
        String cbtDiskId = bus != null && unit != null ? "scsi" + bus + ":" + unit : null;
        // include disk key, backing path, capacity, and cbtDiskId in option details
    }
}
```

If an inventory source cannot provide controller details, Cloud still sends
`diskKey` and `backingPath`; FTCTL performs the final fallback resolution from
`govc vm.info -json`.

### 20.4 Run status and UI projection

Backend should collect redacted CBT status from FTCTL when the run fails or
transitions through `runtime-projection`:

```java
public DrRunStatusDetails readFtctlRuntimeDetails(DrRunVO run) {
    Optional<JsonNode> cbtStatus = ftctlRuntimeClient.readJson(run, "vmware-cbt.json");
    if (cbtStatus.isPresent()) {
        return DrRunStatusDetails.builder()
            .errorCode(cbtStatus.path("error_code").asText(null))
            .message(cbtStatus.path("message").asText(null))
            .cbt(cbtStatus.path("cbt"))
            .disks(cbtStatus.path("disks"))
            .redacted(true)
            .build();
    }
    return DrRunStatusDetails.empty();
}
```

UI display rules:

- List page shows plan/run state and a short localized status.
- Detail page run/progress panel shows the redacted FTCTL message when present.
- Health or runtime JSON blobs are not displayed raw.
- The operator should see actionable guidance such as:
  - vCenter connection failed
  - govc binary not found
  - CBT disk id could not be resolved
  - CBT snapshot conflict
- Buttons remain state-gated and continue to call asynchronous APIs.

### 20.5 DB impact

No schema change is required.

| Data | Existing location |
| --- | --- |
| Plan high-level state | `dr_plan.state`, `dr_plan.last_error_code`, `dr_plan.last_error_message` |
| Run state | `dr_run.state`, `dr_run.error_code`, `dr_run.error_message`, `dr_run.last_status_json` |
| Step state | `dr_run_step.state`, `dr_run_step.error_code`, `dr_run_step.details_json` |
| Runtime file references | `last_status_json` and step `details_json` |

The design relies on better contents in `details_json`, not a new table.

### 20.6 Acceptance criteria

- Creating a VMware-to-ABLESTACK plan still stores no plaintext secret in Cloud
  API responses, plan JSON, or DB runtime details.
- Starting sync returns immediately with an async job/run identity.
- The agent command receives `FTCTL_DR_PROFILE_FILE` and
  `FTCTL_DR_CREDENTIALS_FILE`.
- A valid runtime credential file allows FTCTL CBT preflight to query vCenter.
- Source VM `vm-4486` disk key `2000` is represented as `scsi0:0` either by
  Cloud inventory or by FTCTL fallback normalization.
- UI surfaces the specific redacted CBT preflight result instead of a generic
  failed state.
- No new DB migration is needed for this remediation.

## 21. Implementation Update - 2026-07-08

The preflight-validated contract is now wired through the Cloud observation path.

| Layer | Implemented behavior |
| --- | --- |
| API | `DrPlanResponse` and `DrRunResponse` expose redacted CBT summary fields derived from `last_status_json.cbt_status`. |
| Backend | `DrResponseGenerator` extracts `enabled`, `cbtDiskId`, `message`, `govcBin`, and `checkedAtEpochMs` from the ftctl runtime status without introducing a new table. |
| UI | The DR run progress component displays a compact CBT status note; the DR plan overview detail list shows CBT status, disk ID, and preflight message. |
| Agent | No contract change. Existing status polling continues to return the ftctl status JSON stored in run status. |
| FTCTL | ftctl now emits redacted `cbt_status` in `dr-status --json` and resolves runtime credentials plus compat `govc` during CBT preflight. |
| DB | No migration. Existing `dr_run.last_status_json` and step details carry the redacted runtime evidence. |

The user-facing result is that a VMware sync failure no longer stops at a
generic `DR_VMWARE_CBT_QUERY_FAILED` state. Operators can see whether the CBT
preflight reached vCenter, which `govc` binary was used, and which CBT disk ID
was resolved, without exposing credentials.

## 22. Live Regression - Source Open And Detail API Safety - 2026-07-08

The next live plan showed two independent failures that must be fixed together
before another sync retry can be considered reliable.

Observed plan:

| Item | Value |
| --- | --- |
| Plan UUID | `9e0aaaae-5d0f-4f12-9edf-49ad94f96056` |
| Run UUID | `ed5519bb-0d77-4580-92af-f833346cd456` |
| Direction | `VMWARE_TO_KVM` |
| Source VM ref | `vm-4486` |
| Worker host | `10.10.32.1` |
| Plan DB state | `ERROR` |
| Run DB state | `FAILED` |
| FTCTL step | `scheduler-failed` |
| FTCTL worker exit | `72` |
| Current runtime error | `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` |
| Actual VDDK evidence | `VixDiskLib_ConnectEx: One of the parameters was invalid` |

Layer consistency:

- Cloud API and backend did dispatch the command asynchronously.
- Agent accepted the command and ftctl created runtime files on host
  `10.10.32.1`.
- CBT preflight is no longer the blocker; the selected disk is resolved as
  `scsi0:0`.
- The data mover fails when opening the VMware source through VDDK/NBD.
- `getDrPlan` can return invalid JSON when raw runtime/step details contain a
  Korean datastore path that has been escaped into an invalid form such as
  `\...` before Korean characters.

The invalid JSON issue is a product defect separate from the VDDK source-open
failure. It makes the UI detail page look worse than the actual backend state
because the list/run APIs can still show a terminal failed run while the plan
detail call fails to parse.

## 23. Updated Affected Layers

| Layer | Change required | Reason |
| --- | --- | --- |
| UI | Add detail-load fallback and render latest run/replica state even when `getDrPlan` fails | The UI must not remain in skeleton state when only the detail response is malformed |
| API | Make `getDrPlan` response JSON-safe and keep raw step details out of nested plan responses | Plan detail currently nests raw run/step payloads that can break JSON escaping |
| Backend | Store short sanitized error messages; keep structured runtime JSON in status fields; classify VDDK errors | Operators need specific errors without leaking raw logs or corrupting API JSON |
| Agent | Preserve final ftctl JSON and raw stderr for backend ingestion, but do not require UI/API to expose it raw | Agent remains transport, not presentation authority |
| FTCTL | Add snapshot-aware VDDK source-open preflight and specific error codes | The current source-open contract is rejected by VDDK |
| DB | No schema change; update stored value discipline | Existing `last_status_json`, `details_json`, and error fields are enough if sanitized |

## 24. API/Backend Response Safety Design

### 24.1 Do not nest full run steps in `getDrPlan`

Current code path:

```java
// DrResponseGenerator.createPlanResponse
List<DrRunStepVO> latestSteps = drRunStepDao.listActiveByRunId(latestRun.getId());
response.setLastRun(createRunResponse(latestRun, latestSteps, false));
response.setLastErrorMessage(plan.getLastErrorMessage());
```

This makes plan detail include the latest run and every run step detail. The
step details can contain a nested agent answer and ftctl stdout JSON. That is
too large and too fragile for the primary plan detail response.

Target design:

```java
public DrPlanResponse createPlanResponse(DrPlanVO plan, Map<String, Boolean> actionEligibility) {
    ...
    DrRunVO latestRun = drRunDao.findLatestByPlanId(plan.getId());
    if (latestRun != null) {
        JsonObject runtime = parseObject(latestRun.getLastStatusJson());
        response.setLastRun(createRunSummaryResponse(latestRun, runtime));
        populateRuntimeSummary(response, latestRun, runtime);
    }
    response.setLastErrorCode(plan.getLastErrorCode());
    response.setLastErrorMessage(summarizeError(plan.getLastErrorCode(), plan.getLastErrorMessage()));
    ...
}
```

`createRunSummaryResponse()` must not include `steps`. Step details remain
available through `listDrRunSteps` or a dedicated latest-run-steps API.

### 24.2 Sanitize and normalize step details

When a command response still returns step details, the backend must normalize
them before they reach API serialization.

```java
private String safeDetailsJson(String detailsJson) {
    if (StringUtils.isBlank(detailsJson)) {
        return null;
    }
    JsonObject normalized = tryParseObject(detailsJson);
    if (normalized == null) {
        JsonObject fallback = new JsonObject();
        fallback.addProperty("message", abbreviate(stripUnsafeControlChars(detailsJson), 2048));
        fallback.addProperty("rawDetailsRedacted", true);
        return GSON.toJson(fallback);
    }
    redactSecrets(normalized);
    collapseRawAgentOutput(normalized);
    return GSON.toJson(normalized);
}
```

`collapseRawAgentOutput()` should replace raw stdout/stderr blobs with a compact
shape:

```json
{
  "agentAnswer": {
    "result": true,
    "status": {
      "state": "ERROR",
      "error_code": "DR_VMWARE_VDDK_CONNECT_INVALID",
      "message": "VDDK rejected source connection parameters"
    },
    "rawOutputRedacted": true
  }
}
```

Never return secrets, password file contents, API keys, tokens, or full raw
command lines.

### 24.3 Error message discipline

`dr_plan.last_error_message` and `dr_run.error_message` must be short human
messages. They must not store raw `dr-status --json`, raw nbdkit logs, or agent
stdout.

Recommended mapper:

```java
private String summarizeRuntimeError(String errorCode, JsonObject runtime) {
    switch (StringUtils.defaultString(errorCode)) {
        case "DR_VMWARE_VDDK_CONNECT_INVALID":
            return "VMware VDDK rejected source connection parameters. Check source VM, snapshot, disk path, vCenter endpoint, and credential.";
        case "DR_VMWARE_VDDK_SOURCE_LOCKED":
            return "VMware source disk is locked. A run snapshot is required before source open.";
        case "DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID":
            return "FTCTL could not open the VMware source graph.";
        default:
            return StringUtils.defaultIfBlank(firstString(runtime, "message"), "DR run failed.");
    }
}
```

The full structured runtime object remains in `dr_run.last_status_json` and
step `details_json` after redaction.

### 24.4 Regression test

Add API serialization tests with a Korean datastore path:

```java
@Test
public void getDrPlanWithKoreanDatastorePathReturnsValidJson() {
    String path = "[3번호스트-로컬디스크] test1/test1.vmdk";
    DrRunStepVO step = stepWithDetails("{\"sourceDiskRef\":\"" + path + "\"}");
    DrPlanResponse response = generator.createPlanResponse(plan, eligibility);
    String json = gson.toJson(response);
    JsonParser.parseString(json);
    assertThat(json).contains("3번호스트-로컬디스크");
}
```

Also test that nested plan `lastRun` has no `steps` array, while
`listDrRunSteps` still returns safe redacted details.

## 25. UI Fallback And State Presentation Design

The UI must treat a failed `getDrPlan` as a detail-response failure, not as proof
that the plan does not exist.

Affected files:

```text
ui/src/api/dr.js
ui/src/views/infra/dr/DrPlanOverview.vue
ui/src/views/infra/dr/DrPlanList.vue
ui/src/components/dr/DrRunProgress.vue
```

Target detail-load flow:

```js
async function loadPlanDetail (id) {
  try {
    plan.value = await getDrPlan(id)
  } catch (error) {
    detailLoadError.value = normalizeApiError(error)
    const [runs, replicas] = await Promise.allSettled([
      listDrRuns({ planid: id, page: 1, pagesize: 1 }),
      listDrReplicas({ planid: id })
    ])
    latestRun.value = firstFulfilledItem(runs)
    replica.value = firstFulfilledItem(replicas)
    effectiveState.value = latestRun.value?.state === 'FAILED' ? 'ERROR' : 'UNKNOWN'
  }
}
```

UI rules:

- Show a compact banner: "DR plan detail response could not be loaded. Latest
  run state is shown from run history."
- Keep action buttons disabled when detail cannot prove readiness.
- Show latest run error code and message from `listDrRuns`.
- Do not show raw JSON blobs.
- Provide a refresh action that retries `getDrPlan` and run/replica lookup.

## 26. FTCTL Source-Open Contract Update

The qemu companion design is:

```text
ablestack-qemu-exec-tools/docs/ftctl/433-ftctl-dr-vmware-vddk-connect-contract-design-20260708.md
```

Cloud must provide the runtime source identity and policy, but it must not ask
the operator to provide snapshot MoRefs manually.

Profile contract addition:

```json
{
  "source": {
    "provider": "VMWARE",
    "driver": "VMWARE_CBT",
    "vmId": "vm-4486",
    "externalRef": "vm-4486",
    "endpointRef": "10.10.21.10",
    "snapshotPolicy": {
      "createForSync": true,
      "memory": false,
      "quiesce": false,
      "namePrefix": "ftctl-dr"
    }
  }
}
```

FTCTL runtime status should report a redacted `source_open` object:

```json
{
  "source_open": {
    "checked": true,
    "ready": false,
    "error_code": "DR_VMWARE_VDDK_CONNECT_INVALID",
    "vmRef": "vm-4486",
    "snapshotRefPresent": false,
    "sourceVmdkPathPresent": true
  }
}
```

Backend projection should copy these summary fields into
`dr_run.last_status_json` and expose short fields on `DrPlanResponse` and
`DrRunResponse` when useful.

## 27. DB And Cleanup Design

No DB schema migration is required.

Existing field discipline:

| Table | Field | Target content |
| --- | --- | --- |
| `dr_plan` | `last_error_code` | specific code such as `DR_VMWARE_VDDK_CONNECT_INVALID` |
| `dr_plan` | `last_error_message` | short sanitized message, no raw JSON |
| `dr_run` | `last_status_json` | redacted structured runtime object |
| `dr_run_step` | `details_json` | redacted normalized JSON, no raw agent stdout blob |
| `dr_event` | `details_json` | operator-facing evidence and cleanup hints |

For existing failed rows that already contain raw JSON in error messages, no
schema migration is needed. A retry cleanup or explicit recovery task may
rewrite the latest plan/run message from the structured status JSON.

## 28. Current Error Cause And AS-IS / TO-BE

Current root cause:

```text
The DR sync request reached Cloud backend, Agent, and ftctl. CBT readiness was
valid, but ftctl attempted to open the VMware source disk through VDDK without
a complete snapshot-aware source-open contract. VDDK rejected ConnectEx. At the
same time, Cloud plan detail response nested raw runtime/step details and could
emit invalid JSON for Korean datastore paths, causing UI detail degradation.
```

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| UI detail | `getDrPlan` parse failure can leave the detail page in a broken/loading state | Detail page falls back to latest run/replica APIs and shows a clear load-warning banner |
| API response | `getDrPlan` nests full latest run and raw step details | `getDrPlan` returns a compact latest-run summary; raw step details are only returned through safe step APIs |
| Backend error message | raw status JSON can be stored or returned as error message | error fields contain short sanitized messages; structured redacted status stays in JSON status fields |
| Agent | transport output can be persisted into UI-facing details | Agent still preserves output, but backend collapses it into safe summaries for API |
| FTCTL | `VixDiskLib_ConnectEx` maps to generic source graph invalid | VDDK connect/export/lock/open-denied conditions map to specific error codes |
| DB | existing schema carries state but may contain oversized/unsafe raw details | same schema, stricter content discipline and optional recovery rewrite for current failed rows |
| Next-step readiness | failure can look like UI inconsistency only | readiness is FAIL until source-open preflight succeeds and API detail response is JSON-safe |

## 29. Live Snapshot MoRef Resolve And Payload Stability Follow-up - 2026-07-08

The next live plan `e08b9ef0-8a7a-42f6-bf0a-9e9f41f2fbee` exposed two related
gaps:

1. ftctl created a VMware run snapshot, but failed to resolve its MoRef because
   the deployed `govc snapshot.tree -json` output did not include a `snapshot`
   object.
2. Cloud persisted large runtime JSON repeatedly into plan/run/step projection
   fields, which made UI detail/list rendering look like data disappeared even
   though the DB row was not removed.

### 29.1 Verified root cause

Read-only preflight on the data-plane worker showed:

```json
[
  {
    "name": "ftctl-dr-598e6f88-6e6e-41d4-ab5a-d2ea1d46fbdd-source",
    "current": true,
    "childSnapshotList": null
  }
]
```

This is the full `govc snapshot.tree -vm vm-4486 -json` shape for the current
run snapshot. The existing parser expects `Snapshot` or `snapshot`, so it
returns an empty ref.

The same snapshot is resolvable through:

```text
govc object.collect -json vm-4486 snapshot.rootSnapshotList
```

Relevant object shape:

```json
{
  "name": "ftctl-dr-598e6f88-6e6e-41d4-ab5a-d2ea1d46fbdd-source",
  "snapshot": {
    "type": "VirtualMachineSnapshot",
    "value": "snapshot-7048"
  },
  "vm": {
    "type": "VirtualMachine",
    "value": "vm-4486"
  }
}
```

Therefore the correct resolver is:

```text
object.collect snapshot.rootSnapshotList first, snapshot.tree fallback second.
```

### 29.2 Current failed state

| Field | Observed value |
| --- | --- |
| `dr_plan.state` | `ERROR` |
| `dr_run.state` | `FAILED` |
| `dr_run.error_code` | `DR_VMWARE_VDDK_CONNECT_INVALID` |
| `dr_replica.state` | `ERROR` |
| target VM | not materialized |
| target RBD | created |
| restore point | absent |
| vCenter run snapshot | remains and resolves as `snapshot-7048` |
| next-step PASS | `FAIL` |

The failure is not a UI delete. `dr_plan.removed` is still `NULL`. The UI can
look empty because the detail load path receives unstable or oversized runtime
payloads and then falls into a fallback state.

## 30. Layered Remediation Design

### 30.1 UI

Affected files:

```text
ui/src/views/infra/dr/DrPlanList.vue
ui/src/views/infra/dr/DrPlanOverview.vue
ui/src/components/dr/DrRunProgress.vue
ui/public/locales/ko_KR.json
ui/public/locales/en.json
```

Design:

- List and detail loaders must not clear previously loaded plan data until the
  replacement response is valid.
- Detail loading must use `Promise.allSettled()` for `getDrPlan`,
  `listDrRuns`, `listDrReplicas`, and optional `listDrRunSteps`; one failing
  API must not blank the whole page.
- When `getDrPlan` fails but `listDrRuns` succeeds, render a degraded detail
  state with the latest run error and disable all execution actions.
- Add localized messages for `DR_VMWARE_SNAPSHOT_REF_UNRESOLVED`.
- Do not render raw `details_json`; show summarized runtime fields:
  `runtimeErrorCode`, `runtimeProjectionMessage`, `sourceSnapshot.refPresent`,
  and `sourceOpen.ready`.

Target code shape:

```js
async fetchDetail (options = {}) {
  const current = this.detailPlan && this.detailPlan.id ? this.detailPlan : null
  const [plan, runs, replicas] = await Promise.allSettled([
    getDrPlan(this.detailId),
    listDrRuns({ planid: this.detailId, page: 1, pagesize: 5 }),
    listDrReplicas({ planid: this.detailId })
  ])

  if (plan.status === 'fulfilled') {
    this.detailPlan = plan.value || {}
    this.detailLoadWarning = ''
  } else {
    this.detailPlan = current || this.detailPlanFallbackFromRun(runs)
    this.detailLoadWarning = this.errorMessage(plan.reason)
  }

  this.detailRuns = this.itemsFromSettled(runs)
  this.detailReplicas = this.itemsFromSettled(replicas)
  this.scheduleRuntimePolling()
}
```

### 30.2 API

Affected API responses:

```text
org.apache.cloudstack.api.response.dr.DrPlanResponse
org.apache.cloudstack.api.response.dr.DrRunResponse
org.apache.cloudstack.api.response.dr.DrRunStepResponse
```

Add compact source snapshot fields:

```java
private Boolean runtimeSourceSnapshotChecked;
private Boolean runtimeSourceSnapshotCreated;
private Boolean runtimeSourceSnapshotRefPresent;
private String runtimeSourceSnapshotErrorCode;
private String runtimeSourceSnapshotMessage;
private String runtimeSourceSnapshotResolveMethod;
```

Populate them from `last_status_json.source_snapshot`, not from raw step
details.

`getDrPlan` and `listDrPlans` must return compact plan/run summaries. Detailed
step payloads should remain behind `listDrRunSteps`, and even that command must
return redacted/compacted JSON.

### 30.3 Backend projection

Affected files:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/adapter/ftctl/FtctlDrRuntimeProjectionAdapter.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/orchestrator/DrRunExecutorImpl.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/response/DrResponseGenerator.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrConstants.java
```

Add a new constant:

```java
public static final String ERROR_VMWARE_SNAPSHOT_REF_UNRESOLVED =
        "DR_VMWARE_SNAPSHOT_REF_UNRESOLVED";
```

Projection must never set plan/run error message to raw `status.getDetails()`
when the details are a `dr-status --json` blob.

Target code shape:

```java
if (StringUtils.isNotBlank(runtimeErrorCode)) {
    plan.setLastErrorCode(runtimeErrorCode);
    plan.setLastErrorMessage(summarizeRuntimeError(runtimeErrorCode, runtime));
    changed = true;
}
```

Step details must be compacted before persistence:

```java
private String compactProjectionDetails(FtctlDrStatusAnswer status, JsonObject runtime) {
    JsonObject details = new JsonObject();
    details.addProperty("state", firstString(runtime, "state"));
    details.addProperty("step", firstString(runtime, "step"));
    details.addProperty("error_code", firstString(runtime, "error_code"));
    details.add("source_snapshot", safeObject(runtime, "source_snapshot"));
    details.add("source_open", safeObject(runtime, "source_open"));
    details.addProperty("target_vm_present", firstBoolean(runtime, "target_vm_present"));
    details.addProperty("target_storage_present", firstBoolean(runtime, "target_storage_present"));
    details.addProperty("restore_point_present", firstBoolean(runtime, "restore_point_present"));
    details.addProperty("rawStatusRedacted", true);
    return GSON.toJson(details);
}
```

Full redacted runtime status remains in `dr_run.last_status_json`; it should not
be copied into every `dr_run_step.details_json`.

`DrResponseGenerator.safeDetailsJson()` must also cap verbose arrays such as
runtime `events`:

```java
if ("events".equals(key) && element.isJsonArray()) {
    return lastItems(element.getAsJsonArray(), 20);
}
```

### 30.4 Agent

No new Agent command is required.

Required behavior:

- `FtctlDrStatusCommand` continues to relay `statusJson`, `state`, `step`,
  `errorCode`, and runtime booleans.
- The KVM wrapper should not interpret or rewrite
  `DR_VMWARE_SNAPSHOT_REF_UNRESOLVED`; Cloud projection owns API-level
  summarization.
- Agent logs may retain command output for diagnosis, but Cloud must not persist
  that output verbatim into UI-facing step details.

### 30.5 FTCTL

Companion design:

```text
ablestack-qemu-exec-tools/docs/ftctl/433-ftctl-dr-vmware-vddk-connect-contract-design-20260708.md
```

Required code-level changes:

```text
lib/ftctl/dr_vmware_mover.sh
lib/ftctl/dr_runtime.sh
lib/ftctl/dr_scheduler.sh
bin/ablestack_vm_ftctl_selftest.sh
```

Rules:

- Add exit `81` mapped to `DR_VMWARE_SNAPSHOT_REF_UNRESOLVED`.
- Resolve run snapshot MoRef with
  `govc object.collect -json <vm-ref> snapshot.rootSnapshotList`.
- Keep `snapshot.tree -json` as fallback only.
- Set cleanup environment variables immediately after successful
  `snapshot.create`, before resolve starts.
- Write `source_snapshot` runtime status whether resolve succeeds or fails.
- If resolve fails, write `source_open.checked=false` with
  `skippedReason=SOURCE_SNAPSHOT_REF_UNRESOLVED`.
- Ensure the failure path removes the run snapshot or marks
  `cleanupRequired=true` when cleanup cannot be completed.

### 30.6 DB

No schema migration is required.

Field discipline:

| Table | Field | TO-BE |
| --- | --- | --- |
| `dr_plan` | `last_error_code` | `DR_VMWARE_SNAPSHOT_REF_UNRESOLVED` or the final terminal code |
| `dr_plan` | `last_error_message` | short human message, no raw JSON |
| `dr_run` | `error_code`, `error_message` | same terminal code and short message |
| `dr_run` | `last_status_json` | full redacted runtime status with `source_snapshot` and `source_open` |
| `dr_run_step` | `details_json` | compact projection summary, no duplicated raw status |
| `dr_event` | `details_json` | operator-facing evidence and cleanup hint |

Recovery rewrite for existing failed rows may be implemented as a backend
maintenance action or cleanup routine, not as a mandatory schema migration.

## 31. Updated AS-IS / TO-BE Summary

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| UI | API detail failure or oversized payload can blank the detail/list view | Preserve current data, load auxiliary APIs independently, show degraded error banner |
| API | Plan/run responses expose enough nested runtime detail to make payloads heavy | Return compact runtime/source snapshot summaries; step detail is lazy and redacted |
| Backend | `last_error_message` and step `details_json` can receive raw runtime JSON | Store short messages in error fields and compact projection details per step |
| Agent | Relays ftctl status; no issue in command routing | Same command contract; no interpretation of snapshot resolve errors |
| FTCTL | Run snapshot can be created but not resolved because `snapshot.tree` lacks MoRef; cleanup flag is set too late | Resolve via `object.collect`, fallback to tree, set cleanup flag immediately, emit `source_snapshot` |
| DB | Existing schema has state, but content discipline allows duplicated 35 KB runtime blobs | Same schema, strict compact/error discipline and optional recovery rewrite |
| Error cause | Looks like generic VDDK connect invalid and UI data loss | Exact cause is snapshot MoRef resolve failure plus payload projection instability |
| Next-step readiness | Target RBD exists, but no VM/checkpoint/restore point | FAIL until source snapshot resolves, source-open succeeds, durable checkpoint exists |

## 32. V2K-Validated vCenter Thumbprint Contract

### 32.1 Corrected root cause

The latest live preflight refined the failure boundary again.  The v2k model is
valid for this case because it uses vCenter credentials only.  It does not send
an ESXi account to the conversion/mover layer, although VDDK may internally use
the runtime ESXi host selected by vCenter.

The failing FTCTL runtime profile currently contains the correct vCenter
endpoint and account but no vCenter thumbprint:

```json
{
  "endpoint": "10.10.21.10",
  "principal": "administrator@ablecloud.local",
  "tlsVerify": null,
  "thumbprintPresent": false,
  "vddkLibdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk"
}
```

Target-host preflight proved:

- vCenter-only VDDK open with a thumbprint passes.
- vCenter-only VDDK open with `single-link=true` and a thumbprint also passes.
- The same open without a thumbprint fails with
  `VixDiskLib_ConnectEx: One of the parameters was invalid`.

So the structural issue is not ESXi credential delivery and not
`single-link=true`.  It is the missing vCenter thumbprint in the Cloud to Agent
to FTCTL runtime contract when `tlsVerify` is false or absent.

### 32.2 Layer responsibility

| Layer | Responsibility |
| --- | --- |
| UI | Do not ask the operator for ESXi credentials or thumbprint. Show vCenter connection and source-open readiness in human form. |
| API | Keep create/update parameters vCenter-oriented. Return readiness blockers such as `DR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED`. |
| Backend | Resolve and persist non-secret vCenter thumbprint evidence during site health/readiness/profile generation. |
| Agent | Provide a host-local fallback thumbprint resolver before writing the FTCTL profile, reusing the existing VDDK conversion precedent. |
| FTCTL | Require or auto-resolve `thumbprint=` for nbdkit when `tlsVerify` is not `true`; emit a specific error if unresolved. |
| DB | No schema migration; store only redacted status/capability JSON and short terminal error codes. |

### 32.3 UI design

Affected files:

```text
ui/src/views/infra/dr/DrPlanDetail.vue
ui/src/views/infra/dr/DrPlanList.vue
ui/src/views/infra/dr/components/DrRunProgress.vue
ui/src/api/dr.js
ui/src/locales/en.json
ui/src/locales/ko.json
```

UI rules:

- VMware site dialogs remain vCenter-only: URL, vCenter user, vCenter password,
  and TLS verification.
- Do not add ESXi credential inputs.
- Do not add a raw thumbprint input by default.  If an override is ever needed,
  place it behind an advanced diagnostic section, not in the normal workflow.
- For `DR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED`, display the i18n message key
  `message.dr.vmware.vddk.thumbprint.unresolved`:
  `Unable to detect the vCenter certificate thumbprint. Check vCenter 443 reachability and TLS settings.`
- For `DR_VMWARE_VDDK_CONNECT_INVALID`, display the broader message only after
  thumbprint presence is confirmed.
- Detail/progress panels should expose redacted booleans only:
  `vCenter thumbprint: detected / not detected`, not the secret credential data.

### 32.4 API and backend design

Affected files:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrConstants.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrResolvedSiteCredential.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrPlanReadinessValidator.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/adapter/ftctl/FtctlDrUnifiedActionAdapter.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/health/DrSiteProbeSupport.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/health/DrVmwareDirectSiteProbe.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/response/DrResponseGenerator.java
plugins/integrations/disaster-recovery/src/test/java/com/cloud/dr/adapter/ftctl/FtctlDrUnifiedActionAdapterTest.java
plugins/integrations/disaster-recovery/src/test/java/com/cloud/dr/DrPlanReadinessValidatorTest.java
```

Add constant:

```java
public static final String ERROR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED =
        "DR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED";
```

Normalize runtime credential JSON:

```java
JsonObject runtime = credential.toRuntimeJson();
runtime.addProperty("tlsVerify", Boolean.TRUE.equals(credential.getTlsVerify()));
```

When the source site is VMware and `tlsVerify` is not true, enrich
`credentials.source` in this order:

1. existing explicit `thumbprint` or `tlsThumbprint` value.
2. latest healthy site probe details, for example
   `details.vcenterThumbprint`.
3. bounded direct backend probe to `endpoint:443`.
4. Agent fallback if backend cannot reach vCenter from management.

Backend probe helper:

```java
public Optional<String> fetchSha1Thumbprint(String endpoint, Duration timeout) {
    String host = normalizeEndpointHost(endpoint);
    String output = runOpenSslFingerprint(host, timeout);
    return parseSha1Fingerprint(output);
}
```

Site health probe should record non-secret evidence:

```json
{
  "vcenterThumbprintPresent": true,
  "vcenterThumbprintSource": "probe",
  "vcenterEndpoint": "10.10.21.10"
}
```

`DrPlanReadinessValidator` should add a blocker only when the runtime contract
cannot be satisfied:

```java
if (isVmwareSource(plan) && !tlsVerify && !canResolveThumbprint(plan)) {
    addVmwareDataPlaneBlocker(readiness,
        DrConstants.ERROR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED,
        "The source vCenter thumbprint could not be resolved for VDDK source-open");
}
```

The readiness validator must not require an ESXi credential.

### 32.5 Agent design

Affected files:

```text
plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrActionCommandWrapper.java
plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtConvertInstanceCommandWrapper.java
plugins/hypervisors/kvm/src/test/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrActionCommandWrapperTest.java
```

Reuse the existing conversion precedent:

```java
String vddkThumbprint = command.getConfiguredVddkThumbprint();
if (StringUtils.isBlank(vddkThumbprint)) {
    vddkThumbprint = getVcenterThumbprint(vmwareInstance.getVcenterHost(), timeout, originalVMName);
}
cmd.append("-io vddk-thumbprint=").append(vddkThumbprint).append(" ");
```

For DR, the wrapper should enrich the profile before writing it:

```java
JsonObject source = credentials.getAsJsonObject("source");
boolean tlsVerify = getBoolean(source, "tlsVerify", false);
if (!tlsVerify && isBlank(getString(source, "thumbprint"))) {
    String endpoint = getString(source, "endpoint");
    String thumbprint = getVcenterThumbprint(endpoint, timeout, planName);
    if (StringUtils.isNotBlank(thumbprint)) {
        source.addProperty("thumbprint", thumbprint);
        source.addProperty("thumbprintSource", "agent-auto");
    }
}
```

If the Agent cannot resolve the thumbprint, it should still write the profile
with `thumbprintPresent=false` so FTCTL can return the precise
`DR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED` source-open status.

### 32.6 FTCTL companion contract

Companion design:

```text
ablestack-qemu-exec-tools/docs/ftctl/433-ftctl-dr-vmware-vddk-connect-contract-design-20260708.md
```

FTCTL must:

- read `.credentials.source.thumbprint // .credentials.source.tlsThumbprint`.
- auto-fetch the vCenter SHA1 thumbprint from `endpoint:443` when
  `tlsVerify` is not `true` and no thumbprint is present.
- pass `thumbprint=...` to `nbdkit-vddk-plugin`.
- return `DR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED` before launching the long mover
  path if the thumbprint cannot be resolved.
- keep `DR_VMWARE_VDDK_CONNECT_INVALID` for real VDDK parameter failures after
  thumbprint is present.

### 32.7 DB design

No migration is required.

| Table | Field | TO-BE discipline |
| --- | --- | --- |
| `dr_site_health_check` | `details_json` | include `vcenterThumbprintPresent`, `vcenterThumbprintSource`, endpoint only |
| `dr_plan` | `last_error_code` | may contain `DR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED` |
| `dr_plan` | `last_error_message` | short operator message, no raw nbdkit output |
| `dr_run` | `last_status_json` | redacted `source_open.tlsVerify`, `thumbprintPresent`, `thumbprintSource` |
| `dr_event` | `details_json` | compact evidence and remediation hint |

Do not persist vCenter passwords, API secrets, ESXi credentials, or full
command lines in any UI-facing JSON.

### 32.8 Updated AS-IS / TO-BE Summary

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| UI | Operator could infer an ESXi credential or hidden thumbprint problem from a generic VDDK error | UI remains vCenter-oriented and shows thumbprint readiness as redacted diagnostic state |
| API | No explicit readiness reason for missing vCenter thumbprint | `DR_VMWARE_VDDK_THUMBPRINT_UNRESOLVED` is returned before execution or from source-open |
| Backend | Runtime credential includes endpoint/principal/libdir but can omit thumbprint | Backend enriches source credential from site health or direct probe when `tlsVerify=false` |
| Agent | DR wrapper enriches VDDK libdir/version only | Agent adds host-local vCenter thumbprint fallback using the existing VDDK conversion pattern |
| FTCTL | Missing thumbprint reaches nbdkit and collapses into `VixDiskLib_ConnectEx` / connect invalid | FTCTL requires or auto-resolves thumbprint and emits a specific pre-open error |
| DB | No schema issue, but status lacks thumbprint evidence | Same schema, redacted thumbprint presence/source stored in health and runtime status |
| Error cause | Previously summarized as snapshot MoRef plus payload projection instability | Latest blocker is missing vCenter thumbprint in the runtime VDDK source-open contract |
| Next-step readiness | Source snapshot/disk metadata may exist, but VDDK source-open still fails | PASS only after vCenter-only credentials, snapshot ref/path, VDDK libdir, and thumbprint are all valid |

## 33. 2026-07-08 Follow-Up: Source-Open Success Leads To Initial Seed Pending

The validation for plan `9bb2739b-597c-4c9a-a603-f3edf5abfd60` confirmed that
the thumbprint/source-open contract worked:

- `source_open.ready=true`
- `source_snapshot.ready=true`
- CBT enabled
- `nbdkit vddk` running
- `qemu-img convert` writing to the target RBD

The remaining UI Fail was not a VDDK failure. It was a Cloud projection issue:
`target_vm_present=false` during healthy `SYNCING/full-seed-transfer` was
persisted as `DR_TARGET_VM_NOT_FOUND`.

This document covers source-open correctness. The post-source-open pending
projection contract is defined in
`546-cross-hypervisor-dr-initial-sync-pending-projection-contract-design-20260708.md`.

### 33.1 AS-IS / TO-BE Delta

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| VDDK source open | Can now pass with vCenter thumbprint | Keep as-is |
| Data copy | Enters `full-seed-transfer` | Treat as active progress until durable target checkpoint |
| Target VM absence | Projected as `DR_TARGET_VM_NOT_FOUND` | Pending until durable restore point and materialization phase |
| UI status | Shows Fail even while `qemu-img convert` is running | Shows initial sync in progress |

## 34. 2026-07-14 CBT Persistence And Transfer-Evidence Supersession

The vCenter-only VDDK source-open and S1/S2 CBT experiment in this document
remain authoritative preflight evidence. The following implementation boundary
is corrected:

- JSON-only changeId persistence is insufficient for an atomic DR baseline.
- A long-running `dr_run` is not the RPO cycle-history entity.
- Sequence-based `incremental` naming is not execution proof.
- An invalid changeId must not silently fall back to a complete copy.

Typed `dr_replica_disk` baseline columns, per-cycle/per-disk history tables,
Cloud commit acknowledgement, and transfer metrics are normative in
`555-cross-hypervisor-dr-vmware-cbt-incremental-and-transfer-metrics-design-20260714.md`.
