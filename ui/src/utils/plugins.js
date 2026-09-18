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

import { clearIsoOperations } from '@/utils/vmIsoActions'
import { clearNicOperations } from '@/utils/vmNicActions'
import { clearVolumeOperations } from '@/utils/vmVolumeActions'
import { trackSnapshotJob, finishSnapshotJob, clearSnapshotJobs } from '@/utils/vmSnapshotActions'
import _ from 'lodash'
import { i18n } from '@/locales'
import { getAPI } from '@/api'
import { message, notification, Modal } from 'ant-design-vue'
import eventBus from '@/config/eventBus'
import store from '@/store'
import { createJobTracker } from '@/utils/jobTracker'
import { toLocalDate, toLocaleDate } from '@/utils/date'

function normalizePath (path) {
  if (!path) return ''
  return path.replace(/\/+$/, '')
}

function isBase64 (str) {
  try {
    const decoded = new TextDecoder().decode(Uint8Array.from(atob(str), c => c.charCodeAt(0)))
    return btoa(decoded) === str
  } catch (err) {
    return false
  }
}

export const pollJobPlugin = {
  install (app) {
    const safe = fn => { try { fn() } catch (e) { console.error('[pollJob] callback failed', e) } }
    const notifyError = config => {
      store.commit('SET_COUNT_NOTIFY', store.getters.countNotify + 1)
      notification.error({
        top: '65px',
        duration: 0,
        ...config,
        onClose: () => store.commit('SET_COUNT_NOTIFY', Math.max(0, store.getters.countNotify - 1))
      })
    }
    const tracker = createJobTracker({
      query: jobId => getAPI('queryAsyncJobResult', { jobId }, { timeout: 15000, backgroundJob: true }).then(json => json.queryasyncjobresultresponse),
      onState: (jobId, result, meta) => {
        const { options, router, originalPage, path } = meta
        finishSnapshotJob(jobId, result)
        const {
          title = '', description = '', name = '', action = null, bulkAction = false,
          showLoading = true, showSuccessMessage = true,
          successMessage = i18n.global.t('label.success'),
          errorMessage = i18n.global.t('label.error'),
          loadingMessage = `${i18n.global.t('label.loading')}...`
        } = options
        const terminal = result.jobstatus === 1 || result.jobstatus === 2
        const interrupted = Boolean(result.trackingStatus)
        // Clean up before invoking application callbacks or event listeners.
        if (terminal || interrupted) message.destroy(jobId)
        if (terminal && options.retry && options.batchKey) notification.close(options.batchKey)
        if (result.trackingStatus === 'cancelled') {
          safe(() => store.commit('SET_HEADER_NOTICES', store.getters.headerNotices.map(notice => notice.key === jobId ? { ...notice, status: 'unknown' } : notice)))
          return
        }
        const status = result.jobstatus === 1 ? 'done' : result.jobstatus === 2 ? 'failed' : interrupted ? 'unknown' : 'progress'
        const suppressFailedNotice = result.jobstatus === 2 && action?.suppressErrorNotification
        if (suppressFailedNotice) {
          safe(() => store.commit('SET_HEADER_NOTICES', store.getters.headerNotices.filter(notice => notice.key !== jobId)))
        } else {
          safe(() => store.dispatch('AddHeaderNotice', {
            key: jobId, title, description, path, status, timestamp: new Date()
          }))
        }
        if (result.jobstatus === 0) {
          if (showLoading) message.loading({ content: loadingMessage, key: jobId, duration: 0 })
          return
        }
        if (result.trackingStatus === 'retrying') return
        if (result.trackingStatus === 'unknown') {
          notification.warning({
            key: jobId,
            top: '65px',
            duration: 0,
            message: title,
            description: i18n.global.t('message.job.result.unknown'),
            onClick: () => {
              notification.close(jobId)
              app.config.globalProperties.$pollJob.call(meta.context, { ...options, retry: true })
            }
          })
          return
        }
        if (result.jobstatus === 1) {
          if (showSuccessMessage || options.retry) message.success({ content: name ? `${successMessage} - ${name}` : successMessage, key: jobId, duration: 2 })
        } else if (result.jobstatus === 2) {
          if (!action?.suppressErrorNotification) {
            if (!bulkAction) message.error({ content: errorMessage, key: jobId, duration: 1 })
            safe(() => notifyError({ key: jobId, message: action?.label ? i18n.global.t(action.label) : errorMessage, description: result.jobresult?.errortext }))
          }
        }
        safe(() => eventBus.emit('update-job-details', { jobId, resourceId: options.resourceId }))
        const samePage = normalizePath(router.currentRoute.value.path) === originalPage
        if (samePage && (options.retry || !action || !('isFetchData' in action) || action.isFetchData)) {
          safe(() => eventBus.emit('async-job-complete', action))
        }
      }
    })
    // A route change may cancel one query; retry it. A security scope change must stop tracking.
    store.watch(() => [store.state.user.token, store.getters.userInfo?.id, store.getters.project?.id].join('|'), () => { tracker.clear(); clearSnapshotJobs(); clearVolumeOperations(); clearNicOperations(); clearIsoOperations() })
    app.config.globalProperties.$pollJob = function (options) {
      if (options.retry) options = { ...tracker.metadata(options.jobId)?.options, ...options }
      const originalPage = normalizePath(options.originalPage || this.$router.currentRoute.value.path)
      options = { ...options, originalPage }
      trackSnapshotJob(options)
      const meta = { options, router: this.$router, originalPage, path: this.$route.fullPath, context: this }
      return tracker.track(options.jobId, meta, options.retry).then(result => {
        finishSnapshotJob(options.jobId, result)
        if (result.jobstatus === 1) safe(() => options.successMethod?.(result))
        else if (result.jobstatus === 2) safe(() => options.errorMethod?.(result))
        else safe(() => options.catchMethod?.(result))
        return result
      })
    }
  }
}

