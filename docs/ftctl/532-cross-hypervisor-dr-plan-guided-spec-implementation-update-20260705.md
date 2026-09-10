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

# Cross Hypervisor DR Plan Guided Spec Implementation Update

Created: 2026-07-05

This document supplements `531-cross-hypervisor-dr-plan-guided-spec-design-20260705.md` with the code-level implementation contract that was applied during the guided DR Plan work.

## 1. Scope

The goal is to remove raw engine JSON from the normal DR Plan creation and edit flow. Operators should select or enter business-level DR values, and Cloud should generate the canonical JSON that the backend, Agent, and ftctl engine consume.

Expert JSON remains available only as an explicit override path.

## 2. UI Implementation

| File | Before | After |
| --- | --- | --- |
| `ui/src/views/infra/dr/DrPlanList.vue` | DR Plan modal exposed engine binding and raw JSON fields as ordinary advanced settings. | Guided mode is the default. Raw JSON is hidden behind `expertjson`. |
| `ui/src/views/infra/dr/DrPlanList.vue` | Worker host and target resources were manual text/number inputs. | Worker, target storage, and target compute use inventory options when the backend returns them. |
| `ui/src/views/infra/dr/DrPlanList.vue` | Only JSON syntax validation was available for advanced fields. | Guided mode validates typed values such as sync interval, retention count, and retry count. |
| `ui/src/api/dr.js` | No API wrapper existed for spec preview. | Adds `previewDrPlanSpec`. |
| `ui/public/locales/en.json`, `ui/public/locales/ko_KR.json` | Guided field labels, help text, placeholders, and validation messages were missing. | Adds guided DR Plan i18n keys. |

Submitted guided parameters:

- `guidedplan`
- `targetvmname`
- `targetstorageref`
- `targetcomputeref`
- `targetnetworkref`
- `targetfolderpath`
- `syncintervalseconds`
- `retentioncount`
- `consistencymode`
- `testnetworkmode`
- `failoverpoweron`
- `bandwidthlimitmbps`
- `retrycount`

## 3. API And Backend Implementation

| File | Before | After |
| --- | --- | --- |
| `CreateDrPlanCmd.java` | Accepted raw mapping/schedule/policy/quiesce JSON. | Accepts guided typed parameters and generates canonical JSON before create. |
| `UpdateDrPlanCmd.java` | Updated raw JSON directly. | Rebuilds canonical JSON from existing immutable plan context plus submitted guided values. |
| `PreviewDrPlanSpecCmd.java` | Not present. | Adds synchronous preview API `previewDrPlanSpec`. |
| `DrPlanGuidedSpec.java` | Not present. | Represents typed guided inputs. |
| `DrPlanGeneratedSpec.java` | Not present. | Carries generated canonical JSON and warnings. |
| `DrPlanGuidedSpecBuilder.java` | Not present. | Converts typed guided values into canonical JSON. |
| `DisasterRecoveryClusterServiceImpl.java` | Did not register `previewDrPlanSpec`. | Registers `PreviewDrPlanSpecCmd`. |
| `DisasterRecoveryClusterEventTypes.java` | No preview event type. | Adds `DR.PLAN.SPEC.PREVIEW`. |

Generated schema markers:

- `DR_PLAN_GUIDED_SPEC_V1`
- `DR_SCHEDULE_V1`
- `DR_POLICY_V1`
- `DR_QUIESCE_POLICY_V1`

## 4. Inventory Implementation

| File | Before | After |
| --- | --- | --- |
| `DrPlanInventoryResult.java` | Returned source workloads only. | Adds worker host and target resource option lists. |
| `DrPlanInventoryResponse.java` | Serialized source workload options only. | Serializes worker host, target storage, target compute, target network, and target folder options. |
| `DrResponseGenerator.java` | Did not map the new inventory option lists. | Maps the new lists into API responses. |
| `DrPlanInventoryServiceImpl.java` | Did not populate guided target options. | Populates KVM worker hosts and KVM target storage pools from Cloud inventory; VMware compute defaults to the configured datacenter reference when available. |

New response lists:

- `sourceworkerhosts`
- `targetworkerhosts`
- `coordinatorworkerhosts`
- `targetstorageoptions`
- `targetcomputeoptions`
- `targetnetworkoptions`
- `targetfolderoptions`

## 5. DB Implementation

No new `dr_plan` columns are introduced. The existing JSON columns remain the persistence contract:

- `mapping_json`
- `schedule_json`
- `policy_json`
- `quiesce_policy_json`

Upgrade SQL grants the new `previewDrPlanSpec` API to the Resource Admin DR permission block.

## 6. Agent And ftctl Implementation

Cloud still sends canonical plan JSON through the existing backend, Agent, and ftctl path. The runtime contract is broadened so the generated guided aliases are accepted by ftctl.

| File | Before | After |
| --- | --- | --- |
| `lib/ftctl/dr_vmware.sh` | VMware manifest canonicalization mainly consumed legacy VMware field names such as datastore/resource-pool/folder references. | Also accepts `targetStorageRef`, `targetFolderPath`, `targetComputeRef`, and `targetNetworkRef`. |
| `bin/ablestack_vm_ftctl_selftest.sh` | VMware DR contract self-test did not verify guided aliases. | Adds assertions that guided aliases produce the expected VMware manifest fields. |

## 7. Remaining Limits

- Per-disk and per-NIC mapping tables are not yet exposed in the DR Plan modal.
- VMware datastore/resource pool/network/folder browse APIs are still limited. The UI therefore uses guided text fallback where a richer inventory list is not available.
- These limits do not change the default UX rule: normal users should not edit raw JSON unless expert mode is explicitly enabled.

## 8. 2026-07-05 Post-create Verification Update

A `VMWARE_TO_KVM` DR Plan can currently be created and listed successfully while still being incomplete for FTCTL_DR execution. The observed state is a valid persisted draft, not a runnable protection session.

### 8.1 Observed runtime gap

| Component | Current behavior | Required follow-up |
| --- | --- | --- |
| UI | Plan create/edit can save source/target site and source workload ref without disk mappings. | Treat this as a draft unless preview returns execution-ready. |
| API | `createDrPlan` stores generated JSON and returns success. | Return computed readiness and blocking reasons in `DrPlanResponse` and preview response. |
| Backend | `DrPlanGuidedSpecBuilder` generates site/target hints but not `mapping.disks[]`. | Add typed disk mapping to `DrPlanGuidedSpec` and generate canonical `disks[]`. |
| Backend | `DrPlanServiceImpl.getActionEligibility()` enables `releaseProtection` for any enabled FTCTL_DR plan with no active run. | Require runtime resources or release readiness before enabling release. |
| Orchestrator | `DrProtectionOrchestratorImpl.prepareSyncRun()` rejects missing worker and disk mapping. | Keep this hard guard, but catch the same issue earlier in preview/create/update validation. |
| Agent/ftctl | Runtime profile can only execute once workers and disk mappings are materialized. | Validate profile readiness and report `CONFIG_INCOMPLETE` instead of opaque runtime failure. |
| DB | Existing columns can hold all required data. | No migration required for the first readiness hardening pass. |

### 8.2 Code-level implementation delta

1. Add `DrPlanReadiness` and `DrPlanReadinessValidator` under `com.cloud.dr`.
2. Extend `DrPlanGuidedSpec` with `diskMappingsJson` so a guided caller can pass canonical disk mapping rows without exposing the full engine JSON contract.
3. Extend `DrPlanGuidedSpecBuilder.buildMapping()` to write canonical `disks[]`.
4. Extend `PreviewDrPlanSpecCmd` response with `executionReady`, `blockingReasons`, and `warnings`.
5. Extend `CreateDrPlanCmd` and `UpdateDrPlanCmd` with `diskmappingsjson` and `allowdraft`.
6. Update `DrPlanServiceImpl.getActionEligibility()` so:
   - `sync` requires `executionReady`.
   - `releaseProtection` requires `releaseReady`.
   - `update` and `delete` remain available for draft plans with no active run.
