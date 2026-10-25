<!-- Licensed under the Apache License, Version 2.0. See the NOTICE file. -->
<template>
  <div class="catalog-summary">
    <a-space class="catalog-toolbar" wrap>
      <router-link to="/runtimebundle"><a-button type="primary">{{ $t('label.runtime.catalog') }}</a-button></router-link>
      <a-button :loading="busy" @click="load"><ReloadOutlined />{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert type="info" show-icon :message="$t('message.runtime.catalog.shared')" :description="$t('message.runtime.catalog.context.unknown')" />
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-table :columns="columns" :data-source="rows" row-key="id" size="small" :pagination="false" :scroll="{ x: 650 }">
      <template #bodyCell="{ column, record }">
        <router-link v-if="column.key === 'version'" :to="'/runtimebundle/' + record.id">{{ record.version }}</router-link>
      </template>
    </a-table>
    <a-pagination :current="page" :page-size="10" :total="total" @change="changePage" />
  </div>
</template>
<script>
import { getAPI } from '@/api'
import { ReloadOutlined } from '@ant-design/icons-vue'
export default {
  name: 'RuntimeBundleCatalogSummary',
  components: { ReloadOutlined },
  props: { resource: { type: Object, default: () => ({}) } },
  data () { return { rows: [], page: 1, total: 0, busy: false, error: '', epoch: 0 } },
  computed: { columns () { return ['version', 'state', 'validationstatus', 'profileid'].map(key => ({ key, dataIndex: key, title: this.$t('label.' + key), width: 180 })) } },
  created () { this.load() },
  beforeUnmount () { this.epoch++ },
  methods: {
    changePage (page) { this.page = page; this.load() },
    async load () {
      if (this.busy) return
      const epoch = this.epoch
      this.busy = true; this.error = ''
      try {
        const data = (await getAPI('listStorageServiceRuntimeBundles', { purpose: 'catalog', page: this.page, pagesize: 10 })).liststorageserviceruntimebundlesresponse
        if (epoch === this.epoch) { this.rows = data?.storageserviceruntimebundle || []; this.total = data?.count || 0 }
      } catch (e) { if (epoch === this.epoch) { this.error = this.$t('error.fetching.data'); this.$notifyError(e) } } finally { if (epoch === this.epoch) this.busy = false }
    }
  }
}
</script>
<style scoped lang="less">
.catalog-toolbar { display:flex; margin-bottom:16px !important; }
.ant-alert { margin-bottom:16px; }
.ant-pagination { margin-top:16px; }
</style>
