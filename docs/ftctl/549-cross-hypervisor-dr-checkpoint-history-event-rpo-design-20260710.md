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

# Cross Hypervisor DR Checkpoint, History, Event, And RPO Design

Date: 2026-07-10

## 1. Decision And Scope

Cross Hypervisor DR does not provide point-in-time recovery. A completed DR
replication cycle proves that the mutable target replica reached a durable
checkpoint. It does not preserve an independently recoverable historical disk
image.

Therefore:

- User-facing `restore point` terminology is removed.
- The user-facing term is `synchronization checkpoint`.
- A user cannot choose an old checkpoint for test failover or failover.
- FTCTL atomically locks the latest durable checkpoint when an action starts.
- Existing `dr_restore_point` storage and old API names remain temporarily for
  compatibility, but they are not product terminology.
- No v2k migration lifecycle is invoked by this design.

This design covers only the affected layers: UI, API, Cloud backend, Agent,
FTCTL, and DB. It also corrects the observed event growth and five-minute RPO
scheduling error.

The same semantics are mandatory for all four directions:

```text
ABLESTACK -> VMware
VMware    -> VMware
ABLESTACK -> ABLESTACK
VMware    -> ABLESTACK
```

Provider-specific drivers may produce different manifests, but every direction
reports one latest durable synchronization checkpoint, never a user-selectable
historical recovery point.

## 2. Live Evidence And Root Causes

Plan `a72d9404-1978-43df-8256-edab6c2aa9e7` proved that initial and continuous
replication work, but also exposed the following contract problems.

| Evidence | Result |
| --- | --- |
| Initial SYNC run | `SUCCEEDED`; all five run steps succeeded |
| Target VM | Stopped, target-ready, `UEFI/SECURE`, `io_uring`, iothreads enabled |
| VMware source | EFI, Secure Boot, CBT enabled, VDDK source open ready |
| FTCTL | Scheduler running; incremental checkpoint sequence 7 completed |
| Source snapshot cleanup | vCenter snapshot tree empty after cycle completion |
| Checkpoint rows | 9 rows for sequences 1 through 6; sequences 1, 4, and 5 duplicated |
| Events | 773 rows; 767 were `PROJECTION_REFRESH` |
| RPO target | 300 seconds |
| Sequence 6 to 7 durable interval | 476 seconds |

The source snapshot cleanup result is important: the current records are
checkpoint evidence, not historical recovery artifacts.

### 2.1 Checkpoint Duplication

`FtctlDrRuntimeProjectionAdapter.upsertRestorePointFromStatus` performs a
read-then-insert operation without a plan-scoped DB lock or a unique active
checkpoint key. Concurrent projection calls can both observe no row and insert
the same `source_snapshot_ref`.

### 2.2 Event Amplification

`listDrEvents`, `listDrRuns`, and other detail calls refresh projection. A
successful projection writes `PROJECTION_REFRESH`, so viewing or polling the
screen creates more events. The API then returns all events and the UI renders
all of them.

### 2.3 Incorrect Checkpoint Selector Boundary

The UI sends a Cloud checkpoint UUID. The API converts it to an internal DB
`Long`. `FtctlDrActionCommand.restorePointId` sends that number to the Agent,
while FTCTL expects a runtime reference such as `ftctl:<plan>:<sequence>`.

This selector must be removed from the user contract. A numeric Cloud DB ID
must never be passed to FTCTL.

### 2.4 RPO Schedule Drift

`dr_scheduler.sh` sleeps the full interval after each transfer completes.
Actual durable interval is:

```text
transfer duration + configured interval
```

The VMware cycle also writes `sourceCheckpointAt` and `targetDurableAt` from
the same post-transfer timestamp, which reports zero transfer lag.

## 3. User Information Architecture

### 3.1 Top-Level Detail Tabs

```text
Details | Protection Topology | Replica | History | Events
```

`Restore Points` and `Runs` are removed as separate top-level tabs.

### 3.2 History Tab

`DrPlanHistoryTab.vue` uses a segmented control:

```text
[ Synchronization History ] [ Operation History ]
```

Synchronization History is the default. It shows one row per unique completed
replication cycle:

