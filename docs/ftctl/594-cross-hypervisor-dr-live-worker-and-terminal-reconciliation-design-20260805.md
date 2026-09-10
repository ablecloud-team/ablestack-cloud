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

# 594. Cross Hypervisor DR Live Worker And Terminal Reconciliation Design

- Date: 2026-08-05
- Status: implemented, built, deployed, and preflight verified
- Scope: UI, API, Cloud backend, Agent, FTCTL boundary, DB/cache, recovery
- Parent data contract: [588](588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md)
- Failback causality parent: [593](593-cross-hypervisor-dr-failback-reverse-rbd-readonly-and-terminal-causality-design-20260805.md)
- Engine companion: `ablestack-qemu-exec-tools/docs/ftctl/451-ftctl-dr-worker-identity-live-transfer-and-terminal-reconciliation-design-20260805.md`
- Post-transfer route correction: [595](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)

## 1. Objective

Prevent Cloud from terminalizing a live asynchronous Failback transfer because
one worker-identity observation is inconsistent. The control plane must keep a
Run non-terminal while transfer evidence advances, reconcile conflicting
observations without changing authority, and admit a retry only after the
original operation is terminal or safely canceled.

UI and API remain asynchronous. UI submits intent, Cloud persists acceptance,
Agent supervises FTCTL, FTCTL owns engine execution evidence, and Cloud projects
that evidence to API and UI.

## 2. Verified Incident And Preflight

The design is based on live Plan `7889e625-371a-48f9-b553-54e311481170` and
Failback Run `1d1a7766-dbc8-4394-8cfb-d559d59ff4d6`.

| Layer | Verified observation |
|---|---|
| UI/API | Plan projected `ERROR` after Run was closed as failed |
| Cloud DB | Run terminalized with `DR_TERMINAL_PUBLICATION_TIMEOUT` and `failback-worker-exited` |
| FTCTL state | Run remained `RUNNING`, phase `failback-transfer`, progress 55 percent |
| Worker | parent and mover process trees remained alive |
| Transfer | `dr_extent_patch.py`, read-only `qemu-nbd`, and VDDK `nbdkit` were alive |
| I/O | process counters advanced about 2.75 GB in 20 seconds |
| Authority | TARGET remained authoritative; no commit or rollback occurred |
| Data mode | `FULL_REVERSE_SEED`, approximately 150 GiB, because no reverse baseline existed |

A standalone Bash `BASHPID` and `/proc/<pid>/stat` probe passed. Therefore the
incident must not be attributed to command substitution alone. The reproduced
failure class is concurrent publication to shared mutable Run state: launcher,
worker, and status observation can overwrite each other's identity and terminal
fields without writer ownership or generation ordering.

## 3. Error Cause

### 3.1 FTCTL state ownership is ambiguous

`ftctl_dr_runtime_start_failback()` and
`ftctl_dr_runtime_failback_worker()` both publish worker PID and start ticks.
`ftctl_dr_runtime_emit_state_json()` also writes observation-derived fields.
`ftctl_dr_runtime_path_set()` atomically replaces a file per write, but a
read-copy-modify-rename sequence has no writer generation and remains
last-writer-wins across callers.

### 3.2 Cloud treats provisional observation as terminal evidence

`FtctlDrRuntimeProjectionAdapter.reconcileAcceptedRunFromStatus()` currently
routes retryable worker conditions, worker failure, or runtime error to
`failRunFromProjection()`. A transient identity conflict can therefore close
the Run before an engine terminal is published.

### 3.3 Retry eligibility uses only Cloud terminal state

After false terminalization, `DrPlanServiceImpl` no longer finds an active DB
Run. `DrPlanActionAvailabilityEvaluator` can then expose Failback again even
while the first VDDK transfer still writes to VMware.

## 4. Safety Invariants

1. `ACCEPTED`, `RUNNING`, and `RECONCILING` are non-terminal states.
2. A watchdog observation is not an engine terminal result.
3. Advancing transfer bytes, a fresh heartbeat, or an owned live process blocks
   failure terminalization.
4. TARGET authority is preserved until Failback data, guest, power, and commit
   gates all pass.
5. One Plan has at most one mutating engine operation, including a reconciled
   orphan not represented by an active Cloud Run.
6. UI never calls host runtime or FTCTL directly.
7. Agent relays typed evidence and does not invent a terminal result.
8. Cleanup is Run-owned, idempotent, and must prove process and endpoint drain.

## 5. Canonical State Model

