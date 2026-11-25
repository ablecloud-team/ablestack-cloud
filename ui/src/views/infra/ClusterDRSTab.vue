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
  <div class="cluster-drs-tab">
    <resource-table-toolbar
      :loading="historyLoading || listRefreshing > 0"
      :refreshable="canList"
      @refresh="fetchDRSPlans">
      <div class="cluster-drs-limit">
        <label for="cluster-drs-limit">{{ $t('label.max.migrations') }}</label>
        <a-input-number
          id="cluster-drs-limit"
          v-model:value="maxMigrations"
          :min="1"
          :step="1"
          :disabled="configLoading || planning || executing" />
      </div>
      <a-button
        ref="generateButton"
        type="primary"
        :loading="planning"
        :disabled="!canGenerate || configLoading || planning || executing"
        @click="generateDrsPlan">
        {{ $t('label.drs.generate.plan') }}
      </a-button>
    </resource-table-toolbar>
    <p class="cluster-drs-note">
      <template v-if="algorithm"><strong>{{ $t('label.algorithm') }}</strong> {{ algorithm }} · </template>
      {{ $t('message.drs.plan.description') }}
    </p>
    <a-alert v-if="generationError" type="error" show-icon :message="$t(generationError)" />
    <a-alert v-if="listRefreshFailed" type="error" show-icon :message="$t('message.drs.history.load.failed')" />
    <a-table
      class="cluster-drs-history"
      size="small"
      :columns="historyColumns"
      :data-source="drsPlans"
      row-key="id"
      :loading="historyLoading"
      :pagination="historyPagination"
      :scroll="{ x: 720 }"
      @change="changeHistoryPage">
      <template #bodyCell="{ column, text, record }">
        <template v-if="column.key === 'successfulMigrations'">
          {{ successfulMigrations(record) }} / {{ (record.migrations || []).length }}
        </template>
        <template v-else-if="column.key === 'type'">
          {{ drsValue('type', text) }}
        </template>
        <template v-else-if="column.key === 'status'">
          {{ drsValue('status', text) }}
        </template>
        <template v-else-if="column.key === 'created'">
          {{ text ? $toLocaleDate(text) : '—' }}
        </template>
        <template v-else-if="column.key === 'eventid'">
          <router-link v-if="text && $store.getters.apis.listEvents" :to="{ path: '/event', query: { startid: text } }" target="_blank" rel="noopener">
            <schedule-outlined /> {{ $t('label.events') }}
          </router-link>
          <span v-else>—</span>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button size="small" aria-haspopup="dialog" :disabled="planning || executing" @click="openHistory(record, $event)">
            {{ $t('label.drs.plan.details') }}
          </a-button>
        </template>
      </template>
    </a-table>

    <mold-dialog
      :visible="showModal"
      :title="$t(selectedPlan ? 'label.drs.plan.details' : 'label.drs.plan')"
      size="lg"
      focus-guard
      initial-focus=".cluster-drs-dismiss"
      :closable="!executing"
      :keyboard="!executing"
      :after-close="restoreDialogFocus"
      @cancel="closeModal">
      <a-alert v-if="executionError" type="error" show-icon :message="$t(executionError)" />
      <dl v-if="selectedPlan" class="cluster-drs-plan-summary">
        <div><dt>{{ $t('label.type') }}</dt><dd>{{ drsValue('type', selectedPlan.type) }}</dd></div>
        <div><dt>{{ $t('label.status') }}</dt><dd>{{ drsValue('status', selectedPlan.status) }}</dd></div>
        <div><dt>{{ $t('label.created') }}</dt><dd>{{ selectedPlan.created ? $toLocaleDate(selectedPlan.created) : '—' }}</dd></div>
      </dl>
      <a-table
        v-if="selectedPlan || displayedMigrations.length"
        class="cluster-drs-migrations"
        size="small"
        :columns="migrationColumns"
        :data-source="displayedMigrations"
        :row-key="(record, index) => index"
        :pagination="{ hideOnSinglePage: true, showSizeChanger: true }"
        :scroll="{ x: selectedPlan ? 680 : 560 }" >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'vm'">
            <router-link v-if="record.virtualmachineid && $store.getters.apis.listVirtualMachines" :to="{ path: '/vm/' + record.virtualmachineid }">
              <desktop-outlined /> {{ record.virtualmachinename || record.virtualmachineid }}
            </router-link>
            <span v-else>{{ record.virtualmachinename || record.virtualmachineid || '—' }}</span>
          </template>
          <template v-else-if="column.key === 'sourcehost'">
            <router-link v-if="record.sourcehostid && $store.getters.apis.listHostsMetrics" :to="{ path: '/host/' + record.sourcehostid }">
              <cluster-outlined /> {{ record.sourcehostname || record.sourcehostid }}
            </router-link>
            <span v-else>{{ record.sourcehostname || record.sourcehostid || '—' }}</span>
          </template>
          <template v-else-if="column.key === 'destinationhost'">
            <router-link v-if="record.destinationhostid && $store.getters.apis.listHostsMetrics" :to="{ path: '/host/' + record.destinationhostid }">
              <cluster-outlined /> {{ record.destinationhostname || record.destinationhostid }}
            </router-link>
            <span v-else>{{ record.destinationhostname || record.destinationhostid || '—' }}</span>
          </template>
          <template v-else-if="column.key === 'migrationjobstatus'">
            {{ drsValue('migration', migrationStatus(record)) }}
          </template>
        </template>
      </a-table>
      <p v-else>{{ $t('label.drs.no.plan.generated') }}</p>
      <template #footer>
        <a-button class="cluster-drs-dismiss" :disabled="executing" @click="closeModal">
          {{ $t(selectedPlan ? 'label.close' : 'label.cancel') }}
        </a-button>
        <a-button
          v-if="!selectedPlan && canExecute && generatedMigrations.length"
          type="primary"
          :loading="executing"
          :disabled="executing"
          @click="executeDrsPlan">
          {{ $t('label.execute') }}
        </a-button>
      </template>
    </mold-dialog>
  </div>
