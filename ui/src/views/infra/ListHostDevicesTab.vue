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
  <div>
    <a-tabs v-model:activeKey="activeKey" tab-position="top" @change="handleTabChange">
      <a-tab-pane key="1" :tab="$t('label.other.devices')">
        <a-input-search
          v-model:value="otherSearchQuery"
          :placeholder="$t('label.search')"
          style="width: 500px; margin-bottom: 15px; float: right;"
          @search="onOtherSearch"
          @change="onOtherSearch"
        />
        <a-table
          :columns="columns"
          :dataSource="filteredOtherDevices"
          :pagination="false"
          size="middle"
          :scroll="{ y: 1000 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">
              {{ record.hostDevicesName }}
            </template>
            <template v-if="column.key === 'hostDevicesText'">{{ record.hostDevicesText }}</template>
            <template v-if="column.key === 'vmName'">
              <a-spin v-if="vmNameLoading" size="small" />
              <template v-else>{{ vmNames[record.hostDevicesName] || $t('') }}</template>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <a-button
                  :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                  size="medium"
                  shape="circle"
                  :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                  @click="isDeviceAssigned(record) ? deallocatePciDevice(record) : openPciModal(record)"
                  :loading="loading">
                  <template #icon>
                    <delete-outlined v-if="isDeviceAssigned(record)" />
                    <plus-outlined v-else />
                  </template>
                </a-button>
              </div>
              <span style="display: none;">{{ record.virtualmachineid }}</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="2" :tab="$t('label.usb.devices')">
        <a-input-search
          v-model:value="usbSearchQuery"
          :placeholder="$t('label.search')"
          style="width: 500px; margin-bottom: 15px; float: right;"
          @search="onUsbSearch"
          @change="onUsbSearch"
        />
        <a-table
          :columns="columns"
          :dataSource="filteredUsbDevices"
          :loading="loading"
          :pagination="false"
          size="middle"
          :scroll="{ y: 1000 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">
              {{ record.hostDevicesName }}
            </template>
            <template v-if="column.key === 'hostDevicesText'">
              {{ record.hostDevicesText }}
            </template>
            <template v-if="column.dataIndex === 'vmName'">
              <a-spin v-if="vmNameLoading" size="small" />
              <template v-else>{{ vmNames[record.hostDevicesName] || $t('') }}</template>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <a-button
                  :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                  size="medium"
                  shape="circle"
                  :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                  @click="isDeviceAssigned(record) ? deallocateUsbDevice(record) : openUsbModal(record)"
                  :loading="loading"
                  :disabled="loading"
                  style="z-index: 1; position: relative;">
                  <template #icon>
                    <delete-outlined v-if="isDeviceAssigned(record)" />
                    <plus-outlined v-else />
                  </template>
                </a-button>
              </div>
              <span style="display: none;">{{ record.virtualmachineid }}</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="3" :tab="$t('label.lun.devices')">
        <a-input-search
          v-model:value="lunSearchQuery"
          :placeholder="$t('label.search')"
          style="width: 500px; margin-bottom: 15px; float: right;"
          @search="onLunSearch"
          @change="onLunSearch"
        />
        <a-table
          :columns="columns"
          :dataSource="filteredLunDevices"
          :pagination="false"
          size="middle"
          :scroll="{ y: 1000 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">{{ record.hostDevicesName }}</template>
            <template v-if="column.key === 'hostDevicesText'">{{ record.hostDevicesText }}</template>
            <template v-if="column.dataIndex === 'vmName'">
              <a-spin v-if="vmNameLoading" size="small" />
              <template v-else>{{ vmNames[record.hostDevicesName] || $t('') }}</template>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <a-button
                  v-if="!record.hostDevicesText?.toLowerCase().includes('partitions')"
                  :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                  size="medium"
                  shape="circle"
                  :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                  @click="isDeviceAssigned(record) ? deallocateLunDevice(record) : openLunModal(record)"
                  :loading="loading">
                  <template #icon>
                    <delete-outlined v-if="isDeviceAssigned(record)" />
                    <plus-outlined v-else />
                  </template>
                </a-button>
              </div>
              <span style="display: none;">{{ record.virtualmachineid }}</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="4" :tab="$t('label.hba.devices')">
        <a-input-search
          v-model:value="hbaSearchQuery"
          :placeholder="$t('label.search')"
          style="width: 500px; margin-bottom: 15px; float: right;"
          @search="onHbaSearch"
          @change="onHbaSearch"
        />
        <a-table
          :columns="columns"
          :dataSource="filteredHbaDevices"
          :loading="loading"
          :pagination="false"
          size="middle"
          :scroll="{ y: 1000 }">
          <template #headerCell="{ column }">
            <template v-if="column.key === 'hostDevicesText'">
              {{ $t('label.details') }}
            </template>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'hostDevicesName'">
              {{ record.hostDevicesName }}
            </template>
            <template v-if="column.key === 'hostDevicesText'">
              {{ record.hostDevicesText }}
            </template>
            <template v-if="column.key === 'vmName'">
              <a-spin v-if="vmNameLoading" size="small" />
              <template v-else>{{ vmNames[record.hostDevicesName] || $t('') }}</template>
            </template>
            <template v-if="column.key === 'action'">
              <div style="display: flex; align-items: center; gap: 8px;">
                <a-button
                  :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                  size="medium"
                  shape="circle"
                  :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                  @click="isDeviceAssigned(record) ? deallocateHbaDevice(record) : openHbaModal(record)"
                  :loading="loading"
                  :disabled="loading"
                  style="z-index: 1; position: relative;">
                  <template #icon>
                    <delete-outlined v-if="isDeviceAssigned(record)" />
                    <plus-outlined v-else />
                  </template>
                </a-button>
              </div>
              <span style="display: none;">{{ record.virtualmachineid }}</span>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      :visible="showAddModal"
      :title="$t('label.create.host.devices')"
      :v-html="$t('message.restart.vm.host.update.settings')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeAction">
      <HostDevicesTransfer
        v-if="activeKey === '1' && selectedResource"
        ref="hostDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
      <HostUsbDevicesTransfer
        v-else-if="activeKey === '2' && selectedResource"
        ref="hostUsbDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
      <HostLunDevicesTransfer
        v-else-if="activeKey === '3' && selectedResource"
        ref="hostLunDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
      <HostHbaDevicesTransfer
        v-else-if="activeKey === '4' && selectedResource"
        ref="hostHbaDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
    </a-modal>

    <a-modal
      v-model:visible="showUsbDeleteModal"
      :title="`${vmNames[selectedUsbDevice?.hostDevicesName] || ''} ${$t('message.delete.device.allocation')}`"
      @ok="handleUsbDeviceDelete"
      @cancel="closeUsbDeleteModal"
    >
      <div>
        <p>{{ $t('message.confirm.delete.device') }}</p>
      </div>
    </a-modal>

    <a-modal
      v-model:visible="showPciDeleteModal"
      :title="`${selectedPciDevice?.vmName || ''} ${$t('message.delete.device.allocation1')}`"
      @ok="handlePciDeviceDelete"
      @cancel="closePciDeleteModal"
    >
      <div>
        <p>{{ $t('message.confirm.delete.device') }}</p>
      </div>
    </a-modal>

    <a-modal
      v-model:visible="showLunDeleteModal"
      :title="`${selectedLunDevice?.vmName || ''} ${$t('message.delete.device.allocation')}`"
      @ok="handleLunDeviceDelete"
      @cancel="closeLunDeleteModal"
    >
      <div>
        <p>{{ $t('message.confirm.delete.device') }}</p>
      </div>
    </a-modal>
  </div>
