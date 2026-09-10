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

# Cross Hypervisor DR Initial Sync Pending Projection Contract Design

Date: 2026-07-08

## 1. Purpose

This document defines the structural fix for the VMware to ABLESTACK DR sync
test using plan `9bb2739b-597c-4c9a-a603-f3edf5abfd60` and run
`cf079366-e0cf-4c6d-b55a-ea0e09151544`.

The live runtime showed real progress:

| Item | Observed value |
| --- | --- |
| Cloud plan state | `SYNCING` |
| Cloud run state | `ACCEPTED` |
| Cloud run projection | `syncing`, `runtime-projection` |
| Cloud run error | `DR_TARGET_VM_NOT_FOUND` |
| FTCTL state | `SYNCING` |
| FTCTL step | `full-seed-transfer` |
| FTCTL progress | `40` |
| FTCTL worker | `RUNNING` |
| FTCTL error | empty |
| Target storage | RBD image exists and is growing |
| Target VM | not materialized yet |
| Restore point | not created yet |

This is not an engine failure. Initial full-seed is still running. The target
VM is intentionally absent until a durable target restore point exists. The bug
is that Cloud projection writes a pending condition into `dr_run.error_code`,
and API/UI then renders it as a failure.

## 2. Confirmed Live Preflight

The live environment was checked before this design was written.

| Check | Result |
| --- | --- |
| VDDK source open | `source_open.ready=true` |
| VMware snapshot | `source_snapshot.ready=true`, snapshot ref present |
| CBT | `enabled=true`, VM CBT enabled |
| Data mover | `nbdkit vddk` process running |
| Target writer | `qemu-img convert` process running |
| Target RBD | `Rokcy10-1-dr-disk-0`, 100 GiB |
| RBD used size | increased from 3.9 GiB to 5.3 GiB during observation |
| FTCTL status error | empty |
| DB restore points | `0`, expected before full-seed completion |

The previous `VixDiskLib_ConnectEx` and thumbprint failures have been passed.
The runtime has moved into the real transfer phase.

## 2.1 2026-07-09 Follow-Up: Durable Restore Point Is Not Target Ready

A later validation with plan `dd895181-7fff-43cc-bae6-24a5ab529db8` confirmed
the next boundary after this document:

- the initial seed and restore point path can succeed;
- `target_storage_present=true` and `restore_point_present=true`;
- the latest restore point can be `READY`;
- but `target_vm_present=false`, `target_network_present=false`, and
  `dr_replica.target_vm_id` can remain empty.

That state is no longer initial seed. It must not continue to look like generic
`40%` data transfer. It is the target materialization phase and requires a
Cloud-owned async worker to import/adopt the seeded disk as a managed volume,
deploy a stopped target VM from the imported root volume, update DR DB
references, and send the target refs back to FTCTL.

The detailed follow-up contract is defined in:

- `547-cross-hypervisor-dr-target-vm-materialization-contract-design-20260709.md`

## 3. Root Cause

Current backend code in `FtctlDrRuntimeProjectionAdapter.markSyncTargetPending`
mixes two different concepts:

1. Terminal error: the engine has failed and the operator must intervene.
2. Pending materialization: the engine is still copying data and the target VM
   does not exist yet by design.

The current behavior writes `DR_TARGET_VM_NOT_FOUND` when
`hasTargetReferenceForDirection(plan)` is false, even if FTCTL reports
`SYNCING/full-seed-transfer`, `worker_state=RUNNING`, and empty runtime
`error_code`.

`DR_TARGET_VM_NOT_FOUND` is valid only after the runtime has reached a phase
where a target VM reference is expected.

## 4. Target State Contract

| Runtime condition | Meaning | Cloud projection |
| --- | --- | --- |
| `SYNCING/full-seed-transfer`, `worker_state=RUNNING`, no runtime error, `target_storage_present=true`, `target_vm_present=false` | Initial seed is copying data. Target VM is not expected yet. | Run stays active. No error code. |
| `last_target_durable_at` empty and `restore_point_present=false` | No usable restore point yet. | Progress view says "initial sync in progress". |
| `last_target_durable_at` present and `restore_point_present=true`, but target VM reference absent | The disk is durable; VM materialization should now happen. | `projection_state=target-materializing`; no failure until grace expires. |
| `READY` or `TARGET_READY` but target VM/reference missing | Runtime says ready but Cloud cannot find target VM. | Terminal projection error. |
| Runtime `ERROR`/`FAILED`, `worker_state=FAILED`, or non-empty runtime `error_code` | Engine failure. | Terminal projection error. |

Do not write pending states into error columns.

