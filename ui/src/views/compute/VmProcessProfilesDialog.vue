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
  <a-modal :visible="visible" :title="$t('label.vmprocess.profiles')" :width="860" :mask-closable="false" @cancel="close">
    <div class="profile-toolbar">
      <a-button :loading="loading" :disabled="busy" @click="load"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
    </div>
    <a-alert type="info" show-icon :message="$t(platform === 'windows' ? 'message.vmprocess.profile.registration.windows' : 'message.vmprocess.profile.registration')" class="profile-alert" />
    <a-alert v-if="guestStatus !== 'AVAILABLE'" type="warning" show-icon :message="$t(guestStatus === 'VM_STOPPED' ? 'message.vmprocess.profile.vm.stopped' : 'message.vmprocess.profile.unavailable')" class="profile-alert" />
    <a-alert v-if="historyLimited" type="info" show-icon :message="$t('message.vmprocess.profile.history.limited')" class="profile-alert" />
    <a-alert v-if="error" type="error" show-icon :message="error" class="profile-alert" />
    <a-table :columns="columns" :data-source="profiles" :row-key="item => item.id + ':' + item.version" :pagination="false" size="small" :scroll="{ x: 720 }" class="profile-table">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">{{ record.displayName || record.id }}<br><span class="profile-secondary">{{ record.id }} · v{{ record.version || '—' }}</span></template>
        <template v-else-if="column.key === 'environment'">{{ record.executable || '—' }}<br><span class="profile-secondary">{{ record.account === 'S-1-5-18' ? 'LocalSystem · Session 0' : record.account || '—' }} · {{ record.cwd || '—' }}<br>{{ $t('label.vmprocess.profile.environment') }}: {{ record.environmentRef || $t('label.vmprocess.profile.inherited') }}</span></template>
        <template v-else-if="column.key === 'state'"><a-tag :color="record.registrationState === 'APPROVED' ? 'green' : 'orange'">{{ $t('label.vmprocess.profile.state.' + record.registrationState) }}</a-tag><br><span v-if="record.available === false" class="profile-secondary">{{ $t('message.vmprocess.profile.definition.absent') }}</span></template>
        <template v-else-if="column.key === 'actions'">
          <a-button v-if="record.registrationState === 'UNREGISTERED' && record.available" size="small" :disabled="busy" @click="choose(record, 'REGISTER')">{{ $t('label.vmprocess.profile.register') }}</a-button>
          <a-button v-if="record.registrationState === 'REGISTERED' && record.available" size="small" type="primary" :disabled="busy" @click="choose(record, 'APPROVE')">{{ $t('label.vmprocess.profile.approve') }}</a-button>
          <a-button v-if="['REGISTERED', 'APPROVED'].includes(record.registryState || record.registrationState)" size="small" danger :disabled="busy" @click="choose(record, 'RETIRE')">{{ $t('label.vmprocess.profile.retire') }}</a-button>
        </template>
      </template>
      <template #emptyText>{{ $t(loading ? 'message.vmprocess.profile.loading' : 'message.vmprocess.profile.empty') }}</template>
    </a-table>
    <template #footer><a-button :disabled="busy" @click="close">{{ $t('label.close') }}</a-button></template>
    <a-modal :visible="!!confirmation" :title="confirmation ? $t('label.vmprocess.profile.' + confirmation.operation.toLowerCase()) : ''" :confirm-loading="busy" :mask-closable="false" @ok="manage" @cancel="confirmation = null">
      <a-descriptions v-if="confirmation" bordered :column="1" size="small" class="profile-description">
        <a-descriptions-item :label="$t('label.vm')">{{ resource.displayname || resource.name }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vmprocess.profile')">{{ confirmation.profile.displayName }} · v{{ confirmation.profile.version }}</a-descriptions-item>
        <a-descriptions-item :label="$t('label.vmprocess.profile.fingerprint')">{{ confirmation.profile.definitionHash }}</a-descriptions-item>
      </a-descriptions>
      <a-alert type="warning" show-icon class="profile-alert" :message="$t('message.vmprocess.profile.' + (confirmation?.operation || 'REGISTER').toLowerCase())" />
    </a-modal>
  </a-modal>
</template>
<script>
import { getAPI, postAPI } from '@/api'
const response = (json, api) => json?.[api.toLowerCase() + 'response']?.processprofiles?.processstate
export default {
  name: 'VmProcessProfilesDialog',
  props: { visible: Boolean, platform: { type: String, default: '' }, resource: { type: Object, required: true } },
  emits: ['close', 'updated'],
  data () { return { profiles: [], guestStatus: 'AVAILABLE', historyLimited: false, loading: false, busy: false, error: '', confirmation: null, generation: 0 } },
  computed: {
    columns () { return [{ key: 'name', title: this.$t('label.vmprocess.profile'), width: 230 }, { key: 'environment', title: this.$t('label.vmprocess.profile.execution'), width: 290 }, { key: 'state', title: this.$t('label.state'), width: 100 }, { key: 'actions', title: this.$t('label.actions'), width: 140 }] }
  },
  watch: { visible (value) { this.generation++; this.confirmation = null; if (value) this.load() }, 'resource.id' () { this.generation++; this.profiles = []; this.error = '' } },
  beforeUnmount () { this.generation++ },
  methods: {
    close () { if (!this.busy) this.$emit('close') },
    choose (profile, operation) { if (!this.busy) this.confirmation = { profile: { ...profile }, operation } },
    async load () {
      const token = this.generation
      this.loading = true; this.error = ''
      try {
        const state = response(await getAPI('listVirtualMachineProcessProfiles', { virtualmachineid: this.resource.id }), 'listVirtualMachineProcessProfiles')
        if (token !== this.generation || !this.visible) return
        if (!Array.isArray(state?.profiles)) throw new Error('unavailable')
        this.guestStatus = state.guestStatus || 'AVAILABLE'; this.historyLimited = !!state.historyLimited; this.profiles = state.profiles; this.$emit('updated', this.profiles)
      } catch (_) { if (token === this.generation) this.error = this.$t('message.vmprocess.profile.unavailable') } finally { if (token === this.generation) this.loading = false }
    },
    async manage () {
      if (this.busy || !this.confirmation) return
      const token = this.generation; const choice = this.confirmation
      this.busy = true; this.error = ''
      try {
        await postAPI('manageVirtualMachineProcessProfile', { virtualmachineid: this.resource.id, profileid: choice.profile.id, profileversion: choice.profile.version, operation: choice.operation })
        if (token !== this.generation) return
        this.confirmation = null; await this.load()
      } catch (_) { if (token === this.generation) this.error = this.$t('message.vmprocess.profile.changed') } finally { if (token === this.generation) this.busy = false }
    }
  }
}
</script>
<style scoped lang="scss">
.profile-toolbar { display: flex; gap: 8px; margin-bottom: 16px; }
.profile-alert { margin: 12px 0; }
.profile-secondary { color: var(--ui-text-secondary); font-size: 12px; overflow-wrap: anywhere; }
.profile-table :deep(.ant-table), .profile-table :deep(.ant-table-tbody > tr > td) { color: var(--ui-text-primary); background: var(--ui-bg-surface); border-color: var(--ui-border); overflow-wrap: anywhere; }
.profile-table :deep(.ant-table-thead > tr > th) { color: var(--ui-text-secondary); background: var(--ui-bg-page); border-color: var(--ui-border); }
.profile-description :deep(.ant-descriptions-item-label) { color: var(--ui-text-primary); background: var(--ui-bg-page); }
.profile-description :deep(.ant-descriptions-item-content) { color: var(--ui-text-secondary); background: var(--ui-bg-surface); overflow-wrap: anywhere; }
.profile-description :deep(.ant-descriptions-view), .profile-description :deep(.ant-descriptions-item-label), .profile-description :deep(.ant-descriptions-item-content) { border-color: var(--ui-border); }
</style>
