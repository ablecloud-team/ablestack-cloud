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

# 591. Cross Hypervisor DR Failback Initial Reverse Seed And Early Failure Convergence Design

- Date: 2026-08-04
- Status: revision 3 code-level corrective design; live-runtime convergence implementation pending
- Scope: UI, API, Cloud backend, Agent contract, FTCTL status, DB
- Parent design: [588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md](588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md)
- Engine companion: `ablestack-qemu-exec-tools/docs/ftctl/448-ftctl-dr-initial-reverse-seed-baseline-absence-and-terminal-evidence-design-20260804.md`
- Route and terminal-convergence correction: [595](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)

> Revision 3 live-runtime correction:
> [592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md](592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md)
> supersedes any preflight interpretation that treats Cloud DB or FTCTL
> projected `POWERED_ON` as a live KVM observation. Failback readiness now
> requires separate vCenter, target Agent, FTCTL transition, and reverse-data
> stages with explicit `NOT_RUN` semantics.

> Revision 4 terminal-causality correction:
> [593-cross-hypervisor-dr-failback-reverse-rbd-readonly-and-terminal-causality-design-20260805.md](593-cross-hypervisor-dr-failback-reverse-rbd-readonly-and-terminal-causality-design-20260805.md)
> is normative for read-only RBD snapshot attachment, terminal evidence
> precedence, dead-worker grace, and `DATA_READY`-only data-gate execution.

## 1. Objective

Failback must be asynchronous, observable from request acceptance onward, and
safe when the first KVM-to-VMware reverse seed fails before producing an engine
session id. Cloud must distinguish API acceptance, Agent acceptance, FTCTL
execution, reverse data durability, and authority commit. Only durable reverse
data followed by authority commit permits VMware promotion.

## 2. Verified error cause

Plan `7889e625-371a-48f9-b553-54e311481170` and Failback Run
`7ed30e9b-da7a-4baa-bef9-be555b1464b5` established:

| Layer | Verified result |
|---|---|
| UI/API | asynchronous request accepted |
| Backend | Run and steps created; dispatch completed |
| Agent | command accepted on target worker host |
| FTCTL | reverse profile generated, mover exited `2` |
| Source data | both target-authority RBD disks exist |
| VMware target | powered off, safely fenced |
| DB Plan | `ERROR`, `active_side=TARGET` |
| DB Run | `FAILED`, generic `DR_FAILBACK_REVERSE_SYNC_FAILED` |
| DB failback session | absent |

The engine root cause is the unconditional read of the absent first-generation
reverse baseline. Cloud has a separate convergence defect:

- `DrFailbackLifecycleServiceImpl.reconcile()` creates a session only when
  `failback_session_id` is non-empty;
- FTCTL publishes that id only after reverse data is ready;
- an early mover failure therefore has no `dr_failback_session` row;
- raw mover exit and phase collapse into a generic Run error;
- stale worker and target-storage fields can contradict actual resources.

## 3. Invariants

1. UI calls Cloud API only and never Agent or FTCTL directly.
2. API returns a Run UUID immediately; no transfer runs in the request thread.
3. A `dr_failback_session` row exists before Agent dispatch.
4. `engine_session_id` remains nullable until FTCTL publishes one.
5. Missing reverse baseline is ready when mode is `FULL_REVERSE_SEED`.
6. The data gate runs only after session state `DATA_READY`.
7. Every pre-commit failure preserves TARGET authority, VMware off, and KVM on.
8. Cloud DB is authoritative for target VM, volume, and network existence.
9. `accepted=true` and operation success are independent facts.
10. UI success requires terminal Run success, lifecycle completion, authority
    acknowledgement, and resumed protection evidence.

## 4. End-to-end state contract

```text
UI submit
  -> QUEUED Run + REQUESTED FailbackSession
  -> async executor -> Agent ACCEPTED
  -> FTCTL REVERSE_PREFLIGHT
  -> FULL_REVERSE_SEED | REVERSE_INCREMENTAL
  -> FAILBACK_DATA_READY
  -> Cloud data gate
  -> target stop -> source start -> boot validation
  -> authority commit -> forward protection resume -> COMPLETED

Early failure
  -> FTCTL FAILED -> Agent typed status
  -> Run FAILED + FailbackSession FAILED
  -> active_side=TARGET retained
```

`Plan.state=ERROR` must not hide the serving authority. The response carries a
separate operation-health state while retaining `activeSide=TARGET`.

## 5. UI design

### 5.1 Files

- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/views/infra/dr/DrProtectionInfoTab.vue`
- `ui/src/views/infra/dr/DrStatusPill.vue`
- `ui/src/api/dr.js`
- `ui/src/locales/en.json`
- `ui/src/locales/ko.json`

### 5.2 Behavior

The Failback modal closes after the API returns a valid Run UUID. The UI polls
cached protection-view and Run APIs without blocking navigation.

Show independent authority and operation facts:

```text
Current operating site: Target site (ABLESTACK)
Failback operation: Failed at reverse preflight
Protection status: Degraded, target authority preserved
```

Expose effective reverse mode, baseline file state, failure phase/component,
driver exit code, authority retained, and retry eligibility. Do not display a
generic resource-loss message when Cloud has active replica VM/disk rows.

Retry Failback is enabled only when:

```text
activeSide == TARGET
latest Failback Run is terminal FAILED
no active Run exists
target VM is powered on
source VM is powered off
preflight is READY or has a fixable typed failure
```

Add i18n mappings for `DR_REVERSE_BASELINE_REQUIRED`,
`DR_REVERSE_BASELINE_INVALID`, `DR_REVERSE_SOURCE_STORAGE_MISSING`,
`DR_REVERSE_TARGET_VM_NOT_STOPPED`, `DR_REVERSE_WRITER_FAILED`, and
`DR_REVERSE_DURABILITY_VERIFY_FAILED`. Raw JSON, credentials, host paths, and
full stderr are never rendered.

## 6. API design

### 6.1 Start response

`StartDrFailbackCmd` remains start-only:

```json
{
  "runid": "<run uuid>",
  "failbacksessionid": "<cloud session uuid>",
  "state": "QUEUED",
  "accepted": true
}
```

The Cloud session UUID exists while `engineSessionId` may still be null.

### 6.2 Preflight response

`GetDrFailbackPreflightCmd` adds:

```json
{
  "effectivemode": "FULL_REVERSE_SEED",
  "baselinefilestate": "MISSING_EXPECTED",
  "sourcediskprobestate": "READY",
  "targetwriterprobestate": "READY",
  "targetvmpowerstate": "POWERED_OFF",
  "ready": true
}
```

`MISSING_EXPECTED` blocks only an incremental request, not initial full seed.

### 6.3 Read responses

Run, failback-session, and protection-view responses add nullable fields:

```text
acceptanceState, failurePhase, failedComponent, driverExitCode,
baselineFileState, workerPidAlive, activeSide, authorityRetained
```

## 7. Backend design

### 7.1 `DrOrchestratorImpl`

In the transaction that persists a Failback Run, create the Cloud session:

```java
private void createRequestedFailbackSession(DrPlanVO plan, DrRunVO run) {
    if (!DrConstants.RUN_TYPE_FAILBACK.equals(run.getRunType())) {
        return;
    }
    DrFailbackSessionVO session = new DrFailbackSessionVO(
            plan.getId(), run.getId(), null, "REQUESTED");
    session.setEngineAckState("PENDING");
    session.setTargetPowerState("POWERED_ON");
    session.setSourcePowerState("POWERED_OFF");
    session.setAcceptanceState("QUEUED");
    drFailbackSessionDao.persist(session);
}
```

The existing unique key on `run_id` preserves idempotency.

### 7.2 `DrFailbackLifecycleServiceImpl`

Session existence no longer depends on `engineSessionId`:

```text
REQUESTED -> DISPATCHED -> ENGINE_ACCEPTED -> REVERSE_PREFLIGHT
          -> REVERSE_SYNCING -> DATA_READY -> lifecycle states
          -> FAILED
```

Rules:

- attach `engineSessionId` later when FTCTL publishes it;
- update the pre-created session for every status sample;
- terminal worker failure before `DATA_READY` calls `failSession()` with typed
  phase/component/driver exit evidence;
- lifecycle power transitions are illegal before `DATA_READY`;
- every pre-commit failure calls `preserveTargetAuthority(plan)`;
- persist one deduplicated `FAILBACK_FAILED` event per Run/session.

### 7.3 `DrFailbackDataGateServiceImpl`

Keep existing durable-data checks and add an explicit state guard:

```java
if (!DATA_READY.equals(session.getState())) {
    return blocked("DR_FAILBACK_DATA_NOT_READY", ...);
}
```

The gate validates the committed reverse baseline, durable writer, verified
target writes, and guest compatibility. It does not inspect an absent initial
baseline before transfer.

### 7.4 `FtctlDrRuntimeProjectionAdapter`

- `worker_state=RUNNING` with `worker_pid_alive=false` and no newer heartbeat is
  terminal failed;
- preserve `worker_exit_code`, `failure_phase`, `failed_component`, and bounded
  `error_message` in Run/step details;
- update the pre-created session before Run terminal convergence;
- failed Failback preserves target authority and active replica resources;
- merge operation health with Cloud materialization rather than replacing it.

### 7.5 `DrPlanReadinessValidator`

Retain Cloud-owned resource authority:

```text
targetStoragePresent = latest target-ready restore point
                    OR plan.lastTargetDurableAt
                    OR active materialized replica disks
