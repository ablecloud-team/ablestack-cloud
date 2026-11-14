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
  <mold-dialog v-if="visible" :visible="visible" :title="$t('label.template.zone.' + kind)" size="lg" form-actions focus-guard @cancel="close">
    <div v-ctrl-enter="submit" class="zone-operation">
      <p class="identity">{{ resource.name }}<br>{{ resource.id }}</p>
      <a-alert v-if="kind === 'delete'" type="warning" show-icon :message="$t('message.template.zone.delete')" />
      <a-alert v-if="kind === 'prepare'" type="info" show-icon :message="$t('message.template.zone.prepare')" />
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <template v-if="phase === 'confirm' && kind === 'copy'">
        <p>{{ rows[0]?.zonename }}</p>
        <a-alert v-if="!optionsLoading && !error && !destinations.length" type="info" show-icon :message="$t('message.template.zone.copy.empty')" />
        <a-form-item :label="$t('label.select.zones')">
          <a-select v-model:value="selectedZones" mode="multiple" :loading="optionsLoading" :disabled="!destinations.length" option-filter-prop="label" show-search :options="destinations.map(z => ({value:z.id,label:z.name}))" />
        </a-form-item>
      </template>
      <a-form-item v-if="phase === 'confirm' && kind === 'prepare'" :label="$t('label.primary.storage')">
        <a-select v-model:value="poolid" :loading="optionsLoading" :options="eligiblePools.map(p => ({value:p.id,label:p.name}))" />
        <p v-if="!optionsLoading && !error && !eligiblePools.length">{{ $t('message.template.zone.pool.empty') }}</p>
      </a-form-item>
      <a-table :columns="displayColumns" :data-source="rows" :pagination="false" :scroll="{x:700}" row-key="zoneid" size="small">
        <template #bodyCell="{column,record}">
          <template-availability v-if="column.key==='status'" :record="record" :show-scope="false" compact />
          <span v-else-if="column.key==='isready'">{{ $t(record.isready === true ? 'label.yes' : record.isready === false ? 'label.no' : 'label.unknown') }}</span>
          <span v-else-if="column.key==='created'">{{ record.created ? $toLocaleDate(record.created) : '—' }}</span>
          <span v-else-if="column.key==='operationState'" role="status">{{ $t('label.template.zone.state.'+(kind==='prepare' && record.operationState==='running'?'observing':record.operationState)) }}<br><span v-if="record.error">{{ record.error }}</span></span>
        </template>
      </a-table>
      <div v-if="kind==='delete' && phase==='confirm'" class="force-field">
        <a-switch v-model:checked="forced" :aria-label="$t('label.isforced')" /> {{ $t('label.isforced') }}
        <p>{{ $t('message.template.zone.force') }}</p>
      </div>
      <p v-if="phase==='result'" role="status">{{ $t('label.success') }} {{ count('success') }} · {{ $t('state.failed') }} {{ count('failed') }} · {{ $t('label.unknown') }} {{ count('unknown') }} · {{ $t('state.inprogress') }} {{ count('requesting')+count('running') }}</p>
      <mold-dialog-actions :busy="busy">
        <a-button @click="close">{{ $t(phase==='confirm'?'label.cancel':'label.close') }}</a-button>
        <a-button v-if="phase==='confirm'" type="primary" :danger="kind==='delete'" :disabled="!canSubmit" :loading="busy" @click="submit">{{ $t('label.ok') }}</a-button>
        <a-button v-if="phase==='result' && count('failed')" :disabled="busy" @click="runFailed">{{ $t('label.template.zone.retry.failed') }}</a-button>
        <a-button v-if="phase==='result' && count('unknown')" :disabled="busy" @click="observeUnknown">{{ $t('label.template.zone.observe') }}</a-button>
      </mold-dialog-actions>
    </div>
  </mold-dialog>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TemplateAvailability from '@/components/widgets/TemplateAvailability'