</template>

<script>
import { api } from '@/api'
import eventBus from '@/config/eventBus'
import { IdcardOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import HostDevicesTransfer from '@/views/storage/HostDevicesTransfer'
import HostUsbDevicesTransfer from '@/views/storage/HostUsbDevicesTransfer'
import HostLunDevicesTransfer from '@/views/storage/HostLunDevicesTransfer'
import HostHbaDevicesTransfer from '@/views/storage/HostHbaDevicesTransfer'

export default {
  name: 'ListHostDevicesTab',
  components: {
    IdcardOutlined,
    PlusOutlined,
    DeleteOutlined,
    HostDevicesTransfer,
    HostUsbDevicesTransfer,
    HostLunDevicesTransfer,
    HostHbaDevicesTransfer
  },
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      columns: [
        {
          key: 'hostDevicesName',
          dataIndex: 'hostDevicesName',
          title: this.$t('label.name'),
          width: '30%'
        },
        {
          key: 'hostDevicesText',
          dataIndex: 'hostDevicesText',
          title: this.$t('label.text'),
          width: '50%'
        },
        {
          key: 'vmName',
          dataIndex: 'vmName',
          title: this.$t('label.vmname'),
          width: '20%'
        },
        {
          key: 'action',
          dataIndex: 'action',
          title: this.$t('label.action'),
          width: '20%'
        }
      ],
      dataItems: [],
      loading: false,
      showAddModal: false,
      selectedResource: null,
      searchQuery: '',
      activeKey: '1',
      usbSearchQuery: '',
      lunSearchQuery: '',
      otherSearchQuery: '',
      hbaSearchQuery: '',
      selectedDevices: [],
      selectedPciDevices: [],
      virtualmachines: [],
      showPciDeleteModal: false,
      selectedPciDevice: null,
      pciConfigs: {},
      vmNames: {},
      vmNameLoading: false,
      showUsbDeleteModal: false,
      selectedUsbDevice: null,
      showLunDeleteModal: false,
      selectedLunDevice: null
    }
  },
  computed: {
    tableSource () {
      return this.dataItems.map((item, index) => ({
        key: index,
        hostDevicesName: Array.isArray(item.hostDevicesNames) ? item.hostDevicesNames[0] : item.hostDevicesName,
        hostDevicesText: Array.isArray(item.hostDevicesTexts) ? item.hostDevicesTexts[0] : item.hostDevicesText,
        value: item.value,
        virtualmachineid: item.virtualmachineid,
        isAssigned: item.isAssigned,
        vmName: item.vmName || (this.vmNames[item.hostDevicesName] || '')
      }))
    },

    filteredOtherDevices () {
      const query = this.otherSearchQuery.toLowerCase()
      return this.tableSource.filter(item => {
        const deviceName = String(item.hostDevicesName || '')
        const deviceText = String(item.hostDevicesText || '')
        const isOther = !deviceName.toLowerCase().includes('usb') &&
                       !deviceName.toLowerCase().includes('lun')
        if (!query) return isOther
        return isOther && (
          deviceName.toLowerCase().includes(query) ||
          deviceText.toLowerCase().includes(query)
        )
      })
    },

    filteredUsbDevices () {
      if (!this.dataItems) return []

      const query = this.usbSearchQuery.toLowerCase()
      return this.dataItems.filter(item => {
        if (item.hostDevicesText && item.hostDevicesText.toLowerCase().includes('hub')) {
          return false
        }

        if (!query) return true
        return (
          (item.hostDevicesName && item.hostDevicesName.toLowerCase().includes(query)) ||
          (item.hostDevicesText && item.hostDevicesText.toLowerCase().includes(query))
        )
      })
    },

    filteredLunDevices () {
      const query = this.lunSearchQuery.toLowerCase()
      return this.tableSource.filter(item => {
        const deviceName = String(item.hostDevicesName || '')
        const deviceText = String(item.hostDevicesText || '')
        const isLun = deviceName.startsWith('/dev/')
        if (!query) return isLun
        return isLun && (
          deviceName.toLowerCase().includes(query) ||
          deviceText.toLowerCase().includes(query)
        )
      })
    },

    filteredHbaDevices () {
      const query = this.hbaSearchQuery.toLowerCase()
      return this.dataItems.filter(item => {
        if (!query) return true
        return (
          (item.hostDevicesName && item.hostDevicesName.toLowerCase().includes(query)) ||
          (item.hostDevicesText && item.hostDevicesText.toLowerCase().includes(query))
        )
      })
    }
  },
  created () {
    this.fetchData()
    this.setupVMEventListeners()
  },
  methods: {
    setupVMEventListeners () {
      const eventTypes = ['DestroyVM', 'ExpungeVM']

      eventTypes.forEach(eventType => {
        eventBus.emit('register-event', {
          eventType: eventType,
          callback: async (event) => {
            try {
              const vmId = event.id

              // 현재 호스트의 디바이스 할당 정보 조회
              const response = await api('listHostDevices', { id: this.resource.id })
              const devices = response.listhostdevicesresponse?.listhostdevices?.[0]

              if (devices?.vmallocations) {
                // 삭제된 VM에 할당된 디바이스 찾기
                const allocatedDevices = Object.entries(devices.vmallocations)
                  .filter(([_, allocatedVmId]) => {
                    console.log('Checking device allocation:', { allocatedVmId, vmId, match: allocatedVmId === vmId })
                    return allocatedVmId === vmId
                  })
                  .map(([deviceName]) => deviceName)

                console.log('Found allocated devices:', allocatedDevices)

                // 각 디바이스의 할당 해제
                for (const deviceName of allocatedDevices) {
                  try {
                    console.log('Attempting to update host device:', deviceName)
                    const response = await api('updateHostDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null
                    })
                    console.log('Update host device response:', response)

                    if (!response || response.error) {
                      throw new Error(response?.error?.errortext || 'Failed to update host device')
                    }
                  } catch (error) {
                    console.error('Failed to update host device:', deviceName, error)
                    this.$notification.error({
                      message: this.$t('label.error'),
                      description: error.message || this.$t('message.update.host.device.failed')
                    })
                  }
                }

                if (allocatedDevices.length > 0) {
                  this.$message.info(this.$t('message.device.allocation.removed.vm.deleted'))
                  await this.fetchData()
                  await this.updateVmNames()
                }
              }
            } catch (error) {
              console.error('Error handling VM deletion event:', error)
            }
          }
        })
      })
    },
    async fetchData () {
      this.loading = true
      this.selectedDevices = []

      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const data = response.listhostdevicesresponse?.listhostdevices?.[0]

        if (data) {
          const vmAllocations = data.vmallocations || {}

          const vmIds = Object.values(vmAllocations)
            .filter(id => id)
            .map(id => id.toString())

          const vmNameMap = {}
          if (vmIds.length > 0) {
            for (const vmId of vmIds) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })
                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm) {
                  vmNameMap[vmId] = vm.displayname || vm.name
                }
              } catch (error) {
              }
            }
          }

          this.vmNames = vmNameMap

          this.dataItems = data.hostdevicesname.map((name, index) => {
            const vmId = vmAllocations[name] || null
            const vmName = vmId ? this.vmNames[vmId] : null

            return {
              hostDevicesName: name,
              hostDevicesText: data.hostdevicestext[index] || '',
              virtualmachineid: vmId,
              vmName: vmName,
              isAssigned: Boolean(vmId)
            }
          })

          this.selectedDevices = Object.keys(vmAllocations).filter(key => vmAllocations[key])
        } else {
          this.dataItems = []
          this.selectedDevices = []
        }
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.loading = false
      }
      await this.updateVmNames()
    },
    isDeviceAssigned (record) {
      // HBA 디바이스의 경우 고유한 디바이스명 사용
      const deviceName = this.activeKey === '4' ? record.hostDevicesName : record.hostDevicesName
      return record.virtualmachineid != null ||
             record.isAssigned ||
             this.selectedDevices.includes(deviceName) ||
             (this.activeKey === '4' && this.vmNames[record.hostDevicesName])
    },
    openPciModal (record) {
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
    },
    openUsbModal (record) {
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
    },
    closeAction () {
      this.showAddModal = false
      this.selectedResource = null
      if (this.activeKey === '2') {
        this.fetchUsbDevices()
      } else if (this.activeKey === '3') {
        this.fetchLunDevices()
      } else if (this.activeKey === '4') {
        this.fetchHbaDevices()
      } else {
        this.fetchData()
      }
    },
    onUsbSearch (e) {
      // USB 디바이스 검색 처리
    },
    onLunSearch () {
      // LUN 디바이스 검색 처리
    },
    onOtherSearch () {
      // 기타 디바이스 검색 처리
    },
    async handleDelete (record) {
      if (this.activeKey === '2') {
        // HostUsbDevicesTransfer의 handleDelete 메서드 호출
        this.$refs.hostUsbDevicesTransfer.handleDelete()
      } else if (this.activeKey === '3') {
        // 다른 로직 처리
      } else {
        // 기존 로직 처리
      }
    },
    handleAllocationCompleted () {
      if (this.activeKey === '2') {
        this.fetchUsbDevices()
      } else if (this.activeKey === '3') {
        setTimeout(() => {
          this.fetchLunDevices()
        }, 700)
      } else if (this.activeKey === '4') {
        this.fetchHbaDevices()
      } else {
        this.fetchData()
      }
      this.showAddModal = false
      this.updateDataWithVmNames()
    },
    handleDeviceAllocated () {
      if (this.activeKey === '2') {
        this.fetchUsbDevices()
      } else if (this.activeKey === '3') {
        this.fetchLunDevices()
      } else if (this.activeKey === '4') {
        this.fetchHbaDevices()
      } else {
        this.fetchData()
      }
    },

    async handleTabChange (activeKey) {
      this.activeKey = activeKey
      if (activeKey === '2') {
        this.fetchUsbDevices()
      } else if (activeKey === '1') {
        await this.fetchData()
        await this.updateVmNames()
      } else if (activeKey === '3') {
        this.fetchLunDevices() // LUN 탭 선택 시 호출
      } else if (activeKey === '4') {
        this.fetchHbaDevices() // HBA 탭 선택 시 호출
      }
    },
    fetchUsbDevices () {
      this.loading = true
      api('listHostUsbDevices', {
        id: this.resource.id
      }).then(response => {
        if (response.listhostusbdevicesresponse?.listhostusbdevices?.[0]) {
          const usbData = response.listhostusbdevicesresponse.listhostusbdevices[0]

          const usbDevices = usbData.hostdevicesname.map((name, index) => ({
            key: index,
            hostDevicesName: name,
            hostDevicesText: usbData.hostdevicestext[index],
            virtualmachineid: (usbData.vmallocations && usbData.vmallocations[name]) || null,
            isAssigned: Boolean(usbData.vmallocations && usbData.vmallocations[name])
          }))

          this.dataItems = usbDevices
          // 가상머신 이름을 업데이트합니다
          this.updateUsbVmNames()
        } else {
          this.dataItems = []
        }
      }).catch(error => {
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.usb.devices')
        })
      }).finally(() => {
        this.loading = false
      })
    },
    fetchLunDevices () {
      this.loading = true
      api('listHostLunDevices', {
        id: this.resource.id
      }).then(async response => {
        if (response.listhostlundevicesresponse?.listhostlundevices?.[0]) {
          const lunData = response.listhostlundevicesresponse.listhostlundevices[0]
          const vmAllocations = lunData.vmallocations || {}
          // VM 이름 매핑
          const vmNameMap = {}
          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
              if (vm) vmNameMap[name] = vm.displayname || vm.name
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNameMap }
          const lunDevices = lunData.hostdevicesname.map((name, index) => ({
            key: index,
            hostDevicesName: name,
            hostDevicesText: lunData.hostdevicestext[index],
            virtualmachineid: (lunData.vmallocations && lunData.vmallocations[name]) || null,
            vmName: vmNameMap[name] || '',
            isAssigned: Boolean(lunData.vmallocations && lunData.vmallocations[name]),
            hasPartitions: lunData.haspartitions[name]
          }))
          this.dataItems = lunDevices
        } else {
          this.dataItems = []
        }
      }).catch(error => {
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.lun.devices')
        })
      }).finally(() => {
        this.loading = false
      })
      this.updateDataWithVmNames()
    },
    async handlePciDeviceDelete () {
      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[this.selectedPciDevice.hostDevicesName]

        const vmResponse = await api('listVirtualMachines', {
          id: vmId,
          listall: true,
          details: 'all'
        })
        const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

        const params = {
          id: vm.id
        }

        // XML 설정 찾아서 제거
        Object.entries(vm.details || {}).forEach(([key, value]) => {
          if (key.startsWith('extraconfig-') && value.includes('<hostdev')) {
            const [pciAddress] = this.selectedPciDevice.hostDevicesName.split(' ')
            const [bus, slotFunction] = pciAddress.split(':')
            const [slot, func] = slotFunction.split('.')
            const pattern = `bus='0x${bus}' slot='0x${slot}' function='0x${func}'`

            if (!value.includes(pattern)) {
              params[`details[0].${key}`] = value
            }
          } else if (!key.startsWith('extraconfig-')) {
            params[`details[0].${key}`] = value
          }
        })

        // 먼저 VM의 extraconfig를 업데이트
        await api('updateVirtualMachine', params)

        // 그 다음 호스트 디바이스 할당 해제
        await api('updateHostDevices', {
          hostid: this.resource.id,
          hostdevicesname: this.selectedPciDevice.hostDevicesName,
          virtualmachineid: null
        })

        this.$message.success(this.$t('message.success.remove.allocation'))
        this.showPciDeleteModal = false
        this.selectedPciDevice = null
        this.pciConfigs = {}
        this.fetchData()

        // eventBus 대신 emit 사용
        this.$emit('refresh-vm-list')
      } catch (error) {
        this.$notifyError(error)
      }
    },
    // closePciDeleteModal () {
    //   this.showPciDeleteModal = false
    //   this.selectedPciDevice = null
    //   this.pciConfigs = {}
    // },
    showConfirmModal (device) {
      // vmName을 강제로 할당
      const vmName = this.vmNames[device.hostDevicesName] || ''
      this.selectedPciDevice = { ...device, vmName }
      this.showPciDeleteModal = true
    },
    async updateDataWithVmNames () {
      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const data = response.listhostdevicesresponse?.listhostdevices?.[0]

        if (data && data.vmallocations) {
          const vmIds = [...new Set(Object.values(data.vmallocations))].filter(Boolean)
          const vmNameMap = await this.getVmNames(vmIds)
          this.dataItems = this.dataItems.map(item => ({
            ...item,
            vmName: item.virtualmachineid ? vmNameMap[item.virtualmachineid] : ''
          }))
        }
      } catch (error) {
      }
    },
    async updateVmNames () {
      this.vmNameLoading = true
      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]

        if (devices?.vmallocations) {
          const vmNamesMap = {}
          const entries = Object.entries(devices.vmallocations)
          const processedDevices = new Set()

          for (const [deviceName, vmId] of entries) {
            if (vmId && !processedDevices.has(deviceName)) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })

                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm) {
                  vmNamesMap[deviceName] = vm.name || vm.displayname
                } else {
                  console.log('VM not found, removing device allocation:', deviceName)
                  try {
                    console.log('Attempting to update host device:', deviceName)
                    const updateResponse = await api('updateHostDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null,
                      isattach: false
                    })
                    console.log('Update host device response:', updateResponse)

                    if (!updateResponse || updateResponse.error) {
                      throw new Error(updateResponse?.error?.errortext || 'Failed to update host device')
                    }

                    vmNamesMap[deviceName] = this.$t('label.no.vm.assigned')
                    processedDevices.add(deviceName)

                    // UI 업데이트 (모달 없이)
                    this.dataItems = this.dataItems.map(item => {
                      if (item.hostDevicesName === deviceName) {
                        return {
                          ...item,
                          virtualmachineid: null,
                          isAssigned: false
                        }
                      }
                      return item
                    })
                  } catch (error) {
                    console.error('Failed to update host device:', deviceName, error)
                    // 에러 시에만 notification 표시
                    this.$notification.error({
                      message: this.$t('label.error'),
                      description: error.message || this.$t('message.update.host.device.failed')
                    })
                  }
                }
              } catch (error) {
                console.error('Error processing device:', deviceName, error)
                vmNamesMap[deviceName] = this.$t('label.no.vm.assigned')
              }
            }
          }
          this.vmNames = vmNamesMap
          if (processedDevices.size > 0) {
            await this.fetchData()
          }
        }
      } catch (error) {
        console.error('Error in updateVmNames:', error)
      } finally {
        this.vmNameLoading = false
      }
    },
    async updateUsbVmNames () {
      this.vmNameLoading = true
      try {
        const response = await api('listHostUsbDevices', { id: this.resource.id })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]

        if (devices?.vmallocations) {
          const vmNamesMap = {}
          const entries = Object.entries(devices.vmallocations)

          for (const [deviceName, vmId] of entries) {
            if (vmId) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })

                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm) {
                  vmNamesMap[deviceName] = vm.name || vm.displayname
                } else {
                  vmNamesMap[deviceName] = this.$t('label.no.vm.assigned')
                }
              } catch (error) {
                console.error('Error fetching VM name for USB device:', deviceName, error)
                vmNamesMap[deviceName] = this.$t('label.no.vm.assigned')
              }
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNamesMap }
        }
      } catch (error) {
        console.error('Error in updateUsbVmNames:', error)
      } finally {
        this.vmNameLoading = false
      }
    },
    // 컴포넌트가 제거될 때 이벤트 리스너도 제거
    beforeDestroy () {
      const eventTypes = ['DestroyVM', 'ExpungeVM']
      eventTypes.forEach(eventType => {
        eventBus.emit('unregister-event', {
          eventType: eventType
        })
      })
    },
    async deallocateUsbDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }

      this.loading = true
      const hostDevicesName = record.hostDevicesName

      console.log('Deallocating USB device:', hostDevicesName)

      try {
        // 1. USB 디바이스의 현재 할당 상태 확인
        const response = await api('listHostUsbDevices', {
          id: this.resource.id
        })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]

        if (!vmId) {
          console.error('No VM allocation found for device:', hostDevicesName)
          throw new Error('No VM allocation found for this device')
        }

        // 2. 할당 해제 확인 및 실행
        const vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            const xmlConfig = this.generateXmlUsbConfig(hostDevicesName)
            const detachResponse = await api('updateHostUsbDevices', {
              hostid: this.resource.id,
              hostdevicesname: hostDevicesName,
              virtualmachineid: null,
              currentvmid: vmId,
              xmlconfig: xmlConfig,
              isattach: false
            })

            if (!detachResponse || detachResponse.error) {
              console.error('Failed to detach USB device:', detachResponse?.error?.errortext)
              throw new Error(detachResponse?.error?.errortext || 'Failed to detach USB device')
            }

            // **여기서 dataItems의 vmName을 직접 비워줌**
            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false }
                : item
            )

            this.$message.success(this.$t('message.success.remove.allocation'))
            await this.fetchUsbDevices()
            this.vmNames = {}
            this.$emit('device-allocated')
            this.$emit('allocation-completed')
            this.$emit('close-action')
          },
          onCancel () {
            console.log('Deallocation cancelled')
          }
        })
      } catch (error) {
        console.error('Error during USB device deallocation:', error)
        this.$notifyError(error.message || 'Failed to deallocate USB device')
      } finally {
        this.loading = false
      }
    },

    generateXmlUsbConfig (hostDeviceName) {
      const match = hostDeviceName.match(/(\d+)\D+(\d+)/)
      let bus = '0x001'
      let device = '0x01'
      if (match) {
        bus = '0x' + parseInt(match[1], 10).toString(16).padStart(3, '0')
        device = '0x' + parseInt(match[2], 10).toString(16).padStart(2, '0')
      }
      console.log(bus, device)
      return `
        <hostdev mode='subsystem' type='usb'>
          <source>
            <address type='usb' bus='${bus}' device='${device}' />
          </source>
        </hostdev>
      `.trim()
    },
    generateXmlLunConfig (hostDeviceName) {
      let targetDev = 'sdc'
      const match = hostDeviceName.match(/\/dev\/([a-z]+[a-z0-9]*)$/)
      if (match) {
        targetDev = match[1]
      } else {
        // 기본값
        targetDev = 'sdc'
      }
      return `
        <disk type='block' device='lun'>
          <driver name='qemu' type='raw'/>
          <source dev='${hostDeviceName}'/>
          <target dev='${targetDev}' bus='scsi'/>
          <address type='drive' controller='0' bus='0' target='1' unit='0'/>
        </disk>
      `.trim()
    },
    async handleUsbDeviceDelete () {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }

      this.loading = true
      const hostDevicesName = this.selectedUsbDevice.hostDevicesName

      console.log('Deallocating USB device:', hostDevicesName)

      try {
        const response = await api('listHostUsbDevices', {
          id: this.resource.id
        })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]

        if (!vmId) {
          console.error('No VM allocation found for device:', hostDevicesName)
          throw new Error('No VM allocation found for this device')
        }

        const xmlConfig = this.generateXmlUsbConfig(hostDevicesName)
        const detachResponse = await api('updateHostUsbDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: vmId,
          xmlconfig: xmlConfig,
          isattach: false
        })

        if (!detachResponse || detachResponse.error) {
          // console.error('Failed to detach USB device:', detachResponse?.error?.errortext)
          throw new Error(detachResponse?.error?.errortext || 'Failed to detach USB device')
        }
        console.log('detachResponse', detachResponse)
        console.log('Device detached successfully')

        this.$message.success(this.$t('message.success.remove.allocation'))
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        console.error('Error during USB device deallocation:', error)
        this.$notifyError(error.message || 'Failed to deallocate USB device')
      } finally {
        this.loading = false
      }
    },
    closeUsbDeleteModal () {
      this.showUsbDeleteModal = false
      this.selectedUsbDevice = null
    },
    async deallocateLunDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      const hostDevicesName = record.hostDevicesName
      try {
        // 1. LUN 디바이스의 현재 할당 상태 확인
        const response = await api('listHostLunDevices', {
          id: this.resource.id
        })
        const devices = response.listhostlundevicesresponse?.listhostlundevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]
        if (!vmId) {
          throw new Error('No VM allocation found for this device')
        }
        // 2. 할당 해제 확인 및 실행
        const vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            const xmlConfig = this.generateXmlLunConfig(hostDevicesName)
            const detachResponse = await api('updateHostLunDevices', {
              hostid: this.resource.id,
              hostdevicesname: hostDevicesName,
              virtualmachineid: null,
              currentvmid: vmId,
              xmlconfig: xmlConfig,
              isattach: false
            })
            if (!detachResponse || detachResponse.error) {
              throw new Error(detachResponse?.error?.errortext || 'Failed to detach LUN device')
            }

            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false }
                : item
            )

            this.$message.success(this.$t('message.success.remove.allocation'))
            await this.fetchLunDevices()
            this.vmNames = {}
            this.$emit('device-allocated')
            this.$emit('allocation-completed')
            this.$emit('close-action')
          },
          onCancel () {}
        })
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate LUN device')
      } finally {
        this.loading = false
      }
    },
    closeLunDeleteModal () {
      this.showLunDeleteModal = false
      this.selectedLunDevice = null
    },
    async handleLunDeviceDelete () {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      const hostDevicesName = this.selectedLunDevice.hostDevicesName
      try {
        const response = await api('updateHostLunDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: null
        })
        if (!response || response.error) {
          throw new Error(response?.error?.errortext || 'Failed to detach LUN device')
        }
        this.$message.success(this.$t('message.success.remove.allocation'))
        this.showLunDeleteModal = false
        this.selectedLunDevice = null
        await this.fetchLunDevices()
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate LUN device')
      } finally {
        this.loading = false
      }
    },
    openLunModal (record) {
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
    },
    async deallocatePciDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      const hostDevicesName = record.hostDevicesName
      try {
        // 1. PCI 디바이스의 현재 할당 상태 확인
        const response = await api('listHostDevices', {
          id: this.resource.id
        })
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]
        if (!vmId) {
          throw new Error('No VM allocation found for this device')
        }
        // 2. 할당 해제 확인 및 실행
        const vmName = this.vmNames[hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            // VM extraconfig에서 PCI 관련 설정 제거
            const vmResponse = await api('listVirtualMachines', {
              id: vmId,
              listall: true,
              details: 'all'
            })
            const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]
            const params = { id: vm.id }
            Object.entries(vm.details || {}).forEach(([key, value]) => {
              if (key.startsWith('extraconfig-') && value.includes('<hostdev')) {
                const [pciAddress] = hostDevicesName.split(' ')
                const [bus, slotFunction] = pciAddress.split(':')
                const [slot, func] = slotFunction.split('.')
                const pattern = `bus='0x${bus}' slot='0x${slot}' function='0x${func}'`
                if (!value.includes(pattern)) {
                  params[`details[0].${key}`] = value
                }
              } else if (!key.startsWith('extraconfig-')) {
                params[`details[0].${key}`] = value
              }
            })
            await api('updateVirtualMachine', params)
            // PCI 디바이스 할당 해제
            await api('updateHostDevices', {
              hostid: this.resource.id,
              hostdevicesname: hostDevicesName,
              virtualmachineid: null
            })
            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false }
                : item
            )
            // 해당 디바이스만 vmNames에서 삭제
            if (this.vmNames[hostDevicesName]) {
              delete this.vmNames[hostDevicesName]
            }
            this.$message.success(this.$t('message.success.remove.allocation'))
            await this.fetchData()
            this.$emit('device-allocated')
            this.$emit('allocation-completed')
            this.$emit('close-action')
          },
          onCancel () {}
        })
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate PCI device')
      } finally {
        this.loading = false
      }
    },
    closePciDeleteModal () {
      this.showPciDeleteModal = false
      this.selectedPciDevice = null
    },
    onHbaSearch () {
      // HBA 디바이스 검색은 computed 속성에서 자동으로 처리됨
    },
    async fetchHbaDevices () {
      this.loading = true
      try {
        const response = await api('listHostHbaDevices', {
          id: this.resource.id
        })

        if (response.listhosthbadevicesresponse?.listhosthbadevices?.[0]) {
          const hbaData = response.listhosthbadevicesresponse.listhosthbadevices[0]
          const vmAllocations = hbaData.vmallocations || {}

          // VM 이름 매핑
          const vmNameMap = {}
          for (const name in vmAllocations) {
            const vmId = vmAllocations[name]
            if (vmId) {
              const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
              if (vm) vmNameMap[name] = vm.displayname || vm.name
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNameMap }

          const hbaDevices = hbaData.hostdevicesname.map((name, index) => {
            return {
              key: index,
              hostDevicesName: name,
              hostDevicesText: hbaData.hostdevicestext[index],
              virtualmachineid: (hbaData.vmallocations && hbaData.vmallocations[name]) || null,
              vmName: vmNameMap[name] || '',
              isAssigned: Boolean(hbaData.vmallocations && hbaData.vmallocations[name])
            }
          })

          this.dataItems = hbaDevices
          // 가상머신 이름을 업데이트합니다
          this.updateHbaVmNames()
        } else {
          this.dataItems = []
        }
      } catch (error) {
        console.error('Error fetching HBA devices:', error)
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.hba.devices')
        })
        this.dataItems = []
      } finally {
        this.loading = false
      }
    },

    async updateHbaVmNames () {
      this.vmNameLoading = true
      try {
        const response = await api('listHostHbaDevices', { id: this.resource.id })
        const devices = response.listhosthbadevicesresponse?.listhosthbadevices?.[0]

        if (devices?.vmallocations) {
          const vmNamesMap = {}
          const entries = Object.entries(devices.vmallocations)

          for (const [deviceName, vmId] of entries) {
            if (vmId) {
              try {
                const vmResponse = await api('listVirtualMachines', {
                  id: vmId,
                  listall: true
                })

                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
                if (vm) {
                  vmNamesMap[deviceName] = vm.name || vm.displayname
                } else {
                  vmNamesMap[deviceName] = this.$t('label.no.vm.assigned')
                }
              } catch (error) {
                console.error('Error fetching VM name for HBA device:', deviceName, error)
                vmNamesMap[deviceName] = this.$t('label.no.vm.assigned')
              }
            }
          }
          this.vmNames = { ...this.vmNames, ...vmNamesMap }
        }
      } catch (error) {
        console.error('Error in updateHbaVmNames:', error)
      } finally {
        this.vmNameLoading = false
      }
    },
    async deallocateHbaDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      try {
        // 1. HBA 디바이스의 현재 할당 상태 확인
        const response = await api('listHostHbaDevices', {
          id: this.resource.id
        })
        const devices = response.listhosthbadevicesresponse?.listhosthbadevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[record.hostDevicesName]

        if (!vmId) {
          throw new Error('No VM allocation found for this device')
        }

        // 2. 할당 해제 확인 및 실행
        const vmName = this.vmNames[record.hostDevicesName] || 'Unknown VM'
        this.$confirm({
          title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
          content: `${vmName} ${this.$t('message.confirm.delete.device')}`,
          onOk: async () => {
            const xmlConfig = this.generateXmlHbaConfig(record.hostDevicesName)
            const detachResponse = await api('updateHostHbaDevices', {
              hostid: this.resource.id,
              hostdevicesname: record.hostDevicesName,
              virtualmachineid: null,
              currentvmid: vmId,
              xmlconfig: xmlConfig,
              isattach: false
            })
            if (!detachResponse || detachResponse.error) {
              throw new Error(detachResponse?.error?.errortext || 'Failed to detach HBA device')
            }

            // 현재 디바이스만 업데이트
            this.dataItems = this.dataItems.map(item =>
              item.hostDevicesName === record.hostDevicesName
                ? { ...item, virtualmachineid: null, vmName: '', isAssigned: false }
                : item
            )

            this.$message.success(this.$t('message.success.remove.allocation'))
            await this.fetchHbaDevices()
            this.vmNames = {}
            this.$emit('device-allocated')
            this.$emit('allocation-completed')
            this.$emit('close-action')
          },
          onCancel () {}
        })
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate HBA device')
      } finally {
        this.loading = false
      }
    },
    openHbaModal (record) {
      // 원본 디바이스명 사용 (API 호출용)
      const hostDevicesName = record.hostDevicesName
      this.selectedResource = { ...this.resource, hostDevicesName: hostDevicesName }
      this.showAddModal = true
    },
    generateXmlHbaConfig (hostDeviceName) {
      const match = hostDeviceName.match(/(\d+):(\d+)\.(\d+)/)
      let bus = '0x0000'
      let slot = '0x00'
      let func = '0x0'

      if (match) {
        bus = '0x' + parseInt(match[1], 10).toString(16).padStart(4, '0')
        slot = '0x' + parseInt(match[2], 10).toString(16).padStart(2, '0')
        func = '0x' + parseInt(match[3], 10).toString(16)
      }

      return `
        <hostdev mode='subsystem' type='pci' managed='yes'>
          <source>
            <address domain='0x0000' bus='${bus}' slot='${slot}' function='${func}'/>
          </source>
        </hostdev>
      `.trim()
    }
  }
}
</script>

<style lang="less" scoped>
  .ant-table-wrapper {
    margin: 2rem 0;
  }

  @media (max-width: 600px) {
    position: relative;
    width: 100%;
    top: 0;
    right: 0;
  }

  :deep(.ant-table-tbody) > tr > td {
    cursor: pointer;
  }

  .pci-device-item {
    display: flex;
    justify-content: space-between;
    align-items: center;
    width: 100%;
  }
</style>
