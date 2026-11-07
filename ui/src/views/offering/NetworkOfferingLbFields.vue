// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.

<template>
  <section class="network-offering-lb-fields">
    <a-alert v-if="reason" type="info" show-icon :message="$t('message.offering.lb.excluded.' + reason)" />
    <template v-else>
      <h3>{{ $t('label.offering.lb.settings') }}</h3>
      <p>{{ $t('message.offering.lb.application') }}</p>
      <a-form-item name="maxconnections" :label="$t('label.offering.lb.maxconnections')">
        <a-input :value="modelValue.maxconnections" inputmode="numeric" :placeholder="$t('message.offering.lb.inherit')" @update:value="value => change('maxconnections', value)" />
        <p>{{ $t('message.offering.lb.range') }}</p>
      </a-form-item>
      <a-form-item name="keepaliveenabled" :label="$t('label.offering.lb.keepalive')">
        <a-select :value="typeof modelValue.keepaliveenabled === 'boolean' ? String(modelValue.keepaliveenabled) : undefined" :placeholder="$t('message.offering.lb.unknown')" @update:value="value => change('keepaliveenabled', value === 'true')">
          <a-select-option value="true">{{ $t('label.enabled') }}</a-select-option>
          <a-select-option value="false">{{ $t('label.disabled') }}</a-select-option>
        </a-select>
      </a-form-item>
    </template>
  </section>
</template>
<script>
import { offeringLbSupport } from '@/utils/networkOfferingLb'
export default {
  name: 'NetworkOfferingLbFields',
  props: { modelValue: { type: Object, required: true }, offering: { type: Object, required: true } },
  emits: ['update:modelValue'],
  computed: { reason () { return offeringLbSupport(this.offering) } },
  methods: { change (key, value) { this.$emit('update:modelValue', { ...this.modelValue, [key]: value }) } }
}
</script>
<style scoped>
.network-offering-lb-fields { margin: 16px 0; font-size: 14px; color: var(--ui-text); }
.network-offering-lb-fields p { margin: 8px 0 12px; color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.network-offering-lb-fields h3 { font-size: 14px; font-weight: 600; color: var(--ui-text); }
</style>
