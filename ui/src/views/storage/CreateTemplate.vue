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
  <div class="snapshot-template-content snapshot-dialog-content" @keydown.ctrl.enter.prevent="handleSubmit">
  <a-spin :spinning="loading || queryLoading">
    <StorageSnapshotSafety compact :pending="snapshotSafetyPending" v-if="snapshotSafetyTarget" :context="snapshotSafetyContext" :loading="snapshotSafetyLoading" :error="snapshotSafetyError" @refresh="refreshSnapshotSafety" />
    <a-alert v-if="lookupError" class="snapshot-dialog-notice" type="error" show-icon :message="$t('message.snapshot.dialog.lookup.failed')">
      <template #description><a-button :disabled="loading || queryLoading" @click="fetchData">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-form
      class="form snapshot-dialog-form"
      layout="vertical"
      :ref="formRef"
      :model="form"
      :rules="rules"
      @finish="handleSubmit"
     >
      <a-form-item ref="name" name="name">
        <template #label>
          <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
        </template>
        <a-input
          v-focus="true"
          v-model:value="form.name"
          :placeholder="$t('label.name')" />
      </a-form-item>
      <a-form-item ref="displaytext" name="displaytext">
        <template #label>
          <tooltip-label :title="$t('label.displaytext')" :tooltip="apiParams.displaytext.description"/>
        </template>
        <a-input
          v-model:value="form.displaytext"
          :placeholder="$t('label.displaytext')" />
      </a-form-item>
      <a-form-item v-if="resource.intervaltype" ref="zoneid" name="zoneid">
        <template #label>
          <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid.description"/>
        </template>
        <a-select
          v-model:value="form.zoneid"
          :loading="loading"
          :placeholder="$t('label.zone')"
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
          <a-select-option
            v-for="(zone, index) in zones"
            :value="zone.id"
            :key="index"
            :label="zone.name">
            <span>
              <resource-icon v-if="zone.icon" :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
              <global-outlined v-else style="margin-right: 5px"/>
              {{ zone.name }}
            </span>
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item name="domainid" ref="domainid" v-if="'listDomains' in $store.getters.apis">
          <template #label>
            <tooltip-label :title="$t('label.domain')" :tooltip="apiParams.domainid.description"/>
          </template>
          <infinite-scroll-select
            v-model:value="form.domainid"
            api="listDomains"
            :apiParams="domainsApiParams"
            resourceType="domain"
            optionValueKey="id"
            optionLabelKey="path"
            defaultIcon="block-outlined"
            allowClear="true"
            :placeholder="$t('label.domain')"
            @change-option-value="handleDomainChange" />
        </a-form-item>
        <a-form-item name="account" ref="account" v-if="domainid">
          <template #label>
            <tooltip-label :title="$t('label.account')" :tooltip="apiParams.account.description"/>
          </template>
          <infinite-scroll-select
            v-model:value="form.account"
            api="listAccounts"
            :apiParams="accountsApiParams"
            resourceType="account"
            optionValueKey="name"
            optionLabelKey="name"
            defaultIcon="team-outlined"
            allowClear="true"
            :placeholder="$t('label.account')" />
        </a-form-item>

      <a-form-item
          name="ostypeid"
          ref="ostypeid"
          :label="$t('label.ostypeid')">
          <a-select
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }"
          v-model:value="form.ostypeid"
          :loading="osTypes.loading"
          :placeholder="$t('label.ostypeid')">
          <a-select-option v-for="opt in osTypes.opts" :key="opt.id" :label="opt.name || opt.description">
              {{ opt.name || opt.description }}
          </a-select-option>
          </a-select>
      </a-form-item>
      <a-form-item
        name="arch"
        ref="arch">
        <template #label>
          <tooltip-label :title="$t('label.arch')" :tooltip="apiParams.arch.description"/>
        </template>
        <a-select
          showSearch
          optionFilterProp="label"
          :filterOption="(input, option) => {
            return option.children[0].children.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }"
          v-model:value="form.arch"
          :placeholder="$t('label.arch')">
          <a-select-option v-for="opt in architectureTypes.opts" :key="opt.id">
            {{ opt.name || opt.description }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-row :gutter="12">
        <a-col :md="24" :lg="12">
          <a-form-item ref="isdynamicallyscalable" name="isdynamicallyscalable">
            <template #label>
              <tooltip-label :title="$t('label.isdynamicallyscalable')" :tooltip="apiParams.isdynamicallyscalable.description"/>
            </template>
            <a-switch v-model:checked="form.isdynamicallyscalable" />
          </a-form-item>
          <a-form-item ref="requireshvm" name="requireshvm">
            <template #label>
              <tooltip-label :title="$t('label.requireshvm')" :tooltip="apiParams.requireshvm.description"/>
            </template>
            <a-switch v-model:checked="form.requireshvm" />
          </a-form-item>
          <a-form-item ref="passwordenabled" name="passwordenabled">
            <template #label>
              <tooltip-label :title="$t('label.passwordenabled')" :tooltip="apiParams.passwordenabled.description"/>
            </template>
            <a-switch v-model:checked="form.passwordenabled" />
          </a-form-item>
          <a-form-item
            ref="ispublic"
            name="ispublic"
            v-if="$store.getters.userInfo.roletype === 'Admin' || $store.getters.features.userpublictemplateenabled" >
            <template #label>
              <tooltip-label :title="$t('label.ispublic')" :tooltip="apiParams.ispublic.description"/>
            </template>
            <a-switch v-model:checked="form.ispublic" />
          </a-form-item>
          <a-form-item ref="isfeatured" name="isfeatured" v-if="$store.getters.userInfo.roletype === 'Admin'">
              <template #label>
                <tooltip-label :title="$t('label.isfeatured')" :tooltip="apiParams.isfeatured.description"/>
              </template>
              <a-switch v-model:checked="form.isfeatured" />
          </a-form-item>
        </a-col>
      </a-row>
      <MoldDialogActions :span="24" :busy="loading">
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" :loading="loading" :disabled="snapshotSafetyBlocked || queryLoading || !!lookupError" @click="handleSubmit">{{ $t('label.create.template') }}</a-button>
      </MoldDialogActions>
    </a-form>
  </a-spin>
  </div>
</template>

<script>
import { storageSnapshotSafety } from '@/utils/storageSnapshotSafety'
import storageDialogContext from '@/utils/storageDialogContext'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ref, reactive, toRaw } from 'vue'
import { postAPI } from '@/api'
import { getAPI } from '@/utils/storageApi'
import { mixinForm } from '@/utils/mixin'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import InfiniteScrollSelect from '@/components/widgets/InfiniteScrollSelect.vue'

