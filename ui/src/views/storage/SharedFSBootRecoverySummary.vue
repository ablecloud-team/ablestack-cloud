<!-- Licensed under the Apache License, Version 2.0. See NOTICE. -->
<template>
  <div class="boot-summary">
    <router-link v-if="mode === 'list'" :to="{path:'/sharedfs/' + resource.id,query:{tab:'bootrecovery'}}">{{ label }}</router-link>
    <dl v-else><dt>{{ $t('label.storage.boot.tab') }}</dt><dd>{{ label }}</dd><dt>{{ $t('label.storage.boot.observed') }}</dt><dd>{{ value?.observedAt || '—' }}</dd></dl>
  </div>
</template>
<script>
export default {
  name: 'SharedFSBootRecoverySummary',
  props: { resource: { type: Object, required: true }, mode: { type: String, default: 'detail' } },
  computed: {
    value () { return this.resource.bootrecovery },
    label () { return this.$t('label.storage.boot.state.' + String(this.value?.stale ? 'UNKNOWN' : this.value?.state || 'UNKNOWN').toLowerCase()) }
  }
}
</script>
<style scoped>
.boot-summary { color: var(--ui-text-primary); }
.boot-summary dl { margin: 16px 0; }
.boot-summary dt { font-weight: 600; margin-top: 12px; }
.boot-summary dd { margin: 4px 0; overflow-wrap: anywhere; }
</style>
