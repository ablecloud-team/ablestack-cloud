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

# Cross Hypervisor DR VDDK Libdir Resolution And Preflight Design

Date: 2026-07-07

## 1. Purpose

This document defines the structural improvement for the VMware to ABLESTACK
DR sync failure observed with plan
`8037c34e-5a50-4f4c-bc4e-16dfd54f00d1`.

The target ABLESTACK disk path was already fixed. FTCTL created the expected
RBD image and generated a correct target disk contract:

```text
targetPath=/dev/rbd/rbd/Rokcy10-1-dr-disk-0
targetType=rbd
```

The remaining failure is the VMware source data mover startup:

```text
error_code=DR_VMWARE_NBDKIT_FAILED
worker_exit_code=69
driver_state=TARGET_PREPARED
nbdkit: error: /usr/lib64/vmware-vix-disklib/lib64/libvixDiskLib.so.9:
  cannot open shared object file
ERROR: nbdkit vddk socket did not become ready for disk0
```

Host verification showed usable ABLESTACK-bundled VDDK directories:

```text
/usr/share/ablestack/v2k/compat/vsphere80/vddk
/usr/share/ablestack/v2k/compat/vsphere67/vddk
/usr/share/ablestack/v2k/compat/vsphere60/vddk
```

and `nbdkit --dump-plugin vddk libdir=/usr/share/ablestack/v2k/compat/vsphere80/vddk`
works. The failure therefore is not a missing VDDK asset. It is a missing
runtime contract and missing fallback resolver:

- Cloud does not pass an effective `vddkLibdir` in the FTCTL profile
  credential payload.
- Agent does not enrich the profile with the host-detected VDDK libdir.
- FTCTL does not auto-discover the ABLESTACK compat VDDK path when
  `credentials.source.vddkLibdir` is empty.
- Readiness currently allows sync to reach mover failure instead of blocking
  before target preparation.

## 2. Design Principles

- VDDK libdir is a worker-host data-plane capability, not a normal site
  credential field.
- The ordinary UI should not force users to type a VDDK path. It may show
  the detected path and expose an advanced override only for operations.
- Cloud API/backend must use bounded preflight and cached capability state;
  UI/API must not run long sync work synchronously.
- FTCTL remains the DR engine. It may reuse the installed VDDK asset layout
  that v2k installs, but it must not call v2k as the DR data mover.
- FTCTL is the final safety layer. Even if Cloud/Agent fail to pass
  `vddkLibdir`, FTCTL must try deterministic host-local discovery before
  starting `nbdkit`.
- Terminal failure must be expressed with specific codes:
  `DR_VDDK_LIBDIR_UNRESOLVED`, `DR_VDDK_LIBRARY_LOAD_FAILED`, or
  `DR_VMWARE_NBDKIT_FAILED`, not only a generic sync failure.

## 3. Source-Level Current State

| Area | Current code | Gap |
| --- | --- | --- |
| Cloud credential runtime | `DrResolvedSiteCredential.toRuntimeJson()` returns type, endpoint, principal, tlsVerify, auth | no `vddkLibdir` runtime hint |
| Cloud FTCTL profile | `FtctlDrUnifiedActionAdapter.buildProfileJson()` adds `credentials.source` | no worker host VDDK capability merged into credential |
| Cloud readiness | `DrPlanReadinessValidator.validateForExecution()` checks workers, target placement, disk mapping and disk size | no VMware source data-plane gate |
| Agent host detection | `LibvirtComputingResource.detectVddkLibDir()` searches only `vmware-vix-disklib-distrib` | misses `/usr/share/ablestack/v2k/compat/*/vddk` |
| Agent ready details | `LibvirtReadyCommandWrapper` publishes `Host.HOST_VDDK_LIB_DIR` and `Host.HOST_VDDK_VERSION` | useful but not consumed by DR profile/readiness |
| FTCTL capability | `ftctl_dr_vmware_nbdkit_vddk_available()` accepts plugin availability or help output | can report VDDK ready while the actual library cannot load |
| FTCTL mover | `dr_vmware_mover.sh` reads `.credentials.source.vddkLibdir // .credentials.source.libdir` | if blank, nbdkit uses default `/usr/lib64/vmware-vix-disklib` and fails |
| FTCTL exit mapping | `dr_runtime.sh` / `dr_scheduler.sh` map exit 69 to `DR_VMWARE_NBDKIT_FAILED` | no specific unresolved-libdir or library-load code |
| DB | `dr_site.capabilities_json`, `dr_site_health_check.details_json`, `dr_run.last_status_json` exist | VDDK data-plane capability is not normalized into those JSON fields |

