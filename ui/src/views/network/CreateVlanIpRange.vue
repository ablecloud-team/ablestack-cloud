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
  <div class="guest-range-editor">
    <a-alert v-if="error" class="range-notice" :type="phase === 'editing' ? 'error' : 'warning'" show-icon :message="error" />
    <a-alert v-if="phase !== 'editing'" class="range-notice" type="info" show-icon :message="$t('message.guest.range.saved.check')" />
    <a-form
:id="formName"
:name="formName"
ref="formRef"
:model="form"
layout="vertical"
class="form"
@finish="handleSubmit"
v-ctrl-enter="handleSubmit">
      <fieldset :disabled="busy || phase !== 'editing'" class="range-fields">
        <a-form-item :label="$t('label.internetprotocol')">
          <p v-if="range" class="range-family-value">{{ rangeFamilyLabel }}</p>
          <a-radio-group v-else v-model:value="form.family" :disabled="busy || phase !== 'editing'">
            <a-radio value="ipv4">IPv4</a-radio><a-radio v-if="supportsIpv6" value="ipv6">IPv6</a-radio><a-radio v-if="supportsIpv6" value="dual">IPv4 + IPv6</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="pods.length" :label="$t('label.podid')" name="podid">
          <a-select v-model:value="form.podid" :disabled="busy || phase !== 'editing'"><a-select-option v-for="pod in pods" :key="pod.id" :value="pod.id">{{ pod.name }}</a-select-option></a-select>
        </a-form-item>
        <section v-for="family in visibleFamilies" :key="family.key" class="range-family" :aria-label="family.label">
          <h4>{{ family.label }}</h4>
          <p class="range-hint">{{ $t('message.guest.range.end.default') }}</p>
          <a-form-item v-for="field in family.fields" :key="field.key" :name="field.key" :required="!['endip', 'endipv6'].includes(field.key)">
            <template #label><tooltip-label :title="$t(field.label)" :tooltip="apiParams[field.key]?.description || ''" /></template>
            <a-input v-model:value="form[field.key]" :disabled="busy || phase !== 'editing'" :aria-label="$t(field.label)" :placeholder="apiParams[field.key]?.description || ''" />
          </a-form-item>
        </section>
      </fieldset>
    </a-form>
    <MoldDialogActions :busy="busy">
      <a-button :disabled="busy" @click="$emit('close-action')">{{ $t(phase === 'editing' ? 'label.cancel' : 'label.close') }}</a-button>
      <a-button type="primary" html-type="submit" :loading="busy" :disabled="busy || !canWrite" :form="formName" @click="handleSubmit">{{ $t(phase === 'editing' ? 'label.ok' : 'label.guest.range.check') }}</a-button>
    </MoldDialogActions>
  </div>
