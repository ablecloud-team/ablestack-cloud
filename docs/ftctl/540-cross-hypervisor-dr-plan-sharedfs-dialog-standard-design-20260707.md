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

# Cross Hypervisor DR Plan SharedFS Dialog Standard Design

## 1. Purpose

The DR Plan create/edit dialog must follow the shared filesystem creation
dialog standard from
`origin/codex/europa-storage-service:ui/src/views/storage/CreateSharedFS.vue`.

The current DR Plan dialog technically works, but it still looks and reads like
a long custom form:

- section titles are thin divider text and are hard to scan in dark mode;
- the left/right spacing does not match the shared filesystem dialog;
- most sections are always open, so the dialog feels taller than the task;
- the disk mapping area can visually dominate the form;
- the form structure remains concentrated in `DrPlanList.vue`.

This design changes the dialog structure only. It does not change DR Plan
execution semantics, async action handling, Agent dispatch, ftctl runtime
contracts, or DB schema.

## 2. Reference Standard

Reference implementation:

```text
origin/codex/europa-storage-service:ui/src/views/storage/CreateSharedFS.vue
```

Relevant standard patterns:

| Pattern | SharedFS source shape | DR Plan target |
| --- | --- | --- |
| Wide modal body | `.sharedfs-create-dialog` | `.cross-dr-plan-create-dialog` |
| Top guidance | `.section-alert` | `.cross-dr-plan-section-alert` |
| Two-panel layout | `.sharedfs-create-layout` | `.cross-dr-plan-create-layout` |
| Left review | `.sharedfs-create-summary` + `.summary-panel` | `.cross-dr-plan-summary` + `.cross-dr-plan-summary-panel` |
| Right config | `.sharedfs-create-config` | `.cross-dr-plan-config` |
| Grouped input | `.sharedfs-create-sections` with `a-collapse` | `.cross-dr-plan-sections` with `a-collapse` |
| Field help | `.field-hint` | `.cross-dr-field-hint` |
| Dark mode | `color: inherit` + rgba borders/backgrounds | Same token-neutral style |

DR Plan must not copy SharedFS business fields. It should copy the interaction
and spacing system: clear collapsible sections, consistent panel borders,
sticky review summary, and content-only scrolling.

## 3. Layer Scope

| Layer | Change required | Reason |
| --- | --- | --- |
| UI | Yes | Dialog structure, section grouping, dark-mode readability, review panel, and validation focus are UI concerns. |
| API | No parameter change | Existing create/update/preview/discover APIs already provide the data the dialog needs. |
| Backend | No runtime behavior change | Existing inventory, preview, and readiness responses remain authoritative. UI only maps them to sections. |
| Agent | No change | Agent receives commands only after backend accepts the plan/run. Dialog layout has no Agent contract. |
| ftctl | No change | ftctl consumes backend-generated profiles, not UI layout state. |
| DB | No schema change | Dialog open/closed UI state is ephemeral and must not be stored in DR plan rows. |

## 4. UI Structural Design

### 4.1 Component ownership

The current `ui/src/views/infra/dr/DrPlanList.vue` owns list rendering, detail
navigation, action dispatch, modal state, field loading, inventory discovery,
payload build, and a large create/edit template.

For this improvement, split only the dialog presentation while keeping API
orchestration in `DrPlanList.vue`.

Recommended components:

| File | Responsibility |
| --- | --- |
| `ui/src/components/dr/DrPlanFormDialog.vue` | SharedFS-style dialog body, collapse panels, section layout, emits `submit`, `cancel`, and field-change events. |
| `ui/src/components/dr/DrPlanReviewPanel.vue` | Left review summary only. Receives computed summary items and disk mapping summary. |
| `ui/src/components/dr/DrPlanDiskMappingTable.vue` | Disk mapping rows with stable controls and no horizontal overflow. |
| `ui/src/utils/dr/planDialogSections.js` | Section keys, default active keys, validation reason to section mapping. |

Minimum-change option:

- keep the template inside `DrPlanList.vue` for one iteration;
- still introduce `planDialogSections.js` and SharedFS-compatible CSS class
  names;
- extract components in the next cleanup if the template remains too large.

The structural target is component extraction, because DR Plan creation will
continue to grow as Failover/Failback guided flows mature.

### 4.2 Dialog shell

Keep `DrFormModal.vue` as the common modal shell. It already provides fixed
header/footer and `.cross-dr-modal__scroll` content scrolling.

Code-level target:

```vue
<dr-form-modal
  :visible="createModalVisible"
  :title="planFormTitle"
  :width="planModalWidth"
  :confirm-loading="createLoading"
  @cancel="closeCreateModal"
  @ok="submitPlan">
  <dr-plan-form-dialog
    v-model:active-sections="planSectionActiveKeys"
    :form="createForm"
    :mode="planFormMode"
    :direction="createForm.direction"
    :summary-items="planSummaryItems"
    :disk-summary="diskMappingSummaryText"
    :section-state="planSectionState"
    :inventory-blocking-reasons="inventoryBlockingReasons"
    :source-site-options="sourceSiteOptions"
    :target-site-options="targetSiteOptions"
    :source-vm-options="sourceVmOptions"
    :target-storage-options="targetStorageOptions"
    :target-compute-options="targetComputeOptions"
    :target-network-options="targetNetworkOptions"
    :disk-rows="diskMappingRows"
    @change-source-site="handleSourceSiteChange"
    @change-target-site="handleTargetSiteChange"
    @change-source-vm="handleSourceVmChange"
    @apply-default-storage="applyDefaultStorageToDiskRows"
    @update-disk-rows="handleDiskRowsUpdate" />
</dr-form-modal>
```

If extracted components are not implemented immediately, keep the same
properties and method boundaries in `DrPlanList.vue` so extraction is
mechanical later.

### 4.3 Active section state

Use explicit section keys. Do not depend on translated labels.

```js
export const DR_PLAN_DIALOG_SECTIONS = Object.freeze({
  BASIC: 'basic',
  SITES: 'sites',
  WORKLOAD: 'workload',
  OBJECTIVES: 'objectives',
  TARGET: 'target',
  DISKS: 'disks',
  WORKERS: 'workers',
  POLICY: 'policy',
  ADVANCED: 'advanced'
})

export const DEFAULT_DR_PLAN_ACTIVE_SECTIONS = [
  DR_PLAN_DIALOG_SECTIONS.BASIC,
  DR_PLAN_DIALOG_SECTIONS.SITES,
  DR_PLAN_DIALOG_SECTIONS.WORKLOAD,
  DR_PLAN_DIALOG_SECTIONS.TARGET,
  DR_PLAN_DIALOG_SECTIONS.DISKS
]
```

`DrPlanList.vue` state:

```js
data () {
  return {
    planSectionActiveKeys: [...DEFAULT_DR_PLAN_ACTIVE_SECTIONS]
  }
}
```

When edit modal opens, use the same default keys plus any section with current
blocking reasons.

### 4.4 Section composition

Replace `cross-dr-form-section-title` inside DR Plan create/edit with
`a-collapse-panel`.

Target shape:

```vue
<a-alert
  class="cross-dr-plan-section-alert"
  type="info"
  showIcon
  :message="$t('message.dr.plan.create.dialog.summary')" />

<div class="cross-dr-plan-create-layout">
  <aside class="cross-dr-plan-summary">
    <dr-plan-review-panel
      :items="summaryItems"
      :disk-summary="diskSummary"
      :blocking-reasons="inventoryBlockingReasons" />
  </aside>

  <main class="cross-dr-plan-config">
    <a-collapse
      v-model:activeKey="localActiveSections"
      class="cross-dr-plan-sections"
      :bordered="true">
      <a-collapse-panel key="basic" :header="$t('label.dr.section.basic')">
        <a-row :gutter="16">
          <a-col :xs="24" :md="12">...</a-col>
          <a-col :xs="24" :md="12">...</a-col>
        </a-row>
      </a-collapse-panel>

      <a-collapse-panel key="sites" :header="$t('label.dr.section.site.mapping')">
        ...
      </a-collapse-panel>

      <a-collapse-panel key="workload" :header="$t('label.dr.section.protection.target')">
        ...
      </a-collapse-panel>

      <a-collapse-panel key="objectives" :header="$t('label.dr.section.recovery.objectives')">
        ...
      </a-collapse-panel>

      <a-collapse-panel key="target" :header="$t('label.dr.section.target.placement')">
        ...
      </a-collapse-panel>

      <a-collapse-panel
        v-if="requiresDiskMapping"
        key="disks"
        :header="$t('label.dr.disk.mapping')">
        <dr-plan-disk-mapping-table ... />
      </a-collapse-panel>

      <a-collapse-panel key="workers" :header="$t('label.dr.section.worker.assignment')">
        ...
      </a-collapse-panel>

      <a-collapse-panel key="policy" :header="$t('label.dr.section.sync.policy')">
        ...
      </a-collapse-panel>

      <a-collapse-panel key="advanced" :header="$t('label.dr.section.advanced')">
        ...
      </a-collapse-panel>
    </a-collapse>
  </main>
</div>
```

The old visual divider class can remain for DR Site dialogs, but DR Plan
create/edit should not use it.

### 4.5 Field grouping

Target section grouping:

| Section | Fields |
| --- | --- |
| Basic | name, description |
| Site mapping | source site, target site, direction |
| Protection target | source VM, target VM name |
| Recovery objectives | RPO, RTO, start sync after create |
| Target placement | default target storage, target compute, target network, folder path when VMware target |
| Disk mapping | per-disk source, target disk name, disk offering, target storage |
| Worker assignment | coordinator, source worker, target worker |
| Sync policy | consistency mode, interval, retention, bandwidth/retry when shown |
| Advanced | expert mode, generated JSON preview/override |

Use two-column rows for normal fields:

```vue
<a-row :gutter="16">
  <a-col :xs="24" :md="12">
    <a-form-item name="name" required>...</a-form-item>
  </a-col>
  <a-col :xs="24" :md="12">
    <a-form-item name="description">...</a-form-item>
  </a-col>
</a-row>
```

Disk mapping remains full-width inside its panel.

### 4.6 Validation to section mapping

Local validation and backend readiness/preview errors should open the relevant
collapse panel.

```js
export const DR_PLAN_VALIDATION_SECTION_MAP = Object.freeze({
  name: 'basic',
  description: 'basic',
  sourcesiteid: 'sites',
  targetsiteid: 'sites',
  direction: 'sites',
  sourcevmid: 'workload',
  sourceexternalref: 'workload',
  targetvmname: 'workload',
  rposeconds: 'objectives',
  rtoseconds: 'objectives',
  targetstorageref: 'target',
  targetcomputeref: 'target',
  targetnetworkref: 'target',
  targetfolderpath: 'target',
  diskmappingsjson: 'disks',
  coordinatorworkerhostid: 'workers',
  sourceworkerhostid: 'workers',
  targetworkerhostid: 'workers',
  schedulejson: 'policy',
  policyjson: 'policy',
  mappingjson: 'advanced',
  quiescepolicyjson: 'advanced'
})

export const DR_PLAN_REASON_SECTION_MAP = Object.freeze({
  SOURCE_DISK_INVENTORY_REQUIRED: 'workload',
  SOURCE_DISK_SIZE_UNKNOWN: 'disks',
  TARGET_STORAGE_REQUIRED: 'target',
  TARGET_DISK_OFFERING_REQUIRED: 'disks',
  TARGET_NETWORK_REQUIRED: 'target',
  TARGET_SERVICE_OFFERING_REQUIRED: 'target',
  TARGET_WORKER_REQUIRED: 'workers',
  COORDINATOR_WORKER_REQUIRED: 'workers',
  TARGET_SITE_ZONE_REQUIRED: 'sites'
})
```

