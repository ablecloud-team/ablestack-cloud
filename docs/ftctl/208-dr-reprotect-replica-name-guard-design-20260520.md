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

# 208. DR Re-protection Replica Name Guard Design

Date: 2026-05-20

## 1. Purpose

After a remote-Mold DR replica is adopted as the operating VM, that VM can be protected again toward a recovered or newly built target Mold.

The target Mold can still contain the original primary VM name in `Expunging`, removed, or otherwise unusable state. Re-protection must therefore not blindly create a new Cloud-managed replica with the old primary name. The UI and backend must treat the replica VM name as a target-site resource name that is generated, displayed, optionally edited, and finally validated against the selected target Mold.

## 2. Scope

This design applies to DR Cloud-managed protection for both peer-site types:

- current Mold target site (`local-mold`)
- remote Mold target site (`remote-mold`)

The rule also applies when the source VM itself is an adopted DR replica whose display name ends with `-standby`.

## 3. Principles

- Cloud owns VM, volume, NIC, network, storage, host placement, and lifecycle API calls.
- qemu FTCTL owns only data-plane replication and runtime action execution after Cloud has prepared the target resources.
- The operator must not be asked to rename an existing VM just to make DR re-protection possible.
- The protection dialog must show the target replica VM name before submission.
- Automatic naming is the default. Manual editing is allowed only through an explicit UI opt-in.
- Backend validation is authoritative. UI suggestions are convenience and clarity, not the final safety boundary.
- HA behavior and existing Cloud-managed HA provisioning must not regress.

## 4. UI Contract

The Protection Configuration dialog shows a secondary VM name section for HA/DR.

Default behavior:

- HA: `<source-name>-standby`
- DR: `<base-source-name>-replica[-target-site]`

`base-source-name` is the source display/name with terminal role suffixes such as `-standby` or `-replica` removed. This prevents an adopted VM such as `app-standby` from generating `app-standby-standby` or from falling back to the original old primary name `app`.

For DR, the target-site suffix is derived from the selected peer host address when possible. For example, host `10.10.22.1` yields `app-replica-22`.

The name input is disabled by default. The operator can enable `Edit secondary VM name` only when an explicit site naming policy is needed. When editing is disabled again, the UI restores the automatic suggestion.

The UI must not instruct the user to rename or delete an existing VM. If the backend detects a final conflict, it either selects the next safe candidate or returns a clear provisioning error.

## 5. Backend Contract

`registerFtctlProtection` uses the UI-provided secondary VM name when present. If it is omitted, the backend applies the same DR-safe fallback rule:

```text
DR: <base-source-name>-replica[-target-site]
HA: <base-source-name>-standby
```

For remote-Mold DR, `prepareFtctlDrReplicaResources` performs the final target-Mold name guard:

1. Check the requested name in the target zone.
2. Reuse it only when the existing VM is an active FTCTL remote replica for the same source VM UUID.
3. If the name is occupied by any other VM, including old primary or expunging state, select a safe candidate:

```text
<requested-or-base>-replica
<requested-or-base>-replica-2
<requested-or-base>-replica-3
...
```

4. Use the selected name consistently for:
   - replica VM name
   - replica VM display name
   - root volume name
   - data volume names
   - response payload returned to the source Mold

The source Mold persists the actual returned replica VM identity and sends that identity to qemu FTCTL in the profile.

## 6. Failure Handling

If no safe name can be selected, the backend returns a provisioning failure before qemu FTCTL profile creation. In that case:

- no `ftctl_protection` row should be treated as active;
- no qemu FTCTL profile should be created;
- `ftctl.provisioning.state=ProvisioningFailed` and `ftctl.last.error` must explain the name/resource provisioning failure;
- UI must surface the async job failure rather than implying that synchronization started.

For volume-create failures during the same DR re-protection path, including zero IOPS values from an adopted replica source VM, use the common IOPS and async failure rules in `209-dr-reprotect-iops-and-async-failure-design-20260520.md`.

## 7. Verification

Validate with an adopted DR replica:

1. Adopt the replica as the production VM on the replica Mold.
2. Re-protect it toward the original/recovered Mold while the old primary name still exists or is expunging.
3. Confirm the UI suggests `<base>-replica-<site>` and keeps the input read-only unless edit is enabled.
4. Confirm target Mold creates a replica VM with the selected safe name, not the stale old primary name.
5. Confirm source Mold stores the returned replica VM identity.
6. Confirm qemu FTCTL profile exists only after Cloud-managed target resource preparation succeeds.
7. Confirm block copy starts normally after successful registration.