## 4. UI Design

### 4.1 Files

- `ui/src/views/infra/dr/DrSiteList.vue`
- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/views/infra/dr/DrPlanOverview.vue`
- `ui/src/components/dr/DrRunProgress.vue`
- `ui/src/components/dr/DrStatusPill.vue`
- `ui/public/locales/en.json`
- `ui/public/locales/ko_KR.json`

### 4.2 DR Site UI

For VMware Direct sites, show the site health and data-plane readiness as
separate concepts:

| Field | Meaning |
| --- | --- |
| Site connection | vCenter endpoint and credential validation result |
| VMware data mover | whether the selected worker can run nbdkit/VDDK/qemu-img |
| VDDK library path | detected worker path, shown only in details/diagnostics |

The create/edit dialog should not require `vddkLibdir`. If an override is
needed, it belongs in an advanced operator-only field:

```js
form.advanced.vddkLibdirOverride
```

UI payload:

```js
{
  vddkLibdir: form.advanced.vddkLibdirOverride || undefined
}
```

The label must make the scope clear:

```text
VDDK 라이브러리 경로 override
```

Help text:

```text
일반적으로 자동 탐색됩니다. 선택한 DR 워커 호스트에서 기본 탐색이 실패할 때만 입력합니다.
```

### 4.3 DR Plan UI

For VMware source directions, the plan dialog and detail page must surface
readiness from API instead of waiting for a runtime mover failure.

Blocking reasons:

| Reason | UI message |
| --- | --- |
| `DR_VDDK_LIBDIR_UNRESOLVED` | 선택한 워커 호스트에서 VDDK 라이브러리 경로를 찾을 수 없습니다. |
| `DR_VDDK_LIBRARY_LOAD_FAILED` | VDDK 라이브러리가 있지만 nbdkit-vddk가 로드하지 못했습니다. |
| `DR_VMWARE_NBDKIT_FAILED` | nbdkit VDDK 세션 시작에 실패했습니다. |
| `DR_VMWARE_MOVER_UNAVAILABLE` | VMware DR 데이터 mover가 설치되어 있지 않습니다. |

The detail page should show these fields in a diagnostic area:

```js
plan.capabilities?.vmwareDataPlane?.vddkLibdir
plan.capabilities?.vmwareDataPlane?.vddkLibraryVersion
plan.capabilities?.vmwareDataPlane?.nbdkitVddkReady
plan.capabilities?.vmwareDataPlane?.moverReady
```

The sync button remains asynchronous. The UI only calls `startDrPlanSync` and
then polls plan/run/progress APIs.

## 5. API Design

### 5.1 Files

- `CreateDrSiteCmd.java`
- `UpdateDrSiteCmd.java`
- `CheckDrSiteCmd.java`
- `PreviewDrPlanSpecCmd.java`
- `CreateDrPlanCmd.java`
- `UpdateDrPlanCmd.java`
- `DrSiteResponse.java`
- `DrPlanResponse.java`
- `DrPlanSpecPreviewResponse.java`
- `ui/src/api/dr.js`

### 5.2 Site Parameter

No new normal UI/API parameter is required for this implementation. VDDK
libdir is resolved from worker-host capability and ftctl host-local fallback,
not from a DR Site credential field.

An optional operator-only override may be added later if field operations need
it. If added, it must be stored as non-secret site capability, validated as an
absolute Unix path, and rejected for ordinary non-VMware flows.

### 5.3 Response Contract

The immediate implementation returns VMware data-plane readiness through the
existing plan readiness fields and runtime status projection:

- `readinessstate`
- `readinessreasoncode`
- `readinessmessage`
- `readinessblockingreasons`
- latest run `errorcode`

Future site-level diagnostics may include the following under
`DrSiteResponse.capabilities` and health-check details:

```json
{
  "healthCheck": {
    "state": "CONNECTED",
    "reasonCode": "VCENTER_API_OK"
  },
  "vmwareDataPlane": {
    "state": "READY",
    "hostId": 1,
    "hostUuid": "host-uuid",
    "moverReady": true,
    "moverPath": "/usr/local/lib/ablestack-qemu-exec-tools/ftctl/dr_vmware_mover.sh",
    "nbdkitAvailable": true,
    "nbdkitVddkReady": true,
    "qemuImgAvailable": true,
    "vddkLibdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk",
    "vddkLibraryVersion": "8",
    "checkedAt": "2026-07-07T21:45:00+0900",
    "reasonCode": null,
    "message": "VMware data-plane is ready"
  }
}
```

Plan preview/readiness already carries the blocking reason codes required by UI.

### 5.4 Start-Sync Guard

`createDrPlan(startsync=true)`, `updateDrPlan(startsync=true)`, and
`startDrPlanSync` must reject execution when VMware source data-plane
readiness is blocking:

```java
DrPlanReadiness readiness = drPlanReadinessValidator.validateForExecution(plan);
if (!readiness.isExecutionReady()) {
    throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
            readiness.getMessage());
}
```

This is a short pre-dispatch guard. It must not perform the actual sync copy.

## 6. Backend Design

### 6.1 Files

- `DrSiteCredentialInput.java`
- `DrSiteServiceImpl.java`
- `DrSiteHealthCheckServiceImpl.java`
- `DrVmwareDirectSiteProbe.java`
- `DrPlanReadinessValidator.java`
- `FtctlDrUnifiedActionAdapter.java`
- `DrResponseGenerator.java`

### 6.2 Data-Plane Resolution

The implemented backend keeps data-plane resolution local to the two existing
decision points instead of adding new service classes:

- `DrPlanReadinessValidator` resolves the VMware source data-plane host and
  blocks execution if host VDDK details are absent or not loadable.
- `FtctlDrUnifiedActionAdapter` resolves the same host and merges non-secret
  runtime hints into `credentials.source`.

Resolution order:

1. Host detail `Host.HOST_VDDK_LIB_DIR` for the selected worker host.
2. Empty runtime hint, allowing FTCTL host-local auto-discovery as final
   safety.
3. Future operator override, if implemented, in non-secret site capability JSON.

For execution readiness, do not trust an empty hint as ready. The selected
worker must be known ready by either:

- host details: `Host.HOST_VDDK_SUPPORT=true`, non-empty
  `Host.HOST_VDDK_LIB_DIR`, and non-empty `Host.HOST_VDDK_VERSION`; or
- bounded Agent/FTCTL preflight result persisted into site/plan capability JSON.

Host selection:

```java
Long vmwareDataPlaneHostId(DrPlanVO plan) {
    if (StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_VMWARE_TO_KVM)) {
        return firstNonNull(plan.getTargetWorkerHostId(), plan.getCoordinatorWorkerHostId());
    }
    return firstNonNull(plan.getCoordinatorWorkerHostId(), plan.getTargetWorkerHostId(), plan.getSourceWorkerHostId());
}
```

### 6.3 Credential Runtime JSON

`DrResolvedSiteCredential.toRuntimeJson()` should remain secret-safe, but it
must allow non-secret runtime hints to be merged:

```java
JsonObject runtime = credential.toRuntimeJson();
String vddkLibdir = hostDetailValue(dataPlaneHostId, Host.HOST_VDDK_LIB_DIR);
if (StringUtils.isNotBlank(vddkLibdir)) {
    runtime.addProperty("vddkLibdir", vddkLibdir);
}
runtime.addProperty("dataPlaneHostId", dataPlaneHostId);
runtime.addProperty("dataPlaneHostUuid", resolveHostUuid(dataPlaneHostId));
```

`FtctlDrUnifiedActionAdapter.addCredential()` should receive `plan` or a
precomputed resolver result so that the source VMware credential is enriched
before the profile is passed to the agent:

```java
private void addCredential(JsonObject credentials, String key, DrSiteVO site,
        DrPlanVO plan, boolean source) {
    DrResolvedSiteCredential credential = drSiteCredentialService.resolveCredential(site);
    JsonObject runtime = credential.toRuntimeJson();
    if (source && StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_")) {
        drVmwareDataPlaneResolver.enrichRuntimeCredential(runtime, plan);
    }
    credentials.add(key, runtime);
}
```

### 6.4 Readiness Validator

Add a VMware source data-plane gate:

```java
public static final String REASON_VDDK_LIBDIR_UNRESOLVED = "DR_VDDK_LIBDIR_UNRESOLVED";
public static final String REASON_VDDK_LIBRARY_LOAD_FAILED = "DR_VDDK_LIBRARY_LOAD_FAILED";
public static final String REASON_VMWARE_MOVER_UNAVAILABLE = "DR_VMWARE_MOVER_UNAVAILABLE";