Validation handler:

```js
openSectionForValidation (fieldNameOrReason) {
  const key = DR_PLAN_VALIDATION_SECTION_MAP[fieldNameOrReason] ||
    DR_PLAN_REASON_SECTION_MAP[String(fieldNameOrReason).split(':')[0]]
  if (key && !this.planSectionActiveKeys.includes(key)) {
    this.planSectionActiveKeys = [...this.planSectionActiveKeys, key]
  }
}
```

Submit failure should call `openSectionForValidation()` before notification.

### 4.7 Review panel

The left review panel should stay concise and must not repeat every form field.

Recommended summary items:

```js
planSummaryItems () {
  return [
    { key: 'name', label: this.$t('label.name'), value: this.createForm.name },
    { key: 'direction', label: this.$t('label.dr.direction'), value: this.directionLabel },
    { key: 'sourceSite', label: this.$t('label.dr.source.site'), value: this.selectedSourceSiteName },
    { key: 'targetSite', label: this.$t('label.dr.target.site'), value: this.selectedTargetSiteName },
    { key: 'sourceVm', label: this.$t('label.dr.source.vm'), value: this.selectedSourceVmName },
    { key: 'targetVm', label: this.$t('label.dr.target.vm'), value: this.createForm.targetvmname },
    { key: 'defaultStorage', label: this.$t('label.dr.default.target.storage'), value: this.defaultStorageLabel },
    { key: 'rpo', label: this.$t('label.dr.rpo'), value: this.formatSeconds(this.createForm.rposeconds) },
    { key: 'rto', label: this.$t('label.dr.rto'), value: this.formatSeconds(this.createForm.rtoseconds) }
  ]
}
```

The panel may show badges for:

- direction;
- start sync after create;
- disk mapping completeness;
- blocking reason count.

### 4.8 CSS design

LESS in this Cloud UI build treats `min()` as a LESS function, so do not use raw
CSS `min()` in `.less` files. Use `width` plus `max-width`.

```less
.cross-dr-plan-create-dialog {
  width: 1120px;
  max-width: calc(100vw - 64px);
  max-height: calc(100vh - 96px);
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.cross-dr-plan-section-alert {
  flex: 0 0 auto;
  margin-bottom: 16px;
  color: inherit;
  border-color: rgba(127, 127, 127, 0.28);
  background: rgba(64, 158, 255, 0.12);
}

.cross-dr-plan-create-layout {
  flex: 1 1 auto;
  display: grid;
  grid-template-columns: minmax(240px, 300px) minmax(0, 1fr);
  gap: 16px;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.cross-dr-plan-summary,
.cross-dr-plan-config {
  min-height: 0;
  height: 100%;
  overflow: auto;
}

.cross-dr-plan-config {
  padding-right: 4px;
}

.cross-dr-plan-summary-panel,
.cross-dr-plan-service-section {
  color: inherit;
  border: 1px solid rgba(127, 127, 127, 0.24);
  border-radius: 6px;
  background: rgba(127, 127, 127, 0.06);
}

.cross-dr-plan-summary-panel {
  position: sticky;
  top: 0;
  padding: 16px;
}

.cross-dr-plan-sections {
  color: inherit;
  border-color: rgba(127, 127, 127, 0.24);
  background: transparent;
}

.cross-dr-plan-sections > .ant-collapse-item {
  border-color: rgba(127, 127, 127, 0.24);
}

.cross-dr-plan-sections > .ant-collapse-item > .ant-collapse-header {
  color: inherit;
  font-weight: 600;
  background: rgba(127, 127, 127, 0.06);
}

.cross-dr-plan-sections > .ant-collapse-item > .ant-collapse-content {
  color: inherit;
  border-color: rgba(127, 127, 127, 0.24);
  background: rgba(127, 127, 127, 0.025);
}

.cross-dr-plan-sections > .ant-collapse-item > .ant-collapse-content > .ant-collapse-content-box {
  padding: 16px 16px 8px;
}

.cross-dr-field-hint {
  margin-top: 4px;
  color: rgba(127, 127, 127, 0.95);
  font-size: 12px;
  line-height: 1.5;
}

@media (max-width: 900px) {
  .cross-dr-plan-create-dialog {
    max-width: calc(100vw - 32px);
    max-height: calc(100vh - 64px);
  }

  .cross-dr-plan-create-layout {
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .cross-dr-plan-summary,
  .cross-dr-plan-config {
    max-height: none;
    overflow: visible;
  }
}
```