```text
ACCEPTED -> RUNNING -> SUCCEEDED
                    -> FAILED
                    -> CANCELED
             |
             +-> RECONCILING -> RUNNING
                               -> SUCCEEDED
                               -> FAILED
                               -> CANCELED
```

`RECONCILING` means Cloud found conflicting or incomplete runtime evidence but
has not proven operation termination. It is not a failure and must not change
active site, protection authority, or Failback session terminal state.

### 5.1 Terminal acceptance predicate

Cloud may persist a terminal Run only when one predicate is true:

```text
ENGINE_TERMINAL:
  terminal_authoritative == true
  and terminal_run_uuid == accepted_run_uuid
  and terminal_version > persisted_terminal_version

DEAD_WORKER_TERMINAL:
  liveness == DEAD_CONFIRMED
  and transfer_activity != ADVANCING
  and owned_process_count == 0
  and runtime_endpoints_drained == true
  and the same result is observed for N consecutive samples

OPERATOR_CANCELED:
  cancel was accepted for the same Run
  and process/endpoints are drained
  and FTCTL publishes CANCELED terminal
```

Recommended `N` is 3 with two-second engine samples. Increasing a single grace
timeout is not an acceptable substitute for this predicate.

## 6. FTCTL Contract

Document 451 defines owner-specific journals and a pure status merge. Cloud and
Agent consume these typed fields:

| Field | Type | Meaning |
|---|---|---|
| `worker_identity_state` | enum | `UNPUBLISHED`, `MATCHED`, `CONFLICT` |
| `worker_liveness_state` | enum | `ALIVE`, `SUSPECT`, `DEAD_CONFIRMED`, `TERMINAL` |
| `worker_launch_nonce` | string | launcher-to-worker handshake nonce |
| `worker_generation` | long | immutable worker generation |
| `worker_pid` | long | worker-published PID |
| `worker_start_ticks` | long | worker-published process start ticks |
| `worker_heartbeat_at` | timestamp | latest worker heartbeat |
| `transfer_activity_state` | enum | `UNKNOWN`, `IDLE`, `ADVANCING`, `STALLED`, `COMPLETE` |
| `transfer_payload_bytes` | long | cumulative payload bytes |
| `owned_process_count` | int | live processes owned by the Run |
| `runtime_endpoints_drained` | boolean | NBD/VDDK endpoints are gone |
| `reconciliation_required` | boolean | observations conflict or are incomplete |
| `terminal_authoritative` | boolean | terminal came from terminal journal |
| `terminal_version` | long | monotonic terminal evidence version |

The existing `terminal_source`, PID, start ticks, and heartbeat fields remain
for compatibility. Consumers must prefer the new typed fields when present.

## 7. Agent Design

### 7.1 DTO

Extend `core/.../FtctlDrStatusAnswer.java` with the fields in section 6. Add
explicit getters and constructor parameters; do not derive one field from
another in the DTO.

### 7.2 KVM wrapper

Extend `LibvirtFtctlDrStatusCommandWrapper` to parse the fields from FTCTL JSON.
Unknown fields remain backward compatible. Missing new fields map to
`UNKNOWN`, not `FAILED`.

The wrapper must:

1. invoke status only;
2. preserve `run_uuid` and `operation` exactly;
3. relay terminal and liveness evidence unchanged;
4. never kill a process or synthesize a terminal result during a status call.

### 7.3 Polling

While a Run is active or reconciling, poll the same Run UUID. A replacement
Run must not be selected solely because Cloud previously persisted a false
terminal. Poll failures update observation health but do not terminalize the
engine operation.

## 8. Cloud Backend Design

### 8.1 Runtime observation classifier

Add a pure classifier near `FtctlDrRuntimeProjectionAdapter`:

```java
enum DrRuntimeObservationClass {
    ENGINE_TERMINAL,
    LIVE,
    RECONCILIATION_REQUIRED,
    DEAD_CONFIRMED,
    INCONCLUSIVE
}

DrRuntimeObservation classifyRuntimeObservation(
        DrRunVO run,
        FtctlDrStatusAnswer answer,
        JsonObject runtime,
        DrPlanRuntimeVO persistedRuntime);
```

Call it at the start of `reconcileAcceptedRunFromStatus()`, before
`isRetryableWorkerCondition()`, `isWorkerFailed()`, or `isRuntimeError()`.

Decision order:

1. authoritative terminal for the same Run;
2. advancing bytes or fresh worker heartbeat;
3. live owned process;
4. conflicting identity or stale observation;
5. repeated dead-and-drained proof;
6. inconclusive.

