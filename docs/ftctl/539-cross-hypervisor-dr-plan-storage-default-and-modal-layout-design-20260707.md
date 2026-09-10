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

# Cross Hypervisor DR Plan Storage Default And Modal Layout Design

## 1. Purpose

This design fixes two DR Plan creation UX problems without changing the DR
execution model:

1. The dialog asks for a top-level target storage and also asks for target
   storage per disk. The two fields look like duplicate required inputs.
2. The disk mapping row can shrink inside the modal until the disk offering or
   storage select becomes hard to read or use.

The target state is:

- Per-disk target storage is the authoritative disk placement.
- The top-level storage field is only a default applied to disk rows, not a
  separately required execution value.
- The DR Plan dialog follows the shared filesystem creation dialog pattern from
  `origin/codex/europa-storage-service:ui/src/views/storage/CreateSharedFS.vue`:
  wide modal, fixed header/footer, scrollable content, left review summary, and
  right collapsible sections.

## 2. Current Code Findings

### 2.1 UI duplicate storage input

Current source:

- `ui/src/views/infra/dr/DrPlanList.vue`
  - top-level storage: `createForm.targetstorageref`
  - disk-level storage: `disk.targetStorageRef`
  - `applyDefaultGuidedSelections()` auto-selects a single
    `targetStorageOptions` item into `targetstorageref`
  - `rebuildDiskMappingRows()` copies `createForm.targetstorageref` into new
    disk rows
  - `validatePlanForm()` requires both `targetstorageref` and each
    `disk.targetStorageRef` for KVM targets
  - `buildDiskMappingsJson()` serializes disk-level `targetStorageRef`
  - guided submit currently sends both `targetstorageref` and
    `diskmappingsjson`

The current backend is already close to the desired contract because
`DrPlanTargetPlacementResolverImpl.resolveDisks()` prefers per-disk storage and
only falls back to placement-level storage.

### 2.2 Disk mapping row layout

Current source:

- `ui/src/style/cross-dr.less`
  - `.cross-dr-form-layout` is fixed at `552px`
  - `.cross-dr-disk-mapping-row` is one column by default
  - at `min-width: 720px`, the row becomes only two columns
  - source disk summary spans all columns

The row contains four logical pieces of information:

1. source disk summary
2. target disk name
3. target disk offering
4. target disk storage

Two columns inside a 552px modal cannot reliably show the two selects. Long
storage names and meta labels such as `RBD` force the visible selection text to
shrink.

### 2.3 Shared filesystem dialog pattern

Reference branch:

- `origin/codex/europa-storage-service:ui/src/views/storage/CreateSharedFS.vue`

Relevant pattern:

- `.sharedfs-create-dialog`
  - `width: min(92vw, 1120px)`
  - `max-height: calc(100vh - 96px)`
  - `overflow: hidden`
- `.sharedfs-create-layout`
  - `grid-template-columns: minmax(240px, 300px) minmax(0, 1fr)`
  - left summary and right config panels both scroll independently
- `.sharedfs-create-summary`
  - sticky review summary
- `.sharedfs-create-sections`
  - `a-collapse` sections for grouped input
- dark-mode rules for disabled text, alert text, and form controls

DR Plan should reuse this structure rather than adding more fields to the
current narrow single-column form.

## 3. UI Design

### 3.1 Component structure

Keep `DrFormModal.vue` as the common shell for DR dialogs. Add plan-specific
layout classes inside `DrPlanList.vue`.

Recommended template shape:

```vue
<dr-form-modal
  :width="planModalWidth"
  class="cross-dr-plan-modal"
  ...>
  <div class="cross-dr-plan-create-dialog">
    <a-alert
      v-if="inventoryBlockingReasons.length"
      class="cross-dr-plan-section-alert"
      type="warning"
      showIcon
      :message="inventoryBlockingReasons.join(', ')" />

    <div class="cross-dr-plan-create-layout">
      <aside class="cross-dr-plan-summary">
        <section class="cross-dr-plan-summary-panel">
          <!-- name, direction, source site, target site, workload, target placement, disk count -->
        </section>
      </aside>

      <main class="cross-dr-plan-config">
        <a-collapse
          class="cross-dr-plan-sections"
          :defaultActiveKey="['basic', 'sites', 'workload', 'target', 'disks']">
          <a-collapse-panel key="basic" :header="$t('label.dr.section.basic')" />
          <a-collapse-panel key="sites" :header="$t('label.dr.section.site.mapping')" />
          <a-collapse-panel key="workload" :header="$t('label.dr.section.protection.target')" />
          <a-collapse-panel key="target" :header="$t('label.dr.section.target.placement')" />
          <a-collapse-panel key="disks" :header="$t('label.dr.disk.mapping')" />
          <a-collapse-panel key="policy" :header="$t('label.dr.section.policy')" />
          <a-collapse-panel key="advanced" :header="$t('label.dr.section.advanced')" />
        </a-collapse>
      </main>
    </div>
  </div>
</dr-form-modal>
```

`DrFormModal.vue` already keeps the Ant Design modal body scroll isolated via
`.cross-dr-modal__scroll`. The DR Plan body should add a second-level layout
that does not create horizontal overflow.

### 3.2 Target storage semantics

Rename the top-level field in the UI from "Target storage" to "Default target
storage".

Form state can keep the existing API property name for compatibility:

```js
createForm: {
  targetstorageref: '', // default target storage only
  diskmappingsjson: '',
  ...
}
```

Add a computed helper:

```js
hasDiskLevelStorageAuthority () {
  return this.requiresDiskMapping && this.diskMappingRows.length > 0
}
```

Change `validatePlanForm()`:

```js
if (this.directionUsesKvmTarget) {
  if (!this.createForm.targetworkerhostid) {
    return this.$t('message.dr.plan.validation.target.worker')
  }
  if (!this.hasDiskLevelStorageAuthority && !this.createForm.targetstorageref) {
    return this.$t('message.dr.plan.validation.target.storage')
  }
  if (this.requiresDiskMapping && this.diskMappingRows.length === 0) {
    return this.$t('message.dr.plan.validation.disk.mapping')
  }
  const incompleteDisk = this.diskMappingRows.find(row =>
    !row.targetDiskName || !row.targetDiskOfferingId || !row.targetStorageRef)
  if (incompleteDisk) {
    return this.$t('message.dr.plan.validation.disk.mapping')
  }
}
```

The top-level default is not required when every disk row has storage. It is
required only for legacy or non-disk-row KVM target plans.

### 3.3 Default storage apply behavior

Add one explicit apply action near the default storage selector:

```vue
<a-button
  v-if="requiresDiskMapping && createForm.targetstorageref"
  size="small"
  @click="applyDefaultStorageToDiskRows">
  {{ $t('label.dr.apply.to.all.disks') }}
</a-button>
```

Method:

```js
applyDefaultStorageToDiskRows () {
  const storageRef = this.createForm.targetstorageref
  if (!storageRef) return
  this.diskMappingRows = this.diskMappingRows.map(row => ({
    ...row,
    targetStorageRef: storageRef
  }))
  this.createForm.diskmappingsjson = this.buildDiskMappingsJson()
}
```

`rebuildDiskMappingRows()` may still initialize new rows from the default
storage, but changing the default later must not silently override operator
per-disk choices. Use the explicit "apply to all disks" action for that.

### 3.4 Submit payload

`buildPlanPayload()` keeps compact guided payloads:

```js
if (!this.createForm.expertjson) {
  this.createForm.diskmappingsjson = this.buildDiskMappingsJson()
}
```

Payload rule:

- `diskmappingsjson` is authoritative for disk storage when present.
- `targetstorageref` is retained as a default/fallback for legacy and
  non-disk-row flows.
- `targetstorageref` must not override any disk row that has
  `targetStorageRef`.

The UI must not submit backend-owned runtime fields such as:

- `targetStoragePath`
- `targetStorageType`
- `targetStorageKrbdPath`
- `storageHostAddress`
- `target.storagePath`
- `target.krbdPath`

