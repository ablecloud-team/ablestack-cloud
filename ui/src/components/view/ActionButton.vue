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
    <ResourceActionMenu
      v-if="dataView"
      :entries="dataViewMenuEntries"
      :title="displayTitle"
      :show-resource-title="showResourceTitle"
      @execute="executeDataViewEntry" />
    <template v-else>
    <a-tooltip
      v-for="(action, actionIndex) in actions"
      :key="actionIndex"
      arrowPointAtCenter
      placement="bottomRight">
      <template v-if="getActionTooltip(action)" #title>
        {{ getActionTooltip(action) }}
      </template>
      <template v-else-if="!dataView && action.hoverLabel" #title>
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
        :disabled="isActionDisabled(action)" >
        <a-button
          :disabled="isActionDisabled(action)"
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
        :disabled="isActionDisabled(action)"
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
    </template>
  </span>
</template>

<script>
import { api } from '@/api'
import ResourceActionMenu from '@/components/view/ResourceActionMenu'
import { isDangerAction, resolveActionMenuGroup } from '@/utils/actionMenu'

export default {
  name: 'ActionButton',
  components: { ResourceActionMenu },
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
    routeName () {
      this.updateWallLinkUrl()
    }
  },
  computed: {
    routeName () {
      return this.$route?.meta?.name || this.$route?.name || ''
    },
    dataViewMenuEntries () {
      const entries = []
      if (this.showConsoleButtons) {
        entries.push(this.createBuiltInMenuEntry('console', 'label.view.console', 'CodeOutlined', {
          disabled: this.consoleButtonDisabled,
          handler: () => this.openConsole(false)
        }))
        entries.push(this.createBuiltInMenuEntry('copy-console-url', 'label.copy.consoleurl', 'CopyOutlined', {
          disabled: this.consoleButtonDisabled,
          handler: () => this.openConsole(true)
        }))
      }
      if (this.showWorksButton) {
        entries.push(this.createExternalLinkEntry('works', 'label.works.portal.url', 'LaptopOutlined', this.worksUrl))
      }
      if (this.wallLinkReady) {
        entries.push(this.createExternalLinkEntry('wall', `label.wall.portal.${this.routeName}.url`, 'AreaChartOutlined', this.wallLinkUrl))
      }
      if (this.showGenieButton) {
        entries.push(this.createExternalLinkEntry('genie', 'label.genie.portal.url', 'LaptopOutlined', this.genieUrl))
      }
      if (this.showOobmButton) {
        entries.push(this.createExternalLinkEntry('oobm', 'label.oobm.portal.url', 'LaptopOutlined', this.oobmUrl, this.oobmButtonDisabled))
      }
      if (this.showCubeButton) {
        entries.push(this.createExternalLinkEntry('cube', 'label.cube.portal.url', 'BankOutlined', this.cubeUrl))
      }

      this.actions.forEach((action, index) => {
        if (!this.shouldShowDataViewAction(action)) {
          return
        }
        entries.push({
          key: `action-${action.api || action.label}-${index}`,
          label: action.label,
          icon: action.icon,
          group: resolveActionMenuGroup(action),
          danger: isDangerAction(action),
          disabled: this.isActionDisabled(action),
          tooltip: this.getActionTooltip(action),
          badge: action.showBadge && this.actionBadge[action.api] ? this.actionBadge[action.api].badgeNum : 0,
          action
        })
      })
      return entries
    },
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
      return hasApis && ['vm', 'systemvm', 'router', 'ilbvm', 'vnfapp'].includes(this.routeName)
    },
    consoleButtonDisabled () {
      if (!this.resource) {
        return true
      }
      return ['Stopped', 'Error', 'Destroyed'].includes(this.resource.state) || this.resource.hostcontrolstate === 'Offline'
    },
    showWorksButton () {
      return this.resource && this.resource.id && this.resource.worksvmip &&
        this.routeName === 'desktopcluster' &&
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
      return this.resource && this.resource.id && ['vm', 'host', 'cluster'].includes(this.routeName)
    },
    showGenieButton () {
      return this.resource && this.resource.id &&
        this.routeName === 'automationcontroller' &&
        ('listAutomationController' in this.$store.getters.apis)
    },
    genieUrl () {
      if (!this.showGenieButton) {
        return ''
      }
      return `http://${this.resource.automationcontrollerpublicip}:80`
    },
    showOobmButton () {
      return this.resource && this.resource.id && this.routeName === 'host'
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
      return this.resource && this.resource.id && this.routeName === 'host'
    },
    cubeUrl () {
      if (!this.showCubeButton) {
        return ''
      }
      return `https://${this.resource.ipaddress}:9090`
    }
  },
  methods: {
    createBuiltInMenuEntry (key, label, icon, options = {}) {
      return {
        key: `built-in-${key}`,
        label,
        icon,
        group: 'ACCESS',
        danger: false,
        disabled: Boolean(options.disabled),
        tooltip: options.tooltip || '',
        handler: options.handler
      }
    },
    createExternalLinkEntry (key, label, icon, url, disabled = false) {
      return this.createBuiltInMenuEntry(key, label, icon, {
        disabled,
        handler: () => window.open(url, '_blank', 'noopener')
      })
    },
    shouldShowDataViewAction (action) {
      if (!(action.api in this.$store.getters.apis)) {
        return false
      }
      const isGroupSelection = action.groupAction && this.selectedRowKeys.length > 1
      if (!action.dataView && !isGroupSelection) {
        return false
      }
      if (isGroupSelection && 'groupShow' in action) {
        return action.groupShow(this.selectedItems, this.$store.getters)
      }
      return 'show' in action ? action.show(this.resource, this.$store.getters) : true
    },
    executeDataViewEntry (entry) {
      if (entry.handler) {
        entry.handler()
        return
      }
      if (entry.action) {
        this.execAction(entry.action)
      }
    },
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
      if (this.isActionDisabled(action)) {
        return
      }
      action.resource = this.resource
      if (action.docHelp) {
        action.docHelp = this.$applyDocHelpMappings(action.docHelp)
      }
      this.$emit('exec-action', action)
    },
    isActionDisabled (action) {
      return 'disabled' in action ? action.disabled(this.resource, this.$store.getters, this.selectedItems) : false
    },
    getActionTooltip (action) {
      if (!('tooltip' in action)) {
        return ''
      }
      const tooltip = action.tooltip(this.resource, this.$store.getters, this.selectedItems)
      return tooltip ? this.$t(tooltip) : ''
    },
    openConsole (copyUrlToClipboard) {
      if (!this.resource || !this.resource.id) {
        return
      }

      const externalUrl = this.resource?.details?.['External:console_url']
      if (externalUrl) {
        if (copyUrlToClipboard) {
          this.$copyText(externalUrl)
          this.$message.success({ content: this.$t('label.copied.clipboard') })
        } else {
          window.open(externalUrl, '_blank')
        }
        return
      }

      const params = { virtualmachineid: this.resource.id }
      api('createConsoleEndpoint', params).then(json => {
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
      api('listConfigurations', { keyword: 'monitoring.wall.portal' }).then(json => {
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
        if (this.routeName === 'vm') {
          const path = getValue('monitoring.wall.portal.vm.uri') || ''
          finalUrl = `${baseUrl}${path}&var-vm_uuid=${this.resource.id}`
        } else if (this.routeName === 'host') {
          const path = getValue('monitoring.wall.portal.host.uri') || ''
          finalUrl = `${baseUrl}${path}&var-host=${this.resource.ipaddress}`
        } else if (this.routeName === 'cluster') {
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
            api(action.api, action.param).then(json => {
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

:deep(.button-action-badge) .ant-badge-count {
  right: 10px;
  z-index: 8;
}
</style>
