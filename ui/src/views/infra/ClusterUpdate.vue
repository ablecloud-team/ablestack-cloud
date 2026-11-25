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
  <a-spin :spinning="loading">
    <a-form
      class="form-layout"
      layout="vertical"
      :ref="formRef"
      :model="form"
      :rules="rules"
      v-ctrl-enter="handleSubmit"
      @finish="handleSubmit">
      <a-alert v-if="saveStage" type="info" show-icon class="cluster-save-status" :message="$t(saveStage)" />
      <a-alert v-if="baseSaved && saveError" type="success" show-icon class="cluster-save-status" :message="$t('message.cluster.update.fields.saved')" />
      <a-alert v-if="saveError" :type="groupUnknown ? 'warning' : 'error'" show-icon class="cluster-save-status" :message="$t(saveError)" />
      <fieldset :disabled="loading || baseComplete">
        <a-form-item v-if="apiParams.clustername && resource.hypervisortype !== 'VMware'" name="name" :label="$t('label.name')">
          <a-input v-model:value="form.name" v-focus="true" />
        </a-form-item>
        <a-form-item v-if="apiParams.arch" name="arch" :label="$t('label.arch')" html-for="cluster-edit-arch">
          <a-select id="cluster-edit-arch" v-model:value="form.arch" show-search option-filter-prop="label" :options="architectureOptions" :disabled="loading || baseComplete" />
        </a-form-item>
        <a-form-item v-if="resource.hypervisortype === 'External' && resource.extensionid && apiParams.externaldetails" name="externaldetails" :label="$t('label.configuration.details')">
          <p>{{ $t('message.add.extension.resource.details') }}</p>
          <details-input v-model:value="form.externaldetails" />
        </a-form-item>
      </fieldset>
      <a-form-item v-if="canConfigureStorage" name="storageaccessgroups" :label="$t('label.storageaccessgroups')" html-for="cluster-edit-storage-groups">
        <a-select
          id="cluster-edit-storage-groups"
          v-model:value="form.storageaccessgroups"
          mode="tags"
          option-filter-prop="label"
          :disabled="loading || groupUnknown"
          :options="storageAccessGroups"
          :loading="storageAccessGroupsLoading"
          :placeholder="apiParamsConfigureStorageAccess.storageaccessgroups.description" />
      </a-form-item>
      <mold-dialog-actions :busy="loading">
        <a-button :disabled="loading" @click="onCloseAction">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" :loading="loading" @click="handleSubmit">
          {{ $t(groupUnknown ? 'label.host.operation.recheck' : saveError ? 'label.retry' : 'label.ok') }}
        </a-button>
      </mold-dialog-actions>
    </a-form>
  </a-spin>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { createHostJobTracker as createJobTracker, hostRequestRejected as requestRejected, hostStorageGroups as normalizeGroups } from '@/utils/hostOperations'
import DetailsInput from '@/components/widgets/DetailsInput'

