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
  <div class="vm-settings">
    <div class="settings-toolbar">
      <a-tooltip :title="blockReason"><span v-if="canEdit"><a-button type="primary" :disabled="!!blockReason" @click="open('add')"><template #icon><plus-outlined /></template>{{ s('add') }}</a-button></span></a-tooltip>
      <a-button :loading="loading" :disabled="submitting" @click="refresh"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search v-model:value="search" :placeholder="s('search')" @change="page = 1" />
    </div>
    <a-alert v-if="error || blockReason" show-icon :type="error ? 'warning' : 'info'" :message="error || blockReason" />
    <a-table :columns="columns" :data-source="visibleRows" row-key="name" :loading="loading" :pagination="false" :scroll="{ x: 700 }" size="small">
      <template #emptyText>{{ s(error ? 'loadFailed' : search ? 'noResults' : 'empty') }}</template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name' || column.key === 'value'"><a-tooltip :title="record[column.key]"><span class="setting-value">{{ record[column.key] }}</span></a-tooltip></template>
        <template v-if="column.key === 'access'"><a-tooltip :title="reason(record.name)"><span>{{ s(reason(record.name) ? 'unavailable' : 'editable') }}</span></a-tooltip></template>
        <template v-if="column.key === 'actions'"><div class="settings-actions">
          <a-tooltip v-if="canEdit" :title="reason(record.name)"><span><a-button type="link" size="small" :disabled="!!reason(record.name)" @click="open('edit', record)">{{ $t('label.edit') }}</a-button></span></a-tooltip>
          <a-dropdown :trigger="['click']" placement="bottomRight"><a-button size="small" :aria-label="$t('label.actions')"><down-outlined /></a-button><template #overlay><a-menu>
            <a-menu-item v-if="canEdit" key="delete" danger :disabled="!!reason(record.name)" @click="open('delete', record)">{{ s('delete') }}</a-menu-item>
            <a-menu-divider v-if="canEdit" />
            <a-menu-item key="details" @click="open('details', record)">{{ $t('label.details') }}</a-menu-item>
          </a-menu></template></a-dropdown>
        </div></template>
      </template>
    </a-table>
    <div class="settings-pagination"><a-pagination v-model:current="page" v-model:page-size="pageSize" :total="rows.length" show-size-changer :page-size-options="['10', '20', '50']" /></div>
    <p class="settings-help">{{ s('help') }}</p>
    <a-modal
      :visible="!!dialog"
      :title="s(dialog || 'details')"
      :width="720"
      centered
      wrap-class-name="vm-settings-dialog"
      :mask-closable="false"
      :closable="!submitting"
      :keyboard="!submitting"
      @cancel="close">
      <a-descriptions :column="2" bordered size="small"><a-descriptions-item :label="$t('label.virtualmachine')">{{ vm.displayname || vm.name }}</a-descriptions-item><a-descriptions-item :label="$t('label.state')">{{ vm.state }}</a-descriptions-item></a-descriptions>
      <a-alert v-if="dialogError" type="error" show-icon :message="dialogError" />
      <template v-if="dialog === 'add' || dialog === 'edit'">
        <a-form layout="vertical">
          <a-form-item :label="s('name')" required><a-auto-complete v-if="dialog === 'add'" v-model:value="draftName" :options="keyOptions" :filter-option="filterOption" :disabled="submitting" /><a-input v-else :value="draftName" disabled /></a-form-item>
          <a-form-item :label="$t('label.value')" required><a-auto-complete v-model:value="draftValue" :options="valueOptions" :filter-option="filterOption" :disabled="submitting" /></a-form-item>
          <p v-if="dialog === 'edit'" class="settings-help">{{ s('previous') }}: {{ selected.value }}</p>
          <template v-if="dialog === 'add' && draftName === 'video.hardware'">
            <a-form-item :label="s('videoCount')"><a-input-number v-model:value="videoCount" :min="1" :max="4" :precision="0" :disabled="submitting" /></a-form-item>
            <a-alert type="warning" show-icon :message="s('videoWarning')" />
            <div v-for="i in previewCount" :key="i" class="settings-help">{{ i }}: video.hardware{{ i > 1 ? i : '' }} = {{ draftValue || '—' }} · video.ram{{ i > 1 ? i : '' }} = 16384</div>
          </template>
          <p class="settings-help">{{ s('submitHelp') }}</p>
        </a-form>
      </template>
      <template v-else-if="selected">
        <a-descriptions :column="1" bordered size="small"><a-descriptions-item :label="s('name')">{{ selected.name }}</a-descriptions-item><a-descriptions-item :label="$t('label.value')">{{ selected.value }}</a-descriptions-item><a-descriptions-item v-if="selected.original" :label="s('original')"><div v-for="(value, key) in selected.original" :key="key">{{ key }} = {{ value }}</div></a-descriptions-item><a-descriptions-item v-if="dialog === 'details'" :label="s('access')">{{ reason(selected.name) || s('editable') }}</a-descriptions-item></a-descriptions>
        <a-alert v-if="dialog === 'delete'" type="warning" show-icon :message="s('deleteWarning')" />
      </template>
      <template #footer><a-button :disabled="submitting" @click="close">{{ $t(dialog === 'details' ? 'label.close' : 'label.cancel') }}</a-button><a-button v-if="dialog !== 'details'" type="primary" :danger="dialog === 'delete'" :loading="submitting" :disabled="submitDisabled" @click="submit">{{ s(dialog === 'edit' ? 'save' : dialog || 'add') }}</a-button></template>
    </a-modal>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { settingRestriction, settingRows, settingsFingerprint, settingsParams } from '@/utils/vmSettings'
