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
      <a-alert v-if="saveStage" class="host-save-alert" type="info" show-icon :message="$t(saveStage)" />
      <a-alert v-if="baseSaved && saveError" class="host-save-alert" type="success" show-icon :message="$t('message.host.update.fields.saved')" />
      <a-alert v-if="saveError" class="host-save-alert" :type="groupUnknown ? 'warning' : 'error'" show-icon :message="$t(saveError)" />
      <fieldset :disabled="loading || baseComplete">
      <a-form-item v-if="apiParams.name" name="name" ref="name">
        <template #label>
          <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
        </template>
        <a-input
          v-model:value="form.name"
          v-focus="true" />
      </a-form-item>
      <a-form-item v-if="apiParams.hosttags" name="hosttags" ref="hosttags">
        <template #label>
          <tooltip-label :title="$t('label.hosttags')" :tooltip="$t('label.hosttags.explicit.description')"/>
        </template>
        <a-input v-model:value="form.hosttags" />
      </a-form-item>
      <a-form-item v-if="apiParams.istagarule" name="istagarule" ref="istagarule">
        <template #label>
          <tooltip-label :title="$t('label.istagarule')" :tooltip="apiParams.istagarule.description"/>
        </template>
        <a-switch v-model:checked="form.istagarule" />
      </a-form-item>
      <a-form-item v-if="apiParams.guestosrule && apiParams.oscategoryid" name="guestosasrule" ref="guestosasrule">
        <template #label>
          <tooltip-label :title="$t('label.guestosasrule')"/>
        </template>
        <a-switch v-model:checked="form.guestosasrule"/>
      </a-form-item>
      <a-form-item v-if="form.guestosasrule && apiParams.guestosrule" name="guestosrule" ref="guestosrule">
        <template #label>
          <tooltip-label :title="$t('label.guestosrule')" :tooltip="apiParams.guestosrule.description"/>
        </template>
        <a-textarea
          v-model:value="form.guestosrule"
          :placeholder="apiParams.guestosrule.name">
        </a-textarea>
      </a-form-item>
      <a-form-item v-if="!form.guestosasrule && apiParams.oscategoryid" name="oscategoryid" ref="oscategoryid">
        <template #label>
          <tooltip-label :title="$t('label.oscategoryid')" :tooltip="apiParams.oscategoryid.description"/>
        </template>
        <a-select
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }"
          :loading="osCategories.loading"
          v-model:value="form.oscategoryid">
          <a-select-option v-for="(osCategory) in osCategories.opts" :key="osCategory.id" :label="osCategory.name">
            {{ osCategory.name }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item name="externaldetails" ref="externaldetails" v-if="resource.hypervisor === 'External' && apiParams.externaldetails">
        <template #label>
          <tooltip-label :title="$t('label.configuration.details')" :tooltip="apiParams.externaldetails.description"/>
        </template>
        <div style="margin-bottom: 10px">{{ $t('message.add.extension.resource.details') }}</div>
        <details-input
          v-model:value="form.externaldetails" />
      </a-form-item>
      <a-form-item v-if="apiParams.migrationip" name="migrationip" ref="migrationip">
        <template #label>
          <tooltip-label :title="$t('label.migrationip')" :tooltip="$t('label.migrationip')"/>
        </template>
        <a-input v-model:value="form.migrationip" />
      </a-form-item>
      </fieldset>
      <a-form-item v-if="canConfigureStorage" name="storageaccessgroups" :label="$t('label.storageaccessgroups')" html-for="host-edit-storage-groups">
        <a-select
          id="host-edit-storage-groups"
          v-model:value="form.storageaccessgroups"
          mode="tags"
          :disabled="loading || groupUnknown"
          :options="storageAccessGroups"
          :loading="storageAccessGroupsLoading"
          :placeholder="apiParamsConfigureStorageAccess.storageaccessgroups.description" />
      </a-form-item>
      <mold-dialog-actions :busy="loading">
        <a-button :disabled="loading" @click="onCloseAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t(groupUnknown ? 'label.host.operation.recheck' : saveError ? 'label.retry' : 'label.ok') }}</a-button>
      </mold-dialog-actions>
    </a-form>
  </a-spin>
</template>

<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import DetailsInput from '@/components/widgets/DetailsInput'
import { getFilteredExternalDetails } from '@/utils/extension'
import { createHostJobTracker, hostRequestRejected, hostStorageGroups } from '@/utils/hostOperations'

export default {
  name: 'HostUpdate',
  components: {
    MoldDialogActions,
    TooltipLabel,
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
      storageAccessGroups: [],
      storageAccessGroupsLoading: false,
      osCategories: {
        loading: false,
        opts: []
      }
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateHost')
    this.apiParamsConfigureStorageAccess = this.$getApiParams('configureStorageAccess')
  },
  created () {
    this.initForm()
    this.fetchOsCategories()
    if (this.canConfigureStorage) this.fetchStorageAccessGroupsData()
    this.groupTracker = createHostJobTracker()
  },
  beforeUnmount () {
    if (this.groupTracker) this.groupTracker.clear()
  },
  computed: {
    canConfigureStorage () {
      return Boolean(this.apiParamsConfigureStorageAccess?.hostid &&
        this.apiParamsConfigureStorageAccess?.storageaccessgroups &&
        this.$store.getters.apis?.queryAsyncJobResult)
    },
    resourceExternalDetails () {
      return getFilteredExternalDetails(this.resource.details)
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      const guestOsRule = this.resource?.guestosrule
      this.form = reactive({
        name: this.resource.name,
        hosttags: this.resource.explicithosttags,
        istagarule: this.resource.istagarule,
        storageaccessgroups: this.resource.storageaccessgroups
          ? this.resource.storageaccessgroups.split(',')
          : [],
        oscategoryid: this.resource.oscategoryid,
        externaldetails: this.resourceExternalDetails,
        migrationip: this.resource.migrationip,
        guestosasrule: guestOsRule !== undefined,
        guestosrule: guestOsRule
      })
      this.rules = reactive({
        migrationip: [
          {
            validator: (rule, value) => {
              return new Promise((resolve, reject) => {
                if (value === null || value === undefined || value === '') {
                  resolve()
                } else {
                  // IPv4 regex
                  const ipv4Regex = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/
                  if (ipv4Regex.test(value)) {
                    resolve()
                  } else {
                    reject(new Error(this.$t('message.error.ipv4.address')))
                  }
                }
              })
            }
          }
        ]
      })
    },
    async fetchStorageAccessGroupsData () {
      if (!this.$store.getters.apis?.listStorageAccessGroups) return
      this.storageAccessGroupsLoading = true
      try {
        const response = await getAPI('listStorageAccessGroups', {}, { preserveOnFailure: true })
        const names = response.liststorageaccessgroupsresponse?.storageaccessgroup?.map(group => group.name) || []
        this.storageAccessGroups = [...new Set(names)].map(name => ({ value: name, label: name }))
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.storageAccessGroupsLoading = false
      }
    },
    fetchOsCategories () {
      this.osCategories.loading = true
      this.osCategories.opts = []
      getAPI('listOsCategories').then(json => {
        this.osCategories.opts = json.listoscategoriesresponse.oscategory || []
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.osCategories.loading = false
      })
    },
    baseChanges (values) {
      const changes = {}
      const before = this.resource
      for (const [field, original] of Object.entries({
        name: before.name,
        hosttags: before.explicithosttags,
        migrationip: before.migrationip,
        istagarule: before.istagarule
      })) {
        if (this.apiParams[field] && (values[field] ?? '') !== (original ?? '')) {
          changes[field] = values[field] ?? ''
        }
      }
      if (values.guestosasrule && this.apiParams.guestosrule) {
        if (values.guestosrule !== before.guestosrule) changes.guestosrule = values.guestosrule ?? ''
      } else if (this.apiParams.oscategoryid && (values.oscategoryid !== before.oscategoryid || before.guestosrule !== undefined)) {
        const category = values.oscategoryid || this.osCategories.opts.find(os => os.name === 'None')?.id
        if (category) changes.oscategoryid = category
      }
      if (before.hypervisor === 'External' && this.apiParams.externaldetails) {
        const entries = Object.entries(values.externaldetails || {}).sort()
        const previous = Object.entries(this.resourceExternalDetails || {}).sort()
        if (JSON.stringify(entries) !== JSON.stringify(previous)) {
          if (!entries.length && this.apiParams.cleanupexternaldetails) changes.cleanupexternaldetails = true
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
            this.saveStage = 'message.host.update.fields.saving'
            await postAPI('updateHost', { id, ...changes }, { preserveOnFailure: true })
            this.baseSaved = true
          }
          this.baseComplete = true
        }
        const selected = hostStorageGroups(values.storageaccessgroups)
        if (this.groupUnknown || (this.canConfigureStorage && selected !== hostStorageGroups(this.resource.storageaccessgroups))) {
          if (!this.groupUnknown) this.requestedGroups = selected
          if (!(await this.saveStorageGroups(id))) return
        }
        this.$message.success(this.$t('message.host.update.complete'))
        this.$emit('refresh-data')
        this.$emit('close-action')
      } catch (error) {
        if (error.errorFields?.[0]) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.saveError = 'message.host.update.fields.failed'
      } finally {
        this.saveStage = ''
        this.loading = false
      }
    },
    async saveStorageGroups (id) {
      this.saveStage = 'message.host.update.groups.saving'
      if (this.groupUnknown && !this.groupJobId) {
        try {
          const response = await getAPI('listHosts', { id }, { preserveOnFailure: true, timeout: 10000 })
          const host = response.listhostsresponse?.host?.find(item => item.id === id)
          if (host && hostStorageGroups(host.storageaccessgroups) === this.requestedGroups) return true
        } catch {}
        this.saveError = 'message.host.update.groups.unknown'
        return false
      }
      if (!this.groupJobId) {
        try {
          const response = await postAPI('configureStorageAccess', {
            hostid: id,
            storageaccessgroups: this.requestedGroups
          }, { preserveOnFailure: true, timeout: 15000 })
          this.groupJobId = response.configurestorageaccessresponse?.jobid
        } catch (error) {
          if (hostRequestRejected(error)) {
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

<style scoped lang="less">
.form-layout {
  fieldset {
    min-width: 0;
    padding: 0;
    border: 0;
  }

  .host-save-alert {
    margin-bottom: 12px;
  }

  width: 100%;
  min-width: 0;

  .action-button {
    text-align: right;
    margin-top: 20px;

    button {
      margin-right: 5px;
    }
  }
}
</style>
