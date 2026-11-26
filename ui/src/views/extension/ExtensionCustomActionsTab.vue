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
    <a-alert v-if="listRefreshFailed" type="warning" show-icon :message="$t('message.list.refresh.stale')">
      <template #description><a-button size="small" @click="fetchCustomActions">{{ $t('label.refresh') }}</a-button></template>
    </a-alert>
    <div class="extension-action-toolbar">
      <a-button type="primary" @click="showAddCustomActionModal" :disabled="!('addCustomAction' in $store.getters.apis)">
        <template #icon><plus-outlined /></template> {{ $t('label.add.custom.action') }}
      </a-button>
      <a-input-search v-model:value="searchInput" :aria-label="$t('label.search')" :placeholder="$t('label.search')" allow-clear @search="changeCriteria('actionkeyword', $event)" />
      <a-select :value="criteria.actiontype || ''" :aria-label="$t('label.resourcetype')" @change="changeCriteria('actiontype', $event)">
        <a-select-option value="">{{ $t('label.resourcetype') }} · {{ $t('label.all') }}</a-select-option>
        <a-select-option v-for="type in resourceTypes" :key="type" :value="type">{{ type }}</a-select-option>
      </a-select>
      <a-select :value="criteria.actionstate || ''" :aria-label="$t('label.state')" @change="changeCriteria('actionstate', $event)">
        <a-select-option value="">{{ $t('label.state') }} · {{ $t('label.all') }}</a-select-option>
        <a-select-option value="true">{{ $t('label.enabled') }}</a-select-option>
        <a-select-option value="false">{{ $t('label.disabled') }}</a-select-option>
      </a-select>
      <a-button @click="fetchCustomActions">{{ $t('label.refresh') }}</a-button>
    </div>
    <detail-tab-table
      size="small"
      :columns="columns"
      :dataSource="extensionCustomActions"
      :rowKey="item => item.id"
      :pagination="false"
      :loading="tabLoading"
      :rowExpandable="(record) => record.parameters && Object.keys(record.parameters).length > 0">
      <template #bodyCell="{ column, text, record }">
        <template v-if="column.key === 'name'">
          <router-link :to="{ path: '/customaction/' + record.id, query: actionDetailQuery }">
            {{ text }}
          </router-link>
        </template>
        <template v-if="['enabled'].includes(column.key)">
          <status :text="text ? 'Enabled' : 'Disabled'" displayText />
        </template>
        <template v-if="['created'].includes(column.key)">
          {{ text && $toLocaleDate(text) }}
        </template>
        <template v-if="column.key === 'actions'">
          <span style="margin-right: 5px">
            <tooltip-button
              v-if="'updateCustomAction' in $store.getters.apis"
              :tooltip="$t('label.update.custom.action')"
              icon="edit-outlined"
              @click="openUpdateCustomActionModal(record)" />
          </span>
          <tooltip-button
            v-if="'updateCustomAction' in $store.getters.apis"
            :tooltip="$t(record.enabled ? 'label.disable.custom.action' : 'label.enable.custom.action')"
            :icon="record.enabled ? 'pause-circle-outlined' : 'play-circle-outlined'"
            @onClick="openConfirmation(record, 'toggle')" />
          <tooltip-button
            v-if="'deleteCustomAction' in $store.getters.apis"
            :tooltip="$t('label.delete.custom.action')"
            type="primary"
            :danger="true"
            icon="delete-outlined"
            @onClick="openConfirmation(record, 'delete')" />
        </template>
      </template>
      <template #expandedRowRender="{ record }">
        <strong>{{ $t('label.parameters') }}</strong>
        <object-list-table
          :showHeader="true"
          :data-array="record.parameters" />
      </template>
    </detail-tab-table>
    <detail-tab-pagination
      style="margin-top: 12px"
      size="small"
      :current="page"
      :page-size="pageSize"
      :total="totalCount"
      :show-total="total => $t('label.total') + ' ' + total + ' ' + $t('label.items')"
      :page-size-options="['10', '20', '40', '80']"
      show-size-changer
      @change="changePage" />

    <mold-dialog
      :after-close="restoreDialogFocus"
      size="lg"
      form-actions
      focus-guard
      :visible="showAddCustomAction"
      :title="$t('label.add.custom.action')"
      @cancel="closeModals">
      <add-custom-action v-if="showAddCustomAction" :extension="resource" @refresh-data="refreshAfterChange" @close-action="closeModals" />
    </mold-dialog>

    <mold-dialog
      :after-close="restoreDialogFocus"
      size="lg"
      form-actions
      focus-guard
      :visible="showUpdateCustomAction"
      :title="$t('label.update.custom.action')"
      @cancel="closeModals">
      <update-custom-action
