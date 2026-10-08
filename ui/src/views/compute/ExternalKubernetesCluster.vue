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
  <ExternalKubernetesNodes v-if="registered && continueNodes" :resource="registered" @close-action="$emit('close-action')" />
  <a-form v-else ref="form" :model="form" layout="vertical" class="mold-form-dialog kubernetes-create-dialog" @finish="finishStep">
    <a-steps class="kubernetes-wizard-steps" size="small" :current="wizardStep"><a-step v-for="title in ['basic','external.options','review']" :key="title" :title="$t('label.kubernetes.ui.step.' + title)" /></a-steps><div class="mold-form-content kubernetes-wizard-body"><div class="mold-form-grid kubernetes-wizard-main"><a-form-item v-show="wizardStep === 0" class="mold-form-full" :label="$t('label.clustertype')"><slot name="management-type"><a-tag>{{ $t('label.external.managed') }}</a-tag></slot></a-form-item><div v-show="wizardStep === 0" class="mold-form-full"><a-alert class="mold-form-full mold-dialog-summary" type="info" show-icon :message="$t('message.kubernetes.external.registration')" /></div>
<div v-show="wizardStep === 0"><a-form-item name="name" :label="$t('label.name')" :rules="[{ required: true, whitespace: true, message: $t('label.required') }]"><a-input v-model:value="form.name" :disabled="busy" /></a-form-item></div>
<div v-show="wizardStep === 0"><a-form-item name="zoneid" :label="$t('label.zoneid')" :rules="[{ required: true, message: $t('label.required') }]">
        <a-select v-model:value="form.zoneid" :loading="loading" :disabled="busy" :options="zones.map(zone => ({ value: zone.id, label: zone.name }))" @change="zoneChanged" />
      </a-form-item></div>
<div v-show="wizardStep === 0" class="mold-form-full"><a-form-item name="description" :label="$t('label.description')" class="mold-form-full"><a-textarea v-model:value="form.description" :disabled="busy" /></a-form-item></div>
<div v-show="wizardStep === 0" class="mold-form-full"><ownership-selection v-if="isAdmin()" class="mold-form-full" @fetch-owner="ownerChanged" /></div>
<div v-show="wizardStep === 1"><a-form-item name="networkid" :label="$t('label.networkid')" :rules="[{ required: edgeZone, message: $t('label.required') }]">
        <a-select v-model:value="form.networkid" allow-clear :loading="loading" :disabled="busy || !form.zoneid" :options="networks.map(network => ({ value: network.id, label: network.name }))" />
      </a-form-item></div>
<div v-show="wizardStep === 1"><a-form-item name="kubernetesversionid" :label="$t('label.kubernetesversionid')"><a-select v-model:value="form.kubernetesversionid" allow-clear :disabled="busy" :options="versions.map(version => ({ value: version.id, label: version.name }))" /></a-form-item></div>
<div v-show="wizardStep === 1"><a-form-item name="keypair" :label="$t('label.keypair')"><a-select v-model:value="form.keypair" allow-clear :disabled="busy" :options="keys.map(key => ({ value: key.name, label: key.name }))" /></a-form-item></div>
<div v-show="wizardStep === 1" class="mold-form-full"><a-form-item class="mold-form-full"><a-checkbox v-model:checked="continueNodes" :disabled="busy">{{ $t('label.kubernetes.external.continue') }}</a-checkbox></a-form-item></div>
<div v-show="wizardStep === 1" class="mold-form-full"><a-alert v-if="failed" type="error" show-icon class="mold-form-full" :message="$t('message.list.refresh.stale')" /></div><KubernetesCreateReview v-if="wizardStep === 2" class="mold-form-full" :items="createSummary" /></div><KubernetesCreateSummary :name="form.name" :items="createSummary" /></div><details class="kubernetes-summary-mobile"><summary>{{ $t('label.kubernetes.ui.summary') }}</summary><KubernetesCreateSummary :name="form.name" :items="createSummary" /></details>
    <div class="action-button"><a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button><a-button v-if="wizardStep > 0" :disabled="busy" @click="wizardStep--">{{ $t('label.previous') }}</a-button><a-button html-type="submit" type="primary" :loading="busy" :disabled="loading || failed">{{ $t(wizardStep === 2 ? 'label.kubernetes.ui.register' : 'label.next') }}</a-button></div>
  </a-form>
