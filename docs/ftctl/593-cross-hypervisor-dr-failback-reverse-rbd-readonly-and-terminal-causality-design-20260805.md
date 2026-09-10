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

# 593. Cross Hypervisor DR Failback Reverse RBD Read-Only And Terminal Causality Design

- Date: 2026-08-05
- Status: read-only and terminal-grace baseline; live-worker reconciliation is superseded by document 594
- Scope: UI, API, Cloud backend, Agent contract, FTCTL boundary, DB/cache
- Parent data contract: [588](588-cross-hypervisor-dr-bidirectional-incremental-replication-and-failback-data-contract-design-20260801.md)
- Failback lifecycle parent: [591](591-cross-hypervisor-dr-failback-initial-reverse-seed-and-early-failure-convergence-design-20260804.md)
- Runtime preflight parent: [592](592-cross-hypervisor-dr-failback-live-runtime-preflight-and-ux-convergence-design-20260804.md)
- Engine companion: `ablestack-qemu-exec-tools/docs/ftctl/450-ftctl-dr-reverse-rbd-snapshot-readonly-nbd-and-terminal-causality-design-20260805.md`
- Live-worker correction: [594](594-cross-hypervisor-dr-live-worker-and-terminal-reconciliation-design-20260805.md)
- Route and terminal-convergence correction: [595](595-cross-hypervisor-dr-failback-route-contract-and-terminal-convergence-design-20260805.md)

## 1. Objective

Complete the first KVM-to-VMware Failback reverse seed through immutable RBD
snapshots and expose one causally correct terminal result at every layer. The
UI and API remain asynchronous: the API returns acceptance, Agent supervises
FTCTL, and Cloud projects durable evidence for the UI.

This revision corrects two defects observed in one live Run:

1. FTCTL opened an RBD snapshot through `qemu-nbd` without read-only semantics.
2. Cloud terminalized from an absent worker PID before FTCTL published its
   typed terminal, after which a premature data gate supplied a third error.

## 2. Verified Incident And Preflight

Plan `7889e625-371a-48f9-b553-54e311481170`, Failback Run
`8e413a38-981a-4c64-8b93-f62140c6c986`, correctly selected
`FULL_REVERSE_SEED` because the initial reverse baseline was absent. Preflight
found the running KVM source, two disks, 150 GiB estimated virtual bytes, and a
ready VMware writer.

The transfer then failed with:

```text
qemu-nbd: Failed to blk_new_open
'rbd:rbd/w22-01-dr-disk-0@ftctl-dr-7889e625-12-0':
rbd snapshots are read-only
```

The authoritative engine terminal was exit `86`, error
`DR_REVERSE_SNAPSHOT_OR_NBD_FAILED`, phase `REVERSE_TRANSFER`, component
`kvm-vmware-mover`. Cloud had already projected `DR_FAILBACK_WORKER_EXITED`
with exit `70`; the session later recorded `DR_FAILBACK_DIRECTION_MISMATCH`.
The latter two are state-causality defects, not the transfer root cause.

A controlled host preflight attached a temporary snapshot with:

```bash
qemu-nbd --read-only --connect=/dev/nbd15 --format=raw \
  rbd:rbd/w22-01-dr-disk-0@codex-ro-preflight-20260805
```

The device became readable at `107374182400` bytes. It was disconnected and
the snapshot removed with no NBD or RBD residue. This validates the proposed
source-open semantics in the actual environment without executing Failback.

## 3. Safety And Ownership Invariants

1. Cloud owns lifecycle, authority, target VM/network/storage, and UI state.
2. Agent transports and monitors work; it does not invent engine failures.
3. FTCTL owns transfer mechanics and typed terminal evidence.
4. Every immutable RBD snapshot source is opened read-only.
5. TARGET stays authoritative until reverse data and VMware guest gates pass.
6. Worker PID disappearance is an observation, not immediate terminal failure.
7. Later FTCTL terminal evidence may correct an earlier watchdog result.
8. The data gate executes only after the session reaches `DATA_READY`.
9. UI/API never wait synchronously for movers or poll hosts directly.
10. Cleanup removes only resources owned by the current Run.

