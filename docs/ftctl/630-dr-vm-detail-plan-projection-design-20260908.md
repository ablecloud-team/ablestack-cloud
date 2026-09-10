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

# DR VM Detail Plan Projection Design

## 1. Decision

The virtual-machine detail `DR plans` tab is a local Cloud DB read model. It
must never read source or target site state synchronously. This prohibition
applies equally to source VMs, recovery target VMs, and test failover VMs.

The tab does not own DR state and does not introduce an engine-specific state
machine. It projects existing `dr_plan`, `dr_replica`, `dr_test_session`,
`dr_plan_runtime`, and `dr_run` rows for one local VM UUID.

## 2. Non-Negotiable Read Boundary

`getDrVmProtectionView` and its service must not invoke:

- a source or target Mold API;
- a source or target Agent command;
- FTCTL status or capability commands;
- a provider adapter or projection refresh;
- a live VM, host, storage, RBD, or SharedMountPoint probe.

A UI refresh repeats the local DB query only. Missing or stale data is returned
as `UNKNOWN` with its persisted observation time. There is no live fallback.
Explicit DR actions, action preflight, and independent background reconciliation
retain their existing remote execution contracts and are outside this read path.

## 3. Relationship Identity

The local Cloud VM UUID is resolved once to `vm_instance.id`. Relationships are
then found by stable database identity:

| Role | Authoritative relationship |
| --- | --- |
| `SOURCE` | `dr_plan.source_vm_id = vm_instance.id` |
| `RECOVERY_TARGET` | `dr_replica.target_vm_id = vm_instance.id` |
| `TEST_TARGET` | active `dr_test_session.target_vm_id = vm_instance.id` |

Names, instance names, and worker hosts are never identity keys. A source and a
target are distinct by `(site_id, provider type, native VM reference)`. A VM
projected into conflicting active permanent relationships is reported as
`DR_VM_RELATION_CONFLICT`; the read path does not repair or mutate it.

Numeric VM IDs are scoped to one Cloud database and must never be resolved on
the opposite controller. A `SOURCE` relationship may resolve only its local
source VM row. `RECOVERY_TARGET` and `TEST_TARGET` relationships may resolve
only their local target VM row. The opposite endpoint is rendered from its
persisted UUID/external reference and display-name snapshot. Coincident numeric
IDs in two sites must never bind both endpoints to the same local VM.

## 4. API Contract

```text
getDrVmProtectionView virtualmachineid=<Cloud VM UUID>
```

The response contains the viewed VM, management scope, conflict state, and zero
or more `association` records. Each association contains:

- plan identity, relationship role, and current authority role;
- persisted source and target site/VM identity;
- plan, protection, freshness, and replication activity state;
- RPO target/age, last durable target time, and persisted observation time;
- target materialization/power state and latest operation summary.

Internal database IDs, credentials, worker identities, device locators, and
engine-specific paths are not exposed.

## 5. UI Contract

The tab shows a source-to-target topology with the viewed VM highlighted. Static
relationship role and dynamic authority role are displayed separately so that a
recovery target does not get renamed to a source after failover.

Stored direction enums remain engine-neutral API values, while the UI renders
provider names: `KVM_TO_KVM` is `ABLESTACK to ABLESTACK` and
`VMWARE_TO_KVM` is `VMware to ABLESTACK`. Hypervisor implementation names are
not exposed as the user-facing DR topology.

If the local controller has no persisted source display-name snapshot, the
target-side view renders `source_external_ref`. It must not search generic
`vmName`, `displayName`, or `name` keys because those keys can belong to the
local recovery target and would misidentify it as the source.

The tab is a projection and navigation surface. Its commands are fixed to the
top-right toolbar. A single relationship shows `Open plan` and `Refresh`; zero
or multiple relationships replace `Open plan` with `Open DR plan list`.
Per-association footer buttons are not rendered. Sync, test failover, failover,
failback, reprotect, and release remain on the authoritative DR Plan page.

When no relationship is present, the UI says that this management server does
not manage a DR plan for the VM. It does not claim that no remote controller has
a plan. Plan creation remains on the DR Plan list page.

## 6. Regression Gate

1. Source, target, and both sites unavailable: the endpoint still returns local
   persisted data.
2. Agent, FTCTL, provider adapter, and remote Mold invocation count is zero.
3. A source VM, recovery target VM, and test target VM resolve by database ID
   only on their owning controller, never by name or an opposite-site numeric
   ID.
4. More than one active permanent relationship is returned as a conflict.
5. The endpoint does not depend on DR Plan list pagination.
6. VMware-to-KVM, KVM-to-KVM, RBD, and SharedMountPoint paths render through the
   same response contract without an engine branch in the UI.
7. Light and dark modes preserve readable borders, labels, state pills, and
   topology emphasis at desktop and narrow widths.

## 7. Change Boundary

This change adds a DB-only Cloud API and replaces the VM tab client-side Plan
matching. Agent, FTCTL, scheduler, checkpoint, transfer, materialization,
failover, failback, and reprotect implementations are unchanged.