```

FTCTL `target_storage_present=false` is a probe disagreement when active Cloud
volumes exist, not proof of deletion. Actual format validation may still block.

### 7.6 Protection view and response generation

`DrProtectionViewServiceImpl` includes the early session. `DrResponseGenerator`
separates:

```text
authorityState=FAILED_OVER
operationState=FAILED
readinessState=DEGRADED
activeSide=TARGET
```

## 8. Agent contract

Update these concrete boundaries:

- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/adapter/ftctl/FtctlDrUnifiedActionAdapter.java`;
- `core/src/main/java/com/cloud/agent/api/FtctlDrActionCommand.java`;
- `core/src/main/java/com/cloud/agent/api/FtctlDrStatusAnswer.java`;
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrActionCommandWrapper.java`;
- `plugins/hypervisors/kvm/src/main/java/com/cloud/hypervisor/kvm/resource/wrapper/LibvirtFtctlDrStatusCommandWrapper.java`.

The action adapter and command/answer DTO preserve:

```text
planUuid, runUuid, cloudFailbackSessionUuid, idempotencyKey,
expectedActiveSide=TARGET, expectedSourcePowerState=POWERED_OFF
```

The Agent returns quickly after FTCTL accepts the worker. `FtctlDrStatusAnswer`
adds nullable `workerPidAlive`, `workerExitCode`, `failurePhase`,
`failedComponent`, `baselineFileState`, `sourceDiskProbeState`, and
`targetWriterProbeState`. Non-zero driver exit is never translated to success;
the answer contains only a bounded redacted summary.

## 9. DB design

### 9.1 Row timing and schema

Insert `dr_failback_session` with the Run before dispatch. Keep
`engine_session_id` nullable and add queryable terminal evidence:

```sql
acceptance_state     varchar(32) NULL,
failure_phase        varchar(64) NULL,
failed_component     varchar(128) NULL,
driver_exit_code     int NULL,
baseline_file_state varchar(32) NULL,
worker_pid_alive     tinyint(1) NULL
```

Bounded full status remains in `details_json`. Update consistently:

- `setup/db/create-schema.sql`;
- `engine/schema/src/main/resources/META-INF/db/schema-Europa-After.sql`;
- maintained upgrade paths that create/alter `dr_failback_session`;
- `DrFailbackSessionVO`, response generation, and DAO tests.

Upgrade scripts use `IDEMPOTENT_ADD_COLUMN`.

### 9.2 Reconcile candidates

`DrFailbackSessionDaoImpl.RECONCILE_STATES` adds:

```text
REQUESTED, DISPATCHED, ENGINE_ACCEPTED, REVERSE_PREFLIGHT, REVERSE_SYNCING
```

Terminal `FAILED` is finalized once and is not continuously reconciled.

## 10. Error propagation

| FTCTL error | Cloud result | Authority | Retry |
|---|---|---|---|
| `DR_REVERSE_BASELINE_REQUIRED` | FAILED | TARGET retained | select/reset full seed |
| `DR_REVERSE_BASELINE_INVALID` | FAILED | TARGET retained | repair/reset baseline |
| `DR_REVERSE_SOURCE_STORAGE_MISSING` | FAILED | TARGET retained | recover storage |
| `DR_REVERSE_TARGET_VM_NOT_STOPPED` | FAILED | TARGET retained | restore VMware fence |
| `DR_REVERSE_WRITER_FAILED` | FAILED | TARGET retained | repair VDDK writer |
| `DR_REVERSE_DURABILITY_VERIFY_FAILED` | FAILED | TARGET retained | verify target storage |

Backend logic branches on stable codes, never translated message text.

## 11. Tests and build verification

### 11.1 UI

- modal closes on accepted Run;
- early failure appears without reload;
- current operating site remains TARGET;
- retry action follows typed readiness;
- dark mode uses semantic status tokens;
- no raw JSON or host path is exposed.

### 11.2 API/backend

Add or extend:

- `DrOrchestratorImplTest`;
- `DrFailbackLifecycleServiceImplTest`;
- `DrFailbackDataGateServiceImplTest`;
- `FtctlDrRuntimeProjectionAdapterTest`;
- `DrFailbackPreflightServiceImplTest`;
- `DrProtectionViewServiceImplTest`.

- Run and session commit atomically;
- idempotent request creates one session;
- missing engine id does not lose early failure evidence;
- only `DATA_READY` invokes the data gate;
- pre-commit failure always preserves TARGET;
- repeated polling creates one terminal event;
- Cloud resource rows override transient false storage probe.

### 11.3 Agent and DB

- command acceptance returns before transfer;
- exit/phase/component survive serialization;
- dead worker PID projects false;
- secrets and full stderr are absent;
- create/upgrade schema matches VO;
- no orphan active session remains after cleanup.

### 11.4 Live acceptance

1. preflight reports `MISSING_EXPECTED/FULL_REVERSE_SEED`;
2. initial reverse seed writes non-zero bytes and commits generation 1;
3. a second KVM change produces a smaller incremental transfer;
4. Failback reaches `DATA_READY` before power transition;
5. Cloud validates source boot and commits authority;
6. post-Failback forward protection creates a newer durable checkpoint;
7. UI/API/DB/Agent/FTCTL agree on terminal state and active side.

## 12. Recommended implementation priority

1. P0: FTCTL baseline-absence correction and mover self-tests.
2. P0: pre-dispatch Cloud failback-session creation.
3. P0: typed Agent/FTCTL failure propagation and dead-worker cleanup.
4. P1: lifecycle convergence and TARGET-authority preservation tests.
5. P1: DB columns, schema paths, VO, DAO, and API fields.
6. P1: readiness merge and UI authority/operation split.
7. P2: changed Maven module build, UI build, and qemu Actions package.
8. P2: paired deployment and clean live acceptance.

## 13. AS-IS / TO-BE

| Layer | Error cause | AS-IS | TO-BE |
|---|---|---|---|
| UI | generic Run error | reason and serving site ambiguous | typed failure plus TARGET retained |
| API | Run only at acceptance | no session before engine data-ready | Run and Cloud session returned |
| Backend | session depends on engine id | early failure has no lifecycle row | session before dispatch |
| Lifecycle | data-ready path dominates | early terminal path incomplete | explicit pre-data-ready convergence |
| Agent | driver context compressed | generic reverse-sync failure | exit/phase/component fields |
| FTCTL | absent baseline read unconditionally | exit `2` before full seed | `MISSING_EXPECTED` full seed |
| DB | no early failback row | Run/session evidence disagree | one session per Run from REQUESTED |
| Readiness | operation can shadow resources | real RBD appears absent | Cloud materialization authoritative |
| Authority | Plan error obscures active side | serving site can be misread | authority and operation separated |

## 14. Completion criteria

Complete means: missing-baseline initial reverse seed succeeds, the next cycle
is truly incremental, injected early failure yields matching terminal evidence
in every layer, and every pre-commit failure visibly retains active KVM
authority without synchronous UI/API waiting.

## 15. Implementation result (2026-08-04)

The implementation now follows this document's early-session contract:

- `DrOrchestratorImpl` creates exactly one `REQUESTED` failback session in the
  same transaction as the Run, using the deterministic FTCTL session identity;
- `DrFailbackLifecycleServiceImpl` accepts status before DATA_READY, projects
  preflight/reverse phases, converges dead-worker or typed runtime failures to
  FAILED, and retains TARGET authority before Cloud commit;
- `DrFailbackSessionVO` and all maintained schema paths include acceptance,
  failure phase/component, driver exit, baseline-file, and worker-liveness
  columns;
- `FtctlDrStatusAnswer` and the KVM status wrapper preserve the typed FTCTL
  evidence through Agent serialization and validate its JSON types;
- `DrProtectionInfoTab.vue` exposes the bounded failure evidence in the existing
  failback lifecycle section with localized labels.

Build and test evidence from the WSL ext4 build clone:

```text
cloud-engine-schema: BUILD SUCCESS
cloud-core: BUILD SUCCESS
cloud-plugin-hypervisor-kvm: BUILD SUCCESS
cloud-plugin-integrations-disaster-recovery: BUILD SUCCESS
LibvirtFtctlCommandWrappersTest: 18 passed
DrFailbackLifecycleServiceImplTest + DrTargetResourceOwnershipServiceTest: 6 passed
```

The build uses only the changed Maven modules. Deployment must preserve the
management webapp's `WEB-INF` and apply the idempotent DB columns before the
management service is restarted.

### 15.1 Retry authority and transition-preflight correction

A failed pre-commit Failback intentionally leaves `Plan.state=ERROR` while the
committed operating authority remains TARGET. Both `DrFailbackPreflightService`
and `DrSourceIsolationPreflightService` must therefore use
`DrCurrentAuthorityResolver` instead of requiring the mutable Plan state string
to equal `FAILED_OVER`.

The retry gate is now:

```text
authority.side == TARGET
authority.consistent == true
latest Failback Run is terminal FAILED
no active Run exists
registered site and credential checks pass
source isolation and target Agent power probes pass
FTCTL transition-preflight-v2 returns one strict JSON object
```

This preserves the safety requirement: an ERROR operation never implies that
authority was lost, but inconsistent or uncommitted TARGET evidence is still
rejected. Added tests cover retry from `ERROR/TARGET`, inconsistent authority,
and progression into the target-serving probe. The focused Cloud test set now
passes 13 tests with no failures.

### 15.2 Build, deployment, and retry-readiness evidence

The Cloud deployment uses only changed module output. The management JAR,
KVM Agent JARs, and static UI assets were updated independently; the active
webapp retained `WEB-INF` and `/client/` returned HTTP 200 after restart.
The management service and all three KVM Agents are active.

FTCTL commit `24c932c6f52ebdd27e586970d2d16f016d8d686d` was built by GitHub
Actions run `30875939771`. RPM creation and artifact upload succeeded. The
development Release publication was rate-limited after artifact upload, so it
is not used as package-success evidence. The deployed RPM is
`ablestack_vm_ftctl-0.9.1-1.noarch` with SHA256:

```text
b0de8d20920ca130118a8f7e2ae53356bcb9ed75d6e335152fd3b2f55dd7ae48
```

The same RPM is installed on `10.10.32.1`, `10.10.32.2`, and
`10.10.32.3`; every FTCTL timer is active. Direct transition-preflight
validation now emits exactly one JSON object and no stderr. A missing runtime
projection is returned as typed `DR_TRANSITION_PREFLIGHT_STATE_MISSING`
instead of an Agent contract mismatch.

For the retained failed Failback test plan, the transient coordinator
projection was reconstructed only from the last committed Cloud cutover
session: TARGET authority, generation 10, powered-on target, acknowledged
engine commit, and checkpoint 10. The resulting Cloud API preflight reports
`ready=true` and `enginepreflightready=true`; action evaluation exposes
Failback as applicable and enabled. This is retry readiness, not a claim that
the next live reverse transfer has already succeeded.

## 16. Revision 2: initial reverse mode selection and full-stack preflight

### 16.1 Latest verified failure

The retry-readiness state in section 15.2 was insufficient. The next deployed
Failback for Plan `7889e625-371a-48f9-b553-54e311481170` created Run
`3a6357e1-9092-47c9-9d5b-e72f4a543fc0` and failed as follows:

| Layer | Verified result |
|---|---|
| UI/API | request accepted asynchronously |
| Cloud Run | id `136`, `FAILBACK/FAILED`, accepted by engine |
| Cloud FailbackSession | id `9`, `FAILED`, phase `REVERSE_TRANSFER` |
| Agent | target worker accepted the command |
| FTCTL requested intent | `failback-final` |
| FTCTL selected mode | `REVERSE_FINAL` |
| Reverse baseline | `MISSING_EXPECTED` |
| Engine result | mover exit `83`, `DR_REVERSE_BASELINE_REQUIRED` |
| Serving authority | TARGET retained |
| KVM target VM | Running on host 2 with both RBD disks |
| VMware VM | powered off |

The FailbackSession later recorded `DR_FAILBACK_DIRECTION_MISMATCH` because
`replication_direction` and `provider_pair` were still null. That code is a
secondary data-gate symptom. The root error is the FTCTL mode decision made
before reverse data evidence existed.

The deployed-host read-only probe established:

```text
current_effective_mode=REVERSE_FINAL
baseline=MISSING_EXPECTED
proposed_effective_mode=FULL_REVERSE_SEED
disk_map_validation=READY
source_disk_count=2
vmware_power=poweredOff
vddk_plugin=READY
```

Cloud site, credential, authority, fence, and forward-checkpoint preflight can
therefore pass while the first reverse data cycle remains impossible. The
preflight contract must include the reverse data-plane mode decision.

### 16.2 Normative flow

The corrected asynchronous flow is:

```text
UI getDrFailbackPreflight
  -> Cloud authority/site/credential/checkpoint validation
  -> Agent read-only reverse preflight
  -> FTCTL baseline probe and mode decision
  -> UI shows FULL_REVERSE_SEED or REVERSE_FINAL

