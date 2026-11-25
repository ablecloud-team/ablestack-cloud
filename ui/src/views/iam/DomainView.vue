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
    <a-card class="breadcrumb-card content-breadcrumb">
      <div class="domain-page-toolbar">
        <div class="domain-page-location">
          <breadcrumb :resource="breadcrumbResource" standard-layout>
            <template #end>
              <a-button
                ref="domainRefresh"
                :loading="loading"
                shape="round"
                size="small"
                @click="fetchData()">
                <template #icon><ReloadOutlined /></template>
                {{ $t('label.refresh') }}
              </a-button>
            </template>
          </breadcrumb>
          <router-link
            v-if="!dataView && resource.id && resource.level > 0"
            class="domain-breadcrumb-path"
            :title="resource.path"
            :to="domainDetailLocation">
            {{ resource.path || resource.name }}
          </router-link>
        </div>
        <div class="domain-page-actions">
          <a-button v-if="createAction" ref="domainCreate" :aria-label="$t(createAction.label)" type="primary" shape="round" @click="execAction(createAction)">
            <template #icon><plus-outlined /></template>
            {{ $t(createAction.label) }}
          </a-button>
          <a-dropdown
            v-if="detailActions.length"
            v-model:visible="detailActionsVisible"
            :trigger="['click']"
            placement="bottomRight"
            overlayClassName="autogen-action-dropdown"
            class="autogen-action-dropdown__trigger">
            <template #overlay>
              <div class="autogen-action-dropdown__content">
                <action-button
                  :loading="loading"
                  :actions="detailActions"
                  :selected-row-keys="selectedRowKeys"
                  :data-view="true"
                  :resource="resource"
                  @exec-action="handleDropdownAction" />
              </div>
            </template>
            <a-button ref="domainActions" type="primary" class="autogen-action-dropdown__button">
              <template #icon><down-outlined /></template>
              {{ $t('label.actions') }}
            </a-button>
          </a-dropdown>
        </div>
      </div>
    </a-card>

    <div class="row-element">
      <template v-if="dataView">
        <a-alert v-if="readError" type="error" show-icon :message="readError">
          <template #description><a-button size="small" @click="fetchData()">{{ $t('label.retry') }}</a-button></template>
        </a-alert>
        <resource-view
          v-else-if="loading || resource.id"
          :key="$route.params.id"
          :resource="resource"
          :loading="loading"
          :tabs="$route.meta.tabs"
          :actions="actions"
          @refresh="refreshSelectedDomain"
          @exec-action="execAction" />
        <a-empty v-else :description="$t('label.no.data')" />
      </template>
      <tree-view
        v-else
        :key="treeViewKey"
        :tree-data="treeData"
        :resource="resource"
        :keyword="domainStore.keyword"
        :expanded-keys="domainStore.expanded"
        :active-tab="treeActiveTab"
        :tabs="treeTabs"
        :loading="loading"
        :selecting="selecting"
        :error="readError"
        :actions="actions"
        :load-children="searchMode ? undefined : loadChildren"
        @search="searchDomains"
        @refresh="fetchData()"
        @refresh-resource="refreshSelectedDomain"
        @select="selectDomain"
        @expand="expanded => changeDomainStore({ expanded })"
        @tab-change="tab => changeDomainStore({ tab })"
        @exec-action="execAction">
        <template #filters>
          <domain-tree-filters :model-value="domainStore.filters" :selection="resource.id" @change="setDomainFilters" />
        </template>
        <template #pagination>
          <a-pagination
            v-if="searchMode && resultCount > domainTreePageSize"
            size="small"
            :current="domainStore.page || 1"
            :page-size="domainTreePageSize"
            :total="resultCount"
            :show-size-changer="false"
            @change="changeSearchPage" />
        </template>
      </tree-view>
    </div>

    <div v-if="showAction">
      <domain-resource-count
        v-if="action.api === 'updateResourceCount'"
        :resource="actionResource"
        @refresh="refreshSelectedDomain"
        @close="closeAction" />
      <domain-action-form
        v-else
        :showAction="showAction"
        :resource="actionResource"
        :action="action"/>
    </div>
    <domain-delete-confirm
    v-if="showDeleteConfirm"
    :domain="deleteDomainResource"
    :deleting="domainDeletePending"
    :deletion-error="domainDeleteError"
    @close="closeDeleteConfirm"
    @confirm="confirmDeleteDomain" />
  </div>
