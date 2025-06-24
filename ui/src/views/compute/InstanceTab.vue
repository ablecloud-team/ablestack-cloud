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
        v-if="hasPciDevices || hasUsbDevices || hasLunDevices || hasHbaDevices"
      >
        <a-tabs v-model:activeKey="hostDeviceTabKey" style="margin-bottom: 16px;">
          <a-tab-pane v-if="pciDevices.length > 0" key="pci" :tab="$t('label.other.devices')">
            <a-table :columns="pciColumns" :dataSource="pciDevices" :pagination="false" :loading="loading" />
          </a-tab-pane>
          <a-tab-pane v-if="usbDevices.length > 0" key="usb" :tab="$t('label.usb.devices')">
            <a-table :columns="usbColumns" :dataSource="usbDevices" :pagination="false" :loading="loading" />
          </a-tab-pane>
          <a-tab-pane v-if="lunDevices.length > 0" key="lun" :tab="$t('label.lun.devices')">
            <a-table :columns="lunColumns" :dataSource="lunDevices" :pagination="false" :loading="loading" />
          </a-tab-pane>
          <a-tab-pane v-if="hbaDevices.length > 0" key="hba" :tab="$t('label.hba.devices')">
            <a-table :columns="hbaColumns" :dataSource="hbaDevices" :pagination="false" :loading="loading" />
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
      pciColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName'
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText'
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
          key: 'hostDevicesText'
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
          key: 'hostDevicesText'
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
          key: 'hostDevicesText'
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

      console.log('Device counts - PCI:', this.pciDevices.length, 'USB:', this.usbDevices.length, 'LUN:', this.lunDevices.length, 'HBA:', this.hbaDevices.length)

      if (this.pciDevices.length > 0) {
        this.hostDeviceTabKey = 'pci'
      } else if (this.usbDevices.length > 0) {
        this.hostDeviceTabKey = 'usb'
      } else if (this.lunDevices.length > 0) {
        this.hostDeviceTabKey = 'lun'
      } else if (this.hbaDevices.length > 0) {
        this.hostDeviceTabKey = 'hba'
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
      }
      if (this.currentTab !== activeKey) {
        this.currentTab = activeKey
      }
    },
    async fetchPciDevices () {
      this.pciDevices = []

      try {
        const vmNumericId = this.vm.instancename.split('-')[2]
        if (!vmNumericId) {
          console.error('Failed to get VM numeric ID')
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

              if (devices && devices.vmallocations) {
                Object.entries(devices.vmallocations).forEach(([deviceName, vmId]) => {
                  if (vmId === vmNumericId) {
                    const deviceIndex = devices.hostdevicesname.findIndex(name => name === deviceName)
                    if (deviceIndex !== -1) {
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
              console.error('Error checking host:', host.name, error)
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid:', this.vm.hostid)
          const response = await api('listHostDevices', { id: this.vm.hostid })
          const devices = response?.listhostdevicesresponse?.listhostdevices?.[0]

          if (devices && devices.vmallocations) {
            console.log('Found VM allocations:', devices.vmallocations)
            Object.entries(devices.vmallocations).forEach(([deviceName, vmId]) => {
              console.log('Checking device:', deviceName, 'VM ID:', vmId, 'Expected:', vmNumericId)
              if (vmId === vmNumericId) {
                const deviceIndex = devices.hostdevicesname.findIndex(name => name === deviceName)
                if (deviceIndex !== -1) {
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
        console.error('Error fetching PCI devices:', error)
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
          console.error('Failed to get VM numeric ID for USB devices')
          return
        }

        console.log('Fetching USB devices for VM ID:', vmNumericId, 'VM State:', this.vm.state)

        // VM이 정지된 상태에서는 모든 호스트에서 디바이스 할당 정보를 찾아야 함
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

              if (usbData && usbData.vmallocations) {
                for (const [devName, vmId] of Object.entries(usbData.vmallocations)) {
                  if (vmId && String(vmId) === String(vmNumericId)) {
                    this.usbDevices.push({
                      hostDevicesName: devName,
                      hostDevicesText: usbData.hostdevicestext[usbData.hostdevicesname.indexOf(devName)]
                    })
                    console.log('Added USB device from host:', host.name, 'Device:', devName)
                  }
                }
              }
            } catch (error) {
              console.error('Error checking USB devices on host:', host.name, error)
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid for USB:', this.vm.hostid)
          const usbRes = await api('listHostUsbDevices', { id: this.vm.hostid })
          const usbData = usbRes?.listhostusbdevicesresponse?.listhostusbdevices?.[0]

          if (usbData && usbData.vmallocations) {
            for (const [devName, vmId] of Object.entries(usbData.vmallocations)) {
              if (vmId && String(vmId) === String(vmNumericId)) {
                this.usbDevices.push({
                  hostDevicesName: devName,
                  hostDevicesText: usbData.hostdevicestext[usbData.hostdevicesname.indexOf(devName)]
                })
                console.log('Added USB device:', devName)
              }
            }
          }
        }

        console.log('Total USB devices found:', this.usbDevices.length)
      } catch (error) {
        console.error('Error fetching USB devices:', error)
      }
    },
    async fetchLunDevices () {
      this.lunDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          console.error('Failed to get VM numeric ID for LUN devices')
          return
        }

        console.log('Fetching LUN devices for VM ID:', vmNumericId, 'VM State:', this.vm.state)

        // VM이 정지된 상태에서는 모든 호스트에서 디바이스 할당 정보를 찾아야 함
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

              if (lunData && lunData.vmallocations) {
                for (const [devName, vmId] of Object.entries(lunData.vmallocations)) {
                  if (vmId && String(vmId) === String(vmNumericId)) {
                    this.lunDevices.push({
                      hostDevicesName: devName,
                      hostDevicesText: lunData.hostdevicestext[lunData.hostdevicesname.indexOf(devName)]
                    })
                    console.log('Added LUN device from host:', host.name, 'Device:', devName)
                  }
                }
              }
            } catch (error) {
              console.error('Error checking LUN devices on host:', host.name, error)
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid for LUN:', this.vm.hostid)
          const lunRes = await api('listHostLunDevices', { id: this.vm.hostid })
          const lunData = lunRes?.listhostlundevicesresponse?.listhostlundevices?.[0]

          if (lunData && lunData.vmallocations) {
            for (const [devName, vmId] of Object.entries(lunData.vmallocations)) {
              if (vmId && String(vmId) === String(vmNumericId)) {
                this.lunDevices.push({
                  hostDevicesName: devName,
                  hostDevicesText: lunData.hostdevicestext[lunData.hostdevicesname.indexOf(devName)]
                })
                console.log('Added LUN device:', devName)
              }
            }
          }
        }

        console.log('Total LUN devices found:', this.lunDevices.length)
      } catch (error) {
        console.error('Error fetching LUN devices:', error)
      }
    },
    async fetchHbaDevices () {
      this.hbaDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          console.error('Failed to get VM numeric ID for HBA devices')
          return
        }

        console.log('Fetching HBA devices for VM ID:', vmNumericId, 'VM State:', this.vm.state)

        // VM이 정지된 상태에서는 모든 호스트에서 디바이스 할당 정보를 찾아야 함
        if (!this.vm.hostid) {
          console.log('VM is stopped, searching all hosts for HBA device allocations')

          // 모든 호스트 목록 가져오기
          const hostsResponse = await api('listHosts', {})
          const hosts = hostsResponse?.listhostsresponse?.host || []
          console.log('Found hosts for HBA search:', hosts.length)

          // 각 호스트에서 HBA 디바이스 할당 정보 확인
          for (const host of hosts) {
            try {
              const hbaRes = await api('listHostHbaDevices', { id: host.id })
              const hbaData = hbaRes?.listhosthbadevicesresponse?.listhosthbadevices?.[0]

              if (hbaData && hbaData.vmallocations) {
                for (const [devName, vmId] of Object.entries(hbaData.vmallocations)) {
                  if (vmId && String(vmId) === String(vmNumericId)) {
                    this.hbaDevices.push({
                      hostDevicesName: devName,
                      hostDevicesText: hbaData.hostdevicestext[hbaData.hostdevicesname.indexOf(devName)]
                    })
                    console.log('Added HBA device from host:', host.name, 'Device:', devName)
                  }
                }
              }
            } catch (error) {
              console.error('Error checking HBA devices on host:', host.name, error)
            }
          }
        } else {
          // VM이 실행 중인 경우 기존 방식 사용
          console.log('VM is running, using hostid for HBA:', this.vm.hostid)
          const hbaRes = await api('listHostHbaDevices', { id: this.vm.hostid })
          const hbaData = hbaRes?.listhosthbadevicesresponse?.listhosthbadevices?.[0]

          if (hbaData && hbaData.vmallocations) {
            for (const [devName, vmId] of Object.entries(hbaData.vmallocations)) {
              if (vmId && String(vmId) === String(vmNumericId)) {
                this.hbaDevices.push({
                  hostDevicesName: devName,
                  hostDevicesText: hbaData.hostdevicestext[hbaData.hostdevicesname.indexOf(devName)]
                })
                console.log('Added HBA device:', devName)
              }
            }
          }
        }

        console.log('Total HBA devices found:', this.hbaDevices.length)
      } catch (error) {
        console.error('Error fetching HBA devices:', error)
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