UI startDrFailback
  -> Cloud commits Run + FailbackSession with direction and mode intent
  -> API returns Run UUID and UI closes modal
  -> Agent accepts start-only command
  -> FTCTL repeats the same decision under the plan lock
  -> FULL_REVERSE_SEED when baseline is missing
  -> VDDK flush/verification
  -> generation 1 baseline and reverse checkpoint commit
  -> FAILBACK_DATA_READY
  -> Cloud data gate
  -> stop KVM target -> start/validate VMware source
  -> authority commit -> forward protection resume
```

Operation intent and data mode are independent fields:

```text
operationIntent = FAILBACK_FINAL
requestedMode   = AUTO
effectiveMode   = FULL_REVERSE_SEED | REVERSE_FINAL
```

`FAILBACK_FINAL` means that this transfer belongs to a planned cutback. It
does not prove that an incremental baseline exists.

### 16.3 UI design

Update `ui/src/views/infra/dr/DrPlanList.vue` and the DR status components.

The Failback modal displays:

```text
Reverse synchronization: Initial full synchronization required
Transfer mode: Full reverse seed
Reverse baseline: Not created yet (expected)
Source disks: Ready (2)
VMware writer: Ready
Current operating site: ABLESTACK target
```

Rules:

1. `MISSING_EXPECTED + FULL_REVERSE_SEED` is ready, not an error.
2. `MISSING_EXPECTED + REVERSE_FINAL` is a blocking contract error.
3. Warn that the first Failback may transfer the full virtual disk size and
   show estimated bytes when available.
4. Disable Confirm for `INVALID`, source-disk failure, writer failure, or a
   powered-on VMware destination.
5. Close the modal immediately after `startDrFailback` returns a Run UUID and
   poll Run/protection cache asynchronously.
6. Display `Failback failed` separately from `Target site still serving`.
7. Preserve the FTCTL root error instead of a later direction mismatch.

Add Korean and English i18n keys for full reverse seed, reverse final delta,
expected missing baseline, invalid baseline, mode decision, disk/writer probes,
initial-seed byte estimate, and target-authority retention.

### 16.4 API design

Extend `DrFailbackPreflightResult` and `DrFailbackPreflightResponse` with:

```text
operationintent, requestedmode, effectivemode, modedecisioncode,
initialseedrequired, baselinefilestate, sourcediskprobestate,
sourcediskcount, targetwriterprobestate, targetvmpowerstate,
estimatedvirtualbytes
```

Example response:

```json
{
  "ready": true,
  "operationintent": "FAILBACK_FINAL",
  "requestedmode": "AUTO",
  "effectivemode": "FULL_REVERSE_SEED",
  "modedecisioncode": "INITIAL_REVERSE_BASELINE_ABSENT",
  "initialseedrequired": true,
  "baselinefilestate": "MISSING_EXPECTED",
  "sourcediskprobestate": "READY",
  "sourcediskcount": 2,
  "targetwriterprobestate": "READY",
  "targetvmpowerstate": "POWERED_OFF",
  "estimatedvirtualbytes": 161061273600
}
```

`getDrFailbackPreflight` remains bounded and read-only. It never copies data or
waits for a replication cycle. `startDrFailback` remains start-only and returns
the Run and Cloud FailbackSession UUIDs.

### 16.5 Backend design

#### 16.5.1 `DrFailbackPreflightServiceImpl`

Inject `DrReverseReplicationPreflightService`. After Cloud-owned authority,
site, credential, checkpoint, and transition validation, call the Agent
reverse preflight and merge its result.

| Baseline | Requested mode | Effective mode | Ready |
|---|---|---|---|
| missing | `AUTO` | `FULL_REVERSE_SEED` | yes |
| durable | `AUTO` | `REVERSE_FINAL` | yes |
| missing | explicit final/incremental | none | no |
| invalid | any non-forced mode | none | no |

Reject an Agent response missing direction, provider pair, effective mode, or
probe states. A timeout returns `DR_REVERSE_PREFLIGHT_UNAVAILABLE`; it must not
optimistically return ready.

#### 16.5.2 `DrOrchestratorImpl`

`createRequestedFailbackSession()` initializes queryable intent before Agent
dispatch:

```java
session.setReplicationDirection("KVM_TO_VMWARE");
session.setProviderPair("ABLESTACK_TO_VMWARE");
session.setOperationIntent("FAILBACK_FINAL");
session.setRequestedMode("AUTO");
session.setEffectiveMode(preflight.getEffectiveMode());
session.setModeDecisionCode(preflight.getModeDecisionCode());
session.setBaselineFileState(preflight.getBaselineFileState());
session.setSourceDiskProbeState(preflight.getSourceDiskProbeState());
session.setTargetWriterProbeState(preflight.getTargetWriterProbeState());
```

Normal Failback does not accept an arbitrary user-selected mode. Cloud derives
`AUTO`; administrator-forced reseed is a separate audited recovery operation.

#### 16.5.3 `DrFailbackLifecycleServiceImpl`

Lifecycle ordering is strict:

```text
REQUESTED -> DISPATCHED -> ENGINE_ACCEPTED -> REVERSE_PREFLIGHT
          -> REVERSE_SYNCING -> DATA_READY -> DATA_GATE_READY
          -> power transition and authority commit
