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

# DR Plan UI Action Menu Regression Design

## 1. Goal

Validate every DR plan action exposed by the Cloud UI without bypassing the UI
with direct API or database mutations. Previously validated full sync, failover,
and failback contracts remain immutable regression gates.

## 2. Menu inventory

The FTCTL DR plan action surface consists of plan edit/delete, full resync,
sync recovery, pause/resume, test failover/cleanup, failover/failback,
reprotect, protection release, and active-run cancellation. Adopt replica is not
applicable to `FTCTL_DR` and must remain hidden.

State-changing validation must use the UI. Cloud API, database, Agent, FTCTL,
libvirt, and vCenter evidence are read-only validation sources.

## 3. Edit-form inventory contract

### AS-IS

Opening an existing VMware-to-KVM plan restores the persisted custom compute
sizing. As asynchronous inventory responses arrive, missing host CPU-speed data
causes `applyDefaultTargetComputeSizing()` to overwrite the persisted CPU speed
with `undefined`. A description-only edit is then rejected as an invalid custom
service offering.

The update request also serializes every guided placement field even when the
operator changes only the description. The regenerated mapping is not byte-for-
byte identical to the materialized mapping, so the backend correctly rejects it
as `DR_RUNTIME_RESOURCE_EXISTS`.

### TO-BE

1. Persisted positive `targetCpuNumber`, `targetCpuSpeed`, and `targetMemory`
   values remain authoritative while an existing plan is being initialized.
2. Background inventory refresh may replace a value only when it resolves a
   valid positive replacement.
3. An explicit source workload, target compute, or target worker change starts a
   new sizing decision and does not silently reuse stale values.
4. Create-mode behavior is unchanged.
5. Description-only edits must not mutate mapping, storage, firmware, I/O, or
   disk-placement contracts.
6. Edit mode captures a compact baseline after restoring the persisted form.
   It refreshes that baseline after asynchronous inventory normalization so
   inventory-derived local IDs and disk metadata are not mistaken for operator
   edits.
   The update API receives only fields whose normalized form value changed.
7. A real placement change remains in the request and continues to be rejected
   while runtime resources exist. The UI must never weaken that backend guard.

## 4. Regression procedure

Run actions in a state-safe order: edit, pause, resume, test failover, test
cleanup, cancellation, sync recovery, reprotect, protection release, and delete.
After every action, verify the accepted Cloud Run, database terminal state,
FTCTL authority/scheduler state, and VM power state where applicable. Stop at the
first failure, preserve evidence, patch the smallest owning layer, rebuild,
deploy, and repeat from the failed action.

Protection release and delete are destructive and therefore run last. The test
plan must be recreated through the UI after deletion when continued regression
coverage is required.

## 5. Active-run cancellation contract

### AS-IS

`cancelDrRun` changes the Cloud `dr_run` row to `CANCEL_REQUESTED` and then
terminalizes it as `CANCELED`, but it does not dispatch the already implemented
`FtctlDrCancelCommand` to the coordinator Agent. A Full Seed therefore continues
to copy data after the UI reports that it was canceled. Cloud and FTCTL disagree
about the active operation, and a new action can be admitted while the old mover
still owns runtime resources.

### TO-BE

1. Queued work that was never dispatched remains a Cloud-only cancellation.
2. For an active `FTCTL_DR` run, Cloud first sends `FtctlDrCancelCommand` to the
   plan coordinator, using the exact plan and run UUIDs of the accepted work.
3. Cloud may terminalize the Run as `CANCELED` only after the typed Agent answer
   confirms command success, `accepted=true`, `state=CANCELED`,
   `terminal_authoritative=true`, `runtime_endpoints_drained=true`, and
   `transfer_activity_state=CANCELED`.
4. A missing coordinator, unavailable Agent, rejected answer, or mismatched
   plan/run UUID keeps the Run in `CANCEL_REQUESTED`; a later operator retry may
   resend the same idempotent cancellation. It must never become a false
   terminal success.
5. FTCTL `dr-cancel` stops and drains the active scheduler worker. The plan then
   exposes sync recovery instead of pretending to be `READY` with a running
   mover. `recoverDrSync` starts the scheduler again from the latest durable
   completed checkpoint; the canceled partial Full Seed is not a new baseline.
6. Admission leases are released only after Cloud cancellation terminalization,
   and NBD capacity must return to the reserved pool before another sync action
   is enabled.
7. Regression coverage includes queued cancellation, accepted FTCTL
   cancellation, rejected FTCTL cancellation, and exact plan/run identity
   validation. Existing sync, failover, and failback contracts are unchanged.
8. The list view resolves and freezes the active Run UUID before opening the
   cancellation dialog. A stale list state that has no active Run refreshes
   instead of submitting an empty `cancelDrRun.id` parameter.
9. The list-context lookup uses `page=1` together with `pagesize=20`. Cloud's
   common list contract rejects a page size without an explicit page; that
   validation failure must not be exposed as a cancellation failure.