| Column | Meaning |
| --- | --- |
| Sequence | Monotonic cycle sequence within a run |
| Completed | Target durable completion time |
| Cycle | Full or incremental |
| Transfer duration | Source checkpoint to target durable duration |
| Current checkpoint age | Current time minus source checkpoint time |
| RPO target | Plan RPO |
| RPO result | Compliant or breached |
| State | Completed or failed |

Operation History shows asynchronous operator actions: SYNC start, pause,
resume, test failover, test cleanup, failover, failback, reprotect, and release.
The current Plan has one row because only one operator SYNC action was created.
This table becomes valuable over the full DR lifecycle, but it does not need a
separate top-level tab.

### 3.3 Protection Topology Tab

Move `DrTopology` out of `DrPlanOverview.vue` into
`DrPlanTopologyTab.vue`. This tab shows:

- source site and source VM;
- current active side;
- target site, replica VM, network, and storage;
- protection state;
- scheduler activity;
- latest checkpoint sequence and age;
- target RPO compliance.

The Details tab keeps resource fields, KPI values, current action progress,
and operator-visible errors.

### 3.4 Events Tab

- Default: latest 20 significant events.
- Page size choices: 10, 20, and 50.
- Default filter excludes successful unchanged projection refreshes.
- Filters: severity, event class, operation run, and time range.
- Details JSON stays collapsed.

### 3.5 Action Dialogs

Remove the checkpoint selector from test failover and failover dialogs.
Display a read-only statement instead:

```text
The latest completed synchronization checkpoint will be used.
Latest checkpoint: #7 / 2026-07-10 14:57:38 / RPO compliant
```

## 4. UI Code Design

Affected files:

```text
ui/src/views/infra/dr/DrPlanList.vue
ui/src/views/infra/dr/DrPlanOverview.vue
ui/src/views/infra/dr/DrPlanHistoryTab.vue                 (new)
ui/src/views/infra/dr/DrSyncCheckpointsTable.vue           (new)
ui/src/views/infra/dr/DrOperationRunsTable.vue             (new)
ui/src/views/infra/dr/DrPlanTopologyTab.vue                (new)
ui/src/views/infra/dr/DrEventsTab.vue
ui/src/api/dr.js
ui/public/locales/en.json
ui/public/locales/ko_KR.json
```

`DrPlanList.vue` tab structure:

```vue
<a-tab-pane key="details" :tab="$t('label.details')">...</a-tab-pane>
<a-tab-pane key="topology" :tab="$t('label.dr.protection.topology')">
  <dr-plan-topology-tab ... />
</a-tab-pane>
<a-tab-pane key="replica" :tab="$t('label.dr.replica')">...</a-tab-pane>
<a-tab-pane key="history" :tab="$t('label.history')">
  <dr-plan-history-tab :planId="detailPlan.id" />
</a-tab-pane>
<a-tab-pane key="events" :tab="$t('label.events')">...</a-tab-pane>
```

`DrPlanHistoryTab.vue` lazily fetches only the active segment. It does not poll
terminal operation history. Synchronization history polls only while the
scheduler is transferring or while the tab is visible.

`DrEventsTab.vue` request:

```js
listDrEvents({
  planid: this.planId,
  page: this.page,
  pagesize: this.pageSize,
  excludeprojectionrefresh: true,
  severity: this.severity || undefined
})
```

Compatibility components `DrRestorePointsTab.vue` and `DrRunsTab.vue` may
delegate to the new table components for one release, but routes and labels use
the new information architecture.

## 5. API Contract

### 5.1 New Checkpoint API

Add:

```text
listDrSyncCheckpoints
```

Parameters:

```text
planid       required UUID
page         default 1
pagesize     default 20, maximum 100
state        optional
rpocompliant optional boolean
```

Response fields:

```json
{
  "id": "cloud-checkpoint-uuid",
  "planid": "plan-uuid",
  "runid": "run-uuid",
  "checkpointref": "ftctl:<plan>:<run>:7",
  "sequence": 7,
  "cycletype": "INCREMENTAL",
  "sourcecheckpointat": "...",
  "targetdurableat": "...",
  "transferlagseconds": 176,
  "checkpointageseconds": 2,
  "rpotargetseconds": 300,
  "rpocompliant": true,
  "state": "COMPLETED"
}
```

`listDrRestorePoints` remains a deprecated alias for one release. It returns
the same checkpoint rows, sets a deprecation warning, and must not describe
historical recovery.

### 5.2 Operation History

