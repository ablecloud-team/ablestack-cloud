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
          <a-select-option
            v-for="vm in virtualmachines"
            :key="vm.id"
            :label="vm.name || vm.displayname"
          >
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
  name: 'HostHbaDevicesTransfer',
  i18n: {
    messages: {
      ko: {
        'message.success.hba.device.allocated': 'HBA 디바이스가 성공적으로 할당되었습니다.'
      }
    }
  },
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
      availableHbaDevices: [] // 사용 가능한 HBA 디바이스 목록
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
      const params = {
        hostid: this.resource.id,
        details: 'all',
        listall: true,
        state: 'Running' // Running 상태의 VM만 가져오기
      }

      return Promise.all([
        // Running 상태의 VM 목록만 가져오기
        api('listVirtualMachines', params)
          .then(vmResponse => {
            const vms = vmResponse.listvirtualmachinesresponse?.virtualmachine || []
            // console.log(`Fetched ${vms.length} Running VMs`)

            // 인스턴스 이름이 있는 VM만 필터링
            const validVms = vms.filter(vm => vm.instancename && vm.instancename.trim() !== '')
            // console.log(`Valid VMs with instance name: ${validVms.length}`)

            return validVms.map(vm => ({
              ...vm,
              instanceId: vm.instancename ? vm.instancename.split('-')[2] : null
            }))
          })
          .catch(error => {
            console.error('Error fetching VMs:', error)
            return []
          }),
        // 현재 HBA 디바이스 할당 상태 가져오기
        api('listHostHbaDevices', { id: this.resource.id })
      ]).then(async ([vms, hbaResponse]) => {
        // console.log('Total valid VMs fetched:', vms.length)

        // HBA 할당 상태 확인
        const hbaData = hbaResponse?.listhosthbadevicesresponse?.listhosthbadevices?.[0]
        const vmAllocations = hbaData?.vmallocations || {}

        // 현재 선택한 HBA 디바이스가 할당된 VM ID 확인
        const currentDeviceVmId = vmAllocations[this.resource.hostDevicesName]
        console.log('Current device VM ID:', currentDeviceVmId)

        // 모든 HBA 디바이스가 할당된 VM들을 필터링하고, 존재하지 않는 VM은 자동으로 할당 해제
        const allocatedVmIds = new Set()
        const processedDevices = new Set()

        for (const [deviceName, vmId] of Object.entries(vmAllocations)) {
          if (vmId && !processedDevices.has(deviceName)) {
            try {
              // VM이 실제로 존재하는지 확인
              const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
              const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

              if (vm && vm.state !== 'Expunging') {
                allocatedVmIds.add(vmId.toString())
              } else {
                // VM이 존재하지 않거나 Expunging 상태면 자동으로 할당 해제
                try {
                  const xmlConfig = this.generateHbaDeallocationXmlConfig(deviceName)
                  await api('updateHostHbaDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: deviceName,
                    virtualmachineid: null,
                    xmlconfig: xmlConfig,
                    isattach: false
                  })
                  // console.log(`Automatically deallocated HBA device ${deviceName} from deleted/expunging VM ${vmId}`)
                } catch (error) {
                  console.error(`Failed to automatically deallocate HBA device ${deviceName}:`, error)
                }
              }
            } catch (error) {
              console.error(`Error checking VM ${vmId} for device ${deviceName}:`, error)
              // VM 조회 실패 시에도 할당 해제 시도
              try {
                const xmlConfig = this.generateHbaDeallocationXmlConfig(deviceName)
                await api('updateHostHbaDevices', {
                  hostid: this.resource.id,
                  hostdevicesname: deviceName,
                  virtualmachineid: null,
                  xmlconfig: xmlConfig,
                  isattach: false
                })
                // console.log(`Automatically deallocated HBA device ${deviceName} after VM check error`)
              } catch (detachError) {
                console.error(`Failed to automatically deallocate HBA device ${deviceName} after error:`, detachError)
              }
            }
            processedDevices.add(deviceName)
          }
        }

        // console.log('All allocated VM IDs:', Array.from(allocatedVmIds))

        // HBA 디바이스는 같은 이름으로도 여러 할당이 가능하므로 모든 VM을 표시
        // 백엔드에서 같은 HBA 디바이스 이름으로도 여러 할당을 허용하므로 필터링하지 않음
        this.virtualmachines = vms

        if (this.virtualmachines.length === 0) {
          this.$notification.warning({
            message: this.$t('message.warning'),
            description: 'No VMs with valid instance names found. Please ensure VMs are properly running.'
          })
        }
      }).catch(error => {
        console.error('Error in refreshVMList:', error)
        this.$notifyError(error.message || 'Failed to fetch VMs')
      }).finally(() => {
        this.loading = false
      })
    },

    fetchVMs () {
      this.form.virtualmachineid = null
      this.availableHbaDevices = []
      return this.refreshVMList()
    },

    // SCSI 주소 추출
    extractScsiAddress (deviceText) {
      if (!deviceText) return null

      const scsiMatch = deviceText.match(/SCSI Address:\s*([0-9]+:[0-9]+:[0-9]+:[0-9]+)/i) ||
                        deviceText.match(/scsi[:\s]*([0-9]+:[0-9]+:[0-9]+:[0-9]+)/i) ||
                        deviceText.match(/([0-9]+:[0-9]+:[0-9]+:[0-9]+)/)

      const result = scsiMatch ? scsiMatch[1] : null
      return result
    },

    // HBA 정보 조회 함수 추가
    async getHbaInfo (hostDevicesName) {
      const response = await api('listHostHbaDevices', { id: this.resource.id })
      const hbaData = response.listhosthbadevicesresponse?.listhosthbadevices?.[0]
      if (!hbaData) return {}
      const idx = hbaData.hostdevicesname.indexOf(hostDevicesName)
      if (idx === -1) return {}
      // WWNN/WWPN 파싱
      const text = hbaData.hostdevicestext[idx] || ''
      const wwpn = (text.match(/wwpn[:\s]*([0-9A-Fa-f]{16})/i) || [])[1] || ''
      const wwnn = (text.match(/wwnn[:\s]*([0-9A-Fa-f]{16})/i) || [])[1] || ''
      return { wwpn, wwnn }
    },

    // XML 생성 함수에서 동적 SCSI 주소 사용
    generateXmlConfig (hostDeviceName, scsiAddress = null) {
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
        const scsiMatch = hostDeviceName.match(/scsi_host(\d+)/i)
        if (scsiMatch) {
          const hostNum = scsiMatch[1]
          bus = '0'
          target = '0'
          unit = '0'
          console.log('Constructed SCSI address from host number:', `${hostNum}:${bus}:${target}:${unit}`)
        }
      }

      if (this.resource && this.resource.deviceType === 'physical') {
        return `<hostdev mode='subsystem' type='scsi'>
  <source>
    <adapter name='${adapterName}'/>
    <address bus='${bus}' target='${target}' unit='${unit}'/>
  </source>
</hostdev>`
      } else {
        return `<hostdev mode='subsystem' type='scsi'>
  <source>
    <adapter name='${adapterName}'/>
    <address bus='${bus}' target='${target}' unit='${unit}'/>
  </source>
</hostdev>`
      }
    },

    // HBA 할당 해제용 XML 설정 생성
    generateHbaDeallocationXmlConfig (hostDeviceName) {
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
        const scsiMatch = hostDeviceName.match(/scsi_host(\d+)/i)
        if (scsiMatch) {
          bus = '0'
          target = '0'
          unit = '0'
        }
      }

      return `
        <hostdev mode='subsystem' type='scsi'>
          <source>
            <adapter name='${adapterName}'/>
            <address bus='${bus}' target='${target}' unit='${unit}'/>
          </source>
        </hostdev>
      `.trim()
    },

    getHbaInfoFromDeviceText () {
      let deviceText = ''

      if (this.resource.hostDevicesText) {
        deviceText = this.resource.hostDevicesText
      } else if (this.resource.hostdevicestext) {
        deviceText = this.resource.hostdevicestext
      } else if (this.resource.text) {
        deviceText = this.resource.text
      }

      const scsiAddressMatch = deviceText.match(/SCSI Address:\s*([0-9:]+)/i) ||
                              deviceText.match(/scsi[:\s]*([0-9:]+)/i) ||
                              deviceText.match(/([0-9]+:[0-9]+:[0-9]+:[0-9]+)/)

      if (scsiAddressMatch) {
        const scsiAddress = scsiAddressMatch[1]
        return {
          scsiAddress: scsiAddress
        }
      }

      return null
    },

    async handleSubmit () {
      if (!this.form.virtualmachineid) {
        this.$notification.error({
          message: this.$t('message.error'),
          description: this.$t('message.please.select.vm')
        })
        return
      }

      // VM 상태 확인
      try {
        const vmResponse = await api('listVirtualMachines', {
          id: this.form.virtualmachineid,
          listall: true
        })
        const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

        if (!vm) {
          this.$notification.error({
            message: this.$t('label.error'),
            description: 'Selected VM not found.'
          })
          return
        }

        if (vm.state !== 'Running') {
          this.$notification.warning({
            message: this.$t('message.warning'),
            description: `VM must be in Running state for HBA device allocation. Current state: ${vm.state}`
          })
          return
        }
      } catch (error) {
        console.error('Error checking VM state:', error)
        this.$notification.error({
          message: this.$t('label.error'),
          description: 'Failed to check VM state.'
        })
        return
      }

      this.loading = true
      try {
        // 선택된 VM의 상태 확인
        const selectedVM = this.virtualmachines.find(vm => vm.id === this.form.virtualmachineid)
        if (!selectedVM) {
          throw new Error('선택된 VM을 찾을 수 없습니다.')
        }

        // VM 상태 검증
        if (!selectedVM.instancename) {
          throw new Error(`VM ${selectedVM.name || selectedVM.displayname}의 인스턴스 이름이 없습니다. VM을 재시작해주세요.`)
        }

        if (selectedVM.state !== 'Running') {
          this.$notification.warning({
            message: this.$t('message.warning'),
            description: `VM ${selectedVM.name || selectedVM.displayname}이 Running 상태가 아닙니다.`
          })
          return
        }

        const hbaResponse = await api('listHostHbaDevices', { id: this.resource.id })
        const hbaDevices = hbaResponse.listhosthbadevicesresponse?.listhosthbadevices?.[0]

        // console.log('HBA Devices response:', hbaResponse)
        // console.log('HBA Devices data:', hbaDevices)

        let scsiAddress = null
        let deviceType = null
        let isVhba = false
        let wwnn = null
        let wwpn = null

        if (hbaDevices && hbaDevices.hostdevicesname && hbaDevices.hostdevicestext) {
          const deviceIndex = hbaDevices.hostdevicesname.indexOf(this.resource.hostDevicesName)
          if (deviceIndex !== -1) {
            // 디바이스 타입 확인
            if (hbaDevices.devicetypes && hbaDevices.devicetypes[deviceIndex]) {
              deviceType = hbaDevices.devicetypes[deviceIndex]
              isVhba = deviceType === 'virtual'
              console.log('Device type:', deviceType, 'isVhba:', isVhba)
            }

            // 디바이스 텍스트에서 SCSI 주소 및 WWN 정보 추출
            if (hbaDevices.hostdevicestext[deviceIndex]) {
              const deviceText = hbaDevices.hostdevicestext[deviceIndex]
              // console.log('Found device text for', this.resource.hostDevicesName, ':', deviceText)

              // SCSI 주소 패턴 매칭
              const scsiMatch = deviceText.match(/SCSI Address:\s*([0-9:]+)/i) ||
                               deviceText.match(/scsi[:\s]*([0-9:]+)/i) ||
                               deviceText.match(/([0-9]+:[0-9]+:[0-9]+:[0-9]+)/)

              if (scsiMatch) {
                scsiAddress = scsiMatch[1]
                console.log('Extracted SCSI address from device text:', scsiAddress)
              }

              // WWNN/WWPN 추출
              const wwnnMatch = deviceText.match(/WWNN:\s*([a-fA-F0-9]+)/i)
              const wwpnMatch = deviceText.match(/WWPN:\s*([a-fA-F0-9]+)/i)

              if (wwnnMatch) {
                wwnn = wwnnMatch[1]
                console.log('Extracted WWNN:', wwnn)
              }
              if (wwpnMatch) {
                wwpn = wwpnMatch[1]
                console.log('Extracted WWPN:', wwpn)
              }
            }
          }
        }

        try {
          let scsiAddress = null
          let deviceText = ''

          if (this.resource.hostDevicesText) {
            deviceText = this.resource.hostDevicesText
          } else if (this.resource.hostdevicestext) {
            deviceText = this.resource.hostdevicestext
          } else if (this.resource.text) {
            deviceText = this.resource.text
          }

          scsiAddress = this.extractScsiAddress(deviceText)
          // console.log('Device text:', deviceText)
          // console.log('Extracted SCSI address:', scsiAddress)

          const xmlConfig = this.generateXmlConfig(this.resource.hostDevicesName, scsiAddress)
          console.log('Generated XML config:', xmlConfig)

          // VM 상태 재확인
          const vmStatusResponse = await api('listVirtualMachines', {
            id: selectedVM.id,
            details: 'all'
          })

          const currentVM = vmStatusResponse.listvirtualmachinesresponse?.virtualmachine?.[0]
          if (!currentVM || currentVM.state !== 'Running') {
            throw new Error(`VM ${selectedVM.name || selectedVM.displayname}이 Running 상태가 아닙니다.`)
          }

          if (!currentVM.instancename) {
            throw new Error(`VM ${selectedVM.name || selectedVM.displayname}의 인스턴스 이름이 없습니다.`)
          }

          // HBA 디바이스 할당 시도
          const allocationParams = {
            hostid: this.resource.id,
            hostdevicesname: this.resource.hostDevicesName,
            virtualmachineid: selectedVM.id,
            xmlconfig: xmlConfig
          }

          // 디바이스 타입 확인
          if (this.resource.deviceType === 'virtual') {
            allocationParams.devicetype = 'virtual'
          }

          // console.log('Allocating with params:', allocationParams)

          const allocationResponse = await api('updateHostHbaDevices', allocationParams)

          if (allocationResponse.error) {
            throw new Error(allocationResponse.error.errortext || 'Failed to allocate HBA device')
          }

          this.$message.success(this.$t('message.success.hba.device.allocated'))
        } catch (error) {
          console.error('Failed to allocate HBA device:', error)
          throw error
        }

        // 할당 완료 후 이벤트 발생
        this.$emit('allocation-completed')
        this.$emit('close-action')
      } catch (error) {
        console.error('Error allocating HBA device:', error)

        // 오류 응답에서 더 자세한 정보 추출
        let errorMessage = error.message || this.$t('message.failed.to.allocate.hba.device')

        if (error.response && error.response.data) {
          const responseData = error.response.data
          if (responseData.updatehosthbadevicesresponse && responseData.updatehosthbadevicesresponse.errortext) {
            errorMessage = responseData.updatehosthbadevicesresponse.errortext
          } else if (responseData.errorresponse && responseData.errorresponse.errortext) {
            errorMessage = responseData.errorresponse.errortext
          }
        }

        this.$notification.error({
          message: this.$t('message.error'),
          description: errorMessage
        })
      } finally {
        this.loading = false
      }
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
