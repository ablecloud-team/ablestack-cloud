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
  <div class="vm-schedules">
    <div class="schedule-toolbar">
      <a-button type="primary" :disabled="!allowed('create') || loading" @click="open('create')"><template #icon><plus-outlined /></template>{{ $t('label.schedule.add') }}</a-button>
      <a-button :loading="busy" @click="fetchSchedules"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search v-model:value="search" :placeholder="$t('label.search')" allow-clear @search="current = 1" @change="current = 1" />
    </div>
    <a-alert v-if="listRefreshFailed" class="schedule-spacing" type="warning" show-icon :message="$t('message.list.refresh.stale')" />
    <a-table :columns="columns" :data-source="visibleRows" row-key="id" :pagination="false" :loading="busy && !rows.length" :scroll="{ x: 850 }" size="small">
      <template #emptyText>{{ $t(search ? 'message.schedule.search.empty' : 'message.schedule.empty') }}</template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'action'">{{ actionName(record.action) }}</template>
        <template v-else-if="column.key === 'enabled'"><a-tag :color="record.enabled ? 'green' : undefined">{{ $t(record.enabled ? 'label.enabled' : 'label.disabled') }}</a-tag></template>
        <template v-else-if="column.key === 'description'"><span class="schedule-wrap">{{ record.description || '—' }}</span></template>
        <template v-else-if="column.key === 'schedule'"><span class="schedule-wrap">{{ human(record.schedule) }}</span><code class="schedule-note">{{ record.schedule }}</code></template>
        <template v-else-if="column.key === 'actions'">
          <div class="schedule-actions">
            <a-button type="link" size="small" :disabled="!allowed('edit')" @click="open('edit', record)">{{ $t('label.edit') }}</a-button>
            <a-dropdown :trigger="['click']">
              <a-button size="small" :aria-label="$t('label.actions')"><down-outlined /></a-button>
              <template #overlay><a-menu>
                <a-menu-item key="toggle" :disabled="!allowed('toggle')" @click="open('toggle', record)">{{ $t(record.enabled ? 'label.disable' : 'label.enable') }}</a-menu-item>
                <a-menu-item key="delete" danger :disabled="!allowed('delete')" @click="open('delete', record)">{{ $t('label.delete') }}</a-menu-item>
                <a-menu-divider />
                <a-menu-item key="details" @click="open('details', record)">{{ $t('label.details') }}</a-menu-item>
              </a-menu></template>
            </a-dropdown>
          </div>
        </template>
      </template>
    </a-table>
    <a-pagination v-model:current="current" v-model:pageSize="size" :total="filteredRows.length" :page-size-options="['10', '20', '50', '100']" show-size-changer class="schedule-pagination" @change="clampPage">
      <template #buildOptionText="props">{{ props.value }} / {{ $t('label.page') }}</template>
    </a-pagination>
    <a-modal :visible="!!mode" :title="modalTitle" :width="720" centered wrap-class-name="vm-schedule-modal" :mask-closable="false" :closable="!submitting" :keyboard="!submitting" :destroy-on-close="true" @cancel="close">
      <template #footer>
        <a-button :disabled="submitting" @click="close">{{ $t(mode === 'details' ? 'label.close' : 'label.cancel') }}</a-button>
        <a-button v-if="mode !== 'details'" type="primary" :danger="mode === 'delete'" :loading="submitting" @click="submit">{{ $t(mode === 'delete' ? 'label.delete' : 'label.ok') }}</a-button>
      </template>
      <a-alert v-if="submitError" class="schedule-spacing" type="error" show-icon :message="submitError" />
      <a-form v-if="mode === 'create' || mode === 'edit'" ref="editor" :model="form" :rules="rules" layout="vertical" @finish="submit">
        <a-form-item :label="$t('label.description')" name="description"><a-input v-model:value="form.description" :maxlength="255" /></a-form-item>
        <a-form-item :label="$t('label.action')" name="action">
          <a-select v-model:value="form.action" :disabled="mode === 'edit'"><a-select-option v-for="action in actions" :key="action" :value="action">{{ actionName(action) }}</a-select-option></a-select>
          <span v-if="form.action.startsWith('FORCE_')" class="schedule-note">{{ $t('message.schedule.force') }}</span>
        </a-form-item>
        <a-form-item :label="$t('label.timezone')" name="timezone">
          <a-select v-model:value="form.timezone" show-search option-filter-prop="label"><a-select-option v-for="zone in zones" :key="zone.id" :value="zone.id" :label="zone.name">{{ zone.name }}</a-select-option></a-select>
          <span class="schedule-note">{{ $t('message.schedule.timezone') }}</span>
        </a-form-item>
        <div class="schedule-dates">
          <a-form-item :label="$t('label.start.date.and.time')" name="startDate"><a-date-picker v-model:value="form.startDate" show-time format="YYYY-MM-DD HH:mm:ss" :allow-clear="mode !== 'edit'" :placeholder="$t('message.select.start.date.and.time')" /></a-form-item>
          <a-form-item :label="$t('label.end.date.and.time')" name="endDate"><a-date-picker v-model:value="form.endDate" show-time format="YYYY-MM-DD HH:mm:ss" :allow-clear="!selected?.enddate" :placeholder="$t('message.select.end.date.and.time')" /></a-form-item>
        </div>
        <p v-if="selected?.enddate" class="schedule-note schedule-spacing">{{ $t('message.schedule.enddate.keep') }}</p>
        <a-form-item :label="$t('label.schedule')" name="schedule">
          <div class="schedule-mode"><span>{{ $t('label.cron.mode') }}</span><a-switch v-model:checked="form.rawCron" :aria-label="$t('label.cron.mode')" /></div>
          <cron-ant v-if="!form.rawCron" v-model="form.schedule" :periods="periods" :button-props="{ type: 'default', size: 'small' }" />
          <a-input v-else v-model:value="form.schedule" :aria-label="$t('label.cron')" />
          <span class="schedule-note">{{ human(form.schedule) }}</span>
          <span class="schedule-note">{{ $t('message.schedule.cron.help') }}</span>
        </a-form-item>
        <a-form-item :label="$t('label.enabled')" name="enabled"><a-switch v-model:checked="form.enabled" :aria-label="$t('label.enabled')" /></a-form-item>
      </a-form>
      <template v-else-if="selected">
        <a-alert v-if="mode !== 'details'" class="schedule-spacing" :type="mode === 'delete' ? 'warning' : 'info'" show-icon :message="$t(mode === 'delete' ? 'message.schedule.delete.confirm' : selected.enabled ? 'message.schedule.disable.confirm' : 'message.schedule.enable.confirm')" />
        <a-descriptions bordered :column="1" size="small">
          <a-descriptions-item v-for="item in details" :key="item.label" :label="$t(item.label)">{{ item.value || '—' }}</a-descriptions-item>
        </a-descriptions>
      </template>
    </a-modal>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import { timeZone } from '@/utils/timezone'
