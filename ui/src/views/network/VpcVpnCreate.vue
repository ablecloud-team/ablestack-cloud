<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->
<template>
  <MoldDialog :visible="!closed" :title="$t(kind === 'gateway' ? 'label.add.vpn.gateway' : 'label.create.vpn.connection')" size="md" focus-guard :closable="!busy" :keyboard="!busy" @cancel="close">
    <div class="vpc-vpn-target"><strong>{{ resource.name }}</strong><span>{{ resource.id }}</span><span>{{ $t(resource.projectid ? 'label.project' : 'label.account') }}: {{ resource.project || resource.account || '—' }}</span></div>
    <a-alert v-if="error && !jobId" type="error" show-icon :message="$t('message.vpc.vpn.failed')" :description="error" role="alert" />
    <a-alert v-if="jobId" :type="busy ? 'info' : 'warning'" show-icon :message="$t(busy ? 'message.vpc.vpn.pending' : 'message.vpc.vpn.unknown')" :description="jobId" role="status" />
    <a-button class="vpc-vpn-reload" :loading="loading" :disabled="busy || !!jobId || !allowed" @click="load"><template #icon><reload-outlined /></template>{{ $t('label.vpc.vpn.reload') }}</a-button>
    <a-spin :spinning="loading">
      <a-form
        ref="formRef"
        class="vpc-vpn-form"
        layout="vertical"
        :model="draft"
        :rules="rules"
        tabindex="-1"
        @keydown.ctrl.enter.prevent="submit"
        @finish="submit">
        <a-form-item v-if="kind === 'gateway'" :label="$t('label.ip')" name="ipaddress">
          <a-select v-model:value="draft.ipaddress" :disabled="fieldsDisabled" show-search option-filter-prop="label" :aria-label="$t('label.ip')">
            <a-select-option v-for="ip in ips" :key="ip.id" :value="ip.id" :label="ip.ipaddress">{{ ip.ipaddress }}</a-select-option>
          </a-select>
        </a-form-item>
        <template v-else>
          <a-form-item :label="$t('label.vpn.gateway')"><span>{{ gateway?.publicip || '—' }}</span></a-form-item>
          <a-form-item :label="$t('label.vpncustomergatewayid')" name="customer">
            <a-select v-model:value="draft.customer" :disabled="fieldsDisabled" show-search option-filter-prop="label" :aria-label="$t('label.vpncustomergatewayid')">
              <a-select-option v-for="row in customers" :key="row.id" :value="row.id" :label="row.name + ' · ' + row.gateway">{{ row.name }} · {{ row.gateway }}</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item :label="$t('label.vpc.vpn.passive')" :extra="$t('message.vpc.vpn.passive')" name="passive"><a-checkbox v-model:checked="draft.passive" :disabled="fieldsDisabled" :aria-label="$t('label.vpc.vpn.passive')">{{ $t('label.vpc.vpn.passive') }}</a-checkbox></a-form-item>
        </template>
        <a-alert v-if="loaded && !loadError && !available" type="info" show-icon :message="$t('message.vpc.vpn.unavailable')" />
      </a-form>
    </a-spin>
    <template #footer>
      <a-button :disabled="busy" @click="close">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="busy" :disabled="busy || !allowed || (!jobId && (!loaded || !!loadError || !available))" @click="submit">{{ $t(jobId ? 'label.network.lifecycle.recheck' : 'label.ok') }}</a-button>
    </template>
  </MoldDialog>
</template>
<script>
import { v4 as uuid } from 'uuid'
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import MoldDialog from '@/components/network/VpcDialog'
import { createJobTracker } from '@/utils/jobTracker'
import { vpcVpnApis, vpcVpnContext, vpcVpnOwner, vpcVpnIps, vpcVpnSameOwner } from '@/utils/vpcVpn'

