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
  <mold-dialog
    :visible="visible"
    :title="$t(title)"
    size="md"
    form-actions
    focus-guard
    :initial-focus="initialFocus"
    :after-close="() => $emit('after-close')"
    @cancel="$emit('cancel')">
    <a-form v-ctrl-enter="submit" layout="vertical" :model="form" @finish="submit">
      <a-row :gutter="16">
        <a-col :xs="24" :sm="12">
          <a-form-item html-for="secondary-selector-editor-zone" :label="$t('label.zone')">
            <a-input id="secondary-selector-editor-zone" :value="zoneName" disabled />
          </a-form-item>
        </a-col>
        <a-col :xs="24" :sm="12">
          <a-form-item name="type" html-for="secondary-selector-type" :label="$t('label.type')">
            <a-select id="secondary-selector-type" v-model:value="form.type" :options="types" :disabled="editing || busy" />
            <template #extra>{{ $t('message.secondary.selector.type.' + scriptVariable) }}</template>
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item name="name" html-for="secondary-selector-name" :label="$t('label.name')" required>
        <a-input id="secondary-selector-name" v-model:value="form.name" :disabled="editing || busy" />
      </a-form-item>
      <a-form-item name="description" html-for="secondary-selector-description" :label="$t('label.description')" required>
        <a-input id="secondary-selector-description" v-model:value="form.description" :disabled="editing || busy" />
      </a-form-item>
      <a-form-item name="mode" :label="$t('label.secondary.selector.mode')">
        <a-radio-group v-model:value="form.mode" :disabled="busy" @change="confirmReplacement = false">
          <a-radio value="fixed">{{ $t('label.secondary.selector.fixed') }}</a-radio>
          <a-radio value="script">{{ $t('label.secondary.selector.script') }}</a-radio>
        </a-radio-group>
      </a-form-item>
      <a-form-item v-if="form.mode === 'fixed'" name="target" html-for="secondary-selector-target" :label="$t('label.secondary.selector.target')" required>
        <a-select
          id="secondary-selector-target"
          v-model:value="form.target"
          :loading="storesLoading"
          :options="storeOptions"
          :disabled="busy || storesLoading || storesFailed"
          option-filter-prop="label"
          show-search />
        <template #extra>
          <span v-if="storesFailed">{{ $t('message.secondary.selector.stores.failed') }}</span>
          <span v-else-if="!storesLoading && !storeOptions.some(option => !option.disabled)">{{ $t('message.secondary.selector.no.stores') }}</span>
        </template>
      </a-form-item>
      <a-form-item v-else name="script" html-for="secondary-selector-script" :label="$t('label.secondary.selector.script')" required>
        <a-textarea
          id="secondary-selector-script"
          v-model:value="form.script"
          :rows="5"
          :maxlength="65535"
          :disabled="busy"
          spellcheck="false" />
        <template #extra>{{ $t('message.secondary.selector.variables', [scriptVariable]) }}</template>
      </a-form-item>
      <a-checkbox v-if="requiresReplacement" v-model:checked="confirmReplacement" :disabled="busy">
        {{ $t('label.secondary.selector.confirm.fixed') }}
      </a-checkbox>
      <a-alert v-if="duplicate" type="warning" show-icon :message="$t('message.secondary.selector.duplicate')" />
    </a-form>
    <mold-dialog-actions :busy="busy">
      <a-button @click="$emit('cancel')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="busy" :disabled="!canSave" @click="submit">
        {{ $t('label.save') }}
      </a-button>
    </mold-dialog-actions>
  </mold-dialog>
</template>

<script>
import MoldDialog from '@/components/view/MoldDialog'
import MoldDialogActions from '@/components/view/MoldDialogActions'
import { fixedSelectorId, fixedSelectorRule, selectorTypes } from '@/utils/secondaryStorageSelectors'

export default {
  name: 'SecondaryStorageSelectorDialog',
  components: { MoldDialog, MoldDialogActions },
  props: {
    dialog: { type: Object, required: true },
    zoneName: { type: String, required: true },
    stores: { type: Array, default: () => [] },
    visible: { type: Boolean, default: false },
    busy: { type: Boolean, default: false },
    storesLoading: { type: Boolean, default: false },
    storesFailed: { type: Boolean, default: false }
  },
  emits: ['save', 'cancel', 'after-close'],
  data () {
    const record = this.dialog.record || {}
    const fixed = fixedSelectorId(record.heuristicrule)
    const used = this.dialog.usedTypes || []
    return {
      form: {
        type: record.type || this.dialog.type || selectorTypes.find(type => !used.includes(type)) || selectorTypes[0],
        mode: this.dialog.mode === 'edit' && !fixed ? 'script' : 'fixed',
        target: fixed || undefined,
        script: record.heuristicrule || '',
        name: record.name || '',
        description: record.description || ''
      },
      confirmReplacement: false
    }
  },
  computed: {
    editing () { return this.dialog.mode === 'edit' },
    title () {
      return this.editing ? 'label.secondary.selector.edit' : 'label.secondary.selector.add'
    },
    initialFocus () {
      if (!this.editing) return '#secondary-selector-name'
      return fixedSelectorId(this.dialog.record.heuristicrule) ? '#secondary-selector-target' : '#secondary-selector-script'
    },
    types () { return selectorTypes.map(type => ({ value: type, label: type })) },
    scriptVariable () { return this.form.type.toLowerCase() },
    storeOptions () {
      return this.stores.map(store => ({
        value: store.id.toLowerCase(),
        label: [store.name || store.id, store.protocol, String(store.readonly) === 'true' ? this.$t('label.readonly') : ''].filter(Boolean).join(' · '),
        disabled: String(store.readonly) === 'true'
      }))
    },
    duplicate () { return !this.editing && !!this.dialog.usedTypes?.includes(this.form.type) },
    requiresReplacement () { return this.editing && !fixedSelectorId(this.dialog.record.heuristicrule) && this.form.mode === 'fixed' },
    rule () { return this.form.mode === 'fixed' ? fixedSelectorRule(this.form.target) : this.form.script },
    canSave () {
      if (this.busy || this.duplicate || !this.rule.trim() || this.rule.length > 65535) return false
      if (this.requiresReplacement && !this.confirmReplacement) return false
      if (this.form.mode === 'fixed' && (this.storesLoading || this.storesFailed || !this.storeOptions.some(option => option.value === this.form.target && !option.disabled))) return false
      return selectorTypes.includes(this.form.type) && !!this.form.name.trim() && !!this.form.description.trim()
    }
  },
  methods: {
    submit (event) {
      if (event?.preventDefault) event.preventDefault()
      if (!this.canSave) return
      this.$emit('save', {
        name: this.form.name.trim(),
        description: this.form.description.trim(),
        type: this.form.type,
        heuristicrule: this.rule
      })
    }
  }
}
</script>
