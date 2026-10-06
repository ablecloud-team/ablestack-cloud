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
  <div>
    <disk-offering-selection
      :items="items"
      :zone-id="zoneId"
      :value="value.offeringid || ''"
      :loading="loading"
      :is-iso-selected="false"
      @select-disk-offering-item="selectOffering"
      @handle-search-filter="$emit('search', $event)" />
    <template v-if="offering">
      <disk-quantity-selection
        :offering="offering"
        :size="value.size"
        :count="value.count === undefined ? 1 : value.count"
        @update:size="size => change({ size })"
        @update:count="count => change({ count })" />
      <disk-size-selection
        v-if="offering.iscustomizediops || offering.encrypt"
        input-decorator="size"
        :show-size="false"
        :disk-selected="offering"
        :kms-keys="kmsKeys"
        :loading-kms-keys="loadingKmsKeys"
        @handler-error="error => change({ invalid: error })"
        @update-iops-value="(key, amount) => change({ [key]: amount })"
        @update-data-kms-key="kmskeyid => change({ kmskeyid })" />
      <slot :offering="offering"></slot>
    </template>
  </div>
</template>
<script>
import DiskOfferingSelection from './DiskOfferingSelection'
import DiskSizeSelection from './DiskSizeSelection'
import DiskQuantitySelection from './DiskQuantitySelection'
export default {
  name: 'IsoAdditionalDataDisks',
  components: { DiskOfferingSelection, DiskSizeSelection, DiskQuantitySelection },
  props: {
    value: { type: Object, required: true },
    items: { type: Array, default: () => [] },
    zoneId: { type: String, default: '' },
    loading: Boolean,
    kmsKeys: { type: Array, default: () => [] },
    loadingKmsKeys: Boolean
  },
  emits: ['update:value', 'search'],
  data () { return { cachedOffering: null } },
  computed: {
    offering () { return this.items.find(item => item.id === this.value.offeringid) || (this.cachedOffering?.id === this.value.offeringid ? this.cachedOffering : null) },
    diskSize () { return this.offering?.iscustomized ? (this.value.size || 0) : (this.offering?.disksize || 0) }
  },
  methods: {
    change (values) { this.$emit('update:value', { ...this.value, ...values }) },
    selectOffering (offeringid) {
      this.cachedOffering = this.items.find(item => item.id === offeringid) || null
      this.$emit('update:value', { offeringid: offeringid === '0' ? null : offeringid, count: 1, size: 0, offering: this.cachedOffering })
    }
  }
}
</script>