export default {
  name: 'VmSettingsTab',
  props: { resource: { type: Object, required: true }, active: Boolean },
  data () { return { vm: {}, template: null, options: {}, loaded: false, loading: false, error: '', search: '', page: 1, pageSize: 10, dialog: '', selected: null, draftName: '', draftValue: '', videoCount: 1, baseline: '', submitting: false, dialogError: '', revision: 0 } },
  computed: {
    canEdit () {
      const user = this.$store.getters.userInfo || {}
      return 'updateVirtualMachine' in this.$store.getters.apis && (user.roletype === 'Admin' || (this.vm.domainid === user.domainid && this.vm.account === user.account) || (this.vm.projectid && this.vm.projectid === this.$store.getters.project?.id))
    },
    blockReason () { return !this.loaded || this.loading || this.error ? this.s('verify') : !this.canEdit ? this.s('permission') : this.vm.state !== 'Stopped' ? this.s('stopped') : '' },
    columns () { return [{ key: 'name', title: this.s('name'), width: 220 }, { key: 'value', title: this.$t('label.value') }, { key: 'access', title: this.s('access'), width: 140 }, { key: 'actions', title: this.$t('label.actions'), width: 140 }] },
    rows () { const query = this.search.toLowerCase(); return settingRows(this.vm).filter(r => [r.name, r.value].some(v => v.toLowerCase().includes(query))) },
    visibleRows () { return this.rows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize) },
    keyOptions () { return Object.keys(this.options).map(value => ({ value })) },
    valueOptions () { const values = this.options[this.draftName]; return (Array.isArray(values) ? values : values == null ? [] : [values]).map(value => ({ value: String(value) })) },
    previewCount () { return Number.isInteger(this.videoCount) ? Math.max(0, Math.min(4, this.videoCount)) : 0 },
    submitDisabled () { return this.submitting || !!this.blockReason || (this.dialog !== 'delete' && (!this.draftName.trim() || !this.draftValue.trim())) || !!(this.draftName && settingRestriction(this.vm, this.template, this.draftName)) }
  },
  watch: {
    active: { immediate: true, handler (active) { if (active) this.refresh() } },
    'resource.id' () { this.revision++; this.dialog = ''; this.vm = {}; this.loaded = false; this.search = ''; this.page = 1; if (this.active) this.refresh() },
    'resource.state' () { if (this.active && !this.submitting) this.refresh() },
    pageSize () { this.page = 1 }
  },
  beforeUnmount () { this.revision++ },
  methods: {
    s (key) { return this.$t('label.vmsettings.' + key) },
    filterOption (input, option) { return option.value.toLowerCase().includes(input.toLowerCase()) },
    reason (name) { const key = settingRestriction(this.vm, this.template, name); return this.blockReason || (key ? this.s(key) : '') },
    async fetchState (id) {
      const response = await getAPI('listVirtualMachines', { id, details: 'all' })
      const vm = response.listvirtualmachinesresponse?.virtualmachine?.[0]
      if (!vm || vm.id !== id) throw new Error('loadFailed')
      const [optionResponse, templateResponse] = await Promise.all([getAPI('listDetailOptions', { resourcetype: 'UserVm', resourceid: id }), vm.templateid && vm.templateformat !== 'ISO' ? getAPI('listTemplates', { templatefilter: 'all', id: vm.templateid }) : Promise.resolve(null)])
      const options = optionResponse.listdetailoptionsresponse?.detailoptions?.details
      const template = templateResponse?.listtemplatesresponse?.template?.[0] || null
      if (!options || (templateResponse && !template)) throw new Error('loadFailed')
      return { vm, options, template }
    },
    async refresh () {
      const revision = ++this.revision
      const id = this.resource.id
      this.loading = true
      try {
        const state = await this.fetchState(id)
        if (revision !== this.revision || this.resource.id !== id) return
        Object.assign(this, state); this.loaded = true; this.error = ''
        this.page = Math.min(this.page, Math.max(1, Math.ceil(this.rows.length / this.pageSize)))
      } catch (e) { if (revision === this.revision) this.error = this.s('loadFailed') } finally { if (revision === this.revision) this.loading = false }
    },
    open (dialog, row) {
      if (dialog !== 'details' && (this.blockReason || (row && this.reason(row.name)))) return
      this.dialog = dialog; this.selected = row ? { ...row } : null; this.draftName = row?.name || ''; this.draftValue = row?.value || ''; this.videoCount = 1; this.dialogError = ''; this.baseline = settingsFingerprint(this.vm)
    },
    close () { if (!this.submitting) this.dialog = '' },
    async submit () {
      if (this.submitDisabled) return
      const id = this.resource.id
      const revision = ++this.revision
      this.submitting = true; this.dialogError = ''
      let saved = false
      try {
        const state = await this.fetchState(id)
        if (revision !== this.revision || id !== this.resource.id) return
        Object.assign(this, state); this.loaded = true; this.error = ''
        if (this.blockReason) { this.dialogError = this.blockReason; return }
        if (settingsFingerprint(this.vm) !== this.baseline) throw new Error('changed')
        const params = settingsParams(this.vm, this.template, this.dialog, this.draftName.trim(), this.draftValue, this.videoCount, this.$store.getters.userInfo.roletype === 'Admin')
        await postAPI('updateVirtualMachine', params)
        saved = true
        if (revision !== this.revision || id !== this.resource.id) return
        this.dialog = ''; this.$message.success(this.s('success'))
      } catch (e) {
        if (revision === this.revision && id === this.resource.id) {
          const known = ['loadFailed', 'required', 'duplicate', 'count', 'protected', 'tpm', 'readonly', 'template', 'cleanupBlocked', 'changed']
          this.dialogError = known.includes(e.message) ? this.s(e.message) : (Object.values(e.response?.data || {}).find(v => v?.errortext)?.errortext || this.s('failed'))
        }
      } finally {
        this.submitting = false
        if (saved && id === this.resource.id) await this.refresh()
      }
    }
  }
}
</script>

