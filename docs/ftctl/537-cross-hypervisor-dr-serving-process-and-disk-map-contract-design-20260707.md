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

# Cross Hypervisor DR Serving Process And Disk Map Contract Design - 2026-07-07

## 1. Purpose

This document closes the remaining structural gap found during the VMware to
ABLESTACK DR sync validation for plan `cf377f76-e539-47b5-8dfd-dcaf2b3aaf78`.

The earlier terminal projection design ensures that a host runtime failure can be
projected into Cloud DB and UI state. The latest validation found two additional
contracts that must be explicit:

1. The Cloud API/UI must be served by the currently deployed management process.
   If an old Java process still owns port `8080`, new projection code is present
   on disk but not active at runtime.
2. A VMware source disk map and an ABLESTACK target disk map are different
   artifacts. The runtime must not let the VMware `vmware-disks.json` overwrite
   or represent the ABLESTACK target-preparation contract.

Without these contracts, UI/API can continue to show `SYNCING`/`ACCEPTED` while
FTCTL already reports `ERROR`, and an ABLESTACK target worker can fail before
creating the target VM or target volume.

## 2. Observed Failure

Current validation state:

| Item | Observed value |
| --- | --- |
| Plan UUID | `cf377f76-e539-47b5-8dfd-dcaf2b3aaf78` |
| Latest run UUID | `e8a5647d-3b37-4b51-83d8-22df1ad0f7e4` |
| DB plan state | `SYNCING` |
| DB run state | `ACCEPTED` |
| DB replica state | `SKELETON_READY` |
| FTCTL runtime state | `ERROR` |
| FTCTL runtime step | `ablestack-target-prepare-failed` |
| FTCTL runtime error | `DR_TARGET_DISK_TYPE_INVALID` |
| Target VM | not present |
| Target volume/RBD image | not present |
| Restore point | not present |

Additional runtime evidence:

- The deployed Cloud JAR contained the new projection response fields.
- The active `mold.service` process was not the same process that owned
  listener port `8080`.
- The API endpoint therefore served stale code and did not expose the new
  runtime/effective projection fields.
- The FTCTL state pointed to `disk_map_path=.../vmware-disks.json` while the
  ABLESTACK target contract was available in `ablestack-disks.json`.
- `vmware-disks.json` contained source VMDK references such as `2000` and no
  ABLESTACK target storage/type contract.
- `ablestack-disks.json` contained ABLESTACK target storage data but still
  needed strict target size/type validation before the worker starts.

## 2.1 2026-07-07 Additional Failure: VMware Source Size Missing

A later validation for plan `05527cbe-974e-4ca8-b65e-f844cb3420e7` found the
same source/target disk-map split working as intended, but the input contract
was still not strong enough:

| Item | Observed value |
| --- | --- |
| Run UUID | `79f4a7b9-778b-4279-a4bd-3aa7af38ed53` |
| Cloud plan/run state | `SYNCING` / `ACCEPTED` |
| FTCTL state | `ERROR` |
| FTCTL step | `ablestack-target-map-invalid` |
| FTCTL error | `DR_TARGET_DISK_SIZE_UNRESOLVED` |
| `vmware-disks.json` | disk `2000`, `sizeBytes=0` |
| `ablestack-disks.json` | disk `2000`, `sizeBytes=0`, target `RBD` |
| Target VM/volume | not created |
| Restore point | not created |

This expands the contract:

- VMware source disk size must be resolved during inventory/preview before
  `createDrPlan(startsync=true)` can dispatch.
- Cloud must not persist an executable replica disk as `SKELETON_READY` when
  its `size_bytes` is null or zero.
- RBD target storage must be normalized to the ABLESTACK target contract
  (`targetType=rbd`, canonical raw block target semantics) before FTCTL worker
  materialization.
- FTCTL remains the final guard, but Cloud should fail fast with a clear
  `SOURCE_DISK_SIZE_UNRESOLVED` or `DR_TARGET_DISK_SIZE_UNRESOLVED` message.

