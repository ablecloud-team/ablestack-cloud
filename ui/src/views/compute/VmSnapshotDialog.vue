<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->
<template>
  <MoldDialog :title="$t(title)" :closable="!submitting" @cancel="close">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-spin :spinning="loading">
      <template v-if="mode === 'create'">
        <a-input-search v-model:value="vmKeyword" :placeholder="$t('label.search')" @search="loadVms(1)" />
        <a-table
class="mold-dialog-section"
size="middle"
row-key="id"
:columns="vmColumns"
:data-source="vms"
:pagination="false"
:row-selection="{ type: 'radio', selectedRowKeys: selectedVm ? [selectedVm.id] : [], onChange: selectVm }"
:scroll="{ x: 550 }" />
        <a-pagination class="mold-dialog-section" :current="vmPage" :page-size="20" :total="vmTotal" @change="loadVms" />
        <a-alert v-if="createReason" class="mold-dialog-section" type="warning" show-icon :message="$t(createReason)" />
      </template>
      <template v-else-if="mode === 'relation'">
        <a-alert show-icon :type="relations.partial ? 'warning' : 'info'" :message="$t(relations.partial ? 'message.vmsnapshot.relations.partial' : 'message.vmsnapshot.relations.complete', [relations.rows.length, relations.total])" :description="$t('message.vmsnapshot.relations.meaning')" />
        <a-alert v-if="tree.warnings.length" class="mold-dialog-section" type="warning" show-icon :message="$t('message.vmsnapshot.relations.invalid')" />
        <div class="mold-dialog-toolbar"><a-button @click="loadRelations">{{ $t('label.refresh') }}</a-button></div>
        <a-tree :tree-data="tree.roots" :selected-keys="selected ? [selected.id] : []" default-expand-all @select="selectNode">
          <template #title="{ row }">
            <span class="mold-dialog-tree-node">
              <span class="mold-dialog-tree-label">{{ row.displayname || row.name }} · {{ $toLocaleDate(row.created) }} · {{ $t(row.type === 'DiskAndMemory' ? 'label.vmsnapshot.disk.memory' : 'label.vmsnapshot.disk') }}</span>
              <a-tag v-if="row.current">{{ $t('label.vmsnapshot.current.yes') }}</a-tag>
              <span v-if="tree.warnings.some(item => item.id === row.id)">{{ $t('label.warning') }}</span>
            </span>
          </template>
        </a-tree>
        <a-empty v-if="!relations.rows.length && !loading" />
      </template>
      <template v-if="['restore', 'delete', 'relation'].includes(mode) && (mode !== 'relation' || selected)">
        <VmSnapshotSummary v-if="selected" :snapshot="selected" />
        <template v-if="['restore', 'delete'].includes(mode)">
          <a-alert class="mold-dialog-section" type="warning" show-icon :message="$t(mode === 'delete' ? 'message.action.vmsnapshot.delete' : selected.type === 'DiskAndMemory' ? 'message.vmsnapshot.restore.memory.impact' : 'message.vmsnapshot.restore.disk.impact')" />
          <a-table
v-if="targets.length > 1"
class="mold-dialog-section"
size="small"
row-key="id"
:columns="targetColumns"
:data-source="contexts"
:pagination="false"
:scroll="{ x: 550 }">
            <template #bodyCell="{ column, record }"><span v-if="column.key === 'reason'">{{ record.reason ? $t(record.reason) : $t('label.vmsnapshot.eligible') }}</span><span v-else>{{ record[column.dataIndex] }}</span></template>
          </a-table>
          <a-alert v-if="blockedReason" class="mold-dialog-section" type="error" show-icon :message="$t(blockedReason)" />
          <a-checkbox v-if="forceAvailable" v-model:checked="force" class="mold-dialog-section" :disabled="submitting || loading">{{ $t('label.vmsnapshot.force.delete') }}</a-checkbox>
          <a-alert v-if="force" class="mold-dialog-section" type="warning" show-icon :message="$t('message.vmsnapshot.force.delete.impact')" />
          <p>{{ $t('message.vmsnapshot.fresh.check') }}</p>
          <a-checkbox v-model:checked="acknowledged" :disabled="submitting || !!blockedReason">{{ $t('message.vmsnapshot.acknowledge') }}</a-checkbox>
        </template>
        <a-table