| DB field | Pending full-seed | Terminal failure |
| --- | --- | --- |
| `dr_run.error_code` | `NULL` | specific error code |
| `dr_run.error_message` | `NULL` | short operator message |
| `dr_run_step.error_code` | `NULL` for `RUNNING` pending step | specific error code |
| `dr_plan.last_error_code` | unchanged or cleared when runtime is healthy | specific error code |
| API `runtimeErrorCode` | empty | specific error code |

## 5. UI Layer Design

Affected files:

```text
ui/src/utils/dr/resourceActions.js
ui/src/views/infra/dr/DrPlanList.vue
ui/src/views/infra/dr/DrPlanOverview.vue
ui/src/views/infra/dr/DrRunsTab.vue
ui/src/components/dr/DrRunProgress.vue
ui/public/locales/en-US.json
ui/public/locales/ko-KR.json
```

Current logic treats any run error code as runtime failure. TO-BE failure
detection must require terminal evidence:

```js
const PENDING_RUNTIME_CODES = new Set([
  'DR_TARGET_VM_NOT_MATERIALIZED',
  'DR_TARGET_VM_PENDING',
  'DR_TARGET_RESTORE_POINT_PENDING'
])

function hasRuntimeFailure (resource) {
  const runtime = upper(resource?.runtimestate || resource?.runtimeState)
  const worker = upper(resource?.lastrun?.workerstate || resource?.lastrun?.workerState)
  const runState = upper(resource?.lastrun?.state || resource?.lastRun?.state)
  const runtimeCode = upper(resource?.runtimeerrorcode || resource?.runtimeErrorCode ||
    resource?.lastrun?.runtimeerrorcode || resource?.lastRun?.runtimeErrorCode)

  if (runtime === 'ERROR' || runtime === 'FAILED' || worker === 'FAILED' || runState === 'FAILED') {
    return true
  }
  return !!runtimeCode && !PENDING_RUNTIME_CODES.has(runtimeCode)
}
```

Visible status rules:

| API evidence | UI status |
| --- | --- |
| active run + `runtimeState=SYNCING` + `runtimeStep=full-seed-transfer` | `Initial sync in progress` |
| `targetStoragePresent=true`, `targetVmPresent=false`, no runtime error | `Target disk prepared, target VM pending` |
| durable checkpoint present, target VM pending | `Target VM materializing` |
| target VM, restore point, durable checkpoint present | `Recovery ready` |
| terminal runtime error | `Failed` |

Failover/test failover remain disabled until `targetMaterialized=true`,
`restorePointPresent=true`, and no runtime failure.

## 6. API Layer Design

Affected files:

```text
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/response/dr/DrPlanResponse.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/response/dr/DrRunResponse.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/response/DrResponseGenerator.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/GetDrPlanCmd.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/ListDrPlansCmd.java
plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/ListDrRunsCmd.java
```

Add or consistently populate non-error pending fields:

```java
@SerializedName("targetmaterializationstate")
private String targetMaterializationState; // NOT_STARTED, DISK_SEEDING, RESTORE_POINT_READY, VM_MATERIALIZING, READY

@SerializedName("targetmaterializationmessage")
private String targetMaterializationMessage;

@SerializedName("initialsyncinprogress")
private Boolean initialSyncInProgress;
```

If adding fields is deferred, compute the same semantic state in response
generation and UI normalization without a DB migration.

`DrResponseGenerator` must not promote `run.errorCode` to `runtimeErrorCode`
when the runtime payload is healthy and the run is active.

```java
private String visibleRuntimeErrorCode(DrRunVO run, JsonObject runtime) {
    String runtimeState = StringUtils.upperCase(firstString(runtime, "state"));
    String workerState = StringUtils.upperCase(firstString(runtime, "worker_state"));
    String runtimeErrorCode = firstString(runtime, "error_code");
    if (StringUtils.isNotBlank(runtimeErrorCode)) {
        return runtimeErrorCode;
    }
    if (StringUtils.equalsAny(runtimeState, "ERROR", "FAILED")
            || StringUtils.equals(workerState, "FAILED")
            || StringUtils.equals(run.getState(), DrConstants.RUN_STATE_FAILED)) {
        return run.getErrorCode();
    }
    return null;
}
```

## 7. Backend Projection Design

Affected files:

```text
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/adapter/ftctl/FtctlDrRuntimeProjectionAdapter.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrConstants.java
plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrPlanReadinessValidator.java
plugins/integrations/disaster-recovery/src/test/java/com/cloud/dr/adapter/ftctl/FtctlDrRuntimeProjectionAdapterTest.java
plugins/integrations/disaster-recovery/src/test/java/com/cloud/dr/response/DrResponseGeneratorTest.java
```

### 7.1 `markSyncTargetPending`

Change the method so healthy active sync never writes an error code.

