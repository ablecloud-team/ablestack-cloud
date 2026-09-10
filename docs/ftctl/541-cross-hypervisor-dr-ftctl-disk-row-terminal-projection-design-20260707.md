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

# Cross Hypervisor DR FTCTL Disk Row And Terminal Projection Design

Date: 2026-07-07

## 1. Purpose

This document closes the follow-up gap found during the VMware to ABLESTACK
sync validation for plan `8a8ccdf4-ab2b-4819-aa5f-9c476cd8b8a5`.

The plan was created and the sync command was accepted asynchronously, but the
actual FTCTL worker failed before target materialization:

| Item | Observed value |
| --- | --- |
| Plan UUID | `8a8ccdf4-ab2b-4819-aa5f-9c476cd8b8a5` |
| Run UUID | `0630c71f-addd-4b1d-bf8a-cd14111b62bf` |
| Direction | `VMWARE_TO_KVM` |
| Cloud API plan state | `SYNCING` |
| Cloud DB run state | `ACCEPTED` |
| Cloud DB replica state | `SKELETON_READY` |
| Target VM / volume | not created |
| Restore point | not created |
| FTCTL runtime state | `ERROR` |
| FTCTL runtime step | `ablestack-target-prepare-failed` |
| FTCTL error code | `DR_TARGET_DISK_TYPE_INVALID` |
| FTCTL event reason | `missing_target_type` |

The important difference from the earlier disk-size issue is that the generated
ABLESTACK target disk map was structurally correct:

```json
{
  "sourceFormat": "",
  "targetFormat": "raw",
  "targetStorageType": "RBD",
  "targetType": "rbd",
  "targetPath": "/dev/rbd/Rokcy10-1-dr-disk-0",
  "sizeBytes": 107374182400
}
```

The worker nevertheless reported `missing_target_type`. That points to a host
runtime serialization/parsing defect, not a Cloud guided-spec omission. The
current FTCTL code emits disk rows as tab-separated text and reads them with
Bash `read` using tab in `IFS`. Because tab is an IFS whitespace character,
empty fields such as `sourceFormat=""` are not preserved reliably. The columns
shift and the final `targetType` field can become empty.

This document defines the layered fix and the readiness contract so UI, API,
backend, agent, FTCTL, and DB all converge on the same actual runtime result.

## 2. Design Principles

1. `ACCEPTED` means the Agent accepted the command transport. It is not a
   sync-health state.
2. FTCTL disk-map records must be parsed as structured data. Empty fields must
   never shift later columns.
3. Transport success and runtime success are separate signals. A status command
   may exit successfully while the runtime payload says `state=ERROR`.
4. Cloud read APIs and background reconciliation must project terminal runtime
   state into DB before UI makes action decisions.
5. Next-step PASS requires target materialization evidence: target VM reference,
   target disk/volume reference, durable checkpoint, and restore point.

## 3. UI Layer Design

Target files:

- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/views/infra/dr/DrPlanOverview.vue`
- `ui/src/views/infra/dr/DrRunsTab.vue`
- `ui/src/components/dr/DrRunProgress.vue`
- `ui/src/utils/dr/resourceActions.js`
- `ui/src/api/dr.js`
- `ui/public/locales/en.json`
- `ui/public/locales/ko_KR.json`

Required behavior:

1. DR plan list/detail must render `effectiveState` from API, with runtime
   terminal state taking priority over stored `plan.state`.
2. After starting sync, the UI must poll `getDrPlan` and `listDrRuns` until the
   latest run reaches `SUCCEEDED`, `FAILED`, or `CANCELED`.
3. The UI must not enable failover, test failover, failback, reprotect, or
   cleanup from `plan.state=SYNCING` or `lastRun.state=ACCEPTED`.
4. If runtime status contains `errorCode`, show a concise error banner and a
   diagnostics drawer. The raw JSON remains hidden by default.
5. Disk diagnostics must show source and target map paths separately:
   `sourceDiskMapPath`, `targetDiskMapPath`, `diskMapRole`,
   `targetDiskCount`, and `targetDiskInvalidCount`.

State normalization:

```js
function normalizeDrEffectiveState(plan) {
  const runtimeState = upper(plan.runtimestate || plan.runtimeState)
  const workerState = upper(plan.workerstate || plan.workerState)
  const errorCode = plan.runtimeerrorcode || plan.runtimeErrorCode ||
    plan.lasterrorcode || plan.lastErrorCode

  if (runtimeState === 'ERROR' || runtimeState === 'FAILED' ||
      workerState === 'FAILED' || errorCode) {
    return 'ERROR'
  }

  return upper(plan.effectivestate || plan.effectiveState || plan.state)
}

