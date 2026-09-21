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

# HA-RKY Reverse Sync Reconcile and UI Nonblocking Fix Design

Date: 2026-05-09
Scope: HA-RKY full-chain retest follow-up for `ha-r9-01`, RBD -> RBD shared-blockcopy.

Superseded note, 2026-05-10: the `status --json` refresh design in this document is no longer the final architecture. UI/Cloud status reads must be cached and non-blocking; qemu FTCTL action/reconcile paths own libvirt/QMP blockjob refresh. Progress must not be stored in VM details. See `192-HA-RKY-progress-single-source-final-design-20260510.md` for the final progress single-source design.

## Test Result Summary

The HA-RKY full-chain retest progressed through failover and reached failback, but failback did not complete.

- UI failback action returned and reverse blockcopy was started.
- Host-side QMP on the standby domain later showed reverse blockjobs with `ready=true`.
- Cloud API and DB stayed at `failed_over / failed_over / secondary / manual-fenced`.
- `ftctl.sync.progress` remained stale at `1.6%`.
- The Cloud failback monitor did not advance to cutback because it never observed `reverse_sync_ready`.
- The Fault Protection tab could still appear blocked while blockcopy/protection work was in progress.

## Root Cause

### Reverse Sync State Regression

`failback-sync` correctly sets qemu state to `failing_back / reverse_syncing`.
After that, the periodic qemu `reconcile` path evaluates the standby VM as running with explicit manual fencing and incorrectly classifies the VM as a stable failover state.

That path overwrites the state back to:

- `protection_state=failed_over`
- `transport_state=failed_over`
- `active_side=secondary`

Once the transport state is back to `failed_over`, both qemu `status` and `reconcile` stop refreshing reverse blockjobs. Cloud therefore sees stale progress and cannot proceed.

### UI Blocking

The previous UI change reduced some visible blocking, but `getFtctlProtection` still refreshed runtime state from the host agent by default. During long qemu actions, host agent commands can be delayed behind the active operation, so simply opening or refreshing the Fault Protection tab can wait on live host status.

The UI also awaited `fetchRuntimeData()` as part of full tab loading, which includes check, health, and event calls.

## Fix Design

### qemu-exec-tools

1. Add reverse-sync artifact detection.
   - Detect `.blockcopy.reverse` records.
   - Treat reverse `sync_progress` as evidence that failback is in progress.

2. Recover stale reverse sync state.
   - If transport is `failed_over`, `unknown`, or empty but reverse artifacts exist, promote the local state back to `failing_back / reverse_syncing`.

3. Refresh reverse blockjobs from `status`.
   - `status --json` must call reverse job refresh when reverse sync is active or recoverable.
   - This lets Cloud failback monitor observe `reverse_sync_ready` without waiting for a separate reconcile cycle.

4. Protect failback transitions in `reconcile`.
   - If qemu state is `failing_back` or transport is a reverse/failback transition, do not run failover steady-state classification.
   - Refresh reverse jobs first and return.
   - On reverse refresh failure, mark `reverse_sync_failed` instead of falling through to failover steady-state.

### Cloud Backend

1. Make `getFtctlProtection` cached by default.
   - It returns DB/detail state without host agent runtime refresh unless explicitly requested.

2. Add optional API parameter:
   - `refreshruntime=true`
   - Only callers that explicitly need live host state should use this.

This prevents the UI tab from hanging behind long-running host agent operations.

### Cloud UI

1. Keep Fault Protection tab loading nonblocking after initial cached data load.
2. Fetch cached protection state first.
3. Run check/health/events refresh in the background instead of awaiting it in `fetchAll()`.
4. Remove runtime data refresh from progress auto-refresh.
5. After action/job completion, refresh silently instead of re-entering a blocking full-tab load.

## Expected Result

- Reverse blockcopy completion is reflected as `reverse_sync_ready` or `reverse_sync_cutback_required`.
- Cloud failback monitor proceeds with secondary stop, finalize, primary restore, and reprotect.
- Opening or refreshing the Fault Protection tab no longer waits on host live status by default.
- During blockcopy, the UI keeps rendering existing data and updates cached progress without full-tab visual blocking.

## Validation Plan

1. qemu static syntax check for changed shell files.
2. Cloud module compile from WSL ext4 clone when build is requested.
3. UI build/deploy verification when build is requested.
4. Retest HA-RKY 01-15 from clean state.
5. During retest verify:
   - no regression from `reverse_syncing` to `failed_over`
   - `getFtctlProtection` returns quickly during blockcopy
   - UI tab remains usable while blockcopy runs
   - failback completes through Cloud-managed lifecycle
