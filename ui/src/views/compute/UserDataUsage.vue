// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <section class="userdata-usage" :aria-label="$t('label.userdata.usage')">
    <div class="usage-toolbar detail-tab-toolbar">
      <a-button :loading="pending" :disabled="!canRead || !resourceActionReady()" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search class="usage-search" v-model:value="draftKeyword" :disabled="!canRead" :placeholder="$t('label.search')" :aria-label="$t('label.search')" allow-clear @search="search" />
    </div>
    <a-alert v-if="['error', 'forbidden', 'mismatch', 'missing', 'unsupported'].includes(status)" class="usage-feedback" type="error" show-icon role="status" :message="$t('message.userdata.usage.' + status)">
      <template #description><span v-if="rows.length">{{ $t('message.userdata.usage.stale') }}</span><a-button v-if="canRead" type="link" :loading="pending" @click="fetchData">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <div class="usage-table">
      <ListView
detail-tab
:resource-path="config.path"
:columns="columns"
:items="rows"
:loading="tableLoading"
:actions="[]"
:emptyText="$t('message.userdata.usage.' + (status === 'ready' ? 'empty' : status))"
@table-change="sort" />
    </div>
    <div v-if="status === 'ready' || rows.length" class="usage-pagination detail-tab-pagination">
      <detail-tab-pagination :current="page" :pageSize="pagesize" :total="count" :showTotal="total => $t('message.userdata.usage.total', [total])" show-size-changer :pageSizeOptions="['10', '20', '50', '100']" @change="changePage" />
    </div>
  </section>
</template>
<script>
import { getAPI } from '@/api'
import ListView from '@/components/view/ListView'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { userDataUsageParams, userDataUsageRows, userDataUsageKinds, userDataUsageSupported } from '@/utils/userDataUsage'
import { userDataDeleteLookup, userDataDeleteIdentity, userDataDeleteError } from '@/utils/userDataDeletion'