`listDrRuns` must honor `BaseListCmd` page and page-size parameters and return
newest first. No new run is created for each scheduler cycle.

### 5.3 Events

`listDrEvents` adds:

```text
eventclass
severity
excludeprojectionrefresh default true
page/pagesize default 1/20
```

The DAO returns newest first and includes total count for pagination.

### 5.4 Plan State Axes

`DrPlanResponse` exposes two independent fields:

```text
protectionstate: NEW | READY | DEGRADED | FAILED_OVER | ERROR
syncactivity: IDLE | WAITING | TRANSFERRING | PAUSED | ERROR
```

`effectivestate` remains as a compatibility field and maps to
`protectionstate`. Continuous scheduler execution no longer makes a ready Plan
look permanently `SYNCING`.

### 5.5 Action Contract

For FTCTL_DR actions:

- `restorepointid` is deprecated and hidden from the UI.
- An old client value is accepted only long enough to validate that it belongs
  to the Plan and resolves to the latest durable checkpoint.
- A non-latest value is rejected with
  `DR_HISTORICAL_CHECKPOINT_UNSUPPORTED`.
- Cloud resolves an internal checkpoint reference and sends a String
  `checkpointRef`, never a numeric DB ID.
- Normal UI requests omit the selector; FTCTL locks the latest completed
  checkpoint atomically.

## 6. Backend Design

Add services:

```text
com.cloud.dr.checkpoint.DrCheckpointProjectionService
com.cloud.dr.checkpoint.DrCheckpointSelector
com.cloud.dr.event.DrEventPersistencePolicy
com.cloud.dr.state.DrProtectionStateProjector
```

### 6.1 Idempotent Checkpoint Projection

```java
@DB
public DrRestorePointVO projectCheckpoint(long planId, CheckpointStatus status) {
    DrPlanVO plan = drPlanDao.acquireInLockTable(planId);
    try {
        String ref = canonicalCheckpointRef(plan, status);
        byte[] hash = sha256(ref);
        DrRestorePointVO checkpoint = dao.findActiveByPlanAndRefHash(planId, hash);
        if (checkpoint == null) {
            checkpoint = new DrRestorePointVO(planId, "REPLICATION_CHECKPOINT");
            checkpoint.setCheckpointRefHash(hash);
        }
        applyStatus(checkpoint, status);
        persistOrUpdate(checkpoint);
        enforceRetention(plan, planSchedule(plan).getRetentionCount());
        return checkpoint;
    } finally {
        drPlanDao.releaseFromLockTable(planId);
    }
}
```

Canonical reference V2:

```text
ftctl:<planUuid>:<runUuid>:<sequence>
```

Legacy `ftctl:<planUuid>:<sequence>` references remain readable.

### 6.2 Latest Checkpoint Selection

```java
CheckpointSelection selectLatestDurable(long planId) {
    DrRestorePointVO checkpoint = dao.findLatestTargetReadyByPlanId(planId);
    require(checkpoint != null && checkpoint.getState().equals("COMPLETED"));
    require(noActiveTransferOrFinalSyncCompleted(planId));
    return selection(checkpoint.getCheckpointRef(), checkpoint.getSequence());
}
```

The selection is internal evidence. It is not a user-selectable recovery
point.

### 6.3 Event Persistence Policy

Do not persist successful unchanged projection polls. Persist only:

- operation lifecycle transitions;
- protection-state transitions;
- checkpoint completion or failure, once per sequence;
- RPO breach and recovery;
- target materialization transitions;
- warnings and errors.

The event fingerprint excludes volatile fields such as current age,
`events_offset`, poll time, and raw event arrays.

```java
boolean shouldPersist(EventCandidate candidate, DrEventVO latest) {
    if (candidate.isErrorOrWarning()) return true;
    if (candidate.isCheckpointCompletion()) return !sameSequence(candidate, latest);
    if (candidate.isProjectionRefresh()) return false;
    return !candidate.stableFingerprint().equals(latestStableFingerprint(latest));
}
```

## 7. Agent Contract

Affected files:

```text
core/src/main/java/com/cloud/agent/api/FtctlDrActionCommand.java
plugins/hypervisors/kvm/.../LibvirtFtctlDrActionCommandWrapper.java
```

Changes:

```java
private String checkpointRef;       // internal, non-secret
@Deprecated
private Long restorePointId;
```

