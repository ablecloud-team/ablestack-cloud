<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance with the License.
You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations. -->
<template>
  <section v-if="available && active" class="resource-tags" data-testid="resource-tags">
    <a-divider />
    <div class="resource-tags-heading">
      <strong>{{ $t('label.tags') }}</strong>
      <a-button size="small" :loading="loadingTags" :disabled="loadingTags" @click="refreshTags">
        <reload-outlined /> {{ $t('label.tags.refresh') }}
      </a-button>
    </div>
    <a-alert v-if="readError" type="error" show-icon class="resource-tags-notice" :message="$t('label.tags.read.failed')" :description="translateError(readError)" />
    <a-alert v-if="operationState.status !== 'idle'" :type="noticeType" show-icon class="resource-tags-notice">
      <template #message>{{ $t('message.tags.status.' + operationState.status) }}</template>
      <template v-if="operationState.status !== 'succeeded'" #description>
        <div v-if="operationState.params['tags[0].key']" class="resource-tag-value">{{ $t(operationState.command === 'deleteTags' ? 'label.delete' : 'label.add') }}: {{ operationState.params['tags[0].key'] }} = {{ operationState.params['tags[0].value'] }}</div>
        <div v-if="operationState.error">{{ translateError(operationState.error) }}</div>
        <div v-if="operationState.status === 'unknown'">{{ $t(operationState.jobId ? 'message.tags.unknown.job' : 'message.tags.unknown.submission') }}</div>
        <div v-if="operationState.jobId">{{ $t('label.job.id') }}: {{ operationState.jobId }}</div>
        <a-button v-if="operationState.status === 'unknown' && operationState.jobId" size="small" @click="resumeJob">{{ $t('label.tags.retry.status') }}</a-button>
        <a-button v-if="operationState.status === 'failed'" size="small" :disabled="!canChange" @click="retryMutation">{{ $t('label.tags.retry.operation') }}</a-button>
      </template>
    </a-alert>
    <a-spin :spinning="loadingTags">
      <div class="resource-tag-list">
        <a-tag v-for="tag in visibleTags" :key="JSON.stringify([tag.key, tag.value])" :closable="canChange && 'deleteTags' in $store.getters.apis" @close.prevent="deleteTag(tag)">
          {{ tag.key }} = {{ tag.value }}
        </a-tag>
        <span v-if="!visibleTags.length && !loadingTags && !readError" class="resource-tags-empty">{{ $t('message.tags.empty') }}</span>
      </div>
    </a-spin>
    <div v-if="inputVisible" class="resource-tag-editor" @keyup.enter="submitTag">
      <a-input ref="keyInput" v-model:value="inputKey" :aria-label="$t('label.key')" :placeholder="$t('label.key')" :maxlength="255" :disabled="!canChange" />
      <span aria-hidden="true">=</span>
      <a-input v-model:value="inputValue" :aria-label="$t('label.value')" :placeholder="$t('label.value')" :maxlength="255" :disabled="!canChange" />
      <div class="resource-tag-editor-actions">
        <a-button :aria-label="$t('label.ok')" :disabled="!canChange" @click="submitTag"><check-outlined /></a-button>
        <a-button :aria-label="$t('label.cancel')" :disabled="blocked" @click="inputVisible = false"><close-outlined /></a-button>
      </div>
    </div>
    <a-button v-else-if="canEdit && 'createTags' in $store.getters.apis" type="dashed" size="small" :disabled="!canChange" @click="showInput">
      <plus-outlined /> {{ $t('label.new.tag') }}
    </a-button>
    <div v-if="fieldError" role="alert" class="resource-tag-field-error">{{ $t(fieldError) }}</div>
  </section>
</template>