private void validateVmwareSourceDataPlane(DrPlanVO plan, DrPlanReadiness readiness) {
    if (!StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_")) {
        return;
    }
    DrVmwareDataPlaneResolution resolution = drVmwareDataPlaneResolver.resolve(plan);
    if (!resolution.isMoverReady()) {
        readiness.addBlockingReason(REASON_VMWARE_MOVER_UNAVAILABLE);
    }
    if (!resolution.isVddkLibdirResolved()) {
        readiness.addBlockingReason(REASON_VDDK_LIBDIR_UNRESOLVED);
    }
    if (resolution.isVddkLibdirResolved() && !resolution.isVddkLoadable()) {
        readiness.addBlockingReason(REASON_VDDK_LIBRARY_LOAD_FAILED);
    }
    readiness.addCapability("vmwareDataPlane", resolution.toJson());
}
```

This gate should run after worker validation and before disk/target
materialization starts.

### 6.5 Health Check

`DrVmwareDirectSiteProbe` should continue to validate vCenter endpoint and
credential. It should not SSH to arbitrary hosts or start long copy jobs.

`DrSiteHealthCheckServiceImpl` should optionally attach the most recent worker
data-plane capability when a preferred worker host is known. If no worker is
known at site-check time, health state can be `CONNECTED` while
`vmwareDataPlane.state=UNKNOWN`.

Plan readiness is the authoritative place for worker-specific data-plane
blocking.

## 7. Agent Design

### 7.1 Files

- `LibvirtComputingResource.java`
- `LibvirtReadyCommandWrapper.java`
- `LibvirtFtctlDrActionCommandWrapper.java`
- `LibvirtFtctlDrStatusCommandWrapper.java`
- `LibvirtFtctlWrapperHelper.java`
- `FtctlDrStatusAnswer.java`
- optional new `FtctlDrPreflightCommand.java`

### 7.2 VDDK Auto-Detection

Extend `LibvirtComputingResource.VDDK_AUTODETECT_PATH_CMD`.

Current search:

```java
find / -type d -name 'vmware-vix-disklib-distrib' 2>/dev/null | head -n 1
```

Target search:

```bash
if [ -n "${VDDK_LIBDIR:-}" ]; then echo "$VDDK_LIBDIR"; exit 0; fi
if [ -f /etc/profile.d/v2k-vddk.sh ]; then . /etc/profile.d/v2k-vddk.sh >/dev/null 2>&1; fi
if [ -n "${VDDK_LIBDIR:-}" ]; then echo "$VDDK_LIBDIR"; exit 0; fi
for d in \
  /opt/vmware-vix-disklib-distrib \
  /usr/share/ablestack/v2k/compat/vsphere80/vddk \
  /usr/share/ablestack/v2k/compat/vsphere67/vddk \
  /usr/share/ablestack/v2k/compat/vsphere60/vddk; do
  [ -d "$d/lib64" ] && ls "$d"/lib64/libvixDiskLib.so* >/dev/null 2>&1 && echo "$d" && exit 0
