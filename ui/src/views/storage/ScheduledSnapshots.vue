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
  <div class="list-schedule">
    <a-table size="small" :columns="columns" :dataSource="dataSource" :rowKey="record => record.id" :pagination="false" :loading="loading" :scroll="{ x: 880 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'intervaltype'"><router-link :to="{ path: '/snapshotpolicy/' + record.id, ...(record.projectid ? { query: { projectid: record.projectid } } : {}) }">{{ intervalText(record.intervaltype) }}</router-link></template>
        <template v-if="column.key === 'time'">{{ scheduleText(record) }}</template>
        <template v-if="column.key === 'timezone'">{{ getTimeZone(record.timezone) }}</template>
        <template v-if="column.key === 'tags'"><a-tag v-for="(tag, index) in record.tags" :key="index">{{ tag.key + '=' + tag.value }}</a-tag></template>
        <template v-if="column.key === 'zones'">
          <div v-for="zone in record.zone" :key="zone.id"><router-link v-if="'listZones' in $store.getters.apis" :to="{ path: '/zone/' + zone.id }">{{ zone.name }}</router-link><span v-else>{{ zone.name }}</span></div>
        </template>
        <template v-if="column.key === 'storage'">
          <div v-for="pool in record.storage" :key="pool.id"><router-link v-if="'listStoragePools' in $store.getters.apis" :to="{ path: '/storagepool/' + pool.id }">{{ pool.name }}</router-link><span v-else>{{ pool.name }}</span></div>
        </template>
        <template v-if="column.key === 'scheduling'">{{ schedulingText(record) }}</template>
        <template v-if="column.key === 'actions'">
          <div class="policy-row-actions">
          <tooltip-button v-if="canEdit" :tooltip="$t('label.snapshot.policy.edit')" icon="edit-outlined" size="small" :disabled="disabled" @onClick="openPolicy(record, 'edit')" />
          <tooltip-button v-if="canManage && typeof record.fordisplay === 'boolean'" :tooltip="$t(record.fordisplay ? 'label.snapshot.policy.pause' : 'label.snapshot.policy.resume')" :icon="record.fordisplay ? 'pause-circle-outlined' : 'play-circle-outlined'" size="small" :disabled="disabled" @onClick="openPolicy(record, record.fordisplay ? 'pause' : 'resume')" />
          <tooltip-button
            v-if="canDelete"
            :tooltip="$t('label.delete')"
            type="primary"
            :danger="true"
            icon="close-outlined"
            size="small"

            :disabled="disabled"
            @onClick="handleClickDelete(record)" />
          </div>
        </template>
      </template>
    </a-table>
    <SnapshotPolicyDeleteDialog v-if="policyTarget && policyMode === 'delete'" :key="policyTarget.id" :resource="policyTarget" @refresh-data="$emit('refresh')" @close-action="closePolicyDelete" />
    <SnapshotPolicyActionDialog v-else-if="policyTarget" :key="policyTarget.id + policyMode" :resource="policyTarget" :current-action="{ policyMode }" @refresh-data="$emit('refresh')" @close-action="policyTarget = null" />
  </div>
</template>
<script>
import TooltipButton from '@/components/widgets/TooltipButton'
import { timeZoneName } from '@/utils/timezone'
import SnapshotPolicyActionDialog from './SnapshotPolicyActionDialog'
import SnapshotPolicyDeleteDialog from './SnapshotPolicyDeleteDialog'
import { policyInterval, policySchedule, policyScheduling } from '@/utils/snapshotPolicyPresentation'
import { canDeletePolicy } from '@/utils/snapshotPolicyDeletion'
import { canEditPolicy, canManagePolicy } from '@/utils/snapshotPolicyEditing'