export default {
  name: 'VpcVpnCreate',
  components: { MoldDialog },
  props: { resource: { type: Object, required: true }, kind: { type: String, required: true, validator: value => ['gateway', 'connection'].includes(value) } },
  emits: ['cancel', 'success'],
  data () { return { draft: { ipaddress: '', customer: '', passive: false }, ips: [], customers: [], gateway: null, loaded: false, loading: false, saving: false, loadError: '', error: '', jobId: '', entityId: '', target: null, origin: '', entryKey: '', closed: false, disposed: false, tracker: null } },
  computed: {
    scope () { return vpcVpnContext(this, this.kind) },
    storageKey () {
      const user = this.$store.getters.userInfo || {}
      return 'vpc-vpn-create:' + JSON.stringify([this.kind, this.resource.id, this.resource.projectid, this.resource.account, this.resource.domainid, user.id, user.roleid, user.roletype, this.$store.getters.project?.id])
    },
    command () { return this.kind === 'gateway' ? 'createVpnGateway' : 'createVpnConnection' },
    allowed () {
      const project = this.$store.getters.project?.id
      return this.current() && !!this.$store.getters.userInfo?.id && (!project || String(project) === '0' || project === this.resource.projectid) && vpcVpnApis(this.kind).every(api => api in (this.$store.getters.apis || {}))
    },
    busy () { return this.loading || this.saving },
    fieldsDisabled () { return this.busy || !!this.jobId || !this.allowed || !this.loaded || !!this.loadError },
    available () { return this.kind === 'gateway' ? this.ips.length > 0 : !!this.gateway && this.customers.length > 0 },
    rules () { return { [this.kind === 'gateway' ? 'ipaddress' : 'customer']: [{ required: true, message: this.$t('label.required') }] } }
  },
  watch: { scope: { flush: 'sync', handler () { this.invalidate() } } },
  created () {
    this.origin = this.scope; this.entryKey = this.storageKey
    try {
      const pending = JSON.parse(sessionStorage.getItem(this.entryKey) || 'null')
      if (pending?.vpcid === this.resource.id && /^[\da-f-]{36}$/i.test(pending.jobId)) {
        this.jobId = pending.jobId; this.entityId = pending.entityId || ''; this.draft = { ...this.draft, ...pending.draft }; this.target = pending.target
      }
    } catch (_) {}
    this.tracker = createJobTracker({
      interval: 1000,
      maxRetries: 2,
      maxDuration: 120000,
      onState: () => {},
      query: async id => {
        const json = await getAPI('queryAsyncJobResult', { jobid: id, ignoreproject: true }, { preserveOnFailure: true, timeout: 15000 })
        const job = json.queryasyncjobresultresponse
        if (!job || job.jobid !== id || (job.cmd && job.cmd.split('.').pop().toLowerCase() !== this.command.toLowerCase() + 'cmd')) throw new Error(this.$t('message.vpc.vpn.job.scope'))
        if (job.jobstatus === 1) {
          const row = job.jobresult?.[this.kind === 'gateway' ? 'vpngateway' : 'vpnconnection']
          if (!row?.id || (this.entityId && row.id !== this.entityId) || !vpcVpnSameOwner(row, this.resource) || (this.kind === 'gateway' ? row.vpcid !== this.resource.id : row.s2svpngatewayid !== this.target?.gateway || row.s2scustomergatewayid !== this.target?.customer)) throw new Error(this.$t('message.vpc.vpn.job.scope'))
        }
        return job
      }
    })
    eventBus.on('project-switch-start', this.invalidate)
    this.load()
  },
  beforeUnmount () { this.disposed = true; eventBus.off('project-switch-start', this.invalidate); this.tracker && this.tracker.clear() },
  methods: {
    current () { return !this.closed && !this.disposed && this.origin === this.scope },
    invalidate () { if (this.closed) return; this.closed = true; this.tracker && this.tracker.clear(); this.$emit('cancel') },
    message (error) { const detail = error?.response?.data?.errorresponse; return [detail?.errorcode, detail?.errortext || error?.message || this.$t('message.request.failed')].filter(Boolean).join(': ') },
    persist () { sessionStorage.setItem(this.entryKey, JSON.stringify({ vpcid: this.resource.id, jobId: this.jobId, entityId: this.entityId, draft: { ...this.draft }, target: this.target })) },
    clearReceipt () { this.jobId = ''; this.entityId = ''; this.target = null; try { sessionStorage.removeItem(this.entryKey) } catch (_) {} },
    async rows (api, key, params) {
      const rows = []
      for (let page = 1; page <= 100; page++) {
        const json = await getAPI(api, { ...vpcVpnOwner(this.resource), ignoreproject: true, listall: true, ...params, page, pagesize: 100 }, { preserveOnFailure: true, timeout: 15000 })
        if (!this.current()) return []
        const envelope = json?.[api.toLowerCase() + 'response']
        if (!envelope || envelope.errorcode) throw new Error(this.$t('message.vpc.vpn.lookup'))
        const result = envelope[key] || []; rows.push(...result)
        if (result.length < 100) return rows
      }
      throw new Error(this.$t('message.vpc.vpn.lookup'))
    },
    async fresh () {
      const vpcs = await this.rows('listVPCs', 'vpc', { id: this.resource.id })
      const vpc = vpcs.find(v => v.id === this.resource.id)
      if (!vpc || !vpcVpnSameOwner(vpc, this.resource) || (vpc.state && vpc.state !== 'Enabled')) throw new Error(this.$t('message.vpc.vpn.changed'))
      const gateways = (await this.rows('listVpnGateways', 'vpngateway', { vpcid: vpc.id })).filter(g => g.vpcid === vpc.id)
      if (this.kind === 'gateway') {
        if (gateways.length) throw new Error(this.$t('message.vpc.vpn.gateway.exists'))
        const ips = vpcVpnIps(await this.rows('listPublicIpAddresses', 'publicipaddress', { vpcid: vpc.id }), vpc.id)
        return { ips, gateway: null, customers: [] }
      }
      const customers = await this.rows('listVpnCustomerGateways', 'vpncustomergateway', {})
      const connections = await this.rows('listVpnConnections', 'vpnconnection', { vpcid: vpc.id })
      const attached = new Set(connections.map(row => row.s2scustomergatewayid))
      return { ips: [], gateway: gateways.length === 1 ? gateways[0] : null, customers: customers.filter(row => vpcVpnSameOwner(row, vpc) && !attached.has(row.id)).map(row => ({ id: row.id, name: row.name, gateway: row.gateway })) }
    },
    async load () {
      if (this.busy || !this.allowed) return
      if (this.jobId) return this.checkJob()
      this.loading = true; this.error = ''; this.loadError = ''
      try {
        const state = await this.fresh()
        if (!this.current()) return
        this.ips = state.ips; this.gateway = state.gateway; this.customers = state.customers; this.loaded = true
        if (!this.draft.ipaddress) this.draft.ipaddress = this.ips[0]?.id || ''
        if (!this.draft.customer) this.draft.customer = this.customers[0]?.id || ''
      } catch (error) { if (this.current()) this.error = this.loadError = this.message(error) } finally { if (this.current()) this.loading = false }
    },
    close () { if (!this.busy) { this.closed = true; this.$emit('cancel') } },
    async checkJob () {
      if (this.busy || !this.allowed || !this.jobId) return
      this.saving = true; this.error = ''
      const result = await this.tracker.track(this.jobId, {}, true)
      if (!this.current()) return
      this.saving = false
      if (result?.jobstatus === 1) { this.clearReceipt(); this.closed = true; this.$emit('success'); this.$emit('cancel') } else if (result?.jobstatus === 2) {
        this.error = [result.jobresult?.errorcode || result.jobresultcode, result.jobresult?.errortext || this.$t('message.request.failed')].filter(Boolean).join(': ')
        this.clearReceipt()
      } else this.error = this.$t('message.vpc.vpn.unknown')
    },
    async submit () {
      if (this.busy || !this.allowed) return
      if (this.jobId) return this.checkJob()
      if (!this.loaded || this.loadError || !this.available) return
      this.saving = true; this.error = ''
      try {
        await this.$refs.formRef.validate()
        if (!this.current()) return
        const state = await this.fresh()
        if (!this.current()) return
        const valid = this.kind === 'gateway' ? state.ips.some(ip => ip.id === this.draft.ipaddress) : this.gateway && state.gateway?.id === this.gateway.id && state.customers.some(row => row.id === this.draft.customer)
        if (!valid) throw new Error(this.$t('message.vpc.vpn.changed'))
        const params = this.kind === 'gateway' ? { vpcid: this.resource.id, ipaddressid: this.draft.ipaddress } : { s2svpngatewayid: state.gateway.id, s2scustomergatewayid: this.draft.customer, passive: !!this.draft.passive }
        this.target = { vpcid: this.resource.id, gateway: state.gateway?.id, customer: this.draft.customer, ipaddress: this.draft.ipaddress }
        this.jobId = uuid()
        try { this.persist() } catch (_) { this.clearReceipt(); throw new Error(this.$t('message.vpc.vpn.storage')) }
        let json
        try { json = await postAPI(this.command, { ...params, ...vpcVpnOwner(this.resource), ignoreproject: true, customjobid: this.jobId }, { preserveOnFailure: true }) } catch (error) {
          if (!this.current()) return
          if (error?.response?.data?.errorresponse || [400, 401, 403, 404].includes(error?.response?.status)) this.clearReceipt()
          throw error
        }
        if (!this.current()) return
        if (json?.errorresponse) { this.clearReceipt(); throw new Error(json.errorresponse.errortext) }
        const receipt = json?.[this.command.toLowerCase() + 'response']
        if (!receipt?.jobid || !/^[\da-f-]{36}$/i.test(receipt.jobid)) throw new Error(this.$t('message.vpc.vpn.unknown'))
        this.jobId = receipt.jobid; this.entityId = receipt.id || ''; this.persist()
      } catch (error) { if (this.current() && !error?.errorFields) this.error = this.message(error) } finally { if (this.current()) this.saving = false }
      if (this.current() && this.jobId) return this.checkJob()
    }
  }
}
</script>
<style scoped>
.vpc-vpn-target { display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; overflow-wrap: anywhere; }
.vpc-vpn-target span { opacity: .75; }
.vpc-vpn-reload, .ant-alert { margin-bottom: 16px; }
.vpc-vpn-form, .vpc-vpn-form :deep(.ant-select) { width: 100%; min-width: 0; }
</style>
