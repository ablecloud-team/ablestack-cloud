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
        <template v-if="column.key === 'intervaltype'">{{ intervalText(record.intervaltype) }}</template>
        <template v-if="column.key === 'time'">
          <div>{{ scheduleTime(record) }}</div>
          <div v-if="Number(record.intervaltype) === 2">{{ $t('label.every') }} {{ $t('label.' + listDayOfWeek[Number(record.schedule.split(':')[2]) - 1]) }}</div>
          <div v-else-if="Number(record.intervaltype) === 3">{{ $t('label.snapshot.policy.month.day', { day: record.schedule.split(':')[2] }) }}</div>
        </template>
        <template v-if="column.key === 'timezone'">{{ getTimeZone(record.timezone) }}</template>
        <template v-if="column.key === 'tags'"><a-tag v-for="(tag, index) in record.tags" :key="index">{{ tag.key + '=' + tag.value }}</a-tag></template>
        <template v-if="column.key === 'zones'">
          <div v-for="zone in record.zone" :key="zone.id"><router-link v-if="'listZones' in $store.getters.apis" :to="{ path: '/zone/' + zone.id }">{{ zone.name }}</router-link><span v-else>{{ zone.name }}</span></div>
        </template>
        <template v-if="column.key === 'storage'">
          <div v-for="pool in record.storage" :key="pool.id"><router-link v-if="'listStoragePools' in $store.getters.apis" :to="{ path: '/storagepool/' + pool.id }">{{ pool.name }}</router-link><span v-else>{{ pool.name }}</span></div>
        </template>
        <template v-if="column.key === 'actions'">
          <tooltip-button
            :tooltip="$t('label.delete')"
            type="primary"
            :danger="true"
            icon="close-outlined"
            size="small"
            :loading="actionLoading"
            :disabled="disabled || actionLoading"
            @onClick="handleClickDelete(record)" />
        </template>
      </template>
    </a-table>
  </div>
</template>
<script>
import { postAPI } from '@/api'
import TooltipButton from '@/components/widgets/TooltipButton'
import { timeZoneName } from '@/utils/timezone'

export default {
  name: 'ScheduledSnapshots',
  components: { TooltipButton },
  props: { loading: Boolean, disabled: Boolean, dataSource: { type: Array, required: true }, resource: { type: Object, required: true } },
  emits: ['refresh'],
  data () { return { actionLoading: false, deleteVersion: 0, disposed: false, listDayOfWeek: ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'] } },
  computed: {
    canDelete () { return 'deleteSnapshotPolicies' in this.$store.getters.apis },
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
        ...(this.canDelete ? [{ key: 'actions', title: this.$t('label.actions'), width: 64 }] : [])
      ]
    }
  },
  watch: { ownerScope () { this.deleteVersion++; this.actionLoading = false } },
  beforeUnmount () { this.disposed = true; this.deleteVersion++ },
  methods: {
    intervalText (value) { const name = ['hourly', 'daily', 'weekly', 'monthly'][Number(value)]; return name ? this.$t('label.' + name) : '—' },
    scheduleTime (record) {
      if (!record.schedule) return '—'
      const values = String(record.schedule).split(':')
      if (Number(record.intervaltype) !== 0 && values.length < 2) return '—'
      return Number(record.intervaltype) === 0 ? record.schedule + this.$t('label.min.past.hour') : values[1].padStart(2, '0') + ':' + values[0].padStart(2, '0')
    },
    async handleClickDelete (record) {
      if (this.disposed || this.disabled || this.actionLoading || !this.canDelete || !this.dataSource.some(row => row.id === record.id && (!row.volumeid || row.volumeid === this.resource.id))) return
      const version = ++this.deleteVersion; const scope = this.ownerScope
      const current = () => !this.disposed && version === this.deleteVersion && scope === this.ownerScope
      this.actionLoading = true
      try {
        const result = await postAPI('deleteSnapshotPolicies', { id: record.id }, { preserveOnFailure: true })
        if (current() && result.deletesnapshotpoliciesresponse.success) {
          this.$notification.success({ message: this.$t('label.delete.snapshot.policy'), description: this.$t('message.success.delete.snapshot.policy') })
          this.$emit('refresh')
        }
      } catch (error) { if (current()) this.$notifyError(error) } finally { if (current()) this.actionLoading = false }
    },
    getTimeZone (value) { return timeZoneName(value) }
  }
}
</script>
<style scoped>
.list-schedule { min-width: 0; }
</style>
