// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <MoldDialog class="network-restart-dialog" size="lg" focus-guard :title="$t('label.restart.network')" :closable="!busy" :keyboard="!busy" @cancel="close">
    <p class="network-restart-help">{{ $t('message.network.restart.impact') }}</p>
    <a-alert v-if="scopeChanged" type="error" show-icon :message="$t('message.network.restart.scope')" role="alert" />
    <a-alert v-if="error" ref="errorAlert" type="error" show-icon :message="error" role="alert" />
    <a-alert v-if="rows.some(r => r.network.vpcid)" type="warning" show-icon :message="$t('message.network.restart.vpc')" />
    <a-form layout="vertical" class="network-restart-options">
      <a-row :gutter="12">
        <a-col :xs="24" :md="8" v-for="key in optionKeys" :key="key">
          <a-form-item :label="$t('label.network.restart.' + key)">
            <a-switch :id="'network-restart-' + key" :checked="options[key]" :disabled="busy || started || !capabilities[key] || (key === 'livepatch' && (options.cleanup || options.makeredundant))" :aria-label="$t('label.network.restart.' + key)" @change="value => changeOption(key, value)" />
          </a-form-item>
        </a-col>
      </a-row>
      <p class="network-restart-help">{{ $t('message.network.restart.options') }}</p>
      <p v-if="options.livepatch" class="network-restart-help">{{ $t('message.network.restart.patch.result') }}</p>
    </a-form>
    <div class="network-restart-toolbar">
      <strong role="status">{{ $t('label.total') }} {{ rows.length }} · {{ $t('label.network.restart.status.success') }} {{ succeeded }}</strong>
      <a-button v-if="!started" :loading="busy" :disabled="busy || scopeChanged" @click="refreshTargets">{{ $t('label.network.restart.refresh') }}</a-button>
    </div>
    <div class="network-restart-results" role="list" :aria-busy="busy">
      <section v-for="row in rows" :key="row.id" role="listitem" class="network-restart-result" :data-network-id="row.id" :data-status="row.status">
        <div><strong>{{ row.network.name || row.id }}</strong><div class="network-restart-help">{{ $t(networkTypeLabel(row.network.type)) }} · {{ $t(networkStateLabel(row.network.state)) }} · {{ row.network.account || row.network.project || row.network.domainpath || row.network.domain || '—' }}</div><small class="network-restart-help">{{ row.id }}</small></div>
        <div><a-tag :color="row.status === 'success' ? 'green' : ['failed', 'unknown'].includes(row.status) ? 'red' : 'blue'">{{ $t('label.network.restart.status.' + row.status) }}</a-tag></div>
        <div>
          <div v-if="row.error || reason(row)" role="alert" class="network-restart-error">{{ row.error || reason(row) }}</div>
          <div v-if="row.jobId" class="network-restart-help">{{ $t('label.network.restart.job') }}: {{ row.jobId }}</div>
          <a-button v-if="row.status === 'unknown' && row.jobId" size="small" :disabled="busy || scopeChanged" @click="recheck(row)">{{ $t('label.network.restart.recheck') }}</a-button>
        </div>
      </section>
    </div>
    <template #footer>
      <a-button ref="closeButton" :disabled="busy" @click="close">{{ $t('label.close') }}</a-button>
      <a-button v-if="!started" ref="startButton" type="primary" :loading="busy" :disabled="!canStart" @click="run()">{{ $t('label.restart.network') }}</a-button>
      <a-button v-else-if="failedIds.length" type="primary" :loading="busy" :disabled="busy || unsettled || scopeChanged" @click="run(failedIds)">{{ $t('label.network.restart.retry.failed') }} ({{ failedIds.length }})</a-button>
    </template>
  </MoldDialog>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import { networkTypeLabel, networkStateLabel, networkPendingJobs, networkJob } from '@/utils/networkLifecycle'
import { networkRestartReason, networkRestartFingerprint, networkRestartCapabilities, networkRestartOptionReason, networkRestartParams, definitiveNetworkRejection } from '@/utils/networkRestart'

