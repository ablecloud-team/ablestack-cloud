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

# HA-RKY FTCTL Host-Owned Progress Event Design

Date: 2026-05-09
Scope: HA-RKY full-chain retest follow-up for `ha-r9-01`, RBD -> RBD shared-blockcopy.

Finalized note, 2026-05-10: this host-owned progress direction remains valid, but the final implementation rule is stricter: Cloud must not write or read live progress through VM details, and the UI `Update` action must not overwrite event-derived progress with `getFtctlProtection` response fields. See `192-HA-RKY-progress-single-source-final-design-20260510.md`.

## Test Result Summary

During block copy, the Fault Protection tab still appeared blocked and the Block Copy Progress panel did not reflect the current host-side progress.

Host-side progress was available directly from libvirt:

```bash
virsh blockjob --domain 42 --path sdb --info
```

The VM detail cache in Cloud could remain stale while FTCTL host events and state files already had newer progress. This made the UI look stuck even though host-side block copy continued.

Correction: this document describes the progress visibility and UI blocking defect, but it is not the complete failure root cause for the later HA-RKY full-chain failure. The direct destructive failure was hangctl misclassifying FTCTL/libvirt contention as `qmp_no_response` and killing the primary VM while blockcopy was active. See `191-HA-RKY-hangctl-ftctl-guard-destroy-fix-design-20260509.md` for the corrected root-cause chain and required qemu-side safety guard.

## Architectural Boundary

FTCTL exists so replication work can run independently on the host side.

Cloud-managed means Cloud creates, deletes, starts, and stops VM resources, volumes, and NICs. It does not mean Cloud owns the block copy control loop.

The host-side FTCTL process owns:

- block copy start, monitor, classify, and finalize
- QMP/libvirt block job inspection
- progress calculation
- state file updates
- event logging
- reconcile/timer based recovery

Cloud owns:

- Cloud-managed standby/primary resource lifecycle
- sending requested FTCTL actions to the host
- presenting FTCTL state and events in the UI
- deciding which Cloud-managed lifecycle action is allowed from FTCTL state

Cloud must not synchronously poll QMP/libvirt block jobs through `getFtctlProtection` or UI auto-refresh.

## Root Cause

The UI progress auto-refresh called `getFtctlProtection` with `refreshruntime=true`.

That invoked this synchronous path:

```text
UI fetchSyncProgress
  -> Cloud getFtctlProtection(refreshruntime=true)
  -> agentManager.send(FtctlStatusCommand)
  -> KVM agent ablestack_vm_ftctl status --json
  -> ftctl status side-effect refresh
  -> virsh qemu-monitor-command query-block-jobs
```

This made a read-only UI refresh enter the libvirt/QMP control path. During long block copy, that can contend with FTCTL reconcile/action processing and make the UI wait behind host control-plane work.

The previous design also stored progress into VM details and read it back as the UI source of truth. That cache can be stale and should not be the primary progress feed.

## Fix Design

### qemu-exec-tools

1. Make `ablestack_vm_ftctl status --json` a pure cached-state read.
   - It reads FTCTL state and progress files.
   - It must not call QMP.
   - It must not refresh block jobs.

2. Keep QMP/libvirt block job inspection inside host-owned FTCTL reconcile/action paths only.
   - `protect`, `reconcile`, `failback-sync`, and failback reconcile can refresh progress.
   - UI and Cloud status reads cannot trigger progress refresh.

3. Treat progress events as the operational telemetry feed.
   - `blockcopy.progress` and `reverse_sync.progress` events must include percent, copied bytes, total bytes, ready flag, direction, stage, disk count, and update timestamp.
   - Event bucketing should be fine enough for operations visibility.

### Cloud Backend

1. `getFtctlProtection` remains cached by default.
2. `refreshruntime=true` remains an explicit, manual runtime refresh escape hatch only.
3. Cloud action completion should not wait for block copy completion.
4. Cloud failback monitoring may read `ftctl status`, but that status must be cached host state produced by FTCTL reconcile, not a QMP refresh.

### Cloud UI

1. Progress auto-refresh must call `getFtctlEvents`, not `getFtctlProtection(refreshruntime=true)`.
2. The UI parses the latest `blockcopy.progress` or `reverse_sync.progress` event and updates only the local progress panel data.
3. The tab must not show a full loading overlay during background progress refresh.
4. A full runtime refresh is allowed only from an explicit user action.

## Expected Result

- Block copy continues independently on the host.
- Cloud UI progress refresh reads event telemetry only.
- Progress polling no longer adds QMP/libvirt monitor traffic.
- VM detail progress cache no longer determines what the progress bar shows.
- The Fault Protection tab remains usable while forward or reverse block copy is running.

## Validation Plan

1. Run qemu shell syntax checks and FTCTL selftests that cover progress/status/events.
2. Build qemu package with GitHub Actions.
3. Build the modified Cloud Maven module from the WSL ext4 clone.
4. Build the UI from the WSL ext4 clone if UI files changed.
5. Deploy the resulting artifacts to the test hosts.
6. Retest HA-RKY 01-15 from a clean state and verify that progress updates from events while the UI remains responsive.
