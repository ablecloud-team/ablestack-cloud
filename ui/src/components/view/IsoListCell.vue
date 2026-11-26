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
  <span v-if="field === 'name'" class="iso-primary">
    <resource-icon v-if="$showIcon() && record.icon?.base64image" :image="record.icon.base64image" size="2x" />
    <os-logo v-else-if="record.ostypename" :osName="record.ostypename" size="xl" />
    <a-tooltip :title="record.name" :trigger="['hover', 'focus']">
      <router-link class="iso-value iso-name" :to="{ path: '/iso/' + record.id, query: returnQuery }" :aria-label="record.name">{{ record.name }}</router-link>
    </a-tooltip>
    <router-link v-if="record.hasannotations" :to="{ path: '/iso/' + record.id, query: { ...returnQuery, tab: 'comments' } }" :aria-label="$t('label.comments')"><message-filled /></router-link>
  </span>
  <a-tooltip v-else :title="fullValue" :trigger="['hover', 'focus']">
    <span tabindex="0" class="iso-value" :aria-label="fullValue">
      <template-availability v-if="field === 'state'" kind="iso" :record="record" compact single-line />
      <router-link v-else-if="field === 'account' && record.account" :to="{ path: '/account', query: { name: record.account, domainid: record.domainid, dataView: true } }">{{ fullValue }}</router-link>
      <router-link v-else-if="field === 'project' && record.projectid" :to="{ path: '/project/' + record.projectid }">{{ fullValue }}</router-link>
      <span v-else>{{ fullValue }}</span>
    </span>
  </a-tooltip>
</template>
<script>
import OsLogo from '@/components/widgets/OsLogo'
import ResourceIcon from '@/components/view/ResourceIcon'
import TemplateAvailability from '@/components/widgets/TemplateAvailability'
import { isoDetailQuery } from '@/utils/isoSearch'
import { isoAvailability } from '@/utils/isoModel'
export default {
  name: 'IsoListCell',
  components: { OsLogo, ResourceIcon, TemplateAvailability },
  props: { record: { type: Object, required: true }, field: { type: String, required: true }, detailQuery: { type: Object, default: null } },
  computed: {
    returnQuery () { return this.detailQuery || isoDetailQuery(this.$route.query, this.$store.getters) },
    fullValue () {
      if (this.field === 'state') {
        const state = isoAvailability(this.record)
        return [this.$t(state.labelKey), ...state.reasons, state.zoneName ? this.$t('label.template.availability.zone', { zone: state.zoneName }) : ''].filter(Boolean).join('\n')
      }
      const value = this.field === 'project' ? (this.record.projectname || this.record.project?.name || this.record.project) : this.record[this.field]
      if (this.field === 'size') return value == null ? '—' : (Number(value) / (1024 ** 3)).toFixed(2) + ' GiB'
      return value == null || value === '' ? '—' : String(value)
    }
  }
}
</script>
<style scoped lang="less">
.iso-primary { display: flex; align-items: center; gap: 8px; min-width: 0; max-width: 100%; }
.iso-primary > :first-child { flex-shrink: 0; }
.iso-value { display: block; min-width: 0; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.iso-name { flex: 1; }
.iso-value:focus-visible { outline: 2px solid var(--ui-color-primary, #1890ff); outline-offset: -2px; }
</style>