import cronstrue from 'cronstrue/i18n'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
dayjs.extend(utc)
dayjs.extend(timezone)
const commands = { create: 'createResourceSchedule', edit: 'updateResourceSchedule', toggle: 'updateResourceSchedule', delete: 'deleteResourceSchedule' }
export default {
  name: 'VmSchedulesTab',
  mixins: [listRefreshMixin(['fetchSchedules'])],
  props: { resource: { type: Object, required: true }, loading: Boolean },
  data () {
    return {
      rows: [], busy: false, current: 1, size: 10, search: '', mode: '', selected: null, submitting: false, submitError: '', form: {}, zones: [],
      actions: ['START', 'STOP', 'REBOOT', 'FORCE_STOP', 'FORCE_REBOOT'],
      periods: [
        { id: 'year', value: ['month', 'day', 'dayOfWeek', 'hour', 'minute'] },
        { id: 'month', value: ['day', 'dayOfWeek', 'hour', 'minute'] },
        { id: 'week', value: ['dayOfWeek', 'hour', 'minute'] },
        { id: 'day', value: ['hour', 'minute'] }
      ]
    }
  },
  computed: {
    scopeKey () { return JSON.stringify([this.resource.id, this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state?.user?.token]) },
    columns () {
      return [
        { key: 'description', dataIndex: 'description', title: this.$t('label.description'), width: 200 },
        { key: 'action', title: this.$t('label.action'), width: 120 },
        { key: 'enabled', title: this.$t('label.state'), width: 100 },
        { key: 'schedule', title: this.$t('label.schedule'), width: 240 },
        { key: 'timezone', dataIndex: 'timezone', title: this.$t('label.timezone'), width: 140 },
        { key: 'actions', title: this.$t('label.actions'), fixed: 'right', width: 120 }
      ]
    },
    filteredRows () {
      const query = this.search.trim().toLocaleLowerCase()
      return this.rows.filter(row => [row.description, row.schedule, row.timezone, this.actionName(row.action), this.human(row.schedule), this.$t(row.enabled ? 'label.enabled' : 'label.disabled')].some(value => String(value || '').toLocaleLowerCase().includes(query)))
    },
    visibleRows () { return this.filteredRows.slice((this.current - 1) * this.size, this.current * this.size) },
    modalTitle () {
      const key = { create: 'label.schedule.add', edit: 'label.schedule.edit', delete: 'label.schedule.delete', details: 'label.schedule.details', toggle: this.selected?.enabled ? 'label.schedule.disable' : 'label.schedule.enable' }
      return this.$t(key[this.mode] || 'label.schedule')
    },
    rules () {
      return {
        action: [{ required: true, message: this.$t('message.error.required.input') }],
        timezone: [{ required: true, message: this.$t('message.error.required.input') }],
        schedule: [{ validator: async (_, value) => {
          if (!value || value.trim().split(/\s+/).length !== 5) throw new Error(this.$t('message.schedule.cron.invalid'))
          try { cronstrue.toString(value) } catch (_) { throw new Error(this.$t('message.schedule.cron.invalid')) }
        } }],
        endDate: [{ validator: async () => {
          if (this.form.startDate && this.form.endDate && !this.form.endDate.isAfter(this.form.startDate)) throw new Error(this.$t('message.schedule.date.invalid'))
        } }]
      }
    },
    details () {
      const row = this.selected || {}
      return [
        { label: 'label.description', value: row.description }, { label: 'label.action', value: this.actionName(row.action) },
        { label: 'label.state', value: this.$t(row.enabled ? 'label.enabled' : 'label.disabled') },
        { label: 'label.schedule', value: this.human(row.schedule) }, { label: 'label.cron', value: row.schedule },
        { label: 'label.timezone', value: row.timezone },
        { label: 'label.start.date.and.time', value: this.formatDate(row.startdate, row.timezone) },
        { label: 'label.end.date.and.time', value: this.formatDate(row.enddate, row.timezone) },
        { label: 'label.created', value: this.$toLocaleDate(row.created) }, { label: 'label.id', value: row.id }
      ]
    }
  },
  watch: {
    scopeKey () { this.rows = []; this.current = 1; this.search = ''; this.mode = ''; this.selected = null; this.fetchSchedules() }
  },
  created () { this.fetchSchedules(); timeZone().then(zones => { this.zones = zones }) },
  methods: {
    allowed (mode) { return mode === 'details' || commands[mode] in this.$store.getters.apis },
    actionName (action) { return this.$t('label.' + String(action || '').toLowerCase().replaceAll('_', '.')) },
    human (expression) {
      if (!expression) return '—'
      try { return cronstrue.toString(expression, { locale: this.$i18n.locale === 'ko_KR' ? 'ko' : this.$i18n.locale, verbose: true }) } catch (_) { return this.$t('message.schedule.cron.invalid') }
    },
    formatDate (value, zone) { return value ? dayjs(value).tz(zone || 'UTC').format('YYYY-MM-DD HH:mm:ss') : '' },
    clampPage () { this.current = Math.max(1, Math.min(this.current, Math.ceil(this.filteredRows.length / this.size))) },
    async fetchSchedules () {
      const request = this.listRequestToken('fetchSchedules')
      const scope = this.scopeKey
      if (!this.resource.id) return
      this.busy = true
      try {
        // The existing API does not implement keyword search. Fetch all scoped pages before publishing the list.
        const rows = []
        let page = 1
        let total = 0
        do {
          const json = await getAPI('listResourceSchedule', { resourceid: this.resource.id, resourcetype: 'VirtualMachine', listall: true, page, pagesize: 100 })
          if (scope !== this.scopeKey || !this.isListRequestCurrent('fetchSchedules', request)) return
          const result = json.listresourcescheduleresponse || {}
          const batch = result.resourceschedule || []
          total = result.count || 0
          if (!batch.length) break
          rows.push(...batch)
          page++
        } while (rows.length < total)
        this.rows = rows
        this.clampPage()
      } catch (error) {
        if (scope !== this.scopeKey || !this.isListRequestCurrent('fetchSchedules', request)) return
        request.failed = true
        this.listRefreshFailed = true
        if (!this.rows.length) this.$notifyError(error)
      } finally {
        if (scope === this.scopeKey && this.isListRequestCurrent('fetchSchedules', request)) this.busy = false
      }
    },
    open (mode, row = null) {
      if (!this.allowed(mode) || this.submitting) return
      this.submitError = ''
      this.selected = row ? { ...row } : null
      this.form = {
        description: row?.description || '', action: row?.action || 'START', schedule: row?.schedule || '0 0 * * *', timezone: row?.timezone || 'UTC',
        enabled: row ? row.enabled : true, rawCron: !!row,
        // API dates represent instants; date pickers represent wall clock time in the schedule's timezone.
        startDate: row?.startdate ? dayjs(this.formatDate(row.startdate, row.timezone)) : null,
        endDate: row?.enddate ? dayjs(this.formatDate(row.enddate, row.timezone)) : null
      }
      this.mode = mode
    },
    close () { if (!this.submitting) { this.mode = ''; this.selected = null; this.submitError = '' } },
    async submit () {
      const mode = this.mode
      if (this.submitting || !commands[mode] || !this.allowed(mode)) return
      const scope = this.scopeKey
      this.submitting = true
      this.submitError = ''
      try {
        let params
        if (mode === 'create' || mode === 'edit') {
          await this.$refs.editor.validate()
          if (scope !== this.scopeKey) return
          params = { description: this.form.description, schedule: this.form.schedule.trim(), timezone: this.form.timezone, enabled: this.form.enabled }
          if (mode === 'edit') params.id = this.selected.id
          else Object.assign(params, { resourceid: this.resource.id, resourcetype: 'VirtualMachine', action: this.form.action })
          // Omit unchanged dates when editing, including a historical start date. Null cannot clear enddate in the existing API.
          for (const [field, key] of [['startDate', 'startdate'], ['endDate', 'enddate']]) {
            const value = this.form[field]?.format('YYYY-MM-DD HH:mm:ss')
            if (value && (mode === 'create' || value !== this.formatDate(this.selected[key], this.selected.timezone) || this.form.timezone !== this.selected.timezone)) params[key] = value
          }
        } else {
          params = { id: this.selected.id }
          if (mode === 'toggle') params.enabled = !this.selected.enabled
        }
        await postAPI(commands[mode], params)
        if (scope !== this.scopeKey) return
        this.mode = ''; this.selected = null
        this.$message.success(this.$t('message.success.config.resource.schedule'))
        await this.fetchSchedules()
      } catch (error) {
        if (scope !== this.scopeKey) return
        if (error.errorFields) this.$refs.editor?.scrollToField(error.errorFields[0].name)
        else { this.submitError = error?.response?.data?.errorresponse?.errortext || error.message || this.$t('message.schedule.operation.failed'); this.$notifyError(error) }
      } finally { this.submitting = false }
    }
  }
}
</script>

