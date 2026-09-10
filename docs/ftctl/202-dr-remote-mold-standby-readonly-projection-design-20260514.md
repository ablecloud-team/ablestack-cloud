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

# FTCTL DR Remote Mold Standby Projection And Disaster Action Boundary Design

## Background

DR cloud-managed provisioning now creates the standby VM and volumes through the target Mold service when the replica site uses a remote Mold. While the source Mold is healthy, the source Mold owns the FTCTL protection row, qemu command path, runtime events, and lifecycle state. The remote Mold owns the Cloud resources that were created in that remote site.

The current remote standby VM detail page still falls back to "not protected" because `getFtctlProtection` only looks for a local active `ftctl_protection` row by primary or secondary VM id. In remote Mold DR, the remote standby VM has no local protection row by design. It only has VM detail markers such as `ftctl.remote.replica.vm=true`, `ftctl.standby.vm=true`, and `ftctl.remote.source.vm.*`.

## Design Principles

1. Preserve the HA ownership model.
   - Cloud creates and manages VMs and volumes through Cloud APIs.
   - Mold Agent relays FTCTL commands and returns qemu-side status or events.
   - qemu FTCTL performs DR replication and Cloud-requested data-plane transition actions.
   - Cloud owns Cloud-managed automatic fencing decisions and standby VM lifecycle orchestration. See `204-cloud-managed-ha-dr-automatic-fencing-orchestration-design-20260514.md`.
   - Cloud reads qemu events/status asynchronously and projects them to the UI.

2. Do not duplicate source protection ownership across Mold services.
   - The source Mold keeps the authoritative `ftctl_protection` row.
   - The remote Mold must not create a synthetic local `ftctl_protection` row for the standby VM.
   - Before failover or while the source Mold remains authoritative, the remote Mold must not issue qemu FTCTL actions from the standby VM detail page.
   - After disaster failover or source Mold loss, the remote Mold may use a separate replica recovery session and expose disaster failback/adoption actions as defined in `206-dr-replica-site-disaster-failback-and-adoption-design-20260517.md`.

3. Apply the model to both current-Mold and remote-Mold DR.
   - Current-Mold DR continues to use the local protection row because both source and standby VM ids are in one Cloud database.
   - Remote-Mold DR uses source-side detail snapshots for source UI and target-side VM detail markers for target UI.
   - Any new DR display logic must explicitly account for both paths.

4. Keep the remote standby page read-only before takeover.
   - The target Mold page should show that the VM is an FTCTL standby/replica resource.
   - It should display local Cloud state for the standby VM and attached volumes.
   - It should not expose protection registration or source-controller lifecycle action buttons for the standby VM.
   - Runtime qemu check/event data is not authoritative on the target Mold unless a source-owned runtime profile is available, so target-side runtime sections should return `not_available` instead of triggering misleading qemu reads.
   - If the replica has failed over, is running as the active workload, or the source Mold is unavailable, the page leaves read-only projection mode and follows the replica-site disaster action model.

## Current Failure

Observed after DR-WIN-03 retest:

- Source Mold VM page:
  - Primary VM state is shown.
  - Remote standby VM state, host, and remote volumes are shown.
  - Protection state is shown as protected/mirroring/primary/clear.

- Remote Mold standby VM page:
  - The standby VM is stopped, as expected for cloud-managed standby.
  - The FTCTL tab displays "protection is not configured".
  - The configure button is disabled because the VM is stopped.

The second screen is wrong. A stopped cloud-managed standby VM is expected, and the screen should be a read-only FTCTL standby view, not a registration candidate.

## Backend Changes

`FtctlServiceImpl.buildProtectionResponse()` should add a fallback path:

1. Look up active local protection as today.
2. If no local protection exists, detect a remote Mold standby resource from VM details:
   - `ftctl.remote.replica.vm=true`
   - `ftctl.standby.vm=true`
   - source metadata under `ftctl.remote.source.vm.*`
3. Return a read-only `FtctlProtectionResponse`:
   - `protectionrole=standby`
   - `enabled=true`
   - `mode=dr`
   - `backendmode=remote-nbd`
   - `provisioningbackend=cloud-managed`
   - `provisioningstate=Ready`
   - `protectionstate=protected`
   - `transportstate=not_available`
   - `activeside=primary`
   - `adminstate=read-only`
   - primary VM name/UUID from `ftctl.remote.source.vm.*`
   - secondary VM id/UUID/name/state/host from the requested local standby VM
   - secondary volume list from `volumeDao.findByInstance(standbyVmId)`

`getFtctlCheck`, `getFtctlHealth`, and `getFtctlEvents` should also detect the same remote standby marker and return read-only `not_available` responses without invoking the agent. This prevents a remote Mold standby page from querying qemu runtime data that belongs to the source Mold protection profile.

## UI Changes

The FTCTL tab hides action buttons when `protectionrole=standby` and no disaster takeover/adoption state is present. It also suppresses protection registration in standby view. One adjustment is required:

- If `primaryvirtualmachineid` is absent but `primaryvirtualmachinename` or `primaryvirtualmachineuuid` is present, display it as plain text instead of `-`.

This is needed because a remote Mold cannot route to the source Mold VM detail page using a local numeric VM id.

After failover or source unavailability, the UI must not treat `protectionrole=standby` as a permanent action block. It may expose `Disaster failback`, `Adopt replica as primary`, and `Forced protection release` when the replica recovery session indicates that the replica Mold has become the active DR controller.

## Expected Result

After the change:

- Source Mold page keeps showing the source-owned protection state and remote replica snapshot.
- Remote Mold standby page shows a read-only FTCTL standby view before takeover.
- Remote Mold replica page can expose disaster failback/adoption actions after failover or source Mold loss.
- The remote standby VM being `Stopped` no longer causes the FTCTL tab to show the "not protected" empty state.
- No HA behavior changes.
- No current-Mold DR behavior changes.
- No qemu FTCTL action is issued from the remote standby page before takeover.
