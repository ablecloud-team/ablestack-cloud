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
  <MoldDialog class="network-delete-dialog" size="lg" focus-guard :title="$t('label.action.delete.network')" :closable="!submitting" :keyboard="!submitting" @cancel="close">
    <p class="network-delete-help">{{ $t('message.network.lifecycle.delete.help') }}</p>
    <div class="network-delete-toolbar">
      <strong role="status">{{ $t('label.total') }} {{ rows.length }} · {{ $t('label.network.lifecycle.status.success') }} {{ removed.length }}</strong>
      <a-button :loading="busy" :disabled="busy || scopeChanged" @click="refresh">{{ $t('label.refresh') }}</a-button>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="error" role="alert" />
    <a-alert v-if="scopeChanged" type="error" show-icon :message="$t('message.network.lifecycle.scope')" role="alert" />
    <div class="network-delete-results" role="list" :aria-busy="busy">
      <section v-for="row in rows" :key="row.id" class="network-delete-result" role="listitem" :data-network-id="row.id" :data-status="row.status">
        <div class="network-delete-heading"><strong>{{ row.network.name || row.id }}</strong><a-tag :color="row.status === 'success' ? 'green' : ['failed', 'blocked', 'unknown'].includes(row.status) ? 'red' : 'blue'">{{ $t('label.network.lifecycle.status.' + row.status) }}</a-tag></div>
        <p class="network-delete-help">{{ $t(networkTypeLabel(row.network.type)) }} · {{ $t(networkStateLabel(row.network.state)) }} · {{ row.network.account || row.network.project || row.network.domain || '—' }}</p>
        <p class="network-delete-help">{{ row.id }}</p>
        <template v-if="row.loaded">
          <h3>{{ $t('label.network.lifecycle.dependencies') }}</h3>
          <p class="network-delete-help">{{ $t('message.network.lifecycle.dependencies.help') }}</p>
          <p v-if="row.vms === null" class="network-delete-help">{{ $t('message.network.lifecycle.dependencies.unavailable') }}</p>
          <p v-else-if="!row.vms.length" class="network-delete-help">{{ $t('message.network.lifecycle.dependencies.empty') }}</p>
          <ul v-else class="network-delete-vms"><li v-for="vm in row.vms" :key="vm.id"><router-link :to="'/vm/' + vm.id" @click="close">{{ vm.displayname || vm.name }}</router-link> · {{ vm.state }}<div class="network-delete-help">{{ vm.id }}<span v-for="nic in vm.nic.filter(n => n.networkid === row.id)" :key="nic.id"> · NIC {{ nic.id }} · {{ nic.ipaddress || nic.ip6address || '—' }}</span></div></li></ul>
          <p class="network-delete-help">{{ $t('label.virtual.routers') }}: {{ row.routers === null ? $t('label.network.lifecycle.not.visible') : row.routers.map(r => r.name + ' (' + r.state + ')').join(', ') || '0' }}</p>
          <p class="network-delete-help">{{ $t('label.public.ip') }}: {{ row.ips === null ? $t('label.network.lifecycle.not.visible') : row.ips.map(ip => ip.ipaddress).join(', ') || '0' }}</p>
          <p v-if="row.network.associatednetworkid || (row.network.related && row.network.related !== row.id)" class="network-delete-help">{{ $t('label.network.lifecycle.related') }}: {{ row.network.associatednetworkid || row.network.related }}</p>
        </template>
        <p v-if="row.error" class="network-delete-error" role="alert">{{ row.error }}</p>
        <p v-else-if="reason(row)" class="network-delete-error">{{ reason(row) }}</p>
        <p v-if="row.code" class="network-delete-help">{{ $t('label.error.code') }}: {{ row.code }}</p>
        <p v-if="row.jobId" class="network-delete-help">{{ $t('label.network.restart.job') }}: {{ row.jobId }}</p>
        <a-button v-if="['unknown', 'running'].includes(row.status)" :disabled="busy || scopeChanged" @click="recheck(row)">{{ $t('label.network.lifecycle.recheck') }}</a-button>
      </section>
    </div>
    <p class="network-delete-help">{{ $t('message.network.lifecycle.delete.physical') }}</p>
    <a-checkbox :checked="acknowledged" :disabled="busy || scopeChanged" @change="event => acknowledged = event.target.checked">{{ $t('message.network.lifecycle.delete.acknowledge') }}</a-checkbox>
    <template #footer>
      <a-button ref="closeButton" :disabled="submitting" @click="close">{{ $t('label.close') }}</a-button>
      <a-button v-if="!started" type="primary" danger :loading="busy" :disabled="!canStart" @click="run()">{{ $t(rows.some(row => row.lastDeletion?.jobstatus === 2) ? 'label.network.lifecycle.retry.failed' : 'label.action.delete.network') }}</a-button>
      <a-button v-else-if="retryIds.length" type="primary" danger :loading="busy" :disabled="busy || unsettled || scopeChanged || !acknowledged" @click="run(retryIds)">{{ $t('label.network.lifecycle.retry.failed') }} ({{ retryIds.length }})</a-button>
    </template>
  </MoldDialog>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import { networkTypeLabel, networkStateLabel, networkDeleteReason, networkDeleteFingerprint, readNetworkDeletion, networkError, networkJob, networkCollection } from '@/utils/networkLifecycle'