export default {
  name: 'CreateTemplate',
  mixins: [storageDialogContext, storageSnapshotSafety('createTemplate', 'snapshot', vm => !!vm.resource?.intervaltype), mixinForm],
  components: {
    MoldDialogActions,
    ResourceIcon,
    TooltipLabel,
    InfiniteScrollSelect
  },
  props: {
    resource: {
      type: Object,
      default: () => {}
    }
  },
  data () {
    return {
      querySequence: 0,
      queryLoading: false,
      lookupError: '',
      snapshotZoneIds: [],
      zones: [],
      osTypes: {},
      loading: false,
      domainid: null,
      account: null,
      architectureTypes: {}
    }
  },
  computed: {
    isAdminRole () {
      return this.$store.getters.userInfo.roletype === 'Admin'
    },
    domainsApiParams () {
      return {
        listall: true,
        showicon: true,
        details: 'min'
      }
    },
    accountsApiParams () {
      if (!this.domainid) {
        return null
      }
      return {
        domainid: this.domainid
      }
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('createTemplate')
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        groupenabled: ['requireshvm']
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.required.input') }],
        ostypeid: [{ required: true, message: this.$t('message.error.select') }],
        groupenabled: [{ type: 'array' }]
      })
    },
    async fetchData () {
      if (this.queryLoading || this.loading) return
      const sequence = ++this.querySequence
      this.queryLoading = true; this.lookupError = ''
      this.architectureTypes.opts = this.$fetchCpuArchitectureTypes()
      try {
        await Promise.all([this.fetchOsTypes(sequence), ...(this.resource.intervaltype ? [this.fetchSnapshotZones(sequence)] : [])])
      } catch (error) { if (this.dialogCurrent() && sequence === this.querySequence) this.lookupError = 'message.snapshot.dialog.lookup.failed' } finally { if (this.dialogCurrent() && sequence === this.querySequence) this.queryLoading = false }
    },
    async fetchOsTypes (sequence = this.querySequence) {
      const json = await getAPI('listOsTypes', { page: 1, pagesize: 500 }, { preserveOnFailure: true, timeout: 20000 })
      if (!this.dialogCurrent() || sequence !== this.querySequence) return
      const rows = json.listostypesresponse?.ostype
      if (!Array.isArray(rows) || !rows.length) throw new Error('message.snapshot.dialog.lookup.failed')
      this.osTypes.opts = rows
    },
    async fetchZones (ids, sequence = this.querySequence) {
      const json = await getAPI('listZones', { showicon: true, ids: ids.join(','), page: 1, pagesize: 500 }, { preserveOnFailure: true, timeout: 20000 })
      if (!this.dialogCurrent() || sequence !== this.querySequence) return
      this.zones = (json.listzonesresponse?.zone || []).filter(zone => ids.includes(zone.id))
      if (!this.zones.length) throw new Error('message.snapshot.dialog.lookup.failed')
      if (!this.zones.some(zone => zone.id === this.form.zoneid)) this.form.zoneid = this.zones[0].id
    },
    async fetchSnapshotZones (sequence = this.querySequence) {
      const json = await getAPI('listSnapshots', { showunique: false, id: this.resource.id, page: 1, pagesize: 500 }, { preserveOnFailure: true, timeout: 20000 })
      if (!this.dialogCurrent() || sequence !== this.querySequence) return
      this.snapshotZoneIds = [...new Set((json.listsnapshotsresponse?.snapshot || []).map(row => row.zoneid).filter(Boolean))]
      if (!this.snapshotZoneIds.length) throw new Error('message.snapshot.dialog.lookup.failed')
      await this.fetchZones(this.snapshotZoneIds, sequence)
    },
    handleDomainChange (domainId) {
      this.domainid = domainId
      this.form.account = null
      this.account = null
    },
    handleAccountChange (accountName) {
      if (accountName) {
        this.account = accountName
      } else {
        this.account = null
      }
    },
    handleSubmit (e) {
      if (this.loading || this.queryLoading || this.lookupError || this.snapshotSafetyBlocked) return
      this.formRef.value.validate().then(async () => {
        if (this.loading || this.queryLoading || this.snapshotSafetyBlocked || !this.dialogCurrent()) return
        const formRaw = toRaw(this.form)
        const values = this.handleRemoveFields(formRaw)
        const params = {}
        if (this.resource.intervaltype) {
          params.snapshotid = this.resource.id
        } else {
          params.volumeid = this.resource.id
        }

        for (const key in values) {
          const input = values[key]
          if (input === undefined) {
            continue
          }
          params[key] = input
        }
        this.loading = true
        if (this.snapshotSafetyTarget) {
          try {
            const operation = await this.submitSnapshotSafely(params)
            if (operation.jobid && this.dialogCurrent()) this.closeModal()
          } finally { this.loading = false }
          return
        }
        postAPI('createTemplate', params).then(response => {
          this.$pollJob({
            jobId: response.createtemplateresponse.jobid,
            title: this.$t('message.success.create.template'),
            description: values.name,
            successMessage: this.$t('message.success.create.template'),
            successMethod: (result) => {
              this.closeModal()
            },
            errorMessage: this.$t('message.create.template.failed'),
            loadingMessage: this.$t('message.create.template.processing'),
            catchMessage: this.$t('error.fetching.async.job.result')
          })
          this.closeModal()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch((error) => {
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        else this.$notifyError(error)
      })
    },
    closeModal () {
      this.$emit('close-action')
    },
    isAdminOrDomainAdmin () {
      return ['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo.roletype)
    }
  }
}
</script>

<style lang="scss" scoped>
.form {
  width: 80vw;

  @media (min-width: 500px) {
    min-width: 400px;
    width: 100%;
  }
}
</style>
