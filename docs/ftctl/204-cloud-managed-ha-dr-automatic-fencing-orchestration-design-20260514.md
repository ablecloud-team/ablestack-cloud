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

# Cloud-Managed HA/DR Automatic Fencing Orchestration Design

Date: 2026-05-14

## 1. Purpose

This document corrects and unifies the HA/DR automatic fencing design for FTCTL when `provisioningbackend=cloud-managed`.

The governing rule is:

- Cloud owns Cloud-managed VM, volume, network, host placement, fencing decision orchestration, and VM lifecycle API calls.
- Mold Agent forwards FTCTL commands and returns qemu FTCTL status, logs, events, and command results.
- qemu FTCTL owns replication, blockcopy/NBD handling, data-plane transition checks, and low-level command execution after Cloud has chosen the lifecycle transition.

For Cloud-managed protection, qemu FTCTL must not be the automatic failover controller.

## 2. Current Implementation Finding

The current qemu reconciler can auto-request HA failover when:

- `mode=ha`
- `active_side=primary`
- local libvirt domain lookup fails

That behavior is insufficient as the Cloud-managed automatic fencing model because:

1. Cloud-managed standby VMs are intentionally Cloud-owned and can be `Stopped` or transient from the qemu/libvirt point of view.
2. The standby VM may not be defined in libvirt until Cloud starts it.
3. qemu profile sync currently targets the primary VM execution host. If that host is lost, the qemu timer on that host is not a reliable controller.
4. qemu can only infer from local runtime/libvirt signals, while Cloud has the authoritative VM state, host state, agent heartbeat, OOBM data, resource mappings, and current/remote Mold API paths.
5. qemu already returns `cloud_managed_standby_start_pending` instead of starting the standby VM itself, which confirms that Cloud must own the next lifecycle step.

Therefore, qemu automatic failover behavior must be treated as a legacy/libvirt-managed helper or a candidate signal, not as the Cloud-managed HA/DR controller.

## 3. Corrected Ownership Model

### Cloud

Cloud must:

- create or reuse the Cloud-managed standby/replica VM and volumes.
- own target host, target storage, target network, and target offering selection.
- persist source/replica metadata for current-Mold and remote-Mold paths.
- collect or project runtime state from qemu FTCTL and Cloud DB/API.
- run the Cloud-managed automatic failover reconciler.
- decide whether automatic fencing is strict, manual-required, confirmed, or disaster-assumed.
- perform Cloud VM lifecycle calls such as `startVirtualMachine`, `stopVirtualMachine`, and remote Mold API equivalents.
- command qemu FTCTL to prepare/finalize failover or failback after Cloud has completed the lifecycle decision.

### Mold Agent

Mold Agent must:

- forward explicit FTCTL commands to qemu.
- return qemu status, events, logs, progress, and command output.
- install/remove DR SSH keys only when Cloud asks through the explicit DR SSH setup flow.

Mold Agent must not make the automatic fencing decision.

### qemu FTCTL

qemu FTCTL must:

- perform SSH/libvirt/NBD preflight.
- perform blockcopy, reverse sync, remote-nbd export handling, and data-plane checks.
- report replication readiness, runtime state, progress, and events.
- execute explicit low-level FTCTL actions requested by Cloud.
- mark manual/operator states where a qemu data-plane step must be preserved.

For Cloud-managed HA/DR, qemu FTCTL must not:

- decide automatic failover from a single libvirt/domain probe.
- create, define, start, stop, delete, attach, detach, resize, or format Cloud-managed VMs or volumes.
- treat a missing transient standby libvirt domain as a Cloud resource failure.
- directly start the standby VM after fencing.

## 4. Cloud-Managed Automatic Failover Reconciler

Add a Cloud-side reconciler, for example `FtctlCloudManagedFailoverMonitor`.

The reconciler evaluates active Cloud-managed HA/DR protections and gathers multiple signals:

- source VM Cloud state.
- source VM host state and agent heartbeat.
- qemu FTCTL runtime heartbeat and last status timestamp.
- replication/blockcopy readiness and freshness.
- source and target host reachability.
- Mold management API reachability for the current or remote Mold path.
- optional QGA/libvirt runtime observations.
- OOBM/IPMI availability and command result when automatic fencing is enabled.

