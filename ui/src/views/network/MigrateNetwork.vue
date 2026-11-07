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
  <MoldDialog class="network-migration-dialog" size="lg" focus-guard :title="$t('label.network.migration')" :closable="!submitting" :keyboard="!submitting" @cancel="close">
    <strong>{{ context?.network.name || resource.name }}</strong>
    <p class="migration-help">{{ resource.id }}</p>
    <div class="migration-toolbar">
      <a-tag :color="job?.jobstatus === 2 || unknown ? 'red' : verified ? 'green' : 'blue'" role="status">{{ $t('label.network.migration.status.' + status) }}</a-tag>
      <a-button :loading="loading" :disabled="submitting || checking" @click="load"><reload-outlined />{{ $t('label.refresh') }}</a-button>
    </div>
    <a-alert v-if="scopeChanged" type="error" show-icon :message="$t('message.network.migration.scope')" role="alert" />
    <a-alert v-if="error" type="error" show-icon :message="error" role="alert" />
    <p v-if="job?.jobid" class="migration-help migration-job">{{ $t('label.network.migration.job') }}: {{ job.jobid }}</p>
    <a-spin :spinning="loading">
      <template v-if="context">
        <a-alert v-if="reason && !pending && !submitting" type="warning" show-icon :message="$t('message.network.migration.' + reason)" />
        <a-alert v-if="context.checkpoint" :type="pending ? 'info' : 'warning'" show-icon :message="$t(pending ? 'message.network.lifecycle.migration.running' : 'message.network.migration.resume.locked')" />
        <section class="migration-section">
          <h3>{{ $t('label.network.migration.current') }}</h3>
          <a-descriptions bordered size="small" :column="1">
            <a-descriptions-item :label="$t('label.physical.network')">{{ physicalName(context.network.physicalnetworkid) }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.network.offering')">{{ context.offering.name }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.state')">{{ context.network.state }}</a-descriptions-item>
            <a-descriptions-item label="CIDR / Gateway">{{ context.network.cidr || '—' }} / {{ context.network.gateway || '—' }}</a-descriptions-item>
            <a-descriptions-item label="DNS">{{ [context.network.dns1, context.network.dns2].filter(Boolean).join(', ') || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.supportedservices')">{{ context.offering.service?.map(s => s.name).join(', ') || '—' }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.public.ip')"><template v-if="context.publicIps.length"><p v-for="ip in context.publicIps" :key="ip.id" class="migration-help">{{ ip.ipaddress }} · {{ connectionPhysical(ip.migrationnetworkid) }} · {{ ip.state }}</p></template><span v-else>—</span></a-descriptions-item>
            <a-descriptions-item :label="$t('label.virtual.routers')"><template v-if="context.routers?.length"><p v-for="router in context.routers" :key="router.id" class="migration-help">{{ router.name }} · {{ router.state }} · {{ connectionPhysical(router.migrationnetworkid) }}</p></template><span v-else>—</span></a-descriptions-item>
          </a-descriptions>
          <p v-if="context.previous" class="migration-help">{{ $t('label.network.migration.previous') }}: {{ physicalName(context.previous.physicalnetworkid) }} · {{ context.previous.state }} · {{ context.previous.id }}</p>
        </section>
        <section class="migration-section">
          <h3>{{ $t('label.network.migration.destination') }}</h3>
          <a-form layout="vertical">
            <a-form-item :label="$t('label.network.offering')">
              <div class="migration-select">
                <a-select :value="targetId || undefined" show-search option-filter-prop="label" :placeholder="$t('message.network.migration.select')" :disabled="context.checkpoint || busy || pending" :aria-label="$t('label.network.migration.destination')" @change="selectTarget">
                  <a-select-option v-if="context.checkpoint" :value="context.offering.id" :label="context.offering.name">{{ context.offering.name }}</a-select-option>
                  <template v-else><a-select-option v-for="candidate in context.candidates" :key="candidate.offering.id" :value="candidate.offering.id" :label="candidate.offering.name">{{ candidate.offering.name }} · {{ candidate.physical.name }}</a-select-option></template>
                </a-select>
                <a-tooltip :title="$t('label.refresh')"><a-button :loading="loading" :disabled="submitting || checking" :aria-label="$t('label.network.migration.refresh')" @click="load"><reload-outlined /></a-button></a-tooltip>
              </div>
            </a-form-item>
          </a-form>
          <p v-if="!context.checkpoint && !context.candidates.length" class="migration-help">{{ $t('message.network.migration.empty') }}</p>
          <p v-if="target" class="migration-help">{{ $t('label.physical.network') }}: {{ target.physical?.name }} · {{ target.physical?.id }}</p>
          <p v-if="providerStatus" :class="['migration-help', { 'migration-error': providerStatus === 'failed' }]" role="status">{{ $t('message.network.migration.provider.' + providerStatus) }}</p>
        </section>
        <section class="migration-section">
          <h3>{{ $t('label.network.migration.connections') }} ({{ vmRows.length }})</h3>
          <a-table class="migration-vms" size="small" :columns="vmColumns" :data-source="vmRows" :pagination="false" row-key="key" :scroll="{ x: 620 }">
            <template #bodyCell="{ column, record }">
              <router-link v-if="column.key === 'name'" :to="'/vm/' + record.id" @click="$emit('close-action')">{{ record.name }}</router-link>
              <span v-else>{{ record[column.key] }}</span>
            </template>
          </a-table>
        </section>
        <section class="migration-section migration-impact">
          <h3>{{ $t('label.network.migration.impact') }}</h3>
          <p class="migration-help">{{ $t('message.network.migration.impact') }}</p>
          <p class="migration-help">{{ $t('message.network.migration.failure') }}</p>
          <a-checkbox :checked="acknowledged" :disabled="busy || pending || !!reason" @change="e => acknowledged = e.target.checked">{{ $t('message.network.migration.acknowledge') }}</a-checkbox>
        </section>
      </template>
    </a-spin>
    <template #footer>
      <a-button :disabled="submitting" @click="close">{{ $t('label.close') }}</a-button>
      <a-button v-if="pending" type="primary" :loading="checking" :disabled="submitting || loading || scopeChanged" @click="poll(generation)">{{ $t('label.network.migration.recheck') }}</a-button>
      <a-button v-else type="primary" :loading="submitting" :disabled="!canStart" @click="start">{{ $t(context?.checkpoint ? 'label.network.migration.resume' : 'label.network.migration.start') }}</a-button>
    </template>
  </MoldDialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog'
import { getAPI, postAPI } from '@/api'
import { networkOfferingFingerprint } from '@/utils/networkOfferingChange'
import { migrationVisible, migrationNetworkReason, readMigrationContext, migrationTargetDiagnostic, migrationFingerprint, migrationParams, queryMigrationJob, migrationSucceeded } from '@/utils/networkMigration'

export default {
  name: 'MigrateNetwork',
  components: { MoldDialog },
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () { return { context: null, targetId: '', acknowledged: false, loading: false, submitting: false, checking: false, readReady: false, unknown: false, verified: false, error: '', providerStatus: '', job: null, attempt: null, pollFailures: 0, generation: 0, diagnosticGeneration: 0, timer: null, loadScope: '', scopeChanged: false, destroyed: false } },
  computed: {
    scope () { return JSON.stringify([this.resource.id, this.resource.projectid, this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, migrationVisible(this.resource, this.$store.getters)]) },
    target () { return this.context?.checkpoint ? { offering: this.context.offering, physical: this.context.physical.find(p => p.id === this.context.network.physicalnetworkid) } : this.context?.candidates.find(c => c.offering.id === this.targetId) },
    pending () { return this.job?.jobstatus === 0 },
    busy () { return this.loading || this.submitting || this.checking },
    reason () { return this.context ? migrationNetworkReason(this.context.network) || (this.context.vms.some(v => !['Running', 'Stopped'].includes(v.state)) ? 'vm.state' : '') || (this.context.routers?.some(r => !['Running', 'Stopped'].includes(r.state)) ? 'router.state' : '') : '' },
    canStart () { return this.readReady && !!this.target?.physical && !this.busy && !this.pending && !this.unknown && !this.scopeChanged && !this.reason && this.acknowledged && this.providerStatus === 'ready' && migrationVisible(this.context.network, this.$store.getters) },
    status () { return this.submitting ? 'submitting' : this.unknown ? 'unknown' : this.pending ? 'running' : this.job?.jobstatus === 2 ? 'failed' : this.verified ? 'success' : this.context?.checkpoint ? 'resume' : 'ready' },
    vmRows () {
      if (!this.context) return []
      return this.context.vms.flatMap(v => v.nic.filter(n => [this.context.network.id, this.context.previous?.id].includes(n.networkid)).map(n => ({ key: v.id + ':' + n.id, id: v.id, name: v.displayname || v.name, state: v.state, ip: n.ipaddress || '—', location: this.physicalName(n.networkid === this.context.network.id ? this.context.network.physicalnetworkid : this.context.previous.physicalnetworkid) })))
    },
    vmColumns () { return [{ key: 'name', title: this.$t('label.name') }, { key: 'state', title: this.$t('label.state') }, { key: 'ip', title: this.$t('label.ipaddress') }, { key: 'location', title: this.$t('label.physical.network') }] }
  },
  watch: {
    scope: { immediate: true, handler () { this.load() } },
    '$route.path' (path, previous) { if (path !== previous) { this.scopeChanged = true; this.invalidate(); this.$emit('close-action') } }
  },
  beforeUnmount () { this.destroyed = true; this.invalidate() },
  methods: {
    invalidate () { this.generation++; this.diagnosticGeneration++; clearTimeout(this.timer) },
    active (token) { return !this.destroyed && !this.scopeChanged && token === this.generation && this.loadScope === this.scope },
    errorText (error) { const message = error?.response?.data?.migratenetworkresponse?.errortext || error?.response?.data?.errorresponse?.errortext || error?.message || this.$t('message.request.failed'); return message.startsWith('message.network.migration.') ? this.$t(message) : message },
    physicalName (id) { return this.context?.physical.find(p => p.id === id)?.name || id || '—' },
    connectionPhysical (networkId) { return this.physicalName(networkId === this.context.previous?.id ? this.context.previous.physicalnetworkid : this.context.network.physicalnetworkid) },
    setContext (context) {
      this.context = context; this.acknowledged = false; this.readReady = true
      if (context.checkpoint) this.targetId = context.offering.id
      else if (!context.candidates.some(c => c.offering.id === this.targetId)) this.targetId = ''
    },
    async load () {
      this.invalidate(); const token = this.generation
      if (!migrationVisible(this.resource, this.$store.getters)) { this.scopeChanged = true; this.loading = false; this.$emit('close-action'); return }
      if (this.loadScope !== this.scope) { this.context = null; this.job = null; this.targetId = '' }
      this.loadScope = this.scope; this.loading = true; this.readReady = false; this.error = ''; this.unknown = false; this.verified = false; this.providerStatus = ''
      this.attempt = null; this.pollFailures = 0
      try {
        const context = await readMigrationContext(getAPI, this.resource.id, this.resource.projectid)
        if (!this.active(token)) return
        this.setContext(context); this.job = context.job
        if (this.job) await this.poll(token)
        else await this.diagnose(token)
      } catch (e) { if (this.active(token)) this.error = this.errorText(e) } finally { if (this.active(token)) this.loading = false }
    },
    async selectTarget (id) { this.targetId = id; this.acknowledged = false; await this.diagnose(this.generation) },
    async diagnose (token) {
      const sequence = ++this.diagnosticGeneration
      this.providerStatus = ''
      if (!this.target || this.pending) return
      this.providerStatus = 'checking'
      try {
        const result = await migrationTargetDiagnostic(getAPI, this.$store.getters, this.context, this.target.offering)
        if (!this.active(token) || sequence !== this.diagnosticGeneration) return
        this.providerStatus = result.network.id === this.target.physical?.id ? 'ready' : 'failed'
      } catch (e) { if (this.active(token) && sequence === this.diagnosticGeneration) this.providerStatus = 'failed' }
    },
    async start () {
      if (!this.canStart) return
      const expected = migrationFingerprint(this.context); const selected = this.target
      this.invalidate(); const token = this.generation
      this.submitting = true; this.error = ''; this.verified = false
      try {
        const fresh = await readMigrationContext(getAPI, this.resource.id, this.resource.projectid)
        if (!this.active(token)) return
        const target = fresh.checkpoint ? { offering: fresh.offering, physical: fresh.physical.find(p => p.id === fresh.network.physicalnetworkid) } : fresh.candidates.find(c => c.offering.id === selected.offering.id)
        if (migrationFingerprint(fresh) !== expected || !target || target.physical?.id !== selected.physical.id || networkOfferingFingerprint(target.offering) !== networkOfferingFingerprint(selected.offering)) throw new Error('message.network.migration.changed')
        const params = migrationParams(fresh, target)
        const diagnostic = await migrationTargetDiagnostic(getAPI, this.$store.getters, fresh, target.offering)
        if (!this.active(token)) return
        if (diagnostic.network.id !== target.physical.id) throw new Error('message.network.migration.changed')
        this.attempt = target
        let response
        try { response = await postAPI('migrateNetwork', { ...params, ignoreproject: true }, { preserveOnFailure: true }) } catch (e) {
          if (this.active(token)) this.unknown = !(e?.response?.data?.migratenetworkresponse?.errorcode || e?.response?.data?.errorresponse?.errorcode)
          throw e
        }
        if (!this.active(token)) return
        const id = response?.migratenetworkresponse?.jobid
        if (!id) { this.unknown = true; throw new Error('message.network.migration.receipt') }
        this.job = { jobid: id, jobstatus: 0 }; this.acknowledged = false; this.pollFailures = 0
        await this.poll(token)
      } catch (e) { if (this.active(token)) this.error = this.errorText(e) } finally { if (this.active(token)) this.submitting = false }
    },
    async poll (token) {
      if (!this.active(token) || !this.job?.jobid || this.checking) return
      clearTimeout(this.timer); this.checking = true
      try {
        const job = await queryMigrationJob(getAPI, this.resource.id, this.job.jobid)
        if (!this.active(token)) return
        this.job = job; this.unknown = false; this.pollFailures = 0
        if (job.jobstatus === 0) { this.timer = setTimeout(() => this.poll(token), 2000); return }
        const context = await readMigrationContext(getAPI, this.resource.id, this.resource.projectid)
        if (!this.active(token)) return
        this.setContext(context)
        if (job.jobstatus === 2) this.error = job.jobresult?.errortext || this.$t('message.request.failed')
        else {
          const result = job.jobresult?.network
          const expected = this.attempt || (result?.id === this.resource.id ? { offering: { id: result.networkofferingid }, physical: { id: result.physicalnetworkid } } : null)
          if (!expected || !migrationSucceeded(context, expected)) throw new Error('message.network.migration.result')
          this.verified = true; this.error = ''
        }
        await this.diagnose(token)
        if (this.active(token)) this.$emit('refresh-data')
      } catch (e) {
        if (this.active(token)) {
          this.unknown = true; this.error = this.errorText(e)
          // Source cleanup briefly swaps network UUIDs. Keep strict job identity
          // checks, and retry only this read before falling back to manual retry.
          if (this.pending && this.pollFailures++ < 3) this.timer = setTimeout(() => this.poll(token), 2000)
        }
      } finally { if (this.active(token)) this.checking = false }
    },
    close () { if (!this.submitting) this.$emit('close-action') }
  }
}
</script>
<style scoped lang="less">
.network-migration-dialog { font-size: 14px; color: var(--ui-text); }
.migration-help { margin: 8px 0; color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.migration-job { font-size: 14px; }
.migration-toolbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin: 12px 0; }
.migration-section { margin-top: 20px; min-width: 0; }
.migration-section h3 { color: var(--ui-text); font-size: 14px; font-weight: 600; margin-bottom: 12px; }
.migration-select { display: flex; gap: 8px; min-width: 0; }
.migration-select .ant-select { flex: 1; min-width: 0; }
.migration-vms { width: 100%; min-width: 0; }
.migration-error { color: var(--ui-error-text); }
.migration-impact { padding: 12px; border: 1px solid var(--ui-border); border-radius: 4px; background: var(--ui-bg-surface); }
.network-migration-dialog :deep(.ant-alert) { margin-bottom: 12px; }
.network-migration-dialog :deep(.ant-descriptions-item-content) { overflow-wrap: anywhere; }
@media (max-width: 767px) { .network-migration-dialog :deep(.ant-descriptions-item-label) { width: 100px; white-space: normal; } }
</style>
