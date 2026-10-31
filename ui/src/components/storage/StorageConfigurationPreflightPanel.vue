<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Licensed under the Apache License,
Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <section class="configuration-preflight" aria-live="polite">
    <a-alert
:type="report.status === 'READY' ? 'info' : 'error'"
show-icon
      :message="$t('label.storage.preflight.' + report.status.toLowerCase())" />
    <a-descriptions bordered size="small" :column="2" class="summary">
      <a-descriptions-item :label="$t('label.storage.preflight.observed')">{{ report.observedat }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.preflight.revision')">{{ report.revision }}</a-descriptions-item>
      <a-descriptions-item v-if="report.impact?.class" :label="$t('label.storage.preflight.impact')">{{ $t('label.storage.preflight.' + report.impact.class) }} · {{ report.impact.affectedcount }}</a-descriptions-item>
      <a-descriptions-item v-if="report.impact?.connections !== undefined" :label="$t('label.storage.preflight.connections')">{{ report.impact.connections }} TCP</a-descriptions-item>
    </a-descriptions>
    <div v-for="(check, index) in report.checks" :key="index" class="check">
      <a-tag :color="check.blocking ? 'red' : check.severity === 'WARNING' ? 'orange' : 'green'">{{ check.code }}</a-tag>
      <span v-if="check.message" class="check-message">{{ check.httpstatus ? 'HTTP ' + check.httpstatus + ' · ' : '' }}{{ check.message }}</span>
      <span v-if="check.availablebytes !== undefined">{{ mib(check.availablebytes) }} MiB / {{ mib(check.requiredbytes) }} MiB</span>
      <span v-if="check.connections !== undefined"> · {{ check.connections }} TCP</span>
    </div>
    <a-descriptions v-if="report.posixobservation && Object.keys(report.posixchanges || {}).length" bordered size="small" :column="1" class="summary">
      <a-descriptions-item :label="$t('label.storage.nfs.current.posix')">{{ beforePosix }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.nfs.after.posix')">{{ afterPosix }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.nfs.permission.scope')">{{ $t(report.posixchanges?.recursivePermission ? 'message.storage.nfs.recursive.scope' : 'message.storage.nfs.root.only') }}</a-descriptions-item>
    </a-descriptions>
    <section v-if="report.pathpreview" class="path-preview">
      <a-descriptions bordered size="small" :column="1" class="summary">
        <a-descriptions-item :label="$t('label.storage.path.physical')"><code>{{ report.pathpreview.physicalpath }}</code></a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.path.identity')">{{ report.pathpreview.filesystemuuid }} · {{ report.pathpreview.inode || '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.nfs.current.posix')">{{ report.pathpreview.exists ? report.pathpreview.owneruid + ':' + report.pathpreview.ownergid + ' / ' + report.pathpreview.mode : $t('label.storage.path.absent') }}</a-descriptions-item>
      </a-descriptions>
      <a-table size="small" row-key="uuid" :pagination="false" :data-source="report.pathpreview.resources || []" :columns="pathColumns">
        <template #bodyCell="{ column, record }"><span v-if="column.key === 'relation'">{{ $t('label.storage.path.' + record.relation.toLowerCase()) }}</span></template>
      </a-table>
      <a-alert v-if="report.pathpreview.parentaccesswarning" type="warning" show-icon :message="$t('message.storage.path.parent.access')" />
    </section>
    <a-alert v-if="report.impact?.ackrequired" type="warning" show-icon :message="$t(report.impact?.class === 'ONLINE_RELOAD' ? 'message.storage.creation.online' : 'message.storage.preflight.interruption')" />
    <a-checkbox
v-if="report.impact?.ackrequired"
:checked="acknowledged"
:disabled="report.status !== 'READY'"
      class="ack"
@change="$emit('acknowledge', $event.target.checked)">{{ $t(report.impact?.class === 'ONLINE_RELOAD' ? 'message.storage.creation.ack' : 'message.storage.preflight.acknowledge') }}</a-checkbox>
    <p>{{ $t('message.storage.preflight.not.cancel') }}</p>
    <a-button :loading="loading" @click="$emit('refresh')"><template #icon><ReloadOutlined /></template>{{ $t('label.storage.preflight.check') }}</a-button>
  </section>
</template>
<script>
import { ReloadOutlined } from '@ant-design/icons-vue'
export default {
  name: 'StorageConfigurationPreflightPanel',
  components: { ReloadOutlined },
  props: { report: { type: Object, required: true }, loading: Boolean, acknowledged: Boolean },
  emits: ['refresh', 'acknowledge'],
  computed: {
    pathColumns () {
      return [
        { title: this.$t('label.name'), dataIndex: 'name', key: 'name' },
        { title: this.$t('label.protocol'), dataIndex: 'protocol', key: 'protocol' },
        { title: this.$t('label.storage.path.relative'), dataIndex: 'relativepath', key: 'relativepath' },
        { title: this.$t('label.storage.path.relation'), dataIndex: 'relation', key: 'relation' }
      ]
    },
    beforePosix () {
      const p = this.report.posixobservation || {}
      return p.status === 'OBSERVED' ? `${p.owneruid}:${p.ownergid} / ${p.mode}` : this.$t('label.storage.nfs.posix.unavailable')
    },
    afterPosix () {
      const p = this.report.posixobservation || {}; const c = this.report.posixchanges || {}
      return `${c.ownerUid ?? p.owneruid ?? '-'}:${c.ownerGid ?? p.ownergid ?? '-'} / ${c.mode || p.mode || '-'}`
    }
  },
  methods: { mib (value) { return Math.ceil(Number(value || 0) / 1048576) } }
}
</script>
<style scoped>
.configuration-preflight { margin-bottom: 24px; color: var(--text-color); }
.summary, .check, .ack { margin-top: 12px; }
.check-message { overflow-wrap: anywhere; white-space: normal; }
.path-preview { margin-top: 16px; display: grid; gap: 12px; }
.path-preview code { overflow-wrap: anywhere; white-space: normal; }
p { margin-top: 12px; color: var(--text-color-secondary); }
</style>
