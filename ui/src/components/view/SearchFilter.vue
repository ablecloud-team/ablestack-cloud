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
  <a-row
    style="overflow: auto"
    :wrap="false"
  >
    <template
      v-for="filter in this.searchFilters"
      :key="filter.key + filter.value"
    >
      <a-col v-if="!['page', 'pagesize', 'q', 'keyword', 'tags', 'projectid', 'sortkey', 'sortorder'].includes(filter.key)">
        <a-tag
          v-if="!filter.isTag"
          closable
          @close="() => $emit('removeFilter', filter)"
        >
          <a-tooltip
            :title="retrieveFieldLabel(filter.key) + ': ' + filter.value + (['affinitygroupid', 'volumeid'].includes(filter.key) && filter.rawValue !== filter.value ? ' (' + filter.rawValue + ')' : '')"
            placement="bottom"
          >
            {{ retrieveFieldLabel(filter.key) }} : {{ getTrimmedText(filter.value, 20) }}
          </a-tooltip>
        </a-tag>
        <a-tag
          v-else
          closable
          @close="() => $emit('removeFilter', filter)"
        >
          <a-tooltip
            :title="$t('label.tag') + ': ' + filter.key + '=' + filter.value"
            placement="bottom"
          >
            {{ $t('label.tag') }}: {{ filter.key }}={{ getTrimmedText(filter.value, 20) }}
          </a-tooltip>
        </a-tag>
      </a-col>
    </template>
  </a-row>
</template>

<script>

import { getAPI } from '@/api'
import { affinityPolicy } from '@/utils/affinityGroupPresentation'
import { snapshotSearchLabel, snapshotSearchValue, snapshotPickerScope } from '@/utils/storageSnapshotSearch'

