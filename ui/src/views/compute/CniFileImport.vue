// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div class="cni-file-import">
    <div class="detail-tab-toolbar">
      <a-button :disabled="disabled || pending" @click="choose('text')">{{ $t('label.cni.file.text') }}</a-button>
      <a-button :disabled="disabled || pending" @click="choose('base64')">{{ $t('label.cni.file.base64') }}</a-button>
      <slot />
      <span v-if="pending" role="status"><loading-outlined /> {{ $t('label.loading') }}</span>
    </div>
    <input ref="file" class="cni-file-input" type="file" accept="text/*,.yaml,.yml,.json,.sh,.txt,.b64,.base64" :aria-label="$t('label.cni.file.select')" @change="selected" />
    <a-alert v-if="error && !candidate" type="error" show-icon role="alert" :message="$t(error)" />
    <MoldDialog v-if="candidate" class="cni-file-preview" size="lg" :title="$t('label.cni.file.preview')" @cancel="cancel" @keydown.ctrl.enter.stop.prevent>
      <a-descriptions size="small" :column="1" class="mold-dialog-summary"><a-descriptions-item :label="$t('label.name')">{{ candidate.name }}</a-descriptions-item><a-descriptions-item :label="$t('label.cni.file.bytes')">{{ candidate.bytes }}</a-descriptions-item></a-descriptions>
      <a-alert v-if="error" type="error" show-icon role="alert" :message="$t(error)" />
      <UserDataContent :resource="{ id: 'cni-file-preview', name: candidate.name }" source-type="cni" tab="cni.content" :preview-encoded="candidate.encoded" />
      <template #footer><a-button @click="cancel">{{ $t('label.cancel') }}</a-button><a-button type="primary" :disabled="disabled" @click="apply">{{ $t('label.cni.file.apply') }}</a-button></template>
    </MoldDialog>
  </div>
</template>
<script>
import MoldDialog from '@/components/view/MoldDialog'
import UserDataContent from './UserDataContent'
import { canonicalCni } from '@/utils/cniRegistration'
import { readCniFile } from '@/utils/cniFileImport'
export default {
  components: { MoldDialog, UserDataContent },
  props: { disabled: Boolean, maxLength: { type: Number, required: true }, context: { type: String, required: true } },
  emits: ['reading', 'apply'],
  data () { return { pending: false, candidate: null, error: '', mode: 'text', generation: 0, disposed: false } },
  watch: { context () { this.cancel() } },
  beforeUnmount () { this.disposed = true; this.generation++; this.$emit('reading', false) },
  methods: {
    choose (mode) {
      if (this.disabled || this.pending) return
      this.mode = mode; this.$refs.file.value = ''; this.$refs.file.click()
    },
    cancel () { this.generation++; this.pending = false; this.candidate = null; this.error = ''; this.$emit('reading', false) },
    async selected (event) {
      const file = event.target.files?.[0]
      if (!file || this.disabled || this.pending) return
      const generation = ++this.generation; const context = this.context
      const current = () => !this.disposed && generation === this.generation && context === this.context
      this.pending = true; this.error = ''; this.$emit('reading', true)
      try {
        const draft = await readCniFile(file, this.mode, this.maxLength)
        if (current()) this.candidate = draft
      } catch (error) { if (current()) { this.candidate = null; this.error = error.message } } finally { if (current()) { this.pending = false; this.$emit('reading', false) } }
    },
    apply () {
      if (this.disabled || !this.candidate) return
      try { const encoded = canonicalCni(this.candidate.encoded, true, this.maxLength); this.$emit('apply', encoded); this.cancel() } catch (error) { this.error = error.message }
    }
  }
}
</script>
<style scoped>
.cni-file-import { min-width: 0; }
.cni-file-import > .detail-tab-toolbar { margin-bottom: 12px; }
.cni-file-import .cni-file-input { display: none; }
.cni-file-import > .ant-alert { margin-bottom: 12px; }
</style>

<style>
/* MoldDialog renders this class through a portal, outside the scoped root. */
.cni-file-preview .content-code { max-height: 45vh; }
</style>
