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
  <MoldDialog :visible="!closed" :title="$t('label.add.new.gateway')" size="md" focus-guard :closable="!busy" :keyboard="!busy" @cancel="close">
    <div class="gateway-target"><strong>{{ resource.name }}</strong><span>{{ resource.id }}</span><span>{{ resource.project || resource.account }} · {{ resource.zonename }}</span></div>
    <a-alert v-if="!allowed" type="warning" show-icon :message="$t('message.private.gateway.context')" role="alert" />
    <a-alert v-if="error" type="error" show-icon :message="$t('message.private.gateway.failed')" :description="error" role="alert" />
    <a-alert v-if="jobId || lastJobId" :type="busy ? 'info' : 'warning'" show-icon :message="$t('message.private.gateway.' + (busy ? 'pending' : jobId ? 'unknown' : 'failed.job'))" role="status">
      <template #description><div>{{ $t('label.job.id') }}: {{ jobId || lastJobId }}</div><div v-if="jobId && !busy">{{ $t('message.private.gateway.no.resubmit') }}</div></template>
    </a-alert>
    <div v-if="requestId && !jobId && !lastJobId" class="gateway-target">{{ $t('label.vpc.offering.operation.request') }}: {{ requestId }}</div>
    <a-button :loading="loading" :disabled="busy || !!jobId || !allowed" @click="load"><template #icon><reload-outlined /></template>{{ $t('label.private.gateway.reload') }}</a-button>
    <a-alert v-if="loaded && !loadError && mode === 'associated' && !networks.length" type="info" show-icon :message="$t('message.private.gateway.empty')" />
    <a-alert v-if="loaded && !loadError && mode === 'vlan' && !physical.length" type="info" show-icon :message="$t('message.private.gateway.validation.candidate')" />
    <a-spin :spinning="loading">
      <a-form ref="formRef" layout="vertical" :model="draft" :rules="rules" class="gateway-form" @keydown.ctrl.enter.prevent="submit" @finish="submit">
        <fieldset :disabled="fieldsDisabled" :inert="fieldsDisabled || undefined">
          <a-form-item v-if="admin" :label="$t('label.private.gateway.connection')">
            <a-radio-group v-model:value="draft.mode" :disabled="fieldsDisabled">
              <a-radio value="vlan">{{ $t('label.vlan') }}</a-radio><a-radio value="associated">{{ $t('label.associatednetwork') }}</a-radio>
            </a-radio-group>
          </a-form-item>
          <template v-if="mode === 'vlan'">
            <a-form-item :label="$t('label.physicalnetworkid')" name="physicalnetwork" required><a-select v-model:value="draft.physicalnetwork" :disabled="fieldsDisabled" :aria-label="$t('label.physicalnetworkid')" show-search option-filter-prop="label"><a-select-option v-for="p in physical" :key="p.id" :value="p.id" :label="p.name">{{ p.name }}</a-select-option></a-select></a-form-item>
            <a-form-item :label="$t('label.vlan')" name="vlan" required><a-input v-model:value="draft.vlan" :disabled="fieldsDisabled" :aria-label="$t('label.vlan')" /></a-form-item>
            <a-form-item v-if="bypassSupported" :label="$t('label.bypassvlanoverlapcheck')"><a-checkbox v-model:checked="draft.bypass" :disabled="fieldsDisabled" :aria-label="$t('label.bypassvlanoverlapcheck')" /></a-form-item>
          </template>
          <a-form-item v-else :label="$t('label.associatednetwork')" name="associatednetworkid" required><a-select v-model:value="draft.associatednetworkid" :disabled="fieldsDisabled" :aria-label="$t('label.associatednetwork')" show-search option-filter-prop="label"><a-select-option v-for="n in networks" :key="n.id" :value="n.id" :label="n.name">{{ n.name }}</a-select-option></a-select></a-form-item>
          <a-form-item :label="$t('label.ip')" name="ipaddress" required><a-input v-model:value="draft.ipaddress" :disabled="fieldsDisabled" :aria-label="$t('label.ip')" /></a-form-item>
          <a-form-item :label="$t('label.gateway')" name="gateway" required><a-input v-model:value="draft.gateway" :disabled="fieldsDisabled" :aria-label="$t('label.gateway')" /></a-form-item>
          <a-form-item :label="$t('label.netmask')" name="netmask" required><a-input v-model:value="draft.netmask" :disabled="fieldsDisabled" :aria-label="$t('label.netmask')" /></a-form-item>
          <a-alert v-if="addressWarning" type="warning" show-icon :message="$t('message.private.gateway.address.warning')" />
          <a-alert v-if="vlanWarning" type="warning" show-icon :message="$t('message.private.gateway.vlan.warning')" />
          <p class="gateway-hint">{{ $t('message.private.gateway.range') }}</p>
          <a-form-item :label="$t('label.sourcenat')"><a-checkbox v-model:checked="draft.nat" :disabled="fieldsDisabled" :aria-label="$t('label.sourcenat')" /></a-form-item>
          <a-form-item :label="$t('label.aclid')" name="acl" required :extra="$t('message.private.gateway.acl.default')"><a-select v-model:value="draft.acl" :disabled="fieldsDisabled" :aria-label="$t('label.aclid')" show-search option-filter-prop="label"><a-select-option v-for="a in acls" :key="a.id" :value="a.id" :label="a.name + ' ' + (a.description || '')">{{ a.name }} · {{ a.description }}</a-select-option></a-select></a-form-item>
        </fieldset>
      </a-form>
    </a-spin>
    <template #footer><MoldDialogActions :busy="busy" @keydown.ctrl.enter.prevent="submit"><a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button><a-button type="primary" :loading="busy" :disabled="!allowed || busy || storageError || (!jobId && (!loaded || !!loadError || !acls.length || (mode === 'associated' ? !networks.length : !physical.length)))" @click="submit">{{ $t(jobId ? 'label.network.lifecycle.recheck' : 'label.ok') }}</a-button></MoldDialogActions></template>
  </MoldDialog>
