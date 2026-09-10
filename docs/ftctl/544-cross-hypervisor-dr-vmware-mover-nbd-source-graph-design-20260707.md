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

# Cross Hypervisor DR VMware Mover NBD Source Graph Design

Date: 2026-07-07

## 1. Purpose

This document defines the structural fix for the VMware to ABLESTACK DR sync
failure observed with plan `987bb250-3b5a-4053-9720-2ff93b4cc88c` and run
`296b916d-adad-49e3-95de-4581c9e55c51`.

The previous fixes worked up to the data mover boundary:

- Cloud created a canonical `VMWARE_TO_KVM` plan.
- API and backend accepted the sync asynchronously.
- Agent dispatched the action to the selected worker host.
- FTCTL resolved VDDK libdir and started the VMware contract path.
- FTCTL prepared the target RBD image `rbd/Rokcy10-1-dr-disk-0`.

The remaining failure is the source image graph passed from the VMware mover to
`qemu-img`:

```text
VMware DR mover: [3...local-disk] test1/test1.vmdk -> rbd:rbd/Rokcy10-1-dr-disk-0
qemu-img: Could not open 'json:{"driver":"nbd","server":{"type":"unix","path":"/tmp/.../vddk.sock"}}':
  A block device must be specified for "file"
ERROR: qemu-img conversion failed for disk0
```

This is not a Cloud UI, DB schema, credential, VDDK libdir, or nbdkit plugin
issue. It is a mover-side QEMU block graph contract issue. The mover exposed an
NBD protocol node directly while also forcing `-f raw`; QEMU's raw driver needs
an explicit `file` child block device.

## 2. Affected Layers

| Layer | Change required | Reason |
| --- | --- | --- |
| UI | Small mapping update only | surface the more specific mover source graph error when API returns it |
| API | Response/error contract update only | include the new terminal error code in plan/run responses |
| Backend | Projection/error mapping update only | terminal runtime status must persist the new error without new schema |
| Agent | No new command; pass-through check | wrappers must preserve final FTCTL JSON and not hide mover stderr |
| FTCTL | Primary implementation | build a valid raw-over-NBD source graph for `qemu-img` |
| DB | No schema change | existing `dr_plan`, `dr_run`, `dr_run_step`, `dr_event` fields carry the state |

## 3. Target Behavior

The VMware mover must represent a VDDK-backed nbdkit socket as a raw image whose
file child is the NBD node.

Required source graph:

```text
raw
  file -> nbd
            server.type = unix
            server.path = <private vddk.sock>
```

The mover must not call `qemu-img convert` with the current direct NBD JSON:

```bash
qemu-img convert -p -n -f raw \
  'json:{"driver":"nbd","server":{"type":"unix","path":"<sock>"}}' \
  rbd:<pool>/<image>
```

Instead it must use one of the following equivalent explicit forms. The
preferred form is `--image-opts` because it is compact, testable, and avoids
nesting JSON in a shell string:

```bash
source_opts="driver=raw,file.driver=nbd,file.server.type=unix,file.server.path=${socket_path}"

qemu-img info --force-share --image-opts "${source_opts}"

qemu-img convert --force-share -p -n --image-opts \
  -O "${target_format:-raw}" \
  "${source_opts}" \
  "${target_uri}"
```

A JSON equivalent is acceptable only if it includes the raw wrapper:

```json
{
  "driver": "raw",
  "file": {
    "driver": "nbd",
    "server": {
      "type": "unix",
      "path": "/tmp/ftctl.vmware.mover.<id>/vddk.sock"
    }
  }
}
```

## 4. FTCTL Code-level Design

### 4.1 Files

- `lib/ftctl/dr_vmware_mover.sh`
- `lib/ftctl/dr_runtime.sh`
- `lib/ftctl/dr_scheduler.sh`
- `lib/ftctl/dr_vmware.sh`
- `bin/ablestack_vm_ftctl_selftest.sh`

### 4.2 Mover source graph helper

Add a helper near the existing target URI helper:

```bash
ftctl_vmware_mover_qemu_opt_escape() {
  local value="${1-}"
  value="${value//\\/\\\\}"
  value="${value//,/\\,}"
  printf '%s' "${value}"
}

ftctl_vmware_mover_source_image_opts() {
  local socket_path="${1-}"
  [[ -n "${socket_path}" ]] || ftctl_vmware_mover_die 72 \
    "DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID: nbd socket path is empty"
  printf 'driver=raw,file.driver=nbd,file.server.type=unix,file.server.path=%s\n' \
    "$(ftctl_vmware_mover_qemu_opt_escape "${socket_path}")"
}
```

The helper is source-only. Target handling remains the existing
`ftctl_vmware_mover_target_uri()` path so RBD targets continue to use
`rbd:<pool>/<image>`.

### 4.3 Source graph preflight

Before data copy, run a bounded source-graph preflight while nbdkit is alive:

```bash
source_opts="$(ftctl_vmware_mover_source_image_opts "${socket_path}")"

if ! timeout "${FTCTL_DR_VMWARE_QEMU_INFO_TIMEOUT:-20}" \
    qemu-img info --force-share --image-opts "${source_opts}" >/dev/null; then
  kill "${pid}" 2>/dev/null || true
  wait "${pid}" 2>/dev/null || true
  rm -rf "${work_dir}"
  ftctl_vmware_mover_die 72 \
    "DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID: qemu-img cannot open VDDK NBD source for ${label}"
fi
```

This creates a specific terminal error before the longer convert step.

### 4.4 Convert command

Replace the current convert command:

```bash
nbd_source="json:{\"driver\":\"nbd\",\"server\":{\"type\":\"unix\",\"path\":\"${socket_path}\"}}"
if ! qemu-img convert -p -n -f raw -O "${target_format:-raw}" "${nbd_source}" "${target_uri}"; then
```

with:

```bash
if ! qemu-img convert --force-share -p -n --image-opts \
    -O "${target_format:-raw}" \
    "${source_opts}" \
    "${target_uri}"; then
  kill "${pid}" 2>/dev/null || true
  wait "${pid}" 2>/dev/null || true
  rm -rf "${work_dir}"
  ftctl_vmware_mover_die 68 "qemu-img conversion failed for ${label}"
fi
```

Important rules:

- Do not pass `-f raw` together with `--image-opts`.
- Keep `-n` because the target RBD image is pre-created by the ABLESTACK target
  preparation step.
- Keep `-O raw` for RBD/raw block targets.
- Keep target URI handling independent from source graph handling.
- Log the source path and target URI, but do not log credentials or password
  files.

### 4.5 Cleanup guard

The mover currently repeats cleanup in multiple branches. Convert it to a trap
for safer failure handling:

```bash
ftctl_vmware_mover_cleanup() {
  [[ -n "${pid:-}" ]] && kill "${pid}" 2>/dev/null || true
  [[ -n "${pid:-}" ]] && wait "${pid}" 2>/dev/null || true
  [[ -n "${work_dir:-}" ]] && rm -rf "${work_dir}"
}
```

If a shell-level trap is too broad for the current script style, keep local
cleanup but make sure the source-graph preflight failure follows the same
cleanup sequence as nbdkit startup and convert failure.

### 4.6 Error mapping

Add a new mover source graph error:

| Exit | Error code | Meaning |
| --- | --- | --- |
| 72 | `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` | nbdkit socket exists, but QEMU cannot open the raw-over-NBD source graph |

Update exit-code mapping in:

- `lib/ftctl/dr_runtime.sh`
- `lib/ftctl/dr_scheduler.sh`

The existing exit 68 remains `DR_VMWARE_MOVER_FAILED` for actual convert/data
copy failures after source graph preflight succeeds.

## 5. Agent Design

No new Agent command is required.

Affected classes:

- `LibvirtFtctlDrActionCommandWrapper`
- `LibvirtFtctlDrStatusCommandWrapper`
- `LibvirtFtctlWrapperHelper`

Rules:

- Preserve stdout/stderr lines that include `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID`.
- Continue selecting the final FTCTL JSON object when command output includes
  human-readable mover logs before JSON.
- Do not convert FTCTL terminal runtime errors into Agent transport failures
  when the status command itself succeeds.

Agent answers should carry the final status JSON so backend projection can
persist:

```json
{
  "state": "ERROR",
  "error_code": "DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID",
  "worker_exit_code": 72,
  "driver_state": "TARGET_PREPARED"
}
```

## 6. Backend Design

### 6.1 Files

