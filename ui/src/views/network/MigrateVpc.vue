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
class="vpc-migration-dialog"
size="lg"
form-actions
focus-guard
:title="$t('label.vpc.migration')"
:closable="!busy"
:keyboard="!busy"
@cancel="close">
    <a-form layout="vertical" @keydown.ctrl.enter.prevent.stop="submitKey">
      <p class="migration-help">{{ $t('message.vpc.migration.impact') }}</p>
      <a-alert v-if="error" type="error" show-icon :message="error" role="alert" />
      <div class="migration-toolbar">
        <a-tag role="status" :color="status === 'success' ? 'green' : ['failed', 'unknown'].includes(status) ? 'red' : 'blue'">{{ $t('label.vpc.migration.status.' + status) }}</a-tag>
        <a-button :loading="loading" :disabled="busy || unsettled" @click="load">{{ $t('label.refresh') }}</a-button>
      </div>
      <template v-if="context">
        <a-alert v-if="reason" type="warning" show-icon :message="$t('message.vpc.migration.' + reason)" role="alert" />
        <a-alert v-if="context.previous" type="warning" show-icon :message="$t('message.vpc.migration.resume.info')" role="status" />
        <section>
          <h3>{{ $t('label.network.migration.current') }}</h3>
          <a-descriptions bordered size="small" :column="1">
            <a-descriptions-item :label="$t('label.name')">{{ context.vpc.name }} · {{ context.vpc.id }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.account')">{{ context.vpc.project || context.vpc.account }} · {{ context.vpc.domainpath || context.vpc.domainid }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.zone')">{{ context.vpc.zonename || context.vpc.zoneid }}</a-descriptions-item>
            <a-descriptions-item label="CIDR / DNS">{{ context.vpc.cidr }} · {{ context.vpc.dns1 || '—' }} · {{ context.vpc.dns2 || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.vpcoffering')">{{ context.offering.name }} · {{ context.offering.id }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.vpc.migration.services')">{{ services(context.offering) }}</a-descriptions-item>
          </a-descriptions>
          <p v-if="context.previous" class="migration-help">{{ $t('label.vpc.migration.previous') }}: {{ context.previous.id }} · {{ context.previous.vpcofferingname || context.previous.vpcofferingid }}</p>
        </section>
        <section>
          <h3>{{ $t('label.network.migration.destination') }}</h3>
          <a-form-item :label="$t('label.vpcoffering')">
            <a-select :value="targetId || undefined" show-search option-filter-prop="label" :aria-label="$t('label.vpc.migration.target')" :placeholder="$t('message.vpc.migration.select')" :disabled="busy || unsettled || status === 'success' || !!context.previous" @change="selectTarget">
              <a-select-option v-for="o in targets" :key="o.id" :value="o.id" :label="o.name">{{ o.name }} · {{ o.id }}</a-select-option>
            </a-select>
          </a-form-item>
          <p v-if="!targets.length" class="migration-help">{{ $t('message.vpc.migration.empty') }}</p>
          <p v-if="target" class="migration-help">{{ services(target) }} · IPv4 / NATTED</p>
        </section>
        <section>
          <h3>{{ $t('label.vpc.migration.tiers') }} ({{ context.tiers.length }})</h3>
          <p class="migration-help">{{ $t('message.vpc.migration.mapping.info') }}</p>
          <article v-for="row in context.tiers" :key="row.network.id" class="migration-tier" :data-tier-id="row.network.id" :data-location="row.locked ? 'destination' : 'source'">
            <strong>{{ row.network.name }}</strong>
            <p class="migration-help">{{ row.network.id }} · {{ row.network.state }} · {{ row.network.cidr }} · {{ row.network.gateway }}</p>
            <p class="migration-help">{{ row.offering.name }} · {{ physicalName(row.network.physicalnetworkid) }}</p>
            <p class="migration-help">{{ services(row.offering) }}</p>
            <a-tag v-if="context.previous">{{ $t(row.locked ? 'label.vpc.migration.tier.fixed' : 'label.vpc.migration.tier.remaining') }}</a-tag>
            <p v-if="row.source" class="migration-help">{{ $t('label.vpc.migration.previous') }}: {{ row.source.id }} · {{ physicalName(row.source.physicalnetworkid) }} · {{ row.source.state }}</p>
            <a-form-item :label="$t('label.network.offering')">
              <a-select :value="mappings[row.network.id] || undefined" show-search option-filter-prop="label" :aria-label="$t('label.network.offering') + ': ' + row.network.name" :placeholder="$t('message.vpc.migration.select')" :disabled="busy || unsettled || status === 'success' || row.locked || !target" @change="value => selectTier(row.network.id, value)">
                <a-select-option v-for="c in choices(row)" :key="c.offering.id" :value="c.offering.id" :label="c.offering.name">{{ c.offering.name }} · {{ c.physical.name }}</a-select-option>
              </a-select>
            </a-form-item>
            <p v-if="destination(row)" class="migration-help">{{ destination(row).offering.id }} · {{ destination(row).physical.id }} · {{ $t(destination(row).physical.id === row.network.physicalnetworkid ? 'label.vpc.migration.same.physical' : 'label.vpc.migration.other.physical') }} · {{ destination(row).offering.networkrate || '—' }} Mbps</p>
            <p v-if="target && !choices(row).length" class="migration-error" role="alert">{{ $t('message.vpc.migration.tier.empty') }}</p>
          </article>
          <p v-if="!context.tiers.length" class="migration-help">{{ $t('message.vpc.migration.no.tiers') }}</p>
          <p v-if="diagnostic" class="migration-help" role="status">{{ $t('label.vpc.migration.diagnostic.' + diagnostic) }}</p>
        </section>
        <section>
          <h3>{{ $t('label.network.migration.connections') }}</h3>
          <p class="migration-help">{{ $t('label.virtual.routers') }}: {{ context.routers.map(r => r.name + ' · ' + r.state).join(', ') || '—' }}</p>
          <p class="migration-help">{{ $t('label.public.ip') }}: {{ context.publicIps.map(p => p.ipaddress + ' · ' + p.state).join(', ') || '—' }}</p>
          <a-table size="small" :columns="vmColumns" :data-source="vmRows" row-key="key" :pagination="false" :scroll="{ x: 600 }">
            <template #bodyCell="{ column, record }"><router-link v-if="column.key === 'name'" :to="'/vm/' + record.vmId">{{ record.name }}</router-link><span v-else>{{ record[column.dataIndex] }}</span></template>
          </a-table>
        </section>
        <section class="migration-impact">
          <p class="migration-help">{{ $t('message.vpc.migration.support') }}</p>
          <a-checkbox :checked="acknowledged" :disabled="busy || unsettled || status === 'success' || !!reason || diagnostic !== 'ready'" @change="e => acknowledged = e.target.checked">{{ $t('message.vpc.migration.acknowledge') }}</a-checkbox>
        </section>
      </template>
      <section v-if="history.length" aria-live="polite">
        <h3>{{ $t('label.vpc.migration.history') }}</h3>
        <div v-for="(h, i) in history" :key="h.jobId" class="migration-tier">
          {{ i + 1 }} · {{ $t('label.vpc.migration.mode.' + h.mode) }} · {{ $t('label.vpc.migration.status.' + h.status) }}
          <p class="migration-help">{{ $t('label.network.restart.job') }}: {{ h.jobId }}</p>
          <p v-if="h.error" class="migration-error">{{ h.error }}</p>
        </div>
      </section>
    </a-form>
    <MoldDialogActions :busy="busy" @keydown.ctrl.enter.prevent.stop="submitKey">
      <a-button :disabled="busy" @click="close">{{ $t('label.close') }}</a-button>
      <a-button v-if="unsettled" type="primary" :disabled="busy || scopeChanged || !jobId" @click="settle">{{ $t('label.network.restart.recheck') }}</a-button>
      <a-button v-else-if="status !== 'success'" type="primary" :loading="busy" :disabled="!canStart" @click="start">{{ $t(context?.previous ? 'label.vpc.migration.resume' : 'label.vpc.migration.start') }}</a-button>
    </MoldDialogActions>
  </MoldDialog>
</template>
<script>
import { v4 as uuid } from 'uuid'
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import MoldDialog from '@/components/network/VpcDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { createJobTracker } from '@/utils/jobTracker'
import { vpcMigrationVisible, vpcMigrationIdentity, vpcMigrationReason, readVpcMigrationContext, vpcMigrationTargets, vpcTierMigrationTargets, buildVpcMigrationPlan, diagnoseVpcMigrationPlan, vpcMigrationParams, vpcMigrationPlanFingerprint, validVpcMigrationJob, vpcMigrationPreservation, vpcMigrationFingerprint, vpcMigrationSucceeded } from '@/utils/vpcMigration'

export default {
  name: 'MigrateVpc',
  components: { MoldDialog, MoldDialogActions },
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data: () => ({ context: null, targetId: '', mappings: {}, acknowledged: false, diagnostic: '', loaded: false, loading: false, working: false, status: 'idle', error: '', history: [], jobId: '', jobUserId: '', plan: null, original: null, storageError: false, scopeChanged: false, disposed: false, origin: '', entryKey: '', generation: 0, diagnosticGeneration: 0, readEpoch: 0, progressReading: false, tracker: null }),
  computed: {
    scope () { const u = this.$store.getters.userInfo || {}; return JSON.stringify([this.$route.fullPath, this.resource.id, this.resource.account, this.resource.domainid, this.resource.projectid, this.$store.getters.project?.id, u.id, u.roleid, u.roletype, this.$store.state?.user?.token, vpcMigrationVisible(this.resource, this.$store.getters)]) },
    storageKey () { const u = this.$store.getters.userInfo || {}; return 'vpc-migration:v1:' + JSON.stringify([u.id, u.roleid, u.roletype, this.$store.getters.project?.id, this.resource.id, this.resource.account, this.resource.domainid, this.resource.projectid]) },
    busy () { return this.loading || this.working },
    unsettled () { return ['submitting', 'running', 'unknown'].includes(this.status) },
    reason () { return this.context ? vpcMigrationReason(this.context) : '' },
    targets () { return this.context ? this.status === 'success' ? [this.context.offering] : vpcMigrationTargets(this.context) : [] },
    target () { return this.targets.find(o => o.id === this.targetId) },
    preview () { try { return this.context && buildVpcMigrationPlan(this.context, this.targetId, this.mappings) } catch (_) { return null } },
    canStart () { return this.active() && this.loaded && !this.busy && !this.unsettled && this.status !== 'success' && !this.storageError && this.acknowledged && this.diagnostic === 'ready' && !!this.preview && vpcMigrationVisible(this.resource, this.$store.getters) },
    vmColumns () { return [{ key: 'name', dataIndex: 'name', title: this.$t('label.name') }, { key: 'state', dataIndex: 'state', title: this.$t('label.state') }, { key: 'ip', dataIndex: 'ip', title: this.$t('label.ipaddress') }, { key: 'tier', dataIndex: 'tier', title: this.$t('label.vpc.migration.tiers') }] },
    vmRows () { return (this.context?.vms || []).flatMap(v => (v.nic || []).filter(n => this.context.tiers.some(r => [r.network.id, r.source?.id].includes(n.networkid))).map(n => ({ key: n.id, vmId: v.id, name: v.displayname || v.name, state: v.state, ip: n.ipaddress || '—', tier: this.context.tiers.find(r => [r.network.id, r.source?.id].includes(n.networkid))?.network.name }))) }
  },
  watch: { scope: { flush: 'sync', handler () { this.invalidate() } } },
  created () {
    this.origin = this.scope; this.entryKey = this.storageKey
    try {
      const saved = JSON.parse(sessionStorage.getItem(this.entryKey) || 'null')
      if (saved?.vpcId === this.resource.id && saved.identity === vpcMigrationIdentity(this.resource)) {
        for (const k of ['targetId', 'mappings', 'history', 'jobId', 'jobUserId', 'plan', 'original']) if (saved[k] !== undefined) this[k] = saved[k]
        this.status = ['running', 'submitting', 'unknown'].includes(saved.status) ? 'unknown' : saved.status || 'idle'
      }
    } catch (_) { this.storageError = true; this.error = this.$t('message.vpc.migration.storage') }
    this.tracker = createJobTracker({
      interval: 2000,
      maxRetries: 2,
      maxDuration: 600000,
      query: async id => {
        const job = (await this.read('queryAsyncJobResult', { jobid: id, ignoreproject: true }))?.queryasyncjobresultresponse
        if (!validVpcMigrationJob(job, id, this.resource.id, this.jobUserId)) throw new Error(this.$t('message.vpc.migration.job.scope'))
        return job
      },
      onState: (id, result) => { if (this.active() && id === this.jobId && result.jobstatus === 0) this.progress() }
    })
    eventBus.on('project-switch-start', this.invalidate)
    this.load()
  },
  beforeUnmount () { this.disposed = true; this.generation++; eventBus.off('project-switch-start', this.invalidate); this.tracker.clear() },
  methods: {
    active (token = this.generation) { return !this.disposed && !this.scopeChanged && this.origin === this.scope && token === this.generation },
    invalidate () { if (this.scopeChanged) return; this.scopeChanged = true; this.generation++; if (this.tracker) this.tracker.clear(); this.$emit('close-action') },
    read (api, params, options = {}) { return getAPI(api, params, { ...options, preserveOnFailure: true, backgroundJob: true, timeout: 15000 }) },
    text (e) { const d = Object.values(e?.response?.data || {}).find(v => v?.errorcode); const s = d?.errortext || e?.message || this.$t('message.request.failed'); return /^(message\.vpc\.migration\.|message\.network\.migration\.)/.test(s) ? this.$t(s) : [d?.errorcode, s].filter(Boolean).join(': ') },
    services (o) { return (o?.service || []).map(s => s.name + ': ' + (s.provider || []).map(p => p.name).join(', ')).join('; ') || '—' },
    physicalName (id) { return this.context?.physical.find(p => p.id === id)?.name || id || '—' },
    choices (row) {
      if (this.status === 'success') {
        const physical = this.context.physical.find(p => p.id === row.network.physicalnetworkid)
        return physical ? [{ offering: row.offering, physical }] : []
      }
      return this.target ? vpcTierMigrationTargets(this.context, row, this.target) : []
    },
    destination (row) { return this.choices(row).find(c => c.offering.id === this.mappings[row.network.id]) },
    persist () {
      if (!this.active() || this.storageError) return false
      try {
        const record = Object.fromEntries(['targetId', 'mappings', 'history', 'jobId', 'jobUserId', 'plan', 'original', 'status'].map(k => [k, this[k]]))
        sessionStorage.setItem(this.entryKey, JSON.stringify({ ...record, vpcId: this.resource.id, identity: vpcMigrationIdentity(this.resource) })); return true
      } catch (_) { this.storageError = true; this.error = this.$t('message.vpc.migration.storage'); return false }
    },
    setContext (context) {
      this.context = context; this.acknowledged = false; this.diagnostic = ''
      if (context.previous || this.status === 'success') this.targetId = context.offering.id
      else if (!vpcMigrationTargets(context).some(o => o.id === this.targetId)) this.targetId = ''
      this.mappings = Object.fromEntries(context.tiers.map(r => [r.network.id, r.locked || this.status === 'success' ? r.network.networkofferingid : this.mappings[r.network.id] || '']))
    },
    async load () {
      if (this.busy || !this.active()) return
      if (!vpcMigrationVisible(this.resource, this.$store.getters)) { this.invalidate(); return }
      const token = ++this.generation; ++this.readEpoch; this.loading = true; this.loaded = false
      if (!this.storageError) this.error = ''
      try {
        const context = await readVpcMigrationContext(this.read, this.resource)
        if (!this.active(token)) return
        this.setContext(context); this.loaded = true
        if (this.status === 'success' && (!this.plan || !this.original || !vpcMigrationSucceeded(context, this.plan, this.original))) this.result('unknown', this.$t('message.vpc.migration.result'))
        await this.diagnose(token)
      } catch (e) { if (this.active(token)) this.error = this.text(e) } finally { if (this.active(token)) this.loading = false }
    },
    async selectTarget (id) { if (this.busy || this.unsettled || this.context.previous) return; this.targetId = id; this.mappings = {}; await this.diagnose() },
    async selectTier (id, value) { if (this.busy || this.unsettled || this.context.tiers.find(r => r.network.id === id)?.locked) return; this.mappings = { ...this.mappings, [id]: value }; await this.diagnose() },
    async diagnose (token = this.generation) {
      const sequence = ++this.diagnosticGeneration; this.acknowledged = false; this.diagnostic = ''; this.persist()
      const plan = this.preview
      if (!plan || this.unsettled) return
      this.diagnostic = 'checking'
      try { await diagnoseVpcMigrationPlan(this.read, this.$store.getters, this.context, plan); if (this.active(token) && sequence === this.diagnosticGeneration) this.diagnostic = 'ready' } catch (e) { if (this.active(token) && sequence === this.diagnosticGeneration) { this.diagnostic = 'failed'; this.error = this.text(e) } }
    },
    result (status, error = '') {
      this.status = status; this.error = error
      if (status === 'success') { this.targetId = this.context.offering.id; this.mappings = Object.fromEntries(this.context.tiers.map(r => [r.network.id, r.network.networkofferingid])) }
      const h = this.history.at(-1); if (h) Object.assign(h, { status, error }); this.persist()
    },
    async start () {
      if (!this.canStart) return
      const token = ++this.generation; const expected = vpcMigrationFingerprint(this.context); const selected = this.preview; const selectedFingerprint = vpcMigrationPlanFingerprint(this.context, selected)
      this.working = true; this.error = ''; ++this.readEpoch
      try {
        const fresh = await readVpcMigrationContext(this.read, this.resource)
        if (!this.active(token)) return
        const plan = buildVpcMigrationPlan(fresh, this.targetId, this.mappings)
        if (vpcMigrationFingerprint(fresh) !== expected || vpcMigrationPlanFingerprint(fresh, plan) !== selectedFingerprint) throw new Error('message.vpc.migration.changed')
        await diagnoseVpcMigrationPlan(this.read, this.$store.getters, fresh, plan)
        if (!this.active(token)) return
        if (!fresh.previous || !this.original) this.original = vpcMigrationPreservation(fresh)
        this.plan = plan; this.jobId = uuid(); this.jobUserId = this.$store.getters.userInfo.id
        this.history.push({ jobId: this.jobId, mode: plan.resume ? 'resume' : 'new', status: 'submitting', error: '' }); this.status = 'submitting'
        if (!this.persist()) { this.status = 'failed'; return }
        let response
        try { response = await postAPI('migrateVPC', { ...vpcMigrationParams(plan, this.jobId), ignoreproject: true }, { preserveOnFailure: true, timeout: 15000 }) } catch (e) {
          if (this.active(token)) this.result(Object.values(e?.response?.data || {}).some(v => v?.errorcode) ? 'failed' : 'unknown', this.text(e))
          return
        }
        if (!this.active(token)) return
        if (response?.migratevpcresponse?.jobid !== this.jobId) { this.result('unknown', this.$t('message.vpc.migration.receipt')); return }
        this.result('running'); await this.finish(token)
      } catch (e) { if (this.active(token)) { this.error = this.text(e); this.acknowledged = false; this.diagnostic = ''; this.loaded = false } } finally { if (this.active(token)) this.working = false }
    },
    async progress () {
      if (!this.active() || this.status !== 'running' || this.progressReading) return
      this.progressReading = true; const token = this.generation; const epoch = ++this.readEpoch
      try { const context = await readVpcMigrationContext(this.read, this.resource); if (this.active(token) && epoch === this.readEpoch && this.status === 'running') this.context = context } catch (_) { /* Final verification remains mandatory. */ } finally { this.progressReading = false }
    },
    async finish (token) {
      const job = await this.tracker.track(this.jobId, {}, true)
      if (!this.active(token)) return
      ++this.readEpoch
      if (![1, 2].includes(job.jobstatus)) { this.result('unknown', this.$t('message.vpc.migration.unknown')); return }
      try {
        const context = await readVpcMigrationContext(this.read, this.resource)
        if (!this.active(token)) return
        this.setContext(context); this.loaded = true
        if (job.jobstatus === 2) this.result('failed', [job.jobresultcode, job.jobresult?.errortext || this.$t('message.request.failed')].filter(Boolean).join(': '))
        else if (job.jobresult?.vpc?.id !== this.resource.id || job.jobresult.vpc.vpcofferingid !== this.plan?.offeringId || !this.original || !vpcMigrationSucceeded(context, this.plan, this.original)) this.result('unknown', this.$t('message.vpc.migration.result'))
        else this.result('success')
        if (this.status === 'failed') await this.diagnose(token)
        if (this.active(token)) this.$emit('refresh-data')
      } catch (e) { if (this.active(token)) { this.loaded = false; this.result(job.jobstatus === 2 ? 'failed' : 'unknown', [job.jobresult?.errortext, this.text(e)].filter(Boolean).join(' · ')) } }
    },
    async settle () { if (!this.active() || this.busy || !this.unsettled || !this.jobId || !this.plan) return; const token = ++this.generation; this.working = true; this.result('running'); try { await this.finish(token) } finally { if (this.active(token)) this.working = false } },
    submitKey () { if (this.canStart) this.start() },
    close () { if (this.busy) return; if (this.status === 'success') { try { sessionStorage.removeItem(this.entryKey) } catch (_) {} } else this.persist(); this.$emit('close-action') }
  }
}
</script>
<style scoped lang="less">
.vpc-migration-dialog { font-size: 14px; color: var(--ui-text); }
.migration-help { margin: 8px 0; color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.migration-error { margin: 8px 0; color: var(--ui-error-text); overflow-wrap: anywhere; }
.migration-toolbar { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin: 12px 0; }
section { margin-top: 20px; min-width: 0; }
h3 { color: var(--ui-text); font-size: 14px; font-weight: 600; margin-bottom: 12px; }
.migration-tier, .migration-impact { margin: 12px 0; padding: 12px; border: 1px solid var(--ui-border); border-radius: 4px; background: var(--ui-bg-surface); min-width: 0; overflow-wrap: anywhere; }
.vpc-migration-dialog :deep(.ant-descriptions-item-content) { overflow-wrap: anywhere; }
.vpc-migration-dialog :deep(.ant-select) { width: 100%; min-width: 0; }
.vpc-migration-dialog :deep(.ant-table-wrapper) { max-width: 100%; }
.vpc-migration-dialog :deep(.ant-alert) { margin-bottom: 12px; }
@media (max-width: 767px) { .vpc-migration-dialog :deep(.ant-descriptions-item-label) { width: 92px; white-space: normal; } }
</style>
