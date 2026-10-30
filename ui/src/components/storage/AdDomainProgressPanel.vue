<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <section class="ad-domain-progress" aria-live="polite">
    <a-descriptions :column="1" bordered size="small">
      <a-descriptions-item :label="$t('label.storage.ad.membership.stage')">{{ label(status.membership) }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.storage.ad.service.stage')">{{ label(status.serviceApply) }}</a-descriptions-item>
      <a-descriptions-item v-if="status.rollback" :label="$t('label.storage.configuration.rollback')">{{ label(status.rollback) }}</a-descriptions-item>
      <a-descriptions-item v-if="status.operationid" :label="$t('label.storage.configuration.request')"><code>{{ status.operationid }}</code></a-descriptions-item>
    </a-descriptions>
    <a-alert v-if="status.failureCode || error" type="error" show-icon :message="status.failureCode || error" />
    <a-alert v-else-if="busy" type="info" show-icon :message="$t('message.storage.ad.wait.both.stages')" />
  </section>
</template>
<script>
export default {
  name: 'AdDomainProgressPanel',
  props: { status: { type: Object, default: () => ({}) }, busy: Boolean, error: String },
  methods: { label (value) { return this.$t('label.storage.ad.stage.' + (value || 'WAITING')) } }
}
</script>
<style scoped>
.ad-domain-progress { margin: 0 0 16px; color: var(--ui-text-primary); }
.ad-domain-progress :deep(.ant-alert) { margin-top: 12px; }
.ad-domain-progress code { color: var(--ui-text-primary); overflow-wrap: anywhere; }
</style>
