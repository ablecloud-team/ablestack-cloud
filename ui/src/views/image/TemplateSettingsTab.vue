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
  <div class="template-settings-tab">
    <div class="template-tab-toolbar">
      <a-button v-if="canWrite" type="primary" :disabled="!loaded || !!readError || optionsFailed || busy || !!dialog" @click="open('add', null, $event)"><template #icon><plus-outlined /></template>{{ $t('label.add.setting') }}</a-button>
      <a-button :loading="fetching" :disabled="busy || !!dialog" @click="refresh"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
      <a-input-search v-model:value="search" :placeholder="$t('label.vmsettings.search')" @change="page = 1" />
    </div>
    <a-alert v-if="readError || optionsFailed" type="warning" show-icon :message="$t(readError || 'message.template.settings.options.failed')" />
    <a-table size="small" :columns="columns" :data-source="visibleRows" :loading="fetching && !loaded" :pagination="false" row-key="name" :scroll="{x:700}">
      <template #emptyText>{{ $t(readError ? 'error.fetching.data' : search ? 'message.template.settings.no.results' : 'label.no.data') }}</template>
      <template #bodyCell="{column,record}">
        <a-tooltip v-if="['name','value'].includes(column.key)" :title="record[column.key]"><span class="template-cell-value">{{ record[column.key] }}</span></a-tooltip>
        <span v-else-if="column.key==='access'">{{ $t(restriction(record.name) || !canWrite ? 'label.vmsettings.unavailable' : 'label.vmsettings.editable') }}</span>
        <div v-else-if="column.key==='actions'" class="template-row-actions">
          <a-button v-if="canWrite" size="small" type="link" :disabled="!editable(record.name)" @click="open('edit',record,$event)">{{ $t('label.edit') }}</a-button>
          <a-dropdown :trigger="['click']" placement="bottomRight"><a-button size="small" :disabled="busy" :aria-label="$t('label.actions')" @click="trigger=$event.currentTarget"><down-outlined /></a-button><template #overlay><a-menu>
            <a-menu-item v-if="canWrite" key="delete" danger :disabled="!editable(record.name)" @click="open('delete',record)">{{ $t('label.delete') }}</a-menu-item>
            <a-menu-item key="details" @click="open('details',record)">{{ $t('label.details') }}</a-menu-item>
          </a-menu></template></a-dropdown>
        </div>
      </template>
    </a-table>
    <div class="template-tab-pagination"><a-pagination v-model:current="page" v-model:page-size="pageSize" :total="rows.length" show-size-changer :page-size-options="['10','20','50']" /></div>
    <MoldDialog
v-if="dialog"
class="template-settings-dialog"
:visible="!!dialog"
:title="$t(dialog==='add'?'label.add.setting':dialog==='edit'?'label.edit.setting':dialog==='delete'?'label.delete.setting':'label.details')"
size="md"
focus-guard
form-actions
:closable="!busy"
:keyboard="!busy"
:focus-trigger-after-close="false"
@cancel="close">
      <dl class="template-dialog-context"><dt>{{ $t('label.template') }}</dt><dd>{{ source.name || resource.name }}<br>{{ resource.id }}</dd></dl>
      <a-alert v-if="dialogError" type="error" show-icon role="alert" :message="$t(dialogError)" />
      <a-form v-if="['add','edit'].includes(dialog)" layout="vertical" @keydown.ctrl.enter.stop.prevent="save">
        <a-form-item :label="$t('label.name')" html-for="template-setting-name" required><a-auto-complete v-if="dialog==='add'" id="template-setting-name" v-model:value="draftName" :options="keyOptions" :disabled="busy" :filter-option="filterOption" /><a-input v-else id="template-setting-name" :value="draftName" readonly /></a-form-item>
        <a-form-item :label="$t('label.value')" html-for="template-setting-value" required><a-auto-complete id="template-setting-value" v-model:value="draftValue" :options="valueOptions" :disabled="busy" :filter-option="filterOption" /></a-form-item>
      </a-form>
      <a-descriptions v-else :column="1" bordered size="small"><a-descriptions-item :label="$t('label.name')">{{ draftName }}</a-descriptions-item><a-descriptions-item :label="$t('label.value')">{{ draftValue }}</a-descriptions-item></a-descriptions>
      <a-alert v-if="dialog==='delete'" type="warning" show-icon :message="$t('label.delete.setting') + ': ' + draftName" />
      <MoldDialogActions :busy="busy">
        <a-button @click="close">{{ $t(dialog==='details'?'label.close':'label.cancel') }}</a-button>
        <a-button v-if="unknownOutcome" :loading="busy" @click="checkOutcome">{{ $t('label.refresh') }}</a-button>
        <a-button v-else-if="dialog!=='details'" type="primary" :danger="dialog==='delete'" :disabled="!canWrite || !!readError || optionsFailed" :loading="busy" @click="save">{{ $t('label.ok') }}</a-button>
      </MoldDialogActions>
    </MoldDialog>
  </div>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { readTemplateResource } from '@/utils/templateSearch'