`LIVE` updates progress and returns. `RECONCILIATION_REQUIRED` persists a
non-terminal reconciliation record and returns. Only `ENGINE_TERMINAL` or
`DEAD_CONFIRMED` may call terminal projection methods.

### 8.2 Reconciliation service

Add `DrRuntimeReconciliationService` with transactional methods:

```java
void markRequired(long planId, long runId, DrRuntimeObservation observation);
void markLive(long planId, long runId, DrRuntimeObservation observation);
void acceptTerminal(long planId, long runId, DrRuntimeObservation observation);
boolean hasBlockingRuntime(long planId);
```

It performs compare-and-set by Plan id, Run UUID, and runtime revision. Stale
samples cannot overwrite a newer heartbeat, payload count, or terminal version.

### 8.3 Run and Failback session

- Do not set `DrRunVO.status=FAILED` from a single watchdog conflict.
- Do not close `DrFailbackSessionVO` while reconciliation is required.
- Keep the operation phase `failback-transfer` and the last valid progress.
- Preserve TARGET authority through `preserveFailedOverTargetAuthority()`.
- On authoritative `FAILBACK_DATA_READY`, continue the existing Cloud-owned
  guest, power, authority, and commit gates.

### 8.4 Orphan admission guard

`DrPlanServiceImpl.getActionEvaluation()` must consider both:

```text
active Cloud Run
OR reconciliation_required
OR FTCTL owned_process_count > 0 for the Plan operation
```

Add blocker `DR_ACTION_RUNTIME_RECONCILIATION_REQUIRED`. During this state all
mutating actions are disabled except `cancelCurrentRun` and safe status refresh.
Failback must not be admitted a second time.

### 8.5 Recovery of an already false-terminal Run

If Cloud Run is terminal but FTCTL reports the same Run as live:

1. do not reopen the immutable Run row;
2. create a reconciliation record linked to the original Run;
3. project Plan as `RECOVERING` with TARGET authority;
4. continue observing the original engine Run;
5. when it terminalizes, create an append-only corrective projection event;
6. require controlled cancellation and drain before any new Run is admitted.

This preserves audit history while preventing two writers.

## 9. API Design

Extend Plan protection view and Run responses with:

```text
currentoperationstate
reconciliationrequired
workeridentitystate
workerlivenessstate
workerheartbeatat
transferactivitystate
transferpayloadbytes
ownedprocesscount
terminalauthoritative
terminalsource
terminalversion
```

`listDrPlans`, `listDrRuns`, and the protection-view API return cached fields
from Cloud DB. They do not perform synchronous host calls. Action submission
continues to return an async job/accepted Run contract.

Action availability adds:

```json
{
  "allowed": false,
  "reasoncode": "DR_ACTION_RUNTIME_RECONCILIATION_REQUIRED",
  "message": "A previous DR operation is still being verified."
}
```

Concrete API targets are `DrProtectionViewResponse`, the Plan/Run response
builders used by `listDrPlans` and `listDrRuns`, and
`DrPlanServiceImpl.getActionEvaluation()`. `GetDrProtectionViewCmd` remains a
cached read. `RefreshDrProtectionViewCmd` remains asynchronous and may request
projection work, but its completion does not mean the engine operation itself
is terminal.

## 10. UI Design

### 10.1 State presentation

Map runtime state as follows:

| Runtime | UI status | User text |
|---|---|---|
| live full seed | `SYNCING` | `Reverse full replication is in progress` |
| live incremental | `SYNCING` | `Reverse incremental replication is in progress` |
| reconciliation | `RECOVERING` | `Verifying the current replication operation` |
| authoritative success | `READY` or next lifecycle state | existing success text |
| authoritative failure | `ERROR` | typed cause and remediation |

Do not show `Error` for `SUSPECT`, identity conflict, or a stale single sample.
Show last heartbeat, transferred bytes, and last update time in Protection
Information. Keep technical PID/tick data out of the normal operator view.

### 10.2 Action gating

Use API `actionavailability` only. If reconciliation is active, disable
Failback, Failover, Reprotect, Release Protection, Full Resync, and Test
Failover. Keep `Cancel current operation` only when the backend says it is safe.

### 10.3 Refresh

Poll the cached protection view while state is `SYNCING` or `RECOVERING`.
Update sections without clearing the page. UI must not invoke Agent/FTCTL or
request a synchronous runtime refresh.

Concrete UI targets are:

- `ui/src/views/infra/dr/DrPlanList.vue`: normalize the new cached fields,
  select active/reconciling Run, and keep adaptive protection-view polling;
