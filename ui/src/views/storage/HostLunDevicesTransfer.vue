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
          <span v-html="$t('message.warning.host.devices')" />
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
  name: 'HostLunDevicesTransfer',
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
      currentScsiAddresses: new Set(),
      currentVmId: null,
      currentVmName: ''
    }
  },
  created () {
    if (this.resource && this.resource.id) {
      this.fetchVMs()
      this.fetchCurrentAllocation()
    }
  },
  watch: {
    showAddModal: {
      immediate: true,
      handler (newVal) {
        if (newVal && this.resource && this.resource.id) {
          this.fetchVMs()
        }
      }
    },
    resource: {
      immediate: true,
      handler () {
        if (this.resource && this.resource.id) {
          this.fetchCurrentAllocation()
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
        const [vmArrays, lunResponse] = await Promise.all([
          // 실행 중인 VM 목록 가져오기
          Promise.all(vmStates.map(state => {
            return api('listVirtualMachines', { ...params, state })
              .then(vmResponse => {
                const vms = vmResponse.listvirtualmachinesresponse?.virtualmachine || []
                return vms.map(vm => ({
                  ...vm,
                  instanceId: vm.instancename ? vm.instancename.split('-')[2] : null
                }))
              })
          })),
          // 현재 LUN 디바이스 할당 상태 가져오기
          api('listHostLunDevices', { id: this.resource.id })
        ])

        const vms = vmArrays.flat()
        const lunDevices = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]
        const allocatedVmIds = new Set()

        // 모든 LUN 디바이스에 할당된 VM ID 수집 (다중 할당 허용하므로 모든 할당 유지)
        if (lunDevices?.vmallocations) {
          for (const [deviceName, vmId] of Object.entries(lunDevices.vmallocations)) {
            if (vmId) {
              try {
                // VM이 실제로 존재하는지 확인
                const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

                if (vm && vm.state !== 'Expunging') {
                  allocatedVmIds.add(vmId.toString())
                } else {
                  // VM이 존재하지 않거나 Expunging 상태면 자동으로 할당 해제
                  try {
                    const xmlConfig = this.generateXmlConfig(deviceName)
                    await api('updateHostLunDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null,
                      xmlconfig: xmlConfig,
                      isattach: false
                    })
                    console.log(`Automatically deallocated LUN device ${deviceName} from deleted/expunging VM ${vmId}`)
                  } catch (error) {
                    console.error(`Failed to automatically deallocate LUN device ${deviceName}:`, error)
                  }
                }
              } catch (error) {
                console.error(`Error checking VM ${vmId} for device ${deviceName}:`, error)
                // VM 조회 실패 시에도 할당 해제 시도
                try {
                  const xmlConfig = this.generateXmlConfig(deviceName)
                  await api('updateHostLunDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: deviceName,
                    virtualmachineid: null,
                    xmlconfig: xmlConfig,
                    isattach: false
                  })
                  console.log(`Automatically deallocated LUN device ${deviceName} after VM check error`)
                } catch (detachError) {
                  console.error(`Failed to automatically deallocate LUN device ${deviceName} after error:`, detachError)
                }
              }
            }
          }
        }

        // 현재 디바이스에 할당된 VM ID는 제외하지 않음 (다중 할당 허용)
        // 모든 VM을 표시하되, 할당된 VM은 별도 표시

        // 모든 VM을 표시 (할당된 VM도 포함)
        const detailedVms = await Promise.all(vms.map(vm => {
          return api('listVirtualMachines', {
            id: vm.id,
            details: 'all'
          }).then(detailResponse => {
            const detailedVm = detailResponse.listvirtualmachinesresponse.virtualmachine[0]
            return {
              ...detailedVm,
              instanceId: vm.instanceId,
              isAllocated: allocatedVmIds.has(vm.instanceId?.toString())
            }
          })
        }))

        this.virtualmachines = detailedVms
      } catch (error) {
        this.$notifyError(error.message || 'Failed to fetch VMs')
      } finally {
        this.loading = false
      }
    },

    fetchVMs () {
      this.form.virtualmachineid = undefined
      if (!this.resource || !this.resource.id) {
        return
      }
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
        const xmlConfig = this.generateXmlConfig()

        await api('updateHostLunDevices', {
          hostid: this.resource.id,
          hostdevicesname: this.resource.hostDevicesName,
          virtualmachineid: this.form.virtualmachineid,
          xmlconfig: xmlConfig
        })

        this.$message.success(this.$t('message.success.allocate.device'))

        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error)
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
        const response = await api('listHostLunDevices', {
          id: this.resource.id
        })
        const devices = response.listhostlundevicesresponse?.listhostlundevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]

        if (!vmId) {
          throw new Error('No VM allocation found for this device')
        }

        const xmlConfig = this.generateXmlConfig(hostDevicesName)

        const detachResponse = await api('updateHostLunDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: null,
          currentvmid: vmId,
          xmlconfig: xmlConfig,
          isattach: false
        })

        if (!detachResponse || detachResponse.error) {
          console.error('Failed to detach LUN device:', detachResponse?.error?.errortext)
          throw new Error(detachResponse?.error?.errortext || 'Failed to detach LUN device')
        }

        this.$message.success(this.$t('message.success.remove.allocation'))
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate LUN device')
      } finally {
        this.loading = false
      }
    },

    generateXmlConfig (devicePath) {
      // devicePath가 제공되지 않은 경우 resource에서 가져오기
      const targetDevicePath = devicePath || this.resource.hostDevicesName

      // 디바이스 이름에서 target 디바이스 이름 추출
      let targetDev = 'sdc'
      const match = targetDevicePath.match(/\/dev\/([a-z]+[a-z0-9]*)$/)
      if (match) {
        targetDev = match[1]
      }

      // 표준 LUN 디바이스 XML 설정 (address 없이)
      return `
        <disk type='block' device='lun'>
          <driver name='qemu' type='raw'/>
          <source dev='${targetDevicePath}'/>
          <target dev='${targetDev}' bus='scsi'/>
        </disk>
      `.trim()
    },

    closeAction () {
      this.$emit('close-action')
    },

    filterOption (input, option) {
      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
    },

    async fetchCurrentAllocation () {
      if (!this.resource || !this.resource.id || !this.resource.hostDevicesName) {
        this.currentVmId = null
        this.currentVmName = ''
        return
      }
      try {
        const response = await api('listHostLunDevices', { id: this.resource.id })
        const devices = response.listhostlundevicesresponse?.listhostlundevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[this.resource.hostDevicesName]
        this.currentVmId = vmId || null
        if (vmId) {
          const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
          const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
          this.currentVmName = vm ? (vm.displayname || vm.name) : this.$t('label.no.vm.assigned')
        } else {
          this.currentVmName = this.$t('label.no.vm.assigned')
        }
      } catch (e) {
        this.currentVmId = null
        this.currentVmName = ''
      }
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
