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
  <span class="asn-list-cell">
    <a-badge v-if="field === 'allocationstate'" :status="record.allocationstate === 'Allocated' ? 'processing' : record.allocationstate === 'Free' ? 'success' : 'default'" :text="$t(record.allocationstate === 'Allocated' ? 'label.asn.state.allocated' : record.allocationstate === 'Free' ? 'label.asn.state.free' : 'label.unknown')" />
    <span v-else-if="field === 'releasereason'">{{ $t('message.asn.release.' + reason) }}</span>
    <span v-else-if="['allocated', 'created'].includes(field)">{{ record[field] ? $toLocaleDate(record[field]) : '—' }}</span>
    <template v-else>
      <router-link v-if="reference.path" :to="{ path: reference.path }">{{ reference.text }}</router-link>
      <span v-else :title="field === 'asnrange' ? record.asnrangeid : undefined">{{ reference.text }}</span>
      <tooltip-button v-if="field === 'asnumber' && record.id" :copyResource="record.id" :tooltip="$t('label.id') + ': ' + record.id" icon="copy-outlined" size="small" type="text" />
    </template>
  </span>
</template>
<script>
import { asnField, asnReleaseReason } from '@/utils/asnOperations'
import TooltipButton from '@/components/widgets/TooltipButton'
export default {
  components: { TooltipButton },
  props: { record: { type: Object, required: true }, field: { type: String, required: true } },
  computed: {
    reference () { return asnField(this.record, this.field, this.$store.getters.apis) },
    reason () { return asnReleaseReason(this.record) }
  }
}
</script>
<style scoped>
.asn-list-cell { overflow-wrap: anywhere; color: var(--ui-text-primary); }
.asn-list-cell a { color: var(--ui-link); }
</style>
