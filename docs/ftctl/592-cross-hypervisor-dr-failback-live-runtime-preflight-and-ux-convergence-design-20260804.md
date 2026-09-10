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

# 592. Cross Hypervisor DR Failback Live Runtime Preflight And UX Convergence Design

- Date: 2026-08-04
- Status: code-level corrective design; implementation pending
- Scope: UI, API, Cloud backend, Agent, FTCTL boundary, DB/cache
- Parent design: [591-cross-hypervisor-dr-failback-initial-reverse-seed-and-early-failure-convergence-design-20260804.md](591-cross-hypervisor-dr-failback-initial-reverse-seed-and-early-failure-convergence-design-20260804.md)
- Data contract: [588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md](588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md)
- Engine companion: `ablestack-qemu-exec-tools/docs/ftctl/449-ftctl-dr-live-runtime-observation-and-projection-boundary-design-20260804.md`
- Terminal-causality addendum: [593-cross-hypervisor-dr-failback-reverse-rbd-readonly-and-terminal-causality-design-20260805.md](593-cross-hypervisor-dr-failback-reverse-rbd-readonly-and-terminal-causality-design-20260805.md)
- Live-worker reconciliation addendum: [594-cross-hypervisor-dr-live-worker-and-terminal-reconciliation-design-20260805.md](594-cross-hypervisor-dr-live-worker-and-terminal-reconciliation-design-20260805.md)
- Route and terminal-convergence addendum: [595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)

## 1. Objective

Failback readiness must be decided from fresh runtime observations, not from a
previous cutover projection. The UI must explain which stage failed and must
not label a Cloud/Agent VM-power failure as an FTCTL engine failure.

The corrected preflight separates five facts:

1. committed operating authority;
2. VMware source isolation and power state observed through vCenter;
3. serving KVM VM power state observed through its assigned Mold Agent;
4. FTCTL transition-state contract;
5. KVM-to-VMware reverse data-plane feasibility.

No destructive action may start unless all required stages are `READY` at
action submission time. A modal cache can improve responsiveness, but the
backend must repeat safety-critical probes before dispatch.

## 2. Verified incident and error cause

Plan `7889e625-371a-48f9-b553-54e311481170` reproduced a cross-layer runtime
drift after host restart.

| Layer | Observed value |
|---|---|
| Failback API | `ready=false`, `DR_TRANSITION_TARGET_NOT_SERVING` |
| UI | `FTCTL transition preflight: ERROR 10` |
| Cloud VM DB | VM `266`, `i-2-266-VM`, `Running`, host `2` |
| Cutover DB | `FAILED_OVER`, target `POWERED_ON`, `PROMOTED` |
| Target host | `10.10.32.2` restarted at `2026-08-04 10:16:34` |
| Host libvirt | `i-2-266-VM` absent |
| Other KVM hosts | the domain is absent on `10.10.32.1` and `.3` too |
| Mold Agent | repeated `Domain not found: i-2-266-VM` |
| FTCTL projection | `active_side=TARGET`, `target_power_state=POWERED_ON` |
| FTCTL transition probe | `ready=true` from stale projection |
| VMware source | `w22-01`, vCenter `vm-6429`, `poweredOff` |
| KVM data disks | two active RBD images, 100 GiB and 50 GiB |

The Cloud backend correctly executes `CheckVirtualMachineCommand` before the
FTCTL transition probe. It returns early because the serving domain is absent.
The UI then derives `enginepreflightready=false` from the aggregate failure and
renders it as an FTCTL error. The adjacent value `10` is the authority
generation, not an FTCTL exit code.

There are therefore three independent defects:

1. Cloud DB and FTCTL runtime projections can remain `POWERED_ON` after the
   actual target domain disappears;
2. the response collapses a target-Agent failure and a not-executed FTCTL
   stage into one Boolean;
3. reverse mode is a hard-coded `AGENT_VALIDATION_REQUIRED/AUTO` placeholder
   rather than the actual reverse-preflight result.

## 3. Safety invariants

1. Cloud VM lifecycle and its host Agent are authoritative for current KVM VM
   existence and power state.
2. vCenter is authoritative for current VMware VM power state.
3. FTCTL runtime power fields are projections and must never override a newer
   Agent or vCenter observation.
