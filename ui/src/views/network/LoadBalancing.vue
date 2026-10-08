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
  <div>
    <p v-if="listRefreshFailed" role="status">{{ $t('message.list.refresh.stale') }}</p>
    <network-rules-toolbar :can-add="'createLoadBalancerRule' in $store.getters.apis && !contextLoading" :loading="loading || contextLoading || listRefreshing > 0" @add="openCreateDialog" @refresh="refreshRules"><slot name="toolbar-context" /></network-rules-toolbar>
    <slot name="guidance" />
    <a-modal
centered
class="mold-dialog network-rule-dialog"
:visible="createModalVisible"
:title="$t('label.network.rule.add')"
:width="920"
:footer="null"
:mask-closable="false"
@cancel="createModalVisible = false">
      <div class="mold-form-dialog"><div class="mold-form-content"><NetworkRuleContext :resource="resource" /><a-alert v-if="creationFailed" type="error" show-icon class="mold-dialog-summary" :message="$t('message.network.lb.create.failed')" /><a-alert v-if="assignmentFailed" class="mold-dialog-summary" type="error" show-icon :message="$t('message.network.lb.retry')" />
    <div @keyup.ctrl.enter="handleAddNewRule">
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
          <a-button :disabled="!('createLoadBalancerRule' in $store.getters.apis)" type="primary" @click="handleOpenAddSslCertModal(null)">
            {{ this.selectedSsl.id != null ? this.selectedSsl.name : $t('label.add') }}
          </a-button>
        </div>
        <div class="form__item" v-if="newRule.protocol === 'ssl'">
          <div class="form__label">{{ $t('label.backend.ssl') }}</div>
          <a-switch v-model:checked="newRule.backendssl" />
        </div>

        <div class="form__item" v-if="newRule.autoscale === 'yes' && ('vpcid' in this.resource && !this.associatednetworkid)">
          <div class="form__label" style="white-space: nowrap;">{{ $t('label.select.tier') }}</div>
          <a-button :disabled="!('createLoadBalancerRule' in $store.getters.apis)" type="primary" @click="handleOpenAddNetworkModal">
            {{ $t('label.add') }}
          </a-button>
        </div>
        <div class="form__item" v-else-if="newRule.autoscale === 'yes'">
          <div class="form__label" style="white-space: nowrap;">{{ $t('label.add') }}</div>
          <a-button :disabled="!('createLoadBalancerRule' in $store.getters.apis)" type="primary" @click="handleAddNewRule">
            {{ $t('label.add') }}
          </a-button>
        </div>
      </div>
    </div>
      <div v-if="newRule.autoscale !== 'yes'" class="mold-dialog-section"><div class="network-form-group-label">{{ $t('label.network.lb.targets') }}</div><a-radio-group v-model:value="targetMode" @change="targetModeChanged"><a-radio-button value="select" :disabled="!('assignToLoadBalancerRule' in $store.getters.apis)">{{ $t('label.network.lb.select') }}</a-radio-button><a-radio-button value="later">{{ $t('label.network.lb.later') }}</a-radio-button></a-radio-group><a-alert class="mold-dialog-section" type="info" show-icon :message="$t(targetMode === 'later' ? 'message.network.lb.later' : 'message.network.lb.targets')" /><div v-if="targetMode === 'select'" class="mold-dialog-section">        <span
          v-if="'vpcid' in resource && (!('associatednetworkid' in resource) || vpcConserveMode)">
          <strong>{{ $t('label.select.tier') }} </strong>
          <a-select
            v-focus="'vpcid' in resource && (!('associatednetworkid' in resource) || vpcConserveMode)"
            v-model:value="selectedTier"
            @change="() => { selectedBackends = {}; fetchVirtualMachines() }"
            :placeholder="$t('label.select.tier')"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option
              v-for="tier in tiers.data"
              :loading="tiers.loading"
              :key="tier.id"
              :label="tier.displaytext">
              {{ tier.displaytext }}
            </a-select-option>
          </a-select>
        </span>
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
                  :label="nic + (nicIndex === 0 ? ' (' + $t('label.primary') + ')' : '')">
                  {{ nic }}{{ nicIndex === 0 ? ` (${$t('label.primary')})` : null }}
                </a-select-option>
              </a-select>
            </template>

            <template v-if="column.key === 'state'">
              <status :text="text ? text : ''" displayText></status>
            </template>

            <template v-if="column.key === 'actions'" style="text-align: center" :text="text">
              <a-checkbox :checked="!!selectedBackends[record.id]" :value="record.id" @change="e => fetchNics(e, record.id)" />
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
</div></div></div><div class="action-button"><a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button><a-button v-if="newRule.autoscale !== 'yes'" type="primary" :loading="loading" :disabled="contextLoading || creationFailed || addVmModalLoading || addVmModalNicLoading || (targetMode === 'select' && !Object.keys(selectedBackends).length) || Object.values(selectedBackends).some(selection => !selection.ips.length)" @click="handleAddNewRule">{{ $t('label.network.rule.add') }}</a-button></div></div>
    </a-modal>

    <a-divider />
    <a-button
      v-if="(('deleteLoadBalancerRule' in $store.getters.apis) && this.selectedItems.length > 0)"
      type="primary"
      danger
      style="width: 100%; margin-bottom: 15px"
      @click="bulkActionConfirmation()">
      <template #icon><delete-outlined /></template>
      {{ $t('label.action.bulk.delete.load.balancer.rules') }}
    </a-button>
    <a-table
      size="small"
      class="list-view"
      :loading="loading"
      :columns="compactColumns"
      :scroll="{ x: 1080 }"
      :dataSource="lbRules"
      :pagination="false"
      :rowSelection="{selectedRowKeys: selectedRowKeys, onChange: onSelectChange, getCheckboxProps: record => ({ disabled: isProtectedRule(record) })}"
      :rowKey="record => record.id"
      :expandRowByClick="true">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">{{ record.name }} <a-tag v-if="isProtectedRule(record)">{{ $t(ruleOwners[record.id] ? 'label.kubernetes.lb.' + ruleOwners[record.id].kind : 'label.kubernetes.lb.unknown') }}</a-tag></template>
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
            <a-dropdown :trigger="['click']" :disabled="isProtectedRule(record)">
              <a-button :disabled="isProtectedRule(record)">{{ $t('label.settings') }}<down-outlined /></a-button>
              <template #overlay><a-menu>
                <a-menu-item :disabled="!('updateLoadBalancerRule' in $store.getters.apis)" @click="openEditRuleModal(record)">{{ $t('label.edit') }}</a-menu-item>
                <a-menu-item :disabled="!!record.autoscalevmgroup || !('assignToLoadBalancerRule' in $store.getters.apis)" @click="selectedRule = record; handleOpenAddVMModal()">{{ $t('label.add.vms') }}</a-menu-item>
                <a-menu-item v-if="!isNetrisZone" :disabled="!('createLBStickinessPolicy' in $store.getters.apis)" @click="openStickinessModal(record.id)">{{ $t('label.action.configure.stickiness') }}</a-menu-item>
                <a-menu-item :disabled="record.protocol !== 'ssl'" @click="selectedRule = record; handleOpenAddSslCertModal(record)">{{ $t('label.sslcertificate') }}</a-menu-item>
                <a-menu-item v-if="record.autoscalevmgroup"><router-link :to="'/autoscalevmgroup/' + record.autoscalevmgroup.id">{{ $t('label.autoscale') }}</router-link></a-menu-item>
                <a-menu-item v-else-if="!record.ruleInstances?.length && 'createAutoScaleVmGroup' in $store.getters.apis"><router-link :to="{ path: '/action/createAutoScaleVmGroup', query: { networkid: record.networkid, lbruleid: record.id } }">{{ $t('label.autoscale') }}</router-link></a-menu-item>
                <a-menu-item v-if="columns.some(column => column.key === 'healthmonitor')" @click="openHealthMonitorModal(record.id)">{{ $t('label.action.health.monitor') }}</a-menu-item>
              </a-menu></template>
            </a-dropdown>
            <tooltip-button :tooltip="$t('label.edit.tags')" :disabled="isProtectedRule(record) || !('createTags' in $store.getters.apis)" icon="tag-outlined" @onClick="() => openTagsModal(record.id)" />
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
                :disabled='record.autoscalevmgroup || isProtectedRule(record)'
                :tooltip="$t('label.remove.vm.from.lb')"
                type="primary"
                :danger="true"
                icon="delete-outlined"
                @onClick="() => handleDeleteInstanceFromRule(instance, record, ip)" />
            </div>
          </div>
        </div>
      </template>
    </a-table>
    <a-pagination
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
    </a-pagination>

    <a-modal