function isDrNextStepReady(plan) {
  return normalizeDrEffectiveState(plan) === 'READY' &&
    bool(plan.targetvmpresent || plan.targetVmPresent) &&
    bool(plan.targetstoragepresent || plan.targetStoragePresent) &&
    bool(plan.restorepointpresent || plan.restorePointPresent) &&
    !plan.runtimeerrorcode && !plan.runtimeErrorCode
}
```

Localized messages:

- `message.dr.plan.runtime.error.disk.type`
- `message.dr.plan.runtime.error.target.prepare`
- `message.dr.plan.runtime.error.projection.stale`
- `message.dr.plan.nextstep.not.ready`

Recommended Korean text:

- `대상 디스크 매핑을 FTCTL이 해석하지 못했습니다. 동기화는 실패했으며 대상 VM/볼륨은 생성되지 않았습니다.`
- `실행 상태가 아직 Cloud DB에 반영되지 않았습니다. 잠시 후 새로고침하거나 상태 조회를 다시 실행하세요.`

## 4. API Layer Design

Target files:

- `GetDrPlanCmd.java`
- `ListDrPlansCmd.java`
- `ListDrRunsCmd.java`
- `ListDrRunStepsCmd.java`
- `ListDrReplicasCmd.java`
- `ListDrRestorePointsCmd.java`
- `DrPlanResponse.java`
- `DrRunResponse.java`
- `DrResponseGenerator.java`

Required behavior:

1. All DR read APIs used by list/detail/progress tabs must invoke run-aware
   projection refresh before response generation.
2. The response must expose transport and runtime states separately.
3. A successful status command with payload `state=ERROR`, `result=error`,
   `worker_state=FAILED`, or non-empty `error_code` must return
   `effectiveState=ERROR`.
4. If status refresh cannot reach the host, return a visible stale projection
   signal instead of silently preserving optimistic `SYNCING`.

Response contract:

```json
{
  "state": "SYNCING",
  "effectiveState": "ERROR",
  "runtimeProjectionState": "failed",
  "runtimeProjectionChecked": "2026-07-07T14:49:00+0900",
  "runtimeState": "ERROR",
  "runtimeStep": "ablestack-target-prepare-failed",
  "runtimeErrorCode": "DR_TARGET_DISK_TYPE_INVALID",
  "runtimeErrorMessage": "ABLESTACK target preparation failed before target VM materialization",
  "targetMaterialized": false,
  "targetVmPresent": false,
  "targetStoragePresent": true,
  "targetNetworkPresent": false,
  "restorePointPresent": false,
  "sourceDiskMapPath": "/run/ablestack-vm-ftctl/.../vmware-disks.json",
  "targetDiskMapPath": "/run/ablestack-vm-ftctl/.../ablestack-disks.json",
  "targetDiskCount": 1,
  "targetDiskInvalidCount": 0,
  "lastRun": {
    "state": "FAILED",
    "projectionState": "failed",
    "currentStep": "runtime-projection",
    "workerState": "FAILED",
    "workerExitCode": 32
  }
}
```

Read command flow:

```java
DrPlanVO plan = drPlanService.getPlan(planId);
DrPlanVO refreshed = drProjectionService.refreshPlanProjection(plan.getId(), true);
DrPlanVO latest = drPlanService.getPlan(refreshed.getId());
DrPlanResponse response = drResponseGenerator.createPlanResponse(
        latest,
        drPlanService.getActionEligibility(latest.getId()));
