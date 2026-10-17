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
  <a-button :disabled="disabled || pending || !available || (mode === 'extract' ? !encoded : !resource.id)" @click="open">{{ $t(mode === 'extract' ? 'label.cni.variables.extract' : 'label.cni.variables.preview') }}</a-button>
  <MoldDialog v-if="visible" class="cni-variable-dialog" :size="mode === 'extract' ? 'md' : 'lg'" :title="$t(mode === 'extract' ? 'label.cni.variables.extract' : 'label.cni.variables.preview')" @cancel="reset" @keydown.ctrl.enter.stop.prevent>
    <a-spin :spinning="pending">
      <a-alert v-if="error" type="error" show-icon role="alert" :message="error" />
      <template v-if="result">
        <a-alert v-if="result.format === 'binary'" type="warning" show-icon :message="$t('message.cni.variables.binary')" />
        <template v-else-if="mode === 'extract'">
          <div class="cni-variable-table"><a-table size="small" :columns="[{ title: $t('label.name'), dataIndex: 'key', key: 'key' }]" :data-source="(result.candidates || []).map(key => ({ key }))" :pagination="false" row-key="key" :row-selection="{ selectedRowKeys: selected, onChange: keys => { selected = keys } }" /></div>
          <a-alert v-if="result.unsupported?.length" type="warning" show-icon :message="$t('message.cni.variables.unsupported', { names: result.unsupported.join(', ') })" />
        </template>
        <template v-else>
          <div class="cni-variable-table"><a-table size="small" :columns="columns" :data-source="rows" :pagination="false" row-key="key" /></div>
          <a-alert v-if="result.missing?.length" type="error" show-icon :message="$t('message.cni.variables.missing', { names: result.missing.join(', ') })" />
          <a-alert v-if="result.extra?.length" type="error" show-icon :message="$t('message.cni.variables.extra', { names: result.extra.join(', ') })" />
          <a-alert v-if="result.unresolved?.length" type="warning" show-icon :message="$t('message.cni.variables.unresolved', { names: result.unresolved.join(', ') })" />
          <a-alert v-if="result.unsupported?.length" type="warning" show-icon :message="$t('message.cni.variables.unsupported', { names: result.unsupported.join(', ') })" />
          <UserDataContent :resource="{ id: 'cni-variable-preview', name: resource.name || 'cni-preview' }" source-type="cni" tab="cni.content" :preview-encoded="result.userdata" />
        </template>
      </template>
    </a-spin>
    <template #footer><a-button @click="reset">{{ $t('label.cancel') }}</a-button><a-button v-if="mode === 'extract'" type="primary" :disabled="pending || disabled || !selected.length || !result" @click="apply">{{ $t('label.cni.variables.apply') }}</a-button></template>
  </MoldDialog>
</template>
<script>
import { postAPI } from '@/api'
import MoldDialog from '@/components/view/MoldDialog'
import UserDataContent from './UserDataContent'
import { cniPreviewRequest, mergeCniVariables } from '@/utils/cniVariables'
export default {
  components: { MoldDialog, UserDataContent },
  props: { mode: { type: String, default: 'preview' }, encoded: { type: String, default: '' }, resource: { type: Object, default: () => ({}) }, params: { type: Array, default: () => [] }, values: { type: Object, default: () => ({}) }, networkId: { type: String, default: '' }, asNumber: { type: [String, Number], default: undefined }, scope: { type: String, default: '' }, disabled: Boolean },
  emits: ['apply', 'reading'],
  data () { return { visible: false, pending: false, result: null, error: '', selected: [], generation: 0, disposed: false } },
  computed: {
    available () { return !!this.$store.getters.apis?.previewCniConfiguration },
    snapshot () { return JSON.stringify([this.mode, this.encoded, this.resource.id, this.params, this.values, this.networkId, this.asNumber, this.scope, this.disabled, this.available, this.$store.state?.user?.token]) },
    columns () { return [{ title: this.$t('label.name'), dataIndex: 'key', key: 'key' }, { title: this.$t('label.value'), dataIndex: 'value', key: 'value' }] },
    rows () { return (this.result?.params || []).map(key => ({ key, value: key === 'AS_NUMBER' ? this.result.asnumber ?? this.$t('label.automatic') : this.result.values?.[key] ?? '—' })) }
  },
  watch: { snapshot () { this.reset() } },
  beforeUnmount () { this.disposed = true; this.reset() },
  methods: {
    reset () { this.generation++; this.visible = false; this.pending = false; this.result = null; this.error = ''; this.selected = []; this.$emit('reading', false) },
    async open () {
      if (this.disabled || this.pending || !this.available) return
      this.reset(); const generation = ++this.generation; const snapshot = this.snapshot
      this.visible = true; this.pending = true; this.$emit('reading', true)
      const current = () => !this.disposed && generation === this.generation && snapshot === this.snapshot
      try {
        const json = await postAPI('previewCniConfiguration', cniPreviewRequest(this))
        if (!current()) return
        const result = json?.previewcniconfigurationresponse?.cnipreview
        if (!result || typeof result.userdata !== 'string' || this.mode !== 'extract' && result.id !== this.resource.id) throw new Error(this.$t('message.cni.variables.failed'))
        this.result = result; this.selected = [...(result.candidates || [])]
      } catch (error) { if (current()) this.error = error.response?.data?.previewcniconfigurationresponse?.errortext || (error.message?.startsWith('message.') ? this.$t(error.message) : error.message) || this.$t('message.cni.variables.failed') } finally { if (current()) { this.pending = false; this.$emit('reading', false) } }
    },
    apply () {
      if (this.disabled || this.pending || !this.result) return
      try { const keys = mergeCniVariables(this.params, this.selected); this.$emit('apply', keys); this.reset() } catch (error) { this.error = this.$t(error.message) }
    }
  }
}
</script>
<style>
.cni-variable-dialog .cni-variable-table { max-height: 24vh; overflow: auto; margin-bottom: 12px; }
.cni-variable-dialog .ant-table-cell { overflow-wrap: anywhere; white-space: pre-wrap; }
.cni-variable-dialog .content-code { max-height: 32vh; }
.cni-variable-dialog .ant-alert { margin-bottom: 12px; }
</style>
