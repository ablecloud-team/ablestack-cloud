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
  <a-modal :visible="true" :title="$t('label.vbmc.manage')" :width="880" centered wrap-class-name="vm-vbmc-modal" :mask-closable="false" @cancel="close">
    <a-alert class="vbmc-space" show-icon :type="statusType" :message="$t('label.vbmc.' + status)" :description="$t('message.vbmc.' + status)" />
    <a-alert v-if="error" class="vbmc-space" type="error" show-icon :message="$t('message.vbmc.request.failed')" />
    <a-alert v-if="reason && !hasEndpoint" class="vbmc-space" type="warning" show-icon :message="$t('message.vbmc.' + reason)" />
    <a-spin :spinning="loading">
      <a-descriptions bordered size="small" :column="1" class="vbmc-space">
        <a-descriptions-item :label="$t('label.vm')"><span :title="vm.displayname || vm.name">{{ vm.displayname || vm.name }}</span></a-descriptions-item>
        <a-descriptions-item :label="$t('label.vbmc.currenthost')">{{ vm.hostname || '—' }}</a-descriptions-item>
        <template v-if="hasEndpoint">
          <a-descriptions-item :label="$t('label.vbmc.endpoint')">{{ vm.vbmcaddress || '—' }} / {{ vm.vbmcport }} (UDP)</a-descriptions-item>
          <a-descriptions-item :label="$t('label.username')">vbmc</a-descriptions-item>
          <a-descriptions-item :label="$t('label.vbmc.cidr')">{{ vm.vbmcallowedcidr || '—' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.vbmc.checked')">{{ checked || $t('label.vbmc.unchecked') }}</a-descriptions-item>
        </template>
      </a-descriptions>
      <a-form v-if="mode === 'allocate'" layout="vertical" @submit.prevent>
        <a-form-item :label="$t('label.username')"><a-input value="vbmc" readonly /></a-form-item>
        <a-form-item :label="$t('label.password')" :help="$t('message.vbmc.password')" :validate-status="password && !validSecret ? 'error' : ''">
          <a-input-password v-model:value="password" autocomplete="new-password" :maxlength="20" :aria-label="$t('label.password')" />
        </a-form-item>
        <a-form-item :label="$t('label.vbmc.access')">
          <a-radio-group v-model:value="external"><a-radio :value="false">{{ $t('label.vbmc.local') }}</a-radio><a-radio :value="true">{{ $t('label.vbmc.external') }}</a-radio></a-radio-group>
        </a-form-item>
        <a-form-item v-if="external" :label="$t('label.vbmc.cidr')" :help="$t('message.vbmc.cidr')" :validate-status="cidr && !validRange ? 'error' : ''">
          <a-input v-model:value="cidr" placeholder="192.0.2.0/24" :aria-label="$t('label.vbmc.cidr')" />
        </a-form-item>
        <p class="vbmc-note">{{ $t('message.vbmc.dynamic') }}</p>
      </a-form>
      <template v-else-if="mode === 'remove' || mode === 'replace'">
        <a-alert class="vbmc-space" type="warning" show-icon :message="$t('label.vbmc.' + mode)" :description="$t('message.vbmc.' + mode)" />
        <a-checkbox v-if="mode === 'replace'" v-model:checked="ack">{{ $t('message.vbmc.ack') }}</a-checkbox>
      </template>
      <template v-else>
        <template v-if="hasEndpoint && vm.vbmcaddress">
          <h4>{{ $t('label.vbmc.command') }}</h4>
          <pre class="vbmc-command">{{ command }}</pre>
          <p class="vbmc-note">{{ $t('message.vbmc.command') }}</p>
        </template>
        <a-alert class="vbmc-space" type="info" show-icon :message="$t('label.vbmc.limits')" :description="$t('message.vbmc.limits')" />
        <p v-if="hasEndpoint" class="vbmc-note">{{ $t('message.vbmc.credentials') }}</p>
        <p v-if="!allowed('allocateVbmcToVM') || !allowed('checkVbmcToVM') || !allowed('removeVbmcToVM')" class="vbmc-note">{{ $t('message.vbmc.permission') }}</p>
        <details v-if="vm.vbmclasterror"><summary>{{ $t('label.vbmc.error') }}</summary><p class="vbmc-note">{{ vm.vbmclasterror }}</p></details>
      </template>
    </a-spin>
    <template #footer>
      <a-button @click="mode === 'overview' ? close() : cancel()">{{ $t(mode === 'overview' ? 'label.close' : 'label.cancel') }}</a-button>
      <template v-if="mode === 'overview'">
        <a-button :loading="loading" :disabled="loading || operation?.polling" @click="refresh">{{ $t('label.refresh') }}</a-button>
        <a-button v-if="!hasEndpoint && allowed('allocateVbmcToVM')" type="primary" :disabled="blocked || !!reason" @click="mode = 'allocate'">{{ $t('label.vbmc.allocate') }}</a-button>
        <template v-if="hasEndpoint">
          <a-button v-if="allowed('removeVbmcToVM')" :disabled="blocked" @click="mode = 'remove'">{{ $t(status === 'CleanupRequired' ? 'label.vbmc.cleanup' : 'label.vbmc.remove') }}</a-button>
          <a-button v-if="allowed('removeVbmcToVM') && allowed('allocateVbmcToVM')" :disabled="blocked || !!reason" @click="mode = 'replace'">{{ $t('label.vbmc.replace') }}</a-button>
          <a-button v-if="allowed('checkVbmcToVM')" type="primary" :disabled="blocked" @click="execute('checkVbmcToVM')">{{ $t('label.vbmc.check') }}</a-button>
        </template>
      </template>
      <a-button v-else-if="mode === 'allocate'" type="primary" :disabled="blocked || !!reason || !validSecret || !validRange" @click="execute('allocateVbmcToVM')">{{ $t('label.vbmc.allocate') }}</a-button>
      <a-button v-else type="primary" danger :disabled="blocked || (mode === 'replace' && !ack)" @click="execute('removeVbmcToVM')">{{ $t(mode === 'replace' ? 'label.vbmc.replace.confirm' : 'label.vbmc.remove') }}</a-button>
    </template>
  </a-modal>
</template>
<script>
import { getAPI } from '@/api'
import { allocated, allocationReason, validCidr, validPassword, vbmcOperations, runVbmc, pollVbmc } from '@/utils/vbmc'
import eventBus from '@/config/eventBus'
export default {
  name: 'VmVbmcManager',
  props: { resource: { type: Object, required: true } },
  emits: ['close-action'],
  data () { return { vm: {}, loading: false, stale: true, error: false, mode: 'overview', password: '', cidr: '', external: false, ack: false, checked: '', request: 0, disposed: false, submitting: false } },
  computed: {
    scope () { return JSON.stringify([this.$store.getters.userInfo?.id, this.$store.state.user.token, this.$store.getters.project?.id, this.resource.id]) },
    operation () { return vbmcOperations[this.scope] },
    hasEndpoint () { return allocated(this.vm) },
    reason () { return allocationReason(this.vm) },
    validSecret () { return validPassword(this.password) },
    validRange () { return !this.external || validCidr(this.cidr) },
    blocked () { return this.loading || this.stale || ['pending', 'unknown'].includes(this.operation?.state) || ['Allocating', 'Removing'].includes(this.vm.vbmcstatus) },
    status () {
      if (this.stale || this.operation?.state === 'unknown') return 'unknown'
      if (this.operation?.state === 'pending') return 'pending'
      if (!this.hasEndpoint) return 'Unallocated'
      if (this.vm.vbmcstatus === 'Ready') return this.checked ? 'Ready' : 'stored'
      return ['Allocating', 'Removing', 'CleanupRequired'].includes(this.vm.vbmcstatus) ? this.vm.vbmcstatus : 'unknown'
    },
    statusType () { return this.status === 'Ready' ? 'success' : ['unknown', 'CleanupRequired'].includes(this.status) ? 'warning' : 'info' },
    command () { return `ipmitool -I lanplus -H ${this.vm.vbmcaddress} -p ${this.vm.vbmcport} -U vbmc -a chassis status` }
  },
  watch: {
    'operation.state' (value) { if (['success', 'failed'].includes(value) && !this.loading && !this.submitting) this.refresh() },
    scope () { this.password = ''; this.checked = ''; this.request++; this.close() }
  },
  created () { this.refresh() },
  beforeUnmount () { this.disposed = true; this.request++; this.password = '' },
  methods: {
    allowed (api) { return api in this.$store.getters.apis },
    cancel () { this.mode = 'overview'; this.password = ''; this.ack = false },
    close () { this.password = ''; this.$emit('close-action') },
    async refresh () {
      if (this.loading) return
      const ticket = ++this.request; const scope = this.scope
      this.loading = true; this.checked = ''
      try {
        if (this.operation?.state === 'unknown' && this.operation.jobId) await pollVbmc(scope)
        const result = await getAPI('listVirtualMachines', { id: this.resource.id })
        if (this.disposed || ticket !== this.request || scope !== this.scope) return
        const vm = result.listvirtualmachinesresponse.virtualmachine?.find(v => v.id === this.resource.id)
        if (!vm) throw new Error('Unavailable')
        this.vm = vm; this.stale = false
      } catch (_) { if (!this.disposed && ticket === this.request) this.stale = true } finally { if (!this.disposed && ticket === this.request) this.loading = false }
    },
    async execute (api) {
      if (this.blocked || !this.allowed(api)) return
      if (api === 'allocateVbmcToVM' && (this.reason || !this.validSecret || !this.validRange || this.hasEndpoint)) return
      const scope = this.scope; const replace = this.mode === 'replace'
      const params = { virtualmachineid: this.resource.id }
      if (api === 'allocateVbmcToVM') { params.password = this.password; params.allowedcidr = this.external ? this.cidr : '127.0.0.1/32' }
      this.password = ''; this.checked = ''; this.error = false; this.mode = 'overview'
      this.submitting = true
      try { await runVbmc(scope, api, params) } finally { delete params.password; this.submitting = false }
      if (this.disposed || scope !== this.scope) return
      await this.refresh()
      if (this.disposed || scope !== this.scope) return
      this.error = this.operation?.state === 'failed'
      if (!this.stale && this.operation?.state === 'success') {
        if (api === 'checkVbmcToVM' && this.vm.vbmcstatus === 'Ready') this.checked = new Date().toLocaleString()
        if (replace && !this.hasEndpoint) { this.mode = 'allocate'; this.ack = false }
      }
      eventBus.emit('async-job-complete', { api, isFetchData: true })
    }
  }
}
</script>
<style lang="scss">
.vm-vbmc-modal {
  color: var(--ui-text-primary);
  .ant-modal { top: 0; padding-bottom: 0; max-width: calc(100vw - 32px); }
  .ant-modal-content { display: flex; flex-direction: column; max-height: calc(100vh - 48px); max-height: calc(100dvh - 48px); overflow: hidden; background: var(--ui-bg-surface); }
  .ant-modal-header, .ant-modal-footer { flex-shrink: 0; }
  .ant-modal-body { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 24px; }
  .ant-modal-footer { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; padding: 12px 24px; }
  .ant-modal-footer .ant-btn + .ant-btn { margin-left: 0; }
  .ant-descriptions-bordered .ant-descriptions-item-label { width: 180px; background: var(--ui-bg-page); color: var(--ui-text-secondary); }
  .ant-descriptions-bordered .ant-descriptions-item-content { color: var(--ui-text-primary); overflow-wrap: anywhere; }
  .ant-descriptions-bordered .ant-descriptions-view, .ant-descriptions-bordered .ant-descriptions-row, .ant-descriptions-bordered .ant-descriptions-item-label, .ant-descriptions-bordered .ant-descriptions-item-content { border-color: var(--ui-border); }
  .ant-form-item-label > label, .ant-radio-wrapper, .ant-checkbox-wrapper, h4, summary { color: var(--ui-text-primary); }
  .ant-form-item-explain, .ant-form-item-extra, .vbmc-note { color: var(--ui-text-secondary); }
  .ant-form-item { margin-bottom: 24px; }
  .ant-input[readonly], .ant-input[disabled], .ant-btn[disabled] { color: var(--ui-text-secondary) !important; background: var(--ui-bg-disabled) !important; border-color: var(--ui-border) !important; }
  .ant-form-item-has-error .ant-input, .ant-form-item-has-error .ant-input-affix-wrapper { background: var(--ui-bg-input) !important; }

  .vbmc-space { margin-bottom: 20px; }
  .vbmc-note { margin: 10px 0 20px; overflow-wrap: anywhere; }
  .vbmc-command { padding: 16px; background: var(--ui-bg-page); color: var(--ui-text-primary); border: 1px solid var(--ui-border); border-radius: 4px; white-space: pre-wrap; overflow-wrap: anywhere; }
  @media (max-width: 600px) { .ant-modal-body { padding: 16px; } .ant-descriptions-bordered .ant-descriptions-item-label { width: 112px; } }
}
</style>
