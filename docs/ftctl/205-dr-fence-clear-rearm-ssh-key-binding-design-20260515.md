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

# 205. DR Fence Clear Re-arm And SSH Key Binding Design

Date: 2026-05-15

## 1. Purpose

This document records the DR-WIN manual-fence recovery failure observed after fence confirmation and fence clear.

Fence confirmation succeeded and the remote Cloud-managed replica VM started, but after the operator executed fence clear the source-side protection degraded and finally entered `error / rearm_exhausted`.

The fix must preserve the established FTCTL ownership model:

- Cloud owns Cloud-managed VM, volume, network, host placement, and lifecycle calls.
- Mold Agent forwards explicit FTCTL commands and returns qemu FTCTL status, logs, events, and command results.
- qemu FTCTL owns replication, remote-NBD, blockcopy, and explicit data-plane actions requested by Cloud.
- qemu FTCTL must not become the Cloud-managed automatic failover or failback lifecycle controller.

This design applies to both DR peer-site models:

- current Mold DR
- remote Mold DR

Remote Mold is not an exception path. The same Cloud-managed recovery semantics must be valid when the replica site is controlled by the current Mold or by a remote Mold.

## 2. Related Designs

This document extends these existing designs:

- `200-dr-current-remote-mold-common-provisioning-design-20260514.md`
- `201-dr-current-remote-mold-standby-state-projection-design-20260514.md`
- `204-cloud-managed-ha-dr-automatic-fencing-orchestration-design-20260514.md`

If an older document implies that fence clear should immediately let qemu auto-rearm a Cloud-managed failed-over DR protection, this document supersedes that interpretation.

## 3. Observed DR-WIN Evidence

Target:

- Source VM: `dr-w22-01`
- Source instance: `i-2-381-VM`
- Source host: `10.10.22.3`
- Remote replica VM: `dr-w22-01-standby`
- Remote replica instance: `i-2-26-VM`
- Remote host: `10.10.32.1`
- Protection row: `86`

After fence clear, source Cloud state became:

- `protection_state=error`
- `transport_state=rearm_exhausted`
- `active_side=secondary`
- `fencing_state=clear`
- `last_error=rearm_attempts_exhausted`

The source event stream shows the real sequence:

1. `FENCE_CONFIRM` succeeded.
2. Source Cloud started the remote Mold replica VM.
3. qemu FTCTL failover reached `failed_over / failed_over / secondary / manual-fenced`.
4. `FENCE_CLEAR` succeeded.
5. State was updated to `failed_over / failed_over / secondary / clear`.
6. qemu reconcile then reported `degraded / transient_loss`.
7. qemu reconcile then reported `rearming / rearm_pending`.
8. qemu reconcile finally reported `error / rearm_exhausted`.

Remote-site evidence did not show a replica lifecycle failure:

- remote Mold DB shows `dr-w22-01-standby` as `Running`.
- remote Mold DB shows root and data volumes as `Ready`.
- host `10.10.32.1` shows libvirt domain `i-2-26-VM` as `running`.

The qemu profile on the source host showed:

```bash
FTCTL_PROFILE_SECONDARY_URI="qemu+ssh://root@10.10.32.1:22/system"
FTCTL_PROFILE_SECONDARY_VM_NAME="i-2-26-VM"
FTCTL_PROFILE_PROVISIONING_BACKEND="cloud-managed"
FTCTL_PROFILE_BACKEND_MODE="remote-nbd"
```

The source host also had an FTCTL-generated private key:

```bash
/root/.ssh/ftctl-dr/i-2-381-VM/id_ed25519
```

The remote host accepted that key, but the persisted qemu/libvirt URI did not reference it. The following behavior was observed from the source host:

- `qemu+ssh://root@10.10.32.1:22/system` failed with SSH authentication errors.
- `qemu+ssh://root@10.10.32.1:22/system?keyfile=/root/.ssh/ftctl-dr/i-2-381-VM/id_ed25519` successfully returned the remote domain state.

Therefore, the key setup worked only partially: the key was generated and installed, but qemu runtime access did not bind the key into every remote SSH/libvirt path.

## 4. Root Causes

### 4.1 SSH Key Binding Gap

The automatic DR SSH key setup path installed a protection-scoped public key on the remote host, but the runtime profile still used a generic `qemu+ssh` URI without an explicit key.

