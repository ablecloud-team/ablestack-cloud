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
          :notFoundContent="$t('message.pci.no.eligible.vm')"
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
import { getAPI, postAPI } from '@/api'
import { generatePciDeviceXml } from '@/utils/util'

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
    async refreshVMList () {
      if (!this.resource || !this.resource.id) {
        this.loading = false
        return Promise.reject(new Error('Invalid resource'))
      }

      this.loading = true
      this.virtualmachines = []
      try {
        const responses = await Promise.all(['Running', 'Stopped'].map(state => getAPI('listVirtualMachines', {
          hostid: this.resource.id,
          state,
          details: 'min',
          listall: true,
          pagesize: -1
        })))
        const vms = responses.flatMap(response => response.listvirtualmachinesresponse?.virtualmachine || [])
          .filter(vm => ['Running', 'Stopped'].includes(vm.state))
        this.virtualmachines = Array.from(new Map(vms.map(vm => [vm.id, vm])).values())
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.loading = false
      }
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
        const vmId = this.form.virtualmachineid
        const hostId = this.resource.id
        const deviceName = this.resource.hostDevicesName
        const responses = await Promise.all(['Running', 'Stopped'].map(state => getAPI('listVirtualMachines', { id: vmId, hostid: hostId, state, listall: true, details: 'min' })))
        const vm = responses.flatMap(response => response.listvirtualmachinesresponse?.virtualmachine || [])
          .find(vm => vm.id === vmId && ['Running', 'Stopped'].includes(vm.state))
        if (this.form.virtualmachineid !== vmId || this.resource.id !== hostId || this.resource.hostDevicesName !== deviceName) return
        if (!vm) {
          this.$notification.warning({ message: this.$t('label.warning'), description: this.$t('message.pci.vm.not.eligible') })
          this.form.virtualmachineid = undefined
          await this.refreshVMList()
          return
        }
        await postAPI('updateHostDevices', {
          hostid: hostId,
          hostdevicesname: deviceName,
          hostdevicestext: this.resource.hostDevicesText || '',
          virtualmachineid: vmId,
          xmlconfig: xmlConfig
        })
        this.$message.success(this.$t('label.vmdevice.pciSaved'))
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
