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
  <section class="runtime-compatibility">
    <a-alert v-if="!verified || !complete" type="warning" show-icon :message="$t('message.storage.runtime.compatibility.unknown')" />
    <a-descriptions v-else bordered size="small" :column="1">
      <a-descriptions-item v-for="component in components" :key="component" :label="$t('label.storage.runtime.consumer.' + component)">
        <div>{{ compatibility[component].minimumVersion }} ≤ {{ $t('label.version') }} &lt; {{ compatibility[component].maximumVersionExclusive }}</div>
        <div>{{ $t('label.storage.runtime.observed.version') }}: {{ observedVersion(component) }}</div>
      </a-descriptions-item>
    </a-descriptions>
    <a-alert v-if="!observationVerified" type="warning" show-icon :message="$t('message.storage.runtime.consumer.unverified')" />
    <p>{{ $t('message.storage.runtime.compatibility.scope') }}</p>
  </section>
</template>
<script>
export default {
  name: 'StorageRuntimeCompatibility',
  props: { manifest: { type: Object, default: () => ({}) }, verified: { type: Boolean, default: false }, observation: { type: Object, default: () => ({}) } },
  data: () => ({ components: ['manager', 'agent', 'template'] }),
  computed: {
    compatibility () { return this.manifest.compatibility || {} },
    observationVerified () { return this.observation.platformVersionKnown === true && this.observation.platformObservationRecorded === true && this.observation.updaterVerified === true && !!this.observation.templateManifestSha256 },
    complete () { return this.compatibility.schemaVersion === 1 && this.components.every(component => typeof this.compatibility[component]?.minimumVersion === 'string' && typeof this.compatibility[component]?.maximumVersionExclusive === 'string') }
  },
  methods: {
    observedVersion (component) {
      const key = { manager: 'managerVersion', agent: 'agentVersion', template: 'templatePlatformVersion' }[component]
      if (component === 'template' && !this.observationVerified) return this.$t('label.storage.runtime.consumer.unknown')
      return this.observation[key] || this.$t('label.storage.runtime.consumer.unknown')
    }
  }
}
</script>
<style scoped>
.runtime-compatibility { margin-top: 12px; }
.runtime-compatibility p { margin-top: 12px; }
</style>
