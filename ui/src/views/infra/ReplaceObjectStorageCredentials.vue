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
  <div class="form-layout bucket-credential-form" v-ctrl-enter="submit">
    <a-alert type="info" show-icon :message="$t('label.objectstore.credentials.replace')" :description="$t('message.objectstore.credentials.replace')" />
    <a-alert v-if="errorText" type="error" show-icon :message="errorText" />
    <a-form layout="vertical" @finish="submit">
      <a-form-item :label="$t('label.access.key')" required>
        <a-input-password v-model:value="access" autocomplete="off" :disabled="loading" />
      </a-form-item>
      <a-form-item :label="$t('label.secret.key')" required>
        <a-input-password v-model:value="secret" autocomplete="off" :disabled="loading" />
      </a-form-item>
      <a-checkbox v-model:checked="acknowledged" :disabled="loading">{{ $t('message.objectstore.credentials.acknowledge') }}</a-checkbox>
      <div class="action-button">
        <a-button :disabled="loading" @click="close">{{ $t('label.cancel') }}</a-button>
        <a-button type="primary" :loading="loading" :disabled="!valid || !acknowledged" @click="submit">{{ $t('label.objectstore.credentials.replace') }}</a-button>
      </div>
    </a-form>
  </div>
</template>
<script>
import { postAPI } from '@/api'
export default {
  name: 'ReplaceObjectStorageCredentials',
  props: { resource: { type: Object, required: true } },
  inject: { parentFetchData: { default: null } },
  data () { return { access: '', secret: '', acknowledged: false, loading: false, errorText: '' } },
  computed: {
    valid () { return !!this.access.trim() && !!this.secret.trim() },
    contextKey () { return JSON.stringify([this.resource.id, this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state.user?.token]) }
  },
  watch: { contextKey () { this.wipe(); this.$emit('close-action') } },
  beforeUnmount () { this.wipe() },
  methods: {
    wipe () { this.access = ''; this.secret = ''; this.acknowledged = false },
    close () { this.wipe(); this.$emit('close-action') },
    async submit () {
      if (this.loading || !this.valid || !this.acknowledged) return
      const context = this.contextKey
      const access = this.access
      const secret = this.secret
      this.loading = true
      this.errorText = ''
      try {
        await postAPI('updateObjectStoragePool', {
          id: this.resource.id,
          'details[0].key': 'accesskey',
          'details[0].value': access,
          'details[1].key': 'secretkey',
          'details[1].value': secret
        }, { preserveOnFailure: true })
        if (context !== this.contextKey) return
        this.parentFetchData && this.parentFetchData()
        this.close()
      } catch (_) {
        if (context === this.contextKey) this.errorText = this.$t('message.objectstore.credentials.failed')
      } finally { this.loading = false; this.wipe() }
    }
  }
}
</script>
<style scoped lang="less">
.bucket-credential-form {
  min-width: 0;
  overflow-wrap: anywhere;
  .ant-alert { margin-bottom: 16px; }
  :deep(.ant-input), :deep(.ant-input-affix-wrapper) {
    color: var(--ui-text-primary) !important;
    background: var(--ui-bg-input) !important;
  }
}
</style>
