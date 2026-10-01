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
    <a-alert v-if="catalog?.selectionSource === 'REGISTERED_OS' && stage !== 'READY'" class="tools-block" type="info" show-icon :message="$t('message.vmprocess.tools.declared', { os: catalog.registeredOsName })" />
    <a-checkbox v-if="stage === 'SELECT' && catalog?.selectionSource === 'REGISTERED_OS'" v-model:checked="osConfirmed" class="tools-block">{{ $t('message.vmprocess.tools.os.confirm') }}</a-checkbox>
    <a-alert v-if="error" class="tools-block" type="error" show-icon :message="error" />
    <a-alert v-if="stage === 'INSTALL_PENDING' || stage === 'REBOOT_REQUIRED'" class="tools-block" type="info" show-icon :message="$t('message.vmprocess.tools.install.pending')" :description="$t('message.vmprocess.tools.install.manual')" />
    <a-alert v-if="stage === 'READY'" class="tools-block" type="success" show-icon :message="$t('message.vmprocess.tools.ready')" />
    <template v-if="canVerify && !['READY', 'VERIFYING'].includes(stage)">
      <p class="tools-note">{{ $t('message.vmprocess.tools.console') }}</p>
      <pre class="tools-command">{{ installCommand }}</pre>
      <p class="tools-note">{{ $t('message.vmprocess.tools.reboot') }}</p>
    </template>
    <template #footer>
      <a-button @click="$emit('close')">{{ $t('label.close') }}</a-button>
      <a-button v-if="operation?.items?.some(item => item.status === 'unknown' && item.jobId)" :loading="busy" @click="checkOperation">{{ $t('label.vmiso.check') }}</a-button>
      <a-button v-if="stage === 'INSTALL_PENDING'" :disabled="busy" @click="stage = 'REBOOT_REQUIRED'">{{ $t('label.vmprocess.tools.reboot') }}</a-button>
      <a-button v-if="['INSTALL_PENDING', 'REBOOT_REQUIRED'].includes(stage) || (stage === 'FAILED' && canVerify)" type="primary" :loading="busy" @click="verify">{{ $t('label.vmprocess.tools.verify') }}</a-button>
      <a-button v-else-if="stage === 'SELECT'" type="primary" :loading="busy" :disabled="catalog?.status !== 'MATCHED' || !allowed || (catalog.selectionSource === 'REGISTERED_OS' && !osConfirmed)" @click="attach">{{ $t('label.vmprocess.tools.attach') }}</a-button>
      <a-button v-if="stage === 'FAILED' && !canVerify" :loading="busy" @click="open">{{ $t('label.refresh') }}</a-button>
    </template>
  </a-modal>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { reactive } from 'vue'
import { attachedIsos, isoActionReason, isoOperations, startIsoOperation } from '@/utils/vmIsoActions'
import { requiredRpcs } from './vmProcessDisplay'
import { matchesToolsIsoChecksum } from './vmProcessToolsChecksum'

const result = (json, command) => json?.[command.toLowerCase() + 'response']
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
// Retain accepted read jobs across dialog close/reopen; scope includes VM, user and project.
const verificationJobs = reactive({})

