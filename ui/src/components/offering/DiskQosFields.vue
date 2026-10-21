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
  <div class="disk-qos-fields">
    <p class="qos-help">{{ $t('message.offering.qos.units') }}</p>
    <div v-for="group in groups" :key="group.api" class="qos-group">
      <div class="qos-title">{{ $t(group.label) }} ({{ group.unit }})</div>
      <div class="qos-grid">
        <a-form-item v-for="field in fieldsFor(group)" :key="field.api" :name="field.form">
          <template #label>
            <span class="qos-label">
              <span>{{ $t(field.label) }}{{ field.unit ? ' (' + field.unit + ')' : '' }}</span>
              <small v-if="current">{{ $t('label.current') }}: {{ current[field.form] === '' ? $t('label.offering.qos.default') : current[field.form] }}</small>
            </span>
          </template>
          <a-input v-model:value="values[field.form]" :aria-label="$t(group.label) + ' ' + $t(field.label)" :data-qos="field.api" inputmode="numeric" :placeholder="current ? $t('label.offering.qos.preserve') : '0'" :disabled="disabled" />
        </a-form-item>
      </div>
    </div>
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
    disabled: Boolean
  },
  computed: {
    values () { return this.form },
    groups () { return qosGroups.filter(group => group.api in this.apiParams) },
    error () { return qosError(this.form, this.current) }
  },
  methods: {
    fieldsFor (group) {
      return [
        { api: group.api, form: group.form, label: 'label.offering.qos.base' },
        { api: group.api + 'max', form: group.form + 'max', label: 'label.offering.qos.maximum' },
        { api: group.api + 'maxlength', form: group.form + 'maxlength', label: 'label.offering.qos.duration', unit: 's' }
      ].filter(field => field.api in this.apiParams)
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
@media (max-width: 480px) { .qos-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
