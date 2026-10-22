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
      <a-radio-group :value="form.allocationmode || 'legacy'" @change="change({ allocationmode: $event.target.value })">
        <a-radio-button value="legacy">{{ $t('label.allocation.legacy') }}</a-radio-button>
        <a-radio-button value="explicit" :disabled="!canQuery && !form.allocationoriginal">{{ $t('label.allocation.explicit') }}</a-radio-button>
      </a-radio-group>
    </a-form-item>
    <a-form-item v-if="form.allocationmode !== 'explicit'" :label="$t('label.provisioningtype')">
      <a-radio-group :value="form.provisioningtype || 'thin'" @change="change({ provisioningtype: $event.target.value })">
        <a-radio-button v-for="p in ['thin', 'sparse', 'fat']" :key="p" :value="p">{{ p }}</a-radio-button>
      </a-radio-group>
      <p class="allocation-note">{{ $t('message.allocation.legacy') }}</p>
    </a-form-item>
    <template v-else>
      <a-form-item :label="$t('label.allocation.reference.pool')">
        <a-select v-model:value="poolId" :loading="poolLoading" allow-clear @change="onPoolChange" :placeholder="$t('message.allocation.reference.pool')">
          <a-select-option v-for="pool in pools" :key="pool.id" :value="pool.id">{{ pool.name }} · {{ pool.type }}</a-select-option>
        </a-select>
        <a-button v-if="poolError" size="small" @click="fetchPools">{{ $t('label.retry') }}</a-button>
      </a-form-item>
      <a-form-item :label="$t('label.allocation.format')">
        <a-select :value="form.allocationformat" @change="changeFormat" :disabled="!pool">
          <a-select-option value="raw">RAW</a-select-option>
          <a-select-option v-if="pool?.type !== 'RBD'" value="qcow2">QCOW2</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item :label="$t('label.allocation.requested')">
        <a-select :value="form.allocationpolicy" :loading="loading" :disabled="loading || !Object.keys(policies).length" @change="change({ allocationpolicy: $event })">
          <a-select-option v-for="(method, policy) in policies" :key="policy" :value="policy">{{ $t('label.allocation.policy.' + policy) }}</a-select-option>
          <a-select-option v-if="!Object.keys(policies).length && form.allocationpolicy" :value="form.allocationpolicy">{{ $t('label.allocation.policy.' + form.allocationpolicy) }}</a-select-option>
        </a-select>
      </a-form-item>
      <a-alert v-if="error || poolError" type="error" show-icon :message="$t('message.allocation.capability.failed')" />
      <a-button v-if="error && pool" size="small" @click="query">{{ $t('label.retry') }}</a-button>
      <p class="allocation-note">{{ $t('message.allocation.offering.conditions') }}</p>
      <p v-if="form.allocationpolicy" class="allocation-note">{{ $t('message.allocation.cost.' + form.allocationpolicy) }}</p>
    </template>
  </section>
</template>
<script>
import { getAPI } from '@/api'
import { readAllocationList, readAllocationCapability } from '@/utils/volumeAllocation'
export default {
  name: 'AllocationPolicyEditor',
  props: { form: { type: Object, required: true } },
  emits: ['change'],
  data: () => ({ pools: [], poolId: undefined, poolLoading: false, poolError: false, policies: {}, loading: false, error: false, sequence: 0 }),
  computed: {
    pool () { return this.pools.find(p => p.id === this.poolId) },
    canQuery () { return !!this.$store.getters.apis?.getVolumeAllocationCapabilities }
  },
  created () { if (this.canQuery) this.fetchPools() },
  beforeUnmount () { this.sequence++ },
  methods: {
    change (value) { this.$emit('change', value) },
    async fetchPools () {
      this.poolLoading = true; this.poolError = false
      try { this.pools = (await readAllocationList(getAPI, 'listStoragePools', 'storagepool')).filter(p => p.state === 'Up' && ['RBD', 'SharedMountPoint'].includes(p.type)) } catch (e) { this.poolError = true } finally { this.poolLoading = false }
    },
    onPoolChange () {
      this.policies = {}; this.error = false; this.sequence++
      this.change({ allocationformat: this.pool?.type === 'RBD' ? 'raw' : this.form.allocationformat || 'qcow2', allocationpolicy: undefined })
      this.$nextTick(() => this.query())
    },
    changeFormat (format) { this.change({ allocationformat: format, allocationpolicy: undefined }); this.$nextTick(() => this.query()) },
    async query () {
      const id = ++this.sequence
      this.policies = {}; this.error = false; this.loading = !!this.pool
      if (!this.pool) return
      try {
        const cap = await readAllocationCapability(getAPI, this.pool, this.form.allocationformat, 'create', () => id === this.sequence)
        if (!cap) return
        this.policies = cap.policies
        if (!this.policies[this.form.allocationpolicy]) this.change({ allocationpolicy: undefined })
      } catch (e) { if (id === this.sequence) this.error = true } finally { if (id === this.sequence) this.loading = false }
    },
    validate () {
      if (this.form.allocationmode !== 'explicit') return Promise.resolve()
      const unchanged = this.form.allocationoriginal === JSON.stringify({ policy: this.form.allocationpolicy, format: this.form.allocationformat })
      if (!this.poolId && unchanged) return Promise.resolve()
      if (this.loading || this.error || this.poolError || !this.policies[this.form.allocationpolicy]) return Promise.reject(new Error(this.$t('message.allocation.capability.failed')))
      return Promise.resolve()
    }
  }
}
</script>
<style scoped>
.allocation-note { margin: 6px 0 12px; color: var(--ui-text-secondary, #666); font-size: 12px; overflow-wrap: anywhere; }
</style>
