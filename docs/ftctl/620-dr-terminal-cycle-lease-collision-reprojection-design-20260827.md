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

# DR Terminal Cycle Lease Collision Reprojection Design

## 1. Purpose

This design closes a Cloud projection gap observed after an FTCTL scheduler
restart or package replacement. The data plane can complete a durable cycle
while Cloud keeps the accepted `SYNC` run in `RUNNING` because the engine
sequence was previously used by an older scheduler lease.

The change must preserve all validated VMware to ABLESTACK and ABLESTACK RBD
paths. It changes only Cloud cycle identity and strict terminal recovery. It
does not change copy, CBT, RBD, target materialization, failover, or failback
behavior.

## 2. Incident Contract

The affected state has all of the following evidence:

- FTCTL worker state is `TERMINAL_PUBLISHED` with exit code `0`.
- `control_request_run_uuid` equals the Cloud run UUID.
- Engine state is `READY` and step is `full-resync-completed`.
- The manifest and checkpoint paths contain the same run UUID.
- The target durable timestamp is not older than the Cloud run start.
- A historical `dr_sync_cycle` already owns the engine sequence under a
  different scheduler lease.

Cloud must not require a manual DB update in this state. It must create or
resolve the current lease's canonical cycle, bind it to the accepted run, and
allow the normal terminal projection path to converge the run and plan.

## 3. Identity Model

Engine sequence alone is not a durable Cloud identity across scheduler lease
changes. Cloud resolves a cycle in this order:

1. `(plan_id, scheduler_session_uuid, scheduler_lease_epoch, cycle_token)`
2. Legacy `(plan_id, sequence)` only when the stored row has no lease identity
3. A new canonical Cloud sequence when the engine sequence collides with a row
   from another lease

The canonical sequence is at least the engine sequence, greater than the
latest Cloud cycle, and not lower than FTCTL `authority_sequence`. The FTCTL
cycle token remains attached so accepted-run ownership is not inferred from a
newer scheduler producer UUID.

## 4. Strict Late Terminal Recovery

`FtctlDrRuntimeProjectionAdapter` may reconstruct a missing completed Full Seed
cycle only when every incident-contract predicate in section 2 is true. The
reconstructed row is persisted as `FULL_SEED / READY / LOCAL_DURABLE`, linked
to the Cloud run, and then used by the existing accepted-cycle terminal gate.

Missing, stale, mismatched, failed, or non-owned evidence remains non-terminal.
The recovery path never marks a copy successful from progress percentage alone.

## 5. Transaction And Retry Behavior

- Cycle resolution and terminal binding run under the existing plan lock and
  projection transaction.
- Repeated polling resolves the same scheduler-cycle identity and is
  idempotent.
- A later FTCTL cycle with the global sequence floor naturally supersedes the
  one-time compatibility cycle.
- Cloud background projection or the UI Update action is sufficient to recover
  an accepted run. Direct SQL repair is prohibited.

## 6. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
|---|---|---|
| Cycle identity | `(plan_id, sequence)` | Lease-aware scheduler cycle identity |
| Sequence reuse | Historical row is reused or current row is skipped | Allocate a canonical Cloud sequence |
| Full Seed terminal | Requires a pre-existing non-colliding Cycle | Strict terminal evidence can reconstruct the missing durable Cycle |
| Run result | `RUNNING` can remain after FTCTL terminal | Accepted Run converges to `SUCCEEDED` |
| Plan/UI | `SYNCING` and action gating remain stale | Plan converges to `READY`; actions become usable |
| Recovery | Manual DB repair | Automatic idempotent reprojection |

### Post-terminal scheduler advance

A completed canonical Full Seed Cycle owned by the accepted Cloud Run is an
immutable terminal proof even after the live scheduler advances
`control_request_run_uuid` to the next incremental producer. This fallback
requires matching `run_id`, `accepted_cycle_sequence`, and
`accepted_cycle_token`, plus a terminal `READY` and durable Cycle. It cannot
consume another Run's Cycle or an incomplete transfer. A Run completed through
this proof records `terminal_source=CYCLE_DURABLE` and
`terminal_authoritative=true`.

For a Cloud-managed KVM target, target VM and network presence are evaluated
from the active `dr_replica` binding rather than FTCTL's non-owning runtime
flags. FTCTL remains authoritative for target storage durability and checkpoint
publication. VMware targets and directions without a Cloud target binding keep
the strict engine presence checks.

### Cloud-managed KVM materialization authority and UI readiness

`dr-status` can correctly report an idle scheduler and a durable completed
cycle while its non-owning `target_vm_present` and `target_network_present`
flags remain false. For a Cloud-managed KVM target these two objects are
created and owned by Cloud, so the active `dr_replica.target_vm_id` and the
latest target-ready restore point are the authoritative materialization proof.
The FTCTL evidence is still mandatory for target storage, durable checkpoint,
and completed-cycle integrity.

The runtime projection therefore derives `targetMaterialized` from either an
explicit FTCTL materialization result or all of the following conditions:

1. the scheduler is healthy and the current cycle is `IDLE` or `COMPLETED`;
2. a durable checkpoint and latest target-ready restore point exist;
3. the active KVM replica has a non-null `target_vm_id`;
4. FTCTL does not report target storage or restore-point absence.

The UI presents two independent contracts. The primary status continues to use
the projected protection state, while execution readiness uses
`readinessstate`. A transient replication state must not overwrite or duplicate
the execution-readiness value.