7. Keep `DrPlanInventoryServiceImpl` worker/target option discovery as the current guided selection source. Source disk browse remains a follow-up because the current inventory clients do not yet return per-disk rows.
8. Update `DrPlanList.vue` so existing disk mappings are preserved during guided edit, DR Plan detail shows readiness, and `startsync` is blocked until preview returns execution-ready.
9. Keep action enablement backend-authoritative through `DrPlanServiceImpl.getActionEligibility()`, so list/detail action menus stay closed for incomplete plans.
10. Update ftctl selftest to cover VMware missing disk mappings as `CONFIG_INCOMPLETE`, while keeping the existing guided alias contract-ready test.

### 8.3 Compatibility rule

Existing draft plans without disk mapping must remain editable and deletable. They must not expose sync/failover/release actions until the operator reopens the Plan, completes worker and disk mapping, and the backend preview returns `EXECUTION_READY`.

## 9. 2026-07-05 Readiness Hardening Implementation

| Component | Implemented change |
| --- | --- |
| UI | `DrPlanList.vue` now submits `allowdraft`, preserves guided `diskmappingsjson` on edit, checks `previewDrPlanSpec` before `startsync`, and displays `readinessstate` on the detail info card. |
| API | `CreateDrPlanCmd`, `UpdateDrPlanCmd`, and `PreviewDrPlanSpecCmd` accept `diskmappingsjson`; create/update enforce readiness when `startsync=true` or `allowdraft=false`. |
| Backend | `DrPlanReadinessValidator` computes `CONFIG_INCOMPLETE`, `EXECUTION_READY`, and release readiness from worker bindings, disk mappings, and runtime resources. |
| Backend | `DrPlanServiceImpl.getActionEligibility()` now requires execution readiness for `sync` and release readiness for `releaseProtection`. |
| Backend | `DrResponseGenerator`, `DrPlanResponse`, and `DrPlanSpecPreviewResponse` expose readiness state, execution readiness, release readiness, and blocking reasons. |
| Agent/ftctl | `lib/ftctl/dr_vmware.sh` reports missing VMware disk mappings as `CONFIG_INCOMPLETE` with `DR_TARGET_MAPPING_INVALID`, not as a silent wait state. |
| Test | `ablestack_vm_ftctl_selftest.sh` adds `selftest_case_dr_vmware_missing_disk_map_config_incomplete`. |
| DB | No schema change was required; readiness is calculated from existing plan JSON and runtime tables. |

## 10. 2026-07-06 VMware To ABLESTACK Guided Target Inventory Follow-up

### 10.1 Verification finding

The current implementation is good enough to persist a draft DR Plan, but it is not yet good enough for a normal operator to complete `VMWARE_TO_KVM` protection without knowing internal references.

Observed behavior:

| Area | Current behavior | Required behavior |
| --- | --- | --- |
| Target site Zone | A KVM target site with `dr_site.zone_id = NULL` produces empty worker/storage options. | Inventory must return `TARGET_SITE_ZONE_REQUIRED`, and site edit must store a local Zone before Plan execution readiness. |
| Target worker | UI falls back to manual input when no worker options are returned. | Guided mode must show a select or a blocking message only. |
| Target storage | UI falls back to manual input when no storage options are returned. | Guided mode must show a select or a blocking message only. |
| Target compute | KVM target compute option currently reuses worker hosts. | KVM target compute must be service offering selection; target host remains worker selection. |
| Target network | UI is a manual text input. | Target network must be discovered from Cloud network inventory and selected. |
| Source disk mapping | VMware VM list does not include disk rows. | Source VM selection must trigger vCenter disk/NIC detail lookup and build disk mapping rows. |
| Readiness | Missing disk mapping and coordinator worker are detected, but target placement is not fully explained. | Readiness must include target Zone, worker, storage, service offering, network, and disk offering blockers. |

### 10.2 Code-level delta to implement next