</template>

<script>
import { Empty } from 'ant-design-vue'
import { getAPI, callAPI, postAPI } from '@/api'
import store from '@/store'
import { mixinDevice } from '@/utils/mixin.js'
import { domainTreeNodes, domainTreePath, domainTreePageSize, moveDomainCandidates } from '@/utils/domainTree'

import Breadcrumb from '@/components/widgets/Breadcrumb'
import ActionButton from '@/components/view/ActionButton'
import TreeView from '@/components/view/TreeView'
import DomainActionForm from '@/views/iam/DomainActionForm'
import DomainTreeFilters from '@/views/iam/DomainTreeFilters'
import DomainResourceCount from '@/views/iam/DomainResourceCount'
import ResourceView from '@/components/view/ResourceView'
import DomainDeleteConfirm from '@/components/view/DomainDeleteConfirm'
import eventBus from '@/config/eventBus'

export default {
  name: 'DomainView',
  components: {
    AEmpty: Empty,
    Breadcrumb,
    ActionButton,
    TreeView,
    DomainActionForm,
    DomainTreeFilters,
    DomainResourceCount,
    ResourceView,
    DomainDeleteConfirm
  },
  mixins: [mixinDevice],
  data () {
    return {
      resource: {},
      loading: false,
      selectedRowKeys: [],
      treeViewKey: 0,
      treeData: [],
      selecting: false,
      readError: '',
      resultCount: 0,
      domainTreePageSize,
      showAction: false,
      action: {},
      actionResource: {},
      dataView: false,
      domainStore: {},
      detailActionsVisible: false,
      showDeleteConfirm: false,
      deleteDomainResource: null,
      domainDeletePending: false,
      domainDeleteError: '',
      domainDeleteReturning: false,
      domainDisposed: false,
      domainSelectionGeneration: 0,
      domainRefreshGeneration: 0,
      domainRequestAbort: null,
      moveParentRequest: null,
      domainIconRefresh: null,
      actionSource: ''
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
    domainOwner () {
      const user = this.$store.getters.userInfo || {}
      if (!user.id || !user.domainid) return ''
      return JSON.stringify([user.id, user.domainid, this.$store.getters.project?.id])
    },
    treeTabs () {
      return (this.$route.meta?.tabs || []).filter(tab =>
        (!tab.show || tab.show(this.resource, this.$route, this.$store.getters.userInfo)) &&
        (!tab.permission?.[0] || tab.permission[0] in this.$store.getters.apis))
    },
    ldapVisible () {
      return 'getDomainLdapStatus' in (this.$store.getters.apis || {})
    },
    ldapTargetId () { return this.ldapVisible ? this.resource.id : null },
    searchMode () {
      return !!this.domainStore.keyword || Object.keys(this.domainStore.filters || {}).length > 0
    },
    treeActiveTab () {
      return this.treeTabs.some(tab => tab.name === this.domainStore.tab)
        ? this.domainStore.tab
        : this.treeTabs[0]?.name
    },
    breadcrumbResource () {
      return this.dataView && this.resource.path ? { ...this.resource, name: this.resource.path } : this.resource
    },
    domainDetailLocation () {
      const tab = this.treeActiveTab === 'domain' ? 'details' : this.treeActiveTab
      return { path: '/domain/' + this.resource.id, query: tab ? { tab } : {} }
    },
    createAction () { return this.menuActions.find(action => action.api === 'createDomain') },
    detailActions () { return this.menuActions.filter(action => action.api !== 'createDomain') },
    menuActions () {
      if (this.loading || this.selecting || !this.resource.id) {
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
  created () {
    this.restoreDomainStore()
    this.fetchData()
    this.domainIconRefresh = () => {
      if (this.$showIcon()) {
        this.fetchData()
      }
    }
    eventBus.on('refresh-domain-icon', this.domainIconRefresh)
  },
  beforeUnmount () {
    this.domainDisposed = true
    this.closeAction()
    this.invalidateDomainRead()
    eventBus.off('refresh-domain-icon', this.domainIconRefresh)
  },
  watch: {
    ldapTargetId (id) {
      if (id) this.readLdapState(id)
    },
    domainOwner () {
      this.restoreDomainStore()
      this.fetchData()
    },
    '$route' (to, from) {
      // When the route changes from /domain/:id to /domain or vice versa, the component is not destroyed and created again
    // So, we need to watch the route params to fetch the data again to update the component
      if (!this.domainDeleteReturning && to.path.startsWith('/domain') && from.params.id !== to.params.id) {
        this.fetchData()
      }
    }
  },
  provide () {
    return {
      parentCloseAction: this.closeAction,
      parentFetchData: this.fetchData,
      parentRefreshMovedDomain: this.refreshMovedDomain,
      parentForceRerender: this.forceRerender
    }
  },
  methods: {
    handleDropdownAction (action) {
      this.detailActionsVisible = false
      this.execAction(action)
    },
    async execAction (action) {
      this.detailActionsVisible = false
      if (this.loading || this.selecting || this.domainDeletePending) return
      const id = action.resource?.id || this.resource.id
      const currentScope = this.domainReadScope()
      if (id && id !== this.resource.id && !this.dataView) await this.selectDomain(id)
      if (!id || this.resource.id !== id || currentScope !== this.domainReadScope()) return
      const configuredAction = this.menuActions.find(item => item.api === action.api)
      if (!configuredAction) return
      const target = { ...this.resource }
      this.actionSource = action.api === 'createDomain' ? 'domainCreate' : 'domainActions'
      if (action.api === 'deleteDomain') {
        this.deleteDomainResource = target
        this.domainDeleteError = ''
        this.showDeleteConfirm = true
        return
      }
      const params = (store.getters.apis[action.api].params || []).map(param => ({ ...param }))
      const args = typeof configuredAction.args === 'function'
        ? configuredAction.args(target, this.$store.getters)
        : configuredAction.args || []
      const paramFields = args.map(name => params.find(param => param.name.toLowerCase() === name.toLowerCase())).filter(Boolean)
      this.actionResource = target
      this.action = { ...configuredAction, params, paramFields, loading: false }
      this.showAction = true
      for (const field of this.action.paramFields) {
        if (field.type === 'list' && ['tags', 'hosttags'].includes(field.name)) field.type = 'string'
        if (action.api === 'moveDomain' && field.name === 'parentdomainid') {
          this.loadMoveParents(field, target)
        } else if (['uuid', 'list'].includes(field.type) || field.name === 'account' || this.action.mapping?.[field.name]) {
          this.listUuidOpts(field)
        }
      }
    },
    domainReadScope () {
      return JSON.stringify([this.$route.path, this.$store.state?.user?.token, this.domainOwner])
    },
    invalidateDomainRead () {
      if (this.domainRequestAbort) this.domainRequestAbort.abort()
      this.domainRequestAbort = null
    },
    domainRead (command, params, signal) {
      return getAPI(command, { listall: true, showicon: true, ...params }, {
        preserveOnFailure: true, timeout: 20000, signal
      })
    },
    async readChildren (node, signal) {
      const response = await this.domainRead('listDomainChildren', { id: node.id }, signal)
      node.children = domainTreeNodes(response.listdomainchildrenresponse?.domain || [])
      node.isLeaf = node.children.length === 0
    },
    async restoreTreeBranches (nodes, requested, signal) {
      await Promise.all(nodes.filter(node => !node.isLeaf && requested.has(node.id)).map(async node => {
        await this.readChildren(node, signal)
        await this.restoreTreeBranches(node.children, requested, signal)
      }))
    },
    async loadChildren (treeNode) {
      const node = domainTreePath(this.treeData, treeNode.eventKey).at(-1)
      if (!node || node.children || this.loading || !this.domainRequestAbort) return
      const controller = this.domainRequestAbort
      const scope = this.domainReadScope()
      try {
        await this.readChildren(node, controller.signal)
        if (controller === this.domainRequestAbort && scope === this.domainReadScope()) {
          this.treeData = [...this.treeData]
        }
      } catch (error) {
        if (controller === this.domainRequestAbort && scope === this.domainReadScope()) this.notifyReadError(error)
      }
    },
    async refreshSelectedDomain () {
      const id = this.resource.id
      if (!id || this.domainDisposed) return
      const generation = ++this.domainRefreshGeneration
      const scope = this.domainReadScope()
      const controller = this.domainRequestAbort
      const current = () => !this.domainDisposed && this.resource.id === id &&
        this.domainRefreshGeneration === generation && scope === this.domainReadScope() &&
        controller === this.domainRequestAbort
      try {
        const { rows } = await this.readDomainPage('listDomains', { id }, controller?.signal)
        if (!current()) return
        const updated = domainTreeNodes(rows).find(record => record.id === id)
        if (updated) this.resource = { ...updated, domainLdap: this.resource.domainLdap }
      } catch (error) {
        if (current()) this.notifyReadError(error)
      }
    },
    async readLdapState (id) {
      const controller = this.domainRequestAbort
      const scope = this.domainReadScope()
      const current = () => !this.domainDisposed && this.resource.id === id &&
        this.domainRequestAbort === controller && scope === this.domainReadScope()
      try {
        const response = await getAPI('getDomainLdapStatus', { domainid: id }, {
          preserveOnFailure: true, timeout: 20000, signal: controller?.signal
        })
        if (!current()) return
        const status = response.getdomainldapstatusresponse?.domainldapstatus
        this.resource = { ...this.resource, domainLdap: status?.domainid === id ? status : { failed: true } }
      } catch (error) {
        if (current()) this.resource = { ...this.resource, domainLdap: { failed: true } }
      }
    },
    async readDomainPage (command, params, signal) {
      const response = await this.domainRead(command, params, signal)
      const body = response[command.toLowerCase() + 'response']
      const rows = body?.domain || []
      return { rows, count: body?.count ?? rows.length }
    },
    async readAllDomainPages (command, params, signal) {
      const result = []
      for (let page = 1; ; page++) {
        const { rows, count } = await this.readDomainPage(command, { ...params, page, pagesize: 100 }, signal)
        result.push(...rows)
        if (!rows.length || result.length >= count) return result
      }
    },
    async readDomainSearch (context, signal) {
      const filters = context.filters || {}
      const page = context.page || 1
      const params = { page, pagesize: domainTreePageSize, name: filters.name, keyword: context.keyword || undefined }
      if (!filters.parentdomainid) {
        return this.readDomainPage('listDomains', { ...params, id: filters.id, level: filters.level }, signal)
      }
      const scoped = { ...params, id: filters.parentdomainid, keyword: filters.id || params.keyword, isrecursive: !!filters.recursive, listall: !filters.recursive }
      if (!filters.name && filters.level === undefined && !(filters.id && params.keyword)) {
        return this.readDomainPage('listDomainChildren', scoped, signal)
      }
      const rows = (await this.readAllDomainPages('listDomainChildren', scoped, signal)).filter(record =>
        (!filters.name || record.name === filters.name) &&
        (filters.level === undefined || record.level === filters.level) &&
        (!filters.id || record.id.toLowerCase() === filters.id.toLowerCase()) &&
        (!params.keyword || record.name.toLowerCase().includes(params.keyword.toLowerCase()) || record.id.toLowerCase().includes(params.keyword.toLowerCase())))
      const start = (page - 1) * domainTreePageSize
      return { rows: rows.slice(start, start + domainTreePageSize), count: rows.length }
    },
    async loadMoveParents (field, target) {
      const controller = new AbortController()
      const action = this.action
      field.loading = true
      field.opts = []
      field.loadError = ''
      this.moveParentRequest = controller
      try {
        const rows = await this.readAllDomainPages('listDomains', { details: 'min', showicon: false }, controller.signal)
        if (!controller.signal.aborted && this.action === action) field.opts = moveDomainCandidates(target, rows)
      } catch (error) {
        if (!controller.signal.aborted && this.action === action) field.loadError = this.domainErrorText(error)
      } finally {
        field.loading = false
      }
    },
    refreshMovedDomain (id, parentPath) {
      if (!this.dataView || this.domainStore.selectedId === id) {
        const expanded = [...new Set([...(this.domainStore.expanded || []), ...parentPath])]
        this.changeDomainStore({ expanded, selectedId: id, selectedPath: [...parentPath, id] })
      }
      return this.fetchData()
    },
    async fetchData (selectSingleMatch = false) {
      if (this.domainDisposed || this.domainDeleteReturning || !this.$route.path.startsWith('/domain')) return
      this.invalidateDomainRead()
      const controller = new AbortController()
      this.domainRequestAbort = controller
      const scope = this.domainReadScope()
      const current = () => !this.domainDisposed && this.domainRequestAbort === controller && scope === this.domainReadScope()
      this.dataView = !!this.$route.params?.id
      this.loading = true
      this.selecting = false
      this.readError = ''
      this.detailActionsVisible = false
      this.closeAction()
      this.resource = {}
      this.treeData = []
      try {
        const id = this.$route.params?.id || this.$store.getters.userInfo.domainid
        const context = this.domainStore
        const search = !this.dataView && this.searchMode
        const { rows, count } = search
          ? await this.readDomainSearch(context, controller.signal)
          : await this.readDomainPage('listDomains', { id }, controller.signal)
        if (!current()) return
        this.resultCount = search ? count : 0
        const lastPage = Math.max(1, Math.ceil(count / domainTreePageSize))
        if (search && !rows.length && context.page > lastPage) {
          this.changeDomainStore({ page: lastPage })
          return this.fetchData(selectSingleMatch)
        }
        const nodes = domainTreeNodes(rows, search)
        if (this.dataView) {
          this.resource = nodes[0] || {}
          return
        }
        if (!search) {
          await this.restoreTreeBranches(nodes, new Set([...(context.expanded || []), ...(context.selectedPath || []).slice(0, -1)]), controller.signal)
        }
        if (!current()) return
        this.treeData = nodes
        const wanted = domainTreePath(nodes, context.selectedId)
        const initial = (!search && !context.selectedId) || (selectSingleMatch && count === 1)
        const path = wanted.length ? wanted : initial && nodes.length ? [nodes[0]] : []
        const expanded = (context.expanded || []).filter(id => domainTreePath(nodes, id).length)
        this.changeDomainStore({ expanded })
        if (path.length > 1 && !search) {
          this.loading = false
          await this.selectDomain(path.at(-1).id)
        } else {
          this.setTreeSelection(path, path.at(-1))
        }
      } catch (error) {
        if (!current()) return
        this.readError = this.domainErrorText(error)
        if (this.dataView) this.notifyReadError(error)
      } finally {
        if (current()) this.loading = false
      }
    },
    searchDomains (value) {
      const keyword = value.trim()
      if (keyword !== (this.domainStore.keyword || '')) {
        this.changeDomainStore({ keyword, expanded: [], tab: '', page: 1 })
      }
      return this.fetchData(true)
    },
    setDomainFilters (filters) {
      this.changeDomainStore({ filters, page: 1, expanded: [] })
      return this.fetchData(true)
    },
    changeSearchPage (page) {
      this.changeDomainStore({ page })
      return this.fetchData()
    },
    async selectDomain (id) {
      const path = domainTreePath(this.treeData, id)
      if (!path.length || this.loading || !this.domainRequestAbort) return
      if (this.resource.id === id) return
      const controller = this.domainRequestAbort
      const generation = ++this.domainSelectionGeneration
      const scope = this.domainReadScope()
      const current = () => !this.domainDisposed && this.domainRequestAbort === controller &&
        generation === this.domainSelectionGeneration && scope === this.domainReadScope()
      this.selecting = true
      this.resource = {}
      try {
        const response = await this.domainRead('listDomains', { id }, controller.signal)
        if (!current()) return
        const record = domainTreeNodes(response.listdomainsresponse?.domain || []).find(domain => domain.id === id)
        this.setTreeSelection(record ? path : [], record)
      } catch (error) {
        if (current()) {
          this.setTreeSelection([])
          this.notifyReadError(error)
        }
      } finally {
        if (current()) this.selecting = false
      }
    },
    setTreeSelection (path, record = {}) {
      this.resource = record
      this.changeDomainStore({ selectedId: record.id || '', selectedPath: path.map(item => item.id) })
    },
    restoreDomainStore () {
      const stored = store.getters.domainStore || {}
      this.domainStore = this.domainOwner && stored.owner === this.domainOwner ? stored : {}
    },
    changeDomainStore (changes, reset = false) {
      this.domainStore = { ...(reset ? {} : this.domainStore), ...changes, owner: this.domainOwner }
      store.dispatch('SetDomainStore', this.domainStore)
    },
    domainErrorText (error) {
      return Object.values(error.response?.data || {}).find(body => body?.errortext)?.errortext ||
        error.response?.headers?.['x-description'] || this.$t('message.request.failed')
    },
    notifyReadError (error) {
      this.$notification.error({ message: this.$t('message.request.failed'), description: this.domainErrorText(error) })
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
          const customParams = this.action.mapping[param.name].params(this.actionResource)
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
          const responseObj = Object.entries(json).find(([key]) => key.includes('response'))?.[1]
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
    restoreActionFocus () {
      const action = this.actionSource
      this.actionSource = ''
      this.$nextTick(() => {
        if (this.domainDisposed || this.showAction || this.showDeleteConfirm || document.activeElement !== document.body) return
        const target = this.$refs[action]?.$el || this.$refs.domainRefresh?.$el
        if (target?.isConnected) target.focus({ preventScroll: true })
      })
    },
    closeAction () {
      const restoreFocus = this.showAction
      if (this.moveParentRequest) this.moveParentRequest.abort()
      this.moveParentRequest = null
      this.showAction = false
      this.actionResource = {}
      if (restoreFocus) this.restoreActionFocus()
    },
    closeDeleteConfirm () {
      if (this.domainDeletePending) return
      this.showDeleteConfirm = false; this.deleteDomainResource = null; this.domainDeleteError = ''
      this.restoreActionFocus()
    },
    async returnAfterDomainDeletion () {
      this.domainDeleteReturning = true
      this.invalidateDomainRead()
      this.showDeleteConfirm = false; this.deleteDomainResource = null
      this.resource = {}
      this.treeData = []
      this.changeDomainStore({}, true)
      try {
        await this.$router.replace({ path: '/domain' })
        if (this.domainDisposed || this.$route.path !== '/domain') return
        this.treeViewKey++
      } finally { this.domainDeleteReturning = false }
      if (!this.domainDisposed && this.$route.path === '/domain') await this.fetchData()
    },
    async confirmDeleteDomain () {
      if (this.domainDeletePending || !this.deleteDomainResource?.id) return
      const domain = { ...this.deleteDomainResource }; const scope = this.domainReadScope()
      const current = () => !this.domainDisposed && scope === this.domainReadScope() && this.deleteDomainResource?.id === domain.id
      this.domainDeletePending = true; this.domainDeleteError = ''
      try {
        const json = await postAPI('deleteDomain', { id: domain.id, cleanup: true }, { preserveOnFailure: true, timeout: 20000 })
        const jobId = json?.deletedomainresponse?.jobid
        if (!jobId) throw new Error('Missing domain deletion job')
        const result = await this.$pollJob({
          jobId,
          title: this.$t('label.action.delete.domain'),
          description: domain.name,
          loadingMessage: `${this.$t('label.action.delete.domain')} ${domain.name}`,
          successMessage: `${this.$t('label.action.delete.domain')} ${domain.name}`,
          catchMessage: this.$t('error.fetching.async.job.result')
        })
        if (!current()) return
        if (result.jobstatus === 1) await this.returnAfterDomainDeletion()
        else this.domainDeleteError = result.jobresult?.errortext || this.$t('message.request.failed')
      } catch (error) {
        if (!current()) return
        this.domainDeleteError = Object.values(error.response?.data || {}).find(body => body?.errortext)?.errortext ||
          error.response?.headers?.['x-description'] || this.$t('message.request.failed')
      } finally {
        if (!this.domainDisposed) this.domainDeletePending = false
      }
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

  .domain-page-toolbar,
  .domain-page-location,
  .domain-page-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }

  .domain-page-toolbar { justify-content: space-between; }
  .domain-page-location { min-width: 0; flex: 1 1 380px; }
  .domain-breadcrumb-path {
    max-width: ~"min(100%, 420px)";
    text-overflow: ellipsis;
    white-space: nowrap;
    overflow: hidden;
  }
  @media (max-width: 600px) {
    .domain-page-actions { width: 100%; }
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
</style>
