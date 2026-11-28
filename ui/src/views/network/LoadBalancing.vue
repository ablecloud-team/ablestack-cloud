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
  <div class="lb-backend-removal" :data-list-editing="lbBackendBusy">
    <network-rules-toolbar :can-add="'createLoadBalancerRule' in $store.getters.apis && !contextLoading" :loading="loading || contextLoading || listRefreshing > 0" @add="openCreateDialog" @refresh="refreshRules">
      <a-button
        v-if="(('deleteLoadBalancerRule' in $store.getters.apis) && this.selectedItems.length > 0)"
        type="primary"
        danger
        @click="bulkActionConfirmation()">
        <template #icon><delete-outlined /></template>
        {{ $t('label.action.bulk.delete.load.balancer.rules') }}
      </a-button>
      <slot name="toolbar-context" />
    </network-rules-toolbar>
    <a-alert v-if="listRefreshFailed" class="network-rules-guidance" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <slot name="guidance" />
    <MoldDialog
      class="network-rule-dialog"
      size="lg"
      focus-guard
      :visible="createModalVisible"
      :title="$t('label.network.rule.add')"
      :closable="!loading"
      :keyboard="!loading"
      @cancel="!loading && closeModal()">
      <div @keyup.ctrl.enter="handleAddNewRule">
        <NetworkRuleContext :resource="resource" />
        <a-alert
          v-if="creationFailed"
          type="error"
          show-icon
          class="mold-dialog-summary"
          :message="$t(creationUncertain ? 'message.public.ip.lb.uncertain' : 'message.network.lb.create.failed')" />
        <a-alert
          v-if="assignmentFailed"
          class="mold-dialog-summary"
          type="error"
          show-icon
          :message="$t('message.network.lb.retry')" />
        <div class="form">
          <div class="form__item form__item--full" ref="newRuleName">
            <div class="form__label"><span class="form__required">*</span>{{ $t('label.name') }}</div>
            <a-input v-focus="true" v-model:value="newRule.name"></a-input>
            <span class="error-text">{{ $t('label.required') }}</span>
          </div>
          <div class="form__item" ref="newRulePublicPort">
            <div class="form__label"><span class="form__required">*</span>{{ $t('label.publicport') }}</div>
            <a-input v-model:value="newRule.publicport"></a-input>
            <span class="error-text">{{ $t('label.required') }}</span>
          </div>
          <div class="form__item" ref="newRulePrivatePort">
            <div class="form__label"><span class="form__required">*</span>{{ $t('label.privateport') }}</div>
            <a-input v-model:value="newRule.privateport"></a-input>
            <span class="error-text">{{ $t('label.required') }}</span>
          </div>
        </div>
        <div class="form">
          <div class="form__item form__item--full" ref="newCidrList">
            <tooltip-label :title="$t('label.sourcecidrlist')" bold :tooltip="createLoadBalancerRuleParams.cidrlist.description" :tooltip-placement="'right'"/>
            <a-input v-model:value="newRule.cidrlist"></a-input>
          </div>
          <div class="form__item" v-if="lbProvider !== 'Netris'">
            <div class="form__label">{{ $t('label.algorithm') }}</div>
            <a-select
              v-model:value="newRule.algorithm"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option value="roundrobin" :label="$t('label.lb.algorithm.roundrobin')">{{ $t('label.lb.algorithm.roundrobin') }}</a-select-option>
              <a-select-option value="leastconn" :label="$t('label.lb.algorithm.leastconn')">{{ $t('label.lb.algorithm.leastconn') }}</a-select-option>
              <a-select-option value="source" :label="$t('label.lb.algorithm.source')">{{ $t('label.lb.algorithm.source') }}</a-select-option>
            </a-select>
          </div>
          <div class="form__item">
            <div class="form__label">{{ $t('label.protocol') }}</div>
            <a-select
              v-model:value="newRule.protocol"
              style="min-width: 100px"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option v-if="lbProvider !== 'Netris'" value="tcp-proxy" :label="$t('label.tcp.proxy')">{{ $t('label.tcp.proxy') }}</a-select-option>
              <a-select-option value="tcp" :label="$t('label.tcp')">{{ $t('label.tcp') }}</a-select-option>
              <a-select-option value="udp" :label="$t('label.udp')">{{ $t('label.udp') }}</a-select-option>
              <a-select-option value="ssl" :label="$t('label.ssl')">{{ $t('label.ssl') }}</a-select-option>
              <a-select-option value="http" :label="$t('label.lb.protocol.http')">{{ $t('label.lb.protocol.http') }}</a-select-option>
            </a-select>
          </div>
          <div class="form__item">
            <div class="form__label">{{ $t('label.autoscale') }}</div>
            <a-select
              v-model:value="newRule.autoscale"
              :disabled="loading || !!creationJob || !!selectedRule"
              defaultValue="no"
              style="min-width: 100px"
              showSearch
              optionFilterProp="value"
              :filterOption="(input, option) => {
                return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option value="yes">{{ $t('label.yes') }}</a-select-option>
              <a-select-option value="no">{{ $t('label.no') }}</a-select-option>
            </a-select>
          </div>
          <div class="form__item" v-if="newRule.protocol === 'ssl'" >
            <div class="form__label">{{ $t('label.sslcertificate') }}</div>
            <a-button :disabled="!sslSelectionAllowed" type="primary" @click="handleOpenAddSslCertModal(null)">
              {{ this.selectedSsl.id != null ? this.selectedSsl.name : $t('label.add') }}
            </a-button>
          </div>
          <div class="form__item" v-if="newRule.protocol === 'ssl'">
            <div class="form__label">{{ $t('label.backend.ssl') }}</div>
            <a-switch v-model:checked="newRule.backendssl" />
          </div>

        </div>
        <a-alert v-if="newRule.autoscale === 'yes'" class="mold-dialog-section" type="info" show-icon :message="$t('message.network.lb.autoscale.create')" />
        <PublicLbTierSelect
          v-if="publicTierRequired"
          :state="tiers"
          :value="lbTargetNetworkId"
          :vpc-name="vpcName || resource.vpcname || resource.vpcid"
          :project-name="resource.project || resource.projectid"
          :hide-select="newRule.autoscale === 'yes' && !resource.associatednetworkid"
          :fixed="!!selectedRule || (!!resource.associatednetworkid && !vpcConserveMode)"
          :busy="loading || contextLoading"
          @change="selectPublicTier"
          @refresh="fetchListTiers" />
        <div v-if="newRule.autoscale !== 'yes'" class="mold-dialog-section">
          <div class="network-form-group-label">{{ $t('label.network.lb.targets') }}</div>
          <a-radio-group v-model:value="targetMode" @change="targetModeChanged">
            <a-radio-button value="select" :disabled="!('assignToLoadBalancerRule' in $store.getters.apis)">{{ $t('label.network.lb.select') }}</a-radio-button>
            <a-radio-button value="later">{{ $t('label.network.lb.later') }}</a-radio-button>
          </a-radio-group>
          <a-alert
            class="mold-dialog-section"
            type="info"
            show-icon
            :message="$t(targetMode === 'later' ? 'message.network.lb.later' : 'message.network.lb.targets')" />
          <div v-if="targetMode === 'select'" class="mold-dialog-section">
            <a-input-search
              v-focus="!('vpcid' in resource && !('associatednetworkid' in resource))"
              class="input-search"
              :placeholder="$t('label.search')"
              v-model:value="searchQuery"
              allowClear
              @search="onSearch" />
            <a-table
              size="small"
              class="lb-target-list"
              :loading="addVmModalLoading"
              :columns="vmColumns"
              :dataSource="vms"
              :pagination="false"
              :rowKey="record => record.id"
              :scroll="{ x: 400 }">
              <template #bodyCell="{ column, text, record }">
                <template v-if="column.key === 'name'">
                  <span>
                    {{ text }}
                  </span>
                  <loading-outlined v-if="addVmModalNicLoading" />
                  <a-select
                    style="display: block"
                    v-else-if="!addVmModalNicLoading && selectedBackends[record.id]"
                    mode="multiple"
                    v-model:value="selectedBackends[record.id].ips"
                    showSearch
                    optionFilterProp="label"
                    :filterOption="(input, option) => {
                      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                    }" >
                    <a-select-option
                      v-for="(nic, nicIndex) in selectedBackends[record.id].options"
                      :key="nic"
                      :value="nic"
                      :label="nic + (nicIndex === 0 ? ' (' + $t('label.primary') + ')' : '')">
                      {{ nic }}{{ nicIndex === 0 ? ` (${$t('label.primary')})` : null }}
                    </a-select-option>
                  </a-select>
                </template>

                <template v-if="column.key === 'state'">
                  <status :text="text ? text : ''" displayText></status>
                </template>

                <template v-if="column.key === 'actions'" style="text-align: center" :text="text">
                  <a-checkbox :disabled="loading || publicTierSubmissionBlocked" :checked="!!selectedBackends[record.id]" :value="record.id" @change="e => fetchNics(e, record.id)" />
                </template>
              </template>
            </a-table>
            <a-pagination
              class="detail-tab-pagination"
              size="small"
              :current="vmPage"
              :pageSize="vmPageSize"
              :total="vmCount"
              :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
              :pageSizeOptions="['10', '20', '40', '80', '100']"
              @change="handleChangeVmPage"
              @showSizeChange="handleChangeVmPageSize"
              showSizeChanger>
              <template #buildOptionText="props">
                <span>{{ props.value }} / {{ $t('label.page') }}</span>
              </template>
            </a-pagination>
          </div>
        </div>
      </div>
      <template #footer>
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="loading" :disabled="ruleSubmissionBlocked || !('createLoadBalancerRule' in $store.getters.apis)" @click="handleAddNewRule">
          {{ $t(needsAutoScaleTier ? 'label.select.tier' : 'label.network.rule.add') }}
        </a-button>
      </template>
    </MoldDialog>
    <network-mutation-status :operation="lbBackendOperation" @recheck="recheckLbBackendRemoval" />
    <detail-tab-table