import { templateSettingsCanWrite, templateSettingRestriction, templateSettingsFingerprint, templateSettingsParams } from '@/utils/templateSettings'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
export default {
  name: 'TemplateSettingsTab',
  components: { MoldDialog, MoldDialogActions },
  props: { resource: { type: Object, required: true }, loading: Boolean },
  emits: ['refresh'],
  data () { return { source: {}, options: {}, loaded: false, fetching: false, readError: '', optionsFailed: false, busy: false, dialog: '', draftName: '', draftValue: '', dialogError: '', unknownOutcome: false, submittedParams: null, baseline: '', search: '', page: 1, pageSize: 10, epoch: 0, trigger: null } },
  computed: {
    scope () { const g = this.$store.getters; return JSON.stringify([this.resource.id, g.userInfo?.id, g.userInfo?.roleid, g.userInfo?.roletype, g.project?.id, g.listAllProjects, this.$store.state?.user?.token, Object.keys(g.apis || {}).sort()]) },
    canWrite () { return templateSettingsCanWrite(this.source.id ? this.source : this.resource, this.$store.getters) },
    columns () { return [{ key: 'name', dataIndex: 'name', title: this.$t('label.name'), width: 220 }, { key: 'value', dataIndex: 'value', title: this.$t('label.value') }, { key: 'access', title: this.$t('label.vmsettings.access'), width: 140 }, { key: 'actions', title: this.$t('label.actions'), width: 140 }] },
    rows () { const q = this.search.toLowerCase(); return Object.entries(this.source.details || {}).map(([name, value]) => ({ name, value: String(value) })).filter(r => [r.name, r.value].some(v => v.toLowerCase().includes(q))).sort((a, b) => a.name.localeCompare(b.name)) },
    visibleRows () { return this.rows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize) },
    keyOptions () { return Object.keys(this.options).map(value => ({ value })).filter(o => !this.restriction(o.value)) },
    valueOptions () { const v = this.options[this.draftName]; return (Array.isArray(v) ? v : v == null ? [] : [v]).map(value => ({ value: String(value) })) }
  },
  watch: { scope () { this.invalidate(); this.refresh() }, pageSize () { this.page = 1 } },
  created () { this.refresh() },
  activated () { this.refresh() },
  deactivated () { this.invalidate() },
  beforeUnmount () { this.invalidate() },
  methods: {
    filterOption (input, option) { return option.value.toLowerCase().includes(input.toLowerCase()) },
    restriction (name) { return templateSettingRestriction(this.source, name) },
    editable (name) { return this.canWrite && this.loaded && !this.readError && !this.optionsFailed && !this.busy && !this.dialog && !this.restriction(name) },
    invalidate () { this.epoch++; this.source = {}; this.options = {}; this.loaded = false; this.fetching = false; this.busy = false; this.dialog = ''; this.draftName = this.draftValue = this.dialogError = ''; this.unknownOutcome = false; this.submittedParams = null; this.trigger = null; this.search = ''; this.page = 1 },
    async read () { const scope = this.scope; const id = this.resource.id; const json = await readTemplateResource(getAPI, { id }, this.$store.getters, () => scope === this.scope); const row = json.listtemplatesresponse?.template?.find(r => r.id === this.resource.id); if (!row) throw new Error('missing'); return { ...row, details: { ...(row.details || {}) } } },
    async refresh () {
      if (this.fetching || this.busy || this.dialog || !this.resource.id) return
      const epoch = ++this.epoch; const scope = this.scope; this.fetching = true; this.readError = ''
      try {
        const row = await this.read(); if (epoch !== this.epoch || scope !== this.scope) return
        this.source = row; this.loaded = true; this.optionsFailed = false
        if ('listDetailOptions' in (this.$store.getters.apis || {})) {
          try { const json = await getAPI('listDetailOptions', { resourcetype: 'Template', resourceid: row.id }, { preserveOnFailure: true, timeout: 15000 }); if (epoch === this.epoch && scope === this.scope) { const options = json.listdetailoptionsresponse?.detailoptions?.details; if (!options || typeof options !== 'object' || Array.isArray(options)) throw new Error('options'); this.options = options } } catch (e) { if (epoch === this.epoch && scope === this.scope) { this.options = {}; this.optionsFailed = true } }
        }
      } catch (e) { if (epoch === this.epoch && scope === this.scope) this.readError = 'error.fetching.data' } finally { if (epoch === this.epoch && scope === this.scope) this.fetching = false }
    },
    open (kind, row, event) { if (this.busy || this.dialog || !this.loaded || (kind !== 'details' && (!this.canWrite || this.readError || this.optionsFailed || this.restriction(row?.name || '')))) return; this.trigger = event?.currentTarget || (this.trigger?.isConnected ? this.trigger : document.activeElement); this.dialog = kind; this.draftName = row?.name || ''; this.draftValue = row?.value || ''; this.baseline = templateSettingsFingerprint(this.source); this.dialogError = ''; this.unknownOutcome = false; this.submittedParams = null },
    close () { if (this.busy) return; const target = this.trigger; const scope = this.scope; this.dialog = ''; this.draftName = this.draftValue = this.dialogError = ''; this.unknownOutcome = false; this.submittedParams = null; this.trigger = null; this.$nextTick(() => { if (scope === this.scope && target?.isConnected && !target.disabled && target.getClientRects().length)target.focus({ preventScroll: true }) }) },
    async save () {
      if (this.busy || !this.canWrite || !this.dialog || this.dialog === 'details' || this.unknownOutcome || this.readError || this.optionsFailed) return
      let params; try { params = templateSettingsParams(this.source, this.dialog, this.draftName, this.draftValue) } catch (e) { this.dialogError = 'message.template.settings.' + e.message; return }
      const epoch = this.epoch; const scope = this.scope; const current = () => epoch === this.epoch && scope === this.scope; this.busy = true; this.dialogError = ''
      try {
        const latest = await this.read(); if (!current()) return
        if (!templateSettingsCanWrite(latest, this.$store.getters) || templateSettingsFingerprint(latest) !== this.baseline) { this.dialogError = 'message.template.settings.changed'; return }
        this.submittedParams = params
        const json = await postAPI('updateTemplate', params, { preserveOnFailure: true, timeout: 30000 }); if (!current()) return
        const row = json.updatetemplateresponse?.template
        if (!row || row.id !== this.resource.id) { this.unknownOutcome = true; this.dialogError = 'message.template.settings.unknown'; return }
        this.source = { ...this.source, ...row, details: { ...(row.details || {}) } }; this.loaded = true; this.busy = false; this.close(); this.$emit('refresh')
      } catch (e) { if (current()) { this.unknownOutcome = !!this.submittedParams && (!e.response || Number(e.response.status) >= 500); this.dialogError = this.unknownOutcome ? 'message.template.settings.unknown' : 'message.template.settings.failed' } } finally { if (current()) this.busy = false }
    },
    async checkOutcome () {
      if (this.busy || !this.unknownOutcome) return
      const epoch = this.epoch; const scope = this.scope; this.busy = true
      try { const latest = await this.read(); if (epoch !== this.epoch || scope !== this.scope) return; const expected = Object.fromEntries(Object.entries(this.submittedParams || {}).filter(([k]) => k.startsWith('details[0].')).map(([k, v]) => [k.slice(11), v])); const applied = templateSettingsFingerprint({ ...latest, details: expected }) === templateSettingsFingerprint(latest); this.source = latest; this.loaded = true; if (applied) { this.busy = false; this.close(); this.$emit('refresh') } else if (templateSettingsFingerprint(latest) === this.baseline) { this.unknownOutcome = false; this.dialogError = 'message.template.settings.not.applied' } else this.dialogError = 'message.template.settings.changed' } catch (e) { if (epoch === this.epoch && scope === this.scope) this.dialogError = 'message.template.settings.unknown' } finally { if (epoch === this.epoch && scope === this.scope) this.busy = false }
    }
  }
}
</script>
