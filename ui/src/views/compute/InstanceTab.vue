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
          :columns="['name', 'status', 'size', 'virtualsize', 'type', 'intervaltype', 'created']"
          :routerlinks="(record) => { return { name: '/backup/' + record.id } }"
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
              size="small"
              :loading="loading" />
          </div>

          <!-- SCSI 디바이스 -->
          <div class="device-section">
            <h3 class="section-title">{{ $t('label.scsi.devices') }}</h3>
            <a-table
              :columns="deviceColumns"
              :dataSource="scsiDevices"
              :pagination="false"
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
import GPUTab from '@/components/view/GPUTab.vue'

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
    GPUTab,
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
      showUpdateSecurityGroupsModal: false,
      showAddVolumeModal: false,
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
      // 디바이스 데이터 캐싱을 위한 플래그들
      devicesLoaded: false,
      deviceLoadingStates: {
        pci: false,
        usb: false,
        lun: false,
        hba: false,
        vhba: false,
        scsi: false
      },
      deviceColumns: [
        {
          title: this.$t('label.name'),
          dataIndex: 'hostDevicesName',
          key: 'hostDevicesName',
          customRender: ({ text }) => {
            return h('div', { style: 'white-space: pre-line; line-height: 1.4; min-height: 50px; padding-top: 8px;' }, this.formatDeviceName(text))
          }
        },
        {
          title: this.$t('label.details'),
          dataIndex: 'hostDevicesText',
          key: 'hostDevicesText',
          customRender: ({ text }) => {
            return h('div', { style: 'white-space: pre-wrap; word-break: break-word; min-height: 40px;' }, text)
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
          const newHostId = newData?.hostid

          this.dataResource = newData
          this.vm = this.dataResource

          // 호스트가 변경되었거나 리소스가 변경되면 디바이스 캐시 초기화
          if (oldHostId !== newHostId || !this.devicesLoaded) {
            this.resetDeviceCache()
            this.fetchData()
          }
        }
      }
    },
    '$route.fullPath': function () {
      this.setCurrentTab()
    }
  },
  computed: {
  },
  mounted () {
    this.setCurrentTab()
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
    setCurrentTab () {
      this.currentTab = this.$route.query.tab ? this.$route.query.tab : 'details'
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
        // 모든 디바이스 타입을 병렬로 로드
        await Promise.all([
          this.fetchPciDevices(),
          this.fetchUsbDevices(),
          this.fetchLunDevices(),
          this.fetchHbaDevices(),
          this.fetchVhbaDevices(),
          this.fetchScsiDevices()
        ])

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
      postAPI('updateVirtualMachine', { id: this.vm.id, securitygroupids: this.securitygroupids.join(',') }).catch(error => {
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
      // 디바이스 탭으로 변경될 때만 필요한 경우에만 데이터 로드
      if (activeKey === 'hostdevices') {
        // 디바이스 탭이 처음 열릴 때 또는 마이그레이션으로 인해 장치 데이터가 없는 경우 데이터 로드
        if (!this.devicesLoaded) {
          await this.fetchData()
        }
      }

      if (this.currentTab !== activeKey) {
        this.currentTab = activeKey

        // URL 쿼리 파라미터 업데이트
        const query = Object.assign({}, this.$route.query)
        query.tab = activeKey
        const queryString = Object.keys(query).map(key => {
          return encodeURIComponent(key) + '=' + encodeURIComponent(query[key])
        }).join('&')

        history.pushState({}, null, '#' + this.$route.path + '?' + queryString)
      }
    },
    resetDeviceCache () {
      this.devicesLoaded = false
      this.deviceLoadingStates = {
        pci: false,
        usb: false,
        lun: false,
        hba: false,
        vhba: false,
        scsi: false
      }
      // 장치 데이터 초기화
      this.pciDevices = []
      this.usbDevices = []
      this.lunDevices = []
      this.hbaDevices = []
      this.vhbaDevices = []
      this.scsiDevices = []
    },
    async fetchPciDevices () {
      if (this.deviceLoadingStates.pci) return

      this.deviceLoadingStates.pci = true
      this.pciDevices = []

      try {
        const vmNumericId = this.vm.instancename?.split('-')[2]
        if (!vmNumericId) {
          return
        }

        // 모든 호스트 목록 가져오기
        const hostsResponse = await getAPI('listHosts', {})
        const hosts = hostsResponse?.listhostsresponse?.host || []

        // 각 호스트에서 디바이스 할당 정보 확인
        for (const host of hosts) {
          try {
            const response = await getAPI('listHostDevices', { id: host.id })

            // API 응답 에러 확인 (530 에러는 지원되지 않는 명령)
            if (response?.listhostdevicesresponse?.errorcode === 530) {
              continue
            }
            if (response?.listhostdevicesresponse?.errorcode) {
              continue
            }

            const devices = response?.listhostdevicesresponse?.listhostdevices?.[0]

            if (devices && devices.vmallocations && devices.hostdevicesname && devices.hostdevicestext) {
              Object.entries(devices.vmallocations).forEach(([deviceName, vmId]) => {
                if (vmId === vmNumericId) {
                  const deviceIndex = devices.hostdevicesname.findIndex(name => name === deviceName)
                  if (deviceIndex !== -1 && devices.hostdevicestext[deviceIndex]) {
                    // 중복 디바이스 방지
                    const existingDevice = this.pciDevices.find(device => device.hostDevicesName === deviceName)
                    if (!existingDevice) {
                      this.pciDevices.push({
                        key: `${host.id}-${deviceName}`,
                        hostDevicesName: devices.hostdevicesname[deviceIndex],
                        hostDevicesText: devices.hostdevicestext[deviceIndex]
                      })
                    }
                  }
                }
              })
            }
          } catch (error) {
            // API가 지원되지 않거나 에러가 발생한 경우 조용히 건너뛰기
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
            } else {
              console.warn('Error checking PCI devices on host:', host.name, error.message)
            }
          }
        }
      } catch (error) {
        console.warn('Error in fetchPciDevices:', error.message)
      } finally {
        this.deviceLoadingStates.pci = false
      }
    },
    getVmNumericId () {
      if (!this.vm.instancename) return null
      const parts = this.vm.instancename.split('-')
      return parts.length > 2 ? parts[2] : null
    },
    async fetchUsbDevices () {
      if (this.deviceLoadingStates.usb) return

      this.deviceLoadingStates.usb = true
      this.usbDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          return
        }

        // 모든 호스트 목록 가져오기
        const hostsResponse = await getAPI('listHosts', {})
        const hosts = hostsResponse?.listhostsresponse?.host || []

        // 각 호스트에서 USB 디바이스 할당 정보 확인
        for (const host of hosts) {
          try {
            const usbRes = await getAPI('listHostUsbDevices', { id: host.id })

            // API 응답 에러 확인 (530 에러는 지원되지 않는 명령)
            if (usbRes?.listhostusbdevicesresponse?.errorcode === 530) {
              continue
            }
            if (usbRes?.listhostusbdevicesresponse?.errorcode) {
              continue
            }

            const usbData = usbRes?.listhostusbdevicesresponse?.listhostusbdevices?.[0]

            if (usbData && usbData.vmallocations && usbData.hostdevicesname && usbData.hostdevicestext) {
              for (const [devName, vmId] of Object.entries(usbData.vmallocations)) {
                if (vmId && String(vmId) === String(vmNumericId)) {
                  const deviceIndex = usbData.hostdevicesname.indexOf(devName)
                  if (deviceIndex !== -1 && usbData.hostdevicestext[deviceIndex]) {
                    // 중복 디바이스 방지
                    const existingDevice = this.usbDevices.find(device => device.hostDevicesName === devName)
                    if (!existingDevice) {
                      this.usbDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: usbData.hostdevicestext[deviceIndex]
                      })
                    }
                  }
                }
              }
            }
          } catch (error) {
            // API가 지원되지 않거나 에러가 발생한 경우 조용히 건너뛰기
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
            } else {
              console.warn('Error checking USB devices on host:', host.name, error.message)
            }
          }
        }
      } catch (error) {
        console.warn('Error in fetchUsbDevices:', error.message)
      } finally {
        this.deviceLoadingStates.usb = false
      }
    },
    async fetchLunDevices () {
      if (this.deviceLoadingStates.lun) return

      this.deviceLoadingStates.lun = true
      this.lunDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          return
        }

        // 모든 호스트 목록 가져오기
        const hostsResponse = await getAPI('listHosts', {})
        const hosts = hostsResponse?.listhostsresponse?.host || []

        // 각 호스트에서 LUN 디바이스 할당 정보 확인
        for (const host of hosts) {
          try {
            const lunRes = await getAPI('listHostLunDevices', { id: host.id })

            // API 응답 에러 확인 (530 에러는 지원되지 않는 명령)
            if (lunRes?.listhostlundevicesresponse?.errorcode === 530) {
              continue
            }
            if (lunRes?.listhostlundevicesresponse?.errorcode) {
              continue
            }

            const lunData = lunRes?.listhostlundevicesresponse?.listhostlundevices?.[0]

            if (lunData && lunData.vmallocations && lunData.hostdevicesname && lunData.hostdevicestext) {
              for (const [devName, vmId] of Object.entries(lunData.vmallocations)) {
                if (vmId && String(vmId) === String(vmNumericId)) {
                  const deviceIndex = lunData.hostdevicesname.indexOf(devName)
                  if (deviceIndex !== -1 && lunData.hostdevicestext[deviceIndex]) {
                    // 중복 디바이스 방지
                    const existingDevice = this.lunDevices.find(device => device.hostDevicesName === devName)
                    if (!existingDevice) {
                      this.lunDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: lunData.hostdevicestext[deviceIndex]
                      })
                    }
                  }
                }
              }
            }
          } catch (error) {
            // API가 지원되지 않거나 에러가 발생한 경우 조용히 건너뛰기
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
            } else {
              console.warn('Error checking LUN devices on host:', host.name, error.message)
            }
          }
        }
      } catch (error) {
        console.warn('Error fetching LUN devices:', error.message)
      } finally {
        this.deviceLoadingStates.lun = false
      }
    },
    async fetchHbaDevices () {
      if (this.deviceLoadingStates.hba) return

      this.deviceLoadingStates.hba = true
      this.hbaDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          return
        }

        // VM이 실제로 가지고 있는 모든 HBA 디바이스를 찾기 위해 모든 호스트를 검색

        // 모든 호스트 목록 가져오기
        const hostsResponse = await getAPI('listHosts', {})
        const hosts = hostsResponse?.listhostsresponse?.host || []

        // HBA API 지원 여부를 먼저 확인
        let hbaApiSupported = false
        for (const host of hosts) {
          try {
            const testRes = await getAPI('listHostHbaDevices', { id: host.id })
            if (!testRes?.listhosthbadevicesresponse?.errorcode) {
              hbaApiSupported = true
              break
            }
          } catch (error) {
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
              continue
            }
          }
        }

        // HBA API가 지원되지 않으면 조기 종료
        if (!hbaApiSupported) {
          return
        }

        // 각 호스트에서 HBA 디바이스 할당 정보 확인
        for (const host of hosts) {
          try {
            const hbaRes = await getAPI('listHostHbaDevices', { id: host.id })

            // API 응답에서 에러 코드 확인 (530 에러는 지원되지 않는 명령)
            if (hbaRes?.listhosthbadevicesresponse?.errorcode === 530) {
              continue
            }
            if (hbaRes?.listhosthbadevicesresponse?.errorcode) {
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
                    // 중복 디바이스 방지
                    const existingDevice = this.hbaDevices.find(device => device.hostDevicesName === devName)
                    if (!existingDevice) {
                      this.hbaDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: hbaData.hostdevicestext[deviceIndex]
                      })
                    }
                  }
                }
              }
            }
          } catch (error) {
            // API가 지원되지 않는 경우 조용히 건너뛰기
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
            } else {
              console.warn('Error checking HBA devices on host:', host.name, error.message)
            }
          }
        }
      } catch (error) {
        console.warn('Error in fetchHbaDevices:', error.message)
      } finally {
        this.deviceLoadingStates.hba = false
      }
    },
    async fetchVhbaDevices () {
      if (this.deviceLoadingStates.vhba) return

      this.deviceLoadingStates.vhba = true
      this.vhbaDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          return
        }

        // VM이 실제로 가지고 있는 모든 VHBA 디바이스를 찾기 위해 모든 호스트를 검색

        // 모든 호스트 목록 가져오기
        const hostsResponse = await getAPI('listHosts', {})
        const hosts = hostsResponse?.listhostsresponse?.host || []

        // VHBA API 지원 여부를 먼저 확인
        let vhbaApiSupported = false
        for (const host of hosts) {
          try {
            const testRes = await getAPI('listVhbaDevices', { hostid: host.id })
            if (!testRes?.listvhbadevicesresponse?.errorcode) {
              vhbaApiSupported = true
              break
            }
          } catch (error) {
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
              continue
            }
          }
        }

        // VHBA API가 지원되지 않으면 조기 종료
        if (!vhbaApiSupported) {
          return
        }

        // 각 호스트에서 VHBA 디바이스 할당 정보 확인
        for (const host of hosts) {
          try {
            const vhbaRes = await getAPI('listVhbaDevices', { hostid: host.id })

            // API 응답에서 에러 코드 확인 (530 에러는 지원되지 않는 명령)
            if (vhbaRes?.listvhbadevicesresponse?.errorcode === 530) {
              continue
            }
            if (vhbaRes?.listvhbadevicesresponse?.errorcode) {
              continue
            }

            const vhbaData = vhbaRes?.listvhbadevicesresponse?.listvhbadevices?.[0]

            // 할당된 디바이스만 처리
            if (vhbaData && vhbaData.vmallocations && vhbaData.hostdevicesname && vhbaData.hostdevicestext) {
              for (const [devName, vmId] of Object.entries(vhbaData.vmallocations)) {
                // VM ID가 있고 현재 VM과 일치하는 경우만 처리
                if (vmId && String(vmId) === String(vmNumericId)) {
                  const deviceIndex = vhbaData.hostdevicesname.indexOf(devName)
                  if (deviceIndex !== -1 && vhbaData.hostdevicestext[deviceIndex]) {
                    // 중복 디바이스 방지
                    const existingDevice = this.vhbaDevices.find(device => device.hostDevicesName === devName)
                    if (!existingDevice) {
                      this.vhbaDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: vhbaData.hostdevicestext[deviceIndex]
                      })
                    }
                  }
                }
              }
            }
          } catch (error) {
            // API가 지원되지 않는 경우 조용히 건너뛰기
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
            } else {
              console.warn('Error checking VHBA devices on host:', host.name, error.message)
            }
          }
        }
      } catch (error) {
        console.warn('Error in fetchVhbaDevices:', error.message)
      } finally {
        this.deviceLoadingStates.vhba = false
      }
    },
    async fetchScsiDevices () {
      if (this.deviceLoadingStates.scsi) return

      this.deviceLoadingStates.scsi = true
      this.scsiDevices = []

      try {
        const vmNumericId = this.getVmNumericId()
        if (!vmNumericId) {
          return
        }

        // 모든 호스트 목록 가져오기
        const hostsResponse = await getAPI('listHosts', {})
        const hosts = hostsResponse?.listhostsresponse?.host || []

        // SCSI API 지원 여부를 먼저 확인
        let scsiApiSupported = false
        for (const host of hosts) {
          try {
            const testRes = await getAPI('listHostScsiDevices', { id: host.id })
            if (!testRes?.listhostscsidevicesresponse?.errorcode) {
              scsiApiSupported = true
              break
            }
          } catch (error) {
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
              continue
            }
          }
        }

        // SCSI API가 지원되지 않으면 조기 종료
        if (!scsiApiSupported) {
          return
        }

        // 각 호스트에서 SCSI 디바이스 할당 정보 확인
        for (const host of hosts) {
          try {
            const scsiRes = await getAPI('listHostScsiDevices', { id: host.id })

            // API 응답에서 에러 코드 확인 (530 에러는 지원되지 않는 명령)
            if (scsiRes?.listhostscsidevicesresponse?.errorcode === 530) {
              continue
            }
            if (scsiRes?.listhostscsidevicesresponse?.errorcode) {
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
                    // 중복 디바이스 방지
                    const existingDevice = this.scsiDevices.find(device => device.hostDevicesName === devName)
                    if (!existingDevice) {
                      this.scsiDevices.push({
                        hostDevicesName: devName,
                        hostDevicesText: scsiData.hostdevicestext[deviceIndex]
                      })
                    }
                  }
                }
              }
            }
          } catch (error) {
            // API가 지원되지 않는 경우 조용히 건너뛰기
            if (error.response?.status === 530 || error.message?.includes('BadCommand') || error.message?.includes('Unsupported command')) {
            } else {
              console.warn('Error checking SCSI devices on host:', host.name, error.message)
            }
          }
        }
      } catch (error) {
        console.warn('Error in fetchScsiDevices:', error.message)
      } finally {
        this.deviceLoadingStates.scsi = false
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

</style>
