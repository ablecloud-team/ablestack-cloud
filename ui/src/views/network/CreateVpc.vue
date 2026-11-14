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
  <MoldDialog
    class="vpc-create-dialog"
    size="md"
    form-actions
    focus-guard
    :title="$t('label.add.vpc')"
    :closable="!submitting"
    :keyboard="!submitting"
    @cancel="closeAction">
    <a-alert v-if="error" type="error" show-icon :message="error" role="alert" />
    <template v-if="pending">
      <a-alert type="warning" show-icon :message="$t('message.vpc.create.receipt')" role="status" />
      <div class="vpc-create-target"><strong>{{ pending.params.name }}</strong><span>{{ pending.params.zoneid }} · {{ pending.params.vpcofferingid }}</span><span>{{ pending.params.projectid || pending.params.account }} · {{ pending.params.domainid || '—' }}</span><span>{{ $t('label.job.id') }}: {{ pending.id }}</span></div>
    </template>
    <a-form v-show="!pending" ref="formRef" :model="form" :rules="rules" layout="vertical" @keydown.ctrl.enter.prevent.stop="handleSubmit">
      <div class="vpc-create-toolbar"><span role="status">{{ $t('message.vpc.create.' + (loadingZones || loadingOffering ? 'loading' : loadReason || 'review')) }}</span><a-button :loading="loadingZones || loadingOffering" :disabled="submitting || loadingZones || loadingOffering" @click="reload">{{ $t('label.retry') }}</a-button></div>
      <a-alert v-if="loadReason" type="warning" show-icon :message="$t('message.vpc.create.' + loadReason)" role="alert" />
      <a-form-item name="name" :label="$t('label.name')"><a-input v-model:value="form.name" :disabled="submitting" :aria-label="$t('label.name')" /></a-form-item>
      <a-form-item name="displaytext" :label="$t('label.displaytext')"><a-input v-model:value="form.displaytext" :disabled="submitting" :aria-label="$t('label.displaytext')" /></a-form-item>
      <a-form-item name="zoneid" :label="$t('label.zoneid')">
        <a-select :value="form.zoneid || undefined" :loading="loadingZones" :disabled="submitting || loadingZones || !permitted" :aria-label="$t('label.zoneid')" show-search option-filter-prop="label" @change="changeZone"><a-select-option v-for="z in zones" :key="z.id" :value="z.id" :label="z.name">{{ z.name }}</a-select-option></a-select>
      </a-form-item>
      <OwnershipSelection v-if="selectOwner" embedded safe-scope :disabled="submitting" @fetch-owner="fetchOwnerOptions" />
      <p v-else class="vpc-create-help">{{ $t('label.account') }}: {{ activeProject ? ($store.getters.project?.name || activeProject) : $store.getters.userInfo?.account }}</p>
      <a-form-item name="vpcofferingid" :label="$t('label.vpcoffering')">
        <a-select :value="form.vpcofferingid || undefined" :loading="loadingOffering" :disabled="submitting || loadingOffering || !loaded" :aria-label="$t('label.vpcoffering')" show-search option-filter-prop="label" @change="handleVpcOfferingChange"><a-select-option v-for="r in offeringRows" :key="r.offering.id" :value="r.offering.id" :label="r.offering.name" :disabled="!!r.reason">{{ r.offering.name }}{{ r.reason ? ' · ' + $t('message.vpc.create.reason.' + r.reason) : '' }}</a-select-option></a-select>
      </a-form-item>
      <details v-if="unavailable.length" class="vpc-create-help"><summary>{{ $t('label.vpc.create.unavailable') }} ({{ unavailable.length }})</summary><ul><li v-for="r in unavailable" :key="r.offering.id">{{ r.offering.name }}: {{ $t('message.vpc.create.reason.' + r.reason) }}<span v-for="(issue, i) in r.diagnostic.issues" :key="i"> · {{ issue.service }} / {{ issue.provider }}: {{ $t('message.network.diagnostics.' + issue.reason) }}</span></li></ul></details>
      <template v-if="choice">
        <div class="vpc-create-target"><strong>{{ choice.offering.name }}</strong><span>{{ choice.offering.id }}</span><span>{{ choice.offering.internetprotocol || 'IPv4' }} · {{ mode }} / {{ routing }} · {{ choice.offering.state }}</span><span>{{ services }}</span></div>
        <a-alert :type="choice.diagnostic.status === 'ready' && !choice.unverified ? 'success' : 'warning'" show-icon :message="$t('message.vpc.create.diagnostic.' + (choice.unverified ? 'advanced' : choice.diagnostic.status))" :description="choice.diagnostic.network ? choice.diagnostic.network.name + ' · ' + choice.diagnostic.network.id : undefined" />
        <p class="vpc-create-help">{{ $t('message.network.diagnostics.configuration.only') }}</p>
        <a-form-item v-if="!fields.routed || admin" name="cidr" :label="$t('label.cidr')"><a-input v-model:value="form.cidr" :disabled="submitting" :aria-label="$t('label.cidr')" /></a-form-item>
        <a-form-item v-if="fields.routed" name="cidrsize" :label="$t('label.cidrsize')"><a-input-number v-model:value="form.cidrsize" :disabled="submitting" :min="1" :max="32" :aria-label="$t('label.cidrsize')" /></a-form-item>
        <a-form-item name="networkdomain" :label="$t('label.networkdomain')"><a-input v-model:value="form.networkdomain" :disabled="submitting" :aria-label="$t('label.networkdomain')" /></a-form-item>
        <a-form-item v-if="fields.asNumber" name="asnumber" :label="$t('label.asnumber')"><a-select :value="form.asnumber" :loading="asLoading" :disabled="submitting || asLoading" :aria-label="$t('label.asnumber')" show-search option-filter-prop="label" @change="value => form.asnumber = value"><a-select-option v-for="a in asNumbers" :key="a.id" :value="Number(a.asnumber)" :label="String(a.asnumber)">{{ a.asnumber }}</a-select-option></a-select><p v-if="!asLoading && !asNumbers.length" class="vpc-create-help" role="alert">{{ $t('message.vpc.create.as.empty') }}</p></a-form-item>
        <a-form-item v-if="fields.mtu" name="publicmtu" :label="$t('label.publicmtu')"><a-input-number v-model:value="form.publicmtu" :disabled="submitting" :min="68" :max="Number(zone.routerpublicinterfacemaxmtu || 1500)" :aria-label="$t('label.publicmtu')" /></a-form-item>
        <a-form-item v-if="fields.resolver" :label="$t('label.use.router.ip.resolver')"><a-switch :checked="!!form.userouteripresolver" :disabled="submitting" :aria-label="$t('label.use.router.ip.resolver')" @change="changeResolver" /></a-form-item>
        <a-row v-if="fields.dns && !form.userouteripresolver" :gutter="16"><a-col v-for="k in ['dns1', 'dns2']" :key="k" :xs="24" :sm="12"><a-form-item :name="k" :label="$t('label.' + k)"><a-input v-model:value="form[k]" :disabled="submitting" :aria-label="$t('label.' + k)" /></a-form-item></a-col></a-row>
        <a-row v-if="fields.dns && fields.ipv6" :gutter="16"><a-col v-for="k in ['ip6dns1', 'ip6dns2']" :key="k" :xs="24" :sm="12"><a-form-item v-if="apiParams[k]" :name="k" :label="$t('label.' + k)"><a-input v-model:value="form[k]" :disabled="submitting" :aria-label="$t('label.' + k)" /></a-form-item></a-col></a-row>
        <a-form-item v-if="fields.sourceNat" name="sourcenatipaddress" :label="$t('label.routerip')"><a-input v-model:value="form.sourcenatipaddress" :disabled="submitting" :aria-label="$t('label.routerip')" /></a-form-item>
        <a-form-item v-if="fields.mac" :label="$t('label.keep.mac.address.on.public.nic')"><a-switch v-model:checked="form.keepmacaddressonpublicnic" :disabled="submitting" :aria-label="$t('label.keep.mac.address.on.public.nic')" /></a-form-item>
        <a-form-item :label="$t('label.start')"><a-switch v-model:checked="form.start" :disabled="submitting" :aria-label="$t('label.start')" /></a-form-item>
        <a-alert v-if="routing === 'Dynamic'" type="info" show-icon :message="$t('message.vpc.create.bgp.policy')" />
        <a-checkbox v-if="choice.unverified" v-model:checked="advancedAcknowledged" :disabled="submitting">{{ $t('message.vpc.create.advanced.ack') }}</a-checkbox>
      </template>
    </a-form>
    <MoldDialogActions :busy="submitting" @keydown.ctrl.enter.prevent.stop="handleSubmit"><a-button :disabled="submitting" @click="closeAction">{{ $t('label.cancel') }}</a-button><a-button v-if="pending" type="primary" :loading="submitting" :disabled="submitting || storageError || !permitted" @click="recheck">{{ $t('label.network.restart.recheck') }}</a-button><a-button v-else type="primary" :loading="submitting" :disabled="!canSubmit" @click="handleSubmit">{{ $t('label.ok') }}</a-button></MoldDialogActions>
  </MoldDialog>
