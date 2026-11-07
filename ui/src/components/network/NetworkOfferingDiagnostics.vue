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
  <div v-if="offering?.id && zoneId" class="network-offering-diagnostics" :aria-busy="loading">
    <div class="diagnostic-heading">
      <strong>{{ $t('label.network.offering.diagnostics') }}</strong>
      <a-tooltip :title="$t('label.network.offering.diagnostics.reload')">
        <a-button size="small" shape="circle" :loading="loading" :disabled="loading" :aria-label="$t('label.network.offering.diagnostics.reload')" @click="validate">
          <template #icon><reload-outlined /></template>
        </a-button>
      </a-tooltip>
    </div>
    <a-alert :type="loading ? 'info' : report.blocked ? 'error' : report.status === 'ready' ? 'success' : 'warning'" show-icon :message="$t('message.network.diagnostics.' + (loading ? 'checking' : report.status))">
      <template #description>
        <span v-if="report.network">{{ report.network.name }} · {{ report.network.id }}</span>
        <ul v-if="report.issues?.length">
          <li v-for="(issue, i) in report.issues" :key="i">
            <strong v-if="issue.service">{{ issue.service }} → {{ issue.provider }}: </strong>{{ $t('message.network.diagnostics.' + issue.reason) }}
          </li>
        </ul>
        <div v-if="report.status === 'ready'">{{ $t('message.network.diagnostics.configuration.only') }}</div>
      </template>
    </a-alert>
    <details v-if="report.checks?.length && !loading">
      <summary>{{ $t('label.network.offering.providers') }} ({{ report.checks.length }})</summary>
      <ul><li v-for="(check, i) in report.checks" :key="i">{{ check.service }} → {{ check.provider || '—' }} · {{ check.reason ? $t('message.network.diagnostics.' + check.reason) : $t('message.network.diagnostics.supported') }}</li></ul>
    </details>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import { diagnoseNetworkOffering, diagnosticApis } from '@/utils/networkOfferingDiagnostics'

export default {
  name: 'NetworkOfferingDiagnostics',
  props: {
    offering: { type: Object, default: null },
    zoneId: { type: String, default: '' },
    physicalNetworkId: { type: String, default: '' }
  },
  emits: ['state'],
  data: () => ({ report: { status: 'empty', blocked: true, issues: [], checks: [] }, loading: false, request: 0, disposed: false }),
  computed: {
    scope () {
      return JSON.stringify([this.zoneId, this.physicalNetworkId, this.offering, this.$store?.getters?.project?.id,
        [...diagnosticApis, 'listVirtualRouterElements'].map(api => api in (this.$store?.getters?.apis || {}))])
    }
  },
  watch: { scope: { immediate: true, handler () { this.validate() } } },
  beforeUnmount () { this.disposed = true; this.request++ },
  methods: {
    async validate () {
      const request = ++this.request; const scope = this.scope
      this.loading = true
      this.report = { status: 'checking', blocked: true, issues: [], checks: [] }
      this.$emit('state', { blocked: true, pending: true })
      try {
        const report = await diagnoseNetworkOffering(getAPI, this.$store?.getters?.apis, { zoneId: this.zoneId, physicalNetworkId: this.physicalNetworkId, offering: this.offering })
        if (this.disposed || request !== this.request || scope !== this.scope) return false
        this.report = report
        return !report.blocked
      } catch (error) {
        if (this.disposed || request !== this.request || scope !== this.scope) return false
        this.report = { status: 'failed', blocked: true, issues: [], checks: [] }
        return false
      } finally {
        if (!this.disposed && request === this.request && scope === this.scope) {
          this.loading = false
          this.$emit('state', { ...this.report, pending: false })
        }
      }
    }
  }
}
</script>

<style scoped>
.network-offering-diagnostics { margin: 12px 0 20px; color: var(--ui-text-primary); overflow-wrap: anywhere; }
.diagnostic-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.network-offering-diagnostics ul { padding-left: 20px; margin: 8px 0 0; }
.network-offering-diagnostics details { margin-top: 8px; }
.network-offering-diagnostics summary { cursor: pointer; font-weight: 600; }
</style>
