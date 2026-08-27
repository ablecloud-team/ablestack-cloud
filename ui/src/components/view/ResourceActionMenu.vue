<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
<template>
  <div class="resource-action-menu">
    <div v-if="showResourceTitle && title" class="resource-action-menu__title" :title="title">
      {{ title }}
    </div>
    <a-menu class="resource-action-menu__list" :selectable="false">
      <a-menu-item-group v-for="group in groupedEntries" :key="group.key">
        <template #title>{{ $t(group.label) }}</template>
        <a-menu-item
          v-for="entry in group.entries"
          :key="entry.key"
          :disabled="entry.disabled"
          :class="{ 'resource-action-menu__item--danger': entry.danger && !entry.disabled }"
          @click="execute(entry)">
          <a-tooltip placement="right" :title="entry.disabled ? entry.tooltip : ''">
            <span class="resource-action-menu__item-content">
              <span class="resource-action-menu__item-icon" aria-hidden="true">
                <render-icon v-if="typeof entry.icon === 'string'" :icon="entry.icon" />
                <font-awesome-icon v-else-if="entry.icon" :icon="entry.icon" />
              </span>
              <span class="resource-action-menu__item-label">{{ $t(entry.label) }}</span>
              <a-badge
                v-if="entry.badge"
                class="resource-action-menu__badge"
                :count="entry.badge"
                :overflowCount="9" />
            </span>
          </a-tooltip>
        </a-menu-item>
      </a-menu-item-group>
    </a-menu>
  </div>
</template>

<script>
import { groupActionMenuEntries } from '@/utils/actionMenu'

export default {
  name: 'ResourceActionMenu',
  props: {
    entries: { type: Array, default: () => [] },
    title: { type: String, default: '' },
    showResourceTitle: { type: Boolean, default: false }
  },
  emits: ['execute'],
  computed: {
    groupedEntries () {
      return groupActionMenuEntries(this.entries)
    }
  },
  methods: {
    execute (entry) {
      if (!entry.disabled) {
        this.$emit('execute', entry)
      }
    }
  }
}
</script>