<style lang="scss">
.vm-settings {
  color: var(--ui-text-primary);
  .settings-toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 20px; }
  .settings-toolbar > span, .settings-toolbar > .ant-btn { flex-shrink: 0; }
  .settings-toolbar .ant-input-search { width: 260px; max-width: 100%; margin-left: auto; }
  .settings-actions { display: flex; gap: 8px; align-items: center; white-space: nowrap; }
  .setting-value { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
  .settings-pagination { display: flex; justify-content: flex-end; margin-top: 20px; }
  .ant-pagination-item-link { background: var(--ui-bg-surface) !important; border-color: var(--ui-border) !important; color: var(--ui-text-primary) !important; }
  .ant-pagination-disabled .ant-pagination-item-link { color: var(--ui-text-secondary) !important; opacity: 0.55; }
  .ant-alert { margin: 12px 0; }
}
.settings-help { color: var(--ui-text-secondary); line-height: 1.6; margin: 12px 0; overflow-wrap: anywhere; }
.vm-settings-dialog {
  .ant-modal { padding-bottom: 0; max-width: calc(100vw - 32px); }
  .ant-modal-content { display: flex; flex-direction: column; max-height: calc(100dvh - 48px); background: var(--ui-bg-surface); color: var(--ui-text-primary); }
  .ant-modal-header, .ant-modal-footer { flex: none; background: var(--ui-bg-surface); border-color: var(--ui-border); }
  .ant-modal-title { padding-right: 24px; overflow-wrap: anywhere; }
  .ant-modal-title, .ant-modal-close, .ant-form-item-label > label { color: var(--ui-text-primary); }
  .ant-modal-body { overflow-y: auto; min-height: 0; }
  .ant-form, .ant-alert { margin-top: 20px; }
  .ant-select, .ant-input-number { width: 100%; }
  .ant-descriptions { margin-bottom: 20px; }
  .ant-descriptions-item-label { background: var(--ui-bg-page) !important; color: var(--ui-text-primary) !important; }
  .ant-descriptions-item-content { background: var(--ui-bg-surface) !important; color: var(--ui-text-secondary) !important; overflow-wrap: anywhere; white-space: pre-wrap; }
  .ant-descriptions-view, .ant-descriptions-row, .ant-descriptions-item-label, .ant-descriptions-item-content { border-color: var(--ui-border) !important; }
}
</style>
