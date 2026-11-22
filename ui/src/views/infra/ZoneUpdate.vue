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
      <a-alert v-if="saveStage" type="info" show-icon :message="$t(saveStage)" class="save-status" />
      <a-alert v-if="baseSaved && saveError" type="success" show-icon :message="$t('message.zone.update.base.saved')" class="save-status" />
      <a-alert v-if="saveError" :type="groupUnknown ? 'warning' : 'error'" show-icon :message="$t(saveError)" class="save-status" />
      <fieldset :disabled="loading || baseComplete">

        <a-form-item name="name" ref="name">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
          </template>
          <a-input v-model:value="form.name" />
        </a-form-item>

        <a-form-item name="dns1" ref="dns1">
          <template #label>
            <tooltip-label :title="$t('label.dns1')" :tooltip="apiParams.dns1.description"/>
          </template>
          <a-input v-model:value="form.dns1" />
        </a-form-item>

        <a-form-item name="dns2" ref="dns2">
          <template #label>
            <tooltip-label :title="$t('label.dns2')" :tooltip="apiParams.dns2.description"/>
          </template>
          <a-input v-model:value="form.dns2" />
        </a-form-item>

        <a-form-item name="ip6dns1" ref="ip6dns1">
          <template #label>
            <tooltip-label :title="$t('label.ip6dns1')" :tooltip="apiParams.ip6dns1.description"/>
          </template>
          <a-input v-model:value="form.ip6dns1" />
        </a-form-item>

        <a-form-item name="ip6dns2" ref="ip6dns2">
          <template #label>
            <tooltip-label :title="$t('label.ip6dns2')" :tooltip="apiParams.ip6dns2.description"/>
          </template>
          <a-input v-model:value="form.ip6dns2" />
        </a-form-item>

        <a-form-item name="internaldns1" ref="internaldns1">
          <template #label>
            <tooltip-label :title="$t('label.internaldns1')" :tooltip="apiParams.internaldns1.description"/>
          </template>
          <a-input v-model:value="form.internaldns1" />
        </a-form-item>

        <a-form-item name="internaldns2" ref="internaldns2">
          <template #label>
            <tooltip-label :title="$t('label.internaldns2')" :tooltip="apiParams.internaldns2.description"/>
          </template>
          <a-input v-model:value="form.internaldns2" />
        </a-form-item>

        <a-form-item v-if="resource.networktype === 'Advanced'" name="guestcidraddress" ref="guestcidraddress">
          <template #label>
            <tooltip-label :title="$t('label.guestcidraddress')" :tooltip="apiParams.guestcidraddress.description"/>
          </template>
          <a-input v-model:value="form.guestcidraddress" />
        </a-form-item>

        <a-form-item name="domain" ref="domain">
          <template #label>
            <tooltip-label :title="$t('label.zone.networkdomain')" :tooltip="apiParams.domain.description"/>
          </template>
          <a-input v-model:value="form.domain" />
        </a-form-item>

        <a-form-item v-if="apiParams.dnssearchorder" name="dnssearchorder" :label="$t('label.zone.dnssearchorder')">
          <a-textarea v-model:value="form.dnssearchorder" :rows="3" :placeholder="$t('message.zone.dnssearchorder.help')" />
        </a-form-item>

        <a-form-item name="localstorageenabled" ref="localstorageenabled">
          <template #label>
            <tooltip-label :title="$t('label.localstorageenabled')" :tooltip="apiParams.localstorageenabled.description"/>
          </template>
          <a-switch v-model:checked="form.localstorageenabled" />
        </a-form-item>

      </fieldset>

      <a-form-item v-if="canConfigureStorage" name="storageaccessgroups" ref="storageaccessgroups">
        <template #label>
          <tooltip-label :title="$t('label.storageaccessgroups')" :tooltip="apiParamsConfigureStorageAccess.storageaccessgroups.description"/>
        </template>
        <a-select
          mode="tags"
          v-model:value="form.storageaccessgroups"
          showSearch
          option-filter-prop="label"
          :options="storageAccessGroups.map(value => ({ label: value, value }))"
          :disabled="loading || groupUnknown"
          :loading="storageAccessGroupsLoading"
          :placeholder="apiParamsConfigureStorageAccess.storageaccessgroups.description" />
      </a-form-item>

      <mold-dialog-actions :busy="loading">
        <a-button :disabled="loading" @click="onCloseAction">{{ $t('label.cancel') }}</a-button>
        <a-button :loading="loading" ref="submit" type="primary" @click="handleSubmit">{{ $t(groupUnknown ? 'label.zone.update.recheck' : saveError ? 'label.retry' : 'label.ok') }}</a-button>
      </mold-dialog-actions>
    </a-form>
  </a-spin>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { createJobTracker } from '@/utils/jobTracker'
import { parseZoneDnsSearchOrder, validZoneDnsSuffix, zoneDnsSearchOrder } from '@/utils/zone'

