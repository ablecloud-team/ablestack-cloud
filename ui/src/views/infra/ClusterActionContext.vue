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
  <section class="cluster-action-context" :aria-label="$t('label.clustername')">
    <dl>
      <dt>{{ $t('label.clustername') }}</dt><dd>{{ resource.name || resource.id }}</dd>
      <dt>{{ $t('label.allocationstate') }}</dt><dd>{{ $t(clusterStateLabel('allocationstate', resource.allocationstate)) }}</dd>
      <dt>{{ $t('label.managedstate') }}</dt><dd>{{ $t(clusterStateLabel('managedstate', resource.managedstate)) }}</dd>
    </dl>
    <a-alert v-if="impact" :type="destructive ? 'warning' : 'info'" show-icon :message="$t(impact)" />
  </section>
</template>

<script>
import { clusterStateLabel } from '@/utils/clusterSearch'

export default {
  name: 'ClusterActionContext',
  props: {
    resource: { type: Object, required: true },
    impact: { type: String, default: '' },
    destructive: { type: Boolean, default: false }
  },
  methods: { clusterStateLabel }
}
</script>

<style scoped>
.cluster-action-context { color: var(--ui-text); margin-bottom: 16px; }
.cluster-action-context dl {
  display: grid;
  grid-template-columns: minmax(6rem, 1fr) minmax(0, 2fr);
  gap: 6px 12px;
  margin-bottom: 12px;
}
.cluster-action-context dt { font-weight: 600; }
.cluster-action-context dd { margin: 0; overflow-wrap: anywhere; }
</style>
