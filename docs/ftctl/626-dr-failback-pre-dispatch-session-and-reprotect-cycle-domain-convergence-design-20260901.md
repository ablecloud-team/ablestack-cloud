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

# DR Failback pre-dispatch session and reprotect cycle-domain convergence

## Problem

An FTCTL failback Run can fail while Cloud is preparing remote transport, before an Agent or FTCTL worker accepts the action. The Run becomes `FAILED`, but its eagerly created `dr_failback_session` remains `REQUESTED / SUBMITTED`. A later failback is then rejected as `DR_FAILBACK_CLEANUP_PENDING`, even though no engine artifact exists to clean up.

KVM-to-KVM reprotect also used `plan_cycle_sequence` as both the Cloud `dr_sync_cycle.sequence` and the FTCTL engine checkpoint sequence. These are independent monotonic domains. A durable row can therefore have Cloud sequence `1163` and cycle token `<plan>:546`; looking up Cloud sequence `546` incorrectly rejects the checkpoint.

## Contract

### Failback session terminalization

- A session is artifact-free only when its Run is terminal failed or canceled and the session is still `REQUESTED / SUBMITTED` with no engine acknowledgement, checkpoint, data-ready, power-transition, commit, rollback, authority, or live worker evidence.
- `DrRunExecutor` terminalizes that session as `FAILED` or `ABORTED`, records `PRE_DISPATCH`, copies the Run error, sets `completed_at`, and removes it from the active set.
- The periodic failback lifecycle reconciler applies the same terminalization so existing artifact-free rows converge after deployment without a DB repair or a new operator action.
- Creating a later failback performs the same narrow reconciliation for legacy rows before applying the cleanup gate.
- Any engine-accepted or transition-started session remains active and continues to require the normal compensation and cleanup lifecycle.

### KVM-to-KVM durable checkpoint identity

- `dr_sync_cycle.sequence` is the Cloud projection sequence.
- `<planUuid>:<engineCheckpointSequence>` is the cross-controller engine cycle token.
- Reprotect first accepts an explicitly recorded Cloud sequence when it resolves to the matching durable token. If sequence domains differ, it resolves the durable Cycle by exact plan and cycle token.
- `READY`, `LOCAL_DURABLE`, and non-null `target_durable_at` remain mandatory. VMware-to-KVM behavior is unchanged.

## Regression gates

- Pre-dispatch failback failure leaves no active failback session.
- An engine-accepted failback failure is not auto-removed.
- A legacy artifact-free failed session is reconciled before a new failback.
- A genuine active failback still returns `DR_FAILBACK_CLEANUP_PENDING`.
- KVM-to-KVM reprotect accepts independent Cloud and engine sequence domains only when the exact engine token is durable.