The complete layered design for this follow-up is documented in
`538-cross-hypervisor-dr-vmware-to-kvm-disk-size-and-projection-hardening-design-20260707.md`.

## 2.2 2026-07-07 Additional Failure: Empty Disk Row Field Shift

Validation for plan `8a8ccdf4-ab2b-4819-aa5f-9c476cd8b8a5` found a lower-level
FTCTL target-map serialization bug after the source/target disk-map split and
source-size propagation were corrected.

The generated `ablestack-disks.json` was correct:

- `targetStorageType=RBD`
- `targetType=rbd`
- `targetFormat=raw`
- `targetPath=/dev/rbd/Rokcy10-1-dr-disk-0`
- `sizeBytes=107374182400`

FTCTL still failed with `DR_TARGET_DISK_TYPE_INVALID` and event reason
`missing_target_type`. Code inspection showed that `dr_ablestack.sh` converts
disk objects into tab-separated rows and then reads them with Bash `read` using
tab as `IFS`. Because tab is treated as IFS whitespace, empty nullable fields
such as `sourceFormat=""` can be collapsed and later columns can shift, making
the final `targetType` appear empty.

The structural fix is to stop using whitespace-delimited rows for nullable disk
fields. The ABLESTACK target-preparation path must use JSON Lines or another
field-preserving structured format. Cloud projection must also continue to
import terminal runtime state from the host so DB/API/UI do not remain
`SYNCING`/`ACCEPTED` after FTCTL has already reached `ERROR`.

The complete layered design for this follow-up is documented in
`541-cross-hypervisor-dr-ftctl-disk-row-terminal-projection-design-20260707.md`.

## 3. Design Goals

- A UI refresh must not hide a terminal FTCTL runtime error.
- API state must be projected from the actual host runtime for the latest run.
- Cloud deployment validation must fail if the current service process is not
  the process serving `:8080`.
- Source disk metadata and target disk materialization metadata must be separate
  first-class contracts.
- ABLESTACK target preparation must fail before worker acceptance when required
  target disk type, target storage, target size, target network, or target
  offering data is missing.
- Next-step PASS requires target VM/materialization evidence, not only an
  accepted sync run.

## 4. Layered Design

### 4.1 UI Layer

Files:

- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/views/infra/dr/DrPlanOverview.vue`
- `ui/src/utils/dr/resourceActions.js`
- `ui/src/api/dr.js`
- `ui/public/locales/en.json`
- `ui/public/locales/ko_KR.json`

Required behavior:

1. DR plan list/detail must render the effective state returned by the API, not
   only the stored plan state.
2. If `runtimeprojectionstate`, `runtimestate`, `runtimeerrorcode`, or
   `runtimeerrormessage` is present, the detail view must show it in the main
   status panel and latest run panel.
3. If the API marks projection as stale or unavailable, the UI must show a
   warning status and disable failover/test/failback actions.
4. `SYNCING` is only an in-progress display state when the latest run has one of
   these proofs:
   - `runtimeprojectionstate=syncing|target-materializing`
   - worker state `STARTING|RUNNING|RETRYING`
   - target materialization evidence is progressing
5. Action gating must treat these cases as not ready:
   - latest run state `ACCEPTED` with no runtime worker heartbeat
   - runtime state `ERROR|FAILED`
   - `target_vm_present=false`
   - no target disk refs
   - no restore point

Code-level update:

```js
function normalizeDrPlanState(plan) {
  const effective = upper(plan.effectivestate || plan.effectiveState || plan.state)
  const runtime = upper(plan.runtimestate || plan.runtimeState)
  const runtimeError = plan.runtimeerrorcode || plan.runtimeErrorCode
  if (runtime === 'ERROR' || runtime === 'FAILED' || runtimeError) return 'ERROR'
  return effective || upper(plan.state)
}