### 3.5 Disk mapping layout CSS

Replace the current two-column row with a stable row layout.

Recommended CSS:

```less
.cross-dr-plan-create-dialog {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  max-height: calc(100vh - 96px);
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.cross-dr-plan-create-layout {
  flex: 1 1 auto;
  display: grid;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  gap: 16px;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  min-height: 0;
  overflow: hidden;
}

.cross-dr-plan-summary,
.cross-dr-plan-config {
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
}

.cross-dr-plan-config {
  scrollbar-gutter: stable;
  padding-right: 14px;
}

.cross-dr-disk-mapping-row {
  display: grid;
  grid-template-columns:
    minmax(150px, 1.1fr)
    minmax(150px, 1fr)
    minmax(170px, 1.1fr)
    minmax(170px, 1.1fr);
  gap: 10px;
  align-items: start;
}

.cross-dr-disk-field {
  min-width: 0;
}

.cross-dr-disk-field__label {
  display: block;
  margin-bottom: 4px;
  color: var(--cross-dr-text-secondary);
  font-size: 12px;
  line-height: 18px;
}

.cross-dr-disk-mapping-row .ant-input,
.cross-dr-disk-mapping-row .ant-select {
  width: 100%;
  min-width: 0;
}

.cross-dr-disk-mapping-row .ant-select-selection-item,
.cross-dr-disk-mapping-row .ant-select-selection-placeholder {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 900px) {
  .cross-dr-plan-create-layout {
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .cross-dr-plan-summary,
  .cross-dr-plan-config {
    max-height: none;
    overflow: visible;
  }

  .cross-dr-disk-mapping-row {
    grid-template-columns: 1fr;
  }
}
```

The nested dialog width must not be `1120px` when rendered inside
`DrFormModal.vue`, because the modal shell already owns width and body padding.
The current detailed modal refinement is documented in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md#13-2026-07-07-refinement-dark-alert-and-right-gutter).

Each select option can keep the metadata, but the selected value should prefer
the storage name and show the backend type as a suffix badge or tooltip instead
of letting it consume the selected-value width.

## 4. API Design

No new API command is required.

Affected commands:

- `CreateDrPlanCmd`
- `UpdateDrPlanCmd`
- `PreviewDrPlanSpecCmd`

Parameter semantics:

| Parameter | New meaning |
| --- | --- |
| `targetstorageref` | Default/fallback target storage for KVM target plans. Not authoritative when `diskmappingsjson` has per-disk storage. |
| `diskmappingsjson` | Compact disk-level source and target placement selections. Authoritative for each disk's target storage. |

`@Parameter(length = 65535)` remains required for `diskmappingsjson`.

Validation rule:

```java
if (isKvmTarget(direction) && hasDiskMappings(diskMappingsJson)) {
    requireEveryDiskTargetStorage(diskMappingsJson);
} else if (isKvmTarget(direction)) {
    requireTargetStorageRef(targetStorageRef);
}
```

The API response should expose preview/readiness blockers at disk granularity:

- `TARGET_STORAGE_REQUIRED:<index>`
- `TARGET_STORAGE_INVALID:<index>`
- `TARGET_DISK_OFFERING_REQUIRED:<index>`
- `SOURCE_DISK_SIZE_UNRESOLVED:<index>`

## 5. Backend Design

Affected source:

- `DrPlanGuidedSpec.java`
- `DrPlanGuidedSpecBuilder.java`
- `DrPlanTargetPlacementResolver.java`
- `DrPlanTargetPlacementResolverImpl.java`
- `DrPlanReadinessValidator.java`

The backend must keep this precedence:

1. disk-level `disk.targetStorageRef`
2. disk-level `disk.target.storageRef`
3. top-level `mapping.target.storageRef`
4. guided fallback `targetstorageref`

`DrPlanGuidedSpecBuilder.buildMapping()` should still write
`mapping.target.storageRef` when the default target storage is present, but it
must not use that field to overwrite disk-level storage.

Code-level rule:

```java
String defaultStorageRef = StringUtils.trimToNull(spec.getTargetStorageRef());
JsonArray disks = parseDiskMappings(spec.getDiskMappingsJson());

for (JsonObject disk : disks) {
    JsonObject target = objectAt(disk, "target");
    String diskStorageRef = firstNonBlank(
            firstString(disk, "targetStorageRef", "storageRef"),
            firstString(target, "storageRef", "storagePoolId", "targetStorageRef"));

    String effectiveStorageRef = firstNonBlank(diskStorageRef, defaultStorageRef);
    if (StringUtils.isBlank(effectiveStorageRef)) {
        blockingReasons.add(REASON_TARGET_STORAGE_REQUIRED + ":" + index);
        continue;
    }
}
```

`DrPlanReadinessValidator` must validate the same effective storage selection as
the builder. A plan is execution-ready only when every selected source disk has
source identity, positive source size for VMware source, target disk name/ref,
target disk offering for KVM target, and backend-resolved target storage.

## 6. Agent Design

No Agent command schema change is required.

Affected behavior:

- `FtctlDrUnifiedActionAdapter` forwards the backend-generated profile to the
  KVM Agent.
- The Agent must receive `mapping.target` and `mapping.disks[]` after backend
  resolver enrichment.
- The Agent must not infer target disk storage from the old top-level
  `targetstorageref` API parameter.

Dispatch acceptance rule:

- If backend readiness has disk storage blockers, do not dispatch the Agent
  command.
- If a legacy plan reaches Agent without per-disk storage but with a valid
  backend-resolved `mapping.target.storageRef`, the Agent may forward it as a
  legacy fallback profile.

## 7. ftctl Design

No qemu/ftctl CLI option or state-file schema change is required.

Runtime contract:

- ftctl consumes the canonical profile generated by Cloud.
- For each disk, `mapping.disks[].target.storageRef` and backend-owned runtime
  fields are preferred.
- ftctl keeps the final guard:
  - missing target storage/path -> `CONFIG_INCOMPLETE`
  - invalid disk target path -> `DR_TARGET_MAPPING_INVALID`
  - unresolved disk size -> `DR_TARGET_DISK_SIZE_UNRESOLVED`

The top-level storage value is a Cloud/API guided fallback only.

## 8. DB Design

No schema change is required.

Existing columns remain:

- `dr_plan.mapping_json`
- `dr_plan.schedule_json`
- `dr_plan.policy_json`
- `dr_plan.quiesce_policy_json`

Canonical JSON ownership:

| JSON path | Owner |
| --- | --- |
| `mapping.target.storageRef` | Backend resolver default/fallback |
| `mapping.disks[].target.storageRef` | UI-selected disk value, validated and normalized by backend |
| `mapping.disks[].target.storagePath` | Backend resolver only |
| `mapping.disks[].target.storagePoolType` | Backend resolver only |
| `mapping.disks[].target.storageHostAddress` | Backend resolver only |
| `mapping.disks[].target.krbdPath` | Backend resolver only |

Legacy rows can keep top-level storage. On update or preview, backend should
rebuild the canonical JSON through `DrPlanGuidedSpecBuilder` and preserve
per-disk storage where present.

## 9. Implementation Order

1. UI semantics
   - rename top-level label/help to "Default target storage"
   - add explicit "Apply to all disks"
   - stop requiring top-level storage when all disk rows have storage
2. UI layout
   - introduce SharedFS-style plan modal layout
   - add left review summary and right collapse sections
   - rewrite disk mapping row CSS
3. API command semantics
   - document and enforce disk-level storage precedence
   - preserve `diskmappingsjson` length
4. Backend resolver
   - ensure builder/readiness use the same effective storage precedence
   - add focused unit tests for disk storage override and default fallback
5. Agent/ftctl smoke
   - verify dispatched profile contains disk-level storage
   - verify legacy fallback still fails safely if unresolved
6. DB smoke
   - create plan with two disks on different target storages
   - confirm `mapping_json.disks[]` stores distinct storage refs
   - confirm no runtime paths originate from UI payload