<script>
import { CheckOutlined, CloseOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { getAPI, postAPI } from '@/api'
import { getResourceTagOperation, readResourceTags, tagAccessError, tagApiError, tagContext, tagIdentity, tagOperationBlocked, tagTargetParams } from '@/utils/resourceTags'

export default {
  name: 'ResourceTags',
  components: { CheckOutlined, CloseOutlined, PlusOutlined, ReloadOutlined },
  props: {
    resource: { type: Object, required: true },
    resourceType: { type: String, required: true },
    canEdit: { type: Boolean, default: false }
  },
  data () {
    return {
      tags: [],
      loadingTags: false,
      readError: '',
      fieldError: '',
      boundScope: '',
      readVersion: 0,
      inputVisible: false,
      inputKey: '',
      inputValue: '',
      active: true,
      disposed: false,
      operation: null,
      unsubscribe: null,
      releaseBinding: null,
      operationState: { status: 'idle', params: {}, jobId: '', error: '', sequence: 0 }
    }
  },
  computed: {
    identity () { return tagIdentity(this.$store) },
    context () { return tagContext(this.$store, this.resource, this.resourceType) },
    viewScope () {
      return JSON.stringify([this.context, this.$route.fullPath, this.$route.meta.resourceType,
        ...['listTags', 'createTags', 'deleteTags'].map(api => api in this.$store.getters.apis), this.canEdit])
    },
    bindingKey () { return JSON.stringify([this.viewScope, this.available]) },
    available () {
      if (!this.resource?.id || this.resourceType !== this.$route.meta.resourceType || !('listTags' in this.$store.getters.apis)) return false
      const routeId = this.$route.params?.id
      if (/^[0-9a-f-]{36}$/i.test(routeId || '') && routeId !== this.resource.id) return false
      const selected = this.$store.getters.project?.id
      const project = ['0', '-1'].includes(String(selected)) ? '' : selected
      return !project || this.$store.getters.allProjects || !['Snapshot', 'UserVm', 'Volume', 'VMSnapshot', 'Backup'].includes(this.resourceType) || this.resource.projectid === project
    },
    visibleTags () { return this.boundScope === this.viewScope && this.available && this.active ? this.tags : [] },
    blocked () { return tagOperationBlocked(this.operationState) },
    canChange () { return this.available && this.active && this.canEdit && this.boundScope === this.viewScope && !this.blocked && !this.loadingTags && !this.readError },
    noticeType () { return this.operationState.status === 'failed' ? 'error' : this.operationState.status === 'unknown' ? 'warning' : this.operationState.status === 'succeeded' ? 'success' : 'info' }
  },
  watch: {
    bindingKey: { immediate: true, flush: 'sync', handler () { this.bindTags() } },
    resource () { if (this.boundScope === this.viewScope && !this.blocked && !this.loadingTags) this.refreshTags() }
  },
  activated () { this.active = true; if (this.boundScope !== this.viewScope) this.bindTags() },
  deactivated () { this.active = false; this.stopBinding(); this.tags = [] },
  beforeUnmount () { this.disposed = true; this.stopBinding() },
  methods: {
    translateError (error) { return error?.startsWith('message.') ? this.$t(error) : error },
    stopBinding () {
      this.readVersion++
      if (this.unsubscribe) this.unsubscribe()
      this.unsubscribe = null
      if (this.releaseBinding) this.releaseBinding()
      this.releaseBinding = null
      this.operation = null
      this.boundScope = ''
    },
    bindTags () {
      this.stopBinding()
      this.tags = []; this.loadingTags = false; this.readError = ''; this.fieldError = ''
      this.inputVisible = false; this.inputKey = ''; this.inputValue = ''
      this.operationState = { status: 'idle', params: {}, jobId: '', error: '', sequence: 0 }
      if (!this.available || !this.active || this.disposed) return
      const scope = this.viewScope
      this.boundScope = scope
      let storage
      try { storage = window.sessionStorage } catch (_) { storage = { getItem: () => null, setItem: () => { throw new Error('storage') } } }
      const store = this.$store
      const operation = getResourceTagOperation({ identity: this.identity, context: this.context, getIdentity: () => tagIdentity(store), get: getAPI, post: postAPI, storage })
      this.operation = operation
      this.releaseBinding = operation.bind(() => this.current(scope))
      this.operationState = operation.snapshot()
      this.unsubscribe = operation.subscribe(state => {
        if (!this.current(scope)) return
        const before = this.operationState
        this.operationState = state
        if (state.status === 'succeeded' && (before.status !== 'succeeded' || before.sequence !== state.sequence)) {
          this.refreshTags().then(ok => {
            if (ok && this.current(scope) && this.operationState.sequence === state.sequence) {
              this.inputVisible = false; this.inputKey = ''; this.inputValue = ''
            }
          })
        }
      })
      this.refreshTags()
    },
    current (scope) { return !this.disposed && this.active && this.available && scope === this.viewScope && this.boundScope === scope },
    async refreshTags () {
      const scope = this.viewScope; const version = ++this.readVersion
      if (!this.current(scope)) return false
      const current = () => this.current(scope) && this.readVersion === version
      this.loadingTags = true
      try {
        const rows = await readResourceTags(getAPI, { ...this.resource }, this.resourceType, current)
        if (!current()) return false
        this.tags = rows; this.readError = ''
        return true
      } catch (error) {
        if (current()) {
          if (tagAccessError(error)) this.tags = []
          this.readError = tagApiError(error) || (error.message?.startsWith('message.') ? error.message : 'message.tags.read.failed')
        }
        return false
      } finally { if (current()) this.loadingTags = false }
    },
    showInput () {
      if (!this.canChange) return
      this.inputVisible = true
      this.$nextTick(() => this.$refs.keyInput?.focus())
    },
    submitTag () {
      if (!('createTags' in this.$store.getters.apis) || !this.canChange) return
      if (!this.inputKey.trim() || !this.inputValue) { this.fieldError = 'message.tags.fields.required'; return }
      this.fieldError = ''
      return this.mutate('createTags', { key: this.inputKey, value: this.inputValue })
    },
    deleteTag (tag) {
      if (!('deleteTags' in this.$store.getters.apis) || !this.canChange) return
      return this.mutate('deleteTags', tag)
    },
    mutate (command, tag, explicitRetry = false) {
      if (!this.canChange || !(command in this.$store.getters.apis)) return
      const previous = this.operationState
      if (!explicitRetry && previous.status === 'failed' && previous.command === command && previous.params['tags[0].key'] === tag.key && previous.params['tags[0].value'] === tag.value) return
      const operation = this.operation; const scope = this.viewScope
      const params = { ...tagTargetParams(this.resource, this.resourceType), resourceids: this.resource.id, 'tags[0].key': tag.key, 'tags[0].value': tag.value }
      return operation.run(command, params, async () => {
        if (!await this.refreshTags() || !this.current(scope) || !this.canEdit || !(command in this.$store.getters.apis)) throw new Error('message.tags.prepare.failed')
      })
    },
    retryMutation () {
      if (this.operationState.status !== 'failed') return
      const state = this.operationState
      return this.mutate(state.command, { key: state.params['tags[0].key'], value: state.params['tags[0].value'] }, true)
    },
    resumeJob () { return this.operation?.resume() }
  }
}
</script>

<style scoped lang="less">
.resource-tags-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.resource-tags-notice { margin-bottom: 8px; word-break: break-word; }
.resource-tag-list { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.resource-tag-list :deep(.ant-tag) { margin: 0; max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
.resource-tag-list :deep(.ant-tag-close-icon) { color: inherit; }
.resource-tags-empty { opacity: 0.7; }
.resource-tag-value { overflow-wrap: anywhere; }
.resource-tag-editor { display: grid; grid-template-columns: minmax(0, 1fr); gap: 6px; }
.resource-tag-editor > span { display: none; }
.resource-tag-editor-actions { display: flex; justify-content: flex-end; gap: 6px; }
.resource-tag-field-error { color: var(--ant-error-color, #ff4d4f); margin-top: 6px; }
</style>
