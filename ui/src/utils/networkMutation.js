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

import { getAPI, postAPI } from '@/api'
import { networkBusy, networkCollection, networkError, networkPendingJobs } from './networkLifecycle'

export const networkMutationMixin = {
  data: () => ({ networkMutation: null, networkMutationVersion: 0, networkMutationDisposed: false }),
  computed: {
    networkMutationScope () {
      const user = this.$store.getters.userInfo || {}
      return JSON.stringify([this.$route.path, this.resource?.id, this.resource?.projectid, user.id, user.roletype, user.roleid, user.domainid, this.$store.state?.user?.token, this.$store.getters.project?.id, Object.keys(this.$store.getters.apis || {}).sort()])
    },
    networkMutationBusy () { return ['checking', 'submitting', 'running'].includes(this.networkMutation?.status) },
    networkMutationSubmitting () { return ['checking', 'submitting'].includes(this.networkMutation?.status) },
    networkMutationBlocked () { return this.networkMutationBusy || this.networkMutation?.status === 'unknown' }
  },
  watch: {
    networkMutationScope () { this.networkMutationVersion++; this.networkMutation = null; this.networkMutationSuccess = null }
  },
  beforeUnmount () { this.networkMutationDisposed = true; this.networkMutationVersion++ },
  methods: {
    pollScopedNetworkJob (options) {
      const scope = this.networkMutationScope
      const version = this.networkMutationVersion
      const active = () => !this.networkMutationDisposed && scope === this.networkMutationScope && version === this.networkMutationVersion
      const callbacks = Object.fromEntries(['successMethod', 'errorMethod', 'catchMethod'].filter(key => options[key]).map(key => [key, (...args) => { if (active()) return options[key](...args) }]))
      return this.$pollJob({ ...options, ...callbacks })
    },
    resetNetworkMutation () { if (!this.networkMutationBlocked) { this.networkMutation = null; this.networkMutationSuccess = null } },
    currentNetworkMutation (operation) { return !this.networkMutationDisposed && this.networkMutation === operation && operation.scope === this.networkMutationScope && operation.version === this.networkMutationVersion },
    async submitNetworkMutation (api, params, success) {
      if (this.networkMutationBlocked) return false
      if (this.networkMutation?.status === 'success' && this.networkMutation.api === api && JSON.stringify(this.networkMutation.params) === JSON.stringify(params)) return false
      this.networkMutation = { api, params: { ...params }, status: 'checking', error: '', code: '', jobId: '', entityId: '', scope: this.networkMutationScope, version: ++this.networkMutationVersion }
      const operation = this.networkMutation
      this.networkMutationSuccess = success
      try {
        if (!(api in this.$store.getters.apis) || !('queryAsyncJobResult' in this.$store.getters.apis)) throw new Error(this.$t('message.network.lifecycle.reason.permission'))
        if (this.listRefreshFailed) throw new Error(this.$t('message.list.refresh.stale'))
        const networkId = params.networkid || this.resource?.associatednetworkid || (this.$route.meta?.name === 'guestnetwork' ? this.resource?.id : null)
        if (networkId && 'listNetworks' in this.$store.getters.apis) {
          const networks = await networkCollection(getAPI, 'listNetworks', 'network', { id: networkId, listall: true, ignoreproject: true, ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}) })
          if (!this.currentNetworkMutation(operation)) return false
          if (networks.length !== 1 || networks[0].id !== networkId) throw new Error(this.$t('message.network.lifecycle.reason.missing'))
          if (networkBusy(networks[0])) throw new Error(this.$t('message.network.lifecycle.reason.busy'))
          if ('listAsyncJobs' in this.$store.getters.apis && (await networkPendingJobs(getAPI, networkId)).length) throw new Error(this.$t('message.network.lifecycle.reason.busy'))
          if (!this.currentNetworkMutation(operation)) return false
        }
      } catch (error) {
        if (this.currentNetworkMutation(operation)) { operation.status = 'failed'; operation.error = networkError(error).text || this.$t('message.request.failed') }
        return false
      }
      operation.status = 'submitting'
      try {
        const json = await postAPI(api, { ...params, ignoreproject: true, ...(this.resource.projectid ? { projectid: this.resource.projectid } : {}) }, { preserveOnFailure: true })
        if (!this.currentNetworkMutation(operation)) return false
        const receipt = json?.[api.toLowerCase() + 'response']
        operation.jobId = receipt?.jobid || ''
        operation.entityId = receipt?.id || ''
        if (!operation.jobId) { operation.status = 'unknown'; operation.error = this.$t('message.network.lifecycle.receipt'); return false }
      } catch (error) {
        if (this.currentNetworkMutation(operation)) {
          const detail = networkError(error)
          operation.status = detail.definitive ? 'failed' : 'unknown'; operation.code = detail.code
          operation.error = detail.text + (detail.definitive ? '' : ' ' + this.$t('message.network.lifecycle.receipt'))
        }
        return false
      }
      return this.pollNetworkMutation(operation)
    },
    async pollNetworkMutation (operation) {
      if (!this.currentNetworkMutation(operation) || !operation.jobId) return false
      operation.status = 'running'; operation.error = ''; let failures = 0
      for (let reads = 0; reads < 150 && this.currentNetworkMutation(operation); reads++) {
        try {
          const json = await getAPI('queryAsyncJobResult', { jobid: operation.jobId, ignoreproject: true }, { preserveOnFailure: true })
          if (!this.currentNetworkMutation(operation)) return false
          const job = json?.queryasyncjobresultresponse
          const expected = operation.api === 'associateIpAddress' ? 'associateipaddrcmd' : operation.api.toLowerCase() + 'cmd'
          const sourceNatUpdate = ['updateNetwork', 'updateVPC'].includes(operation.api) && !!operation.params.sourcenatipaddress
          const commands = operation.api === 'associateIpAddress' || sourceNatUpdate ? [expected, expected + 'byadmin'] : [expected]
          const expectedEntity = sourceNatUpdate ? operation.params.id : operation.entityId
          // The VPN create receipt names the VPN, while async metadata labels it as an IP address.
          const createsVpn = operation.api === 'createRemoteAccessVpn'
          const vpn = job?.jobresult?.remoteaccessvpn
          const wrongEntity = createsVpn
            ? job?.jobstatus === 1 && (vpn?.id !== operation.entityId || vpn.publicip !== this.resource.ipaddress)
            : expectedEntity && job?.jobinstanceid !== expectedEntity
          if (!job || job.jobid !== operation.jobId || ![0, 1, 2].includes(job.jobstatus) || (job.cmd && !commands.includes(job.cmd.split('.').pop().toLowerCase())) || wrongEntity) throw new Error('Network operation job scope mismatch')
          failures = 0
          if (job.jobstatus === 2) { operation.status = 'failed'; operation.code = job.jobresult?.errorcode || job.jobresultcode || ''; operation.error = job.jobresult?.errortext || this.$t('message.request.failed'); return false }
          if (job.jobstatus === 1) {
            operation.status = 'success'
            try { await this.networkMutationSuccess?.(job) } catch (error) { if (this.currentNetworkMutation(operation)) operation.error = this.$t('message.network.lifecycle.mutation.refresh') }
            return true
          }
        } catch (error) {
          if (!this.currentNetworkMutation(operation)) return false
          operation.error = networkError(error).text
          if (++failures >= 3) break
        }
        await new Promise(resolve => setTimeout(resolve, 2000))
      }
      if (this.currentNetworkMutation(operation)) { operation.status = 'unknown'; operation.error = (operation.error ? operation.error + ' ' : '') + this.$t('message.network.lifecycle.pending') }
      return false
    },
    async recheckNetworkMutation () {
      const operation = this.networkMutation
      if (!operation?.jobId || operation.status !== 'unknown') return
      return this.pollNetworkMutation(operation)
    }
  }
}