10. `scheduler_recovery_state=REQUIRED` with
    `reseed_reason=OPERATOR_CANCELED_TRANSFER` is an operator hold, not an
    infrastructure outage. The automatic scheduler recovery controller must
    not submit `RECOVER_SYNC` for that pair. Only an explicit UI
    `recoverDrSync` action may clear the hold and restart the Full Reseed.
11. Automatic recovery remains available for transport and source-site outage
    failures. This guard must not weaken the already validated outage recovery
    path.
12. FTCTL's durable `scheduler/control.state command=stop` is authoritative
    while an operator-canceled transfer awaits recovery. Cloud must preserve
    the terminal canceled Run and expose `recoverDrSync`; neither the Cloud
    recovery controller nor FTCTL local reconcile may create a recovery Run.
    Only the explicit UI recovery action may advance the control generation to
    `run` and restart the Full Reseed.
13. Cloud admission must not depend only on runtime projection for this hold.
    If the latest terminal Run is `SYNC/CANCELED`, automatic recovery is
    rejected even while runtime projection still contains the pre-cancel
    values. An explicit UI recovery immediately creates a newer
    `RECOVER_SYNC` Run and is therefore admitted without weakening source
    outage recovery.
14. `scheduler_recovery_state=REQUIRED` is an explicit manual recovery
    condition even though the desired scheduler state is `STOPPED`. Backend
    action availability exposes only `recoverDrSync` for that hold. The UI
    presents the replication resume state as `RECOVERY_REQUIRED`; a Full
    Reseed, pause, test failover, failover, or release cannot bypass it.

## 6. Protection release and recreate ownership contract

### AS-IS

A successful protection release removes replicas and replica disks but leaves
their `dr_target_resource_claim` rows active. Recreating a plan can also retain
an automatically generated disk name after the operator changes the target VM
name. Name-based discovery can then find a released plan's volume and reject
the new run with `DR_TARGET_OWNERSHIP_CONFLICT`. The failed Run is terminal but
the plan projection may remain `SYNCING`.

### TO-BE

1. Release terminalization changes every active claim for the plan to
   `RELEASED`, clears both active unique keys, and records `released` in the
   same transaction that removes replica rows and disables the plan.
2. Historical removed replica disks do not independently block ownership
   checks. A still-attached VM and its explicit `dr.plan.*` details remain a
   safety boundary and cannot be silently adopted by a different plan.
3. In create mode, changing the target VM name updates only disk names that
   still match the previous automatic pattern. Operator-authored disk names
   remain unchanged.
4. Target ownership conflicts are authoritative terminal materialization
   failures and project the plan to `ERROR`; they must never leave a failed Run
   behind a misleading `SYNCING` plan state.
5. A create request with `startsync=false` reports only plan creation. It must
   not claim that initial synchronization was queued.
6. Source disk inventory is asynchronous. Automatic target disk names are
   normalized after mapping JSON is parsed and whenever inventory rows are
   rebuilt, so the operator-entered target VM name remains authoritative.
   Explicitly edited disk names are preserved.

## 7. Reprotect terminal convergence contract

### AS-IS

The reverse mover can make its manifest and checkpoint durable and return
`READY / reprotect-ready / 100%`, while the Run has no engine terminal journal.
Cloud correctly keeps that Run in `ACCEPTED`; treating a successful Agent
dispatch or a 100% transfer sample as terminal would race a still-live worker.

### TO-BE

1. FTCTL publishes `terminal_authoritative=true`,
   `runtime_endpoints_drained=true`, and
   `control_request_run_uuid=<REPROTECT Run UUID>` only after the reverse
   manifest and checkpoint are durable.
2. Cloud continues to terminalize REPROTECT only from that authoritative
   engine evidence. The UI remains asynchronous and may briefly show result
   convergence, but it must not report success from `agent-accepted` alone.
3. `dr-status --run` may repair an older completed REPROTECT Run only when its
   state is `READY / reprotect-ready / 100%`, its error is empty, and both
   durable files exist. The repair publishes metadata only and never repeats
   the transfer.
4. Regression verifies both normal terminal publication and the read-repair
   path. Existing forward sync, failover, failback, release, and cancellation
   contracts remain unchanged.

## 8. Reprotect completion and RPO freshness separation

### AS-IS

Cloud can receive an authoritative REPROTECT terminal journal after the reverse
manifest and checkpoint are durable, but still leave the Run in `ACCEPTED`.
The completion predicate requires the projected plan state to be exactly
`READY`. When the latest durable timestamp already exceeds the target RPO, the
same projection correctly derives `DEGRADED`; that health result then blocks an
otherwise completed operation forever.

### TO-BE

1. REPROTECT Run completion is an operation contract. It requires the exact
   `control_request_run_uuid`, `terminal_authoritative=true`,
   `runtime_endpoints_drained=true`, `READY / reprotect-ready`, an empty error,
   a non-empty reprotect session and completion timestamp, and durable reverse
   manifest/checkpoint evidence.
2. RPO freshness is a protection-health contract. It may leave the plan in
   `DEGRADED` after a successful REPROTECT Run and must not be used as a terminal
   Run predicate.
