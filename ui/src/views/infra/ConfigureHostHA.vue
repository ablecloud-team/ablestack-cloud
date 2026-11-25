<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <a-form layout="vertical" @finish="save">
    <a-alert v-if="error" type="error" show-icon :message="$t(error)" />
    <a-form-item :label="$t('label.provider')" html-for="host-ha-provider">
      <a-select id="host-ha-provider" v-model:value="provider" :options="options" :loading="loading" :disabled="loading || saving || !options.length" />
    </a-form-item>
    <a-empty v-if="!loading && !error && !options.length" :description="$t('message.host.ha.no.providers')" />
    <mold-dialog-actions :busy="saving">
      <a-button @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button v-if="error === 'message.host.ha.providers.failed'" @click="loadProviders">{{ $t('label.retry') }}</a-button>
      <a-button type="primary" :disabled="loading || !provider" @click="save">{{ $t('label.ok') }}</a-button>
    </mold-dialog-actions>
  </a-form>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'

export default {
  name: 'ConfigureHostHA',
  components: { MoldDialogActions },
  props: { resource: { type: Object, required: true } },
  data () { return { provider: undefined, options: [], loading: false, saving: false, error: '' } },
  created () { this.loadProviders() },
  methods: {
    async loadProviders () {
      this.loading = true
      this.error = ''
      try {
        const { listhosthaprovidersresponse: result } = await getAPI('listHostHAProviders', { hypervisor: this.resource.hypervisor }, { preserveOnFailure: true })
        const rows = Object.values(result || {}).find(Array.isArray) || []
        this.options = rows.filter(row => row.haprovider).map(row => ({ value: row.haprovider, label: row.haprovider }))
        const current = this.resource.hostha?.haprovider?.toLowerCase()
        this.provider = this.options.find(option => option.value.toLowerCase() === current)?.value || this.options[0]?.value
      } catch {
        this.error = 'message.host.ha.providers.failed'
      } finally {
        this.loading = false
      }
    },
    async save () {
      if (this.saving || !this.provider) return
      this.saving = true
      this.error = ''
      try {
        await postAPI('configureHAForHost', { hostid: this.resource.id, provider: this.provider }, { preserveOnFailure: true })
        this.$message.success(this.$t('label.success'))
        this.$emit('refresh-data')
        this.$emit('close-action')
      } catch {
        this.error = 'message.host.ha.save.failed'
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
