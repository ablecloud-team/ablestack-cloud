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
    <a-tabs v-model:activeKey="activeKey" tab-position="top">
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
            <template v-if="column.key === 'hostDevicesName'">{{ record.hostDevicesName }}</template>
            <template v-if="column.key === 'hostDevicesText'">{{ record.hostDevicesText }}</template>
            <template v-if="column.key === 'action'">
              <a-button
                :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                size="medium"
                shape="circle"
                :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                @click="isDeviceAssigned(record) ? handleDelete(record) : openModal(record)"
                :loading="loading">
                <template #icon>
                  <delete-outlined v-if="isDeviceAssigned(record)" />
                  <plus-outlined v-else />
                </template>
              </a-button>
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
            <template v-if="column.key === 'action'">
              <a-button
                :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                size="medium"
                shape="circle"
                :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                @click="isDeviceAssigned(record) ? handleDelete(record) : openModal(record)"
                :loading="loading">
                <template #icon>
                  <delete-outlined v-if="isDeviceAssigned(record)" />
                  <plus-outlined v-else />
                </template>
              </a-button>
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
            <template v-if="column.key === 'action'">
              <a-button
                :type="isDeviceAssigned(record) ? 'danger' : 'primary'"
                size="medium"
                shape="circle"
                :tooltip="isDeviceAssigned(record) ? $t('label.remove') : $t('label.create')"
                @click="isDeviceAssigned(record) ? handleDelete(record) : openModal(record)"
                :loading="loading">
                <template #icon>
                  <delete-outlined v-if="isDeviceAssigned(record)" />
                  <plus-outlined v-else />
                </template>
              </a-button>
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
      @cancel="closeModals">
      <HostDevicesTransfer
        ref="hostDevicesTransfer"
        :resource="selectedResource"
        @close-action="closeModals"
        @allocation-completed="handleAllocationCompleted"
        @device-allocated="handleDeviceAllocated" />
    </a-modal>
  </div>
</template>

