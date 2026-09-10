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

# KVM Cutover Checkpoint Seal Ordering

## Problem

A planned remote KVM failover can finish its operation-owned final checkpoint
while the protection runtime still exposes the previous scheduler checkpoint as
`latest_completed_checkpoint_sequence`.

Cloud previously populated the cutover session from the generic latest
checkpoint before reading `failover_restore_point_sequence`. It could therefore
drain the target export for sequence N, power on the target, then rediscover the
operation-owned sequence N+1 and try to drain the export again. The second drain
correctly finds the running target QEMU writer and leaves the Run in
`COMMIT_VERIFYING`, even though the final checkpoint and guest boot are valid.

## Contract

1. A remote `KVM_TO_KVM` Failover owns exactly one final checkpoint sequence.
   `failover_restore_point_sequence` is authoritative for that operation.
2. Generic scheduler fields such as `latest_completed_checkpoint_sequence` are
   fallback evidence only. They must not replace an operation-owned sequence.
3. Target export drain is a barrier before target power-on. Cloud must call it
   with the cutover session's authoritative sequence.
4. `POWER_ON_VALIDATED` proves that the drain barrier and target power-on have
   already completed in order. Projection retries from this state must retry
   only the cutover commit and must not drain the now-running target disk.
5. `PROMOTED` is terminal and also suppresses transport drain retries.
6. The rule is scoped to Plan-owned remote KVM transport. VMware-to-KVM and
   non-remote KVM paths retain their existing checkpoint contracts.

## Recovery

An existing Run stopped at `POWER_ON_VALIDATED / RETRY_REQUIRED` is recovered by
normal runtime projection. Cloud reuses the stored operation checkpoint,
skips the completed transport barrier, retries the idempotent cutover commit,
and converges the Run, session, Plan, and UI without direct DB repair or target
VM power cycling.

## Regression Gates

- When the latest scheduler checkpoint is 109 and the Failover checkpoint is
  110, target export drain receives 110.
- The target VM is not powered on before the drain for 110 succeeds.
- A commit retry from `POWER_ON_VALIDATED` does not call target export drain.
- The retry can reach `PROMOTED`, `FAILED_OVER`, and a terminal successful Run.
- Baseline action contracts for sync, test failover/cleanup, failover, and
  failback remain green before deployment.

## UI Acceptance

The existing Plan must converge from `COMMIT_VERIFYING` to `FAILED_OVER` through
normal refresh. The UI must show the target as the active side, the Failover Run
as successful, and Failback as available. The target VM console must remain at
the booted Windows screen throughout recovery.