</template>
<script>
import { getCurrentInstance } from 'vue'
import { getAPI, postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { buildRangeParams, currentRangeNetwork, guestIpRangeSupported, guestRangesOverlap, listAllGuestRanges, rangeMatches, rangeScope, rangeSignature, rangeError } from '@/utils/guestIpRanges'

const groups = [
  { key: 'ipv4', label: 'IPv4', fields: [{ key: 'startip', label: 'label.startipv4' }, { key: 'endip', label: 'label.endipv4' }, { key: 'gateway', label: 'label.gateway' }, { key: 'netmask', label: 'label.netmask' }] },
  { key: 'ipv6', label: 'IPv6', fields: [{ key: 'startipv6', label: 'label.startipv6' }, { key: 'endipv6', label: 'label.endipv6' }, { key: 'ip6gateway', label: 'label.ip6gateway' }, { key: 'ip6cidr', label: 'label.ip6cidr' }] }
]
export default {
  name: 'CreateVlanIpRange',
  components: { TooltipLabel, MoldDialogActions },
  props: { resource: { type: Object, required: true }, range: { type: Object, default: null }, defaults: { type: Object, default: () => ({}) }, writable: { type: Boolean, default: true } },
  emits: ['close-action', 'refresh-data', 'busy'],
  setup () { return { formName: 'guest-ip-range-' + getCurrentInstance().uid } },
  data () { return { form: {}, busy: false, error: '', phase: 'editing', savedId: null, savedParams: null, beforeIds: [], pods: [], disposed: false } },
  computed: {
    rangeFamilyLabel () {
      return this.form.family === 'dual' ? this.$t('label.ip.v4.v6') : this.form.family === 'ipv6' ? 'IPv6' : 'IPv4'
    },
    command () { return this.range ? 'updateVlanIpRange' : 'createVlanIpRange' },
    scopeKey () { return rangeScope(this.resource, this.$store.getters, this.$store.state?.user?.token) },
    canWrite () { const apis = this.$store.getters.apis || {}; return this.writable && guestIpRangeSupported(this.resource) && ['listNetworks', 'listVlanIpRanges', this.command].every(a => a in apis) },
    apiParams () { return this.$getApiParams?.(this.command) || {} },
    supportsIpv6 () { return !!(this.resource.type === 'Shared' || this.resource.ip6cidr || this.range?.ip6cidr) && ['startipv6', 'endipv6', 'ip6gateway', 'ip6cidr'].every(k => k in this.apiParams) },
    visibleFamilies () { return groups.filter(g => this.form.family === 'dual' || this.form.family === g.key) }
  },
  watch: { busy (value) { this.$emit('busy', value) } },
  created () {
    const row = this.range || {}
    const ipv4 = !!(row.startip || (!this.range && (this.defaults.gateway || this.resource.gateway)))
    const ipv6 = !!row.startipv6
    this.form = { family: ipv4 ? (ipv6 ? 'dual' : 'ipv4') : ((this.defaults.ip6cidr || this.resource.ip6cidr) ? 'ipv6' : 'ipv4'), gateway: this.defaults.gateway || this.resource.gateway?.split(',')[0] || '', netmask: this.defaults.netmask || this.resource.netmask || '', ip6gateway: this.defaults.ip6gateway || this.resource.ip6gateway?.split(',')[0] || '', ip6cidr: this.defaults.ip6cidr || this.resource.ip6cidr?.split(',')[0] || '', ...row }
    this.loadPods()
  },
  beforeUnmount () { this.disposed = true },
  methods: {
    current (scope) { return !this.disposed && this.scopeKey === scope },
    async loadPods () {
      if (this.range || !('listZones' in (this.$store.getters.apis || {}))) return
      const scope = this.scopeKey
      try {
        const json = await getAPI('listZones', { id: this.resource.zoneid, ignoreproject: true }, { preserveOnFailure: true })
        if (!this.current(scope) || json.listzonesresponse?.zone?.[0]?.networktype !== 'Basic') return
        if (!('listPods' in this.$store.getters.apis)) throw new Error('message.guest.range.read.failed')
        const pods = await getAPI('listPods', { zoneid: this.resource.zoneid, ignoreproject: true }, { preserveOnFailure: true })
        if (this.current(scope)) { this.pods = pods.listpodsresponse?.pod || []; this.form.podid = this.pods[0]?.id }
      } catch (error) { if (this.current(scope)) this.error = rangeError(error, this.$t) }
    },
    async handleSubmit (event) {
      if (typeof event?.preventDefault === 'function') event.preventDefault()
      if (this.busy || !this.canWrite) return
      if (this.phase !== 'editing') return this.checkResult()
      const scope = this.scopeKey; const draft = JSON.stringify(this.form)
      this.busy = true; this.error = ''
      let sent = false
      try {
        if (['ipv6', 'dual'].includes(this.form.family) && !this.supportsIpv6) throw new Error('message.guest.range.unsupported')
        const params = buildRangeParams(this.form, this.resource, this.range)
        if (this.pods.length) { if (!this.form.podid) throw new Error('message.guest.range.required.family'); params.podid = this.form.podid }
        await currentRangeNetwork(getAPI, this.resource)
        const rows = await listAllGuestRanges(getAPI, this.resource)
        if (!this.current(scope) || !this.canWrite || JSON.stringify(this.form) !== draft) return
        if (this.range) {
          const row = rows.find(r => r.id === this.range.id)
          if (!row || rangeSignature(row) !== rangeSignature(this.range)) throw new Error('message.guest.range.scope.changed')
          if (rangeMatches(row, params)) { this.$emit('close-action'); return }
        } else if (rows.some(row => rangeMatches(row, params))) throw new Error('message.guest.range.already.exists')
        if (rows.some(row => row.id !== this.range?.id && guestRangesOverlap(row, params))) throw new Error('message.guest.range.overlap')
        this.savedParams = params; this.beforeIds = rows.map(row => row.id)
        sent = true; this.phase = 'sending'
        const json = await postAPI(this.command, params, { preserveOnFailure: true })
        if (!this.current(scope)) return
        const result = json?.[this.command.toLowerCase() + 'response']
        this.savedId = this.range?.id || result?.id || result?.vlan?.id || result?.vlaniprange?.id
        this.phase = 'saved'
        await this.verify(scope)
      } catch (error) {
        if (this.current(scope)) {
          if (sent && this.phase === 'sending') this.phase = error.response ? 'editing' : 'unknown'
          this.error = rangeError(error, this.$t)
        }
      } finally { if (!this.disposed && this.scopeKey === scope) this.busy = false }
    },
    async verify (scope) {
      const rows = await listAllGuestRanges(getAPI, this.resource)
      if (!this.current(scope)) return
      const candidates = this.savedId ? rows.filter(row => row.id === this.savedId) : rows.filter(row => !this.beforeIds.includes(row.id) && rangeMatches(row, this.savedParams))
      if (candidates.length !== 1 || !rangeMatches(candidates[0], this.savedParams)) throw new Error('message.guest.range.verify.failed')
      this.$notification.success({ message: this.$t(this.range ? 'message.success.update.iprange' : 'message.success.add.iprange') })
      this.$emit('refresh-data'); this.$emit('close-action')
    },
    async checkResult () {
      const scope = this.scopeKey; this.busy = true; this.error = ''
      try { await this.verify(scope) } catch (error) { if (this.current(scope)) this.error = rangeError(error, this.$t) } finally { if (!this.disposed && this.scopeKey === scope) this.busy = false }
    }
  }
}
</script>
<style scoped>
.guest-range-editor, .range-fields { width: 100%; min-width: 0; }
.range-fields { border: 0; padding: 0; }
.range-family { margin-bottom: 16px; }
.range-family h4 { margin-bottom: 12px; font-weight: 700; }
.range-notice { margin-bottom: 12px; }
.range-hint { margin-bottom: 12px; }
.range-family-value { margin: 0; color: var(--ui-text); font-weight: 600; }
.form :deep(.ant-form-item) { margin-bottom: 12px; }
.form :deep(.ant-form-item-label > label) { font-size: 14px; font-weight: 700; }
</style>