3. Successful reconciliation preserves `active_side=TARGET`, clears only Run
   failure metadata, and never rewrites a legitimate `DEGRADED` plan to
   `READY`. A later durable protection cycle may restore `READY` normally.
4. Periodic projection must terminalize a pre-deployment `ACCEPTED` Run once
   FTCTL read-repair publishes the authoritative journal; no database repair or
   transfer replay is allowed.
5. Regression coverage includes terminal reprotect with an overdue RPO. It
   asserts `Run=SUCCEEDED`, `plan=DEGRADED`, `active_side=TARGET`, and leaves the
   existing sync, test, failover, failback, cancellation, and release contracts
   unchanged.
6. `preserveCommittedTargetAuthorityAfterReprotectFailure()` is a failure-only
   guard. A successful `dr-reprotect` response must continue to Run terminal
   reconciliation even though its action field remains `dr-reprotect`.
7. Reprotect regression fixtures include the real `action` field. This prevents
   a successful response from being captured by the failure-preservation branch
   before authoritative terminal reconciliation runs.

## 9. Delegated target power and Failback preflight

### AS-IS

Reprotect intentionally records `target_power_state=POWER_ON_DELEGATED` because
Cloud owns the serving VM lifecycle. Cloud then confirms the VM is `PowerOn`
through its assigned Agent, but FTCTL transition preflight accepts only the
literal projection `POWERED_ON`. A healthy serving VM is consequently blocked
from Failback after successful Reprotect.

### TO-BE

1. Cloud continues to require a live `CheckVirtualMachineAnswer=PowerOn` from
   the assigned Agent. DB `Running` alone is never sufficient.
2. FTCTL transition preflight accepts `POWERED_ON` and
   `POWER_ON_DELEGATED` as authority-compatible projections. The delegated
   value does not replace Cloud's live power probe.
3. Authority side and generation checks remain strict. Any mismatch still
   blocks the transition.
4. Regression covers successful delegated projection, read-only state checksum,
   generation mismatch, and missing state. Existing Sync, Test Failover,
   Cleanup, Failover, Reprotect, Release, and Cancel contracts are unchanged.

## 10. Authoritative protection-view warning convergence

### AS-IS

A transient `getDrPlan` or protection-view request failure can set the detail
warning while an action is converging. Later polling successfully loads a
version 4 authoritative protection snapshot, but the stale warning remains on
screen and incorrectly implies that the current DR state is incomplete.

### TO-BE

1. A successfully parsed version 4 protection snapshot is authoritative for
   the DR detail surface and clears an earlier transient detail-load warning.
2. Invalid or non-authoritative snapshots never clear the warning.
3. Action execution and polling remain asynchronous; clearing the warning does
   not alter Run, Cycle, authority, or scheduler state.
4. UI smoke validation covers a transient warning followed by a successful
   authoritative refresh, and confirms that the current protection state stays
   visible without a stale HTTP error banner.

## 11. UI action-menu end-to-end regression evidence

### Contract

1. Operator actions are submitted through the Cloud UI. API, database, Agent,
   FTCTL, host, and hypervisor access are read-only evidence paths during the
   test.
2. A Full Reseed does not immediately satisfy normal cutover readiness. Test
   Failover and Failover remain disabled until the next durable incremental or
   no-change Cycle sets `latest_completed_incremental_verified=true`.
3. Test Failover pauses normal replication, creates a Cloud-managed isolated
   test VM, validates its boot state, and leaves the production replica
   unchanged.
4. Test Cleanup expunges the test VM, terminalizes the test session, and
   automatically resumes the scheduler. A later durable incremental Cycle is
   required before the normal cutover actions are enabled again.
5. Recover Sync is exposed only after an operator-canceled Full Reseed. It
   resumes the same canceled Cycle and must not create a duplicate Cycle.
6. `Adopt replica` and manual fence-clear controls are not applicable to an
   `FTCTL_DR` plan and remain hidden. Protection Release and plan deletion are
   destructive lifecycle operations and require a separate operator approval;
   they are not executed by an unattended menu regression.

### 2026-08-23 evidence

The `r9-01 DR Plan` UI regression completed the following actions from an
authenticated browser session:

| UI action | Run or Cycle | Result |
|---|---:|---|
| Edit plan | authoritative refresh | PASS |
| Pause replication | Run 294 | SUCCEEDED |
| Resume replication | Run 295 / Cycle 2468 | SUCCEEDED / READY |
| Full resync | Run 296 / Cycle 2469 | accepted and transferred |
| Cancel current run | Run 296 | CANCELED |
| Recover replication | Run 297 / Cycle 2469 | SUCCEEDED / READY |
| Post-reseed cutover gate | Cycle 2470 | incremental verified; actions enabled |
| Test Failover | Run 298 / test VM 282 | SUCCEEDED / Running / boot validated |
| Test Cleanup | Run 299 | SUCCEEDED / session CLEANED / VM Expunging |
| Automatic resume | Cycle 2472 | READY; scheduler RUNNING/HEALTHY |

The previously validated Failover, Reprotect, and Failback chain remains the
transition regression baseline. This menu regression does not change its
runtime, storage, authority, or lifecycle implementation.