function isDrPlanNextStepReady(plan) {
  return normalizeDrPlanState(plan) === 'READY'
    && bool(plan.targetvmpresent || plan.targetVmPresent)
    && bool(plan.restorepointpresent || plan.restorePointPresent)
    && !plan.runtimeerrorcode
}
```

The UI must not infer readiness from `state=SYNCING` or `run.state=ACCEPTED`.

### 4.2 API Layer

Files:

- `GetDrPlanCmd.java`
- `ListDrPlansCmd.java`
- `GetDrRunCmd.java`
- `ListDrRunsCmd.java`
- `ListDrRunStepsCmd.java`
- `ListDrReplicasCmd.java`
- `ListDrRestorePointsCmd.java`
- `DrPlanResponse.java`
- `DrRunResponse.java`
- `DrResponseGenerator.java`

Required behavior:

1. All read APIs that are used by DR list/detail/progress views must call
   `DrProjectionService.refreshPlanProjection(planId, true)` before response
   generation.
2. Projection refresh must be best-effort for read APIs, but stale projection
   must be visible in the response instead of silently returning optimistic DB
   state.
3. Plan and run responses must include:
   - `effectiveState`
   - `runtimeProjectionState`
   - `runtimeProjectionChecked`
   - `runtimeState`
   - `runtimeStep`
   - `runtimeErrorCode`
   - `runtimeErrorMessage`
   - `workerState`
   - `workerExitCode`
   - `targetVmPresent`
   - `targetStoragePresent`
   - `targetNetworkPresent`
   - `restorePointPresent`
   - `sourceDiskMapPath`
   - `targetDiskMapPath`
4. If the backend detects a serving-process mismatch, API response must expose
   a warning such as:

```json
{
  "runtimeProjectionState": "STALE",
  "runtimeErrorCode": "DR_MANAGEMENT_SERVING_PROCESS_STALE",
  "runtimeErrorMessage": "Cloud service MainPID is not the process serving port 8080"
}
```

This response field is for operator visibility. Deployment scripts must still
fail hard before this state reaches production use.

### 4.3 Backend Layer

Files:

- `DrProjectionService.java`
- `DrProjectionServiceImpl.java`
- `FtctlDrRuntimeProjectionAdapter.java`
- `DrRunExecutorImpl.java`
- `DrConstants.java`
- `DrEventVO.java`
- `DrRunVO.java`

Required behavior:

1. `DrProjectionServiceImpl.refreshPlanProjection` must classify projection
   results into four categories:

| Category | Meaning | DB effect |
| --- | --- | --- |
| `fresh-success` | Host runtime read and projected | update plan/run/replica/disk |
| `fresh-terminal` | Host runtime terminal error read | fail plan/run/replica/disk atomically |
| `fresh-pending` | Host runtime read but still working | keep in-progress with latest heartbeat |
| `stale` | Status could not be trusted | keep last state but expose projection stale |

2. `FtctlDrRuntimeProjectionAdapter` must not complete a sync run unless
   `isSyncTargetReady` proves all readiness data:
   - runtime state `READY|TARGET_READY`
   - target reference exists in DB or runtime
   - target VM present
   - target storage present
   - target network present
   - restore point present
   - last target durable timestamp exists
3. `failRunFromProjection` must include disk-map diagnostics in
   `dr_run.last_status_json` and `dr_run_step.details_json`.
4. Add explicit constants:
   - `DR_MANAGEMENT_SERVING_PROCESS_STALE`
   - `DR_TARGET_DISK_MAP_INVALID`
   - `DR_TARGET_DISK_SIZE_UNRESOLVED`
   - `DR_TARGET_DISK_TYPE_INVALID`
   - `DR_TARGET_STORAGE_UNRESOLVED`
5. Add a scheduled reconciler for active DR plans:
   - interval: 15-30 seconds
   - candidates: plans in `SYNCING`, `TESTING`, `FAILED_OVER`, `FAILING_BACK`,
     or latest run in `ACCEPTED|RUNNING`
   - action: call `refreshPlanProjection(planId, true)`
   - do not block UI/API calls

Recommended implementation shape:

```java
public interface DrProjectionService {
    DrPlanVO refreshPlanProjection(long planId, boolean bestEffort);
    DrPlanVO markProjectionStale(long planId, String errorCode, String message, String detailsJson);
    int reconcileActivePlans();
}
```

`markProjectionStale` must not overwrite a known terminal runtime error with an
optimistic in-progress state.

### 4.4 Agent Layer

Files:

- `FtctlDrActionCommand.java`
- `FtctlDrStatusCommand.java`
- `FtctlDrStatusAnswer.java`
- `LibvirtFtctlDrActionCommandWrapper.java`
- `LibvirtFtctlDrStatusCommandWrapper.java`
- `LibvirtFtctlDrCommandHelper.java`

Required behavior:

1. Action command payload must carry a complete profile with separate source and
   target disk metadata. The agent writes it once as `profile.json`; FTCTL
   derives source/target maps from that profile.
2. Status command must always pass the run UUID when available:

```bash
ablestack_vm_ftctl dr-status --plan <planUuid> --run <runUuid> --json
```

3. Status answer must parse and expose the new disk map fields:
   - `source_disk_map_path`
   - `target_disk_map_path`
   - `disk_map_role`
   - `target_disk_count`
   - `target_disk_invalid_count`
4. Agent logs must redact credentials and must not print Mold/vCenter secret
   values from the DR site profile.
5. The status wrapper timeout remains bounded and read-only. It must not obtain
   FTCTL global locks or start workers.

Code-level update:

```java
answer.setSourceDiskMapPath(getString(payload, "source_disk_map_path"));
answer.setTargetDiskMapPath(getString(payload, "target_disk_map_path"));
answer.setDiskMapRole(getString(payload, "disk_map_role"));
answer.setTargetDiskCount(getInteger(payload, "target_disk_count"));
answer.setTargetDiskInvalidCount(getInteger(payload, "target_disk_invalid_count"));
```

### 4.5 FTCTL Layer

Files:

- `bin/ablestack_vm_ftctl.sh`
- `lib/ftctl/dr_runtime.sh`
- `lib/ftctl/dr_vmware.sh`
- `lib/ftctl/dr_ablestack.sh`
- `bin/ablestack_vm_ftctl_selftest.sh`

Required runtime model:

| Artifact | Owner | Meaning |
| --- | --- | --- |
| `profile.json` | Cloud/Agent | Full DR plan contract |
| `vmware-disks.json` | VMware source driver | Source VMDK/CBT/checkpoint metadata |
| `ablestack-disks.json` | ABLESTACK target driver | Target storage/materialization contract |
| `status.state` | FTCTL runtime | latest visible runtime state |
| `runs/<run>.state` | FTCTL runtime | run-specific runtime state |

The state file must no longer use a single ambiguous `disk_map_path` as the only
disk-map field. It must persist:

```text
source_disk_map_path=/run/.../vmware-disks.json
target_disk_map_path=/run/.../ablestack-disks.json
disk_map_role=target
disk_map_path=/run/.../ablestack-disks.json
```

`disk_map_path` may remain for backward compatibility, but for target-provider
ABLESTACK it must point to `target_disk_map_path`.

Required FTCTL function changes:

1. Add helpers in `dr_runtime.sh`:

```bash
ftctl_dr_runtime_source_disk_map_path() {
  printf '%s/vmware-disks.json\n' "$(ftctl_dr_runtime_plan_dir "$1")"
}

