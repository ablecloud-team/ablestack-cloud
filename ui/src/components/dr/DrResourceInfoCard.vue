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
  <a-card
    class="spin-content vm-info-card dr-standard-info-card"
    :class="{ 'workspace-fill': viewportScroll }"
    :loading="loading"
    :bordered="true"
    @contextmenu.stop.prevent="$emit('contextmenu', $event, resource)">
    <div class="card-body" :class="{ 'workspace-fill': viewportScroll }">
      <div class="resource-summary-header">
        <div class="resource-details">
          <div class="resource-details__name">
            <div class="avatar dr-resource-avatar">
              <slot name="avatar">
                <GlobalOutlined
                  v-if="resourceType === 'site'"
                  class="dr-resource-avatar__icon" />
                <BranchesOutlined
                  v-else
                  class="dr-resource-avatar__icon" />
              </slot>
            </div>
            <div>
              <h4 class="name">{{ displayName }}</h4>
            </div>
          </div>
          <div v-if="visibleTags.length" class="tags">
            <a-tag v-for="tag in visibleTags" :key="tag.key">
              {{ tag.label }}
            </a-tag>
          </div>
        </div>
        <a-divider />
      </div>
      <div
        class="card-content"
        :class="{ 'workspace-panel': viewportScroll }"
        v-scroll-activity="viewportScroll && { label: $t('label.summary'), scope: resource.id }">
        <div
          v-for="field in visibleSummaryFields"
          :key="field.key"
          class="resource-detail-item">
          <div class="resource-detail-item__label">{{ field.label }}</div>
          <div
            :class="[
              'resource-detail-item__details',
              field.align === 'start' ? 'resource-detail-item__details--start' : ''
            ]">
            <template v-if="field.copy">
              <tooltip-button
                tooltipPlacement="top"
                :tooltip="field.copyTooltip || $t('label.copy')"
                :icon="field.icon || 'copy-outlined'"
                type="dashed"
                size="small"
                :copyResource="copyValue(field)"
                @onClick="$message.success($t('label.copied.clipboard'))" />
              <span class="dr-standard-info-card__copy-value">
                <copy-label
                  v-if="field.copyLabel"
                  :label="valueLabel(field)"
                  :copyValue="copyValue(field)" />
                <span v-else>{{ formatValue(field.value) }}</span>
              </span>
            </template>
            <template v-else>
              <component
                v-if="field.iconComponent"
                :is="field.iconComponent"
                v-bind="field.iconProps || {}" />
              <component
                v-if="field.component"
                :is="field.component"
                v-bind="field.props || {}" />
              <router-link
                v-else-if="field.route"
                :to="field.route">
                <copy-label
                  v-if="field.copyLabel"
                  :label="valueLabel(field)"
                  :copyValue="copyValue(field)" />
                <span v-else>{{ formatValue(field.value) }}</span>
              </router-link>
              <span
                v-else-if="field.copyLabel"
                class="dr-standard-info-card__value">
                <copy-label
                  :label="valueLabel(field)"
                  :copyValue="copyValue(field)" />
              </span>
              <span
                v-else
                :class="['dr-standard-info-card__value', field.valueClass]">
                {{ formatValue(field.value) }}
              </span>
            </template>
          </div>
        </div>
      </div>
    </div>
  </a-card>
</template>

<script>
import scrollActivity from '@/utils/scrollActivity'
import CopyLabel from '@/components/widgets/CopyLabel'
import TooltipButton from '@/components/widgets/TooltipButton'
import { BranchesOutlined, GlobalOutlined } from '@ant-design/icons-vue'

export default {
  name: 'DrResourceInfoCard',
  directives: { scrollActivity },
  components: {
    BranchesOutlined,
    CopyLabel,
    GlobalOutlined,
    TooltipButton
  },
  props: {
    viewportScroll: Boolean,
    resource: {
      type: Object,
      default: () => ({})
    },
    resourceType: {
      type: String,
      default: ''
    },
    title: {
      type: String,
      default: ''
    },
    tags: {
      type: Array,
      default: () => []
    },
    summaryFields: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  emits: ['contextmenu'],
  computed: {
    displayName () {
      return this.title || this.resource.name || this.resource.displayname || this.resource.id || '-'
    },
    visibleTags () {
      return this.tags.filter(tag => tag && tag.visible !== false && tag.label)
    },
    visibleSummaryFields () {
      return this.summaryFields.filter(field => field && field.visible !== false)
    }
  },
  methods: {
    copyValue (field) {
      return String(field.copyResource || field.value || '')
    },
    formatValue (value) {
      if (value === undefined || value === null || value === '') {
        return '-'
      }
      return value
    },
    valueLabel (field) {
      return String(this.formatValue(field.value))
    }
  }
}
</script>