export default {
  name: 'ZoneUpdate',
  components: {
    TooltipLabel,
    MoldDialogActions
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
      storageAccessGroupsLoading: false
    }
  },
  computed: {
    canConfigureStorage () {
      return this.resource.networktype === 'Advanced' && !!this.apiParamsConfigureStorageAccess?.storageaccessgroups
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('updateZone')
    this.apiParamsConfigureStorageAccess = this.$getApiParams('configureStorageAccess')
  },
  created () {
    this.initForm()
    if (this.canConfigureStorage && 'listStorageAccessGroups' in this.$store.getters.apis) this.fetchStorageAccessGroupsData()
    this.groupTracker = createJobTracker({
      query: jobId => getAPI('queryAsyncJobResult', { jobid: jobId }, { timeout: 12000, backgroundJob: true })
        .then(json => json.queryasyncjobresultresponse),
      onState: () => {},
      maxRetries: 1,
      maxDuration: 90000
    })
  },
  beforeUnmount () {
    this.groupTracker.clear()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        name: this.resource.name,
        dns1: this.resource.dns1,
        dns2: this.resource.dns2,
        ip6dns1: this.resource.ip6dns1,
        ip6dns2: this.resource.ip6dns2,
        internaldns1: this.resource.internaldns1,
        internaldns2: this.resource.internaldns2,
        guestcidraddress: this.resource.guestcidraddress,
        domain: this.resource.domain,
        ...(this.apiParams.dnssearchorder ? { dnssearchorder: zoneDnsSearchOrder(this.resource).join('\n') } : {}),
        localstorageenabled: this.resource.localstorageenabled,
        storageaccessgroups: this.resource.storageaccessgroups
          ? this.resource.storageaccessgroups.split(',')
          : []
      })
      this.rules = reactive({
        dnssearchorder: [{
          validator: async (_, value) => {
            if (!parseZoneDnsSearchOrder(value).every(validZoneDnsSuffix)) throw new Error(this.$t('message.zone.dnssearchorder.invalid'))
          }
        }]
      })
    },
    fetchStorageAccessGroupsData () {
      const params = {}
      this.storageAccessGroupsLoading = true
      getAPI('listStorageAccessGroups', params, { preserveOnFailure: true }).then(json => {
        const sags = json.liststorageaccessgroupsresponse.storageaccessgroup || []
        for (const sag of sags) {
          if (!this.storageAccessGroups.includes(sag.name)) {
            this.storageAccessGroups.push(sag.name)
          }
        }
      }).catch(() => {
        this.storageAccessGroups = []
      }).finally(() => {
        this.storageAccessGroupsLoading = false
      })
    },
    async handleSubmit () {
      if (this.loading) return
      this.loading = true
      this.saveError = ''
      try {
        await this.formRef.value.validate()
        const { storageaccessgroups: groups, ...values } = toRaw(this.form)
        const id = this.resource.id
        if (!this.baseComplete) {
          const changes = Object.fromEntries(Object.entries(values)
            .filter(([key, value]) => key !== 'dnssearchorder' && (value ?? '') !== (this.resource[key] ?? '')))
          const searchOrder = parseZoneDnsSearchOrder(values.dnssearchorder).join(',')
          if (this.apiParams.dnssearchorder && searchOrder !== zoneDnsSearchOrder(this.resource).join(',')) changes.dnssearchorder = searchOrder
          if (Object.keys(changes).length) {
            this.saveStage = 'message.zone.update.base.saving'
            await postAPI('updateZone', { id, ...changes }, { preserveOnFailure: true })
            this.baseSaved = true
          }
          this.baseComplete = true
        }
        if (this.groupUnknown || (this.canConfigureStorage && this.normalizeGroups(groups) !== this.normalizeGroups(this.resource.storageaccessgroups))) {
          if (!this.groupUnknown) this.requestedGroups = this.normalizeGroups(groups)
          if (!(await this.saveStorageGroups(id))) return
        }
        this.$message.success(this.$t('message.zone.update.complete'))
        this.$emit('refresh-data')
        this.$emit('close-action')
      } catch (error) {
        if (error.errorFields?.length) {
          this.formRef.value.scrollToField(error.errorFields[0].name)
        } else {
          this.saveError = 'message.zone.update.base.failed'
        }
      } finally {
        this.saveStage = ''
        this.loading = false
      }
    },
    normalizeGroups (value) {
      const groups = Array.isArray(value) ? value : (value || '').split(',')
      return [...new Set(groups.map(group => group.trim()).filter(Boolean))].sort().join(',')
    },
    async saveStorageGroups (id) {
      this.saveStage = 'message.zone.update.groups.saving'
      if (this.groupUnknown && !this.groupJobId) {
        try {
          const { listzonesmetricsresponse: result } = await getAPI('listZonesMetrics', { id }, { preserveOnFailure: true })
          const zone = result.zone?.find(zone => zone.id === id)
          if (zone && this.normalizeGroups(zone.storageaccessgroups) === this.requestedGroups) return true
        } catch (_) {}
        this.saveError = 'message.zone.update.groups.unknown'
        return false
      }
      if (!this.groupJobId) {
        try {
          const response = await postAPI('configureStorageAccess', { zoneid: id, storageaccessgroups: this.requestedGroups }, { preserveOnFailure: true, timeout: 15000 })
          this.groupJobId = response.configurestorageaccessresponse?.jobid
        } catch (error) {
          const status = error.response?.status
          if (status >= 400 && status < 500 && status !== 408 && status !== 429) {
            this.saveError = 'message.zone.update.groups.failed'
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
          this.saveError = 'message.zone.update.groups.failed'
          return false
        }
      }
      this.groupUnknown = true
      this.saveError = 'message.zone.update.groups.unknown'
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
    margin: 0;
    padding: 0;
    border: 0;
    min-width: 0;
  }

  .save-status {
    margin-bottom: 12px;
  }

  width: 80vw;

  @media (min-width: 600px) {
    width: 450px;
  }

  .action-button {
    text-align: right;
    margin-top: 20px;

    button {
      margin-right: 5px;
    }
  }
}
</style>
