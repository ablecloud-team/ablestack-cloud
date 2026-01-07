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
    <a-card class="breadcrumb-card">
      <a-row>
        <a-col :span="14" style="padding-left: 6px">
          <breadcrumb :resource="resource">
            <template #end>
              <a-button
                style="margin-top: 4px"
                :loading="loading"
                shape="round"
                size="small"
                @click="fetchData()">
                <template #icon><ReloadOutlined /></template>
                {{ $t('label.refresh') }}
              </a-button>
            </template>
          </breadcrumb>
        </a-col>
        <a-col :span="10">
          <span style="float: right">
            <a-dropdown
              v-if="menuActions.length > 0"
              v-model:visible="detailActionsVisible"
              :trigger="['click']"
              placement="bottomRight"
              overlayClassName="autogen-action-dropdown"
              class="autogen-action-dropdown__trigger">
              <template #overlay>
                <div class="autogen-action-dropdown__content">
                  <action-button
                    :loading="loading"
                    :actions="menuActions"
                    :selectedRowKeys="selectedRowKeys"
                    :dataView="true"
                    :resource="resource"
                    @exec-action="handleDropdownAction"/>
                </div>
              </template>
              <a-button type="primary" class="autogen-action-dropdown__button">
                <template #icon><down-outlined /></template>
                {{ $t('label.actions') }}
              </a-button>
            </a-dropdown>
          </span>
        </a-col>
      </a-row>
    </a-card>

    <div class="row-element">
      <resource-view
        v-if="dataView"
        :resource="resource"
        :loading="loading"
        :tabs="$route.meta.tabs"
        :actions="actions"
        @exec-action="execAction" />
      <tree-view
        v-else
        :key="treeViewKey"
        :treeData="treeData"
        :treeSelected="treeSelected"
        :treeStore="domainStore"
        :loading="loading"
        :tabs="$route.meta.tabs"
        :treeDeletedKey="treeDeletedKey"
        :actions="actions"
        @change-resource="changeResource"
        @change-tree-store="changeDomainStore"
        @exec-action="execAction"/>
    </div>

    <div v-if="showAction">
      <domain-action-form
        :showAction="showAction"
        :resource="resource"
        :action="action"/>
    </div>
  </div>
</template>

<script>
import { getAPI, callAPI } from '@/api'
import store from '@/store'
import { mixinDevice } from '@/utils/mixin.js'

import Breadcrumb from '@/components/widgets/Breadcrumb'
import ActionButton from '@/components/view/ActionButton'
import TreeView from '@/components/view/TreeView'
import DomainActionForm from '@/views/iam/DomainActionForm'
import ResourceView from '@/components/view/ResourceView'
import eventBus from '@/config/eventBus'