:enabled="detailViewport"
      size="small"
      class="list-view"
      v-show="!lbBackendSuspended"
      :loading="loading"
      :columns="compactColumns"
      :scroll="{ x: 1080 }"
      :dataSource="lbRules"
      :pagination="false"
      :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange, getCheckboxProps: record => ({ disabled: isProtectedRule(record) })}"
      :rowKey="record => record.id"
      :expandRowByClick="true">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">{{ record.name }} <a-tag v-if="ruleOwners[record.id]">{{ $t('label.kubernetes.lb.' + ruleOwners[record.id].kind) }}</a-tag></template>
        <template v-if="column.key === 'cidrlist'">
          <span style="white-space: pre-line"> {{ record.cidrlist?.replaceAll(",", "\n") }}</span>
        </template>
        <template v-if="column.key === 'algorithm'">
          {{ returnAlgorithmName(record.algorithm) }}
        </template>
        <template v-if="column.key === 'protocol'">
          {{ getCapitalise(record.protocol) }}
        </template>
        <template v-if="column.key === 'backendssl'">
          {{ record.protocol === 'ssl' && record.backendssl ? $t('label.yes') : $t('label.no') }}
        </template>
        <template v-if="column.key === 'ports'">{{ record.publicport }} / {{ record.privateport }}</template>
        <template v-if="column.key === 'policy'">{{ returnAlgorithmName(record.algorithm) }}<br><span class="network-rule-secondary">{{ getCapitalise(record.protocol) }}</span></template>
        <template v-if="column.key === 'targets'"><div class="network-rule-targets"><router-link v-for="entry in (record.ruleInstances || []).slice(0, 2)" :key="entry.loadbalancerruleinstance.id" :to="'/vm/' + entry.loadbalancerruleinstance.id">{{ entry.loadbalancerruleinstance.displayname || entry.loadbalancerruleinstance.name }}</router-link><span v-if="record.ruleInstances?.length > 2">+{{ record.ruleInstances.length - 2 }}</span><span v-if="!record.ruleInstances?.length">{{ $t('label.network.lb.later') }}</span></div></template>
        <template v-if="column.key === 'actions'">
          <div class="network-rule-actions">
            <a-dropdown :trigger="['click']" :disabled="isProtectedRule(record)" :visible="settingsRuleId === record.id" @visibleChange="visible => settingsRuleId = visible ? record.id : null">
              <a-button :disabled="isProtectedRule(record)">{{ $t('label.settings') }}<down-outlined /></a-button>
              <template #overlay><a-menu @click="closeSettingsMenu">
                <a-menu-item :disabled="!('updateLoadBalancerRule' in $store.getters.apis)" @click="openEditRuleModal(record)">{{ $t('label.edit') }}</a-menu-item>
                <a-menu-item :disabled="!!record.autoscalevmgroup || !('assignToLoadBalancerRule' in $store.getters.apis)" @click="selectedRule = record; handleOpenAddVMModal()">{{ $t('label.add.vms') }}</a-menu-item>
                <a-menu-item v-if="!isNetrisZone" :disabled="!canConfigureStickiness(record)" @click="openStickinessModal(record.id)">{{ $t('label.action.configure.stickiness') }}</a-menu-item>
                <a-menu-item :disabled="record.protocol !== 'ssl' || !('listSslCerts' in $store.getters.apis) || publicContextSuspended" @click="handleOpenAddSslCertModal(record)">{{ $t('label.sslcertificate') }}</a-menu-item>
                <a-menu-item v-if="record.autoscalevmgroup"><router-link :to="'/autoscalevmgroup/' + record.autoscalevmgroup.id">{{ $t('label.autoscale') }}</router-link></a-menu-item>
                <a-menu-item v-else-if="!record.ruleInstances?.length && 'createAutoScaleVmGroup' in $store.getters.apis"><router-link :to="{ path: '/action/createAutoScaleVmGroup', query: { networkid: record.networkid, lbruleid: record.id } }">{{ $t('label.autoscale') }}</router-link></a-menu-item>
                <a-menu-item v-if="columns.some(column => column.key === 'healthmonitor')" @click="openHealthMonitorModal(record.id)">{{ $t('label.action.health.monitor') }}</a-menu-item>
              </a-menu></template>
            </a-dropdown>
            <tooltip-button :tooltip="$t('label.edit.tags')" :disabled="isProtectedRule(record) || !('createTags' in $store.getters.apis)" icon="tag-outlined" @onClick="() => openTagsModal(record)" />
            <a-popconfirm
              :title="$t('label.delete') + '?'"
              @confirm="handleDeleteRule(record)"
              :okText="$t('label.yes')"
              :cancelText="$t('label.no')"
            >
              <tooltip-button
                :tooltip="$t('label.delete')"
                :disabled="isProtectedRule(record) || !('deleteLoadBalancerRule' in $store.getters.apis)"
                type="primary"
                :danger="true"
                icon="delete-outlined" />
            </a-popconfirm>
          </div>
        </template>
      </template>
      <template #expandedRowRender="{ record }">
        <div class="rule-instance-list">
          <div v-for="instance in record.ruleInstances" :key="instance.loadbalancerruleinstance.id">
            <div v-for="ip in instance.lbvmipaddresses" :key="ip" class="rule-instance-list__item">
              <div>
                <status :text="instance.loadbalancerruleinstance.state" />
                <desktop-outlined />
                <router-link :to="{ path: '/vm/' + instance.loadbalancerruleinstance.id }">
                  {{ instance.loadbalancerruleinstance.displayname }}
                </router-link>
              </div>
              <div v-if="this.vpcConserveMode">
                <router-link :to="{ path: '/guestnetwork/' + instance.loadbalancerruleinstance.nic[0].networkid }">
                  {{ instance.loadbalancerruleinstance.nic[0].networkname }}
                </router-link>
              </div>
              <div>{{ ip }}</div>
              <tooltip-button
                :disabled="!canRemoveRuleBackend(record, instance, ip)"
                :loading="lbBackendBusy && lbBackendOperation.params.id === record.id && lbBackendOperation.params['vmidipmap[0].vmip'] === ip"
                :tooltip="$t('label.remove.vm.from.lb')"
                type="primary"
                :danger="true"
                icon="delete-outlined"
                @onClick="() => handleDeleteInstanceFromRule(instance, record, ip)" />
            </div>
          </div>
        </div>
      </template>
    </detail-tab-table>
    <detail-tab-pagination
:enabled="detailViewport"
      class="detail-tab-pagination"
      size="small"
      :current="page"
      :pageSize="pageSize"
      :total="totalCount"
      :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
      :pageSizeOptions="['10', '20', '40', '80', '100']"
      @change="handleChangePage"
      @showSizeChange="handleChangePageSize"
      showSizeChanger>
      <template #buildOptionText="props">
        <span>{{ props.value }} / {{ $t('label.page') }}</span>
      </template>
    </detail-tab-pagination>

    <RuleTagsDialog
      v-if="tagRule && tagRule.parentId === resource.id"
      :resource="resource"
      :rule="tagRule"
      resource-type="LoadBalancer"
      @cancel="tagRule = null" />

    <LoadBalancerStickinessDialog
      v-if="stickinessRule"
      ref="stickinessDialog"
      :visible="stickinessModalVisible"
      :resource="{ ...resource, associatednetworkid: stickinessRule.networkid || resource.associatednetworkid }"
      :rule="stickinessRule"
      :protected-rule="isProtectedRule(stickinessRule)"
      @cancel="stickinessModalVisible = false"
      @invalidated="invalidateStickinessDialog"
      @refresh="fetchData" />

    <MoldDialog
class="network-rule-dialog"
size="md"
      :title="$t('label.edit.rule')"
      :visible="editRuleModalVisible"

      :closable="!networkMutationBusy"

      @cancel="closeModal">
      <network-mutation-status :operation="networkMutation" @recheck="recheckNetworkMutation" />

      <div class="edit-rule" v-if="selectedRule" v-ctrl-enter="handleSubmitEditForm">
        <div class="edit-rule__item">
          <p class="edit-rule__label">{{ $t('label.name') }}</p>
          <a-input v-focus="true" v-model:value="editRuleDetails.name" :aria-label="$t('label.name')" :disabled="networkMutationBusy" />
        </div>
        <div v-if="lbProvider !== 'Netris'" class="edit-rule__item">
          <p class="edit-rule__label">{{ $t('label.algorithm') }}</p>
          <a-select
            v-model:value="editRuleDetails.algorithm"
            :aria-label="$t('label.algorithm')"
            :disabled="networkMutationBusy"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option value="roundrobin" :label="$t('label.lb.algorithm.roundrobin')">{{ $t('label.lb.algorithm.roundrobin') }}</a-select-option>
            <a-select-option value="leastconn" :label="$t('label.lb.algorithm.leastconn')">{{ $t('label.lb.algorithm.leastconn') }}</a-select-option>
            <a-select-option value="source" :label="$t('label.lb.algorithm.source')">{{ $t('label.lb.algorithm.source') }}</a-select-option>
          </a-select>
        </div>
        <div v-if="lbProvider !== 'Netris'" class="edit-rule__item">
          <p class="edit-rule__label">{{ $t('label.protocol') }}</p>
          <a-select
            v-model:value="editRuleDetails.protocol"
            :aria-label="$t('label.protocol')"
            :disabled="networkMutationBusy"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option value="tcp-proxy" :label="$t('label.tcp.proxy')">{{ $t('label.tcp.proxy') }}</a-select-option>
            <a-select-option value="tcp" :label="$t('label.tcp')">{{ $t('label.tcp') }}</a-select-option>
            <a-select-option value="udp" :label="$t('label.udp')">{{ $t('label.udp') }}</a-select-option>
            <a-select-option value="ssl" :label="$t('label.ssl')">{{ $t('label.ssl') }}</a-select-option>
            <a-select-option value="http" :label="$t('label.lb.protocol.http')">{{ $t('label.lb.protocol.http') }}</a-select-option>
          </a-select>
        </div>
        <div v-if="lbProvider !== 'Netris'" class="edit-rule__item">
          <p class="edit-rule__label">
            {{ $t('label.sourcecidrlist') }}
            <tooltip-label
              :title="''"
              bold
              :tooltip="createLoadBalancerRuleParams.cidrlist.description || 'Enter a comma-separated list of CIDR blocks.'"
              :tooltip-placement="'right'"
              style="display: inline; margin-left: 5px;"
            />
          </p>
          <a-input
            v-model:value="editRuleDetails.cidrlist"
            :disabled="networkMutationBusy"
            :placeholder="$t('label.sourcecidrlist')"
          />
        </div>
        <div class="edit-rule__item" v-if="editRuleDetails.protocol === 'ssl'">
          <p class="edit-rule__label">{{ $t('label.backend.ssl') }}</p>
          <a-switch v-model:checked="editRuleDetails.backendssl" :aria-label="$t('label.backend.ssl')" :disabled="networkMutationBusy" />
        </div>
      </div>
      <template #footer>
          <a-button :disabled="networkMutationBusy" @click="closeModal">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" :loading="editRuleModalLoading || networkMutationBusy" :disabled="networkMutationBlocked || contextLoading || !editRuleDetails.name.trim() || !('updateLoadBalancerRule' in $store.getters.apis)" @click="handleSubmitEditForm">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>

    <MoldDialog
