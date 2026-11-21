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
  <a-spin class="project-limit-editor" :spinning="loading">
    <a-alert v-if="readError" type="error" show-icon :message="readError" class="project-limit-notice" />
    <a-alert
      v-else-if="inactive || !canWrite"
      show-icon
      type="info"
      :message="$t(inactive ? 'message.project.limit.inactive' : 'message.project.limit.read.only')"
      class="project-limit-notice" />
    <a-alert v-if="needsReadback" type="warning" show-icon :message="$t('message.project.limit.readback.required')" class="project-limit-notice" />
    <a-alert
      v-if="summary.some(count => count > 0)"
      :type="summary[1] || summary[2] ? 'warning' : 'success'"
      show-icon
      :message="$t('message.project.limit.summary', summary)"
      class="project-limit-notice" />
    <section v-for="group in groups" :key="group.label" class="project-limit-group">
      <h3>{{ $t(group.label) }}</h3>
      <a-table
        size="small"
        row-key="key"
        :columns="columns"
        :data-source="group.rows"
        :pagination="false"
        :scroll="{ x: 416 }"
        class="project-tab-table">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'label'">
            <span>{{ resourceLabel(record) }}</span>
            <a-tag v-if="record.tag" class="project-limit-tag">{{ record.tag }}</a-tag>
          </template>
          <template v-else-if="column.key === 'saved'">
            {{ record.saved === -1 ? $t('label.unlimited') + ' (-1)' : record.saved }}
          </template>
          <template v-else-if="column.key === 'draft'">
            <a-input-number
              :value="record.draft"
              :aria-label="resourceLabel(record) + (record.tag ? ' ' + record.tag : '')"
              :disabled="!canEdit || saving"
              class="project-limit-input"
              @change="value => edit(record, value)" />
          </template>
          <template v-else-if="column.key === 'result' && record.status">
            <a-tag :color="{ saved: 'green', failed: 'red', unknown: 'orange' }[record.status]">{{ $t('label.project.limit.' + record.status) }}</a-tag>
            <p v-if="record.detail" class="project-limit-detail">{{ record.detail }}</p>
          </template>
        </template>
      </a-table>
    </section>
    <a-space class="project-limit-actions">
      <a-button :disabled="loading || saving" @click="refresh">{{ $t('label.refresh') }}</a-button>
      <a-button
        type="primary"
        :loading="saving"
        :disabled="!canEdit || saving || !dirtyCount"
        @click="save">
        {{ $t('label.save') }}
      </a-button>
    </a-space>
  </a-spin>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { projectListScope } from '@/utils/projectSearch'
import {
  orderProjectLimitChanges, projectLimitDirty, projectLimitEditAllowed,
  projectLimitParams, projectLimitResponseMatches, projectLimitRows, projectLimitTagConflict, projectLimitValue
} from '@/utils/projectLimits'

