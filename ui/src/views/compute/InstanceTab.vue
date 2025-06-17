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
      <a-tab-pane :tab="$t('label.listhostdevices')" key="pcidevices" v-if="shouldShowPciDevicesTab">
        <div v-if="pciDevices.length > 0">
          <a-table
            :columns="pciColumns"
            :dataSource="pciDevices"
            :pagination="false"
            :loading="loading">
          </a-table>
        </div>
        <div v-else>
          {{ $t('label.no.pci.devices') }}
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
      hasPciDevices: false,
      pciDevices: [],
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
          this.dataResource = newData
          this.vm = this.dataResource
          this.fetchData()
        }
      }
    },
    '$route.fullPath': function () {
      this.setCurrentTab()
    }
  },
  mounted () {
    this.setCurrentTab()
    this.fetchPciDevices()
  },
  computed: {
    shouldShowPciDevicesTab () {
      return this.pciDevices.length > 0
    }
  },
  methods: {
    setCurrentTab () {
      this.currentTab = this.$route.query.tab ? this.$route.query.tab : 'details'
    },
    async fetchData () {
      this.annotations = []
      if (!this.vm || !this.vm.id) {
        return
      }
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

      this.hasPciDevices = false
      if (this.vm.details) {
        for (const [key, value] of Object.entries(this.vm.details)) {
          if (key.startsWith('extraconfig-') && (value.includes('<hostdev') || value.includes('pci'))) {
            this.hasPciDevices = true
            break
          }
        }
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
        if (this.pciDevices.length === 0) {
          this.currentTab = 'details'
          return
        }
      }
      if (this.currentTab !== activeKey) {
        this.currentTab = activeKey
      }
    },
    async fetchPciDevices () {
      this.pciDevices = []
      if (!this.vm.hostid) {
        // VM이 정지된 상태에서도 PCI 디바이스 정보 표시
        if (this.vm.details) {
          const pciAddresses = []

          for (const [key, value] of Object.entries(this.vm.details)) {
            if (key.startsWith('extraconfig-') && value.includes('<hostdev')) {
              const sourceMatch = value.match(/<source>\s*<address[^>]*domain='([^']*)'[^>]*bus='([^']*)'[^>]*slot='([^']*)'[^>]*function='([^']*)'[^>]*\/>\s*<\/source>/)
              if (sourceMatch) {
                const [, domain, bus, slot, function_] = sourceMatch

                const domainHex = domain.replace('0x', '')
                const busHex = bus.replace('0x', '')
                const slotHex = slot.replace('0x', '')
                const functionHex = function_.replace('0x', '')
                const pciAddress = `${busHex.padStart(2, '0')}:${slotHex.padStart(2, '0')}.${functionHex}`
                pciAddresses.push({
                  key: key,
                  pciAddress: pciAddress,
                  domain: domainHex,
                  bus: busHex,
                  slot: slotHex,
                  function: functionHex
                })
              } else {
              }
            }
          }
          if (pciAddresses.length > 0) {
            try {
              let hostId = this.vm.hostid || this.vm.lastHostId
              if (!hostId) {
                const zoneResponse = await api('listHosts', {
                  zoneid: this.vm.zoneid,
                  type: 'Routing',
                  state: 'Up'
                })

                if (zoneResponse?.listhostsresponse?.host && zoneResponse.listhostsresponse.host.length > 0) {
                  hostId = zoneResponse.listhostsresponse.host[0].id
                }
              }

              if (!hostId) {
                console.error('No hostId available for PCI device lookup')
                pciAddresses.forEach(addr => {
                  this.pciDevices.push({
                    key: addr.key,
                    hostDevicesName: `PCI Device at ${addr.pciAddress}`,
                    hostDevicesText: `Domain: ${addr.domain}, Bus: ${addr.bus}, Slot: ${addr.slot}, Function: ${addr.function}`
                  })
                })
                return
              }

              const response = await api('listHostDevices', {
                id: hostId
              })

              const devices = response?.listhostdevicesresponse?.listhostdevices?.[0]
              if (devices && devices.hostdevicesname && devices.hostdevicestext) {
                for (let i = 0; i < devices.hostdevicesname.length; i++) {
                  const deviceName = devices.hostdevicesname[i]
                  const deviceText = devices.hostdevicestext[i]
                  const pciMatch = deviceName.match(/^([0-9a-fA-F]{2}:[0-9a-fA-F]{2}\.[0-9a-fA-F])/)
                  if (pciMatch) {
                    const devicePciAddress = pciMatch[1].toLowerCase()

                    const matchingAddress = pciAddresses.find(addr => {
                      const addrLower = addr.pciAddress.toLowerCase()
                      return addrLower === devicePciAddress
                    })
                    if (matchingAddress) {
                      console.log('Found matching device:', deviceName)
                      this.pciDevices.push({
                        key: matchingAddress.key,
                        hostDevicesName: deviceName,
                        hostDevicesText: deviceText
                      })
                    }
                  }
                }
              }
            } catch (error) {
              pciAddresses.forEach(addr => {
                this.pciDevices.push({
                  key: addr.key,
                  hostDevicesName: `PCI Device at ${addr.pciAddress}`,
                  hostDevicesText: `Domain: ${addr.domain}, Bus: ${addr.bus}, Slot: ${addr.slot}, Function: ${addr.function}`
                })
              })
            }
          }
        }
        return
      }

      try {
        const vmNumericId = this.vm.instancename.split('-')[2]
        if (!vmNumericId) {
          console.error('Failed to get VM numeric ID')
          return
        }

        const response = await api('listHostDevices', {
          id: this.vm.hostid
        })

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
              }
            }
          })
        }
      } catch (error) {
        console.error('Error fetching PCI devices:', error)
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