class="network-rule-dialog"
size="lg"
      :title="$t('label.add.vms')"

      :closable="true"
      v-if="addVmModalVisible"
      :visible="addVmModalVisible"

      @cancel="closeModal"
    >
      <a-alert class="mold-dialog-summary" type="info" show-icon :message="$t('message.network.lb.targets')" />
      <a-alert v-if="creationFailed" type="error" show-icon class="mold-dialog-summary" :message="$t('message.network.lb.create.failed')" />
      <a-alert v-if="assignmentFailed" class="mold-dialog-summary" type="error" show-icon :message="$t('message.network.lb.retry')" />
      <div class="mold-form-dialog"><div class="mold-form-content" @keyup.ctrl.enter="handleAddNewRule">
<PublicLbTierSelect
        v-if="publicTierRequired"
        :state="tiers"
        :value="lbTargetNetworkId"
        :vpc-name="vpcName || resource.vpcname || resource.vpcid"
        :project-name="resource.project || resource.projectid"
        :fixed="!!selectedRule || (!!resource.associatednetworkid && !vpcConserveMode)"
        :busy="loading || contextLoading"
        @change="selectPublicTier"
        @refresh="fetchListTiers" />
        <a-input-search
          v-focus="!('vpcid' in resource && !('associatednetworkid' in resource))"
          class="input-search"
          :placeholder="$t('label.search')"
          v-model:value="searchQuery"
          allowClear
          @search="onSearch" />
        <a-table
          size="small"
          class="list-view"
          :loading="addVmModalLoading"
          :columns="vmColumns"
          :dataSource="vms"
          :pagination="false"
          :rowKey="record => record.id"
          :scroll="{ y: 300 }">
          <template #bodyCell="{ column, text, record }">
            <template v-if="column.key === 'name'">
              <span>
                {{ text }}
              </span>
              <loading-outlined v-if="addVmModalNicLoading" />
              <a-select
                style="display: block"
                v-else-if="!addVmModalNicLoading && selectedBackends[record.id]"
                mode="multiple"
                v-model:value="selectedBackends[record.id].ips"
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option
                  v-for="(nic, nicIndex) in selectedBackends[record.id].options"
                  :key="nic"
                  :value="nic"
                  :label="nic + nicIndex === 0 ? ` (${$t('label.primary')})` : null">
                  {{ nic }}{{ nicIndex === 0 ? ` (${$t('label.primary')})` : null }}
                </a-select-option>
              </a-select>
            </template>

            <template v-if="column.key === 'state'">
              <status :text="text ? text : ''" displayText></status>
            </template>

            <template v-if="column.key === 'actions'" style="text-align: center" :text="text">
              <a-checkbox :disabled="loading || publicTierSubmissionBlocked" :checked="!!selectedBackends[record.id]" :value="record.id" @change="e => fetchNics(e, record.id)" />
            </template>
          </template>
        </a-table>
        <a-pagination
          class="detail-tab-pagination"
          size="small"
          :current="vmPage"
          :pageSize="vmPageSize"
          :total="vmCount"
          :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
          :pageSizeOptions="['10', '20', '40', '80', '100']"
          @change="handleChangeVmPage"
          @showSizeChange="handleChangeVmPageSize"
          showSizeChanger>
          <template #buildOptionText="props">
            <span>{{ props.value }} / {{ $t('label.page') }}</span>
          </template>
        </a-pagination>

        </div>
        <div :span="24" class="action-button">
          <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
          <a-button :disabled="ruleSubmissionBlocked || addVmModalLoading || addVmModalNicLoading || Object.values(selectedBackends).some(selection => !selection.ips.length)" type="primary" ref="submit" @click="handleAddNewRule">{{ $t('label.ok') }}</a-button>
        </div>
      </div>
          <template #footer><span /></template>
    </MoldDialog>

    <LoadBalancerSslDialog
      v-if="addSslCertModalVisible"
      :resource="resource"
      :rule="sslRule"
      :selected="selectedSsl"
      @select="selectedSsl = $event"
      @cancel="addSslCertModalVisible = false; sslRule = null"
      @refresh="fetchData" />

    <MoldDialog
      class="network-rule-dialog"
      size="lg"
      :title="$t('label.select.tier')"
      :maskClosable="false"
      :closable="!loading"
      :keyboard="!loading"
      v-if="addNetworkModalVisible"
      :visible="addNetworkModalVisible"

      @cancel="closeModal"
    >
      <PublicLbTierSelect :state="tiers" :value="selectedTierForAutoScaling" :vpc-name="vpcName || resource.vpcname || resource.vpcid" :project-name="resource.project || resource.projectid" :hide-select="true" :busy="loading || contextLoading" @refresh="fetchListTiers" />
      <div @keyup.ctrl.enter="handleAddNewRule">
        <a-input-search
          v-focus="!('vpcid' in resource && !('associatednetworkid' in resource))"
          class="input-search"
          :placeholder="$t('label.search')"
          v-model:value="networkSearchQuery"
          allowClear
          @search="onNetworkSearch" />
        <a-table
          size="small"
          class="list-view"
          :loading="tiers.loading"
          :columns="networkColumns"
          :class="'public-lb-tier-table'"
          :dataSource="networks"
          :pagination="false"
          :rowKey="record => record.id"
          :scroll="{ x: 800, y: 300 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'state'"><status :text="record.state" displayText /></template>
            <template v-if="column.key === 'actions'">
              <div style="text-align: center">
                <a-radio-group
                  class="radio-group"
                  :key="record.id"
                  v-model:value="this.selectedTierForAutoScaling"
                  @change="($event) => this.selectedTierForAutoScaling = $event.target.value">
                  <a-radio :value="record.id" :disabled="loading || tiers.loading || !tiers.ready" />
                </a-radio-group>
              </div>
            </template>
          </template>
        </a-table>
        <a-pagination
          class="detail-tab-pagination"
          size="small"
          :current="networkPage"
          :pageSize="networkPageSize"
          :total="networkCount"
          :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
          :pageSizeOptions="['10', '20', '40', '80', '100']"
          @change="handleChangeNetworkPage"
          @showSizeChange="handleChangeNetworkPageSize"
          showSizeChanger>
          <template #buildOptionText="props">
            <span>{{ props.value }} / {{ $t('label.page') }}</span>
          </template>
        </a-pagination>

      </div>
      <template #footer>
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button :disabled="ruleSubmissionBlocked || !selectedTierForAutoScaling" :loading="loading" type="primary" @click="handleAddNewRule">{{ $t('label.ok') }}</a-button>
      </template>
    </MoldDialog>

    <a-modal
centered
class="mold-dialog network-rule-dialog"
      v-if="healthMonitorModal"
      :title="$t('label.configure.health.monitor')"
      :visible="healthMonitorModal"
      :footer="null"
      :maskClosable="false"
      :closable="true"
      @cancel="closeMonitorModal">
      <a-form
        :ref="monitorRef"
        :model="monitorForm"
        :rules="monitorRules"
        layout="vertical"
        @finish="handleConfigHealthMonitor"
        v-ctrl-enter="handleConfigHealthMonitor">
        <a-form-item name="type" ref="type" :label="$t('label.monitor.type')">
          <a-select
            v-focus="true"
            v-model:value="monitorForm.type"
            @change="(value) => { healthMonitorParams.type = value }"
            showSearch
            optionFilterProp="value"
            :filterOption="(input, option) => {
              return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }">
            <a-select-option value="PING">PING</a-select-option>
            <a-select-option value="TCP">TCP</a-select-option>
            <a-select-option value="HTTP">HTTP</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="retry" ref="retry" :label="$t('label.monitor.retry')">
          <a-input v-model:value="monitorForm.retry" />
        </a-form-item>
        <a-form-item name="timeout" ref="timeout" :label="$t('label.monitor.timeout')">
          <a-input v-model:value="monitorForm.timeout" />
        </a-form-item>
        <a-form-item name="interval" ref="interval" :label="$t('label.monitor.interval')">
          <a-input v-model:value="monitorForm.interval" />
        </a-form-item>
        <a-form-item
          name="httpmethodtype"
          ref="httpmethodtype"
          :label="$t('label.monitor.http.method')"
          v-if="healthMonitorParams.type === 'HTTP'">
          <a-select
            v-focus="true"
            v-model:value="monitorForm.httpmethodtype"
            showSearch
            optionFilterProp="value"
            :filterOption="(input, option) => {
              return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }">
            <a-select-option value="GET">GET</a-select-option>
            <a-select-option value="HEAD">HEAD</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item
          name="expectedcode"
          ref="expectedcode"
          :label="$t('label.monitor.expected.code')"
          v-if="healthMonitorParams.type === 'HTTP'">
          <a-input v-model:value="monitorForm.expectedcode" />
        </a-form-item>
        <a-form-item
          name="urlpath"
          ref="urlpath"
          :label="$t('label.monitor.url')"
          v-if="healthMonitorParams.type === 'HTTP'">
          <a-input v-model:value="monitorForm.urlpath" />
        </a-form-item>

        <div :span="24" class="action-button">
          <a-button :loading="healthMonitorLoading" @click="closeMonitorModal">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="healthMonitorLoading" type="primary" @click="handleConfigHealthMonitor">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-modal>

    <bulk-action-view
      v-if="showConfirmationAction || showGroupActionModal"
      :showConfirmationAction="showConfirmationAction"
      :showGroupActionModal="showGroupActionModal"
      :items="lbRules"
      :selectedRowKeys="selectedRowKeys"
      :selectedItems="selectedItems"
      :columns="columns"
      :selectedColumns="selectedColumns"
      :filterColumns="filterColumns"
      action="deleteLoadBalancerRule"
      :loading="loading"
      :message="message"
      @group-action="deleteRules"
      @handle-cancel="handleCancel"
      @close-modal="closeModal" />
  </div>