export const notifierPlugin = {
  install (app) {
    app.config.globalProperties.$notifyError = function (error) {
      console.log(error)
      var msg = i18n.global.t('message.request.failed')
      var desc = ''
      if (error && error.response) {
        if (error.response.status) {
          msg = `${i18n.global.t('message.request.failed')} (${error.response.status})`
        }
        if (error.response.headers?.['x-description']) {
          desc = error.response.headers['x-description']
        } else if (error.response.data) {
          const responseKey = _.findKey(error.response.data, 'errortext')
          if (responseKey) {
            desc = error.response.data[responseKey].errortext
          } else if (typeof error.response.data === 'string') {
            desc = error.response.data
          }
        }
        if (!desc && error.message) {
          desc = error.message
        }
      }
      let countNotify = store.getters.countNotify
      countNotify++
      store.commit('SET_COUNT_NOTIFY', countNotify)
      notification.error({
        top: '65px',
        message: msg,
        description: desc,
        duration: 0,
        onClose: () => {
          let c = store.getters.countNotify
          c > 0 ? c-- : c = 0
          store.commit('SET_COUNT_NOTIFY', c)
        }
      })
    }

    app.config.globalProperties.$notification = {
      defaultConfig: {
        top: '65px',
        onClose: () => {
          let c = store.getters.countNotify
          c > 0 ? c-- : c = 0
          store.commit('SET_COUNT_NOTIFY', c)
        }
      },
      setCountNotify: () => {
        let c = store.getters.countNotify
        c++
        store.commit('SET_COUNT_NOTIFY', c)
      },
      info: (config) => {
        app.config.globalProperties.$notification.setCountNotify()
        config = Object.assign({}, app.config.globalProperties.$notification.defaultConfig, config)
        notification.info(config)
      },
      error: (config) => {
        app.config.globalProperties.$notification.setCountNotify()
        config = Object.assign({}, app.config.globalProperties.$notification.defaultConfig, config)
        notification.error(config)
      },
      success: (config) => {
        app.config.globalProperties.$notification.setCountNotify()
        config = Object.assign({}, app.config.globalProperties.$notification.defaultConfig, config)
        notification.success(config)
      },
      warning: (config) => {
        app.config.globalProperties.$notification.setCountNotify()
        config = Object.assign({}, app.config.globalProperties.$notification.defaultConfig, config)
        notification.warning(config)
      },
      warn: (config) => {
        app.config.globalProperties.$notification.setCountNotify()
        config = Object.assign({}, app.config.globalProperties.$notification.defaultConfig, config)
        notification.warn(config)
      },
      close: (key) => notification.close(key),
      destroy: () => notification.destroy()
    }

    app.config.globalProperties.$messageConfigSuccess = function (msg, configrecord) {
      if (configrecord.isdynamic) {
        msg += `. ${this.$t('message.setting.update.delay')}`
      }
      message.success(msg)
    }
  }
}

