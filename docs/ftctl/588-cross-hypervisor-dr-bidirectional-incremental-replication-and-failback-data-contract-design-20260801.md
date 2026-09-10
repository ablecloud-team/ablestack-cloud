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

# 588. Cross Hypervisor DR Bidirectional Incremental Replication And Failback Data Contract Design

> 2026-08-11 cutover-baseline and terminal-state correction:
> [602-cross-hypervisor-dr-cutover-reverse-baseline-and-terminal-convergence-design-20260811.md](602-cross-hypervisor-dr-cutover-reverse-baseline-and-terminal-convergence-design-20260811.md)
> makes the Failover cutover baseline visible to Failback preflight and prevents
> stale runtime `SYNCING` evidence from overriding a completed Cloud lifecycle.
>
> 2026-08-05 route and failure-convergence correction:
> [595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)
> separates `KVM_TO_VMWARE` topology from `ABLESTACK_TO_VMWARE` provider pair,
> defines the FTCTL route-contract v2 envelope, and makes Cloud lifecycle gate
> failure converge Session, Run, Plan, authority, runtime, and cache.
>
> 2026-08-04 live-runtime preflight correction:
> [592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md](592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md)
> separates committed authority projection from Agent/vCenter live power,
> makes skipped engine stages `NOT_RUN`, and requires a live KVM serving domain
> before reverse data readiness.
>
> 2026-08-04 corrective contract:
> [591-cross-hypervisor-dr-failback-initial-reverse-seed-and-early-failure-convergence-design-20260804.md](591-cross-hypervisor-dr-failback-initial-reverse-seed-and-early-failure-convergence-design-20260804.md)
> defines first reverse-seed baseline absence, pre-dispatch FailbackSession
> creation, typed early-failure convergence, and TARGET-authority retention.
> Revision 2 separates `FAILBACK_FINAL` operation intent from the
> `FULL_REVERSE_SEED`/`REVERSE_FINAL` data mode and requires read-only reverse
> data-plane preflight before Failback is enabled.
>
> 2026-08-03 최신 후속 규약:
> [589-cross-hypervisor-dr-reprotect-preflight-and-release-terminal-convergence-design-20260803.md](589-cross-hypervisor-dr-reprotect-preflight-and-release-terminal-convergence-design-20260803.md)
> 는 역방향 보호 시작 전 Agent/FTCTL 계약 버전을 검증하고, Release가 현재 VM과
> authority를 변경하지 않는 terminal 규약을 추가한다.

- Date: 2026-08-01
- Status: code-level design; implementation pending
- Scope: UI, API, Cloud backend, Agent, FTCTL contract, DB, VMware <-> KVM Failover/Reprotect/Failback
- Engine companion: `ablestack-qemu-exec-tools/docs/ftctl/445-ftctl-dr-bidirectional-incremental-replication-and-reverse-guest-compatibility-design-20260801.md`

## 1. Objective

The product contract is continuous, bidirectional incremental protection:

```text
VMware authority --CBT incremental--> KVM replica
KVM authority --RBD/QCOW2 incremental--> VMware replica
```

Failover changes the active authority. Reprotect establishes protection from
the new authority. Failback performs only the final planned delta, guest
compatibility validation, VM lifecycle, and authority commit.

The UI and Cloud backend must not call a Failback successful merely because the
old VMware VM powered on. Success requires durable reverse data evidence.

## 2. Verified current defect

For Windows Plan `2514a846-64a2-4bc7-ba88-38a874410782`:

| Evidence | Observed value |
|---|---|
| Cloud Failback Run | `124 / SUCCEEDED` |
| FTCTL reverse direction | `KVM_TO_VMWARE` |
| Reverse checkpoint | `1495` |
| Effective mode | `NO_CHANGE` |
| Source read / target write | `0 B / 0 B` |
| Change IDs | VMware CBT IDs advanced |
| KVM baseline | absent |
| DB cycle owner | old protection Run `91`, not Failback Run `124` |
| Boot validation | `POWER_STATE_VALIDATED` |

The result proves lifecycle convergence but not KVM-to-VMware data
convergence. A Failback data gate must reject this evidence.

## 3. Product invariants

