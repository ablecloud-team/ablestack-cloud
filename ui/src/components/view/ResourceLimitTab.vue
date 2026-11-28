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
  <div class="resource-limit-tab">
    <div class="detail-tab-toolbar">
      <a-button
        v-if="canWrite"
        type="primary"
        :loading="saving"
        :disabled="!canEdit || !dirtyCount"
        @click="save">
        <template #icon><save-outlined /></template>{{ $t('label.save') }}
      </a-button>
      <a-button :loading="reading" :disabled="saving" @click="refresh">
        <template #icon><reload-outlined /></template>{{ $t('label.refresh') }}
      </a-button>
    </div>
    <a-alert
      v-if="isDomain"
      class="detail-tab-notice"
      type="info"
      show-icon
      :message="$t('message.domain.limit.scope', { path: resource.path || resource.name })"
      :description="$t('message.domain.limit.values')" />
    <a-alert
      v-else-if="canWrite"
      class="detail-tab-notice"
      type="info"
      show-icon
      :message="$t('message.account.limit.tag.scope')" />
    <a-alert v-if="readError" class="detail-tab-notice" type="error" show-icon :message="readError" />
    <a-alert
      v-if="saveSummary"
      class="detail-tab-notice"
      :type="saveSummary.failed ? 'warning' : 'success'"
      show-icon
      :message="$t('message.account.limit.save.result', saveSummary)" />
    <resource-limit-table
      :resource="resource"
      :settings="rows"
      :loading="reading || loading"
      :can-configure="canWrite"
      :can-edit="canEdit"
      @edit="edit" />
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import ResourceLimitTable from '@/components/view/ResourceLimitTable.vue'
import { orderProjectLimitChanges, projectLimitDirty, projectLimitKey, projectLimitValue, resourceLimitRows } from '@/utils/projectLimits'

export default {
  name: 'ResourceLimitTab',
  components: { ResourceLimitTable },
  emits: ['refresh'],
  props: {
    resource: { type: Object, required: true },
    loading: Boolean
  },
  data () {
    return { rows: [], reading: false, saving: false, generation: 0, readError: '', saveSummary: null }
  },
  computed: {
    isDomain () { return this.$route.meta.name === 'domain' },
    canRead () {
      const { apis, userInfo } = this.$store.getters
      return !!apis.listResourceLimits && ['Admin', 'DomainAdmin'].includes(userInfo?.roletype)
    },
    canWrite () {
      const { apis, userInfo } = this.$store.getters
      return this.canRead && !!apis.updateResourceLimit && (!this.isDomain ||
        (Number(this.resource.level) !== 0 && (userInfo.roletype === 'Admin' || this.resource.id !== userInfo.domainid)))
    },
    canEdit () { return this.canWrite && !this.reading && !this.loading && !this.saving && !this.readError },
    scope () {
      if (!this.resource.id) return null
      return this.isDomain ? { domainid: this.resource.id } : { account: this.resource.name, domainid: this.resource.domainid }
    },
    scopeKey () { return JSON.stringify([this.resource.id, this.scope, this.canRead]) },
    dirtyCount () { return this.rows.filter(projectLimitDirty).length }
  },
  watch: {
    scopeKey: { immediate: true, handler () { this.reset() } }
  },
  beforeUnmount () { this.generation++ },
  methods: {
    reset () {
      this.generation++
      this.rows = []
      this.readError = ''
      this.reading = false
      this.saving = false
      this.saveSummary = null
      this.fetchData()
    },
    edit (row, value) {
      if (!this.canEdit) return
      row.draft = value
      row.status = ''
      row.detail = ''
    },
    async refresh () {
      if (this.reading || this.saving) return
      this.$emit('refresh')
      await this.fetchData()
    },
    async fetchData () {
      if (!this.canRead || !this.scope || this.reading) return
      const generation = this.generation
      this.reading = true
      this.readError = ''
      try {
        const result = await getAPI('listResourceLimits', this.scope, { preserveOnFailure: true })
        if (generation !== this.generation) return
        const before = new Map(this.rows.map(row => [row.key, row]))
        this.rows = resourceLimitRows(result.listresourcelimitsresponse?.resourcelimit || []).map(row => {
          const prior = before.get(row.key)
          if (prior) {
            row.status = prior.status
            row.detail = prior.detail
            if (projectLimitDirty(prior)) row.draft = prior.draft
          }
          return row
        })
      } catch (error) {
        if (generation === this.generation) this.readError = this.errorDetail(error)
      } finally {
        if (generation === this.generation) this.reading = false
      }
    },
    errorDetail (error) {
      return Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext ||
        error.response?.headers?.['x-description'] || error.message || this.$t('message.request.failed')
    },
    async save () {
      if (!this.canEdit || !this.dirtyCount || !this.scope) return
      const generation = this.generation
      const scope = this.scope
      const observed = new Map(this.rows.map(row => [row.key, row.saved]))
      const planned = new Map(this.rows.map(row => [row.key, projectLimitValue(row.draft)]))
      const changes = this.rows.filter(projectLimitDirty).map(row => ({ row, value: planned.get(row.key) }))
      const summary = { success: 0, failed: 0 }
      this.saving = true
      this.saveSummary = null
      try {
        for (const { row, value } of orderProjectLimitChanges(changes, observed)) {
          if (generation !== this.generation) return
          row.status = 'failed'
          const parent = planned.get(projectLimitKey(row.type, null))
          const conflicts = row.tag ? (parent != null && parent >= 0 && value > parent)
            : (value >= 0 && this.rows.some(other => other.type === row.type && other.tag && observed.get(other.key) > value))
          if (value === null || (/_storage$/.test(row.name) && value > 8589934591)) {
            row.detail = this.$t('message.project.limit.invalid.number')
          } else if (conflicts) {
            row.detail = this.$t('message.project.limit.tag.bound')
          } else {
            try {
              await postAPI('updateResourceLimit', { ...scope, resourcetype: row.type, max: value, ...(row.tag ? { tag: row.tag } : {}) }, { preserveOnFailure: true })
              if (generation !== this.generation) return
              row.saved = value
              row.status = 'saved'
              row.detail = ''
              observed.set(row.key, value)
              summary.success++
              continue
            } catch (error) {
              if (generation !== this.generation) return
              row.detail = this.errorDetail(error)
            }
          }
          summary.failed++
        }
        this.saveSummary = summary
        if (summary.success) {
          this.$emit('refresh')
          await this.fetchData()
        }
      } finally {
        if (generation === this.generation) this.saving = false
      }
    }
  }
}
</script>

<style lang="scss" scoped>
@import '@/style/components/view/DetailTab.scss';
</style>
