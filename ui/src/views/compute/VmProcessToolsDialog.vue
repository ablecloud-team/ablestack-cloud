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
  <a-modal :visible="visible" :title="$t('label.vmprocess.tools.title')" :width="760" centered wrap-class-name="vm-process-tools-modal" :mask-closable="false" @cancel="$emit('close')">
    <a-descriptions bordered :column="1" size="small" class="tools-block">
      <a-descriptions-item :label="$t('label.vm')">{{ resource.displayname || resource.name }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.vmprocess.tools.stage')">{{ $t('label.vmprocess.tools.' + stage.toLowerCase()) }}</a-descriptions-item>
      <a-descriptions-item v-if="catalog?.status === 'MATCHED'" :label="$t('label.vmprocess.tools.media')">{{ media?.displaytext || media?.name || catalog.name }}</a-descriptions-item>
    </a-descriptions>
    <a-alert v-if="error" class="tools-block" type="error" show-icon :message="error" />
    <a-alert v-if="stage === 'INSTALL_PENDING' || stage === 'REBOOT_REQUIRED'" class="tools-block" type="info" show-icon :message="$t('message.vmprocess.tools.install.pending')" :description="$t('message.vmprocess.tools.install.manual')" />
    <a-alert v-if="stage === 'READY'" class="tools-block" type="success" show-icon :message="$t('message.vmprocess.tools.ready')" />
    <template v-if="stage === 'INSTALL_PENDING' || stage === 'REBOOT_REQUIRED'">
      <p class="tools-note">{{ $t('message.vmprocess.tools.console') }}</p>
      <pre class="tools-command">{{ installCommand }}</pre>
      <p class="tools-note">{{ $t('message.vmprocess.tools.reboot') }}</p>
    </template>
    <template #footer>
      <a-button @click="$emit('close')">{{ $t('label.close') }}</a-button>
      <a-button v-if="operation?.items?.some(item => item.status === 'unknown' && item.jobId)" :loading="busy" @click="checkOperation">{{ $t('label.vmiso.check') }}</a-button>
      <a-button v-if="stage === 'INSTALL_PENDING'" @click="stage = 'REBOOT_REQUIRED'">{{ $t('label.vmprocess.tools.reboot') }}</a-button>
      <a-button v-if="['INSTALL_PENDING', 'REBOOT_REQUIRED'].includes(stage) || (stage === 'FAILED' && canVerify)" type="primary" :loading="busy" @click="verify">{{ $t('label.vmprocess.tools.verify') }}</a-button>
      <a-button v-else-if="stage === 'SELECT'" type="primary" :loading="busy" :disabled="catalog?.status !== 'MATCHED' || !allowed" @click="attach">{{ $t('label.vmprocess.tools.attach') }}</a-button>
    </template>
  </a-modal>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { attachedIsos, isoActionReason, isoOperations, startIsoOperation } from '@/utils/vmIsoActions'
import { requiredRpcs } from './vmProcessDisplay'
import { matchesToolsIsoChecksum } from './vmProcessToolsChecksum'

const result = (json, command) => json?.[command.toLowerCase() + 'response']
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))