1. UI actions are asynchronous and return a Run UUID immediately.
2. Cloud owns Plan, Run, VM lifecycle, site credentials, and authority commit.
3. Agent transports typed commands and status; it never decides data semantics.
4. FTCTL owns change tracking, extent transfer, staging writer, and local evidence.
5. VMware CBT is accepted only for a VMware source.
6. KVM-to-VMware evidence must name an RBD/QCOW2 tracker and a VDDK writer.
7. Failback is disabled until reverse protection has a durable baseline.
8. Zero-byte data-ready requires a valid source tracker proof.
9. Windows boot readiness is independent from power readiness.
10. No secret is persisted in Plan, Run, baseline, cycle, event, or UI cache rows.

## 4. End-to-end lifecycle

### 4.1 Failover

```text
READY/SOURCE
  -> final forward CBT cycle
  -> KVM cutover baseline creation
  -> KVM guest preparation
  -> KVM boot validation
  -> TARGET authority commit
  -> FAILED_OVER_UNPROTECTED
```

The cutover baseline is created before guest preparation so the reverse tracker
can observe VirtIO preparation and all KVM runtime writes.

### 4.2 Reprotect

```text
FAILED_OVER_UNPROTECTED
  -> reverse provider preflight
  -> full reverse seed when no KVM baseline is valid
  -> KVM-to-VMware baseline commit
  -> continuous reverse incremental cycles
  -> REVERSE_PROTECTED
```

### 4.3 Failback

```text
REVERSE_PROTECTED
  -> stop accepting new reverse cycle
  -> quiesce/stop active KVM VM
  -> final KVM delta
  -> stage VMware-compatible guest preparation
  -> isolated VMware boot validation
  -> power off KVM serving VM
  -> promote VMware staging candidate
  -> commit SOURCE authority
  -> resume VMware-to-KVM protection
```

If any step before authority commit fails, Cloud restores TARGET service and
keeps TARGET authority.

## 5. UI design

### 5.1 Protection information

`DrProtectionInfoTab.vue` displays:

```text
active authority
protection direction
provider pair
baseline generation and state
last transfer mode
changed/read/written/verified bytes
reverse protection readiness
guest compatibility state
boot validation state
```

Method labels are direction-specific:

```text
VMware CBT incremental
KVM RBD incremental
KVM QCOW2 bitmap incremental
Full reverse seed
No changes verified
```

### 5.2 Action availability

`actionAvailability.js` and backend eligibility share these gates:

```javascript
canReprotect =
  authority.side === 'TARGET' &&
  authority.consistent === true &&
  reversePreflight.ready === true &&
  noConflictingRun

canFailback =
  authority.side === 'TARGET' &&
  reverseProtection.state === 'READY' &&
  reverseProtection.baselineState === 'LOCAL_DURABLE' &&
  reverseProtection.writerReady === true &&
  reverseProtection.guestCompatibilityReady === true &&
  noConflictingRun
```

Failback is not enabled directly from `FAILED_OVER_UNPROTECTED`.

### 5.3 Dialog behavior

The Failback dialog shows read-only derived information:

- active and destination sites;
- planned transfer mode;
- reverse baseline age;
- estimated changed bytes when available;
- staging datastore/VM policy;
- Windows guest compatibility and validation policy;
- explicit risk acknowledgement only when validation is weaker than heartbeat.

Credentials, Mold selection, raw JSON, VDDK paths, and engine host fields are
not user input.

### 5.4 Polling

After action acceptance:

1. close the modal;
2. retain current screen data;
3. poll the accepted Run by UUID;
4. refresh Plan protection cache independently;
5. stop Run polling only on a terminal Run state;
6. never block the entire UI while transfer runs.

## 6. API design

### 6.1 Read-only preflight

Add or extend:

```text
getDrReplicationDirectionPreflight
getDrFailbackPreflight
getDrPlan
listDrSyncCycles
```

`getDrFailbackPreflight` response adds:

```json
{
  "ready": false,
  "direction": "KVM_TO_VMWARE",
  "authorityside": "TARGET",
  "providerpair": "RBD_DIFF__VMWARE_VDDK",
  "plannedtransfermode": "FULL_REVERSE_SEED",
  "baselineid": null,
  "baselinegeneration": 0,
  "baselinestate": "MISSING",
  "sourcetrackerready": true,
  "targetwriterready": false,
  "stagingtargetready": false,
  "guestcompatibilityready": false,
  "bootvalidationmode": "GUEST_HEARTBEAT",
  "errorcode": "DR_REVERSE_FULL_SEED_REQUIRED"
}
```

