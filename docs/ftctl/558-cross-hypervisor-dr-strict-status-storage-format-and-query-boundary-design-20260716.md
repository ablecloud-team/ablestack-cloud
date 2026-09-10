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

# Cross Hypervisor DR Strict Status, Storage Format, And Query Boundary Design

Date: 2026-07-16

Status: implemented and deployed; 2026-07-17 incremental mode/projection correction defined in 559

## 1. Purpose

This document closes five consistency gaps found after successful Rocky Linux
and Windows Server VMware-to-ABLESTACK replication tests:

1. FTCTL `dr-status --json` can emit Python boolean literals (`True`, `False`).
2. RBD replica disks are physically raw and `dr_replica_disk.format=raw`, while
   the linked Cloud `volumes.format` can remain `QCOW2`.
3. `listDrPlans` ignores its keyword, does not accept `id`, and does not apply
   server-side paging.
4. Plan status includes events from the global FTCTL log, including events
   owned by other VMs and Plans.
5. VMware source snapshot status preserves creation-time active fields after
   cleanup even when the vCenter snapshot tree is empty.

The correction applies to all four DR directions. Snapshot lifecycle logic is
provider-specific; the strict status, query, event, cache, and storage-format
contracts are common.

## 2. Read-Only Preflight Evidence

| Check | Observed result | Verdict |
|---|---|---|
| Strict parse of Rocky `dr-status --json` | about 9 KiB; `ConvertFrom-Json` failed at `True` | FAIL |
| Plan status from event offset zero | about 40 KiB; 100 events, 85 from another VM | FAIL |
| Signed `listDrPlans id=<Rocky UUID>` | both active Plans returned | FAIL |
| RBD physical and mapping format | RBD images and replica mappings are raw | PASS |
| Cloud volume format | three DR RBD volumes recorded as `QCOW2` | FAIL |
| VMware snapshot tree | empty for both tested source VMs | PASS cleanup |
| FTCTL source snapshot status | creation-time reference remains present | FAIL projection |

The checks were read-only. No source VM, target disk, snapshot, or DB row was
changed.

## 3. Normative Invariants

1. Every FTCTL JSON command writes one RFC 8259 object and passes
   `jq -e 'type == "object"'`.
2. Fields have one type for their lifetime; booleans never travel as display
   strings.
3. Plan status contains only that Plan's runtime and events.
4. Event payloads are bounded by count and bytes at every layer.
5. Cloud volume, replica disk, target URI, pool, and physical target formats
   agree before READY.
6. List APIs apply identity, filters, and paging in the DAO query.
7. Snapshot audit identity and active snapshot ownership are separate.
8. Malformed live status never replaces the last-good protection-view cache.
9. Test Failover and Failover fail closed on invalid/freshness, format, or
   snapshot-cleanup uncertainty.

## 4. Target Flow

```mermaid
sequenceDiagram
    participant UI as Cloud UI
    participant API as Cloud API
    participant BE as DR Backend
    participant DB as Cloud DB
    participant AG as Mold Agent
    participant FT as FTCTL
    participant VC as vCenter

    UI->>API: exact getDrPlan or paged listDrPlans
    API->>DB: bounded identity/filter query
    DB-->>UI: typed cached view through API
    BE->>AG: status(plan, run, cursor, limit)
    AG->>FT: dr-status --events-limit 0 --json
    FT->>FT: build and strict-validate one object
    FT-->>AG: Plan-only bounded status
    AG->>AG: strict parse, size/type/identity validation
    AG-->>BE: typed answer plus compact status
    BE->>DB: transactional projection and cache generation
    UI->>API: poll cached protection view
    FT->>VC: remove owned temporary snapshot
    VC-->>FT: result
    FT->>FT: lifecycle=CLEANED; clear active reference
```

## 5. FTCTL Code-Level Design

### 5.1 Canonical checkpoint types

Current `lib/ftctl/dr_scheduler.sh`
`ftctl_dr_scheduler_checkpoint_value()` uses Python `str(value)`. Replace it
with typed helpers:

```bash
ftctl_dr_checkpoint_string <path> <field>
ftctl_dr_checkpoint_integer <path> <field>
ftctl_dr_checkpoint_boolean <path> <field>
```

The Python implementation validates the expected type. Boolean output is
exactly `true` or `false`; integer output is base-10 digits. A present field of
the wrong type fails with `DR_CHECKPOINT_TYPE_INVALID` instead of contaminating
the runtime state.

`incrementalVerified` and `metricsEstimated` use the boolean helper. Byte,
extent, duration, sequence, and generation fields use the integer helper.

### 5.2 Runtime JSON writers

Add to `lib/ftctl/dr_runtime.sh`:

```bash
ftctl_dr_runtime_json_boolean_field() {
  local name="$1" value="${2-}"
  case "${value,,}" in
    true|1|yes) printf ',"%s":true' "$name" ;;
    false|0|no) printf ',"%s":false' "$name" ;;
    '') return 0 ;;
    *) return 65 ;;
  esac
}
```

Both latest-completed boolean fields use this helper. Raw `printf` of a value
loaded from a file is prohibited. The top-level command writes to a temporary
file, validates it with `jq -e`, then prints it. Failure returns a small valid
object with `error_code=DR_STATUS_JSON_INVALID`; invalid diagnostics remain in
a root-readable redacted file only.

### 5.3 Plan-local bounded events

`ftctl_dr_runtime_emit_events_since()` currently tails global
`${FTCTL_EVENTS_LOG}`. DR status must read:

```text
/run/ablestack-vm-ftctl/dr-runtime/plans/<plan-key>/events.jsonl
```

Every DR event includes `plan_uuid` and optional `run_uuid`. A global
operational copy may remain, but it is not a status source.

Add `--events-limit <0..100>` with default 20. Projection polling uses zero.
The response includes `events_offset`, `events_next_offset`,
`events_truncated`, and `events_invalid_count`. Invalid JSONL lines are counted
and skipped. The complete status response is limited to 256 KiB.

### 5.4 Snapshot lifecycle

Replace creation-oriented status in `lib/ftctl/dr_vmware_mover.sh` with:

```json
{
  "lifecycleState": "CLEANED",
  "ready": true,
  "vmRef": "vm-4486",
  "activeSnapshotRef": "",
  "activeSnapshotRefPresent": false,
  "lastSnapshotRef": "snapshot-7267",
  "lastSnapshotName": "ftctl-dr-...",
  "createdAtEpochMs": 0,
  "cleanedAtEpochMs": 0,
  "cleanupRequired": false,
  "cleanupErrorCode": "",
  "cleanupMessage": ""
}
```

States are `CREATING`, `ACTIVE`, `COMMITTED`, `CLEANUP_PENDING`, `CLEANED`,
and `CLEANUP_FAILED`. Replace unconditional snapshot removal `|| true` with:

1. bounded removal;
2. absence verification by owned name/ref;
3. atomic `CLEANED` write and active-ref clear;
4. typed cleanup failure and bounded retry;
5. no next cycle while an owned snapshot is cleanup-pending.

The CBT baseline remains committed per-disk changeId/generation, not a retained
VMware snapshot.

## 6. Mold Agent Code-Level Design

Applicable classes are `LibvirtFtctlDrActionCommandWrapper`,
`LibvirtFtctlDrStatusCommandWrapper`, `LibvirtFtctlDrCommandHelper`, and
`FtctlDrStatusAnswer`.

1. Projection status invokes `--events-limit 0`; explicit event reads are
   bounded.
2. Capture at most 256 KiB with a 10-second status timeout.
3. Parse with strict Gson `JsonReader.setLenient(false)`.
4. Require one top-level object and exact `plan_uuid`; validate requested
   `run_uuid` semantics.
5. Validate known booleans, integers, enums, and event ownership.
6. Return typed `DR_STATUS_INVALID_JSON`, `DR_STATUS_IDENTITY_MISMATCH`,
   `DR_STATUS_PAYLOAD_TOO_LARGE`, or `DR_STATUS_TYPE_MISMATCH`.
7. `Answer.details` is a short sentence; compact parsed JSON travels only in
   `statusJson`.

Tests include Python booleans, trailing objects, foreign events, oversized
payload, wrong identity, and valid zero-event status.

## 7. Cloud Backend Code-Level Design

### 7.1 Exact Plan search

Add `DrPlanSearchCriteria` with `id`, `keyword`, `state`, `sourceSiteId`,
`targetSiteId`, `startIndex`, and `pageSize`. Replace
`DrPlanService.listPlans()` in the list command with:

```java
Pair<List<DrPlanVO>, Integer> searchPlans(DrPlanSearchCriteria criteria);
```

`DrPlanDaoImpl` uses one `SearchBuilder` with `removed IS NULL` and optional
predicates. Keyword matches normalized name, description, or UUID. SQL ordering
is `created DESC, id DESC`; `Filter` applies paging.

### 7.2 Target format normalization

`DrTargetMaterializationServiceImpl.normalizeImportedVolume()` already fixes
type, device, state, size, pool, offering, path, and owner, but not format. Add:

```java
private Storage.ImageFormat expectedTargetFormat(
        DrResolvedDiskMapping disk, StoragePoolVO pool) {
    if (Storage.StoragePoolType.RBD.equals(pool.getPoolType()) ||
            "rbd".equalsIgnoreCase(disk.getTargetType())) {
        return Storage.ImageFormat.RAW;
    }
    return Storage.ImageFormat.valueOf(
        StringUtils.upperCase(disk.getTargetFormat()));
}
```

Pass the mapping into normalization, set/persist/reload/verify the format, and
run the same normalization immediately after a new `importVolume()`. Replica
READY is forbidden before verification.

`DrPlanReadinessValidator` adds blocking
`DR_TARGET_VOLUME_FORMAT_MISMATCH` with volume UUID, expected, and actual
format. Test Failover/Failover eligibility remains false until all disks pass.

### 7.3 Projection and last-good cache

`FtctlDrRuntimeProjectionAdapter.parseObject()` must not turn parse failure
into an empty object. Introduce:

```java
record DrStatusParseResult(JsonObject value, String errorCode,
        String message, boolean valid) {}
```

Invalid status does not update Plan/Run/Replica state or replace cache JSON.
It records a bounded `STATUS_REFRESH_FAILED` event and marks cache health
`DEGRADED` while preserving the last-good generation and timestamp. Valid
status projects typed snapshot lifecycle and verified disk format.

### 7.4 Event ownership

Cloud DB remains the UI event source. Accepted FTCTL events are persisted only
after Plan/Run ownership validation and an idempotency key such as
`(plan_id, run_id, source_cursor, event_type)`. Raw global events are never
persisted by projection polling.

## 8. Cloud API Design

`ListDrPlansCmd` adds `id`, `keyword`, `state`, optional source/target site IDs,
and inherited paging. `execute()` calls `searchPlans`, sets rows and total
count, and never loops over all active Plans.

Detail uses `getDrPlan(id)` and `getDrProtectionView(planId)`.
`listDrEvents` requires a Plan, defaults to 20, caps at 100, and orders newest
first. Host runtime/event arrays are excluded from Plan list responses.

## 9. Cloud UI Design

Applicable files include `ui/src/api/dr.js` and
`ui/src/views/infra/dr/DrPlanList.vue`.

1. List sends page, pageSize, keyword, and filters; pagination uses API count.
2. Detail calls `getDrPlan(detailId)`, not a fetch-all list.
3. Refresh failure retains `detailPlan` and `protectionSnapshot`, displaying
   `상태 갱신 지연` and last successful time.
4. Event table requests 20 rows and uses server paging; it ignores status event
   arrays.
5. Snapshot UI distinguishes active state, cleanup state/time, and audit ref.
6. Disk details show verified format and server eligibility disables actions on
   mismatch.
7. Polling remains asynchronous and cache-first; UI never calls Agent/FTCTL.

## 10. DB And Migration Design

Add an idempotent correction to the active Europa upgrade paths:

```sql
UPDATE volumes v
JOIN dr_replica_disk d
  ON d.target_volume_id = v.id AND d.removed IS NULL
JOIN storage_pool p
  ON p.id = v.pool_id AND p.removed IS NULL
SET v.format = 'RAW'
WHERE v.removed IS NULL
  AND UPPER(p.pool_type) = 'RBD'
  AND UPPER(COALESCE(v.format, '')) <> 'RAW';
```