export default {
  name: 'ClusterUpdate',
  components: {
    MoldDialogActions,
    DetailsInput
  },
  props: {
    action: {
      type: Object,
      required: true
    },
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false,
      baseComplete: false,
      baseSaved: false,
      saveStage: '',
      saveError: '',
      groupJobId: '',
      groupUnknown: false,
      requestedGroups: '',
      initialExternalDetails: null,
      storageAccessGroups: [],
      storageAccessGroupsLoading: false
    }
  },
  computed: {
    canConfigureStorage () {
      return Boolean(this.apiParamsConfigureStorageAccess?.clusterid &&
        this.apiParamsConfigureStorageAccess?.storageaccessgroups &&
        this.$store.getters.apis?.queryAsyncJobResult)
    },
    architectureOptions () {
      const options = this.$fetchCpuArchitectureTypes().map(opt => ({ value: opt.id, label: opt.name || opt.description || opt.id }))
      if (this.resource.arch && !options.some(opt => opt.value === this.resource.arch)) {
        options.push({ value: this.resource.arch, label: this.resource.arch })
      }
      return options
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateCluster') || {}
    this.apiParamsConfigureStorageAccess = this.$getApiParams('configureStorageAccess')
  },
  created () {
    this.initForm()
    this.groupTracker = createJobTracker()
    if (this.canConfigureStorage) this.fetchStorageAccessGroupsData()
    this.fetchExtensionResourceMapDetails()
  },
  beforeUnmount () {
    this.groupTracker.clear()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        name: this.resource.name,
        arch: this.resource.arch,
        storageaccessgroups: this.resource.storageaccessgroups
          ? this.resource.storageaccessgroups.split(',')
          : []
      })
      this.rules = reactive({})
    },
    async fetchStorageAccessGroupsData () {
      if (!this.$store.getters.apis?.listStorageAccessGroups) return
      this.storageAccessGroupsLoading = true
      try {
        const response = await getAPI('listStorageAccessGroups', {}, { preserveOnFailure: true })
        const groups = response.liststorageaccessgroupsresponse?.storageaccessgroup || []
        this.storageAccessGroups = [...new Set(groups.map(group => group.name))].map(name => ({ value: name, label: name }))
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.storageAccessGroupsLoading = false
      }
    },
    fetchExtensionResourceMapDetails () {
      this.form.externaldetails = null
      if (!this.resource.id || !this.resource.extensionid) {
        return
      }
      this.loading = true
      const params = {
        id: this.resource.extensionid,
        details: 'resource'
      }
      getAPI('listExtensions', params).then(json => {
        const resources = json?.listextensionsresponse?.extension?.[0]?.resources || []
        const resourceMap = resources.find(r => r.id === this.resource.id)
        if (resourceMap && resourceMap.details && typeof resourceMap.details === 'object') {
          this.initialExternalDetails = { ...resourceMap.details }
          this.form.externaldetails = { ...resourceMap.details }
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    baseChanges (values) {
      const changes = {}
      if (this.apiParams.clustername && this.resource.hypervisortype !== 'VMware' && values.name !== this.resource.name) changes.clustername = values.name
      if (this.apiParams.arch && values.arch && values.arch !== this.resource.arch) changes.arch = values.arch
      if (this.apiParams.externaldetails && this.resource.hypervisortype === 'External' && this.resource.extensionid) {
        const previous = Object.entries(this.initialExternalDetails || {}).sort()
        const entries = Object.entries(values.externaldetails || {}).sort()
        if (JSON.stringify(entries) !== JSON.stringify(previous)) {
          entries.forEach(([key, value]) => { changes['externaldetails[0].' + key] = value })
        }
      }
      return changes
    },
    async handleSubmit () {
      if (this.loading) return
      this.loading = true
      this.saveError = ''
      try {
        await this.formRef.value.validate()
        const values = toRaw(this.form)
        const id = this.resource.id
        if (!this.baseComplete) {
          const changes = this.baseChanges(values)
          if (Object.keys(changes).length) {
            this.saveStage = 'message.cluster.update.fields.saving'
            await postAPI('updateCluster', { id, ...changes }, { preserveOnFailure: true })
            this.baseSaved = true
          }
          this.baseComplete = true
        }
        const groups = normalizeGroups(values.storageaccessgroups)
        if (this.groupUnknown || (this.canConfigureStorage && groups !== normalizeGroups(this.resource.storageaccessgroups))) {
          if (!this.groupUnknown) this.requestedGroups = groups
          if (!(await this.saveStorageGroups(id))) return
        }
        this.$message.success(this.$t('message.cluster.update.complete'))
        this.$emit('refresh-data')
        this.$emit('close-action')
      } catch (error) {
        if (error.errorFields?.[0]) this.formRef.value.scrollToField(error.errorFields[0].name)
        else {
          this.saveError = 'message.cluster.update.fields.failed'
          this.$notifyError(error)
        }
      } finally {
        this.saveStage = ''
        this.loading = false
      }
    },
    async saveStorageGroups (id) {
      this.saveStage = 'message.host.update.groups.saving'
      if (this.groupUnknown && !this.groupJobId) {
        try {
          const response = await getAPI('listClusters', { id }, { preserveOnFailure: true, timeout: 10000 })
          const cluster = response.listclustersresponse?.cluster?.find(item => item.id === id)
          if (cluster && normalizeGroups(cluster.storageaccessgroups) === this.requestedGroups) return true
        } catch {}
        this.saveError = 'message.host.update.groups.unknown'
        return false
      }
      if (!this.groupJobId) {
        try {
          const response = await postAPI('configureStorageAccess', {
            clusterid: id,
            storageaccessgroups: this.requestedGroups
          }, { preserveOnFailure: true, timeout: 15000 })
          this.groupJobId = response.configurestorageaccessresponse?.jobid
        } catch (error) {
          if (requestRejected(error)) {
            this.saveError = 'message.host.update.groups.failed'
            return false
          }
        }
      }
      if (this.groupJobId) {
        const result = await this.groupTracker.track(this.groupJobId, {}, this.groupUnknown)
        if (result.jobstatus === 1) return true
        if (result.jobstatus === 2) {
          this.groupJobId = ''
          this.groupUnknown = false
          this.saveError = 'message.host.update.groups.failed'
          return false
        }
      }
      this.groupUnknown = true
      this.saveError = 'message.host.update.groups.unknown'
      return false
    },
    onCloseAction () {
      if (this.loading) return
      if (this.baseSaved || this.groupUnknown) this.$emit('refresh-data')
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped>
.form-layout fieldset {
  min-width: 0;
  padding: 0;
  border: 0;
}

.cluster-save-status {
  margin-bottom: 12px;
}
</style>
