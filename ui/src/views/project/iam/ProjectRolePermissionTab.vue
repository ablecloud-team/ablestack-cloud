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
  <section ref="rulesPanel" class="project-rules">
    <a-alert v-if="error" class="project-tab-notice" show-icon type="error" :message="error" />
    <div v-if="!ready" class="project-tab-toolbar">
      <a-button :loading="busy" @click="refresh">
        <template #icon><reload-outlined /></template>
        {{ $t('label.refresh') }}
      </a-button>
    </div>
    <a-form v-if="apis.createProjectRolePermission" class="project-rule-form" :model="draft" layout="vertical" @finish="createRule">
      <a-form-item name="rule" :label="$t('label.rule')" html-for="project-rule-name" required>
        <a-auto-complete
          id="project-rule-name"
          v-model:value="draft.rule"
          :options="apiOptions"
          :filter-option="filterOption"
          :disabled="locked" />
      </a-form-item>
      <a-form-item :label="$t('label.permission')" html-for="project-rule-permission">
        <a-select id="project-rule-permission" v-model:value="draft.permission" :options="permissionOptions" :disabled="locked" />
      </a-form-item>
      <a-form-item :label="$t('label.description')" html-for="project-rule-description">
        <a-input id="project-rule-description" v-model:value="draft.description" :disabled="locked" />
      </a-form-item>
      <a-button type="primary" html-type="submit" :disabled="locked || !draft.rule.trim()">
        {{ $t('label.save.new.rule') }}
      </a-button>
    </a-form>
    <a-table
      class="project-tab-table"
      size="small"
      row-key="id"
      :columns="columns"
      :data-source="rules"
      :scroll="{ x: 480 }"
      :loading="busy"
      :pagination="false">
      <template #bodyCell="{ column, record, index }">
        <a-select
          v-if="column.key === 'permission'"
          :value="record.permission"
          :aria-label="$t('label.permission') + ' · ' + record.rule"
          :options="permissionOptions"
          :disabled="locked || !apis.updateProjectRolePermission"
          class="project-rule-select"
          @change="setPermission(record, $event)" />
        <a-space v-else-if="column.key === 'actions'" :data-rule-id="record.id" wrap>
          <template v-if="apis.updateProjectRolePermission">
            <a-tooltip :title="$t('label.move.up.row')">
              <a-button
                size="small"
                data-rule-direction="-1"
                :aria-label="$t('label.move.up.row') + ' · ' + record.rule"
                :disabled="locked || index === 0"
                @click="moveRule(index, -1, $event)">
                <template #icon><arrow-up-outlined /></template>
              </a-button>
            </a-tooltip>
            <a-tooltip :title="$t('label.move.down.row')">
              <a-button
                size="small"
                data-rule-direction="1"
                :aria-label="$t('label.move.down.row') + ' · ' + record.rule"
                :disabled="locked || index === rules.length - 1"
                @click="moveRule(index, 1, $event)">
                <template #icon><arrow-down-outlined /></template>
              </a-button>
            </a-tooltip>
          </template>
          <RuleDelete v-if="apis.deleteProjectRolePermission" :record="record" :disabled="locked" @delete="deleteRule(record)" />
        </a-space>
      </template>
    </a-table>
    <p class="project-rule-feedback" role="status">{{ announcement }}</p>
  </section>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import RuleDelete from '@/views/iam/RuleDelete'

const emptyRule = () => ({ rule: '', permission: 'deny', description: '' })

