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
    refreshVMList () {
      if (!this.resource || !this.resource.id) {
        this.loading = false
        return Promise.reject(new Error('Invalid resource'))
      }

      this.loading = true
      const params = { hostid: this.resource.id, details: 'all', listall: true }
      const vmStates = ['Running']

      return Promise.all([
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
        // 현재 USB 디바이스 할당 상태 가져오기
        api('listHostUsbDevices', { id: this.resource.id })
      ]).then(([vmArrays, usbResponse]) => {
        const vms = vmArrays.flat()
        const usbDevices = usbResponse.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const allocatedVmIds = new Set()

        // 모든 USB 디바이스에 할당된 VM ID 수집
        if (usbDevices?.vmallocations) {
          Object.values(usbDevices.vmallocations).forEach(vmId => {
            if (vmId) {
              allocatedVmIds.add(vmId.toString())
            }
          })
        }

        // 현재 디바이스에 할당된 VM ID가 있다면 제외
        if (this.resource.hostDevicesName && usbDevices?.vmallocations) {
          const currentVmId = usbDevices.vmallocations[this.resource.hostDevicesName]
          if (currentVmId) {
            allocatedVmIds.delete(currentVmId.toString())
          }
        }

        // 할당되지 않은 VM만 필터링
        this.virtualmachines = vms.filter(vm => !allocatedVmIds.has(vm.instanceId?.toString()))
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

        await api('updateHostUsbDevices', {
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
      console.log('handleDeallocate method called')
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }

      this.loading = true
      try {
        const hostDevicesName = this.resource.hostDevicesName
        console.log('Fetching USB devices for host:', this.resource.id)
        const response = await api('listHostUsbDevices', {
          id: this.resource.id
        })
        const devices = response.listhostusbdevicesresponse?.listhostusbdevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]

        if (!vmId) {
          console.log('No VM allocation found for device:', hostDevicesName)
          throw new Error('No VM allocation found for this device')
        }

        const xmlConfig = this.generateXmlConfig(hostDevicesName)

        console.log('Attempting to detach USB device with config:', xmlConfig)

        const detachResponse = await api('updateHostDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: null
        })

        if (!detachResponse || detachResponse.error) {
          console.error('Failed to detach USB device:', detachResponse?.error?.errortext)
          throw new Error(detachResponse?.error?.errortext || 'Failed to detach USB device')
        }

        console.log('Device detached successfully')

        this.$message.success(this.$t('message.success.remove.allocation'))
        await this.fetchUsbDevices()
        await this.updateUsbVmNames()
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

    generateXmlConfig (hostDeviceName) {
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
