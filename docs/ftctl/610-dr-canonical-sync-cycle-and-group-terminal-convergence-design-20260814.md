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

# DR Canonical Sync Cycle And Group Terminal Convergence Design

## 1. Objective

Prevent one FTCTL engine Cycle from appearing as multiple Cloud Cycle rows when
the scheduler Run and an operator or protection-group Run publish the same
sequence with different Run UUIDs. The VMware to ABLESTACK RBD success path,
transfer engine, NBD allocation, and RBD format remain unchanged.

## 2. Verified failure

The 32.x test DB contained pairs such as:

| Plan | Sequence | Producer A | Producer B | Result |
|---|---:|---|---|---|
| Rocky | 157 | scheduler / SUPERSEDED | group operation / READY | duplicate Cycle |
| Ubuntu | 26 | scheduler / SUPERSEDED | group operation / READY | duplicate Cycle |

The old key `(plan_id, engine_run_uuid, sequence)` allowed both rows. Cleanup
only selected incomplete rows with a lower sequence, so the same-sequence alias
remained active until a later Cycle completed.

## 3. Canonical identity

```text
CycleKey = (plan_id, sequence)
cycle_token = plan_uuid + ':' + sequence
engine_run_uuid = first producer metadata
run_id = first Cloud Run metadata
```

Run UUID is not a durability boundary. A scheduler, an immediate-sync action,
and a protection-group child Run can observe the same Cycle without creating
new rows.

## 4. Cloud implementation

### DAO

- `findByPlanSequence(planId, sequence)` returns the canonical row.
- `listIncompleteAtOrBeforeSequence()` includes same-sequence aliases for
  immediate convergence.
- producer-oriented lookup remains available for diagnostics only.

### Projection

1. Acquire the DR Plan lock.
2. Execute current and latest-completed projection in one DB transaction.
3. Upsert both through the canonical Plan/sequence lookup.
4. Preserve the first producer Run metadata.
5. Terminalize incomplete rows with sequence less than or equal to the durable
   sequence before releasing the Plan lock.

### Protection group

When every child Run succeeds, alias convergence and the group `SUCCEEDED`
terminal update execute in one transaction. The UI can therefore never receive
a successful group terminal paired with an active duplicate Cycle.

## 5. Database migration

The upgrade chooses one canonical row per Plan/sequence in this order:

1. active row;
2. completed row;
3. READY, COMPLETED, or TARGET_READY row;
4. newest row ID.

Other aliases are deleted because `dr_sync_cycle` is a projection table and
`dr_run` remains the immutable operation audit. The migration then replaces
the old unique Run-based index with:

```sql
UNIQUE KEY uk_dr_sync_cycle__plan_sequence (plan_id, sequence)
KEY i_dr_sync_cycle__plan_run_sequence (plan_id, engine_run_uuid, sequence)
```

## 6. Layer impact

| Layer | AS-IS | TO-BE |
|---|---|---|
| UI | Can briefly show a successful group while DB still has an active alias | No payload change; terminal group state implies converged Cycle rows |
| API | Reads whichever duplicate row wins ordering | Reads one canonical row |
| Backend | Run UUID participates in Cycle identity | Plan-wide sequence is identity; producer Run is metadata |
| Agent | Publishes scheduler and operation observations | Unchanged |
| FTCTL | Owns Plan-wide sequence and cycle token | Unchanged |
| DB | Unique Plan/Run/sequence allows aliases | Unique Plan/sequence prevents aliases |

## 7. Verification

1. Unit-test scheduler and operation Run UUID observations against one sequence.
2. Unit-test same-sequence alias terminalization.
3. Apply migration twice to prove idempotency.
4. Confirm no duplicate `(plan_id, sequence)` rows and the unique key exists.
5. Run a two-member protection-group full synchronization.
6. Confirm each member has one terminal Cycle for its completed sequence, no
   active alias, and a `SUCCEEDED` group row.

## 8. Acceptance criteria

- `COUNT(*) GROUP BY plan_id, sequence HAVING COUNT(*) > 1` returns zero.
- A completed Cycle terminalizes same-sequence incomplete aliases immediately.
- Group success and alias convergence are atomic.
- Existing single-plan and grouped VMware to ABLESTACK RBD synchronization
  continue to use the established mover, librbd destination, and NBD range.

## 9. Implementation and deployment verification

The implementation was built from the WSL ext4 checkout and deployed as
changed Cloud classes only. Agent and FTCTL packages were not changed because
the defect was limited to Cloud projection identity, group terminalization,
and the Cloud database uniqueness boundary.

Focused Maven verification completed as follows:

- `FtctlDrRuntimeProjectionAdapterTest`: 35 tests passed;
- `DrProtectionGroupServiceImplTest`: 5 tests passed;
- `DrAdmissionControllerImplTest`: 2 tests passed;
- `Upgrade42210to42300Test`: 4 tests passed.

The group service test directly verifies that a fully successful group
terminalizes an incomplete same-sequence alias and updates the group Run to
`SUCCEEDED` through the same transaction callback.

The migration and changed classes were deployed to both test management
servers on 2026-08-14. The 32 cluster was backed up under
`/root/dr-cycle-canonical-20260814-205350`, and the 22 cluster under
`/root/dr-cycle-canonical-20260814-205451`.

| Check | 32 cluster | 22 cluster |
|---|---:|---:|
| duplicate Plan/sequence pairs before migration | 122 | 0 |
| incomplete aliases with a completed peer before migration | 6 | 0 |
| duplicate Plan/sequence pairs after migration | 0 | 0 |
| incomplete aliases with a completed peer after migration | 0 | 0 |
| canonical unique index | present | present |
| producer diagnostic index | present | present |
| Mold service | active | active |
| `/client/` | HTTP 200 | HTTP 200 |
| class linkage errors after restart | 0 | 0 |

All deployed class-entry SHA256 values matched the WSL Maven output. The
active webapp retained `WEB-INF` and the required asynchronous DR UI markers.
On the 32 cluster, all three active plans remained `READY`, the latest
two-member group Run remained `SUCCEEDED` with `2/2`, and no active group Run
or resource lease remained. All six compute hosts retained `nbds_max=32`, the
reserved `/dev/nbd16` through `/dev/nbd31` range, and an active FTCTL timer.

## 10. Retest procedure

1. Select the Rocky and Ubuntu plans in the 32-cluster DR Plan list.
2. Run protection-group full synchronization.
3. Confirm both members reach a terminal success state in the same group Run.
4. Confirm one `dr_sync_cycle` row exists for each completed Plan/sequence.
5. Confirm no incomplete row exists with the same Plan/sequence as a completed
   row and no new duplicate pair can be inserted.
6. Confirm subsequent scheduler observations update the canonical Cycle rather
   than creating a second row with another `engine_run_uuid`.
