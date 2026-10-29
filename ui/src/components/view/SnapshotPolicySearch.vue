<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE for copyright ownership.
Licensed under the Apache License, Version 2.0 (the "License");
http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <span class="snapshot-search-control" @keydown.esc.stop="close(true)">
    <span class="filter-group">
      <a-input-search v-model:value="keyword" class="input-search" :placeholder="$t('label.search')" allow-clear @search="$emit('search', { searchQuery: keyword })">
        <template #addonBefore>
          <a-popover placement="bottomRight" trigger="click" :visible="visible" @visibleChange="$event ? open() : close()">
            <template #content>
              <a-form v-if="visible" class="storage-standard-search-form" :model="form" layout="vertical" @finish="apply" v-ctrl-enter="apply" @keydown.esc.stop.prevent="close(true)">
                <a-alert v-if="error" type="error" show-icon :message="$t('message.snapshot.policy.search.' + error)" />
                <a-alert v-if="projectScope !== '0'" type="info" show-icon :message="$t('message.snapshot.policy.search.project')" />
                <a-form-item :label="$t('label.search')" html-for="policy-search-keyword"><a-input id="policy-search-keyword" ref="keyword" v-model:value="form.keyword" /></a-form-item>
                <a-form-item :label="$t('label.id')" html-for="policy-search-id"><a-input id="policy-search-id" v-model:value="form.id" :placeholder="$t('label.snapshot.policy.search.id')" /></a-form-item>
                <template v-if="ownerFilters && projectScope === '0'">
                  <a-form-item v-for="field in ownerFields" :key="field.key" :label="$t('label.' + field.label)" :html-for="'policy-search-' + field.key">
                    <a-select
                      :id="'policy-search-' + field.key"
                      v-model:value="form[field.key]"
                      allow-clear
                      show-search
                      :filter-option="false"
                      :loading="candidates[field.key].loading"
                      :disabled="field.key === 'account' && !form.domainid"
                      @search="search(field, $event)"
                      @change="change(field.key)">
                      <a-select-option v-if="form[field.key] && !candidates[field.key].rows.some(row => value(field, row) === form[field.key])" :value="form[field.key]">{{ form[field.key] }}</a-select-option>
                      <a-select-option v-for="row in candidates[field.key].rows" :key="row.id" :value="value(field, row)">{{ row.path || row.name }} · {{ row.id.slice(0, 8) }}</a-select-option>
                    </a-select>
                    <template #extra>
                      <a-alert v-if="candidates[field.key].error" type="error" show-icon :message="$t('error.fetching.data')"><template #description><a-button size="small" @click="load(field, true)">{{ $t('label.retry') }}</a-button></template></a-alert>
                      <a-button v-if="candidates[field.key].seen.length < candidates[field.key].count" type="link" size="small" :disabled="candidates[field.key].loading || !!candidates[field.key].error" @click="load(field, false)">{{ $t('message.snapshot.search.more', [candidates[field.key].seen.length, candidates[field.key].count]) }}</a-button>
                    </template>
                  </a-form-item>
                  <a-form-item :label="$t('label.snapshot.search.recursive')"><a-switch :checked="form.isrecursive === 'true'" @change="form.isrecursive = $event ? 'true' : undefined; change('isrecursive')" /></a-form-item>
                </template>
                <a-form-item v-if="'listVolumes' in $store.getters.apis" :label="$t('label.volume')">
                  <VolumeSearchSelect v-model:value="form.volumeid" :scope="volumeScope" @blocked="volumeBlocked = $event" />
                </a-form-item>
                <a-form-item v-if="rootAdmin" :label="$t('label.snapshot.policy.scheduling')" html-for="policy-search-fordisplay">
                  <a-select id="policy-search-fordisplay" v-model:value="form.fordisplay" allow-clear>
                    <a-select-option value="true">{{ $t('label.snapshot.policy.schedule.enabled') }}</a-select-option>
                    <a-select-option value="false">{{ $t('label.snapshot.policy.schedule.paused') }}</a-select-option>
                  </a-select>
                </a-form-item>
                <div class="filter-group-button">
                  <a-button class="filter-group-button-clear" size="small" @click="reset"><template #icon><stop-outlined /></template>{{ $t('label.reset') }}</a-button>
                  <a-button class="filter-group-button-search" size="small" type="primary" html-type="submit" :disabled="blocked"><template #icon><search-outlined /></template>{{ $t('label.search') }}</a-button>
                </div>
              </a-form>
            </template>
            <a-button ref="trigger" class="filter-button" size="small" :aria-label="$t('label.advanced.search')" :aria-expanded="visible"><filter-two-tone v-if="filtered" /><filter-outlined v-else /></a-button>
          </a-popover>
        </template>
      </a-input-search>
    </span>
  </span>
