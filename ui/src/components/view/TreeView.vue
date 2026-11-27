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
  <resource-layout :viewport-scroll="viewportScroll">
    <template #left>
      <a-card :class="{ 'workspace-fill': viewportScroll }" :bordered="false" :aria-busy="loading">
        <div class="domain-tree-toolbar">
          <a-input-search
            v-model:value="searchQuery"
            :placeholder="$t('label.search')"
            :aria-label="$t('label.search')"
            allow-clear
            @search="$emit('search', $event)" />
          <slot name="filters" />
        </div>
        <div
          :class="{ 'workspace-panel': viewportScroll }"
          v-scroll-activity="viewportScroll && { label: $t('label.domains'), scope: keyword }">
          <a-spin :spinning="loading">
          <a-alert
            v-if="error"
            type="error"
            show-icon
            :message="error">
            <template #description><a-button size="small" @click="$emit('refresh')">{{ $t('label.retry') }}</a-button></template>
          </a-alert>
          <a-tree
            v-else-if="treeData.length"
            show-line
            show-icon
            checkable
            check-strictly
            class="list-tree-view mold-domain-tree"
            :tree-data="treeData"
            :load-data="loadChildren"
            :selected-keys="resource.id ? [resource.id] : []"
            :checked-keys="{ checked: resource.id ? [resource.id] : [], halfChecked: [] }"
            :expanded-keys="expandedKeys"
            :auto-expand-parent="false"
            @select="(_, event) => $emit('select', event.node.eventKey)"
            @check="(_, event) => $emit('select', event.node.eventKey)"
            @expand="$emit('expand', $event)"
            @rightClick="handleContextMenu">
            <template #icon="{ data }"><block-outlined v-if="data.isLeaf" /></template>
            <template #title="data">
              <span
                tabindex="0"
                class="mold-domain-tree-title"
                :title="data.title"
                @keydown="handleTreeKey($event, data)">{{ data.title }}</span>
            </template>
          </a-tree>
          <a-empty v-else-if="!loading" :description="$t('label.no.data')" />
          </a-spin>
        </div>
        <slot name="pagination" />
      </a-card>
    </template>
    <template #right>
      <a-spin :wrapper-class-name="viewportScroll ? 'workspace-fill' : undefined" :spinning="loading || selecting">
        <a-card v-if="resource.id && !loading" class="spin-content resource-content-card">
          <a-tabs
            :class="{ 'workspace-tabset': viewportScroll }"
            v-scroll-activity="viewportScroll && { selector: tabScrollSelector, label: $t('label.details'), scope: activeTab }"
            :key="resource.id"
            :tab-position="resourceTabPosition"
            :animated="false"
            :active-key="activeTab"
            @change="$emit('tab-change', $event)">
            <a-tab-pane v-for="tab in tabs" :key="tab.name" :tab="$t('label.' + tab.name)">
              <keep-alive>
                <component
                  :is="tab.component"
                  :resource="resource"
                  :resource-type="tab.resourceType"
                  :tab="activeTab"
                  :loading="selecting"
                  :actions="actions"
                  :bordered="false"
                  @refresh="$emit('refresh-resource')" />
              </keep-alive>
            </a-tab-pane>
          </a-tabs>
        </a-card>
      </a-spin>
    </template>
  </resource-layout>
  <resource-context-menu
    v-if="!loading && !selecting && contextMenuActions.length"
    :actions="contextMenuActions"
    :resource="contextRecord"
    :position="contextPosition"
    @close="contextRecord = null"
    @exec-action="handleContextAction" />
</template>

<script>
import scrollActivity, { tabScrollSelector } from '@/utils/scrollActivity'
import { Empty } from 'ant-design-vue'
import { mixinDevice } from '@/utils/mixin.js'
import ResourceLayout from '@/layouts/ResourceLayout'
import ResourceContextMenu from '@/components/view/ResourceContextMenu'

