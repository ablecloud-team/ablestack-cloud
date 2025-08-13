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
            console.log(`Fetched ${vms.length} Running VMs`)

            // 인스턴스 이름이 있는 VM만 필터링
            const validVms = vms.filter(vm => vm.instancename && vm.instancename.trim() !== '')
            console.log(`Valid VMs with instance name: ${validVms.length}`)

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
      ]).then(([vms, hbaResponse]) => {
        console.log('Total valid VMs fetched:', vms.length)

        // HBA 할당 상태 확인
        const hbaData = hbaResponse?.listhosthbadevicesresponse?.listhosthbadevices?.[0]
        const vmAllocations = hbaData?.vmallocations || {}

        // 현재 선택한 HBA 디바이스가 할당된 VM ID 확인
        const currentDeviceVmId = vmAllocations[this.resource.hostDevicesName]
        console.log('Current device VM ID:', currentDeviceVmId)

        // 모든 HBA 디바이스가 할당된 VM들을 필터링
        const allocatedVmIds = new Set()
        Object.values(vmAllocations).forEach(vmId => {
          if (vmId) {
            allocatedVmIds.add(vmId.toString())
          }
        })

        console.log('All allocated VM IDs:', Array.from(allocatedVmIds))

        // HBA 디바이스가 할당된 VM들을 제외 (인스턴스 번호로 비교)
        this.virtualmachines = vms.filter(vm => {
          // VM의 인스턴스 번호 추출 (i-2-163-VM -> 163)
          const instanceNumber = vm.instancename ? vm.instancename.split('-')[2] : null

          // HBA 디바이스가 할당된 VM은 제외 (인스턴스 번호로 비교)
          if (instanceNumber && allocatedVmIds.has(instanceNumber)) {
            return false
          }
          return true
        })

        if (this.virtualmachines.length === 0) {
          this.$notification.warning({
            message: this.$t('message.warning'),
            description: allocatedVmIds.size > 0
              ? 'All VMs already have HBA devices allocated. Please deallocate an HBA device first to assign to another VM.'
              : 'No VMs with valid instance names found. Please ensure VMs are properly running.'
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
          console.log('Const  ructed SCSI address from host number:', `${hostNum}:${bus}:${target}:${unit}`)
        }
      }

      // 물리 HBA와 가상 HBA의 XML 구조를 다르게 처리
      if (this.resource && this.resource.deviceType === 'physical') {
        // 물리 HBA의 경우 더 간단한 XML 구조 사용
        return `<hostdev mode='subsystem' type='scsi' rawio='yes'>
  <source>
    <adapter name='${adapterName}'/>
    <address bus='${bus}' target='${target}' unit='${unit}'/>
  </source>
</hostdev>`
      } else {
        // 가상 HBA의 경우 기존 XML 구조 사용
        return `<hostdev mode='subsystem' type='scsi' rawio='yes'>
  <source>
    <adapter name='${adapterName}'/>
    <address bus='${bus}' target='${target}' unit='${unit}'/>
  </source>
</hostdev>`
      }
    },

    // HBA 디바이스 텍스트에서 SCSI 주소 정보 추출
    getHbaInfoFromDeviceText () {
      // 여러 가능한 속성에서 SCSI 주소 찾기
      let deviceText = ''

      if (this.resource.hostDevicesText) {
        deviceText = this.resource.hostDevicesText
      } else if (this.resource.hostdevicestext) {
        deviceText = this.resource.hostdevicestext
      } else if (this.resource.text) {
        deviceText = this.resource.text
      }

      // SCSI 주소 패턴 매칭 (다양한 형식 지원)
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

    // 다중 HBA 할당 처리
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

        // HBA 디바이스 상세 정보 가져오기
        const hbaResponse = await api('listHostHbaDevices', { id: this.resource.id })
        const hbaDevices = hbaResponse.listhosthbadevicesresponse?.listhosthbadevices?.[0]

        console.log('HBA Devices response:', hbaResponse)
        console.log('HBA Devices data:', hbaDevices)

        // HBA 디바이스 데이터에서 SCSI 주소 정보 추출
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
              console.log('Found device text for', this.resource.hostDevicesName, ':', deviceText)

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

        // 현재 HBA 디바이스를 선택된 VM에 할당
        console.log('Allocating current HBA device to VM...')

        try {
          // SCSI 주소 추출 - resource에서 직접 가져오기
          let scsiAddress = null
          let deviceText = ''

          // 여러 가능한 속성에서 디바이스 텍스트 찾기
          if (this.resource.hostDevicesText) {
            deviceText = this.resource.hostDevicesText
          } else if (this.resource.hostdevicestext) {
            deviceText = this.resource.hostdevicestext
          } else if (this.resource.text) {
            deviceText = this.resource.text
          }

          scsiAddress = this.extractScsiAddress(deviceText)
          console.log('Device text:', deviceText)
          console.log('Extracted SCSI address:', scsiAddress)

          // XML 설정 생성
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

          console.log('Allocating with params:', allocationParams)

          const allocationResponse = await api('updateHostHbaDevices', allocationParams)

          if (allocationResponse.error) {
            throw new Error(allocationResponse.error.errortext || 'Failed to allocate HBA device')
          }

          console.log('Successfully allocated HBA device to VM')
          this.$message.success('HBA 디바이스가 성공적으로 할당되었습니다.')
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
