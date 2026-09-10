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

# DR Current And Remote Mold Standby State Projection Design

Date: 2026-05-14

## 1. Purpose

DR protection status must expose the standby VM and volume state through the same Cloud API response shape whether the replica is owned by the current Mold or by a remote Mold.

The current Mold path can read the standby VM and volumes from the local Cloud database by numeric IDs. The remote Mold path cannot store remote numeric IDs as local IDs, so the source Mold must persist a sanitized remote Cloud state snapshot returned by the remote Mold during replica resource preparation.

## 2. Incident Summary

During `DR-WIN-04` and `DR-WIN-05`, source-side protection for `dr-w22-01` was active and qemu FTCTL replication was healthy, but the source VM Fault Protection tab did not show the standby VM state.

Observed source-side response:

- `secondaryvirtualmachineuuid` was present.
- `secondaryvirtualmachinedisplayname` was present.
- `secondaryvirtualmachinestate` was missing.
- `secondaryvirtualmachinehostname` was missing.

Observed remote-side Cloud state:

- remote standby VM `dr-w22-01-standby` existed on the 10.10.32 Mold.
- remote standby VM state was `Stopped`.
- remote root/data volumes were `Ready`.

The UI already renders `secondaryvirtualmachinestate` when the backend supplies it. The missing part is backend projection for the remote Mold path.

## 3. Design Principles

- Current Mold and remote Mold DR must both satisfy the same public protection response contract.
- Cloud owns Cloud-managed replica VM and volume lifecycle and Cloud state lookup.
- qemu FTCTL owns DR replication, NBD/blockcopy, Cloud-requested failover/failback data-plane actions, events, and progress.
- Cloud owns Cloud-managed automatic fencing decisions and standby VM lifecycle orchestration. See `204-cloud-managed-ha-dr-automatic-fencing-orchestration-design-20260514.md`.
- Mold Agent only forwards qemu commands and returns qemu logs/status/events.
- Remote Mold API keys remain transient request inputs and must not be persisted in VM details, FTCTL profiles, host files, or logs.
- Existing HA cloud-managed behavior must not change.

## 4. Response Contract

`getFtctlProtection` must project these standby fields for both current-Mold and remote-Mold Cloud-managed DR:

- `secondaryvirtualmachineuuid`
- `secondaryvirtualmachinedisplayname`
- `secondaryvirtualmachinestate`
- `secondaryvirtualmachinehostid` when the value is numeric in that Mold context
- `secondaryvirtualmachinehostname`
- `secondaryvolumes[].id`
- `secondaryvolumes[].name`
- `secondaryvolumes[].path`
- `secondaryvolumes[].disklabel`
- `secondaryvolumes[].state`

For current Mold, the values come from local DAO/API reads.

For remote Mold, the values come from the remote Mold's `prepareFtctlDrReplicaResources` response and are persisted as sanitized source VM details. These values are a Cloud state snapshot, not a qemu runtime state.

## 5. Remote Mold Snapshot Fields

The remote Mold `prepareFtctlDrReplicaResources` response must include:

- remote replica VM UUID, display name, hypervisor instance name
- remote replica VM state
- remote replica target host ID/name used by Cloud-managed preparation
- snapshot timestamp
- remote replica volume UUID/name/path/state per source protected volume
- canonical disk map

The source Mold persists only these non-secret fields under `ftctl.dr.remote.replica.*`.

## 6. UI Behavior

No UI state derivation should be added for this issue. The UI already displays the standby VM state when `secondaryvirtualmachinestate` exists.

The backend must provide the field consistently, so the UI can render:

- source/current Mold standby VM state from local Cloud DB
- remote Mold standby VM state from the sanitized remote snapshot

If the remote snapshot is absent because protection was created by an older backend, the UI may continue to show `-` until the protection is re-registered or a later refresh path updates the snapshot.

## 7. Verification

Unit verification:

- flat remote prepare responses parse VM state and volume state.
- nested CloudStack remote prepare responses parse VM state and volume state.
- `getFtctlProtection` projects remote Mold standby state from persisted details.
- current-Mold volume state still comes from local Cloud volume state.

Runtime verification:

- remote Mold DR registration persists standby VM state details on the source VM.
- source VM Fault Protection tab shows the remote standby VM state.
- qemu profile and host files do not contain remote Mold API or secret keys.
- HA cloud-managed tests remain unchanged.