Dark mode must not introduce a separate one-off color palette. Use the same
rgba and inherited-color strategy as SharedFS. Add only targeted overrides for
Ant Design controls that otherwise render low-contrast disabled text.

### 4.9 i18n additions

Add Korean and English keys:

```json
{
  "label.dr.section.basic": "기본 정보",
  "label.dr.section.site.mapping": "사이트 매핑",
  "label.dr.section.protection.target": "보호 대상",
  "label.dr.section.recovery.objectives": "복구 목표",
  "label.dr.section.target.placement": "대상 배치",
  "label.dr.section.worker.assignment": "워커 배치",
  "label.dr.section.sync.policy": "동기화 정책",
  "label.dr.section.advanced": "고급 설정",
  "message.dr.plan.create.dialog.summary": "DR 계획은 원본 가상머신과 복구 대상 자원을 선택해 비동기 보호 작업으로 실행됩니다."
}
```

English:

```json
{
  "label.dr.section.basic": "Basic information",
  "label.dr.section.site.mapping": "Site mapping",
  "label.dr.section.protection.target": "Protection target",
  "label.dr.section.recovery.objectives": "Recovery objectives",
  "label.dr.section.target.placement": "Target placement",
  "label.dr.section.worker.assignment": "Worker assignment",
  "label.dr.section.sync.policy": "Sync policy",
  "label.dr.section.advanced": "Advanced settings",
  "message.dr.plan.create.dialog.summary": "A DR plan selects the source VM and recovery resources, then runs protection work asynchronously."
}
```

## 5. API Design

No API command or parameter change is required for this dialog standardization.

The UI continues to use:

- `listDrSites`
- `discoverDrPlanInventory`
- `previewDrPlanSpec`
- `createDrPlan`
- `updateDrPlan`

API contract constraints:

1. UI must not send collapse state or section state to the API.
2. UI must not split the guided spec into section-specific API calls.
3. `previewDrPlanSpec` remains the single pre-submit backend preview.
4. `createDrPlan` and `updateDrPlan` remain async-safe command entry points.
5. Existing parameter semantics from
   [539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md](539-cross-hypervisor-dr-plan-storage-default-and-modal-layout-design-20260707.md)
   stay intact: `targetstorageref` is default/fallback and disk mappings are
   authoritative per disk.

Optional response presentation mapping:

```js
const reason = 'TARGET_STORAGE_REQUIRED:0'
const section = DR_PLAN_REASON_SECTION_MAP[reason.split(':')[0]]
```

This mapping belongs in UI utility code, not in API response schema.

## 6. Backend Design

No backend orchestration change is required.

Backend remains responsible for:

- inventory discovery;
- guided spec preview;
- readiness blocking reasons;
- canonical `mapping_json` generation;
- async run creation/dispatch after explicit user action.

The dialog improvement must not make backend work synchronous. UI can call
inventory and preview APIs while the dialog is open, but plan creation and sync
start remain backend async flows.

Backend reason codes should remain stable because the UI will map them to
sections. If new reason codes are added later, update
`DR_PLAN_REASON_SECTION_MAP` in UI.

No Java code is required unless an existing reason is too generic to map. In
that case, prefer adding a specific reason code such as
`TARGET_DISK_STORAGE_REQUIRED` over adding layout metadata to backend responses.

## 7. Agent Design

No Agent code change is required.

The Agent only receives commands after:

1. the UI submits create/update or runtime action through Cloud API;
2. backend validates and persists the DR plan/run;
3. backend dispatches the existing command contract.

Collapse section state, review summary values, and field hints are UI-only.
They must never be serialized into `FtctlDrActionCommand`.

Smoke check after implementation:

- existing Agent command class fields remain unchanged;
- no new UI-only keys appear in command `details` or profile JSON.

## 8. ftctl Design

No ftctl source change is required.

ftctl still receives backend-generated runtime profiles and validates disk
runtime paths. The UI standardization must not introduce:

- new ftctl CLI flags;
- new ftctl state-file fields;
- direct UI-to-ftctl calls;
- section names in profile JSON.

Smoke check after implementation:

- generated profile still contains `mapping.target` and `mapping.disks[]`;
- disk-level storage semantics from document 539 remain unchanged;
- ftctl final guards remain the last safety net.

## 9. DB Design

No DB schema or migration change is required.

Do not store:

- `planSectionActiveKeys`;
- review panel values;
- UI section status;
- section validation focus.

Existing rows continue to store only canonical DR data:

- `dr_plan.mapping_json`
- `dr_plan.schedule_json`
- `dr_plan.policy_json`
- `dr_plan.quiesce_policy_json`
- `dr_run` and `dr_run_step` runtime state

The dialog can rehydrate display state from existing plan JSON and inventory
responses when opened.

## 10. Implementation Plan

1. Add section constants and validation mapping utility.
2. Replace DR Plan create/edit section dividers with `a-collapse`.
3. Add top section alert and SharedFS-style field hints.
4. Rework left review panel styling to match SharedFS summary spacing.
5. Move target placement and disk mapping into clearly separated collapse
   panels.
6. Apply SharedFS-compatible CSS with LESS-safe width rules.
7. Add i18n keys for section labels and top guidance.
8. Run UI build and dark-mode visual check.
9. Confirm API payload does not include section state.
10. Confirm no backend, Agent, ftctl, or DB migration is required.

## 11. AS-IS / TO-BE

| Area | AS-IS | TO-BE |
| --- | --- | --- |
| Section UI | Thin divider title repeated in a long form. | SharedFS-style `a-collapse` panels with clear headers. |
| Dialog spacing | Review panel and config panel do not match SharedFS margins. | 1120px modal, 300px sticky review, 16px gap, independent scroll. |
| Readability | Dark mode section titles are low contrast and easy to miss. | Collapse headers use bordered panels and inherited color with rgba surfaces. |
| Field grouping | Many fields are vertically stacked. | Related fields are grouped in two-column rows inside semantic sections. |
| Validation focus | Error notification does not guide the user to a section. | Error reason/field opens the matching section. |
| API | No layout state, existing guided payload. | Same; no API schema change. |
| Backend | Existing preview/readiness reasons. | Same; UI maps reasons to sections. |
| Agent | Existing command dispatch. | Same; no UI section state in commands. |
| ftctl | Existing runtime profile validation. | Same; no CLI/state change. |
| DB | Existing canonical JSON columns. | Same; no UI state persistence. |

## 12. Acceptance Criteria

- DR Plan create/edit dialog visually matches the shared filesystem create
  dialog standard for width, left/right spacing, collapse sections, and dark
  mode contrast.
- The old `.cross-dr-form-section-title` divider style is not used inside DR
  Plan create/edit.
- Header/footer remain fixed; only dialog content scrolls.
- Left review summary remains visible at the top of its own scroll area.
- Closing and reopening the dialog resets to default active sections, not a
  previously stored DB state.
- Submit validation opens the section containing the first invalid field.
- Backend/API/Agent/ftctl/DB contracts remain unchanged.

## 13. 2026-07-07 Refinement: Dark Alert And Right Gutter

### 13.1 Problem

The first SharedFS-style implementation exposed two UI defects in dark mode:

- the top guidance alert inherited the outer modal color only partially, so
  Ant Design's internal `.ant-alert-message` and `.ant-alert-icon` could remain
  too dark to read;
