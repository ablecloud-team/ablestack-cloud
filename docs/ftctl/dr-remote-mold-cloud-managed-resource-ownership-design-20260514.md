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

# DR Remote Mold Cloud-Managed Resource Ownership Design

Date: 2026-05-14

## 1. Purpose

Remote Mold DR must follow the same ownership model as the validated HA cloud-managed path.

Cloud owns VM and volume lifecycle. qemu FTCTL owns replication and Cloud-requested disaster-recovery data-plane actions. Mold Agent delivers commands and returns logs/status/events. Remote Mold DR must not shift replica VM or RBD/volume creation into qemu FTCTL.

For Cloud-managed automatic HA/DR, Cloud also owns the automatic fencing decision and standby VM lifecycle orchestration. qemu FTCTL may report candidate/runtime evidence and execute explicit data-plane commands, but it must not be the automatic failover controller. The governing automatic-fencing design is `204-cloud-managed-ha-dr-automatic-fencing-orchestration-design-20260514.md`.

If the source Mold is unavailable after disaster failover, the remote Mold must be able to continue from a separate replica recovery session for disaster failback or non-destructive replica adoption/release. That target-side authority is defined in `206-dr-replica-site-disaster-failback-and-adoption-design-20260517.md` and does not transfer VM/volume creation ownership to qemu FTCTL.

This document is remote-Mold focused. The cross-cutting DR provisioning contract for both current Mold and remote Mold is defined in `200-dr-current-remote-mold-common-provisioning-design-20260514.md` and must be treated as the governing design for new DR changes.

## 2. Required Boundary

Cloud responsibilities:

- create source-side FTCTL protection records
- create local replica VM/volumes for local-Mold HA/DR
- call remote Mold Cloud APIs to create remote replica VM/volumes for remote-Mold DR
- persist local or remote replica metadata
- query Cloud API/DB state
- query qemu FTCTL events/logs/status through backend or Mold Agent paths
- start/stop Cloud-owned VMs during failover/failback
- clean Cloud-created resources on release/forced-release

Mold Agent responsibilities:

- forward FTCTL commands to qemu
- return qemu status, logs, events, and command results
- install/remove DR SSH public keys only when Cloud asks through the explicit DR SSH setup flow

qemu FTCTL responsibilities:

- SSH/libvirt/NBD preflight
- remote-nbd export handling
- blockcopy and reverse sync
- Cloud-requested failover/failback data-plane checks
- events.log/progress/status production

qemu FTCTL must not create, define, start, stop, delete, attach, detach, resize, or format Cloud-managed VMs or volumes.
For Cloud-managed automatic flows, qemu FTCTL must also not decide automatic failover from a single libvirt/domain observation or start Cloud-managed standby VMs.

## 3. Current Cloud Gap

The local Cloud-managed path already creates standby root/data volumes and a standby VM before qemu starts replication.

The remote Mold DR path currently stores remote host/storage metadata and can proceed without a remote Mold-created replica VM/volume mapping. That allows qemu remote-nbd logic to infer or prepare target paths. For Cloud-integrated DR this is the wrong owner.

## 4. New Cloud Design

Add a remote provisioning strategy behind FTCTL protection registration. This strategy must be a peer of the current-Mold provisioning path, not a special case:

- local Mold path: use the existing Cloud-managed provisioning service.
- remote Mold path: use a new remote Mold DR provisioning strategy.
- registration defaults to `cloud-managed` for both local/current Mold and remote Mold; `libvirt-managed` is an explicit legacy/standalone choice, not the implicit Cloud UI/API path.

Both paths must return the same canonical replica resource model before source-side FTCTL protection state is persisted.

The remote strategy calls a remote Mold-side API such as `prepareFtctlDrReplicaResources`.

The remote command must run inside the remote Mold management service and use Cloud APIs/DB to:

- create a stopped replica VM
- create root and data volumes matching source protected disks
- attach replica volumes with matching device IDs
- return remote VM UUID/name/instance name
- return remote volume UUID/name/path/device mapping
- return a complete qemu disk map

The source Cloud then stores remote external identities and passes only explicit Cloud-created disk paths to qemu.

## 5. Registration Sequence

For `mode=dr`, `drpeersitetype=remote-mold`, `provisioningbackend=cloud-managed`, and `backendmode=remote-nbd`:

1. Validate the source VM is Running.
2. Validate remote Mold connection with transient credentials.
3. Resolve remote host/storage/network/offering inputs.
4. Resolve SSH/libvirt/NBD transfer path.
5. Prepare SSH key access if the operator selected automatic setup.
6. Run qemu preflight through Mold Agent.
7. Call remote Mold Cloud API to create replica VM and volumes.
8. Query remote Mold until replica volume paths are ready.
9. Persist source protection rows and remote external-resource mappings.
10. Sync qemu profile with explicit `disk_map`.
11. Send qemu `protect`.

If remote transfer preflight fails, Cloud must not create durable protection state or remote replica resources.

If remote resource creation fails, Cloud must clean up Cloud-created partial resources or preserve enough remote IDs for a Cloud-owned cleanup action.

## 6. API And Persistence Requirements

Remote Mold credentials:

- accepted only as transient request inputs
- never persisted in VM details, FTCTL profiles, host files, or logs

Remote resource metadata:

- remote site/API identity
- remote VM UUID/name/instance name
- remote volume UUID/name/path per protected source disk
- remote storage pool UUID/name/type/path
- remote host UUID/address/libvirt URI
- qemu disk target to remote path mapping

Because remote resources do not have local Cloud numeric IDs, FTCTL must not overload local `secondary_vm_id` or `secondary_volume_id` with remote identities. Use external identity columns or a dedicated mapping table.

## 7. qemu Contract Expected By Cloud

For Cloud-managed remote-nbd:

- `disk_map` is mandatory.
- `disk_map=auto` is invalid.
- every destination path must come from Cloud.
- missing target paths are fatal errors.
- qemu may verify and export existing targets.
- qemu must not create or reformat Cloud-managed targets.

## 8. UI Impact

- `Use remote Mold` remains optional.
- remote Mold lookup fields are shown only when selected.
- remote host/storage choices come from remote Mold API.
- SSH edit fields remain disabled unless the user chooses to customize them.
- automatic SSH key setup remains optional.
- arbitrary qemu target directory creation must not be exposed as the Cloud-managed DR resource model.

Additional remote Cloud provisioning choices may be needed for zone, network, service offering, disk offering, and account/domain mapping. Defaults must be Cloud-resolved and visible as Cloud-managed choices.

## 9. Verification

- remote-Mold DR registration calls the remote Cloud provisioning API before qemu `protect`.
- remote Mold DB contains the replica VM/volumes before qemu replication starts.
- source Cloud stores remote external IDs and complete disk map.
- qemu profile contains explicit Cloud-created paths.
- qemu events/logs show replication actions only.
- release/forced-release cleans remote Cloud-created resources through Cloud APIs.
- replica-site forced release/adoption preserves the running replica VM and volumes by default when the source Mold is unavailable.
- existing HA cloud-managed manual/operator tests remain unchanged.
- Cloud-managed automatic HA/DR tests follow `204-cloud-managed-ha-dr-automatic-fencing-orchestration-design-20260514.md`.
