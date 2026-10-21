<!-- Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific
language governing permissions and limitations under the License. -->
<template>
  <a-row :gutter="12" v-if="eligibility?.inputrequired">
    <a-col v-for="field in fields" :key="field" :xs="24" :md="8">
      <a-form-item :name="field" :label="$t(labels[field])" :required="constraint(field).custom" :rules="fieldRules(field)">
        <!-- Keep the raw typed value for validation; AntD min/max otherwise retains a prior valid value. -->
        <a-input-number
          :value="constraint(field).custom ? values[field] : constraint(field).value"
          :disabled="!constraint(field).custom"
          style="width: 100%"
          @update:value="value => $emit('update', field, value)" />
        <small v-if="constraint(field).custom">
          {{ $t('message.sharedfs.offering.input.range', { min: constraint(field).min,
            max: constraint(field).max === 2147483647 ? $t('label.sharedfs.offering.global.limit') : constraint(field).max }) }}
        </small>
        <small v-else>{{ $t('label.sharedfs.offering.fixed.value') }}</small>
      </a-form-item>
    </a-col>
  </a-row>
</template>
<script>
import { computeFields, computeInputErrors, computeReasonText } from '@/utils/sharedFsOffering'
export default {
  name: 'SharedFSOfferingInputs',
  props: {
    eligibility: { type: Object, default: () => null },
    values: { type: Object, required: true }
  },
  emits: ['update'],
  data: () => ({
    fields: computeFields,
    labels: { cpunumber: 'label.cpunumber', memory: 'label.memory.mb', cpuspeed: 'label.sharedfs.offering.cpu.speed' }
  }),
  methods: {
    constraint (field) { return this.eligibility?.constraints?.[field] || {} },
    fieldRules (field) {
      return [{
        validator: async () => {
          const reason = computeInputErrors(this.eligibility, this.values).find(x => x.field === field)
          if (reason) throw new Error(computeReasonText(this.$t, reason))
        }
      }]
    }
  }
}
</script>
