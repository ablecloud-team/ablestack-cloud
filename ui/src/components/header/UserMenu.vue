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
  <div class="user-menu">
    <span class="action">
      <create-menu v-if="device === 'desktop'" />
    </span>
    <external-link class="action"/>
    <translation-menu class="action"/>
    <header-notice
      class="action"
      @open-activity-panel="$emit('open-activity-panel')" />
    <label class="user-menu-server-info action" v-if="$config.multipleServer">
      <database-outlined />
      {{ server.name || server.apiBase || 'Local-Server' }}
    </label>
    <a-dropdown>
      <span class="user-menu-dropdown action">
        <span v-if="image">
          <resource-icon :image="image" size="4x" style="margin-right: 5px; margin-top: -3px"/>
        </span>
        <a-avatar v-else-if="userInitials" class="user-menu-avatar avatar" size="small" :style="{ backgroundColor: $config.theme['@primary-color'], color: 'white' }">
          {{ userInitials }}
        </a-avatar>
        <a-avatar v-else class="user-menu-avatar avatar" size="small" :style="{ backgroundColor: $config.theme['@primary-color'], color: 'white' }">
          <template #icon><user-outlined /></template>
        </a-avatar>
        <span>{{ nickname() }}</span>
      </span>
      <template #overlay>
        <a-menu class="user-menu-wrapper" @click="handleClickMenu">
          <a-menu-item class="user-menu-item" key="profile">
            <UserOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name">{{ $t('label.profilename') }}</span>
          </a-menu-item>
          <a-menu-item class="user-menu-item" key="limits">
            <ControlOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name">{{ $t('label.limits') }}</span>
          </a-menu-item>
          <a-menu-item class="user-menu-item" key="timezone">
            <ClockCircleOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name" style="margin-right: 5px">{{ $t('label.use.local.timezone') }}</span>
            <a-switch :checked="$store.getters.usebrowsertimezone" />
          </a-menu-item>
          <a-menu-item
            v-if="canOpenDisplaySettings"
            class="user-menu-item"
            key="display-settings">
            <SettingOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name">{{ $t('label.theme.page.style.setting') }}</span>
          </a-menu-item>
          <a-menu-item class="user-menu-item" key="document">
            <QuestionCircleOutlined class="user-menu-item-icon" />
            <span class="user-menu-item-name">{{ $t('label.help') }}</span>
          </a-menu-item>
          <a v-if="$store.getters.userInfo.roletype === 'Admin'" @click="wallPortalLink" >
            <a-menu-item class="user-menu-item" key="1">
              <AreaChartOutlined class="user-menu-item-icon" />
              <span class="user-menu-item-name">{{ $t('label.wall.portal.url') }}</span>
            </a-menu-item>
          </a>
          <a-menu-divider/>
          <a href="javascript:;" @click="handleLogout">
            <a-menu-item class="user-menu-item" key="4">
              <LogoutOutlined class="user-menu-item-icon" />
              <span class="user-menu-item-name">{{ $t('label.logout') }}</span>
            </a-menu-item>
          </a>
        </a-menu>
      </template>
    </a-dropdown>
  </div>
</template>

<script>
import { getAPI } from '@/api'
import { discoverOptional, hasDiscoveryApi } from '@/utils/optionalDiscovery'
import CreateMenu from './CreateMenu'
import ExternalLink from './ExternalLink'
import HeaderNotice from './HeaderNotice'
import TranslationMenu from './TranslationMenu'
import { mapActions, mapGetters } from 'vuex'
import ResourceIcon from '@/components/view/ResourceIcon'
import eventBus from '@/config/eventBus'
import { SERVER_MANAGER } from '@/store/mutation-types'
import { sourceToken } from '@/utils/request'
import { applyCustomGuiTheme } from '@/utils/guiTheme'
import { isAdmin } from '@/role'

