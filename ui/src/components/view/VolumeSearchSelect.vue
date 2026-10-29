<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file for copyright ownership.
Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. -->
<template>
  <div class="volume-search-select">
    <a-select
      :value="value ? { value, label: selected?.name || value } : undefined"
      label-in-value
      :options="options"
      :loading="loading"
      :disabled="!allowed"
      :filter-option="false"
      :aria-label="$t('label.volume')"
      :not-found-content="loading ? $t('label.loading') : $t('message.snapshot.search.empty')"
      dropdown-class-name="volume-search-options"
      option-label-prop="label"
      show-search
      allow-clear
      @search="search"
      @change="select"
      @popupScroll="moreOnScroll">
      <template #option="{ label, description }">
        <div class="volume-search-option"><div>{{ label }}</div><div class="volume-search-description">{{ description }}</div></div>
      </template>
    </a-select>
    <p v-if="selected" class="volume-search-description volume-search-target">{{ describe(selected) }}</p>
    <a-alert v-if="value && !selected && !loading && !error" type="warning" show-icon :message="$t('message.volume.search.unavailable')" />
    <a-alert v-if="error" type="error" show-icon :message="$t('error.fetching.data')">
      <template #description><a-button size="small" @click="load(true)">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <a-button v-if="rows.length < total && !error" type="link" size="small" :disabled="loading" @click="load(false)">{{ $t('message.snapshot.search.more', [rows.length, total]) }}</a-button>
  </div>
</template>
<script>
import { getAPI } from '@/api'
import { readSnapshotCandidatePage } from '@/utils/storageSnapshotSearch'

export default {
  name: 'VolumeSearchSelect',
  props: { value: { type: String, default: undefined }, scope: { type: Object, default: () => ({}) } },
  emits: ['update:value', 'blocked'],
  data () { return { rows: [], total: 0, page: 0, keyword: '', selected: null, loading: true, error: false, version: 0, timer: null, disposed: false } },
  computed: {
    allowed () { return 'listVolumes' in (this.$store.getters.apis || {}) },
    scopeKey () { return JSON.stringify([this.scope, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roletype, this.$store.state?.user?.token, this.$store.getters.project?.id, this.$store.getters.listAllProjects, this.allowed, this.$route.path]) },
    blocked () { return !this.allowed || this.loading || this.error || (!!this.value && this.selected?.id !== this.value) },
    options () { return (this.loading || this.error ? [] : this.rows).map(row => ({ value: row.id, label: row.name, description: this.describe(row) })) }
  },
  watch: {
    blocked: { immediate: true, handler (value) { this.$emit('blocked', !!value) } },
    scopeKey () { clearTimeout(this.timer); this.version++; this.rows = []; this.selected = null; this.$emit('update:value', undefined); this.load(true, '') },
    value (value) {
      if (!value) { this.selected = null; return }
      if (this.selected?.id === value) return
      this.selected = this.rows.find(row => row.id === value) || null
      if (!this.selected) this.load(true)
    }
  },
  created () { this.load(true, '') },
  beforeUnmount () { this.disposed = true; clearTimeout(this.timer); this.version++ },
  methods: {
    describe (row) {
      return [row.vmdisplayname || row.vmname, row.project || row.account, row.domain, row.zonename, row.id].filter(value => value != null && value !== '').join(' · ')
    },
    search (keyword) {
      clearTimeout(this.timer); this.version++; this.rows = []; this.total = 0; this.page = 0; this.loading = true; this.error = false
      this.keyword = String(keyword || '').trim()
      this.timer = setTimeout(() => this.load(true), 250)
    },
    select (option) {
      const value = option?.value
      if (value == null) { this.selected = null; this.$emit('update:value', undefined); this.search(''); return }
      if (this.loading || this.error) return
      const row = this.rows.find(row => row.id === value)
      if (!row) return
      this.selected = row; this.$emit('update:value', row.id)
    },
    moreOnScroll (event) {
      if (!this.loading && !this.error && this.rows.length < this.total && event.target.scrollHeight - event.target.scrollTop - event.target.clientHeight < 24) this.load(false)
    },
    async load (reset, keyword) {
      clearTimeout(this.timer)
      const version = ++this.version; const scope = this.scopeKey; const value = this.value
      const current = () => !this.disposed && version === this.version && scope === this.scopeKey
      if (keyword !== undefined) this.keyword = keyword
      if (reset) { this.rows = []; this.page = 0; this.total = 0 }
      this.error = false; this.loading = true
      if (!this.allowed) { this.loading = false; return }
      const params = { listall: true, ...this.scope, page: reset ? 1 : this.page + 1, pagesize: 20, ...(this.keyword ? { keyword: this.keyword } : {}) }
      try {
        const response = await getAPI('listVolumes', params, { preserveOnFailure: true, timeout: 20000 })
        if (!current()) return
        const result = readSnapshotCandidatePage(response, 'listVolumes', 'volume', params)
        if (!reset && (result.count !== this.total || result.rows.some(row => this.rows.some(old => old.id === row.id)) || (!result.rows.length && this.rows.length < result.count))) throw new Error('changed')
        let selected = this.selected || result.rows.find(row => row.id === value) || null
        if (value && selected?.id !== value) {
          const lookup = { listall: true, ...this.scope, id: value, page: 1, pagesize: 20 }
          const byId = await getAPI('listVolumes', lookup, { preserveOnFailure: true, timeout: 20000 })
          if (!current() || value !== this.value) return
          selected = readSnapshotCandidatePage(byId, 'listVolumes', 'volume', lookup).rows.find(row => row.id === value) || null
        }
        if (!current()) return
        this.rows = reset ? result.rows : [...this.rows, ...result.rows]; this.total = result.count; this.page = params.page; this.selected = selected
      } catch (_) {
        if (current()) { this.rows = []; this.total = 0; this.page = 0; this.error = true }
      } finally { if (current()) this.loading = false }
    }
  }
}
</script>
<style scoped>
.volume-search-select { min-width: 0; width: 100%; }
.volume-search-select :deep(.ant-select) { width: 100%; }
.volume-search-target { overflow-wrap: anywhere; margin: 8px 0; }
.volume-search-description { font-size: 12px; color: var(--ui-text-secondary); }
</style>
<style>
.volume-search-options .volume-search-option { white-space: normal; overflow-wrap: anywhere; }
.volume-search-options .volume-search-description { font-size: 12px; color: var(--ui-text-secondary); }
</style>