</template>

<script>
import { validPortRange, loadBalancerIsProtected, selectedBackendMap, loadBalancerSubmissionBlocked } from '@/utils/networkRuleForm'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import LoadBalancerStickinessDialog from '@/components/network/LoadBalancerStickinessDialog'
import LoadBalancerSslDialog from '@/components/network/LoadBalancerSslDialog'
import { networkError } from '@/utils/networkLifecycle'
import { networkMutationMixin } from '@/utils/networkMutation'
import { lbBackendRemovalMixin } from '@/utils/lbBackendRemoval'
import NetworkMutationStatus from '@/components/view/NetworkMutationStatus'

import { ref, reactive, toRaw } from 'vue'
import NetworkRuleContext from '@/components/view/NetworkRuleContext'
import NetworkRulesToolbar from '@/components/view/NetworkRulesToolbar'
import PublicLbTierSelect from '@/components/view/PublicLbTierSelect'
import { fetchPublicLbTiers, validatePublicLbTier } from '@/utils/publicLbTiers'
import RuleTagsDialog from '@/components/view/RuleTagsDialog.vue'
import { getAPI, postAPI } from '@/api'
import { mixinForm } from '@/utils/mixin'
import Status from '@/components/widgets/Status'
import TooltipButton from '@/components/widgets/TooltipButton'
import BulkActionView from '@/components/view/BulkActionView'
import eventBus from '@/config/eventBus'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'LoadBalancing',
  mixins: [listRefreshMixin(['fetchLBRules'], { scopeKey: vm => [vm.lbBackendScope, vm.lbBackendVersion, vm.lbBackendSuspended, vm.page, vm.pageSize] }), mixinForm, networkMutationMixin, lbBackendRemovalMixin],
  components: {
    MoldDialog,
    MoldDialogActions,
    LoadBalancerSslDialog,
    LoadBalancerStickinessDialog,
    NetworkMutationStatus,
    RuleTagsDialog,
    NetworkRuleContext,
    NetworkRulesToolbar,
    PublicLbTierSelect,
    Status,
    TooltipButton,
    BulkActionView,
    TooltipLabel
  },
  props: {
    detailViewport: { type: Boolean, default: true },
    contextLoading: { type: Boolean, default: false },
    ruleOwners: { type: Object, default: () => ({}) },
    resource: {
      type: Object,
      required: true
    }
  },
  inject: ['parentFetchData', 'parentToggleLoading'],
  data () {
    return {
      targetMode: 'select',
      createModalVisible: false,
      assignmentFailed: false,
      selectedBackends: {},
      nicPending: 0,
      nicRequest: 0,
      backendEpoch: 0,
      vmRequest: 0,
      tierRequest: 0,
      publicContextSuspended: false,
      vpcName: '',
      creationJob: null,
      creationFailed: false,
      creationUncertain: false,
      selectedRowKeys: [],
      showGroupActionModal: false,
      selectedItems: [],
      selectedColumns: [],
      filterColumns: ['State', 'Actions', 'Add VMs', 'Stickiness'],
      showConfirmationAction: false,
      message: {
        title: this.$t('label.action.bulk.delete.load.balancer.rules'),
        confirmMessage: this.$t('label.confirm.delete.loadbalancer.rules')
      },
      loading: true,
      lbRules: [],
      tagRule: null,
      selectedRule: null,
      settingsRuleId: null,
      selectedTier: null,
      stickinessModalVisible: false,
      stickinessRule: null,
      stickinessPolicies: [],
      editRuleModalVisible: false,
      editRuleModalLoading: false,
      editRuleDetails: {
        name: '',
        algorithm: '',
        protocol: '',
        cidrlist: '',
        backendssl: false
      },
      newRule: {
        autoscale: 'no',
        algorithm: 'roundrobin',
        name: '',
        privateport: '',
        publicport: '',
        protocol: 'tcp',
        backendssl: false,
        virtualmachineid: [],
        vmguestip: [],
        cidrlist: ''
      },
      lbProvider: null,
      addVmModalVisible: false,
      addVmModalLoading: false,
      addVmModalNicLoading: false,
      zoneloading: false,
      vms: [],
      nics: [],
      totalCount: 0,
      page: 1,
      pageSize: 10,
      selectedSsl: { id: null, name: '' },
      addSslCertModalVisible: false,
      sslRule: null,
      columns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          title: this.$t('label.publicport'),
          dataIndex: 'publicport'
        },
        {
          title: this.$t('label.privateport'),
          dataIndex: 'privateport'
        },
        {
          key: 'cidrlist',
          title: this.$t('label.sourcecidrlist')
        },
        {
          key: 'protocol',
          title: this.$t('label.protocol')
        },
        {
          key: 'backendssl',
          title: this.$t('label.backend.ssl')
        },
        {
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          key: 'stickiness',
          title: this.$t('label.action.configure.stickiness')
        },
        {
          key: 'add',
          title: this.$t('label.add.vms')
        },
        {
          key: 'sslcert',
          title: this.$t('label.sslcertificate')
        },
        {
          key: 'autoscale',
          title: this.$t('label.autoscale')
        },
        {
          key: 'actions',
          title: this.$t('label.actions')
        }
      ],
      tiers: {
        loading: false,
        ready: false,
        failed: false,
        data: []
      },
      vmPage: 1,
      vmPageSize: 10,
      vmCount: 0,
      addNetworkModalVisible: false,
      addNetworkModalLoading: false,
      associatednetworkid: null,
      selectedTierForAutoScaling: null,
      networkColumns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name',
          width: 260
        },
        {
          key: 'state',
          title: this.$t('label.state'),
          dataIndex: 'state',
          width: 100
        },
        {
          title: this.$t('label.gateway'),
          dataIndex: 'gateway',
          width: 160
        },
        {
          title: this.$t('label.netmask'),
          dataIndex: 'netmask',
          width: 160
        },
        {
          key: 'actions',
          title: this.$t('label.select'),
          dataIndex: 'actions',
          width: 80,
          fixed: 'right'
        }
      ],
      networkPage: 1,
      networkPageSize: 10,
      networkSearchQuery: '',
      searchQuery: null,
      tungstenHealthMonitors: [],
      healthMonitorModal: false,
      healthMonitorParams: {
        type: 'PING',
        retry: 3,
        timeout: 5,
        interval: 5,
        httpmethodtype: 'GET',
        expectedcode: undefined,
        urlpath: '/'
      },
      healthMonitorLoading: false,
      isNetrisZone: false,
      vpcConserveMode: false
    }
  },
  computed: {
    sslSelectionAllowed () { return !this.publicContextSuspended && ['createLoadBalancerRule', 'listSslCerts', 'assignCertToLoadBalancer', 'queryAsyncJobResult'].every(api => api in (this.$store.getters.apis || {})) },
    ruleSubmissionBlocked () { return this.publicContextSuspended || loadBalancerSubmissionBlocked(this) || this.publicTierSubmissionBlocked },
    publicTierRequired () { return !!this.resource.vpcid },
    lbTargetNetworkId () {
      if (this.selectedRule) return this.selectedRule.networkid || this.associatednetworkid || this.resource.associatednetworkid
      if (this.selectedTierForAutoScaling) return this.selectedTierForAutoScaling
      return this.publicTierRequired && (!this.resource.associatednetworkid || this.vpcConserveMode) ? this.selectedTier : this.resource.associatednetworkid
    },
    publicTierSubmissionBlocked () {
      if (!this.publicTierRequired || this.creationJob) return false
      if (this.tiers.loading || this.tiers.failed || !this.tiers.ready || !this.tiers.data.length) return true
      // A new AutoScale rule opens the explicit Tier chooser before posting anything.
      if (this.needsAutoScaleTier) return false
      return !this.tiers.data.some(tier => tier.id === this.lbTargetNetworkId)
    },
    filteredPublicTiers () {
      const keyword = String(this.networkSearchQuery || '').trim().toLowerCase()
      return this.tiers.data.filter(tier => [tier.name, tier.displaytext, tier.id, tier.gateway].some(value => String(value || '').toLowerCase().includes(keyword)))
    },
    networkCount () { return this.filteredPublicTiers.length },
    networks () { return this.filteredPublicTiers.slice((this.networkPage - 1) * this.networkPageSize, this.networkPage * this.networkPageSize) },
    needsAutoScaleTier () { return this.newRule.autoscale === 'yes' && !!this.resource.vpcid && !this.resource.associatednetworkid && !this.selectedTierForAutoScaling },
    vmColumns () {
      return [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name',
          width: 220
        },
        {
          key: 'state',
          title: this.$t('label.state'),
          dataIndex: 'state'
        },
        {
          title: this.$t('label.displayname'),
          dataIndex: 'displayname'
        },
        {
          title: this.$t('label.account'),
          dataIndex: 'account'
        },
        {
          title: this.$t('label.zonename'),
          dataIndex: 'zonename'
        },
        {
          key: 'actions',
          title: this.$t('label.select'),
          dataIndex: 'actions',
          width: 80
        }
      ]
    },
    compactColumns () {
      return [
        { key: 'name', dataIndex: 'name', title: this.$t('label.name'), width: 180 },
        { key: 'ports', title: this.$t('label.network.lb.ports'), width: 140 },
        { key: 'policy', title: this.$t('label.network.lb.policy'), width: 140 },
        { key: 'targets', title: this.$t('label.network.lb.targets.short'), width: 200 },
        { key: 'cidrlist', title: this.$t('label.sourcecidrlist'), width: 160 },
        { key: 'state', dataIndex: 'state', title: this.$t('label.state'), width: 90 },
        { key: 'actions', title: this.$t('label.actions'), width: 170, fixed: 'right' }
      ]
    },
    hasSelected () {
      return this.selectedRowKeys.length > 0
    }
  },
  beforeCreate () {
    this.createLoadBalancerRuleParams = this.$getApiParams('createLoadBalancerRule')
    if ('associatednetworkid' in this.resource) {
      this.associatednetworkid = this.resource.associatednetworkid
    }
  },
  created () {
    window.addEventListener('resize', this.closeSettingsMenu)
    eventBus.on('project-switch-start', this.onPublicProjectSwitch)
    this.initForm()
    this.initMonitorForm()
    this.fetchData()
  },
  beforeUnmount () {
    window.removeEventListener('resize', this.closeSettingsMenu)
    this.tierRequest++
    this.vmRequest++
    eventBus.off('project-switch-start', this.onPublicProjectSwitch)
  },
  watch: {
    '$route.query.tab' () { this.closeSettingsMenu(); this.invalidateStickinessDialog() },
    networkMutationScope () {
      this.closeSettingsMenu()
      this.invalidateStickinessDialog()
      this.addSslCertModalVisible = false; this.sslRule = null; this.selectedSsl = { id: null, name: '' }
      this.invalidatePublicTierContext()
      this.createModalVisible = false
      this.addNetworkModalVisible = false
      this.selectedBackends = {}
      this.creationJob = null
      this.creationFailed = false
      this.creationUncertain = false
      this.loading = false
      this.selectedRule = null
      if (this.editRuleModalVisible) {
        this.editRuleModalVisible = false
        this.editRuleModalLoading = false
        this.selectedRule = null
      }
    },
    'newRule.autoscale' () {
      this.clearBackendSelection()
      this.selectedTierForAutoScaling = null
    },
    'resource.id' () { this.tagRule = null; this.publicContextSuspended = false },
    resource: {
      deep: true,
      handler (newItem) {
        if (!newItem || !newItem.id) {
          return
        }
        this.fetchData()
      }
    }
  },
  emits: ['refresh-inventory', 'selection-change'],
  methods: {
    closeSettingsMenu () { this.settingsRuleId = null },
    onPublicProjectSwitch () {
      this.closeSettingsMenu()
      this.invalidateStickinessDialog()
      this.publicContextSuspended = true
      this.addSslCertModalVisible = false; this.sslRule = null; this.selectedSsl = { id: null, name: '' }
      this.invalidatePublicTierContext()
      this.selectedRule = null
      this.creationJob = null
      this.loading = false
    },
    clearBackendSelection () {
      this.backendEpoch++
      this.vmRequest++
      this.selectedBackends = {}
      this.nicPending = 0
      this.addVmModalNicLoading = false
      this.addVmModalLoading = false
      this.vms = []
      this.vmCount = 0
    },
    invalidatePublicTierContext () {
      this.networkMutationVersion++
      this.vpcName = ''
      this.tierRequest++
      this.tiers = { data: [], ready: false, failed: false, loading: false }
      this.selectedTier = null
      this.selectedTierForAutoScaling = null
      this.associatednetworkid = this.resource.associatednetworkid || null
      this.createModalVisible = false
      this.addVmModalVisible = false
      this.addNetworkModalVisible = false
      this.clearBackendSelection()
    },
    selectPublicTier (id) {
      this.clearBackendSelection()
      this.selectedTier = this.tiers.data.some(tier => tier.id === id) ? id : null
      this.vmPage = 1
      this.fetchVirtualMachines()
    },
    refreshRules () {
      this.fetchData()
      this.$emit('refresh-inventory')
    },
    isProtectedRule (rule) { return this.contextLoading || loadBalancerIsProtected(rule, this.ruleOwners) },
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({})
    },
    initMonitorForm () {
      this.monitorRef = ref()
      this.monitorForm = reactive({
        type: this.healthMonitorParams.type,
        retry: this.healthMonitorParams.retry,
        timeout: this.healthMonitorParams.timeout,
        interval: this.healthMonitorParams.interval,
        httpmethodtype: this.healthMonitorParams.httpmethodtype,
        expectedcode: this.healthMonitorParams.expectedcode,
        urlpath: this.healthMonitorParams.urlpath
      })
      this.monitorRules = reactive({
        retry: [{ required: true, message: this.$t('message.error.required.input') }],
        timeout: [{ required: true, message: this.$t('message.error.required.input') }],
        interval: [{ required: true, message: this.$t('message.error.required.input') }],
        expectedcode: [{ required: true, message: this.$t('message.error.required.input') }],
        urlpath: [{ required: true, message: this.$t('message.error.required.input') }]
      })
    },
    fetchData () {
      this.fetchVpc()
      this.fetchListTiers()
      this.fetchLBRules()
      this.fetchZone()
    },
    fetchVpc () {
      if (!this.resource.vpcid) {
        return
      }
      this.vpcConserveMode = false
      const scope = this.networkMutationScope
      getAPI('listVPCs', {
        id: this.resource.vpcid, ignoreproject: true, ...(this.resource.projectid ? { projectid: this.resource.projectid } : {})
      }).then(json => {
        if (this.networkMutationDisposed || scope !== this.networkMutationScope) return
        this.vpcName = json.listvpcsresponse?.vpc?.[0]?.name || this.resource.vpcname || this.resource.vpcid
        this.vpcConserveMode = json.listvpcsresponse?.vpc?.[0].vpcofferingconservemode || false
      }).catch(error => {
        if (this.networkMutationDisposed || scope !== this.networkMutationScope) return
        this.$notifyError(error)
      })
    },
    async fetchListTiers () {
      const request = ++this.tierRequest
      const scope = this.networkMutationScope
      const current = () => !this.networkMutationDisposed && request === this.tierRequest && scope === this.networkMutationScope
      if (!this.publicTierRequired) {
        this.tiers = { data: [], ready: false, failed: false, loading: false }
        return
      }
      if (this.publicContextSuspended) return
      this.tiers.loading = true
      this.tiers.failed = false
      try {
        const data = await fetchPublicLbTiers(getAPI, this.resource.vpcid, this.resource.projectid, current)
        if (!current()) return
        const previous = this.lbTargetNetworkId
        this.tiers = { data, ready: true, failed: false, loading: false }
        this.selectedTier = data.some(tier => tier.id === this.selectedTier) ? this.selectedTier : data[0]?.id || null
        if (!data.some(tier => tier.id === this.selectedTierForAutoScaling)) this.selectedTierForAutoScaling = null
        if (!data.some(tier => tier.id === previous) || previous !== this.lbTargetNetworkId) this.clearBackendSelection()
        if ((this.networkPage - 1) * this.networkPageSize >= this.networkCount) this.networkPage = 1
        if (data.some(tier => tier.broadcasturi === 'tf://tf') && !this.columns.some(column => column.key === 'healthmonitor')) {
          this.columns.splice(8, 0, {
            title: this.$t('label.action.health.monitor'),
            key: 'healthmonitor'
          })
        }
        if (this.createModalVisible || this.addVmModalVisible) this.fetchVirtualMachines()
      } catch (error) {
        if (!current()) return
        this.tiers = { data: [], ready: false, failed: true, loading: false }
        this.selectedTier = null
        this.selectedTierForAutoScaling = null
        this.clearBackendSelection()
      }
    },
    async fetchLBRules () {
      const listRequest = this.listRequestToken('fetchLBRules')
      this.loading = !listRequest.loaded
      try {
        const response = await getAPI('listLoadBalancerRules', {
          listAll: true, publicipid: this.resource.id, page: this.page, pageSize: this.pageSize
        }, { preserveOnFailure: true })
        if (!this.isListRequestCurrent('fetchLBRules', listRequest)) return
        const rules = response.listloadbalancerrulesresponse.loadbalancerrule || []
        const snapshots = await Promise.all(rules.map(async rule => {
          const apis = this.$store.getters.apis
          const [instances, stickiness, autoscale, monitor] = await Promise.all([
            getAPI('listLoadBalancerRuleInstances', { listAll: true, lbvmips: true, id: rule.id }, { preserveOnFailure: true }),
            'listLBStickinessPolicies' in apis ? getAPI('listLBStickinessPolicies', { listAll: true, lbruleid: rule.id }) : null,
            'listAutoScaleVmGroups' in apis ? getAPI('listAutoScaleVmGroups', { listAll: true, lbruleid: rule.id }) : null,
            'listTungstenFabricLBHealthMonitor' in apis ? getAPI('listTungstenFabricLBHealthMonitor', { listAll: true, lbruleid: rule.id }) : null
          ])
          return {
            rule: { ...rule, ruleInstances: instances.listloadbalancerruleinstancesresponse.lbrulevmidip || [], autoscalevmgroup: autoscale?.listautoscalevmgroupsresponse?.autoscalevmgroup?.[0] },
            stickiness: stickiness?.listlbstickinesspoliciesresponse?.stickinesspolicies || [],
            monitors: (monitor?.listtungstenfabriclbhealthmonitorresponse?.healthmonitor || []).map(item => ({ ...item, lbruleid: rule.id }))
          }
        }))
        if (!this.isListRequestCurrent('fetchLBRules', listRequest)) return
        // Publish one complete snapshot: refresh never clears visible rules or targets.
        this.lbRules = snapshots.map(item => item.rule)
        this.stickinessPolicies = snapshots.flatMap(item => item.stickiness)
        this.tungstenHealthMonitors = snapshots.flatMap(item => item.monitors)
        this.totalCount = response.listloadbalancerrulesresponse.count || 0
      } catch (error) {
        if (!this.isListRequestCurrent('fetchLBRules', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (!listRequest.loaded) this.$notifyError(error)
      } finally {
        if (this.isListRequestCurrent('fetchLBRules', listRequest)) this.loading = false
      }
    },
    fetchZone () {
      this.zoneloading = true
      return getAPI('listZones', {
        id: this.resource.zoneid
      }).then(response => {
        this.lbProvider = response?.listzonesresponse?.zone?.[0]?.provider || null
        if (this.lbProvider != null) {
          this.isNetrisZone = this.lbProvider === 'Netris'
        }
      }).finally(() => {
        this.zoneloading = false
      })
    },
    handleAddSslCert (data) {
      if (!this.selectedSsl.id || !this.sslSelectionAllowed) return
      getAPI('assignCertToLoadBalancer', {
        lbruleid: data,
        certid: this.selectedSsl.id,
        forced: true,
        ignoreproject: true,
        ...(this.resource.projectid ? { projectid: this.resource.projectid } : {})
      }, { preserveOnFailure: true }).then(response => {
        this.pollScopedNetworkJob({
          jobId: response.assigncerttoloadbalancerresponse.jobid,
          successMessage: this.$t('message.success.assign.sslcert'),
          errorMessage: this.$t('message.assign.sslcert.failed'),
          loadingMessage: this.$t('message.assign.sslcert.processing'),
          catchMessage: this.$t('error.fetching.async.job.result')
        })
      }).catch(error => this.$notifyError(error))
    },
    handleOpenAddSslCertModal (record) {
      if (this.publicContextSuspended || !('listSslCerts' in (this.$store.getters.apis || {})) || (record ? record.protocol !== 'ssl' : !this.sslSelectionAllowed)) return
      this.sslRule = record
      this.addSslCertModalVisible = true
    },
    returnAlgorithmName (name) {
      switch (name) {
        case 'leastconn':
          return 'Least connections'
        case 'roundrobin' :
          return 'Round-robin'
        case 'source':
          return 'Source'
        default :
          return ''
      }
    },
    returnStickinessLabel (id) {
      const match = this.stickinessPolicies.filter(policy => policy.lbruleid === id)
      const current = match.flatMap(group => group.stickinesspolicy || []).find(policy => String(policy.state || '').toLowerCase() !== 'revoked')
      if (current) return current.methodname
      return 'Configure'
    },
    getCapitalise (val) {
      if (!val) {
        return
      }
      if (val === 'all') return this.$t('label.all')
      return val.toUpperCase()
    },
    openTagsModal (rule) {
      if (!rule.id || !this.resource.id || !('listTags' in this.$store.getters.apis)) return
      this.tagRule = { id: rule.id, parentId: this.resource.id, canEdit: !this.isProtectedRule(rule) }
    },
    canConfigureStickiness (rule) {
      const apis = this.$store.getters.apis || {}
      return !this.publicContextSuspended && !this.contextLoading && !this.isProtectedRule(rule) &&
        ['listLoadBalancerRules', 'listLBStickinessPolicies', 'listNetworks', 'queryAsyncJobResult'].every(api => api in apis) &&
        ['createLBStickinessPolicy', 'deleteLBStickinessPolicy'].some(api => api in apis)
    },
    openStickinessModal (id) {
      const rule = this.lbRules.find(rule => rule.id === id)
      if (!rule || !this.canConfigureStickiness(rule)) return
      if (this.$refs.stickinessDialog?.networkMutationBlocked && this.stickinessRule?.id !== id) {
        this.$notification.warning({ message: this.$t('message.network.lifecycle.pending') }); return
      }
      this.stickinessRule = { ...rule }
      this.stickinessModalVisible = true
    },
    invalidateStickinessDialog () { this.stickinessModalVisible = false; this.stickinessRule = null },
    refreshLbBackends () { return this.fetchLBRules() },
    onLbBackendProjectEnd () { this.publicContextSuspended = false },
    canRemoveRuleBackend (rule, instance, ip) {
      const current = this.lbRules.find(item => item.id === rule?.id)
      return !!(this.lbBackendAllowed && !this.loading && !this.contextLoading && !this.listRefreshFailed && !this.listRefreshing &&
        !this.networkMutationBlocked && !this.publicContextSuspended && current && current.publicipid === this.resource.id &&
        !current.autoscalevmgroup && !this.isProtectedRule(current) && current.ruleInstances?.some(item =>
        item.loadbalancerruleinstance?.id === instance?.loadbalancerruleinstance?.id && item.lbvmipaddresses?.includes(ip)))
    },
    handleDeleteInstanceFromRule (instance, rule, ip) {
      if (!this.canRemoveRuleBackend(rule, instance, ip)) return false
      return this.removeLbBackend({
        id: rule.id,
        'vmidipmap[0].vmid': instance.loadbalancerruleinstance.id,
        'vmidipmap[0].vmip': ip
      })
    },
    openEditRuleModal (rule) {
      if (this.isProtectedRule(rule)) return
      if (this.networkMutationBlocked) {
        // Reopen the same pending receipt to recheck it; never send a duplicate update.
        if (this.networkMutation?.api === 'updateLoadBalancerRule' && this.networkMutation.params.id === rule.id) {
          this.selectedRule = rule
          this.editRuleModalVisible = true
        }
        return
      }
      this.resetNetworkMutation()
      this.selectedRule = rule
      this.editRuleModalVisible = true
      this.editRuleDetails.name = this.selectedRule.name
      this.editRuleDetails.algorithm = this.lbProvider !== 'Netris' ? this.selectedRule.algorithm : undefined
      this.editRuleDetails.protocol = this.lbProvider !== 'Netris' ? ((this.selectedRule.protocol || '').trim().toLowerCase() || 'tcp') : undefined
      // Normalize cidrlist: replace spaces with commas and clean up
      this.editRuleDetails.cidrlist = (this.selectedRule.cidrlist || '')
        .split(/[\s,]+/) // Split on spaces or commas
        .map(c => c.trim())
        .filter(c => c)
        .join(',') || ''
      this.editRuleDetails.backendssl = this.editRuleDetails.protocol === 'ssl' && !!this.selectedRule.backendssl
    },
    async handleSubmitEditForm () {
      if (this.editRuleModalLoading || this.networkMutationBlocked || !this.selectedRule || this.isProtectedRule(this.selectedRule) || !this.editRuleDetails.name.trim()) return
      const scope = this.networkMutationScope
      this.editRuleModalLoading = true
      const payload = {
        id: this.selectedRule.id,
        name: this.editRuleDetails.name,
        ...(this.lbProvider !== 'Netris' && {
          algorithm: this.editRuleDetails.algorithm,
          protocol: (this.editRuleDetails.protocol || '').trim().toLowerCase() || 'tcp',
          backendssl: this.editRuleDetails.protocol === 'ssl' && this.editRuleDetails.backendssl,
          cidrlist: (this.editRuleDetails.cidrlist || '').split(',').map(c => c.trim()).filter(c => c).join(',')
        })
      }
      try {
        await this.submitNetworkMutation('updateLoadBalancerRule', payload, () => {
          this.refreshRules()
          this.closeModal()
        })
      } finally {
        if (!this.networkMutationDisposed && this.networkMutationScope === scope) this.editRuleModalLoading = false
      }
    },
    setSelection (selection) {
      this.selectedRowKeys = selection
      this.$emit('selection-change', this.selectedRowKeys)
      this.selectedItems = (this.lbRules.filter(function (item) {
        return selection.indexOf(item.id) !== -1
      }))
    },
    resetSelection () {
      this.setSelection([])
    },
    onSelectChange (selectedRowKeys, selectedRows) {
      this.setSelection(selectedRowKeys)
    },
    bulkActionConfirmation () {
      this.showConfirmationAction = true
      this.selectedColumns = this.columns.filter(column => {
        return !this.filterColumns.includes(column.title)
      })
      this.selectedItems = this.selectedItems.map(v => ({ ...v, status: 'InProgress' }))
    },
    handleCancel () {
      eventBus.emit('update-bulk-job-status', { items: this.selectedItems, action: false })
      this.showGroupActionModal = false
      this.selectedItems = []
      this.selectedColumns = []
      this.selectedRowKeys = []
      this.parentFetchData()
    },
    deleteRules (e) {
      this.showConfirmationAction = false
      this.selectedColumns.splice(0, 0, {
        key: 'status',
        dataIndex: 'status',
        title: this.$t('label.operation.status'),
        filters: [
          { text: 'In Progress', value: 'InProgress' },
          { text: 'Success', value: 'success' },
          { text: 'Failed', value: 'failed' }
        ]
      })
      if (this.selectedRowKeys.length > 0) {
        this.showGroupActionModal = true
      }
      for (const rule of this.selectedItems.filter(rule => !this.isProtectedRule(rule))) {
        this.handleDeleteRule(rule)
      }
    },
    handleDeleteRule (rule) {
      if (this.isProtectedRule(rule)) return
      this.loading = true
      postAPI('deleteLoadBalancerRule', {
        id: rule.id
      }).then(response => {
        const jobId = response.deleteloadbalancerruleresponse.jobid
        eventBus.emit('update-job-details', { jobId, resourceId: null })
        this.pollScopedNetworkJob({
          title: this.$t('label.action.delete.load.balancer'),
          description: rule.id,
          jobId: jobId,
          successMessage: this.$t('message.success.remove.rule'),
          successMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems: this.selectedItems, resource: rule.id, state: 'success' })
            }
            if (this.selectedRowKeys.length === 0) {
              this.parentToggleLoading()
              this.fetchData()
            }
            this.closeModal()
          },
          errorMessage: this.$t('message.remove.rule.failed'),
          errorMethod: () => {
            if (this.selectedItems.length > 0) {
              eventBus.emit('update-resource-state', { selectedItems: this.selectedItems, resource: rule.id, state: 'failed' })
            }
            if (this.selectedRowKeys.length === 0) {
              this.parentToggleLoading()
              this.fetchData()
            }
            this.closeModal()
          },
          loadingMessage: this.$t('message.delete.rule.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            if (this.selectedRowKeys.length === 0) {
              this.parentToggleLoading()
              this.parentFetchData()
            }
            this.closeModal()
          },
          bulkAction: `${this.selectedItems.length > 0}` && this.showGroupActionModal
        })
      }).catch(error => {
        this.$notifyError(error)
        this.loading = false
      })
    },
    checkNewRule () {
      if (!this.selectedRule) {
        if (!this.newRule.name) {
          this.$refs.newRuleName.classList.add('error')
        } else {
          this.$refs.newRuleName.classList.remove('error')
        }
        if (!this.newRule.publicport) {
          this.$refs.newRulePublicPort.classList.add('error')
        } else {
          this.$refs.newRulePublicPort.classList.remove('error')
        }
        if (!this.newRule.privateport) {
          this.$refs.newRulePrivatePort.classList.add('error')
        } else {
          this.$refs.newRulePrivatePort.classList.remove('error')
        }
        if (!this.newRule.name || !validPortRange(this.newRule.publicport, this.newRule.publicport) || !validPortRange(this.newRule.privateport, this.newRule.privateport)) return false
      }
      return true
    },
    openCreateDialog () {
      if (this.publicContextSuspended || this.contextLoading) return
      this.closeModal(); this.createModalVisible = true
      this.newRule.autoscale = 'no'
      this.targetMode = 'assignToLoadBalancerRule' in this.$store.getters.apis ? 'select' : 'later'
      this.vmPage = 1; this.searchQuery = ''; this.fetchListTiers(); this.fetchVirtualMachines()
    },
    targetModeChanged () { this.clearBackendSelection(); this.fetchVirtualMachines() },
    handleOpenAddVMModal () {
      if (this.selectedRule && this.isProtectedRule(this.selectedRule)) return
      if (this.addVmModalLoading) return
      if (!this.checkNewRule()) {
        return
      }
      this.createModalVisible = false
      this.addVmModalVisible = true
      this.fetchVirtualMachines()
    },
    async fetchNics (e, id) {
      if (this.publicTierSubmissionBlocked) return
      if (!e.target.checked) { delete this.selectedBackends[id]; return }
      const scope = this.networkMutationScope
      const epoch = this.backendEpoch
      const selection = { ips: [], options: [], request: ++this.nicRequest }
      this.selectedBackends[id] = selection
      this.nicPending++; this.addVmModalNicLoading = true
      const networkid = this.lbTargetNetworkId
      try {
        const response = await getAPI('listNics', { virtualmachineid: id, networkid })
        if (this.networkMutationDisposed || scope !== this.networkMutationScope || epoch !== this.backendEpoch || networkid !== this.lbTargetNetworkId || this.selectedBackends[id]?.request !== selection.request) return
        const nic = response.listnicsresponse?.nic?.find(nic => nic.networkid === networkid)
        if (!nic?.ipaddress) throw new Error(this.$t('label.nic'))
        this.selectedBackends[id] = { ips: [nic.ipaddress], options: [nic.ipaddress, ...(nic.secondaryip || []).map(ip => ip.ipaddress)] }
      } catch (error) {
        if (this.networkMutationDisposed || scope !== this.networkMutationScope || epoch !== this.backendEpoch) return
        this.$notifyError(error); if (this.selectedBackends[id]?.request === selection.request) delete this.selectedBackends[id]
      } finally {
        if (scope === this.networkMutationScope && epoch === this.backendEpoch) { this.nicPending = Math.max(0, this.nicPending - 1); this.addVmModalNicLoading = this.nicPending > 0 }
      }
    },
    fetchVirtualMachines () {
      const request = ++this.vmRequest
      const scope = this.networkMutationScope
      this.vmCount = 0
      this.vms = []
      this.addVmModalLoading = true
      const networkId = this.lbTargetNetworkId
      const current = () => !this.networkMutationDisposed && scope === this.networkMutationScope && request === this.vmRequest && networkId === this.lbTargetNetworkId
      if (!networkId || this.publicTierSubmissionBlocked) {
        this.addVmModalLoading = false
        return
      }
      getAPI('listVirtualMachines', {
        listAll: true,
        state: 'Present',
        keyword: this.searchQuery,
        page: this.vmPage,
        pagesize: this.vmPageSize,
        networkid: networkId
      }).then(response => {
        if (!current()) return
        this.vmCount = response.listvirtualmachinesresponse.count || 0
        this.vms = response.listvirtualmachinesresponse.virtualmachine || []
      }).catch(error => {
        if (!current()) return
        this.$notifyError(error)
      }).finally(() => {
        if (!current()) return
        this.addVmModalLoading = false
      })
    },
    handleOpenAddNetworkModal () {
      if (this.addNetworkModalLoading) return
      if (!this.checkNewRule()) {
        return
      }
      this.addNetworkModalVisible = true
      this.fetchNetworks()
    },
    fetchNetworks () {
      this.selectedTierForAutoScaling = null
      this.networkPage = 1
      return this.fetchListTiers()
    },
    onNetworkSearch (value) {
      this.networkSearchQuery = value
      this.networkPage = 1
    },
    handleChangeNetworkPage (page, pageSize) {
      this.networkPage = page
      this.networkPageSize = pageSize
    },
    handleChangeNetworkPageSize (currentPage, pageSize) {
      this.networkPage = currentPage
      this.networkPageSize = pageSize
    },
    async handleAssignToLBRule (data) {
      const operationScope = this.networkMutationScope
      const operationVersion = this.networkMutationVersion
      const networkId = this.lbTargetNetworkId
      if (this.publicTierRequired && !await this.checkPublicTierBeforeWrite(networkId, operationScope)) return
      const vmIDIpMap = selectedBackendMap(this.newRule.autoscale === 'yes' ? {} : this.selectedBackends, this.vpcConserveMode, networkId)
      const selectedVmCount = Object.keys(vmIDIpMap).length
      if (selectedVmCount === 0) {
        if (this.newRule.protocol === 'ssl' && this.selectedSsl.id !== null) this.handleAddSslCert(data)
        this.loading = false; this.fetchData(); this.closeModal()
        return
      }

      this.loading = true
      postAPI('assignToLoadBalancerRule', {
        id: data,
        ...vmIDIpMap
      }).then(response => {
        if (this.networkMutationDisposed || this.networkMutationScope !== operationScope || this.networkMutationVersion !== operationVersion) return
        this.pollScopedNetworkJob({
          jobId: response.assigntoloadbalancerruleresponse.jobid,
          successMessage: this.$t('message.success.assign.vm'),
          successMethod: () => {
            this.parentToggleLoading()
            if (this.newRule.protocol === 'ssl' && this.selectedSsl.id !== null) {
              this.handleAddSslCert(data)
            }
            this.fetchData()
            this.assignmentFailed = false
            this.closeModal()
          },
          errorMessage: this.$t('message.assign.vm.failed'),
          errorMethod: () => {
            this.parentToggleLoading()
            this.fetchData()
            this.assignmentFailed = true
            this.loading = false
          },
          loadingMessage: this.$t('message.assign.vm.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentFetchData()
            this.parentToggleLoading()
            this.fetchData()
            this.assignmentFailed = true
            this.loading = false
          }
        })
      }).catch(error => { if (this.networkMutationDisposed || this.networkMutationScope !== operationScope || this.networkMutationVersion !== operationVersion) return; this.$notifyError(error); this.assignmentFailed = true; this.loading = false })
    },
    async checkPublicTierBeforeWrite (networkId, scope) {
      if (!this.publicTierRequired) return true
      const epoch = this.backendEpoch
      const current = () => !this.publicContextSuspended && !this.networkMutationDisposed && scope === this.networkMutationScope && networkId === this.lbTargetNetworkId && epoch === this.backendEpoch
      try {
        if (await validatePublicLbTier(getAPI, networkId, this.resource.vpcid, this.resource.projectid, current)) return true
        if (!current()) { if (!this.networkMutationDisposed && scope === this.networkMutationScope) this.loading = false; return false }
      } catch (error) {
        if (!current()) { if (!this.networkMutationDisposed && scope === this.networkMutationScope) this.loading = false; return false }
      }
      this.tierRequest++
      this.tiers = { data: [], ready: false, failed: true, loading: false }
      this.clearBackendSelection()
      this.loading = false
      return false
    },
    async handleAddNewRule () {
      const operationScope = this.networkMutationScope
      const operationVersion = this.networkMutationVersion
      if (this.ruleSubmissionBlocked) return
      if (!this.selectedRule && !('createLoadBalancerRule' in this.$store.getters.apis)) return
      if (this.newRule.autoscale === 'yes' && this.resource?.vpcid && !this.resource.associatednetworkid && !this.selectedTierForAutoScaling) {
        this.handleOpenAddNetworkModal()
        return
      }
      this.loading = true

      if (this.creationJob) { this.finishRuleCreation(); return }
      if (this.selectedRule) {
        this.handleAssignToLBRule(this.selectedRule.id)
        return
      } else if (!this.checkNewRule()) {
        this.loading = false
        return
      }

      const networkId = this.lbTargetNetworkId
      if (this.publicTierRequired && !await this.checkPublicTierBeforeWrite(networkId, operationScope)) return
      postAPI('createLoadBalancerRule', {
        ignoreproject: true,
        ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}),
        openfirewall: false,
        networkid: networkId,
        publicipid: this.resource.id,
        algorithm: this.newRule.algorithm,
        name: this.newRule.name,
        privateport: this.newRule.privateport,
        protocol: this.newRule.protocol,
        publicport: this.newRule.publicport,
        cidrlist: this.newRule.cidrlist,
        backendssl: this.newRule.protocol === 'ssl' && this.newRule.backendssl
      }, { preserveOnFailure: true }).then(response => {
        if (this.networkMutationDisposed || this.networkMutationScope !== operationScope || this.networkMutationVersion !== operationVersion) return
        const rule = response.createloadbalancerruleresponse
        if (!rule?.id) {
          this.creationFailed = true
          this.creationUncertain = true
          this.loading = false
          this.fetchData()
          return
        }
        this.selectedRule = { id: rule.id }
        this.creationJob = response.createloadbalancerruleresponse.jobid
        this.associatednetworkid = networkId
        if (this.creationJob) this.finishRuleCreation()
        else this.handleAssignToLBRule(this.selectedRule.id)
      }).catch(error => {
        if (this.networkMutationDisposed || this.networkMutationScope !== operationScope || this.networkMutationVersion !== operationVersion) return
        this.creationUncertain = !networkError(error).definitive
        this.creationFailed = this.creationUncertain
        if (this.creationUncertain) this.fetchData()
        else this.$notifyError(error)
        this.loading = false
      })

      // assigntoloadbalancerruleresponse.jobid
    },
    async finishRuleCreation () {
      const operationScope = this.networkMutationScope
      const operationVersion = this.networkMutationVersion
      try {
        const result = await this.pollScopedNetworkJob({ jobId: this.creationJob, title: this.$t('label.network.rule.add'), catchMessage: this.$t('error.fetching.async.job.result') })
        if (this.networkMutationDisposed || this.networkMutationScope !== operationScope || this.networkMutationVersion !== operationVersion) return
        if (result.jobstatus === 1) { this.creationJob = null; this.handleAssignToLBRule(this.selectedRule.id) } else { this.loading = false; this.creationFailed = result.jobstatus === 2; this.fetchData() }
      } catch (error) {
        if (this.networkMutationDisposed || this.networkMutationScope !== operationScope || this.networkMutationVersion !== operationVersion) return
        this.$notifyError(error); this.loading = false
      }
    },
    closeModal () {
      if (this.stickinessModalVisible && this.$refs.stickinessDialog?.busy) return
      if (this.editRuleModalVisible && this.networkMutationBusy) return
      this.selectedRule = null
      this.clearBackendSelection()
      this.assignmentFailed = false
      this.creationJob = null
      this.creationFailed = false
      this.creationUncertain = false
      this.createModalVisible = false
      this.tagRule = null
      this.stickinessModalVisible = false
      this.editRuleModalVisible = false
      this.editRuleModalLoading = false
      this.addVmModalLoading = false
      this.addVmModalNicLoading = false
      this.showConfirmationAction = false
      this.vms = []
      this.nics = []
      this.addVmModalVisible = false
      this.newRule.virtualmachineid = []
      this.selectedBackends = {}
      this.addNetworkModalLoading = false
      this.addNetworkModalVisible = false
      this.selectedTierForAutoScaling = null
      this.addSslCertModalVisible = null
    },
    handleChangePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    handleChangePageSize (currentPage, pageSize) {
      this.page = currentPage
      this.pageSize = pageSize
      this.fetchData()
    },
    handleChangeVmPage (page, pageSize) {
      this.vmPage = page
      this.vmPageSize = pageSize
      this.fetchVirtualMachines()
    },
    handleChangeVmPageSize (currentPage, pageSize) {
      this.vmPage = currentPage
      this.vmPageSize = pageSize
      this.fetchVirtualMachines()
    },
    onSearch (value) {
      this.searchQuery = value
      this.fetchVirtualMachines()
    },
    returnHealthMonitorLabel (id) {
      const match = this.tungstenHealthMonitors.filter(item => item.lbruleid === id)
      if (match.length > 0) {
        return match[0].type
      }
      return this.$t('label.configure')
    },
    openHealthMonitorModal (id) {
      const match = this.tungstenHealthMonitors.filter(item => item.lbruleid === id)
      this.healthMonitorParams.lbruleid = id
      if (match.length > 0) {
        this.healthMonitorParams.type = match[0].type
        this.healthMonitorParams.retry = match[0].retry
        this.healthMonitorParams.timeout = match[0].timeout
        this.healthMonitorParams.interval = match[0].interval
        this.healthMonitorParams.httpmethodtype = match[0].httpmethod
        this.healthMonitorParams.expectedcode = match[0].expectedcode
        this.healthMonitorParams.urlpath = match[0].urlpath
      }
      this.initMonitorForm()
      this.healthMonitorModal = true
    },
    closeMonitorModal () {
      this.healthMonitorModal = false
      this.healthMonitorParams = {
        type: 'PING',
        retry: 3,
        timeout: 5,
        interval: 5,
        httpmethodtype: 'GET',
        expectedcode: undefined,
        urlpath: '/'
      }
    },
    handleConfigHealthMonitor () {
      if (this.healthMonitorLoading) return

      this.monitorRef.value.validate().then(() => {
        const values = toRaw(this.monitorForm)

        this.healthMonitorParams.type = values.type
        this.healthMonitorParams.retry = values.retry
        this.healthMonitorParams.timeout = values.timeout
        this.healthMonitorParams.interval = values.interval
        if (values.type === 'HTTP') {
          this.healthMonitorParams.httpmethodtype = values.httpmethodtype
          this.healthMonitorParams.expectedcode = values.expectedcode
          this.healthMonitorParams.urlpath = values.urlpath
        }

        this.healthMonitorLoading = true
        postAPI('updateTungstenFabricLBHealthMonitor', this.healthMonitorParams).then(json => {
          const jobId = json?.updatetungstenfabriclbhealthmonitorresponse?.jobid
          this.pollScopedNetworkJob({
            jobId: jobId,
            successMessage: this.$t('message.success.config.health.monitor'),
            successMethod: () => {
              this.parentToggleLoading()
              this.fetchData()
              this.closeMonitorModal()
              this.healthMonitorLoading = false
            },
            errorMessage: this.$t('message.config.health.monitor.failed'),
            errorMethod: () => {
              this.parentToggleLoading()
              this.fetchData()
              this.closeMonitorModal()
              this.healthMonitorLoading = false
            },
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.parentToggleLoading()
              this.fetchData()
              this.closeMonitorModal()
              this.healthMonitorLoading = false
            }
          })
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.healthMonitorLoading = false
        })
      }).catch((error) => {
        this.monitorRef.value.scrollToField(error.errorFields[0].name)
      }).finally(() => {
        this.healthMonitorLoading = false
      })
    }
  }
}
</script>