| Component | Files | Required change |
| --- | --- | --- |
| UI | `ui/src/views/infra/dr/DrPlanList.vue` | Remove guided-mode `<a-input>` fallback for target storage/compute/network/worker fields; show select or blocking alert. |
| UI | `DrPlanList.vue` or new `DrPlanDiskMappingTable.vue` | Add source disk to target disk mapping table with target disk offering and storage selects. |
| UI | `DrPlanList.vue` | Add `targetzoneid`, `targetserviceofferingid`, `targetnetworkids`, `targetdiskmappings`, `targetDiskOfferingOptions`, `targetServiceOfferingOptions`, `sourceDiskOptions`, `sourceNicOptions`. |
| UI | `DrPlanList.vue` | Change `applyDefaultGuidedSelections()` to auto-select only exactly one selectable option and never auto-pick among multiple service offerings/networks/storage pools. |
| API | `DiscoverDrPlanInventoryCmd` | Add `sourceexternalref`, `sourcevmid`, `includeplacement`, `includedisks`, `includenetworks`. |
| API response | `DrPlanInventoryResponse`, `DrResponseGenerator` | Add `targetzone`, `targetserviceofferings`, `targetdiskofferings`, `sourcedisks`, `sourcenics`, `blockingreasons`, `warnings`. |
| Backend inventory | `DrPlanInventoryServiceImpl` | Resolve target Zone first; return `TARGET_SITE_ZONE_REQUIRED` when KVM site has no Zone; list target workers, storage, service offerings, disk offerings, and networks by Zone. |
| Backend VMware | `DrVmwareInventoryClient` | Add VM detail, disk, and NIC lookup using `/rest/vcenter/vm/{vm}/hardware/*` and `/api/vcenter/vm/{vm}/hardware/*` fallback. |
| Backend spec | `DrPlanGuidedSpecBuilder` | Store target placement under `mapping_json.target` and disk rows under `mapping_json.disks[]`. |
| Backend readiness | `DrPlanReadinessValidator` | Add `TARGET_SITE_ZONE_REQUIRED`, `TARGET_WORKER_REQUIRED`, `TARGET_STORAGE_REQUIRED`, `TARGET_SERVICE_OFFERING_REQUIRED`, `TARGET_NETWORK_REQUIRED`, `SOURCE_DISK_INVENTORY_REQUIRED`, `TARGET_DISK_OFFERING_REQUIRED`. |
| Agent/ftctl | `FtctlDrUnifiedActionAdapter`, ftctl DR scripts | Treat incomplete target placement as `CONFIG_INCOMPLETE` before starting runtime work. |
| DB | `dr_site`, `dr_plan.mapping_json` | No new column for the first pass. Store target Zone on site and canonical target placement in existing mapping JSON. |

### 10.3 Compatibility rule

Existing draft plans remain editable and deletable. They must not expose `sync`, `failover`, or runtime release actions until target placement and disk mapping are completed and backend preview returns `EXECUTION_READY`.

### 10.4 Smoke criteria

| Test | Expected result |
| --- | --- |
| KVM target site without Zone | `discoverDrPlanInventory` returns `TARGET_SITE_ZONE_REQUIRED`; UI shows blocking alert. |
| KVM target site with one Zone and one candidate per field | UI auto-selects single candidates and marks them as auto-selected. |
| KVM target site with multiple offerings/networks/storage pools | UI leaves field empty and requires explicit user selection. |
| VMware source VM selected | `sourcedisks[]` and `sourcenics[]` are populated or a source inventory blocker is shown. |
| Plan saved without disk offering/network | Plan is saved only as draft when `allowdraft=true`; execution actions remain disabled. |
| Plan saved with complete placement | `readinessstate=EXECUTION_READY`; sync action becomes available. |

## 11. 2026-07-06 Implementation Update - VMware To ABLESTACK Target Selection

This update closes the gap between guided UI selections and the runtime profile consumed by Agent/ftctl.

