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
    <div class="provider-actions">
      <div class="provider-state">
        <strong>{{ $t('label.state') }}</strong>
        <span v-if="resource.supported === false">{{ $t('label.zone.provider.unsupported') }}</span>
        <status v-else-if="resource.state" :text="resource.state" display-text />
        <span v-else>{{ $t('label.not.added') }}</span>
      </div>
      <action-button
        :actions="providerActions"
        :resource="resource"
        :loading="loading || actionsDisabled"
        @exec-action="handleExecAction" />
    </div>
    <provider-detail
      :details="providerDetails"
      :nsp="resource"
      :loading="loading" />
    <div
      v-for="(list, index) in listData"
      :key="index">
      <provider-list-view
        class="provider-resource-list"
        v-if="resource.id"
        :title="list.title"
        :action="currentAction"
        :dataSource="list.data"
        :columns="list.columns"
        :itemCount="list.itemCount"
        :resource="resource"
        :page="page"
        :pageSize="pageSize"
        :loading="loading || list.loading"
        :refreshing="list.refreshing" />
    </div>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import ActionButton from '@/components/view/ActionButton'
import Status from '@/components/widgets/Status'
import ProviderDetail from '@/views/infra/network/providers/ProviderDetail'
import ProviderListView from '@/views/infra/network/providers/ProviderListView'

export default {
  name: 'ProviderItem',
  components: {
    ActionButton,
    Status,
    ProviderDetail,
    ProviderListView
  },
  props: {
    actionsDisabled: { type: Boolean, default: false },
    itemNsp: {
      type: Object,
      required: true
    },
    nsp: {
      type: Object,
      default: () => {}
    },
    resourceId: {
      type: String,
      default: () => ''
    },
    zoneId: {
      type: String,
      default: () => ''
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      provider: {},
      listData: {},
      resource: {},
      currentAction: {},
      page: 1,
      pageSize: 10,
      itemCount: 0,
      pluginEnabled: false
    }
  },
  provide () {
    return {
      providerChangePage: this.changePage
    }
  },
  computed: {
    providerActions () {
      if (this.resource.supported === false) return []
      return (this.provider.actions || []).map(action => {
        const stateAction = action.api === 'updateNetworkServiceProvider' &&
          ['label.enable.provider', 'label.disable.provider'].includes(action.label)
        return stateAction ? { ...action, showLabel: true } : action
      })
    },
    providerDetails () {
      return (this.provider.details || []).filter(field => field !== 'state')
    }
  },
  inject: ['provideSetNsp', 'provideExecuteAction'],
  watch: {
    nsp: {
      deep: true,
      handler () {
        this.fetchData()
      }
    }
  },
  created () {
    if (!this.resourceId || !this.zoneId) {
      return
    }
    this.fetchData()
  },
  methods: {
    async fetchData () {
      if (!this.nsp || Object.keys(this.nsp).length === 0) {
        this.resource = {
          name: this.itemNsp.title,
          physicalnetworkid: this.resourceId,
          zoneid: this.zoneId
        }
      } else {
        this.resource = { ...this.nsp, zoneid: this.zoneId }
      }
      if (this.itemNsp && Object.keys(this.itemNsp).length > 0) {
        this.provider = this.itemNsp
        this.provideSetNsp(this.resource)

        if (this.resource.supported === false || !this.resource.id || !this.provider.lists?.length) {
          return
        }
        this.provider.lists.map(this.fetchOptions)
      }
    },
    async fetchConfiguration (configName) {
      const params = {
        name: configName
      }
      getAPI('listConfigurations', params).then(json => {
        if (json.listconfigurationsresponse.configuration !== null) {
          const config = json.listconfigurationsresponse.configuration[0]
          if (config && config.name === params.name) {
            this.pluginEnabled = config.value
          }
        }
      })
    },
    async fetchOptions (args) {
      if (!args || Object.keys(args).length === 0) {
        return
      }

      const params = {}
      if (args.mapping) {
        Object.keys(args.mapping).map(key => {
          params[key] = args.mapping[key]?.value(this.resource) || null
        })
      }
      params.page = this.page
      params.pageSize = this.pageSize

      let length = args.columns.length
      if (args.columns.includes('actions')) {
        length--
      }
      const columns = args.columns.map(col => {
        if (col === 'actions') {
          return {
            key: col,
            title: this.$t('label.' + col),
            dataIndex: col,
            width: 80,
            fixed: 'right'
          }
        }
        const width = 100 / (length) + '%'
        return {
          key: col,
          title: this.$t('label.' + col),
          width: width,
          dataIndex: col
        }
      })

      const previous = this.listData[args.title]
      this.listData[args.title] = {
        title: args.title,
        columns,
        data: previous?.data || [],
        itemCount: previous?.itemCount || 0,
        loading: !previous,
        refreshing: true
      }
      const list = this.listData[args.title]
      const current = () => this.listData[args.title] === list

      try {
        const providers = ['tungsten', 'nsx', 'netris']
        const apiLower = args.api.toLowerCase()

        for (const provider of providers) {
          if (apiLower.includes(provider)) {
            await this.fetchConfiguration(`${provider}.plugin.enable`)
            if (!this.pluginEnabled || !current()) return
          }
        }
        const listResult = await this.executeApi(args.api, params)
        if (!current()) return
        list.data = listResult.data
        list.itemCount = listResult.itemCount
      } catch (error) {
        if (!current()) return
        this.$notification.error({
          message: this.$t('message.request.failed'),
          description: (error.response?.headers?.['x-description']) || error.message
        })
      } finally {
        if (current()) {
          list.loading = false
          list.refreshing = false
        }
      }
    },
    executeApi (apiName, params) {
      return new Promise((resolve, reject) => {
        getAPI(apiName, params).then(json => {
          let responseName
          let objectName
          let itemCount = 0
          const result = {
            data: [],
            itemCount: 0
          }
          for (const key in json) {
            if (key.includes('response') || key.includes(apiName)) {
              responseName = key
              break
            }
          }
          if (!responseName) {
            resolve(result)
            return
          }
          for (const key in json[responseName]) {
            if (key === 'count') {
              itemCount = json[responseName].count
              continue
            }
            objectName = key
            break
          }
          result.data = json[responseName][objectName] || []
          result.itemCount = itemCount
          resolve(result)
        }).catch(e => {
          reject(e)
        })
      })
    },
    handleExecAction (action) {
      this.currentAction = action
      this.provideExecuteAction(action)
    },
    changePage (listName, page, pageSize) {
      this.page = page
      this.pageSize = pageSize
      const listItem = this.provider.lists.filter(provider => provider.title === listName)[0] || {}
      this.fetchOptions(listItem)
    }
  }
}
</script>

<style scoped>
.provider-actions,
.provider-state {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.provider-actions { margin-bottom: 16px; }
.provider-actions :deep(.row-action-button) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}
.provider-resource-list {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--ui-border);
}
</style>
