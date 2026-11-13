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
  <div class="template-availability" :class="{ 'template-availability--compact': compact }">
    <a-badge :status="availability.badge" :text="$t(availability.labelKey)" />
    <div v-for="reason in shownReasons" :key="reason" class="template-availability__reason">{{ reason }}</div>
    <div v-if="showScope" class="template-availability__scope">
      <span v-if="availability.complete">{{ $t('label.template.availability.zones', { ready: availability.readyCount, total: availability.zoneCount }) }}</span>
      <span v-else-if="availability.zoneName">{{ $t('label.template.availability.zone', { zone: availability.zoneName }) }}</span>
      <span v-else>{{ $t('label.template.availability.observed') }}</span>
    </div>
    <a-button v-if="inspectable && availability.key !== 'ready'" size="small" class="template-availability__inspect" @click="$emit('show-zones')">{{ $t('label.template.availability.inspect') }}</a-button>
  </div>
</template>
<script>
import { templateAvailability } from '@/utils/templateAvailability'
export default {
  name: 'TemplateAvailability',
  props: { record: { type: Object, required: true }, observation: { type: Object, default: null }, compact: Boolean, inspectable: Boolean, showScope: { type: Boolean, default: true } },
  emits: ['show-zones'],
  computed: {
    availability () { return templateAvailability(this.record, this.observation) },
    shownReasons () { return this.compact ? this.availability.reasons.slice(0, 1) : this.availability.reasons }
  }
}
</script>
<style scoped>
.template-availability { min-width: 0; overflow-wrap: anywhere; }
.template-availability--compact { min-width: 120px; }
.template-availability__reason, .template-availability__scope { margin-top: 4px; font-size: 12px; color: var(--ui-text-secondary); white-space: normal; overflow-wrap: anywhere; }
.template-availability__inspect { margin-top: 8px; }
.template-availability :deep(.ant-badge-status-text) { white-space: normal; }
</style>
