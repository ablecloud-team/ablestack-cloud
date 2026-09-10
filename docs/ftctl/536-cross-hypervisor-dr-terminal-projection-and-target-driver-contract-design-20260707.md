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

# Cross Hypervisor DR Terminal Projection And Target Driver Contract Design

Date: 2026-07-07

## 1. Purpose

This design closes the gap found during the VMware to ABLESTACK DR sync test for plan `b0522fc5-047f-4dc6-9cd7-b43a17daae45`.

The runtime host reported a terminal FTCTL error:

- plan: `b0522fc5-047f-4dc6-9cd7-b43a17daae45`
- run: `bdbea146-ac2d-4ab1-9e6e-6a004a5173cc`
- FTCTL runtime state: `ERROR`
- FTCTL step: `ablestack-driver-failed`
- FTCTL error code: `DR_ABLESTACK_DRIVER_FAILED`
- FTCTL worker state: `FAILED`
- target VM present: `false`
- target network present: `false`

At the same time Cloud API and UI still exposed the plan as `SYNCING` and the run as `ACCEPTED`. That means the UI/API/backend projection chain was not using the terminal runtime state as the effective source of truth.

This document defines the required structural improvement across UI, API, backend, agent, FTCTL, and DB layers.

## 1.1 2026-07-07 Follow-Up: Disk Size Readiness Must Precede Projection

The later validation for plan `05527cbe-974e-4ca8-b65e-f844cb3420e7` confirmed
that terminal projection alone is not enough. The Cloud DB/API still showed
`SYNCING`/`ACCEPTED`, while FTCTL had already failed the run with
`DR_TARGET_DISK_SIZE_UNRESOLVED` because VMware source disk `2000` reached the
ABLESTACK target map with `sizeBytes=0`.

This document remains the terminal projection contract. The required
pre-dispatch disk-size and guided-spec hardening is defined in
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`.
Implementation must satisfy both documents:

- `previewDrPlanSpec`/`createDrPlan` must reject or block execution when VMware
  source disk size is unresolved.
- `DrProtectionOrchestratorImpl` must not create a healthy `SKELETON_READY`
  replica disk with `size_bytes=NULL` or `0` for an executable
  `VMWARE_TO_KVM` plan.
- `FtctlDrRuntimeProjectionAdapter` and the projection reconciler must still
  project any host-side terminal error that occurs after dispatch.

## 2. Design Principles

1. UI and API must never present an accepted async command as healthy when the FTCTL runtime has already reached a terminal error.
2. Plan-level status is not enough for async DR execution. Projection must refresh by `planUuid` and the latest active `runUuid`.
3. Agent command transport success and FTCTL runtime success are different states and must be represented separately.
4. Target materialization readiness is complete only when target VM, disk, network, and durable checkpoint information are all available.
5. FTCTL must persist exact target-driver sub-errors. Generic `DR_ABLESTACK_DRIVER_FAILED` is allowed only as a wrapper, not as the only diagnostic.
6. DB projection must be atomic: when a run becomes terminal, the related plan, run, step, replica, and disk projection must be updated together.

## 3. UI Layer Design

### 3.1 Components

Update the DR plan screens in:

- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/views/infra/dr/DrPlanDetail.vue`
- `ui/src/views/infra/dr/components/DrRunProgress.vue`
- `ui/src/views/infra/dr/components/DrStatusPill.vue`
- `ui/src/views/infra/dr/components/DrActionToolbar.vue`
- `ui/src/api/dr.js`
- `ui/public/locales/ko-KR.json`
- `ui/public/locales/en-US.json`

### 3.2 Effective State Rendering

UI must derive its visible state from `effectiveState`, not only `plan.state`.

Effective state priority:

1. `lastRun.runtimeState in (ERROR, FAILED)` or `lastRun.state = FAILED` -> show error.
2. `plan.state = ERROR` -> show error.
3. `lastRun.state in (ACCEPTED, RUNNING)` -> show sync in progress.
4. `replica.readinessState = READY` and `targetMaterialized = true` -> show ready.
5. otherwise show preparation or warning state.

