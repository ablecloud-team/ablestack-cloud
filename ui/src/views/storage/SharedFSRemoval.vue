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
  <a-modal
class="mold-dialog sharedfs-removal-dialog"
centered
:visible="true"
:maskClosable="false"
:width="900"
    :title="$t(currentAction.label || 'label.destroy.sharedfs')"
:confirmLoading="submitting"
    :okButtonProps="{ disabled: !canSubmit }"
:okText="$t('label.ok')"
:cancelText="$t('label.cancel')"
    @cancel="close"
@ok="submit">
    <a-spin :spinning="loading">
      <a-alert show-icon type="warning" :message="$t('message.storage.service.removal.downtime')" />
      <a-form layout="vertical">
        <a-form-item :label="$t('label.storage.service.removal.policy')">
          <a-select v-model:value="policy" @change="refreshPlan">
            <a-select-option value="PRESERVE_VOLUMES">{{ $t('label.storage.service.removal.preserve') }}</a-select-option>
            <a-select-option value="DELETE_VOLUMES">{{ $t('label.storage.service.removal.delete') }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-alert v-if="readError" type="error" show-icon :message="$t('message.storage.service.removal.preview.failed')" />
        <a-button :loading="loading" @click="refreshPlan"><ReloadOutlined />{{ $t('label.refresh') }}</a-button>
        <a-table :dataSource="volumes" :columns="volumeColumns" :pagination="false" rowKey="uuid" :scroll="{ x: 650 }">
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'sizeBytes'">{{ $bytesToHumanReadableSize(record.sizeBytes) }}</template>
            <template v-if="column.key === 'action'">{{ $t(policy === 'PRESERVE_VOLUMES' ? 'label.storage.service.removal.preserve' : 'label.storage.service.removal.delete') }}</template>
          </template>
        </a-table>
        <a-form-item v-if="apiName === 'destroySharedFileSystem'" :label="$t('label.expunge')">
          <a-switch v-model:checked="expunge" />
          <div>{{ $t('message.storage.service.removal.finalization') }}</div>
        </a-form-item>
        <template v-if="policy === 'DELETE_VOLUMES'">
          <a-alert type="error" show-icon :message="$t('message.storage.service.removal.data.loss')" />
          <a-form-item :label="$t('label.storage.service.removal.confirm.name')">
            <a-input v-model:value="confirmation" :placeholder="resource.name" />
          </a-form-item>
          <a-checkbox v-model:checked="acknowledged">{{ $t('message.storage.service.removal.confirm.loss') }}</a-checkbox>
        </template>
      </a-form>
    </a-spin>
  </a-modal>
</template>
<script>
import { ReloadOutlined } from '@ant-design/icons-vue'
import { getAPI, postAPI } from '@/api'
export default {
  name: 'SharedFSRemoval',
  components: { ReloadOutlined },
  props: { resource: { type: Object, required: true }, currentAction: { type: Object, required: true } },
  emits: ['close-action'],
  data () {
    return {
      policy: 'PRESERVE_VOLUMES',
      expunge: false,
      confirmation: '',
      acknowledged: false,
      loading: false,
      submitting: false,
      readError: false,
      plan: null,
      requestToken: 0
    }
  },
  computed: {
    apiName () { return this.currentAction.api || 'destroySharedFileSystem' },
    volumes () { return this.plan?.volumes || [] },
    canSubmit () {
      return !this.loading && !this.submitting && !this.readError && !!this.plan?.planHash &&
        (this.policy === 'PRESERVE_VOLUMES' || (this.acknowledged && this.confirmation === this.resource.name))
    },
    volumeColumns () {
      return ['name', 'uuid', 'sizeBytes', 'action'].map(key => ({
        key,
        dataIndex: key,
        title: this.$t({ name: 'label.name', uuid: 'label.id', sizeBytes: 'label.size', action: 'label.action' }[key])
      }))
    }
  },
  created () { this.refreshPlan() },
  beforeUnmount () { this.requestToken++ },
  methods: {
    close () { if (!this.submitting) this.$emit('close-action') },
    async refreshPlan () {
      const request = ++this.requestToken
      this.plan = null
      this.loading = true
      this.readError = false
      this.confirmation = ''
      this.acknowledged = false
      try {
        const response = await getAPI('getSharedFileSystemDeletionPlan', { id: this.resource.id, datavolumepolicy: this.policy },
          { preserveOnFailure: true, timeout: 15000 })
        if (request !== this.requestToken) return
        const entry = response.getsharedfilesystemdeletionplanresponse.sharedfilesystemdeletionplan
        const plan = JSON.parse(entry.plan)
        if (plan.policy !== this.policy || !Array.isArray(plan.volumes) || !plan.planHash) throw new Error('Invalid removal preview')
        this.plan = plan
      } catch (error) {
        if (request === this.requestToken) this.readError = true
      } finally {
        if (request === this.requestToken) this.loading = false
      }
    },
    async submit () {
      if (!this.canSubmit) return
      this.submitting = true
      const params = { id: this.resource.id, datavolumepolicy: this.policy }
      if (this.apiName === 'destroySharedFileSystem') {
        params.expunge = this.expunge
        params.forced = this.resource.state === 'Ready'
      }
      if (this.policy === 'DELETE_VOLUMES') {
        params.confirmdataloss = this.confirmation
        params.expectedplanhash = this.plan.planHash
      }
      try {
        const response = await postAPI(this.apiName, params)
        this.$pollJob({
          jobId: response[this.apiName.toLowerCase() + 'response'].jobid,
          title: this.$t(this.currentAction.label),
          description: this.resource.name,
          successMessage: this.$t('message.success'),
          errorMessage: this.$t('message.error')
        })
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error)
        // A changed inventory invalidates the approval and must be reviewed again.
        this.refreshPlan()
      } finally { this.submitting = false }
    }
  }
}
</script>
<style scoped>
.sharedfs-removal-dialog :deep(.ant-modal-content) { display: flex; flex-direction: column; max-height: calc(100vh - 48px); }
.sharedfs-removal-dialog :deep(.ant-modal-body) { overflow-y: auto; min-height: 0; }
.sharedfs-removal-dialog :deep(.ant-modal-header), .sharedfs-removal-dialog :deep(.ant-modal-footer) { flex-shrink: 0; }
</style>
