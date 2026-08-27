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
  <div>
    <div :class="['mask', visible ? 'open' : 'close']" @click="close"></div>
    <div :class="['drawer', placement, visible ? 'open' : 'close']">
      <div ref="drawer" class="content">
        <header v-if="title || closable" class="drawer-header">
          <span class="drawer-title">{{ title }}</span>
          <a-button
            v-if="closable"
            type="text"
            :aria-label="$t('label.close')"
            @click.stop="close">
            <close-outlined />
          </a-button>
        </header>
        <div class="drawer-body">
          <slot name="drawer"></slot>
        </div>
      </div>

      <div
        v-if="showHandler && $slots.handler"
        :class="['handler-container', placement, visible ? 'open' : 'close']"
        ref="handler"
        @click="toggle">
        <slot v-if="$slots.handler" name="handler"></slot>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'Drawer',
  data () {
    return {
    }
  },
  model: {
    prop: 'visible',
    event: 'change'
  },
  props: {
    visible: {
      type: Boolean,
      required: false,
      default: false
    },
    placement: {
      type: String,
      required: false,
      default: 'left'
    },
    showHandler: {
      type: Boolean,
      required: false,
      default: true
    },
    closable: {
      type: Boolean,
      required: false,
      default: true
    },
    title: {
      type: String,
      required: false,
      default: ''
    }
  },
  inject: ['parentToggleSetting'],
  methods: {
    open () {
      this.parentToggleSetting(true)
    },
    close () {
      this.parentToggleSetting(false)
    },
    toggle () {
      this.parentToggleSetting(!this.visible)
    }
  }
}
</script>

<style lang="less" scoped>
.mask {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  top: 0;
  transition: all 0.5s;
  z-index: 100;
  background-color: #000000;
  opacity: 0.2;

  &.open{
    display: inline-block;
  }

  &.close{
    display: none;
  }
}

.drawer{
  position: fixed;
  transition: all 0.5s;
  height: 100vh;
  z-index: 100;

  &.left{
    left: 0;

    &.close{
      transform: translateX(-100%);
    }
  }

  &.right{
    right: 0;

    &.close{
      transform: translateX(100%);
    }
  }
}

.content {
  display: inline-block;
  height: 100vh;
  overflow: hidden;
  width: 300px;
  color: var(--ui-text-primary, rgba(0, 0, 0, 0.85));
  background-color: var(--ui-bg-surface, #fff);
}

.drawer-header {
  height: 52px;
  padding: 0 12px 0 20px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--ui-border, #f0f0f0);
}

.drawer-title {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  color: var(--ui-text-primary, rgba(0, 0, 0, 0.85));
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-header .ant-btn {
  flex: 0 0 auto;
  color: var(--ui-text-muted, rgba(0, 0, 0, 0.45));
}

.drawer-body {
  height: calc(100vh - 52px);
  overflow-y: auto;
}

.handler-container {
  position: absolute;
  display: inline-block;
  text-align: center;
  transition: all 0.5s;
  cursor: pointer;
  top: calc(100% - 45px);
  z-index: 100;

  &.left{
    right: -40px;

    .handler{
      border-radius: 0 5px 5px 0;
    }

    :deep(button) {
      border-top-left-radius: 0;
      border-bottom-left-radius: 0;
      padding-left: 10px;
      padding-right: 12px;
    }
  }

  &.right{
    left: -40px;

    .handler {
      border-radius: 5px 0 0 5px;
    }

    :deep(button) {
      border-top-right-radius: 0;
      border-bottom-right-radius: 0;
      padding-left: 12px;
      padding-right: 10px;
    }
  }
}
</style>