</template>
<script>
import { v4 as uuid } from 'uuid'
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import MoldDialog from '@/components/network/VpcDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import OwnershipSelection from '@/views/compute/wizard/OwnershipSelection'
import { networkCollection } from '@/utils/networkLifecycle'
import { isValidIPv4Cidr } from '@/utils/util'
import { vpcCreateApis, vpcCreateZoneAllowed, vpcCreateFields, vpcCreateMode, vpcCreateRouting, vpcCreateOfferingFingerprint, vpcCreateDiagnosticFingerprint, vpcCreateZoneFingerprint, resetVpcCreateFields, readVpcCreateChoices, readVpcCreateAsNumbers, buildVpcCreateParams } from '@/utils/vpcCreate'

export default {
  name: 'CreateVpc',
  inheritAttrs: false,
  components: { MoldDialog, MoldDialogActions, OwnershipSelection },
  emits: ['close-action', 'refresh-data'],
  data: () => ({ form: { name: '', displaytext: '', start: true, keepmacaddressonpublicnic: true }, owner: null, ownerReady: false, zones: [], offeringRows: [], asNumbers: [], loadingZones: false, loadingOffering: false, asLoading: false, submitting: false, loaded: false, loadReason: '', error: '', advancedAcknowledged: false, requestEpoch: 0, asEpoch: 0, zoneEpoch: 0, origin: '', entryKey: '', pending: null, storageError: false, disposed: false, closed: false }),
  computed: {
    admin () { return this.$store.getters.userInfo?.roletype === 'Admin' },
    activeProject () { const id = this.$store.getters.project?.id; return id && String(id) !== '0' ? id : '' },
    selectOwner () { return ['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo?.roletype) && !this.activeProject },
    apiParams () { return Object.fromEntries((this.$store.getters.apis?.createVPC?.params || []).map(p => [p.name, p])) },
    permitted () { return vpcCreateApis.every(a => this.$store.getters.apis?.[a]) },
    securityScope () { const g = this.$store.getters; const u = g.userInfo || {}; return JSON.stringify([this.$route.fullPath, u.id, u.roleid, u.roletype, this.$store.state?.user?.token, g.project?.id, [...vpcCreateApis, 'listProjects', 'listASNumbers', 'listPhysicalNetworks', 'listTrafficTypes', 'listNetworkServiceProviders', 'listVirtualRouterElements'].map(a => !!g.apis?.[a]), (g.apis?.createVPC?.params || []).map(p => p.name).sort()]) },
    contextScope () { return JSON.stringify([this.securityScope, this.owner, this.ownerReady, this.form.zoneid]) },
    storageKey () { const u = this.$store.getters.userInfo || {}; return 'vpc-create-receipt:v1:' + JSON.stringify([u.id, u.roleid, u.roletype, this.activeProject]) },
    zone () { return this.zones.find(z => z.id === this.form.zoneid) },
    choice () { return this.offeringRows.find(r => r.offering.id === this.form.vpcofferingid && !r.reason) },
    fields () { return vpcCreateFields(this.choice?.offering, this.zone, this.$store.getters) },
    mode () { return vpcCreateMode(this.choice?.offering) },
    routing () { return vpcCreateRouting(this.choice?.offering) },
    unavailable () { return this.offeringRows.filter(r => r.reason) },
    services () { return (this.choice?.offering.service || []).map(s => s.name + ': ' + (s.provider || []).map(p => p.name).join(', ')).join('; ') },
    canSubmit () { return this.current() && this.permitted && this.ownerReady && this.loaded && !!this.choice && !this.loadReason && !this.pending && !this.storageError && !this.loadingZones && !this.loadingOffering && !this.asLoading && !this.submitting && (!this.fields.asNumber || this.asNumbers.some(a => Number(a.asnumber) === Number(this.form.asnumber))) && (!this.choice.unverified || this.advancedAcknowledged) },
    rules () {
      const required = [{ required: true, message: this.$t('message.error.required.input') }]
      const cidr = { validator: (rule, value) => this.fields.routed && !value ? Promise.resolve() : isValidIPv4Cidr(rule, value) }
      return { name: required, zoneid: required, vpcofferingid: required, cidr: this.fields.routed ? [cidr] : [...required, cidr], displaytext: this.apiParams.displaytext?.required ? required : [] }
    }
  },
  watch: { securityScope: { flush: 'sync', handler () { this.invalidate() } } },
  created () {
    this.origin = this.securityScope; this.entryKey = this.storageKey
    try {
      const record = JSON.parse(sessionStorage.getItem(this.entryKey) || 'null')
      if (record) {
        if (record.version !== 1 || !record.id || !record.params?.name || record.userId !== this.$store.getters.userInfo.id) throw new Error('Invalid receipt')
        this.pending = record
      }
    } catch (_) { this.storageError = true; this.error = this.$t('message.vpc.create.storage') }
    eventBus.on('project-switch-start', this.invalidate)
    if (!this.pending) this.reload()
  },
  beforeUnmount () { this.disposed = true; ++this.requestEpoch; ++this.zoneEpoch; ++this.asEpoch; eventBus.off('project-switch-start', this.invalidate) },
  methods: {
    current () { return !this.closed && !this.disposed && this.origin === this.securityScope },
    invalidate () { if (this.closed) return; this.closed = true; ++this.requestEpoch; ++this.asEpoch; ++this.zoneEpoch; this.$emit('close-action') },
    closeAction () { if (!this.submitting) this.invalidate() },
    read (api, params, options = {}) { return getAPI(api, params, { ...options, preserveOnFailure: true, backgroundJob: true, timeout: 15000 }) },
    text (e) { const d = Object.values(e?.response?.data || {}).find(x => x?.errorcode); const message = d?.errortext || e?.message || this.$t('message.request.failed'); return message.startsWith('message.vpc.create.') ? this.$t(message) : [d?.errorcode, message].filter(Boolean).join(': ') },
    reason (e) { const d = Object.values(e?.response?.data || {}).find(x => x?.errorcode); return e?.response?.status === 403 || [401, 531].includes(d?.errorcode) ? 'permission' : 'lookup' },
    clearChoices (full = false) {
      ++this.requestEpoch; ++this.asEpoch; this.form.vpcofferingid = undefined; this.offeringRows = []; this.asNumbers = []; this.loaded = false; this.loadingOffering = false; this.asLoading = false; this.advancedAcknowledged = false
      resetVpcCreateFields(this.form, null, null, this.zone, this.$store.getters, full)
    },
    async reload () {
      if (!this.current() || this.submitting || this.pending || this.loadingZones || this.loadingOffering) return
      if (!this.permitted) { this.loadReason = 'permission'; return }
      if (!this.storageError) this.error = ''
      if (!this.selectOwner) {
        this.ownerReady = false
        if (this.activeProject) {
          if (!this.$store.getters.apis?.listProjects) { this.loadReason = 'permission'; return }
          try {
            const rows = await networkCollection(this.read, 'listProjects', 'project', { id: this.activeProject, listall: true, ignoreproject: true })
            if (!this.current()) return
            if (rows.length !== 1 || rows[0].id !== this.activeProject || !rows[0].domainid || rows[0].state !== 'Active') throw new Error('message.vpc.create.owner')
            this.owner = { projectid: rows[0].id, domainid: rows[0].domainid }
          } catch (e) { if (this.current()) { this.clearChoices(true); this.loadReason = 'owner'; this.error = this.text(e) } return }
        } else this.owner = { account: this.$store.getters.userInfo.account, domainid: this.$store.getters.userInfo.domainid }
        this.ownerReady = !!this.owner?.domainid
      }
      const epoch = ++this.zoneEpoch; this.loadingZones = true; this.clearChoices(true)
      try {
        const rows = await networkCollection(this.read, 'listZones', 'zone', { showicon: true, ignoreproject: true })
        if (!this.current() || epoch !== this.zoneEpoch) return
        this.zones = rows.filter(z => vpcCreateZoneAllowed(z, this.$store.getters))
        const selected = this.zones.find(z => z.id === this.form.zoneid) || this.zones[0]
        this.form.zoneid = selected?.id
        if (!selected) this.loadReason = rows.length ? 'zone' : 'zones.empty'
        else await this.fetchOfferings()
      } catch (e) { if (this.current() && epoch === this.zoneEpoch) { this.zones = []; this.form.zoneid = undefined; this.loadReason = this.reason(e); this.error = this.text(e) } } finally { if (this.current() && epoch === this.zoneEpoch) this.loadingZones = false }
    },
    async changeZone (id) { if (this.submitting || this.pending) return; this.form.zoneid = id; this.clearChoices(true); await this.fetchOfferings() },
    async fetchOwnerOptions (o) {
      if (!this.current() || this.submitting || this.pending) return
      const next = o.ready ? o.selectedAccountType === 'Project' ? { projectid: o.selectedProject, domainid: o.selectedDomain } : { account: o.selectedAccount, domainid: o.selectedDomain } : null
      if (this.ownerReady === !!o.ready && JSON.stringify(this.owner) === JSON.stringify(next)) return
      this.owner = next; this.ownerReady = !!o.ready; this.clearChoices(true)
      if (!this.ownerReady) { this.loadReason = 'owner'; return }
      if (this.zone) await this.fetchOfferings()
    },
    async fetchOfferings () {
      if (!this.current() || !this.permitted || !this.zone || !this.ownerReady) { this.loadReason = !this.permitted ? 'permission' : !this.ownerReady ? 'owner' : 'zone'; return }
      const scope = this.contextScope; this.clearChoices(); const epoch = this.requestEpoch; this.loadingOffering = true; this.loadReason = ''; if (!this.storageError) this.error = ''
      try {
        const result = await readVpcCreateChoices(this.read, this.$store.getters, this.owner, this.zone)
        if (!this.current() || epoch !== this.requestEpoch || scope !== this.contextScope) return
        this.offeringRows = result.rows; this.loaded = true; this.loadReason = result.reason
        await this.handleVpcOfferingChange(result.rows.find(r => !r.reason)?.offering.id)
      } catch (e) { if (this.current() && epoch === this.requestEpoch && scope === this.contextScope) { this.loadReason = this.reason(e); this.error = this.text(e); this.loaded = false } } finally { if (this.current() && epoch === this.requestEpoch && scope === this.contextScope) this.loadingOffering = false }
    },
    async handleVpcOfferingChange (id) {
      if (this.submitting || this.pending) return
      const previous = this.choice?.offering; this.form.vpcofferingid = this.offeringRows.some(r => r.offering.id === id && !r.reason) ? id : undefined
      resetVpcCreateFields(this.form, previous, this.choice?.offering, this.zone, this.$store.getters)
      this.advancedAcknowledged = false; this.asNumbers = []; const epoch = ++this.asEpoch; this.asLoading = false
      if (!this.fields.asNumber) return
      const scope = this.contextScope; const selected = this.form.vpcofferingid; this.asLoading = true
      try {
        const rows = await readVpcCreateAsNumbers(this.read, this.zone.id)
        if (!this.current() || epoch !== this.asEpoch || scope !== this.contextScope || selected !== this.form.vpcofferingid) return
        this.asNumbers = rows
      } catch (e) { if (this.current() && epoch === this.asEpoch && scope === this.contextScope) this.error = this.text(e) } finally { if (this.current() && epoch === this.asEpoch && scope === this.contextScope) this.asLoading = false }
    },
    changeResolver (value) { if (this.submitting) return; this.form.userouteripresolver = value; resetVpcCreateFields(this.form, this.choice?.offering, this.choice?.offering, this.zone, this.$store.getters) },
    removeReceipt () { try { sessionStorage.removeItem(this.entryKey); return true } catch (_) { this.storageError = true; this.error = this.$t('message.vpc.create.storage'); return false } },
    attachJob (id) {
      if (!this.current()) return
      if (!this.removeReceipt()) return
      this.$pollJob({ jobId: id, title: this.$t('label.add.vpc'), description: this.$t('message.success.add.vpc'), loadingMessage: this.$t('message.vpc.create.accepted'), catchMessage: this.$t('error.fetching.async.job.result') })
      this.$emit('refresh-data'); this.invalidate()
    },
    async recheck () {
      if (!this.current() || !this.pending || this.submitting || this.storageError || !this.permitted) return
      this.submitting = true; this.error = ''
      try {
        const record = this.pending; const result = (await this.read('queryAsyncJobResult', { jobid: record.id, ignoreproject: true }))?.queryasyncjobresultresponse
        if (!this.current()) return
        if (result?.jobid !== record.id || result.userid !== record.userId || !/CreateVPCCmd(?:ByAdmin)?$/.test(result.cmd || '') || ![0, 1, 2].includes(result.jobstatus)) throw new Error('message.vpc.create.receipt')
        this.attachJob(record.id)
      } catch (e) { if (this.current()) this.error = this.text(e) } finally { if (this.current()) this.submitting = false }
    },
    async handleSubmit (e) {
      if (e?.preventDefault) e.preventDefault()
      if (this.pending) return this.recheck()
      if (!this.canSubmit) return
      const scope = this.contextScope; const id = this.form.vpcofferingid; const previous = this.choice; const oldZone = vpcCreateZoneFingerprint(this.zone); this.submitting = true; this.error = ''
      try {
        await this.$refs.formRef.validate()
        const zones = await networkCollection(this.read, 'listZones', 'zone', { id: this.zone.id, ignoreproject: true })
        if (!this.current() || scope !== this.contextScope || id !== this.form.vpcofferingid) return
        const zone = zones.find(z => z.id === this.form.zoneid)
        if (!zone || oldZone !== vpcCreateZoneFingerprint(zone)) throw new Error('message.vpc.create.changed')
        const latest = await readVpcCreateChoices(this.read, this.$store.getters, this.owner, zone)
        const choice = latest.rows.find(r => r.offering.id === id && !r.reason)
        if (!this.current() || scope !== this.contextScope || id !== this.form.vpcofferingid) return
        if (!choice || vpcCreateOfferingFingerprint(previous.offering) !== vpcCreateOfferingFingerprint(choice.offering) || vpcCreateDiagnosticFingerprint(previous.diagnostic) !== vpcCreateDiagnosticFingerprint(choice.diagnostic)) throw new Error('message.vpc.create.changed')
        const asNumbers = this.fields.asNumber ? await readVpcCreateAsNumbers(this.read, zone.id) : []
        if (!this.current() || scope !== this.contextScope || id !== this.form.vpcofferingid) return
        const params = buildVpcCreateParams(this.form, this.owner, zone, choice.offering, this.$store.getters, asNumbers)
        const record = { version: 1, id: uuid(), params, userId: this.$store.getters.userInfo.id }
        try { sessionStorage.setItem(this.entryKey, JSON.stringify(record)) } catch (_) { this.storageError = true; throw new Error('message.vpc.create.storage') }
        this.pending = record
        let response
        try { response = await postAPI('createVPC', { ...params, customjobid: record.id }, { preserveOnFailure: true, backgroundJob: true, timeout: 15000 }) } catch (err) {
          if (!this.current()) return
          const code = err?.response?.status
          const rejected = err?.response?.data?.createvpcresponse
          // Mold returns HTTP 530 for synchronous create failures such as an
          // unavailable Source NAT IP. No async job has been accepted then.
          const createRejected = code === 530 && rejected?.errorcode === 530 && !rejected.jobid
          if (createRejected || (code >= 400 && code < 500 && Object.values(err.response.data || {}).some(r => r?.errorcode))) { if (this.removeReceipt()) this.pending = null }
          throw err
        }
        if (!this.current()) return
        if (response?.createvpcresponse?.jobid !== record.id) throw new Error('message.vpc.create.receipt')
        this.attachJob(record.id)
      } catch (err) {
        if (!this.current()) return
        if (err?.errorFields) { if (this.$refs.formRef?.scrollToField) this.$refs.formRef.scrollToField(err.errorFields[0]?.name) } else { this.error = this.text(err); if (err?.message === 'message.vpc.create.changed') { this.loaded = false; this.loadReason = 'changed' } }
      } finally { if (this.current()) this.submitting = false }
    }
  }
}
</script>
<style scoped>
.vpc-create-dialog { color: var(--ui-text); font-size: 14px; }
.vpc-create-target { display: flex; flex-direction: column; gap: 6px; padding: 12px; margin: 16px 0; border: 1px solid var(--ui-border); border-radius: 4px; overflow-wrap: anywhere; }
.vpc-create-target span, .vpc-create-help { color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.vpc-create-help { margin: 12px 0; }
.vpc-create-help ul { padding-left: 20px; }
.vpc-create-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 16px; }
.vpc-create-dialog :deep(.ant-select), .vpc-create-dialog :deep(.ant-input-number) { width: 100%; min-width: 0; }
.vpc-create-dialog :deep(.ant-alert) { margin: 12px 0; overflow-wrap: anywhere; }
</style>