```

For list APIs, each active or runtime-relevant plan should be refreshed. Plans
with no active run and terminal stable state can use a short projection cache
window to avoid excessive host polling.

## 5. Backend Layer Design

Target files:

- `DrProjectionService.java`
- `DrProjectionServiceImpl.java`
- `FtctlDrRuntimeProjectionAdapter.java`
- `FtctlDrUnifiedActionAdapter.java`
- `DrProtectionOrchestratorImpl.java`
- `DrPlanServiceImpl.java`
- `DrConstants.java`
- `DrResponseGenerator.java`
- `DrRunDao.java`
- `DrRunStepDao.java`
- `DrReplicaDao.java`
- `DrReplicaDiskDao.java`
- `DrRestorePointDao.java`

### 5.1 Runtime Terminal Classification

`FtctlDrRuntimeProjectionAdapter` must treat runtime terminal payload as
authoritative even when `Answer.getResult()` is true:

```java
private boolean isRuntimeTerminalFailure(FtctlDrStatusAnswer status, JsonObject runtime) {
    String result = lower(firstNonBlank(status.getFtctlResult(), stringValue(runtime, "result")));
    String state = upper(firstNonBlank(status.getState(), stringValue(runtime, "state")));
    String workerState = upper(firstNonBlank(status.getWorkerState(), stringValue(runtime, "worker_state")));
    String errorCode = firstNonBlank(status.getErrorCode(), stringValue(runtime, "error_code"));

    return "error".equals(result)
            || "failed".equals(result)
            || "ERROR".equals(state)
            || "FAILED".equals(state)
            || "FAILED".equals(workerState)
            || StringUtils.isNotBlank(errorCode);
}
```

Projection order:

```java
JsonObject runtime = parseObject(status.getStatusJson());

if (isStatusTimeout(status, runtime)) {
    markProjectionStale(plan, status);
    return staleResult();
}

if (isRuntimeTerminalFailure(status, runtime)) {
    failRunFromProjection(plan, run, status, runtime);
    return terminalProjectedResult();
}

if (isRunSatisfiedByRuntime(plan, run, status, runtime)) {
    completeRunFromProjection(plan, run, status);
    return successResult();
}

markSyncTargetPending(plan, run, status, runtime);
```

### 5.2 Atomic DB Projection

Terminal projection must update related rows in one transaction:

```java
Transaction.execute(status -> {
    run.setState(DrConstants.RUN_STATE_FAILED);
    run.setProjectionState("failed");
    run.setProjectionChecked(now);
    run.setCurrentStepName("runtime-projection");
    run.setErrorCode(errorCode);
    run.setErrorMessage(errorMessage);
    run.setLastStatusJson(statusJson);
    run.setCompleted(now);
    drRunDao.update(run.getId(), run);

    recordRunProjectionStep(run, DrConstants.STEP_STATE_FAILED, 100,
            statusJson, errorCode, errorMessage);

    plan.setState(DrConstants.PLAN_STATE_ERROR);
    plan.setLastErrorCode(errorCode);
    plan.setLastErrorMessage(errorMessage);
    drPlanDao.update(plan.getId(), plan);

    markReplicaProjectionFailed(plan, status, runtime, errorCode, errorMessage);
    persistRunProjectionEvent(plan, run, DrConstants.EVENT_RUN_FAILED,
            DrConstants.EVENT_SEVERITY_ERROR, errorMessage, statusJson);
});
```

Projection must never leave this combination after the transaction:

- `dr_plan.state=SYNCING`
- latest `dr_run.state=ACCEPTED`
- latest runtime status `state=ERROR`

### 5.3 Background Reconciler

Add a non-blocking active-plan reconciler:

```java
List<DrPlanVO> candidates = drPlanDao.listProjectionCandidates(
        Set.of("SYNCING", "TESTING", "FAILING_BACK", "REPROTECTING"),
        Set.of("ACCEPTED", "RUNNING", "RETRYING"));