export default {
  name: 'VmProcessToolsDialog',
  props: { visible: Boolean, resource: { type: Object, required: true }, catalog: { type: Object, default: null }, capability: { type: Object, default: null } },
  emits: ['close', 'verified'],
  data () { return { stage: 'SELECT', error: '', media: null, vm: null, busy: false, osConfirmed: false, generation: 0, disposed: false } },
  computed: {
    scopeKey () { return JSON.stringify([this.resource.id, this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.$store.state?.user?.token]) },
    allowed () { return 'attachIso' in (this.$store.getters.apis || {}) },
    operation () { return isoOperations['process-tools:' + this.scopeKey] },
    canVerify () { return this.vm && attachedIsos(this.vm).some(item => item.id === this.catalog?.isoId) },
    installCommand () {
      return this.catalog?.isoFamily === 'windows'
        ? this.$t('message.vmprocess.tools.command.windows')
        : this.$t('message.vmprocess.tools.command.linux')
    }
  },
  watch: {
    visible (value) { if (value) this.open(); else { this.generation++; this.busy = false } },
    scopeKey () { this.generation++; this.stage = 'SELECT'; this.error = ''; this.media = null; this.vm = null; this.busy = false; this.osConfirmed = false }
  },
  beforeUnmount () { this.disposed = true; this.generation++ },
  methods: {
    context () { return { generation: this.generation, key: this.scopeKey, vmId: this.resource.id, catalog: { ...this.catalog } } },
    current (ctx) { return !this.disposed && this.visible && ctx.generation === this.generation && ctx.key === this.scopeKey },
    assertCurrent (ctx) { if (!this.current(ctx)) throw new Error(this.$t('message.list.refresh.stale')) },
    showError (ctx, error) { if (this.current(ctx)) { this.error = this.$t(error?.response?.data?.errorresponse?.errortext || error.message); this.stage = 'FAILED' } },
    catalogError () { return this.$t('message.vmprocess.tools.catalog.' + (this.catalog?.status || 'NOT_CONFIGURED').toLowerCase()) },
    async loadMedia (ctx) {
      this.assertCurrent(ctx)
      const catalog = ctx.catalog
      if (catalog.status !== 'MATCHED') throw new Error(this.catalogError())
      if (!this.allowed) throw new Error(this.$t('message.vmprocess.tools.permission'))
      const current = result(await getAPI('getVirtualMachineProcessCapabilities', { virtualmachineid: ctx.vmId }), 'getVirtualMachineProcessCapabilities')?.processcapability?.toolsiso
      this.assertCurrent(ctx)
      if (current?.status !== 'MATCHED') throw new Error(this.$t('message.vmprocess.tools.catalog.' + (current?.status || 'OS_UNKNOWN').toLowerCase()))
      if (['isoId', 'name', 'checksum', 'zoneId', 'arch', 'isoFamily', 'selectionSource', 'registeredOsName'].some(field => current[field] !== catalog[field])) throw new Error(this.$t('message.list.refresh.stale'))
      const vmResponse = await getAPI('listVirtualMachines', { id: ctx.vmId })
      this.assertCurrent(ctx)
      const vm = result(vmResponse, 'listVirtualMachines')?.virtualmachine?.find(item => item.id === ctx.vmId)
      if (!vm) throw new Error(this.$t('message.list.refresh.stale'))
      if (vm.zoneid !== catalog.zoneId) throw new Error(this.$t('message.vmprocess.tools.zone'))
      const isoResponse = await getAPI('listIsos', { id: catalog.isoId, zoneid: vm.zoneid, isofilter: 'executable', listall: true })
      this.assertCurrent(ctx)
      const iso = result(isoResponse, 'listIsos')?.iso?.find(item => item.id === catalog.isoId)
      if (!iso) throw new Error(this.$t('message.vmprocess.tools.missing'))
      if (iso.isready !== true) throw new Error(this.$t('message.vmprocess.tools.notready'))
      if (iso.name !== catalog.name) throw new Error(this.$t('message.list.refresh.stale'))
      if (iso.zoneid && iso.zoneid !== vm.zoneid) throw new Error(this.$t('message.vmprocess.tools.zone'))
      if (iso.arch && iso.arch !== catalog.arch) throw new Error(this.$t('message.vmprocess.tools.arch'))
      if (!matchesToolsIsoChecksum(iso.checksum, catalog.checksum)) throw new Error(this.$t('message.vmprocess.tools.checksum'))
      this.vm = vm; this.media = iso
      return { vm, iso }
    },
    async open () {
      this.generation++; const ctx = this.context()
      this.error = ''; this.vm = null; this.media = null; this.osConfirmed = false
      this.stage = 'SELECT'; this.busy = true
      try {
        const { vm } = await this.loadMedia(ctx)
        if (attachedIsos(vm).some(item => item.id === ctx.catalog.isoId)) this.stage = 'INSTALL_PENDING'
        else if (this.operation?.running || this.operation?.items?.some(item => item.status === 'unknown')) throw new Error(this.$t('message.job.result.unknown'))
        else {
          const reason = isoActionReason(vm, true)
          if (reason) throw new Error(this.$t(reason))
        }
      } catch (error) { this.showError(ctx, error) } finally { if (this.current(ctx)) this.busy = false }
    },
    async attach () {
      if (this.busy || !this.allowed || (this.catalog?.selectionSource === 'REGISTERED_OS' && !this.osConfirmed)) return
      const ctx = this.context()
      this.error = ''; this.busy = true
      try {
        const { vm, iso } = await this.loadMedia(ctx)
        if (attachedIsos(vm).some(item => item.id === iso.id)) { this.stage = 'INSTALL_PENDING'; return }
        const reason = isoActionReason(vm, true)
        if (reason) throw new Error(this.$t(reason))
        const key = 'process-tools:' + this.scopeKey
        const originalPage = this.$route.path
        this.stage = 'CONNECTING'
        const op = startIsoOperation(key, { api: 'attachIso', items: [iso] }, {
          current: () => key === 'process-tools:' + this.scopeKey,
          refresh: async () => { await this.loadMedia(ctx) },
          validate: async item => {
            const fresh = await this.loadMedia(ctx)
            const issue = isoActionReason(fresh.vm, true)
            if (issue) throw new Error(this.$t(issue))
            if (attachedIsos(fresh.vm).some(entry => entry.id === item.id)) throw new Error(this.$t('message.vmiso.changed'))
          },
          submit: item => postAPI('attachIso', { virtualmachineid: vm.id, id: item.id }).then(json => result(json, 'attachIso')),
          poll: (jobId, item) => this.$pollJob({ jobId, retry: true, originalPage, resourceId: vm.id, title: this.$t('label.vmiso.attach'), description: item.name, showLoading: false, showSuccessMessage: false, action: { api: 'attachIso', isFetchData: true } })
        })
        await op.done
        this.assertCurrent(ctx)
        if (op.items[0].status === 'success' && this.canVerify) this.stage = 'INSTALL_PENDING'
        else { this.stage = 'FAILED'; this.error = this.$t(op.items[0].error || 'message.job.result.unknown') }
      } catch (error) { this.showError(ctx, error) } finally { if (this.current(ctx)) this.busy = false }
    },
    async checkOperation () {
      if (!this.operation || this.busy) return
      const ctx = this.context()
      this.busy = true
      try {
        const item = this.operation.items[0]
        const job = item.jobId ? await this.$pollJob({ jobId: item.jobId, retry: true, originalPage: this.$route.path, resourceId: ctx.vmId, title: this.$t('label.vmiso.attach'), showLoading: false, showSuccessMessage: false }) : null
        this.assertCurrent(ctx)
        item.status = job?.jobstatus === 1 ? 'success' : job?.jobstatus === 2 ? 'failed' : 'unknown'
        item.error = job?.jobresult?.errortext || (item.status === 'unknown' ? 'message.job.result.unknown' : '')
        await this.loadMedia(ctx)
        if (this.canVerify) { this.stage = 'INSTALL_PENDING'; this.error = '' } else this.error = this.$t(this.operation.items[0].error || 'message.job.result.unknown')
      } catch (error) { this.showError(ctx, error) } finally { if (this.current(ctx)) this.busy = false }
    },
    async verify () {
      if (this.busy) return
      const ctx = this.context()
      this.busy = true; this.stage = 'VERIFYING'; this.error = ''
      try {
        const capability = result(await getAPI('getVirtualMachineProcessCapabilities', { virtualmachineid: ctx.vmId }), 'getVirtualMachineProcessCapabilities')?.processcapability?.processstate
        this.assertCurrent(ctx)
        if (!capability || !requiredRpcs.every(rpc => capability.rpcs?.[rpc] === 'ENABLED')) throw new Error(this.$t('message.vmprocess.tools.rpc'))
        if (capability.kind !== 'capability' || capability.schemaVersion !== '1.0' || capability.readiness !== 'READY' || capability.authority?.vmUuid !== ctx.vmId || !capability.allowedActions?.includes('process.list')) throw new Error(this.$t('message.vmprocess.tools.adapter'))
        if (!verificationJobs[ctx.key]) {
          const started = result(await postAPI('refreshVirtualMachineProcesses', { virtualmachineid: ctx.vmId }), 'refreshVirtualMachineProcesses')
          if (!started?.jobid) throw new Error(this.$t('message.vmprocess.result.missing'))
          verificationJobs[ctx.key] = started.jobid
          this.assertCurrent(ctx)
        }
        let job
        for (let attempt = 0; attempt < 60; attempt++) {
          job = result(await getAPI('queryAsyncJobResult', { jobid: verificationJobs[ctx.key] }), 'queryAsyncJobResult')
          this.assertCurrent(ctx)
          if (job?.jobstatus !== 0) break
          await pause(400)
        }
        if (!job || job.jobstatus === 0) throw new Error(this.$t('message.vmprocess.tools.timeout'))
        delete verificationJobs[ctx.key]
        const snapshot = job.jobresult?.processsnapshot?.processstate
        if (job.jobstatus !== 1 || snapshot?.schemaVersion !== '1.0' || snapshot.kind !== 'snapshot' ||
            snapshot.authority?.vmUuid !== ctx.vmId || !snapshot.snapshotId || !snapshot.bootId ||
            !['OK', 'PARTIAL'].includes(snapshot.status) || !Array.isArray(snapshot.processes) ||
            !(Date.parse(snapshot.observedAt) <= Date.now() + 5000 && Date.parse(snapshot.expiresAt) > Date.now())) {
          throw new Error(job?.jobresult?.errortext || this.$t('message.vmprocess.tools.probe'))
        }
        this.stage = 'READY'; this.$emit('verified')
      } catch (error) { this.showError(ctx, error) } finally { if (this.current(ctx)) this.busy = false }
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