export default {
  name: 'ProjectResourceLimitTab',
  inject: ['parentFetchData'],
  props: {
    resource: { type: Object, required: true }
  },
  data () {
    return {
      rows: [],
      loading: false,
      saving: false,
      readError: '',
      needsReadback: false,
      latestState: '',
      generation: 0
    }
  },
  computed: {
    getters () { return this.$store.getters },
    scope () { return JSON.stringify([this.resource.id, projectListScope(this.getters, this.$store.state?.user?.token)]) },
    canWrite () { return this.getters.apis.updateResourceLimit && ['Admin', 'DomainAdmin'].includes(this.getters.userInfo?.roletype) },
    effectiveState () { return this.latestState || this.resource.state },
    inactive () { return this.effectiveState !== 'Active' },
    canEdit () {
      return projectLimitEditAllowed({ ...this.resource, state: this.effectiveState }, this.getters) && !this.loading && !this.readError && !this.needsReadback
    },
    dirtyCount () { return this.rows.filter(projectLimitDirty).length },
    summary () {
      return ['saved', 'failed', 'unknown'].map(status => this.rows.filter(row => row.status === status).length)
    },
    groups () {
      const categories = [
        { label: 'label.compute', types: [0, 8, 9, 16] },
        { label: 'label.network', types: [1, 6, 7] },
        { label: 'label.storage', types: [2, 3, 4, 10, 11, 12, 13, 14, 15] }
      ]
      const known = categories.flatMap(category => category.types)
      return [
        ...categories.map(category => ({ ...category, rows: this.rows.filter(row => category.types.includes(row.type)) })),
        { label: 'label.other', rows: this.rows.filter(row => !known.includes(row.type)) }
      ].filter(group => group.rows.length)
    },
    columns () {
      return [
        { key: 'label', title: this.$t('label.project.limit.resource'), width: '32%' },
        { key: 'saved', title: this.$t('label.project.limit.applied'), width: '19%' },
        { key: 'draft', title: this.$t('label.project.limit.requested'), width: '23%' },
        { key: 'result', title: this.$t('label.project.limit.result') }
      ]
    }
  },
  created () { this.reset() },
  watch: {
    scope () { this.reset() },
    'resource.state' (state) { this.latestState = state }
  },
  beforeUnmount () { this.generation++ },
  methods: {
    resourceLabel (row) {
      if (row.name === 'backup') return this.$t('label.maxbackups')
      return row.name ? this.$t('label.max' + row.name.replace('_', '')) : String(row.type)
    },
    edit (row, value) {
      if (!this.canEdit || this.saving) return
      row.draft = value
      row.status = ''
      row.detail = ''
    },
    reset () {
      this.generation++
      this.rows = []
      this.loading = false
      this.saving = false
      this.readError = ''
      this.needsReadback = false
      this.latestState = this.resource.state
      this.refresh()
    },
    current (generation, projectid) { return generation === this.generation && projectid === this.resource.id },
    async getLimits (projectid) {
      const reply = await getAPI('listResourceLimits', { projectid, ignoreproject: true }, { preserveOnFailure: true, timeout: 15000 })
      const records = reply.listresourcelimitsresponse?.resourcelimit
      if (!Array.isArray(records) || !records.length ||
        records.some(record => record.projectid !== projectid)) {
        throw new Error(this.$t('message.project.limit.invalid.response'))
      }
      return projectLimitRows(records)
    },
    async observeProject (projectid) {
      const reply = await getAPI('listProjects', { id: projectid, listall: true, details: 'min', ignoreproject: true }, { preserveOnFailure: true, timeout: 15000 })
      const target = reply.listprojectsresponse?.project?.find(project => project.id === projectid)
      if (!target) throw new Error(this.$t('message.project.limit.invalid.response'))
      return target.state
    },
    failureDetail (error) {
      return Object.values(error.response?.data || {}).find(entry => typeof entry?.errortext === 'string')?.errortext ||
        error.message || this.$t('message.request.failed')
    },
    merge (latest) {
      const previous = new Map(this.rows.map(row => [row.key, row]))
      this.rows = latest.map(value => {
        const row = previous.get(value.key)
        if (!row) return value
        previous.delete(value.key)
        const dirty = projectLimitDirty(row)
        row.saved = value.saved
        if (!dirty) row.draft = value.saved
        return row
      })
      for (const row of previous.values()) {
        if (projectLimitDirty(row)) {
          row.status = 'failed'
          row.detail = this.$t('message.project.limit.invalid.response')
          this.rows.push(row)
        }
      }
    },
    async refresh () {
      if (this.loading || this.saving || !this.resource.id) return
      const generation = this.generation
      const projectid = this.resource.id
      this.loading = true
      this.readError = ''
      try {
        const [latest, state] = await Promise.all([this.getLimits(projectid), this.observeProject(projectid)])
        if (!this.current(generation, projectid)) return
        this.latestState = state
        this.merge(latest)
        for (const row of this.rows.filter(row => row.status === 'unknown')) {
          row.status = projectLimitDirty(row) ? 'failed' : 'saved'
          row.detail = row.status === 'saved' ? '' : this.$t('message.project.limit.not.applied')
        }
        this.needsReadback = false
      } catch (error) {
        if (this.current(generation, projectid)) this.readError = this.failureDetail(error)
      } finally {
        if (this.current(generation, projectid)) this.loading = false
      }
    },
    async save () {
      if (!this.canEdit || this.saving || !this.dirtyCount) return
      const generation = this.generation
      const projectid = this.resource.id
      const changes = this.rows.filter(projectLimitDirty).map(row => ({ row, before: row.saved, value: projectLimitValue(row.draft) }))
      this.saving = true
      const current = () => this.current(generation, projectid)
      let sent = false
      try {
        const [latest, state] = await Promise.all([this.getLimits(projectid), this.observeProject(projectid)])
        if (!current()) return
        this.latestState = state
        if (!this.canEdit) return
        const observed = new Map(latest.map(row => [row.key, row.saved]))
        const ordered = orderProjectLimitChanges(changes, observed)
        const sentChanges = []
        for (const change of ordered) {
          if (!current() || !this.canEdit) break
          if (this.validateChange(change, latest, observed)) {
            sent = true
            sentChanges.push(change)
            await this.saveRow(change, projectid, observed)
          }
        }
        if (!current()) return
        this.merge(latest)
        for (const { row, value } of changes) {
          if (row.status === 'saved') row.draft = value
        }
        if (sent) {
          const persisted = await this.getLimits(projectid)
          if (!current()) return
          const values = new Map(persisted.map(row => [row.key, row.saved]))
          this.merge(persisted)
          for (const { row, value } of sentChanges) {
            if (values.get(row.key) === value) {
              row.saved = value
              row.draft = value
              row.status = 'saved'
              row.detail = ''
            } else if (row.status === 'checking') {
              row.status = 'failed'
              row.detail = this.$t('message.project.limit.not.applied')
            }
          }
          this.needsReadback = sentChanges.some(change => change.row.status === 'unknown')
          this.parentFetchData({ irefresh: true })
        }
      } catch (error) {
        if (!current()) return
        this.readError = this.failureDetail(error)
        if (sent) {
          this.needsReadback = true
          for (const { row } of changes.filter(change => change.row.status === 'checking')) {
            row.status = 'unknown'
            row.detail = this.$t('message.project.limit.readback.required')
          }
        }
      } finally {
        if (current()) this.saving = false
      }
    },
    validateChange ({ row, before, value }, latest, observed) {
      row.status = 'failed'
      const actual = observed.get(row.key)
      if (value === null || (/_storage$/.test(row.name) && value > 8589934591)) {
        row.detail = this.$t('message.project.limit.invalid.number')
      } else if (actual === value) {
        row.status = 'saved'
        row.detail = ''
      } else if (actual !== before) {
        row.detail = this.$t('message.project.limit.changed')
      } else if (projectLimitTagConflict(row, value, latest, observed)) {
        row.detail = this.$t('message.project.limit.tag.bound')
      } else {
        return true
      }
      return false
    },
    async saveRow ({ row, value }, projectid, observed) {
      row.detail = ''
      try {
        const result = await postAPI('updateResourceLimit', projectLimitParams(projectid, row, value),
          { preserveOnFailure: true, timeout: 30000 })
        row.status = projectLimitResponseMatches(result, projectid, row, value) ? 'checking' : 'unknown'
        if (row.status === 'checking') observed.set(row.key, value)
      } catch (error) {
        const apiError = Object.values(error.response?.data || {}).some(entry => !!entry?.errortext)
        row.status = apiError || (error.response?.status >= 400 && error.response?.status < 500) ? 'failed' : 'unknown'
        row.detail = this.failureDetail(error)
      }
    }
  }
}
</script>

<style scoped>
.project-limit-notice { margin-bottom: 10px; }
.project-limit-actions { display: flex; justify-content: flex-end; margin-top: 12px; }
.project-limit-tag { margin-inline-start: 8px; }
.project-limit-detail { margin: 4px 0 0; overflow-wrap: anywhere; }
.project-limit-group { margin-bottom: 16px; }
.project-limit-group h3 { margin: 0 0 8px; color: var(--ui-text-primary); font-size: 14px; font-weight: 600; }
.project-limit-input { width: 100%; max-width: 116px; }
.project-limit-group :deep(.ant-tag) { max-width: 100%; white-space: normal; }
</style>
