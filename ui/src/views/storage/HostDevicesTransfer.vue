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
  name: 'HostDevicesTransfer',
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
    refreshVMList () {
      if (!this.resource || !this.resource.id) {
        this.loading = false
        return Promise.reject(new Error('Invalid resource'))
      }

      this.loading = true
      const params = { hostid: this.resource.id, details: 'min', listall: true }
      const vmStates = ['Running']

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
            const vmDetails = detailResponse.listvirtualmachinesresponse.virtualmachine[0]

            // XML 형식이 아닌 extraconfig 제거
            if (vmDetails.details) {
              const filteredDetails = {}
              Object.entries(vmDetails.details).forEach(([key, value]) => {
                if (!key.startsWith('extraconfig-') || value.includes('<hostdev')) {
                  filteredDetails[key] = value
                }
              })
              vmDetails.details = filteredDetails
            }

            return vmDetails
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
            if (latestAllocatedVmIds.has(vm.id.toString()) &&
                vm.details?.['extraconfig-1']?.toLowerCase().includes('usb') ||
                vm.details?.['extraconfig-1']?.toLowerCase().includes('disk type=\'block\' device=\'lun\'')) {
              return false
            }
            return true
          })
        })
      }).catch(error => {
        this.$notifyError(error.message || 'Failed to fetch VMs')
      }).finally(() => {
        this.loading = false
      })
    },

    fetchVMs () {
      this.form.virtualmachineid = undefined
      return this.refreshVMList()
    },

    handleSubmit () {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }

      if (!this.form.virtualmachineid) {
        this.$notifyError(this.$t('message.error.select.vm'))
        return
      }

      this.loading = true
      const hostDevicesName = this.resource.hostDevicesName

      api('listVirtualMachines', {
        id: this.form.virtualmachineid,
        details: 'all'
      }).then(response => {
        const vm = response?.listvirtualmachinesresponse?.virtualmachine?.[0]
        const details = vm?.details || {}

        // VM 이벤트 리스너 등록
        this.registerVMEventListener(vm.id, hostDevicesName)

        let nextConfigNum = 1
        let lastXmlConfig = 0

        // XML 설정이 있는 마지막 extraconfig 번호 찾기
        Object.entries(details).forEach(([key, value]) => {
          if (key.startsWith('extraconfig-') && value.includes('<hostdev')) {
            const num = parseInt(key.split('-')[1])
            lastXmlConfig = Math.max(lastXmlConfig, num)
          }
        })

        nextConfigNum = lastXmlConfig + 1
        const xmlConfig = this.generateXmlConfig(hostDevicesName)
        const params = {
          id: vm.id,
          [`details[0].extraconfig-${nextConfigNum}`]: xmlConfig
        }

        // 기존 XML 설정만 유지
        Object.entries(details).forEach(([key, value]) => {
          if (key.startsWith('extraconfig-') && value.includes('<hostdev')) {
            params[`details[0].${key}`] = value
          } else if (!key.startsWith('extraconfig-')) {
            params[`details[0].${key}`] = value
          }
        })

        return api('updateVirtualMachine', params)
          .then(() => {
            return api('updateHostDevices', {
              hostid: this.resource.id,
              hostdevicesname: hostDevicesName,
              virtualmachineid: this.form.virtualmachineid
            })
          })
      }).then(() => {
        this.$message.success(this.$t('message.success.allocate.device'))
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('close-action')
      }).catch(error => {
        this.$notifyError(error)
        this.formRef.value.scrollToField('virtualmachineid')
      }).finally(() => {
        this.loading = false
      })
    },

    registerVMEventListener (vmId, hostDevicesName) {
      const eventTypes = ['DestroyVM', 'ExpungeVM', 'UpdateVirtualMachine']

      eventTypes.forEach(eventType => {
        this.$store.dispatch('event/subscribe', {
          eventType: eventType,
          resourceId: vmId,
          callback: async () => {
            try {
              if (eventType === 'UpdateVirtualMachine') {
                // VM의 현재 상태 확인
                const response = await api('listVirtualMachines', {
                  id: vmId,
                  details: 'all'
                })

                const vm = response?.listvirtualmachinesresponse?.virtualmachine?.[0]
                const details = vm?.details || {}
                const hasDeviceConfig = Object.values(details).some(value =>
                  value.includes('<hostdev') && value.includes(hostDevicesName)
                )
                if (hasDeviceConfig) {
                  return
                }
              }

              // 디비에서 호스트 디바이스 할당 정보 삭제
              await api('updateHostDevices', {
                hostid: this.resource.id,
                hostdevicesname: hostDevicesName,
                virtualmachineid: vmId
              })
            } catch (error) {
              console.error('Failed to delete host device allocation:', error)
            }
          }
        })
      })
    },

    generateXmlConfig (hostDeviceName) {
      const [pciAddress] = hostDeviceName.split(' ')
      const [bus, slotFunction] = pciAddress.split(':')
      const [slot, func] = slotFunction.split('.')

      return `
      <devices>
      <hostdev mode='subsystem' type='pci' managed='yes'>
        <source>
          <address domain='0x0000' bus='0x${bus}' slot='0x${slot}' function='0x${func}'/>
        </source>
      </hostdev>
      </devices>
      `.trim()
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
