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
<template>
  <div class="cross-dr-page cross-dr-standard-page">
    <a-affix
      :key="'affix-' + showSearchFilters"
      :offsetTop="this.$store.getters.maintenanceInitiated || this.$store.getters.shutdownTriggered ? 103 : 78">
      <a-card class="breadcrumb-card" style="z-index: 10">
        <a-row>
          <a-col
            :span="device === 'mobile' ? 24 : 12"
            style="padding-left: 12px; margin-top: 10px">
            <breadcrumb :resource="breadcrumbResource">
              <template #end>
                <a-button
                  :loading="loading || protectionRefreshing"
                  style="margin-bottom: 5px"
                  shape="round"
                  size="small"
                  @click="detailId ? requestProtectionRefresh() : fetchData()">
                  <template #icon><ReloadOutlined /></template>
                  {{ $t('label.refresh') }}
                </a-button>
                <a-tooltip placement="right">
                  <template #title>
                    {{ $t('label.filterby') }}
                  </template>
                  <a-select
                    v-if="!detailId"
                    :placeholder="$t('label.filterby')"
                    :value="filterValue"
                    style="min-width: 100px; margin-left: 10px; margin-bottom: 5px"
                    size="small"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                    @change="changeFilter">
                    <template #suffixIcon><filter-outlined class="ant-select-suffix" /></template>
                    <a-select-option key="all" :label="$t('label.all')">
                      {{ $t('label.all') }}
                    </a-select-option>
                    <a-select-option
                      v-for="state in planStates"
                      :key="state"
                      :label="state">
                      {{ state }}
                    </a-select-option>
                  </a-select>
                </a-tooltip>
              </template>
            </breadcrumb>
          </a-col>
          <a-col
            :span="device === 'mobile' ? 24 : 12"
            :style="device === 'mobile' ? { float: 'right', 'margin-top': '12px', 'margin-bottom': '-6px', display: 'table' } : { float: 'right', display: 'table', 'margin-top': '6px' }">
            <dr-resource-action-menu
              v-if="detailId && detailPlan.id"
              :actions="planActions"
              :resource="detailPlan"
              :triggerStyle="{ float: device === 'mobile' ? 'left' : 'right' }"
              @exec-action="runPlanAction" />
            <dr-checkpoint-manager
              v-if="!detailId"
              toolbar
              style="display: inline-flex; margin-right: 10px; vertical-align: middle" />
            <span
              v-if="!detailId && selectedRowKeys.length > 0 && 'startDrProtectionGroupAction' in $store.getters.apis"
              class="row-action-button"
              :style="{ 'margin-right': '10px', display: 'inline-flex' }">
              <a-button
                type="primary"
                shape="round"
                class="action-button-item"
                :style="{ 'margin-left': '5px' }"
                @click="openGroupModal">
                <ApartmentOutlined class="action-button-item__icon" />
                <span>{{ $t('label.dr.protection.group.action') }}</span>
              </a-button>
            </span>
            <action-button
              v-else-if="!detailId && 'createDrPlan' in $store.getters.apis"
              :style="{ 'margin-right': '10px', display: 'inline-flex' }"
              :loading="loading"
              :actions="createPlanActions"
              :selectedRowKeys="selectedRowKeys"
              :selectedItems="[]"
              :dataView="false"
              :resource="{}"
              @exec-action="openCreateModal" />
            <search-view
              v-if="!detailId"
              :searchFilters="searchFilters"
              :searchParams="searchParams"
              apiName="listDrPlans"
              @search="onSearch" />
          </a-col>
        </a-row>
        <a-row
          v-if="showSearchFilters"
          style="min-height: 36px; padding-top: 12px; padding-left: 12px;">
          <search-filter
            :filters="activeFiltersList"
            apiName="listDrPlans"
            @removeFilter="removeFilter" />
        </a-row>
      </a-card>
    </a-affix>

    <a-alert
      v-if="!hasListApi"
      type="warning"
      show-icon
      :message="$t('message.dr.api.unavailable')" />
    <a-alert
      v-else-if="!detailId && listLoadWarning"
      type="warning"
      show-icon
      class="cross-dr-detail-warning"
      :message="$t('message.dr.plan.list.fallback')"
      :description="listLoadWarning" />

    <template v-else-if="detailId">
      <resource-layout>
        <template #left>
          <dr-resource-info-card
            resourceType="plan"
            :resource="detailPlan"
            :title="detailPlan.name || detailPlan.id || '-'"
            :tags="planInfoTags"
            :summaryFields="planSummaryFields"
            :loading="loading"
            @contextmenu="openPlanContextMenu" />
        </template>

        <template #right>
          <a-card
            class="spin-content"
            :loading="loading"
            :bordered="true"
            style="width: 100%"
            @contextmenu.stop.prevent="openPlanContextMenu($event, detailPlan)">
            <a-alert
              v-if="detailLoadWarning"
              type="warning"
              show-icon
              class="cross-dr-detail-warning"
              :message="$t('message.dr.plan.detail.fallback')"
              :description="detailLoadWarning" />
            <a-tabs
              style="width: 100%; margin-top: -12px"
              :activeKey="activeTab"
              :animated="false"
              @change="changeTab">
              <a-tab-pane key="details" :tab="$t('label.details')">
                <dr-plan-overview
                  v-if="detailPlan.id"
                  :plan="detailPlan"
                  :sourceSite="siteById[detailPlan.sourcesiteid] || {}"
                  :targetSite="siteById[detailPlan.targetsiteid] || {}"
                  :currentRun="currentRun"
                  :showProtectionSummary="false" />
              </a-tab-pane>
              <a-tab-pane key="protection" :tab="$t('label.dr.protection.info')">
                <dr-protection-info-tab
                  v-if="detailPlan.id"
                  :plan="detailPlan"
                  :sourceSite="siteById[detailPlan.sourcesiteid] || {}"
                  :targetSite="siteById[detailPlan.targetsiteid] || {}"
                  :currentRun="currentRun"
                  :latestOperationRun="latestOperationRun"
                  :currentSyncCycle="currentSyncCycle"
                  :latestCompletedSyncCycle="latestCompletedSyncCycle"
                  :currentProtectionRuntime="currentProtectionRuntime"
                  :failbackSession="protectionSnapshot.failbackSession || {}"
                  :replicas="protectionSnapshot.replicas || []"
                  :latestCompletedCheckpoint="protectionSnapshot.latestCompletedCheckpoint || {}"
                  :generated="protectionView.generated || ''"
                  :projectionState="protectionView.projectionstate || ''"
                  :lastError="protectionView.lasterror || ''" />
              </a-tab-pane>
              <a-tab-pane key="history" :tab="$t('label.dr.history')">
                <dr-plan-history-tab v-if="detailPlan.id" :planId="detailPlan.id" />
              </a-tab-pane>
              <a-tab-pane key="events" :tab="$t('label.events')">
                <dr-events-tab v-if="detailPlan.id" :planId="detailPlan.id" :runId="$route.query.runid || ''" />
              </a-tab-pane>
            </a-tabs>
          </a-card>
        </template>
      </resource-layout>
    </template>

    <template v-else>
      <div class="row-element" @contextmenu="openListContextMenu">
        <section v-if="trackedGroupRun.id" class="cross-dr-group-run-panel">
          <div class="cross-dr-group-run-panel__header">
            <div>
              <strong>{{ trackedGroupRun.groupname || $t('label.dr.protection.group.action') }}</strong>
              <span class="cross-dr-group-run-panel__action">{{ groupActionLabel(trackedGroupRun.action) }}</span>
            </div>
            <dr-status-pill :status="trackedGroupRun.state" />
          </div>
          <div class="cross-dr-group-run-panel__summary">
            {{ $t('message.dr.protection.group.summary', {
              succeeded: trackedGroupRun.succeededcount || 0,
              failed: trackedGroupRun.failedcount || 0,
              total: trackedGroupRun.totalcount || 0
            }) }}
          </div>
          <div
            v-if="trackedGroupResultFinalizingCount || trackedGroupConsistencyWarningCount"
            class="cross-dr-group-run-panel__verification">
            <span v-if="trackedGroupResultFinalizingCount" class="cross-dr-group-result-finalizing">
              {{ $t('message.dr.protection.group.result.finalizing.count', { count: trackedGroupResultFinalizingCount }) }}
            </span>
            <span v-if="trackedGroupConsistencyWarningCount" class="cross-dr-group-resource-wait">
              {{ $t('message.dr.protection.group.consistency.warning.count', { count: trackedGroupConsistencyWarningCount }) }}
            </span>
          </div>
          <a-progress
            :percent="trackedGroupProgressPercent"
            :status="String(trackedGroupRun.state || '').toUpperCase() === 'FAILED' ? 'exception' : 'active'"
            size="small" />
          <a-table
            v-if="trackedGroupPlanResults.length"
            class="cross-dr-group-table cross-dr-group-run-table"
            size="small"
            :pagination="false"
            :dataSource="trackedGroupPlanResults"
            :rowKey="record => record.planId || record.planName">
            <a-table-column :title="$t('label.name')" dataIndex="planName" key="planName" />
            <a-table-column :title="$t('label.dr.protection.group.initial.sync')" key="initialSyncState">
              <template #default="{ record }"><dr-status-pill :status="record.terminalizationState || record.initialSyncState || record.state" /></template>
            </a-table-column>
            <a-table-column :title="$t('label.dr.protection.group.continuous.protection')" key="continuousProtectionState">
              <template #default="{ record }"><dr-status-pill :status="record.continuousProtectionState || 'PENDING'" /></template>
            </a-table-column>
            <a-table-column :title="$t('label.dr.protection.group.rpo')" key="rpo">
              <template #default="{ record }">{{ groupRpoText(record) }}</template>
            </a-table-column>
            <a-table-column :title="$t('label.details')" key="reason">
              <template #default="{ record }">
                <span v-if="record.resourceWaiting" class="cross-dr-group-resource-wait">
                  {{ $t('message.dr.protection.group.resource.wait') }}
                </span>
                <span v-else-if="record.terminalizationState === 'RESULT_FINALIZING'" class="cross-dr-group-result-finalizing">
                  {{ $t('message.dr.protection.group.result.finalizing') }}
                </span>
                <span v-else-if="record.terminalizationState === 'CONSISTENCY_WARNING'" class="cross-dr-group-resource-wait">
                  {{ $t('message.dr.protection.group.consistency.warning', { sequence: record.acceptedCycleSequence || '-' }) }}
                </span>
                <span v-else>{{ groupReasonText(record.reasonCode, record.error) }}</span>
              </template>
            </a-table-column>
          </a-table>
        </section>
        <a-table
          class="cross-dr-standard-table"
          size="middle"
          :columns="tableColumns"
          :dataSource="pagedPlans"
          :rowKey="record => record.id"
          :loading="loading"
          :pagination="false"
          :rowSelection="listRowSelection">
          <template #customFilterDropdown>
            <div style="padding: 8px" class="filter-dropdown">
              <a-menu>
                <a-menu-item
                  v-for="column in columnSelectorColumns"
                  :key="column.key"
                  @click="updateSelectedColumns(column.dataIndex)">
                  <a-checkbox :checked="selectedColumns.includes(column.dataIndex)" />
                  {{ column.title }}
                </a-menu-item>
              </a-menu>
            </div>
          </template>
          <template #bodyCell="{ column, record, text }">
            <template v-if="column.key === 'name'">
              <span class="cross-dr-resource-name">
                <BranchesOutlined />
                <router-link :to="{ path: '/drplan/' + record.id }">{{ text || record.id }}</router-link>
              </span>
            </template>
            <template v-else-if="column.key === 'state'">
              <status :text="effectivePlanState(record)" displayText />
            </template>
            <template v-else-if="column.key === 'sourcesiteid' || column.key === 'targetsiteid'">
              {{ siteName(text) }}
            </template>
            <template v-else-if="column.key === 'direction'">
              {{ $t(directionLabel(text)) }}
            </template>
            <template v-else-if="column.key === 'enginetype'">
              {{ $t(engineLabel(text)) }}
            </template>
            <template v-else-if="column.key === 'displayrposeconds'">
              <span class="cross-dr-rpo-cell">
                <span>{{ formatRpo(record) }}</span>
                <dr-status-pill
                  v-if="isRpoAttentionState(record.freshnessstate)"
                  :status="record.freshnessstate" />
              </span>
            </template>
          </template>
        </a-table>
        <a-pagination
          class="row-element"
          style="margin-top: 10px"
          size="small"
          :current="normalizedPage"
          :pageSize="pageSize"
          :total="listTotal"
          :showTotal="paginationTotal"
          :pageSizeOptions="pageSizeOptions"
          @change="changePage"
          @showSizeChange="changePageSize"
          showSizeChanger
          showQuickJumper>
          <template #buildOptionText="props">
            <span>{{ props.value }} / {{ $t('label.page') }}</span>
          </template>
        </a-pagination>
      </div>
    </template>

    <dr-form-modal
      :visible="showCreateModal"
      :title="planModalTitle"
      :width="planModalWidth"
      :confirm-loading="createLoading"
      @cancel="closeCreateModal"
      @ok="submitPlan">
      <div class="cross-dr-plan-create-dialog" v-ctrl-enter="submitPlan">
        <a-alert
          class="cross-dr-plan-section-alert"
          type="info"
          show-icon
          :message="$t('message.dr.plan.create.dialog.summary')" />
        <div class="cross-dr-plan-create-layout">
          <aside class="cross-dr-plan-summary">
            <section class="cross-dr-plan-summary-panel">
              <div class="cross-dr-plan-summary-title">{{ $t('label.dr.review') }}</div>
              <dl class="cross-dr-plan-review-list">
                <template v-for="item in planSummaryItems" :key="item.key">
                  <dt>{{ item.label }}</dt>
                  <dd>{{ item.value || '-' }}</dd>
                </template>
              </dl>
              <div v-if="diskMappingRows.length > 0" class="cross-dr-plan-summary-note">
                {{ diskMappingSummaryText }}
              </div>
              <a-alert
                v-if="inventoryBlockingReasons.length > 0"
                type="warning"
                showIcon
                class="cross-dr-plan-summary-alert"
                :message="inventoryBlockingReasons.join(', ')" />
            </section>
          </aside>
          <main class="cross-dr-plan-config">
        <a-form layout="vertical" class="cross-dr-plan-form">
          <a-collapse
            v-model:activeKey="planSectionActiveKeys"
            class="cross-dr-plan-sections"
            :bordered="true">
          <a-collapse-panel key="basic" :header="$t('label.dr.basic.info')">
            <a-row :gutter="16">
              <a-col :xs="24" :md="12">
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.name')" :tooltip="$t('message.dr.plan.name.help')" />
            </template>
            <a-input
              v-model:value="createForm.name"
              :maxlength="255"
              :placeholder="$t('message.dr.plan.name.placeholder')" />
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.description')" :tooltip="$t('message.dr.plan.description.help')" />
            </template>
            <a-input
              v-model:value="createForm.description"
              :maxlength="1024"
              :placeholder="$t('message.dr.plan.description.placeholder')" />
          </a-form-item>
              </a-col>
            </a-row>
          </a-collapse-panel>
          <a-collapse-panel v-if="planFormMode === 'create'" key="sites" :header="$t('label.dr.site.mapping')">
            <a-row :gutter="16">
              <a-col :xs="24" :md="12">
          <a-form-item v-if="planFormMode === 'create'" required>
            <template #label>
              <tooltip-label :title="$t('label.dr.source.site')" :tooltip="$t('message.dr.plan.source.site.help')" />
            </template>
            <a-select
              v-model:value="createForm.sourcesiteid"
              showSearch
              optionFilterProp="label"
              :placeholder="$t('message.dr.plan.source.site.placeholder')"
              @change="changeSourceSite">
              <a-select-option v-for="site in sites" :key="site.id" :value="site.id" :label="site.name">{{ site.name }}</a-select-option>
            </a-select>
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item v-if="planFormMode === 'create'" required>
            <template #label>
              <tooltip-label :title="$t('label.dr.target.site')" :tooltip="$t('message.dr.plan.target.site.help')" />
            </template>
            <a-select
              v-model:value="createForm.targetsiteid"
              showSearch
              optionFilterProp="label"
              :placeholder="$t('message.dr.plan.target.site.placeholder')"
              @change="changeTargetSite">
              <a-select-option v-for="site in sites" :key="site.id" :value="site.id" :label="site.name">{{ site.name }}</a-select-option>
            </a-select>
          </a-form-item>
              </a-col>
              <a-col :xs="24">
          <a-form-item v-if="planFormMode === 'create'" required>
            <template #label>
              <tooltip-label :title="$t('label.dr.direction')" :tooltip="$t('message.dr.plan.direction.help')" />
            </template>
            <a-select
              v-model:value="createForm.direction"
              :placeholder="$t('message.dr.plan.direction.placeholder')"
              :disabled="true">
              <a-select-option
                v-for="direction in directionOptions"
                :key="direction.value"
                :value="direction.value">
                {{ $t(direction.label) }}
              </a-select-option>
            </a-select>
          </a-form-item>
              </a-col>
            </a-row>
          </a-collapse-panel>
          <a-collapse-panel key="workload" :header="$t('label.dr.protection.target')">
          <a-form-item required>
            <template #label>
              <tooltip-label :title="$t('label.dr.source.vm')" :tooltip="$t('message.dr.plan.source.vm.help')" />
            </template>
            <a-select
              v-model:value="createForm.sourceworkloadvalue"
              showSearch
              allowClear
              :filterOption="false"
              :loading="sourceWorkloadLoading"
              :disabled="planFormMode === 'edit' || (planFormMode === 'create' && !canDiscoverSourceWorkloads)"
              :placeholder="sourceWorkloadPlaceholder"
              @focus="ensureSourceWorkloads"
              @search="searchSourceWorkloads"
              @change="changeSourceWorkload">
              <a-select-option
                v-for="workload in sourceWorkloadOptions"
                :key="workload.optionKey"
                :value="workload.optionKey"
                :label="workload.name">
                <span>{{ workload.name || workload.externalref || workload.id }}</span>
                <span v-if="workload.state" class="cross-dr-select-meta">{{ workload.state }}</span>
              </a-select-option>
            </a-select>
            <div v-if="sourceWorkloadHelpText" class="cross-dr-form-help">{{ sourceWorkloadHelpText }}</div>
          </a-form-item>
          </a-collapse-panel>
          <a-collapse-panel key="objectives" :header="$t('label.dr.recovery.objective')">
            <a-row :gutter="16">
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.rpo')" :tooltip="$t('message.dr.plan.rpo.help')" />
            </template>
            <a-input-number
              v-model:value="createForm.rposeconds"
              style="width: 100%"
              :min="1"
              :placeholder="$t('message.dr.plan.rpo.placeholder')" />
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.rto')" :tooltip="$t('message.dr.plan.rto.help')" />
            </template>
            <a-input-number
              v-model:value="createForm.rtoseconds"
              style="width: 100%"
              :min="1"
              :placeholder="$t('message.dr.plan.rto.placeholder')" />
          </a-form-item>
              </a-col>
              <a-col v-if="planFormMode === 'create'" :xs="24">
          <a-form-item v-if="planFormMode === 'create'">
            <template #label>
              <tooltip-label :title="$t('label.dr.start.sync.after.create')" :tooltip="$t('message.dr.plan.start.sync.help')" />
            </template>
            <a-switch v-model:checked="createForm.startsync" />
          </a-form-item>
              </a-col>
            </a-row>
          </a-collapse-panel>
          <a-collapse-panel key="target" :header="$t('label.dr.target.placement')">
            <a-row :gutter="16">
              <a-col v-if="directionUsesKvmTarget || targetWorkerHostOptions.length > 0" :xs="24" :md="12">
          <a-form-item v-if="directionUsesKvmTarget || targetWorkerHostOptions.length > 0">
            <template #label>
              <tooltip-label :title="$t('label.dr.target.vm.name')" :tooltip="$t('message.dr.plan.target.vm.name.help')" />
            </template>
            <a-input
              v-model:value="createForm.targetvmname"
              :maxlength="255"
              :placeholder="$t('message.dr.plan.target.vm.name.placeholder')" />
          </a-form-item>
              </a-col>
              <a-col :xs="24">
          <a-alert
            v-if="inventoryBlockingReasons.length > 0"
            type="warning"
            showIcon
            class="cross-dr-form-alert"
            :message="inventoryBlockingReasons.join(', ')" />
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.default.target.storage')" :tooltip="$t('message.dr.plan.default.target.storage.help')" />
            </template>
            <a-select
              v-if="targetStorageOptions.length > 0"
              v-model:value="createForm.targetstorageref"
              showSearch
              allowClear
              optionFilterProp="label"
              :placeholder="$t('message.dr.plan.default.target.storage.placeholder')">
              <a-select-option
                v-for="storage in targetStorageOptions"
                :key="storage.optionKey"
                :value="storage.value"
                :label="storage.name">
                <span>{{ storage.name || storage.value }}</span>
                <span v-if="storage.description" class="cross-dr-select-meta">{{ storage.description }}</span>
              </a-select-option>
            </a-select>
            <a-alert
              v-else
              type="warning"
              showIcon
              :message="$t('message.dr.plan.target.storage.empty')" />
            <div v-if="requiresDiskMapping && createForm.targetstorageref" class="cross-dr-inline-action-row">
              <a-button size="small" @click="applyDefaultStorageToDiskRows">
                {{ $t('label.dr.apply.to.all.disks') }}
              </a-button>
              <span>{{ $t('message.dr.plan.default.target.storage.apply.help') }}</span>
            </div>
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.target.compute')" :tooltip="$t('message.dr.plan.target.compute.help')" />
            </template>
            <a-select
              v-if="targetComputeOptions.length > 0"
              v-model:value="createForm.targetcomputeref"
              showSearch
              allowClear
              optionFilterProp="label"
              :placeholder="$t('message.dr.plan.target.compute.placeholder')"
              @change="changeTargetCompute">
              <a-select-option
                v-for="compute in targetComputeOptions"
                :key="compute.optionKey"
                :value="compute.value"
                :label="compute.name">
                <span>{{ compute.name || compute.value }}</span>
                <span v-if="compute.description" class="cross-dr-select-meta">{{ compute.description }}</span>
              </a-select-option>
            </a-select>
            <a-alert
              v-else
              type="warning"
              showIcon
              :message="$t('message.dr.plan.target.compute.empty')" />
          </a-form-item>
              </a-col>
              <a-col v-for="field in targetSizingFields" :key="field.key" :xs="24" :md="8">
                <a-form-item :label="$t(field.label)" :required="!field.fixed">
                  <a-input-number
                    v-model:value="createForm[field.key]"
                    :aria-label="$t(field.label)"
                    :disabled="!!field.fixed"
                    :min="field.min"
                    :max="field.max"
                    :step="1"
                    style="width: 100%" />
                  <div v-if="!field.fixed" class="cross-dr-select-meta">
                    {{ $t('message.dr.target.compute.range', { min: field.min, max: field.max }) }}
                  </div>
                </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.target.network')" :tooltip="$t('message.dr.plan.target.network.help')" />
            </template>
            <a-select
              v-if="targetNetworkOptions.length > 0"
              v-model:value="createForm.targetnetworkref"
              showSearch
              allowClear
              optionFilterProp="label"
              :placeholder="$t('message.dr.plan.target.network.placeholder')">
              <a-select-option
                v-for="network in targetNetworkOptions"
                :key="network.optionKey"
                :value="network.value"
                :label="network.name">
                <span>{{ network.name || network.value }}</span>
                <span v-if="network.description" class="cross-dr-select-meta">{{ network.description }}</span>
              </a-select-option>
            </a-select>
            <a-alert
              v-else
              type="warning"
              showIcon
              :message="$t('message.dr.plan.target.network.empty')" />
          </a-form-item>
              </a-col>
              <a-col v-if="directionUsesVmwareTarget" :xs="24" :md="12">
          <a-form-item v-if="directionUsesVmwareTarget">
            <template #label>
              <tooltip-label :title="$t('label.dr.target.folder.path')" :tooltip="$t('message.dr.plan.target.folder.path.help')" />
            </template>
            <a-input
              v-model:value="createForm.targetfolderpath"
              :placeholder="$t('message.dr.plan.target.folder.path.placeholder')" />
          </a-form-item>
              </a-col>
            </a-row>
          </a-collapse-panel>
          <a-collapse-panel v-if="requiresDiskMapping" key="disks" :header="$t('label.dr.disk.mapping')">
          <div v-if="requiresDiskMapping && diskMappingRows.length > 0" class="cross-dr-disk-mapping-list">
            <div
              v-for="(disk, diskIndex) in diskMappingRows"
              :key="disk.sourceDiskRef || disk.sourcePath || diskIndex"
              class="cross-dr-disk-mapping-row">
              <div class="cross-dr-disk-field cross-dr-disk-source">
                <span class="cross-dr-disk-field__label">{{ $t('label.dr.source.disk') }}</span>
                <strong>{{ disk.sourceLabel }}</strong>
                <span v-if="disk.sourceDiskRef">{{ $t('label.dr.source.disk.id') }}: {{ disk.sourceDiskRef }}</span>
                <span v-if="disk.sourcePath">{{ $t('label.dr.source.disk.path') }}: {{ disk.sourcePath }}</span>
                <span v-if="normalizeDiskSizeBytes(disk.capacityBytes)" class="cross-dr-select-meta">
                  {{ $t('label.dr.source.disk.size') }}: {{ formatBytes(disk.capacityBytes) }}
                </span>
                <span v-else class="cross-dr-disk-warning">
                  {{ $t('message.dr.plan.validation.source.disk.size') }}
                </span>
              </div>
              <div class="cross-dr-disk-field">
                <span class="cross-dr-disk-field__label">{{ $t('label.dr.target.disk.name') }}</span>
                <a-input
                  v-model:value="disk.targetDiskName"
                  :placeholder="$t('message.dr.plan.target.disk.name.placeholder')" />
              </div>
              <div class="cross-dr-disk-field">
                <span class="cross-dr-disk-field__label">{{ $t('label.dr.target.disk.offering') }}</span>
                <a-select
                  v-model:value="disk.targetDiskOfferingId"
                  showSearch
                  allowClear
                  optionFilterProp="label"
                  :placeholder="$t('message.dr.plan.target.disk.offering.placeholder')">
                  <a-select-option
                    v-for="offering in targetDiskOfferingOptions"
                    :key="offering.optionKey"
                    :value="offering.value"
                    :label="offering.name">
                    <span>{{ offering.name || offering.value }}</span>
                    <span v-if="offering.description" class="cross-dr-select-meta">{{ offering.description }}</span>
                  </a-select-option>
                </a-select>
              </div>
              <div class="cross-dr-disk-field">
                <span class="cross-dr-disk-field__label">{{ $t('label.dr.target.storage') }}</span>
                <a-select
                  v-model:value="disk.targetStorageRef"
                  showSearch
                  allowClear
                  optionFilterProp="label"
                  :placeholder="$t('message.dr.plan.target.storage.placeholder')">
                  <a-select-option
                    v-for="storage in targetStorageOptions"
                    :key="storage.optionKey"
                    :value="storage.value"
                    :label="storage.name">
                    <span>{{ storage.name || storage.value }}</span>
                    <span v-if="storage.description" class="cross-dr-select-meta">{{ storage.description }}</span>
                  </a-select-option>
                </a-select>
              </div>
            </div>
          </div>
          <a-alert
            v-else-if="requiresDiskMapping"
            type="warning"
            showIcon
            :message="$t('message.dr.plan.source.disk.empty')" />
          </a-collapse-panel>
          <a-collapse-panel key="workers" :header="$t('label.dr.worker.assignment')">
            <a-alert
              :type="coordinatorWorkerHostOptions.length > 0 || targetWorkerHostOptions.length > 0 ? 'success' : 'warning'"
              showIcon
              class="cross-dr-form-alert"
              :message="$t(coordinatorWorkerHostOptions.length > 0 || targetWorkerHostOptions.length > 0
                ? 'message.dr.plan.worker.automatic.ready'
                : 'message.dr.plan.worker.automatic.unavailable')" />
          </a-collapse-panel>
          <a-collapse-panel key="policy" :header="$t('label.dr.sync.policy')">
            <a-row :gutter="16">
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.consistency.mode')" :tooltip="$t('message.dr.plan.consistency.mode.help')" />
            </template>
            <a-select v-model:value="createForm.consistencymode">
              <a-select-option value="CRASH_CONSISTENT">{{ $t('label.dr.consistency.crash') }}</a-select-option>
              <a-select-option value="APPLICATION_CONSISTENT">{{ $t('label.dr.consistency.application') }}</a-select-option>
            </a-select>
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.sync.interval.seconds')" :tooltip="$t('message.dr.plan.sync.interval.help')" />
            </template>
            <a-input-number
              v-model:value="createForm.syncintervalseconds"
              style="width: 100%"
              :min="1"
              :placeholder="$t('message.dr.plan.sync.interval.placeholder')" />
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.retention.count')" :tooltip="$t('message.dr.plan.retention.count.help')" />
            </template>
            <a-input-number
              v-model:value="createForm.retentioncount"
              style="width: 100%"
              :min="1"
              :placeholder="$t('message.dr.plan.retention.count.placeholder')" />
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.test.network.mode')" :tooltip="$t('message.dr.plan.test.network.mode.help')" />
            </template>
            <a-select v-model:value="createForm.testnetworkmode">
              <a-select-option value="ISOLATED">{{ $t('label.dr.test.network.isolated') }}</a-select-option>
              <a-select-option value="PRODUCTION">{{ $t('label.dr.test.network.production') }}</a-select-option>
            </a-select>
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.test.boot.validation.mode')" :tooltip="$t('message.dr.plan.test.boot.validation.mode.help')" />
            </template>
            <a-select v-model:value="createForm.testbootvalidationmode">
              <a-select-option value="POWER_STATE_ONLY">{{ $t('label.dr.test.boot.validation.power.state') }}</a-select-option>
              <a-select-option value="QGA_REQUIRED">{{ $t('label.dr.test.boot.validation.qga') }}</a-select-option>
            </a-select>
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.test.boot.timeout.seconds')" :tooltip="$t('message.dr.plan.test.boot.timeout.help')" />
            </template>
            <a-input-number v-model:value="createForm.testboottimeoutseconds" style="width: 100%" :min="30" :max="1800" />
          </a-form-item>
              </a-col>
              <a-col :xs="24" :md="12">
          <a-form-item>
            <template #label>
              <tooltip-label :title="$t('label.dr.failover.power.on')" :tooltip="$t('message.dr.plan.failover.power.on.help')" />
            </template>
            <a-switch v-model:checked="createForm.failoverpoweron" />
          </a-form-item>
              </a-col>
            </a-row>
          </a-collapse-panel>
          <a-collapse-panel key="advanced" :header="$t('label.dr.expert.engine.settings')">
              <a-form-item>
                <template #label>
                  <tooltip-label :title="$t('label.dr.expert.json.mode')" :tooltip="$t('message.dr.plan.expert.json.mode.help')" />
                </template>
                <a-switch v-model:checked="createForm.expertjson" />
              </a-form-item>
              <a-form-item :label="$t('label.dr.engine')">
                <a-select v-model:value="createForm.enginetype" @change="onCreateEngineChange">
                  <a-select-option
                    v-for="engine in engineOptions"
                    :key="engine.value"
                    :value="engine.value"
                    :disabled="engine.disabled">
                    {{ $t(engine.label) }}
                  </a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item v-if="createForm.expertjson" :label="$t('label.dr.engine.binding.type')">
                <a-input v-model:value="createForm.enginebindingtype" />
              </a-form-item>
              <a-form-item v-if="createForm.expertjson" :label="$t('label.dr.engine.binding.id')">
                <a-input-number v-model:value="createForm.enginebindingid" style="width: 100%" />
              </a-form-item>
              <a-form-item v-if="createForm.expertjson" :label="$t('label.dr.mapping.json')">
                <a-textarea v-model:value="createForm.mappingjson" :rows="4" :placeholder="$t('message.dr.plan.mapping.json.placeholder')" />
              </a-form-item>
              <a-form-item v-if="createForm.expertjson" :label="$t('label.dr.schedule.json')">
                <a-textarea v-model:value="createForm.schedulejson" :rows="2" :placeholder="$t('message.dr.plan.schedule.json.placeholder')" />
              </a-form-item>
              <a-form-item v-if="createForm.expertjson" :label="$t('label.dr.policy.json')">
                <a-textarea v-model:value="createForm.policyjson" :rows="2" :placeholder="$t('message.dr.plan.policy.json.placeholder')" />
              </a-form-item>
              <a-form-item v-if="createForm.expertjson" :label="$t('label.dr.quiesce.policy.json')">
                <a-textarea v-model:value="createForm.quiescepolicyjson" :rows="2" :placeholder="$t('message.dr.plan.quiesce.json.placeholder')" />
              </a-form-item>
          </a-collapse-panel>
          </a-collapse>
        </a-form>
          </main>
        </div>
      </div>
    </dr-form-modal>
    <dr-resource-context-menu
      :visible="contextMenuVisible"
      :actions="contextMenuActions"
      :resource="contextMenuResource"
      :position="contextMenuPosition"
      :title="contextMenuTitle"
      @close="closeContextMenu"
      @exec-action="runContextMenuAction" />

    <dr-form-modal
      :visible="showActionModal"
      :title="actionModalTitle"
      :loading="actionPreflightLoading"
      :confirm-loading="actionSubmitting"
      :ok-disabled="isFailbackAction && (!canSubmitFailback || actionPreflightLoading)"
      :danger="selectedAction.danger"
      @cancel="closeActionModal"
      @ok="submitActionModal">
      <div class="form-layout cross-dr-form-layout" v-ctrl-enter="submitActionModal">
      <a-form layout="vertical" class="cross-dr-action-modal">
        <a-alert
          v-if="isFullResyncAction"
          type="warning"
          show-icon
          :message="$t('message.dr.full.resync.confirm')" />
        <a-alert
          v-if="isReprotectAction"
          type="info"
          show-icon
          :message="$t('message.dr.reprotect.preflight.automatic')" />
        <a-alert
          v-if="isReleaseAction"
          :type="actionForm.resourcedisposition === 'DELETE_STANDBY_REPLICA' ? 'warning' : 'info'"
          show-icon
          :message="releaseDispositionMessage" />
        <a-form-item
          v-if="isReleaseAction"
          :label="$t('label.dr.release.resource.disposition')"
          required>
          <a-radio-group
            v-model:value="actionForm.resourcedisposition"
            class="cross-dr-release-disposition">
            <a-radio value="RETAIN_OPERATIONAL_VM">
              <span class="cross-dr-release-option-title">{{ $t('label.dr.release.retain.operational.vm') }}</span>
              <span class="cross-dr-release-option-description">{{ $t('message.dr.release.retain.operational.vm') }}</span>
            </a-radio>
            <a-radio
              value="DELETE_STANDBY_REPLICA"
              :disabled="releaseTargetAuthorityActive">
              <span class="cross-dr-release-option-title">{{ $t('label.dr.release.delete.standby.replica') }}</span>
              <span class="cross-dr-release-option-description">{{ $t(releaseTargetAuthorityActive
                ? 'message.dr.release.delete.blocked.target.authority'
                : 'message.dr.release.delete.standby.replica') }}</span>
            </a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item
          v-if="isFailoverAction || isReleaseAction || isFailbackAction"
          :label="$t('label.dr.action.force')">
          <a-switch v-model:checked="actionForm.force" />
        </a-form-item>
        <a-form-item v-if="isTestFailoverAction" :label="$t('label.dr.test.source.independent')">
          <a-switch v-model:checked="actionForm.sourceindependent" />
        </a-form-item>
        <a-alert
          v-if="isTestFailoverAction && actionForm.sourceindependent"
          type="info"
          show-icon
          :message="$t('message.dr.test.source.independent')" />
        <a-form-item
          v-if="isFailoverAction"
          :label="$t('label.dr.action.disaster')">
          <a-switch
            v-model:checked="actionForm.disaster"
            :disabled="failoverRequiresDisasterMode" />
        </a-form-item>
        <a-alert
          v-if="failoverRequiresDisasterMode"
          class="cross-dr-plan-section-alert"
          type="warning"
          show-icon
          :message="$t('message.dr.failover.degraded.disaster.required')" />
        <a-form-item
          v-if="isFailoverAction && !actionForm.disaster"
          :label="$t('label.dr.action.final.sync')">
          <a-switch v-model:checked="actionForm.finalsync" />
        </a-form-item>
        <a-form-item
          v-if="isFailoverAction && !actionForm.disaster"
          :label="$t('label.dr.action.skip.source.fence')">
          <a-switch v-model:checked="actionForm.skipsourcefencerequest" />
        </a-form-item>
        <template v-if="isFailoverAction && actionForm.disaster">
          <a-alert
            type="warning"
            show-icon
            :message="$t('message.dr.failover.source.isolation.notice')" />
          <a-form-item :label="$t('label.dr.failover.source.isolation.acknowledged')" required>
            <a-checkbox v-model:checked="actionForm.sourceisolationacknowledged">
              {{ $t('message.dr.failover.source.isolation.acknowledgement') }}
            </a-checkbox>
          </a-form-item>
          <a-form-item :label="$t('label.dr.failover.source.isolation.reason')" required>
            <a-textarea
              v-model:value="actionForm.sourceisolationreason"
              :rows="3"
              :placeholder="$t('message.dr.failover.source.isolation.reason.placeholder')" />
          </a-form-item>
        </template>
        <a-alert
          v-if="isTestFailoverAction || isFailoverAction"
          type="info"
          show-icon
          :message="$t('message.dr.latest.checkpoint.automatic')" />
        <template v-if="isTestFailoverAction">
          <a-form-item :label="$t('label.dr.test.network.mode')">
            <a-select v-model:value="actionForm.networkmode">
              <a-select-option value="ISOLATED_NETWORK">{{ $t('label.dr.test.network.isolated') }}</a-select-option>
              <a-select-option value="PRODUCTION_NETWORK">{{ $t('label.dr.test.network.production') }}</a-select-option>
              <a-select-option value="NIC_DISABLED">{{ $t('label.dr.test.network.none') }}</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item
            :label="$t('label.dr.test.network')"
            required>
            <a-select
              v-model:value="actionForm.networkid"
              showSearch
              optionFilterProp="label"
              :loading="actionNetworkLoading"
              :placeholder="$t('message.dr.test.network.placeholder')">
              <a-select-option
                v-for="network in actionNetworkOptions"
                :key="network.optionKey"
                :value="network.value"
                :label="network.name">
                <span>{{ network.name || network.value }}</span>
                <span v-if="network.description" class="cross-dr-select-meta">{{ network.description }}</span>
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item :label="$t('label.dr.test.boot.validation.mode')">
            <a-select v-model:value="actionForm.bootvalidationmode">
              <a-select-option value="POWER_STATE_ONLY">{{ $t('label.dr.test.boot.validation.power.state') }}</a-select-option>
              <a-select-option value="QGA_REQUIRED">{{ $t('label.dr.test.boot.validation.qga') }}</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item :label="$t('label.dr.test.boot.timeout.seconds')">
            <a-input-number v-model:value="actionForm.boottimeoutseconds" style="width: 100%" :min="30" :max="1800" />
          </a-form-item>
        </template>
        <template v-if="isFailbackAction">
          <a-alert
            class="cross-dr-failback-alert"
            show-icon
            :type="actionPreflightLoading ? 'info' : (failbackPreflight.ready ? 'success' : 'error')"
            :message="failbackPreflight.ready
              ? $t('message.dr.failback.preflight.ready')
              : failbackPreflightMessage()" />
          <a-descriptions
            class="cross-dr-failback-route"
            size="small"
            :column="1"
            bordered>
            <a-descriptions-item :label="$t('label.dr.failback.active.site')">
              <div class="cross-dr-failback-site">
                <div class="cross-dr-failback-site__identity">
                  <strong>{{ failbackSiteValue('active', 'sitename') }}</strong>
                  <span>{{ failbackSiteValue('active', 'sitetype') }}</span>
                </div>
                <div class="cross-dr-failback-site__statuses">
                  <dr-status-pill :status="failbackSiteValue('active', 'sitehealth')" />
                  <dr-status-pill :status="failbackSiteValue('active', 'credentialstate')" />
                </div>
              </div>
            </a-descriptions-item>
            <a-descriptions-item :label="$t('label.dr.failback.destination.site')">
              <div class="cross-dr-failback-site">
                <div class="cross-dr-failback-site__identity">
                  <strong>{{ failbackSiteValue('destination', 'sitename') }}</strong>
                  <span>{{ failbackSiteValue('destination', 'sitetype') }}</span>
                </div>
                <div class="cross-dr-failback-site__statuses">
                  <dr-status-pill :status="failbackSiteValue('destination', 'sitehealth')" />
                  <dr-status-pill :status="failbackSiteValue('destination', 'credentialstate')" />
                </div>
              </div>
            </a-descriptions-item>
            <a-descriptions-item :label="$t('label.dr.failback.checkpoint')">
              <div class="cross-dr-failback-checkpoint">
                <strong>{{ failbackPreflight.checkpointsequence || '-' }}</strong>
                <span v-if="failbackPreflight.checkpointreadyat">{{ failbackPreflight.checkpointreadyat }}</span>
              </div>
            </a-descriptions-item>
          </a-descriptions>
          <a-alert
            class="cross-dr-failback-alert"
            show-icon
            type="info"
            :message="$t('message.dr.failback.reverse.mode.auto')" />
        </template>
        <a-form-item
          v-if="isAdoptAction"
          :label="$t('label.dr.replica')">
          <a-select v-model:value="actionForm.replicaid" allowClear>
            <a-select-option v-for="replica in actionReplicas" :key="replica.id" :value="replica.id">
              {{ replica.targetvmname || replica.targetexternalref || replica.id }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item
          v-if="isAdoptAction"
          :label="$t('label.dr.cleanup.transport')">
          <a-switch v-model:checked="actionForm.cleanuptransport" />
        </a-form-item>
        <a-form-item :label="$t('label.dr.action.reason')">
          <a-input v-model:value="actionForm.reason" />
        </a-form-item>
        <a-form-item
          v-if="selectedAction.danger"
          :label="$t('label.dr.action.acknowledgement')">
          <a-input v-model:value="actionForm.acknowledgement" />
        </a-form-item>
      </a-form>
      </div>
    </dr-form-modal>

    <dr-form-modal
      :visible="showGroupModal"
      :title="$t('label.dr.protection.group.action')"
      :loading="groupHistoryLoading || groupPreflightLoading"
      :confirm-loading="groupSubmitting"
      :ok-disabled="selectedRowKeys.length < 1 || groupPreflightLoading || groupPreflight.ready !== true"
      width="820"
      @cancel="closeGroupModal"
      @ok="submitGroupAction">
      <a-form layout="vertical" class="cross-dr-action-modal">
        <a-alert
          class="cross-dr-plan-section-alert"
          type="info"
          show-icon
          :message="$t('message.dr.protection.group.order')" />
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item :label="$t('label.name')" required>
              <a-input v-model:value="groupForm.name" :placeholder="$t('message.dr.protection.group.name.placeholder')" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item :label="$t('label.action')" required>
              <a-select v-model:value="groupForm.action" @change="refreshGroupPreflight">
                <a-select-option value="SYNC">{{ $t('label.dr.action.full.resync') }}</a-select-option>
                <a-select-option value="TEST_FAILOVER">{{ $t('label.dr.action.test.failover') }}</a-select-option>
                <a-select-option value="TEST_CLEANUP">{{ $t('label.dr.action.test.cleanup') }}</a-select-option>
                <a-select-option value="FAILOVER">{{ $t('label.dr.action.failover') }}</a-select-option>
                <a-select-option value="FAILBACK">{{ $t('label.dr.action.failback') }}</a-select-option>
                <a-select-option value="REPROTECT">{{ $t('label.dr.action.reprotect') }}</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item :label="$t('label.dr.protection.group.max.parallel')">
              <a-input-number v-model:value="groupForm.maxparallel" :min="1" :max="16" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item :label="$t('label.dr.protection.group.quiesce')">
              <a-switch v-model:checked="groupForm.quiescerequired" @change="refreshGroupPreflight" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-alert
          v-if="groupPreflight.ready === false"
          class="cross-dr-plan-section-alert"
          type="error"
          show-icon
          :message="$t('message.dr.protection.group.preflight.blocked')" />
        <a-table
          class="cross-dr-group-table"
          size="small"
          :pagination="false"
          :dataSource="groupPlanRows"
          :rowKey="record => record.id || record.planid">
          <a-table-column :title="$t('label.dr.protection.group.order')" key="order">
            <template #default="{ index }">{{ index + 1 }}</template>
          </a-table-column>
          <a-table-column :title="$t('label.name')" key="name">
            <template #default="{ record }">{{ record.planname || record.name }}</template>
          </a-table-column>
          <a-table-column :title="$t('label.state')" key="state">
            <template #default="{ record }"><status :text="record.planstate || effectivePlanState(record)" displayText /></template>
          </a-table-column>
          <a-table-column :title="$t('label.dr.readiness')" key="readiness">
            <template #default="{ record }">
              <dr-status-pill :status="record.eligible === true ? 'READY' : (record.eligible === false ? 'BLOCKED' : 'UNKNOWN')" />
              <div v-if="record.eligible === false" class="cross-dr-group-preflight-reason">
                {{ groupReasonText(record.reasoncode) }}
              </div>
            </template>
          </a-table-column>
        </a-table>
        <a-divider v-if="groupHistory.length" />
        <a-table
          v-if="groupHistory.length"
          class="cross-dr-group-table"
          size="small"
          :pagination="false"
          :dataSource="groupHistory"
          :rowKey="record => record.id">
          <a-table-column :title="$t('label.action')" dataIndex="action" key="action" />
          <a-table-column :title="$t('label.state')" dataIndex="state" key="state" />
          <a-table-column :title="$t('label.dr.protection.group.progress')" key="progress">
            <template #default="{ record }">{{ record.succeededcount || 0 }} / {{ record.totalcount || 0 }}</template>
          </a-table-column>
          <a-table-column :title="$t('label.created')" dataIndex="created" key="created" />
        </a-table>
      </a-form>
    </dr-form-modal>
  </div>
</template>

<script>
import DrCheckpointManager from '@/components/dr/DrCheckpointManager.vue'
import { h } from 'vue'
import { Checkbox, notification } from 'ant-design-vue'
import ActionButton from '@/components/view/ActionButton'
import Breadcrumb from '@/components/widgets/Breadcrumb'
import DrEventsTab from '@/views/infra/dr/DrEventsTab.vue'
import DrFormModal from '@/components/dr/DrFormModal.vue'
import DrPlanHistoryTab from '@/views/infra/dr/DrPlanHistoryTab.vue'
import DrPlanOverview from '@/views/infra/dr/DrPlanOverview.vue'
import DrProtectionInfoTab from '@/views/infra/dr/DrProtectionInfoTab.vue'
import DrResourceActionMenu from '@/components/dr/DrResourceActionMenu.vue'
import DrResourceContextMenu from '@/components/dr/DrResourceContextMenu.vue'
import DrResourceInfoCard from '@/components/dr/DrResourceInfoCard.vue'
import DrStatusPill from '@/components/dr/DrStatusPill.vue'
import ResourceLayout from '@/layouts/ResourceLayout'
import SearchFilter from '@/components/view/SearchFilter'
import SearchView from '@/components/view/SearchView'
import Status from '@/components/widgets/Status'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { configureDrProtectionGroup, createDrPlan, deleteDrPlan, discoverDrPlanInventory, getDrFailbackPreflight, getDrPlan, getDrProtectionView, listDrPlans, listDrProtectionGroupRuns, listDrReplicas, listDrRuns, listDrSites, previewDrPlanSpec, previewDrProtectionGroupAction, refreshDrProtectionView, startDrAction, startDrProtectionGroupAction, updateDrPlan, waitForDrMutation } from '@/api/dr'
import { drActionReasonMessageKey, requiresDisasterFailover } from '@/utils/dr/actionAvailability'
import { DEFAULT_DR_PLAN_ACTIVE_SECTIONS, DR_PLAN_DIALOG_SECTIONS, drPlanSectionForValidation } from '@/utils/dr/planDialogSections'
import { buildActiveDrRunQuery, buildChangedPlanPayload, findActiveDrRun, requiresSourceHardwareRefresh, resolveDrSourceDiskFormat, resolveDrSourceDiskType, resolveTargetComputeSizingValue, targetComputeFields, invalidTargetComputeField, updateAutoGeneratedDiskNames } from '@/utils/dr/planForm'
import { isActiveDrRun, isActiveDrSyncCycle, reconcileDrPlanProjection, reconcileDrRunProjection, resolveDrPlanState, resolveDrReadinessState } from '@/utils/dr/planState'
import { buildDrPlanActions } from '@/utils/dr/resourceActions'
import { mixinDevice } from '@/utils/mixin.js'
import { ApartmentOutlined, BranchesOutlined, ClockCircleOutlined, DesktopOutlined, GlobalOutlined } from '@ant-design/icons-vue'

export default {
  name: 'DrPlanList',
  components: {
    DrCheckpointManager,
    ActionButton,
    Breadcrumb,
    DrEventsTab,
    DrFormModal,
    DrPlanHistoryTab,
    DrPlanOverview,
    DrProtectionInfoTab,
    DrResourceActionMenu,
    DrResourceContextMenu,
    DrResourceInfoCard,
    DrStatusPill,
    ApartmentOutlined,
    BranchesOutlined,
    GlobalOutlined,
    ResourceLayout,
    SearchFilter,
    SearchView,
    Status,
    TooltipLabel
  },
  mixins: [mixinDevice],
  data () {
    return {
      loading: false,
      createLoading: false,
      actionLoading: '',
      actionLoadingPlanId: '',
      plans: [],
      listTotal: 0,
      sites: [],
      detailPlan: {},
      listLoadWarning: '',
      detailLoadWarning: '',
      detailRuns: [],
      protectionView: {},
      protectionSnapshot: {},
      protectionRefreshing: false,
      activeTab: this.normalizeDetailTab(this.$route.query.tab),
      showCreateModal: false,
      showActionModal: false,
      planFormMode: 'create',
      editingPlan: {},
      editInitialPayload: {},
      contextMenuVisible: false,
      contextMenuMode: 'single',
      contextMenuPlan: {},
      contextMenuPosition: { x: 0, y: 0 },
      actionSubmitting: false,
      actionPreflightLoading: false,
      selectedAction: {},
      selectedActionPlan: {},
      actionRequestKey: '',
      failbackPreflight: {},
      actionReplicas: [],
      actionForm: this.defaultActionForm(),
      runtimePollTimer: null,
      runtimePollInFlight: false,
      activeRuntimePollIntervalMs: 2000,
      steadyProtectionPollIntervalMs: 10000,
      searchQuery: '',
      searchParams: {},
      selectedRowKeys: [],
      showGroupModal: false,
      groupSubmitting: false,
      groupHistoryLoading: false,
      groupHistory: [],
      groupPreflightLoading: false,
      groupPreflight: {},
      groupPreflightRequest: 0,
      trackedGroupRun: {},
      groupRunPollTimer: null,
      groupRunPollDeadline: 0,
      groupForm: {
        name: '',
        action: 'SYNC',
        maxparallel: 2,
        quiescerequired: false
      },
      selectedColumns: ['name', 'state', 'direction', 'sourcesiteid', 'targetsiteid', 'displayrposeconds', 'enginetype'],
      page: 1,
      pageSize: this.$store.getters.defaultListViewPageSize || 20,
      filters: {
        state: undefined,
        direction: undefined
      },
      createForm: this.defaultCreateForm(),
      sourceWorkloadLoading: false,
      sourceWorkloadOptions: [],
      sourceWorkloadMessage: '',
      sourceWorkloadSearchTimer: null,
      sourceWorkloadKeyword: '',
      sourceWorkloadLoadedKey: '',
      sourceWorkerHostOptions: [],
      targetWorkerHostOptions: [],
      coordinatorWorkerHostOptions: [],
      targetZoneOption: null,
      targetServiceOfferingOptions: [],
      targetDiskOfferingOptions: [],
      targetStorageOptions: [],
      targetComputeOptions: [],
      targetNetworkOptions: [],
      actionNetworkOptions: [],
      actionNetworkLoading: false,
      targetFolderOptions: [],
      sourceDiskOptions: [],
      sourceNicOptions: [],
      sourceHardware: {},
      resolvedTargetHardware: {},
      diskMappingRows: [],
      inventoryBlockingReasons: [],
      inventoryWarnings: [],
      planSectionActiveKeys: [...DEFAULT_DR_PLAN_ACTIVE_SECTIONS],
      directions: ['KVM_TO_KVM', 'KVM_TO_VMWARE', 'VMWARE_TO_VMWARE', 'VMWARE_TO_KVM'],
      engineOptions: [
        { value: 'FTCTL_DR', label: 'label.dr.engine.ftctl.dr' },
        { value: 'FTCTL', label: 'label.dr.engine.ftctl' },
        { value: 'VMWARE_PHASE1', label: 'label.dr.engine.vmware.phase1' },
        { value: 'V2K', label: 'label.dr.engine.v2k.migration.only', disabled: true }
      ],
      planStates: ['CREATED', 'ENABLED', 'SYNCING', 'READY', 'TESTING', 'FAILED_OVER', 'FAILED_OVER_UNPROTECTED', 'FAILBACK_READY', 'COMMIT_VERIFYING', 'REPROTECTING', 'PAUSED', 'UNPROTECTED', 'ERROR'],
      columns: [
        { key: 'name', title: this.$t('label.name'), dataIndex: 'name', sorter: this.sortBy('name') },
        { key: 'state', title: this.$t('label.state'), dataIndex: 'state', sorter: this.sortBy('state') },
        { key: 'direction', title: this.$t('label.dr.direction'), dataIndex: 'direction', sorter: this.sortBy('direction') },
        { key: 'sourcesiteid', title: this.$t('label.dr.source.site'), dataIndex: 'sourcesiteid', sorter: this.sortBy('sourcesiteid') },
        { key: 'targetsiteid', title: this.$t('label.dr.target.site'), dataIndex: 'targetsiteid', sorter: this.sortBy('targetsiteid') },
        { key: 'displayrposeconds', title: this.$t('label.dr.target.rpo'), dataIndex: 'displayrposeconds', sorter: this.sortBy('displayrposeconds') },
        { key: 'enginetype', title: this.$t('label.dr.engine'), dataIndex: 'enginetype', sorter: this.sortBy('enginetype') }
      ]
    }
  },
  computed: {
    targetSizingFields () {
      if (!this.directionUsesKvmTarget || !this.createForm.targetcomputeref) return []
      const compute = this.findOptionByValue(this.targetComputeOptions, this.createForm.targetcomputeref)
      return compute ? targetComputeFields(compute.detailsObject || {}) : []
    },
    hasListApi () {
      return 'listDrPlans' in this.$store.getters.apis
    },
    detailId () {
      return this.$route.params.id || ''
    },
    breadcrumbResource () {
      return this.detailId ? this.detailPlan : {}
    },
    siteById () {
      return this.sites.reduce((map, site) => {
        map[site.id] = site
        return map
      }, {})
    },
    planInfoTags () {
      const plan = this.detailPlan || {}
      return [
        {
          key: 'direction',
          label: plan.direction ? this.$t(this.directionLabel(plan.direction)) : '',
          visible: !!plan.direction
        },
        {
          key: 'engine',
          label: plan.enginetype ? this.$t(this.engineLabel(plan.enginetype)) : '',
          visible: !!plan.enginetype
        }
      ]
    },
    planSummaryFields () {
      const plan = this.detailPlan || {}
      const sourceVm = plan.sourcevmid || plan.sourceexternalref
      return [
        {
          key: 'state',
          label: this.$t('label.status'),
          component: Status,
          props: { text: this.effectivePlanState(plan), displayText: true },
          visible: !!(plan.protectionstate || plan.effectivestate || plan.state)
        },
        {
          key: 'readiness',
          label: this.$t('label.dr.readiness'),
          component: Status,
          props: { text: this.effectivePlanReadiness(plan), displayText: true },
          visible: !!plan.readinessstate
        },
        {
          key: 'targetMaterialized',
          label: this.$t('label.dr.target.materialized'),
          component: Status,
          props: { text: plan.targetmaterialized ? 'READY' : 'PENDING', displayText: true },
          visible: plan.targetmaterialized !== undefined
        },
        {
          key: 'id',
          label: this.$t('label.id'),
          value: plan.id,
          icon: 'barcode-outlined',
          copy: true,
          copyTooltip: this.$t('label.copyid'),
          copyResource: String(plan.id || ''),
          copyLabel: true,
          visible: !!plan.id
        },
        {
          key: 'sourceSite',
          label: this.$t('label.dr.source.site'),
          value: this.siteName(plan.sourcesiteid),
          iconComponent: GlobalOutlined,
          visible: !!plan.sourcesiteid
        },
        {
          key: 'targetSite',
          label: this.$t('label.dr.target.site'),
          value: this.siteName(plan.targetsiteid),
          iconComponent: GlobalOutlined,
          visible: !!plan.targetsiteid
        },
        {
          key: 'sourceVm',
          label: this.$t('label.dr.source.vm'),
          value: sourceVm,
          route: plan.sourcevmid ? { path: '/vm/' + plan.sourcevmid } : null,
          iconComponent: DesktopOutlined,
          copyLabel: !plan.sourcevmid,
          visible: !!sourceVm
        },
        {
          key: 'rpo',
          label: this.$t('label.dr.rpo'),
          value: this.formatSeconds(plan.rposeconds),
          iconComponent: ClockCircleOutlined
        },
        {
          key: 'rto',
          label: this.$t('label.dr.rto'),
          value: this.formatSeconds(plan.rtoseconds),
          iconComponent: ClockCircleOutlined
        },
        {
          key: 'currentRun',
          label: this.$t('label.dr.runs'),
          component: Status,
          props: { text: this.currentRun.state || '', displayText: true },
          visible: !!this.currentRun.id
        }
      ]
    },
    searchFilters () {
      return ['direction', 'enginetype']
    },
    directionOptions () {
      return this.directions.map(direction => ({
        value: direction,
        label: this.directionLabel(direction)
      }))
    },
    selectedSourceSite () {
      return this.siteById[this.createForm.sourcesiteid] || {}
    },
    selectedTargetSite () {
      return this.siteById[this.createForm.targetsiteid] || {}
    },
    canDiscoverSourceWorkloads () {
      return !!(this.createForm.sourcesiteid && this.createForm.targetsiteid && this.createForm.direction)
    },
    directionUsesVmwareTarget () {
      return String(this.createForm.direction || '').toUpperCase().endsWith('_VMWARE')
    },
    directionUsesKvmSource () {
      return String(this.createForm.direction || '').toUpperCase().startsWith('KVM_')
    },
    directionUsesKvmTarget () {
      return String(this.createForm.direction || '').toUpperCase().endsWith('_KVM')
    },
    remoteSourceWorkerLabel () {
      if (!this.directionUsesKvmSource || this.sourceWorkerHostOptions.length > 0) {
        return ''
      }
      const name = this.sourceHardware.sourceHostName || this.sourceHardware.sourcehostname || ''
      const uuid = this.sourceHardware.sourceHostUuid || this.sourceHardware.sourcehostuuid || ''
      if (name && uuid) {
        return `${name} (${uuid})`
      }
      return name || uuid
    },
    requiresDiskMapping () {
      return !this.createForm.expertjson && this.directionUsesKvmTarget && !!(this.createForm.sourcevmid || this.createForm.sourceexternalref)
    },
    sourceWorkloadPlaceholder () {
      if (!this.createForm.sourcesiteid || !this.createForm.targetsiteid) {
        return this.$t('message.dr.plan.source.vm.placeholder.wait.site')
      }
      if (this.sourceWorkloadLoading) {
        return this.$t('message.dr.plan.source.vm.placeholder.loading')
      }
      return this.$t('message.dr.plan.source.vm.placeholder')
    },
    sourceWorkloadHelpText () {
      if (this.sourceWorkloadMessage) {
        return this.sourceWorkloadMessage
      }
      if (!this.canDiscoverSourceWorkloads) {
        return this.$t('message.dr.plan.source.vm.help.wait.site')
      }
      if (!this.sourceWorkloadLoading && this.sourceWorkloadOptions.length === 0 && this.sourceWorkloadLoadedKey) {
        return this.$t('message.dr.plan.source.vm.empty')
      }
      return ''
    },
    createPlanActions () {
      return [{
        api: 'createDrPlan',
        icon: 'plus-outlined',
        label: 'label.dr.plan.add',
        listView: true
      }]
    },
    planActions () {
      return buildDrPlanActions(this.detailId ? this.currentRun : {})
    },
    contextMenuActions () {
      if (this.contextMenuMode !== 'multiple') {
        return this.planActions
      }
      return [
        {
          api: 'startDrProtectionGroupAction',
          icon: 'apartment-outlined',
          label: 'label.dr.protection.group.action',
          group: 'MULTI',
          contextAction: 'openProtectionGroup'
        },
        {
          icon: 'close-outlined',
          label: 'label.dr.selection.clear',
          group: 'MULTI',
          contextAction: 'clearSelection'
        }
      ]
    },
    contextMenuResource () {
      return this.contextMenuMode === 'multiple' ? {} : this.contextMenuPlan
    },
    contextMenuTitle () {
      if (this.contextMenuMode === 'multiple') {
        return this.$t('label.dr.plan.selection', { count: this.selectedRowKeys.length })
      }
      return ''
    },
    planModalTitle () {
      return this.planFormMode === 'edit' ? this.$t('label.dr.plan.edit') : this.$t('label.dr.plan.add')
    },
    planModalWidth () {
      return this.device === 'mobile' ? 'calc(100vw - 24px)' : '1120px'
    },
    planSummaryItems () {
      const sourceSite = this.selectedSourceSite
      const targetSite = this.selectedTargetSite
      return [
        {
          key: 'name',
          label: this.$t('label.name'),
          value: this.createForm.name
        },
        {
          key: 'direction',
          label: this.$t('label.dr.direction'),
          value: this.createForm.direction ? this.$t(this.directionLabel(this.createForm.direction)) : ''
        },
        {
          key: 'sourceSite',
          label: this.$t('label.dr.source.site'),
          value: sourceSite.name || sourceSite.displaytext || sourceSite.id
        },
        {
          key: 'targetSite',
          label: this.$t('label.dr.target.site'),
          value: targetSite.name || targetSite.displaytext || targetSite.id
        },
        {
          key: 'sourceWorkload',
          label: this.$t('label.dr.source.vm'),
          value: this.createForm.sourceworkloadname || this.createForm.sourceexternalref || this.createForm.sourcevmid
        },
        {
          key: 'targetVmName',
          label: this.$t('label.dr.target.vm.name'),
          value: this.createForm.targetvmname
        },
        {
          key: 'sourceBoot',
          label: this.$t('label.dr.source.boot'),
          value: this.hardwareBootLabel(this.sourceHardware, true)
        },
        {
          key: 'targetBoot',
          label: this.$t('label.dr.target.boot'),
          value: this.hardwareBootLabel(this.effectiveTargetHardware, false)
        },
        {
          key: 'targetIo',
          label: this.$t('label.dr.target.io'),
          value: this.effectiveTargetHardware.ioPolicy || this.effectiveTargetHardware.iopolicy || 'io_uring'
        },
        {
          key: 'defaultStorage',
          label: this.$t('label.dr.default.target.storage'),
          value: this.optionDisplayName(this.targetStorageOptions, this.createForm.targetstorageref)
        }
      ]
    },
    effectiveTargetHardware () {
      if (Object.keys(this.resolvedTargetHardware || {}).length > 0) {
        return this.resolvedTargetHardware
      }
      const firmware = String(this.sourceHardware.firmware || '').toUpperCase()
      const secure = String(this.sourceHardware.secureBoot || this.sourceHardware.secureboot || '').toLowerCase() === 'true'
      if (!firmware) return {}
      return {
        bootType: firmware.includes('EFI') || secure ? 'UEFI' : 'BIOS',
        bootMode: firmware.includes('EFI') && secure ? 'SECURE' : 'LEGACY',
        ioPolicy: 'io_uring'
      }
    },
    diskMappingSummaryText () {
      const total = this.diskMappingRows.length
      const completed = this.diskMappingRows.filter(row => row.targetDiskName && row.targetDiskOfferingId && row.targetStorageRef).length
      return this.$t('message.dr.plan.disk.mapping.summary', { completed, total })
    },
    hasDiskLevelStorageAuthority () {
      return this.requiresDiskMapping &&
        this.diskMappingRows.length > 0 &&
        !this.diskMappingRows.some(row => !row.targetStorageRef)
    },
    columnSelectorColumns () {
      return this.columns.filter(column => !column.alwaysVisible)
    },
    tableColumns () {
      return [
        ...this.columns.filter(column => column.alwaysVisible || this.selectedColumns.includes(column.dataIndex)),
        {
          key: 'filtercolumn',
          dataIndex: 'filtercolumn',
          title: '',
          customFilterDropdown: true,
          width: 5
        }
      ]
    },
    listRowSelection () {
      return {
        selectedRowKeys: this.selectedRowKeys,
        onChange: this.onRowSelectionChange,
        columnWidth: 30
      }
    },
    selectedGroupPlans () {
      const plansById = new Map(this.plans.map(plan => [plan.id, plan]))
      return this.selectedRowKeys.map(id => plansById.get(id)).filter(Boolean)
    },
    groupPlanRows () {
      return Array.isArray(this.groupPreflight.plans) && this.groupPreflight.plans.length
        ? this.groupPreflight.plans
        : this.selectedGroupPlans
    },
    trackedGroupProgress () {
      const value = this.trackedGroupRun.progressjson || this.trackedGroupRun.progressJson
      if (!value) return {}
      if (typeof value === 'object') return value
      try {
        return JSON.parse(value)
      } catch (e) {
        return {}
      }
    },
    trackedGroupPlanResults () {
      const results = Array.isArray(this.trackedGroupProgress.plans) ? this.trackedGroupProgress.plans : []
      return results.map(record => {
        const plan = this.plans.find(item => String(item.id || '') === String(record.planId || ''))
        if (!plan) return record
        const currentRpoValue = plan.displayrposeconds ?? plan.rpoageseconds ?? plan.targetreadyrposeconds
        const currentRpo = currentRpoValue !== null && currentRpoValue !== undefined && currentRpoValue !== ''
          ? Number(currentRpoValue) : Number.NaN
        const targetRpo = plan.rposeconds !== null && plan.rposeconds !== undefined && plan.rposeconds !== ''
          ? Number(plan.rposeconds) : Number.NaN
        const errorCode = String(plan.lasterrorcode || '').toUpperCase()
        const resourceWaiting = ['DR_RESOURCE_BUSY', 'DR_NBD_CAPACITY_INVALID', 'DR_TARGET_EXPORT_UNAVAILABLE'].includes(errorCode)
        let continuousProtectionState = String(this.effectivePlanState(plan) || plan.state || 'PENDING').toUpperCase()
        if (resourceWaiting) {
          continuousProtectionState = 'WAITING_RESOURCE'
        } else if (Number.isFinite(currentRpo) && Number.isFinite(targetRpo) && currentRpo > targetRpo) {
          continuousProtectionState = 'DEGRADED'
        }
        return {
          ...record,
          continuousProtectionState,
          currentRpoSeconds: Number.isFinite(currentRpo) ? currentRpo : record.currentRpoSeconds,
          targetRpoSeconds: Number.isFinite(targetRpo) ? targetRpo : record.targetRpoSeconds,
          resourceWaiting
        }
      })
    },
    trackedGroupResultFinalizingCount () {
      const aggregate = Number(this.trackedGroupProgress.resultFinalizingCount)
      return Number.isFinite(aggregate)
        ? aggregate
        : this.trackedGroupPlanResults.filter(record => record.terminalizationState === 'RESULT_FINALIZING').length
    },
    trackedGroupConsistencyWarningCount () {
      const aggregate = Number(this.trackedGroupProgress.consistencyWarningCount)
      return Number.isFinite(aggregate)
        ? aggregate
        : this.trackedGroupPlanResults.filter(record => record.terminalizationState === 'CONSISTENCY_WARNING').length
    },
    trackedGroupProgressPercent () {
      const total = Number(this.trackedGroupRun.totalcount || this.trackedGroupProgress.total || 0)
      if (!total) return 0
      const terminal = this.trackedGroupPlanResults.filter(plan =>
        ['SUCCEEDED', 'FAILED', 'CANCELED', 'BLOCKED', 'SKIPPED'].includes(String(plan.state || '').toUpperCase())
      ).length
      return Math.min(100, Math.round((terminal / total) * 100))
    },
    filterValue () {
      return this.filters.state || 'all'
    },
    activeFiltersList () {
      const activeFilters = []
      if (this.filters.state) {
        activeFilters.push({ key: 'state', value: this.filters.state, isTag: false })
      }
      if (this.filters.direction) {
        activeFilters.push({ key: 'direction', value: this.filters.direction, isTag: false })
      }
      for (const key in this.searchParams) {
        const value = this.searchParams[key]
        if (value !== '' && value !== undefined && value !== null) {
          activeFilters.push({ key, value, isTag: false })
        }
      }
      return activeFilters
    },
    showSearchFilters () {
      const excludedKeys = ['page', 'pagesize', 'q', 'keyword', 'tags', 'projectid']
      return !this.detailId && this.activeFiltersList.some(f => !excludedKeys.includes(f.key))
    },
    filteredPlans () {
      return this.plans
    },
    normalizedPage () {
      const maxPage = Math.max(1, Math.ceil(this.listTotal / this.pageSize))
      return Math.min(this.page, maxPage)
    },
    pagedPlans () {
      return this.filteredPlans
    },
    currentRun () {
      const cachedActiveRun = this.protectionSnapshot.activeRun || {}
      if (Number(this.protectionSnapshot.version || 0) >= 4) {
        return isActiveDrRun(cachedActiveRun) ? cachedActiveRun : {}
      }
      return isActiveDrRun(cachedActiveRun)
        ? cachedActiveRun
        : (this.detailRuns.find(run => isActiveDrRun(run)) || {})
    },
    latestOperationRun () {
      return this.protectionSnapshot.latestOperationRun || this.detailRuns[0] || this.detailPlan.lastrun || {}
    },
    currentSyncCycle () {
      return this.protectionSnapshot.currentSyncCycle || {}
    },
    latestCompletedSyncCycle () {
      return this.protectionSnapshot.latestCompletedSyncCycle || {}
    },
    currentProtectionRuntime () {
      return this.protectionSnapshot.currentProtectionRuntime || {}
    },
    actionModalTitle () {
      return this.selectedAction.label ? this.$t(this.selectedAction.label) : this.$t('label.actions')
    },
    isFailoverAction () {
      return this.selectedAction.command === 'startDrFailover'
    },
    failoverRequiresDisasterMode () {
      return this.isFailoverAction && requiresDisasterFailover(this.selectedActionPlan)
    },
    isTestFailoverAction () {
      return this.selectedAction.command === 'startDrTestFailover'
    },
    isFullResyncAction () {
      return this.selectedAction.command === 'startDrSync'
    },
    isFailbackAction () {
      return this.selectedAction.command === 'startDrFailback'
    },
    canSubmitFailback () {
      return this.failbackPreflight.ready === true
    },
    isAdoptAction () {
      return this.selectedAction.command === 'adoptDrReplica'
    },
    isReleaseAction () {
      return this.selectedAction.command === 'releaseDrProtection'
    },
    releaseTargetAuthorityActive () {
      const activeSide = String(this.selectedActionPlan.activeside || this.selectedActionPlan.activeSide || '').toUpperCase()
      const state = String(this.selectedActionPlan.state || '').toUpperCase()
      return activeSide === 'TARGET' || state === 'FAILED_OVER' || state === 'FAILED_OVER_UNPROTECTED'
    },
    releaseDispositionMessage () {
      if (this.releaseTargetAuthorityActive) {
        return this.$t('message.dr.release.target.authority.retain')
      }
      return this.$t(this.actionForm.resourcedisposition === 'DELETE_STANDBY_REPLICA'
        ? 'message.dr.release.delete.effects'
        : 'message.dr.release.effects')
    },
    isReprotectAction () {
      return this.selectedAction.command === 'startDrReprotect'
    },
    pageSizeOptions () {
      return this.device === 'desktop' ? ['20', '50', '100', '200'] : ['10', '20', '50', '100', '200']
    }
  },
  watch: {
    '$route.path': function () {
      this.selectedRowKeys = []
      this.closeContextMenu()
      this.stopGroupRunPolling()
      this.fetchData()
    },
    '$route.query.tab': function () {
      this.activeTab = this.normalizeDetailTab(this.$route.query.tab)
    },
    'createForm.targetvmname': function (value, previousValue) {
      if (this.planFormMode !== 'create' || !Array.isArray(this.diskMappingRows) || !this.diskMappingRows.length) {
        return
      }
      this.diskMappingRows = updateAutoGeneratedDiskNames(this.diskMappingRows, previousValue, value)
    }
  },
  created () {
    this.fetchData()
  },
  mounted () {
    document.addEventListener('visibilitychange', this.onVisibilityChange)
  },
  beforeUnmount () {
    this.stopRuntimePolling()
    document.removeEventListener('visibilitychange', this.onVisibilityChange)
    if (this.sourceWorkloadSearchTimer) {
      clearTimeout(this.sourceWorkloadSearchTimer)
    }
    this.stopGroupRunPolling()
  },
  methods: {
    defaultCreateForm () {
      return {
        name: '',
        description: '',
        sourcesiteid: undefined,
        targetsiteid: undefined,
        direction: 'KVM_TO_KVM',
        sourcevmid: '',
        sourceexternalref: '',
        sourceworkloadvalue: undefined,
        sourceworkloadname: '',
        enginetype: 'FTCTL_DR',
        enginebindingtype: 'FTCTL_DR',
        enginebindingid: undefined,
        rposeconds: 300,
        rtoseconds: 300,
        sourceworkerhostid: '',
        targetworkerhostid: '',
        coordinatorworkerhostid: '',
        mappingjson: '',
        schedulejson: '',
        policyjson: '',
        quiescepolicyjson: '',
        diskmappingsjson: '',
        allowdraft: true,
        guidedplan: true,
        expertjson: false,
        targetvmname: '',
        targetzoneid: '',
        targetstorageref: '',
        targetcomputeref: '',
        targetcpunumber: undefined,
        targetcpuspeed: undefined,
        targetmemory: undefined,
        targetboottype: '',
        targetbootmode: '',
        targetrootdiskcontroller: '',
        targetdatadiskcontroller: '',
        targetiothreadsenabled: true,
        targetiopolicy: 'io_uring',
        targetnetworkref: '',
        targetfolderpath: '',
        consistencymode: 'CRASH_CONSISTENT',
        testnetworkmode: 'ISOLATED',
        testbootvalidationmode: 'POWER_STATE_ONLY',
        testboottimeoutseconds: 180,
        failoverpoweron: true,
        syncintervalseconds: 300,
        retentioncount: 24,
        bandwidthlimitmbps: undefined,
        retrycount: 3,
        startsync: false
      }
    },
    defaultActionForm () {
      return {
        reason: '',
        acknowledgement: '',
        force: true,
        disaster: false,
        sourceindependent: false,
        finalsync: true,
        skipsourcefencerequest: false,
        sourceisolationacknowledged: false,
        sourceisolationreason: '',
        remotemoldapiurl: '',
        remotemoldapikey: '',
        remotemoldsecretkey: '',
        replicaid: undefined,
        cleanuptransport: true,
        resourcedisposition: 'RETAIN_OPERATIONAL_VM',
        networkmode: 'ISOLATED_NETWORK',
        networkid: undefined,
        bootvalidationmode: 'POWER_STATE_ONLY',
        boottimeoutseconds: 180
      }
    },
    fetchData () {
      if (this.detailId) {
        return this.fetchDetail()
      } else {
        this.stopRuntimePolling()
        return this.fetchList()
      }
    },
    fetchSites () {
      if (!('listDrSites' in this.$store.getters.apis)) {
        this.sites = []
        return Promise.resolve()
      }
      return listDrSites().then(result => {
        this.sites = result.items || []
      })
    },
    changeSourceSite () {
      this.refreshDirectionFromSites()
      this.resetSourceWorkloads(true)
      this.ensureSourceWorkloads()
    },
    changeTargetSite () {
      this.refreshDirectionFromSites()
      this.resetSourceWorkloads(true)
      this.ensureSourceWorkloads()
    },
    refreshDirectionFromSites () {
      if (!this.createForm.sourcesiteid || !this.createForm.targetsiteid) {
        return
      }
      this.createForm.direction = this.directionForSites(this.selectedSourceSite, this.selectedTargetSite)
      this.onCreateEngineChange(this.createForm.enginetype)
    },
    directionForSites (sourceSite, targetSite) {
      const sourceVmware = this.isVmwareSite(sourceSite)
      const targetVmware = this.isVmwareSite(targetSite)
      if (sourceVmware && targetVmware) return 'VMWARE_TO_VMWARE'
      if (sourceVmware) return 'VMWARE_TO_KVM'
      if (targetVmware) return 'KVM_TO_VMWARE'
      return 'KVM_TO_KVM'
    },
    isVmwareSite (site) {
      return String(site?.hypervisortype || '').toUpperCase() === 'VMWARE'
    },
    resetSourceWorkloads (clearSelection = false) {
      this.sourceWorkloadOptions = []
      this.sourceWorkloadMessage = ''
      this.sourceWorkloadLoadedKey = ''
      this.sourceWorkerHostOptions = []
      this.targetWorkerHostOptions = []
      this.coordinatorWorkerHostOptions = []
      this.targetZoneOption = null
      this.targetServiceOfferingOptions = []
      this.targetDiskOfferingOptions = []
      this.targetStorageOptions = []
      this.targetComputeOptions = []
      this.targetNetworkOptions = []
      this.targetFolderOptions = []
      this.sourceDiskOptions = []
      this.sourceNicOptions = []
      this.sourceHardware = {}
      this.resolvedTargetHardware = {}
      this.diskMappingRows = []
      this.inventoryBlockingReasons = []
      this.inventoryWarnings = []
      if (clearSelection) {
        this.createForm.sourceworkloadvalue = undefined
        this.createForm.sourceworkloadname = ''
        this.createForm.sourcevmid = ''
        this.createForm.sourceexternalref = ''
        this.createForm.targetvmname = ''
        this.createForm.targetzoneid = ''
        this.createForm.targetstorageref = ''
        this.createForm.targetcomputeref = ''
        this.createForm.targetcpunumber = undefined
        this.createForm.targetcpuspeed = undefined
        this.createForm.targetmemory = undefined
        this.createForm.targetnetworkref = ''
        this.createForm.targetfolderpath = ''
        this.createForm.diskmappingsjson = ''
      }
    },
    sourceWorkloadInventoryKey (keyword = this.sourceWorkloadKeyword) {
      return [
        this.createForm.sourcesiteid || '',
        this.createForm.targetsiteid || '',
        this.createForm.direction || '',
        this.createForm.sourcevmid || this.createForm.sourceexternalref || '',
        keyword || ''
      ].join('|')
    },
    ensureSourceWorkloads () {
      if (this.planFormMode !== 'create' || !this.canDiscoverSourceWorkloads) {
        return
      }
      if (this.sourceWorkloadLoadedKey !== this.sourceWorkloadInventoryKey()) {
        this.fetchSourceWorkloads()
      }
    },
    searchSourceWorkloads (keyword) {
      this.sourceWorkloadKeyword = keyword || ''
      if (this.sourceWorkloadSearchTimer) {
        clearTimeout(this.sourceWorkloadSearchTimer)
      }
      this.sourceWorkloadSearchTimer = setTimeout(() => {
        this.fetchSourceWorkloads(true)
      }, 350)
    },
    fetchSourceWorkloads (force = false) {
      if (!('discoverDrPlanInventory' in this.$store.getters.apis) || !this.canDiscoverSourceWorkloads) {
        return Promise.resolve()
      }
      const inventoryKey = this.sourceWorkloadInventoryKey()
      if (!force && this.sourceWorkloadLoadedKey === inventoryKey) {
        return Promise.resolve()
      }
      this.sourceWorkloadLoading = true
      this.sourceWorkloadMessage = ''
      return discoverDrPlanInventory({
        sourcesiteid: this.createForm.sourcesiteid,
        targetsiteid: this.createForm.targetsiteid,
        sourcevmid: this.createForm.sourcevmid || undefined,
        sourceexternalref: this.createForm.sourceexternalref || undefined,
        keyword: this.sourceWorkloadKeyword || undefined,
        includeplacement: true,
        includedisks: !!(this.createForm.sourcevmid || this.createForm.sourceexternalref),
        includenetworks: true
      }).then(result => {
        this.createForm.direction = result.direction || this.createForm.direction
        this.sourceWorkloadOptions = this.normalizeSourceWorkloads(result.sourceworkloads || [])
        this.sourceWorkerHostOptions = this.normalizeInventoryOptions(result.sourceworkerhosts || [])
        this.targetWorkerHostOptions = this.normalizeInventoryOptions(result.targetworkerhosts || [])
        this.coordinatorWorkerHostOptions = this.normalizeInventoryOptions(result.coordinatorworkerhosts || [])
        this.targetZoneOption = result.targetzone || null
        this.createForm.targetzoneid = this.targetZoneOption?.value || this.createForm.targetzoneid
        this.targetStorageOptions = this.normalizeInventoryOptions(result.targetstorageoptions || [])
        this.targetServiceOfferingOptions = this.normalizeInventoryOptions(result.targetserviceofferings || [])
        this.targetDiskOfferingOptions = this.normalizeInventoryOptions(result.targetdiskofferings || [])
        this.targetComputeOptions = this.targetServiceOfferingOptions.length > 0
          ? this.targetServiceOfferingOptions
          : this.normalizeInventoryOptions(result.targetcomputeoptions || [])
        this.targetNetworkOptions = this.normalizeInventoryOptions(result.targetnetworkoptions || [])
        this.targetFolderOptions = this.normalizeInventoryOptions(result.targetfolderoptions || [])
        this.sourceDiskOptions = this.normalizeInventoryOptions(result.sourcedisks || [])
        this.sourceNicOptions = this.normalizeInventoryOptions(result.sourcenics || [])
        this.sourceHardware = this.parseOptionDetails(result.sourcehardware)
        this.resolvedTargetHardware = {}
        this.inventoryBlockingReasons = result.blockingreasons || []
        this.inventoryWarnings = result.warnings || []
        if (this.sourceDiskOptions.length > 0) {
          this.rebuildDiskMappingRows()
        }
        this.applyDefaultGuidedSelections()
        this.sourceWorkloadLoadedKey = inventoryKey
        if (result.healthstate && result.healthstate !== 'CONNECTED') {
          this.sourceWorkloadMessage = result.message || this.$t('message.dr.plan.source.vm.discovery.failed')
        }
      }).catch(error => {
        this.sourceWorkloadOptions = []
        this.sourceWorkerHostOptions = []
        this.targetWorkerHostOptions = []
        this.coordinatorWorkerHostOptions = []
        this.targetZoneOption = null
        this.targetServiceOfferingOptions = []
        this.targetDiskOfferingOptions = []
        this.targetStorageOptions = []
        this.targetComputeOptions = []
        this.targetNetworkOptions = []
        this.targetFolderOptions = []
        this.sourceDiskOptions = []
        this.sourceNicOptions = []
        this.inventoryBlockingReasons = []
        this.inventoryWarnings = []
        this.sourceWorkloadLoadedKey = ''
        this.sourceWorkloadMessage = error?.response?.data?.errorresponse?.errortext || error?.message || this.$t('message.dr.plan.source.vm.discovery.failed')
      }).finally(() => {
        this.sourceWorkloadLoading = false
      })
    },
    normalizeSourceWorkloads (workloads) {
      return (Array.isArray(workloads) ? workloads : [workloads]).filter(Boolean).map((workload, index) => {
        const optionKey = [
          workload.referencetype || 'EXTERNAL_REF',
          workload.value || workload.externalref || workload.externalid || workload.id || index
        ].join(':')
        return Object.assign({}, workload, { optionKey })
      }).map(workload => {
        return Object.assign({}, workload, {
          detailsObject: this.parseOptionDetails(workload.details)
        })
      })
    },
    normalizeInventoryOptions (options) {
      return (Array.isArray(options) ? options : [options]).filter(Boolean).map((option, index) => {
        const optionKey = [
          option.referencetype || option.type || 'OPTION',
          option.value || option.externalid || option.localid || option.id || index
        ].join(':')
        return Object.assign({}, option, {
          optionKey,
          detailsObject: this.parseOptionDetails(option.details)
        })
      })
    },
    normalizeDiskSizeBytes (value) {
      const parsed = Number(value)
      return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 0
    },
    sourceDiskSizeBytes (disk) {
      const details = (disk && disk.detailsObject) || {}
      return this.normalizeDiskSizeBytes(details.sizeBytes || details.capacityBytes || details.capacity || disk.sizeBytes || disk.capacityBytes || disk.capacity || '')
    },
    sourceDiskPath (disk) {
      const details = (disk && disk.detailsObject) || {}
      return details.path || details.vmdkFile || details.backingFile || disk.description || ''
    },
    sourceDiskType (disk) {
      const details = (disk && disk.detailsObject) || {}
      const path = this.sourceDiskPath(disk)
      return resolveDrSourceDiskType(details, path)
    },
    sourceDiskFormat (disk) {
      const details = (disk && disk.detailsObject) || {}
      return resolveDrSourceDiskFormat(details, this.sourceDiskPath(disk), this.directionUsesKvmSource)
    },
    sourceDiskRef (disk, index) {
      const details = (disk && disk.detailsObject) || {}
      return details.diskRef || disk.value || disk.externalid || disk.externalId || disk.id || String(index)
    },
    sourceDiskControllerBusNumber (disk) {
      const details = (disk && disk.detailsObject) || {}
      return details.controllerBusNumber || details.controllerBus || details.bus || ''
    },
    sourceDiskUnitNumber (disk) {
      const details = (disk && disk.detailsObject) || {}
      return details.unitNumber || details.unit || ''
    },
    sourceDiskCbtId (disk) {
      const details = (disk && disk.detailsObject) || {}
      if (details.cbtDiskId) {
        return details.cbtDiskId
      }
      const bus = this.sourceDiskControllerBusNumber(disk)
      const unit = this.sourceDiskUnitNumber(disk)
      return bus !== '' && unit !== '' ? `scsi${bus}:${unit}` : ''
    },
    sourceDiskDeviceKey (disk) {
      const details = (disk && disk.detailsObject) || {}
      return details.deviceKey || details.key || details.diskRef || disk.externalid || disk.externalId || disk.id || ''
    },
    sourceDiskControllerType (disk) {
      const details = (disk && disk.detailsObject) || {}
      const controller = details.controller || {}
      return details.sourceController || details.controllerType || controller.type || controller.name || (typeof details.controller === 'string' ? details.controller : '') || ''
    },
    formatBytes (value) {
      const bytes = this.normalizeDiskSizeBytes(value)
      if (!bytes) {
        return '-'
      }
      const gib = bytes / 1024 / 1024 / 1024
      return gib >= 1 ? `${gib.toFixed(gib >= 10 ? 0 : 1)} GiB` : `${bytes} B`
    },
    storageOptionForRef (storageRef) {
      return this.findOptionByValue(this.targetStorageOptions, storageRef) || {}
    },
    targetDiskTypeForStorage (storageRef) {
      const option = this.storageOptionForRef(storageRef)
      const details = option.detailsObject || {}
      const text = [
        details.storagePoolType,
        details.poolType,
        details.type,
        option.type,
        option.description,
        option.name,
        option.value
      ].filter(Boolean).join(' ').toLowerCase()
      return text.includes('rbd') ? 'rbd' : 'file'
    },
    targetDiskFormatForStorage (storageRef) {
      return this.targetDiskTypeForStorage(storageRef) === 'rbd' ? 'raw' : 'qcow2'
    },
    parseOptionDetails (details) {
      if (!details) {
        return {}
      }
      if (typeof details === 'object') {
        return details
      }
      try {
        return JSON.parse(details)
      } catch (e) {
        return {}
      }
    },
    hardwareBootLabel (hardware, source) {
      if (!hardware || Object.keys(hardware).length === 0) return ''
      const type = hardware.firmware || hardware.bootType || hardware.boottype || ''
      const secureValue = hardware.secureBoot ?? hardware.secureboot
      const mode = hardware.bootMode || hardware.bootmode || (String(secureValue).toLowerCase() === 'true' ? 'SECURE' : 'LEGACY')
      if (!type) return ''
      return source ? `${type} / ${mode === 'SECURE' ? 'Secure Boot' : mode}` : `${type} / ${mode}`
    },
    changeSourceWorkload (optionKey) {
      const workload = this.sourceWorkloadOptions.find(item => item.optionKey === optionKey)
      this.createForm.sourceworkloadvalue = optionKey
      this.createForm.sourceworkloadname = workload?.name || ''
      this.createForm.sourcevmid = ''
      this.createForm.sourceexternalref = ''
      this.sourceDiskOptions = []
      this.sourceNicOptions = []
      this.diskMappingRows = []
      this.createForm.diskmappingsjson = ''
      if (!workload) {
        return
      }
      if ((workload.referencetype || '').toUpperCase() === 'CLOUD_VM_ID') {
        this.createForm.sourcevmid = workload.value || workload.externalid || workload.id
      } else {
        this.createForm.sourceexternalref = workload.externalref || workload.value || workload.externalid || workload.id
      }
      if (!this.createForm.targetvmname) {
        this.createForm.targetvmname = this.defaultTargetVmName(workload)
      }
      this.applyDefaultTargetComputeSizing(workload, true)
      this.createForm.diskmappingsjson = this.readDiskMappingsJson(workload.mappingjson || workload.mappingJson || '')
      if (this.createForm.diskmappingsjson) {
        this.diskMappingRows = this.diskRowsFromJson(this.createForm.diskmappingsjson)
        this.diskMappingRows = updateAutoGeneratedDiskNames(
          this.diskMappingRows,
          this.defaultTargetVmName(workload),
          this.createForm.targetvmname)
        this.createForm.diskmappingsjson = this.buildDiskMappingsJson()
      }
      this.fetchSourceWorkloads(true)
    },
    defaultTargetVmName (workload = {}) {
      const sourceName = workload.name || workload.displayname || workload.value || workload.externalref || this.createForm.sourceexternalref || this.createForm.sourcevmid
      return sourceName ? `${sourceName}-dr` : ''
    },
    applyDefaultGuidedSelections () {
      this.autoSelectSingleOption('targetstorageref', this.targetStorageOptions)
      this.autoSelectSingleOption('targetcomputeref', this.targetComputeOptions)
      this.autoSelectSingleOption('targetnetworkref', this.targetNetworkOptions)
      this.applyDefaultTargetComputeSizing()
      this.applyDefaultDiskMappingSelections()
    },
    autoSelectSingleOption (field, options) {
      const selectable = (options || []).filter(option => option.selectable !== false)
      if (!this.createForm[field] && selectable.length === 1) {
        this.createForm[field] = selectable[0].value
      }
    },
    findOptionByValue (options, value) {
      return (options || []).find(option => option.value === value || option.id === value || option.externalid === value || option.localid === value) || null
    },
    optionDisplayName (options, value) {
      const option = this.findOptionByValue(options, value)
      return option ? (option.name || option.label || option.value) : value
    },
    changeTargetCompute () {
      this.applyDefaultTargetComputeSizing(this.selectedSourceWorkload(), false)
    },
    selectedSourceWorkload () {
      return this.sourceWorkloadOptions.find(item => item.optionKey === this.createForm.sourceworkloadvalue) || {}
    },
    applyDefaultTargetComputeSizing (workload = this.selectedSourceWorkload(), preserveExisting = true) {
      if (!this.directionUsesKvmTarget || !this.createForm.targetcomputeref) {
        this.createForm.targetcpunumber = undefined
        this.createForm.targetcpuspeed = undefined
        this.createForm.targetmemory = undefined
        return
      }
      const compute = this.findOptionByValue(this.targetComputeOptions, this.createForm.targetcomputeref) || {}
      const details = compute.detailsObject || {}
      const sourceDetails = (workload && workload.detailsObject) || {}
      const targetCpuNumber = this.resolveTargetComputeInteger(
        details.cpu,
        details.requiresCpuNumber,
        sourceDetails.cpuCount || sourceDetails.cpuNumber || sourceDetails.cpu,
        details.mincpunumber,
        details.maxcpunumber)
      const targetMemory = this.resolveTargetComputeInteger(
        details.memoryMb,
        details.requiresMemory,
        sourceDetails.memoryMiB || sourceDetails.memoryMb || sourceDetails.memory,
        details.minmemory,
        details.maxmemory)
      const targetCpuSpeed = this.resolveTargetComputeInteger(
        details.speed,
        details.requiresCpuSpeed,
        sourceDetails.cpuSpeed || sourceDetails.speed,
        undefined,
        undefined)
      this.createForm.targetcpunumber = resolveTargetComputeSizingValue(
        targetCpuNumber, this.createForm.targetcpunumber, preserveExisting && !this.positiveInteger(details.cpu))
      this.createForm.targetmemory = resolveTargetComputeSizingValue(
        targetMemory, this.createForm.targetmemory, preserveExisting && !this.positiveInteger(details.memoryMb))
      this.createForm.targetcpuspeed = resolveTargetComputeSizingValue(
        targetCpuSpeed, this.createForm.targetcpuspeed, preserveExisting && !this.positiveInteger(details.speed))
    },
    resolveTargetComputeInteger (offeringValue, required, sourceValue, minValue, maxValue) {
      const fixed = this.positiveInteger(offeringValue)
      if (fixed) {
        return fixed
      }
      if (!this.truthyValue(required)) {
        return undefined
      }
      const min = this.positiveInteger(minValue)
      const max = this.positiveInteger(maxValue)
      const candidate = this.positiveInteger(sourceValue) || min
      if (!candidate) {
        return undefined
      }
      if (min && candidate < min) {
        return min
      }
      if (max && candidate > max) {
        return max
      }
      return candidate
    },
    positiveInteger (value) {
      const parsed = Number(value)
      return Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : undefined
    },
    truthyValue (value) {
      return value === true || String(value || '').toLowerCase() === 'true'
    },
    applyDefaultStorageToDiskRows () {
      if (!this.createForm.targetstorageref || !this.diskMappingRows.length) {
        return
      }
      this.diskMappingRows = this.diskMappingRows.map(row => Object.assign({}, row, {
        targetStorageRef: this.createForm.targetstorageref
      }))
      this.createForm.diskmappingsjson = this.buildDiskMappingsJson()
    },
    rebuildDiskMappingRows () {
      const existingBySource = this.diskMappingRows.reduce((map, row) => {
        map[row.sourceDiskRef || row.sourcePath || row.sourceLabel] = row
        return map
      }, {})
      this.diskMappingRows = this.sourceDiskOptions.map((disk, index) => {
        const sourceDiskRef = this.sourceDiskRef(disk, index)
        const sourcePath = this.sourceDiskPath(disk)
        const existing = existingBySource[sourceDiskRef] || existingBySource[sourcePath] || {}
        return Object.assign({
          sourceDiskRef,
          sourcePath,
          sourceType: this.sourceDiskType(disk),
          sourceFormat: this.sourceDiskFormat(disk),
          sourceLabel: disk.name || `Disk ${index + 1}`,
          cbtDiskId: this.sourceDiskCbtId(disk),
          sourceDiskKey: this.sourceDiskDeviceKey(disk),
          sourceController: this.sourceDiskControllerType(disk),
          controllerBusNumber: this.sourceDiskControllerBusNumber(disk),
          unitNumber: this.sourceDiskUnitNumber(disk),
          capacityBytes: this.sourceDiskSizeBytes(disk),
          targetDiskName: `${this.createForm.targetvmname || 'dr-target'}-disk-${index}`,
          targetDiskOfferingId: '',
          targetStorageRef: this.createForm.targetstorageref || ''
        }, existing)
      })
      this.diskMappingRows = updateAutoGeneratedDiskNames(
        this.diskMappingRows,
        this.defaultTargetVmName(this.selectedSourceWorkload()),
        this.createForm.targetvmname)
      this.applyDefaultDiskMappingSelections()
      this.createForm.diskmappingsjson = this.buildDiskMappingsJson()
    },
    applyDefaultDiskMappingSelections () {
      if (!this.diskMappingRows.length) {
        return
      }
      const diskOfferings = (this.targetDiskOfferingOptions || []).filter(option => option.selectable !== false)
      const storages = (this.targetStorageOptions || []).filter(option => option.selectable !== false)
      this.diskMappingRows.forEach(row => {
        if (!row.targetDiskOfferingId && diskOfferings.length === 1) {
          row.targetDiskOfferingId = diskOfferings[0].value
        }
        if (!row.targetStorageRef && storages.length === 1) {
          row.targetStorageRef = storages[0].value
        }
      })
    },
    diskRowsFromJson (json) {
      try {
        const rows = JSON.parse(json)
        return (Array.isArray(rows) ? rows : []).map((disk, index) => {
          const source = disk.source || {}
          const target = disk.target || {}
          return {
            sourceDiskRef: source.diskRef || disk.sourceRef || disk.sourceDiskRef || String(index),
            sourcePath: source.vmdkPath || source.path || disk.sourcePath || '',
            sourceType: source.type || source.sourceType || disk.sourceType || '',
            sourceFormat: source.format || source.sourceFormat || disk.sourceFormat || '',
            sourceLabel: source.label || disk.label || `Disk ${index + 1}`,
            cbtDiskId: source.cbtDiskId || disk.cbtDiskId || disk.sourceCbtDiskId || '',
            sourceDiskKey: source.deviceKey || disk.sourceDiskKey || disk.deviceKey || '',
            sourceController: source.controllerType || source.sourceController || disk.sourceController || disk.controllerType || '',
            controllerBusNumber: source.controllerBusNumber || disk.controllerBusNumber || disk.controllerBus || '',
            unitNumber: source.unitNumber || disk.unitNumber || disk.unit || '',
            capacityBytes: this.normalizeDiskSizeBytes(source.sizeBytes || source.capacityBytes || source.capacity || disk.sizeBytes || disk.capacityBytes || disk.capacity || ''),
            targetDiskName: target.name || disk.targetRef || disk.targetDisk || `dr-disk-${index}`,
            targetDiskOfferingId: target.diskOfferingId || disk.targetDiskOfferingId || disk.diskOfferingId || '',
            targetStorageRef: target.storageRef || disk.targetStorageRef || disk.targetStorage || ''
          }
        })
      } catch (e) {
        return []
      }
    },
    buildDiskMappingsJson () {
      if (!this.diskMappingRows.length) {
        return ''
      }
      return JSON.stringify(this.diskMappingRows.map((row, index) => {
        const sizeBytes = this.normalizeDiskSizeBytes(row.capacityBytes)
        const targetType = this.targetDiskTypeForStorage(row.targetStorageRef)
        const targetFormat = this.targetDiskFormatForStorage(row.targetStorageRef)
        const controllerBusNumber = row.controllerBusNumber || ''
        const unitNumber = row.unitNumber || ''
        const sourceController = row.sourceController || ''
        const cbtDiskId = row.cbtDiskId || (controllerBusNumber !== '' && unitNumber !== '' ? `scsi${controllerBusNumber}:${unitNumber}` : '')
        const device = cbtDiskId || row.sourceDiskRef
        return {
          label: row.sourceLabel,
          device,
          cbtDiskId,
          sourceDiskKey: row.sourceDiskKey,
          sourceController,
          controllerBusNumber,
          unitNumber,
          sourceRef: row.sourceDiskRef,
          sourcePath: row.sourcePath,
          sourceType: row.sourceType,
          sourceFormat: row.sourceFormat,
          sizeBytes,
          capacityBytes: sizeBytes,
          targetRef: row.targetDiskName,
          targetStorageRef: row.targetStorageRef,
          targetDiskOfferingId: row.targetDiskOfferingId,
          source: {
            diskRef: row.sourceDiskRef,
            device,
            cbtDiskId,
            deviceKey: row.sourceDiskKey,
            controllerType: sourceController,
            controllerBusNumber,
            unitNumber,
            label: row.sourceLabel,
            vmdkPath: row.sourcePath,
            type: row.sourceType,
            sourceType: row.sourceType,
            format: row.sourceFormat,
            sourceFormat: row.sourceFormat,
            capacityBytes: sizeBytes,
            sizeBytes,
            boot: index === 0
          },
          target: {
            name: row.targetDiskName,
            storageRef: row.targetStorageRef,
            diskOfferingId: row.targetDiskOfferingId,
            type: targetType,
            targetType,
            format: targetFormat,
            capacityBytes: sizeBytes,
            sizeBytes
          }
        }
      }))
    },
    fetchList (options = {}) {
      this.loading = true
      this.fetchSites().catch(error => {
        this.listLoadWarning = this.errorMessage(error)
      })
      return listDrPlans(this.listQueryParams()).then(result => {
        this.plans = this.reconcilePlanList(result.items || [], options.retain || [])
        this.listTotal = Math.max(Number(result.count) || 0, this.plans.length)
        this.listLoadWarning = ''
        return this.plans
      }).catch(error => {
        this.listLoadWarning = this.errorMessage(error)
        return this.plans
      }).finally(() => {
        this.loading = false
      })
    },
    listQueryParams () {
      const params = {
        page: this.page,
        pagesize: this.pageSize
      }
      if (this.searchQuery) params.keyword = this.searchQuery
      if (this.filters.state) params.state = this.filters.state
      if (this.filters.direction) params.direction = this.filters.direction
      if (this.searchParams.direction) params.direction = this.searchParams.direction
      if (this.searchParams.enginetype) params.enginetype = this.searchParams.enginetype
      if (this.searchParams.sourcesiteid) params.sourcesiteid = this.searchParams.sourcesiteid
      if (this.searchParams.targetsiteid) params.targetsiteid = this.searchParams.targetsiteid
      return params
    },
    reconcilePlanList (serverPlans, retainedPlans = []) {
      const plans = [...serverPlans]
      const identifiers = new Set(plans.map(plan => String(plan.id || plan.uuid || '')))
      retainedPlans.forEach(plan => {
        const identifier = String(plan?.id || plan?.uuid || '')
        if (identifier && !identifiers.has(identifier)) {
          plans.unshift(plan)
          identifiers.add(identifier)
        }
      })
      return plans
    },
    upsertPlan (plan) {
      const identifier = String(plan?.id || plan?.uuid || '')
      if (!identifier) {
        return
      }
      const index = this.plans.findIndex(item => String(item.id || item.uuid || '') === identifier)
      if (index >= 0) {
        this.plans.splice(index, 1, Object.assign({}, this.plans[index], plan))
      } else {
        this.plans.unshift(plan)
      }
    },
    fetchDetail (options = {}) {
      if (!this.detailId) {
        return
      }
      const silent = options.silent === true
      if (!silent) {
        this.loading = true
      }
      this.detailLoadWarning = ''
      let currentPlan = {}
      const planTask = getDrPlan(this.detailId).then(plan => {
        currentPlan = plan || {}
        this.detailPlan = currentPlan
      }).catch(error => {
        this.detailLoadWarning = this.errorMessage(error)
      })
      const tasks = [planTask, this.fetchProtectionView()]
      if (options.skipSites !== true && !('getDrProtectionView' in this.$store.getters.apis)) {
        tasks.unshift(this.fetchSites())
      }
      return Promise.all(tasks).then(() => {
        // The database plan is newer than a previously generated protection snapshot.
        this.detailPlan = reconcileDrPlanProjection(this.detailPlan, currentPlan)
      }).finally(() => {
        if (!silent) {
          this.loading = false
        }
        this.scheduleRuntimePolling()
      })
    },
    fetchProtectionView (options = {}) {
      if (!('getDrProtectionView' in this.$store.getters.apis)) {
        this.protectionView = {}
        this.protectionSnapshot = {}
        return this.fetchRuns()
      }
      return Promise.all([getDrProtectionView(this.detailId), getDrPlan(this.detailId)]).then(([view, livePlan]) => {
        this.protectionView = view || {}
        let snapshot = view?.snapshot || {}
        if (typeof snapshot === 'string') {
          try {
            snapshot = JSON.parse(snapshot)
          } catch (e) {
            this.detailLoadWarning = this.$t('message.dr.protection.view.invalid')
            return
          }
        }
        const snapshotVersion = Number(snapshot.version || view?.snapshotversion || 0)
        const authoritativeProjection = snapshotVersion >= 4
        if (authoritativeProjection) {
          // A successful authoritative snapshot supersedes transient detail-load warnings.
          this.detailLoadWarning = ''
        }
        const cachedPlan = this.normalizeCachedRecord(
          authoritativeProjection ? snapshot.planProjection : snapshot.plan)
        this.applyCachedPlan(cachedPlan, { authoritative: authoritativeProjection })
        // A source outage can leave the projection snapshot older than completed target actions.
        this.detailPlan = reconcileDrPlanProjection(this.detailPlan, livePlan)
        const sourceSite = this.normalizeCachedRecord(snapshot.sourceSite)
        const targetSite = this.normalizeCachedRecord(snapshot.targetSite)
        if (sourceSite.uuid) sourceSite.id = sourceSite.uuid
        if (targetSite.uuid) targetSite.id = targetSite.uuid
        const latestOperationRun = this.normalizeCachedRecord(snapshot.latestOperationRun || snapshot.latestRun)
        let activeRun = this.normalizeCachedRecord(snapshot.activeRun)
        if ((!activeRun.id && !activeRun.uuid) && isActiveDrRun(latestOperationRun)) {
          activeRun = latestOperationRun
        }
        const activeRunSteps = (snapshot.activeRunSteps || (activeRun.id === latestOperationRun.id ? snapshot.latestRunSteps : []) || [])
          .map(item => this.normalizeCachedRecord(item))
        const latestOperationRunSteps = (snapshot.latestOperationRunSteps || snapshot.latestRunSteps || [])
          .map(item => this.normalizeCachedRecord(item))
        const currentProtectionRuntime = this.normalizeCachedRecord(snapshot.currentProtectionRuntime)
        const currentSyncCycle = this.normalizeCachedRecord(snapshot.currentSyncCycle)
        const latestCompletedSyncCycle = this.normalizeCachedRecord(snapshot.latestCompletedSyncCycle)
        const failbackSession = this.normalizeCachedRecord(snapshot.failbackSession)
        const normalizedActiveRun = activeRun && (activeRun.uuid || activeRun.id)
          ? Object.assign({}, activeRun, {
            id: activeRun.uuid || activeRun.id,
            steps: activeRunSteps
          })
          : {}
        const normalizedLatestOperationRun = latestOperationRun && (latestOperationRun.uuid || latestOperationRun.id)
          ? Object.assign({}, latestOperationRun, {
            id: latestOperationRun.uuid || latestOperationRun.id,
            steps: latestOperationRunSteps
          })
          : {}
        this.protectionSnapshot = {
          version: snapshotVersion,
          plan: cachedPlan,
          sourceSite,
          targetSite,
          activeRun: normalizedActiveRun,
          activeRunSteps,
          latestOperationRun: normalizedLatestOperationRun,
          latestOperationRunSteps,
          currentProtectionRuntime,
          currentSyncCycle,
          latestCompletedSyncCycle,
          failbackSession,
          latestRun: normalizedLatestOperationRun,
          latestRunSteps: latestOperationRunSteps,
          replicas: (snapshot.replicas || []).map(item => this.normalizeCachedRecord(item)),
          latestCompletedCheckpoint: this.normalizeCachedRecord(snapshot.latestCompletedCheckpoint),
          events: (snapshot.events || []).map(item => this.normalizeCachedRecord(item))
        }
        this.sites = [sourceSite, targetSite].filter(site => site && site.id)
        this.detailRuns = [normalizedActiveRun, normalizedLatestOperationRun]
          .filter(run => run && run.id)
          .filter((run, index, runs) => runs.findIndex(item => String(item.id) === String(run.id)) === index)
        if ('listDrRuns' in this.$store.getters.apis) {
          return listDrRuns({ planid: this.detailId }).then(result => {
            const liveRuns = (result.items || []).map(item => this.normalizeCachedRecord(item))
            this.detailRuns = liveRuns
            this.protectionSnapshot = reconcileDrRunProjection(this.protectionSnapshot, liveRuns, {
              authoritativeActiveRun: authoritativeProjection
            })
          }).catch(error => {
            if (!options.silent) {
              this.detailLoadWarning = this.errorMessage(error)
            }
          })
        }
      }).catch(error => {
        this.detailLoadWarning = this.errorMessage(error)
        return options.silent ? undefined : this.fetchRuns()
      })
    },
    applyCachedPlan (cachedPlan, options = {}) {
      if (!cachedPlan || (!cachedPlan.uuid && !cachedPlan.id)) {
        return
      }
      if (options.authoritative === true) {
        const publicId = cachedPlan.id || cachedPlan.uuid || this.detailPlan.id || this.detailId
        const reconciledPlan = reconcileDrPlanProjection(cachedPlan, this.detailPlan, {
          authoritativeActions: true
        })
        this.detailPlan = Object.assign({}, this.detailPlan, reconciledPlan, {
          id: publicId,
          uuid: publicId
        })
        return
      }
      const databaseId = cachedPlan.id
      const publicId = cachedPlan.uuid || this.detailPlan.id || this.detailId
      const refreshableKeys = [
        'name',
        'description',
        'state',
        'adminstate',
        'activeside',
        'rposeconds',
        'rtoseconds',
        'lastsourcecheckpointat',
        'lasttargetdurableat',
        'freshnessstate',
        'schedulernextrunat',
        'schedulerexecutionbudgetseconds',
        'schedulercyclewalldurationseconds',
        'targetreadyat',
        'targetreadyrposeconds',
        'displayrposeconds',
        'rpoageseconds',
        'rpostatus',
        'lasterrorcode',
        'lasterrormessage',
        'failedcomponent',
        'datacommitstate',
        'datacopied',
        'metadatacommitted',
        'targetdurable',
        'cycleretrymode',
        'created',
        'updated',
        'removed'
      ]
      const refreshablePlan = refreshableKeys.reduce((result, key) => {
        if (Object.prototype.hasOwnProperty.call(cachedPlan, key)) {
          result[key] = cachedPlan[key]
        }
        return result
      }, {})
      this.detailPlan = Object.assign({}, this.detailPlan, refreshablePlan, {
        databaseid: databaseId,
        id: publicId,
        uuid: cachedPlan.uuid || publicId
      })
    },
    normalizeCachedRecord (record) {
      if (!record || typeof record !== 'object') {
        return {}
      }
      return Object.keys(record).reduce((result, key) => {
        result[String(key).toLowerCase()] = record[key]
        return result
      }, {})
    },
    fetchRuns () {
      if (!('listDrRuns' in this.$store.getters.apis)) {
        this.detailRuns = []
        return Promise.resolve()
      }
      return listDrRuns({ planid: this.detailId }).then(result => {
        this.detailRuns = result.items || []
      })
    },
    siteName (siteId) {
      return this.siteById[siteId]?.name || siteId || '-'
    },
    directionLabel (direction) {
      return {
        KVM_TO_KVM: 'label.dr.direction.kvm.to.kvm',
        KVM_TO_VMWARE: 'label.dr.direction.kvm.to.vmware',
        VMWARE_TO_VMWARE: 'label.dr.direction.vmware.to.vmware',
        VMWARE_TO_KVM: 'label.dr.direction.vmware.to.kvm'
      }[direction] || direction || '-'
    },
    engineLabel (engineType) {
      return {
        FTCTL_DR: 'label.dr.engine.ftctl.dr',
        FTCTL: 'label.dr.engine.ftctl',
        VMWARE_PHASE1: 'label.dr.engine.vmware.phase1',
        V2K: 'label.dr.engine.v2k.migration.only'
      }[engineType] || engineType || '-'
    },
    normalizeDetailTab (tab) {
      if (tab === 'overview') return 'details'
      if (tab === 'restorepoints' || tab === 'runs') return 'history'
      if (tab === 'topology' || tab === 'replica') return 'protection'
      return tab || 'details'
    },
    resetPlanDialogSections (extraSections = []) {
      const keys = new Set(DEFAULT_DR_PLAN_ACTIVE_SECTIONS)
      extraSections.filter(Boolean).forEach(section => keys.add(section))
      if (this.planFormMode === 'edit') {
        keys.add(DR_PLAN_DIALOG_SECTIONS.OBJECTIVES)
        keys.add(DR_PLAN_DIALOG_SECTIONS.POLICY)
      }
      this.planSectionActiveKeys = Array.from(keys)
    },
    openPlanDialogSection (sectionKey) {
      if (!sectionKey || this.planSectionActiveKeys.includes(sectionKey)) {
        return
      }
      this.planSectionActiveKeys = [...this.planSectionActiveKeys, sectionKey]
    },
    openSectionForValidation (fieldNameOrReason) {
      this.openPlanDialogSection(drPlanSectionForValidation(fieldNameOrReason))
    },
    planValidationMessage (fieldNameOrReason, message) {
      this.openSectionForValidation(fieldNameOrReason)
      return message
    },
    changeTab (tab) {
      const normalizedTab = this.normalizeDetailTab(tab)
      this.activeTab = normalizedTab
      this.$router.replace({ path: this.$route.path, query: Object.assign({}, this.$route.query, { tab: normalizedTab }) }).catch(() => {})
    },
    requestProtectionRefresh () {
      if (!this.detailId || !('refreshDrProtectionView' in this.$store.getters.apis)) {
        this.fetchDetail()
        return
      }
      this.protectionRefreshing = true
      refreshDrProtectionView(this.detailId).then(result => {
        if (!result?.jobid) {
          return this.fetchProtectionView({ silent: true })
        }
        return new Promise((resolve, reject) => {
          this.$pollJob({
            jobId: result.jobid,
            title: this.$t('label.refresh'),
            description: this.detailPlan.name || this.detailId,
            showSuccessMessage: false,
            showLoading: false,
            successMethod: resolve,
            errorMethod: reject,
            catchMethod: reject,
            action: { isFetchData: false }
          })
        }).then(() => this.fetchProtectionView({ silent: true }))
      }).catch(error => {
        notification.error({
          message: this.$t('label.refresh'),
          description: this.errorMessage(error)
        })
      }).finally(() => {
        this.protectionRefreshing = false
        this.scheduleRuntimePolling()
      })
    },
    openCreateModal () {
      this.planFormMode = 'create'
      this.editingPlan = {}
      this.createForm = this.defaultCreateForm()
      this.resetPlanDialogSections()
      this.showCreateModal = true
      this.fetchSites()
    },
    openEditModal (plan) {
      if (!plan?.id) {
        return
      }
      this.planFormMode = 'edit'
      this.editingPlan = plan
      this.editInitialPayload = {}
      const sourceReference = plan.sourcevmid || plan.sourceexternalref || ''
      const sourceReferenceType = plan.sourcevmid ? 'CLOUD_VM_ID' : 'EXTERNAL_REF'
      const sourceOptionKey = sourceReference ? [sourceReferenceType, sourceReference].join(':') : undefined
      this.createForm = Object.assign(this.defaultCreateForm(), {
        name: plan.name || '',
        description: plan.description || '',
        sourcesiteid: plan.sourcesiteid,
        targetsiteid: plan.targetsiteid,
        direction: plan.direction || 'KVM_TO_KVM',
        sourcevmid: plan.sourcevmid || '',
        sourceexternalref: plan.sourceexternalref || '',
        sourceworkloadvalue: sourceOptionKey,
        sourceworkloadname: sourceReference,
        enginetype: plan.enginetype || 'FTCTL_DR',
        enginebindingtype: plan.enginebindingtype || plan.enginetype || 'FTCTL_DR',
        enginebindingid: plan.enginebindingid,
        rposeconds: plan.rposeconds,
        rtoseconds: plan.rtoseconds,
        sourceworkerhostid: plan.sourceworkerhostid || '',
        targetworkerhostid: plan.targetworkerhostid || '',
        coordinatorworkerhostid: plan.coordinatorworkerhostid || '',
        mappingjson: plan.mappingjson || '',
        schedulejson: plan.schedulejson || '',
        policyjson: plan.policyjson || '',
        quiescepolicyjson: plan.quiescepolicyjson || '',
        diskmappingsjson: this.readDiskMappingsJson(plan.mappingjson),
        guidedplan: true,
        expertjson: false,
        targetvmname: this.readJsonValue(plan.mappingjson, 'targetVmName') || '',
        targetzoneid: this.readJsonValue(plan.mappingjson, 'target.zoneId') || this.readJsonValue(plan.mappingjson, 'targetZoneId') || '',
        targetstorageref: this.readJsonValue(plan.mappingjson, 'target.storageRef') || this.readJsonValue(plan.mappingjson, 'targetStorageRef') || this.readJsonValue(plan.mappingjson, 'targetDatastoreRef') || '',
        targetcomputeref: this.readJsonValue(plan.mappingjson, 'target.serviceOfferingId') || this.readJsonValue(plan.mappingjson, 'targetComputeRef') || this.readJsonValue(plan.mappingjson, 'targetResourcePoolRef') || '',
        targetcpunumber: this.readJsonValue(plan.mappingjson, 'target.cpuNumber') || this.readJsonValue(plan.mappingjson, 'targetCpuNumber') || undefined,
        targetcpuspeed: this.readJsonValue(plan.mappingjson, 'target.cpuSpeed') || this.readJsonValue(plan.mappingjson, 'targetCpuSpeed') || undefined,
        targetmemory: this.readJsonValue(plan.mappingjson, 'target.memory') || this.readJsonValue(plan.mappingjson, 'targetMemory') || undefined,
        targetboottype: this.readJsonValue(plan.mappingjson, 'target.hardware.bootType') || this.readJsonValue(plan.mappingjson, 'targetBootType') || '',
        targetbootmode: this.readJsonValue(plan.mappingjson, 'target.hardware.bootMode') || this.readJsonValue(plan.mappingjson, 'targetBootMode') || '',
        targetrootdiskcontroller: this.readJsonValue(plan.mappingjson, 'target.hardware.rootDiskController') || this.readJsonValue(plan.mappingjson, 'targetRootDiskController') || '',
        targetdatadiskcontroller: this.readJsonValue(plan.mappingjson, 'target.hardware.dataDiskController') || this.readJsonValue(plan.mappingjson, 'targetDataDiskController') || '',
        targetiothreadsenabled: this.readJsonValue(plan.mappingjson, 'target.hardware.ioThreadsEnabled') !== false,
        targetiopolicy: this.readJsonValue(plan.mappingjson, 'target.hardware.ioPolicy') || this.readJsonValue(plan.mappingjson, 'targetIoPolicy') || 'io_uring',
        targetnetworkref: this.readJsonValue(plan.mappingjson, 'target.networks.0.networkId') || this.readJsonValue(plan.mappingjson, 'targetNetworkRef') || this.readJsonValue(plan.mappingjson, 'networkRef') || '',
        targetfolderpath: this.readJsonValue(plan.mappingjson, 'targetFolderPath') || this.readJsonValue(plan.mappingjson, 'folderPath') || '',
        consistencymode: this.readJsonValue(plan.policyjson, 'consistencyMode') || 'CRASH_CONSISTENT',
        testnetworkmode: this.readJsonValue(plan.policyjson, 'testNetworkMode') || 'ISOLATED',
        testbootvalidationmode: this.readJsonValue(plan.policyjson, 'testBootValidationMode') || 'POWER_STATE_ONLY',
        testboottimeoutseconds: this.readJsonValue(plan.policyjson, 'testBootTimeoutSeconds') || 180,
        failoverpoweron: this.readJsonValue(plan.policyjson, 'failover.powerOn') !== false,
        syncintervalseconds: this.readJsonValue(plan.schedulejson, 'intervalSeconds') || plan.rposeconds || 300,
        retentioncount: this.readJsonValue(plan.schedulejson, 'retentionCount') || 24,
        bandwidthlimitmbps: this.readJsonValue(plan.policyjson, 'bandwidthLimitMbps') || undefined,
        retrycount: this.readJsonValue(plan.policyjson, 'retry.maxAttempts') || 3,
        startsync: false
      })
      this.sourceWorkloadOptions = sourceReference ? [{
        optionKey: sourceOptionKey,
        referencetype: sourceReferenceType,
        value: sourceReference,
        externalref: plan.sourceexternalref || '',
        name: sourceReference
      }] : []
      this.diskMappingRows = this.diskRowsFromJson(this.createForm.diskmappingsjson)
      this.editInitialPayload = this.buildPlanPayload({ includeUnchangedEditFields: true })
      const editingPlanId = plan.id
      this.fetchSourceWorkloads(true).then(() => {
        if (this.planFormMode === 'edit' && this.editingPlan?.id === editingPlanId) {
          this.editInitialPayload = this.buildPlanPayload({ includeUnchangedEditFields: true })
        }
      })
      this.resetPlanDialogSections([
        DR_PLAN_DIALOG_SECTIONS.OBJECTIVES,
        DR_PLAN_DIALOG_SECTIONS.POLICY
      ])
      this.showCreateModal = true
    },
    onCreateEngineChange (engineType) {
      this.createForm.enginebindingtype = engineType
      if (engineType === 'FTCTL') {
        this.createForm.direction = 'KVM_TO_KVM'
      }
    },
    closeCreateModal () {
      this.showCreateModal = false
      this.planFormMode = 'create'
      this.editingPlan = {}
      this.editInitialPayload = {}
      this.resetSourceWorkloads(true)
    },
    submitPlan () {
      if (this.planFormMode === 'edit') {
        this.updatePlan()
        return
      }
      this.createPlan()
    },
    createPlan () {
      const validationMessage = this.validatePlanForm()
      if (validationMessage) {
        notification.warning({
          message: this.$t('label.dr.plan.add'),
          description: validationMessage
        })
        return
      }
      this.createLoading = true
      this.ensureExecutionReadyForImmediateSync().then(() => createDrPlan(this.buildPlanPayload())).then(admission => {
        notification.success({
          message: this.$t('label.dr.plan.add'),
          description: this.$t(this.createForm.startsync
            ? 'message.dr.create.sync.accepted'
            : 'message.dr.create.accepted')
        })
        this.closeCreateModal()
        this.createLoading = false
        return waitForDrMutation(admission).then(result => {
          if (!result?.id) return this.fetchList()
          return getDrPlan(result.id).then(plan => {
            this.upsertPlan(plan)
            return this.fetchList({ retain: [plan] })
          })
        }).catch(error => {
          notification.error({
            message: this.$t('label.dr.plan.add'),
            description: this.errorMessage(error)
          })
        })
      }).catch(error => {
        notification.error({
          message: this.$t('label.dr.plan.add'),
          description: this.errorMessage(error)
        })
      }).finally(() => {
        this.createLoading = false
      })
    },
    updatePlan () {
      const validationMessage = this.validatePlanForm()
      if (!this.editingPlan?.id || validationMessage) {
        notification.warning({
          message: this.$t('label.dr.plan.edit'),
          description: validationMessage || this.$t('message.dr.required.fields')
        })
        return
      }
      this.createLoading = true
      const changedPayload = this.buildPlanPayload()
      const refreshSourceHardware = requiresSourceHardwareRefresh(this.editingPlan, changedPayload)
      const payload = refreshSourceHardware
        ? this.buildPlanPayload({ includeUnchangedEditFields: true })
        : changedPayload
      updateDrPlan(this.editingPlan.id, payload).then(admission => waitForDrMutation(admission)).then(result => {
        notification.success({
          message: this.$t('label.dr.plan.edit'),
          description: this.$t('label.success')
        })
        this.closeCreateModal()
        if (!result?.id) return this.fetchData()
        return getDrPlan(result.id).then(plan => {
          this.upsertPlan(plan)
          return this.fetchData()
        })
      }).catch(error => {
        notification.error({
          message: this.$t('label.dr.plan.edit'),
          description: this.errorMessage(error)
        })
      }).finally(() => {
        this.createLoading = false
      })
    },
    buildPlanPayload (options = {}) {
      if (!this.createForm.expertjson) {
        this.createForm.diskmappingsjson = this.buildDiskMappingsJson()
      }
      const payload = Object.assign({}, this.createForm)
      delete payload.sourceworkerhostid
      delete payload.targetworkerhostid
      delete payload.coordinatorworkerhostid
      payload.guidedplan = !payload.expertjson
      payload.allowdraft = this.planFormMode === 'edit' ? true : !payload.startsync
      delete payload.sourceworkloadvalue
      delete payload.sourceworkloadname
      delete payload.expertjson
      if (payload.guidedplan) {
        delete payload.mappingjson
        delete payload.schedulejson
        delete payload.policyjson
        delete payload.quiescepolicyjson
        delete payload.enginebindingid
      } else {
        delete payload.targetvmname
        delete payload.targetzoneid
        delete payload.targetstorageref
        delete payload.targetcomputeref
        delete payload.targetcpunumber
        delete payload.targetcpuspeed
        delete payload.targetmemory
        delete payload.targetboottype
        delete payload.targetbootmode
        delete payload.targetrootdiskcontroller
        delete payload.targetdatadiskcontroller
        delete payload.targetiothreadsenabled
        delete payload.targetiopolicy
        delete payload.targetnetworkref
        delete payload.targetfolderpath
        delete payload.diskmappingsjson
        delete payload.consistencymode
        delete payload.testnetworkmode
        delete payload.testbootvalidationmode
        delete payload.testboottimeoutseconds
        delete payload.failoverpoweron
        delete payload.syncintervalseconds
        delete payload.retentioncount
        delete payload.bandwidthlimitmbps
        delete payload.retrycount
      }
      if (this.planFormMode === 'edit') {
        delete payload.sourcesiteid
        delete payload.targetsiteid
        delete payload.direction
        delete payload.startsync
        delete payload.allowdraft
        delete payload.sourcevmid
        delete payload.sourceexternalref
      }
      const compactPayload = this.compactPayload(payload)
      if (this.planFormMode === 'edit' && !options.includeUnchangedEditFields) {
        return buildChangedPlanPayload(compactPayload, this.editInitialPayload)
      }
      return compactPayload
    },
    validatePlanForm () {
      if (!this.createForm.name) {
        return this.planValidationMessage('name', this.$t('message.dr.plan.validation.name'))
      }
      if (this.planFormMode === 'create' && (!this.createForm.sourcesiteid || !this.createForm.targetsiteid || !this.createForm.direction)) {
        return this.planValidationMessage('sourcesiteid', this.$t('message.dr.plan.validation.site.mapping'))
      }
      if (this.planFormMode === 'create' && !this.createForm.sourcevmid && !this.createForm.sourceexternalref) {
        return this.planValidationMessage('sourceworkloadvalue', this.$t('message.dr.plan.validation.source.vm'))
      }
      if (this.createForm.rposeconds !== undefined && this.createForm.rposeconds !== null && Number(this.createForm.rposeconds) <= 0) {
        return this.planValidationMessage('rposeconds', this.$t('message.dr.plan.validation.rpo'))
      }
      if (this.createForm.rtoseconds !== undefined && this.createForm.rtoseconds !== null && Number(this.createForm.rtoseconds) <= 0) {
        return this.planValidationMessage('rtoseconds', this.$t('message.dr.plan.validation.rto'))
      }
      if (this.createForm.expertjson) {
        return this.validatePlanJsonFields()
      }
      if (this.createForm.syncintervalseconds !== undefined && this.createForm.syncintervalseconds !== null && Number(this.createForm.syncintervalseconds) <= 0) {
        return this.planValidationMessage('syncintervalseconds', this.$t('message.dr.plan.validation.sync.interval'))
      }
      if (this.createForm.retentioncount !== undefined && this.createForm.retentioncount !== null && Number(this.createForm.retentioncount) <= 0) {
        return this.planValidationMessage('retentioncount', this.$t('message.dr.plan.validation.retention'))
      }
      if (this.createForm.retrycount !== undefined && this.createForm.retrycount !== null && Number(this.createForm.retrycount) <= 0) {
        return this.planValidationMessage('retrycount', this.$t('message.dr.plan.validation.retry'))
      }
      if (Number(this.createForm.testboottimeoutseconds) < 30 || Number(this.createForm.testboottimeoutseconds) > 1800) {
        return this.planValidationMessage('testboottimeoutseconds', this.$t('message.dr.plan.validation.test.boot.timeout'))
      }
      if (this.directionUsesKvmTarget) {
        if (this.inventoryBlockingReasons.length > 0) {
          return this.planValidationMessage(this.inventoryBlockingReasons[0], this.inventoryBlockingReasons.join(', '))
        }
        if (!this.hasDiskLevelStorageAuthority && !this.createForm.targetstorageref) {
          return this.planValidationMessage('targetstorageref', this.$t('message.dr.plan.validation.target.storage'))
        }
        if (!this.createForm.targetcomputeref) {
          return this.planValidationMessage('targetcomputeref', this.$t('message.dr.plan.validation.target.compute'))
        }
        const computeSizingMessage = this.validateTargetComputeSizing()
        if (computeSizingMessage) {
          return this.planValidationMessage('targetcomputeref', computeSizingMessage)
        }
        if (!this.createForm.targetnetworkref) {
          return this.planValidationMessage('targetnetworkref', this.$t('message.dr.plan.validation.target.network'))
        }
        if (this.requiresDiskMapping && this.diskMappingRows.length === 0) {
          return this.planValidationMessage('diskmappingsjson', this.$t('message.dr.plan.validation.disk.mapping'))
        }
        const incompleteDisk = this.diskMappingRows.find(row => !row.targetDiskName || !row.targetDiskOfferingId || !row.targetStorageRef)
        if (incompleteDisk) {
          return this.planValidationMessage('diskmappingsjson', this.$t('message.dr.plan.validation.disk.mapping'))
        }
        if (String(this.createForm.direction || '').toUpperCase() === 'VMWARE_TO_KVM') {
          const unresolvedDisk = this.diskMappingRows.find(row => !this.normalizeDiskSizeBytes(row.capacityBytes || row.sizeBytes))
          if (unresolvedDisk) {
            return this.planValidationMessage('sourceDiskSize', this.$t('message.dr.plan.validation.source.disk.size'))
          }
        }
      }
      return ''
    },
    validateTargetComputeSizing () {
      const field = invalidTargetComputeField(this.targetSizingFields, this.createForm)
      return field ? this.$t('message.dr.target.compute.invalid', {
        field: this.$t(field.label), min: field.min, max: field.max
      }) : ''
    },
    validatePlanJsonFields () {
      const fields = [
        { key: 'mappingjson', label: this.$t('label.dr.mapping.json') },
        { key: 'schedulejson', label: this.$t('label.dr.schedule.json') },
        { key: 'policyjson', label: this.$t('label.dr.policy.json') },
        { key: 'quiescepolicyjson', label: this.$t('label.dr.quiesce.policy.json') }
      ]
      for (const field of fields) {
        const value = this.createForm[field.key]
        if (!value) {
          continue
        }
        try {
          JSON.parse(value)
        } catch (e) {
          return this.planValidationMessage(field.key, this.$t('message.dr.plan.validation.json', { field: field.label }))
        }
      }
      return ''
    },
    compactPayload (payload) {
      return Object.keys(payload || {}).reduce((result, key) => {
        const value = payload[key]
        if (value !== '' && value !== undefined && value !== null) {
          result[key] = value
        }
        return result
      }, {})
    },
    readJsonValue (json, path) {
      if (!json || !path) {
        return undefined
      }
      try {
        const object = JSON.parse(json)
        return path.split('.').reduce((value, key) => value && value[key], object)
      } catch (e) {
        return undefined
      }
    },
    readDiskMappingsJson (json) {
      if (!json) {
        return ''
      }
      try {
        const object = typeof json === 'string' ? JSON.parse(json) : json
        const disks = object?.disks || object?.diskMappings || object?.volumes || object?.volumeMappings
        return Array.isArray(disks) && disks.length > 0 ? JSON.stringify(disks) : ''
      } catch (e) {
        return ''
      }
    },
    ensureExecutionReadyForImmediateSync () {
      if (!this.createForm.startsync || this.createForm.expertjson || !('previewDrPlanSpec' in this.$store.getters.apis)) {
        return Promise.resolve()
      }
      return this.previewGuidedSpec().then(preview => {
        if (preview.executionready === true) {
          return
        }
        const reasons = preview.blockingreasons || []
        if (reasons.length > 0) {
          this.openSectionForValidation(reasons[0])
        }
        const suffix = reasons.length > 0 ? ` (${reasons.join(', ')})` : ''
        return Promise.reject(new Error(this.$t('message.dr.plan.validation.execution.ready') + suffix))
      })
    },
    previewGuidedSpec () {
      if (!('previewDrPlanSpec' in this.$store.getters.apis)) {
        return Promise.resolve({})
      }
      return previewDrPlanSpec(this.buildPlanPayload()).then(preview => {
        const sourceHardware = this.parseOptionDetails(preview.sourcehardwarejson)
        if (Object.keys(sourceHardware).length > 0) {
          this.sourceHardware = sourceHardware
        }
        this.resolvedTargetHardware = this.parseOptionDetails(preview.resolvedtargethardwarejson)
        return preview
      })
    },
    errorMessage (error) {
      return error?.response?.data?.errorresponse?.errortext || error?.message || this.$t('message.error')
    },
    runPlanAction (action, plan) {
      const target = plan || action.resource || this.contextMenuPlan || this.detailPlan
      if (!target?.id) {
        return
      }
      if (action.api === 'updateDrPlan') {
        this.openEditModal(target)
        return
      }
      if (action.api === 'deleteDrPlan') {
        this.confirmDeletePlan(target)
        return
      }
      if (action.command === 'cancelDrRun') {
        this.openCancelRunModal(action, target)
        return
      }
      if (this.requiresActionModal(action)) {
        this.openActionModal(action, target)
        return
      }
      this.executePlanAction(action, target, {})
    },
    openCancelRunModal (action, plan) {
      const knownRun = action.currentRun?.id ? action.currentRun : this.currentRun
      if (knownRun?.id && isActiveDrRun(knownRun)) {
        this.openActionModal(Object.assign({}, action, { currentRun: knownRun }), plan)
        return
      }
      if (!('listDrRuns' in this.$store.getters.apis)) {
        notification.error({
          message: this.$t('label.dr.action.cancel.run'),
          description: this.$t('message.dr.action.cancel.no.active.run')
        })
        return
      }
      this.actionLoading = action.command
      this.actionLoadingPlanId = plan.id
      listDrRuns(buildActiveDrRunQuery(plan.id)).then(result => {
        const activeRun = findActiveDrRun(result.items || [], isActiveDrRun)
        if (!activeRun) {
          notification.info({
            message: this.$t('label.dr.action.cancel.run'),
            description: this.$t('message.dr.action.cancel.no.active.run')
          })
          this.fetchData()
          return
        }
        this.openActionModal(Object.assign({}, action, { currentRun: activeRun }), plan)
      }).catch(error => {
        notification.error({
          message: this.$t('label.dr.action.cancel.run'),
          description: this.errorMessage(error)
        })
      }).finally(() => {
        this.actionLoading = ''
        this.actionLoadingPlanId = ''
      })
    },
    requiresActionModal (action) {
      return ['startDrSync', 'startDrTestFailover', 'startDrFailover', 'startDrFailback', 'startDrReprotect', 'adoptDrReplica', 'releaseDrProtection', 'cancelDrRun', 'stopDrTestFailover'].includes(action.command)
    },
    openActionModal (action, plan) {
      this.selectedAction = Object.freeze(Object.assign({}, action))
      this.selectedActionPlan = Object.freeze(Object.assign({}, plan))
      this.actionRequestKey = this.createActionRequestKey(action, plan)
      this.actionForm = this.defaultActionForm()
      if (action.command === 'startDrFailover' && requiresDisasterFailover(plan)) {
        this.actionForm.disaster = true
        this.actionForm.finalsync = false
      }
      this.actionReplicas = []
      this.actionNetworkOptions = []
      this.failbackPreflight = {}
      this.showActionModal = true
      if (action.command === 'startDrTestFailover') {
        this.loadActionNetworks(plan)
      }
      if (action.command === 'adoptDrReplica' && 'listDrReplicas' in this.$store.getters.apis) {
        listDrReplicas({ planid: plan.id }).then(result => {
          this.actionReplicas = result.items || []
        })
      }
      if (action.command === 'startDrFailback') {
        this.loadFailbackPreflight(plan)
      }
    },
    closeActionModal () {
      this.showActionModal = false
      this.selectedAction = {}
      this.selectedActionPlan = {}
      this.actionRequestKey = ''
      this.actionReplicas = []
      this.actionNetworkOptions = []
      this.failbackPreflight = {}
      this.actionPreflightLoading = false
      this.actionForm = this.defaultActionForm()
    },
    submitActionModal () {
      if (this.isTestFailoverAction && !this.actionForm.networkid) {
        notification.error({
          message: this.$t('label.dr.test.network'),
          description: this.$t('message.dr.test.network.required')
        })
        return
      }
      if (this.isFailoverAction && this.actionForm.disaster &&
        (!this.actionForm.sourceisolationacknowledged || !this.actionForm.sourceisolationreason.trim())) {
        notification.error({
          message: this.$t('label.dr.failover.source.isolation.acknowledged'),
          description: this.$t('message.dr.failover.source.isolation.required')
        })
        return
      }
      this.actionSubmitting = true
      this.executePlanAction(this.selectedAction, this.selectedActionPlan, this.buildActionPayload())
        .then(() => {
          this.closeActionModal()
        })
        .finally(() => {
          this.actionSubmitting = false
        })
    },
    buildActionPayload () {
      const payload = {
        reason: this.actionForm.reason || undefined,
        acknowledgement: this.actionForm.acknowledgement || undefined
      }
      if (this.isFailoverAction) {
        payload.force = this.actionForm.force
        payload.disaster = this.actionForm.disaster
        payload.finalsync = !this.actionForm.disaster && this.actionForm.finalsync
        payload.skipsourcefencerequest = this.actionForm.skipsourcefencerequest
        payload.sourceisolationacknowledged = this.actionForm.disaster
          ? this.actionForm.sourceisolationacknowledged
          : undefined
        payload.sourceisolationreason = this.actionForm.disaster
          ? this.actionForm.sourceisolationreason.trim()
          : undefined
      }
      if (this.isReleaseAction) {
        payload.force = this.actionForm.force
        payload.resourcedisposition = this.actionForm.resourcedisposition
      }
      if (this.isTestFailoverAction) {
        payload.sourceindependent = this.actionForm.sourceindependent
        payload.networkmode = this.actionForm.networkmode
        payload.networkid = this.actionForm.networkid
        payload.bootvalidationmode = this.actionForm.bootvalidationmode
        payload.boottimeoutseconds = this.actionForm.boottimeoutseconds
      }
      if (this.isFailbackAction) {
        payload.force = this.actionForm.force
      }
      if (this.isAdoptAction) {
        payload.replicaid = this.actionForm.replicaid || undefined
        payload.cleanuptransport = this.actionForm.cleanuptransport
      }
      return payload
    },
    loadFailbackPreflight (plan) {
      if (!plan?.id || !('getDrFailbackPreflight' in this.$store.getters.apis)) {
        this.failbackPreflight = {
          ready: false,
          message: this.$t('message.dr.failback.preflight.api.unavailable')
        }
        return
      }
      this.actionPreflightLoading = true
      getDrFailbackPreflight(plan.id).then(result => {
        this.failbackPreflight = result || {}
      }).catch(error => {
        this.failbackPreflight = {
          ready: false,
          message: error?.response?.data?.errorresponse?.errortext ||
            this.$t('message.dr.failback.preflight.not.ready')
        }
      }).finally(() => {
        this.actionPreflightLoading = false
      })
    },
    failbackSiteValue (side, field) {
      const prefix = side === 'active' ? 'active' : 'destination'
      return this.failbackPreflight[`${prefix}${field}`] || '-'
    },
    failbackPreflightMessage () {
      const errorCode = String(this.failbackPreflight.errorcode || '').toLowerCase()
      if (errorCode) {
        const key = `message.dr.preflight.${errorCode}`
        const localized = this.$t(key)
        if (localized !== key) {
          return localized
        }
      }
      return this.failbackPreflight.message || this.$t('message.dr.failback.preflight.not.ready')
    },
    loadActionNetworks (plan) {
      const configuredNetworkId = this.readJsonValue(plan.mappingjson, 'target.networks.0.networkId') ||
        this.readJsonValue(plan.mappingjson, 'targetNetworkRef') ||
        this.readJsonValue(plan.mappingjson, 'networkRef') || ''
      this.actionForm.networkid = configuredNetworkId || undefined
      if (!('discoverDrPlanInventory' in this.$store.getters.apis)) {
        return
      }
      this.actionNetworkLoading = true
      discoverDrPlanInventory({
        sourcesiteid: plan.sourcesiteid,
        targetsiteid: plan.targetsiteid,
        sourcevmid: plan.sourcevmid || undefined,
        sourceexternalref: plan.sourceexternalref || undefined,
        includeplacement: true,
        includedisks: false,
        includenetworks: true
      }).then(result => {
        this.actionNetworkOptions = this.normalizeInventoryOptions(result.targetnetworkoptions || [])
        if (!this.actionForm.networkid && this.actionNetworkOptions.length === 1) {
          this.actionForm.networkid = this.actionNetworkOptions[0].value
        }
      }).catch(() => {
        this.actionNetworkOptions = []
      }).finally(() => {
        this.actionNetworkLoading = false
      })
    },
    executePlanAction (action, plan, payload) {
      this.actionLoading = action.command
      this.actionLoadingPlanId = plan.id
      const expectedRunType = action.expectedRunType || action.intent
      const requestKey = this.actionRequestKey || this.createActionRequestKey(action, plan)
      const actionContract = expectedRunType
        ? { actionintent: action.intent, idempotencykey: requestKey }
        : {}
      const cancelRunId = action.currentRun?.id || this.currentRun.id
      if (action.command === 'cancelDrRun' && !cancelRunId) {
        this.actionLoading = ''
        this.actionLoadingPlanId = ''
        return Promise.reject(new Error(this.$t('message.dr.action.cancel.no.active.run')))
      }
      const params = action.command === 'cancelDrRun'
        ? Object.assign({ id: cancelRunId }, payload)
        : Object.assign({ planid: plan.id }, payload, actionContract)
      return startDrAction(action.command, params, { expectedRunType }).then(run => {
        const actualRunType = String(run.runtype || run.runType || '').toUpperCase()
        if (expectedRunType && actualRunType !== String(expectedRunType).toUpperCase()) {
          this.fetchData()
          throw new Error(`DR action contract mismatch: expected ${expectedRunType}, received ${actualRunType || 'EMPTY'}`)
        }
        const runState = String(run.state || '').toUpperCase()
        const terminalSuccess = runState === 'SUCCEEDED'
        const description = String(expectedRunType).toUpperCase() === 'TEST_FAILOVER' && !terminalSuccess
          ? this.$t('message.dr.test.failover.accepted')
          : (run.id || run.state || this.$t('label.success'))
        const notifier = terminalSuccess ? notification.success : notification.info
        notifier({
          message: terminalSuccess ? this.$t(action.label) : this.$t('message.dr.action.accepted'),
          description
        })
        this.applyAcceptedRun(run, plan)
        this.fetchData()
        return run
      }).catch(error => {
        notification.error({
          message: this.$t('label.error'),
          description: this.errorMessage(error)
        })
        this.fetchData()
        return null
      }).finally(() => {
        this.actionLoading = ''
        this.actionLoadingPlanId = ''
      })
    },
    createActionRequestKey (action, plan) {
      if (window.crypto && typeof window.crypto.randomUUID === 'function') {
        return window.crypto.randomUUID()
      }
      return [
        action.command || action.api || 'dr-action',
        plan.id || 'plan',
        Date.now(),
        Math.random().toString(16).slice(2)
      ].join(':')
    },
    confirmDeletePlan (plan) {
      if (!plan?.id) {
        return
      }
      let force = false
      this.$confirm({
        title: this.$t('label.dr.plan.delete'),
        content: () => h('div', [
          h('p', this.$t('message.dr.confirm.delete.plan')),
          h(Checkbox, {
            defaultChecked: false,
            onChange: event => { force = event.target.checked }
          }, { default: () => this.$t('label.dr.action.force') }),
          h('p', { style: 'margin-top: 12px' }, this.$t('message.dr.confirm.force.delete.plan')),
          h('p', plan.id)
        ]),
        okType: 'danger',
        okText: this.$t('label.yes'),
        cancelText: this.$t('label.no'),
        onOk: () => {
          return deleteDrPlan(plan.id, force).then(result => this.waitForDeleteJob(result?.jobid, {
            title: this.$t('label.dr.plan.delete'),
            description: plan.name || plan.id || ''
          })).then(() => {
            notification.success({
              message: this.$t('label.dr.plan.delete'),
              description: plan.name || plan.id || this.$t('label.success')
            })
            if (this.detailId) {
              this.$router.push({ path: '/drplan' }).catch(() => {})
            } else {
              this.fetchList()
            }
          })
        }
      })
    },
    waitForDeleteJob (jobId, options = {}) {
      if (!jobId) {
        return Promise.resolve()
      }
      return new Promise((resolve, reject) => {
        this.$pollJob({
          jobId,
          title: options.title || this.$t('label.dr.plan.delete'),
          description: options.description || '',
          showSuccessMessage: false,
          showLoading: true,
          loadingMessage: `${this.$t('label.loading')}...`,
          errorMessage: options.title || this.$t('label.error'),
          successMethod: resolve,
          errorMethod: reject,
          catchMethod: reject,
          action: { isFetchData: false }
        })
      })
    },
    openPlanContextMenu (event, plan) {
      if (!plan?.id) {
        return
      }
      event.preventDefault()
      this.contextMenuMode = 'single'
      this.contextMenuPlan = plan
      this.contextMenuPosition = { x: event.clientX, y: event.clientY }
      this.contextMenuVisible = true
    },
    openMultiplePlanContextMenu (event) {
      event.preventDefault()
      this.contextMenuMode = 'multiple'
      this.contextMenuPlan = {}
      this.contextMenuPosition = { x: event.clientX, y: event.clientY }
      this.contextMenuVisible = true
    },
    openListContextMenu (event) {
      const rowElement = event.target.closest('tr.ant-table-row')
      if (!rowElement) {
        this.closeContextMenu()
        return
      }
      if (this.selectedRowKeys.length > 1) {
        this.openMultiplePlanContextMenu(event)
        return
      }
      const rowKey = rowElement.getAttribute('data-row-key')
      const plan = this.pagedPlans.find(item => String(item.id) === String(rowKey))
      if (plan) {
        this.openPlanContextMenu(event, plan)
      }
    },
    runContextMenuAction (action, plan) {
      if (action?.contextAction === 'openProtectionGroup') {
        this.openGroupModal()
        return
      }
      if (action?.contextAction === 'clearSelection') {
        this.selectedRowKeys = []
        return
      }
      this.runPlanAction(action, plan)
    },
    closeContextMenu () {
      this.contextMenuVisible = false
      this.contextMenuMode = 'single'
      this.contextMenuPlan = {}
    },
    applyAcceptedRun (run, plan) {
      if (!run || !run.id || !this.detailId || String(plan.id) !== String(this.detailId)) {
        return
      }
      this.detailRuns = [
        run,
        ...this.detailRuns.filter(item => String(item.id) !== String(run.id))
      ]
      this.scheduleRuntimePolling()
    },
    isActiveRun (run) {
      return isActiveDrRun(run)
    },
    shouldPollActiveRun () {
      return this.isActiveRun(this.currentRun)
    },
    shouldPollActiveCycle () {
      return isActiveDrSyncCycle(this.currentSyncCycle)
    },
    shouldPollProtectionView () {
      if (!this.detailId || !this.detailPlan.id || !('getDrProtectionView' in this.$store.getters.apis)) {
        return false
      }
      const adminState = String(this.detailPlan.adminstate || '').toUpperCase()
      return !this.detailPlan.removed && (this.shouldPollActiveRun() || adminState === 'ENABLED')
    },
    shouldPollRuntime () {
      return !document.hidden && this.shouldPollProtectionView()
    },
    runtimePollDelay () {
      return this.shouldPollActiveRun() || this.shouldPollActiveCycle()
        ? this.activeRuntimePollIntervalMs
        : this.steadyProtectionPollIntervalMs
    },
    scheduleRuntimePolling () {
      this.stopRuntimePolling()
      if (!this.shouldPollRuntime()) {
        return
      }
      this.runtimePollTimer = window.setTimeout(this.pollRuntime, this.runtimePollDelay())
    },
    stopRuntimePolling () {
      if (this.runtimePollTimer) {
        window.clearTimeout(this.runtimePollTimer)
        this.runtimePollTimer = null
      }
    },
    pollRuntime () {
      if (this.runtimePollInFlight || !this.detailId) {
        return
      }
      this.runtimePollInFlight = true
      this.fetchProtectionView({ silent: true }).finally(() => {
        this.runtimePollInFlight = false
        this.scheduleRuntimePolling()
      })
    },
    onVisibilityChange () {
      if (document.hidden) {
        this.stopRuntimePolling()
        return
      }
      if (this.shouldPollProtectionView()) {
        this.pollRuntime()
      }
    },
    onSearch (opts) {
      if (opts && Object.prototype.hasOwnProperty.call(opts, 'searchQuery')) {
        this.searchQuery = opts.searchQuery || ''
        this.searchParams = {}
      } else {
        this.searchParams = opts || {}
      }
      this.resetPagination()
      this.fetchList()
    },
    changeFilter (filter) {
      this.filters.state = filter === 'all' ? undefined : filter
      this.resetPagination()
      this.fetchList()
    },
    resetPagination () {
      this.page = 1
    },
    removeFilter (filter) {
      if (filter.key === 'state') {
        this.filters.state = undefined
      } else if (filter.key === 'direction' && this.filters.direction) {
        this.filters.direction = undefined
      } else {
        const searchParams = Object.assign({}, this.searchParams)
        delete searchParams[filter.key]
        this.searchParams = searchParams
      }
      this.resetPagination()
      this.fetchList()
    },
    changePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize || this.pageSize
      this.fetchList()
    },
    changePageSize (current, size) {
      this.page = 1
      this.pageSize = size
      this.fetchList()
    },
    updateSelectedColumns (name) {
      if (!name) {
        return
      }
      if (this.selectedColumns.includes(name)) {
        this.selectedColumns = this.selectedColumns.filter(column => column !== name)
      } else {
        this.selectedColumns.push(name)
      }
    },
    onRowSelectionChange (selectedRowKeys) {
      this.selectedRowKeys = selectedRowKeys
    },
    async openGroupModal () {
      const plans = this.selectedGroupPlans
      this.groupForm = {
        name: plans[0]?.protectiongroupname || this.$t('label.dr.protection.group.default.name'),
        action: 'SYNC',
        maxparallel: Number(plans[0]?.protectiongroupmaxparallel || 2),
        quiescerequired: plans.some(plan => plan.protectiongroupquiescerequired === true)
      }
      this.groupHistory = []
      this.groupPreflight = {}
      this.showGroupModal = true
      await this.refreshGroupPreflight()
      const groupUuid = plans[0]?.protectiongroupuuid
      if (groupUuid && plans.every(plan => plan.protectiongroupuuid === groupUuid)) {
        this.groupHistoryLoading = true
        try {
          const result = await listDrProtectionGroupRuns({ groupuuid: groupUuid })
          this.groupHistory = result.items || []
        } finally {
          this.groupHistoryLoading = false
        }
      }
    },
    async refreshGroupPreflight () {
      if (this.selectedRowKeys.length < 1 || !this.groupForm.action) {
        this.groupPreflight = {}
        return false
      }
      const request = ++this.groupPreflightRequest
      this.groupPreflightLoading = true
      try {
        const result = await previewDrProtectionGroupAction({
          planids: this.selectedRowKeys.join(','),
          action: this.groupForm.action,
          quiescerequired: this.groupForm.quiescerequired
        })
        if (request === this.groupPreflightRequest) {
          this.groupPreflight = result || {}
        }
        return result?.ready === true
      } catch (error) {
        if (request === this.groupPreflightRequest) {
          this.groupPreflight = { ready: false, plans: [] }
          notification.error({
            message: this.$t('message.dr.protection.group.preflight.failed'),
            description: error?.response?.data?.errorresponse?.errortext || error?.message || ''
          })
        }
        return false
      } finally {
        if (request === this.groupPreflightRequest) {
          this.groupPreflightLoading = false
        }
      }
    },
    closeGroupModal () {
      if (!this.groupSubmitting) {
        this.showGroupModal = false
      }
    },
    async submitGroupAction () {
      if (!this.groupForm.name || this.selectedRowKeys.length < 1) {
        notification.error({ message: this.$t('message.dr.protection.group.validation') })
        return
      }
      this.groupSubmitting = true
      try {
        if (!await this.refreshGroupPreflight()) {
          notification.error({ message: this.$t('message.dr.protection.group.preflight.blocked') })
          return
        }
        const planids = this.selectedRowKeys.join(',')
        await configureDrProtectionGroup({
          planids,
          groupname: this.groupForm.name,
          maxparallel: this.groupForm.maxparallel,
          quiescerequired: this.groupForm.quiescerequired
        })
        const groupRun = await startDrProtectionGroupAction({
          planids,
          action: this.groupForm.action,
          maxparallel: this.groupForm.maxparallel,
          quiescerequired: this.groupForm.quiescerequired
        })
        this.trackedGroupRun = groupRun || {}
        this.groupHistory = [groupRun, ...this.groupHistory.filter(run => run.id !== groupRun?.id)].filter(Boolean)
        this.showGroupModal = false
        this.selectedRowKeys = []
        if (String(groupRun?.state || '').toUpperCase() === 'FAILED') {
          notification.error({ message: this.$t('message.dr.protection.group.terminal.failed') })
        } else {
          notification.info({ message: this.$t('message.dr.protection.group.accepted') })
          this.startGroupRunPolling(groupRun)
        }
        await this.fetchList()
      } catch (error) {
        notification.error({
          message: this.$t('message.dr.protection.group.failed'),
          description: error?.response?.data?.errorresponse?.errortext || error?.message || ''
        })
      } finally {
        this.groupSubmitting = false
      }
    },
    startGroupRunPolling (groupRun) {
      this.stopGroupRunPolling()
      if (!groupRun?.id || !groupRun?.groupuuid) return
      this.groupRunPollDeadline = Date.now() + (24 * 60 * 60 * 1000)
      const poll = async () => {
        if (Date.now() >= this.groupRunPollDeadline) {
          this.stopGroupRunPolling()
          notification.warning({ message: this.$t('message.dr.protection.group.poll.timeout') })
          return
        }
        try {
          const result = await listDrProtectionGroupRuns({ groupuuid: groupRun.groupuuid })
          const current = (result.items || []).find(run => run.id === groupRun.id)
          if (current) {
            this.trackedGroupRun = current
            const state = String(current.state || '').toUpperCase()
            if (['SUCCEEDED', 'FAILED'].includes(state)) {
              this.stopGroupRunPolling()
              notification[state === 'SUCCEEDED' ? 'success' : 'error']({
                message: this.$t(state === 'SUCCEEDED'
                  ? 'message.dr.protection.group.terminal.succeeded'
                  : 'message.dr.protection.group.terminal.failed')
              })
              await this.fetchList()
              return
            }
          }
        } catch (error) {
          // Keep the last durable group state visible and retry the read.
        }
        this.groupRunPollTimer = setTimeout(poll, 2000)
      }
      this.groupRunPollTimer = setTimeout(poll, 500)
    },
    stopGroupRunPolling () {
      if (this.groupRunPollTimer) {
        clearTimeout(this.groupRunPollTimer)
        this.groupRunPollTimer = null
      }
      this.groupRunPollDeadline = 0
    },
    groupReasonText (reasonCode, fallback) {
      if (!reasonCode) return fallback || '-'
      const key = drActionReasonMessageKey(reasonCode)
      return typeof this.$te === 'function' && this.$te(key) ? this.$t(key) : (fallback || reasonCode)
    },
    groupActionLabel (action) {
      const labels = {
        SYNC: 'label.dr.action.full.resync',
        TEST_FAILOVER: 'label.dr.action.test.failover',
        TEST_CLEANUP: 'label.dr.action.test.cleanup',
        FAILOVER: 'label.dr.action.failover',
        FAILBACK: 'label.dr.action.failback',
        REPROTECT: 'label.dr.action.reprotect'
      }
      return this.$t(labels[String(action || '').toUpperCase()] || 'label.action')
    },
    groupRpoText (record = {}) {
      const actual = Number(record.currentRpoSeconds)
      const target = Number(record.targetRpoSeconds)
      if (!Number.isFinite(target) || target <= 0) return '-'
      return `${Number.isFinite(actual) && actual >= 0 ? actual : '-'}s / ${target}s`
    },
    paginationTotal (total) {
      const start = total === 0 ? 0 : Math.min(total, 1 + ((this.normalizedPage - 1) * this.pageSize))
      const end = Math.min(this.normalizedPage * this.pageSize, total)
      if (this.$localStorage.get('LOCALE') === 'ko_KR') {
        return `${this.$t('label.total')} ${total} ${this.$t('label.items')} ${this.$t('label.of')} ${start}-${end} ${this.$t('label.showing')}`
      }
      return `${this.$t('label.showing')} ${start}-${end} ${this.$t('label.of')} ${total} ${this.$t('label.items')}`
    },
    normalizeText (value) {
      return String(value || '').trim().toLowerCase()
    },
    matchesKeyword (record, keyword, fields) {
      return fields.some(field => this.normalizeText(record[field]).includes(keyword))
    },
    matchesSearchParams (record, params, fields) {
      return fields.every(field => {
        const value = params[field]
        if (value === '' || value === undefined || value === null) {
          return true
        }
        return this.normalizeText(record[field]).includes(this.normalizeText(value))
      })
    },
    sortBy (field) {
      return (a, b) => this.normalizeText(a[field]).localeCompare(this.normalizeText(b[field]))
    },
    formatSeconds (seconds) {
      const value = Number(seconds)
      if (!Number.isFinite(value)) {
        return '-'
      }
      if (value < 60) {
        return `${Math.round(value)}s`
      }
      if (value < 3600) {
        return `${Math.round(value / 60)}m`
      }
      if (value < 86400) {
        return `${Math.round(value / 3600)}h`
      }
      return `${Math.round(value / 86400)}d`
    },
    formatRpo (plan) {
      const currentValue = plan.displayrposeconds ?? plan.rpoageseconds ?? plan.targetreadyrposeconds
      const current = this.formatSeconds(currentValue)
      const target = this.formatSeconds(plan.rposeconds)
      return `${current} / ${target}`
    },
    isRpoAttentionState (state) {
      return ['RPO_DUE_SOON', 'OVERDUE'].includes(String(state || '').toUpperCase())
    },
    effectivePlanState (plan) {
      return resolveDrPlanState(plan)
    },
    effectivePlanReadiness (plan) {
      return resolveDrReadinessState(plan)
    }
  }
}
</script>

<style lang="less">
.cross-dr-rpo-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.cross-dr-action-modal {
  display: grid;
  gap: 10px;
}

.cross-dr-action-modal .ant-alert {
  margin-bottom: 4px;
}

.cross-dr-release-disposition {
  display: grid;
  gap: 10px;
  width: 100%;
}

.cross-dr-release-disposition .ant-radio-wrapper {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
  width: 100%;
  margin-right: 0;
  padding: 10px 12px;
  border: 1px solid var(--cross-dr-border);
  background: var(--cross-dr-surface);
}

.cross-dr-release-disposition .ant-radio-wrapper-disabled {
  background: var(--cross-dr-surface-muted);
  color: var(--cross-dr-text-secondary);
}

.cross-dr-release-option-title,
.cross-dr-release-option-description {
  display: block;
}

.cross-dr-release-option-title {
  font-weight: 600;
}

.cross-dr-release-option-description {
  margin-top: 3px;
  color: var(--cross-dr-text-secondary);
  white-space: normal;
}

.cross-dr-select-meta {
  float: right;
  margin-left: 12px;
  color: currentColor;
  font-size: 12px;
  opacity: 0.65;
}
</style>
