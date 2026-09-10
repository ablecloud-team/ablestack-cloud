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

# Cross Hypervisor DR Incremental Mode Decision And Cycle Projection Design

- Date: 2026-07-17
- Status: implemented and deployed; cycle-consistency correction in document
  560 is ready for live retest
- Scope: VMware source to ABLESTACK target continuous replication
- Affected layers: UI, Cloud API, Cloud backend, Mold Agent, FTCTL, Cloud DB
- Supersedes on conflict: incremental mode selection, completed-cycle projection,
  and normal cutover readiness portions of 503, 510, 521, 523, 525, 555,
  556, 557, and 558

## 1. Purpose

Linux Plan `297556ce-7e1c-4553-8a51-b6400a2e896a` and Windows Plan
`0987a837-549c-4457-9eef-3874e0ac9057` both reached a durable target and kept
their schedulers running. However, every cycle after the first seed was
recorded as `FULL_RESEED`. This document defines the corrective contract that
must make a scheduler-requested incremental cycle execute as CBT incremental,
project every completed cycle to Cloud, and prevent normal cutover until a
measured incremental cycle has been verified.

This is not a snapshot-retention correction. VMware CBT uses the committed
per-disk changeId as its baseline. A short-lived snapshot may be removed after
the cycle because the committed changeId and disk identity remain the next
cycle's durable baseline.

## 2. Live Evidence And Current Verdict

| Evidence | Linux | Windows | Meaning |
|---|---:|---:|---|
| Source VM ref | `vm-4486` | `vm-6429` | Source inventory resolved |
| Target VM | VM 248, 100 GiB | VM 249, 100 GiB + 50 GiB | Target materialized |
| Firmware and I/O | Secure Boot, `io.policy=io_uring`, iothreads | Secure Boot, `io.policy=io_uring`, iothreads | Target definition is usable |
| Sequence 1 | `FULL_SEED` | `FULL_SEED` | Initial seed is expected |
| Later completed cycles | `FULL_RESEED`, 100 GiB each | `FULL_RESEED`, 150 GiB each | Incremental execution failed |
| `incrementalVerified` | `false` | `false` | Incremental acceptance not met |
| FTCTL committed baseline | advancing changeId, `LOCAL_DURABLE`, generation > 0 | advancing changeIds, `LOCAL_DURABLE`, generation > 0 | A valid next-cycle baseline exists |
| Scheduler request | sequence > 1 is `incremental` | sequence > 1 is `incremental` | Scheduler policy is correct |
| Cloud cycle projection | generally completes | older rows can remain `TRANSFERRING` | Polling can miss a completed sequence |

Verdict: target materialization and full-copy durability are PASS, but
continuous incremental DR is FAIL. Test Failover and planned Failover are not
normal-path ready until this design is implemented and one measured
incremental or no-change cycle is projected consistently.

## 3. Confirmed Root Causes

### 3.1 FTCTL loses baseline metadata while constructing the execution plan

`lib/ftctl/dr_vmware_mover.sh` has the following current flow:

1. `ftctl_vmware_mover_disk_plan()` reads the committed source disk map.
2. It copies `previousChangeId` into each execution row.
3. It does not copy `baselineState`, `baselineGeneration`, or
   `lastSyncSequence`.
4. `ftctl_vmware_mover_resolve_requested_mode()` treats missing or zero
   `baselineGeneration` as an invalid baseline.
5. The function overwrites `CBT_INCREMENTAL` with `FULL_RESEED`.
6. The full-copy branch executes `qemu-img convert` and commits another valid
   baseline, but the next execution plan drops the same fields again.

The result is a self-repeating reseed loop caused by an internal row-contract
loss, not by invalid VMware CBT state.

### 3.2 Requested mode is overwritten instead of preserving intent

The current variable named `requested_mode` first represents Scheduler intent,
then is replaced with the resolver output. The journal therefore cannot tell
whether the Scheduler requested incremental and FTCTL automatically promoted
it to reseed. Operator history loses the distinction between policy and actual
execution.

### 3.3 Cloud projects only the current sequence

`FtctlDrRuntimeProjectionAdapter.projectRuntimeAuthority()` invokes
`projectSyncCycle()` only for `currentCheckpointSequence`. Completed metrics
are copied only when `latestCompletedCheckpointSequence` equals that current
sequence.

If sequence N+1 starts before Cloud polls the brief completion state of N,
Cloud sees:

