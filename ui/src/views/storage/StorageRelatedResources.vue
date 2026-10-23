<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <section class="storage-related-resources">
    <div class="detail-tab-toolbar">
      <a-button :loading="pending" :disabled="!canRead || loading" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search class="storage-related-search" v-model:value="draftKeyword" :placeholder="$t('label.search')" :aria-label="$t('label.search')" :disabled="!canRead" allow-clear @search="search" />
    </div>
    <a-alert v-if="error" type="error" show-icon :message="$t(loadedScope ? 'message.list.refresh.stale' : 'error.fetching.data')"><template #description><a-button :loading="pending" @click="fetchData">{{ $t('label.retry') }}</a-button></template></a-alert>
    <ListView :resource-path="definition.path" :items="rows" :columns="columns" :actions="[]" :loading="pending && !loadedScope" />
    <a-pagination
      class="detail-tab-pagination"
      :current="page"
      :pageSize="pagesize"
      :total="count"
      :showTotal="total => $t('label.total') + ' ' + total + ' ' + $t('label.items')"
      :pageSizeOptions="['10', '20', '50', '100']"
      show-size-changer
      @change="changePage" />
  </section>
</template>
<script>
import { getAPI } from '@/utils/storageApi'
import ListView from '@/components/view/ListView'
import { listRefreshMixin } from '@/utils/listRefreshMixin'

const definitions = {
  computeoffering: { tab: 'instances', api: 'listVirtualMachines', response: 'listvirtualmachinesresponse', rows: 'virtualmachine', param: 'serviceofferingid', path: '/vm', fields: ['name', 'instancename', 'state', 'account', 'project', 'zonename'] },
  diskoffering: { tab: 'volumes', api: 'listVolumes', response: 'listvolumesresponse', rows: 'volume', param: 'diskofferingid', path: '/volume', fields: ['name', 'state', 'size', 'type', 'vmname', 'zonename'] },
  volume: { tab: 'snapshots', api: 'listSnapshots', response: 'listsnapshotsresponse', rows: 'snapshot', param: 'volumeid', path: '/snapshot', fields: ['name', 'state', 'intervaltype', 'physicalsize', 'created'] }
}
export default {
  name: 'StorageRelatedResources',
  components: { ListView },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => vm.active })],
  props: { resource: { type: Object, required: true }, tab: { type: String, default: '' }, loading: Boolean },
  data () { return { version: 0, disposed: false, rows: [], count: 0, pending: false, error: false, loadedScope: '', draftKeyword: '' } },
  computed: {
    definition () { return definitions[this.$route.meta?.name] || definitions.volume },
    active () { return this.tab === this.definition.tab },
    canRead () { return this.definition.api in this.$store.getters.apis },
    page () { const value = Number(this.$route.query.relatedpage); return Number.isSafeInteger(value) && value > 0 ? value : 1 },
    pagesize () { const value = Number(this.$route.query.relatedpagesize); return [10, 20, 50, 100].includes(value) ? value : 20 },
    keyword () { return String(this.$route.query.relatedkeyword || '') },
    ownerScope () { return JSON.stringify([this.$route.path, this.resource.id, this.$store.getters.userInfo?.id, this.$store.getters.project?.id, this.$store.state?.user?.token, this.canRead]) },
    queryScope () { return JSON.stringify([this.ownerScope, this.keyword, this.page, this.pagesize]) },
    columns () { return this.definition.fields.map(key => ({ key, dataIndex: key, title: this.$t('label.' + (key === 'name' && this.definition.path === '/vm' ? 'vm.displayname' : key)) })) }
  },
  watch: {
    queryScope () { this.draftKeyword = this.keyword; this.reset(); this.fetchData() },
    active (active) { this.version++; this.pending = false; if (active) this.fetchData() },
    loading (value) { if (!value) this.fetchData() }
  },
  created () { this.draftKeyword = this.keyword; this.fetchData() },
  beforeUnmount () { this.disposed = true; this.version++ },
  methods: {
    reset () { this.version++; this.rows = []; this.count = 0; this.pending = false; this.error = false; this.loadedScope = '' },
    saveQuery (values) {
      const query = { ...this.$route.query, tab: this.definition.tab, ...values }
      Object.keys(query).forEach(key => { if (query[key] === '' || query[key] == null) delete query[key] })
      return this.$router.push({ path: this.$route.path, query })
    },
    search (keyword) { this.saveQuery({ relatedkeyword: keyword, relatedpage: '1' }) },
    changePage (page, pagesize) { this.saveQuery({ relatedpage: String(pagesize === this.pagesize ? page : 1), relatedpagesize: String(pagesize) }) },
    async fetchData () {
      if (!this.active || this.disposed || this.loading || this.pending) return
      if (!this.resource.id || !this.canRead) { this.reset(); return }
      const version = ++this.version; const scope = this.queryScope
      const current = () => !this.disposed && this.active && version === this.version && scope === this.queryScope
      this.pending = true
      try {
        const params = { [this.definition.param]: this.resource.id, listall: true, page: this.page, pagesize: this.pagesize }
        if (this.keyword) params.keyword = this.keyword
        if (this.resource.projectid) params.projectid = this.resource.projectid
        const result = (await getAPI(this.definition.api, params, { preserveOnFailure: true, timeout: 20000 }))?.[this.definition.response]
        if (!current()) return
        if (!result) throw new Error('Missing response')
        const count = Number(result.count || 0)
        const last = Math.max(1, Math.ceil(count / this.pagesize))
        if (this.page > last) { this.changePage(last, this.pagesize); return }
        this.rows = (result[this.definition.rows] || []).map(row => ({ ...row, name: row.displayname || row.name }))
        this.count = count; this.error = false; this.loadedScope = scope
      } catch (error) {
        if (!current()) return
        this.error = true
        const code = Number(error?.response?.data?.errorresponse?.errorcode || error?.response?.status)
        if ([401, 403, 431, 432, 531].includes(code)) { this.rows = []; this.count = 0; this.loadedScope = '' }
      } finally { if (current()) this.pending = false }
    }
  }
}
</script>
<style scoped>
.storage-related-search { margin-left: auto; max-width: 320px; width: 100%; }
@media (max-width: 600px) { .storage-related-search { max-width: none; } }
</style>