ftctl_dr_runtime_target_disk_map_path() {
  local plan="$1" target_provider="$2"
  case "$(printf '%s' "$target_provider" | tr '[:lower:]' '[:upper:]')" in
    ABLESTACK) printf '%s/ablestack-disks.json\n' "$(ftctl_dr_runtime_plan_dir "$plan")" ;;
    VMWARE*) printf '%s/vmware-target-disks.json\n' "$(ftctl_dr_runtime_plan_dir "$plan")" ;;
    *) printf '%s/target-disks.json\n' "$(ftctl_dr_runtime_plan_dir "$plan")" ;;
  esac
}

ftctl_dr_runtime_set_disk_maps() {
  local state_path="$1" source_map="$2" target_map="$3" role="$4"
  ftctl_dr_runtime_path_set "$state_path" \
    "source_disk_map_path=$source_map" \
    "target_disk_map_path=$target_map" \
    "disk_map_role=$role" \
    "disk_map_path=$target_map"
}
```

2. Update `ftctl_dr_vmware_sync_start`:
   - write only source map/checkpoint fields for VMware source mode
   - set `source_disk_map_path`
   - never overwrite target `disk_map_path` for target-provider ABLESTACK

3. Update `ftctl_dr_ablestack_sync_start`:
   - always canonicalize `profile.json` into `ablestack-disks.json`
   - set `target_disk_map_path`
   - set compatibility `disk_map_path` to the ABLESTACK map
   - perform preflight before target preparation

4. Add target disk preflight in `dr_ablestack.sh`:

```bash
ftctl_dr_ablestack_validate_target_disk_map() {
  local disk_map="$1"
  python3 - "$disk_map" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
errors = []
for i, disk in enumerate(data.get("disks") or []):
    target_type = str(disk.get("targetType") or "").lower()
    target_storage_type = str(disk.get("targetStorageType") or "").upper()
    size = int(disk.get("sizeBytes") or 0)
    if target_storage_type == "RBD" and target_type != "rbd":
        errors.append(f"DR_TARGET_DISK_TYPE_INVALID:{i}")
    if size <= 0:
        errors.append(f"DR_TARGET_DISK_SIZE_UNRESOLVED:{i}")
    if not (disk.get("targetPath") or disk.get("targetName")):
        errors.append(f"DR_TARGET_DISK_MAP_INVALID:{i}")
if errors:
    print(",".join(errors))
    sys.exit(32)
PY
}
```

5. VMware source sizes must be resolved from VMware/VDDK metadata, not from
   local file stat on a disk key such as `2000`.

Valid size sources, in priority order:

1. Cloud guided inventory `source.disks[].sizeBytes`
2. VMware inventory `capacityInBytes`
3. VDDK/VixDiskLib metadata
4. Explicit operator override from UI target disk size

If all are missing, fail before accepting the run:

```text
state=ERROR
step=dr-preflight-failed
error_code=DR_TARGET_DISK_SIZE_UNRESOLVED
accepted=false
```

6. `dr-status` output must include:

```json
{
  "source_disk_map_path": ".../vmware-disks.json",
  "target_disk_map_path": ".../ablestack-disks.json",
  "disk_map_role": "target",
  "target_disk_count": 1,
  "target_disk_invalid_count": 0
}
```

### 4.6 DB Layer

Existing schema can support the immediate fix through JSON/status columns, but
the following persistence rules are required:

| Table | Required update |
| --- | --- |
| `dr_plan` | terminal runtime error sets `state=ERROR`, `last_error_code`, `last_error_message` |
| `dr_run` | latest status JSON stores FTCTL runtime with source/target disk map paths |
| `dr_run_step` | add/update `runtime-projection` step for every projection refresh |
| `dr_replica` | sync is not ready until target VM/external ref is present |
| `dr_replica_disk` | details JSON stores target disk type/storage/size/map path |
| `dr_restore_point` | created only after durable target checkpoint exists |

Optional schema hardening:

```sql
ALTER TABLE dr_run
  ADD COLUMN runtime_state varchar(32) NULL,
  ADD COLUMN runtime_error_code varchar(255) NULL,
  ADD COLUMN runtime_updated datetime NULL,
  ADD COLUMN source_disk_map_path varchar(1024) NULL,
  ADD COLUMN target_disk_map_path varchar(1024) NULL;
