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
      <a-tab-pane :tab="$t('label.iso')" key="cdrom" v-if="vm.isoid">
        <usb-outlined />
        <router-link :to="{ path: '/iso/' + vm.isoid }">{{ vm.isoname }}</router-link> <br/>
        <barcode-outlined /> {{ vm.isoid }}
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.volumes')" key="volumes" v-if="'listVolumes' in $store.getters.apis">
        <a-button
          type="primary"
          style="width: 100%; margin-bottom: 10px"
          @click="showAddVolModal"
          :loading="loading"
          :disabled="!('createVolume' in $store.getters.apis)">
          <template #icon><plus-outlined /></template> {{ $t('label.action.create.volume.add') }}
        </a-button>
        <volumes-tab :resource="vm" :loading="loading" />
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.nics')" key="nics" v-if="'listNics' in $store.getters.apis">
        <NicsTab :resource="vm"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.vm.snapshots')" key="vmsnapshots" v-if="'listVMSnapshot' in $store.getters.apis">
        <ListResourceTable
          apiName="listVMSnapshot"
          :resource="dataResource"
          :params="{virtualmachineid: dataResource.id}"
          :columns="['displayname', 'state', 'type', 'created']"
          :routerlinks="(record) => { return { displayname: '/vmsnapshot/' + record.id } }"/>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.dr')" key="disasterrecoverycluster" v-if="'createDisasterRecoveryClusterVm' in $store.getters.apis">
        <a-button
          type="primary"
          style="width: 100%; margin-bottom: 10px"
          @click="showAddMirVMModal"
          :loading="loadingMirror"
          :disabled="!('createDisasterRecoveryClusterVm' in $store.getters.apis)">
          <template #icon><plus-outlined /></template> {{ $t('label.add.dr.mirroring.vm') }}
        </a-button>
        <DRTable :resource="vm" :loading="loading">
          <template #actions="record">
            <tooltip-button
              tooltipPlacement="bottom"
              :tooltip="$t('label.dr.simulation.test')"
              icon="ExperimentOutlined"
              :disabled="!('connectivityTestsDisasterRecovery' in $store.getters.apis)"
              @onClick="DrSimulationTest(record)" />
            <tooltip-button
              tooltipPlacement="bottom"
              :tooltip="$t('label.dr.remove.mirroring')"
              :disabled="!('deleteDisasterRecoveryClusterVm' in $store.getters.apis)"
              type="primary"
              :danger="true"
              icon="link-outlined"
              @onClick="removeMirror(record)" />
          </template>
        </DRTable>
      </a-tab-pane>
      <a-tab-pane :tab="$t('label.backup')" key="backups" v-if="'listBackups' in $store.getters.apis">
        <ListResourceTable
          apiName="listBackups"
          :resource="resource"
          :params="{virtualmachineid: dataResource.id}"
          :columns="['created', 'status', 'type', 'size', 'virtualsize']"
          :routerlinks="(record) => { return { created: '/backup/' + record.id } }"
          :showSearch="false"/>
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
      <a-tab-pane :tab="$t('label.schedules')" key="schedules" v-if="'listVMSchedule' in $store.getters.apis">
        <InstanceSchedules
          :virtualmachine="vm"
          :loading="loading"/>
      </a-tab-pane>
      <a-tab-pane
        :tab="$t('label.listhostdevices')"
        key="pcidevices"
        v-if="hasPciDevices || hasUsbDevices || hasLunDevices || hasHbaDevices || hasVhbaDevices || hasScsiDevices"
      >
        <a-tabs v-model:activeKey="hostDeviceTabKey" style="margin-bottom: 16px;">
          <a-tab-pane v-if="pciDevices.length > 0" key="pci" :tab="$t('label.other.devices')">
            <a-table :columns="pciColumns" :dataSource="pciDevices" :pagination="false" :loading="loading" />
          </a-tab-pane>
          <a-tab-pane v-if="hbaDevices.length > 0" key="hba" :tab="$t('label.hba.devices')">
            <a-table :columns="hbaColumns" :dataSource="hbaDevices" :pagination="false" :loading="loading" />
          </a-tab-pane>
          <a-tab-pane v-if="vhbaDevices.length > 0" key="vhba" :tab="$t('label.vhba.devices')">
            <a-table :columns="vhbaColumns" :dataSource="vhbaDevices" :pagination="false" :loading="loading" />
          </a-tab-pane>
          <a-tab-pane v-if="usbDevices.length > 0" key="usb" :tab="$t('label.usb.devices')">
            <a-table :columns="usbColumns" :dataSource="usbDevices" :pagination="false" :loading="loading" />
          </a-tab-pane>
          <a-tab-pane v-if="lunDevices.length > 0" key="lun" :tab="$t('label.lun.devices')">
            <a-table :columns="lunColumns" :dataSource="lunDevices" :pagination="false" :loading="loading" />
          </a-tab-pane>
          <a-tab-pane v-if="scsiDevices.length > 0" key="scsi" :tab="$t('label.scsi.devices')">
            <a-table :columns="scsiColumns" :dataSource="scsiDevices" :pagination="false" :loading="loading" />
          </a-tab-pane>
        </a-tabs>
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

    <a-modal
      :visible="showAddMirrorVMModal"
      :title="$t('label.add.dr.mirroring.vm')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeModals">
      <DRMirroringVMAdd :resource="resource" @close-action="closeModals" />
    </a-modal>

    <a-modal
      :visible="showDrSimulationTestModal"
      :title="$t('label.dr.simulation.test')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      width="850px"
      @cancel="closeModals">
      <DRsimulationTestModal :resource="resource" @close-action="closeModals" />
    </a-modal>

    <a-modal
      :visible="showRemoveMirrorVMModal"
      :title="$t('label.dr.remove.mirroring')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeModals">
      <DRMirroringVMRemove :resource="resource" @close-action="closeModals" />
    </a-modal>
  </a-spin>