`resolveRestorePointSelector` becomes `resolveCheckpointSelector` and never
falls back to `String.valueOf(restorePointId)`. If `checkpointRef` is absent,
the CLI receives no selector and performs latest-checkpoint locking.

No Agent polling or synchronous waiting is added. The action remains accepted
asynchronously and status is projected through `dr-status`.

## 8. FTCTL Design

Affected files:

```text
lib/ftctl/dr_scheduler.sh
lib/ftctl/dr_runtime.sh
lib/ftctl/dr_vmware.sh
lib/ftctl/dr_ablestack.sh
bin/ablestack_vm_ftctl.sh
bin/ablestack_vm_ftctl_selftest.sh
```

### 8.1 Deadline-Based Scheduling

The configured interval is measured between cycle starts, not after cycle
completion.

```bash
next_due_epoch="$(date +%s)"
while true; do
  cycle_started_epoch="$(date +%s)"
  run_cycle
  cycle_finished_epoch="$(date +%s)"
  next_due_epoch=$((next_due_epoch + interval))
  schedule_lag=$((cycle_finished_epoch - next_due_epoch))
  sleep_seconds=$((next_due_epoch - cycle_finished_epoch))
  (( sleep_seconds > 0 )) && sleep_or_stop "${sleep_seconds}"
  (( schedule_lag > 0 )) && emit_rpo_breach "${schedule_lag}"
done
```

Cycles never overlap. When transfer duration exceeds the interval, the next
cycle starts immediately and status exposes the overrun.

### 8.2 Correct Time Semantics

```text
source_checkpoint_at = source snapshot/CBT anchor acquired time
target_durable_at = target write, flush, and verification completion time
transfer_lag_seconds = target_durable_at - source_checkpoint_at
checkpoint_age_seconds = now - source_checkpoint_at
rpo_compliant = checkpoint_age_seconds <= rpo_target_seconds
```

Do not assign one post-transfer timestamp to both source and target fields.

### 8.3 Checkpoint Status

Add preferred status fields:

```text
checkpoint_present
latest_checkpoint_ref
checkpoint_sequence
checkpoint_cycle_type
checkpoint_age_seconds
transfer_lag_seconds
rpo_compliant
schedule_lag_seconds
```

Old `restore_point_*` fields remain read-only aliases for one compatibility
release and are then removed.

### 8.4 Failover Boundary

`dr-test-failover` and `dr-failover` call one helper:

```bash
ftctl_dr_runtime_lock_latest_checkpoint PLAN RUN EXPECTED_REF
```

The helper:

1. acquires the Plan runtime lock;
2. ensures no partial checkpoint is selected;
3. reads the latest completed JSONL record;
4. optionally verifies Cloud's internal expected reference;
5. verifies manifest, checkpoint, target disk map, and target durability;
6. writes an immutable action selection file;
7. returns the locked checkpoint reference.

It does not roll the target disk backward.

## 9. DB Design And Migration

The physical table name `dr_restore_point` remains for compatibility. Its rows
are treated as synchronization checkpoint history.

Add nullable columns:

```sql
ALTER TABLE dr_restore_point
  ADD COLUMN run_id BIGINT UNSIGNED NULL,
  ADD COLUMN checkpoint_sequence BIGINT UNSIGNED NULL,
  ADD COLUMN checkpoint_cycle_type VARCHAR(32) NULL,
  ADD COLUMN checkpoint_ref_hash BINARY(32) NULL;
```

Backfill active rows:

```sql
UPDATE dr_restore_point rp
JOIN (SELECT plan_id, MAX(id) AS run_id
        FROM dr_run WHERE removed IS NULL GROUP BY plan_id) latest_run
  ON latest_run.plan_id = rp.plan_id
   SET rp.run_id = latest_run.run_id
 WHERE rp.removed IS NULL AND rp.run_id IS NULL;

UPDATE dr_restore_point
   SET checkpoint_sequence = CAST(SUBSTRING_INDEX(source_snapshot_ref, ':', -1) AS UNSIGNED),
       checkpoint_cycle_type = IF(CAST(SUBSTRING_INDEX(source_snapshot_ref, ':', -1) AS UNSIGNED) = 1,
                                  'full-seed', 'incremental')
 WHERE removed IS NULL AND checkpoint_sequence IS NULL
   AND source_snapshot_ref LIKE 'ftctl:%'
   AND SUBSTRING_INDEX(source_snapshot_ref, ':', -1) REGEXP '^[0-9]+$';

UPDATE dr_restore_point
   SET checkpoint_ref_hash = UNHEX(SHA2(source_snapshot_ref, 256))
 WHERE removed IS NULL AND source_snapshot_ref IS NOT NULL;
```

