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
  <div class="security-group-rules">
    <div class="detail-tab-toolbar">
      <a-button class="add-rule-button" type="primary" :disabled="!canAddRule || isSubmitted" @click="openAddRule">
        <template #icon><plus-outlined /></template>
        {{ $t('label.add.rule') }}
      </a-button>
    </div>
    <a-alert v-if="ruleError && !addRuleVisible" class="detail-tab-notice" type="error" show-icon :message="ruleError" />

    <detail-tab-table
      size="small"
      :columns="columns"
      :data-source="rules"
      :loading="loading"
      :pagination="pagination"
      :scroll="{ x: 1000 }"
      row-key="ruleid">
      <template #bodyCell="{ column, text, record }">
        <template v-if="column.key === 'protocol'">
          {{ getCapitalise(record.protocol) }}
        </template>
        <template v-if="column.key === 'cidr'">
          <span class="rule-target">{{ text }}</span>
        </template>
        <template v-if="column.key === 'account'">
          <div v-if="record.account && record.securitygroupname" class="rule-target">
            {{ record.account }} - {{ record.securitygroupname }}
          </div>
        </template>
        <template v-if="column.key === 'startport'">
          <div v-if="!['tcp', 'udp', 'icmp'].includes(record.protocol)">{{ $t('label.all') }}</div>
          <div v-else>{{ text }}</div>
        </template>
        <template v-if="column.key === 'endport'">
          <div v-if="!['tcp', 'udp', 'icmp'].includes(record.protocol)">{{ $t('label.all') }}</div>
          <div v-else>{{ text }}</div>
        </template>
        <template v-if="column.key === 'actions'">
          <div class="rule-actions">
            <tooltip-button :tooltip="$t('label.edit.tags')" icon="tag-outlined" @onClick="() => openTagsModal(record)" />
            <a-popconfirm
              :title="$t('label.delete') + '?'"
              @confirm="handleDeleteRule(record)"
              :okText="$t('label.yes')"
              :cancelText="$t('label.no')"
            >
              <tooltip-button
                :disabled="!canDeleteRule || isSubmitted"
                :tooltip="$t('label.delete')"
                type="primary"
                :danger="true"
                icon="delete-outlined" />
            </a-popconfirm>
          </div>
        </template>
      </template>
    </detail-tab-table>

    <MoldDialog
      v-if="addRuleVisible"
      size="md"
      form-actions
      focus-guard
      initial-focus=".rule-target-form input:checked"
      :title="$t('label.add.rule')"
      @cancel="addRuleVisible = false">
      <div v-ctrl-enter="handleAddRule">
        <div class="rule-target-form">
          <div class="form__item">
            <div class="form__label">{{ targetTypeLabel }}</div>
            <a-radio-group
              v-model:value="addType"
              role="radiogroup"
              :aria-label="targetTypeLabel"
              :disabled="isSubmitted"
              @change="changeTargetType">
              <a-radio value="cidr">{{ $t('label.cidr') }}</a-radio>
              <a-radio value="securitygroup">{{ $t('label.security.group.rule.group') }}</a-radio>
            </a-radio-group>
          </div>
          <div v-if="addType === 'cidr'" class="form__item">
            <div class="form__label">{{ $t('label.cidr') }}</div>
            <a-input v-model:value="newRule.cidrlist" :aria-label="$t('label.cidr')" :disabled="isSubmitted" />
          </div>
          <div v-else class="rule-form-grid">
            <div class="form__item">
              <label for="sg-rule-owner" class="form__label">{{ $t('label.security.group.rule.owner') }}</label>
              <a-select
                id="sg-rule-owner"
                v-model:value="newRule.referenceAccountId"
                :aria-label="$t('label.security.group.rule.owner')"
                :placeholder="$t('label.security.group.rule.select.owner')"
                :options="referenceAccounts"
                :filter-option="matchOption"
                :loading="referenceLoading === 'accounts'"
                :disabled="isSubmitted || referenceLoading === 'accounts'"
                show-search
                @change="fetchReferenceGroups" />
            </div>
            <div class="form__item">
              <label for="sg-rule-peer" class="form__label">{{ $t('label.security.group.rule.group') }}</label>
              <a-select
                id="sg-rule-peer"
                v-model:value="newRule.referenceGroupId"
                :aria-label="$t('label.security.group.rule.group')"
                :placeholder="$t('label.security.group.rule.select.group')"
                :options="referenceGroups"
                :filter-option="matchOption"
                :loading="referenceLoading === 'groups'"
                :disabled="isSubmitted || !newRule.referenceAccountId || referenceLoading === 'groups'"
                show-search />
            </div>
          </div>
          <a-alert
            v-if="referenceError"
            type="error"
            show-icon
            :message="$t(referenceError === 'accounts' ? 'message.security.group.rule.accounts.failed' : 'message.security.group.rule.groups.failed')">
            <template #description>
              <a-button size="small" @click="referenceError === 'accounts' ? fetchReferenceAccounts() : fetchReferenceGroups()">{{ $t('label.retry') }}</a-button>
            </template>
          </a-alert>
        </div>

        <div class="rule-form-grid rule-traffic-form">
          <div class="form__item">
            <div class="form__label">{{ $t('label.protocol') }}</div>
            <a-select
              v-model:value="newRule.protocol"
              :aria-label="$t('label.protocol')"
              :disabled="isSubmitted"
              @change="resetRulePorts"
              showSearch
              optionFilterProp="label"
              :filterOption="matchOption">
              <a-select-option value="tcp" :label="$t('label.tcp')">{{ $t('label.tcp') }}</a-select-option>
              <a-select-option value="udp" :label="$t('label.udp')">{{ $t('label.udp') }}</a-select-option>
              <a-select-option value="icmp" :label="$t('label.icmp')">{{ $t('label.icmp') }}</a-select-option>
              <a-select-option value="all" :label="$t('label.all')">{{ $t('label.all') }}</a-select-option>
              <a-select-option value="protocolnumber" :label="$t('label.protocol.number')">{{ $t('label.protocol.number') }}</a-select-option>
            </a-select>
          </div>
          <div v-if="newRule.protocol === 'protocolnumber'" class="form__item">
            <div class="form__label">{{ $t('label.protocol.number') }}</div>
            <a-select
              v-model:value="newRule.protocolnumber"
              :aria-label="$t('label.protocol.number')"
              :disabled="isSubmitted"
              showSearch
              optionFilterProp="label"
              :filterOption="matchOption">
              <a-select-option v-for="opt in protocolNumbers" :key="opt.index" :value="opt.index" :label="opt.index + ' - ' + opt.name">
                {{ opt.index + ' - ' + opt.name }}
              </a-select-option>
            </a-select>
          </div>
          <div v-if="showsPorts" class="form__item">
            <div class="form__label">{{ $t('label.startport') }}</div>
            <a-input v-model:value="newRule.startport" :aria-label="$t('label.startport')" :disabled="isSubmitted" />
          </div>
          <div v-if="showsPorts" class="form__item">
            <div class="form__label">{{ $t('label.endport') }}</div>
            <a-input v-model:value="newRule.endport" :aria-label="$t('label.endport')" :disabled="isSubmitted" />
          </div>
          <div v-if="showsIcmp" class="form__item">
            <div class="form__label">{{ $t('label.icmptype') }}</div>
            <a-select
              v-model:value="newRule.icmptype"
              :aria-label="$t('label.icmptype')"
              :disabled="isSubmitted"
              @change="val => { updateIcmpCodes(val) }"
              showSearch
              optionFilterProp="label"
              :filterOption="matchOption">
              <a-select-option v-for="opt in icmpTypes" :key="opt.index" :value="opt.index" :label="opt.index + ' - ' + opt.description">
                {{ opt.index + ' - ' + opt.description }}
              </a-select-option>
            </a-select>
          </div>
          <div v-if="showsIcmp" class="form__item">
            <div class="form__label">{{ $t('label.icmpcode') }}</div>
            <a-select
              v-model:value="newRule.icmpcode"
              :aria-label="$t('label.icmpcode')"
              :disabled="isSubmitted"
              showSearch
              optionFilterProp="label"
              :filterOption="matchOption">
              <a-select-option v-for="opt in icmpCodes" :key="opt.code" :value="opt.code" :label="opt.code + ' - ' + opt.description">
                {{ opt.code + ' - ' + opt.description }}
              </a-select-option>
            </a-select>
          </div>
        </div>
        <a-alert v-if="ruleError" type="error" show-icon :message="ruleError" />
      </div>
      <MoldDialogActions :busy="isSubmitted">
        <a-button :disabled="isSubmitted" @click="addRuleVisible = false">{{ $t('label.cancel') }}</a-button>
        <a-button
          type="primary"
          :disabled="!canAddRule || isSubmitted || (addType === 'securitygroup' && !newRule.referenceGroupId)"
          :loading="isSubmitted"
          @click="handleAddRule">{{ $t('label.add') }}</a-button>
      </MoldDialogActions>
    </MoldDialog>

    <a-modal
      :title="$t('label.edit.tags')"
      :visible="tagsModalVisible"
      :footer="null"
      :closable="true"
      :afterClose="closeModal"
      :maskClosable="false"
      class="tags-modal"
      @cancel="tagsModalVisible = false">
      <a-spin v-if="tagsLoading"></a-spin>

      <div v-else v-ctrl-enter="handleAddTag">
        <a-form :ref="formRef" :model="form" :rules="formRules" class="add-tags">
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
            <a-form-item  ref="value" name="value">
              <a-input v-model:value="form.value" />
            </a-form-item>
          </div>
          <a-button :disabled="!('createTags' in $store.getters.apis)" type="primary" ref="submit" @click="handleAddTag">{{ $t('label.add') }}</a-button>
        </a-form>

        <a-divider style="margin-top: 0;" />

        <div class="tags-container">
          <div class="tags" v-for="(tag, index) in tags" :key="index">
            <a-tag :key="index" :closable="true" @close="() => handleDeleteTag(tag)">
              {{ tag.key }} = {{ tag.value }}
            </a-tag>
          </div>
        </div>

        <a-button class="add-tags-done" @click="tagsModalVisible = false" type="primary">{{ $t('label.ok') }}</a-button>
      </div>

    </a-modal>

  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TooltipButton from '@/components/widgets/TooltipButton'
