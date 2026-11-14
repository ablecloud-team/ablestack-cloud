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
size="md"
focus-guard
:title="$t('label.add.new.tier')"
    :closable="!busy"
:keyboard="!busy"
@cancel="$emit('cancel')">
    <dl class="tier-context">
      <dt>{{ $t('label.vpc') }}</dt><dd>{{ resource.name }}</dd>
      <dt>{{ $t('label.zone') }}</dt><dd>{{ resource.zonename }}</dd>
      <dt>{{ $t(resource.projectid ? 'label.project' : 'label.account') }}</dt><dd>{{ resource.project || resource.account }}</dd>
      <template v-if="!resource.projectid"><dt>{{ $t('label.domain') }}</dt><dd>{{ resource.domain }}</dd></template>
      <dt>{{ $t('label.cidr') }}</dt><dd>{{ resource.cidr }}</dd>
    </dl>

    <a-form ref="tierForm" layout="vertical" :model="form" :rules="rules" :disabled="locked" @finish="submit">
      <a-form-item :label="$t('label.name')" name="name">
        <a-input v-model:value="form.name" v-focus="true" :disabled="locked" />
      </a-form-item>
      <a-form-item :label="$t('label.networkofferingid')" name="offering">
        <a-select
v-model:value="form.offering"
show-search
option-filter-prop="label"
          :loading="loading"
:disabled="locked || loading"
:placeholder="$t('label.networkofferingid')"
          :options="offeringOptions" />
      </a-form-item>
      <section class="tier-diagnostics" :aria-busy="loading">
        <div class="diagnostic-heading">
          <strong>{{ $t('label.network.offering.diagnostics') }}</strong>
          <a-tooltip :title="$t('label.network.offering.diagnostics.reload')">
            <a-button
shape="circle"
size="small"
:disabled="busy || loading || !!pending"
              :aria-label="$t('label.network.offering.diagnostics.reload')"
@click="load">
              <template #icon><reload-outlined /></template>
            </a-button>
          </a-tooltip>
        </div>
        <a-alert show-icon :type="diagnosticType" :message="diagnosticMessage">
          <template v-if="selected?.network" #description>{{ selected.network.name }}</template>
        </a-alert>
        <details v-if="rejected.length && !loading" class="offering-reasons">
          <summary>{{ $t('message.vpc.tier.rejected', { count: rejected.length }) }}</summary>
          <ul>
            <li v-for="choice in rejected" :key="choice.offering.id">
              <strong>{{ offeringLabel(choice.offering) }}</strong>
              <ul><li v-for="(issue, i) in choice.issues" :key="i">
                <template v-if="issue.service">{{ issue.service }}<template v-if="issue.provider"> / {{ issue.provider }}</template> · </template>
                {{ $t('message.network.diagnostics.' + issue.reason) }}
              </li></ul>
            </li>
          </ul>
        </details>
      </section>
      <a-form-item v-if="zone?.allowuserspecifyvrmtu" name="privatemtu" :label="$t('label.privatemtu')">
        <a-input-number v-model:value="form.privatemtu" :disabled="locked" :max="zone.routerprivateinterfacemaxmtu || 1500" style="width: 100%" />
      </a-form-item>
      <a-form-item v-if="selected?.offering.specifyvlan" name="vlan" :label="$t('label.vlan')">
        <a-input v-model:value="form.vlan" :disabled="locked" />
      </a-form-item>
      <a-form-item v-if="showAsNumber" name="asnumber" :label="$t('label.asnumber')">
        <a-select
