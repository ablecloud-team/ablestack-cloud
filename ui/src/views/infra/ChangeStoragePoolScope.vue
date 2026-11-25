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
  <a-spin :spinning="targetsLoading || submitting">
    <a-form
      :ref="formRef"
      :model="form"
      :rules="rules"
      layout="vertical"
      v-ctrl-enter="handleSubmitForm"
      @finish="handleSubmitForm">
      <a-alert :type="targetsFailed || saveFailed ? 'error' : 'warning'" show-icon class="scope-message">
        <template #message>
          <span v-if="saveFailed">{{ $t('message.change.scope.failed') }}</span>
          <span v-else-if="targetsFailed">{{ $t('message.primary.storage.scope.targets.failed') }}</span>
          <span v-else-if="requiresCluster && !targetsLoading && !clustersList.length">{{ $t('message.primary.storage.scope.targets.empty') }}</span>
          <template v-else>
            <strong>{{ resource.name }}</strong>
            <div v-html="$t(requiresCluster ? 'message.action.primary.storage.scope.cluster' : 'message.action.primary.storage.scope.zone')"></div>
            <div v-html="$t('message.warn.change.primary.storage.scope')"></div>
          </template>
        </template>
      </a-alert>
      <a-form-item v-if="requiresCluster" name="clusterid" html-for="primary-storage-scope-cluster">
        <template #label>
          <tooltip-label :title="$t('label.clustername')" :tooltip="clusterHint" />
        </template>
        <a-select
          id="primary-storage-scope-cluster"
          v-model:value="form.clusterid"
          :placeholder="clusterHint"
          :options="clustersList"
          :disabled="targetsLoading || targetsFailed || !clustersList.length || submitting || !!jobId"
          option-filter-prop="label"
          show-search />
      </a-form-item>
      <mold-dialog-actions :busy="submitting">
        <a-button :disabled="submitting" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
        <a-button :disabled="!canSubmit" :loading="submitting" type="primary" @click="handleSubmitForm">
          {{ $t(jobId ? 'label.host.operation.recheck' : 'label.ok') }}
        </a-button>
      </mold-dialog-actions>
    </a-form>
  </a-spin>
</template>

<script>
import { ref, reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'ChangeStoragePoolScope',
  components: { MoldDialogActions, TooltipLabel },
  emits: ['refresh-data', 'close-action'],
  props: {
    resource: { type: Object, required: true }
  },
  data () {
    return {
      targetsLoading: false,
      targetsFailed: false,
      submitting: false,
      saveFailed: false,
      jobId: '',
      scopeJobObserved: false,
      clustersList: []
    }
  },
  computed: {
    requiresCluster () { return this.resource.scope === 'ZONE' },
    canSubmit () {
      return !!this.$store.getters.apis.changeStoragePoolScope && this.resource.state === 'Disabled' && !this.targetsLoading && !this.submitting &&
        (!!this.jobId || !this.requiresCluster || this.clustersList.some(cluster => cluster.value === this.form.clusterid))
    }
  },
  created () {
    this.formRef = ref()
    this.form = reactive({ clusterid: undefined })
    this.rules = { clusterid: [{ required: true, message: this.$t('message.error.select') }] }
    this.clusterHint = this.$getApiParams('changeStoragePoolScope')?.clusterid?.description || this.$t('label.clustername')
    if (this.requiresCluster) this.fetchClusters()
  },
  methods: {
    async fetchClusters () {
      if (!this.resource.zoneid || !this.$store.getters.apis.listClusters) {
        this.targetsFailed = true
        return
      }
      this.targetsLoading = true
      try {
        const response = await getAPI('listClusters', { zoneid: this.resource.zoneid }, { preserveOnFailure: true })
        this.clustersList = (response.listclustersresponse?.cluster || []).map(cluster => ({ value: cluster.id, label: cluster.name }))
        this.form.clusterid = this.clustersList[0]?.value
      } catch {
        this.targetsFailed = true
      } finally {
        this.targetsLoading = false
      }
    },
    async handleSubmitForm () {
      if (!this.canSubmit) return
      this.submitting = true
      this.saveFailed = false
      try {
        if (!this.jobId) {
          if (this.requiresCluster) await this.formRef.value.validate()
          const args = this.requiresCluster
            ? { id: this.resource.id, scope: 'CLUSTER', clusterid: this.form.clusterid }
            : { id: this.resource.id, scope: 'ZONE' }
          const response = await postAPI('changeStoragePoolScope', args, { preserveOnFailure: true })
          this.jobId = response.changestoragepoolscoperesponse?.jobid || ''
          this.scopeJobObserved = false
        }
        if (this.jobId) {
          const result = await this.$pollJob({ jobId: this.jobId, showLoading: false, retry: this.scopeJobObserved })
          this.scopeJobObserved = true
          if (result.jobstatus === 1) {
            this.$emit('refresh-data')
            this.$emit('close-action')
            return
          }
          if (result.jobstatus === 2) this.jobId = ''
        }
        this.saveFailed = true
      } catch (error) {
        if (error.errorFields?.[0]) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.saveFailed = true
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style scoped>
.scope-message { margin-bottom: 16px; }
</style>