</template>

<script>
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import MoldDialog from '@/components/view/MoldDialog'
import ResourceTableToolbar from '@/components/view/ResourceTableToolbar'
import { getAPI, postAPI } from '@/api'

const historyPageSizes = [10, 20, 50, 100]
const drsLabels = {
  type: { automated: 'label.automatic', manual: 'label.drs.type.manual' },
  status: { under_review: 'label.drs.status.under.review', ready: 'state.ready', in_progress: 'state.inprogress', completed: 'state.completed' },
  migration: { succeeded: 'label.success', failed: 'state.failed', in_progress: 'state.inprogress', cancelled: 'state.canceled' }
}

export default {
  mixins: [listRefreshMixin(['fetchDRSPlans'])],
  name: 'ClusterDrsTab',
  components: { MoldDialog, ResourceTableToolbar },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      historyCount: 0,
      historyLoading: false,
      selectedPlan: null,
      dialogTrigger: null,
      configLoading: false,
      planning: false,
      executing: false,
      generationError: '',
      executionError: '',
      viewVersion: 0,
      planClusterId: '',
      drsPlans: [],
      algorithm: '',
      maxMigrations: undefined,
      generatedMigrations: [],
      showModal: false
    }
  },
  computed: {
    canList () { return Boolean(this.$store.getters.apis?.listClusterDrsPlan) },
    canGenerate () { return Boolean(this.$store.getters.apis?.generateClusterDrsPlan) },
    canExecute () { return Boolean(this.$store.getters.apis?.executeClusterDrsPlan) },
    historyPage () {
      const page = Number(this.$route.query.drspage)
      return Number.isSafeInteger(page) && page > 0 ? page : 1
    },
    historyPageSize () {
      const size = Number(this.$route.query.drspagesize)
      return historyPageSizes.includes(size) ? size : historyPageSizes[0]
    },
    historyPageQuery () { return this.historyPage + ':' + this.historyPageSize },
    historyPagination () {
      return {
        current: this.historyPage,
        pageSize: this.historyPageSize,
        total: this.historyCount,
        pageSizeOptions: historyPageSizes.map(String),
        hideOnSinglePage: true,
        showSizeChanger: true
      }
    },
    historyColumns () {
      return [
        { title: this.$t('label.type'), key: 'type', dataIndex: 'type', width: 96 },
        { title: this.$t('label.success.migrations'), key: 'successfulMigrations', width: 134 },
        { title: this.$t('label.status'), key: 'status', dataIndex: 'status', width: 110 },
        { title: this.$t('label.created'), key: 'created', dataIndex: 'created', width: 158 },
        { title: this.$t('label.events'), key: 'eventid', dataIndex: 'eventid', width: 102 },
        { title: this.$t('label.actions'), key: 'actions', width: 116, fixed: 'right' }
      ]
    },
    migrationColumns () {
      const columns = [
        { key: 'vm', title: this.$t('label.vm'), width: 200 },
        { key: 'sourcehost', title: this.$t('label.sourcehost'), width: 180 },
        { key: 'destinationhost', title: this.$t('label.desthost'), width: 180 }
      ]
      if (this.selectedPlan) columns.push({ key: 'migrationjobstatus', title: this.$t('label.status'), width: 120 })
      return columns
    },
    displayedMigrations () {
      return this.selectedPlan ? this.selectedPlan.migrations || [] : this.generatedMigrations
    }
  },
  watch: {
    historyPageQuery () { this.fetchDRSPlans() },
    'resource.id' (id, previous) {
      if (id === previous) return
      this.viewVersion++
      this.planning = false
      this.executing = false
      this.configLoading = false
      this.generationError = ''
      this.algorithm = ''
      this.maxMigrations = undefined
      this.drsPlans = []
      this.historyCount = 0
      this.historyLoading = false
      this.listRefreshFailed = false
      this.closeModal()
      this.clearDialog()
      this.fetchDRSPlans()
      this.fetchDrsConfig()
    }
  },
  created () {
    this.fetchDRSPlans()
    this.fetchDrsConfig()
  },
  beforeUnmount () {
    this.viewVersion++
  },
  methods: {
    isCurrentCluster (id, version) {
      const routeId = this.$route.params?.id
      return this.viewVersion === version && this.resource.id === id && (!routeId || routeId === id)
    },
    async fetchDRSPlans () {
      const listRequest = this.listRequestToken('fetchDRSPlans')
      const clusterid = this.resource.id
      const version = this.viewVersion
      if (!clusterid || !this.canList || !this.isCurrentCluster(clusterid, version)) return

      const page = this.historyPage
      const pagesize = this.historyPageSize
      this.historyLoading = !listRequest.loaded
      if (!listRequest.loaded) {
        this.drsPlans = []
        this.listRefreshFailed = false
      }
      const current = () => this.isCurrentCluster(clusterid, version) && this.isListRequestCurrent('fetchDRSPlans', listRequest)
      try {
        const response = await getAPI('listClusterDrsPlan', { page, pagesize, clusterid }, { preserveOnFailure: true })
        if (!current()) return
        const result = response.listclusterdrsplanresponse || {}
        const rows = result.drsPlan || []
        const count = Number(result.count)
        this.historyCount = Number.isSafeInteger(count) && count >= 0 ? count : rows.length
        const lastPage = Math.max(1, Math.ceil(this.historyCount / pagesize))
        if (page > lastPage) {
          this.changeHistoryPage({ current: lastPage, pageSize: pagesize })
          return
        }
        this.drsPlans = rows
      } catch {
        if (!current()) return
        listRequest.failed = true
        this.listRefreshFailed = true
      } finally {
        if (current()) this.historyLoading = false
      }
    },
    changeHistoryPage ({ current, pageSize }) {
      if (current === this.historyPage && pageSize === this.historyPageSize) return
      this.$router.replace({ query: { ...this.$route.query, tab: 'drs', drspage: String(current), drspagesize: String(pageSize) } })
    },
    drsValue (kind, value) {
      if (!value) return '—'
      const label = drsLabels[kind][String(value).toLowerCase()]
      return label ? this.$t(label) : value
    },
    migrationStatus (record) { return record.migrationjobstatus ?? record.jobstatus },
    successfulMigrations (plan) {
      return (plan.migrations || []).filter(row => this.migrationStatus(row) === 'SUCCEEDED').length
    },
    openHistory (plan, event) {
      if (this.planning || this.executing || this.showModal || !this.isCurrentCluster(this.resource.id, this.viewVersion)) return
      this.selectedPlan = plan
      this.dialogTrigger = event.currentTarget
      this.showModal = true
    },
    async executeDrsPlan () {
      const id = this.resource.id
      const version = this.viewVersion
      if (this.selectedPlan || this.executing || !this.canExecute || !this.generatedMigrations.length || this.planClusterId !== id || !this.isCurrentCluster(id, version)) return

      this.executing = true
      this.executionError = ''
      const params = { id }
      this.generatedMigrations.forEach((mapping, index) => {
        params['migrateto[' + index + '].vm'] = mapping.virtualmachineid
        params['migrateto[' + index + '].host'] = mapping.destinationhostid
      })

      try {
        await postAPI('executeClusterDrsPlan', params, { preserveOnFailure: true })
        if (!this.isCurrentCluster(id, version)) return
        this.$message.success(this.$t('message.drs.plan.executed'))
        this.executing = false
        this.closeModal()
        await this.fetchDRSPlans()
      } catch {
        if (this.isCurrentCluster(id, version)) this.executionError = 'message.drs.plan.execution.failed'
      } finally {
        if (this.isCurrentCluster(id, version)) this.executing = false
      }
    },
    async generateDrsPlan () {
      const id = this.resource.id
      const version = this.viewVersion
      if (!id || this.showModal || this.planning || this.executing || this.configLoading || !this.canGenerate || !this.isCurrentCluster(id, version)) return

      this.generationError = ''
      const migrations = this.maxMigrations == null ? undefined : Number(this.maxMigrations)
      if (migrations !== undefined && (!Number.isInteger(migrations) || migrations < 1)) {
        this.generationError = 'message.drs.plan.invalid.limit'
        return
      }
      this.planning = true
      try {
        const response = await postAPI('generateClusterDrsPlan', { id, migrations }, { preserveOnFailure: true })
        if (!this.isCurrentCluster(id, version)) return
        const envelope = response.generateclusterdrsplanresponse || {}
        const entries = (envelope.generateclusterdrsplanresponse || envelope).migrations
        this.generatedMigrations = Array.isArray(entries) ? entries : []
        this.planClusterId = id
        this.executionError = ''
        this.dialogTrigger = this.$refs.generateButton?.$el
        this.showModal = true
      } catch {
        if (this.isCurrentCluster(id, version)) this.generationError = 'message.drs.plan.generation.failed'
      } finally {
        if (this.isCurrentCluster(id, version)) this.planning = false
      }
    },
    async fetchDrsConfig () {
      const clusterid = this.resource.id
      const version = this.viewVersion
      if (!clusterid || !this.$store.getters.apis?.listConfigurations) return
      this.configLoading = true
      try {
        const [algorithm, limit] = await Promise.all(
          ['drs.algorithm', 'drs.max.migrations'].map(name =>
            getAPI('listConfigurations', { clusterid, name }, { preserveOnFailure: true })
              .then(response => response.listconfigurationsresponse?.configuration?.[0]?.value))
        )
        if (!this.isCurrentCluster(clusterid, version)) return
        this.algorithm = algorithm || ''
        const max = Number(limit)
        this.maxMigrations = Number.isInteger(max) && max > 0 ? max : undefined
      } catch (error) {
        if (this.isCurrentCluster(clusterid, version)) this.$notifyError(error)
      } finally {
        if (this.isCurrentCluster(clusterid, version)) this.configLoading = false
      }
    },
    closeModal () {
      if (!this.executing) this.showModal = false
    },
    clearDialog () {
      this.selectedPlan = null
      this.generatedMigrations = []
      this.planClusterId = ''
      this.executionError = ''
      this.dialogTrigger = null
    },
    restoreDialogFocus () {
      if (this.showModal) return
      const trigger = this.dialogTrigger
      this.clearDialog()
      if (trigger?.isConnected && this.$route.params.id === this.resource.id && trigger.getClientRects().length) {
        trigger.focus({ preventScroll: true })
      }
    }
  }
}
</script>

<style scoped>
.cluster-drs-tab { min-width: 0; }
.cluster-drs-limit { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.cluster-drs-limit :deep(.ant-input-number) { width: 86px; }
.cluster-drs-note { color: var(--ui-text-secondary); margin: 0 0 12px; }
.cluster-drs-tab :deep(.ant-alert) { margin-bottom: 12px; }
.cluster-drs-history, .cluster-drs-migrations { min-width: 0; max-width: 100%; }
.cluster-drs-plan-summary { display: flex; flex-wrap: wrap; gap: 8px 24px; padding-bottom: 8px; }
.cluster-drs-plan-summary dt { font-weight: 600; }
.cluster-drs-plan-summary dd { margin-bottom: 0; }
</style>
