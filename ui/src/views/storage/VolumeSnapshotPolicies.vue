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
  <section class="volume-policy-tab">
    <div class="detail-tab-toolbar">
      <a-button :loading="pending" :disabled="!canRead || parentPending" @click="fetchData"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="$t(loaded ? 'message.list.refresh.stale' : 'error.fetching.data')">
      <template #description><a-button :loading="pending" @click="fetchData">{{ $t('label.retry') }}</a-button></template>
    </a-alert>
    <ScheduledSnapshots :resource="resource" :dataSource="rows" :active="active" :loading="pending && !loaded" :disabled="pending || parentPending || !!error || !active" @refresh="fetchData" />
  </section>
</template>
<script>
import { getAPI } from '@/utils/storageApi'
import { readAllocationList } from '@/utils/volumeAllocation'
import { listRefreshMixin } from '@/utils/listRefreshMixin'
import ScheduledSnapshots from '@/views/storage/ScheduledSnapshots'

export default {
  name: 'VolumeSnapshotPolicies',
  components: { ScheduledSnapshots },
  mixins: [listRefreshMixin(['fetchData'], { active: vm => vm.active })],
  props: { resource: { type: Object, required: true }, loading: Boolean, tab: { type: String, default: '' } },
  inject: { resourceReadPending: { default: () => () => false } },
  data () { return { rows: [], error: false, pending: false, loaded: false, version: 0, disposed: false } },
  computed: {
    active () { return this.tab === 'recurringsnapshots' },
    parentPending () { return this.loading || this.resourceReadPending() },
    canRead () { return 'listSnapshotPolicies' in this.$store.getters.apis },
    ownerScope () { return JSON.stringify([this.$route.path, this.resource.id, this.$store.getters.userInfo?.id, this.$store.state?.user?.token, this.$store.getters.project?.id, this.canRead]) }
  },
  watch: {
    resource () { this.fetchData() },
    ownerScope () { this.reset(); this.fetchData() },
    active (value) { this.version++; this.pending = false; if (value) this.fetchData() },
    parentPending (value) { if (!value) this.fetchData() }
  },
  created () { this.fetchData() },
  beforeUnmount () { this.disposed = true; this.version++ },
  methods: {
    reset () { this.version++; this.rows = []; this.error = false; this.pending = false; this.loaded = false },
    async fetchData () {
      if (this.disposed || !this.active || this.loading || this.parentPending || this.pending) return
      if (!this.canRead || !this.resource.id) { this.reset(); return }
      const version = ++this.version; const scope = this.ownerScope; const id = this.resource.id
      const current = () => !this.disposed && this.active && version === this.version && scope === this.ownerScope
      this.pending = true
      try {
        const rows = await readAllocationList(getAPI, 'listSnapshotPolicies', 'snapshotpolicy', { volumeid: id, listall: true }, current)
        if (!current()) return
        this.rows = rows || []; this.error = false; this.loaded = true
      } catch (error) {
        if (!current()) return
        this.error = true
        const code = Number(error?.response?.data?.errorresponse?.errorcode || error?.response?.status)
        if ([401, 403, 431, 432, 531].includes(code)) { this.rows = []; this.loaded = false }
      } finally { if (current()) this.pending = false }
    }
  }
}
</script>
<style scoped>
.volume-policy-tab { width: 100%; min-width: 0; }
.volume-policy-tab > .ant-alert { margin-bottom: 16px; }
</style>