</template>
<script>
import { v4 as uuid } from 'uuid'
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { createJobTracker } from '@/utils/jobTracker'
import { privateGatewayApis, gatewayOwner, sameGatewayOwner, gatewayNetworks, gatewayAcls, gatewayErrors, gatewayParams, gatewayIp, gatewaySubnet, gatewayVlan } from '@/utils/privateGateway'

const blank = admin => ({ mode: admin ? 'vlan' : 'associated', physicalnetwork: '', vlan: '', associatednetworkid: '', ipaddress: '', gateway: '', netmask: '', nat: false, bypass: false, acl: '' })
export default {
  name: 'PrivateGatewayCreate',
  components: { MoldDialog, MoldDialogActions },
  props: { resource: { type: Object, required: true } },
  emits: ['cancel', 'success'],
  data: () => ({ draft: {}, physical: [], networks: [], acls: [], gateways: [], loaded: false, loading: false, saving: false, loadError: '', error: '', jobId: '', lastJobId: '', requestId: '', entityId: '', target: null, origin: '', entryKey: '', closed: false, disposed: false, tracker: null, storageError: false }),
  computed: {
    admin () { return this.$store.getters.userInfo?.roletype === 'Admin' && (this.$store.getters.apis?.createPrivateGateway?.params || []).some(p => p.name === 'vlan') },
    bypassSupported () { return this.admin && (this.$store.getters.apis.createPrivateGateway?.params || []).some(p => p.name === 'bypassvlanoverlapcheck') },
    mode () { return this.admin ? this.draft.mode : 'associated' },
    scope () {
      const u = this.$store.getters.userInfo || {}; const r = this.resource
      return JSON.stringify([r.id, r.zoneid, r.projectid, r.account, r.domainid, u.id, u.roleid, u.roletype, this.$store.getters.project?.id, this.$store.state?.user?.token, this.$route?.fullPath, privateGatewayApis.map(a => a in (this.$store.getters.apis || {}))])
    },
    storageKey () { const u = this.$store.getters.userInfo || {}; return 'vpc-private-gateway:v1:' + JSON.stringify([u.id, u.roleid, this.$store.getters.project?.id, this.resource.id, gatewayOwner(this.resource)]) },
    allowed () { const project = this.$store.getters.project?.id; return this.current() && !!this.$store.getters.userInfo?.id && (!project || String(project) === '0' || project === this.resource.projectid) && privateGatewayApis.every(a => a in (this.$store.getters.apis || {})) },
    busy () { return this.loading || this.saving },
    fieldsDisabled () { return this.busy || !!this.jobId || !this.allowed || !this.loaded || !!this.loadError || this.storageError },
    addressWarning () { return gatewayIp(this.draft.ipaddress) !== null && gatewaySubnet(this.draft.gateway, this.draft.netmask) !== null && gatewaySubnet(this.draft.ipaddress, this.draft.netmask) !== gatewaySubnet(this.draft.gateway, this.draft.netmask) },
    vlanWarning () { const v = gatewayVlan(this.draft.vlan); return this.mode === 'vlan' && /^\d+$/.test(v) && (Number(v) < 1 || Number(v) > 4094) },
    rules () {
      return Object.fromEntries(['physicalnetwork', 'vlan', 'associatednetworkid', 'ipaddress', 'gateway', 'netmask', 'acl'].map(field => [field, [{
        validator: () => {
          const failure = gatewayErrors({ ...this.draft, mode: this.mode }, this.resource, this, this.admin)[field]
          return failure ? Promise.reject(new Error(this.$t('message.private.gateway.validation.' + failure))) : Promise.resolve()
        }
      }]]))
    }
  },
  watch: {
    scope: { flush: 'sync', handler () { this.invalidate() } },
    draft: { deep: true, handler () { if (this.current() && !this.storageError) this.persist() } }
  },
  created () {
    this.origin = this.scope; this.entryKey = this.storageKey; this.draft = blank(this.admin)
    try {
      const saved = JSON.parse(sessionStorage.getItem(this.entryKey) || 'null')
      if (saved?.vpcid === this.resource.id) {
        this.draft = { ...this.draft, ...saved.draft }; this.jobId = saved.jobId || ''; this.lastJobId = saved.lastJobId || ''; this.requestId = saved.requestId || ''; this.entityId = saved.entityId || ''; this.target = saved.target; this.error = saved.error || ''
      }
    } catch (_) { this.storageError = true; this.error = this.$t('message.private.gateway.storage') }
    this.tracker = createJobTracker({
      interval: 1000,
      maxRetries: 2,
      maxDuration: 120000,
      onState: () => {},
      query: async id => {
        const j = await this.read('queryAsyncJobResult', { jobid: id })
        if (j?.jobid !== id || (j.cmd && !/CreatePrivateGateway(?:ByAdmin)?Cmd$/.test(j.cmd)) || (j.userid && j.userid !== this.$store.getters.userInfo.id)) throw new Error(this.$t('message.private.gateway.result'))
        return j
      }
    })
    eventBus.on('project-switch-start', this.invalidate)
    if (!this.jobId) this.load()
  },
  beforeUnmount () { this.disposed = true; eventBus.off('project-switch-start', this.invalidate); this.tracker.clear() },
  methods: {
    current () { return !this.closed && !this.disposed && this.origin === this.scope },
    invalidate () { if (!this.closed) { this.closed = true; if (this.tracker) this.tracker.clear(); this.$emit('cancel') } },
    close () { if (!this.busy) { this.persist(); this.invalidate() } },
    message (e) { const d = Object.values(e?.response?.data || {}).find(d => d?.errorcode); return [d?.errorcode, d?.errortext || e?.message || this.$t('message.request.failed')].filter(Boolean).join(': ') },
    persist () {
      if (!this.entryKey || !this.current()) return !this.storageError
      try { sessionStorage.setItem(this.entryKey, JSON.stringify({ vpcid: this.resource.id, draft: this.draft, jobId: this.jobId, lastJobId: this.lastJobId, requestId: this.requestId, entityId: this.entityId, target: this.target, error: this.error })); return true } catch (_) { this.storageError = true; this.error = this.$t('message.private.gateway.storage'); return false }
    },
    async read (api, params) {
      if (!this.current()) throw new Error('Context changed')
      const j = await getAPI(api, { ...params, ignoreproject: true }, { preserveOnFailure: true, backgroundJob: true, timeout: 15000 })
      if (!this.current()) throw new Error('Context changed')
      const r = j?.[api.toLowerCase() + 'response']
      if (!r || r.errorcode) throw new Error(this.$t('message.private.gateway.lookup'))
      return r
    },
    async rows (api, key, params = {}) {
      const all = []
      for (let page = 1; page <= 100; page++) {
        const r = await this.read(api, { ...gatewayOwner(this.resource), listall: true, ...params, page, pagesize: 100 })
        const rows = r[key] || []; if (!Array.isArray(rows)) throw new Error(this.$t('message.private.gateway.lookup'))
        all.push(...rows); if (rows.length < 100) return all
      }
      throw new Error(this.$t('message.private.gateway.lookup'))
    },
    async fresh () {
      const vpcs = await this.rows('listVPCs', 'vpc', { id: this.resource.id })
      const v = vpcs.find(v => v.id === this.resource.id && v.zoneid === this.resource.zoneid && sameGatewayOwner(v, this.resource) && v.state === 'Enabled')
      if (!v) throw new Error(this.$t('message.private.gateway.context'))
      const [nets, acls, gateways, physical] = await Promise.all([
        this.rows('listNetworks', 'network', { zoneid: v.zoneid, networkfilter: 'account' }),
        this.rows('listNetworkACLLists', 'networkacllist', { vpcid: v.id }),
        this.rows('listPrivateGateways', 'privategateway'),
        this.admin && 'listPhysicalNetworks' in this.$store.getters.apis ? this.rows('listPhysicalNetworks', 'physicalnetwork', { zoneid: v.zoneid }) : Promise.resolve([])
      ])
      return { networks: gatewayNetworks(nets, v), acls: gatewayAcls(acls, v), gateways: gateways.filter(g => sameGatewayOwner(g, v) && g.zoneid === v.zoneid), physical: physical.filter(p => p.zoneid === v.zoneid && p.state === 'Enabled') }
    },
    async load () {
      if (this.busy || !this.allowed || this.jobId) return
      this.loading = true
      if (this.loadError && this.error === this.loadError) this.error = ''
      this.loadError = ''
      try {
        const s = await this.fresh(); if (!this.current()) return
        Object.assign(this, s); this.loaded = true
        if (!this.draft.acl) this.draft.acl = this.acls.find(a => !a.vpcid && a.name === 'default_deny')?.id || ''
        if (!this.draft.physicalnetwork && this.physical.length === 1) this.draft.physicalnetwork = this.physical[0].id
        if (!this.acls.length) throw new Error(this.$t('message.private.gateway.acl.empty'))
      } catch (e) { if (this.current()) this.error = this.loadError = this.message(e) } finally { if (this.current()) { this.loading = false; this.persist() } }
    },
    matches (row) {
      const p = this.target
      return row?.id && (!this.entityId || row.id === this.entityId) && row.vpcid === this.resource.id && row.zoneid === this.resource.zoneid && sameGatewayOwner(row, this.resource) && row.ipaddress === p?.ipaddress && row.gateway === p.gateway && row.netmask === p.netmask && row.aclid === p.aclid && row.sourcenatsupported === p.sourcenatsupported && (p.associatednetworkid ? row.associatednetworkid === p.associatednetworkid : row.physicalnetworkid === p.physicalnetworkid && gatewayVlan(row.vlan) === gatewayVlan(p.vlan)) && row.state === 'Ready'
    },
    async checkJob () {
      if (this.busy || !this.allowed || !this.jobId) return
      this.saving = true; this.error = ''
      try {
        const job = await this.tracker.track(this.jobId, {}, true)
        if (!this.current()) return
        if (job.jobstatus === 1) {
          const row = job.jobresult?.privategateway
          if (!this.matches(row)) throw new Error(this.$t('message.private.gateway.result'))
          const actual = await this.rows('listPrivateGateways', 'privategateway', { id: row.id, vpcid: this.resource.id })
          if (actual.length !== 1 || !this.matches(actual[0])) throw new Error(this.$t('message.private.gateway.result'))
          sessionStorage.removeItem(this.entryKey); this.closed = true
          this.$message.success(this.$t('message.success.add.private.gateway')); this.$emit('success'); this.$emit('cancel')
        } else if (job.jobstatus === 2) {
          this.error = [job.jobresult?.errorcode || job.jobresultcode, job.jobresult?.errortext || this.$t('message.request.failed')].filter(Boolean).join(': ')
          this.lastJobId = this.jobId; this.jobId = ''
        } else this.error = this.$t('message.private.gateway.unknown')
      } catch (e) { if (this.current()) this.error = this.message(e) } finally { if (this.current()) { this.saving = false; this.persist() } }
    },
    async submit () {
      if (this.busy || !this.allowed || this.storageError) return
      if (this.jobId) return this.checkJob()
      if (!this.loaded || this.loadError) return
      this.saving = true; this.error = ''
      try {
        await this.$refs.formRef.validate(); if (!this.current()) return
        const fresh = await this.fresh(); if (!this.current()) return
        Object.assign(this, fresh)
        const errors = gatewayErrors({ ...this.draft, mode: this.mode }, this.resource, fresh, this.admin)
        if (Object.keys(errors).length) throw new Error(this.$t('message.private.gateway.validation.' + Object.values(errors)[0]))
        this.target = gatewayParams({ ...this.draft, mode: this.mode }, this.resource, this.admin, this.bypassSupported)
        this.requestId = this.jobId = uuid(); this.entityId = ''; this.lastJobId = ''
        if (!this.persist()) { this.jobId = ''; return }
        let response
        try { response = await postAPI('createPrivateGateway', { ...this.target, customjobid: this.jobId }, { preserveOnFailure: true, backgroundJob: true, timeout: 30000 }) } catch (e) {
          if (!this.current()) return
          const status = e?.response?.status
          if (status >= 400 && status < 500 && Object.values(e.response.data || {}).some(d => d?.errorcode)) this.jobId = ''
          throw e
        }
        if (!this.current()) return
        const receipt = response?.createprivategatewayresponse
        if (receipt?.jobid !== this.jobId) throw new Error(this.$t('message.private.gateway.unknown'))
        this.entityId = receipt.id || ''; this.persist()
      } catch (e) { if (this.current() && !e?.errorFields) this.error = this.message(e) } finally { if (this.current()) { this.saving = false; this.persist() } }
      if (this.current() && this.jobId) return this.checkJob()
    }
  }
}
</script>
<style scoped>
.gateway-target { display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; overflow-wrap: anywhere; }
.gateway-target span, .gateway-hint { opacity: .75; }
.gateway-target, .ant-alert { overflow-wrap: anywhere; }
.ant-alert, .gateway-form { margin-top: 16px; margin-bottom: 16px; }
.gateway-form fieldset { min-width: 0; border: 0; padding: 0; margin: 0; }
.gateway-form :deep(.ant-select) { width: 100%; min-width: 0; }
</style>
