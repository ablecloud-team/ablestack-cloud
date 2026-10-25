<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for copyright ownership.
Licensed under the Apache License, Version 2.0; see
http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an AS IS BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <span class="snapshot-search-control" @keydown.esc.stop="close(true)">
    <span class="filter-group">
      <a-input-search v-model:value="keyword" class="input-search" :placeholder="$t('label.search')" allow-clear @search="$emit('search', { searchQuery: keyword })">
        <template #addonBefore>
          <a-popover placement="bottomRight" trigger="click" :visible="visible" @visibleChange="onVisibleChange">
            <template #content>
              <a-form v-if="visible" :model="form" layout="vertical" class="storage-standard-search-form" @finish="apply" @keydown.esc.stop.prevent="close(true)" v-ctrl-enter="apply">
                <a-alert v-if="error" type="error" show-icon :message="$t('message.snapshot.search.' + error)" />
                <a-alert v-if="projectId" type="info" show-icon :message="$t('message.snapshot.search.project')" />
                <a-form-item :label="$t('label.search')" html-for="snapshot-search-keyword"><a-input id="snapshot-search-keyword" ref="filterKeyword" v-model:value="form.keyword" /></a-form-item>
                <a-form-item :label="$t('label.name')" html-for="snapshot-search-name"><a-input id="snapshot-search-name" v-model:value="form.name" /></a-form-item>
                <a-form-item v-for="field in staticFields" :key="field.key" :label="$t(field.label)" :html-for="'snapshot-search-' + field.key">
                  <a-select :id="'snapshot-search-' + field.key" v-model:value="form[field.key]" :disabled="field.disabled" :allow-clear="field.key !== 'showunique'" @change="change(field.key)">
                    <a-select-option v-for="option in field.options" :key="option" :value="option">{{ $t(valueLabel(field.key, option)) }}</a-select-option>
                  </a-select>
                </a-form-item>
                <a-form-item v-for="field in pickers" :key="field.key" :label="$t(field.label)" :html-for="'snapshot-search-' + field.key">
                  <a-select
                    :id="'snapshot-search-' + field.key"
                    v-model:value="form[field.key]"
                    allow-clear
                    show-search
                    :filter-option="false"
                    :loading="candidates[field.key].loading"
                    :disabled="field.disabled"
                    @search="searchOptions(field, $event)"
                    @change="change(field.key)">
                    <a-select-option v-if="form[field.key] && !candidates[field.key].rows.some(row => (field.key === 'account' ? row.name : row.id) === form[field.key])" :value="form[field.key]">{{ form[field.key] }}</a-select-option>
                    <a-select-option v-for="row in candidates[field.key].rows" :key="row.id" :value="field.key === 'account' ? row.name : row.id">{{ field.key === 'domainid' ? (row.path || row.name) : row.name }} · {{ row.id.slice(0, 8) }}</a-select-option>
                  </a-select>
                  <template #extra>
                    <a-alert v-if="candidates[field.key].error" type="error" :message="$t('message.snapshot.search.lookup.' + candidates[field.key].error)" show-icon>
                      <template #description><a-button size="small" @click="load(field, true)">{{ $t('label.retry') }}</a-button></template>
                    </a-alert>
                    <span v-else-if="field.disabled" class="snapshot-search-hint">{{ $t('message.snapshot.search.owner.hint') }}</span>
                    <span v-else-if="!candidates[field.key].loading && !candidates[field.key].count" class="snapshot-search-hint">{{ $t('message.snapshot.search.empty') }}</span>
                    <a-button v-if="candidates[field.key].rows.length < candidates[field.key].count" type="link" size="small" :disabled="candidates[field.key].loading || !!candidates[field.key].error" @click="load(field, false)">{{ $t('message.snapshot.search.more', [candidates[field.key].rows.length, candidates[field.key].count]) }}</a-button>
                    <span v-if="field.key === 'volumeid' && form.volumeid && !candidates.volumeid.loading && !candidates.volumeid.rows.some(row => row.id === form.volumeid)" class="snapshot-search-hint">{{ $t('message.snapshot.search.retained.volume') }}</span>
                  </template>
                </a-form-item>
                <a-form-item v-if="ownerFilters && !projectId" :label="$t('label.snapshot.search.recursive')"><a-switch :checked="form.isrecursive === 'true'" @change="form.isrecursive = $event ? 'true' : undefined; change('isrecursive')" /></a-form-item>
                <p class="snapshot-search-hint">{{ $t('message.snapshot.search.location.hint') }}</p>
                <a-form-item :label="$t('label.tags')">
                  <div v-for="(tag, index) in form.tags" :key="index" class="snapshot-search-tag">
                    <a-input v-model:value="tag.key" :aria-label="$t('label.key') + ' ' + (index + 1)" :placeholder="$t('label.key')" />
                    <a-input v-model:value="tag.value" :aria-label="$t('label.value') + ' ' + (index + 1)" :placeholder="$t('label.value')" />
                    <a-button :aria-label="$t('label.remove') + ' ' + (index + 1)" @click="form.tags.splice(index, 1)"><close-outlined /></a-button>
                  </div>
                  <a-button size="small" @click="form.tags.push({ key: '', value: '' })"><plus-outlined />{{ $t('label.add') }}</a-button>
                  <p class="snapshot-search-hint">{{ $t('message.snapshot.search.tags.hint') }}</p>
                </a-form-item>
                <div class="filter-group-button">
                  <a-button class="filter-group-button-clear" size="small" @click="reset"><template #icon><stop-outlined /></template>{{ $t('label.reset') }}</a-button>
                  <a-button ref="submit" class="filter-group-button-search" type="primary" size="small" html-type="submit" :disabled="blocked"><template #icon><search-outlined /></template>{{ $t('label.search') }}</a-button>
                </div>
              </a-form>
            </template>
            <a-button ref="filterTrigger" class="filter-button" size="small" :aria-label="$t('label.advanced.search')" :aria-expanded="visible">
              <filter-two-tone v-if="isFiltered" /><filter-outlined v-else />
            </a-button>
          </a-popover>
        </template>
      </a-input-search>
    </span>
  </span>
