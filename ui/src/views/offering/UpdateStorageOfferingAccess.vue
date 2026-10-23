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
      <div class="volume-operation-target"><strong>{{ resource.name }}</strong><a-tag>{{ record.state }}</a-tag></div>
      <a-alert v-if="readError" type="error" :message="readError" show-icon />
      <a-button v-if="readError" class="operation-retry" @click="load">{{ $t('label.retry') }}</a-button>
      <a-form v-if="loaded" layout="vertical">
        <a-form-item v-if="managementAdmin && managementFields.domainid" :label="$t('label.ispublic')"><a-switch v-model:checked="form.ispublic" :disabled="managementDisabled" :aria-label="$t('label.ispublic')" /></a-form-item>
        <a-form-item v-if="!form.ispublic && managementFields.domainid" :label="$t('label.domainid')">
          <a-select v-model:value="form.domainid" mode="multiple" show-search option-filter-prop="label" :disabled="managementDisabled" :options="domains.map(d => ({ value: d.id, label: d.path || d.name }))" :aria-label="$t('label.domainid')" />
          <p v-if="retainedDomains.length" class="operation-help">{{ $t('message.storage.management.domains.retained') }} {{ retainedDomains.join(', ') }}</p>
        </a-form-item>
        <a-form-item v-if="managementAdmin && managementFields.zoneid" :label="$t('label.zoneid')">
          <a-select
            v-model:value="form.zoneid"
            mode="multiple"
            show-search
            option-filter-prop="label"
            :disabled="managementDisabled"
            :options="zones.map(z => ({ value: z.id, label: z.name }))"
            :placeholder="$t('label.all.zone')"
            :aria-label="$t('label.zoneid')" />
          <p class="operation-help">{{ $t('message.storage.management.zones') }}</p>
        </a-form-item>
        <p v-else class="operation-help">{{ $t('message.storage.management.zones.admin') }}</p>
      </a-form>
      <a-alert v-if="submitError" type="error" :message="submitError" show-icon />
      <a-alert v-if="unknownResult" class="operation-retry" type="warning" :message="$t('message.offering.update.unknown')" show-icon />
      <a-button v-if="unknownResult || submitError" class="operation-retry" :disabled="saving" @click="load">{{ $t('label.refresh') }}</a-button>
      <MoldDialogActions class="operation-footer" :busy="loading || saving"><a-button :disabled="saving" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button><a-button type="primary" :loading="saving" :disabled="managementDisabled" @click="submit">{{ $t('label.ok') }}</a-button></MoldDialogActions>
    </a-spin>
  </div>
</template>
<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { postAPI } from '@/api'
import { getAPI } from '@/utils/storageApi'
import management from '@/utils/storageManagementMixin'
import { readAllocationList } from '@/utils/volumeAllocation'
import { scopeIds, accessParams, offeringSnapshot } from '@/utils/storageManagement'
export default {
  components: { MoldDialogActions },
  name: 'UpdateStorageOfferingAccess',
  mixins: [management],
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data: () => ({ record: {}, domains: [], zones: [], form: { ispublic: false, domainid: [], zoneid: [] }, postStarted: false }),
  computed: {
    offeringType () { return this.$route.meta.name === 'diskoffering' ? 'DiskOffering' : 'ServiceOffering' },
    managementApi () { return 'update' + this.offeringType },
    retainedDomains () { return scopeIds(this.record.domainid).filter(id => !this.domains.some(d => d.id === id)) }
  },
  methods: {
    async readRecord () {
      const command = 'list' + this.offeringType + 's'
      const result = await getAPI(command, { id: this.resource.id, listall: true, state: 'all' }, { preserveOnFailure: true })
      const record = result?.[command.toLowerCase() + 'response']?.[this.offeringType.toLowerCase()]?.find(row => row.id === this.resource.id)
      if (!record) throw new Error('message.offering.not.found')
      return record
    },
    async choices (current) {
      const domains = await readAllocationList(getAPI, 'listDomains', 'domain', { listall: true, isrecursive: true, details: 'min' }, current)
      const zones = this.managementAdmin ? await readAllocationList(getAPI, 'listZones', 'zone', {}, current) : []
      return { domains, zones }
    },
    async load () {
      const version = ++this.version; const context = this.managementContext; const current = () => this.currentRequest(version, context)
      this.loading = true; this.loaded = false; this.readError = ''; this.submitError = ''; this.unknownResult = false; this.postStarted = false
      try {
        this.checkManagementPermission()
        const record = await this.readRecord(); const lists = await this.choices(current)
        if (!current()) return
        if (this.managementAdmin && (scopeIds(record.domainid).some(id => !lists.domains.some(d => d.id === id)) || scopeIds(record.zoneid).some(id => !lists.zones.some(z => z.id === id)))) throw new Error('message.storage.management.selection.changed')
        this.record = record; this.domains = lists.domains; this.zones = lists.zones
        this.form = { ispublic: !scopeIds(record.domainid).length, domainid: scopeIds(record.domainid).filter(id => this.domains.some(d => d.id === id)), zoneid: scopeIds(record.zoneid) }
        this.loaded = true
      } catch (error) { if (current()) this.readError = this.managementReason(error) } finally { if (current()) this.loading = false }
    },
    async submit () {
      if (this.managementDisabled) return
      const version = this.version; const context = this.managementContext; const current = () => this.currentRequest(version, context)
      this.saving = true; this.submitError = ''; this.postStarted = false
      try {
        this.checkManagementPermission()
        const fresh = await this.readRecord(); const lists = await this.choices(current)
        if (!current()) return
        if (offeringSnapshot(fresh) !== offeringSnapshot(this.record)) throw new Error('message.storage.management.changed')
        if (this.form.zoneid.some(id => !lists.zones.some(z => z.id === id)) && this.managementAdmin) throw new Error('message.storage.management.selection.changed')
        const params = accessParams(this.record, this.form, { admin: this.managementAdmin, managedDomainIds: lists.domains.map(d => d.id), supported: this.managementFields })
        if (Object.keys(params).length === 1) { this.$emit('close-action'); return }
        this.postStarted = true
        await postAPI(this.managementApi, params, { preserveOnFailure: true })
        if (!current()) return
        this.$message.success(this.$t('label.action.update.offering.access')); this.$emit('refresh-data'); this.$emit('close-action')
      } catch (error) { if (current()) this.failedSubmission(error) } finally { if (current()) this.saving = false }
    }
  }
}
</script>
<style scoped lang="scss">
@import '@/style/volume-operation-dialog';
</style>
