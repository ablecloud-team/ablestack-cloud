<!-- Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.
See NOTICE. Apache License, Version 2.0. http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <section class="nfs-identity-panel">
    <a-alert v-if="error" type="warning" show-icon :message="$t('message.storage.nfs.identity.unavailable')" />
    <a-spin :spinning="loading">
      <a-descriptions size="small" :column="{ xs: 1, md: 2 }">
        <a-descriptions-item :label="$t('label.storage.nfs.identity.configured')">{{ report?.mode || '-' }} / {{ report?.policyrevision ?? '-' }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.nfs.identity.effective')">
          <a-badge :status="report?.observationstatus === 'APPLIED' ? 'success' : 'warning'" />
          {{ report?.effectivemode || $t('label.unknown') }} · {{ report?.observationstatus || 'UNKNOWN' }}
        </a-descriptions-item>
        <a-descriptions-item :label="$t('label.storage.nfs.identity.domain')">{{ report?.observeddomain || '-' }}</a-descriptions-item>
        <a-descriptions-item label="Security">AUTH_SYS (sec=sys)</a-descriptions-item>
      </a-descriptions>
      <a-table v-if="report?.endpoints?.length" size="small" :columns="columns" :data-source="report.endpoints" row-key="endpoint" :pagination="false" :scroll="{ x: 620 }" />
    </a-spin>
    <a-alert class="nfs-identity-help" type="info" show-icon :message="$t('message.storage.nfs.identity.' + (mode === 'NUMERIC' ? 'numeric' : 'name'))">
      <template #description>
        <p>{{ $t('message.storage.nfs.identity.permissions') }}</p>
        <code v-if="mode === 'NUMERIC'">cat /sys/module/nfs/parameters/nfs4_disable_idmapping # Y; mount -o vers=4,sec=sys; ls -ln</code>
        <code v-else>nfsidmap -d; ls -ln; stat -c '%u:%g %a' FILE</code>
      </template>
    </a-alert>
  </section>
</template>
<script>
export default {
  props: {
    report: { type: Object, default: null },
    mode: { type: String, default: 'NAME_DOMAIN' },
    loading: Boolean,
    error: Boolean
  },
  computed: {
    columns () {
      return [
        { title: this.$t('label.port'), dataIndex: 'port' },
        { title: this.$t('label.storage.nfs.identity.configured'), dataIndex: 'configuredmode' },
        { title: this.$t('label.storage.nfs.identity.effective'), dataIndex: 'effectivemode' },
        { title: this.$t('label.storage.nfs.identity.revision'), dataIndex: 'observedrevision' },
        { title: this.$t('label.state'), dataIndex: 'state' }
      ]
    }
  }
}
</script>
<style scoped>
.nfs-identity-panel { margin-bottom: 16px; }
.nfs-identity-help { margin-top: 16px; }
.nfs-identity-help p { margin-bottom: 8px; }
.nfs-identity-help code { color: inherit; white-space: normal; overflow-wrap: anywhere; }
</style>