## 10. AS-IS / TO-BE

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| UI storage semantics | Top-level target storage and per-disk target storage are both required. | Top-level field is default storage only; per-disk storage is authoritative. |
| UI layout | 552px single-column modal with disk rows squeezed into two columns. | SharedFS-style wide modal with summary, sections, and stable disk grid. |
| UI validation | Missing top-level storage blocks submit even when every disk has storage. | Submit validates disk rows first and uses top-level storage only as fallback. |
| API | `targetstorageref` reads as the recovery target storage. | `targetstorageref` is documented as default/fallback when disk mappings exist. |
| Backend | Resolver mostly prefers disk storage but the contract is implicit. | Builder/readiness/API all share explicit disk-first precedence. |
| Agent | Receives canonical profile but storage authority is not documented. | Receives backend-resolved disk-level storage profile; no UI runtime paths. |
| ftctl | Final preflight catches unresolved storage/path. | Final guard remains, while Cloud prevents predictable mapping errors before dispatch. |
| DB | Existing `mapping_json` stores both top-level and disk values. | Same schema; canonical JSON declares default vs disk-level ownership. |

## 11. Acceptance Criteria

- DR Plan dialog does not horizontally overflow at desktop or narrow modal
  widths.
- Disk offering and storage selects remain clickable and readable with long
  labels.
- Changing "Default target storage" does not silently overwrite per-disk
  choices.
- "Apply to all disks" copies the default storage to every disk row.
- A guided KVM target plan can be submitted with blank `targetstorageref` when
  every disk row has `targetStorageRef`.
- Backend preview/create/update store per-disk storage in `mapping_json.disks[]`.
- Agent/ftctl profile contains backend-resolved disk target storage and runtime
  fields.
- No DB migration is required.

## 12. Related Dialog Standardization Detail

This document defines storage ownership and the first layout correction for the
DR Plan dialog. The more specific SharedFS dialog standardization design is
documented in
[540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md](540-cross-hypervisor-dr-plan-sharedfs-dialog-standard-design-20260707.md).

Relationship between the documents:

- Document 539 owns the storage semantics: default target storage is a fallback,
  disk-row storage is authoritative.
- Document 540 owns the create/edit dialog structure: SharedFS-style wide
  modal, left review panel, right collapsible sections, section validation
  focus, dark-mode readability, and UI-only state boundaries.
- Both documents preserve the same API/backend/Agent/ftctl/DB contracts.

## 13. Related Alert/Gutter Refinement

The storage ownership design in this document is unaffected by the
dark-mode alert and right-gutter refinement. That refinement is UI-only and is
specified in document 540.

Storage contract remains:

- top-level target storage is still only a default/fallback;
- disk-row target storage remains authoritative;
- API/backend/Agent/ftctl/DB storage semantics are unchanged.

## 14. 2026-07-07 Update: Source Disk Detail And Target Placement UX

The latest VMware source validation found that a selected source VM can return
disk list items without size or backing data. For vCenter VM `vm-4486`, the disk
list endpoint returned only `disk=2000`, while the disk detail endpoint returned
`capacity=107374182400` and the VMDK path. Therefore the DR Plan dialog must not
make the disk key look like a size, and the storage/default UX must remain
separate from source disk metadata.

### 14.1 Source Disk Rendering

The disk mapping card should show source metadata as separate fields:

| Field | Source |
| --- | --- |
| Source disk label | `details.label || name || Disk N` |
| Source disk ID | `details.diskRef || value || externalid` |
| Source disk size | `details.sizeBytes || details.capacityBytes || details.capacity` |
| Source disk path | `details.path || details.vmdkFile || description` |

Recommended layout:

```text
Source disk
  Label: Hard disk 1
  ID: 2000
  Size: 100 GiB
  Path: [datastore] vm/vm.vmdk

Target disk
  Name: ...
  Disk offering: ...
  Storage: ...
```

If source size is unresolved, the section must show one blocking alert at the
top and the row must mark the size as `Unresolved`. The text `2000` must be
shown only as an ID.

### 14.2 Default Storage Placement

The top-level default storage field should move closer to the disk mapping
section and be labelled as a bulk-fill helper, for example:

- `Default disk storage`
- `Apply to all disks`

It must not be presented as the final target storage when disk rows exist.

Final storage precedence remains:

1. disk row `targetStorageRef`
2. disk row `target.storageRef`
3. mapping-level `target.storageRef`
4. guided `targetstorageref` fallback

### 14.3 UI Code-Level Changes

Affected file:

- `ui/src/views/infra/dr/DrPlanList.vue`

Add helpers:

```js
sourceDiskSizeBytes (disk) {
  const details = disk.detailsObject || {}
  return this.normalizeDiskSizeBytes(
    details.sizeBytes ||
    details.capacityBytes ||
    details.capacity ||
    disk.sizeBytes ||
    disk.capacityBytes ||
    disk.capacity)
}

sourceDiskPath (disk) {
  const details = disk.detailsObject || {}
  return details.path || details.vmdkFile || details.backingFile || disk.description || ''
}

hasUnresolvedSourceDiskSize () {
  if (String(this.createForm.direction || '').toUpperCase() !== 'VMWARE_TO_KVM') {
    return false
  }
  return this.diskMappingRows.some(row => !this.normalizeDiskSizeBytes(row.capacityBytes || row.sizeBytes))
}
```

`rebuildDiskMappingRows()` must use these helpers instead of reading only
`details.sizeBytes || details.capacityBytes`:

```js
capacityBytes: this.sourceDiskSizeBytes(disk),
sourcePath: this.sourceDiskPath(disk)
```

`validatePlanForm()` must block before preview/create:

```js
if (this.hasUnresolvedSourceDiskSize()) {
  return this.planValidationMessage(
    'sourceDiskSize',
    this.$t('message.dr.plan.validation.source.disk.size'))
}
```

### 14.4 CSS Code-Level Changes

Affected file:

- `ui/src/style/cross-dr.less`

The one-row four-column grid should evolve to a stable card-like grid:

```less
.cross-dr-disk-mapping-row {
  display: grid;
  grid-template-columns: minmax(190px, 0.85fr) minmax(0, 1fr);
  gap: 14px;
}

.cross-dr-disk-source {
  grid-row: span 2;
}

.cross-dr-disk-target-fields {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr);
  gap: 12px;
}
```

On narrow widths, the row collapses to one column. This prevents target storage
and disk offering selects from shrinking until their dropdown trigger becomes
hard to use.

### 14.5 AS-IS / TO-BE Update

| Layer | AS-IS | TO-BE |
| --- | --- | --- |
| UI source disk | Disk key `2000` is shown near size warning and can be mistaken for size. | Disk ID, size, and path are separate fields; unresolved size is a blocker. |
| UI target storage | Default storage and disk storage look like duplicate final inputs. | Default storage is a bulk-fill helper; disk row storage is final. |
| API | Disk list result can omit capacity/path but still reach UI as selectable disk details. | `discoverDrPlanInventory` returns detail-enriched disk options or blockers. |
| Backend | VMware disk list response is treated as enough inventory. | Backend calls disk detail endpoint and normalizes `capacityBytes`/`sizeBytes`. |
| Agent | Could receive unsafe legacy profile if backend accepts unresolved disk metadata. | Predictable source disk metadata failures are blocked before dispatch. |
| ftctl | Final guard detects unresolved disk size at runtime. | Final guard remains, but Cloud prevents known invalid maps earlier. |
| DB | Invalid legacy rows can carry null/zero size as failure evidence. | New executable plans store positive disk size in canonical `mapping_json`. |

## 15. Implementation Note - 2026-07-07

The first implementation pass applied the inventory and disk-row data fixes
that unblock the target placement UI:

- VMware source disk rows now consume detail-enriched source metadata from the
  backend rather than relying only on the disk list response.
- The disk mapping row now separates source disk ID, source disk path, and
  source disk size so the user can distinguish identity from capacity.
- The default target storage remains a bulk-fill helper, while each disk row's
  selected target storage remains authoritative.

No DB, Agent, or ftctl contract change is required by this UI data-display
correction.
