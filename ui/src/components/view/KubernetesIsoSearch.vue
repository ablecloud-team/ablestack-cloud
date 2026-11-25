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
  <a-input-search v-model:value="keyword" :placeholder="$t('label.search')" allow-clear @search="$emit('search', { searchQuery: $event })">
    <template #addonBefore>
      <a-popover v-model:visible="visible" trigger="click" placement="bottomRight">
        <template #content>
          <a-form class="catalog-search-form" ref="searchForm" :model="form" layout="vertical" @finish="submit" v-ctrl-enter="submit">
            <a-form-item v-if="supports('zoneid')" name="zoneid" :label="$t('label.zone')">
              <a-select v-model:value="form.zoneid" allow-clear show-search option-filter-prop="label">
                <a-select-option v-for="zone in zones" :key="zone.id" :value="zone.id" :label="zone.name">{{ zone.name }}</a-select-option>
              </a-select>
            </a-form-item>
            <a-form-item v-if="supports('arch')" name="arch" :label="$t('label.arch')">
              <a-select v-model:value="form.arch" allow-clear>
                <a-select-option v-for="arch in ['x86_64', 'aarch64', 's390x']" :key="arch" :value="arch">{{ arch }}</a-select-option>
              </a-select>
            </a-form-item>
            <a-form-item :label="$t('label.kubernetes.iso.minimum.mode')">
              <a-select v-model:value="minimumMode" @change="onModeChange">
                <a-select-option value="none">{{ $t('label.none') }}</a-select-option>
                <a-select-option v-if="supports('minimumsemanticversion')" value="semantic">{{ $t('label.minimumsemanticversion') }}</a-select-option>
                <a-select-option v-if="supports('minimumkubernetesversionid')" value="version">{{ $t('label.kubernetes.iso.minimum.version') }}</a-select-option>
              </a-select>
            </a-form-item>
            <a-form-item v-if="minimumMode === 'semantic'" name="minimumsemanticversion" :label="$t('label.minimumsemanticversion')" :rules="[{ validator: validateMinimum }]">
              <a-input v-model:value="form.minimumsemanticversion" placeholder="1.30.0" />
            </a-form-item>
            <a-form-item v-if="minimumMode === 'version'" name="minimumkubernetesversionid" :label="$t('label.kubernetes.iso.minimum.version')" :rules="[{ required: true, message: $t('message.error.select') }]">
              <a-select v-model:value="form.minimumkubernetesversionid" show-search :filter-option="false" :loading="loading" @search="searchVersions">
                <a-select-option v-for="version in versions" :key="version.id" :value="version.id" :label="version.name">{{ version.name }} ({{ version.semanticversion }})</a-select-option>
              </a-select>
              <a-button v-if="hasMore" type="link" :disabled="loading" @click="fetchVersions(false)">{{ $t('label.more') }}</a-button>
              <a-alert v-if="lookupError" type="error" show-icon :message="$t('message.kubernetes.iso.search.lookup')">
                <template #action><a-button size="small" @click="fetchVersions(true)">{{ $t('label.retry') }}</a-button></template>
              </a-alert>
            </a-form-item>
            <div class="catalog-search-actions">
              <a-button size="small" @click="reset">{{ $t('label.reset') }}</a-button>
              <a-button size="small" html-type="submit" type="primary" :disabled="minimumMode === 'version' && (loading || lookupError)">{{ $t('label.search') }}</a-button>
            </div>
          </a-form>
        </template>
        <a-button class="filter-button" size="small" :aria-label="$t('label.advanced.search')" :aria-expanded="visible">
          <filter-outlined />
        </a-button>
      </a-popover>
    </template>
  </a-input-search>
