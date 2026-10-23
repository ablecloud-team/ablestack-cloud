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
  <section class="allocation-editor">
    <a-form-item :label="$t('label.allocation.mode')">
      <a-radio-group :value="form.allocationmode || 'legacy'" @change="changeMode($event.target.value)">
        <a-radio-button value="explicit" :disabled="!canDefine">{{ $t('label.offering.allocation.europa') }}</a-radio-button>
        <a-radio-button value="legacy">{{ $t('label.offering.allocation.legacy') }}</a-radio-button>
      </a-radio-group>
    </a-form-item>
    <a-form-item v-if="form.allocationmode !== 'explicit'" :label="$t('label.provisioningtype')">
      <a-radio-group :value="form.provisioningtype || 'thin'" @change="change({ provisioningtype: $event.target.value })">
        <a-radio-button v-for="p in ['thin', 'sparse', 'fat']" :key="p" :value="p">{{ p }}</a-radio-button>
      </a-radio-group>
      <p class="allocation-note">{{ $t('message.allocation.legacy') }}</p>
    </a-form-item>
    <template v-else>
      <div class="allocation-fields">
        <a-form-item :label="$t('label.offering.allocation.format')" name="allocationformat">
          <a-select :value="form.allocationformat" @change="changeFormat" :aria-label="$t('label.offering.allocation.format')">
            <a-select-option value="raw">RAW</a-select-option>
            <a-select-option value="qcow2">QCOW2</a-select-option>
          </a-select>
          <p class="allocation-note">{{ form.allocationformat === 'qcow2' ? $t('label.offering.allocation.gfs2.only') : 'GFS2 · RBD' }}</p>
        </a-form-item>
        <a-form-item :label="$t('label.offering.allocation.policy')" name="allocationpolicy">
          <a-select :value="form.allocationpolicy" @change="change({ allocationpolicy: $event })" :aria-label="$t('label.offering.allocation.policy')">
            <a-select-option v-for="policy in policies" :key="policy" :value="policy">{{ $t('label.allocation.policy.' + policy) }}{{ policy === 'falloc' ? ' · ' + $t('label.offering.allocation.gfs2.only') : '' }}</a-select-option>
          </a-select>
        </a-form-item>
      </div>
      <p v-if="form.allocationpolicy" class="allocation-note">{{ $t('message.allocation.cost.' + form.allocationpolicy) }}</p>
      <details class="allocation-support">
        <summary>{{ $t('label.offering.allocation.support') }}</summary>
        <dl>
          <dt>GFS2 · RAW</dt>
          <dd>off · falloc · full</dd>
          <dt>GFS2 · QCOW2</dt>
          <dd>metadata · falloc · full</dd>
          <dt>RBD · RAW</dt>
          <dd>off · full</dd>
        </dl>
        <p class="allocation-note">{{ $t('message.offering.allocation.rbd') }}</p>
      </details>
    </template>
    <p v-if="!canDefine" class="allocation-note">{{ $t('message.offering.allocation.old.api') }}</p>
  </section>
</template>
<script>
import { offeringAllocationPolicies } from '@/utils/volumeAllocation'
export default {
  name: 'AllocationPolicyEditor',
  props: { form: { type: Object, required: true }, apiParams: { type: Object, default: () => ({}) } },
  emits: ['change'],
  computed: {
    policies () { return offeringAllocationPolicies(this.form.allocationformat) },
    canDefine () { return !!(this.apiParams.allocationpolicy && this.apiParams.allocationformat) || !!this.form.allocationoriginal }
  },
  methods: {
    change (value) { this.$emit('change', value) },
    changeMode (mode) {
      if (mode === 'explicit') {
        if (!this.canDefine) return
        const format = ['raw', 'qcow2'].includes(this.form.allocationformat) ? this.form.allocationformat : 'raw'
        const policies = offeringAllocationPolicies(format)
        this.change({ allocationmode: mode, allocationformat: format, allocationpolicy: policies.includes(this.form.allocationpolicy) ? this.form.allocationpolicy : policies[0], storagetype: 'shared', encryptdisk: false })
      } else this.change({ allocationmode: 'legacy' })
    },
    changeFormat (format) {
      const policies = offeringAllocationPolicies(format)
      this.change({ allocationformat: format, allocationpolicy: policies.includes(this.form.allocationpolicy) ? this.form.allocationpolicy : policies[0] })
    },
    validate () {
      if (this.form.allocationmode !== 'explicit') return Promise.resolve()
      if (!this.canDefine || !this.policies.includes(this.form.allocationpolicy) || this.form.storagetype === 'local' || this.form.encryptdisk) return Promise.reject(new Error(this.$t('message.offering.allocation.invalid')))
      return Promise.resolve()
    }
  }
}
</script>
<style scoped>
.allocation-note { margin: 6px 0 12px; color: var(--ui-text-secondary, #666); font-size: 12px; overflow-wrap: anywhere; }
.allocation-editor :deep(.ant-radio-group) { display: flex; flex-wrap: wrap; gap: 8px; }
.allocation-editor :deep(.ant-radio-button-wrapper) { border-left-width: 1px; border-radius: 3px; height: auto; white-space: normal; }
.allocation-editor :deep(.ant-radio-button-wrapper::before) { display: none; }
.allocation-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.allocation-support { margin: 6px 0 18px; }
.allocation-support summary { color: var(--ui-link, #1677ff); cursor: pointer; font-size: 12px; }
.allocation-support dl { display: grid; grid-template-columns: 135px minmax(0, 1fr); gap: 8px; font-size: 12px; margin: 12px 0; }
.allocation-support dd { margin: 0; }
@media (max-width: 600px) { .allocation-fields, .allocation-support dl { grid-template-columns: minmax(0, 1fr); } }
</style>