done
find /usr/share/ablestack/v2k/compat -maxdepth 3 -type d -name vddk 2>/dev/null | head -n 1
```

`hostSupportsVddk()` should verify not only files, but also nbdkit loadability:

```java
boolean loadable = Script.runSimpleBashScriptForExitValue(
        "nbdkit --dump-plugin vddk libdir=" + BashQuote.safe(effectiveVddkLibDir) + " >/dev/null 2>&1") == 0;
```

### 7.3 Action Profile Enrichment

The agent is the last Cloud-side component that knows the actual host runtime.
Before writing the profile JSON, `LibvirtFtctlDrActionCommandWrapper` should
enrich missing VMware source VDDK hints:

```java
JsonObject profile = LibvirtFtctlWrapperHelper.parseJsonObject(command.getProfileJson());
JsonObject sourceCredential = objectAt(objectAt(profile, "credentials"), "source");
if (isVmwareSource(profile) && StringUtils.isBlank(getString(sourceCredential, "vddkLibdir"))) {
    String hostVddkLibdir = serverResource.getVddkLibDir();
    if (StringUtils.isNotBlank(hostVddkLibdir)) {
        sourceCredential.addProperty("vddkLibdir", hostVddkLibdir);
    }
}
profileFile = LibvirtFtctlDrCommandHelper.writeProfileJson(command.getPlanUuid(), GSON.toJson(profile));
```

This does not replace backend readiness. It prevents stale Cloud host details
from causing a runtime miss when the agent itself can detect the path.

### 7.4 Optional Preflight Command

Add a bounded `FtctlDrPreflightCommand` only if backend readiness needs an
agent-confirmed capability refresh before sync:

```java
public class FtctlDrPreflightCommand extends Command {
    private String planUuid;
    private String profileJson;
    private String probeType; // VMWARE_DATA_PLANE
}
```

The wrapper should call:

```bash
ablestack_vm_ftctl dr-preflight --profile-json <file> --type vmware-data-plane --json
```

Timeout must stay short, for example 10 seconds.

## 8. FTCTL Design

### 8.1 Files

- `lib/ftctl/dr_vmware.sh`
- `lib/ftctl/dr_vmware_mover.sh`
- `lib/ftctl/dr_runtime.sh`
- `lib/ftctl/dr_scheduler.sh`
- new `lib/ftctl/dr_vddk.sh`
- `bin/ablestack_vm_ftctl.sh`
- `bin/ablestack_vm_ftctl_selftest.sh`

### 8.2 Reusable Resolver

Add `lib/ftctl/dr_vddk.sh`:

```bash
ftctl_dr_vddk_candidate_dirs() {
  local credentials_file="${1:-}"
  ftctl_dr_vddk_json_value "${credentials_file}" '.credentials.source.vddkLibdir // .credentials.source.libdir' ''
  printf '%s\n' "${FTCTL_DR_VMWARE_VDDK_LIBDIR:-}"
  printf '%s\n' "${VDDK_LIBDIR:-}"
  if [[ -f /etc/profile.d/v2k-vddk.sh ]]; then
    ( . /etc/profile.d/v2k-vddk.sh >/dev/null 2>&1; printf '%s\n' "${VDDK_LIBDIR:-}" )
  fi
  printf '%s\n' /opt/vmware-vix-disklib-distrib
  printf '%s\n' /usr/share/ablestack/v2k/compat/vsphere80/vddk
  printf '%s\n' /usr/share/ablestack/v2k/compat/vsphere67/vddk
  printf '%s\n' /usr/share/ablestack/v2k/compat/vsphere60/vddk
}