```

Required changes:

- do not evaluate `DrFailbackDataGateService` before `DATA_READY`;
- merge FTCTL data evidence before gate evaluation;
- make terminal failure idempotent and first-error-wins;
- never overwrite a typed engine error with a later direction mismatch;
- preserve TARGET authority for every failure before Cloud commit;
- retry before generation 1 remains a full seed;
- never stop KVM or start VMware before durable write proof.

#### 16.5.4 `DrFailbackDataGateServiceImpl`

Add an explicit state guard:

```java
if (!DATA_READY.equals(session.getState())) {
    return blocked("DR_FAILBACK_DATA_NOT_READY",
            "Reverse replication has not produced durable data");
}
```

Then retain direction, provider pair, baseline/tracker, writer, write
verification, and guest compatibility checks. This gate validates a completed
checkpoint; it does not choose the first-cycle mode.

#### 16.5.5 Projection and materialization

`FtctlDrRuntimeProjectionAdapter` persists requested/effective mode, decision
code, probes, worker exit, phase, and component. `accepted=true` means only
command acceptance.

`DrPlanReadinessValidator` and protection-view generation merge FTCTL probes
with Cloud-owned replica, VM, and volume rows. Operation-local
`target_storage_present=false` cannot erase active Cloud RBD volumes. Expose
the disagreement separately while retaining materialization.

### 16.6 Agent contract

Add a read-only pair instead of overloading ordinary status:

```text
FtctlDrReversePreflightCommand
FtctlDrReversePreflightAnswer
```

Command fields are `planUuid`, `operationIntent`, `requestedMode`,
`expectedActiveSide`, `expectedSourcePowerState`, and
`expectedTargetPowerState`. The KVM wrapper executes:

```text
ablestack-vm-ftctl dr-reverse-preflight --plan <uuid>
  --operation-intent failback-final --requested-mode auto --json
