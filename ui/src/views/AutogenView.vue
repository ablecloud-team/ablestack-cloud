// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div :class="{ 'autoscale-screen': $route.meta?.name === 'autoscalevmgroup', 'userdata-library': $route.meta?.name === 'userdata', 'affinity-library': $route.meta?.name === 'affinitygroup', 'snapshot-list-page': isSnapshotList, 'object-storage-ui': isObjectStorageUi, 'bucket-list-page': isBucketList, 'vm-list-page': isVmList, 'event-list-page': isEventList, 'vm-acl-standard': standardResourceToolbar, 'vpn-user-page': isVpnUser, 'vpn-customer-gateway-page': isVpnCustomerGateway, 'project-standard-page': isProjectUi, 'role-standard-page': isRoleUi, 'account-standard-page': isAccountUi, 'account-standard-list': isAccountList, 'acl-page': isAclUi, 'kms-management-page': kmsRoute($route), 'kms-list-page': kmsListRoute($route),  'public-ip-page': isPublicIpUi, 'volume-list-page': isVolumeList, 'template-list-page': isTemplateList, 'iso-list-page': isIsoList, 'kubernetes-iso-page': $route.meta?.name === 'kubernetesiso', 'kubernetes-iso-list-page': isKubernetesIsoList, 's2s-gateway-list-page': s2sGatewayListRoute($route), 's2s-connection-page': vpnConnectionRoute($route), 'asn-list-page': !!asnSearchAuth, 'themed-list-toolbar': (isSystemOfferingUi && !dataView) || isZoneList || isZoneRelatedList || isAccountList || isRoleList || isGuestVlan || isIpv4Subnet || isDnsZone || isDnsServer || isVpnCustomerGateway || isVpnUser || vpnConnectionRoute($route) || isS2sSearchList || !!asnSearchAuth || isKubernetesIsoList || isPublicIpUi || isTemplateList || isIsoList || isVpcUi || isVmList || isProjectList || isAclUi || isEventList || isVolumeList || isGuestNetworkList || ($route.name === 'sharedfs' && !dataView), 'guest-network-page': isGuestNetworkUi, 'runtime-catalog-screen': $route.meta?.name === 'runtimebundle' }">
    <div class="content-breadcrumb">
      <a-card class="breadcrumb-card" style="z-index: 10">
        <a-row>
          <a-col
            :class="{ 'vm-acl-breadcrumb-layout': standardResourceToolbar }"
            :span="device === 'mobile' ? 24 : 12"
            style="padding-left: 12px; margin-top: 10px"
          >
            <breadcrumb :resource="resource" :standard-layout="standardResourceToolbar">
              <template #end>
                <a-button
                  v-if="$route.meta?.name !== 'runtimebundle'"
                  :loading="loading || ($route.meta?.name === 'vnfapp' && vnfProjectSync) || ((isZoneUi || isPhysicalNetworkUi || vpnConnectionRoute($route) || ['vmgroup', 'vnfapp'].includes($route.meta?.name) || isSnapshotList || isAclUi) && listRequestPending)"
                  :disabled="(isSnapshotList || isAclUi) && listRequestPending || isVpnUser && (vpnUserProjectSwitching || listRequestPending)"
                  style="margin-bottom: 5px"
                  shape="round"
                  size="small"
                  @click="fetchData({ irefresh: true })"
                >
                  <template #icon><reload-outlined /></template>
                  {{ $t('label.refresh') }}
                </a-button>
                <label v-if="isTemplateList || isIsoList || (s2sDisplayFilterId && filters?.length > 0) || (isPublicIpUi && !dataView && filters?.length > 0)" :for="s2sDisplayFilterId || (isPublicIpUi ? 'public-ip-state-filter' : templateFilterId)" class="template-filter-accessible-label">{{ $t('label.filterby') }}</label>
                <label v-if="aclDisplayFilterId && !dataView" :for="aclDisplayFilterId" class="template-filter-accessible-label">{{ $t('label.acl.display.filter') }}</label>
                <label v-if="kmsListRoute($route)" for="kms-display-filter" class="template-filter-accessible-label">{{ $t('label.kms.display.filter') }}</label>
                <a-select
                  v-if="kmsListRoute($route)"
                  id="kms-display-filter"
                  class="kms-display-filter"
                  :value="$route.query.enabled || 'all'"
                  size="small"
                  @change="changeKmsDisplay">
                  <template #suffixIcon><filter-outlined class="ant-select-suffix" /></template>
                  <a-select-option value="all">{{ $t('label.all') }}</a-select-option>
                  <a-select-option value="true">{{ $t('label.enabled') }}</a-select-option>
                  <a-select-option value="false">{{ $t('label.disabled') }}</a-select-option>
                </a-select>
                <a-select
                  v-if="isProjectList"
                  id="project-state-filter"
                  :aria-label="$t('label.state')"
                  :value="$route.query.state || ''"
                  :options="projectStateOptions"
                  size="small"
                  class="project-state-filter"
                  @change="changeProjectState">
                  <template #suffixIcon><filter-outlined /></template>
                </a-select>
                <a-tooltip placement="right">
                  <template #title>
                    {{ $t('label.filterby') }}
                  </template>
                  <a-select
                    v-if="!dataView && filters && filters.length > 0"
                    :placeholder="$t('label.filterby')"
                    :aria-label="aclDisplayFilterId ? $t('label.acl.display.filter') : s2sDisplayFilterId ? $t('label.s2s.display.filter') : isSystemOfferingUi ? $t('label.state') : undefined"
                    :id="aclDisplayFilterId || s2sDisplayFilterId || (isPublicIpUi ? 'public-ip-state-filter' : (isTemplateList || isIsoList) ? templateFilterId : undefined)"
                    :value="filterValue"
                    style="min-width: 100px; margin-left: 10px; margin-bottom: 5px"
                    size=small
                    @change="changeFilter"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                  >
                    <template #suffixIcon><filter-outlined class="ant-select-suffix" /></template>
                    <a-select-option
                      v-if="(['Admin', 'DomainAdmin'].includes($store.getters.userInfo.roletype) || (isIsoList && $store.getters.userInfo.roletype === 'ResourceAdmin')) &&
                      ['vm', 'iso', 'template', 'pod', 'cluster', 'host', 'systemvm', 'router', 'storagepool', 'kubernetes', 'computeoffering', 'systemoffering', 'diskoffering', 'sharedfs', 'extension', 'alertRules', 's2svpn'].includes($route.name) ||
                      ['account'].includes($route.name)"
                      key="all"
                      :label="$t('label.all')"
                    >
                      {{ $t('label.all') }}
                    </a-select-option>
                    <a-select-option v-if="isoRestoredFilter" :key="isoRestoredFilter" :label="getFilterLabel(isoRestoredFilter)">{{ getFilterLabel(isoRestoredFilter) }}</a-select-option>
                    <a-select-option
                      v-for="filter in filters"
                      :key="filter"
                      :label="getFilterLabel(filter)"
                    >
                      {{ getFilterLabel(filter) }}
                      <clock-circle-outlined
                        v-if="['comment'].includes($route.name) && !['Admin'].includes($store.getters.userInfo.roletype) && filter === 'all'"
                      />
                    </a-select-option>
                  </a-select>
                </a-tooltip>
                <a-switch
                  v-if="!dataView && ($route.name !== 'vm' || vmMetricsAvailable($store.getters.apis)) && ['vm', 'volume', 'zone', 'cluster', 'host', 'storagepool', 'managementserver', 'sharedfs'].includes($route.name)"
                  style="margin-left: 8px; min-height: 23px; margin-bottom: 4px"
                  :checked-children="$t('label.metrics')"
                  :un-checked-children="$t('label.metrics')"
                  :checked="$store.getters.metrics"
                  @change="(checked, event) => { $store.dispatch('SetMetrics', checked) }"
                />
                <BucketStoreFilter v-if="isBucketList" />
                <a-tag
                  v-if="(isIsoList || isTemplateList) && $route.query.account && $route.query.account !== '[invalid]' && $route.query.domainid !== '[invalid]'"
                  :title="$route.query.domainid">
                  {{ $t('message.image.account.scope', { account: $route.query.account }) }}
                </a-tag>
                <a-tag v-if="isEventList && !isEventTimeline && eventScopeDiffers">{{ $t('message.events.scope', { scope: eventTimelineScopeLabel }) }}</a-tag>
                <a-switch
                  v-if="!isEventTimeline && !projectView && hasProjectId && (!(vpnConnectionRoute($route) || isVpnUser || isVpnCustomerGateway) || !dataView)"
                  style="margin-left: 8px; min-height: 23px; margin-bottom: 4px"
                  :checked-children="$t('label.projects')"
                  :un-checked-children="$t('label.projects')"
                  :checked="isVpnUser ? vpnUserScope === '-1' : isEventList ? eventProjectScope($route, $store.getters) === '-1' : $store.getters.listAllProjects"
                  :disabled="isVpnUser && vpnUserProjectSwitching"
                  :aria-label="isVpnCustomerGateway ? $t('label.projects') : isVpnUser ? $t('label.vpn.user.scope.all') : undefined"
                  @change="changeListProjects"
                />
              </template>
            </breadcrumb>
          </a-col>
          <a-col
            class="autogen-toolbar-actions"
            :class="{ 'snapshot-list-actions': isSnapshotList }"
            :span="device === 'mobile' ? 24 : 12"
            :style="device === 'mobile' ? { float: 'right', 'margin-top': '12px', 'margin-bottom': '-6px', display: 'table' } : { float: 'right', display: 'table', 'margin-top': '6px' }" >
            <slot name="action" v-if="dataView && $route.path.startsWith('/publicip') && $slots.action"></slot>
            <template v-else>
              <a-button
                v-if="isGuestVlan && dataView && resource.physicalnetworkid && $store.getters.apis.listPhysicalNetworks && $store.getters.apis.listDedicatedGuestVlanRanges"
                class="guest-vlan-range-link"
                @click="$router.push({ path: '/physicalnetwork/' + resource.physicalnetworkid, query: { tab: 'dedicated.vlan.vni.ranges' } })">
                {{ $t('label.dedicated.vlan.vni.ranges') }}
              </a-button>
              <a-tooltip v-if="isSnapshotList && 'createSnapshot' in $store.getters.apis && 'listVolumes' in $store.getters.apis" :title="$t('message.snapshot.list.create.volume')">
                <a-button type="primary" shape="round" @click="$router.push(snapshotSourceVolumeRoute($route))">
                  <plus-outlined /> {{ $t('label.snapshot.list.create') }}
                </a-button>
              </a-tooltip>
              <div
                v-if="dataView && visibleDataViewActions.length > 0"
                class="autogen-action-dropdown__trigger"
                :style="{ float: device === 'mobile' ? 'left' : 'right' }">
                <a-dropdown
                  v-model:visible="detailActionsVisible"
                  :trigger="['click']"
                  placement="bottomRight"
                  overlayClassName="autogen-action-dropdown">
                  <template #overlay>
                    <div class="autogen-action-dropdown__content">
                      <action-button
                        :loading="loading"
                        :actions="visibleDataViewActions"
                        :selectedRowKeys="selectedRowKeys"
                        :selectedItems="selectedItems"
                        :dataView="true"
                        :resource="resource"
                        @exec-action="handleDataViewAction"/>
                    </div>
                  </template>
                  <a-button ref="detailActionsTrigger" type="primary" class="autogen-action-dropdown__button">
                    <template #icon>
                      <down-outlined />
                    </template>
                    {{ $t('label.actions') }}
                  </a-button>
                </a-dropdown>
              </div>
              <action-button
                v-else-if="!dataView && visibleListActions.length > 0"
                :style="{ 'margin-right': '10px', display: getStyle() }"
                :loading="loading"
                :actions="visibleListActions"
                :selectedRowKeys="selectedRowKeys"
                :selectedItems="selectedItems"
                :dataView="false"
                :resource="resource"
                @exec-action="(action) => execAction(action, action.groupAction && !dataView)"/>
              <span v-if="isVpnUser && !dataView && selectedRowKeys.length" class="vpn-user-selection-count" role="status">
                {{ $t('label.vpn.user.selected', [selectedRowKeys.length]) }}
              </span>
            </template>
            <a-button
              v-if="$route.meta?.name === 'runtimebundle'"
              data-testid="runtime-catalog-refresh"
              :loading="loading"
              :aria-label="$t('label.refresh')"
              @click="fetchData({ irefresh: true })">
              <template #icon><reload-outlined /></template>
              {{ $t('label.refresh') }}
            </a-button>
            <AsnListTools v-if="asnSearchAuth" :selected="selectedItems" :ready="asnListReady" :scope="listScope()" @refresh="resetSelection(); $refs.listview?.resetSelection(); fetchData({ irefresh: true })" />
            <ProjectListSearch v-if="isProjectList" class="project-toolbar-search" @search="onSearch" />
            <search-view
              v-else-if="!dataView"
              :class="{ 'kms-toolbar-search': kmsListRoute($route), 'vm-acl-toolbar-search': standardResourceToolbar, 'account-toolbar-search': isAccountList }"
              :searchFilters="searchFilters"
              :searchParams="searchParams"
              :apiName="apiName"
              :asn-search-failure="asnSearchFailure"
              :s2s-gateway-search-failure="s2sGatewaySearchFailure"
              @search="onSearch"
              @change-filter="changeFilter"
            />
          </a-col>
        </a-row>
        <S2sGatewayFilterChips v-if="isS2sSearchList" :api-name="s2sSearchApi" :validation-error="s2sGatewaySearchFailure" @search="onSearch" />
        <a-row
          v-if="showSearchFilters"
          style="min-height: 36px; padding-top: 12px; padding-left: 12px;"
        >
          <search-filter
            :filters="activeFiltersList"
            :apiName="apiName"
            @removeFilter="removeFilter"
          />
        </a-row>
      </a-card>
    </div>

    <a-alert
      v-if="(isIsoList && isoListError) || (isTemplateList && templateFilterError)"
      class="image-list-query-error"
      type="error"
      show-icon
      :message="$t(isIsoList ? isoListError : templateFilterError)" />

    <div v-show="showAction">
      <component
        v-if="showAction && currentAction.selfManagedDialog"
        :key="(currentAction.kmsTarget || currentAction.storageSnapshotMode || currentAction.policyDeleteDialog || currentAction.vmDeletionTargets || currentAction.vmEditCurrent || currentAction.vnfReinstallCurrent || currentAction.vnfUnmanageCurrent || currentAction.systemOfferingState) ? storageActionGeneration : undefined"
        ref="selfManagedAction"
        :is="currentAction.component"
        :resource="resource"
        :current-action="currentAction"
        :selected-items="selectedItems"
        v-bind="isVpnUser && currentAction.api === 'addVpnUser' || vpnCustomerGatewayActionScope || currentAction.ipv4SubnetDeleteTargets || currentAction.systemOfferingState ? { afterClose: restoreClosedActionFocus } : {}"
        dialog-only
        @refresh-data="refreshActionData"
        @close-action="closeAction"
      />
      <keep-alive :max="(isStorageUi || isPolicyUi || isObjectStorageUi || currentAction.vmDeletionTargets || currentAction.vmEditCurrent || currentAction.vnfReinstallCurrent || vmStandardDialog || roleStandardDialog || accountStandardDialog || publicIpStandardDialog || isDnsZone || isDnsServer || isZoneUi) ? 1 : undefined" v-else-if="currentAction.component && (!currentAction.invokedAsGroupAction || ['destroyVirtualMachine', 'deleteBackup', 'destroySharedFileSystem'].includes(currentAction.api))">
        <component
          :is="currentAction.fixedFormFooter ? 'SharedFSFormDialog' : isVpcUi ? 'VpcDialog' : standardActionDialog ? 'MoldDialog' : 'a-modal'"
          :size="currentAction.standardDialogSize || (vmStandardDialog || roleStandardDialog || accountStandardDialog ? 'md' : isVpcUi || isObjectStorageUi ? 'md' : isStorageUi ? storageDialogSize(currentAction.api) : undefined)"
          class="mold-dialog"
          :class="{ 'object-storage-dialog': isObjectStorageUi, 'autoscale-dialog': $route.meta?.name === 'autoscalevmgroup', 'storage-standard-dialog': standardActionDialog, 'vpc-standard-dialog': isVpcUi, 'snapshot-standard-dialog': currentAction.snapshotStandardDialog, 'sharedfs-standard-dialog': $route.meta?.name === 'sharedfs' }"
          centered
          :visible="showAction"
          :closable="true"
          :maskClosable="false"
          :cancelText="$t('label.cancel')"
          @cancel="cancelAction"
          :confirmLoading="actionLoading"
          :footer="standardActionDialog ? undefined : null"
          :form-actions="standardActionDialog"
          :focus-guard="isSystemOfferingUi && standardActionDialog || isZoneUi || isPhysicalNetworkUi || isDnsZone || isDnsServer || isIpv4Subnet || isVpcUi || isProjectUi || vmStandardDialog || roleStandardDialog || accountStandardDialog || publicIpStandardDialog || !!currentAction.snapshotStandardDialog || !!currentAction.vnfReinstallCurrent"
          :after-close="restoreClosedActionFocus"
          :width="currentAction.dialogWidth || 'auto'"
        >
          <template #title>
            <span v-if="currentAction.label">{{ $t(currentAction.label) }}</span>
            <a
              v-if="currentAction.docHelp || $route.meta.docHelp"
              style="margin-left: 5px"
              :href="documentationUrl($config.docBase, currentAction.docHelp || $route.meta.docHelp, $i18n.locale)"
              target="_blank"
              :aria-label="$t('label.help')"
              rel="noopener noreferrer"
            >
              <question-circle-outlined />
            </a>
          </template>
          <VpcActionContext v-if="isVpcUi && resource.id && !currentAction.listView" :resources="[resource]" />
          <keep-alive :max="(isStorageUi || isPolicyUi || isObjectStorageUi || currentAction.vmDeletionTargets || currentAction.vmEditCurrent || currentAction.vnfReinstallCurrent || vmStandardDialog || roleStandardDialog || accountStandardDialog || publicIpStandardDialog || isDnsZone || isDnsServer || isZoneUi) ? 1 : undefined">
            <component
              :key="(isStorageUi || isPolicyUi || isObjectStorageUi || currentAction.vmDeletionTargets || currentAction.vmEditCurrent || currentAction.vnfReinstallCurrent || vmStandardDialog || roleStandardDialog || accountStandardDialog || publicIpStandardDialog || isDnsZone || isDnsServer || isZoneUi) ? storageActionGeneration : undefined"
              :is="currentAction.component"
              :resource="resource"
              :loading="loading"
              :action="{currentAction}"
              :selectedRowKeys="selectedRowKeys"
              :selectedItems="selectedItems"
              :chosenColumns="presentedChosenColumns"
              v-bind="{currentAction}"
              @refresh-data="fetchData"
              @poll-action="pollActionCompletion"
              @restore-started="markBackupRestoreStarted"
              @close-action="closeAction"
              @cancel-bulk-action="handleCancel"
            />
          </keep-alive>
        </component>
      </keep-alive>
      <event-csv-dialog
        v-else-if="showAction && currentAction.api === 'downloadEvents'"
        :key="storageActionGeneration"
        :ids="currentAction.eventCsvIds"
        :doc-help="currentAction.docHelp"
        :after-close="restoreClosedActionFocus"
        @close="closeAction"
      />
      <vpn-user-delete-dialog
        v-else-if="showAction && currentAction.vpnUserDeleteTargets"
        :key="storageActionGeneration"
        :targets="currentAction.vpnUserDeleteTargets"
        :current="currentAction.vpnUserDeleteCurrent"
        :after-close="restoreClosedActionFocus"
        @close="finishVpnUserDeletion"
        @completed="finishVpnUserDeletion(true, true)"
      />
      <event-mutation-dialog
        v-else-if="showAction && currentAction.eventMutationTargets"
        :key="storageActionGeneration"
        :api="currentAction.api"
        :targets="currentAction.eventMutationTargets"
        :doc-help="currentAction.docHelp"
        :after-close="restoreClosedActionFocus"
        @close="closeAction"
        @completed="finishEventMutation(true)"
        @refresh="finishEventMutation(false)"
      />
      <component
        :is="isVpcUi ? 'VpcDialog' : (isStorageUi || isBucketGroupDelete || isGuestNetworkDelete || vmStandardDialog || projectStandardDialog || roleStandardDialog || accountStandardDialog || aclStandardDialog || publicIpReleaseDialog || infrastructureStandardDialog) ? 'MoldDialog' : 'a-modal'"
        :size="(isVpcUi || isStorageUi || isBucketGroupDelete || isGuestNetworkDelete || vmStandardDialog || projectStandardDialog || roleStandardDialog || accountStandardDialog || aclStandardDialog || publicIpReleaseDialog || infrastructureStandardDialog) ? 'md' : undefined"
        class="mold-dialog"
        :class="{ 'storage-standard-dialog': infrastructureStandardDialog || isStorageUi || isBucketGroupDelete || isGuestNetworkDelete || vmStandardDialog || projectStandardDialog || roleStandardDialog || accountStandardDialog || aclStandardDialog || publicIpReleaseDialog, 'vpc-standard-dialog': isVpcUi, 'acl-standard-dialog': aclStandardDialog, 'object-storage-dialog': isBucketGroupDelete, 'autoscale-dialog': $route.meta?.name === 'autoscalevmgroup' }"
          v-else
        :visible="showAction"
        :closable="!(isVpcUi || vmStandardDialog || projectStandardDialog || roleStandardDialog || accountStandardDialog || aclStandardDialog || publicIpReleaseDialog || infrastructureStandardDialog) || !(actionLoading || vmActionValidationPending)"
        :maskClosable="false"
        :width="(isBucketGroupDelete || isGuestNetworkDelete || publicIpReleaseDialog || aclStandardDialog) ? 'min(720px, calc(100vw - 32px))' : (currentAction.invokedAsGroupAction ? modalWidth : 760)"
        :focus-guard="isVpcUi || vmStandardDialog || projectStandardDialog || roleStandardDialog || accountStandardDialog || aclStandardDialog || publicIpReleaseDialog || infrastructureStandardDialog"
        :keyboard="!(isVpcUi || vmStandardDialog || projectStandardDialog || roleStandardDialog || accountStandardDialog || aclStandardDialog || publicIpReleaseDialog || infrastructureStandardDialog) || !(actionLoading || vmActionValidationPending)"
        :after-close="restoreClosedActionFocus"
        :ok-button-props="getOkProps()"
        ok-text="111"
        :cancel-button-props="getCancelProps()"
        :confirmLoading="actionLoading"
        @cancel="cancelAction"
        centered
      >
        <template #title>
          <span v-if="currentAction.label">{{ $t(currentAction.label) }}</span>
          <a
            v-if="currentAction.docHelp || $route.meta.docHelp"
            style="margin-left: 5px"
            :href="documentationUrl($config.docBase, currentAction.docHelp || $route.meta.docHelp, $i18n.locale)"
            target="_blank"
            :aria-label="$t('label.help')"
          >
            <question-circle-outlined />
          </a>
        </template>
        <VpcActionContext v-if="isVpcUi && !currentAction.listView" :resources="currentAction.invokedAsGroupAction ? selectedItems : [resource]" />
        <dl v-if="aclStandardDialog && !currentAction.listView && resource.id" class="acl-action-context">
          <dt>{{ $t('label.name') }}</dt><dd>{{ resource.name }}</dd>
          <dt>{{ $t('label.scope') }}</dt><dd><AclScopeValue :resource="resource" :linked="false" /></dd>
        </dl>
        <a-alert v-if="vmStandardDialog && vmActionError" type="error" show-icon :message="vmActionError" class="vm-action-error" />
        <a-alert v-if="roleStandardDialog && roleActionError" type="error" show-icon :message="roleActionError" class="vm-action-error" />
        <a-alert v-if="projectStandardDialog && projectActionError" type="error" show-icon :message="projectActionError" class="vm-action-error" />
        <a-alert v-if="isVpcUi && vpcActionError" type="error" show-icon :message="vpcActionError" style="margin-bottom: 16px" />
        <a-spin
          :spinning="actionLoading"
          v-ctrl-enter="vmStandardDialog || projectStandardDialog ? false : handleSubmit"
          @keydown.ctrl.enter.prevent="vmStandardDialog && !$event.repeat && handleSubmit($event)"
        >
          <div @keydown.ctrl.enter.capture="handleProjectDialogShortcut">
            <div v-if="isGuestNetworkDelete && !currentAction.invokedAsGroupAction" style="display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; overflow-wrap: anywhere">
              <strong>{{ resource.name || resource.id }}</strong>
              <span>{{ resource.type }} · {{ resource.id }}</span>
            </div>
            <PublicIpReleaseSummary v-if="publicIpReleaseDialog" :resources="currentAction.invokedAsGroupAction ? selectedItems : [currentAction.resource || resource]" />
            <VmSnapshotSummary v-if="currentAction.api === 'createVMSnapshot'" vm-only :snapshot="{ virtualmachineid: currentAction.resource.id, virtualmachinename: currentAction.resource.displayname || currentAction.resource.name, virtualmachineinstancename: currentAction.resource.instancename, virtualmachinestate: currentAction.resource.state }" />
            <InstanceGroupDeleteSummary v-if="currentAction.api === 'deleteInstanceGroup'" :groups="currentAction.vmGroupTargets || []" />
            <span v-if="currentAction.message">
              <div v-if="selectedRowKeys.length > 0 && currentAction.invokedAsGroupAction">
                <a-alert
                  v-if="['delete-outlined', 'DeleteOutlined', 'poweroff-outlined', 'PoweroffOutlined'].includes(currentAction.icon)"
                  type="error"
                >
                  <template #message>
                    <exclamation-circle-outlined style="color: red; fontSize: 30px; display: inline-flex" />
                    <span style="padding-left: 5px" v-html="`<b>${selectedRowKeys.length}` + $t('label.items.selected') + `. </b>&nbsp`" />
                    <span v-html="currentAction.message" />
                  </template>
                </a-alert>
                <a-alert
                  v-else
                  type="warning"
                >
                  <template #message>
                    <span v-if="selectedRowKeys.length > 0" v-html="`<b>${selectedRowKeys.length}` + $t('label.items.selected') + `. </b>&nbsp`" />
                    <span v-html="currentAction.message" />
                  </template>
                </a-alert>
              </div>
              <div v-else>
                <a-alert type="warning">
                  <template #message>
                    <span v-html="currentAction.message" />
                  </template>
                </a-alert>
              </div>
              <div v-if="selectedRowKeys.length > 0 && currentAction.invokedAsGroupAction">
                <a-divider />
                <a-table
                  v-if="selectedRowKeys.length > 0 && currentAction.api !== 'deleteInstanceGroup'"
                  size="middle"
                  :columns="presentedChosenColumns"
                  :dataSource="selectedItems"
                  :rowKey="(record, idx) => record.id || record.name || record.usageType || idx + '-' + Math.random()"
                  :pagination="true"
                  :scroll="isBucketGroupDelete ? { x: 760 } : undefined"
                  style="overflow-y: auto"
                >
                  <template #bodyCell="{ column, text, record }">
                    <public-ip-detail-value v-if="$route.meta?.name === 'publicip' && ['state', 'allocated', 'issystem', 'issourcenat', 'isstaticnat', 'fordisplay'].includes(column.key)" :resource="record" :field="column.key" compact />
                    <template v-if="column.key === 'displayname' && isVmIdentityRoute($route)">{{ vmDisplayName(record) }}</template>
                    <template v-if="column.key === 'allocated' && $route.meta.name === 'asnumbers'">
                      {{ $toLocaleDate(text) }}
                    </template>
                  </template>
                </a-table>
              </div>
              <br v-if="currentAction.paramFields.length > 0" />
            </span>
            <a-alert
              v-if="projectStandardDialog && currentAction.api === 'deleteProject' && currentAction.paramFields.some(field => field.name === 'cleanup')"
              :type="form.cleanup ? 'warning' : 'info'"
              show-icon
              :message="$t(form.cleanup ? 'message.project.delete.cleanup' : 'message.project.delete.keep')"
              class="vm-action-error" />
            <network-operation-policy v-if="isGuestNetworkDelete" context="delete" :network-type="resource.type" />
            <div v-if="requiresNameConfirmation" style="margin-bottom: 5px">
              <a-form-item>
                <a-input v-model:value="actionConfirmText" :placeholder="resource.name" :aria-label="$t('label.delete.confirmation')" v-focus="projectStandardDialog" />
              </a-form-item>
              <a-alert type="info" :message="$t('label.delete.confirmation')" />
            </div>
            <a-form
              :ref="formRef"
              :model="form"
              :rules="rules"
              @finish="handleSubmit"
              layout="vertical"
            >
              <div
                v-for="(field, fieldIndex) in currentAction.paramFields"
                :key="fieldIndex"
              >
                <a-form-item
                  :name="field.name"
                  :ref="field.name"
                  :v-bind="field.name"
                  :extra="currentAction.api === 'createNetworkACLList' && field.name === 'vpcid' ? $t('message.acl.scope.create') : undefined"
                  v-if="!(currentAction.mapping && field.name in currentAction.mapping && currentAction.mapping[field.name].value)"
                >
                  <template #label>
                    <tooltip-label
                      v-if="['domain', 'guestcidraddress'].includes(field.name) && ['createZone', 'updateZone'].includes(currentAction.api)"
                      :title="$t('label.default.network.' + field.name + '.isolated.network')"
                      :tooltip="actionFieldDescription(field)"
                    />
                    <tooltip-label
                      v-else-if="field.name === 'keepmacaddressonpublicnic' && currentAction.api === 'updateVPC'"
                      :title="$t('label.keep.mac.address.on.public.nic')"
                      :tooltip="actionFieldDescription(field)"
                    />
                    <tooltip-label
                      v-else
                      :title="$t('label.' + field.name)"
                      :tooltip="actionFieldDescription(field)"
                    />
                  </template>

                  <a-switch
                    v-if="field.type==='boolean'"
                    v-model:checked="form[field.name]"
                    :aria-label="field.name === 'fordisplay' ? $t('label.fordisplay') : undefined"
                    :placeholder="actionFieldDescription(field)"
                    v-focus="fieldIndex === firstIndex"
                  />
                  <a-select
                    v-else-if="currentAction.mapping && field.name in currentAction.mapping && currentAction.mapping[field.name].options"
                    :loading="field.loading"
                    v-model:value="form[field.name]"
                    :placeholder="actionFieldDescription(field)"
                    v-focus="fieldIndex === firstIndex"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                  >
                    <a-select-option
                      key=""
                      label=""
                    >{{ }}</a-select-option>
                    <a-select-option
                      v-for="(opt, optIndex) in currentAction.mapping[field.name].options"
                      :key="optIndex"
                      :label="opt"
                    >
                      {{ opt }}
                    </a-select-option>
                  </a-select>
                  <a-select
                    v-else-if="field.name==='keypair' ||
                      (field.name==='account' && !['addAccountToProject', 'createAccount'].includes(currentAction.api))"
                    showSearch
                    optionFilterProp="label"
                    v-model:value="form[field.name]"
                    @change="val => handleSelectChange(field.name, val)"
                    :loading="field.loading"
                    :placeholder="actionFieldDescription(field)"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                    v-focus="fieldIndex === firstIndex"
                  >
                    <a-select-option
                      key=""
                      label=""
                    >{{ }}</a-select-option>
                    <a-select-option
                      v-for="(opt, optIndex) in field.opts"
                      :key="optIndex"
                      :label="opt.name || opt.description || opt.traffictype || opt.publicip"
                    >
                      {{ opt.name || opt.description || opt.traffictype || opt.publicip }}
                    </a-select-option>
                  </a-select>
                  <a-select
                    v-else-if="field.type==='uuid'"
                    showSearch
                    optionFilterProp="label"
                    v-model:value="form[field.name]"
                    @change="val => handleSelectChange(field.name, val)"
                    :loading="field.loading"
                    :placeholder="actionFieldDescription(field)"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                    v-focus="fieldIndex === firstIndex"
                  >
                    <a-select-option
                      key=""
                      label=""
                    >{{ }}</a-select-option>
                    <a-select-option
                      v-for="opt in field.opts"
                      :key="opt.id"
                      :label="opt.name || opt.description || opt.traffictype || opt.publicip"
                    >
                      <div>
                        <span v-if="(field.name.startsWith('template') || field.name.startsWith('iso'))">
                          <span v-if="opt.icon">
                            <resource-icon
                              :image="opt.icon.base64image"
                              size="1x"
                              style="margin-right: 5px"
                            />
                          </span>
                          <os-logo
                            v-else
                            :osId="opt.ostypeid"
                            :osName="opt.ostypename"
                            size="lg"
                            style="margin-left: -1px"
                          />
                        </span>
                        <span v-if="(field.name.startsWith('zone'))">
                          <span v-if="opt.icon">
                            <resource-icon
                              :image="opt.icon.base64image"
                              size="1x"
                              style="margin-right: 5px"
                            />
                          </span>
                          <global-outlined
                            v-else
                            style="margin-right: 5px"
                          />
                        </span>
                        <span v-if="(field.name.startsWith('project'))">
                          <span v-if="opt.icon">
                            <resource-icon
                              :image="opt.icon.base64image"
                              size="1x"
                              style="margin-right: 5px"
                            />
                          </span>
                          <project-outlined
                            v-else
                            style="margin-right: 5px"
                          />
                        </span>
                        <span v-if="(field.name.startsWith('account') || field.name.startsWith('user'))">
                          <span v-if="opt.icon">
                            <resource-icon
                              :image="opt.icon.base64image"
                              size="1x"
                              style="margin-right: 5px"
                            />
                          </span>
                          <user-outlined
                            v-else
                            style="margin-right: 5px"
                          />
                        </span>
                        <span v-if="(field.name.startsWith('network'))">
                          <span v-if="opt.icon">
                            <resource-icon
                              :image="opt.icon.base64image"
                              size="1x"
                              style="margin-right: 5px"
                            />
                          </span>
                          <apartment-outlined
                            v-else
                            style="margin-right: 5px"
                          />
                        </span>
                        <span v-if="(field.name.startsWith('domain'))">
                          <span v-if="opt.icon">
                            <resource-icon
                              :image="opt.icon.base64image"
                              size="1x"
                              style="margin-right: 5px"
                            />
                          </span>
                          <block-outlined
                            v-else
                            style="margin-right: 5px"
                          />
                        </span>
                        {{ opt.name || opt.description || opt.traffictype || opt.publicip }}
                      </div>
                    </a-select-option>
                  </a-select>
                  <a-select
                    v-else-if="field.type==='list'"
                    :loading="field.loading"
                    mode="multiple"
                    v-model:value="form[field.name]"
                    @change="val => handleSelectChange(field.name, val)"
                    :placeholder="actionFieldDescription(field)"
                    v-focus="fieldIndex === firstIndex"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }"
                  >
                    <a-select-option
                      v-for="(opt, optIndex) in field.opts"
                      :key="optIndex"
                      :label="(opt.name && opt.type ? opt.name + ' (' + opt.type + ')' : opt.name || opt.description)"
                    >
                      {{ (opt.name && opt.type ? opt.name + ' (' + opt.type + ')' : opt.name || opt.description) }}
                    </a-select-option>
                  </a-select>
                  <details-input
                    v-else-if="field.type==='map'"
                    v-model:value="form[field.name]"
                    :optionalKeys="currentAction.mapping?.[field.name]?.optionalKeys || []" />
                  <a-input-number
                    v-else-if="field.type==='long'"
                    v-focus="fieldIndex === firstIndex"
                    style="width: 100%;"
                    v-model:value="form[field.name]"
                    :placeholder="actionFieldDescription(field)"
                  />
                  <a-input-password
                    v-else-if="field.name==='password' || field.name==='currentpassword' || field.name==='confirmpassword' || field.name==='secretkey'"
                    v-model:value="form[field.name]"
                    :placeholder="actionFieldDescription(field)"
                    @blur="($event) => handleConfirmBlur($event, field.name)"
                    v-focus="fieldIndex === firstIndex"
                  />
                  <a-textarea
                    v-else-if="field.name==='certificate' || field.name==='privatekey' || field.name==='certchain'"
                    rows="2"
                    v-model:value="form[field.name]"
                    :placeholder="actionFieldDescription(field)"
                    v-focus="fieldIndex === firstIndex"
                  />
                  <a-input
                    v-else
                    v-focus="fieldIndex === firstIndex"
                    v-model:value="form[field.name]"
                    :placeholder="actionFieldDescription(field)"
                  />
                </a-form-item>
              </div>

            </a-form>
          </div>
        </a-spin>
        <template #footer>
          <a-button
            v-focus="aclDeleteDialog"
            @click="vmStandardDialog || projectStandardDialog || roleStandardDialog || accountStandardDialog || aclStandardDialog || infrastructureStandardDialog ? cancelAction() : closeAction()"
            :disabled="(vmStandardDialog || projectStandardDialog || roleStandardDialog || accountStandardDialog || aclStandardDialog || publicIpReleaseDialog || infrastructureStandardDialog) && (actionLoading || vmActionValidationPending)">
            {{ $t('label.cancel') }}
          </a-button>
          <a-button
            type="primary"
            @click="handleSubmit"
            :disabled="isSubmitDisabled || (vmStandardDialog && (vmActionValidationPending || vmActionUnknown))"
            :loading="actionLoading || (vmStandardDialog && vmActionValidationPending)"
            :danger="aclDeleteDialog || (projectStandardDialog && currentAction.api === 'deleteProject') || publicIpReleaseDialog || isVpcUi && currentAction.api?.startsWith('delete') || vmStandardDialog && currentAction.api === 'stopVirtualMachine'"
            ref="submit">
            {{ $t(publicIpReleaseDialog ? 'label.public.ip.release.confirm' : (isVpcUi || vmStandardDialog || projectStandardDialog || roleStandardDialog || accountStandardDialog || aclStandardDialog || infrastructureStandardDialog) && currentAction.label ? currentAction.label : 'label.ok') }}
          </a-button>
        </template>
      </component>
    </div>

    <div
      :style="this.$store.getters.maintenanceInitiated || this.$store.getters.shutdownTriggered ? 'margin-top: 24px; margin-bottom: 12px' : null"
    >
      <a-alert v-if="s2sGatewayRoute($route) && dataView && listRefreshError" type="error" show-icon :message="$t(s2sGatewaySearchFailure === 'permission' || s2sGatewaySearchFailure === 'scope' ? 'message.s2s.gateway.search.' + s2sGatewaySearchFailure : resource.id ? 'message.list.refresh.stale' : 'error.fetching.data')"><template #description><a-button @click="fetchData({ irefresh: true })">{{ $t('label.retry') }}</a-button></template></a-alert>
      <InstanceGroupQueryStatus
        v-if="dataView && $route.meta?.name === 'vmgroup'"
        :status="vmGroupQueryStatus"
        :updated="listLastUpdated"
        :retained="!!listLoadedScope"
        @retry="fetchData({ irefresh: true })" />
      <a-alert v-if="listRefreshError && (isSystemOfferingUi || dataView && (isVpcUi || isStorageUi || isSnapshotReadView || publicIpDetail || isAclUi))" class="storage-query-error" type="error" show-icon :message="$t(listLoadedScope ? 'message.list.refresh.stale' : 'error.fetching.data')">
        <template #description><p v-if="isSnapshotReadView && snapshotListError">{{ snapshotListError.startsWith('message.') ? $t(snapshotListError) : snapshotListError }}</p><a-button size="small" :disabled="listRequestPending" @click="fetchData({ irefresh: true })">{{ $t('label.retry') }}</a-button></template>
      </a-alert>
      <a-alert v-if="dataView && $route.meta?.name === 'kubernetesiso' && kubernetesIsoReadError" type="error" show-icon :message="$t((kubernetesIsoReadError === 'unsupported' ? 'message.kubernetes.iso.search.unsupported' : kubernetesIsoReadError === 'searchInvalid' ? 'message.kubernetes.iso.search.invalid' : 'message.kubernetes.iso.read.' + kubernetesIsoReadError))">
        <template #description><a-button size="small" :disabled="listRequestPending" @click="fetchData({ irefresh: true })">{{ $t('label.retry') }}</a-button></template>
      </a-alert>
      <a-alert v-if="isVpnUser && listRefreshError" class="vpn-user-read-error" type="error" show-icon :message="$t('message.vpn.user.search.failed')">
        <template #description><a-button size="small" :disabled="listRequestPending || vpnUserProjectSwitching" @click="fetchData({ irefresh: true })">{{ $t('label.retry') }}</a-button></template>
      </a-alert>
      <div v-if="dataView">
        <a-alert v-if="isKmsDetail && kmsDetailError" type="error" show-icon :message="$t('message.kms.detail.' + kmsDetailError)">
          <template #description>
            <a-space>
              <router-link :to="currentKmsListLocation()">{{ $t('message.kms.detail.back') }}</router-link>
              <a-button v-if="kmsDetailError !== 'missing'" size="small" :disabled="listRequestPending" @click="fetchData({ irefresh: true })">{{ $t('label.retry') }}</a-button>
            </a-space>
          </template>
        </a-alert>
        <a-alert v-else-if="templateDetailError" type="error" show-icon :message="$t(templateDetailError)">
          <template #description>
            <router-link :to="templateListLocation($route, $store.getters)">{{ $t('message.template.detail.back') }}</router-link>
            <a-button v-if="templateDetailError === 'message.template.detail.read.failed'" size="small" :disabled="listRequestPending" @click="fetchData({ irefresh: true })">{{ $t('label.refresh') }}</a-button>
          </template>
        </a-alert>
        <a-spin v-else-if="isSshKeyPairDetail && !sshKeyPairDetailVisible" :spinning="loading">
          <a-alert show-icon :type="loading ? 'info' : 'error'" :message="$t('message.ssh.keypair.detail.' + sshKeyPairDetailStatus)">
            <template #action>
              <a-button :disabled="loading" @click="fetchData({ irefresh: true })">{{ $t('label.refresh') }}</a-button>
              <router-link to="/ssh" style="margin-left: 8px">{{ $t('label.ssh.key.pairs') }}</router-link>
            </template>
          </a-alert>
        </a-spin>
        <a-alert
          v-if="isVpnConnectionDetail && (listRefreshError || (!loading && !resource.id))"
          class="s2s-connection-detail-notice"
          show-icon
          :type="listRefreshError ? 'error' : 'info'"
          :message="$t(listRefreshError ? 'error.fetching.data' : 'message.s2s.connection.detail.missing')">
          <template #description>
            <a-button size="small" :loading="listRequestPending" @click="fetchData({ irefresh: true })">{{ $t('label.retry') }}</a-button>
          </template>
        </a-alert>
        <slot
          name="resource"
          v-if="$route.path.startsWith('/publicip')"
        ></slot>
        <resource-view
          v-else-if="(!isKmsDetail || (resource.id || loading) && !kmsDetailError) && (!(s2sGatewayRoute($route) || isVpnConnectionDetail) || resource.id || loading) && (!isVpnUser || !listRefreshError && (resource.id || loading)) && !templateDetailError && !($route.meta?.name === 'kubernetesiso' && kubernetesIsoReadError) && (!(isSnapshotDetail || isPolicyDetail) || resource.id || loading) && (!isSshKeyPairDetail || sshKeyPairDetailVisible) && ($route.meta?.name !== 'vmgroup' || resource.id || loading)"
          :key="isKmsDetail || isSshKeyPairDetail || isSnapshotDetail || isPolicyDetail ? listScope() : $route.path"
          :readPending="listRequestPending"
          :readFailed="(isVpcUi || isAclUi || ['ilb', 'vm', 'vnfapp', 's2svpn', 's2svpnconn'].includes($route.meta?.name)) && listRefreshError"
          :readyForActions="isKmsDetail ? kmsDetailReady : $route.meta?.name === 'kubernetesiso' ? !listRequestPending && !kubernetesIsoReadError && listLoadedScope === listScope() : ['vm', 'vnfapp', 's2svpn', 's2svpnconn', 'vpncustomergateway'].includes($route.meta?.name) ? storageActionReady : (isStorageUi || isObjectStorageUi || isSnapshotDetail || isPolicyDetail) ? storageActionReady : isSshKeyPairDetail ? sshKeyPairDetailActionReady : ($route.meta?.name === 'cniconfiguration' ? cniDetailActionReady : ($route.meta?.name !== 'vmgroup' || vmGroupQueryReady))"
          :resource="resource"
          :loading="loading"
          :quiet-refresh="isZoneUi || isPhysicalNetworkUi || isIpv4Subnet || isGuestVlan || s2sGatewayRoute($route) || isVpnConnectionDetail || isVpcUi || ['sharedfs', 'runtimebundle'].includes($route.meta?.name)"
          :tabs="$route.meta.tabs"
          :actions="(isSshKeyPairDetail && !sshKeyPairDetailActionReady) || ($route.meta?.name === 'vmgroup' && !vmGroupQueryReady) ? [] : actions"
          @exec-action="handleDataViewAction"
          @template-operation-retained="retainTemplateOperation"
          @iso-operation-retained="retainIsoOperation"
          @backup-controls-change="Object.assign(resource, $event)"
          @refresh="['template','buckets', 'objectstore'].includes($route.meta.name) && fetchData({ irefresh: true })"
          @change-resource="resource = $event" />
      </div>
      <div
        class="row-element"
        v-else
      >
        <advisories-view
          v-if="$route.meta.advisories && !loading"
        />
        <p v-if="isVmList && listRefreshError" role="status">{{ $t('message.list.refresh.stale') }}</p>
        <list-view
          v-if="!isVpnUser || !listRefreshError"
          :loading="sharedFSContentLoading || isVpnUser && vpnUserProjectSwitching"
          :columns="presentedColumns"
          :kms-table="kmsListRoute($route)"
          :items="items"
          :actions="(asnSearchAuth && asnSearchFailure) || isSnapshotCopyList || snapshotSearchFailure || policySearchFailure || ((isSnapshotList || isPolicyList || isObjectStorageUi || $route.meta?.name === 'vnfapp') && !storageActionReady) || ($route.meta?.name === 'vmgroup' && !vmGroupQueryReady) ? [] : actions"
          :selectionDisabled="record => ((isProjectList || isVpnUser || isVpnCustomerGateway) && !storageActionReady) || (asnSearchAuth && !asnListReady) || (isSnapshotList || isPolicyList || isObjectStorageUi || $route.meta?.name === 'vnfapp') && !storageActionReady"
          :explicitlyAllowRowSelection="policySelectionAvailable || !!asnSearchAuth"
          :tableScroll="isRoleList ? { x: 670 } : isGuestVlan || isIpv4Subnet ? { x: presentedColumns.reduce((sum, column) => sum + column.width, 0) } : isProjectList ? { x: projectTableWidth } : isEventList ? { x: 1210 } : isVpnUser ? { x: 458 } : isVpnCustomerGateway ? { x: presentedColumns.reduce((sum, column) => sum + (column.width || 0), 36) } : asnSearchAuth ? { x: 1640 } : s2sGatewayListRoute($route) ? { x: 860 } : vpnConnectionListRoute($route) ? { x: vpnConnectionTableWidth } : undefined"
          :keyboardContextMenu="isSnapshotList"
          :currentPage="page"
          :pageSize="pageSize"
          :columnKeys="columnKeys"
          :selectedColumns="selectedColumns"
          ref="listview"
          @update-selected-columns="updateSelectedColumns"
          @selection-change="onRowSelectionChange"
          @refresh="fetchData"
          @table-change="handleTableChange"
        />
        <a-pagination
          v-if="(!isVpnUser || !listRefreshError) && ((!isSnapshotList && !['vmgroup', 'vnfapp'].includes($route.meta?.name)) || listLoadedScope)"
          class="row-element"
          style="margin-top: 10px"
          size="small"
          :current="page"
          :pageSize="pageSize"
          :total="itemCount"
          :showTotal="total => this.$localStorage.get('LOCALE') == 'ko_KR' ?
            `${$t('label.total')} ${total} ${$t('label.items')} ${$t('label.of')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.showing')}` :
            `${$t('label.showing')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
          :pageSizeOptions="pageSizeOptions"
          @change="changePage"
          @showSizeChange="onListPageSizeChange"
          showSizeChanger
          showQuickJumper
        >
          <template #buildOptionText="props">
            <span>{{ props.value }} / {{ $t('label.page') }}</span>
          </template>
        </a-pagination>
      </div>
    </div>
    <bulk-action-progress
      :showGroupActionModal="showGroupActionModal"
      :selectedItems="selectedItems"
      :selectedColumns="bulkColumns"
      :message="modalInfo"
      @handle-cancel="handleCancel"
    />
  </div>
</template>

<script>
import '@/style/components/view/kubernetes-iso.less'
import { isVmDeletion } from '@/utils/vmDeletion'
import { templateObservationContext } from '@/utils/templateAvailability'
import { isoZoneContext } from '@/utils/isoZoneOperations'
import { isoListQuery, isoSearchQuery, isoSearchError, isoRequestParams, isoListOwner, isoFilterMismatch, isoFilterChips, isoColumn } from '@/utils/isoSearch'
import { isoListResponse, readIsoResource } from '@/utils/isoModel'
import { kubernetesIsoApis, kubernetesIsoCapability, canKubernetesIsoAction, kubernetesIsoContext } from '@/utils/kubernetesIsoCapabilities'
import { kubernetesIsoListResponse, kubernetesIsoReadParams, kubernetesIsoFailure } from '@/utils/kubernetesIsoModel'
import { kubernetesIsoListQuery, kubernetesIsoListLocation, kubernetesIsoListOwner, kubernetesIsoSearchError, kubernetesIsoSearchQuery, kubernetesIsoSortFields, kubernetesIsoFilterMismatch } from '@/utils/kubernetesIsoSearch'
import { templateListQuery, templateSearchQuery, templateSearchError, templateRequestParams, readTemplateResource, templateOwner, templateListLocation, templateFilterMismatch } from '@/utils/templateSearch'
import { guestVlanRoute, guestVlanReadParams, guestVlanSearchQuery } from '@/utils/guestVlan'
import { guestNetworkRoute, networkSearchQuery, readNetworkPage, networkListLocation } from '@/utils/guestNetworkSearch'
import { networkTabRoute, networkTabRouteKey } from '@/utils/networkTabs'
import S2sGatewayFilterChips from '@/components/view/S2sGatewayFilterChips'
import BucketStoreFilter from '@/components/view/BucketStoreFilter'
import '@/style/components/view/autoscale.less'
import '@/style/components/view/objectStorage.less'
import { autoScaleInfrastructureRows, autoScaleVisibleColumns } from '@/utils/autoScaleUi'
import { createListRefresh, listRowKey, canRefreshList } from '@/utils/listRefresh'
import { ref, reactive, toRaw, h, markRaw, defineAsyncComponent } from 'vue'
import SharedFSFormDialog from '@/components/storage/SharedFSFormDialog'
import { Button } from 'ant-design-vue'
import { vmReadApi, vmMetricsAvailable } from '@/utils/vmRead'
import { getAPI, postAPI, callAPI } from '@/api'
import { freshSnapshotContext } from '@/utils/vmSnapshotList'
import { displayEditable, readPublicIpDetail } from '@/utils/publicIpDisplay'
import { publicIpReadAuth, validatePublicIpDetail, publicIpFieldLabel } from '@/utils/publicIpDetails'
import { scopedSnapshotOperations, snapshotOperationAffectsRoute } from '@/utils/storageSnapshotNotices'
import { snapshotSecurityScope as storageSnapshotSecurityScope } from '@/utils/storageSnapshotContext'
import { snapshotDetailRoute, snapshotDetailParams, snapshotDetailPermissions, readSnapshotDetail } from '@/utils/storageSnapshotDetail'
import { snapshotListPage, snapshotSourceVolumeRoute, loadSnapshotListColumns, saveSnapshotListColumns } from '@/utils/storageSnapshotPresentation'
import { storageSnapshotListQuery, storageSnapshotSortColumn, storageSnapshotSortQuery } from '@/utils/storageSnapshotList'
import { normalizeSnapshotSearch, snapshotSearchError, snapshotSearchPermissionError, snapshotSearchQuery, snapshotCopyList, snapshotListRoute } from '@/utils/storageSnapshotSearch'
import { canDeletePolicy } from '@/utils/snapshotPolicyDeletion'
import { snapshotBusy, snapshotSubmissions, trackUnknownSnapshotSubmission } from '@/utils/vmSnapshotActions'
import VmSnapshotSummary from '@/components/view/VmSnapshotSummary'
import { mixinDevice } from '@/utils/mixin.js'
import { genericCompare } from '@/utils/sort.js'
import { sourceToken } from '@/utils/request'
import store from '@/store'
import { accountTransitionAllowed } from '@/utils/accountState'
import { projectBulkScope, projectBulkTargets } from '@/utils/projectBulk'
import { canRoleAction, roleEditFields, roleUsageParams } from '@/utils/roleActions'
import { accountQuery, accountListParams, accountSortFields, applyAccountSearch } from '@/utils/accountSearch'
import { applyRoleSearch, roleListParams, roleSearchQuery, roleSortFields } from '@/utils/roleSearch'
import { applyProjectSearch, projectListParams, projectListScope, projectSearchQuery, projectSortFields, projectStates } from '@/utils/projectSearch'
import ProjectListSearch from '@/views/project/ProjectListSearch'
import eventBus from '@/config/eventBus'

import Breadcrumb from '@/components/widgets/Breadcrumb'
import { documentationUrl } from '@/utils/cniFileImport'
import ListView from '@/components/view/ListView'
import ResourceView from '@/components/view/ResourceView'
import VpcDialog from '@/components/network/VpcDialog'
import VpcActionContext from '@/components/network/VpcActionContext'
import MoldDialog from '@/components/view/MoldDialog'
import { dnsServerListQuery, dnsServerSearchQuery } from '@/utils/dnsServer'
import { zoneListQuery, zoneReadParams, zoneSearchQuery } from '@/utils/zone'
import { dnsZoneSearchQuery } from '@/utils/dnsZone'
import { canDeleteIpv4Subnet, ipv4SubnetListQuery, ipv4SubnetReadParams, ipv4SubnetRoute, ipv4SubnetSearchQuery } from '@/utils/ipv4Subnet'
import EventCsvDialog from '@/views/event/EventCsvDialog'
import EventMutationDialog from '@/views/event/EventMutationDialog'
import VpnUserDeleteDialog from '@/views/network/VpnUserDeleteDialog'
import { canDeleteVpnUsers, vpnUserDeleteTargets } from '@/utils/vpnUserDeletion'
import { vpnCustomerGatewayActionKey } from '@/utils/vpnCustomerGatewayJobs'
import { vpnUserOwner, vpnUserVisibleColumns } from '@/utils/vpnUserPresentation'
import { canSwitchVpnUserProjects, readVpnUsers, vpnUserListLocation, vpnUserListQuery, vpnUserProject, vpnUserRoute, vpnUserSearchAuth, vpnUserSearchFields, vpnUserSearchQuery } from '@/utils/vpnUserSearch'
import { canMutateEvents, isEventMutation } from '@/utils/eventActions'
import { eventArchiveScope, eventDetailKey, eventListLocation, eventProjectScope, eventViewScope, isEventDetailRoute, isEventTimelineRoute } from '@/utils/eventTimeline'
import { eventCreatedSortQuery, eventSearchQuery } from '@/utils/eventSearch'
import NetworkOperationPolicy from '@/components/view/NetworkOperationPolicy'
import { storageUiRoute, storageDialogSize, storageDetailRouteKey } from '@/utils/storageUi'
import { policyDetailRoute, policyDetailPermissions, readPolicyDetail } from '@/utils/snapshotPolicyDetail'
import { snapshotPolicyListRoute, snapshotPolicyListQuery, snapshotPolicySortColumn, snapshotPolicySortQuery } from '@/utils/snapshotPolicyList'
import { normalizePolicySearch, policyProjectScope, policySearchError, policySearchForm, policySearchQuery, policySearchParams, policyListPage } from '@/utils/snapshotPolicySearch'
import ActionButton from '@/components/view/ActionButton'
import { affinityGroupQuery } from '@/utils/affinityGroupCreation'
import SearchView from '@/components/view/SearchView'
import SearchFilter from '@/components/view/SearchFilter'
import OsLogo from '@/components/widgets/OsLogo'
import ResourceIcon from '@/components/view/ResourceIcon'
import BulkActionProgress from '@/components/view/BulkActionProgress'
import { bulkColumns, updateBulkItem } from '@/utils/bulkAction'
import { userDataDeleteTargets } from '@/utils/userDataDeletion'
import { cniDeleteTargets, cniListLocation } from '@/utils/cniDeletion'
import { cniHistoryRouteKey } from '@/utils/cniHistory'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import DetailsInput from '@/components/widgets/DetailsInput'
import AdvisoriesView from '@/components/view/AdvisoriesView'
import InstanceGroupDeleteSummary from '@/components/view/InstanceGroupDeleteSummary'
import InstanceGroupQueryStatus from '@/components/view/InstanceGroupQueryStatus'
import { vmGroupQuery, vmGroupQueryChanged, vmGroupQueryParams, vmGroupQueryScope, vmGroupQueryRows } from '@/utils/vmGroupQuery'
import { vmGroupLookupStatus } from '@/utils/vmGroupLookup'
import { sshKeyPairDeleteTargets } from '@/utils/sshKeyPairDeletion'
import { isSshKeyPairDetailRoute, sshKeyPairDetailScope, sshKeyPairDetailTarget, sshKeyPairDetailParams, sshKeyPairMatchesTarget, sshKeyPairDetailResult, sshKeyPairDetailFailure, sshKeyPairVmParams } from '@/utils/sshKeyPairIdentity'
import { normalizeVmGroupName, isVmGroupNameAction, isVmGroupAction, vmGroupErrorKey, vmGroupDeleteTargets } from '@/utils/vmGroupNames'
import { vmDisplayName, isVmIdentityRoute, vmEditScope } from '@/utils/vmIdentity'
import PublicIpReleaseSummary from '@/components/view/PublicIpReleaseSummary'
import PublicIpDetailValue from '@/components/widgets/PublicIpDetailValue'
import AsnListTools from '@/components/view/AsnListTools'
import { kmsOperationApis } from '@/utils/kmsOperation'
import { availabilityActions, actionAvailability, kmsAvailabilityLabel } from '@/utils/kmsAvailability'
import { kmsRelatedParentLocation } from '@/utils/kmsRelated'
import { kmsDisplayQuery } from '@/utils/kmsPresentation'
import { kmsDeletionConfirmed, kmsDetailFailure, kmsDetailKey, kmsDetailRoute, kmsListLocation, readKmsDetail } from '@/utils/kmsNavigation'
import AclScopeValue from '@/components/view/AclScopeValue'
import { aclDisplaySupported, aclDisplayValue, aclHasParam, aclListSearchQuery, isAclListRoute, readAclList } from '@/utils/vpcAclAccess'
import { kmsRoute, kmsListRoute, kmsListApi, kmsAuth, kmsProjectScope, kmsQuery, kmsSearchQuery, kmsParams, readKmsPage } from '@/utils/kmsSearch'
import { gatewayDisplayListLocation } from '@/utils/s2sGatewayDisplay'
import { readGatewayDetail, gatewayVisibleColumns, gatewayDeletedListLocation } from '@/utils/s2sGateway'
import { vpnConnectionDisplaySupported } from '@/utils/s2sConnection'
import { s2sGatewayListLocation, s2sGatewayProject, s2sGatewayRoute, s2sGatewayListRoute, s2sGatewayAuth, s2sGatewayQuery, s2sGatewayError, s2sGatewayParams, s2sGatewaySearchQuery, readS2sGatewayQuery, vpnConnectionApi, vpnConnectionListRoute, vpnConnectionRoute, readVpnConnectionQuery } from '@/utils/s2sGatewaySearch'
import { asnRoute, asnAuth, asnQuery, asnError, asnParams, asnSearchQuery, readAsnQuery } from '@/utils/asnSearch'
import { publicIpRoute, publicIpProject, publicIpDefaultFilter, publicIpQuery, publicIpSearchQuery, publicIpSearchError, publicIpParams, readPublicIpPage } from '@/utils/publicIpSearch'
import { vnfRoute, vnfAuth, vnfProject, vnfListQuery, vnfSearchQuery, vnfSearchError, vnfListParams, readVnfPage, vnfStates } from '@/utils/vnfSearch'

let templateFilterSequence = 0

export default {
  name: 'Resource',
  components: {
    AclScopeValue,
    AsnListTools,
    NetworkOperationPolicy,
    BucketStoreFilter,
    Breadcrumb,
    MoldDialog,
    EventCsvDialog,
    EventMutationDialog,
    VpnUserDeleteDialog,
    PublicIpReleaseSummary,
    PublicIpDetailValue,
    VpcActionContext,
    VpcDialog,
    ResourceView,
    ListView,
    ActionButton,
    VmSnapshotSummary,
    InstanceGroupDeleteSummary,
    InstanceGroupQueryStatus,
    SearchView,
    ProjectListSearch,
    SearchFilter,
    S2sGatewayFilterChips,
    BulkActionProgress,
    TooltipLabel,
    OsLogo,
    ResourceIcon,
    DetailsInput,
    AdvisoriesView,
    SharedFSFormDialog
  },
  mixins: [mixinDevice],
  provide: function () {
    return {
      parentFetchData: this.fetchData,
      resourceActionReady: () => !(this.isStorageUi || this.isSnapshotReadView || ['vm', 'vnfapp'].includes(this.$route.meta?.name)) || this.storageActionReady,
      parentExecuteAction: this.handleDataViewAction,
      parentToggleLoading: this.toggleLoading,
      parentStartLoading: this.startLoading,
      parentFinishLoading: this.finishLoading,
      parentSearch: this.onSearch,
      parentChangeFilter: this.changeFilter,
      parentChangeResource: this.changeResource,
      parentPollActionCompletion: this.pollActionCompletion
    }
  },
  data () {
    return {
      templateFilterId: 'template-filter-' + (++templateFilterSequence),
      storageActionGeneration: 0,
      apiName: '',
      kubernetesIsoReadError: '',
      loading: false,
      // IntervalLoading: false,
      actionLoading: false,
      vmActionValidationPending: false,
      vmActionError: '',
      projectActionError: '',
      roleActionError: '',
      vmActionUnknown: false,
      columnKeys: [],
      allColumns: [],
      columns: [],
      bulkColumns: [],
      selectedColumns: [],
      chosenColumns: [],
      customColumnsDropdownVisible: false,
      showGroupActionModal: false,
      selectedItems: [],
      items: [],
      modalInfo: {},
      itemCount: 0,
      page: 1,
      pageSize: this.$store.getters.defaultListViewPageSize,
      resource: {},
      selectedRowKeys: [],
      currentAction: {},
      vpcActionError: '',
      actionTrigger: null,
      actionFocusReturn: null,
      showAction: false,
      dataView: false,
      templateDetailError: '',
      isoListError: '',
      templateOperationContext: '',
      isoOperationContext: '',
      isoOperationBusy: false,
      templateFilterError: '',
      projectView: false,
      hasProjectId: false,
      selectedFilter: '',
      filters: [],
      searchFilters: [],
      searchParams: {},
      actions: [],
      confirmDirty: false,
      firstIndex: 0,
      modalWidth: '30vw',
      actionConfirmText: '',
      promises: [],
      detailActionsVisible: false,
      autoRefreshTimer: null,
      listRequestVersion: 0,
      vpnUserProjectSwitching: false,
      listRequestPending: false,
      listRequestScope: null,
      listRequestPromise: null,
      listRefreshQueued: false,
      listLoadedScope: null,
      listSchemaScope: null,
      listSecurityScope: null,
      listCustomRender: {},
      listRefreshError: false,
      vnfSearchFailure: '',
      s2sGatewaySearchFailure: '',
      kmsSearchError: '',
      kmsDetailError: '',
      asnSearchFailure: '',
      publicIpSearchFailure: '',
      publicIpProjectSync: false,
      publicIpProjectApplying: false,
      vnfProjectSync: false,
      snapshotListError: '',
      snapshotSearchFailure: '',
      policySearchFailure: '',
      listLastUpdated: null,
      vmGroupContextScope: null,
      vmGroupQueryStatus: 'loading',
      sshKeyPairDetailStatus: 'loading',
      sshKeyPairDetailContext: null,
      projectBulkEpoch: 0,
      autoRefreshInterval: 10000
    }
  },
  beforeUnmount () {
    for (const [name, handler] of this.listEventHandlers || []) eventBus.off(name, handler)
    this.clearAutoRefresh()
    this.listRequestVersion += 1
  },
  mounted () {
    this.onListEvent('exec-action', (args) => {
      const { action, isGroupAction } = args
      this.execAction(action, isGroupAction)
    })
  },
  created () {
    this.listEventHandlers = []
    this.formRef = ref()
    this.form = reactive({})
    this.rules = reactive({})
    this.onListEvent('project-switch-start', () => {
      if (s2sGatewayRoute(this.$route) || vpnConnectionRoute(this.$route)) this.rejectS2sGatewaySearch('scope')
      if (this.isProjectList) {
        this.projectBulkEpoch++
        this.resetSelection()
        if (this.$refs.listview) this.$refs.listview.resetSelection()
      }
      if (this.isVpnUser) {
        this.vpnUserProjectSwitching = true
        this.invalidateVpnUserRead()
      }
    })
    this.onListEvent('project-switch-end', () => {
      if (!this.vpnUserProjectSwitching) return
      this.vpnUserProjectSwitching = false
      if (this.isVpnUser) this.fetchData()
    })
    this.onListEvent('vm-refresh-data', () => {
      if (this.$route.path === '/vm' || this.$route.path.includes('/vm/')) {
        this.fetchData()
      }
      if (this.$route.path === '/backup') {
        this.$router.push('/vm')
        this.fetchData()
      }
    })
    this.onListEvent('backup-restore-updated', () => {
      if (this.$route.path === '/vm' || this.$route.path.startsWith('/vm/') ||
          this.$route.path === '/backup' || this.$route.path.startsWith('/backup/')) {
        this.fetchData()
      }
    })
    this.onListEvent('desktop-refresh-data', () => {
      if (this.$route.path === '/desktopcluster' || this.$route.path.includes('/desktopcluster/')) {
        this.fetchData()
      }
    })
    this.onListEvent('resource-request-refresh-data', () => {
      if (this.$route.path === '/desktopcluster' || this.$route.path.includes('/desktopcluster/')) {
        this.fetchData()
      }
    })
    // this.onListEvent('automation-refresh-data', () => {
    //   if (this.$route.path === '/automationtemplate' || this.$route.path.includes('/automationtemplate/')) {
    //     this.fetchData()
    //   }
    // })
    this.onListEvent('automation-controller-refresh-data', () => {
      if (this.$route.path === '/automationcontroller' || this.$route.path.includes('/automationcontroller/')) {
        this.fetchData()
      }
    })
    this.onListEvent('dr-refresh-data', () => {
      if (this.$route.path === '/disasterrecoverycluster' || this.$route.path.includes('/disasterrecoverycluster/')) {
        this.fetchData()
      }
    })
    this.onListEvent('refresh-icon', () => {
      if (this.$showIcon()) {
        this.fetchData()
      }
    })
    this.onListEvent('async-job-complete', (action) => {
      if (this.$route.path.includes('/vm/')) {
        if (action && 'api' in action && ['destroyVirtualMachine'].includes(action.api)) {
          return
        }
      }

      if ((this.$route.path.includes('/publicip/') && ['firewall', 'portforwarding', 'loadbalancing'].includes(this.$route.query.tab)) ||
        (this.$route.path.includes('/guestnetwork/') && (this.$route.query.tab === 'egress.rules' || this.$route.query.tab === 'public.ip.addresses'))) {
        return
      }

      if (this.$route.path.includes('/template/') || this.$route.path.includes('/iso/')) {
        return
      }
      this.fetchData()
    })
    this.onListEvent('update-bulk-job-status', (args) => {
      var { items, action } = args
      for (const item of items) {
        this.$store.getters.headerNotices.map(function (j) {
          if (j.jobid === item.jobid) {
            j.bulkAction = action
          }
        })
      }
    })

    this.onListEvent('update-resource-state', (args) => {
      var {
        selectedItems,
        resource,
        state,
        jobid
      } = args
      if (selectedItems.length === 0 || (this.showGroupActionModal && selectedItems !== this.selectedItems)) {
        return
      }
      var tempResource = []
      this.selectedItems = selectedItems
      if (selectedItems && resource) {
        if (resource.includes(',')) {
          resource = resource.split(',')
          tempResource = resource
        } else {
          tempResource.push(resource)
        }
        for (var r = 0; r < tempResource.length; r++) {
          var objIndex = 0
          if (this.$route.path.includes('/template') || this.$route.path.includes('/iso')) {
            objIndex = selectedItems.findIndex(obj => (obj.zoneid === tempResource[r]))
          } else if (this.$route.path.includes('/router')) {
            objIndex = selectedItems.findIndex(obj => (obj.guestnetworkid === tempResource[r]))
          } else {
            objIndex = selectedItems.findIndex(obj => (obj.id === tempResource[r] || obj.username === tempResource[r] || obj.name === tempResource[r]))
          }
          if (objIndex !== -1) {
            updateBulkItem(this.selectedItems[objIndex], state, jobid)
          }
        }
      }
    })

    this.currentPath = this.$route.fullPath
    this.fetchData()
    if (!asnRoute(this.$route) && !s2sGatewayRoute(this.$route) && !vpnConnectionListRoute(this.$route) && !kmsRoute(this.$route) && 'projectid' in this.$route.query && !this.isKmsRelatedVolume(this.$route) && this.$route.meta?.name !== 'affinitygroup' && !this.isVpnUser && !isSshKeyPairDetailRoute(this.$route)) {
      this.switchProject(this.$route.query.projectid)
    }
    this.setModalWidthByScreen()
    this.scheduleAutoRefresh()
  },
  beforeRouteUpdate (to, from, next) {
    if (this.$refs.selfManagedAction?.blocksNavigation || this.isoOperationBusy) { next(false); return }
    const leavesGuestNetworkScope = guestNetworkRoute(from) &&
      (to.path !== from.path || to.query?.projectid !== from.query?.projectid)
    if (this.showAction && (kmsRoute(from) && to.fullPath !== from.fullPath || vnfRoute(from) && to.fullPath !== from.fullPath || leavesGuestNetworkScope ||
      (to.path !== from.path && (storageUiRoute(from) || (from.meta?.name || from.name) === 'snapshot')))) {
      this.closeAction()
    }
    this.currentPath = this.$route.fullPath
    next()
  },
  beforeRouteLeave (to, from, next) {
    if (this.$refs.selfManagedAction?.blocksNavigation || this.isoOperationBusy) { next(false); return }
    if ((kmsRoute(from) || guestNetworkRoute(from) || storageUiRoute(from) || (from.meta?.name || from.name) === 'snapshot') && this.showAction) this.closeAction()
    console.log('DEBUG - Due to route change, ignoring results for any on-going API request', this.apiName)
    sourceToken.cancel()
    sourceToken.init()
    this.currentPath = this.$route.fullPath
    this.resetSelection()
    this.clearAutoRefresh()
    this.listRequestVersion += 1
    this.listRequestPending = false
    next()
  },
  watch: {
    vpnCustomerGatewayActionScope: {
      flush: 'sync',
      handler (scope, previous) {
        if (previous && scope !== previous && this.showAction && this.currentAction.vpnCustomerGatewayScope === previous) this.closeAction()
      }
    },
    projectSearchScope (value, previous) {
      if (!this.isProjectList || value === previous) return
      this.resetSelection()
      if (this.$refs.listview) this.$refs.listview.resetSelection()
    },
    kmsSearchAuth: {
      flush: 'sync',
      handler (value, previous) {
        if (!kmsRoute(this.$route)) return
        if (this.showAction && previous && value !== previous) this.closeAction()
        this.invalidateKmsResults()
        if (this.isKmsDetail && previous && value !== previous) {
          const { projectid, ...query } = this.$route.query
          if (projectid || this.$router.options.history.state?.kmsReturn) {
            const path = this.$route.path
            return this.$router.replace({ path, query, force: true, state: { kmsReturn: null } }).then(() => {
              if (this.$route.path === path && this.kmsSearchAuth === value && !this.listRequestPending) this.fetchData()
            })
          }
        }
        if (kmsListRoute(this.$route) && previous && value !== previous) {
          const query = kmsQuery({}, kmsListApi(this.$route), this.$store.getters, this.pageSize)
          if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) {
            this.$router.replace({ query })
            return
          }
        }
        this.fetchData()
      }
    },
    s2sGatewaySearchAuth: {
      flush: 'sync',
      handler (value, old) {
        if (!s2sGatewayRoute(this.$route) && !vpnConnectionRoute(this.$route)) return
        this.rejectS2sGatewaySearch('')
        if (this.isS2sSearchList) {
          let switched = false
          try { const before = JSON.parse(old || 'null'); const after = JSON.parse(value); switched = !!before?.[0] && JSON.stringify(before.slice(0, 6)) !== JSON.stringify(after.slice(0, 6)) } catch (_) {}
          if (switched) { this.$router.replace({ query: s2sGatewayQuery({}, this.getValidPageSize(this.pageSize), this.$store.getters, this.s2sSearchApi) }); return }
        }
        this.fetchData()
      }
    },
    asnSearchAuth: { flush: 'sync', handler () { if (asnRoute(this.$route)) { this.rejectAsnSearch(''); this.fetchData() } } },
    publicIpAuth: {
      flush: 'sync',
      handler () {
        if (!publicIpRoute(this.$route)) return
        this.resetSelection(); if (this.$refs.listview) { this.$refs.listview.resetSelection(); if (typeof this.$refs.listview.closeContextQuickView === 'function') this.$refs.listview.closeContextQuickView() }
        if (!this.publicIpDetail) { this.searchParams = {}; this.publicIpSearchFailure = ''; this.listSchemaScope = null; if (!this.publicIpProjectApplying) this.$router.replace({ path: '/publicip', query: publicIpQuery({}, this.pageSize, this.$store.getters) }) }
        this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false; this.loading = false
        this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.listRefreshError = false
        this.$emit('change-resource', this.resource)
        this.$emit('public-ip-read-state', { pending: false, failed: false })
        if (this.showAction) this.closeAction()
        if (!this.publicIpProjectApplying && this.$store.state?.user?.token && 'listPublicIpAddresses' in (this.$store.getters.apis || {})) this.fetchData()
      }
    },
    kubernetesIsoSecurityContext: {
      flush: 'sync',
      handler () {
        if (this.$route.meta?.name !== 'kubernetesiso') return
        this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false; this.loading = false
        this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.listSchemaScope = null; this.kubernetesIsoReadError = ''
        this.resetSelection(); this.$emit('change-resource', this.resource)
        if (this.showAction) this.closeAction()
        if (!this.$route.params?.id) {
          const query = kubernetesIsoListQuery({}, this.$store.getters.defaultListViewPageSize, this.$store.getters, this.$store.state?.user?.token)
          if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) return this.$router.replace({ query })
        } else if (this.$route.query.kisoreturn || this.$route.query.kisoowner) {
          const query = { ...this.$route.query }; delete query.kisoreturn; delete query.kisoowner
          return this.$router.replace({ query })
        }
        this.fetchData()
      }
    },
    vmDialogScope () {
      if (this.showAction && this.vmStandardDialog) this.closeAction()
    },
    vpnUserAuth: {
      flush: 'sync',
      handler (value, previous) {
        if (!this.isVpnUser) return
        this.invalidateVpnUserRead()
        if (this.vpnUserProjectSwitching) return
        if (value && previous && this.$store.state?.user?.token) {
          const query = vpnUserListQuery({ projectid: this.vpnUserScope, pagesize: this.pageSize }, this.pageSize, this.$store.getters)
          if (this.$route.params?.id || JSON.stringify(query) !== JSON.stringify(this.$route.query)) {
            this.$router.replace({ path: '/vpnuser', query })
            return
          }
        }
        this.fetchData()
      }
    },
    vpnUserDeleteScope: {
      flush: 'sync',
      handler () {
        if (this.showAction && this.currentAction.vpnUserDeleteTargets) this.closeAction()
      }
    },
    eventActionScope: {
      flush: 'sync',
      handler () {
        if (this.showAction && (this.currentAction.api === 'downloadEvents' || this.currentAction.eventMutationTargets)) this.closeAction()
      }
    },
    vnfSearchAuth: {
      flush: 'sync',
      handler (value, previous) {
        if (!vnfRoute(this.$route)) return
        this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false; this.loading = false
        this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.listSchemaScope = null
        this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection()
        if (this.showAction) this.closeAction()
        if (this.vnfProjectSync) return
        const projectid = vnfProject(this.$store.getters)
        const old = previous ? JSON.parse(previous) : []
        const next = JSON.parse(value)
        const identityChanged = old.length && old.slice(0, 5).some((v, i) => v !== next[i])
        if (identityChanged || this.$route.query.projectid && this.$route.query.projectid !== projectid) return this.$router.replace({ path: '/vnfapp', query: vnfListQuery({ projectid }, this.pageSize, this.$store.getters) })
        this.fetchData()
      }
    },
    isoSecurityContext () {
      if (this.$route.meta?.name !== 'iso') return
      this.isoOperationContext = ''; this.isoOperationBusy = false
      this.listRequestVersion++; this.listRequestPending = false; this.loading = false; this.listLoadedScope = null; this.listSchemaScope = null
      this.items = []; this.resource = {}; this.itemCount = 0; this.resetSelection()
      if (this.showAction) this.closeAction()
      if (!this.$route.params?.id) {
        const query = isoListQuery({}, this.$store.getters.defaultListViewPageSize, this.$store.getters)
        if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) return this.$router.replace({ query })
      } else if (this.$route.query.isoreturn || this.$route.query.isoowner) {
        const query = { ...this.$route.query }; delete query.isoreturn; delete query.isoowner
        return this.$router.replace({ query })
      }
      if ('listIsos' in this.$store.getters.apis) this.fetchData()
    },
    templateSecurityContext () {
      if (this.$route.meta?.name !== 'template') return
      this.listRequestVersion++; this.listRequestPending = false; this.loading = false; this.listLoadedScope = null; this.items = []; this.resource = {}; this.itemCount = 0
      this.resetSelection()
      if (this.showAction) this.closeAction()
      if (!this.$route.params?.id) {
        const query = templateListQuery({}, this.pageSize)
        if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) return this.$router.replace({ query })
      }
      if ('listTemplates' in this.$store.getters.apis) this.fetchData()
    },
    snapshotOperationCompletions (next, before = []) {
      const previous = new Map(before.map(entry => [entry.id, entry.status]))
      if (next.some(entry => previous.has(entry.id) && previous.get(entry.id) !== entry.status && ['complete', 'failed'].includes(entry.status) && snapshotOperationAffectsRoute(entry, this.$route))) this.fetchData({ irefresh: true })
    },
    guestNetworkAuth () {
      if (!guestNetworkRoute(this.$route)) return
      if (this.showAction) this.closeAction()
      this.listRequestVersion++
      this.items = []
      this.resource = {}
      this.itemCount = 0
      this.listLoadedScope = null
      if (this.$store.state?.user?.token && 'listNetworks' in (this.$store.getters.apis || {})) {
        this.fetchData()
      } else {
        this.listRequestPending = false
        this.listRefreshError = false
      }
    },
    snapshotSecurityScope () {
      if (this.isObjectStorageUi) { this.resetSelection(); this.fetchData() }
      if (this.$route.meta?.name === 'vmgroup' || this.isSshKeyPairDetail || this.$route.name === 'snapshot' || snapshotDetailRoute(this.$route) || policyDetailRoute(this.$route) || snapshotPolicyListRoute(this.$route)) this.fetchData()
      if (['createVMSnapshot', 'revertToVMSnapshot', 'deleteVMSnapshot', 'createSnapshotFromVMSnapshot'].includes(this.currentAction.api)) this.closeAction()
      if (this.showAction && (this.$route.meta?.name || this.$route.name) === 'snapshot') this.closeAction()
    },
    '$route' (to, from) {
      if (to.fullPath !== from.fullPath && !to.path.startsWith('/action/') && (to?.query?.tab !== 'browser' || (to.meta?.name === 'buckets' && to.path !== from.path))) {
        if (isEventDetailRoute(to) && isEventDetailRoute(from) && eventDetailKey(to) === eventDetailKey(from)) return
        if (vpnConnectionRoute(to) && to.params?.id && storageDetailRouteKey(to) === storageDetailRouteKey(from)) return
        if (kmsDetailRoute(to) && kmsDetailRoute(from) && this.listLoadedScope &&
          kmsDetailKey(to, this.$store.getters) === kmsDetailKey(from, this.$store.getters)) return
        if (to.meta?.name === 'physicalnetwork' && to.params?.id && to.path === from.path) return
        if (networkTabRoute(to) && networkTabRoute(from) && to.params?.id && networkTabRouteKey(to) === networkTabRouteKey(from)) return
        if (storageUiRoute(to) && to.params?.id && to.path === from.path &&
          storageDetailRouteKey(to) === storageDetailRouteKey(from)) return
        if (to.meta?.name === 'affinitygroup' && from.meta?.name === 'affinitygroup' && to.params?.id && to.path === from.path &&
          JSON.stringify({ ...to.query, tab: undefined }) === JSON.stringify({ ...from.query, tab: undefined })) return
        if (to.meta?.name === 'cniconfiguration' && from.meta?.name === 'cniconfiguration' && to.params?.id &&
          cniHistoryRouteKey(to) === cniHistoryRouteKey(from)) return
        if (to.meta?.name === 'vmgroup' && from.meta?.name === 'vmgroup' &&
          vmGroupQueryScope(to, this.$store.getters, this.$store.state?.user?.token) === vmGroupQueryScope(from, this.$store.getters, this.$store.state?.user?.token)) {
          this.fetchData({ irefresh: true })
          return
        }
        if ((ipv4SubnetRoute(from) || storageUiRoute(from) || (from.meta?.name || from.name) === 'snapshot' || this.currentAction.projectBulkTargets) && this.showAction) this.closeAction()
        this.resetSelection()
        const page = Number(to.query.page)
        this.page = Number.isInteger(page) && page > 0 ? page : 1
        if ('pagesize' in to.query || ipv4SubnetRoute(to)) this.pageSize = this.getValidPageSize(to.query.pagesize)
        this.itemCount = 0
        this.clearAutoRefresh()
        this.fetchData()
        this.scheduleAutoRefresh()
        if (!s2sGatewayRoute(to) && !vpnConnectionListRoute(to) && !kmsRoute(to) && 'projectid' in to.query && !this.isKmsRelatedVolume(to) && to.meta?.name !== 'affinitygroup' && !isSshKeyPairDetailRoute(to)) {
          this.switchProject(to.query.projectid)
        }
      }
    },
    '$i18n.locale' (to, from) {
      if (to !== from) {
        this.fetchData()
      }
    },
    dataView (newVal, oldVal) {
      if (newVal) {
        this.detailActionsVisible = false
      }
      this.scheduleAutoRefresh()
    },
    '$store.getters.metrics' (oldVal, newVal) {
      this.fetchData()
    },
    '$store.getters.listAllProjects' (enabled) {
      // The synchronous VNF context watcher owns its canonical URL transition.
      if (this.isVpnUser || this.isEventList || vnfRoute(this.$route) || publicIpRoute(this.$route) || s2sGatewayRoute(this.$route) || this.isS2sSearchList) return
      if ((snapshotListRoute(this.$route) || snapshotPolicyListRoute(this.$route) || guestNetworkRoute(this.$route)) && !this.$store.getters.project?.id) {
        const projectid = enabled ? '-1' : '0'
        if (this.$route.query.projectid !== projectid) return this.$router.push({ query: { ...this.$route.query, projectid, page: '1' } })
      }
      this.fetchData()
    },
    showAction (visible) {
      if (visible) {
        this.clearAutoRefresh()
      } else {
        this.scheduleAutoRefresh()
      }
    }
  },
  computed: {
    kubernetesIsoSecurityContext () { return kubernetesIsoContext(this.$store.getters, this.$store.state?.user?.token) },
    vnfSearchAuth () { return vnfAuth(this.$store.getters, this.$store.state?.user?.token) },
    isKubernetesIsoList () { return this.$route.meta?.name === 'kubernetesiso' && !this.$route.params?.id },
    isIsoList () { return this.$route.meta?.name === 'iso' && !this.$route.params?.id },
    isoRestoredFilter () { return this.isIsoList && ['executable', 'selfexecutable', 'sharedexecutable'].includes(this.filterValue) ? this.filterValue : '' },
    isoSecurityContext () { return JSON.stringify([isoListOwner(this.$store.getters), this.$store.state?.user?.token, Object.keys(this.$store.getters.apis || {}).sort()]) },
    isTemplateList () { return this.$route.meta?.name === 'template' && !this.$route.params?.id },
    templateSecurityContext () { return JSON.stringify([templateOwner(this.$store.getters), this.$store.state?.user?.token, Object.keys(this.$store.getters.apis || {}).sort()]) },
    guestNetworkAuth () { return JSON.stringify([this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.getters.userInfo?.roleid, this.$store.getters.userInfo?.domainid, this.$store.state?.user?.token, this.$store.getters.project?.id, this.$store.getters.listAllProjects, Object.keys(this.$store.getters.apis || {}).sort()]) },
    sharedFSContentLoading () {
      if (vnfRoute(this.$route) && this.vnfProjectSync) return true
      if (!['sharedfs', 'runtimebundle'].includes(this.$route.meta?.name) || this.listLoadedScope !== this.listScope()) return this.loading
      if (this.dataView && (!this.resource.id || this.resource.id !== this.$route.params.id)) return this.loading
      return false
    },
    snapshotOperationCompletions () { return scopedSnapshotOperations(storageSnapshotSecurityScope(this.$store)).map(entry => ({ ...entry })) },
    isBucketGroupDelete () { return this.$route.path === '/buckets' && this.currentAction?.api === 'deleteBucket' && !!this.currentAction.invokedAsGroupAction },
    isVolumeList () { return !this.dataView && this.$route.meta?.name === 'volume' },
    isGuestNetworkList () { return !this.dataView && guestNetworkRoute(this.$route) },
    isGuestNetworkUi () { return ['guestnetwork', 'networkoffering'].includes(this.$route.meta?.name) },
    isProjectUi () { return this.$route.meta?.name === 'project' },
    isVmUi () { return this.$route.meta?.name === 'vm' },
    isVmList () { return !this.dataView && this.isVmUi },
    projectStateOptions () {
      return [{ value: '', label: this.$t('label.all') },
        ...projectStates.map(value => ({ value, label: this.$t('state.' + value.toLowerCase()) }))]
    },
    isRoleUi () { return this.$route.meta?.name === 'role' },
    isAccountUi () { return this.$route.meta?.name === 'account' },
    isAccountList () { return this.isAccountUi && !this.$route.params?.id },
    accountStandardDialog () {
      return this.isAccountUi && ['createAccount', 'ldapCreateAccount', 'updateAccount', 'deleteAccount', 'updateResourceCount', 'uploadSslCert'].includes(this.currentAction?.api)
    },
    isRoleList () { return this.isRoleUi && !this.dataView },
    roleStandardDialog () {
      return this.isRoleUi && ['createRole', 'importRole', 'updateRole', 'enableRole', 'disableRole', 'deleteRole'].includes(this.currentAction?.api)
    },
    isProjectList () { return !this.dataView && this.$route.meta?.name === 'project' },
    projectSearchScope () {
      return this.$route.meta?.name === 'project' ? projectListScope(this.$store.getters, this.$store.state?.user?.token) : ''
    },
    projectBulkContext () {
      if (!this.isProjectList) return ''
      return JSON.stringify([
        this.projectBulkEpoch,
        projectBulkScope(this.$route, this.$store.getters, this.$store.state?.user?.token, this.selectedRowKeys)
      ])
    },
    isAclUi () { return isAclListRoute(this.$route) },
    aclStandardDialog () {
      return this.isAclUi && ['createNetworkACLList', 'updateNetworkACLList', 'deleteNetworkACLList'].includes(this.currentAction?.api)
    },
    aclDeleteDialog () { return this.aclStandardDialog && this.currentAction.api === 'deleteNetworkACLList' },
    isBucketList () { return !this.dataView && this.$route.meta?.name === 'buckets' },
    isVpcUi () { return ['vpc', 'vpcoffering', 'privategw'].includes(this.$route.meta?.name) },
    isObjectStorageUi () { return ['buckets', 'objectstore'].includes(this.$route.meta?.name) },
    isStorageUi () { return storageUiRoute(this.$route) },
    isSnapshotList () { return snapshotListRoute(this.$route) },
    isSnapshotDetail () { return snapshotDetailRoute(this.$route) },
    isPolicyDetail () { return policyDetailRoute(this.$route) },
    isPolicyList () { return snapshotPolicyListRoute(this.$route) },
    isPolicyUi () { return this.isPolicyList || this.isPolicyDetail },
    isSnapshotReadView () { return this.isSnapshotList || this.isSnapshotDetail || this.isPolicyDetail || snapshotPolicyListRoute(this.$route) },
    isSnapshotCopyList () { return snapshotCopyList(this.$route) },
    isGuestNetworkDelete () { return this.$route.meta?.name === 'guestnetwork' && this.currentAction.api === 'deleteNetwork' },
    vmDialogScope () { return isVmIdentityRoute(this.$route) ? vmEditScope(this.resource, this.$route, this.$store) : null },
    isEventTimeline () { return isEventTimelineRoute(this.$route) },
    isEventList () { return this.$route.meta?.name === 'event' && !this.$route.params?.id },
    eventCreatedSortEnabled () {
      const params = this.$getApiParams('listEvents')
      return this.isEventList && !this.isEventTimeline && !!params.sortkey && !!params.sortorder
    },
    eventScopeDiffers () {
      return eventProjectScope(this.$route, this.$store.getters) !== eventViewScope(this.$store.getters)
    },
    eventTimelineScopeLabel () {
      const { project, listAllProjects } = this.$store.getters
      const id = this.$route.query.projectid || project?.id || (listAllProjects ? '-1' : '0')
      if (id === '0') return this.$t('label.default.view')
      if (id === '-1') return this.$t('label.projects')
      return (project?.id === id ? project.name : this.items.find(row => row.projectid === id)?.project) || this.$t('label.project')
    },
    isVpnUser () { return vpnUserRoute(this.$route) },
    vpnCustomerGatewayActionScope () {
      return this.$route.meta?.name === 'vpncustomergateway' ? vpnCustomerGatewayActionKey(this.$store.getters, this.$route, this.$store.state?.user?.token) : null
    },
    isGuestVlan () { return guestVlanRoute(this.$route) },
    isZoneUi () { return this.$route.meta?.name === 'zone' },
    isZoneList () { return this.isZoneUi && !this.$route.params?.id },
    isPhysicalNetworkUi () { return this.$route.meta?.name === 'physicalnetwork' },
    isZoneRelatedList () {
      return !this.$route.params?.id && !!this.$route.query.zoneid &&
        ['pod', 'cluster', 'host', 'storagepool', 'imagestore'].includes(this.$route.meta?.name)
    },
    infrastructureStandardDialog () { return (this.isZoneUi || this.isPhysicalNetworkUi) && !!this.currentAction.api },
    isDnsServer () { return this.$route.meta?.name === 'dnsserver' },
    isDnsZone () { return this.$route.meta?.name === 'dnszone' },
    isIpv4Subnet () { return ipv4SubnetRoute(this.$route) },
    isVpnCustomerGateway () { return this.$route.meta?.name === 'vpncustomergateway' },
    isSystemOfferingUi () { return this.$route.meta?.name === 'systemoffering' },
    standardResourceToolbar () { return this.isSystemOfferingUi || this.isZoneUi || this.isPhysicalNetworkUi || this.isZoneRelatedList || this.isRoleUi || this.isVmUi || this.isAclUi || this.isVpnUser || this.isProjectUi || this.isVpnCustomerGateway || this.isGuestVlan || this.isIpv4Subnet || this.isDnsZone || this.isDnsServer },
    vpnUserAuth () { return this.isVpnUser ? vpnUserSearchAuth(this.$store.getters, this.$store.state?.user?.token) : null },
    vpnUserScope () { return this.isVpnUser ? vpnUserProject(this.$route.query, this.$store.getters) : null },
    vpnUserDeleteScope () {
      return this.isVpnUser ? JSON.stringify([this.$route.fullPath, this.vpnUserScope, this.vpnUserAuth]) : null
    },
    eventActionScope () {
      if (this.$route.meta?.name !== 'event') return null
      const { apis, project, listAllProjects, features } = this.$store.getters
      return JSON.stringify([this.$route.fullPath, project?.id, listAllProjects, this.$store.state?.user?.token,
        apis.downloadEvents?.response?.some(field => field.name === 'csv'), !!apis.archiveEvents, !!apis.deleteEvents, features?.eventdeleteenabled])
    },
    projectStandardDialog () {
      return this.isProjectUi && ['updateProject', 'activateProject', 'suspendProject', 'deleteProject'].includes(this.currentAction?.api)
    },
    vmStandardDialog () { return isVmIdentityRoute(this.$route) && ['updateVirtualMachine', 'startVirtualMachine', 'stopVirtualMachine', 'rebootVirtualMachine', 'restoreVirtualMachine', 'recoverVirtualMachine'].includes(this.currentAction?.api) },
    isKmsDetail () { return kmsDetailRoute(this.$route) },
    kmsDetailReady () {
      return !this.listRequestPending && !this.kmsDetailError && this.listLoadedScope === this.listScope() &&
        this.resource.id === this.$route.params?.id?.toLowerCase()
    },
    kmsSearchAuth () { return kmsRoute(this.$route) ? kmsAuth(this.$store.getters, this.$store.state?.user?.token) : '' },
    isVpnConnectionDetail () { return vpnConnectionRoute(this.$route) && !!this.$route.params?.id },
    isS2sSearchList () { return s2sGatewayListRoute(this.$route) || vpnConnectionListRoute(this.$route) },
    s2sSearchApi () { return vpnConnectionListRoute(this.$route) ? vpnConnectionApi : 'listVpnGateways' },
    s2sGatewaySearchAuth () { return s2sGatewayRoute(this.$route) || vpnConnectionRoute(this.$route) ? s2sGatewayAuth(this.$store.getters, this.$store.state?.user?.token) : '' },
    asnSearchAuth () { return asnRoute(this.$route) ? asnAuth(this.$store) : '' },
    asnListReady () { return !!this.asnSearchAuth && !this.listRefreshError && !this.asnSearchFailure && this.listLoadedScope === this.listScope() },
    isPublicIpUi () { return publicIpRoute(this.$route) },
    publicIpDetail () { return this.$route.meta?.name === 'publicip' && !!this.$route.params?.id },
    publicIpAuth () { return publicIpRoute(this.$route) ? publicIpReadAuth(this.$store) : '' },
    presentedColumns () {
      const columns = this.localizePublicIpColumns(this.columns)
      if (this.isAccountList) {
        const { sortkey, sortorder } = accountQuery(this.$route.query)
        return columns.map(column => accountSortFields.includes(column.key) ? {
          ...column,
          sorter: (left, right) => String(left[column.key] || '').localeCompare(String(right[column.key] || '')),
          ...(column.key === 'state' ? { stateLabel: record => this.$t('label.' + record.state) } : {}),
          sortOrder: sortkey === column.key ? (sortorder === 'asc' ? 'ascend' : 'descend') : null
        } : column)
      }
      if (this.isRoleList) {
        const { sortkey, sortorder } = roleSearchQuery(this.$route.query)
        const widths = { name: 224, type: 118, description: 180, state: 104, filtercolumn: 44 }
        return columns.map(column => ({
          ...column,
          width: widths[column.key],
          ...(column.key === 'state' ? { stateLabel: record => this.$t('label.' + record.state) } : {}),
          ...(roleSortFields.includes(column.key) ? {
            sortOrder: sortkey === column.key ? (sortorder === 'asc' ? 'ascend' : 'descend') : null
          } : {})
        }))
      }
      if (this.isProjectList) {
        const { sortkey, sortorder } = projectSearchQuery(this.$route.query, this.$store.getters)
        const sortValue = (record, key) => key === 'account'
          ? (record.owner || []).map(owner => owner.account).join(', ') || record.account || ''
          : record[key] || ''
        const widths = { name: 216, state: 104, displaytext: 192, account: 140, domain: 128, filtercolumn: 44 }
        return columns.map(column => ({
          ...column,
          width: widths[column.key] || 144,
          className: 'project-list-column-' + column.key,
          ...(projectSortFields.includes(column.key) ? {
            sorter: (a, b) => genericCompare(sortValue(a, column.key), sortValue(b, column.key)),
            sortOrder: sortkey === column.key ? (sortorder === 'asc' ? 'ascend' : 'descend') : null
          } : {})
        }))
      }
      if (this.isEventList) {
        const { sortkey, sortorder } = this.$route.query
        const widths = { type: 166, level: 70, state: 100, description: 250, resource: 154, username: 114, account: 110, project: 130, domain: 108, created: 180, filtercolumn: 42 }
        return columns.map(column => ({
          ...column,
          width: widths[column.key] || column.width,
          ...(column.key === 'description' ? { ellipsis: true } : {}),
          ...(this.isEventTimeline ? { sorter: false } : column.key === 'created' ? {
            sorter: this.eventCreatedSortEnabled,
            sortOrder: this.eventCreatedSortEnabled && sortkey === 'created' ? (sortorder === 'asc' ? 'ascend' : 'descend') : null
          } : {})
        }))
      }
      if (s2sGatewayRoute(this.$route)) return columns.filter(column => column.key !== 'project' || s2sGatewayProject(this.$store.getters) !== '0')
      if (vpnConnectionListRoute(this.$route)) {
        const widths = { publicip: 154, state: 118, gateway: 180, ipsecpsk: 120, ikepolicy: 162, esppolicy: 162, filtercolumn: 42 }
        const labels = { publicip: 'label.s2s.connection.local', gateway: 'label.s2s.connection.peer' }
        return columns.map(column => ({
          ...column,
          width: widths[column.key] || 156,
          title: labels[column.key] ? this.$t(labels[column.key]) : column.title
        }))
      }
      if (this.isIpv4Subnet) {
        const widths = { subnet: 152, zonename: 110, parentsubnet: 152, networkname: 166, vpcname: 136, created: 176, allocated: 176, state: 108, filtercolumn: 42 }
        return columns.map(column => ({ ...column, sorter: false, width: widths[column.key] || 120 }))
      }
      if (this.isGuestVlan) {
        const widths = { vlan: 104, allocationstate: 116, physicalnetworkname: 164, taken: 190, account: 186, project: 174, domain: 136, zonename: 110, 'guest.networks': 240, filtercolumn: 42 }
        return columns.map(column => ({
          ...column,
          width: widths[column.key] || 144,
          title: column.key === 'taken' ? this.$t('label.guest.vlan.taken') : column.title
        }))
      }
      if (this.isVpnCustomerGateway) {
        const widths = { name: 172, gateway: 152, project: 132, account: 104, domain: 102, cidrlist: 174, filtercolumn: 36 }
        return columns.map(column => ({
          ...column,
          width: widths[column.key],
          title: column.key === 'gateway' ? this.$t('label.vpn.customer.gateway.peer') : column.title
        }))
      }
      if (this.isVpnUser) {
        const widths = { username: 136, state: 100, owners: 156, filtercolumn: 36 }
        return columns.map(column => ({
          ...column,
          width: widths[column.key],
          ...(column.key === 'owners' ? { sorter: (a, b) => genericCompare(vpnUserOwner(a).name || '', vpnUserOwner(b).name || '') } : {})
        }))
      }
      if (!asnRoute(this.$route)) return columns
      return columns.map(column => column.key === 'filtercolumn' ? column : ({ ...column, title: this.$t(column.key === 'allocated' ? 'label.asn.allocatedat' : 'label.' + column.key.toLowerCase()), width: ['asnumber', 'allocationstate'].includes(column.key) ? 140 : column.key === 'releasereason' ? 230 : 160 }))
    },
    projectTableWidth () { return 40 + this.presentedColumns.reduce((width, column) => width + column.width, 0) },
    vpnConnectionTableWidth () { return this.presentedColumns.reduce((width, column) => width + column.width, 0) },
    presentedChosenColumns () { return this.localizePublicIpColumns(this.chosenColumns) },
    publicIpReleaseDialog () { return this.$route.meta?.name === 'publicip' && this.currentAction?.api === 'releaseIpAddress' },
    publicIpReservationDialog () { return this.$route.meta?.name === 'publicip' && this.currentAction?.api === 'reserveIpAddress' },
    publicIpStandardDialog () { return this.$route.meta?.name === 'publicip' && ['reserveIpAddress', 'updateIpAddress'].includes(this.currentAction?.api) },
    standardActionDialog () { return this.roleStandardDialog || this.accountStandardDialog || this.vmStandardDialog || this.isVpcUi || this.isStorageUi || this.isObjectStorageUi || !!this.currentAction.standardDialogSize },
    storageActionReady () { return !this.listRequestPending && !this.listRefreshError && this.listLoadedScope === this.listScope() },
    policySelectionAvailable () {
      return this.isPolicyList && !this.policySearchFailure && this.actions.some(action => action.groupAction && action.api in this.$store.getters.apis)
    },
    isSshKeyPairDetail () { return isSshKeyPairDetailRoute(this.$route) },
    sshKeyPairDetailVisible () {
      return ['ready', 'refreshing'].includes(this.sshKeyPairDetailStatus) &&
        this.sshKeyPairDetailContext === this.listScope() &&
        sshKeyPairMatchesTarget(this.resource, sshKeyPairDetailTarget(this.$route, this.$store.getters.project, this.$store.getters.listAllProjects))
    },
    sshKeyPairDetailActionReady () { return this.sshKeyPairDetailVisible && this.sshKeyPairDetailStatus === 'ready' && !this.listRequestPending },
    cniDetailActionReady () { return !this.listRequestPending && !this.listRefreshError && this.listLoadedScope === this.listScope() && !!this.resource.id && String(this.resource.id).toLowerCase() === String(this.$route.params.id || '').toLowerCase() },
    vmGroupQueryReady () { return ['ready', 'empty'].includes(this.vmGroupQueryStatus) && this.listLoadedScope === this.listScope() && !this.listRequestPending },
    snapshotSecurityScope () { return JSON.stringify([this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.$store.state?.user?.token, this.$store.getters.userInfo?.roletype, ...(snapshotDetailRoute(this.$route) ? [this.$store.getters.userInfo?.roleid, snapshotDetailPermissions(this.$store.getters)] : []), ...(policyDetailRoute(this.$route) ? [this.$store.getters.userInfo?.roleid, policyDetailPermissions(this.$store.getters)] : [])]) },
    activeFiltersList () {
      if (asnRoute(this.$route) || this.isS2sSearchList) return []
      if (this.isIsoList) return isoFilterChips(this.$route.query, this.$store.getters)
      const queryParams = this.isProjectList ? projectSearchQuery(this.$route.query, this.$store.getters) : { ...this.$route.query }
      const activeFilters = []
      for (const filter in queryParams) {
        if (guestVlanRoute(this.$route) && ['filter', 'allocatedonly'].includes(filter)) continue
        if (isAclListRoute(this.$route) && !['name', 'vpcid'].includes(filter)) continue
        if (this.isEventList && ['timeline', 'startid', 'archived', 'filter', 'projectid'].includes(filter)) continue
        if (kmsListRoute(this.$route) && filter === 'projectid' && kmsProjectScope(this.$store.getters)) continue
        if (['sortkey', 'sortorder', 'kisoowner', 'kisoreturn'].includes(filter) || publicIpRoute(this.$route) && filter === 'filter') continue
        if (this.$route.name === 'vmsnapshot' && filter === 'filter') {
          continue
        }
        if (this.$route.name === 'host' && filter === 'type') {
          continue
        }
        if (!filter.startsWith('tags[')) {
          activeFilters.push({
            key: filter,
            value: queryParams[filter],
            isTag: false
          })
        } else if (filter.endsWith('].key')) {
          const tagIdx = filter.split('[')[1].split(']')[0]
          const tagKey = queryParams[`tags[${tagIdx}].key`]
          const tagValue = queryParams[`tags[${tagIdx}].value`]
          activeFilters.push({
            key: tagKey,
            value: tagValue,
            isTag: true,
            tagIdx: tagIdx
          })
        }
      }
      return activeFilters
    },
    showSearchFilters () {
      if (ipv4SubnetRoute(this.$route)) return !this.dataView && this.activeFiltersList.some(filter => ['zoneid', 'parentid', 'subnet', 'networkid', 'vpcid'].includes(filter.key))
      if (isAclListRoute(this.$route)) return !this.dataView && this.activeFiltersList.length > 0
      const excludedKeys = ['page', 'pagesize', 'q', 'keyword', 'tags', 'projectid']
      return !this.dataView && this.$config.showSearchFilters && this.activeFiltersList.some(f => !excludedKeys.includes(f.key))
    },
    hasSelected () {
      return this.selectedRowKeys.length > 0
    },
    pageSizeOptions () {
      var sizes = [20, 50, 100, 200, this.$store.getters.defaultListViewPageSize]
      if (this.device !== 'desktop') {
        sizes.unshift(10)
      }
      return [...new Set(sizes)].sort(function (a, b) {
        return a - b
      }).map(String)
    },
    s2sDisplayFilterId () {
      if (s2sGatewayListRoute(this.$route)) return 's2s-gateway-display-filter'
      return vpnConnectionListRoute(this.$route) && vpnConnectionDisplaySupported(this.$store.getters) ? 's2s-connection-display-filter' : undefined
    },
    aclDisplayFilterId () {
      return isAclListRoute(this.$route) && aclDisplaySupported(this.$store.getters) ? 'acl-display-filter' : undefined
    },
    filterValue () {
      if (guestVlanRoute(this.$route)) return this.$route.query.allocatedonly === 'true' ? 'allocatedonly' : 'all'
      if (this.aclDisplayFilterId) return aclDisplayValue(this.$route.query.fordisplay) || 'true'
      if (this.isEventList) return eventArchiveScope(this.$route.query, this.isEventTimeline)
      if (this.s2sDisplayFilterId) return this.$route.query.fordisplay || 'all'
      if (asnRoute(this.$route)) return this.$route.query.filter || 'all'
      if (publicIpRoute(this.$route)) return this.$route.query.filter || String(this.$route.query.state || publicIpDefaultFilter(this.$store.getters)).toLowerCase()
      if (vnfRoute(this.$route)) return String(this.$route.query.state || 'all').toLowerCase()
      if (this.$route.meta?.defaultFilter) {
        const value = String(this.$route.query.filter || this.$route.query.state || this.$route.meta.defaultFilter).toLowerCase()
        return this.filters.includes(value) ? value : this.$route.meta.defaultFilter
      }
      if (this.isAccountList) return this.$route.query.state || 'all'
      if (this.$route.query.filter) {
        return this.$route.query.filter
      }
      const routeName = this.$route.name
      if ((this.projectView && routeName === 'vm') || (['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo.roletype) && ['vm', 'iso', 'template', 'pod', 'cluster', 'host', 'systemvm', 'router', 'storagepool'].includes(routeName)) || ['account', 'guestnetwork', 'guestvlans', 'oauthsetting', 'guestos', 'guestoshypervisormapping', 'kubernetes', 'asnumbers', 'networkoffering', 'extension'].includes(routeName)) {
        return 'all'
      }
      if (['publicip'].includes(routeName)) {
        return publicIpDefaultFilter(this.$store.getters)
      }
      if (['volume'].includes(routeName)) {
        return 'user'
      }
      if (['event', 'computeoffering', 'systemoffering', 'diskoffering', 'quotatariff'].includes(routeName)) {
        return 'active'
      }
      if (['alertRules'].includes(routeName)) {
        return 'all'
      }
      return 'self'
    },
    visibleDataViewActions () {
      if ((this.isVpnUser || this.isVpnCustomerGateway) && !this.storageActionReady) return []
      if (this.isAclUi && (this.listRefreshError || this.resource?.id !== this.$route.params?.id)) return []
      if (this.isKmsDetail && !this.kmsDetailReady) return []
      const policyBackgroundRead = this.isPolicyDetail && this.listRequestPending && !this.listRefreshError &&
        !!this.resource?.id && this.listLoadedScope === this.listScope()
      if ((this.isSnapshotDetail || this.isPolicyDetail) && !this.storageActionReady && !policyBackgroundRead) return []
      if ((this.isStorageUi || this.isObjectStorageUi) && !this.resource?.id) return []
      if (this.isObjectStorageUi && !this.storageActionReady) return []
      if (this.isSshKeyPairDetail && !this.sshKeyPairDetailActionReady) return []
      if (this.$route.meta?.name === 'vmgroup' && !this.vmGroupQueryReady) return []
      if (this.isVpnConnectionDetail && (!this.resource.id || !this.storageActionReady)) return []
      if (!this.actions || !this.dataView) {
        return []
      }
      return this.actions.filter(action => {
        if (!(action.api in this.$store.getters.apis)) {
          return false
        }
        if (!action.dataView) {
          return false
        }
        if (isEventMutation(action.api) && !this.storageActionReady) return false
        return 'show' in action ? action.show(this.resource, this.$store.getters) : true
      }).map(action => policyBackgroundRead ? { ...action, disabled: () => true } : action)
    },
    visibleListActions () {
      if (this.isVpnUser && this.vpnUserProjectSwitching) return []
      if (vnfRoute(this.$route) && !this.storageActionReady) return []
      if (this.isObjectStorageUi && !this.storageActionReady) return []
      if (this.isSnapshotCopyList || this.snapshotSearchFailure || this.policySearchFailure || (this.isSnapshotList && !this.storageActionReady) || (this.isPolicyList && this.listLoadedScope !== this.listScope())) return []
      if (!this.actions || this.actions.length === 0 || this.dataView) {
        return []
      }
      const selectionCount = this.selectedRowKeys.length
      return this.actions.filter(action => {
        if ((isEventMutation(action.api) || (this.isProjectList && action.projectBulkAction) || (this.isVpnCustomerGateway && action.groupAction)) && !this.storageActionReady) return false
        if (!(action.api in this.$store.getters.apis)) {
          return false
        }
        if (selectionCount > 0) {
          // Explicitly named bulk actions stay in the existing toolbar position.
          const showExplicitGroup = action.groupAction && action.toolbarLabel &&
            ('groupShow' in action ? action.groupShow(this.selectedItems, this.$store.getters) : true)
          return showExplicitGroup || (action.listView && !action.groupAction && ('show' in action ? action.show(this.resource, this.$store.getters) : true))
        }
        const showOnList = action.listView && ('show' in action ? action.show(this.resource, this.$store.getters) : true)
        const showOnGroup = action.groupAction && selectionCount > 0 &&
          ('groupShow' in action ? action.groupShow(this.selectedItems, this.$store.getters) : true)
        return showOnList || showOnGroup
      }).map(action => (this.isPolicyList && !this.canOpenPolicyCreate(action, false) || this.isVpnUser && action.api === 'removeVpnUser') && !this.storageActionReady
        ? { ...action, disabled: () => true }
        : action)
    },
    requiresNameConfirmation () {
      return !!this.currentAction?.requireNameConfirmation &&
        !(this.currentAction.invokedAsGroupAction && this.selectedRowKeys.length > 0)
    },
    isSubmitDisabled () {
      if (this.isSshKeyPairDetail && !this.sshKeyPairDetailActionReady) return true
      return this.requiresNameConfirmation &&
        (!this.resource?.name || this.actionConfirmText.trim() !== this.resource.name.trim())
    }
  },
  methods: {
    eventProjectScope,
    s2sGatewayRoute,
    s2sGatewayListRoute,
    vpnConnectionRoute,
    vpnConnectionListRoute,
    kmsListRoute,
    kmsRoute,
    publicIpProject,
    publicIpDefaultFilter,
    vmDisplayName,
    isVmIdentityRoute,
    actionFieldDescription (field) {
      if (this.aclStandardDialog && this.currentAction.api === 'createNetworkACLList' && field.name === 'vpcid') {
        return this.$t('label.acl.scope.pick')
      }
      const known = ['podid', 'clusterid', 'hostid', 'considerlasthost', 'bootintosetup', 'forced']
      return this.vmStandardDialog && known.includes(field.name) ? this.$t('message.vm.start.' + field.name) : field.description
    },
    vmMetricsAvailable,
    templateListLocation,
    storageDialogSize,
    snapshotSourceVolumeRoute,
    documentationUrl,
    rejectSshKeyPairDetail (status) {
      this.sshKeyPairDetailStatus = status
      this.items = []; this.resource = {}; this.itemCount = 0
      this.listLoadedScope = null; this.listRefreshError = true
      this.detailActionsVisible = false
      if (this.showAction) this.closeAction()
      if (this.showGroupActionModal) this.handleCancel()
      this.$emit('change-resource', this.resource)
    },
    canExecuteSshKeyPairAction (action) {
      if (!isSshKeyPairDetailRoute(this.$route)) return true
      if (!this.sshKeyPairDetailActionReady) return false
      if (action?.resource && (!sshKeyPairMatchesTarget(action.resource, sshKeyPairDetailTarget(this.$route, this.$store.getters.project, this.$store.getters.listAllProjects)) || action.resource.id !== this.resource.id)) {
        this.rejectSshKeyPairDetail('mismatch')
        return false
      }
      return true
    },
    isScheduleListRoute () {
      return ['/snapshotpolicy', '/backupschedule'].some(path => this.$route.path.endsWith(path))
    },
    getScheduleSortValue (record) {
      const schedule = String(record?.schedule || '')
      const intervalType = record?.intervaltype
      if (intervalType === 0 || intervalType === 'HOURLY') {
        const minute = Number(schedule)
        return Number.isFinite(minute) ? minute : Number.MAX_SAFE_INTEGER
      }

      const [minuteValue, hourValue] = schedule.split(':')
      const minute = Number(minuteValue)
      const hour = Number(hourValue)
      if (!Number.isFinite(hour) || !Number.isFinite(minute)) {
        return Number.MAX_SAFE_INTEGER
      }
      return (hour * 60) + minute
    },
    getColumnSorter (key) {
      if (key === 'schedule' && this.isScheduleListRoute()) {
        return (a, b) => this.getScheduleSortValue(a) - this.getScheduleSortValue(b)
      }
      return (a, b) => genericCompare(a[key] || '', b[key] || '')
    },
    resetSelection () {
      this.selectedRowKeys = []
      this.selectedItems = []
    },
    getValidPageSize (value) {
      const pageSize = Number(value)
      if (Number.isInteger(pageSize) && pageSize > 0) {
        return pageSize
      }
      const defaultPageSize = Number(this.$store.getters.defaultListViewPageSize)
      return Number.isInteger(defaultPageSize) && defaultPageSize > 0 ? defaultPageSize : 20
    },
    onListEvent (name, handler) {
      this.listEventHandlers.push([name, handler])
      eventBus.on(name, handler)
    },
    shouldAutoRefresh () {
      if (vnfRoute(this.$route) && !this.$route.params?.id && this.selectedRowKeys.length) return false
      return this.autoRefreshInterval > 0 && /^(list|get|quota)/i.test(this.apiName || '')
    },
    scheduleAutoRefresh () {
      this.clearAutoRefresh()
      if (!this.shouldAutoRefresh()) {
        return
      }
      this.autoRefreshTimer = createListRefresh({
        interval: this.dataView ? 30000 : this.autoRefreshInterval,
        active: () => this.shouldAutoRefresh() && !this.showAction && canRefreshList(this.$el),
        refresh: () => this.fetchData({ irefresh: true, autoscheduled: true })
      })
    },
    clearAutoRefresh () {
      if (this.autoRefreshTimer) {
        this.autoRefreshTimer.stop()
        this.autoRefreshTimer = null
      }
    },
    listScope () {
      if (this.vpnUserDeleteScope) return this.vpnUserDeleteScope
      if (this.isKmsDetail) return JSON.stringify([kmsDetailKey(this.$route, this.$store.getters), this.kmsSearchAuth])
      if (kmsListRoute(this.$route)) return JSON.stringify([this.$route.fullPath, this.kmsSearchAuth])
      if (this.$route.meta?.name === 'kubernetesiso') return JSON.stringify([this.$route.fullPath, kubernetesIsoContext(this.$store.getters, this.$store.state?.user?.token)])
      if (vnfRoute(this.$route)) return JSON.stringify([this.$route.fullPath, vnfAuth(this.$store.getters, this.$store.state?.user?.token), this.$store.getters.metrics])
      if (isSshKeyPairDetailRoute(this.$route)) return sshKeyPairDetailScope(this.$route, this.$store.getters, this.$store.state?.user?.token)
      if (this.$route.meta?.name === 'project') return JSON.stringify([this.$route.fullPath, this.projectSearchScope])
      if (this.$route.meta?.name === 'vmgroup') return vmGroupQueryScope(this.$route, this.$store.getters, this.$store.state?.user?.token)
      // Tabs and S3 folder normalization do not change the authorized resource.
      // Keep all actual filters and the identity/session scope below.
      const objectStoreDetail = this.isObjectStorageUi && this.$route.params?.id
      let routeKey = this.$route.fullPath
      if (this.$route.meta?.name === 'physicalnetwork' && this.$route.params?.id) {
        routeKey = this.$route.path
      } else if (networkTabRoute(this.$route) && this.$route.params?.id) {
        routeKey = networkTabRouteKey(this.$route)
      } else if (objectStoreDetail) {
        routeKey = JSON.stringify([this.$route.path, Object.entries(this.$route.query || {}).filter(([key]) => !['tab', 'browserPath'].includes(key)).sort(([a], [b]) => a.localeCompare(b))])
      } else if ((this.isVpnConnectionDetail || storageUiRoute(this.$route) || snapshotDetailRoute(this.$route) || policyDetailRoute(this.$route)) && this.$route.params?.id) {
        routeKey = storageDetailRouteKey(this.$route)
      } else if (isEventDetailRoute(this.$route)) {
        routeKey = eventDetailKey(this.$route)
      } else if (['template', 'iso', 's2svpn'].includes(this.$route.meta?.name) && this.$route.params?.id) {
        const query = { ...this.$route.query }; delete query.tab
        routeKey = JSON.stringify([this.$route.path, query])
      } else if (this.$route.meta?.name === 'cniconfiguration' && this.$route.params?.id) {
        routeKey = cniHistoryRouteKey(this.$route)
      }
      return JSON.stringify([routeKey, ...(['template', 'iso', 'vm', 'kubernetesiso'].includes(this.$route.meta?.name) ? [this.$store.getters.userInfo?.roletype, this.$store.getters.userInfo?.roleid, this.$store.state?.user?.token, Object.keys(this.$store.getters.apis || {}).sort()] : []), ...(guestNetworkRoute(this.$route) ? [this.guestNetworkAuth] : []), ...(publicIpRoute(this.$route) ? [this.publicIpAuth] : []), ...(asnRoute(this.$route) ? [this.asnSearchAuth] : []), ...(this.s2sGatewaySearchAuth ? [this.s2sGatewaySearchAuth] : []), ...((snapshotListRoute(this.$route) || snapshotDetailRoute(this.$route) || policyDetailRoute(this.$route) || snapshotPolicyListRoute(this.$route) || this.isObjectStorageUi) ? [this.$store.getters.userInfo?.roletype, this.$store.state?.user?.token, this.$store.getters.userInfo?.roleid, ...(snapshotDetailRoute(this.$route) ? [snapshotDetailPermissions(this.$store.getters)] : []), ...(policyDetailRoute(this.$route) ? [this.$store.getters.userInfo?.roleid, policyDetailPermissions(this.$store.getters)] : [])] : []), this.$store.getters.project?.id,
        this.$store.getters.userInfo?.id, this.$store.getters.metrics, this.$store.getters.listAllProjects])
    },
    getStyle () {
      if (['snapshot', 'snapshotpolicy', 'vmsnapshot', 'publicip'].includes(this.$route.name)) {
        return 'table-cell'
      }
      return 'inline-flex'
    },
    getOkProps () {
      if (this.selectedRowKeys.length > 0 && this.currentAction?.invokedAsGroupAction) {
      } else {
        return { props: { type: 'primary' } }
      }
    },
    getCancelProps () {
      if (this.selectedRowKeys.length > 0 && this.currentAction?.invokedAsGroupAction) {
        return { props: { type: 'primary' } }
      } else {
        return { props: { type: 'default' } }
      }
    },
    canonicalNetworkProjectRoute () {
      return ['guestnetwork', 'networkoffering', 'vnfapp'].includes(this.$route.meta?.name) ||
        (this.$route.meta?.name === 'vpc' && this.$route.params?.id && this.$route.query.projectid === '0')
    },
    async syncSnapshotProject (projectId) {
      // The URL retains the scope. 0 is a UI-only default-view marker, never an API project ID.
      if (typeof projectId !== 'string') return
      const globalView = ['0', '-1'].includes(projectId)
      if (vnfRoute(this.$route) && !globalView && !('listProjects' in (this.$store.getters.apis || {}))) { this.rejectVnfSearch('message.vnf.search.permission'); return }
      if ((globalView ? !this.$store.getters.project?.id : projectId === this.$store.getters.project?.id) && !!this.$store.getters.listAllProjects === (projectId === '-1')) return
      const fromVnf = vnfRoute(this.$route)
      // Canonicalizing search predicates must not cancel a lookup for the same
      // authorized project. A different route, owner or session still cancels it.
      const scope = () => JSON.stringify([fromVnf ? [this.$route.path, this.$route.query.projectid, vnfAuth(this.$store.getters, this.$store.state?.user?.token)] : this.$route.fullPath, this.$store.getters.userInfo?.id, this.$store.state?.user?.token, this.$store.getters.project?.id, this.$store.getters.listAllProjects])
      const captured = scope()
      let retry = false
      if (vnfRoute(this.$route) && this.vnfProjectSync) return
      if (vnfRoute(this.$route)) this.vnfProjectSync = true
      try {
        let project = {}
        if (!globalView) {
          const json = await getAPI('listProjects', { id: projectId, listall: true, details: 'min', ignoreproject: true }, { preserveOnFailure: true, timeout: 20000 })
          project = json?.listprojectsresponse?.project?.find(item => item.id === projectId)
          if (!project) throw new Error(this.$t('message.snapshot.search.lookup.denied'))
        }
        if (!(snapshotListRoute(this.$route) || snapshotPolicyListRoute(this.$route) || this.canonicalNetworkProjectRoute()) || captured !== scope() || this.$route.query.projectid !== projectId) { retry = fromVnf && vnfRoute(this.$route) && !!this.$store.state?.user?.token && 'listVnfAppliances' in (this.$store.getters.apis || {}); return }
        await this.$store.dispatch('SetProject', project)
        await this.$store.dispatch('SetListAllProjects', projectId === '-1')
        if (vnfRoute(this.$route)) this.vnfProjectSync = false
        this.fetchData({ irefresh: true })
      } catch (error) { if ((snapshotListRoute(this.$route) || snapshotPolicyListRoute(this.$route) || this.canonicalNetworkProjectRoute()) && captured === scope()) { if (vnfRoute(this.$route)) this.rejectVnfSearch('message.vnf.search.permission'); this.$notifyError(error) } } finally { if (fromVnf) { this.vnfProjectSync = false; if (retry) this.fetchData() } }
    },
    changeListProjects (enabled) {
      if (this.isVpnUser) {
        if (this.projectView || !canSwitchVpnUserProjects(this.$store.getters)) return
        this.$store.dispatch('SetListAllProjects', enabled)
        return this.$router.push({ query: vpnUserListQuery({ projectid: enabled ? '-1' : '0', pagesize: this.pageSize }, this.pageSize, this.$store.getters) })
      }
      if (this.isEventList) {
        const query = { ...this.$route.query, projectid: enabled ? '-1' : '0', page: '1' }
        if (enabled) delete query.account
        this.$store.dispatch('SetListAllProjects', enabled)
        return this.$router.push({ query })
      }
      this.$store.dispatch('SetListAllProjects', enabled)
    },
    switchProject (projectId) {
      if (this.isVpnUser) return
      // Event reads carry their own project scope, without changing the selected project on detail navigation.
      if (this.$route.meta?.name === 'event') return
      // Public IP URLs are validated against the selected scope; the legacy
      // switcher removes projectid and would fight canonical query restoration.
      if (publicIpRoute(this.$route) || (this.$route.meta?.name === 's2svpnconn' && projectId === this.$store.getters.project?.id)) return
      if (snapshotListRoute(this.$route) || snapshotPolicyListRoute(this.$route) || this.canonicalNetworkProjectRoute()) return this.syncSnapshotProject(projectId)
      if (!projectId || !projectId.length || projectId.length !== 36) {
        return
      }
      getAPI('listProjects', { id: projectId, listall: true, details: 'min' }).then(json => {
        if (!json || !json.listprojectsresponse || !json.listprojectsresponse.project) return
        const projects = json.listprojectsresponse.project
        const project = json.listprojectsresponse.project[0]
        this.$store.dispatch('SetProject', project)
        this.$store.commit('RELOAD_ALL_PROJECTS', projects)
        this.$store.dispatch('ToggleTheme', project.id === undefined ? 'light' : 'dark')
        this.$message.success(`${this.$t('message.switch.to')} "${project.name}"`)
        const query = Object.assign({}, this.$route.query)
        delete query.projectid
        this.$router.replace({ query })
      })
    },
    rejectTemplateDetail (message) {
      this.templateDetailError = message
      this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null
      this.actions = []; this.listSchemaScope = null
      this.$emit('change-resource', this.resource)
    },
    retainIsoOperation (value) {
      if (this.$route.meta?.name !== 'iso' || value.context !== isoZoneContext(this.resource, this.$store.getters, this.$store.state?.user?.token)) return
      this.isoOperationContext = value.active ? value.context : ''; this.isoOperationBusy = !!value.busy && !!value.active
    },
    retainTemplateOperation (value) {
      if (this.$route.meta?.name === 'template' && value.context === templateObservationContext(this.$route, this.resource, this.$store.getters, this.$store.state?.user?.token)) this.templateOperationContext = value.active ? value.context : ''
    },
    rejectVnfSearch (message) {
      this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false; this.loading = false
      this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.listRefreshError = false; this.vnfSearchFailure = message
      this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection()
    },
    async restorePublicIpProject (projectid) {
      if (this.publicIpProjectSync) return
      const route = this.$route.fullPath; const auth = this.publicIpAuth
      const current = () => publicIpRoute(this.$route) && !this.$route.params?.id && route === this.$route.fullPath && auth === this.publicIpAuth
      this.publicIpProjectSync = true; this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false; this.loading = false
      this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null
      this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection()
      if (typeof this.$refs.listview?.closeContextQuickView === 'function') this.$refs.listview.closeContextQuickView()
      if (this.showAction) this.closeAction()
      let retry = true
      try {
        if (!('listProjects' in (this.$store.getters.apis || {}))) throw Error('scope')
        let project = {}
        if (!['0', '-1'].includes(projectid)) {
          const json = await getAPI('listProjects', { id: projectid, listall: true, details: 'min', ignoreproject: true }, { preserveOnFailure: true, timeout: 20000 })
          project = json?.listprojectsresponse?.project?.find(row => row.id === projectid)
          if (current() && !project) throw Error('scope')
        }
        if (current()) {
          this.publicIpProjectApplying = true
          await this.$store.dispatch('SetProject', project)
          await this.$store.dispatch('SetListAllProjects', projectid === '-1')
        }
      } catch (_) {
        if (current()) { this.publicIpSearchFailure = 'message.public.ip.search.scope'; this.listRefreshError = true }
        retry = false
      } finally {
        this.publicIpProjectApplying = false; this.publicIpProjectSync = false
      }
      if (retry && publicIpRoute(this.$route) && !this.$route.params?.id && this.$store.state?.user?.token) return this.fetchData()
    },
    rejectAsnSearch (reason) {
      this.asnSearchFailure = reason; this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false; this.loading = false
      this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.resetSelection()
      if (this.$refs.listview) this.$refs.listview.resetSelection()
    },
    rejectS2sGatewaySearch (reason) {
      this.s2sGatewaySearchFailure = reason; this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false; this.loading = false
      this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.resetSelection()
      if (this.$refs.listview) this.$refs.listview.resetSelection()
    },
    isKmsRelatedVolume (route) {
      const state = this.$router?.options?.history?.state
      return route.meta?.name === 'volume' && state?.kmsRelatedReturn &&
        !!kmsRelatedParentLocation(route, state, this.$store.getters, this.$store.state?.user?.token)
    },
    currentKmsListLocation () {
      return kmsListLocation(this.$route, this.$router.options.history.state, this.$store.getters, this.$store.state?.user?.token)
    },
    rejectKmsDetail (reason) {
      this.invalidateKmsResults()
      this.kmsDetailError = reason
      this.$emit('change-resource', this.resource)
    },
    invalidateKmsResults () {
      this.kmsDetailError = ''
      this.listRequestVersion++
      this.listRequestPending = false
      this.listRefreshQueued = false
      this.listLoadedScope = null
      this.items = []
      this.itemCount = 0
      this.resource = {}
      this.loading = false
      this.resetSelection()
      if (this.$refs.listview) this.$refs.listview.resetSelection()
    },
    invalidateVpnUserRead () {
      this.listRequestVersion++
      this.listRequestPending = false
      this.listRefreshQueued = false
      this.loading = false
      this.items = []
      this.resource = {}
      this.itemCount = 0
      this.listLoadedScope = null
      this.listRefreshError = false
      this.searchParams = {}
      this.resetSelection()
      if (this.$refs.listview) {
        this.$refs.listview.resetSelection()
        this.$refs.listview.closeContextQuickView()
      }
      this.detailActionsVisible = false
      if (this.showAction) this.closeAction()
    },
    fetchData (params = {}) {
      const vpnUser = this.isVpnUser
      if (vpnUser) {
        if (this.vpnUserProjectSwitching) return
        this.dataView = !!this.$route.params?.id
        const getters = this.$store.getters
        if (!this.$store.state?.user?.token || !getters.apis?.listVpnUsers ||
            (getters.project?.id && !this.$getApiParams('listVpnUsers').projectid)) {
          this.invalidateVpnUserRead()
          this.listRefreshError = true
          return
        }
        const query = vpnUserListQuery(this.$route.query, this.pageSize, getters)
        if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) {
          this.invalidateVpnUserRead()
          return this.$router.replace({ query })
        }
      }
      const kmsList = kmsListRoute(this.$route)
      const kmsDetail = this.isKmsDetail
      if (kmsDetail) this.kmsDetailError = ''
      if (kmsList) {
        const api = kmsListApi(this.$route)
        if (!(api in this.$store.getters.apis)) {
          this.invalidateKmsResults()
          this.kmsSearchError = 'forbidden'
          return
        }
        const raw = this.$route.query
        const query = kmsQuery(raw, api, this.$store.getters, this.getValidPageSize(this.pageSize))
        if (JSON.stringify(query) !== JSON.stringify(raw)) {
          this.invalidateKmsResults()
          return this.$router.replace({ query })
        }
        this.kmsSearchError = ''
      }
      if (this.isS2sSearchList) {
        this.dataView = false
        const query = s2sGatewayQuery(this.$route.query, this.getValidPageSize(this.pageSize), this.$store.getters, this.s2sSearchApi)
        const error = !this.$store.state?.user?.token ? 'permission' : s2sGatewayError(query, this.$store.getters, this.$getApiParams(this.s2sSearchApi), this.s2sSearchApi)
        if (error) { this.rejectS2sGatewaySearch(error); return Promise.resolve() }
        if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) { this.rejectS2sGatewaySearch(''); return this.$router.replace({ query }) }
        this.s2sGatewaySearchFailure = ''
      }
      if (asnRoute(this.$route)) {
        this.dataView = false; this.asnSearchFailure = ''
        const query = asnQuery(this.$route.query, this.getValidPageSize(this.pageSize), this.$store.getters)
        const error = !this.$store.state?.user?.token ? 'permission' : asnError(query, this.$store.getters)
        if (error) { this.rejectAsnSearch(error); return Promise.resolve() }
        if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) { this.rejectAsnSearch(''); return this.$router.replace({ query }) }
      }
      if (publicIpRoute(this.$route) && !this.$route.params?.id) {
        if (this.publicIpProjectSync) return Promise.resolve()
        this.dataView = false; this.publicIpSearchFailure = ''
        const query = publicIpQuery(this.$route.query, this.getValidPageSize(this.pageSize), this.$store.getters)
        const target = { ...this.$store.getters, project: ['0', '-1'].includes(query.projectid) ? {} : { id: query.projectid }, listAllProjects: query.projectid === '-1' }
        const error = !this.$store.state?.user?.token ? 'message.public.ip.search.permission' : publicIpSearchError(query, target, this.$getApiParams('listPublicIpAddresses'))
        if (error || JSON.stringify(query) !== JSON.stringify(this.$route.query)) {
          this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false; this.loading = false
          this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.listRefreshError = false
          this.resetSelection(); if (this.$refs.listview) { this.$refs.listview.resetSelection(); if (typeof this.$refs.listview.closeContextQuickView === 'function') this.$refs.listview.closeContextQuickView() }
          if (error) { this.publicIpSearchFailure = error; return Promise.resolve() }
          return this.$router.replace({ query })
        }
        if (query.projectid !== publicIpProject(this.$store.getters)) return this.restorePublicIpProject(query.projectid)
      }
      const kubernetesIsoQuery = this.$route.meta?.name === 'kubernetesiso'
      if (kubernetesIsoQuery) {
        this.dataView = !!this.$route.params?.id
        this.kubernetesIsoReadError = ''
        const reject = reason => {
          this.listRequestVersion++; this.listRequestPending = false; this.loading = false; this.listLoadedScope = null
          this.items = []; this.resource = {}; this.itemCount = 0; this.listRefreshError = true; this.kubernetesIsoReadError = reason
          this.resetSelection(); this.$emit('change-resource', this.resource)
        }
        if (!this.$store.state?.user?.token || !kubernetesIsoCapability('read', this.$store.getters).available) { reject('forbidden'); return Promise.resolve() }
        try { kubernetesIsoReadParams({}, this.$route.params?.id) } catch (_) { reject('invalid'); return Promise.resolve() }
        if (!this.dataView) {
          const query = kubernetesIsoListQuery(this.$route.query, this.pageSize, this.$store.getters, this.$store.state?.user?.token)
          if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) return this.$router.replace({ query })
          const error = kubernetesIsoSearchError(query, this.$store.getters)
          if (error) { reject(error === 'message.kubernetes.iso.search.unsupported' ? 'unsupported' : 'searchInvalid'); return Promise.resolve() }
        } else if (this.$route.query.kisoowner && this.$route.query.kisoowner !== kubernetesIsoListOwner(this.$store.getters, this.$store.state?.user?.token)) {
          const query = { ...this.$route.query }; delete query.kisoowner; delete query.kisoreturn
          return this.$router.replace({ query })
        }
      }
      if (vnfRoute(this.$route)) {
        this.dataView = !!this.$route.params?.id
        this.vnfSearchFailure = ''
        if (!this.$store.state?.user?.token || !('listVnfAppliances' in (this.$store.getters.apis || {}))) { this.rejectVnfSearch('message.vnf.search.permission'); return Promise.resolve() }
        if (!this.dataView) {
          const query = vnfListQuery(this.$route.query, this.getValidPageSize(this.pageSize), this.$store.getters)
          if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) { this.rejectVnfSearch(''); return this.$router.replace({ query }) }
          const error = vnfSearchError(query, this.$store.getters, this.$getApiParams('listVnfAppliances'))
          if (error) { this.rejectVnfSearch(error); return Promise.resolve() }
        }
        const projectid = this.$route.query.projectid
        if (projectid && (projectid !== vnfProject(this.$store.getters) || !!this.$store.getters.listAllProjects !== (projectid === '-1'))) return this.syncSnapshotProject(projectid)
        if (this.vnfProjectSync) return Promise.resolve()
        if (!this.dataView) { this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection() }
      }
      const isoQuery = this.$route.meta?.name === 'iso'
      if (isoQuery && this.$route.params?.id && this.isoOperationContext && this.isoOperationContext === isoZoneContext(this.resource, this.$store.getters, this.$store.state?.user?.token)) return Promise.resolve()
      if (isoQuery) {
        this.isoListError = ''
        if (!this.$route.params?.id) {
          this.dataView = false
          const query = isoListQuery(this.$route.query, this.pageSize, this.$store.getters)
          if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) return this.$router.replace({ query })
          this.isoListError = isoSearchError(query, this.$getApiParams('listIsos'), this.$store.getters)
          if (this.isoListError) {
            this.listRequestVersion++; this.listRequestPending = false; this.loading = false; this.listLoadedScope = null
            this.items = []; this.resource = {}; this.itemCount = 0; this.resetSelection()
            return Promise.resolve()
          }
        }
      }
      const templateDetail = this.$route.meta?.name === 'template' && !!this.$route.params?.id
      const templateOperationHeld = () => templateDetail && this.templateOperationContext && this.templateOperationContext === templateObservationContext(this.$route, this.resource, this.$store.getters, this.$store.state?.user?.token)
      if (templateOperationHeld()) return Promise.resolve()
      this.templateDetailError = ''
      this.templateFilterError = ''
      if (this.$route.meta?.name === 'template' && !templateDetail) {
        const query = templateListQuery(this.$route.query, this.pageSize)
        this.dataView = false
        if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) return this.$router.replace({ query })
        this.templateFilterError = templateSearchError(query, this.$getApiParams('listTemplates'))
        if (this.templateFilterError) { this.listRequestVersion++; this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.listRequestPending = false; this.loading = false; this.resetSelection(); return Promise.resolve() }
      }
      if (templateDetail && !this.$isValidUuid(this.$route.params.id)) {
        this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false
        this.dataView = true; this.loading = false
        this.rejectTemplateDetail('message.template.detail.invalid')
        return Promise.resolve()
      }
      const policyRoute = snapshotPolicyListRoute(this.$route) || policyDetailRoute(this.$route)
      // Apply the destination view before URL/permission guards can return early.
      if (policyRoute) this.dataView = !!this.$route.params?.id
      if (policyRoute && !('listSnapshotPolicies' in (this.$store.getters.apis || {}))) {
        // Logout/role discovery can clear permissions before this view leaves.
        // Invalidate pending reads without sending a new request with a revoked session.
        this.listRequestVersion++; this.listRequestPending = false; this.listRefreshQueued = false
        this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.loading = false
        this.listRefreshError = true; this.snapshotListError = 'message.snapshot.policy.detail.missing'
        this.resetSelection(); this.$emit('change-resource', this.resource)
        return
      }
      if (snapshotPolicyListRoute(this.$route)) {
        const clean = snapshotPolicyListQuery(normalizePolicySearch({ ...this.$route.query, projectid: policyProjectScope(this.$route, this.$store.getters) }), this.pageSize)
        if (JSON.stringify(clean) !== JSON.stringify(this.$route.query)) {
          this.listRequestVersion++; this.listRequestPending = false; this.listLoadedScope = null
          this.items = []; this.resource = {}; this.itemCount = 0; this.loading = false; this.resetSelection()
          return this.$router.replace({ query: clean })
        }
        this.policySearchFailure = policySearchError(clean, this.$store.getters)
        if (this.policySearchFailure) {
          this.listRequestVersion++; this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null
          this.listRequestPending = false; this.loading = false; this.listRefreshError = false; this.resetSelection()
          return
        }
      } else {
        this.policySearchFailure = ''
      }
      if (this.$route.name === 'snapshot' && !this.$route.params.id) {
        const projectId = this.$route.query.projectid || this.$store.getters.project?.id || (this.$store.getters.listAllProjects ? '-1' : '0')
        const clean = storageSnapshotListQuery(normalizeSnapshotSearch({ ...this.$route.query, ...(projectId ? { projectid: projectId } : {}) }))
        if (JSON.stringify(clean) !== JSON.stringify(this.$route.query)) return this.$router.replace({ query: clean })
        this.snapshotSearchFailure = snapshotSearchError(clean) || snapshotSearchPermissionError(clean, this.$store.getters)
        if (this.snapshotSearchFailure) {
          this.listRequestVersion++; this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null
          this.listRequestPending = false; this.loading = false; this.listRefreshError = false; this.resetSelection()
          return
        }
      } else {
        this.snapshotSearchFailure = ''
      }
      if (this.$route.meta?.name === 'affinitygroup') {
        const clean = affinityGroupQuery(this.$route.query)
        if (JSON.stringify(clean) !== JSON.stringify(this.$route.query)) {
          return this.$router.replace({ query: clean })
        }
      }
      if (isSshKeyPairDetailRoute(this.$route)) {
        const context = this.listScope()
        if (this.sshKeyPairDetailContext !== context) {
          this.sshKeyPairDetailContext = context
          this.items = []; this.resource = {}; this.itemCount = 0
          this.listLoadedScope = null; this.listRefreshQueued = false
          this.sshKeyPairDetailStatus = 'loading'
          this.resetSelection()
          if (this.showAction) this.closeAction()
          if (this.showGroupActionModal) this.handleCancel()
        }
      }
      if (this.$route.meta?.name === 'vmgroup') {
        const clean = vmGroupQuery(this.$route.query)
        if (vmGroupQueryChanged(this.$route.query, clean)) {
          if (Object.keys(this.$route.query).some(key => !(key in clean))) this.$message.info(this.$t('message.vm.group.query.removed'))
          return this.$router.replace({ query: clean })
        }
        const context = this.listScope()
        if (this.vmGroupContextScope !== context) {
          this.vmGroupContextScope = context
          this.items = []; this.resource = {}; this.itemCount = 0
          this.listLoadedScope = null; this.listLastUpdated = null; this.listRefreshError = false
          this.listRefreshQueued = false
          this.resetSelection()
          if (this.$refs.listview) this.$refs.listview.resetSelection()
          if (this.showGroupActionModal) this.handleCancel()
          if (this.showAction) this.closeAction()
          this.vmGroupQueryStatus = 'loading'
        }
      }
      // [ADD] 모든 반복 전 배열 보장 유틸
      const asArray = (v) => Array.isArray(v) ? v : []

      if (['deployVirtualMachine', 'usage'].includes(this.$route.name)) {
        return
      }
      const currentRouteName = this.$route.meta?.name === 'vmgroup' ? 'vmgroup' : this.$route.name
      if (this.routeName !== currentRouteName) {
        this.routeName = currentRouteName
        this.items = []
      }
      if (!this.routeName) {
        const matchedRoute = this.$route?.matched?.length
          ? this.$route.matched[this.$route.matched.length - 1]
          : null
        this.routeName = matchedRoute?.meta?.name || this.$route?.meta?.name || this.$route?.name || ''
      }
      const securityScope = JSON.stringify([...(['template', 'iso', 'vm', 'kubernetesiso'].includes(this.$route.meta?.name) ? [this.$store.getters.userInfo?.roletype, this.$store.getters.userInfo?.roleid, this.$store.state?.user?.token, Object.keys(this.$store.getters.apis || {}).sort()] : []), ...(guestNetworkRoute(this.$route) ? [this.guestNetworkAuth] : []), ...(publicIpRoute(this.$route) ? [this.publicIpAuth] : []), ...(asnRoute(this.$route) ? [this.asnSearchAuth] : []), ...(this.s2sGatewaySearchAuth ? [this.s2sGatewaySearchAuth] : []), this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.$route.params.id, ...(this.$route.meta?.name === 'event' ? [this.$route.query.startid, this.$route.query.projectid, this.$store.state?.user?.token] : []), ...((snapshotListRoute(this.$route) || snapshotDetailRoute(this.$route) || policyDetailRoute(this.$route) || snapshotPolicyListRoute(this.$route) || this.isObjectStorageUi) ? [this.$store.getters.userInfo?.roletype, this.$store.state?.user?.token, this.$store.getters.userInfo?.roleid, ...(snapshotDetailRoute(this.$route) ? [snapshotDetailPermissions(this.$store.getters)] : []), ...(policyDetailRoute(this.$route) ? [this.$store.getters.userInfo?.roleid, policyDetailPermissions(this.$store.getters)] : [])] : [])])
      if (this.listSecurityScope !== securityScope) {
        this.items = []
        this.resource = {}
        this.itemCount = 0
        this.listLoadedScope = null
        this.listSecurityScope = securityScope
        if (this.publicIpDetail) this.$emit('change-resource', this.resource)
        if (this.$route.meta?.name === 'vm') { this.resetSelection(); this.listSchemaScope = null }
      }
      const scope = this.listScope()
      if (this.listRequestPending && this.listRequestScope === scope) {
        if (!params.autoscheduled) this.listRefreshQueued = true
        return this.listRequestPromise
      }
      const version = ++this.listRequestVersion
      const sameList = this.listLoadedScope === scope
      const publicIpList = this.$route.meta?.name === 'publicip' && !this.$route.params?.id && !this.$route.query?.dataView
      if (vpnUser && !sameList) {
        this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.listRefreshError = false
        this.resetSelection()
        if (this.$refs.listview) {
          this.$refs.listview.resetSelection()
          this.$refs.listview.closeContextQuickView()
        }
      }
      if ((this.isEventList || this.isProjectList || this.isRoleList || this.isAccountList) && !sameList) {
        this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.listRefreshError = false
        this.resetSelection()
        if (this.$refs.listview) this.$refs.listview.resetSelection()
      }
      if (kubernetesIsoQuery && !sameList) {
        this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.resetSelection()
      }
      if ((kmsList || publicIpList || (this.isAclUi && !this.dataView) || snapshotListRoute(this.$route) || snapshotDetailRoute(this.$route) || policyDetailRoute(this.$route) || snapshotPolicyListRoute(this.$route) || (this.isObjectStorageUi || guestNetworkRoute(this.$route) || vnfRoute(this.$route) || this.isS2sSearchList) && !this.dataView) && !sameList) {
        this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null
        this.snapshotListError = ''; this.listRefreshError = false
        this.resetSelection()
        if (this.$refs.listview) this.$refs.listview.resetSelection()
        if (publicIpList && typeof this.$refs.listview?.closeContextQuickView === 'function') this.$refs.listview.closeContextQuickView()
      }
      const schemaScope = JSON.stringify([this.$route.path, this.$i18n?.locale, this.$store.getters.metrics, this.$store.getters.listAllProjects, this.$store.getters.userInfo?.id, this.isSnapshotCopyList, ...(['vm', 'kubernetesiso'].includes(this.$route.meta?.name) ? [this.$store.getters.userInfo?.roleid, this.$store.getters.userInfo?.roletype, this.$store.state?.user?.token, Object.keys(this.$store.getters.apis || {}).sort()] : []), ...(this.isObjectStorageUi ? [this.$store.getters.project?.id, this.$store.getters.userInfo?.roletype, this.$store.getters.userInfo?.roleid, this.$store.state?.user?.token] : [])])
      const rebuildSchema = this.listSchemaScope !== schemaScope
      if (rebuildSchema) {
        this.apiName = ''
        this.actions = []
        this.columns = []
        this.columnKeys = []
        this.selectedColumns = []
      }
      const refreshed = ('irefresh' in params)
      const isAutoScheduled = Boolean(params.autoscheduled)

      params.listall = true

      this.dataView = !!(this.$route?.params?.id || (this.$route.meta?.name === 'vmgroup' ? this.$route.query.dataView === 'true' : !!this.$route?.query?.dataView))

      if (this.$route.meta.params) {
        const metaParams = this.$route.meta.params
        if (typeof metaParams === 'function') {
          Object.assign(params, metaParams(this.dataView))
        } else {
          Object.assign(params, metaParams)
        }
      }
      if (['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo.roletype) &&
        'templatefilter' in params && (['template'].includes(this.routeName))) {
        params.templatefilter = 'all'
      }
      if (['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo.roletype) &&
        'isofilter' in params && this.routeName === 'iso') {
        params.isofilter = 'all'
      }
      if (['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo.roletype) &&
        ['computeoffering', 'systemoffering', 'diskoffering'].includes(this.routeName) && this.$route.params.id) {
        params.state = 'all'
      }
      if (Object.keys(this.$route.query).length > 0) {
        if ('page' in this.$route.query) {
          const page = Number(this.$route.query.page)
          this.page = Number.isInteger(page) && page > 0 ? page : 1
        }
        if ('pagesize' in this.$route.query) {
          this.pageSize = this.getValidPageSize(this.$route.query.pagesize)
        }
        Object.assign(params, this.$route.query)
      }
      if ((this.$route.name === 'vmsnapshot' || ['vmgroup', 'cniconfiguration', 'guestnetwork', 'iso', 'kubernetesiso', 'zone'].includes(this.$route.meta?.name)) && !rebuildSchema) {
        this.columns.forEach(column => { column.sortOrder = (params.sortkey || (this.$route.name === 'vmsnapshot' ? 'created' : '')) === column.key ? (params.sortorder === 'asc' ? 'ascend' : 'descend') : null })
      }
      if (this.$route.name === 'snapshot' && !this.dataView && !rebuildSchema) {
        this.columns.forEach(column => Object.assign(column, storageSnapshotSortColumn(column.key, params)))
      }
      if (snapshotPolicyListRoute(this.$route) && !rebuildSchema) {
        this.columns.forEach(column => Object.assign(column, snapshotPolicySortColumn(column.key, params, this.$t)))
      }
      delete params.q
      delete params.filter
      delete params.irefresh
      delete params.autoscheduled
      delete params.pipreturn
      delete params.cnireturn
      delete params.s2sreturn
      delete params.networkreturn
      if (guestNetworkRoute(this.$route) && typeof params.forvpc === 'boolean') params.forvpc = String(params.forvpc)
      if (storageUiRoute(this.$route)) ['relatedkeyword', 'relatedpage', 'relatedpagesize'].forEach(key => delete params[key])

      this.searchFilters = this.$route && this.$route.meta && this.$route.meta.searchFilters
      this.filters = this.$route && this.$route.meta && this.$route.meta.filters
      if (this.isEventTimeline) {
        this.filters = ['all', 'active', 'archived']
      } else if (typeof this.filters === 'function') {
        this.filters = this.filters()
      }
      if (typeof this.searchFilters === 'function') {
        this.searchFilters = this.searchFilters()
      }

      if (vpnUser) this.searchFilters = vpnUserSearchFields(this.vpnUserScope, store.getters)
      this.projectView = this.isEventList ? !['0', '-1'].includes(eventProjectScope(this.$route, store.getters)) : Boolean(store.getters.project && store.getters.project.id)
      this.hasProjectId = vpnUser && canSwitchVpnUserProjects(store.getters) || (vpnConnectionListRoute(this.$route) && 'projectid' in this.$getApiParams(vpnConnectionApi)) || ['vm', 'vmgroup', 'ssh', 'affinitygroup', 'userdata', 'cniconfiguration', 'volume', 'snapshot', 'buckets', 'vmsnapshot', 'guestnetwork',
        'vpc', 'securitygroups', 'publicip', 's2svpn', 'vpncustomergateway', 'template', 'iso', 'event', 'kubernetes', 'sharedfs',
        'autoscalevmgroup', 'vnfapp', 'webhook', 'kmskey', 'hsmprofile', 'snapshotpolicy'].includes(this.$route.name)

      if (this.dataView && !refreshed && !sameList) {
        this.resource = {}
        this.$emit('change-resource', this.resource)
      }

      if (this.dataView && ['Admin'].includes(this.$store.getters.userInfo.roletype) && this.routeName === 'volume') {
        params.listsystemvms = true
      }

      const customRender = rebuildSchema ? {} : this.listCustomRender
      if (rebuildSchema) {
        if (this.$route && this.$route.meta && this.$route.meta.permission) {
          this.apiName = this.$route.meta?.name === 'vm' ? vmReadApi(store.getters.apis, { dataView: this.dataView, metrics: store.getters.metrics }) : this.$route.meta.permission[0]
          if (this.$route.meta?.name === 'vm' && !this.apiName) { this.listRefreshError = true; this.listRequestPending = false; this.loading = false; return }
          if (!store.getters.metrics && !this.dataView &&
            this.apiName && this.apiName.endsWith('Metrics') &&
            store.getters.apis[this.apiName.replace(/Metrics$/, '')]) {
            this.apiName = this.apiName.replace(/Metrics$/, '')
          }

          // [CHANGE] meta.columns → 항상 배열 보정
          if (this.$route.meta.columns) {
            const columns = this.$route.meta.columns
            if (columns && typeof columns === 'function') {
              this.columnKeys = asArray(columns(this.$store.getters, this.$route))
            } else {
              this.columnKeys = asArray(columns)
            }
          }

          // [CHANGE] meta.actions → 항상 배열 보정
          if (this.$route.meta.actions) {
            const acts = this.$route.meta.actions
            this.actions = asArray(typeof acts === 'function' ? acts(this.$store.getters) : acts)
          }
        }

        if (this.apiName === '' || this.apiName === undefined) {
          return
        }

        if (!this.columnKeys || this.columnKeys.length === 0) {
        // [CHANGE] API 메타 응답 반복 안전화
          const apiMeta = store.getters && store.getters.apis && store.getters.apis[this.apiName]
          const respFields = apiMeta && apiMeta.response
          for (const field of asArray(respFields)) {
            this.columnKeys.push(field.name)
          }
          this.columnKeys = [...new Set(this.columnKeys)]
          this.columnKeys.sort(function (a, b) {
            if (a === 'name' && b !== 'name') { return -1 }
            if (a < b) { return -1 }
            if (a > b) { return 1 }
            return 0
          })
        }

        // Optional AutoScale infrastructure fields use the existing VM column menu.
        const defaultColumnKeys = [...this.columnKeys]
        if (['autoscalevmgroup', 'sharedfs'].includes(this.$route.name)) {
          this.columnKeys = [...this.columnKeys, ...asArray(this.$route.meta.optionalColumns)]
        }
        // [CHANGE] 컬럼 루프 안전화
        for (const columnKey of asArray(this.columnKeys)) {
          let key = columnKey
          let title = columnKey === 'cidr' && this.columnKeys.includes('ip6cidr') ? 'ipv4.cidr' : key
          if (typeof columnKey === 'object') {
            if ('customTitle' in columnKey && 'field' in columnKey) {
              key = columnKey.field
              title = columnKey.customTitle
              customRender[key] = columnKey[key]
            } else {
              key = Object.keys(columnKey)[0]
              title = (typeof title === 'object') ? key : title
              customRender[key] = columnKey[key]
            }
          }
          const sorter = key === 'resources'
            ? (a, b) => {
              const cpuCompare = Number(a.cpunumber || 0) - Number(b.cpunumber || 0)
              if (cpuCompare !== 0) {
                return cpuCompare
              }
              return Number(a.memory || 0) - Number(b.memory || 0)
            }
            : (a, b) => genericCompare(isVmIdentityRoute(this.$route) && key === 'displayname' ? vmDisplayName(a) : a[key] || '', isVmIdentityRoute(this.$route) && key === 'displayname' ? vmDisplayName(b) : b[key] || '')
          this.columns.push({
            key: key,
            title: publicIpRoute(this.$route) && key === 'allocated' ? this.$t('label.public.ip.allocated') : this.$route.name === 'sharedfs' && key === 'capacity' ? this.$t('label.storage.capacity.column') : this.$route.name === 'sharedfs' && key === 'sizegb' ? this.$t('label.storage.capacity.initial') : this.$t('label.' + String(title).toLowerCase()),
            dataIndex: key,
            sorter: kubernetesIsoQuery ? kubernetesIsoSortFields.includes(key) && !!kubernetesIsoCapability('read', this.$store.getters).parameters.sortkey && !!kubernetesIsoCapability('read', this.$store.getters).parameters.sortorder : this.$route.name === 'sharedfs' && ['capacity', 'bootrecovery'].includes(key) ? false : this.$route.name === 'vmsnapshot' ? ['displayname', 'state', 'type', 'current', 'created'].includes(key) : sorter,
            ...((this.$route.name === 'vmsnapshot' || ['vmgroup', 'cniconfiguration', 'guestnetwork', 'kubernetesiso', 'zone'].includes(this.$route.meta?.name)) ? { sortOrder: (this.$route.query.sortkey || (this.$route.name === 'vmsnapshot' ? 'created' : '')) === key ? (this.$route.query.sortorder === 'asc' ? 'ascend' : 'descend') : null } : {}),
            ...(this.$route.name === 'snapshot' ? storageSnapshotSortColumn(key, this.$route.query) : {}),
            ...(snapshotPolicyListRoute(this.$route) ? snapshotPolicySortColumn(key, this.$route.query, this.$t) : {})
          })
          if (!['autoscalevmgroup', 'sharedfs'].includes(this.$route.name) || defaultColumnKeys.some(column => (typeof column === 'object' ? column.field : column) === key)) this.selectedColumns.push(key)
        }
        this.allColumns = ['autoscalevmgroup', 'sharedfs'].includes(this.$route.name) ? [...this.columns] : this.columns
        if (['autoscalevmgroup', 'sharedfs'].includes(this.$route.name)) this.columns = this.allColumns.filter(column => this.selectedColumns.includes(column.dataIndex))

        const requiredCopyColumns = this.isSnapshotCopyList ? this.columns.filter(column => ['copy', 'datastorename'].includes(column.key)) : []
        if (!store.getters.metrics || (isoQuery && !this.dataView)) {
          if (!this.$store.getters.customColumns[this.$store.getters.userInfo.id]) {
            this.$store.getters.customColumns[this.$store.getters.userInfo.id] = {}
            this.$store.getters.customColumns[this.$store.getters.userInfo.id][this.$route.path] = this.selectedColumns
          } else {
            this.selectedColumns = this.$store.getters.customColumns[this.$store.getters.userInfo.id][this.$route.path] || this.selectedColumns
            if (s2sGatewayRoute(this.$route)) this.selectedColumns = gatewayVisibleColumns(this.selectedColumns, defaultColumnKeys)
            if (this.$route.meta?.name === 'vnfapp' && this.selectedColumns.includes('name')) {
              this.selectedColumns = [...new Set(this.selectedColumns.map(key => key === 'name' ? 'displayname' : key))]
              this.$store.getters.customColumns[this.$store.getters.userInfo.id][this.$route.path] = this.selectedColumns
            }
            if (this.$route.name === 'sharedfs') {
              const oldDefaults = defaultColumnKeys.map(key => key === 'capacity' ? 'sizegb' : key)
              if (JSON.stringify(this.selectedColumns) === JSON.stringify(oldDefaults)) this.selectedColumns = [...defaultColumnKeys]
            }
            if (this.$route.name === 'autoscalevmgroup') {
              this.selectedColumns = autoScaleVisibleColumns(this.selectedColumns)
            }
            if (this.$route.name === 'kubernetes') {
              this.selectedColumns = [...new Set(this.selectedColumns.map(key => ['cpunumber', 'memory'].includes(key) ? 'resources' : key))]
            }
            if (this.$route.name === 'vmsnapshot') {
              // Replace the former default domain column in saved selections as well.
              this.selectedColumns = [...new Set(this.selectedColumns.map(key => key === 'domain' ? 'zonename' : key))]
            }
            if (this.$route.meta?.name === 'asnumbers') {
              const oldDefaults = ['asnumber', 'allocationstate', 'asnrange', 'associatednetworkname', 'vpcname', 'allocated', 'account', 'domain', 'zonename']
              if (JSON.stringify(this.selectedColumns) === JSON.stringify(oldDefaults)) this.selectedColumns = [...defaultColumnKeys]
            }
            if (vpnUser) this.selectedColumns = vpnUserVisibleColumns(this.selectedColumns)
            if (this.$store.getters.listAllProjects && !this.projectView && !isoQuery && !vpnUser) {
              this.selectedColumns.push('project')
            }
            this.updateSelectedColumns()
          }
        }

        if (snapshotListRoute(this.$route) && this.$localStorage) {
          const available = this.columnKeys.map(column => typeof column === 'object' ? column.field : column)
          this.selectedColumns = loadSnapshotListColumns(this.$localStorage, this.$store.getters.userInfo.id, this.isSnapshotCopyList, this.selectedColumns, available)
          this.updateSelectedColumns()
        }
        if (this.isSnapshotCopyList) {
          this.selectedColumns = [...new Set([...this.selectedColumns, 'copy', 'datastorename'])]
          requiredCopyColumns.forEach(column => { if (!this.columns.some(old => old.key === column.key)) this.columns.splice(1, 0, column) })
        }
        if (this.$route.name === 'autoscalevmgroup' && !this.columns.some(column => column.key === 'filtercolumn')) this.updateSelectedColumns()
        if (kubernetesIsoQuery && !this.columns.some(column => column.key === 'filtercolumn')) this.updateSelectedColumns()

        this.chosenColumns = this.columns.filter(column => {
          return ![this.$t('label.state'), this.$t('label.hostname'), this.$t('label.hostid'), this.$t('label.zonename'),
            this.$t('label.zone'), this.$t('label.zoneid'), this.$t('label.ip'), this.$t('label.ipaddress'), this.$t('label.privateip'),
            this.$t('label.linklocalip'), this.$t('label.size'), this.$t('label.sizegb'), this.$t('label.current'),
            this.$t('label.created'), this.$t('label.order'), this.$t('label.networkname'), this.$t('label.kvdoenable'),
            this.$t('label.usedfsbytes'), this.$t('label.qemuagentversion')].includes(column.title)
        })
        this.chosenColumns.splice(this.chosenColumns.length - 1, 1)

        if (this.$route.meta?.name === 'template') {
          const widths = { state: 140, format: 96, ostypename: 180, arch: 104, hypervisor: 104, size: 112, account: 120, project: 136, templatetype: 120, order: 168 }
          this.columns.forEach(column => {
            if (widths[column.key]) column.width = widths[column.key]
            if (column.key === 'format') column.title = this.$t('label.template.image.format')
            if (column.key === 'templatetype') column.title = this.$t('label.template.type')
            if (column.key === 'name') delete column.width
            column.ellipsis = column.key === 'order' ? false : { showTitle: false }
            const previousCell = column.customCell
            column.customCell = record => {
              const attributes = typeof previousCell === 'function' ? previousCell(record) : {}
              if (['name', 'state', 'order'].includes(column.key)) return attributes
              let value = column.key === 'project' ? (record.projectname || record.project?.name || record.project) : record[column.key]
              if (column.key === 'size' && value != null && this.$bytesToHumanReadableSize) value = this.$bytesToHumanReadableSize(value)
              return { ...attributes, title: value == null ? undefined : String(value) }
            }
            const previousHeader = column.customHeaderCell
            column.customHeaderCell = (...args) => ({ ...(typeof previousHeader === 'function' ? previousHeader(...args) : {}), title: typeof column.title === 'string' ? column.title : undefined })
          })
        }
        this.listSchemaScope = schemaScope
        this.listCustomRender = customRender
      }

      if (['listTemplates', 'listIsos'].includes(this.apiName) && this.dataView) {
        delete params.showunique
      }
      if (['listVirtualMachinesMetrics'].includes(this.apiName) && this.dataView) {
        delete params.details
        delete params.isvnf
        params.details = 'group,nics,secgrp,tmpl,servoff,diskoff,iso,volume,affgrp,backoff,guestnetwork'
      }

      if (this.apiName === 'quotaTariffList' && !('quotaTariffCreate' in store.getters.apis || 'quotaTariffUpdate' in store.getters.apis)) {
        const index = this.columns.findIndex(col => col.dataIndex === 'hasActivationRule')
        if (index >= 0) {
          this.columns.splice(index, 1)
        }
      }

      this.loading = !sameList
      if (this.$route.path.startsWith('/cniconfiguration')) {
        params.forcks = true
      }

      if (this.$route.params && this.$route.params.id) {
        params.id = this.$route.params.id
        if (this.apiName === 'listSharedFileSystems' && this.dataView) params.includecapacitydetails = true
        if (['listNetworks'].includes(this.apiName) && 'displaynetwork' in this.$route.query) {
          params.displaynetwork = this.$route.query.displaynetwork
        }
        if (['listSSHKeyPairs'].includes(this.apiName)) {
          if (!this.$isValidUuid(params.id)) {
            delete params.id
            params.name = this.$route.params.id
          }
          params.account = this.$route.query.account
          params.domainid = this.$route.query.domainid
        }
        if (['listUserKeys'].includes(this.apiName)) {
          delete params.listall
          params.keypairid = this.$route.params.id
        }
        if (['listPublicIpAddresses'].includes(this.apiName)) {
          params.allocatedonly = false
        }
        if (this.$route.path.startsWith('/vmsnapshot/')) {
          params.vmsnapshotid = this.$route.params.id
        }
        if (this.$route.path.startsWith('/tungstenpolicy/')) {
          params.policyuuid = this.$route.params.id
        }
        if (this.$route.path.startsWith('/tungstenpolicyset/')) {
          params.applicationpolicysetuuid = this.$route.params.id
        }
        if (this.$route.path.startsWith('/tungstennetworkroutertable/')) {
          params.tungstennetworkroutetableuuid = this.$route.params.id
        }
        if (this.$route.path.startsWith('/tungsteninterfaceroutertable/')) {
          params.tungsteninterfaceroutetableuuid = this.$route.params.id
        }
        if (this.$route.path.startsWith('/tungstenfirewallpolicy/')) {
          params.firewallpolicyuuid = this.$route.params.id
        }
        if (this.apiName === 'quotaSummary' && params.id) {
          params.accountid = params.id
          delete params.id
        }
        if (this.apiName === 'quotaEmailTemplateList' && params.id) {
          params.templatetype = params.id
          delete params.id
        }
      }

      if (this.$store.getters.listAllProjects && !this.projectView) {
        params.projectid = (this.$route.meta?.name === 'affinitygroup' || snapshotListRoute(this.$route) || snapshotPolicyListRoute(this.$route) || this.canonicalNetworkProjectRoute()) ? (this.$route.query.projectid || '-1') : '-1'
      }
      if (this.$route.meta?.name === 'event' && this.$route.query.projectid) params.projectid = this.$route.query.projectid
      if (this.isEventTimeline) params.timeline = true
      if (this.isEventList) {
        const archive = eventArchiveScope(this.$route.query, this.isEventTimeline)
        if (archive === 'all') delete params.archived
        else params.archived = archive === 'archived'
      }
      if (this.isEventList && !this.eventCreatedSortEnabled) {
        delete params.sortkey
        delete params.sortorder
      }
      if ((this.$route.meta?.name === 'event' || snapshotListRoute(this.$route) || this.canonicalNetworkProjectRoute()) && params.projectid === '0') { delete params.projectid; params.ignoreproject = true }
      if (snapshotPolicyListRoute(this.$route)) params = policySearchParams(params)

      params.page = Number.isFinite(Number(this.page)) && Number(this.page) > 0 ? Number(this.page) : 1
      params.pagesize = this.getValidPageSize(this.pageSize)

      if (this.$showIcon()) {
        params.showIcon = true
      }

      if (isoQuery) {
        this.columns = this.columns.map(column => isoColumn(column, this.$route.query, this.$t))
        this.allColumns = this.allColumns.map(column => isoColumn(column, this.$route.query, this.$t))
      }
      const customParamHandler = this.$route.meta.customParamHandler
      if (customParamHandler && typeof customParamHandler === 'function') {
        params = customParamHandler(params, this.$route.query)
      }
      if (kmsList) {
        params = kmsParams(this.$route.query)
        this.columns = this.columns.map(column => ({ ...column, sorter: false }))
        this.allColumns = this.allColumns.map(column => ({ ...column, sorter: false }))
      }
      if (this.isS2sSearchList) {
        params = s2sGatewayParams(this.$route.query, this.s2sSearchApi)
        this.columns.forEach(column => { column.sorter = false })
      }
      if (this.isVpnConnectionDetail) {
        params = s2sGatewayParams({ id: this.$route.params.id, projectid: s2sGatewayProject(this.$store.getters) }, vpnConnectionApi)
        // A plain detail bookmark keeps the API's ACL-checked UUID lookup.
        // The account-only list still sends excludeprojectresources.
        delete params.excludeprojectresources
        if (vpnConnectionDisplaySupported(this.$store.getters)) params.displayall = true
      }
      if (this.apiName === 'listPhysicalNetworks' && this.$route.params?.id) params = { id: this.$route.params.id }
      if (guestVlanRoute(this.$route)) params = guestVlanReadParams(params)
      if (['listZones', 'listZonesMetrics'].includes(this.apiName) && this.$route.meta?.name === 'zone') params = zoneReadParams(this.$route.query, params)
      if (this.isDnsServer) {
        const id = this.$route.params?.id
        const { providertype, keyword } = dnsServerListQuery(this.$route.query)
        params = id ? { id } : { page: params.page, pagesize: params.pagesize, providertype, keyword }
        if (this.$getApiParams('listDnsServers')?.includedisabled) params.includedisabled = true
      }
      if (this.isDnsZone) {
        const id = this.$route.params?.id
        const { dnsserverid, keyword } = this.$route.query
        params = id
          ? { id }
          : { page: params.page, pagesize: params.pagesize, dnsserverid: dnsserverid || params.dnsserverid, keyword: keyword || params.keyword }
      }
      if (ipv4SubnetRoute(this.$route)) params = ipv4SubnetReadParams(this.$route.query, params)
      if (asnRoute(this.$route)) { params = asnParams(this.$route.query); this.columns.forEach(column => { column.sorter = false }) }
      if (publicIpList) params = publicIpParams(this.$route.query, this.$store.getters, this.$getApiParams('listPublicIpAddresses'))
      if (this.isAccountList && this.apiName === 'listAccounts') params = accountListParams(this.$route.query, params)
      if (this.apiName === 'listRoles') {
        if (this.isRoleList) params = roleListParams(this.$route.query, params)
        else if (params.id) Object.assign(params, roleUsageParams(this.$store.getters.apis))
      }
      if (this.isProjectList && this.apiName === 'listProjects') params = projectListParams(this.$route.query, params, this.$store.getters)
      if (vnfRoute(this.$route)) params = vnfListParams(params, this.dataView)

      if (['listAnnotations', 'listRoles', 'listZonesMetrics', 'listPods',
        'listClustersMetrics', 'listHostsMetrics', 'listStoragePoolsMetrics',
        'listImageStores', 'listSystemVms', 'listManagementServers',
        'listConfigurations', 'listHypervisorCapabilities',
        'listAlerts', 'listNetworkOfferings', 'listVPCOfferings',
        'listASNumbers', 'listWallAlertRules'].includes(this.apiName)) {
        delete params.listall
      }

      this.listRequestPending = true
      this.listRequestScope = scope
      if (this.publicIpDetail) this.$emit('public-ip-read-state', { pending: true, failed: this.listRefreshError })
      if (this.isVpnCustomerGateway && this.dataView) {
        params = { id: this.$route.params.id, listall: true, projectid: params.projectid }
      }
      const requestApi = this.apiName
      const vmGroups = this.$route.meta?.name === 'vmgroup'
      if (vmGroups) {
        this.columns.forEach(column => { if (column.key === 'virtualmachinestates') column.sorter = false })
        params = vmGroupQueryParams(params)
        if (this.dataView) { params.page = 1; params.pagesize = 20 }
        this.vmGroupQueryStatus = sameList ? 'refreshing' : 'loading'
      }
      const sshDetail = isSshKeyPairDetailRoute(this.$route)
      const sshTarget = sshDetail ? sshKeyPairDetailTarget(this.$route, this.$store.getters.project, this.$store.getters.listAllProjects) : null
      if (sshDetail) {
        if (sshTarget.invalid) {
          this.rejectSshKeyPairDetail('mismatch')
          this.listRequestPending = false; this.loading = false
          return
        }
        params = sshKeyPairDetailParams(params, sshTarget)
        this.sshKeyPairDetailStatus = sameList ? 'refreshing' : 'loading'
      }
      if (['listVirtualMachines', 'listVirtualMachinesMetrics'].includes(requestApi)) {
        params = sshKeyPairVmParams(params, this.$route.query)
      }
      const templateQuery = this.$route.meta?.name === 'template'
      if (templateQuery) params = templateRequestParams(params, this.dataView, this.$store.getters)
      const cniQuery = ['cniconfiguration', 'affinitygroup', 'guestnetwork'].includes(this.$route.meta?.name)
      const snapshotDetail = snapshotDetailRoute(this.$route)
      const policyDetail = policyDetailRoute(this.$route)
      if (guestNetworkRoute(this.$route)) { delete params.sortkey; delete params.sortorder }
      if (isoQuery) params = isoRequestParams(params, this.dataView, this.$store.getters)
      if (kubernetesIsoQuery) params = kubernetesIsoReadParams(params, this.$route.params?.id)
      const readCurrentScope = () => version === this.listRequestVersion && scope === this.listScope()
      if (isAclListRoute(this.$route)) {
        delete params.includedefaults
        if (!this.dataView && params.vpcid && aclHasParam(this.$store.getters, requestApi, 'includedefaults')) params.includedefaults = false
      }
      const response = vpnUser
        ? readVpnUsers(getAPI, this.$route.query, this.$route.params?.id)
        : isAclListRoute(this.$route)
          ? readAclList(getAPI, params, aclDisplaySupported(this.$store.getters), this.$route.params.id, readCurrentScope)
          : kmsDetail ? readKmsDetail(getAPI, this.$route, this.$store.getters) : kmsList ? readKmsPage(getAPI, requestApi, params, readCurrentScope) : s2sGatewayRoute(this.$route) && this.dataView ? readGatewayDetail(getAPI, this.$route, this.$store.getters, readCurrentScope, this.$getApiParams('listVpnGateways')) : this.isVpnConnectionDetail || this.isS2sSearchList ? (vpnConnectionRoute(this.$route) ? readVpnConnectionQuery : readS2sGatewayQuery)(getAPI, params, this.$store.getters, readCurrentScope) : asnRoute(this.$route) ? readAsnQuery(getAPI, params, this.$store.getters, () => version === this.listRequestVersion && scope === this.listScope()) : isoQuery && this.dataView ? readIsoResource(getAPI, params, this.$store.getters, () => version === this.listRequestVersion && scope === this.listScope()) : this.publicIpDetail ? readPublicIpDetail(getAPI, params, this.$store.getters, () => version === this.listRequestVersion && scope === this.listScope()) : templateQuery && this.dataView ? readTemplateResource(getAPI, params, this.$store.getters, () => version === this.listRequestVersion && scope === this.listScope()) : policyDetail ? Promise.resolve().then(() => readPolicyDetail(getAPI, this.$route, this.$store.getters.project, () => version === this.listRequestVersion && scope === this.listScope())) : snapshotDetail ? Promise.resolve().then(() => readSnapshotDetail(getAPI, snapshotDetailParams(this.$route, this.$store.getters.project), this.$store.getters, () => version === this.listRequestVersion && scope === this.listScope())) : this.isEventList || publicIpList || isoQuery || kubernetesIsoQuery || vmGroups || sshDetail || templateQuery || cniQuery || ['vm', 'vnfapp'].includes(this.$route.meta?.name) || this.publicIpDetail || this.isSystemOfferingUi || this.isObjectStorageUi || storageUiRoute(this.$route) || snapshotListRoute(this.$route) || snapshotPolicyListRoute(this.$route) ? getAPI(requestApi, params, { preserveOnFailure: true, timeout: 20000, ...(kubernetesIsoQuery ? { readScope: JSON.stringify([this.$store.state?.user?.token, this.$store.getters.userInfo?.id, this.$store.getters.project?.id]) } : {}) }) : callAPI(requestApi, params)
      const request = response.then(async json => {
        if (templateOperationHeld()) return
        if (version !== this.listRequestVersion || scope !== this.listScope()) return
        if (kmsDetail) {
          this.items = [json]
          this.itemCount = 1
          this.resource = json
          this.listLastUpdated = Date.now()
          this.listLoadedScope = scope
          this.listRefreshError = false
          this.$emit('change-resource', this.resource)
          return
        }
        if (kubernetesIsoQuery) {
          const page = kubernetesIsoListResponse(json, this.$store.getters, this.$route.params?.id, { allowEmptyPage: !this.dataView && Number(params.page) > 1 })
          if (!this.dataView && kubernetesIsoFilterMismatch(page.rows, params)) throw new Error('message.kubernetes.iso.read.invalid')
          if (!this.dataView && !page.countKnown && page.rows.length) throw new Error('message.kubernetes.iso.read.invalid')
          if (page.status === 'missing') {
            this.kubernetesIsoReadError = 'missing'; this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null
            this.$emit('change-resource', this.resource)
            return
          }
          json = { listkubernetessupportedversionsresponse: { count: page.count, kubernetessupportedversion: page.rows } }
        }
        if (isoQuery && !this.dataView) {
          const page = isoListResponse(json)
          if (!page.countKnown && page.rows.length) throw new Error('invalid ISO count')
          json = { ...json, listisosresponse: { ...json.listisosresponse, count: page.count, iso: page.rows } }
          if (isoFilterMismatch(page.rows, params)) throw new Error('message.iso.search.ignored')
        }
        if (templateQuery && !this.dataView && templateFilterMismatch(json?.listtemplatesresponse?.template || [], params)) {
          this.templateFilterError = 'message.template.search.ignored'
          this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.listRefreshError = true
          return
        }
        if (publicIpList) readPublicIpPage(json, params, this.$store.getters)
        if (this.publicIpDetail) validatePublicIpDetail(json, this.$route.params.id)
        const networkPage = guestNetworkRoute(this.$route) && !this.dataView ? readNetworkPage(json, 'listNetworks', 'network', params) : null
        const vnfPage = vnfRoute(this.$route) && !this.dataView ? readVnfPage(json, params) : null
        const groupResult = vmGroups ? vmGroupQueryRows(json) : null
        const snapshotPage = snapshotListRoute(this.$route) ? snapshotListPage(json, this.$route, this.page, this.pageSize) : null
        const policyPage = snapshotPolicyListRoute(this.$route) ? policyListPage(json, params) : null
        this.snapshotListError = ''
        if (sshDetail) {
          const result = sshKeyPairDetailResult(json, sshTarget)
          if (result.status !== 'ready') { this.rejectSshKeyPairDetail(result.status); return }
          this.items = result.rows; this.itemCount = 1
          this.resource = result.rows[0]
          this.sshKeyPairDetailStatus = 'ready'
          this.listLastUpdated = Date.now(); this.listLoadedScope = scope; this.listRefreshError = false
          this.$emit('change-resource', this.resource)
          return
        }
        this.listLastUpdated = Date.now()
        this.listLoadedScope = scope
        this.listRefreshError = false
        var responseName
        var objectName
        for (const key in json) {
          if (key.includes('response')) {
            responseName = key
            break
          }
        }
        let apiItemCount = json[responseName]?.count || 0
        for (const key in json[responseName]) {
          if (key === 'count') {
            apiItemCount = json[responseName].count
            continue
          }
          objectName = key
          break
        }

        if (networkPage) apiItemCount = networkPage.count
        if (vnfPage) apiItemCount = vnfPage.count
        if (groupResult) apiItemCount = groupResult.count
        if (snapshotPage) apiItemCount = snapshotPage.count
        if (policyPage) apiItemCount = policyPage.count

        const idFromRouteMatchesApiParameter = this.$route.params.id === params.id ||
          this.apiName === 'quotaSummary' && this.$route.params.id === params.accountid ||
          this.apiName === 'quotaEmailTemplateList' && this.$route.params.id === params.templatetype

        if ('id' in this.$route.params && !idFromRouteMatchesApiParameter) {
          console.log('DEBUG - Discarding API response as its `id` does not match the uuid on the browser path')
          return
        }

        if (!this.dataView && (policyPage || Object.prototype.hasOwnProperty.call(json[responseName], 'count'))) {
          const lastPage = Math.max(1, Math.ceil(apiItemCount / this.pageSize))
          if (this.page > lastPage) {
            this.$router.replace({ query: { ...this.$route.query, page: String(lastPage) } })
            return
          }
        }
        let responseItems = vnfPage ? vnfPage.rows : policyPage ? policyPage.rows : snapshotPage ? snapshotPage.rows : groupResult ? groupResult.rows : json[responseName][objectName]
        if (this.$route.name === 'autoscalevmgroup' && !this.dataView && this.selectedColumns.includes('zonename')) {
          responseItems = await autoScaleInfrastructureRows(getAPI, responseItems || [], 'listNetworks' in this.$store.getters.apis)
          if (version !== this.listRequestVersion || scope !== this.listScope()) return
        }
        if (policyDetail) responseItems = responseItems.map(record => ({ ...record, name: (record.volumename || record.volumeid) + ' · ' + this.$t(['label.hourly', 'label.daily', 'label.weekly', 'label.monthly'][Number(record.intervaltype)] || 'label.snapshot.list.unknown') }))
        if (templateDetail && (!Array.isArray(responseItems) || !responseItems.length || responseItems.some(row => row.id?.toLowerCase() !== params.id?.toLowerCase()))) {
          this.rejectTemplateDetail('message.template.detail.unavailable')
          return
        }
        this.items = responseItems
        if (!this.items || this.items.length === 0) {
          this.items = []
        }
        this.itemCount = apiItemCount

        if (this.dataView && this.$route.path.includes('/zone/') && 'listVmwareDcs' in this.$store.getters.apis) {
          getAPI('listVmwareDcs', { zoneid: this.items[0].id }).then(response => {
            this.items[0].vmwaredc = response.listvmwaredcsresponse.VMwareDC
          })
        }

        if (['listTemplates', 'listIsos'].includes(this.apiName) && this.items.length > 1) {
          this.items = [...new Map(this.items.map(x => [x.id, x])).values()]
        }

        if (this.apiName === 'listProjects' && this.items.length > 0) {
          this.$store.commit('RELOAD_ALL_PROJECTS', this.items)
          this.columns.map(col => {
            if (col.title === 'Account') {
              col.title = this.$t('label.project.owner')
            }
          })
        }

        if (this.apiName === 'listAnnotations') {
          this.columns.map(col => {
            if (col.title === 'label.entityid') {
              col.title = this.$t('label.annotation.entity')
            } else if (col.title === 'label.entitytype') {
              col.title = this.$t('label.annotation.entity.type')
            } else if (col.title === 'label.adminsonly') {
              col.title = this.$t('label.annotation.admins.only')
            }
          })
        }

        for (let idx = 0; idx < this.items.length; idx += 1) {
          this.items[idx].key = idx
          for (const key in customRender) {
            const func = customRender[key]
            if (func && typeof func === 'function') {
              this.items[idx][key] = func(this.items[idx])
            }
          }
        }
        if (vmGroups) this.vmGroupQueryStatus = this.items.length ? 'ready' : (this.dataView ? 'missing' : 'empty')
        if (this.items.length <= 0 && this.dataView && !vmGroups && !this.isVpnConnectionDetail) {
          this.$router.push({ path: '/exception/404' })
        }
        if (!this.showAction || this.dataView || (this.items.length === 1 && this.apiName === 'getUserKeys')) {
          const resource = this.items?.[0] || {}
          if (JSON.stringify(resource) !== JSON.stringify(this.resource)) {
            this.resource = resource
            this.$emit('change-resource', this.resource)
          }
        }
      }).catch(error => {
        if (kubernetesIsoQuery) {
          if (version !== this.listRequestVersion || scope !== this.listScope()) return
          this.kubernetesIsoReadError = kubernetesIsoFailure(error)
          this.listRefreshError = true; this.listLoadedScope = null
          this.items = []; this.resource = {}; this.itemCount = 0; this.resetSelection(); this.$emit('change-resource', this.resource)
          if (isAutoScheduled) throw error
          return
        }
        if (isoQuery && !this.dataView) {
          if (version !== this.listRequestVersion || scope !== this.listScope()) return
          this.isoListError = error?.message === 'message.iso.search.ignored' ? error.message : 'message.iso.search.failed'
          this.listRefreshError = true
          this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.resetSelection()
          return
        }
        if (templateOperationHeld()) return
        if (version !== this.listRequestVersion || scope !== this.listScope()) return
        if (this.isEventList) {
          this.listRefreshError = true; this.listLoadedScope = null
          this.items = []; this.resource = {}; this.itemCount = 0
          this.resetSelection()
          if (this.$refs.listview) this.$refs.listview.resetSelection()
          return
        }
        if (templateDetail) {
          const code = Number(error?.response?.status)
          this.rejectTemplateDetail([403, 404, 431, 432, 531].includes(code) ? 'message.template.detail.unavailable' : 'message.template.detail.read.failed')
          if (isAutoScheduled) throw error
          return
        }
        if (this.publicIpDetail) {
          this.listRefreshError = true
          const code = Number(Object.values(error?.response?.data || {}).find(value => value?.errorcode)?.errorcode || error?.response?.status)
          if (!sameList || [401, 403, 404, 431, 432, 531].includes(code)) {
            this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null
            this.$emit('change-resource', this.resource)
          }
          if (isAutoScheduled) throw error
          return
        }
        if (sshDetail) { this.rejectSshKeyPairDetail(sshKeyPairDetailFailure(error)); return }
        if (cniQuery || ['vm', 'vnfapp'].includes(this.$route.meta?.name) || this.isObjectStorageUi || storageUiRoute(this.$route) || snapshotListRoute(this.$route) || snapshotDetail || policyDetail || snapshotPolicyListRoute(this.$route)) {
          if (snapshotListRoute(this.$route) || snapshotDetail || policyDetail) this.snapshotListError = Object.values(error?.response?.data || {}).find(value => value?.errortext)?.errortext || (error.message?.startsWith('message.') ? error.message : 'message.snapshot.list.read.failed')
          this.listRefreshError = true
          if (cniQuery || vnfRoute(this.$route)) {
            this.resetSelection()
            if (this.$refs.listview) this.$refs.listview.resetSelection()
          }
          const code = Number(Object.values(error?.response?.data || {}).find(value => value?.errorcode)?.errorcode || error?.response?.status)
          if (!sameList || [401, 403, 431, 432, 531].includes(code) || ((snapshotDetail || policyDetail) && code === 404)) {
            this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null
            this.resetSelection()
            if (this.$refs.listview) this.$refs.listview.resetSelection()
          }
          if (isAutoScheduled) throw error
          return
        }
        if (vmGroups) {
          this.vmGroupQueryStatus = vmGroupLookupStatus(error)
          this.listRefreshError = true
          this.resetSelection()
          if (this.$refs.listview) this.$refs.listview.resetSelection()
          if (this.vmGroupQueryStatus === 'forbidden') {
            this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null
          }
          if (isAutoScheduled) throw error
          return
        }
        if (kmsDetail) {
          this.rejectKmsDetail(kmsDetailFailure(error))
          return
        }
        if (kmsList) {
          const denied = [401, 403].includes(Number(error?.response?.status))
          this.kmsSearchError = denied ? 'forbidden' : error?.message === 'profile' ? 'profile' : 'failed'
          this.listRefreshError = true
          this.items = []
          this.itemCount = 0
          this.listLoadedScope = null
          return
        }
        if (s2sGatewayRoute(this.$route) || vpnConnectionRoute(this.$route)) {
          const denied = [401, 403].includes(Number(error?.response?.status))
          const reason = denied ? 'permission' : ['permission', 'scope', 'missing', 'uuid', 'unsupported', 'invalid', 'account'].includes(error?.message) ? error.message : 'lookup'
          this.s2sGatewaySearchFailure = reason; this.listRefreshError = true; this.resetSelection(); this.$refs.listview && this.$refs.listview.resetSelection()
          if (!sameList || ['permission', 'scope', 'missing', 'invalid'].includes(reason)) { this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null }
          return
        }
        if (asnRoute(this.$route)) {
          this.asnSearchFailure = ['permission', 'project', 'zone', 'domain', 'integer', 'invalid', 'missing', 'scope', 'combination'].includes(error?.message) ? error.message : 'lookup'
          this.listRefreshError = true; this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection()
          if (!sameList) { this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null }
          return
        }
        if (publicIpList) { if ([401, 403].includes(Number(error?.response?.status))) { this.items = []; this.resource = {}; this.itemCount = 0; this.listLoadedScope = null; this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection() } this.listRefreshError = true; this.publicIpSearchFailure = 'message.public.ip.search.failed'; if (!isAutoScheduled) this.$notifyError(error); return }
        if (vpnUser) {
          this.invalidateVpnUserRead()
          this.listRefreshError = true
          return
        }
        if (this.isSystemOfferingUi || this.isAclUi) { this.listRefreshError = true; return }
        if (sameList || this.$route.name === 'vmsnapshot') {
          this.listRefreshError = true
          if (isAutoScheduled) throw error
          this.$notifyError(error)
          return
        }
        if (!error || !error.message) {
          console.log('API request likely got cancelled due to route change:', this.apiName)
          return
        }
        if ([401].includes(error.response?.status)) {
          return
        }

        if (Object.keys(this.searchParams).length > 0) {
          this.itemCount = 0
          this.items = []
          this.$message.error({
            content: error.response.headers['x-description'],
            duration: 5
          })
          return
        }

        this.$notifyError(error)

        if ([405].includes(error.response?.status)) {
          this.$router.push({ path: '/dashboard' })
        }
        if ([430, 431, 432].includes(error.response?.status)) {
          this.$router.push({ path: '/dashboard' })
        }
        if ([530, 531, 532, 533, 534, 535, 536, 537].includes(error.response?.status)) {
          this.$router.push({ path: '/dashboard' })
        }
      }).finally(f => {
        if (version !== this.listRequestVersion || scope !== this.listScope()) return
        this.listRequestPending = false
        this.loading = false
        if (asnRoute(this.$route) && !this.listRefreshError && !this.asnSearchFailure) this.onRowSelectionChange(this.selectedRowKeys)
        if (this.publicIpDetail) this.$emit('public-ip-read-state', { pending: false, failed: this.listRefreshError })
        this.searchParams = params
        if (this.listRefreshQueued) {
          this.listRefreshQueued = false
          this.fetchData({ irefresh: true })
        }
      })
      this.listRequestPromise = request

      // [CHANGE] 라우터 쿼리 action 루프 안전화
      if (!kmsDetail && 'action' in this.$route.query) {
        const actionName = this.$route.query.action
        for (const action of asArray(this.actions)) {
          if (action.listView && action.api === actionName) {
            this.execAction(action, false)
            const query = Object.assign({}, this.$route.query)
            delete query.action
            this.$router.replace({ query })
            break
          }
        }
      }
      return request
    },
    refreshActionData (result) {
      if (s2sGatewayRoute(this.$route)) {
        if (result?.displayChanged) return
        if (this.currentAction?.api === 'deleteVpnGateway' && this.dataView && result?.removed?.includes(this.resource.id)) return
        return this.fetchData({ irefresh: true })
      }
      if (this.currentAction?.networkDeletionTargets) {
        if (this.dataView && result?.removed?.includes(this.$route.params?.id)) return
        return this.fetchData({ irefresh: true })
      }
      // A confirmed policy deletion must close before navigation. Refetching its
      // detail here clears the dialog scope and loses the after-close result.
      if (this.currentAction?.policyDeleteDialog) {
        if (policyDetailRoute(this.$route) && result?.removed?.includes(this.$route.params?.id)) return
        return this.fetchData()
      }
      return this.fetchData(result)
    },
    finishVpnUserDeletion (refresh, complete = false) {
      if (!this.currentAction.vpnUserDeleteCurrent?.()) return
      const deletedDetail = complete && this.dataView
      this.closeAction()
      if (!refresh) return
      this.resetSelection()
      if (this.$refs.listview) this.$refs.listview.resetSelection()
      if (deletedDetail) return this.$router.replace(vpnUserListLocation(this.$route, this.$store.getters))
      return this.fetchData({ irefresh: true })
    },
    finishEventMutation (success) {
      const deletedDetail = success && this.dataView && this.currentAction.api === 'deleteEvents'
      const location = deletedDetail && eventListLocation(this.$route, this.$router.options.history.state, this.$store.getters, this.$store.state?.user?.token, this.resource)
      this.closeAction()
      this.resetSelection()
      if (this.$refs.listview) this.$refs.listview.resetSelection()
      if (location) return this.$router.replace(location)
      return this.fetchData({ irefresh: true })
    },
    closeAction (result) {
      const refreshAccountList = this.currentAction?.accountTargets && result?.refreshAccountList && this.$route.meta?.name === 'account'
      const refreshProjectBulk = this.currentAction?.projectBulkTargets && result?.refreshProjectList && this.isProjectList
      const gatewayDeletion = this.vpnCustomerGatewayActionScope && this.currentAction?.vpnCustomerGatewayScope === this.vpnCustomerGatewayActionScope && result?.gatewayDeletion
      const deletedCustomerGateway = gatewayDeletion && this.dataView && result.removed.includes(this.$route.params.id)
      if (result?.kmsRemoved && !kmsDeletionConfirmed(this.$route, this.currentAction, result, this.kmsNavigationScope())) return
      const keyResourceList = result?.kmsRemoved ? this.currentKmsListLocation() : null
      if (keyResourceList) {
        this.invalidateKmsResults()
        this.$emit('change-resource', this.resource)
      }
      const changedGatewayDisplay = s2sGatewayRoute(this.$route) && ['updateVpnGateway', 'createVpnGateway'].includes(this.currentAction?.api) && result?.displayChanged && result.originPath === this.$route.fullPath && result.auth === this.s2sGatewaySearchAuth
      const displayLocation = changedGatewayDisplay ? gatewayDisplayListLocation(this.$route, this.$store.getters, s2sGatewayListLocation(this.$route, this.$store.getters), result.fordisplay, result.id, result.target) : null
      const deletedGatewayDetail = s2sGatewayRoute(this.$route) && this.currentAction?.api === 'deleteVpnGateway' && this.dataView && result?.removed?.includes(this.$route.params?.id)
      const gatewayLocation = deletedGatewayDetail ? gatewayDeletedListLocation(s2sGatewayListLocation(this.$route, this.$store.getters), this.$route.params.id) : null
      const deletedVpnConnectionDetail = this.$route.meta?.name === 's2svpnconn' && this.currentAction?.api === 'deleteVpnConnection' &&
        this.dataView && result?.removed?.includes(this.$route.params?.id)
      const vpnConnectionLocation = deletedVpnConnectionDetail
        ? gatewayDeletedListLocation(s2sGatewayListLocation(this.$route, this.$store.getters, vpnConnectionApi), this.$route.params.id)
        : null
      if (vpnConnectionLocation) vpnConnectionLocation.query.page = '1'
      if (deletedGatewayDetail || deletedVpnConnectionDetail) {
        this.listRequestVersion++
        this.listRequestPending = false
        this.listLoadedScope = null
        this.resource = {}
        this.items = []
        this.itemCount = 0
        this.resetSelection()
      }
      const deletedKubernetesIso = this.$route.meta?.name === 'kubernetesiso' && this.currentAction?.api === 'deleteKubernetesSupportedVersion' && result?.removed?.includes(this.resource?.id)
      const kubernetesIsoLocation = deletedKubernetesIso ? kubernetesIsoListLocation(this.$route, this.$store.getters, this.$store.state?.user?.token) : null
      if (deletedKubernetesIso) {
        this.listRequestVersion++; this.listRequestPending = false; this.listLoadedScope = null
        this.resource = {}; this.items = []; this.itemCount = 0; this.resetSelection()
      }
      const networkDeletion = this.currentAction?.networkDeletionTargets && this.currentAction.networkDeletionScope === this.listScope()
      const deletedNetworkDetail = networkDeletion && this.dataView && result?.removed?.includes(this.$route.params?.id)
      const networkLocation = this.currentAction?.networkDeletionLocation
      if (networkDeletion && result?.started) {
        const removed = new Set(result.removed || [])
        this.items = this.items.filter(row => !removed.has(row.id))
        this.selectedItems = this.items.filter(row => result.retained?.includes(row.id))
        this.selectedRowKeys = this.selectedItems.map(row => row.id)
        this.listRequestVersion++; this.listRequestPending = false
      }
      const snapshotDialog = this.currentAction?.storageSnapshotMode || this.currentAction?.snapshotStandardDialog
      const vpnUserDelete = !!this.currentAction?.vpnUserDeleteTargets
      const kmsDialog = kmsRoute(this.$route) && this.currentAction?.selfManagedDialog
      const systemOfferingDialog = this.isSystemOfferingUi && (this.standardActionDialog || this.currentAction.systemOfferingState)
      const guardedDialog = systemOfferingDialog || this.isZoneUi || this.isPhysicalNetworkUi || !!this.currentAction?.vpnCustomerGatewayScope || ['downloadEvents', 'addVpnUser'].includes(this.currentAction?.api) || !!this.currentAction?.eventMutationTargets || vpnUserDelete || kmsDialog || this.isProjectUi || ['kubernetesiso', 's2svpn', 's2svpnconn'].includes(this.$route.meta?.name) || this.vmStandardDialog || this.roleStandardDialog || this.accountStandardDialog || this.aclStandardDialog || this.publicIpStandardDialog || snapshotDialog || !!this.currentAction?.vnfReinstallCurrent || !!this.currentAction?.vnfUnmanageCurrent
      const policyDeleteDialog = this.currentAction?.policyDeleteDialog
      const deletedPolicyDetail = policyDeleteDialog && policyDetailRoute(this.$route) && this.currentAction.policyListScope === this.listScope() && result?.removed?.includes(this.$route.params?.id)
      let policyListLocation
      if (deletedPolicyDetail) {
        const query = snapshotPolicyListQuery(policySearchQuery(this.$route.query, policySearchForm(this.$route.query), this.$route.query.pagesize || this.pageSize || 20))
        if (query.id === this.$route.params.id) delete query.id
        policyListLocation = { path: '/snapshotpolicy', query }
        this.listRequestVersion++; this.listRequestPending = false; this.listLoadedScope = null
        this.resource = {}; this.items = []; this.itemCount = 0; this.snapshotListError = ''; this.listRefreshError = false
      }
      if (policyDeleteDialog && result?.clearSelection && this.currentAction.policyListScope === this.listScope()) {
        this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection()
      }
      const cniAction = this.currentAction?.api === 'deleteCniConfiguration' && this.currentAction.cniListScope === this.listScope()
      const cniLocation = this.currentAction?.cniListLocation
      const deletedCniDetail = cniAction && this.dataView && result?.removed?.includes(this.resource?.id)
      if (cniAction && result?.removed?.length) {
        const removed = new Set(result.removed)
        this.items = this.items.filter(item => !removed.has(item.id))
        this.selectedItems = this.items.filter(item => (result.retained || []).includes(item.id))
        this.selectedRowKeys = this.selectedItems.map(item => item.id)
        // Invalidate old detail/list reads before routing; stale responses cannot revive deleted actions.
        this.listRequestVersion++; this.listRequestPending = false; this.listLoadedScope = null
        if (deletedCniDetail) { this.resource = {}; this.items = []; this.itemCount = 0 }
      }
      const subnetDeletion = this.currentAction.api === 'deleteIpv4SubnetForGuestNetwork' && result?.ipv4SubnetDeletion
      const deletedSubnetDetail = subnetDeletion && this.dataView && result.removed.includes(this.$route.params.id)
      const deletedSshDetail = this.currentAction?.api === 'deleteSSHKeyPair' && isSshKeyPairDetailRoute(this.$route) && result?.removed?.includes(this.resource?.id)
      if (this.currentAction?.api === 'deleteSSHKeyPair' && result?.clearSelection) { this.selectedRowKeys = []; this.selectedItems = [] }
      const deletedUserDataDetail = this.currentAction?.api === 'deleteUserData' && this.dataView && result?.removed?.includes(this.resource?.id)
      if (this.currentAction?.api === 'deleteUserData' && result?.started && this.currentAction.userDataListScope === this.listScope()) {
        const removed = new Set(result.removed || [])
        const retained = new Set(result.retained || [])
        this.items = this.items.filter(item => !removed.has(item.id))
        this.selectedItems = this.items.filter(item => retained.has(item.id))
        this.selectedRowKeys = this.selectedItems.map(item => item.id)
      }
      const trigger = this.actionTrigger
      const triggerLabel = this.actionTriggerLabel
      const originPath = this.actionOriginPath
      this.actionTrigger = null
      this.actionLoading = false
      this.showAction = false
      this.currentAction = {}
      this.actionConfirmText = ''
      if (refreshAccountList) {
        this.resetSelection()
        if (this.$refs.listview) this.$refs.listview.resetSelection()
        this.fetchData({ irefresh: true })
      }
      if (refreshProjectBulk) {
        this.resetSelection()
        if (this.$refs.listview) this.$refs.listview.resetSelection()
        this.fetchData({ irefresh: true })
      }
      if (displayLocation) {
        this.$message.success(this.$t(result.fordisplay ? 'message.s2s.display.shown' : 'message.s2s.display.hidden'))
        const query = this.$route.query || {}; const next = displayLocation.query || {}
        const sameList = this.$route.path === displayLocation.path && Object.keys(query).length === Object.keys(next).length && Object.keys(next).every(key => String(query[key]) === String(next[key]))
        if (sameList) {
          this.$nextTick(() => { if (this.$route.fullPath === result.originPath && result.auth === this.s2sGatewaySearchAuth) this.fetchData({ irefresh: true }) })
        } else this.$router.replace(displayLocation)
      }
      if (deletedSubnetDetail) this.$router.replace({ name: 'ipv4subnets', query: ipv4SubnetListQuery(this.$route.query) })
      else if (subnetDeletion) {
        this.resetSelection()
        if (this.$refs.listview) this.$refs.listview.resetSelection()
        this.fetchData({ irefresh: true })
      }
      if (keyResourceList) this.$router.replace(keyResourceList)
      if (deletedGatewayDetail) this.$router.replace(gatewayLocation)
      if (vpnConnectionLocation) this.$router.replace(vpnConnectionLocation)
      if (deletedNetworkDetail) this.$router.replace(networkLocation || { path: '/guestnetwork' })
      else if (networkDeletion && result?.started) this.$nextTick(() => this.fetchData({ irefresh: true }))
      if (deletedCniDetail) this.$router.replace(cniLocation || { path: '/cniconfiguration' })
      if (deletedPolicyDetail) this.$router.replace(policyListLocation)
      if (deletedSshDetail) this.$router.push('/ssh')
      if (deletedUserDataDetail) this.$router.push('/userdata')
      if (deletedKubernetesIso) this.$router.replace(kubernetesIsoLocation)
      // Clearing currentAction unmounts the legacy modal before AntD can restore focus.
      const generation = this.storageActionGeneration
      const scope = this.listScope?.()
      const restoreFocus = () => {
        if ((guardedDialog || this.isVpcUi) && (generation !== this.storageActionGeneration || scope !== this.listScope?.() || originPath !== this.$route?.path)) return
        let replacement = (this.isVpcUi || this.isStorageUi || guardedDialog) && originPath === this.$route?.path
          ? (this.$refs?.detailActionsTrigger?.$el || (triggerLabel && Array.from(this.$el?.querySelectorAll('.autogen-toolbar-actions button') || []).find(button => button.getAttribute('aria-label') === triggerLabel)))
          : null
        if (kmsDialog && !replacement && !trigger?.isConnected) replacement = this.$el?.querySelector('.autogen-toolbar-actions button:not([disabled])')
        if ((vpnUserDelete || systemOfferingDialog && this.$route.path === '/systemoffering') && !replacement &&
          (!trigger?.isConnected || !trigger.getClientRects().length || trigger === document.body || trigger.disabled)) {
          replacement = this.$el?.querySelector('.ant-table-row-selected input[type="checkbox"]:not([disabled])') ||
            this.$el?.querySelector('.list-view-table-wrapper[tabindex="0"]')
        }
        if (policyDeleteDialog && !replacement && originPath === this.$route?.path) replacement = this.$el?.querySelector('.autogen-toolbar-actions button:not([disabled])')
        if (this.isVpcUi && !replacement && originPath === this.$route?.path) replacement = this.$el?.querySelector('.ant-table-row-selected input[type="checkbox"]:not([disabled])') || this.$el?.querySelector('.autogen-toolbar-actions button:not([disabled])')
        // A portal menu item may remain connected but hidden after its dropdown closes.
        // Snapshot dialogs return to the stable toolbar/detail button in the current route.
        const preferTrigger = this.isProjectUi && trigger?.isConnected
        const target = !preferTrigger && (this.isVpcUi || guardedDialog) && replacement?.isConnected ? replacement : trigger?.isConnected ? trigger : replacement
        if (!this.showAction && target?.isConnected && target !== document.body) {
          target.focus({ preventScroll: true })
        }
      }
      this.actionFocusReturn = (guardedDialog || this.isVpcUi) ? { restore: restoreFocus } : null
      this.$nextTick(restoreFocus)
      if (gatewayDeletion) {
        this.resetSelection()
        if (this.$refs.listview) this.$refs.listview.resetSelection()
        if (deletedCustomerGateway) {
          const { tab, ...query } = this.$route.query
          return this.$router.replace({ path: '/vpncustomergateway', query })
        }
        return this.fetchData({ irefresh: true })
      }
    },
    restoreClosedActionFocus () {
      const pending = this.actionFocusReturn
      if (!pending || this.showAction) return
      this.$nextTick(() => {
        // Never steal focus from another control, a new dialog, or a different scope.
        if (this.actionFocusReturn === pending && !this.showAction && document.activeElement === document.body) pending.restore()
      })
    },
    cancelAction () {
      if (this.vmStandardDialog && (this.actionLoading || this.vmActionValidationPending)) return
      if ((this.isVpcUi || this.aclStandardDialog || this.projectStandardDialog || this.roleStandardDialog) && this.actionLoading) return
      eventBus.emit('action-closing', { action: this.currentAction })
      this.closeAction()
    },
    removeFilter (filter) {
      if (this.isVpnUser) {
        const query = { ...this.$route.query, page: '1' }
        delete query[filter.key]
        if (filter.key === 'domainid') delete query.account
        return this.$router.push({ query: vpnUserListQuery(query, this.pageSize, this.$store.getters) })
      }
      if (kmsListRoute(this.$route)) {
        const raw = { ...this.$route.query, page: '1' }
        delete raw[filter.key]
        const query = kmsQuery(raw, kmsListApi(this.$route), this.$store.getters, this.pageSize)
        this.$router.push({ query })
        return
      }
      if (this.isIsoList && filter.key === 'isofilter') { this.changeFilter(['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo?.roletype) ? 'all' : 'self'); return }
      const queryParams = Object.assign({}, this.$route.query)
      if (filter.isTag) {
        delete queryParams[`tags[${filter.tagIdx}].key`]
        delete queryParams[`tags[${filter.tagIdx}].value`]
      } else {
        delete queryParams[filter.key]
        if ((this.isIsoList || this.isTemplateList) && filter.key === 'domainid') delete queryParams.account
        if (publicIpRoute(this.$route)) {
          const children = { zoneid: ['vpcid', 'associatednetworkid', 'networkid', 'physicalnetworkid', 'vlanid'], domainid: ['account', 'vpcid', 'associatednetworkid', 'vlanid'], account: ['vpcid', 'associatednetworkid', 'vlanid'], vpcid: ['associatednetworkid'], networkid: ['vlanid'], physicalnetworkid: ['vlanid'] }
          for (const key of children[filter.key] || []) delete queryParams[key]
          if (filter.key === 'state') queryParams.filter = publicIpDefaultFilter(this.$store.getters)
        }
        if (this.$route.name === 'snapshot') {
          if (filter.key === 'showunique') delete queryParams.locationtype
          if (filter.key === 'snapshottype') delete queryParams.intervaltype
          if (filter.key === 'domainid') delete queryParams.account
        }
        if (ipv4SubnetRoute(this.$route) && filter.key === 'zoneid') {
          delete queryParams.parentid
          delete queryParams.networkid
          delete queryParams.vpcid
        }
        if (guestNetworkRoute(this.$route) && filter.key === 'domainid') delete queryParams.account
        if ((guestNetworkRoute(this.$route) || guestVlanRoute(this.$route)) && filter.key === 'zoneid') delete queryParams.physicalnetworkid
        if (vnfRoute(this.$route) && filter.key === 'domainid') { delete queryParams.account; delete queryParams.networkid; delete queryParams.templateid; delete queryParams.serviceofferingid; delete queryParams.groupid }
        if (vnfRoute(this.$route) && filter.key === 'zoneid') { delete queryParams.networkid; delete queryParams.templateid; delete queryParams.serviceofferingid }
        if (vnfRoute(this.$route) && filter.key === 'account') { delete queryParams.networkid; delete queryParams.templateid; delete queryParams.serviceofferingid; delete queryParams.groupid }
        if (this.$route.name === 'vmsnapshot' && filter.key === 'state') delete queryParams.filter
        if (snapshotPolicyListRoute(this.$route) && filter.key === 'domainid') delete queryParams.account
      }
      queryParams.page = '1'
      queryParams.pagesize = String(this.pageSize)
      if (this.isProjectList && filter.key === 'account') delete queryParams.username
      this.$router.push({ query: this.isProjectList ? projectSearchQuery(queryParams, this.$store.getters) : queryParams })
    },
    onRowSelectionChange (selection) {
      if (this.isProjectList && !this.storageActionReady && selection?.length) return
      if (asnRoute(this.$route)) {
        if (this.listRequestPending && this.listLoadedScope !== this.listScope()) return
        if (this.listRefreshError || this.asnSearchFailure) { this.resetSelection(); return }
        selection = [...new Set(selection || [])].filter(id => this.items.some(row => listRowKey(row) === id))
      }
      if (vnfRoute(this.$route)) {
        if (!this.storageActionReady) { this.resetSelection(); return }
        selection = [...new Set(selection || [])].filter(id => this.items.some(row => row.id === id))
      }
      // List refreshes must not replace the snapshot owned by active jobs.
      if (this.showGroupActionModal) return
      this.selectedRowKeys = selection
      if (selection?.length > 0) {
        this.modalWidth = '50vw'
        this.selectedItems = (this.items.filter(function (item) {
          return selection.includes(listRowKey(item))
        }))
      } else {
        this.modalWidth = '30vw'
        this.selectedItems = []
      }
      // this.modalWidth = '45vw'

      this.setModalWidthByScreen()
    },
    handleDataViewAction (action) {
      this.detailActionsVisible = false
      this.execAction(action, false)
      if (this.actionTrigger?.closest('.autogen-action-dropdown')) {
        this.actionTrigger = this.$refs?.detailActionsTrigger?.$el || this.actionTrigger
      }
    },
    canOpenPolicyCreate (action, isGroupAction) {
      // Creating starts an independent volume read; it does not act on stale list rows.
      return snapshotPolicyListRoute(this.$route) && !isGroupAction && !action.dataView && action.listView &&
        action.api === 'createSnapshotPolicy' && action.popup && action.snapshotStandardDialog &&
        !this.policySearchFailure && !this.listRefreshError && this.listLoadedScope === this.listScope() &&
        ['listSnapshotPolicies', 'listVolumes', 'createSnapshotPolicy'].every(api => api in (this.$store.getters.apis || {}))
    },
    kmsNavigationScope () { return JSON.stringify([this.$route.fullPath, this.kmsSearchAuth]) },
    execAction (action, isGroupAction) {
      if (this.$route.meta?.name === 'role' && !canRoleAction(action.api, action.resource || this.resource, this.$store.getters.apis)) return
      if (this.isKmsDetail && !this.kmsDetailReady) return
      if (availabilityActions.includes(action.api)) {
        const getters = this.$store.getters
        const reason = actionAvailability(action.api, { resource: action.resource || this.resource, scope: { role: getters.userInfo?.roletype }, apis: getters.apis })
        if (reason) {
          if (this.$message) this.$message.info(this.$t(kmsAvailabilityLabel(reason)))
          return
        }
      }
      if (this.$route.meta?.name === 'kubernetesiso') {
        const operation = Object.keys(kubernetesIsoApis).find(key => kubernetesIsoApis[key] === action.api)
        if (!canKubernetesIsoAction(operation, action.resource || this.resource, this.$store.getters) || this.kubernetesIsoReadError || this.listRequestPending || this.listLoadedScope !== this.listScope()) return
      }
      this.vpcActionError = ''
      this.projectActionError = ''
      this.roleActionError = ''
      if (vnfRoute(this.$route) && (!this.storageActionReady || !(action.api in (this.$store.getters.apis || {})) || isGroupAction && (!this.selectedRowKeys.length || this.selectedRowKeys.some(id => !this.items.some(row => row.id === id))))) return
      if ((snapshotListRoute(this.$route) || snapshotDetailRoute(this.$route) || policyDetailRoute(this.$route) || snapshotPolicyListRoute(this.$route)) && this.storageActionReady === false && !this.canOpenPolicyCreate(action, isGroupAction)) return
      if (storageUiRoute(this.$route) && (action.dataView || isGroupAction) && !this.storageActionReady) return
      this.vmActionValidationPending = false; this.vmActionError = ''; this.vmActionUnknown = false
      this.storageActionGeneration++
      this.actionFocusReturn = null
      if (action.storageSnapshotMode) {
        this.actionTrigger = this.$refs?.detailActionsTrigger?.$el || document.activeElement
        this.actionTriggerLabel = this.actionTrigger?.getAttribute('aria-label')
        this.actionOriginPath = this.$route?.path
        this.currentAction = { ...action, invokedAsGroupAction: !!isGroupAction, storageSnapshotTargets: Object.freeze((isGroupAction ? this.selectedItems : [action.resource || this.resource]).filter(Boolean).map(row => Object.freeze({ ...row }))) }
        this.resource = action.resource || {}; this.showAction = true
        return
      }
      if (storageUiRoute(this.$route) && (action.dataView || isGroupAction) && !this.storageActionReady) return
      if (isSshKeyPairDetailRoute(this.$route) && !this.canExecuteSshKeyPairAction(action)) return
      this.actionTrigger = document.activeElement
      this.actionTriggerLabel = this.actionTrigger?.getAttribute('aria-label')
      this.actionOriginPath = this.$route?.path
      if (action.systemOfferingState) {
        const targets = isGroupAction ? this.selectedItems : [action.resource || this.resource]
        if (!targets.length || targets.some(record => !record?.id)) return
        const scope = this.listScope()
        const generation = this.storageActionGeneration
        this.currentAction = {
          ...action,
          selfManagedDialog: true,
          component: markRaw(defineAsyncComponent(() => import('@/views/offering/SystemOfferingStateDialog.vue'))),
          systemOfferingTargets: targets.map(({ id, name }) => ({ id, name })),
          systemOfferingCurrent: () => this.showAction && generation === this.storageActionGeneration && scope === this.listScope()
        }
        this.showAction = true
        return
      }
      if (action.accountState) {
        const scope = this.listScope()
        const generation = this.storageActionGeneration
        const records = isGroupAction ? this.selectedItems : [action.resource || this.resource]
        if (!records.some(record => accountTransitionAllowed(action, record, this.$store.getters))) return
        const selection = JSON.stringify(this.selectedRowKeys)
        this.currentAction = {
          ...action,
          selfManagedDialog: true,
          component: markRaw(defineAsyncComponent(() => import('@/views/iam/AccountStateDialog.vue'))),
          accountTargets: records.map(record => ({ ...record })),
          accountCurrent: () => this.showAction && generation === this.storageActionGeneration &&
            scope === this.listScope() && selection === JSON.stringify(this.selectedRowKeys)
        }
        this.showAction = true
        return
      }
      if (isGroupAction && action.projectBulkAction) {
        if (!this.isProjectList || !this.storageActionReady) return
        const targets = projectBulkTargets(action.api, this.selectedRowKeys, this.items, this.$store.getters)
        if (!targets.length) return
        const context = this.projectBulkContext
        this.currentAction = {
          ...action,
          selfManagedDialog: true,
          component: markRaw(defineAsyncComponent(() => import('@/views/project/ProjectBulkActionDialog.vue'))),
          projectBulkTargets: Object.freeze(targets),
          projectBulkCurrent: () => this.showAction && context === this.projectBulkContext
        }
        this.showAction = true
        return
      }
      if (action.api === 'removeVpnUser') {
        const scope = this.vpnUserDeleteScope
        if (!scope || !canDeleteVpnUsers(this.$store.getters)) return
        const ids = isGroupAction ? this.selectedRowKeys : [this.dataView ? this.$route.params?.id : action.resource?.id]
        const targets = this.storageActionReady && vpnUserDeleteTargets(ids, this.dataView ? [this.resource] : this.items)
        if (!targets?.length) {
          this.$message.warning(this.$t('message.vpn.user.delete.target.changed'))
          return
        }
        const generation = this.storageActionGeneration
        this.currentAction = {
          ...action,
          vpnUserDeleteTargets: targets,
          vpnUserDeleteCurrent: () => this.showAction && this.storageActionGeneration === generation && scope === this.vpnUserDeleteScope
        }
        this.showAction = true
        return
      }
      if (action.api === 'deleteIpv4SubnetForGuestNetwork') {
        const record = this.dataView ? this.resource : action.resource
        const candidates = isGroupAction ? this.items.filter(row => this.selectedRowKeys.includes(row.id)) : [record]
        const targets = candidates.filter(canDeleteIpv4Subnet).map(({ id, subnet }) => ({ id, subnet }))
        if (!targets.length || !this.$store.getters.apis.queryAsyncJobResult) return
        const generation = this.storageActionGeneration
        this.currentAction = {
          ...action,
          ipv4SubnetDeleteTargets: targets,
          ipv4SubnetDeleteExcluded: candidates.length - targets.length,
          ipv4SubnetDeleteCurrent: () => this.showAction && generation === this.storageActionGeneration
        }
        this.showAction = true
        return
      }
      if (isEventMutation(action.api)) {
        if (!this.eventActionScope || !this.storageActionReady) return
        const rows = isGroupAction ? this.selectedItems : [action.resource || this.resource]
        if (!canMutateEvents(action.api, rows, this.$store.getters)) return
        if (isGroupAction && (rows.length !== this.selectedRowKeys.length ||
          rows.some(row => !this.selectedRowKeys.includes(row.id)))) return
        const targets = rows.map(({ id, type }) => Object.freeze({ id, type }))
        this.currentAction = { ...action, eventMutationTargets: Object.freeze(targets) }
        this.showAction = true
        return
      }
      if (action.api === 'downloadEvents') {
        const api = this.$store.getters.apis.downloadEvents
        if (!api?.response?.some(field => field.name === 'csv')) return
        const rows = isGroupAction ? this.selectedItems : [action.resource || this.resource]
        const ids = rows.map(row => row?.id).filter(Boolean)
        if (!ids.length || (isGroupAction && ids.length !== this.selectedRowKeys.length)) return
        this.currentAction = { ...action, eventCsvIds: Object.freeze(ids) }
        this.showAction = true
        return
      }
      if (isVmIdentityRoute(this.$route) && (action.resource || this.resource)?.templatetype === 'VNF' && action.api === 'cloneVirtualMachine') return
      if (isVmIdentityRoute(this.$route) && action.api === 'unmanageVirtualMachine' && (action.resource || this.resource)?.templatetype === 'VNF') {
        if (!this.storageActionReady || !this.$store.getters.apis.unmanageVirtualMachine) return
        const generation = this.storageActionGeneration
        this.currentAction = { ...action, component: markRaw(defineAsyncComponent(() => import('@/views/compute/VnfUnmanage.vue'))), popup: true, selfManagedDialog: true, invokedAsGroupAction: false, vnfUnmanageCurrent: () => this.showAction && generation === this.storageActionGeneration }
        this.resource = action.resource || this.resource; this.showAction = true
        return
      }
      if (isVmIdentityRoute(this.$route) && action.api === 'restoreVirtualMachine' && (action.resource || this.resource)?.templatetype === 'VNF') {
        const target = action.resource || this.resource
        const generation = this.storageActionGeneration
        this.currentAction = { ...action, component: markRaw(defineAsyncComponent(() => import('@/views/compute/VnfReinstall.vue'))), popup: true, standardDialogSize: 'lg', invokedAsGroupAction: false, vnfReinstallCurrent: () => this.showAction && generation === this.storageActionGeneration }
        this.resource = target; this.showAction = true
        return
      }
      if (this.$route.meta?.name === 'publicip' && action.api === 'updateIpAddress') {
        const target = action.resource || this.resource
        if (isGroupAction || !displayEditable(target, this.$store.getters)) return
        const generation = this.storageActionGeneration
        this.currentAction = { ...action, standardDialogSize: 'md', invokedAsGroupAction: false, displayCurrent: () => this.showAction && generation === this.storageActionGeneration }
        this.resource = target; this.showAction = true
        return
      }
      if (this.$route.meta?.name === 'publicip' && action.api === 'reserveIpAddress') {
        const target = action.resource || this.resource
        if (isGroupAction || target?.state !== 'Free' || !this.$store.getters.apis.reserveIpAddress) return
        const generation = this.storageActionGeneration
        this.currentAction = { ...action, standardDialogSize: 'md', invokedAsGroupAction: false, reservationCurrent: () => this.showAction && generation === this.storageActionGeneration }
        this.resource = target; this.showAction = true
        return
      }
      if (isVmIdentityRoute(this.$route) && action.api === 'updateVirtualMachine') {
        const target = action.resource || this.resource
        if (!target?.id) return
        const generation = this.storageActionGeneration
        this.currentAction = { ...action, standardDialogSize: 'md', invokedAsGroupAction: false, vmEditCurrent: () => this.showAction && generation === this.storageActionGeneration }
        this.resource = target; this.showAction = true
        return
      }
      if (isVmDeletion(action)) {
        const targets = (isGroupAction ? this.selectedItems : [action.resource || this.resource]).filter(row => row?.id)
        if (!targets.length) return
        const generation = this.storageActionGeneration
        this.currentAction = { ...action, invokedAsGroupAction: !!isGroupAction, vmDeletionCurrent: () => this.showAction && generation === this.storageActionGeneration, vmDeletionTargets: Object.freeze(targets.map(row => Object.freeze({ ...row }))) }
        this.resource = targets[0]; this.showAction = true
        return
      }
      if (isVmIdentityRoute(this.$route) && action.api === 'startVirtualMachine' && !isGroupAction) {
        const generation = this.storageActionGeneration
        this.currentAction = { ...action, standardDialogSize: 'md', invokedAsGroupAction: false, vmStartCurrent: () => this.showAction && generation === this.storageActionGeneration }
        this.resource = action.resource || this.resource; this.showAction = true
        return
      }
      if (action.api === 'deleteNetwork' && guestNetworkRoute(this.$route)) {
        const targets = isGroupAction ? this.selectedItems : [action.resource || this.resource]
        if (!targets.length || !['deleteNetwork', 'listNetworks'].every(api => api in this.$store.getters.apis)) return
        this.currentAction = { ...action, invokedAsGroupAction: !!isGroupAction, networkDeletionScope: this.listScope(), networkDeletionLocation: networkListLocation(this.$route), networkDeletionTargets: Object.freeze(targets.filter(Boolean).map(row => Object.freeze({ ...row }))) }
        this.resource = targets[0]; this.showAction = true
        return
      }
      if (action.api === 'restartNetwork') {
        const targets = isGroupAction ? this.selectedItems : [action.resource || this.resource]
        if (!targets.length || !['restartNetwork', 'listNetworks'].every(api => api in this.$store.getters.apis)) return
        this.currentAction = { ...action, invokedAsGroupAction: !!isGroupAction, networkRestartTargets: Object.freeze(targets.filter(Boolean).map(row => Object.freeze({ ...row }))) }
        this.resource = targets[0]
        this.showAction = true
        return
      }
      if (action.api === 'restartVPC') {
        const targets = isGroupAction ? this.selectedItems : [action.resource || this.resource]
        if (!targets.length || !['restartVPC', 'listVPCs'].every(api => api in this.$store.getters.apis)) return
        this.currentAction = { ...action, invokedAsGroupAction: !!isGroupAction, vpcRestartTargets: Object.freeze(targets.filter(Boolean).map(row => Object.freeze({ ...row }))) }
        this.resource = targets[0]; this.showAction = true
        return
      }
      if (action.policyDeleteDialog) {
        const targets = isGroupAction ? this.selectedItems : [action.resource || this.resource]
        if (!canDeletePolicy(this.$store.getters.apis) || !targets.length || targets.some(p => !p?.id || !p.volumeid)) return
        this.currentAction = { ...action, invokedAsGroupAction: !!isGroupAction, policyListScope: this.listScope(), policyDeleteTargets: Object.freeze(targets.map(p => Object.freeze({ ...p }))) }
        this.resource = Object.freeze({ ...targets[0] })
        this.showAction = true
        return
      }
      if (action.api === 'deleteAffinityGroup') {
        if (!['listAffinityGroups', 'deleteAffinityGroup'].every(api => api in this.$store.getters.apis)) return
        this.currentAction = { ...action, invokedAsGroupAction: !!isGroupAction, affinityTargets: Object.freeze((isGroupAction ? this.selectedItems : [action.resource]).filter(Boolean).map(row => Object.freeze({ ...row }))) }
        this.resource = action.resource || {}
        this.showAction = true
        return
      }
      if (action.api === 'deleteCniConfiguration') {
        if (!['listCniConfiguration', 'deleteCniConfiguration'].every(api => api in this.$store.getters.apis)) return
        const request = { ...action, invokedAsGroupAction: !!isGroupAction }
        this.currentAction = { ...request, cniTargets: cniDeleteTargets(request, this.selectedItems), cniListScope: this.listScope(), cniListLocation: cniListLocation(this.$route) }
        this.resource = action.resource || {}
        this.showAction = true
        return
      }
      if (action.api === 'deleteUserData') {
        if (!('deleteUserData' in this.$store.getters.apis) || !('listUserData' in this.$store.getters.apis)) return
        const request = { ...action, invokedAsGroupAction: !!isGroupAction }
        this.currentAction = { ...request, userDataTargets: userDataDeleteTargets(request, this.selectedItems), userDataListScope: this.listScope() }
        this.resource = action.resource || {}
        this.showAction = true
        return
      }
      if (action.api === 'deleteSSHKeyPair') {
        if (this.actionTrigger?.closest('.resource-context-menu')) this.actionTrigger = this.$refs?.detailActionsTrigger?.$el || this.$el?.querySelector('.ant-table-selection-column input') || this.actionTrigger
        const request = { ...action, invokedAsGroupAction: !!isGroupAction }
        this.currentAction = { ...request, sshKeyPairTargets: sshKeyPairDeleteTargets(request, this.selectedItems) }
        this.resource = action.resource || {}
        this.showAction = true
        return
      }
      if (kmsOperationApis.includes(action.api) && action.api !== 'createHSMProfile') {
        const target = action.resource || this.resource
        if (isGroupAction || !(action.api in (this.$store.getters.apis || {})) ||
            (action.api !== 'createKMSKey' && !target?.id)) return
        const kmsTarget = action.api === 'createKMSKey' ? {} : target
        this.currentAction = { ...action, kmsTarget: Object.freeze({ ...kmsTarget }), kmsNavigationScope: this.kmsNavigationScope(), invokedAsGroupAction: false }
        this.showAction = true
        return
      }
      if (action.api === 'createHSMProfile') {
        if (!('createHSMProfile' in (this.$store.getters.apis || {}))) return
        this.currentAction = { ...action, invokedAsGroupAction: false }
        this.resource = {}
        this.showAction = true
        return
      }
      if (action.api === 'migrateVolumesToKMS') {
        const target = action.resource || this.resource
        if (!target?.id || !('migrateVolumesToKMS' in (this.$store.getters.apis || {}))) return
        this.currentAction = { ...action, invokedAsGroupAction: false }
        this.resource = { ...target }
        this.showAction = true
        return
      }
      if (action.api === 'createVMSnapshot') action = { ...action, resource: Object.freeze({ ...action.resource }) }
      if (action.snapshotMode) {
        this.currentAction = { ...action, invokedAsGroupAction: !!isGroupAction, snapshotTargets: Object.freeze((isGroupAction ? this.selectedItems : [action.resource]).filter(Boolean).map(row => Object.freeze({ ...row }))) }
        this.resource = action.resource || {}
        this.showAction = true
        return
      }
      this.listEventHandlers = []
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({})
      if (action.component && action.api && !action.popup) {
        const query = {}
        if (this.$route.path.startsWith('/vm') || this.$route.path.startsWith('/vnfapp')) {
          switch (true) {
            case ('templateid' in this.$route.query):
              query.templateid = this.$route.query.templateid
              break
            case ('isoid' in this.$route.query):
              query.isoid = this.$route.query.isoid
              break
            case ('networkid' in this.$route.query):
              query.networkid = this.$route.query.networkid
              break
            default:
              break
          }
        }
        this.$router.push({ name: action.api, query })
        return
      }
      if (this.vpnCustomerGatewayActionScope && ['createVpnCustomerGateway', 'updateVpnCustomerGateway', 'deleteVpnCustomerGateway'].includes(action.api)) {
        const getters = this.$store.getters
        if (!(action.api in getters.apis) || !getters.apis.queryAsyncJobResult) return
        const record = action.resource || this.resource
        if (action.api !== 'createVpnCustomerGateway' && (!this.storageActionReady || !isGroupAction && !record?.id)) return
        const selection = isGroupAction ? this.selectedItems : [record]
        if (isGroupAction && (!selection.length || selection.some(row => !this.selectedRowKeys.includes(row.id)))) return
        const targets = selection.filter(row => row?.id && !row.removed)
          .map(({ id, name, gateway, project, account, domain }) => Object.freeze({ id, name, gateway, project, account, domain }))
        if (action.api === 'deleteVpnCustomerGateway' && targets.length !== selection.length) return
        this.resource = record || {}
        this.currentAction = {
          ...action,
          vpnCustomerGatewayScope: this.vpnCustomerGatewayActionScope,
          vpnCustomerGatewayTargets: Object.freeze(targets)
        }
        this.showAction = true
        return
      }
      this.currentAction = {
        ...action,
        invokedAsGroupAction: !!isGroupAction
      }
      if (action.api === 'deleteInstanceGroup') {
        this.currentAction.vmGroupTargets = vmGroupDeleteTargets(this.currentAction, this.selectedItems)
      }
      const apiParams = store.getters.apis[this.currentAction.api]?.params
      if (!Array.isArray(apiParams)) {
        this.rejectActionSchema(action.api, ['*'])
        return
      }
      this.currentAction.params = apiParams
      this.actionConfirmText = ''
      this.resource = action.resource
      this.$emit('change-resource', this.resource)
      var paramFields = this.currentAction.params
      paramFields.sort(function (a, b) {
        if (a.name === 'name' && b.name !== 'name') { return -1 }
        if (a.name !== 'name' && b.name === 'name') { return -1 }
        if (a.name === 'id') { return -1 }
        if (a.name < b.name) { return -1 }
        if (a.name > b.name) { return 1 }
        return 0
      })
      this.currentAction.paramFields = []
      this.currentAction.paramFilters = []
      if ('message' in this.currentAction) {
        let message = this.currentAction.message
        if (typeof message === 'function') {
          message = message(action.resource)
        }
        this.currentAction.message = Array.isArray(message) ? this.$t(...message) : this.$t(message)
      }
      const missingArgs = this.getArgs(action, isGroupAction, paramFields)
      if (missingArgs.length > 0) {
        this.rejectActionSchema(action.api, missingArgs)
        return
      }
      this.getFilters(action, isGroupAction, paramFields)
      this.getFirstIndexFocus()

      this.showAction = true
      const listIconForFillValues = ['copy-outlined', 'CopyOutlined', 'edit-outlined', 'EditOutlined', 'share-alt-outlined', 'ShareAltOutlined', 'minus-square-outlined']
      for (const param of this.currentAction.paramFields) {
        if (param.type === 'list' && ['tags', 'hosttags', 'storagetags', 'storageaccessgroups', 'files'].includes(param.name)) {
          param.type = 'string'
        }
        this.setRules(param)
        if (param.type === 'uuid' || param.type === 'list' || param.name === 'account' || (this.currentAction.mapping && param.name in this.currentAction.mapping)) {
          this.listUuidOpts(param, this.currentAction.paramFilters[param.name])
        }
      }
      this.actionLoading = false
      if (action.dataView && listIconForFillValues.includes(action.icon)) {
        this.fillEditFormFieldValues()
      }
      if (['createNetworkACLList', 'updateNetworkACLList'].includes(action.api) &&
          this.currentAction.paramFields.some(field => field.name === 'fordisplay')) {
        this.form.fordisplay = action.api === 'createNetworkACLList' ? true : action.resource.fordisplay
      }
    },
    rejectActionSchema (api, missingArgs) {
      this.closeAction()
      this.$message.error(this.$t('message.api.schema.mismatch', { api, parameters: missingArgs.join(', ') }))
    },
    getArgs (action, isGroupAction, paramFields) {
      const self = this
      const missingArgs = []
      if ('args' in action) {
        var args = action.args
        if (typeof action.args === 'function') {
          args = action.args(action.resource, this.$store.getters, isGroupAction)
        }
        if (args.length > 0) {
          this.currentAction.paramFields = args.map(function (arg) {
            if (arg === 'confirmpassword') {
              return {
                type: 'password',
                name: 'confirmpassword',
                required: true,
                description: self.$t('label.confirmpassword.description')
              }
            }
            if (arg === 'ostypeid') {
              return {
                type: 'uuid',
                name: 'ostypeid',
                required: true,
                description: self.$t('label.select.guest.os.type')
              }
            }
            const field = paramFields.find(function (param) {
              return param.name.toLowerCase() === arg.toLowerCase()
            })
            if (!field) {
              missingArgs.push(arg)
            }
            return field
          })
        }
      }
      return missingArgs
    },
    getFilters (action, isGroupAction, paramFields) {
      if ('filters' in action) {
        var filters = action.filters
        if (typeof action.filters === 'function') {
          filters = action.filters(action.resource, this.$store.getters, isGroupAction)
        }
        this.currentAction.paramFilters = filters
      }
    },
    getFirstIndexFocus () {
      this.firstIndex = 0
      if (this.projectStandardDialog && this.requiresNameConfirmation) { this.firstIndex = -1; return }
      for (let fieldIndex = 0; fieldIndex < this.currentAction.paramFields.length; fieldIndex++) {
        const field = this.currentAction.paramFields[fieldIndex]
        if (!(this.currentAction.mapping && field.name in this.currentAction.mapping && this.currentAction.mapping[field.name].value)) {
          this.firstIndex = fieldIndex
          break
        }
      }
    },
    handleSelectChange (name, val) {
      if (name === 'domainid') {
        const accountField = this.currentAction.paramFields.find(f => f.name === 'account')
        if (accountField) {
          this.form.account = null
          this.listUuidOpts(accountField, { domainid: val })
        }
      } else if (name === 'account') {
        const volumeField = this.currentAction.paramFields.find(f => f.name === 'volumeids')
        if (volumeField) {
          this.form.volumeids = null
          this.listUuidOpts(volumeField, { domainid: this.form.domainid, account: val })
        }
      }
    },
    listUuidOpts (param, filters) {
      if (this.currentAction.mapping && param.name in this.currentAction.mapping && !this.currentAction.mapping[param.name].api) {
        return
      }
      var paramName = param.name
      var extractedParamName = paramName.replace('ids', '').replace('id', '').toLowerCase()
      if (extractedParamName.endsWith('ory')) {
        extractedParamName = extractedParamName.slice(0, -3) + 'orie'
      }
      var params = { listall: true }
      for (const filter in filters) {
        params[filter] = filters[filter]
      }
      const possibleName = 'list' + extractedParamName + 's'
      var showIcon = false
      if (this.$showIcon(extractedParamName)) {
        showIcon = true
      }
      var possibleApi
      if (this.currentAction.mapping && param.name in this.currentAction.mapping && this.currentAction.mapping[param.name].api) {
        possibleApi = this.currentAction.mapping[param.name].api
        if (this.currentAction.mapping[param.name].params) {
          const customParams = this.currentAction.mapping[param.name].params(this.resource)
          if (customParams) {
            params = { ...params, ...customParams }
          }
        }
      } else if (paramName === 'id') {
        possibleApi = this.apiName
      } else {
        for (const api in store.getters.apis) {
          if (api.toLowerCase().startsWith(possibleName)) {
            possibleApi = api
            break
          }
        }
      }
      if (!possibleApi) {
        return
      }
      param.loading = true
      param.opts = []
      if (possibleApi === 'listTemplates') {
        params.templatefilter = 'executable'
      } else if (possibleApi === 'listIsos') {
        if (this.$route.path.startsWith('/kubernetesiso')) {
          params.bootable = false
        }
        params.isofilter = 'executable'
      } else if (possibleApi === 'listHosts') {
        params.type = 'routing'
        if (this.currentAction?.api === 'restoreBackup') {
          params.resourcestate = 'enabled'
          params.state = 'up'
        }
      } else if (possibleApi === 'listNetworkOfferings' && this.resource) {
        if (this.resource.type) {
          params.guestiptype = this.resource.type
        }
        if (!this.resource.vpcid) {
          params.forvpc = false
        }
      }
      if (showIcon) {
        params.showicon = true
      }
      callAPI(possibleApi, params).then(json => {
        param.loading = false
        for (const obj in json) {
          if (obj.includes('response')) {
            if (possibleApi === 'listBackupOfferings' && json[obj].backupoffering) {
              json[obj].backupoffering.sort((a, b) => {
                return a.name > b.name
              })
            }
            for (const res in json[obj]) {
              if (res === 'count') {
                continue
              }
              param.opts = json[obj][res]
              if (this.currentAction.mapping && this.currentAction.mapping[param.name] && this.currentAction.mapping[param.name].filter) {
                const filter = this.currentAction.mapping[param.name].filter
                param.opts = json[obj][res].filter(filter)
              }
              if (['listTemplates', 'listIsos'].includes(possibleApi)) {
                param.opts = [...new Map(param.opts.map(x => [x.id, x])).values()]
              }
              break
            }
            break
          }
        }
      }).catch(function (error) {
        console.log(error)
        param.loading = false
      })
    },
    pollActionCompletion (jobId, action, resourceName, resource, showLoading = true, selectedItems = this.selectedItems) {
      const commvaultRestore = action?.api === 'restoreBackup' && String(this.resource?.provider || '').toLowerCase() === 'ablestack-commvault'
      if (this.shouldNavigateBack(action)) {
        action.isFetchData = false
      }
      return new Promise((resolve) => {
        this.$pollJob({
          jobId,
          title: this.$t(action.label),
          description: resourceName,
          name: action.api === 'restoreBackup' ? '' : resourceName,
          successMethod: result => {
            if (selectedItems === this.selectedItems && selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems, resource, state: 'success' })
            }
            if (action.response) {
              const response = action.response(result.jobresult)
              if (response) {
                if (typeof response === 'object') {
                  this.$notification.info({
                    message: this.$t(action.label),
                    description: (<span v-html={response.message}></span>),
                    btn: () => h(
                      Button,
                      {
                        type: 'primary',
                        size: 'small',
                        onClick: () => this.copyToClipboard(response.copytext)
                      },
                      () => [this.$t(response.copybuttontext)]
                    ),
                    duration: 0
                  })
                } else {
                  this.$notification.info({
                    message: this.$t(action.label),
                    description: (<span v-html={response}></span>),
                    duration: 0
                  })
                }
              }
            }
            if ('successMethod' in action) {
              action.successMethod(this, result)
            }
            if (['createProject', 'updateProject', 'deleteProject'].includes(action.api)) {
              eventBus.emit('projects-updated', { action: action.api, project: this.resource })
            }
            if (action.api === 'restoreBackup') {
              const restoringBackup = this.resource
              this.markBackupRestoreStarted(restoringBackup)
              Promise.resolve(this.fetchData({ irefresh: true })).finally(() => {
                this.markBackupRestoreStarted(restoringBackup)
              })
            }
            resolve(true)
          },
          errorMethod: () => {
            if (selectedItems === this.selectedItems && selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems, resource, state: 'failed' })
            }
            if (action.api === 'restoreBackup') {
              this.fetchData({ irefresh: true })
            }
            resolve(true)
          },
          loadingMessage: `${this.$t(action.label)} - ${resourceName}`,
          successMessage: ['restoreBackup', 'cancelBackup'].includes(action.api) ? this.$t(action.successMessage) : undefined,
          showLoading: showLoading && !commvaultRestore,
          showSuccessMessage: !commvaultRestore,
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            if (action.api === 'restoreBackup') {
              this.fetchData({ irefresh: true })
            }
            resolve(false)
          },
          action,
          bulkAction: `${selectedItems.length > 0}` && this.showGroupActionModal,
          resourceId: resource
        })
      })
    },
    fillEditFormFieldValues () {
      this.currentAction.paramFields.map(field => {
        let fieldValue = null
        let fieldName = null
        if (field.type === 'list' || field.name === 'account') {
          fieldName = field.name.replace('ids', 'name').replace('id', 'name')
        } else {
          fieldName = field.name
        }
        fieldValue = this.resource[fieldName] ? this.resource[fieldName] : null
        if (fieldValue) {
          this.form[field.name] = fieldValue
        } else if (field.type === 'boolean' && field.name === 'rebalance' && this.currentAction.api === 'cancelMaintenance') {
          this.form[field.name] = true
        } else if (field.type === 'map') {
          const transformedValue = this.currentAction.mapping?.[field.name]?.transformedvalue
          if (typeof transformedValue === 'function') {
            this.form[field.name] = transformedValue(this.resource)
          } else if (typeof transformedValue === 'object' && transformedValue !== null) {
            this.form[field.name] = { ...transformedValue }
          } else {
            this.form[field.name] = {}
          }
        }
      })
    },
    handleCancel () {
      eventBus.emit('update-bulk-job-status', { items: this.selectedItems, action: false })
      this.showGroupActionModal = false
      this.selectedItems = []
      this.bulkColumns = []
      this.selectedRowKeys = []
      this.message = {}
    },
    handleProjectDialogShortcut (event) {
      if (!this.projectStandardDialog) return
      event.preventDefault()
      event.stopPropagation()
      if (!event.repeat) this.handleSubmit(event)
    },
    handleSubmit (e) {
      if (this.currentAction.api === 'downloadEvents' || isEventMutation(this.currentAction.api)) return
      if (this.$route.meta?.name === 'kubernetesiso' && !canKubernetesIsoAction(Object.keys(kubernetesIsoApis).find(key => kubernetesIsoApis[key] === this.currentAction?.api), this.resource, this.$store.getters)) return
      if (this.actionLoading || this.isSubmitDisabled || (this.vmStandardDialog && (this.vmActionValidationPending || this.vmActionUnknown))) return
      this.promises = []
      const groupDeletion = this.currentAction.api === 'deleteInstanceGroup' && this.currentAction.invokedAsGroupAction
      const selectedKeys = groupDeletion ? (this.currentAction.vmGroupTargets || []).map(group => group.id) : this.selectedRowKeys
      if (!this.dataView && this.currentAction.invokedAsGroupAction && selectedKeys.length > 0) {
        if (selectedKeys.length > 0) {
          this.bulkColumns = bulkColumns(this.chosenColumns)
          this.selectedItems = (groupDeletion ? this.currentAction.vmGroupTargets : this.selectedItems).map(v => ({ ...v, status: 'InProgress' }))
          this.bulkColumns.splice(0, 0, {
            key: 'status',
            dataIndex: 'status',
            title: this.$t('label.operation.status'),
            filters: [
              { text: 'In Progress', value: 'InProgress' },
              { text: 'Success', value: 'success' },
              { text: 'Failed', value: 'failed' }
            ]
          })
          this.showGroupActionModal = true
          this.modalInfo.title = this.currentAction.label
          this.modalInfo.docHelp = this.currentAction.docHelp
        }
        const submittedAction = this.currentAction
        const vmScope = this.vmStandardDialog ? vmEditScope(this.resource, this.$route, this.$store) : null
        const vmTargets = vmScope ? JSON.stringify(selectedKeys) : null
        const vmGeneration = this.storageActionGeneration
        if (vmScope) this.vmActionValidationPending = true
        this.formRef.value.validate().then(() => {
          if (vmScope && (!this.showAction || submittedAction !== this.currentAction || vmScope !== vmEditScope(this.resource, this.$route, this.$store) || vmTargets !== JSON.stringify(this.selectedRowKeys))) return
          if (vmScope) this.vmActionValidationPending = false
          const values = toRaw(this.form)
          this.actionLoading = true
          const itemsNameMap = {}
          const namedItems = groupDeletion ? this.currentAction.vmGroupTargets : this.items
          namedItems.map(x => {
            itemsNameMap[x.id] = isVmIdentityRoute(this.$route) ? vmDisplayName(x) : x.name || x.displaytext || x.id
          })
          const paramsList = this.currentAction.groupMap(groupDeletion ? selectedKeys : this.selectedRowKeys, values, this.items)
          for (const params of paramsList) {
            var resourceName = itemsNameMap[params.id]
            // Using a method for this since it's an async call and don't want wrong prarms to be passed
            this.promises.push(this.callGroupApi(params, resourceName))
          }
          this.$message.info({
            content: this.$t(this.currentAction.label),
            key: this.currentAction.label,
            duration: 3
          })
          Promise.all(this.promises).finally(() => {
            if (vmScope && (vmGeneration !== this.storageActionGeneration || vmScope !== vmEditScope(this.resource, this.$route, this.$store))) return
            this.actionLoading = false
            this.fetchData()
          })
        }).catch(error => {
          if (vmScope && submittedAction !== this.currentAction) return
          if (vmScope) this.vmActionValidationPending = false
          if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        })
      } else {
        this.execSubmit(e)
      }
    },
    callGroupApi (params, resourceName) {
      const selectedItems = this.selectedItems
      return new Promise((resolve, reject) => {
        const action = this.currentAction
        const vmGeneration = this.storageActionGeneration
        const vmScope = this.vmStandardDialog ? vmEditScope(this.resource, this.$route, this.$store) : null
        postAPI(action.api, params).then(json => {
          if (vmScope && (vmGeneration !== this.storageActionGeneration || vmScope !== vmEditScope(this.resource, this.$route, this.$store))) { resolve(false); return }
          resolve(this.handleResponse(json, resourceName, this.getDataIdentifier(params), action, false, selectedItems))
          if (!vmScope || this.currentAction === action) this.closeAction()
        }).catch(error => {
          if (vmScope && (vmGeneration !== this.storageActionGeneration || vmScope !== vmEditScope(this.resource, this.$route, this.$store))) { resolve(false); return }
          if ([401].includes(error.response?.status)) {
            resolve(false)
            return
          }
          if (selectedItems === this.selectedItems && selectedItems.length !== 0) {
            if (isVmGroupAction(action.api)) this.notifyVmGroupError(error)
            else this.$notifyError(error)
            eventBus.emit('update-resource-state', { selectedItems, resource: this.getDataIdentifier(params), state: error.trackingStatus === 'unknown' ? 'unknown' : 'failed' })
          }
          resolve(false)
        })
      })
    },
    getDataIdentifier (params) {
      var dataIdentifier = ''
      dataIdentifier = params.id || params.username || params.name || params.vmsnapshotid || params.ids
      return dataIdentifier
    },
    handleResponse (response, resourceName, resource, action, showLoading = true, selectedItems = this.selectedItems) {
      return new Promise(resolve => {
        let jobId = null
        for (const obj in response) {
          if (obj.includes('response')) {
            if (response[obj].jobid) {
              jobId = response[obj].jobid
            } else {
              if (selectedItems === this.selectedItems && selectedItems.length > 0) {
                eventBus.emit('update-resource-state', { selectedItems, resource, state: 'success' })
                if (resource) {
                  selectedItems.filter(item => item === resource)
                }
              }
              if (selectedItems.length === 0) {
                let message = ''
                let messageDuration = 2
                if ('successMessage' in action) {
                  message = action.successMessage
                  if (typeof action.successMessage === 'function') {
                    message = action.successMessage(action.resource)
                  }
                  message = Array.isArray(message) ? this.$t(...message) : this.$t(message)
                } else {
                  message = this.$t(action.label) + (resourceName ? ' - ' + resourceName : '')
                }
                if ('additionalMessage' in action) {
                  message = `${message} - ${this.$t(action.additionalMessage)}`
                  messageDuration = 5
                }
                this.$message.success({
                  content: message,
                  key: action.label + resourceName,
                  duration: messageDuration
                })
              }
              break
            }
          }
        }
        if (['addLdapConfiguration', 'deleteLdapConfiguration'].includes(action.api)) {
          this.$store.dispatch('UpdateConfiguration')
        }
        if (jobId) {
          if (action.api === 'restoreBackup') {
            this.markBackupRestoreStarted(this.resource)
            if (String(this.resource?.provider || '').toLowerCase() === 'ablestack-commvault') {
              this.$message.info({ content: this.$t('label.backup.restore.requested'), duration: 2 })
            }
          }
          if (selectedItems === this.selectedItems) {
            eventBus.emit('update-resource-state', { selectedItems, resource, state: 'InProgress', jobid: jobId })
          }
          resolve(this.pollActionCompletion(jobId, action, resourceName, resource, showLoading, selectedItems))
        }
        resolve(false)
      })
    },
    markBackupRestoreStarted (backup) {
      if (!backup?.id) {
        return
      }
      const trackedBackup = this.items.find(item => item.id === backup.id) || backup
      trackedBackup.restoreoperationpending = true
      trackedBackup.restorejobstate = 'STARTING'
      trackedBackup.restorejobstep = 'REQUESTED'
    },
    async postSnapshotAwareAction (action, params) {
      if (action.api !== 'createVMSnapshot') return postAPI(action.api, params)
      const security = JSON.stringify([this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.$store.state?.user?.token])
      const id = action.resource.id
      const [context] = await freshSnapshotContext(getAPI, [{ virtualmachineid: id }], 'listBackups' in this.$store.getters.apis)
      const vm = context.vm
      if (security !== JSON.stringify([this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.$store.state?.user?.token]) || !('createVMSnapshot' in this.$store.getters.apis)) throw new Error(this.$t('message.vmsnapshot.permission'))
      if (!vm || !action.show(vm, this.$store.getters)) throw new Error(this.$t('message.vmsnapshot.vm.state'))
      if (context.busy || snapshotBusy(id) || action.disabled(vm, this.$store.getters, [])) throw new Error(this.$t(vm.vmsnapshotblockedreason ? 'message.backup.snapshot.snapshot.blocked' : 'message.vmsnapshot.busy'))
      const submission = Symbol(id)
      snapshotSubmissions[id] = submission
      try {
        const response = await postAPI(action.api, { ...params, virtualmachineid: id })
        if (!response.createvmsnapshotresponse?.jobid) throw Object.assign(new Error(this.$t('message.vmsnapshot.submission.unknown')), { trackingStatus: 'unknown' })
        return response
      } catch (error) {
        if (error.trackingStatus === 'unknown' || (error.isAxiosError && !error.response)) {
          trackUnknownSnapshotSubmission(id)
          throw Object.assign(new Error(this.$t('message.vmsnapshot.submission.unknown')), { trackingStatus: 'unknown' })
        }
        throw error
      } finally { if (snapshotSubmissions[id] === submission) delete snapshotSubmissions[id] }
    },
    execSubmit (e) {
      if (isSshKeyPairDetailRoute(this.$route) && !this.canExecuteSshKeyPairAction(this.currentAction)) return
      const sshActionScope = isSshKeyPairDetailRoute(this.$route) ? this.listScope() : null
      const submittedAction = this.currentAction
      const roleEditId = this.$route.meta?.name === 'role' && submittedAction.api === 'updateRole' ? this.resource.id : null
      if (roleEditId && !canRoleAction('updateRole', this.resource, this.$store.getters.apis)) return
      if (roleEditId) this.roleActionError = ''
      const projectScope = this.projectStandardDialog ? this.listScope() : null
      if (projectScope) this.projectActionError = ''
      const vmScope = this.vmStandardDialog ? vmEditScope(this.resource, this.$route, this.$store) : null
      if (vmScope && (this.vmActionValidationPending || this.vmActionUnknown)) return
      if (vmScope) { this.vmActionValidationPending = true; this.vmActionError = '' }
      if (e && typeof e.preventDefault === 'function') e.preventDefault()
      this.formRef.value.validate().then(() => {
        if (projectScope && (!this.showAction || submittedAction !== this.currentAction || projectScope !== this.listScope() || this.actionLoading)) return
        if (vmScope && (!this.showAction || submittedAction !== this.currentAction || vmScope !== vmEditScope(this.resource, this.$route, this.$store))) return
        if (vmScope) this.vmActionValidationPending = false
        if (sshActionScope && (sshActionScope !== this.listScope() || !this.canExecuteSshKeyPairAction(submittedAction))) return
        let values = toRaw(this.form)
        if (roleEditId) {
          if (!this.showAction || submittedAction !== this.currentAction || roleEditId !== this.resource.id ||
            !canRoleAction('updateRole', this.resource, this.$store.getters.apis)) return
          const fields = roleEditFields(this.resource)
          values = Object.fromEntries(Object.entries(values).filter(([key]) => fields.includes(key)))
        }
        const params = {}
        const action = this.currentAction
        if (isVmGroupNameAction(action.api)) {
          values = { ...values, name: normalizeVmGroupName(values.name, action.api === 'updateInstanceGroup' ? this.resource.name : undefined) }
          if (action.api === 'updateInstanceGroup' && values.name === this.resource.name) {
            this.$message.info(this.$t('message.vm.group.name.unchanged'))
            this.closeAction()
            return
          }
        }
        if ('id' in this.resource && action.params.map(i => { return i.name }).includes('id')) {
          params.id = this.resource.id
        }
        if (action.api === 'deleteInstanceGroup') {
          if (!action.vmGroupTargets?.length) throw new Error('message.vm.group.operation.missing')
          params.id = action.vmGroupTargets[0].id
        }

        if (['updateDiskOffering'].includes(action.api) && values.tags === this.resource.tags) {
          delete values.tags
        }

        if (['updateServiceOffering'].includes(action.api)) {
          if (values.hosttags === this.resource.hosttags) {
            delete values.hosttags
          }
          if (values.storagetags === this.resource.storagetags) {
            delete values.tags
          }
        }

        if (['cancelMaintenance'].includes(action.api) && (params.rebalance === undefined || params.rebalance === null || params.rebalance === '')) {
          params.rebalance = true
        }

        for (const key in values) {
          const input = values[key]
          if (key === 'fordisplay' && ['createNetworkACLList', 'updateNetworkACLList'].includes(action.api) &&
              (!aclDisplaySupported(this.$store.getters, action.api) || typeof input !== 'boolean' ||
               (action.api === 'updateNetworkACLList' && input === action.resource.fordisplay))) continue
          for (const param of action.params) {
            if (param.name !== key) {
              continue
            }
            if (input === undefined || input === null ||
              (input === '' && !['updateStoragePool', 'updateHost', 'updatePhysicalNetwork',
                'updateDiskOffering', 'updateNetworkOffering', 'updateServiceOffering',
                'updateZone', 'updateAccount', 'updateWebhook'].includes(action.api) &&
                !(action.api === 'updateProject' && key === 'displaytext'))) {
              if (param.type === 'boolean') {
                params[key] = false
              }
              break
            }
            if (input === '' && !['tags', 'hosttags', 'storagetags', 'storageaccessgroups', 'dns2', 'ip6dns1',
              'ip6dns2', 'internaldns2', 'networkdomain', 'secretkey'].includes(key)) {
              break
            }
            if (action.mapping && key in action.mapping && action.mapping[key].options) {
              params[key] = action.mapping[key].options[input]
              if (['createAffinityGroup'].includes(action.api) && key === 'type') {
                if (params[key] === 'host anti-affinity (Strict)') {
                  params[key] = 'host anti-affinity'
                } else if (params[key] === 'host affinity (Strict)') {
                  params[key] = 'host affinity'
                } else if (params[key] === 'host anti-affinity (Non-Strict)') {
                  params[key] = 'non-strict host anti-affinity'
                } else if (params[key] === 'host affinity (Non-Strict)') {
                  params[key] = 'non-strict host affinity'
                }
              }
            } else if (param.type === 'list') {
              params[key] = input.map(e => { return param.opts[e].id }).reduce((str, name) => { return str + ',' + name })
            } else if (param.name === 'account' || param.name === 'keypair') {
              if (['addAccountToProject', 'createAccount'].includes(action.api)) {
                params[key] = input
              } else {
                params[key] = param.opts[input].name
              }
            } else if (param.type === 'map' && typeof input === 'object') {
              const details = values[key]
              if (details && Object.keys(details).length > 0) {
                Object.entries(details).forEach(([k, v]) => {
                  params[key + '[0].' + k] = v
                })
              } else {
                if (['details', 'externaldetails'].includes(key)) {
                  const updateApiParams = this.$getApiParams(action.api)
                  const cleanupKey = 'cleanup' + key
                  if (cleanupKey in updateApiParams) {
                    params[cleanupKey] = true
                    break
                  }
                }
                params[key] = {}
              }
            } else {
              params[key] = input
            }
            break
          }
        }

        for (const key in action.defaultArgs) {
          if (!params[key]) {
            params[key] = action.defaultArgs[key]
          }
        }

        if (!this.projectView || !['uploadSslCert'].includes(action.api)) {
          if (action.mapping) {
            for (const key in action.mapping) {
              if (!action.mapping[key].value) {
                continue
              }
              params[key] = action.mapping[key].value(this.resource, params, this.$route.query)
            }
          }
        }

        const resourceName = (isVmIdentityRoute(this.$route) && this.resource?.id && !action.listView ? vmDisplayName(this.resource) : '') || (action.api === 'deleteInstanceGroup' ? action.vmGroupTargets[0]?.name : null) || params.displayname || params.displaytext || params.name || params.hostname || params.username ||
          params.ipaddress || params.virtualmachinename || this.resource.name || this.resource.ipaddress || this.resource.id

        var hasJobId = false
        const actionScope = this.listScope()
        this.actionLoading = true
        const request = roleEditId ? postAPI(action.api, params, { preserveOnFailure: true }) : this.postSnapshotAwareAction(action, params)
        request.then(json => {
          if (roleEditId && (action !== this.currentAction || roleEditId !== this.resource.id)) return
          if (vmScope && (action !== this.currentAction || vmScope !== vmEditScope(this.resource, this.$route, this.$store))) return
          if (vmScope && ['startVirtualMachine', 'stopVirtualMachine', 'rebootVirtualMachine', 'restoreVirtualMachine'].includes(action.api) && !Object.values(json || {}).some(value => value?.jobid)) {
            this.vmActionUnknown = true; this.vmActionError = this.$t('message.vm.start.unknown'); return
          }
          var response = this.handleResponse(json, resourceName, this.getDataIdentifier(params), action)
          if (!response) {
            this.fetchData()
            this.closeAction()
            return
          }
          response.then(jobId => {
            hasJobId = jobId
            if (actionScope !== this.listScope()) return
            if (this.shouldNavigateBack(action)) {
              this.$router.go(-1)
            } else {
              if (!hasJobId) {
                this.fetchData()
              }
            }
          })
          this.closeAction()
        }).catch(error => {
          if (vmScope && (action !== this.currentAction || vmScope !== vmEditScope(this.resource, this.$route, this.$store))) return
          if (vmScope) {
            this.vmActionUnknown = !error.response || error.response.status >= 500
            this.vmActionError = this.vmActionUnknown ? this.$t('message.vm.start.unknown') : Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext || this.$t('error.fetching.data')
          }
          if (roleEditId) {
            if (action === this.currentAction && roleEditId === this.resource.id) {
              this.roleActionError = Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext || this.$t('message.request.failed')
            }
            return
          }
          if ([401].includes(error.response?.status)) {
            return
          }

          if (!isVmGroupAction(action.api)) console.log(error)
          if (projectScope && this.currentAction === action && projectScope === this.listScope()) {
            this.projectActionError = Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext || this.$t('message.request.failed')
          }
          if (this.isVpcUi && this.currentAction === action && actionScope === this.listScope()) this.vpcActionError = Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext || error.message || this.$t('error.fetching.data')
          eventBus.emit('update-resource-state', { selectedItems: this.selectedItems, resource: this.getDataIdentifier(params), state: 'failed' })
          if (isVmGroupAction(action.api)) this.notifyVmGroupError(error)
          else this.$notifyError(error)
        }).finally(f => {
          if (vmScope && action !== this.currentAction) return
          this.actionLoading = false
        })
      }).catch(error => {
        if (vmScope && submittedAction !== this.currentAction) return
        if (vmScope) this.vmActionValidationPending = false
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        else if (isVmGroupAction(this.currentAction.api)) this.notifyVmGroupError(error)
      })
    },
    notifyVmGroupError (error) {
      this.$notification.error({ message: this.$t('message.request.failed'), description: this.$t(vmGroupErrorKey(error)) })
    },
    shouldNavigateBack (action) {
      return ((['delete-outlined', 'DeleteOutlined'].includes(action.icon) || ['archiveEvents', 'archiveAlerts', 'unmanageVirtualMachine'].includes(action.api)) && this.dataView)
    },
    getColumnKey (name) {
      if (typeof name === 'object') {
        name = Object.keys(name).includes('customTitle') ? name.customTitle : name.field
      }
      return name
    },
    updateSelectedColumns (name) {
      if (name) {
        name = this.getColumnKey(name)
        if (this.selectedColumns.includes(name)) {
          this.selectedColumns = this.selectedColumns.filter(x => x !== name)
        } else {
          this.selectedColumns.push(name)
        }
      }

      if (snapshotListRoute(this.$route) && name) saveSnapshotListColumns(this.$localStorage, this.$store.getters.userInfo.id, this.isSnapshotCopyList, this.selectedColumns)

      if (this.$route.name === 'autoscalevmgroup' && name === 'zonename' && this.selectedColumns.includes('zonename')) this.fetchData()

      this.columns = this.allColumns.filter(x => this.selectedColumns.includes(x.dataIndex))
      const filterColumn = {
        key: 'filtercolumn',
        dataIndex: 'filtercolumn',
        title: '',
        customFilterDropdown: true,
        width: 5
      }
      if (this.columns.length === 0) {
        filterColumn.width = 'auto'
      }
      this.columns.push(filterColumn)
      if (this.$route.meta?.name === 'iso') this.columns = this.columns.map(column => isoColumn(column, this.$route.query, this.$t))
      if (!this.$store.getters.customColumns[this.$store.getters.userInfo.id]) {
        this.$store.getters.customColumns[this.$store.getters.userInfo.id] = {}
      }
      this.$store.getters.customColumns[this.$store.getters.userInfo.id][this.$route.path] = this.selectedColumns
      this.$store.dispatch('SetCustomColumns', this.$store.getters.customColumns)
      if (this.isIsoList && name && name === this.$route.query.sortkey && !this.selectedColumns.includes(name)) {
        const query = { ...this.$route.query }; delete query.sortkey; delete query.sortorder
        this.$router.replace({ query: isoListQuery(query, this.pageSize, this.$store.getters) })
      }
    },
    localizePublicIpColumns (columns) {
      if (!publicIpRoute(this.$route)) return columns
      return (columns || []).map(column => column.dataIndex && column.title && typeof column.title === 'string' ? { ...column, title: this.$t(publicIpFieldLabel(column.key) || 'label.' + String(column.key).toLowerCase()) } : column)
    },
    getFilterLabel (filter) {
      const key = this.$route.meta?.filterLabels?.[filter] || ('label.' + (this.$route.name === 'comment' ? 'filter.annotations.' : '') + filter)
      return this.$t(key)
    },
    changeKmsDisplay (filter) {
      const raw = kmsDisplayQuery(this.$route.query, filter)
      const query = kmsQuery(raw, kmsListApi(this.$route), this.$store.getters, this.pageSize)
      this.$router.push({ query })
    },
    changeFilter (filter) {
      if (this.isAccountList) {
        return this.$router.push({ query: accountQuery({ ...this.$route.query, state: filter, page: '1' }) })
      }
      if (isAclListRoute(this.$route)) {
        if (this.aclDisplayFilterId && aclDisplayValue(filter)) this.$router.push({ query: { ...this.$route.query, fordisplay: filter, page: '1' } })
        return
      }
      if (this.s2sDisplayFilterId) { this.onSearch({ s2sGatewaySearch: { ...this.$route.query, fordisplay: filter } }); return }
      if (asnRoute(this.$route)) { const query = { ...this.$route.query, filter }; delete query.isallocated; this.onSearch(query); return }
      if (publicIpRoute(this.$route)) {
        const query = { ...this.$route.query, filter }; delete query.state
        if (filter !== 'all') query.state = filter
        this.onSearch(query); return
      }
      if (vnfRoute(this.$route)) {
        const query = { ...this.$route.query }; delete query.state; delete query.filter
        if (filter !== 'all') query.state = vnfStates(this.$store.getters).find(s => s.toLowerCase() === filter) || filter
        this.onSearch({ ...query }); return
      }
      if (this.isIsoList) {
        this.$router.push({ query: isoListQuery({ ...this.$route.query, filter, isofilter: filter, page: '1' }, this.pageSize, this.$store.getters) })
        return
      }
      if (this.isTemplateList) {
        this.$router.push({ query: templateListQuery({ ...this.$route.query, filter, templatefilter: filter, page: '1' }, this.pageSize) })
        return
      }
      const query = Object.assign({}, this.$route.query)
      delete query.templatefilter
      delete query.isofilter
      delete query.state
      delete query.annotationfilter
      delete query.leased
      if (!['publicip', 'vmsnapshot'].includes(this.$route.name)) {
        delete query.account
        delete query.domainid
      }
      if (this.$route.name === 'template') {
        query.templatefilter = filter
      } else if (this.$route.name === 'iso') {
        query.isofilter = filter
      } else if (this.$route.name === 'volume') {
        if (filter === 'all') {
          query.listsystemvms = true
        } else {
          delete query.listsystemvms
        }
      } else if (this.$route.name === 'guestnetwork') {
        if (filter === 'all') {
          delete query.networkfilter
        } else {
          query.networkfilter = filter
        }
      } else if (['account', 'publicip', 'systemvm', 'router', 'runtimebundle'].includes(this.$route.name)) {
        if (filter !== 'all') {
          query.state = filter
        }
      } else if (this.$route.name === 'storagepool') {
        if (filter === 'all') {
          delete query.status
        } else {
          query.status = filter
        }
      } else if (['pod', 'cluster'].includes(this.$route.name)) {
        if (filter === 'all') {
          delete query.allocationstate
        } else {
          query.allocationstate = filter
        }
      } else if (this.$route.name === 'alertRules') {
        if (filter === 'all') {
          delete query.state
        } else {
          query.state = String(filter).toUpperCase()
        }
      } else if (['host'].includes(this.$route.name)) {
        if (filter === 'all') {
          delete query.resourcestate
          delete query.state
        } else if (['up', 'down', 'disconnected', 'alert'].includes(filter)) {
          delete query.resourcestate
          query.state = filter
        } else {
          delete query.state
          query.resourcestate = filter
        }
      } else if (this.$route.name === 'vm') {
        if (filter === 'self') {
          query.account = this.$store.getters.userInfo.account
          query.domainid = this.$store.getters.userInfo.domainid
        } else if (['running', 'stopped'].includes(filter)) {
          query.state = filter
        } else if (filter === 'leased') {
          query.leased = true
        }
      } else if (this.$route.name === 'vmsnapshot') {
        if (filter !== 'all') query.state = filter[0].toUpperCase() + filter.slice(1)
      } else if (this.$route.name === 'comment') {
        query.annotationfilter = filter
      } else if (this.$route.name === 'guestvlans') {
        if (filter === 'all') {
          query.allocatedonly = 'false'
        } else if (filter === 'allocatedonly') {
          query.allocatedonly = 'true'
        }
      } else if (this.$route.name === 'asnumbers') {
        if (['allocatedonly', 'free'].includes(filter)) {
          query.isallocated = (filter === 'allocatedonly')
        } else {
          delete query.isallocated
        }
      } else if (this.$route.name === 'event') {
        if (filter === 'archived') {
          query.archived = 'true'
        } else if (this.isEventTimeline && filter === 'active') {
          query.archived = 'false'
        } else {
          delete query.archived
        }
      } else if (this.$route.name === 'guestoshypervisormapping') {
        if (filter === 'all') {
          delete query.hypervisor
        } else {
          query.hypervisor = filter
        }
      } else if (this.$route.name === 'kubernetes') {
        if (filter === 'all') {
          delete query.clustertype
        } else {
          query.clustertype = filter === 'cloud.managed' ? 'CloudManaged' : 'ExternalManaged'
        }
      } else if (['computeoffering', 'systemoffering', 'diskoffering'].includes(this.$route.name)) {
        query.state = filter
      } else if (['extension'].includes(this.$route.name)) {
        if (filter === 'all') {
          delete query.type
        } else {
          query.type = filter
        }
      }
      query.filter = filter
      query.page = '1'
      query.pagesize = this.pageSize.toString()
      this.$router.push({ query })
    },
    changeProjectState (state) {
      const query = projectSearchQuery({ ...this.$route.query, state, page: '1' }, this.$store.getters)
      return this.$router.push({ query })
    },
    onSearch (opts) {
      if (this.isZoneList) {
        const query = zoneSearchQuery(this.$route.query, opts || {}, this.pageSize)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (this.isDnsServer && !this.dataView) {
        const query = dnsServerSearchQuery(this.$route.query, opts || {}, this.pageSize)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (this.isDnsZone && !this.dataView) {
        const query = dnsZoneSearchQuery(this.$route.query, opts || {}, this.pageSize)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (ipv4SubnetRoute(this.$route) && !this.dataView) {
        const query = ipv4SubnetSearchQuery(this.$route.query, opts || {}, this.pageSize)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (this.isAccountList) {
        const query = applyAccountSearch(this.$route.query, opts || {}, this.pageSize)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (this.isRoleList) {
        const query = applyRoleSearch(this.$route.query, opts || {}, this.pageSize)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (guestVlanRoute(this.$route) && !this.dataView) {
        const query = guestVlanSearchQuery(this.$route.query, opts || {}, this.pageSize)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (this.isProjectList) {
        this.resetSelection()
        if (this.$refs.listview) this.$refs.listview.resetSelection()
        const query = applyProjectSearch({ pagesize: String(this.pageSize), ...this.$route.query }, opts, this.$store.getters)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (this.isVpnUser && !this.dataView) {
        const query = vpnUserSearchQuery(this.$route.query, opts || {}, this.pageSize, this.$store.getters)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (isAclListRoute(this.$route) && !this.dataView) {
        const query = aclListSearchQuery(this.$route.query, opts || {}, this.pageSize, this.$store.getters)
        this.resetSelection()
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData()
        return this.$router.push({ query })
      }
      if (this.isEventList) {
        const query = eventSearchQuery(this.$route.query, opts)
        this.searchParams = {}
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData({ irefresh: true })
        return this.$router.push({ query })
      }
      if (kmsListRoute(this.$route)) {
        const query = kmsSearchQuery(this.$route.query, opts || {}, kmsListApi(this.$route), this.$store.getters, this.pageSize)
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData({ irefresh: true })
        else this.$router.push({ query })
        return
      }
      if (this.isS2sSearchList) {
        const query = s2sGatewaySearchQuery(this.$route.query, opts || {}, this.pageSize, this.$store.getters, this.s2sSearchApi)
        this.searchParams = {}; this.resetSelection(); this.$refs.listview && this.$refs.listview.resetSelection()
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData()
        else this.$router.push({ query })
        return
      }
      if (asnRoute(this.$route)) {
        const query = asnSearchQuery(this.$route.query, opts?.asnSearch || opts || {}, this.pageSize, this.$store.getters)
        this.searchParams = {}; this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection()
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData()
        else this.$router.push({ query })
        return
      }
      if (publicIpRoute(this.$route) && !this.$route.params?.id) {
        const query = publicIpSearchQuery(this.$route.query, opts, this.pageSize, this.$store.getters)
        this.searchParams = {}; this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection()
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData()
        else this.$router.push({ query })
        return
      }
      if (this.$route.meta?.name === 'kubernetesiso' && !this.$route.params?.id) {
        const query = kubernetesIsoSearchQuery(this.$route.query, opts || {}, this.pageSize, this.$store.getters, this.$store.state?.user?.token)
        this.searchParams = {}; this.resetSelection()
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) return this.fetchData()
        return this.$router.push({ query })
      }
      if (vnfRoute(this.$route) && !this.$route.params?.id) {
        const query = vnfSearchQuery(this.$route.query, opts, this.pageSize, this.$store.getters)
        this.searchParams = {}; this.resetSelection(); if (this.$refs.listview) this.$refs.listview.resetSelection()
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData()
        else this.$router.push({ query })
        return
      }
      if (this.isIsoList) {
        const query = isoSearchQuery(this.$route.query, opts || {}, this.pageSize, this.$store.getters)
        this.searchParams = {}
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData(query)
        else this.$router.push({ query })
        return
      }
      if (this.$route.meta?.name === 'template' && !this.$route.params?.id) {
        const query = templateSearchQuery(this.$route.query, opts || {}, this.pageSize)
        this.searchParams = {}
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData(query)
        else this.$router.push({ query })
        return
      }
      if (guestNetworkRoute(this.$route) && !this.$route.params?.id) {
        const query = networkSearchQuery(this.$route.query, opts, this.pageSize)
        this.searchParams = {}
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData(query)
        else this.$router.push({ query })
        return
      }
      if (snapshotPolicyListRoute(this.$route) && opts && Object.prototype.hasOwnProperty.call(opts, 'policySearch')) {
        const query = snapshotPolicyListQuery(policySearchQuery(this.$route.query, opts.policySearch, this.pageSize))
        this.searchParams = {}
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData(query)
        else this.$router.push({ query })
        return
      }
      if (this.$route.name === 'snapshot' && opts && Object.prototype.hasOwnProperty.call(opts, 'snapshotSearch')) {
        const query = storageSnapshotListQuery(snapshotSearchQuery(this.$route.query, opts.snapshotSearch, this.pageSize))
        this.searchParams = {}
        if (JSON.stringify(query) === JSON.stringify(this.$route.query)) this.fetchData(query)
        else this.$router.push({ query })
        return
      }
      const query = Object.assign({}, this.$route.query)
      const snapshotKeywordSearch = (this.$route.name === 'snapshot' || snapshotPolicyListRoute(this.$route)) && opts && Object.prototype.hasOwnProperty.call(opts, 'searchQuery')
      let searchFilters = this.$route?.meta?.searchFilters || []
      if (typeof searchFilters === 'function') {
        searchFilters = searchFilters()
      }
      if (Array.isArray(searchFilters) && !snapshotKeywordSearch) {
        searchFilters.forEach(key => delete query[key])
      }
      if (!snapshotKeywordSearch) {
        delete query.name
        delete query.templatetype
      }
      delete query.keyword
      delete query.q
      this.searchParams = {}
      if (opts && Object.keys(opts).length > 0) {
        this.searchParams = opts
        if ('searchQuery' in opts) {
          const value = opts.searchQuery
          if (value && value.length > 0) {
            if (this.$route.name === 'role') {
              query.keyword = value
            } else if (this.$route.name === 'quotaemailtemplate') {
              query.templatetype = value
            } else if (this.$route.name === 'globalsetting') {
              query.name = value
            } else if (this.$route.name === 'guestoshypervisormapping') {
              query.hypervisor = value
            } else if (this.$route.name === 'guestos') {
              query.description = value
            } else if (this.$route.name === 'oauthsetting') {
              query.provider = value
            } else {
              query.keyword = value
            }
            query.q = value
          }
          this.searchParams = {}
        } else {
          Object.assign(query, opts)
        }
      }
      query.page = '1'
      query.pagesize = String(this.pageSize)
      if (JSON.stringify(query) === JSON.stringify(this.$route.query)) {
        this.fetchData(query)
        return
      }
      this.$router.push({ query })
    },
    changePage (page, pageSize) {
      const query = Object.assign({}, this.$route.query)
      query.page = (this.isZoneList || this.isRoleList || this.isProjectList || ['template', 'iso', 'cniconfiguration', 'guestnetwork', 'vnfapp', 'asnumbers', 'kubernetesiso', 'acllist', 'vpnuser', 'guestvlans', 'ipv4subnets', 'dnszone', 'dnsserver'].includes(this.$route.meta?.name) || this.$route.name === 'snapshot' || snapshotPolicyListRoute(this.$route) || vpnConnectionListRoute(this.$route)) && Number(pageSize) !== this.pageSize ? 1 : page
      query.pagesize = pageSize
      this.$router.push({ query: this.isRoleList ? roleSearchQuery(query) : this.isAccountList ? accountQuery(query) : query })
    },
    handleTableChange (pagination, filters, sorter) {
      if (this.isZoneList) {
        const sortorder = sorter?.order === 'ascend' ? 'asc' : sorter?.order === 'descend' ? 'desc' : undefined
        const query = zoneListQuery({ ...this.$route.query, sortkey: sortorder && (sorter.columnKey || sorter.field), sortorder })
        return this.$router.push({ query })
      }
      if (this.isAccountList) {
        const sortorder = sorter?.order === 'ascend' ? 'asc' : sorter?.order === 'descend' ? 'desc' : undefined
        return this.$router.replace({ query: accountQuery({ ...this.$route.query, sortkey: sorter?.columnKey, sortorder }) })
      }
      if (this.isRoleList) {
        const sortorder = sorter?.order === 'ascend' ? 'asc' : sorter?.order === 'descend' ? 'desc' : undefined
        return this.$router.replace({ query: roleSearchQuery({ ...this.$route.query, sortkey: sorter?.columnKey, sortorder }) })
      }
      if (this.isProjectList) {
        const query = { ...this.$route.query, sortkey: sorter?.columnKey, sortorder: sorter?.order === 'ascend' ? 'asc' : sorter?.order === 'descend' ? 'desc' : undefined }
        return this.$router.push({ query: projectSearchQuery(query, this.$store.getters) })
      }
      if (this.eventCreatedSortEnabled && (sorter?.field === 'created' || sorter?.columnKey === 'created')) {
        const query = eventCreatedSortQuery(this.$route.query, sorter.order)
        return this.$router.push({ query })
      }
      if (this.$route.meta?.name === 'kubernetesiso' && !this.$route.params?.id) {
        const query = { ...this.$route.query, page: '1' }; delete query.sortkey; delete query.sortorder
        if (sorter?.order && kubernetesIsoSortFields.includes(sorter.field)) { query.sortkey = sorter.field; query.sortorder = sorter.order === 'ascend' ? 'asc' : 'desc' }
        return this.$router.push({ query: kubernetesIsoListQuery(query, this.pageSize, this.$store.getters, this.$store.state?.user?.token) })
      }
      if (this.isIsoList) {
        const query = { ...this.$route.query }; delete query.sortkey; delete query.sortorder
        if (sorter?.order) { query.sortkey = sorter.field; query.sortorder = sorter.order === 'ascend' ? 'asc' : 'desc' }
        this.$router.replace({ query: isoListQuery(query, this.pageSize, this.$store.getters) })
        return
      }
      if (snapshotPolicyListRoute(this.$route)) {
        this.$router.push({ query: snapshotPolicySortQuery(this.$route.query, sorter, this.pageSize) })
        return
      }
      if (this.$route.name === 'snapshot') {
        this.$router.push({ query: storageSnapshotSortQuery(this.$route.query, sorter, this.pageSize) })
        return
      }
      if (['vmgroup', 'cniconfiguration', 'guestnetwork'].includes(this.$route.meta?.name)) {
        const query = { ...this.$route.query }
        if (sorter?.order) { query.sortkey = sorter.field; query.sortorder = sorter.order === 'ascend' ? 'asc' : 'desc' } else { delete query.sortkey; delete query.sortorder }
        this.$router.push({ query: this.$route.meta?.name === 'vmgroup' ? vmGroupQuery(query) : query })
        return
      }
      if (this.$route.name !== 'vmsnapshot') return
      this.$router.push({ query: { ...this.$route.query, page: '1', pagesize: String(this.pageSize), sortkey: sorter?.order ? sorter.field : 'created', sortorder: sorter?.order === 'ascend' ? 'asc' : 'desc' } })
    },
    onListPageSizeChange (currentPage, pageSize) {
      if (!this.isProjectList && !this.isRoleList) this.changePageSize(currentPage, pageSize)
    },
    changePageSize (currentPage, pageSize) {
      const query = Object.assign({}, this.$route.query)
      query.page = (this.isZoneList || ['template', 'iso', 'cniconfiguration', 'guestnetwork', 'vnfapp', 'asnumbers', 'kubernetesiso', 'acllist', 'vpnuser', 'guestvlans', 'ipv4subnets'].includes(this.$route.meta?.name) || this.$route.name === 'snapshot' || snapshotPolicyListRoute(this.$route)) ? 1 : currentPage
      query.pagesize = pageSize
      this.$router.push({ query })
    },
    changeResource (resource) {
      this.resource = resource
    },
    start () {
      this.loading = true
      this.fetchData()
      setTimeout(() => {
        this.loading = false
        this.selectedRowKeys = []
      }, 1000)
    },
    toggleLoading () {
      this.loading = !this.loading
    },
    startLoading () {
      this.loading = true
    },
    finishLoading () {
      this.loading = false
    },
    handleConfirmBlur (e, name) {
      if (name !== 'confirmpassword') {
        return
      }
      const value = e.target.value
      this.confirmDirty = this.confirmDirty || !!value
    },
    async validateTwoPassword (rule, value) {
      if (!value || value.length === 0) {
        return Promise.resolve()
      } else if (rule.field === 'confirmpassword') {
        const messageConfirm = this.$t('message.validate.equalto')
        const passwordVal = this.form.password
        if (passwordVal && passwordVal !== value) {
          return Promise.reject(messageConfirm)
        } else {
          return Promise.resolve()
        }
      } else if (rule.field === 'password') {
        const confirmPasswordVal = this.form.confirmpassword
        if (!confirmPasswordVal || confirmPasswordVal.length === 0) {
          return Promise.resolve()
        } else if (value && this.confirmDirty) {
          this.formRef.value.validateFields('confirmpassword')
          return Promise.resolve()
        } else {
          return Promise.resolve()
        }
      } else {
        return Promise.resolve()
      }
    },
    setRules (field) {
      let rule = {}

      if (!field || Object.keys(field).length === 0) {
        return
      }

      if (!this.rules[field.name]) {
        this.rules[field.name] = []
      }

      switch (true) {
        case (field.name === 'name' && isVmGroupNameAction(this.currentAction.api)):
          rule.validator = (_, value) => {
            try {
              normalizeVmGroupName(value, this.currentAction.api === 'updateInstanceGroup' ? this.resource?.name : undefined)
              return Promise.resolve()
            } catch (error) { return Promise.reject(this.$t(error.message)) }
          }
          this.rules[field.name].push(rule)
          break
        case (field.type === 'boolean'):
          rule.required = field.required
          rule.message = this.$t('message.error.required.input')
          this.rules[field.name].push(rule)
          break
        case (this.currentAction.mapping && field.name in this.currentAction.mapping && 'options' in this.currentAction.mapping[field.name]):
          rule.required = field.required
          rule.message = this.$t('message.error.select')
          this.rules[field.name].push(rule)
          break
        case (field.name === 'keypair' || (field.name === 'account' && !['addAccountToProject', 'createAccount'].includes(this.currentAction.api))):
          rule.required = field.required
          rule.message = this.$t('message.error.select')
          this.rules[field.name].push(rule)
          break
        case (field.type === 'uuid'):
          rule.required = field.required
          rule.message = this.$t('message.error.select')
          this.rules[field.name].push(rule)
          break
        case (field.type === 'list'):
          rule.type = 'array'
          rule.required = field.required
          rule.message = this.$t('message.error.select')
          this.rules[field.name].push(rule)
          break
        case (field.type === 'long'):
          rule.type = 'number'
          rule.required = field.required
          rule.message = this.$t('message.validate.number')
          this.rules[field.name].push(rule)
          break
        case (field.name === 'password' || field.name === 'currentpassword' || field.name === 'confirmpassword'):
          rule.required = field.required
          rule.message = this.$t('message.error.required.input')
          this.rules[field.name].push(rule)

          rule = {}
          rule.validator = this.validateTwoPassword
          this.rules[field.name].push(rule)
          break
        case (field.name === 'certificate' || field.name === 'privatekey' || field.name === 'certchain'):
          rule.required = field.required
          rule.message = this.$t('message.error.required.input')
          this.rules[field.name].push(rule)
          break
        default:
          rule.required = field.required
          rule.message = this.$t('message.error.required.input')
          this.rules[field.name].push(rule)
          break
      }

      rule = {}
    },
    setModalWidthByScreen () {
      const screenWidth = window.innerWidth
      if (screenWidth <= 768) {
        this.modalWidth = '450px'
      }
    },
    copyToClipboard (txt) {
      const parent = this
      this.$copyText(txt, document.body, function (err) {
        if (!err) {
          parent.$message.success(parent.$t('label.copied.clipboard'))
        }
      })
    }
  }
}
</script>

<style scoped src="@/style/components/view/list-toolbar.css" />

<style scoped src="@/style/components/view/vm-acl-standard.css" />
<style scoped src="@/style/components/view/project-standard.css" />
<style src="@/style/components/view/s2s-connection.css" />
<style scoped>
.vpn-user-read-error { margin-block-end: 12px; }
</style>
<style scoped src="@/style/components/view/public-ip-breadcrumb.css" />

<style scoped>
.vm-action-error { overflow-wrap: anywhere; margin-bottom: 16px; }
.runtime-catalog-screen :deep(.breadcrumb-card > .ant-card-body > .ant-row) { display:flex; flex-direction:column; gap:8px; }
.runtime-catalog-screen :deep(.breadcrumb-card .ant-col) { width:100%; max-width:100%; float:none !important; }
.runtime-catalog-screen :deep(.autogen-toolbar-actions) { display:flex !important; align-items:center; gap:8px; }
.runtime-catalog-screen :deep(.autogen-action-dropdown__trigger) { float:none !important; }

.kms-management-page { min-width: 0; max-width: 100%; }
.kms-display-filter { min-width: 100px; margin-inline-start: 8px; margin-bottom: 5px; }
.kms-management-page :deep(.ant-breadcrumb) { overflow-wrap: anywhere; }
.kms-management-page :deep(.breadcrumb-controls) { display: inline-flex; flex-wrap: wrap; gap: 4px; vertical-align: middle; }
@media (max-width: 767px) {
  .kms-management-page .breadcrumb-card { margin-inline: -12px; }
  .kms-list-page .kms-toolbar-search { flex-basis: 100%; }
}
.breadcrumb-card {
  margin-left: -24px;
  margin-right: -24px;
  margin-top: -16px;
  margin-bottom: 12px;
}

.row-element {
  margin-bottom: 10px;
}

.ant-breadcrumb {
  vertical-align: text-bottom;
}

:deep(.ant-switch-inner) {
  display: block;
  font-size: 14px;
  margin: 0px 14px 0px 28px;
}

:deep(.ant-alert-message) {
  display: flex;
  align-items: center;
}

.autogen-action-dropdown__trigger {
  display: inline-block;
}

.autogen-action-dropdown__button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.autogen-action-dropdown__content {
  width: 272px;
  max-height: ~"min(70vh, 640px)";
  overflow-x: hidden;
  overflow-y: auto;
  padding: 4px;
  background: var(--ui-bg-elevated);
  border: 1px solid var(--ui-border);
  border-radius: 6px;
  box-shadow: 0 8px 24px var(--ui-shadow);
}

.autogen-action-dropdown__content :deep(.row-action-button--dataview) {
  width: 100%;
  min-width: 0;
}

.hide {
  display: none !important;
}

.snapshot-list-page { min-width: 0; max-width: 100%; }
.snapshot-list-actions { display: flex !important; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; }
.snapshot-list-actions :deep(.row-action-button) { display: inline-flex !important; flex-wrap: wrap; align-items: center; gap: 8px; margin: 0 !important; }
.snapshot-list-actions :deep(.ant-btn) { margin: 0; }
.snapshot-list-actions :deep(.snapshot-search-control) { flex: 1 1 180px; min-width: 0; }
.snapshot-list-page :deep(.ant-pagination) { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
@media (max-width: 767px) {
  .snapshot-list-page .breadcrumb-card { margin-left: -12px; margin-right: -12px; }
}

@media (max-width: 767px) {
  .guest-network-page .breadcrumb-card { margin-left: -12px; margin-right: -12px; }
  .bucket-list-page .breadcrumb-card { margin-left: -12px; margin-right: -12px; }
  .bucket-list-page .autogen-toolbar-actions { display: flex !important; flex-wrap: wrap; align-items: center; gap: 8px; }
  .bucket-list-page .autogen-toolbar-actions :deep(.row-action-button) { display: inline-flex !important; flex-wrap: wrap; gap: 8px; margin: 0 !important; }
  .bucket-list-page .autogen-toolbar-actions > span:last-child { display: block !important; flex: 1 1 180px; width: auto !important; min-width: 0; }
}

</style>

<style scoped lang="less">
.template-list-page :deep(.ant-table-thead th) { white-space: nowrap; }
.template-list-page :deep(.ant-table-cell) { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.template-list-page :deep(.ant-breadcrumb) { overflow-wrap: anywhere; }
</style>

<style scoped>
.template-filter-accessible-label { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0,0,0,0); white-space:nowrap; border:0; }
</style>

<style scoped lang="less">
.iso-list-page { min-width: 0; max-width: 100%; }
.iso-list-page :deep(.ant-table-cell) { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.iso-list-page :deep(.ant-table-thead th) { white-space: nowrap; }
.iso-list-page :deep(.ant-breadcrumb) { overflow-wrap: anywhere; }
</style>

<style scoped>
.event-list-page :deep(.ant-table-cell) { white-space: nowrap; }
.event-list-page :deep(.ant-table-cell-ellipsis) { overflow: hidden; text-overflow: ellipsis; }
.event-list-page :deep(.autogen-toolbar-actions) { min-width: 0; }
</style>

<style scoped>
.role-standard-page :deep(.resource-details__name) { min-width: 0; }
.role-standard-page :deep(.resource-details__name h4) { word-break: normal; }
.role-standard-page :deep(.resource-details__name h4),
.role-standard-page :deep(.resource-detail-item),
.role-standard-page :deep(.resource-details-list),
.role-standard-page :deep(.ant-table-cell) { overflow-wrap: anywhere; }
.role-standard-page :deep(.resource-content-card),
.role-standard-page :deep(.ant-tabs-content-holder) { min-width: 0; }
</style>

<style scoped lang="less">
.account-standard-list .autogen-toolbar-actions {
  display: flex !important;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.account-standard-list .autogen-toolbar-actions :deep(.row-action-button) {
  display: inline-flex !important;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 !important;
}
.account-standard-list .account-toolbar-search { flex: 0 1 228px; min-width: 165px; }
.account-standard-page :deep(.resource-content-card),
.account-standard-page :deep(.ant-tabs-content-holder) { min-width: 0; }
.account-standard-page :deep(.resource-details__name h4),
.account-standard-page :deep(.resource-detail-item) { overflow-wrap: anywhere; }
.account-standard-page :deep(.account-settings-search) { width: 248px !important; max-width: 100%; }
@media (max-width: 767px) {
  .account-standard-page .breadcrumb-card { margin-left: -12px; margin-right: -12px; }
  .account-standard-list .autogen-toolbar-actions { justify-content: flex-start; }
  .account-standard-list .account-toolbar-search {
    display: block !important;
    flex: 1 1 100%;
    min-width: 0;
  }
}
</style>