The reconciler must not trigger failover from one failed virsh lookup. It must require repeated or quorum-style failure observations before entering automatic fencing.

Suggested state progression:

1. `protected` / `mirroring` / `primary` / `clear`
2. `failover_suspect` after first multi-signal failure observation
3. `fencing_required` or `fencing_in_progress` after repeated confirmation
4. `fenced`, `manual-required`, or `assumed-fenced`
5. `standby_starting`
6. `failover_finalizing`
7. `failed_over` / `failed_over` / `secondary`

Cloud persists the state in `ftctl_protection` and VM details. qemu events remain visible as data-plane evidence, not as the sole Cloud lifecycle authority.

## 5. HA Automatic Fencing Policy

HA is an availability flow inside one administrative Cloud control plane. Split-brain prevention is more important than aggressive recovery.

Default HA automatic policy:

- Use strict fencing.
- IPMI/OOBM must be configured and confirmed for automatic failover.
- If OOBM is missing, disabled, unreachable, or the power-off command cannot be confirmed, Cloud must not auto-start the standby VM.
- The protection should move to `manual-required` or equivalent operator-confirmation state.

This keeps the already validated HA manual-block behavior intact.

## 6. DR Automatic Fencing Policy

DR is a disaster flow. The source site may be powered off, unreachable, or destroyed. DR therefore needs a separate disaster-assumed policy.

Default DR automatic policy:

- Use heartbeat and multi-signal failure detection before fencing.
- Try IPMI as the final complete fencing method when OOBM data is available.
- If IPMI succeeds, record `ipmi_confirmed_fenced`.
- If OOBM data is missing, record `ipmi_unknown_assumed_fenced`.
- If IPMI command/status fails because the disaster makes the source unreachable, record `ipmi_failed_assumed_fenced`.
- `assumed-fenced` must be shown differently from confirmed fencing in API responses, UI, and events.

The DR assumed-fenced path is allowed only after the Cloud reconciler has classified the situation as a disaster candidate through repeated multi-signal checks.

## 7. Current-Mold DR

Current-Mold DR must use the same Cloud-managed reconciler architecture as HA.

Differences from HA:

- The replica target may be in the same Zone or a different Zone. Different Zone is not mandatory.
- The target host/storage/network selection still follows the DR target resource model.
- If the target Zone list has a single entry, the UI may auto-select it and avoid showing a redundant Zone choice.
- The target network must be selected from networks usable by the target host/Zone, not copied blindly from the source VM network ID.
- The selected target network IDs must be carried through `registerFtctlProtection` into the Cloud-owned standby VM creation call. Missing DR target network IDs are a registration error, not a qemu recovery condition.
- DR may use the disaster-assumed fencing policy, while HA defaults to strict fencing.

Cloud starts the current-Mold standby VM through local Cloud APIs. qemu FTCTL then finalizes the data-plane failover after Cloud has started the standby VM.

## 8. Remote-Mold DR

Remote-Mold DR cannot depend on one-time operator credentials for automatic failover.

Manual remote-Mold DR may still collect remote Mold API URL, API key, and secret key in the fence confirmation dialog and submit them only with that action.

Automatic remote-Mold DR requires one of these durable models:

- a secure Cloud-side encrypted automation credential model for the remote Mold, or
- a target-side protection projection/reconciler in the remote Mold that can start the replica without calling back to the failed source Mold.

Because the source Mold may be unavailable in a real disaster, the preferred long-term model is target-side orchestration:

- source Mold owns registration and normal replication while healthy.
- remote Mold stores sanitized replica metadata, target resource mappings, and required OOBM/fencing metadata.
- remote Mold evaluates disaster heartbeat/projection state when configured for automatic DR.
- remote Mold starts the replica VM through its own Cloud APIs after confirmed or assumed fencing.
- source-side protection state is reconciled later when the source Mold returns.

The same target-side authority must cover post-failover recovery operations. If the source Mold is unavailable for a long time or permanently lost, the remote Mold must also be able to run disaster failback to a restored/new target Mold or adopt the running replica VM as an independent production VM. That model is defined in `206-dr-replica-site-disaster-failback-and-adoption-design-20260517.md`.

