<!-- Licensed under the Apache License, Version 2.0. See NOTICE. -->
<template>
  <div class="sharedfs-capacity-tab">
    <a-space class="capacity-toolbar" wrap>
      <a-button :loading="loading" @click="$emit('refresh')"><ReloadOutlined />{{ $t('label.refresh') }}</a-button>
      <a-button @click="dialog = true"><InfoCircleOutlined />{{ $t('label.storage.capacity.rules') }}</a-button>
    </a-space>
    <SharedFSCapacitySummary :resource="resource" />
    <a-alert class="capacity-section" type="info" show-icon :message="$t('message.storage.capacity.coverage')" :description="$t('message.storage.capacity.unknown')" />
    <div class="capacity-section capacity-search"><a-input-search v-model:value="search" :placeholder="$t('label.storage.capacity.search')" allow-clear /><span>{{ $t('message.storage.capacity.exclusions') }}</span></div>
    <a-table class="capacity-section" row-key="id" size="small" :columns="columns" :data-source="filtered" :pagination="{ pageSize: 10 }" :scroll="{ x: 1160 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">{{ record.name || record.id }}<div class="capacity-secondary">{{ record.id }}</div><div v-if="record.hidden" class="capacity-secondary">{{ $t('label.storage.capacity.hidden') }}</div></template>
        <template v-else-if="column.key === 'size'">{{ bytes(record.size) }}</template>
        <template v-else-if="column.key === 'usage'">{{ bytes(record.usedbytes) }}<div class="capacity-secondary">{{ record.filesystem || status(record.status) }}</div></template>
        <template v-else-if="column.key === 'state'">{{ record.state }}<div class="capacity-secondary">{{ status(record.status) }}</div></template>
        <template v-else-if="column.key === 'references'">NFS {{ record.nfsreferences ?? '—' }} · SMB {{ record.smbreferences ?? '—' }} · Block {{ record.blockreferences ?? '—' }}</template>
        <template v-else-if="column.key === 'observedat'">{{ record.observedat || '—' }}</template>
      </template>
    </a-table>
    <MoldDialog v-if="dialog" size="md" :title="$t('label.storage.capacity.rules')" @cancel="dialog = false">
      <a-alert type="info" show-icon :message="$t('message.storage.capacity.coverage')" />
      <ul class="capacity-rules"><li v-for="key in ruleKeys" :key="key">{{ $t(key) }}</li></ul>
      <template #footer><a-button @click="dialog = false">{{ $t('label.close') }}</a-button></template>
    </MoldDialog>
  </div>
</template>
<script>
import { ReloadOutlined, InfoCircleOutlined } from '@ant-design/icons-vue'
import MoldDialog from '@/components/view/MoldDialog.vue'
import SharedFSCapacitySummary from './SharedFSCapacitySummary.vue'
export default {
  name: 'SharedFSCapacityTab',
  components: { ReloadOutlined, InfoCircleOutlined, MoldDialog, SharedFSCapacitySummary },
  props: { resource: { type: Object, required: true }, loading: { type: Boolean, default: false } },
  emits: ['refresh'],
  data () { return { search: '', dialog: false } },
  computed: {
    filtered () { return (this.resource.capacity?.volumes || []).filter(row => String(row.name || row.id).toLowerCase().includes(this.search.toLowerCase())) },
    columns () {
      return [['name', 'label.name', 260], ['size', 'label.storage.capacity.provisioned', 130], ['usage', 'label.storage.capacity.observed.usage', 170], ['state', 'label.state', 160], ['references', 'label.storage.capacity.references', 260], ['observedat', 'label.storage.capacity.observed.at', 200]]
        .map(([key, title, width]) => ({ key, title: this.$t(title), width }))
    },
    ruleKeys () { return ['message.storage.capacity.exclusions', 'message.storage.capacity.references', 'message.storage.capacity.aliases', 'message.storage.capacity.raw', 'message.storage.capacity.unknown', 'message.storage.capacity.hidden'] }
  },
  methods: {
    bytes (value) { return value === null || value === undefined ? '—' : this.$bytesToHumanReadableSize(value) },
    status (value) { return this.$t('label.storage.capacity.status.' + String(value || 'UNAVAILABLE').toLowerCase()) }
  }
}
</script>
<style scoped>
.sharedfs-capacity-tab { color: var(--ui-text-primary); }
.capacity-toolbar { margin-bottom: 16px; }
.capacity-section { margin-top: 16px; }
.capacity-search { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.capacity-search .ant-input-search { max-width: 300px; }
.capacity-secondary, .capacity-search > span { color: var(--ui-text-secondary); font-size: 12px; overflow-wrap: anywhere; }
.capacity-rules { padding-left: 24px; margin: 16px 0 0; }
.capacity-rules li { margin: 16px 0; line-height: 1.8; }
</style>
