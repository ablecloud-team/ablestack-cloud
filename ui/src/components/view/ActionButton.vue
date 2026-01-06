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
  <span :class="['row-action-button', { 'row-action-button--dataview': dataView }]">
    <template v-if="dataView">
      <div
        v-if="showResourceTitle && displayTitle"
        class="action-button-title">
        {{ displayTitle }}
      </div>
      <a-tooltip arrowPointAtCenter placement="bottomRight" v-if="showConsoleButtons">
        <template v-if="!dataView" #title>
          {{ $t('label.view.console') }}
        </template>
        <a-button
          class="action-button-item action-button-item--dataview"
          :disabled="consoleButtonDisabled"
          type="text"
          @click="openConsole(false)">
          <code-outlined class="action-button-item__icon" />
          <span class="action-button-item__label">
            {{ $t('label.view.console') }}
          </span>
        </a-button>
      </a-tooltip>
      <a-tooltip arrowPointAtCenter placement="bottomRight" v-if="showConsoleButtons">
        <template v-if="!dataView" #title>
          {{ $t('label.copy.consoleurl') }}
        </template>
        <a-button
          class="action-button-item action-button-item--dataview"
          :disabled="consoleButtonDisabled"
          type="text"
          @click="openConsole(true)">
          <copy-outlined class="action-button-item__icon" />
          <span class="action-button-item__label">
            {{ $t('label.copy.consoleurl') }}
          </span>
        </a-button>
      </a-tooltip>
      <a-tooltip arrowPointAtCenter placement="bottomRight" v-if="showWorksButton">
        <template v-if="!dataView" #title>
          {{ $t('label.works.portal.url') }}
        </template>
        <a :href="worksUrl" target="_blank">
          <a-button class="action-button-item action-button-item--dataview" type="text">
            <LaptopOutlined class="action-button-item__icon" />
            <span class="action-button-item__label">
              {{ $t('label.works.portal.url') }}
            </span>
          </a-button>
        </a>
      </a-tooltip>
      <a-tooltip arrowPointAtCenter placement="bottomRight" v-if="wallLinkReady">
        <template v-if="!dataView" #title>
          {{ $t('label.wall.portal.' + $route.meta.name + '.url') }}
        </template>
        <a :href="wallLinkUrl" target="_blank">
          <a-button class="action-button-item action-button-item--dataview" type="text">
            <AreaChartOutlined class="action-button-item__icon" />
            <span class="action-button-item__label">
              {{ $t('label.wall.portal.' + $route.meta.name + '.url') }}
            </span>
          </a-button>
        </a>
      </a-tooltip>
      <a-tooltip arrowPointAtCenter placement="bottomRight" v-if="showGenieButton">
        <template v-if="!dataView" #title>
          {{ $t('label.genie.portal.url') }}
        </template>
        <a :href="genieUrl" target="_blank">
          <a-button class="action-button-item action-button-item--dataview" type="text">
            <LaptopOutlined class="action-button-item__icon" />
            <span class="action-button-item__label">
              {{ $t('label.genie.portal.url') }}
            </span>
          </a-button>
        </a>
      </a-tooltip>
      <a-tooltip arrowPointAtCenter placement="bottomRight" v-if="showOobmButton">
        <template v-if="!dataView" #title>
          {{ $t('label.oobm.portal.url') }}
        </template>
        <a :href="oobmUrl" target="_blank">
          <a-button
            class="action-button-item action-button-item--dataview"
            :disabled="oobmButtonDisabled"
            type="text">
            <LaptopOutlined class="action-button-item__icon" />
            <span class="action-button-item__label">
              {{ $t('label.oobm.portal.url') }}
            </span>
          </a-button>
        </a>
      </a-tooltip>
      <a-tooltip arrowPointAtCenter placement="bottomRight" v-if="showCubeButton">
        <template v-if="!dataView" #title>
          {{ $t('label.cube.portal.url') }}
        </template>
        <a :href="cubeUrl" target="_blank">
          <a-button class="action-button-item action-button-item--dataview" type="text">
            <BankOutlined class="action-button-item__icon" />
            <span class="action-button-item__label">
              {{ $t('label.cube.portal.url') }}
            </span>
          </a-button>
        </a>
      </a-tooltip>
    </template>
    <a-tooltip
      v-for="(action, actionIndex) in actions"
      :key="actionIndex"
      arrowPointAtCenter
      placement="bottomRight">
      <template v-if="!dataView && action.hoverLabel" #title>
        {{ $t(action.hoverLabel) }}
      </template>
      <template v-else-if="!dataView" #title>
        {{ $t(action.label) }}
      </template>
      <a-badge
        class="button-action-badge"
        :overflowCount="9"
        :count="actionBadge[action.api] ? actionBadge[action.api].badgeNum : 0"
        v-if="action.api in $store.getters.apis &&
          action.showBadge && (
            (!dataView && ((action.listView && ('show' in action ? action.show(resource, $store.getters) : true)) || (action.groupAction && selectedRowKeys.length > 0 && ('groupShow' in action ? action.groupShow(selectedItems, $store.getters) : true)))) ||
            (dataView && action.dataView && ('show' in action ? action.show(resource, $store.getters) : true))
          )"
        :disabled="'disabled' in action ? action.disabled(resource, $store.getters) : false" >
        <a-button
          :type="dataView ? 'text' : (primaryIconList.includes(action.icon) ? 'primary' : 'default')"
          :shape="dataView ? null : (['PlusOutlined', 'plus-outlined'].includes(action.icon) ? 'round' : 'circle')"
          :danger="dangerIconList.includes(action.icon)"
          :style="dataView ? {} : { marginLeft: '5px' }"
          :class="['action-button-item', { 'action-button-item--dataview': dataView }]"
          :size="size"
          @click="execAction(action)">
          <render-icon v-if="(typeof action.icon === 'string')" :icon="action.icon" class="action-button-item__icon" />
          <font-awesome-icon v-else :icon="action.icon" class="action-button-item__icon" />
          <span v-if="dataView" class="action-button-item__label">
            {{ $t(action.label) }}
          </span>
          <span v-else-if="['PlusOutlined', 'plus-outlined'].includes(action.icon)">
            {{ $t(action.label) }}
          </span>
        </a-button>
      </a-badge>
      <a-button
        v-if="action.api in $store.getters.apis &&
          !action.showBadge && (
            (!dataView && ((action.listView && ('show' in action ? action.show(resource, $store.getters) : true)) || (action.groupAction && selectedRowKeys.length > 0 && ('groupShow' in action ? action.groupShow(selectedItems, $store.getters) : true)))) ||
            (dataView && action.dataView && ('show' in action ? action.show(resource, $store.getters) : true))
          )"
        :disabled="'disabled' in action ? action.disabled(resource, $store.getters) : false"
        :type="dataView ? 'text' : (primaryIconList.includes(action.icon) ? 'primary' : 'default')"
        :danger="dangerIconList.includes(action.icon)"
        :shape="dataView ? null : (['PlusOutlined', 'plus-outlined', 'UserAddOutlined', 'user-add-outlined'].includes(action.icon) ? 'round' : 'circle')"
        :style="dataView ? {} : { marginLeft: '5px' }"
        :class="['action-button-item', { 'action-button-item--dataview': dataView }]"
        :size="size"
        @click="execAction(action)">
        <render-icon v-if="(typeof action.icon === 'string')" :icon="action.icon" class="action-button-item__icon" />
        <font-awesome-icon v-else :icon="action.icon" class="action-button-item__icon" />
        <span v-if="dataView" class="action-button-item__label">
          {{ $t(action.label) }}
        </span>
        <span v-else-if="['PlusOutlined', 'plus-outlined', 'UserAddOutlined', 'user-add-outlined'].includes(action.icon)">
          {{ $t(action.label) }}
        </span>
      </a-button>
    </a-tooltip>
  </span>
