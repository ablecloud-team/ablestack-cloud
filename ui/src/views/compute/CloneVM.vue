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
  <a-spin :spinning="loading || allocationLookupLoading">
    <a-alert v-if="allocationLookupError" type="error" show-icon :message="$t('message.clone.allocation.lookup.failed')" style="margin-bottom: 16px">
      <template #description><a-button @click="fetchSourceVolumes">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-alert v-if="hasFullRbdAllocation" type="info" show-icon :message="$t('message.clone.allocation.full.linked')" style="margin-bottom: 16px" />
    <a-alert v-if="rawSourceRequiresStop" type="warning" show-icon :message="$t('message.clone.allocation.raw.stop')" style="margin-bottom: 16px" />
    <a-alert
      v-if="extraConfigDiskAttached"
      type="warning"
      show-icon
      :message="$t('message.clone.extraconfig.disk.blocked')"
      style="margin-bottom: 16px" />
    <section v-if="batchRequest" class="clone-batch-result" :aria-label="$t('label.clone.batch.title')">
      <a-alert :type="batchResult?.status === 'SUCCEEDED' ? 'success' : batchBusy ? 'info' : 'warning'" show-icon :message="$t('label.clone.batch.title')" style="margin-bottom: 12px">
        <template #description>
          <div>{{ batchRequest.params.name }} · {{ $t('label.deploy.vm.number') }}: {{ batchRequest.params.count }} · {{ batchResult?.status || 'UNKNOWN' }}</div>
          <div>{{ $t('label.clone.batch.request') }}: <code>{{ batchRequest.id }}</code></div>
          <div v-if="batchResult?.jobid">Job: <code>{{ batchResult.jobid }}</code></div>
        </template>
      </a-alert>
      <a-alert v-if="batchError" type="error" show-icon :message="batchError" style="margin-bottom: 12px" />
      <a-alert v-if="batchStorageError" type="warning" show-icon :message="batchStorageError" style="margin-bottom: 12px" />
      <a-alert v-if="batchResult?.error" type="warning" show-icon :message="batchResult.error" style="margin-bottom: 12px" />
      <a-alert v-if="retryTargets.some(t => t.retryaction === 'CREATE')" type="info" show-icon :message="$t('message.clone.batch.recapture')" style="margin-bottom: 12px" />
      <a-table :columns="batchColumns" :data-source="batchResult?.targets || []" row-key="index" :pagination="false" size="small" :scroll="{ x: 820 }">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'target'">
            <router-link v-if="record.id && record.state !== 'REMOVED'" :to="'/vm/' + record.id" @click="closeAction">{{ record.name }}</router-link><span v-else>{{ record.name }}</span>
            <div v-if="record.id" class="batch-uuid">{{ record.id }}</div>
          </template>
          <template v-else-if="column.key === 'outcome'">
            <div>{{ record.phase === 'ACCESS_RESTRICTED' ? $t('label.clone.batch.restricted') : record.created ? $t('label.clone.batch.created') : $t('label.clone.batch.notcreated') }} · {{ record.state }}</div>
            <div>{{ record.phase }} · {{ $t('label.clone.batch.action.' + record.retryaction) }}</div>
            <div v-if="record.error" class="batch-error">{{ record.error }}</div>
          </template>
          <template v-else-if="column.key === 'cleanup'">
            <div>{{ record.cleanup }}</div>
            <div v-for="disk in record.volumes" :key="disk.id" class="batch-uuid">
              {{ disk.type }} · {{ disk.state }} · {{ disk.cleanup }}
              <router-link v-if="!['NOT_CREATED', 'Expunged'].includes(disk.state)" :to="'/volume/' + disk.id" @click="closeAction">{{ disk.id }}</router-link><span v-else>{{ disk.id }}</span>
            </div>
          </template>
        </template>
      </a-table>
      <p v-if="!batchBusy && !retryTargets.length && batchResult?.status !== 'SUCCEEDED'">{{ $t('message.clone.batch.inspect') }}</p>
      <div class="batch-actions">
        <a-button :loading="queryingBatch" @click="refreshBatch">{{ $t('label.refresh') }}</a-button>
        <a-button v-if="batchUnconfirmed" :loading="loading" :disabled="queryingBatch" @click="submitBatch">{{ $t('label.clone.batch.resend') }}</a-button>
        <a-button v-if="retryTargets.length" type="primary" :disabled="batchBusy || loading || queryingBatch" @click="retryBatch">{{ $t('label.clone.batch.retry') }} ({{ retryTargets.map(t => t.index).join(', ') }})</a-button>
        <a-button :disabled="batchBusy || batchUnconfirmed || loading || queryingBatch" @click="newBatch">{{ $t('label.clone.batch.new') }}</a-button>
        <a-button @click="closeAction">{{ $t('label.close') }}</a-button>
      </div>
    </section>
    <a-form
      v-else
      class="form-layout"
      layout="vertical"
      :ref="formRef"
      :model="form"
      :rules="rules"
      v-ctrl-enter="handleSubmit"
      @finish="handleSubmit">
      <!-- <a-alert style="margin-bottom: 5px" type="info" show-icon>
        <template #message>
          <span v-html="$t('message.clone.vm')" />
        </template>
      </a-alert> -->
      <a-form-item name="name" ref="name">
        <template #label>
          <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
        </template>
        <a-input v-model:value="form.name" v-focus="true" />
      </a-form-item>
      <a-form-item v-if="resource.pooltype == 'RBD'" ref="clonetype" name="clonetype">
        <template #label>
          <tooltip-label :title="$t('label.clonetype')" :tooltip="apiParams.clonetype.description"/>
        </template>
        <a-select v-model:value="form.clonetype" @change="typeChange">
          <a-select-option value='full'>Full</a-select-option>
          <a-select-option value='linked' :disabled="hasFullRbdAllocation">Linked</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item name="startvm" ref="startvm" >
        <template #label>
          <tooltip-label :title="$t('label.action.start.instance')" :tooltip="apiParams.startvm.description"/>
        </template>
        <a-switch v-model:checked="form.startvm" />
      </a-form-item>
      <a-form-item :label="$t('label.deploy.vm.number')" name="count" ref="vmNumber">
        <a-input-number :min=1 :max=50 :maxlength="2" v-model:value="form.count" />
      </a-form-item>

      <div :span="24" class="action-button">
        <a-button :loading="loading" @click="closeAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" :disabled="cloneBlocked" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
      </div>
    </a-form>
  </a-spin>