### 6.2 Action APIs

```text
startDrReprotect(planid, idempotencykey, reason)
startDrFailback(planid, idempotencykey, validationmode, reason)
cancelDrRun(runid)
```

These APIs accept intent only. They do not accept credentials, disk paths,
worker commands, or raw engine JSON.

### 6.3 Cycle API

`listDrSyncCycles` adds filters and fields:

```text
direction
authorityside
runid
baselineid
providerpair
transfermode
trackerproof
writerproof
guestprepstate
```

## 7. Backend design

### 7.1 New services

```java
DrReplicationDirectionService
DrReplicationBaselineService
DrReverseProtectionService
DrFailbackDataGate
DrGuestCompatibilityService
DrStagingTargetService
```

Responsibilities:

| Service | Responsibility |
|---|---|
| `DrReplicationDirectionService` | derive direction/provider pair from current authority |
| `DrReplicationBaselineService` | validate monotonic baseline lineage |
| `DrReverseProtectionService` | start/project reverse seed and scheduler |
| `DrFailbackDataGate` | reject false or incomplete DATA_READY evidence |
| `DrGuestCompatibilityService` | validate reverse guest-prep evidence and policy |
| `DrStagingTargetService` | create/promote/abort VMware staging candidate through site adapter |

### 7.2 Failback data gate

Before `TARGET_STOPPING`, require:

```java
requireEquals(run.getId(), cycle.getRunId());
requireEquals("KVM_TO_VMWARE", cycle.getDirection());
requireEquals("TARGET", cycle.getAuthoritySide());
requireTrue(cycle.getBaselineState() == LOCAL_DURABLE);
requireTrue(cycle.getCommitState() == LOCAL_DURABLE);
requireTrue(cycle.getTargetWriterType() == VMWARE_VDDK);
requireTrue(cycle.getWriteVerifiedBytes() == cycle.getTargetWrittenBytes());
requireTrue(cycle.getEffectiveMode() != NO_CHANGE || cycle.isNoChangeVerified());
requireTrue(failbackSession.getGuestCompatibilityState() == READY);
```

For a full seed, verified bytes must cover the expected virtual data contract.
For incremental, every disk must reference the same committed baseline
generation and a successful tracker proof.

### 7.3 Cloud-owned staging lifecycle

Cloud uses the source-site adapter to:

1. create or resolve the staging VMDK/VM;
2. keep the production VMware VM powered off and fenced;
3. provide opaque staging references to Agent/FTCTL;
4. request isolated power-on after reverse guest preparation;
5. validate heartbeat;
6. promote staging atomically or abort and remove it.

FTCTL never selects a vCenter, datastore, network, or VM on its own.

### 7.4 Failback lifecycle correction

`DrFailbackLifecycleServiceImpl` changes from:

```text
DATA_READY -> stop target -> start source -> POWER_STATE_VALIDATED -> commit
```

to:

```text
DATA_READY_EVIDENCE_VALIDATING
-> FINAL_DELTA_VERIFIED
-> GUEST_COMPATIBILITY_VALIDATING
-> VMWARE_STAGING_BOOT_VALIDATING
-> TARGET_STOPPING
-> STAGING_PROMOTING
-> SOURCE_STARTING
-> AUTHORITY_COMMITTING
-> FORWARD_PROTECTION_RESUMING
```

`POWER_STATE_VALIDATED` is not sufficient for Windows unless explicitly allowed
by policy and acknowledged by the operator.

### 7.5 Projection rules

- A Run failure does not overwrite healthy Plan authority.
- Reprotect Run owns reverse seed/cycles; Failback Run owns final delta/cutback.
- A cycle cannot be projected under a different Run.
- A stale forward CBT cycle cannot satisfy a reverse Failback gate.
- Plan returns to `READY/SOURCE` only after a post-Failback forward checkpoint.

## 8. Agent contract

### 8.1 Command fields

Extend `FtctlDrActionCommand` with non-secret fields:

```text
replicationDirection
providerPair
authorityGeneration
expectedBaselineId
expectedBaselineGeneration
stagingTargetRef
validationMode
minimumVerifiedCheckpointSequence
```

