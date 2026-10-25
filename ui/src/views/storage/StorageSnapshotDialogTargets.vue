<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <section class="snapshot-dialog-targets" data-testid="snapshot-dialog-targets">
    <p>{{ $t('message.snapshot.dialog.target.count', [rows.length]) }}</p>
    <a-descriptions v-if="rows.length === 1" :column="1" bordered size="small">
      <a-descriptions-item v-for="column in columns" :key="column.key" :label="column.title">{{ rows[0][column.key] }}</a-descriptions-item>
    </a-descriptions>
    <a-table v-else size="small" row-key="key" :columns="columns" :data-source="rows" :pagination="false" :scroll="{ x: 1500, y: 300 }" />
  </section>
</template>
<script>
import { snapshotListSize, snapshotListInterval } from '@/utils/storageSnapshotPresentation'
export default {
  name: 'StorageSnapshotDialogTargets',
  props: { targets: { type: Array, required: true }, contexts: { type: Array, default: () => [] } },
  computed: {
    columns () { return ['name', 'volume', 'virtualmachine', 'account', 'state', 'virtualsize', 'intervaltype', 'created', 'scope'].map(key => ({ key, dataIndex: key, title: this.$t(key === 'scope' ? 'label.snapshot.dialog.scope' : 'label.' + key), width: { name: 220, volume: 200, virtualmachine: 200, account: 180, state: 100, virtualsize: 120, intervaltype: 100, created: 170, scope: 210 }[key] })) },
    rows () {
      return this.targets.map(target => {
        const context = this.contexts.find(row => row.target.id === target.id && row.target.zoneId === target.zoneId)
        const record = context?.record || target.record
        const copies = (context?.copies || []).filter(copy => target.zoneId === 'all' || copy.zoneid === target.zoneId)
        const zones = [...new Set(copies.map(copy => copy.zonename || copy.zoneid).filter(Boolean))]
        const vmName = context?.source?.virtualmachinename || record.virtualmachinename
        const vmState = context?.source?.virtualmachinestate || record.virtualmachinestate
        const virtualmachine = vmName ? [vmName, vmState ? this.$t('state.' + vmState.toLowerCase()) : ''].filter(Boolean).join(' · ') : this.$t(context?.source?.sourceavailable === true ? 'label.none' : 'label.snapshot.list.unknown')
        return { key: target.id + ':' + target.zoneId, name: record.name || target.id, volume: context?.source.volumename || record.volumename || record.volumeid || '—', virtualmachine, account: [record.account, record.project || record.domain].filter(Boolean).join(' · ') || '—', state: record.state ? this.$t('state.' + record.state.toLowerCase()) : '—', virtualsize: snapshotListSize(record.virtualsize) ?? this.$t('label.snapshot.list.unknown'), intervaltype: this.$t(snapshotListInterval(record.intervaltype)), created: record.created ? this.$toLocaleDate(record.created) : '—', scope: target.zoneId === 'all' ? this.$t('message.snapshot.action.all.zones') : zones.join(', ') || target.record.zonename || target.zoneId || this.$t('label.snapshot.list.unknown') }
      })
    }
  }
}
</script>
<style scoped>
.snapshot-dialog-targets { min-width: 0; }
.snapshot-dialog-targets > p { margin: 0 0 12px; }
.snapshot-dialog-targets :deep(.ant-descriptions-item-content), .snapshot-dialog-targets :deep(td) { overflow-wrap: anywhere; }
</style>
