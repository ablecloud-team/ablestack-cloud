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
  <section class="cni-usage" :aria-label="$t('label.cni.usage')">
    <div class="detail-tab-toolbar usage-toolbar">
      <a-button :loading="pending" :disabled="capability !== 'ready' || !resourceActionReady()" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search v-model:value="draftKeyword" class="usage-search" :disabled="capability !== 'ready'" :placeholder="$t('label.search')" :aria-label="$t('label.search')" allow-clear @search="search" />
    </div>
    <a-alert v-if="['error', 'forbidden', 'unsupported', 'mismatch', 'missing'].includes(status)" class="usage-feedback" type="error" show-icon role="status" :message="$t('message.cni.usage.' + status)">
      <template #description><span v-if="rows.length">{{ $t('message.cni.usage.stale') }}</span><a-button v-if="capability === 'ready'" type="link" :loading="pending" @click="fetchData">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-alert v-if="!canReadNodes && rows.length" class="usage-feedback" type="info" show-icon :message="$t('message.cni.usage.nodes.forbidden')" />
    <div class="usage-table">
      <a-table
        size="small"
        :scroll="{ x: 1100 }"
        :columns="columns"
        :dataSource="rows"
        rowKey="id"
        :pagination="false"
        :loading="tableLoading"
        :locale="{ emptyText: $t('message.cni.usage.' + (status === 'ready' ? 'empty' : status)) }"
        :expandedRowKeys="expandedKeys"
        :rowExpandable="() => canReadNodes"
        @expand="expand">
        <template #bodyCell="{ column, record, text }">
          <router-link v-if="column.key === 'name'" :title="text" :to="resourceLink('/kubernetes', record.id)">{{ text }}</router-link>
          <Status v-else-if="column.key === 'state'" :text="text" displayText />
          <span v-else-if="column.key === 'owner'">{{ record.project || record.account || '—' }}</span>
          <span v-else-if="column.key === 'nodes'">{{ canReadNodes ? record.virtualmachines.length : $t('label.cni.usage.nodes.restricted') }}</span>
          <span v-else>{{ text }}</span>
        </template>
        <template #expandedRowRender="{ record }">
          <section v-if="canReadNodes" class="usage-nodes" :aria-label="$t('message.cni.usage.nodes.title', [record.name])">
            <a-table size="small" :scroll="{ x: 800 }" :columns="nodeColumns" :dataSource="record.virtualmachines" rowKey="id" :pagination="false" :locale="{ emptyText: $t('message.cni.usage.nodes.empty') }">
              <template #bodyCell="{ column, record: node, text }">
                <router-link v-if="column.key === 'name'" :title="text" :to="resourceLink('/vm', node.id)">{{ text }}</router-link>
                <Status v-else-if="column.key === 'state'" :text="text" displayText />
                <span v-else-if="column.key === 'role'">{{ $t('label.cni.usage.role.' + (node.iscontrolnode ? 'control' : node.isetcdnode ? 'etcd' : 'worker')) }}</span>
                <span v-else>{{ text }}</span>
              </template>
            </a-table>
          </section>
        </template>
      </a-table>
    </div>
    <div v-if="status === 'ready' || rows.length" class="detail-tab-pagination usage-pagination">
      <a-pagination :current="page" :pageSize="pagesize" :total="count" :showTotal="total => $t('message.cni.usage.total', [total])" show-size-changer :pageSizeOptions="['10', '20', '50', '100']" @change="changePage" />
    </div>
  </section>
</template>
<script>
import { getAPI } from '@/api'
import Status from '@/components/widgets/Status'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { cniIdentity, cniIdentityParams, cniUsageCapability, cniUsageParams, cniUsageRows, cniUsageError } from '@/utils/cniUsage'