Before applying, verify the actual pool-type representation. The join through
active `dr_replica_disk` prevents rewriting unrelated volumes. Apply the same
statement to `schema-42200to42210.sql`, `schema-42210to42300.sql`, and
`schema-Europa-After.sql` according to this branch's existing DR schema rule.

Use `EXPLAIN` for newest-first event queries; add composite
`dr_event(plan_id, created, id)` only if an equivalent index is not selected.
If absent, add cache health columns `last_refresh_error_code`,
`last_refresh_error_message`, and `last_refresh_failed_at`; they do not replace
the last-good snapshot/generation.

## 11. Failure And Eligibility Matrix

| Condition | Projection | UI | Test Failover |
|---|---|---|---|
| Invalid FTCTL JSON | retain cache; degraded | refresh delayed | blocked until fresh valid status |
| Foreign event | reject and audit | no foreign row | otherwise unaffected |
| Oversized status | reject and retain cache | payload warning | blocked |
| RBD format mismatch | replica not READY | expected/actual | blocked |
| Snapshot CLEANED | normal | last cleanup time | allowed if other gates pass |
| Snapshot cleanup pending/failed | typed warning/failure | cleanup status | blocked |
| List filter mismatch | API failure | retain current list | no mutation |

## 12. Validation Plan

### 12.1 Automated

- FTCTL syntax/selftests and `jq -e` for every status fixture.
- Plan A status cannot contain Plan B events; cursor/limit/invalid JSONL tests.
- Snapshot ACTIVE-to-CLEANED and cleanup retry tests.
- Agent strict JSON, size, type, identity, and ownership tests.
- Backend last-good cache preservation and RBD RAW normalization tests.
- API identity/filter/paging/count tests.
- UI exact detail, server paging, event limit, and non-destructive refresh tests.

### 12.2 Live acceptance

1. Migration precheck lists only active DR RBD target volumes.
2. Create fresh Rocky and Windows Plans.
3. Complete one `FULL_SEED` and one measured `CBT_INCREMENTAL` cycle each.
4. Strict-parse every sampled status; verify no foreign events and size below
   256 KiB.
5. Verify physical, mapping, replica, and Cloud volume formats are RAW.
6. Verify vCenter tree empty and lifecycle CLEANED after each cycle.
7. Verify exact Plan identity and paging through signed APIs.
8. Induce a malformed-status fixture and prove last-good UI data remains.
9. Only then enable Test Failover for Linux and Windows.

## 13. Implementation Order

1. FTCTL typed scalars and strict self-validation.
2. FTCTL Plan-local bounded events.
3. FTCTL verified snapshot lifecycle.
4. Agent strict size/type/identity boundary.
5. Cloud format normalization and readiness gate.
6. Backend typed parse result and last-good cache semantics.
7. DAO/service/API Plan search and event paging.
8. DB migration/index/cache-health changes.
9. UI server paging, exact detail, cache retention, lifecycle/format display.
10. Tests, builds, deployment verification, migration, cleanup, and fresh
    Linux/Windows acceptance.

## 14. Error Cause And AS-IS / TO-BE

| Layer | Root cause | AS-IS | TO-BE |
|---|---|---|---|
| UI | client owns filter/page and can clear on failure | fetch-all/local slice/stale wording | server paging, exact detail, last-good view |
| API | declared filters are ignored | all active Plans returned | DAO filters, identity, paging, count |
| Backend | parse failure becomes empty; format omitted | cache ambiguity and metadata drift | typed failure, retained cache, RAW guard |
| Agent | permissive unbounded output trust | malformed/wrong-owner status crosses boundary | strict size/type/identity validation |
| FTCTL | Python strings and global event tail | `True/False`, foreign events, stale snapshot ref | canonical JSON, Plan events, CLEANED lifecycle |
| DB | imported format not reconciled | Cloud QCOW2 while RBD/replica raw | scoped RAW migration and cache health |

## 15. Completion Criteria

- all status objects pass strict JSON parsing;
- no Plan status contains another Plan's event;
- list/detail APIs obey identity and paging;
- every RBD replica is RAW across all metadata and physical layers;
- snapshot lifecycle agrees with vCenter after cleanup;
- malformed live reads preserve the last-good UI view;
- Linux and Windows full plus incremental cycles pass; and
- Test Failover is enabled only after all new gates pass.

## 16. 2026-07-16 Deployment And Live Revalidation

### 16.1 Applied and verified

- FTCTL commits `10dac72398` and `e781d50ef` were built by GitHub Actions runs
  `29487350217` and `29488311624`. The second artifact SHA-256 was
  `f79a97fb6d364ba9d02cd4b1270bc1dc847efe6c16d7c296bef033066dbeb0a4`.
- `ablestack_vm_ftctl-0.9.1-1.noarch` was installed on 10.10.32.1/2/3.
  Agent and FTCTL timer services were active on all three hosts.
- Strict status output, Plan-local bounded events, and profile-less status JSON
  were verified on the installed scripts.
- Cloud manager/Agent changed classes and the UI bundle were deployed. The
  active webapp retained `WEB-INF`, `/client/` returned HTTP 200, and the
  non-map JavaScript bundles contained the new paging and snapshot-lifecycle
  markers.
- DB cache-health columns and the Plan/event query index were present. Active
  DR RBD target metadata was corrected consistently to `dr_replica_disk=raw`
  and `volumes=RAW`.
- Both target VMs retained KVM UEFI Secure Boot, SCSI controllers,
  `io.policy=io_uring`, and `iothreads=true`. Target NICs and all three RBD
  images existed at the expected sizes.
- The new snapshot lifecycle cleanup completed for the failed Linux cycle 54:
  `snapshot-7351` transitioned to `CLEANED`. No active FTCTL snapshot remained
  in the vCenter snapshot tree for either source VM.

### 16.2 Live acceptance result

| Plan | Last durable result | New-cycle result | Verdict |
|---|---|---|---|
| Rocky Linux `790bbe51-...` | checkpoint 53, `TARGET_READY`, RPO 4 seconds | cycle 54 blocked with `DR_CBT_RESEED_REQUIRED` because the committed changeId is absent | FAIL for continued protection and Test Failover readiness |
| Windows `36b9b5f4-...` | checkpoint 43, verified CBT incremental, 3,693,543,424 bytes | cycle 44 failed with `DR_CBT_PATCH_FAILED` | FAIL for continued protection and Test Failover readiness |

