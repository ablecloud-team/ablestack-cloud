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
      <a-alert
        v-if="resource"
        type="info"
        style="margin-bottom: 16px">
        <template #message>
          <div style="display: block; width: 100%;">
            <div style="display: block; margin-bottom: 8px;">
              <strong>{{ $t('message.clone.offering.from') }}: {{ resource.name }}</strong>
            </div>
            <div style="display: block; font-size: 12px;">
              {{ $t('message.clone.offering.edit.hint') }}
            </div>
          </div>
        </template>
      </a-alert>
      <a-form
        layout="vertical"
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
      >
        <a-form-item name="name" ref="name">
          <template #label>
            <tooltip-label :title="$t('label.name')" :tooltip="apiParams.name.description"/>
          </template>
          <a-input
            v-focus="true"
            v-model:value="form.name"/>
        </a-form-item>
        <a-form-item name="description" ref="description">
          <template #label>
            <tooltip-label :title="$t('label.description')" :tooltip="apiParams.description.description"/>
          </template>
          <a-input v-model:value="form.description"/>
        </a-form-item>
        <a-form-item name="zoneid" ref="zoneid">
          <template #label>
            <tooltip-label :title="$t('label.zoneid')" :tooltip="apiParams.zoneid ? apiParams.zoneid.description : ''"/>
          </template>
          <a-select
            allowClear
            v-model:value="form.zoneid"
            :disabled="loading"
            :loading="zones.loading"
            @change="onChangeZone"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="zone in zones.opts" :key="zone.name" :label="zone.name">
              <span>
                <resource-icon v-if="zone.icon" :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
                <global-outlined v-else style="margin-right: 5px"/>
                {{ zone.name }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="externalid" ref="externalid">
          <template #label>
            <tooltip-label :title="$t('label.externalid')" :tooltip="apiParams.externalid ? apiParams.externalid.description : ''"/>
          </template>
          <a-select
            allowClear
            v-model:value="form.externalid"
            :loading="externals.loading"
            :disabled="loading || externals.loading || externalLoadFailed"
            :notFoundContent="externalEmptyMessage"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option v-for="opt in externals.opts" :key="opt.id" :value="opt.externalid" :label="opt.name">
              {{ opt.name }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item name="allowuserdrivenbackups" ref="allowuserdrivenbackups" v-if="!isFixedIdProvider">
          <template #label>
            <tooltip-label :title="$t('label.allowuserdrivenbackups')" :tooltip="apiParams.allowuserdrivenbackups ? apiParams.allowuserdrivenbackups.description : ''"/>
          </template>
          <a-switch v-model:checked="form.allowuserdrivenbackups"/>
        </a-form-item>
        <a-form-item name="ispublic" ref="ispublic" :label="$t('label.ispublic')" v-if="isAdmin()">
          <a-switch v-model:checked="form.ispublic" @change="onChangeIsPublic" />
        </a-form-item>
        <a-form-item name="domainid" ref="domainid" v-if="!form.ispublic">
          <template #label>
            <tooltip-label :title="$t('label.domainid')" :tooltip="apiParams.domainid.description"/>
          </template>
          <a-select
            mode="multiple"
            :getPopupContainer="(trigger) => trigger.parentNode"
            v-model:value="form.domainid"
            @change="onChangeDomain"
            showSearch
            optionFilterProp="label"
            :filterOption="(input, option) => {
              return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }"
            :loading="domains.loading"
            :placeholder="apiParams.domainid.description">
            <a-select-option v-for="(opt, optIndex) in domains.opts" :key="optIndex" :label="opt.path || opt.name || opt.description">
              <span>
                <resource-icon v-if="opt && opt.icon" :image="opt.icon.base64image" size="1x" style="margin-right: 5px"/>
                <block-outlined v-else style="margin-right: 5px" />
                {{ opt.path || opt.name || opt.description }}
              </span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <div :span="24" class="action-button">
          <a-button :loading="loading" @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button :loading="loading" :disabled="providers.loading || externals.loading || !externalOptionsLoaded || !externals.opts.length" ref="submit" type="primary" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import ResourceIcon from '@/components/view/ResourceIcon'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { GlobalOutlined } from '@ant-design/icons-vue'
import { isAdmin } from '@/role'
import { fixedBackupOfferingExternalId, loadBackupOfferingOptions } from '@/utils/backupOfferingOptions'

export default {
  name: 'CloneBackupOffering',
  components: {
    TooltipLabel,
    ResourceIcon,
    GlobalOutlined
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
      zones: {
        loading: false,
        opts: []
      },
      providers: {
        loading: false,
        opts: []
      },
      externals: {
        loading: false,
        opts: []
      },
      domains: {
        loading: false,
        opts: []
      },
      selectedZoneId: null,
      providerRequest: 0,
      externalRequest: 0,
      externalOptionsLoaded: false,
      externalLoadFailed: false,
      hasProviderOfferings: false
    }
  },
  beforeCreate () {
    this.apiParams = this.$getApiParams('cloneBackupOffering')
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  computed: {
    isFixedIdProvider () {
      return !!fixedBackupOfferingExternalId(this.resource.provider)
    },
    externalEmptyMessage () {
      if (this.externalLoadFailed) return this.$t('error.fetching.data')
      return this.$t(this.hasProviderOfferings ? 'message.backup.offering.externalid.all.registered' : 'message.backup.offering.externalid.none')
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        allowuserdrivenbackups: true
      })
      this.rules = reactive({
        name: [{ required: true, message: this.$t('message.error.required.input') }],
        description: [{ required: true, message: this.$t('message.error.required.input') }],
        externalid: [{ required: true, message: this.$t('message.error.select') }]
      })
    },
    fetchData () {
      this.fetchZone()
      this.fetchDomain()
      this.$nextTick(() => {
        this.populateFormFromResource()
      })
    },
    fetchZone () {
      this.zones.loading = true
      getAPI('listZones', { available: true, showicon: true }).then(json => {
        this.zones.opts = json.listzonesresponse.zone || []
        this.$nextTick(() => {
          this.populateFormFromResource()
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.zones.loading = false
      })
    },
    fetchDomain () {
      this.domains.loading = true
      const params = { listAll: true, showicon: true, details: 'min' }
      getAPI('listDomains', params).then(json => {
        this.domains.opts = json.listdomainsresponse.domain || []
        this.$nextTick(() => {
          this.populateFormFromResource()
        })
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.domains.loading = false
      })
    },
    fetchProvider (zoneId) {
      const request = ++this.providerRequest
      if (!zoneId) {
        this.providers.opts = []
        this.externals.opts = []
        this.form.externalid = null
        return
      }
      const isCurrent = () => request === this.providerRequest && this.selectedZoneId === zoneId
      this.providers.loading = true
      return getAPI('listBackupProvidersForZone', { zoneid: zoneId }).then(json => {
        if (!isCurrent()) return
        this.providers.opts = json.listbackupprovidersforzoneresponse.providers || []
        const sourceProvider = this.resource?.provider
        const provider = this.providers.opts.find(provider => {
          return provider.name?.toLowerCase() === sourceProvider?.toLowerCase()
        })
        if (!provider) {
          this.externals.opts = []
          this.form.externalid = null
          return
        }
        return this.fetchExternal(zoneId, provider.name)
      }).catch(error => {
        if (isCurrent()) this.$notifyError(error)
      }).finally(() => {
        if (isCurrent()) this.providers.loading = false
      })
    },
    async fetchExternal (zoneId, providerName) {
      const request = ++this.externalRequest
      this.externalOptionsLoaded = false
      this.externalLoadFailed = false
      this.hasProviderOfferings = false
      this.externals.opts = []
      if (!zoneId || !providerName) {
        this.externals.loading = false
        this.form.externalid = null
        return null
      }

      const isCurrent = () => request === this.externalRequest && this.selectedZoneId === zoneId
      this.externals.loading = true
      try {
        const result = await loadBackupOfferingOptions(getAPI, zoneId, providerName)
        if (!isCurrent()) return null
        this.externals.opts = result.options
        this.externalOptionsLoaded = true
        this.hasProviderOfferings = result.hasProviderOfferings
        const hasSelectedExternal = this.externals.opts.some(opt => {
          return String(opt.externalid) === String(this.form.externalid)
        })
        if (!hasSelectedExternal) {
          this.form.externalid = null
        }
        return result
      } catch (error) {
        if (isCurrent()) {
          this.externalLoadFailed = true
          this.externals.opts = []
          this.form.externalid = null
          this.$notifyError(error)
        }
        return null
      } finally {
        if (isCurrent()) this.externals.loading = false
      }
    },
    populateFormFromResource () {
      if (!this.resource) return

      const r = this.resource

      this.form.name = r.name + ' - Clone'
      this.form.description = r.description || r.name

      if (r.allowuserdrivenbackups !== undefined) {
        this.form.allowuserdrivenbackups = r.allowuserdrivenbackups
      }

      if (r.domainid) {
        let offeringDomainIds = r.domainid
        offeringDomainIds = (typeof offeringDomainIds === 'string' && offeringDomainIds.indexOf(',') !== -1) ? offeringDomainIds.split(',') : [offeringDomainIds]
        const selected = []
        for (let i = 0; i < offeringDomainIds.length; i++) {
          for (let j = 0; j < this.domains.opts.length; j++) {
            if (String(offeringDomainIds[i]) === String(this.domains.opts[j].id)) {
              selected.push(j)
            }
          }
        }
        if (selected.length > 0) {
          this.form.ispublic = false
          this.form.domainid = selected
        }
      } else {
        if (isAdmin()) {
          this.form.ispublic = true
        }
      }

      if (r.zoneid && this.zones.opts.length > 0) {
        const zone = this.zones.opts.find(z => z.id === r.zoneid)
        if (zone) {
          this.form.zoneid = zone.name
          this.selectedZoneId = zone.id
          this.fetchProvider(zone.id)
        }
      }
    },
    async handleSubmit (e) {
      if (e?.preventDefault) e.preventDefault()
      if (this.loading || this.providers.loading || this.externals.loading || !this.externalOptionsLoaded) return
      const zoneId = this.selectedZoneId
      const request = this.externalRequest
      try {
        await this.formRef.value.validate()
      } catch (error) {
        if (error.errorFields?.length) this.formRef.value.scrollToField(error.errorFields[0].name)
        return
      }
      if (this.loading || this.providers.loading || this.externals.loading || zoneId !== this.selectedZoneId || request !== this.externalRequest) return
      const values = { ...toRaw(this.form) }
      this.loading = true
      try {
        const result = await this.fetchExternal(zoneId, this.resource.provider)
        if (!result) return
        if (!result.options.some(option => option.externalid === values.externalid)) {
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: this.$t('message.backup.offering.externalid.unavailable')
          })
          return
        }
        const params = {
          sourceofferingid: this.resource.id
        }

        if (values.name) {
          params.name = values.name
        }

        if (values.description) {
          params.description = values.description
        }

        if (values.zoneid) {
          const zone = this.zones.opts.find(z => z.name === values.zoneid)
          if (zone) {
            params.zoneid = zone.id
          }
        }

        if (values.externalid) {
          params.externalid = values.externalid
        }

        if (values.allowuserdrivenbackups !== undefined) {
          params.allowuserdrivenbackups = this.isFixedIdProvider ? false : values.allowuserdrivenbackups
        }

        // Include selected domain IDs when offering is not public
        if (values.ispublic !== true) {
          const domainIndexes = values.domainid
          let domainId = null
          if (domainIndexes && domainIndexes.length > 0) {
            const domainIds = []
            const domains = this.domains.opts
            for (let i = 0; i < domainIndexes.length; i++) {
              domainIds.push(domains[domainIndexes[i]].id)
            }
            domainId = domainIds.join(',')
          }
          if (domainId) {
            params.domainid = domainId
          }
        }

        const title = this.$t('label.clone.backup.offering')
        const json = await postAPI('cloneBackupOffering', params)
        const jobId = json.clonebackupofferingresponse?.jobid
        if (jobId) {
          this.$pollJob({
            jobId,
            title,
            description: values.name,
            successMethod: result => {
              this.$message.success(`${title}: ${values.name}`)
              this.$emit('refresh-data')
              this.closeAction()
            },
            loadingMessage: `${title} ${this.$t('label.in.progress')} ${this.$t('label.for')} ${params.name}`,
            catchMessage: this.$t('error.fetching.async.job.result'),
            catchMethod: () => {
              this.closeAction()
            }
          })
        } else {
          this.$message.success(`${title}: ${values.name}`)
          this.$emit('refresh-data')
          this.closeAction()
        }
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.loading = false
      }
    },
    onChangeZone (value) {
      this.externalRequest++
      this.providerRequest++
      this.externalOptionsLoaded = false
      this.externalLoadFailed = false
      this.hasProviderOfferings = false
      this.providers.loading = false
      this.externals.loading = false
      if (!value) {
        this.selectedZoneId = null
        this.providers.opts = []
        this.externals.opts = []
        this.form.externalid = null
        return
      }
      const zone = this.zones.opts.find(z => z.name === value)
      if (zone) {
        this.selectedZoneId = zone.id
        this.form.externalid = null
        this.externals.opts = []
        this.fetchProvider(zone.id)
      }
    },
    onChangeIsPublic (value) {
      // when made public, clear any domain selection
      try {
        if (value === true) {
          this.form.domainid = []
        }
      } catch (e) {
        // ignore
      }
    },
    onChangeDomain (value) {
      // value is an array of selected indexes (as used in the form), when empty -> make offering public
      try {
        if (!value || (Array.isArray(value) && value.length === 0)) {
          this.form.ispublic = true
          // clear domain selection to avoid confusing hidden inputs
          this.form.domainid = []
        } else {
          this.form.ispublic = false
        }
      } catch (e) {
        // defensive - do nothing on error
      }
    },
    closeAction () {
      this.$emit('close-action')
    },
    isAdmin () {
      return isAdmin()
    }
  }
}
</script>

<style scoped lang="less">
.form-layout {
  width: 30vw;

  @media (min-width: 500px) {
    width: 450px;
  }
}
</style>
