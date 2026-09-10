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

# Cross Hypervisor DR Terminal Authority Ordering

> 2026-08-06 pre-terminal ordering correction:
> [599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md](599-cross-hypervisor-dr-forward-cutover-commit-contract-and-authority-convergence-design-20260806.md)
> is authoritative when the target VM is running but FTCTL ACK is not durable.
> This document remains authoritative only after a committed cutover session exists.

## Purpose

This document supplements the post-failover runtime and UI convergence design.
It records a correction found by live preflight after deployment.

## Observed State

The deployed environment contained this valid cutover evidence:

```text
Plan: FAILED_OVER
Active side: TARGET
Cutover session: FAILED_OVER / PROMOTED / ACKNOWLEDGED
```

The older runtime row still contained a larger scheduler lease and authority
sequence:

```text
Scheduler: STOPPED
Desired scheduler state: RUNNING
Owner matched: true
Protection state: DEGRADED
```

The Cloud projection rejected the lower terminal FTCTL status before checking
the committed cutover session. The UI consequently displayed a stale warning
even though the target VM was the committed serving authority.

## Authority Ordering Contract

For a normal SOURCE-side protection lifecycle, Cloud rejects a lower scheduler
lease or authority sequence. After a committed cutover, the cutover session is
the durable authority and the old forward scheduler no longer owns the plan.

`FtctlDrRuntimeProjectionAdapter.projectProtectionAuthority()` therefore uses
this ordering:

1. Resolve committed TARGET authority from `dr_cutover_session`.
2. If the Plan is `FAILED_OVER/TARGET` and the session is
   `FAILED_OVER/PROMOTED/ACKNOWLEDGED`, bypass forward-scheduler stale sequence
   rejection.
3. Persist the canonical terminal tuple:
   - scheduler state `STOPPED`
   - desired scheduler state `STOPPED`
   - scheduler health `SUPPRESSED`
   - recovery state `SUPPRESSED`
   - replication activity `STOPPED`
   - scheduler PID not alive
   - owner not matched
   - no active worker identity
   - protection state `FAILED_OVER_UNPROTECTED`
4. Freeze displayed RPO at the cutover point. The authoritative value is
   `max(0, cutover_session.completed_at - last_source_checkpoint_at)`.
   This formula also repairs a legacy Plan whose `target_ready_rpo_seconds`
   was incorrectly aged after cutover.
5. Preserve lease and sequence rejection for every non-terminal protection
   state.
6. Idempotently backfill typed `dr_cutover_disk` rows from active replica disk
   records when an older committed session has no disk audit rows.

## Regression Test

`periodicAuthorityProjectionDoesNotReapplyTerminalReprotectError()` seeds the
existing runtime with:

```text
lease_epoch=99
authority_sequence=999
scheduler_desired_state=RUNNING
owner_matched=true
```

It then projects a lower-sequence terminal status backed by a committed cutover
session. The test requires the complete canonical terminal tuple and verifies
that the historical reprotect error does not replace current TARGET authority.

`committedTargetProjectionFreezesPlanRpoAtCutover()` seeds a stale 6500-second
Plan RPO and requires both Plan and runtime projection to converge to the
209-second cutover RPO, regardless of the host status query time.

## AS-IS / TO-BE

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| Cloud projection | Scheduler sequence checked before cutover authority | Committed cutover authority checked first |
| Runtime DB | `STOPPED/RUNNING/DEGRADED`, owner still matched | `STOPPED/STOPPED/FAILED_OVER_UNPROTECTED`, owner false |
| RPO | Continued aging after cutover | Frozen at the committed target-ready RPO |
| Disk audit | Legacy committed session may have zero typed rows | Projection backfills one row per replica disk |
| UI/API | Historical scheduler warning appeared current | Current TARGET authority is informational and actionable |
| Safety | Lower sequence always rejected | Rejection retained except for durable terminal TARGET authority |

## Implementation And Deployment Verification

The final implementation was verified on 2026-07-30 as follows:

- `FtctlDrRuntimeProjectionAdapterTest`: 25 tests passed, with no failures or
  errors.
- The changed Disaster Recovery Maven module packaged successfully from the
  WSL ext4 build clone.
- The Cloud deployment updated only
  `FtctlDrRuntimeProjectionAdapter.class` and `DrCutoverDiskVO.class` in the
  management JAR.
- The active management webapp retained `WEB-INF`, and `/client/` returned
  HTTP 200 after restart.
- FTCTL GitHub Actions run `30511607024` completed successfully for commit
  `d6cf1a3403dedd1c83ac35b409ba72ea549be4d8`.
- All three DR compute hosts run `ablestack_vm_ftctl-0.9.1-1.noarch`, with
  `mold-agent` and `ablestack-vm-ftctl.timer` active.

The live Windows DR Plan
`2514a846-64a2-4bc7-ba88-38a874410782` converged to:

```text
Plan state: FAILED_OVER
Active side: TARGET
Protection state: FAILED_OVER_UNPROTECTED
Scheduler: STOPPED / desired STOPPED
Scheduler health and recovery: SUPPRESSED
Replication activity: STOPPED
Owner matched: false
RPO mode: CUTOVER_FROZEN
Displayed RPO: 209 seconds, MET
Current severity: INFO
Active runs: 0
```

The legacy committed cutover session now has two typed disk audit rows. Both
are `RBD/PROMOTED`, reference the Cloud target volume IDs and UUIDs, and carry
checkpoint sequence `1192` plus the committed manifest SHA-256.
## 2026-07-30 Failback Terminal Ordering Follow-up

Failback terminal ordering은 `SOURCE authority commit -> scheduler baseline seed ->
immediate checkpoint -> Cutover authority end` 순서를 사용한다. 기준 checkpoint와
같은 sequence의 재개 cycle은 terminal 증거로 인정하지 않는다. 구체적인 API
수락, transition severity, DB 및 FTCTL 인계 계약은 문서 583을 따른다.