v-model:value="form.asnumber"
:disabled="locked"
show-search
option-filter-prop="label"
          :options="asNumbers.map(row => ({ label: String(row.asnumber), value: row.asnumber }))" />
      </a-form-item>
      <a-form-item :label="$t('label.gateway')" name="gateway">
        <a-input v-model:value="form.gateway" :disabled="locked" :placeholder="$t('label.create.tier.gateway.description', { value: resource.cidr })" />
      </a-form-item>
      <a-form-item :label="$t('label.netmask')" name="netmask">
        <a-input v-model:value="form.netmask" :disabled="locked" :placeholder="$t('label.create.tier.netmask.description', { value: vpcNetmask })" />
      </a-form-item>
      <a-form-item :label="$t('label.externalid')" name="externalid">
        <a-input v-model:value="form.externalid" :disabled="locked" />
      </a-form-item>
      <a-form-item v-if="showAcl" :label="$t('label.aclid')" name="acl">
        <a-select
v-model:value="form.acl"
:disabled="locked || loading"
show-search
option-filter-prop="label"
          :options="acls.map(row => ({ label: row.name + ' (' + row.description + ')', value: row.id }))" />
      </a-form-item>
      <a-alert v-if="showAcl && ['default_allow', 'default_deny'].includes(aclName)" type="warning" show-icon>
        <template #message><div v-html="$t('message.network.acl.' + (aclName === 'default_allow' ? 'default.allow' : 'default.deny'))" /></template>
      </a-alert>
    </a-form>
    <div v-if="error" ref="errorNotice" class="tier-error" tabindex="-1">
      <a-alert type="error" show-icon :message="$t('message.vpc.tier.submit.failed')" :description="error" />
    </div>
    <template #footer>
      <a-button :disabled="busy" @click="$emit('cancel')">{{ $t('label.cancel') }}</a-button>
      <a-button v-if="pending" type="primary" :loading="busy" @click="recheck">{{ $t('label.vmvolume.check') }}</a-button>
      <a-button v-else type="primary" :loading="busy" :disabled="!canSubmit" @click="submit">{{ $t('label.ok') }}</a-button>
    </template>
  </MoldDialog>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/network/VpcDialog'
import { getNetmaskFromCidr } from '@/utils/network'
import { networkError } from '@/utils/networkLifecycle'
import { readDiagnosticList } from '@/utils/networkOfferingDiagnostics'
import { networkOfferingFingerprint } from '@/utils/networkOfferingChange'
import { loadVpcTierChoices, needsTierAsNumber, tierHasAcl, tierNetworkMatches, vpcTierScope } from '@/utils/vpcTierCreation'