export default {
  name: 'RestartNetwork',
  components: { MoldDialog },
  props: { resource: { type: Object, default: () => ({}) }, currentAction: { type: Object, default: () => ({}) } },
  emits: ['close-action', 'refresh-data'],
  data () {
    const targets = this.currentAction.networkRestartTargets || [this.resource]
    return { rows: targets.filter(r => r?.id).map(r => ({ id: r.id, network: { ...r }, offering: null, routers: undefined, expected: '', status: 'idle', error: '', code: '', jobId: '', attempts: 0, jobs: [] })), options: { cleanup: false, makeredundant: false, livepatch: false }, optionKeys: ['cleanup', 'makeredundant', 'livepatch'], busy: false, loaded: false, started: false, error: '', generation: 0, alive: true, scopeChanged: false }
  },
  computed: {
    params () { return (this.$store.getters.apis.restartNetwork?.params || []).map(p => p.name) },
    scope () { const user = this.$store.getters.userInfo || {}; return JSON.stringify([this.$route.path, this.$store.getters.project?.id, user.id, user.roletype, user.roleid, user.domainid, this.$store.state?.user?.token, Object.keys(this.$store.getters.apis || {}).sort()]) },
    capabilities () {
      return Object.fromEntries(this.optionKeys.map(key => [key, this.loaded && this.rows.length > 0 && this.rows.every(row => networkRestartCapabilities(row, this.params, this.$config)[key])]))
    },
    canStart () { return this.params.includes('id') && this.loaded && this.rows.length > 0 && !this.busy && !this.started && !this.scopeChanged && this.rows.every(row => !row.error && !this.reason(row)) },
    succeeded () { return this.rows.filter(r => r.status === 'success').length },
    unsettled () { return this.rows.some(r => ['unknown', 'running', 'submitting'].includes(r.status)) },
    failedIds () { return this.rows.filter(r => r.status === 'failed').map(r => r.id) }
  },
  watch: {
    scope () { this.generation++; this.scopeChanged = true; this.busy = false; this.$emit('close-action') },
    error () { this.$nextTick(() => this.$refs.errorAlert?.$el?.scrollIntoView?.({ block: 'nearest' })) }
  },
  created () { this.refreshTargets() },
  beforeUnmount () { this.alive = false; this.generation++ },
  methods: {
    networkTypeLabel,
    networkStateLabel,
    active (token) { return this.alive && token === this.generation && !this.scopeChanged },
    errorText (e) { return Object.values(e?.response?.data || {}).find(response => response?.errortext)?.errortext || e?.message || this.$t('message.request.failed') },
    reason (row) {
      const key = networkRestartReason(row.network, this.$store.getters) || networkRestartOptionReason(row, this.options, this.params, this.$config) || (row.jobs?.length ? 'state' : '')
      return key ? this.$t('message.network.restart.reason.' + key) : ''
    },
    changeOption (key, value) {
      if (this.busy || this.started) return
      this.options[key] = value
      if (key === 'makeredundant' && value) this.options.cleanup = true
      if (this.options.cleanup || this.options.makeredundant) this.options.livepatch = false
    },
    async readTarget (row) {
      const json = await getAPI('listNetworks', { id: row.id, listall: true, ignoreproject: true, ...(row.network.projectid ? { projectid: row.network.projectid } : {}) }, { preserveOnFailure: true })
      const network = json?.listnetworksresponse?.network?.find(n => n.id === row.id)
      if (!network) throw new Error(this.$t('message.network.restart.reason.missing'))
      const apis = this.$store.getters.apis
      const [offeringJson, routerJson, jobs] = await Promise.all([
        'listNetworkOfferings' in apis ? getAPI('listNetworkOfferings', { id: network.networkofferingid, ignoreproject: true }, { preserveOnFailure: true }) : Promise.resolve(null),
        'listRouters' in apis ? getAPI('listRouters', { listall: true, ignoreproject: true, ...(network.projectid ? { projectid: network.projectid } : {}), ...(network.vpcid ? { vpcid: network.vpcid } : { networkid: row.id }) }, { preserveOnFailure: true }) : Promise.resolve(null),
        'listAsyncJobs' in apis ? networkPendingJobs(getAPI, row.id) : Promise.resolve([])
      ])
      const offering = offeringJson?.listnetworkofferingsresponse?.networkoffering?.find(o => o.id === network.networkofferingid)
      if (offeringJson && !offering) throw new Error(this.$t('message.network.restart.reason.lookup'))
      return { network, offering, routers: routerJson ? (routerJson.listroutersresponse?.router || []) : undefined, jobs }
    },
    async refreshTargets () {
      if (this.busy || this.started || this.scopeChanged) return
      const token = ++this.generation
      this.busy = true; this.error = ''
      const reads = await Promise.allSettled(this.rows.map(r => this.readTarget(r)))
      if (!this.active(token)) return
      this.rows.forEach((r, i) => {
        r.error = reads[i].status === 'rejected' ? this.errorText(reads[i].reason) : ''
        if (!r.error) { Object.assign(r, reads[i].value); r.expected = networkRestartFingerprint(r.network) }
      })
      this.loaded = true
      for (const row of this.rows) {
        const pending = row.jobs?.find(job => /(?:^|\.)RestartNetworkCmd$/.test(job.cmd || ''))
        if (pending && this.active(token)) { this.started = true; row.jobId = pending.jobid; await this.settle(row, token) }
      }
      if (!this.active(token)) return
      this.busy = false
      if (this.rows.some(r => r.error)) this.error = this.$t('message.network.restart.preflight')
      this.$nextTick(() => (this.canStart ? this.$refs.startButton : this.$refs.closeButton)?.$el?.focus())
    },
    async validateTarget (row) {
      const data = await this.readTarget(row)
      if (networkRestartFingerprint(data.network) !== row.expected) throw new Error(this.$t('message.network.restart.reason.changed'))
      const why = networkRestartReason(data.network, this.$store.getters) || networkRestartOptionReason(data, this.options, this.params, this.$config) || (data.jobs?.length ? 'state' : '')
      if (why) throw new Error(this.$t('message.network.restart.reason.' + why))
      return data
    },
    async run (ids) {
      if (this.busy || this.unsettled || this.scopeChanged || (!this.started && !this.canStart)) return
      const targets = this.started ? this.rows.filter(r => this.failedIds.includes(r.id) && ids?.includes(r.id)) : this.rows
      if (!targets.length) return
      const token = ++this.generation
      this.busy = true; this.error = ''
      try {
        const checks = await Promise.allSettled(targets.map(r => this.validateTarget(r)))
        if (!this.active(token)) return
        targets.forEach((r, i) => { r.error = checks[i].status === 'rejected' ? this.errorText(checks[i].reason) : ''; if (!r.error) Object.assign(r, checks[i].value) })
        if (targets.some(r => r.error)) { this.error = this.$t('message.network.restart.preflight'); return }
        this.started = true
        for (const row of targets) {
          if (!this.active(token)) return
          try {
            row.status = 'checking'; row.error = ''
            const data = await this.validateTarget(row)
            if (!this.active(token)) return
            Object.assign(row, data)
          } catch (e) {
            if (!this.active(token)) return
            row.status = 'failed'; row.error = this.errorText(e); continue
          }
          row.status = 'submitting'; row.jobId = ''; row.attempts++
          try {
            const json = await postAPI('restartNetwork', { ...networkRestartParams(row.id, this.options, this.params), ignoreproject: true, ...(row.network.projectid ? { projectid: row.network.projectid } : {}) }, { preserveOnFailure: true })
            if (!this.active(token)) return
            row.jobId = json?.restartnetworkresponse?.jobid || ''
            if (!row.jobId) { row.status = 'unknown'; row.error = this.$t('message.network.restart.receipt'); break }
          } catch (e) {
            if (!this.active(token)) return
            row.status = definitiveNetworkRejection(e) ? 'failed' : 'unknown'; row.error = this.errorText(e) + (row.status === 'unknown' ? ' ' + this.$t('message.network.restart.receipt') : ''); if (row.status === 'unknown') break; continue
          }
          await this.settle(row, token)
          if (row.status === 'unknown') break
        }
        if (this.active(token)) this.$emit('refresh-data')
      } finally {
        if (this.active(token)) {
          if (this.unsettled) this.rows.filter(r => r.status === 'idle').forEach(r => { r.status = 'failed'; r.error = this.$t('message.network.lifecycle.batch.wait') })
          this.busy = false
        }
      }
    },
    async settle (row, token) {
      row.status = 'running'; row.error = ''
      try {
        let result
        for (let reads = 0; reads < 150 && this.active(token); reads++) {
          result = await networkJob(getAPI, row.id, row.jobId)
          if (result.jobstatus !== 0) break
          await new Promise(resolve => setTimeout(resolve, 2000))
        }
        if (!this.active(token)) return
        if (result?.jobstatus === 2) { row.status = 'failed'; row.error = result.jobresult?.errortext || this.$t('message.request.failed'); return }
        if (result?.jobstatus !== 1) throw new Error(this.$t('message.network.restart.pending'))
        const data = await this.readTarget(row)
        if (!this.active(token)) return
        if (!['Implemented', 'Setup'].includes(data.network.state)) throw new Error(this.$t('message.network.restart.pending'))
        Object.assign(row, data); row.status = 'success'
      } catch (e) { if (this.active(token)) { row.status = 'unknown'; row.error = this.errorText(e) } }
    },
    async recheck (row) {
      if (this.busy || this.scopeChanged || row.status !== 'unknown' || !row.jobId) return
      const token = ++this.generation
      this.busy = true
      try { await this.settle(row, token); if (this.active(token)) this.$emit('refresh-data') } finally { if (this.active(token)) this.busy = false }
    },
    close () { if (!this.busy) this.$emit('close-action') }
  }
}
</script>

<style scoped lang="less">
.network-restart-help { color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.network-restart-options { margin-top: 16px; }
.network-restart-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 16px 0 12px; flex-wrap: wrap; }
.network-restart-results { display: flex; flex-direction: column; gap: 12px; }
.network-restart-result { display: grid; grid-template-columns: minmax(0, 1fr) 110px minmax(0, 1fr); gap: 12px; padding: 12px; border: 1px solid var(--ui-border); border-radius: 4px; background: var(--ui-bg-surface); overflow-wrap: anywhere; }
.network-restart-error { color: var(--ui-error-text); }
.network-restart-dialog :deep(.ant-alert) { margin-bottom: 12px; }
@media (max-width: 767px) { .network-restart-result { grid-template-columns: minmax(0, 1fr); } }
</style>