```text
currentCheckpointSequence=N+1
latestCompletedCheckpointSequence=N
```

The restore-point projection correctly records N, but `dr_sync_cycle` N can
remain `TRANSFERRING` without metrics. Larger Windows cycles make this race
more visible.

### 3.4 Normal action eligibility does not prove incremental operation

`DrProtectionAuthorityServiceImpl` currently checks READY, RPO freshness,
Scheduler liveness, and the absence of current errors. It does not require a
completed `incremental_verified=true` checkpoint. Consequently
`getActionEligibility()` can expose Test Failover or normal Failover after only
full reseeds.

### 3.5 JSON helper failure is not typed

FTCTL logs contain a non-terminal Python `JSONDecodeError` after completed full
copies. The cycle can remain green while a helper consumed empty or malformed
JSON. Raw tracebacks make diagnosis difficult and allow partial telemetry such
as zero duration or estimated counters to look authoritative.

## 4. Normative Invariants

1. The committed per-disk map is the only baseline authority.
2. An execution row must preserve disk identity, changeId, baseline state,
   baseline generation, and last committed sequence.
3. Scheduler-requested mode is immutable for the lifetime of a cycle.
4. Effective mode is a separate, typed decision.
5. Missing metadata introduced by FTCTL itself must never silently cause an
   unbounded full-copy loop.
6. If any disk requires reseed, the whole VM reseeds atomically; mixed disk
   generations are forbidden.
7. One status response can project both the current sequence and a different
   latest-completed sequence.
8. A completed `dr_run` represents the accepted command lifecycle, not all
   later Scheduler cycles.
9. Display cache is never an eligibility authority.
10. Normal Test Failover and planned Failover require a locally durable,
    Cloud-projected, measured incremental or no-change checkpoint.
11. Emergency Failover remains possible through an explicit degraded/forced
    path using the last durable checkpoint; it must be separately confirmed
    and audited.

## 5. FTCTL Detailed Design

### 5.1 Preserve the complete disk baseline contract

File: `lib/ftctl/dr_vmware_mover.sh`

Change `ftctl_vmware_mover_disk_plan()` so every row contains:

```json
{
  "index": 0,
  "sourceDiskKey": "2000",
  "cbtDiskId": "2000",
  "previousChangeId": "52 ...",
  "baselineState": "LOCAL_DURABLE",
  "baselineGeneration": 6,
  "lastSyncSequence": 6,
  "diskIdentityHash": "sha256:...",
  "virtualBytes": 107374182400,
  "targetPath": "rbd/...",
  "targetFormat": "raw"
}
```

The builder must reject duplicate source disk keys, duplicate target paths,
and source/target disk-count divergence before opening VMware or target data.
`baselineGeneration` and `lastSyncSequence` remain integers, not strings.

### 5.2 Replace string mode mutation with a structured decision

Replace `ftctl_vmware_mover_resolve_requested_mode()` with
`ftctl_vmware_mover_resolve_cycle_mode(rows, scheduler_requested_mode)`.
It returns one canonical JSON object:

```json
{
  "requestedMode": "CBT_INCREMENTAL",
  "effectiveMode": "CBT_INCREMENTAL",
  "automaticReseed": false,
  "decisionCode": "BASELINE_VALID",
  "reseedReason": "",
  "invalidDisks": [],
  "baselineGeneration": 6
}
```

The caller uses separate shell variables:

```bash
scheduler_requested_mode="CBT_INCREMENTAL"
mode_decision="$(ftctl_vmware_mover_resolve_cycle_mode \
  "${rows}" "${scheduler_requested_mode}")"
effective_mode_request="$(jq -er '.effectiveMode' <<<"${mode_decision}")"
```

`scheduler_requested_mode` is written unchanged to the journal and metrics.
Only `effective_mode_request` selects full copy, no-change, or CBT patch code.

### 5.3 Typed baseline validation

Add `ftctl_vmware_mover_validate_baseline_contract()` with these reasons:

