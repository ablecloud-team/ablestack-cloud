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
    <a-button v-if="'registerExtension' in $store.getters.apis && ['Orchestrator', 'NetworkOrchestrator'].includes(resource.type)" type="primary" class="register-extension-resource" @click="openRegisterModal">
      <template #icon><plus-outlined /></template>
      {{ $t('label.register.extension') }}
    </a-button>
    <a-table
      size="small"
      :columns="columns"
      :dataSource="extensionResources"
      :rowKey="item => item.id"
      :pagination="false"
      :rowExpandable="(record) => record.details && Object.keys(record.details).length > 0">
      <template #bodyCell="{ column, text, record }">
        <template v-if="column.key === 'name'">
          <router-link :to="{ path: '/' + record.type.toLowerCase() + '/' + record.id }">
            {{ text }}
          </router-link>
        </template>
        <template v-if="['created'].includes(column.key)">
          {{ text && $toLocaleDate(text) }}
        </template>
        <template v-if="column.key === 'actions'">
          <span style="margin-right: 5px">
            <tooltip-button
              v-if="'updateRegisteredExtension' in $store.getters.apis"
              :tooltip="$t('label.action.update.extension.resource')"
              type="default"
              icon="edit-outlined"
              @onClick="openUpdateModal(record)" />
          </span>
          <tooltip-button
            v-if="'unregisterExtension' in $store.getters.apis"
            :tooltip="$t('label.action.unregister.extension.resource')"
            type="primary"
            :danger="true"
            icon="delete-outlined"
            @onClick="openUnregister(record)" />
        </template>
      </template>
      <template #expandedRowRender="{ record }">
        <strong>{{ $t('label.details') }}</strong>
        <object-list-table
          :data-map="record.details" />
      </template>
    </a-table>
    <mold-dialog
      :after-close="restoreDialogFocus"
      size="md"
      form-actions
      focus-guard
      :visible="registerModalVisible"
      :title="$t('label.register.extension')"
      @cancel="registerModalVisible = false">
      <register-extension v-if="registerModalVisible" :resource="resource" @refresh-data="handleRefreshData" @close-action="registerModalVisible = false" />
    </mold-dialog>
    <mold-dialog
      :after-close="restoreDialogFocus"
      size="md"
      form-actions
      focus-guard
      :visible="updateModalVisible"
      :title="$t('label.action.update.extension.resource')"
      @cancel="closeUpdateModal">
      <update-registered-extension
v-if="updateModalVisible"
        :resource="resource"
        :extension-resource="selectedResource"
        @refresh-data="handleRefreshData"
        @close-action="closeUpdateModal" />
    </mold-dialog>
    <mold-dialog
      :after-close="restoreDialogFocus"
      size="sm"
      :visible="!!unregisterTarget"
      :title="$t('label.action.unregister.extension.resource')"
      :closable="!unregisterLoading"
      :keyboard="!unregisterLoading"
      @cancel="closeUnregister">
      <p>{{ unregisterTarget?.name }}</p>
      <p>{{ $t('message.action.unregister.extension.resource') }}</p>
      <template #footer>
        <a-button :disabled="unregisterLoading" @click="closeUnregister">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" danger :loading="unregisterLoading" @click="unregisterExtension(unregisterTarget)">{{ $t('label.ok') }}</a-button>
      </template>
    </mold-dialog>
  </div>
</template>

<script>
import MoldDialog from '@/components/view/MoldDialog'
import { postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import ObjectListTable from '@/components/view/ObjectListTable.vue'
import TooltipButton from '@/components/widgets/TooltipButton'
import UpdateRegisteredExtension from '@/views/extension/UpdateRegisteredExtension'
import RegisterExtension from '@/views/extension/RegisterExtension'

export default {
  name: 'ExtensionResourcesTab',
  components: {
    MoldDialog,
    ObjectListTable,
    TooltipButton,
    UpdateRegisteredExtension,
    RegisterExtension
  },
  inject: ['parentFetchData'],
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
      columns: [
        {
          key: 'name',
          title: this.$t('label.name'),
          dataIndex: 'name'
        },
        {
          title: this.$t('label.type'),
          dataIndex: 'type'
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
      unregisterLoading: false,
      unregisterTarget: null,
      updateModalVisible: false,
      registerModalVisible: false,
      selectedResource: null
    }
  },
  computed: {
    extensionResources () {
      return this.resource?.resources || []
    }
  },
  methods: {
    rememberDialogTrigger () {
      const active = document.activeElement
      this.dialogTrigger = active instanceof HTMLElement && active !== document.body ? active : null
    },
    restoreDialogFocus () {
      const trigger = this.dialogTrigger?.isConnected ? this.dialogTrigger : this.$el?.querySelector?.('.register-extension-resource')
      this.dialogTrigger = null
      if (trigger) trigger.focus()
    },
    openRegisterModal () {
      this.rememberDialogTrigger()
      this.registerModalVisible = true
    },
    openUnregister (record) {
      this.rememberDialogTrigger()
      this.unregisterTarget = record
    },
    openUpdateModal (record) {
      this.rememberDialogTrigger()
      this.selectedResource = record
      this.updateModalVisible = true
    },
    closeUpdateModal () {
      this.updateModalVisible = false
      this.selectedResource = null
    },
    handleRefreshData () {
      if (this.parentFetchData) {
        this.parentFetchData()
      }
    },
    closeUnregister () {
      if (!this.unregisterLoading) this.unregisterTarget = null
    },
    unregisterExtension (record) {
      if (!record || this.unregisterLoading) return
      const params = {
        extensionid: this.resource.id,
        resourceid: record.id,
        resourcetype: record.type
      }
      this.unregisterLoading = true
      postAPI('unregisterExtension', params).then(() => {
        this.unregisterTarget = null
        eventBus.emit('async-job-complete', null)
        this.$notification.success({
          message: this.$t('label.unregister.extension'),
          description: this.$t('message.success.unregister.extension')
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.unregisterLoading = false
      })
    }
  }
}
</script>

<style scoped>
.register-extension-resource { margin-bottom: 12px; }
</style>
