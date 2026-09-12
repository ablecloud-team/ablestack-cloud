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
  <a-spin :spinning="loading">
    <a-alert v-if="vm.qemuagentversion === 'Not Installed'" :message="$t('message.alert.qemuagentversion')" type="error" show-icon />
    <br/>
    <a-tabs
      :activeKey="currentTab"
      :tabPosition="device === 'mobile' ? 'top' : 'left'"
      :animated="false"
      @change="handleChangeTab">
      <a-tab-pane :tab="$t('label.details')" key="details">
        <DetailsTab :resource="dataResource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.metrics')" key="stats">
        <StatsTab :resource="resource"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.iso')" key="cdrom" v-if="attachedIsos.length > 0">
        <div v-for="iso in attachedIsos" :key="iso.id" style="margin-bottom: 12px;">
          <usb-outlined />
          <router-link :to="{ path: '/iso/' + iso.id }">{{ iso.displaytext || iso.name }}</router-link>
          <a-tag style="margin-left: 8px;">{{ slotLabel(iso.deviceseq) }}</a-tag>
          <a-tag v-if="iso.bootable" color="blue" style="margin-left: 4px;">{{ $t('label.bootable') }}</a-tag>
          <br/>
          <barcode-outlined /> {{ iso.id }}
        </div>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.volumes')" key="volumes" v-if="'listVolumes' in $store.getters.apis">
        <a-button
          type="primary"
          style="width: 100%; margin-bottom: 10px"
          @click="showAddVolModal"
          :loading="loading"
          :disabled="!('createVolume' in $store.getters.apis) || this.vm.state === 'Error' || resource.hypervisor === 'External'">
          <template #icon><plus-outlined /></template> {{ $t('label.action.create.volume.add') }}
        </a-button>
        <volumes-tab :resource="vm" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.gpu')" key="gpu" v-if="dataResource.gpucardname">
        <GPUTab
          apiName="listGpuDevices"
          :resource="dataResource"
          :params="{virtualmachineid: dataResource.id}"
          resourceType="VirtualMachine"
          :columns="['gpucardname', 'vgpuprofilename', 'state'].concat($store.getters.userInfo.roletype === 'Admin' ? ['id', 'hostname'] : [])"
          :routerlinks="(record) => { return { displayname: '/gpudevice/' + record.id } }"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.nics')" key="nics" v-if="'listNics' in $store.getters.apis">
        <NicsTab :resource="vm"/>
      </a-tab-pane>
      <a-tab-pane
        :tab="$t('label.vm.ip.configuration')"
        key="guestnetwork"
        v-if="resource.hypervisor === 'KVM' && 'getVirtualMachineGuestNetworkState' in $store.getters.apis">
        <GuestNetworkTab :resource="vm"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.vm.snapshots')" key="vmsnapshots" v-if="'listVMSnapshot' in $store.getters.apis">
        <ListResourceTable
          apiName="listVMSnapshot"
          :resource="dataResource"
          :params="{virtualmachineid: dataResource.id}"
          :columns="['displayname', 'state', 'type', 'created']"
          :routerlinks="(record) => { return { displayname: '/vmsnapshot/' + record.id } }"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.dr.plans')" key="drplans" v-if="'getDrVmProtectionView' in $store.getters.apis">
        <DrPlanVmTab :resource="vm" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.backup')" key="backups" v-if="'listBackups' in $store.getters.apis">
        <ListResourceTable
          apiName="listBackups"
          :resource="resource"
          :params="{virtualmachineid: dataResource.id}"
          :columns="dataResource.backupprovider === 'kboss'
            ? ['name', 'status', 'compressionstatus', 'validationstatus', 'size', 'virtualsize', 'type', 'intervaltype', 'created']
            : ['name', 'status', 'size', 'virtualsize', 'type', 'intervaltype', 'created']"
          :routerlinks="(record) => { return { name: '/backup/' + record.id } }"
          :showSearch="false"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.ftctl.fault.protection')" key="ftctl" v-if="'getFtctlProtection' in $store.getters.apis">
        <FtctlTab :resource="vm" :loading="loading" @keep-current-tab="keepCurrentTab" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.securitygroups')" key="securitygroups" v-if="(dataResource.securitygroup && dataResource.securitygroup.length > 0) || ($store.getters.showSecurityGroups && securityGroupNetworkProviderUseThisVM)">
        <a-button
          type="primary"
          style="width: 100%; margin-bottom: 10px"
          @click="showUpdateSGModal"
          :loading="loading">
          <template #icon><edit-outlined /></template> {{ $t('label.action.update.security.groups') }}
        </a-button>
        <ListResourceTable
          apiName="listSecurityGroups"
          :params="{virtualmachineid: dataResource.id}"
          :items="dataResource.securitygroup"
          :columns="['name', 'description']"
          :routerlinks="(record) => { return { name: '/securitygroups/' + record.id } }"
          :showSearch="false"/>
      </a-tab-pane>
      <a-tab-pane
        :tab="$t('label.schedules')"
        key="schedules"
        v-if="'listResourceSchedule' in $store.getters.apis && !dataResource.autoscalevmgroupid"
      >
        <ResourceSchedules
          :resource="vm"
          resourceType="VirtualMachine"
          :loading="loading"/>
      </a-tab-pane>
      <a-tab-pane
        :tab="$t('label.listhostdevices')"
        key="hostdevices"
      >
        <div class="host-devices-container">
          <!-- 기타 장치 (PCI) -->
          <div class="device-section">
            <h3 class="section-title">{{ $t('label.other.devices') }}</h3>
            <a-table
              :columns="deviceColumns"
              :dataSource="pciDevices"
              :pagination="false"
              :scroll="{ x: 'max-content' }"
              size="small"
              :loading="loading" />
          </div>

          <!-- HBA 디바이스 -->
          <div class="device-section">
            <h3 class="section-title">{{ $t('label.hba.devices') }}</h3>
            <a-table
              :columns="deviceColumns"
              :dataSource="hbaDevices"
              :pagination="false"
              :scroll="{ x: 'max-content' }"
              size="small"
              :loading="loading" />
          </div>

          <!-- VHBA 디바이스 -->
          <div class="device-section">
            <h3 class="section-title">{{ $t('label.vhba.devices') }}</h3>
            <a-table
              :columns="deviceColumns"
              :dataSource="vhbaDevices"
              :pagination="false"
              :scroll="{ x: 'max-content' }"
              size="small"
              :loading="loading" />
          </div>

          <!-- USB 디바이스 -->
          <div class="device-section">
            <h3 class="section-title">{{ $t('label.usb.devices') }}</h3>
            <a-table
              :columns="deviceColumns"
              :dataSource="usbDevices"
              :pagination="false"
              :scroll="{ x: 'max-content' }"
              size="small"
              :loading="loading" />
          </div>

          <!-- LUN 디바이스 -->
          <div class="device-section">
            <h3 class="section-title">{{ $t('label.lun.devices') }}</h3>
            <a-table
              :columns="deviceColumns"
              :dataSource="lunDevices"
              :pagination="false"
              :scroll="{ x: 'max-content' }"
              size="small"
              :loading="loading" />
          </div>

          <!-- SCSI 디바이스 -->
          <div class="device-section">
            <h3 class="section-title">{{ $t('label.scsi.devices') }}</h3>
            <a-table
              :columns="scsiDeviceColumns"
              :dataSource="scsiDevices"
              :pagination="false"
              :scroll="{ x: 'max-content' }"
              size="small"
              :loading="loading" />
          </div>
        </div>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.settings')" key="settings">
        <DetailSettings :resource="dataResource" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.events')" key="events" v-if="'listEvents' in $store.getters.apis">
        <events-tab :resource="dataResource" resourceType="VirtualMachine" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.annotations')" key="comments" v-if="'listAnnotations' in $store.getters.apis">
        <AnnotationsTab
          :resource="vm"
          :items="annotations">
        </AnnotationsTab>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      :visible="showUpdateSecurityGroupsModal"
      :title="$t('label.action.update.security.groups')"
      :maskClosable="false"
      :closable="true"
      @ok="updateSecurityGroups"
      @cancel="closeModals">
      <security-group-selection
        :zoneId="this.vm.zoneid"
        :value="securitygroupids"
        :loading="false"
        :preFillContent="dataPreFill"
        @select-security-group-item="($event) => updateSecurityGroupsSelection($event)"></security-group-selection>
    </a-modal>

    <a-modal
      :visible="showAddVolumeModal"
      :title="$t('label.action.create.volume.add')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeModals">
      <CreateVolume :resource="resource" @close-action="closeModals" />
    </a-modal>

  </a-spin>