Required fields in API response:

```json
{
  "state": "SYNCING",
  "effectiveState": "ERROR",
  "runtimeState": "ERROR",
  "runtimeStep": "ablestack-driver-failed",
  "runtimeErrorCode": "DR_ABLESTACK_DRIVER_FAILED",
  "targetMaterialized": false,
  "lastRun": {
    "uuid": "bdbea146-ac2d-4ab1-9e6e-6a004a5173cc",
    "state": "FAILED",
    "projectionState": "runtime-error",
    "runtimeState": "ERROR",
    "runtimeErrorCode": "DR_ABLESTACK_DRIVER_FAILED",
    "workerState": "FAILED"
  }
}
```

### 3.3 Action Gating

UI must block follow-up DR actions unless the backend exposes the required readiness flags.

| Action | Required state |
| --- | --- |
| Start sync | plan enabled, no active run, sites connected, source inventory valid |
| Pause sync | active sync run accepted/running |
| Resume sync | paused run or paused plan |
| Failover | replica ready, target materialized, latest durable checkpoint exists |
| Test failover | replica ready, target materialized, test supported by engine |
| Failback | failed over state, reverse mapping ready |
| Delete plan | no active run, or delete request explicitly cancels active run first |

If `effectiveState = ERROR`, the primary action must become `Retry sync` or `View diagnostics`; failover actions must be disabled.

### 3.4 User Messages

Add localized UI messages for these error codes:

- `DR_ABLESTACK_DRIVER_FAILED`
- `DR_TARGET_DISK_SIZE_UNRESOLVED`
- `DR_TARGET_DISK_TYPE_INVALID`
- `DR_TARGET_DISK_PREPARE_FAILED`
- `DR_TARGET_VM_MATERIALIZE_FAILED`
- `DR_TARGET_NETWORK_MATERIALIZE_FAILED`
- `DR_SOURCE_DISK_SIZE_UNKNOWN`
- `DR_RUNTIME_STATUS_TERMINAL_ERROR`

The UI must show operator-friendly text first and keep raw JSON hidden by default. Raw runtime status can remain available in a diagnostic drawer or copy button.

## 4. API Layer Design

### 4.1 Response Contract

Extend or normalize the DR plan and run responses:

- `DrPlanResponse`
  - `state`
  - `effectiveState`
  - `readinessState`
  - `targetMaterialized`
  - `runtimeState`
  - `runtimeStep`
  - `runtimeErrorCode`
  - `runtimeErrorMessage`
  - `lastRun`
  - `latestReplica`
- `DrRunResponse`
  - `state`
  - `projectionState`
  - `currentStep`
  - `runtimeState`
  - `runtimeStep`
  - `runtimeErrorCode`
  - `runtimeErrorMessage`
  - `workerState`
  - `workerExitCode`
  - `targetVmPresent`
  - `targetNetworkPresent`
  - `targetStoragePresent`
  - `lastStatusJson` only for diagnostic views

### 4.2 Refresh Rules

The following commands must trigger a run-aware projection refresh before returning data:

- `getDrPlan`
- `listDrPlans`
- `listDrRuns`
- `listDrRunSteps`
- `listDrReplicas`
- `listDrReplicaDisks`

Refresh algorithm:

```java
DrPlanVO plan = drPlanDao.findByUuid(planUuid);
DrRunVO latestRun = drRunDao.findLatestByPlanId(plan.getId());

DrProjectionRefreshRequest request = new DrProjectionRefreshRequest(
    plan.getId(),
    latestRun == null ? null : latestRun.getUuid(),
    RefreshReason.API_READ
);

projectionService.refreshPlanProjection(request);
return responseBuilder.build(plan.getId());
```

The API must not swallow projection failures that contain valid runtime terminal status. If the FTCTL status command succeeds but the runtime payload says `ERROR`, API must return that effective error state.

## 5. Backend Projection Design

