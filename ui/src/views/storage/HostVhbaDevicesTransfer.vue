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
      form: reactive({ virtualmachineid: null }),
      // 로컬 할당 상태 관리
      isAssigned: false,
      virtualmachineid: null,
      vmName: null
    }
  },
  computed: {
    // 할당 상태를 부모 컴포넌트에서 접근할 수 있도록 computed 속성 제공
    allocationStatus () {
      return {
        isAssigned: this.isAssigned,
        virtualmachineid: this.virtualmachineid,
        vmName: this.vmName
      }
    }
  },
  created () {
    this.fetchVMs()
    this.checkCurrentAllocation()
  },
  methods: {
    // 현재 할당 상태 확인
    async checkCurrentAllocation () {
      if (!this.resource || !this.resource.id || !this.resource.hostDevicesName) {
        return
      }

      try {
        const response = await api('listVhbaDevices', {
          hostid: this.resource.id
        })

        if (response.listvhbadevicesresponse?.listvhbadevices?.[0]) {
          const vhbaData = response.listvhbadevicesresponse.listvhbadevices[0]
          const vmAllocations = vhbaData.vmallocations || {}
          const vmId = vmAllocations[this.resource.hostDevicesName]

          if (vmId) {
            // 할당된 상태로 설정
            this.isAssigned = true
            this.virtualmachineid = vmId

            // VM 이름 찾기
            const vm = this.virtualmachines.find(vm => vm.id === vmId)
            if (vm) {
              this.vmName = vm.name || vm.displayname
            }
          } else {
            // 할당되지 않은 상태로 설정
            this.isAssigned = false
            this.virtualmachineid = null
            this.vmName = null
          }
        }
      } catch (error) {
        console.error('Failed to check current allocation:', error)
      }
    },

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
        // VM 목록 로드 완료 후 할당 상태 다시 확인
        this.checkCurrentAllocation()
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

        // UUID/숫자(long) 호환: 먼저 UUID로 시도, 실패 시 numeric으로 재시도
        const vmUuid = selectedVM.id
        // VM ID가 숫자인지 확인
        let vmNumericId = null
        if (typeof vmUuid === 'number') {
          vmNumericId = vmUuid
          console.log('VM ID is already numeric:', vmNumericId)
        } else if (typeof vmUuid === 'string') {
          // UUID인 경우 인스턴스 이름에서 숫자 ID 추출
          let instanceNumber = null
          if (selectedVM.instancename) {
            const parts = selectedVM.instancename.split('-')
            if (parts.length >= 3) instanceNumber = parts[2]
            console.log('Extracted instance number from instancename:', instanceNumber, 'from:', selectedVM.instancename)
          }
          if (!instanceNumber) {
            const numberFromName = (selectedVM.name || selectedVM.displayname || '').match(/(\d+)/)
            if (numberFromName) instanceNumber = numberFromName[1]
            console.log('Extracted instance number from name:', instanceNumber, 'from:', selectedVM.name || selectedVM.displayname)
          }
          vmNumericId = instanceNumber && !Number.isNaN(parseInt(instanceNumber, 10))
            ? parseInt(instanceNumber, 10)
            : null
          console.log('Final numeric ID:', vmNumericId, 'from UUID:', vmUuid)
        }

        const xmlConfig = this.generateVhbaAllocationXmlConfig()

        // 숫자 ID가 있으면 바로 사용, 없으면 UUID로 시도 후 재시도
        let response
        if (vmNumericId != null) {
          // 숫자 ID가 있으면 바로 사용
          response = await api('updateHostVhbaDevices', {
            hostid: this.resource.id,
            hostdevicesname: this.resource.hostDevicesName,
            virtualmachineid: vmNumericId,
            xmlconfig: xmlConfig
          })
        } else {
          // 숫자 ID가 없으면 UUID로 시도 후 재시도
          try {
            response = await api('updateHostVhbaDevices', {
              hostid: this.resource.id,
              hostdevicesname: this.resource.hostDevicesName,
              virtualmachineid: vmUuid,
              xmlconfig: xmlConfig
            })
            if (response?.error) throw response.error
          } catch (e1) {
            const errText = e1?.errortext || e1?.message || ''
            const errCode = e1?.errorcode || response?.updatehostvhbadevicesresponse?.errorcode
            const csErr = e1?.cserrorcode || response?.updatehostvhbadevicesresponse?.cserrorcode
            const uuidFailed = (errCode === 431) || (csErr === 9999) || /incorrect long value format/i.test(errText)

            if (uuidFailed) {
              throw new Error('VM ID format not supported. Please check VM instance name format.')
            } else {
              throw new Error(errText || 'Failed to allocate vHBA device')
            }
          }
        }

        if (response.error) {
          throw new Error(response.error.errortext || 'Failed to allocate vHBA device')
        }

        // 할당 성공 후 UI 상태 업데이트
        if (response.updatehostvhbadevicesresponse?.updatehostvhbadevices?.[0]) {
          const allocationResult = response.updatehostvhbadevicesresponse.updatehostvhbadevices[0]
          if (allocationResult.isattached) {
            // 할당된 상태로 UI 업데이트
            this.isAssigned = true
            this.virtualmachineid = allocationResult.virtualmachineid
            this.vmName = this.virtualmachines.find(vm => vm.id === this.form.virtualmachineid)?.name ||
                         this.virtualmachines.find(vm => vm.id === this.form.virtualmachineid)?.displayname ||
                         'Unknown VM'
          }
        }

        this.$message.success(this.$t('message.success.allocate.device'))
        this.$emit('allocation-completed')
        this.$emit('allocation-status-changed', this.allocationStatus)
        this.$emit('refresh-device-list') // 디바이스 리스트 새로고침 이벤트 추가
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
          xmlconfig: xmlConfig
        })

        if (!detachResponse || detachResponse.error) {
          throw new Error(detachResponse?.error?.errortext || 'Failed to detach vHBA device')
        }

        // 해제 성공 후 UI 상태 업데이트
        this.isAssigned = false
        this.virtualmachineid = null
        this.vmName = null

        this.$message.success(this.$t('message.success.remove.allocation'))
        this.$emit('device-allocated')
        this.$emit('allocation-completed')
        this.$emit('allocation-status-changed', this.allocationStatus)
        this.$emit('refresh-device-list') // 디바이스 리스트 새로고침 이벤트 추가
        this.$emit('close-action')
      } catch (error) {
        this.$notifyError(error.message || 'Failed to deallocate vHBA device')
      } finally {
        this.loading = false
      }
    },

    // SCSI 주소 추출 함수
    extractScsiAddress (deviceText) {
      if (!deviceText) return null

      const scsiMatch = deviceText.match(/SCSI Address:\s*([0-9]+:[0-9]+:[0-9]+:[0-9]+)/i) ||
                        deviceText.match(/scsi[:\s]*([0-9]+:[0-9]+:[0-9]+:[0-9]+)/i) ||
                        deviceText.match(/([0-9]+:[0-9]+:[0-9]+:[0-9]+)/)

      const result = scsiMatch ? scsiMatch[1] : null
      return result
    },

    // vHBA 할당용 XML 설정 생성 (libvirt SCSI 패스스루 표준)
    generateVhbaAllocationXmlConfig () {
      const hostDeviceName = this.resource.hostDevicesName || ''

      // 디바이스 텍스트에서 SCSI 주소 추출 시도
      let scsiAddress = null
      if (this.resource.hostDevicesText) {
        scsiAddress = this.extractScsiAddress(this.resource.hostDevicesText)
      }

      let bus = '0'
      let target = '0'
      let unit = '0'
      let adapterName = hostDeviceName

      // 전달받은 SCSI 주소 사용
      if (scsiAddress) {
        const scsiParts = scsiAddress.split(':')
        if (scsiParts.length >= 4) {
          const hostNum = scsiParts[0]
          bus = scsiParts[1] || '0'
          target = scsiParts[2] || '0'
          unit = scsiParts[3] || '0'
          adapterName = `scsi_host${hostNum}`
        }
      } else {
        // 디바이스 이름에서 고유한 SCSI 주소 생성
        const hostMatch = hostDeviceName.match(/scsi_host(\d+)/i)
        if (hostMatch) {
          const hostNum = parseInt(hostMatch[1])
          bus = '0'
          target = '0'
          unit = '0'
          adapterName = `scsi_host${hostNum}`
        }
      }

      return `
        <hostdev mode='subsystem' type='scsi' rawio='yes'>
          <source>
            <adapter name='${adapterName}'/>
            <address bus='${bus}' target='${target}' unit='${unit}'/>
          </source>
        </hostdev>
      `.trim()
    },

    // vHBA 해제용 XML 설정 생성
    generateVhbaDeallocationXmlConfig () {
      const hostDeviceName = this.resource.hostDevicesName || ''

      // 할당 시와 동일한 SCSI 주소 생성 로직 사용
      let bus = '0'
      let target = '0'
      let unit = '0'
      let adapterName = hostDeviceName

      // 디바이스 텍스트에서 SCSI 주소 추출 시도
      let scsiAddress = null
      if (this.resource.hostDevicesText) {
        scsiAddress = this.extractScsiAddress(this.resource.hostDevicesText)
      }

      // 전달받은 SCSI 주소 사용
      if (scsiAddress) {
        const scsiParts = scsiAddress.split(':')
        if (scsiParts.length >= 4) {
          const hostNum = scsiParts[0]
          bus = scsiParts[1] || '0'
          target = scsiParts[2] || '0'
          unit = scsiParts[3] || '0'
          adapterName = `scsi_host${hostNum}`
        }
      } else {
        // 디바이스 이름에서 고유한 SCSI 주소 생성
        const hostMatch = hostDeviceName.match(/scsi_host(\d+)/i)
        if (hostMatch) {
          const hostNum = parseInt(hostMatch[1])
          bus = '0'
          target = '0'
          unit = '0'
          adapterName = `scsi_host${hostNum}`
        }
      }

      return `
        <hostdev mode='subsystem' type='scsi' rawio='yes'>
          <source>
            <adapter name='${adapterName}'/>
            <address bus='${bus}' target='${target}' unit='${unit}'/>
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