<script>
import { api } from '@/api'
import { IdcardOutlined, PlusOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import HostDevicesTransfer from '@/views/storage/HostDevicesTransfer'

export default {
  name: 'ListHostDevices',
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
      virtualmachines: []
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
      const query = this.usbSearchQuery.toLowerCase()
      return this.tableSource.filter(item => {
        const deviceName = String(item.hostDevicesName || '')
        const deviceText = String(item.hostDevicesText || '')
        const isUsb = deviceName.toLowerCase().includes('usb')
        if (!query) return isUsb
        return isUsb && (
          deviceName.toLowerCase().includes(query) ||
          deviceText.toLowerCase().includes(query)
        )
      })
    },

    filteredLunDevices () {
      const query = this.lunSearchQuery.toLowerCase()
      return this.tableSource.filter(item => {
        const deviceName = String(item.hostDevicesName || '')
        const deviceText = String(item.hostDevicesText || '')
        const isLun = deviceName.toLowerCase().includes('lun')
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
  },
  methods: {
    fetchData () {
      this.loading = true
      this.selectedDevices = []

      api('listHostDevices', {
        id: this.resource.id
      }).then(json => {
        const response = json.listhostdevicesresponse
        if (response && response.listhostdevices && response.listhostdevices.length > 0) {
          const data = response.listhostdevices[0]
          const vmAllocations = data.vmallocations || {}

          // VM 할당 정보가 있는 디바이스들에 대해 실제 VM 상태 확인
          const vmChecks = Object.entries(vmAllocations)
            .filter(([_, vmId]) => vmId) // vmId가 있는 경우만 처리
            .map(([deviceName, vmId]) => {
              return api('listVirtualMachines', {
                id: vmId,
                listall: true,
                details: 'all'
              }).then(vmResponse => {
                const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]
                // extraconfig-1이 없으면 할당되지 않은 것으로 처리
                if (!vm?.details?.['extraconfig-1']) {
                  vmAllocations[deviceName] = null
                }
                return null
              }).catch(() => {
                // VM 조회 실패시 할당되지 않은 것으로 처리
                vmAllocations[deviceName] = null
                return null
              })
            })

          Promise.all(vmChecks).then(() => {
            this.dataItems = data.hostdevicesname.map((deviceName, index) => {
              const vmId = vmAllocations[deviceName]
              return {
                hostDevicesName: deviceName,
                hostDevicesText: data.hostdevicestext[index] || '',
                virtualmachineid: vmId,
                isAssigned: Boolean(vmId)
              }
            })

            this.selectedDevices = Object.keys(vmAllocations).filter(key => vmAllocations[key])
          })
        } else {
          this.dataItems = []
          this.selectedDevices = []
          localStorage.removeItem(`hostDevices_${this.resource.id}`)
        }
      }).catch(error => {
        console.error('Error fetching host devices:', error)
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    },
    isDeviceAssigned (record) {
      return record.virtualmachineid != null ||
             record.isAssigned ||
             this.selectedDevices.includes(record.hostDevicesName)
    },
    openModal (record) {
      this.selectedResource = { ...this.resource, hostDevicesName: record.hostDevicesName }
      this.showAddModal = true
    },
    closeModals () {
      this.showAddModal = false
      this.selectedResource = null
      this.fetchData()
    },
    onUsbSearch () {
      // USB 디바이스 검색 처리
    },
    onLunSearch () {
      // LUN 디바이스 검색 처리
    },
    onOtherSearch () {
      // 기타 디바이스 검색 처리
    },
    handleDelete (record) {
      api('listHostDevices', {
        id: this.resource.id
      }).then(response => {
        const devices = response.listhostdevicesresponse?.listhostdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[record.hostDevicesName]

        if (!vmId) {
          return api('updateHostDevices', {
            hostid: this.resource.id,
            hostdevicesname: record.hostDevicesName,
            value: null,
            virtualmachineid: null
          }).then(() => {
            return Promise.all([
              this.fetchData(),
              this.refreshVMFiltering()
            ]).then(() => {
              this.$message.success(this.$t('message.success.remove.allocation'))
            })
          })
        }

        return api('listVirtualMachines', {
          id: vmId,
          listall: true,
          details: 'all'
        }).then(vmResponse => {
          const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]
          const vmName = vm?.displayname || vm?.name || this.$t('label.unknown.vm')

          this.$confirm({
            title: `${vmName} ${this.$t('message.delete.device.allocation')}`,
            okText: this.$t('label.ok'),
            cancelText: this.$t('label.cancel'),
            onOk: () => {
              this.loading = true
              const params = {
                id: vm.id
              }

              if (vm.details) {
                Object.entries(vm.details).forEach(([key, value]) => {
                  if (key !== 'extraconfig-1') {
                    params[`details[0].${key}`] = value
                  }
                })
              }

              return api('updateVirtualMachine', params)
                .then(() => {
                  return api('updateHostDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: record.hostDevicesName,
                    value: null,
                    virtualmachineid: null
                  })
                })
                .then(() => {
                  return Promise.all([
                    this.fetchData(),
                    this.refreshVMFiltering()
                  ])
                })
                .then(() => {
                  this.$message.success(this.$t('message.success.remove.allocation'))
                  if (this.showAddModal && this.$refs.hostDevicesTransfer) {
                    this.$refs.hostDevicesTransfer.fetchVMs()
                  }
                })
                .catch(error => {
                  console.error('Error during deletion:', error)
                  this.$notification.error({
                    message: this.$t('label.error'),
                    description: error.message || this.$t('message.error.remove.allocation')
                  })
                })
                .finally(() => {
                  this.loading = false
                })
            }
          })
        })
      }).catch(error => {
        console.error('Error fetching device or VM details:', error)
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || this.$t('message.error.fetch.vm')
        })
      })
    },
    handleAllocationCompleted (hostDevicesName, vmId) {
      // 할당 완료 후 즉시 데이터 새로고침
      this.loading = true
      api('listHostDevices', {
        id: this.resource.id
      }).then(json => {
        const response = json.listhostdevicesresponse
        if (response && response.listhostdevices && response.listhostdevices.length > 0) {
          const data = response.listhostdevices[0]
          const vmAllocations = data.vmallocations || {}

          this.dataItems = data.hostdevicesname.map((deviceName, index) => {
            const vmId = vmAllocations[deviceName]
            return {
              hostDevicesName: deviceName,
              hostDevicesText: data.hostdevicestext[index] || '',
              virtualmachineid: vmId,
              isAssigned: Boolean(vmId)
            }
          })

          this.selectedDevices = Object.keys(vmAllocations).filter(key => vmAllocations[key])
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
        this.closeModals()
        this.fetchData()
      })
    },
    handleDeviceAllocated () {
      this.fetchData()
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
</style>
