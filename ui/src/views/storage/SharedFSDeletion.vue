<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <div class="sharedfs-deletion">
    <a-alert v-if="groupTargets.length > 1" type="info" show-icon :message="$t('message.sharedfs.delete.group') + ' (' + (groupIndex + 1) + ' / ' + groupTargets.length + ') · ' + target.name" class="section" />
    <a-alert v-if="error" type="error" show-icon :message="error" class="section" />
    <a-spin :spinning="loading">
      <template v-if="showPlan">
        <a-button :disabled="submitting" @click="reloadPlan"><ReloadOutlined />{{ $t('label.refresh') }}</a-button>
        <a-alert v-if="impact?.supported === false" type="error" show-icon message="PROVIDER_DATA_PRESERVATION_UNSUPPORTED" />
        <a-alert type="info" show-icon :message="$t('label.sharedfs.delete.preserve')" :description="$t('message.sharedfs.delete.preserve')" />
        <a-form layout="vertical" class="section">
          <a-form-item :label="$t('label.sharedfs.delete.policy')">
            <a-radio-group v-model:value="policy" :disabled="frozen" @change="clearApproval">
              <a-radio value="PRESERVE">{{ $t('label.sharedfs.delete.preserve') }}</a-radio>
              <a-radio value="DELETE_SELECTED">{{ $t('label.sharedfs.delete.selected') }}</a-radio>
            </a-radio-group>
          </a-form-item>
          <a-form-item :label="$t('label.expunge')">
            <a-switch v-model:checked="expunge" :disabled="expungeAction" @change="clearApproval" />
            <p class="hint">{{ $t(expunge ? 'message.sharedfs.delete.immediate' : 'message.sharedfs.delete.delayed') }}</p>
          </a-form-item>
        </a-form>
        <a-alert v-if="policy === 'DELETE_SELECTED'" type="error" show-icon :message="$t('message.sharedfs.delete.loss')" />
        <a-alert v-if="impact?.blocked || (expunge && impact?.rootdeleteprotected)" type="error" show-icon :message="impact.blocked ? 'VOLUME_ATTACHED_ELSEWHERE' : 'ROOT_DELETE_PROTECTED'" />
        <a-table class="section" size="small" :columns="columns" :data-source="volumes" row-key="uuid" :pagination="false" :scroll="{ x: 900 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'volume'"><strong>{{ record.name }}</strong><div class="uuid">{{ record.uuid }}</div></template>
            <template v-else-if="column.key === 'size'">{{ record.size == null ? '—' : $bytesToHumanReadableSize(Number(record.size)) }}</template>
            <template v-else-if="column.key === 'origin'">{{ $t('label.sharedfs.delete.origin.' + record.origin) }}</template>
            <template v-else-if="column.key === 'references'">{{ (record.references || []).map(x => x.protocol + ' · ' + x.name).join(', ') || '—' }}</template>
            <template v-else-if="column.key === 'protection'"><a-tag v-if="record.deleteprotection">{{ $t('label.delete.protection') }}</a-tag><a-tag v-if="record.shared || record.externalrefs">{{ $t('label.sharedfs.delete.shared') }}</a-tag></template>
            <template v-else-if="column.key === 'action'">
              <a-tag v-if="record.type === 'ROOT'">{{ $t('label.sharedfs.delete.root') }}</a-tag>
              <a-checkbox v-else-if="policy === 'DELETE_SELECTED'" :checked="selected.includes(record.uuid)" :disabled="frozen || !record.deletable" @change="select(record.uuid, $event.target.checked)">{{ $t('label.sharedfs.delete.select') }}</a-checkbox>
              <a-tag v-else color="blue">{{ $t('label.sharedfs.delete.preserve') }}</a-tag>
            </template>
          </template>
        </a-table>
        <a-alert class="section" type="warning" show-icon :message="$t('message.sharedfs.delete.stop')" />
        <a-checkbox class="section" v-model:checked="stopConfirmed">{{ $t('message.sharedfs.delete.stop.confirm') }}</a-checkbox>
        <a-form layout="vertical" class="section">
          <a-form-item :label="$t('label.sharedfs.delete.confirm.name')"><a-input v-model:value="confirmName" :placeholder="target.name" /></a-form-item>
          <a-checkbox v-if="policy === 'DELETE_SELECTED'" v-model:checked="lossConfirmed">{{ $t('message.sharedfs.delete.loss.confirm') }} ({{ selected.length }})</a-checkbox>
        </a-form>
      </template>
      <template v-else>
        <a-alert :type="operation.state === 'COMPLETED' ? 'success' : operation.state === 'RECOVERY_REQUIRED' ? 'warning' : 'info'" show-icon :message="$t('label.sharedfs.delete.state.' + (operation.state === 'RUNNING' && operation.workeractive === false ? 'OBSERVATION_PENDING' : operation.state))" />
        <a-descriptions class="section" bordered size="small" :column="1">
          <a-descriptions-item :label="$t('label.id')">{{ operation.operationid }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.sharedfs.delete.request')">{{ operation.requestid }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.sharedfs.delete.phase')">{{ operation.phase }}</a-descriptions-item>
          <a-descriptions-item v-if="operation.originalfailure" :label="$t('label.sharedfs.delete.failure')">{{ operation.originalfailure.code }} · {{ operation.originalfailure.phase }} · {{ operation.originalfailure.volumeuuid }}</a-descriptions-item>
        </a-descriptions>
        <a-table class="section" size="small" :columns="resultColumns" :data-source="resultRows" row-key="uuid" :pagination="false" />
        <p class="hint">{{ $t('message.sharedfs.delete.resume') }}</p>
      </template>
    </a-spin>
    <MoldDialogActions>
      <a-button @click="close">{{ $t(operation ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button v-if="operation?.state === 'REJECTED'" type="primary" @click="reloadPlan">{{ $t('label.sharedfs.delete.reapprove') }}</a-button>
      <a-button v-if="operation?.canresume" :disabled="submitting" type="primary" :loading="submitting" @click="resume">{{ $t('label.sharedfs.delete.resume') }}</a-button>
      <a-button v-else-if="showPlan" type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">{{ $t(expunge ? 'label.expunge.sharedfs' : 'label.destroy.sharedfs') }}</a-button>
      <a-button v-if="groupTargets.length > groupIndex + 1 && ['COMPLETED', 'SOFT_DESTROYED'].includes(operation?.state)" type="primary" @click="nextTarget">{{ $t('label.sharedfs.delete.next') }}</a-button>
      <a-button v-if="operation" @click="observe"><ReloadOutlined />{{ $t('label.refresh') }}</a-button>
    </MoldDialogActions>
  </div>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { v4 as uuid } from 'uuid'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ReloadOutlined } from '@ant-design/icons-vue'
const apiError = e => { const body = e.response?.data || {}; return body.errortext || body.errorresponse?.errortext || Object.values(body).find(x => x?.errortext)?.errortext || e.message }
const unpack = json => { const root = Object.values(json || {})[0]; return root?.sharedfsdeletion?.result || root?.result }
export default {
  name: 'SharedFSDeletion',
  components: { MoldDialogActions, ReloadOutlined },
  props: { historyMode: { type: Boolean, default: false }, initialOperation: { type: Object, default: null }, selectedItems: { type: Array, default: () => [] }, resource: { type: Object, required: true }, currentAction: { type: Object, default: () => ({}) } },
  emits: ['close-action', 'refresh-data'],
  data () {
    return { closed: false, groupTargets: this.selectedItems.map(x => ({ id: x.id, name: x.name, state: x.state })), groupIndex: 0, loading: false, submitting: false, error: '', impact: null, operation: this.initialOperation ? JSON.parse(JSON.stringify(this.initialOperation)) : null, policy: 'PRESERVE', selected: [], expunge: false, stopConfirmed: false, lossConfirmed: false, confirmName: '', requestId: '', jobId: null, timer: null, generation: 0 }
  },
  computed: {
    target () { return this.groupTargets[this.groupIndex] || this.resource },
    showPlan () { return !this.operation || (this.operation.state === 'SOFT_DESTROYED' && this.expungeAction) },
    expungeAction () { return this.currentAction.api === 'expungeSharedFileSystem' },
    frozen () { return this.operation?.state === 'SOFT_DESTROYED' },
    volumes () { return this.operation?.plan?.volumes || this.impact?.volumes || [] },
    canSubmit () { return this.showPlan && !this.loading && !this.submitting && !!this.impact && this.impact.supported !== false && !this.impact.blocked && !(this.expunge && this.impact.rootdeleteprotected) && this.confirmName === this.target.name && this.stopConfirmed && (this.policy === 'PRESERVE' || (this.selected.length > 0 && this.lossConfirmed)) },
    columns () {
      return [{ title: this.$t('label.volume'), key: 'volume', width: 240 }, { title: this.$t('label.type'), dataIndex: 'type', width: 100 }, { title: this.$t('label.sharedfs.delete.origin'), dataIndex: 'origin', key: 'origin', width: 180 }, { title: this.$t('label.provisioningtype'), dataIndex: 'provisioning', width: 100 }, { title: this.$t('label.size'), key: 'size', dataIndex: 'size', width: 130 }, { title: this.$t('label.sharedfs.delete.refs'), key: 'references', width: 190 }, { title: this.$t('label.sharedfs.delete.protection'), key: 'protection', width: 160 }, { title: this.$t('label.sharedfs.delete.policy'), key: 'action', width: 180 }]
    },
    resultColumns () { return [{ title: this.$t('label.volume'), dataIndex: 'name' }, { title: this.$t('label.id'), dataIndex: 'uuid' }, { title: this.$t('label.state'), dataIndex: 'status' }] },
    resultRows () { return this.volumes.map(v => ({ ...v, status: this.$t('label.sharedfs.delete.volume.' + (this.operation?.progress?.volumes?.[v.uuid] || (v.type === 'ROOT' ? (this.operation?.progress?.vmRemoved ? 'REMOVED' : 'PRESERVED') : 'NOT_STARTED'))) })) }
  },
  created () { this.expungeInit(); if (this.initialOperation) this.observe(); else this.load() },
  activated () { if (this.closed) { this.closed = false; this.clearApproval(); if (this.initialOperation) this.observe(); else this.load() } },
  deactivated () { this.closed = true; this.generation++; clearTimeout(this.timer) },
  beforeUnmount () { this.closed = true; this.generation++; clearTimeout(this.timer) },
  methods: {
    expungeInit () { this.expunge = this.expungeAction },
    nextTarget () { this.generation++; clearTimeout(this.timer); this.groupIndex++; this.operation = null; this.impact = null; this.requestId = ''; this.jobId = null; this.submitting = false; this.policy = 'PRESERVE'; this.selected = []; this.clearApproval(); this.expunge = this.expungeAction; this.load() },
    reloadPlan () { if (this.submitting) return; if (this.operation?.state === 'REJECTED') { this.operation = null; this.requestId = ''; this.jobId = null } this.clearApproval(); this.selected = []; this.load() },
    clearApproval () { this.stopConfirmed = false; this.lossConfirmed = false; this.confirmName = ''; if (this.policy === 'PRESERVE') this.selected = [] },
    select (uuid, checked) { this.selected = checked ? [...new Set([...this.selected, uuid])] : this.selected.filter(x => x !== uuid); this.lossConfirmed = false },
    async load () {
      const g = ++this.generation; this.loading = true; this.error = ''
      try {
        const impact = unpack(await getAPI('getSharedFileSystemDeletionImpact', { id: this.target.id }, { preserveOnFailure: true }))
        const operation = unpack(await getAPI('getSharedFileSystemDeletionOperation', { id: this.target.id }, { preserveOnFailure: true }))
        if (g !== this.generation) return
        this.impact = impact
        if (operation && !['NOT_FOUND', 'CANCELLED', 'REJECTED'].includes(operation.state)) {
          this.operation = operation
          if (operation.state === 'SOFT_DESTROYED') { this.policy = operation.plan.policy; this.selected = operation.plan.volumes.filter(v => v.delete).map(v => v.uuid) }
          if (operation.state === 'RUNNING' && operation.workeractive !== false) { this.submitting = true; this.schedule() } else if (operation.state === 'PLANNED') this.schedule()
        } else this.operation = null
      } catch (e) { if (g === this.generation) this.error = apiError(e) } finally { if (g === this.generation) this.loading = false }
    },
    schedule () { clearTimeout(this.timer); if (!this.closed) this.timer = setTimeout(() => this.observe(), 2000) },
    async observe () {
      if (this.closed) return
      const g = this.generation
      try {
        const params = this.operation?.operationid ? { operationid: this.operation.operationid } : { id: this.target.id, requestid: this.requestId }
        const result = unpack(await getAPI('getSharedFileSystemDeletionOperation', params, { preserveOnFailure: true }))
        if (g !== this.generation) return
        if (result?.state !== 'NOT_FOUND') this.operation = result
        if (this.jobId) {
          const job = (await getAPI('queryAsyncJobResult', { jobid: this.jobId })).queryasyncjobresultresponse
          if (g !== this.generation) return
          if (job.jobstatus === 0) { this.submitting = true; this.schedule(); return }
          if (job.jobstatus === 2) { this.error = job.jobresult?.errortext || this.$t('label.sharedfs.delete.failure'); this.submitting = false; if (!this.operation) { this.impact = null; this.jobId = null; this.requestId = '' } return }
        }
        const running = !this.operation || (this.operation.state === 'RUNNING' && this.operation.workeractive !== false) || this.operation.state === 'OBSERVATION_PENDING'
        this.submitting = running
        if (running) this.schedule()
      } catch (e) { if (g === this.generation) { this.error = apiError(e); this.submitting = false } }
    },
    async send (api, params) {
      const g = this.generation
      this.submitting = true; this.error = ''
      try {
        const json = await postAPI(api, params, { preserveOnFailure: true }); const response = Object.values(json)[0]
        if (this.closed || g !== this.generation) return
        this.jobId = response?.jobid
        await this.observe()
      } catch (e) { if (this.closed || g !== this.generation) return; this.error = apiError(e); this.submitting = false; if (!this.operation) { if ([400, 403, 431].includes(e.response?.status)) { this.impact = null; this.requestId = ''; this.jobId = null } else this.operation = { state: 'OBSERVATION_PENDING', phase: 'REQUEST_OBSERVATION_PENDING', requestid: this.requestId } } }
    },
    submit () {
      if (!this.canSubmit) return
      if (this.frozen) { this.resume(); return }
      this.requestId = this.requestId || uuid()
      this.send(this.expungeAction ? 'expungeSharedFileSystem' : 'destroySharedFileSystem', { id: this.target.id, volumepolicy: this.policy, deletevolumeids: this.selected.join(',') || undefined, requestid: this.requestId, planfingerprint: this.impact.fingerprint, confirmresource: this.confirmName, confirmdataloss: this.lossConfirmed, forced: this.stopConfirmed, expunge: this.expunge })
    },
    async resume () {
      if (!this.operation || this.submitting) return
      this.requestId = this.operation.requestid
      await this.send('resumeSharedFileSystemDeletion', { operationid: this.operation.operationid, requestid: this.operation.requestid })
    },
    close () {
      this.closed = true; this.generation++; clearTimeout(this.timer)
      if (this.operation?.state === 'COMPLETED' && !this.historyMode) { this.$emit('close-action'); this.$router.push('/sharedfs'); return }
      this.$emit('close-action'); this.$emit('refresh-data')
    }
  }
}
</script>
<style scoped>
.sharedfs-deletion { min-width: 0; }
.section { margin-top: 16px; }
.hint { margin: 12px 0; color: var(--ui-text-secondary); line-height: 1.8; }
.uuid { font-size: 11px; color: var(--ui-text-secondary); overflow-wrap: anywhere; }
</style>