export default {
  name: 'SearchFilter',
  props: {
    filters: {
      type: Array,
      default: () => []
    },
    apiName: {
      type: String,
      default: () => ''
    },
    filterKey: {
      type: String,
      default: () => ''
    },
    filterValue: {
      type: String,
      default: () => ''
    },
    isTag: {
      type: Boolean,
      default: () => false
    }
  },
  emits: ['removeFilter'],
  data () {
    return {
      searchFilters: [],
      filterVersion: 0,
      apiMap: {
        type: (value) => this.getType(value),
        hypervisor: (value) => this.getHypervisor(value),
        zoneid: {
          apiName: 'listZones',
          responseKey1: 'listzonesresponse',
          responseKey2: 'zone',
          field: 'name'
        },
        domainid: {
          apiName: 'listDomains',
          responseKey1: 'listdomainsresponse',
          responseKey2: 'domain',
          field: 'name'
        },
        account: {
          apiName: 'listAccounts',
          responseKey1: 'listaccountsresponse',
          responseKey2: 'account',
          field: 'name'
        },
        imagestoreid: {
          apiName: 'listImageStores',
          responseKey1: 'listimagestoresresponse',
          responseKey2: 'imagestore',
          field: 'name'
        },
        storageid: {
          apiName: 'listStoragePools',
          responseKey1: 'liststoragepoolsresponse',
          responseKey2: 'storagepool',
          field: 'name'
        },
        podid: {
          apiName: 'listPods',
          responseKey1: 'listpodsresponse',
          responseKey2: 'pod',
          field: 'name'
        },
        clusterid: {
          apiName: 'listClusters',
          responseKey1: 'listclustersresponse',
          responseKey2: 'cluster',
          field: 'name'
        },
        hostid: {
          apiName: 'listHosts',
          responseKey1: 'listhostsresponse',
          responseKey2: 'host',
          field: 'name'
        },
        groupid: {
          apiName: 'listInstanceGroups',
          responseKey1: 'listinstancegroupsresponse',
          responseKey2: 'instancegroup',
          field: 'name'
        },
        managementserverid: {
          apiName: 'listManagementServers',
          responseKey1: 'listmanagementserversresponse',
          responseKey2: 'managementserver',
          field: 'name'
        },
        serviceofferingid: {
          apiName: 'listServiceOfferings',
          responseKey1: 'listserviceofferingsresponse',
          responseKey2: 'serviceoffering',
          field: 'name'
        },
        diskofferingid: {
          apiName: 'listDiskOfferings',
          responseKey1: 'listdiskofferingsresponse',
          responseKey2: 'diskoffering',
          field: 'name'
        },
        networkid: {
          apiName: 'listNetworks',
          responseKey1: 'listnetworksresponse',
          responseKey2: 'network',
          field: 'name'
        },
        hsmprofileid: {
          apiName: 'listHSMProfiles',
          responseKey1: 'listhsmprofilesresponse',
          responseKey2: 'hsmprofile',
          field: 'name'
        },
        kmskeyid: {
          apiName: 'listKMSKeys',
          responseKey1: 'listkmskeysresponse',
          responseKey2: 'kmskey',
          field: 'name'
        }
      }
    }
  },
  computed: {
    lookupScope () { return JSON.stringify([this.$store?.getters?.userInfo?.id, this.$store?.state?.user?.token, this.$store?.getters?.project?.id, this.$route.query?.projectid, this.$route.path, this.apiName]) }
  },
  beforeUnmount () { this.filterVersion++ },
  watch: {
    filters: { immediate: true, handler (filters) { this.resolveFilters(filters) } },
    lookupScope () { this.resolveFilters(this.filters) }
  },
  methods: {
    async resolveFilters (filters) {
      const version = ++this.filterVersion
      const scope = this.lookupScope
      const cloned = filters.map(filter => ({ ...filter, rawValue: filter.value }))
      this.searchFilters = cloned.map(filter => ({ ...filter }))
      const resolved = await Promise.all(cloned.map(async filter => {
        if (filter.key === 'tags') return { ...filter, isTag: true }
        try {
          const value = await this.getSearchFilters(filter.key, filter.value)
          return { ...filter, value: value !== undefined && value !== null && value !== '' ? value : filter.value }
        } catch (_) { return filter }
      }))
      if (version === this.filterVersion && scope === this.lookupScope) this.searchFilters = resolved
    },
    getTrimmedText (text, length) {
      if (!text) {
        return ''
      }
      return (text.length <= length) ? text : (text.substring(0, length - 3) + '...')
    },
    retrieveFieldLabel (fieldName) {
      if (this.apiName === 'listSnapshotPolicies' && fieldName === 'fordisplay') return this.$t('label.snapshot.policy.scheduling')
      if (this.apiName === 'listSnapshots') return this.$t(snapshotSearchLabel(fieldName))
      if (fieldName === 'affinitygroupid') return this.$t('label.affinitygroup')
      if (fieldName === 'groupid') {
        fieldName = 'group'
      }
      if (fieldName === 'hsmprofileid') {
        fieldName = 'hsm.profile'
      }
      if (fieldName === 'kmskeyid') {
        fieldName = 'kms.key'
      }
      if (fieldName === 'keyword') {
        if ('listAnnotations' in this.$store.getters.apis) {
          return this.$t('label.annotation')
        } else {
          return this.$t('label.name')
        }
      }
      return this.$t('label.' + fieldName)
    },
    async getSearchFilters (key, value) {
      const val = this.getStaticFieldValue(key, value)
      if (val !== '') {
        return val
      } else {
        return this.getDynamicFieldValue(key, value)
      }
    },
    getStaticFieldValue (key, value) {
      if (this.apiName === 'listSnapshotPolicies' && key === 'fordisplay') return this.$t(value === 'true' ? 'label.snapshot.policy.schedule.enabled' : 'label.snapshot.policy.schedule.paused')
      if (this.apiName === 'listSnapshots') {
        const label = snapshotSearchValue(key, value)
        if (label) return this.$t(label)
      }
      let formattedValue = ''
      if (key.includes('type')) {
        if (this.$route.path === '/guestnetwork' || this.$route.path.includes('/guestnetwork/')) {
          formattedValue = this.getGuestNetworkType(value)
        } else if (this.$route.path === '/role' || this.$route.path.includes('/role/')) {
          formattedValue = this.getRoleType(value)
        }
      }

      if (key.includes('scope')) {
        // Check storage pool scope first (more specific), then fall back to general scope
        const storagePoolScope = this.getStoragePoolScope(value)
        formattedValue = storagePoolScope && storagePoolScope.length > 0 ? storagePoolScope : this.getScope(value)
      }

      if (key.includes('state')) {
        formattedValue = this.getState(value)
      }

      if (key.includes('level')) {
        formattedValue = this.getLevel(value)
      }

      if (key.includes('entitytype')) {
        formattedValue = this.getEntityType(value)
      }

      if (key.includes('accounttype')) {
        formattedValue = this.getAccountType(value)
      }

      if (key.includes('systemvmtype')) {
        formattedValue = this.getSystemVmType(value)
      }

      if (key.includes('provider')) {
        formattedValue = this.getImageStoreProvider(value)
      }

      if (key.includes('resourcetype')) {
        formattedValue = value
      }
      return formattedValue
    },
    async getDynamicFieldValue (key, value) {
      let formattedValue = ''

      if (this.apiName === 'listSnapshots' && key === 'account') return value
      if (['listSnapshots', 'listSnapshotPolicies'].includes(this.apiName) && key === 'volumeid') {
        if (!this.$store.getters.apis?.listVolumes) return value
        const query = this.$route.query
        const project = query.projectid || this.$store.getters.project?.id || (this.$store.getters.listAllProjects ? '-1' : '')
        const response = await getAPI('listVolumes', { id: value, listall: true, ...snapshotPickerScope(query, project), ...(query.zoneid ? { zoneid: query.zoneid } : {}) }, { preserveOnFailure: true, timeout: 20000 })
        return response?.listvolumesresponse?.volume?.find(row => row.id === value)?.name || value
      }

      if (key === 'affinitygroupid') {
        if (!this.$isValidUuid(value) || (this.$store?.getters?.apis && !('listAffinityGroups' in this.$store.getters.apis))) return value
        const response = await getAPI('listAffinityGroups', { id: value, listall: true, projectid: this.$route.query?.projectid || this.$store?.getters?.project?.id || '-1' }, { preserveOnFailure: true })
        return response?.listaffinitygroupsresponse?.affinitygroup?.find(group => group.id === value)?.name || value
      }
      if (typeof this.apiMap[key] === 'function') {
        formattedValue = await this.apiMap[key](value)
      } else if (this.apiMap[key]) {
        const apiName = this.apiMap[key].apiName
        const responseKey1 = this.apiMap[key].responseKey1
        const responseKey2 = this.apiMap[key].responseKey2
        const field = this.apiMap[key].field
        formattedValue = await this.getResourceNameById(apiName, responseKey1, responseKey2, value, field)
      }
      if (formattedValue === '') {
        formattedValue = value
      }
      return formattedValue
    },
    getHypervisor (value) {
      return new Promise((resolve) => {
        getAPI('listHypervisors').then(json => {
          if (json?.listhypervisorsresponse?.hypervisor) {
            for (const key in json.listhypervisorsresponse.hypervisor) {
              const hypervisor = json.listhypervisorsresponse.hypervisor[key]
              if (hypervisor.name === value) {
                return resolve(hypervisor.name)
              }
            }
          }
          resolve(null)
        }).catch(() => {
          resolve(null)
        })
      })
    },
    getResourceNameById (apiName, responseKey1, responseKey2, id, field) {
      return new Promise((resolve) => {
        if (!this.$isValidUuid(id)) {
          return resolve('')
        }
        getAPI(apiName, { listAll: true, id: id }).then(json => {
          const items = json && json[responseKey1] && json[responseKey1][responseKey2]
          if (Array.isArray(items) && items.length > 0 && items[0] && items[0][field] !== undefined) {
            resolve(items[0][field])
          } else {
            resolve('')
          }
        }).catch(() => {
          resolve('')
        })
      })
    },
    getType (type) {
      if (this.$route.path === '/alert') {
        return this.getAlertType(type)
      } else if (this.$route.path === '/affinitygroup') {
        return this.getAffinityGroupType(type)
      }
    },
    getAlertType (type) {
      return new Promise((resolve) => {
        getAPI('listAlertTypes').then(json => {
          const alertTypes = {}
          for (const key in json.listalerttypesresponse.alerttype) {
            const alerttype = json.listalerttypesresponse.alerttype[key]
            alertTypes[alerttype.id] = alerttype.name
          }
          resolve(alertTypes[type])
        }).catch(() => {
          resolve(null)
        })
      })
    },
    getAffinityGroupType (type) {
      const policy = affinityPolicy(type)
      return Promise.resolve(policy ? this.$t('label.affinity.type.' + policy) : type)
    },
    getGuestNetworkType (value) {
      switch (value.toLowerCase()) {
        case 'isolated':
          return this.$t('label.isolated')
        case 'shared':
          return this.$t('label.shared')
        case 'l2':
          return this.$t('label.l2')
      }
    },
    getAccountType (type) {
      const types = []
      if (this.apiName.indexOf('listAccounts') > -1) {
        switch (type) {
          case '1':
            return 'Admin'
          case '2':
            return 'DomainAdmin'
          case '0':
            return 'User'
        }
      }
      return types
    },
    getSystemVmType (type) {
      if (this.apiName.indexOf('listSystemVms') > -1) {
        switch (type.toLowerCase()) {
          case 'consoleproxy':
            return this.$t('label.console.proxy.vm')
          case 'secondarystoragevm':
            return this.$t('label.secondary.storage.vm')
        }
      }
    },
    getStoragePoolScope (scope) {
      if (this.apiName.indexOf('listStoragePools') > -1) {
        switch (scope.toUpperCase()) {
          case 'CLUSTER':
            return this.$t('label.cluster')
          case 'ZONE':
            return this.$t('label.zone')
          case 'REGION':
            return this.$t('label.region')
          case 'GLOBAL':
            return this.$t('label.global')
        }
      }
    },
    getImageStoreProvider (provider) {
      if (this.apiName.indexOf('listImageStores') > -1) {
        switch (provider.toUpperCase()) {
          case 'NFS':
            return 'NFS'
          case 'SMB':
            return 'SMB/CIFS'
          case 'S3':
            return 'S3'
          case 'SWIFT':
            return 'Swift'
        }
      }
    },
    getRoleType (role) {
      switch (role.toLowerCase()) {
        case 'Admin'.toLowerCase():
          return 'Admin'
        case 'ResourceAdmin'.toLowerCase():
          return 'ResourceAdmin'
        case 'DomainAdmin'.toLowerCase():
          return 'DomainAdmin'
        case 'User'.toLowerCase():
          return 'User'
      }
    },
    getScope (scope) {
      switch (scope.toLowerCase()) {
        case 'local':
          return this.$t('label.local')
        case 'domain':
          return this.$t('label.domain')
        case 'global':
          return this.$t('label.global')
      }
    },
    getState (state) {
      if (this.apiName === 'listVMSnapshot') {
        const value = String(state).toLowerCase()
        return ['ready', 'creating', 'allocated', 'reverting', 'expunging', 'error'].includes(value) ? this.$t('state.' + value) : state
      } else if (this.apiName.includes('listVolumes')) {
        switch (state.toLowerCase()) {
          case 'allocated':
            return this.$t('label.allocated')
          case 'ready':
            return this.$t('label.isready')
          case 'destroy':
            return this.$t('label.destroy')
          case 'expunging':
            return this.$t('label.expunging')
          case 'expunged':
            return this.$t('label.expunged')
          case 'migrating':
            return this.$t('label.migrating')
        }
      } else if (this.apiName.includes('listKubernetesClusters')) {
        switch (state.toLowerCase()) {
          case 'created':
            return this.$t('label.created')
          case 'starting':
            return this.$t('label.starting')
          case 'running':
            return this.$t('label.running')
          case 'stopping':
            return this.$t('label.stopping')
          case 'stopped':
            return this.$t('label.stopped')
          case 'scaling':
            return this.$t('label.scaling')
          case 'upgrading':
            return this.$t('label.upgrading')
          case 'alert':
            return this.$t('label.alert')
          case 'recovering':
            return this.$t('label.recovering')
          case 'destroyed':
            return this.$t('label.destroyed')
          case 'destroying':
            return this.$t('label.destroying')
          case 'error':
            return this.$t('label.error')
        }
      } else if (this.apiName.indexOf('listWebhooks') > -1) {
        switch (state.toLowerCase()) {
          case 'enabled':
            return this.$t('label.enabled')
          case 'disabled':
            return this.$t('label.disabled')
        }
      }
    },
    getEntityType (type) {
      let entityType = ''
      if (this.apiName.indexOf('listAnnotations') > -1) {
        const allowedTypes = {
          VM: 'Virtual Machine',
          HOST: 'Host',
          VOLUME: 'Volume',
          SNAPSHOT: 'Snapshot',
          VM_SNAPSHOT: 'VM Snapshot',
          INSTANCE_GROUP: 'Instance Group',
          NETWORK: 'Network',
          VPC: 'VPC',
          PUBLIC_IP_ADDRESS: 'Public IP Address',
          VPN_CUSTOMER_GATEWAY: 'VPC Customer Gateway',
          TEMPLATE: 'Template',
          ISO: 'ISO',
          SSH_KEYPAIR: 'SSH Key Pair',
          DOMAIN: 'Domain',
          SERVICE_OFFERING: 'Service Offering',
          DISK_OFFERING: 'Disk Offering',
          NETWORK_OFFERING: 'Network Offering',
          POD: 'Pod',
          ZONE: 'Zone',
          CLUSTER: 'Cluster',
          PRIMARY_STORAGE: 'Primary Storage',
          SECONDARY_STORAGE: 'Secondary Storage',
          VR: 'Virtual Router',
          SYSTEM_VM: 'System VM',
          KUBERNETES_CLUSTER: 'Kubernetes Cluster'
        }
        entityType = allowedTypes[type.toUpperCase()]
      }
      return entityType
    },
    getLevel (level) {
      switch (level.toUpperCase()) {
        case 'INFO':
          return this.$t('label.info.upper')

        case 'WARN':
          return this.$t('label.warn.upper')

        case 'ERROR':
          return this.$t('label.error.upper')
      }
    }
  }
}
</script>
