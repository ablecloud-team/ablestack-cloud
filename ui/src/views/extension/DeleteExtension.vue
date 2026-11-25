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
  <div>
    <p class="extension-delete-name">{{ resource.name }}</p>
    <p>{{ $t('message.action.delete.extension') }}</p>
    <a-alert v-if="linked" type="warning" show-icon :message="$t('message.extension.delete.linked')" />
    <a-checkbox v-model:checked="cleanup" :disabled="loading || unverified" aria-describedby="extension-delete-cleanup-description">
      {{ $t('label.extension.delete.cleanup') }}
    </a-checkbox>
    <p id="extension-delete-cleanup-description">{{ $t('message.extension.delete.cleanup') }}</p>
    <a-alert v-if="error" type="error" show-icon :message="error">
      <template v-if="unverified" #description>
        <a-button size="small" :loading="loading" @click="recheck">{{ $t('label.refresh') }}</a-button>
      </template>
    </a-alert>
    <mold-dialog-actions :busy="loading">
      <a-button @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" danger :disabled="linked || unverified" :loading="loading" @click="remove">{{ $t('label.delete') }}</a-button>
    </mold-dialog-actions>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { extensionListQuery } from '@/utils/extension'

export default {
  name: 'DeleteExtension',
  components: { MoldDialogActions },
  props: {
    resource: { type: Object, required: true }
  },
  emits: ['close-action'],
  data () {
    return { cleanup: false, loading: false, error: '', unverified: false }
  },
  computed: {
    linked () { return !!this.resource.resources?.length }
  },
  methods: {
    async remove () {
      if (this.loading || this.linked || this.unverified) return
      const target = { id: this.resource.id, name: this.resource.name, cleanup: this.cleanup }
      this.loading = true
      this.error = ''
      try {
        const response = await postAPI('deleteExtension', { id: target.id, cleanup: target.cleanup }, { preserveOnFailure: true })
        if (response.deleteextensionresponse?.success !== true) throw new Error(this.$t('message.request.failed'))
        this.complete(target)
      } catch (error) {
        await this.reconcile(target, error)
      } finally {
        this.loading = false
      }
    },
    async recheck () {
      if (this.loading || !this.unverified) return
      this.loading = true
      try {
        await this.reconcile({ id: this.resource.id, name: this.resource.name, cleanup: this.cleanup })
      } finally {
        this.loading = false
      }
    },
    async reconcile (target, deletionError) {
      try {
        const data = await getAPI('listExtensions', { id: target.id, details: 'min' }, { preserveOnFailure: true })
        const response = data?.listextensionsresponse
        if (!response || response.errorcode) throw new Error(this.$t('message.request.failed'))
        this.unverified = false
        if ((response.extension || []).some(extension => extension.id === target.id)) {
          this.error = deletionError?.response?.data?.deleteextensionresponse?.errortext ||
            deletionError?.response?.data?.errorresponse?.errortext ||
            this.$t('message.extension.delete.retained')
        } else {
          this.complete(target, target.cleanup)
        }
      } catch (error) {
        this.unverified = true
        this.error = this.$t('message.extension.delete.unknown')
      }
    },
    complete (target, cleanupFailed = false) {
      const title = this.$t('label.delete.extension') + ' · ' + target.name
      if (cleanupFailed) {
        this.$notification.warning({
          message: title,
          description: this.$t('message.extension.delete.partial'),
          duration: 0
        })
      } else {
        this.$notification.success({
          message: title,
          description: this.$t('message.extension.delete.done')
        })
      }
      if (this.$route.params.id === target.id) {
        this.$emit('close-action')
        this.$router.replace({ path: '/extension', query: extensionListQuery(this.$route.query) })
      }
    }
  }
}
</script>

<style scoped>
.extension-delete-name { font-weight: 600; overflow-wrap: anywhere; }
#extension-delete-cleanup-description { margin-block: 8px 12px; }
</style>
