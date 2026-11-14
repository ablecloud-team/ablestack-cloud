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
  <div class="vnf-settings">
    <a-alert v-if="saveError" type="error" show-icon role="alert" :message="saveError" />
    <section class="template-tab-section" :aria-label="$t('label.vnf.nics')">
      <h5 class="template-tab-section-title">{{ $t('label.vnf.nics') }}</h5>
      <div class="template-tab-toolbar">
        <a-button v-if="canWrite" type="primary" :disabled="updateBusy || editorOpen" @click="onShowAddVnfNic"><template #icon><plus-outlined /></template>{{ $t('label.vnf.nic.add') }}</a-button>
        <a-button :disabled="updateBusy || editorOpen" :loading="loading" @click="refreshView"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
        <a-input-search v-model:value="nicSearch" :placeholder="$t('label.template.vnf.nic.search')" @change="nicPage=1" />
      </div>
      <a-table size="small" :loading="loading" :columns="columns" :data-source="visibleNics" :pagination="false" :row-key="record=>record.deviceid" :scroll="{x:850}">
        <template #required="{record}">{{ $t(record.required?'label.yes':'label.no') }}</template>
        <template #management="{record}">{{ $t(record.management?'label.yes':'label.no') }}</template>
        <template #bodyCell="{column,record}"><a-tooltip v-if="['name','description'].includes(column.key)" :title="record[column.key]"><span class="template-cell-value">{{ record[column.key] || '—' }}</span></a-tooltip></template>
        <template #actions="{record}"><div v-if="canWrite" class="template-row-actions">
          <a-button type="link" size="small" :disabled="updateBusy || editorOpen" @click="onShowEditVnfNic(record,$event)">{{ $t('label.edit') }}</a-button>
          <tooltip-button :tooltip="$t(nicSearch?'message.template.vnf.move.filtered':canMove(record,-1)?'label.move.up.row':'message.vnf.nic.move.boundary')" icon="caret-up-outlined" :disabled="updateBusy || editorOpen || !canMove(record,-1)" @onClick="moveVnfNicUp(record)" />
          <tooltip-button :tooltip="$t(nicSearch?'message.template.vnf.move.filtered':canMove(record,1)?'label.move.down.row':'message.vnf.nic.move.boundary')" icon="caret-down-outlined" :disabled="updateBusy || editorOpen || !canMove(record,1)" @onClick="moveVnfNicDown(record)" />
          <a-popconfirm :title="$t('message.vnf.nic.delete.confirm',{name:record.name,id:record.deviceid})" :disabled="updateBusy || editorOpen" :ok-text="$t('label.yes')" :cancel-text="$t('label.no')" @confirm="deleteVnfNic(record)"><tooltip-button :tooltip="$t('label.vnf.nic.delete')" icon="delete-outlined" :danger="true" :disabled="updateBusy || editorOpen" /></a-popconfirm>
        </div></template>
      </a-table>
      <div class="template-tab-pagination"><a-pagination v-model:current="nicPage" v-model:page-size="nicPageSize" :total="filteredNics.length" show-size-changer :page-size-options="['10','20','50']" /></div>
    </section>
    <MoldDialog