| Component | Before | After |
| --- | --- | --- |
| UI | Target storage/compute/network could be typed manually or saved without disk-level runtime hints. | Target storage, service offering, network, disk offering, and disk rows are selected from inventory. Disk mapping JSON includes storage `path`, `poolType`, `hostAddress`, and `krbdPath` details when available. |
| API | `discoverDrPlanInventory` returned placement candidates but storage details were too shallow for runtime path derivation. | Target storage options include Cloud pool `path`, `hostAddress`, `poolType`, and `krbdPath` in option details. |
| Backend spec | Target placement lived in legacy top-level aliases only. | `DrPlanGuidedSpecBuilder` writes canonical `mapping.target` plus `mapping.disks[]`; `FtctlDrUnifiedActionAdapter` merges `mapping.target` into `profile.target` before dispatch. |
| Backend readiness | Disk target validation did not recognize the UI-generated `targetRef` / `target.name` shape. | `DrPlanReadinessValidator` accepts `targetRef` and `target.name` as the target disk identity and still requires KVM target disk offering. |
| Agent | Profile target endpoint carried site/provider metadata but not all selected target placement data. | Agent receives a profile where `profile.target` contains the generated target placement values needed by ftctl. |
| ftctl | `dr_ablestack.sh` preserved source/target provider and disk paths, but not Cloud target placement metadata. | `dr_ablestack.sh` preserves target zone, worker, VM name, storage, service offering, networks, disk storage, disk offering, and derived target path in disk map/manifest. |
| ftctl validation | Missing ABLESTACK target fields could fail later during target preparation. | Missing target zone/storage/service offering/network, disk target name/path/storage/offering is reported as `CONFIG_INCOMPLETE` with `DR_TARGET_MAPPING_INVALID:*`. |
| DB | No new column was required. | Existing `dr_site` Zone fields and `dr_plan.mapping_json` remain the persistence contract. |

Runtime path rule:

- The UI never asks the user to type an engine path.
- The UI stores the selected storage pool reference and includes storage pool details returned by the backend.
- ftctl derives `targetPath` only when storage details are sufficient:
  - RBD/KRBD: `krbdPath + target disk name`
  - file-like path: `storage path + target disk name + .qcow2`
  - explicit RBD spec path: `rbd:<pool> + target disk name`
- If a path cannot be derived, ftctl records `DISK_TARGET_PATH_REQUIRED:<index>` and does not start runtime copy work.

Smoke criteria added by this update:

| Test | Expected result |
| --- | --- |
| KVM target storage selected | Saved disk mapping contains `target.storageRef` and storage details when backend inventory exposes them. |
| Complete VMware to ABLESTACK mapping | Agent profile contains `profile.target.serviceOfferingId`, `profile.target.networks[]`, and `mapping.disks[]`. |
| Storage details missing | ftctl reports `CONFIG_INCOMPLETE` and `DISK_TARGET_PATH_REQUIRED:<index>` instead of failing during copy preparation. |
| VMware source / KVM target dialog | Description and RPO fields are always visible; source/target worker warnings are shown only for directions that need those worker roles. |

## 12. 2026-07-06 Structural Follow-up - Compact Disk Mapping Payload

### 12.1 Verification finding

The implementation in section 11 works as an interim bridge, but it carries too much runtime information through the browser.

Observed defects:

| Defect | Evidence | Root cause |
| --- | --- | --- |
| Disk mapping row overflows the modal | The storage select is clipped at the right edge of the DR Plan dialog. | `.cross-dr-disk-mapping-row` uses four fixed minimum grid columns inside a narrow modal body. |
| `previewDrPlanSpec` fails with HTTP 431 | API response header says `Value greater than max allowed length 255 for param: diskMappingsJson`. | `diskmappingsjson` has no explicit `@Parameter.length`, so CloudStack applies the default 255 character limit. |
| Payload contains runtime paths | `DrPlanList.vue.buildDiskMappingsJson()` copies storage `path`, `poolType`, `hostAddress`, and `krbdPath` from inventory option details. | UI display metadata is being reused as backend execution metadata. |

### 12.2 Corrected architecture

Section 11 should be interpreted as an interim deployment state. The corrected architecture is:

1. `discoverDrPlanInventory` may return storage details for display and preview.
2. `DrPlanList.vue` must submit only compact selections.
3. `CreateDrPlanCmd`, `UpdateDrPlanCmd`, and `PreviewDrPlanSpecCmd` must accept compact JSON arrays larger than 255 characters.
4. `DrPlanGuidedSpecBuilder` must call a backend resolver to enrich the compact selections.
5. `DrPlanReadinessValidator` must use the same resolver result.
6. `FtctlDrUnifiedActionAdapter` and ftctl must consume only backend-generated canonical placement.

