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
    <external-link class="action"/>
    <translation-menu class="action"/>
    <header-notice class="action"/>
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
import { api } from '@/api'
import ExternalLink from './ExternalLink'
import HeaderNotice from './HeaderNotice'
import TranslationMenu from './TranslationMenu'
import { mapActions, mapGetters } from 'vuex'
import ResourceIcon from '@/components/view/ResourceIcon'
import eventBus from '@/config/eventBus'
import { SERVER_MANAGER } from '@/store/mutation-types'

export default {
  name: 'UserMenu',
  components: {
    ExternalLink,
    TranslationMenu,
    HeaderNotice,
    ResourceIcon
  },
  data () {
    return {
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
    this.getIcon()
    this.fetchConfigurationSwitch()
    eventBus.on('refresh-header', () => {
      this.getIcon()
    })
    this.$store.watch(
      (state, getters) => getters.countNotify,
      (newValue, oldValue) => {
        this.countNotify = newValue
      }
    )
    this.faviconSetting()
  },
  watch: {
    image () {
      this.getIcon()
    },
    faviconState () {
      this.faviconSetting()
    }
  },
  computed: {
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
    fetchResourceIcon (id) {
      return new Promise((resolve, reject) => {
        api('listUsers', {
          id: id,
          showicon: true
        }).then(json => {
          const response = json.listusersresponse.user || []
          if (response?.[0]) {
            this.image = response[0]?.icon?.base64image || ''
            resolve(this.image)
          }
        }).catch(error => {
          reject(error)
        })
      })
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
        case 'document':
          window.open(this.$config.docBase, '_blank')
          break
        case 'logout':
          this.handleLogout()
          break
      }
    },
    handleLogout () {
      return this.Logout({}).then(() => {
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
    wallPortalLink () {
      var uri = ''
      const host = this.$store.getters.features.host
      const wallPortalProtocol = this.$store.getters.features.wallportalprotocol
      const wallPortalDomain = this.$store.getters.features.wallportaldomain
      const wallPortalPort = this.$store.getters.features.wallportalport

      if (wallPortalProtocol === null || wallPortalProtocol === '') {
        uri += 'http://'
      } else {
        uri += wallPortalProtocol + '://'
      }
      if (wallPortalDomain === null || wallPortalDomain === '') {
        uri += host
      } else {
        uri += wallPortalDomain
      }
      if (typeof wallPortalPort !== 'undefined') {
        uri += ':' + wallPortalPort
      }
      uri += '/login?orgId=1'

      window.open(uri, '_blank')
    },
    async fetchConfigurationSwitch () {
      await this.fetchFaviconStateInterval()
      await this.fetchFaviconStateYellowCapacity()
      await this.fetchFaviconStateRedCapacity()
      await this.fetchHostState()
    },
    fetchFaviconStateInterval () {
      return new Promise((resolve, reject) => {
        api('listConfigurations', {
          name: 'favicon.state.interval'
        }).then(json => {
          const response = json.listconfigurationsresponse.configuration || []
          if (response?.[0]) {
            this.faviconStateInterval = json.listconfigurationsresponse.configuration[0].value * 1000
            resolve(this.faviconStateInterval)
          }
        }).catch(error => {
          reject(error)
        })
      })
    },
    fetchFaviconStateYellowCapacity () {
      return new Promise((resolve, reject) => {
        api('listConfigurations', {
          name: 'favicon.state.yellow.capacity'
        }).then(json => {
          const response = json.listconfigurationsresponse.configuration || []
          if (response?.[0]) {
            this.faviconStateYellowCapacity = json.listconfigurationsresponse.configuration[0].value
          }
          resolve(this.faviconStateYellowCapacity)
        }).catch(error => {
          reject(error)
        })
      })
    },
    fetchFaviconStateRedCapacity () {
      return new Promise((resolve, reject) => {
        api('listConfigurations', {
          name: 'favicon.state.red.capacity'
        }).then(json => {
          const response = json.listconfigurationsresponse.configuration || []
          if (response?.[0]) {
            this.faviconStateRedCapacity = json.listconfigurationsresponse.configuration[0].value
          }
          resolve(this.faviconStateRedCapacity)
        }).catch(error => {
          reject(error)
        })
      })
    },
    async fetchHostState () {
      return new Promise((resolve, reject) => {
        setInterval(() => {
          api('listHostsMetrics', { listall: true }).then(async json => {
            const hosts = json.listhostsmetricsresponse.host || []
            const totalHostCount = json.listhostsmetricsresponse.count
            let errorHostCount = 0
            hosts.forEach((host) => {
              if (host.state !== 'Up' || host.resourcestate !== 'Enabled') {
                errorHostCount = errorHostCount + 1
              }
            })
            if (errorHostCount !== 0) {
              const ratio = 1 - (errorHostCount / totalHostCount)
              if (ratio <= 0.70) {
                this.faviconState = '#FFA500'
                if (ratio <= 0.40) {
                  this.faviconState = '#FF0000'
                }
              }
            }
            resolve(this.faviconState)
          }).catch(error => {
            reject(error)
          })
        }, this.faviconStateInterval)
      })
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