const tabName = 'cni.usage'
export default {
  name: 'CniUsage',
  components: { Status },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => vm.active })],
  props: { resource: { type: Object, required: true }, tab: { type: String, default: tabName }, loading: Boolean },
  inject: { resourceActionReady: { default: () => () => true } },
  data () { return { disposed: false, version: 0, rows: [], count: 0, status: 'loading', pending: false, loadedScope: '', keyword: '', draftKeyword: '', page: 1, pagesize: 20, expandedKeys: [] } },
  computed: {
    active () { return this.tab === tabName && this.$route.path.toLowerCase() === '/cniconfiguration/' + (this.resource.id || '').toLowerCase() },
    capability () { return cniUsageCapability(this.$store.getters.apis) },
    canReadNodes () { return !!this.$store.getters.apis?.listVirtualMachines },
    scope () { return JSON.stringify([this.resource.id, this.resource.name, this.resource.account, this.resource.domainid, this.resource.projectid, this.$route.path, this.$store.getters.project?.id, this.$store.getters.listAllProjects, this.$store.getters.userInfo?.id, this.$store.state?.user?.token, this.capability, this.canReadNodes]) },
    queryKey () { return JSON.stringify(['cnikeyword', 'cnipage', 'cnipagesize'].map(key => this.$route.query[key])) },
    queryScope () { return JSON.stringify([this.scope, this.keyword, this.page, this.pagesize]) },
    tableLoading () { return this.pending && this.loadedScope !== this.queryScope },
    columns () { return ['name', 'id', 'state', 'owner', 'domain', 'nodes'].map(key => ({ key, dataIndex: key, width: { name: 220, id: 320, state: 120, owner: 180, domain: 180, nodes: 80 }[key], title: this.$t(key === 'owner' ? (this.resource.projectid ? 'label.project' : 'label.account') : key === 'nodes' ? 'label.cni.usage.nodes' : 'label.' + key) })) },
    nodeColumns () { return ['name', 'id', 'state', 'role'].map(key => ({ key, dataIndex: key, width: { name: 220, id: 320, state: 120, role: 140 }[key], title: this.$t(key === 'name' ? 'label.vm.displayname' : 'label.' + key) })) }
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
      const q = this.$route.query
      this.keyword = typeof q.cnikeyword === 'string' ? q.cnikeyword : ''; this.draftKeyword = this.keyword
      this.page = Number.isSafeInteger(Number(q.cnipage)) && Number(q.cnipage) > 0 ? Number(q.cnipage) : 1
      this.pagesize = [10, 20, 50, 100].includes(Number(q.cnipagesize)) ? Number(q.cnipagesize) : 20
    },
    saveQuery (values) {
      const query = { ...this.$route.query, tab: tabName, ...values }
      Object.keys(query).forEach(key => { if (query[key] === '' || query[key] == null) delete query[key] })
      return this.$router.push({ path: this.$route.path, query })
    },
    search (value) { this.saveQuery({ cnikeyword: value, cnipage: '1' }) },
    changePage (page, pagesize) { this.saveQuery({ cnipage: String(pagesize === this.pagesize ? page : 1), cnipagesize: String(pagesize) }) },
    resourceLink (path, id) { return { path: path + '/' + id, ...(this.resource.projectid ? { query: { projectid: this.resource.projectid } } : {}) } },
    expand (expanded, record) { this.expandedKeys = expanded && this.canReadNodes ? [record.id] : [] },
    reset () { this.version++; this.rows = []; this.count = 0; this.expandedKeys = []; this.status = 'loading'; this.pending = false; this.loadedScope = '' },
    async fetchData () {
      if (!this.active || this.disposed) return
      if (this.capability !== 'ready') { this.reset(); this.status = this.capability; return }
      if (!this.resourceActionReady()) return
      const source = Object.freeze(Object.fromEntries(['id', 'name', 'account', 'domainid', 'projectid'].map(key => [key, this.resource[key]])))
      const lookup = cniIdentityParams(source)
      const project = this.$store.getters.project?.id
      if (!lookup || (project && project !== '-1' && project !== source.projectid)) { this.reset(); this.status = 'mismatch'; return }
      const version = ++this.version; const scope = this.queryScope
      const current = () => !this.disposed && this.active && version === this.version && scope === this.queryScope
      if (this.loadedScope !== scope) { this.rows = []; this.count = 0; this.expandedKeys = []; this.status = 'loading' }
      this.pending = true
      const options = { preserveOnFailure: true, timeout: 20000 }
      try {
        let identity = cniIdentity(await getAPI('listCniConfiguration', lookup, options), source)
        if (!current()) return
        if (identity !== 'ready') throw Object.assign(new Error('CNI identity changed'), { usageState: identity })
        const result = cniUsageRows(await getAPI('listKubernetesClusters', cniUsageParams(source, { keyword: this.keyword, page: this.page, pagesize: this.pagesize }), options), source)
        if (!current()) return
        identity = cniIdentity(await getAPI('listCniConfiguration', lookup, options), source)
        if (!current()) return
        if (identity !== 'ready') throw Object.assign(new Error('CNI identity changed'), { usageState: identity })
        const last = Math.max(1, Math.ceil(result.count / this.pagesize))
        if (this.page > last) { this.changePage(last, this.pagesize); return }
        this.rows = result.rows; this.count = result.count; this.status = 'ready'; this.loadedScope = scope
        this.expandedKeys = this.expandedKeys.filter(id => this.rows.some(row => row.id === id))
      } catch (error) {
        if (!current()) return
        this.status = cniUsageError(error)
        if (this.status !== 'error') { this.rows = []; this.count = 0; this.expandedKeys = []; this.loadedScope = '' }
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

.cni-usage { min-width: 0; }
.usage-search { max-width: 100%; margin-left: auto; }
.usage-feedback { margin-bottom: 12px; }
.usage-table { min-width: 0; :deep(.ant-table-cell) { overflow-wrap: anywhere; } }
.usage-nodes { min-width: 0; }
.usage-pagination { display: flex; justify-content: flex-end; flex-wrap: wrap; margin-top: 20px; :deep(.ant-pagination) { display: flex; flex-wrap: wrap; gap: 8px 0; } }
@media (max-width: 768px) { .usage-search { flex-basis: 100%; width: 100%; } }
</style>
