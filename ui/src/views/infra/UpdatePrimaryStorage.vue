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
    <a-spin :spinning="loading">
      <a-alert v-if="saveStage" class="primary-storage-save-status" type="info" show-icon :message="$t(saveStage)" />
      <a-alert v-if="baseSaved && saveError" class="primary-storage-save-status" type="success" show-icon :message="$t('message.primary.storage.update.fields.saved')" />
      <a-alert v-if="saveError" class="primary-storage-save-status" :type="groupUnknown ? 'warning' : 'error'" show-icon :message="$t(saveError)" />
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
        layout="vertical"
       >
        <a-form-item v-if="apiParams.name" name="name" ref="name">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
          </template>
          <a-input
            id="primary-storage-edit-name"
            v-model:value="form.name"
            :disabled="loading || baseComplete"
            :placeholder="apiParams.name.description" />
        </a-form-item>
        <a-form-item v-if="apiParams.tags" name="tags" ref="tags">
          <template #label>
            <tooltip-label :title="$t('label.tags')" :tooltip="apiParams.tags.description"/>
          </template>
          <a-input
            v-model:value="form.tags"
            :disabled="loading || baseComplete"
            :placeholder="apiParams.tags.description" />
        </a-form-item>
        <a-form-item v-if="apiParams.istagarule" name="isTagARule" ref="isTagARule">
          <template #label>
            <tooltip-label :title="$t('label.istagarule')" :tooltip="apiParams.istagarule.description"/>
          </template>
          <a-switch v-model:checked="form.isTagARule" :disabled="loading || baseComplete" />
        </a-form-item>
        <a-form-item v-if="apiParams.capacitybytes" name="capacityBytes" ref="capacityBytes">
          <template #label>
            <tooltip-label :title="$t('label.capacitybytes')" :tooltip="apiParams.capacitybytes.description"/>
          </template>
          <a-input
            v-model:value="form.capacityBytes"
            :disabled="loading || baseComplete"
            :placeholder="apiParams.capacitybytes.description" />
        </a-form-item>
        <a-form-item v-if="apiParams.capacityiops" name="capacityIOPS" ref="capacityIOPS">
          <template #label>
            <tooltip-label :title="$t('label.capacityiops')" :tooltip="apiParams.capacityiops.description"/>
          </template>
          <a-input
            v-model:value="form.capacityIOPS"
            :disabled="loading || baseComplete"
            :placeholder="apiParams.capacityiops.description" />
        </a-form-item>
        <br>
        <a-form-item name="nfsMountOpts" ref="nfsMountOpts" v-if="canUpdateNFSMountOpts">
          <template #label>
            <tooltip-label :title="$t('label.nfsmountopts')" :tooltip="$t('message.nfs.mount.options.description')"/>
          </template>
          <a-alert type="warning">
            <template #message>
              <span v-html="$t('message.action.edit.nfs.mount.options')"></span>
            </template>
          </a-alert>
          <br>
          <a-input
            v-model:value="form.nfsMountOpts"
            :disabled="loading || baseComplete"
            :placeholder="$t('message.nfs.mount.options.description')" />
        </a-form-item>

        <a-form-item v-if="canManageGroups" name="storageaccessgroups" html-for="primary-storage-edit-access-groups">
          <template #label>
            <tooltip-label :title="$t('label.storageaccessgroups')" :tooltip="groupParams.storageaccessgroups.description"/>
          </template>
          <a-select
            id="primary-storage-edit-access-groups"
            v-model:value="form.storageaccessgroups"
            mode="tags"
            option-filter-prop="label"
            :options="storageAccessGroups"
            :loading="storageAccessGroupsLoading"
            :disabled="loading || groupUnknown || storageAccessGroupsLoading"
            :placeholder="groupParams.storageaccessgroups.description" />
        </a-form-item>

        <mold-dialog-actions :busy="loading">
          <a-button :disabled="loading" @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">
            {{ $t(groupUnknown ? 'label.host.operation.recheck' : saveError ? 'label.retry' : 'label.ok') }}
          </a-button>
        </mold-dialog-actions>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { ref, reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
import { isAdmin } from '@/role'
import { createHostJobTracker, hostRequestRejected, hostStorageGroups } from '@/utils/hostOperations'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import MoldDialogActions from '@/components/view/MoldDialogActions'

const groupReadTimeout = 10000

export default {
  name: 'UpdateStoragePool',
  components: { TooltipLabel, MoldDialogActions },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      loading: false,
      disposed: false,
      baseComplete: false,
      baseSaved: false,
      saveStage: '',
      saveError: '',
      groupJobId: '',
      groupUnknown: false,
      requestedGroups: '',
      storageAccessGroups: [],
      storageAccessGroupsLoading: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateStoragePool') || {}
    this.groupParams = this.$getApiParams('configureStorageAccess') || {}
  },
  created () {
    this.targetId = this.resource.id
    this.groupTracker = createHostJobTracker()
    this.formRef = ref()
    const groups = hostStorageGroups(this.resource.storageaccessgroups)
    this.initial = {
      name: this.resource.name || '',
      tags: this.resource.tags || '',
      isTagARule: this.resource.istagarule === true || this.resource.istagarule === 'true',
      capacityBytes: this.resource.disksizetotal,
      capacityIOPS: this.resource.capacityiops,
      nfsMountOpts: this.resource.nfsmountopts || '',
      groups
    }
    this.form = reactive({ ...this.initial, storageaccessgroups: groups ? groups.split(',') : [] })
    this.rules = reactive({})
    if (this.canManageGroups) this.fetchGroups()
  },
  beforeUnmount () {
    this.disposed = true
    this.groupTracker.clear()
  },
  computed: {
    canReadGroups () {
      return this.resource.scope !== 'HOST' && !!this.$store.getters.apis.listStorageAccessGroups
    },
    canManageGroups () {
      const apis = this.$store.getters.apis
      return this.canReadGroups && !!this.groupParams.storageid && !!this.groupParams.storageaccessgroups &&
        !!apis.configureStorageAccess && !!apis.queryAsyncJobResult
    },
    canUpdateNFSMountOpts () {
      return !!this.apiParams.details && isAdmin() && this.resource.type === 'NetworkFilesystem' &&
        this.resource.state === 'Maintenance' && ['KVM', 'Simulator'].includes(this.resource.hypervisor)
    }
  },
  methods: {
    isCurrent () {
      return !this.disposed && this.resource.id === this.targetId
    },
    async fetchGroups () {
      this.storageAccessGroupsLoading = true
      try {
        const response = await getAPI('listStorageAccessGroups', {}, { preserveOnFailure: true })
        if (!this.isCurrent()) return
        const options = response.liststorageaccessgroupsresponse?.storageaccessgroup || []
        const names = hostStorageGroups(options.map(item => item.name))
        this.storageAccessGroups = names ? names.split(',').map(name => ({ value: name, label: name })) : []
      } catch (error) {
        if (this.isCurrent()) this.$notifyError(error)
      } finally {
        if (this.isCurrent()) this.storageAccessGroupsLoading = false
      }
    },
    baseChanges () {
      const changes = {}
      const name = this.form.name?.trim()
      if (this.apiParams.name && name && name !== this.initial.name) changes.name = name
      if (this.apiParams.tags && (this.form.tags !== this.initial.tags ||
        (this.apiParams.istagarule && this.form.isTagARule !== this.initial.isTagARule))) {
        changes.tags = this.form.tags || ''
        if (this.apiParams.istagarule) changes.istagarule = this.form.isTagARule
      }
      const capacityFields = { capacitybytes: 'capacityBytes', capacityiops: 'capacityIOPS' }
      for (const [parameter, field] of Object.entries(capacityFields)) {
        const value = String(this.form[field] ?? '').trim()
        if (this.apiParams[parameter] && value !== '' && value !== String(this.initial[field] ?? '')) changes[parameter] = value
      }
      if (this.canUpdateNFSMountOpts && (this.form.nfsMountOpts || '') !== this.initial.nfsMountOpts) {
        changes['details[0].nfsmountopts'] = this.form.nfsMountOpts || ''
      }
      return changes
    },
    async handleSubmit () {
      if (this.loading || !this.isCurrent()) return
      this.loading = true
      this.saveError = ''
      try {
        await this.formRef.value.validate()
        if (!this.isCurrent()) return
        const groups = hostStorageGroups(this.form.storageaccessgroups)
        const needsGroups = this.canManageGroups && groups !== this.initial.groups
        const changes = this.baseComplete || !this.$store.getters.apis.updateStoragePool ? {} : this.baseChanges()
        if (Object.keys(changes).length) {
          this.saveStage = 'message.primary.storage.update.fields.saving'
          await postAPI('updateStoragePool', { id: this.targetId, ...changes }, { preserveOnFailure: true })
          if (!this.isCurrent()) return
          this.baseSaved = true
        }
        this.baseComplete = true
        if (this.groupUnknown || needsGroups) {
          if (!this.groupUnknown) this.requestedGroups = groups
          if (!(await this.saveGroups()) || !this.isCurrent()) return
        }
        if (this.baseSaved || needsGroups || this.groupUnknown) {
          this.$message.success(this.$t('message.success.edit.primary.storage') + ' · ' + this.resource.name)
          this.$emit('refresh-data')
        }
        this.$emit('close-action')
      } catch (error) {
        if (!this.isCurrent()) return
        if (error.errorFields?.[0]) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.saveError = 'message.primary.storage.update.fields.failed'
      } finally {
        if (this.isCurrent()) {
          this.saveStage = ''
          this.loading = false
        }
      }
    },
    async saveGroups () {
      this.saveStage = 'message.host.update.groups.saving'
      if (this.groupUnknown && !this.groupJobId) {
        try {
          const response = await getAPI('listStoragePools', { id: this.targetId }, { preserveOnFailure: true, timeout: groupReadTimeout })
          const pool = response.liststoragepoolsresponse?.storagepool?.find(item => item.id === this.targetId)
          if (this.isCurrent() && pool && hostStorageGroups(pool.storageaccessgroups) === this.requestedGroups) return true
        } catch {}
        if (this.isCurrent()) this.saveError = 'message.host.update.groups.unknown'
        return false
      }
      if (!this.groupJobId && this.canManageGroups) {
        try {
          const response = await postAPI('configureStorageAccess', {
            storageid: this.targetId,
            storageaccessgroups: this.requestedGroups
          }, { preserveOnFailure: true, timeout: 15000 })
          if (!this.isCurrent()) return false
          this.groupJobId = response.configurestorageaccessresponse?.jobid
        } catch (error) {
          if (!this.isCurrent()) return false
          if (hostRequestRejected(error)) {
            this.saveError = 'message.host.update.groups.failed'
            return false
          }
        }
      }
      if (this.groupJobId) {
        const result = await this.groupTracker.track(this.groupJobId, {}, this.groupUnknown)
        if (!this.isCurrent()) return false
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
    closeAction () {
      if (this.loading) return
      if (this.baseSaved || this.groupUnknown) this.$emit('refresh-data')
      this.$emit('close-action')
    }
  }
}
</script>

<style scoped>
.form-layout { min-width: 0; width: 100%; }
.primary-storage-save-status { margin-bottom: 12px; }
</style>