</template>

<script>

import { getAPI, postAPI } from '@/api'
import { h } from 'vue'
import { mixinDevice } from '@/utils/mixin.js'
import ResourceLayout from '@/layouts/ResourceLayout'
import DetailsTab from '@/components/view/DetailsTab'
import StatsTab from '@/components/view/StatsTab'
import EventsTab from '@/components/view/EventsTab'
import DetailSettings from '@/components/view/DetailSettings'
import CreateVolume from '@/views/storage/CreateVolume'
import NicsTab from '@/views/network/NicsTab'
import GuestNetworkTab from '@/views/compute/GuestNetworkTab'
import ResourceSchedules from '@/views/compute/ResourceSchedules.vue'
import ListResourceTable from '@/components/view/ListResourceTable'
import ResourceIcon from '@/components/view/ResourceIcon'
import AnnotationsTab from '@/components/view/AnnotationsTab'
import VolumesTab from '@/components/view/VolumesTab.vue'
import SecurityGroupSelection from '@views/compute/wizard/SecurityGroupSelection'
import DrPlanVmTab from '@/views/compute/dr/DrPlanVmTab.vue'
import GPUTab from '@/components/view/GPUTab.vue'
import FtctlTab from '@/views/compute/FtctlTab.vue'

export default {
  name: 'InstanceTab',
  components: {
    ResourceLayout,
    DetailsTab,
    StatsTab,
    EventsTab,
    DetailSettings,
    CreateVolume,
    NicsTab,
    GuestNetworkTab,
    DrPlanVmTab,
    GPUTab,
    FtctlTab,
    ResourceSchedules,
    ListResourceTable,
    SecurityGroupSelection,
    ResourceIcon,
    AnnotationsTab,
    VolumesTab
  },
  mixins: [mixinDevice],
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      vm: {},
      totalStorage: 0,
      currentTab: this.resolveCurrentTabFromRoute(),
      showUpdateSecurityGroupsModal: false,
      showAddVolumeModal: false,
      diskOfferings: [],
      annotations: [],
      dataResource: {},
      editeNic: '',
      editNicLinkStat: '',
      dataPreFill: {},
      securitygroupids: [],
      securityGroupNetworkProviderUseThisVM: false,
      usbDevices: [],
      lunDevices: [],
      pciDevices: [],
      hbaDevices: [],
      vhbaDevices: [],
      scsiDevices: [],
      // 디바이스 데이터 캐싱을 위한 플래그들
      devicesLoaded: false,
      deviceLoadingStates: {
        pci: false,
        usb: false,
        lun: false,
        hba: false,
        vhba: false,
        scsi: false,
        fetching: false
      },
      deviceAssignmentsPromise: null,
      deviceColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName',
          fixed: 'left',
          width: 250,
          customRender: ({ text }) => {
            return h('div', { style: 'white-space: pre-line; line-height: 1.4; min-height: 50px; padding-top: 8px;' }, this.formatDeviceName(text))
          }
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText',
          width: 500,
          customRender: ({ text }) => {
            return h('div', {
              style: 'white-space: pre-wrap; word-break: break-word; min-height: 40px;',
              innerHTML: this.formatHostDevicesText(text)
            })
          }
        }
      ],
      scsiDeviceColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName',
          fixed: 'left',
          width: 250,
          customRender: ({ record }) => {
            return h(
              'div',
              { style: 'white-space: pre-line; line-height: 1.4; min-height: 50px; padding-top: 8px;' },
              this.formatDeviceName(record.scsiDisplayName || record.hostDevicesName)
            )
          }
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText',
          width: 500,
          customRender: ({ text }) => {
            return h('div', {
              style: 'white-space: pre-wrap; word-break: break-word; min-height: 40px;',
              innerHTML: this.formatScsiHostDevicesText(text)
            })
          }
        }
      ]
    }
  },
  created () {
    const self = this
    this.dataResource = this.resource
    this.vm = this.dataResource
    this.fetchData()
    window.addEventListener('popstate', function () {
      self.setCurrentTab()
    })
  },
  watch: {
    resource: {
      deep: true,
      handler (newData, oldData) {
        if (newData !== oldData) {
          const oldHostId = this.dataResource?.hostid
          const oldState = this.dataResource?.state
          const newHostId = newData?.hostid
          const newState = newData?.state

          this.dataResource = newData
          this.vm = this.dataResource

          // 호스트가 변경되었거나, VM 상태가 변경되면 디바이스 캐시 초기화
          if (oldHostId !== newHostId || oldState !== newState) {
            this.resetDeviceCache()
            // 디바이스 탭이 열려있으면 즉시 새로고침
            if (this.currentTab === 'hostdevices') {
              this.fetchData()
            }
          }
        }
      }
    },
    '$route.fullPath': function () {
      this.setCurrentTab()
    }
  },
  mounted () {
    this.setCurrentTab()
  },
  computed: {
    attachedIsos () {
      if (this.vm.isos && this.vm.isos.length > 0) {
        return [...this.vm.isos].sort((a, b) => (a.deviceseq || 0) - (b.deviceseq || 0))
      }
      if (this.vm.isoid) {
        return [{
          id: this.vm.isoid,
          name: this.vm.isoname,
          displaytext: this.vm.isodisplaytext,
          deviceseq: 3
        }]
      }
      return []
    }
  },
  methods: {
    // 디바이스 이름을 포맷팅하여 괄호 안의 내용을 줄바꿈으로 표시
    formatDeviceName (deviceName) {
      if (!deviceName) return ''

      // 괄호가 있는 경우 줄바꿈으로 분리
      const match = deviceName.match(/^(.+?)\s*\((.+?)\)$/)
      if (match) {
        const [, mainName, bracketContent] = match
        const result = `${mainName.trim()}\n(${bracketContent})`
        return result
      }

      return deviceName
    },
    formatHostDevicesText (text) {
      if (!text) {
        return ''
      }

      let formattedText = String(text)
      formattedText = formattedText.replace(/(^|\s)USE:\s*/g, '$1TRANSPORT: ')

      formattedText = formattedText.replace(/\s+SIZE:/g, '\nSIZE:')
      formattedText = formattedText.replace(/\s+HAS_PARTITIONS:/g, '\nHAS_PARTITIONS:')
      formattedText = formattedText.replace(/\s+SCSI_ADDRESS:/g, '\nSCSI_ADDRESS:')
      formattedText = formattedText.replace(/\s+SCSI\s+Address:/g, '\nSCSI Address:')
      formattedText = formattedText.replace(/\s+Type:/g, '\nType:')
      formattedText = formattedText.replace(/\s+Vendor:/g, '\nVendor:')
      formattedText = formattedText.replace(/\s+Model:/gi, '\nModel:')
      formattedText = formattedText.replace(/\s+Revision:/gi, '\nRevision:')
      formattedText = formattedText.replace(/\s+Device:/gi, '\nDevice:')
      formattedText = formattedText.replace(/\s+BY_ID:/g, '\nBY_ID:')
      formattedText = formattedText.replace(/\s+TRANSPORT:/g, '\nTRANSPORT:')
      formattedText = formattedText.replace(/\s+파티션\s+없음/g, '\n파티션 없음')
      formattedText = formattedText.replace(/\s+파티션\s+있음/g, '\n파티션 있음')
      formattedText = formattedText.replace(/\s+WWNN:/g, '\nWWNN:')
      formattedText = formattedText.replace(/\s+WWPN:/g, '\nWWPN:')
      formattedText = formattedText.replace(/\s+Fabric\s+WWN:/g, '\nFabric WWN:')
      formattedText = formattedText.replace(/\s+Max\s+vPorts:/g, '\nMax vPorts:')
      formattedText = formattedText.replace(/\s+ID\s+/g, '\nID ')

      formattedText = formattedText.replace(/(Revision:\s+[^\s\n]+)( +)(?=\S)/gi, '$1\n$2')

      formattedText = formattedText.replace(/HAS_PARTITIONS:\s*false/gi, '')
      formattedText = formattedText.replace(/HAS_PARTITIONS:\s*true/gi, this.$t('label.has.partitions'))

      formattedText = formattedText.replace(/USAGE_STATUS:\s*사용안함/gi, '사용안함')
      formattedText = formattedText.replace(/USAGE_STATUS:\s*사용중/gi, '사용중')

      formattedText = formattedText.replace(/IN_USE:\s*false/gi, this.$t('label.not.in.use'))
      formattedText = formattedText.replace(/IN_USE:\s*true/gi, this.$t('label.in.use'))

      formattedText = formattedText.replace(/(?:\r\n|\r|\n)/g, '<br/>')

      return formattedText
    },
    formatScsiHostDevicesText (text) {
      if (!text) {
        return ''
      }
      const withoutDevice = String(text).replace(/\s*Device:\s*\S+/gi, '')
      return this.formatHostDevicesText(withoutDevice)
    },
    slotLabel (deviceseq) {
      // 3 -> hdc, 4 -> hdd, ... matches LibvirtVMDef.getDevLabel for the IDE bus on KVM.
      if (typeof deviceseq !== 'number') return ''
      return 'hd' + String.fromCharCode('a'.charCodeAt(0) + deviceseq - 1)
    },
    setCurrentTab () {
      const routeTab = this.resolveCurrentTabFromRoute()
      if (this.currentTab !== routeTab) {
        this.currentTab = routeTab
      }
    },
    resolveCurrentTabFromRoute () {
      let tab = null
      if (this.$route?.query?.tab) {
        tab = this.$route.query.tab
      }
      if (!tab && typeof window !== 'undefined' && window.location?.hash) {
        const queryString = window.location.hash.split('?')[1] || ''
        tab = new URLSearchParams(queryString).get('tab')
      }
      if (tab === 'disasterrecoverycluster') {
        return 'getDrVmProtectionView' in this.$store.getters.apis ? 'drplans' : 'details'
      }
      return tab || 'details'
    },
    async fetchData () {
      this.annotations = []
      if (!this.vm || !this.vm.id) {
        return
      }
      getAPI('listAnnotations', { entityid: this.dataResource.id, entitytype: 'VM', annotationfilter: 'all' }).then(json => {
        if (json.listannotationsresponse && json.listannotationsresponse.annotation) {
          this.annotations = json.listannotationsresponse.annotation
        }
      })
      getAPI('listNetworks', { supportedservices: 'SecurityGroup' }).then(json => {
        if (json.listnetworksresponse && json.listnetworksresponse.network) {
          for (const net of json.listnetworksresponse.network) {
            if (this.securityGroupNetworkProviderUseThisVM) {
              break
            }
            const listVmParams = {
              id: this.resource.id,
              networkid: net.id,
              listall: true
            }
            getAPI('listVirtualMachines', listVmParams).then(json => {
              if (json.listvirtualmachinesresponse && json.listvirtualmachinesresponse?.virtualmachine?.length > 0) {
                this.securityGroupNetworkProviderUseThisVM = true
              }
            })
          }
        }
      })

      if (!this.devicesLoaded) {
        await this.loadDevicesFromDb()
        this.devicesLoaded = true
      }
    },
    listDiskOfferings () {
      getAPI('listDiskOfferings', {
        listAll: 'true',
        zoneid: this.vm.zoneid
      }).then(response => {
        this.diskOfferings = response.listdiskofferingsresponse.diskoffering
      })
    },
    showAddVolModal () {
      this.showAddVolumeModal = true
      this.listDiskOfferings()
    },
    showUpdateSGModal () {
      this.loadingSG = true
      if (this.vm.securitygroup && this.vm.securitygroup?.length > 0) {
        this.securitygroupids = []
        for (const sg of this.vm.securitygroup) {
          this.securitygroupids.push(sg.id)
        }
        this.dataPreFill = { securitygroupids: this.securitygroupids }
      }
      this.showUpdateSecurityGroupsModal = true
      this.loadingSG = false
    },
    closeModals () {
      this.showAddVolumeModal = false
      this.showUpdateSecurityGroupsModal = false
    },
    updateSecurityGroupsSelection (securitygroupids) {
      this.securitygroupids = securitygroupids || []
    },
    updateSecurityGroups () {
      postAPI('updateVirtualMachine', { id: this.vm.id, securitygroupids: this.securitygroupids.join(',') }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.closeModals()
        this.parentFetchData()
      })
    },
    async handleChangeTab (activeKey) {
      // Load host device data only when the device tab is selected.
      if (activeKey === 'hostdevices') {
        this.resetDeviceCache()
        await this.fetchData()
      }

      if (this.currentTab !== activeKey) {
        this.currentTab = activeKey

        // Keep the tab in the URL without triggering a full route update.
        const query = Object.assign({}, this.$route.query)
        query.tab = activeKey
        const queryString = Object.keys(query).map(key => {
          return encodeURIComponent(key) + '=' + encodeURIComponent(query[key])
        }).join('&')

        history.pushState({}, null, '#' + this.$route.path + '?' + queryString)
      }
    },
    keepCurrentTab (activeKey = 'ftctl') {
      const query = Object.assign({}, this.$route.query)
      if (query.tab !== activeKey) {
        query.tab = activeKey
        const queryString = Object.keys(query).map(key => {
          return encodeURIComponent(key) + '=' + encodeURIComponent(query[key])
        }).join('&')
        history.pushState({}, null, '#' + this.$route.path + '?' + queryString)
      }
      this.currentTab = activeKey
    },
    resetDeviceCache () {
      this.devicesLoaded = false
      this.deviceLoadingStates = {
        pci: false,
        usb: false,
        lun: false,
        hba: false,
        vhba: false,
        scsi: false,
        fetching: false
      }
      this.deviceAssignmentsPromise = null
      // 장치 데이터 초기화
      this.pciDevices = []
      this.usbDevices = []
      this.lunDevices = []
      this.hbaDevices = []
      this.vhbaDevices = []
      this.scsiDevices = []
    },
    async loadDevicesFromDb () {
      if (this.deviceAssignmentsPromise) {
        return this.deviceAssignmentsPromise
      }

      const deviceTypes = ['pci', 'usb', 'lun', 'hba', 'vhba', 'scsi']
      this.deviceAssignmentsPromise = (async () => {
        this.deviceLoadingStates.fetching = true
        deviceTypes.forEach(type => { this.deviceLoadingStates[type] = true })

        try {
          const response = await getAPI('listVmDeviceAssignments', { virtualmachineid: this.vm.id })
          const assignments = response?.listvmdeviceassignmentsresponse?.vmdeviceassignment
          const assignmentList = Array.isArray(assignments)
            ? assignments
            : assignments
              ? [assignments]
              : []

          const categorized = {
            pci: [],
            usb: [],
            lun: [],
            hba: [],
            vhba: [],
            scsi: []
          }

          const addUnique = (list, device) => {
            if (!list.some(existing =>
              existing.hostDevicesName === device.hostDevicesName &&
              existing.hostId === device.hostId)) {
              list.push(device)
            }
          }

          assignmentList.forEach(item => {
            const type = (item.devicetype || '').toLowerCase()
            if (!['pci', 'usb', 'lun', 'hba', 'vhba', 'scsi'].includes(type)) {
              return
            }
            const device = {
              key: `${item.hostid || 'na'}-${item.hostdevicesname}`,
              hostDevicesName: item.hostdevicesname,
              hostDevicesText: item.hostdevicestext || '',
              hostId: item.hostid
            }
            if (type === 'scsi') {
              const devMatch = String(device.hostDevicesText).match(/Device:\s*(\S+)/i)
              device.scsiDisplayName = devMatch ? devMatch[1] : device.hostDevicesName
            }

            switch (type) {
              case 'pci':
                addUnique(categorized.pci, device)
                break
              case 'usb':
                addUnique(categorized.usb, device)
                break
              case 'lun':
                addUnique(categorized.lun, device)
                break
              case 'hba':
                addUnique(categorized.hba, device)
                break
              case 'vhba':
                addUnique(categorized.vhba, device)
                break
              case 'scsi':
                addUnique(categorized.scsi, device)
                break
            }
          })

          const hostId = this.vm?.hostid || categorized.lun?.[0]?.hostId
          if (hostId && categorized.lun.length > 0) {
            const lunDetailMap = await this.fetchLunDetailMap(hostId)
            categorized.lun = categorized.lun.map(device => {
              if (device.hostDevicesText && String(device.hostDevicesText).trim().length > 0) {
                return device
              }
              const detail = lunDetailMap[device.hostDevicesName]
              if (detail) {
                return { ...device, hostDevicesText: detail }
              }
              return device
            })
          }

          this.pciDevices = categorized.pci
          this.usbDevices = categorized.usb
          this.lunDevices = categorized.lun
          this.hbaDevices = categorized.hba
          this.vhbaDevices = categorized.vhba
          this.scsiDevices = categorized.scsi
        } catch (error) {
          console.error('Failed to load VM device assignments', error)
          this.pciDevices = []
          this.usbDevices = []
          this.lunDevices = []
          this.hbaDevices = []
          this.vhbaDevices = []
          this.scsiDevices = []
        } finally {
          deviceTypes.forEach(type => { this.deviceLoadingStates[type] = false })
          this.deviceLoadingStates.fetching = false
          this.deviceAssignmentsPromise = null
        }
      })()

      return this.deviceAssignmentsPromise
    },
    async fetchLunDetailMap (hostId) {
      const detailMap = {}
      try {
        const [singleSettled, multiSettled] = await Promise.allSettled([
          getAPI('listHostLunDevices', { id: hostId, lunpathmode: 'single', lunPathMode: 'single' }),
          getAPI('listHostLunDevices', { id: hostId, lunpathmode: 'multipath', lunPathMode: 'multipath' })
        ])

        const sources = []
        if (singleSettled.status === 'fulfilled') {
          sources.push(singleSettled.value?.listhostlundevicesresponse?.listhostlundevices?.[0])
        }
        if (multiSettled.status === 'fulfilled') {
          sources.push(multiSettled.value?.listhostlundevicesresponse?.listhostlundevices?.[0])
        }

        sources.forEach(src => {
          if (!src) return
          if (src.devicedetails && typeof src.devicedetails === 'object') {
            Object.entries(src.devicedetails).forEach(([name, text]) => {
              if (name && text && !detailMap[name]) {
                detailMap[name] = text
              }
            })
          }
          const names = Array.isArray(src.hostdevicesname) ? src.hostdevicesname : []
          const texts = Array.isArray(src.hostdevicestext) ? src.hostdevicestext : []
          names.forEach((name, idx) => {
            const text = texts[idx]
            if (name && text && !detailMap[name]) {
              detailMap[name] = text
            }
          })
        })
      } catch (e) {
      }
      return detailMap
    },
    async fetchPciDevices () {
      await this.loadDevicesFromDb()
    },
    async fetchUsbDevices () {
      await this.loadDevicesFromDb()
    },
    async fetchLunDevices () {
      await this.loadDevicesFromDb()
    },
    async fetchHbaDevices () {
      await this.loadDevicesFromDb()
    },
    async fetchVhbaDevices () {
      await this.loadDevicesFromDb()
    },
    async fetchScsiDevices () {
      await this.loadDevicesFromDb()
    }
  }
}
</script>