class="vnf-template-nic-dialog"
size="md"
focus-guard
:visible="showAddVnfNic || showEditVnfNic"
:title="$t(showEditVnfNic ? 'label.vnf.nic.edit' : 'label.vnf.nic.add')"
:closable="!updateBusy"
:keyboard="!updateBusy"
:focus-trigger-after-close="false"
destroy-on-close
:after-close="afterVnfNicClose"
@cancel="closeVnfNicModal">
      <dl class="vnf-context"><dt>{{ $t('label.template') }}</dt><dd>{{ resource.name }}<br>{{ resource.id }}</dd><dt>{{ $t(resource.projectid ? 'label.project' : 'label.account') }}</dt><dd>{{ resource.project || resource.account }}</dd></dl>
      <a-form layout="vertical" @keydown.ctrl.enter.stop.prevent="submitVnfNic">
        <a-form-item :label="$t('label.deviceid')" html-for="vnf-nic-deviceid" required :extra="$t('message.vnf.nic.ids.hint')"><a-input id="vnf-nic-deviceid" v-model:value="nicDraft.deviceid" type="number" min="0" step="1" :disabled="showEditVnfNic || updateBusy" v-focus="true" /></a-form-item>
        <a-form-item :label="$t('label.name')" html-for="vnf-nic-name" required><a-input id="vnf-nic-name" v-model:value="nicDraft.name" :disabled="updateBusy" v-focus="showEditVnfNic" /></a-form-item>
        <a-form-item :label="$t('label.required')" html-for="vnf-nic-required"><a-switch id="vnf-nic-required" :aria-label="$t('label.required')" v-model:checked="nicDraft.required" :disabled="updateBusy" /></a-form-item>
        <a-form-item :label="$t('label.vnf.nic.management')" html-for="vnf-nic-management"><a-switch id="vnf-nic-management" :aria-label="$t('label.vnf.nic.management')" v-model:checked="nicDraft.management" :disabled="updateBusy" /></a-form-item>
        <a-form-item :label="$t('label.description')" html-for="vnf-nic-description"><a-textarea id="vnf-nic-description" v-model:value="nicDraft.description" :rows="2" :disabled="updateBusy" /></a-form-item>
        <a-alert v-if="nicError" type="error" show-icon role="alert" :message="nicError" />
        <MoldDialogActions :busy="updateBusy"><a-button @click="closeVnfNicModal">{{ $t('label.cancel') }}</a-button><a-button type="primary" :loading="updateBusy" :disabled="!canWrite" @click="submitVnfNic">{{ $t('label.ok') }}</a-button></MoldDialogActions>
      </a-form>
    </MoldDialog>
    <section class="template-tab-section" :aria-label="$t('label.vnf.details')">
      <h5 class="template-tab-section-title">{{ $t('label.vnf.details') }}</h5>
      <a-alert v-if="optionsError" type="warning" show-icon :message="$t('message.vnf.detail.options.failed')"><template #description><a-button size="small" :loading="optionsLoading" @click="loadOptions">{{ $t('label.retry') }}</a-button></template></a-alert>
      <div class="template-tab-toolbar">
        <a-button v-if="canWrite" type="primary" :disabled="updateBusy || editorOpen || !optionsReady" @click="onShowAddVnfDetail"><template #icon><plus-outlined /></template>{{ $t('label.vnf.detail.add') }}</a-button>
        <a-button :disabled="updateBusy || editorOpen" :loading="loading" @click="refreshView"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
        <a-input-search v-model:value="detailSearch" :placeholder="$t('label.template.vnf.detail.search')" @change="detailPage=1" />
      </div>
      <a-table size="small" :columns="detailColumns" :data-source="visibleDetails" :pagination="false" row-key="name" :scroll="{x:650}">
        <template #bodyCell="{column,record}">
          <a-tooltip v-if="column.key==='name'" :title="record.name"><span class="template-cell-value">{{ record.name }}</span></a-tooltip>
          <a-tooltip v-else-if="column.key==='value'" :title="getDisplayValue(record.name,record.value)"><span class="template-cell-value">{{ getDisplayValue(record.name,record.value) }}</span></a-tooltip>
          <div v-else-if="column.key==='actions' && canWrite" class="template-row-actions">
            <a-button size="small" type="link" :disabled="updateBusy || editorOpen || !optionsReady" @click="showEditVnfDetail(record.name,$event)">{{ $t('label.edit') }}</a-button>
            <a-popconfirm :title="$t('label.delete.setting')+': '+record.name+'?'" :disabled="updateBusy || editorOpen" :ok-text="$t('label.yes')" :cancel-text="$t('label.no')" @confirm="deleteVnfDetail(record.name)"><tooltip-button :tooltip="$t('label.delete')" icon="delete-outlined" :danger="true" :disabled="updateBusy || editorOpen" /></a-popconfirm>
          </div>
        </template>
      </a-table>
      <div class="template-tab-pagination"><a-pagination v-model:current="detailPage" v-model:page-size="detailPageSize" :total="filteredDetails.length" show-size-changer :page-size-options="['10','20','50']" /></div>
    </section>
    <MoldDialog