export default {
  name: 'DeleteNetwork',
  components: { MoldDialog },
  props: { resource: { type: Object, default: () => ({}) }, currentAction: { type: Object, default: () => ({}) } },
  emits: ['close-action', 'refresh-data'],
  data () {
    const targets = this.currentAction.networkDeletionTargets || [this.resource]
    return { rows: targets.filter(r => r?.id).map(network => ({ id: network.id, network: { ...network }, vms: null, routers: null, ips: null, jobs: [], loaded: false, expected: '', status: 'checking', error: '', code: '', jobId: '', attempts: 0 })), acknowledged: false, busy: false, submitting: false, started: false, error: '', generation: 0, alive: true, scopeChanged: false }
  },
  computed: {
    scope () { const user = this.$store.getters.userInfo || {}; return JSON.stringify([this.$route.path, this.$store.getters.project?.id, this.$store.getters.listAllProjects, user.id, user.roletype, user.roleid, user.domainid, this.$store.state?.user?.token, Object.keys(this.$store.getters.apis || {}).sort()]) },
    removed () { return this.rows.filter(r => r.status === 'success').map(r => r.id) },
    unsettled () { return this.rows.some(r => ['submitting', 'running', 'unknown'].includes(r.status)) },
    retryIds () { return this.rows.filter(r => ['failed', 'blocked'].includes(r.status)).map(r => r.id) },
    canStart () { return !this.started && !this.busy && !this.scopeChanged && this.acknowledged && this.rows.some(r => r.loaded && !this.reason(r) && ['ready', 'failed'].includes(r.status)) }
  },
  watch: {
    scope () { this.scopeChanged = true; this.generation++; this.busy = false; this.$emit('close-action', this.result()) }
  },
  created () { this.refresh() },
  beforeUnmount () { this.alive = false; this.generation++ },
  methods: {
    networkTypeLabel,
    networkStateLabel,
    active (token) { return this.alive && token === this.generation && !this.scopeChanged },
    text (e) { const value = networkError(e).text; return value.startsWith('message.') ? this.$t(value) : value || this.$t('message.request.failed') },
    reason (row) {
      const why = networkDeleteReason(row.network, this.$store.getters) || (row.vms?.length ? 'vms' : '') || (row.jobs?.length ? 'busy' : '')
      return why ? this.$t('message.network.lifecycle.reason.' + why) : ''
    },
    async read (row) { return readNetworkDeletion(getAPI, row.network, this.$store.getters) },
    async refresh () {
      if (this.busy || this.scopeChanged) return
      const token = ++this.generation; this.busy = true; this.error = ''
      for (const row of this.rows.filter(r => r.status !== 'success')) {
        if (!this.active(token)) return
        if (row.jobId && ['unknown', 'running'].includes(row.status)) { await this.settle(row, token); continue }
        try {
          const data = await this.read(row)
          if (!this.active(token)) return
          Object.assign(row, data, { loaded: true, expected: networkDeleteFingerprint(data.network) })
          const pending = data.jobs.find(job => /(?:^|\.)DeleteNetworkCmd$/.test(job.cmd || ''))
          if (pending || data.lastDeletion?.jobstatus === 1) {
            this.started = true; row.jobId = (pending || data.lastDeletion).jobid; await this.settle(row, token)
          } else if (data.lastDeletion?.jobstatus === 2 && !row.attempts && row.status !== 'unknown') {
            const failed = await networkJob(getAPI, row.id, data.lastDeletion.jobid)
            if (!this.active(token)) return
            if (failed.jobstatus !== 2) throw new Error('Network failure history changed')
            row.jobId = failed.jobid; row.code = failed.jobresult?.errorcode || failed.jobresultcode || ''
            row.error = failed.jobresult?.errortext || this.$t('message.request.failed')
            row.status = this.reason(row) ? 'blocked' : 'failed'
          } else if (row.status !== 'unknown') {
            const failed = row.status === 'failed'
            if (!failed) { row.error = ''; row.code = '' }
            row.status = this.reason(row) ? 'blocked' : failed ? 'failed' : 'ready'
          }
        } catch (e) { if (this.active(token)) { row.error = this.text(e); row.status = row.status === 'unknown' ? 'unknown' : 'blocked' } }
      }
      if (this.active(token)) this.busy = false
    },
    async run (ids) {
      if (this.busy || this.unsettled || this.scopeChanged || !this.acknowledged || (!this.started && !this.canStart)) return
      const targets = this.started ? this.rows.filter(r => ids?.includes(r.id) && this.retryIds.includes(r.id)) : this.rows.filter(r => !['success', 'unknown', 'running'].includes(r.status))
      if (!targets.length) return
      const token = ++this.generation; this.busy = true; this.started = true; this.error = ''
      try {
        // A completed job is observed before sending the next delete. Do not replay
        // an entire batch after partial success or an ambiguous receipt.
        for (const row of targets) {
          if (!this.active(token)) return
          try {
            const data = await this.read(row)
            if (!this.active(token)) return
            const changed = row.expected && row.expected !== networkDeleteFingerprint(data.network)
            Object.assign(row, data, { loaded: true })
            if (changed) throw new Error('message.network.lifecycle.reason.changed')
            const pending = data.jobs.find(job => /(?:^|\.)DeleteNetworkCmd$/.test(job.cmd || ''))
            if (pending) { row.jobId = pending.jobid; await this.settle(row, token); if (row.status === 'unknown') break; continue }
            const why = this.reason(row)
            if (why) { row.status = 'blocked'; row.error = why; continue }
          } catch (e) { if (!this.active(token)) return; row.status = 'blocked'; row.error = this.text(e); continue }
          row.status = 'submitting'; row.error = ''; row.code = ''; row.jobId = ''; row.attempts++; this.submitting = true
          try {
            const json = await postAPI('deleteNetwork', { id: row.id, ignoreproject: true, ...(row.network.projectid ? { projectid: row.network.projectid } : {}) }, { preserveOnFailure: true })
            if (!this.active(token)) return
            row.jobId = json?.deletenetworkresponse?.jobid || ''
            if (!row.jobId) { row.status = 'unknown'; row.error = this.$t('message.network.lifecycle.receipt'); break }
          } catch (e) {
            if (!this.active(token)) return
            const detail = networkError(e); row.status = detail.definitive ? 'failed' : 'unknown'; row.error = this.text(e); row.code = detail.code
            if (!detail.definitive) { row.error += ' ' + this.$t('message.network.lifecycle.receipt'); break }
            continue
          } finally { this.submitting = false }
          await this.settle(row, token)
          if (row.status === 'unknown') break
        }
        if (this.active(token)) this.$emit('refresh-data', this.result())
      } finally {
        if (this.active(token)) {
          if (this.unsettled) this.rows.filter(r => r.status === 'ready').forEach(r => { r.status = 'blocked'; r.error = this.$t('message.network.lifecycle.batch.wait') })
          this.busy = false
        }
      }
    },
    async settle (row, token) {
      row.status = 'running'; let failures = 0; let completedReads = 0
      for (let reads = 0; reads < 150 && this.active(token); reads++) {
        try {
          const job = await networkJob(getAPI, row.id, row.jobId)
          if (!this.active(token)) return
          failures = 0
          if (job.jobstatus === 2) { row.status = 'failed'; row.code = job.jobresult?.errorcode || job.jobresultcode || ''; row.error = job.jobresult?.errortext || this.$t('message.request.failed'); return }
          if (job.jobstatus === 1) {
            const networks = await networkCollection(getAPI, 'listNetworks', 'network', { id: row.id, listall: true, ignoreproject: true, ...(row.network.projectid ? { projectid: row.network.projectid } : {}) })
            if (!this.active(token)) return
            if (!networks.length) { row.status = 'success'; row.error = ''; return }
            if (networks.some(n => n.id !== row.id)) throw new Error('Network scope mismatch')
            if (++completedReads >= 3) { row.status = 'unknown'; row.error = this.$t('message.network.lifecycle.pending'); return }
          }
        } catch (e) { if (!this.active(token)) return; row.error = this.text(e); if (++failures >= 3) break }
        await new Promise(resolve => setTimeout(resolve, 2000))
      }
      if (this.active(token)) { row.status = 'unknown'; row.error = (row.error ? row.error + ' ' : '') + this.$t('message.network.lifecycle.pending') }
    },
    async recheck (row) {
      if (this.busy || this.scopeChanged || !['unknown', 'running'].includes(row.status)) return
      if (!row.jobId) return this.refresh()
      const token = ++this.generation; this.busy = true
      try { await this.settle(row, token); if (this.active(token)) this.$emit('refresh-data', this.result()) } finally { if (this.active(token)) this.busy = false }
    },
    result () { return { started: this.started, removed: this.removed, retained: this.rows.filter(r => r.status !== 'success').map(r => r.id) } },
    close () { if (!this.submitting) { this.generation++; this.$emit('close-action', this.result()) } }
  }
}
</script>

<style scoped lang="less">
.network-delete-dialog { font-size: 14px; }
.network-delete-help { color: var(--ui-text-secondary); overflow-wrap: anywhere; margin: 8px 0; font-size: 14px; }
.network-delete-toolbar, .network-delete-heading { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.network-delete-toolbar { margin: 16px 0; }
.network-delete-results { display: flex; flex-direction: column; gap: 12px; }
.network-delete-result { padding: 16px; border: 1px solid var(--ui-border); border-radius: 6px; background: var(--ui-bg-surface); min-width: 0; overflow-wrap: anywhere; }
.network-delete-result h3 { font-size: 14px; color: var(--ui-text-primary); margin: 16px 0 8px; }
.network-delete-error { color: var(--ui-error-text); overflow-wrap: anywhere; }
.network-delete-vms { margin: 0; padding-left: 20px; }
.network-delete-dialog :deep(.ant-alert) { margin-bottom: 12px; }
.network-delete-dialog :deep(.ant-checkbox-wrapper) { color: var(--ui-text-primary); font-size: 14px; }
</style>