```java
private void markSyncTargetPending(DrPlanVO plan, DrRunVO run,
        FtctlDrStatusAnswer status, JsonObject runtime) {
    if (run == null || run.getCompleted() != null) {
        return;
    }

    boolean targetReferencePresent = hasTargetReferenceForDirection(plan);
    boolean durablePresent = hasDurableCheckpoint(status, runtime);
    String runtimeState = runtimeState(status, runtime);
    boolean initialSeedRunning = StringUtils.equalsAny(runtimeState, "SYNCING", "RUNNING")
            && !durablePresent
            && !targetReferencePresent;

    String projectionState = durablePresent && !targetReferencePresent
            ? "target-materializing"
            : "syncing";
    String message = initialSeedRunning
            ? "FTCTL_DR initial seed is copying data; target VM will be materialized after a durable restore point exists"
            : durablePresent && !targetReferencePresent
                ? "FTCTL_DR sync has a durable restore point and is materializing the target VM"
                : "FTCTL_DR sync is still materializing target restore point";

    int progress = status.getProgress() != null
            ? Math.max(1, Math.min(status.getProgress(), 99))
            : (durablePresent ? 95 : 40);
    String compactStatusJson = compactRuntimeStatusJson(status.getStatusJson());

    recordRunProjectionStep(run, DrConstants.STEP_STATE_RUNNING, progress,
            compactStatusJson, null, null);
    run.setProjectionState(projectionState);
    run.setProjectionChecked(new Date());
    run.setCurrentStepName(durablePresent && !targetReferencePresent
            ? "target-materializing"
            : "initial-sync");
    run.setLastStatusJson(compactStatusJson);
    run.setErrorCode(null);
    run.setErrorMessage(null);
    run.markUpdated();
    drRunDao.update(run.getId(), run);

    clearHealthyProjectionError(plan);
}
```

### 7.2 Terminal Target VM Missing

Use `DR_TARGET_VM_NOT_FOUND` only when the target VM reference is required:

```java
private boolean isTargetReferenceMissingTerminal(DrPlanVO plan,
        FtctlDrStatusAnswer status, JsonObject runtime) {
    String runtimeState = runtimeState(status, runtime);
    boolean durablePresent = hasDurableCheckpoint(status, runtime);
    boolean restorePointPresent = isExplicitTrue(status.getRestorePointPresent(),
            booleanValue(runtime, "restore_point_present"));
    boolean readyRuntime = StringUtils.equalsAny(runtimeState, "READY", "TARGET_READY");
    return !hasTargetReferenceForDirection(plan)
            && (readyRuntime || (durablePresent && restorePointPresent && materializationGraceExpired(plan)));
}
```

When this returns true, fail projection with `DR_TARGET_VM_NOT_FOUND`.

### 7.3 Clear Stale Errors

If a later projection sees healthy `SYNCING` runtime with no runtime error,
clear stale plan/run/step errors instead of preserving them.

```java
private void clearHealthyProjectionError(DrPlanVO plan) {
    DrPlanVO latest = drPlanDao.findById(plan.getId());
    if (latest != null && StringUtils.equals(latest.getState(), DrConstants.PLAN_STATE_SYNCING)) {
        latest.setLastErrorCode(null);
        latest.setLastErrorMessage(null);
        latest.markUpdated();
        drPlanDao.update(latest.getId(), latest);
    }
}
```

### 7.4 Tests

Add tests:

```java
@Test
public void refreshPlanProjectionKeepsInitialSeedPendingRunHealthyWhenTargetVmIsAbsent() {
    // runtime: SYNCING/full-seed-transfer, worker RUNNING, no error,
    // target_storage_present=true, target_vm_present=false,
    // restore_point_present=false
    // expect: run ACCEPTED, projection_state=syncing, errorCode=null,
    // step RUNNING with errorCode=null, plan not ERROR
}

@Test
public void responseGeneratorDoesNotExposeRunErrorCodeAsRuntimeErrorForHealthyActiveRuntime() {
    // run has stale DR_TARGET_VM_NOT_FOUND, runtime has SYNCING and empty error_code
    // expect: runtimeErrorCode null, effectiveState SYNCING
}
```

## 8. Agent Layer Design

No Agent protocol change is required.

Current `FtctlDrStatusAnswer` already transports the needed distinction:

```text
state=SYNCING
step=full-seed-transfer
progress=40
targetStoragePresent=true
targetVmPresent=false
targetNetworkPresent=false
restorePointPresent=false
errorCode=
```

Agent responsibility remains:

1. Run bounded `dr-status`.
2. Preserve `errorCode` exactly as FTCTL emits it.
3. Do not synthesize target VM errors from boolean readiness fields.

## 9. FTCTL Layer Design

No blocking FTCTL change is required for this failure.

FTCTL already emits the correct distinction:

