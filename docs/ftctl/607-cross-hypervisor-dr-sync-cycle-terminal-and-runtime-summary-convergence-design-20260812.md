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

# Cross-Hypervisor DR Sync Cycle Terminal And Runtime Summary Convergence Design

## 1. Scope

This change completes the lifecycle of older sync-cycle rows after a newer
durable checkpoint has been committed. It also makes the raw
`dr_plan_runtime` transfer summary identify the same completed cycle used by
the list and Protection APIs.

The VDDK, CBT, RBD, snapshot, transfer, Failover, and Failback data paths are
unchanged.

## 2. Problem

After a successful Failback and the following durability cycle, the latest
cycle is complete and usable, but older rows can remain non-terminal:

- a delayed or restarted forward cycle can remain `SYNCING`;
- the reverse Failback checkpoint can remain `FAILBACK_DATA_READY` after it has
  already been consumed by the next forward durable cycle;
- `dr_plan_runtime` can retain transfer bytes and mode from an old live sample.

These rows are ignored by the current read model, but they make operational
history and direct DB inspection ambiguous.

## 3. Terminal Contract

When FTCTL publishes a coherent latest completed cycle `N`, Cloud performs the
following ordered convergence:

1. project cycle `N` into `dr_sync_cycle` and require `completed` to be set;
2. set the raw runtime transfer summary to cycle `N` when replication is
   `IDLE`;
3. query incomplete rows with `sequence < N` in bounded batches;
4. change reverse data-ready rows to `CONSUMED` with commit state
   `CONSUMED_BY_DURABLE_CYCLE`;
5. change other incomplete rows to `SUPERSEDED` with commit state
   `SUPERSEDED_BY_DURABLE_CYCLE`;
6. clear stale cycle error fields and assign the durable completion time.

The query contains `completed IS NULL`, so retries and management-server
restarts are idempotent. A batch limit prevents a status refresh from scanning
an unbounded history.

## 4. Runtime Summary Contract

When `replication_activity=IDLE`, the raw runtime row is overwritten from the
same coherent FTCTL completed-cycle snapshot:

- cycle and sample sequence: latest completed sequence;
- phase: `COMPLETED`;
- mode and bytes: the latest completed cycle metrics;
- percent and ETA: `100` and `0`;
- sampled time: target durable time;
- stale: `false`.

Live operation samples remain authoritative while replication is active. This
keeps the existing progress path unchanged.

## 5. Layer Design

| Layer | Design |
|---|---|
| UI | No component change; existing fields receive one coherent cycle |
| API | Existing response fields remain backward compatible |
| Backend | Runtime projection persists the latest completed summary and then terminalizes older cycles |
| Agent | No protocol change; the existing completed-cycle snapshot is sufficient |
| FTCTL | No data-path change; FTCTL remains the producer of durable-cycle evidence |
| DB | No schema change; existing `state`, `commit_state`, `completed`, and error columns are used |

## 6. Verification

Unit tests cover:

- stale raw `FULL_RESEED` summary converging to completed
  `CBT_INCREMENTAL` cycle 528;
- cycle 523 becoming `SUPERSEDED`;
- reverse cycle 527 becoming `CONSUMED`;
- repeated projection after restart producing no additional terminal writes.

Runtime PASS requires:

- latest runtime, list API, Protection API, and `dr_sync_cycle` use one cycle;
- no lower sequence remains incomplete;
- Failover and Failback runs and sessions remain terminal-success;
- FTCTL reverse evidence and scheduler state remain healthy;
- a new incremental cycle can start after cleanup.

## 7. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
|---|---|---|
| Reverse checkpoint | `FAILBACK_DATA_READY` can remain indefinitely | Next durable cycle marks it `CONSUMED` |
| Interrupted older cycle | Lower sequence can remain `SYNCING` | Higher durable sequence marks it `SUPERSEDED` |
| Raw runtime transfer | Old live `FULL_RESEED` sample can remain | Latest completed cycle is stored while idle |
| API consistency | API correction can hide raw DB drift | API and raw runtime share the same cycle |
| Restart behavior | Reprojection can repeat ambiguous cleanup | `completed IS NULL` makes cleanup idempotent |
| Schema | A new cleanup table might appear necessary | Existing cycle and runtime columns are reused |

## 8. Implementation And Deployment Verification

The 2026-08-12 test deployment completed with the following evidence:

- Cloud disaster-recovery module tests: 138 passed, 0 failed;
- changed Cloud module package: build success from the WSL ext4 clone;
- changed-class-only deployment: runtime adapter and sync-cycle DAO classes;
- active web application: `WEB-INF` preserved and `/client/` returned HTTP 200;
- FTCTL GitHub Actions Run 31574728760: success for commit `1675db811b`;
- FTCTL RPM SHA-256:
  `10b09e8dca8d50758e33a2e9c2d3e602809c491193d7d38d9e3b0cdc42216626`;
- all three hosts: FTCTL 0.9.5 installed and timer active;
- coordinator scheduler: `RUNNING/HEALTHY` after the package restart;
- plan 43 legacy cycle 523: `SUPERSEDED`;
- plan 43 reverse cycle 527: `CONSUMED`;
- latest cycle 536: `READY/CBT_INCREMENTAL`, 2,031,616 transferred bytes;
- raw runtime and API both identify cycle 536 with no projection error;
- no active Run exists and the latest Failback session remains terminal-success.
