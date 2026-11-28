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
  <div class="project-limit-editor">
    <div class="detail-tab-toolbar">
      <a-button
        v-if="canWrite"
        type="primary"
        :loading="saving"
        :disabled="!canEdit || saving || !dirtyCount"
        @click="save">
        <template #icon><save-outlined /></template>{{ $t('label.save') }}
      </a-button>
      <a-button :disabled="loading || saving" :loading="loading" @click="refreshUsage">
        <template #icon><reload-outlined /></template>{{ $t('label.refresh') }}
      </a-button>
    </div>
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
    <resource-limit-table
      project
      :resource="resource"
      :settings="rows"
      :loading="loading"
      :can-configure="canWrite"
      :can-edit="canEdit && !saving"
      @edit="edit" />
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import ResourceLimitTable from '@/components/view/ResourceLimitTable.vue'
import { projectListScope } from '@/utils/projectSearch'
import {
  orderProjectLimitChanges, projectLimitDirty, projectLimitEditAllowed,
  projectLimitParams, projectLimitResponseMatches, projectLimitRows, projectLimitTagConflict, projectLimitValue
} from '@/utils/projectLimits'

export default {
  name: 'ProjectResourceLimitTab',
  components: { ResourceLimitTable },
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
    canRead () { return !!this.getters.apis.listResourceLimits && ['Admin', 'DomainAdmin'].includes(this.getters.userInfo?.roletype) },
    canWrite () { return this.canRead && !!this.getters.apis.updateResourceLimit && !!this.getters.apis.listProjects },
    effectiveState () { return this.latestState || this.resource.state },
    inactive () { return this.effectiveState !== 'Active' },
    canEdit () {
      return projectLimitEditAllowed({ ...this.resource, state: this.effectiveState }, this.getters) && !this.loading && !this.readError && !this.needsReadback
    },
    dirtyCount () { return this.rows.filter(projectLimitDirty).length },
    summary () {
      return ['saved', 'failed', 'unknown'].map(status => this.rows.filter(row => row.status === status).length)
    }
  },
  created () { this.reset() },
  watch: {
    scope () { this.reset() },
    canRead () { this.reset() },
    'resource.state' (state) { this.latestState = state }
  },
  beforeUnmount () { this.generation++ },
  methods: {
    async refreshUsage () {
      await Promise.all([this.refresh(), this.parentFetchData?.()])
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
      if (!this.canRead || this.loading || this.saving || !this.resource.id) return
      const generation = this.generation
      const projectid = this.resource.id
      this.loading = true
      this.readError = ''
      try {
        const [latest, state] = await Promise.all([this.getLimits(projectid), this.getters.apis.listProjects ? this.observeProject(projectid) : this.resource.state])
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

<style lang="scss" scoped>
@import '@/style/components/view/DetailTab.scss';
.project-limit-notice { margin-bottom: 10px; }
</style>