The Windows failure was reproduced without writes. Immediately after
`qemu-nbd --connect` the target NBD size was zero; after 50 ms it was the
expected 107,374,182,400 bytes. The extent patch helper therefore consumed a
transient zero-size device, not an undersized RBD image.

The APIs also exposed a precedence defect. Both Plans had persisted
`state=ERROR`, but `effectivestate=READY`, `executionready=true`, and empty
blocking reasons were returned from the retained target-ready snapshot. The
last completed Cloud run stayed `SUCCEEDED/RUNNING` although the continuous
scheduler had subsequently failed. A last-good cache is useful for display,
but it must not become current action authority.

### 16.3 Required follow-up design

1. **CBT baseline repair:** before an incremental cycle, validate a committed
   changeId per source disk. When missing, schedule an explicit full reseed,
   atomically commit the new baseline only after target durability, and resume
   incremental cycles. Never leave the Plan in an unrecoverable ERROR that
   requires editing runtime files.
2. **NBD readiness barrier:** after each `qemu-nbd`/`nbd-client` attach, poll
   both sysfs sectors and `blockdev --getsize64` until they equal the mapped
   target capacity. Use a bounded timeout, retry attach once, and emit
   `DR_TARGET_NBD_SIZE_NOT_READY` with observed/expected sizes. The patch helper
   must never run with size zero.
3. **Current-cycle authority:** compute effective state and action eligibility
   from the newest runtime cycle first. Current `ERROR`, stale RPO, dead worker,
   or stopped scheduler blocks Test Failover and normal Failover readiness even
   when a previous durable checkpoint exists. Emergency failover from a stale
   checkpoint must be an explicit degraded action with age confirmation.
4. **Projection continuity:** scheduler cycles after the initial Cloud run must
   update a cycle/run projection row or a dedicated protection-runtime record.
   Do not leave the completed initial run showing `workerstate=RUNNING` while
   the current cycle is failed.
5. **Service recovery:** Agent/package restart reconciliation must inspect
   profiles and live PIDs, mark stale `RUNNING` state as `STOPPED`, and restart
   eligible continuous schedulers through the same asynchronous control path.
6. **Cache health semantics:** preserve last-good display JSON, but publish the
   current runtime error and freshness separately. Eligibility must never be
   derived solely from cached target materialization.

### 16.4 Updated AS-IS / TO-BE

| Area | AS-IS observed live | TO-BE acceptance condition |
|---|---|---|
| Linux CBT baseline | missing committed changeId permanently blocks cycle 54 | automatic full reseed then measured incremental success |
| Windows NBD attach | size is zero for about 50 ms and patch starts immediately | bounded size-ready barrier before any write |
| Effective state | Plan ERROR is masked by cached READY | current ERROR/RPO/worker state has authority |
| Run history | initial run is SUCCEEDED while later scheduler cycle failed | each cycle is projected with terminal state and metrics |
| Restart recovery | state says RUNNING without a live scheduler PID | reconciler marks stale state and restarts asynchronously |
| Snapshot cleanup | old status could retain an active reference | terminal lifecycle agrees with the vCenter tree |

Linux and Windows Test Failover remain disabled for acceptance purposes until
one repaired full/reseed cycle and one subsequent measured CBT incremental
cycle complete for each Plan within the configured five-minute RPO.

## 17. Follow-Up Scope And Normative Priority

This section turns the live findings in section 16 into an implementation
contract. It is normative for the next correction and takes priority where
older documents model a completed Cloud Run as the current continuous
replication state.

The correction applies to the Cloud UI, API, backend projection, Mold Agent
FTCTL boundary, FTCTL VMware mover and scheduler, and Cloud DB runtime model.
It does not change the successful storage-copy paths. VMware access continues
to use vCenter credentials and the verified v2k-compatible VDDK/govc runtime;
ESXi credentials are not added to the DR contract.

## 18. Revalidated Failure Evidence

### 18.1 NBD readiness race

A read-only preflight against rbd/rbd/w22-01-dr-disk-0 on 10.10.32.2 produced:

| Time after qemu-nbd connect | blockdev bytes | sysfs sectors |
|---:|---:|---:|
| 0 ms | 0 | 0 |
| 50 ms | 107,374,182,400 | 209,715,200 |
| 250 ms and later | 107,374,182,400 | 209,715,200 |

The RBD image itself was already 107,374,182,400 bytes. The Windows cycle 44
failure is therefore an attachment-readiness race. The mover invokes
dr_extent_patch.py immediately after qemu-nbd returns, and the helper computes
an upper bound of zero from BLKGETSIZE64.

### 18.2 Missing committed baseline

The Linux Plan reports current cycle 54 as FAILED with
DR_CBT_RESEED_REQUIRED while checkpoint 53 remains the last durable
checkpoint. The mover supports FULL_RESEED, but the scheduler requests an
incremental cycle after sequence one. The mover exits before transfer when
previousChangeId is empty. This is recoverable, but no state machine currently
selects and completes the recovery path.

### 18.3 Stale authority

Both FTCTL status objects report scheduler_state=ERROR and cycle_state=FAILED.
Cloud can nevertheless return READY and execution readiness true because:

1. DrResponseGenerator reads runtime JSON from the latest Cloud Run;
2. the initial SYNC Run can already be terminal SUCCEEDED;
3. later scheduler cycles do not own a dedicated Cloud row; and
4. DrPlanReadinessValidator.applyTargetReadiness treats an old durable target
   as sufficient and clears the readiness reason.

DrPlanServiceImpl then enables Test Failover and normal Failover from target
materialization plus old control JSON. This violates the display-cache and
action-authority boundary.

## 19. Target State And Authority Model

### 19.1 Separate responsibilities

| Concept | Owner | Meaning |
|---|---|---|
| Administrative state | dr_plan | operator enabled, paused, released |
| Requested operation | dr_run | one asynchronous UI/API command |
| Continuous cycle | dr_sync_cycle | one full, reseed, incremental, or no-change cycle |
| Current authority | dr_plan_runtime | newest accepted engine state and freshness |
| Display cache | dr_plan_view_cache | last-good assembled UI document only |

