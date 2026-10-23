<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this
work for additional information regarding copyright ownership. The ASF
licenses this file to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance with the License.
You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations. -->
<template>
  <div data-testid="offering-disk-definition">
    <dl class="offering-root-summary">
      <dt>{{ $t('label.offering.allocation.format') }}</dt>
      <dd>{{ intent?.format.toUpperCase() || $t('label.allocation.unknown') }}</dd>
      <dt>{{ $t('label.offering.allocation.policy') }}</dt>
      <dd>{{ label }}</dd>
      <dt>{{ $t('label.storagetags') }}</dt>
      <dd>{{ tags || $t('label.none') }}</dd>
    </dl>
    <p class="offering-help">{{ $t('message.offering.root.inheritance') }}</p>
  </div>
</template>
<script>
import { allocationIntent, allocationLabel } from '@/utils/volumeAllocation'
export default {
  name: 'OfferingDiskDefinition',
  props: { resource: { type: Object, default: () => ({}) } },
  computed: {
    intent () { return allocationIntent(this.resource) },
    label () { return allocationLabel(this.resource, this.$t) },
    tags () { return Array.isArray(this.resource.tags) ? this.resource.tags.join(', ') : this.resource.tags }
  }
}
</script>
