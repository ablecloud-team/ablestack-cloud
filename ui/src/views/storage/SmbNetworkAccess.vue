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
  <section class="storage-table-section smb-network-access">
    <h4>{{ $t('label.smb.network.access') }}</h4>
    <p>{{ $t('message.smb.network.access.help') }}</p>
    <a-space class="network-actions" wrap>
      <a-button v-if="canCreate" type="primary" :disabled="!shares.length" @click="openEditor()"><template #icon><PlusOutlined /></template>{{ $t('label.smb.network.create') }}</a-button>
      <a-button :loading="loading" @click="refresh"><template #icon><ReloadOutlined /></template>{{ $t('label.refresh') }}</a-button>
    </a-space>
    <a-alert v-if="readError" type="warning" show-icon :message="$t('message.smb.network.read.failed')" />
    <a-table size="small" row-key="id" :columns="columns" :data-source="rows" :loading="loading" :pagination="false" :scroll="{ x: 850 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'state'"><a-tag>{{ record.state }}</a-tag></template>
        <template v-else-if="column.key === 'actions'">
          <a-space><a-button v-if="canUpdate" size="small" @click="openEditor(record)"><template #icon><EditOutlined /></template>{{ $t('label.edit') }}</a-button><a-button v-if="canDelete" size="small" danger @click="deleteTarget = record"><template #icon><DeleteOutlined /></template>{{ $t('label.delete') }}</a-button></a-space>
        </template>
      </template>
    </a-table>
    <a-table size="small" row-key="id" :columns="summaryColumns" :data-source="summaries" :pagination="false" :scroll="{ x: 700 }" class="network-summary" />
    <a-modal v-model:visible="editing" :title="$t(editId ? 'label.smb.network.edit' : 'label.smb.network.create')" :confirm-loading="saving" :ok-button-props="{ disabled: !form.shareid || !form.sources.length }" :body-style="{ maxHeight: '65vh', overflowY: 'auto' }" @ok="save">
      <a-form layout="vertical">
        <a-form-item :label="$t('label.share')" required>
          <a-select v-model:value="form.shareid" :disabled="!!editId"><a-select-option v-for="share in shares" :key="share.id" :value="share.id">{{ share.name }}</a-select-option></a-select>
        </a-form-item>
        <a-form-item :label="$t('label.smb.network.sources')" required>
          <a-select v-model:value="form.sources" mode="tags" :token-separators="[',']" :placeholder="$t('message.smb.network.sources.example')" />
        </a-form-item>
        <a-alert type="info" show-icon :message="$t('message.smb.network.account.and.source')" />
      </a-form>
    </a-modal>
    <a-modal :visible="!!deleteTarget" :title="$t('label.smb.network.delete')" :confirm-loading="saving" :body-style="{ maxHeight: '65vh', overflowY: 'auto' }" @cancel="deleteTarget = null" @ok="remove">
      <a-alert :type="isLastRule ? 'warning' : 'info'" show-icon :message="$t(isLastRule ? 'message.smb.network.last.delete' : 'message.smb.network.delete.confirm')" />
      <p>{{ deleteTarget?.principal }}</p>
    </a-modal>
  </section>