v-if="results.length"
class="mold-dialog-section"
size="small"
row-key="id"
:columns="resultColumns"
:data-source="results"
:pagination="false"
:scroll="{ x: 550 }">
          <template #bodyCell="{ column, record }"><span v-if="column.key === 'outcome'">{{ $t('label.vmsnapshot.outcome.' + record.outcome) }} {{ record.error || '' }}</span><CopyLabel v-else-if="column.key === 'jobid' && record.jobid" :label="record.jobid" /><span v-else>{{ record[column.dataIndex] }}</span></template>
        </a-table>
      </template>
    </a-spin>
    <template #footer>
      <a-button :disabled="submitting" @click="close">{{ $t(results.length ? 'label.close' : 'label.cancel') }}</a-button>
      <a-button v-if="error && !submitting" @click="reload">{{ $t('label.refresh') }}</a-button>
      <a-button v-if="mode === 'create'" type="primary" :disabled="!selectedVm || !!createReason || loading" @click="create">{{ $t('label.action.vmsnapshot.create') }}</a-button>
      <template v-if="mode === 'relation' && selected">
        <a-button v-if="allowed('revertToVMSnapshot')" :disabled="!!reason('revertToVMSnapshot', selected)" @click="openNode('restore')">{{ $t('label.action.vmsnapshot.revert') }}</a-button>
        <a-button v-if="allowed('deleteVMSnapshot')" danger :disabled="!!reason('deleteVMSnapshot', selected)" @click="openNode('delete')">{{ $t('label.action.vmsnapshot.delete') }}</a-button>
      </template>
      <a-button v-if="['restore', 'delete'].includes(mode) && !results.length" type="primary" :danger="mode === 'delete'" :loading="submitting" :disabled="!acknowledged || !!blockedReason || loading" @click="submit">{{ $t(mode === 'delete' ? force ? 'label.vmsnapshot.force.delete' : 'label.action.vmsnapshot.delete' : 'label.vmsnapshot.restore.submit') }}</a-button>
    </template>
  </MoldDialog>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import eventBus from '@/config/eventBus'
import compute from '@/config/section/compute'
import MoldDialog from '@/components/view/MoldDialog'
import CopyLabel from '@/components/widgets/CopyLabel'
import VmSnapshotSummary from '@/components/view/VmSnapshotSummary'
import { snapshotActionReason, snapshotBusy, snapshotSubmissions, trackUnknownSnapshotSubmission } from '@/utils/vmSnapshotActions'
import { loadVmSnapshotRelations, snapshotRelationTree, freshSnapshotContext, runSnapshotDeleteBatch } from '@/utils/vmSnapshotList'

