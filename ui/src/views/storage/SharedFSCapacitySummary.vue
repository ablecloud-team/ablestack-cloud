<!-- Licensed under the Apache License, Version 2.0. See NOTICE. -->
<template>
  <div v-if="mode === 'list'" class="sharedfs-capacity-cell">
    <router-link :to="{ path: '/sharedfs/' + resource.id, query: { tab: 'capacity' } }" class="capacity-value">
      {{ capacity ? bytes(capacity.provisionedbytes) : resource.sizegb || bytes(resource.size) }}
      <span v-if="capacity && capacity.volumecount != null"> · DATA {{ capacity.volumecount }}</span>
    </router-link>
    <div class="capacity-secondary">{{ capacity ? $t('label.storage.capacity.observed.usage') + ' ' + bytes(capacity.filesystemusedbytes) : $t('label.storage.capacity.initial') }}</div>
    <div class="capacity-secondary" v-if="capacity">{{ coverage }}</div>
    <div class="capacity-meta"><a-tag v-if="capacity">{{ status(capacity.usagestatus) }}</a-tag>{{ capacity?.observedat || $t('label.storage.capacity.not.observed') }}</div>
  </div>
  <dl v-else class="sharedfs-capacity-summary" :class="{ 'capacity-info': mode === 'info' }">
    <div v-for="row in rows" :key="row[0]" class="capacity-row">
      <dt>{{ $t(row[0]) }}</dt><dd>{{ row[1] }}</dd>
    </div>
  </dl>
</template>
<script>
export default {
  name: 'SharedFSCapacitySummary',
  props: { resource: { type: Object, required: true }, mode: { type: String, default: 'details' } },
  computed: {
    capacity () { return this.resource.capacity },
    coverage () {
      const c = this.capacity
      return c ? this.$t('label.storage.capacity.filesystems') + ' ' + (c.filesystemcount ?? 0) + ' · DATA ' +
        (c.observedvolumecount ?? 0) + '/' + (c.volumecount ?? '—') + ' · ' +
        this.$t('label.storage.capacity.unsupported') + ' ' + (c.unsupportedvolumecount ?? 0) : '—'
    },
    rows () {
      const c = this.capacity
      const rows = [['label.storage.capacity.initial', c ? this.bytes(c.initialvolumebytes) : this.resource.sizegb || this.bytes(this.resource.size)]]
      if (!c) return rows
      return [
        ['label.storage.capacity.provisioned', this.bytes(c.provisionedbytes) + ' · DATA ' + (c.volumecount ?? '—') + ' · ' + (c.provisioningstatus === 'CURRENT' ? this.$t('label.storage.capacity.membership.current') : this.status(c.provisioningstatus))],
        ['label.storage.capacity.observed.usage', this.bytes(c.filesystemusedbytes) + ' · ' + this.status(c.usagestatus)],
        ...rows,
        ...(this.mode === 'info' ? [['label.storage.capacity.observed.at', c.observedat || this.$t('label.storage.capacity.not.observed')]] : [['label.storage.capacity.coverage', this.coverage], ['label.storage.capacity.observed.at', c.observedat || this.$t('label.storage.capacity.not.observed')]])
      ]
    }
  },
  methods: {
    bytes (value) { return value === null || value === undefined ? '—' : this.$bytesToHumanReadableSize(value) },
    status (value) { return this.$t('label.storage.capacity.status.' + String(value || 'UNAVAILABLE').toLowerCase()) }
  }
}
</script>
<style scoped>
.sharedfs-capacity-cell { min-width: 240px; line-height: 1.7; }
.capacity-value { font-weight: 600; color: var(--ui-link); }
.capacity-secondary, .capacity-meta, .capacity-row dt { color: var(--ui-text-secondary); }
.capacity-meta { font-size: 12px; overflow-wrap: anywhere; }
.capacity-meta .ant-tag { margin-right: 8px; color: var(--ui-text-primary); background: var(--ui-bg-secondary); border-color: var(--ui-border); }
.sharedfs-capacity-summary { margin: 0; color: var(--ui-text-primary); }
.capacity-row { display: grid; grid-template-columns: 160px minmax(0,1fr); gap: 16px; padding: 8px 0; }
.capacity-row dt { font-weight: 600; }
.capacity-row dd { margin: 0; overflow-wrap: anywhere; }
.capacity-info .capacity-row { display: block; padding: 0; margin-bottom: 16px; }
.capacity-info dt { margin-bottom: 6px; }
@media(max-width:600px) { .capacity-row { grid-template-columns:120px minmax(0,1fr); } }
</style>
