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

# HA-RKY FTCTL Progress Single Source Final Design

Date: 2026-05-10
Scope: HA-RKY full-chain retest follow-up for `ha-r9-01`, RBD -> RBD shared-blockcopy.

## Test Result Summary

The HA-RKY retest showed an impossible UI transition: block copy progress reached `100%` and then changed back to `4.4%` after pressing the Fault Protection tab `Update` button.

Host-side FTCTL state and libvirt block jobs showed the copy had reached `ready=true` / `100%`, but Cloud `getFtctlProtection` returned stale progress from VM details:

- `ftctl.sync.progress.percent=4.4`
- `ftctl.sync.copied.bytes=9457106944`
- `ftctl.sync.total.bytes=214748364800`
- `ftctl.sync.progress.json=...`

The UI then preferred `protection.syncprogresspercent` over event-derived progress and replaced the displayed `100%` with stale `4.4%`.

## Final Root Cause

Progress was recorded through multiple conflicting sources:

1. Host FTCTL state/progress/event files.
2. Cloud Event rows generated from sampled status.
3. VM detail cache keys under `ftctl.sync.*`.
4. UI event parsing that wrote progress back into the `protection` object.

VM details are not a valid runtime telemetry store. They are durable VM metadata and can remain stale across refreshes, retries, failover, failback, and cleanup gaps.

The `Update` button calls `fetchAll()`, then `fetchProtection()`, then `getFtctlProtection`. The response replaced the whole `protection` object. Because the UI progress computed properties read `protection.syncprogress*` first, pressing `Update` could overwrite newer host-event progress with older VM-detail progress.

## Final Architecture

FTCTL block copy is host-owned.

Cloud-managed means Cloud owns VM, volume, NIC, start, stop, destroy, and resource lifecycle. It does not mean Cloud owns the blockcopy monitoring loop.

The single source of truth for progress is:

- qemu host-side FTCTL progress state
- qemu host-side FTCTL events: `blockcopy.progress`, `reverse_sync.progress`

Cloud may display, relay, or persist high-level state transitions, but it must not mirror live progress into VM details.

## Required Backend Changes

1. Stop writing progress to VM details.
   - Remove writes for `ftctl.sync.progress.percent`.
   - Remove writes for `ftctl.sync.copied.bytes`.
   - Remove writes for `ftctl.sync.total.bytes`.
   - Remove writes for `ftctl.sync.ready`.
   - Remove writes for `ftctl.sync.direction`.
   - Remove writes for `ftctl.sync.updated`.
   - Remove writes for `ftctl.sync.progress.json`.
   - Remove writes for `ftctl.sync.progress.event.bucket`.

2. Stop reading progress from VM details.
   - `getFtctlProtection` must not populate response progress from VM details.
   - `refreshruntime=true` may update high-level runtime state, but it must not put progress into the protection response.

3. Clean legacy stale detail keys opportunistically.
   - When building FTCTL protection response, remove legacy `ftctl.sync.*` progress keys and old failover-ready progress details from the primary VM.
   - Protection release still removes all `ftctl.*` details as before.

4. Keep runtime state separate from progress.
   - `ftctl_protection.protection_state`, `transport_state`, `active_side`, `admin_state`, and `fencing_state` may be updated from host status.
   - This state update must not persist progress telemetry to VM details.

## Required UI Changes

1. Split progress state from protection state.
   - `this.protection` is for protection configuration and high-level status.
   - `this.syncProgressState` is for blockcopy progress.

2. `Update` must not overwrite progress.
   - `fetchProtection()` may replace `this.protection`.
   - It must not set or reset `syncProgressState`.
   - Progress refresh happens only through `getFtctlEvents` and host-owned progress events.

3. Event-derived progress must be monotonic per direction.
   - Ignore older timestamps.
   - For the same direction, ignore a lower percent after `100%` unless a newer operation direction is observed.
   - Reset progress only when protection is not configured or a new direction/operation is detected.

4. Remove `protection.syncprogress*` reads and writes.
   - Computed progress fields must read only `syncProgressState`.
   - `applyActionPayload()` must not copy progress fields into `protection`.
   - `applyProgressFromEvents()` must write only to `syncProgressState`.

## Conflicting Prior Design Cleanup

Document `189-HA-RKY-reverse-sync-reconcile-ui-nonblocking-fix-design-20260509.md` said `status --json` should refresh reverse jobs. That is superseded.

The final rule is:

- UI/Cloud status reads are cached/non-blocking.
- qemu FTCTL action/reconcile paths own libvirt/QMP blockjob refresh.
- UI reads progress from host FTCTL events/state exposed through Cloud, not from VM details.

## Verification Criteria

1. During forward blockcopy, pressing `Update` repeatedly must not change progress from `100%` back to stale lower values.
2. During reverse blockcopy, pressing `Update` repeatedly must not reset progress.
3. `vm_instance_details` must not contain `ftctl.sync.%` keys after opening or refreshing the FTCTL tab.
4. `getFtctlProtection` response must not include progress fields populated from VM details.
5. The progress panel must continue to update from `blockcopy.progress` and `reverse_sync.progress` events.
6. HA-RKY 01 through 15 must pass from a clean state without UI progress regression.