</template>

<script>
import { postAPI } from '@/api'
import { getAPI } from '@/api'

export default {
  name: 'ActionButton',
  data () {
    return {
      actionBadge: {},
      wallLinkUrl: '',
      wallLinkReady: false
    }
  },
  created () {
    this.onResourceChange(this.resource)
  },
  props: {
    actions: {
      type: Array,
      default () {
        return []
      }
    },
    resource: {
      type: Object,
      default () {
        return {}
      }
    },
    dataView: {
      type: Boolean,
      default: false
    },
    selectedRowKeys: {
      type: Array,
      default () {
        return []
      }
    },
    selectedItems: {
      type: Array,
      default () {
        return []
      }
    },
    loading: {
      type: Boolean,
      default: false
    },
    size: {
      type: String,
      default: 'default'
    },
    showResourceTitle: {
      type: Boolean,
      default: false
    },
    titleOverride: {
      type: String,
      default: ''
    }
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem, oldItem) {
        this.onResourceChange(newItem)
      }
    },
    '$route.meta.name' () {
      this.updateWallLinkUrl()
    }
  },
  computed: {
    primaryIconList () {
      return ['PlusOutlined', 'plus-outlined', 'DeleteOutlined', 'delete-outlined', 'UsergroupDeleteOutlined', 'usergroup-delete-outlined']
    },
    dangerIconList () {
      return ['DeleteOutlined', 'delete-outlined', 'UsergroupDeleteOutlined', 'usergroup-delete-outlined']
    },
    resourceDisplayName () {
      if (!this.resource) {
        return ''
      }
      return this.resource.displayname || this.resource.name || this.resource.hostname || this.resource.vmname || this.resource.annotation || this.resource.hypervisor || this.resource.type || this.resource.username || this.resource.ipaddress || this.resource.uuid || this.resource.id || ''
    },
    displayTitle () {
      return this.titleOverride || this.resourceDisplayName
    },
    showConsoleButtons () {
      if (!this.resource || !this.resource.id) {
        return false
      }
      if (this.selectedRowKeys && this.selectedRowKeys.length > 1) {
        return false
      }
      const requiredApis = ['listVirtualMachines', 'createConsoleEndpoint']
      const hasApis = requiredApis.every(apiName => apiName in this.$store.getters.apis)
      return hasApis && ['vm', 'systemvm', 'router', 'ilbvm', 'vnfapp'].includes(this.$route.meta.name)
    },
    consoleButtonDisabled () {
      if (!this.resource) {
        return true
      }
      return ['Stopped', 'Error', 'Destroyed'].includes(this.resource.state) || this.resource.hostcontrolstate === 'Offline'
    },
    showWorksButton () {
      return this.resource && this.resource.id && this.resource.worksvmip &&
        this.$route.meta.name === 'desktopcluster' &&
        ('listDesktopClusters' in this.$store.getters.apis)
    },
    worksUrl () {
      if (!this.showWorksButton) {
        return ''
      }
      return `http://${this.resource.worksvmip}:${this.$store.getters.features.desktopworksportalport}`
    },
    shouldShowWallLink () {
      if (this.selectedRowKeys && this.selectedRowKeys.length > 1) {
        return false
      }
      return this.resource && this.resource.id && ['vm', 'host', 'cluster'].includes(this.$route.meta.name)
    },
    showGenieButton () {
      return this.resource && this.resource.id &&
        this.$route.meta.name === 'automationcontroller' &&
        ('listAutomationController' in this.$store.getters.apis)
    },
    genieUrl () {
      if (!this.showGenieButton) {
        return ''
      }
      return `http://${this.resource.automationcontrollerpublicip}:80`
    },
    showOobmButton () {
      return this.resource && this.resource.id && this.$route.meta.name === 'host'
    },
    oobmButtonDisabled () {
      if (!this.showOobmButton) {
        return true
      }
      return this.resource.details?.manageconsoleport == null
    },
    oobmUrl () {
      if (!this.showOobmButton || this.oobmButtonDisabled) {
        return '#'
      }
      const protocol = this.resource.details?.manageconsoleprotocol || 'http'
      const address = this.resource.outofbandmanagement?.address || ''
      const port = this.resource.details?.manageconsoleport
      return `${protocol}://${address}:${port}`
    },
    showCubeButton () {
      return this.resource && this.resource.id && this.$route.meta.name === 'host'
    },
    cubeUrl () {
      if (!this.showCubeButton) {
        return ''
      }
      return `https://${this.resource.ipaddress}:9090`
    }
  },
  methods: {
    onResourceChange (item) {
      if (!item || !item.id) {
        this.wallLinkReady = false
        this.wallLinkUrl = ''
        return
      }
      this.handleShowBadge()
      this.updateWallLinkUrl()
    },
    execAction (action) {
      action.resource = this.resource
      if (action.docHelp) {
        action.docHelp = this.$applyDocHelpMappings(action.docHelp)
      }
      this.$emit('exec-action', action)
    },
    openConsole (copyUrlToClipboard) {
      if (!this.resource || !this.resource.id) {
        return
      }
      const params = { virtualmachineid: this.resource.id }
      getAPI('createConsoleEndpoint', params).then(json => {
        const response = json?.createconsoleendpointresponse?.consoleendpoint
        const url = response?.url || '#/exception/404'
        if (response?.success) {
          if (copyUrlToClipboard) {
            this.$copyText(url)
            this.$message.success({
              content: this.$t('label.copied.clipboard')
            })
          } else {
            window.open(url, '_blank')
          }
        } else {
          this.$notification.error({
            message: this.$t('error.execute.api.failed') + ' ' + 'createConsoleEndpoint',
            description: response?.details
          })
        }
      }).catch(error => {
        this.$notifyError(error)
      })
    },
    updateWallLinkUrl () {
      if (!this.shouldShowWallLink) {
        this.wallLinkReady = false
        this.wallLinkUrl = ''
        return
      }
      getAPI('listConfigurations', { keyword: 'monitoring.wall.portal' }).then(json => {
        const items = json?.listconfigurationsresponse?.configuration || []
        const getValue = (name) => {
          const config = items.find(item => item.name === name)
          return config ? config.value : ''
        }
        const protocol = getValue('monitoring.wall.portal.protocol') || 'http'
        const port = getValue('monitoring.wall.portal.port')
        let domain = getValue('monitoring.wall.portal.domain')
        if (!domain) {
          domain = this.$store.getters.features.host
        }
        let baseUrl = `${protocol}://${domain}`
        if (port) {
          baseUrl += `:${port}`
        }

        let finalUrl = ''
        if (this.$route.meta.name === 'vm') {
          const path = getValue('monitoring.wall.portal.vm.uri') || ''
          finalUrl = `${baseUrl}${path}&var-vm_uuid=${this.resource.id}`
        } else if (this.$route.meta.name === 'host') {
          const path = getValue('monitoring.wall.portal.host.uri') || ''
          finalUrl = `${baseUrl}${path}&var-host=${this.resource.ipaddress}`
        } else if (this.$route.meta.name === 'cluster') {
          const path = getValue('monitoring.wall.portal.cluster.uri') || ''
          finalUrl = `${baseUrl}${path}`
        }

        this.wallLinkUrl = finalUrl
        this.wallLinkReady = !!finalUrl
      }).catch(() => {
        this.wallLinkReady = false
      })
    },
    handleShowBadge () {
      this.actionBadge = {}
      const arrAsync = []
      const actionBadge = this.actions.filter(action => action.showBadge === true)

      if (actionBadge && actionBadge.length > 0) {
        const dataLength = actionBadge.length

        for (let i = 0; i < dataLength; i++) {
          const action = actionBadge[i]

          arrAsync.push(new Promise((resolve, reject) => {
            postAPI(action.api, action.param).then(json => {
              let responseJsonName
              const response = {}

              response.api = action.api
              response.count = 0

              for (const key in json) {
                if (key.includes('response')) {
                  responseJsonName = key
                  break
                }
              }

              if (json[responseJsonName] && json[responseJsonName].count && json[responseJsonName].count > 0) {
                response.count = json[responseJsonName].count
              }

              resolve(response)
            }).catch(error => {
              reject(error)
            })
          }))
        }

        Promise.all(arrAsync).then(response => {
          for (let j = 0; j < response.length; j++) {
            this.actionBadge[response[j].api] = {}
            this.actionBadge[response[j].api].badgeNum = response[j].count
          }
        }).catch(() => {})
      }
    }
  }
}
</script>