Before adding the unique index:

1. group active rows by `(plan_id, checkpoint_ref_hash)`;
2. keep the newest complete row;
3. repoint any artifacts to the survivor;
4. set duplicate `checkpoint_ref_hash` to NULL and soft-delete duplicates.

Then add:

```sql
CREATE UNIQUE INDEX uk_dr_restore_point__plan_checkpoint_hash
    ON dr_restore_point(plan_id, checkpoint_ref_hash);
CREATE INDEX i_dr_restore_point__plan_ready_removed
    ON dr_restore_point(plan_id, target_ready_at, removed);
```

Upgrade scripts must call `IDEMPOTENT_ADD_UNIQUE_KEY`, not
`IDEMPOTENT_ADD_KEY`, for the active checkpoint hash. The latter creates a
non-unique index and does not enforce the projection contract.

Soft delete clears `checkpoint_ref_hash` before setting `removed`, allowing a
new run to reuse a legacy reference safely. V2 references include run UUID and
normally never collide.

Event indexes:

```sql
CREATE INDEX i_dr_event__plan_created ON dr_event(plan_id, created, id);
CREATE INDEX i_dr_event__run_created ON dr_event(run_id, created, id);
```

No Plan-state column migration is required. Protection state and sync activity
are derived from Plan, current run, and correlated runtime status.

## 10. Compatibility And Cleanup

- Existing API clients can call `listDrRestorePoints` for one release.
- Existing checkpoint rows are returned through the new response mapper.
- Existing action requests with a checkpoint ID cannot select history; only a
  latest-row match is accepted.
- Duplicate active rows are repaired by the DB migration before deployment.
- Existing `PROJECTION_REFRESH` rows may be retained as historical noise or
  removed in controlled batches after count verification.
- UI never displays `restore` or `recovery point` wording for checkpoint rows.

## 11. Validation Plan

### 11.1 Unit And Integration Tests

```text
DrCheckpointProjectionServiceTest.concurrentProjectionCreatesOneCheckpoint
DrCheckpointProjectionServiceTest.retentionKeepsConfiguredCount
DrCheckpointSelectorTest.rejectsHistoricalCheckpoint
DrCheckpointSelectorTest.resolvesLatestDurableReference
ListDrSyncCheckpointsCmdTest.paginatesNewestFirst
ListDrEventsCmdTest.defaultsToTwentyAndExcludesProjectionRefresh
DrEventPersistencePolicyTest.unchangedProjectionIsNotPersisted
LibvirtFtctlDrActionCommandWrapperTest.neverFallsBackToNumericDbId
DrProtectionStateProjectorTest.readyProtectionCanHaveTransferringActivity
```

FTCTL self-tests:

```text
scheduler start-to-start interval excludes transfer duration
scheduler overrun emits one RPO breach event
source and target checkpoint timestamps differ by transfer duration
latest checkpoint locking rejects partial cycle
legacy checkpoint reference remains readable
historical selection is not exposed to the user action path
```

### 11.2 Live Preflight

1. Run at least three continuous cycles with 300-second RPO.
2. Verify cycle start intervals are at most 300 seconds plus scheduler jitter.
3. Verify `transfer_lag_seconds` is non-zero and measured from the source
   anchor to target durability.
4. Verify `checkpoint_age_seconds` and `rpo_compliant` change correctly during
   transfer and waiting.
5. Verify one DB checkpoint row per FTCTL sequence.
6. Verify source VMware snapshot tree is empty after every completed cycle.
7. Open and refresh every detail tab; significant event count must not grow
   unless state changes.
8. Verify Events initially renders no more than 20 rows.
9. Verify History shows synchronization cycles and one operator SYNC run in
   separate segments.
10. Start test failover without a user checkpoint selector and verify the
    latest completed sequence is locked.