const tabPrefix = 'userdata.usage.'
export default {
  name: 'UserDataUsage',
  components: { ListView },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => vm.active })],
  props: { resource: { type: Object, required: true }, tab: { type: String, default: '' }, resourceType: { type: String, required: true }, loading: Boolean },
  inject: { resourceActionReady: { default: () => () => true } },
  data () { return { disposed: false, version: 0, rows: [], count: 0, status: 'loading', pending: false, loadedScope: '', keyword: '', draftKeyword: '', page: 1, pagesize: 20, sortkey: '', sortorder: '' } },
  computed: {
    kind () { return this.resourceType },
    config () { return userDataUsageKinds[this.kind] },
    tabName () { return tabPrefix + this.kind },
    active () { return this.tab === this.tabName },
    tableLoading () { return this.pending && this.loadedScope !== this.queryScope },
    canRead () { return userDataUsageSupported(this.$store.getters.apis, this.kind) },
    scope () { return JSON.stringify([this.kind, this.resource.id, this.resource.name, this.resource.account, this.resource.domainid, this.resource.projectid, this.$route.path, this.$store.getters.project?.id, this.$store.getters.listAllProjects, this.$store.getters.userInfo?.id, this.$store.state?.user?.token, this.canRead]) },
    queryKey () { return JSON.stringify(['keyword', 'page', 'pagesize', 'sort', 'order'].map(key => this.$route.query[this.kind + key])) },
    queryScope () { return JSON.stringify([this.scope, this.keyword, this.page, this.pagesize]) },
    columns () {
      return ['name', 'id', ...(this.kind === 'vms' ? ['state'] : ['userdatapolicy']), 'account', 'project', 'domain'].map(key => ({ key, dataIndex: key, title: this.$t(key === 'name' && this.kind === 'vms' ? 'label.vm.displayname' : 'label.' + key), sorter: (a, b) => String(a[key] || '').localeCompare(String(b[key] || '')), sortOrder: this.sortkey === key ? (this.sortorder === 'asc' ? 'ascend' : 'descend') : null }))
    }
  },
  watch: {
    scope () { this.reset(); this.fetchData() },
    queryKey () { this.readQuery(); this.reset(); this.fetchData() },
    active (active) { this.version++; this.pending = false; if (active) this.fetchData() },
    loading (loading) { if (!loading) this.fetchData() }
  },
  created () { this.readQuery(); this.fetchData() },
  beforeUnmount () { this.disposed = true; this.version++ },
  methods: {
    readQuery () {
      const q = Object.fromEntries(['keyword', 'page', 'pagesize', 'sort', 'order'].map(key => ['usage' + key, this.$route.query[this.kind + key]]))
      this.keyword = typeof q.usagekeyword === 'string' ? q.usagekeyword : ''; this.draftKeyword = this.keyword
      this.page = Number.isSafeInteger(Number(q.usagepage)) && Number(q.usagepage) > 0 ? Number(q.usagepage) : 1
      this.pagesize = [10, 20, 50, 100].includes(Number(q.usagepagesize)) ? Number(q.usagepagesize) : 20
      this.sortkey = ['name', 'id', 'state', 'userdatapolicy', 'account', 'project', 'domain'].includes(q.usagesort) ? q.usagesort : ''; this.sortorder = ['asc', 'desc'].includes(q.usageorder) ? q.usageorder : ''
    },
    saveQuery (values) {
      const query = { ...this.$route.query, tab: this.tabName, ...Object.fromEntries(Object.entries(values).map(([key, value]) => [this.kind + key.replace('usage', ''), value])) }
      Object.keys(query).forEach(key => { if (query[key] === '' || query[key] == null) delete query[key] })
      return this.$router.push({ path: this.$route.path, query })
    },
    search (value) { this.saveQuery({ usagekeyword: value, usagepage: '1' }) },
    changePage (page, pagesize) { this.saveQuery({ usagepage: String(pagesize === this.pagesize ? page : 1), usagepagesize: String(pagesize) }) },
    sort (_, __, sorter) { this.saveQuery({ usagesort: sorter.order ? sorter.field : undefined, usageorder: sorter.order ? (sorter.order === 'ascend' ? 'asc' : 'desc') : undefined }) },
    reset () { this.version++; this.rows = []; this.count = 0; this.status = 'loading'; this.pending = false; this.loadedScope = '' },
    async fetchData () {
      if (!this.active || this.disposed) return
      if (!this.canRead) { this.reset(); this.status = 'unsupported'; return }
      if (!this.resourceActionReady()) return
      const source = Object.freeze({ ...this.resource }); const lookup = userDataDeleteLookup(source)
      if (!lookup) { this.reset(); this.status = 'mismatch'; return }
      const version = ++this.version; const scope = this.queryScope
      const current = () => !this.disposed && this.active && version === this.version && scope === this.queryScope
      if (this.loadedScope !== scope) { this.rows = []; this.count = 0; this.status = 'loading' }
      this.pending = true
      const options = { preserveOnFailure: true, timeout: 20000 }
      try {
        let identity = userDataDeleteIdentity(await getAPI('listUserData', lookup, options), source)
        if (!current()) return
        if (identity !== 'ready') throw Object.assign(new Error('Key identity changed'), { usageState: identity })
        const result = userDataUsageRows(await getAPI(this.config.api, userDataUsageParams(source, this.kind, this.$store.getters.project, this.$store.getters.userInfo.roletype, { keyword: this.keyword || undefined, page: this.page, pagesize: this.pagesize }), options), source, this.kind)
        if (!current()) return
        identity = userDataDeleteIdentity(await getAPI('listUserData', lookup, options), source)
        if (!current()) return
        if (identity !== 'ready') throw Object.assign(new Error('Key identity changed'), { usageState: identity })
        const last = Math.max(1, Math.ceil(result.count / this.pagesize))
        if (this.page > last) { this.changePage(last, this.pagesize); return }
        this.rows = result.rows; this.count = result.count; this.status = 'ready'; this.loadedScope = scope
      } catch (error) {
        if (!current()) return
        const code = userDataDeleteError(error).code
        const state = error.usageState || ([401, 403, 432, 531].includes(code) ? 'forbidden' : code === 431 ? 'missing' : 'error')
        this.status = ['forbidden', 'mismatch', 'missing'].includes(state) ? state : 'error'
        if (this.status !== 'error') { this.rows = []; this.count = 0; this.loadedScope = '' }
      } finally { if (current()) this.pending = false }
    }
  }
}
</script>
<style lang="less" scoped>
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

.userdata-usage { min-width: 0; }
.usage-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 20px;
  .ant-btn, :deep(.ant-input), :deep(.ant-input-search-button) { height: 32px; }
}
.usage-search { width: 240px; max-width: 100%; margin-left: auto; }
.usage-feedback { margin: 20px 0; }
.usage-table { margin-top: 20px; overflow-x: auto; :deep(.list-view-table-wrapper) { min-width: 640px; } }
.usage-pagination { display: flex; justify-content: flex-end; flex-wrap: wrap; margin-top: 20px; :deep(.ant-pagination) { display: flex; flex-wrap: wrap; gap: 8px 0; } }
@media (max-width: 767px) { .usage-search { flex-basis: 100%; width: 100%; } }
</style>