### 12.3 UI implementation delta

Files:

- `ui/src/views/infra/dr/DrPlanList.vue`
- `ui/src/style/cross-dr.less`
- optional later extraction: `ui/src/components/dr/DrPlanDiskMappingTable.vue`

Required changes:

| Method / selector | Required change |
| --- | --- |
| `buildDiskMappingsJson()` | Remove `targetStoragePath`, `targetStorageType`, `targetStorageKrbdPath`, `target.storagePath`, `target.storagePoolType`, `target.storageHostAddress`, and `target.krbdPath` from submitted JSON. |
| `diskRowsFromJson()` | Accept both old verbose rows and new compact rows so existing draft plans remain editable. |
| `validatePlanForm()` | Continue requiring target disk name, target storage ref, and target disk offering when KVM target is selected. Do not require runtime path fields in UI. |
| `previewGuidedSpec()` | Keep the preview call, but send compact disk mapping JSON. |
| `.cross-dr-disk-mapping-row` | Replace four-column fixed minimum grid with a responsive grid. |
| `.cross-dr-disk-mapping-row .ant-input/.ant-select` | Set `min-width: 0` and `width: 100%`. |

Compact submit shape:

```json
[
  {
    "sourceRef": "disk-2000",
    "sourcePath": "[datastore1] Rocky10/Rocky10.vmdk",
    "targetRef": "Rocky10-1-dr-disk-0",
    "targetStorageRef": "primary-storage-uuid",
    "targetDiskOfferingId": "disk-offering-uuid",
    "source": {
      "diskRef": "disk-2000",
      "label": "Disk 1",
      "vmdkPath": "[datastore1] Rocky10/Rocky10.vmdk",
      "capacityBytes": 214748364800,
      "boot": true
    },
    "target": {
      "name": "Rocky10-1-dr-disk-0",
      "storageRef": "primary-storage-uuid",
      "diskOfferingId": "disk-offering-uuid",
      "format": "qcow2"
    }
  }
]
```

Responsive style target:

```less
.cross-dr-disk-mapping-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 8px;
  width: 100%;
}

.cross-dr-disk-source {
  min-width: 0;
}

.cross-dr-disk-mapping-row .ant-input,
.cross-dr-disk-mapping-row .ant-select {
  min-width: 0;
  width: 100%;
}

@media (min-width: 720px) {
  .cross-dr-disk-mapping-row {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  }

  .cross-dr-disk-source {
    grid-column: 1 / -1;
  }
}
```

### 12.4 API implementation delta

Files:

- `plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/CreateDrPlanCmd.java`
- `plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/UpdateDrPlanCmd.java`
- `plugins/integrations/disaster-recovery/src/main/java/org/apache/cloudstack/api/command/admin/dr/PreviewDrPlanSpecCmd.java`

Required changes:

| Parameter | Length |
| --- | --- |
| `diskmappingsjson` | `65535` |
| `mappingjson` | `65535` |
| `schedulejson` | `65535` |
| `policyjson` | `65535` |
| `quiescepolicyjson` | `65535` |

Example:

```java
@Parameter(name = "diskmappingsjson", type = CommandType.STRING,
        description = "the compact guided disk mapping JSON array", length = 65535)
private String diskMappingsJson;
```

The length change is still required after compaction because multi-disk VMs can exceed 255 characters without carrying runtime paths.

### 12.5 Backend implementation delta

New classes:

- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrPlanTargetPlacementResolver.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrPlanTargetPlacementResolverImpl.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrResolvedTargetPlacement.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrResolvedDiskMapping.java`
- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/DrResolvedNetworkMapping.java`

Updated classes:

- `DrPlanGuidedSpecBuilder.java`
- `DrPlanReadinessValidator.java`
- `DrPlanInventoryServiceImpl.java`
- `DrResponseGenerator.java`

Resolver responsibilities:

