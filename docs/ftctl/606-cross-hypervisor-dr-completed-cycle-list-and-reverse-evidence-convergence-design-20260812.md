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

# Cross-Hypervisor DR Completed Cycle List And Reverse Evidence Convergence Design

## 1. Scope

This change closes two read-model inconsistencies after a successful Failback:

- `listDrPlans` must summarize the durable sync cycle identified by
  `dr_plan_runtime.latest_completed_cycle_sequence` while replication is idle.
- FTCTL `dr-status` must resolve reverse evidence from the completed Failback
  Run, even after the resumed forward scheduler publishes a newer plan status.

It does not change the VDDK, RBD, CBT, snapshot, or transfer data paths.

The subsequent raw-runtime and old-cycle terminal contract is defined in
`607-cross-hypervisor-dr-sync-cycle-terminal-and-runtime-summary-convergence-design-20260812.md`.

## 2. Cloud Contract

`DrResponseGenerator` keeps live runtime transfer fields while an operation is
active. When `replication_activity=IDLE`, it queries
`DrSyncCycleDao.findLatestCompletedByPlanId(planId)` and uses that row only if
its sequence equals `DrPlanRuntimeVO.latestCompletedCycleSequence`.

The completed row projects the following list fields:

- `transferActivityState=IDLE`
- `transferCycleSequence=dr_sync_cycle.sequence`
- `transferMode=dr_sync_cycle.effective_mode`
- total bytes from `virtual_bytes`
- processed and payload bytes from `transfer_payload_bytes`
- source read, target written, verified bytes, throughput, 100 percent, and
  zero ETA from the same row

A missing row or sequence mismatch is fail-closed: the response retains the
runtime projection and never combines fields from different cycles. The
Protection tab already follows the same sequence-equality rule.

## 3. FTCTL Contract

Reverse evidence is owned by the Failback Run that created the durable reverse
checkpoint. FTCTL stores `reverse_evidence_run_uuid` in the Run and plan status,
and also overlays it from `failbacks/active.json` after normal forward status
publication.

`dr-status` resolves evidence in this order:

1. explicit `reverse_evidence_run_uuid`;
2. the Run UUID parsed from `failback_session_id=<plan>:<run>`;
3. current status `run`;
4. command Run, external job reference, and control request Run.

The first candidate with an existing Run state file is used. This keeps the
completed Failback checkpoint, tracker, writer, write-verification, and guest
compatibility evidence authoritative while normal forward replication resumes.

## 4. Layer Impact

| Layer | Change |
|---|---|
| UI | No component change; the existing list receives coherent API fields |
| API | `listDrPlans` exposes one durable completed cycle while idle |
| Backend | Response generator joins runtime sequence to `dr_sync_cycle` |
| Agent | No protocol change |
| FTCTL | Completed Failback Run UUID is retained and preferred for evidence |
| DB | No schema change; existing runtime and cycle columns are used |

## 5. Verification

Cloud unit tests create an idle READY runtime with a stale FULL_RESEED sample
and a matching completed CBT_INCREMENTAL cycle. The response must expose only
the matching completed cycle metrics.

FTCTL selftests complete Failback, publish a forward scheduler Run over the plan
status, and require `reverse_evidence_state=COMPLETE` with the original Failback
Run UUID. Existing failback terminal, scheduler resume, and incremental tests
must remain green.

Runtime PASS requires all of the following:

- list and Protection APIs use the same latest completed cycle sequence;
- mode and byte metrics equal the `dr_sync_cycle` row for that sequence;
- `dr-status.reverse_evidence_state=COMPLETE`;
- `reverse_evidence_run_uuid` identifies the completed Failback Run;
- management, Agent, FTCTL timer, and plan scheduler services are healthy.

## 6. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
|---|---|---|
| Plan list transfer | Old live FULL_RESEED sample can survive IDLE | Matching latest completed cycle is projected |
| Cycle identity | Sequence, mode, and bytes may come from different ages | All summary fields come from one `dr_sync_cycle` row |
| Reverse evidence owner | Current forward Run is selected first | Completed Failback Run is selected first |
| Post-Failback status | Forward publish can produce `INCONSISTENT` evidence | Durable reverse evidence remains `COMPLETE` |
| Storage | Additional state or columns might appear necessary | Existing Run sidecar and DB columns are reused |