ftctl_dr_vddk_validate_libdir() {
  local dir="${1:-}"
  [[ -n "${dir}" && -d "${dir}/lib64" ]] || return 1
  compgen -G "${dir}/lib64/libvixDiskLib.so*" >/dev/null || return 1
  nbdkit --dump-plugin vddk "libdir=${dir}" >/dev/null 2>&1 || return 1
}

ftctl_dr_vddk_resolve_libdir() {
  local credentials_file="${1:-}" candidate seen=""
  while IFS= read -r candidate; do
    candidate="$(printf '%s' "${candidate}" | xargs 2>/dev/null || true)"
    [[ -n "${candidate}" ]] || continue
    [[ "${seen}" == *"|${candidate}|"* ]] && continue
    seen="${seen}|${candidate}|"
    if ftctl_dr_vddk_validate_libdir "${candidate}"; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done < <(ftctl_dr_vddk_candidate_dirs "${credentials_file}")
  return 1
}
```

The resolver checks product-installed VDDK assets directly. It does not call
v2k.

### 8.3 Capability Probe

`ftctl_dr_vmware_write_capability()` must stop treating plugin help output as
sufficient. It should resolve and validate the actual libdir:

```bash
vddk_libdir="$(ftctl_dr_vddk_resolve_libdir "${credentials_file:-}" || true)"
if [[ -n "${vddk_libdir}" ]]; then
  nbdkit_vddk="1"
  vddk_ready="1"