### 5.1 Target Classes

Update:

- `server/src/main/java/com/cloud/ftctl/FtctlDrRuntimeProjectionAdapter.java`
- `server/src/main/java/com/cloud/ftctl/FtctlDrProjectionService.java`
- `server/src/main/java/com/cloud/ftctl/FtctlDrStatusCommand.java`
- `server/src/main/java/com/cloud/ftctl/FtctlDrStatusAnswer.java`
- `server/src/main/java/com/cloud/ftctl/dao/DrRunDao.java`
- `server/src/main/java/com/cloud/ftctl/dao/DrRunStepDao.java`
- `server/src/main/java/com/cloud/ftctl/dao/DrReplicaDao.java`
- `server/src/main/java/com/cloud/ftctl/dao/DrReplicaDiskDao.java`

### 5.2 Run-Aware Status Query

`FtctlDrRuntimeProjectionAdapter.refreshPlanProjection` must find the latest active or latest terminal run and request run-specific status:

```java
DrRunVO run = drRunDao.findLatestByPlanId(plan.getId());
String runUuid = run == null ? null : run.getUuid();

FtctlDrStatusCommand cmd = new FtctlDrStatusCommand(plan.getUuid(), runUuid);
FtctlDrStatusAnswer answer = agentManager.easySend(hostId, cmd);
FtctlDrRuntimeStatus status = runtimeStatusMapper.from(answer);

applyRuntimeStatus(plan, run, status);
```

If both plan-level and run-level status are available, run-level terminal state wins.

### 5.3 Runtime Status Interpretation

Do not use `Answer.getResult()` as the only state signal.

Required interpretation order:

```java
boolean runtimeTerminalError =
    status.hasRuntimePayload()
    && (
        "ERROR".equals(status.getState())
        || "FAILED".equals(status.getState())
        || StringUtils.isNotBlank(status.getErrorCode())
        || "FAILED".equals(status.getWorkerState())
    );

if (runtimeTerminalError) {
    markRunFailed(plan, run, status);
    return;
}

if (!answer.getResult() && !status.hasRuntimePayload()) {
    markProjectionUnavailable(plan, run, answer);
    return;
}
```

### 5.4 Atomic Terminal Projection

When FTCTL runtime status is terminal error:

```java
run.setState(DrRunState.FAILED);
run.setProjectionState("runtime-error");
run.setCurrentStep(status.getStep());
run.setErrorCode(status.getErrorCode());
run.setErrorMessage(status.getErrorMessage());
run.setLastStatusJson(status.getRawJson());
run.setCompleted(new Date());
drRunDao.update(run.getId(), run);

plan.setState(DrPlanState.ERROR);
plan.setLastErrorCode(status.getErrorCode());
plan.setLastErrorMessage(status.getErrorMessage());
plan.setLastChecked(new Date());
drPlanDao.update(plan.getId(), plan);

drRunStepDao.closeOpenStep(run.getId(), status.getStep(), DrRunStepState.FAILED, status);
drReplicaDao.markLatestReplicaError(plan.getId(), status);
drReplicaDiskDao.markDisksError(plan.getId(), status);
```

This prevents the current inconsistent state: plan `SYNCING`, run `ACCEPTED`, runtime `ERROR`.

### 5.5 Readiness Evaluation

`DrPlanReadinessValidator` must treat target skeleton state as insufficient.

Readiness is `READY` only if:

- latest run succeeded or sync is actively producing durable checkpoints,
- target VM is materialized or target external ref is available,
- every target disk has a durable target ref,
- target network mapping is materialized,
- latest durable checkpoint time is present.

If FTCTL reports `target_vm_present=false`, readiness must be `FAILED` or `NOT_READY`, not `READY`.

## 6. Agent Layer Design

### 6.1 Command Contract

`FtctlDrStatusCommand` must carry:

- `planUuid`
- `runUuid`
- `json = true`

### 6.2 Wrapper Behavior

Update:

- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrStatusCommandWrapper.java`

Required command:

```bash
ablestack_vm_ftctl dr-status --plan "$PLAN_UUID" --run "$RUN_UUID" --json
```

If `runUuid` is empty, the wrapper may call plan-only status as fallback.

### 6.3 Answer Semantics

Agent answer must separate transport and runtime result:

- `commandSucceeded`: the wrapper could run `dr-status` and parse JSON.
- `runtimeResult`: `ok`, `running`, `error`, or `unknown`.
- `runtimeState`: FTCTL state field.
- `runtimeErrorCode`: FTCTL error code.
- `runtimeErrorMessage`: FTCTL error message.
- `rawStatusJson`: exact status payload.

The backend must not lose terminal runtime status because the shell command itself returned exit code `0`.

## 7. FTCTL Layer Design

### 7.1 Run-Aware Status

Update:

- `lib/ftctl/dr_runtime.sh`
- `lib/ftctl/dr_status.sh` or the status command implementation
- `bin/ablestack_vm_ftctl`

`dr-status --run` must read run state first:

1. `/run/ablestack-vm-ftctl/dr-runtime/plans/<plan>/runs/<run>.state`
2. `/run/ablestack-vm-ftctl/dr-runtime/plans/<plan>/status.state`
3. latest event log fallback

JSON output must include:

```json
{
  "result": "error",
  "plan": "...",
  "run": "...",
  "state": "ERROR",
  "step": "ablestack-driver-failed",
  "progress": 100,
  "error_code": "DR_ABLESTACK_DRIVER_FAILED",
  "error_message": "...",
  "driver": "VMWARE",
  "driver_state": "VMWARE_CONTRACT_READY",
  "worker_state": "FAILED",
  "worker_exit_code": 32,
  "target_vm_present": false,
  "target_network_present": false,
  "target_storage_present": true,
  "last_source_checkpoint_at": "...",
  "last_target_durable_at": ""
}
```

### 7.2 ABLESTACK Target Driver Diagnostics

Update:

- `lib/ftctl/dr_ablestack.sh`
- `lib/ftctl/dr_runtime.sh`

The ABLESTACK target driver must not return only exit code `32` without a sub-error.

Add explicit substeps:

- `ablestack-target-config-validated`
- `ablestack-source-size-resolved`
- `ablestack-target-disk-prepared`
- `ablestack-target-vm-materialized`
- `ablestack-target-network-attached`

Add explicit error codes:

- `DR_TARGET_DISK_TYPE_INVALID`
- `DR_SOURCE_DISK_SIZE_UNKNOWN`
- `DR_TARGET_DISK_SIZE_UNRESOLVED`
- `DR_TARGET_DISK_PREPARE_FAILED`
- `DR_TARGET_VM_MATERIALIZE_FAILED`
- `DR_TARGET_NETWORK_MATERIALIZE_FAILED`
- `DR_ABLESTACK_DRIVER_FAILED`

### 7.3 Disk Contract Normalization

The generated disk contract must be canonical before target preparation.

Required canonical fields:

```json
{
  "sourceDiskId": "2000",
  "sourceType": "vmdk",
  "sourcePath": "2000",
  "sourceCapacityBytes": 21474836480,
  "targetName": "Rokcy10-1-dr-disk-0",
  "targetType": "rbd",
  "targetStorageUuid": "91cae554-3fce-3f93-89d1-cefaf9bf8122",
  "targetPath": "rbd/<pool>/<image>",
  "targetCapacityBytes": 21474836480
}
```

For VMware source disks, FTCTL must not treat an opaque VMware disk id such as `2000` as a local qemu file path for size detection. Size must come from:

1. VMware inventory or VDDK metadata,
2. Cloud guided DR plan preview,
3. explicit target disk size selected in UI,
4. otherwise fail before execution with `DR_SOURCE_DISK_SIZE_UNKNOWN`.

For ABLESTACK RBD target storage, target type must be `rbd`. A target disk with RBD storage and `targetType=file` is invalid and must fail in validation before worker dispatch.

## 8. DB Layer Design

### 8.1 Required Projection Writes

No immediate schema change is required if existing columns are used consistently:

- `dr_plan.state`
- `dr_plan.last_error_code`
- `dr_plan.last_error_message`
- `dr_plan.last_checked`
- `dr_run.state`
- `dr_run.projection_state`
- `dr_run.current_step`
- `dr_run.error_code`
- `dr_run.error_message`
- `dr_run.last_status_json`
- `dr_run.completed`
- `dr_run_step.state`
- `dr_replica.state`
- `dr_replica.runtime_json`
- `dr_replica_disk.state`
- `dr_replica_disk.details_json`

If query performance becomes an issue, add optional columns later:

- `dr_run.runtime_state`
- `dr_run.runtime_error_code`
- `dr_run.worker_state`
- `dr_replica.target_materialized`

### 8.2 State Transition Rules

| Runtime observation | Plan state | Run state | Replica state | Disk state |
| --- | --- | --- | --- | --- |
| FTCTL accepted | `SYNCING` | `ACCEPTED` | `SKELETON_READY` | `SKELETON_READY` |
| Worker running | `SYNCING` | `RUNNING` | `SYNCING` | `SYNCING` |
| Target materialized but no checkpoint | `SYNCING` | `RUNNING` | `MATERIALIZED` | `MATERIALIZED` |
| Durable checkpoint ready | `READY` | `SUCCEEDED` | `READY` | `READY` |
| Runtime terminal error | `ERROR` | `FAILED` | `ERROR` | `ERROR` |

The backend must not leave a run in `ACCEPTED` after the host status says `worker_state=FAILED`.

## 9. Validation Plan

### 9.1 Current Failure Regression

1. Create a VMware to ABLESTACK plan.
2. Start sync.
3. Force or reproduce FTCTL runtime `ERROR`.
4. Call `getDrPlan`.
5. Expected:
   - API returns `effectiveState=ERROR`.
   - DB plan becomes `ERROR`.
   - DB latest run becomes `FAILED`.
   - UI shows error and diagnostic message.
   - failover actions are disabled.

### 9.2 Disk Contract Validation

1. Select RBD target storage in UI.
2. Preview plan spec.
3. Expected:
   - disk mapping has `targetType=rbd`.
   - disk size is non-zero.
   - if VMware source size is unknown, preview fails before sync start.

### 9.3 Run-Aware Status Validation

1. Start a sync run.
2. Check agent command log or host command execution.
3. Expected:
   - status command includes `--run <runUuid>`.
   - backend stores the raw runtime status in `dr_run.last_status_json`.

### 9.4 Target Readiness Validation

Plan is PASS for next step only when:

- plan effective state is `READY`,
- latest run is `SUCCEEDED`,
- target VM or target external ref exists,
- target disk refs exist,
- target network mapping exists,
- latest durable checkpoint is recorded.

`target_vm_present=false` is always FAIL for failover readiness.

## 10. Implementation Order

1. FTCTL run-aware `dr-status` and detailed ABLESTACK driver diagnostics.
2. Agent wrapper support for `--run` and runtime payload fields.
3. Backend run-aware projection and atomic terminal state update.
4. API response normalization with `effectiveState` and latest runtime fields.
5. UI effective-state rendering, action gating, and localized diagnostics.
6. Regression tests and smoke verification on plan detail/list views.

## 11. Implementation Update - 2026-07-07

This design has been implemented as a terminal projection recovery patch.

| Layer | Implemented change |
| --- | --- |
| FTCTL | ABLESTACK target disk normalization now derives RBD targets from selected target storage, preserves `targetType=rbd`, records exact driver error codes, and emits `error_message` plus `driver_exit_code` in runtime status JSON. |
| Agent/API bridge | Projection refresh now passes the active or latest run UUID to `FtctlDrStatusCommand`, so plan detail/list refresh reads the run state that produced the sync failure. |
| Backend | Runtime `ERROR`, worker `FAILED`, and non-empty runtime error codes now atomically fail the run, plan, replica, and replica disk projection instead of leaving the API in `SYNCING`/`ACCEPTED`. |
| API response | DR plan and run responses now include effective/runtime/worker state fields used by UI gating and diagnostics. |
| UI | DR plan list/detail and run progress now render `effectiveState`, runtime/worker state, and localized FTCTL/target-driver error messages. |
| DB | No schema change was required. Existing JSON/status columns are used for terminal runtime projection; optional denormalized runtime columns remain a later optimization. |

Build verification:

- Cloud changed Maven module build: `mvn -pl plugins/integrations/disaster-recovery -am -DskipTests install`
- Cloud UI build: `NODE_OPTIONS=--openssl-legacy-provider npm run build`
- FTCTL shell syntax check: `bash -n lib/ftctl/dr_ablestack.sh lib/ftctl/dr_runtime.sh bin/ablestack_vm_ftctl.sh`

## 12. Follow-up Contract - 2026-07-07

The next VMware to ABLESTACK sync validation found that terminal projection
alone is not sufficient when the deployed Cloud classes are present on disk but
the process serving `:8080` is still an old Java process, or when FTCTL exposes
the VMware source disk map as the active disk map for an ABLESTACK target
preparation step.

Detailed follow-up design:

- [537-cross-hypervisor-dr-serving-process-and-disk-map-contract-design-20260707.md](537-cross-hypervisor-dr-serving-process-and-disk-map-contract-design-20260707.md)

Additional design rules:

1. Cloud deployment validation must assert that `mold.service` `MainPID` is the
   same PID that owns listener port `8080`.
2. DR read APIs may refresh projection, but the response is not trustworthy if a
   stale process serves the endpoint; deployment must fail before retest in that
   case.
3. FTCTL must persist separate `source_disk_map_path` and
   `target_disk_map_path` fields. For ABLESTACK targets, compatibility
   `disk_map_path` must point to the ABLESTACK target map.
4. VMware disk identifiers such as `2000` are source inventory references, not
   local qemu file paths. Disk size must come from Cloud guided inventory,
   VMware/VDDK metadata, or explicit operator target size.
5. Next-step PASS requires target VM, target disk refs, target network evidence,
   restore point evidence, and durable target checkpoint. `ACCEPTED` is never a
   readiness state by itself.

## 13. VMware Mover And Cloud-owned Materialization Follow-up - 2026-07-07

The subsequent plan
`ba4f53f8-eb17-41cd-bbe6-7e746772f209` proved that terminal projection and disk
map fixes are necessary but not sufficient for VMware to ABLESTACK PASS.

New finding:

- FTCTL target RBD preparation succeeded.
- VMware data movement failed because no production mover was configured.
- Cloud API/DB still exposed stale `SYNCING/ACCEPTED`.
- Cloud target VM and target volume rows were not materialized.

Detailed follow-up design:

- [542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md](542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md)

This follow-up adds two implementation gates:

1. VMware mover availability must be validated before target storage
   allocation.
2. ABLESTACK target readiness must be Cloud-owned: RBD-only target storage is
   not a valid target VM/volume materialization result.

## 2026-07-08 Update: Terminal Projection Must Not Consume Pending State

This terminal projection contract still applies to runtime `ERROR`, `FAILED`,
`worker_state=FAILED`, and non-empty FTCTL runtime `error_code`. It must not be
used to convert active full-seed pending states into failure.

For VMware to ABLESTACK initial sync:

- `state=SYNCING`
- `step=full-seed-transfer`
- `worker_state=RUNNING`
- `target_storage_present=true`
- `target_vm_present=false`
- `restore_point_present=false`
- empty `error_code`

means the target disk copy is in progress. It is not a terminal target VM
missing condition. Cloud must keep the run active and clear stale run/step
error fields.

The pending-state code-level design is maintained in
`546-cross-hypervisor-dr-initial-sync-pending-projection-contract-design-20260708.md`.