dr_run must not become an endless scheduler record. A SYNC Run ends after
asynchronous command acceptance and initial target materialization contract
completion. Every later scheduler cycle is persisted independently.

### 19.2 Authority service

Introduce DrProtectionAuthorityService. It returns one immutable snapshot used
by response generation, readiness, action eligibility, and cache assembly.

~~~java
public record DrProtectionAuthoritySnapshot(
        String protectionState,
        String freshnessState,
        String schedulerState,
        String workerState,
        Long runtimeGeneration,
        Long currentCycleSequence,
        String currentCycleState,
        String currentCycleMode,
        String errorCode,
        String errorMessage,
        Date lastStatusAt,
        Date lastTargetDurableAt,
        Long rpoAgeSeconds,
        boolean rpoOverdue,
        boolean targetMaterialized,
        boolean normalCutoverReady) {
}
~~~

State precedence is:

1. FAILED_OVER or TESTING transition state;
2. current valid runtime ERROR or failed current cycle;
3. stale runtime, dead scheduler/worker, or overdue RPO as DEGRADED;
4. RESEEDING or SYNCING;
5. PAUSED;
6. READY only when target materialization, current durability, runtime
   freshness, and scheduler/control health all pass;
7. NEW or PREPARING.

An older durable checkpoint remains recovery evidence but never overrides a
current ERROR or DEGRADED state.

## 20. DB Design

### 20.1 dr_plan_runtime

Add a one-to-one authority table rather than transient columns on dr_plan.