export default {
  name: 'TreeView',
  directives: { scrollActivity },
  components: { AEmpty: Empty, ResourceLayout, ResourceContextMenu },
  mixins: [mixinDevice],
  provide () { return { resourceViewportEnabled: () => this.viewportScroll } },
  props: {
    viewportScroll: Boolean,
    treeData: { type: Array, required: true },
    resource: { type: Object, required: true },
    tabs: { type: Array, required: true },
    actions: { type: Array, required: true },
    keyword: { type: String, default: '' },
    activeTab: { type: String, default: 'domain' },
    expandedKeys: { type: Array, default: () => [] },
    loading: Boolean,
    selecting: Boolean,
    error: { type: String, default: '' },
    loadChildren: { type: Function, default: undefined }
  },
  emits: ['search', 'refresh', 'refresh-resource', 'select', 'expand', 'tab-change', 'exec-action'],
  data () {
    return { searchQuery: this.keyword, contextRecord: null, contextPosition: null }
  },
  computed: {
    tabScrollSelector () { return tabScrollSelector },
    contextMenuActions () {
      const record = this.contextRecord
      if (!record) return []
      return this.actions
        .filter(action => action.api in this.$store.getters.apis &&
          (action.dataView || action.listView) &&
          (!action.show || action.show(record, this.$store.getters)))
        .map(action => ({ ...action, dataView: true }))
    }
  },
  watch: {
    keyword (value) { this.searchQuery = value },
    loading (value) { if (value) this.contextRecord = null }
  },
  methods: {
    handleTreeKey (event, data) {
      if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return
      const rect = event.currentTarget.getBoundingClientRect()
      this.openTreeMenu(event, data, { x: rect.left + 16, y: rect.top + 20 })
    },
    handleContextMenu ({ event, node }) {
      this.openTreeMenu(event, node.dataRef, { x: event.clientX, y: event.clientY })
    },
    openTreeMenu (event, record, position) {
      if (this.loading || this.selecting || !record?.id) return
      this.contextRecord = record.id === this.resource.id ? this.resource : record
      if (record.id !== this.resource.id) this.$emit('select', record.id)
      if (!this.contextMenuActions.length) return
      event.preventDefault()
      event.stopPropagation()
      this.contextPosition = position
    },
    handleContextAction (action) {
      const record = this.contextRecord
      this.contextRecord = null
      this.$emit('exec-action', { ...action, resource: record })
    }
  }
}
</script>

<style lang="less" scoped>
.mold-domain-tree :deep(.ant-tree-treenode) { min-height: 47px; align-items: center; min-width: 0; }
.mold-domain-tree :deep(.ant-tree-node-content-wrapper) { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mold-domain-tree-title { white-space: nowrap; text-overflow: ellipsis; }
.domain-tree-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 8px;
}

:deep(.ant-tree).list-tree-view {
  overflow-y: hidden;
  margin-top: 10px;
}
:deep(.ant-tree).ant-tree-directory {
  li.ant-tree-treenode-selected {
    span.ant-tree-switcher {
      color: rgba(0, 0, 0, 0.65);
    }
    span.ant-tree-node-content-wrapper.ant-tree-node-selected > span {
      color: rgba(0, 0, 0, 0.65);
      background-color: #bae7ff;
    }
    span.ant-tree-node-content-wrapper::before {
      background: #ffffff;
    }
  }

  .ant-tree-child-tree {
    li.ant-tree-treenode-selected {
      span.ant-tree-switcher {
        color: rgba(0, 0, 0, 0.65);
      }
      span.ant-tree-node-content-wrapper::before {
        background: #ffffff;
      }
    }
  }
}

:deep(.ant-tree-show-line) .ant-tree-switcher.ant-tree-switcher-noop {
  display: none
}

:deep(.ant-tree-node-content-wrapper-open) > span:first-child,
:deep(.ant-tree-node-content-wrapper-close) > span:first-child {
  display: none;
}

:deep(.ant-tree-icon__customize) {
  padding-right: 5px;
  background: transparent;
}

:deep(.ant-tree) {
  .ant-tree-treenode {
    .ant-tree-node-content-wrapper {
      margin-left: 0;
      margin-bottom: 2px;

      &:before {
        border-left: 1px solid #d9d9d9;
        content: " ";
        height: 100%;
        height: calc(100% - 15px);
        left: 12px;
        margin: 22px 0 0;
        position: absolute;
        width: 1px;
        top: 0;
      }
    }

    &.ant-tree-treenode-switcher-close, &.ant-tree-treenode-switcher-open, &.ant-tree-treenode-leaf-last {
      .ant-tree-node-content-wrapper:before {
        display: none;
      }
    }
  }
}

:deep(.ant-tree-show-line) .ant-tree-indent-unit:before {
  bottom: -2px;
  top: -5px;
}

</style>