## 4. Corrected End-To-End Sequence

```text
UI -> executeDrPlanAction(FAILBACK)
API -> validate and return accepted job/run identity
Backend transaction -> persist Run + Session + direction/provider/intent
Backend -> dispatch Agent command after commit
Agent -> invoke FTCTL start and monitor asynchronously
FTCTL -> choose FULL_REVERSE_SEED when baseline is absent
FTCTL -> create immutable RBD snapshots
FTCTL -> qemu-nbd --read-only each source snapshot
FTCTL -> transfer through the VDDK writer
FTCTL -> atomically publish typed terminal JSON
Agent -> relay terminal provenance and evidence
Backend -> merge Run + Session terminal state transactionally
UI -> refresh cached protection view and show one canonical result
```

If the worker exits before terminal JSON is visible, the state is
`TERMINAL_PUBLICATION_PENDING`. Reconciliation retries asynchronously for a
bounded grace period; the API request thread never sleeps or loops.

## 5. Canonical Terminal Evidence

Evidence precedence is:

```text
ENGINE_TERMINAL > WATCHDOG_DERIVED > GATE_DERIVED
```

The canonical object is:

```json
{
  "terminalSource": "ENGINE_TERMINAL",
  "terminalVersion": 1,
  "workerExitCode": 86,
  "driverExitCode": 86,
  "failurePhase": "REVERSE_TRANSFER",
  "failureComponent": "kvm-vmware-mover",
  "errorCode": "DR_REVERSE_SNAPSHOT_OPEN_FAILED",
  "errorMessage": "The reverse source snapshot could not be opened read-only.",
  "retryable": true
}
```

`terminalVersion` increases monotonically per Run. A provisional lower-rank
result can be replaced by newer, higher-rank evidence. The correction creates
an audit event with previous and new evidence.

Stable errors are:

| Code | Meaning | Policy |
|---|---|---|
| `DR_REVERSE_SNAPSHOT_OPEN_FAILED` | immutable source could not attach read-only | retry after correction |
| `DR_TERMINAL_PUBLICATION_TIMEOUT` | terminal absent after grace | inspect Agent/FTCTL |
| `DR_REVERSE_DATA_GATE_FAILED` | transfer finished but durable data evidence failed | retain TARGET |

## 6. UI Design

Apply the contract in the current DR Plan action, detail, protection, and
history components, including `ui/src/views/infra/dr/DrPlanList.vue` and the
Korean/English locale files. Exact sibling component names must be confirmed
from the repository at implementation time.

1. Failback confirmation submits once and closes after API acceptance.
2. Detail polling reads the cached Cloud view, never host runtime directly.
3. One banner shows phase, component, reason, and safe next action.
4. Technical details are expandable and include source/version and engine exit.
5. Pending publication shows `Finalizing operation result`, not `Failed`.
6. Retry appears only for `retryable=true` while authority remains TARGET.
7. List, detail, protection, and history use the same canonical result.

Dark mode uses existing semantic alert, text, border, disabled, hover, and
focus tokens. Fixed white/yellow backgrounds and black text are prohibited.

## 7. API Design

`executeDrPlanAction` stays start-only:

```json
{
  "accepted": true,
  "jobid": "...",
  "runid": "...",
  "operation": "FAILBACK"
}
```

`getDrPlan`, `listDrRuns`, and `getDrProtectionView` expose identical fields:

- `terminalsource`, `terminalversion`;
- `failurephase`, `failurecomponent`;
- `errorcode`, `errormessage`;
- `retryable`, `terminalpublicationpending`.

Compatibility fields are derived from this object and cannot select their own
error independently.

## 8. Cloud Backend Design

### 8.1 Pre-dispatch transaction

The action service persists before dispatch:

- `DrRunVO`: operation, accepted state, request identity, authority generation;
- `DrFailbackSessionVO`: `ABLESTACK_TO_VMWARE`, provider pair, `FAILBACK` intent;
- an accepted operation event.

