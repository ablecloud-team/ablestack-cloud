<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance with the License.
You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations
under the License. -->
<template>
  <div class="disk-qos-fields" :class="{ compact }">
    <p class="qos-help">{{ $t(compact ? 'message.offering.qos.creation' : 'message.offering.qos.units') }}</p>
    <div class="qos-base-groups">
      <div v-for="group in groups" :key="group.api" class="qos-group">
        <div v-if="!compact" class="qos-title">{{ $t(group.label) }} ({{ group.unit }})</div>
        <div class="qos-grid">
          <a-form-item v-for="field in fieldsFor(group)" :key="field.api" :name="field.form">
            <template #label>
              <span class="qos-label">
                <span>{{ compact ? groupLabel(group) : $t(field.label) }}{{ field.unit ? ' (' + field.unit + ')' : '' }}</span>
                <small v-if="current">{{ $t('label.current') }}: {{ current[field.form] === '' ? $t('label.offering.qos.default') : current[field.form] }}</small>
              </span>
            </template>
            <a-input v-model:value="values[field.form]" :aria-label="compact ? groupLabel(group) : $t(group.label) + ' ' + $t(field.label)" :data-qos="field.api" inputmode="numeric" :placeholder="current ? $t('label.offering.qos.preserve') : '0'" :disabled="disabled" />
          </a-form-item>
        </div>
      </div>
    </div>
    <details v-if="compact && burstGroups.length" class="qos-burst" :open="hasBurst || !!error">
      <summary>{{ $t('label.offering.qos.burst.settings') }}</summary>
      <div v-for="group in burstGroups" :key="group.api" class="qos-group">
        <div class="qos-title">{{ groupLabel(group) }}</div>
        <div class="qos-grid">
          <a-form-item v-for="field in fieldsFor(group, true)" :key="field.api" :name="field.form">
            <template #label>{{ $t(field.label) }}{{ field.unit ? ' (' + field.unit + ')' : '' }}</template>
            <a-input v-model:value="values[field.form]" :aria-label="groupLabel(group) + ' ' + $t(field.label)" :data-qos="field.api" inputmode="numeric" placeholder="0" :disabled="disabled" />
          </a-form-item>
        </div>
      </div>
    </details>
    <a-alert v-if="error" type="error" :message="$t(error)" show-icon />
  </div>
</template>
<script>
import { qosGroups, qosError } from '@/utils/offeringQos'
export default {
  name: 'DiskQosFields',
  props: {
    form: { type: Object, required: true },
    apiParams: { type: Object, default: () => ({}) },
    current: { type: Object, default: null },
    disabled: Boolean,
    compact: Boolean
  },
  computed: {
    values () { return this.form },
    groups () { return qosGroups.filter(group => group.api in this.apiParams) },
    error () { return qosError(this.form, this.current) },
    burstGroups () { return this.groups.filter(group => this.fieldsFor(group, true).length) },
    hasBurst () { return this.burstGroups.some(group => this.fieldsFor(group, true).some(field => this.form[field.form] !== undefined && this.form[field.form] !== '' && Number(this.form[field.form]) !== 0)) }
  },
  methods: {
    groupLabel (group) { return this.$t('label.offering.qos.' + group.api) + (group.unit === 'B/s' ? ' (B/s)' : '') },
    fieldsFor (group, burst = false) {
      return [
        { api: group.api, form: group.form, label: 'label.offering.qos.base' },
        { api: group.api + 'max', form: group.form + 'max', label: 'label.offering.qos.maximum' },
        { api: group.api + 'maxlength', form: group.form + 'maxlength', label: 'label.offering.qos.duration', unit: 's' }
      ].filter(field => field.api in this.apiParams && (!this.compact || (field.api !== group.api) === burst))
    },
    validate () {
      if (this.error) return Promise.reject(new Error(this.$t(this.error)))
      return Promise.resolve()
    }
  }
}
</script>
<style scoped lang="scss">
.qos-help, .qos-label small { font-size: 12px; opacity: .8; }
.qos-group { margin-top: 16px; }
.qos-title { font-weight: 500; margin-bottom: 8px; }
.qos-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.qos-grid .ant-form-item { display: block; min-width: 0; margin-bottom: 8px; }
.qos-grid :deep(.ant-form-item-label), .qos-grid :deep(.ant-form-item-control) { width: 100%; max-width: 100%; min-width: 0; }
.qos-label { display: flex; flex-direction: column; gap: 2px; white-space: normal; }
.compact .qos-base-groups { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.compact .qos-base-groups .qos-grid { grid-template-columns: minmax(0, 1fr); }
.compact .qos-group { margin-top: 8px; }
.compact .qos-burst .qos-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.qos-burst summary { color: var(--ui-link, #1677ff); cursor: pointer; margin: 8px 0 14px; }
@media (max-width: 600px) { .compact .qos-base-groups, .compact .qos-burst .qos-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 480px) { .qos-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
