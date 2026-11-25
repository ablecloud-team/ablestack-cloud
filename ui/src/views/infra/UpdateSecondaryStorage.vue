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
  <a-form v-ctrl-enter="submit" layout="vertical" :model="form" @finish="submit">
    <a-form-item :label="$t('label.id')">
      <span>{{ resource.id }}</span>
    </a-form-item>
    <a-form-item name="name" html-for="secondary-storage-edit-name" :label="$t('label.name')">
      <a-input id="secondary-storage-edit-name" v-model:value="form.name" :disabled="saving" />
    </a-form-item>
    <a-form-item v-if="supports('readonly')" name="readonly" :label="$t('label.readonly')">
      <a-switch v-model:checked="form.readonly" :aria-label="$t('label.readonly')" :disabled="saving" />
    </a-form-item>
    <a-form-item v-if="supports('capacitybytes')" name="capacitybytes" :label="$t('label.capacitybytes')">
      <a-input
        v-model:value="form.capacitybytes"
        inputmode="numeric"
        :placeholder="resource.disksizetotal == null ? undefined : String(resource.disksizetotal)"
        :disabled="saving" />
      <template #extra>{{ $t('message.secondary.storage.capacity.hint') }}</template>
    </a-form-item>
    <a-alert v-if="invalidCapacity" type="error" show-icon :message="$t('message.secondary.storage.capacity.invalid')" />
    <mold-dialog-actions :busy="saving">
      <a-button :disabled="saving" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button @click="submit" type="primary" :loading="saving" :disabled="invalidCapacity">{{ $t('label.ok') }}</a-button>
    </mold-dialog-actions>
  </a-form>
</template>

<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { postAPI } from '@/api'

export default {
  props: { resource: { type: Object, required: true } },
  components: { MoldDialogActions },
  emits: ['close-action', 'refresh-data'],
  data () {
    return { form: { name: this.resource.name || '', readonly: String(this.resource.readonly) === 'true', capacitybytes: '' }, saving: false }
  },
  computed: {
    invalidCapacity () {
      const value = this.form.capacitybytes.trim()
      return !!value && (!/^\d+$/.test(value) || value.length > 19 || (value.length === 19 && value > '9223372036854775807'))
    }
  },
  methods: {
    supports (name) {
      return !!this.$store.getters.apis.updateImageStore?.params?.some(param => param.name === name)
    },
    async submit (event) {
      if (event?.preventDefault) event.preventDefault()
      if (this.saving || this.invalidCapacity || !this.resource.id || !this.$store.getters.apis.updateImageStore) return
      const params = { id: this.resource.id }
      if (this.form.name.trim()) params.name = this.form.name.trim()
      if (this.supports('readonly')) params.readonly = this.form.readonly
      if (this.supports('capacitybytes') && this.form.capacitybytes.trim()) {
        params.capacitybytes = this.form.capacitybytes.trim()
      }
      this.saving = true
      try {
        await postAPI('updateImageStore', params, { preserveOnFailure: true })
        this.$emit('refresh-data')
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.saving = false
      }
    }
  }
}
</script>
