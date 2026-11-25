<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <section class="host-action-context" :aria-label="$t('label.host.action.context')">
    <dl>
      <dt>{{ $t('label.host.action.context') }}</dt><dd>{{ resource.name || resource.id }}</dd>
      <dt>{{ $t('label.state') }}</dt><dd>{{ $t(hostStatusLabel(resource.state)) }}</dd>
      <dt>{{ $t('label.resourcestate') }}</dt><dd>{{ $t(hostStatusLabel(resource.resourcestate)) }}</dd>
    </dl>
    <a-alert v-if="impact" type="warning" show-icon :message="$t(impact)" />
  </section>
</template>

<script>
import { hostStatusLabel } from '@/utils/hostPresentation'

const impacts = {
  deleteHost: 'message.host.action.delete.impact',
  reconnectHost: 'message.host.action.reconnect.impact',
  prepareHostForMaintenance: 'message.host.action.maintenance.impact',
  startRollingMaintenance: 'message.host.action.maintenance.impact',
  issueOutOfBandManagementPowerAction: 'message.host.action.power.impact'
}

export default {
  name: 'HostActionContext',
  props: {
    resource: { type: Object, required: true },
    api: { type: String, default: '' }
  },
  computed: {
    impact () { return impacts[this.api] }
  },
  methods: { hostStatusLabel }
}
</script>

<style scoped>
.host-action-context {
  color: var(--ui-text);
  margin-bottom: 16px;
}

.host-action-context dl {
  display: grid;
  grid-template-columns: minmax(5rem, 1fr) minmax(0, 2fr);
  gap: 6px 12px;
  margin-bottom: 12px;
}

.host-action-context dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.host-action-context dt {
  font-weight: 600;
}
</style>