</template>
<script>
import KubernetesCreateSummary from '@/components/view/KubernetesCreateSummary'
import KubernetesCreateReview from '@/components/view/KubernetesCreateReview'
import { getAPI, postAPI } from '@/api'
import { isAdmin } from '@/role'
import { externalClusterParams } from '@/utils/kubernetesClusterActions'
import OwnershipSelection from '@/views/compute/wizard/OwnershipSelection'
import ExternalKubernetesNodes from '@/views/compute/ExternalKubernetesNodes'
export default {
  name: 'ExternalKubernetesCluster',
  components: { KubernetesCreateSummary, KubernetesCreateReview, OwnershipSelection, ExternalKubernetesNodes },
  emits: ['close-action'],
  inject: { parentFetchData: { default: () => {} } },
  data () { return { wizardStep: 0, form: {}, owner: {}, zones: [], networks: [], versions: [], keys: [], loading: false, failed: false, busy: false, continueNodes: false, registered: null, request: 0, createJob: null } },
  computed: {
    createSummary () {
      return [
        { label: 'label.zoneid', value: this.zones.find(zone => zone.id === this.form.zoneid)?.name },
        { label: 'label.clustertype', value: this.$t('label.external.managed') },
        { label: 'label.networkid', value: this.networks.find(network => network.id === this.form.networkid)?.name },
        { label: 'label.kubernetesversionid', value: this.versions.find(version => version.id === this.form.kubernetesversionid)?.name },
        { label: 'label.instances', value: this.$t('label.kubernetes.ui.not.deployed') },
        { label: 'label.kubernetes.node.control', value: this.$t('label.kubernetes.ui.operator.managed') },
        { label: 'label.kubernetes.external.continue', value: this.$t(this.continueNodes ? 'label.yes' : 'label.no') }
      ]
    },
    edgeZone () { return this.zones.find(zone => zone.id === this.form.zoneid)?.zonetype === 'Edge' }
  },
  created () { this.fetchOptions() },
  beforeUnmount () { this.request++ },
  methods: {
    isAdmin,
    async finishStep () {
      if (this.wizardStep === 2) return this.submit()
      const fields = this.wizardStep === 0 ? ['name', 'zoneid'] : ['networkid']
      try { await this.$refs.form.validateFields(fields); this.wizardStep++ } catch (error) { if (error.errorFields?.length) this.$refs.form.scrollToField(error.errorFields[0].name) }
    },
    ownerChanged (owner) { this.owner = owner; this.form.networkid = undefined; this.fetchOptions() },
    zoneChanged () { this.form.networkid = undefined; this.fetchOptions() },
    async fetchOptions () {
      const token = ++this.request
      this.loading = true; this.failed = false
      const scope = this.owner.projectid ? { projectid: this.owner.projectid } : this.owner.account ? { account: this.owner.account, domainid: this.owner.domainid } : {}
      try {
        const result = await Promise.all([
          getAPI('listZones', { available: true }),
          this.form.zoneid ? getAPI('listNetworks', { ...scope, zoneid: this.form.zoneid, listall: true }) : Promise.resolve({ listnetworksresponse: {} }),
          getAPI('listKubernetesSupportedVersions', {}), getAPI('listSSHKeyPairs', scope)
        ])
        if (token !== this.request) return
        this.zones = result[0].listzonesresponse.zone || []
        this.networks = (result[1].listnetworksresponse.network || []).filter(network => network.type !== 'L2')
        this.versions = result[2].listkubernetessupportedversionsresponse.kubernetessupportedversion || []
        this.keys = result[3].listsshkeypairsresponse.sshkeypair || []
      } catch (error) { if (token === this.request) { this.failed = true; this.$notifyError(error) } } finally { if (token === this.request) this.loading = false }
    },
    async submit () {
      if (this.busy || !this.$store.getters.apis.createKubernetesCluster) return
      this.busy = true
      try {
        if (!this.createJob) {
          const response = await postAPI('createKubernetesCluster', externalClusterParams(this.form, this.owner))
          const body = response.createkubernetesclusterresponse
          if (!body?.jobid) throw new Error('Missing creation job')
          this.createJob = { id: body.id, jobid: body.jobid }
        }
        const result = await this.$pollJob({ jobId: this.createJob.jobid, title: this.$t('label.kubernetes.cluster.create'), description: this.form.name, catchMessage: this.$t('error.fetching.async.job.result') })
        if (result.jobstatus === 2) { this.createJob = null; return }
        if (result.jobstatus !== 1) return
        const response = await getAPI('listKubernetesClusters', { id: this.createJob.id })
        this.registered = response.listkubernetesclustersresponse.kubernetescluster?.[0]
        if (!this.registered) throw new Error('Registered cluster is unavailable')
        this.parentFetchData()
        if (!this.continueNodes) this.$emit('close-action')
      } catch (error) { this.$notifyError(error) } finally { this.busy = false }
    }
  }
}
</script>