v-if="showUpdateCustomAction"
      :key="customActionForUpdate.id"
      :resource="customActionForUpdate"
      @refresh-data="refreshAfterChange"
      @close-action="closeModals" />
    </mold-dialog>
    <mold-dialog
      :after-close="restoreDialogFocus"
      size="sm"
      :visible="!!confirmation"
      :title="$t(confirmationTitle)"
      :closable="!confirmationBusy"
      :keyboard="!confirmationBusy"
      @cancel="closeConfirmation">
      <p>{{ confirmation?.record.name }}</p>
      <p>{{ $t(confirmationMessage) }}</p>
      <template #footer>
        <a-button :disabled="confirmationBusy" @click="closeConfirmation">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :danger="confirmation?.operation === 'delete'" :loading="confirmationBusy" @click="confirmOperation">{{ $t('label.ok') }}</a-button>
      </template>
    </mold-dialog>
  </div>
</template>

<script>
import MoldDialog from '@/components/view/MoldDialog'
import { customActionResourceTypes, extensionActionListQuery, extensionListQuery } from '@/utils/extension'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

import { getAPI, postAPI } from '@/api'
import ObjectListTable from '@/components/view/ObjectListTable.vue'
import TooltipButton from '@/components/widgets/TooltipButton'
import AddCustomAction from '@/views/extension/AddCustomAction.vue'
import UpdateCustomAction from '@/views/extension/UpdateCustomAction.vue'
import Status from '@/components/widgets/Status'

