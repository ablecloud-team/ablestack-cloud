<!-- Licensed under the Apache License, Version 2.0. See the NOTICE file. -->
<template>
  <div class="runtime-catalog-records">
    <a-space class="catalog-toolbar" wrap>
      <a-button :loading="busy" @click="load"><ReloadOutlined />{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-alert v-if="tab === 'runtime.usages'" :type="total ? 'warning' : 'info'" show-icon :message="$t(total ? 'message.runtime.catalog.used' : 'message.runtime.catalog.unused')" />
    <a-table :columns="columns" :data-source="rows" row-key="id" :loading="busy" :pagination="false" :scroll="{ x: 700 }" size="small">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'"><a-tag>{{ record.state }}</a-tag></template>
        <template v-else-if="column.key === 'target'">
          <router-link v-if="record.sharedfilesystemid" :to="'/sharedfs/' + record.sharedfilesystemid + '?tab=runtime'">{{ record.name }}</router-link>
          <router-link v-else-if="record.vmid" :to="'/vm/' + record.vmid">{{ record.name }}</router-link><span v-else>{{ record.name }}</span>
        </template>
        <template v-else-if="column.key === 'view'"><a-button size="small" @click="detail = record">{{ $t('label.details') }}</a-button></template>
      </template>
    </a-table>
    <a-pagination v-if="total > 0" :current="page" :page-size="pagesize" :total="total" show-size-changer @change="changePage" @show-size-change="changePage" />
    <MoldDialog v-if="detail" :visible="true" :title="$t('label.details')" size="lg" @cancel="detail = null">
      <a-descriptions bordered size="small" :column="1">
        <a-descriptions-item v-for="field in ['requestid','state','phase','compatibility','errorcode','errormessage','reason','created','completed']" :key="field" :label="$t('label.' + field)">{{ detail[field] || '-' }}</a-descriptions-item>
      </a-descriptions>
      <pre>{{ pretty(detail.detailsjson) }}</pre>
    </MoldDialog>
  </div>
</template>
<script>
import { getAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
import MoldDialog from '@/components/view/MoldDialog'
export default {
  name: 'RuntimeCatalogRecords',
  components: { MoldDialog, ReloadOutlined },
  props: { resource: { type: Object, required: true }, tab: { type: String, required: true } },
  data () { return { rows: [], total: 0, page: 1, pagesize: 10, busy: false, error: '', epoch: 0, detail: null } },
  computed: {
    columns () {
      const names = this.tab === 'runtime.usages' ? ['type', 'target', 'state', 'created'] : this.tab === 'runtime.events' ? ['created', 'action', 'state', 'reason', 'revision', 'view'] : ['created', 'state', 'compatibility', 'errorcode', 'view']
      return names.map(key => ({ key, dataIndex: key, title: this.$t('label.' + (key === 'target' ? 'resource' : key === 'view' ? 'details' : key)), width: key === 'target' || key === 'reason' ? 280 : 150 }))
    }
  },
  watch: { 'resource.id' () { this.epoch++; this.busy = false; this.page = 1; this.load() }, tab () { this.epoch++; this.busy = false; this.page = 1; this.load() } },
  created () { this.load() },
  beforeUnmount () { this.epoch++ },
  methods: {
    pretty (value) { try { return JSON.stringify(JSON.parse(value || '{}'), null, 2) } catch (_) { return value } },
    changePage (page, size) { this.page = page; this.pagesize = size; this.load() },
    async load () {
      if (!this.resource.id || this.busy) return
      this.busy = true; this.error = ''
      const epoch = this.epoch
      const suffix = { 'runtime.validations': 'Validations', 'runtime.usages': 'Usages', 'runtime.events': 'Events' }[this.tab]
      const api = 'listStorageServiceRuntimeBundle' + suffix
      try {
        const response = await getAPI(api, { bundleid: this.resource.id, page: this.page, pagesize: this.pagesize })
        if (epoch !== this.epoch) return
        const data = response[api.toLowerCase() + 'response']
        this.rows = data?.storageserviceruntimecatalogrecord || []; this.total = data?.count || 0
      } catch (e) { if (epoch === this.epoch) { this.error = this.$t('error.fetching.data'); this.$notifyError(e) } } finally { if (epoch === this.epoch) this.busy = false }
    }
  }
}
</script>
<style scoped lang="less">
.catalog-toolbar { display:flex; margin-bottom:16px !important; }
.ant-alert, .ant-descriptions, .ant-pagination { margin-bottom:16px; }
.ant-pagination { margin-top:16px; }
pre { white-space:pre-wrap; overflow-wrap:anywhere; color:var(--ui-text-primary); background:var(--ui-bg-surface); padding:16px; }
</style>
