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

# 602. Cross Hypervisor DR Cutover Reverse Baseline And Terminal Convergence Design

> Follow-up: `603-cross-hypervisor-dr-failback-terminal-late-ack-plan-authority-convergence-design-20260811.md`
> defines ACK-pending non-terminal classification and atomic Cloud Run/session
> convergence after the post-failback checkpoint.

- Date: 2026-08-11
- Status: implementation contract
- Scope: UI, API, Cloud backend, Agent, FTCTL, DB
- Engine companion: `ablestack-qemu-exec-tools/docs/ftctl/456-ftctl-dr-cutover-reverse-baseline-and-terminal-convergence-design-20260811.md`

## 1. Root Cause

The data mover correctly fell back to a full reverse seed because no durable
KVM baseline existed. The missing step was earlier: Failover did not snapshot
the fully synchronized KVM replica before guest preparation and activation.
Separately, Cloud marked the Failback Run and Plan complete while persisting the
raw FTCTL `SYNCING / protection-resuming` payload as the latest runtime JSON.
The UI merged that stale payload over the terminal DB state.

## 2. Cross-Layer Contract

| Layer | Responsibility |
|---|---|
| UI | Submit asynchronous actions; display effective reverse mode, bytes, and authoritative terminal state |
| API | Reuse existing Failback preflight and Run fields; no synchronous transfer wait |
| Backend | Persist a normalized terminal runtime snapshot without dropping transfer evidence |
| Agent | Relay FTCTL status and preflight fields unchanged |
| FTCTL | Create cutover baseline, perform extent transfer, publish terminal state |
| DB | Persist existing Run/session/runtime JSON atomically; no schema migration |

## 3. State And Data Flow

```text
Failover final CBT durable
  -> FTCTL RBD cutover snapshots
  -> baseline.json origin=FAILOVER_CUTOVER
  -> TARGET authority
  -> KVM guest changes
  -> Failback preflight effectiveMode=REVERSE_FINAL
  -> rbd diff extents -> VDDK writes
  -> VMware boot and Cloud authority commit
  -> required forward CBT checkpoint
  -> FTCTL READY/COMPLETED
  -> Cloud normalized terminal snapshot
  -> UI READY with retained transfer metrics
```

## 4. Backend And UI Design

`DrFailbackLifecycleServiceImpl.completeLifecycle` must deep-copy the latest
runtime JSON and normalize only lifecycle fields: state, step, progress,
Failback phase, scheduler health, transfer activity, terminal authority, and
errors. Checkpoint identity, byte counts, effective mode, throughput, and
baseline generation remain untouched.

`DrProtectionInfoTab.protectionPlan` normally merges runtime over the plan. If
the latest Run is a successful `FAILBACK`, it must project `READY`, completed
phase, 100 percent, idle transfer activity, and terminal authority. This is a
defensive rule for one refresh interval or legacy cached rows; live running
Failback data remains runtime-driven.

## 5. API, Agent, And DB Impact

- API: no new command and no blocking call; existing `effectivemode`,
  `initialseedrequired`, checkpoint, and transfer byte fields are normative.
- Agent: no new dispatch contract; it transports new FTCTL status markers via
  the existing status envelope.
- DB: no DDL. Existing `dr_run.last_status_json`, Failback session details,
  plan state, replica runtime JSON, and cutover authority are updated in one
  transaction.

## 6. Acceptance Gates

1. Pre-Failover state is SOURCE/READY and the latest forward checkpoint is durable.
2. Failover creates a baseline with `origin=FAILOVER_CUTOVER` before target power-on.
3. A small KVM guest write is made after Failover.
4. Failback preflight reports `REVERSE_FINAL`, `initialSeedRequired=false`.
5. `0 < transferPayloadBytes < virtualBytes`; mode is not `FULL_REVERSE_SEED`.
6. VMware boots and contains the guest write.
7. Run, Failback session, Plan, replica, FTCTL status, and UI all report terminal success.
8. The following forward cycle uses VMware CBT incrementally.

## 7. AS-IS / TO-BE

| Component | AS-IS | TO-BE |
|---|---|---|
| UI | Stale runtime `SYNCING` can override successful Run | Successful Failback terminal state wins |
| API | Correct fields exist but baseline is absent | Existing fields prove cutover baseline and reverse delta |
| Backend | Stores raw transitional runtime on completion | Stores normalized terminal snapshot and retained evidence |
| Agent | Relays transitional status | Relays FTCTL authoritative terminal markers |
| FTCTL | Baseline begins at first full Failback | Baseline begins at Failover cutover |
| DB | Run terminal and runtime JSON can disagree | Transactionally consistent terminal records |