export default {
  mixins: [listRefreshMixin(['fetchCustomActions'], {
    scopeKey: vm => [vm.$route.path, vm.$store.getters.userInfo?.id, vm.$store.getters.project?.id,
      vm.resource.id, vm.criteria, vm.page, vm.pageSize, vm.listRevision]
  })],
  name: 'ExtensionCustomActionsTab',
  components: {
    MoldDialog,
    ObjectListTable,
    TooltipButton,
    AddCustomAction,
    UpdateCustomAction,
    Status
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      tabLoading: false,
      dialogTrigger: null,
      extensionCustomActions: [],
      listRevision: 0,
      columns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          key: 'enabled',
          title: this.$t('label.state'),
          dataIndex: 'enabled'
        },
        {
          title: this.$t('label.resourcetype'),
          dataIndex: 'resourcetype'
        },
        {
          key: 'created',
          title: this.$t('label.created'),
          dataIndex: 'created'
        },
        {
          key: 'actions',
          title: this.$t('label.actions')
        }
      ],
      criteria: extensionActionListQuery(this.$route.query),
      searchInput: this.$route.query.actionkeyword || '',
      page: Math.max(1, Number(this.$route.query.actionpage) || 1),
      pageSize: [10, 20, 40, 80].includes(Number(this.$route.query.actionpagesize)) ? Number(this.$route.query.actionpagesize) : 20,
      totalCount: 0,
      showAddCustomAction: false,
      updateLoading: false,
      deleteLoading: false,
      confirmation: null,
      showUpdateCustomAction: false,
      customActionForUpdate: {}
    }
  },
  computed: {
    confirmationBusy () { return this.updateLoading || this.deleteLoading },
    confirmationTitle () {
      if (this.confirmation?.operation === 'delete') return 'label.delete.custom.action'
      return this.confirmation?.record.enabled ? 'label.disable.custom.action' : 'label.enable.custom.action'
    },
    confirmationMessage () {
      if (this.confirmation?.operation === 'delete') return 'message.action.delete.custom.action'
      return this.confirmation?.record.enabled ? 'message.confirm.disable.custom.action' : 'message.confirm.enable.custom.action'
    },
    resourceTypes () { return customActionResourceTypes(this.resource.type) },
    actionDetailQuery () {
      return { ...extensionListQuery(this.$route.query), ...this.actionQuery() }
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    'resource.id' () {
      this.page = 1
      this.totalCount = 0
      this.extensionCustomActions = []
      this.listRefreshFailed = false
      this.fetchCustomActions()
    }
  },
  methods: {
    rememberDialogTrigger () {
      const active = document.activeElement
      this.dialogTrigger = active instanceof HTMLElement && active !== document.body ? active : null
    },
    restoreDialogFocus () {
      const trigger = this.dialogTrigger?.isConnected ? this.dialogTrigger : this.$el?.querySelector?.('.extension-action-toolbar button:not([disabled])')
      this.dialogTrigger = null
      if (trigger) trigger.focus()
    },
    fetchData () {
      this.fetchCustomActions()
    },
    refreshAfterChange () {
      this.listRevision += 1
      return this.fetchCustomActions()
    },
    actionQuery () {
      return extensionActionListQuery({ ...this.criteria, actionpage: String(this.page), actionpagesize: String(this.pageSize) })
    },
    syncQuery () {
      const query = { ...this.$route.query, ...this.actionQuery() }
      for (const key of ['actionkeyword', 'actiontype', 'actionstate']) {
        if (!this.criteria[key]) delete query[key]
      }
      this.$router.replace({ query })
    },
    changeCriteria (key, value) {
      this.criteria = { ...this.criteria, [key]: value?.trim() || '' }
      this.page = 1
      this.syncQuery()
      this.fetchCustomActions()
    },
    changePage (page, size) {
      this.page = size === this.pageSize ? page : 1
      this.pageSize = size
      this.syncQuery()
      this.fetchCustomActions()
    },
    fetchCustomActions () {
      const listRequest = this.listRequestToken('fetchCustomActions')

      if (!this.resource.id) {
        return
      }
      const params = {
        page: this.page,
        pagesize: this.pageSize,
        extensionid: this.resource.id,
        listall: true
      }
      if (this.criteria.actionkeyword) params.keyword = this.criteria.actionkeyword
      if (this.resourceTypes.includes(this.criteria.actiontype)) params.resourcetype = this.criteria.actiontype
      if (['true', 'false'].includes(this.criteria.actionstate)) params.enabled = this.criteria.actionstate
      this.tabLoading = !listRequest.loaded
      return getAPI('listCustomActions', params, { preserveOnFailure: true }).then(json => {
        if (!this.isListRequestCurrent('fetchCustomActions', listRequest)) return
        this.totalCount = json?.listcustomactionsresponse?.count || 0
        const lastPage = Math.max(1, Math.ceil(this.totalCount / this.pageSize))
        if (this.page > lastPage) {
          this.page = lastPage
          this.syncQuery()
          return this.fetchCustomActions()
        }
        this.extensionCustomActions = json?.listcustomactionsresponse?.extensioncustomaction || []
        this.tabLoading = false
      }).catch(error => {
        if (!this.isListRequestCurrent('fetchCustomActions', listRequest)) return
        listRequest.failed = true
        this.listRefreshFailed = true
        if (listRequest.loaded) return

        this.$notifyError(error)
      }).finally(() => {
        if (!this.isListRequestCurrent('fetchCustomActions', listRequest)) return
        this.tabLoading = false
      })
    },
    showAddCustomActionModal () {
      this.rememberDialogTrigger()
      this.showAddCustomAction = true
    },
    openUpdateCustomActionModal (action) {
      this.rememberDialogTrigger()
      this.customActionForUpdate = action
      this.showUpdateCustomAction = true
    },
    closeModals () {
      this.showAddCustomAction = false
      this.showUpdateCustomAction = false
      this.customActionForUpdate = {}
    },
    openConfirmation (record, operation) {
      this.rememberDialogTrigger()
      this.confirmation = { record, operation }
    },
    closeConfirmation () {
      if (!this.confirmationBusy) this.confirmation = null
    },
    confirmOperation () {
      if (!this.confirmation || this.confirmationBusy) return
      const { record, operation } = this.confirmation
      if (operation === 'delete') this.deleteCustomAction(record)
      else this.updateCustomAction(record)
    },
    updateCustomAction (record) {
      const params = {
        id: record.id,
        enabled: !record.enabled
      }
      this.updateLoading = true
      postAPI('updateCustomAction', params).then(json => {
        this.confirmation = null
        this.refreshAfterChange()
        this.$notification.success({
          message: record.enabled ? this.$t('label.disable.custom.action') : this.$t('label.enable.custom.action'),
          description: this.$t('message.success.update.custom.action')
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.updateLoading = false
      })
    },
    deleteCustomAction (record) {
      const params = {
        id: record.id
      }
      this.deleteLoading = true
      postAPI('deleteCustomAction', params).then(json => {
        this.confirmation = null
        this.refreshAfterChange()
        this.$notification.success({
          message: this.$t('label.delete.custom.action'),
          description: this.$t('message.success.delete.custom.action')
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.deleteLoading = false
      })
    }
  }
}
</script>

<style scoped>
.extension-action-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-block: 0 12px; align-items: center; }
.extension-action-toolbar :deep(.ant-input-search) { width: 220px; max-width: 100%; }
.extension-action-toolbar :deep(.ant-select) { min-width: 140px; max-width: 100%; }
</style>