import { validCidr } from '@/utils/conditionalFirewallRule'
import { networkCollection } from '@/utils/networkLifecycle'

export default {
  components: {
    MoldDialog,
    MoldDialogActions,
    TooltipButton
  },
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: Boolean
  },
  inject: ['parentFetchData', 'parentToggleLoading'],
  data () {
    return {
      newRule: {
        protocol: 'tcp',
        startport: null,
        endport: null,
        protocolnumber: null,
        cidrlist: null,
        icmptype: null,
        icmpcode: null,
        referenceAccountId: undefined,
        referenceGroupId: undefined
      },
      referenceAccounts: [],
      referenceGroups: [],
      referenceLoading: '',
      referenceError: '',
      referenceRequest: 0,
      protocolNumbers: [],
      icmpTypes: [],
      icmpCodes: [],
      tagsModalVisible: false,
      tags: [],
      newTag: {
        key: null,
        value: null
      },
      selectedRule: null,
      tagsLoading: false,
      addType: 'cidr',
      addRuleVisible: false,
      page: 1,
      pageSize: 10,
      columns: [
        {
          key: 'protocol',
          width: 115,
          title: this.$t('label.protocol')
        },
        {
          key: 'startport',
          width: 100,
          title: this.$t('label.startport'),
          dataIndex: 'startport'
        },
        {
          key: 'endport',
          width: 100,
          title: this.$t('label.endport'),
          dataIndex: 'endport'
        },
        {
          title: this.$t('label.icmptype'),
          width: 100,
          dataIndex: 'icmptype'
        },
        {
          title: this.$t('label.icmpcode'),
          width: 100,
          dataIndex: 'icmpcode'
        },
        {
          key: 'cidr',
          title: this.$t('label.cidr'),
          dataIndex: 'cidr'
        },
        {
          key: 'account',
          width: 205,
          title: this.$t('label.account.and.security.group')
        },
        {
          key: 'actions',
          width: 100,
          title: this.$t('label.actions')
        }
      ],
      isSubmitted: false,
      ruleError: '',
      ruleGeneration: 0
    }
  },
  computed: {
    tabType () {
      return this.$route.query.tab === 'ingress.rule' ? 'ingress' : 'egress'
    },
    targetTypeLabel () {
      return this.$t(this.tabType === 'ingress' ? 'label.security.group.rule.source.type' : 'label.security.group.rule.destination.type')
    },
    rules () {
      return (this.tabType === 'ingress' ? this.resource.ingressrule : this.resource.egressrule) || []
    },
    pagination () {
      return {
        current: this.page,
        pageSize: this.pageSize,
        pageSizeOptions: ['10', '20', '40', '80', '100', '200'],
        showSizeChanger: true,
        onChange: this.changePage
      }
    },
    ruleScope () {
      return JSON.stringify([this.resource.id, this.resource.domainid, this.tabType, this.$route.path])
    },
    authorizeCommand () {
      return this.tabType === 'ingress' ? 'authorizeSecurityGroupIngress' : 'authorizeSecurityGroupEgress'
    },
    revokeCommand () {
      return this.tabType === 'ingress' ? 'revokeSecurityGroupIngress' : 'revokeSecurityGroupEgress'
    },
    canAddRule () {
      return this.authorizeCommand in (this.$store.getters.apis || {})
    },
    canDeleteRule () {
      return this.revokeCommand in (this.$store.getters.apis || {})
    },
    showsPorts () {
      return ['tcp', 'udp'].includes(this.newRule.protocol)
    },
    showsIcmp () {
      return this.newRule.protocol === 'icmp' ||
        (this.newRule.protocol === 'protocolnumber' && this.newRule.protocolnumber === 1)
    }
  },
  watch: {
    ruleScope () {
      this.ruleGeneration++
      this.isSubmitted = false
      this.addRuleVisible = false
      this.page = 1
      this.resetAllRules()
    },
    addRuleVisible (visible) {
      if (!visible) this.resetReference()
    },
    'rules.length' (count) {
      this.page = Math.min(this.page, Math.max(1, Math.ceil(count / this.pageSize)))
    },
    newRule: {
      deep: true,
      handler () { this.ruleError = '' }
    }
  },
  beforeUnmount () {
    this.ruleGeneration++
    this.referenceRequest++
  },
  created () {
    this.initForm()
    this.fetchNetworkProtocols()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.formRules = reactive({
        key: [{ required: true, message: this.$t('message.specify.tag.key') }],
        value: [{ required: true, message: this.$t('message.specify.tag.value') }]
      })
    },
    matchOption (input, option) {
      return String(option?.label || '').toLowerCase().includes(input.trim().toLowerCase())
    },
    resetReference () {
      this.referenceRequest++
      this.referenceAccounts = []
      this.referenceGroups = []
      this.referenceLoading = ''
      this.referenceError = ''
      this.newRule.referenceAccountId = undefined
      this.newRule.referenceGroupId = undefined
    },
    changeTargetType () {
      this.newRule.cidrlist = null
      this.resetReference()
      if (this.addType === 'securitygroup') this.fetchReferenceAccounts()
    },
    async fetchReferenceAccounts () {
      const domainid = this.resource.domainid
      const request = ++this.referenceRequest
      this.referenceLoading = 'accounts'
      this.referenceError = ''
      try {
        if (!domainid) throw new Error('Missing security group domain')
        const rows = await networkCollection(getAPI, 'listAccounts', 'account', { domainid, listall: true, isrecursive: false, ignoreproject: true })
        if (request !== this.referenceRequest) return
        this.referenceAccounts = rows
          .filter(row => row.domainid === domainid && Number(row.accounttype ?? row.type) !== 5 && row.name)
          .map(row => ({ value: row.id, label: row.name }))
          .sort((a, b) => a.label.localeCompare(b.label))
      } catch (error) {
        if (request === this.referenceRequest) this.referenceError = 'accounts'
      } finally {
        if (request === this.referenceRequest) this.referenceLoading = ''
      }
    },
    async fetchReferenceGroups () {
      const account = this.referenceAccounts.find(option => option.value === this.newRule.referenceAccountId)
      if (!account) return
      const domainid = this.resource.domainid
      const request = ++this.referenceRequest
      this.newRule.referenceGroupId = undefined
      this.referenceGroups = []
      this.referenceError = ''
      this.referenceLoading = 'groups'
      try {
        const rows = await networkCollection(getAPI, 'listSecurityGroups', 'securitygroup', {
          account: account.label, domainid, listall: true, isrecursive: false, ignoreproject: true
        })
        if (request !== this.referenceRequest) return
        this.referenceGroups = rows
          .filter(row => row.domainid === domainid && row.account === account.label && !row.projectid && row.name)
          .map(row => ({ value: row.id, label: row.name }))
          .sort((a, b) => a.label.localeCompare(b.label))
      } catch (error) {
        if (request === this.referenceRequest) this.referenceError = 'groups'
      } finally {
        if (request === this.referenceRequest) this.referenceLoading = ''
      }
    },
    fetchNetworkProtocols () {
      if (!('listNetworkProtocols' in (this.$store.getters.apis || {}))) return
      getAPI('listNetworkProtocols', { option: 'protocolnumber' }).then(json => {
        this.protocolNumbers = (json.listnetworkprotocolsresponse?.networkprotocol || []).map(row => ({ ...row, index: Number(row.index) }))
      }).catch(error => this.$notifyError(error))
      getAPI('listNetworkProtocols', { option: 'icmptype' }).then(json => {
        const types = (json.listnetworkprotocolsresponse?.networkprotocol || []).map(row => ({ ...row, index: Number(row.index) }))
        this.icmpTypes = [{ index: -1, description: this.$t('label.all') }, ...types]
      }).catch(error => this.$notifyError(error))
    },
    updateIcmpCodes (type) {
      const entry = this.icmpTypes.find(option => option.index === type)
      const codes = Object.entries(entry?.details || {}).map(([code, description]) => ({ code: Number(code), description }))
      this.icmpCodes = type === -1 ? [{ code: -1, description: this.$t('label.all') }]
        : (codes.length ? codes : [{ code: 0, description: this.$t('label.none') }])
      this.newRule.icmpcode = this.icmpCodes.length === 1 ? this.icmpCodes[0].code : null
    },
    getCapitalise (value) {
      if (value === 'all') return this.$t('label.all')
      if (value == null) return ''
      const protocol = this.protocolNumbers.find(option => String(option.index) === String(value))
      return protocol ? protocol.index + ' - ' + protocol.name : String(value).toUpperCase()
    },
    ruleParameters () {
      const rule = this.newRule
      const params = { protocol: rule.protocol === 'protocolnumber' ? rule.protocolnumber : rule.protocol }
      if (rule.protocol === 'protocolnumber' &&
          !this.protocolNumbers.some(option => option.index === params.protocol)) {
        return { error: 'message.security.group.rule.protocol' }
      }
      if (this.showsPorts) {
        const numbers = [rule.startport, rule.endport].map(value => String(value ?? '').trim())
        if (!numbers.every(value => /^\d+$/.test(value) && Number(value) <= 65535) || Number(numbers[0]) > Number(numbers[1])) {
          return { error: 'message.security.group.rule.ports' }
        }
        params.startport = Number(numbers[0])
        params.endport = Number(numbers[1])
      }
      if (this.showsIcmp) {
        if (!this.icmpTypes.some(option => option.index === rule.icmptype) || !this.icmpCodes.some(option => option.code === rule.icmpcode)) {
          return { error: 'message.security.group.rule.icmp' }
        }
        params.icmptype = rule.icmptype
        params.icmpcode = rule.icmpcode
      }
      if (this.addType === 'cidr') {
        const cidrs = String(rule.cidrlist ?? '').split(',').map(cidr => cidr.trim())
        if (!cidrs.every(cidr => validCidr(cidr, cidr.includes(':')))) {
          return { error: 'message.security.group.rule.cidrs' }
        }
        params.cidrlist = cidrs.join(',')
      } else {
        const account = this.referenceAccounts.find(option => option.value === rule.referenceAccountId)
        const group = this.referenceGroups.find(option => option.value === rule.referenceGroupId)
        if (!account || !group) return { error: 'message.security.group.rule.reference' }
        params['usersecuritygrouplist[0].account'] = account.label
        params['usersecuritygrouplist[0].group'] = group.label
      }
      return { params }
    },
    changePage (page, size) {
      this.page = this.pageSize === size ? page : 1
      this.pageSize = size
    },
    openAddRule () {
      this.addType = 'cidr'
      this.resetAllRules()
      this.addRuleVisible = true
    },
    handleAddRule () {
      if (!this.canAddRule || this.isSubmitted) return
      const { params, error } = this.ruleParameters()
      if (error) {
        this.ruleError = this.$t(error)
        return
      }
      const messages = ['message.success.add.rule', 'message.add.rule.failed', 'message.add.rule.processing']
      return this.submitRuleChange(this.authorizeCommand, { securitygroupid: this.resource.id, ...params }, messages)
    },
    handleDeleteRule (rule) {
      if (!this.canDeleteRule || this.isSubmitted || !this.rules.some(row => row.ruleid === rule.ruleid)) return
      const messages = ['message.success.remove.rule', 'message.remove.rule.failed', 'message.remove.securitygroup.rule.processing']
      return this.submitRuleChange(this.revokeCommand, { id: rule.ruleid }, messages)
    },
    async submitRuleChange (command, params, [success, failure, progress]) {
      const generation = ++this.ruleGeneration
      const scope = this.ruleScope
      const active = () => this.ruleGeneration === generation && this.ruleScope === scope
      this.isSubmitted = true
      this.ruleError = ''
      const root = command.toLowerCase() + 'response'
      try {
        const response = await postAPI(command, { ...params, domainid: this.resource.domainid, account: this.resource.account }, { preserveOnFailure: true })
        const result = await this.$pollJob({
          jobId: response[root].jobid,
          successMessage: this.$t(success),
          errorMessage: this.$t(failure),
          loadingMessage: this.$t(progress),
          catchMessage: this.$t('error.fetching.async.job.result')
        })
        if (!active()) return
        if (result.jobstatus === 1) {
          await this.parentFetchData()
          if (active() && command === this.authorizeCommand) this.addRuleVisible = false
        } else {
          this.ruleError = result.jobresult?.errortext || this.$t(failure)
        }
      } catch (error) {
        if (active()) this.ruleError = error.response?.data?.[root]?.errortext || this.$t(failure)
      } finally {
        if (active()) this.isSubmitted = false
      }
    },
    fetchTags (rule) {
      getAPI('listTags', {
        resourceId: rule.ruleid,
        resourceType: 'SecurityGroupRule',
        listAll: true
      }).then(response => {
        this.tags = response.listtagsresponse.tag
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    handleDeleteTag (tag) {
      this.parentToggleLoading()
      this.tagsLoading = true
      postAPI('deleteTags', {
        'tags[0].key': tag.key,
        'tags[0].value': tag.value,
        resourceIds: this.selectedRule.ruleid,
        resourceType: 'SecurityGroupRule'
      }).then(response => {
        this.$pollJob({
          jobId: response.deletetagsresponse.jobid,
          successMessage: this.$t('message.success.delete.tag'),
          successMethod: () => {
            this.parentToggleLoading()
            this.fetchTags(this.selectedRule)
            this.tagsLoading = false
          },
          errorMessage: this.$t('message.delete.tag.failed'),
          errorMethod: () => {
            this.parentToggleLoading()
            this.fetchTags(this.selectedRule)
            this.tagsLoading = false
          },
          loadingMessage: this.$t('message.delete.tag.processing'),
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.parentToggleLoading()
            this.fetchTags(this.selectedRule)
            this.tagsLoading = false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
        this.parentToggleLoading()
        this.tagsLoading = false
      })
    },
    handleAddTag (e) {
      if (this.tagsLoading) return
      this.tagsLoading = true

      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)

        this.parentToggleLoading()
        postAPI('createTags', {
          'tags[0].key': values.key,
          'tags[0].value': values.value,
          resourceIds: this.selectedRule.ruleid,
          resourceType: 'SecurityGroupRule'
        }).then(response => {
          this.$pollJob({
            jobId: response.createtagsresponse.jobid,
            successMessage: this.$t('message.success.add.tag'),
            successMethod: () => {
              this.parentToggleLoading()
              this.fetchTags(this.selectedRule)
              this.tagsLoading = false
            },
            errorMessage: this.$t('message.add.tag.failed'),
            errorMethod: () => {
              this.parentToggleLoading()
              this.fetchTags(this.selectedRule)
              this.tagsLoading = false
            },
            loadingMessage: this.$t('message.add.tag.processing'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.parentToggleLoading()
              this.fetchTags(this.selectedRule)
              this.tagsLoading = false
            }
          })
        }).catch(error => {
          this.$notifyError(error)
          this.parentToggleLoading()
          this.tagsLoading = false
        })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      }).finally(() => { this.tagsLoading = false })
    },
    openTagsModal (rule) {
      this.selectedRule = rule
      this.fetchTags(this.selectedRule)
      this.tagsModalVisible = true
    },
    closeModal () {
      this.newTag.key = null
      this.newTag.value = null
      this.selectedRule = null
    },
    resetRulePorts () {
      this.newRule.startport = null
      this.newRule.endport = null
      this.newRule.icmptype = null
      this.newRule.icmpcode = null
    },
    resetAllRules () {
      this.newRule.protocol = 'tcp'
      this.newRule.protocolnumber = null
      this.ruleError = ''
      this.newRule.cidrlist = null
      this.resetReference()
      this.resetRulePorts()
    },
    capitalise (val) {
      return val.toUpperCase()
    }
  }
}
</script>

<style scoped lang="scss">
  .add-rule-button:not(:disabled) {
    color: var(--ui-action-primary-text);
    background: var(--ui-action-primary-bg);
    border-color: var(--ui-action-primary-bg);

    &:hover,
    &:focus-visible {
      background: var(--ui-action-primary-hover);
      border-color: var(--ui-action-primary-hover);
    }
  }

  .security-group-rules :deep(.detail-table-footer .ant-pagination-item-active a) {
    color: var(--ui-link);
  }

  .rule-target-form,
  .rule-form-grid,
  .form__item {
    display: grid;
    gap: 8px;
    min-inline-size: 0;
  }

  .rule-target-form {
    gap: 12px;

    :deep(.ant-select:not(.ant-select-disabled) .ant-select-selection-placeholder) {
      color: var(--ui-text-muted);
    }
  }

  .rule-form-grid {
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 15rem), 1fr));
    gap: 16px;
  }

  .rule-traffic-form {
    margin-block: 16px 4px;
  }

  .rule-actions {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .rule-target {
    overflow-wrap: anywhere;
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
</style>