export default {
  name: 'ScheduledSnapshots',
  components: { TooltipButton, SnapshotPolicyActionDialog, SnapshotPolicyDeleteDialog },
  props: { loading: Boolean, disabled: Boolean, active: { type: Boolean, default: true }, dataSource: { type: Array, required: true }, resource: { type: Object, required: true } },
  emits: ['refresh'],
  data () { return { policyTarget: null, policyMode: '', deleteTrigger: null, deleteFocusScope: '', pendingDeleteFocus: false, disposed: false, listDayOfWeek: ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'] } },
  computed: {
    canDelete () { return canDeletePolicy(this.$store.getters.apis) },
    canEdit () { return canEditPolicy(this.$store.getters.apis || {}) },
    canManage () { return canManagePolicy(this.$store.getters) },
    ownerScope () { return JSON.stringify([this.resource.id, this.$route?.path, this.$store.getters.userInfo?.id, this.$store.state?.user?.token, this.$store.getters.project?.id]) },
    columns () {
      return [
        { key: 'intervaltype', title: this.$t('label.intervaltype'), dataIndex: 'intervaltype', width: 100 },
        { key: 'time', title: this.$t('label.time'), dataIndex: 'schedule', width: 160 },
        { key: 'timezone', title: this.$t('label.timezone'), dataIndex: 'timezone', width: 140 },
        { key: 'keep', title: this.$t('label.keep'), dataIndex: 'maxsnaps', width: 80 },
        { key: 'zones', title: this.$t('label.zones'), dataIndex: 'zone' },
        { key: 'storage', title: this.$t('label.storage'), dataIndex: 'storage' },
        { key: 'tags', title: this.$t('label.tags'), dataIndex: 'tags' },
        { key: 'scheduling', title: this.$t('label.snapshot.policy.scheduling'), width: 120 },
        ...(this.canDelete || this.canEdit || this.canManage ? [{ key: 'actions', title: this.$t('label.actions'), width: 136 }] : [])
      ]
    }
  },
  watch: {
    ownerScope () { this.policyTarget = null; this.pendingDeleteFocus = false; this.deleteTrigger = null },
    active (value) { if (!value) { this.policyTarget = null; this.pendingDeleteFocus = false } },
    disabled (value) { if (!value) this.$nextTick(this.restorePolicyFocus) }
  },
  beforeUnmount () { this.disposed = true },
  methods: {
    openPolicy (record, mode) {
      if (this.disabled || this.disposed || this.policyTarget || !(mode === 'delete' ? this.canDelete : mode === 'edit' ? this.canEdit : this.canManage) || !this.dataSource.some(row => row.id === record.id && row.volumeid === this.resource.id)) return
      if (mode === 'delete') { this.deleteTrigger = document.activeElement; this.deleteFocusScope = this.ownerScope; this.pendingDeleteFocus = false }
      this.policyTarget = Object.freeze({ ...record }); this.policyMode = mode
    },
    closePolicyDelete () { this.policyTarget = null; this.pendingDeleteFocus = true; this.$nextTick(this.restorePolicyFocus) },
    restorePolicyFocus () {
      if (!this.pendingDeleteFocus || this.disposed || this.disabled || this.policyTarget || this.deleteFocusScope !== this.ownerScope) return
      if (document.activeElement !== document.body) { this.pendingDeleteFocus = false; return }
      const trigger = this.deleteTrigger?.isConnected && !this.deleteTrigger.disabled ? this.deleteTrigger : this.$el?.closest('.volume-policy-tab')?.querySelector('.detail-tab-toolbar button:not([disabled])')
      if (trigger) { trigger.focus({ preventScroll: true }); this.pendingDeleteFocus = false }
    },
    intervalText (value) { return policyInterval(value, this.$t) },
    scheduleText (record) { return policySchedule(record, this.$t) },
    schedulingText (record) { return policyScheduling(record, this.$t) },
    scheduleTime (record) {
      if (!record.schedule) return '—'
      const values = String(record.schedule).split(':')
      if (Number(record.intervaltype) !== 0 && values.length < 2) return '—'
      return Number(record.intervaltype) === 0 ? record.schedule + this.$t('label.min.past.hour') : values[1].padStart(2, '0') + ':' + values[0].padStart(2, '0')
    },
    handleClickDelete (record) { this.openPolicy(record, 'delete') },
    getTimeZone (value) { return timeZoneName(value) }
  }
}
</script>
<style scoped>
.list-schedule { min-width: 0; }
.policy-row-actions { display: flex; align-items: center; gap: 6px; }
</style>
