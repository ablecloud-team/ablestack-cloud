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
  <teleport to="body">
    <div
      ref="menu"
      class="resource-context-menu"
      :style="menuStyle"
      role="menu"
      @click.stop
      @contextmenu.stop.prevent>
      <ActionButton
        :actions="actions"
        :resource="resource"
        :dataView="true"
        :selectedRowKeys="selectedRowKeys"
        :selectedItems="selectedItems"
        :show-resource-title="true"
        :titleOverride="titleOverride"
        size="default"
        @exec-action="$emit('exec-action', $event)" />
    </div>
  </teleport>
</template>

<script>
import ActionButton from '@/components/view/ActionButton'

const VIEWPORT_PADDING = 8

export default {
  name: 'ResourceContextMenu',
  components: { ActionButton },
  props: {
    actions: { type: Array, default: () => [] },
    resource: { type: Object, default: () => ({}) },
    position: { type: Object, default: () => ({ x: 0, y: 0 }) },
    selectedRowKeys: { type: Array, default: () => [] },
    selectedItems: { type: Array, default: () => [] },
    titleOverride: { type: String, default: '' }
  },
  emits: ['close', 'exec-action'],
  data () {
    return { resolvedPosition: { ...this.position } }
  },
  computed: {
    menuStyle () {
      return {
        top: `${this.resolvedPosition.y}px`,
        left: `${this.resolvedPosition.x}px`
      }
    }
  },
  mounted () {
    document.addEventListener('pointerdown', this.handleDocumentPointerDown, true)
    document.addEventListener('keydown', this.handleKeydown)
    window.addEventListener('resize', this.close)
    window.addEventListener('scroll', this.handleWindowScroll, true)
    this.$nextTick(this.clampToViewport)
  },
  beforeUnmount () {
    document.removeEventListener('pointerdown', this.handleDocumentPointerDown, true)
    document.removeEventListener('keydown', this.handleKeydown)
    window.removeEventListener('resize', this.close)
    window.removeEventListener('scroll', this.handleWindowScroll, true)
  },
  methods: {
    close () {
      this.$emit('close')
    },
    handleDocumentPointerDown (event) {
      if (!this.$refs.menu?.contains(event.target)) {
        this.close()
      }
    },
    handleKeydown (event) {
      if (event.key === 'Escape') {
        this.close()
      }
    },
    handleWindowScroll (event) {
      if (this.$refs.menu?.contains(event.target)) {
        return
      }
      this.close()
    },
    clampToViewport () {
      const menu = this.$refs.menu
      if (!menu) return
      const rect = menu.getBoundingClientRect()
      this.resolvedPosition = {
        x: Math.max(VIEWPORT_PADDING, Math.min(this.position.x, window.innerWidth - rect.width - VIEWPORT_PADDING)),
        y: Math.max(VIEWPORT_PADDING, Math.min(this.position.y, window.innerHeight - rect.height - VIEWPORT_PADDING))
      }
    }
  }
}
</script>