<style lang="scss" scoped>
.schedule-toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }
.schedule-toolbar :deep(.ant-input-search) { margin-left: auto; width: 280px; }
.schedule-actions { display: flex; align-items: center; gap: 4px; white-space: nowrap; }
.schedule-pagination { margin-top: 20px; text-align: right; }
.schedule-wrap { overflow-wrap: anywhere; }
@media (max-width: 768px) { .schedule-toolbar :deep(.ant-input-search) { width: 100%; } }
</style>
<style lang="scss">
.schedule-note { display: block; margin-top: 6px; color: var(--ui-text-secondary); overflow-wrap: anywhere; }
.schedule-spacing { margin-bottom: 16px; }
.vm-schedule-modal {
  .ant-modal { top: 0; padding-bottom: 0; max-width: calc(100vw - 32px); }
  .ant-modal-content { display: flex; flex-direction: column; overflow: hidden; max-height: calc(100vh - 48px); max-height: calc(100dvh - 48px); }
  .ant-modal-header, .ant-modal-footer { flex-shrink: 0; }
  .ant-modal-body { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 24px; }
  .ant-modal-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 24px; }
  .ant-modal-footer .ant-btn + .ant-btn { margin-left: 0; }
  .ant-modal-title { padding-right: 24px; }
  .schedule-dates { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
  .ant-picker { width: 100%; }
  .schedule-mode { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
  .ant-descriptions-item-content { overflow-wrap: anywhere; color: var(--ui-text-primary); }
  .ant-descriptions-bordered .ant-descriptions-item-label { width: 160px; background: var(--ui-bg-page); color: var(--ui-text-secondary); }
  .ant-descriptions-bordered .ant-descriptions-view, .ant-descriptions-bordered .ant-descriptions-row, .ant-descriptions-bordered .ant-descriptions-item-label, .ant-descriptions-bordered .ant-descriptions-item-content { border-color: var(--ui-border); }
  label, .ant-form-item-label > label, .vcron { color: var(--ui-text-primary); }
  input::placeholder { color: var(--ui-text-secondary); }
  @media (max-width: 600px) { .ant-modal-body { padding: 16px; } .ant-modal-footer { padding: 12px 16px; } .schedule-dates { grid-template-columns: 1fr; gap: 0; } }
}
</style>