class="vnf-template-detail-dialog"
size="md"
focus-guard
:visible="showAddVnfDetail || showEditDetail"
:title="$t(showEditDetail ? 'label.edit.setting' : 'label.vnf.detail.add')"
:closable="!updateBusy"
:keyboard="!updateBusy"
:focus-trigger-after-close="false"
destroy-on-close
:after-close="afterDetailClose"
@cancel="closeVnfDetail">
      <dl class="vnf-context"><dt>{{ $t('label.template') }}</dt><dd>{{ resource.name }}<br>{{ resource.id }}</dd></dl>
      <a-form layout="vertical" @keydown.ctrl.enter.stop.prevent="saveVnfDetail">
        <a-form-item :label="$t('label.name')" html-for="vnf-detail-key" required><a-select id="vnf-detail-key" v-model:value="detailDraft.key" :options="detailKeys" show-search :disabled="showEditDetail || updateBusy" @change="onDetailKeyChange" /></a-form-item>
        <a-form-item v-if="detailSecret && showEditDetail" :label="$t('label.vnf.secret.action')"><a-switch :aria-label="$t('label.vnf.secret.replace')" v-model:checked="detailDraft.replaceSecret" :disabled="updateBusy" /> {{ $t(detailDraft.replaceSecret ? 'label.vnf.secret.replace' : 'label.vnf.secret.unchanged') }}</a-form-item>
        <a-form-item :label="$t('label.value')" html-for="vnf-detail-value" :required="!detailSecret || !showEditDetail || detailDraft.replaceSecret">
          <a-select v-if="detailDraft.key === 'access_methods'" id="vnf-detail-value" v-model:value="detailDraft.values" :options="detailMethodOptions" mode="multiple" :disabled="updateBusy" />
          <a-input v-else-if="detailSecret" id="vnf-detail-value" type="password" autocomplete="new-password" v-model:value="detailDraft.value" :disabled="updateBusy || (showEditDetail && !detailDraft.replaceSecret)" :placeholder="$t('label.vnf.secret.new')" />
          <a-input v-else id="vnf-detail-value" v-model:value="detailDraft.value" :disabled="updateBusy" />
        </a-form-item>
        <a-alert v-if="detailError" type="error" show-icon role="alert" :message="detailError" />
        <MoldDialogActions :busy="updateBusy"><a-button @click="closeVnfDetail">{{ $t('label.cancel') }}</a-button><a-button type="primary" :loading="updateBusy" :disabled="!canWrite || !optionsReady" @click="saveVnfDetail">{{ $t('label.ok') }}</a-button></MoldDialogActions>
      </a-form>
    </MoldDialog>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import TooltipButton from '@/components/widgets/TooltipButton'