| Step | Resolver behavior |
| --- | --- |
| Zone | Resolve target Zone from `targetzoneid` or the target DR Site. |
| Worker | Resolve target worker host and ensure it belongs to the target Zone. |
| Storage | Resolve target storage pool from selected ID/ref and read backend-owned `path`, `poolType`, `hostAddress`, and `krbdPath`. |
| Service offering | Resolve selected service offering and preserve the canonical UUID/id expected by ABLESTACK VM creation. |
| Network | Resolve selected network IDs and ensure they belong to the target Zone/account scope. |
| Disk offering | Resolve each selected disk offering. |
| Sanitization | Drop any runtime path fields submitted by UI before generating `mapping_json`. |
| Blocking reasons | Return the same `TARGET_*` and `DISK_*` blockers used by preview and readiness. |

`DrPlanGuidedSpecBuilder` usage:

```java
public DrPlanGeneratedSpec build(DrPlanVO plan, DrPlanGuidedSpec spec) {
    DrResolvedTargetPlacement placement = targetPlacementResolver.resolve(plan, spec);
    generated.setMappingJson(GSON.toJson(buildMapping(plan, spec, placement)));
    generated.getWarnings().addAll(placement.getWarnings());
    generated.getBlockingReasons().addAll(placement.getBlockingReasons());
    return generated;
}
```

`DrPlanReadinessValidator` usage:

```java
DrResolvedTargetPlacement placement = targetPlacementResolver.resolve(plan, specFromPlan(plan));
if (!placement.getBlockingReasons().isEmpty()) {
    return DrPlanReadiness.configIncomplete(placement.getBlockingReasons());
}
```

This prevents the preview/create/update path and action eligibility path from drifting.

### 12.6 Agent and ftctl implementation delta

Files:

- `plugins/integrations/disaster-recovery/src/main/java/com/cloud/dr/adapter/ftctl/FtctlDrUnifiedActionAdapter.java`
- `lib/ftctl/dr_ablestack.sh`

Required behavior:

| Layer | Required behavior |
| --- | --- |
| Adapter | Forward `mapping.target` and `mapping.disks[]` after backend resolver enrichment. |
| Adapter | Do not add or trust runtime path fields from API parameters. |
| ftctl | Keep `CONFIG_INCOMPLETE` preflight for missing target path, offering, storage, and network. |
| ftctl | Treat `target.storagePath` / `target.krbdPath` as backend-generated canonical data. |

No qemu-side schema change is required if Cloud writes the same canonical fields already consumed by `dr_ablestack.sh`.

### 12.7 DB implementation delta

No DB migration is required for this structural fix.

The storage ownership changes only at the data-generation layer:

| Stored field | Owner |
| --- | --- |
| `dr_site.zone_id` | DR Site UI/API |
| `dr_plan.mapping_json.target.*` | Backend resolver |
| `dr_plan.mapping_json.disks[].source.*` | UI-selected source disk identity, normalized by backend |
| `dr_plan.mapping_json.disks[].target.storageRef` | UI-selected storage ref, validated by backend |
| `dr_plan.mapping_json.disks[].target.storagePath/krbdPath` | Backend resolver only |

Legacy draft plans containing verbose UI-provided storage fields remain readable, but update/preview should rewrite or ignore those fields through the resolver.

### 12.8 Test and smoke plan

| Test level | Case | Expected result |
| --- | --- | --- |
| UI unit/manual | DR Plan modal with one disk and long storage label | No horizontal clipping; select controls stay inside modal body. |
| UI unit/manual | Submit compact disk mappings | Network request does not contain `targetStoragePath`, `krbdPath`, or `storageHostAddress`. |
| API smoke | `previewDrPlanSpec` with multi-disk compact JSON | No 431; preview returns readiness or blocking reasons. |
| Backend unit | UI-injected `target.storagePath=/tmp/evil` | Builder drops it and stores backend-resolved path only. |
| Backend unit | Invalid target storage ref | Preview/readiness returns `TARGET_STORAGE_REQUIRED` or `TARGET_STORAGE_INVALID`. |
| Backend unit | Missing storage path after backend resolution | Generated plan is not execution-ready. |
| ftctl selftest | Backend canonical mapping without derivable path | `CONFIG_INCOMPLETE`, `DISK_TARGET_PATH_REQUIRED:<index>`. |
| End-to-end smoke | VMware source to ABLESTACK target plan create with `startsync=true` | `previewDrPlanSpec` passes, `createDrPlan` is accepted, sync action follows async backend path. |