4. A missing serving TARGET domain is blocking and cannot be bypassed by the
   Failback `force` flag.
5. `force` may only acknowledge a documented source-isolation exception when
   the remote source cannot be queried; it cannot bypass target-domain,
   writer, disk-map, or authority failures.
6. Read-only preflight does not start, stop, define, or delete a VM and does not
   create snapshots or write target sectors.
7. The action executor repeats live power and reverse-data probes after it owns
   the Plan transition lock.
8. A cache is display acceleration only. Its result is never action authority.
9. Runtime drift does not silently change `active_side`; authority remains
   TARGET until an explicit cutover commit changes it.
10. Credentials, raw command lines, secret keys, and unbounded stderr are not
    stored in DB/cache or returned to the UI.

## 4. Corrected end-to-end flow

```text
UI opens Failback modal
  -> getDrFailbackPreflight (cached snapshot, immediate)
  -> refreshDrFailbackPreflight (async when absent/stale)
  -> Cloud preflight worker
       1. authority and site contract
       2. vCenter source power observation
       3. target Mold Agent CheckVirtualMachineCommand
       4. FTCTL transition-preflight-v2
       5. FTCTL reverse data preflight
  -> cache one stage report with observedAt/expiresAt
  -> UI polls and renders each stage

UI confirms Failback
  -> startDrFailback returns Run UUID immediately
  -> executor acquires Plan transition lock
  -> repeat stages 1-5 without trusting modal cache
  -> persist Run steps and FailbackSession evidence
  -> Agent accepts start-only FTCTL command
  -> reverse transfer -> data gate -> power transition -> authority commit
```

Failure short-circuiting is explicit:

```text
AUTHORITY BLOCKED
  -> SOURCE_RUNTIME, TARGET_RUNTIME, ENGINE, REVERSE_DATA = NOT_RUN

TARGET_RUNTIME BLOCKED
  -> ENGINE, REVERSE_DATA = NOT_RUN

ENGINE BLOCKED
  -> REVERSE_DATA = NOT_RUN
```

`NOT_RUN` is not displayed as `ERROR`. It means an earlier safety stage
prevented execution.

## 5. UI design

### 5.1 Files

- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/views/infra/dr/DrStatusPill.vue`
- `ui/src/api/dr.js`
- `ui/src/style/cross-dr.less`
- `ui/public/locales/ko_KR.json`
- `ui/public/locales/en.json`

### 5.2 Modal information architecture

Replace the current mixed rows with four groups.

```text
Failback route
  Current operating site: 32 ABLESTACK Cluster
  Failback destination:    21 VMware ESXi Cluster
  Direction:               KVM -> VMware

Runtime safety
  VMware source VM: POWERED_OFF / vCenter / observed 20:34:10
  KVM serving VM:  NOT_FOUND / ablecube32-2 / observed 20:34:11
  Source isolation: VERIFIED | ACKNOWLEDGED | UNVERIFIED

Engine transition
  FTCTL contract: NOT_RUN | READY | BLOCKED
  Authority generation: 10
  Projection drift: DB_RUNNING_AGENT_NOT_FOUND

Reverse data
  Requested mode: AUTO
  Effective mode: FULL_REVERSE_SEED | REVERSE_FINAL | -
  Baseline: MISSING_EXPECTED | DURABLE | INVALID | -
  Source disks: READY (2) | NOT_RUN | BLOCKED
  VMware writer: READY | NOT_RUN | BLOCKED
