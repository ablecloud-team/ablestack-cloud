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
  <div id="userLayout" :class="['user-layout', device]">
    <a-button
      v-if="showClear"
      type="default"
      size="small"
      class="button-clear-notification"
      @click="onClearNotification">{{ $t('label.clear.notification') }}</a-button>
    <div class="user-layout-container">
      <div class="user-layout-header">
        <img
          :style="{
            width: $config.theme['@banner-width'],
            height: $config.theme['@banner-height']
          }"
          :src="logoPath"
          class="user-layout-logo"
          alt="logo">
      </div>
      <route-view></route-view>
    </div>
    <div class="user-layout-footer" v-if="$config.loginFooter">
      <label v-html="$config.loginFooter"></label>
    </div>
  </div>
</template>

<script>
import RouteView from '@/layouts/RouteView'
import { mixinDevice } from '@/utils/mixin.js'
import notification from 'ant-design-vue/es/notification'

export default {
  name: 'UserLayout',
  components: { RouteView },
  mixins: [mixinDevice],
  data () {
    return {
      showClear: false,
      logoPath: this.$config.logo
    }
  },
  watch: {
    '$store.getters.darkMode' (darkMode) {
      document.body.classList.toggle('dark-mode', darkMode)
      this.logoPath = darkMode ? this.$config.whiteLogo : this.$config.logo
    },
    '$store.getters.countNotify' (countNotify) {
      this.showClear = false
      if (countNotify && countNotify > 0) {
        this.showClear = true
      }
    }
  },
  mounted () {
    // 시스템 테마 변경되었을때 감지 후 테마 변경
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (event) => {
      this.$localStorage.set('DARK_MODE', event.matches)
      this.$store.dispatch('SetDarkMode', event.matches)
      document.body.classList.toggle('dark-mode', event.matches)
    })

    // 로컬스토리지 다크 모드 확인 후 변경
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    let isDark = this.$localStorage.get('DARK_MODE')

    this.logoPath = isDark ? this.$config.whiteLogo : this.$config.logo

    if (isDark === null) {
      isDark = prefersDark
      this.$localStorage.set('DARK_MODE', isDark)
    }

    this.$store.dispatch('SetDarkMode', isDark)
    this.$config.theme['@layout-mode'] = isDark ? 'dark' : 'light'
    document.body.classList.toggle('dark-mode', isDark)

    document.body.classList.add('userLayout')
    const countNotify = this.$store.getters.countNotify
    this.showClear = false
    if (countNotify && countNotify > 0) {
      this.showClear = true
    }
  },
  beforeUnmount () {
    document.body.classList.remove('userLayout')
    document.body.classList.remove('dark-mode')
  },
  methods: {
    onClearNotification () {
      notification.destroy()
      this.$store.commit('SET_COUNT_NOTIFY', 0)
    }
  }
}
</script>

<style lang="less" scoped>
.user-layout {
  height: 100%;

  &-container {
    padding: 3rem 0;
    width: 100%;

    @media (min-height:600px) {
      padding: 0;
      position: relative;
      top: 50%;
      transform: translateY(-50%);
      margin-top: -50px;
    }
  }

  &-logo {
    border-style: none;
    margin: 0 auto 2rem;
    display: block;

    .mobile & {
      max-width: 300px;
      margin-bottom: 1rem;
    }
  }

  &-footer {
    display: flex;
    flex-direction: column;
    position: absolute;
    bottom: 20px;
    text-align: center;
    width: 100%;

    @media (max-height: 600px) {
      position: relative;
      margin-top: 50px;
    }

    label {
      width: 368px;
      font-weight: 500;
      margin: 0 auto;
    }
  }
}
</style>