- `ui/src/views/infra/dr/DrProtectionInfoTab.vue`: show the operator-level
  liveness, heartbeat, and payload summary;
- `ui/src/views/infra/dr/DrPlanOverview.vue` and
  `ui/src/utils/dr/planState.js`: classify reconciliation as recovery, not
  error;
- `ui/src/utils/dr/actionAvailability.js`: honor the backend reconciliation
  blocker without duplicating lifecycle rules in the browser.

## 11. DB Design

### 11.1 `dr_plan_runtime`

Add nullable/backward-compatible columns:

```sql
worker_identity_state       varchar(32)
worker_liveness_state       varchar(32)
worker_launch_nonce         varchar(64)
worker_generation           bigint unsigned
transfer_activity_state     varchar(32)
transfer_payload_bytes      bigint unsigned
owned_process_count         int unsigned
runtime_endpoints_drained   tinyint(1)
reconciliation_state        varchar(32)
reconciliation_run_id       bigint unsigned
reconciliation_checked      int unsigned default 0
terminal_source             varchar(32)
terminal_version            bigint unsigned
terminal_authoritative      tinyint(1)
runtime_revision            bigint unsigned not null default 0
```

Reuse the existing `active_worker_run_uuid`, `active_worker_pid`,
`active_worker_start_ticks`, and `worker_heartbeat_at` columns rather than
adding duplicate identity columns. Extend `DrPlanRuntimeVO` and its DAO update
path only for the genuinely new fields above.

Add index `(reconciliation_state, updated)` and use `runtime_revision` for
compare-and-set updates.

### 11.2 `dr_failback_session`

Add `reconciliation_state`, `worker_heartbeat_at`,
`transfer_payload_bytes`, `terminal_source`, `terminal_version`, and
`terminal_authoritative`. These fields are session audit data; they do not
replace per-Run `last_status_json`.

### 11.3 `dr_run`

Keep `last_status_json` as immutable raw evidence for each sample. Add
queryable `terminal_source`, `terminal_version`, and `terminal_authoritative`
so a watchdog result cannot overwrite a stronger engine terminal.

### 11.4 Upgrade scripts

Apply equivalent guarded changes to:

- `engine/schema/src/main/resources/META-INF/db/schema-42200to42210.sql`
- `engine/schema/src/main/resources/META-INF/db/schema-42210to42300.sql`
- `engine/schema/src/main/resources/META-INF/db/schema-Europa-After.sql`

Backfill existing active rows to `reconciliation_state='NONE'`. Do not rewrite
historical terminal results. A deploy-time reconciler examines only non-removed
Plans and records corrective events when live FTCTL evidence conflicts with a
historical false terminal.

## 12. Cache Contract

Bump the Protection View cache schema version. Invalidate cache when any of
these change: Run status, operation phase, reconciliation state, heartbeat,
payload bucket, terminal version, authority, or action availability.

Payload invalidation should use a bounded bucket, for example each 64 MiB, to
avoid cache churn while still showing visible progress.

Implement the snapshot additions in `DrProtectionViewServiceImpl` and expose
them through `DrProtectionViewResponse`. `DrProjectionScheduler` may refresh
the projection on its existing schedule; it must not synchronously wait for a
long-running mover.

## 13. Verification Design

### 13.1 FTCTL and Agent tests

- launcher/worker handshake identity remains stable;
- 1,000 concurrent status reads do not change journal content;
- long-running two-disk mover reports heartbeat and increasing bytes;
- identity conflict plus advancing bytes remains non-terminal;
- dead worker requires three dead-and-drained samples;
- Agent preserves unknown typed fields as `UNKNOWN`, never `FAILED`.

### 13.2 Backend tests

Add cases to `FtctlDrRuntimeProjectionAdapterTest` and
`DrPlanActionAvailabilityEvaluatorTest`:

- worker mismatch plus advancing bytes -> Run remains RUNNING;
- stale PID plus live owned process -> RECONCILING;
- authoritative terminal -> one terminal transition;
- watchdog terminal followed by engine terminal -> engine result wins;
- false-terminal DB Run plus live FTCTL Run -> retry blocked;
- TARGET authority remains unchanged throughout reconciliation;
- canceled-and-drained Run permits one later retry.

### 13.3 API and UI tests

- accepted request returns without waiting for transfer;
- `RECOVERING` is not rendered as Error;
- action menu blocks duplicate mutation during reconciliation;
- polling updates bytes without blanking the page;
- terminal message uses typed cause only after authoritative terminal.

### 13.4 Live preflight and acceptance

