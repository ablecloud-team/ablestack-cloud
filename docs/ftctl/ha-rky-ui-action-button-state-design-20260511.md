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

# HA-RKY Fault Protection Action Button State Design - 2026-05-11

## Background

The Fault Protection tab previously exposed action buttons mostly by API permission and a small set of coarse state checks. This allowed invalid UI flows such as:

- Failback being enabled while the VM was not in a failed-over state.
- Failback being enabled before manual fence release.
- Protection release being enabled before the pair returned to a stable primary-protected state.
- Other lifecycle actions being clickable while another FTCTL action or blocking refresh was already in progress.

The UI must guide the operator through the same lifecycle that FTCTL actually supports, while still preserving the ownership rule: Cloud UI reads Cloud API data and sends Cloud API commands only. It must not call host libvirt or the FTCTL engine directly.

## Source Data

The button state is derived only from values already returned through Cloud APIs:

- `protectionstate`
- `transportstate`
- `activeside`
- `adminstate`
- `fencingstate`
- primary VM state
- secondary VM state

FTCTL engine events and block-copy progress still come from qemu FTCTL `events.log` through the Cloud backend event API path. The UI does not invent FTCTL state and does not directly refresh host runtime state.

## Base Gate

Every action button is disabled unless all base conditions are satisfied:

- The corresponding API exists in the current account permission set.
- The current resource is an admin-visible KVM VM.
- The view is not a standby-only protection view.
- Active FTCTL protection is configured and enabled.
- The VM is not in an unsafe Cloud state such as `Destroyed`, `Expunging`, `Error`, or offline host control state.
- A blocking refresh or another FTCTL action is not already in progress.

The same base gate is checked again before calling the API, so stale clicks cannot bypass a disabled button state.

## Action-Specific Gates

| Action | Enabled only when |
| --- | --- |
| Pause | `active_side=primary`, `protection_state=protected|colo_running`, `transport_state=mirroring|replicating`, `fencing_state=clear`, and `admin_state=active|running|empty` |
| Resume | `admin_state=paused` |
| Failover | stable primary-protected state, clear fencing, and active admin state |
| Fence confirmation | `active_side=primary`, `protection_state=failing_over`, `transport_state=mirroring|failed_over`, `fencing_state=required|failed|manual-required`, and primary VM state is `Stopped` |
| Fence release | `active_side=secondary`, `protection_state=failed_over`, `transport_state=failed_over`, and `fencing_state=manual-fenced|fenced` |
| Failback | `active_side=secondary`, `protection_state=failed_over`, `transport_state=failed_over`, `fencing_state=clear`, and active admin state |
| Protection release | stable primary-protected state, active admin state, clear fencing, and primary VM state is `Running` |

## HA-RKY Manual-Fence Flow

The expected HA-RKY manual-fence operator flow becomes:

1. Protection reaches `protected / mirroring / primary / clear`.
2. Failover is enabled.
3. Failover requests manual fencing and moves to `failing_over / mirroring / primary / required`.
4. After the primary VM is stopped, Fence confirmation is enabled.
5. Fence confirmation completes failover and reaches `failed_over / failed_over / secondary / manual-fenced`.
6. Fence release is enabled.
7. After fence release sets `fencing_state=clear`, Failback is enabled.
8. Failback returns the pair to `protected / mirroring / primary / clear`.
9. Protection release is enabled only after the primary VM is Running again.

## UI Behavior

Disabled buttons expose a short reason through the button title. The click handler also checks the same reason before sending the API request and displays a warning if the action is no longer valid.

This is intentionally a UI safety gate, not the final authority. Cloud backend and qemu FTCTL must still reject invalid transitions when commands are called directly or when state changes between UI refreshes. For Cloud-managed automatic HA/DR, the Cloud backend is also the automatic fencing and VM lifecycle orchestration authority; qemu FTCTL remains the replication/data-plane executor.

## Protection Release Modal Readability

The protection release modal must make the normal release and forced release paths visually clear in both light and dark themes.

- The modal receives a dedicated wrapper class so release-specific alert and checkbox styles do not affect the rest of the Fault Protection tab.
- Warning and error alerts in dark mode use stronger foreground contrast, visible borders, and readable icon colors. Yellow or red alert backgrounds must not pair with low-contrast white text.
- Forced release checkboxes are laid out as a single row with the checkbox immediately beside its label. Long acknowledgement text wraps from the label column, not below the checkbox.
- Spacing follows the action order: normal release description, normal-release unavailable warning, forced-release opt-in, forced-release warning, forced-release acknowledgement.
- The modal behavior remains unchanged: normal release is allowed only in a valid stable state, forced release requires explicit user selection and acknowledgement, and the backend remains the final state-transition authority.
