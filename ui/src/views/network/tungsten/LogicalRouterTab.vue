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
    <NetworkRulesToolbar :can-add="canWrite('addTungstenFabricNetworkGatewayToLogicalRouter')" :loading="fetchLoading" @add="onShowAction" @refresh="fetchData">
      <template #add-label>{{ $t('label.add.logical.router') }}</template>
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
      :title="$t('label.add.logical.router')"
            :footer="null"
      :closable="!submitLoading"
      @cancel="!submitLoading && (showAction = false)">
      <a-alert v-if="candidateFailed" type="warning" show-icon :message="$t('message.guest.feature.load.failed')">
        <template #action><a-button size="small" @click="onShowAction">{{ $t('label.refresh') }}</a-button></template>
      </a-alert>
      <div v-ctrl-enter="handleSubmit">
        <a-form :ref="formRef" :model="form" :rules="rules" layout="vertical">
          <a-form-item name="logicalrouteruuid" ref="logicalrouteruuid" :label="$t('label.tungsten.logical.router')">
            <a-select
              :loading="logicalRouters.loading"
              v-model:value="form.logicalrouteruuid">
              <a-select-option v-for="logicalRouter in logicalRouters.opts" :key="logicalRouter.uuid">{{ logicalRouter.name }}</a-select-option>
            </a-select>
          </a-form-item>
        </a-form>

        <div :span="24" class="action-button">
          <a-button @click="() => { showAction = false }">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" :loading="submitLoading" :disabled="!canWrite('addTungstenFabricNetworkGatewayToLogicalRouter') || logicalRouters.loading || candidateFailed || !form.logicalrouteruuid" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
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

export default {
  name: 'LogicalRouterTab',
  components: {
    MoldDialog,
    NetworkRulesToolbar,
    TooltipButton
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
      logicalRouters: {
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
  created () {
    this.initForm()
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
        canWriteGuestFeature('tungsten.logical.router', command, this.resource, getters)
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({
        logicalrouteruuid: [{ required: true, message: this.$t('message.error.select') }]
      })
    },
    fetchData () {
      const listRequest = this.listRequestToken('fetchData')
      if (Object.keys(this.resource).length === 0) {
        return
      }
      const params = {}
      params.zoneid = this.resource.zoneid
      params.networkuuid = this.resource.id
      params.listAll = true
      params.page = this.page
      params.pagesize = this.pageSize

      this.fetchLoading = !listRequest.loaded

      return getAPI('listTungstenFabricLogicalRouter', params).then(json => {
        if (!this.isListRequestCurrent('fetchData', listRequest)) return
        this.itemCount = 0
        this.dataSource = []
        this.itemCount = json?.listtungstenfabriclogicalrouterresponse?.count || 0
        this.dataSource = json?.listtungstenfabriclogicalrouterresponse?.logicalrouter || []
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
      if (!this.canWrite('addTungstenFabricNetworkGatewayToLogicalRouter')) return
      this.candidateFailed = false
      this.showAction = true
      this.logicalRouters.loading = true
      this.logicalRouters.opts = []
      getAPI('listTungstenFabricLogicalRouter', { zoneid: this.resource.zoneid }).then(json => {
        this.logicalRouters.opts = json?.listtungstenfabriclogicalrouterresponse?.logicalrouter || []
        this.logicalRouters.opts = this.logicalRouters.opts.filter(row => row.uuid && !this.dataSource.some(used => used.uuid === row.uuid))
        this.form.logicalrouteruuid = null
      }).catch(() => {
        this.candidateFailed = true
      }).finally(() => {
        this.logicalRouters.loading = false
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
      if (this.submitLoading || this.candidateFailed || this.logicalRouters.loading || !this.canWrite('addTungstenFabricNetworkGatewayToLogicalRouter')) return
      this.submitLoading = true

      this.formRef.value.validate().then(() => {
        const values = toRaw(this.form)
        if (!this.logicalRouters.opts.some(row => row.uuid === values.logicalrouteruuid)) return

        const params = {}
        params.zoneid = this.resource.zoneid
        params.networkuuid = this.resource.id
        params.logicalrouteruuid = values.logicalrouteruuid

        return postAPI('addTungstenFabricNetworkGatewayToLogicalRouter', params).then(json => {
          const jobId = json?.addtungstenfabricnetworkgatewaytologicalrouterresponse?.jobid
          return this.$pollJob({
            jobId,
            title: this.$t('label.add.logical.router'),
            description: values.logicalrouteruuid,
            successMessage: `${this.$t('message.success.add.logical.router')} ${values.logicalrouteruuid}`,
            successMethod: () => {
              this.fetchData()
            },
            errorMessage: this.$t('message.error.add.logical.router'),
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
    }
  }
}
</script>