- `FtctlDrRuntimeProjectionAdapter.java`
- `DrProtectionOrchestratorImpl.java`
- `DrPlanReadinessValidator.java`
- `DrResponseGenerator.java`
- `DrRunServiceImpl.java`
- DR event writer used by runtime projection

### 6.2 Projection

`FtctlDrRuntimeProjectionAdapter` must treat the new error as terminal:

```java
private boolean isTerminalRuntimeError(String errorCode) {
    return Set.of(
        "DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID",
        "DR_VMWARE_MOVER_FAILED",
        "DR_VMWARE_NBDKIT_FAILED",
        "DR_VDDK_LIBDIR_UNRESOLVED",
        "DR_VDDK_LIBRARY_LOAD_FAILED"
    ).contains(errorCode);
}
```

On terminal projection:

```java
run.setState(DrConstants.RUN_STATE_FAILED);
run.setProjectionState("failed");
run.setCompleted(now);
run.setCurrentStepName("runtime-projection");
run.setErrorCode(runtime.errorCode());
run.setErrorMessage(runtime.errorMessageOrDefault());
run.setLastStatusJson(runtime.rawJson());

plan.setState(DrConstants.PLAN_STATE_ERROR);
plan.setLastErrorCode(runtime.errorCode());
plan.setLastErrorMessage(runtime.errorMessageOrDefault());
```

The backend must not mark the plan ready when only `target_storage_present=true`.
For this failure, `target_vm_present=false`, `target_network_present=false`, and
`restore_point_present=false`, so the run remains failed.

### 6.3 Readiness

This source graph error is not a plan preview validation error. It can only be
confirmed after nbdkit starts and QEMU probes the socket. Therefore:

- Do not block plan creation only because source graph preflight has not run.
- Do block next actions after the run fails.
- Keep `startDrPlanSync` asynchronous.
- Require cleanup or a new successful sync before failover/test failover is
  enabled.

## 7. API Design

Affected response classes:

- `DrPlanResponse`
- `DrRunResponse`
- `DrRunStepResponse`
- `DrEventResponse`

No new API command or DB schema is required.

Response contract:

```json
{
  "state": "ERROR",
  "effectivestate": "ERROR",
  "lasterrorcode": "DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID",
  "lastrun": {
    "state": "FAILED",
    "errorcode": "DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID",
    "runtimestate": "ERROR"
  }
}
```

`getDrPlan`, `listDrPlans`, `listDrRuns`, and `listDrRunSteps` must return the
same terminal state after projection. They must not show stale `ACCEPTED` after
FTCTL status has terminal failure data.

## 8. UI Design

Affected files:

- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/views/infra/dr/DrPlanOverview.vue`
- `ui/src/components/dr/DrRunProgress.vue`
- `ui/src/components/dr/DrStatusPill.vue`
- `ui/src/utils/dr/errors.js` or the local error mapping helper
- `ui/public/locales/en.json`
- `ui/public/locales/ko_KR.json`

Add the error label:

| Code | User message intent |
| --- | --- |
| `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` | VMware source disk connection was opened by nbdkit, but QEMU could not build a valid raw-over-NBD source graph. Retry after mover update/deployment. |

UI behavior:

- Plan list shows `ERROR`.
- Detail page latest run shows `FAILED`.
- Run progress panel shows the specific source graph failure instead of a
  generic "mover failed" message when the code is available.
- Failover/test failover actions remain disabled.
- The message should not expose socket paths or credentials by default; detailed
  diagnostics can show the run id and error code.

## 9. DB Design

No schema migration is required.

Use existing fields:

| Table | Field usage |
| --- | --- |
| `dr_plan` | `state=ERROR`, `last_error_code=DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID`, `last_error_message` |
| `dr_run` | `state=FAILED`, `projection_state=failed`, `worker_exit_code` inside `last_status_json` |
| `dr_run_step` | `runtime-projection` failed step |
| `dr_event` | terminal projection event with sanitized mover diagnostics |
| `dr_replica` / `dr_replica_disk` | remain pending/error; do not mark target ready |

The RBD image created before failure is runtime residue. Cleanup is operational,
not a DB schema concern. A later cleanup implementation may add a targeted
runtime cleanup action, but that is outside this source graph fix.

## 10. Test Design

### 10.1 FTCTL selftests

Add or update selftests in `bin/ablestack_vm_ftctl_selftest.sh`:

| Test | Assertion |
| --- | --- |
| `dr-vmware-mover-source-image-opts` | mover calls `qemu-img convert --image-opts` with `driver=raw,file.driver=nbd` |
| `dr-vmware-mover-no-direct-nbd-json` | mover no longer calls direct `json:{"driver":"nbd"...}` with `-f raw` |
| `dr-vmware-mover-source-graph-preflight-fail` | failed `qemu-img info --image-opts` maps to exit 72 |
| `dr-vmware-mover-source-graph-error-code` | scheduler/runtime map exit 72 to `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` |
| `dr-vmware-mover-rbd-target-preserved` | RBD target URI remains `rbd:<pool>/<image>` and convert keeps `-n` |

### 10.2 Backend/API tests

| Component | Test |
| --- | --- |
| Runtime projection | status JSON with error code 72 fails run and plan |
| API response | `getDrPlan` returns `effectivestate=ERROR` and latest run failed |
| UI error mapper | new code maps to a specific message |

### 10.3 Live retest PASS criteria

The next VMware to ABLESTACK sync can be marked PASS only when all are true:

1. `dr_run.state=SUCCEEDED`.
2. `dr_plan.state=READY` or equivalent target-ready.
3. FTCTL status has no `DR_VMWARE_*` terminal error.
4. `last_target_durable_at` is set.
5. target VM/volume references exist in Cloud DB or the design explicitly keeps
   them pending for a later materialization phase.
6. UI enables next actions only after API/DB/runtime agree.

## 11. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| qemu-img source | direct NBD JSON is passed while forcing `-f raw` | raw-over-NBD source graph is explicit through `--image-opts` |
| Error specificity | invalid source graph collapses into `DR_VMWARE_MOVER_FAILED` | `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` identifies graph/probe failure |
| Convert safety | graph validity is discovered during convert | bounded `qemu-img info --image-opts` preflight runs before convert |
| FTCTL cleanup | repeated branch cleanup can miss new failure branches | source graph preflight follows the same nbdkit cleanup path |
| Backend projection | generic mover failure is terminal | specific source graph failure is also terminal and persisted |
| API/UI | generic failed state only | specific code/message is visible without exposing secrets |
| DB | no new schema | existing plan/run/step/event fields carry the state |

## 12. Follow-up: VDDK Connect Contract

The raw-over-NBD graph fix moves the VMware mover past the original QEMU graph
syntax problem. A later run for plan
`71182935-11c6-4ed3-aeec-ebde1486bdfa` failed with:

```text
VixDiskLib_ConnectEx: One of the parameters was invalid
```

That follow-up is not another raw-over-NBD graph issue. It means nbdkit/VDDK
received an incomplete or invalid VMware source connection contract, such as a
missing source object, source VM MoRef, vCenter/source endpoint identity, source
disk path, or credential field.

The follow-up design is:

```text
545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md
```

Implementation must keep the distinction:

| Failure family | Error code |
| --- | --- |
| QEMU cannot build/open the raw-over-NBD source graph | `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` |
| VDDK rejects source connection parameters | `DR_VMWARE_VDDK_CONNECT_INVALID` |
| VDDK NBD export is unavailable after the socket path is built | `DR_VMWARE_VDDK_EXPORT_UNAVAILABLE` |
| VDDK cannot open a powered-on disk because no run snapshot is used | `DR_VMWARE_VDDK_SOURCE_LOCKED` |
| VDDK rejects the requested VMDK path or current delta path | `DR_VMWARE_VDDK_OPEN_DENIED` |

## 13. Follow-up: Deterministic NBD Drain And Cloud Projection

Live RPO-cycle evidence on 2026-07-23 showed that a valid source graph and a
successful incremental data patch can still leave a short disconnect race with
udev/partition reads. The full cross-layer correction is:

```text
569-cross-hypervisor-dr-nbd-deterministic-drain-and-cycle-observability-design-20260723.md
```

Cloud must not accept `COMPLETED` or `incrementalVerified=true` for a cycle
whose FTCTL NBD teardown state is not `DRAINED`. Raw NBD device paths remain
host-local; Agent/API/UI receive only typed aggregate teardown state and
sanitized errors.