export default {
  name: 'ProjectRolePermissionTab',
  components: { RuleDelete },
  props: {
    resource: { type: Object, required: true },
    role: { type: Object, required: true }
  },
  data () {
    return { busy: false, ready: false, error: '', announcement: '', rules: [], draft: emptyRule() }
  },
  computed: {
    apis () { return this.$store.getters.apis },
    locked () { return this.busy || !this.ready },
    target () { return { projectid: this.resource.id, projectroleid: this.role.id, ignoreproject: true } },
    apiOptions () { return Object.keys(this.apis).sort().map(value => ({ value })) },
    permissionOptions () { return ['allow', 'deny'].map(value => ({ value, label: this.$t('label.' + value) })) },
    columns () {
      return [
        { key: 'rule', dataIndex: 'rule', title: this.$t('label.rule') },
        { key: 'permission', title: this.$t('label.permission'), width: 95 },
        { key: 'description', dataIndex: 'description', title: this.$t('label.description') },
        ...(this.apis.updateProjectRolePermission || this.apis.deleteProjectRolePermission
          ? [{ key: 'actions', title: this.$t('label.actions'), width: 122 }]
          : [])
      ]
    }
  },
  mounted () { this.refresh() },
  methods: {
    filterOption (input, option) { return option.value.toLowerCase().includes(input.toLowerCase()) },
    errorText (error) {
      return Object.values(error.response?.data || {}).find(value => value?.errortext)?.errortext || this.$t('message.request.failed')
    },
    async fetchRules () {
      const reply = await getAPI('listProjectRolePermissions', this.target, { preserveOnFailure: true, timeout: 15000 })
      this.rules = reply.listprojectrolepermissionsresponse?.projectrolepermission || []
      this.ready = true
    },
    async refresh () {
      if (this.busy) return
      this.busy = true
      this.error = ''
      try {
        await this.fetchRules()
      } catch (error) {
        this.ready = false
        this.error = this.errorText(error)
      } finally {
        this.busy = false
      }
    },
    async mutate (api, fields, onSuccess) {
      if (this.locked || !this.apis[api]) return
      this.busy = true
      this.error = ''
      this.announcement = ''
      try {
        await postAPI(api, { ...this.target, ...fields }, { preserveOnFailure: true, timeout: 15000 })
        this.ready = false
        await this.fetchRules()
        if (onSuccess) onSuccess()
      } catch (error) {
        this.error = this.errorText(error)
      } finally {
        this.busy = false
      }
    },
    async moveRule (index, direction, event) {
      const next = index + direction
      if (next < 0 || next >= this.rules.length) return
      const row = this.rules[index]
      const ruleorder = this.rules.map(rule => rule.id)
      ruleorder[index] = ruleorder[next]
      ruleorder[next] = row.id
      const trigger = event?.currentTarget
      await this.mutate('updateProjectRolePermission', { ruleorder }, () => {
        this.announcement = this.$t('message.project.rule.moved', { rule: row.rule, position: next + 1 })
      })
      await this.$nextTick()
      if (!trigger || ![trigger, document.body].includes(document.activeElement)) return
      const controls = Array.from(this.$refs.rulesPanel?.querySelectorAll('[data-rule-id]') || [])
        .find(element => element.dataset.ruleId === row.id)
      const replacement = controls?.querySelector('[data-rule-direction="' + direction + '"]:not(:disabled)') ||
        controls?.querySelector('[data-rule-direction]:not(:disabled)')
      if (replacement) replacement.focus({ preventScroll: true })
    },
    createRule () {
      const rule = this.draft.rule.trim()
      if (!rule) return
      return this.mutate('createProjectRolePermission', { ...this.draft, rule }, () => { this.draft = emptyRule() })
    },
    setPermission (record, permission) {
      return this.mutate('updateProjectRolePermission', { projectrolepermissionid: record.id, permission })
    },
    deleteRule (record) {
      return this.mutate('deleteProjectRolePermission', { id: record.id })
    }
  }
}
</script>

<style scoped>
.project-rules { min-width: 0; }
.project-rule-form {
  display: grid;
  grid-template-columns: minmax(100px, 1fr) minmax(84px, 0.65fr) minmax(100px, 1fr);
  gap: 0 8px;
  align-items: start;
  margin-bottom: 16px;
}
.project-rule-form :deep(.ant-form-item) { margin-bottom: 8px; }
.project-rule-form > .ant-btn { justify-self: start; }
.project-rule-select { width: 100%; }
.project-rule-feedback { margin: 8px 0 0; color: var(--ui-text-secondary); }
.project-rule-feedback:empty { display: none; }
@media (max-width: 768px) { .project-rule-form { grid-template-columns: 1fr; } }
</style>
