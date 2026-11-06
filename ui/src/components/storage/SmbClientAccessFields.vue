<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <div>
    <a-form-item :label="$t('label.storage.client.source')">
      <a-radio-group :value="value.mode" :disabled="disabled" @change="change({mode:$event.target.value})">
        <a-radio value="RESTRICTED">{{ $t('label.storage.client.restricted') }}</a-radio>
        <a-radio value="ANY_SOURCE">{{ $t('label.storage.client.any') }}</a-radio>
      </a-radio-group>
    </a-form-item>
    <a-form-item v-if="value.mode==='RESTRICTED'" :label="$t('label.storage.client.cidrs')" :validate-status="validationError?'error':''" :help="validationError ? $t('message.storage.client.'+validationError) : ''">
      <a-textarea :value="value.input" :rows="3" :disabled="disabled" :aria-label="$t('label.storage.client.cidrs')" @update:value="change({input:$event})" />
      <p class="client-help">{{ $t('message.storage.client.input') }}</p>
    </a-form-item>
    <a-alert type="info" show-icon :message="$t('message.storage.client.layers')" :description="$t('message.storage.client.nat')" />
    <template v-if="requireConfirmation && value.mode==='ANY_SOURCE'">
      <a-alert class="client-spaced" type="warning" show-icon :message="$t('message.storage.client.clear')" />
      <a-checkbox class="client-spaced" :checked="value.confirmed" :disabled="disabled" @change="$emit('update:value',{...value,confirmed:$event.target.checked})">{{ $t('message.storage.client.confirm') }}</a-checkbox>
    </template>
    <a-alert class="client-spaced" type="warning" show-icon :message="$t('message.storage.client.sessions')" />
  </div>
</template>
<script>
import { clientAccessValues } from '@/utils/smbClientAccess'
export default {
  name: 'SmbClientAccessFields',
  props: { value: { type: Object, required: true }, disabled: Boolean, requireConfirmation: Boolean },
  emits: ['update:value'],
  computed: { validationError () { try { clientAccessValues(this.value); return '' } catch (e) { return e.message } } },
  methods: { change (patch) { this.$emit('update:value', { ...this.value, ...patch, confirmed: false }) } }
}
</script>
<style scoped>
.client-spaced { margin-top: 16px; }
.client-help { color: var(--ui-text-secondary); font-size: 12px; line-height: 1.7; margin: 8px 0 0; }
</style>