export default {
  name: 'DomainView',
  components: {
    Breadcrumb,
    ActionButton,
    TreeView,
    DomainActionForm,
    ResourceView
  },
  mixins: [mixinDevice],
  data () {
    return {
      resource: {},
      loading: false,
      selectedRowKeys: [],
      treeViewKey: 0,
      treeData: [],
      treeSelected: {},
      showAction: false,
      action: {},
      dataView: false,
      domainStore: {},
      treeDeletedKey: null,
      detailActionsVisible: false
    }
  },
  computed: {
    actions () {
      let actions = []
      if (this.$route && this.$route.meta) {
        if (this.$route.meta.actions) {
          actions = this.$route.meta.actions
        }
      }
      return actions
    },
    menuActions () {
      if (!this.actions || this.actions.length === 0) {
        return []
      }
      return this.actions.filter(action => {
        if (!(action.api in this.$store.getters.apis)) {
          return false
        }
        const usable = action.dataView || action.listView
        if (!usable) {
          return false
        }
        return 'show' in action ? action.show(this.resource, this.$store.getters) : true
      }).map(action => {
        if (!action.dataView) {
          return { ...action, dataView: true }
        }
        return action
      })
    }
  },
  beforeRouteUpdate (to, from, next) {
    next()
  },
  beforeRouteLeave (to, from, next) {
    this.changeDomainStore({})
    next()
  },
  created () {
    this.domainStore = store.getters.domainStore
    this.fetchData()
    eventBus.on('refresh-domain-icon', () => {
      if (this.$showIcon()) {
        this.fetchData()
      }
    })
  },
  watch: {
    '$route' (to, from) {
      // When the route changes from /domain/:id to /domain or vice versa, the component is not destroyed and created again
    // So, we need to watch the route params to fetch the data again to update the component
      if (to.path.startsWith('/domain') && from.params.id !== to.params.id) {
        this.fetchData()
      }
    }
  },
  provide () {
    return {
      parentCloseAction: this.closeAction,
      parentFetchData: this.fetchData,
      parentForceRerender: this.forceRerender
    }
  },
  methods: {
    handleDropdownAction (action) {
      this.detailActionsVisible = false
      this.execAction(action)
    },
    execAction (action) {
      this.detailActionsVisible = false
      this.treeDeletedKey = action.api === 'deleteDomain' ? this.resource.key : null
      this.actionData = []
      this.action = action
      this.action.params = store.getters.apis[this.action.api].params
      const paramFields = this.action.params
      paramFields.sort(function (a, b) {
        if (a.name === 'name' && b.name !== 'name') { return -1 }
        if (a.name !== 'name' && b.name === 'name') { return -1 }
        if (a.name === 'id') { return -1 }
        if (a.name < b.name) { return -1 }
        if (a.name > b.name) { return 1 }
        return 0
      })
      this.action.paramFields = []
      if (action.args) {
        var args = action.args
        if (typeof action.args === 'function') {
          args = action.args(action.resource, this.$store.getters)
        }
        if (args.length > 0) {
          this.action.paramFields = args.map(function (arg) {
            return paramFields.filter(function (param) {
              return param.name.toLowerCase() === arg.toLowerCase()
            })[0]
          })
        }
      }
      this.showAction = true
      for (const param of this.action.paramFields) {
        if (param.type === 'list' && ['tags', 'hosttags'].includes(param.name)) {
          param.type = 'string'
        }
        if (param.type === 'uuid' || param.type === 'list' || param.name === 'account' || (this.action.mapping && param.name in this.action.mapping)) {
          this.listUuidOpts(param)
        }
      }
      this.action.loading = false
    },
    fetchData () {
      this.treeData = []
      this.treeSelected = {}
      const params = { listall: true }
      if (this.$route && this.$route.params && this.$route.params.id) {
        this.resource = {}
        this.dataView = true
        params.id = this.$route.params.id
      } else {
        this.dataView = false
        params.id = this.$store.getters.userInfo.domainid
      }

      this.loading = true
      params.showicon = true
      getAPI('listDomains', params).then(json => {
        const domains = json.listdomainsresponse.domain || []
        this.treeData = this.generateTreeData(domains)
        this.resource = domains[0] || {}
        this.treeSelected = domains[0] || {}
      }).catch(error => {
        if ([401].includes(error.response.status)) {
          return
        }

        this.$notification.error({
          message: this.$t('message.request.failed'),
          description: error.response.headers['x-description'],
          duration: 0
        })

        if ([405].includes(error.response.status)) {
          this.$router.push({ path: '/dashboard' })
        }

        if ([430, 431, 432].includes(error.response.status)) {
          this.$router.push({ path: '/dashboard' })
        }

        if ([530, 531, 532, 533, 534, 535, 536, 537].includes(error.response.status)) {
          this.$router.push({ path: '/dashboard' })
        }
      }).finally(f => {
        this.loading = false
      })
    },
    listUuidOpts (param) {
      if (this.action.mapping && param.name in this.action.mapping && !this.action.mapping[param.name].api) {
        return
      }
      const paramName = param.name
      const possibleName = 'list' + paramName.replace('ids', '').replace('id', '').toLowerCase() + 's'
      let params = { listall: true }
      let possibleApi
      if (this.action.mapping && param.name in this.action.mapping && this.action.mapping[param.name].api) {
        possibleApi = this.action.mapping[param.name].api
        if (this.action.mapping[param.name].params) {
          const customParams = this.action.mapping[param.name].params(this.resource)
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
      callAPI(possibleApi, params)
        .then(json => {
          param.loading = false
          const responseObj = Object.values(json).find(obj => obj.includes('response'))
          if (responseObj) {
            const responseData = Object.entries(responseObj).find(([res, value]) => res !== 'count')
            if (responseData) {
              param.opts = responseData[1]
            }
          }
        })
        .catch(() => {
          param.loading = false
        })
    },
    generateTreeData (treeData) {
      const result = []
      const rootItem = treeData

      rootItem[0].title = rootItem[0].title ? rootItem[0].title : rootItem[0].name
      rootItem[0].key = rootItem[0].id ? rootItem[0].id : 0
      rootItem[0].resourceIcon = rootItem[0].icon || {}
      delete rootItem[0].icon

      if (!rootItem[0].haschild) {
        rootItem[0].isLeaf = true
      }

      result.push(rootItem[0])
      return result
    },
    changeResource (resource) {
      this.treeSelected = resource
      this.resource = this.treeSelected
    },
    changeDomainStore (domainStore) {
      this.domainStore = domainStore
      store.dispatch('SetDomainStore', domainStore)
    },
    closeAction () {
      this.showAction = false
    },
    forceRerender () {
      this.treeViewKey += 1
    }
  }
}
</script>

<style scoped lang="less">
  .breadcrumb-card {
    margin-left: -24px;
    margin-right: -24px;
    margin-top: -16px;
    margin-bottom: 12px;
  }

  .row-element {
    margin-top: 10px;
    margin-bottom: 10px;
  }

  .ant-breadcrumb {
    vertical-align: text-bottom;
  }

  .ant-breadcrumb .anticon {
    margin-left: 8px;
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
    background: #fff;
    border-radius: 8px;
    border: 1px solid #d9d9d9;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
    padding: 12px;
  }

  .autogen-action-dropdown__content :deep(.row-action-button--dataview) {
    width: max-content;
    min-width: 0;
  }
</style>
