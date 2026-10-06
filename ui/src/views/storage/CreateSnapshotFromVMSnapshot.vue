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
  <MoldDialog :title="$t('label.action.create.snapshot.from.vmsnapshot')" :closable="!loading" @cancel="closeModal">
    <VmSnapshotSummary :snapshot="target" />
    <a-spin :spinning="loading">
      <a-form :ref="formRef" :model="form" :rules="rules" @finish="handleSubmit" v-ctrl-enter="handleSubmit" layout="vertical">
        <a-form-item :label="$t('label.name')" name="name"><a-input v-focus="true" v-model:value="form.name" :placeholder="$t('label.snapshot.name')" /></a-form-item>
        <a-form-item :label="$t('label.volume')" name="volumeid">
          <a-select v-model:value="form.volumeid" show-search option-filter-prop="label">
            <a-select-option v-for="volume in volumes" :key="volume.id" :value="volume.id" :label="volume.displaytext || volume.name">{{ volume.displaytext || volume.name }} · {{ volume.id }}</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-spin>
    <template #footer>
      <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="loading" :disabled="!volumes.length" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
    </template>
  </MoldDialog>
</template>
<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import VmSnapshotSummary from '@/components/view/VmSnapshotSummary'
import { freshSnapshotContext } from '@/utils/vmSnapshotList'
import { snapshotActionReason, snapshotBusy, snapshotSubmissions, trackUnknownSnapshotSubmission } from '@/utils/vmSnapshotActions'
export default {
  name: 'CreateSnapshotFromVMSnapshot',
  components: { MoldDialog, VmSnapshotSummary },
  props: { fullWidth: { type: Boolean, default: false }, resource: { type: Object, required: true } },
  emits: ['close-action'],
  data () { return { target: Object.freeze({ ...this.resource }), volumes: [], loading: false, disposed: false } },
  computed: { security () { return JSON.stringify([this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.$store.state?.user?.token]) } },
  watch: { security () { this.$emit('close-action') } },
  created () {
    this.formRef = ref()
    this.form = reactive({})
    this.rules = reactive({ name: [{ required: true, message: this.$t('message.error.name') }], volumeid: [{ required: true, message: this.$t('message.error.select') }] })
    this.fetchData()
  },
  beforeUnmount () { this.disposed = true },
  methods: {
    async fetchData () {
      const security = this.security
      this.loading = true
      try {
        const rows = (await getAPI('listVolumes', { virtualmachineid: this.target.virtualmachineid, listall: true })).listvolumesresponse.volume || []
        if (this.disposed || security !== this.security) return
        this.volumes = rows
        this.form.volumeid = rows[0]?.id || ''
      } catch (error) { this.$notifyError(error) } finally { this.loading = false }
    },
    async handleSubmit () {
      if (this.loading) return
      this.loading = true
      const security = this.security
      const vmId = this.target.virtualmachineid
      const submission = Symbol(vmId)
      let postStarted = false
      try {
        await this.formRef.value.validate()
        const values = { ...toRaw(this.form) }
        const [context] = await freshSnapshotContext(getAPI, [this.target], 'listBackups' in this.$store.getters.apis)
        const volumes = (await getAPI('listVolumes', { virtualmachineid: vmId, id: values.volumeid, listall: true })).listvolumesresponse.volume || []
        if (this.disposed || security !== this.security || !('createSnapshotFromVMSnapshot' in this.$store.getters.apis)) throw new Error(this.$t('message.vmsnapshot.permission'))
        if (!context.snapshot || !context.vm || !volumes.some(volume => volume.id === values.volumeid && volume.virtualmachineid === vmId)) throw new Error(this.$t('message.vmsnapshot.not.ready'))
        const reason = snapshotActionReason('createSnapshotFromVMSnapshot', context.snapshot, context.vm, context.busy || snapshotBusy(vmId))
        if (reason) throw new Error(this.$t(reason))
        snapshotSubmissions[vmId] = submission
        postStarted = true
        const response = await postAPI('createSnapshotFromVMSnapshot', { ...values, vmsnapshotid: this.target.id })
        const jobId = response.createsnapshotfromvmsnapshotresponse?.jobid
        if (!jobId) {
          trackUnknownSnapshotSubmission(vmId, this.target.id)
          throw new Error(this.$t('message.vmsnapshot.submission.unknown'))
        }
        if (security !== this.security) return
        this.$pollJob({ jobId, originalPage: this.$route.path, title: this.$t('message.success.create.snapshot.from.vmsnapshot'), action: { api: 'createSnapshotFromVMSnapshot', resource: this.target }, description: values.name, successMessage: this.$t('message.success.create.snapshot.from.vmsnapshot'), errorMessage: this.$t('message.create.snapshot.from.vmsnapshot.failed'), loadingMessage: this.$t('message.create.snapshot.from.vmsnapshot.progress'), catchMessage: this.$t('error.fetching.async.job.result') }).catch(() => {})
        this.$emit('close-action')
      } catch (error) {
        const unconfirmed = postStarted && error.isAxiosError && !error.response
        const reportedError = unconfirmed ? new Error(this.$t('message.vmsnapshot.submission.unknown')) : error
        if (postStarted && error.isAxiosError && !error.response) {
          trackUnknownSnapshotSubmission(vmId, this.target.id)
        }
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.$notifyError(reportedError)
      } finally { if (snapshotSubmissions[vmId] === submission) delete snapshotSubmissions[vmId]; this.loading = false }
    },
    closeModal () { if (!this.loading) this.$emit('close-action') }
  }
}
</script>
