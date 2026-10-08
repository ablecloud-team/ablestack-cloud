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
  <div class="detail-tab-toolbar">
    <a-button type="primary" :disabled="!canAdd || loading" @click="$emit('add')"><template #icon><plus-outlined /></template>{{ $t('label.network.rule.add') }}</a-button>
    <a-button :loading="loading" @click="$emit('refresh')"><template #icon><reload-outlined /></template>{{ $t('label.refresh') }}</a-button>
    <slot />
  </div>
</template>
<script>
export default {
  name: 'NetworkRulesToolbar',
  props: { canAdd: { type: Boolean, default: false }, loading: { type: Boolean, default: false } },
  emits: ['add', 'refresh']
}
</script>
