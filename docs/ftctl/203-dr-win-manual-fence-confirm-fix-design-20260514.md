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

# DR-WIN Manual Fence Confirmation Fix Design - 2026-05-14

## Scope

This document covers the operator-driven `manual-block` DR-WIN fence confirmation flow.

It does not define Cloud-managed automatic fencing or automatic failover ownership. For automatic HA/DR, Cloud must own the fencing decision and Cloud VM lifecycle orchestration, while qemu FTCTL remains the replication/data-plane executor. See `204-cloud-managed-ha-dr-automatic-fencing-orchestration-design-20260514.md`.

## Background

During DR-WIN validation, the operator completed pause, resume, failover request, and then stopped the primary VM. The UI could not proceed with fence confirmation. Runtime state had already changed to `protection_state=error`, `transport_state=rearm_exhausted`, `active_side=primary`, `fencing_state=required`, and `last_error=rearm_attempts_exhausted`.

## Root Cause

1. qemu FTCTL preserved HA manual-fence failover state, but did not preserve the same DR state. DR reconcile treated primary libvirt loss as transport loss and attempted blockcopy rearm until exhaustion.
2. qemu FTCTL only required blockcopy-ready markers for HA. DR remote-nbd failover therefore did not consistently record `failover_ready` before waiting for manual fencing.
3. Cloud manual fence confirmation assumed the standby VM exists in the current Mold database. Remote Mold DR intentionally has no local `secondary_vm_id`; the remote standby VM UUID is stored in source VM details.

## Design Principles

- Preserve the HA-proven responsibility split:
  - Cloud owns VM, volume, and lifecycle API calls.
  - Mold agent relays ftctl commands and status/log collection.
  - qemu FTCTL performs replication, NBD export release, Cloud-requested failover/failback data-plane transitions, and runtime state persistence.
- Apply DR behavior consistently for both current Mold and remote Mold.
- Do not persist remote Mold API or secret keys. Remote API URL may be stored as a non-secret endpoint, but API key and secret key must be action-scoped only.
- Do not weaken the already validated HA flow.

## qemu FTCTL Changes

These qemu changes are scoped to preserving a manual/operator failover state. They must not be interpreted as qemu-owned Cloud-managed automatic failover orchestration.

- Extend blockcopy-ready precheck from HA to DR for `remote-nbd` and `shared-blockcopy`.
- Record `failover_ready=1` before entering manual fencing wait in DR.
- Extend reconcile deferral from HA to DR when the VM is in primary-side `failing_over` with manual fencing required and a valid failover-ready marker.
- While deferred, update only reconcile timestamp and log `reconcile.defer reason=manual_fence_in_progress`; do not refresh blockcopy or attempt rearm.

## Cloud Changes

- Expose DR peer-site metadata through `getFtctlProtection`: `drpeersitetype` and `remotemoldapiurl`.
- Keep current Mold DR on the existing local lifecycle path using `secondary_vm_id`.
- Add remote Mold DR lifecycle path to `confirmFtctlFence`:
  - require one-time `remotemoldapikey` and `remotemoldsecretkey`;
  - use stored or supplied `remotemoldapiurl`;
  - call remote Mold `listVirtualMachines` to skip start if already running;
  - call remote Mold `startVirtualMachine` with the remote standby VM UUID and remote target host UUID;
  - poll `queryAsyncJobResult` until success, failure, or timeout;
  - persist the latest remote standby state snapshot back into source VM details.
- Do not perform local NIC DAO handoff for remote Mold DR.

## UI Changes

- For current Mold DR and HA, keep the existing confirmation popconfirm flow.
- For remote Mold DR, show a dedicated fence confirmation modal that collects remote Mold API URL, API key, and secret key.
- Submit those credentials only with the `confirmFtctlFence` action request.

## Expected Outcome

After this fix, DR-WIN manual-block failover should remain fence-confirmable after the primary VM is stopped:

- `protection_state=failing_over`
- `active_side=primary`
- `fencing_state=required` or `manual-fenced`
- no `rearm_exhausted` transition while waiting for operator fence confirmation

Remote Mold DR fence confirmation should confirm qemu manual fencing, start the remote standby VM through the remote Mold API, execute qemu failover finalization, and converge to `failed_over/failed_over/secondary/manual-fenced`.