<style lang="scss" scoped>
  .public-lb-tier-table :deep(.ant-table-cell-fix-right) { background: var(--ui-bg-surface) !important; }
  .rule {

    &-container {
      display: flex;
      flex-direction: column;
      width: 100%;

      @media (min-width: 760px) {
        margin-right: -20px;
        margin-bottom: -10px;
      }

    }

    &__row {
      display: flex;
      flex-wrap: wrap;
    }

    &__item {
      padding-right: 20px;
      margin-bottom: 20px;

      @media (min-width: 760px) {
        flex: 1;
      }

    }

    &__title {
      font-weight: bold;
    }

  }

  .add-btn {
    width: 100%;
    padding-top: 15px;
    padding-bottom: 15px;
    height: auto;
  }

  .add-actions {
    display: flex;
    justify-content: flex-end;
    margin-right: -20px;
    margin-bottom: 20px;

    @media (min-width: 760px) {
      margin-top: 20px;
    }

    button {
      margin-right: 20px;
    }

  }

  .form {
    display: flex;
    margin-right: -20px;
    flex-direction: column;
    align-items: flex-start;

    @media (min-width: 760px) {
      flex-direction: row;
    }

    &__required {
      margin-right: 5px;
      color: red;
    }

    .error-text {
      display: none;
      color: red;
      font-size: 0.8rem;
    }

    .error {

      input {
        border-color: red;
      }

      .error-text {
        display: block;
      }

    }

    &--column {
      flex-direction: column;
      margin-right: 0;
      align-items: flex-end;

      .form__item {
        width: 100%;
        padding-right: 0;
      }

    }

    &__item {
      display: flex;
      flex-direction: column;
      padding-right: 20px;
      margin-bottom: 20px;

      @media (min-width: 1200px) {
        margin-bottom: 0;
        flex: 1;
      }

      input,
      .ant-select {
        margin-top: auto;
      }

      &__input-container {
        display: flex;

        input {

          &:not(:last-child) {
            margin-right: 10px;
          }

        }

      }

    }

    &__label {
      font-weight: bold;
    }

  }

  .rule-action {
    margin-bottom: 10px;
  }

  .modal-loading {
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    left: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: rgba(0,0,0,0.5);
    z-index: 1;
    color: #1890ff;
    font-size: 2rem;
  }

  .ant-list-item {
    display: flex;
    flex-direction: column;
    align-items: flex-start;

    @media (min-width: 760px) {
      flex-direction: row;
      align-items: center;
    }

  }

  .rule-instance-collapse {
    width: 100%;
    margin-left: -15px;

    .ant-collapse-item {
      border: 0;
    }

  }

  .rule-instance-list {
    display: flex;
    flex-direction: column;

    &__item {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 10px;

      div {
        margin-left: 25px;
        margin-bottom: 10px;
      }
    }
  }

  .edit-rule {

    .ant-select {
      width: 100%;
    }

    &__item {
      margin-bottom: 10px;
    }

    &__label {
      margin-bottom: 5px;
      font-weight: bold;
    }

  }

  .vm-modal {

    &__header {
      display: flex;

      span {
        flex: 1;
        font-weight: bold;
        margin-right: 10px;
      }

    }

    &__item {
      display: flex;
      margin-top: 10px;

      span,
      label {
        display: block;
        flex: 1;
        margin-right: 10px;
      }

    }

  }

  .custom-ant-form {
    .ant-form-item-label {
      font-weight: bold;
      line-height: 1;
    }
    .ant-form-item {
      margin-bottom: 10px;
    }
  }

  .custom-ant-list {
    .ant-list-item-action {
      margin-top: 10px;
      margin-left: 0;

      @media (min-width: 760px) {
        margin-top: 0;
        margin-left: 24px;
      }

    }
  }

  .rule-instance-collapse {
    .ant-collapse-header,
    .ant-collapse-content {
      margin-left: -12px;
    }
  }

  .rule {
    .ant-list-item-content-single {
      width: 100%;

      @media (min-width: 760px) {
        width: auto;
      }

    }
  }

  .pagination {
    margin-top: 20px;
    text-align: right;
  }

  .actions {
    button {
      &:not(:last-child) {
        margin-right: 10px;
      }
    }
  }

  .list-view {
    overflow-y: auto;
    display: block;
    width: 100%;
  }

  .filter {
    display: block;
    width: 240px;
    margin-bottom: 10px;
  }

  .input-search {
    margin-bottom: 10px;
    width: 50%;
    float: right;
  }
</style>
