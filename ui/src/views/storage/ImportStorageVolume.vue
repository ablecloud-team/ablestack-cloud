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
  <a-form layout="vertical" @submit.prevent="submit">
    <a-alert type="info" show-icon :message="$t('message.creation.source.import')" style="margin-bottom: 16px" />
    <a-form-item :label="$t('label.path')" required>
      <a-input v-model:value="path" :placeholder="$t('message.creation.source.import.path')" :disabled="loading" />
    </a-form-item>
    <a-form-item :label="$t('label.name')"><a-input v-model:value="name" :disabled="loading" /></a-form-item>
    <a-form-item :label="$t('label.diskoffering')">
      <infinite-scroll-select
v-model:value="offering"
api="listDiskOfferings"
:api-params="{ zoneid: resource.zoneid, listall: true, iscustomized: true }"
resource-type="diskoffering"
option-value-key="id"
option-label-key="displaytext"
default-icon="hdd-outlined"
allow-clear="true" />
    </a-form-item>
    <div class="action-button">
      <a-button :disabled="loading" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="loading" :disabled="!path.trim()" @click="submit">{{ $t('label.creation.source.import') }}</a-button>
    </div>
  </a-form>
</template>
<script>
import { postAPI } from '@/api'
import InfiniteScrollSelect from '@/components/widgets/InfiniteScrollSelect.vue'
export default {
  components: { InfiniteScrollSelect },
  props: { resource: { type: Object, required: true }, initialPath: { type: String, default: '' } },
  emits: ['close-action'],
  data () { return { path: this.initialPath, name: '', offering: undefined, loading: false } },
  methods: {
    async submit () {
      if (this.loading || !this.path.trim()) return
      this.loading = true
      try {
        const args = { storageid: this.resource.id, path: this.path.trim(), name: this.name.trim() || undefined, diskofferingid: this.offering || undefined }
        const result = (await postAPI('importVolume', args)).importvolumeresponse
        if (!result?.jobid) throw new Error(this.$t('message.request.failed'))
        this.$pollJob({
          jobId: result.jobid,
          title: this.$t('label.creation.source.import'),
          successMessage: this.$t('message.success.create.volume'),
          successMethod: job => {
            this.loading = false; this.$emit('close-action')
            const volume = job.jobresult?.volume
            if (volume?.id) this.$router.push('/volume/' + volume.id)
          },
          errorMessage: this.$t('message.create.volume.failed'),
          loadingMessage: this.$t('message.create.volume.processing'),
          errorMethod: () => { this.loading = false }
        })
      } catch (error) { this.loading = false; this.$notifyError(error) }
    }
  }
}
</script>