export default {
  name: 'UserMenu',
  components: {
    CreateMenu,
    ExternalLink,
    TranslationMenu,
    HeaderNotice,
    ResourceIcon
  },
  props: {
    device: {
      type: String,
      required: false,
      default: 'desktop'
    }
  },
  data () {
    return {
      disposed: false,
      hostStateTimer: null,
      settingsGeneration: 0,
      stopNotifyWatch: null,
      refreshHeader: null,
      image: '',
      userInitials: '',
      countNotify: 0,
      faviconStateInterval: 60000,
      faviconStateYellowCapacity: '0.75',
      faviconStateRedCapacity: '0.55',
      faviconState: '#008000'
    }
  },
  created () {
    this.userInitials = (this.$store.getters.userInfo.firstname.toUpperCase().charAt(0) || '') +
      (this.$store.getters.userInfo.lastname.toUpperCase().charAt(0) || '')
    this.getIcon().catch(() => {})
    this.fetchConfigurationSwitch()
    this.refreshHeader = () => { this.getIcon().catch(() => {}) }
    eventBus.on('refresh-header', this.refreshHeader)
    this.stopNotifyWatch = this.$store.watch(
      (state, getters) => getters.countNotify,
      (newValue, oldValue) => {
        this.countNotify = newValue
      }
    )
    this.faviconSetting()
  },
  beforeUnmount () {
    this.disposed = true
    this.settingsGeneration += 1
    clearInterval(this.hostStateTimer)
    if (this.stopNotifyWatch) this.stopNotifyWatch()
    eventBus.off('refresh-header', this.refreshHeader)
  },
  watch: {
    discoveryContext () { this.fetchConfigurationSwitch() },
    image () {
      this.getIcon().catch(() => {})
    },
    faviconState () {
      this.faviconSetting()
    }
  },
  computed: {
    discoveryContext () {
      return [this.$store.state.user.discoveryGeneration, this.$store.getters.apis]
    },
    canOpenDisplaySettings () {
      return isAdmin() && (process.env.NODE_ENV === 'development' || this.$config.allowSettingTheme)
    },
    server () {
      return this.$localStorage.get(SERVER_MANAGER) || this.$config.servers[0]
    }
  },
  methods: {
    ...mapActions(['Logout']),
    ...mapGetters(['nickname', 'avatar']),
    toggleUseBrowserTimezone () {
      this.$store.dispatch('SetUseBrowserTimezone', !this.$store.getters.usebrowsertimezone)
    },
    async getIcon () {
      await this.fetchResourceIcon(this.$store.getters.userInfo.id)
    },
    async fetchResourceIcon (id) {
      const generation = this.$store.state.user.discoveryGeneration
      if (this.$store.getters.avatar) {
        this.image = this.$store.getters.avatar
        return this.image
      }
      const json = await getAPI('listUsers', { id, showicon: true })
      if (this.disposed || generation !== this.$store.state.user.discoveryGeneration) return
      this.image = json.listusersresponse?.user?.[0]?.icon?.base64image || ''
      this.$store.commit('SET_AVATAR', this.image)
      return this.image
    },
    handleClickMenu (item) {
      switch (item.key) {
        case 'profile':
          this.$router.push(`/accountuser/${this.$store.getters.userInfo.id}`)
          break
        case 'limits':
          this.$router.push(`/account/${this.$store.getters.userInfo.accountid}?tab=limits`)
          break
        case 'timezone':
          this.toggleUseBrowserTimezone()
          break
        case 'display-settings':
          this.$emit('open-display-settings')
          break
        case 'document':
          window.open(this.$config.docBase, '_blank')
          break
        case 'logout':
          this.handleLogout()
          break
      }
    },
    handleLogout () {
      return this.Logout({ apiBase: this.$config.apiBase }).then(async () => {
        sourceToken.init()
        await applyCustomGuiTheme(null, null)
        this.$router.push('/user/login')
      }).catch(err => {
        this.$message.error({
          title: 'Failed to Logout',
          description: err.message
        })
      })
    },
    clearAllNotify () {
      this.$store.commit('SET_COUNT_NOTIFY', 0)
      this.$notification.destroy()
    },
    async wallPortalLink () {
      const generation = this.settingsGeneration
      const json = await discoverOptional(this.$store.getters.apis, 'listConfigurations', { keyword: 'monitoring.wall.portal' })
      if (generation !== this.settingsGeneration) return
      const items = json?.listconfigurationsresponse?.configuration || []
      const value = name => items.find(x => x.name === name)?.value
      const protocol = value('monitoring.wall.portal.protocol')
      const port = value('monitoring.wall.portal.port')
      const domain = value('monitoring.wall.portal.domain') || this.$store.getters.features.host
      if (protocol && domain && port) window.open(protocol + '://' + domain + ':' + port, '_blank', 'noopener')
    },
    async fetchConfigurationSwitch () {
      const generation = ++this.settingsGeneration
      clearInterval(this.hostStateTimer)
      this.hostStateTimer = null
      this.faviconStateInterval = 60000
      this.faviconStateYellowCapacity = '0.75'
      this.faviconStateRedCapacity = '0.55'
      this.faviconState = '#008000'
      await Promise.all([
        this.fetchFaviconStateInterval(),
        this.fetchFaviconStateYellowCapacity(),
        this.fetchFaviconStateRedCapacity()
      ])
      if (generation !== this.settingsGeneration) return
      if (!this.$store.getters.features.securityfeaturesenabled) this.fetchHostState()
    },
    async fetchFaviconConfiguration (name, field, multiplier = 1) {
      const generation = this.settingsGeneration
      const json = await discoverOptional(this.$store.getters.apis, 'listConfigurations', { name })
      if (generation !== this.settingsGeneration) return
      const value = Number(json?.listconfigurationsresponse?.configuration?.[0]?.value)
      if (Number.isFinite(value) && value > 0) this[field] = value * multiplier
      return this[field]
    },
    fetchFaviconStateInterval () {
      return this.fetchFaviconConfiguration('favicon.state.interval', 'faviconStateInterval', 1000)
    },
    fetchFaviconStateYellowCapacity () {
      return this.fetchFaviconConfiguration('favicon.state.yellow.capacity', 'faviconStateYellowCapacity')
    },
    fetchFaviconStateRedCapacity () {
      return this.fetchFaviconConfiguration('favicon.state.red.capacity', 'faviconStateRedCapacity')
    },
    fetchHostState () {
      clearInterval(this.hostStateTimer)
      if (!hasDiscoveryApi(this.$store.getters.apis, 'listHostsMetrics')) return
      const generation = this.settingsGeneration
      this.hostStateTimer = setInterval(async () => {
        if (generation !== this.settingsGeneration) return
        const json = await discoverOptional(this.$store.getters.apis, 'listHostsMetrics')
        if (generation !== this.settingsGeneration || !json) return
        const hosts = json.listhostsmetricsresponse?.host || []
        const errors = hosts.filter(host => host.state !== 'Up' || host.resourcestate !== 'Enabled').length
        const ratio = hosts.length ? 1 - errors / hosts.length : 1
        this.faviconState = ratio <= 0.40 ? '#FF0000' : ratio <= 0.70 ? '#FFA500' : '#008000'
      }, this.faviconStateInterval)
    },
    faviconSetting () {
      const faviconSize = 16
      const favicon = document.getElementById('favicon')
      const canvas = document.createElement('canvas')
      canvas.width = faviconSize
      canvas.height = faviconSize
      const context = canvas.getContext('2d')
      const img = document.createElement('img')
      img.src = favicon.href

      img.onload = () => {
        context.drawImage(img, 0, 0, 16, 16)

        context.beginPath()
        context.fillStyle = this.faviconState
        context.arc(canvas.width - faviconSize / 4, faviconSize / 4, faviconSize / 4, 0, 2 * Math.PI)
        context.fill()

        favicon.href = canvas.toDataURL('image/png')
      }
    }
  }
}
</script>

<style lang="less" scoped>
.user-menu {
  &-wrapper {
    padding: 4px 0;
  }

  &-item {
    width: auto;
  }

  &-item-name {
    user-select: none;
    margin-left: 8px;
  }

  &-item-icon i {
    min-width: 12px;
    margin-right: 8px;
  }

  &-server-info {
    .anticon {
      margin-right: 5px;
    }
  }
}
</style>
