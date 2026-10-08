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
  <section class="storage-table-section posix-directory-policies">
    <h4>{{ $t('label.posix.directory.policy') }}</h4>
    <p>{{ $t('message.posix.directory.policy.help') }}</p>
    <a-space wrap class="policy-actions">
      <a-button v-if="canCreate" type="primary" @click="openEditor()"><template #icon><PlusOutlined /></template>{{ $t('label.posix.directory.create') }}</a-button>
      <a-button :loading="loading" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="readError" type="warning" show-icon :message="$t('message.posix.directory.read.failed')" />
    <a-table size="small" row-key="id" :columns="columns" :data-source="rows" :pagination="false" :scroll="{ x: 1500 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'actions'">
          <a-space>
            <a-button v-if="canUpdate" size="small" @click="openEditor(record)"><template #icon><EditOutlined /></template>{{ $t('label.edit') }}</a-button>
            <a-button v-if="canApply" size="small" @click="openEditor(record, true)">{{ $t('label.posix.directory.apply') }}</a-button>
            <a-button v-if="canDelete" size="small" danger @click="deleteTarget=record"><template #icon><DeleteOutlined /></template>{{ $t('label.delete') }}</a-button>
          </a-space>
        </template>
        <template v-else>{{ record[column.dataIndex] }}</template>
      </template>
    </a-table>
    <a-modal v-model:visible="editing" :title="$t(editId ? 'label.posix.directory.edit' : 'label.posix.directory.create')" :confirm-loading="saving" :body-style="{ maxHeight: '65vh', overflowY: 'auto' }" :ok-button-props="{ disabled: !canSavePreview }" @ok="save" @cancel="clearPreview">
      <a-form layout="vertical" :disabled="applyOnly">
        <a-form-item :label="$t('label.storage.service.backing.volume')" required>
          <a-select v-model:value="form.volumeid" :disabled="!!editId"><a-select-option v-for="volume in volumes" :key="volume.id" :value="volume.id">{{ volume.name || volume.id }}</a-select-option></a-select>
        </a-form-item>
        <a-form-item :label="$t('label.posix.directory.relative.path')" required><a-input v-model:value="form.relativepath" :disabled="!!editId" /></a-form-item>
        <a-form-item :label="$t('label.posix.directory.apply.owner')"><a-switch v-model:checked="form.applyowner" /></a-form-item>
        <a-form-item v-if="form.applyowner" label="UID"><a-input-number v-model:value="form.owneruid" :min="0" :max="2147483647" /></a-form-item>
        <a-form-item v-if="form.applyowner" label="GID"><a-input-number v-model:value="form.ownergid" :min="0" :max="2147483647" /></a-form-item>
        <a-form-item :label="$t('label.storage.service.directory.mode')" required><a-input v-model:value="form.directorymode" :maxlength="4" /></a-form-item>
        <a-form-item :label="$t('label.posix.directory.setgid')"><a-switch :checked="setgid" @change="setGroupInheritance" /></a-form-item>
        <template v-for="scope in scopes" :key="scope.key">
          <h4>{{ $t(scope.label) }}</h4>
          <div v-for="(entry,index) in form[scope.key]" :key="index" class="acl-entry">
            <a-form-item :label="$t('label.storage.service.principal.type')"><a-select v-model:value="entry.principalType"><a-select-option v-for="type in principalTypes" :key="type" :value="type">{{ type }}</a-select-option></a-select></a-form-item>
            <a-form-item :label="$t('label.storage.service.principal')"><a-input v-model:value="entry.principal" /></a-form-item>
            <a-form-item :label="$t('label.storage.service.permission')"><a-select v-model:value="entry.permission"><a-select-option value="READ_ONLY">{{ $t('label.storage.service.permission.readonly') }}</a-select-option><a-select-option value="READ_WRITE">{{ $t('label.storage.service.permission.readwrite') }}</a-select-option><a-select-option value="FULL_CONTROL">{{ $t('label.posix.directory.full.control') }}</a-select-option></a-select></a-form-item>
            <a-button danger @click="form[scope.key].splice(index,1)">{{ $t('label.delete') }}</a-button>
          </div>
          <a-button @click="addEntry(scope.key)"><template #icon><PlusOutlined /></template>{{ $t('label.posix.directory.acl.add') }}</a-button>
        </template>
        <a-alert type="info" show-icon :message="$t('message.posix.directory.nonrecursive')" class="policy-warning" />
        <a-alert v-if="previewError" type="error" show-icon :message="previewError" />
        <a-descriptions v-if="preview" bordered :column="1" size="small">
          <a-descriptions-item :label="$t('label.posix.directory.canonical.path')">{{ preview.canonicalpath }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.posix.directory.current.owner')">{{ previewCurrent.uid ?? previewEffective.effectiveUid }}:{{ previewCurrent.gid ?? previewEffective.effectiveGid }} · {{ previewCurrent.mode || previewEffective.effectiveMode }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.posix.expected.owner')">{{ previewV2.suggested?.applyowner ? previewV2.suggested?.owneruid : previewCurrent.uid }}:{{ previewV2.suggested?.applyowner ? previewV2.suggested?.ownergid : previewCurrent.gid }} · {{ previewV2.suggested?.mode || form.directorymode }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.storage.volume.fs.uuid')"><code>{{ previewCurrent.filesystemUuid || '-' }}</code></a-descriptions-item>
          <a-descriptions-item :label="$t('label.posix.directory.affected.shares')">{{ (preview.affectedshares || []).join(', ') || '-' }}</a-descriptions-item>
          <a-descriptions-item :label="$t('label.posix.directory.current.acl')"><pre>{{ (previewEffective.acl || []).join('\n') }}</pre></a-descriptions-item>
        </a-descriptions>
      </a-form>
      <a-button :loading="previewing" @click="loadPreview">{{ $t('label.posix.directory.preview') }}</a-button>
      <a-alert v-if="previewV2.readonlyTraversalOK === false" type="warning" show-icon :message="$t('message.storage.posix.read.traverse')" />
      <a-checkbox v-if="previewV2.previewToken" v-model:checked="confirmed">{{ $t('message.storage.posix.confirm.inode') }}</a-checkbox>
    </a-modal>
    <a-modal :visible="!!deleteTarget" :title="$t('label.delete')" :confirm-loading="saving" @cancel="deleteTarget=null" @ok="remove">
      <a-alert type="warning" show-icon :message="$t('message.posix.directory.delete.preserve')" />
      <p>{{ deleteTarget?.relativepath }}</p>
    </a-modal>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { PlusOutlined, ReloadOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons-vue'
const parse = value => { try { return typeof value === 'string' ? JSON.parse(value) : (value || {}) } catch (error) { return {} } }
export default {
  name: 'PosixDirectoryPolicies',
  components: { PlusOutlined, ReloadOutlined, EditOutlined, DeleteOutlined },
  props: { instanceId: { type: String, required: true }, volumes: { type: Array, default: () => [] } },
  emits: ['refresh', 'applied'],
  data () {
    return {
      policies: [],
      loading: false,
      readError: false,
      generation: 0,
      editing: false,
      editId: '',
      saving: false,
      preview: null,
      previewError: '',
      previewing: false,
      previewToken: '',
      deleteTarget: null,
      form: {},
      exportContext: null,
      applyOnly: false,
      confirmed: false,
      previewGeneration: 0,
      principalTypes: ['NUMERIC_UID', 'NUMERIC_GID', 'LOCAL_USER', 'LOCAL_GROUP'],
      scopes: [{ key: 'access', label: 'label.posix.directory.access.acl' }, { key: 'defaults', label: 'label.posix.directory.default.acl' }]
    }
  },
  computed: {
    canCreate () { return 'createStoragePosixDirectoryPolicy' in this.$store.getters.apis },
    canUpdate () { return 'updateStoragePosixDirectoryPolicy' in this.$store.getters.apis },
    canDelete () { return 'deleteStoragePosixDirectoryPolicy' in this.$store.getters.apis },
    canApply () { return 'applyStoragePosixDirectoryPolicy' in this.$store.getters.apis },
    formToken () { return JSON.stringify({ form: this.form, exportContext: this.exportContext }) },
    previewEffective () { return parse(this.preview?.effective) },
    previewV2 () { return parse(this.preview?.preview) },
    previewCurrent () { return this.previewV2.current || {} },
    canSavePreview () { return this.validPreview() },
    editorCommand () { return this.applyOnly ? 'applyStoragePosixDirectoryPolicy' : (this.editId ? 'updateStoragePosixDirectoryPolicy' : 'createStoragePosixDirectoryPolicy') },
    setgid () { return /^[0-7]{3,4}$/.test(this.form.directorymode || '') && (parseInt(this.form.directorymode, 8) & 0o2000) !== 0 },
    rows () {
      return this.policies.map(policy => {
        const config = parse(policy.config); const effective = parse(policy.effective)
        return {
          ...policy,
          owner: `${effective.effectiveUid ?? '-'}:${effective.effectiveGid ?? '-'}`,
          mode: effective.effectiveMode || '-',
          affected: (policy.affectedshares || []).join(', '),
          acl: `${(config.accessEntries || []).length} / ${(config.defaultEntries || []).length}`
        }
      })
    },
    columns () {
      return [
        { title: this.$t('label.posix.directory.relative.path'), dataIndex: 'relativepath', key: 'relativepath', fixed: 'left', width: 220 },
        { title: 'UID:GID', dataIndex: 'owner', key: 'owner', width: 150 },
        { title: this.$t('label.storage.service.directory.mode'), dataIndex: 'mode', key: 'mode', width: 110 },
        { title: this.$t('label.posix.directory.revision'), dataIndex: 'revision', key: 'revision', width: 100 },
        { title: 'Access / Default ACL', dataIndex: 'acl', key: 'acl', width: 170 },
        { title: this.$t('label.posix.directory.affected.shares'), dataIndex: 'affected', key: 'affected', width: 250 },
        { title: this.$t('label.state'), dataIndex: 'driftstatus', key: 'driftstatus', width: 140 },
        { title: this.$t('label.actions'), key: 'actions', fixed: 'right', width: 260, align: 'right' }]
    }
  },
  watch: { instanceId () { this.generation++; this.policies = []; this.editing = false; this.deleteTarget = null; this.saving = false; this.clearPreview(); this.refresh() } },
  mounted () { this.refresh() },
  beforeUnmount () { this.generation++; this.previewGeneration++ },
  methods: {
    clearPreview () { this.previewGeneration++; this.previewing = false; this.preview = null; this.previewError = ''; this.previewToken = ''; this.confirmed = false },
    async refresh () {
      const token = ++this.generation; const instance = this.instanceId
      if (!instance) return
      this.loading = true
      try {
        const result = await getAPI('listStoragePosixDirectoryPolicies', { instanceid: instance }, { preserveOnFailure: true, timeout: 15000 }); if (token !== this.generation || instance !== this.instanceId) return
        this.policies = result.liststorageposixdirectorypoliciesresponse?.storageposixdirectorypolicy || []; this.readError = false
      } catch (error) { if (token === this.generation) this.readError = true } finally { if (token === this.generation) this.loading = false }
    },
    openEditor (policy, applyOnly = false) {
      this.exportContext = null
      this.applyOnly = applyOnly
      const config = parse(policy?.config); const effective = parse(policy?.effective)
      this.editId = policy?.id || ''; this.clearPreview()
      this.form = {
        volumeid: policy?.volumeid || this.volumes[0]?.id || '',
        relativepath: policy?.relativepath || '',
        owneruid: config.ownerUid ?? effective.effectiveUid ?? null,
        ownergid: config.ownerGid ?? effective.effectiveGid ?? null,
        directorymode: config.directoryMode || effective.effectiveMode || '2775',
        applyowner: !!config.applyOwner,
        expectedpolicyrevision: policy?.revision,
        access: JSON.parse(JSON.stringify(config.accessEntries || [])),
        defaults: JSON.parse(JSON.stringify(config.defaultEntries || []))
      }
      this.editing = true
    },
    openForExport (exportId, draft, policyId) {
      const policy = this.policies.find(item => item.id === policyId)
      if (policyId && !policy) { this.$message.error(this.$t('message.posix.directory.read.failed')); return }
      this.openEditor(policy)
      this.exportContext = { exportid: exportId, readonly: !!draft.readonly, rootsquash: !!draft.rootsquash, allsquash: !!draft.allsquash, anonuid: draft.anonuid, anongid: draft.anongid }
      Object.assign(this.form, { volumeid: draft.volumeid, relativepath: policy?.relativepath || draft.relativepath || '.', owneruid: draft.owneruid, ownergid: draft.ownergid, directorymode: draft.mode || this.form.directorymode })
    },
    setGroupInheritance (enabled) {
      const current = parseInt(this.form.directorymode || '0770', 8); if (Number.isNaN(current)) return
      this.form.directorymode = ((enabled ? current | 0o2000 : current & ~0o2000).toString(8)).padStart(4, '0'); this.clearPreview()
    },
    addEntry (scope) { this.form[scope].push({ principalType: 'NUMERIC_GID', principal: '', permission: 'READ_WRITE' }); this.clearPreview() },
    params () {
      if (this.applyOnly) return { id: this.editId, expectedpolicyrevision: this.form.expectedpolicyrevision }
      return {
        ...(this.exportContext || {}),
        ...(this.editId ? { id: this.editId } : { instanceid: this.instanceId }),
        volumeid: this.form.volumeid,
        relativepath: this.form.relativepath,
        owneruid: this.form.applyowner ? this.form.owneruid : undefined,
        ownergid: this.form.applyowner ? this.form.ownergid : undefined,
        applyowner: this.form.applyowner,
        directorymode: this.form.directorymode,
        recursive: false,
        expectedpolicyrevision: this.form.expectedpolicyrevision,
        accessentries: JSON.stringify(this.form.access),
        defaultentries: JSON.stringify(this.form.defaults)
      }
    },
    async resolved (command, params) {
      let result
      try { result = await postAPI(command, params) } catch (error) {
        const response = error.response?.data
        const body = response?.[command.toLowerCase() + 'response'] || response?.errorresponse
        throw new Error(typeof body?.errortext === 'string' ? body.errortext : error.message)
      }
      const payload = result[command.toLowerCase() + 'response'] || result
      if (!payload.jobid) return payload.storageposixdirectorypolicy || payload
      for (let i = 0; i < 60; i++) {
        result = await getAPI('queryAsyncJobResult', { jobid: payload.jobid }, { preserveOnFailure: true, timeout: 15000 }); const job = result.queryasyncjobresultresponse
        if (job.jobstatus === 1) return job.jobresult.storageposixdirectorypolicy || job.jobresult
        if (job.jobstatus === 2) throw new Error(job.jobresult?.errortext || 'Operation failed')
        await new Promise(resolve => setTimeout(resolve, 1000))
      }
      throw new Error(this.$t('message.posix.directory.timeout'))
    },
    validPreview () {
      const expiry = typeof this.previewV2.expiresAt === 'number' ? this.previewV2.expiresAt : Date.parse(this.previewV2.expiresAt)
      return !!this.previewV2.previewToken && this.previewV2.schemaVersion === 2 && this.previewToken === this.formToken && this.confirmed && Number.isFinite(expiry) && Date.now() < expiry
    },
    async loadPreview () {
      const generation = ++this.previewGeneration
      const token = this.formToken; const instance = this.instanceId; this.previewing = true; this.previewError = ''
      try {
        const value = await this.resolved(this.editorCommand, { ...this.params(), preview: true })
        if (generation === this.previewGeneration && token === this.formToken && instance === this.instanceId && this.editing) { this.preview = value; this.previewToken = token; this.confirmed = false }
      } catch (error) { if (generation === this.previewGeneration && instance === this.instanceId) this.previewError = error.message } finally { if (generation === this.previewGeneration) this.previewing = false }
    },
    async save () {
      if (!this.validPreview()) return
      const instance = this.instanceId; const generation = this.previewGeneration
      this.saving = true
      try { const saved = await this.resolved(this.editorCommand, { ...this.params(), previewtoken: this.previewV2.previewToken, applyconfirmation: true }); if (instance !== this.instanceId || generation !== this.previewGeneration) return; this.$emit('applied', { policy: saved, exportid: this.exportContext?.exportid }); this.editing = false; this.clearPreview(); await this.refresh(); this.$emit('refresh') } catch (error) { if (instance === this.instanceId && generation === this.previewGeneration) this.previewError = error.message } finally { if (instance === this.instanceId) this.saving = false }
    },
    async remove () {
      this.saving = true
      try { await this.resolved('deleteStoragePosixDirectoryPolicy', { id: this.deleteTarget.id, expectedpolicyrevision: this.deleteTarget.revision }); this.deleteTarget = null; await this.refresh(); this.$emit('refresh') } catch (error) { this.$message.error(error.message) } finally { this.saving = false }
    }
  }
}
</script>
<style scoped>
.policy-actions { margin-bottom: 16px; }
.acl-entry { margin: 16px 0; padding: 12px; border: 1px solid var(--border-color, #7776); border-radius: 4px; }
.policy-warning { margin: 16px 0; }
pre { white-space: pre-wrap; margin: 0; }
</style>