</template>
<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI } from '@/api'
import { batchStorageKey, newCloneRequest, cloneAttemptParams, retryableTargets, cloneBatchBusy, retryCloneRequest, readCloneRequest } from '@/utils/cloneBatch'
import { hasExtraConfigDisk } from '@/utils/vmClone'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'CloneVM',
  components: {
    TooltipLabel
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false,
      batchRequest: null,
      batchResult: null,
      batchError: '',
      batchStorageError: '',
      queryingBatch: false,
      batchDisposed: false,
      batchTimer: null,
      sourceVolumes: [],
      allocationLookupLoading: false,
      allocationLookupError: ''
    }
  },
  computed: {
    batchBusy () { return cloneBatchBusy(this.batchResult) },
    batchUnconfirmed () { return !this.batchResult || this.batchResult.status === 'NOTFOUND' || (this.batchResult.status === 'QUEUED' && !this.batchResult.jobid) || this.batchResult.attemptid !== (this.batchRequest?.attempt?.id || this.batchRequest?.id) },
    retryTargets () { return this.batchBusy || this.batchUnconfirmed ? [] : retryableTargets(this.batchResult) },
    batchColumns () {
      return [
        { title: '#', dataIndex: 'index', key: 'index', width: 45 },
        { title: this.$t('label.name'), key: 'target', width: 210 },
        { title: this.$t('label.clone.batch.outcome'), key: 'outcome', width: 240 },
        { title: this.$t('label.clone.batch.cleanup'), key: 'cleanup', width: 320 }
      ]
    },
    extraConfigDiskAttached () {
      return hasExtraConfigDisk(this.resource)
    },
    hasFullRbdAllocation () {
      return this.resource.pooltype === 'RBD' && this.sourceVolumes.some(v =>
        v.allocation?.policyorigin === 'explicit' && v.allocation?.requestedpolicy === 'full')
    },
    rawSourceRequiresStop () {
      return this.resource.pooltype === 'SharedMountPoint' && this.resource.state !== 'Stopped' &&
        this.sourceVolumes.some(v => v.allocation?.policyorigin === 'explicit') &&
        this.sourceVolumes.some(v => v.allocation?.actualformat === 'raw')
    },
    cloneBlocked () {
      return this.extraConfigDiskAttached || this.allocationLookupLoading || !!this.allocationLookupError ||
        this.rawSourceRequiresStop || (this.hasFullRbdAllocation && this.form.clonetype === 'linked')
    }
  },
  watch: {
    'form.count' () {
      if (this.form.name && this.formRef.value) {
        this.formRef.value.validateFields(['name']).catch(() => {})
      }
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('cloneVirtualMachine')
  },
  created () {
    this.initForm()
    this.fetchSourceVolumes()
    this.restoreBatch()
  },
  beforeUnmount () {
    this.batchDisposed = true
    clearTimeout(this.batchTimer)
  },
  methods: {
    async fetchSourceVolumes () {
      this.allocationLookupLoading = true
      this.allocationLookupError = ''
      try {
        const json = await getAPI('listVolumes', { virtualmachineid: this.resource.id, listall: true })
        const volumes = json.listvolumesresponse.volume || []
        if (!volumes.length) throw new Error('No source volumes')
        this.sourceVolumes = volumes
      } catch (error) {
        this.allocationLookupError = this.$t('message.clone.allocation.lookup.failed')
      } finally {
        this.allocationLookupLoading = false
      }
    },
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        clonetype: 'full',
        startvm: false,
        count: 1
      })
      this.rules = reactive({
        name: [
          { required: true, message: this.$t('label.required') },
          { validator: this.validateCloneName }
        ],
        count: [{ required: true, type: 'integer', min: 1, max: 50, message: this.$t('message.clone.count.invalid') }]
      })
    },
    async validateCloneName (rule, value) {
      if (!value) return
      if (!/^[a-zA-Z]/.test(value) || /[^a-zA-Z0-9-]/.test(value) || value.endsWith('-')) {
        throw new Error(this.$t('message.clone.name.invalid'))
      }
      const count = this.form.count
      const suffix = Number.isInteger(count) && count > 1 ? `-${count}` : ''
      if (value.length + suffix.length > 63) {
        throw new Error(this.$t('message.clone.name.length', { max: 63 - suffix.length }))
      }
    },
    handleSubmit (e) {
      if (e) e.preventDefault()
      if (this.loading || this.cloneBlocked) return
      this.formRef.value.validate().then(() => {
        if (this.loading || this.cloneBlocked) return
        const values = toRaw(this.form)

        const params = {
          virtualmachineid: this.resource.id
        }
        for (const key in values) {
          if (values[key]) {
            params[key] = values[key]
          }
        }
        params.startvm = this.form.startvm
        if (this.resource.pooltype !== 'RBD') {
          params.clonetype = null
        }
        this.batchRequest = newCloneRequest(params)
        this.batchResult = null
        this.persistBatch()
        this.submitBatch()
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    storageKey () {
      return batchStorageKey(this.$store?.getters?.userInfo?.id || 'current', this.resource.id)
    },
    persistBatch () {
      try { window.localStorage.setItem(this.storageKey(), JSON.stringify(this.batchRequest)); this.batchStorageError = '' } catch (error) { this.batchStorageError = this.$t('message.clone.batch.storage') }
    },
    restoreBatch () {
      try { this.batchRequest = readCloneRequest(window.localStorage, this.storageKey(), this.resource.id) } catch (error) { this.batchStorageError = this.$t('message.clone.batch.storage') }
      if (this.batchRequest) this.refreshBatch()
    },
    async submitBatch () {
      if (!this.batchRequest || this.loading) return
      this.loading = true
      this.batchError = ''
      try {
        await getAPI('cloneVirtualMachine', cloneAttemptParams(this.batchRequest))
        await this.refreshBatch()
      } catch (error) {
        this.$notifyError(error)
        const result = await this.refreshBatch()
        if (result?.status === 'NOTFOUND' && error.response?.data?.clonevirtualmachineresponse?.errorcode) {
          this.batchRequest = null
          window.localStorage.removeItem(this.storageKey())
        } else if (!result || this.batchUnconfirmed) {
          this.batchError = this.$t('message.clone.batch.pending')
        }
      } finally { this.loading = false }
    },
    async refreshBatch () {
      if (!this.batchRequest || this.queryingBatch || this.batchDisposed) return
      clearTimeout(this.batchTimer)
      this.queryingBatch = true
      const requestId = this.batchRequest.id
      try {
        const json = await getAPI('queryVMCloneBatch', { requestid: requestId })
        const result = json.queryvmclonebatchresponse?.clonebatch
        if (!result) throw new Error('Clone batch result unavailable')
        if (this.batchDisposed || this.batchRequest?.id !== requestId) return
        this.batchResult = result
        this.batchError = ''
        if (cloneBatchBusy(result)) this.batchTimer = setTimeout(() => this.refreshBatch(), 2000)
        return result
      } catch (error) {
        if (!this.batchDisposed) this.batchError = this.$t('message.clone.batch.query.failed')
      } finally { this.queryingBatch = false }
    },
    retryBatch () {
      if (this.batchBusy || this.loading || this.queryingBatch || !this.retryTargets.length) return
      this.batchRequest = retryCloneRequest(this.batchRequest, this.batchResult)
      this.persistBatch()
      this.submitBatch()
    },
    newBatch () {
      if (this.batchBusy || this.batchUnconfirmed || this.loading || this.queryingBatch) return
      clearTimeout(this.batchTimer)
      try { window.localStorage.removeItem(this.storageKey()) } catch (error) { this.batchError = this.$t('message.clone.batch.storage'); return }
      this.batchRequest = null
      this.batchResult = null
      this.batchError = ''
      this.initForm()
    },
    closeAction () {
      this.$emit('close-action')
    },
    typeChange (val) {
      this.form.clonetype = val
    }
  }
}
</script>

<style scoped lang="less">
  .clone-batch-result { width: 82vw; max-width: 1100px; }
  .batch-uuid { overflow-wrap: anywhere; font-size: 12px; margin-top: 4px; }
  .batch-error { color: #c9190b; overflow-wrap: anywhere; }
  .batch-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
  .form-layout {
    width: 80vw;
    @media (min-width: 800px) {
      width: 400px;
    }
  }
</style>