for (DrPlanVO plan : candidates) {
    try {
        drProjectionService.refreshPlanProjection(plan.getId(), false);
    } catch (Exception e) {
        persistProjectionStaleEvent(plan, e);
    }
}
```

Recommended interval: 15 seconds. The reconciler must be best-effort and must
not block UI/API calls.

### 5.4 Eligibility

`DrPlanServiceImpl.getActionEligibility()` must call or consume fresh projection
for runtime-active plans before enabling next-step actions.

Rules:

| Action | Required evidence |
| --- | --- |
| sync retry | no active run, plan `ERROR`, retryable or operator-allowed |
| failover | plan `READY`, latest run `SUCCEEDED`, target VM present, restore point present |
| test failover | same as failover plus test supported |
| delete | no active run; runtime resources released or explicit cleanup path |
| update | no active run; disruptive updates blocked when runtime resources exist |

## 6. Agent Layer Design

Target files:

- `core/src/main/java/com/cloud/agent/api/FtctlDrStatusAnswer.java`
- `core/src/main/java/com/cloud/agent/api/FtctlDrStatusCommand.java`
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrStatusCommandWrapper.java`
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrActionCommandWrapper.java`
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrCommandHelper.java`

Required behavior:

1. Agent status wrapper must preserve transport success separately from runtime
   success.
2. `FtctlDrStatusAnswer` should expose these fields directly or through
   `statusJson`:
   - `runtimeResult`
   - `runtimeAccepted`
   - `runtimeErrorMessage`
   - `driverExitCode`
   - `workerState`
   - `workerExitCode`
   - `targetDiskInvalidReasons`
3. Status command must always pass `--plan` and latest `--run` so stale plan
   status cannot hide a failed run.
4. Action wrapper must not treat a later status probe as accepted if the probed
   payload already has `state=ERROR`, `result=error`, or `error_code`.

Status wrapper classification:

```java
boolean transportSuccess = exitValue == 0;
JsonObject payload = parseJsonObject(output);
String runtimeResult = getString(payload, "result");
String runtimeState = getString(payload, "state");
String runtimeErrorCode = getString(payload, "error_code");

FtctlDrStatusAnswer answer = new FtctlDrStatusAnswer(command, transportSuccess, ...);
answer.setRuntimeResult(runtimeResult);
answer.setRuntimeTerminal("error".equalsIgnoreCase(runtimeResult)
        || "ERROR".equalsIgnoreCase(runtimeState)
        || StringUtils.isNotBlank(runtimeErrorCode));
```

The backend remains responsible for mutating DB state; Agent only transports
structured runtime evidence.

## 7. FTCTL Layer Design

Target files:

- `lib/ftctl/dr_ablestack.sh`
- `lib/ftctl/dr_runtime.sh`
- `lib/ftctl/dr_scheduler.sh`
- `bin/ablestack_vm_ftctl.sh`
- `bin/ablestack_vm_ftctl_selftest.sh`

### 7.1 Replace TSV Disk Rows With JSONL

Current risky pattern:

```bash
while IFS=$'\t' read -r device source_path target_path source_format target_format size_bytes source_type target_type; do
  ...
done < <(ftctl_dr_ablestack_disk_rows "${disk_map}")
```

This must be replaced with structured JSON Lines. Empty `sourceFormat` must not
shift `targetFormat`, `sizeBytes`, `sourceType`, or `targetType`.

New helper:

```bash
ftctl_dr_ablestack_disk_rows_json() {
  local disk_map="${1-}"
  [[ -n "${disk_map}" && -f "${disk_map}" ]] || return 1
  python3 - "${disk_map}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as fh:
    data = json.load(fh)

for disk in data.get("disks") or []:
    row = {
        "device": str(disk.get("device") or ""),
        "sourcePath": str(disk.get("sourcePath") or ""),
        "targetPath": str(disk.get("targetPath") or ""),
        "sourceFormat": str(disk.get("sourceFormat") or ""),
        "targetFormat": str(disk.get("targetFormat") or ""),
        "sizeBytes": str(disk.get("sizeBytes") or ""),
        "sourceType": str(disk.get("sourceType") or ""),
        "targetType": str(disk.get("targetType") or ""),
    }
    print(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
PY
}
```

Read helper:

```bash
ftctl_dr_json_field() {
  local json="${1-}" key="${2-}"
  python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get(sys.argv[2], "") or "")' \
    "${json}" "${key}"
}
```

Target preparation loop:

```bash
while IFS= read -r row_json; do
  [[ -n "${row_json}" ]] || continue
  device="$(ftctl_dr_json_field "${row_json}" device)"
  source_path="$(ftctl_dr_json_field "${row_json}" sourcePath)"
  target_path="$(ftctl_dr_json_field "${row_json}" targetPath)"
  source_format="$(ftctl_dr_json_field "${row_json}" sourceFormat)"
  target_format="$(ftctl_dr_json_field "${row_json}" targetFormat)"
  size_bytes="$(ftctl_dr_json_field "${row_json}" sizeBytes)"
  source_type="$(ftctl_dr_json_field "${row_json}" sourceType)"
  target_type="$(ftctl_dr_json_field "${row_json}" targetType)"
  ...
done < <(ftctl_dr_ablestack_disk_rows_json "${disk_map}")
```

For performance, a single Python decoder can also write shell-safe key/value
records. The structural rule is the same: do not use IFS whitespace-delimited
records for nullable fields.

### 7.2 Records File Format

Change target-preparation records from TSV to JSONL:

```bash
printf '%s\n' "$(python3 - <<PY
import json
print(json.dumps({
  "device": "${device}",
  "sourcePath": "${source_path}",
  "targetPath": "${target_path}",
  "sourceFormat": "${source_format}",
  "targetFormat": "${target_format}",
  "sizeBytes": int("${resolved_size}"),
  "sourceType": "${source_type}",
  "targetType": "${target_type}",
}, sort_keys=True, separators=(",", ":")))
PY
)" >> "${records_path}"
```

`ftctl_dr_ablestack_write_manifest()` must read both JSONL and legacy TSV
records during one compatibility window. New records must be JSONL only.

### 7.3 Error Classification

`DR_TARGET_DISK_TYPE_INVALID` must be emitted only when the canonical JSON row
has no target type or has an incompatible target type. A target creation failure
must be `DR_TARGET_DISK_PREPARE_FAILED`.

Required classification:

| Condition | Error code |
| --- | --- |
| canonical row missing target type | `DR_TARGET_DISK_TYPE_INVALID` |
| RBD storage but target type is not `rbd` | `DR_TARGET_DISK_TYPE_INVALID` |
| target path cannot be derived | `DR_TARGET_DISK_MAPPING_INVALID` |
| positive source size is missing | `DR_TARGET_DISK_SIZE_UNRESOLVED` |
| `rbd create` or target prepare command fails | `DR_TARGET_DISK_PREPARE_FAILED` |

The runtime status JSON must include disk-level diagnostics:

```json
{
  "target_disk_count": 1,
  "target_disk_invalid_count": 1,
  "target_disk_invalid_reasons": [
    {
      "device": "disk0",
      "reason": "missing_target_type",
      "targetPath": "/dev/rbd/Rokcy10-1-dr-disk-0"
    }
  ]
}
```

### 7.4 Selftests

Add FTCTL selftests:

1. `selftest_case_dr_ablestack_disk_row_preserves_empty_source_format`
   - input: `sourceFormat=""`, `targetFormat=raw`, `targetType=rbd`
   - expected: target prepare receives `target_type=rbd`
2. `selftest_case_dr_ablestack_rbd_target_type_from_json`
   - input: RBD storage and explicit `targetType=rbd`
   - expected: no `DR_TARGET_DISK_TYPE_INVALID`
3. `selftest_case_dr_runtime_terminal_error_status_payload`
   - input: failed worker state
   - expected: `dr-status --json` emits `state=ERROR`, `worker_state=FAILED`,
     `error_code`, `target_materialized=false`

## 8. DB Layer Design

No mandatory schema migration is required for the immediate fix. Existing
columns can represent the complete state:

| Table | Required update on terminal failure |
| --- | --- |
| `dr_plan` | `state=ERROR`, `last_error_code`, `last_error_message`, `updated` |
| `dr_run` | `state=FAILED`, `projection_state=failed`, `projection_checked`, `current_step_name=runtime-projection`, `error_code`, `error_message`, `completed`, `last_status_json` |
| `dr_run_step` | upsert `runtime-projection` step with `state=FAILED`, `progress=100`, `details_json=statusJson` |
| `dr_replica` | `state=ERROR`, `runtime_state_json=statusJson` |
| `dr_replica_disk` | `state=ERROR`, `details_json` enriched with disk invalid reasons |
| `dr_restore_point` | no row is created unless `last_target_durable_at` exists |
| `dr_event` | projection failure event with runtime JSON |

