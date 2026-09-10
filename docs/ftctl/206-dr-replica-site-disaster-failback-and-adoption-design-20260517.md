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

# 206. DR Replica-Site Disaster Failback And Adoption Design

Date: 2026-05-17

## 1. Purpose

Remote-Mold DR cannot assume that the original source Mold will survive the disaster. If the source Mold or source site is destroyed, the operator cannot run failback or forced release from the source-side protection page.

This document defines the Cloud-side model where the Mold that owns the running DR replica VM becomes the recovery controller.

It adds two required capabilities:

- replica-site disaster failback to a restored or newly installed target Mold.
- replica-site forced protection release/adoption when the replica VM will run long term and the source site may never return.

## 2. Related Designs

This document extends and supersedes conflicting wording in:

- [200. DR Current/Remote Mold Common Provisioning Design](200-dr-current-remote-mold-common-provisioning-design-20260514.md)
- [202. DR Remote Mold Standby Read-Only Projection Design](202-dr-remote-mold-standby-readonly-projection-design-20260514.md)
- [204. Cloud-Managed HA/DR Automatic Fencing Orchestration Design](204-cloud-managed-ha-dr-automatic-fencing-orchestration-design-20260514.md)
- [205. DR Fence Clear Re-arm And SSH Key Binding Design](205-dr-fence-clear-rearm-ssh-key-binding-design-20260515.md)

If an earlier document says that a remote Mold standby page is always read-only or that all DR failback/release actions must be initiated by the source Mold, read that statement as applying only while the source Mold remains available and authoritative. After disaster failover, the replica Mold may become the active DR controller.

The qemu-side companion design is `208-dr-replica-site-disaster-failback-and-adoption-design-20260517.md` in the qemu FTCTL repository.

The adopted-replica re-protection follow-up is [207. DR Adopted Replica Re-protection Readiness Design](207-dr-adopted-replica-reprotect-readiness-design-20260519.md).

## 3. Principles

- Cloud owns Cloud-managed VM, volume, network, storage, host placement, and lifecycle APIs.
- qemu FTCTL owns replication, reverse copy, NBD/export handling, finalize, reprotect, and cleanup of qemu runtime state.
- Mold Agent relays explicit qemu FTCTL commands and returns status, events, logs, and command results.
- qemu FTCTL must not create, start, stop, delete, attach, detach, resize, or format Cloud-managed VMs or volumes.
- Mold API keys and secret keys are transient operator inputs unless an approved Cloud secret-reference mechanism is introduced.
- A running DR replica VM must not be deleted by replica-site forced release unless the operator explicitly chooses a destructive cleanup mode.
- HA and source-controller DR behavior must not regress.

## 4. Replica-Site Recovery Session

The remote Mold must persist a non-secret recovery session for Cloud-managed DR replica resources. VM details alone are not sufficient once the source Mold is gone.

Suggested durable fields:

- `recoverySessionUuid`
- stable logical protected VM UUID
- source VM UUID, display name, and instance name
- replica VM UUID, display name, and instance name
- replica host UUID/name/address snapshot
- disk label to replica volume UUID/name/path map
- original source volume UUIDs and disk labels
- backend mode and transfer endpoint metadata
- current role: `standby`, `active-replica`, `adopted`
- last known protection state and transport state
- fencing result/reason snapshot
- source Mold API URL as a non-secret display hint when available
- source protection UUID or external protection id when available
- timestamps for registration, failover, adoption, release, and failback attempts

The session must not persist API keys, secret keys, signed requests, or raw private key material.

## 5. Disaster Failback From Replica Mold

When the source Mold is unavailable, the replica Mold owns the orchestration.

Sequence:

