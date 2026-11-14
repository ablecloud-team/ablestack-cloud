// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div v-if="eligible" class="catalog-summary">
    <div class="template-tab-toolbar catalog-toolbar">
      <router-link to="/runtimebundle"><a-button type="primary">{{ $t('label.runtime.catalog') }}</a-button></router-link>
      <a-button :loading="busy" @click="load"><ReloadOutlined />{{ $t('label.refresh') }}</a-button>
    </div>
    <p class="catalog-scope"><a-tooltip :title="$t('message.runtime.catalog.context.unknown')"><info-circle-outlined /> {{ $t('message.template.runtime.scope') }}</a-tooltip></p>
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-table :columns="columns" :data-source="rows" row-key="id" size="small" :pagination="false" :scroll="{ x: 650 }">
      <template #bodyCell="{ column, record }">
        <router-link v-if="column.key === 'version'" :to="'/runtimebundle/' + record.id">{{ record.version }}</router-link>
      </template>
    </a-table>
    <div class="template-tab-pagination"><a-pagination :current="page" :page-size="10" :total="total" @change="changePage" /></div>
  </div>
</template>
<script>
import { getAPI } from '@/api'
import { isStorageServiceTemplate, canReadTemplateRuntimeCatalog } from '@/utils/templateDetails'
import { ReloadOutlined } from '@ant-design/icons-vue'
export default {
  name: 'RuntimeBundleCatalogSummary',
  components: { ReloadOutlined },
  props: { resource: { type: Object, default: () => ({}) } },
  data () { return { rows: [], page: 1, total: 0, busy: false, error: '', epoch: 0, active: true } },
  computed: {
    eligible () { return canReadTemplateRuntimeCatalog(this.$store?.getters) && (this.$route?.meta?.name !== 'template' || isStorageServiceTemplate(this.resource)) },
    scope () { const g = this.$store?.getters || {}; return JSON.stringify([this.$route?.path, this.resource.id, this.resource.templatetype, g.userInfo?.id, g.userInfo?.roletype, g.project?.id, this.$store?.state?.user?.token, Object.keys(g.apis || {}).sort()]) },
    columns () { return ['version', 'state', 'validationstatus', 'profileid'].map(key => ({ key, dataIndex: key, title: this.$t('label.' + key), width: 180 })) }
  },
  watch: { scope () { this.epoch++; this.rows = []; this.total = 0; this.page = 1; this.error = ''; this.busy = false; if (this.active) this.load() } },
  created () { this.load() },
  activated () { if (!this.active) { this.active = true; this.load() } },
  deactivated () { this.active = false; this.epoch++; this.rows = []; this.total = 0; this.busy = false },
  beforeUnmount () { this.epoch++ },
  methods: {
    changePage (page) { this.page = page; this.load() },
    async load () {
      if (!this.eligible || !this.active) return
      const epoch = ++this.epoch
      const scope = this.scope
      this.busy = true; this.error = ''
      try {
        const data = (await getAPI('listStorageServiceRuntimeBundles', { purpose: 'catalog', page: this.page, pagesize: 10 })).liststorageserviceruntimebundlesresponse
        if (epoch === this.epoch && scope === this.scope && this.eligible && this.active) { this.rows = data?.storageserviceruntimebundle || []; this.total = data?.count || 0 }
      } catch (e) { if (epoch === this.epoch && scope === this.scope && this.eligible && this.active) { this.error = this.$t('error.fetching.data'); this.$notifyError(e) } } finally { if (epoch === this.epoch && scope === this.scope && this.eligible && this.active) this.busy = false }
    }
  }
}
</script>
<style scoped lang="less">
.catalog-summary { min-width: 0; overflow-wrap: anywhere; }
.catalog-toolbar { display:flex; margin-bottom:16px !important; }
.ant-alert { margin-bottom:16px; }
.ant-pagination { margin-top:16px; }
</style>
