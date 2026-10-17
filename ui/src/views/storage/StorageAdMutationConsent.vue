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
  <mold-dialog v-if="visible" :visible="visible" :title="title" @cancel="$emit('cancel')">
    <a-alert type="warning" show-icon :message="$t('message.storage.service.ad.maintenance.help')" />
    <a-form layout="vertical">
      <a-form-item><a-checkbox v-model:checked="maintenancewindow">{{ $t('message.storage.template.maintenance.confirm') }}</a-checkbox></a-form-item>
      <a-form-item :label="$t('label.storage.config.confirmation')"><a-input v-model:value="confirmation" :placeholder="instance.name" autocomplete="off" /></a-form-item>
    </a-form>
    <template #footer>
      <a-button @click="$emit('cancel')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :disabled="!approved" @click="approve">{{ $t('label.ok') }}</a-button>
    </template>
  </mold-dialog>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog'
export default {
  name: 'StorageAdMutationConsent',
  components: { MoldDialog },
  props: { visible: { type: Boolean, default: false }, title: { type: String, default: '' }, instance: { type: Object, required: true }, scope: { type: String, required: true } },
  emits: ['approve', 'cancel'],
  data: () => ({ maintenancewindow: false, confirmation: '' }),
  computed: { approved () { return !!this.instance?.id && !!this.instance.name && !!this.scope && this.maintenancewindow === true && this.confirmation === this.instance.name } },
  watch: { scope () { this.reset() }, visible () { this.reset() }, instance: { deep: true, handler () { this.reset() } } },
  methods: {
    reset () { this.maintenancewindow = false; this.confirmation = '' },
    approve () { if (!this.approved) return; this.$emit('approve', { maintenancewindow: true, confirmation: this.confirmation, scope: this.scope }); this.reset() }
  }
}
</script>