| Field | Current value | Meaning |
| --- | --- | --- |
| `state` | `SYNCING` | active non-terminal work |
| `step` | `full-seed-transfer` | initial seed copy |
| `target_storage_present` | `true` | target disk exists |
| `target_vm_present` | `false` | target VM not materialized yet |
| `restore_point_present` | `false` | no durable restore point yet |
| `error_code` | empty | not failed |

Optional improvement: parse `qemu-img convert -p` progress or bytes written to
target RBD and update runtime `progress` during full-seed. This is a UX
improvement only and is not required to fix the false Fail.

## 10. DB Layer Design

No DB migration is required.

Use existing columns with stricter semantics:

| Table | Field | TO-BE discipline |
| --- | --- | --- |
| `dr_run` | `state` | stays `ACCEPTED` or `RUNNING` until terminal success/failure |
| `dr_run` | `projection_state` | `syncing`, `initial-sync`, `target-materializing`, `succeeded`, `failed` |
| `dr_run` | `error_code` | null for pending materialization |
| `dr_run` | `error_message` | null for pending materialization |
| `dr_run` | `last_status_json` | compact healthy runtime status retained |
| `dr_run_step` | `state` | `RUNNING` for `runtime-projection` while full-seed is active |
| `dr_run_step` | `error_code` | null for active pending status |
| `dr_plan` | `last_error_code` | cleared when healthy runtime projection arrives |
| `dr_restore_point` | row | created only after durable target checkpoint exists |
| `dr_replica` | `target_vm_id` | null before target VM materialization |

## 11. AS-IS / TO-BE Summary

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| UI | Any `runtimeErrorCode` or last-run `errorCode` makes the plan look failed. | Terminal runtime state or terminal run state is required for Fail; initial seed pending is shown as in progress. |
| API | `DrResponseGenerator` promotes stale `run.errorCode` into `runtimeErrorCode` even when FTCTL runtime is healthy. | `runtimeErrorCode` is emitted only from runtime error or terminal failed run. Pending messages use separate materialization state/message. |
| Backend | `markSyncTargetPending` writes `DR_TARGET_VM_NOT_FOUND` during healthy full-seed. | Pending target VM absence clears run/step error fields and stores `projection_state=syncing` or `target-materializing`. |
| Agent | Correctly forwards FTCTL status, but Cloud may misinterpret booleans. | No change; Agent does not synthesize errors from `target_vm_present=false`. |
| FTCTL | Emits correct `SYNCING` status but progress can remain coarse at 40. | No required fix; optional data-copy progress enrichment. |
| DB | Active run can contain error fields, causing UI/API false Fail. | Active healthy run has null error fields; only terminal failures write error fields. |
| Error cause | Looks like target VM missing failure. | It is a false projection failure during initial full-seed transfer. |
| Next-step readiness | UI may imply Fail while data is still copying. | Next-step remains disabled but status is "initial sync in progress" until durable restore point and target VM exist. |

## 12. Implementation Order

1. Backend: update `markSyncTargetPending`, terminal target-missing check, and
   stale error clearing.
2. API: update `DrResponseGenerator` runtime error mapping and materialization
   message.
3. UI: update failure detection and labels for initial sync / target VM pending.
4. Tests: add backend projection and response generator unit tests.
5. Deployment cleanup: clear stale `DR_TARGET_VM_NOT_FOUND` from active healthy
   runs after deployment by forcing projection refresh, not by blind DB updates.

## 13. Implementation Update - 2026-07-08

Implemented scope:

| Layer | Implemented change |
| --- | --- |
| Backend | `FtctlDrRuntimeProjectionAdapter.markSyncTargetPending` no longer stores `DR_TARGET_VM_NOT_FOUND` for healthy initial seed. It records the projection step as `RUNNING`, clears active run error fields, and keeps `projection_state=syncing` or `target-materializing`. |
| Backend | `updatePlanFromStatus` clears stale plan-level last errors when FTCTL reports healthy `RUNNING`, `SYNCING`, `SEEDING`, `READY`, or `TARGET_READY` progress with no runtime error. |
| API | `DrResponseGenerator` exposes `runtimeErrorCode` only from FTCTL runtime error fields or terminal failed runs. Active sync run error residue is not promoted to runtime failure. |
| API | `DrPlanResponse` now exposes `initialsyncinprogress`, `targetmaterializationstate`, and `targetmaterializationmessage` as derived response fields. No DB migration is required. |
| UI | DR plan list/action eligibility checks no longer treat active run `errorcode` as failure unless the run state is terminal `FAILED`. |
| UI | DR plan overview and run progress suppress active-run stale error text and show target materialization state/detail labels. |
| Tests | Added adapter coverage for VMware-to-KVM initial full-seed where target storage exists but target VM and restore point do not yet exist. |

Deployment cleanup should still prefer a normal projection refresh after
deployment. Direct DB cleanup is only a fallback if a stale active run cannot be
refreshed through the backend.
