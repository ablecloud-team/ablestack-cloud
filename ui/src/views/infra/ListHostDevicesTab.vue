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
    <!-- 탭 관련 코드 주석 처리
    <a-tabs v-model:activeKey="activeKey" tab-position="top" @change="handleTabChange">
      <a-tab-pane key="1" :tab="$t('label.other.devices')">
    -->
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
                  @click="isDeviceAssigned(record) ? showConfirmModal(record) : openModal(record)"
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
    <!-- 탭 닫는 태그 주석 처리
      </a-tab-pane>

      <a-tab-pane key="2" :tab="$t('label.usb.devices')">
        ... USB devices content ...
      </a-tab-pane>

      <a-tab-pane key="3" :tab="$t('label.lun.devices')">
        ... LUN devices content ...
      </a-tab-pane>
    </a-tabs>
    -->

    <a-modal
      :visible="showAddModal"
      :title="$t('label.create.host.devices')"
      :v-html="$t('message.restart.vm.host.update.settings')"
      :maskClosable="false"
      :closable="true"
      :footer="null"
      @cancel="closeAction">
      <HostDevicesTransfer
        v-if="activeKey === '1'"
        ref="hostDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeAction"
        @allocation-completed="onAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
    </a-modal>

    <a-modal
      v-model:visible="showPciDeleteModal"
      :title="`${vmNames[selectedPciDevice?.hostDevicesName] || ''} ${$t('message.delete.device.allocation')}`"
      :cancelText="$t('label.cancel')"
      :okText="$t('label.ok')"
      @ok="handlePciDeviceDelete"
      @cancel="closePciDeleteModal"
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

