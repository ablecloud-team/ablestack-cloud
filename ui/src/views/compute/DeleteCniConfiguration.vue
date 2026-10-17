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
  <MoldDialog class="cni-delete-dialog" size="lg" :title="$t('label.remove.cni.configuration')" :closable="!busy" :keyboard="!busy" @cancel="close" @keydown.ctrl.enter.stop.prevent>
    <div class="cni-delete-content" :aria-busy="busy">
      <a-alert type="warning" show-icon :message="$t('message.cni.delete.scope')" />
      <a-alert v-if="!supported" type="error" show-icon :message="$t('message.cni.delete.unsupported')" />
      <div class="detail-tab-toolbar">
        <strong>{{ $t('message.userdata.delete.count', { count: rows.length }) }}</strong>
        <span class="detail-tab-toolbar-context"><a-tooltip :title="$t('label.userdata.usage.refresh')"><a-button :aria-label="$t('label.userdata.usage.refresh')" :loading="referencePending" :disabled="busy || referencePending || !allowed || !active()" @click="loadReferences"><template #icon><reload-outlined /></template></a-button></a-tooltip></span>
      </div>
      <p v-if="started" role="status" aria-live="polite">{{ $t('message.userdata.delete.results', totals) }}</p>
      <a-alert v-if="!allowed || !active() || !rows.length" type="error" show-icon :message="$t('message.cni.delete.unavailable')" />
      <a-table :columns="columns" :data-source="rows" :row-key="row => row.target.id" :pagination="{ pageSize: 10, showSizeChanger: false }" :scroll="{ x: 760 }" size="small">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'name'"><strong>{{ record.target.name }}</strong><div>{{ record.target.id }}</div></template>
          <template v-else-if="column.key === 'owner'">{{ record.target.account }}<div>{{ record.target.domain || record.target.domainid }}</div><div v-if="record.target.projectid">{{ $t('label.project') }}: {{ record.target.project || record.target.projectid }}</div></template>
          <template v-else-if="column.key === 'status'"><a-badge :status="badgeStatus(record.status)" :text="$t('label.userdata.delete.state.' + record.status)" :data-delete-state="record.status" /></template>
          <template v-else-if="column.key === 'reason'">
            <div v-if="record.reason && ![$t('message.cni.delete.incomplete'), $t('message.cni.delete.inuse')].includes(record.reason)">{{ record.reason }}</div>
            <div v-if="record.references && record.references.clusters > 0">{{ $t('message.cni.delete.inuse') }}</div>
            <div v-if="record.references === null">{{ $t('message.cni.delete.checking') }}</div>
            <div v-else-if="record.references === false">{{ $t('message.cni.delete.incomplete') }}</div>
            <div v-for="(count, kind) in record.references || {}" :key="kind">
              <router-link v-if="!['success', 'absent'].includes(record.status)" :to="referenceLink(record.target, kind)" :aria-disabled="busy" :tabindex="busy ? -1 : 0" @click.capture="followReference($event)">{{ $t('label.cni.usage') }}: {{ count }}</router-link>
            </div>
          </template>
        </template>
      </a-table>
    </div>
    <template #footer>
      <a-button ref="cancel" :disabled="busy" @click="close">{{ $t(started ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button v-if="checkableRows.length" :disabled="busy || referencePending || !allowed || !active()" @click="checkUnknown">{{ $t('label.userdata.delete.check') }}</a-button>
      <a-button v-if="eligibleRows.length || busy" type="primary" danger :loading="busy" :disabled="busy || referencePending || !allowed || !active()" @click="execute">{{ $t(started ? 'label.userdata.delete.retry' : 'label.delete') }}</a-button>
    </template>
  </MoldDialog>
</template>
<script>
import { cniUsageCapability, cniUsageParams, cniUsageRows } from '@/utils/cniUsage'
import MoldDialog from '@/components/view/MoldDialog'
import { getAPI, postAPI } from '@/api'
import { cniDeleteParams, cniDeleteLookup, cniDeleteIdentity, cniDeleteError, cniListLocation } from '@/utils/cniDeletion'

export default {
  name: 'DeleteCniConfiguration',
  components: { MoldDialog },
  props: { currentAction: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () { return { rows: (this.currentAction.cniTargets || []).map(target => ({ target, status: cniDeleteParams(target) ? 'ready' : 'blocked', reason: '', references: null })), referencePending: false, busy: false, started: false, disposed: false, session: '' } },
  computed: {
    allowed () { return ['listCniConfiguration', 'deleteCniConfiguration'].every(api => api in this.$store.getters.apis) },
    supported () { return cniUsageCapability(this.$store.getters.apis) === 'ready' },
    context () { return this.sessionKey() },
    blocksNavigation () { return this.busy },
    eligibleRows () { return this.rows.filter(row => ['ready', 'failed'].includes(row.status) && row.references?.clusters === 0) },
    checkableRows () { return this.rows.filter(row => ['unknown', 'blocked'].includes(row.status) && cniDeleteParams(row.target)) },
    totals () { return { success: this.rows.filter(row => ['success', 'absent'].includes(row.status)).length, failed: this.rows.filter(row => ['failed', 'blocked'].includes(row.status)).length, unknown: this.rows.filter(row => row.status === 'unknown').length, pending: this.rows.filter(row => ['ready', 'checking', 'deleting'].includes(row.status)).length } },
    columns () { return [['name', 'label.name', 220], ['owner', 'label.account', 180], ['status', 'label.operation.status', 130], ['reason', 'label.userdata.delete.reason', 230]].map(([key, title, width]) => ({ key, title: this.$t(title), width })) }
  },
  watch: {
    context () { this.invalidate() },
    allowed (allowed) { if (!allowed) this.invalidate() },
    supported (supported) { if (!supported) this.invalidate() }
  },
  mounted () { this.session = this.sessionKey(); this.loadReferences(); this.$nextTick(() => this.$refs.cancel?.$el?.focus()); window.addEventListener('beforeunload', this.beforeUnload) },
  beforeUnmount () { this.disposed = true; window.removeEventListener('beforeunload', this.beforeUnload) },
  methods: {
    sessionKey () { return JSON.stringify([this.$store.getters.userInfo?.id, this.$store.state?.user?.token, this.$store.getters.project?.id, this.$store.getters.listAllProjects, this.$route.fullPath, this.currentAction.cniTargets?.map(target => [target.id, target.account, target.domainid, target.projectid])]) },
    active () { return !this.disposed && this.session === this.sessionKey() },
    invalidate () {
      for (const row of this.rows) {
        row.references = false
        if (!['success', 'absent'].includes(row.status)) { row.status = row.status === 'deleting' ? 'unknown' : 'blocked'; row.reason = this.$t('message.cni.delete.unavailable') }
      }
    },
    badgeStatus (state) { return { success: 'success', absent: 'success', failed: 'error', blocked: 'error', unknown: 'warning', checking: 'processing', deleting: 'processing' }[state] || 'default' },
    referenceLink (target) {
      return { path: '/cniconfiguration/' + target.id, query: { cnireturn: JSON.stringify(cniListLocation(this.$route).query), tab: 'cni.usage' } }
    },
    followReference (event) { if (this.busy) event.preventDefault(); else this.close() },
    async references (row) {
      if (!this.active() || !this.allowed || !this.supported) throw new Error(this.$t('message.cni.delete.incomplete'))
      const result = cniUsageRows(await getAPI('listKubernetesClusters', cniUsageParams(row.target, { pagesize: 1 }), { preserveOnFailure: true, timeout: 15000 }), row.target)
      if (!this.active() || !this.allowed || !this.supported) throw new Error(this.$t('message.cni.delete.unavailable'))
      row.references = { clusters: result.count }
      return result.count
    },
    async loadReferences () {
      if (this.busy || this.referencePending || !this.active()) return
      this.referencePending = true
      try {
        await Promise.all(this.rows.filter(row => !['success', 'absent'].includes(row.status)).map(async row => {
          row.references = null
          const state = await this.identity(row)
          if (!this.active()) return
          if (state === 'missing') { row.status = 'absent'; row.reason = this.$t('message.userdata.delete.already.absent'); return }
          if (state !== 'ready') { row.references = false; row.status = 'blocked'; row.reason = this.$t('message.userdata.delete.preflight.failed'); return }
          try {
            await this.references(row)
            const after = await this.identity(row)
            if (!this.active()) return
            if (after !== 'ready') throw new Error(this.$t('message.userdata.delete.preflight.failed'))
            row.status = row.references.clusters > 0 ? 'blocked' : 'ready'; row.reason = ''
          } catch (_) { if (this.active()) { row.references = false; row.status = 'blocked'; row.reason = this.$t('message.cni.delete.incomplete') } }
        }))
      } finally { this.referencePending = false }
    },
    async identity (row) {
      if (!this.active() || !this.allowed) return 'forbidden'
      const params = cniDeleteLookup(row.target)
      if (!params) return 'mismatch'
      try {
        let json; let byName = false
        try { json = await getAPI('listCniConfiguration', params, { preserveOnFailure: true, timeout: 15000 }) } catch (error) {
          // A removed UUID may be rejected by command parameter conversion. Confirm absence
          // through a complete owner-scoped name lookup instead of treating 431 as absence.
          if (cniDeleteError(error).code !== 431 || !this.active() || !this.allowed) throw error
          byName = true
          json = await getAPI('listCniConfiguration', cniDeleteLookup(row.target, true), { preserveOnFailure: true, timeout: 15000 })
        }
        return this.active() && this.allowed ? cniDeleteIdentity(json, row.target, byName) : 'forbidden'
      } catch (error) { return [401, 403, 432, 531].includes(cniDeleteError(error).code) ? 'forbidden' : 'error' }
    },
    async reconcile (row) {
      const status = await this.identity(row)
      if (!this.active()) return
      if (status === 'missing') { row.status = 'success'; row.reason = this.$t('message.userdata.delete.confirmed.absent') } else if (status === 'ready') { row.status = 'failed'; row.reason = [row.reason, this.$t('message.userdata.delete.confirmed.present')].filter(Boolean).join(' ') } else if (status === 'mismatch') { row.status = 'blocked'; row.reason = this.$t('message.userdata.delete.changed') } else { row.status = 'unknown'; row.reason = [row.reason, this.$t('message.userdata.delete.unknown')].filter(Boolean).join(' ') }
    },
    async execute () {
      if (this.busy || this.referencePending || !this.active() || !this.allowed || !this.supported || !this.eligibleRows.length) return
      this.busy = true; this.started = true
      const pending = [...this.eligibleRows]
      try {
        for (const row of pending) {
          if (!this.active() || !this.allowed) break
          row.status = 'checking'; row.reason = ''
          const status = await this.identity(row)
          if (!this.active() || !this.allowed) break
          if (status === 'missing') { row.status = 'absent'; row.reason = this.$t('message.userdata.delete.already.absent'); continue }
          if (status !== 'ready') { row.status = 'blocked'; row.reason = this.$t(status === 'mismatch' ? 'message.userdata.delete.changed' : 'message.userdata.delete.preflight.failed'); continue }
          try {
            row.references = null
            const count = await this.references(row)
            if (!this.active() || !this.allowed) break
            if (count > 0) { row.status = 'failed'; row.reason = this.$t('message.cni.delete.inuse'); continue }
            if (await this.identity(row) !== 'ready' || !this.active() || !this.allowed) { row.status = 'blocked'; row.reason = this.$t('message.userdata.delete.preflight.failed'); continue }
          } catch (_) { if (this.active()) { row.references = false; row.status = 'blocked'; row.reason = this.$t('message.cni.delete.incomplete') } continue }
          row.status = 'deleting'
          try {
            const json = await postAPI('deleteCniConfiguration', cniDeleteParams(row.target), { preserveOnFailure: true, timeout: 30000 })
            if (!this.active()) break
            if (json?.deletecniconfigurationresponse?.success === true) row.status = 'success'
            else { row.status = 'unknown'; row.reason = this.$t('message.userdata.delete.unknown'); await this.reconcile(row) }
          } catch (error) {
            if (!this.active()) break
            row.reason = cniDeleteError(error).reason
            // Even a server 530 can mean provider/transport ambiguity; read before retry.
            row.status = 'unknown'; await this.reconcile(row); if (this.active() && row.status === 'failed') { try { await this.references(row) } catch (_) { row.references = false } }
          }
        }
      } finally {
        if (!this.active() || !this.allowed) {
          for (const row of pending) {
            if (row.status === 'deleting') row.status = 'unknown'
            else if (['ready', 'checking'].includes(row.status)) row.status = 'blocked'
            if (['unknown', 'blocked'].includes(row.status)) row.reason = this.$t('message.cni.delete.unavailable')
          }
        }
        this.busy = false
      }
      if (this.active() && this.allowed && this.rows.every(row => ['success', 'absent'].includes(row.status))) this.close()
    },
    async checkUnknown () {
      if (this.busy || this.referencePending || !this.active() || !this.allowed) return
      this.busy = true
      try { for (const row of [...this.checkableRows]) { if (!this.active() || !this.allowed) break; row.reason = ''; await this.reconcile(row) } } finally { this.busy = false }
    },
    beforeUnload (event) { if (this.busy) { event.preventDefault(); event.returnValue = '' } },
    close () {
      if (this.busy) return
      if (!this.active()) { this.$emit('close-action'); return }
      const removed = this.rows.filter(row => ['success', 'absent'].includes(row.status)).map(row => row.target.id)
      const leavingDetail = this.$route.params?.id && removed.includes(this.$route.params.id)
      this.$emit('close-action', { started: this.started, removed, retained: this.rows.filter(row => !removed.includes(row.target.id)).map(row => row.target.id) })
      if (this.started && !leavingDetail) this.$emit('refresh-data')
    }
  }
}
</script>
<style scoped lang="less">
.cni-delete-dialog :deep(td) { overflow-wrap: anywhere; word-break: break-word; }
.cni-delete-content { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
.cni-delete-content > p { margin: 0; }
.cni-delete-content > .detail-tab-toolbar { margin-bottom: 0; }
</style>