export default {
  name: 'VpcTierCreateDialog',
  components: { MoldDialog },
  props: { resource: { type: Object, required: true }, scope: { type: String, required: true } },
  emits: ['cancel', 'created'],
  data: () => ({
    form: {},
    choices: [],
    acls: [],
    asNumbers: [],
    zone: null,
    loading: true,
    loadFailed: false,
    busy: false,
    pending: null,
    error: '',
    disposed: false
  }),
  computed: {
    apis () { return this.$store.getters.apis || {} },
    selected () { return this.choices.find(row => row.offering.id === this.form.offering) },
    rejected () { return this.choices.filter(row => row.blocked) },
    offeringOptions () {
      return this.choices.map(row => ({ label: this.offeringLabel(row.offering), value: row.offering.id, disabled: row.blocked }))
    },
    locked () { return this.busy || !!this.pending },
    showAcl () { return 'listNetworkACLLists' in this.apis && tierHasAcl(this.selected?.offering) },
    showAsNumber () { return 'listASNumbers' in this.apis && needsTierAsNumber(this.selected?.offering) },
    aclName () { return this.acls.find(row => row.id === this.form.acl)?.name },
    vpcNetmask () { return getNetmaskFromCidr(this.resource.cidr) },
    rules () {
      const required = { required: true, message: this.$t('label.required') }
      const textRequired = { ...required, whitespace: true, type: 'string' }
      return {
        name: [textRequired],
        offering: [required],
        gateway: [textRequired],
        netmask: [textRequired],
        ...(this.showAcl ? { acl: [required] } : {}),
        ...(this.selected?.offering.specifyvlan ? { vlan: [required] } : {})
      }
    },
    canSubmit () {
      return 'createNetwork' in this.apis && !this.locked && !this.loading && !this.loadFailed &&
        !!this.selected && !this.selected.blocked
    },
    diagnosticType () {
      if (this.loadFailed || this.selected?.blocked) return 'error'
      if (this.loading || !this.selected) return 'info'
      return this.selected.status === 'ready' ? 'success' : 'warning'
    },
    diagnosticMessage () {
      if (this.loading) return this.$t('message.network.diagnostics.checking')
      if (this.loadFailed) return this.$t('message.network.diagnostics.failed')
      if (!this.selected) return this.$t('message.vpc.tier.' + (this.form.offering ? 'missing' : 'empty'))
      return this.$t('message.vpc.tier.' + this.selected.status)
    }
  },
  mounted () { this.load() },
  beforeUnmount () { this.disposed = true },
  methods: {
    offeringLabel (offering) { return offering.displaytext || offering.name || offering.id },
    current (scope) { return !this.disposed && scope === this.scope && scope === vpcTierScope(this) && 'createNetwork' in this.apis },
    applyChoices (result) {
      this.choices = result.choices
      this.acls = result.acls
      this.zone = result.zone
      this.asNumbers = result.asNumbers
      if (!this.form.offering) this.form.offering = (this.choices.find(row => row.status === 'ready') || this.choices.find(row => !row.blocked))?.offering.id
    },
    async load () {
      const scope = this.scope
      this.loading = true
      this.loadFailed = false
      try {
        const result = await loadVpcTierChoices(getAPI, this.apis, this.resource)
        if (this.current(scope)) this.applyChoices(result)
      } catch {
        if (this.current(scope)) this.loadFailed = true
      } finally {
        if (this.current(scope)) this.loading = false
      }
    },
    params () {
      const { resource, form, selected } = this
      return {
        vpcid: resource.id,
        zoneid: resource.zoneid,
        ignoreproject: true,
        ...(resource.projectid ? { projectid: resource.projectid } : { domainid: resource.domainid, account: resource.account }),
        networkofferingid: form.offering,
        name: form.name.trim(),
        displaytext: form.name.trim(),
        gateway: form.gateway.trim(),
        netmask: form.netmask.trim(),
        ...(form.externalid?.trim() ? { externalid: form.externalid.trim() } : {}),
        ...(this.showAcl && form.acl ? { aclid: form.acl } : {}),
        ...(selected.offering.specifyvlan && form.vlan ? { vlan: form.vlan } : {}),
        ...(this.zone?.allowuserspecifyvrmtu && form.privatemtu ? { privatemtu: form.privatemtu } : {}),
        ...(this.showAsNumber && form.asnumber ? { asnumber: form.asnumber } : {})
      }
    },
    async showError (message, scope) {
      if (!this.current(scope)) return
      this.error = message
      await this.$nextTick()
      if (this.current(scope) && this.$refs.errorNotice) this.$refs.errorNotice.focus()
    },
    async submit () {
      if (!this.canSubmit) return
      const scope = this.scope
      this.busy = true
      this.error = ''
      try {
        try {
          await this.$refs.tierForm.validate()
        } catch (error) {
          if (this.current(scope) && error.errorFields?.[0]) this.$refs.tierForm.scrollToField(error.errorFields[0].name)
          return
        }
        if (!this.current(scope)) return
        const params = this.params()
        const previous = this.selected.offering
        const fresh = await loadVpcTierChoices(getAPI, this.apis, this.resource)
        if (!this.current(scope)) return
        this.applyChoices(fresh)
        if (!this.selected || this.selected.blocked ||
            networkOfferingFingerprint(previous) !== networkOfferingFingerprint(this.selected.offering) ||
            (params.aclid && !this.acls.some(row => row.id === params.aclid))) {
          await this.showError(this.$t('message.vpc.tier.changed'), scope)
          return
        }
        await this.createTier(params, scope)
      } catch (error) {
        await this.showError(networkError(error).text || this.$t('message.request.failed'), scope)
      } finally {
        if (this.current(scope)) this.busy = false
      }
    },
    async createTier (params, scope) {
      let response
      try {
        response = await postAPI('createNetwork', params, { preserveOnFailure: true })
      } catch (error) {
        if (!this.current(scope)) return
        const detail = networkError(error)
        if (!detail.definitive) this.pending = { params }
        await this.showError(detail.definitive ? detail.text : this.$t('message.vpc.tier.uncertain'), scope)
        return
      }
      if (!this.current(scope)) return
      const receipt = response?.createnetworkresponse
      if (receipt?.errorcode) {
        await this.showError(receipt.errortext || this.$t('message.request.failed'), scope)
      } else if (receipt?.jobid) {
        this.pending = { params, jobId: receipt.jobid }
        await this.poll(scope)
      } else if (tierNetworkMatches(receipt?.network || receipt, params)) {
        this.$emit('created')
      } else {
        this.pending = { params }
        await this.showError(this.$t('message.vpc.tier.uncertain'), scope)
      }
    },
    async poll (scope) {
      const { jobId, params } = this.pending
      if ('queryAsyncJobResult' in this.apis) {
        for (let read = 0; read < 60 && this.current(scope); read++) {
          let job
          try {
            job = (await getAPI('queryAsyncJobResult', { jobid: jobId, ignoreproject: true },
              { preserveOnFailure: true, backgroundJob: true, timeout: 15000 }))?.queryasyncjobresultresponse
          } catch { break }
          if (!this.current(scope)) return
          if (job?.jobid !== jobId || (job.cmd && !job.cmd.endsWith('.CreateNetworkCmd'))) break
          if (job.jobstatus === 2) {
            this.pending = null
            await this.showError(job.jobresult?.errortext || this.$t('message.request.failed'), scope)
            return
          }
          if (job.jobstatus === 1) {
            if (tierNetworkMatches(job.jobresult?.network || job.jobresult, params)) {
              this.$emit('created')
            } else {
              await this.reconcile(scope, false)
            }
            return
          }
          if (job.jobstatus !== 0) break
          await new Promise(resolve => setTimeout(resolve, 2000))
        }
      }
      await this.showError(this.$t('message.vpc.tier.job.pending', { id: jobId }), scope)
    },
    async reconcile (scope, allowRetry) {
      const { params } = this.pending
      const rows = await readDiagnosticList(getAPI, 'listNetworks', {
        vpcid: params.vpcid,
        name: params.name,
        projectid: params.projectid,
        account: params.account,
        domainid: params.domainid,
        listall: true,
        ignoreproject: true
      }, 'network')
      if (!this.current(scope)) return
      const exact = rows.filter(row => row.name === params.name)
      if (exact.length === 1 && tierNetworkMatches(exact[0], params)) {
        this.$emit('created')
      } else if (!exact.length && allowRetry) {
        this.pending = null
        await this.showError(this.$t('message.vpc.tier.retry.checked'), scope)
      } else {
        await this.showError(this.$t('message.vpc.tier.uncertain'), scope)
      }
    },
    async recheck () {
      if (this.busy || !this.pending) return
      const scope = this.scope
      this.busy = true
      try {
        if (this.pending.jobId) await this.poll(scope)
        else await this.reconcile(scope, true)
      } catch (error) {
        await this.showError(networkError(error).text || this.$t('message.request.failed'), scope)
      } finally {
        if (this.current(scope)) this.busy = false
      }
    }
  }
}
</script>

<style scoped>
.tier-context { margin: 0 0 16px; overflow-wrap: anywhere; }
.tier-context dt { font-weight: 600; }
.tier-context dd { margin: 0 0 8px; }
.tier-diagnostics, .tier-error { margin: 0 0 16px; overflow-wrap: anywhere; }
.tier-error { margin: 16px 0 0; }
.diagnostic-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.offering-reasons { margin-top: 8px; }
.offering-reasons summary { cursor: pointer; }
.offering-reasons ul { padding-inline-start: 18px; margin-bottom: 4px; }
</style>
