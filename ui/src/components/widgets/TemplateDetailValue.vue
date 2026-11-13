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
  <div class="template-detail-value">
    <span class="template-detail-value__text">{{ displayValue }}</span>
    <tooltip-button
      v-if="copyable"
      class="template-detail-value__copy"
      :tooltip="copyLabel"
      :aria-label="copyLabel"
      :copy-resource="String(value)"
      icon="copy-outlined"
      type="dashed"
      size="small"
      @onClick="$message.success($t('label.copied.clipboard'))" />
  </div>
</template>
<script>
import TooltipButton from '@/components/widgets/TooltipButton'
import { templateBooleanFields } from '@/utils/templateDetails'
export default {
  name: 'TemplateDetailValue',
  components: { TooltipButton },
  props: { field: { type: String, required: true }, value: { default: undefined }, label: { type: String, required: true } },
  computed: {
    copyable () { return ['id', 'checksum', 'url'].includes(this.field) && this.value != null && String(this.value).length > 0 },
    copyLabel () { return `${this.$t('label.copy')} ${this.label}` },
    displayValue () {
      if (templateBooleanFields.has(this.field)) {
        if (this.value === true || this.value === 'true') return this.$t('label.yes')
        if (this.value === false || this.value === 'false') return this.$t('label.no')
        return this.value == null ? this.$t('label.unknown') : String(this.value)
      }
      return this.value == null ? '—' : String(this.value)
    }
  }
}
</script>
<style scoped>
.template-detail-value { display: flex; align-items: flex-start; gap: 8px; min-width: 0; max-width: 100%; }
.template-detail-value__text { flex: 1; min-width: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.template-detail-value__copy { flex-shrink: 0; }
</style>
