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
        v-if="pendingStaging"
        type="warning"
        show-icon
        :message="$t('message.secondary.storage.staging.pending', [pendingStaging.name])"
        :description="pendingStaging.id" />
      <a-alert v-if="error" type="error" show-icon :message="$t(error)" />
      <a-form
        v-if="!pendingStaging"
        :ref="formRef"
        :model="form"
        :rules="rules"
        layout="vertical"
        @finish="handleSubmit">
        <a-form-item name="name" ref="name" html-for="secondary-storage-name" :label="$t('label.name')">
          <a-input id="secondary-storage-name" v-model:value="form.name" />
        </a-form-item>
        <a-form-item name="provider" ref="provider" :label="$t('label.providername')">
          <a-select
            v-model:value="form.provider"
            @change="onProviderChange"
            showSearch
            optionFilterProp="value"
            :filterOption="(input, option) => {
              return option.value.toLowerCase().indexOf(input.toLowerCase()) >= 0
            }" >
            <a-select-option
              :value="prov"
              v-for="(prov,idx) in providers"
              :key="idx"
            >{{ prov }}</a-select-option>
          </a-select>
        </a-form-item>
        <div v-if="!['Swift', 'S3'].includes(form.provider)">
          <a-form-item name="zone" ref="zone" :label="$t('label.zone')">
            <a-select
              v-model:value="form.zone"
              @change="onZoneChange"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option
                :value="zone.id"
                v-for="(zone) in zones"
                :key="zone.id"
                :label="zone.name">
                <span>
                  <resource-icon v-if="zone.icon" :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <global-outlined v-else style="margin-right: 5px" />
                  {{ zone.name }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item name="server" ref="server" :label="$t('label.server')">
            <a-input v-model:value="form.server" />
          </a-form-item>
          <a-form-item name="path" ref="path" :label="$t('label.path')">
            <a-input v-model:value="form.path" />
          </a-form-item>
        </div>
        <div v-if="form.provider === 'SMB/CIFS'">
          <a-form-item name="smbUsername" ref="smbUsername" :label="$t('label.smbusername')">
            <a-input v-model:value="form.smbUsername" />
          </a-form-item>
          <a-form-item name="smbPassword" ref="smbPassword" :label="$t('label.smbpassword')">
            <a-input-password v-model:value="form.smbPassword" />
          </a-form-item>
          <a-form-item name="smbDomain" ref="smbDomain" :label="$t('label.smbdomain')">
            <a-input v-model:value="form.smbDomain" />
          </a-form-item>
        </div>
        <div v-if="form.provider === 'Swift'">
          <a-form-item name="url" ref="url" :label="$t('label.url')">
            <a-input v-model:value="form.url" />
          </a-form-item>
          <a-form-item name="account" ref="account" :label="$t('label.account')">
            <a-input v-model:value="form.account" />
          </a-form-item>
          <a-form-item name="username" ref="username" :label="$t('label.username')">
            <a-input v-model:value="form.username" />
          </a-form-item>
          <a-form-item name="key" ref="key" :label="$t('label.key')">
            <a-input-password v-model:value="form.key" />
          </a-form-item>
          <a-form-item name="storagepolicy" ref="storagepolicy" :label="$t('label.storagepolicy')">
            <a-input v-model:value="form.storagepolicy" />
          </a-form-item>
        </div>
        <div v-if="form.provider === 'S3'">
          <a-form-item name="zone" ref="zone" :label="$t('label.zone')">
            <a-select
              v-model:value="form.zone"
              @change="onZoneChange"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }" >
              <a-select-option
                :value="zone.id"
                v-for="(zone) in zones"
                :key="zone.id"
                :label="zone.name">
                <span>
                  <resource-icon v-if="zone.icon" :image="zone.icon.base64image" size="1x" style="margin-right: 5px"/>
                  <global-outlined v-else style="margin-right: 5px" />
                  {{ zone.name }}
                </span>
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item name="secondaryStorageAccessKey" ref="secondaryStorageAccessKey" :label="$t('label.s3.access.key')">
            <a-input v-model:value="form.secondaryStorageAccessKey" />
          </a-form-item>
          <a-form-item name="secondaryStorageSecretKey" ref="secondaryStorageSecretKey" :label="$t('label.s3.secret.key')">
            <a-input-password v-model:value="form.secondaryStorageSecretKey" />
          </a-form-item>
          <a-form-item name="secondaryStorageBucket" ref="secondaryStorageBucket" :label="$t('label.s3.bucket')">
            <a-input v-model:value="form.secondaryStorageBucket"/>
          </a-form-item>
          <a-form-item name="secondaryStorageEndpoint" ref="secondaryStorageEndpoint" :label="$t('label.s3.endpoint')">
            <a-input v-model:value="form.secondaryStorageEndpoint"/>
          </a-form-item>
          <a-form-item name="secondaryStorageHttps" ref="secondaryStorageHttps" :label="$t('label.s3.use.https')">
            <a-switch v-model:checked="form.secondaryStorageHttps" />
          </a-form-item>
          <a-form-item name="secondaryStorageConnectionTimeout" ref="secondaryStorageConnectionTimeout" :label="$t('label.s3.connection.timeout')">
            <a-input v-model:value="form.secondaryStorageConnectionTimeout"/>
          </a-form-item>
          <a-form-item name="secondaryStorageMaxError" ref="secondaryStorageMaxError" :label="$t('label.s3.max.error.retry')">
            <a-input v-model:value="form.secondaryStorageMaxError"/>
          </a-form-item>
          <a-form-item name="secondaryStorageSocketTimeout" ref="secondaryStorageSocketTimeout" :label="$t('label.s3.socket.timeout')">
            <a-input v-model:value="form.secondaryStorageSocketTimeout"/>
          </a-form-item>
          <a-form-item v-if="canCreateStaging" name="secondaryStorageNFSStaging" ref="secondaryStorageNFSStaging" :label="$t('label.create.nfs.secondary.staging.storage')">
            <a-switch v-model:checked="form.secondaryStorageNFSStaging" />
          </a-form-item>
        </div>
        <div v-if="stagingRequested">
          <a-form-item name="secondaryStorageNFSServer" ref="secondaryStorageNFSServer" :label="$t('label.s3.nfs.server')">
            <a-input v-model:value="form.secondaryStorageNFSServer" />
          </a-form-item>
          <a-form-item name="secondaryStorageNFSPath" ref="secondaryStorageNFSPath" :label="$t('label.s3.nfs.path')">
            <a-input v-model:value="form.secondaryStorageNFSPath"/>
          </a-form-item>
        </div>
        <div v-if="showCopyTemplatesToggle">
          <a-form-item
            name="copyTemplatesFromOtherSecondaryStorages"
            ref="copyTemplatesFromOtherSecondaryStorages"
            :label="$t('label.copy.templates.from.other.secondary.storages')">
            <a-switch
              v-model:checked="form.copyTemplatesFromOtherSecondaryStorages"
              @change="onCopyTemplatesToggleChanged"
            />
          </a-form-item>
        </div>
      </a-form>
      <mold-dialog-actions :busy="loading">
        <a-button :disabled="loading" @click="closeModal">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" ref="submit" :loading="loading" @click="handleSubmit">
          {{ $t(pendingStaging ? 'label.retry' : 'label.ok') }}
        </a-button>
      </mold-dialog-actions>
    </a-spin>
  </div>
