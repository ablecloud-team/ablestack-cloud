// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.

<template>
  <a-collapse class="network-operation-policy" :bordered="false">
    <a-collapse-panel key="policy" :header="$t('label.network.operation.policy')">
      <p>{{ $t('message.network.operation.policy.intro.' + context) }}</p>
      <dl>
        <template v-for="item in items" :key="item">
          <dt>{{ $t('label.network.operation.policy.' + item) }}</dt>
          <dd>{{ $t('message.network.operation.policy.' + item) }}</dd>
        </template>
      </dl>
      <p v-if="context === 'update'">{{ $t('message.network.operation.policy.cidr.' + (networkType === 'Isolated' ? 'isolated' : 'other')) }}</p>
    </a-collapse-panel>
  </a-collapse>
</template>

<script>
export default {
  name: 'NetworkOperationPolicy',
  props: {
    context: { type: String, default: 'update', validator: value => ['update', 'delete'].includes(value) },
    networkType: { type: String, default: '' }
  },
  computed: {
    items () { return this.context === 'delete' ? ['delete'] : ['update', 'cidr', 'id'] }
  }
}
</script>

<style scoped>
.network-operation-policy { margin: 16px 0; font-size: 14px; color: var(--ui-text); }
.network-operation-policy p, .network-operation-policy dd { margin: 0 0 12px; overflow-wrap: anywhere; color: var(--ui-text-secondary); }
.network-operation-policy dt { font-weight: 600; margin-bottom: 4px; }
.network-operation-policy dl { margin-bottom: 0; }
</style>