This is fragile because:

- libvirt `qemu+ssh` will not necessarily use the FTCTL-generated key.
- global root SSH configuration can override ports or identity behavior.
- the 22 cluster uses port `10022` defaults for local management, while the 32 cluster uses port `22`.
- remote SSH preflight can pass in one code path while reconcile or failback uses another path.

All qemu FTCTL remote operations must use one canonical SSH connection model.

### 4.2 Fence Clear Triggered qemu Auto Re-arm Too Early

For Cloud-managed DR, `failed_over / failed_over / secondary / clear` means:

- the operator has released the manual fence.
- the secondary side is active.
- Cloud may now orchestrate failback or reprotect.

It must not mean:

- qemu should immediately auto-rearm the forward replication path.
- qemu should increment `rearm_count` while Cloud-managed failback is not yet requested.
- qemu should overwrite the recovery-ready state with `rearm_exhausted`.

The observed `rearm_exhausted` is a secondary symptom. The first incorrect transition is the inventory-driven `degraded / transient_loss` after a successful Cloud-managed fence clear.

## 5. Target SSH Connection Contract

Cloud and qemu must treat the DR remote execution path as a materialized connection contract, not as a string-only URI.

The contract must include:

- remote SSH user
- remote SSH port
- remote host address
- remote libvirt URI
- optional FTCTL-generated private key path
- strict non-interactive SSH mode
- explicit identity selection when a key path exists
- known-host behavior selected by the FTCTL policy

For qemu profiles, add explicit fields such as:

```bash
FTCTL_PROFILE_SECONDARY_SSH_USER="root"
FTCTL_PROFILE_SECONDARY_SSH_PORT="22"
FTCTL_PROFILE_SECONDARY_SSH_HOST="10.10.32.1"
FTCTL_PROFILE_SECONDARY_SSH_KEY_FILE="/root/.ssh/ftctl-dr/i-2-381-VM/id_ed25519"
FTCTL_PROFILE_SECONDARY_URI="qemu+ssh://root@10.10.32.1:22/system?keyfile=/root/.ssh/ftctl-dr/i-2-381-VM/id_ed25519"
```

The private key content must never be uploaded to Cloud or stored in Cloud DB. The key path is host-local runtime metadata and may be written only into the qemu host profile or passed as an explicit Mold Agent command parameter.

When a key path exists, qemu must use it for every remote operation:

- `ssh`
- `scp`
- `virsh -c qemu+ssh://...`
- remote-NBD prepare/cleanup scripts
- inventory and standby-domain probes
- failover/failback data-plane checks

The qemu implementation must not rely on `/root/.ssh/config` defaults for DR remote paths.

Suggested shell options:

```bash
ssh -o BatchMode=yes \
    -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=accept-new \
    -i "$FTCTL_PROFILE_SECONDARY_SSH_KEY_FILE" \
    -p "$FTCTL_PROFILE_SECONDARY_SSH_PORT" \
    "$FTCTL_PROFILE_SECONDARY_SSH_USER@$FTCTL_PROFILE_SECONDARY_SSH_HOST" \
    true
```

Suggested libvirt URI:

```text
qemu+ssh://root@10.10.32.1:22/system?keyfile=/root/.ssh/ftctl-dr/i-2-381-VM/id_ed25519
```

If libvirt URI escaping is required, qemu must encode the keyfile parameter in one helper function and reuse it everywhere.

## 6. Fence Clear State Machine Contract

For `provisioning_backend=cloud-managed`, qemu reconcile must guard this state:

```text
mode=dr
active_side=secondary
protection_state=failed_over
transport_state=failed_over
fencing_state=clear
```

In that state, qemu must:

- preserve the failed-over secondary-active state.
- emit an event such as `cloud_managed_failback_ready`.
- avoid inventory-driven forward auto-rearm.
- avoid incrementing `rearm_count`.
- avoid setting `degraded`, `rearming`, or `rearm_exhausted`.

Cloud must:

- display this as a recovery-ready state, not as a protection error.
- enable the appropriate failback or reprotect action.
- keep Cloud-managed VM lifecycle orchestration in Cloud.
- request explicit qemu data-plane actions only when the operator or Cloud reconciler starts recovery.

This mirrors the HA cloud-managed rule: after manual fence release, failback readiness is an operator/Cloud workflow state, not a qemu automatic rearm failure condition.

