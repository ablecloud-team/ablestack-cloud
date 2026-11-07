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
  <div class="network-search-select">
    <a-select
      :value="value ? { value, label: selected?.name || value } : undefined"
      :options="options"
      :loading="loading"
      :disabled="!allowed"
      label-in-value
      show-search
      allow-clear
      :filter-option="false"
      :aria-label="$t(config.label)"
      :placeholder="$t('label.all')"
      :not-found-content="loading ? $t('label.loading') : $t('label.no.data')"
      dropdown-class-name="network-search-options"
      @search="search"
      @change="select"
      @popupScroll="moreOnScroll">
      <template #option="{ label, description }"><div class="network-search-option">{{ label }}<div class="network-search-description">{{ description }}</div></div></template>
    </a-select>
    <p v-if="value && selected" class="network-search-description">{{ describe(selected) }}</p>
    <a-alert v-if="value && !selected && !loading && !error" type="warning" show-icon :message="$t('message.network.search.unavailable')" />
    <a-alert v-if="error" type="error" show-icon :message="$t('error.fetching.data')"><template #description><a-button size="small" @click="load(true)">{{ $t('label.retry') }}</a-button></template></a-alert>
    <a-button v-if="rows.length < total && !error" type="link" size="small" :disabled="loading" @click="load(false)">{{ $t('message.snapshot.search.more', [rows.length, total]) }}</a-button>
  </div>
</template>
<script>
import { getAPI } from '@/api'
import { networkPickerApi, networkPickerParams, readNetworkPage } from '@/utils/guestNetworkSearch'
export default {
  name: 'NetworkSearchSelect',
  props: { value: { type: String, default: undefined }, kind: { type: String, required: true }, scope: { type: Object, default: () => ({}) } },
  emits: ['update:value'],
  data () { return { rows: [], selected: null, total: 0, page: 0, keyword: '', loading: false, error: false, version: 0, timer: null, disposed: false } },
  computed: {
    config () { return networkPickerApi(this.kind) },
    allowed () { return !!this.$store.state?.user?.token && this.config.api in (this.$store.getters.apis || {}) },
    context () { return JSON.stringify([networkPickerParams(this.kind, this.scope), this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.getters.userInfo?.roleid, this.$store.getters.userInfo?.domainid, this.$store.state?.user?.token, this.$store.getters.project?.id, this.$store.getters.listAllProjects, this.allowed]) },
    options () { return (this.loading || this.error ? [] : this.rows).map(r => ({ value: r.id, label: r.name, description: this.describe(r) })) }
  },
  watch: {
    context () { this.version++; clearTimeout(this.timer); this.rows = []; this.selected = null; this.load(true, '') },
    value (v) { if (!v) this.selected = null; else if (this.selected?.id !== v) this.load(true) }
  },
  created () { this.load(true) },
  beforeUnmount () { this.disposed = true; this.version++; clearTimeout(this.timer) },
  methods: {
    describe (r) { return [r.guestiptype, r.state, r.zonename, r.id].filter(Boolean).join(' · ') },
    search (keyword) { clearTimeout(this.timer); this.version++; this.keyword = String(keyword || '').trim(); this.rows = []; this.page = 0; this.total = 0; this.loading = true; this.error = false; this.timer = setTimeout(() => this.load(true), 250) },
    select (option) {
      if (!option?.value) { this.selected = null; this.$emit('update:value', undefined); this.search(''); return }
      if (this.loading || this.error) return
      const row = this.rows.find(r => r.id === option.value)
      if (row) { this.selected = row; this.$emit('update:value', row.id) }
    },
    moreOnScroll (e) { if (!this.loading && !this.error && this.rows.length < this.total && e.target.scrollHeight - e.target.scrollTop - e.target.clientHeight < 24) this.load(false) },
    async load (reset, keyword) {
      clearTimeout(this.timer)
      const version = ++this.version; const context = this.context; const value = this.value
      const current = () => !this.disposed && version === this.version && context === this.context
      if (keyword !== undefined) this.keyword = keyword
      if (reset) { this.rows = []; this.total = 0; this.page = 0 }
      this.error = false; this.loading = true
      if (!this.allowed) { this.selected = null; this.loading = false; return }
      const params = { ...networkPickerParams(this.kind, this.scope), page: reset ? 1 : this.page + 1, pagesize: 20, ...(this.keyword ? { keyword: this.keyword } : {}) }
      try {
        const response = await getAPI(this.config.api, params, { preserveOnFailure: true, timeout: 20000 })
        if (!current()) return
        const result = readNetworkPage(response, this.config.api, this.config.field, params)
        if (!reset && (result.count !== this.total || result.rows.some(r => this.rows.some(old => old.id === r.id)) || (!result.rows.length && this.rows.length < result.count))) throw new Error('changed')
        let selected = result.rows.find(r => r.id === value) || this.selected
        if (value && selected?.id !== value) {
          const lookup = { ...networkPickerParams(this.kind, this.scope), id: value, page: 1, pagesize: 20 }
          const response = await getAPI(this.config.api, lookup, { preserveOnFailure: true, timeout: 20000 })
          if (!current() || value !== this.value) return
          selected = readNetworkPage(response, this.config.api, this.config.field, lookup).rows.find(r => r.id === value) || null
        }
        if (!current()) return
        this.rows = reset ? result.rows : [...this.rows, ...result.rows]; this.total = result.count; this.page = params.page; this.selected = selected
      } catch (e) {
        if (current()) { this.rows = []; this.page = 0; this.total = 0; this.error = true; this.selected = null }
      } finally { if (current()) this.loading = false }
    }
  }
}
</script>
<style scoped>
.network-search-select, .network-search-select :deep(.ant-select) { min-width: 0; width: 100%; }
.network-search-description { margin: 4px 0; overflow-wrap: anywhere; font-size: 12px; color: var(--ui-text-secondary); }
</style>
<style>
.network-search-options .network-search-option { white-space: normal; overflow-wrap: anywhere; }
.network-search-options .network-search-description { font-size: 12px; color: var(--ui-text-secondary); }
</style>
