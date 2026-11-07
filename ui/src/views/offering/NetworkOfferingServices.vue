<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for ownership information.
Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->

<template>
  <section class="offering-services" data-testid="offering-services">
    <a-alert show-icon type="info" :message="$t('message.offering.service.catalog')" />
    <a-empty v-if="!services.length" :description="$t('label.no.data')" />
    <a-list v-else :data-source="services" item-layout="vertical" size="small">
      <template #renderItem="{ item }">
        <a-list-item>
          <h4>{{ item.name }}</h4>
          <dl>
            <dt>{{ $t('label.provider') }}</dt>
            <dd>{{ item.provider?.map(provider => provider.name).join(', ') || '—' }}</dd>
            <template v-for="capability in item.capability || []" :key="capability.name">
              <dt>{{ capability.name }}</dt>
              <dd>{{ capabilityText(capability.value) }}</dd>
            </template>
          </dl>
        </a-list-item>
      </template>
    </a-list>
  </section>
</template>

<script>
export default {
  name: 'NetworkOfferingServices',
  props: {
    resource: { type: Object, required: true }
  },
  computed: {
    services () { return this.resource.service || [] }
  },
  methods: {
    capabilityText (value) {
      if (String(value) === 'true') return this.$t('label.yes')
      if (String(value) === 'false') return this.$t('label.no')
      return value ?? '—'
    }
  }
}
</script>

<style scoped>
.offering-services { min-width: 0; overflow-wrap: anywhere; }
.offering-services :deep(.ant-alert) { margin-bottom: 12px; }
dl { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 2fr); gap: 6px 12px; margin: 0; }
dt { color: var(--ui-text-secondary); }
dd { margin: 0; }
@media (max-width: 480px) {
  dl { grid-template-columns: minmax(0, 1fr); }
  dd { margin-bottom: 6px; }
}
</style>