</template>
<script>
import { getAPI } from '@/utils/storageApi'
import VolumeSearchSelect from './VolumeSearchSelect'
import { policyProjectScope, policySearchForm, policySearchError } from '@/utils/snapshotPolicySearch'
import { readSnapshotCandidatePage } from '@/utils/storageSnapshotSearch'
const empty = () => ({ rows: [], seen: [], count: 0, page: 0, keyword: '', loading: false, error: false, version: 0 })
export default {
  name: 'SnapshotPolicySearch',
  components: { VolumeSearchSelect },
  emits: ['search'],
  data () { return { visible: false, keyword: '', form: {}, candidates: { domainid: empty(), account: empty() }, timers: {}, volumeBlocked: false } },
  computed: {
    projectScope () { return policyProjectScope(this.$route, this.$store.getters) },
    rootAdmin () { return this.$store.getters.userInfo?.roletype === 'Admin' },
    ownerFilters () { return ['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo?.roletype) },
    securityScope () { return JSON.stringify([this.$route.path, this.projectScope, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roleid, this.$store.getters.userInfo?.roletype, this.$store.state?.user?.token, Object.keys(this.$store.getters.apis || {}).sort()]) },
    ownerFields () { return [{ key: 'domainid', label: 'domain', api: 'listDomains', response: 'domain' }, { key: 'account', label: 'account', api: 'listAccounts', response: 'account' }].filter(field => field.api in this.$store.getters.apis) },
    volumeScope () { return this.projectScope === '0' ? { ...Object.fromEntries(['domainid', 'account', 'isrecursive'].filter(key => this.form[key]).map(key => [key, this.form[key]])), ignoreproject: true } : { projectid: this.projectScope } },
    error () { return policySearchError({ ...Object.fromEntries(Object.entries(this.form).filter(([, v]) => v !== undefined && v !== '')), projectid: this.projectScope }, this.$store.getters) },
    filtered () { return Object.keys(policySearchForm(this.$route.query)).some(key => !['keyword', 'q'].includes(key)) },
    blocked () { return !!this.error || this.volumeBlocked || (this.ownerFilters && this.projectScope === '0' && this.ownerFields.some(field => this.candidates[field.key].loading || this.candidates[field.key].error)) }
  },
  watch: {
    '$route.query': { immediate: true, handler () { this.keyword = this.$route.query.keyword || this.$route.query.q || ''; if (this.visible) this.close() } },
    securityScope () { this.close(); this.form = {} }
  },
  beforeUnmount () { this.close() },
  methods: {
    value (field, row) { return field.key === 'account' ? row.name : row.id },
    open () { this.form = policySearchForm(this.$route.query); this.volumeBlocked = false; this.visible = true; if (this.ownerFilters && this.projectScope === '0') this.ownerFields.forEach(field => this.load(field, true, '')); this.$nextTick(() => this.$refs.keyword?.focus()) },
    close (focus = false) { this.visible = false; Object.values(this.timers).forEach(clearTimeout); this.timers = {}; Object.values(this.candidates).forEach(c => { c.version++; c.rows = []; c.seen = []; c.count = 0; c.loading = false; c.error = false }); if (focus) this.$nextTick(() => this.$refs.trigger?.$el?.focus()) },
    reset () { this.close(true); this.$emit('search', { policySearch: {} }) },
    apply () { if (this.blocked) return; const form = { ...this.form }; this.close(true); this.$emit('search', { policySearch: form }) },
    change (key) { this.form.volumeid = undefined; if (key === 'domainid') { this.form.account = undefined; const field = this.ownerFields.find(f => f.key === 'account'); if (field) this.load(field, true, '') } },
    search (field, keyword) { clearTimeout(this.timers[field.key]); this.candidates[field.key].version++; this.candidates[field.key].loading = true; this.timers[field.key] = setTimeout(() => this.load(field, true, keyword), 250) },
    async load (field, reset, keyword) {
      const c = this.candidates[field.key]; const version = ++c.version; const scope = this.securityScope
      if (keyword !== undefined) c.keyword = String(keyword || '')
      if (reset) { c.rows = []; c.seen = []; c.page = 0; c.count = 0 }
      c.error = false; c.loading = true
      if (field.key === 'account' && !this.form.domainid) { c.loading = false; return }
      const params = { listall: true, page: c.page + 1, pagesize: 20, ...(c.keyword ? { keyword: c.keyword } : {}), ...(field.key === 'account' ? { domainid: this.form.domainid, isrecursive: false } : {}) }
      try {
        const response = await getAPI(field.api, params, { timeout: 20000 })
        if (!this.visible || version !== c.version || scope !== this.securityScope) return
        // Root listAccounts can return descendants despite isrecursive=false. Keep raw paging
        // separate from eligible options so descendant-only pages do not stall the picker.
        const result = readSnapshotCandidatePage(response, field.api, field.response, field.key === 'account' ? { ...params, domainid: undefined } : params)
        const seen = new Set(c.seen); const fresh = result.rows.filter(row => !seen.has(row.id))
        if (!fresh.length && result.count > c.seen.length) throw new Error('Incomplete candidate page')
        c.seen.push(...fresh.map(row => row.id)); c.rows.push(...fresh.filter(row => field.key !== 'account' || row.domainid === this.form.domainid)); c.count = result.count; c.page = params.page
      } catch { if (this.visible && version === c.version && scope === this.securityScope) c.error = true } finally { if (version === c.version) c.loading = false }
    }
  }
}
</script>
<style scoped lang="less">
@import '~@/style/components/view/search-view.less';
.snapshot-search-control { display: table-cell; width: 100%; line-height: 31px; }
.storage-standard-search-form :deep(.ant-select) { width: 100%; }
</style>
