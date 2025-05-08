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
      resourceType: 'UserVm'
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

      // Promise.all을 사용하여 각 상태별로 VM 조회
      return Promise.all(vmStates.map(state => {
        return api('listVirtualMachines', { ...params, state })
          .then(vmResponse => vmResponse.listvirtualmachinesresponse.virtualmachine || [])
      })).then(vmArrays => {
        // 모든 상태의 VM을 하나의 배열로 합침
        const vms = vmArrays.flat()

        // 각 VM에 대해 상세 정보를 추가로 조회
        return Promise.all(vms.map(vm => {
          return api('listVirtualMachines', {
            id: vm.id,
            details: 'all'
          }).then(detailResponse => {
            return detailResponse.listvirtualmachinesresponse.virtualmachine[0]
          })
        }))
      }).then(detailedVms => {
        // 실시간 필터링을 위해 최신 할당 상태 다시 확인
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

          // 최신 상태로 필터링
          this.virtualmachines = detailedVms.filter(vm => {
            // 이미 할당된 VM 제외
            if (latestAllocatedVmIds.has(vm.id.toString())) {
              return false
            }
            // PCI 디바이스가 적용된 VM 제외
            if (vm.details && vm.details['extraconfig-1']) {
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
      const xmlConfig = this.generateXmlConfig(hostDevicesName)

      // 할당 전에 다시 한번 VM 상태 확인
      api('listVirtualMachines', {
        id: this.form.virtualmachineid,
        details: 'all'
      }).then(response => {
        const vm = response.listvirtualmachinesresponse.virtualmachine[0]
        if (vm.details && vm.details['extraconfig-1']) {
          throw new Error(this.$t('message.device.already.allocated'))
        }

        const params = {
          id: vm.id,
          'details[0].extraconfig-1': xmlConfig
        }

        if (vm.details) {
          Object.entries(vm.details).forEach(([key, value]) => {
            if (key !== 'extraconfig-1') {
              params[`details[0].${key}`] = value
            }
          })
        }

        return api('updateVirtualMachine', params)
      }).then(() => {
        return api('updateHostDevices', {
          hostid: this.resource.id,
          hostdevicesname: hostDevicesName,
          virtualmachineid: this.form.virtualmachineid
        })
      }).then(() => {
        this.$message.success(this.$t('message.success.allocate.device'))
        // VM 리스트 새로고침
        return this.refreshVMList()
      }).then(() => {
        // 성공 후 form 초기화
        this.form.virtualmachineid = undefined
        // 부모 컴포넌트에 완료 이벤트 발생
        this.$emit('device-allocated')
        this.$emit('allocation-completed', hostDevicesName, this.form.virtualmachineid)
        // 모달 닫기
        this.$emit('close-action')
      }).catch(error => {
        this.$notifyError(error.message || 'Failed to allocate device')
        this.formRef.value.scrollToField('virtualmachineid')
        // 에러 발생 시에도 VM 리스트 새로고침
        this.refreshVMList()
      }).finally(() => {
        this.loading = false
      })
    },

    generateXmlConfig (hostDevicesName) {
      const [pciAddress] = hostDevicesName.split(' ')
      const [bus, slotFunction] = pciAddress.split(':')
      const [slot, func] = slotFunction.split('.')

      return `
      <devices>
        <hostdev mode='subsystem' type='pci' managed='yes'>
        <source>
          <address domain='0x0000' bus='${parseInt(bus, 16)}' slot='0x${parseInt(slot, 16)}' function='0x${parseInt(func, 16)}'/>
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