| Decision code | Meaning | Action |
|---|---|---|
| `BASELINE_VALID` | all disks have a matching durable baseline | CBT query |
| `MISSING_CHANGE_ID` | committed disk has no changeId | one automatic whole-VM reseed |
| `MISSING_BASELINE_GENERATION` | generation absent or zero | one automatic whole-VM reseed |
| `BASELINE_NOT_LOCAL_DURABLE` | previous cycle did not commit | stop before copy unless explicit recovery |
| `DISK_IDENTITY_CHANGED` | source disk key/identity changed | one audited reseed |
| `DISK_SET_CHANGED` | disk count or membership changed | one audited reseed |
| `SOURCE_HARDWARE_CHANGED` | protected hardware fingerprint changed | one audited reseed |
| `OPERATOR_REQUESTED` | operator explicitly requested reseed | reseed |

`BASELINE_NOT_LOCAL_DURABLE` is not automatically promoted because it may
hide an interrupted target write. It returns a typed recovery-required error.

### 5.4 Automatic reseed circuit breaker

Add to Plan-local Scheduler state:

```json
{
  "consecutiveAutomaticReseedCount": 0,
  "lastReseedReason": "",
  "lastReseedBaselineGeneration": 0,
  "lastDiskIdentityHash": "sha256:..."
}
```

Rules:

1. One automatic reseed is allowed for a new, typed baseline defect.
2. A successful `CBT_INCREMENTAL` or `NO_CHANGE` resets the counter to zero.
3. A second automatic reseed with the same generation, reason, and disk
   identity fails before source open with `DR_CBT_RESEED_LOOP_DETECTED`.
4. An explicit operator reseed is recorded separately and does not masquerade
   as automatic recovery.

This prevents a metadata regression from repeatedly transferring entire
virtual disks every RPO interval.

### 5.5 Journal and checkpoint fields

Extend cycle journal, completed checkpoint, and `dr-status --json` with:

```text
current_checkpoint_requested_mode
current_checkpoint_effective_mode
current_checkpoint_mode_decision_code
current_checkpoint_automatic_reseed
current_checkpoint_invalid_baseline_disk_count
latest_completed_requested_mode
latest_completed_effective_mode
latest_completed_mode_decision_code
latest_completed_reseed_reason
latest_completed_automatic_reseed
latest_completed_invalid_baseline_disk_count
consecutive_automatic_reseed_count
```

The existing byte, duration, baseline generation, cycle token, and
`incremental_verified` fields remain mandatory for a completed checkpoint.

### 5.6 Strict JSON read boundary

Add one shared helper, for example
`ftctl_vmware_mover_read_json_object(path, error_code, context)`:

1. require a non-empty regular file;
2. validate with `jq -e 'type == "object"'` or a bounded Python parser;
3. validate required keys before field extraction;
4. return a typed code such as `DR_CBT_QUERY_PAYLOAD_INVALID` or
   `DR_VMWARE_SNAPSHOT_PAYLOAD_INVALID`;
5. persist a concise context string and payload file path;
6. never emit a raw Python traceback into a successful cycle.

Full-copy duration must be measured around `qemu-img convert`. Full-copy bytes
may remain `metricsEstimated=true`, but `durationMs=0` is not accepted after a
non-empty transfer. CBT incremental metrics remain measured and must set
`metricsEstimated=false`.

## 6. Scheduler Design

`ftctl_dr_scheduler_cycle_type()` continues to express policy only:

```text
sequence 1 -> FULL_SEED
sequence > 1 -> CBT_INCREMENTAL
operator recovery -> FULL_RESEED
```

The Scheduler writes requested mode before dispatch. The mover writes its
structured decision and actual mode after baseline evaluation. The Scheduler
must not clear decision or reseed fields until Cloud has observed the completed
checkpoint generation.

The next cycle may start immediately; status must therefore retain both
`current_checkpoint_*` and `latest_completed_*` snapshots atomically.

## 7. Agent Contract

Files:

- `core/.../FtctlDrStatusAnswer.java`
- `plugins/hypervisors/kvm/.../LibvirtFtctlDrStatusCommandWrapper.java`

Add typed fields and getters/setters for the new requested-mode and decision
properties. The wrapper must:

1. accept only documented enum values;
2. parse booleans and non-negative integers strictly;
3. reject an effective incremental checkpoint without
   `incremental_verified=true`;
4. preserve current and latest-completed fields independently;
5. return a bounded typed Agent error when FTCTL status is malformed.

The Agent remains a transport boundary. It does not decide whether reseed or
cutover is safe.

## 8. Cloud Backend Projection

File:
`FtctlDrRuntimeProjectionAdapter.java`

### 8.1 Split current and completed projection

Replace the single current-only call with:

```java
projectCurrentCycle(plan, projectionRun, status);
projectLatestCompletedCycle(plan, projectionRun, status);
projectLatestCompletedRestorePoint(plan, projectionRun, status);
```

`projectCurrentCycle` upserts `currentCheckpointSequence` and current state.
`projectLatestCompletedCycle` independently upserts
`latestCompletedCheckpointSequence`, even when it differs from current.

### 8.2 Common idempotent upsert

Both paths call:

```java
upsertSyncCycle(planId, sequence, engineRunUuid, Consumer<DrSyncCycleVO> patch)
```

The unique identity is `(plan_id, sequence)`. `engine_run_uuid` records the
first producer that established the canonical row and does not create an alias.
A completed
row is monotonic:

- `LOCAL_DURABLE` cannot regress to `TRANSFERRING`;
- non-null measured metrics cannot be replaced with null or estimates;
- a higher baseline generation cannot regress;
- duplicate status polls are no-ops after equivalent data is present.

### 8.3 Reconcile previously missed completed rows

During projection, if a READY `dr_restore_point` exists for the same Plan and
sequence while `dr_sync_cycle` remains non-terminal, update the
cycle from typed restore-point/checkpoint data. Do not infer
`incremental_verified=true`; copy only persisted evidence.

### 8.4 Runtime authority fields

`dr_plan_runtime` remains current-state authority and gains:

```text
consecutive_automatic_reseed_count
last_mode_decision_code
latest_completed_cycle_sequence
latest_completed_incremental_verified
```

`dr_run` remains command lifecycle only. `dr_plan_view_cache` remains a
display-only materialized view.

## 9. Cloud DB Design

Use a forward-only upgrade script following the repository's schema version
and idempotent `INFORMATION_SCHEMA` checks.

Add to `dr_sync_cycle`:

```sql
automatic_reseed tinyint(1) DEFAULT NULL,
mode_decision_code varchar(128) DEFAULT NULL,
invalid_baseline_disk_count int unsigned DEFAULT NULL
```

Add to `dr_plan_runtime`:

```sql
consecutive_automatic_reseed_count int unsigned NOT NULL DEFAULT 0,
last_mode_decision_code varchar(128) DEFAULT NULL,
latest_completed_cycle_sequence bigint unsigned DEFAULT NULL,
latest_completed_incremental_verified tinyint(1) DEFAULT NULL
```

Do not add an opaque decision JSON column. Per-disk details remain in the
Plan-local FTCTL journal; Cloud persists the typed aggregate needed for API,
readiness, and audit.

Backfill is idempotent:

1. join active `dr_sync_cycle` rows to READY restore points by Plan and sequence;
2. fill terminal state, effective mode, commit state, generation, byte metrics,
   timestamps, and verification fields only when the restore-point field is
   non-null;
3. never manufacture requested mode or incremental verification;
4. leave irreconcilable rows visible for operator diagnosis.

The Plan-sequence key is unique. The Plan/Run/sequence index remains non-unique
for producer-oriented diagnostics.

## 10. API Design

Extend checkpoint responses with:

```text
requestedmode
effectivemode
automaticreseed
modedecisioncode
reseedreason
invalidbaselinediskcount
incrementalverified
```

Extend Plan authority response with the latest completed sequence,
incremental verification, and consecutive automatic reseed count.

Action eligibility must expose reason codes, not only booleans. Keep the
existing boolean map for compatibility and add an `actioneligibilityreasons`
map. Relevant codes are:

```text
NO_VERIFIED_INCREMENTAL_CHECKPOINT
RESEED_LOOP_DETECTED
CYCLE_PROJECTION_LAG
RPO_OVERDUE
SCHEDULER_NOT_RUNNING
TARGET_NOT_READY
```

Normal `testFailover` and planned `failover` require:

```text
target materialized
AND scheduler/control healthy
AND current RPO within limit
AND no current failure
AND latest completed checkpoint LOCAL_DURABLE
AND latest completed incrementalVerified = true
```

Emergency Failover uses the existing `force` transport only after a separate
degraded confirmation. It may use the last durable full/reseed checkpoint, but
must record the missing incremental proof and accepted RPO risk in the Run
request and event audit.

## 11. UI Design

Files:

- `DrSyncCheckpointsTab.vue`
- DR Plan action eligibility/confirmation components
- Korean and English locale files

Add columns:

