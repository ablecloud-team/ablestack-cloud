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
          <span v-html="$t('message.warning.host.device')" />
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
          <a-button
            type="primary"
            ref="submit"
            @click="isDeleteMode ? handleDelete() : handleSubmit()"
          >{{ isDeleteMode ? $t('label.delete') : $t('label.ok') }}</a-button>
        </div>
      </a-form-item>
    </a-form>
  </a-spin>
</template>

<script>
import { reactive } from 'vue'
import { api } from '@/api'

export default {
  name: 'HostScsiDevicesTransfer',
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
      isDeleteMode: false,
      deleteTargetType: null, // 'scsi' | 'lun'
      deleteTargetName: null,
      deleteTargetVmId: null,
      // 교차 확인으로 LUN API 호출 시 미지원 호스트에서 오류가 발생하므로 SCSI 플로우에서는 비활성화
      skipLunCrossCheck: true
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
      const params = { hostid: this.resource.id, details: 'all', listall: true }
      const vmStates = ['Running']

      try {
        const [vmArrays, scsiResponse] = await Promise.all([
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
          // 현재 SCSI 디바이스 할당 상태 가져오기
          api('listHostScsiDevices', { id: this.resource.id })
        ])

        const vms = vmArrays.flat()
        const scsiDevices = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]
        const allocatedVmIds = new Set()

        // 모든 SCSI 디바이스에 할당된 VM ID 수집 (다중 할당 허용하므로 모든 할당 유지)
        if (scsiDevices?.vmallocations) {
          for (const [deviceName, vmId] of Object.entries(scsiDevices.vmallocations)) {
            if (vmId) {
              try {
                // VM이 실제로 존재하는지 확인
                const vmResponse = await api('listVirtualMachines', { id: vmId, listall: true })
                const vm = vmResponse.listvirtualmachinesresponse?.virtualmachine?.[0]

                if (vm && vm.state !== 'Expunging') {
                  allocatedVmIds.add(vmId.toString())
                } else {
                  // VM이 존재하지 않거나 Expunging 상태면 자동으로 할당 해제
                  try {
                    // 실제 디바이스 정보 조회
                    const deviceIndex = scsiDevices.hostdevicesname?.indexOf(deviceName)
                    const deviceText = deviceIndex !== -1 ? scsiDevices.hostdevicestext?.[deviceIndex] : ''
                    const xmlConfig = await this.generateXmlConfig(deviceName, deviceText)
                    await api('updateHostScsiDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null,
                      currentvmid: vmId,
                      xmlconfig: xmlConfig,
                      isattach: false
                    })
                  } catch (error) {
                    // Failed to automatically deallocate SCSI device
                  }
                }
              } catch (error) {
                // VM 조회 실패 시에도 할당 해제 시도
                try {
                  // 실제 디바이스 정보 조회
                  const deviceIndex = scsiDevices.hostdevicesname?.indexOf(deviceName)
                  const deviceText = deviceIndex !== -1 ? scsiDevices.hostdevicestext?.[deviceIndex] : ''
                  const xmlConfig = await this.generateXmlConfig(deviceName, deviceText)
                  await api('updateHostScsiDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: deviceName,
                    virtualmachineid: null,
                    currentvmid: vmId,
                    xmlconfig: xmlConfig,
                    isattach: false
                  })
                } catch (detachError) {
                  // Failed to automatically deallocate SCSI device after error
                }
              }
            }
          }
        }

        // 현재 디바이스에 할당된 VM ID는 제외하지 않음 (다중 할당 허용)
        // 모든 VM을 표시하되, 할당된 VM은 별도 표시

        // 모든 VM을 표시 (할당된 VM도 포함)
        this.virtualmachines = vms.map(vm => ({
          ...vm,
          isAllocated: allocatedVmIds.has(vm.instanceId?.toString())
        }))
        await this.detectAllocationState()
      } catch (error) {
        this.$notifyError(error.message || 'Failed to fetch VMs')
      } finally {
        this.loading = false
      }
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
        // LUN 교차 확인은 스킵하여 미지원 호스트에서의 오류를 차단
        if (!this.skipLunCrossCheck) {
          const isAllocatedInLun = await this.checkDeviceAllocationInLun(this.resource.hostDevicesName)
          if (isAllocatedInLun) {
            this.isDeleteMode = true
            this.deleteTargetType = 'lun'
            this.deleteTargetName = this.resource.hostDevicesName
            try {
              const lunResp = await api('listHostLunDevices', { id: this.resource.id })
              const lun = lunResp?.listhostlundevicesresponse?.listhostlundevices?.[0]
              this.deleteTargetVmId = lun?.vmallocations?.[this.resource.hostDevicesName] || null
            } catch (e) {}
            return
          }
        }

        // SCSI 디바이스의 상세 정보를 다시 조회
        let hostDevicesText = this.resource.hostDevicesText

        if (!hostDevicesText) {
          // hostDevicesText가 없으면 API를 통해 다시 조회
          const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
          const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

          if (scsiData) {
            const deviceIndex = scsiData.hostdevicesname.indexOf(this.resource.hostDevicesName)
            if (deviceIndex !== -1) {
              hostDevicesText = scsiData.hostdevicestext[deviceIndex]
            }
          }
        }

        const xmlConfig = await this.generateXmlConfig(this.resource.hostDevicesName, hostDevicesText)

        await api('updateHostScsiDevices', {
          hostid: this.resource.id,
          hostdevicesname: this.resource.hostDevicesName,
          hostdevicestext: hostDevicesText || '',
          virtualmachineid: this.form.virtualmachineid,
          xmlconfig: xmlConfig,
          isattach: true
        })

        // 매핑된 디바이스 상태 업데이트
        this.$emit('mapped-device-updated', {
          deviceName: this.resource.hostDevicesName,
          deviceType: 'scsi',
          vmId: this.form.virtualmachineid,
          vmName: this.virtualmachines.find(vm => vm.id === this.form.virtualmachineid)?.displayname ||
                  this.virtualmachines.find(vm => vm.id === this.form.virtualmachineid)?.name || 'Unknown VM',
          isAttach: true
        })

        this.$message.success(this.$t('message.success.allocate.device'))

        // 할당 완료 후 이벤트 발생 순서 조정
        this.$emit('device-allocated')
        this.$emit('allocation-completed')

        // 모달 닫기 전에 잠시 대기하여 데이터 로드 완료 보장
        setTimeout(() => {
          this.$emit('close-action')
        }, 500)
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.loading = false
      }
    },

    async handleDelete () {
      if (this.deleteTargetType === 'scsi') {
        try {
          this.loading = true

          // VM 상태 확인 - 실행 중인 경우 할당 해제 불가
          if (this.deleteTargetVmId) {
            const vmResponse = await api('listVirtualMachines', {
              id: this.deleteTargetVmId,
              listall: true
            })
            const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

            if (vm && vm.state === 'Running') {
              this.$notification.warning({
                message: this.$t('label.warning'),
                description: this.$t('message.cannot.remove.device.vm.running')
              })
              this.loading = false
              return
            }
          }

          // LUN으로 동일 SCSI 주소가 붙어있다면 먼저 LUN 해제 시도
          const detachedLun = await this.tryDetachLunIfAllocatedByAddress()
          if (detachedLun) {
            this.$message.success(this.$t('message.success.remove.allocation'))
            this.$emit('allocation-completed')
            this.$emit('close-action')
            return
          }

          // 해제 시에는 현재 SCSI 디바이스 목록을 다시 조회하여 정확한 정보 사용
          const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
          const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

          let actualDeviceText = this.resource.hostDevicesText || ''
          if (scsiData && scsiData.hostdevicesname) {
            const deviceIndex = scsiData.hostdevicesname.indexOf(this.deleteTargetName || this.resource.hostDevicesName)
            if (deviceIndex !== -1 && scsiData.hostdevicestext) {
              actualDeviceText = scsiData.hostdevicestext[deviceIndex] || actualDeviceText
            }
          }

          const xmlConfig = await this.generateXmlConfig(this.deleteTargetName || this.resource.hostDevicesName, actualDeviceText)
          await api('updateHostScsiDevices', {
            hostid: this.resource.id,
            hostdevicesname: this.deleteTargetName || this.resource.hostDevicesName,
            virtualmachineid: null,
            currentvmid: this.deleteTargetVmId || null,
            xmlconfig: xmlConfig,
            isattach: false
          })
          this.$message.success(this.$t('message.success.remove.allocation'))
          this.$emit('allocation-completed')
          this.$emit('close-action')
        } catch (e) {
          this.$notifyError(e.message || 'Failed to deallocate SCSI device')
        } finally {
          this.loading = false
        }
        return
      }
      if (this.deleteTargetType === 'lun') {
        try {
          this.loading = true

          // VM 상태 확인 - 실행 중인 경우 할당 해제 불가
          if (this.deleteTargetVmId) {
            const vmResponse = await api('listVirtualMachines', {
              id: this.deleteTargetVmId,
              listall: true
            })
            const vm = vmResponse?.listvirtualmachinesresponse?.virtualmachine?.[0]

            if (vm && vm.state === 'Running') {
              this.$notification.warning({
                message: this.$t('label.warning'),
                description: this.$t('message.cannot.remove.device.vm.running')
              })
              this.loading = false
              return
            }
          }

          // 최소 LUN XML
          const lunXml = `
            <disk type='block' device='lun'>
              <driver name='qemu' type='raw' cache='none'/>
              <source dev='/dev/null'/>
            </disk>
          `.trim()
          await api('updateHostLunDevices', {
            hostid: this.resource.id,
            hostdevicesname: this.deleteTargetName,
            virtualmachineid: null,
            currentvmid: this.deleteTargetVmId,
            xmlconfig: lunXml,
            isattach: false
          })
          this.$message.success(this.$t('message.success.remove.allocation'))
          this.$emit('allocation-completed')
          this.$emit('close-action')
        } catch (e) {
          this.$notifyError(e.message || 'Failed to deallocate LUN device')
        } finally {
          this.loading = false
        }
      }
    },

    // SCSI_ADDRESS 기준으로 같은 물리 디바이스가 LUN에 할당되어 있으면 LUN을 먼저 해제
    async tryDetachLunIfAllocatedByAddress () {
      if (this.skipLunCrossCheck) {
        return false
      }
      try {
        const scsiAddr = this.extractAddressStringFromText(this.resource.hostDevicesText)
        if (!scsiAddr) {
          return false
        }

        const lunResp = await api('listHostLunDevices', { id: this.resource.id })
        const lun = lunResp?.listhostlundevicesresponse?.listhostlundevices?.[0]

        if (!lun || !Array.isArray(lun.hostdevicesname)) {
          return false
        }

        for (let i = 0; i < lun.hostdevicesname.length; i++) {
          const ltext = lun.hostdevicestext[i] || ''
          const laddr = this.extractAddressStringFromText(ltext)

          if (laddr && laddr === scsiAddr) {
            const vmId = lun.vmallocations?.[lun.hostdevicesname[i]]

            if (!vmId) {
              continue
            }

            // LUN 디바이스의 실제 XML 설정 사용
            const lunXml = this.generateLunXmlForDetach(lun.hostdevicesname[i], ltext)

            await api('updateHostLunDevices', {
              hostid: this.resource.id,
              hostdevicesname: lun.hostdevicesname[i],
              virtualmachineid: null,
              currentvmid: vmId,
              xmlconfig: lunXml,
              isattach: false
            })

            return true
          }
        }

        return false
      } catch (e) {
        return false
      }
    },

    // 텍스트에서 SCSI 주소 문자열 추출 (SCSI_ADDRESS: h:b:t:u 또는 [h:b:t:u])
    extractAddressStringFromText (text) {
      if (!text) return null
      let m = String(text).match(/SCSI_ADDRESS:\s*(\d+:\d+:\d+:\d+)/)
      if (m && m[1]) return m[1]
      m = String(text).match(/\[(\d+):(\d+):(\d+):(\d+)\]/)
      if (m) return `${m[1]}:${m[2]}:${m[3]}:${m[4]}`
      return null
    },

    generateLunXmlForDetach (deviceName, deviceText) {
      try {
        // deviceName에서 실제 디바이스 경로 추출
        const basePath = (deviceName || '').split(' (')[0]
        const byIdMatch = (deviceName || '').match(/\(([^)]+)\)/)

        // by-id 값을 우선적으로 사용
        let actualDevicePath = ''
        if (byIdMatch && byIdMatch[1]) {
          actualDevicePath = `/dev/disk/by-id/${byIdMatch[1]}`
        } else if (basePath && basePath.startsWith('/dev/disk/by-id/')) {
          actualDevicePath = basePath
        } else {
          actualDevicePath = basePath || '/dev/null'
        }

        // target dev 추출 - 디바이스 경로에서 추출하거나 SCSI 주소 정보에서 유추
        let targetDev = 'vda' // 기본값
        if (basePath) {
          // /dev/sda -> sda, /dev/sdb -> sdb
          targetDev = basePath.replace('/dev/', '').split(/[0-9]/)[0]
        } else {
          // basePath가 없으면 SCSI 주소 정보에서 유추
          const scsiAddr = this.extractAddressStringFromText(deviceText)
          if (scsiAddr) {
            const target = parseInt(scsiAddr.split(':')[2])
            // target 번호를 기반으로 sd[a-z] 생성
            targetDev = 'sd' + String.fromCharCode(97 + (target % 26))
          }
        }

        // SCSI 주소 추출
        const scsiAddr = this.extractAddressStringFromText(deviceText)
        const addressTag = scsiAddr
          ? `<address type='drive' controller='${scsiAddr.split(':')[0]}' bus='${scsiAddr.split(':')[1]}' target='${scsiAddr.split(':')[2]}' unit='${scsiAddr.split(':')[3]}'/>`
          : ''

        const xml = `
          <disk type='block' device='lun'>
            <driver name='qemu' type='raw' io='native' cache='none'/>
            <source dev='${actualDevicePath}'/>
            <target dev='${targetDev}' bus='scsi'/>
            ${addressTag}
            <!-- Fallback device path: ${basePath} -->
          </disk>
        `.trim()

        return xml
      } catch (error) {
        // 기본값 반환
        return `
          <disk type='block' device='lun'>
            <driver name='qemu' type='raw' cache='none'/>
            <source dev='/dev/null'/>
          </disk>
        `.trim()
      }
    },

    async detectAllocationState () {
      try {
        this.isDeleteMode = false
        this.deleteTargetType = null
        this.deleteTargetName = null
        this.deleteTargetVmId = null

        // 1) 동일 SCSI 항목에 할당되어 있으면 SCSI 삭제 모드
        const scsiResp = await api('listHostScsiDevices', { id: this.resource.id })
        const scsi = scsiResp?.listhostscsidevicesresponse?.listhostscsidevices?.[0]
        const vmId = scsi?.vmallocations?.[this.resource.hostDevicesName]
        if (vmId) {
          this.isDeleteMode = true
          this.deleteTargetType = 'scsi'
          this.deleteTargetName = this.resource.hostDevicesName
          this.deleteTargetVmId = vmId
          return
        }

        // 2) (옵션) LUN 교차 확인은 비활성화됨
        if (!this.skipLunCrossCheck) {
          const lunResp = await api('listHostLunDevices', { id: this.resource.id })
          const lun = lunResp?.listhostlundevicesresponse?.listhostlundevices?.[0]
          if (lun && lun.vmallocations && Array.isArray(lun.hostdevicesname)) {
            for (let i = 0; i < lun.hostdevicesname.length; i++) {
              const lunName = lun.hostdevicesname[i]
              const lvm = lun.vmallocations[lunName]
              if (!lvm) continue
              if (this.isSamePhysicalDevice(this.resource.hostDevicesName, lunName)) {
                this.isDeleteMode = true
                this.deleteTargetType = 'lun'
                this.deleteTargetName = lunName
                this.deleteTargetVmId = lvm
                return
              }
            }
          }
        }
      } catch (e) {
        // 무시
      }
    },

    async generateXmlConfig (hostDeviceName, hostDevicesText) {
      // hostDevicesText가 없으면 API를 통해 다시 조회
      let actualDeviceText = hostDevicesText

      if (!actualDeviceText) {
        try {
          const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
          const scsiData = scsiResponse.listhostscsidevicesresponse?.listhostscsidevices?.[0]

          if (scsiData && scsiData.hostdevicesname) {
            const deviceIndex = scsiData.hostdevicesname.indexOf(hostDeviceName)
            if (deviceIndex !== -1 && scsiData.hostdevicestext) {
              actualDeviceText = scsiData.hostdevicestext[deviceIndex]
            }
          }
        } catch (error) {
          console.warn(`[SCSI] Failed to fetch device text for ${hostDeviceName}:`, error)
        }
      }

      // 여전히 텍스트가 없으면 에러
      if (!actualDeviceText) {
        throw new Error(`Cannot generate SCSI XML: no device information available for ${hostDeviceName}`)
      }

      // actualDeviceText에서 [host:bus:target:unit] 추출
      const match = actualDeviceText.match(/\[(\d+):(\d+):(\d+):(\d+)\]/)
      if (!match) {
        throw new Error(`Cannot extract SCSI address from device text: ${actualDeviceText}`)
      }

      const host = match[1]
      const bus = match[2]
      const target = match[3]
      const unit = match[4]

      const adapterName = `scsi_host${host}`

      return `
        <hostdev mode='subsystem' type='scsi'>
          <source>
            <adapter name='${adapterName}'/>
            <address bus='${bus}' target='${target}' unit='${unit}'/>
          </source>
        </hostdev>
      `.trim()
    },

    closeAction () {
      this.$emit('close-action')
    },

    filterOption (input, option) {
      return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
    },

    async checkDeviceAllocationInLun (deviceName) {
      try {
        // 현재 선택된 호스트에서만 LUN 디바이스 할당 상태 확인
        if (!this.resource?.id) return false
        const lunResponse = await api('listHostLunDevices', { id: this.resource.id })
        const lunDevices = lunResponse?.listhostlundevicesresponse?.listhostlundevices?.[0]
        if (lunDevices && lunDevices.vmallocations) {
          for (const [lunDeviceName, vmId] of Object.entries(lunDevices.vmallocations)) {
            if (vmId && this.isSamePhysicalDevice(deviceName, lunDeviceName)) {
              return true
            }
          }
        }
        return false
      } catch (error) {
        // 미지원 호스트 등은 교차 할당 없음으로 처리
        return false
      }
    },

    isSamePhysicalDevice (scsiDevice, lunDevice) {
      // 디바이스 이름에서 물리적 디바이스 경로 추출
      const scsiBase = this.extractDeviceBase(scsiDevice)
      const lunBase = this.extractDeviceBase(lunDevice)

      // 같은 물리적 디바이스인지 확인
      return scsiBase === lunBase || this.areRelatedDevices(scsiBase, lunBase)
    },

    extractDeviceBase (deviceName) {
      // 디바이스 이름에서 기본 경로 추출
      if (deviceName.includes('(')) {
        // 괄호 안의 by-id 값에서 기본 디바이스 추출
        const match = deviceName.match(/\(([^)]+)\)/)
        if (match) {
          const byIdName = match[1]
          // by-id 이름에서 실제 디바이스 경로 추출
          if (byIdName.startsWith('scsi-')) {
            return byIdName
          }
        }
      }

      // 직접적인 디바이스 경로인 경우
      if (deviceName.startsWith('/dev/')) {
        return deviceName
      }

      return deviceName
    },

    areRelatedDevices (device1, device2) {
      // SCSI 주소 기반으로 관련 디바이스인지 확인
      return device1 === device2
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