Remote Mold API keys must not be written into qemu profiles, host files, VM details, or logs. OOBM secrets must be stored only through an approved Cloud secret/encryption mechanism or as a non-secret reference.

## 9. qemu Changes Required

For Cloud-managed protections:

- Disable direct automatic `ftctl_failover_request` from the qemu reconciler, or downgrade it to a candidate event such as `cloud_managed_failover_candidate`.
- Keep manual-fence deferral logic for operator-driven HA/DR flows.
- Keep blockcopy-ready markers and NBD release/finalize commands.
- Ensure transient/missing standby domains are treated as expected when Cloud has not started the standby VM.
- Add explicit result/event names for Cloud-directed fencing/failover steps so Cloud can distinguish data-plane readiness from lifecycle completion.

For libvirt-managed standalone protections, existing qemu-driven automatic behavior can remain if it is still required.

## 10. Cloud Changes Required

Cloud must add or update:

- `FtctlCloudManagedFailoverMonitor` or equivalent scheduler.
- multi-signal failover suspicion counters and timestamps.
- current-Mold and remote-Mold execution branches.
- strict HA fencing result handling.
- disaster-assumed DR fencing result handling.
- standby VM start orchestration for current-Mold and remote-Mold DR.
- qemu `FAILOVER_PREPARE` / `FAILOVER` sequencing after Cloud starts the standby VM.
- API response fields for fencing classification, for example `fencingresult=confirmed|assumed|manual-required` and `fencingreason`.
- UI labels that clearly distinguish confirmed fencing from assumed disaster fencing.

The existing `FtctlRuntimeStateSync` remains useful, but it is not enough. Runtime sync copies qemu state into Cloud; it does not decide or execute Cloud-managed automatic failover.

## 11. Manual Flow Compatibility

Manual HA/DR flows remain valid:

- qemu can preserve `failing_over` plus manual fencing markers.
- Cloud UI can collect operator confirmation.
- For remote-Mold manual DR, the dialog may collect one-time remote Mold credentials.
- Cloud starts the standby VM through the relevant Mold API.
- qemu finalizes the data-plane transition only after Cloud lifecycle work is complete.

Manual flow documents such as `203-dr-win-manual-fence-confirm-fix-design-20260514.md` remain scoped to operator-driven failover and must not be read as the automatic Cloud-managed orchestration design.

## 12. Conflict Resolution

This document supersedes any earlier wording that implies qemu FTCTL owns Cloud-managed automatic failover or automatic fencing orchestration.

The consistent interpretation across the FTCTL DR design set is:

- qemu FTCTL owns replication and data-plane execution.
- Cloud owns Cloud-managed VM and volume lifecycle.
- Cloud owns Cloud-managed automatic HA/DR fencing decisions.
- qemu automatic failover may remain only for libvirt-managed or legacy standalone paths.
- Remote-Mold automatic DR requires durable target-side orchestration or a secure automation credential model; one-time UI credentials are manual-flow only.
- Replica-site long-term operation requires target-side disaster failback and non-destructive replica adoption/release authority; it must not depend on source Mold recovery.

## 13. Verification Requirements

Design-level verification:

- HA cloud-managed auto failover does not depend on standby libvirt domain existence before Cloud starts the standby VM.
- HA strict IPMI failure does not auto-start the standby VM.
- DR disaster-assumed fencing records assumed status distinctly from confirmed fencing.
- Current-Mold DR and remote-Mold DR both use Cloud-managed lifecycle calls.
- qemu events show replication and data-plane transitions, not Cloud VM lifecycle ownership.

Runtime verification after implementation:

- qemu reconciler no longer starts Cloud-managed automatic failover from a single local domain miss.
- Cloud monitor can detect a multi-signal HA failure and stop at manual-required when strict fencing cannot be confirmed.
- Cloud monitor can detect a DR disaster candidate and apply confirmed or assumed fencing policy.
- Cloud starts the standby VM through local or remote Mold APIs.
- qemu finalizes failover only after Cloud has started the standby VM.
