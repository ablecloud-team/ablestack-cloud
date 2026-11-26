<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See NOTICE. Licensed under the Apache License,
Version 2.0: http://www.apache.org/licenses/LICENSE-2.0 -->
<template>
  <section class="storage-config-backup">
    <div class="detail-tab-toolbar">
      <a-button type="primary" :disabled="busy || protectedOperation" @click="capture">
        <template #icon><PlusOutlined /></template>{{ $t('label.sharedfs.config.capture') }}
      </a-button>
      <a-button :loading="busy" :disabled="busy" @click="load">
        <template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}
      </a-button>
      <a-button v-if="canApply" :disabled="busy || protectedOperation" @click="openUpload">{{ $t('label.sharedfs.config.upload') }}</a-button>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="error" :description="failureHint(error)" />
    <detail-tab-table
:table="$options.components.ATable"
:pagination="false"
:columns="columns"
:data-source="rows"
row-key="id"
:loading="busy && !listReadComplete"
size="small"
:scroll="{ x: 1060 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'kind'">{{ $t('label.sharedfs.config.kind.' + record.kind.toLowerCase()) }}</template>
        <template v-else-if="column.key === 'created'">{{ createdLabel(record.created) }}</template>
        <template v-else-if="column.key === 'coverage'">
          <a-tag v-for="(state, protocol) in record.metadata?.coverage" :key="protocol" :title="state">{{ protocol }} · {{ coverageLabel(state) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-space>
            <a-button size="small" :disabled="busy" @click="download(record)">{{ $t('label.download') }}</a-button>
            <a-button v-if="canApply" size="small" :disabled="busy || protectedOperation" @click="openReview(record)">{{ $t('label.sharedfs.config.restore') }}</a-button>
          </a-space>
        </template>
      </template>
    </detail-tab-table>
    <div class="detail-tab-toolbar detail-table-footer">
      <a-button :disabled="busy || page === 1" @click="changePage(-1)">{{ $t('label.previous') }}</a-button>
      <a-button :disabled="busy || rows.length < 25" @click="changePage(1)">{{ $t('label.next') }}</a-button>
    </div>
    <MoldDialog
class="sharedfs-standard-dialog"
v-if="mode"
size="lg"
focus-guard
:closable="!busy"
:keyboard="!busy"
      :title="$t(mode === 'upload' ? 'label.sharedfs.config.upload' : 'label.sharedfs.config.restore')"
@cancel="close">
      <a-alert v-if="dialogError" type="error" show-icon :message="dialogError" :description="failureHint(dialogError)" />
      <template v-if="mode === 'upload'">
        <p>{{ $t('message.sharedfs.config.upload') }}</p>
        <input type="file" accept=".zip,application/zip" :disabled="busy" @change="file = $event.target.files[0]" />
      </template>
      <template v-else-if="operation">
        <StorageConfigurationOperationPanel :operation="operation" :refreshing="busy" @refresh="track" @resume="resume" />
      </template>
      <template v-else>
        <a-form layout="vertical">
          <a-form-item :label="$t('label.protocol')">
            <a-checkbox-group v-model:value="scope" :options="protocols" :disabled="busy" @change="inspect" />
          </a-form-item>
          <a-form-item v-for="source in sources" :key="source" :label="source">
            <a-select
v-model:value="mapping[source]"
:options="targets.map(value => ({ label: value, value }))"
              :placeholder="$t('label.sharedfs.config.target.address')"
:disabled="busy"
@change="invalidatePlan" />
          </a-form-item>
        </a-form>
        <template v-if="plan">
          <a-descriptions bordered size="small" :column="1">
            <a-descriptions-item :label="$t('label.sharedfs.config.revision')">{{ plan.revision }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.sharedfs.config.additions')">{{ plan.diff.additions.length }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.sharedfs.config.updates')">{{ plan.diff.updates.length }}</a-descriptions-item>
            <a-descriptions-item :label="$t('label.sharedfs.config.acl.removal')">{{ plan.diff.aclRemovals.length }}</a-descriptions-item>
          </a-descriptions>
          <a-collapse class="storage-config-backup__section">
            <a-collapse-panel key="diff" :header="$t('label.details')">
              <a-list size="small" :data-source="[...plan.diff.additions, ...plan.diff.updates, ...plan.diff.aclRemovals]">
                <template #renderItem="{ item }"><a-list-item><code>{{ item.type }} · {{ item.key }}</code></a-list-item></template>
              </a-list>
            </a-collapse-panel>
          </a-collapse>
          <a-alert
            v-if="scope.includes('SMB') && !Array.isArray(plan.smbParents)"
            class="storage-config-backup__section"
            type="warning"
            show-icon
            :message="$t('message.sharedfs.config.smb.review.required')" />
          <a-table
            v-if="plan.smbParents?.length"
            class="storage-config-backup__section"
            :columns="smbParentColumns"
            :data-source="plan.smbParents"
            row-key="resourceUuid"
            size="small"

            :scroll="{ x: 620 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'verified'">
                <a-tag>{{ $t(record.code === 'SMB_PARENT_TRAVERSAL_OK'
                  ? 'label.sharedfs.config.smb.verified'
                  : record.code === 'SMB_PARENT_TRAVERSAL_PENDING_ACL'
                    ? 'label.sharedfs.config.smb.no.acl' : 'label.sharedfs.config.smb.not.applicable') }}</a-tag>
              </template>
              <template v-else-if="column.key === 'followup'">
                {{ $t(record.code === 'SMB_PARENT_TRAVERSAL_PENDING_ACL'
                  ? 'label.sharedfs.config.smb.no.acl'
                  : record.bindingPending || record.accountPending
                    ? 'message.sharedfs.config.smb.pending' : 'label.sharedfs.config.smb.verified') }}
              </template>
            </template>
          </a-table>
          <a-alert
v-if="plan.nfsBinding"
class="storage-config-backup__section"
type="warning"
show-icon
            :message="$t('message.sharedfs.config.servicewide')">
            <template #description>
              <div v-for="(row, index) in plan.nfsBinding" :key="index">
                <code>{{ row.after?.port || row.before?.port }}</code>
                <template v-if="row.after"> · {{ row.after.mode }} · {{ row.after.addresses.join(', ') }}</template>
                <template v-else> · {{ $t('label.remove') }}</template>
              </div>
            </template>
          </a-alert>
          <a-checkbox v-if="plan.nfsBinding" v-model:checked="acceptNfs" :disabled="busy">{{ $t('label.sharedfs.config.approve.nfs') }}</a-checkbox>
          <a-checkbox v-if="plan.diff.aclRemovals.length" v-model:checked="acceptAcl" :disabled="busy">{{ $t('label.sharedfs.config.approve.acl') }}</a-checkbox>
          <a-form v-if="credentialFields.length" layout="vertical">
            <a-form-item v-for="field in credentialFields" :key="field.key" :label="field.slot + ' · ' + field.resource">
              <a-input-password v-model:value="secrets[field.key]" autocomplete="new-password" :disabled="busy" />
            </a-form-item>
          </a-form>
        </template>
      </template>
      <template #footer>
        <a-button :disabled="busy" @click="close">{{ $t('label.close') }}</a-button>
        <a-button v-if="mode === 'upload'" type="primary" :loading="busy" :disabled="!file" @click="upload">{{ $t('label.sharedfs.config.upload') }}</a-button>
        <a-button v-else-if="!operation && !plan" type="primary" :loading="busy" :disabled="!canPlan" @click="review">{{ $t('label.sharedfs.config.review') }}</a-button>
        <a-button v-else-if="!operation" type="primary" :loading="busy" :disabled="!canSubmit" @click="apply">{{ $t('label.sharedfs.config.restore') }}</a-button>
      </template>
    </MoldDialog>
  </section>
</template>
<script>
import SharedFSTable from '@/components/storage/SharedFSTable.vue'
import hash from 'hash.js'
import { sharedfsDateInput } from '@/utils/sharedfsPresentation'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { v4 as uuid } from 'uuid'
import { getAPI, postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog.vue'
import StorageConfigurationOperationPanel from '@/components/storage/StorageConfigurationOperationPanel.vue'
import { decodeConfigurationOperation, observeConfigurationOperation } from '@/utils/storageConfigOperation'

const chunkSize = 64 * 1024
const maxBytes = 32 * chunkSize
const protocolValues = ['NFS', 'SMB', 'ISCSI', 'NVME_OF']
const digest = bytes => hash.sha256().update(Array.from(bytes)).digest('hex')

export default {
  name: 'StorageConfigBackupTab',
  components: { ATable: SharedFSTable, PlusOutlined, ReloadOutlined, MoldDialog, StorageConfigurationOperationPanel },
  props: {
    instanceid: { type: String, required: true },
    canApply: Boolean,
    protectedOperation: Boolean
  },
  data () {
    return {
      page: 1,
      rows: [],
      listReadComplete: false,
      busy: false,
      error: '',
      dialogError: '',
      mode: '',
      file: null,
      artifact: null,
      scope: [],
      sources: [],
      targets: [],
      mapping: {},
      inspected: false,
      plan: null,
      acceptAcl: false,
      acceptNfs: false,
      secrets: {},
      operation: null,
      requestid: null,
      jobid: null,
      active: true
    }
  },
  computed: {
    columns () {
      return [
        { key: 'kind', title: this.$t('label.type'), width: 160 },
        { key: 'created', title: this.$t('label.created'), width: 240 },
        { key: 'revision', dataIndex: 'revision', title: this.$t('label.sharedfs.config.revision'), width: 120 },
        { key: 'coverage', title: this.$t('label.protocol'), width: 340 },
        { key: 'actions', title: this.$t('label.actions'), width: 200 }
      ]
    },
    protocols () {
      const coverage = this.artifact?.metadata?.coverage || {}
      return protocolValues.filter(p => coverage[p] && coverage[p] !== 'NOT_CONFIGURED')
        .map(p => ({ value: p, label: p, disabled: coverage[p] !== 'OBSERVED' }))
    },
    credentialFields () {
      return (this.plan?.diff?.credentialSlots || []).flatMap(row => row.slots.map(slot => ({
        key: row.resource + ':' + slot, resource: row.resource, slot
      })))
    },
    canPlan () {
      return this.inspected && this.scope.length > 0 && this.sources.every(source => this.mapping[source])
    },
    smbParentColumns () {
      return [
        { title: this.$t('label.name'), dataIndex: 'name', key: 'name' },
        { title: this.$t('label.sharedfs.config.smb.traversal'), key: 'verified' },
        { title: this.$t('label.sharedfs.config.smb.followup'), key: 'followup' }
      ]
    },
    canSubmit () {
      return this.plan && (!this.scope.includes('SMB') || Array.isArray(this.plan.smbParents)) && this.plan.expires > Date.now() && (!this.plan.nfsBinding || this.acceptNfs) &&
        (!this.plan.diff.aclRemovals.length || this.acceptAcl) && this.credentialFields.every(field => this.secrets[field.key])
    }
  },
  mounted () { this.load() },
  beforeUnmount () { this.active = false; this.secrets = {} },
  methods: {
    createdLabel (value) {
      const input = sharedfsDateInput(value)
      return input ? this.$toLocaleDate(input) : '—'
    },
    coverageLabel (state) {
      return ['OBSERVED', 'NOT_CONFIGURED', 'UNAVAILABLE'].includes(state)
        ? this.$t('label.sharedfs.config.coverage.' + state.toLowerCase()) : state
    },
    async artifactApi (action, fields = {}) {
      const wire = await postAPI('manageStorageConfigArtifact', { action, instanceid: this.instanceid, ...fields },
        { preserveOnFailure: true, timeout: 120000 })
      const envelope = wire.managestorageconfigartifactresponse
      const result = (envelope?.storageconfigartifact || envelope)?.result
      if (!result) throw new Error(this.$t('message.storage.configuration.observation.unavailable'))
      return result
    },
    async run (fn, dialog = false) {
      if (this.busy) return
      this.busy = true
      if (dialog) this.dialogError = ''
      else this.error = ''
      try {
        await fn()
      } catch (error) {
        const data = error.response?.data || {}
        const body = data.errorresponse || Object.values(data).find(value => typeof value?.errortext === 'string')
        const message = (typeof body?.errortext === 'string' ? body.errortext : error.message) ||
          this.$t('message.storage.configuration.observation.unavailable')
        if (dialog) this.dialogError = message
        else this.error = message
      } finally {
        this.busy = false
      }
    },
    failureHint (message) {
      if (message?.includes('RESTORE_SMB_PARENT_OBSERVATION_UNAVAILABLE')) return this.$t('message.sharedfs.config.smb.review.required')
      if (message?.includes('SMB_PARENT_TRAVERSAL_DENIED') || message?.includes('SMB_RUNTIME_BINDING_TRAVERSAL_DENIED')) return this.$t('message.sharedfs.config.smb.denied')
      return message?.includes('RESTORE_EXPLICIT_LISTENER_REQUIRED')
        ? this.$t('message.sharedfs.config.explicit.listener.required') : undefined
    },
    async load () {
      await this.run(async () => { this.rows = (await this.artifactApi('LIST', { page: this.page })).artifacts })
      this.listReadComplete = true
    },
    changePage (direction) {
      this.page += direction
      return this.load()
    },
    async capture () {
      await this.run(async () => {
        await this.artifactApi('CAPTURE')
        this.page = 1
        this.rows = (await this.artifactApi('LIST', { page: this.page })).artifacts
      })
    },
    async download (record) {
      await this.run(async () => {
        if (!Number.isInteger(record.bytes) || record.bytes < 1 || record.bytes > maxBytes) throw new Error(this.$t('message.sharedfs.config.archive.invalid'))
        const bytes = new Uint8Array(record.bytes)
        for (let offset = 0; offset < record.bytes; offset += chunkSize) {
          const index = offset / chunkSize
          const response = await this.artifactApi('DOWNLOAD', { artifactid: record.id, chunkindex: index })
          const binary = atob(response.payload)
          if (response.index !== index || response.sha256 !== record.sha256 ||
              binary.length !== Math.min(chunkSize, record.bytes - offset)) throw new Error(this.$t('message.sharedfs.config.archive.invalid'))
          bytes.set(Uint8Array.from(binary, character => character.charCodeAt(0)), offset)
        }
        if (digest(bytes) !== record.sha256) throw new Error(this.$t('message.sharedfs.config.archive.invalid'))
        const url = URL.createObjectURL(new Blob([bytes], { type: 'application/zip' }))
        const a = document.createElement('a')
        a.href = url
        a.download = 'sharedfs-config-' + record.id + '.zip'
        document.body.appendChild(a)
        a.click()
        a.remove()
        setTimeout(() => URL.revokeObjectURL(url), 1000)
      })
    },
    openUpload () {
      this.close()
      this.mode = 'upload'
    },
    async upload () {
      await this.run(async () => {
        const bytes = new Uint8Array(await this.file.arrayBuffer())
        if (bytes.length < 22 || bytes.length > maxBytes) throw new Error(this.$t('message.sharedfs.config.archive.invalid'))
        let record = await this.artifactApi('BEGIN_UPLOAD', { bytes: bytes.length, sha256: digest(bytes) })
        for (let offset = 0; offset < bytes.length; offset += chunkSize) {
          const chunk = bytes.subarray(offset, offset + chunkSize)
          record = await this.artifactApi('APPEND_UPLOAD', {
            artifactid: record.id,
            chunkindex: offset / chunkSize,
            sha256: digest(chunk),
            payload: btoa(String.fromCharCode(...chunk))
          })
        }
        if (record.state !== 'VALIDATED') throw new Error(this.$t('message.sharedfs.config.archive.invalid'))
        this.file = null
        this.setReview(record)
        await this.fetchMapping()
      }, true)
    },
    setReview (record) {
      this.artifact = record
      this.mode = 'review'
      this.scope = this.protocols.filter(p => !p.disabled).map(p => p.value)
      this.invalidatePlan()
      this.inspected = false
      this.sources = []
      this.targets = []
      this.mapping = {}
    },
    openReview (record) {
      this.close()
      this.setReview(record)
      return this.inspect()
    },
    inspect () { return this.run(() => this.fetchMapping(), true) },
    async fetchMapping () {
      this.invalidatePlan()
      this.inspected = false
      this.sources = []
      this.targets = []
      this.mapping = {}
      if (!this.scope.length) return
      const result = await this.artifactApi('INSPECT', { artifactid: this.artifact.id, protocols: this.scope.join(',') })
      this.sources = result.sources
      this.targets = result.targets
      this.mapping = Object.fromEntries(this.sources.filter(ip => this.targets.includes(ip)).map(ip => [ip, ip]))
      this.inspected = true
    },
    invalidatePlan () {
      this.plan = null
      this.acceptAcl = false
      this.acceptNfs = false
      this.secrets = {}
    },
    review () {
      return this.run(async () => {
        this.plan = await this.artifactApi('PLAN', {
          artifactid: this.artifact.id,
          protocols: this.scope.join(','),
          addressmapping: JSON.stringify(this.mapping)
        })
      }, true)
    },
    apply () {
      return this.run(async () => {
        if (!this.canSubmit) throw new Error(this.$t('message.sharedfs.config.plan.expired'))
        const secrets = {}
        for (const field of this.credentialFields) {
          if (!secrets[field.resource]) secrets[field.resource] = {}
          secrets[field.resource][field.slot] = this.secrets[field.key]
        }
        this.requestid = uuid()
        this.operation = { requestid: this.requestid, state: 'QUEUED' }
        let wire
        try {
          wire = await postAPI('applyStorageConfigRestore', {
            instanceid: this.instanceid,
            planid: this.plan.id,
            sha256: this.plan.sha256,
            expectedrevision: this.plan.revision,
            requestid: this.requestid,
            acceptaclremoval: this.acceptAcl,
            acceptnfsservicewide: this.acceptNfs,
            restoresecrets: JSON.stringify(secrets)
          }, { preserveOnFailure: true })
        } catch (error) {
          this.operation = { requestid: this.requestid, state: 'SUBMISSION_UNCONFIRMED' }
          throw error
        } finally {
          this.secrets = {}
        }
        const result = wire.applystorageconfigrestoreresponse || wire
        this.jobid = result.jobid
        const direct = decodeConfigurationOperation(result)
        if (direct) this.operation = direct
        if (this.jobid) await this.observe()
      }, true)
    },
    async observe () {
      await observeConfigurationOperation({
        get: (api, args) => getAPI(api, api === 'listStorageServiceConfigurationOperations' ? { ...args, page: 1, pagesize: 10 } : args),
        instanceid: this.instanceid,
        requestid: this.requestid,
        jobid: this.jobid,
        current: () => this.active && !!this.mode,
        changed: value => { this.operation = value }
      })
    },
    track () { return this.run(() => this.observe(), true) },
    resume (operation) {
      return this.run(async () => {
        const wire = await postAPI('resumeStorageServiceConfigurationOperation', { instanceid: this.instanceid, id: operation.id })
        this.operation = decodeConfigurationOperation(wire) || this.operation
        if (!this.operation || this.operation.state === 'RECOVERY_REQUIRED') await this.observe()
      }, true)
    },
    close () {
      if (this.busy) return
      this.mode = ''
      this.dialogError = ''
      this.file = null
      this.operation = null
      this.requestid = null
      this.jobid = null
      this.invalidatePlan()
    }
  }
}
</script>
<style scoped>
.storage-config-backup { color: var(--text-color); min-width: 0; }
.storage-config-backup__section { margin-top: 16px; }
.storage-config-backup code { color: inherit; overflow-wrap: anywhere; }
.storage-config-backup :deep(.ant-table-tbody > tr > td:nth-child(-n+2)) { white-space: nowrap; }
</style>