## 12. AS-IS / TO-BE Summary

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Product term | `Restore Point`, implying point-in-time recovery | `Synchronization Checkpoint`, explicitly not point-in-time recovery |
| History tabs | Restore Points and Runs are separate top-level tabs | One History tab with Synchronization and Operation segments |
| Operation count | One SYNC run appears incomplete to the user | Explain that scheduler cycles are synchronization history; operator actions remain operation history |
| User selection | Action dialog selects a checkpoint UUID | No user checkpoint selection; latest durable checkpoint is locked internally |
| Agent selector | Numeric Cloud DB ID can reach FTCTL | String checkpoint reference only; no numeric fallback |
| Duplicate rows | Concurrent projection creates duplicate sequence rows | Plan lock plus active reference hash uniqueness |
| Release cleanup query | Unbounded DAO lookup uses `Long.MAX_VALUE` as a page size and can return no rows | Explicit unbounded `Filter(..., null, null)` returns every active checkpoint for release/delete checks |
| Events | All events rendered; 767 projection events accumulated | Latest 20 significant events; unchanged projection polls are not persisted |
| Diagram | Topology is embedded below Details | Dedicated Protection Topology tab |
| Plan state | Ready protection can still persist/display `SYNCING` ambiguously | Protection state and sync activity are separate axes |
| Scheduler | Full interval begins after transfer completion | Start-to-start deadline scheduling |
| RPO timestamps | Source and target use the same post-transfer time | Source anchor and target durability are measured independently |
| Five-minute target | Durable interval observed at 476 seconds | Three-cycle live validation requires interval at or below target plus jitter |

## 13. Implementation Order

1. Add DB checkpoint columns, deduplicate active rows, and add indexes.
2. Add checkpoint projection, selector, retention, and event persistence
   services.
3. Add paged checkpoint/event APIs and compatibility aliases.
4. Remove user checkpoint selection and numeric Agent fallback.
5. Implement FTCTL deadline scheduling, correct timestamps, and latest
   checkpoint locking.
6. Separate protection state from sync activity in API responses.
7. Rebuild the Plan detail tabs and History segmented view.
8. Run unit, Maven module, UI, and FTCTL tests.
9. Deploy changed Cloud classes/UI and the GitHub Actions FTCTL package.
10. Repair current duplicate rows and projection noise, then run three RPO
    cycles before test failover.

## 14. Implementation And Deployment Result (2026-07-10)

### 14.1 Implemented Contract

| Layer | Before | Implemented result |
| --- | --- | --- |
| UI | Restore Points, Runs, embedded topology, and an unbounded event list were separate surfaces. | Added dedicated Protection Topology and History tabs. History separates synchronization checkpoints from operator actions, Events renders the latest 20 significant rows, and user checkpoint selection was removed. |
| API | Restore point compatibility API and numeric database identifiers were exposed to action flow. | Added paged `listDrSyncCheckpoints`, retained `listDrRestorePoints` as an alias, returned checkpoint run/sequence/type/reference fields, and made failover actions latest-checkpoint-only. |
| Backend | Target materialization and runtime projection could both create history rows. | Runtime projection is the single checkpoint writer, guarded by the Plan lock and SHA-256 reference hash. Materialization no longer creates a synthetic row. |
| Agent | A Cloud numeric restore point ID could be used as an FTCTL fallback. | `FtctlDrActionCommand` carries a string `checkpointRef`; the KVM wrapper never converts or falls back to a numeric Cloud ID. |
| FTCTL | Checkpoint references were Plan/sequence scoped and scheduler delay started after transfer. | References are `ftctl:<plan>:<run>:<sequence>`, legacy references remain readable, sequence resumes for a matching run, and the next cycle deadline is calculated from cycle start. |
| RPO | Source and durable target timestamps could both be captured after transfer. | ABLESTACK source time is captured before copy; VMware prefers the source snapshot anchor; target time is captured after transfer durability. |
| DB | Active duplicates were possible and the upgrade path initially requested a normal index. | Added run/sequence/cycle/hash columns, backfilled legacy FTCTL rows, deduplicated active references, and created a unique `(plan_id, checkpoint_ref_hash)` index. |
| Release | Unpaged active checkpoint lookup used `Long.MAX_VALUE`. | The DAO uses an explicit unbounded filter, so release cleanup and delete eligibility operate on the actual active row set. |

### 14.2 Build And Test Evidence

- Changed Cloud Maven reactor build passed for `core`, KVM, and Disaster
  Recovery modules with dependencies.
