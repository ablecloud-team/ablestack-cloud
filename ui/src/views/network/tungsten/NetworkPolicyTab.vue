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
    <NetworkRulesToolbar :can-add="canWrite('applyTungstenFabricPolicy')" :loading="fetchLoading" @add="onShowAction" @refresh="fetchData">
      <template #add-label>{{ $t('label.apply.tungsten.network.policy') }}</template>
    </NetworkRulesToolbar>
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.guest.feature.load.failed')" />
    <a-table
      :scroll="{ x: 560 }"
      size="small"
      :loading="fetchLoading"
      :columns="columns"
      :dataSource="dataSource"
      :rowKey="listRowKey"
      :pagination="false">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'actions'">
          <a-popconfirm
            v-if="canWrite('removeTungstenFabricPolicy')"
            placement="topRight"
            :title="$t('message.confirm.remove.network.policy')"
            :ok-text="$t('label.yes')"
            :cancel-text="$t('label.no')"
            :loading="deleteLoading"
            @confirm="removeNetworkPolicy(record)"
          >
            <tooltip-button
              :tooltip="$t('label.action.remove.network.policy')"
              danger
              type="primary"
              icon="delete-outlined" />
          </a-popconfirm>
        </template>
      </template>
    </a-table>
    <div style="display: block; text-align: right; margin-top: 10px;">
      <a-pagination
        size="small"
        :current="page"
        :pageSize="pageSize"
        :total="itemCount"
        :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="pageSizeOptions"
        @change="changePage"
        @showSizeChange="changePageSize"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </div>
    <MoldDialog
      size="md"
      v-if="showAction"
      :visible="showAction"
      :title="$t('label.apply.tungsten.network.policy')"
            :footer="null"
      :closable="!submitLoading"
      @cancel="!submitLoading && (showAction = false)">
      <a-alert v-if="candidateFailed" type="warning" show-icon :message="$t('message.guest.feature.load.failed')">
        <template #action><a-button size="small" @click="onShowAction">{{ $t('label.refresh') }}</a-button></template>
      </a-alert>
      <div v-ctrl-enter="handleSubmit">
        <a-form :ref="formRef" :model="form" :rules="rules" layout="vertical">
          <a-form-item name="policyuuid" ref="policyuuid">
            <template #label>
              <tooltip-label :title="$t('label.policyuuid')" :tooltip="apiParams.policyuuid.description"/>
            </template>
            <a-select
              :loading="networks.loading"
              :placeholder="apiParams.policyuuid.description"
              v-model:value="form.policyuuid">
              <a-select-option v-for="network in networks.opts" :key="network.uuid">{{ network.name }}</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item name="majorsequence" ref="majorsequence">
            <template #label>
              <tooltip-label :title="$t('label.majorsequence')" :tooltip="apiParams.majorsequence.description"/>
            </template>
            <a-input-number :min="0" :max="2147483647" :precision="0" v-model:value="form.majorsequence" :placeholder="apiParams.majorsequence.description" />
          </a-form-item>
          <a-form-item name="minorsequence" ref="minorsequence">
            <template #label>
              <tooltip-label :title="$t('label.minorsequence')" :tooltip="apiParams.minorsequence.description"/>
            </template>
            <a-input-number :min="0" :max="2147483647" :precision="0" v-model:value="form.minorsequence" :placeholder="apiParams.minorsequence.description" />
          </a-form-item>
        </a-form>

        <div :span="24" class="action-button">
          <a-button @click="() => { showAction = false }">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" :loading="submitLoading" :disabled="!canWrite('applyTungstenFabricPolicy') || networks.loading || candidateFailed || !form.policyuuid" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </div>
    </MoldDialog>
  </div>
</template>