Direction, provider, and intent become immutable after dispatch.

### 8.2 Lifecycle gate

`DrFailbackLifecycleServiceImpl` uses an explicit state guard:

```java
switch (session.getState()) {
case PREPARING:
case TRANSFERRING:
    reconcileRuntimeOnly();
    return;
case DATA_READY:
    drFailbackDataGateService.evaluate(session, run);
    continuePowerAndAuthorityTransition();
    return;
case FAILED:
case COMPLETED:
    return;
default:
    failTyped("DR_FAILBACK_INVALID_SESSION_STATE");
}
```

`DrFailbackDataGateServiceImpl` is never called during `PREPARING` or
`TRANSFERRING`, so missing data-ready direction cannot mask transfer failure.

### 8.3 Projection merge and grace

`FtctlDrRuntimeProjectionAdapter` delegates to one evidence merger:

```java
TerminalEvidence merged = terminalEvidenceMerger.merge(
    persistedEvidence, observedEvidence);

if (merged.isHigherPrecedenceOrNewer()) {
    updateRunAndSessionInOneTransaction(merged);
    persistTerminalCorrectionEvent(previous, merged);
}
```

Absent PID with no terminal JSON sets pending plus first-observed time, queues
a reconciliation after 2 seconds, and accepts the engine terminal when it
appears. Only after a configurable 10-second grace is
`DR_TERMINAL_PUBLICATION_TIMEOUT` synthesized.

## 9. Agent Contract

Agent command/status DTOs preserve without remapping:

- Run/session/action identity;
- terminal source/version;
- worker/driver exit codes;
- failure phase/component and typed error;
- retryability and terminal-pending state;
- capability `dr-reverse-rbd-snapshot-readonly-v1`.

Cloud rejects Failback before dispatch if the active Agent/FTCTL lacks that
capability, preventing mixed-version execution.

## 10. FTCTL Boundary

Paired engine document 450 changes:

- `lib/ftctl/dr_kvm_vmware_mover.sh`: attach RBD snapshot sources using
  `qemu-nbd --read-only --format=raw --cache=none` through one helper;
- `lib/ftctl/dr_runtime.sh`: atomically publish terminal JSON before clearing
  worker identity and apply dead-worker grace;
- capability output: `dr-reverse-rbd-snapshot-readonly-v1`.

Cloud does not duplicate RBD, NBD, VDDK, snapshot, or cleanup logic.

## 11. DB And Cache Design

No schema migration is expected. Existing Run/FailbackSession status and
detail fields can represent terminal provenance, phase/component, exits,
direction/provider evidence, and raw status JSON. Add an idempotent column only
if implementation inspection proves an essential field otherwise requires
opaque-text parsing.

1. Run and Session canonical terminal fields update in one transaction.
2. Cache refresh runs after commit.
3. Cache identity includes Run and terminal version.
4. Stale cache cannot downgrade a higher terminal version.
5. Late correction invalidates list and detail caches together.
6. Cleanup preserves Run/session/event history.

### 11.1 Completed Run And Current Authority Reconciliation

A failed Failback Run is immutable history even when a later fenced abort
restores the serving TARGET authority. Periodic projection therefore handles
the two records independently:

1. keep the completed Run `FAILED` with its original engine error;
2. accept terminal rollback evidence only when `active_side=TARGET`,
   `failback_commit_outcome=ROLLED_BACK`, `rollback_state=COMPLETED`, source is
   powered off, and target is powered on;
3. clear `dr_plan.last_error_*` and `dr_plan_runtime.error_*` because the
   current authority is no longer in error;
4. preserve `FAILED_OVER/FAILED_OVER_UNPROTECTED` and the serving replica;
5. allow a fresh Failback only after the live preflight returns `READY`.

This reconciliation also applies when the failed Run already has a completion
timestamp. A completed history row must not prevent current authority from
converging after a later engine rollback acknowledgement.

## 12. Verification Design

