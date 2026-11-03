<!-- Licensed under the Apache License, Version 2.0. See NOTICE. -->
<template>
  <div class="sharedfs-boot-recovery">
    <a-space class="boot-toolbar" wrap>
      <a-button type="primary" :disabled="!resumeRequest || unknown || guarded || busy" @click="dialog = 'resume'"><PlayCircleOutlined />{{ $t('label.storage.boot.resume') }}</a-button>
      <a-button :loading="loading" @click="$emit('refresh')"><ReloadOutlined />{{ $t('label.refresh') }}</a-button>
      <a-button :disabled="!value" @click="dialog = 'diagnostic'"><FileSearchOutlined />{{ $t('label.storage.boot.diagnostic') }}</a-button>
    </a-space>
    <a-alert show-icon :type="unknown ? 'info' : state === 'READY' ? 'success' : state === 'BLOCKED' ? 'error' : 'warning'" :message="stateLabel(state)" :description="$t('message.storage.boot.preserve')" />
    <a-descriptions class="boot-section" bordered :column="1" size="small">
      <a-descriptions-item :label="$t('label.storage.boot.phase')">{{ value?.phase || '—' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.boot.identity')">{{ value?.bootId || '—' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.boot.observed')">{{ value?.observedAt || '—' }} <span v-if="unknown">{{ $t('label.storage.boot.previous') }}</span></a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.boot.budget')">{{ value?.elapsedSeconds ?? '—' }} / {{ value?.budgetSeconds ?? '—' }} s</a-descriptions-item>
    </a-descriptions>
    <a-table class="boot-section" :columns="protocolColumns" :data-source="protocols" row-key="protocol" size="small" :pagination="false" :scroll="{ x: 800 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'">{{ stateLabel(record.state) }}</template>
        <template v-else-if="column.key === 'progress'">{{ record.completed ?? '—' }} / {{ record.total ?? '—' }} <span v-if="record.externalClientRequired"> · {{ $t('label.storage.boot.external') }} {{ record.externalClientRequired }}</span></template>
      </template>
    </a-table>
    <div class="boot-section boot-search"><a-input-search v-model:value="search" allow-clear :placeholder="$t('label.storage.boot.search')" /><span>{{ $t('message.storage.boot.external') }}</span></div>
    <a-table class="boot-section" :columns="exportColumns" :data-source="exports" :row-key="rowKey" size="small" :pagination="{ pageSize: 10 }" :scroll="{ x: 1000 }">
      <template #bodyCell="{column,record}"><template v-if="column.key === 'state'">{{ stateLabel(record.state) }}</template></template>
    </a-table>
    <MoldDialog v-if="dialog" :visible="true" :title="$t(dialog === 'resume' ? 'label.storage.boot.resume' : 'label.storage.boot.diagnostic')" size="lg" form-actions @cancel="!busy && (dialog = '')">
      <a-alert show-icon type="info" :message="$t('message.storage.boot.preserve')" />
      <a-descriptions class="boot-section" bordered :column="1" size="small">
        <a-descriptions-item :label="$t('label.storage.boot.phase')">{{ value?.phase || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.identity')">{{ value?.bootId || '—' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.first.failure')">{{ failure(value?.firstFailure) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.last.failure')">{{ failure(value?.lastFailure) }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.boot.cleanup')">{{ value?.cleanup?.state || '—' }}</a-descriptions-item>
      </a-descriptions>
      <p class="boot-hint">{{ $t('message.storage.boot.resume') }}</p>
      <a-alert v-if="error" class="boot-section" type="error" show-icon :message="error" />
      <MoldDialogActions :busy="busy">
        <a-button :disabled="busy" @click="dialog = ''">{{ $t(dialog === 'resume' ? 'label.cancel' : 'label.close') }}</a-button>
        <a-button v-if="dialog === 'resume'" type="primary" :loading="busy" :disabled="!resumeRequest || unknown || guarded" @click="resume">{{ $t('label.ok') }}</a-button>
      </MoldDialogActions>
    </MoldDialog>
  </div>
</template>
<script>
import { postAPI } from '@/api'
import { PlayCircleOutlined, ReloadOutlined, FileSearchOutlined } from '@ant-design/icons-vue'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { bootRecoveryObservation, bootResumeRequest } from '@/utils/sharedfsBootRecovery'
export default {
  name: 'SharedFSBootRecoveryTab',
  components: { PlayCircleOutlined, ReloadOutlined, FileSearchOutlined, MoldDialog, MoldDialogActions },
  props: {
    instance: { type: Object, default: null },
    inventory: { type: Array, default: () => [] },
    unavailable: { type: Boolean, default: false },
    guarded: { type: Boolean, default: false },
    loading: { type: Boolean, default: false }
  },
  emits: ['refresh'],
  data () { return { dialog: '', busy: false, error: '', search: '', lastValue: null, timer: null, epoch: 0 } },
  computed: {
    observation () { return bootRecoveryObservation(this.inventory, this.unavailable) },
    value () { return this.observation.value || this.lastValue },
    unknown () { return this.observation.unknown },
    state () { return this.unknown ? 'UNKNOWN' : this.value?.state || 'UNKNOWN' },
    resumeRequest () { return bootResumeRequest(this.instance, this.observation.value) },
    protocols () { return Object.entries(this.value?.protocols || {}).map(([protocol, state]) => ({ protocol, ...state })) },
    exports () { return (this.value?.exports || []).filter(row => !this.search || JSON.stringify(row).toLowerCase().includes(this.search.toLowerCase())) },
    protocolColumns () {
      return [['protocol', 'label.protocol', 110], ['state', 'label.state', 160], ['phase', 'label.storage.boot.phase', 200], ['progress', 'label.storage.boot.progress', 330]]
        .map(([key, title, width]) => ({ key, dataIndex: key, title: this.$t(title), width }))
    },
    exportColumns () {
      return [['exportName', 'label.name', 200], ['port', 'label.port', 90], ['version', 'label.version', 90], ['state', 'label.state', 180], ['code', 'label.storage.boot.reason', 240], ['observedAt', 'label.storage.boot.observed', 200]]
        .map(([key, title, width]) => ({ key, dataIndex: key, title: this.$t(title), width }))
    }
  },
  watch: {
    observation: {
      immediate: true,
      handler (value) {
        if (value.value && !value.unknown) this.lastValue = value.value
        this.schedule()
      }
    },
    'instance.id' () { this.epoch++; this.lastValue = null; this.dialog = ''; this.error = ''; this.busy = false }
  },
  beforeUnmount () { this.epoch++; clearTimeout(this.timer) },
  methods: {
    stateLabel (state) { return this.$t('label.storage.boot.state.' + String(state || 'UNKNOWN').toLowerCase()) },
    rowKey (row) { return [row.exportUuid, row.port, row.version || 'external'].join(':') },
    failure (value) { return value ? [value.phase, value.code, value.exportUuid, value.port, value.version, value.observedAt].filter(x => x !== null && x !== undefined).join(' · ') : '—' },
    schedule () {
      clearTimeout(this.timer)
      if (!this.unknown && ['STARTING', 'RUNNING'].includes(this.value?.state)) this.timer = setTimeout(() => this.$emit('refresh'), 3000)
    },
    async resume () {
      const request = this.resumeRequest
      if (!request || this.busy || this.unknown || this.guarded) return
      const epoch = this.epoch
      this.busy = true; this.error = ''
      try {
        const wire = await postAPI('resumeStorageServiceBootRecovery', request)
        const result = wire.resumestorageservicebootrecoveryresponse || {}
        // Async request acknowledgement is not recovery completion. Observe the
        // same boot epoch through the ordinary inventory GET; never resubmit.
        const jobid = result.jobid || result.storageserviceruntime?.jobid
        if (!jobid) throw new Error(this.$t('message.storage.boot.unavailable'))
        await new Promise((resolve, reject) => {
          this.$pollJob({
            jobId: jobid,
            title: this.$t('label.storage.boot.resume'),
            showLoading: false,
            successMethod: resolve,
            errorMethod: reject,
            catchMethod: reject
          })
        })
        if (epoch === this.epoch) { this.dialog = ''; this.$emit('refresh') }
      } catch (error) {
        if (epoch === this.epoch) this.error = error?.response?.data?.errorresponse?.errortext || error.message || this.$t('message.storage.boot.unavailable')
      } finally { if (epoch === this.epoch) this.busy = false }
    }
  }
}
</script>
<style scoped>
.sharedfs-boot-recovery { color: var(--ui-text-primary); }
.boot-toolbar { margin-bottom: 16px; }
.boot-section { margin-top: 16px; }
.boot-search { display: flex; align-items: center; gap: 16px; justify-content: space-between; }
.boot-search .ant-input-search { max-width: 300px; }
.boot-search > span, .boot-hint { color: var(--ui-text-secondary); font-size: 12px; line-height: 1.8; }
.boot-hint { margin: 16px 0 0; }
</style>