| Column | Display |
|---|---|
| Requested mode | Incremental, Full Seed, or Full ReSeed |
| Actual mode | CBT Incremental, No Change, Full Seed, or Full ReSeed |
| Decision | localized reason; hidden when baseline is valid |
| Transfer ratio | changed bytes / virtual bytes |
| Evidence | Measured or Estimated |

An unexpected automatic Full ReSeed is a warning. Repeated reseed circuit
breaker state is an error. Raw codes remain available in a tooltip or detail
row, while primary text is localized.

Disable normal Test Failover and planned Failover when verified incremental
proof is absent. The tooltip explains that initial replication is complete but
incremental protection has not yet been verified. Emergency Failover remains
an explicit dangerous action and is never presented as normal readiness.

No UI request waits for a replication cycle. Polling continues to read Cloud
authority/cache asynchronously.

## 12. Readiness Service Design

Change `DrProtectionAuthorityServiceImpl` so `normalCutoverReady` additionally
requires `latestCompletedIncrementalVerified=true`. The value comes from
Cloud-projected typed state, not status JSON or display cache.

Add focused methods:

```java
boolean hasVerifiedIncrementalCheckpoint(long planId);
List<String> getNormalCutoverBlockingReasons(long planId);
```

`NO_CHANGE` qualifies only when FTCTL actually executed a valid CBT query from
the committed previous changeId and set `incrementalVerified=true`.

## 13. Error Contract

| Code | Owner | Retryable | Meaning |
|---|---|---:|---|
| `DR_CBT_BASELINE_NOT_DURABLE` | FTCTL | no | Previous baseline was not atomically committed |
| `DR_CBT_RESEED_LOOP_DETECTED` | FTCTL | no | Same automatic reseed condition recurred |
| `DR_CBT_QUERY_PAYLOAD_INVALID` | FTCTL | bounded | CBT helper returned empty or invalid JSON |
| `DR_CBT_DISK_IDENTITY_CHANGED` | FTCTL | after reseed | Protected disk identity changed |
| `DR_CYCLE_PROJECTION_LAG` | Backend | yes | Latest completed engine cycle is not projected |
| `DR_INCREMENTAL_NOT_VERIFIED` | Backend/API | yes | Normal cutover proof is absent |

Error messages are concise and bounded. Full diagnostic paths and per-disk
evidence stay in FTCTL journals/events.

## 14. Test Design

### 14.1 FTCTL unit and shell tests

1. Valid multi-disk durable rows preserve all baseline fields and choose
   `CBT_INCREMENTAL`.
2. Missing generation chooses one `FULL_RESEED` with a typed reason.
3. One invalid disk forces an atomic whole-VM reseed.
4. A second identical automatic reseed fails before source open.
5. Disk identity or set change produces the correct reason.
6. Empty or malformed CBT JSON produces a typed error without traceback.
7. No-change CBT query is measured and verified.
8. Full-copy duration is non-zero when bytes were transferred.

### 14.2 Agent tests

1. Current N+1 and latest completed N survive deserialization independently.
2. Invalid enum, negative count, or type mismatch is rejected.
3. New fields remain backward compatible when absent from an older FTCTL.

### 14.3 Backend and DB tests

1. Status with current N+1 and latest completed N finalizes N and upserts N+1.
2. Duplicate projection is idempotent.
3. A completed row cannot regress to transferring.
4. Restore-point reconciliation fills an older missed cycle without inventing
   verification.
5. Normal cutover is false before verified incremental and true afterward.
6. Forced emergency failover remains separate and audited.
7. Migration and backfill are repeat-safe.

### 14.4 UI tests

1. Requested and actual modes are displayed separately.
2. Unexpected Full ReSeed has a warning and localized reason.
3. Transfer ratio uses virtual and changed bytes safely.
4. Normal cutover actions show the correct blocking reason.
5. Refresh remains asynchronous and preserves last-good information.

## 15. Live Preflight And Acceptance Plan

The current environment already supplied sufficient non-destructive preflight
evidence: source CBT is enabled, per-disk changeIds advance, durable baseline
generation is positive, and Scheduler requests incremental. No additional
source mutation is required during design.

After implementation and deployment:

1. Use one Linux and one Windows Plan with a completed initial seed.
2. Make a small, controlled guest write on each source.
3. Observe the next RPO cycle.
4. Require `requestedMode=CBT_INCREMENTAL`.
5. Require actual mode `CBT_INCREMENTAL` or `NO_CHANGE`.
6. Require `incrementalVerified=true` and `metricsEstimated=false`.
7. Require changed/read/written bytes materially below virtual bytes for the
   controlled write.
8. Require baseline generation and changeId to advance exactly once.
9. Require no `JSONDecodeError` or raw traceback.
10. Require FTCTL checkpoint, Agent answer, `dr_plan_runtime`,
    `dr_sync_cycle`, restore point, API, and UI to agree on sequence and mode.
11. Require normal Test Failover eligibility to be false before this proof and
    true after it, assuming target and RPO are otherwise healthy.

## 16. Implementation Order

1. FTCTL disk-row contract and structured mode decision.
2. FTCTL circuit breaker, strict JSON boundary, journal/status fields.
3. FTCTL tests and GitHub Actions package.
4. Agent answer and wrapper typed fields.
5. DB migration, VO/DAO fields, and idempotent repair path.
6. Backend current/latest-completed dual projection.
7. Backend readiness and action reason codes.
8. API response fields.
9. UI history and eligibility presentation.
10. Changed-module Maven tests/build, UI build, deployment, DB migration, and
    live Linux/Windows acceptance.

## 17. AS-IS / TO-BE Summary

| Layer | AS-IS | TO-BE |
|---|---|---|
| UI | shows only actual mode and allows normal cutover without incremental proof | shows requested/actual mode, reseed reason, transfer ratio, and blocks normal cutover until verified |
| API | boolean eligibility has no blocking reason; checkpoint omits decision intent | typed decision fields plus compatibility booleans and blocking reason codes |
| Backend | projects only current sequence and can miss the latest completed cycle | independently and idempotently projects current and latest-completed cycles |
| Agent | transports effective/metric fields but not requested/decision fields | transports both mode intent and actual decision with strict types |
| FTCTL | drops baseline generation, overwrites requested mode, and repeats full reseed | preserves baseline contract, separates requested/effective mode, and stops repeated reseed |
| DB | has cycle metrics but no explicit automatic-reseed decision or runtime incremental proof | stores typed decision aggregate, latest verified sequence, and reseed-loop state |
| Readiness | READY/RPO/Scheduler can enable normal cutover after full reseed only | normal cutover additionally requires a verified incremental or valid no-change checkpoint |
| Diagnostics | malformed helper JSON can emit a traceback while the cycle looks green | strict JSON boundary returns a typed error and preserves diagnostic evidence |

## 18. Completion Criteria

This correction is complete only when all of the following are true:

1. Linux and Windows both complete a measured CBT incremental or verified
   no-change cycle after initial seed.
2. No unrequested repeated full reseed occurs.
3. Every completed engine sequence has a terminal `dr_sync_cycle` row.
4. FTCTL, Agent, DB, API, and UI agree on requested mode, actual mode,
   generation, bytes, and verification.
5. Normal Test Failover/planned Failover is gated by verified incremental
   evidence; emergency Failover remains explicit and audited.
6. All focused tests, changed Maven modules, UI build, deployment checks, DB
   migration, and live acceptance pass.

## 19. Implementation And Build Result (2026-07-18)

### 19.1 Implemented scope

1. FTCTL now resolves a structured cycle decision without overwriting the
   requested mode. The decision includes requested/effective mode, automatic
   reseed, decision code, reseed reason, invalid disk count, baseline
   generation, and disk identity hash.
2. VMware mover disk rows preserve durable baseline state, generation, last
   sequence, source disk identity, and disk-set hash through planning and
   execution.
3. A repeated automatic full reseed is stopped before source disk transfer and
   is surfaced as `DR_CBT_RESEED_LOOP_DETECTED`. A non-durable baseline is
   blocked as `DR_CBT_BASELINE_NOT_DURABLE`.
4. FTCTL runtime status transports current-cycle and latest-completed-cycle
   decision fields independently.
5. Agent status parsing and Cloud projection persist current N+1 and completed
   N independently, without regressing a terminal cycle to transferring.
6. Runtime authority and cutover readiness now require verified incremental
   evidence and reject an automatic-reseed loop.
7. Restore point, cycle, runtime, response, schema, and upgrade contracts store
   the new decision and verification fields.
8. The checkpoint UI displays requested mode, effective mode, automatic reseed,
   reseed reason, and transfer ratio with Korean and English labels.