import { operationSnapshot, preparePoolEligible, primaryPreparationState } from '@/utils/templateZoneOperations'
import { templateListLocation } from '@/utils/templateSearch'
export default {
  name: 'TemplateZoneOperations',
  components: { MoldDialog, MoldDialogActions, TemplateAvailability },
  props: { resource: { type: Object, required: true }, context: { type: String, required: true }, permitted: Boolean },
  emits: ['retained', 'refresh', 'refresh-parent', 'closed'],
  data () { return { visible: false, kind: 'delete', phase: 'confirm', rows: [], forced: false, busy: false, error: '', destinations: [], pools: [], selectedZones: [], poolid: undefined, optionsLoading: false, epoch: 0, operationContext: '', returnFocus: null } },
  computed: {
    eligiblePools () { return this.pools.filter(p => preparePoolEligible(p, this.rows[0]?.zoneid)) },
    canSubmit () {
      if (this.busy || this.optionsLoading || !this.permitted || !this.rows.length) return false
      if (this.kind === 'copy') return this.selectedZones.length > 0 && this.selectedZones.every(id => this.destinations.some(z => z.id === id))
      if (this.kind === 'prepare') return this.eligiblePools.some(p => p.id === this.poolid)
      return true
    },
    displayColumns () {
      const keys = this.kind === 'copy' && this.phase === 'result' ? ['zonename'] : ['zonename', 'created', 'status', 'isready']
      if (this.phase === 'result') keys.push('operationState')
      return keys.map(key => ({ key, dataIndex: key, title: this.$t(key === 'operationState' ? 'label.operation.status' : 'label.' + key) }))
    }
  },
  watch: { context () { this.invalidate() } },
  beforeUnmount () { this.invalidate() },
  methods: {
    count (state) { return this.rows.filter(r => r.operationState === state).length },
    current (epoch) { return this.visible && epoch === this.epoch && this.operationContext === this.context },
    invalidate () { this.returnFocus = null; this.epoch++; this.visible = false; this.busy = false; this.$emit('retained', { active: false, context: this.operationContext }) },
    async open (kind, records) {
      if (this.busy || !this.permitted || !records.length || records.some(r => !r.zoneid || !(r.id || this.resource.id))) return
      const active = document.activeElement
      const label = this.$t('label.' + (kind === 'copy' ? 'action.copy.template' : kind === 'prepare' ? 'template.zone.prepare' : 'action.delete.template'))
      this.returnFocus = active && active !== document.body ? active : Array.from(this.$parent?.$el?.querySelectorAll?.('button[aria-label]') || []).find(button => button.getAttribute('aria-label') === label && button.closest('tr')?.getAttribute('data-row-key') === records[0].zoneid)
      this.epoch++; this.kind = kind; this.phase = 'confirm'; this.rows = records.map(r => operationSnapshot(r, this.resource)); this.forced = false; this.optionsLoading = false; this.error = ''; this.selectedZones = []; this.poolid = undefined; this.destinations = []; this.pools = []; this.operationContext = this.context; this.visible = true
      this.$emit('retained', { active: true, context: this.operationContext })
      const epoch = this.epoch
      if (kind === 'delete') return
      this.optionsLoading = true
      try {
        if (kind === 'copy') { const d = (await getAPI('listZones', { available: true })).listzonesresponse; if (this.current(epoch)) this.destinations = (d?.zone || []).filter(z => z.id !== this.rows[0].zoneid) } else { const d = (await getAPI('listStoragePools', { zoneid: this.rows[0].zoneid, listall: true })).liststoragepoolsresponse; if (this.current(epoch)) this.pools = d?.storagepool || [] }
      } catch (e) { if (this.current(epoch)) this.error = e.message || this.$t('error.fetching.data') } finally { if (this.current(epoch)) this.optionsLoading = false }
    },
    async submit (event) {
      if (event?.preventDefault) event.preventDefault()
      if (this.phase !== 'confirm' || !this.canSubmit) return
      if (this.kind === 'copy') { const source = this.rows[0]; this.sourceZoneId = source.zoneid; this.rows = this.selectedZones.map(id => ({ ...source, zoneid: id, zonename: this.destinations.find(z => z.id === id).name, operationState: 'pending', error: '', jobid: '' })) }
      this.phase = 'result'; await this.run(this.rows)
    },
    async run (rows) {
      if (this.busy) return
      this.busy = true; const epoch = this.epoch
      try { await Promise.all(rows.map(r => this.request(r, epoch))) } finally { if (this.current(epoch)) { this.busy = false; this.$emit('refresh') } }
    },
    async request (row, epoch) {
      if (!this.current(epoch)) return
      row.operationState = 'requesting'; row.error = ''; row.jobid = ''
      const api = { delete: 'deleteTemplate', copy: 'copyTemplate', prepare: 'prepareTemplate' }[this.kind]
      const params = this.kind === 'delete' ? { id: row.id, zoneid: row.zoneid, forced: this.forced } : this.kind === 'copy' ? { id: row.id, sourcezoneid: this.sourceZoneId, destzoneids: row.zoneid } : { templateid: row.id, zoneid: row.zoneid, storageid: this.poolid }
      if (!(api in this.$store.getters.apis) || !this.permitted || (this.kind === 'prepare' && this.$store.getters.userInfo?.roletype !== 'Admin')) { row.operationState = 'failed'; row.error = this.$t('message.no.access'); return }
      if (this.kind === 'prepare' && !this.eligiblePools.some(p => p.id === this.poolid)) { row.operationState = 'failed'; return }
      try {
        const json = await postAPI(api, params, { preserveOnFailure: true }); if (!this.current(epoch)) return
        row.operationState = 'running'
        if (this.kind === 'prepare') await this.observePrepare(row, epoch)
        else { row.jobid = json[api.toLowerCase() + 'response']?.jobid; if (!row.jobid)row.operationState = 'unknown'; else await this.observeJob(row, epoch) }
      } catch (e) { if (this.current(epoch)) { row.operationState = e.response ? 'failed' : 'unknown'; row.error = e.message || this.$t('message.request.failed') } }
    },
    async observeJob (row, epoch) {
      const end = Date.now() + 60000
      try {
        do {
          const d = (await getAPI('queryAsyncJobResult', { jobid: row.jobid }, { preserveOnFailure: true, timeout: 10000 })).queryasyncjobresultresponse; if (!this.current(epoch)) return
          if (d.jobstatus === 1) { row.operationState = 'success'; return }
          if (d.jobstatus === 2) { row.operationState = 'failed'; row.error = d.jobresult?.errortext || this.$t('message.request.failed'); return }
          await new Promise(resolve => setTimeout(resolve, 2000))
        } while (this.current(epoch) && Date.now() < end)
        if (this.current(epoch))row.operationState = 'unknown'
      } catch (e) { if (this.current(epoch)) { row.operationState = 'unknown'; row.error = e.message } }
    },
    async observePrepare (row, epoch) {
      const end = Date.now() + 60000
      try {
        do {
          const d = (await getAPI('listTemplates', { id: row.id, zoneid: row.zoneid, templatefilter: 'all', listall: true }, { preserveOnFailure: true, timeout: 10000 })).listtemplatesresponse; if (!this.current(epoch)) return
          const state = primaryPreparationState(d?.template || [], row.zoneid, this.poolid, row.id)
          if (state !== 'running') { row.operationState = state; return }
          await new Promise(resolve => setTimeout(resolve, 2000))
        } while (this.current(epoch) && Date.now() < end)
        if (this.current(epoch))row.operationState = 'unknown'
      } catch (e) { if (this.current(epoch)) { row.operationState = 'unknown'; row.error = e.message } }
    },
    async runFailed () { await this.run(this.rows.filter(r => r.operationState === 'failed')) },
    async observeUnknown () {
      if (this.busy) return
      this.busy = true; const epoch = this.epoch
      try { await Promise.all(this.rows.filter(r => r.operationState === 'unknown').map(r => this.kind === 'prepare' ? this.observePrepare(r, epoch) : r.jobid ? this.observeJob(r, epoch) : Promise.resolve())) } finally { if (this.current(epoch)) { this.busy = false; this.$emit('refresh') } }
    },
    close () {
      if (this.busy) return
      const back = this.phase === 'result' && this.kind === 'delete'
      const target = this.returnFocus; const scope = this.operationContext
      this.invalidate(); this.$emit('closed')
      if (back) this.$router.push(templateListLocation(this.$route, this.$store.getters)); else {
        this.$emit('refresh'); this.$emit('refresh-parent')
        this.$nextTick(() => {
          if (this.visible || scope !== this.context || !target?.isConnected || target.disabled || !target.getClientRects().length) return
          target.focus({ preventScroll: true })
        })
      }
    }
  }
}
</script>
<style scoped>
.zone-operation { min-width:0;overflow-wrap:anywhere; }
.zone-operation .ant-alert { margin-bottom:16px; }
.identity { overflow-wrap:anywhere; }
.force-field { margin-top:16px; }
</style>