## 7. Current-Mold And Remote-Mold Requirements

### Current Mold DR

Current-Mold DR must follow the same state machine:

- Cloud owns the local replica VM lifecycle.
- qemu does not auto-rearm after fence clear while the secondary side is active.
- qemu uses an explicit SSH/libvirt connection model when the target host requires non-default SSH settings.

### Remote Mold DR

Remote-Mold DR must additionally:

- continue to keep remote Mold API key and secret as transient request inputs.
- persist only sanitized remote replica metadata.
- use the FTCTL-generated SSH key path for source-host to remote-host execution.
- query or project remote VM state through Cloud, not through UI direct host access.

The same public `getFtctlProtection` response should be valid for both paths.

## 8. Implementation Plan

### 8.1 qemu FTCTL

1. Extend the qemu profile schema with explicit remote SSH fields and optional keyfile.
2. Add one helper to build remote SSH command options.
3. Add one helper to build qemu+ssh libvirt URIs with keyfile when present.
4. Replace ad hoc remote SSH and `virsh -c` construction with those helpers.
5. Update `preflight-remote`, remote-NBD prepare/cleanup, inventory checks, failover checks, failback checks, and release cleanup to use the same connection contract.
6. Add the Cloud-managed DR fence-clear guard in reconcile.
7. Ensure `rearm_count` is not incremented for Cloud-managed failed-over secondary-active fence-clear states.
8. Ensure `rearm_exhausted` does not overwrite the recovery-ready state.

### 8.2 Cloud

1. When automatic DR SSH key setup is enabled, capture the qemu-returned key path as runtime connection metadata.
2. Pass the key path into qemu profile creation/update without storing private key content.
3. For both current-Mold and remote-Mold DR, persist only non-secret connection metadata required for display and command reconstruction.
4. Treat `failed_over / failed_over / secondary / clear` as failback-ready for Cloud-managed DR.
5. Do not classify the state as degraded solely because the primary libvirt domain is absent after failover.
6. Keep remote Mold API credentials transient.
7. Ensure UI action gating enables the next recovery action from the recovery-ready state.

## 9. Recovery Handling For Existing Failed Runs

For already affected protections such as row `86`, do not rely on manual DB edits as the normal operator path.

After the code fix, recovery should be one of:

- a Cloud API action that resynchronizes the qemu profile with the key-bound connection contract and resets the false `rearm_exhausted` state to failback-ready, or
- a forced cleanup followed by a clean registration when state repair is not safe.

Manual intervention may be used only as test-lab recovery evidence and must be documented separately.

## 10. Validation Plan

Unit and component checks:

- qemu profile parser accepts the new SSH key path field.
- qemu libvirt URI builder appends `keyfile` when present.
- qemu SSH helper always uses `BatchMode`, explicit port, and explicit identity.
- Cloud registration never stores private key content.
- Cloud registration for current-Mold DR still works without remote Mold credentials.
- Cloud registration for remote-Mold DR keeps remote Mold credentials transient.

Runtime checks:

- source host can run remote `ssh` using the generated FTCTL key.
- source host can run `virsh -c qemu+ssh://...keyfile=... domstate <remote-vm>`.
- remote-NBD prepare and cleanup use the same key.
- after fence clear, state remains `failed_over / failed_over / secondary / clear` or an explicit failback-ready equivalent.
- `rearm_count` does not increase while waiting for Cloud-managed failback/reprotect.
- UI shows failback/recovery action availability instead of degraded/error.

End-to-end DR-WIN retest:

1. Register protection with target network and automatic SSH setup.
2. Confirm replication reaches 100 percent.
3. Stop or lose the primary side according to the manual-fence test.
4. Confirm fence.
5. Verify remote Mold starts the replica VM.
6. Clear fence.
7. Verify no `degraded`, `rearm_pending`, or `rearm_exhausted` transition occurs.
8. Start failback or reprotect through Cloud-managed recovery flow.

## 11. Non-Goals

- Do not move private SSH keys into Cloud DB.
- Do not let qemu create Cloud-managed replica VMs or volumes.
- Do not let qemu start or stop Cloud-managed replica VMs directly.
- Do not make remote Mold one-time UI credentials durable automation credentials.
- Do not change the validated HA manual-block behavior except to share the same Cloud-managed fence-clear rearm guard where applicable.