```

The top alert uses the stable error-code localization and a short remediation:

```text
Target VM runtime is not available.
Cloud records i-2-266-VM as Running, but the assigned host could not find the
domain. Reconcile and start the target VM before Failback.
```

Raw English Agent details are available only in an expandable technical-detail
section for administrators.

### 5.3 Stage rendering

Add helpers in `DrPlanList.vue`:

```javascript
preflightStage (code) {
  return (this.failbackPreflight.stages || []).find(stage => stage.code === code) || {
    code,
    state: 'NOT_RUN'
  }
},
canSubmitFailback () {
  return this.failbackPreflight.preflightstate === 'READY' &&
    this.requiredFailbackStages.every(code => this.preflightStage(code).state === 'READY')
}
```

`ok-disabled` uses `canSubmitFailback()` and no longer derives all stage state
from the aggregate `ready` Boolean.

The UI must not render:

```text
ERROR 10
```

It renders:

```text
FTCTL contract: NOT RUN
Authority generation: 10
```

### 5.4 Dark-mode alert contract

`cross-dr.less` already defines `--cross-dr-info-*` variables but does not bind
them to `.ant-alert-info`. Add:

```less
.cross-dr-failback-alert.ant-alert-info {
  background: var(--cross-dr-info-bg);
  border-color: var(--cross-dr-info-border);
  color: var(--cross-dr-info-text);
}

.cross-dr-failback-alert .ant-alert-message,
.cross-dr-failback-alert .ant-alert-description,
.cross-dr-failback-alert .ant-alert-icon {
  color: inherit;
}
```

Do not use fixed pale cyan backgrounds or fixed white text. The same selector
must pass light and dark themes. Disabled or `NOT_RUN` states use the muted
surface and secondary text token, not an error color.

### 5.5 Refresh behavior

Opening the modal obtains the latest cached report immediately. If
`expiresat <= now`, UI submits `refreshDrFailbackPreflight`, receives an async
job id, and polls without freezing the page. Closing the modal cancels only UI
polling, never the backend refresh.

The detail page can show the last report, but action submission always causes a
new backend validation under the transition lock.

## 6. API design

### 6.1 Commands

Keep backward-compatible `getDrFailbackPreflight` and add:

```text
refreshDrFailbackPreflight
  planid: UUID, required
  response: async job id
```

`getDrFailbackPreflight` parameters:

```text
planid: UUID, required
maxage: integer seconds, optional, default 15, max 60
```

It returns cached data immediately. It does not wait for vCenter/VDDK probes.

### 6.2 Response DTO

Extend `DrFailbackPreflightResponse` without removing existing fields:

```text
preflightstate          READY | REFRESHING | BLOCKED | STALE
failurestage            AUTHORITY | SOURCE_RUNTIME | TARGET_RUNTIME |
                        FTCTL_TRANSITION | REVERSE_DATA | null
observedat              datetime
expiresat               datetime
direction               KVM_TO_VMWARE
providerpair            ABLESTACK_TO_VMWARE
targetvminstancename    i-2-266-VM
targetvmhostid          host UUID
targetvmhostname        ablecube32-2
targetvmdbstate         Running
targetvmagentstate      POWERED_ON | POWERED_OFF | NOT_FOUND | UNKNOWN
runtimeDriftState       CONSISTENT | DB_RUNNING_AGENT_NOT_FOUND |
                        DB_RUNNING_AGENT_POWERED_OFF | HOST_UNREACHABLE
sourcepowerstate        POWERED_ON | POWERED_OFF | UNKNOWN
sourcepowerauthority    VCENTER | CACHED_CUTOVER | OPERATOR_ACK
enginepreflightstate    READY | BLOCKED | NOT_RUN
enginepreflightready    true | false | null
effectivemode           FULL_REVERSE_SEED | REVERSE_FINAL | null
modedecisioncode        string | null
initialseedrequired     boolean | null
baselinefilestate       string | null
sourcediskprobestate    string | null
sourcediskcount         integer | null
targetwriterprobestate  string | null
estimatedvirtualbytes   long | null
stages                  list<DrPreflightStageResponse>
```

`enginepreflightready` becomes nullable. `null` means `NOT_RUN`; `false` means
FTCTL was invoked and rejected the transition.

Stage response:

```java
class DrPreflightStageResponse {
    String code;
    String state;
    String errorCode;
    String messageKey;
    Date observedAt;
    String authority;
    Map<String, String> boundedEvidence;
}
```

`messageKey` is stable and localizable. `boundedEvidence` has a whitelist and a
maximum serialized size; it never contains secrets or raw stderr.

### 6.3 Compatibility

Legacy clients continue to read `ready`, `errorcode`, `message`,
`enginepreflightready`, and `requestedmode`. New clients use stage state.
Aggregate mapping is:

```text
ready = every required stage is READY
errorcode = first BLOCKED stage error code
enginepreflightready = null when FTCTL_TRANSITION is NOT_RUN
datapreflightstate = state of REVERSE_DATA
```

## 7. Cloud backend design

### 7.1 New services

Add small ownership-specific services:

```text
DrFailbackPreflightCoordinator
DrSourceRuntimeProbeService
DrServingTargetRuntimeProbeService
DrReverseReplicationPreflightService
DrFailbackPreflightCacheService
DrRuntimeDriftEventService
```

`DrFailbackPreflightServiceImpl` becomes the aggregate façade and does not
perform transport-specific checks itself.

### 7.2 `DrServingTargetRuntimeProbeService`

Input:

```java
probe(DrPlanVO plan, DrReplicaVO servingReplica)
```

Algorithm:

```java
UserVmVO vm = userVmDao.findById(replica.getTargetVmId());
requirePresent(vm, vm.getHostId(), vm.getInstanceName());

