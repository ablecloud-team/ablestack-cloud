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
  <a-layout-sider
    :class="['sider', isDesktop() ? null : 'shadow', theme, fixSiderbar ? 'ant-fixed-sidemenu' : null ]"
    width="256px"
    collapsible
    v-model:collapsed="isCollapsed"
    :trigger="null">
    <logo
      :collapsed="collapsed"/>
    <s-menu
      :collapsed="isCollapsed"
      :menu="menus"
      :theme="theme"
      :mode="mode"
      @select="onSelect"></s-menu>
  </a-layout-sider>

</template>

<script>
import Logo from '../header/Logo'
import SMenu from './index'
import { mixin, mixinDevice } from '@/utils/mixin.js'

export default {
  name: 'SideMenu',
  components: { Logo, SMenu },
  mixins: [mixin, mixinDevice],
  props: {
    mode: {
      type: String,
      required: false,
      default: 'inline'
    },
    theme: {
      type: String,
      required: false,
      default: 'dark'
    },
    collapsible: {
      type: Boolean,
      required: false,
      default: false
    },
    collapsed: {
      type: Boolean,
      required: false,
      default: false
    },
    menus: {
      type: Array,
      required: true
    }
  },
  computed: {
    isCollapsed () {
      return this.collapsed
    }
  },
  methods: {
    onSelect (obj) {
      this.$emit('menuSelect', obj)
    }
  }
}
</script>

<style lang="less" scoped>
.sider {
  box-shadow: 2px 0 6px rgba(0, 21, 41, .35);
  position: relative;
  z-index: 10;
  height: auto;

  /* (원래 동작 유지) 비고정 상태: 기본은 숨기고 hover 시 스크롤 */
  :deep(.ant-layout-sider-children) {
    overflow-y: hidden;
    &:hover { overflow-y: auto; }
  }

  :deep(.ant-menu-vertical) .ant-menu-item {
    margin-top: 0px;
    margin-bottom: 0px;
  }

  :deep(.ant-menu-inline) .ant-menu-item:not(:last-child) { margin-bottom: 0px; }
  :deep(.ant-menu-inline) .ant-menu-item { margin-top: 0px; }

  /* ✅ 고정 사이드바: 배너 높이만큼 아래서 시작 + 하단까지 채움 */
  &.ant-fixed-sidemenu {
    position: fixed;
    top: var(--affixTopHeader, 0px); /* [추가] 배너 높이 반영 */
    bottom: 0;                       /* [추가] 하단 고정 */
    height: auto;                    /* [변경] 100% → auto (top/bottom로 계산) */

    /* ✅ 고정 상태에서는 내부 컨테이너가 자체 스크롤을 가짐 */
    :deep(.ant-layout-sider-children) {
      height: calc(100vh - var(--affixTopHeader, 0px)); /* 배너만큼 뺀 가시 높이 */
      min-height: 0;
      overflow-y: auto;               /* 항상 스크롤 가능 */
      overscroll-behavior: contain;   /* 스크롤 튐 방지 */
    }
  }

  &.light {
    box-shadow: 2px 0px 8px 0px rgba(29, 35, 41, 0.05);
    .ant-menu-light { border-right-color: transparent; }
  }

  &.dark { .ant-menu-dark { border-right-color: transparent; } }
}
</style>