<style scoped >
.button-action-badge {
  margin-left: 5px;
}

:deep(.row-action-button--dataview .button-action-badge) {
  margin-left: 0;
}

:deep(.button-action-badge) .ant-badge-count {
  right: 10px;
  z-index: 8;
}

  .row-action-button--dataview {
    display: flex;
    flex-direction: column;
    width: auto;
    max-width: none;
  }

.row-action-button--dataview :deep(.ant-tooltip) {
  width: 100%;
}

.action-button-title {
  font-weight: 600;
  margin-bottom: 6px;
  padding: 4px 12px;
  color: #262626;
  border-bottom: 1px solid #f0f0f0;
}

:deep(.action-button-item--dataview) {
  display: flex !important;
  align-items: center;
  justify-content: flex-start;
  width: 100%;
  margin-left: 0 !important;
  border: none;
}

:deep(.action-button-item--dataview.ant-btn-text) {
  padding-left: 12px;
  padding-right: 12px;
}

:deep(.action-button-item--dataview:not(.ant-btn-disabled):hover),
:deep(.action-button-item--dataview:not(.ant-btn-disabled):focus) {
  background-color: #e6f4ff;
  border-color: transparent;
  color: #0958d9;
}

:deep(.action-button-item__icon) {
  font-size: 16px;
}

:deep(.action-button-item__label) {
  margin-left: 8px;
  font-weight: 500;
}

:deep(.action-button-item--dataview:not(.ant-btn-disabled):hover .action-button-item__label),
:deep(.action-button-item--dataview:not(.ant-btn-disabled):hover .action-button-item__icon),
:deep(.action-button-item--dataview:not(.ant-btn-disabled):focus .action-button-item__label),
:deep(.action-button-item--dataview:not(.ant-btn-disabled):focus .action-button-item__icon) {
  color: #0958d9;
}
</style>
