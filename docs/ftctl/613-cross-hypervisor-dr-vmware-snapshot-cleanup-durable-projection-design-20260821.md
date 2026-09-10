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

# Cross Hypervisor DR VMware Snapshot Cleanup And Durable Projection Design

## 1. Scope

This design keeps Cloud's completed-cycle projection consistent when FTCTL has
durably committed VMware data but still has temporary source snapshot cleanup
work. It does not change the validated VMware to ABLESTACK RBD data path.

## 2. State Separation

| Fact | Authority | Projection rule |
|---|---|---|
| Current scheduler activity | FTCTL current status | may be `WAITING_CLEANUP` or degraded |
| Last durable replication | FTCTL latest completed cycle | remains completed and usable |
| Snapshot maintenance | FTCTL source snapshot lifecycle | warning/retry state, not data rollback |

Cloud must choose `latestCompletedCycleSequence` before the legacy
`latestCompletedCheckpointSequence` when locating and committing the canonical
`dr_sync_cycle`. Current errors must not erase or regress that completed row.

## 3. Projection Flow

```text
Mold Agent -> FtctlDrStatusAnswer
  current cycle: WAITING_CLEANUP
  latest completed cycle: LOCAL_DURABLE N
        |
        v
FtctlDrRuntimeProjectionAdapter
  project current maintenance state
  project completed cycle N independently
        |
        v
dr_plan_runtime.latest_completed_cycle_sequence = N
dr_sync_cycle(plan, N) = READY / LOCAL_DURABLE
```

## 4. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
|---|---|---|
| Sequence selection | several paths require checkpoint alias | explicit latest-cycle sequence preferred |
| Current failure | can prevent completed-cycle projection | current and completed projections are independent |
| History | durable reseed may be absent until next success | durable cycle is projected immediately |

## 5. Validation

- Unit test a status containing only `latestCompletedCycleSequence` and a
  coherent `LOCAL_DURABLE` snapshot.
- Verify `dr_sync_cycle` completion and runtime latest sequence are updated even
  if the current scheduler is degraded.
- After deployment, verify all three 32-cluster plans complete an automatic
  incremental or no-change cycle and expose it through list and detail APIs.

## 6. vCenter Certificate Rollover

Cloud distinguishes automatically discovered vCenter thumbprints from
operator-pinned values. Credentials marked `backend-auto`,
`backend-auto-refreshed`, or `backend-auto-fallback` are refreshed from the
registered vCenter endpoint whenever a DR command profile is rendered. A
`runtime` thumbprint remains pinned.

This keeps newly dispatched profiles consistent after a vCenter certificate
rollover. FTCTL independently refreshes the same automatic value at source-open
time so a persistent scheduler can recover without waiting for another UI/API
action. Snapshot cleanup state and the latest durable Cycle remain independent
from this transient source-authentication recovery.