export const toLocaleDatePlugin = {
  install (app) {
    app.config.globalProperties.$toLocaleDate = function (date) {
      const { timezoneoffset, usebrowsertimezone } = this.$store.getters
      return toLocaleDate({ date, timezoneoffset, usebrowsertimezone })
    }

    app.config.globalProperties.$toLocalDate = function (date) {
      const { timezoneoffset, usebrowsertimezone } = this.$store.getters
      return toLocalDate({ date, timezoneoffset, usebrowsertimezone }).toISOString()
    }
  }
}

export const configUtilPlugin = {
  install (app) {
    app.config.globalProperties.$applyDocHelpMappings = function (docHelp) {
      var docHelpMappings = this.$config.docHelpMappings
      if (docHelp && docHelpMappings &&
        docHelpMappings.constructor === Object && Object.keys(docHelpMappings).length > 0) {
        for (var key in docHelpMappings) {
          if (docHelp.includes(key) && docHelp !== docHelpMappings[key]) {
            docHelp = docHelp.replace(key, docHelpMappings[key])
            break
          }
        }
      }
      return docHelp
    }
  }
}

export const showIconPlugin = {
  install (app) {
    app.config.globalProperties.$showIcon = function (resource) {
      var resourceType = this.$route.path.split('/')[1]
      if (resource) {
        resourceType = resource
      }
      if (['zone', 'zones', 'template', 'iso', 'account', 'accountuser', 'vm', 'domain', 'project', 'vpc', 'guestnetwork', 'guestoscategory'].includes(resourceType)) {
        return true
      } else {
        return false
      }
    }
  }
}

export const resourceTypePlugin = {
  install (app) {
    app.config.globalProperties.$getResourceType = function () {
      const type = this.$route.path.split('/')[1]
      if (type === 'vm') {
        return 'UserVM'
      } else if (type === 'accountuser') {
        return 'User'
      } else if (type === 'guestnetwork') {
        return 'Network'
      } else {
        return type
      }
    }

    app.config.globalProperties.$getRouteFromResourceType = function (resourceType) {
      switch (resourceType) {
        case 'VirtualMachine':
          return 'vm'
        case 'DomainRouter':
          return 'router'
        case 'ConsoleProxy':
          return 'systemvm'
        case 'User':
          return 'accountuser'
        case 'Network':
          return 'guestnetwork'
        case 'ServiceOffering':
          return 'computeoffering'
        case 'IpAddress':
          return 'publicip'
        case 'NetworkAcl':
          return 'acllist'
        case 'KubernetesCluster':
          return 'kubernetes'
        case 'KubernetesSupportedVersion':
          return 'kubernetesiso'
        case 'ExtensionCustomAction':
          return 'customaction'
        case 'SystemVm':
        case 'PhysicalNetwork':
        case 'Backup':
        case 'SecurityGroup':
        case 'StoragePool':
        case 'ImageStore':
        case 'Template':
        case 'Iso':
        case 'Host':
        case 'Volume':
        case 'Account':
        case 'Snapshot':
        case 'Project':
        case 'Domain':
        case 'DiskOffering':
        case 'NetworkOffering':
        case 'VpcOffering':
        case 'BackupOffering':
        case 'Zone':
        case 'Vpc':
        case 'VmSnapshot':
        case 'Pod':
        case 'Cluster':
        case 'Role':
        case 'AffinityGroup':
        case 'VpnCustomerGateway':
        case 'AutoScaleVmGroup':
        case 'QuotaTariff':
        case 'GuestOsCategory':
        case 'Extension':
          return resourceType.toLowerCase()
      }
      return ''
    }

    app.config.globalProperties.$getIconFromResourceType = function (resourceType) {
      var routePath = this.$getRouteFromResourceType(resourceType)
      if (!routePath) return ''
      var route = this.$router.resolve('/' + routePath)
      if (routePath === 'kubernetes') {
        return route?.meta?.icon[0]
      }
      return route?.meta?.icon || ''
    }
  }
}

export const apiMetaUtilPlugin = {
  install (app) {
    app.config.globalProperties.$getApiParams = function () {
      var apiParams = {}
      for (var argument of arguments) {
        var apiConfig = this.$store.getters.apis[argument] || {}
        if (apiConfig && apiConfig.params) {
          apiConfig.params.forEach(param => {
            apiParams[param.name] = param
          })
        }
      }
      return apiParams
    }
  }
}

export const localesPlugin = {
  install (app) {
    app.config.globalProperties.$t = i18n.global.t
  }
}

const KiB = 1024
const MiB = 1024 * KiB
const GiB = 1024 * MiB
const TiB = 1024 * GiB