</template>

<script>

import { h } from 'vue'
import { api } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import ResourceLayout from '@/layouts/ResourceLayout'
import DetailsTab from '@/components/view/DetailsTab'
import StatsTab from '@/components/view/StatsTab'
import EventsTab from '@/components/view/EventsTab'
import DetailSettings from '@/components/view/DetailSettings'
import CreateVolume from '@/views/storage/CreateVolume'
import NicsTab from '@/views/network/NicsTab'
import InstanceSchedules from '@/views/compute/InstanceSchedules.vue'
import ListResourceTable from '@/components/view/ListResourceTable'
import TooltipButton from '@/components/widgets/TooltipButton'
import ResourceIcon from '@/components/view/ResourceIcon'
import AnnotationsTab from '@/components/view/AnnotationsTab'
import VolumesTab from '@/components/view/VolumesTab.vue'
import SecurityGroupSelection from '@views/compute/wizard/SecurityGroupSelection'
import DRTable from '@/views/compute/dr/DRTable'
import DRsimulationTestModal from '@/views/compute/dr/DRsimulationTestModal'
import DRMirroringVMAdd from '@/views/compute/dr/DRMirroringVMAdd'
import DRMirroringVMRemove from '@/views/compute/dr/DRMirroringVMRemove'

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
    DRTable,
    DRsimulationTestModal,
    DRMirroringVMAdd,
    DRMirroringVMRemove,
    InstanceSchedules,
    ListResourceTable,
    SecurityGroupSelection,
    TooltipButton,
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
      currentTab: 'details',
      showAddVolumeModal: false,
      showUpdateSecurityGroupsModal: false,
      diskOfferings: [],
      showAddMirrorVMModal: false,
      showDrSimulationTestModal: false,
      showRemoveMirrorVMModal: false,
      loadingMirror: false,
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
      pciColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName'
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText',
          customRender: ({ text }) => {
            return h('div', { style: 'white-space: pre-wrap; word-break: break-word;' }, text)
          }
        }
      ],
      usbColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName'
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText',
          customRender: ({ text }) => {
            return h('div', { style: 'white-space: pre-wrap; word-break: break-word;' }, text)
          }
        }
      ],
      lunColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName'
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText',
          customRender: ({ text }) => {
            return h('div', { style: 'white-space: pre-wrap; word-break: break-word;' }, text)
          }
        }
      ],
      hbaColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName'
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText',
          customRender: ({ text }) => {
            return h('div', { style: 'white-space: pre-wrap; word-break: break-word;' }, text)
          }
        }
      ],
      vhbaColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName'
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText',
          customRender: ({ text }) => {
            return h('div', { style: 'white-space: break-word;' }, text)
          }
        }
      ],
      scsiColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName'
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText',
          customRender: ({ text }) => {
            return h('div', { style: 'white-space: pre-wrap; word-break: break-word;' }, text)
          }
        }
      ],
      hostDeviceTabKey: 'pci'
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
          this.dataResource = newData
          this.vm = this.dataResource
          this.fetchData()
        }
      }
    },
    '$route.fullPath': function () {
      this.setCurrentTab()
    },
    hostDeviceTabKey (newKey) {
      if (newKey === 'pci') {
        this.fetchPciDevices()
      } else if (newKey === 'usb') {
        this.fetchUsbDevices()
      } else if (newKey === 'lun') {
        this.fetchLunDevices()
      } else if (newKey === 'hba') {
        this.fetchHbaDevices()
      } else if (newKey === 'vhba') {
        this.fetchVhbaDevices()
      } else if (newKey === 'scsi') {
        this.fetchScsiDevices()
      }
    }
  },
  computed: {
    hasPciDevices () {
      const has = this.pciDevices.length > 0
      console.log('hasPciDevices:', has, 'count:', this.pciDevices.length)
      return has
    },
    hasUsbDevices () {
      const has = this.usbDevices.length > 0
      console.log('hasUsbDevices:', has, 'count:', this.usbDevices.length)
      return has
    },
    hasLunDevices () {
      const has = this.lunDevices.length > 0
      console.log('hasLunDevices:', has, 'count:', this.lunDevices.length)
      return has
    },
    hasHbaDevices () {
      const has = this.hbaDevices.length > 0
      console.log('hasHbaDevices:', has, 'count:', this.hbaDevices.length)
      return has
    },
    hasVhbaDevices () {
      const has = this.vhbaDevices.length > 0
      console.log('hasVhbaDevices:', has, 'count:', this.vhbaDevices.length)
      return has
    },
    hasScsiDevices () {
      const has = this.scsiDevices.length > 0
      console.log('hasScsiDevices:', has, 'count:', this.scsiDevices.length)
      return has
    }
  },
  mounted () {
    this.setCurrentTab()
  },
  methods: {
    setCurrentTab () {
      this.currentTab = this.$route.query.tab ? this.$route.query.tab : 'details'
    },
    async fetchData () {
      this.annotations = []
      if (!this.vm || !this.vm.id) return
      api('listAnnotations', { entityid: this.dataResource.id, entitytype: 'VM', annotationfilter: 'all' }).then(json => {
        if (json.listannotationsresponse && json.listannotationsresponse.annotation) {
          this.annotations = json.listannotationsresponse.annotation
        }
      })
      api('listNetworks', { supportedservices: 'SecurityGroup' }).then(json => {
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
            api('listVirtualMachines', listVmParams).then(json => {
              if (json.listvirtualmachinesresponse && json.listvirtualmachinesresponse?.virtualmachine?.length > 0) {
                this.securityGroupNetworkProviderUseThisVM = true
              }
            })
          }
        }
      })

      console.log('Fetching device data for VM:', this.vm.name, 'State:', this.vm.state)

      await this.fetchPciDevices()
      await this.fetchUsbDevices()
      await this.fetchLunDevices()
      await this.fetchHbaDevices()
      await this.fetchVhbaDevices()
      await this.fetchScsiDevices()

      console.log('Device counts - PCI:', this.pciDevices.length, 'USB:', this.usbDevices.length, 'LUN:', this.lunDevices.length, 'HBA:', this.hbaDevices.length, 'VHBA:', this.vhbaDevices.length, 'SCSI:', this.scsiDevices.length)

      if (this.pciDevices.length > 0) {
        this.hostDeviceTabKey = 'pci'
      } else if (this.hbaDevices.length > 0) {
        this.hostDeviceTabKey = 'hba'
      } else if (this.vhbaDevices.length > 0) {
        this.hostDeviceTabKey = 'vhba'
      } else if (this.usbDevices.length > 0) {
        this.hostDeviceTabKey = 'usb'
      } else if (this.lunDevices.length > 0) {
        this.hostDeviceTabKey = 'lun'
      } else if (this.scsiDevices.length > 0) {
        this.hostDeviceTabKey = 'scsi'
      }
    },
    listDiskOfferings () {
      api('listDiskOfferings', {
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
    showAddMirVMModal () {
      this.showAddMirrorVMModal = true
    },
    closeModals () {
      this.showAddVolumeModal = false
      this.showUpdateSecurityGroupsModal = false
      this.showAddMirrorVMModal = false
      this.showRemoveMirrorVMModal = false
      this.showDrSimulationTestModal = false
    },
    updateSecurityGroupsSelection (securitygroupids) {
      this.securitygroupids = securitygroupids || []
    },
    updateSecurityGroups () {
      api('updateVirtualMachine', { id: this.vm.id, securitygroupids: this.securitygroupids.join(',') }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.closeModals()
        this.parentFetchData()
      })
    },
    DrSimulationTest () {
      this.showDrSimulationTestModal = true
    },
    removeMirror () {
      this.showRemoveMirrorVMModal = true
    },
    async handleChangeTab (activeKey) {
      if (activeKey === 'pcidevices') {
        await this.fetchPciDevices()
        await this.fetchUsbDevices()
        await this.fetchLunDevices()
        await this.fetchHbaDevices()
        await this.fetchVhbaDevices()
        await this.fetchScsiDevices()
      }
      if (this.currentTab !== activeKey) {
        this.currentTab = activeKey
      }
    },
    async fetchPciDevices () {
      this.pciDevices = []

      try {
        const vmNumericId = this.vm.instancename?.split('-')[2]
        if (!vmNumericId) {
          console.log('No VM numeric ID found, skipping PCI device fetch')
          return
        }

        console.log('Fetching PCI devices for VM ID:', vmNumericId, 'VM State:', this.vm.state)

        // VM이 정지된 상태에서는 모든 호스트에서 디바이스 할당 정보를 찾아야 함
        if (!this.vm.hostid) {
          console.log('VM is stopped, searching all hosts for device allocations')

          // 모든 호스트 목록 가져오기
          const hostsResponse = await api('listHosts', {})
          const hosts = hostsResponse?.listhostsresponse?.host || []
          console.log('Found hosts:', hosts.length)

          // 각 호스트에서 디바이스 할당 정보 확인
          for (const host of hosts) {
            try {
              const response = await api('listHostDevices', { id: host.id })
              const devices = response?.listhostdevicesresponse?.listhostdevices?.[0]

              if (devices && devices.vmallocations && devices.hostdevicesname && devices.hostdevicestext) {
                Object.entries(devices.vmallocations).forEach(([deviceName, vmId]) => {
                  if (vmId === vmNumericId) {
                    const deviceIndex = devices.hostdevicesname.findIndex(name => name === deviceName)
                    if (deviceIndex !== -1 && devices.hostdevicestext[deviceIndex]) {
                      this.pciDevices.push({
                        key: deviceName,
                        hostDevicesName: devices.hostdevicesname[deviceIndex],
                        hostDevicesText: devices.hostdevicestext[deviceIndex]
                      })
                      console.log('Added PCI device from host:', host.name, 'Device:', deviceName)
                    }
                  }
                })
              }
            } catch (error) {
              console.warn('Error checking host:', host.name, error.message)
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid:', this.vm.hostid)
          const response = await api('listHostDevices', { id: this.vm.hostid })
          const devices = response?.listhostdevicesresponse?.listhostdevices?.[0]

          if (devices && devices.vmallocations && devices.hostdevicesname && devices.hostdevicestext) {
            console.log('Found VM allocations:', devices.vmallocations)
            Object.entries(devices.vmallocations).forEach(([deviceName, vmId]) => {
              console.log('Checking device:', deviceName, 'VM ID:', vmId, 'Expected:', vmNumericId)
              if (vmId === vmNumericId) {
                const deviceIndex = devices.hostdevicesname.findIndex(name => name === deviceName)
                if (deviceIndex !== -1 && devices.hostdevicestext[deviceIndex]) {
                  this.pciDevices.push({
                    key: deviceName,
                    hostDevicesName: devices.hostdevicesname[deviceIndex],
                    hostDevicesText: devices.hostdevicestext[deviceIndex]
                  })
                  console.log('Added PCI device:', deviceName)
                }
              }
            })
          }
        }

        console.log('Total PCI devices found:', this.pciDevices.length)
      } catch (error) {
        console.warn('Error fetching PCI devices:', error.message)
      }
    },
    getVmNumericId () {
      if (!this.vm.instancename) return null
      const parts = this.vm.instancename.split('-')
      return parts.length > 2 ? parts[2] : null
    },
    async fetchUsbDevices () {
      this.usbDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          console.log('No VM numeric ID found, skipping USB device fetch')
          return
        }

        console.log('Fetching USB devices for VM ID:', vmNumericId, 'VM State:', this.vm.state)

        if (!this.vm.hostid) {
          console.log('VM is stopped, searching all hosts for USB device allocations')

          // 모든 호스트 목록 가져오기
          const hostsResponse = await api('listHosts', {})
          const hosts = hostsResponse?.listhostsresponse?.host || []
          console.log('Found hosts for USB search:', hosts.length)

          // 각 호스트에서 USB 디바이스 할당 정보 확인
          for (const host of hosts) {
            try {
              const usbRes = await api('listHostUsbDevices', { id: host.id })
              const usbData = usbRes?.listhostusbdevicesresponse?.listhostusbdevices?.[0]

              if (usbData && usbData.vmallocations && usbData.hostdevicesname && usbData.hostdevicestext) {
                for (const [devName, vmId] of Object.entries(usbData.vmallocations)) {
                  if (vmId && String(vmId) === String(vmNumericId)) {
                    const deviceIndex = usbData.hostdevicesname.indexOf(devName)
                    if (deviceIndex !== -1 && usbData.hostdevicestext[deviceIndex]) {
                      this.usbDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: usbData.hostdevicestext[deviceIndex]
                      })
                      console.log('Added USB device from host:', host.name, 'Device:', devName)
                    }
                  }
                }
              }
            } catch (error) {
              console.warn('Error checking USB devices on host:', host.name, error.message)
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid for USB:', this.vm.hostid)
          const usbRes = await api('listHostUsbDevices', { id: this.vm.hostid })
          const usbData = usbRes?.listhostusbdevicesresponse?.listhostusbdevices?.[0]

          if (usbData && usbData.vmallocations && usbData.hostdevicesname && usbData.hostdevicestext) {
            for (const [devName, vmId] of Object.entries(usbData.vmallocations)) {
              if (vmId && String(vmId) === String(vmNumericId)) {
                const deviceIndex = usbData.hostdevicesname.indexOf(devName)
                if (deviceIndex !== -1 && usbData.hostdevicestext[deviceIndex]) {
                  this.usbDevices.push({
                    hostDevicesName: devName,
                    hostDevicesText: usbData.hostdevicestext[deviceIndex]
                  })
                  console.log('Added USB device:', devName)
                }
              }
            }
          }
        }

        console.log('Total USB devices found:', this.usbDevices.length)
      } catch (error) {
        console.warn('Error fetching USB devices:', error.message)
      }
    },
    async fetchLunDevices () {
      this.lunDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          console.log('No VM numeric ID found, skipping LUN device fetch')
          return
        }

        console.log('Fetching LUN devices for VM ID:', vmNumericId, 'VM State:', this.vm.state)

        if (!this.vm.hostid) {
          console.log('VM is stopped, searching all hosts for LUN device allocations')

          // 모든 호스트 목록 가져오기
          const hostsResponse = await api('listHosts', {})
          const hosts = hostsResponse?.listhostsresponse?.host || []
          console.log('Found hosts for LUN search:', hosts.length)

          // 각 호스트에서 LUN 디바이스 할당 정보 확인
          for (const host of hosts) {
            try {
              const lunRes = await api('listHostLunDevices', { id: host.id })
              const lunData = lunRes?.listhostlundevicesresponse?.listhostlundevices?.[0]

              if (lunData && lunData.vmallocations && lunData.hostdevicesname && lunData.hostdevicestext) {
                for (const [devName, vmId] of Object.entries(lunData.vmallocations)) {
                  if (vmId && String(vmId) === String(vmNumericId)) {
                    const deviceIndex = lunData.hostdevicesname.indexOf(devName)
                    if (deviceIndex !== -1 && lunData.hostdevicestext[deviceIndex]) {
                      this.lunDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: lunData.hostdevicestext[deviceIndex]
                      })
                      console.log('Added LUN device from host:', host.name, 'Device:', devName)
                    }
                  }
                }
              }
            } catch (error) {
              console.warn('Error checking LUN devices on host:', host.name, error.message)
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid for LUN:', this.vm.hostid)
          const lunRes = await api('listHostLunDevices', { id: this.vm.hostid })
          const lunData = lunRes?.listhostlundevicesresponse?.listhostlundevices?.[0]

          if (lunData && lunData.vmallocations && lunData.hostdevicesname && lunData.hostdevicestext) {
            for (const [devName, vmId] of Object.entries(lunData.vmallocations)) {
              if (vmId && String(vmId) === String(vmNumericId)) {
                const deviceIndex = lunData.hostdevicesname.indexOf(devName)
                if (deviceIndex !== -1 && lunData.hostdevicestext[deviceIndex]) {
                  this.lunDevices.push({
                    hostDevicesName: devName,
                    hostDevicesText: lunData.hostdevicestext[deviceIndex]
                  })
                  console.log('Added LUN device:', devName)
                }
              }
            }
          }
        }

        console.log('Total LUN devices found:', this.lunDevices.length)
      } catch (error) {
        console.warn('Error fetching LUN devices:', error.message)
      }
    },
    async fetchHbaDevices () {
      this.hbaDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          console.log('No VM numeric ID found, skipping HBA device fetch')
          return
        }

        console.log('Fetching HBA devices for VM ID:', vmNumericId, 'VM State:', this.vm.state)

        if (!this.vm.hostid) {
          console.log('VM is stopped, searching all hosts for HBA device allocations')

          // 모든 호스트 목록 가져오기
          const hostsResponse = await api('listHosts', {})
          const hosts = hostsResponse?.listhostsresponse?.host || []
          console.log('Found hosts for HBA search:', hosts.length)

          // HBA API 지원 여부를 먼저 확인
          let hbaApiSupported = false
          for (const host of hosts) {
            try {
              const testRes = await api('listHostHbaDevices', { id: host.id })
              if (!testRes?.listhosthbadevicesresponse?.errorcode) {
                hbaApiSupported = true
                console.log(`HBA API is supported on host ${host.name}`)
                break
              }
            } catch (error) {
              if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
                console.log(`HBA API not supported on host ${host.name}, trying next host...`)
                continue
              }
            }
          }

          // HBA API가 지원되지 않으면 조기 종료
          if (!hbaApiSupported) {
            console.log('HBA API is not supported on any host, skipping HBA device search')
            return
          }

          // 각 호스트에서 HBA 디바이스 할당 정보 확인
          for (const host of hosts) {
            try {
              const hbaRes = await api('listHostHbaDevices', { id: host.id })

              // API 응답에서 에러 코드 확인
              if (hbaRes?.listhosthbadevicesresponse?.errorcode) {
                console.log(`HBA API not supported on host ${host.name}, skipping...`)
                continue
              }

              const hbaData = hbaRes?.listhosthbadevicesresponse?.listhosthbadevices?.[0]

              // 할당된 디바이스만 처리
              if (hbaData && hbaData.vmallocations && hbaData.hostdevicesname && hbaData.hostdevicestext) {
                for (const [devName, vmId] of Object.entries(hbaData.vmallocations)) {
                  // VM ID가 있고 현재 VM과 일치하는 경우만 처리
                  if (vmId && String(vmId) === String(vmNumericId)) {
                    const deviceIndex = hbaData.hostdevicesname.indexOf(devName)
                    if (deviceIndex !== -1 && hbaData.hostdevicestext[deviceIndex]) {
                      this.hbaDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: hbaData.hostdevicestext[deviceIndex]
                      })
                      console.log('Added HBA device from host:', host.name, 'Device:', devName)
                    }
                  }
                }
              }
            } catch (error) {
              // API가 지원되지 않는 경우 조용히 건너뛰기
              if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
                console.log(`HBA devices not supported on host ${host.name}, skipping...`)
              } else {
                console.warn('Error checking HBA devices on host:', host.name, error.message)
              }
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid for HBA:', this.vm.hostid)
          try {
            const hbaRes = await api('listHostHbaDevices', { id: this.vm.hostid })

            // API 응답에서 에러 코드 확인
            if (hbaRes?.listhosthbadevicesresponse?.errorcode) {
              console.log('HBA API not supported, skipping...')
              return
            }

            const hbaData = hbaRes?.listhosthbadevicesresponse?.listhosthbadevices?.[0]

            // 할당된 디바이스만 처리
            if (hbaData && hbaData.vmallocations && hbaData.hostdevicesname && hbaData.hostdevicestext) {
              for (const [devName, vmId] of Object.entries(hbaData.vmallocations)) {
                // VM ID가 있고 현재 VM과 일치하는 경우만 처리
                if (vmId && String(vmId) === String(vmNumericId)) {
                  const deviceIndex = hbaData.hostdevicesname.indexOf(devName)
                  if (deviceIndex !== -1 && hbaData.hostdevicestext[deviceIndex]) {
                    this.hbaDevices.push({
                      hostDevicesName: devName,
                      hostDevicesText: hbaData.hostdevicestext[deviceIndex]
                    })
                    console.log('Added HBA device:', devName)
                  }
                }
              }
            }
          } catch (error) {
            // API가 지원되지 않는 경우 조용히 건너뛰기
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
              console.log('HBA devices not supported, skipping...')
            } else {
              console.warn('Error fetching HBA devices:', error.message)
            }
          }
        }

        console.log('Total HBA devices found:', this.hbaDevices.length)
      } catch (error) {
        console.warn('Error in fetchHbaDevices:', error.message)
      }
    },
    async fetchVhbaDevices () {
      this.vhbaDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          console.log('No VM numeric ID found, skipping VHBA device fetch')
          return
        }

        console.log('Fetching VHBA devices for VM ID:', vmNumericId, 'VM State:', this.vm.state)

        if (!this.vm.hostid) {
          console.log('VM is stopped, searching all hosts for VHBA device allocations')

          // 모든 호스트 목록 가져오기
          const hostsResponse = await api('listHosts', {})
          const hosts = hostsResponse?.listhostsresponse?.host || []
          console.log('Found hosts for VHBA search:', hosts.length)

          // VHBA API 지원 여부를 먼저 확인
          let vhbaApiSupported = false
          for (const host of hosts) {
            try {
              const testRes = await api('listHostVhbaDevices', { id: host.id })
              if (!testRes?.listhostvhbadevicesresponse?.errorcode) {
                vhbaApiSupported = true
                console.log(`VHBA API is supported on host ${host.name}`)
                break
              }
            } catch (error) {
              if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
                console.log(`VHBA API not supported on host ${host.name}, trying next host...`)
                continue
              }
            }
          }

          // VHBA API가 지원되지 않으면 조기 종료
          if (!vhbaApiSupported) {
            console.log('VHBA API is not supported on any host, skipping VHBA device search')
            return
          }

          // 각 호스트에서 VHBA 디바이스 할당 정보 확인
          for (const host of hosts) {
            try {
              const vhbaRes = await api('listHostVhbaDevices', { id: host.id })

              // API 응답에서 에러 코드 확인
              if (vhbaRes?.listhostvhbadevicesresponse?.errorcode) {
                console.log(`VHBA API not supported on host ${host.name}, skipping...`)
                continue
              }

              const vhbaData = vhbaRes?.listhostvhbadevicesresponse?.listhostvhbadevices?.[0]

              // 할당된 디바이스만 처리
              if (vhbaData && vhbaData.vmallocations && vhbaData.hostdevicesname && vhbaData.hostdevicestext) {
                for (const [devName, vmId] of Object.entries(vhbaData.vmallocations)) {
                  // VM ID가 있고 현재 VM과 일치하는 경우만 처리
                  if (vmId && String(vmId) === String(vmNumericId)) {
                    const deviceIndex = vhbaData.hostdevicesname.indexOf(devName)
                    if (deviceIndex !== -1 && vhbaData.hostdevicestext[deviceIndex]) {
                      this.vhbaDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: vhbaData.hostdevicestext[deviceIndex]
                      })
                      console.log('Added VHBA device from host:', host.name, 'Device:', devName)
                    }
                  }
                }
              }
            } catch (error) {
              // API가 지원되지 않는 경우 조용히 건너뛰기
              if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
                console.log(`VHBA devices not supported on host ${host.name}, skipping...`)
              } else {
                console.warn('Error checking VHBA devices on host:', host.name, error.message)
              }
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid for VHBA:', this.vm.hostid)
          try {
            const vhbaRes = await api('listHostVhbaDevices', { id: this.vm.hostid })

            // API 응답에서 에러 코드 확인
            if (vhbaRes?.listhostvhbadevicesresponse?.errorcode) {
              console.log('VHBA API not supported, skipping...')
              return
            }

            const vhbaData = vhbaRes?.listhostvhbadevicesresponse?.listhostvhbadevices?.[0]

            // 할당된 디바이스만 처리
            if (vhbaData && vhbaData.vmallocations && vhbaData.hostdevicesname && vhbaData.hostdevicestext) {
              for (const [devName, vmId] of Object.entries(vhbaData.vmallocations)) {
                // VM ID가 있고 현재 VM과 일치하는 경우만 처리
                if (vmId && String(vmId) === String(vmNumericId)) {
                  const deviceIndex = vhbaData.hostdevicesname.indexOf(devName)
                  if (deviceIndex !== -1 && vhbaData.hostdevicestext[deviceIndex]) {
                    this.vhbaDevices.push({
                      hostDevicesName: devName,
                      hostDevicesText: vhbaData.hostdevicestext[deviceIndex]
                    })
                    console.log('Added VHBA device:', devName)
                  }
                }
              }
            }
          } catch (error) {
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
              console.log('VHBA devices not supported, skipping...')
            } else {
              console.warn('Error fetching VHBA devices:', error.message)
            }
          }
        }

        console.log('Total VHBA devices found:', this.vhbaDevices.length)
      } catch (error) {
        console.warn('Error in fetchVhbaDevices:', error.message)
      }
    },
    async fetchScsiDevices () {
      this.scsiDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          console.log('No VM numeric ID found, skipping SCSI device fetch')
          return
        }

        console.log('Fetching SCSI devices for VM ID:', vmNumericId, 'VM State:', this.vm.state)

        // VM이 정지된 상태에서는 모든 호스트에서 디바이스 할당 정보를 찾아야 함
        if (!this.vm.hostid) {
          console.log('VM is stopped, searching all hosts for SCSI device allocations')

          // 모든 호스트 목록 가져오기
          const hostsResponse = await api('listHosts', {})
          const hosts = hostsResponse?.listhostsresponse?.host || []
          console.log('Found hosts for SCSI search:', hosts.length)

          // SCSI API 지원 여부를 먼저 확인
          let scsiApiSupported = false
          for (const host of hosts) {
            try {
              const testRes = await api('listHostScsiDevices', { id: host.id })
              if (!testRes?.listhostscsidevicesresponse?.errorcode) {
                scsiApiSupported = true
                console.log(`SCSI API is supported on host ${host.name}`)
                break
              }
            } catch (error) {
              if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
                console.log(`SCSI API not supported on host ${host.name}, trying next host...`)
                continue
              }
            }
          }

          // SCSI API가 지원되지 않으면 조기 종료
          if (!scsiApiSupported) {
            console.log('SCSI API is not supported on any host, skipping SCSI device search')
            return
          }

          // 각 호스트에서 SCSI 디바이스 할당 정보 확인
          for (const host of hosts) {
            try {
              const scsiRes = await api('listHostScsiDevices', { id: host.id })

              // API 응답에서 에러 코드 확인
              if (scsiRes?.listhostscsidevicesresponse?.errorcode) {
                console.log(`SCSI API not supported on host ${host.name}, skipping...`)
                continue
              }

              const scsiData = scsiRes?.listhostscsidevicesresponse?.listhostscsidevices?.[0]

              // 할당된 디바이스만 처리
              if (scsiData && scsiData.vmallocations && scsiData.hostdevicesname && scsiData.hostdevicestext) {
                for (const [devName, vmId] of Object.entries(scsiData.vmallocations)) {
                  // VM ID가 있고 현재 VM과 일치하는 경우만 처리
                  if (vmId && String(vmId) === String(vmNumericId)) {
                    const deviceIndex = scsiData.hostdevicesname.indexOf(devName)
                    if (deviceIndex !== -1 && scsiData.hostdevicestext[deviceIndex]) {
                      this.scsiDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: scsiData.hostdevicestext[deviceIndex]
                      })
                      console.log('Added SCSI device from host:', host.name, 'Device:', devName)
                    }
                  }
                }
              }
            } catch (error) {
              // API가 지원되지 않는 경우 조용히 건너뛰기
              if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
                console.log(`SCSI devices not supported on host ${host.name}, skipping...`)
              } else {
                console.warn('Error checking SCSI devices on host:', host.name, error.message)
              }
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid for SCSI:', this.vm.hostid)
          try {
            const scsiRes = await api('listHostScsiDevices', { id: this.vm.hostid })

            // API 응답에서 에러 코드 확인
            if (scsiRes?.listhostscsidevicesresponse?.errorcode) {
              console.log('SCSI API not supported, skipping...')
              return
            }

            const scsiData = scsiRes?.listhostscsidevicesresponse?.listhostscsidevices?.[0]

            // 할당된 디바이스만 처리
            if (scsiData && scsiData.vmallocations && scsiData.hostdevicesname && scsiData.hostdevicestext) {
              for (const [devName, vmId] of Object.entries(scsiData.vmallocations)) {
                // VM ID가 있고 현재 VM과 일치하는 경우만 처리
                if (vmId && String(vmId) === String(vmNumericId)) {
                  const deviceIndex = scsiData.hostdevicesname.indexOf(devName)
                  if (deviceIndex !== -1 && scsiData.hostdevicestext[deviceIndex]) {
                    this.scsiDevices.push({
                      hostDevicesName: devName,
                      hostDevicesText: scsiData.hostdevicestext[deviceIndex]
                    })
                    console.log('Added SCSI device:', devName)
                  }
                }
              }
            }
          } catch (error) {
            // API가 지원되지 않는 경우 조용히 건너뛰기
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
              console.log('SCSI devices not supported, skipping...')
            } else {
              console.warn('Error fetching SCSI devices:', error.message)
            }
          }
        }

        console.log('Total SCSI devices found:', this.scsiDevices.length)
      } catch (error) {
        console.warn('Error in fetchScsiDevices:', error.message)
      }
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
</style>
