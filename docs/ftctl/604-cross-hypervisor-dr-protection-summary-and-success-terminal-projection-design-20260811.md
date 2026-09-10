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

# Cross-Hypervisor DR Protection Summary And Success Terminal Projection Design

Date: 2026-08-11

## 1. Purpose

This design closes two read-model consistency gaps after a successful sync or
Failback:

1. the Protection tab must show transfer metrics from the exact completed
   `dr_sync_cycle` named by `dr_plan_runtime.latest_completed_cycle_sequence`;
2. a successful Failback must not retain or expose failure-only metadata.

The FTCTL engine remains the authority for cycle evidence. Cloud owns durable
projection and terminal transaction consistency. The UI renders the active
cycle while work is running and the latest completed cycle while runtime is
idle.

## 2. Verified Failure Cause

For plan `ef73f5f3-9740-4bbd-8c9a-74a972e5f19f`, FTCTL and
`dr_sync_cycle(sequence=299)` reported an incremental payload of 18,939,904
bytes. `dr_plan_runtime.latest_completed_cycle_sequence` also reported 299,
but its transient transfer fields still referred to sequence 246 and 6,684,672
bytes. The cached protection view copied those transient fields without a
sequence join, so History and Protection displayed different values.

The completed Failback session also retained `failed_component=ftctl` even
though its state was `COMPLETED` and its error code/message were empty. The
success transaction cleared only error code and message, while the UI always
rendered failure phase and component rows.

## 3. Projection Contract

### 3.1 Active cycle

When an authoritative active cycle exists, live transfer progress continues to
come from `dr_plan_runtime` and the matching active `dr_sync_cycle`. No
completed-cycle values may overwrite live progress.

### 3.2 Idle runtime

When no authoritative active cycle exists and runtime replication activity is
`IDLE`, Cloud may project completed transfer metrics only if:

```text
dr_plan_runtime.latest_completed_cycle_sequence
  == dr_sync_cycle.sequence
```

The projected summary uses the matched cycle's requested/effective mode,
changed bytes, source-read bytes, target-written bytes, payload bytes, commit
state, and incremental verification evidence. A sequence mismatch is treated
as unavailable completed evidence, not as permission to display an older
transient sample.

### 3.3 Successful terminal metadata

Inside the same successful Failback transaction, Cloud clears:

- `failure_phase`
- `failed_component`
- `error_code`
- `error_message`

The terminal runtime JSON clears the snake-case equivalents as well. The
protection-view API removes these fields from a `COMPLETED` session with no
error, which also protects existing rows until bounded DB cleanup is applied.
The UI independently hides failure-only rows for successful sessions.

## 4. Code-Level Design

### 4.1 Cloud Backend/API

`DrProtectionViewServiceImpl`:

- increment the protection snapshot version to invalidate stale cached views;
- pass both authoritative active cycle and latest completed cycle into
  `protectionRuntimeJson`;
- overlay completed transfer fields only for an idle runtime whose latest
  completed sequence matches the cycle row;
- serialize a successful Failback session through a sanitizer that omits
  failure-only properties.

`DrFailbackLifecycleServiceImpl`:

- clear all four failure columns in `completeLifecycle` before persisting the
  completed session;
- remove failure keys from `terminalRuntimeSnapshot` so Run/session details do
  not reintroduce stale failure evidence.

No schema migration is required because all columns and cycle metrics already
exist.

### 4.2 UI

`DrProtectionInfoTab.vue` derives one `displaySyncCycle`:

- active cycle while an active cycle ID exists;
- otherwise latest completed cycle.

Replication mode, changed bytes, payload bytes, and commit state use that
cycle. Worker liveness is shown only for active work; idle state is not mapped
to `UNKNOWN` or `NOT_READY`. Failure phase/component rows are rendered only
when a non-success session contains failure evidence.

### 4.3 FTCTL And Agent

No FTCTL or Agent implementation change is required. Deployment verification
must prove that the installed engine still reports the matching latest cycle,
payload bytes, incremental mode, and durable commit evidence consumed by the
Cloud projection.

## 5. Tests

Backend unit tests cover:

- stale transient transfer sequence plus a matching latest completed cycle;
- exact completed payload/mode/commit projection;
- successful session failure-field sanitization;
- successful terminal runtime failure-key removal.

UI unit tests cover:

- active-cycle precedence;
- idle latest-completed-cycle display;
- durable commit projection without false `NOT_READY`;
- successful session failure-row suppression.

Runtime verification compares the same sequence and byte count across FTCTL,
`dr_sync_cycle`, protection-view API, and the Protection/History tabs.

## 6. Deployment And Retest Gate

1. Build only the changed Cloud DR Maven module from the WSL ext4 clone.
2. Build the UI from the WSL ext4 clone.
3. Deploy only changed Cloud classes and static UI assets, preserving
   `WEB-INF` and `META-INF`.
4. Clear stale failure columns only for the verified completed Failback session
   and rebuild its protection-view cache.
5. Verify management, `/client/`, Agent connectivity, and FTCTL scheduler/timer.
6. Confirm sequence and byte equality in DB/API/UI before handing the next
   Failover/Failback retest to the operator.

## 7. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
|---|---|---|
| Completed transfer | Runtime transient sample can refer to an older cycle | Exact join to `latest_completed_cycle_sequence` |
| IDLE display | Current-cycle fields become `UNKNOWN` or `NOT_READY` | Latest durable cycle is shown as completed evidence |
| Failback success DB | Failure component may remain after success | All failure columns cleared atomically |
| Success API | Completed session can expose stale failure fields | Failure-only fields omitted |
| Success UI | Failure rows always rendered | Failure rows shown only for actual failure evidence |
| Engine role | Correct evidence exists but is inconsistently consumed | FTCTL evidence is consumed without engine changes |

## 8. Success Terminal Storage Convergence Follow-up

The API and UI sanitization in this document does not replace durable database
convergence. The live Failover/Failback retest found that a completed session
could still retain `failed_component=ftctl` even though FTCTL, `details_json`,
the Run, and the protection view all reported success. The atomic DAO update,
terminal guard, read-after-write assertion, and bounded legacy cleanup are
defined in
`605-cross-hypervisor-dr-failback-success-metadata-storage-convergence-design-20260811.md`.

## 9. Plan List And Reverse Evidence Follow-up

The Protection tab and plan list must consume the same completed cycle, and a
resumed forward scheduler must not replace the completed Failback Run as the
owner of reverse evidence. The sequence join, FTCTL Run selection precedence,
tests, and deployment gate are defined in
`606-cross-hypervisor-dr-completed-cycle-list-and-reverse-evidence-convergence-design-20260812.md`.
