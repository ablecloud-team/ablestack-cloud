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
    <a-form
      class="form"
      layout="vertical"
      :ref="formRef"
      :model="form"
    >
      <a-alert type="warning">
        <template #message>
          <span v-html="$t('message.warning.host.device')" />
        </template>
      </a-alert>
      <br>
      <a-form-item :label="$t('label.virtualmachine')" name="virtualmachineid" ref="virtualmachineid">
        <a-select
          v-focus="true"
          v-model:value="form.virtualmachineid"
          :placeholder="$t('label.select.vm')"
          showSearch
          optionFilterProp="label"
          :filterOption="filterOption"
        >
          <a-select-option v-for="vm in virtualmachines" :key="vm.id" :label="vm.name || vm.displayname">
            {{ vm.name || vm.displayname }}
          </a-select-option>
        </a-select>
        <div class="actions">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form-item>
    </a-form>
  </a-spin>
</template>

<script>
import { reactive } from 'vue'
import { api } from '@/api'

export default {
  name: 'HostUsbDevicesTransfer',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  data () {
    return {
      virtualmachines: [],
      loading: true,
      form: reactive({ virtualmachineid: null }),
      resourceType: 'UserVm',
      currentVmDevices: new Set(),
      currentScsiAddresses: new Set()
    }
  },
  created () {
    this.fetchVMs()
  },
  watch: {
    showAddModal: {
      immediate: true,
      handler (newVal) {
        if (newVal) {
          this.fetchVMs()
        }
      }
    }
  },
  methods: {
    async refreshVMList () {
      if (!this.resource || !this.resource.id) {
        this.loading = false
        return Promise.reject(new Error('Invalid resource'))
      }

      this.loading = true
      const params = { hostid: this.resource.id, details: 'all', listall: true }
      const vmStates = ['Running']

      try {
        const [vmArrays, usbResponse] = await Promise.all([
          // 실행 중인 VM 목록 가져오기
          Promise.all(vmStates.map(state => {
            return api('listVirtualMachines', { ...params, state })
              .then(vmResponse => {
                const vms = vmResponse.listvirtualmachinesresponse?.virtualmachine || []
                return vms.map(vm => {
                  // VM의 인스턴스 번호 추출 (i-2-163-VM -> 163)
                  const instanceNumber = vm.instancename ? vm.instancename.split('-')[2] : null

                  return {
                    ...vm,
                    instanceNumber: instanceNumber
                  }
                })
              })
          })),
          // 현재 USB 디바이스 할당 상태 가져오기
          api('listHostUsbDevices', { id: this.resource.id })
        ])

        const vms = vmArrays.flat()
        const usbDevices = usbResponse.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const allocatedVmIds = new Set()

        // 모든 USB 디바이스에 할당된 VM ID 수집하고, 존재하지 않는 VM은 자동으로 할당 해제
        if (usbDevices?.vmallocations) {
          const processedDevices = new Set()

          for (const [deviceName, vmId] of Object.entries(usbDevices.vmallocations)) {
            if (vmId && !processedDevices.has(deviceName)) {
              try {
                // VM이 실제로 존재하는지 확인
                const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

                if (vm && vm.state !== 'Expunging') {
                  // VM이 정상적으로 존재하는 경우에만 할당된 VM ID에 추가
                  // VM의 인스턴스 번호 추출 (i-2-163-VM -> 163)
                  const instanceNumber = vm.instancename ? vm.instancename.split('-')[2] : null

                  if (instanceNumber) {
                    allocatedVmIds.add(instanceNumber.toString())
                  }
                } else {
                  // VM이 존재하지 않거나 Expunging 상태면 자동으로 할당 해제
                  try {
                    const xmlConfig = this.generateXmlConfig(deviceName)
                    await api('updateHostUsbDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null,
                      currentvmid: vmId,
                      xmlconfig: xmlConfig
                    })
                  } catch (error) {
                    // Failed to automatically deallocate USB device
                  }
                }
              } catch (error) {
                // VM 조회 실패 시에도 할당 해제 시도
                try {
                  const xmlConfig = this.generateXmlConfig(deviceName)
                  await api('updateHostUsbDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: deviceName,
                    virtualmachineid: null,
                    currentvmid: vmId,
                    xmlconfig: xmlConfig
                  })
                } catch (detachError) {
                  // Failed to automatically deallocate USB device after error
                }
              }
              processedDevices.add(deviceName)
            }
          }
        }

        // 현재 디바이스에 할당된 VM ID가 있다면 제외 (인스턴스 번호로 비교)
        if (this.resource.hostDevicesName && usbDevices?.vmallocations) {
          const currentVmId = usbDevices.vmallocations[this.resource.hostDevicesName]
          if (currentVmId) {
            try {
              // 현재 VM의 인스턴스 번호 가져오기
              const currentVmResponse = await api('listVirtualMachines', { id: currentVmId, listall: true })
              const currentVm = currentVmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

              if (currentVm && currentVm.instancename) {
                const currentInstanceNumber = currentVm.instancename.split('-')[2]

                if (currentInstanceNumber) {
                  allocatedVmIds.delete(currentInstanceNumber.toString())
                }
              }
            } catch (error) {
              // Error getting current VM info
            }
          }
        }

        // USB 디바이스가 할당된 VM들을 제외 (인스턴스 번호로 비교)
        this.virtualmachines = vms.filter(vm => {
          // VM의 인스턴스 번호 추출 (i-2-163-VM -> 163)
          const instanceNumber = vm.instancename ? vm.instancename.split('-')[2] : null

          // USB 디바이스가 할당된 VM은 제외 (인스턴스 번호로 비교)
          if (instanceNumber && allocatedVmIds.has(instanceNumber)) {
            return false
          }
          return true
        })

        if (this.virtualmachines.length === 0) {
          this.$notification.warning({
            message: this.$t('message.warning'),
            description: allocatedVmIds.size > 0
              ? 'All VMs already have USB devices allocated. Please deallocate a USB device first to assign to another VM.'
              : 'No VMs with valid instance names found. Please ensure VMs are properly running.'
          })
        }
      } catch (error) {
        this.$notifyError(error.message || 'Failed to fetch VMs')
      } finally {
        this.loading = false
      }
    },

    fetchVMs () {
      this.form.virtualmachineid = undefined
      return this.refreshVMList()
    },

    async handleSubmit () {
      if (!this.form.virtualmachineid) {
        this.$notification.error({
          message: this.$t('message.error'),
          description: this.$t('message.please.select.vm')
        })
        return
      }

      this.loading = true
      try {
        const xmlConfig = this.generateXmlConfig(this.resource.hostDevicesName)

        const response = await api('updateHostUsbDevices', {
          hostid: this.resource.id,
          hostdevicesname: this.resource.hostDevicesName,
          hostdevicestext: this.resource.hostDevicesText || '',
          virtualmachineid: this.form.virtualmachineid,
          xmlconfig: xmlConfig
        })

        if (response && response.error) {
          throw new Error(response.error.errortext || 'Failed to allocate USB device')
        }

        this.$message.success(this.$t('message.success.allocate.device'))

        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        this.$notification.error({
          message: this.$t('label.error'),
          description: error.message || 'Failed to allocate USB device'
        })
      } finally {
        this.loading = false
      }
    },

    async handleDeallocate () {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }

      this.loading = true
      try {
        const hostDevicesName = this.resource.hostDevicesName
        const response = await api('listHostUsbDevices', {
          id: this.resource.id
        })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]

        if (!vmId) {
          throw new Error('No VM allocation found for this device')
        }

        // VM 상태 확인
        try {
          const vmResponse = await api('listVirtualMachines', {
            id: vmId,
            listall: true
          })
          const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

          if (!vm) {
            // VM not found, proceeding with deallocation
          } else if (vm.state === 'Expunging') {
            // VM is in Expunging state, proceeding with deallocation
          } else if (vm.state !== 'Running' && vm.state !== 'Stopped') {
            // VM is in other state, proceeding with deallocation
          }
        } catch (vmError) {
          // Failed to check VM state, proceeding with deallocation
        }

        const xmlConfig = this.generateXmlConfig(hostDevicesName)

        const detachResponse = await api('updateHostUsbDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: null,
          currentvmid: vmId,
          xmlconfig: xmlConfig
        })

        if (!detachResponse || detachResponse.error) {
          throw new Error(detachResponse?.error?.errortext || 'Failed to detach USB device')
        }

        this.$message.success(this.$t('message.success.remove.allocation'))
        // VM 목록 새로고침
        await this.refreshVMList()
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate USB device')
      } finally {
        this.loading = false
      }
    },

    generateXmlConfig (hostDeviceName) {
      try {
        let bus = '0x001'
        let device = '0x01'

        // 다양한 USB 디바이스 이름 형식 지원
        // 1. "001 Device 003" 형식
        let match = hostDeviceName.match(/(\d+)\s+Device\s+(\d+)/i)
        if (match && match.length >= 3) {
          const busNum = parseInt(match[1], 10)
          const deviceNum = parseInt(match[2], 10)

          if (!isNaN(busNum) && !isNaN(deviceNum)) {
            bus = '0x' + busNum.toString(16).padStart(3, '0')
            device = '0x' + deviceNum.toString(16).padStart(2, '0')
          }
        } else {
          // 2. "001:003" 형식
          match = hostDeviceName.match(/(\d+):(\d+)/)
          if (match && match.length >= 3) {
            const busNum = parseInt(match[1], 10)
            const deviceNum = parseInt(match[2], 10)

            if (!isNaN(busNum) && !isNaN(deviceNum)) {
              bus = '0x' + busNum.toString(16).padStart(3, '0')
              device = '0x' + deviceNum.toString(16).padStart(2, '0')
            }
          } else {
            // 3. "001.003" 형식
            match = hostDeviceName.match(/(\d+)\.(\d+)/)
            if (match && match.length >= 3) {
              const busNum = parseInt(match[1], 10)
              const deviceNum = parseInt(match[2], 10)

              if (!isNaN(busNum) && !isNaN(deviceNum)) {
                bus = '0x' + busNum.toString(16).padStart(3, '0')
                device = '0x' + deviceNum.toString(16).padStart(2, '0')
              }
            } else {
              // 4. 기존 정규식 (숫자+문자+숫자)
              match = hostDeviceName.match(/(\d+)\D+(\d+)/)
              if (match && match.length >= 3) {
                const busNum = parseInt(match[1], 10)
                const deviceNum = parseInt(match[2], 10)

                if (!isNaN(busNum) && !isNaN(deviceNum)) {
                  bus = '0x' + busNum.toString(16).padStart(3, '0')
                  device = '0x' + deviceNum.toString(16).padStart(2, '0')
                }
              }
            }
          }
        }

        return `
        <hostdev mode='subsystem' type='usb'>
          <source>
            <address type='usb' bus='${bus}' device='${device}' />
          </source>
        </hostdev>
        `.trim()
      } catch (error) {
        // 기본값 반환
        return `
        <hostdev mode='subsystem' type='usb'>
          <source>
            <address type='usb' bus='0x001' device='0x01' />
          </source>
        </hostdev>
        `.trim()
      }
    },

    closeAction () {
      this.$emit('close-action')
    },

    filterOption (input, option) {
      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
    }
  }
}
</script>

<style lang="scss" scoped>
.form {
  width: 80vw;

  @media (min-width: 500px) {
    width: 475px;
  }
}
.actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
  button {
    &:not(:last-child) {
      margin-right: 10px;
    }
  }
}
</style>