import TooltipLabel from '@/components/widgets/TooltipLabel'
import { VNF_DETAIL_KEYS, isVnfSecret, normalizeVnfKey, vnfWriteAllowed, vnfDetailError, vnfNicError } from '@/utils/templateVnfSettings'
const cloneNics = rows => (rows || []).map(n => ({ ...n }))
export default {
  name: 'TemplateVnfSettings',
  components: { MoldDialog, MoldDialogActions, TooltipButton, TooltipLabel },
  props: { resource: { type: Object, required: true }, loading: { type: Boolean, default: false } },
  data () {
    return { nicSearch: '', detailSearch: '', nicPage: 1, detailPage: 1, nicPageSize: 10, detailPageSize: 10, updateBusy: false, updateSequence: 0, optionsSequence: 0, optionsLoading: false, optionsReady: false, optionsError: false, vnfDetailOptions: {}, columns: [], vnfNics: [], vnfDetails: [], previousVnfNics: [], previousVnfDetails: [], showAddVnfNic: false, showEditVnfNic: false, nicDraft: {}, nicError: '', saveError: '', showAddVnfDetail: false, showEditDetail: false, detailDraft: { key: '', value: '', values: [], replaceSecret: false }, detailError: '' }
  },
  beforeCreate () { this.apiParams = this.$getApiParams('updateVnfTemplate') || {} },
  computed: {
    filteredNics () { const q = this.nicSearch.toLowerCase(); return this.vnfNics.filter(n => [n.deviceid, n.name, n.description].some(v => String(v ?? '').toLowerCase().includes(q))) },
    visibleNics () { return this.filteredNics.slice((this.nicPage - 1) * this.nicPageSize, this.nicPage * this.nicPageSize) },
    filteredDetails () { const q = this.detailSearch.toLowerCase(); return this.vnfDetails.filter(d => d.name.toLowerCase().includes(q) || (!isVnfSecret(d.name) && String(d.value).toLowerCase().includes(q))) },
    visibleDetails () { return this.filteredDetails.slice((this.detailPage - 1) * this.detailPageSize, this.detailPage * this.detailPageSize) },
    detailColumns () { return [{ key: 'name', dataIndex: 'name', title: this.$t('label.name'), width: 220 }, { key: 'value', dataIndex: 'value', title: this.$t('label.value') }, { key: 'actions', title: this.$t('label.actions'), width: 140 }] },
    canWrite () { return vnfWriteAllowed(this.resource, this.$store.getters.userInfo, this.$store.getters.project, this.$store.getters.apis) },
    editorOpen () { return this.showAddVnfNic || this.showEditVnfNic || this.showAddVnfDetail || this.showEditDetail },
    detailSecret () { return isVnfSecret(this.detailDraft.key) },
    detailKeys () { return Object.keys(this.vnfDetailOptions).sort().filter(k => !this.vnfDetails.some(d => normalizeVnfKey(d.name) === k) || (this.showEditDetail && this.detailDraft.key === k)).map(value => ({ value })) },
    detailMethodOptions () { return (this.vnfDetailOptions.access_methods || []).map(value => ({ value })) },
    nicScope () {
      const { userInfo: u = {}, project: p = {}, apis = {} } = this.$store.getters
      const r = this.resource
      return JSON.stringify([r.id, r.templatetype, r.account, r.domainid, r.domainpath, r.projectid, p.id, u.id, u.roletype, u.roleid, u.account, u.domainid, u.domainpath, this.$store.state?.user?.token, this.$route?.fullPath, Object.keys(apis).filter(k => ['updateVnfTemplate', 'listDetailOptions', 'listDomains'].includes(k)).sort()])
    }
  },
  created () {
    this.columns = ['deviceid', 'name', 'required', 'management', 'description', 'actions'].map(key => ({ key, dataIndex: key, title: this.$t({ deviceid: 'label.deviceid', name: 'label.name', required: 'label.required', management: 'label.vnf.nic.management', description: 'label.description', actions: 'label.action' }[key]), ...(['required', 'management', 'actions'].includes(key) ? { slots: { customRender: key } } : {}), ...({ deviceid: 80, name: 180, required: 90, management: 100, description: 220, actions: 200 }[key] ? { width: { deviceid: 80, name: 180, required: 90, management: 100, description: 220, actions: 200 }[key] } : {}) }))
    this.initForm(); this.loadOptions()
  },
  beforeUnmount () { this.invalidate() },
  deactivated () { this.invalidate() },
  activated () { this.initForm(); this.loadOptions() },
  watch: {
    'filteredNics.length' () { this.nicPage = Math.min(this.nicPage, Math.max(1, Math.ceil(this.filteredNics.length / this.nicPageSize))) },
    'filteredDetails.length' () { this.detailPage = Math.min(this.detailPage, Math.max(1, Math.ceil(this.filteredDetails.length / this.detailPageSize))) },
    nicPageSize () { this.nicPage = 1 },
    detailPageSize () { this.detailPage = 1 },
    nicScope () { this.invalidate(); this.initForm(); this.loadOptions() },
    loading (value) { if (!value && !this.updateBusy && !this.editorOpen) this.initForm() }
  },
  methods: {
    refreshView () { if (!this.updateBusy && !this.editorOpen) { this.$emit('refresh'); this.loadOptions() } },
    isAdminOrOwner () { return this.canWrite },
    initForm () {
      if (this.updateBusy || this.editorOpen) return
      this.vnfNics = cloneNics(this.resource.vnfnics).sort((a, b) => a.deviceid - b.deviceid)
      this.vnfDetails = Object.entries(this.resource.vnfdetails || {}).map(([name, value]) => ({ name: normalizeVnfKey(name), value }))
      this.previousVnfNics = cloneNics(this.vnfNics); this.previousVnfDetails = this.vnfDetails.map(d => ({ ...d }))
    },
    fetchData () { this.vnfNics = cloneNics(this.previousVnfNics); this.vnfDetails = this.previousVnfDetails.map(d => ({ ...d })) },
    invalidate () {
      this.nicSearch = this.detailSearch = ''; this.nicPage = this.detailPage = 1
      this.updateSequence++; this.optionsSequence++; this.updateBusy = false; this.optionsLoading = false; this.optionsReady = false
      this.showAddVnfNic = this.showEditVnfNic = this.showAddVnfDetail = this.showEditDetail = false
      this.nicTrigger = this.detailTrigger = null; this.resetNicDraft(); this.resetDetailDraft(); this.saveError = ''
    },
    async loadOptions () {
      const scope = this.nicScope; const sequence = ++this.optionsSequence; this.optionsReady = false; this.optionsError = false; this.optionsLoading = true
      try {
        if (!('listDetailOptions' in this.$store.getters.apis)) throw new Error('unavailable')
        const json = await getAPI('listDetailOptions', { resourcetype: 'VnfTemplate' }, { preserveOnFailure: true, timeout: 15000 })
        if (scope !== this.nicScope || sequence !== this.optionsSequence) return
        const options = json.listdetailoptionsresponse?.detailoptions?.details
        if (!options || typeof options !== 'object' || Array.isArray(options)) throw new Error('invalid options')
        this.vnfDetailOptions = Object.fromEntries(Object.entries(options).map(([k, v]) => [normalizeVnfKey(k), v]).filter(([k, v]) => VNF_DETAIL_KEYS.includes(k) && Array.isArray(v)))
        if (!Object.keys(this.vnfDetailOptions).length) throw new Error('empty options')
        this.optionsReady = true
      } catch (e) { if (scope === this.nicScope && sequence === this.optionsSequence) { this.vnfDetailOptions = {}; this.optionsError = true } } finally { if (scope === this.nicScope && sequence === this.optionsSequence) this.optionsLoading = false }
    },
    resetNicDraft () { this.nicDraft = { deviceid: null, name: '', required: true, management: false, description: '' }; this.nicError = '' },
    onShowAddVnfNic (event) {
      if (!this.canWrite || this.updateBusy || this.editorOpen) return
      this.resetNicDraft(); this.nicDraft.deviceid = this.vnfNics.length; this.nicDraft.required = this.vnfNics.every(n => n.required); this.nicTrigger = event?.currentTarget || document.activeElement; this.showAddVnfNic = true
    },
    onShowEditVnfNic (record, event) {
      if (!this.canWrite || this.updateBusy || this.editorOpen) return
      this.nicDraft = { ...record }; this.nicError = ''; this.nicTrigger = event?.currentTarget || document.activeElement; this.showEditVnfNic = true
    },
    validNicDraft () {
      const id = Number(this.nicDraft.deviceid)
      const error = this.nicDraft.deviceid === null || String(this.nicDraft.deviceid).trim() === '' || !Number.isInteger(id) || id < 0 || (!this.showEditVnfNic && this.vnfNics.some(n => Number(n.deviceid) === id)) ? 'message.vnf.nic.ids.invalid' : !this.nicDraft.name?.trim() ? 'message.vnf.nic.name.required' : ''
      this.nicError = error ? this.$t(error) : ''; return !error
    },
    submitVnfNic () { return this.showEditVnfNic ? this.editVnfNic() : this.addVnfNic() },
    addVnfNic () {
      if (!this.showAddVnfNic || this.updateBusy || !this.validNicDraft()) return
      return this.updateVnfTemplateNics([...this.vnfNics, { ...this.nicDraft, deviceid: Number(this.nicDraft.deviceid), name: this.nicDraft.name.trim() }])
    },
    editVnfNic () {
      if (!this.showEditVnfNic || this.updateBusy || !this.validNicDraft()) return
      const draft = { ...this.nicDraft, name: this.nicDraft.name.trim() }
      return this.updateVnfTemplateNics(this.vnfNics.map(n => Number(n.deviceid) === Number(draft.deviceid) ? draft : { ...n }))
    },
    deleteVnfNic (record) {
      if (this.updateBusy || this.editorOpen) return
      return this.updateVnfTemplateNics(this.vnfNics.filter(n => n.deviceid !== record.deviceid).map((n, deviceid) => ({ ...n, deviceid })))
    },
    canMove (record, direction) {
      if ((this.nicSearch || '').trim()) return false
      const other = this.vnfNics.find(n => Number(n.deviceid) === Number(record.deviceid) + direction)
      return !!(other && !!other.required === !!record.required)
    },
    moveVnfNicUp (record) { return this.moveVnfNic(record, -1) },
    moveVnfNicDown (record) { return this.moveVnfNic(record, 1) },
    moveVnfNic (record, direction) {
      if (this.updateBusy || this.editorOpen || !this.canMove(record, direction)) return
      const rows = cloneNics(this.vnfNics); const current = rows.find(n => n.deviceid === record.deviceid); const other = rows.find(n => n.deviceid === record.deviceid + direction)
      if (!current || !other) return
      current.deviceid += direction; other.deviceid -= direction
      return this.updateVnfTemplateNics(rows.sort((a, b) => a.deviceid - b.deviceid))
    },
    updateVnfTemplateNics (nics = this.vnfNics) {
      const error = vnfNicError(nics); this.nicError = error ? this.$t(error) : ''; this.saveError = this.editorOpen ? '' : this.nicError
      if (error) return
      return this.updateVnfTemplate(false, true, nics)
    },
    resetDetailDraft () { this.detailDraft = { key: '', value: '', values: [], replaceSecret: false }; this.detailError = '' },
    onShowAddVnfDetail (event) {
      if (!this.canWrite || this.updateBusy || this.editorOpen || !this.optionsReady) return
      this.resetDetailDraft(); this.detailTrigger = event?.currentTarget || document.activeElement; this.showAddVnfDetail = true
    },
    onDetailKeyChange () { this.detailDraft.value = ''; this.detailDraft.values = []; this.detailDraft.replaceSecret = false; this.detailError = '' },
    showEditVnfDetail (key, event) {
      if (!this.canWrite || this.updateBusy || this.editorOpen || !this.optionsReady) return
      const item = typeof key === 'number' ? this.vnfDetails[key] : this.vnfDetails.find(d => d.name === key)
      if (!item) return
      this.resetDetailDraft(); this.detailDraft.key = item.name; this.detailDraft.value = isVnfSecret(item.name) ? '' : item.value; this.detailDraft.values = item.name === 'access_methods' ? item.value.split(',') : []; this.detailTrigger = event?.currentTarget || document.activeElement; this.showEditDetail = true
    },
    saveVnfDetail () {
      if (!this.canWrite || this.updateBusy || !this.optionsReady || !(this.showAddVnfDetail || this.showEditDetail)) return
      const key = normalizeVnfKey(this.detailDraft.key); const existing = this.vnfDetails.find(d => d.name === key)
      if (this.showEditDetail && isVnfSecret(key) && !this.detailDraft.replaceSecret) { this.closeVnfDetail(); return }
      const value = key === 'access_methods' ? this.detailDraft.values.map(v => v.toLowerCase()).join(',') : this.detailDraft.value
      const error = !this.showEditDetail && existing ? 'message.vnf.detail.duplicate' : vnfDetailError(key, value, this.vnfDetailOptions)
      this.detailError = error ? this.$t(error) : ''; if (error) return
      const details = this.vnfDetails.filter(d => d.name !== key).map(d => ({ ...d })); details.push({ name: key, value })
      return this.updateVnfTemplate(true, false, this.vnfNics, details)
    },
    addVnfDetail () { return this.saveVnfDetail() },
    updateVnfDetail () { return this.saveVnfDetail() },
    deleteVnfDetail (key) {
      if (this.updateBusy || this.editorOpen) return
      const item = typeof key === 'number' ? this.vnfDetails[key] : this.vnfDetails.find(d => d.name === key)
      if (item) return this.updateVnfTemplate(true, false, this.vnfNics, this.vnfDetails.filter(d => d !== item).map(d => ({ ...d })))
    },
    async updateVnfTemplate (detailsChanged, nicsChanged, nics = this.vnfNics, details = this.vnfDetails) {
      if (this.updateBusy || !this.canWrite) return false
      const scope = this.nicScope; const sequence = ++this.updateSequence; const current = () => scope === this.nicScope && sequence === this.updateSequence
      const params = { id: this.resource.id }
      if (detailsChanged) {
        if (!details.length) params.cleanupvnfdetails = true
        // The server replaces the entire map; retain unchanged secret values only in memory.
        for (const d of details) params['vnfdetails[0].' + d.name] = d.value
      }
      if (nicsChanged) {
        if (!nics.length) params.cleanupvnfnics = true
        nics.forEach((nic, i) => ['deviceid', 'name', 'required', 'management', 'description'].forEach(key => { params['vnfnics[' + i + '].' + key] = nic[key] }))
      }
      this.updateBusy = true; this.saveError = ''; this.nicError = ''; this.detailError = ''
      try {
        const json = await postAPI('updateVnfTemplate', params, { preserveOnFailure: true, timeout: 30000 })
        if (!current()) return false
        const template = json.updatetemplateresponse?.template
        if (!template || (template.id && template.id !== this.resource.id)) throw new Error('invalid response')
        this.vnfNics = cloneNics(template.vnfnics).sort((a, b) => a.deviceid - b.deviceid)
        this.vnfDetails = Object.entries(template.vnfdetails || {}).map(([name, value]) => ({ name: normalizeVnfKey(name), value }))
        this.previousVnfNics = cloneNics(this.vnfNics); this.previousVnfDetails = this.vnfDetails.map(d => ({ ...d }))
        this.closeVnfDetail(true); this.closeVnfNicModal(true); this.$emit('refresh'); return true
      } catch (e) {
        if (current()) { const message = this.$t('message.vnf.save.failed'); this.saveError = message; if (detailsChanged) this.detailError = message; if (nicsChanged) this.nicError = message }
        return false
      } finally { if (current()) this.updateBusy = false }
    },
    getDisplayValue (key, value) { return isVnfSecret(key) ? '********' : value },
    closeVnfDetail (completed = false) { if (this.updateBusy && completed !== true) return; this.showAddVnfDetail = this.showEditDetail = false; this.resetDetailDraft() },
    afterDetailClose () { if (this.showAddVnfDetail || this.showEditDetail) return; this.resetDetailDraft(); if (this.detailTrigger?.isConnected) this.detailTrigger.focus({ preventScroll: true }); this.detailTrigger = null },
    closeVnfNicModal (completed = false) { if (this.updateBusy && completed !== true) return; this.showAddVnfNic = this.showEditVnfNic = false },
    afterVnfNicClose () { if (this.showAddVnfNic || this.showEditVnfNic) return; this.resetNicDraft(); if (this.nicTrigger?.isConnected) this.nicTrigger.focus({ preventScroll: true }); this.nicTrigger = null }
  }
}
</script>
<style lang="less" scoped>
.vnf-add { width: 100%; margin-bottom: 12px; }
.vnf-context { display: grid; grid-template-columns: minmax(80px, auto) minmax(0, 1fr); gap: 8px 16px; dt { font-weight: 600; } dd { margin: 0; overflow-wrap: anywhere; } }
.shift-btns { display: flex; gap: 4px; }
.vnf-value { overflow-wrap: anywhere; }
.vnf-settings :deep(.ant-form-item) { margin-bottom: 16px; }
</style>
