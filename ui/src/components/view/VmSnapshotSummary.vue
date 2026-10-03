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
  <a-descriptions class="mold-dialog-section mold-dialog-summary" :column="1" bordered size="small">
    <a-descriptions-item :label="$t('label.virtualmachinename')">{{ snapshot.virtualmachinename || snapshot.virtualmachineinstancename || '—' }}</a-descriptions-item>
    <a-descriptions-item v-if="vmOnly" :label="$t('label.state')"><Status :text="snapshot.virtualmachinestate" display-text /></a-descriptions-item>
    <template v-else>
      <a-descriptions-item :label="$t('label.snapshot.name')">{{ snapshot.displayname || snapshot.name || '—' }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.created')">{{ $toLocaleDate(snapshot.created) }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.type')">{{ $t(snapshot.type === 'DiskAndMemory' ? 'label.vmsnapshot.disk.memory' : 'label.vmsnapshot.disk') }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.current')">{{ $t(snapshot.current ? 'label.vmsnapshot.current.yes' : 'label.vmsnapshot.current.no') }}</a-descriptions-item>
      <a-descriptions-item :label="$t('label.parentname')">{{ snapshot.parentName || '—' }}</a-descriptions-item>
    </template>
  </a-descriptions>
</template>
<script>
import Status from '@/components/widgets/Status'
export default {
  name: 'VmSnapshotSummary',
  components: { Status },
  props: { snapshot: { type: Object, required: true }, vmOnly: { type: Boolean, default: false } }
}
</script>
