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
  name: 'HostVhbaDevicesTransfer',
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
      form: reactive({ virtualmachineid: null })
    }
  },
  created () {
    this.fetchVMs()
  },
  methods: {
    refreshVMList () {
      if (!this.resource || !this.resource.id) {
        this.loading = false
        return Promise.reject(new Error('Invalid resource'))
      }

      this.loading = true
      const params = { hostid: this.resource.id, details: 'all', listall: true }
      const vmStates = ['Running', 'Stopped', 'Starting', 'Stopping']

      return Promise.all([
        // 다양한 상태의 VM 목록 가져오기
        Promise.all(vmStates.map(state => {
          return api('listVirtualMachines', { ...params, state })
            .then(vmResponse => {
              const vms = vmResponse.listvirtualmachinesresponse?.virtualmachine || []
              return vms.map(vm => ({
                ...vm,
                instanceId: vm.instancename ? vm.instancename.split('-')[2] : null
              }))
            })
            .catch(error => {
              console.error(`Error fetching VMs with state ${state}:`, error)
              return []
            })
        })),
        // 현재 vHBA 디바이스 할당 상태 가져오기
        api('listHostHbaDevices', { id: this.resource.id })
      ]).then(([vmArrays, hbaResponse]) => {
        const vms = vmArrays.flat()

        const hbaDevices = hbaResponse.listhosthbadevicesresponse?.listhosthbadevices?.[0]
        const allocatedVmIds = new Set()

        // 현재 vHBA 디바이스에 할당된 VM ID만 제외하고, 다른 디바이스에 할당된 VM들은 그대로 유지
        if (hbaDevices?.vmallocations && this.resource.hostDevicesName) {
          Object.entries(hbaDevices.vmallocations).forEach(([deviceName, vmId]) => {
            if (vmId && deviceName !== this.resource.hostDevicesName) {
              allocatedVmIds.add(vmId.toString())
            }
          })
        }

        // 할당되지 않은 VM만 필터링
        this.virtualmachines = vms.filter(vm => !allocatedVmIds.has(vm.instanceId?.toString()))

        // VM 정보 로깅
        this.virtualmachines.forEach(vm => {
          console.log(`Available VM: ${vm.name || vm.displayname} (ID: ${vm.id}, State: ${vm.state})`)
        })
      }).catch(error => {
        console.error('Error in refreshVMList:', error)
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
        // VM 상태 확인
        const selectedVM = this.virtualmachines.find(vm => vm.id === this.form.virtualmachineid)
        if (!selectedVM) {
          throw new Error('Selected VM not found')
        }

        // VM이 실행 중인지 확인
        if (selectedVM.state !== 'Running') {
          this.$notification.warning({
            message: this.$t('message.warning'),
            description: this.$t('message.vm.must.be.running.for.device.allocation')
          })
          return
        }

        // CloudStack의 VM UUID를 그대로 사용
        const vmUuid = selectedVM.id

        const xmlConfig = this.generateVhbaAllocationXmlConfig()

        // updateHostVhbaDevices API 호출 시 virtualmachineid에 VM UUID 전달
        const response = await api('updateHostVhbaDevices', {
          hostid: this.resource.id,
          hostdevicesname: this.resource.hostDevicesName,
          virtualmachineid: vmUuid,
          xmlconfig: xmlConfig,
          isattach: true
        })

        if (response.error) {
          throw new Error(response.error.errortext || 'Failed to allocate vHBA device')
        }

        this.$message.success(this.$t('message.success.allocate.device'))
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        this.$notification.error({
          message: this.$t('message.error'),
          description: error.message || this.$t('message.failed.to.allocate.vhba.device')
        })
      } finally {
        this.loading = false
      }
    },

    // vHBA 해제 함수 추가
    async deallocateVhbaDevice (record) {
      if (!this.resource || !this.resource.id) {
        this.$notifyError(this.$t('message.error.invalid.resource'))
        return
      }
      this.loading = true
      const hostDevicesName = record.hostDevicesName || this.resource.hostDevicesName
      try {
        // 1. vHBA 디바이스의 현재 할당 상태 확인
        const response = await api('listHostHbaDevices', { id: this.resource.id })
        const devices = response.listhosthbadevicesresponse?.listhosthbadevices?.[0]
        const vmAllocations = devices?.vmallocations || {}
        const vmId = vmAllocations[hostDevicesName]
        if (!vmId) throw new Error('No VM allocation found for this vHBA device')

        // 2. 해제용 XML 생성
        const xmlConfig = this.generateVhbaDeallocationXmlConfig()

        // 3. 해제 API 호출
        const detachResponse = await api('updateHostVhbaDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: null,
          currentvmid: vmId,
          xmlconfig: xmlConfig,
          isattach: false
        })

        if (!detachResponse || detachResponse.error) {
          throw new Error(detachResponse?.error?.errortext || 'Failed to detach vHBA device')
        }

        this.$message.success(this.$t('message.success.remove.allocation'))
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate vHBA device')
      } finally {
        this.loading = false
      }
    },

    // vHBA 할당용 XML 설정 생성 (libvirt SCSI 패스스루 표준)
    generateVhbaAllocationXmlConfig () {
      // hostDevicesName에서 숫자 추출
      const hostDeviceName = this.resource.hostDevicesName || ''
      const numberMatch = hostDeviceName.match(/(\d+)/)
      const adapterNumber = numberMatch ? numberMatch[1] : '0'
      return `
        <hostdev mode='subsystem' type='scsi' rawio='yes'>
          <source>
            <adapter name='scsi_host${adapterNumber}'/>
            <address bus='0' target='0' unit='3'/>
          </source>
        </hostdev>
      `.trim()
    },

    // vHBA 해제용 XML 설정 생성 (HBA와 동일하게)
    generateVhbaDeallocationXmlConfig () {
      const hostDeviceName = this.resource.hostDevicesName || ''
      const numberMatch = hostDeviceName.match(/(\d+)/)
      const adapterNumber = numberMatch ? numberMatch[1] : '0'
      return `
        <hostdev mode='subsystem' type='scsi' rawio='yes'>
          <source>
            <adapter name='scsi_host${adapterNumber}'/>
            <address bus='0' target='0' unit='1'/>
          </source>
        </hostdev>
      `.trim()
    },

    filterOption (input, option) {
      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
    },

    closeAction () {
      this.$emit('close-action')
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