Before retest:

1. prove no stale Failback worker, NBD endpoint, VDDK process, lock, or partial
   VMware writer remains;
2. verify TARGET authority and KVM serving VM state;
3. verify VMware source remains powered off;
4. verify FTCTL, Agent, Cloud DB, and API agree on no active Run;
5. execute exactly one Failback;
6. sample heartbeat, bytes, process ownership, DB Run, and API state throughout
   the full reverse seed;
7. verify `FAILBACK_DATA_READY`, Cloud commit gates, VMware boot, and final
   SOURCE authority;
8. verify zero endpoint/process residue.

## 14. Implementation Priority

1. P0: FTCTL owner journals, worker handshake, heartbeat, and pure status read.
2. P0: Agent typed relay fields.
3. P0: backend observation classifier before terminal projection.
4. P0: orphan/reconciliation action blocker.
5. P0: controlled cleanup of the current orphaned transfer.
6. P1: DB schema, CAS reconciliation service, and cache version.
7. P1: API/UI state and action presentation.
8. P1: unit, integration, package, deployment, and live preflight.
9. P1: one clean full Failback retest.

## 15. AS-IS / TO-BE

| Layer | Error cause | AS-IS | TO-BE |
|---|---|---|---|
| UI | Cloud false terminal is trusted | live transfer shown as Error | SYNCING/RECOVERING until authoritative terminal |
| API | reconciliation evidence absent | caller cannot distinguish provisional state | typed liveness, bytes, reconciliation, terminal authority |
| Backend | one worker conflict closes Run | active copy terminalized as failed | observation classifier and multi-signal terminal predicate |
| Availability | only active DB Run blocks retry | duplicate Failback may be enabled | live engine or reconciliation also blocks mutation |
| Agent | incomplete typed relay | Cloud infers from weak fields | lossless typed status relay, no synthesized terminal |
| FTCTL | shared state has multiple writers | identity and status overwrite each other | owner journals, handshake, heartbeat, pure merge |
| DB | terminal strength not queryable | watchdog can become canonical | terminal source/version/authority plus reconciliation CAS |
| Authority | false failure can confuse lifecycle | TARGET remains operational but Plan is Error | TARGET explicitly preserved until commit gates pass |
| Cleanup | orphan transfer survives Cloud failure | unsafe retry and partial writer risk | Run-owned cancel, drain proof, then retry admission |

## 16. Completion And Operator Handoff

This design is complete when every layer derives one non-terminal live state
and one canonical terminal from the same Run UUID, with no duplicate transfer
and no premature authority change.

No operator action is required during implementation, build, deployment,
cleanup, and preflight. After those steps pass, the operator's next and only
test action is to execute Failback once from the prepared Plan.

## 17. Implementation and deployment verification

- Changed Maven modules built successfully from the WSL ext4 clone: `core`,
  `plugins/hypervisors/kvm`, and `plugins/integrations/disaster-recovery`.
- Automated verification passed: 18 KVM status-contract tests and 31 DR
  projection/evaluator tests.
- UI production build passed and the active management bundle contains the
  reconciliation-state marker.
- The changed Cloud classes were applied to the active management and Agent
  JARs; no full Cloud package replacement was performed.
- The active UI was updated without replacing the webapp root; `WEB-INF` was
  preserved and `/client/` returned HTTP 200 after deployment.
- The DB schema contains worker identity, liveness, transfer progress,
  reconciliation, endpoint drain, and terminal authority columns for runtime
  and Run records.
- The failed historical Run remains immutable for audit, while FTCTL cleanup
  and scheduler projection restored the Plan to `FAILED_OVER` with TARGET
  authority and cleared the stale canonical error.
- Live API preflight reports `ready=true`; authority, source runtime, target
  runtime, FTCTL transition, and reverse-data stages are all `READY`.
- Operator handoff: perform one normal Failback from Plan
  `7889e625-371a-48f9-b553-54e311481170`. Forced execution is not required.

## 18. Route Contract And Cloud Terminal Convergence Addendum

The live-worker correction succeeded: the subsequent reverse transfer reached
authoritative `FAILBACK_DATA_READY`. Cloud then rejected the valid route because
it compared `KVM_TO_VMWARE` topology with an `ABLESTACK_TO_VMWARE` provider
literal and closed only the FailbackSession. Document 595 supersedes this
document for route typing and Cloud lifecycle failure convergence. The next
operator Failback is deferred until document 595 and FTCTL companion 452 are
implemented, deployed together, and the stuck Run is cleaned through the
official convergence path.