export default {
  name: 'VmProcessToolsDialog',
  props: { visible: Boolean, resource: { type: Object, required: true }, catalog: { type: Object, default: null }, capability: { type: Object, default: null } },
  emits: ['close', 'verified'],
  data () { return { stage: 'SELECT', error: '', media: null, vm: null, busy: false } },
  computed: {
    scopeKey () { return JSON.stringify([this.resource.id, this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.$store.state?.user?.token]) },
    allowed () { return 'attachIso' in (this.$store.getters.apis || {}) },
    operation () { return isoOperations['process-tools:' + this.scopeKey] },
    canVerify () { return (this.vm && attachedIsos(this.vm).some(item => item.id === this.catalog?.isoId)) || this.operation?.items?.some(item => item.status === 'success') },
    installCommand () {
      return this.capability?.os?.family === 'windows'
        ? this.$t('message.vmprocess.tools.command.windows')
        : this.$t('message.vmprocess.tools.command.linux')
    }
  },
  watch: {
    visible (value) { if (value) this.open() },
    scopeKey () { this.stage = 'SELECT'; this.error = ''; this.media = null; this.vm = null }
  },
  methods: {
    catalogError () { return this.$t('message.vmprocess.tools.catalog.' + (this.catalog?.status || 'NOT_CONFIGURED').toLowerCase()) },
    async loadMedia () {
      if (this.catalog?.status !== 'MATCHED') throw new Error(this.catalogError())
      if (!this.allowed) throw new Error(this.$t('message.vmprocess.tools.permission'))
      const current = result(await getAPI('getVirtualMachineProcessCapabilities', { virtualmachineid: this.resource.id }), 'getVirtualMachineProcessCapabilities')?.processcapability?.toolsiso
      if (current?.status !== 'MATCHED') throw new Error(this.$t('message.vmprocess.tools.catalog.' + (current?.status || 'OS_UNKNOWN').toLowerCase()))
      if (current.isoId !== this.catalog.isoId || current.name !== this.catalog.name ||
          current.checksum !== this.catalog.checksum ||
          current.zoneId !== this.catalog.zoneId || current.arch !== this.catalog.arch ||
          current.isoFamily !== this.catalog.isoFamily) throw new Error(this.$t('message.list.refresh.stale'))
      const vmResponse = await getAPI('listVirtualMachines', { id: this.resource.id })
      const vm = result(vmResponse, 'listVirtualMachines')?.virtualmachine?.find(item => item.id === this.resource.id)
      if (!vm) throw new Error(this.$t('message.list.refresh.stale'))
      if (vm.zoneid !== this.catalog.zoneId) throw new Error(this.$t('message.vmprocess.tools.zone'))
      const isoResponse = await getAPI('listIsos', { id: this.catalog.isoId, zoneid: vm.zoneid, isofilter: 'executable', listall: true })
      const iso = result(isoResponse, 'listIsos')?.iso?.find(item => item.id === this.catalog.isoId)
      if (!iso) throw new Error(this.$t('message.vmprocess.tools.missing'))
      if (iso.isready !== true) throw new Error(this.$t('message.vmprocess.tools.notready'))
      if (iso.name !== this.catalog.name) throw new Error(this.$t('message.list.refresh.stale'))
      if (iso.zoneid && iso.zoneid !== vm.zoneid) throw new Error(this.$t('message.vmprocess.tools.zone'))
      if (iso.arch && iso.arch !== this.catalog.arch) throw new Error(this.$t('message.vmprocess.tools.arch'))
      if (!matchesToolsIsoChecksum(iso.checksum, this.catalog.checksum)) throw new Error(this.$t('message.vmprocess.tools.checksum'))
      this.vm = vm; this.media = iso
      return { vm, iso }
    },
    async open () {
      this.error = ''
      if (this.operation?.items?.some(item => item.status === 'success')) { this.stage = 'INSTALL_PENDING'; return }
      if (this.operation?.items?.some(item => item.status === 'unknown')) { this.stage = 'FAILED'; this.error = this.$t('message.job.result.unknown'); return }
      this.stage = 'SELECT'; this.busy = true
      try {
        const { vm } = await this.loadMedia()
        if (attachedIsos(vm).some(item => item.id === this.catalog.isoId)) this.stage = 'INSTALL_PENDING'
        else {
          const reason = isoActionReason(vm, true)
          if (reason) throw new Error(this.$t(reason))
        }
      } catch (error) { this.error = error.message; this.stage = 'FAILED' } finally { this.busy = false }
    },
    async attach () {
      if (this.busy || !this.allowed) return
      this.error = ''; this.busy = true
      try {
        const { vm, iso } = await this.loadMedia()
        if (attachedIsos(vm).some(item => item.id === iso.id)) { this.stage = 'INSTALL_PENDING'; return }
        const reason = isoActionReason(vm, true)
        if (reason) throw new Error(this.$t(reason))
        const key = 'process-tools:' + this.scopeKey
        const originalPage = this.$route.path
        const op = startIsoOperation(key, { api: 'attachIso', items: [iso] }, {
          current: () => key === 'process-tools:' + this.scopeKey,
          refresh: async () => { await this.loadMedia() },
          validate: async item => {
            const fresh = await this.loadMedia()
            const issue = isoActionReason(fresh.vm, true)
            if (issue) throw new Error(this.$t(issue))
            if (attachedIsos(fresh.vm).some(entry => entry.id === item.id)) throw new Error(this.$t('message.vmiso.changed'))
          },
          submit: item => postAPI('attachIso', { virtualmachineid: vm.id, id: item.id }).then(json => result(json, 'attachIso')),
          poll: (jobId, item) => this.$pollJob({ jobId, retry: true, originalPage, resourceId: vm.id, title: this.$t('label.vmiso.attach'), description: item.name, showLoading: false, showSuccessMessage: false, action: { api: 'attachIso', isFetchData: true } })
        })
        await op.done
        if (op.items[0].status === 'success') this.stage = 'INSTALL_PENDING'
        else { this.stage = 'FAILED'; this.error = op.items[0].error || this.$t('message.job.result.unknown') }
      } catch (error) { this.stage = 'FAILED'; this.error = error.message } finally { this.busy = false }
    },
    async checkOperation () {
      if (!this.operation || this.busy) return
      this.busy = true
      try {
        await this.operation.check()
        if (this.operation.items[0].status === 'success') { this.stage = 'INSTALL_PENDING'; this.error = '' } else this.error = this.operation.items[0].error || this.$t('message.job.result.unknown')
      } finally { this.busy = false }
    },
    async verify () {
      if (this.busy) return
      this.busy = true; this.stage = 'VERIFYING'; this.error = ''
      try {
        const capability = result(await getAPI('getVirtualMachineProcessCapabilities', { virtualmachineid: this.resource.id }), 'getVirtualMachineProcessCapabilities')?.processcapability?.processstate
        if (!capability || !requiredRpcs.every(rpc => capability.rpcs?.[rpc] === 'ENABLED')) throw new Error(this.$t('message.vmprocess.tools.rpc'))
        const started = result(await postAPI('refreshVirtualMachineProcesses', { virtualmachineid: this.resource.id }), 'refreshVirtualMachineProcesses')
        if (!started?.jobid) throw new Error(this.$t('message.vmprocess.result.missing'))
        let job
        for (let attempt = 0; attempt < 30; attempt++) {
          job = result(await getAPI('queryAsyncJobResult', { jobid: started.jobid }), 'queryAsyncJobResult')
          if (job?.jobstatus !== 0) break
          await pause(400)
        }
        if (job?.jobstatus !== 1 || job.jobresult?.processsnapshot?.processstate?.kind !== 'snapshot' ||
            job.jobresult.processsnapshot.processstate.authority?.vmUuid !== this.resource.id) {
          throw new Error(job?.jobresult?.errortext || this.$t('message.vmprocess.tools.probe'))
        }
        this.stage = 'READY'; this.$emit('verified')
      } catch (error) { this.stage = 'FAILED'; this.error = error.message } finally { this.busy = false }
    }
  }
}
</script>