<style lang="scss" scoped>
  .page-header-wrapper-grid-content-main {
    width: 100%;
    height: 100%;
    min-height: 100%;
    transition: 0.3s;
    .vm-detail {
      .svg-inline--fa {
        margin-left: -1px;
        margin-right: 8px;
      }
      span {
        margin-left: 10px;
      }
      margin-bottom: 8px;
    }
  }

  .list {
    margin-top: 20px;

    &__item {
      display: flex;
      flex-direction: column;
      align-items: flex-start;

      @media (min-width: 760px) {
        flex-direction: row;
        align-items: center;
      }
    }
  }

  .modal-form {
    display: flex;
    flex-direction: column;

    &__label {
      margin-top: 20px;
      margin-bottom: 5px;
      font-weight: bold;

      &--no-margin {
        margin-top: 0;
      }
    }
  }

  .actions {
    display: flex;
    flex-wrap: wrap;

    button {
      padding: 5px;
      height: auto;
      margin-bottom: 10px;
      align-self: flex-start;

      &:not(:last-child) {
        margin-right: 10px;
      }
    }

  }

  .label {
    font-weight: bold;
  }

  .attribute {
    margin-bottom: 10px;
  }

  .ant-tag {
    padding: 4px 10px;
    height: auto;
    margin-left: 5px;
  }

  .title {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    align-items: center;

    a {
      margin-right: 30px;
      margin-bottom: 10px;
    }

    .ant-tag {
      margin-bottom: 10px;
    }

    &__details {
      display: flex;
    }

    .tags {
      margin-left: 10px;
    }

  }
  .dr-simulation-modal {
    width: 100%;
  }

  .ant-list-item-meta-title {
    margin-bottom: -10px;
  }

  .divider-small {
    margin-top: 20px;
    margin-bottom: 20px;
  }

  .list-item {

    &:not(:first-child) {
      padding-top: 25px;
    }

  }
</style>
<style scoped>
.wide-modal {
  min-width: 50vw;
}

:deep(.ant-list-item) {
  padding-top: 12px;
  padding-bottom: 12px;
}

.host-devices-container .device-section {
  margin-bottom: 32px;
}

.host-devices-container .device-section:last-child {
  margin-bottom: 0;
}

.host-devices-container .device-section .section-title {
  margin-bottom: 16px;
  font-size: 16px;
  font-weight: 600;
  color: #1890ff;
  border-bottom: 2px solid #f0f0f0;
  padding-bottom: 8px;
}

.host-devices-container .device-section :deep(.ant-table-cell:nth-child(2)) {
  white-space: pre-line;
}

</style>
