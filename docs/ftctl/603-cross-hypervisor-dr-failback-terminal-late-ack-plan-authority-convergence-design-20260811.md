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

# 603. Cross Hypervisor DR Failback Terminal, Late ACK, Plan Authority Convergence Design

- Date: 2026-08-11
- Status: implementation contract
- Scope: UI, API, Cloud backend, Agent, FTCTL, DB

## 1. Goal

Cloud must treat FTCTL failback commit acknowledgement as asynchronous. A
temporary `DR_FAILBACK_COMMIT_ACK_PENDING` observation is a running lifecycle
state, not a failed Run. Once FTCTL publishes acknowledged source authority and
the required post-failback checkpoint, Cloud must transactionally finish the
Run, Failback session, Plan, and replica.

The FTCTL companion is
`ablestack-qemu-exec-tools/docs/ftctl/457-ftctl-dr-failback-terminal-late-ack-plan-authority-convergence-design-20260811.md`.

## 2. Incident Evidence

For plan `ef73f5f3-9740-4bbd-8c9a-74a972e5f19f`, reverse incremental transfer
completed and the VMware VM was restored. FTCTL later acknowledged the commit
and completed checkpoint 283. Cloud Run 160, however, had already been closed
as `FAILED` with `DR_FAILBACK_COMMIT_ACK_PENDING`; Failback session 18 remained
`PROTECTION_RESUMING`.

This is a terminal-ordering defect, not a data-transfer failure.

## 3. Layer Contract

| Layer | Responsibility |
|---|---|
| UI | Show the persisted Run/session terminal result; never infer success from VM power alone |
| API | Return asynchronous acceptance and current persisted lifecycle state |
| Backend | Classify commit ACK pending as non-terminal and converge late ACK atomically |
| Agent | Relay PLAN_AUTHORITY and OPERATION status without rewriting semantics |
| FTCTL | Preserve source authority and post-failback checkpoint evidence |
| DB | Commit Run, session, Plan, replica, and terminal steps in one transaction |

## 4. Backend Design

### 4.1 Runtime projection classification

`FtctlDrRuntimeProjectionAdapter` recognizes a Failback lifecycle pending state
before generic worker/error classification when one of these contracts is met:

- error code is `DR_FAILBACK_COMMIT_ACK_PENDING`;
- phase is `COMMIT_VERIFYING` with commit outcome `UNKNOWN` or `PENDING`;
- phase is `PROTECTION_RESUMING` with commit outcome `ACKNOWLEDGED`.

The Run remains `RUNNING`, has no completion timestamp or error, and is retried
after the engine-provided interval. A data-worker `ENGINE_TERMINAL` marker does
not override this lifecycle classification.

### 4.2 Late ACK convergence

`DrFailbackLifecycleServiceImpl` remains the single owner of successful
Failback completion. When PLAN_AUTHORITY proves source authority, healthy
scheduler, acknowledged commit, and the required checkpoint, its existing
transaction updates:

- `dr_failback_session`: `COMPLETED`, post-checkpoint sequence, verification/completion timestamps;
- `dr_run`: `SUCCEEDED`, completed step, terminal runtime snapshot, cleared errors;
- `dr_run_step`: synthetic ACK-pending `runtime-projection` is normalized to `SUCCEEDED`;
- `dr_plan`: `READY`, `SOURCE`, cleared errors;
- `dr_replica`: `READY`, `POWERED_OFF`, `SOURCE`;
- associated terminal steps and authority session state.

Only the synthetic ACK-pending pseudo-failure is recoverable. Real failed
Failback history remains immutable.

### 4.3 Terminal source normalization

Successful Failback is finalized by the Cloud lifecycle transaction, not by
the reverse data worker alone. On successful convergence, `dr_run` therefore
records `terminal_source=CLOUD_LIFECYCLE`, increments `terminal_version` once,
and sets `terminal_authoritative=true`. Repeated completion handling does not
increment the version again. A pre-patch completed Run is repaired only by a
bounded cleanup after its session, authority, VM power, and checkpoint evidence
have all been verified; unrelated history is not rewritten.

### 4.4 No schema change

The required columns already exist. This patch changes classification and
convergence behavior only; no DB migration is required.

## 5. Tests

Backend tests must cover ACK pending plus an engine data-terminal marker,
clearing stale Run error fields while pending, acknowledged source authority,
the required post-failback checkpoint, and normalized terminal persistence.

FTCTL tests cover sticky authority and non-terminal commit semantics in the
companion design.

## 6. Deployment And Runtime Verification

1. Build the changed Cloud DR integration module from the WSL ext4 clone.
2. Deploy changed classes only and restart management.
3. Build FTCTL through GitHub Actions and deploy the generated package to DR hosts.
4. Verify the installed FTCTL script contains the authority-overlay and pending-terminal markers.
5. Confirm management, Mold agents, and FTCTL timers are healthy.
6. Preserve the current incremental baseline; remove only stale locks or abandoned operation state after ownership checks.
7. Run Failover, make a small guest change, then run Failback.
8. Query UI/API, FTCTL state, VM power, `dr_run`, and `dr_failback_session` before declaring PASS.

## 7. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
|---|---|---|
| ACK pending | Generic error projection can close Run as FAILED | Explicit non-terminal lifecycle state |
| Late ACK | VM/FTCTL recover while Run/session stay stale | Scheduled reconciliation atomically succeeds all records |
| Terminal source | A recovered Run may retain `ENGINE_TERMINAL` | Success records one authoritative `CLOUD_LIFECYCLE` terminal |
| DB history | Run may recover while one ACK-pending step stays failed | Run, all lifecycle steps, session, Plan, and replica have one terminal outcome |
| Operator PASS | Manual VM and byte checks only | VM, incremental metrics, FTCTL, Run, session, Plan, replica all verified |

## 8. Follow-up Projection Consistency

Protection-summary sequence joins and successful-session failure metadata
cleanup are specified by
`604-cross-hypervisor-dr-protection-summary-and-success-terminal-projection-design-20260811.md`.
That contract is part of terminal convergence: a successful terminal state is
not complete until the read model displays the exact latest durable cycle and
contains no stale failure-only metadata.

## 9. Committed Source Authority Projection Barrier

Once a Failback session reaches `PROTECTION_RESUMING` with acknowledged commit,
acknowledged engine authority, source `POWERED_ON`, and target `POWERED_OFF`, the
session is the authoritative owner of the transition. Plan-authority polling may
still receive an older target-site runtime sample. That sample must not change
the Plan back to `TARGET`, `FAILED_OVER`, or `DEGRADED`.

`FtctlDrRuntimeProjectionAdapter` therefore resolves the latest Failback session
even when the periodic projection has no finite operation Run. While the above
contract is active it projects only `SYNCING / SOURCE`, clears stale target-only
errors, and keeps the replica `READY / POWERED_OFF / SOURCE`. The Failback
lifecycle remains the only component allowed to advance the session to
`COMPLETED`; it does so after the remote source scheduler is healthy and the
required post-Failback checkpoint is durable.

After completion, a source-side authority sample and the completed session
converge the Plan to `READY / SOURCE`. A later, genuinely committed Failover is
not blocked because the barrier applies only to the non-terminal
`PROTECTION_RESUMING` state. The regression suite must include the ordering
`source authority commit -> late target FAILED_OVER sample -> post-Failback
checkpoint` and prove that Plan authority never moves backward.
