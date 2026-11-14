<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->
<template>
  <MoldDialog
class="vpc-restart-dialog"
size="lg"
form-actions
focus-guard
:title="$t('label.restart.vpc')"
:closable="!busy"
:keyboard="!busy"
@cancel="close">
    <p class="vpc-restart-help">{{ $t('message.vpc.restart.impact') }}</p>
    <a-alert v-if="error" type="error" show-icon :message="error" role="alert" />
    <a-alert v-if="started" type="info" show-icon :message="$t('message.vpc.restart.results')" role="status" />
    <a-form layout="vertical" @keydown.ctrl.enter.prevent.stop="submitKey">
      <a-row :gutter="16">
        <a-col v-for="key in optionKeys" :key="key" :xs="24" :md="8">
          <a-form-item :label="$t('label.network.restart.' + key)">
            <a-switch :checked="options[key]" :aria-label="$t('label.network.restart.' + key)" :disabled="busy || started || !capabilities[key] || (key === 'livepatch' && (options.cleanup || options.makeredundant))" @change="value => changeOption(key, value)" />
          </a-form-item>
        </a-col>
      </a-row>
      <p class="vpc-restart-help">{{ $t('message.vpc.restart.options') }}</p>
    </a-form>
    <div class="vpc-restart-toolbar">
      <strong role="status">{{ $t('label.total') }} {{ rows.length }} · {{ $t('label.network.restart.status.success') }} {{ succeeded }} · {{ $t('label.network.restart.status.failed') }} {{ failedIds.length }}</strong>
      <a-button v-if="!started" :loading="busy" :disabled="busy || scopeChanged" @click="refreshTargets">{{ $t('label.network.restart.refresh') }}</a-button>
    </div>
    <div role="list" :aria-busy="busy" class="vpc-restart-results">
      <section v-for="row in rows" :key="row.id" role="listitem" class="vpc-restart-result" :data-vpc-id="row.id" :data-status="row.status">
        <div><strong>{{ row.vpc.name || row.id }}</strong><div class="vpc-restart-help">{{ row.id }}</div><div class="vpc-restart-help">{{ row.vpc.project || row.vpc.account }} · {{ row.vpc.zonename || row.vpc.zoneid }}</div><div class="vpc-restart-help">{{ $t(row.vpc.redundantvpcrouter ? 'label.vpc.restart.redundant' : 'label.vpc.restart.single') }} · {{ row.vpc.state ? $t('state.' + row.vpc.state.toLowerCase()) : '—' }} · {{ providers(row) || '—' }}</div></div>
        <div><a-tag :color="row.status === 'success' ? 'green' : ['failed', 'unknown'].includes(row.status) ? 'red' : 'blue'">{{ $t('label.network.restart.status.' + row.status) }}</a-tag><div v-if="row.error || reason(row)" role="alert" class="vpc-restart-error">{{ row.error || reason(row) }}</div><div v-for="(attempt, i) in row.history" :key="attempt.jobId" class="vpc-restart-help">{{ $t('label.vpc.restart.attempt') }} {{ i + 1 }} · {{ $t('label.network.restart.status.' + attempt.status) }}<div>{{ $t('label.network.restart.job') }}: {{ attempt.jobId }}</div><div v-if="attempt.error">{{ attempt.error }}</div></div><a-button v-if="row.status === 'unknown' && row.jobId" :disabled="busy || scopeChanged" @click="recheck(row)">{{ $t('label.network.restart.recheck') }}</a-button></div>
      </section>
    </div>
    <MoldDialogActions :busy="busy" @keydown.ctrl.enter.prevent.stop="submitKey">
      <a-button :disabled="busy" @click="close">{{ $t('label.close') }}</a-button>
      <a-button v-if="!started" type="primary" :loading="busy" :disabled="!canStart" @click="run()">{{ $t('label.restart.vpc') }}</a-button>
      <a-button v-else-if="failedIds.length" type="primary" :loading="busy" :disabled="busy || unsettled || scopeChanged || storageError" @click="run(failedIds)">{{ $t('label.network.restart.retry.failed') }} ({{ failedIds.length }})</a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>