export const fileSizeUtilPlugin = {
  install (app) {
    app.config.globalProperties.$bytesToGiB = function (bytes) {
      if (bytes == null || bytes === 0) {
        return 0
      }
      return (bytes / GiB).toFixed(2)
    }
    app.config.globalProperties.$bytesToHumanReadableSize = function (bytes) {
      if (bytes == null) {
        return ''
      }
      if (bytes < KiB && bytes >= 0) {
        return bytes + ' bytes'
      }
      if (bytes < MiB) {
        return (bytes / KiB).toFixed(2) + ' KiB'
      } else if (bytes < GiB) {
        return (bytes / MiB).toFixed(2) + ' MiB'
      } else if (bytes < TiB) {
        return (bytes / GiB).toFixed(2) + ' GiB'
      } else {
        return (bytes / TiB).toFixed(2) + ' TiB'
      }
    }
  }
}

export const genericUtilPlugin = {
  install (app) {
    app.config.globalProperties.$isValidUuid = function (uuid) {
      const regexExp = /^[0-9a-fA-F]{8}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{4}\b-[0-9a-fA-F]{12}$/gi
      return regexExp.test(uuid)
    }

    app.config.globalProperties.$toBase64AndURIEncoded = function (text) {
      if (isBase64(text)) {
        return text
      }
      return encodeURI(btoa(unescape(encodeURIComponent(text))))
    }
  }
}

export function createPathBasedOnVmType (vmtype, virtualmachineid) {
  let path = ''
  switch (vmtype) {
    case 'ConsoleProxy':
    case 'SecondaryStorageVm':
      path = '/systemvm/'
      break
    case 'DomainRouter':
      path = '/router/'
      break
    default:
      path = '/vm/'
  }

  return path + virtualmachineid
}

export const dialogUtilPlugin = {
  install (app) {
    app.config.globalProperties.$resetConfigurationValueConfirm = function (configRecord, callback) {
      Modal.confirm({
        title: i18n.global.t('label.reset.config.value'),
        content: `${i18n.global.t('message.confirm.reset.configuration.value').replace('%x', configRecord.name)}`,
        okText: i18n.global.t('label.yes'),
        cancelText: i18n.global.t('label.no'),
        okType: 'primary',
        onOk: () => callback(configRecord)
      })
    }

    app.config.globalProperties.$notifyConfigurationValueChange = function (configRecord) {
      if (!configRecord || configRecord.isdynamic || store.getters.userInfo?.roletype !== 'Admin') {
        return
      }
      const server = configRecord.group === 'Usage Server' ? 'usage' : 'mgmt'
      this.$notification.warning({
        message: this.$t('label.status'),
        description: this.$t('message.restart.' + server + '.server')
      })
    }
  }
}

export const cpuArchitectureUtilPlugin = {
  install (app) {
    app.config.globalProperties.$fetchCpuArchitectureTypes = function () {
      const architectures = [
        { id: 'x86_64', name: 'Intel/AMD 64 bits (x86_64)' },
        { id: 'aarch64', name: 'ARM 64 bits (aarch64)' },
        { id: 's390x', name: 'IBM Z 64 bits (s390x)' }
      ]
      return architectures.map(item => ({ ...item, description: item.name }))
    }
  }
}

export const imagesUtilPlugin = {
  install (app) {
    app.config.globalProperties.$fetchTemplateTypes = function (hypervisor) {
      const baseTypes = ['USER']
      if (hypervisor === 'External') {
        return baseTypes.map(type => ({ id: type, name: type, description: type }))
      }
      baseTypes.push('VNF')
      const adminTypes = ['SYSTEM', 'BUILTIN', 'ROUTING']
      const types = [...baseTypes]
      if (store.getters.userInfo?.roletype === 'Admin') {
        types.push(...adminTypes)
      }
      return types.map(type => ({ id: type, name: type, description: type }))
    }
  }
}

export const extensionsUtilPlugin = {
  install (app) {
    app.config.globalProperties.$fetchCustomActionRoleTypes = function () {
      const roleTypes = []
      const roleTypesList = ['Admin', 'Resource Admin', 'Domain Admin', 'User']
      roleTypesList.forEach((item) => {
        roleTypes.push({
          id: item.replace(' ', ''),
          description: item
        })
      })
      return roleTypes
    }
  }
}

export const backupUtilPlugin = {
  install (app) {
    app.config.globalProperties.$isBackupProviderSupportsQuiesceVm = function (provider) {
      if (!provider && typeof provider !== 'string') {
        return false
      }
      return ['ablestack-nas', 'ablestack-commvault'].includes(provider.toLowerCase())
    }
  }
}