Answer answer = agentManager.easySend(vm.getHostId(),
    new CheckVirtualMachineCommand(vm.getInstanceName()));

return normalize(vm, answer, clock.now());
```

Normalization:

| DB state | Agent answer | Result | Drift |
|---|---|---|---|
| Running | PowerOn | READY | CONSISTENT |
| Running | PowerOff | BLOCKED | DB_RUNNING_AGENT_POWERED_OFF |
| Running | domain missing | BLOCKED | DB_RUNNING_AGENT_NOT_FOUND |
| any | no answer/host down | BLOCKED | HOST_UNREACHABLE |
| Stopped | PowerOff | BLOCKED | CONSISTENT_NOT_SERVING |

The service records the response receipt time as `observedAt`. It must not
change `vm_instance.state` directly. It emits a drift event and requests the
ordinary Cloud VM-state reconciler to converge the VM separately.

### 7.3 `DrSourceRuntimeProbeService`

For `VMWARE_DIRECT`, query the registered vCenter through the existing site
adapter and stored credential reference. ESXi credentials are not requested or
persisted.

Output includes VM managed-object reference, `powerState`, vCenter observation
time, and connection state. Failback requires `poweredOff` while vCenter is
reachable.

If vCenter is unavailable, an operator `force` acknowledgement can satisfy
source isolation only when the committed fence evidence is acknowledged and a
typed reason/confirmation is supplied. `force` never changes target-runtime or
data-plane results.

### 7.4 `DrSourceIsolationPreflightServiceImpl`

Split the current method into stage orchestration. It must not return a single
failure object before preserving completed stage evidence.

Current:

```java
if (target power probe failed) {
    return failure(...); // FTCTL appears false even though it was not called
}
```

Target:

```java
report.add(authorityStage);
report.add(sourceRuntimeStage);
report.add(targetRuntimeStage);

if (!report.requiredStagesReady()) {
    report.markRemainingNotRun();
    return report;
}