| Area | AS-IS | TO-BE |
|---|---|---|
| KVM target VM/network authority | FTCTL false flags force `SYNCING` | Active Cloud replica and target-ready checkpoint prove VM/network materialization |
| Storage and checkpoint authority | Mixed with Cloud-owned object presence | FTCTL remains strict authority for storage and durable checkpoint |
| Runtime convergence | Healthy idle/completed runtime can remain `SYNCING` | Runtime converges to `READY` when all split-authority conditions pass |
| UI execution readiness | Reuses the protection status | Displays `readinessstate` independently |
| Regression proof | No combined projection/UI assertion | Java projection and UI state-resolution tests cover the split contract |

## 7. Checkpoint And Canonical Sequence Separation (2026-09-04)

The scheduler checkpoint sequence and the Cloud row sequence are different
domains after a scheduler lease collision. The checkpoint sequence identifies
the FTCTL data generation and remains encoded in `cycle_token`. The Cloud row
sequence is a monotonically increasing persistence key used to satisfy the
existing `(plan_id, sequence)` uniqueness contract.

Cloud must therefore apply these rules consistently:

1. A new row uses the engine checkpoint sequence only when it is greater than
   the current Cloud sequence floor.
2. If the Cloud floor is already equal to or greater than the engine sequence,
   Cloud allocates a new canonical row sequence even when there is no row at
   that exact engine sequence.
3. Runtime-to-Cycle matching uses the checkpoint sequence parsed from
   `cycle_token`, never the canonical row sequence.
4. API and UI transfer sequence fields expose the checkpoint sequence. The
   protection-view cache may include `canonicalSequence` for diagnostics, but
   it must not use that value as the data generation shown to users.
5. `authority_sequence` remains projection ordering metadata and must never be
   interpreted as a checkpoint sequence or transfer generation.

This closes the observed interval where an older synthetic Cloud sequence with
`FULL_SEED/source_runtime_unavailable` masked a newer durable `NO_CHANGE`
checkpoint. It does not change provider selection, transfer, bitmap, VMware
CBT, RBD diff, failover, or failback behavior.

| Area | AS-IS | TO-BE |
|---|---|---|
| New Cycle allocation | Uses the lower engine sequence when no exact collision exists | Applies the Cloud sequence floor before every insert |
| Runtime matching | Compares checkpoint sequence to canonical row sequence | Compares checkpoint sequence to `cycle_token` generation |
| API/UI | Can suppress current completed metrics after a lease collision | Shows the latest durable checkpoint and its actual transfer mode |
| Provider paths | Projection error can look like a Full Seed regression | VMware CBT, RBD diff, and qcow2 bitmap decisions remain isolated and unchanged |

## 8. Verification Gate

1. Unit test a reused engine sequence under a new scheduler lease.
2. Run the disaster-recovery plugin test suite and changed-module Maven build.
3. Deploy the changed Cloud classes and the FTCTL package containing the global
   sequence floor to the same test cluster.
4. Verify package versions and installed script hashes on every compute host.
5. Without modifying DB rows, refresh projection and verify:
   - accepted `SYNC` run is `SUCCEEDED`;
   - current plan is `READY / ENABLED`;
   - latest durable Cycle belongs to the current scheduler lease;
   - UI actions are available;
   - no active worker, lock, or NBD endpoint remains.
6. For a Cloud-managed KVM replica, set the FTCTL VM/network presence flags to
   false while retaining a valid Cloud replica, target-ready restore point,
   durable storage, and healthy idle/completed scheduler. Verify the projected
   protection state is `READY`.
7. In the UI, verify protection status and execution readiness are rendered
   from `protectionstate` and `readinessstate` respectively, including dark
   mode, after the operator presses Update.

## 9. Failed requested cycle followed by durable same-Run Full Seed

### 9.1 Failure

A transient VMware CBT query fault can publish `FAILED` before systemd restarts
the scheduler. If that same Cloud Run subsequently owns a newer durable Full
Seed, the authority projection stores the valid Cycle but the completed failed
Run is no longer selected for operation-scoped status. Target materialization
is therefore skipped and the UI remains `SYNCING` without a target VM.

### 9.2 Strict recovery contract

Cloud may select and reopen a completed failed `SYNC/FULL_RESEED` Run only when:

1. a newer completed `FULL_SEED` or `FULL_RESEED` Cycle is owned by the same
   Cloud Run row;
2. its state is ready/complete, commit is durable, and token equals the Plan
   UUID plus the engine checkpoint generation;
3. operation-scoped FTCTL status names the same control Run and reports an
   authoritative successful terminal (`TERMINAL_PUBLISHED`, exit code `0`);
4. runtime state is `READY/full-resync-completed`.

Cloud then rebinds `accepted_cycle_sequence/token`, clears the obsolete Run
failure, and invokes the existing target-materialization workflow. It does not
mark the Run successful until the Cloud target VM and restore-point contracts
are satisfied. Plan authority continues to use the latest scheduler Cycle, so
a later incremental is never replaced by the recovered Full Seed.

| Area | AS-IS | TO-BE |
|---|---|---|
| Projection Run selection | Completed failed Run is ignored | Strict newer same-Run durable proof makes it queryable |
| Accepted Cycle | Superseded failed sequence remains bound | Rebind to the recovered durable Full Seed |
| Target materialization | Never enqueued | Existing idempotent materializer resumes |
| Failure safety | Broad late success would hide errors | Run, terminal, token, mode, durability, and sequence must all match |
| Existing providers | Shared behavior may regress | No copy/provider decision changes; baseline path tests remain mandatory |