```

This optional migration is not required for the immediate fix if the existing
JSON response path is used consistently.

## 5. Serving Process Contract

The deployment process must verify that the process serving the active Cloud UI
and API is the process started by `mold.service`.

Required validation after Cloud class/JAR deployment:

```bash
service_pid="$(systemctl show -p MainPID --value mold.service)"
listener_pid="$(ss -ltnp 'sport = :8080' | sed -n 's/.*pid=\([0-9]\+\).*/\1/p' | head -n1)"
test -n "$service_pid" && test "$service_pid" = "$listener_pid"
pgrep -af 'org.apache.cloudstack.ServerDaemon' | wc -l
curl -fsS http://127.0.0.1:8080/client/ >/dev/null
```

If `service_pid != listener_pid`, deployment must fail and the stale process
must be stopped before any DR retest. A successful class copy alone is not a
valid deployment.

Expected deployment guard result:

| Check | PASS condition |
| --- | --- |
| `mold.service` active | `systemctl is-active mold.service` returns `active` |
| Listener ownership | `MainPID == ss(:8080).pid` |
| Duplicate ServerDaemon | no extra active old process owns `8080` |
| API marker | `getDrPlan` response contains latest runtime/effective fields |
| UI marker | active bundle contains DR progress/projection markers |

## 6. End-to-End Readiness Rule

A VMware to ABLESTACK DR plan is PASS for the next step only when all layers
agree:

| Layer | PASS evidence |
| --- | --- |
| UI | effective state `READY`, no runtime error banner |
| API | plan `effectiveState=READY`, latest run `SUCCEEDED` |
| Backend | latest projection check is fresh and not stale |
| Agent | run-aware `dr-status` returned successfully |
| FTCTL | runtime `READY`, `target_vm_present=true`, `restore_point_present=true` |
| DB | target replica, target disk refs, and restore point rows exist |
| Target platform | target VM/materialized volume exists in ABLESTACK or VMware |

The following states are always FAIL for next-step readiness:

- plan `SYNCING` with run `ACCEPTED` and no worker heartbeat
- runtime `ERROR|FAILED`
- `target_vm_present=false`
- no target disk refs
- no restore point
- serving process mismatch

## 7. Implementation Result - 2026-07-07

The implementation follows this contract with the following concrete updates:

- FTCTL now exports `source_disk_map_path`, `target_disk_map_path`,
  `disk_map_role`, `target_disk_count`, and `target_disk_invalid_count` in
  runtime status JSON.
- Cloud agent status answer and the KVM status wrapper parse those fields and
  return them to management.
- Backend projection stores the fields in the existing runtime/details JSON and
  blocks target readiness when runtime explicitly reports
  `target_vm_present=false`, `target_storage_present=false`,
  `target_network_present=false`, or `restore_point_present=false`.
- Plan/run API responses expose the disk-map diagnostics so UI can render the
  same evidence used by the backend readiness decision.
- UI action gating no longer enables test failover or failover from a stored
  `READY`/`SYNCING` label alone. It requires target materialization evidence
  and no runtime failure.
- No new DB table or column is required. The compatibility layer uses the
  existing plan/run detail JSON so this can be deployed as changed Java classes,
  changed resources, and UI static assets.

## 8. Implementation Order

1. Fix deployment/runtime validation so stale Cloud management processes cannot
   serve API/UI after class deployment.
2. Extend FTCTL state fields with source/target disk map paths and target disk
   map role.
3. Make ABLESTACK target map authoritative for ABLESTACK target preparation.
4. Add VMware disk size propagation and fail-fast target disk map preflight.
5. Extend agent status answer with source/target disk map fields.
6. Extend backend projection to persist and expose map diagnostics.
7. Add scheduled active-plan projection reconciliation.
8. Update UI state rendering/action gating to require effective ready evidence.
9. Add regression tests for stale process detection, map selection, target size
   failure, and terminal projection.

## 9. Test Matrix

| Test | Expected result |
| --- | --- |
| Old Java process owns `8080` after deployment | deployment validation fails |
| FTCTL `ERROR` while DB run is `ACCEPTED` | API projects plan/run to failure |
| VMware source disk id `2000` without size | preflight fails with `DR_TARGET_DISK_SIZE_UNRESOLVED` |
| RBD target storage with non-rbd target type | preflight fails with `DR_TARGET_DISK_TYPE_INVALID` |
| Valid VMware to ABLESTACK plan | target map path is `ablestack-disks.json` |
| Target materialization incomplete | plan remains not ready and actions are disabled |
| Target VM/volume/restore point present | plan becomes `READY`, latest run `SUCCEEDED` |