- `FtctlDrUnifiedActionAdapterTest` and
  `FtctlDrRuntimeProjectionAdapterTest` passed: 14 tests.
- The production UI build passed and emitted the
  `listDrSyncCheckpoints`, History, and Protection Topology markers.
- FTCTL Bash syntax validation passed.
- FTCTL selected tests passed for ABLESTACK seed, ABLESTACK/VMware scheduler
  checkpoints, test failover cleanup, planned failover, failback, reprotect,
  and VMware raw-over-NBD mover behavior.
- The repository-wide FTCTL self-test still stops in pre-existing HA
  reconcile and mover-unavailable fixtures that are outside this checkpoint
  change. The changed DR cases were therefore executed independently and
  completed successfully.

### 14.3 Deployment Evidence

- FTCTL commit: `79d28eb2970e446188528470f5de2a498fbe2753`.
- GitHub Actions run: `29076005135`.
- RPM build and Actions artifact upload succeeded. The workflow conclusion is
  failure only because the final draft Release attempted to replace a large
  dependency asset set; the run artifact records the expected source commit.
- Deployed RPM: `ablestack_vm_ftctl-0.9.1-1.noarch`.
- Deployed RPM SHA-256:
  `cdcc2f0832cc053df8214219dfc47b3e30973d62c471505a5f2806f8b553b30c`.
- Cloud deployment used changed classes only. UI deployment updated static
  assets under `/usr/share/cloudstack-management/webapp` and preserved
  `WEB-INF`.
- `mold`, every `mold-agent`, and every `ablestack-vm-ftctl.timer` were active
  after deployment. `/client/` returned HTTP 200.
- Live DB migration backup:
  `/root/dr_restore_point-before-checkpoint-history-20260710.sql`.

### 14.4 Retest Cleanup Result

The previous successful Plan `a72d9404-1978-43df-8256-edab6c2aa9e7` was
released through the supported asynchronous API. RELEASE run
`a7a03407-25c9-4914-828d-a80fec354965` completed `SUCCEEDED`. The target VM was
expunged, the Plan was deleted through `deleteDrPlan`, all synchronization
checkpoint rows were soft-deleted, and the Plan-specific FTCTL runtime path
was removed only after the scheduler process had stopped. Timers were then
re-enabled on all three hosts.

The environment is clean for a new Plan. The next validation must create a
fresh Plan and verify at least three checkpoint cycles before test failover.

## 15. Post-Deployment Correction: Cached Protection View And Completed Boundary

The fresh Plan `211c5a64-1d5b-4621-a752-f457e2437095` confirmed that data-plane
replication, target materialization, and repeated RPO cycles work. It also
proved that parts of this document's intended contract were not implemented
at their required boundary:

- Events UI sends `pagesize=20` without `page=1`, producing HTTP 431.
- get/list APIs still call `refreshPlanProjection()` synchronously.
- a five-second detail poll and route full-path watcher amplify Agent calls.
- unchanged projection reads continue to create `PROJECTION_REFRESH` events.
- FTCTL exposes current `checkpoint_sequence` without an independent latest
  completed sequence/reference, so Cloud can temporarily mark an in-progress
  sequence READY with the prior cycle's timestamps.

The previous dedicated Protection Topology and Replica tabs are superseded by
one `Protection Information` tab. The read path is now a versioned DB JSON
snapshot, while projection is owned by a background scheduler or explicit
asynchronous refresh job.

Sections 3.1, 3.3, 4, 5.3, 6, 7, 8.3, 9, 11, 12, and 14 of this document must
be read with the correction in the following normative design:

`550-cross-hypervisor-dr-protection-view-cache-and-completed-checkpoint-design-20260710.md`.

## 16. 2026-07-14 Synchronization History Transfer-Evidence Extension

The term `synchronization checkpoint` remains correct and still does not mean a
selectable point-in-time recovery image. Its history view is extended from a
timestamp/state list into replication evidence.

Each completed cycle must include actual/effective transfer mode, incremental
verification, virtual bytes, CBT changed bytes, actual source-read bytes,
target-written bytes, payload bytes, extent count, duration, throughput, and
actual RPO. Per-disk evidence is available by expanding the row.

The checkpoint sequence alone must not determine whether a cycle is shown as
incremental. The normative storage/API/UI contract is
`555-cross-hypervisor-dr-vmware-cbt-incremental-and-transfer-metrics-design-20260714.md`.