### 8.2 Status fields

Extend `FtctlDrStatusAnswer` and `FtctlDrCycleSnapshot`:

```text
cycleRunUuid
direction
authoritySide
providerPair
transferMode
baselineId
baselineGeneration
trackerType
trackerProofState
writerType
writerProofState
writeVerifiedBytes
stagingTargetRef
reverseGuestPrepState
bootValidationState
```

The KVM wrapper validates required fields before accepting a successful result.
Missing reverse evidence returns `DR_REVERSE_EVIDENCE_MISMATCH`.

## 9. FTCTL boundary

FTCTL implements the companion document's provider-pair registry, KVM change
trackers, VDDK staging writer, reverse guest preparation, and local evidence.

Cloud sends site-derived opaque references and intent. Agent executes:

```text
dr-transition-preflight --direction KVM_TO_VMWARE
dr-reprotect
dr-failback
dr-failback-commit
dr-failback-abort
```

FTCTL never calls Cloud UI/API and never owns the production VM lifecycle.

## 10. Database design

### 10.1 `dr_replication_baseline`

```sql
CREATE TABLE `cloud`.`dr_replication_baseline` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `direction` varchar(32) NOT NULL,
  `authority_side` varchar(16) NOT NULL,
  `generation` bigint unsigned NOT NULL,
  `state` varchar(32) NOT NULL,
  `tracker_type` varchar(32) NOT NULL,
  `source_checkpoint_sequence` bigint unsigned DEFAULT NULL,
  `created_by_run_id` bigint unsigned NOT NULL,
  `committed_at` datetime DEFAULT NULL,
  `retired_at` datetime DEFAULT NULL,
  `details_json` mediumtext,
  `created` datetime NOT NULL,
  `updated` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_replication_baseline_uuid` (`uuid`),
  UNIQUE KEY `uk_dr_replication_baseline_generation`
    (`plan_id`,`direction`,`generation`,`removed`),
  KEY `idx_dr_replication_baseline_active`
    (`plan_id`,`direction`,`state`,`removed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 10.2 `dr_replication_baseline_disk`

Stores one disk identity and opaque tracker reference per baseline. Secrets and
complete credentials are forbidden.

```text
baseline_id
replica_disk_id
disk_key
source_identity_hash
target_identity_hash
tracker_ref_encrypted_or_opaque
size_bytes
state
details_json
```

### 10.3 `dr_sync_cycle`

Add:

```text
direction
authority_side
provider_pair
transfer_mode
source_baseline_id
result_baseline_id
tracker_type
tracker_proof_state
writer_type
writer_proof_state
write_verified_bytes
no_change_verified
```

`run_id` is mandatory for transition-owned cycles. A Failback final cycle must
point to the Failback Run, not the long-lived forward scheduler Run.

### 10.4 `dr_sync_cycle_disk`

Persist per-disk evidence:

```text
cycle_id
replica_disk_id
disk_key
baseline_generation
changed_extent_count
changed_bytes
source_read_bytes
target_written_bytes
write_verified_bytes
source_identity_hash
target_identity_hash
tracker_proof_json
writer_proof_json
```

### 10.5 `dr_failback_session`

Add:

```text
reverse_protection_baseline_id
final_delta_cycle_id
staging_target_ref
guest_compatibility_state
guest_prep_manifest_ref
boot_validation_mode
boot_validation_evidence
data_gate_state
```

## 11. Error model

Cloud preserves FTCTL typed errors and adds lifecycle errors:

```text
DR_REVERSE_PROTECTION_NOT_READY
DR_REVERSE_CYCLE_RUN_MISMATCH
DR_REVERSE_DIRECTION_MISMATCH
DR_REVERSE_BASELINE_MISSING
DR_REVERSE_WRITER_PROOF_MISSING
DR_REVERSE_ZERO_CHANGE_UNVERIFIED
DR_REVERSE_GUEST_COMPATIBILITY_NOT_READY
DR_VMWARE_STAGING_BOOT_FAILED
DR_FAILBACK_DATA_GATE_REJECTED
```

## 12. Tests

### 12.1 UI

- Reprotect is available after target authority commit.
- Failback remains disabled until reverse protection is ready.
- Transfer direction/mode/bytes and guest compatibility are visible.
- Run polling is non-blocking and modal closure is immediate.
- Dark mode uses existing semantic status tokens.

### 12.2 Backend/API

- reject a reverse cycle owned by another Run;
- reject VMware CBT proof for `KVM_TO_VMWARE`;
- reject zero-byte reverse data without tracker proof;
- reject power-only Windows validation when policy requires heartbeat;
- preserve TARGET authority on every pre-commit failure;
- complete only after post-Failback forward protection resumes.

### 12.3 Agent

- command/status serialization preserves direction and generations;
- missing writer proof cannot become success;
- retries preserve idempotency key and staging reference.

### 12.4 Live round trip

1. VMware write -> forward CBT incremental -> KVM checksum PASS.
2. Failover -> KVM write -> reverse RBD incremental -> staging VMDK checksum PASS.
3. Windows staging boot on VMware with EFI/Secure Boot and heartbeat PASS.
4. Failback -> VMware write -> resumed forward CBT incremental PASS.
5. DB cycle ownership and baseline generations remain monotonic.

## 13. Recommended implementation order

1. Add backend and FTCTL guards that block unsafe reverse Failback.
2. Add direction/provider-pair DTO and status fields.
3. Add baseline and per-disk DB schema/VO/DAO.
4. Implement FTCTL direction-specific maps and KVM trackers.
5. Implement VDDK staging writer and proof contract.
6. Implement full reverse seed and reverse scheduler.
7. Implement reverse guest preparation and isolated VMware boot.
8. Add Cloud staging lifecycle and Failback data gate.
9. Add API responses and action eligibility.
10. Add UI direction/readiness/evidence display.
11. Build and run automated tests.
12. Deploy and execute the live bidirectional round trip.

## 14. AS-IS / TO-BE

| Layer | Error cause | AS-IS | TO-BE |
|---|---|---|---|
| UI | success is authority/power oriented | cannot prove reverse bytes | displays direction, baseline, mode, bytes, guest readiness |
| API | Failback preflight lacks data-plane proof | site/power readiness only | tracker/writer/baseline/guest proof |
| Backend | accepts false DATA_READY | powers old VM after 0-byte forward CBT query | validates reverse cycle and staging boot before cutback |
| Agent | status lacks directional proof | success payload can be ambiguous | typed provider-pair and evidence fields |
| FTCTL | one VMware mover handles both directions | KVM changes are not read | RBD/QCOW2 tracker plus VDDK writer |
| DB | cycle ownership and direction are incomplete | Failback cycle can belong to forward Run | normalized baseline and per-cycle/per-disk evidence |
| Windows | only VMware-to-KVM preparation exists | data copy does not prove VMware bootability | reverse driver/BCD/EFI preparation plus heartbeat |
| Safety | KVM can be stopped after false no-change | KVM-only data loss is possible | TARGET remains authoritative until all gates pass |

## 15. Completion criteria

The feature is complete when one Windows Plan and one Linux Plan pass the full
round trip with real non-zero incremental evidence in both directions, survive
no-change and retry cases, boot on both hypervisors, and preserve monotonic
authority/baseline generations without synchronous UI/API blocking.

## 16. Implementation update (2026-08-01)

The implementation now persists reverse data evidence in `dr_failback_session` and applies a Cloud-owned data gate before any power transition. `DrFailbackDataGateServiceImpl` requires the `ABLESTACK_TO_VMWARE` provider pair, a durable baseline/tracker, a durable VDDK writer, and verified target writes. `DrFailbackLifecycleServiceImpl` evaluates that gate before stopping the active KVM VM and validates VMware guest identity through vCenter before authority commit.

Agent status now carries reverse direction, provider pair, baseline generation/state, tracker state, writer state, target-write flags, and guest compatibility state. The cached protection-view API and `DrProtectionInfoTab.vue` expose the same evidence without adding a synchronous engine call from the UI.

Database upgrade scripts and the create schema contain the new failback evidence columns. The schema is backward-compatible through idempotent column additions on upgrade paths.

Production PASS still requires the live round-trip acceptance in section 12.4; build success alone does not prove bidirectional data correctness.

## 17. Corrective design update (2026-08-04)

Live Failback Run `7ed30e9b-da7a-4baa-bef9-be555b1464b5` failed before reverse
data-ready because FTCTL treated the expected absence of the first reverse
baseline as a file-read error. Cloud safely retained TARGET authority, but the
early engine failure had no `dr_failback_session` row because session creation
depended on a later `failback_session_id`.

Document 591 is normative for this correction. Cloud creates the failback
session with the Run before dispatch, reconciles typed pre-data-ready failures,
separates operation health from serving authority, and keeps Cloud-owned target
VM/disk/network materialization authoritative when transient FTCTL operation
fields are empty. Revision 2 additionally supersedes the fixed
`failback-final -> REVERSE_FINAL` mapping: operation intent is independent from
data mode, and an absent first reverse baseline selects `FULL_REVERSE_SEED`.
The paired FTCTL correction is document 448 revision 2.

## 18. Reverse Snapshot And Terminal Evidence Addendum (2026-08-05)

The reverse data contract additionally requires every immutable RBD snapshot
source to be attached read-only. Cloud must preserve the engine's typed
terminal result as the canonical Run and FailbackSession result; an absent
worker PID or a downstream data-gate failure cannot overwrite a more
authoritative transfer error. The full UI/API/backend/Agent/FTCTL/DB contract
and implementation order are defined in document 593, paired with FTCTL
document 450.

## 19. Live Worker And Reconciliation Addendum (2026-08-05)

The data contract now distinguishes a durable engine terminal from provisional
worker observation. Advancing bytes, fresh heartbeat, or a Run-owned process
keeps the transfer non-terminal even when worker identity conflicts. Cloud
preserves TARGET authority, blocks duplicate mutation, and enters
`RECONCILIATION_REQUIRED` until authoritative terminal or repeated
dead-and-drained proof is available. Document 594 and FTCTL document 451 define
the complete typed contract and implementation order.

## 20. Failback Route And Terminal Convergence Addendum (2026-08-05)

A successful reverse checkpoint uses two valid but different vocabularies:
`replication_direction=KVM_TO_VMWARE` describes hypervisor topology, while
`provider_pair=ABLESTACK_TO_VMWARE` describes the product/provider path.
Document 595 is normative where earlier text or implementation compared both
fields to one literal. It also supersedes session-only failure handling: every
Cloud lifecycle gate failure must converge FTCTL operation ownership,
FailbackSession, Run, Plan, replica authority, action availability, and cache.

## 21. Durable Evidence Publication Addendum (2026-08-06)

The reverse checkpoint data contract is complete only when its durability tuple
is observable through FTCTL status and persisted in the Failback Session. A
checkpoint file that is valid but invisible to Agent/Cloud does not authorize
the authority transition. Document 596 defines the typed fields, coherent
lineage rule, asynchronous publication grace, error taxonomy, and retained
baseline retest procedure.

## 22. Failback Commit Envelope Addendum (2026-08-06)

Reverse incremental data completion and authority commit are separate gates.
After a complete reverse checkpoint, Cloud must persist a versioned commit
envelope that carries the FTCTL checkpoint and the committed cutover authority
generation as independent values. Only then may Cloud stop the KVM serving VM
and start the VMware source.

Document 597 defines the cross-layer gate and current-session forward recovery;
qemu document 453 defines the FTCTL journal and idempotency contract. A missing
commit tuple must never trigger a new full reverse seed or be hidden as an
ambiguous late acknowledgement.

## 23. Forward Target Locator And Protection Resume Addendum (2026-08-06)

The resumed forward path must use the same Cloud-owned target volume identity
and FTCTL canonical locator as initial protection. A bare image name, reverse
map, or allocated `plan_cycle_sequence` cannot prove forward protection.
Document 598 defines the typed target descriptor, asynchronous projection,
database fields, and durable checkpoint completion gate. FTCTL document 454
defines the shared storage locator and direction-scoped map behavior.

## 24. Cutover Reverse Baseline And Terminal Snapshot Addendum (2026-08-11)

The normal VMware-to-ABLESTACK round trip no longer waits until Failback to
create its first KVM baseline. FTCTL document 456 defines the cutover snapshot
and atomic baseline contract. Cloud document 602 defines the existing API/DB
evidence reuse, completion snapshot normalization, UI precedence rule, and live
incremental acceptance procedure. Legacy plans without a cutover baseline may
still use an explicitly reported full reverse seed.