```

The answer matches the API fields and adds `replicationDirection` and
`providerPair`. `FtctlDrActionCommand` carries intent and mode independently;
the wrapper never translates `FAILBACK_FINAL` into `REVERSE_FINAL`.

`FtctlDrStatusAnswer` and `FtctlDrCycleSnapshot` preserve:

```text
requestedMode, effectiveMode, modeDecisionCode, baselineFileState,
replicationDirection, providerPair, sourceDiskProbeState,
targetWriterProbeState, baselineGeneration, writerState,
targetWritten, writeVerified
```

### 16.7 FTCTL boundary

The paired engine contract is document 448 revision 2. Cloud owns Run/session,
VM lifecycle, and authority commit. FTCTL owns baseline probing, cycle-mode
selection, RBD/QCOW2 tracking, VDDK transfer, durable checkpoint evidence, and
typed engine status.

FTCTL repeats the selector under the plan lock. If a baseline changed after UI
preflight, execution records the new safe decision instead of using stale
preflight data.

### 16.8 DB design

Extend `dr_failback_session`:

```sql
operation_intent          varchar(32)  NULL,
requested_mode            varchar(32)  NULL,
effective_mode            varchar(32)  NULL,
mode_decision_code        varchar(64)  NULL,
initial_seed_required     tinyint(1)   NULL,
source_disk_probe_state   varchar(32)  NULL,
source_disk_count         int          NULL,
target_writer_probe_state varchar(32)  NULL,
estimated_virtual_bytes   bigint       NULL
```

Existing direction, provider, baseline, tracker, writer, and write-verification
columns remain committed data evidence. Update in lockstep:

- `setup/db/create-schema.sql`;
- `engine/schema/src/main/resources/META-INF/db/schema-Europa-After.sql`;
- `schema-42200to42210.sql` and `schema-42210to42300.sql`;
- `DrFailbackSessionVO`, DAO mappings, response generation, and schema tests.

Upgrade scripts use idempotent column helpers. Secrets, passwords, raw stderr,
and host-local paths are forbidden in columns and API responses.

`dr_sync_cycle` remains the per-cycle transfer record. The first successful
reverse seed stores `FULL_REVERSE_SEED`, baseline generation 1, per-disk bytes,
and the Failback Run id.

### 16.9 Error precedence

Converge errors in this order:

1. FTCTL transfer or durability error;
2. Agent transport/contract error;
3. Cloud data-gate error after `DATA_READY`;
4. lifecycle power, boot, or commit error.

A lower-priority result cannot overwrite a persisted higher-priority terminal
error. Bounded details may retain both codes, but `error_code` stays the root
cause.

### 16.10 Verification plan

Automated coverage:

- API exposes effective mode and expected missing baseline;
- missing baseline plus `AUTO` is ready/full seed;
- explicit final plus missing baseline is blocked;
- Run and Session commit direction/mode before dispatch;
- Agent serializes intent and mode separately;
- early engine exit preserves the FTCTL root code;
- Data Gate is unreachable before `DATA_READY`;
- Cloud VM/volume rows survive empty operation checkpoint fields;
- UI closes after acceptance and polls asynchronously;
- UI shows full-seed warning and TARGET retention in dark mode.

Live acceptance:

1. preflight reports `MISSING_EXPECTED/FULL_REVERSE_SEED`;
2. first Failback writes both VMDKs and commits reverse generation 1;
3. verify known KVM-side data through isolated VMware boot;
4. make a small KVM-side change after returning authority safely;
5. next reverse cycle reports incremental/final changed bytes below virtual
   bytes;
6. complete authority commit and resumed VMware-to-KVM protection;
7. compare UI, API, DB, Agent, FTCTL, and both VM power states.

### 16.11 Recommended implementation priority

1. P0: FTCTL intent/mode selector and read-only reverse preflight.
2. P0: selector, preflight, and generation-1 self-tests.
3. P0: Agent preflight DTO/wrapper and action intent/mode fields.
4. P0: Cloud preflight merge and pre-dispatch session initialization.
5. P0: lifecycle `DATA_READY` guard and first-error-wins convergence.
6. P1: DB columns, VO/DAO/schema paths, and response fields.
7. P1: materialization/probe merge and UI readiness display.
8. P1: changed Maven module/UI builds and qemu Actions package.
9. P2: paired deployment, failed-session cleanup, initial full-seed retry.
10. P2: second-cycle incremental and complete round-trip acceptance.

### 16.12 Revision 2 AS-IS / TO-BE

| Layer | Error cause | AS-IS | TO-BE |
|---|---|---|---|
| UI | preflight lacks reverse mode evidence | ready without transfer feasibility | shows mode and probe readiness |
| API | site/fence checks only | no baseline or writer fields | reverse data-plane preflight contract |
| Backend | downstream conflates intent and mode | transfer fails after acceptance | persists `AUTO` decision and gates on data ready |
| Agent | no separate intent/mode | distinction is lost | separate fields and read-only preflight DTO |
| FTCTL | `failback-final` forces final mode | missing baseline exits `83` | missing selects `FULL_REVERSE_SEED` |
| DB | direction/mode null before transfer | direction mismatch masks root | evidence committed before dispatch |
| Projection | operation storage false overrides reality | active RBD appears absent | Cloud materialization retained |
| Errors | later gate overwrites engine failure | conflicting codes | first typed terminal error wins |
| Safety | no data-ready generation can be reached | retries repeat early failure | no power transition until generation 1 is durable |

## 17. Revision 4: Reverse Snapshot And Terminal Causality (2026-08-05)

Live Run `8e413a38-981a-4c64-8b93-f62140c6c986` proved that initial reverse
mode selection and live preflight were correct, then failed because
`dr_kvm_vmware_mover.sh` opened an immutable RBD snapshot without
`qemu-nbd --read-only`. FTCTL later published exit `86`, phase
`REVERSE_TRANSFER`, while Cloud had already recorded synthetic worker exit
`70` and the Failback session recorded a direction mismatch from a premature
data-gate evaluation.

Document 593 supersedes those terminalization rules. The first reverse seed
must use read-only snapshot attachment, engine terminal evidence outranks
watchdog and gate-derived evidence, and `DrFailbackDataGateServiceImpl` runs
only after the session reaches `DATA_READY`. TARGET authority remains retained
through all failures.