<script>
import MoldDialog from '@/components/view/MoldDialog'
import NetworkRulesToolbar from '@/components/view/NetworkRulesToolbar'
import { canWriteGuestFeature } from '@/utils/conditionalGuestNetwork'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import TooltipButton from '@/components/widgets/TooltipButton'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'NetworkPolicyTab',
  components: {
    MoldDialog,
    NetworkRulesToolbar,
    TooltipButton,
    TooltipLabel
  },
  mixins: [listRefreshMixin(['fetchData']), mixinDevice],
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      fetchLoading: false,
      deleteLoading: false,
      submitLoading: false,
      showAction: false,
      candidateFailed: false,
      page: 1,
      pageSize: this.$store.getters.defaultListViewPageSize,
      itemCount: 0,
      columns: [{
        title: this.$t('label.name'),
        dataIndex: 'name'
      }, {
        title: this.$t('label.actions'),
        dataIndex: 'actions',
        key: 'actions',
        width: 80
      }],
      dataSource: [],
      networks: {
        loading: false,
        opts: []
      }
    }
  },
  watch: {
    resource (newData, oldData) {
      if (newData !== oldData) {
        this.fetchData()
      }
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('applyTungstenFabricPolicy')
  },
  created () {
    this.fetchData()
  },
  computed: {
    pageSizeOptions () {
      const sizes = [20, 50, 100, 200, this.$store.getters.defaultListViewPageSize]
      if (this.device !== 'desktop') {
        sizes.unshift(10)
      }
      return [...new Set(sizes)].sort(function (a, b) {
        return a - b
      }).map(String)
    }
  },
  methods: {
    canWrite (command) {
      const getters = this.$store.getters
      if (this.$route.meta.name !== 'guestnetwork') return command in getters.apis
      // Candidate list commands only accept a zone; they cannot enforce an account or project filter.
      return getters.userInfo?.roletype === 'Admin' && !getters.project?.id && !this.listRefreshFailed &&
        canWriteGuestFeature('network.policy', command, this.resource, getters)
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        majorsequence: 0,
        minorsequence: 0
      })
      this.rules = reactive({
        policyuuid: [{ required: true, message: this.$t('message.error.select') }],
        majorsequence: [{ required: true, message: this.$t('message.error.required.input') }],
        minorsequence: [{ required: true, message: this.$t('message.error.required.input') }]
      })
    },
    initRemoveForm () {
      this.removeRef = ref()
      this.removeForm = reactive({
        policyuuid: this.networks.opts[0]?.uuid || null
      })
      this.removeRules = reactive({
        policyuuid: [{ required: true, message: this.$t('message.error.select') }]
      })
    },
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      if (Object.keys(this.resource).length === 0) {
        return
      }
      const params = {}
      params.zoneid = this.resource.zoneid
      params.networkid = this.resource.id
      params.listAll = true
      params.page = this.page
      params.pagesize = this.pageSize

      this.fetchLoading = !listRequest.loaded

      return getAPI('listTungstenFabricPolicy', params).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.itemCount = 0
        this.dataSource = []
        this.itemCount = json?.listtungstenfabricpolicyresponse?.count || 0
        this.dataSource = json?.listtungstenfabricpolicyresponse?.policy || []
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.fetchLoading = false
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.fetchLoading = false
      })
    },
    onShowAction () {
      this.initForm()
      if (!this.canWrite('applyTungstenFabricPolicy')) return
      this.candidateFailed = false
      this.showAction = true
      this.networks.loading = true
      this.networks.opts = []
      getAPI('listTungstenFabricPolicy', { listall: true, zoneid: this.resource.zoneid }).then(json => {
        this.networks.opts = json?.listtungstenfabricpolicyresponse?.policy || []
        this.networks.opts = this.networks.opts.filter(row => row.uuid && !this.dataSource.some(used => used.uuid === row.uuid))
        this.form.policyuuid = null
      }).catch(() => {
        this.candidateFailed = true
      }).finally(() => {
        this.networks.loading = false
      })
    },
    changePage (page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      this.fetchData()
    },
    changePageSize (currentPage, pageSize) {
      this.page = currentPage
      this.pageSize = pageSize
      this.fetchData()
    },
    handleSubmit () {
      if (this.submitLoading || this.candidateFailed || this.networks.loading || !this.canWrite('applyTungstenFabricPolicy')) return
      this.submitLoading = true

      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        if (!this.networks.opts.some(row => row.uuid === values.policyuuid)) return

        const params = {}
        params.zoneid = this.resource.zoneid
        params.networkuuid = this.resource.id
        params.policyuuid = values.policyuuid
        params.majorsequence = values.majorsequence
        params.minorsequence = values.minorsequence

        const match = this.networks.opts.filter(network => network.uuid === values.policyuuid)
        const resourceName = match[0]?.name || values.policyuuid

        return postAPI('applyTungstenFabricPolicy', params).then(json => {
          const jobId = json?.applytungstenfabricpolicyresponse?.jobid
          return this.$pollJob({
            jobId,
            title: this.$t('label.apply.tungsten.network.policy'),
            description: resourceName,
            successMessage: this.$t('message.success.apply.network.policy'),
            successMethod: () => {
              this.fetchData()
            },
            errorMessage: this.$t('message.error.apply.network.policy'),
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.fetchData()
            },
            action: {
              isFetchData: false
            }
          }).then(result => {
            if (result.jobstatus === 1) this.showAction = false
          })
        }).catch(error => {
          this.$notifyError(error)
        })
      }).catch(error => {
        if (error.errorFields?.[0]) this.formRef.value.scrollToField(error.errorFields[0].name)
      }).finally(() => {
        this.submitLoading = false
      })
    },
    removeNetworkPolicy (record) {
      if (this.deleteLoading || !this.canWrite('removeTungstenFabricPolicy')) return
      this.deleteLoading = true
      const params = {}
      params.zoneid = this.resource.zoneid
      params.networkuuid = this.resource.id
      params.policyuuid = record.uuid

      postAPI('removeTungstenFabricPolicy', params).then(json => {
        const jobId = json?.removetungstenfabricpolicyresponse?.jobid
        return this.$pollJob({
          jobId,
          title: this.$t('label.action.remove.network.policy'),
          description: record.name || record.uuid,
          successMessage: this.$t('message.success.remove.network.policy'),
          successMethod: () => {
            this.fetchData()
          },
          errorMessage: this.$t('message.error.remove.network.policy'),
          errorMethod: () => {
            this.fetchData()
          },
          catchMessage: this.$t('error.fetching.async.job.result'),
          catchMethod: () => {
            this.fetchData()
          },
          action: {
            isFetchData: false
          }
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => { this.deleteLoading = false })
    }
  }
}
</script>
