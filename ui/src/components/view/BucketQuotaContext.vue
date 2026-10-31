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
  <a-alert class="bucket-quota-context" type="info" show-icon>
    <template #message>{{ $t('message.bucket.quota.validation') }}</template>
    <template #description>
      <div>{{ $t('message.bucket.quota.live.check') }}</div>
      <div v-if="snapshot">
        {{ $t('label.bucket.quota.available') }}: {{ available }} GiB ·
        {{ $t('label.bucket.quota.reserved') }}: {{ reserved }} GiB
      </div>
      <div v-if="checked">{{ $t('label.bucket.quota.checked') }}: {{ $toLocaleDate(checked) }}</div>
      <div v-if="failed">{{ $t('message.bucket.quota.capacity.unknown') }}</div>
    </template>
  </a-alert>
</template>
<script>
import { getAPI } from '@/api'
export default {
  name: 'BucketQuotaContext',
  props: { storeId: { type: String, default: '' } },
  data: () => ({ snapshot: null, checked: '', failed: false, generation: 0 }),
  computed: {
    reserved () { return this.snapshot ? Number(this.snapshot.storagereserved || 0) / 1073741824 : 0 },
    available () {
      if (!this.snapshot?.storagetotal) return this.$t('label.bucket.quota.untracked')
      return Math.max(0, (Number(this.snapshot.storagetotal) - Number(this.snapshot.storageallocated || 0) - Number(this.snapshot.storagereserved || 0)) / 1073741824)
    }
  },
  watch: { storeId: { immediate: true, handler: 'fetchCapacity' } },
  beforeUnmount () { this.generation++ },
  methods: {
    async fetchCapacity () {
      const generation = ++this.generation
      this.snapshot = null; this.failed = false; this.checked = ''
      if (!this.storeId) return
      try {
        const result = await getAPI('listObjectStoragePools', { id: this.storeId })
        if (generation !== this.generation) return
        this.snapshot = (result.listobjectstoragepoolsresponse.objectstore || []).find(store => store.id === this.storeId) || null
        this.failed = !this.snapshot; this.checked = this.snapshot ? new Date().toISOString() : ''
      } catch (_) { if (generation === this.generation) this.failed = true }
    }
  }
}
</script>
<style scoped>
.bucket-quota-context { margin-bottom: 16px; overflow-wrap: anywhere; }
</style>
