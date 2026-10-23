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
  <details v-if="enabled" class="offering-advanced-group">
    <summary>
      <span>{{ $t(title) }}</span>
      <small v-if="hint">{{ $t(hint) }}</small>
    </summary>
    <div class="offering-advanced-body">
      <slot />
    </div>
  </details>
  <div v-else>
    <slot />
  </div>
</template>
<script>
export default { name: 'OfferingAdvancedGroup', props: { enabled: Boolean, title: String, hint: String } }
</script>
<style scoped>
.offering-advanced-group { border-bottom: 1px solid var(--ui-border, #d9d9d9); }
summary { padding: 14px 0; cursor: pointer; }
summary small { color: var(--ui-text-secondary, #666); font-size: 12px; margin-left: 8px; }
.offering-advanced-body { padding: 6px 0 10px; }
@media (max-width: 600px) { summary small { display: block; margin: 4px 0 0 16px; } }
</style>
