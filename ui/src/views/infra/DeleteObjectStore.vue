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
  <div class="object-store-delete">
    <a-alert show-icon :type="ready ? 'warning' : 'info'" :message="$t('message.objectstore.delete.empty.only')" />
    <p>{{ resource.name }}</p>
    <a-alert v-if="error" type="error" show-icon :message="$t('message.objectstore.delete.failed')" />
    <ObjectStoreBuckets :detail-viewport="false" :resource="resource" :disabled="busy" @state="state = $event" />
    <a-alert v-if="state.known && state.total > 0" show-icon type="warning" :message="$t('message.objectstore.delete.has.buckets')" />
    <MoldDialogActions :busy="busy">
      <a-button :disabled="busy" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button v-if="state.known && state.total > 0" :disabled="busy" @click="showBuckets">{{ $t('label.objectstore.buckets.view') }}</a-button>
      <a-button type="primary" danger :loading="busy" :disabled="!ready" @click="remove">{{ $t('label.delete') }}</a-button>
    </MoldDialogActions>
  </div>
</template>
<script>
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { postAPI } from '@/api'
import ObjectStoreBuckets from '@/views/infra/ObjectStoreBuckets'
export default {
  components: { ObjectStoreBuckets, MoldDialogActions },
  props: { resource: { type: Object, required: true } },
  emits: ['close-action', 'refresh-data'],
  data () { return { state: {}, busy: false, error: false, disposed: false } },
  computed: { context () { return JSON.stringify([this.resource.id, this.$store.getters.userInfo?.id, this.$store.getters.userInfo?.roleid, this.$store.getters.project?.id, this.$store.state?.user?.token]) }, ready () { return !this.busy && this.state.id === this.resource.id && this.state.known && this.state.total === 0 && this.$store.getters.userInfo?.roletype === 'Admin' && !!this.$store.getters.apis?.deleteObjectStoragePool } },
  watch: { context () { this.state = {}; this.busy = false; this.error = false } },
  beforeUnmount () { this.disposed = true },
  methods: {
    showBuckets () { this.$emit('close-action'); this.$router.push({ path: '/objectstore/' + this.resource.id, query: { tab: 'buckets' } }) },
    async remove () {
      if (!this.ready) return
      const context = this.context; const id = this.resource.id; this.busy = true; this.error = false
      try {
        const response = await postAPI('deleteObjectStoragePool', { id, ignoreproject: true })
        if (!response.deleteobjectstoragepoolresponse?.success) throw new Error('Deletion not confirmed')
        if (this.disposed || context !== this.context) return
        this.$emit('refresh-data'); this.$emit('close-action'); this.$router.push('/objectstore')
      } catch (error) {
        if (this.disposed || context !== this.context) return
        this.error = true; this.state = {}; this.$emit('refresh-data')
      } finally { if (!this.disposed && context === this.context) this.busy = false }
    }
  }
}
</script>
<style scoped>
.object-store-delete { min-width: 0; color: var(--ui-text-primary); }
.object-store-delete > .ant-alert { margin-bottom: 16px; }
.object-store-delete-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; margin-top: 16px; }
.object-store-delete-actions .ant-btn { height: 32px; font-size: 14px; border-radius: 4px; }
</style>
