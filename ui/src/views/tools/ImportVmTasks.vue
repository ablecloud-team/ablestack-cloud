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
  <a-row :gutter="12">
    <a-col :md="24" :lg="24">
      <a-card class="instances-card">
        <template #title>
          <div class="import-vm-tasks-header">
            <div class="import-vm-tasks-title">
              <span>{{ $t('label.import.vm.tasks') }}</span>
              <a-tooltip :title="$t('message.import.vm.tasks')">
                <info-circle-outlined />
              </a-tooltip>
              <a-button
                class="import-vm-tasks-refresh"
                :loading="loading"
                size="small"
                shape="circle"
                @click="this.$emit('fetch-import-vm-tasks')" >
                <template #icon><reload-outlined /></template>
              </a-button>
            </div>
            <div class="import-vm-tasks-tools">
            <a-select
              class="import-vm-tasks-filter"
              :placeholder="$t('label.filterby')"
              :value="filter"
              size="small"
              @change="onFilterChange"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }"
            >
              <template #suffixIcon><filter-outlined /></template>
                <a-select-option
                  v-for="filter in filters"
                  :key="filter"
                  :label="$t('label.' + filter)"
                >
                  {{ $t('label.' + filter) }}
                </a-select-option>
              </a-select>
            </div>
          </div>
        </template>
        <div class="import-vm-tasks-table-wrap">
          <a-table
            :data-source="tasks"
            class="instances-card-table import-vm-tasks-table"
            size="small"
            :pagination="false"
            :columns="taskColumns"
            :scroll="{ x: taskTableScrollX }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'convertinstancehostid'">
                <router-link :to="{ path: '/host/' + record.convertinstancehostid }">{{ record.convertinstancehostname }}</router-link>
              </template>
              <template v-else-if="column.key === 'migrationtool'">
                <a-space>
                  <a-tag color="blue">{{ getMigrationToolLabel(record.migrationtool) }}</a-tag>
                  <a-tag>{{ getSourceProviderLabel(record.sourceprovider) }}</a-tag>
                </a-space>
              </template>
              <template v-else-if="column.key === 'displayname'">
                <router-link v-if="record.virtualmachineid" :to="{ path: '/vm/' + record.virtualmachineid }">{{ record.displayname }}</router-link>
                <span v-else>{{ record.displayname }}</span>
              </template>
              <template v-else-if="column.key === 'created'">
                <span>{{ $toLocaleDate(record.created) }}</span>
              </template>
              <template v-else-if="column.key === 'step'">
                <span>{{ record.displaystep || record.step || '-' }}</span>
              </template>
              <template v-else-if="column.key === 'credentialstate'">
                <a-tag :color="getCredentialStateColor(record.credentialstate)">
                  {{ $t('label.credential.state.' + (record.credentialstate || 'unknown')) }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'state'">
                <status :text="record.state || ''" displayText />
              </template>
              <template v-else-if="column.key === 'description'">
                <div class="import-vm-task-sync-progress">
                  <div class="import-vm-task-sync-title">{{ getSyncProgressTitle(record) }}</div>
                  <div class="import-vm-task-sync-bytes">{{ getSyncProgressBytes(record) }}</div>
                  <div class="import-vm-task-sync-cumulative">{{ getSyncProgressCumulative(record) }}</div>
                  <a-progress
                    v-if="Number.isFinite(getSyncProgressPercent(record))"
                    size="small"
                    :percent="getSyncProgressPercent(record)"
                    :show-info="false" />
                </div>
              </template>
              <template v-else-if="column.key === 'actions'">
                <a-space class="import-vm-task-actions">
                  <a-button size="small" @click="openTaskDetail(record)">
                    <template #icon><eye-outlined /></template>
                    {{ $t('label.details') }}
                  </a-button>
                  <a-dropdown v-if="getTaskActions(record).length > 0" :trigger="['click']">
                    <a-button size="small">
                      {{ $t('label.select.action') }}
                      <down-outlined />
                    </a-button>
                    <template #overlay>
                      <a-menu @click="({ key }) => openActionConfirm(record, key)">
                        <a-menu-item
                          v-for="action in getTaskActions(record)"
                          :key="action.key"
                          :class="{ 'import-vm-task-action-danger': action.danger }">
                          <component :is="action.icon" v-if="action.icon" />
                          <span>{{ action.label }}</span>
                        </a-menu-item>
                      </a-menu>
                    </template>
                  </a-dropdown>
                </a-space>
              </template>
            </template>
          </a-table>
        </div>
        <div class="instances-card-footer">
          <a-pagination
            class="row-element"
            size="small"
            :current="page"
            :pageSize="pageSize"
            :total="total"
            :showTotal="total => `${$t('label.showing')} ${Math.min(total, 1+((page-1)*pageSize))}-${Math.min(page*pageSize, total)} ${$t('label.of')} ${total} ${$t('label.items')}`"
            @change="onPaginationChange"
            showQuickJumper>
            <template #buildOptionText="props">
              <span>{{ props.value }} / {{ $t('label.page') }}</span>
            </template>
          </a-pagination>
        </div>
      </a-card>
    </a-col>
    <a-drawer
      :visible="detailVisible"
      :title="$t('label.import.vm.task.details')"
      class="import-vm-task-detail-drawer"
      width="560"
      @close="detailVisible = false">
      <a-descriptions v-if="selectedTask" size="small" bordered :column="1">
        <a-descriptions-item :label="$t('label.name')">{{ selectedTask.displayname || selectedTask.sourcevmname }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.migration.tool')">{{ getMigrationToolLabel(selectedTask.migrationtool) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.source.provider')">{{ getSourceProviderLabel(selectedTask.sourceprovider) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.target.storage.plan')">
          {{ selectedTask.targetprofile || '-' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.phase')">{{ selectedTask.phase || selectedTask.currentphase || '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.state')">{{ selectedTask.migrationstate || selectedTask.state || '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.workdir')">{{ selectedTask.workdir || '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.credential.state')">
          {{ $t('label.credential.state.' + (selectedTask.credentialstate || 'unknown')) }}
        </a-descriptions-item>
      </a-descriptions>
      <a-divider />
      <a-row justify="space-between" align="middle" style="margin-bottom: 12px">
        <a-col>{{ $t('label.events') }}</a-col>
        <a-col>
          <a-button size="small" :loading="eventsLoading" @click="fetchTaskEvents(selectedTask)">
            <template #icon><reload-outlined /></template>
          </a-button>
        </a-col>
      </a-row>
      <a-spin :spinning="eventsLoading">
        <a-timeline v-if="taskEvents.length > 0">
          <a-timeline-item v-for="event in taskEvents" :key="event.id">
            <div class="task-event-line">
              <strong>{{ event.eventtype }}</strong>
              <span>{{ $toLocaleDate(event.created) }}</span>
            </div>
            <div class="task-event-message">{{ event.message }}</div>
            <div class="task-event-meta">{{ event.phase || '-' }} / {{ event.state || '-' }} / {{ event.step || '-' }}</div>
          </a-timeline-item>
        </a-timeline>
        <a-empty v-else />
      </a-spin>
    </a-drawer>
    <a-modal
      :visible="actionConfirmVisible"
      :title="selectedAction ? selectedAction.label : $t('label.select.action')"
      :okText="$t('label.ok')"
      :cancelText="$t('label.cancel')"
      wrapClassName="import-vm-task-action-confirm-modal"
      :okButtonProps="{ danger: selectedAction && selectedAction.danger, loading: actionConfirmLoading }"
      @ok="confirmSelectedAction"
      @cancel="closeActionConfirm">
      <p class="import-vm-task-action-confirm-message">{{ selectedAction ? selectedAction.confirmMessage : '' }}</p>
      <a-descriptions v-if="actionTask" class="import-vm-task-action-confirm-summary" size="small" bordered :column="1">
        <a-descriptions-item :label="$t('label.name')">{{ actionTask.displayname || actionTask.sourcevmname }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.state')">{{ actionTask.state || '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.phase')">{{ actionTask.phase || actionTask.currentphase || '-' }}</a-descriptions-item>
      </a-descriptions>
    </a-modal>
    <a-modal
      :visible="phase2Visible"
      :title="$t('label.phase2.execute')"
      :okText="$t('label.ok')"
      :cancelText="$t('label.cancel')"
      @ok="confirmPhase2"
      @cancel="phase2Visible = false">
      <a-alert
        type="info"
        :showIcon="true"
        :message="$t('message.phase2.credential.reuse')"
        style="margin-bottom: 12px" />
      <a-form layout="vertical">
        <a-form-item :label="phase2EndpointLabel">
          <a-input v-model:value="phase2Form.endpoint" />
        </a-form-item>
        <a-form-item :label="$t('label.username')">
          <a-input v-model:value="phase2Form.username" />
        </a-form-item>
        <a-form-item :label="$t('label.password')">
          <a-input-password v-model:value="phase2Form.password" />
        </a-form-item>
        <a-form-item v-if="isN2KTask(phase2Task)" :label="$t('label.source.api')">
          <a-select v-model:value="phase2Form.sourceapi">
            <a-select-option value="v3">{{ $t('label.nutanix.api.v3') }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="isN2KTask(phase2Task)" :label="$t('label.n2k.target.vm.power.policy')">
          <a-select v-model:value="phase2Form.starttargetvm">
            <a-select-option :value="null">{{ $t('label.keep.current.setting') }}</a-select-option>
            <a-select-option :value="true">{{ $t('label.n2k.start.target.vm') }}</a-select-option>
            <a-select-option :value="false">{{ $t('label.n2k.keep.target.vm.stopped') }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item v-if="isN2KTask(phase2Task)" :label="$t('label.n2k.snapshot.retention.days')">
          <a-input-number
            v-model:value="phase2Form.retentiondays"
            :min="1"
            :max="365"
            :precision="0"
            style="width: 100%" />
        </a-form-item>
        <a-form-item v-if="isN2KTask(phase2Task)" :label="$t('label.skip.tls.verify')">
          <a-switch v-model:checked="phase2Form.insecure" />
        </a-form-item>
      </a-form>
    </a-modal>
  </a-row>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import Status from '@/components/widgets/Status'

export default {
  name: 'ImportVmTasks',
  components: {
    Status
  },
  props: {
    tasks: {
      type: Array,
      required: true
    },
    loading: {
      type: Boolean,
      required: false
    },
    filter: {
      type: String,
      required: false
    },
    total: {
      type: Number,
      required: true
    },
    page: {
      type: Number,
      required: true
    },
    pageSize: {
      type: Number,
      required: true
    }
  },
  data () {
    const columns = [
      {
        key: 'migrationtool',
        title: this.$t('label.migration.tool'),
        dataIndex: 'migrationtool',
        width: 205,
        className: 'import-vm-task-cell-compact'
      },
      {
        key: 'created',
        title: this.$t('label.created'),
        dataIndex: 'created',
        width: 118
      },
      {
        key: 'displayname',
        title: this.$t('label.displayname'),
        dataIndex: 'displayname',
        width: 96
      },
      {
        key: 'convertinstancehostid',
        title: this.$t('label.conversionhost'),
        dataIndex: 'convertinstancehostid',
        width: 120
      },
      {
        key: 'phase',
        title: this.$t('label.phase'),
        dataIndex: 'phase',
        width: 82
      },
      {
        key: 'step',
        title: this.$t('label.currentstep'),
        dataIndex: 'step',
        width: 150
      },
      {
        key: 'credentialstate',
        title: this.$t('label.credential.state'),
        dataIndex: 'credentialstate',
        width: 112
      },
      {
        key: 'stepduration',
        title: this.$t('label.currentstep.duration'),
        dataIndex: 'stepduration',
        width: 104
      },
      {
        key: 'description',
        title: this.$t('label.sync.progress.status'),
        dataIndex: 'description',
        width: 230
      },
      {
        key: 'duration',
        title: this.$t('label.totalduration'),
        dataIndex: 'duration',
        width: 90
      },
      {
        key: 'sourcevmname',
        title: this.$t('label.sourcevmname'),
        dataIndex: 'sourcevmname',
        width: 100
      },
      {
        key: 'vcenter',
        title: this.$t('label.vcenter'),
        dataIndex: 'vcenter',
        width: 128
      },
      {
        key: 'datacentername',
        title: this.$t('label.vcenter.datacenter'),
        dataIndex: 'datacentername',
        width: 122
      },
      {
        key: 'state',
        title: this.$t('label.state'),
        dataIndex: 'state',
        width: 90
      },
      {
        key: 'actions',
        title: this.$t('label.actions'),
        dataIndex: 'actions',
        width: 210,
        fixed: 'right'
      }
    ]
    return {
      columns,
      filters: ['all', 'running', 'completed', 'failed'],
      filterValue: 'running',
      detailVisible: false,
      selectedTask: null,
      taskEvents: [],
      eventsLoading: false,
      phase2Visible: false,
      phase2Task: null,
      phase2Form: {
        endpoint: '',
        username: '',
        password: '',
        sourceapi: 'v3',
        starttargetvm: null,
        insecure: true,
        retentiondays: 14
      },
      actionConfirmVisible: false,
      actionConfirmLoading: false,
      actionTask: null,
      selectedAction: null
    }
  },
  computed: {
    taskTableScrollX () {
      return this.taskColumns.reduce((total, column) => total + (column.width || 120), 0)
    },
    taskColumns () {
      const hasTasks = Array.isArray(this.tasks) && this.tasks.length > 0
      const allN2K = hasTasks && this.tasks.every(task => this.isN2KTask(task))
      const allV2K = hasTasks && this.tasks.every(task => !this.isN2KTask(task))
      return this.columns.map(column => {
        if (column.key === 'vcenter') {
          return {
            ...column,
            title: allN2K
              ? this.$t('label.nutanix.prism.endpoint')
              : allV2K ? this.$t('label.vcenter') : this.$t('label.source.endpoint')
          }
        }
        if (column.key === 'datacentername') {
          return {
            ...column,
            title: allN2K
              ? this.$t('label.cluster')
              : allV2K ? this.$t('label.vcenter.datacenter') : this.$t('label.source.location')
          }
        }
        return column
      })
    },
    phase2EndpointLabel () {
      return this.isN2KTask(this.phase2Task) ? this.$t('label.nutanix.prism.endpoint') : this.$t('label.vcenter')
    }
  },
  methods: {
    fetchData () {
      this.$emit('fetch-import-vm-tasks')
    },
    openPhase2Modal (record) {
      this.phase2Task = record
      this.phase2Form = {
        endpoint: '',
        username: '',
        password: '',
        sourceapi: 'v3',
        starttargetvm: null,
        insecure: true,
        retentiondays: 14
      }
      this.phase2Visible = true
    },
    confirmPhase2 () {
      const credential = {}
      if (this.phase2Form.endpoint) credential.endpoint = this.phase2Form.endpoint
      if (this.phase2Form.username) credential.username = this.phase2Form.username
      if (this.phase2Form.password) credential.password = this.phase2Form.password
      if (this.isN2KTask(this.phase2Task)) {
        credential.sourceapi = 'v3'
        if (this.phase2Form.starttargetvm !== null && this.phase2Form.starttargetvm !== undefined) {
          credential.starttargetvm = this.phase2Form.starttargetvm
        }
        credential.insecure = this.phase2Form.insecure !== false
        const retentionDays = Number(this.phase2Form.retentiondays || 14)
        if (!Number.isFinite(retentionDays) || retentionDays < 1) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.error.input.value')
          })
          return
        }
        credential.retentionseconds = Math.round(retentionDays * 24 * 60 * 60)
      }
      this.phase2Visible = false
      this.$emit('start-phase2', {
        task: this.phase2Task,
        credential
      })
    },
    openTaskDetail (record) {
      this.selectedTask = record
      this.detailVisible = true
      this.fetchTaskEvents(record)
    },
    fetchTaskEvents (record) {
      if (!record?.id) {
        return
      }
      this.eventsLoading = true
      getAPI('listImportVmTaskEvents', {
        importvmtaskid: record.id,
        page: 1,
        pagesize: 50
      }).then(response => {
        this.taskEvents = response.listimportvmtaskeventsresponse?.importvmtaskevent || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.eventsLoading = false
      })
    },
    getTaskActions (record) {
      const actions = []
      const availableActions = Array.isArray(record?.availableactions) ? record.availableactions : []
      const definitions = {
        phase2: {
          key: 'phase2',
          label: this.$t('label.phase2.execute'),
          icon: 'play-circle-outlined',
          confirmMessage: this.$t('message.confirm.phase2.import.vm.task')
        },
        resume: {
          key: 'resume',
          label: this.$t('label.resume.import.vm.task'),
          icon: 'redo-outlined',
          confirmMessage: this.$t('message.confirm.resume.import.vm.task')
        },
        retryfromstart: {
          key: 'retryfromstart',
          label: this.$t('label.retry.import.vm.task.from.start'),
          icon: 'rollback-outlined',
          danger: true,
          confirmMessage: this.$t('message.confirm.retry.import.vm.task.from.start')
        },
        cancel: {
          key: 'cancel',
          label: this.$t('label.cancel.import.vm.task'),
          icon: 'stop-outlined',
          danger: true,
          confirmMessage: this.$t('message.confirm.cancel.import.vm.task')
        },
        delete: {
          key: 'delete',
          label: this.$t('label.delete.import.vm.task'),
          icon: 'delete-outlined',
          danger: true,
          confirmMessage: this.$t('message.confirm.delete.import.vm.task')
        },
        clearcredentials: {
          key: 'clearcredentials',
          label: this.$t('label.clear.credentials'),
          icon: 'key-outlined',
          danger: true,
          confirmMessage: this.$t('message.confirm.clear.import.vm.task.credentials')
        }
      }
      availableActions.forEach(action => {
        if (definitions[action]) {
          actions.push(definitions[action])
        }
      })
      return actions
    },
    openActionConfirm (record, actionKey) {
      const action = this.getTaskActions(record).find(item => item.key === actionKey)
      if (!action) {
        return
      }
      if (action.key === 'phase2') {
        this.openPhase2Modal(record)
        return
      }
      this.actionTask = record
      this.selectedAction = action
      this.actionConfirmVisible = true
    },
    closeActionConfirm () {
      this.actionConfirmVisible = false
      this.actionConfirmLoading = false
      this.actionTask = null
      this.selectedAction = null
    },
    confirmSelectedAction () {
      if (!this.actionTask || !this.selectedAction) {
        this.closeActionConfirm()
        return
      }
      this.actionConfirmLoading = true
      const params = {
        importvmtaskid: this.actionTask.id,
        action: this.selectedAction.key
      }
      if (this.selectedAction.key === 'delete') {
        params.cleanup = true
        params.removecredentials = true
      }
      if (this.selectedAction.key === 'cancel') {
        params.removecredentials = false
      }
      if (['resume', 'retryfromstart'].includes(this.selectedAction.key)) {
        this.submitImportTaskReentryAction(this.actionTask, this.selectedAction)
        return
      }
      postAPI('executeImportVmTaskAction', params).then(() => {
        this.closeActionConfirm()
        this.$emit('fetch-import-vm-tasks')
      }).catch(error => {
        this.actionConfirmLoading = false
        this.$notifyError(error)
      })
    },
    submitImportTaskReentryAction (record, action) {
      const isN2KTask = this.isN2KTask(record)
      const importApi = isN2KTask ? 'importUnmanagedInstanceForAblestackN2K' : 'importUnmanagedInstanceForAblestackV2K'
      const params = {
        importvmtaskid: record.id,
        taskaction: action.key,
        split: record.currentphase === 'phase2' || String(record.migrationstep || record.v2kstep || '').toLowerCase().includes('phase2') ? 'phase2' : 'phase1',
        zoneid: record.zoneid,
        clusterid: record.clusterid,
        serviceofferingid: record.serviceofferingid,
        name: record.sourcevmname,
        importsource: isN2KTask ? 'nutanix' : 'VMWARE',
        hypervisor: 'KVM'
      }
      if (isN2KTask) {
        params.sourceapi = 'v3'
        params.insecure = true
      }
      postAPI(importApi, params).then(() => {
        this.closeActionConfirm()
        this.$emit('fetch-import-vm-tasks')
        window.setTimeout(() => this.$emit('fetch-import-vm-tasks'), 3000)
      }).catch(error => {
        this.actionConfirmLoading = false
        this.$notifyError(error)
      })
    },
    clearCredentials (record) {
      postAPI('executeImportVmTaskAction', {
        importvmtaskid: record.id,
        action: 'clearcredentials'
      }).then(() => {
        this.$emit('fetch-import-vm-tasks')
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    hasAction (record, action) {
      return Array.isArray(record?.availableactions) && record.availableactions.includes(action)
    },
    isN2KTask (record) {
      return record?.migrationtool === 'ablestack_n2k' || record?.sourceprovider === 'nutanix'
    },
    getMigrationToolLabel (tool) {
      if (tool === 'ablestack_n2k') return 'ABLESTACK-N2K'
      if (tool === 'ablestack_v2k') return 'ABLESTACK-V2K'
      return tool || '-'
    },
    getSourceProviderLabel (provider) {
      if (provider === 'nutanix') return this.$t('label.nutanix')
      if (provider === 'vmware') return this.$t('label.vmware')
      return provider || '-'
    },
    getCredentialStateColor (state) {
      if (state === 'stored' || state === 'managed') return 'green'
      if (state === 'missing') return 'red'
      if (state === 'legacy') return 'orange'
      return 'default'
    },
    getSyncProgressTitle (record) {
      const label = record?.syncprogresslabel || record?.displaystep || record?.migrationstep || '-'
      if (['base', 'BASE_SYNC', 'Base Sync'].includes(label)) return this.$t('label.import.step.base.sync')
      if (['incr', 'INCR_SYNC', 'Incr Sync'].includes(label)) return this.$t('label.import.step.incr.sync')
      if (['final', 'FINAL_SYNC', 'Final Sync'].includes(label)) return this.$t('label.import.step.final.sync')
      return label || '-'
    },
    getSyncProgressBytes (record) {
      const done = this.toFiniteNumber(record?.syncdonebytes)
      const total = this.toFiniteNumber(record?.synctotalbytes)
      const percent = this.getSyncProgressPercent(record)
      if (!Number.isFinite(done) || !Number.isFinite(total) || total <= 0) {
        return record?.syncphysical || '-'
      }
      return `${this.formatBytes(done)} / ${this.formatBytes(total)} (${percent}%)`
    },
    getSyncProgressCumulative (record) {
      const done = this.toFiniteNumber(record?.synccumulativedonebytes)
      const total = this.toFiniteNumber(record?.synccumulativeknownbytes)
      const percent = this.toFiniteNumber(record?.synccumulativepercent)
      if (!Number.isFinite(done) || !Number.isFinite(total) || total <= 0) {
        return this.$t('label.sync.cumulative') + ' -'
      }
      const safePercent = Number.isFinite(percent) ? Math.max(0, Math.min(100, Math.round(percent))) : Math.round(done * 100 / total)
      return `${this.$t('label.sync.cumulative')} ${this.formatBytes(done)} / ${this.formatBytes(total)} (${safePercent}%)`
    },
    getSyncProgressPercent (record) {
      const percent = this.toFiniteNumber(record?.syncpercent)
      if (Number.isFinite(percent)) {
        return Math.max(0, Math.min(100, Math.round(percent)))
      }
      const done = this.toFiniteNumber(record?.syncdonebytes)
      const total = this.toFiniteNumber(record?.synctotalbytes)
      if (!Number.isFinite(done) || !Number.isFinite(total) || total <= 0) {
        return NaN
      }
      return Math.max(0, Math.min(100, Math.round(done * 100 / total)))
    },
    toFiniteNumber (value) {
      const number = Number(value)
      return Number.isFinite(number) ? number : NaN
    },
    formatBytes (value) {
      const bytes = this.toFiniteNumber(value)
      if (!Number.isFinite(bytes) || bytes < 0) return '-'
      const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
      let unitIndex = 0
      let size = bytes
      while (size >= 1024 && unitIndex < units.length - 1) {
        size /= 1024
        unitIndex++
      }
      const fractionDigits = unitIndex === 0 ? 0 : 1
      return `${size.toFixed(fractionDigits)} ${units[unitIndex]}`
    },
    onFilterChange (e) {
      this.$emit('change-filter', e)
    },
    onPaginationChange (page, size) {
      this.$emit('change-pagination', page, size)
    }
  }
}
</script>

<style scoped lang="less">
@import url('../../style/index');

.task-event-line {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.task-event-meta {
  color: @text-color-secondary;
  font-size: 12px;
  margin-top: 4px;
}

.import-vm-tasks-header {
  align-items: center;
  display: flex;
  gap: 16px;
  justify-content: space-between;
  min-height: 32px;
  width: 100%;
}

.import-vm-tasks-title,
.import-vm-tasks-tools {
  align-items: center;
  display: flex;
}

.import-vm-tasks-title {
  gap: 6px;
  min-width: 0;
}

.import-vm-tasks-refresh {
  align-items: center;
  display: inline-flex;
  justify-content: center;
  margin-left: 8px;
}

.import-vm-tasks-filter {
  min-width: 116px;
}

.import-vm-tasks-filter :deep(.ant-select-selector) {
  align-items: center;
  display: flex;
}

.import-vm-tasks-filter :deep(.ant-select-selection-item),
.import-vm-tasks-filter :deep(.ant-select-selection-placeholder) {
  line-height: 22px;
}

.import-vm-tasks-filter :deep(.ant-select-arrow) {
  align-items: center;
  display: inline-flex;
  height: 100%;
  justify-content: center;
  margin-top: 0;
  top: 0;
}

.import-vm-tasks-table-wrap {
  overflow-x: auto;
  overflow-y: hidden;
  padding-bottom: 2px;
  scrollbar-color: fade(@text-color-secondary, 42%) fade(@border-color-base, 36%);
  scrollbar-width: thin;
}

.import-vm-tasks-table-wrap::-webkit-scrollbar,
.import-vm-tasks-table :deep(.ant-table-body)::-webkit-scrollbar,
.import-vm-tasks-table :deep(.ant-table-content)::-webkit-scrollbar {
  height: 8px;
  width: 8px;
}

.import-vm-tasks-table-wrap::-webkit-scrollbar-track,
.import-vm-tasks-table :deep(.ant-table-body)::-webkit-scrollbar-track,
.import-vm-tasks-table :deep(.ant-table-content)::-webkit-scrollbar-track {
  background: fade(@border-color-base, 28%);
  border-radius: 8px;
}

.import-vm-tasks-table-wrap::-webkit-scrollbar-thumb,
.import-vm-tasks-table :deep(.ant-table-body)::-webkit-scrollbar-thumb,
.import-vm-tasks-table :deep(.ant-table-content)::-webkit-scrollbar-thumb {
  background: fade(@text-color-secondary, 40%);
  border-radius: 8px;
}

.import-vm-tasks-table :deep(.ant-table) {
  table-layout: fixed;
}

.import-vm-tasks-table :deep(.ant-table-thead > tr > th) {
  line-height: 1.35;
  padding: 10px 8px;
  vertical-align: middle;
  white-space: normal;
  word-break: keep-all;
}

.import-vm-tasks-table :deep(.ant-table-tbody > tr > td) {
  line-height: 1.45;
  padding: 9px 8px;
  vertical-align: middle;
}

.import-vm-tasks-table :deep(.ant-table-tbody > tr > td:not(.ant-table-cell-fix-right)) {
  overflow: hidden;
}

.import-vm-task-description {
  display: -webkit-box;
  line-height: 1.45;
  max-height: 62px;
  overflow: hidden;
  white-space: normal;
  word-break: break-word;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.import-vm-task-description-tooltip {
  white-space: pre-line;
}

.import-vm-task-sync-progress {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 180px;
}

.import-vm-task-sync-title {
  color: @text-color;
  font-weight: 600;
  line-height: 1.3;
}

.import-vm-task-sync-bytes,
.import-vm-task-sync-cumulative {
  color: @text-color-secondary;
  font-size: 12px;
  line-height: 1.25;
  white-space: nowrap;
}

.import-vm-task-sync-progress :deep(.ant-progress-line) {
  font-size: 0;
  line-height: 1;
}

.import-vm-task-sync-progress :deep(.ant-progress-inner) {
  background-color: fade(@text-color-secondary, 18%);
}

.import-vm-task-actions {
  flex-wrap: nowrap;
  white-space: nowrap;
}

.import-vm-task-action-danger {
  color: @error-color;
}
</style>

<style lang="less">
@import url('../../style/index');

.import-vm-task-detail-drawer {
  .ant-drawer-body {
    background: @component-background;
  }

  .task-event-message {
    color: @text-color;
    line-height: 1.5;
    margin-top: 4px;
    word-break: break-word;
  }

  .task-event-meta {
    word-break: break-word;
  }
}

.dark-mode .import-vm-task-detail-drawer,
.dark-mode .ant-drawer.import-vm-task-detail-drawer {
  .ant-drawer-content,
  .ant-drawer-wrapper-body,
  .ant-drawer-header,
  .ant-drawer-body {
    background: #061826 !important;
    color: #d6e4ff !important;
  }

  .ant-drawer-header {
    border-bottom-color: #27445f !important;
  }

  .ant-drawer-title,
  .ant-drawer-close {
    color: #f0f5ff !important;
  }

  .ant-descriptions-view,
  .ant-descriptions-bordered .ant-descriptions-view,
  .ant-descriptions-bordered .ant-descriptions-row,
  .ant-descriptions-bordered .ant-descriptions-item-label,
  .ant-descriptions-bordered .ant-descriptions-item-content {
    border-color: #36506a !important;
  }

  .ant-descriptions-bordered .ant-descriptions-item-label,
  .ant-descriptions-item-label {
    background: #102538 !important;
    color: #f0f5ff !important;
    font-weight: 600;
  }

  .ant-descriptions-bordered .ant-descriptions-item-content,
  .ant-descriptions-item-content {
    background: #071d33 !important;
    color: #d6e4ff !important;
  }

  .ant-descriptions-item-label *,
  .ant-descriptions-item-content * {
    color: inherit !important;
  }

  .task-event-line,
  .task-event-line strong,
  .task-event-line span,
  .task-event-message,
  .ant-timeline-item-content {
    color: #d6e4ff !important;
  }

  .task-event-line {
    border-bottom: 1px solid rgba(143, 179, 217, 0.18);
    padding-bottom: 2px;
  }

  .task-event-meta,
  .ant-empty-description {
    color: #8fb3d9 !important;
  }

  .ant-divider {
    border-color: #27445f !important;
  }

  .ant-timeline-item-tail {
    border-left-color: #36506a !important;
  }

  .ant-timeline-item-head {
    background: #061826 !important;
    border-color: @primary-color !important;
  }

  .ant-btn:not(.ant-btn-primary) {
    background: #102538 !important;
    border-color: #36506a !important;
    color: #d6e4ff !important;
  }
}

.dark-mode {
  .import-vm-tasks-table-wrap {
    scrollbar-color: rgba(143, 179, 217, 0.52) rgba(54, 80, 106, 0.35);
  }

  .import-vm-tasks-table-wrap::-webkit-scrollbar-track,
  .import-vm-tasks-table .ant-table-body::-webkit-scrollbar-track,
  .import-vm-tasks-table .ant-table-content::-webkit-scrollbar-track {
    background: rgba(54, 80, 106, 0.34);
  }

  .import-vm-tasks-table-wrap::-webkit-scrollbar-thumb,
  .import-vm-tasks-table .ant-table-body::-webkit-scrollbar-thumb,
  .import-vm-tasks-table .ant-table-content::-webkit-scrollbar-thumb {
    background: rgba(143, 179, 217, 0.52);
  }

  .import-vm-tasks-table-wrap::-webkit-scrollbar-thumb:hover,
  .import-vm-tasks-table .ant-table-body::-webkit-scrollbar-thumb:hover,
  .import-vm-tasks-table .ant-table-content::-webkit-scrollbar-thumb:hover {
    background: rgba(143, 179, 217, 0.72);
  }

  .import-vm-tasks-table .ant-table-thead > tr > th,
  .import-vm-tasks-table .ant-table-cell-fix-right {
    background: @component-background;
  }

  .import-vm-task-sync-progress {
    color: #d6e4ff;
  }

  .import-vm-task-sync-title {
    color: #f0f5ff;
  }

  .import-vm-task-sync-bytes {
    color: #aac7e8;
  }

  .import-vm-task-sync-cumulative {
    color: #8fb3d9;
  }

  .import-vm-task-sync-progress .ant-progress-inner {
    background-color: rgba(143, 179, 217, 0.2);
  }

  .import-vm-task-sync-progress .ant-progress-bg {
    background-color: @primary-color;
  }
}

.import-vm-task-action-confirm-modal {
  .ant-modal-body {
    padding-top: 22px;
  }

  .import-vm-task-action-confirm-message {
    color: @text-color-secondary;
    line-height: 1.55;
    margin-bottom: 14px;
    word-break: keep-all;
  }

  .import-vm-task-action-confirm-summary {
    .ant-descriptions-view,
    .ant-descriptions-row,
    .ant-descriptions-item-label,
    .ant-descriptions-item-content {
      border-color: @border-color-base;
    }

    .ant-descriptions-item-label {
      background: @background-color-light;
      color: @heading-color;
      font-weight: 600;
      width: 48%;
    }

    .ant-descriptions-item-content {
      background: @component-background;
      color: @text-color;
      word-break: break-word;
    }
  }
}

.dark-mode .import-vm-task-action-confirm-modal {
  .ant-modal-content,
  .ant-modal-header,
  .ant-modal-footer {
    background: @dark-secondary-bgColor;
  }

  .ant-modal-header,
  .ant-modal-footer {
    border-color: @dark-border-color;
  }

  .ant-modal-title,
  .ant-modal-close,
  .ant-modal-close-x {
    color: @dark-text-color-3;
  }

  .ant-modal-close:hover,
  .ant-modal-close-x:hover {
    color: @dark-text-color-4;
  }

  .import-vm-task-action-confirm-message {
    color: @dark-text-color-2;
  }

  .import-vm-task-action-confirm-summary {
    .ant-descriptions-view,
    .ant-descriptions-row,
    .ant-descriptions-item-label,
    .ant-descriptions-item-content {
      border-color: @dark-border-color;
    }

    .ant-descriptions-item-label {
      background: fade(@dark-bgColor, 68%);
      color: @dark-text-color-4;
    }

    .ant-descriptions-item-content {
      background: fade(@dark-secondary-bgColor, 92%);
      color: @dark-text-color-3;
    }

    .ant-descriptions-item-label *,
    .ant-descriptions-item-content * {
      color: inherit;
    }
  }

  .ant-btn:not(.ant-btn-primary):not(.ant-btn-dangerous) {
    background: @dark-bgColor;
    border-color: @dark-border-color;
    color: @dark-text-color-3;
  }

  .ant-btn:not(.ant-btn-primary):not(.ant-btn-dangerous):hover,
  .ant-btn:not(.ant-btn-primary):not(.ant-btn-dangerous):focus {
    border-color: @primary-color;
    color: @dark-text-color-4;
  }
}
</style>
