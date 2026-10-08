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
  <section v-if="supported">
    <a-button :loading="saving" @click="open">{{ $t('label.storage.smb.identity.repair') }}</a-button>
    <a-modal
:visible="visible"
:title="$t('label.storage.smb.identity.repair')"
:confirm-loading="saving"
:ok-button-props="{ disabled: !confirmed || saving }"
:cancel-button-props="{ disabled: saving }"
:body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
@ok="repair"
@cancel="close">
      <a-alert type="warning" show-icon :message="$t('message.storage.smb.identity.repair.maintenance')" />
      <a-form layout="vertical">
        <a-form-item :label="$t('label.storage.config.confirmation')"><a-input v-model:value="confirmation" :placeholder="instanceName" :disabled="saving" autocomplete="off" /></a-form-item>
        <a-form-item><a-checkbox v-model:checked="maintenanceWindow" :disabled="saving">{{ $t('label.storage.smb.identity.repair.acknowledge') }}</a-checkbox></a-form-item>
      </a-form>
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-alert v-if="jobId" type="info" show-icon :message="jobId" />
    </a-modal>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
export default {
  name: 'StorageSmbIdentityRepair',
  props: { instanceId: { type: String, required: true }, instanceName: { type: String, required: true } },
  emits: ['operation-updated'],
  data: () => ({ generation: 0, visible: false, confirmation: '', maintenanceWindow: false, requestKey: '', saving: false, error: '', jobId: '' }),
  computed: {
    supported () { return 'repairStorageServiceSmbIdentity' in (this.$store?.getters?.apis || {}) },
    confirmed () { return this.supported && !!this.instanceName && this.confirmation === this.instanceName && this.maintenanceWindow === true }
  },
  watch: { instanceId () { this.generation++; this.visible = false; this.saving = false; this.confirmation = ''; this.maintenanceWindow = false; this.requestKey = ''; this.error = ''; this.jobId = '' } },
  beforeUnmount () { this.generation++ },
  methods: {
    open () { this.visible = true; this.confirmation = ''; this.maintenanceWindow = false; this.error = ''; this.jobId = ''; if (!this.requestKey) this.requestKey = 'smb-repair-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) },
    close () { if (this.saving) return; this.visible = false; this.confirmation = ''; this.maintenanceWindow = false },
    async repair () {
      if (!this.confirmed || this.saving) return
      const generation = this.generation; const instance = this.instanceId
      this.saving = true; this.error = ''
      try {
        const response = await postAPI('repairStorageServiceSmbIdentity', { instanceid: instance, confirmation: this.confirmation, maintenancewindow: true, idempotencykey: this.requestKey }, { timeout: 15000 })
        if (generation !== this.generation || instance !== this.instanceId) return
        this.jobId = response.repairstorageservicesmbidentityresponse?.jobid || ''
        if (!this.jobId) throw new Error(this.$t('message.storage.smb.identity.repair.unknown'))
        let done = false
        for (let i = 0; i < 180; i++) {
          if (generation !== this.generation || instance !== this.instanceId) return
          const reply = await getAPI('queryAsyncJobResult', { jobid: this.jobId }, { timeout: 15000, preserveOnFailure: true })
          if (generation !== this.generation || instance !== this.instanceId) return
          const job = reply.queryasyncjobresultresponse
          if (job.jobstatus === 1) { done = true; break }
          if (job.jobstatus === 2) { this.$emit('operation-updated', instance); throw new Error(job.jobresult?.errortext || this.$t('message.storage.config.failed')) }
          await new Promise(resolve => setTimeout(resolve, 1000))
        }
        if (!done) throw new Error(this.$t('message.storage.smb.identity.repair.unknown'))
        this.visible = false; this.confirmation = ''; this.maintenanceWindow = false; this.requestKey = ''; this.$emit('operation-updated', instance)
      } catch (error) { if (generation === this.generation) this.error = error.message } finally { if (generation === this.generation) this.saving = false }
    }
  }
}
</script>
