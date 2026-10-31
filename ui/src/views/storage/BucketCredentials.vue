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
  <div class="form-layout bucket-credential-form">
    <a-alert type="info" show-icon :message="$t('label.bucket.credentials.manage')" :description="$t('message.bucket.credentials.scope')" />
    <a-alert v-if="errorText" type="error" show-icon :message="errorText" />
    <a-spin :spinning="loading">
      <template v-if="metadata">
        <div class="credential-state">
          <Status :text="metadata.recoveryrequired ? 'Error' : ['Prepared', 'Retiring'].includes(metadata.rotationstate) ? 'Pending' : 'Running'" :display-label="$t('state.bucket.credentials.' + (metadata.recoveryrequired ? 'recovery' : (metadata.rotationstate || 'ready')).toLowerCase())" display-text />
          <span>{{ $t('label.bucket.credentials.revision') }}: {{ metadata.credentialrevision }}</span>
        </div>
        <a-form layout="vertical">
          <a-form-item :label="$t('label.access.key')">
            <a-input-password :value="keys ? keys.accesskey : metadata.accesskeyhint" readonly autocomplete="off" :visibility-toggle="!!keys" />
          </a-form-item>
          <a-form-item :label="$t('label.secret.key')">
            <a-input-password :value="keys ? keys.usersecretkey : '********'" readonly autocomplete="off" :visibility-toggle="!!keys" />
          </a-form-item>
          <a-space wrap>
            <a-button :disabled="loading || !metadata" @click="keys ? hideKeys() : load(true)">{{ $t(keys ? 'label.bucket.credentials.hide' : 'label.bucket.credentials.reveal') }}</a-button>
            <a-button :disabled="loading" @click="load(false)">{{ $t('label.refresh') }}</a-button>
          </a-space>
          <h4>{{ $t('label.bucket.credentials.affected') }}</h4>
          <ul class="credential-targets"><li v-for="bucket in metadata.affectedbuckets" :key="bucket.id">{{ bucket.name }} <small>{{ bucket.id }}</small></li></ul>
          <template v-if="resource.provider === 'Glue' && canRotate">
            <template v-if="metadata.rotationstate && metadata.rotationstate !== 'Completed'">
              <div>{{ $t('label.bucket.credentials.operation') }}: {{ metadata.rotationid }}</div>
              <div v-if="metadata.retireafter">{{ $t('label.bucket.credentials.retire.after') }}: {{ $toLocaleDate(metadata.retireafter) }}</div>
              <a-alert v-if="metadata.recoveryrequired" type="warning" show-icon :message="$t('message.bucket.credentials.recovery')" />
              <a-space wrap class="credential-recovery">
                <a-button :disabled="loading" @click="rotate('retry')">{{ $t('label.bucket.credentials.retry') }}</a-button>
                <a-button :disabled="loading || !canRetire" @click="rotate('retire')">{{ $t('label.bucket.credentials.retire') }}</a-button>
              </a-space>
            </template>
            <template v-else>
              <a-form-item :label="$t('label.bucket.credentials.grace')">
                <a-input-number v-model:value="grace" :min="5" :max="86400" :precision="0" style="width:100%" />
              </a-form-item>
              <a-checkbox v-model:checked="acknowledged">{{ $t('message.bucket.credentials.acknowledge') }}</a-checkbox>
              <a-button type="primary" :disabled="loading || !acknowledged || !validGrace" @click="rotate('rotate')">{{ $t('label.bucket.credentials.rotate') }}</a-button>
            </template>
          </template>
        </a-form>
      </template>
    </a-spin>
    <div class="action-button"><a-button @click="close">{{ $t('label.close') }}</a-button></div>
  </div>
