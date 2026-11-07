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
  <div class="dns-zone-network-form" v-ctrl-enter="handleSubmit">
    <a-spin :spinning="loading">
      <a-form
        ref="formRef"
        :model="form"
        :rules="rules"
        :id="formId"
        layout="vertical"
        @finish="handleSubmit">

        <a-form-item name="dnszoneid" ref="dnszoneid">
          <template #label>
            <tooltip-label
              :title="$t('label.dns.zone')"
              :tooltip="apiParams.dnszoneid?.description" />
          </template>
          <a-select
            v-model:value="form.dnszoneid"
            :placeholder="$t('label.dns.zone.select')"
            :loading="fetchingZones"
            :disabled="fetchingZones || loadFailed || loading"
            showSearch
            optionFilterProp="label"
            v-focus="true">
            <a-select-option
              v-for="zone in dnsZones"
              :key="zone.id"
              :value="zone.id"
              :label="zone.name">
              {{ zone.name }}
            </a-select-option>
          </a-select>
        </a-form-item>

        <a-alert
v-if="loadFailed || (!fetchingZones && dnsZones.length === 0)"
type="warning"
show-icon
class="dns-zone-choice-alert"
          :message="$t(loadFailed ? 'message.error.fetch.dns.zones' : 'message.shared.dns.zones.empty')">
          <template #description>
            <a-button size="small" @click="fetchDnsZones"><reload-outlined /> {{ $t('label.refresh') }}</a-button>
          </template>
        </a-alert>
        <a-form-item name="subdomain" ref="subdomain">
          <template #label>
            <tooltip-label
              :title="$t('label.subdomain')"
              :tooltip="apiParams.subdomain?.description" />
          </template>
          <a-input
            v-model:value="form.subdomain"
            :disabled="loading"
            :placeholder="apiParams.subdomain?.description" />
        </a-form-item>

        <MoldDialogActions :busy="loading">
          <a-button @click="closeAction">
            {{ $t('label.cancel') }}
          </a-button>
          <a-button
            type="primary"
            :loading="loading"
            :disabled="!canSubmit"
            :form="formId"
            htmlType="submit">
            {{ $t('label.ok') }}
          </a-button>
        </MoldDialogActions>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { getCurrentInstance } from 'vue'
import MoldDialogActions from '@/components/view/MoldDialogActions.vue'
import { getAPI, postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'AssociateDnsZone',
  components: {
    TooltipLabel, MoldDialogActions
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  setup () { return { formId: 'network-associate-dns-' + getCurrentInstance().uid } },
  data () {
    return {
      loading: false,
      apiParams: {},
      form: {
        dnszoneid: undefined,
        subdomain: ''
      },
      rules: {},
      fetchingZones: false,
      loadFailed: false,
      request: 0,
      dnsZones: []
    }
  },
  computed: {
    scope () { return JSON.stringify([this.resource.id, this.resource.dnszone, this.$store.getters.project?.id]) },
    canSubmit () {
      return !this.loading && !this.fetchingZones && !this.loadFailed && !this.resource.dnszone &&
        this.dnsZones.some(zone => zone.id === this.form.dnszoneid)
    }
  },
  watch: {
    scope () { this.fetchDnsZones() },
    loading (value) { this.$emit('busy', value) }
  },
  beforeUnmount () { this.request++ },
  created () {
    this.apiParams = this.$getApiParams('associateDnsZoneToNetwork') || {}
    this.rules = {
      dnszoneid: [{ required: true, message: this.$t('message.error.required.input') }]
    }
    this.fetchDnsZones()
  },
  methods: {
    async handleSubmit () {
      if (!this.canSubmit) return

      try {
        await this.$refs.formRef.validate()
      } catch (error) {
        const field = error?.errorFields?.[0]?.name
        if (field) {
          this.$refs.formRef.scrollToField(field)
        }
        return
      }

      if (!this.canSubmit) return
      this.loading = true
      const scope = this.scope

      try {
        const params = {
          dnszoneid: this.form.dnszoneid,
          networkid: this.resource.id
        }

        if (this.form.subdomain && this.form.subdomain.trim()) {
          params.subdomain = this.form.subdomain.trim()
        }

        const json = await postAPI('associateDnsZoneToNetwork', params, { preserveOnFailure: true })
        if (this.scope !== scope) return
        const result = json?.associatednszonetonetworkresponse
        if (result?.networkid !== params.networkid || result?.dnszoneid !== params.dnszoneid) {
          throw new Error(this.$t('message.shared.dns.zone.changed'))
        }
        this.$notification.success({
          message: this.$t('label.action.associate.dns.zone'),
          description: this.$t('message.success.associate.dns.zone')
        })
        this.$emit('refresh-data')
        this.closeAction()
      } catch (error) {
        this.$notification.error({
          message: this.$t('message.request.failed'),
          description: error?.response?.headers['x-description'] || error.message,
          duration: 0
        })
      } finally {
        this.loading = false
      }
    },
    closeAction () {
      this.$emit('close-action')
    },
    async fetchDnsZones () {
      const id = ++this.request
      this.fetchingZones = true
      this.loadFailed = false
      this.dnsZones = []
      this.form.dnszoneid = undefined
      try {
        const response = await getAPI('listDnsZones', {}, { preserveOnFailure: true })
        if (id !== this.request) return
        this.dnsZones = response?.listdnszonesresponse?.dnszone || []
      } catch (_) {
        if (id === this.request) this.loadFailed = true
      } finally {
        if (id === this.request) this.fetchingZones = false
      }
    }
  }
}
</script>

<style lang="less" scoped>
.dns-zone-network-form { width: 100%; min-width: 0; }
.dns-zone-choice-alert { margin: 0 0 16px; overflow-wrap: anywhere; }
</style>
