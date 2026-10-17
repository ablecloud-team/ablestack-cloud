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
  <div class="storage-nfs-probe-observation">
    <div v-for="row in rows" :key="row.endpoint" class="storage-nfs-probe-observation__endpoint">
      <a-tag :color="color(row.state)" role="status">{{ $t('label.storage.service.nfs.probe.' + row.state) }}</a-tag>
      <div v-if="row.endpoint !== '—'" class="storage-field-hint">{{ row.endpoint }}</div>
      <div v-if="row.reason" class="storage-field-hint">{{ $t('message.storage.service.nfs.probe.' + row.reason) }}</div>
      <div v-if="row.reason" class="storage-field-hint">{{ $t('message.storage.service.nfs.probe.external.separate') }}</div>
      <div v-if="row.clients.length">{{ $t('label.storage.service.nfs.applied.clients') }}: <span class="storage-nfs-probe-observation__clients">{{ row.clients.join(', ') }}</span></div>
    </div>
  </div>
</template>
<script>
import { storageNfsProbeRows } from '@/utils/storageNfsProbe'

export default {
  name: 'StorageNfsProbeStatus',
  props: {
    health: { type: Object, default: null },
    exportUuid: { type: String, default: '' },
    stale: { type: Boolean, default: false },
    readFailed: { type: Boolean, default: false }
  },
  computed: {
    rows () { return storageNfsProbeRows(this.health, this.exportUuid, { stale: this.stale, readFailed: this.readFailed }) }
  },
  methods: {
    color (state) { return { VERIFIED: 'green', SKIPPED_ACL: 'blue', SKIPPED_DYNAMIC: 'blue', FAILED: 'red', UNKNOWN: 'default' }[state] }
  }
}
</script>
<style scoped>
.storage-nfs-probe-observation__endpoint + .storage-nfs-probe-observation__endpoint { margin-top: 8px; }
.storage-nfs-probe-observation__clients { overflow-wrap: anywhere; }
</style>
