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
  <section v-for="operation in operations" :key="operation.created" class="source-operation" data-testid="creation-source-operation">
    <strong>{{ $t('label.creation.source.job') }} · {{ operation.name || operation.sourceid }}</strong>
    <p>{{ $t('message.creation.source.job.' + (operation.status === 'submitting' ? 'pending' : operation.status)) }}</p>
    <p v-if="operation.jobid" class="operation-meta">{{ $t('label.id') }}: {{ operation.jobid }}</p>
    <p v-if="operation.vmid"><router-link :to="'/vm/' + operation.vmid">{{ $t('label.creation.source.inspect.vm') }} · {{ operation.vmid }}</router-link></p>
    <a-alert v-if="operation.error" type="error" show-icon :message="errorMessage(operation.error)" />
    <details v-if="operation.error && errorMessage(operation.error) !== operation.error" class="operation-meta">
      <summary>{{ $t('label.creation.source.error.details') }}</summary>
      <pre>{{ operation.error }}</pre>
    </details>
    <a-button v-if="['pending', 'unknown', 'failed'].includes(operation.status)" :loading="checking" @click="check(operation)">{{ $t('message.creation.source.job.check') }}</a-button>
    <a-button v-if="operation.status === 'failed' && operation.vmid && operation.retryable" :loading="checking" @click="retryStart(operation)">{{ $t('label.creation.source.start.retry') }}</a-button>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { vmCreationSourceErrorMessage } from '@/utils/vmCreationSourceError'
export default {
  props: { operations: { type: Array, default: () => [] }, storageKey: { type: String, required: true } },
  emits: ['update:operations'],
  data () { return { timer: null, checking: false, alive: true } },
  watch: { operations: { deep: true, handler () { this.save(); this.schedule() } } },
  mounted () {
    try {
      const operations = JSON.parse(sessionStorage.getItem(this.storageKey) || '[]')
      operations.forEach(op => { if (op.status === 'submitting') op.status = 'unknown' })
      if (operations.length) this.$emit('update:operations', operations)
    } catch (error) { sessionStorage.removeItem(this.storageKey) }
    this.schedule()
  },
  beforeUnmount () { this.alive = false; clearTimeout(this.timer); this.save() },
  methods: {
    errorMessage (error) { return vmCreationSourceErrorMessage(error, this.$t) },
    save () { sessionStorage.setItem(this.storageKey, JSON.stringify(this.operations)) },
    schedule () {
      clearTimeout(this.timer)
      if (!this.alive || this.checking || !this.operations.some(op => op.jobid && op.status === 'pending')) return
      this.timer = setTimeout(async () => { for (const op of this.operations.filter(op => op.jobid && op.status === 'pending')) await this.check(op); this.schedule() }, 2500)
    },
    async inspectVm (operation) {
      if (!operation.vmid) return
      const vm = (await getAPI('listVirtualMachines', { id: operation.vmid }, { backgroundJob: true, timeout: 15000 })).listvirtualmachinesresponse.virtualmachine?.[0]
      const volumes = (await getAPI('listVolumes', { virtualmachineid: operation.vmid, type: 'ROOT' }, { backgroundJob: true, timeout: 15000 })).listvolumesresponse.volume || []
      operation.retryable = vm?.state === 'Stopped' && volumes.length === 1 && volumes[0].state === 'Ready'
    },
    async recover (operation) {
      if (!operation.name) return
      const result = (await getAPI('listVirtualMachines', { keyword: operation.name, details: 'all' }, { backgroundJob: true, timeout: 15000 })).listvirtualmachinesresponse
      const matches = (result.virtualmachine || []).filter(vm => (vm.name === operation.name || vm.displayname === operation.name) &&
        vm.details?.['vm.creation.source.id'] === operation.sourceid && new Date(vm.created).getTime() >= new Date(operation.created).getTime() - 5000)
      if (matches.length !== 1) return
      operation.vmid = matches[0].id
      const jobs = (await getAPI('listAsyncJobs', { listall: false }, { backgroundJob: true, timeout: 15000 })).listasyncjobsresponse.asyncjobs || []
      const matchingJobs = jobs.filter(job => job.jobinstanceid === operation.vmid && /DeployVMCmd/.test(job.cmd || '') &&
        new Date(job.created).getTime() >= new Date(operation.created).getTime() - 5000)
      if (matchingJobs.length === 1) operation.jobid = matchingJobs[0].jobid
    },
    async check (operation) {
      if (this.checking) return
      this.checking = true
      try {
        if (!operation.jobid) await this.recover(operation)
        if (!operation.jobid) return
        const job = (await getAPI('queryAsyncJobResult', { jobid: operation.jobid }, { backgroundJob: true, timeout: 15000 })).queryasyncjobresultresponse
        if (!this.alive) return
        const vm = job.jobresult?.virtualmachine
        if (vm?.id) operation.vmid = vm.id
        else if (job.jobinstancetype === 'VirtualMachine' && job.jobinstanceid) operation.vmid = job.jobinstanceid
        if (job.jobstatus === 0) operation.status = 'pending'
        else if (job.jobstatus === 1) { operation.status = 'complete'; operation.error = ''; operation.retryable = false } else { operation.status = 'failed'; operation.error = job.jobresult?.errortext || this.$t('message.creation.source.job.failed'); await this.inspectVm(operation) }
      } catch (error) { if (this.alive) { operation.status = 'unknown'; operation.error = this.$t('message.creation.source.job.unknown') } } finally { this.checking = false; if (this.alive) { this.save(); this.schedule() } }
    },
    async retryStart (operation) {
      this.checking = true
      try {
        await this.inspectVm(operation)
        if (!operation.retryable) throw new Error(this.$t('message.creation.source.job.failed'))
        const result = (await postAPI('startVirtualMachine', { id: operation.vmid }, { preserveOnFailure: true })).startvirtualmachineresponse
        operation.jobid = result.jobid; operation.status = 'pending'; operation.error = ''; operation.retryable = false
      } catch (error) { operation.error = error.response?.data?.errorresponse?.errortext || error.message } finally { this.checking = false; this.save(); this.schedule() }
    }
  }
}
</script>
<style lang="less" scoped>
.source-operation { border: 1px solid var(--ui-border); border-radius: 6px; padding: 16px; margin-bottom: 16px; color: var(--ui-text); background: var(--ui-bg-elevated); }
.operation-meta { color: var(--ui-text-secondary); overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; color: var(--ui-text-secondary); }
.ant-button { margin: 10px 10px 0 0; }
</style>