else
  missing_code="DR_VDDK_LIBDIR_UNRESOLVED"
fi
```

Capability JSON:

```json
{
  "vddkReady": true,
  "vddkLibdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk",
  "vddkLibraryVersion": "8",
  "nbdkitVddk": true,
  "missingCode": ""
}
```

### 8.4 Mover Startup

`dr_vmware_mover.sh` should resolve libdir before launching nbdkit:

```bash
libdir="$(ftctl_dr_vddk_resolve_libdir "${credentials_file}" || true)"
[[ -n "${libdir}" ]] || ftctl_vmware_mover_die 70 "DR_VDDK_LIBDIR_UNRESOLVED: no usable VDDK libdir"
nbdkit --dump-plugin vddk "libdir=${libdir}" >/dev/null 2>&1 \
  || ftctl_vmware_mover_die 71 "DR_VDDK_LIBRARY_LOAD_FAILED: ${libdir}"
nbdkit_args+=("libdir=${libdir}")
```

If the socket still does not become ready after successful load preflight,
keep using `DR_VMWARE_NBDKIT_FAILED`.

### 8.5 Exit Mapping

Add explicit mappings:

| Exit | Error code | Meaning |
| --- | --- | --- |
| 70 | `DR_VDDK_LIBDIR_UNRESOLVED` | no usable VDDK libdir candidate |
| 71 | `DR_VDDK_LIBRARY_LOAD_FAILED` | libdir exists but nbdkit cannot load it |
| 69 | `DR_VMWARE_NBDKIT_FAILED` | nbdkit process/socket failed after libdir resolution |

Update:

- `lib/ftctl/dr_runtime.sh`
- `lib/ftctl/dr_scheduler.sh`

### 8.6 Status JSON

FTCTL runtime status should include:

```json
{
  "vmware_data_plane": {
    "vddk_ready": true,
    "vddk_libdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk",
    "vddk_library_version": "8",
    "nbdkit_vddk": true,
    "mover_ready": true,
    "missing_code": ""
  }
}
```

The backend projection should copy this into `dr_run.last_status_json` and
`dr_event.details_json` without exposing secrets.

## 9. DB Design

No mandatory table migration is required for the first fix because the schema
already has JSON extension points:

| Table | Field | Usage |
| --- | --- | --- |
| `dr_site` | `capabilities_json` | latest site connection and optional data-plane capability summary |
| `dr_site_health_check` | `details_json` | health-check history, including VDDK data-plane snapshot when available |
| `dr_run` | `last_status_json` | FTCTL terminal/runtime status including VDDK fields |
| `dr_run_step` | `details_json` | preflight or mover failure diagnostic |
| `dr_event` | `details_json` | operator-readable failure evidence |

Suggested JSON shape in `dr_site.capabilities_json`:

```json
{
  "vmware": {
    "vddkLibdirOverride": "/usr/share/ablestack/v2k/compat/vsphere80/vddk"
  },
  "vmwareDataPlane": {
    "state": "READY",
    "hostId": 1,
    "vddkLibdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk",
    "vddkLibraryVersion": "8",
    "checkedAt": "2026-07-07T21:45:00+0900"
  }
}
```

Optional future schema only if frequent querying/filtering is needed:

```sql
ALTER TABLE dr_site
  ADD COLUMN vmware_vddk_libdir varchar(1024) DEFAULT NULL,
  ADD COLUMN vmware_dataplane_state varchar(32) DEFAULT NULL,
  ADD COLUMN vmware_dataplane_checked datetime DEFAULT NULL;