report.add(ftctlTransitionStage);
return report;
```

Rename Run step `source-isolation-preflight` into independent steps for new
Runs while retaining old step rendering:

| Step order | Step name |
|---|---|
| 10 | `authority-preflight` |
| 11 | `source-runtime-preflight` |
| 12 | `target-runtime-preflight` |
| 13 | `ftctl-transition-preflight` |
| 14 | `reverse-data-preflight` |

### 7.5 `DrFailbackPreflightServiceImpl`

The coordinator order is strict:

```java
report = coordinator.begin(plan);
report.add(validateAuthority(plan));
report.add(validateSitesAndCredentials(plan));
report.add(probeSourceRuntime(plan));
report.add(probeServingTarget(plan));
report.add(probeFtctlTransition(plan));
report.add(probeReverseData(plan));
return report.finish();
```

The reverse probe is called only after target runtime and FTCTL transition are
ready. It merges `FtctlDrReversePreflightAnswer`; it does not retain the current
hard-coded `AGENT_VALIDATION_REQUIRED` value.

### 7.6 Action-time validation

`DrOrchestratorImpl.startDrFailback` persists the asynchronous Run first, then
the executor performs a fresh report under the Plan transition lock. It does
not reuse a modal report older than the lock acquisition.

On failure:

```text
Run = FAILED
FailbackSession = FAILED when already created
active_side = TARGET retained
VMware remains powered off
KVM VM is not automatically created or started by preflight
```

The root error is the first blocked stage, for example
`DR_TRANSITION_TARGET_DOMAIN_NOT_FOUND`, not a generic FTCTL failure.

### 7.7 Runtime drift reconciliation

When Mold Agent reconnects or a host boot id changes:

1. identify DR replicas assigned to that host;
2. invalidate their failback-preflight cache;
3. query actual domain state through the normal VM-state reconciliation path;
4. publish `DR_RUNTIME_STATE_DRIFT_DETECTED` when DB and Agent disagree;
5. mark protection health `DEGRADED` without changing committed authority;
6. require operator/Cloud lifecycle recovery before Failback is enabled.

DR code must not repair the VM by issuing direct libvirt commands or by
updating `vm_instance.state` with SQL.

## 8. Agent design

### 8.1 Serving VM probe

Reuse `CheckVirtualMachineCommand` for live KVM power because Cloud VM
lifecycle already owns that command. The DR backend adds typed normalization.
No FTCTL command is allowed to claim that this is a live observation.

Agent logging adds one bounded structured line:

```text
plan=<uuid> instance=i-2-266-VM host=<uuid>
probe=serving-target result=DOMAIN_NOT_FOUND observedAt=<timestamp>
```

Do not include disk credentials, API keys, or profile JSON.

### 8.2 FTCTL transition probe

The Agent sends `FtctlDrStatusCommand(StatusScope.TRANSITION_PREFLIGHT)` only
after the serving-target probe is READY. Its result is mapped to the
`FTCTL_TRANSITION` stage. A skipped call is `NOT_RUN`, not `false`.

### 8.3 Reverse-data probe

`FtctlDrReversePreflightCommand` continues to carry a temporary profile,
`operationIntent=FAILBACK_FINAL`, and `requestedMode=AUTO`. The wrapper must
return all selector and probe fields. It runs only after earlier stages pass.

Timeout and parser failures map to distinct errors:

```text
DR_REVERSE_PREFLIGHT_TIMEOUT
DR_REVERSE_PREFLIGHT_CONTRACT_MISMATCH
DR_REVERSE_PREFLIGHT_ENGINE_REJECTED
```

## 9. FTCTL boundary

FTCTL transition runtime is durable workflow projection, not live
hypervisor inventory. Its JSON changes terminology:

```text
target_power_state              -> projection_target_power_state
source_power_state              -> projection_source_power_state
power_evidence_authority        = PROJECTION_ONLY
power_evidence_observed_at      = <runtime update time>
```

Legacy fields remain during compatibility, but the Agent/Cloud response marks
them as projection evidence.

`dr-transition-preflight` validates engine-owned facts only:

- active side and authority generation;
- transition lock and active operation;
- scheduler quiescence;
- runtime/profile contract version;
- previous terminal action convergence.

It does not declare a KVM VM live merely because the runtime file says
`POWERED_ON`.

`dr-reverse-preflight` validates the data path and returns a typed failure when
the active KVM source domain is absent:

```text
DR_REVERSE_SOURCE_DOMAIN_NOT_FOUND
```

Source RBD images existing is insufficient: final failback requires a live
serving VM that can be quiesced and checkpointed.

The engine companion document 449 defines the exact JSON and self-tests.

## 10. DB and cache design

### 10.1 No authority mutation from preflight

Read-only preflight does not update:

- `dr_plan.active_side`;
- `dr_cutover_session.target_power_state`;
- `vm_instance.state`;
- `dr_replica.active_side`.

These remain committed workflow/lifecycle data, not fresh observations.

### 10.2 View-cache snapshot

No new table is required. Increment `dr_plan_view_cache.snapshot_version` and
add a bounded object to `snapshot_json`:

```json
{
  "failbackPreflight": {
    "state": "BLOCKED",
    "failureStage": "TARGET_RUNTIME",
    "generatedAt": "2026-08-04T20:34:11+09:00",
    "expiresAt": "2026-08-04T20:34:26+09:00",
    "authorityGeneration": 10,
    "direction": "KVM_TO_VMWARE",
    "targetRuntime": {
      "instanceName": "i-2-266-VM",
      "dbState": "Running",
      "agentState": "NOT_FOUND",
      "hostId": 2,
      "driftState": "DB_RUNNING_AGENT_NOT_FOUND"
    },
    "stages": []
  }
}
```

TTL defaults to 15 seconds. Host reconnect, VM start/stop, cutover generation
change, and Plan action acceptance invalidate the object immediately.

### 10.3 Audit evidence

Modal refresh writes no Run rows. It may emit a deduplicated `dr_event` only
when a new drift signature appears:

```text
event_type=DR_RUNTIME_STATE_DRIFT_DETECTED
resource_type=DR_PLAN
details={stage, vmId, instanceName, hostId, dbState, agentState, observedAt}
```

Action-time preflight persists the five `dr_run_step` rows and bounded details.
Repeated polling does not create repeated events.

### 10.4 Schema impact

This design uses the existing `dr_plan_view_cache.snapshot_json`, `dr_event`,
and `dr_run_step` structures. No DDL is required. Schema tests only assert the
existing mediumtext capacity and cache version compatibility.

## 11. Error taxonomy and precedence

| Stage | Error code | Meaning |
|---|---|---|
| AUTHORITY | `DR_TRANSITION_AUTHORITY_INVALID` | committed TARGET authority missing/inconsistent |
| SOURCE_RUNTIME | `DR_SOURCE_VM_NOT_POWERED_OFF` | vCenter reports VMware source on |
| SOURCE_RUNTIME | `DR_SOURCE_RUNTIME_UNAVAILABLE` | vCenter observation unavailable |
| TARGET_RUNTIME | `DR_TRANSITION_TARGET_VM_MISSING` | Cloud target identity/host missing |
| TARGET_RUNTIME | `DR_TRANSITION_TARGET_DOMAIN_NOT_FOUND` | assigned Agent cannot find domain |
| TARGET_RUNTIME | `DR_TRANSITION_TARGET_NOT_POWERED_ON` | domain exists but is off |
| TARGET_RUNTIME | `DR_TRANSITION_TARGET_HOST_UNREACHABLE` | Agent/host unavailable |
| FTCTL_TRANSITION | `DR_TRANSITION_ENGINE_PREFLIGHT_FAILED` | FTCTL invoked and rejected state |
| FTCTL_TRANSITION | `DR_AGENT_TRANSITION_PREFLIGHT_CONTRACT_MISMATCH` | typed answer invalid |
| REVERSE_DATA | `DR_REVERSE_SOURCE_DOMAIN_NOT_FOUND` | active KVM data source cannot be quiesced |
| REVERSE_DATA | `DR_REVERSE_PREFLIGHT_ENGINE_REJECTED` | disk/writer/baseline validation failed |

The first blocking stage is the aggregate error. Later stages remain
`NOT_RUN`; they cannot overwrite it.

## 12. Verification design

### 12.1 Cloud unit tests

Add tests for:

1. DB `Running` + Agent `PowerOn` -> target stage READY;
2. DB `Running` + Agent domain missing -> typed drift and BLOCKED;
3. target BLOCKED -> FTCTL and reverse services are never invoked;
4. engine not invoked -> nullable `enginepreflightready`, state `NOT_RUN`;
5. authority generation is not rendered as an error number;
6. vCenter `poweredOff` overrides stale cutover `UNKNOWN` for display;
7. cache expiry and host reconnect invalidate the report;
8. action executor ignores display cache and repeats live probes;
9. `force` cannot bypass missing target domain;
10. no preflight path mutates VM, authority, or cutover rows.

### 12.2 Agent tests

1. normalize `PowerOn`, `PowerOff`, domain missing, timeout;
2. transition command is skipped after target-runtime failure;
3. reverse command is skipped after transition failure;
4. structured log excludes profile and credentials;
5. reverse answer preserves all selector fields.

### 12.3 FTCTL tests

The companion document requires:

1. stale projected `POWERED_ON` is labeled projection-only;
2. transition readiness does not claim live VM presence;
3. missing active KVM domain returns `DR_REVERSE_SOURCE_DOMAIN_NOT_FOUND`;
4. source disks alone do not satisfy reverse readiness;
5. successful live-domain preflight remains read-only and emits one JSON
   object with no stderr.

### 12.4 UI tests

1. `NOT_RUN` is neutral and not red;
2. target Agent failure is not labeled FTCTL failure;
3. authority generation has its own label;
4. KVM-to-VMware direction and actual mode/probes are visible;
5. Confirm remains disabled for target-domain drift even with `force=true`;
6. `ant-alert-info` meets light/dark contrast and uses theme tokens;
7. raw Agent English is replaced by localized operator text;
8. async refresh leaves the rest of the UI usable.

### 12.5 Live acceptance

1. Reproduce DB `Running` with absent domain and verify typed BLOCKED stages.
2. Confirm FTCTL transition and reverse stages show `NOT_RUN`.
3. Recover the target VM through Cloud lifecycle, not direct virsh or SQL.
4. Verify `virsh domstate`, Agent answer, Cloud VM state, and cache agree.
5. Verify vCenter source is `poweredOff` and displayed as live evidence.
6. Verify reverse preflight reports direction, mode, baseline, two disks, and
   writer readiness.
7. Submit Failback and confirm action-time probes repeat under the lock.
8. Verify Run steps, FailbackSession, FTCTL events, and UI converge.

## 13. Recommended implementation priority

1. P0: stage result model and nullable/not-run API semantics.
2. P0: target live-runtime probe normalization and non-bypassable gate.
3. P0: source vCenter live-power probe and force-policy boundary.
4. P0: action-time revalidation under the Plan transition lock.
5. P0: FTCTL projection-only power semantics and missing-domain reverse error.
6. P1: merge actual reverse preflight fields; remove hard-coded placeholder.
7. P1: cache version, invalidation, and deduplicated runtime-drift event.
8. P1: UI stage layout, localization, generation separation, dark-mode alert.
9. P1: Cloud/Agent/FTCTL/UI tests and changed-module builds.
10. P2: paired deployment, runtime reconciliation, and live Failback retest.

Do not begin a live Failback retest after only the UI patch. P0 items and the
target VM runtime reconciliation must be complete first.

## 14. Error cause and AS-IS / TO-BE

| Layer | Error cause | AS-IS | TO-BE |
|---|---|---|---|
| UI | aggregate Boolean is presented as engine state | `FTCTL ERROR 10` mixes stage and generation | independent stage, error code, generation, and observation time |
| UI style | info variables are not bound to info alert | pale ribbon and low-contrast text in dark mode | token-based `.ant-alert-info` with inherited text/icon color |
| API | skipped FTCTL stage becomes `false` | target Agent failure looks like FTCTL rejection | nullable stage state: `NOT_RUN`, `READY`, `BLOCKED` |
| API | reverse values are defaults | `AGENT_VALIDATION_REQUIRED/AUTO` only | actual direction, effective mode, baseline, disk, writer evidence |
| Backend | cutover/DB projection and live runtime are conflated | stale `POWERED_ON` survives missing domain | Agent/vCenter observations are separate authorities |
| Backend | early return discards completed-stage structure | one generic failure object | ordered stage report with short-circuit and preserved evidence |
| Agent | domain absence is only free-form details | Cloud knows only not-PowerOn | normalized `NOT_FOUND/OFF/ON/UNREACHABLE` result and timestamp |
| FTCTL | runtime power field looks live | stale projection can return transition ready | projection-only terminology; Cloud Agent owns live KVM power |
| FTCTL | disk existence can appear sufficient | source VM may be absent | reverse preflight requires live source domain and quiesce capability |
| DB | workflow projection remains after host restart | DB `Running`, cutover `POWERED_ON` | keep committed authority but cache fresh observation and drift state |
| Cache | no stage report or invalidation contract | modal repeats ambiguous synchronous result | 15-second bounded snapshot, host/VM/action invalidation |
| Safety | force toggle appears able to override all checks | operator may expect unsafe bypass | force never bypasses target VM, authority, disk, or writer failures |
| Operations | action may trust earlier readiness | state can change between modal and submit | repeat all safety probes under transition lock |

## 15. Retest gate

The affected Plan is not ready for Failback while all three KVM hosts report
that `i-2-266-VM` is absent. The next test gate is:

```text
Cloud DB VM state            == Running
assigned Agent VM state      == POWERED_ON
runtime drift                == CONSISTENT
vCenter source power         == POWERED_OFF
FTCTL transition stage       == READY
reverse data stage           == READY
overall failback preflight   == READY
```

RBD disk presence and a stale FTCTL `POWERED_ON` projection do not satisfy this
gate.

## 16. Implementation and deployment result (2026-08-04)

The P0/P1 contract in this document was implemented and deployed as one paired
Cloud/Agent/FTCTL/UI change. No DB DDL was added because the ordered stage
report uses the existing response, run-step, event, and view-cache structures.

### 16.1 Build and deployment evidence

- Cloud changed modules built successfully from the WSL ext4 clone:
  `cloud-core`, KVM hypervisor plugin, and disaster-recovery integration.
- Seven staged preflight tests passed, including DB/Agent drift,
  short-circuit, and ordered-stage response cases.
- The production UI build completed and was deployed by replacing static
  assets only; `WEB-INF` remained present and `/client/` returned HTTP 200.
- FTCTL was built by GitHub Actions run `30911213591` from qemu-exec-tools
  commit `95bb796e9eed3e051d943ca269fd1da67eadef03`.
- The deployed RPM is `ablestack_vm_ftctl-0.9.1-1.noarch`, SHA-256
  `e2e6aab624b65b626eaa571cf9e2cf6c3a9210c225681811bd85d5c8f998e051`.
- `mold`, all three `mold-agent` services, and all three FTCTL timers were
  verified active after deployment.

### 16.2 Live acceptance result

Plan `7889e625-371a-48f9-b553-54e311481170` was verified through the live API
and the deployed UI. Its serving KVM VM `i-2-266-VM` is present and running on
host `10.10.32.2`; Cloud reports DB state `Running` and Agent state
`POWERED_ON` with runtime drift `CONSISTENT`.

The ordered live report returned:

| Stage | State | Observer |
|---|---|---|
| `AUTHORITY` | `READY` | `CLOUD_DB` |
| `SOURCE_RUNTIME` | `READY` | `CUTOVER_OR_FENCE` |
| `TARGET_RUNTIME` | `READY` | `MOLD_AGENT` |
| `FTCTL_TRANSITION` | `READY` | `FTCTL` |
| `REVERSE_DATA` | `READY` | `FTCTL` |

Reverse data preflight selected `FULL_REVERSE_SEED` because the initial
reverse baseline is intentionally absent, then proved the live source domain,
two source disks, and the VMware target writer path ready. This is a valid
first failback mode, not a fallback caused by a failed probe.

The deployed dark-mode dialog rendered all five stages independently and kept
Confirm available only after every stage became `READY`. No browser console
error was observed. The plan is therefore ready for the operator's failback
retest; this acceptance did not submit the destructive transition itself.

## 17. Post-Preflight Transfer Correction (2026-08-05)

The subsequent live Failback proved that all five preflight stages can be
`READY` while reverse transfer still fails if the RBD snapshot is opened
writable. Document 593 adds the missing transfer-level invariant and corrects
the terminal publication race. A successful preflight means a Run may be
dispatched; final PASS still requires read-only snapshot attachment, non-zero
reverse bytes, durable terminal evidence, and gate-controlled authority change.

## 18. Live Worker Observation Correction (2026-08-05)

A successful transition preflight does not authorize Cloud to infer terminal
state from one worker PID observation. Document 594 adds the post-dispatch
`LIVE/RECONCILIATION_REQUIRED/DEAD_CONFIRMED/ENGINE_TERMINAL` classifier. While
bytes, heartbeat, or owned processes are live, the Run remains non-terminal and
all duplicate mutations remain blocked.

## 19. Evidence Publication Capability Correction (2026-08-06)

Pre-dispatch reverse-path readiness and post-transfer commit readiness remain
separate phases, but they now share evidence contract version 1. A preflight may
report Ready only when the installed FTCTL can publish the complete typed
post-transfer evidence required by Cloud. UI presents one preparation result;
the field-level evidence remains system diagnostics. See document 596.