<style lang="scss" scoped>
.tools-block { margin-bottom: 16px; }
.tools-note { color: var(--ui-text-secondary); }
.tools-command { white-space: pre-wrap; overflow-wrap: anywhere; padding: 12px; background: var(--ui-bg-page); color: var(--ui-text-primary); border: 1px solid var(--ui-border); border-radius: 4px; }
</style>
<style lang="scss">
.vm-process-tools-modal {
  .ant-modal { top: 0; padding-bottom: 0; max-width: calc(100vw - 32px); }
  .ant-modal-content { display: flex; flex-direction: column; overflow: hidden; max-height: calc(100dvh - 48px); }
  .ant-modal-body { overflow-y: auto; }
  .ant-modal-footer { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
  .ant-modal-footer .ant-btn + .ant-btn { margin-left: 0; }
  .ant-descriptions-bordered .ant-descriptions-item-label { background: var(--ui-bg-page); color: var(--ui-text-secondary); }
  .ant-descriptions-bordered .ant-descriptions-item-content { color: var(--ui-text-primary); }
  .ant-descriptions-bordered .ant-descriptions-view, .ant-descriptions-bordered .ant-descriptions-row, .ant-descriptions-bordered .ant-descriptions-item-label, .ant-descriptions-bordered .ant-descriptions-item-content { border-color: var(--ui-border); }
}
</style>
