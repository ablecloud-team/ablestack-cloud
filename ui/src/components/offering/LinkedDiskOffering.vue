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
  <span class="linked-disk-offering">
    <template v-if="connection">
      {{ $t(computeOnly === true ? 'label.compute.offering.dedicated.auto.created' : computeOnly === false ? 'label.general.disk.offering.link' : 'label.allocation.unknown') }}
      <p class="connection-help">{{ $t(computeOnly === true ? 'message.offering.compute.dedicated' : computeOnly === false ? 'message.offering.compute.linked' : 'message.offering.disk.connection.unknown') }}</p>
    </template>
    <router-link v-else-if="canNavigate" :to="{ path: '/diskoffering/' + encodeURIComponent(resource.diskofferingid) }">{{ name }}</router-link>
    <span v-else :title="computeOnly === null && name !== '—' ? $t('message.offering.disk.connection.unknown') : undefined">{{ name }}</span>
  </span>
</template>
<script>
export default {
  name: 'LinkedDiskOffering',
  props: { resource: { type: Object, required: true }, connection: { type: Boolean, default: false } },
  computed: {
    computeOnly () {
      const value = this.resource.diskofferingcomputeonly
      if (value === true || value === 'true') return true
      if (value === false || value === 'false') return false
      return null
    },
    name () { return this.resource.diskofferingname || this.resource.diskofferingdisplaytext || '—' },
    canNavigate () { return this.computeOnly === false && !!this.resource.diskofferingid && this.name !== '—' && !!this.$store.getters.apis?.listDiskOfferings }
  }
}
</script>
<style scoped>
.linked-disk-offering { overflow-wrap: anywhere; }
.connection-help { margin: 4px 0 0; font-size: 12px; opacity: .8; }
</style>