Optional future columns can be added only if query performance or UI filtering
requires them:

```sql
ALTER TABLE dr_run
  ADD COLUMN runtime_result varchar(32) NULL,
  ADD COLUMN runtime_state varchar(32) NULL,
  ADD COLUMN runtime_error_code varchar(255) NULL,
  ADD COLUMN runtime_error_message varchar(1024) NULL,
  ADD COLUMN runtime_updated datetime NULL;
```

## 9. Layer Responsibility Summary

| Layer | Before | To be |
| --- | --- | --- |
| UI | Could show `SYNCING` from stored plan/run state. | Shows runtime-effective state, polls until terminal, disables next actions unless materialized. |
| API | Could return optimistic `SYNCING/ACCEPTED` when DB projection lagged. | Refreshes run-aware projection before reads and returns runtime terminal fields. |
| Backend | Projection existed but terminal runtime must be treated as authoritative on every read/reconcile path. | Classifies payload terminal state independently of transport success and updates plan/run/replica/disk atomically. |
| Agent | Transports FTCTL status output. | Preserves transport success separately from runtime result and exposes worker/disk diagnostics. |
| FTCTL | Disk rows use whitespace-delimited text and can lose empty fields. | Disk rows/records are JSONL or otherwise structured; empty fields cannot shift `targetType`. |
| DB | Stored accepted run and skeleton replica even after host-side failure. | Stores terminal failure in plan/run/step/replica/disk/event consistently. |

## 10. Validation Plan

### 10.1 Unit and Selftest

| Area | Test |
| --- | --- |
| FTCTL | JSONL row parser preserves empty `sourceFormat` and reads `targetType=rbd`. |
| FTCTL | RBD target prepare calls `rbd create` or validates existing image using canonical spec. |
| Agent | `LibvirtFtctlDrStatusCommandWrapper` parses runtime error payload and sets worker/disk fields. |
| Backend | `FtctlDrRuntimeProjectionAdapterTest` projects `state=ERROR`, `result=error`, `worker_state=FAILED` to failed run and error plan. |
| API | `getDrPlan` after runtime failure returns `effectiveState=ERROR`. |
| UI | DR plan list/detail shows runtime error and disables failover/test failover. |

### 10.2 Live Retest PASS Criteria

For a new VMware to ABLESTACK plan:

1. After `start sync`, API may briefly show accepted/running.
2. If FTCTL fails, DB/API/UI converge to `ERROR/FAILED` within one projection
   polling interval.
3. If FTCTL succeeds, target RBD image exists, target VM/volume references are
   persisted, restore point exists, latest run is `SUCCEEDED`, and UI shows
   next-step actions only then.

PASS is not allowed when:

- latest run remains `ACCEPTED` after host runtime is terminal;
- target VM id is null while UI enables failover/test failover;
- restore point is missing while plan appears ready;
- FTCTL runtime says `ERROR` but DB/API state remains `SYNCING`.

## 11. 2026-07-07 Follow-up: VMware Mover And Projection Convergence

The follow-up live test for plan
`ba4f53f8-eb17-41cd-bbe6-7e746772f209` confirmed that the disk-row/RBD fix
worked: FTCTL preserved `targetType=rbd`, created the canonical target path
`/dev/rbd/rbd/Rokcy10-1-dr-disk-0`, and did not report invalid target disks.

The remaining failure moved to the next layer:

```text
state=ERROR
step=scheduler-failed
worker_state=FAILED
error_code=DR_VMWARE_MOVER_UNAVAILABLE
target_storage_present=true
target_vm_present=false
```

The detailed design for this next implementation pass is documented in
[542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md](542-cross-hypervisor-dr-vmware-mover-and-projection-convergence-design-20260707.md).

Additional rules added by this finding:

- FTCTL must preflight the VMware mover before allocating target RBD images.
- `DR_VMWARE_MOVER_UNAVAILABLE` is a terminal sync failure, not a pending
  materialization state.
- Cloud projection must import the terminal `dr-status` payload into DB before
  returning `getDrPlan` or `listDrRuns`.
- Target storage alone is never enough for PASS. ABLESTACK target PASS requires
  Cloud-owned target VM and volume references.
