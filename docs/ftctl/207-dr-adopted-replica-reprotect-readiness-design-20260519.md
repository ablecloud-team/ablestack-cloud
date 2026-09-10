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

# 207. DR Adopted Replica Re-protection Readiness Design

Date: 2026-05-19

## 1. Purpose

After a remote-Mold DR replica is adopted as the operating VM, or its replica-side protection relationship is force released, that VM becomes a normal production VM on the replica Mold.

The current failure mode is that the replica VM keeps terminal FTCTL details such as `ftctl.dr.recovery.role=adopted`, `ftctl.dr.recovery.released=true`, and `ftctl.last.protection.state=adopted`. `getFtctlProtection` then projects those details as an active protection-like state, so the UI hides the Protection Configuration button even though there is no active `ftctl_protection` row.

This document defines the correction: adoption/release closes the DR recovery session and must leave the VM immediately eligible for new protection registration.

## 2. Related Designs

This document extends:

- [206. DR Replica-Site Disaster Failback And Adoption Design](206-dr-replica-site-disaster-failback-and-adoption-design-20260517.md)
- qemu companion document `209-dr-adopted-replica-reprotect-readiness-design-20260519.md`

It supersedes any wording that implies an adopted replica should keep `ftctl.last.*` VM details as its long-term state record.

## 3. Principles

- Cloud owns Cloud-managed VM, volume, network, storage, host placement, and lifecycle APIs.
- qemu FTCTL owns only data-plane replication, copy/finalize, transport, and qemu runtime cleanup.
- A replica-side adoption or non-destructive release must preserve the VM, volumes, NICs, account ownership, service offering, and Cloud lifecycle state.
- Adoption/release is terminal for the old DR relationship, not an active protection state.
- Audit belongs in Cloud events and action responses. Protection-blocking VM details must not be retained merely as audit history.
- HA and source-controller DR behavior must not regress.

## 4. Backend State Transition

When `adoptFtctlDrReplica` or `releaseFtctlDrReplicaProtection` succeeds:

1. Validate that the requested VM is a remote DR replica recovery target.
2. Optionally ask qemu FTCTL to clean session-specific transport state.
3. Preserve the VM and volumes.
4. Remove all `ftctl.*` VM details from the adopted/released replica VM.
5. Return an action response with terminal values such as:

```text
protectionState=disabled
transportState=stopped
activeSide=primary
adminState=inactive
fencingState=clear
```

6. Publish a Cloud event recording the adoption/release decision.

After step 4, a refreshed `getFtctlProtection` response for the VM must contain only VM identity/basic state unless a new active protection has been registered.

## 5. Defensive Compatibility

Older deployments may already have adopted/released VMs with stale terminal details.

`getFtctlProtection` must therefore treat this detail combination as unconfigured when no active protection row exists:

```text
ftctl.dr.recovery.released=true
ftctl.dr.recovery.role=adopted|released
```

The response must not project stale `ftctl.last.*` values into `protectionState`, `transportState`, `activeSide`, `adminState`, or `fencingState`.

`registerFtctlProtection` must also clear this closed recovery session before writing a new protection configuration, so a stale UI cache cannot block backend registration.

## 6. UI Contract

The FTCTL tab must treat a terminal adopted/released/disabled projection with no `enabled`, `mode`, or `backendMode` as unconfigured.

Expected UI result:

- running adopted replica VM: Protection Configuration button is visible.
- stopped adopted replica VM: Protection Configuration button remains blocked by the existing VM-running rule.
- active standby replica before adoption: replica recovery action view remains visible.
- active source-controller protection: normal protection state and action buttons remain visible.

## 7. Verification

Verify the following from the replica Mold after adoption/release:

- active `ftctl_protection` rows for the VM: `0`.
- `vm_instance_details` rows with `name LIKE 'ftctl.%'` for the VM: `0`.
- `getFtctlProtection` returns VM identity/basic state and no active protection state fields.
- the FTCTL tab shows Protection Configuration when the adopted VM is Running.
- registering new protection on the adopted VM creates a new protection record and new details from the new registration only.