1. Load the replica-site recovery session.
2. Validate that the requested VM is the active DR replica or an adopted replica.
3. Collect and validate target Mold context from the operator.
4. Query target Mold zone, host, storage pool, network, service offering, and disk offering choices.
5. Ask the target Mold to create or validate a stopped target primary VM and target volumes.
6. Build explicit source replica disk to target disk mappings.
7. Ask the replica-side qemu FTCTL host to copy data into the target Mold-created paths.
8. Monitor qemu progress through Mold Agent events/status.
9. Stop the active replica VM through the replica Mold when reverse copy is ready.
10. Ask qemu FTCTL to finalize target disks.
11. Start the target primary VM through the target Mold.
12. Write a target-side handoff/protection record.
13. Mark the replica-side recovery session as `failed_back_to_target` or `reprotected`.

Source Mold reconciliation is optional and best-effort.

## 6. Forced Protection Release And Adoption

If the operator decides to run permanently or long term from the replica site, the replica Mold must provide a non-destructive release/adoption action.

Suggested API:

- `releaseFtctlDrReplicaProtection`
- or `adoptFtctlDrReplica`

Default behavior:

```text
preserveReplicaVm=true
preserveReplicaVolumes=true
abandonSource=true
cleanupTransport=true
```

The backend must:

1. Load the replica-site recovery session.
2. Verify the requested VM is the active replica or already adopted workload.
3. Ask qemu FTCTL to clean session-specific NBD exports, locks, profiles, state files, and generated SSH key material.
4. Remove or archive FTCTL standby/replica markers from the replica VM according to the chosen policy.
5. Preserve replica VM NICs, volumes, account ownership, service offering, and Cloud lifecycle state.
6. Close the recovery session as `adopted` or `released`, then remove protection-blocking `ftctl.*` VM details from the replica VM so it is immediately eligible for new protection registration.
7. Optionally call source Mold to mark the source-side protection released if credentials are supplied and the source Mold is reachable.

Failure to reach the source Mold records `source_abandoned` but does not fail adoption.

The operation must not destroy, expunge, detach, or delete the replica VM or volumes unless the operator selects a destructive cleanup mode.

Adoption/release is terminal for the old DR relationship. The backend must not leave `ftctl.last.*` state details that make a later `getFtctlProtection` response look protected when no active `ftctl_protection` row exists. Audit belongs in Cloud events and the action response, not in long-lived VM details that block re-protection.

## 7. UI Contract

Before failover, the remote Mold standby page remains a projection view.

After failover or source unavailability, the FTCTL tab for the replica VM may expose:

- `Disaster failback`
- `Adopt replica as primary`
- `Forced protection release`

The UI must clearly distinguish these actions from source-controller `Failback` and source-controller `Protection release`.

The disaster failback dialog must collect target Mold context, target host, storage, and network selections. Target network selection remains mandatory when Cloud-managed VM creation requires a network.

The adoption/release dialog must state that the running replica VM and volumes are preserved by default and that source Mold cleanup is best-effort.

After adoption/release completes, the same FTCTL tab must return to the normal unconfigured VM view. If the adopted VM is Running, the Protection Configuration button must be available so the operator can protect the newly active workload.

## 8. State Model

Replica-site DR adds these states:

- `standby`
- `failed_over`
- `source_unavailable`
- `disaster_failback_target_prepared`
- `reverse_syncing_to_target`
- `reverse_sync_ready`
- `cutback_in_progress`
- `failed_back_to_target`
- `adopt_requested`
- `adopted`
- `released`
- `release_failed`

These states belong to the replica recovery session. They are not a duplicate source-side `ftctl_protection` row.

## 9. Conflict Resolution

The earlier read-only standby projection remains valid only while the source Mold is healthy or the replica has not taken over.

After failover/source unavailability:

- the replica Mold may expose disaster failback and adoption actions.
- the replica Mold may keep a local recovery session.
- the replica Mold must not invent local numeric IDs for source-side resources.
- source Mold reconciliation is optional and best-effort.

## 10. Verification Requirements

- Remote standby page is read-only before failover.
- Remote replica page exposes disaster actions after failover/source unavailability.
- Replica-site disaster failback creates target VM/volumes through target Mold APIs.
- qemu logs/events show only data-plane copy/finalize/cleanup actions.
- forced release/adoption preserves the running replica VM and volumes.
- adopted/released replica VM can be protected again as a normal primary candidate.
- source Mold credentials are optional and never persisted.
