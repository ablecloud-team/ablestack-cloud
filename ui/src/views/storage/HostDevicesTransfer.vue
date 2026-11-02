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
import { generatePciDeviceXml } from '@/utils/pci'

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
            if (vm.state === 'Running') {
              return true
            }
            return false
          })
          console.log('Filtered VMs for allocation (All Running VMs):', this.virtualmachines.length)
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

    async handleSubmit () {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }

      if (!this.form.virtualmachineid) {
        this.$notifyError(this.$t('message.error.select.vm'))
        return
      }

      this.loading = true
      try {
        const xmlConfig = this.generateXmlConfig(this.resource.hostDevicesName)
        await api('updateHostDevices', {
          hostid: this.resource.id,
          hostdevicesname: this.resource.hostDevicesName,
          hostdevicestext: this.resource.hostDevicesText || '',
          virtualmachineid: this.form.virtualmachineid,
          xmlconfig: xmlConfig
        })
        this.$message.success(this.$t('message.success.allocate.device'))
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.loading = false
      }
    },

    generateXmlConfig (hostDeviceName) {
      return generatePciDeviceXml(hostDeviceName)
    },

    closeAction () {
      this.$emit('close-action')
    },

    filterOption (input, option) {
      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
    },

    showConfirmModal (device) {
      // vmName을 강제로 할당
      const vmName = this.vmNames[device.hostDevicesName] || ''
      this.selectedPciDevice = { ...device, vmName }
      this.showPciDeleteModal = true
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
