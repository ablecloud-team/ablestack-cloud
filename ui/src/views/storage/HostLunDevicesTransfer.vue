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
      <a-alert
        v-if="currentVmName"
        type="info"
        style="margin-bottom: 10px;"
        :message="`${$t('label.current.allocated.vm')}: ${currentVmName}`"
      />
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
        // 현재 LUN 디바이스 할당 상태 가져오기
        api('listHostLunDevices', { id: this.resource.id })
      ]).then(([vmArrays, lunResponse]) => {
        const vms = vmArrays.flat()
        const lunDevices = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]
        const allocatedVmIds = new Set()

        // 모든 LUN 디바이스에 할당된 VM ID 수집
        if (lunDevices?.vmallocations) {
          Object.values(lunDevices.vmallocations).forEach(vmId => {
            if (vmId) {
              allocatedVmIds.add(vmId.toString())
            }
          })
        }

        // 현재 디바이스에 할당된 VM ID가 있다면 제외
        if (this.resource.hostDevicesName && lunDevices?.vmallocations) {
          const currentVmId = lunDevices.vmallocations[this.resource.hostDevicesName]
          if (currentVmId) {
            allocatedVmIds.delete(currentVmId.toString())
          }
        }

        // 할당되지 않은 VM만 필터링
        return Promise.all(vms
          .filter(vm => !allocatedVmIds.has(vm.instanceId?.toString()))
          .map(vm => {
            return api('listVirtualMachines', {
              id: vm.id,
              details: 'all'
            }).then(detailResponse => {
              const detailedVm = detailResponse.listvirtualmachinesresponse.virtualmachine[0]
              return {
                ...detailedVm,
                instanceId: vm.instanceId
              }
            })
          }))
      }).then(detailedVms => {
        this.virtualmachines = detailedVms
      }).catch(error => {
        this.$notifyError(error.message || 'Failed to fetch VMs')
      }).finally(() => {
        this.loading = false
      })
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
        const xmlConfig = await this.generateXmlConfig()

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

    // SCSI 주소 추출 메서드
    async extractScsiAddress (devicePath) {
      // SCSI 주소 정보가 resource에 포함되어 있다면 사용
      if (this.resource.scsiAddress) {
        console.log('Using resource.scsiAddress:', this.resource.scsiAddress)
        return this.parseScsiAddress(this.resource.scsiAddress)
      }

      // 디바이스 텍스트에서 SCSI 주소 추출
      if (this.resource.hostDevicesText) {
        console.log('Searching in hostDevicesText:', this.resource.hostDevicesText)
        const scsiMatch = this.resource.hostDevicesText.match(/SCSI_ADDRESS:\s*([0-9]+:[0-9]+:[0-9]+:[0-9]+)/i)
        if (scsiMatch) {
          console.log('Found SCSI address in hostDevicesText:', scsiMatch[1])
          return this.parseScsiAddress(scsiMatch[1])
        }
      }

      // 백엔드에서 LUN 디바이스 정보를 다시 조회하여 SCSI 주소 가져오기
      try {
        const response = await api('listHostLunDevices', { id: this.resource.id })
        const lunDevices = response.listhostlundevicesresponse?.listhostlundevices?.[0]

        if (lunDevices && lunDevices.hostdevicesname && lunDevices.hostdevicestext) {
          const deviceIndex = lunDevices.hostdevicesname.indexOf(devicePath)
          if (deviceIndex !== -1 && lunDevices.hostdevicestext[deviceIndex]) {
            const deviceText = lunDevices.hostdevicestext[deviceIndex]

            const scsiMatch = deviceText.match(/SCSI_ADDRESS:\s*([0-9]+:[0-9]+:[0-9]+:[0-9]+)/i)
            if (scsiMatch) {
              return this.parseScsiAddress(scsiMatch[1])
            }
          }
        }
      } catch (error) {
        console.error('Error fetching LUN devices:', error)
      }

      // 기본값 반환
      return { bus: '0', target: '0', unit: '0' }
    },

    // SCSI 주소 파싱
    parseScsiAddress (scsiAddress) {
      const parts = scsiAddress.split(':')

      if (parts.length >= 4) {
        const result = {
          host: parts[0],
          bus: parts[1] || '0',
          target: parts[2] || '0',
          unit: parts[3] || '0'
        }
        return result
      }

      return { bus: '0', target: '0', unit: '0' }
    },

    async generateXmlConfig () {
      let targetDev = 'sdc'

      // multipath 장치인지 확인
      const isMultipath = this.resource.hostDevicesName.startsWith('/dev/mapper/')

      if (isMultipath) {
        // multipath 장치의 경우 dm-* 이름을 사용
        const match = this.resource.hostDevicesName.match(/\/dev\/mapper\/(dm-\d+)$/)
        if (match) {
          targetDev = match[1] // dm-10, dm-11 등
        } else {
          // fallback: mpatha, mpathb 등
          const mpathMatch = this.resource.hostDevicesName.match(/\/dev\/mapper\/(mpath[a-z]+)$/)
          if (mpathMatch) {
            targetDev = mpathMatch[1]
          }
        }
      } else {
        // 일반 블록 디바이스의 경우
        const match = this.resource.hostDevicesName.match(/\/dev\/([a-z]+[a-z0-9]*)$/)
        if (match) {
          targetDev = match[1]
        }
      }

      // SCSI 주소 추출
      const scsiAddress = await this.extractScsiAddress(this.resource.hostDevicesName)

      // multipath 장치인 경우와 일반 디바이스인 경우를 구분하여 XML 생성
      if (isMultipath) {
        // multipath 장치용 XML - SCSI address 없이
        return `
          <disk type='block' device='lun'>
            <driver name='qemu' type='raw'/>
            <source dev='${this.resource.hostDevicesName}'/>
            <target dev='${targetDev}' bus='scsi'/>
            <serial>multipath-${targetDev}</serial>
          </disk>
        `.trim()
      } else {
        // 일반 블록 디바이스용 XML - SCSI address 포함
        return `
          <disk type='block' device='lun'>
            <driver name='qemu' type='raw'/>
            <source dev='${this.resource.hostDevicesName}'/>
            <target dev='${targetDev}' bus='scsi'/>
            <address type='drive' controller='0' bus='${scsiAddress.bus}' target='${scsiAddress.target}' unit='${scsiAddress.unit}'/>
          </disk>
        `.trim()
      }
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
