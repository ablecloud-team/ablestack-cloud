<script>
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

import Status from '@/components/widgets/Status'
import TooltipButton from '@/components/widgets/TooltipButton'
import { vpnUserOwner, vpnUserState } from '@/utils/vpnUserPresentation'

export default {
  name: 'VpnUserValue',
  components: { Status, TooltipButton },
  props: { resource: { type: Object, required: true }, field: { type: String, required: true }, plain: Boolean },
  computed: {
    owner () { return vpnUserOwner(this.resource, this.$store.getters) },
    state () { return vpnUserState(this.resource.state) }
  },
  methods: {
    canNavigate (location) {
      if (this.plain || !location) return false
      const matched = this.$router.resolve(location).matched
      return matched.length > 0 && !matched.some(route => route.redirect === '/exception/404')
    }
  }
}
</script>

<template>
  <div v-if="field === 'owners'" class="vpn-user-owner" :data-owner-type="owner.type">
    <div>{{ $t(owner.type) }} ·
      <router-link v-if="canNavigate(owner.location)" :to="owner.location">{{ owner.name }}</router-link>
      <span v-else>{{ owner.name || $t('label.unknown') }}</span>
    </div>
    <div v-if="owner.domain" class="vpn-user-owner-domain">{{ $t('label.domain') }} ·
      <router-link v-if="canNavigate(owner.domainLocation)" :to="owner.domainLocation">{{ owner.domain }}</router-link>
      <span v-else>{{ owner.domain }}</span>
    </div>
  </div>
  <Status
    v-else-if="field === 'state'"
    class="vpn-user-state"
    :data-vpn-user-state="resource.state"
    :text="state.color"
    :display-label="$t(state.label)"
    :show-tooltip="false"
    display-text />
  <div v-else-if="field === 'id'" class="vpn-user-uuid">
    <span>{{ resource.id }}</span>
    <TooltipButton
      :tooltip="$t('label.vpn.user.copy.uuid')"
      :copy-resource="resource.id"
      icon="copy-outlined"
      size="small"
      type="dashed"
      @copy-success="$message.success($t('label.copied.clipboard'))"
      @copy-error="$message.error($t('message.vpn.user.copy.uuid.failed'))" />
  </div>
</template>

<style scoped>
.vpn-user-owner, .vpn-user-uuid {
  min-width: 0;
  overflow-wrap: anywhere;
}

.vpn-user-owner a {
  color: var(--ui-link);
}

.vpn-user-owner a:focus-visible {
  outline: 2px solid var(--ui-focus);
  outline-offset: 2px;
}

.vpn-user-state :deep(.ant-badge-status-text) {
  color: var(--ui-text-primary);
}

.vpn-user-owner-domain {
  color: var(--ui-text-secondary);
}

.vpn-user-uuid {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.vpn-user-uuid > span:first-child {
  min-width: 0;
}
</style>