~~~sql
CREATE TABLE IF NOT EXISTS cloud.dr_plan_runtime (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    plan_id bigint unsigned NOT NULL,
    engine_run_uuid varchar(40) NULL,
    runtime_generation bigint unsigned NOT NULL DEFAULT 0,
    scheduler_state varchar(32) NULL,
    scheduler_pid_alive tinyint(1) NOT NULL DEFAULT 0,
    worker_state varchar(32) NULL,
    current_cycle_sequence bigint unsigned NULL,
    current_cycle_state varchar(32) NULL,
    current_cycle_mode varchar(32) NULL,
    baseline_state varchar(32) NULL,
    reseed_reason varchar(128) NULL,
    protection_state varchar(32) NOT NULL DEFAULT 'UNKNOWN',
    freshness_state varchar(32) NOT NULL DEFAULT 'UNKNOWN',
    last_status_at datetime NULL,
    last_source_checkpoint_at datetime NULL,
    last_target_durable_at datetime NULL,
    rpo_age_seconds bigint unsigned NULL,
    rpo_overdue tinyint(1) NOT NULL DEFAULT 0,
    error_code varchar(128) NULL,
    error_message varchar(4096) NULL,
    status_json mediumtext NULL,
    created datetime NOT NULL,
    updated datetime NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dr_plan_runtime__plan_id (plan_id),
    KEY i_dr_plan_runtime__state_updated
        (protection_state, freshness_state, updated),
    CONSTRAINT fk_dr_plan_runtime__plan_id
        FOREIGN KEY (plan_id) REFERENCES dr_plan (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
~~~

status_json is bounded last-valid evidence. Typed columns are authority.
Invalid or older generations replace neither typed authority nor last-valid
JSON.

### 20.2 dr_sync_cycle

Add one row per scheduler cycle.

~~~sql
CREATE TABLE IF NOT EXISTS cloud.dr_sync_cycle (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    uuid varchar(40) NOT NULL,
    plan_id bigint unsigned NOT NULL,
    run_id bigint unsigned NULL,
    engine_run_uuid varchar(40) NOT NULL,
    sequence bigint unsigned NOT NULL,
    requested_mode varchar(32) NOT NULL,
    effective_mode varchar(32) NULL,
    state varchar(32) NOT NULL,
    baseline_generation bigint unsigned NULL,
    baseline_state varchar(32) NULL,
    reseed_reason varchar(128) NULL,
    commit_state varchar(32) NULL,
    incremental_verified tinyint(1) NULL,
    metrics_estimated tinyint(1) NULL,
    virtual_bytes bigint unsigned NULL,
    changed_bytes bigint unsigned NULL,
    source_read_bytes bigint unsigned NULL,
    target_written_bytes bigint unsigned NULL,
    transfer_payload_bytes bigint unsigned NULL,
    changed_extent_count bigint unsigned NULL,
    duration_ms bigint unsigned NULL,
    throughput_bps bigint unsigned NULL,
    source_checkpoint_at datetime NULL,
    target_durable_at datetime NULL,
    error_code varchar(128) NULL,
    error_message varchar(4096) NULL,
    started datetime NULL,
    completed datetime NULL,
    created datetime NOT NULL,
    updated datetime NULL,
    removed datetime NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dr_sync_cycle__uuid (uuid),
    UNIQUE KEY uk_dr_sync_cycle__plan_sequence (plan_id, sequence),
    KEY i_dr_sync_cycle__plan_run_sequence
        (plan_id, engine_run_uuid, sequence),
    KEY i_dr_sync_cycle__plan_state_updated (plan_id, state, updated),
    CONSTRAINT fk_dr_sync_cycle__plan_id
        FOREIGN KEY (plan_id) REFERENCES dr_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_dr_sync_cycle__run_id
        FOREIGN KEY (run_id) REFERENCES dr_run (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
~~~

dr_restore_point remains the durable replicated-copy record. A failed cycle
does not create or modify a restore point. A LOCAL_DURABLE cycle creates one
through an idempotency key derived from Plan and sequence. The engine Run is
retained as producer metadata and is not part of durable Cycle identity.

### 20.3 Migration rules

Apply the same idempotent DDL to schema-42200to42210.sql,
schema-42210to42300.sql, and schema-Europa-After.sql.

Backfill conservatively:

- create runtime rows for active FTCTL DR Plans;
- copy last durable timestamps from dr_plan;
- set protection_state UNKNOWN until fresh strict status arrives;
- never infer READY from a restore point alone;
- historical restore points may backfill successful cycles with
  commit_state=LOCAL_DURABLE; and
- never infer current scheduler health.

## 21. FTCTL Design

### 21.1 NBD size-ready barrier

In lib/ftctl/dr_vmware_mover.sh add:

~~~bash
ftctl_vmware_mover_wait_block_device_ready() {
  local device="$1" expected_bytes="$2" timeout_ms="$3" poll_ms="$4"
  # Poll sysfs sectors and blockdev size until both are non-zero and agree.
}
~~~

Defaults:

~~~bash
FTCTL_DR_NBD_READY_TIMEOUT_MS=5000
FTCTL_DR_NBD_READY_POLL_MS=50
FTCTL_DR_NBD_ATTACH_ATTEMPTS=2
~~~

After source and target attachment:

1. obtain expected virtual bytes from the resolved disk row;
2. call udevadm settle when present, without treating it as proof;
3. poll sysfs size times 512 and blockdev --getsize64;
4. require both non-zero, equal, and at least expected size;
5. on timeout disconnect, allocate a fresh NBD device, and retry once;
6. on final failure emit DR_TARGET_NBD_SIZE_NOT_READY with expected, observed,
   elapsed, device, and attempt fields; and
7. never invoke dr_extent_patch.py before the gate succeeds.

The helper also receives explicit source and target expected sizes and rejects
zero, shrinking, mismatched, or out-of-range devices before opening an output
write path.

### 21.2 Automatic whole-VM reseed

Add ftctl_vmware_mover_resolve_cycle_mode before snapshot creation. It examines
all disks and returns FULL_SEED, FULL_RESEED, or CBT_INCREMENTAL. NO_CHANGE is
decided only after a valid CBT query.

Convert an incremental request to FULL_RESEED when any disk has:

- no committed previousChangeId;
- a missing or non-LOCAL_DURABLE baseline manifest;
- baseline-generation mismatch;
- changed disk set, disk key, virtual size, or hardware fingerprint;
- a changeId rejected by QueryChangedDiskAreas; or
- a prior local commit marked RESEED_REQUIRED.

Do not mix full and incremental writes in one checkpoint generation. Persist:

~~~text
plans/<plan>/baselines/committed.json
plans/<plan>/baselines/pending-<sequence>.json
~~~

Commit order is:

1. finish all target writes;
2. flush every target;
3. write and fsync the pending baseline;
4. write and fsync cycle metrics/checkpoint;
5. atomically rename pending to committed.json;
6. append LOCAL_DURABLE evidence; and
7. clean the short-lived VMware snapshot.

Keep the old committed baseline and checkpoint until step 5. Limit automatic
reseed to one attempt per failed generation, add cooldown, and expose
DR_CBT_RESEED_FAILED after bounded recovery fails.

### 21.3 Scheduler restart reconciliation

Add:

~~~text
ablestack_vm_ftctl dr-reconcile --all --json
~~~

The systemd timer invokes it before the regular scan. For every enabled
continuous profile, validate PID-file existence, kill -0, process command-line
ownership of plan/run, transition state, and runtime generation.

If state says RUNNING but ownership is absent, write STOPPED then RECOVERING,
increment runtime_generation, and start asynchronously through
ftctl_dr_scheduler_start. dr-sync-resume calls the same ensure-running
primitive; it must not merely write RUNNING after acknowledgment. Do not
restart released, paused, failed-over, testing, or quiesced Plans.

### 21.4 Strict status extension

Expose runtime_generation, scheduler_pid_alive, protection_state,
freshness_state, rpo_age_seconds, rpo_overdue, baseline_state, reseed_reason,
and bounded nbd_readiness evidence. Generation increases only when ownership
changes or a newer cycle becomes current. Status must not embed unbounded
events.

## 22. Mold Agent Design

The Agent remains a transport and validation boundary, not the scheduler.

1. Extend FtctlDrStatusAnswer and LibvirtFtctlDrStatusCommandWrapper with the
   section 21.4 fields.
2. Validate strict JSON, maximum 256 KiB, Plan and engine-run identity,
   non-negative generation, and legal enum values.
3. Reject generations older than Cloud's current accepted generation.
4. Return action acceptance immediately; never wait for a cycle or reseed.
5. Use an explicit reconcile command only for backend repair. Normal recovery
   is timer-driven locally.
6. Redact credentials and changeIds from Agent logs and API responses.

Test oversized JSON, wrong Plan/run, stale generation, invalid enums,
asynchronous reconcile acceptance, and normal status.

## 23. Cloud Backend Design

### 23.1 Transactional projection

Split FtctlDrRuntimeProjectionAdapter into:

- FtctlDrStatusParser;
- DrRuntimeAuthorityProjector;
- DrSyncCycleProjector;
- DrRestorePointProjector;
- DrProtectionAuthorityService; and
- DrProtectionViewAssembler.

A valid status transaction:

1. validates Plan/run identity and generation;
2. discards older generations;
3. upserts dr_plan_runtime;
4. upserts dr_sync_cycle by Plan-wide sequence and retains engine Run as producer metadata;
5. creates a restore point only for LOCAL_DURABLE;
6. recomputes protection/freshness;
7. updates dr_plan summary state and last error;
8. appends bounded idempotent events; and
9. commits.

Rebuild the display cache only after commit. Cache failure cannot roll back
authority. Malformed status preserves last valid authority, ages freshness to
STALE, and blocks normal cutover.

### 23.2 RPO freshness

Compute rpoAgeSeconds from now minus lastTargetDurableAt and set rpoOverdue
when it exceeds configured RPO plus a small bounded projection-jitter grace.
Persist the result so list and detail agree.

### 23.3 Operation versus protection history

The initial SYNC DrRun may be SUCCEEDED while a later dr_sync_cycle is FAILED.
Keep that operation history; do not rewrite it. Current Plan authority comes
from dr_plan_runtime and the newest cycle.

## 24. Readiness And Action Eligibility

DrPlanReadinessValidator must consume DrProtectionAuthoritySnapshot.
applyTargetReadiness may confirm physical target resources but cannot clear a
runtime or freshness blocker.

Normal Test Failover and Failover require:

- enabled Plan and no conflicting DrRun;
- materialized target and durable checkpoint;
- fresh runtime status and healthy scheduler/control plane;
- non-failed current cycle and RPO within policy;
- no reseed in progress;
- verified target sizes/formats; and
- passed guest-preparation/cutover prerequisites.

Return typed action readiness:

~~~json
{
  "testFailover": {
    "enabled": false,
    "reasonCode": "DR_CURRENT_CYCLE_FAILED",
    "message": "The latest replication cycle failed.",
    "checkpointAgeSeconds": 69696
  }
}
~~~

Keep legacy booleans for one compatibility release, derived from typed values.
Emergency failover from a stale last-good checkpoint is a separate capability
requiring checkpoint UUID, age display, acknowledgment, and audit.

## 25. API Design

Plan responses expose protectionstate, freshnessstate, schedulerstate,
schedulerpidalive, current-cycle identity/state/mode, baseline state,
reseed reason, last status/durable times, RPO age/overdue, current error, and
typed actionreadiness. effectivestate is a compatibility alias of
protectionstate, never derived from cache or latest completed DrRun.

Add listDrSyncCycles with planid, state, mode, page, pagesize, newest-first
ordering, default 20, maximum 100, typed metrics, and no raw changeIds.

getDrProtectionView returns cache generation/health and authority generation.
The backend overlays the small current-authority summary on cached display
data before response, preserving fast reads without stale action authority.

## 26. UI Design

Applicable files include ui/src/api/dr.js, DrPlanList.vue, DrPlanOverview.vue,
DrProtectionInfoTab.vue, and DrActionToolbar.vue.

1. Render protectionstate, never cached target readiness, as the main pill.
2. Poll the small authority response asynchronously; refresh the larger view
   only when authorityGeneration changes.
3. Patch content in place and never blank list/detail during silent refresh.
4. Show baseline rebuilding and full-copy byte progress during RESEEDING.
5. Show current-cycle error and last-good checkpoint age in ERROR.
6. Gate actions and tooltips only from backend typed actionreadiness.
7. Put cycle history in Protection Information instead of another one-row
   operation tab.
8. Separate display-cache warnings from replication status.
9. Add localized light/dark-mode states; do not expose raw engine enums.

## 27. Error Codes

| Code | Retry | Required behavior |
|---|---:|---|
| DR_TARGET_NBD_SIZE_NOT_READY | bounded attach retry | no write; report observed/expected |
| DR_CBT_BASELINE_MISSING | automatic reseed | preserve last-good baseline |
| DR_CBT_RESEED_IN_PROGRESS | no parallel retry | report full-copy progress |
| DR_CBT_RESEED_FAILED | operator after diagnosis | ERROR; retain checkpoint |
| DR_RUNTIME_STALE | reconcile/poll | block normal cutover |
| DR_SCHEDULER_NOT_RUNNING | automatic reconcile | do not claim RUNNING |
| DR_RPO_OVERDUE | next successful cycle | block/degrade by policy |
| DR_STATUS_GENERATION_STALE | discard | retain current authority |

## 28. Verification Plan

### 28.1 Automated

- delayed NBD size succeeds; permanent zero and undersized devices fail before
  writes;
- missing baseline, disk-set change, and hardware change select whole-VM
  FULL_RESEED;
- reseed failure preserves committed baseline;
- successful reseed is followed by measured CBT incremental;
- timer reconciles stale RUNNING but not paused/transition/released Plans;
- Agent rejects wrong identity, stale generation, malformed/oversized status;
- completed SYNC plus failed current cycle yields Plan ERROR;
- old target plus current ERROR never enables Test Failover;
- cache failure preserves display but not stale eligibility;
- duplicate cycle projection is idempotent;
- only LOCAL_DURABLE creates restore points; and
- UI list/detail agree and silent polling retains content.

### 28.2 Live acceptance

1. Apply migrations without inferring READY during backfill.
2. Deploy with version and marker verification.
3. Reconcile Linux and Windows profiles without manual source-snapshot edits.
4. Linux automatically runs FULL_RESEED for the missing baseline.
5. Windows passes the NBD barrier and completes incremental transfer.
6. Both complete one further measured CBT incremental cycle.
7. A controlled small guest write proves written bytes are below virtual size.
8. FTCTL, runtime/cycle tables, restore point, API, and UI agree.
9. Agent/timer restart proves asynchronous scheduler recovery.
10. Enable Test Failover only while both Plans are within five-minute RPO.

## 29. Implementation Order

1. FTCTL NBD barrier and tests.
2. FTCTL baseline manifest and whole-VM automatic reseed.
3. FTCTL generation and timer reconcile.
4. Strict status and Agent boundary.
5. DB runtime/cycle tables and DAO/entities.
6. Transactional runtime/cycle/restore projection.
7. DrProtectionAuthorityService and readiness/eligibility replacement.
8. API fields and listDrSyncCycles.
9. Cache overlay and UI authority rendering.
10. Builds, GitHub Actions FTCTL package, deployment, migration, cleanup, and
    live acceptance.

## 30. Consolidated AS-IS / TO-BE

| Layer | Root cause | AS-IS | TO-BE |
|---|---|---|---|
| UI | old target-ready snapshot is treated as current | READY masks failed cycle | poll/render authority separately from cache |
| API | latest DrRun and cache drive state | executionready can be true in ERROR | current authority and typed reasons win |
| Backend | no continuous-cycle entity | terminal initial Run is the only projection | transactional runtime/cycle projection |
| Agent | no generation contract | stale status can look current | strict identity, generation, enum, size |
| FTCTL mover | patch starts before capacity is visible | zero-size extent bound | size-ready barrier plus helper contract |
| FTCTL CBT | sequence implies incremental | missing changeId stops Linux | automatic whole-VM FULL_RESEED |
| FTCTL scheduler | state RUNNING lacks PID proof | dead protection after restart/resume | timer reconciliation and ensure-running |
| DB | Run/runtime/checkpoint/cache overlap | old durable data becomes authority | separate run, cycle, runtime, restore, cache |

## 31. Completion Gate

- no extent patch starts on a zero or mismatched device;
- Linux repairs its baseline without manual file edits;
- Linux and Windows complete reseed/full plus measured CBT incremental;
- current ERROR, stale RPO, or dead scheduler blocks normal cutover;
- UI, API, DB, and FTCTL agree on one authority generation;
- restart restores eligible continuous protection asynchronously; and
- last-good display cache never overrides action authority.

## 32. Implementation, Deployment, And Retest Handoff (2026-07-17)

### 32.1 Implemented contract

- FTCTL waits for an attached qemu-nbd block device to expose a stable,
  non-zero expected size before an extent write can start.
- Missing or invalid VMware CBT baseline metadata selects a typed whole-VM
  `FULL_RESEED` path instead of repeatedly failing an incremental cycle.
- Scheduler start/resume requires a live owned PID and no longer acknowledges
  `RUNNING` from a state file alone.
- Agent status carries runtime generation, scheduler PID liveness, baseline
  state, and reseed reason to the Cloud projection boundary.
- Cloud persists current authority in `dr_plan_runtime` and cycle measurements
  in `dr_sync_cycle`; readiness and action eligibility prefer this authority
  over old Run or display-cache evidence.
- List/detail UI consumes the typed protection/freshness/current-cycle fields
  without replacing the page during asynchronous refresh.
- Plan deletion removes its runtime authority and cycle cache after protection
  and runtime resources have been released. The delete service test verifies
  both DAO cleanup calls.

### 32.2 Verification evidence

| Area | Result |
|---|---|
| FTCTL focused self-tests | PASS: delayed NBD readiness, zero-size rejection, automatic reseed, scheduler ownership/control |
| FTCTL package | GitHub Actions run `29557526772` succeeded; `ablestack_vm_ftctl-0.9.1-1.noarch` deployed to `10.10.32.1/2/3` |
| Cloud Maven modules | PASS: changed-module reactor package; DR module compiled 224 main and 9 test sources |
| Cloud deletion lifecycle test | PASS: `DrPlanServiceImplTest`, 7 tests, 0 failures |
| Cloud UI | Production build succeeded and the active bundle contains authority markers |
| Management deployment | `mold` active, `/client/` HTTP 200, authority classes loaded, `WEB-INF` preserved |
| Agent deployment | `mold-agent` active on all three hosts with the updated status answer and KVM wrapper |
| DB migration | `dr_plan_runtime` and `dr_sync_cycle` schema applied; orphan authority rows cleaned |
| Retest cleanup | No active Plan, authority, replica, or cycle rows; old target volumes expunged; host plan runtime absent |
| VMware source cleanup | vCenter snapshot property is null for source refs `vm-4486` and `vm-6429` |

### 32.3 Final AS-IS / TO-BE

| Layer | AS-IS before this implementation | Deployed TO-BE |
|---|---|---|
| UI | cached target-ready data could mask a failed current cycle | typed authority and freshness are rendered independently and refreshed asynchronously |
| API | async acceptance could be mistaken for completed engine work | Run acceptance remains immediate; runtime authority determines completion and eligibility |
| Backend | release/delete left new authority cache outside the Plan lifecycle | Plan delete removes runtime and cycle cache after release guards pass |
| Agent | stale status lacked PID/baseline generation proof | generation, PID liveness, baseline, and reseed reason cross the Agent contract |
| FTCTL | NBD timing, missing baseline, and dead scheduler could stall or repeat failure | readiness barrier, automatic full reseed, and owned-PID recovery are enforced |
| DB | old Run/cache evidence overlapped current state | Plan, Run, current runtime, cycle history, restore evidence, and display cache have separate roles |

### 32.4 Remaining live acceptance

The environment is clean and ready for a new Linux and Windows Plan test. The
deployment is not yet a functional DR PASS until each new Plan demonstrates:

1. one successful initial full seed or automatic full reseed;
2. one later measured CBT incremental cycle with durable target bytes;
3. scheduler PID continuity across at least one timer/reconcile interval;
4. matching UI, API, runtime table, cycle table, and FTCTL generation/state;
5. normal Test Failover eligibility only while target materialization and RPO
   freshness are both current.

## 33. 2026-07-17 Incremental Mode And Projection Correction

### 33.1 Live finding

Linux Plan `297556ce-7e1c-4553-8a51-b6400a2e896a` and Windows Plan
`0987a837-549c-4457-9eef-3874e0ac9057` proved target durability, target VM
materialization, CBT enablement, advancing changeIds, positive durable baseline
generations, and Scheduler continuity. They did not prove continuous
incremental replication: every later cycle executed as full reseed.

### 33.2 Corrective boundary

- FTCTL execution rows preserve baseline state/generation and disk identity.
- Scheduler-requested mode and mover-effective mode are separate fields.
- A typed automatic reseed is allowed once; an identical repeat is stopped
  before copy.
- Agent transports the requested/effective decision and reseed evidence.
- Cloud projects both current and latest-completed cycle identities from one
  status response.
- DB stores typed decision aggregates and latest incremental proof.
- Normal Test Failover and planned Failover require verified incremental or a
  valid no-change checkpoint, while emergency forced Failover remains explicit
  and audited.

### 33.3 Normative detail

The code-level design, migration fields, tests, live acceptance plan, and
AS-IS/TO-BE table are in
`559-cross-hypervisor-dr-incremental-mode-decision-and-cycle-projection-design-20260717.md`.

Until its live Linux and Windows gates pass, the deployed state is not a
functional continuous-DR PASS even when target readiness and RPO freshness are
green.

## 34. 2026-07-20 Scheduler Session And Authority Version Correction

The runtime authority table and cycle table remain the canonical Cloud
boundaries, but `engine_run_uuid + runtime_generation` is not a sufficient
ordering key. Live validation proved that control generation can be written as
runtime generation and then be followed by a lower run-local cycle sequence.
It also proved that the latest operation run can be terminal while an older
run's scheduler is the only live worker.

The corrected authority version is `(scheduler_lease_epoch,
authority_sequence)`. Continuous protection has a scheduler session identity
separate from operation runs, and cycle history uses a Plan-wide sequence.
Scheduler health includes lease ownership, PID start token, heartbeat, and ACK
identity. The typed DB migration and cross-layer implementation contract are
defined in
`564-cross-hypervisor-dr-plan-scheduler-singleton-authority-design-20260720.md`.

## 35. 2026-07-21 Finite Operation Query Boundary Correction

A strict status response may be requested for a finite operation while carrying
newer Plan-wide protection authority. The consumer must validate and project
the operation and protection envelopes independently. A terminal
`TEST_CLEANUP` Run may remain the latest operation but cannot suppress authority,
cycle, RPO, or restore-point projection from the active producer.

The response envelope, DAO resolution, projection order, typed cleanup-resume
state, migration, and acceptance tests are defined in document 565 and FTCTL
companion document 437.