</template>
<script>
import { getAPI, postAPI } from '@/api'
import { PlusOutlined, ReloadOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons-vue'
export default {
  name: 'SmbNetworkAccess',
  components: { PlusOutlined, ReloadOutlined, EditOutlined, DeleteOutlined },
  props: {
    instanceId: { type: String, default: '' },
    shares: { type: Array, default: () => [] },
    runtime: { type: Object, default: () => ({}) }
  },
  emits: ['refresh'],
  data () { return { rules: [], loading: false, readError: false, requestToken: 0, editing: false, saving: false, editId: '', deleteTarget: null, form: { shareid: '', sources: [] } } },
  computed: {
    canCreate () { return 'createStorageSmbNetworkAcl' in (this.$store?.getters?.apis || {}) },
    canUpdate () { return 'updateStorageSmbNetworkAcl' in (this.$store?.getters?.apis || {}) },
    canDelete () { return 'deleteStorageSmbNetworkAcl' in (this.$store?.getters?.apis || {}) },
    columns () {
      return [
        { title: this.$t('label.share'), dataIndex: 'shareName', key: 'shareName', width: 180 },
        { title: this.$t('label.storage.service.principal.type'), dataIndex: 'principaltype', key: 'principaltype', width: 130 },
        { title: this.$t('label.smb.network.sources'), dataIndex: 'principal', key: 'principal', width: 230 },
        { title: this.$t('label.state'), dataIndex: 'state', key: 'state', width: 110 },
        { title: this.$t('label.actions'), key: 'actions', fixed: 'right', align: 'right', width: 160 }
      ]
    },
    summaryColumns () { return [{ title: this.$t('label.share'), dataIndex: 'name' }, { title: this.$t('label.smb.network.desired'), dataIndex: 'desired' }, { title: this.$t('label.smb.network.runtime'), dataIndex: 'runtime' }, { title: this.$t('label.state'), dataIndex: 'status' }] },
    rows () { return this.rules.map(rule => ({ ...rule, shareName: this.shares.find(share => share.id === rule.resourceid)?.name || rule.resourceid })) },
    summaries () {
      return this.shares.map(share => {
        const rules = this.rules.filter(rule => rule.resourceid === share.id)
        const desired = rules.map(rule => rule.principal).sort()
        const actual = this.runtime[share.id] || this.runtime[share.uuid] || {}
        const observed = [...(actual.allowedSources || [])].sort()
        const seen = !!actual.networkAccessMode
        return { id: share.id, name: share.name, desired: desired.join(', ') || this.$t('label.smb.network.any'), runtime: seen ? (observed.join(', ') || this.$t('label.smb.network.any')) : this.$t('label.unknown'), status: seen ? (JSON.stringify(desired) === JSON.stringify(observed) ? 'CONSISTENT' : 'DRIFT') : 'UNOBSERVED' }
      })
    },
    isLastRule () { return !!this.deleteTarget && this.rules.filter(rule => rule.resourceid === this.deleteTarget.resourceid).length === 1 }
  },
  watch: { instanceId: { immediate: true, handler () { this.rules = []; this.editing = false; this.deleteTarget = null; this.refresh() } } },
  beforeUnmount () { this.requestToken++ },
  methods: {
    async refresh () {
      const token = ++this.requestToken
      if (!this.instanceId) { this.loading = false; this.readError = false; return }
      this.loading = true; this.readError = false
      try {
        const result = await getAPI('listStorageSmbNetworkAcls', { instanceid: this.instanceId }, { preserveOnFailure: true, timeout: 15000 })
        if (token === this.requestToken) this.rules = result.liststoragesmbnetworkaclsresponse.storageaccessrule || []
      } catch (error) { if (token === this.requestToken) this.readError = true } finally { if (token === this.requestToken) this.loading = false }
    },
    openEditor (rule) {
      this.editId = rule?.id || ''
      this.form = { shareid: rule?.resourceid || this.shares[0]?.id || '', sources: rule ? [rule.principal] : [] }
      this.editing = true
    },
    async mutate (command, params) {
      const result = await postAPI(command, params)
      const payload = result[command.toLowerCase() + 'response'] || result
      if (payload.jobid) {
        await new Promise((resolve, reject) => this.$pollJob({ jobId: payload.jobid, title: this.$t('label.smb.network.access'), successMethod: resolve, errorMethod: reject }))
      }
      await this.refresh(); this.$emit('refresh')
    },
    async save () {
      if (this.saving || !this.form.shareid || !this.form.sources.length) return
      if (this.editId && this.form.sources.length !== 1) { this.$message.error(this.$t('message.smb.network.edit.one')); return }
      this.saving = true
      try {
        const params = this.editId ? { id: this.editId, principal: this.form.sources[0], principaltype: this.form.sources[0].includes('/') ? 'CIDR' : 'IP_ADDRESS' } : { shareid: this.form.shareid, principals: this.form.sources.join(',') }
        await this.mutate(this.editId ? 'updateStorageSmbNetworkAcl' : 'createStorageSmbNetworkAcl', params); this.editing = false
      } catch (error) { this.$notifyError(error) } finally { this.saving = false }
    },
    async remove () {
      if (this.saving || !this.deleteTarget) return
      this.saving = true
      try { await this.mutate('deleteStorageSmbNetworkAcl', { id: this.deleteTarget.id }); this.deleteTarget = null } catch (error) { this.$notifyError(error) } finally { this.saving = false }
    }
  }
}
</script>
<style scoped>
.network-actions { margin: 0 0 16px; }
.network-summary { margin-top: 16px; }
</style>
