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
  <div class="form-layout" v-ctrl-enter="handleSubmit">
    <a-alert v-if="errorText" type="error" show-icon :message="errorText" style="margin-bottom: 16px" />
    <a-form :ref="formRef" :model="form" :rules="rules" layout="vertical" @finish="handleSubmit">
      <a-form-item v-if="apiParams.name" name="name" ref="name" :label="$t('label.name')">
        <a-input v-model:value="form.name" :disabled="loading || !!jobId" :placeholder="$t('label.name')" />
      </a-form-item>
      <a-form-item v-if="apiParams.deleteprotection" name="deleteprotection" :label="$t('label.deleteprotection')">
        <a-switch v-model:checked="form.deleteprotection" :disabled="loading || !!jobId" />
        <p class="form-help">{{ $t('message.volume.edit.protection') }}</p>
      </a-form-item>
      <a-form-item v-if="pathAvailable" name="pathEnabled" :label="$t('label.path')">
        <a-switch v-model:checked="form.pathEnabled" :disabled="loading || !!jobId" />
        <p class="form-help">{{ $t('message.volume.edit.path.help') }}</p>
        <div>{{ $t('label.current') }}: {{ target.path || $t('label.allocation.unknown') }}</div>
      </a-form-item>
      <a-alert v-else-if="admin && apiParams.path" type="info" show-icon :message="$t(pathReason)" style="margin-bottom: 16px" />
      <template v-if="pathAvailable && form.pathEnabled">
        <a-form-item name="path" ref="path" :label="$t('label.path')">
          <a-input v-model:value="form.path" :disabled="loading || !!jobId" :placeholder="$t('label.path')" />
        </a-form-item>
        <a-alert type="warning" show-icon :message="$t('message.volume.edit.path.warning')" style="margin-bottom: 16px" />
      </template>
      <div class="action-button">
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button v-if="jobId" :loading="loading" type="primary" @click="resumeJob">{{ $t('message.volume.edit.check.result') }}</a-button>
        <a-button v-else :loading="loading" type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </div>
</template>
<script>
import { ref, reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
import { volumeEditParams, volumePathEditReason, validateVolumeEditSnapshot } from '@/utils/volumeEdit'

export default {
  name: 'UpdateVolume',
  props: { resource: { type: Object, required: true } },
  emits: ['refresh-data', 'close-action'],
  data () { return { loading: false, errorText: '', jobId: null, active: true } },
  beforeCreate () { this.apiParams = this.$getApiParams('updateVolume') || {} },
  created () {
    this.target = Object.freeze({ ...this.resource })
    this.originalScope = this.scope()
    this.formRef = ref()
    this.form = reactive({ name: this.target.name, deleteprotection: this.target.deleteprotection, path: this.target.path, pathEnabled: false })
    this.rules = { name: [{ validator: (_, value) => value?.trim() ? Promise.resolve() : Promise.reject(this.$t('message.volume.edit.name.required')) }], path: [{ validator: (_, value) => !this.form.pathEnabled || value?.trim() ? Promise.resolve() : Promise.reject(this.$t('message.volume.edit.path.required')) }] }
  },
  beforeUnmount () { this.active = false },
  computed: {
    admin () { return this.$store.getters.userInfo?.roletype === 'Admin' },
    context () { return { admin: this.admin, params: this.apiParams } },
    pathReason () { return volumePathEditReason(this.target, this.context) },
    pathAvailable () { return !this.pathReason }
  },
  methods: {
    scope () {
      const s = this.$store.getters
      return JSON.stringify([this.resource.id, s.userInfo?.id, s.userInfo?.roletype, s.project?.id, this.$route.path])
    },
    current () { return this.active && this.originalScope === this.scope() },
    failure (error) {
      const body = error?.response?.data
      const text = body?.errorresponse?.errortext || Object.values(body || {}).find(value => value?.errortext)?.errortext || error?.errortext || error?.message
      this.errorText = text?.startsWith('message.') ? this.$t(text) : text || this.$t('message.request.failed')
    },
    async handleSubmit () {
      if (this.loading || this.jobId) return
      this.loading = true
      this.errorText = ''
      try {
        await this.formRef.value.validate()
        if (!this.current()) throw new Error('message.volume.edit.context')
        const params = volumeEditParams(this.target, { ...this.form }, this.context)
        if (Object.keys(params).length === 1) { this.closeModal(); return }
        const response = await getAPI('listVolumes', { id: this.target.id, listall: true, ...(this.target.projectid ? { projectid: this.target.projectid } : {}) }, { preserveOnFailure: true })
        if (!this.current()) throw new Error('message.volume.edit.context')
        const rows = response?.listvolumesresponse?.volume
        const fresh = Array.isArray(rows) && rows.length === 1 ? rows[0] : null
        validateVolumeEditSnapshot(this.target, fresh, params, this.context)
        // Reconcile an earlier ambiguous response without submitting it again.
        if (Object.entries(params).every(([key, value]) => key === 'id' || fresh[key] === value)) { this.complete(); return }
        const result = (await postAPI('updateVolume', params, { preserveOnFailure: true }))?.updatevolumeresponse
        if (!result || result.errorcode) throw new Error('message.volume.edit.unknown')
        if (!this.current()) return
        if (result.jobid) { this.jobId = result.jobid; await this.poll(false) } else if (result.volume?.id === this.target.id) this.complete()
        else throw new Error('message.volume.edit.unknown')
      } catch (error) {
        if (this.current()) {
          if (error?.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
          else this.failure(error)
        }
      } finally { this.loading = false }
    },
    async poll (retry) {
      const result = await this.$pollJob({
        jobId: this.jobId,
        batchKey: this.jobId,
        resourceId: this.target.id,
        retry,
        title: this.$t('label.action.update.volume'),
        description: this.form.name,
        successMessage: this.$t('message.success.update.volume'),
        errorMessage: this.$t('message.update.volume.failed')
      })
      if (!this.current()) return
      if (result.jobstatus === 1) this.complete()
      else if (result.jobstatus === 2) { this.jobId = null; this.failure(result.jobresult) } else this.errorText = this.$t('message.volume.edit.unknown')
    },
    async resumeJob () {
      if (this.loading || !this.jobId) return
      if (!this.current()) { this.failure(new Error('message.volume.edit.context')); return }
      this.loading = true
      try { await this.poll(true) } catch (error) { if (this.current()) this.failure(error) } finally { this.loading = false }
    },
    complete () { this.jobId = null; this.$emit('refresh-data'); this.$emit('close-action') },
    closeModal () { this.$emit('close-action') }
  }
}
</script>
<style lang="scss" scoped>
.form-layout {
  width: 85vw;
  @media (min-width: 760px) { width: 500px; }
}
.form-help { margin: 8px 0 0; opacity: .8; }
</style>