- the dialog content could be clipped on the right because
  `DrFormModal.vue` already provides modal body padding while
  `.cross-dr-plan-create-dialog` also used a fixed `1120px` width.

This is still a UI-only correction. It must not change DR Plan payloads,
preview semantics, backend readiness, Agent dispatch, ftctl profiles, or DB
schema.

### 13.2 Layer Scope

| Layer | Change required | Design |
| --- | --- | --- |
| UI | Yes | Fix DR Plan modal CSS for alert contrast and content gutter. |
| API | No | No new parameter, no section state, no alert/layout state. |
| Backend | No | Existing readiness and blocking reason contract is unchanged. |
| Agent | No | Agent receives only backend action commands. |
| ftctl | No | ftctl receives only backend-generated runtime profiles. |
| DB | No | Do not persist modal size, gutter, alert, or collapse state. |

### 13.3 Alert Dark-mode Contract

Use the SharedFS alert pattern as the source of truth:

- base alert uses inherited text color;
- Ant Design internal message, description, and icon colors are explicitly
  covered;
- dark mode uses the same readable blue-toned treatment as SharedFS inline
  alerts.

Code-level target:

```less
.cross-dr-plan-section-alert {
  flex: 0 0 auto;
  margin-bottom: 16px;
  color: inherit;
  border-color: rgba(127, 127, 127, 0.28);
  background: rgba(64, 158, 255, 0.12);
}

.cross-dr-plan-section-alert .ant-alert-message,
.cross-dr-plan-section-alert .ant-alert-description,
.cross-dr-plan-section-alert .ant-alert-icon {
  color: inherit;
}

body.dark-mode .cross-dr-modal .cross-dr-plan-section-alert {
  color: rgba(214, 234, 255, 0.94);
  background: rgba(24, 144, 255, 0.12);
  border-color: rgba(64, 169, 255, 0.35);
}

body.dark-mode .cross-dr-modal .cross-dr-plan-section-alert .ant-alert-message,
body.dark-mode .cross-dr-modal .cross-dr-plan-section-alert .ant-alert-description,
body.dark-mode .cross-dr-modal .cross-dr-plan-section-alert .ant-alert-icon {
  color: rgba(214, 234, 255, 0.94);
}
```

Do not introduce a separate DR-only palette. Keep the same SharedFS color
strategy so future dialog components can reuse the pattern.

### 13.4 Modal Width And Gutter Contract

`DrFormModal.vue` owns the modal width and already provides body padding through
`.cross-dr-modal__scroll`. Therefore the nested dialog must not use another
fixed `1120px` width.

Code-level target:

```less
.cross-dr-modal__scroll {
  box-sizing: border-box;
}

.cross-dr-plan-create-dialog {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  max-height: calc(100vh - 222px);
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.cross-dr-plan-create-layout {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  overflow: hidden;
}

.cross-dr-plan-summary,
.cross-dr-plan-config {
  min-width: 0;
  min-height: 0;
  max-height: calc(100vh - 294px);
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-gutter: stable;
}

.cross-dr-plan-config {
  padding-right: 14px;
}

.cross-dr-plan-sections > .ant-collapse-item > .ant-collapse-content > .ant-collapse-content-box {
  padding: 16px 18px 8px 16px;
}
```

Implementation note:

- `scrollbar-gutter: stable` is progressive enhancement. Browsers that do not
  support it still keep the explicit right padding.
- Do not compensate by widening the modal beyond `planModalWidth`; that would
  only reintroduce clipping on narrower desktops.
- Select and input controls inside the collapse panels must keep `min-width: 0`
  and `width: 100%`.

### 13.5 Acceptance Criteria

- In dark mode, the top guidance alert message and icon are readable without
  relying on hover/focus.
- The right edge of select/input controls is visible inside the modal body.
- Scrollbars do not overlap the select arrow or selected text.
- The fix changes CSS only; API payloads and backend-generated JSON remain
  byte-for-byte governed by the existing guided DR Plan logic.