### 19.2 Verification completed before deployment

| Verification | Result |
|---|---|
| FTCTL shell syntax | PASS |
| FTCTL automatic-reseed decision self-test | PASS |
| Cloud DR projection/service tests | PASS (`18` tests) |
| KVM Agent FTCTL wrapper tests | PASS (`11` tests) |
| Changed Cloud Maven reactor package | PASS (`39` modules) |
| UI locale JSON validation | PASS |
| UI production build | PASS |

The live Linux/Windows RPO acceptance in section 15 remains a post-deployment
retest criterion. Deployment success alone must not be reported as proof that
incremental replication is operational.

### 19.3 Deployment and cleanup result

| Item | Result |
|---|---|
| FTCTL source | commit `566ac78` on `feature/ftctl-cloud-integration` |
| FTCTL GitHub Actions | PASS, run `29599230776` |
| FTCTL package | `ablestack_vm_ftctl-0.9.1-1.noarch`, replaced on `10.10.32.1/2/3` |
| Package SHA-256 | `29dceae4e31ccc64428d5fdd26b901d8de3d0a9e6594a9d620fa1eb274eb94a7` |
| Agent patch | Core answer and KVM status wrapper deployed to all three hosts; class hashes match the build output |
| Cloud patch | Changed DR and Core classes deployed to the active management JAR; class hashes match the build output |
| DB migration | New runtime, cycle, and restore-point decision columns applied idempotently |
| UI deployment | Static assets updated under the active webapp while preserving `WEB-INF`; `/client/` returns HTTP 200 |
| Service state | `mold` and all `mold-agent` services active; all FTCTL timers active |
| Compatibility check | No recent `NoSuchMethod`, `NoSuchField`, class-linkage, or unknown-column errors |

Retest cleanup removed the two previous plans, their target VMs and volumes,
plan runtime directories, transfer processes, and source-side FTCTL snapshots.
It also removed one stale `dr_plan_runtime` projection row that still claimed
the deleted Windows Plan worker was running even though no host process or
runtime directory existed. The preserved historical SYNC and RELEASE runs are
all terminal `SUCCEEDED` records.
After timer restart, every host reported zero plan runtime directories and zero
DR transfer processes. This is the required clean starting state for a new
Linux and Windows acceptance run.

### 19.4 Remaining live acceptance boundary

The deployment is ready for retest, but the implementation is not accepted as
operational incremental DR until the section 15 Linux and Windows runs prove
all of the following in the same cycle chain:

1. Initial seed completes once.
2. The next RPO cycle requests and executes `CBT_INCREMENTAL`, or reports a
   verified `NO_CHANGE` when there are no changed extents.
3. A second automatic `FULL_RESEED` for the same unresolved baseline defect is
   rejected before data transfer.
4. Engine status, Agent answer, DB rows, API response, and UI history agree on
   sequence, requested/effective mode, reason, generation, and byte metrics.
5. Normal cutover eligibility becomes true only after verified incremental
   evidence and all other target-readiness checks pass.

## 20. Post-Deployment Cycle Consistency Correction (2026-07-18)

The Linux and Windows acceptance runs proved continuous CBT/no-change operation,
but live audit found two non-blocking consistency defects:

1. historical completed rows can contain the baseline generation of an adjacent
   sequence;
2. `cycles/*.json` retains the pre-transfer `CBT_INCREMENTAL` decision even
   when all final disk results and `cycle-metrics/*.json` are `NO_CHANGE`.

The first defect is caused by `dr-status` reopening the mutable state file for
each field and Cloud allowing terminal rows to be rewritten. The second is
caused by separate pre-transfer and post-transfer mode writers.

The normative correction is specified in
`560-cross-hypervisor-dr-cycle-snapshot-consistency-design-20260718.md`. It
adds:

- same-directory atomic FTCTL state publication and one-read status snapshots;
- one post-disk effective-mode aggregator;
- typed Agent current/completed cycle objects with coherence validation;
- coherence validation before projection, completed-row immutability, and
  last-good retention;
- retained exact-sequence evidence-based correction for the three known
  historical rows;
- DB/API/UI projection-integrity state.

This correction is required before treating historical checkpoint audit data
as fully accepted and before advancing to Test Failover validation. Retention
count enforcement and display-only nested runtime freshness remain separate
follow-up items and are not folded into this consistency patch.