</template>
<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { ref, reactive } from 'vue'
import { getAPI, postAPI } from '@/api'
import ResourceIcon from '@/components/view/ResourceIcon'

export default {
  name: 'AddSecondaryStorage',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  components: {
    MoldDialogActions,
    ResourceIcon
  },
  inject: ['parentFetchData'],
  data () {
    return {
      providers: ['NFS', 'SMB/CIFS', 'S3', 'Swift'],
      zones: [],
      loading: false,
      pendingStaging: null,
      error: null,
      showCopyTemplatesToggle: false,
      copyTemplatesTouched: false
    }
  },
  computed: {
    canCreateStaging () { return !!this.$store.getters.apis.createSecondaryStagingStore },
    stagingRequested () {
      return this.form.provider === 'S3' && this.canCreateStaging && this.form.secondaryStorageNFSStaging
    }
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({
        provider: 'NFS',
        secondaryStorageHttps: true,
        copyTemplatesFromOtherSecondaryStorages: true
      })
      this.rules = reactive({
        zone: [{
          validator: (_, value) => {
            if (value || (this.form.provider === 'S3' && !this.stagingRequested)) return Promise.resolve()
            return Promise.reject(new Error(this.$t('label.required')))
          }
        }],
        server: [{ required: true, message: this.$t('label.required') }],
        path: [{ required: true, message: this.$t('label.required') }],
        smbUsername: [{ required: true, message: this.$t('label.required') }],
        smbPassword: [{ required: true, message: this.$t('label.required') }],
        smbDomain: [{ required: true, message: this.$t('label.required') }],
        url: [{ required: true, message: this.$t('label.required') }],
        account: [{ required: true, message: this.$t('label.required') }],
        username: [{ required: true, message: this.$t('label.required') }],
        key: [{ required: true, message: this.$t('label.required') }],
        secondaryStorageAccessKey: [{ required: true, message: this.$t('label.required') }],
        secondaryStorageSecretKey: [{ required: true, message: this.$t('label.required') }],
        secondaryStorageBucket: [{ required: true, message: this.$t('label.required') }],
        secondaryStorageNFSServer: [{ required: true, message: this.$t('label.required') }],
        secondaryStorageNFSPath: [{ required: true, message: this.$t('label.required') }]
      })
    },
    fetchData () {
      this.listZones()
      if (!this.$store.getters.apis.listImageStores) return
      getAPI('listImageStores', {}, { preserveOnFailure: true }).then(json => {
        this.showCopyTemplatesToggle = !!json.listimagestoresresponse?.imagestore?.length
      }).catch(() => {})
    },
    closeModal () {
      if (this.loading) return
      this.pendingStaging = null
      this.error = null
      this.copyTemplatesTouched = false
      this.initForm()
      this.form.zone = this.zones[0]?.id
      this.fetchCopyTemplatesConfig()
      this.$emit('close-action')
    },
    fetchCopyTemplatesConfig () {
      const zone = this.form.zone
      if (!zone || !this.$store.getters.apis.listConfigurations) return
      getAPI('listConfigurations', {
        name: 'copy.templates.from.other.secondary.storages',
        zoneid: zone
      }, { preserveOnFailure: true }).then(json => {
        if (this.form.zone !== zone || this.copyTemplatesTouched || this.loading || this.pendingStaging) return
        const configuration = json.listconfigurationsresponse?.configuration?.find(item =>
          item.name === 'copy.templates.from.other.secondary.storages')
        if (configuration) this.form.copyTemplatesFromOtherSecondaryStorages = configuration.value === 'true'
      }).catch(() => {})
    },
    onZoneChange (value) {
      this.form.zone = value
      this.copyTemplatesTouched = false
      this.fetchCopyTemplatesConfig()
    },
    onProviderChange (provider) {
      this.form.provider = provider
      this.form.secondaryStorageNFSStaging = false
    },
    listZones () {
      if (!this.$store.getters.apis.listZones) return
      getAPI('listZones', { showicon: true }, { preserveOnFailure: true }).then(json => {
        this.zones = json.listzonesresponse?.zone || []
        this.onZoneChange(this.form.zone || this.zones[0]?.id)
      }).catch(() => { this.error = 'error.fetching.data' })
    },
    onCopyTemplatesToggleChanged () {
      this.copyTemplatesTouched = true
    },
    storageUrl (protocol, server, path) {
      const host = server.trim().replace(/\/$/, '')
      const directory = path.trim().replace(/^\//, '')
      return (host.includes('://') ? host : protocol + '://' + host) + '/' + directory
    },
    registrationParams () {
      const form = this.form
      const provider = form.provider === 'SMB/CIFS' ? 'SMB' : form.provider
      const params = { provider }
      if (form.name?.trim()) params.name = form.name.trim()
      let details = {}
      if (provider === 'NFS' || provider === 'SMB') {
        params.zoneid = form.zone
        params.url = this.storageUrl(provider === 'SMB' ? 'cifs' : 'nfs', form.server, form.path)
      }
      if (provider === 'SMB') {
        details = { user: form.smbUsername, password: form.smbPassword, domain: form.smbDomain }
      } else if (provider === 'Swift') {
        params.url = form.url.trim()
        details = { account: form.account, username: form.username, key: form.key, storagepolicy: form.storagepolicy }
      } else if (provider === 'S3') {
        details = {
          accesskey: form.secondaryStorageAccessKey,
          secretkey: form.secondaryStorageSecretKey,
          bucket: form.secondaryStorageBucket,
          usehttps: !!form.secondaryStorageHttps,
          endpoint: form.secondaryStorageEndpoint,
          connectiontimeout: form.secondaryStorageConnectionTimeout,
          maxerrorretry: form.secondaryStorageMaxError,
          sockettimeout: form.secondaryStorageSocketTimeout
        }
      }
      if (this.showCopyTemplatesToggle && this.copyTemplatesTouched) {
        details.copytemplatesfromothersecondarystorages = String(form.copyTemplatesFromOtherSecondaryStorages)
      }
      Object.entries(details).filter(([, value]) => value !== undefined && value !== '').forEach(([key, value], index) => {
        params[`details[${index}].key`] = key
        params[`details[${index}].value`] = value
      })
      return params
    },
    async handleSubmit (event) {
      if (event?.preventDefault) event.preventDefault()
      if (this.loading) return
      this.loading = true
      this.error = null
      let completed = false
      try {
        if (!this.pendingStaging) {
          await this.formRef.value.validate()
          const params = this.registrationParams()
          const staging = this.stagingRequested
            ? { provider: 'nfs', zoneid: this.form.zone, url: this.storageUrl('nfs', this.form.secondaryStorageNFSServer, this.form.secondaryStorageNFSPath) }
            : null
          const result = await postAPI('addImageStore', params, { preserveOnFailure: true })
          if (staging) {
            const response = result.addimagestoreresponse
            const store = response?.imagestore || response
            this.pendingStaging = { id: store?.id, name: store?.name || params.name || params.provider, params: staging }
            this.parentFetchData()
          }
        }
        if (this.pendingStaging) {
          await postAPI('createSecondaryStagingStore', this.pendingStaging.params, { preserveOnFailure: true })
        }
        completed = true
        this.$notification.success({ message: this.$t('label.add.secondary.storage') })
      } catch (error) {
        if (error?.errorFields?.length) {
          this.formRef.value.scrollToField(error.errorFields[0].name)
        } else {
          this.error = this.pendingStaging ? 'message.secondary.storage.staging.failed' : 'message.secondary.storage.registration.failed'
        }
      } finally {
        this.loading = false
      }
      if (completed) {
        this.closeModal()
        this.parentFetchData()
      }
    }
  }
}
</script>
<style lang="scss" scoped>
.form-layout {
  .ant-alert { margin-bottom: 12px; }

}
</style>