### 12.9 AS-IS / TO-BE

| Component | AS-IS | TO-BE |
| --- | --- | --- |
| UI layout | Disk mapping row can overflow modal width. | Responsive modal-safe disk mapping layout. |
| UI payload | Sends selected IDs plus runtime storage details. | Sends selected IDs and source disk identity only. |
| API | Default 255 character limit blocks disk mappings. | Explicit 65535 length for guided JSON parameters. |
| Backend builder | Copies disk rows as submitted. | Sanitizes disk rows and enriches them through Cloud inventory. |
| Backend readiness | Validates generated JSON after the fact. | Uses the same placement resolver as JSON generation. |
| Agent | Receives mixed UI/backend placement details. | Receives backend-authoritative canonical placement. |
| ftctl | Final guard catches missing runtime paths. | Final guard remains, while Cloud catches most issues before dispatch. |
| DB | Existing JSON columns hold generated plan. | Same DB contract; stronger ownership of generated runtime fields. |

## 13. 2026-07-06 Implementation Update - Resolver-backed Compact Payload

This update implements the structural fix described in section 12.

| Component | Implemented change |
| --- | --- |
| UI | `DrPlanList.vue.buildDiskMappingsJson()` now submits compact disk rows only. It keeps source disk identity, target disk name, target storage ref, and target disk offering id, but no longer submits runtime storage path, pool type, storage host address, or krbd path. |
| UI | `cross-dr.less` changes `.cross-dr-disk-mapping-row` to a modal-safe responsive grid and forces child inputs/selects to `min-width: 0; width: 100%`. |
| API | `CreateDrPlanCmd`, `UpdateDrPlanCmd`, and `PreviewDrPlanSpecCmd` declare `length = 65535` for guided JSON parameters that can exceed the CloudStack default 255 character limit. |
| API | The three command classes now use the Spring-managed `DrPlanGuidedSpecBuilder` instead of constructing a detached builder, so backend resolver injection is active in create, update, and preview paths. |
| Backend | `DrPlanTargetPlacementResolverImpl` resolves KVM target Zone, worker host, storage pool, service offering, network, and per-disk disk offering from Cloud DB inventory. |
| Backend | `DrPlanGuidedSpecBuilder` sanitizes caller disk rows before generating `mapping_json`, and when the resolver returns placement it writes backend-owned runtime fields under `mapping.target` and `mapping.disks[].target`. |
| Backend | `DrPlanGeneratedSpec` carries `blockingReasons`, allowing `previewDrPlanSpec` to report resolver blockers before a plan is stored or executed. |
| Backend | `DrPlanReadinessValidator` uses the same resolver for KVM target plans, so preview/create/update/action eligibility do not drift from each other. |
| Spring | `spring-disaster-recovery-context.xml` registers `drPlanTargetPlacementResolver` and `drPlanGuidedSpecBuilder`. |
| Agent/ftctl | No qemu-side schema change is required for this pass. The adapter/ftctl path continues to consume `mapping.target` and `mapping.disks[]`; missing target paths still fail as `CONFIG_INCOMPLETE`. |
| DB | No schema change is required. Existing `dr_plan.mapping_json` remains the canonical execution contract. |

Runtime ownership after this update:

| Data | Owner |
| --- | --- |
| Source disk id/path/label | UI selection normalized by backend |
| Target disk name | UI selection normalized by backend |
| Target storage/service/network/disk offering refs | UI selection validated by backend |
| Storage path, pool type, host address, krbd path | Backend resolver only |
| Execution readiness blockers | Backend resolver and readiness validator |

Updated smoke expectations:

| Case | Expected result |
| --- | --- |
| Multi-disk `previewDrPlanSpec` | HTTP 431 is not returned; command parameter length accepts compact JSON. |
| UI-injected runtime path | Builder ignores submitted runtime path fields and writes only backend-resolved values. |
| Invalid storage/network/offering ref | Preview and readiness return `TARGET_*` or `DISK_*` blocker before startSync can run. |
| DR Plan modal disk row | Row remains inside the modal body on narrow and wide viewports. |
