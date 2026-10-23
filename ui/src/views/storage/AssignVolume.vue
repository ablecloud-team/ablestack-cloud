<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance with the License.
You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations
under the License. -->
<template>
  <div class="volume-operation-form" v-ctrl-enter="submit">
    <a-spin :spinning="loading || saving">
      <div class="volume-operation-target"><strong>{{ resource.name }}</strong><a-tag>{{ resource.type }}</a-tag></div>
      <a-alert v-if="readError" type="error" :message="readError" show-icon />
      <a-button v-if="readError" class="operation-retry" @click="load">{{ $t('label.retry') }}</a-button>
      <a-form v-if="loaded" layout="vertical">
        <a-form-item>
          <template #label><span class="operation-label"><span>{{ $t('label.volume.assign.target') }}</span><span class="operation-secondary">{{ $t('label.current') }}: {{ record.project || record.account }} · {{ record.domain }}</span></span></template>
          <a-radio-group v-model:value="form.type" :disabled="managementDisabled" @change="form.target = null; form.confirm = false">
            <a-radio v-if="managementFields.accountid" value="account">{{ $t('label.account') }}</a-radio><a-radio v-if="managementFields.projectid" value="project">{{ $t('label.project') }}</a-radio>
          </a-radio-group>
          <a-select v-model:value="form.target" show-search option-filter-prop="label" :disabled="managementDisabled" :options="candidates.map(row => ({ value: row.id, label: (row.displaytext || row.name) + ' · ' + (row.domain || row.domainid) }))" :aria-label="$t('label.volume.assign.target')" />
          <p class="operation-help">{{ $t('message.volume.assign.help') }}</p>
        </a-form-item>
        <a-checkbox v-model:checked="form.confirm" :disabled="managementDisabled">{{ $t('message.volume.assign.confirm') }}</a-checkbox>
      </a-form>
      <a-alert v-if="submitError" class="operation-retry" type="error" :message="submitError" show-icon />
      <a-alert v-if="unknownResult" class="operation-retry" type="warning" :message="$t('message.storage.management.unknown')" show-icon />
      <a-button v-if="unknownResult || submitError" class="operation-retry" :disabled="saving" @click="load">{{ $t('label.refresh') }}</a-button>
      <MoldDialogActions class="operation-footer" :busy="loading || saving"><div class="operation-summary">{{ selectedTarget?.name || $t('message.error.select') }}</div><a-button :disabled="saving" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button><a-button type="primary" :loading="saving" :disabled="managementDisabled || !selectedTarget || !form.confirm" @click="submit">{{ $t('label.volume.assign') }}</a-button></MoldDialogActions>
    </a-spin>
  </div>
</template>
<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { postAPI } from '@/api'
import { getAPI } from '@/utils/storageApi'
import management from '@/utils/storageManagementMixin'
import { readAllocationList } from '@/utils/volumeAllocation'
import { assignmentReason, assignmentSnapshot, assignmentParams } from '@/utils/storageManagement'
export default {
  components: { MoldDialogActions },
  name: 'AssignVolume',
  mixins: [management],
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data: () => ({ record: {}, accounts: [], projects: [], form: { type: 'account', target: null, confirm: false }, postStarted: false }),
  computed: {
    managementApi: () => 'assignVolume',
    candidates () {
      return (this.form.type === 'account' ? this.accounts : this.projects).filter(row => {
        try { assignmentParams(this.record, this.form.type, row); return true } catch (error) { return false }
      })
    },
    selectedTarget () { return this.candidates.find(row => row.id === this.form.target) }
  },
  methods: {
    async readRecord () {
      const result = await getAPI('listVolumes', { id: this.resource.id, listall: true, ignoreproject: true }, { preserveOnFailure: true })
      const record = result?.listvolumesresponse?.volume?.find(row => row.id === this.resource.id)
      if (!record) throw new Error('message.storage.management.not.found')
      const reason = assignmentReason(record, this.$store.getters.userInfo.roletype)
      if (reason) throw new Error(reason)
      return record
    },
    async choices (current) {
      const args = { listall: true, isrecursive: true, ignoreproject: true }
      const accounts = this.managementFields.accountid ? await readAllocationList(getAPI, 'listAccounts', 'account', args, current) : []
      const projects = this.managementFields.projectid ? await readAllocationList(getAPI, 'listProjects', 'project', args, current) : []
      return { accounts, projects }
    },
    async load () {
      const version = ++this.version; const context = this.managementContext; const current = () => this.currentRequest(version, context)
      this.loading = true; this.loaded = false; this.readError = ''; this.submitError = ''; this.unknownResult = false; this.postStarted = false
      try {
        this.checkManagementPermission()
        const record = await this.readRecord(); const lists = await this.choices(current)
        if (!current()) return
        this.record = record; this.accounts = lists.accounts; this.projects = lists.projects
        this.form = { type: this.managementFields.accountid ? 'account' : 'project', target: null, confirm: false }; this.loaded = true
      } catch (error) { if (current()) this.readError = this.managementReason(error) } finally { if (current()) this.loading = false }
    },
    async submit () {
      if (this.managementDisabled || !this.selectedTarget || !this.form.confirm) return
      const version = this.version; const context = this.managementContext; const current = () => this.currentRequest(version, context)
      this.saving = true; this.submitError = ''; this.postStarted = false
      try {
        this.checkManagementPermission()
        const record = await this.readRecord(); const lists = await this.choices(current)
        const snapshots = await getAPI('listSnapshots', { volumeid: this.resource.id, listall: true, ignoreproject: true, page: 1, pagesize: 1 }, { preserveOnFailure: true })
        if (!current()) return
        if (!snapshots?.listsnapshotsresponse) throw new Error('message.storage.management.selection.changed')
        if (assignmentSnapshot(record) !== assignmentSnapshot(this.record)) throw new Error('message.storage.management.changed')
        if ((snapshots?.listsnapshotsresponse?.count || snapshots?.listsnapshotsresponse?.snapshot?.length || 0) > 0) throw new Error('message.volume.assign.snapshots')
        const target = (this.form.type === 'account' ? lists.accounts : lists.projects).find(row => row.id === this.form.target)
        const params = assignmentParams(record, this.form.type, target)
        this.postStarted = true
        const result = await postAPI(this.managementApi, params, { preserveOnFailure: true })
        if (!current()) return
        const assigned = result?.assignvolumeresponse
        if ((assigned?.volume?.id || assigned?.id) !== record.id) throw new Error('message.storage.management.unknown')
        this.$message.success(this.$t('message.volume.assign.saved')); this.$emit('close-action')
        await this.$router.push({ path: '/volume' })
      } catch (error) { if (current()) this.failedSubmission(error) } finally { if (current()) this.saving = false }
    }
  }
}
</script>
<style scoped lang="scss">
@import '@/style/volume-operation-dialog';
.ant-radio-group { display: flex; gap: 12px; margin-bottom: 12px; }
</style>