```

Do not add this migration for the immediate implementation unless UI filtering
or reporting requires indexed fields.

## 10. Test Plan

### 10.1 Unit Tests

| Layer | Test |
| --- | --- |
| Backend | `DrVmwareDataPlaneResolver` picks host detail before override fallback |
| Backend | `DrPlanReadinessValidator` blocks VMware source with `DR_VDDK_LIBDIR_UNRESOLVED` |
| Agent | auto-detect command finds `/usr/share/ablestack/v2k/compat/vsphere80/vddk` |
| Agent | profile enrichment adds `credentials.source.vddkLibdir` only when missing |
| FTCTL | resolver accepts ABLESTACK compat VDDK and rejects plugin-only default |
| FTCTL | mover returns 70/71/69 with distinct error codes |
| UI | readiness blocking reasons render localized, actionable messages |

### 10.2 Live Verification

Before starting sync:

```bash
ablestack_vm_ftctl dr-preflight --profile-json <profile> --type vmware-data-plane --json
```

Expected:

```json
{
  "result": "ok",
  "vmware_data_plane": {
    "vddk_ready": true,
    "vddk_libdir": "/usr/share/ablestack/v2k/compat/vsphere80/vddk"
  }
}
```

During sync, `nbdkit` command must include:

```text
libdir=/usr/share/ablestack/v2k/compat/vsphere80/vddk
```

PASS criteria for this specific fix:

1. Plan preview/readiness does not allow sync when no loadable VDDK libdir is
   available.
2. For a valid host, runtime profile or agent enrichment contains
   `credentials.source.vddkLibdir`.
3. FTCTL mover uses the resolved libdir and no longer falls back to
   `/usr/lib64/vmware-vix-disklib`.
4. If nbdkit still fails, UI/DB show the exact terminal error and do not keep
   the run in `ACCEPTED` or `SYNCING`.

## 11. AS-IS / TO-BE Summary

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| UI | Site connection can look normal while VMware data-plane is not visible | Site connection and VMware data-plane readiness are displayed separately |
| API | no VDDK libdir/capability contract in site or plan readiness responses | optional `vddklibdir` override and `vmwareDataPlane` capability response |
| Backend | FTCTL profile credentials do not carry host VDDK hints | resolver enriches VMware source credential with effective `vddkLibdir` |
| Backend readiness | sync can start and fail only inside mover | VMware source data-plane gate blocks before dispatch |
| Agent | VDDK auto-detect misses ABLESTACK compat paths | detects `/usr/share/ablestack/v2k/compat/*/vddk` and enriches profile |
| FTCTL | plugin availability can be mistaken for usable VDDK | resolves and validates actual libdir before preflight/mover |
| FTCTL errors | missing libdir collapses into `DR_VMWARE_NBDKIT_FAILED` | unresolved libdir, library load failure, and nbdkit failure are distinct |
| DB | JSON extension fields do not contain data-plane evidence | capabilities/status/event JSON keep VDDK readiness evidence without new mandatory schema |

## 12. Implementation Update

Implemented on 2026-07-07:

| Layer | Implemented file | Result |
| --- | --- | --- |
| UI | `ui/public/locales/en.json`, `ui/public/locales/ko_KR.json` | added user-facing messages for `DR_VDDK_LIBDIR_UNRESOLVED` and `DR_VDDK_LIBRARY_LOAD_FAILED` |
| API | existing DR plan create/update/start guards | no new API parameter is required for the normal flow; readiness blocks VMware source plans when the selected data-plane worker has no usable VDDK capability |
| Backend | `DrPlanReadinessValidator.java` | validates VMware source data-plane host details before sync dispatch |
| Backend | `FtctlDrUnifiedActionAdapter.java` | injects the selected worker's `Host.HOST_VDDK_LIB_DIR` and `Host.HOST_VDDK_VERSION` into `credentials.source` for FTCTL runtime profiles |
| Agent | `LibvirtComputingResource.java` | auto-detects ABLESTACK v2k compat VDDK paths and verifies that nbdkit can load the selected libdir |
| Agent | `LibvirtFtctlDrActionCommandWrapper.java` | adds a host-local safety enrichment of `credentials.source.vddkLibdir` before writing the temporary FTCTL profile |
| ftctl | `lib/ftctl/dr_vddk.sh`, `dr_vmware.sh`, `dr_vmware_mover.sh`, `dr_runtime.sh`, `dr_scheduler.sh` | resolves and validates VDDK libdir and propagates precise failure codes |
| DB | existing `host_details` and DR runtime JSON columns | no schema migration required; `host_details` carries cached host VDDK capability and run status carries runtime failure/progress |

The implemented flow remains asynchronous: UI calls Cloud API, Cloud dispatches
to Agent, Agent invokes ftctl, and ftctl status is projected back through the
existing run/status polling path.

## 13. 2026-07-07 Follow-up: VDDK Ready But QEMU Source Graph Invalid

After this design was implemented, live validation for plan
`987bb250-3b5a-4053-9720-2ff93b4cc88c` showed that the selected worker can load
the ABLESTACK-bundled VDDK path successfully:

```text
nbdkit --dump-plugin vddk libdir=/usr/share/ablestack/v2k/compat/vsphere80/vddk
vddk_library_version=8
```

The next failure occurred after VDDK/nbdkit readiness, at the `qemu-img` source
graph:

```text
A block device must be specified for "file"
```

That failure is outside the VDDK libdir resolver. The resolver remains valid.
The paired design for the next layer is
[544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md](544-cross-hypervisor-dr-vmware-mover-nbd-source-graph-design-20260707.md).

Boundary rule:

- `DR_VDDK_LIBDIR_UNRESOLVED`, `DR_VDDK_LIBRARY_LOAD_FAILED`, and
  `DR_VMWARE_NBDKIT_FAILED` remain VDDK/nbdkit readiness failures.
- `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID` means nbdkit/VDDK reached a socket, but
  the QEMU block graph built by the mover is invalid.

## 14. 2026-07-08 Follow-up: VDDK Connect Contract

After the VDDK libdir and raw-over-NBD graph fixes, plan
`71182935-11c6-4ed3-aeec-ebde1486bdfa` reached the VMware VDDK connect path and
failed with:

```text
VixDiskLib_ConnectEx: One of the parameters was invalid
```

This is not a libdir or plugin-load failure. The VDDK library is loadable, but
the source connection contract passed to VDDK is incomplete or invalid.

The paired design is
[545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md](545-cross-hypervisor-dr-vmware-vddk-connect-contract-design-20260708.md).

Additional boundary rule:

- `DR_VMWARE_VDDK_CONNECT_INVALID` means VDDK rejected source connection
  parameters such as endpoint, VM MoRef, disk path, snapshot reference, or
  credentials.
- `DR_VMWARE_VDDK_EXPORT_UNAVAILABLE` means the raw-over-NBD graph is valid but
  the requested VDDK export is not available.
- `DR_VMWARE_VDDK_SOURCE_LOCKED` means the VDDK source is a powered-on disk
  opened without the required run snapshot.
- `DR_VMWARE_VDDK_OPEN_DENIED` means VDDK rejected the requested VMDK path,
  commonly because the mover selected the current delta path instead of the base
  backing path.
- These codes are later than libdir readiness and must not be collapsed into
  `DR_VDDK_LIBDIR_UNRESOLVED` or `DR_VMWARE_MOVER_SOURCE_GRAPH_INVALID`.