<script>
import { v4 as uuid } from 'uuid'
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { createJobTracker } from '@/utils/jobTracker'
import { networkCollection } from '@/utils/networkLifecycle'
import { vpcRestartApis, vpcRestartOptions, vpcRestartIdentity, vpcRestartFingerprint, vpcRestartReason, vpcRestartCapabilities, vpcRestartOptionReason, vpcRestartParams, validVpcRestartJob } from '@/utils/vpcRestart'

export default {
  name: 'RestartVpc',
  components: { MoldDialog, MoldDialogActions },
  props: { resource: { type: Object, default: () => ({}) }, currentAction: { type: Object, default: () => ({}) } },
  emits: ['close-action', 'refresh-data'],
  data () {
    const targets = this.currentAction.vpcRestartTargets || [this.resource]
    return { rows: targets.filter(r => r?.id).map(v => ({ id: v.id, vpc: { ...v }, expected: vpcRestartFingerprint(v), original: { ...v }, history: [], status: 'idle', error: '', jobId: '', jobs: [] })), options: { cleanup: false, makeredundant: false, livepatch: false }, optionKeys: vpcRestartOptions, busy: false, loaded: false, started: false, error: '', generation: 0, scopeChanged: false, disposed: false, origin: '', entryKey: '', storageError: false, tracker: null }
  },
  computed: {
    params () { return (this.$store.getters.apis.restartVPC?.params || []).map(p => p.name) },
    scope () { const u = this.$store.getters.userInfo || {}; return JSON.stringify([this.$route.fullPath, this.$store.getters.project?.id, u.id, u.roleid, u.roletype, u.account, u.domainid, this.$store.state?.user?.token, vpcRestartApis.map(a => a in this.$store.getters.apis)]) },
    storageKey () { const u = this.$store.getters.userInfo || {}; return 'vpc-restart:v1:' + JSON.stringify([u.id, u.roleid, u.roletype, this.$store.getters.project?.id, this.rows.map(r => [r.id, vpcRestartIdentity(r.original)]).sort()]) },
    capabilities () { return Object.fromEntries(this.optionKeys.map(k => [k, this.loaded && this.rows.length > 0 && this.rows.every(r => vpcRestartCapabilities(r, this.params)[k])])) },
    canStart () { return this.active() && this.params.includes('id') && this.loaded && this.rows.length > 0 && !this.busy && !this.started && !this.storageError && this.rows.every(r => !r.error && !this.reason(r)) },
    succeeded () { return this.rows.filter(r => r.status === 'success').length },
    failedIds () { return this.rows.filter(r => r.status === 'failed').map(r => r.id) },
    unsettled () { return this.rows.some(r => ['unknown', 'running', 'submitting'].includes(r.status)) }
  },
  watch: { scope: { flush: 'sync', handler () { this.invalidate() } } },
  created () {
    this.origin = this.scope; this.entryKey = this.storageKey
    try {
      const saved = JSON.parse(sessionStorage.getItem(this.entryKey) || 'null')
      if (saved?.rows?.length === this.rows.length && saved.rows.every(r => this.rows.some(x => x.id === r.id))) {
        this.options = { ...this.options, ...saved.options }; this.started = !!saved.started
        this.rows = this.rows.map(r => ({ ...r, ...saved.rows.find(x => x.id === r.id), status: ['submitting', 'running'].includes(saved.rows.find(x => x.id === r.id).status) ? 'unknown' : saved.rows.find(x => x.id === r.id).status }))
      }
    } catch (_) { this.storageError = true; this.error = this.$t('message.vpc.restart.storage') }
    this.tracker = createJobTracker({
      interval: 2000,
      maxRetries: 2,
      maxDuration: 600000,
      onState: () => {},
      query: async id => {
        const row = this.rows.find(r => r.jobId === id)
        const json = await getAPI('queryAsyncJobResult', { jobid: id, ignoreproject: true }, { preserveOnFailure: true, backgroundJob: true, timeout: 15000 })
        const job = json?.queryasyncjobresultresponse
        if (!row || !validVpcRestartJob(job, row.id, id, this.$store.getters.userInfo.id)) throw new Error(this.$t('message.vpc.restart.job.scope'))
        return job
      }
    })
    eventBus.on('project-switch-start', this.invalidate)
    this.refreshTargets()
  },
  beforeUnmount () { this.disposed = true; this.generation++; eventBus.off('project-switch-start', this.invalidate); this.tracker.clear() },
  methods: {
    active (token = this.generation) { return !this.disposed && !this.scopeChanged && this.origin === this.scope && token === this.generation },
    invalidate () { if (this.scopeChanged) return; this.scopeChanged = true; this.generation++; if (this.tracker) this.tracker.clear(); this.$emit('close-action') },
    text (e) { const d = Object.values(e?.response?.data || {}).find(x => x?.errorcode); return [d?.errorcode, d?.errortext || e?.message || this.$t('message.request.failed')].filter(Boolean).join(': ') },
    providers (r) { return [...new Set((r.offering?.service || []).flatMap(s => (s.provider || []).map(p => p.name)))].join(', ') },
    converted (row, data = row) { return this.started && this.options.makeredundant && !row.original.redundantvpcrouter && data.vpc.redundantvpcrouter && data.vpc.vpcofferingid === data.redundantOffering?.id && vpcRestartIdentity(data.vpc) === vpcRestartIdentity(row.original) },
    reason (row) { const k = vpcRestartReason(row.vpc, this.$store.getters) || vpcRestartOptionReason(row, this.options, this.params, this.converted(row)); return k ? this.$t('message.vpc.restart.reason.' + k) : '' },
    persist () {
      if (!this.active() || this.storageError) return false
      const fields = ['id', 'name', 'zoneid', 'zonename', 'account', 'domainid', 'domainpath', 'projectid', 'project', 'vpcofferingid', 'redundantvpcrouter', 'state']
      const pick = v => Object.fromEntries(fields.filter(k => k in v).map(k => [k, v[k]]))
      const rows = this.rows.map(r => ({ id: r.id, vpc: pick(r.vpc), original: pick(r.original), expected: r.expected, jobId: r.jobId, status: r.status, error: r.error, history: r.history }))
      try { sessionStorage.setItem(this.entryKey, JSON.stringify({ rows, options: this.options, started: this.started })); return true } catch (_) { this.storageError = true; this.error = this.$t('message.vpc.restart.storage'); return false }
    },
    changeOption (key, value) {
      if (this.busy || this.started || !this.capabilities[key]) return
      this.options[key] = value === true
      if (key === 'makeredundant' && value) this.options.cleanup = true
      if (key === 'cleanup' && !value) this.options.makeredundant = false
      if (this.options.cleanup || this.options.makeredundant) this.options.livepatch = false
      this.persist()
    },
    async collection (api, field, p) { return networkCollection(getAPI, api, field, { ...p, ignoreproject: true }) },
    async readTarget (row) {
      const p = { listall: true, ...(row.original.projectid ? { projectid: row.original.projectid } : {}) }
      const vpcs = await this.collection('listVPCs', 'vpc', { ...p, id: row.id })
      if (vpcs.length !== 1 || vpcs[0].id !== row.id) throw new Error(this.$t('message.vpc.restart.reason.missing'))
      const vpc = vpcs[0]; const apis = this.$store.getters.apis
      const [offerings, networks, routers, pending] = await Promise.all([
        this.collection('listVPCOfferings', 'vpcoffering', { listall: true }),
        'listNetworks' in apis ? this.collection('listNetworks', 'network', { ...p, vpcid: row.id }) : null,
        'listRouters' in apis ? this.collection('listRouters', 'router', { ...p, vpcid: row.id }) : null,
        networkCollection(getAPI, 'listAsyncJobs', 'asyncjobs', { ...p, ignoreproject: true, ...(vpc.projectid ? {} : { account: vpc.account, domainid: vpc.domainid }) }, 'jobid')
      ])
      const offering = offerings.find(o => o.id === vpc.vpcofferingid)
      if (!offering || networks?.some(n => n.vpcid !== row.id) || routers?.some(r => r.vpcid !== row.id)) throw new Error(this.$t('message.vpc.restart.lookup'))
      // The deployed VPC job view may omit its resource UUID. An unbound
      // pending VPC job in this owner's scope blocks writes conservatively.
      const jobs = pending.filter(j => j.jobstatus === 0 && (String(j.jobinstancetype).toLowerCase() === 'vpc' || /(?:Create|Restart|Delete)VPCCmd$/.test(j.cmd || '')) && (!j.jobinstanceid || j.jobinstanceid === row.id))
      return { vpc, offering, networks, routers, jobs, redundantOffering: offerings.find(o => o.isdefault && o.name === 'Redundant VPC offering' && o.state === 'Enabled') }
    },
    async refreshTargets () {
      if (this.busy || !this.active()) return
      const token = ++this.generation; this.busy = true; this.error = ''
      const reads = await Promise.allSettled(this.rows.map(r => this.readTarget(r)))
      if (!this.active(token)) return
      this.rows.forEach((row, i) => {
        if (reads[i].status === 'rejected') row.error = this.text(reads[i].reason)
        else { Object.assign(row, reads[i].value); if (!this.started) { row.error = ''; row.original = { ...row.vpc }; row.expected = vpcRestartFingerprint(row.vpc) } }
      })
      this.loaded = true; this.busy = false
      if (reads.some(r => r.status === 'rejected')) this.error = this.$t('message.vpc.restart.preflight')
      this.persist()
    },
    async validate (row) {
      const data = await this.readTarget(row)
      if (vpcRestartFingerprint(data.vpc) !== row.expected && !this.converted(row, data)) throw new Error(this.$t('message.vpc.restart.reason.changed'))
      const k = vpcRestartReason(data.vpc, this.$store.getters) || vpcRestartOptionReason(data, this.options, this.params, this.converted(row, data))
      if (k) throw new Error(this.$t('message.vpc.restart.reason.' + k))
      return data
    },
    result (row, status, error = '') { row.status = status; row.error = error; const last = row.history.at(-1); if (last) Object.assign(last, { status, error }); this.persist() },
    async settle (row, token) {
      this.result(row, 'running')
      try {
        const job = await this.tracker.track(row.jobId, {}, true)
        if (!this.active(token)) return
        if (job.jobstatus === 2) { this.result(row, 'failed', [job.jobresultcode, job.jobresult?.errortext || this.$t('message.request.failed')].filter(Boolean).join(': ')); return }
        if (job.jobstatus !== 1 || job.jobresult?.success !== true) throw new Error(this.$t('message.vpc.restart.unknown'))
        const data = await this.readTarget(row)
        if (!this.active(token)) return
        if (vpcRestartIdentity(data.vpc) !== vpcRestartIdentity(row.original) || data.vpc.state !== 'Enabled' || data.vpc.restartrequired || (this.options.makeredundant ? !this.converted(row, data) : vpcRestartFingerprint(data.vpc) !== row.expected) || (data.routers && (!data.routers.length || data.routers.some(r => r.state !== 'Running')))) throw new Error(this.$t('message.vpc.restart.unknown'))
        Object.assign(row, data); this.result(row, 'success')
      } catch (e) { if (this.active(token)) this.result(row, 'unknown', this.text(e)) }
    },
    async run (ids) {
      if (this.busy || this.unsettled || !this.active() || this.storageError || (!this.started && !this.canStart)) return
      const targets = this.started ? this.rows.filter(r => r.status === 'failed' && ids?.includes(r.id)) : this.rows
      if (!targets.length) return
      const token = ++this.generation; this.busy = true; this.error = ''
      try {
        const checks = await Promise.allSettled(targets.map(r => this.validate(r)))
        if (!this.active(token)) return
        targets.forEach((row, i) => { row.error = checks[i].status === 'rejected' ? this.text(checks[i].reason) : ''; if (!row.error) Object.assign(row, checks[i].value) })
        if (targets.some(r => r.error)) { this.error = this.$t('message.vpc.restart.preflight'); return }
        this.started = true
        for (const row of targets) {
          if (!this.active(token)) return
          try { Object.assign(row, await this.validate(row)) } catch (e) { if (!this.active(token)) return; row.status = 'failed'; row.error = this.text(e); this.persist(); continue }
          if (!this.active(token)) return
          row.jobId = uuid(); row.history.push({ jobId: row.jobId, status: 'submitting', error: '' }); this.result(row, 'submitting')
          if (this.storageError) break
          try {
            const json = await postAPI('restartVPC', { ...vpcRestartParams(row.id, this.options, this.params), customjobid: row.jobId, ignoreproject: true, ...(row.vpc.projectid ? { projectid: row.vpc.projectid } : {}) }, { preserveOnFailure: true, backgroundJob: true, timeout: 30000 })
            if (!this.active(token)) return
            if (json?.restartvpcresponse?.jobid !== row.jobId) throw new Error(this.$t('message.vpc.restart.unknown'))
          } catch (e) {
            if (!this.active(token)) return
            const s = e?.response?.status
            if (s >= 400 && s < 500 && Object.values(e.response.data || {}).some(d => d?.errorcode)) { this.result(row, 'failed', this.text(e)); continue }
            this.result(row, 'unknown', this.text(e))
          }
          await this.settle(row, token)
          if (row.status === 'unknown') break
        }
        if (this.active(token)) this.$emit('refresh-data')
      } finally {
        if (this.active(token)) {
          if (this.unsettled) this.rows.filter(r => r.status === 'idle').forEach(r => { r.status = 'failed'; r.error = this.$t('message.vpc.restart.batch.wait') })
          this.busy = false; this.persist()
        }
      }
    },
    async recheck (row) { if (this.busy || !this.active() || row.status !== 'unknown' || !row.jobId) return; const token = ++this.generation; this.busy = true; try { await this.settle(row, token); if (this.active(token)) this.$emit('refresh-data') } finally { if (this.active(token)) { this.busy = false; this.persist() } } },
    submitKey () { if (this.started) { if (this.failedIds.length) this.run(this.failedIds) } else this.run() },
    close () { if (this.busy) return; if (this.rows.length && (this.rows.every(r => r.status === 'success') || this.rows.every(r => !r.history.length))) { try { sessionStorage.removeItem(this.entryKey) } catch (_) {} } else this.persist(); this.invalidate() }
  }
}
</script>
<style scoped>
.vpc-restart-help { color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.vpc-restart-toolbar { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin: 16px 0; }
.vpc-restart-results { display: flex; flex-direction: column; gap: 12px; }
.vpc-restart-result { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; padding: 16px; border: 1px solid var(--ui-border); border-radius: 4px; background: var(--ui-bg-surface); overflow-wrap: anywhere; }
.vpc-restart-result > div { min-width: 0; }
.vpc-restart-error { color: var(--ui-error-text); }
.vpc-restart-dialog :deep(.ant-alert) { margin-bottom: 16px; }
@media (max-width: 767px) { .vpc-restart-result { grid-template-columns: minmax(0, 1fr); } }
</style>