export default {
  name: 'VmSnapshotDialog',
  components: { MoldDialog, CopyLabel, VmSnapshotSummary },
  props: { resource: { type: Object, default: () => ({}) }, currentAction: { type: Object, required: true } },
  emits: ['close-action'],
  inject: { parentFetchData: { default: null } },
  data () {
    const targets = this.currentAction.snapshotTargets || [this.resource]
    return { mode: this.currentAction.snapshotMode, targets: targets.filter(row => row.id), selected: targets[0]?.id ? { ...targets[0] } : null, loading: false, submitting: false, force: false, acknowledged: false, contexts: [], results: [], error: '', relations: { rows: [], total: 0, partial: false }, vms: [], vmPage: 1, vmTotal: 0, vmKeyword: '', selectedVm: null, disposed: false, requestVersion: 0 }
  },
  computed: {
    forceAvailable () { return this.mode === 'delete' && this.targets.length === 1 && this.$store.getters.userInfo?.roletype === 'Admin' && this.selected?.forcedeletionallowed === true },
    title () { return { restore: 'label.action.vmsnapshot.revert', delete: 'label.action.vmsnapshot.delete', relation: 'label.vmsnapshot.relations', create: 'label.action.vmsnapshot.create' }[this.mode] },
    api () { return this.mode === 'delete' ? 'deleteVMSnapshot' : 'revertToVMSnapshot' },
    tree () { return snapshotRelationTree(this.relations.rows) },
    security () { return JSON.stringify([this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state?.user?.token]) },
    blockedReason () { return !this.contexts.length ? 'message.vmsnapshot.not.ready' : this.contexts.find(item => item.reason)?.reason || '' },
    targetColumns () { return ['displayname', 'virtualmachinename', 'id', 'reason'].map(key => ({ key, dataIndex: key, title: this.$t(key === 'reason' ? 'label.vmsnapshot.eligibility' : 'label.' + key) })) },
    resultColumns () { return ['displayname', 'outcome', 'jobid'].map(key => ({ key, dataIndex: key, title: this.$t('label.' + key) })) },
    vmColumns () { return ['displayname', 'instancename', 'id', 'account', 'state'].map(key => ({ key, dataIndex: key, title: this.$t('label.' + key) })) },
    createDefinition () { return compute.children.find(item => item.name === 'vm').actions.find(action => action.api === 'createVMSnapshot') },
    createReason () { return !this.selectedVm ? '' : !this.createDefinition.show(this.selectedVm, this.$store.getters) ? 'message.vmsnapshot.vm.state' : this.createDefinition.disabled(this.selectedVm, this.$store.getters, []) ? (this.createDefinition.tooltip(this.selectedVm, this.$store.getters, []) || 'message.vmsnapshot.busy') : '' }
  },
  watch: { force () { this.acknowledged = false }, security () { this.disposed = true; this.requestVersion++; this.$emit('close-action') } },
  created () { this.reload() },
  beforeUnmount () { this.disposed = true; this.requestVersion++ },
  methods: {
    allowed (api) { return api in this.$store.getters.apis },
    reason (api, row, vm, busy = false) { return !this.allowed(api) ? 'message.vmsnapshot.permission' : snapshotActionReason(api, row, vm, busy || snapshotBusy(row.virtualmachineid)) },
    close () { if (!this.submitting) this.$emit('close-action') },
    reload () { if (this.mode === 'relation') return this.loadRelations(); if (this.mode === 'create') return this.loadVms(this.vmPage); return this.refreshContexts() },
    async refreshContexts () {
      const version = ++this.requestVersion
      const security = this.security
      this.loading = true; this.error = ''
      try {
        const contexts = await freshSnapshotContext(getAPI, this.targets, this.allowed('listBackups'))
        if (this.disposed || version !== this.requestVersion || security !== this.security) return
        this.contexts = contexts.map(item => ({ ...item.target, snapshot: item.snapshot, vm: item.vm, reason: !item.snapshot || !item.vm ? 'message.vmsnapshot.not.ready' : this.reason(this.api, item.snapshot, item.vm, item.busy) }))
        if (this.contexts[0]?.snapshot) this.selected = { ...this.contexts[0].snapshot }
      } catch (error) { this.error = error.message || this.$t('error.fetching.data'); this.contexts = [] } finally { if (version === this.requestVersion) this.loading = false }
    },
    async loadRelations () {
      const version = ++this.requestVersion
      this.loading = true; this.error = ''
      try {
        const relations = await loadVmSnapshotRelations(getAPI, this.resource.virtualmachineid)
        if (!this.disposed && version === this.requestVersion) { this.relations = relations; this.selected = relations.rows.find(row => row.id === this.selected?.id) || null }
      } catch (error) { this.error = error.message || this.$t('error.fetching.data'); this.relations.partial = true } finally { if (version === this.requestVersion) this.loading = false }
    },
    selectNode (keys) { this.selected = this.relations.rows.find(row => row.id === keys[0]) || null },
    openNode (mode) { this.mode = mode; this.targets = Object.freeze([Object.freeze({ ...this.selected })]); this.force = false; this.acknowledged = false; this.results = []; this.refreshContexts() },
    async loadVms (page = 1) {
      const version = ++this.requestVersion
      this.loading = true; this.error = ''; this.selectedVm = null
      try {
        const response = (await getAPI('listVirtualMachines', { listall: true, keyword: this.vmKeyword.trim() || undefined, page, pagesize: 20 }, { preserveOnFailure: true })).listvirtualmachinesresponse
        if (!this.disposed && version === this.requestVersion) { this.vms = response.virtualmachine || []; this.vmTotal = response.count || 0; this.vmPage = page }
      } catch (error) { this.error = error.message } finally { if (version === this.requestVersion) this.loading = false }
    },
    selectVm (keys, rows) { this.selectedVm = rows[0] || null },
    async create () {
      if (!this.selectedVm || this.createReason || this.loading) return
      this.loading = true
      const security = this.security
      try {
        const vm = (await getAPI('listVirtualMachines', { id: this.selectedVm.id, listall: true }, { preserveOnFailure: true })).listvirtualmachinesresponse.virtualmachine?.[0]
        if (!vm || this.disposed || security !== this.security || !this.allowed('createVMSnapshot')) return
        this.selectedVm = vm
        if (this.createReason) return
        this.close()
        this.$nextTick(() => eventBus.emit('exec-action', { action: { ...this.createDefinition, resource: vm }, isGroupAction: false }))
      } catch (error) { this.error = error.message } finally { this.loading = false }
    },
    async execute (target, security) {
      if (security !== this.security) throw new Error(this.$t('message.vmsnapshot.permission'))
      const [context] = await freshSnapshotContext(getAPI, [target], this.allowed('listBackups'))
      const reason = !context.snapshot || !context.vm ? 'message.vmsnapshot.not.ready' : this.reason(this.api, context.snapshot, context.vm, context.busy)
      if (security !== this.security) throw new Error(this.$t('message.vmsnapshot.permission'))
      if (reason) throw new Error(this.$t(reason))
      if (this.force && (this.mode !== 'delete' || this.targets.length !== 1 || this.$store.getters.userInfo?.roletype !== 'Admin' || context.snapshot?.forcedeletionallowed !== true)) throw new Error(this.$t('message.vmsnapshot.force.delete.unavailable'))
      const vmId = target.virtualmachineid
      if (snapshotSubmissions[vmId]) throw new Error(this.$t('message.vmsnapshot.busy'))
      const submission = Symbol(vmId)
      snapshotSubmissions[vmId] = submission
      try {
        const response = await postAPI(this.api, { vmsnapshotid: target.id, ...(this.force ? { force: true } : {}) })
        const jobId = response[this.api.toLowerCase() + 'response']?.jobid
        if (!jobId) {
          trackUnknownSnapshotSubmission(vmId, target.id)
          return { jobstatus: null, trackingStatus: 'unknown', error: this.$t('message.vmsnapshot.submission.unknown') }
        }
        if (security !== this.security) return { jobstatus: null, trackingStatus: 'unknown', jobid: jobId }
        const job = await this.$pollJob({ jobId, originalPage: this.$route.path, title: this.$t(this.title), description: target.displayname || target.name, resourceId: target.id, action: { api: this.api, resource: target, isFetchData: false }, successMethod: () => { if (this.parentFetchData && !this.disposed) this.parentFetchData({ irefresh: true }) } })
        return { ...job, jobid: jobId, error: job?.jobresult?.errortext || job?.error || '' }
      } catch (error) {
        if (error.isAxiosError && !error.response) {
          trackUnknownSnapshotSubmission(vmId, target.id)
          return { jobstatus: null, trackingStatus: 'unknown', error: this.$t('message.vmsnapshot.submission.unknown') }
        }
        throw error
      } finally { if (snapshotSubmissions[vmId] === submission) delete snapshotSubmissions[vmId] }
    },
    async submit () {
      if (this.submitting || !this.acknowledged || this.blockedReason) return
      const security = this.security
      this.submitting = true; this.error = ''
      try {
        await this.refreshContexts()
        if (security !== this.security || this.disposed || this.blockedReason) return
        if (this.mode === 'delete') this.results = await runSnapshotDeleteBatch(this.targets, target => this.execute(target, security))
        else { const job = await this.execute(this.targets[0], security); this.results = [{ ...this.targets[0], ...job, outcome: job.jobstatus === 1 ? 'success' : job.jobstatus === 2 ? 'failed' : 'unknown' }] }
      } catch (error) { this.error = error.message } finally { this.submitting = false; if (this.parentFetchData && !this.disposed) this.parentFetchData({ irefresh: true }) }
    }
  }
}
</script>
