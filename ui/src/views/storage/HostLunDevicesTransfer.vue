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
      currentVmName: '',
      // UI 전환 및 교차 타입 삭제 타겟
      isDeleteMode: false,
      deleteTargetType: null, // 'lun' | 'scsi'
      deleteTargetName: null,
      deleteTargetVmId: null
    }
  },
  created () {
    if (this.resource && this.resource.id) {
      // LUN이나 dm이 포함된 디바이스는 할당 불가
      const deviceName = String(this.resource.hostDevicesName || '')
      if (deviceName.toUpperCase().includes('LUN') || deviceName.toLowerCase().includes('dm')) {
        this.$notification.warning({
          message: this.$t('label.warning'),
          description: 'LUN 또는 dm이 포함된 디바이스는 할당할 수 없습니다.'
        })
        this.$emit('close-action')
        return
      }

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
    async refreshVMList () {
      if (!this.resource || !this.resource.id) {
        this.loading = false
        return Promise.reject(new Error('Invalid resource'))
      }

      this.loading = true
      const params = { hostid: this.resource.id, details: 'all', listall: true }
      const vmStates = ['Running']

      try {
        const [vmArrays, lunResponse] = await Promise.all([
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
        ])

        const vms = vmArrays.flat()
        const lunDevices = lunResponse.listhostlundevicesresponse?.listhostlundevices?.[0]
        const allocatedVmIds = new Set()

        // 모든 LUN 디바이스에 할당된 VM ID 수집 (다중 할당 허용하므로 모든 할당 유지)
        if (lunDevices?.vmallocations) {
          for (const [deviceName, vmId] of Object.entries(lunDevices.vmallocations)) {
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
                    const xmlConfig = this.generateXmlConfig(deviceName)
                    await api('updateHostLunDevices', {
                      hostid: this.resource.id,
                      hostdevicesname: deviceName,
                      virtualmachineid: null,
                      xmlconfig: xmlConfig,
                      isattach: false
                    })
                  } catch (error) {
                    // Failed to automatically deallocate LUN device
                  }
                }
              } catch (error) {
                // VM 조회 실패 시에도 할당 해제 시도
                try {
                  const xmlConfig = this.generateXmlConfig(deviceName)
                  await api('updateHostLunDevices', {
                    hostid: this.resource.id,
                    hostdevicesname: deviceName,
                    virtualmachineid: null,
                    xmlconfig: xmlConfig,
                    isattach: false
                  })
                } catch (detachError) {
                  // Failed to automatically deallocate LUN device after error
                }
              }
            }
          }
        }

        // 현재 디바이스에 할당된 VM ID는 제외하지 않음 (다중 할당 허용)
        // 모든 VM을 표시하되, 할당된 VM은 별도 표시

        // 모든 VM을 표시 (할당된 VM도 포함)
        const detailedVms = await Promise.all(vms.map(vm => {
          return api('listVirtualMachines', {
            id: vm.id,
            details: 'all'
          }).then(detailResponse => {
            const detailedVm = detailResponse.listvirtualmachinesresponse.virtualmachine[0]
            return {
              ...detailedVm,
              instanceId: vm.instanceId,
              isAllocated: allocatedVmIds.has(vm.instanceId?.toString())
            }
          })
        }))

        this.virtualmachines = detailedVms
        // 모달 오픈 시 현재/교차 할당 상태 감지
        await this.detectAllocationState()
      } catch (error) {
        this.$notifyError(error.message || 'Failed to fetch VMs')
      } finally {
        this.loading = false
      }
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
        // 교차 할당(SCSI) 감지 시, 먼저 SCSI 해제 후 종료
        const detachedByScsi = await this.tryDetachScsiIfAllocatedByAddress()
        if (detachedByScsi) {
          this.$message.success(this.$t('message.success.remove.allocation'))
          this.$emit('allocation-completed')
          this.$emit('close-action')
          return
        }

        const xmlConfig = this.generateXmlConfig()

        await api('updateHostLunDevices', {
          hostid: this.resource.id,
          hostdevicesname: this.resource.hostDevicesName,
          hostdevicestext: this.resource.hostDevicesText || '',
          virtualmachineid: this.form.virtualmachineid,
          xmlconfig: xmlConfig,
          isattach: true
        })

        // 매핑된 디바이스 상태 업데이트
        this.$emit('mapped-device-updated', {
          deviceName: this.resource.hostDevicesName,
          deviceType: 'lun',
          vmId: this.form.virtualmachineid,
          vmName: this.virtualmachines.find(vm => vm.id === this.form.virtualmachineid)?.displayname ||
                  this.virtualmachines.find(vm => vm.id === this.form.virtualmachineid)?.name || 'Unknown VM',
          isAttach: true
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

    async tryDetachScsiIfAllocatedByAddress () {
      try {
        const lunAddr = this.extractAddressStringFromText(this.resource.hostDevicesText)
        if (!lunAddr) {
          return false
        }

        const scsiResp = await api('listHostScsiDevices', { id: this.resource.id })
        const scsi = scsiResp?.listhostscsidevicesresponse?.listhostscsidevices?.[0]

        if (!scsi || !Array.isArray(scsi.hostdevicesname)) {
          return false
        }

        for (let i = 0; i < scsi.hostdevicesname.length; i++) {
          const stext = scsi.hostdevicestext[i] || ''
          const saddr = this.extractAddressStringFromText(stext)

          if (saddr && saddr === lunAddr) {
            const vmId = scsi.vmallocations?.[scsi.hostdevicesname[i]]

            if (!vmId) {
              continue
            }

            // SCSI 디바이스의 실제 XML 설정 사용
            const scsiXml = this.generateScsiXmlForDetach(scsi.hostdevicesname[i], stext)

            await api('updateHostScsiDevices', {
              hostid: this.resource.id,
              hostdevicesname: scsi.hostdevicesname[i],
              virtualmachineid: null,
              currentvmid: vmId,
              xmlconfig: scsiXml,
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

    extractAddressStringFromText (text) {
      if (!text) return null
      let m = String(text).match(/SCSI_ADDRESS:\s*(\d+:\d+:\d+:\d+)/)
      if (m && m[1]) return m[1]
      m = String(text).match(/\[(\d+):(\d+):(\d+):(\d+)\]/)
      if (m) return `${m[1]}:${m[2]}:${m[3]}:${m[4]}`
      return null
    },

    generateScsiXmlForDetach (deviceName, deviceText) {
      try {
        // SCSI 디바이스의 기본 XML 구조 생성
        return `
          <hostdev mode='subsystem' type='scsi'>
            <source>
              <adapter name='scsi_host0'/>
              <address bus='0' target='0' unit='0'/>
            </source>
          </hostdev>
        `.trim()
      } catch (error) {
        // 기본값 반환
        return `
          <hostdev mode='subsystem' type='scsi'>
            <source>
              <adapter name='scsi_host0'/>
              <address bus='0' target='0' unit='0'/>
            </source>
          </hostdev>
        `.trim()
      }
    },

    async handleDelete () {
      // deleteTargetType 에 따라 적절한 API 호출
      if (this.deleteTargetType === 'lun') {
        await this.handleDeallocate()
        return
      }
      if (this.deleteTargetType === 'scsi') {
        try {
          this.loading = true
          // 최소 XML (백엔드에서 실제 부착 여부 확인 후 분기)
          const scsiXml = `
            <hostdev mode='subsystem' type='scsi'>
              <source>
                <adapter name='scsi_host0'/>
                <address bus='0' target='0' unit='0'/>
              </source>
            </hostdev>
          `.trim()

          await api('updateHostScsiDevices', {
            hostid: this.resource.id,
            hostdevicesname: this.deleteTargetName,
            virtualmachineid: null,
            currentvmid: this.deleteTargetVmId,
            xmlconfig: scsiXml,
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

        // VM 상태 확인 - 실행 중인 경우 할당 해제 불가
        const vmResponse = await api('listVirtualMachines', {
          id: vmId,
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
          throw new Error(detachResponse?.error?.errortext || 'Failed to detach LUN device')
        }

        // 매핑된 디바이스 상태 업데이트
        this.$emit('mapped-device-updated', {
          deviceName: hostDevicesName,
          deviceType: 'lun',
          vmId: vmId,
          vmName: this.vmName || 'Unknown VM',
          isAttach: false
        })

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

    async detectAllocationState () {
      try {
        this.isDeleteMode = false
        this.deleteTargetType = null
        this.deleteTargetName = null
        this.deleteTargetVmId = null

        // 1) 동일 LUN 항목에 할당되어 있으면 LUN 삭제 모드
        const lunResp = await api('listHostLunDevices', { id: this.resource.id })
        const lun = lunResp.listhostlundevicesresponse?.listhostlundevices?.[0]
        const lunVm = lun?.vmallocations?.[this.resource.hostDevicesName]
        if (lunVm) {
          this.isDeleteMode = true
          this.deleteTargetType = 'lun'
          this.deleteTargetName = this.resource.hostDevicesName
          this.deleteTargetVmId = lunVm
          return
        }

        // 2) SCSI 쪽에 같은 물리 디바이스가 할당되어 있으면 SCSI 삭제 모드
        const scsiResp = await api('listHostScsiDevices', { id: this.resource.id })
        const scsi = scsiResp?.listhostscsidevicesresponse?.listhostscsidevices?.[0]
        if (scsi && scsi.vmallocations && Array.isArray(scsi.hostdevicesname)) {
          for (let i = 0; i < scsi.hostdevicesname.length; i++) {
            const scsiName = scsi.hostdevicesname[i]
            const vmId = scsi.vmallocations[scsiName]
            if (!vmId) continue
            if (this.isSamePhysicalDevice(this.resource.hostDevicesName, scsiName)) {
              this.isDeleteMode = true
              this.deleteTargetType = 'scsi'
              this.deleteTargetName = scsiName
              this.deleteTargetVmId = vmId
              return
            }
          }
        }
      } catch (e) {
        // 무시하고 기본 모드 유지
      }
    },

    generateXmlConfig (devicePath) {
      const targetDevicePath = devicePath || this.resource.hostDevicesName

      const basePath = (targetDevicePath || '').split(' (')[0]
      const byIdMatch = (targetDevicePath || '').match(/\(([^)]+)\)/)

      // by-id 값을 우선적으로 사용
      let actualDevicePath = ''
      if (byIdMatch && byIdMatch[1]) {
        actualDevicePath = `/dev/disk/by-id/${byIdMatch[1]}`
      } else if (basePath && basePath.startsWith('/dev/disk/by-id/')) {
        actualDevicePath = basePath
      } else {
        actualDevicePath = basePath
      }

      // LUN 디바이스 XML 생성 (target은 백엔드에서 동적 할당)
      const targetDev = basePath.replace('/dev/', '')
      return `
        <disk type='block' device='lun'>
          <driver name='qemu' type='raw' io='native' cache='none'/>
          <source dev='${actualDevicePath}'/>
          <target dev='${targetDev}' bus='scsi'/>
        </disk>
      `.trim()
    },

    extractScsiAddressFromHostDevicesText () {
      // hostDevicesText에서 SCSI 주소 추출
      const hostDevicesText = this.resource.hostDevicesText

      // SCSI_ADDRESS가 다른 속성에 있는지 확인
      for (const [, value] of Object.entries(this.resource)) {
        if (typeof value === 'string' && value.includes('SCSI_ADDRESS')) {
          const scsiAddressMatch = value.match(/SCSI_ADDRESS:\s*(\d+):(\d+):(\d+):(\d+)/)
          if (scsiAddressMatch) {
            const host = scsiAddressMatch[1]
            const bus = scsiAddressMatch[2]
            const target = scsiAddressMatch[3]
            const unit = scsiAddressMatch[4]
            return `<address type='drive' controller='${host}' bus='${bus}' target='${target}' unit='${unit}'/>`
          }
        }
      }

      // hostDevicesText에서 SCSI_ADDRESS 패턴 찾기
      if (hostDevicesText) {
        const scsiAddressMatch = hostDevicesText.match(/SCSI_ADDRESS:\s*(\d+):(\d+):(\d+):(\d+)/)
        if (scsiAddressMatch) {
          const host = scsiAddressMatch[1]
          const bus = scsiAddressMatch[2]
          const target = scsiAddressMatch[3]
          const unit = scsiAddressMatch[4]
          return `<address type='drive' controller='${host}' bus='${bus}' target='${target}' unit='${unit}'/>`
        }
      }

      // 패턴을 찾을 수 없으면 기본값 반환
      return '<address type=\'drive\' controller=\'0\' bus=\'0\' target=\'48\' unit=\'0\'/>'
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
    },

    async checkDeviceAllocationInScsi (deviceName) {
      try {
        // 현재 선택된 호스트에서만 SCSI 디바이스 할당 상태 확인
        if (!this.resource?.id) return false
        const scsiResponse = await api('listHostScsiDevices', { id: this.resource.id })
        const scsiDevices = scsiResponse?.listhostscsidevicesresponse?.listhostscsidevices?.[0]
        if (scsiDevices && scsiDevices.vmallocations) {
          for (const [scsiDeviceName, vmId] of Object.entries(scsiDevices.vmallocations)) {
            if (vmId && this.isSamePhysicalDevice(deviceName, scsiDeviceName)) {
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

    isSamePhysicalDevice (lunDevice, scsiDevice) {
      // 디바이스 이름에서 물리적 디바이스 경로 추출
      const lunBase = this.extractDeviceBase(lunDevice)
      const scsiBase = this.extractDeviceBase(scsiDevice)

      // 같은 물리적 디바이스인지 확인 (예: /dev/sda와 /dev/sg0는 같은 물리적 디바이스)
      return lunBase === scsiBase || this.areRelatedDevices(lunBase, scsiBase)
    },

    extractDeviceBase (deviceName) {
      // 디바이스 이름에서 기본 경로 추출
      if (deviceName.includes('(')) {
        // 괄호 안의 by-id 값에서 기본 디바이스 추출
        const match = deviceName.match(/\(([^)]+)\)/)
        if (match) {
          const byIdName = match[1]
          // by-id 이름에서 실제 디바이스 경로 추출 (예: scsi-xxx -> sda)
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
      // 실제 구현에서는 백엔드의 디바이스 매핑 정보를 사용할 수 있음
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