export default {
  name: 'ListHostDevicesTab',
  components: {
    IdcardOutlined,
    PlusOutlined,
    DeleteOutlined,
    HostDevicesTransfer
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
      selectedDevices: [],
      selectedPciDevices: [],
      virtualmachines: [],
      showPciDeleteModal: false,
      selectedPciDevice: null,
      pciConfigs: {},
      vmNames: {},
      vmNameLoading: false
    }
  },
  computed: {
    tableSource () {
      return this.dataItems.map((item, index) => ({
        key: index,
        hostDevicesName: Array.isArray(item.hostDevicesNames) ? item.hostDevicesNames[0] : item.hostDevicesName,
        hostDevicesText: Array.isArray(item.hostDevicesTexts) ? item.hostDevicesTexts[0] : item.hostDevicesText,
        value: item.value
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
                console.log('VM allocations:', devices.vmallocations)
                console.log('Looking for devices allocated to VM:', vmId)

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

          // VM ID를 문자열로 처리
          const vmIds = Object.values(vmAllocations)
            .filter(id => id)
            .map(id => id.toString())

          // VM 이름 정보를 가져옴
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

          // 데이터 매핑 시 VM ID를 문자열로 변환하여 비교
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
      return record.virtualmachineid != null ||
             record.isAssigned ||
             this.selectedDevices.includes(record.hostDevicesName)
    },
    openModal (record) {
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
      this.$nextTick(() => {
        if (this.$refs.hostDevicesTransfer) {
          this.$refs.hostDevicesTransfer.refreshVMList()
        }
      })
    },
    closeAction () {
      this.showAddModal = false
      this.selectedResource = null
      if (this.activeKey === '2') {
        this.fetchUsbDevices()
      } else if (this.activeKey === '3') {
        this.fetchLunDevices()
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
        this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
        this.showAddModal = true
      } else if (this.activeKey === '3') {
        this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
        this.showAddModal = true
      } else {
        try {
          // 먼저 VM 정보를 가져옵니다
          const response = await api('listHostDevices', { id: this.resource.id })
          const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
          const vmAllocations = devices?.vmallocations || {}
          const vmId = vmAllocations[record.hostDevicesName]

          if (vmId) {
            const vmResponse = await api('listVirtualMachines', {
              id: vmId,
              listall: true
            })
            const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]
            record.vmName = vm?.name || vm?.displayname
          }

          // VM 정보를 가져온 후에 모달을 표시합니다
          this.selectedPciDevice = record
          this.showPciDeleteModal = true
          this.fetchPciConfigs(record)
        } catch (error) {
          console.error('Error fetching VM details:', error)
          this.$notifyError(error)
        }
      }
    },
    handleAllocationCompleted () {
      if (this.activeKey === '2') {
        this.fetchUsbDevices()
      } else if (this.activeKey === '3') {
        this.fetchLunDevices()
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
      } else {
        this.fetchData()
      }
    },
    refreshVMFiltering () {
      const params = { hostid: this.resource.id, details: 'min', listall: true }
      const vmStates = ['Running']

      this.loading = true

      return Promise.all(vmStates.map(state => {
        return api('listVirtualMachines', { ...params, state })
          .then(vmResponse => vmResponse.listvirtualmachinesresponse.virtualmachine || [])
      })).then(vmArrays => {
        const vms = vmArrays.flat()

        return Promise.all(vms.map(vm => {
          return api('listVirtualMachines', {
            id: vm.id,
            details: 'all'
          }).then(detailResponse => {
            return detailResponse.listvirtualmachinesresponse.virtualmachine[0]
          })
        }))
      }).then(detailedVms => {
        return api('listHostDevices', {
          id: this.resource.id
        }).then(latestResponse => {
          const latestDevices = latestResponse.listhostdevicesresponse?.listhostdevices?.[0]
          const latestAllocatedVmIds = new Set()

          if (latestDevices?.vmallocations) {
            Object.values(latestDevices.vmallocations).forEach(vmId => {
              if (vmId) {
                latestAllocatedVmIds.add(vmId.toString())
              }
            })
          }

          this.virtualmachines = detailedVms.filter(vm => {
            if (latestAllocatedVmIds.has(vm.id.toString())) {
              return false
            }
            if (vm.details && vm.details['extraconfig-1']) {
              return false
            }
            return true
          })

          if (this.$refs.hostDevicesTransfer) {
            this.$refs.hostDevicesTransfer.virtualmachines = this.virtualmachines
          }
        })
      }).finally(() => {
        this.loading = false
      })
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
        } else {
          this.dataItems = []
        }
      }).catch(error => {
        console.error('Error fetching USB devices:', error)
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
      }).then(response => {
        if (response.listhostlundevicesresponse?.listhostlundevices?.[0]) {
          const lunData = response.listhostlundevicesresponse.listhostlundevices[0]

          // LUN 디바이스 데이터 매핑
          const lunDevices = lunData.hostdevicesname.map((name, index) => ({
            key: index,
            hostDevicesName: name,
            hostDevicesText: lunData.hostdevicestext[index],
            virtualmachineid: (lunData.vmallocations && lunData.vmallocations[name]) || null,
            isAssigned: Boolean(lunData.vmallocations && lunData.vmallocations[name]),
            hasPartitions: lunData.haspartitions[name]
          }))

          this.dataItems = lunDevices
        } else {
          this.dataItems = []
        }
      }).catch(error => {
        console.error('Error fetching LUN devices:', error)
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.lun.devices')
        })
      }).finally(() => {
        this.loading = false
      })
      this.updateDataWithVmNames()
    },
    async fetchPciConfigs (record) {
      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[record.hostDevicesName]

        if (vmId) {
          const vmResponse = await api('listVirtualMachines', {
            id: vmId,
            listall: true,
            details: 'all'
          })
          const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

          this.pciConfigs = {}
          Object.entries(vm.details || {}).forEach(([key, value]) => {
            if (key.startsWith('extraconfig-') &&
                value.includes(record.hostDevicesName)) {
              this.pciConfigs[key] = {
                vmName: vm.name || vm.displayname,
                config: value
              }
            }
          })
        }
      } catch (error) {
        this.$notifyError(error)
      }
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
        if (vm && vm.state === 'Running') {
          this.$notification.warning({
            message: this.$t('label.warning'),
            description: this.$t('message.cannot.remove.device.vm.running')
          })
          this.showPciDeleteModal = false
          this.selectedPciDevice = null
          this.pciConfigs = {}
          return
        }

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
    closePciDeleteModal () {
      this.showPciDeleteModal = false
      this.selectedPciDevice = null
      this.pciConfigs = {}
    },
    showConfirmModal (device) {
      if (device.virtualmachineid) {
        api('listVirtualMachines', {
          id: device.virtualmachineid,
          listall: true
        }).then(response => {
          const vm = response?.listvirtualmachinesresponse?.virtualmachine?.[0]
          if (vm && vm.state === 'Running') {
            this.$notification.warning({
              message: this.$t('label.warning'),
              description: this.$t('message.cannot.remove.device.vm.running')
            })
            return
          }
          this.selectedPciDevice = device
          this.showPciDeleteModal = true
        }).catch(error => {
          this.$notifyError(error)
        })
      } else {
        this.selectedPciDevice = device
        this.showPciDeleteModal = true
      }
    },
    async updateDataWithVmNames () {
      try {
        const response = await api('listHostDevices', { id: this.resource.id })
        const data = response.listhostdevicesresponse?.listhostdevices?.[0]

        if (data && data.vmallocations) {
          const vmIds = [...new Set(Object.values(data.vmallocations))].filter(Boolean)
          const vmNameMap = await this.getVmNames(vmIds)

          // 기존 dataItems에 vmName 추가
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
          console.log('VM allocations:', devices.vmallocations)
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
                      virtualmachineid: null
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

          // 변경사항이 있을 때만 조용히 데이터 새로고침
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
    // 컴포넌트가 제거될 때 이벤트 리스너도 제거
    beforeDestroy () {
      const eventTypes = ['DestroyVM', 'ExpungeVM']
      eventTypes.forEach(eventType => {
        eventBus.emit('unregister-event', {
          eventType: eventType
        })
      })
    }
  },
  watch: {
    activeKey: {
      handler (newVal) {
        this.$nextTick(() => {
          this.updateDataWithVmNames()
        })
      }
    }
  },
  mounted () {
    this.fetchData()
    this.updateDataWithVmNames()
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
