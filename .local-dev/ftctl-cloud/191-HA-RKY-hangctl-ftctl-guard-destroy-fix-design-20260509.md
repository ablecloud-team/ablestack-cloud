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

# HA-RKY Hangctl FTCTL Guard Destroy Fix Design

Date: 2026-05-09
Scope: HA-RKY full-chain retest follow-up for `ha-r9-01`, RBD -> RBD shared-blockcopy.

## Corrected Test Finding

The HA-RKY full-chain retest did not fail only because Cloud/UI showed stale progress.

The direct failure was that the primary VM was destroyed while FTCTL blockcopy was still running. The UI had already seen host progress near 70%, but Cloud detail progress still showed an older value near 4.5%. That mismatch hid the real problem: a host-side hang detector treated FTCTL/libvirt control-plane contention as a VM hang and executed the crash/kill path.

Observed timeline:

- 15:25:22: FTCTL host progress event recorded about 4.5%.
- 15:32:23: FTCTL host progress event recorded about 70.8%.
- 15:33:13: FTCTL host progress event recorded about 77.6%.
- 15:33:24: FTCTL unprotect cancelled two block jobs while the system was already inconsistent.
- 15:35:34: `ablestack-vm-hangctl` confirmed `qmp_no_response` and started an incident for `i-2-348-VM`.
- 15:36:08: hangctl attempted a crash dump and libvirt/qemu reported the domain destroyed.
- 15:36:12: hangctl sent TERM to the qemu process.
- 15:36:14: hangctl sent KILL to the qemu process.
- 15:40:08: Cloud stop was issued later during recovery, so Cloud stop was not the primary destroy cause.

## Root Cause

FTCTL and hangctl were both using libvirt/QMP against the same VM, but hangctl did not know that the VM was under FTCTL protection or that blockcopy activity can make QMP/libvirt temporarily slow.

The existing storage guard prevents selected storage-risk actions, but it is not enough for HA/FTCTL replication. A VM under FTCTL control must be treated as an orchestrated replication workload. While FTCTL state, profile, lock, or blockcopy progress files exist, hangctl must not crash-dump, destroy, TERM, or KILL the VM.

The previous progress-staleness design remains valid as a UI/control-plane performance fix, but it is not sufficient to prevent VM loss. The destructive action boundary must be enforced in qemu-exec-tools.

## Design Corrections

### qemu-exec-tools

1. Add an FTCTL-aware guard to hangctl.
   - Detect FTCTL-managed VMs from `/etc/ablestack/ftctl.d/<vm>.conf`.
   - Detect active FTCTL runtime from `/run/ablestack-vm-ftctl/state/<vm>.state`.
   - Detect active operation locks under `/run/ablestack-vm-ftctl/locks`.
   - Detect active/progress marker files such as `.blockcopy`, `.blockcopy.reverse`, and `.blockcopy.progress`.

2. If the guard matches, hangctl must skip destructive handling.
   - No crash dump.
   - No `virsh destroy`.
   - No process TERM/KILL.
   - Log `vm.action_guard` and `action.skip` events instead.

3. Change the default memory dump mode from crash dump to live dump.
   - Default `HANGCTL_DUMP_MODE=live`.
   - `crash` remains available only by explicit configuration.
   - `disabled` can be used if dumps must be fully suppressed.

4. Keep the guard enabled by default.
   - Default `HANGCTL_FTCTL_GUARD_ENABLE=1`.
   - The guard is a safety invariant, not a test-only behavior.

### Cloud and UI

The host-owned progress/event design in document 190 still applies:

- Cloud and UI must not synchronously query QMP/libvirt for progress on auto-refresh.
- The UI should read FTCTL progress from host-produced events/state.
- VM detail progress is a cache and cannot be the source of truth during active replication.

However, stale UI progress is now classified as a visibility defect, not the direct destroy cause.

## Conflicting Document Cleanup

Document `190-HA-RKY-ftctl-host-owned-progress-event-design-20260509.md` remains valid for the UI hang and stale-progress problem, but it is incomplete as a failure root-cause record. This document supersedes any interpretation that stale Cloud detail progress alone caused the HA-RKY failure.

The corrected root-cause chain is:

```text
FTCTL blockcopy active
  -> libvirt/QMP may be slow or contended
  -> hangctl sees qmp_no_response
  -> no FTCTL guard exists
  -> hangctl crash/kill path destroys primary VM
  -> HA-RKY full chain fails
```

## Implementation Plan

1. Add `lib/hangctl/ftctl_guard.sh`.
2. Source it from `bin/ablestack_vm_hangctl.sh`.
3. Check the guard after confirmed detection and before action execution.
4. Recheck the guard at action entry to protect direct action calls.
5. Add dump mode configuration defaults and live-dump behavior.
6. Validate shell syntax and deploy qemu package before retesting HA-RKY.

## Expected Result

During HA/FTCTL blockcopy:

- hangctl can still observe and log suspected QMP stalls.
- hangctl does not destroy or kill an FTCTL-managed VM.
- FTCTL remains the owner of replication state and progress.
- HA-RKY blockcopy can complete without host-side safety tooling terminating the primary VM.

## Validation Plan

1. Run qemu shell syntax validation for changed scripts.
2. Build qemu package through GitHub Actions.
3. Deploy package to all test hosts.
4. Clean `ha-r9-01` FTCTL state and details.
5. Run HA-RKY 01-15 from the beginning.
6. While blockcopy is active, verify:
   - FTCTL progress events advance.
   - Fault Protection tab remains responsive.
   - hangctl logs `vm.action_guard` or `action.skip` instead of dump/destroy/TERM/KILL if QMP is slow.
   - Primary VM is not destroyed by hangctl.