### 12.1 FTCTL

1. Assert exact `qemu-nbd` args contain `--read-only` and `--cache=none`.
2. Reject writable attachment of an RBD snapshot source.
3. Verify two-disk ordering, byte totals, and idempotent cleanup.
4. Verify terminal durability before worker identity removal.
5. Verify absent PID during grace returns pending, not exit `70`.

### 12.2 Cloud and Agent

1. `TRANSFERRING` does not invoke the data gate.
2. `DATA_READY` invokes it exactly once.
3. Engine terminal replaces provisional watchdog evidence.
4. Gate error cannot replace an engine transfer error.
5. Run/session persistence rolls back atomically on failure.
6. All read APIs serialize the same terminal object.
7. Start API returns accepted without waiting.
8. Agent preserves all evidence and capability fields.

### 12.3 UI

1. Modal closes on accepted response.
2. Pending state refreshes asynchronously without blocking navigation.
3. One result appears consistently in all DR Plan views.
4. Retry follows capability, retryability, and authority gates.
5. Dark-mode and disabled-state contrast match existing standards.

### 12.4 Live acceptance

1. Verify capability on the active Agent host.
2. Repeat read-only attachment preflight and verify cleanup.
3. Execute Failback from TARGET authority.
4. Confirm two disks and non-zero reverse bytes.
5. Confirm VMware materialization and isolated guest boot checks.
6. Commit authority only after data and guest gates pass.
7. Compare UI, API, DB, Agent, FTCTL, VMware, and KVM state.
8. Confirm no NBD, snapshot, lock, or job residue.

## 13. Recommended Implementation Priority

1. P0: FTCTL read-only attachment helper and tests.
2. P0: atomic terminal publication and dead-worker grace.
3. P0: Agent evidence/capability passthrough.
4. P0: Cloud `DATA_READY` gate and evidence merger.
5. P0: transactional Run/session convergence.
6. P1: API response unification and async reconciliation.
7. P1: UI canonical result, pending state, retry, and dark mode.
8. P1: changed Maven modules/UI build and qemu Actions package.
9. P2: paired deployment, capability/service verification, cleanup.
10. P2: one operator-triggered Failback acceptance test.

## 14. Error Cause And AS-IS / TO-BE

| Layer | Error cause | AS-IS | TO-BE |
|---|---|---|---|
| UI | competing API terminal reasons | misleading changing errors | one canonical result and details |
| API | independent serializers | Run/session disagree | shared terminal object |
| Backend | dead PID terminalizes early | exit 70 masks exit 86 | pending grace and precedence |
| Lifecycle | data gate runs too early | direction mismatch masks transfer | gate only at `DATA_READY` |
| Agent | provenance incomplete | observation looks like engine result | preserve source/version/exits |
| FTCTL | RBD snapshot opened writable | reverse seed stops before VDDK | always attach read-only |
| DB/cache | independent terminal updates | stale contradictory state | transactional versioned merge |
| Safety | retry reason uncertain | operator cannot trust state | retain TARGET and gate retry |

## 15. Completion And Operator Handoff

Completion requires one canonical terminal across all layers, successful
two-disk transfer from read-only snapshots, zero residue, and no authority or
power transition before reverse data and guest gates pass.

No operator action is required during design, implementation, build,
deployment, or cleanup. After those steps pass, the operator performs exactly
one action: execute Failback once from the prepared Plan.

## 15. 2026-08-05 Live Worker Reconciliation Addendum

A later live Run proved that VDDK transfer bytes can continue advancing after
Cloud has closed the Run from provisional worker evidence. The current defect
is therefore broader than terminal-publication grace. Cloud must classify
engine terminal evidence, live transfer evidence, and conflicting observation
before it changes Run or FailbackSession state.

Document 594 supersedes this document wherever a single worker observation can
terminalize a Run. It adds `RECONCILIATION_REQUIRED`, blocks duplicate
mutations, preserves TARGET authority, and accepts terminal state only from an
authoritative engine terminal or repeated dead-and-drained proof.