</template>
<script>
import { postAPI } from '@/api'
import Status from '@/components/widgets/Status'
export default {
  name: 'BucketCredentials',
  components: { Status },
  props: { resource: { type: Object, required: true } },
  inject: { parentFetchData: { default: null } },
  data () { return { metadata: null, keys: null, loading: false, errorText: '', grace: 300, acknowledged: false, now: Date.now() } },
  computed: {
    canRotate () { return !!this.$store.getters.apis.rotateBucketCredentials },
    validGrace () { return Number.isInteger(this.grace) && this.grace >= 5 && this.grace <= 86400 },
    canRetire () { return ['Grace', 'Retiring'].includes(this.metadata?.rotationstate) && this.metadata?.retireafter && this.now >= new Date(this.metadata.retireafter).getTime() },
    contextKey () { return JSON.stringify([this.resource.id, this.$route.path, this.$store.getters.project?.id, this.$store.getters.userInfo?.id, this.$store.state.user?.token]) }
  },
  watch: {
    contextKey () { this.disposeSecrets(); this.metadata = null; this.$emit('close-action') },
    'resource.credentialrevision' () { ++this.generation; this.hideKeys(); this.loading = false; this.load(false) }
  },
  created () {
    this.generation = 0
    this.load(false)
    this.clock = setInterval(() => { this.now = Date.now() }, 1000)
    this.poll = setInterval(() => {
      if (!this.loading && this.metadata?.rotationstate && this.metadata.rotationstate !== 'Completed') this.load(false)
    }, 10000)
  },
  beforeUnmount () { this.disposeSecrets() },
  methods: {
    hideKeys () { this.keys = null; clearTimeout(this.expiry) },
    disposeSecrets () { ++this.generation; this.hideKeys(); clearInterval(this.clock); clearInterval(this.poll) },
    close () { this.disposeSecrets(); this.parentFetchData && this.parentFetchData(); this.$emit('close-action') },
    async load (reveal = false) {
      if (this.loading) return
      const generation = ++this.generation
      const context = this.contextKey
      this.hideKeys()
      this.loading = true
      try {
        const json = await postAPI('getBucketCredentials', { id: this.resource.id, reveal }, { preserveOnFailure: true })
        if (generation !== this.generation || context !== this.contextKey) return
        const { accesskey, usersecretkey, ...metadata } = json.getbucketcredentialsresponse.bucketcredential || json.getbucketcredentialsresponse
        this.metadata = metadata
        if (reveal) {
          this.keys = { accesskey, usersecretkey }
          this.expiry = setTimeout(() => this.hideKeys(), 30000)
        }
      } catch (_) {
        if (generation === this.generation) this.errorText = this.$t('message.bucket.credentials.fetch.failed')
      } finally { if (generation === this.generation) this.loading = false }
    },
    async rotate (action) {
      if (this.loading || !this.metadata || (action === 'rotate' && (!this.acknowledged || !this.validGrace))) return
      this.hideKeys()
      const generation = ++this.generation
      const context = this.contextKey
      this.loading = true
      this.errorText = ''
      try {
        await postAPI('rotateBucketCredentials', {
          id: this.resource.id,
          action,
          expectedrevision: this.metadata.credentialrevision,
          rotationid: action === 'rotate' ? undefined : this.metadata.rotationid,
          graceperiod: this.grace
        }, { preserveOnFailure: true })
        if (generation !== this.generation || context !== this.contextKey) return
        this.acknowledged = false
        this.parentFetchData && this.parentFetchData()
      } catch (_) {
        if (generation === this.generation) this.errorText = this.$t('message.bucket.credentials.recovery')
      } finally {
        if (generation === this.generation) { this.loading = false; await this.load(false) }
      }
    }
  }
}
</script>
<style scoped lang="less">
.bucket-credential-form {
  min-width: 0;
  overflow-wrap: anywhere;
  .ant-alert, .credential-state, .credential-recovery { margin-bottom: 16px; }
  .credential-state { display: flex; flex-wrap: wrap; gap: 12px; }
  .credential-targets { padding-left: 20px; small { display: block; color: var(--ui-text-secondary); } }
  .ant-checkbox-wrapper { margin: 8px 0 16px; }
  :deep(.ant-input), :deep(.ant-input-affix-wrapper), :deep(.ant-input-number), :deep(.ant-input-number-input) {
    color: var(--ui-text-primary) !important;
    background: var(--ui-bg-input) !important;
  }
}
</style>