</template>
<script>
import { getAPI } from '@/api'
import { snapshotIntervals, snapshotSearchError, snapshotSearchPermissionError, snapshotSearchForm, snapshotSearchQuery, snapshotPickerScope, readSnapshotCandidatePage, snapshotSearchValue } from '@/utils/storageSnapshotSearch'
const empty = () => ({ rows: [], count: 0, page: 0, keyword: '', loading: false, error: '', version: 0 })
export default {
  name: 'SnapshotSearch',
  emits: ['search'],
  data () { return { visible: false, keyword: '', form: { tags: [] }, candidates: Object.fromEntries(['volumeid', 'zoneid', 'domainid', 'account', 'storageid', 'imagestoreid'].map(key => [key, empty()])), timers: {} } },
  computed: {
    isFiltered () { const form = snapshotSearchForm(this.$route.query); return Object.entries(form).some(([key, value]) => key === 'tags' ? value.length > 0 : key === 'showunique' ? value === 'false' : !['keyword', 'q'].includes(key) && !!value) },
    ownerFilters () { return ['Admin', 'DomainAdmin'].includes(this.$store.getters.userInfo?.roletype) },
    projectScope () { return this.$route.query.projectid || this.$store.getters.project?.id || (this.$store.getters.listAllProjects ? '-1' : '0') },
    projectId () { return this.projectScope === '0' ? '' : this.projectScope },
    securityScope () { return JSON.stringify([this.$route.path, this.$route.query.projectid, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.state?.user?.token, this.$store.getters.project?.id, this.$store.getters.listAllProjects, Object.keys(this.$store.getters.apis || {}).sort()]) },
    error () { const query = { ...snapshotSearchQuery({}, this.form, 20), ...(this.projectId ? { projectid: this.projectId } : {}) }; return snapshotSearchError(query) || snapshotSearchPermissionError(query, this.$store.getters) },
    staticFields () {
      return [
        { key: 'snapshottype', label: 'label.snapshot.search.type', options: ['MANUAL', 'RECURRING'] },
        { key: 'intervaltype', label: 'label.intervaltype', options: snapshotIntervals, disabled: this.form.snapshottype === 'MANUAL' },
        { key: 'showunique', label: 'label.snapshot.search.unit', options: ['true', 'false'] },
        { key: 'locationtype', label: 'label.snapshot.search.location', options: ['primary', 'secondary'], disabled: this.form.showunique !== 'false' }
      ]
    },
    pickers () {
      const fields = [
        { key: 'volumeid', label: 'label.volume', api: 'listVolumes', response: 'volume' },
        { key: 'zoneid', label: 'label.zone', api: 'listZones', response: 'zone' }
      ]
      if (this.ownerFilters) {
        fields.push(
          { key: 'domainid', label: 'label.domain', api: 'listDomains', response: 'domain', disabled: !!this.projectId },
          { key: 'account', label: 'label.account', api: 'listAccounts', response: 'account', disabled: !!this.projectId || !this.form.domainid },
          { key: 'storageid', label: 'label.primary.storage', api: 'listStoragePools', response: 'storagepool' },
          { key: 'imagestoreid', label: 'label.secondary.storage', api: 'listImageStores', response: 'imagestore' })
      }
      return fields.filter(field => field.api in this.$store.getters.apis)
    },
    blocked () { return !!this.error || this.pickers.some(field => !field.disabled && (this.candidates[field.key].loading || this.candidates[field.key].error)) }
  },
  watch: {
    '$route.query': { immediate: true, handler () { this.keyword = this.$route.query.keyword || this.$route.query.q || ''; if (this.visible) this.close() } },
    securityScope () { this.close(); this.keyword = this.$route.query.keyword || '' }
  },
  beforeUnmount () { this.close() },
  methods: {
    valueLabel: snapshotSearchValue,
    onVisibleChange (visible) { if (visible) this.open(); else this.close() },
    open () { this.form = snapshotSearchForm(this.$route.query); this.visible = true; this.pickers.forEach(field => this.load(field, true, '')); this.$nextTick(() => this.$refs.filterKeyword?.focus?.()) },
    close (returnFocus = false) { this.visible = false; Object.values(this.timers).forEach(clearTimeout); this.timers = {}; Object.values(this.candidates).forEach(state => { state.version++; state.rows = []; state.count = 0; state.loading = false; state.error = '' }); if (returnFocus) this.$nextTick(() => this.$refs.filterTrigger?.$el?.focus()) },
    reset () { this.close(true); this.$emit('search', { snapshotSearch: {} }) },
    apply () { if (this.blocked) return; const form = JSON.parse(JSON.stringify(this.form)); this.close(true); this.$emit('search', { snapshotSearch: form }) },
    change (key) {
      if (key === 'snapshottype' && this.form.snapshottype === 'MANUAL') this.form.intervaltype = undefined
      if (key === 'intervaltype' && this.form.intervaltype) this.form.snapshottype = 'RECURRING'
      if (key === 'showunique' && this.form.showunique !== 'false') this.form.locationtype = undefined
      if (key === 'storageid' && this.form.storageid) { this.form.imagestoreid = undefined; if (this.form.locationtype === 'secondary') this.form.locationtype = undefined }
      if (key === 'imagestoreid' && this.form.imagestoreid) { this.form.storageid = undefined; if (this.form.locationtype === 'primary') this.form.locationtype = undefined }
      if (key === 'locationtype') { if (this.form.locationtype === 'primary') this.form.imagestoreid = undefined; if (this.form.locationtype === 'secondary') this.form.storageid = undefined }
      if (key === 'domainid') this.form.account = undefined
      if (['account', 'domainid', 'isrecursive', 'zoneid'].includes(key)) {
        this.form.volumeid = undefined
        if (key === 'zoneid') { this.form.storageid = undefined; this.form.imagestoreid = undefined }
        this.pickers.filter(field => ['volumeid', ...(key === 'domainid' ? ['account'] : []), ...(key === 'zoneid' ? ['storageid', 'imagestoreid'] : [])].includes(field.key)).forEach(field => this.load(field, true, ''))
      }
    },
    searchOptions (field, keyword) { clearTimeout(this.timers[field.key]); const state = this.candidates[field.key]; state.version++; this.timers[field.key] = setTimeout(() => this.load(field, true, keyword), 250) },
    async load (field, reset, keyword) {
      const state = this.candidates[field.key]; const version = ++state.version; const security = this.securityScope
      if (reset) { state.rows = []; state.count = 0; state.page = 0; if (keyword !== undefined) state.keyword = keyword }
      state.error = ''; state.loading = false
      const current = this.pickers.find(item => item.key === field.key)
      if (!this.visible || !current || current.disabled) return
      state.loading = true
      const params = { listall: true, page: reset ? 1 : state.page + 1, pagesize: 20, ...(state.keyword ? { keyword: state.keyword } : {}) }
      if (field.key === 'volumeid') Object.assign(params, snapshotPickerScope(this.form, this.projectScope), this.form.zoneid ? { zoneid: this.form.zoneid } : {})
      // listAccounts forces recursion when listall=true, even with isrecursive=false.
      if (field.key === 'account') Object.assign(params, { listall: false, domainid: this.form.domainid, isrecursive: false })
      if (field.key === 'domainid') params.isrecursive = true
      if (['storageid', 'imagestoreid'].includes(field.key) && this.form.zoneid) params.zoneid = this.form.zoneid
      try {
        const response = await getAPI(field.api, params, { preserveOnFailure: true, timeout: 20000 })
        if (!this.visible || version !== state.version || security !== this.securityScope) return
        const result = readSnapshotCandidatePage(response, field.api, field.response, params)
        if (!reset && (result.count !== state.count || result.rows.some(row => state.rows.some(old => old.id === row.id)) || (!result.rows.length && state.rows.length < result.count))) throw new Error('changed')
        state.rows = reset ? result.rows : [...state.rows, ...result.rows]; state.count = result.count; state.page = params.page
      } catch (error) {
        if (!this.visible || version !== state.version || security !== this.securityScope) return
        const code = Number(error?.response?.data?.errorresponse?.errorcode || error?.response?.status)
        const denied = [401, 403, 431, 432, 531].includes(code)
        state.error = denied ? 'denied' : ['changed', 'contract', 'scope'].includes(error.message) ? error.message : 'failed'
        if (denied || state.error === 'scope') { state.rows = []; state.count = 0; this.form[field.key] = undefined }
      } finally { if (version === state.version && security === this.securityScope) state.loading = false }
    }
  }
}
</script>
<style scoped lang="less">
@import '~@/style/components/view/search-view.less';
.snapshot-search-control { display: table-cell; width: 100%; line-height: 31px; }
.storage-standard-search-form :deep(.ant-select) { width: 100%; }
.snapshot-search-tag { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 8px; margin-bottom: 8px; }
.snapshot-search-hint { display: block; margin: 6px 0 0; color: inherit; opacity: .85; line-height: 1.5; overflow-wrap: anywhere; }
</style>
