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
  <section v-if="definition" class="kms-related-tab">
    <div class="detail-tab-toolbar">
      <a-button :loading="status === 'loading'" :disabled="loading || !definition" @click="refresh">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
      <a-input-search class="kms-related-search" v-model:value="draft" :placeholder="$t('label.search')" :aria-label="$t('label.search')" :disabled="!canRead || !hasTarget" allow-clear @search="search" />
    </div>
    <a-alert v-if="hasTarget && !loading" class="kms-usage-summary" type="info" show-icon :message="$t('label.kms.related.references')">
      <template #description>
        <a-descriptions v-if="usage" size="small" :column="{ xs: 1, sm: 2 }">
          <a-descriptions-item v-for="(value, field) in usage" :key="field" :label="$t('label.kms.usage.' + field)">{{ value }}</a-descriptions-item>
        </a-descriptions>
        <p v-else>{{ $t('message.kms.related.unknown') }}</p>
        <p v-if="kind === 'hsmprofile'">{{ $t('message.kms.related.previous') }}</p>
        <p>{{ $t('message.kms.related.observed') }}</p>
      </template>
    </a-alert>
    <a-alert v-if="readError" type="error" show-icon :message="$t('message.kms.detail.' + status)">
      <template #description>
        <a-button size="small" @click="refresh">{{ $t('label.retry') }}</a-button>
      </template>
    </a-alert>
    <ListView
detail-tab
      v-else
      :resource-path="'/' + definition.target"
      kms-table
      :resource-link="resourceLink"
      :items="rows"
      :columns="columns"
      :loading="loading || status === 'loading'"
      :empty-text="$t(status === 'ready' ? 'message.kms.related.empty' : 'label.loading')"
      :table-scroll="{ y: 440 }" />
    <detail-tab-pagination
      v-if="status === 'ready'"
      class="detail-tab-pagination"
      :current="criteria.page"
      :page-size="criteria.pagesize"
      :total="count"
      :show-total="total => $t('label.total') + ' ' + total + ' ' + $t('label.items')"
      :page-size-options="['10', '20', '50', '100']"
      show-size-changer
      @change="changePage" />
  </section>
</template>

<script>
import ListView from '@/components/view/ListView'
import { getAPI } from '@/api'
import { kmsAuth } from '@/utils/kmsSearch'
import { kmsRouteHistory } from '@/utils/kmsNavigation'
import { kmsRelations, kmsRelatedFilter, kmsRelatedQuery, kmsRelatedFailure, kmsRelatedChildLocation, kmsUsage, readKmsRelatedPage } from '@/utils/kmsRelated'

export default {
  name: 'KmsRelatedResources',
  components: { ListView },
  props: { resource: { type: Object, required: true }, tab: { type: String, default: '' }, loading: Boolean },
  inject: ['parentFetchData'],
  data () {
    return { draft: '', status: 'loading', rows: [], count: null, generation: 0 }
  },
  computed: {
    kind () { return this.$route.meta?.name },
    definition () { return kmsRelations[this.kind] },
    active () { return !!this.definition && this.tab === this.definition.tab },
    canRead () { return this.definition?.api in this.$store.getters.apis },
    hasTarget () { return !!this.resource.id && this.resource.id === this.$route.params?.id?.toLowerCase() },
    criteria () { return kmsRelatedQuery(this.$route.query) },
    context () {
      const getters = this.$store.getters
      return JSON.stringify([this.$route.path, this.active, this.criteria,
        this.definition && kmsRelatedFilter(this.kind, this.resource, getters), kmsAuth(getters, this.$store.state?.user?.token)])
    },
    usage () { return kmsUsage(this.kind, this.resource) },
    readError () { return ['forbidden', 'failed', 'missing'].includes(this.status) },
    columns () {
      return (this.definition?.fields || []).map(key => ({
        key,
        dataIndex: key,
        width: { name: 190, id: 305, vmname: 150, state: 120, size: 105, encryptformat: 126, kmskeyversion: 110, zonename: 115, enabled: 105, version: 90, hsmprofile: 175, owner: 150 }[key],
        title: this.$t(key === 'owner' ? 'label.kms.related.owner' : 'label.' + key)
      }))
    }
  },
  watch: {
    context: { flush: 'sync', handler () { this.draft = this.criteria.keyword; this.fetchData() } },
    resource () { this.fetchData() },
    loading (loading) { if (!loading) this.fetchData() }
  },
  mounted () { this.draft = this.criteria.keyword; this.fetchData() },
  beforeUnmount () { this.generation++ },
  methods: {
    async refresh () {
      const context = this.context
      const generation = this.generation
      if (this.parentFetchData) await this.parentFetchData({ irefresh: true })
      if (context === this.context && generation === this.generation && this.active) this.fetchData()
    },
    changeQuery (query, replace = false) {
      if (!this.active || !this.hasTarget) return
      const state = kmsRouteHistory(this.$router.options.history.state)
      const location = { path: this.$route.path, query: { ...this.$route.query, tab: this.definition.tab, ...query }, state }
      return replace ? this.$router.replace(location) : this.$router.push(location)
    },
    search (value) {
      return this.changeQuery({ relatedkeyword: String(value || '').trim().slice(0, 255) || undefined, relatedpage: '1' })
    },
    changePage (page, size) {
      return this.changeQuery({ relatedpage: String(size === this.criteria.pagesize ? page : 1), relatedpagesize: String(size) })
    },
    resourceLink (row) {
      return kmsRelatedChildLocation(this.$route, row, this.$router.options.history.state, this.$store.getters, this.$store.state?.user?.token)
    },
    async fetchData () {
      const generation = ++this.generation
      this.rows = []
      this.count = null
      this.status = 'loading'
      if (!this.active || this.loading) return
      if (!this.hasTarget || !this.canRead) {
        this.status = this.canRead ? 'missing' : 'forbidden'
        return
      }
      const context = this.context
      const current = () => generation === this.generation && context === this.context
      const { resource, kind, definition, criteria } = this
      try {
        const { rows, count } = await readKmsRelatedPage(getAPI, kind, resource, this.$store.getters, criteria)
        if (!current()) return
        const last = Math.max(1, Math.ceil(count / criteria.pagesize))
        if (criteria.page > last) {
          this.changeQuery({ relatedpage: String(last) }, true)
          return
        }
        this.rows = rows.map(row => ({ ...row, owner: row.project || row.account || row.domainpath || row.domain }))
        this.count = count
        this.status = 'ready'
      } catch (error) {
        if (current()) this.status = kmsRelatedFailure(error, definition, resource.id)
      }
    }
  }
}
</script>

<style scoped>
.kms-related-tab { min-width: 0; max-width: 100%; }
.kms-related-search { margin-left: auto; max-width: 320px; width: 100%; }
.kms-usage-summary { margin-bottom: 16px; }
.kms-usage-summary p:last-child { margin-bottom: 0; }
@media (max-width: 600px) { .kms-related-search { max-width: none; margin-left: 0; } }
</style>