centered
class="mold-dialog network-rule-dialog tags-modal"
      v-if="tagsModalVisible"
      :title="$t('label.edit.tags')"
      :visible="tagsModalVisible"
      :footer="null"
      :closable="true"
      :afterClose="closeModal"
      :maskClosable="false"

      @cancel="tagsModalVisible = false">
      <span v-show="tagsModalLoading" class="modal-loading">
        <loading-outlined />
      </span>

      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        class="add-tags"
        @finish="handleAddTag"
        v-ctrl-enter="handleAddTag"
       >
        <div class="add-tags__input">
          <p class="add-tags__label">{{ $t('label.key') }}</p>
          <a-form-item ref="key" name="key">
            <a-input
              v-focus="true"
              v-model:value="form.key" />
          </a-form-item>
        </div>
        <div class="add-tags__input">
          <p class="add-tags__label">{{ $t('label.value') }}</p>
          <a-form-item ref="value" name="value">
            <a-input v-model:value="form.value" />
          </a-form-item>
        </div>
        <a-button :disabled="!('createTags' in $store.getters.apis)" type="primary" ref="submit" @click="handleAddTag">{{ $t('label.add') }}</a-button>
      </a-form>

      <a-divider />

      <div v-show="!tagsModalLoading" class="tags-container">
        <div class="tags" v-for="(tag, index) in tags" :key="index">
          <a-tag :key="index" :closable="'deleteTags' in $store.getters.apis" @close="() => handleDeleteTag(tag)">
            {{ tag.key }} = {{ tag.value }}
          </a-tag>
        </div>
      </div>

      <a-button class="add-tags-done" @click="tagsModalVisible = false" type="primary">{{ $t('label.done') }}</a-button>
    </a-modal>

    <a-modal