</template>
<script>
import { getAPI } from '@/api'
import { kubernetesIsoCapability } from '@/utils/kubernetesIsoCapabilities'
import { kubernetesIsoListResponse } from '@/utils/kubernetesIsoModel'
import { kubernetesIsoListOwner, validKubernetesMinimum } from '@/utils/kubernetesIsoSearch'
export default {
  name: 'KubernetesIsoSearch',
  emits: ['search'],
  data () {
    return { visible: false, keyword: '', form: {}, minimumMode: 'none', zones: [], versions: [], loading: false, lookupError: false, hasMore: false, page: 1, lookupKeyword: '', generation: 0, disposed: false }
  },
  computed: {
    capability () { return kubernetesIsoCapability('read', this.$store.getters) },
    owner () { return kubernetesIsoListOwner(this.$store.getters, this.$store.state?.user?.token) }
  },
  watch: {
    '$route.fullPath' () { this.restore() },
    owner: {
      flush: 'sync',
      handler () {
        this.generation++; this.zones = []; this.versions = []; this.visible = false; this.form = {}; this.keyword = ''
        this.minimumMode = 'none'; this.loading = false; this.lookupError = false; this.hasMore = false; this.page = 1; this.lookupKeyword = ''
      }
    },
    visible (value) { if (value) { this.restore(); this.fetchZones(); if (this.minimumMode === 'version') this.fetchVersions(true) } }
  },
  created () { this.restore() },
  beforeUnmount () { this.disposed = true; this.generation++ },
  methods: {
    options () { return { preserveOnFailure: true, timeout: 20000, readScope: JSON.stringify([this.$store.state?.user?.token, this.$store.getters.userInfo?.id, this.$store.getters.project?.id]) } },
    supports (field) { return this.capability.schemaKnown && !!this.capability.parameters[field] },
    restore () {
      const query = this.$route.query || {}
      this.keyword = query.keyword || ''
      this.form = { zoneid: query.zoneid, arch: query.arch, minimumsemanticversion: query.minimumsemanticversion, minimumkubernetesversionid: query.minimumkubernetesversionid }
      this.minimumMode = query.minimumkubernetesversionid ? 'version' : query.minimumsemanticversion ? 'semantic' : 'none'
    },
    async validateMinimum () { if (!validKubernetesMinimum(String(this.form.minimumsemanticversion || '').trim())) throw new Error(this.$t('message.kubernetes.iso.search.minimum')) },
    onModeChange () {
      if (this.minimumMode === 'version') this.fetchVersions(true)
      this.lookupError = false
    },
    async fetchZones () {
      if (!this.$store.getters.apis?.listZones) return
      const owner = this.owner
      try {
        const json = await getAPI('listZones', {}, this.options())
        if (!this.disposed && owner === this.owner) this.zones = Array.isArray(json?.listzonesresponse?.zone) ? json.listzonesresponse.zone : []
      } catch (_) { /* Optional Zone labels do not block catalog reads. */ }
    },
    searchVersions (keyword) { this.lookupKeyword = keyword; this.fetchVersions(true) },
    async fetchVersions (reset) {
      if (!this.capability.available) return
      const generation = ++this.generation; const owner = this.owner
      if (reset) { this.page = 1; this.versions = [] }
      this.loading = true; this.lookupError = false
      try {
        const json = await getAPI('listKubernetesSupportedVersions', { ...(this.lookupKeyword ? { keyword: this.lookupKeyword } : {}), page: this.page, pagesize: 50 }, this.options())
        if (this.disposed || generation !== this.generation || owner !== this.owner) return
        const result = kubernetesIsoListResponse(json, this.$store.getters)
        if (!result.countKnown) throw new Error('missing count')
        this.versions = [...new Map([...this.versions, ...result.rows].map(row => [row.id, row])).values()]
        this.hasMore = this.versions.length < result.count; this.page++
        if (reset && this.form.minimumkubernetesversionid && !this.versions.some(row => row.id === this.form.minimumkubernetesversionid)) {
          const target = this.form.minimumkubernetesversionid
          const response = await getAPI('listKubernetesSupportedVersions', { id: target }, this.options())
          if (this.disposed || generation !== this.generation || owner !== this.owner) return
          const selected = kubernetesIsoListResponse(response, this.$store.getters, target)
          if (!selected.rows.length) throw new Error('unavailable minimum')
          this.versions = [...this.versions, ...selected.rows]
        }
      } catch (_) { if (!this.disposed && generation === this.generation && owner === this.owner) this.lookupError = true } finally { if (generation === this.generation && owner === this.owner) this.loading = false }
    },
    async submit (event) {
      const owner = this.owner
      if (event?.preventDefault) event.preventDefault()
      try {
        await this.$refs.searchForm.validate()
        if (this.disposed || owner !== this.owner) return
        const opts = { zoneid: this.form.zoneid, arch: this.form.arch }
        if (this.minimumMode === 'semantic') opts.minimumsemanticversion = this.form.minimumsemanticversion.trim()
        if (this.minimumMode === 'version') {
          if (this.lookupError || this.loading || !this.form.minimumkubernetesversionid) return
          opts.minimumkubernetesversionid = this.form.minimumkubernetesversionid
        }
        this.visible = false; this.$emit('search', opts)
      } catch (_) { /* Ant Form renders the field validation. */ }
    },
    reset () { this.form = {}; this.minimumMode = 'none'; this.keyword = ''; this.visible = false; this.$emit('search', { searchQuery: '', zoneid: undefined, arch: undefined, minimumsemanticversion: undefined, minimumkubernetesversionid: undefined, reset: true }) }
  }
}
</script>
<style scoped>
.catalog-search-form { width: 300px; max-width: calc(100vw - 64px); }
.catalog-search-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