centered
class="mold-dialog network-rule-dialog"
      :visible="stickinessModalVisible"
      :footer="null"
      :afterClose="closeModal"
      :maskClosable="false"
      :closable="true"
      :okButtonProps="{ props: {htmlType: 'submit'}}"
      @cancel="stickinessModalVisible = false">

      <template #title>
        <span>{{ $t('label.configure.sticky.policy') }}</span>
        <a
          style="margin-left: 5px"
          :href="$config.docBase + '/adminguide/networking/external_firewalls_and_load_balancers.html#sticky-session-policies-for-load-balancer-rules'"
          target="_blank">
          <question-circle-outlined />
        </a>
      </template>

      <span v-show="stickinessModalLoading" class="modal-loading">
        <loading-outlined />
      </span>

      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmitStickinessForm"
        v-ctrl-enter="handleSubmitStickinessForm"
        class="custom-ant-form"
       >
        <a-form-item name="methodname" ref="methodname">
          <template #label>
            <tooltip-label :title="$t('label.stickiness.method')" :tooltip="createLoadBalancerStickinessPolicyParams.methodname.description" :tooltip-placement="'right'"/>
          </template>
          <a-select
            v-focus="true"
            v-model:value="form.methodname"
            @change="handleStickinessMethodSelectChange"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option value="LbCookie" :label="$t('label.lb.cookie')">{{ $t('label.lb.cookie') }}</a-select-option>
            <a-select-option value="AppCookie" :label="$t('label.app.cookie')">{{ $t('label.app.cookie') }}</a-select-option>
            <a-select-option value="SourceBased" :label="$t('label.source.based')">{{ $t('label.source.based') }}</a-select-option>
            <a-select-option value="none" :label="$t('label.none')">{{ $t('label.none') }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item
          name="name"
          ref="name"
          v-show="stickinessPolicyMethod === 'LbCookie' || stickinessPolicyMethod ===
            'AppCookie' || stickinessPolicyMethod === 'SourceBased'">
          <a-input v-model:value="form.name" />
          <template #label>
            <tooltip-label :title="$t('label.sticky.name')" :tooltip="createLoadBalancerStickinessPolicyParams.name.description" :tooltip-placement="'right'"/>
          </template>
        </a-form-item>
        <div v-if="stickinessPolicyMethod !== 'none'">
          <br/>
          {{ $t('message.loadbalancer.stickypolicy.configuration') }}
          <br/>
          <a-card>
            <a-form-item
              name="cookieName"
              ref="cookieName"
              :label="$t('label.sticky.cookie-name')"
              v-show="stickinessPolicyMethod === 'LbCookie' || stickinessPolicyMethod ===
                'AppCookie'">
              <a-input v-model:value="form.cookieName" />
            </a-form-item>
            <a-form-item
              name="mode"
              ref="mode"
              :label="$t('label.sticky.mode')"
              v-show="stickinessPolicyMethod === 'LbCookie' || stickinessPolicyMethod ===
                'AppCookie'">
              <a-input v-model:value="form.mode" />
            </a-form-item>
            <a-form-item name="nocache" ref="nocache" :label="$t('label.sticky.nocache')" v-show="stickinessPolicyMethod === 'LbCookie'">
              <a-checkbox v-model:checked="form.nocache"></a-checkbox>
            </a-form-item>
            <a-form-item name="indirect" ref="indirect" :label="$t('label.sticky.indirect')" v-show="stickinessPolicyMethod === 'LbCookie'">
              <a-checkbox v-model:checked="form.indirect"></a-checkbox>
            </a-form-item>
            <a-form-item name="postonly" ref="postonly" :label="$t('label.sticky.postonly')" v-show="stickinessPolicyMethod === 'LbCookie'">
              <a-checkbox v-model:checked="form.postonly"></a-checkbox>
            </a-form-item>
            <a-form-item name="domain" ref="domain" :label="$t('label.domain')" v-show="stickinessPolicyMethod === 'LbCookie'">
              <a-input v-model:value="form.domain" />
            </a-form-item>
            <a-form-item name="length" ref="length" :label="$t('label.sticky.length')" v-show="stickinessPolicyMethod === 'AppCookie'">
              <a-input v-model:value="form.length" type="number" />
            </a-form-item>
            <a-form-item name="holdtime" ref="holdtime" :label="$t('label.sticky.holdtime')" v-show="stickinessPolicyMethod === 'AppCookie'">
              <a-input v-model:value="form.holdtime" type="number" />
            </a-form-item>
            <a-form-item name="requestLearn" ref="requestLearn" :label="$t('label.sticky.request-learn')" v-show="stickinessPolicyMethod === 'AppCookie'">
              <a-checkbox v-model:checked="form.requestLearn"></a-checkbox>
            </a-form-item>
            <a-form-item name="prefix" ref="prefix" :label="$t('label.sticky.prefix')" v-show="stickinessPolicyMethod === 'AppCookie'">
              <a-checkbox v-model:checked="form.prefix"></a-checkbox>
            </a-form-item>
            <a-form-item name="tablesize" ref="tablesize" :label="$t('label.sticky.tablesize')" v-show="stickinessPolicyMethod === 'SourceBased'">
              <a-input v-model:value="form.tablesize" />
            </a-form-item>
            <a-form-item name="expire" ref="expire" :label="$t('label.sticky.expire')" v-show="stickinessPolicyMethod === 'SourceBased'">
              <a-input v-model:value="form.expire" />
            </a-form-item>
          </a-card>
        </div>
        <div :span="24" class="action-button">
          <a-button @click="stickinessModalVisible = false">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" ref="submit" @click="handleSubmitStickinessForm">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-modal>

    <a-modal
centered
class="mold-dialog network-rule-dialog"
      :title="$t('label.edit.rule')"
      :visible="editRuleModalVisible"
      :afterClose="closeModal"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="editRuleModalVisible = false">
      <span v-show="editRuleModalLoading" class="modal-loading">
        <loading-outlined />
      </span>

      <div class="edit-rule" v-if="selectedRule" v-ctrl-enter="handleSubmitEditForm">
        <div class="edit-rule__item">
          <p class="edit-rule__label">{{ $t('label.name') }}</p>
          <a-input v-focus="true" v-model:value="editRuleDetails.name" />
        </div>
        <div v-if="lbProvider !== 'Netris'" class="edit-rule__item">
          <p class="edit-rule__label">{{ $t('label.algorithm') }}</p>
          <a-select
            v-model:value="editRuleDetails.algorithm"
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
            :placeholder="$t('label.sourcecidrlist')"
          />
        </div>
        <div class="edit-rule__item" v-if="editRuleDetails.protocol === 'ssl'">
          <p class="edit-rule__label">{{ $t('label.backend.ssl') }}</p>
          <a-switch v-model:checked="editRuleDetails.backendssl" />
        </div>
        <div :span="24" class="action-button">
          <a-button @click="() => editRuleModalVisible = false">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" @click="handleSubmitEditForm">{{ $t('label.ok') }}</a-button>
        </div>
      </div>
    </a-modal>

    <a-modal
centered
class="mold-dialog network-rule-dialog"
      :title="$t('label.add.vms')"
      :maskClosable="false"
      :closable="true"
      v-if="addVmModalVisible"
      :visible="addVmModalVisible"

      :width="920"
      :footer="null"
      @cancel="closeModal"
    >
      <a-alert class="mold-dialog-summary" type="info" show-icon :message="$t('message.network.lb.targets')" />
      <a-alert v-if="creationFailed" type="error" show-icon class="mold-dialog-summary" :message="$t('message.network.lb.create.failed')" />
      <a-alert v-if="assignmentFailed" class="mold-dialog-summary" type="error" show-icon :message="$t('message.network.lb.retry')" />
      <div class="mold-form-dialog"><div class="mold-form-content" @keyup.ctrl.enter="handleAddNewRule">
        <span
          v-if="'vpcid' in resource && (!('associatednetworkid' in resource) || vpcConserveMode)">
          <strong>{{ $t('label.select.tier') }} </strong>
          <a-select
            v-focus="'vpcid' in resource && (!('associatednetworkid' in resource) || vpcConserveMode)"
            v-model:value="selectedTier"
            @change="() => { selectedBackends = {}; fetchVirtualMachines() }"
            :placeholder="$t('label.select.tier')"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option
              v-for="tier in tiers.data"
              :loading="tiers.loading"
              :key="tier.id"
              :label="tier.displaytext">
              {{ tier.displaytext }}
            </a-select-option>
          </a-select>
        </span>
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
              <a-checkbox :checked="!!selectedBackends[record.id]" :value="record.id" @change="e => fetchNics(e, record.id)" />
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
          <a-button :disabled="loading || contextLoading || creationFailed || addVmModalLoading || addVmModalNicLoading || Object.values(selectedBackends).some(selection => !selection.ips.length)" type="primary" ref="submit" @click="handleAddNewRule">{{ $t('label.ok') }}</a-button>
        </div>
      </div>
    </a-modal>

    <a-modal
centered
class="mold-dialog network-rule-dialog"
      :title="$t('label.manage.ssl.cert')"
      :maskClosable="false"
      :closable="true"
      v-if="addSslCertModalVisible"
      :visible="addSslCertModalVisible"
      width="30vw"
      @cancel="addSslCertModalVisible = false"
      @ok="addSslCertModalVisible = false"
      :cancelButtonProps="{ style: { display: 'none' } }"
    >
      <a-row v-show="showAssignedSsl && assignedSslCert !== 'None'">
        <a-col :span="8">
          <div class="form__label">{{ $t("label.current") + ' ' + $t('label.sslcertificate') }}</div>
        </a-col>
        <a-col :span="10">
          <div>{{ assignedSslCert }}</div>
        </a-col>
        <a-col :span="6">
          <a-button :disabled="!deleteSslButtonVisible" type="danger" @click="removeSslFromLbRule()">
            <template #icon><delete-outlined /></template>
            {{ $t('label.remove') }}
          </a-button>
        </a-col>
      </a-row>
      <a-row style="margin-top: 16px">
        <a-col :span="8">
          <div class="form__label">{{ $t("label.new") + ' ' + $t('label.sslcertificate') }}</div>
        </a-col>
        <a-col :span="10">
          <div class="form__item">
            <a-select v-model:value="selectedSsl.name" style="width: 80%;" @change="selectssl">
              <a-select-option
                v-for="sslcert in sslcerts.data"
                :key="sslcert.id">{{ sslcert.name }}
              </a-select-option>
            </a-select>
          </div>
        </a-col>
        <a-col :span="6">
          <div>
            <a-button v-show="addSslButtonVisible && assignedSslCert !== 'None'" type="primary" @click="addSslTolbRule()">
              <template #icon><swap-outlined /></template>
              {{ $t('label.replace') }}
            </a-button>
            <a-button v-show="addSslButtonVisible && assignedSslCert === 'None'" type="primary" @click="addSslTolbRule()">
              <template #icon><plus-outlined /></template>
              {{ $t('label.assign') }}
            </a-button>
          </div>
        </a-col>
      </a-row>
    </a-modal>

    <a-modal
centered
class="mold-dialog network-rule-dialog network-modal"
      :title="$t('label.select.tier')"
      :maskClosable="false"
      :closable="true"
      v-if="addNetworkModalVisible"
      :visible="addNetworkModalVisible"

      :width="920"
      :footer="null"
      @cancel="closeModal"
    >
      <div @keyup.ctrl.enter="handleAddNewRule">
        <a-input-search
          v-focus="!('vpcid' in resource && !('associatednetworkid' in resource))"
          class="input-search"
          :placeholder="$t('label.search')"
          v-model:value="searchQuery"
          allowClear
          @search="onNetworkSearch" />
        <a-table
          size="small"
          class="list-view"
          :loading="addNetworkModalLoading"
          :columns="networkColumns"
          :dataSource="networks"
          :pagination="false"
          :rowKey="record => record.id"
          :scroll="{ y: 300 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'actions'">
              <div style="text-align: center">
                <a-radio-group
                  class="radio-group"
                  :key="record.id"
                  v-model:value="this.selectedTierForAutoScaling"
                  @change="($event) => this.selectedTierForAutoScaling = $event.target.value">
                  <a-radio :value="record.id" />
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

        <div :span="24" class="action-button">
          <a-button @click="closeModal">{{ $t('label.cancel') }}</a-button>
          <a-button :disabled="this.selectedTierForAutoScaling === null" type="primary" ref="submit" @click="handleAddNewRule">{{ $t('label.ok') }}</a-button>
        </div>
      </div>
    </a-modal>

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
import { validPortRange, loadBalancerIsProtected, selectedBackendMap } from '@/utils/networkRuleForm'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { ref, reactive, toRaw, nextTick } from 'vue'
import NetworkRuleContext from '@/components/view/NetworkRuleContext'
import NetworkRulesToolbar from '@/components/view/NetworkRulesToolbar'
import { getAPI, postAPI } from '@/api'
import { mixinForm } from '@/utils/mixin'
import Status from '@/components/widgets/Status'
import TooltipButton from '@/components/widgets/TooltipButton'
import BulkActionView from '@/components/view/BulkActionView'
import eventBus from '@/config/eventBus'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'LoadBalancing',
  mixins: [listRefreshMixin(['fetchLBRules']), mixinForm],
  components: {
    NetworkRuleContext,
    NetworkRulesToolbar,
    Status,
    TooltipButton,
    BulkActionView,
    TooltipLabel
  },
  props: {
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
      creationJob: null,
      creationFailed: false,
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
      tagsModalVisible: false,
      tagsModalLoading: false,
      tags: [],
      selectedRule: null,
      selectedTier: null,
      stickinessModalVisible: false,
      stickinessPolicies: [],
      stickinessModalLoading: false,
      selectedStickinessPolicy: null,
      stickinessPolicyMethod: 'LbCookie',
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
      sslcerts: {
        loading: false,
        data: []
      },
      selectedSsl: {
        name: '',
        id: null
      },
      addSslCertModalVisible: false,
      showAssignedSsl: false,
      currentAccountId: null,
      assignedSslCert: 'None',
      deleteSslButtonVisible: true,
      addSslButtonVisible: true,
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
        data: []
      },
      vmPage: 1,
      vmPageSize: 10,
      vmCount: 0,
      addNetworkModalVisible: false,
      addNetworkModalLoading: false,
      networks: [],
      associatednetworkid: null,
      selectedTierForAutoScaling: null,
      networkColumns: [
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
          title: this.$t('label.gateway'),
          dataIndex: 'gateway'
        },
        {
          title: this.$t('label.netmask'),
          dataIndex: 'netmask'
        },
        {
          key: 'actions',
          title: this.$t('label.select'),
          dataIndex: 'actions',
          width: 80
        }
      ],
      networkPage: 1,
      networkPageSize: 10,
      networkCount: 0,
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
    this.createLoadBalancerStickinessPolicyParams = this.$getApiParams('createLBStickinessPolicy')
    if ('associatednetworkid' in this.resource) {
      this.associatednetworkid = this.resource.associatednetworkid
    }
  },
  created () {
    this.initForm()
    this.initMonitorForm()
    this.fetchData()
  },
  watch: {
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
      getAPI('listVPCs', {
        id: this.resource.vpcid
      }).then(json => {
        this.vpcConserveMode = json.listvpcsresponse?.vpc?.[0].vpcofferingconservemode || false
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    fetchListTiers () {
      this.tiers.loading = true

      getAPI('listNetworks', {
        supportedservices: 'Lb',
        isrecursive: true,
        vpcid: this.resource.vpcid
      }).then(json => {
        this.tiers.data = json.listnetworksresponse.network || []
        this.selectedTier = this.tiers.data?.[0]?.id ? this.tiers.data[0].id : null
        if (this.tiers.data?.[0]?.broadcasturi === 'tf://tf') {
          this.columns.splice(8, 0, {
            title: this.$t('label.action.health.monitor'),
            key: 'healthmonitor'
          })
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => { this.tiers.loading = false })
    },
    async fetchLBRules () {
      const listRequest = this.listRequestToken('fetchLBRules')
      this.loading = !listRequest.loaded
      try {
        const response = await getAPI('listLoadBalancerRules', {
          listAll: true, publicipid: this.resource.id, page: this.page, pageSize: this.pageSize
        })
        if (!this.isListRequestCurrent('fetchLBRules', listRequest)) return
        const rules = response.listloadbalancerrulesresponse.loadbalancerrule || []
        const snapshots = await Promise.all(rules.map(async rule => {
          const apis = this.$store.getters.apis
          const [instances, stickiness, autoscale, monitor] = await Promise.all([
            getAPI('listLoadBalancerRuleInstances', { listAll: true, lbvmips: true, id: rule.id }),
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
      getAPI('listZones', {
        id: this.resource.zoneid
      }).then(response => {
        this.lbProvider = response?.listzonesresponse?.zone?.[0]?.provider || null
        if (this.lbProvider != null) {
          this.isNetrisZone = this.lbProvider === 'Netris'
        }
      }).finally(() => {
        this.zoneloading = false
        if (this.lbProvider !== 'Netris') {
          this.column.push({
            key: 'algorithm',
            title: this.$t('label.algorithm')
          })
        }
      })
    },
    fetchSslCerts () {
      this.sslcerts.loading = true
      this.sslcerts.data = []
      // First get the account id
      getAPI('listAccounts', {
        name: this.resource.account,
        domainid: this.resource.domainid
      }).then(json => {
        const accounts = json.listaccountsresponse.account || []
        if (accounts.length > 0) {
          // Now fetch all the ssl certs for this account
          this.currentAccountId = accounts[0].id
          getAPI('listSslCerts', {
            accountid: this.currentAccountId
          }).then(json => {
            json.listsslcertsresponse.sslcert.forEach(entry => this.sslcerts.data.push(entry))
            if (json.listsslcertsresponse.sslcert && json.listsslcertsresponse.sslcert.length > 0 && this.selectedSsl.id == null) {
              this.selectedSsl.name = json.listsslcertsresponse.sslcert[0].name
              this.selectedSsl.id = json.listsslcertsresponse.sslcert[0].id
            }
          }).catch(error => {
            this.$notifyError(error)
          })
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.sslcerts.loading = false
      })
      if (this.selectedRule !== null) {
        this.getCurrentAssignedSslCert()
      }
    },
    getCurrentAssignedSslCert () {
      getAPI('listSslCerts', {
        accountid: this.currentAccountId,
        lbruleid: this.selectedRule.id
      }).then(json => {
        if (json.listsslcertsresponse.sslcert && json.listsslcertsresponse.sslcert.length > 0) {
          this.assignedSslCert = json.listsslcertsresponse.sslcert[0].name
          this.deleteSslButtonVisible = true
        } else {
          this.assignedSslCert = 'None'
          this.deleteSslButtonVisible = false
        }
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    selectssl (e) {
      this.selectedSsl.id = e
      const sslcert = this.sslcerts.data.find(entry => entry.id === this.selectedSsl.id)
      if (sslcert) {
        this.selectedSsl.name = sslcert.name
      }
    },
    handleAddSslCert (data) {
      this.addSslCert(data, this.selectedSsl.id)
    },
    addSslTolbRule () {
      this.visible = false
      this.addSslCert(this.selectedRule.id, this.selectedSsl.id)
    },
    addSslCert (lbRuleId, certId) {
      this.disableSslAddDeleteButtons()
      getAPI('assignCertToLoadBalancer', {
        lbruleid: lbRuleId,
        certid: certId,
        forced: true
      }).then(response => {
        this.$pollJob({
          jobId: response.assigncerttoloadbalancerresponse.jobid,
          successMessage: this.$t('message.success.assign.sslcert'),
          successMethod: () => {
            if (this.selectedRule !== null) {
              this.getCurrentAssignedSslCert()
            }
            this.enableSslAddDeleteButtons()
          },
          errorMessage: this.$t('message.assign.sslcert.failed'),
          errorMethod: () => {
          },
          loadingMessage: this.$t('message.assign.sslcert.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: (e) => {
            this.closeModal()
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
      })
    },
    removeSslFromLbRule () {
      this.disableSslAddDeleteButtons()
      getAPI('removeCertFromLoadBalancer', {
        lbruleid: this.selectedRule.id
      }).then(response => {
        this.$pollJob({
          jobId: response.removecertfromloadbalancerresponse.jobid,
          successMessage: this.$t('message.success.remove.sslcert'),
          successMethod: () => {
            this.visible = true
            this.getCurrentAssignedSslCert()
            this.enableSslAddDeleteButtons()
          },
          errorMessage: this.$t('message.remove.sslcert.failed'),
          errorMethod: () => {
            this.visible = true
          },
          loadingMessage: this.$t('message.remove.sslcert.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.closeModal()
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
      })
    },
    enableSslAddDeleteButtons () {
      this.deleteSslButtonVisible = true
      this.addSslButtonVisible = true
    },
    disableSslAddDeleteButtons () {
      this.addSslButtonVisible = false
      this.deleteSslButtonVisible = false
    },
    handleOpenAddSslCertModal (record) {
      this.addSslCertModalVisible = true
      if (record) {
        this.showAssignedSsl = true
        this.addSslButtonVisible = true
        this.selectedSsl = {}
      } else {
        this.showAssignedSsl = false
        this.addSslButtonVisible = false
      }
      this.fetchSslCerts()
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
      if (match.length > 0 && match[0].stickinesspolicy.length > 0) {
        return match[0].stickinesspolicy[0].methodname
      }
      return 'Configure'
    },
    getCapitalise (val) {
      if (!val) {
        return
      }
      if (val === 'all') return this.$t('label.all')
      return val.toUpperCase()
    },
    openTagsModal (id) {
      this.initForm()
      this.rules = {
        key: [{ required: true, message: this.$t('message.specify.tag.key') }],
        value: [{ required: true, message: this.$t('message.specify.tag.value') }]
      }
      this.tagsModalLoading = true
      this.tagsModalVisible = true
      this.tags = []
      this.selectedRule = id
      getAPI('listTags', {
        resourceId: id,
        resourceType: 'LoadBalancer',
        listAll: true
      }).then(response => {
        this.tags = response.listtagsresponse.tag
        this.tagsModalLoading = false
      }).catch(error => {
        this.$notifyError(error)
        this.closeModal()
      })
    },
    handleAddTag (e) {
      if (this.tagsModalLoading) return
      this.tagsModalLoading = true

      e.preventDefault()
      this.formRef.value.validate().then(() => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)

        postAPI('createTags', {
          'tags[0].key': values.key,
          'tags[0].value': values.value,
          resourceIds: this.selectedRule,
          resourceType: 'LoadBalancer'
        }).then(response => {
          this.$pollJob({
            jobId: response.createtagsresponse.jobid,
            successMessage: this.$t('message.success.add.tag'),
            successMethod: () => {
              this.parentToggleLoading()
              this.openTagsModal(this.selectedRule)
            },
            errorMessage: this.$t('message.add.tag.failed'),
            errorMethod: () => {
              this.parentToggleLoading()
              this.closeModal()
            },
            loadingMessage: this.$t('message.add.tag.processing'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.parentFetchData()
              this.parentToggleLoading()
              this.closeModal()
            }
          })
        }).catch(error => {
          this.$notifyError(error)
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      }).finally(() => {
        this.tagsModalLoading = false
      })
    },
    handleDeleteTag (tag) {
      this.tagsModalLoading = true
      postAPI('deleteTags', {
        'tags[0].key': tag.key,
        'tags[0].value': tag.value,
        resourceIds: tag.resourceid,
        resourceType: 'LoadBalancer'
      }).then(response => {
        this.$pollJob({
          jobId: response.deletetagsresponse.jobid,
          successMessage: this.$t('message.success.delete.tag'),
          successMethod: () => {
            this.parentToggleLoading()
            this.openTagsModal(this.selectedRule)
          },
          errorMessage: this.$t('message.delete.tag.failed'),
          errorMethod: () => {
            this.parentToggleLoading()
            this.closeModal()
          },
          loadingMessage: this.$t('message.delete.tag.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentFetchData()
            this.parentToggleLoading()
            this.closeModal()
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    openStickinessModal (id) {
      this.initForm()
      this.rules = {
        methodname: [{ required: true, message: this.$t('message.error.specify.stickiness.method') }]
      }
      this.stickinessModalVisible = true
      this.selectedRule = id
      const match = this.stickinessPolicies.find(policy => policy.lbruleid === id)

      if (match && match.stickinesspolicy.length > 0) {
        this.selectedStickinessPolicy = match.stickinesspolicy[0]
        this.stickinessPolicyMethod = this.selectedStickinessPolicy.methodname
        nextTick().then(() => {
          this.form.methodname = this.selectedStickinessPolicy.methodname
          this.form.name = this.selectedStickinessPolicy.name
          this.form.cookieName = this.selectedStickinessPolicy.params['cookie-name']
          this.form.mode = this.selectedStickinessPolicy.params.mode
          this.form.domain = this.selectedStickinessPolicy.params.domain
          this.form.length = this.selectedStickinessPolicy.params.length
          this.form.holdtime = this.selectedStickinessPolicy.params.holdtime
          this.form.nocache = !!this.selectedStickinessPolicy.params.nocache
          this.form.indirect = !!this.selectedStickinessPolicy.params.indirect
          this.form.postonly = !!this.selectedStickinessPolicy.params.postonly
          this.form.requestLearn = !!this.selectedStickinessPolicy.params['request-learn']
          this.form.prefix = !!this.selectedStickinessPolicy.params.prefix
        })
      }
    },
    handleAddStickinessPolicy (data, values) {
      postAPI('createLBStickinessPolicy', {
        ...data,
        lbruleid: this.selectedRule,
        name: values.name,
        methodname: values.methodname
      }).then(response => {
        this.$pollJob({
          jobId: response.createLBStickinessPolicy.jobid,
          successMessage: this.$t('message.success.config.sticky.policy'),
          successMethod: () => {
            this.parentToggleLoading()
            this.fetchData()
            this.closeModal()
          },
          errorMessage: this.$t('message.config.sticky.policy.failed'),
          errorMethod: () => {
            this.parentToggleLoading()
            this.fetchData()
            this.closeModal()
          },
          loadingMessage: this.$t('message.config.sticky.policy.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentFetchData()
            this.parentToggleLoading()
            this.fetchData()
            this.closeModal()
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.closeModal()
      })
    },
    handleDeleteStickinessPolicy () {
      this.stickinessModalLoading = true
      postAPI('deleteLBStickinessPolicy', { id: this.selectedStickinessPolicy.id }).then(response => {
        this.$pollJob({
          jobId: response.deleteLBstickinessrruleresponse.jobid,
          successMessage: this.$t('message.success.remove.sticky.policy'),
          successMethod: () => {
            this.parentToggleLoading()
            this.fetchData()
            this.closeModal()
          },
          errorMessage: this.$t('message.remove.sticky.policy.failed'),
          errorMethod: () => {
            this.parentToggleLoading()
            this.fetchData()
            this.closeModal()
          },
          loadingMessage: this.$t('message.remove.sticky.policy.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentFetchData()
            this.parentToggleLoading()
            this.fetchData()
            this.closeModal()
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    handleSubmitStickinessForm (e) {
      if (this.stickinessModalLoading) return
      this.stickinessModalLoading = true
      e.preventDefault()
      this.formRef.value.validate().then(() => {
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)
        if (values.methodname === 'none') {
          this.handleDeleteStickinessPolicy()
          return
        }

        if (values.name === null || values.name === undefined || values.name === '') {
          this.$notification.error({
            message: this.$t('label.error'),
            description: this.$t('message.error.specify.sticky.name')
          })
          return
        }

        values.nocache = this.form.nocache
        values.indirect = this.form.indirect
        values.postonly = this.form.postonly
        values.requestLearn = this.form.requestLearn
        values.prefix = this.form.prefix

        let data = {}
        let count = 0
        Object.entries(values).forEach(([key, val]) => {
          if (val && key !== 'name' && key !== 'methodname') {
            if (key === 'cookieName') {
              data = { ...data, ...{ [`param[${count}].name`]: 'cookie-name' } }
            } else if (key === 'requestLearn') {
              data = { ...data, ...{ [`param[${count}].name`]: 'request-learn' } }
            } else {
              data = { ...data, ...{ [`param[${count}].name`]: key } }
            }
            data = { ...data, ...{ [`param[${count}].value`]: val } }
            count++
          }
        })

        this.handleAddStickinessPolicy(data, values)
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      }).finally(() => {
        this.stickinessModalLoading = false
      })
    },
    handleStickinessMethodSelectChange (e) {
      if (this.formRef.value) this.formRef.value.resetFields()
      this.stickinessPolicyMethod = e
      this.form.methodname = e
    },
    handleDeleteInstanceFromRule (instance, rule, ip) {
      if (this.isProtectedRule(rule)) return
      this.loading = true
      postAPI('removeFromLoadBalancerRule', {
        id: rule.id,
        'vmidipmap[0].vmid': instance.loadbalancerruleinstance.id,
        'vmidipmap[0].vmip': ip
      }).then(response => {
        this.$pollJob({
          jobId: response.removefromloadbalancerruleresponse.jobid,
          successMessage: this.$t('message.success.remove.instance.rule'),
          successMethod: () => {
            this.fetchData()
          },
          errorMessage: this.$t('message.remove.instance.failed'),
          errorMethod: () => {
            this.fetchData()
          },
          loadingMessage: this.$t('message.remove.instance.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.fetchData()
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.fetchData()
      })
    },
    openEditRuleModal (rule) {
      if (this.isProtectedRule(rule)) return
      this.selectedRule = rule
      this.editRuleModalVisible = true
      this.editRuleDetails.name = this.selectedRule.name
      this.editRuleDetails.algorithm = this.lbProvider !== 'Netris' ? this.selectedRule.algorithm : undefined
      this.editRuleDetails.protocol = this.selectedRule.protocol
      // Normalize cidrlist: replace spaces with commas and clean up
      this.editRuleDetails.cidrlist = (this.selectedRule.cidrlist || '')
        .split(/[\s,]+/) // Split on spaces or commas
        .map(c => c.trim())
        .filter(c => c)
        .join(',') || ''
      this.editRuleDetails.backendssl = this.selectedRule.protocol === 'ssl' && !!this.selectedRule.backendssl
    },
    handleSubmitEditForm () {
      if (this.editRuleModalLoading) return
      this.loading = true
      this.editRuleModalLoading = true
      const payload = {
        ...this.editRuleDetails,
        id: this.selectedRule.id,
        backendssl: this.editRuleDetails.protocol === 'ssl' && this.editRuleDetails.backendssl,
        ...(this.editRuleDetails.cidrlist && {
          cidrList: (this.editRuleDetails.cidrlist || '').split(',').map(c => c.trim()).filter(c => c)
        })
      }
      postAPI('updateLoadBalancerRule', payload).then(response => {
        this.$pollJob({
          jobId: response.updateloadbalancerruleresponse.jobid,
          successMessage: this.$t('message.success.edit.rule'),
          successMethod: () => {
            this.parentToggleLoading()
            this.fetchData()
            this.closeModal()
          },
          errorMessage: this.$t('message.edit.rule.failed'),
          errorMethod: () => {
            this.parentToggleLoading()
            this.fetchData()
            this.closeModal()
          },
          loadingMessage: this.$t('message.edit.rule.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentFetchData()
            this.parentToggleLoading()
            this.fetchData()
            this.closeModal()
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.loading = false
      })
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
        this.$pollJob({
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
      this.closeModal(); this.createModalVisible = true
      this.targetMode = 'assignToLoadBalancerRule' in this.$store.getters.apis ? 'select' : 'later'
      this.vmPage = 1; this.searchQuery = ''; this.fetchVirtualMachines()
    },
    targetModeChanged () { this.selectedBackends = {} },
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
      if (!e.target.checked) { delete this.selectedBackends[id]; return }
      const selection = { ips: [], options: [], request: ++this.nicRequest }
      this.selectedBackends[id] = selection
      this.nicPending++; this.addVmModalNicLoading = true
      const networkid = ('vpcid' in this.resource && (!('associatednetworkid' in this.resource) || this.vpcConserveMode)) ? this.selectedTier : this.resource.associatednetworkid
      try {
        const response = await getAPI('listNics', { virtualmachineid: id, networkid })
        if (this.selectedBackends[id]?.request !== selection.request) return
        const nic = response.listnicsresponse?.nic?.find(nic => nic.networkid === networkid)
        if (!nic?.ipaddress) throw new Error(this.$t('label.nic'))
        this.selectedBackends[id] = { ips: [nic.ipaddress], options: [nic.ipaddress, ...(nic.secondaryip || []).map(ip => ip.ipaddress)] }
      } catch (error) { this.$notifyError(error); if (this.selectedBackends[id]?.request === selection.request) delete this.selectedBackends[id] } finally { this.nicPending--; this.addVmModalNicLoading = this.nicPending > 0 }
    },
    fetchVirtualMachines () {
      this.vmCount = 0
      this.vms = []
      this.addVmModalLoading = true
      const networkId = ('vpcid' in this.resource && (!('associatednetworkid' in this.resource) || this.vpcConserveMode)) ? this.selectedTier : this.resource.associatednetworkid
      if (!networkId) {
        this.addVmModalLoading = false
        return
      }
      getAPI('listVirtualMachines', {
        listAll: true,
        keyword: this.searchQuery,
        page: this.vmPage,
        pagesize: this.vmPageSize,
        networkid: networkId
      }).then(response => {
        this.vmCount = response.listvirtualmachinesresponse.count || 0
        this.vms = response.listvirtualmachinesresponse.virtualmachine || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
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
      this.networkCount = 0
      this.networks = []
      this.addNetworkModalLoading = true
      const vpcid = this.resource.vpcid
      if (!vpcid) {
        this.addNetworkModalLoading = false
        return
      }
      getAPI('listNetworks', {
        listAll: true,
        keyword: this.searchQuery,
        page: this.networkPage,
        pagesize: this.networkPageSize,
        supportedservices: 'Lb',
        isrecursive: true,
        vpcid: vpcid
      }).then(response => {
        this.networkCount = response.listnetworksresponse.count || 0
        this.networks = response.listnetworksresponse.network || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.addNetworkModalLoading = false
      })
      this.selectedTierForAutoScaling = null
    },
    onNetworkSearch (value) {
      this.searchQuery = value
      this.fetchNetworks()
    },
    handleChangeNetworkPage (page, pageSize) {
      this.networkPage = page
      this.networkPageSize = pageSize
      this.fetchNetworks()
    },
    handleChangeNetworkPageSize (currentPage, pageSize) {
      this.networkPage = currentPage
      this.networkPageSize = pageSize
      this.fetchNetworks()
    },
    handleAssignToLBRule (data) {
      const vmIDIpMap = selectedBackendMap(this.selectedBackends, this.vpcConserveMode, this.selectedTier)
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
        this.$pollJob({
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
      }).catch(error => { this.$notifyError(error); this.assignmentFailed = true; this.loading = false })
    },
    handleAddNewRule () {
      if (this.loading || this.contextLoading || this.addVmModalNicLoading || this.creationFailed || (this.createModalVisible && this.targetMode === 'select' && !Object.keys(this.selectedBackends).length) || Object.values(this.selectedBackends).some(selection => !selection.ips.length)) return
      this.loading = true

      if (this.creationJob) { this.finishRuleCreation(); return }
      if (this.selectedRule) {
        this.handleAssignToLBRule(this.selectedRule.id)
        return
      } else if (!this.checkNewRule()) {
        this.loading = false
        return
      }

      const networkId = this.selectedTierForAutoScaling != null ? this.selectedTierForAutoScaling
        : ('vpcid' in this.resource && !('associatednetworkid' in this.resource)) ? this.selectedTier : this.resource.associatednetworkid
      postAPI('createLoadBalancerRule', {
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
      }).then(response => {
        this.selectedRule = { id: response.createloadbalancerruleresponse.id }
        this.creationJob = response.createloadbalancerruleresponse.jobid
        this.associatednetworkid = networkId
        if (this.creationJob) this.finishRuleCreation()
        else this.handleAssignToLBRule(this.selectedRule.id)
      }).catch(error => {
        this.$notifyError(error)
        this.loading = false
      })

      // assigntoloadbalancerruleresponse.jobid
    },
    async finishRuleCreation () {
      try {
        const result = await this.$pollJob({ jobId: this.creationJob, title: this.$t('label.network.rule.add'), catchMessage: this.$t('error.fetching.async.job.result') })
        if (result.jobstatus === 1) { this.creationJob = null; this.handleAssignToLBRule(this.selectedRule.id) } else { this.loading = false; this.creationFailed = result.jobstatus === 2; this.fetchData() }
      } catch (error) { this.$notifyError(error); this.loading = false }
    },
    closeModal () {
      this.selectedRule = null
      this.assignmentFailed = false
      this.creationJob = null
      this.creationFailed = false
      this.createModalVisible = false
      this.tagsModalVisible = false
      this.stickinessModalVisible = false
      this.stickinessModalLoading = false
      this.selectedStickinessPolicy = null
      this.stickinessPolicyMethod = 'LbCookie'
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
          this.$pollJob({
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

  .tags-modal {

    .ant-divider {
      margin-top: 0;
    }

  }

  .tags {
    margin-bottom: 10px;
  }

  .add-tags {
    display: flex;
    align-items: center;
    justify-content: space-between;

    &__input {
      margin-right: 10px;
    }

    &__label {
      margin-bottom: 5px;
      font-weight: bold;
    }

  }

  .tags-container {
    display: flex;
    flex-wrap: wrap;
    margin-bottom: 10px;
  }

  .add-tags-done {
    display: block;
    margin-left: auto;
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
